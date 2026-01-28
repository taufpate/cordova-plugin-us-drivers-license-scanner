/**
 * ScannerViewController.m
 *
 * View controller implementation for the guided driver license scanning flow.
 */

#import "ScannerViewController.h"
#import "CameraManager.h"
#import "BarcodeDecoder.h"
#import "FaceDetectorHelper.h"
#import "AAMVAParser.h"
#import "ImageUtils.h"
#import <AudioToolbox/AudioToolbox.h>

@interface ScannerViewController () <CameraManagerDelegate, BarcodeDecoderDelegate, FaceDetectorHelperDelegate>

// UI Components
@property (nonatomic, strong) UIView *previewContainer;
@property (nonatomic, strong) UIView *overlayView;
@property (nonatomic, strong) UIView *scanFrame;
@property (nonatomic, strong) UILabel *instructionLabel;
@property (nonatomic, strong) UILabel *statusLabel;
@property (nonatomic, strong) UIButton *cancelButton;
@property (nonatomic, strong) UIButton *flashButton;
@property (nonatomic, strong) UIActivityIndicatorView *activityIndicator;
@property (nonatomic, strong) UIView *flipInstructionView;
@property (nonatomic, strong) UIImageView *flipIconView;

// Managers
@property (nonatomic, strong) CameraManager *cameraManager;
@property (nonatomic, strong) BarcodeDecoder *barcodeDecoder;
@property (nonatomic, strong) FaceDetectorHelper *faceDetector;
@property (nonatomic, strong) AAMVAParser *aamvaParser;

// State
@property (nonatomic, assign) ScanState currentState;
@property (nonatomic, assign) BOOL isFlashOn;

// Options
@property (nonatomic, assign) BOOL captureFullImages;
@property (nonatomic, assign) BOOL extractPortrait;
@property (nonatomic, assign) NSTimeInterval scanTimeout;
@property (nonatomic, assign) BOOL enableVibration;

// Results
@property (nonatomic, strong) UIImage *frontImage;
@property (nonatomic, strong) UIImage *backImage;
@property (nonatomic, strong) UIImage *portraitImage;
@property (nonatomic, strong) NSString *backRawData;
@property (nonatomic, strong) NSDictionary *parsedFields;

// Timeout timer
@property (nonatomic, strong) NSTimer *timeoutTimer;

@end

@implementation ScannerViewController

#pragma mark - Lifecycle

- (void)viewDidLoad {
    [super viewDidLoad];

    [self parseOptions];
    [self setupUI];
    [self initializeManagers];
}

- (void)viewWillAppear:(BOOL)animated {
    [super viewWillAppear:animated];
    [self transitionToState:ScanStateScanningFront];
}

- (void)viewWillDisappear:(BOOL)animated {
    [super viewWillDisappear:animated];
    [self stopScanning];
    [self cancelTimeout];
}

- (void)dealloc {
    [self cancelTimeout];
    NSLog(@"[ScannerVC] Deallocated");
}

- (UIStatusBarStyle)preferredStatusBarStyle {
    return UIStatusBarStyleLightContent;
}

#pragma mark - Options Parsing

- (void)parseOptions {
    self.captureFullImages = YES;
    self.extractPortrait = YES;
    self.scanTimeout = 30.0;
    self.enableVibration = YES;

    if (self.options) {
        if (self.options[@"captureFullImages"]) {
            self.captureFullImages = [self.options[@"captureFullImages"] boolValue];
        }
        if (self.options[@"extractPortrait"]) {
            self.extractPortrait = [self.options[@"extractPortrait"] boolValue];
        }
        if (self.options[@"scanTimeoutMs"]) {
            self.scanTimeout = [self.options[@"scanTimeoutMs"] doubleValue] / 1000.0;
        }
        if (self.options[@"enableVibration"]) {
            self.enableVibration = [self.options[@"enableVibration"] boolValue];
        }
    }
}

#pragma mark - UI Setup

- (void)setupUI {
    self.view.backgroundColor = [UIColor blackColor];

    [self setupPreviewContainer];
    [self setupOverlay];
    [self setupScanFrame];
    [self setupInstructionLabel];
    [self setupStatusLabel];
    [self setupFlipInstructionView];
    [self setupActivityIndicator];
    [self setupControlButtons];
}

