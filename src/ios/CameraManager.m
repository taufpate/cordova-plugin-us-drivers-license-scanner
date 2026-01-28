/**
 * CameraManager.m
 *
 * AVFoundation camera manager implementation for the driver license scanner.
 */

#import "CameraManager.h"

@interface CameraManager () <AVCaptureVideoDataOutputSampleBufferDelegate, AVCapturePhotoCaptureDelegate>

@property (nonatomic, strong) UIView *previewContainer;
@property (nonatomic, strong) AVCaptureSession *captureSession;
@property (nonatomic, strong) AVCaptureDevice *captureDevice;
@property (nonatomic, strong) AVCaptureDeviceInput *deviceInput;
@property (nonatomic, strong) AVCaptureVideoDataOutput *videoOutput;
@property (nonatomic, strong) AVCapturePhotoOutput *photoOutput;
@property (nonatomic, strong) AVCaptureVideoPreviewLayer *previewLayer;
@property (nonatomic, strong) dispatch_queue_t sessionQueue;
@property (nonatomic, strong) dispatch_queue_t videoQueue;
@property (nonatomic, assign) BOOL isRunning;
@property (nonatomic, assign) BOOL isBarcodeScanning;
@property (nonatomic, assign) BOOL isCapturing;

@end

@implementation CameraManager

#pragma mark - Initialization

- (instancetype)initWithPreviewContainer:(UIView *)previewContainer {
    self = [super init];
    if (self) {
        _previewContainer = previewContainer;
        _sessionQueue = dispatch_queue_create("com.sos.camera.session", DISPATCH_QUEUE_SERIAL);
        _videoQueue = dispatch_queue_create("com.sos.camera.video", DISPATCH_QUEUE_SERIAL);
        _isRunning = NO;
        _isBarcodeScanning = NO;
        _isCapturing = NO;

        [self setupSession];
    }
    return self;
}

- (void)dealloc {
    [self stopCamera];
    NSLog(@"[CameraManager] Deallocated");
}

#pragma mark - Session Setup

- (void)setupSession {
    dispatch_async(self.sessionQueue, ^{
        self.captureSession = [[AVCaptureSession alloc] init];
        self.captureSession.sessionPreset = AVCaptureSessionPresetHigh;

        // Get back camera
        self.captureDevice = [AVCaptureDevice defaultDeviceWithDeviceType:AVCaptureDeviceTypeBuiltInWideAngleCamera
                                                                mediaType:AVMediaTypeVideo
                                                                 position:AVCaptureDevicePositionBack];

        if (!self.captureDevice) {
            [self notifyErrorWithCode:@"CAMERA_NOT_AVAILABLE" message:@"Back camera not available"];
            return;
        }

        NSError *error = nil;

        // Device input
        self.deviceInput = [AVCaptureDeviceInput deviceInputWithDevice:self.captureDevice error:&error];
        if (error) {
            [self notifyErrorWithCode:@"CAMERA_NOT_AVAILABLE" message:error.localizedDescription];
            return;
        }

        if ([self.captureSession canAddInput:self.deviceInput]) {
            [self.captureSession addInput:self.deviceInput];
        }

        // Video output for barcode scanning
        self.videoOutput = [[AVCaptureVideoDataOutput alloc] init];
        self.videoOutput.videoSettings = @{
            (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA)
        };
        self.videoOutput.alwaysDiscardsLateVideoFrames = YES;

        if ([self.captureSession canAddOutput:self.videoOutput]) {
            [self.captureSession addOutput:self.videoOutput];
        }

        // Photo output for image capture
        self.photoOutput = [[AVCapturePhotoOutput alloc] init];
        if ([self.captureSession canAddOutput:self.photoOutput]) {
            [self.captureSession addOutput:self.photoOutput];
        }

        // Configure device
        [self configureDevice];

        // Setup preview layer on main thread
        dispatch_async(dispatch_get_main_queue(), ^{
            [self setupPreviewLayer];
        });

        NSLog(@"[CameraManager] Session setup complete");
    });
}

- (void)configureDevice {
    NSError *error = nil;
    if ([self.captureDevice lockForConfiguration:&error]) {
        // Enable continuous autofocus
        if ([self.captureDevice isFocusModeSupported:AVCaptureFocusModeContinuousAutoFocus]) {
            self.captureDevice.focusMode = AVCaptureFocusModeContinuousAutoFocus;
        }

        // Enable auto exposure
        if ([self.captureDevice isExposureModeSupported:AVCaptureExposureModeContinuousAutoExposure]) {
            self.captureDevice.exposureMode = AVCaptureExposureModeContinuousAutoExposure;
        }

        // Enable auto white balance
        if ([self.captureDevice isWhiteBalanceModeSupported:AVCaptureWhiteBalanceModeContinuousAutoWhiteBalance]) {
            self.captureDevice.whiteBalanceMode = AVCaptureWhiteBalanceModeContinuousAutoWhiteBalance;
        }

        [self.captureDevice unlockForConfiguration];
    } else {
        NSLog(@"[CameraManager] Could not lock device for configuration: %@", error);
    }
}

- (void)setupPreviewLayer {
    self.previewLayer = [AVCaptureVideoPreviewLayer layerWithSession:self.captureSession];
    self.previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill;
    self.previewLayer.frame = self.previewContainer.bounds;

    [self.previewContainer.layer insertSublayer:self.previewLayer atIndex:0];

    // Observe bounds changes
    [self.previewContainer addObserver:self
                            forKeyPath:@"bounds"
                               options:NSKeyValueObservingOptionNew
                               context:nil];
}

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary<NSKeyValueChangeKey,id> *)change
                       context:(void *)context {
    if ([keyPath isEqualToString:@"bounds"]) {
        dispatch_async(dispatch_get_main_queue(), ^{
            self.previewLayer.frame = self.previewContainer.bounds;
        });
    }
}

