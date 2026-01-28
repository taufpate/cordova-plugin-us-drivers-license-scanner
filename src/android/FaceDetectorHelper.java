/**
 * FaceDetectorHelper.java
 *
 * Uses ML Kit Face Detection (on-device) to detect and extract portrait images
 * from driver license photos. Falls back to deterministic cropping if no face is detected.
 */
package com.sos.driverslicensescanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;

/**
 * Helper class for detecting faces and extracting portrait images from license photos.
 */
public class FaceDetectorHelper {

    private static final String TAG = "FaceDetectorHelper";

    // Padding factor to add around detected face (as percentage of face size)
    private static final float FACE_PADDING_FACTOR = 0.3f;

    // Minimum face size relative to image (faces smaller than this are ignored)
    private static final float MIN_FACE_SIZE_RATIO = 0.05f;

    // Maximum face size relative to image (faces larger than this are likely false positives)
    private static final float MAX_FACE_SIZE_RATIO = 0.7f;

    /**
     * Callback interface for face detection results.
     */
    public interface FaceDetectionCallback {
        void onFaceDetected(Bitmap croppedFace);
        void onNoFaceDetected();
        void onFaceDetectionError(String error);
    }

    private final Context context;
    private final FaceDetectionCallback callback;
    private FaceDetector faceDetector;

    /**
     * Creates a new FaceDetectorHelper.
     *
     * @param context The application context
     * @param callback Callback for face detection results
     */
    public FaceDetectorHelper(Context context, FaceDetectionCallback callback) {
        this.context = context;
        this.callback = callback;
        initializeDetector();
    }

    /**
     * Initializes the ML Kit face detector with optimal settings for ID photos.
     */
    private void initializeDetector() {
        // Configure face detector for ID photo portraits
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.1f) // Minimum face size as proportion of image width
                .enableTracking() // Enable face tracking for better detection
                .build();