- (void)setupPreviewContainer {
    self.previewContainer = [[UIView alloc] init];
    self.previewContainer.translatesAutoresizingMaskIntoConstraints = NO;
    self.previewContainer.backgroundColor = [UIColor blackColor];
    [self.view addSubview:self.previewContainer];

    [NSLayoutConstraint activateConstraints:@[
        [self.previewContainer.topAnchor constraintEqualToAnchor:self.view.topAnchor],
        [self.previewContainer.bottomAnchor constraintEqualToAnchor:self.view.bottomAnchor],
        [self.previewContainer.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],
        [self.previewContainer.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor]
    ]];
}

- (void)setupOverlay {
    self.overlayView = [[UIView alloc] init];
    self.overlayView.translatesAutoresizingMaskIntoConstraints = NO;
    self.overlayView.backgroundColor = [UIColor clearColor];
    [self.view addSubview:self.overlayView];

    [NSLayoutConstraint activateConstraints:@[
        [self.overlayView.topAnchor constraintEqualToAnchor:self.view.topAnchor],
        [self.overlayView.bottomAnchor constraintEqualToAnchor:self.view.bottomAnchor],
        [self.overlayView.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor],
        [self.overlayView.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor]
    ]];
}

- (void)setupScanFrame {
    self.scanFrame = [[UIView alloc] init];
    self.scanFrame.translatesAutoresizingMaskIntoConstraints = NO;
    self.scanFrame.backgroundColor = [UIColor clearColor];
    self.scanFrame.layer.borderColor = [UIColor whiteColor].CGColor;
    self.scanFrame.layer.borderWidth = 3.0;
    self.scanFrame.layer.cornerRadius = 12.0;
    [self.view addSubview:self.scanFrame];

    // Driver license aspect ratio: 3.375" x 2.125" = 1.586:1
    CGFloat aspectRatio = 1.586;

    [NSLayoutConstraint activateConstraints:@[
        [self.scanFrame.centerXAnchor constraintEqualToAnchor:self.view.centerXAnchor],
        [self.scanFrame.centerYAnchor constraintEqualToAnchor:self.view.centerYAnchor constant:-50],
        [self.scanFrame.widthAnchor constraintEqualToAnchor:self.view.widthAnchor multiplier:0.85],
        [self.scanFrame.heightAnchor constraintEqualToAnchor:self.scanFrame.widthAnchor multiplier:1.0/aspectRatio]
    ]];

    // Add corner indicators
    [self addCornerIndicatorsToFrame];
}

- (void)addCornerIndicatorsToFrame {
    CGFloat cornerLength = 40.0;
    CGFloat cornerWidth = 4.0;
    UIColor *cornerColor = [UIColor colorWithRed:0 green:0.78 blue:0.33 alpha:1.0]; // Green

    // Create corner indicators (will be positioned in layoutSubviews)
    NSArray *corners = @[@"topLeft", @"topRight", @"bottomLeft", @"bottomRight"];

    for (NSString *corner in corners) {
        UIView *horizontal = [[UIView alloc] init];
        horizontal.backgroundColor = cornerColor;
        horizontal.tag = [corners indexOfObject:corner] * 2 + 100;
        [self.scanFrame addSubview:horizontal];

        UIView *vertical = [[UIView alloc] init];
        vertical.backgroundColor = cornerColor;
        vertical.tag = [corners indexOfObject:corner] * 2 + 101;
        [self.scanFrame addSubview:vertical];
    }
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    [self layoutCornerIndicators];
    [self updateOverlayMask];
}

- (void)layoutCornerIndicators {
    CGFloat cornerLength = 40.0;
    CGFloat cornerWidth = 4.0;
    CGRect bounds = self.scanFrame.bounds;

    // Top-left
    UIView *tlH = [self.scanFrame viewWithTag:100];
    UIView *tlV = [self.scanFrame viewWithTag:101];
    tlH.frame = CGRectMake(0, 0, cornerLength, cornerWidth);
    tlV.frame = CGRectMake(0, 0, cornerWidth, cornerLength);

    // Top-right
    UIView *trH = [self.scanFrame viewWithTag:102];
    UIView *trV = [self.scanFrame viewWithTag:103];
    trH.frame = CGRectMake(bounds.size.width - cornerLength, 0, cornerLength, cornerWidth);
    trV.frame = CGRectMake(bounds.size.width - cornerWidth, 0, cornerWidth, cornerLength);

    // Bottom-left
    UIView *blH = [self.scanFrame viewWithTag:104];
    UIView *blV = [self.scanFrame viewWithTag:105];
    blH.frame = CGRectMake(0, bounds.size.height - cornerWidth, cornerLength, cornerWidth);
    blV.frame = CGRectMake(0, bounds.size.height - cornerLength, cornerWidth, cornerLength);

    // Bottom-right
    UIView *brH = [self.scanFrame viewWithTag:106];
    UIView *brV = [self.scanFrame viewWithTag:107];
    brH.frame = CGRectMake(bounds.size.width - cornerLength, bounds.size.height - cornerWidth, cornerLength, cornerWidth);
    brV.frame = CGRectMake(bounds.size.width - cornerWidth, bounds.size.height - cornerLength, cornerWidth, cornerLength);
}

