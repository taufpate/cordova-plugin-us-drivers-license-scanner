/**
 * ScanResult.java
 *
 * Data class representing the result of a driver license scan.
 * Contains raw data, parsed fields, and captured images.
 */
package com.sos.driverslicensescanner;

import android.graphics.Bitmap;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Encapsulates all data from a driver license scan operation.
 */
public class ScanResult {

    // Raw barcode data
    private String frontRawData;
    private String backRawData;

    // Parsed AAMVA fields
    private JSONObject parsedFields;

    // Captured images
    private Bitmap frontImage;
    private Bitmap backImage;
    private Bitmap portraitImage;

    // Error information
    private String errorCode;
    private String errorMessage;
    private boolean isSuccess;

    /**
     * Creates a successful scan result.
     */
    public ScanResult() {
        this.isSuccess = true;
    }

    /**
     * Creates an error scan result.
     *
     * @param errorCode Error code
     * @param errorMessage Human-readable error message
     */
    public ScanResult(String errorCode, String errorMessage) {
        this.isSuccess = false;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    // ==================== Getters and Setters ====================

    public String getFrontRawData() {
        return frontRawData;
    }

    public void setFrontRawData(String frontRawData) {
        this.frontRawData = frontRawData;
    }

    public String getBackRawData() {
        return backRawData;
    }

    public void setBackRawData(String backRawData) {
        this.backRawData = backRawData;
    }

    public JSONObject getParsedFields() {
        return parsedFields;
    }

    public void setParsedFields(JSONObject parsedFields) {
        this.parsedFields = parsedFields;
    }

    public Bitmap getFrontImage() {
        return frontImage;
    }

    public void setFrontImage(Bitmap frontImage) {
        this.frontImage = frontImage;
    }

    public Bitmap getBackImage() {
        return backImage;
    }

    public void setBackImage(Bitmap backImage) {
        this.backImage = backImage;
    }

    public Bitmap getPortraitImage() {
        return portraitImage;
    }

    public void setPortraitImage(Bitmap portraitImage) {
        this.portraitImage = portraitImage;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
        this.isSuccess = (errorCode == null || errorCode.isEmpty());
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public boolean isSuccess() {
        return isSuccess;
    }

    public void setSuccess(boolean success) {
        isSuccess = success;
    }

    // ==================== Conversion Methods ====================

    /**
     * Converts this scan result to a JSON object for returning to JavaScript.
     *
     * @param includeFullImages Whether to include full front/back images
     * @param imageQuality JPEG quality for image encoding (0-100)
     * @return JSON representation of the scan result
     * @throws JSONException If JSON creation fails
     */
    public JSONObject toJSON(boolean includeFullImages, int imageQuality) throws JSONException {
        JSONObject json = new JSONObject();

        if (!isSuccess) {
            json.put("success", false);
            json.put("errorCode", errorCode);
            json.put("errorMessage", errorMessage);
            return json;
        }

        json.put("success", true);

        // Raw data
        json.put("frontRawData", frontRawData != null ? frontRawData : JSONObject.NULL);
        json.put("backRawData", backRawData != null ? backRawData : JSONObject.NULL);

        // Parsed fields
        if (parsedFields != null) {
            json.put("parsedFields", parsedFields);
        } else {
            json.put("parsedFields", new JSONObject());
        }

        // Portrait image
        if (portraitImage != null && !portraitImage.isRecycled()) {
            json.put("portraitImageBase64", ImageUtils.bitmapToBase64(portraitImage, "JPEG", imageQuality));
        } else {
            json.put("portraitImageBase64", "");
        }

        // Full images
        if (includeFullImages) {
            if (frontImage != null && !frontImage.isRecycled()) {
                json.put("fullFrontImageBase64", ImageUtils.bitmapToBase64(frontImage, "JPEG", imageQuality));
            }
            if (backImage != null && !backImage.isRecycled()) {
                json.put("fullBackImageBase64", ImageUtils.bitmapToBase64(backImage, "JPEG", imageQuality));
            }
        }

        return json;
    }

    /**
     * Releases all bitmap resources held by this result.
     */
    public void recycle() {
        if (frontImage != null && !frontImage.isRecycled()) {
            frontImage.recycle();
            frontImage = null;
        }
        if (backImage != null && !backImage.isRecycled()) {
            backImage.recycle();
            backImage = null;
        }
        if (portraitImage != null && !portraitImage.isRecycled()) {
            portraitImage.recycle();
            portraitImage = null;
        }
    }

    /**
     * Returns a brief summary of this scan result for logging.
     */
    @Override
    public String toString() {
        if (!isSuccess) {
            return "ScanResult[error=" + errorCode + ": " + errorMessage + "]";
        }

        StringBuilder sb = new StringBuilder("ScanResult[success, ");
        if (backRawData != null) {
            sb.append("barcodeLen=").append(backRawData.length()).append(", ");
        }
        if (parsedFields != null) {
            try {
                sb.append("name=")
                        .append(parsedFields.optString("firstName", ""))
                        .append(" ")
                        .append(parsedFields.optString("lastName", ""))
                        .append(", ");
            } catch (Exception ignored) {
            }
        }
        sb.append("portrait=").append(portraitImage != null);
        sb.append("]");
        return sb.toString();
    }

    // ==================== Builder Pattern ====================

    /**
     * Builder class for constructing ScanResult objects.
     */
    public static class Builder {
        private final ScanResult result;

        public Builder() {
            result = new ScanResult();
        }

        public Builder frontRawData(String data) {
            result.setFrontRawData(data);
            return this;
        }

        public Builder backRawData(String data) {
            result.setBackRawData(data);
            return this;
        }

        public Builder parsedFields(JSONObject fields) {
            result.setParsedFields(fields);
            return this;
        }

        public Builder frontImage(Bitmap image) {
            result.setFrontImage(image);
            return this;
        }

        public Builder backImage(Bitmap image) {
            result.setBackImage(image);
            return this;
        }

        public Builder portraitImage(Bitmap image) {
            result.setPortraitImage(image);
            return this;
        }

        public Builder error(String code, String message) {
            result.setErrorCode(code);
            result.setErrorMessage(message);
            result.setSuccess(false);
            return this;
        }

        public ScanResult build() {
            return result;
        }
    }
}
