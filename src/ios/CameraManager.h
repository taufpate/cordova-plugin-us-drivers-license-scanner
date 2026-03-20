/**
 * CameraManager.h
 *
 * Manages AVFoundation camera operations for the driver license scanner.
 * Handles camera preview, image capture, and video frame output for barcode scanning.
 */

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <AVFoundation/AVFoundation.h>

NS_ASSUME_NONNULL_BEGIN

@class CameraManager;

/**
 * Delegate protocol for camera manager events.
 */
@protocol CameraManagerDelegate <NSObject>

/**
 * Called when an image has been captured.
 *
 * @param manager The camera manager
 * @param image The captured image
 */
- (void)cameraManager:(CameraManager *)manager didCaptureImage:(UIImage *)image;

/**
 * Called for each video frame when barcode scanning is enabled.
 *
 * @param manager The camera manager
 * @param sampleBuffer The video sample buffer
 */
- (void)cameraManager:(CameraManager *)manager didReceiveSampleBuffer:(CMSampleBufferRef)sampleBuffer;

/**
 * Called when the camera encounters an error.
 *
 * @param manager The camera manager
 * @param error The error that occurred
 */
- (void)cameraManager:(CameraManager *)manager didFailWithError:(NSError *)error;

@optional

/**
 * Called when a barcode is detected via native AVFoundation metadata output.
 * This is more reliable than ZXingObjC for PDF417 on iOS.
 *
 * @param manager The camera manager
 * @param data The decoded barcode string data
 */
- (void)cameraManager:(CameraManager *)manager didDetectBarcodeData:(NSString *)data;

@end

/**
 * Camera manager for AVFoundation camera operations.
 */
@interface CameraManager : NSObject

/** Delegate for camera events */
@property (nonatomic, weak, nullable) id<CameraManagerDelegate> delegate;

/** Whether the camera is currently running */
@property (nonatomic, readonly) BOOL isRunning;

/** The preview layer for displaying camera output */
@property (nonatomic, strong, readonly) AVCaptureVideoPreviewLayer *previewLayer;

/**
 * Initializes the camera manager with a container view for the preview.
 *
 * @param previewContainer The view to contain the camera preview
 * @return Initialized camera manager instance
 */
- (instancetype)initWithPreviewContainer:(UIView *)previewContainer;

/**
 * Starts the camera with optional barcode scanning mode.
 *
 * @param enableBarcodeScanning If YES, video frames will be delivered to delegate
 */
- (void)startCameraWithBarcodeScanning:(BOOL)enableBarcodeScanning;

/**
 * Stops the camera.
 */
- (void)stopCamera;

/**
 * Captures a still image from the camera.
 */
- (void)captureImage;

/**
 * Enables or disables the camera flash/torch.
 *
 * @param enable YES to enable flash
 */
- (void)setFlash:(BOOL)enable;

/**
 * Sets focus on a specific point.
 *
 * @param point Point in preview layer coordinates (0-1 range)
 */
- (void)setFocusPoint:(CGPoint)point;

/**
 * Sets the local-hour window (0–23) during which auto-torch may activate automatically.
 * Default: start=20 (8 pm), end=4 (4 am).
 */
- (void)setTorchNightWindowStart:(NSInteger)start end:(NSInteger)end;

@end

NS_ASSUME_NONNULL_END
