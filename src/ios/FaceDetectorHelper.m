/**
 * FaceDetectorHelper.m
 *
 * Vision framework face detection implementation for portrait extraction.
 */

#import "FaceDetectorHelper.h"
#import <Vision/Vision.h>

// Padding factor to add around detected face
static CGFloat const kFacePaddingFactor = 0.3;

// Minimum face size relative to image
static CGFloat const kMinFaceSizeRatio = 0.05;

// Maximum face size relative to image
static CGFloat const kMaxFaceSizeRatio = 0.7;

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

#pragma mark - Face Detection

- (void)performFaceDetection:(UIImage *)image {
    // Get CGImage from UIImage
    CGImageRef cgImage = image.CGImage;
    if (!cgImage) {
        NSLog(@"[FaceDetector] Could not get CGImage from UIImage");
        [self notifyNoFaceDetected];
        return;
    }

    // Create face detection request
    VNDetectFaceRectanglesRequest *request = [[VNDetectFaceRectanglesRequest alloc] initWithCompletionHandler:^(VNRequest *request, NSError *error) {
        if (error) {
            NSLog(@"[FaceDetector] Detection error: %@", error);
            [self notifyError:error];
            return;
        }

        [self handleDetectionResults:request.results forImage:image];
    }];

    // Create image request handler
    VNImageRequestHandler *handler = [[VNImageRequestHandler alloc] initWithCGImage:cgImage options:@{}];

    // Perform request
    NSError *error = nil;
    if (![handler performRequests:@[request] error:&error]) {
        NSLog(@"[FaceDetector] Failed to perform request: %@", error);
        [self notifyError:error];
    }
}

- (void)handleDetectionResults:(NSArray<VNFaceObservation *> *)results forImage:(UIImage *)image {
    if (!results || results.count == 0) {
        NSLog(@"[FaceDetector] No faces detected");
        [self notifyNoFaceDetected];
        return;
    }

    NSLog(@"[FaceDetector] Detected %lu face(s)", (unsigned long)results.count);

    // Find the best face
    VNFaceObservation *bestFace = [self findBestFaceInResults:results forImage:image];

    if (!bestFace) {
        NSLog(@"[FaceDetector] No suitable face found");
        [self notifyNoFaceDetected];
        return;
    }

    // Extract face region
    UIImage *croppedFace = [self extractFaceRegion:bestFace fromImage:image];

    if (croppedFace) {
        NSLog(@"[FaceDetector] Successfully extracted face");
        [self notifyFaceDetected:croppedFace];
    } else {
        [self notifyNoFaceDetected];
    }
}

- (VNFaceObservation *)findBestFaceInResults:(NSArray<VNFaceObservation *> *)results forImage:(UIImage *)image {
    CGFloat imageWidth = image.size.width;
    CGFloat imageHeight = image.size.height;
    CGFloat imageArea = imageWidth * imageHeight;

    VNFaceObservation *bestFace = nil;
    CGFloat bestScore = 0;

    for (VNFaceObservation *face in results) {
        CGRect normalizedBounds = face.boundingBox;

        // Convert normalized coordinates to image coordinates
        CGFloat faceWidth = normalizedBounds.size.width * imageWidth;
        CGFloat faceHeight = normalizedBounds.size.height * imageHeight;
        CGFloat faceArea = faceWidth * faceHeight;
        CGFloat areaRatio = faceArea / imageArea;

        // Skip faces that are too small or too large
        if (areaRatio < kMinFaceSizeRatio || areaRatio > kMaxFaceSizeRatio) {
            NSLog(@"[FaceDetector] Face rejected due to size: ratio=%.4f", areaRatio);
            continue;
        }

        // Calculate score based on size and position
        CGFloat sizeScore = faceArea / imageArea;

        // US driver licenses typically have photo on the left
        CGFloat expectedCenterX = 0.25; // Left quarter
        CGFloat expectedCenterY = 0.6;  // Lower portion (Vision uses bottom-left origin)
        CGFloat actualCenterX = normalizedBounds.origin.x + normalizedBounds.size.width / 2;
        CGFloat actualCenterY = normalizedBounds.origin.y + normalizedBounds.size.height / 2;

        CGFloat positionScore = 1.0 - (
            fabs(actualCenterX - expectedCenterX) +
            fabs(actualCenterY - expectedCenterY)
        ) / 2;

        CGFloat totalScore = sizeScore * 0.6 + positionScore * 0.4;

        if (totalScore > bestScore) {
            bestScore = totalScore;
            bestFace = face;
        }
    }

    return bestFace;
}

- (UIImage *)extractFaceRegion:(VNFaceObservation *)face fromImage:(UIImage *)image {
    CGRect normalizedBounds = face.boundingBox;
    CGFloat imageWidth = image.size.width;
    CGFloat imageHeight = image.size.height;

    // Convert from Vision coordinates (bottom-left origin) to UIKit (top-left origin)
    CGFloat x = normalizedBounds.origin.x * imageWidth;
    CGFloat y = (1.0 - normalizedBounds.origin.y - normalizedBounds.size.height) * imageHeight;
    CGFloat width = normalizedBounds.size.width * imageWidth;
    CGFloat height = normalizedBounds.size.height * imageHeight;

    // Add padding
    CGFloat paddingX = width * kFacePaddingFactor;
    CGFloat paddingY = height * kFacePaddingFactor;

    CGFloat left = MAX(0, x - paddingX);
    CGFloat top = MAX(0, y - paddingY);
    CGFloat right = MIN(imageWidth, x + width + paddingX);
    CGFloat bottom = MIN(imageHeight, y + height + paddingY);

    CGRect cropRect = CGRectMake(left, top, right - left, bottom - top);

    // Validate crop rect
    if (cropRect.size.width <= 0 || cropRect.size.height <= 0) {
        NSLog(@"[FaceDetector] Invalid crop rect");
        return nil;
    }

    // Crop the image
    CGImageRef cgImage = CGImageCreateWithImageInRect(image.CGImage, cropRect);
    if (!cgImage) {
        NSLog(@"[FaceDetector] Failed to crop image");
        return nil;
    }

    UIImage *croppedImage = [UIImage imageWithCGImage:cgImage
                                                scale:image.scale
                                          orientation:image.imageOrientation];
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
