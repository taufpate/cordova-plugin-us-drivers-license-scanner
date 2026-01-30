/**
 * ImageUtils.m
 *
 * Image utility methods implementation.
 */

#import "ImageUtils.h"

// Standard driver license aspect ratio (width:height)
static CGFloat const kLicenseAspectRatio = 1.586;

// Maximum image dimension for encoding
static CGFloat const kMaxDimension = 2048;

// Default JPEG quality
static NSInteger const kDefaultQuality = 85;

@implementation ImageUtils

#pragma mark - Base64 Conversion

+ (NSString *)imageToBase64:(UIImage *)image format:(NSString *)format quality:(NSInteger)quality {
    if (!image) {
        NSLog(@"[ImageUtils] Cannot encode nil image");
        return @"";
    }

    @try {
        // Scale down if necessary
        UIImage *scaledImage = [self ensureMaxDimension:image maxSize:kMaxDimension];

        NSData *imageData;

        if ([[format uppercaseString] isEqualToString:@"PNG"]) {
            imageData = UIImagePNGRepresentation(scaledImage);
        } else {
            CGFloat compressionQuality = quality / 100.0;
            imageData = UIImageJPEGRepresentation(scaledImage, compressionQuality);
        }

        if (!imageData) {
            NSLog(@"[ImageUtils] Failed to get image data");
            return @"";
        }

        NSString *base64 = [imageData base64EncodedStringWithOptions:0];
        NSLog(@"[ImageUtils] Encoded image to Base64, size=%lu", (unsigned long)base64.length);
        return base64;

    } @catch (NSException *exception) {
        NSLog(@"[ImageUtils] Exception encoding image: %@", exception);
        return @"";
    }
}

+ (UIImage *)base64ToImage:(NSString *)base64 {
    if (!base64 || base64.length == 0) {
        return nil;
    }

    @try {
        NSData *imageData = [[NSData alloc] initWithBase64EncodedString:base64 options:0];
        return [UIImage imageWithData:imageData];
    } @catch (NSException *exception) {
        NSLog(@"[ImageUtils] Exception decoding Base64: %@", exception);
        return nil;
    }
}

#pragma mark - Image Scaling

+ (UIImage *)ensureMaxDimension:(UIImage *)image maxSize:(CGFloat)maxDimension {
    if (!image) {
        return nil;
    }

    CGFloat width = image.size.width;
    CGFloat height = image.size.height;

    if (width <= maxDimension && height <= maxDimension) {
        return image; // No scaling needed
    }

    CGFloat scale = MIN(maxDimension / width, maxDimension / height);
    CGSize newSize = CGSizeMake(width * scale, height * scale);

    UIGraphicsBeginImageContextWithOptions(newSize, NO, image.scale);
    [image drawInRect:CGRectMake(0, 0, newSize.width, newSize.height)];
    UIImage *scaledImage = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();

    return scaledImage;
}

#pragma mark - Orientation Normalization

+ (UIImage *)normalizeOrientation:(UIImage *)image {
    if (!image) {
        return nil;
    }

    // If orientation is already Up, the CGImage pixels are in display orientation
    if (image.imageOrientation == UIImageOrientationUp) {
        return image;
    }

    NSLog(@"[ImageUtils] Normalizing orientation from %ld, size=%@",
          (long)image.imageOrientation, NSStringFromCGSize(image.size));

    // Draw into a new context to apply the orientation transform.
    // UIKit's drawInRect: automatically applies the imageOrientation rotation,
    // producing a new image with pixels in the correct display orientation.
    UIGraphicsBeginImageContextWithOptions(image.size, NO, image.scale);
    [image drawInRect:CGRectMake(0, 0, image.size.width, image.size.height)];
    UIImage *normalizedImage = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();

    if (normalizedImage) {
        NSLog(@"[ImageUtils] Normalized to size=%@, CGImage=%zux%zu",
              NSStringFromCGSize(normalizedImage.size),
              CGImageGetWidth(normalizedImage.CGImage),
              CGImageGetHeight(normalizedImage.CGImage));
        return normalizedImage;
    }

    return image;
}

#pragma mark - Portrait Extraction

