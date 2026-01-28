# US Drivers License Scanner - Cordova Plugin

A Cordova/Ionic plugin for scanning US Driver Licenses using PDF417 barcodes with a guided two-step scan flow. Fully offline, no paid SDKs, no API keys required.

## Features

- **Guided Scan Flow**: Front scan → Flip instruction → Back scan
- **PDF417 Barcode Decoding**: Using ZXing (Android) and ZXingObjC (iOS)
- **AAMVA Data Parsing**: Full parsing of all standard AAMVA fields
- **Portrait Extraction**: Face detection using ML Kit (Android) and Vision (iOS)
- **Full Image Capture**: Optional capture of front and back license images
- **Fully Offline**: No network calls, no cloud services, no API keys
- **Free and Open Source**: Uses only free, open-source libraries

## Platform Compatibility

| Platform | Version | Notes |
|----------|---------|-------|
| Android | cordova-android 14.0.1+ | minSdk 24, CameraX + ZXing |
| iOS | cordova-ios 7.1.1+ | iOS 13.0+, AVFoundation + ZXingObjC |

## Installation

### Plugin Installation

```bash
cordova plugin add cordova-plugin-us-drivers-license-scanner
```

Or from local path:

```bash
cordova plugin add ./cordova-plugin-us-drivers-license-scanner
```

### Platform Setup

#### Android

The plugin automatically configures the following:

- Camera permission in AndroidManifest.xml
- Required Gradle dependencies (CameraX, ZXing, ML Kit)
- Kotlin support

#### iOS

The plugin automatically configures:

- Camera usage description in Info.plist
- ZXingObjC via CocoaPods

After adding the iOS platform, run:

```bash
cd platforms/ios
pod install
```

## API

### scanDriverLicense(options?)

Scans a US Driver License using a guided two-step flow.

```typescript
interface ScanOptions {
  captureFullImages?: boolean;    // Default: true
  extractPortrait?: boolean;      // Default: true
  scanTimeoutMs?: number;         // Default: 30000
  enableFlash?: boolean;          // Default: false
  enableVibration?: boolean;      // Default: true
  enableSound?: boolean;          // Default: true
}

interface ScanResult {
  frontRawData: string | null;
  backRawData: string | null;
  parsedFields: ParsedFields;
  portraitImageBase64: string;
  fullFrontImageBase64?: string;
  fullBackImageBase64?: string;
}
```

**Example:**

```javascript
cordova.plugins.DriversLicenseScanner.scanDriverLicense({
  captureFullImages: true,
  extractPortrait: true
})
.then(function(result) {
  console.log('Name:', result.parsedFields.fullName);
  console.log('License #:', result.parsedFields.licenseNumber);
  console.log('DOB:', result.parsedFields.dateOfBirth);

  // Display portrait image
  var img = document.getElementById('portrait');
  img.src = 'data:image/jpeg;base64,' + result.portraitImageBase64;
})
.catch(function(error) {
  console.error('Scan error:', error.code, error.message);
});
```

### checkCameraPermission()

Checks if the device has camera capability and permissions.

```javascript
cordova.plugins.DriversLicenseScanner.checkCameraPermission()
.then(function(status) {
  console.log('Has camera:', status.hasCamera);
  console.log('Has permission:', status.hasPermission);
  console.log('Can request:', status.canRequestPermission);
});
```

### requestCameraPermission()

Requests camera permission from the user.

```javascript
cordova.plugins.DriversLicenseScanner.requestCameraPermission()
.then(function(granted) {
  if (granted) {
    // Permission granted, can scan
  }
});
```

### parseAAMVAData(rawData)

Parses raw AAMVA data string without scanning.

```javascript
cordova.plugins.DriversLicenseScanner.parseAAMVAData(rawBarcodeString)
.then(function(parsed) {
  console.log('Parsed fields:', parsed);
});
```

## Parsed AAMVA Fields

The `parsedFields` object contains the following fields:

| Field | AAMVA Code | Description |
|-------|------------|-------------|
| firstName | DAC | First name |
| middleName | DAD | Middle name |
| lastName | DCS | Last name / Family name |
| fullName | - | Combined full name |
| dateOfBirth | DBB | Date of birth (ISO format) |
| dateOfBirthRaw | DBB | Raw date from barcode |
| streetAddress | DAG | Street address |
| city | DAI | City |
| state | DAJ | State code |
| postalCode | DAK | ZIP code |
| fullAddress | - | Combined full address |
| licenseNumber | DAQ | Driver license number |
| gender | DBC | Gender (Male/Female/Unknown) |
| genderCode | DBC | Raw gender code |
| issueDate | DBD | Issue date (ISO format) |
| expirationDate | DBA | Expiration date (ISO format) |
| issuingState | - | Issuing state |
| issuingCountry | DCG | Country code |
| documentDiscriminator | DCF | Document discriminator |
| height | DAU | Height |
| eyeColor | DAY | Eye color |
| hairColor | DAZ | Hair color |
| weight | DAW/DAX | Weight |
| nameSuffix | DCU | Name suffix |
| aamvaVersion | - | AAMVA version number |
| isExpired | - | Whether license is expired |
| isValid | - | Whether parsing was successful |

