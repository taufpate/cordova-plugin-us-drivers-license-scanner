/**
 * FaceDetectorHelper.java
 *
 * Uses ML Kit Face Detection (on-device) to detect and extract portrait images
 * from driver license photos. Falls back to deterministic cropping if no face is detected.
 *
 * Parameters tuned to match iOS Vision framework implementation:
 * - MIN_FACE_SIZE_RATIO: 0.002 (license faces are small in the camera frame)
 * - FACE_PADDING_FACTOR: 0.35 (natural portrait crop around detected face)
 * - Scoring: 70% size / 30% position (larger faces prioritized)
 * - Expected position: 35%/50% (left-center of frame for US licenses)
 */
package com.sos.driverslicensescanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
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

    // Padding factor around detected face (as percentage of face size)
    // Matches iOS kFacePaddingFactor = 0.35
    private static final float FACE_PADDING_FACTOR = 0.35f;

    // Minimum face size relative to image area.
    // A face on a license held at scanning distance is typically 0.3-1% of the full
    // camera frame, so we set a low threshold to avoid rejecting real license faces.
    // Matches iOS kMinFaceSizeRatio = 0.002
    private static final float MIN_FACE_SIZE_RATIO = 0.002f;

    // Maximum face size relative to image (faces larger than this are likely false positives)
    private static final float MAX_FACE_SIZE_RATIO = 0.7f;

    /**
     * Callback interface for face detection results (from captured photo).
     */
    public interface FaceDetectionCallback {
        void onFaceDetected(Bitmap croppedFace);
        void onNoFaceDetected();
        void onFaceDetectionError(String error);
    }

    /**
     * Callback interface for live video face presence detection.
     * Used during front scan to trigger auto-capture when a face is visible.
     * croppedFace is non-null only when faceFound=true and a valid crop could be extracted.
     */
    public interface LiveFaceCallback {
        void onFacePresenceDetected(boolean faceFound, Bitmap croppedFace);
    }

    private final Context context;
    private final FaceDetectionCallback callback;
    private FaceDetector faceDetector;
    private FaceDetector liveFaceDetector;

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
        initializeLiveDetector();
    }

    /**
     * Initializes the ML Kit face detector with optimal settings for ID photo portraits.
     */
    private void initializeDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                // Matches iOS minFaceSize = 0.05 (proportion of image width)
                .setMinFaceSize(0.05f)
                .enableTracking()
                .build();

        faceDetector = FaceDetection.getClient(options);
        Log.d(TAG, "Face detector initialized");
    }

    /**
     * Initializes a face detector for live video frame analysis.
     * Uses PERFORMANCE_MODE_ACCURATE with a very small minFaceSize to
     * detect the small printed face on a driver's license held at arm's length.
     */
    private void initializeLiveDetector() {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                // Very small minFaceSize to detect printed license photos
                .setMinFaceSize(0.01f)
                .build();

        liveFaceDetector = FaceDetection.getClient(options);
        Log.d(TAG, "Live face detector initialized (accurate mode, minFace=0.01)");
    }

    /**
     * Detects a face in the given bitmap and extracts the portrait.
     * Used for captured photos (high-quality, accurate mode).
     *
     * @param bitmap The image to analyze (typically the front of a driver license)
     */
    public void detectFace(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            Log.w(TAG, "Invalid bitmap provided");
            callback.onNoFaceDetected();
            return;
        }

        InputImage inputImage = InputImage.fromBitmap(bitmap, 0);

        faceDetector.process(inputImage)
                .addOnSuccessListener(faces -> handleDetectionResult(bitmap, faces))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Face detection failed", e);
                    callback.onFaceDetectionError(e.getMessage());
                });
    }

    /**
     * Checks for face presence in a live video frame.
     * Uses fast detection mode suitable for real-time video analysis.
     * Does not extract/crop the face — only reports presence.
     *
     * @param bitmap Video frame to analyze
     * @param liveFaceCallback Callback for face presence result
     */
    public void checkForFacePresence(Bitmap bitmap, LiveFaceCallback liveFaceCallback) {
        if (bitmap == null || bitmap.isRecycled()) {
            liveFaceCallback.onFacePresenceDetected(false, null);
            return;
        }

        Log.d(TAG, "checkForFacePresence: bitmap " + bitmap.getWidth() + "x" + bitmap.getHeight());

        InputImage inputImage = InputImage.fromBitmap(bitmap, 0);

        liveFaceDetector.process(inputImage)
                .addOnSuccessListener(faces -> {
                    boolean found = faces != null && !faces.isEmpty();
                    Log.d(TAG, "Live face check result: " + (found ? faces.size() + " face(s) FOUND" : "no faces"));
                    Bitmap cropped = null;
                    if (found) {
                        // Extract the portrait crop right here using the same bitmap and bounding boxes.
                        // This avoids a second detection pass with a different detector later.
                        Face bestFace = findBestFace(bitmap, faces);
                        if (bestFace != null) {
                            cropped = extractFaceRegion(bitmap, bestFace);
                            Log.d(TAG, "Live crop result: " + (cropped != null
                                    ? cropped.getWidth() + "x" + cropped.getHeight() : "null"));
                        }
                    }
                    liveFaceCallback.onFacePresenceDetected(found, cropped);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Live face detection failed: " + e.getMessage(), e);
                    liveFaceCallback.onFacePresenceDetected(false, null);
                });
    }

    /**
     * Handles the face detection result from captured photo.
     */
    private void handleDetectionResult(Bitmap originalBitmap, List<Face> faces) {
        if (faces == null || faces.isEmpty()) {
            Log.d(TAG, "No faces detected");
            callback.onNoFaceDetected();
            return;
        }

        Log.d(TAG, "Detected " + faces.size() + " face(s)");

        Face bestFace = findBestFace(originalBitmap, faces);

        if (bestFace == null) {
            Log.d(TAG, "No suitable face found within expected parameters");
            callback.onNoFaceDetected();
            return;
        }

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
     * Scoring: 70% size + 30% position (matches iOS implementation).
     * US licenses typically have the photo on the left side.
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

            // Size score (70% weight) — larger faces prioritized
            float sizeScore = faceArea / imageArea;

            // Position score (30% weight) — prefer left-center area
            // Matches iOS: expected position at 35%/50% of image
            float expectedCenterX = imageWidth * 0.35f;
            float expectedCenterY = imageHeight * 0.50f;
            float actualCenterX = bounds.centerX();
            float actualCenterY = bounds.centerY();

            float positionScore = 1.0f - (
                    Math.abs(actualCenterX - expectedCenterX) / imageWidth +
                            Math.abs(actualCenterY - expectedCenterY) / imageHeight
            ) / 2;
            positionScore = Math.max(0, positionScore);

            // Combined score: 70/30 split (matches iOS)
            float totalScore = sizeScore * 0.7f + positionScore * 0.3f;

            Log.d(TAG, String.format("Face score: size=%.4f pos=%.4f total=%.4f (center=%.0f,%.0f)",
                    sizeScore, positionScore, totalScore, actualCenterX, actualCenterY));

            if (totalScore > bestScore) {
                bestScore = totalScore;
                bestFace = face;
            }
        }

        return bestFace;
    }

    /**
     * Extracts the face region from the image with 35% padding.
     */
    private Bitmap extractFaceRegion(Bitmap bitmap, Face face) {
        Rect bounds = face.getBoundingBox();

        float faceCX = bounds.exactCenterX();
        float faceCY = bounds.exactCenterY();
        float faceW  = bounds.width();
        float faceH  = bounds.height();

        float angleZ = face.getHeadEulerAngleZ();
        Log.d(TAG, "Face eulerAngleZ=" + angleZ + "° centre=(" + faceCX + "," + faceCY + ")");

        // Desired output square: face size + portrait padding on all sides
        float faceSize   = Math.max(faceW, faceH);
        float outputSize = (float) Math.ceil(faceSize * (1.0f + FACE_PADDING_FACTOR * 2.0f));

        // Pre-rotation crop must be large enough so rotating it by angleZ leaves
        // no empty corners inside the central outputSize × outputSize region.
        // Required side = outputSize × (|cos| + |sin|).
        double rad = Math.toRadians(angleZ);
        float sinA = (float) Math.abs(Math.sin(rad));
        float cosA = (float) Math.abs(Math.cos(rad));
        float preCropSize = (float) Math.ceil(outputSize * (cosA + sinA)) + 4;

        // Crop centred on the face, clamped to bitmap bounds
        int left   = Math.max(0,                 (int)(faceCX - preCropSize / 2));
        int top    = Math.max(0,                 (int)(faceCY - preCropSize / 2));
        int right  = Math.min(bitmap.getWidth(),  (int)(faceCX + preCropSize / 2));
        int bottom = Math.min(bitmap.getHeight(), (int)(faceCY + preCropSize / 2));

        int w = right - left;
        int h = bottom - top;
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "Invalid pre-crop region");
            return null;
        }

        try {
            Bitmap large = Bitmap.createBitmap(bitmap, left, top, w, h);

            // Rotate the large region to align the face upright.
            // Matrix.setRotate positive = clockwise.
            // angleZ > 0 = face tilted counter-clockwise → clockwise correction = +angleZ.
            Bitmap rotated = (Math.abs(angleZ) >= 3.0f)
                    ? rotateBitmap(large, angleZ)
                    : large;

            // Crop a square from the centre — face is centred, no white corners.
            return centerSquareCrop(rotated, (int) outputSize);

        } catch (Exception e) {
            Log.e(TAG, "Error extracting face region", e);
            return null;
        }
    }

    /**
     * Rotates a bitmap by the given angle (degrees) around its centre.
     * The output canvas expands to contain the full rotated content.
     */
    private Bitmap rotateBitmap(Bitmap src, float angleDegrees) {
        double rad = Math.toRadians(angleDegrees);
        float sinA = (float) Math.abs(Math.sin(rad));
        float cosA = (float) Math.abs(Math.cos(rad));
        int newWidth  = Math.round(src.getWidth() * cosA + src.getHeight() * sinA);
        int newHeight = Math.round(src.getWidth() * sinA + src.getHeight() * cosA);

        Matrix matrix = new Matrix();
        matrix.setRotate(angleDegrees, src.getWidth() / 2f, src.getHeight() / 2f);
        matrix.postTranslate((newWidth - src.getWidth()) / 2f, (newHeight - src.getHeight()) / 2f);

        Bitmap result = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(result);
        canvas.drawBitmap(src, matrix, null);
        src.recycle();
        return result;
    }

    /**
     * Crops a square of `size` pixels from the centre of `src`.
     * The centre of `src` is the (corrected) face centre.
     */
    private Bitmap centerSquareCrop(Bitmap src, int size) {
        int side = Math.min(size, Math.min(src.getWidth(), src.getHeight()));
        int x = (src.getWidth()  - side) / 2;
        int y = (src.getHeight() - side) / 2;
        Bitmap result = Bitmap.createBitmap(src, x, y, side, side);
        src.recycle();
        return result;
    }

    /**
     * Performs deterministic cropping based on standard driver license layout.
     * Used as fallback when face detection fails.
     * Percentages match iOS implementation: Y=12%, W=26%, H=55%.
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

        // Matches iOS: 3% left, 12% top, 26% width, 55% height
        int portraitLeft = (int) (width * 0.03);
        int portraitTop = (int) (height * 0.12);
        int portraitWidth = (int) (width * 0.26);
        int portraitHeight = (int) (height * 0.55);

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
     * Releases resources used by the face detectors.
     */
    public void shutdown() {
        if (faceDetector != null) {
            faceDetector.close();
            faceDetector = null;
            Log.d(TAG, "Face detector shut down");
        }
        if (liveFaceDetector != null) {
            liveFaceDetector.close();
            liveFaceDetector = null;
            Log.d(TAG, "Live face detector shut down");
        }
    }
}
