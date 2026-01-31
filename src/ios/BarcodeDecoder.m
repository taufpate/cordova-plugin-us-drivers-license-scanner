/**
 * BarcodeDecoder.m
 *
 * PDF417 barcode decoder using ZXingObjC with strategy-rotation image processing.
 *
 * To avoid starving the decoder of fresh frames, only TWO strategies are
 * tried per frame: the original (HybridBinarizer) plus ONE rotating enhanced
 * strategy. The enhanced strategies cycle across consecutive frames:
 *
 *   Frame N+0: original + GlobalHistogramBinarizer
 *   Frame N+1: original + sharpened-contrast
 *   Frame N+2: original + high-contrast-grayscale
 *   Frame N+3: original + anti-reflection
 *   Frame N+4: original + rotated-90°
 *   Frame N+5: original + luminance-sharpen (blur recovery)
 *   Frame N+6: original + downscale-50% (blur averaging)
 *   Frame N+7: original + large-radius deblur
 *   Frame N+8: (cycle repeats)
 *
 * This keeps per-frame processing ≈25ms instead of ≈80ms when all ran,
 * preventing the `isProcessing` flag from blocking fresh frames too long.
 *
 * CGImage is extracted on the calling thread (video queue) while the
 * CMSampleBufferRef is still valid, then dispatched to the decode queue.
 */

#import "BarcodeDecoder.h"
#import <ZXingObjC/ZXingObjC.h>
#import <CoreImage/CoreImage.h>

// Debounce interval to avoid reporting same barcode repeatedly
static NSTimeInterval const kDebounceInterval = 1.0;

// Maximum dimension for barcode analysis — matches the 1920x1080 camera preset
static CGFloat const kMaxAnalysisDimension = 1080.0;

// Number of enhanced strategies to rotate through (excludes the original which always runs).
// 8 strategies = full cycle in ~1.1s at 15 delivered frames/sec with skip=2
static NSInteger const kEnhancedStrategyCount = 8;

@interface BarcodeDecoder ()

@property (nonatomic, strong) ZXMultiFormatReader *reader;
@property (nonatomic, strong) ZXDecodeHints *decodeHints;
@property (nonatomic, strong) dispatch_queue_t decodeQueue;
@property (nonatomic, strong) CIContext *ciContext;
@property (nonatomic, assign) BOOL isProcessing;
@property (nonatomic, strong) NSString *lastDecodedData;
@property (nonatomic, strong) NSDate *lastDecodeTime;

// Strategy rotation index — cycles 0..4 across frames so each frame only
// runs the original + ONE enhanced strategy instead of all 6.
@property (nonatomic, assign) NSInteger strategyIndex;

@end

@implementation BarcodeDecoder

#pragma mark - Initialization

- (instancetype)init {
    self = [super init];
    if (self) {
        _decodeQueue = dispatch_queue_create("com.sos.barcode.decode", DISPATCH_QUEUE_SERIAL);
        _isProcessing = NO;

        _strategyIndex = 0;

        // GPU-accelerated Core Image context, reused for every frame
        _ciContext = [CIContext contextWithOptions:@{kCIContextUseSoftwareRenderer: @NO}];

        [self setupReader];
    }
    return self;
}

- (void)setupReader {
    // Configure hints once and reuse (avoid per-frame allocation)
    self.decodeHints = [ZXDecodeHints hints];

    // Primary format: PDF417 (used by driver licenses)
    [self.decodeHints addPossibleFormat:kBarcodeFormatPDF417];

    // Enable harder decoding for damaged/blurry barcodes
    self.decodeHints.tryHarder = YES;

    // Use ISO-8859-1 character set (common for AAMVA data)
    self.decodeHints.encoding = NSISOLatin1StringEncoding;

    self.reader = [ZXMultiFormatReader reader];

    NSLog(@"[BarcodeDecoder] Initialized with PDF417 reader + multi-strategy pipeline");
}

#pragma mark - Public Methods

