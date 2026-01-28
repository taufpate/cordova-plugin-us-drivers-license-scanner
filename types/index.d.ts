/**
 * TypeScript definitions for cordova-plugin-us-drivers-license-scanner
 *
 * US Drivers License Scanner Plugin
 * Scans and parses US Driver Licenses using PDF417 barcodes with guided scan flow.
 */

declare namespace DriversLicenseScanner {

    /**
     * Error codes returned by the plugin
     */
    interface ErrorCodes {
        /** Camera permission was denied */
        CAMERA_PERMISSION_DENIED: 'CAMERA_PERMISSION_DENIED';
        /** Camera hardware not available */
        CAMERA_NOT_AVAILABLE: 'CAMERA_NOT_AVAILABLE';
        /** Scan was cancelled by user */
        SCAN_CANCELLED: 'SCAN_CANCELLED';
        /** PDF417 barcode not found */
        BARCODE_NOT_FOUND: 'BARCODE_NOT_FOUND';
        /** Error parsing AAMVA data */
        PARSE_ERROR: 'PARSE_ERROR';
        /** Face not detected in image */
        FACE_NOT_DETECTED: 'FACE_NOT_DETECTED';
        /** Unknown error occurred */
        UNKNOWN_ERROR: 'UNKNOWN_ERROR';
    }

    /**
     * Scan states during the guided flow
     */
    interface ScanState {
        /** Scanning the front of the license */
        SCANNING_FRONT: 'SCANNING_FRONT';
        /** Showing flip instruction */
        FLIP_INSTRUCTION: 'FLIP_INSTRUCTION';
        /** Scanning the back of the license */
        SCANNING_BACK: 'SCANNING_BACK';
        /** Processing scan results */
        PROCESSING: 'PROCESSING';
        /** Scan completed successfully */
        COMPLETED: 'COMPLETED';
        /** Scan failed with error */
        ERROR: 'ERROR';
    }

    /**
     * Options for scanning
     */
    interface ScanOptions {
        /** Whether to capture full front/back images (default: true) */
        captureFullImages?: boolean;
        /** Whether to extract the portrait image (default: true) */
        extractPortrait?: boolean;
        /** Timeout per scan step in milliseconds (default: 30000) */
        scanTimeoutMs?: number;
        /** Whether to enable camera flash (default: false) */
        enableFlash?: boolean;
        /** Whether to vibrate on successful scan (default: true) */
        enableVibration?: boolean;
        /** Whether to play sound on successful scan (default: true) */
        enableSound?: boolean;
    }

    /**
     * Parsed AAMVA fields from driver license
     */
    interface ParsedFields {
        /** First name (DAC) */
        firstName: string;
        /** Middle name (DAD) */
        middleName: string;
        /** Last name / Family name (DCS) */
        lastName: string;
        /** Combined full name */
        fullName: string;
        /** Date of birth in ISO format (YYYY-MM-DD) */
        dateOfBirth: string;
        /** Raw date of birth from barcode */
        dateOfBirthRaw: string;
        /** Street address (DAG) */
        streetAddress: string;
        /** Street address line 2 (DAH) */
        streetAddress2?: string;
        /** City (DAI) */
        city: string;
        /** State code (DAJ) */
        state: string;
        /** ZIP code (DAK) */
        postalCode: string;
        /** Combined full address */
        fullAddress: string;
        /** License number (DAQ) */
        licenseNumber: string;
        /** Gender code (1=M, 2=F, 9=Unknown) (DBC) */
        genderCode: string;
        /** Gender string (Male/Female/Unknown) */
        gender: string;
        /** Issue date in ISO format (YYYY-MM-DD) (DBD) */
        issueDate: string;
        /** Raw issue date from barcode */
        issueDateRaw: string;
        /** Expiration date in ISO format (YYYY-MM-DD) (DBA) */
        expirationDate: string;
        /** Raw expiration date from barcode */
        expirationDateRaw: string;
        /** Issuing state */
        issuingState: string;
        /** Issuing country (DCG) */
        issuingCountry: string;
        /** Document discriminator (DCF) */
        documentDiscriminator: string;
        /** Height (DAU) */
        height: string;
        /** Eye color (DAY) */
        eyeColor: string;
        /** Hair color (DAZ) */
        hairColor: string;
        /** Weight (DAW/DAX) */
        weight: string;
        /** Name suffix (DCU) */
        nameSuffix: string;
        /** Name prefix (DAA) */
        namePrefix: string;
        /** AAMVA version number */
        aamvaVersion: string;
        /** Jurisdiction version */
        jurisdictionVersion: string;
        /** Jurisdiction code */
        jurisdictionCode: string;
        /** Additional parsed fields not in standard set */
        additionalFields: Record<string, string>;
        /** Whether the license is expired */
        isExpired: boolean;
        /** Whether parsing was successful */
        isValid: boolean;
        /** Error message if parsing failed */
        error?: string;
    }