#pragma mark - Camera Control

- (void)startCameraWithBarcodeScanning:(BOOL)enableBarcodeScanning {
    NSLog(@"[CameraManager] Starting camera, barcodeScanning=%d", enableBarcodeScanning);

    self.isBarcodeScanning = enableBarcodeScanning;

    dispatch_async(self.sessionQueue, ^{
        // Configure video output delegate based on mode
        if (enableBarcodeScanning) {
            [self.videoOutput setSampleBufferDelegate:self queue:self.videoQueue];
        } else {
            [self.videoOutput setSampleBufferDelegate:nil queue:nil];
        }

        if (!self.captureSession.isRunning) {
            [self.captureSession startRunning];
            self.isRunning = YES;
            NSLog(@"[CameraManager] Camera started");
        }
    });
}

- (void)stopCamera {
    NSLog(@"[CameraManager] Stopping camera");

    dispatch_async(self.sessionQueue, ^{
        if (self.captureSession.isRunning) {
            [self.captureSession stopRunning];
            self.isRunning = NO;
            NSLog(@"[CameraManager] Camera stopped");
        }
    });
}

#pragma mark - Image Capture

- (void)captureImage {
    if (self.isCapturing) {
        NSLog(@"[CameraManager] Already capturing, ignoring request");
        return;
    }

    self.isCapturing = YES;
    NSLog(@"[CameraManager] Capturing image");

    dispatch_async(self.sessionQueue, ^{
        AVCapturePhotoSettings *settings = [AVCapturePhotoSettings photoSettings];

        // Configure flash if needed
        if ([self.captureDevice hasFlash] && [self.captureDevice isFlashModeSupported:AVCaptureFlashModeAuto]) {
            settings.flashMode = AVCaptureFlashModeAuto;
        }

        [self.photoOutput capturePhotoWithSettings:settings delegate:self];
    });
}

#pragma mark - AVCapturePhotoCaptureDelegate

- (void)captureOutput:(AVCapturePhotoOutput *)output
didFinishProcessingPhoto:(AVCapturePhoto *)photo
                error:(NSError *)error {

    self.isCapturing = NO;

    if (error) {
        NSLog(@"[CameraManager] Photo capture error: %@", error);
        dispatch_async(dispatch_get_main_queue(), ^{
            [self.delegate cameraManager:self didFailWithError:error];
        });
        return;
    }

    NSData *imageData = [photo fileDataRepresentation];
    if (imageData) {
        UIImage *image = [UIImage imageWithData:imageData];
        if (image) {
            NSLog(@"[CameraManager] Photo captured successfully, size=%@", NSStringFromCGSize(image.size));
            dispatch_async(dispatch_get_main_queue(), ^{
                [self.delegate cameraManager:self didCaptureImage:image];
            });
        }
    }
}

#pragma mark - AVCaptureVideoDataOutputSampleBufferDelegate

- (void)captureOutput:(AVCaptureOutput *)output
didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
       fromConnection:(AVCaptureConnection *)connection {

    if (!self.isBarcodeScanning) {
        return;
    }

    // Retain sample buffer for delegate use
    CFRetain(sampleBuffer);

    dispatch_async(dispatch_get_main_queue(), ^{
        [self.delegate cameraManager:self didReceiveSampleBuffer:sampleBuffer];
        CFRelease(sampleBuffer);
    });
}

#pragma mark - Flash Control

- (void)setFlash:(BOOL)enable {
    dispatch_async(self.sessionQueue, ^{
        if ([self.captureDevice hasTorch]) {
            NSError *error = nil;
            if ([self.captureDevice lockForConfiguration:&error]) {
                self.captureDevice.torchMode = enable ? AVCaptureTorchModeOn : AVCaptureTorchModeOff;
                [self.captureDevice unlockForConfiguration];
                NSLog(@"[CameraManager] Flash %@", enable ? @"enabled" : @"disabled");
            } else {
                NSLog(@"[CameraManager] Could not set flash: %@", error);
            }
        }
    });
}

#pragma mark - Focus Control

- (void)setFocusPoint:(CGPoint)point {
    dispatch_async(self.sessionQueue, ^{
        if ([self.captureDevice isFocusPointOfInterestSupported]) {
            NSError *error = nil;
            if ([self.captureDevice lockForConfiguration:&error]) {
                self.captureDevice.focusPointOfInterest = point;
                self.captureDevice.focusMode = AVCaptureFocusModeAutoFocus;

                if ([self.captureDevice isExposurePointOfInterestSupported]) {
                    self.captureDevice.exposurePointOfInterest = point;
                    self.captureDevice.exposureMode = AVCaptureExposureModeAutoExpose;
                }

                [self.captureDevice unlockForConfiguration];
                NSLog(@"[CameraManager] Focus point set to (%.2f, %.2f)", point.x, point.y);
            }
        }
    });
}

#pragma mark - Helper Methods

- (void)notifyErrorWithCode:(NSString *)code message:(NSString *)message {
    NSError *error = [NSError errorWithDomain:@"com.sos.cameramanager"
                                         code:-1
                                     userInfo:@{
                                         NSLocalizedDescriptionKey: message,
                                         @"errorCode": code
                                     }];

    dispatch_async(dispatch_get_main_queue(), ^{
        [self.delegate cameraManager:self didFailWithError:error];
    });
}

@end
