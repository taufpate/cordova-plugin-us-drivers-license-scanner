/**
 * DriversLicenseScannerPlugin.h
 *
 * Main Cordova plugin header for US Drivers License Scanner.
 * Exposes JavaScript interface methods for scanning driver licenses.
 */

#import <Cordova/CDV.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Cordova plugin for scanning US Driver Licenses using PDF417 barcodes.
 * Uses ZXingObjC for barcode decoding and Vision framework for face detection.
 */
@interface DriversLicenseScannerPlugin : CDVPlugin

/**
 * Scans a US Driver License using a guided two-step flow.
 * 1. Scan front (capture image, detect face)
 * 2. Flip instruction
 * 3. Scan back (decode PDF417 barcode)
 *
 * @param command Cordova invoked URL command containing options
 */
- (void)scanDriverLicense:(CDVInvokedUrlCommand *)command;

/**
 * Checks if the device has camera and permission status.
 *
 * @param command Cordova invoked URL command
 */
- (void)checkCameraPermission:(CDVInvokedUrlCommand *)command;

/**
 * Requests camera permission from the user.
 *
 * @param command Cordova invoked URL command
 */
- (void)requestCameraPermission:(CDVInvokedUrlCommand *)command;

/**
 * Parses raw AAMVA data string without scanning.
 *
 * @param command Cordova invoked URL command containing raw data string
 */
- (void)parseAAMVAData:(CDVInvokedUrlCommand *)command;

@end

NS_ASSUME_NONNULL_END