- (void)decodeSampleBuffer:(CMSampleBufferRef)sampleBuffer {
    // Skip if already processing a frame
    if (self.isProcessing) {
        return;
    }

    // CRITICAL: Extract CGImage NOW on the calling thread (video queue)
    // while the CMSampleBufferRef is still valid. The buffer may be recycled
    // by AVFoundation before a dispatched block runs on the decode queue.
    CGImageRef cgImage = [self extractCGImageFromBuffer:sampleBuffer];
    if (!cgImage) {
        return;
    }

    self.isProcessing = YES;

    // Dispatch the independent CGImage copy to the decode queue
    dispatch_async(self.decodeQueue, ^{
        @autoreleasepool {
            [self decodeCGImageAndReport:cgImage];
            CGImageRelease(cgImage);
            self.isProcessing = NO;
        }
    });
}

- (void)decodeImage:(UIImage *)image {
    if (!image || self.isProcessing) {
        return;
    }

    self.isProcessing = YES;

    dispatch_async(self.decodeQueue, ^{
        @autoreleasepool {
            UIImage *scaled = [self scaleImageForAnalysis:image];
            [self processImage:scaled];
            self.isProcessing = NO;
        }
    });
}

- (void)reset {
    self.lastDecodedData = nil;
    self.lastDecodeTime = nil;
    self.isProcessing = NO;
    self.strategyIndex = 0;
}

#pragma mark - Buffer → CGImage Extraction (called on video queue)

- (CGImageRef)extractCGImageFromBuffer:(CMSampleBufferRef)sampleBuffer {
    CVImageBufferRef imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
    if (!imageBuffer) {
        return NULL;
    }

    CVPixelBufferLockBaseAddress(imageBuffer, kCVPixelBufferLock_ReadOnly);

    size_t width = CVPixelBufferGetWidth(imageBuffer);
    size_t height = CVPixelBufferGetHeight(imageBuffer);
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(imageBuffer);
    void *baseAddress = CVPixelBufferGetBaseAddress(imageBuffer);

    if (!baseAddress) {
        CVPixelBufferUnlockBaseAddress(imageBuffer, kCVPixelBufferLock_ReadOnly);
        return NULL;
    }

    // Create CGImage directly from pixel buffer data
    CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
    CGContextRef context = CGBitmapContextCreate(
        baseAddress,
        width,
        height,
        8,
        bytesPerRow,
        colorSpace,
        kCGBitmapByteOrder32Little | kCGImageAlphaPremultipliedFirst
    );

    // CGBitmapContextCreateImage makes an independent copy of the pixel data
    CGImageRef cgImage = CGBitmapContextCreateImage(context);

    // Release buffer lock immediately — cgImage has its own copy
    CVPixelBufferUnlockBaseAddress(imageBuffer, kCVPixelBufferLock_ReadOnly);
    CGContextRelease(context);
    CGColorSpaceRelease(colorSpace);

    return cgImage; // Caller must CGImageRelease
}

#pragma mark - Multi-Strategy Decode (called on decode queue)

