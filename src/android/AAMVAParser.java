/**
 * AAMVAParser.java
 *
 * Parses AAMVA-compliant driver license data from PDF417 barcodes.
 * Supports AAMVA versions 1-10 and handles jurisdiction-specific variations.
 *
 * AAMVA = American Association of Motor Vehicle Administrators
 *
 * Standard Field Codes:
 * DCS - Family Name (Last Name)
 * DAC - First Name
 * DAD - Middle Name
 * DBB - Date of Birth
 * DBA - License Expiration Date
 * DBD - License Issue Date
 * DBC - Sex (1=Male, 2=Female, 9=Not Specified)
 * DAQ - License Number
 * DAG - Street Address
 * DAI - City
 * DAJ - State/Jurisdiction
 * DAK - Postal Code
 * DCF - Document Discriminator
 * DCG - Country
 * DAU - Height
 * DAY - Eye Color
 * DAZ - Hair Color
 * DAW - Weight
 * DCU - Name Suffix
 * DDE - Family Name Truncation
 * DDF - First Name Truncation
 * DDG - Middle Name Truncation
 */
package com.sos.driverslicensescanner;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for AAMVA driver license barcode data.
 */
public class AAMVAParser {

    private static final String TAG = "AAMVAParser";

    // AAMVA Field codes
    private static final String FIELD_FAMILY_NAME = "DCS";
    private static final String FIELD_FIRST_NAME = "DAC";
    private static final String FIELD_MIDDLE_NAME = "DAD";
    private static final String FIELD_DATE_OF_BIRTH = "DBB";
    private static final String FIELD_EXPIRATION_DATE = "DBA";
    private static final String FIELD_ISSUE_DATE = "DBD";
    private static final String FIELD_SEX = "DBC";
    private static final String FIELD_LICENSE_NUMBER = "DAQ";
    private static final String FIELD_STREET_ADDRESS = "DAG";
    private static final String FIELD_STREET_ADDRESS_2 = "DAH";
    private static final String FIELD_CITY = "DAI";
    private static final String FIELD_STATE = "DAJ";
    private static final String FIELD_POSTAL_CODE = "DAK";
    private static final String FIELD_DOCUMENT_DISCRIMINATOR = "DCF";
    private static final String FIELD_COUNTRY = "DCG";
    private static final String FIELD_HEIGHT = "DAU";
    private static final String FIELD_EYE_COLOR = "DAY";
    private static final String FIELD_HAIR_COLOR = "DAZ";
    private static final String FIELD_WEIGHT_LBS = "DAW";
    private static final String FIELD_WEIGHT_KG = "DAX";
    private static final String FIELD_NAME_SUFFIX = "DCU";
    private static final String FIELD_NAME_PREFIX = "DAA";
    private static final String FIELD_FULL_NAME = "DAA";
    private static final String FIELD_FAMILY_NAME_TRUNCATION = "DDE";
    private static final String FIELD_FIRST_NAME_TRUNCATION = "DDF";
    private static final String FIELD_MIDDLE_NAME_TRUNCATION = "DDG";

    // Additional optional fields
    private static final String FIELD_RACE = "DCL";
    private static final String FIELD_COMPLIANCE_TYPE = "DDA";
    private static final String FIELD_CARD_REVISION_DATE = "DDB";
    private static final String FIELD_LIMITED_DURATION = "DDD";
    private static final String FIELD_ORGAN_DONOR = "DDK";
    private static final String FIELD_VETERAN = "DDL";

    // Regex for extracting AAMVA version from header
    private static final Pattern VERSION_PATTERN = Pattern.compile("ANSI\\s*(\\d{6})(\\d{2})(\\d{2})");

