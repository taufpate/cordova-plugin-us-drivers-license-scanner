/**
 * ScanResult.h
 *
 * Data class representing the result of a driver license scan.
 */

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Encapsulates all data from a driver license scan operation.
 */
@interface ScanResult : NSObject

/** Raw barcode data from front scan */
@property (nonatomic, strong, nullable) NSString *frontRawData;

/** Raw barcode data from back scan */
@property (nonatomic, strong, nullable) NSString *backRawData;

/** Parsed AAMVA fields */
@property (nonatomic, strong, nullable) NSDictionary *parsedFields;

/** Captured front image */
@property (nonatomic, strong, nullable) UIImage *frontImage;

/** Captured back image */
@property (nonatomic, strong, nullable) UIImage *backImage;

/** Extracted portrait image */
@property (nonatomic, strong, nullable) UIImage *portraitImage;

/** Error code if scan failed */
@property (nonatomic, strong, nullable) NSString *errorCode;

/** Error message if scan failed */
@property (nonatomic, strong, nullable) NSString *errorMessage;

/** Whether the scan was successful */
@property (nonatomic, assign) BOOL isSuccess;

/**
 * Creates an error scan result.
 *
 * @param errorCode Error code
 * @param errorMessage Human-readable error message
 * @return Error scan result instance
 */
+ (instancetype)resultWithError:(NSString *)errorCode message:(NSString *)errorMessage;

/**
 * Converts this scan result to a dictionary for returning to JavaScript.
 *
 * @param includeFullImages Whether to include full front/back images
 * @param imageQuality JPEG quality for image encoding (0-100)
 * @return Dictionary representation of the scan result
 */
- (NSDictionary *)toDictionaryWithFullImages:(BOOL)includeFullImages quality:(NSInteger)imageQuality;

@end

NS_ASSUME_NONNULL_END
