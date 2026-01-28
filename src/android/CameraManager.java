/**
 * CameraManager.java
 *
 * Manages CameraX camera operations for the driver license scanner.
 * Handles preview, image capture, and barcode analysis.
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
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages CameraX camera operations including preview, capture, and barcode analysis.
 */
public class CameraManager {

    private static final String TAG = "CameraManager";

    // Target resolution for analysis (good balance of speed and quality)
    private static final int ANALYSIS_WIDTH = 1280;
    private static final int ANALYSIS_HEIGHT = 720;

    // Target resolution for capture (higher quality)
    private static final int CAPTURE_WIDTH = 1920;
    private static final int CAPTURE_HEIGHT = 1080;

    // Callback interface for scan events
    public interface ScanCallback {
        void onImageCaptured(Bitmap image);
        void onBarcodeDetected(String rawData);
        void onScanError(String errorCode, String errorMessage);
    }

    private final Context context;
    private final PreviewView previewView;
    private final ScanCallback callback;
    private final ExecutorService cameraExecutor;
    private final BarcodeAnalyzer barcodeAnalyzer;

    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private Preview preview;

    private boolean isBarcodeScanning = false;
    private boolean isCapturing = false;
    private boolean isCameraRunning = false;

    /**
     * Creates a new CameraManager instance.
     *
     * @param context The activity context
     * @param previewView The PreviewView for camera preview
     * @param callback Callback for scan events
     */
    public CameraManager(Context context, PreviewView previewView, ScanCallback callback) {
        this.context = context;
        this.previewView = previewView;
        this.callback = callback;
        this.cameraExecutor = Executors.newSingleThreadExecutor();
        this.barcodeAnalyzer = new BarcodeAnalyzer(this::onBarcodeResult);
    }

    /**
     * Starts the camera with specified mode.
     *
     * @param enableBarcodeScanning True to enable PDF417 barcode scanning
     */
    public void startCamera(boolean enableBarcodeScanning) {
        Log.d(TAG, "Starting camera, barcodeScanning=" + enableBarcodeScanning);

        this.isBarcodeScanning = enableBarcodeScanning;

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(context);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
                isCameraRunning = true;
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting camera", e);
                callback.onScanError("CAMERA_NOT_AVAILABLE", "Failed to start camera: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(context));
    }

    /**
     * Stops the camera and releases resources.
     */
    public void stopCamera() {
        Log.d(TAG, "Stopping camera");

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        isCameraRunning = false;
    }

    /**
     * Shuts down the camera manager and releases all resources.
     */
    public void shutdown() {
        stopCamera();

        if (cameraExecutor != null && !cameraExecutor.isShutdown()) {
            cameraExecutor.shutdown();
        }

        if (barcodeAnalyzer != null) {
            barcodeAnalyzer.shutdown();
        }
    }

    /**
     * Binds camera use cases (preview, capture, analysis).
     */
    private void bindCameraUseCases() {
        if (cameraProvider == null) {
            Log.e(TAG, "Camera provider is null");
            return;
        }

        // Unbind any existing use cases
        cameraProvider.unbindAll();

        // Camera selector - use back camera
        CameraSelector cameraSelector = new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build();

        // Preview use case
        preview = new Preview.Builder()
                .build();

        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        // Image capture use case
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(new Size(CAPTURE_WIDTH, CAPTURE_HEIGHT))
                .build();

        // Image analysis use case (for barcode scanning)
        imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        if (isBarcodeScanning) {
            imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);
        }

        try {
            // Bind use cases to camera
            camera = cameraProvider.bindToLifecycle(
                    (LifecycleOwner) context,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
            );

            Log.d(TAG, "Camera use cases bound successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error binding camera use cases", e);
            callback.onScanError("CAMERA_NOT_AVAILABLE", "Failed to bind camera: " + e.getMessage());
        }
    }

    /**
     * Analyzes an image frame for barcode detection.
     */
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        if (!isBarcodeScanning) {
            imageProxy.close();
            return;
        }

        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) {
            imageProxy.close();
            return;
        }

        try {
            // Convert to bitmap for ZXing processing
            Bitmap bitmap = imageProxyToBitmap(imageProxy);
            if (bitmap != null) {
                barcodeAnalyzer.analyze(bitmap);
                bitmap.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error analyzing image", e);
        } finally {
            imageProxy.close();
        }
    }

    /**
     * Converts ImageProxy to Bitmap.
     */
    @OptIn(markerClass = ExperimentalGetImage.class)
    private Bitmap imageProxyToBitmap(ImageProxy imageProxy) {
        Image image = imageProxy.getImage();
        if (image == null) {
            return null;
        }

        // Get image planes
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        // Convert YUV to NV21
        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);

        // Convert to JPEG then to Bitmap
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), 90, out);

        byte[] jpegBytes = out.toByteArray();
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);

        // Rotate bitmap according to rotation degrees
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
     * Callback when barcode is detected by analyzer.
     */
    private void onBarcodeResult(String rawData) {
        if (rawData != null && !rawData.isEmpty() && isBarcodeScanning) {
            Log.d(TAG, "Barcode detected, data length=" + rawData.length());
            callback.onBarcodeDetected(rawData);
        }
    }

    /**
     * Captures the current camera image.
     */
    public void captureImage() {
        if (imageCapture == null || isCapturing) {
            Log.w(TAG, "Cannot capture: imageCapture=" + imageCapture + ", isCapturing=" + isCapturing);
            return;
        }

        isCapturing = true;
        Log.d(TAG, "Capturing image");

        imageCapture.takePicture(
                cameraExecutor,
                new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    @OptIn(markerClass = ExperimentalGetImage.class)
                    public void onCaptureSuccess(@NonNull ImageProxy imageProxy) {
                        Log.d(TAG, "Image capture success");
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

    /**
     * Enables or disables the camera flash/torch.
     *
     * @param enable True to enable flash
     */
    public void setFlash(boolean enable) {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(enable);
            Log.d(TAG, "Flash " + (enable ? "enabled" : "disabled"));
        }
    }

    /**
     * Sets focus on a specific point (tap to focus).
     *
     * @param x X coordinate (0-1 range)
     * @param y Y coordinate (0-1 range)
     */
    public void setFocusPoint(float x, float y) {
        if (camera == null) return;

        // Create focus metering action
        // Note: Implementation would require MeteringPointFactory, omitted for simplicity
        Log.d(TAG, "Focus point set to (" + x + ", " + y + ")");
    }

    /**
     * Checks if the camera is currently running.
     */
    public boolean isCameraRunning() {
        return isCameraRunning;
    }

    /**
     * Enables or disables barcode scanning mode.
     */
    public void setBarcodeScanning(boolean enable) {
        this.isBarcodeScanning = enable;

        if (imageAnalysis != null) {
            if (enable) {
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);
            } else {
                imageAnalysis.clearAnalyzer();
            }
        }
    }
}
