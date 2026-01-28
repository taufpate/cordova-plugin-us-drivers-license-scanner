/**
 * AAMVAParser.m
 *
 * AAMVA driver license data parser implementation.
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
 */

#import "AAMVAParser.h"

// Field code constants
static NSString *const kFieldFamilyName = @"DCS";
static NSString *const kFieldFirstName = @"DAC";
static NSString *const kFieldMiddleName = @"DAD";
static NSString *const kFieldDateOfBirth = @"DBB";
static NSString *const kFieldExpirationDate = @"DBA";
static NSString *const kFieldIssueDate = @"DBD";
static NSString *const kFieldSex = @"DBC";
static NSString *const kFieldLicenseNumber = @"DAQ";
static NSString *const kFieldStreetAddress = @"DAG";
static NSString *const kFieldStreetAddress2 = @"DAH";
static NSString *const kFieldCity = @"DAI";
static NSString *const kFieldState = @"DAJ";
static NSString *const kFieldPostalCode = @"DAK";
static NSString *const kFieldDocumentDiscriminator = @"DCF";
static NSString *const kFieldCountry = @"DCG";
static NSString *const kFieldHeight = @"DAU";
static NSString *const kFieldEyeColor = @"DAY";
static NSString *const kFieldHairColor = @"DAZ";
static NSString *const kFieldWeightLbs = @"DAW";
static NSString *const kFieldWeightKg = @"DAX";
static NSString *const kFieldNameSuffix = @"DCU";
static NSString *const kFieldNamePrefix = @"DAA";
static NSString *const kFieldFullName = @"DAA";

@interface AAMVAParser ()

@property (nonatomic, strong) NSDateFormatter *aamvaDateFormatter;
@property (nonatomic, strong) NSDateFormatter *aamvaDateFormatterAlt;
@property (nonatomic, strong) NSDateFormatter *isoDateFormatter;

@end

@implementation AAMVAParser

#pragma mark - Initialization

- (instancetype)init {
    self = [super init];
    if (self) {
        // MMDDYYYY format
        _aamvaDateFormatter = [[NSDateFormatter alloc] init];
        _aamvaDateFormatter.dateFormat = @"MMddyyyy";
        _aamvaDateFormatter.locale = [[NSLocale alloc] initWithLocaleIdentifier:@"en_US_POSIX"];

        // YYYYMMDD format
        _aamvaDateFormatterAlt = [[NSDateFormatter alloc] init];
        _aamvaDateFormatterAlt.dateFormat = @"yyyyMMdd";
        _aamvaDateFormatterAlt.locale = [[NSLocale alloc] initWithLocaleIdentifier:@"en_US_POSIX"];

        // ISO format for output
        _isoDateFormatter = [[NSDateFormatter alloc] init];
        _isoDateFormatter.dateFormat = @"yyyy-MM-dd";
        _isoDateFormatter.locale = [[NSLocale alloc] initWithLocaleIdentifier:@"en_US_POSIX"];
    }
    return self;
}

#pragma mark - Public Methods

- (NSDictionary *)parseRawData:(NSString *)rawData {
    if (!rawData || rawData.length == 0) {
        NSLog(@"[AAMVAParser] Empty raw data provided");
        return [self createErrorResult:@"No data provided"];
    }

    @try {
        // Normalize the data
        NSString *normalized = [self normalizeData:rawData];

        // Extract all field values
        NSDictionary *fields = [self extractFields:normalized];

        if (fields.count == 0) {
            NSLog(@"[AAMVAParser] No fields extracted from data");
            return [self createErrorResult:@"Could not extract any fields from barcode data"];
        }

        // Extract version info
        NSDictionary *versionInfo = [self extractVersion:normalized];

        // Build result dictionary
        return [self buildResultFromFields:fields versionInfo:versionInfo rawData:rawData];

    } @catch (NSException *exception) {
        NSLog(@"[AAMVAParser] Exception during parsing: %@", exception);
        return [self createErrorResult:[NSString stringWithFormat:@"Parse error: %@", exception.reason]];
    }
}

#pragma mark - Data Normalization