    // Date format patterns
    private final SimpleDateFormat aamvaDateFormat = new SimpleDateFormat("MMddyyyy", Locale.US);
    private final SimpleDateFormat aamvaDateFormatAlt = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private final SimpleDateFormat isoDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    /**
     * Parses raw AAMVA barcode data into a structured JSON object.
     *
     * @param rawData The raw barcode data string
     * @return JSONObject containing parsed fields, or null if parsing fails
     */
    public JSONObject parse(String rawData) {
        if (rawData == null || rawData.isEmpty()) {
            Log.w(TAG, "Empty raw data provided");
            return createErrorResult("No data provided");
        }

        try {
            // Normalize the data (handle different line endings)
            String normalized = normalizeData(rawData);

            // Extract all field values
            Map<String, String> fields = extractFields(normalized);

            if (fields.isEmpty()) {
                Log.w(TAG, "No fields extracted from data");
                return createErrorResult("Could not extract any fields from barcode data");
            }

            // Extract AAMVA version info
            int[] versionInfo = extractVersion(normalized);

            // Build result JSON
            return buildResult(fields, versionInfo, rawData);

        } catch (Exception e) {
            Log.e(TAG, "Error parsing AAMVA data", e);
            return createErrorResult("Parse error: " + e.getMessage());
        }
    }

