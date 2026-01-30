/**
 * CameraManager.m
 *
 * AVFoundation camera manager implementation for the driver license scanner.
 * Uses AVCaptureMetadataOutput for native PDF417 detection (primary),
 * plus video frame delivery for ZXingObjC fallback decoding.
 */

#import "CameraManager.h"

@interface CameraManager () <AVCaptureVideoDataOutputSampleBufferDelegate,
                              AVCapturePhotoCaptureDelegate,
                              AVCaptureMetadataOutputObjectsDelegate>

@property (nonatomic, strong) UIView *previewContainer;
@property (nonatomic, strong) AVCaptureSession *captureSession;
@property (nonatomic, strong) AVCaptureDevice *captureDevice;
@property (nonatomic, strong) AVCaptureDeviceInput *deviceInput;
@property (nonatomic, strong) AVCaptureVideoDataOutput *videoOutput;
@property (nonatomic, strong) AVCapturePhotoOutput *photoOutput;
@property (nonatomic, strong) AVCaptureMetadataOutput *metadataOutput;
@property (nonatomic, strong) AVCaptureVideoPreviewLayer *previewLayer;
@property (nonatomic, strong) dispatch_queue_t sessionQueue;
@property (nonatomic, strong) dispatch_queue_t videoQueue;
@property (nonatomic, assign) BOOL isRunning;
@property (nonatomic, assign) BOOL isBarcodeScanning;
@property (nonatomic, assign) BOOL isCapturing;
@property (nonatomic, assign) BOOL isObservingBounds;

// Frame throttling: skip frames to avoid memory pressure
@property (nonatomic, assign) NSInteger frameCounter;

@end

// Process every Nth frame for ZXingObjC fallback (native metadata is always active)
static NSInteger const kFrameSkipInterval = 3;

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
        _isObservingBounds = NO;
        _frameCounter = 0;

        [self setupSession];
    }
    return self;
}

- (void)dealloc {
    [self removePreviewObserver];
    [self stopCameraSync];
    NSLog(@"[CameraManager] Deallocated");
}

#pragma mark - Session Setup