- (void)decodeCGImageAndReport:(CGImageRef)cgImage {
    if (!cgImage) {
        return;
    }

    NSString *result = nil;
    NSString *strategy = nil;

    // ---- Always try the original image first (HybridBinarizer) ----
    result = [self decodeFromCGImage:cgImage];
    if (result) {
        strategy = @"original";
    }

    // ---- If original failed, try ONE rotating enhanced strategy ----
    // This avoids the ~80ms cost of trying all 5 enhanced strategies
    // every frame, which was blocking fresh frames and causing "stuck".
    if (!result) {
        NSInteger idx = self.strategyIndex % kEnhancedStrategyCount;
        self.strategyIndex++;

        switch (idx) {
            case 0: {
                // GlobalHistogramBinarizer — different thresholding
                result = [self decodeFromCGImageWithGlobalBinarizer:cgImage];
                if (result) strategy = @"global-histogram";
                break;
            }
            case 1: {
                // Sharpened + contrast + desaturated
                CGImageRef enhanced = [self createSharpenedContrastImage:cgImage];
                if (enhanced) {
                    result = [self decodeFromCGImage:enhanced];
                    if (result) strategy = @"sharpened-contrast";
                    CGImageRelease(enhanced);
                }
                break;
            }
            case 2: {
                // High-contrast grayscale (near-binary)
                CGImageRef grayscale = [self createHighContrastGrayscaleImage:cgImage];
                if (grayscale) {
                    result = [self decodeFromCGImage:grayscale];
                    if (result) strategy = @"high-contrast-grayscale";
                    CGImageRelease(grayscale);
                }
                break;
            }
            case 3: {
                // Anti-reflection
                CGImageRef antiReflect = [self createAntiReflectionImage:cgImage];
                if (antiReflect) {
                    result = [self decodeFromCGImage:antiReflect];
                    if (result) strategy = @"anti-reflection";
                    CGImageRelease(antiReflect);
                }
                break;
            }
            case 4: {
                // 90° rotation fallback
                CGImageRef rotated = [self createRotatedCGImage:cgImage degrees:90];
                if (rotated) {
                    result = [self decodeFromCGImage:rotated];
                    if (result) strategy = @"rotated-90";
                    CGImageRelease(rotated);
                }
                break;
            }
            case 5: {
                // Luminance-only sharpening — recovers edges without color artifacts
                CGImageRef sharpLum = [self createLuminanceSharpenedImage:cgImage];
                if (sharpLum) {
                    result = [self decodeFromCGImage:sharpLum];
                    if (result) strategy = @"luminance-sharpen";
                    CGImageRelease(sharpLum);
                }
                break;
            }
            case 6: {
                // Downscale 50% — pixel averaging smooths out blur/noise,
                // ZXing can decode better from a smaller, cleaner image
                CGImageRef downscaled = [self createDownscaledImage:cgImage scale:0.5];
                if (downscaled) {
                    result = [self decodeFromCGImage:downscaled];
                    if (result) strategy = @"downscale-50";
                    CGImageRelease(downscaled);
                }
                break;
            }
            case 7: {
                // Large-radius deblur — specifically targets defocus blur
                CGImageRef deblurred = [self createDeblurredImage:cgImage];
                if (deblurred) {
                    result = [self decodeFromCGImage:deblurred];
                    if (result) strategy = @"deblur";
                    CGImageRelease(deblurred);
                }
                break;
            }
            default:
                break;
        }
    }

    // Report
    if (result) {
        NSLog(@"[BarcodeDecoder] Decoded via strategy '%@', length=%lu",
              strategy, (unsigned long)result.length);

        if ([self isValidAAMVAData:result]) {
            [self handleDecodedData:result];
        } else {
            NSLog(@"[BarcodeDecoder] AAMVA validation FAILED for strategy '%@'", strategy);
        }
    }
}

#pragma mark - Core Image Processing Strategies

/**
 * Sharpens barcode module edges (unsharp mask), increases contrast,
 * and removes colour information so the binarizer sees cleaner edges.
 * Best for: slightly worn or slightly blurry barcodes.
 */
- (CGImageRef)createSharpenedContrastImage:(CGImageRef)source {
    if (!source) return NULL;

    CIImage *ciImage = [CIImage imageWithCGImage:source];

    // Unsharp mask — emphasises barcode module boundaries
    CIFilter *sharpen = [CIFilter filterWithName:@"CIUnsharpMask"];
    [sharpen setValue:ciImage forKey:kCIInputImageKey];
    [sharpen setValue:@(2.5) forKey:@"inputIntensity"];
    [sharpen setValue:@(1.5) forKey:@"inputRadius"];

    CIImage *sharpened = sharpen.outputImage;
    if (!sharpened) return NULL;

    // Increase contrast + full desaturation
    CIFilter *controls = [CIFilter filterWithName:@"CIColorControls"];
    [controls setValue:sharpened forKey:kCIInputImageKey];
    [controls setValue:@(0.5) forKey:@"inputContrast"];
    [controls setValue:@(0.0) forKey:@"inputBrightness"];
    [controls setValue:@(0.0) forKey:@"inputSaturation"];

    CIImage *output = controls.outputImage;
    if (!output) return NULL;

    return [self.ciContext createCGImage:output fromRect:output.extent];
}

/**
 * Full grayscale with aggressive contrast — pushes the image toward binary.
 * Best for: heavily worn, faded, or low-contrast barcodes where modules
 * are barely distinguishable from the background.
 */
- (CGImageRef)createHighContrastGrayscaleImage:(CGImageRef)source {
    if (!source) return NULL;

    CIImage *ciImage = [CIImage imageWithCGImage:source];

    // Full desaturation + heavy contrast + slight brightness lift
    CIFilter *controls = [CIFilter filterWithName:@"CIColorControls"];
    [controls setValue:ciImage forKey:kCIInputImageKey];
    [controls setValue:@(1.5) forKey:@"inputContrast"];
    [controls setValue:@(0.05) forKey:@"inputBrightness"];
    [controls setValue:@(-1.0) forKey:@"inputSaturation"];

    CIImage *output = controls.outputImage;
    if (!output) return NULL;

    return [self.ciContext createCGImage:output fromRect:output.extent];
}

