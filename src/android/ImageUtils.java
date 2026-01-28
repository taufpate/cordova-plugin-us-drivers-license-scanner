/**
 * ImageUtils.java
 *
 * Utility class for image processing operations in the driver license scanner.
 * Handles bitmap manipulation, format conversion, and Base64 encoding.
 */
package com.sos.driverslicensescanner;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * Utility methods for image processing and conversion.
 */
public class ImageUtils {

    private static final String TAG = "ImageUtils";

    // Standard driver license aspect ratio (width:height) - approximately 1.59
    private static final float LICENSE_ASPECT_RATIO = 1.586f;

    // Maximum image dimension for encoding (to prevent memory issues)
    private static final int MAX_DIMENSION = 2048;

    // Default JPEG quality
    private static final int DEFAULT_QUALITY = 85;

    /**
     * Converts a Bitmap to Base64 encoded string.
     *
     * @param bitmap The bitmap to encode
     * @param format Image format ("JPEG" or "PNG")
     * @param quality JPEG quality (0-100), ignored for PNG
     * @return Base64 encoded string
     */
    public static String bitmapToBase64(Bitmap bitmap, String format, int quality) {
        if (bitmap == null || bitmap.isRecycled()) {
            Log.w(TAG, "Cannot encode null or recycled bitmap");
            return "";
        }

        try {
            // Scale down if necessary
            Bitmap scaledBitmap = ensureMaxDimension(bitmap, MAX_DIMENSION);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            Bitmap.CompressFormat compressFormat = "PNG".equalsIgnoreCase(format)
                    ? Bitmap.CompressFormat.PNG
                    : Bitmap.CompressFormat.JPEG;

            scaledBitmap.compress(compressFormat, quality, outputStream);

            byte[] imageBytes = outputStream.toByteArray();
            String base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP);

            // Clean up scaled bitmap if we created a new one
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle();
            }

