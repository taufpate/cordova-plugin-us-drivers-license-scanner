/**
 * ScanResult.m
 *
 * Scan result data class implementation.
 */

#import "ScanResult.h"
#import "ImageUtils.h"

@implementation ScanResult

#pragma mark - Initialization

- (instancetype)init {
    self = [super init];
    if (self) {
        _isSuccess = YES;
    }
    return self;
}

+ (instancetype)resultWithError:(NSString *)errorCode message:(NSString *)errorMessage {
    ScanResult *result = [[ScanResult alloc] init];
    result.isSuccess = NO;
    result.errorCode = errorCode;
    result.errorMessage = errorMessage;
    return result;
}

#pragma mark - Conversion

- (NSDictionary *)toDictionaryWithFullImages:(BOOL)includeFullImages quality:(NSInteger)imageQuality {
    NSMutableDictionary *dict = [NSMutableDictionary dictionary];

    if (!self.isSuccess) {
        dict[@"success"] = @NO;
        dict[@"errorCode"] = self.errorCode ?: @"UNKNOWN_ERROR";
        dict[@"errorMessage"] = self.errorMessage ?: @"Unknown error";
        return dict;
    }

    dict[@"success"] = @YES;

    // Raw data
    dict[@"frontRawData"] = self.frontRawData ?: [NSNull null];
    dict[@"backRawData"] = self.backRawData ?: [NSNull null];

    // Parsed fields
    dict[@"parsedFields"] = self.parsedFields ?: @{};

    // Portrait image
    if (self.portraitImage) {
        dict[@"portraitImageBase64"] = [ImageUtils imageToBase64:self.portraitImage
                                                          format:@"JPEG"
                                                         quality:imageQuality];
    } else {
        dict[@"portraitImageBase64"] = @"";
    }

    // Full images
    if (includeFullImages) {
        if (self.frontImage) {
            dict[@"fullFrontImageBase64"] = [ImageUtils imageToBase64:self.frontImage
                                                               format:@"JPEG"
                                                              quality:imageQuality];
        }
        if (self.backImage) {
            dict[@"fullBackImageBase64"] = [ImageUtils imageToBase64:self.backImage
                                                              format:@"JPEG"
                                                             quality:imageQuality];
        }
    }

    return dict;
}

#pragma mark - Description

- (NSString *)description {
    if (!self.isSuccess) {
        return [NSString stringWithFormat:@"ScanResult[error=%@: %@]", self.errorCode, self.errorMessage];
    }

    NSMutableString *desc = [NSMutableString stringWithString:@"ScanResult[success"];

    if (self.backRawData) {
        [desc appendFormat:@", barcodeLen=%lu", (unsigned long)self.backRawData.length];
    }

    if (self.parsedFields) {
        NSString *firstName = self.parsedFields[@"firstName"] ?: @"";
        NSString *lastName = self.parsedFields[@"lastName"] ?: @"";
        [desc appendFormat:@", name=%@ %@", firstName, lastName];
    }

    [desc appendFormat:@", portrait=%@", self.portraitImage ? @"YES" : @"NO"];
    [desc appendString:@"]"];

    return desc;
}

@end
