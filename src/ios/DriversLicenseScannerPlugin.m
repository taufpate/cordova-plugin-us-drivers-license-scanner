/**
 * DriversLicenseScannerPlugin.m
 *
 * Main Cordova plugin implementation for US Drivers License Scanner.
 * Handles JavaScript interface calls and manages the scanner view controller lifecycle.
 */

#import "DriversLicenseScannerPlugin.h"
#import "ScannerViewController.h"
#import "AAMVAParser.h"
#import <AVFoundation/AVFoundation.h>

// Error codes matching JavaScript interface
static NSString *const kErrorCameraPermissionDenied = @"CAMERA_PERMISSION_DENIED";
static NSString *const kErrorCameraNotAvailable = @"CAMERA_NOT_AVAILABLE";
static NSString *const kErrorScanCancelled = @"SCAN_CANCELLED";
static NSString *const kErrorBarcodeNotFound = @"BARCODE_NOT_FOUND";
static NSString *const kErrorParseError = @"PARSE_ERROR";
static NSString *const kErrorUnknown = @"UNKNOWN_ERROR";

@interface DriversLicenseScannerPlugin () <ScannerViewControllerDelegate>

@property (nonatomic, strong) NSString *currentCallbackId;
@property (nonatomic, strong) NSDictionary *currentOptions;

@end

@implementation DriversLicenseScannerPlugin

#pragma mark - Plugin Lifecycle

- (void)pluginInitialize {
    [super pluginInitialize];
    NSLog(@"[DriversLicenseScanner] Plugin initialized");
}

#pragma mark - JavaScript Interface Methods

- (void)scanDriverLicense:(CDVInvokedUrlCommand *)command {
    NSLog(@"[DriversLicenseScanner] scanDriverLicense called");

    // Store callback for async result
    self.currentCallbackId = command.callbackId;

    // Parse options
    NSDictionary *options = [command.arguments firstObject];
    if (![options isKindOfClass:[NSDictionary class]]) {
        options = @{};
    }
    self.currentOptions = options;

    // Check camera availability
    if (![UIImagePickerController isSourceTypeAvailable:UIImagePickerControllerSourceTypeCamera]) {
        [self sendErrorResult:kErrorCameraNotAvailable message:@"Device does not have a camera"];
        return;
    }

    // Check camera permission
    AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];

    switch (status) {
        case AVAuthorizationStatusAuthorized: {
            [self launchScanner];
            break;
        }
        case AVAuthorizationStatusNotDetermined: {
            // Request permission
            [AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo completionHandler:^(BOOL granted) {
                dispatch_async(dispatch_get_main_queue(), ^{
                    if (granted) {
                        [self launchScanner];
                    } else {
                        [self sendErrorResult:kErrorCameraPermissionDenied
                                      message:@"Camera permission is required to scan driver licenses"];
                    }
                });
            }];
            break;
        }
        case AVAuthorizationStatusDenied:
        case AVAuthorizationStatusRestricted: {
            [self sendErrorResult:kErrorCameraPermissionDenied
                          message:@"Camera permission denied. Please enable in Settings."];
            break;
        }
    }
}

- (void)checkCameraPermission:(CDVInvokedUrlCommand *)command {
    NSLog(@"[DriversLicenseScanner] checkCameraPermission called");

    BOOL hasCamera = [UIImagePickerController isSourceTypeAvailable:UIImagePickerControllerSourceTypeCamera];
    AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];

    BOOL hasPermission = (status == AVAuthorizationStatusAuthorized);
    BOOL canRequest = (status == AVAuthorizationStatusNotDetermined);

    NSDictionary *result = @{
        @"hasCamera": @(hasCamera),
        @"hasPermission": @(hasPermission),
        @"canRequestPermission": @(canRequest)
    };

    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                  messageAsDictionary:result];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)requestCameraPermission:(CDVInvokedUrlCommand *)command {
    NSLog(@"[DriversLicenseScanner] requestCameraPermission called");

    AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];

    if (status == AVAuthorizationStatusAuthorized) {
        NSDictionary *result = @{@"granted": @YES};
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                      messageAsDictionary:result];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }

    if (status == AVAuthorizationStatusNotDetermined) {
        [AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo completionHandler:^(BOOL granted) {
            dispatch_async(dispatch_get_main_queue(), ^{
                NSDictionary *result = @{@"granted": @(granted)};
                CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                              messageAsDictionary:result];
                [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            });
        }];
    } else {
        // Permission denied or restricted
        NSDictionary *error = @{
            @"code": kErrorCameraPermissionDenied,
            @"message": @"Camera permission denied"
        };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                      messageAsDictionary:error];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }
}

