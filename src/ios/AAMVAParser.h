/**
 * AAMVAParser.h
 *
 * Parses AAMVA-compliant driver license data from PDF417 barcodes.
 * Supports AAMVA versions 1-10 and handles jurisdiction-specific variations.
 */

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * Parser for AAMVA driver license barcode data.
 */
@interface AAMVAParser : NSObject

/**
 * Parses raw AAMVA barcode data into a structured dictionary.
 *
 * @param rawData The raw barcode data string
 * @return Dictionary containing parsed fields, or nil if parsing fails
 */
- (nullable NSDictionary *)parseRawData:(NSString *)rawData;

@end

NS_ASSUME_NONNULL_END