- (NSString *)normalizeData:(NSString *)data {
    // Replace various line endings with standard newline
    NSString *normalized = [data stringByReplacingOccurrencesOfString:@"\r\n" withString:@"\n"];
    normalized = [normalized stringByReplacingOccurrencesOfString:@"\r" withString:@"\n"];

    // Trim whitespace from each line
    NSMutableArray *lines = [NSMutableArray array];
    for (NSString *line in [normalized componentsSeparatedByString:@"\n"]) {
        [lines addObject:[line stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]]];
    }

    return [lines componentsJoinedByString:@"\n"];
}

#pragma mark - Field Extraction

- (NSDictionary *)extractFields:(NSString *)data {
    NSMutableDictionary *fields = [NSMutableDictionary dictionary];

    // Split by newlines
    NSArray *lines = [data componentsSeparatedByString:@"\n"];

    for (NSString *line in lines) {
        if (line.length < 3) {
            continue;
        }

        NSString *code = [[line substringToIndex:3] uppercaseString];
        NSString *value = line.length > 3 ? [[line substringFromIndex:3] stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]] : @"";

        // Only store if it looks like a valid field code (all uppercase letters)
        NSCharacterSet *uppercaseLetters = [NSCharacterSet uppercaseLetterCharacterSet];
        if ([[code stringByTrimmingCharactersInSet:uppercaseLetters] length] == 0) {
            fields[code] = value;
        }
    }

    // Also try inline format (no newlines between fields)
    if (fields.count < 3) {
        [self extractInlineFields:data intoFields:fields];
    }

    return fields;
}

- (void)extractInlineFields:(NSString *)data intoFields:(NSMutableDictionary *)fields {
    NSArray *knownCodes = @[
        @"DCS", @"DAC", @"DAD", @"DBB", @"DBA", @"DBD", @"DBC", @"DAQ",
        @"DAG", @"DAH", @"DAI", @"DAJ", @"DAK", @"DCF", @"DCG", @"DAU",
        @"DAY", @"DAZ", @"DAW", @"DAX", @"DCU", @"DAA", @"DDE", @"DDF",
        @"DDG", @"DCL", @"DDA", @"DDB", @"DDD", @"DDK", @"DDL"
    ];

    for (NSString *code in knownCodes) {
        NSRange codeRange = [data rangeOfString:code];
        if (codeRange.location != NSNotFound) {
            NSUInteger valueStart = codeRange.location + 3;
            NSUInteger valueEnd = data.length;

            // Find end of this field
            for (NSString *nextCode in knownCodes) {
                NSRange nextRange = [data rangeOfString:nextCode
                                                options:0
                                                  range:NSMakeRange(valueStart, data.length - valueStart)];
                if (nextRange.location != NSNotFound && nextRange.location < valueEnd) {
                    valueEnd = nextRange.location;
                }
            }

            NSString *value = [data substringWithRange:NSMakeRange(valueStart, valueEnd - valueStart)];

            // Remove control characters
            NSCharacterSet *controlChars = [NSCharacterSet controlCharacterSet];
            value = [[value componentsSeparatedByCharactersInSet:controlChars] componentsJoinedByString:@""];
            value = [value stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];

            if (value.length > 0 && !fields[code]) {
                fields[code] = value;
            }
        }
    }
}

#pragma mark - Version Extraction

- (NSDictionary *)extractVersion:(NSString *)data {
    NSError *error = nil;
    NSRegularExpression *regex = [NSRegularExpression regularExpressionWithPattern:@"ANSI\\s*(\\d{6})(\\d{2})(\\d{2})"
                                                                           options:0
                                                                             error:&error];

    NSTextCheckingResult *match = [regex firstMatchInString:data options:0 range:NSMakeRange(0, data.length)];

    if (match && match.numberOfRanges >= 4) {
        NSString *jurisdictionCode = [data substringWithRange:[match rangeAtIndex:1]];
        NSString *aamvaVersion = [data substringWithRange:[match rangeAtIndex:2]];
        NSString *jurisdictionVersion = [data substringWithRange:[match rangeAtIndex:3]];

        return @{
            @"jurisdictionCode": jurisdictionCode,
            @"aamvaVersion": aamvaVersion,
            @"jurisdictionVersion": jurisdictionVersion
        };
    }

    return @{
        @"jurisdictionCode": @"0",
        @"aamvaVersion": @"0",
        @"jurisdictionVersion": @"0"
    };
}

#pragma mark - Result Building