/**
 * Counteracts reflections and glare by compressing highlights and
 * lifting shadows, then boosting contrast on the balanced image.
 * Best for: shiny/laminated licenses with specular reflections
 * washing out parts of the barcode.
 */
- (CGImageRef)createAntiReflectionImage:(CGImageRef)source {
    if (!source) return NULL;

    CIImage *ciImage = [CIImage imageWithCGImage:source];

    // Tame highlights (reflections) and recover shadow detail
    CIFilter *hlShadow = [CIFilter filterWithName:@"CIHighlightShadowAdjust"];
    [hlShadow setValue:ciImage forKey:kCIInputImageKey];
    [hlShadow setValue:@(-0.8) forKey:@"inputHighlightAmount"];
    [hlShadow setValue:@(0.6) forKey:@"inputShadowAmount"];

    CIImage *balanced = hlShadow.outputImage;
    if (!balanced) return NULL;

    // Boost contrast + desaturate on the now-balanced image
    CIFilter *controls = [CIFilter filterWithName:@"CIColorControls"];
    [controls setValue:balanced forKey:kCIInputImageKey];
    [controls setValue:@(0.8) forKey:@"inputContrast"];
    [controls setValue:@(0.0) forKey:@"inputBrightness"];
    [controls setValue:@(0.0) forKey:@"inputSaturation"];

    CIImage *output = controls.outputImage;
    if (!output) return NULL;

    return [self.ciContext createCGImage:output fromRect:output.extent];
}

/**
 * Luminance-only sharpening using CISharpenLuminance.
 * Unlike CIUnsharpMask, this only sharpens the brightness channel,
 * avoiding colour artifacts that can confuse the binarizer.
 * Best for: moderate blur where barcode edges are soft but visible.
 */
- (CGImageRef)createLuminanceSharpenedImage:(CGImageRef)source {
    if (!source) return NULL;

    CIImage *ciImage = [CIImage imageWithCGImage:source];

    CIFilter *sharpen = [CIFilter filterWithName:@"CISharpenLuminance"];
    [sharpen setValue:ciImage forKey:kCIInputImageKey];
    [sharpen setValue:@(2.0) forKey:@"inputSharpness"];

    CIImage *sharpened = sharpen.outputImage;
    if (!sharpened) return NULL;

    // Desaturate + light contrast for cleaner binarization
    CIFilter *controls = [CIFilter filterWithName:@"CIColorControls"];
    [controls setValue:sharpened forKey:kCIInputImageKey];
    [controls setValue:@(0.3) forKey:@"inputContrast"];
    [controls setValue:@(0.0) forKey:@"inputSaturation"];

    CIImage *output = controls.outputImage;
    if (!output) return NULL;

    return [self.ciContext createCGImage:output fromRect:output.extent];
}

/**
 * Downscales the image by the given factor using high-quality interpolation.
 * Downscaling averages neighbouring pixels, which smooths out blur and noise
 * at the subpixel level. ZXing's binarizer can sometimes decode a smaller,
 * cleaner image where it fails on the full-resolution blurry one.
 * Best for: motion blur and camera shake.
 */
- (CGImageRef)createDownscaledImage:(CGImageRef)source scale:(CGFloat)scale {
    if (!source) return NULL;

    size_t width = CGImageGetWidth(source);
    size_t height = CGImageGetHeight(source);
    size_t newWidth = (size_t)(width * scale);
    size_t newHeight = (size_t)(height * scale);

    if (newWidth == 0 || newHeight == 0) return NULL;

    CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
    CGContextRef context = CGBitmapContextCreate(
        NULL,
        newWidth,
        newHeight,
        8,
        newWidth * 4,
        colorSpace,
        kCGBitmapByteOrder32Little | kCGImageAlphaPremultipliedFirst
    );
    CGColorSpaceRelease(colorSpace);

    if (!context) return NULL;

    // High-quality interpolation averages blur into cleaner pixels
    CGContextSetInterpolationQuality(context, kCGInterpolationHigh);
    CGContextDrawImage(context, CGRectMake(0, 0, newWidth, newHeight), source);

    CGImageRef downscaled = CGBitmapContextCreateImage(context);
    CGContextRelease(context);

    return downscaled; // Caller must CGImageRelease
}