- (void)parseAAMVAData:(CDVInvokedUrlCommand *)command {
    NSLog(@"[DriversLicenseScanner] parseAAMVAData called");

    NSString *rawData = [command.arguments firstObject];

    if (!rawData || ![rawData isKindOfClass:[NSString class]] || rawData.length == 0) {
        NSDictionary *error = @{
            @"code": kErrorParseError,
            @"message": @"No data provided"
        };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                      messageAsDictionary:error];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }

    AAMVAParser *parser = [[AAMVAParser alloc] init];
    NSDictionary *parsed = [parser parseRawData:rawData];

    if (parsed) {
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                      messageAsDictionary:parsed];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    } else {
        NSDictionary *error = @{
            @"code": kErrorParseError,
            @"message": @"Failed to parse AAMVA data"
        };
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                      messageAsDictionary:error];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }
}

#pragma mark - Scanner Management

- (void)launchScanner {
    NSLog(@"[DriversLicenseScanner] Launching scanner");

    dispatch_async(dispatch_get_main_queue(), ^{
        ScannerViewController *scannerVC = [[ScannerViewController alloc] init];
        scannerVC.delegate = self;
        scannerVC.options = self.currentOptions;
        scannerVC.modalPresentationStyle = UIModalPresentationFullScreen;

        [self.viewController presentViewController:scannerVC animated:YES completion:nil];
    });
}

#pragma mark - ScannerViewControllerDelegate

- (void)scannerViewController:(ScannerViewController *)controller
         didFinishWithResult:(NSDictionary *)result {
    NSLog(@"[DriversLicenseScanner] Scan completed successfully");

    dispatch_async(dispatch_get_main_queue(), ^{
        [controller dismissViewControllerAnimated:YES completion:^{
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK
                                                          messageAsDictionary:result];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:self.currentCallbackId];
            self.currentCallbackId = nil;
        }];
    });
}

- (void)scannerViewController:(ScannerViewController *)controller
           didFailWithError:(NSString *)errorCode
                    message:(NSString *)errorMessage {
    NSLog(@"[DriversLicenseScanner] Scan failed: %@ - %@", errorCode, errorMessage);

    dispatch_async(dispatch_get_main_queue(), ^{
        [controller dismissViewControllerAnimated:YES completion:^{
            [self sendErrorResult:errorCode message:errorMessage];
        }];
    });
}

- (void)scannerViewControllerDidCancel:(ScannerViewController *)controller {
    NSLog(@"[DriversLicenseScanner] Scan cancelled by user");

    dispatch_async(dispatch_get_main_queue(), ^{
        [controller dismissViewControllerAnimated:YES completion:^{
            [self sendErrorResult:kErrorScanCancelled message:@"Scan cancelled by user"];
        }];
    });
}

#pragma mark - Helper Methods

- (void)sendErrorResult:(NSString *)errorCode message:(NSString *)message {
    if (!self.currentCallbackId) {
        NSLog(@"[DriversLicenseScanner] No callback ID for error result");
        return;
    }

    NSDictionary *error = @{
        @"code": errorCode ?: kErrorUnknown,
        @"message": message ?: @"Unknown error"
    };

    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR
                                                  messageAsDictionary:error];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:self.currentCallbackId];
    self.currentCallbackId = nil;
}

@end