- (void)updateOverlayMask {
    // Create semi-transparent overlay with cutout for scan frame
    CAShapeLayer *maskLayer = [CAShapeLayer layer];
    UIBezierPath *path = [UIBezierPath bezierPathWithRect:self.overlayView.bounds];

    CGRect scanFrameRect = [self.view convertRect:self.scanFrame.frame toView:self.overlayView];
    UIBezierPath *scanPath = [UIBezierPath bezierPathWithRoundedRect:scanFrameRect cornerRadius:12.0];
    [path appendPath:scanPath];

    maskLayer.path = path.CGPath;
    maskLayer.fillRule = kCAFillRuleEvenOdd;

    CALayer *overlayLayer = [CALayer layer];
    overlayLayer.frame = self.overlayView.bounds;
    overlayLayer.backgroundColor = [UIColor colorWithWhite:0 alpha:0.5].CGColor;
    overlayLayer.mask = maskLayer;

    [self.overlayView.layer.sublayers makeObjectsPerformSelector:@selector(removeFromSuperlayer)];
    [self.overlayView.layer addSublayer:overlayLayer];
}

- (void)setupInstructionLabel {
    self.instructionLabel = [[UILabel alloc] init];
    self.instructionLabel.translatesAutoresizingMaskIntoConstraints = NO;
    self.instructionLabel.text = @"Position the FRONT of your driver license";
    self.instructionLabel.textColor = [UIColor whiteColor];
    self.instructionLabel.font = [UIFont boldSystemFontOfSize:20];
    self.instructionLabel.textAlignment = NSTextAlignmentCenter;
    self.instructionLabel.numberOfLines = 0;
    self.instructionLabel.layer.shadowColor = [UIColor blackColor].CGColor;
    self.instructionLabel.layer.shadowOffset = CGSizeMake(1, 1);
    self.instructionLabel.layer.shadowOpacity = 0.8;
    self.instructionLabel.layer.shadowRadius = 3;
    [self.view addSubview:self.instructionLabel];

    [NSLayoutConstraint activateConstraints:@[
        [self.instructionLabel.bottomAnchor constraintEqualToAnchor:self.scanFrame.topAnchor constant:-24],
        [self.instructionLabel.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor constant:24],
        [self.instructionLabel.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor constant:-24]
    ]];
}

- (void)setupStatusLabel {
    self.statusLabel = [[UILabel alloc] init];
    self.statusLabel.translatesAutoresizingMaskIntoConstraints = NO;
    self.statusLabel.text = @"Align the license within the frame";
    self.statusLabel.textColor = [UIColor colorWithWhite:0.8 alpha:1.0];
    self.statusLabel.font = [UIFont systemFontOfSize:16];
    self.statusLabel.textAlignment = NSTextAlignmentCenter;
    self.statusLabel.numberOfLines = 0;
    [self.view addSubview:self.statusLabel];

    [NSLayoutConstraint activateConstraints:@[
        [self.statusLabel.topAnchor constraintEqualToAnchor:self.scanFrame.bottomAnchor constant:16],
        [self.statusLabel.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor constant:24],
        [self.statusLabel.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor constant:-24]
    ]];
}

