/**
 * ScannerActivity.java
 *
 * Activity that handles the guided driver license scanning flow.
 * Implements a two-step scan process: front → flip → back.
 *
 * Front scan: Uses timed auto-capture after 3 seconds.
 *   - Camera shows preview while user positions the license
 *   - Auto-captures after FACE_FALLBACK_TIMEOUT_MS
 *   - Face detection runs on the captured full-res photo for portrait extraction
 *
 * Back scan: Reads PDF417 barcode from video frames using strategy rotation.
 *   - Auto-torch activates after ~3s if barcode not detected
 *   - Resets BarcodeAnalyzer strategy index for fresh scan
 */
package com.sos.driverslicensescanner;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
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

    // If no face is found within this time, capture anyway.
    // ML Kit is less sensitive than iOS Vision for small printed faces on licenses,
    // so we use a shorter timeout (3s) to avoid making the user wait.
    private static final long FACE_FALLBACK_TIMEOUT_MS = 3000;

    // Interval for preview-based barcode scanning (ms)
    private static final long BARCODE_SCAN_INTERVAL_MS = 500;

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
    private Runnable barcodeScanRunnable;
    private int previewScanCount = 0;

    // Layout ID stored for re-inflation on orientation change
    private int layoutId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on during scanning
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Set content view using resource ID lookup.
        // Android automatically selects res/layout-land/ in landscape, res/layout/ in portrait.
        layoutId = getResources().getIdentifier("activity_scanner", "layout", getPackageName());
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
        Log.w(TAG, "Transitioning from " + currentState + " to " + newState);
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
                instructionText.setText("Scan the BACK of your driver license");
                statusText.setText("Tip: rotate phone sideways for faster scan");
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
     * Uses a timed auto-capture approach: the camera starts, and after
     * FACE_FALLBACK_TIMEOUT_MS the front image is automatically grabbed from the preview.
     * Face detection runs on the captured photo for portrait extraction.
     */
    private void startFrontScan() {
        Log.w(TAG, "Starting front scan (auto-capture in " + FACE_FALLBACK_TIMEOUT_MS + "ms)");

        frontCaptureTriggered = false;
        faceCheckCounter = 0;

        // Front scan is portrait-only: the user needs to see the card face and portrait photo.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        // Start camera with face detection enabled on live video frames.
        // Every 5th ImageAnalysis frame is fed to ML Kit face detection (checkForFacePresence).
        // When a face is found, auto-capture fires immediately (matches iOS behavior).
        // The 3-second faceFallbackRunnable below captures anyway if no face is detected.
        cameraManager.startCamera(false, true);

        // Overall timeout: fail if front scan takes too long
        startTimeout(() -> {
            if (currentState == ScanState.SCANNING_FRONT) {
                finishWithError("SCAN_TIMEOUT", "Front scan timed out. Please try again.");
            }
        });

        // Auto-capture after a short delay to let the user position the license.
        // Grabs directly from PreviewView bitmap to avoid CameraX capture pipeline issues.
        faceFallbackRunnable = () -> {
            if (currentState == ScanState.SCANNING_FRONT && !frontCaptureTriggered) {
                Log.w(TAG, "Auto-capture: grabbing front photo from preview");
                frontCaptureTriggered = true;
                statusText.setText("Capturing...");

                // Try grabbing from preview first (most reliable)
                Bitmap previewBitmap = previewView.getBitmap();
                if (previewBitmap != null) {
                    Log.w(TAG, "Got preview bitmap: " + previewBitmap.getWidth() + "x" + previewBitmap.getHeight());
                    onImageCaptured(previewBitmap);
                } else {
                    // Fallback to CameraX capture if preview bitmap not available
                    Log.w(TAG, "Preview bitmap null, falling back to CameraX capture");
                    cameraManager.captureImage();
                }
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
     * Uses both ImageAnalysis pipeline AND periodic preview bitmap scanning
     * for maximum compatibility across devices.
     */
    private void startBackScan() {
        Log.w(TAG, "Starting back scan (barcode mode)");

        // Reset barcode analyzer strategy rotation for fresh scan
        cameraManager.resetBarcodeAnalyzer();

        // Start camera WITH ImageAnalysis pipeline enabled.
        // This gives ML Kit two image sources: camera-native YUV frames (via ImageAnalysis)
        // AND preview bitmaps (via scanPreviewForBarcode). Both run in parallel.
        cameraManager.startCamera(true);

        // Gentle zoom (0.25f) is applied immediately inside startCamera() so the barcode
        // fills more of the frame. No postDelayed — the user never sees the un-zoomed state.

        // Allow landscape rotation for back scan — landscape gives ~4.8 px/module (vs ~2.7 in portrait)
        // because the camera sensor's native landscape orientation aligns with the card's horizontal layout.
        // Android will automatically use res/layout-land/activity_scanner.xml when the user rotates.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

        // Set timeout for back scan
        startTimeout(() -> {
            if (currentState == ScanState.SCANNING_BACK) {
                finishWithError("BARCODE_NOT_FOUND", "Could not detect barcode. Please try again.");
            }
        });

        // Also start preview-based barcode scanning after 1s camera warmup.
        // This is a belt-and-suspenders approach: some devices don't deliver
        // usable ImageAnalysis frames, but PreviewView.getBitmap() always works.
        previewScanCount = 0;
        mainHandler.postDelayed(this::startPreviewBarcodeScanning, 1000);
    }

    /**
     * Starts repeating preview-based barcode scanning.
     * Grabs PreviewView bitmap every BARCODE_SCAN_INTERVAL_MS and feeds to ML Kit.
     * Also triggers autofocus periodically and saves debug data to files.
     */
    private void startPreviewBarcodeScanning() {
        // Clear previous debug files
        clearDebugFiles();

        // Run self-test: generate a synthetic PDF417 and verify ML Kit can decode it
        cameraManager.runSelfTest();

        barcodeScanRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentState != ScanState.SCANNING_BACK) return;

                try {

                int scanNum = cameraManager.scanPreviewForBarcode();
                previewScanCount++;

                // Write debug log every scan
                writeDebugLog();

                // Save preview bitmap at scans 5, 15, 25 (different focus states)
                if (previewScanCount == 5 || previewScanCount == 15 || previewScanCount == 25) {
                    saveDebugBitmap(previewScanCount);
                }

                // No manual AF calls — CameraX continuous AF handles focusing automatically.
                // Manual AF calls caused focus hunting (sharpness drop from 26% to 0% between frames).

                // Update status text periodically so the user knows scanning is active
                if (previewScanCount % 10 == 0) {
                    statusText.setText("Scanning barcode... keep steady");
                }

                } catch (Throwable t) {
                    Log.e(TAG, "Scan runnable error: " + t.getMessage(), t);
                }
                // Schedule next scan (always, even after errors)
                mainHandler.postDelayed(this, BARCODE_SCAN_INTERVAL_MS);
            }
        };
        mainHandler.post(barcodeScanRunnable);
    }

    /** Clears debug files from previous scan session. */
    private void clearDebugFiles() {
        try {
            File dir = getFilesDir();
            if (dir == null) return;
            new File(dir, "scanner_debug.txt").delete();
            new File(dir, "preview_5.jpg").delete();
            new File(dir, "preview_15.jpg").delete();
            new File(dir, "preview_25.jpg").delete();
        } catch (Exception ignored) {}
    }

    /** Saves a preview bitmap as JPEG to both internal and external storage. */
    private void saveDebugBitmap(int scanNum) {
        try {
            Bitmap bmp = previewView.getBitmap();
            if (bmp == null) return;

            // Internal storage
            File dir = getFilesDir();
            if (dir != null) {
                File imgFile = new File(dir, "preview_" + scanNum + ".jpg");
                FileOutputStream fos = new FileOutputStream(imgFile);
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.close();
            }

            // External storage (accessible via adb pull)
            File extDir = getExternalFilesDir(null);
            if (extDir != null) {
                File extFile = new File(extDir, "preview_" + scanNum + ".jpg");
                FileOutputStream fos2 = new FileOutputStream(extFile);
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, fos2);
                fos2.close();
            }

            bmp.recycle();
        } catch (Exception e) {
            Log.w(TAG, "Debug bitmap save failed: " + e.getMessage());
        }
    }

    /** Writes one line of debug data per scan to a text file. */
    private void writeDebugLog() {
        try {
            File dir = getFilesDir();
            if (dir == null) return;
            File logFile = new File(dir, "scanner_debug.txt");
            FileWriter fw = new FileWriter(logFile, true);
            Bitmap bmp = previewView.getBitmap();
            String dims = "null";
            String stats = "";
            if (bmp != null) {
                dims = bmp.getWidth() + "x" + bmp.getHeight();
                // Compute image quality stats every 5th scan
                if (previewScanCount % 5 == 0) {
                    stats = "|" + computeImageStats(bmp);
                }
                bmp.recycle();
            }
            fw.write("scan=" + previewScanCount
                    + "|dims=" + dims
                    + "|zoom=" + cameraManager.getCurrentZoom()
                    + "|mlkit=" + cameraManager.getLastMlKitResultCount()
                    + "|pvSub=" + cameraManager.getPreviewMlKitSubmitted()
                    + "|pvBlk=" + cameraManager.getPreviewMlKitBlocked()
                    + "|zxSub=" + cameraManager.getZxingSubmitted()
                    + "|iaSub=" + cameraManager.getIaSubmitted()
                    + "|iaRes=" + cameraManager.getIaResultCount()
                    + "|iaRot=" + cameraManager.getLastIaRotation()
                    + "|diag=" + cameraManager.getLastDiagnosticCount()
                    + "|diagFmt=" + cameraManager.getLastDiagnosticFormats()
                    + "|err=" + cameraManager.getLastMlKitError()
                    + "|test=" + cameraManager.getSelfTestResult()
                    + stats
                    + "|t=" + System.currentTimeMillis()
                    + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }

    /**
     * Computes image quality stats for the center region of a bitmap.
     * Key metrics:
     * - brightness: average luminance (0=black, 255=white)
     * - range: max-min luminance (low = no contrast)
     * - edgeContrast: avg difference between adjacent pixels (low = blurry/unfocused)
     * - sharpPixels%: percentage of pixel transitions > 20 (high = sharp barcode edges)
     */
    private String computeImageStats(Bitmap bmp) {
        int cx = bmp.getWidth() / 2;
        int cy = bmp.getHeight() / 2;
        int half = 100; // 200x200 center region

        long sumBright = 0;
        long sumEdge = 0;
        int count = 0;
        int sharpCount = 0;
        int minL = 255, maxL = 0;

        int startY = Math.max(0, cy - half);
        int endY = Math.min(bmp.getHeight(), cy + half);
        int startX = Math.max(0, cx - half);
        int endX = Math.min(bmp.getWidth(), cx + half);

        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                int pixel = bmp.getPixel(x, y);
                int lum = (((pixel >> 16) & 0xFF) * 77
                        + ((pixel >> 8) & 0xFF) * 150
                        + (pixel & 0xFF) * 29) >> 8;
                sumBright += lum;
                if (lum < minL) minL = lum;
                if (lum > maxL) maxL = lum;

                if (x + 1 < endX) {
                    int next = bmp.getPixel(x + 1, y);
                    int nLum = (((next >> 16) & 0xFF) * 77
                            + ((next >> 8) & 0xFF) * 150
                            + (next & 0xFF) * 29) >> 8;
                    int diff = Math.abs(lum - nLum);
                    sumEdge += diff;
                    if (diff > 20) sharpCount++;
                }
                count++;
            }
        }

        float avgBright = count > 0 ? (float) sumBright / count : 0;
        float avgEdge = count > 0 ? (float) sumEdge / count : 0;
        float sharpPct = count > 0 ? (float) sharpCount / count * 100 : 0;

        // Scan ALL rows (every 20th) to find the row with MOST horizontal transitions.
        // A PDF417 barcode has 50+ transitions per row; a blank area has very few.
        // This tells us WHERE (at what Y) the barcode is in the image, if anywhere.
        int maxTransitions = 0;
        int maxTransY = -1;
        int threshold = 25;
        int imgW = bmp.getWidth();
        int imgH = bmp.getHeight();

        for (int scanY = 10; scanY < imgH - 10; scanY += 20) {
            int rowTrans = 0;
            int pLum = -1;
            boolean pLight = false;
            for (int x = 0; x < imgW; x++) {
                int pixel = bmp.getPixel(x, scanY);
                int lum = (((pixel >> 16) & 0xFF) * 77
                        + ((pixel >> 8) & 0xFF) * 150
                        + (pixel & 0xFF) * 29) >> 8;
                if (pLum >= 0) {
                    boolean isLight = lum > 128;
                    if (isLight != pLight && Math.abs(lum - pLum) > threshold) {
                        rowTrans++;
                    }
                    pLight = isLight;
                } else {
                    pLight = lum > 128;
                }
                pLum = lum;
            }
            if (rowTrans > maxTransitions) {
                maxTransitions = rowTrans;
                maxTransY = scanY;
            }
        }

        return "bright=" + (int) avgBright
                + " range=" + (maxL - minL)
                + " edge=" + String.format("%.1f", avgEdge)
                + " sharp=" + String.format("%.1f%%", sharpPct)
                + " maxTr=" + maxTransitions + "@y=" + maxTransY;
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
        Log.w(TAG, "onImageCaptured: state=" + currentState + ", image=" + (image != null ? image.getWidth() + "x" + image.getHeight() : "null"));

        if (currentState == ScanState.SCANNING_FRONT) {
            frontImage = image;

            // Attempt face detection for portrait extraction on the captured image.
            // If it fails or finds no face, we still transition to flip instruction.
            if (extractPortrait && image != null) {
                faceDetector.detectFace(image);
            } else {
                // No portrait extraction requested or null image — go straight to flip
                mainHandler.post(() -> transitionToState(ScanState.FLIP_INSTRUCTION));
            }

        } else if (currentState == ScanState.SCANNING_BACK) {
            backImage = image;
        }
    }

    @Override
    public void onBarcodeDetected(String rawData) {
        Log.w(TAG, "Barcode detected! state=" + currentState + ", data length=" + (rawData != null ? rawData.length() : 0));

        if (currentState == ScanState.SCANNING_BACK && rawData != null && !rawData.isEmpty()) {
            backRawData = rawData;

            runOnUiThread(() -> {
                Toast.makeText(this, "Barcode found! " + rawData.length() + " chars", Toast.LENGTH_SHORT).show();

                // Grab back image from preview (more reliable than CameraX capture on some devices)
                Bitmap backPreview = previewView.getBitmap();
                if (backPreview != null) {
                    backImage = backPreview;
                }

                // Provide haptic feedback
                if (enableVibration) {
                    vibrate();
                }

                // Short delay then process
                mainHandler.postDelayed(() -> {
                    if (currentState == ScanState.SCANNING_BACK) {
                        transitionToState(ScanState.PROCESSING);
                    }
                }, 300);
            });
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
        Log.w(TAG, "Face detected and cropped from captured photo");
        portraitImage = croppedFace;

        runOnUiThread(() -> {
            if (currentState == ScanState.SCANNING_FRONT) {
                transitionToState(ScanState.FLIP_INSTRUCTION);
            }
        });
    }

    @Override
    public void onNoFaceDetected() {
        Log.w(TAG, "No face in captured photo, will use deterministic crop");

        runOnUiThread(() -> {
            if (currentState == ScanState.SCANNING_FRONT) {
                transitionToState(ScanState.FLIP_INSTRUCTION);
            }
        });
    }

    @Override
    public void onFaceDetectionError(String error) {
        Log.w(TAG, "Face detection error: " + error);

        runOnUiThread(() -> {
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
     * Cancels any pending timeout, face fallback timer, and preview barcode scan.
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
        if (barcodeScanRunnable != null) {
            mainHandler.removeCallbacks(barcodeScanRunnable);
            barcodeScanRunnable = null;
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
        Log.d(TAG, "Finishing with success (writing result to file)");
        try {
            // Write result JSON to a temp file to avoid Android Binder transaction limit (~1MB).
            // Base64-encoded full images (front + back + portrait) can exceed this limit,
            // causing TransactionTooLargeException and ANR/black screen.
            File resultFile = new File(getFilesDir(), "scan_result.json");
            FileWriter writer = new FileWriter(resultFile);
            writer.write(result.toString());
            writer.close();

            Intent data = new Intent();
            data.putExtra(DriversLicenseScannerPlugin.EXTRA_RESULT, resultFile.getAbsolutePath());
            data.putExtra("resultInFile", true);
            setResult(RESULT_OK, data);
            finish();
        } catch (Exception e) {
            Log.e(TAG, "Error writing result file", e);
            finishWithError("WRITE_ERROR", "Failed to save scan result: " + e.getMessage());
        }
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

    /**
     * Called when the device orientation changes (portrait ↔ landscape).
     * Fires only because android:configChanges includes "orientation|screenSize",
     * which prevents the Activity from restarting on rotation.
     *
     * Strategy:
     * 1. Re-inflate the layout — Android automatically picks layout-land/ in landscape
     *    or layout/ in portrait, giving the correct UI for each orientation.
     * 2. Re-bind all view references (new instances after setContentView).
     * 3. Update the camera's SurfaceProvider to the new PreviewView.
     * 4. Restore the UI state for the current scan phase.
     *
     * The camera itself keeps running uninterrupted across orientation changes.
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        // Re-inflate: Android resolves layout-land/ vs layout/ based on newConfig.
        setContentView(layoutId);

        // Re-bind all view references (they are new objects after setContentView).
        initializeViews();

        // Restore flash button label (toggle state survives but label is reset by re-inflation).
        flashButton.setText(isFlashOn ? "Flash Off" : "Flash On");

        // Reconnect camera preview to the new PreviewView surface.
        if (cameraManager != null) {
            cameraManager.updatePreviewView(previewView);
        }

        // Restore UI for the current scan state.
        updateUIForState(currentState);

        // In landscape during back scan: acknowledge the better orientation.
        if (currentState == ScanState.SCANNING_BACK
                && newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            statusText.setText("Landscape mode: scanning barcode...");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentState == ScanState.SCANNING_FRONT) {
            cameraManager.startCamera(false, true); // re-enable live face detection on resume
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
