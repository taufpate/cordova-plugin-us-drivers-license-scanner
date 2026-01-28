/**
 * DriversLicenseScannerService
 *
 * Ionic Angular wrapper service for cordova-plugin-us-drivers-license-scanner.
 * Provides a type-safe, Promise-based API for scanning US Driver Licenses.
 */

import { Injectable } from '@angular/core';
import { Platform } from '@ionic/angular';

/**
 * Scan options for configuring the scanning behavior
 */
export interface ScanOptions {
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
 * Parsed AAMVA fields from driver license barcode
 */
export interface ParsedFields {
    firstName: string;
    middleName: string;
    lastName: string;
    fullName: string;
    dateOfBirth: string;
    dateOfBirthRaw: string;
    streetAddress: string;
    streetAddress2?: string;
    city: string;
    state: string;
    postalCode: string;
    fullAddress: string;
    licenseNumber: string;
    genderCode: string;
    gender: string;
    issueDate: string;
    issueDateRaw: string;
    expirationDate: string;
    expirationDateRaw: string;
    issuingState: string;
    issuingCountry: string;
    documentDiscriminator: string;
    height: string;
    eyeColor: string;
    hairColor: string;
    weight: string;
    nameSuffix: string;
    namePrefix: string;
    aamvaVersion: string;
    jurisdictionVersion: string;
    jurisdictionCode: string;
    additionalFields: Record<string, string>;
    isExpired: boolean;
    isValid: boolean;
    error?: string;
}

/**
 * Result of a successful scan operation
 */
export interface ScanResult {
    /** Raw data from front scan (usually null) */
    frontRawData: string | null;
    /** Raw PDF417 data from back scan */
    backRawData: string | null;
    /** Parsed AAMVA fields */
    parsedFields: ParsedFields;
    /** Base64-encoded portrait image */
    portraitImageBase64: string;
    /** Base64-encoded full front image */
    fullFrontImageBase64?: string;
    /** Base64-encoded full back image */
    fullBackImageBase64?: string;
}

/**
 * Error returned when scan fails
 */
export interface ScanError {
    /** Error code */
    code: string;
    /** Human-readable error message */
    message: string;
}

/**
 * Camera permission status
 */
export interface CameraStatus {
    /** Whether device has a camera */
    hasCamera: boolean;
    /** Whether camera permission is granted */
    hasPermission: boolean;
    /** Whether permission can be requested */
    canRequestPermission: boolean;
}

/**
 * Error codes
 */
export enum ErrorCode {
    CAMERA_PERMISSION_DENIED = 'CAMERA_PERMISSION_DENIED',
    CAMERA_NOT_AVAILABLE = 'CAMERA_NOT_AVAILABLE',
    SCAN_CANCELLED = 'SCAN_CANCELLED',
    BARCODE_NOT_FOUND = 'BARCODE_NOT_FOUND',
    PARSE_ERROR = 'PARSE_ERROR',
    FACE_NOT_DETECTED = 'FACE_NOT_DETECTED',
    UNKNOWN_ERROR = 'UNKNOWN_ERROR',
    PLUGIN_NOT_AVAILABLE = 'PLUGIN_NOT_AVAILABLE'
}

// Declare the Cordova plugin interface
declare const cordova: {
    plugins: {
        DriversLicenseScanner: {
            scanDriverLicense(options?: ScanOptions): Promise<ScanResult>;
            checkCameraPermission(): Promise<CameraStatus>;
            requestCameraPermission(): Promise<boolean>;
            parseAAMVAData(rawData: string): Promise<ParsedFields>;
        };
    };
};

/**
 * Angular service wrapper for the US Drivers License Scanner Cordova plugin.
 *
 * @example
 * ```typescript
 * import { DriversLicenseScannerService } from './drivers-license-scanner.service';
 *
 * constructor(private scanner: DriversLicenseScannerService) {}
 *
 * async scanLicense() {
 *   try {
 *     const result = await this.scanner.scanDriverLicense();
 *     console.log('Name:', result.parsedFields.fullName);
 *     console.log('Portrait:', result.portraitImageBase64);
 *   } catch (error) {
 *     console.error('Scan failed:', error.code, error.message);
 *   }
 * }
 * ```
 */
@Injectable({
    providedIn: 'root'
})
export class DriversLicenseScannerService {

    constructor(private platform: Platform) {}

    /**
     * Checks if the plugin is available and ready to use.
     *
     * @returns true if the plugin is available
     */
    isAvailable(): boolean {
        return this.platform.is('cordova') &&
               typeof cordova !== 'undefined' &&
               cordova.plugins &&
               cordova.plugins.DriversLicenseScanner !== undefined;
    }