        faceDetector = FaceDetection.getClient(options);
        Log.d(TAG, "Face detector initialized");
    }

    /**
     * Detects a face in the given bitmap and extracts the portrait.
     *
     * @param bitmap The image to analyze (typically the front of a driver license)
     */
    public void detectFace(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            Log.w(TAG, "Invalid bitmap provided");
            callback.onNoFaceDetected();
            return;
        }

        // Create input image for ML Kit
        InputImage inputImage = InputImage.fromBitmap(bitmap, 0);

        // Process the image
        faceDetector.process(inputImage)
                .addOnSuccessListener(faces -> handleDetectionResult(bitmap, faces))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Face detection failed", e);
                    callback.onFaceDetectionError(e.getMessage());
                });
    }

    /**
     * Handles the face detection result.
     */
    private void handleDetectionResult(Bitmap originalBitmap, List<Face> faces) {
        if (faces == null || faces.isEmpty()) {
            Log.d(TAG, "No faces detected");
            callback.onNoFaceDetected();
            return;
        }

        Log.d(TAG, "Detected " + faces.size() + " face(s)");

        // Find the best face (largest that's within expected size range for ID photos)
        Face bestFace = findBestFace(originalBitmap, faces);

        if (bestFace == null) {
            Log.d(TAG, "No suitable face found within expected parameters");
            callback.onNoFaceDetected();
            return;
        }

        // Extract and crop the face region
        try {
            Bitmap croppedFace = extractFaceRegion(originalBitmap, bestFace);
            if (croppedFace != null) {
                Log.d(TAG, "Successfully extracted face region");
                callback.onFaceDetected(croppedFace);
            } else {
                callback.onNoFaceDetected();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting face region", e);
            callback.onFaceDetectionError(e.getMessage());
        }
    }

    /**
     * Finds the best face candidate from the detected faces.
     * For driver licenses, we expect one prominent face in a specific location.
     */
    private Face findBestFace(Bitmap bitmap, List<Face> faces) {
        int imageWidth = bitmap.getWidth();
        int imageHeight = bitmap.getHeight();
        float imageArea = imageWidth * imageHeight;

        Face bestFace = null;
        float bestScore = 0;

        for (Face face : faces) {
            Rect bounds = face.getBoundingBox();
            float faceArea = bounds.width() * bounds.height();
            float areaRatio = faceArea / imageArea;

            // Skip faces that are too small or too large
            if (areaRatio < MIN_FACE_SIZE_RATIO || areaRatio > MAX_FACE_SIZE_RATIO) {
                Log.d(TAG, "Face rejected due to size: ratio=" + areaRatio);
                continue;
            }

            // Calculate a score based on:
            // 1. Size (larger is better for ID photos)
            // 2. Position (left side of image is typical for US licenses)
            float sizeScore = faceArea / imageArea;

            // US driver licenses typically have the photo on the left side
            // Score based on how close to expected position
            float expectedCenterX = imageWidth * 0.25f; // Photo usually in left quarter
            float expectedCenterY = imageHeight * 0.4f; // Upper portion
            float actualCenterX = bounds.centerX();
            float actualCenterY = bounds.centerY();

            float positionScore = 1.0f - (
                    Math.abs(actualCenterX - expectedCenterX) / imageWidth +
                            Math.abs(actualCenterY - expectedCenterY) / imageHeight
            ) / 2;

            // Combined score
            float totalScore = sizeScore * 0.6f + positionScore * 0.4f;

            if (totalScore > bestScore) {
                bestScore = totalScore;
                bestFace = face;
            }
        }

        return bestFace;
    }

    /**
     * Extracts the face region from the image with appropriate padding.
     */
    private Bitmap extractFaceRegion(Bitmap bitmap, Face face) {
        Rect bounds = face.getBoundingBox();

        // Add padding around the face
        int paddingX = (int) (bounds.width() * FACE_PADDING_FACTOR);
        int paddingY = (int) (bounds.height() * FACE_PADDING_FACTOR);

        // Calculate crop region with padding
        int left = Math.max(0, bounds.left - paddingX);
        int top = Math.max(0, bounds.top - paddingY);
        int right = Math.min(bitmap.getWidth(), bounds.right + paddingX);
        int bottom = Math.min(bitmap.getHeight(), bounds.bottom + paddingY);

        // Ensure we have a valid region
        int width = right - left;
        int height = bottom - top;

        if (width <= 0 || height <= 0) {
            Log.w(TAG, "Invalid crop region calculated");
            return null;
        }

        // Crop the bitmap
        try {
            return Bitmap.createBitmap(bitmap, left, top, width, height);
        } catch (Exception e) {
            Log.e(TAG, "Error cropping bitmap", e);
            return null;
        }
    }

    /**
     * Performs deterministic cropping based on standard driver license layout.
     * Used as fallback when face detection fails.
     *
     * @param bitmap The front of the driver license
     * @return Cropped portrait region
     */
    public static Bitmap deterministicCrop(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // US driver license typical portrait location:
        // - Left side of the card
        // - Upper portion
        // - Approximately 25-30% of card width
        // - Approximately 40-50% of card height

        // Define the expected portrait region
        int portraitLeft = (int) (width * 0.03); // 3% from left edge
        int portraitTop = (int) (height * 0.15); // 15% from top
        int portraitWidth = (int) (width * 0.28); // 28% of card width
        int portraitHeight = (int) (height * 0.50); // 50% of card height

        // Ensure bounds are valid
        portraitLeft = Math.max(0, portraitLeft);
        portraitTop = Math.max(0, portraitTop);
        portraitWidth = Math.min(portraitWidth, width - portraitLeft);
        portraitHeight = Math.min(portraitHeight, height - portraitTop);

        if (portraitWidth <= 0 || portraitHeight <= 0) {
            Log.w(TAG, "Invalid deterministic crop region");
            return null;
        }

        try {
            return Bitmap.createBitmap(bitmap, portraitLeft, portraitTop, portraitWidth, portraitHeight);
        } catch (Exception e) {
            Log.e(TAG, "Error performing deterministic crop", e);
            return null;
        }
    }

    /**
     * Releases resources used by the face detector.
     */
    public void shutdown() {
        if (faceDetector != null) {
            faceDetector.close();
            faceDetector = null;
            Log.d(TAG, "Face detector shut down");
        }
    }
}