- (void)setupFlipInstructionView {
    self.flipInstructionView = [[UIView alloc] init];
    self.flipInstructionView.translatesAutoresizingMaskIntoConstraints = NO;
    self.flipInstructionView.backgroundColor = [UIColor colorWithWhite:0 alpha:0.8];
    self.flipInstructionView.layer.cornerRadius = 16;
    self.flipInstructionView.hidden = YES;
    [self.view addSubview:self.flipInstructionView];

    [NSLayoutConstraint activateConstraints:@[
        [self.flipInstructionView.centerXAnchor constraintEqualToAnchor:self.view.centerXAnchor],
        [self.flipInstructionView.centerYAnchor constraintEqualToAnchor:self.view.centerYAnchor],
        [self.flipInstructionView.widthAnchor constraintEqualToConstant:280],
        [self.flipInstructionView.heightAnchor constraintEqualToConstant:240]
    ]];

    // Flip icon
    self.flipIconView = [[UIImageView alloc] init];
    self.flipIconView.translatesAutoresizingMaskIntoConstraints = NO;
    self.flipIconView.image = [UIImage systemImageNamed:@"arrow.triangle.2.circlepath"];
    self.flipIconView.tintColor = [UIColor whiteColor];
    self.flipIconView.contentMode = UIViewContentModeScaleAspectFit;
    [self.flipInstructionView addSubview:self.flipIconView];

    UILabel *flipTitle = [[UILabel alloc] init];
    flipTitle.translatesAutoresizingMaskIntoConstraints = NO;
    flipTitle.text = @"Please flip your license\nto scan the back";
    flipTitle.textColor = [UIColor whiteColor];
    flipTitle.font = [UIFont boldSystemFontOfSize:20];
    flipTitle.textAlignment = NSTextAlignmentCenter;
    flipTitle.numberOfLines = 0;
    [self.flipInstructionView addSubview:flipTitle];

    UILabel *flipSubtitle = [[UILabel alloc] init];
    flipSubtitle.translatesAutoresizingMaskIntoConstraints = NO;
    flipSubtitle.text = @"Scanning will continue automatically";
    flipSubtitle.textColor = [UIColor colorWithWhite:0.7 alpha:1.0];
    flipSubtitle.font = [UIFont systemFontOfSize:14];
    flipSubtitle.textAlignment = NSTextAlignmentCenter;
    [self.flipInstructionView addSubview:flipSubtitle];

    [NSLayoutConstraint activateConstraints:@[
        [self.flipIconView.topAnchor constraintEqualToAnchor:self.flipInstructionView.topAnchor constant:24],
        [self.flipIconView.centerXAnchor constraintEqualToAnchor:self.flipInstructionView.centerXAnchor],
        [self.flipIconView.widthAnchor constraintEqualToConstant:80],
        [self.flipIconView.heightAnchor constraintEqualToConstant:80],

        [flipTitle.topAnchor constraintEqualToAnchor:self.flipIconView.bottomAnchor constant:16],
        [flipTitle.leadingAnchor constraintEqualToAnchor:self.flipInstructionView.leadingAnchor constant:16],
        [flipTitle.trailingAnchor constraintEqualToAnchor:self.flipInstructionView.trailingAnchor constant:-16],

        [flipSubtitle.topAnchor constraintEqualToAnchor:flipTitle.bottomAnchor constant:8],
        [flipSubtitle.leadingAnchor constraintEqualToAnchor:self.flipInstructionView.leadingAnchor constant:16],
        [flipSubtitle.trailingAnchor constraintEqualToAnchor:self.flipInstructionView.trailingAnchor constant:-16]
    ]];
}

- (void)setupActivityIndicator {
    self.activityIndicator = [[UIActivityIndicatorView alloc] initWithActivityIndicatorStyle:UIActivityIndicatorViewStyleLarge];
    self.activityIndicator.translatesAutoresizingMaskIntoConstraints = NO;
    self.activityIndicator.color = [UIColor whiteColor];
    self.activityIndicator.hidesWhenStopped = YES;
    [self.view addSubview:self.activityIndicator];

    [NSLayoutConstraint activateConstraints:@[
        [self.activityIndicator.centerXAnchor constraintEqualToAnchor:self.view.centerXAnchor],
        [self.activityIndicator.centerYAnchor constraintEqualToAnchor:self.view.centerYAnchor]
    ]];
}