    /**
     * Gets the native plugin instance.
     *
     * @throws Error if plugin is not available
     */
    private getPlugin() {
        if (!this.isAvailable()) {
            throw {
                code: ErrorCode.PLUGIN_NOT_AVAILABLE,
                message: 'DriversLicenseScanner plugin is not available. Make sure you are running on a device with the plugin installed.'
            } as ScanError;
        }
        return cordova.plugins.DriversLicenseScanner;
    }

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
     * @throws ScanError if scan fails
     *
     * @example
     * ```typescript
     * const result = await this.scanner.scanDriverLicense({
     *   captureFullImages: true,
     *   extractPortrait: true
     * });
     * ```
     */
    async scanDriverLicense(options?: ScanOptions): Promise<ScanResult> {
        await this.platform.ready();
        return this.getPlugin().scanDriverLicense(options);
    }

    /**
     * Checks if the device has camera capability and permissions.
     *
     * @returns Promise resolving to camera status
     * @throws ScanError if check fails
     *
     * @example
     * ```typescript
     * const status = await this.scanner.checkCameraPermission();
     * if (status.hasPermission) {
     *   // Can proceed with scanning
     * }
     * ```
     */
    async checkCameraPermission(): Promise<CameraStatus> {
        await this.platform.ready();
        return this.getPlugin().checkCameraPermission();
    }

    /**
     * Requests camera permission from the user.
     *
     * @returns Promise resolving to true if permission granted
     * @throws ScanError if permission request fails
     *
     * @example
     * ```typescript
     * const granted = await this.scanner.requestCameraPermission();
     * if (granted) {
     *   // Permission granted, can scan
     * }
     * ```
     */
    async requestCameraPermission(): Promise<boolean> {
        await this.platform.ready();
        return this.getPlugin().requestCameraPermission();
    }

    /**
     * Parses raw AAMVA data string into structured fields.
     * Can be used to parse data obtained from other barcode scanners.
     *
     * @param rawData Raw AAMVA data string from PDF417 barcode
     * @returns Promise resolving to parsed fields
     * @throws ScanError if parsing fails
     *
     * @example
     * ```typescript
     * const parsed = await this.scanner.parseAAMVAData(rawBarcodeString);
     * console.log('Name:', parsed.fullName);
     * ```
     */
    async parseAAMVAData(rawData: string): Promise<ParsedFields> {
        await this.platform.ready();
        return this.getPlugin().parseAAMVAData(rawData);
    }

    /**
     * Convenience method to scan and get just the parsed fields.
     *
     * @param options Optional scan configuration
     * @returns Promise resolving to parsed fields only
     *
     * @example
     * ```typescript
     * const fields = await this.scanner.scanAndGetFields();
     * console.log('License Number:', fields.licenseNumber);
     * ```
     */
    async scanAndGetFields(options?: ScanOptions): Promise<ParsedFields> {
        const result = await this.scanDriverLicense(options);
        return result.parsedFields;
    }

    /**
     * Convenience method to check if license is expired.
     *
     * @param options Optional scan configuration
     * @returns Promise resolving to expiration status
     *
     * @example
     * ```typescript
     * const isExpired = await this.scanner.checkIfExpired();
     * if (isExpired) {
     *   console.log('License is expired!');
     * }
     * ```
     */
    async checkIfExpired(options?: ScanOptions): Promise<boolean> {
        const result = await this.scanDriverLicense(options);
        return result.parsedFields.isExpired;
    }

    /**
     * Creates a data URL from a base64 image string for display.
     *
     * @param base64 Base64 encoded image string
     * @param format Image format (default: 'jpeg')
     * @returns Data URL string
     *
     * @example
     * ```typescript
     * const result = await this.scanner.scanDriverLicense();
     * const imageUrl = this.scanner.createImageUrl(result.portraitImageBase64);
     * // Use in template: <img [src]="imageUrl">
     * ```
     */
    createImageUrl(base64: string, format: string = 'jpeg'): string {
        if (!base64) {
            return '';
        }
        return `data:image/${format};base64,${base64}`;
    }

    /**
     * Formats a parsed date for display.
     *
     * @param isoDate ISO date string (YYYY-MM-DD)
     * @param locale Locale for formatting (default: 'en-US')
     * @returns Formatted date string
     *
     * @example
     * ```typescript
     * const result = await this.scanner.scanDriverLicense();
     * const dob = this.scanner.formatDate(result.parsedFields.dateOfBirth);
     * // Returns: "January 1, 1980"
     * ```
     */
    formatDate(isoDate: string, locale: string = 'en-US'): string {
        if (!isoDate || isoDate.length === 0) {
            return '';
        }

        try {
            const date = new Date(isoDate);
            return date.toLocaleDateString(locale, {
                year: 'numeric',
                month: 'long',
                day: 'numeric'
            });
        } catch {
            return isoDate;
        }
    }
}