/**
 * Large-radius unsharp mask targeting defocus blur specifically.
 * A bigger radius (8px) captures the blur spread and compensates for it,
 * unlike the small-radius sharpen in strategy 1 which targets fine detail.
 * Combined with desaturation and contrast boost for cleaner binarization.
 * Best for: out-of-focus barcodes where the camera didn't lock focus properly.
 */
- (CGImageRef)createDeblurredImage:(CGImageRef)source {
    if (!source) return NULL;

    CIImage *ciImage = [CIImage imageWithCGImage:source];

    // Large-radius unsharp mask — counteracts defocus blur
    CIFilter *sharpen = [CIFilter filterWithName:@"CIUnsharpMask"];
    [sharpen setValue:ciImage forKey:kCIInputImageKey];
    [sharpen setValue:@(3.0) forKey:@"inputIntensity"];
    [sharpen setValue:@(8.0) forKey:@"inputRadius"];

    CIImage *deblurred = sharpen.outputImage;
    if (!deblurred) return NULL;

    // Desaturate + moderate contrast boost
    CIFilter *controls = [CIFilter filterWithName:@"CIColorControls"];
    [controls setValue:deblurred forKey:kCIInputImageKey];
    [controls setValue:@(0.6) forKey:@"inputContrast"];
    [controls setValue:@(0.0) forKey:@"inputBrightness"];
    [controls setValue:@(0.0) forKey:@"inputSaturation"];

    CIImage *output = controls.outputImage;
    if (!output) return NULL;

    return [self.ciContext createCGImage:output fromRect:output.extent];
}

#pragma mark - ZXing Decode Helpers

/**
 * Primary decode path using ZXHybridBinarizer (adaptive local thresholding).
 */
- (NSString *)decodeFromCGImage:(CGImageRef)cgImage {
    if (!cgImage) {
        return nil;
    }

    @try {
        ZXCGImageLuminanceSource *source = [[ZXCGImageLuminanceSource alloc] initWithCGImage:cgImage];
        ZXHybridBinarizer *binarizer = [[ZXHybridBinarizer alloc] initWithSource:source];
        ZXBinaryBitmap *bitmap = [[ZXBinaryBitmap alloc] initWithBinarizer:binarizer];

        NSError *error = nil;
        ZXResult *zxResult = [self.reader decode:bitmap hints:self.decodeHints error:&error];

        if (zxResult && zxResult.text) {
            return zxResult.text;
        }

    } @catch (NSException *exception) {
        // Decode exceptions are expected for non-barcode frames
    }

    return nil;
}

/**
 * Alternative decode path using ZXGlobalHistogramBinarizer.
 * Uses a single global threshold computed from the image histogram.
 * Can outperform HybridBinarizer in uniformly-lit conditions.
 */
- (NSString *)decodeFromCGImageWithGlobalBinarizer:(CGImageRef)cgImage {
    if (!cgImage) {
        return nil;
    }

    @try {
        ZXCGImageLuminanceSource *source = [[ZXCGImageLuminanceSource alloc] initWithCGImage:cgImage];
        ZXGlobalHistogramBinarizer *binarizer = [[ZXGlobalHistogramBinarizer alloc] initWithSource:source];
        ZXBinaryBitmap *bitmap = [[ZXBinaryBitmap alloc] initWithBinarizer:binarizer];

        NSError *error = nil;
        ZXResult *zxResult = [self.reader decode:bitmap hints:self.decodeHints error:&error];

        if (zxResult && zxResult.text) {
            return zxResult.text;
        }

    } @catch (NSException *exception) {
        // Expected for non-barcode frames
    }

    return nil;
}

- (NSString *)decodeFromImage:(UIImage *)image {
    if (!image) {
        return nil;
    }

    CGImageRef cgImage = image.CGImage;
    if (!cgImage) {
        return nil;
    }

    return [self decodeFromCGImage:cgImage];
}

#pragma mark - Image Scaling

