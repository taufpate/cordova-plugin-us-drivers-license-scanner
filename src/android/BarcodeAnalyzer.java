/**
 * BarcodeAnalyzer.java
 *
 * PDF417 barcode analyzer using ZXing library with strategy-rotation image processing.
 *
 * To avoid starving the decoder of fresh frames, only TWO strategies are
 * tried per frame: the original (HybridBinarizer) plus ONE rotating enhanced
 * strategy. The enhanced strategies cycle across consecutive frames:
 *
 *   Frame N+0: original + GlobalHistogramBinarizer
 *   Frame N+1: original + sharpened-contrast
 *   Frame N+2: original + high-contrast-grayscale
 *   Frame N+3: original + anti-reflection
 *   Frame N+4: original + rotated-90°
 *   Frame N+5: original + luminance-sharpen (blur recovery)
 *   Frame N+6: original + downscale-50% (blur averaging)
 *   Frame N+7: original + large-radius deblur
 *   Frame N+8: (cycle repeats)
 *
 * This keeps per-frame processing ~25ms instead of ~80ms when all ran,
 * preventing the isProcessing flag from blocking fresh frames too long.
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
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.pdf417.PDF417Reader;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Analyzes images for PDF417 barcodes using ZXing.
 * Uses iOS-matching strategy rotation: original + 1 rotating enhanced per frame.
 */
public class BarcodeAnalyzer {

    private static final String TAG = "BarcodeAnalyzer";

    // Number of enhanced strategies to rotate through (excludes the original which always runs)
    private static final int ENHANCED_STRATEGY_COUNT = 8;

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

    // Strategy rotation index — cycles 0..7 across frames
    private int strategyIndex;

    // Debounce: avoid reporting the same barcode repeatedly
    private String lastDetectedData;
    private long lastDetectionTime;
    private static final long DEBOUNCE_MS = 1000;

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
        this.strategyIndex = 0;

        // Configure hints for optimal PDF417 decoding
        hints = new EnumMap<>(DecodeHintType.class);

        List<BarcodeFormat> formats = new ArrayList<>();
        formats.add(BarcodeFormat.PDF_417);
        formats.add(BarcodeFormat.DATA_MATRIX);
        formats.add(BarcodeFormat.QR_CODE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);

        // Enable harder decoding for damaged/blurry barcodes
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

        // Use ISO-8859-1 character set (common for AAMVA data)
        hints.put(DecodeHintType.CHARACTER_SET, "ISO-8859-1");

        hints.put(DecodeHintType.PURE_BARCODE, Boolean.FALSE);

