/**
 * BarcodeDecoder.m
 *
 * PDF417 barcode decoder implementation using ZXingObjC.
 */

#import "BarcodeDecoder.h"
#import <ZXingObjC/ZXingObjC.h>

// Debounce interval to avoid reporting same barcode repeatedly
static NSTimeInterval const kDebounceInterval = 1.0;

@interface BarcodeDecoder ()

@property (nonatomic, strong) ZXMultiFormatReader *reader;
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
    // Configure hints for PDF417 decoding
    ZXDecodeHints *hints = [ZXDecodeHints hints];

    // Primary format: PDF417 (used by driver licenses)
    [hints addPossibleFormat:kBarcodeFormatPDF417];

    // Fallback formats
    [hints addPossibleFormat:kBarcodeFormatDataMatrix];
    [hints addPossibleFormat:kBarcodeFormatQRCode];

    // Enable harder decoding for damaged/blurry barcodes
    hints.tryHarder = YES;

    // Use ISO-8859-1 character set (common for AAMVA data)
    hints.assumedCharacterSet = @"ISO-8859-1";

    self.reader = [ZXMultiFormatReader reader];

    NSLog(@"[BarcodeDecoder] Initialized with PDF417 reader");
}

#pragma mark - Public Methods

- (void)decodeSampleBuffer:(CMSampleBufferRef)sampleBuffer {
    // Skip if already processing
    if (self.isProcessing) {
        return;
    }

    self.isProcessing = YES;

    // Convert sample buffer to image on decode queue
    dispatch_async(self.decodeQueue, ^{
        @autoreleasepool {
            UIImage *image = [self imageFromSampleBuffer:sampleBuffer];
            if (image) {
                [self processImage:image];
            }
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
            [self processImage:image];
            self.isProcessing = NO;
        }
    });
}

- (void)reset {
    self.lastDecodedData = nil;
    self.lastDecodeTime = nil;
}

#pragma mark - Image Processing

- (UIImage *)imageFromSampleBuffer:(CMSampleBufferRef)sampleBuffer {
    CVImageBufferRef imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
    if (!imageBuffer) {
        return nil;
    }

    CVPixelBufferLockBaseAddress(imageBuffer, kCVPixelBufferLock_ReadOnly);

    void *baseAddress = CVPixelBufferGetBaseAddress(imageBuffer);
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(imageBuffer);
    size_t width = CVPixelBufferGetWidth(imageBuffer);
    size_t height = CVPixelBufferGetHeight(imageBuffer);

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

    CGImageRef cgImage = CGBitmapContextCreateImage(context);

    CVPixelBufferUnlockBaseAddress(imageBuffer, kCVPixelBufferLock_ReadOnly);
    CGContextRelease(context);
    CGColorSpaceRelease(colorSpace);

    if (!cgImage) {
        return nil;
    }

    UIImage *image = [UIImage imageWithCGImage:cgImage];
    CGImageRelease(cgImage);

    return image;
}

- (void)processImage:(UIImage *)image {
    // Try original orientation
    NSString *result = [self decodeFromImage:image];

    if (!result) {
        // Try rotated 90 degrees
        UIImage *rotated90 = [self rotateImage:image byDegrees:90];
        result = [self decodeFromImage:rotated90];
    }

    if (!result) {
        // Try rotated 180 degrees
        UIImage *rotated180 = [self rotateImage:image byDegrees:180];
        result = [self decodeFromImage:rotated180];
    }

    if (!result) {
        // Try rotated 270 degrees
        UIImage *rotated270 = [self rotateImage:image byDegrees:270];
        result = [self decodeFromImage:rotated270];
    }

    if (result && [self isValidAAMVAData:result]) {
        [self handleDecodedData:result];
    }
}

- (NSString *)decodeFromImage:(UIImage *)image {
    if (!image) {
        return nil;
    }

    @try {
        CGImageRef cgImage = image.CGImage;
        if (!cgImage) {
            return nil;
        }

        ZXCGImageLuminanceSource *source = [[ZXCGImageLuminanceSource alloc] initWithCGImage:cgImage];
        ZXHybridBinarizer *binarizer = [[ZXHybridBinarizer alloc] initWithSource:source];
        ZXBinaryBitmap *bitmap = [[ZXBinaryBitmap alloc] initWithBinarizer:binarizer];

        ZXDecodeHints *hints = [ZXDecodeHints hints];
        [hints addPossibleFormat:kBarcodeFormatPDF417];
        hints.tryHarder = YES;

        NSError *error = nil;
        ZXResult *zxResult = [self.reader decode:bitmap hints:hints error:&error];

        if (zxResult && zxResult.text) {
            return zxResult.text;
        }

    } @catch (NSException *exception) {
        NSLog(@"[BarcodeDecoder] Exception during decode: %@", exception);
    }

    return nil;
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

#pragma mark - Image Rotation

- (UIImage *)rotateImage:(UIImage *)image byDegrees:(CGFloat)degrees {
    if (!image) {
        return nil;
    }

    CGFloat radians = degrees * M_PI / 180.0;

    // Calculate new size
    CGRect rect = CGRectMake(0, 0, image.size.width, image.size.height);
    CGAffineTransform transform = CGAffineTransformMakeRotation(radians);
    CGRect rotatedRect = CGRectApplyAffineTransform(rect, transform);

    // Create rotated context
    UIGraphicsBeginImageContextWithOptions(rotatedRect.size, NO, image.scale);
    CGContextRef context = UIGraphicsGetCurrentContext();

    // Move origin to center
    CGContextTranslateCTM(context, rotatedRect.size.width / 2, rotatedRect.size.height / 2);

    // Rotate
    CGContextRotateCTM(context, radians);

    // Draw image
    [image drawInRect:CGRectMake(-image.size.width / 2, -image.size.height / 2,
                                  image.size.width, image.size.height)];

    UIImage *rotatedImage = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();

    return rotatedImage;
}

@end
