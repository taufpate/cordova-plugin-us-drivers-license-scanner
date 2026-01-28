/**
 * FaceDetectorHelper.h
 *
 * Uses Vision framework for on-device face detection to extract portrait images
 * from driver license photos.
 */

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

@class FaceDetectorHelper;

/**
 * Delegate protocol for face detection results.
 */
@protocol FaceDetectorHelperDelegate <NSObject>

/**
 * Called when a face is detected and cropped.
 *
 * @param helper The face detector helper
 * @param croppedFace The cropped face image
 */
- (void)faceDetectorHelper:(FaceDetectorHelper *)helper didDetectFace:(UIImage *)croppedFace;

/**
 * Called when no face is detected in the image.
 *
 * @param helper The face detector helper
 */
- (void)faceDetectorHelperDidNotDetectFace:(FaceDetectorHelper *)helper;

@optional
/**
 * Called when face detection fails with an error.
 *
 * @param helper The face detector helper
 * @param error The detection error
 */
- (void)faceDetectorHelper:(FaceDetectorHelper *)helper didFailWithError:(NSError *)error;

@end

/**
 * Face detector helper using Vision framework.
 */
@interface FaceDetectorHelper : NSObject

/** Delegate for detection events */
@property (nonatomic, weak, nullable) id<FaceDetectorHelperDelegate> delegate;

/**
 * Detects faces in the given image and extracts the portrait region.
 *
 * @param image The image to analyze
 */
- (void)detectFaceInImage:(UIImage *)image;

@end

NS_ASSUME_NONNULL_END