## Error Codes

| Code | Description |
|------|-------------|
| CAMERA_PERMISSION_DENIED | Camera permission was denied |
| CAMERA_NOT_AVAILABLE | Camera hardware not available |
| SCAN_CANCELLED | User cancelled the scan |
| BARCODE_NOT_FOUND | PDF417 barcode not detected |
| PARSE_ERROR | Error parsing AAMVA data |
| FACE_NOT_DETECTED | Face not detected for portrait |
| UNKNOWN_ERROR | Unknown error occurred |

## Ionic Angular Integration

Copy the service from `ionic-wrapper/src/drivers-license-scanner.service.ts` to your project:

```typescript
import { DriversLicenseScannerService } from './services/drivers-license-scanner.service';

@Component({...})
export class MyPage {
  constructor(private scanner: DriversLicenseScannerService) {}

  async scan() {
    try {
      const result = await this.scanner.scanDriverLicense();
      console.log('Name:', result.parsedFields.fullName);

      // Display portrait using helper method
      this.portraitUrl = this.scanner.createImageUrl(result.portraitImageBase64);
    } catch (error) {
      console.error('Scan failed:', error.code, error.message);
    }
  }
}
```

## Project Structure

```
cordova-plugin-us-drivers-license-scanner/
├── plugin.xml                          # Plugin configuration
├── package.json                        # npm package configuration
├── README.md                           # This file
├── www/
│   └── DriversLicenseScanner.js        # JavaScript interface
├── types/
│   └── index.d.ts                      # TypeScript definitions
├── ionic-wrapper/
│   └── src/
│       ├── drivers-license-scanner.service.ts  # Angular service
│       └── index.ts                    # Module exports
├── src/
│   ├── android/
│   │   ├── DriversLicenseScannerPlugin.java    # Plugin entry point
│   │   ├── ScannerActivity.java                # Scanner UI
│   │   ├── CameraManager.java                  # CameraX management
│   │   ├── BarcodeAnalyzer.java                # ZXing PDF417 decoder
│   │   ├── AAMVAParser.java                    # AAMVA data parser
│   │   ├── FaceDetectorHelper.java             # ML Kit face detection
│   │   ├── ScanResult.java                     # Result data class
│   │   ├── ImageUtils.java                     # Image utilities
│   │   └── res/
│   │       ├── layout/activity_scanner.xml     # Scanner layout
│   │       ├── drawable/                       # Drawable resources
│   │       └── values/                         # String/color resources
│   └── ios/
│       ├── DriversLicenseScannerPlugin.h/m     # Plugin entry point
│       ├── ScannerViewController.h/m           # Scanner UI
│       ├── CameraManager.h/m                   # AVFoundation management
│       ├── BarcodeDecoder.h/m                  # ZXingObjC decoder
│       ├── AAMVAParser.h/m                     # AAMVA data parser
│       ├── FaceDetectorHelper.h/m              # Vision face detection
│       ├── ScanResult.h/m                      # Result data class
│       └── ImageUtils.h/m                      # Image utilities
```

## Test Application

A complete Ionic Angular test application is provided in `license-scanner-test-app/`.

### Running the Test App

```bash
cd license-scanner-test-app

# Install dependencies
npm install

# Add platforms
ionic cordova platform add android
ionic cordova platform add ios

# Run on device
ionic cordova run android
ionic cordova run ios
```

## Dependencies

### Android

- **ZXing Core 3.5.2**: PDF417 barcode decoding
- **CameraX 1.3.1**: Modern camera API
- **ML Kit Face Detection 16.1.5**: On-device face detection
- **AndroidX AppCompat/ConstraintLayout**: UI components

### iOS

- **ZXingObjC 3.6.9**: PDF417 barcode decoding (via CocoaPods)
- **AVFoundation**: Camera capture
- **Vision**: Face detection

## Known Limitations

### PDF417 Decoding

- **ZXing Limitations**: ZXing is a general-purpose barcode library and may not decode all AAMVA PDF417 barcodes reliably, especially:
  - Damaged or poorly printed barcodes
  - Barcodes with unusual encoding variations
  - Very old licenses with non-standard formats

- **Recommendations**:
  - Ensure good lighting when scanning
  - Hold the license steady and flat
  - Try different angles if barcode doesn't scan initially
  - The back scan has a 30-second timeout by default

### Portrait Extraction

- Face detection may not work for all licenses, especially:
  - Licenses with unusual portrait positions
  - Very old or damaged licenses
  - Licenses where the portrait is obscured
- Falls back to deterministic cropping based on standard US license layout

### Platform-Specific

- **Android**: Requires minSdk 24 (Android 7.0) due to CameraX requirements
- **iOS**: Requires iOS 13.0+ for Vision framework

## Permissions

### Android

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="true" />
```

### iOS

The plugin automatically adds camera usage description to Info.plist:

```xml
<key>NSCameraUsageDescription</key>
<string>This app requires camera access to scan driver licenses</string>
```

## License

MIT License

## Support

For issues and feature requests, please open an issue on the repository.
