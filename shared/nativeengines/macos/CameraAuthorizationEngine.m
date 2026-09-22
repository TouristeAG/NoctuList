#import <AVFoundation/AVFoundation.h>
#import <CoreVideo/CoreVideo.h>
#import <Foundation/Foundation.h>
#include <string.h>

enum {
    NLCameraAccessAuthorized = 0,
    NLCameraAccessDenied = 1,
    NLCameraAccessSkipped = 2
};

@interface NLCameraFrameHandler : NSObject <AVCaptureVideoDataOutputSampleBufferDelegate>
@end

static NSLock *NLFrameLock(void) {
    static NSLock *lock;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        lock = [[NSLock alloc] init];
    });
    return lock;
}

static NLCameraFrameHandler *gHandler;
static AVCaptureSession *gSession;
static dispatch_queue_t gVideoQueue;
static NSMutableData *gFrameBgr;
static int gFrameWidth;
static int gFrameHeight;
static BOOL gHasNewFrame;

static BOOL NLHasCameraUsageDescription(void) {
    id usage = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"NSCameraUsageDescription"];
    return [usage isKindOfClass:[NSString class]] && [(NSString *)usage length] > 0;
}

static int NLMapAuthorizationStatus(AVAuthorizationStatus status) {
    switch (status) {
        case AVAuthorizationStatusAuthorized:
            return NLCameraAccessAuthorized;
        case AVAuthorizationStatusDenied:
        case AVAuthorizationStatusRestricted:
            return NLCameraAccessDenied;
        case AVAuthorizationStatusNotDetermined:
        default:
            return NLCameraAccessSkipped;
    }
}

static void NLWaitUntil(BOOL *done, NSTimeInterval seconds) {
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:seconds];
    while (!(*done) && [deadline timeIntervalSinceNow] > 0) {
        if ([NSThread isMainThread]) {
            [[NSRunLoop mainRunLoop] runMode:NSDefaultRunLoopMode
                                  beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
        } else {
            [NSThread sleepForTimeInterval:0.05];
        }
    }
}

int cameraAuthorizationStatus(void) {
    if (@available(macOS 10.14, *)) {
        return NLMapAuthorizationStatus([AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo]);
    }
    return NLCameraAccessAuthorized;
}

int requestCameraAccess(void) {
    if (@available(macOS 10.14, *)) {
        AVAuthorizationStatus status = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];
        if (status == AVAuthorizationStatusAuthorized) {
            return NLCameraAccessAuthorized;
        }
        if (status == AVAuthorizationStatusDenied || status == AVAuthorizationStatusRestricted) {
            return NLCameraAccessDenied;
        }
        if (!NLHasCameraUsageDescription()) {
            // Calling requestAccess without NSCameraUsageDescription terminates the process.
            return NLCameraAccessSkipped;
        }

        __block BOOL done = NO;
        __block BOOL granted = NO;
        [AVCaptureDevice requestAccessForMediaType:AVMediaTypeVideo completionHandler:^(BOOL ok) {
            granted = ok;
            done = YES;
        }];
        NLWaitUntil(&done, 120.0);
        if (!done) {
            return cameraAuthorizationStatus();
        }
        return granted ? NLCameraAccessAuthorized : NLCameraAccessDenied;
    }
    return NLCameraAccessAuthorized;
}

static NSArray<AVCaptureDevice *> *NLVideoDevices(void) {
    NSMutableArray<AVCaptureDeviceType> *types = [NSMutableArray array];
    [types addObject:AVCaptureDeviceTypeBuiltInWideAngleCamera];
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    [types addObject:AVCaptureDeviceTypeExternalUnknown];
#pragma clang diagnostic pop
#if defined(__MAC_OS_X_VERSION_MAX_ALLOWED) && __MAC_OS_X_VERSION_MAX_ALLOWED >= 140000
    if (@available(macOS 14.0, *)) {
        [types addObject:AVCaptureDeviceTypeContinuityCamera];
        [types addObject:AVCaptureDeviceTypeExternal];
    }
#endif
    AVCaptureDeviceDiscoverySession *session =
        [AVCaptureDeviceDiscoverySession discoverySessionWithDeviceTypes:types
                                                              mediaType:AVMediaTypeVideo
                                                               position:AVCaptureDevicePositionUnspecified];
    return session.devices ?: @[];
}

int cameraDeviceCount(void) {
    NSArray<AVCaptureDevice *> *devices = NLVideoDevices();
    if (devices.count > 0) {
        return (int)devices.count;
    }
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    NSArray<AVCaptureDevice *> *legacy = [AVCaptureDevice devicesWithMediaType:AVMediaTypeVideo];
#pragma clang diagnostic pop
    return (int)legacy.count;
}

