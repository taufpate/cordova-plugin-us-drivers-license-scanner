/**
 * DriversLicenseScannerPlugin.java
 *
 * Main Cordova plugin entry point for US Drivers License Scanner.
 * Handles JavaScript interface calls and manages the scanning activity lifecycle.
 */
package com.sos.driverslicensescanner;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Cordova plugin for scanning US Driver Licenses using PDF417 barcodes.
 * Provides a guided scan flow with front scan, flip instruction, and back scan.
 */
public class DriversLicenseScannerPlugin extends CordovaPlugin {

    private static final String TAG = "DriversLicenseScanner";

    // Request codes
    private static final int CAMERA_PERMISSION_REQUEST = 1001;
    private static final int SCAN_ACTIVITY_REQUEST = 1002;

    // Action names matching JavaScript interface
    private static final String ACTION_SCAN = "scanDriverLicense";
    private static final String ACTION_CHECK_PERMISSION = "checkCameraPermission";
    private static final String ACTION_REQUEST_PERMISSION = "requestCameraPermission";
    private static final String ACTION_PARSE_AAMVA = "parseAAMVAData";

    // Error codes matching JavaScript interface
    private static final String ERROR_CAMERA_PERMISSION_DENIED = "CAMERA_PERMISSION_DENIED";
    private static final String ERROR_CAMERA_NOT_AVAILABLE = "CAMERA_NOT_AVAILABLE";
    private static final String ERROR_SCAN_CANCELLED = "SCAN_CANCELLED";
    private static final String ERROR_BARCODE_NOT_FOUND = "BARCODE_NOT_FOUND";
    private static final String ERROR_PARSE_ERROR = "PARSE_ERROR";
    private static final String ERROR_UNKNOWN = "UNKNOWN_ERROR";

    // Intent extras keys
    public static final String EXTRA_OPTIONS = "options";
    public static final String EXTRA_RESULT = "result";
    public static final String EXTRA_ERROR_CODE = "errorCode";
    public static final String EXTRA_ERROR_MESSAGE = "errorMessage";

    // Callback contexts for async operations
    private CallbackContext scanCallbackContext;
    private CallbackContext permissionCallbackContext;

    // Current scan options
    private JSONObject currentScanOptions;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        Log.d(TAG, "Execute action: " + action);