- (void)setupSession {
    dispatch_async(self.sessionQueue, ^{
        self.captureSession = [[AVCaptureSession alloc] init];

        // Use 1920x1080 for better barcode resolution, fall back to 1280x720
        if ([self.captureSession canSetSessionPreset:AVCaptureSessionPreset1920x1080]) {
            self.captureSession.sessionPreset = AVCaptureSessionPreset1920x1080;
            NSLog(@"[CameraManager] Using 1920x1080 preset");
        } else {
            self.captureSession.sessionPreset = AVCaptureSessionPreset1280x720;
            NSLog(@"[CameraManager] Using 1280x720 preset (1080p not available)");
        }

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

        // Video output for ZXingObjC fallback barcode scanning + face detection frames
        self.videoOutput = [[AVCaptureVideoDataOutput alloc] init];
        self.videoOutput.videoSettings = @{
            (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA)
        };
        self.videoOutput.alwaysDiscardsLateVideoFrames = YES;

        if ([self.captureSession canAddOutput:self.videoOutput]) {
            [self.captureSession addOutput:self.videoOutput];

            // Set video orientation to portrait so ZXingObjC receives correctly oriented frames
            AVCaptureConnection *videoConnection = [self.videoOutput connectionWithMediaType:AVMediaTypeVideo];
            if (videoConnection && videoConnection.isVideoOrientationSupported) {
                videoConnection.videoOrientation = AVCaptureVideoOrientationPortrait;
                NSLog(@"[CameraManager] Video output orientation set to portrait");
            }
        }

        // Photo output for image capture
        self.photoOutput = [[AVCapturePhotoOutput alloc] init];
        if ([self.captureSession canAddOutput:self.photoOutput]) {
            [self.captureSession addOutput:self.photoOutput];
        }

        // Native metadata output for PDF417 detection (primary barcode detector)
        // AVCaptureMetadataOutput is dramatically more reliable than ZXingObjC for PDF417
        self.metadataOutput = [[AVCaptureMetadataOutput alloc] init];
        if ([self.captureSession canAddOutput:self.metadataOutput]) {
            [self.captureSession addOutput:self.metadataOutput];

            // metadataObjectTypes must be set AFTER adding output to the session
            NSArray *availableTypes = self.metadataOutput.availableMetadataObjectTypes;
            NSMutableArray *desiredTypes = [NSMutableArray array];

            if ([availableTypes containsObject:AVMetadataObjectTypePDF417Code]) {
                [desiredTypes addObject:AVMetadataObjectTypePDF417Code];
            }

            if (desiredTypes.count > 0) {
                self.metadataOutput.metadataObjectTypes = desiredTypes;
                NSLog(@"[CameraManager] Native PDF417 metadata detection enabled");
            } else {
                NSLog(@"[CameraManager] WARNING: PDF417 not available in native metadata output");
            }
        } else {
            NSLog(@"[CameraManager] WARNING: Could not add metadata output to session");
        }

        // Configure device (autofocus, exposure, white balance)
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

    // Observe bounds changes safely
    [self addPreviewObserver];
}

- (void)addPreviewObserver {
    if (!self.isObservingBounds && self.previewContainer) {
        [self.previewContainer addObserver:self
                                forKeyPath:@"bounds"
                                   options:NSKeyValueObservingOptionNew
                                   context:nil];
        self.isObservingBounds = YES;
    }
}

- (void)removePreviewObserver {
    if (self.isObservingBounds && self.previewContainer) {
        @try {
            [self.previewContainer removeObserver:self forKeyPath:@"bounds"];
        } @catch (NSException *exception) {
            NSLog(@"[CameraManager] KVO removal exception: %@", exception);
        }
        self.isObservingBounds = NO;
    }
}

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary<NSKeyValueChangeKey,id> *)change
                       context:(void *)context {
    if ([keyPath isEqualToString:@"bounds"]) {
        dispatch_async(dispatch_get_main_queue(), ^{
            if (self.previewLayer) {
                self.previewLayer.frame = self.previewContainer.bounds;
            }
        });
    }
}

#pragma mark - Camera Control

- (void)startCameraWithBarcodeScanning:(BOOL)enableBarcodeScanning {
    NSLog(@"[CameraManager] Starting camera, barcodeScanning=%d", enableBarcodeScanning);

    self.isBarcodeScanning = enableBarcodeScanning;
    self.frameCounter = 0;

    dispatch_async(self.sessionQueue, ^{
        // Configure video output delegate (for ZXingObjC fallback + face detection)
        if (enableBarcodeScanning) {
            [self.videoOutput setSampleBufferDelegate:self queue:self.videoQueue];
        } else {
            [self.videoOutput setSampleBufferDelegate:nil queue:nil];
        }

        // Configure native metadata output delegate (primary PDF417 detector)
        if (enableBarcodeScanning) {
            [self.metadataOutput setMetadataObjectsDelegate:self queue:dispatch_get_main_queue()];
            NSLog(@"[CameraManager] Native PDF417 metadata delegate enabled");
        } else {
            [self.metadataOutput setMetadataObjectsDelegate:nil queue:nil];
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

    // Remove delegates first to stop receiving frames/metadata immediately
    [self.videoOutput setSampleBufferDelegate:nil queue:nil];
    [self.metadataOutput setMetadataObjectsDelegate:nil queue:nil];
    self.isBarcodeScanning = NO;

    dispatch_async(self.sessionQueue, ^{
        if (self.captureSession.isRunning) {
            [self.captureSession stopRunning];
            self.isRunning = NO;
            NSLog(@"[CameraManager] Camera stopped");
        }
    });
}

// Synchronous stop for use in dealloc
- (void)stopCameraSync {
    [self.videoOutput setSampleBufferDelegate:nil queue:nil];
    [self.metadataOutput setMetadataObjectsDelegate:nil queue:nil];
    self.isBarcodeScanning = NO;
    if (self.captureSession.isRunning) {
        [self.captureSession stopRunning];
        self.isRunning = NO;
    }
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

        // Configure flash if supported
        if ([self.captureDevice hasFlash] && [self.photoOutput.supportedFlashModes containsObject:@(AVCaptureFlashModeAuto)]) {
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

#pragma mark - AVCaptureMetadataOutputObjectsDelegate (Native PDF417 Detection)

- (void)captureOutput:(AVCaptureOutput *)output
didOutputMetadataObjects:(NSArray<__kindof AVMetadataObject *> *)metadataObjects
       fromConnection:(AVCaptureConnection *)connection {

    if (!self.isBarcodeScanning) {
        return;
    }

    for (AVMetadataObject *metadata in metadataObjects) {
        if ([metadata isKindOfClass:[AVMetadataMachineReadableCodeObject class]]) {
            AVMetadataMachineReadableCodeObject *barcode = (AVMetadataMachineReadableCodeObject *)metadata;

            if ([barcode.type isEqualToString:AVMetadataObjectTypePDF417Code]) {
                NSString *data = barcode.stringValue;

                if (data && data.length > 0) {
                    NSLog(@"[CameraManager] *** NATIVE PDF417 DETECTED *** length=%lu", (unsigned long)data.length);
                    NSLog(@"[CameraManager] Native PDF417 first 80 chars: %@", [data substringToIndex:MIN(80, data.length)]);

                    if ([self.delegate respondsToSelector:@selector(cameraManager:didDetectBarcodeData:)]) {
                        [self.delegate cameraManager:self didDetectBarcodeData:data];
                    }

                    return; // Stop after first valid PDF417
                }
            }
        }
    }
}

#pragma mark - AVCaptureVideoDataOutputSampleBufferDelegate (ZXingObjC Fallback)

- (void)captureOutput:(AVCaptureOutput *)output
didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
       fromConnection:(AVCaptureConnection *)connection {

    if (!self.isBarcodeScanning) {
        return;
    }

    // Frame throttling: only process every Nth frame to reduce memory pressure
    self.frameCounter++;
    if (self.frameCounter % kFrameSkipInterval != 0) {
        return;
    }

    // Deliver to delegate for ZXingObjC processing or face detection
    [self.delegate cameraManager:self didReceiveSampleBuffer:sampleBuffer];
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
