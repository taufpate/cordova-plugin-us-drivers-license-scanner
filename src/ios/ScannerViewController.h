/**
 * ScannerViewController.h
 *
 * View controller that handles the guided driver license scanning flow.
 * Implements front scan, flip instruction, and back scan steps.
 */

#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

@class ScannerViewController;

/**
 * Delegate protocol for scanner view controller events.
 */
@protocol ScannerViewControllerDelegate <NSObject>

/**
 * Called when scanning completes successfully.
 *
 * @param controller The scanner view controller
 * @param result Dictionary containing scan results
 */
- (void)scannerViewController:(ScannerViewController *)controller
         didFinishWithResult:(NSDictionary *)result;

/**
 * Called when scanning fails with an error.
 *
 * @param controller The scanner view controller
 * @param errorCode Error code string
 * @param errorMessage Human-readable error message
 */
- (void)scannerViewController:(ScannerViewController *)controller
           didFailWithError:(NSString *)errorCode
                    message:(NSString *)errorMessage;

/**
 * Called when the user cancels the scan.
 *
 * @param controller The scanner view controller
 */
- (void)scannerViewControllerDidCancel:(ScannerViewController *)controller;

@end

/**
 * Scan flow states.
 */
typedef NS_ENUM(NSInteger, ScanState) {
    ScanStateScanningFront,
    ScanStateFlipInstruction,
    ScanStateScanningBack,
    ScanStateProcessing,
    ScanStateCompleted,
    ScanStateError
};

/**
 * Scanner view controller implementing the guided scan flow for driver licenses.
 */
@interface ScannerViewController : UIViewController

/** Delegate for scan events */
@property (nonatomic, weak, nullable) id<ScannerViewControllerDelegate> delegate;

/** Scan options passed from plugin */
@property (nonatomic, strong, nullable) NSDictionary *options;

/** Current scan state */
@property (nonatomic, readonly) ScanState currentState;

@end

NS_ASSUME_NONNULL_END