- (void)setupControlButtons {
    // Flash button
    self.flashButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.flashButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.flashButton setTitle:@"Flash On" forState:UIControlStateNormal];
    [self.flashButton setTitleColor:[UIColor whiteColor] forState:UIControlStateNormal];
    self.flashButton.backgroundColor = [UIColor colorWithWhite:0.2 alpha:1.0];
    self.flashButton.layer.cornerRadius = 24;
    [self.flashButton addTarget:self action:@selector(toggleFlash) forControlEvents:UIControlEventTouchUpInside];
    [self.view addSubview:self.flashButton];

    // Cancel button
    self.cancelButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.cancelButton.translatesAutoresizingMaskIntoConstraints = NO;
    [self.cancelButton setTitle:@"Cancel" forState:UIControlStateNormal];
    [self.cancelButton setTitleColor:[UIColor whiteColor] forState:UIControlStateNormal];
    self.cancelButton.backgroundColor = [UIColor colorWithWhite:0.2 alpha:1.0];
    self.cancelButton.layer.cornerRadius = 24;
    [self.cancelButton addTarget:self action:@selector(cancelPressed) forControlEvents:UIControlEventTouchUpInside];
    [self.view addSubview:self.cancelButton];

    [NSLayoutConstraint activateConstraints:@[
        [self.flashButton.bottomAnchor constraintEqualToAnchor:self.view.safeAreaLayoutGuide.bottomAnchor constant:-32],
        [self.flashButton.leadingAnchor constraintEqualToAnchor:self.view.leadingAnchor constant:24],
        [self.flashButton.widthAnchor constraintEqualToConstant:120],
        [self.flashButton.heightAnchor constraintEqualToConstant:48],

        [self.cancelButton.bottomAnchor constraintEqualToAnchor:self.view.safeAreaLayoutGuide.bottomAnchor constant:-32],
        [self.cancelButton.trailingAnchor constraintEqualToAnchor:self.view.trailingAnchor constant:-24],
        [self.cancelButton.widthAnchor constraintEqualToConstant:120],
        [self.cancelButton.heightAnchor constraintEqualToConstant:48]
    ]];
}

#pragma mark - Manager Initialization

- (void)initializeManagers {
    self.aamvaParser = [[AAMVAParser alloc] init];
    self.faceDetector = [[FaceDetectorHelper alloc] init];
    self.faceDetector.delegate = self;

    self.barcodeDecoder = [[BarcodeDecoder alloc] init];
    self.barcodeDecoder.delegate = self;

    self.cameraManager = [[CameraManager alloc] initWithPreviewContainer:self.previewContainer];
    self.cameraManager.delegate = self;
}

#pragma mark - State Management

- (void)transitionToState:(ScanState)newState {
    NSLog(@"[ScannerVC] Transitioning from %ld to %ld", (long)self.currentState, (long)newState);

    _currentState = newState;
    [self cancelTimeout];

    dispatch_async(dispatch_get_main_queue(), ^{
        [self updateUIForState:newState];
    });

    switch (newState) {
        case ScanStateScanningFront:
            [self startFrontScan];
            break;
        case ScanStateFlipInstruction:
            [self showFlipInstruction];
            break;
        case ScanStateScanningBack:
            [self startBackScan];
            break;
        case ScanStateProcessing:
            [self processResults];
            break;
        case ScanStateCompleted:
            [self finishWithSuccess];
            break;
        case ScanStateError:
            // Error handled separately
            break;
    }
}

- (void)updateUIForState:(ScanState)state {
    // Reset visibility
    self.activityIndicator.hidden = YES;
    [self.activityIndicator stopAnimating];
    self.flipInstructionView.hidden = YES;
    self.scanFrame.hidden = NO;
    self.overlayView.hidden = NO;

    switch (state) {
        case ScanStateScanningFront:
            self.instructionLabel.text = @"Position the FRONT of your driver license";
            self.statusLabel.text = @"Align the license within the frame";
            self.scanFrame.layer.borderColor = [UIColor whiteColor].CGColor;
            break;

        case ScanStateFlipInstruction:
            self.instructionLabel.text = @"Front captured!";
            self.statusLabel.text = @"";
            self.flipInstructionView.hidden = NO;
            self.scanFrame.hidden = YES;
            self.overlayView.hidden = YES;
            self.scanFrame.layer.borderColor = [UIColor colorWithRed:0 green:0.78 blue:0.33 alpha:1.0].CGColor;
            break;

        case ScanStateScanningBack:
            self.instructionLabel.text = @"Position the BACK of your driver license";
            self.statusLabel.text = @"Align the barcode within the frame";
            self.scanFrame.layer.borderColor = [UIColor whiteColor].CGColor;
            break;

        case ScanStateProcessing:
            self.instructionLabel.text = @"Processing...";
            self.statusLabel.text = @"Analyzing license data";
            [self.activityIndicator startAnimating];
            self.scanFrame.hidden = YES;
            self.overlayView.hidden = YES;
            break;

        case ScanStateCompleted:
            self.instructionLabel.text = @"Scan complete!";
            self.statusLabel.text = @"";
            break;

        case ScanStateError:
            self.instructionLabel.text = @"Scan failed";
            self.scanFrame.layer.borderColor = [UIColor redColor].CGColor;
            break;
    }
}