@implementation NLCameraFrameHandler
- (void)captureOutput:(AVCaptureOutput *)output
didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
       fromConnection:(AVCaptureConnection *)connection
{
    CVImageBufferRef imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);
    if (imageBuffer == NULL) {
        return;
    }
    CVPixelBufferLockBaseAddress(imageBuffer, kCVPixelBufferLock_ReadOnly);
    size_t width = CVPixelBufferGetWidth(imageBuffer);
    size_t height = CVPixelBufferGetHeight(imageBuffer);
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(imageBuffer);
    uint8_t *src = (uint8_t *)CVPixelBufferGetBaseAddress(imageBuffer);
    if (src == NULL || width == 0 || height == 0) {
        CVPixelBufferUnlockBaseAddress(imageBuffer, kCVPixelBufferLock_ReadOnly);
        return;
    }

    size_t packed = width * 3;
    NSMutableData *bgr = [NSMutableData dataWithLength:packed * height];
    uint8_t *dst = (uint8_t *)bgr.mutableBytes;
    for (size_t y = 0; y < height; y++) {
        uint8_t *row = src + (y * bytesPerRow);
        uint8_t *outRow = dst + (y * packed);
        for (size_t x = 0; x < width; x++) {
            // BGRA → BGR
            outRow[x * 3 + 0] = row[x * 4 + 0];
            outRow[x * 3 + 1] = row[x * 4 + 1];
            outRow[x * 3 + 2] = row[x * 4 + 2];
        }
    }
    CVPixelBufferUnlockBaseAddress(imageBuffer, kCVPixelBufferLock_ReadOnly);

    [NLFrameLock() lock];
    gFrameBgr = bgr;
    gFrameWidth = (int)width;
    gFrameHeight = (int)height;
    gHasNewFrame = YES;
    [NLFrameLock() unlock];
}
@end

static AVCaptureDevice *NLPickDevice(void) {
    AVCaptureDevice *builtIn = [AVCaptureDevice defaultDeviceWithMediaType:AVMediaTypeVideo];
    if (builtIn != nil) {
        return builtIn;
    }
    return NLVideoDevices().firstObject;
}

int startCameraCapture(int preferredWidth, int preferredHeight) {
    (void)preferredWidth;
    (void)preferredHeight;
    if (gSession != nil && gSession.isRunning) {
        return 0;
    }

    AVCaptureDevice *device = NLPickDevice();
    if (device == nil) {
        return -1;
    }

    NSError *error = nil;
    AVCaptureDeviceInput *input = [AVCaptureDeviceInput deviceInputWithDevice:device error:&error];
    if (input == nil) {
        return -2;
    }

    AVCaptureSession *session = [[AVCaptureSession alloc] init];
    if ([session canSetSessionPreset:AVCaptureSessionPreset1280x720]) {
        session.sessionPreset = AVCaptureSessionPreset1280x720;
    } else if ([session canSetSessionPreset:AVCaptureSessionPresetHigh]) {
        session.sessionPreset = AVCaptureSessionPresetHigh;
    }

    if (![session canAddInput:input]) {
        return -3;
    }
    [session addInput:input];

    AVCaptureVideoDataOutput *output = [[AVCaptureVideoDataOutput alloc] init];
    output.videoSettings = @{
        (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA)
    };
    output.alwaysDiscardsLateVideoFrames = YES;

    if (gHandler == nil) {
        gHandler = [[NLCameraFrameHandler alloc] init];
    }
    if (gVideoQueue == nil) {
        gVideoQueue = dispatch_queue_create("ch.collectifnocturne.noctulist.camera", DISPATCH_QUEUE_SERIAL);
    }
    [output setSampleBufferDelegate:gHandler queue:gVideoQueue];

    if (![session canAddOutput:output]) {
        return -4;
    }
    [session addOutput:output];

    [NLFrameLock() lock];
    gHasNewFrame = NO;
    gFrameBgr = nil;
    gFrameWidth = 0;
    gFrameHeight = 0;
    [NLFrameLock() unlock];

    gSession = session;
    [session startRunning];
    return session.isRunning ? 0 : -5;
}

void stopCameraCapture(void) {
    AVCaptureSession *session = gSession;
    gSession = nil;
    if (session != nil) {
        [session stopRunning];
    }
    [NLFrameLock() lock];
    gFrameBgr = nil;
    gHasNewFrame = NO;
    gFrameWidth = 0;
    gFrameHeight = 0;
    [NLFrameLock() unlock];
}

int grabCameraBgr(void *dst, int dstCapacity, int *outWidth, int *outHeight) {
    if (dst == NULL || outWidth == NULL || outHeight == NULL) {
        return 0;
    }
    [NLFrameLock() lock];
    if (!gHasNewFrame || gFrameBgr == nil || gFrameWidth <= 0 || gFrameHeight <= 0) {
        [NLFrameLock() unlock];
        return 0;
    }
    int needed = gFrameWidth * gFrameHeight * 3;
    if (dstCapacity < needed) {
        [NLFrameLock() unlock];
        return -1;
    }
    memcpy(dst, gFrameBgr.bytes, (size_t)needed);
    *outWidth = gFrameWidth;
    *outHeight = gFrameHeight;
    gHasNewFrame = NO;
    [NLFrameLock() unlock];
    return 1;
}
