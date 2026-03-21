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
@property (atomic, assign) BOOL isBusy;

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

- (void)checkFacePresenceInPixelBuffer:(CVPixelBufferRef)pixelBuffer {
    if (self.isBusy || !pixelBuffer) return;
    self.isBusy = YES;

    // Retain buffer for async use on the detection queue
    CVPixelBufferRetain(pixelBuffer);
    dispatch_async(self.detectionQueue, ^{
        [self performPresenceDetectionInPixelBuffer:pixelBuffer];
        CVPixelBufferRelease(pixelBuffer);
    });
}

/**
 * Runs VNDetectFaceRectanglesRequest on a pixel buffer.
 * Reports presence via delegate if a face passes size filters.
 * Orientation: kCGImagePropertyOrientationRight = phone held in portrait (camera buffer is landscape-right).
 */
- (void)performPresenceDetectionInPixelBuffer:(CVPixelBufferRef)pixelBuffer {
    __weak __typeof(self) weakSelf = self;

    VNDetectFaceRectanglesRequest *request = [[VNDetectFaceRectanglesRequest alloc]
        initWithCompletionHandler:^(VNRequest *req, NSError *error) {
            __strong __typeof(weakSelf) strongSelf = weakSelf;
            if (!strongSelf) return;
            strongSelf.isBusy = NO;

            if (error || !req.results || req.results.count == 0) return;

            // Check that at least one face passes the area-size filter
            for (VNFaceObservation *face in req.results) {
                CGFloat area = face.boundingBox.size.width * face.boundingBox.size.height;
                if (area >= kMinFaceSizeRatio && area <= kMaxFaceSizeRatio) {
                    id<FaceDetectorHelperDelegate> delegate = strongSelf.delegate;
                    if (delegate && [delegate respondsToSelector:@selector(faceDetectorHelperDidDetectFacePresence:)]) {
                        dispatch_async(dispatch_get_main_queue(), ^{
                            [delegate faceDetectorHelperDidDetectFacePresence:strongSelf];
                        });
                    }
                    return;
                }
            }
        }];

    // Phone held in portrait → video buffer is landscape-right (top of physical scene is on the right of the buffer)
    VNImageRequestHandler *handler = [[VNImageRequestHandler alloc]
        initWithCVPixelBuffer:pixelBuffer
                  orientation:kCGImagePropertyOrientationRight
                      options:@{}];

    NSError *handlerError = nil;
    if (![handler performRequests:@[request] error:&handlerError]) {
        self.isBusy = NO;
        NSLog(@"[FaceDetector] Pixel buffer detection error: %@", handlerError);
    }
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

    // Use VNDetectFaceLandmarksRequest (superset of rectangles) so that the
    // VNFaceObservation.roll property is reliably populated — required for tilt correction.
    VNDetectFaceLandmarksRequest *request = [[VNDetectFaceLandmarksRequest alloc]
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
    CGFloat imageWidth  = image.size.width;
    CGFloat imageHeight = image.size.height;

    // Convert Vision coords (bottom-left, normalized) → UIKit pixels (top-left)
    CGRect nb = face.boundingBox;
    CGFloat faceX = nb.origin.x * imageWidth;
    CGFloat faceY = (1.0 - nb.origin.y - nb.size.height) * imageHeight;
    CGFloat faceW = nb.size.width  * imageWidth;
    CGFloat faceH = nb.size.height * imageHeight;

    // Face centre in image pixels
    CGFloat cx = faceX + faceW / 2.0;
    CGFloat cy = faceY + faceH / 2.0;

    NSLog(@"[FaceDetector] Face: x=%.0f y=%.0f w=%.0f h=%.0f centre=(%.0f,%.0f)",
          faceX, faceY, faceW, faceH, cx, cy);

    // Roll angle (0 if not available)
    CGFloat roll = (face.roll != nil) ? face.roll.floatValue : 0.0;
    NSLog(@"[FaceDetector] Roll = %.3f rad (%.1f°)", roll, roll * 180.0 / M_PI);

    // Desired output square side: face size + portrait padding on all sides
    CGFloat faceSize   = MAX(faceW, faceH);
    CGFloat outputSize = ceil(faceSize * (1.0 + kFacePaddingFactor * 2.0));

    // Pre-rotation crop must be large enough so rotating it by `roll` leaves
    // no empty corners inside the central outputSize × outputSize region.
    // Required side = outputSize × (|cos| + |sin|), i.e. the bounding box of
    // a rotated square of side outputSize.
    CGFloat sinA = fabs(sin(roll));
    CGFloat cosA = fabs(cos(roll));
    CGFloat preCropSize = ceil(outputSize * (cosA + sinA)) + 4;

    // Crop centred on the face, clamped to image bounds
    CGFloat left   = MAX(0,           cx - preCropSize / 2.0);
    CGFloat top    = MAX(0,           cy - preCropSize / 2.0);
    CGFloat right  = MIN(imageWidth,  cx + preCropSize / 2.0);
    CGFloat bottom = MIN(imageHeight, cy + preCropSize / 2.0);

    CGRect preCropRect = CGRectMake(left, top, right - left, bottom - top);
    if (preCropRect.size.width <= 0 || preCropRect.size.height <= 0) {
        NSLog(@"[FaceDetector] Invalid pre-crop rect");
        return nil;
    }

    CGImageRef cgLarge = CGImageCreateWithImageInRect(image.CGImage, preCropRect);
    if (!cgLarge) {
        NSLog(@"[FaceDetector] CGImageCreateWithImageInRect (large) failed");
        return nil;
    }
    UIImage *largeImage = [UIImage imageWithCGImage:cgLarge
                                              scale:image.scale
                                        orientation:UIImageOrientationUp];
    CGImageRelease(cgLarge);

    // Rotate the large image to align the face upright.
    // UIKit image context has Y-axis flipped: positive angle = clockwise in output.
    // roll > 0 = face tilted counter-clockwise → clockwise correction = +roll.
    UIImage *rotated = (fabs(roll) > 0.052)
        ? [self rotateImage:largeImage byRadians:roll]
        : largeImage;

    // Crop a square from the centre of the rotated image — the face is centred
    // there and all corners are filled with real image content (no white gaps).
    return [self centerSquareCrop:rotated size:outputSize];
}

/**
 * Rotates an image by `radians` around its centre.
 * Canvas expands to contain the full rotated content (no clipping).
 */
- (UIImage *)rotateImage:(UIImage *)image byRadians:(CGFloat)radians {
    CGFloat sinA = fabs(sin(radians));
    CGFloat cosA = fabs(cos(radians));
    CGFloat newW = image.size.width  * cosA + image.size.height * sinA;
    CGFloat newH = image.size.width  * sinA + image.size.height * cosA;

    UIGraphicsBeginImageContextWithOptions(CGSizeMake(newW, newH), NO, image.scale);
    CGContextRef ctx = UIGraphicsGetCurrentContext();
    CGContextTranslateCTM(ctx, newW / 2.0, newH / 2.0);
    CGContextRotateCTM(ctx, radians);
    [image drawInRect:CGRectMake(-image.size.width  / 2.0,
                                 -image.size.height / 2.0,
                                  image.size.width,
                                  image.size.height)];
    UIImage *result = UIGraphicsGetImageFromCurrentImageContext();
    UIGraphicsEndImageContext();
    return result ?: image;
}

/**
 * Crops a square of `size` pixels from the centre of `image`.
 * The centre of the image = the (corrected) face centre.
 */
- (UIImage *)centerSquareCrop:(UIImage *)image size:(CGFloat)size {
    CGFloat side = MIN(size, MIN(image.size.width, image.size.height));
    CGRect cropRect = CGRectMake((image.size.width  - side) / 2.0,
                                 (image.size.height - side) / 2.0,
                                  side, side);
    CGImageRef cgImage = CGImageCreateWithImageInRect(image.CGImage, cropRect);
    if (!cgImage) return image;
    UIImage *result = [UIImage imageWithCGImage:cgImage
                                          scale:image.scale
                                    orientation:UIImageOrientationUp];
    CGImageRelease(cgImage);
    return result;
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