        multiFormatReader.setHints(hints);
    }

    /**
     * Analyzes a bitmap image for PDF417 barcodes using strategy rotation.
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
     * Decodes a bitmap using strategy rotation matching iOS implementation.
     * Always tries original (HybridBinarizer) first, then one rotating enhanced strategy.
     */
    private String decodeBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        // ---- Always try the original image first (HybridBinarizer) ----
        String result = tryDecode(pixels, width, height);
        if (result != null) {
            return result;
        }

        // ---- If original failed, try ONE rotating enhanced strategy ----
        int idx = strategyIndex % ENHANCED_STRATEGY_COUNT;
        strategyIndex++;

        switch (idx) {
            case 0: {
                // GlobalHistogramBinarizer — different thresholding
                result = tryDecodeWithGlobalBinarizer(pixels, width, height);
                if (result != null) {
                    Log.d(TAG, "Decoded via global-histogram strategy");
                }
                break;
            }
            case 1: {
                // Sharpened + contrast + desaturated (3x3 unsharp mask)
                int[] enhanced = createSharpenedContrastImage(pixels, width, height);
                if (enhanced != null) {
                    result = tryDecode(enhanced, width, height);
                    if (result != null) {
                        Log.d(TAG, "Decoded via sharpened-contrast strategy");
                    }
                }
                break;
            }
            case 2: {
                // High-contrast grayscale (near-binary)
                int[] grayscale = createHighContrastGrayscaleImage(pixels, width, height);
                if (grayscale != null) {
                    result = tryDecode(grayscale, width, height);
                    if (result != null) {
                        Log.d(TAG, "Decoded via high-contrast-grayscale strategy");
                    }
                }
                break;
            }
            case 3: {
                // Anti-reflection (highlight compress + shadow lift)
                int[] antiReflect = createAntiReflectionImage(pixels, width, height);
                if (antiReflect != null) {
                    result = tryDecode(antiReflect, width, height);
                    if (result != null) {
                        Log.d(TAG, "Decoded via anti-reflection strategy");
                    }
                }
                break;
            }
            case 4: {
                // 90° rotation fallback
                int[] rotated90 = rotatePixels90(pixels, width, height);
                result = tryDecode(rotated90, height, width);
                if (result != null) {
                    Log.d(TAG, "Decoded via rotated-90 strategy");
                }
                break;
            }
            case 5: {
                // Luminance-only sharpening — recovers edges without color artifacts
                int[] sharpLum = createLuminanceSharpenedImage(pixels, width, height);
                if (sharpLum != null) {
                    result = tryDecode(sharpLum, width, height);
                    if (result != null) {
                        Log.d(TAG, "Decoded via luminance-sharpen strategy");
                    }
                }
                break;
            }
            case 6: {
                // Downscale 50% — pixel averaging smooths out blur/noise
                Bitmap downscaled = Bitmap.createScaledBitmap(bitmap, width / 2, height / 2, true);
                if (downscaled != null) {
                    int dw = downscaled.getWidth();
                    int dh = downscaled.getHeight();
                    int[] dPixels = new int[dw * dh];
                    downscaled.getPixels(dPixels, 0, dw, 0, 0, dw, dh);
                    downscaled.recycle();
                    result = tryDecode(dPixels, dw, dh);
                    if (result != null) {
                        Log.d(TAG, "Decoded via downscale-50 strategy");
                    }
                }
                break;
            }
            case 7: {
                // Large-radius deblur (7x7 box-blur-based unsharp mask)
                int[] deblurred = createDeblurredImage(pixels, width, height);
                if (deblurred != null) {
                    result = tryDecode(deblurred, width, height);
                    if (result != null) {
                        Log.d(TAG, "Decoded via deblur strategy");
                    }
                }
                break;
            }
            default:
                break;
        }

        return result;
    }

    // ==================== Image Processing Strategies ====================

    /**
     * Sharpens barcode module edges (3x3 unsharp mask), increases contrast,
     * and removes colour information so the binarizer sees cleaner edges.
     * Best for: slightly worn or slightly blurry barcodes.
     */
    private int[] createSharpenedContrastImage(int[] pixels, int width, int height) {
        // 3x3 unsharp mask kernel: sharpen center, subtract neighbors
        int[] sharpened = new int[pixels.length];

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int idx = y * width + x;

                int center = pixels[idx];
                int cR = (center >> 16) & 0xFF;
                int cG = (center >> 8) & 0xFF;
                int cB = center & 0xFF;

                // Average of 4 neighbors
                int top = pixels[(y - 1) * width + x];
                int bot = pixels[(y + 1) * width + x];
                int left = pixels[y * width + (x - 1)];
                int right = pixels[y * width + (x + 1)];

                int avgR = (((top >> 16) & 0xFF) + ((bot >> 16) & 0xFF) +
                        ((left >> 16) & 0xFF) + ((right >> 16) & 0xFF)) / 4;
                int avgG = (((top >> 8) & 0xFF) + ((bot >> 8) & 0xFF) +
                        ((left >> 8) & 0xFF) + ((right >> 8) & 0xFF)) / 4;
                int avgB = ((top & 0xFF) + (bot & 0xFF) +
                        (left & 0xFF) + (right & 0xFF)) / 4;

                // Unsharp mask: result = center + intensity * (center - blurred)
                float intensity = 2.5f;
                int sR = clamp((int) (cR + intensity * (cR - avgR)));
                int sG = clamp((int) (cG + intensity * (cG - avgG)));
                int sB = clamp((int) (cB + intensity * (cB - avgB)));

                // Contrast boost (1.5x around midpoint) + full desaturation
                int lum = luminance(sR, sG, sB);
                int contrasted = clamp((int) ((lum - 128) * 1.5f + 128));

                sharpened[idx] = 0xFF000000 | (contrasted << 16) | (contrasted << 8) | contrasted;
            }
        }

        // Copy edges
        for (int x = 0; x < width; x++) {
            sharpened[x] = pixels[x];
            sharpened[(height - 1) * width + x] = pixels[(height - 1) * width + x];
        }
        for (int y = 0; y < height; y++) {
            sharpened[y * width] = pixels[y * width];
            sharpened[y * width + width - 1] = pixels[y * width + width - 1];
        }

        return sharpened;
    }

    /**
     * Full grayscale with aggressive contrast — pushes the image toward binary.
     * Best for: heavily worn, faded, or low-contrast barcodes.
     */
    private int[] createHighContrastGrayscaleImage(int[] pixels, int width, int height) {
        int[] result = new int[pixels.length];

        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;

            // Full desaturation
            int lum = luminance(r, g, b);

            // Heavy contrast (2.5x) + slight brightness lift
            int val = clamp((int) ((lum - 128) * 2.5f + 128 + 12));

            result[i] = 0xFF000000 | (val << 16) | (val << 8) | val;
        }

        return result;
    }

    /**
     * Counteracts reflections and glare by compressing highlights and
     * lifting shadows, then boosting contrast.
     * Best for: shiny/laminated licenses with specular reflections.
     */
    private int[] createAntiReflectionImage(int[] pixels, int width, int height) {
        int[] result = new int[pixels.length];

        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;

            // Tame highlights (compress values above 180) and lift shadows (boost below 75)
            r = antiReflectChannel(r);
            g = antiReflectChannel(g);
            b = antiReflectChannel(b);

            // Desaturate
            int lum = luminance(r, g, b);

            // Contrast boost (1.8x)
            int val = clamp((int) ((lum - 128) * 1.8f + 128));

            result[i] = 0xFF000000 | (val << 16) | (val << 8) | val;
        }

        return result;
    }

    /** Compress highlights, lift shadows for a single channel. */
    private int antiReflectChannel(int val) {
        if (val > 180) {
            // Compress highlights: map 180-255 to 180-210
            val = 180 + (int) ((val - 180) * 0.4f);
        } else if (val < 75) {
            // Lift shadows: map 0-75 to 30-75
            val = 30 + (int) ((val / 75.0f) * 45);
        }
        return clamp(val);
    }

    /**
     * Luminance-only sharpening. Unlike the unsharp mask in strategy 1,
     * this only sharpens the brightness channel, avoiding colour artifacts.
     * Best for: moderate blur where barcode edges are soft but visible.
     */
    private int[] createLuminanceSharpenedImage(int[] pixels, int width, int height) {
        int[] result = new int[pixels.length];

        // First pass: compute luminance and apply sharpening
        int[] lumArr = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            lumArr[i] = luminance((pixel >> 16) & 0xFF, (pixel >> 8) & 0xFF, pixel & 0xFF);
        }

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int idx = y * width + x;

                int center = lumArr[idx];
                int avgNeighbor = (lumArr[(y - 1) * width + x] + lumArr[(y + 1) * width + x] +
                        lumArr[y * width + (x - 1)] + lumArr[y * width + (x + 1)]) / 4;

                // Sharpen luminance (intensity 2.0)
                int sharpLum = clamp(center + (int) (2.0f * (center - avgNeighbor)));

                // Desaturate + light contrast boost (1.3x)
                int val = clamp((int) ((sharpLum - 128) * 1.3f + 128));

                result[idx] = 0xFF000000 | (val << 16) | (val << 8) | val;
            }
        }

        // Copy edges
        for (int x = 0; x < width; x++) {
            int topLum = lumArr[x];
            result[x] = 0xFF000000 | (topLum << 16) | (topLum << 8) | topLum;
            int botLum = lumArr[(height - 1) * width + x];
            result[(height - 1) * width + x] = 0xFF000000 | (botLum << 16) | (botLum << 8) | botLum;
        }
        for (int y = 0; y < height; y++) {
            int leftLum = lumArr[y * width];
            result[y * width] = 0xFF000000 | (leftLum << 16) | (leftLum << 8) | leftLum;
            int rightLum = lumArr[y * width + width - 1];
            result[y * width + width - 1] = 0xFF000000 | (rightLum << 16) | (rightLum << 8) | rightLum;
        }

        return result;
    }

    /**
     * Large-radius unsharp mask (7x7 box blur) targeting defocus blur.
     * A bigger radius captures the blur spread and compensates for it.
     * Combined with desaturation and contrast boost for cleaner binarization.
     * Best for: out-of-focus barcodes where the camera didn't lock focus properly.
     */
    private int[] createDeblurredImage(int[] pixels, int width, int height) {
        // 7x7 box blur on luminance
        int radius = 3; // 7x7 = radius 3
        int[] lumArr = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            lumArr[i] = luminance((pixel >> 16) & 0xFF, (pixel >> 8) & 0xFF, pixel & 0xFF);
        }

        // Compute box blur using integral image for efficiency
        int[] blurred = boxBlur(lumArr, width, height, radius);

        // Unsharp mask: result = original + intensity * (original - blurred)
        float intensity = 3.0f;
        int[] result = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            int sharp = clamp((int) (lumArr[i] + intensity * (lumArr[i] - blurred[i])));
            // Desaturate + contrast boost (1.6x)
            int val = clamp((int) ((sharp - 128) * 1.6f + 128));
            result[i] = 0xFF000000 | (val << 16) | (val << 8) | val;
        }

        return result;
    }

    /** Simple box blur using running sums. */
    private int[] boxBlur(int[] input, int width, int height, int radius) {
        int[] temp = new int[input.length];
        int[] output = new int[input.length];

        // Horizontal pass
        for (int y = 0; y < height; y++) {
            int sum = 0;
            int count = 0;
            for (int x = 0; x < width; x++) {
                sum += input[y * width + x];
                count++;
                if (x > 2 * radius) {
                    sum -= input[y * width + x - 2 * radius - 1];
                    count--;
                }
                int startX = Math.max(0, x - radius);
                temp[y * width + x] = sum / count;
            }
        }

        // Vertical pass
        for (int x = 0; x < width; x++) {
            int sum = 0;
            int count = 0;
            for (int y = 0; y < height; y++) {
                sum += temp[y * width + x];
                count++;
                if (y > 2 * radius) {
                    sum -= temp[(y - 2 * radius - 1) * width + x];
                    count--;
                }
                output[y * width + x] = sum / count;
            }
        }

        return output;
    }

    // ==================== ZXing Decode Helpers ====================

    /**
     * Primary decode path using HybridBinarizer (adaptive local thresholding).
     */
    private String tryDecode(int[] pixels, int width, int height) {
        try {
            LuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap binaryBitmap = new BinaryBitmap(new HybridBinarizer(source));

            // Try PDF417 reader first
            try {
                Result result = pdf417Reader.decode(binaryBitmap, hints);
                if (result != null && result.getText() != null) {
                    String text = result.getText();
                    if (isLikelyAAMVAData(text)) {
                        return text;
                    }
                }
            } catch (NotFoundException ignored) {
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
            }

        } catch (Exception e) {
            Log.w(TAG, "Decode attempt failed", e);
        }

        return null;
    }

    /**
     * Alternative decode path using GlobalHistogramBinarizer.
     * Uses a single global threshold computed from the image histogram.
     * Can outperform HybridBinarizer in uniformly-lit conditions.
     */
    private String tryDecodeWithGlobalBinarizer(int[] pixels, int width, int height) {
        try {
            LuminanceSource source = new RGBLuminanceSource(width, height, pixels);
            BinaryBitmap binaryBitmap = new BinaryBitmap(new GlobalHistogramBinarizer(source));

            try {
                Result result = pdf417Reader.decode(binaryBitmap, hints);
                if (result != null && result.getText() != null) {
                    String text = result.getText();
                    if (isLikelyAAMVAData(text)) {
                        return text;
                    }
                }
            } catch (NotFoundException ignored) {
            }

            try {
                Result result = multiFormatReader.decode(binaryBitmap);
                if (result != null && result.getText() != null) {
                    String text = result.getText();
                    if (isLikelyAAMVAData(text)) {
                        return text;
                    }
                }
            } catch (NotFoundException ignored) {
            }

        } catch (Exception e) {
            Log.w(TAG, "Global binarizer decode failed", e);
        }

        return null;
    }

    // ==================== Pixel Helpers ====================

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

    /** Perceived luminance from RGB. */
    private int luminance(int r, int g, int b) {
        return (int) (0.299f * r + 0.587f * g + 0.114f * b);
    }

    /** Clamps value to 0-255 range. */
    private int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    // ==================== Validation ====================

    /**
     * Checks if the decoded data looks like AAMVA driver license data.
     */
    private boolean isLikelyAAMVAData(String data) {
        if (data == null || data.length() < 20) {
            return false;
        }

        boolean hasStartMarker = data.startsWith("@") ||
                data.contains("@\n") ||
                data.contains("@\r");

        boolean hasANSIMarker = data.contains("ANSI");

        boolean hasFieldCodes = data.contains("DAQ") ||
                data.contains("DCS") ||
                data.contains("DAC") ||
                data.contains("DBB");

        return (hasStartMarker || hasANSIMarker) && hasFieldCodes;
    }

    // ==================== Public Methods ====================

    /**
     * Resets the strategy rotation index. Call when starting a new scan phase.
     */
    public void resetStrategyIndex() {
        strategyIndex = 0;
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
