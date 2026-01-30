/**
 * ImageUtils.h
 *
 * Utility class for image processing operations in the driver license scanner.
 */

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Utility methods for image processing and conversion.
 */
@interface ImageUtils : NSObject

/**
 * Converts a UIImage to Base64 encoded string.
 *
 * @param image The image to encode
 * @param format Image format ("JPEG" or "PNG")
 * @param quality JPEG quality (0-100), ignored for PNG
 * @return Base64 encoded string
 */
+ (NSString *)imageToBase64:(UIImage *)image format:(NSString *)format quality:(NSInteger)quality;

/**
 * Decodes a Base64 string to UIImage.
 *
 * @param base64 The Base64 encoded string
 * @return Decoded UIImage or nil if decoding fails
 */
+ (nullable UIImage *)base64ToImage:(NSString *)base64;

/**
 * Extracts the portrait region from a driver license image using deterministic cropping.
 *
 * @param licenseImage The full driver license image
 * @return Cropped portrait region
 */
+ (nullable UIImage *)extractPortraitDeterministic:(UIImage *)licenseImage;

/**
 * Normalizes image orientation so CGImage pixels match UIImage display coordinates.
 * Camera photos typically have imageOrientation != Up, which causes coordinate
 * mismatches between Vision bounding boxes and CGImage crop rects.
 *
 * @param image The image to normalize
 * @return Image with orientation=Up and pixels in display orientation
 */
+ (UIImage *)normalizeOrientation:(UIImage *)image;

/**
 * Scales an image to ensure it doesn't exceed the maximum dimension.
 *
 * @param image The original image
 * @param maxDimension Maximum width or height
 * @return Scaled image
 */
+ (UIImage *)ensureMaxDimension:(UIImage *)image maxSize:(CGFloat)maxDimension;

/**
 * Rotates an image by the specified degrees.
 *
 * @param image The image to rotate
 * @param degrees Rotation in degrees (clockwise)
 * @return Rotated image
 */
+ (UIImage *)rotateImage:(UIImage *)image byDegrees:(CGFloat)degrees;

/**
 * Crops an image to the driver license aspect ratio.
 *
 * @param image The image to crop
 * @return Cropped image with license aspect ratio
 */
+ (UIImage *)cropToLicenseAspectRatio:(UIImage *)image;

@end

NS_ASSUME_NONNULL_END
