/**
 * CameraManager.java
 *
 * Manages CameraX camera operations for the driver license scanner.
 * Handles preview, image capture, and barcode analysis.
 *
 * Barcode detection uses a dual-decoder approach:
 * - Primary: ML Kit BarcodeScanner (on-device, ALL formats for maximum detection)
 * - Fallback: ZXing PDF417Reader with strategy rotation (for damaged/worn barcodes)
 *
 * Two scanning pipelines run in parallel for maximum compatibility:
 * 1. ImageAnalysis frames (CameraX pipeline, device-dependent)
 * 2. PreviewView.getBitmap() polling (device-independent, always works)
 */
package com.sos.driverslicensescanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.MeteringPointFactory;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages CameraX camera operations including preview, capture, and barcode analysis.
 */
public class CameraManager {

    private static final String TAG = "CameraManager";

    // Target resolution for analysis — higher resolution is critical for PDF417 barcode
    // decoding. PDF417 has ~510 modules across its width; each needs at least 2 pixels.
    // At 1920px width with barcode at ~60% of frame, we get ~2.3 px/module (minimum viable).
    private static final int ANALYSIS_WIDTH = 1920;
    private static final int ANALYSIS_HEIGHT = 1080;

    // Target resolution for capture
    private static final int CAPTURE_WIDTH = 1920;
    private static final int CAPTURE_HEIGHT = 1080;

    // Process every Nth frame for analysis (matches iOS kFrameSkipInterval = 2)
    private static final int FRAME_SKIP_INTERVAL = 2;

    // Auto-torch threshold: ~3 seconds at ~2 preview scans/sec
    private static final int AUTO_TORCH_FRAME_THRESHOLD = 6;

    // Callback interface for scan events
    public interface ScanCallback {
        void onImageCaptured(Bitmap image);
        void onBarcodeDetected(String rawData);
        void onScanError(String errorCode, String errorMessage);
        void onVideoFrameAvailable(Bitmap frame);
    }

    private final Context context;
    private PreviewView previewView; // non-final: updated when layout re-inflates on orientation change
    private final ScanCallback callback;
    private final ExecutorService cameraExecutor;
    private final BarcodeAnalyzer zxingAnalyzer;

    // ML Kit barcode scanner — primary decoder for ImageAnalysis frames, PDF417 only
    private final BarcodeScanner mlKitScanner;
    private final AtomicBoolean mlKitProcessing = new AtomicBoolean(false);
    // Separate ML Kit instance for preview bitmap scanning — avoids contention with ImageAnalysis.
    // ML Kit is thread-safe but the mlKitProcessing guard was starving the preview path
    // (pvSub=7/24 vs iaSub=69 in testing). Separate instances run truly independently.
    private final BarcodeScanner previewMlKitScanner;
    private final AtomicBoolean previewMlKitProcessing = new AtomicBoolean(false);
    // Diagnostic scanner — ALL formats, used every 10th scan to check if any barcode is visible
    private final BarcodeScanner diagnosticScanner;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private Preview preview;

    private boolean isBarcodeScanning = false;
    private boolean isFaceDetectionMode = false;
    private boolean isCapturing = false;
    private boolean isCameraRunning = false;

    // Frame throttling counter
    private int frameCounter = 0;

    // Auto-torch
    private int framesSinceLastDetection = 0;
    private boolean autoTorchActive = false;

    // Track if barcode already found (prevent duplicate reports)
    private volatile boolean barcodeFound = false;

    // Current zoom level for diagnostics
    private volatile float currentZoom = 0f;

    // Diagnostics: last ML Kit result count (0 = ran but found nothing, -1 = not run yet)
    private volatile int lastMlKitResultCount = -1;
    // Diagnostics: ImageAnalysis ML Kit submissions
    private volatile int iaSubmitted = 0;
    private volatile int iaResultCount = -1;
    private volatile int lastIaRotation = -1;
    // Diagnostics: preview scan submission tracking
    private volatile int previewMlKitSubmitted = 0;
    private volatile int previewMlKitBlocked = 0;
    private volatile String lastMlKitError = "";
    // Diagnostics: ZXing submission count
    private volatile int zxingSubmitted = 0;
    // Diagnostics: ALL_FORMATS scanner to check if ANY barcode type is visible
    private volatile int lastDiagnosticCount = -1;
    private volatile String lastDiagnosticFormats = "";
    // Diagnostics: full (uncropped) bitmap check
    private volatile int lastFullDiagCount = -1;
    private volatile String lastFullDiagFormats = "";
    // Diagnostics: high-res capture dimensions and results
    private volatile String lastCaptureDims = "";
    private volatile int lastCaptureMlKitCount = -1;
    private volatile String lastCaptureFormats = "";
    // Self-test: verifies ML Kit can decode a synthetic PDF417
    private volatile String selfTestResult = "pending";

    public CameraManager(Context context, PreviewView previewView, ScanCallback callback) {
        this.context = context;
        this.previewView = previewView;
        this.callback = callback;
        this.cameraExecutor = Executors.newSingleThreadExecutor();
        this.zxingAnalyzer = new BarcodeAnalyzer(this::onBarcodeResult);

        // Initialize ML Kit barcode scanner — PDF417 ONLY.
        // US driver licenses have both a 1D barcode and a PDF417 on the back.
        // We must ignore the 1D barcode and only read the PDF417 (AAMVA data).
        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_PDF417)
                .build();
        this.mlKitScanner = BarcodeScanning.getClient(options);

