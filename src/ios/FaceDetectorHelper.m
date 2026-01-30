/**
 * FaceDetectorHelper.m
 *
 * Vision framework face detection implementation for portrait extraction.
 * Detects face on a driver license photo and crops it with padding.
 */

#import "FaceDetectorHelper.h"
#import <Vision/Vision.h>

// Padding factor around detected face for the crop
static CGFloat const kFacePaddingFactor = 0.35;

// Minimum face area relative to image area.
// A face on a license held at scanning distance is typically 0.3-1% of the full
// camera frame, so we set a low threshold to avoid rejecting real license faces.
static CGFloat const kMinFaceSizeRatio = 0.002;

// Maximum face area relative to image area
static CGFloat const kMaxFaceSizeRatio = 0.70;

@interface FaceDetectorHelper ()

@property (nonatomic, strong) dispatch_queue_t detectionQueue;

@end

@implementation FaceDetectorHelper

#pragma mark - Initialization

- (instancetype)init {
    self = [super init];
    if (self) {
        _detectionQueue = dispatch_queue_create("com.sos.facedetector", DISPATCH_QUEUE_SERIAL);
    }
    return self;
}

#pragma mark - Public Methods

- (void)detectFaceInImage:(UIImage *)image {
    if (!image) {
        NSLog(@"[FaceDetector] Invalid image provided");
        [self notifyNoFaceDetected];
        return;
    }

    dispatch_async(self.detectionQueue, ^{
        [self performFaceDetection:image];
    });
}

#pragma mark - Orientation Normalization

- (UIImage *)normalizeImageOrientation:(UIImage *)image {
    if (image.imageOrientation == UIImageOrientationUp) {
        return image;
    }

    // Draw into a new context to apply the orientation transform.
    // This creates a new CGImage with pixels in the correct display orientation,
    // so CGImage dimensions match UIImage.size and Vision bounding boxes align
    // with the pixel data used by CGImageCreateWithImageInRect.
    UIGraphicsBeginImageContextWithOptions(image.size, NO, image.scale);
    [image drawInRect:CGRectMake(0, 0, image.size.width, image.size.height)];
    UIImage *normalized = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();

    NSLog(@"[FaceDetector] Normalized image orientation from %ld to Up",
          (long)image.imageOrientation);

    return normalized ?: image;
}

#pragma mark - Face Detection

- (void)performFaceDetection:(UIImage *)image {
    // Normalize orientation so CGImage pixels match UIImage display coordinates.
    // Without this, Vision bounding boxes (relative to raw CGImage) don't match
    // the crop coordinates (computed from UIImage.size), causing wrong region extraction.
    UIImage *normalizedImage = [self normalizeImageOrientation:image];

    CGImageRef cgImage = normalizedImage.CGImage;
    if (!cgImage) {
        NSLog(@"[FaceDetector] Could not get CGImage from UIImage");
        [self notifyNoFaceDetected];
        return;
    }

    size_t cgWidth = CGImageGetWidth(cgImage);
    size_t cgHeight = CGImageGetHeight(cgImage);

    NSLog(@"[FaceDetector] Analyzing image: UIImage size=%@, CGImage=%zux%zu, orientation=%ld",
          NSStringFromCGSize(normalizedImage.size), cgWidth, cgHeight,
          (long)normalizedImage.imageOrientation);

    // Create face detection request
    VNDetectFaceRectanglesRequest *request = [[VNDetectFaceRectanglesRequest alloc]
        initWithCompletionHandler:^(VNRequest *request, NSError *error) {
            if (error) {
                NSLog(@"[FaceDetector] Detection error: %@", error);
                [self notifyError:error];
                return;
            }

            [self handleDetectionResults:request.results forImage:normalizedImage];
        }];

    // No orientation parameter needed since the image is already normalized (orientation=Up).
    // Vision and CGImageCreateWithImageInRect now operate in the same coordinate space.
    VNImageRequestHandler *handler = [[VNImageRequestHandler alloc]
        initWithCGImage:cgImage
                options:@{}];

    NSError *error = nil;
    if (![handler performRequests:@[request] error:&error]) {
        NSLog(@"[FaceDetector] Failed to perform request: %@", error);
        [self notifyError:error];
    }
}