        switch (action) {
            case ACTION_SCAN:
                handleScanAction(args, callbackContext);
                return true;

            case ACTION_CHECK_PERMISSION:
                handleCheckPermissionAction(callbackContext);
                return true;

            case ACTION_REQUEST_PERMISSION:
                handleRequestPermissionAction(callbackContext);
                return true;

            case ACTION_PARSE_AAMVA:
                handleParseAAMVAAction(args, callbackContext);
                return true;

            default:
                Log.w(TAG, "Unknown action: " + action);
                callbackContext.error(createErrorResponse(ERROR_UNKNOWN, "Unknown action: " + action));
                return false;
        }
    }

    /**
     * Handles the scanDriverLicense action.
     * Checks permissions and launches the scanner activity.
     */
    private void handleScanAction(JSONArray args, CallbackContext callbackContext) {
        try {
            // Parse options
            currentScanOptions = args.length() > 0 ? args.getJSONObject(0) : new JSONObject();
            scanCallbackContext = callbackContext;

            // Check if camera is available
            if (!hasCameraHardware()) {
                callbackContext.error(createErrorResponse(ERROR_CAMERA_NOT_AVAILABLE, "Device does not have a camera"));
                return;
            }

            // Check camera permission
            if (hasCameraPermission()) {
                launchScannerActivity();
            } else {
                // Request permission first
                requestCameraPermissionInternal(true);
            }

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing scan options", e);
            callbackContext.error(createErrorResponse(ERROR_PARSE_ERROR, "Invalid scan options: " + e.getMessage()));
        }
    }

    /**
     * Handles the checkCameraPermission action.
     */
    private void handleCheckPermissionAction(CallbackContext callbackContext) {
        try {
            JSONObject result = new JSONObject();
            result.put("hasCamera", hasCameraHardware());
            result.put("hasPermission", hasCameraPermission());
            result.put("canRequestPermission", canRequestCameraPermission());
            callbackContext.success(result);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating permission status", e);
            callbackContext.error(createErrorResponse(ERROR_UNKNOWN, "Failed to check permission status"));
        }
    }

    /**
     * Handles the requestCameraPermission action.
     */
    private void handleRequestPermissionAction(CallbackContext callbackContext) {
        if (hasCameraPermission()) {
            try {
                JSONObject result = new JSONObject();
                result.put("granted", true);
                callbackContext.success(result);
            } catch (JSONException e) {
                callbackContext.error(createErrorResponse(ERROR_UNKNOWN, e.getMessage()));
            }
        } else {
            permissionCallbackContext = callbackContext;
            requestCameraPermissionInternal(false);
        }
    }

    /**
     * Handles the parseAAMVAData action.
     * Parses raw AAMVA data without scanning.
     */
    private void handleParseAAMVAAction(JSONArray args, CallbackContext callbackContext) {
        try {
            if (args.length() < 1 || args.isNull(0)) {
                callbackContext.error(createErrorResponse(ERROR_PARSE_ERROR, "No data provided"));
                return;
            }

            String rawData = args.getString(0);
            AAMVAParser parser = new AAMVAParser();
            JSONObject parsed = parser.parse(rawData);

            if (parsed != null) {
                callbackContext.success(parsed);
            } else {
                callbackContext.error(createErrorResponse(ERROR_PARSE_ERROR, "Failed to parse AAMVA data"));
            }

        } catch (JSONException e) {
            Log.e(TAG, "Error parsing AAMVA data", e);
            callbackContext.error(createErrorResponse(ERROR_PARSE_ERROR, "Parse error: " + e.getMessage()));
        }
    }

    /**
     * Checks if the device has camera hardware.
     */
    private boolean hasCameraHardware() {
        return cordova.getActivity().getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
    }

    /**
     * Checks if camera permission is granted.
     */
    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(cordova.getActivity(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Checks if we can request camera permission (not permanently denied).
     */
    private boolean canRequestCameraPermission() {
        if (hasCameraPermission()) {
            return false; // Already have it
        }

        // Check if we should show rationale (means not permanently denied)
        return ActivityCompat.shouldShowRequestPermissionRationale(
                cordova.getActivity(), Manifest.permission.CAMERA);
    }

    /**
     * Requests camera permission.
     *
     * @param forScanning True if requesting for scanning, false for explicit permission request
     */
    private void requestCameraPermissionInternal(boolean forScanning) {
        Log.d(TAG, "Requesting camera permission, forScanning=" + forScanning);
        cordova.requestPermission(this, CAMERA_PERMISSION_REQUEST, Manifest.permission.CAMERA);
    }

    /**
     * Launches the scanner activity with current options.
     */
    private void launchScannerActivity() {
        Log.d(TAG, "Launching scanner activity");

        Activity activity = cordova.getActivity();
        Intent intent = new Intent(activity, ScannerActivity.class);
        intent.putExtra(EXTRA_OPTIONS, currentScanOptions.toString());

        cordova.startActivityForResult(this, intent, SCAN_ACTIVITY_REQUEST);
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        Log.d(TAG, "onRequestPermissionResult: requestCode=" + requestCode);

        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;

            if (permissionCallbackContext != null) {
                // Explicit permission request
                try {
                    JSONObject result = new JSONObject();
                    result.put("granted", granted);
                    permissionCallbackContext.success(result);
                } catch (JSONException e) {
                    permissionCallbackContext.error(createErrorResponse(ERROR_UNKNOWN, e.getMessage()));
                }
                permissionCallbackContext = null;
            } else if (scanCallbackContext != null) {
                // Permission requested for scanning
                if (granted) {
                    launchScannerActivity();
                } else {
                    scanCallbackContext.error(createErrorResponse(
                            ERROR_CAMERA_PERMISSION_DENIED,
                            "Camera permission is required to scan driver licenses"
                    ));
                    scanCallbackContext = null;
                }
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        Log.d(TAG, "onActivityResult: requestCode=" + requestCode + ", resultCode=" + resultCode);

        if (requestCode == SCAN_ACTIVITY_REQUEST && scanCallbackContext != null) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Read result JSON — may be in a file to avoid Binder transaction limit.
                // Base64-encoded images can exceed the ~1MB IPC limit, causing ANR.
                String resultJson;
                if (data.getBooleanExtra("resultInFile", false)) {
                    String filePath = data.getStringExtra(EXTRA_RESULT);
                    try {
                        java.io.File resultFile = new java.io.File(filePath);
                        java.io.FileInputStream fis = new java.io.FileInputStream(resultFile);
                        byte[] bytes = new byte[(int) resultFile.length()];
                        fis.read(bytes);
                        fis.close();
                        resultJson = new String(bytes, "UTF-8");
                        resultFile.delete();
                    } catch (Exception e) {
                        Log.e(TAG, "Error reading result file: " + filePath, e);
                        scanCallbackContext.error(createErrorResponse(ERROR_PARSE_ERROR,
                                "Failed to read scan result file"));
                        scanCallbackContext = null;
                        return;
                    }
                } else {
                    resultJson = data.getStringExtra(EXTRA_RESULT);
                }

                if (resultJson != null) {
                    try {
                        JSONObject result = new JSONObject(resultJson);
                        scanCallbackContext.success(result);
                    } catch (JSONException e) {
                        Log.e(TAG, "Error parsing scan result", e);
                        scanCallbackContext.error(createErrorResponse(ERROR_PARSE_ERROR, "Invalid scan result"));
                    }
                } else {
                    scanCallbackContext.error(createErrorResponse(ERROR_UNKNOWN, "No scan result returned"));
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                // User cancelled or error
                String errorCode = data != null ? data.getStringExtra(EXTRA_ERROR_CODE) : null;
                String errorMessage = data != null ? data.getStringExtra(EXTRA_ERROR_MESSAGE) : null;

                if (errorCode != null) {
                    scanCallbackContext.error(createErrorResponse(errorCode, errorMessage));
                } else {
                    scanCallbackContext.error(createErrorResponse(ERROR_SCAN_CANCELLED, "Scan was cancelled by user"));
                }
            } else {
                scanCallbackContext.error(createErrorResponse(ERROR_UNKNOWN, "Unknown result code: " + resultCode));
            }
            scanCallbackContext = null;
        }
    }

    /**
     * Creates a JSON error response object.
     */
    private JSONObject createErrorResponse(String code, String message) {
        JSONObject error = new JSONObject();
        try {
            error.put("code", code);
            error.put("message", message);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating error response", e);
        }
        return error;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        scanCallbackContext = null;
        permissionCallbackContext = null;
    }
}