    /**
     * Result of a successful scan
     */
    interface ScanResult {
        /** Raw data from front scan (usually null for front) */
        frontRawData: string | null;
        /** Raw PDF417 data from back scan */
        backRawData: string | null;
        /** Parsed AAMVA fields */
        parsedFields: ParsedFields;
        /** Base64-encoded portrait image (PNG or JPEG) */
        portraitImageBase64: string;
        /** Base64-encoded full front image (if captureFullImages is true) */
        fullFrontImageBase64?: string;
        /** Base64-encoded full back image (if captureFullImages is true) */
        fullBackImageBase64?: string;
    }

    /**
     * Error returned when scan fails
     */
    interface ScanError {
        /** Error code from ErrorCodes */
        code: string;
        /** Human-readable error message */
        message: string;
    }

    /**
     * Camera permission status
     */
    interface CameraStatus {
        /** Whether device has a camera */
        hasCamera: boolean;
        /** Whether camera permission is granted */
        hasPermission: boolean;
        /** Whether permission can be requested */
        canRequestPermission: boolean;
    }

    /**
     * DriversLicenseScanner plugin interface
     */
    interface DriversLicenseScannerPlugin {
        /** Error code constants */
        ErrorCodes: ErrorCodes;
        /** Scan state constants */
        ScanState: ScanState;

        /**
         * Scans a US Driver License using a guided two-step flow.
         *
         * The scan flow is:
         * 1. Scan the FRONT of the license (captures image, detects face for portrait)
         * 2. User is instructed to flip the license
         * 3. Scan the BACK of the license (reads PDF417 barcode)
         * 4. Returns combined result with parsed AAMVA data
         *
         * @param options Optional scan configuration
         * @returns Promise resolving to scan result
         */
        scanDriverLicense(options?: ScanOptions): Promise<ScanResult>;

        /**
         * Checks if the device has camera capability and permissions.
         *
         * @returns Promise resolving to camera status
         */
        checkCameraPermission(): Promise<CameraStatus>;

        /**
         * Requests camera permission from the user.
         *
         * @returns Promise resolving to true if permission granted
         */
        requestCameraPermission(): Promise<boolean>;

        /**
         * Parses raw AAMVA data string into structured fields.
         * Can be used to parse data obtained from other sources.
         *
         * @param rawData Raw AAMVA data string from PDF417 barcode
         * @returns Promise resolving to parsed fields
         */
        parseAAMVAData(rawData: string): Promise<ParsedFields>;
    }
}

// Global declaration for Cordova plugin
interface CordovaPlugins {
    DriversLicenseScanner: DriversLicenseScanner.DriversLicenseScannerPlugin;
}

// Extend global cordova object
interface Cordova {
    plugins: CordovaPlugins;
}

// Module declaration for direct imports
declare module 'cordova-plugin-us-drivers-license-scanner' {
    const DriversLicenseScanner: DriversLicenseScanner.DriversLicenseScannerPlugin;
    export = DriversLicenseScanner;
}

// Export types for use in applications
export = DriversLicenseScanner;