- (void)handleDetectionResults:(NSArray<VNFaceObservation *> *)results forImage:(UIImage *)image {
    if (!results || results.count == 0) {
        NSLog(@"[FaceDetector] No faces detected in image");
        [self notifyNoFaceDetected];
        return;
    }

    NSLog(@"[FaceDetector] Detected %lu face(s)", (unsigned long)results.count);

    // Log all detected faces for debugging
    CGFloat imageArea = image.size.width * image.size.height;
    for (NSUInteger i = 0; i < results.count; i++) {
        VNFaceObservation *face = results[i];
        CGRect bbox = face.boundingBox;
        CGFloat faceArea = (bbox.size.width * image.size.width) * (bbox.size.height * image.size.height);
        CGFloat areaRatio = faceArea / imageArea;
        NSLog(@"[FaceDetector] Face %lu: bbox=(%.3f, %.3f, %.3f, %.3f) confidence=%.2f areaRatio=%.4f",
              (unsigned long)i, bbox.origin.x, bbox.origin.y,
              bbox.size.width, bbox.size.height,
              face.confidence, areaRatio);
    }

    // Find the best face
    VNFaceObservation *bestFace = [self findBestFaceInResults:results forImage:image];

    if (!bestFace) {
        NSLog(@"[FaceDetector] No suitable face found (all rejected by size filter)");
        [self notifyNoFaceDetected];
        return;
    }

    // Extract face region from the normalized image
    UIImage *croppedFace = [self extractFaceRegion:bestFace fromImage:image];

    if (croppedFace) {
        NSLog(@"[FaceDetector] Successfully extracted face portrait, size=%@",
              NSStringFromCGSize(croppedFace.size));
        [self notifyFaceDetected:croppedFace];
    } else {
        NSLog(@"[FaceDetector] Failed to crop face region");
        [self notifyNoFaceDetected];
    }
}

- (VNFaceObservation *)findBestFaceInResults:(NSArray<VNFaceObservation *> *)results
                                    forImage:(UIImage *)image {
    CGFloat imageWidth = image.size.width;
    CGFloat imageHeight = image.size.height;
    CGFloat imageArea = imageWidth * imageHeight;

    VNFaceObservation *bestFace = nil;
    CGFloat bestScore = -1;

    for (VNFaceObservation *face in results) {
        CGRect normalizedBounds = face.boundingBox;

        // Calculate face area ratio
        CGFloat faceWidth = normalizedBounds.size.width * imageWidth;
        CGFloat faceHeight = normalizedBounds.size.height * imageHeight;
        CGFloat faceArea = faceWidth * faceHeight;
        CGFloat areaRatio = faceArea / imageArea;

        // Skip faces outside valid size range
        if (areaRatio < kMinFaceSizeRatio) {
            NSLog(@"[FaceDetector] Face rejected: too small (ratio=%.4f, min=%.4f)",
                  areaRatio, kMinFaceSizeRatio);
            continue;
        }
        if (areaRatio > kMaxFaceSizeRatio) {
            NSLog(@"[FaceDetector] Face rejected: too large (ratio=%.4f, max=%.4f)",
                  areaRatio, kMaxFaceSizeRatio);
            continue;
        }

        // Score: prioritize larger faces (the license face should be the most prominent).
        // Size accounts for 70% of the score. Position accounts for 30%.
        CGFloat sizeScore = areaRatio;

        // Position scoring: US driver licenses typically have the photo on the left side.
        // In a portrait camera frame, the left side of the license (held horizontally)
        // maps to the left-center of the frame.
        CGFloat faceCenterX = normalizedBounds.origin.x + normalizedBounds.size.width / 2.0;
        CGFloat faceCenterY = normalizedBounds.origin.y + normalizedBounds.size.height / 2.0;

        // Prefer faces in the left-center area of the frame
        // Vision coordinate system: (0,0) = bottom-left, (1,1) = top-right
        CGFloat positionScore = 1.0 - (fabs(faceCenterX - 0.35) + fabs(faceCenterY - 0.5)) / 2.0;
        positionScore = MAX(0, positionScore);

        CGFloat totalScore = sizeScore * 0.7 + positionScore * 0.3;

        NSLog(@"[FaceDetector] Face score: size=%.4f pos=%.4f total=%.4f (center=%.2f,%.2f)",
              sizeScore, positionScore, totalScore, faceCenterX, faceCenterY);

        if (totalScore > bestScore) {
            bestScore = totalScore;
            bestFace = face;
        }
    }

    if (bestFace) {
        NSLog(@"[FaceDetector] Selected face with score=%.4f", bestScore);
    }

    return bestFace;
}

