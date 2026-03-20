/**
 * US Drivers License Scanner Plugin
 *
 * Cordova plugin for scanning US Driver Licenses using PDF417 barcodes.
 * Uses ZXing for barcode decoding, fully offline, no paid SDKs.
 *
 * Features:
 * - Guided scan flow (front → flip → back)
 * - PDF417 barcode decoding with ZXing
 * - AAMVA data parsing
 * - Portrait image extraction via face detection
 * - Full front/back image capture
 */

var exec = require('cordova/exec');

/**
 * DriversLicenseScanner module
 * @module DriversLicenseScanner
 */
var DriversLicenseScanner = {

    /**
     * Error codes returned by the plugin
     * @readonly
     * @enum {string}
     */
    ErrorCodes: {
        CAMERA_PERMISSION_DENIED: 'CAMERA_PERMISSION_DENIED',
        CAMERA_NOT_AVAILABLE: 'CAMERA_NOT_AVAILABLE',
        SCAN_CANCELLED: 'SCAN_CANCELLED',
        BARCODE_NOT_FOUND: 'BARCODE_NOT_FOUND',
        PARSE_ERROR: 'PARSE_ERROR',
        FACE_NOT_DETECTED: 'FACE_NOT_DETECTED',
        UNKNOWN_ERROR: 'UNKNOWN_ERROR'
    },

    /**
     * Scan states during the guided flow
     * @readonly
     * @enum {string}
     */
    ScanState: {
        SCANNING_FRONT: 'SCANNING_FRONT',
        FLIP_INSTRUCTION: 'FLIP_INSTRUCTION',
        SCANNING_BACK: 'SCANNING_BACK',
        PROCESSING: 'PROCESSING',
        COMPLETED: 'COMPLETED',
        ERROR: 'ERROR'
    },

    /**
     * Scans a US Driver License using a guided two-step flow.
     *
     * The scan flow is:
     * 1. Scan the FRONT of the license (captures image, detects face for portrait)
     * 2. User is instructed to flip the license
     * 3. Scan the BACK of the license (reads PDF417 barcode)
     * 4. Returns combined result with parsed AAMVA data
     *
     * @param {Object} [options] - Optional configuration options
     * @param {boolean} [options.captureFullImages=true] - Whether to capture full front/back images
     * @param {boolean} [options.extractPortrait=true] - Whether to extract the portrait image
     * @param {number} [options.scanTimeoutMs=30000] - Timeout per scan step in milliseconds
     * @param {boolean} [options.enableFlash=false] - Whether to enable flash/torch
     * @param {boolean} [options.enableVibration=true] - Whether to vibrate on successful scan
     * @param {boolean} [options.enableSound=true] - Whether to play sound on successful scan
     *
     * @returns {Promise<ScanResult>} Promise resolving to scan result
     *
     * @example
     * cordova.plugins.DriversLicenseScanner.scanDriverLicense()
     *   .then(function(result) {
     *     console.log('Parsed fields:', result.parsedFields);
     *     console.log('Portrait:', result.portraitImageBase64);
     *   })
     *   .catch(function(error) {
     *     console.error('Scan failed:', error.code, error.message);
     *   });
     */
    scanDriverLicense: function(options) {
        return new Promise(function(resolve, reject) {
            var defaultOptions = {
                captureFullImages: true,
                extractPortrait: true,
                scanTimeoutMs: 30000,
                enableFlash: false,
                enableVibration: true,
                enableSound: true
            };

            var mergedOptions = Object.assign({}, defaultOptions, options || {});

            exec(
                function(result) {
                    // Success callback - result is the scan result object
                    resolve(result);
                },
                function(error) {
                    // Error callback - error contains code and message
                    reject({
                        code: error.code || DriversLicenseScanner.ErrorCodes.UNKNOWN_ERROR,
                        message: error.message || 'Unknown error occurred during scan'
                    });
                },
                'DriversLicenseScanner',
                'scanDriverLicense',
                [mergedOptions]
            );
        });
    },

    /**
     * Checks if the device has camera capability and permissions.
     *
     * @returns {Promise<CameraStatus>} Promise resolving to camera status
     *
     * @example
     * cordova.plugins.DriversLicenseScanner.checkCameraPermission()
     *   .then(function(status) {
     *     if (status.hasPermission) {
     *       // Can proceed with scanning
     *     } else {
     *       // Need to request permission
     *     }
     *   });
     */
    checkCameraPermission: function() {
        return new Promise(function(resolve, reject) {
            exec(
                function(result) {
                    resolve({
                        hasCamera: result.hasCamera,
                        hasPermission: result.hasPermission,
                        canRequestPermission: result.canRequestPermission
                    });
                },
                function(error) {
                    reject({
                        code: error.code || DriversLicenseScanner.ErrorCodes.UNKNOWN_ERROR,
                        message: error.message || 'Failed to check camera permission'
                    });
                },
                'DriversLicenseScanner',
                'checkCameraPermission',
                []
            );
        });
    },

    /**
     * Requests camera permission from the user.
     *
     * @returns {Promise<boolean>} Promise resolving to true if permission granted
     *
     * @example
     * cordova.plugins.DriversLicenseScanner.requestCameraPermission()
     *   .then(function(granted) {
     *     if (granted) {
     *       // Permission granted, can scan
     *     }
     *   });
     */
    requestCameraPermission: function() {
        return new Promise(function(resolve, reject) {
            exec(
                function(result) {
                    resolve(result.granted);
                },
                function(error) {
                    reject({
                        code: DriversLicenseScanner.ErrorCodes.CAMERA_PERMISSION_DENIED,
                        message: error.message || 'Camera permission denied'
                    });
                },
                'DriversLicenseScanner',
                'requestCameraPermission',
                []
            );
        });
    },

    /**
     * Parses raw AAMVA data string into structured fields.
     * This can be used to parse data obtained from other sources.
     *
     * @param {string} rawData - Raw AAMVA data string from PDF417 barcode
     * @returns {Promise<ParsedFields>} Promise resolving to parsed fields
     *
     * @example
     * cordova.plugins.DriversLicenseScanner.parseAAMVAData(rawBarcodeString)
     *   .then(function(parsed) {
     *     console.log('First name:', parsed.firstName);
     *     console.log('Last name:', parsed.lastName);
     *   });
     */
    parseAAMVAData: function(rawData) {
        return new Promise(function(resolve, reject) {
            if (!rawData || typeof rawData !== 'string') {
                reject({
                    code: DriversLicenseScanner.ErrorCodes.PARSE_ERROR,
                    message: 'Invalid raw data provided'
                });
                return;
            }

            exec(
                function(result) {
                    resolve(result);
                },
                function(error) {
                    reject({
                        code: DriversLicenseScanner.ErrorCodes.PARSE_ERROR,
                        message: error.message || 'Failed to parse AAMVA data'
                    });
                },
                'DriversLicenseScanner',
                'parseAAMVAData',
                [rawData]
            );
        });
    }
};