+ (UIImage *)extractPortraitDeterministic:(UIImage *)licenseImage {
    if (!licenseImage) {
        NSLog(@"[ImageUtils] Cannot extract portrait from nil image");
        return nil;
    }

    // Normalize orientation so CGImage pixels match display coordinates
    UIImage *normalized = [self normalizeOrientation:licenseImage];

    // First crop to license aspect ratio to isolate the license region.
    // The full camera image is larger than the license; cropping centers on
    // the license area so the face-position percentages below are meaningful.
    UIImage *licenseCropped = [self cropToLicenseAspectRatio:normalized];

    CGFloat width = licenseCropped.size.width;
    CGFloat height = licenseCropped.size.height;

    // After aspect-ratio crop, determine if the license region is landscape
    BOOL isLandscape = width > height;

    NSLog(@"[ImageUtils] Deterministic portrait extraction: license crop=%@, isLandscape=%d",
          NSStringFromCGSize(licenseCropped.size), isLandscape);

    CGFloat portraitX, portraitY, portraitWidth, portraitHeight;

    if (isLandscape) {
        // Standard horizontal license orientation
        // Portrait photo is typically on the left side, vertically centered
        portraitX = width * 0.03;       // 3% from left
        portraitY = height * 0.12;      // 12% from top
        portraitWidth = width * 0.26;   // 26% of width
        portraitHeight = height * 0.55; // 55% of height
    } else {
        // Vertical orientation (less common)
        portraitX = width * 0.10;       // 10% from left
        portraitY = height * 0.05;      // 5% from top
        portraitWidth = width * 0.35;   // 35% of width
        portraitHeight = height * 0.30; // 30% of height
    }

    // Ensure bounds are valid
    portraitX = MAX(0, portraitX);
    portraitY = MAX(0, portraitY);
    portraitWidth = MIN(portraitWidth, width - portraitX);
    portraitHeight = MIN(portraitHeight, height - portraitY);

    if (portraitWidth <= 0 || portraitHeight <= 0) {
        NSLog(@"[ImageUtils] Invalid portrait crop dimensions");
        return nil;
    }

    CGRect cropRect = CGRectMake(portraitX, portraitY, portraitWidth, portraitHeight);

    NSLog(@"[ImageUtils] Portrait crop rect: x=%.0f y=%.0f w=%.0f h=%.0f",
          cropRect.origin.x, cropRect.origin.y, cropRect.size.width, cropRect.size.height);

    CGImageRef croppedCGImage = CGImageCreateWithImageInRect(licenseCropped.CGImage, cropRect);
    if (!croppedCGImage) {
        NSLog(@"[ImageUtils] Failed to crop portrait image");
        return nil;
    }

    UIImage *croppedImage = [UIImage imageWithCGImage:croppedCGImage
                                                scale:licenseCropped.scale
                                          orientation:UIImageOrientationUp];
    CGImageRelease(croppedCGImage);

    return croppedImage;
}

#pragma mark - Image Rotation

+ (UIImage *)rotateImage:(UIImage *)image byDegrees:(CGFloat)degrees {
    if (!image || degrees == 0) {
        return image;
    }

    CGFloat radians = degrees * M_PI / 180.0;

    // Calculate new size
    CGRect rect = CGRectMake(0, 0, image.size.width, image.size.height);
    CGAffineTransform transform = CGAffineTransformMakeRotation(radians);
    CGRect rotatedRect = CGRectApplyAffineTransform(rect, transform);

    UIGraphicsBeginImageContextWithOptions(rotatedRect.size, NO, image.scale);
    CGContextRef context = UIGraphicsGetCurrentContext();

    // Move origin to center
    CGContextTranslateCTM(context, rotatedRect.size.width / 2, rotatedRect.size.height / 2);

    // Rotate
    CGContextRotateCTM(context, radians);

    // Draw
    [image drawInRect:CGRectMake(-image.size.width / 2, -image.size.height / 2,
                                  image.size.width, image.size.height)];

    UIImage *rotatedImage = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();

    return rotatedImage;
}

#pragma mark - Aspect Ratio Cropping

+ (UIImage *)cropToLicenseAspectRatio:(UIImage *)image {
    if (!image) {
        return nil;
    }

    CGFloat width = image.size.width;
    CGFloat height = image.size.height;
    CGFloat currentRatio = width / height;

    CGFloat newWidth, newHeight, x, y;

    if (currentRatio > kLicenseAspectRatio) {
        // Image is too wide, crop horizontally
        newHeight = height;
        newWidth = height * kLicenseAspectRatio;
        x = (width - newWidth) / 2;
        y = 0;
    } else {
        // Image is too tall, crop vertically
        newWidth = width;
        newHeight = width / kLicenseAspectRatio;
        x = 0;
        y = (height - newHeight) / 2;
    }

    CGRect cropRect = CGRectMake(x, y, newWidth, newHeight);

    CGImageRef croppedCGImage = CGImageCreateWithImageInRect(image.CGImage, cropRect);
    if (!croppedCGImage) {
        return image;
    }

    UIImage *croppedImage = [UIImage imageWithCGImage:croppedCGImage
                                                scale:image.scale
                                          orientation:image.imageOrientation];
    CGImageRelease(croppedCGImage);

    return croppedImage;
}

@end
