/**
 * BarcodeDecoder.m
 *
 * PDF417 barcode decoder implementation using ZXingObjC.
 * Fixed: CGImage is extracted on the calling thread (video queue) while the
 * CMSampleBufferRef is still valid, then dispatched to the decode queue.
 */

#import "BarcodeDecoder.h"
#import <ZXingObjC/ZXingObjC.h>

// Debounce interval to avoid reporting same barcode repeatedly
static NSTimeInterval const kDebounceInterval = 1.0;

// Maximum dimension for barcode analysis (smaller = less memory, still enough for PDF417)
static CGFloat const kMaxAnalysisDimension = 720.0;

@interface BarcodeDecoder ()

@property (nonatomic, strong) ZXMultiFormatReader *reader;
@property (nonatomic, strong) ZXDecodeHints *decodeHints;
@property (nonatomic, strong) dispatch_queue_t decodeQueue;
@property (nonatomic, assign) BOOL isProcessing;
@property (nonatomic, strong) NSString *lastDecodedData;
@property (nonatomic, strong) NSDate *lastDecodeTime;

@end

@implementation BarcodeDecoder

#pragma mark - Initialization

- (instancetype)init {
    self = [super init];
    if (self) {
        _decodeQueue = dispatch_queue_create("com.sos.barcode.decode", DISPATCH_QUEUE_SERIAL);
        _isProcessing = NO;

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

    NSLog(@"[BarcodeDecoder] Initialized with PDF417 reader");
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
            // Scale down before processing
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

#pragma mark - Decode CGImage (called on decode queue)

- (void)decodeCGImageAndReport:(CGImageRef)cgImage {
    if (!cgImage) {
        return;
    }

    size_t imgW = CGImageGetWidth(cgImage);
    size_t imgH = CGImageGetHeight(cgImage);
    NSLog(@"[BarcodeDecoder] Analyzing frame %zux%zu", imgW, imgH);

    // Try original orientation
    NSString *result = [self decodeFromCGImage:cgImage];

    // Try 90-degree rotation if original fails (portrait vs landscape mismatch)
    if (!result) {
        CGImageRef rotated90 = [self createRotatedCGImage:cgImage degrees:90];
        if (rotated90) {
            result = [self decodeFromCGImage:rotated90];
            CGImageRelease(rotated90);
        }
    }

    // Try 270-degree rotation as last resort
    if (!result) {
        CGImageRef rotated270 = [self createRotatedCGImage:cgImage degrees:270];
        if (rotated270) {
            result = [self decodeFromCGImage:rotated270];
            CGImageRelease(rotated270);
        }
    }

    if (result) {
        NSLog(@"[BarcodeDecoder] ZXing decoded data length=%lu, first 60: %@",
              (unsigned long)result.length,
              [result substringToIndex:MIN(60, result.length)]);

        if ([self isValidAAMVAData:result]) {
            NSLog(@"[BarcodeDecoder] AAMVA validation passed");
            [self handleDecodedData:result];
        } else {
            NSLog(@"[BarcodeDecoder] AAMVA validation FAILED for decoded data");
        }
    }
}

#pragma mark - Image Processing

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
    // Try original orientation
    NSString *result = [self decodeFromImage:image];

    // Only try 90-degree rotation (covers the most common alternative orientation)
    if (!result) {
        UIImage *rotated90 = [self rotateImage:image byDegrees:90];
        result = [self decodeFromImage:rotated90];
    }

    if (result && [self isValidAAMVAData:result]) {
        [self handleDecodedData:result];
    }
}

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