#pragma mark - Scan Flow

- (void)startFrontScan {
    NSLog(@"[ScannerVC] Starting front scan");
    [self.cameraManager startCameraWithBarcodeScanning:NO];
    [self startTimeoutWithHandler:^{
        if (self.currentState == ScanStateScanningFront) {
            [self failWithError:@"SCAN_TIMEOUT" message:@"Front scan timed out. Please try again."];
        }
    }];

    // Auto-capture front after short delay for positioning
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.0 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        if (self.currentState == ScanStateScanningFront) {
            [self.cameraManager captureImage];
        }
    });
}

- (void)showFlipInstruction {
    NSLog(@"[ScannerVC] Showing flip instruction");
    [self.cameraManager stopCamera];

    if (self.enableVibration) {
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate);
    }

    // Animate flip icon
    [UIView animateWithDuration:0.5 delay:0 options:UIViewAnimationOptionRepeat | UIViewAnimationOptionAutoreverse animations:^{
        self.flipIconView.transform = CGAffineTransformMakeRotation(M_PI);
    } completion:nil];

    // Auto-transition to back scan after delay
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2.5 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        [self.flipIconView.layer removeAllAnimations];
        self.flipIconView.transform = CGAffineTransformIdentity;

        if (self.currentState == ScanStateFlipInstruction) {
            [self transitionToState:ScanStateScanningBack];
        }
    });
}

- (void)startBackScan {
    NSLog(@"[ScannerVC] Starting back scan");
    [self.cameraManager startCameraWithBarcodeScanning:YES];
    [self startTimeoutWithHandler:^{
        if (self.currentState == ScanStateScanningBack) {
            [self failWithError:@"BARCODE_NOT_FOUND" message:@"Could not detect barcode. Please try again."];
        }
    }];
}

- (void)processResults {
    NSLog(@"[ScannerVC] Processing results");

    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        // Parse AAMVA data
        if (self.backRawData) {
            self.parsedFields = [self.aamvaParser parseRawData:self.backRawData];
        } else {
            self.parsedFields = @{@"isValid": @NO, @"error": @"No barcode data captured"};
        }

        // Extract portrait if needed and not already done
        if (self.extractPortrait && self.frontImage && !self.portraitImage) {
            self.portraitImage = [ImageUtils extractPortraitDeterministic:self.frontImage];
        }

        dispatch_async(dispatch_get_main_queue(), ^{
            if (self.currentState == ScanStateProcessing) {
                [self transitionToState:ScanStateCompleted];
            }
        });
    });
}

- (void)finishWithSuccess {
    NSMutableDictionary *result = [NSMutableDictionary dictionary];

    result[@"frontRawData"] = [NSNull null];
    result[@"backRawData"] = self.backRawData ?: [NSNull null];
    result[@"parsedFields"] = self.parsedFields ?: @{};

    // Portrait image
    if (self.portraitImage) {
        result[@"portraitImageBase64"] = [ImageUtils imageToBase64:self.portraitImage format:@"JPEG" quality:85];
    } else {
        result[@"portraitImageBase64"] = @"";
    }

    // Full images
    if (self.captureFullImages) {
        if (self.frontImage) {
            result[@"fullFrontImageBase64"] = [ImageUtils imageToBase64:self.frontImage format:@"JPEG" quality:85];
        }
        if (self.backImage) {
            result[@"fullBackImageBase64"] = [ImageUtils imageToBase64:self.backImage format:@"JPEG" quality:85];
        }
    }

    [self.delegate scannerViewController:self didFinishWithResult:result];
}

- (void)failWithError:(NSString *)errorCode message:(NSString *)message {
    _currentState = ScanStateError;
    [self stopScanning];
    [self.delegate scannerViewController:self didFailWithError:errorCode message:message];
}

- (void)stopScanning {
    [self.cameraManager stopCamera];
    [self cancelTimeout];
}