    /**
     * Normalizes barcode data by standardizing line endings and removing noise.
     */
    private String normalizeData(String data) {
        // Replace various delimiters with standard newline.
        // AAMVA barcodes use different delimiters across versions:
        // - \r\n or \r (line endings)
        // - \u001e (record separator, RS, 0x1E)
        // - \u001f (unit separator, US, 0x1F) — strip these entirely
        String normalized = data
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\u001e", "\n")
                .replace("\u001f", "");

        // Remove any leading/trailing whitespace from each line
        String[] lines = normalized.split("\n");
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line.trim()).append("\n");
        }

        return builder.toString();
    }

    /**
     * Extracts all field code/value pairs from the data.
     */
    private Map<String, String> extractFields(String data) {
        Map<String, String> fields = new HashMap<>();

        // Split by newlines to get individual fields
        String[] lines = data.split("\n");

        for (String line : lines) {
            if (line.length() < 3) {
                continue;
            }

            // Field codes are 3 characters
            String code = line.substring(0, 3).toUpperCase();
            String value = line.length() > 3 ? line.substring(3).trim() : "";

            // Only store if it looks like a valid field code (all caps letters)
            if (code.matches("[A-Z]{3}")) {
                fields.put(code, value);
            }
        }

        // Also try to parse format with field codes embedded in continuous string
        // Some barcodes use format like: DAQxxxxxxDCSyyyyyyy
        if (fields.size() < 3) {
            extractInlineFields(data, fields);
        }

        return fields;
    }

    /**
     * Extracts fields from inline format (no newlines between fields).
     */
    private void extractInlineFields(String data, Map<String, String> fields) {
        // List of known field codes to look for
        String[] knownCodes = {
                "DCS", "DAC", "DAD", "DBB", "DBA", "DBD", "DBC", "DAQ",
                "DAG", "DAH", "DAI", "DAJ", "DAK", "DCF", "DCG", "DAU",
                "DAY", "DAZ", "DAW", "DAX", "DCU", "DAA", "DDE", "DDF",
                "DDG", "DCL", "DDA", "DDB", "DDD", "DDK", "DDL"
        };

        for (String code : knownCodes) {
            int startIndex = data.indexOf(code);
            if (startIndex >= 0) {
                int valueStart = startIndex + 3;
                int valueEnd = data.length();

                // Find the end of this field (start of next field code or end of string)
                for (String nextCode : knownCodes) {
                    int nextIndex = data.indexOf(nextCode, valueStart);
                    if (nextIndex > valueStart && nextIndex < valueEnd) {
                        valueEnd = nextIndex;
                    }
                }

                String value = data.substring(valueStart, valueEnd).trim();

                // Clean up value (remove control characters)
                value = value.replaceAll("[\\x00-\\x1F]", "");

                if (!value.isEmpty() && !fields.containsKey(code)) {
                    fields.put(code, value);
                }
            }
        }
    }

    /**
     * Extracts AAMVA version information from the header.
     *
     * @return Array of [jurisdictionCode, aamvaVersion, jurisdictionVersion]
     */
    private int[] extractVersion(String data) {
        Matcher matcher = VERSION_PATTERN.matcher(data);
        if (matcher.find()) {
            try {
                int jurisdictionCode = Integer.parseInt(matcher.group(1));
                int aamvaVersion = Integer.parseInt(matcher.group(2));
                int jurisdictionVersion = Integer.parseInt(matcher.group(3));
                return new int[]{jurisdictionCode, aamvaVersion, jurisdictionVersion};
            } catch (NumberFormatException e) {
                Log.w(TAG, "Could not parse version numbers");
            }
        }
        return new int[]{0, 0, 0};
    }

    /**
     * Builds the final result JSON object.
     */
    private JSONObject buildResult(Map<String, String> fields, int[] versionInfo, String rawData) throws JSONException {
        JSONObject result = new JSONObject();

        // Names
        String lastName = getField(fields, FIELD_FAMILY_NAME);
        String firstName = getField(fields, FIELD_FIRST_NAME);
        String middleName = getField(fields, FIELD_MIDDLE_NAME);

        // Handle full name field (some older formats)
        if ((lastName.isEmpty() || firstName.isEmpty()) && fields.containsKey(FIELD_FULL_NAME)) {
            String fullName = fields.get(FIELD_FULL_NAME);
            String[] nameParts = parseFullName(fullName);
            if (lastName.isEmpty()) lastName = nameParts[0];
            if (firstName.isEmpty()) firstName = nameParts[1];
            if (middleName.isEmpty()) middleName = nameParts[2];
        }

        result.put("lastName", lastName);
        result.put("firstName", firstName);
        result.put("middleName", middleName);
        result.put("fullName", buildFullName(firstName, middleName, lastName));

        // Dates
        String dobRaw = getField(fields, FIELD_DATE_OF_BIRTH);
        String expirationRaw = getField(fields, FIELD_EXPIRATION_DATE);
        String issueRaw = getField(fields, FIELD_ISSUE_DATE);

        result.put("dateOfBirthRaw", dobRaw);
        result.put("dateOfBirth", parseDate(dobRaw));
        result.put("expirationDateRaw", expirationRaw);
        result.put("expirationDate", parseDate(expirationRaw));
        result.put("issueDateRaw", issueRaw);
        result.put("issueDate", parseDate(issueRaw));

        // Check if expired
        result.put("isExpired", isExpired(expirationRaw));

        // Address
        String street = getField(fields, FIELD_STREET_ADDRESS);
        String street2 = getField(fields, FIELD_STREET_ADDRESS_2);
        String city = getField(fields, FIELD_CITY);
        String state = getField(fields, FIELD_STATE);
        String postalCode = formatPostalCode(getField(fields, FIELD_POSTAL_CODE));

        result.put("streetAddress", street);
        result.put("streetAddress2", street2);
        result.put("city", city);
        result.put("state", state);
        result.put("postalCode", postalCode);
        result.put("fullAddress", buildFullAddress(street, street2, city, state, postalCode));

        // License info
        result.put("licenseNumber", getField(fields, FIELD_LICENSE_NUMBER));
        result.put("documentDiscriminator", getField(fields, FIELD_DOCUMENT_DISCRIMINATOR));

        // Gender
        String genderCode = getField(fields, FIELD_SEX);
        result.put("genderCode", genderCode);
        result.put("gender", parseGender(genderCode));

        // Country and issuing state
        String country = getField(fields, FIELD_COUNTRY);
        result.put("issuingCountry", country.isEmpty() ? "USA" : country);
        result.put("issuingState", state);

        // Physical characteristics
        result.put("height", parseHeight(getField(fields, FIELD_HEIGHT)));
        result.put("eyeColor", parseEyeColor(getField(fields, FIELD_EYE_COLOR)));
        result.put("hairColor", parseHairColor(getField(fields, FIELD_HAIR_COLOR)));

        String weightLbs = getField(fields, FIELD_WEIGHT_LBS);
        String weightKg = getField(fields, FIELD_WEIGHT_KG);
        result.put("weight", weightLbs.isEmpty() ? weightKg : weightLbs);

        // Name suffix/prefix
        result.put("nameSuffix", getField(fields, FIELD_NAME_SUFFIX));
        result.put("namePrefix", getField(fields, FIELD_NAME_PREFIX));

        // Version info
        result.put("aamvaVersion", String.valueOf(versionInfo[1]));
        result.put("jurisdictionVersion", String.valueOf(versionInfo[2]));
        result.put("jurisdictionCode", String.valueOf(versionInfo[0]));

        // Additional fields object for any other extracted fields
        JSONObject additionalFields = new JSONObject();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String code = entry.getKey();
            // Skip fields we've already processed
            if (!isStandardField(code)) {
                additionalFields.put(code, entry.getValue());
            }
        }
        result.put("additionalFields", additionalFields);

        // Validation flag
        boolean isValid = !lastName.isEmpty() && !firstName.isEmpty() &&
                !getField(fields, FIELD_LICENSE_NUMBER).isEmpty();
        result.put("isValid", isValid);

        return result;
    }

    /**
     * Gets a field value or empty string if not present.
     */
    private String getField(Map<String, String> fields, String code) {
        String value = fields.get(code);
        return value != null ? value.trim() : "";
    }

    /**
     * Parses a full name string into [last, first, middle] parts.
     */
    private String[] parseFullName(String fullName) {
        String[] parts = new String[]{"", "", ""};
        if (fullName == null || fullName.isEmpty()) {
            return parts;
        }

        // AAMVA format is typically: LAST,FIRST,MIDDLE or LAST FIRST MIDDLE
        if (fullName.contains(",")) {
            String[] split = fullName.split(",");
            if (split.length >= 1) parts[0] = split[0].trim();
            if (split.length >= 2) parts[1] = split[1].trim();
            if (split.length >= 3) parts[2] = split[2].trim();
        } else {
            String[] split = fullName.trim().split("\\s+");
            if (split.length >= 1) parts[0] = split[0];
            if (split.length >= 2) parts[1] = split[1];
            if (split.length >= 3) parts[2] = split[2];
        }

        return parts;
    }

    /**
     * Builds a full name string from parts.
     */
    private String buildFullName(String first, String middle, String last) {
        StringBuilder sb = new StringBuilder();
        if (first != null && !first.isEmpty()) {
            sb.append(first);
        }
        if (middle != null && !middle.isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(middle);
        }
        if (last != null && !last.isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(last);
        }
        return sb.toString();
    }

    /**
     * Builds a full address string from parts.
     */
    private String buildFullAddress(String street, String street2, String city, String state, String postal) {
        StringBuilder sb = new StringBuilder();

        if (street != null && !street.isEmpty()) {
            sb.append(street);
        }
        if (street2 != null && !street2.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(street2);
        }
        if (city != null && !city.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(city);
        }
        if (state != null && !state.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(state);
        }
        if (postal != null && !postal.isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(postal);
        }

        return sb.toString();
    }

    /**
     * Parses a date string from AAMVA format to ISO format.
     */
    private String parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return "";
        }

        // Remove any non-numeric characters
        String cleanDate = dateStr.replaceAll("[^0-9]", "");

        if (cleanDate.length() < 8) {
            return dateStr; // Return original if can't parse
        }

        try {
            Date date;
            // Try MMDDYYYY format first (most common)
            if (cleanDate.length() >= 8) {
                date = aamvaDateFormat.parse(cleanDate.substring(0, 8));
                if (date != null) {
                    return isoDateFormat.format(date);
                }
            }
        } catch (ParseException e) {
            // Try alternative format
        }

        try {
            // Try YYYYMMDD format
            Date date = aamvaDateFormatAlt.parse(cleanDate.substring(0, 8));
            if (date != null) {
                return isoDateFormat.format(date);
            }
        } catch (ParseException e) {
            Log.w(TAG, "Could not parse date: " + dateStr);
        }

        return dateStr;
    }

    /**
     * Checks if the license is expired based on expiration date.
     */
    private boolean isExpired(String expirationDateStr) {
        String isoDate = parseDate(expirationDateStr);
        if (isoDate.isEmpty() || isoDate.equals(expirationDateStr)) {
            return false; // Can't determine, assume not expired
        }

        try {
            Date expiration = isoDateFormat.parse(isoDate);
            return expiration != null && expiration.before(new Date());
        } catch (ParseException e) {
            return false;
        }
    }

    /**
     * Parses gender code to human-readable string.
     */
    private String parseGender(String code) {
        if (code == null || code.isEmpty()) {
            return "Unknown";
        }

        switch (code.trim()) {
            case "1":
                return "Male";
            case "2":
                return "Female";
            case "9":
            case "0":
                return "Not Specified";
            case "M":
                return "Male";
            case "F":
                return "Female";
            default:
                return code;
        }
    }

    /**
     * Formats postal code to standard format.
     */
    private String formatPostalCode(String postal) {
        if (postal == null || postal.isEmpty()) {
            return "";
        }

        // Remove any spaces and standardize
        String cleaned = postal.replaceAll("\\s+", "").replaceAll("[^0-9]", "");

        // Format as XXXXX or XXXXX-XXXX
        if (cleaned.length() >= 9) {
            return cleaned.substring(0, 5) + "-" + cleaned.substring(5, 9);
        } else if (cleaned.length() >= 5) {
            return cleaned.substring(0, 5);
        }

        return postal;
    }

    /**
     * Parses height to human-readable format.
     */
    private String parseHeight(String height) {
        if (height == null || height.isEmpty()) {
            return "";
        }

        // Format is typically: FT-IN (e.g., "510" for 5'10") or metric
        String cleaned = height.replaceAll("[^0-9]", "");

        if (cleaned.length() == 3) {
            // US format: first digit is feet, rest is inches
            return cleaned.charAt(0) + "'" + cleaned.substring(1) + "\"";
        } else if (cleaned.length() == 4) {
            // Could be centimeters
            return cleaned + " cm";
        }

        return height;
    }

    /**
     * Parses eye color code to human-readable string.
     */
    private String parseEyeColor(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }

        switch (code.toUpperCase()) {
            case "BLK":
                return "Black";
            case "BLU":
                return "Blue";
            case "BRO":
                return "Brown";
            case "GRY":
                return "Gray";
            case "GRN":
                return "Green";
            case "HAZ":
                return "Hazel";
            case "MAR":
                return "Maroon";
            case "PNK":
                return "Pink";
            case "DIC":
                return "Dichromatic";
            case "UNK":
                return "Unknown";
            default:
                return code;
        }
    }

    /**
     * Parses hair color code to human-readable string.
     */
    private String parseHairColor(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }

        switch (code.toUpperCase()) {
            case "BAL":
                return "Bald";
            case "BLK":
                return "Black";
            case "BLN":
                return "Blonde";
            case "BRO":
                return "Brown";
            case "GRY":
                return "Gray";
            case "RED":
                return "Red";
            case "SDY":
                return "Sandy";
            case "WHI":
                return "White";
            case "UNK":
                return "Unknown";
            default:
                return code;
        }
    }

    /**
     * Checks if a field code is a standard processed field.
     */
    private boolean isStandardField(String code) {
        return code.equals(FIELD_FAMILY_NAME) ||
                code.equals(FIELD_FIRST_NAME) ||
                code.equals(FIELD_MIDDLE_NAME) ||
                code.equals(FIELD_DATE_OF_BIRTH) ||
                code.equals(FIELD_EXPIRATION_DATE) ||
                code.equals(FIELD_ISSUE_DATE) ||
                code.equals(FIELD_SEX) ||
                code.equals(FIELD_LICENSE_NUMBER) ||
                code.equals(FIELD_STREET_ADDRESS) ||
                code.equals(FIELD_STREET_ADDRESS_2) ||
                code.equals(FIELD_CITY) ||
                code.equals(FIELD_STATE) ||
                code.equals(FIELD_POSTAL_CODE) ||
                code.equals(FIELD_DOCUMENT_DISCRIMINATOR) ||
                code.equals(FIELD_COUNTRY) ||
                code.equals(FIELD_HEIGHT) ||
                code.equals(FIELD_EYE_COLOR) ||
                code.equals(FIELD_HAIR_COLOR) ||
                code.equals(FIELD_WEIGHT_LBS) ||
                code.equals(FIELD_WEIGHT_KG) ||
                code.equals(FIELD_NAME_SUFFIX) ||
                code.equals(FIELD_NAME_PREFIX);
    }

    /**
     * Creates an error result JSON object.
     */
    private JSONObject createErrorResult(String errorMessage) {
        JSONObject result = new JSONObject();
        try {
            result.put("isValid", false);
            result.put("error", errorMessage);
            result.put("firstName", "");
            result.put("lastName", "");
            result.put("middleName", "");
            result.put("fullName", "");
            result.put("dateOfBirth", "");
            result.put("licenseNumber", "");
        } catch (JSONException e) {
            Log.e(TAG, "Error creating error result", e);
        }
        return result;
    }
}