- (UIImage *)scaleImageForAnalysis:(UIImage *)image {
    CGFloat width = image.size.width;
    CGFloat height = image.size.height;

    if (width <= kMaxAnalysisDimension && height <= kMaxAnalysisDimension) {
        return image;
    }

    CGFloat scale = MIN(kMaxAnalysisDimension / width, kMaxAnalysisDimension / height);
    CGSize newSize = CGSizeMake(width * scale, height * scale);

    UIGraphicsBeginImageContextWithOptions(newSize, YES, 1.0);
    [image drawInRect:CGRectMake(0, 0, newSize.width, newSize.height)];
    UIImage *scaledImage = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();

    return scaledImage;
}

- (void)processImage:(UIImage *)image {
    NSString *result = [self decodeFromImage:image];

    if (!result) {
        UIImage *rotated90 = [self rotateImage:image byDegrees:90];
        result = [self decodeFromImage:rotated90];
    }

    if (result && [self isValidAAMVAData:result]) {
        [self handleDecodedData:result];
    }
}

#pragma mark - CGImage Rotation

- (CGImageRef)createRotatedCGImage:(CGImageRef)source degrees:(CGFloat)degrees {
    if (!source) {
        return NULL;
    }

    CGFloat radians = degrees * M_PI / 180.0;
    size_t width = CGImageGetWidth(source);
    size_t height = CGImageGetHeight(source);

    // For 90/270 degree rotation, swap width and height
    size_t newWidth = height;
    size_t newHeight = width;

    CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
    CGContextRef context = CGBitmapContextCreate(
        NULL,
        newWidth,
        newHeight,
        8,
        newWidth * 4,
        colorSpace,
        kCGBitmapByteOrder32Little | kCGImageAlphaPremultipliedFirst
    );
    CGColorSpaceRelease(colorSpace);

    if (!context) {
        return NULL;
    }

    CGContextTranslateCTM(context, newWidth / 2.0, newHeight / 2.0);
    CGContextRotateCTM(context, radians);
    CGContextDrawImage(context, CGRectMake(-width / 2.0, -height / 2.0, width, height), source);

    CGImageRef rotated = CGBitmapContextCreateImage(context);
    CGContextRelease(context);

    return rotated; // Caller must CGImageRelease
}

#pragma mark - Result Handling

- (void)handleDecodedData:(NSString *)data {
    // Apply debounce
    NSDate *now = [NSDate date];

    if (self.lastDecodedData && [self.lastDecodedData isEqualToString:data]) {
        if (self.lastDecodeTime && [now timeIntervalSinceDate:self.lastDecodeTime] < kDebounceInterval) {
            return; // Debounced
        }
    }

    self.lastDecodedData = data;
    self.lastDecodeTime = now;

    NSLog(@"[BarcodeDecoder] Successfully decoded barcode, length=%lu", (unsigned long)data.length);

    dispatch_async(dispatch_get_main_queue(), ^{
        [self.delegate barcodeDecoder:self didDecodeBarcode:data];
    });
}

#pragma mark - Validation

- (BOOL)isValidAAMVAData:(NSString *)data {
    if (!data || data.length < 20) {
        return NO;
    }

    // AAMVA data typically starts with @ and contains ANSI identifier
    BOOL hasStartMarker = [data hasPrefix:@"@"] ||
                          [data containsString:@"@\n"] ||
                          [data containsString:@"@\r"];

    BOOL hasANSIMarker = [data containsString:@"ANSI"];

    // Check for common field codes
    BOOL hasFieldCodes = [data containsString:@"DAQ"] ||  // License number
                         [data containsString:@"DCS"] ||  // Last name
                         [data containsString:@"DAC"] ||  // First name
                         [data containsString:@"DBB"];    // Date of birth

    return (hasStartMarker || hasANSIMarker) && hasFieldCodes;
}

#pragma mark - Legacy Image Rotation (for decodeImage: path)

- (UIImage *)rotateImage:(UIImage *)image byDegrees:(CGFloat)degrees {
    if (!image) {
        return nil;
    }

    CGImageRef rotated = [self createRotatedCGImage:image.CGImage degrees:degrees];
    if (!rotated) {
        return nil;
    }

    UIImage *result = [UIImage imageWithCGImage:rotated];
    CGImageRelease(rotated);

    return result;
}

@end
