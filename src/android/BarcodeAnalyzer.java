/**
 * BarcodeAnalyzer.java
 *
 * PDF417 barcode analyzer using ZXing library.
 * Specifically optimized for decoding AAMVA-compliant US driver license barcodes.
 */
package com.sos.driverslicensescanner;

import android.graphics.Bitmap;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.pdf417.PDF417Reader;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Analyzes images for PDF417 barcodes using ZXing.
 * Optimized for US driver license barcode scanning.
 */
public class BarcodeAnalyzer {

    private static final String TAG = "BarcodeAnalyzer";

    /**
     * Callback interface for barcode detection results.
     */
    public interface BarcodeCallback {
        void onBarcodeDetected(String rawData);
    }

    private final BarcodeCallback callback;
    private final PDF417Reader pdf417Reader;
    private final MultiFormatReader multiFormatReader;
    private final Map<DecodeHintType, Object> hints;
    private final AtomicBoolean isProcessing;

    // Debounce: avoid reporting the same barcode repeatedly
    private String lastDetectedData;
    private long lastDetectionTime;
    private static final long DEBOUNCE_MS = 1000; // 1 second debounce

    /**
     * Creates a new BarcodeAnalyzer.
     *
     * @param callback Callback for barcode detection results
     */
    public BarcodeAnalyzer(BarcodeCallback callback) {
        this.callback = callback;
        this.pdf417Reader = new PDF417Reader();
        this.multiFormatReader = new MultiFormatReader();
        this.isProcessing = new AtomicBoolean(false);

        // Configure hints for optimal PDF417 decoding
        hints = new EnumMap<>(DecodeHintType.class);

        // Specify we're looking for PDF417 (primary) and fallback formats
        List<BarcodeFormat> formats = new ArrayList<>();
        formats.add(BarcodeFormat.PDF_417);
        // Also try these in case the barcode uses alternative encoding
        formats.add(BarcodeFormat.DATA_MATRIX);
        formats.add(BarcodeFormat.QR_CODE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);

        // Enable harder decoding for damaged/blurry barcodes
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        // Use ISO-8859-1 character set (common for AAMVA data)
        hints.put(DecodeHintType.CHARACTER_SET, "ISO-8859-1");

        // Allow detection of multiple barcodes (in case of retry scenarios)
        hints.put(DecodeHintType.PURE_BARCODE, Boolean.FALSE);

        multiFormatReader.setHints(hints);
    }

    /**
     * Analyzes a bitmap image for PDF417 barcodes.
     *
     * @param bitmap The image to analyze
     */
    public void analyze(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }

        // Prevent concurrent processing
        if (!isProcessing.compareAndSet(false, true)) {
            return;
        }

        try {
            String result = decodeBitmap(bitmap);

            if (result != null && !result.isEmpty()) {
                // Check debounce
                long now = System.currentTimeMillis();
                if (!result.equals(lastDetectedData) || (now - lastDetectionTime) > DEBOUNCE_MS) {
                    lastDetectedData = result;
                    lastDetectionTime = now;

                    Log.d(TAG, "Barcode decoded, length=" + result.length());
                    callback.onBarcodeDetected(result);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error analyzing barcode", e);
        } finally {
            isProcessing.set(false);
        }
    }

    /**
     * Decodes a bitmap image looking for PDF417 barcodes.
     * Tries multiple strategies for robust detection.
     *
     * @param bitmap The image to decode
     * @return The decoded barcode data, or null if not found
     */
    private String decodeBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }

        // Convert bitmap to ZXing-compatible format
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        // Try original orientation first
        String result = tryDecode(pixels, width, height);
        if (result != null) {
            return result;
        }

        // Try rotated 90 degrees (in case license is held sideways)
        int[] rotated90 = rotatePixels90(pixels, width, height);
        result = tryDecode(rotated90, height, width);
        if (result != null) {
            return result;
        }

        // Try rotated 180 degrees (upside down)
        int[] rotated180 = rotatePixels180(pixels, width, height);
        result = tryDecode(rotated180, width, height);
        if (result != null) {
            return result;
        }

        // Try rotated 270 degrees
        int[] rotated270 = rotatePixels270(pixels, width, height);
        result = tryDecode(rotated270, height, width);
        if (result != null) {
            return result;
        }

