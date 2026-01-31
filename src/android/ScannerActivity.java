/**
 * ScannerActivity.java
 *
 * Activity that handles the guided driver license scanning flow.
 * Implements a two-step scan process: front → flip → back.
 *
 * Front scan: Uses live video face detection to auto-capture when a face is visible.
 *   - Checks every 5th delivered video frame for face presence
 *   - Auto-captures when face detected (frontCaptureTriggered guard)
 *   - Falls back to capture after 10 seconds if no face found
 *
 * Back scan: Reads PDF417 barcode from video frames using strategy rotation.
 *   - Auto-torch activates after ~3s if barcode not detected
 *   - Resets BarcodeAnalyzer strategy index for fresh scan
 */
package com.sos.driverslicensescanner;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.constraintlayout.widget.ConstraintLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Scanner activity implementing the guided scan flow for driver licenses.
 */
public class ScannerActivity extends AppCompatActivity implements
        CameraManager.ScanCallback,
        FaceDetectorHelper.FaceDetectionCallback {

    private static final String TAG = "ScannerActivity";

    // Face detection runs every Nth delivered video frame (matches iOS kFaceCheckInterval = 5)
    private static final int FACE_CHECK_INTERVAL = 5;

    // If no face is found within this time, capture anyway (matches iOS kFaceDetectionFallbackTimeout = 10s)
    private static final long FACE_FALLBACK_TIMEOUT_MS = 10000;

    // Scan flow states
    private enum ScanState {
        SCANNING_FRONT,
        FLIP_INSTRUCTION,
        SCANNING_BACK,
        PROCESSING,
        COMPLETED,
        ERROR
    }

    // UI components - obtained by resource name lookup for Cordova compatibility
    private PreviewView previewView;
    private View overlayFrame;
    private TextView instructionText;
    private TextView statusText;
    private Button cancelButton;
    private Button flashButton;
    private ProgressBar progressBar;
    private ImageView flipIcon;
    private View flipInstructionContainer;

    // Managers and helpers
    private CameraManager cameraManager;
    private FaceDetectorHelper faceDetector;
    private AAMVAParser aamvaParser;
    private ExecutorService executorService;
    private Handler mainHandler;

    // Options from plugin
    private boolean captureFullImages = true;
    private boolean extractPortrait = true;
    private int scanTimeoutMs = 30000;
    private boolean enableFlash = false;
    private boolean enableVibration = true;
    private boolean enableSound = true;

    // Current state
    private ScanState currentState = ScanState.SCANNING_FRONT;
    private boolean isFlashOn = false;

    // Face detection state (front scan) — matches iOS ScannerViewController
    private boolean frontCaptureTriggered = false;
    private int faceCheckCounter = 0;

    // Scan results storage
    private Bitmap frontImage;
    private Bitmap backImage;
    private Bitmap portraitImage;
    private String frontRawData;
    private String backRawData;
    private JSONObject parsedFields;

    // Timeout handlers
    private Runnable timeoutRunnable;
    private Runnable faceFallbackRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on during scanning
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Set content view using resource ID lookup
        int layoutId = getResources().getIdentifier("activity_scanner", "layout", getPackageName());
        setContentView(layoutId);

        // Initialize components
        initializeViews();
        parseOptions();
        initializeManagers();

        // Start scanning flow
        transitionToState(ScanState.SCANNING_FRONT);
    }

    /**
     * Initialize view references using resource name lookup.
     */
    private void initializeViews() {
        previewView = findViewById(getResId("camera_preview"));
        overlayFrame = findViewById(getResId("overlay_frame"));
        instructionText = findViewById(getResId("instruction_text"));
        statusText = findViewById(getResId("status_text"));
        cancelButton = findViewById(getResId("cancel_button"));
        flashButton = findViewById(getResId("flash_button"));
        progressBar = findViewById(getResId("progress_bar"));
        flipIcon = findViewById(getResId("flip_icon"));
        flipInstructionContainer = findViewById(getResId("flip_instruction_container"));

        cancelButton.setOnClickListener(v -> cancelScan());
        flashButton.setOnClickListener(v -> toggleFlash());
    }

    /**
     * Gets resource ID by name for Cordova compatibility.
     */
    private int getResId(String name) {
        return getResources().getIdentifier(name, "id", getPackageName());
    }

    /**
     * Parse scan options from intent extras.
     */
    private void parseOptions() {
        Intent intent = getIntent();
        String optionsJson = intent.getStringExtra(DriversLicenseScannerPlugin.EXTRA_OPTIONS);

        if (optionsJson != null) {
            try {
                JSONObject options = new JSONObject(optionsJson);
                captureFullImages = options.optBoolean("captureFullImages", true);
                extractPortrait = options.optBoolean("extractPortrait", true);
                scanTimeoutMs = options.optInt("scanTimeoutMs", 30000);
                enableFlash = options.optBoolean("enableFlash", false);
                enableVibration = options.optBoolean("enableVibration", true);
                enableSound = options.optBoolean("enableSound", true);
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing options", e);
            }
        }
    }

    /**
     * Initialize manager classes.
     */
    private void initializeManagers() {
        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        aamvaParser = new AAMVAParser();
        faceDetector = new FaceDetectorHelper(this, this);

        cameraManager = new CameraManager(this, previewView, this);
    }

    /**
     * Transitions to a new scan state and updates UI accordingly.
     */
    private void transitionToState(ScanState newState) {
        Log.d(TAG, "Transitioning from " + currentState + " to " + newState);
        currentState = newState;

        // Cancel any pending timeouts
        cancelTimeout();

        mainHandler.post(() -> updateUIForState(newState));

        switch (newState) {
            case SCANNING_FRONT:
                startFrontScan();
                break;

            case FLIP_INSTRUCTION:
                showFlipInstruction();
                break;

            case SCANNING_BACK:
                startBackScan();
                break;

            case PROCESSING:
                processResults();
                break;

            case COMPLETED:
                finishWithSuccess();
                break;

            case ERROR:
                break;
        }
    }

    /**
     * Updates UI elements based on current state.
     */
    private void updateUIForState(ScanState state) {
        progressBar.setVisibility(View.GONE);
        flipInstructionContainer.setVisibility(View.GONE);
        previewView.setVisibility(View.VISIBLE);
        overlayFrame.setVisibility(View.VISIBLE);

        switch (state) {
            case SCANNING_FRONT:
                instructionText.setText("Position the FRONT of your driver license");
                statusText.setText("Looking for license photo...");
                setOverlayColor(Color.WHITE);
                break;

            case FLIP_INSTRUCTION:
                instructionText.setText("Front captured!");
                statusText.setText("Now flip the license to scan the back");
                flipInstructionContainer.setVisibility(View.VISIBLE);
                previewView.setVisibility(View.INVISIBLE);
                overlayFrame.setVisibility(View.INVISIBLE);
                setOverlayColor(Color.GREEN);
                break;

            case SCANNING_BACK:
                instructionText.setText("Position the BACK of your driver license");
                statusText.setText("Scanning barcode...");
                setOverlayColor(Color.WHITE);
                break;

            case PROCESSING:
                instructionText.setText("Processing...");
                statusText.setText("Analyzing license data");
                progressBar.setVisibility(View.VISIBLE);
                previewView.setVisibility(View.INVISIBLE);
                overlayFrame.setVisibility(View.INVISIBLE);
                break;

            case COMPLETED:
                instructionText.setText("Scan complete!");
                statusText.setText("");
                break;

            case ERROR:
                instructionText.setText("Scan failed");
                setOverlayColor(Color.RED);
                break;
        }
    }

    /**
     * Sets the overlay frame color to indicate scan status.
     */
    private void setOverlayColor(int color) {
        if (overlayFrame != null) {
            overlayFrame.setBackgroundResource(
                    getResources().getIdentifier("scan_overlay", "drawable", getPackageName())
            );
        }
    }

    /**
     * Starts the front side scanning process.
     * Uses face detection mode to auto-capture when a face is visible in video frames.
     * Matches iOS: startCameraWithBarcodeScanning:YES (enables video frames for face detection).
     */
    private void startFrontScan() {
        Log.d(TAG, "Starting front scan with face detection");

        frontCaptureTriggered = false;
        faceCheckCounter = 0;

        // Start camera with face detection mode (delivers video frames for face checking)
        cameraManager.startCamera(false, true);

        // Overall timeout: fail if front scan takes too long
        startTimeout(() -> {
            if (currentState == ScanState.SCANNING_FRONT) {
                finishWithError("SCAN_TIMEOUT", "Front scan timed out. Please try again.");
            }
        });

        // Face detection fallback: if no face found after 10 seconds, capture anyway
        // Matches iOS kFaceDetectionFallbackTimeout = 10.0
        faceFallbackRunnable = () -> {
            if (currentState == ScanState.SCANNING_FRONT && !frontCaptureTriggered) {
                Log.d(TAG, "Face fallback: capturing without face detection");
                frontCaptureTriggered = true;
                statusText.setText("Capturing...");
                cameraManager.captureImage();
            }
        };
        mainHandler.postDelayed(faceFallbackRunnable, FACE_FALLBACK_TIMEOUT_MS);
    }

    /**
     * Shows the flip instruction with animation.
     */
    private void showFlipInstruction() {
        Log.d(TAG, "Showing flip instruction");

        // Stop camera temporarily
        cameraManager.stopCamera();

        // Provide haptic feedback
        if (enableVibration) {
            vibrate();
        }

        // Auto-transition to back scan after 2.5s delay
        mainHandler.postDelayed(() -> {
            if (currentState == ScanState.FLIP_INSTRUCTION) {
                transitionToState(ScanState.SCANNING_BACK);
            }
        }, 2500);
    }

    /**
     * Starts the back side scanning process (barcode scanning).
     * Resets BarcodeAnalyzer strategy index for a fresh scan cycle.
     */
    private void startBackScan() {
        Log.d(TAG, "Starting back scan");

        // Reset barcode analyzer strategy rotation for fresh scan
        cameraManager.resetBarcodeAnalyzer();

        // Start camera with barcode scanning enabled
        cameraManager.startCamera(true);

        // Set timeout for back scan
        startTimeout(() -> {
            if (currentState == ScanState.SCANNING_BACK) {
                finishWithError("BARCODE_NOT_FOUND", "Could not detect barcode. Please try again.");
            }
        });
    }

    /**
     * Processes all collected scan results.
     */
    private void processResults() {
        Log.d(TAG, "Processing scan results");

        executorService.execute(() -> {
            try {
                // Parse AAMVA data from back scan
                if (backRawData != null) {
                    parsedFields = aamvaParser.parse(backRawData);
                } else {
                    parsedFields = new JSONObject();
                    parsedFields.put("isValid", false);
                    parsedFields.put("error", "No barcode data captured");
                }

                // Extract portrait if requested and face was detected
                if (extractPortrait && frontImage != null && portraitImage == null) {
                    portraitImage = ImageUtils.extractPortraitDeterministic(frontImage);
                }

                // Build final result
                JSONObject result = buildScanResult();

                mainHandler.post(() -> {
                    if (currentState == ScanState.PROCESSING) {
                        finishWithResult(result);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error processing results", e);
                mainHandler.post(() -> finishWithError("PARSE_ERROR", "Failed to process scan data: " + e.getMessage()));
            }
        });
    }

    /**
     * Builds the final scan result JSON object.
     */
    private JSONObject buildScanResult() throws JSONException {
        JSONObject result = new JSONObject();

        result.put("frontRawData", frontRawData);
        result.put("backRawData", backRawData);

        result.put("parsedFields", parsedFields != null ? parsedFields : new JSONObject());

        if (portraitImage != null) {
            result.put("portraitImageBase64", ImageUtils.bitmapToBase64(portraitImage, "JPEG", 85));
        } else {
            result.put("portraitImageBase64", "");
        }

        if (captureFullImages) {
            if (frontImage != null) {
                result.put("fullFrontImageBase64", ImageUtils.bitmapToBase64(frontImage, "JPEG", 85));
            }
            if (backImage != null) {
                result.put("fullBackImageBase64", ImageUtils.bitmapToBase64(backImage, "JPEG", 85));
            }
        }

        return result;
    }

    // ==================== CameraManager.ScanCallback Implementation ====================

    @Override
    public void onImageCaptured(Bitmap image) {
        Log.d(TAG, "Image captured, state=" + currentState);

        if (currentState == ScanState.SCANNING_FRONT) {
            frontImage = image;

            // Attempt face detection for portrait extraction
            if (extractPortrait) {
                faceDetector.detectFace(image);
            } else {
                transitionToState(ScanState.FLIP_INSTRUCTION);
            }

        } else if (currentState == ScanState.SCANNING_BACK) {
            backImage = image;
        }
    }

    @Override
    public void onBarcodeDetected(String rawData) {
        Log.d(TAG, "Barcode detected, state=" + currentState + ", data length=" + (rawData != null ? rawData.length() : 0));

        if (currentState == ScanState.SCANNING_BACK && rawData != null && !rawData.isEmpty()) {
            backRawData = rawData;

            // Provide haptic feedback
            if (enableVibration) {
                vibrate();
            }

            // Capture the back image as well
            cameraManager.captureImage();

            // Short delay then process
            mainHandler.postDelayed(() -> {
                if (currentState == ScanState.SCANNING_BACK) {
                    transitionToState(ScanState.PROCESSING);
                }
            }, 300);
        }
    }

    @Override
    public void onScanError(String errorCode, String errorMessage) {
        Log.e(TAG, "Scan error: " + errorCode + " - " + errorMessage);
        finishWithError(errorCode, errorMessage);
    }

    /**
     * Called with each delivered video frame during face detection mode.
     * Checks every 5th frame for face presence and auto-captures when found.
     * Matches iOS ScannerViewController.cameraManager:didReceiveSampleBuffer:
     */
    @Override
    public void onVideoFrameAvailable(Bitmap frame) {
        if (currentState != ScanState.SCANNING_FRONT || frontCaptureTriggered) {
            if (frame != null && !frame.isRecycled()) {
                frame.recycle();
            }
            return;
        }

        // Throttle face checks: every 5th delivered frame (matches iOS kFaceCheckInterval = 5)
        faceCheckCounter++;
        if (faceCheckCounter % FACE_CHECK_INTERVAL != 0) {
            if (frame != null && !frame.isRecycled()) {
                frame.recycle();
            }
            return;
        }

        // Run face detection on the video frame
        faceDetector.checkForFacePresence(frame, faceFound -> {
            // Recycle the frame now that detection is complete
            if (frame != null && !frame.isRecycled()) {
                frame.recycle();
            }

            if (faceFound && currentState == ScanState.SCANNING_FRONT && !frontCaptureTriggered) {
                Log.d(TAG, "Face detected in video frame — auto-capturing");

                frontCaptureTriggered = true;

                mainHandler.post(() -> {
                    if (currentState == ScanState.SCANNING_FRONT) {
                        statusText.setText("Face detected! Capturing...");
                        cameraManager.captureImage();
                    }
                });
            }
        });
    }

    // ==================== FaceDetectorHelper.FaceDetectionCallback Implementation ====================

    @Override
    public void onFaceDetected(Bitmap croppedFace) {
        Log.d(TAG, "Face detected and cropped from captured photo");
        portraitImage = croppedFace;

        mainHandler.post(() -> {
            if (currentState == ScanState.SCANNING_FRONT) {
                transitionToState(ScanState.FLIP_INSTRUCTION);
            }
        });
    }

    @Override
    public void onNoFaceDetected() {
        Log.d(TAG, "No face in captured photo, will use deterministic crop");

        mainHandler.post(() -> {
            if (currentState == ScanState.SCANNING_FRONT) {
                transitionToState(ScanState.FLIP_INSTRUCTION);
            }
        });
    }

    @Override
    public void onFaceDetectionError(String error) {
        Log.w(TAG, "Face detection error: " + error);

        mainHandler.post(() -> {
            if (currentState == ScanState.SCANNING_FRONT) {
                transitionToState(ScanState.FLIP_INSTRUCTION);
            }
        });
    }

    // ==================== Helper Methods ====================

    /**
     * Starts a timeout timer.
     */
    private void startTimeout(Runnable onTimeout) {
        cancelTimeout();
        timeoutRunnable = onTimeout;
        mainHandler.postDelayed(timeoutRunnable, scanTimeoutMs);
    }

    /**
     * Cancels any pending timeout and face fallback timer.
     */
    private void cancelTimeout() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        if (faceFallbackRunnable != null) {
            mainHandler.removeCallbacks(faceFallbackRunnable);
            faceFallbackRunnable = null;
        }
    }

    /**
     * Toggles the camera flash/torch.
     */
    private void toggleFlash() {
        isFlashOn = !isFlashOn;
        cameraManager.setFlash(isFlashOn);
        flashButton.setText(isFlashOn ? "Flash Off" : "Flash On");
    }

    /**
     * Provides haptic feedback.
     */
    private void vibrate() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(100);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Vibration failed", e);
        }
    }

    /**
     * Cancels the scan and returns to the calling activity.
     */
    private void cancelScan() {
        Log.d(TAG, "Scan cancelled by user");
        Intent result = new Intent();
        result.putExtra(DriversLicenseScannerPlugin.EXTRA_ERROR_CODE, "SCAN_CANCELLED");
        result.putExtra(DriversLicenseScannerPlugin.EXTRA_ERROR_MESSAGE, "Scan cancelled by user");
        setResult(RESULT_CANCELED, result);
        finish();
    }

    /**
     * Finishes with a successful scan result.
     */
    private void finishWithResult(JSONObject result) {
        Log.d(TAG, "Finishing with success");
        Intent data = new Intent();
        data.putExtra(DriversLicenseScannerPlugin.EXTRA_RESULT, result.toString());
        setResult(RESULT_OK, data);
        finish();
    }

    /**
     * Finishes with a success result (from processing state).
     */
    private void finishWithSuccess() {
        try {
            JSONObject result = buildScanResult();
            finishWithResult(result);
        } catch (JSONException e) {
            Log.e(TAG, "Error building final result", e);
            finishWithError("PARSE_ERROR", "Failed to build scan result");
        }
    }

    /**
     * Finishes with an error.
     */
    private void finishWithError(String errorCode, String errorMessage) {
        Log.e(TAG, "Finishing with error: " + errorCode + " - " + errorMessage);
        Intent result = new Intent();
        result.putExtra(DriversLicenseScannerPlugin.EXTRA_ERROR_CODE, errorCode);
        result.putExtra(DriversLicenseScannerPlugin.EXTRA_ERROR_MESSAGE, errorMessage);
        setResult(RESULT_CANCELED, result);
        finish();
    }

    // ==================== Lifecycle Methods ====================

    @Override
    protected void onResume() {
        super.onResume();
        if (currentState == ScanState.SCANNING_FRONT) {
            cameraManager.startCamera(false, true);
        } else if (currentState == ScanState.SCANNING_BACK) {
            cameraManager.startCamera(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        cameraManager.stopCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelTimeout();

        if (cameraManager != null) {
            cameraManager.shutdown();
        }

        if (faceDetector != null) {
            faceDetector.shutdown();
        }

        if (executorService != null) {
            executorService.shutdown();
        }

        // Recycle bitmaps
        if (frontImage != null && !frontImage.isRecycled()) {
            frontImage.recycle();
        }
        if (backImage != null && !backImage.isRecycled()) {
            backImage.recycle();
        }
        if (portraitImage != null && !portraitImage.isRecycled()) {
            portraitImage.recycle();
        }
    }

    @Override
    public void onBackPressed() {
        cancelScan();
    }

    /**
     * Called by CameraManager when the user taps to capture front image.
     */
    public void onCaptureButtonClicked() {
        if (currentState == ScanState.SCANNING_FRONT) {
            cameraManager.captureImage();
        }
    }
}
