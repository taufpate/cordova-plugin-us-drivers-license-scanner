/**
 * BarcodeDecoder.h
 *
 * PDF417 barcode decoder using ZXingObjC library.
 * Optimized for decoding AAMVA-compliant US driver license barcodes.
 */

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <AVFoundation/AVFoundation.h>

NS_ASSUME_NONNULL_BEGIN

@class BarcodeDecoder;

/**
 * Delegate protocol for barcode decoder events.
 */
@protocol BarcodeDecoderDelegate <NSObject>

/**
 * Called when a barcode has been successfully decoded.
 *
 * @param decoder The barcode decoder
 * @param rawData The decoded barcode data
 */
- (void)barcodeDecoder:(BarcodeDecoder *)decoder didDecodeBarcode:(NSString *)rawData;

@optional
/**
 * Called when barcode decoding fails.
 *
 * @param decoder The barcode decoder
 * @param error The decoding error
 */
- (void)barcodeDecoder:(BarcodeDecoder *)decoder didFailWithError:(NSError *)error;

@end

/**
 * PDF417 barcode decoder for driver licenses.
 */
@interface BarcodeDecoder : NSObject

/** Delegate for decoding events */
@property (nonatomic, weak, nullable) id<BarcodeDecoderDelegate> delegate;

/**
 * Decodes a barcode from a camera sample buffer.
 *
 * @param sampleBuffer The video sample buffer to analyze
 */
- (void)decodeSampleBuffer:(CMSampleBufferRef)sampleBuffer;

/**
 * Decodes a barcode from an image.
 *
 * @param image The image to analyze
 */
- (void)decodeImage:(UIImage *)image;

/**
 * Resets the decoder state (clears debounce).
 */
- (void)reset;

@end

NS_ASSUME_NONNULL_END