        // Separate ML Kit instance for preview bitmap path — runs independently of ImageAnalysis
        BarcodeScannerOptions previewOptions = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_PDF417)
                .build();
        this.previewMlKitScanner = BarcodeScanning.getClient(previewOptions);

        // Diagnostic scanner: ALL formats to check if ML Kit sees any barcode at all
        BarcodeScannerOptions diagOptions = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build();
        this.diagnosticScanner = BarcodeScanning.getClient(diagOptions);
        Log.w(TAG, "CameraManager initialized with ML Kit (PDF417 x2) + ZXing + diagnostic scanner");
    }

    public void startCamera(boolean enableBarcodeScanning) {
        startCamera(enableBarcodeScanning, false);
    }

    public void startCamera(boolean enableBarcodeScanning, boolean enableFaceDetection) {
        Log.w(TAG, "Starting camera, barcodeScanning=" + enableBarcodeScanning +
                ", faceDetection=" + enableFaceDetection);

        this.isBarcodeScanning = enableBarcodeScanning;
        this.isFaceDetectionMode = enableFaceDetection;
        this.frameCounter = 0;
        this.framesSinceLastDetection = 0;
        this.barcodeFound = false;
        this.isCapturing = false; // Reset capture flag (may be stuck from previous phase)
        this.lastMlKitResultCount = -1;

        if (autoTorchActive) {
            autoTorchActive = false;
            setFlash(false);
        }

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(context);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
                isCameraRunning = true;
                // Apply a gentle zoom immediately after binding so the barcode fills more
                // of the frame. Done here (not with postDelayed) so there is no visible
                // transition — the user never sees the un-zoomed state.
                if (enableBarcodeScanning && camera != null) {
                    camera.getCameraControl().setLinearZoom(0.25f);
                    currentZoom = 0.25f;
                    Log.w(TAG, "Initial zoom 0.25f applied for barcode scan");
                }
                Log.w(TAG, "Camera started successfully, barcodeScanning=" + isBarcodeScanning);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting camera", e);
                callback.onScanError("CAMERA_NOT_AVAILABLE", "Failed to start camera: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(context));
    }

    public void stopCamera() {
        Log.w(TAG, "Stopping camera");

        if (autoTorchActive) {
            autoTorchActive = false;
            setFlash(false);
        }

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        isCameraRunning = false;
        isBarcodeScanning = false;
        isFaceDetectionMode = false;
    }

    public void shutdown() {
        stopCamera();

        if (cameraExecutor != null && !cameraExecutor.isShutdown()) {
            cameraExecutor.shutdown();
        }

        if (zxingAnalyzer != null) {
            zxingAnalyzer.shutdown();
        }

        if (mlKitScanner != null) {
            mlKitScanner.close();
        }

        if (previewMlKitScanner != null) {
            previewMlKitScanner.close();
        }

        if (diagnosticScanner != null) {
            diagnosticScanner.close();
        }
    }

    /**
     * Binds camera use cases using plain CameraX (no Camera2Interop).
     *
     * Camera2Interop was removed because it triggers MediaTek "seamless switch mode"
     * which drops the preview to 7fps with 1-second frame freezes on MediaTek devices.
     * Plain CameraX runs at the camera's native fps (30fps) with its own continuous AF.
     */
    private void bindCameraUseCases() {
        if (cameraProvider == null) {
            Log.e(TAG, "Camera provider is null");
            return;
        }

        cameraProvider.unbindAll();

        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        // Plain Preview — CameraX uses continuous AF by default on most devices.
        // No Camera2Interop: it causes MediaTek HAL to enter "seamless switch mode"
        // which drops the camera to 7fps with 1-second frame freezes.
        preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(new Size(CAPTURE_WIDTH, CAPTURE_HEIGHT))
                .build();

        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        if (isBarcodeScanning || isFaceDetectionMode) {
            imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);
        }

        try {
            camera = cameraProvider.bindToLifecycle(
                    (LifecycleOwner) context,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
            );

            Log.w(TAG, "Camera use cases bound, analyzer=" + (isBarcodeScanning || isFaceDetectionMode));

        } catch (Exception e) {
            Log.e(TAG, "Error binding camera use cases", e);
            callback.onScanError("CAMERA_NOT_AVAILABLE", "Failed to bind camera: " + e.getMessage());
        }
    }

    /**
     * Analyzes an image frame from ImageAnalysis pipeline.
     * Uses ML Kit as primary, ZXing as fallback.
     */
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (!isBarcodeScanning && !isFaceDetectionMode) {
            imageProxy.close();
            return;
        }

        // Frame throttling
        frameCounter++;
        if (frameCounter % FRAME_SKIP_INTERVAL != 0) {
            imageProxy.close();
            return;
        }

        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        if (isBarcodeScanning && !barcodeFound) {
            if (mlKitProcessing.compareAndSet(false, true)) {
                iaSubmitted++;
                int rotation = imageProxy.getImageInfo().getRotationDegrees();
                lastIaRotation = rotation;
                InputImage inputImage = InputImage.fromMediaImage(mediaImage, rotation);

                // Also run ALL_FORMATS diagnostic on ImageAnalysis frames every 20 frames
                boolean runDiagnostic = (iaSubmitted % 20 == 5);

                mlKitScanner.process(inputImage)
                        .addOnSuccessListener(barcodes -> {
                            mlKitProcessing.set(false);
                            iaResultCount = barcodes != null ? barcodes.size() : 0;
                            handleMLKitBarcodes(barcodes);
                        })
                        .addOnFailureListener(e -> {
                            mlKitProcessing.set(false);
                            Log.w(TAG, "ML Kit ImageAnalysis scan failed: " + e.getMessage());
                        })
                        .addOnCompleteListener(task -> {
                            // Run ALL_FORMATS diagnostic on camera-native frames
                            if (runDiagnostic && !barcodeFound) {
                                Image diagMedia = null;
                                try {
                                    diagMedia = imageProxy.getImage();
                                } catch (Exception ignored) {}
                                if (diagMedia != null) {
                                    int rot = imageProxy.getImageInfo().getRotationDegrees();
                                    InputImage diagInput = InputImage.fromMediaImage(diagMedia, rot);
                                    diagnosticScanner.process(diagInput)
                                            .addOnSuccessListener(diags -> {
                                                int ct = diags != null ? diags.size() : 0;
                                                if (ct > 0) {
                                                    StringBuilder sb = new StringBuilder();
                                                    for (Barcode b : diags) {
                                                        sb.append(barcodeFormatName(b.getFormat())).append(",");
                                                    }
                                                    Log.w(TAG, "IA-DIAG: " + ct + " found: " + sb);
                                                    lastDiagnosticCount = ct;
                                                    lastDiagnosticFormats = "IA:" + sb;
                                                    handleMLKitBarcodes(diags);
                                                }
                                            })
                                            .addOnCompleteListener(t -> imageProxy.close());
                                } else {
                                    imageProxy.close();
                                }
                            } else {
                                imageProxy.close();
                            }
                        });
                return;

            } else {
                // ML Kit busy — try ZXing fallback with crop+upscale.
                // ZXing reached ChecksumException (partial decode) on full frames,
                // so cropping to the barcode region and 2x upscale should push it over.
                try {
                    Bitmap bitmap = imageProxyToBitmap(imageProxy);
                    if (bitmap != null) {
                        Bitmap enhanced = cropAndUpscaleForBarcode(bitmap);
                        zxingAnalyzer.analyze(enhanced);
                        if (enhanced != bitmap) enhanced.recycle();
                        bitmap.recycle();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "ZXing fallback error", e);
                }
            }
        } else if (isFaceDetectionMode) {
            try {
                Bitmap bitmap = imageProxyToBitmap(imageProxy);
                if (bitmap != null) {
                    callback.onVideoFrameAvailable(bitmap);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error converting frame for face detection", e);
            }
        }

        imageProxy.close();
    }

    /**
     * Handles ML Kit barcode results.
     * Accepts ANY barcode — no AAMVA validation filtering.
     * Also tries getRawBytes() as fallback when getRawValue() is null.
     */
    private void handleMLKitBarcodes(List<Barcode> barcodes) {
        if (barcodes == null || barcodes.isEmpty() || barcodeFound) {
            return;
        }

        for (Barcode barcode : barcodes) {
            int format = barcode.getFormat();
            String rawValue = barcode.getRawValue();

            // Try rawValue first
            if (rawValue != null && !rawValue.isEmpty()) {
                Log.w(TAG, "ML Kit barcode! format=" + format
                        + " len=" + rawValue.length()
                        + " preview=" + rawValue.substring(0, Math.min(50, rawValue.length())));
                reportBarcode(rawValue);
                return;
            }

            // Fallback: try raw bytes (some PDF417 use binary encoding)
            byte[] rawBytes = barcode.getRawBytes();
            if (rawBytes != null && rawBytes.length > 0) {
                String fromBytes = new String(rawBytes, StandardCharsets.ISO_8859_1);
                if (!fromBytes.isEmpty()) {
                    Log.w(TAG, "ML Kit barcode (bytes)! format=" + format
                            + " len=" + fromBytes.length());
                    reportBarcode(fromBytes);
                    return;
                }
            }

            // Last resort: displayValue
            String displayValue = barcode.getDisplayValue();
            if (displayValue != null && !displayValue.isEmpty()) {
                Log.w(TAG, "ML Kit barcode (display)! format=" + format
                        + " len=" + displayValue.length());
                reportBarcode(displayValue);
                return;
            }
        }
    }

    /**
     * Reports a detected barcode to the callback. Thread-safe guard prevents duplicates.
     */
    private void reportBarcode(String rawData) {
        if (barcodeFound) return;
        barcodeFound = true;

        Log.w(TAG, "Barcode confirmed! length=" + rawData.length());
        framesSinceLastDetection = 0;
        callback.onBarcodeDetected(rawData);
    }

    /**
     * Callback when ZXing fallback detects a barcode.
     */
    private void onBarcodeResult(String rawData) {
        if (rawData != null && !rawData.isEmpty() && isBarcodeScanning) {
            Log.w(TAG, "ZXing barcode detected! length=" + rawData.length());
            reportBarcode(rawData);
        }
    }

    private void enableAutoTorch() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(true);
            Log.w(TAG, "Auto-torch enabled");
        }
    }

    /**
     * Requests autofocus on the center of the preview.
     * Helps on devices where continuous autofocus doesn't work well for close objects.
     */
    public void requestAutoFocus() {
        if (camera == null || previewView == null) return;

        try {
            MeteringPointFactory factory = previewView.getMeteringPointFactory();
            MeteringPoint centerPoint = factory.createPoint(
                    previewView.getWidth() / 2f,
                    previewView.getHeight() / 2f
            );
            FocusMeteringAction action = new FocusMeteringAction.Builder(centerPoint)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build();
            camera.getCameraControl().startFocusAndMetering(action);
            Log.w(TAG, "Autofocus triggered on center");
        } catch (Exception e) {
            Log.w(TAG, "Autofocus request failed: " + e.getMessage());
        }
    }

    /**
     * Sets the camera zoom level using linear zoom (0.0 = no zoom, 1.0 = max zoom).
     * For barcode scanning, a slight zoom (0.3-0.5) helps the barcode fill more of the frame,
     * giving more pixels per barcode module and improving decode success.
     */
    public void setZoom(float linearZoom) {
        if (camera != null) {
            try {
                camera.getCameraControl().setLinearZoom(linearZoom);
                currentZoom = linearZoom;
                Log.w(TAG, "Zoom set to " + linearZoom);
            } catch (Exception e) {
                Log.w(TAG, "Failed to set zoom: " + e.getMessage());
            }
        }
    }

    public float getCurrentZoom() { return currentZoom; }

    /**
     * Updates the PreviewView reference after a layout re-inflation (e.g., on orientation change).
     * The camera keeps running; only the surface provider is switched to the new view.
     * Called from ScannerActivity.onConfigurationChanged() after setContentView().
     */
    public void updatePreviewView(PreviewView newPreviewView) {
        this.previewView = newPreviewView;
        if (preview != null) {
            preview.setSurfaceProvider(newPreviewView.getSurfaceProvider());
            Log.w(TAG, "PreviewView updated after orientation change");
        }
    }

    /**
     * Returns the last ML Kit result count for diagnostics.
     * -1 = not yet run, 0 = ran but found nothing, >0 = found barcodes.
     */
    public int getLastMlKitResultCount() {
        return lastMlKitResultCount;
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) return null;

        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 90, out);

        byte[] jpegBytes = out.toByteArray();
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);

        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            bitmap.recycle();
            return rotated;
        }

        return bitmap;
    }

    /**
     * Scans the current preview bitmap for PDF417 barcodes using ML Kit AND ZXing.
     * Uses PreviewView.getBitmap() which is device-independent and always correctly
     * rotated. This bypasses the ImageAnalysis pipeline entirely.
     *
     * The preview bitmap is cropped to the center region where the barcode should be
     * and upscaled 2x to increase the number of pixels per barcode module.
     * At 720px screen width, a PDF417 with 510 modules at 60% fill gives ~0.85 px/module.
     * After crop+upscale, this doubles to ~1.7 px/module. Combined with camera zoom
     * (which fills more of the frame), we reach the required 2+ px/module threshold.
     *
     * Submitted to:
     * 1. ML Kit PDF417 (async) — primary decoder, on cropped+upscaled image
     * 2. ZXing PDF417Reader (background thread) — fallback with strategy rotation
     * 3. ML Kit ALL_FORMATS (every 10th scan) — diagnostic
     *
     * Must be called from the main (UI) thread.
     *
     * @return scan attempt count, or -1 if scan was skipped
     */
    public int scanPreviewForBarcode() {
        if (barcodeFound) return -1;

        Bitmap fullBitmap = previewView.getBitmap();
        if (fullBitmap == null) {
            Log.w(TAG, "scanPreviewForBarcode: preview bitmap null");
            return -1;
        }

        framesSinceLastDetection++;

        // Auto-torch check
        if (!autoTorchActive && framesSinceLastDetection >= AUTO_TORCH_FRAME_THRESHOLD) {
            autoTorchActive = true;
            enableAutoTorch();
        }

        if (framesSinceLastDetection % 10 == 1) {
            Log.w(TAG, "Preview scan #" + framesSinceLastDetection
                    + " full=" + fullBitmap.getWidth() + "x" + fullBitmap.getHeight()
                    + " zoom=" + currentZoom
                    + " mlkitSub=" + previewMlKitSubmitted
                    + " zxingSub=" + zxingSubmitted);
        }

        // Crop center barcode region and upscale 2x for better per-module resolution.
        // The PDF417 barcode on a US license back is typically in the center area of the frame.
        Bitmap scanBitmap = cropAndUpscaleForBarcode(fullBitmap);

        // Make a copy for ZXing (runs on background thread in parallel)
        Bitmap zxingCopy = null;
        try {
            zxingCopy = scanBitmap.copy(scanBitmap.getConfig(), false);
        } catch (Exception e) {
            Log.w(TAG, "Failed to copy bitmap for ZXing: " + e.getMessage());
        }

        // 1. ML Kit PDF417 — uses SEPARATE scanner instance (previewMlKitScanner) to avoid
        //    contention with ImageAnalysis path. Testing showed the shared lock caused the
        //    preview path to be starved (pvSub=7/24 vs iaSub=69).
        if (previewMlKitProcessing.compareAndSet(false, true)) {
            previewMlKitSubmitted++;
            InputImage inputImage = InputImage.fromBitmap(scanBitmap, 0);
            previewMlKitScanner.process(inputImage)
                    .addOnSuccessListener(barcodes -> {
                        previewMlKitProcessing.set(false);
                        lastMlKitResultCount = barcodes != null ? barcodes.size() : 0;
                        handleMLKitBarcodes(barcodes);
                    })
                    .addOnFailureListener(e -> {
                        previewMlKitProcessing.set(false);
                        lastMlKitError = e.getMessage() != null ? e.getMessage() : "unknown";
                        Log.w(TAG, "ML Kit preview error: " + lastMlKitError);
                    })
                    .addOnCompleteListener(task -> {
                        if (!scanBitmap.isRecycled()) scanBitmap.recycle();
                    });
        } else {
            previewMlKitBlocked++;
            scanBitmap.recycle();
        }

        // 2. ZXing on background thread (strategy rotation handles blurry/worn barcodes)
        if (zxingCopy != null && !barcodeFound) {
            final Bitmap zxBmp = zxingCopy;
            zxingSubmitted++;
            cameraExecutor.execute(() -> {
                zxingAnalyzer.analyze(zxBmp);
                if (!zxBmp.isRecycled()) zxBmp.recycle();
            });
        } else if (zxingCopy != null) {
            zxingCopy.recycle();
        }

        // 3. Diagnostic: ALL_FORMATS check every 10th scan (on cropped+upscaled image)
        if (framesSinceLastDetection % 10 == 5 && !barcodeFound) {
            Bitmap diagSource = previewView.getBitmap();
            if (diagSource != null) {
                Bitmap diagBmp = cropAndUpscaleForBarcode(diagSource);
                diagSource.recycle();
                InputImage diagInput = InputImage.fromBitmap(diagBmp, 0);
                diagnosticScanner.process(diagInput)
                        .addOnSuccessListener(barcodes -> {
                            lastDiagnosticCount = barcodes != null ? barcodes.size() : 0;
                            if (barcodes != null && !barcodes.isEmpty()) {
                                StringBuilder sb = new StringBuilder();
                                for (Barcode b : barcodes) {
                                    sb.append(barcodeFormatName(b.getFormat()));
                                    String val = b.getRawValue();
                                    if (val != null) sb.append("(").append(val.length()).append(")");
                                    sb.append(",");
                                }
                                lastDiagnosticFormats = sb.toString();
                                Log.w(TAG, "DIAGNOSTIC: found " + barcodes.size()
                                        + " barcodes: " + lastDiagnosticFormats);
                                handleMLKitBarcodes(barcodes);
                            } else {
                                lastDiagnosticFormats = "none";
                            }
                        })
                        .addOnFailureListener(e -> {
                            lastDiagnosticFormats = "err:" + e.getMessage();
                        })
                        .addOnCompleteListener(task -> diagBmp.recycle());
            }
        }

        fullBitmap.recycle();
        return framesSinceLastDetection;
    }

    /**
     * Crops the barcode region from the bitmap, upscales 3x, and sharpens.
     *
     * Pipeline: crop → 3x upscale (bilinear) → sharpen (3x3) → contrast stretch
     *
     * Crop regions differ by orientation:
     *
     * PORTRAIT (w < h, e.g. 720×1600):
     *   - Card is held landscape inside a portrait frame
     *   - Barcode is in the center-bottom of the card, roughly at 10–75% height
     *   - Crop: 2% horizontal margin, rows 10–75% of frame height
     *
     * LANDSCAPE (w > h, e.g. 1280×720):
     *   - Card held in natural landscape orientation fills the wide dimension
     *   - Camera sensor (1920×1080, native landscape) maps better → more px/module
     *   - Barcode is at the BOTTOM of the card → lower in the frame than in portrait
     *   - Extend crop to 88% of height to ensure barcode isn't cut off at the bottom
     *   - Result: ~4.8 px/module after 3x upscale vs ~2.7 px/module in portrait
     *
     * @param source Full bitmap from PreviewView.getBitmap() (correctly rotated for display)
     * @return Processed bitmap optimized for barcode decoding
     */
    private Bitmap cropAndUpscaleForBarcode(Bitmap source) {
        int w = source.getWidth();
        int h = source.getHeight();

        final int cropLeft, cropTop, cropW, cropH;
        if (w > h) {
            // Landscape: card fills full width, barcode at card bottom → extend crop lower
            cropLeft = (int)(w * 0.02);
            cropTop  = (int)(h * 0.05);   // 5% top margin (card starts near top in landscape)
            cropW    = Math.min((int)(w * 0.96), w - cropLeft);
            cropH    = Math.min((int)(h * 0.88), h - cropTop); // 88% = down to near bottom
        } else {
            // Portrait: original crop region (10–75% vertically)
            cropLeft = (int)(w * 0.02);
            cropTop  = (int)(h * 0.10);
            cropW    = Math.min((int)(w * 0.96), w - cropLeft);
            cropH    = Math.min((int)(h * 0.65), h - cropTop);
        }

        try {
            Bitmap cropped = Bitmap.createBitmap(source, cropLeft, cropTop, cropW, cropH);
            // 3x upscale with bilinear filtering for more pixels per barcode module.
            // NOTE: Denoising was tried here before upscaling but destroyed the barcode:
            // at ~0.95 px/module native resolution, a 3×3 blur averages 3+ modules together,
            // smearing bar/space transitions that the decoder needs. Denoising is only safe
            // AFTER upscaling (2.85 px/module), but the sharpen pass below already handles
            // the minor bilinear softening — adding another denoise pass is not needed.
            Bitmap upscaled = Bitmap.createScaledBitmap(cropped, cropW * 3, cropH * 3, true);
            cropped.recycle();
            // Sharpen to restore edges softened by bilinear upscaling
            Bitmap sharpened = sharpenBitmap(upscaled);
            upscaled.recycle();
            return sharpened;
        } catch (Exception e) {
            Log.w(TAG, "Crop/upscale/sharpen failed: " + e.getMessage());
            try {
                return source.copy(source.getConfig(), false);
            } catch (Exception e2) {
                return source;
            }
        }
    }

    /**
     * Applies a 3x3 box blur (mean filter) for sensor noise reduction.
     * Called at native resolution BEFORE upscaling so it is computationally cheap.
     *
     * Box blur averages each pixel with its 8 neighbors, smoothing:
     * - Salt-and-pepper noise (dead/hot pixels) — common on budget sensors
     * - Sensor banding and fixed-pattern noise — amplified by sharpening if not removed
     * - High-frequency chroma noise — produces color artifacts in grayscale conversion
     *
     * Edge pixels are copied unchanged to avoid border artifacts.
     */
    private Bitmap denoiseBitmap(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] result = new int[w * h];

        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int rSum = 0, gSum = 0, bSum = 0;
                // Sum 3x3 neighborhood
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int p = pixels[(y + dy) * w + (x + dx)];
                        rSum += (p >> 16) & 0xFF;
                        gSum += (p >> 8) & 0xFF;
                        bSum += p & 0xFF;
                    }
                }
                result[y * w + x] = 0xFF000000
                        | ((rSum / 9) << 16)
                        | ((gSum / 9) << 8)
                        | (bSum / 9);
            }
        }
        // Copy border pixels unchanged
        for (int x = 0; x < w; x++) {
            result[x] = pixels[x];
            result[(h - 1) * w + x] = pixels[(h - 1) * w + x];
        }
        for (int y = 0; y < h; y++) {
            result[y * w] = pixels[y * w];
            result[y * w + w - 1] = pixels[y * w + w - 1];
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(result, 0, w, 0, 0, w, h);
        return out;
    }

    /**
     * Applies a 3x3 sharpening convolution kernel to enhance barcode edges.
     * Kernel: [-1,-1,-1; -1,9,-1; -1,-1,-1] (standard sharpen)
     * Also stretches contrast to maximize the difference between bars and spaces.
     */
    private Bitmap sharpenBitmap(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] result = new int[w * h];

        // Pass 1: Sharpen using 3x3 kernel [-1,-1,-1; -1,9,-1; -1,-1,-1]
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int idx = y * w + x;
                int rSum = 0, gSum = 0, bSum = 0;
                // Center pixel * 9
                int cp = pixels[idx];
                rSum = ((cp >> 16) & 0xFF) * 9;
                gSum = ((cp >> 8) & 0xFF) * 9;
                bSum = (cp & 0xFF) * 9;
                // Subtract 8 neighbors
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int np = pixels[(y + dy) * w + (x + dx)];
                        rSum -= (np >> 16) & 0xFF;
                        gSum -= (np >> 8) & 0xFF;
                        bSum -= np & 0xFF;
                    }
                }
                int r = Math.max(0, Math.min(255, rSum));
                int g = Math.max(0, Math.min(255, gSum));
                int b = Math.max(0, Math.min(255, bSum));
                result[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        // Copy border pixels as-is
        for (int x = 0; x < w; x++) {
            result[x] = pixels[x];
            result[(h - 1) * w + x] = pixels[(h - 1) * w + x];
        }
        for (int y = 0; y < h; y++) {
            result[y * w] = pixels[y * w];
            result[y * w + w - 1] = pixels[y * w + w - 1];
        }

        // Pass 2: Contrast stretch on luminance
        int minL = 255, maxL = 0;
        for (int i = 0; i < result.length; i++) {
            int lum = (((result[i] >> 16) & 0xFF) * 77
                    + ((result[i] >> 8) & 0xFF) * 150
                    + (result[i] & 0xFF) * 29) >> 8;
            if (lum < minL) minL = lum;
            if (lum > maxL) maxL = lum;
        }
        if (maxL - minL > 30) {
            float scale = 255f / (maxL - minL);
            for (int i = 0; i < result.length; i++) {
                int r = Math.max(0, Math.min(255, (int)((((result[i] >> 16) & 0xFF) - minL) * scale)));
                int g = Math.max(0, Math.min(255, (int)((((result[i] >> 8) & 0xFF) - minL) * scale)));
                int b = Math.max(0, Math.min(255, (int)(((result[i] & 0xFF) - minL) * scale)));
                result[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(result, 0, w, 0, 0, w, h);
        return out;
    }

    /** Converts ML Kit barcode format constant to a human-readable name. */
    private String barcodeFormatName(int format) {
        switch (format) {
            case Barcode.FORMAT_PDF417: return "PDF417";
            case Barcode.FORMAT_QR_CODE: return "QR";
            case Barcode.FORMAT_CODE_128: return "CODE128";
            case Barcode.FORMAT_CODE_39: return "CODE39";
            case Barcode.FORMAT_CODE_93: return "CODE93";
            case Barcode.FORMAT_EAN_13: return "EAN13";
            case Barcode.FORMAT_EAN_8: return "EAN8";
            case Barcode.FORMAT_UPC_A: return "UPCA";
            case Barcode.FORMAT_UPC_E: return "UPCE";
            case Barcode.FORMAT_ITF: return "ITF";
            case Barcode.FORMAT_CODABAR: return "CODABAR";
            case Barcode.FORMAT_DATA_MATRIX: return "DATAMATRIX";
            case Barcode.FORMAT_AZTEC: return "AZTEC";
            default: return "FMT" + format;
        }
    }

    /**
     * Takes a high-resolution photo using ImageCapture and runs barcode detection on it.
     * ImageCapture produces images at the camera sensor's native resolution (up to 1920x1080
     * or higher), which is significantly higher quality than PreviewView.getBitmap().
     *
     * Call this periodically (e.g., every 3 seconds) during back scan as a complement
     * to the fast preview-based scanning.
     */
    @OptIn(markerClass = ExperimentalGetImage.class)
    public void captureAndScanBarcode() {
        if (barcodeFound) {
            lastCaptureFormats = "skip:barcodeFound";
            return;
        }
        if (isCapturing) {
            lastCaptureFormats = "skip:isCapturing";
            return;
        }
        if (imageCapture == null) {
            lastCaptureFormats = "skip:imgCapNull";
            return;
        }

        isCapturing = true;
        Log.w(TAG, "Taking high-res capture for barcode detection...");

        imageCapture.takePicture(
                cameraExecutor,
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                        try {
                            Bitmap bitmap = imageProxyToBitmap(imageProxy);
                            if (bitmap == null) {
                                Log.w(TAG, "High-res capture: bitmap conversion failed");
                                return;
                            }

                            lastCaptureDims = bitmap.getWidth() + "x" + bitmap.getHeight();
                            Log.w(TAG, "High-res capture: " + lastCaptureDims);

                            // Run ML Kit ALL_FORMATS on high-res image to see what's detectable
                            InputImage inputImage = InputImage.fromBitmap(bitmap, 0);

                            diagnosticScanner.process(inputImage)
                                    .addOnSuccessListener(barcodes -> {
                                        lastCaptureMlKitCount = barcodes != null ? barcodes.size() : 0;
                                        if (barcodes != null && !barcodes.isEmpty()) {
                                            StringBuilder sb = new StringBuilder();
                                            for (Barcode b : barcodes) {
                                                sb.append(barcodeFormatName(b.getFormat()));
                                                String val = b.getRawValue();
                                                if (val != null) sb.append("(").append(val.length()).append(")");
                                                sb.append(",");
                                            }
                                            lastCaptureFormats = sb.toString();
                                            Log.w(TAG, "HIGH-RES FOUND: " + lastCaptureMlKitCount
                                                    + " barcodes: " + lastCaptureFormats);
                                            // Use any PDF417 result found
                                            handleMLKitBarcodes(barcodes);
                                        } else {
                                            lastCaptureFormats = "none";
                                        }
                                    })
                                    .addOnFailureListener(e -> {
                                        lastCaptureFormats = "err:" + e.getMessage();
                                    })
                                    .addOnCompleteListener(task -> {
                                        // After ML Kit, also try ZXing on the high-res image
                                        if (!barcodeFound) {
                                            zxingAnalyzer.analyze(bitmap);
                                        }
                                        if (!bitmap.isRecycled()) bitmap.recycle();
                                    });

                        } catch (Exception e) {
                            Log.e(TAG, "High-res capture processing error", e);
                        } finally {
                            imageProxy.close();
                            isCapturing = false;
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.w(TAG, "High-res capture failed: " + exception.getMessage());
                        isCapturing = false;
                    }
                }
        );
    }

    public int getPreviewMlKitSubmitted() { return previewMlKitSubmitted; }
    public int getPreviewMlKitBlocked() { return previewMlKitBlocked; }
    public String getLastMlKitError() { return lastMlKitError; }
    public int getZxingSubmitted() { return zxingSubmitted; }
    public int getLastDiagnosticCount() { return lastDiagnosticCount; }
    public String getLastDiagnosticFormats() { return lastDiagnosticFormats; }
    public String getLastCaptureDims() { return lastCaptureDims; }
    public int getLastCaptureMlKitCount() { return lastCaptureMlKitCount; }
    public String getLastCaptureFormats() { return lastCaptureFormats; }
    public int getIaSubmitted() { return iaSubmitted; }
    public int getIaResultCount() { return iaResultCount; }
    public int getLastIaRotation() { return lastIaRotation; }
    public String getSelfTestResult() { return selfTestResult; }

    /**
     * Self-test: verifies ML Kit barcode scanner is functional.
     * Phase 1: Process a blank image (should return 0 barcodes, no error).
     * Phase 2: Generate a synthetic PDF417 using ZXing and verify ML Kit can decode it.
     * Result is stored in selfTestResult.
     */
    public void runSelfTest() {
        selfTestResult = "running";
        try {
            // Phase 1: Verify ML Kit processes without error (blank image → 0 barcodes)
            Bitmap whiteBmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
            whiteBmp.eraseColor(0xFFFFFFFF);

            InputImage whiteInput = InputImage.fromBitmap(whiteBmp, 0);
            mlKitScanner.process(whiteInput)
                    .addOnSuccessListener(barcodes -> {
                        int blankCount = barcodes != null ? barcodes.size() : -1;

                        // Phase 2: Generate and decode a synthetic PDF417
                        try {
                            String testData = "ANSI 636000090002DL00410288DLDAQT64235789"
                                    + "DCSJOHNSON DACJOHN DADM DCAC DCBNONE DCDNONE "
                                    + "DBD08242015 DBB01311970 DBA12312025 DBC1";

                            MultiFormatWriter writer = new MultiFormatWriter();
                            BitMatrix bitMatrix = writer.encode(testData, BarcodeFormat.PDF_417, 600, 200);

                            int w = bitMatrix.getWidth();
                            int h = bitMatrix.getHeight();
                            int[] pixels = new int[w * h];
                            for (int y = 0; y < h; y++) {
                                for (int x = 0; x < w; x++) {
                                    pixels[y * w + x] = bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                                }
                            }
                            Bitmap testBmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);

                            InputImage testInput = InputImage.fromBitmap(testBmp, 0);
                            mlKitScanner.process(testInput)
                                    .addOnSuccessListener(testBarcodes -> {
                                        int tc = testBarcodes != null ? testBarcodes.size() : 0;
                                        if (tc > 0) {
                                            selfTestResult = "ALL_OK:blank=" + blankCount
                                                    + ",pdf417=" + tc;
                                        } else {
                                            // ML Kit can't decode synthetic PDF417 — try ALL_FORMATS
                                            InputImage diagInput = InputImage.fromBitmap(testBmp, 0);
                                            diagnosticScanner.process(diagInput)
                                                    .addOnSuccessListener(diags -> {
                                                        int dc = diags != null ? diags.size() : 0;
                                                        selfTestResult = "MLKIT_RUNS_BUT_NO_PDF417:blank=" + blankCount
                                                                + ",allFmt=" + dc;
                                                    })
                                                    .addOnFailureListener(e2 -> {
                                                        selfTestResult = "DIAG_ERR:" + e2.getMessage();
                                                    })
                                                    .addOnCompleteListener(t2 -> testBmp.recycle());
                                        }
                                    })
                                    .addOnFailureListener(e -> {
                                        selfTestResult = "PDF417_ERR:" + e.getMessage();
                                        testBmp.recycle();
                                    });

                        } catch (Throwable t) {
                            selfTestResult = "ENCODE_ERR:blank=" + blankCount + "," + t.getMessage();
                        }
                    })
                    .addOnFailureListener(e -> {
                        selfTestResult = "BLANK_ERR:" + e.getMessage();
                    })
                    .addOnCompleteListener(task -> whiteBmp.recycle());

        } catch (Throwable t) {
            selfTestResult = "INIT_ERR:" + t.getMessage();
            Log.e(TAG, "Self-test error", t);
        }
    }

    public void captureImage() {
        if (imageCapture == null || isCapturing) {
            Log.w(TAG, "Cannot capture: imageCapture=" + imageCapture + ", isCapturing=" + isCapturing);
            return;
        }

        isCapturing = true;
        Log.w(TAG, "Capturing image now");

        imageCapture.takePicture(
                cameraExecutor,
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    @OptIn(markerClass = ExperimentalGetImage.class)
                    public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                        Log.w(TAG, "Image capture success");
                        try {
                            Bitmap bitmap = imageProxyToBitmap(imageProxy);
                            if (bitmap != null) {
                                callback.onImageCaptured(bitmap);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing captured image", e);
                        } finally {
                            imageProxy.close();
                            isCapturing = false;
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Image capture failed", exception);
                        isCapturing = false;
                        callback.onScanError("CAPTURE_ERROR", "Failed to capture image: " + exception.getMessage());
                    }
                }
        );
    }

    public void setFlash(boolean enable) {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(enable);
        }
    }

    public void setFocusPoint(float x, float y) {
        if (camera == null) return;
    }

    public boolean isCameraRunning() {
        return isCameraRunning;
    }

    public void setBarcodeScanning(boolean enable) {
        this.isBarcodeScanning = enable;
        if (imageAnalysis != null) {
            if (enable || isFaceDetectionMode) {
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);
            } else {
                imageAnalysis.clearAnalyzer();
            }
        }
    }

    public void resetBarcodeAnalyzer() {
        if (zxingAnalyzer != null) {
            zxingAnalyzer.resetStrategyIndex();
            zxingAnalyzer.resetDebounce();
        }
        barcodeFound = false;
        lastMlKitResultCount = -1;
        mlKitProcessing.set(false);
        previewMlKitProcessing.set(false);
    }
}