        // Try with contrast enhancement for low-quality images
        int[] enhanced = enhanceContrast(pixels, width, height);
        result = tryDecode(enhanced, width, height);
        if (result != null) {
            return result;
        }

        return null;
    }

    /**
     * Attempts to decode barcode from pixel array.
     */
    private String tryDecode(int[] pixels, int width, int height) {
        try {
            LuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));

            // Try PDF417 reader first (most common for driver licenses)
            try {
                Result result = pdf417Reader.decode(binaryBitmap, hints);
                if (result != null && result.getText() != null) {
                    // Validate it looks like AAMVA data
                    String text = result.getText();
                    if (isLikelyAAMVAData(text)) {
                        return text;
                    }
                }
            } catch (NotFoundException ignored) {
                // PDF417 not found, try multi-format
            }

            // Fallback to multi-format reader
            try {
                Result result = multiFormatReader.decode(binaryBitmap);
                if (result != null && result.getText() != null) {
                    String text = result.getText();
                    if (isLikelyAAMVAData(text)) {
                        return text;
                    }
                }
            } catch (NotFoundException ignored) {
                // No barcode found
            }

        } catch (Exception e) {
            Log.w(TAG, "Decode attempt failed", e);
        }

        return null;
    }

    /**
     * Checks if the decoded data looks like AAMVA driver license data.
     * AAMVA data typically starts with "@" and contains "ANSI" identifier.
     */
    private boolean isLikelyAAMVAData(String data) {
        if (data == null || data.length() < 20) {
            return false;
        }

        // AAMVA data typically starts with @ or contains ANSI header
        boolean hasStartMarker = data.startsWith("@") ||
                data.contains("@\n") ||
                data.contains("@\r");

        boolean hasANSIMarker = data.contains("ANSI");

        // Check for common field codes
        boolean hasFieldCodes = data.contains("DAQ") || // License number
                data.contains("DCS") || // Last name
                data.contains("DAC") || // First name
                data.contains("DBB");   // Date of birth

        return (hasStartMarker || hasANSIMarker) && hasFieldCodes;
    }

    /**
     * Rotates pixel array 90 degrees clockwise.
     */
    private int[] rotatePixels90(int[] pixels, int width, int height) {
        int[] rotated = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                rotated[x * height + (height - 1 - y)] = pixels[y * width + x];
            }
        }
        return rotated;
    }

    /**
     * Rotates pixel array 180 degrees.
     */
    private int[] rotatePixels180(int[] pixels, int width, int height) {
        int[] rotated = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            rotated[pixels.length - 1 - i] = pixels[i];
        }
        return rotated;
    }

    /**
     * Rotates pixel array 270 degrees clockwise (90 counter-clockwise).
     */
    private int[] rotatePixels270(int[] pixels, int width, int height) {
        int[] rotated = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                rotated[(width - 1 - x) * height + y] = pixels[y * width + x];
            }
        }
        return rotated;
    }

    /**
     * Enhances contrast of image for better barcode detection.
     * Uses simple histogram stretching.
     */
    private int[] enhanceContrast(int[] pixels, int width, int height) {
        int[] enhanced = new int[pixels.length];

        // Find min and max luminance
        int minLum = 255;
        int maxLum = 0;

        for (int pixel : pixels) {
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
            minLum = Math.min(minLum, lum);
            maxLum = Math.max(maxLum, lum);
        }

        // Stretch histogram
        int range = maxLum - minLum;
        if (range < 10) {
            // Image is too uniform, return original
            return pixels;
        }

        float scale = 255.0f / range;

        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int a = (pixel >> 24) & 0xFF;
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;

            // Apply contrast stretch to each channel
            r = clamp((int) ((r - minLum) * scale));
            g = clamp((int) ((g - minLum) * scale));
            b = clamp((int) ((b - minLum) * scale));

            enhanced[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }

        return enhanced;
    }

    /**
     * Clamps value to 0-255 range.
     */
    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /**
     * Resets the debounce state.
     */
    public void resetDebounce() {
        lastDetectedData = null;
        lastDetectionTime = 0;
    }

    /**
     * Cleans up resources.
     */
    public void shutdown() {
        pdf417Reader.reset();
        multiFormatReader.reset();
    }
}