            Log.d(TAG, "Encoded bitmap to Base64, size=" + base64.length());
            return base64;

        } catch (Exception e) {
            Log.e(TAG, "Error encoding bitmap to Base64", e);
            return "";
        }
    }

    /**
     * Converts a Bitmap to Base64 with default settings.
     */
    public static String bitmapToBase64(Bitmap bitmap) {
        return bitmapToBase64(bitmap, "JPEG", DEFAULT_QUALITY);
    }

    /**
     * Decodes a Base64 string to a Bitmap.
     *
     * @param base64 The Base64 encoded string
     * @return Decoded Bitmap or null if decoding fails
     */
    public static Bitmap base64ToBitmap(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }

        try {
            byte[] decodedBytes = Base64.decode(base64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length);
        } catch (Exception e) {
            Log.e(TAG, "Error decoding Base64 to bitmap", e);
            return null;
        }
    }

    /**
     * Ensures a bitmap doesn't exceed the maximum dimension while maintaining aspect ratio.
     *
     * @param bitmap The original bitmap
     * @param maxDimension Maximum width or height
     * @return Scaled bitmap (may be the same object if no scaling needed)
     */
    public static Bitmap ensureMaxDimension(Bitmap bitmap, int maxDimension) {
        if (bitmap == null) {
            return null;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        if (width <= maxDimension && height <= maxDimension) {
            return bitmap; // No scaling needed
        }

        float scale = Math.min(
                (float) maxDimension / width,
                (float) maxDimension / height
        );

        int newWidth = Math.round(width * scale);
        int newHeight = Math.round(height * scale);

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
    }

    /**
     * Rotates a bitmap by the specified degrees.
     *
     * @param bitmap The bitmap to rotate
     * @param degrees Rotation in degrees (clockwise)
     * @return Rotated bitmap
     */
    public static Bitmap rotateBitmap(Bitmap bitmap, int degrees) {
        if (bitmap == null || degrees == 0) {
            return bitmap;
        }

        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * Extracts the portrait region from a driver license image using deterministic cropping.
     * Used as fallback when face detection fails.
     *
     * US driver license typical layout:
     * - Photo on left side, approximately 25-30% of width
     * - Photo in upper portion, approximately 40-50% of height
     *
     * @param licenseImage The full driver license image
     * @return Cropped portrait region
     */
    public static Bitmap extractPortraitDeterministic(Bitmap licenseImage) {
        if (licenseImage == null || licenseImage.isRecycled()) {
            Log.w(TAG, "Cannot extract portrait from null/recycled bitmap");
            return null;
        }

        int width = licenseImage.getWidth();
        int height = licenseImage.getHeight();

        // Determine orientation based on aspect ratio
        boolean isLandscape = width > height;

        int portraitX, portraitY, portraitWidth, portraitHeight;

        if (isLandscape) {
            // Standard horizontal license orientation
            // Portrait typically in left 30%, upper 60% of card
            portraitX = (int) (width * 0.03);     // 3% from left
            portraitY = (int) (height * 0.12);    // 12% from top
            portraitWidth = (int) (width * 0.26); // 26% of width
            portraitHeight = (int) (height * 0.55); // 55% of height
        } else {
            // Vertical orientation (some states)
            // Portrait typically in upper portion
            portraitX = (int) (width * 0.10);     // 10% from left
            portraitY = (int) (height * 0.05);    // 5% from top
            portraitWidth = (int) (width * 0.35); // 35% of width
            portraitHeight = (int) (height * 0.30); // 30% of height
        }

        // Ensure bounds are valid
        portraitX = Math.max(0, portraitX);
        portraitY = Math.max(0, portraitY);
        portraitWidth = Math.min(portraitWidth, width - portraitX);
        portraitHeight = Math.min(portraitHeight, height - portraitY);

        if (portraitWidth <= 0 || portraitHeight <= 0) {
            Log.w(TAG, "Invalid portrait crop dimensions");
            return null;
        }

        try {
            return Bitmap.createBitmap(licenseImage, portraitX, portraitY, portraitWidth, portraitHeight);
        } catch (Exception e) {
            Log.e(TAG, "Error extracting portrait", e);
            return null;
        }
    }

    /**
     * Crops an image to the driver license aspect ratio.
     * Useful for normalizing captured images.
     *
     * @param bitmap The image to crop
     * @return Cropped image with license aspect ratio
     */
    public static Bitmap cropToLicenseAspectRatio(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float currentRatio = (float) width / height;

        int newWidth, newHeight, x, y;

        if (currentRatio > LICENSE_ASPECT_RATIO) {
            // Image is too wide, crop horizontally
            newHeight = height;
            newWidth = (int) (height * LICENSE_ASPECT_RATIO);
            x = (width - newWidth) / 2;
            y = 0;
        } else {
            // Image is too tall, crop vertically
            newWidth = width;
            newHeight = (int) (width / LICENSE_ASPECT_RATIO);
            x = 0;
            y = (height - newHeight) / 2;
        }

        try {
            return Bitmap.createBitmap(bitmap, x, y, newWidth, newHeight);
        } catch (Exception e) {
            Log.e(TAG, "Error cropping to license aspect ratio", e);
            return bitmap;
        }
    }

    /**
     * Applies basic image enhancement for better barcode detection.
     *
     * @param bitmap The image to enhance
     * @return Enhanced image
     */
    public static Bitmap enhanceForBarcodeDetection(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }

        try {
            // Create a mutable copy
            Bitmap enhanced = bitmap.copy(Bitmap.Config.ARGB_8888, true);

            // Apply contrast enhancement
            Canvas canvas = new Canvas(enhanced);
            Paint paint = new Paint();

            // Increase contrast
            ColorMatrix cm = new ColorMatrix(new float[] {
                    1.5f, 0, 0, 0, -50,  // Red
                    0, 1.5f, 0, 0, -50,  // Green
                    0, 0, 1.5f, 0, -50,  // Blue
                    0, 0, 0, 1, 0        // Alpha
            });

            paint.setColorFilter(new ColorMatrixColorFilter(cm));
            canvas.drawBitmap(enhanced, 0, 0, paint);

            return enhanced;

        } catch (Exception e) {
            Log.e(TAG, "Error enhancing image", e);
            return bitmap;
        }
    }

    /**
     * Converts a bitmap to grayscale.
     *
     * @param bitmap The color bitmap
     * @return Grayscale bitmap
     */
    public static Bitmap toGrayscale(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }

        try {
            Bitmap grayscale = Bitmap.createBitmap(
                    bitmap.getWidth(),
                    bitmap.getHeight(),
                    Bitmap.Config.ARGB_8888
            );

            Canvas canvas = new Canvas(grayscale);
            Paint paint = new Paint();

            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0); // Remove color saturation

            paint.setColorFilter(new ColorMatrixColorFilter(cm));
            canvas.drawBitmap(bitmap, 0, 0, paint);

            return grayscale;

        } catch (Exception e) {
            Log.e(TAG, "Error converting to grayscale", e);
            return bitmap;
        }
    }

    /**
     * Calculates the average brightness of an image.
     * Useful for determining if flash is needed.
     *
     * @param bitmap The image to analyze
     * @return Average brightness (0-255)
     */
    public static int calculateAverageBrightness(Bitmap bitmap) {
        if (bitmap == null) {
            return 128; // Return middle value if no image
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        // Sample every 10th pixel for performance
        int sampleStep = 10;
        long totalBrightness = 0;
        int sampleCount = 0;

        for (int y = 0; y < height; y += sampleStep) {
            for (int x = 0; x < width; x += sampleStep) {
                int pixel = bitmap.getPixel(x, y);
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;

                // Perceived brightness formula
                int brightness = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                totalBrightness += brightness;
                sampleCount++;
            }
        }

        return sampleCount > 0 ? (int) (totalBrightness / sampleCount) : 128;
    }

    /**
     * Checks if the image appears to be too dark.
     *
     * @param bitmap The image to check
     * @return true if image is too dark (may need flash)
     */
    public static boolean isTooDark(Bitmap bitmap) {
        int brightness = calculateAverageBrightness(bitmap);
        return brightness < 60; // Threshold for "too dark"
    }

    /**
     * Checks if the image appears to be too bright (overexposed).
     *
     * @param bitmap The image to check
     * @return true if image is too bright
     */
    public static boolean isTooBright(Bitmap bitmap) {
        int brightness = calculateAverageBrightness(bitmap);
        return brightness > 220; // Threshold for "too bright"
    }
}