- (UIImage *)extractFaceRegion:(VNFaceObservation *)face fromImage:(UIImage *)image {
    CGRect normalizedBounds = face.boundingBox;

    // Since the image is normalized (orientation=Up), UIImage.size matches
    // CGImage pixel dimensions. Both Vision and CGImageCreateWithImageInRect
    // operate in the same coordinate space.
    CGFloat imageWidth = image.size.width;
    CGFloat imageHeight = image.size.height;

    // Convert from Vision coordinates (bottom-left origin, normalized)
    // to UIKit/CGImage coordinates (top-left origin, pixels)
    CGFloat x = normalizedBounds.origin.x * imageWidth;
    CGFloat y = (1.0 - normalizedBounds.origin.y - normalizedBounds.size.height) * imageHeight;
    CGFloat width = normalizedBounds.size.width * imageWidth;
    CGFloat height = normalizedBounds.size.height * imageHeight;

    NSLog(@"[FaceDetector] Face pixel coords: x=%.0f y=%.0f w=%.0f h=%.0f (image: %.0fx%.0f)",
          x, y, width, height, imageWidth, imageHeight);

    // Add padding around face for a natural portrait crop
    CGFloat paddingX = width * kFacePaddingFactor;
    CGFloat paddingY = height * kFacePaddingFactor;

    CGFloat left = MAX(0, x - paddingX);
    CGFloat top = MAX(0, y - paddingY);
    CGFloat right = MIN(imageWidth, x + width + paddingX);
    CGFloat bottom = MIN(imageHeight, y + height + paddingY);

    CGRect cropRect = CGRectMake(left, top, right - left, bottom - top);

    NSLog(@"[FaceDetector] Crop rect with padding: x=%.0f y=%.0f w=%.0f h=%.0f",
          cropRect.origin.x, cropRect.origin.y, cropRect.size.width, cropRect.size.height);

    // Validate crop rect
    if (cropRect.size.width <= 0 || cropRect.size.height <= 0) {
        NSLog(@"[FaceDetector] Invalid crop rect dimensions");
        return nil;
    }

    // Crop the image
    CGImageRef cgImage = CGImageCreateWithImageInRect(image.CGImage, cropRect);
    if (!cgImage) {
        NSLog(@"[FaceDetector] CGImageCreateWithImageInRect failed");
        return nil;
    }

    UIImage *croppedImage = [UIImage imageWithCGImage:cgImage
                                                scale:image.scale
                                          orientation:UIImageOrientationUp];
    CGImageRelease(cgImage);

    return croppedImage;
}

#pragma mark - Delegate Notifications

- (void)notifyFaceDetected:(UIImage *)croppedFace {
    dispatch_async(dispatch_get_main_queue(), ^{
        [self.delegate faceDetectorHelper:self didDetectFace:croppedFace];
    });
}

- (void)notifyNoFaceDetected {
    dispatch_async(dispatch_get_main_queue(), ^{
        [self.delegate faceDetectorHelperDidNotDetectFace:self];
    });
}

- (void)notifyError:(NSError *)error {
    dispatch_async(dispatch_get_main_queue(), ^{
        if ([self.delegate respondsToSelector:@selector(faceDetectorHelper:didFailWithError:)]) {
            [self.delegate faceDetectorHelper:self didFailWithError:error];
        } else {
            [self.delegate faceDetectorHelperDidNotDetectFace:self];
        }
    });
}

@end