- (NSDictionary *)buildResultFromFields:(NSDictionary *)fields
                            versionInfo:(NSDictionary *)versionInfo
                                rawData:(NSString *)rawData {

    NSMutableDictionary *result = [NSMutableDictionary dictionary];

    // Names
    NSString *lastName = [self getField:kFieldFamilyName fromFields:fields];
    NSString *firstName = [self getField:kFieldFirstName fromFields:fields];
    NSString *middleName = [self getField:kFieldMiddleName fromFields:fields];

    // Handle full name field
    if ((lastName.length == 0 || firstName.length == 0) && fields[kFieldFullName]) {
        NSArray *nameParts = [self parseFullName:fields[kFieldFullName]];
        if (lastName.length == 0) lastName = nameParts[0];
        if (firstName.length == 0) firstName = nameParts[1];
        if (middleName.length == 0) middleName = nameParts[2];
    }

    result[@"lastName"] = lastName;
    result[@"firstName"] = firstName;
    result[@"middleName"] = middleName;
    result[@"fullName"] = [self buildFullNameFrom:firstName middle:middleName last:lastName];

    // Dates
    NSString *dobRaw = [self getField:kFieldDateOfBirth fromFields:fields];
    NSString *expirationRaw = [self getField:kFieldExpirationDate fromFields:fields];
    NSString *issueRaw = [self getField:kFieldIssueDate fromFields:fields];

    result[@"dateOfBirthRaw"] = dobRaw;
    result[@"dateOfBirth"] = [self parseDate:dobRaw];
    result[@"expirationDateRaw"] = expirationRaw;
    result[@"expirationDate"] = [self parseDate:expirationRaw];
    result[@"issueDateRaw"] = issueRaw;
    result[@"issueDate"] = [self parseDate:issueRaw];
    result[@"isExpired"] = @([self isExpired:expirationRaw]);

    // Address
    NSString *street = [self getField:kFieldStreetAddress fromFields:fields];
    NSString *street2 = [self getField:kFieldStreetAddress2 fromFields:fields];
    NSString *city = [self getField:kFieldCity fromFields:fields];
    NSString *state = [self getField:kFieldState fromFields:fields];
    NSString *postalCode = [self formatPostalCode:[self getField:kFieldPostalCode fromFields:fields]];

    result[@"streetAddress"] = street;
    result[@"streetAddress2"] = street2;
    result[@"city"] = city;
    result[@"state"] = state;
    result[@"postalCode"] = postalCode;
    result[@"fullAddress"] = [self buildFullAddressFrom:street street2:street2 city:city state:state postal:postalCode];

    // License info
    result[@"licenseNumber"] = [self getField:kFieldLicenseNumber fromFields:fields];
    result[@"documentDiscriminator"] = [self getField:kFieldDocumentDiscriminator fromFields:fields];

    // Gender
    NSString *genderCode = [self getField:kFieldSex fromFields:fields];
    result[@"genderCode"] = genderCode;
    result[@"gender"] = [self parseGender:genderCode];

    // Country and issuing state
    NSString *country = [self getField:kFieldCountry fromFields:fields];
    result[@"issuingCountry"] = country.length > 0 ? country : @"USA";
    result[@"issuingState"] = state;

    // Physical characteristics
    result[@"height"] = [self parseHeight:[self getField:kFieldHeight fromFields:fields]];
    result[@"eyeColor"] = [self parseEyeColor:[self getField:kFieldEyeColor fromFields:fields]];
    result[@"hairColor"] = [self parseHairColor:[self getField:kFieldHairColor fromFields:fields]];

    NSString *weightLbs = [self getField:kFieldWeightLbs fromFields:fields];
    NSString *weightKg = [self getField:kFieldWeightKg fromFields:fields];
    result[@"weight"] = weightLbs.length > 0 ? weightLbs : weightKg;

    // Name suffix/prefix
    result[@"nameSuffix"] = [self getField:kFieldNameSuffix fromFields:fields];
    result[@"namePrefix"] = [self getField:kFieldNamePrefix fromFields:fields];

    // Version info
    result[@"aamvaVersion"] = versionInfo[@"aamvaVersion"];
    result[@"jurisdictionVersion"] = versionInfo[@"jurisdictionVersion"];
    result[@"jurisdictionCode"] = versionInfo[@"jurisdictionCode"];

    // Additional fields
    NSMutableDictionary *additionalFields = [NSMutableDictionary dictionary];
    for (NSString *code in fields) {
        if (![self isStandardField:code]) {
            additionalFields[code] = fields[code];
        }
    }
    result[@"additionalFields"] = additionalFields;

    // Validation
    BOOL isValid = lastName.length > 0 && firstName.length > 0 &&
                   [self getField:kFieldLicenseNumber fromFields:fields].length > 0;
    result[@"isValid"] = @(isValid);

    return result;
}