#pragma mark - Button Actions

- (void)toggleFlash {
    self.isFlashOn = !self.isFlashOn;
    [self.cameraManager setFlash:self.isFlashOn];
    [self.flashButton setTitle:self.isFlashOn ? @"Flash Off" : @"Flash On" forState:UIControlStateNormal];
}

- (void)cancelPressed {
    [self stopScanning];
    [self.delegate scannerViewControllerDidCancel:self];
}

#pragma mark - Timeout Management

- (void)startTimeoutWithHandler:(void (^)(void))handler {
    [self cancelTimeout];
    self.timeoutTimer = [NSTimer scheduledTimerWithTimeInterval:self.scanTimeout
                                                         target:self
                                                       selector:@selector(timeoutFired:)
                                                       userInfo:handler
                                                        repeats:NO];
}

- (void)timeoutFired:(NSTimer *)timer {
    void (^handler)(void) = timer.userInfo;
    if (handler) {
        handler();
    }
}

- (void)cancelTimeout {
    [self.timeoutTimer invalidate];
    self.timeoutTimer = nil;
}

#pragma mark - CameraManagerDelegate

- (void)cameraManager:(CameraManager *)manager didCaptureImage:(UIImage *)image {
    NSLog(@"[ScannerVC] Image captured, state=%ld", (long)self.currentState);

    if (self.currentState == ScanStateScanningFront) {
        self.frontImage = image;

        if (self.extractPortrait) {
            [self.faceDetector detectFaceInImage:image];
        } else {
            [self transitionToState:ScanStateFlipInstruction];
        }
    } else if (self.currentState == ScanStateScanningBack) {
        self.backImage = image;
    }
}

- (void)cameraManager:(CameraManager *)manager didReceiveSampleBuffer:(CMSampleBufferRef)sampleBuffer {
    if (self.currentState == ScanStateScanningBack) {
        [self.barcodeDecoder decodeSampleBuffer:sampleBuffer];
    }
}

- (void)cameraManager:(CameraManager *)manager didFailWithError:(NSError *)error {
    NSLog(@"[ScannerVC] Camera error: %@", error);
    [self failWithError:@"CAMERA_NOT_AVAILABLE" message:error.localizedDescription];
}

#pragma mark - BarcodeDecoderDelegate

- (void)barcodeDecoder:(BarcodeDecoder *)decoder didDecodeBarcode:(NSString *)rawData {
    NSLog(@"[ScannerVC] Barcode decoded, length=%lu", (unsigned long)rawData.length);

    if (self.currentState == ScanStateScanningBack && rawData.length > 0) {
        self.backRawData = rawData;

        if (self.enableVibration) {
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate);
        }

        // Capture back image
        [self.cameraManager captureImage];

        // Short delay then process
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.3 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
            if (self.currentState == ScanStateScanningBack) {
                [self transitionToState:ScanStateProcessing];
            }
        });
    }
}

- (void)barcodeDecoder:(BarcodeDecoder *)decoder didFailWithError:(NSError *)error {
    // Ignore decoding errors during continuous scanning
    // NSLog(@"[ScannerVC] Barcode decode error: %@", error);
}

#pragma mark - FaceDetectorHelperDelegate

- (void)faceDetectorHelper:(FaceDetectorHelper *)helper didDetectFace:(UIImage *)croppedFace {
    NSLog(@"[ScannerVC] Face detected and cropped");
    self.portraitImage = croppedFace;

    dispatch_async(dispatch_get_main_queue(), ^{
        if (self.currentState == ScanStateScanningFront) {
            [self transitionToState:ScanStateFlipInstruction];
        }
    });
}

- (void)faceDetectorHelperDidNotDetectFace:(FaceDetectorHelper *)helper {
    NSLog(@"[ScannerVC] No face detected, will use deterministic crop");

    dispatch_async(dispatch_get_main_queue(), ^{
        if (self.currentState == ScanStateScanningFront) {
            [self transitionToState:ScanStateFlipInstruction];
        }
    });
}

- (void)faceDetectorHelper:(FaceDetectorHelper *)helper didFailWithError:(NSError *)error {
    NSLog(@"[ScannerVC] Face detection error: %@", error);

    dispatch_async(dispatch_get_main_queue(), ^{
        if (self.currentState == ScanStateScanningFront) {
            [self transitionToState:ScanStateFlipInstruction];
        }
    });
}

@end