module.exports = DriversLicenseScanner;

/**
 * @typedef {Object} ScanResult
 * @property {string|null} frontRawData - Raw data from front scan (usually null for front)
 * @property {string|null} backRawData - Raw PDF417 data from back scan
 * @property {ParsedFields} parsedFields - Parsed AAMVA fields
 * @property {string} portraitImageBase64 - Base64-encoded portrait image (PNG or JPEG)
 * @property {string} [fullFrontImageBase64] - Base64-encoded full front image
 * @property {string} [fullBackImageBase64] - Base64-encoded full back image
 */

/**
 * @typedef {Object} ParsedFields
 * @property {string} firstName - First name (DAC)
 * @property {string} middleName - Middle name (DAD)
 * @property {string} lastName - Last name (DCS)
 * @property {string} fullName - Combined full name
 * @property {string} dateOfBirth - Date of birth (YYYY-MM-DD format)
 * @property {string} dateOfBirthRaw - Raw date of birth from barcode
 * @property {string} streetAddress - Street address (DAG)
 * @property {string} city - City (DAI)
 * @property {string} state - State code (DAJ)
 * @property {string} postalCode - ZIP code (DAK)
 * @property {string} fullAddress - Combined full address
 * @property {string} licenseNumber - License number (DAQ)
 * @property {string} gender - Gender (M/F/U) (DBC)
 * @property {string} issueDate - Issue date (YYYY-MM-DD format) (DBD)
 * @property {string} issueDateRaw - Raw issue date from barcode
 * @property {string} expirationDate - Expiration date (YYYY-MM-DD format) (DBA)
 * @property {string} expirationDateRaw - Raw expiration date from barcode
 * @property {string} issuingState - Issuing state (DAJ)
 * @property {string} issuingCountry - Issuing country (DCG)
 * @property {string} documentDiscriminator - Document discriminator (DCF)
 * @property {string} height - Height (DAU)
 * @property {string} eyeColor - Eye color (DAY)
 * @property {string} hairColor - Hair color (DAZ)
 * @property {string} weight - Weight (DAW)
 * @property {string} nameSuffix - Name suffix (DCU)
 * @property {string} namePrefix - Name prefix (DAA)
 * @property {string} aamvaVersion - AAMVA version number
 * @property {string} jurisdictionVersion - Jurisdiction version
 * @property {Object} additionalFields - Any additional parsed fields
 * @property {boolean} isExpired - Whether the license is expired
 * @property {boolean} isValid - Whether parsing was successful
 */

/**
 * @typedef {Object} CameraStatus
 * @property {boolean} hasCamera - Whether device has a camera
 * @property {boolean} hasPermission - Whether camera permission is granted
 * @property {boolean} canRequestPermission - Whether permission can be requested
 */

/**
 * @typedef {Object} ScanError
 * @property {string} code - Error code from ErrorCodes enum
 * @property {string} message - Human-readable error message
 */