#pragma mark - Helper Methods

- (NSString *)getField:(NSString *)code fromFields:(NSDictionary *)fields {
    NSString *value = fields[code];
    return value ? [value stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]] : @"";
}

- (NSArray *)parseFullName:(NSString *)fullName {
    NSString *lastName = @"";
    NSString *firstName = @"";
    NSString *middleName = @"";

    if (!fullName || fullName.length == 0) {
        return @[lastName, firstName, middleName];
    }

    if ([fullName containsString:@","]) {
        NSArray *parts = [fullName componentsSeparatedByString:@","];
        if (parts.count >= 1) lastName = [parts[0] stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
        if (parts.count >= 2) firstName = [parts[1] stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
        if (parts.count >= 3) middleName = [parts[2] stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
    } else {
        NSArray *parts = [[fullName stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]]
                          componentsSeparatedByCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];
        if (parts.count >= 1) lastName = parts[0];
        if (parts.count >= 2) firstName = parts[1];
        if (parts.count >= 3) middleName = parts[2];
    }

    return @[lastName, firstName, middleName];
}

- (NSString *)buildFullNameFrom:(NSString *)first middle:(NSString *)middle last:(NSString *)last {
    NSMutableArray *parts = [NSMutableArray array];
    if (first.length > 0) [parts addObject:first];
    if (middle.length > 0) [parts addObject:middle];
    if (last.length > 0) [parts addObject:last];
    return [parts componentsJoinedByString:@" "];
}

- (NSString *)buildFullAddressFrom:(NSString *)street street2:(NSString *)street2
                              city:(NSString *)city state:(NSString *)state postal:(NSString *)postal {
    NSMutableArray *parts = [NSMutableArray array];

    if (street.length > 0) [parts addObject:street];
    if (street2.length > 0) [parts addObject:street2];

    NSMutableString *cityStatePart = [NSMutableString string];
    if (city.length > 0) [cityStatePart appendString:city];
    if (state.length > 0) {
        if (cityStatePart.length > 0) [cityStatePart appendString:@", "];
        [cityStatePart appendString:state];
    }
    if (postal.length > 0) {
        if (cityStatePart.length > 0) [cityStatePart appendString:@" "];
        [cityStatePart appendString:postal];
    }

    if (cityStatePart.length > 0) [parts addObject:cityStatePart];

    return [parts componentsJoinedByString:@", "];
}

- (NSString *)parseDate:(NSString *)dateStr {
    if (!dateStr || dateStr.length == 0) {
        return @"";
    }

    // Remove non-numeric characters
    NSCharacterSet *nonDigits = [[NSCharacterSet decimalDigitCharacterSet] invertedSet];
    NSString *cleanDate = [[dateStr componentsSeparatedByCharactersInSet:nonDigits] componentsJoinedByString:@""];

    if (cleanDate.length < 8) {
        return dateStr;
    }

    cleanDate = [cleanDate substringToIndex:8];

    // Try MMDDYYYY format
    NSDate *date = [self.aamvaDateFormatter dateFromString:cleanDate];
    if (date) {
        return [self.isoDateFormatter stringFromDate:date];
    }

    // Try YYYYMMDD format
    date = [self.aamvaDateFormatterAlt dateFromString:cleanDate];
    if (date) {
        return [self.isoDateFormatter stringFromDate:date];
    }

    return dateStr;
}

- (BOOL)isExpired:(NSString *)expirationDateStr {
    NSString *isoDate = [self parseDate:expirationDateStr];
    if (isoDate.length == 0 || [isoDate isEqualToString:expirationDateStr]) {
        return NO;
    }

    NSDate *expiration = [self.isoDateFormatter dateFromString:isoDate];
    return expiration && [expiration compare:[NSDate date]] == NSOrderedAscending;
}

- (NSString *)parseGender:(NSString *)code {
    if (!code || code.length == 0) {
        return @"Unknown";
    }

    NSString *trimmed = [code stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceCharacterSet]];

    if ([trimmed isEqualToString:@"1"] || [trimmed isEqualToString:@"M"]) return @"Male";
    if ([trimmed isEqualToString:@"2"] || [trimmed isEqualToString:@"F"]) return @"Female";
    if ([trimmed isEqualToString:@"9"] || [trimmed isEqualToString:@"0"]) return @"Not Specified";

    return code;
}

- (NSString *)formatPostalCode:(NSString *)postal {
    if (!postal || postal.length == 0) {
        return @"";
    }

    NSCharacterSet *nonDigits = [[NSCharacterSet decimalDigitCharacterSet] invertedSet];
    NSString *cleaned = [[postal componentsSeparatedByCharactersInSet:nonDigits] componentsJoinedByString:@""];

    if (cleaned.length >= 9) {
        return [NSString stringWithFormat:@"%@-%@",
                [cleaned substringToIndex:5],
                [cleaned substringWithRange:NSMakeRange(5, 4)]];
    } else if (cleaned.length >= 5) {
        return [cleaned substringToIndex:5];
    }

    return postal;
}

- (NSString *)parseHeight:(NSString *)height {
    if (!height || height.length == 0) {
        return @"";
    }

    NSCharacterSet *nonDigits = [[NSCharacterSet decimalDigitCharacterSet] invertedSet];
    NSString *cleaned = [[height componentsSeparatedByCharactersInSet:nonDigits] componentsJoinedByString:@""];

    if (cleaned.length == 3) {
        // US format: first digit is feet
        return [NSString stringWithFormat:@"%c'%@\"",
                [cleaned characterAtIndex:0],
                [cleaned substringFromIndex:1]];
    } else if (cleaned.length == 4) {
        return [NSString stringWithFormat:@"%@ cm", cleaned];
    }

    return height;
}

- (NSString *)parseEyeColor:(NSString *)code {
    if (!code || code.length == 0) return @"";

    NSDictionary *colors = @{
        @"BLK": @"Black",
        @"BLU": @"Blue",
        @"BRO": @"Brown",
        @"GRY": @"Gray",
        @"GRN": @"Green",
        @"HAZ": @"Hazel",
        @"MAR": @"Maroon",
        @"PNK": @"Pink",
        @"DIC": @"Dichromatic",
        @"UNK": @"Unknown"
    };

    return colors[[code uppercaseString]] ?: code;
}

- (NSString *)parseHairColor:(NSString *)code {
    if (!code || code.length == 0) return @"";

    NSDictionary *colors = @{
        @"BAL": @"Bald",
        @"BLK": @"Black",
        @"BLN": @"Blonde",
        @"BRO": @"Brown",
        @"GRY": @"Gray",
        @"RED": @"Red",
        @"SDY": @"Sandy",
        @"WHI": @"White",
        @"UNK": @"Unknown"
    };

    return colors[[code uppercaseString]] ?: code;
}

- (BOOL)isStandardField:(NSString *)code {
    NSSet *standardFields = [NSSet setWithArray:@[
        kFieldFamilyName, kFieldFirstName, kFieldMiddleName,
        kFieldDateOfBirth, kFieldExpirationDate, kFieldIssueDate,
        kFieldSex, kFieldLicenseNumber, kFieldStreetAddress,
        kFieldStreetAddress2, kFieldCity, kFieldState, kFieldPostalCode,
        kFieldDocumentDiscriminator, kFieldCountry, kFieldHeight,
        kFieldEyeColor, kFieldHairColor, kFieldWeightLbs, kFieldWeightKg,
        kFieldNameSuffix, kFieldNamePrefix
    ]];

    return [standardFields containsObject:code];
}

- (NSDictionary *)createErrorResult:(NSString *)errorMessage {
    return @{
        @"isValid": @NO,
        @"error": errorMessage,
        @"firstName": @"",
        @"lastName": @"",
        @"middleName": @"",
        @"fullName": @"",
        @"dateOfBirth": @"",
        @"licenseNumber": @""
    };
}

@end
