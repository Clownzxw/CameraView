package com.otaliastudios.cameraview;

import android.graphics.PointF;
import android.location.Location;


import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

abstract class CameraController implements
        CameraPreview.SurfaceCallback,
        FrameManager.BufferCallback,
        Thread.UncaughtExceptionHandler {

    private static final String TAG = CameraController.class.getSimpleName();
    private static final CameraLogger LOG = CameraLogger.create(TAG);

    static final int STATE_STOPPING = -1; // Camera is about to be stopped.
    static final int STATE_STOPPED = 0; // Camera is stopped.
    static final int STATE_STARTING = 1; // Camera is about to start.
    static final int STATE_STARTED = 2; // Camera is available and we can set parameters.

    static final int REF_SENSOR = 0;
    static final int REF_VIEW = 1;
    static final int REF_OUTPUT = 2;

    protected final CameraView.CameraCallbacks mCameraCallbacks;
    protected CameraPreview mPreview;
    protected WorkerHandler mHandler;
    /* for tests */ Handler mCrashHandler;

    protected Facing mFacing;
    protected Flash mFlash;
    protected WhiteBalance mWhiteBalance;
    protected VideoQuality mVideoQuality;
    protected VideoCodec mVideoCodec;
    protected Mode mMode;
    protected Hdr mHdr;
    protected Location mLocation;
    protected Audio mAudio;
    protected float mZoomValue;
    protected float mExposureCorrectionValue;
    protected boolean mPlaySounds;

    protected int mCameraId;
    protected CameraOptions mCameraOptions;
    protected Mapper mMapper;
    protected FrameManager mFrameManager;
    protected SizeSelector mPictureSizeSelector;
    protected MediaRecorder mMediaRecorder;
    protected VideoResult mVideoResult;
    protected long mVideoMaxSize;
    protected int mVideoMaxDuration;
    protected Size mPictureSize;
    protected Size mPreviewSize;
    protected int mPreviewFormat;

    protected int mSensorOffset;
    private int mDisplayOffset;
    private int mDeviceOrientation;

    protected boolean mIsCapturingImage = false;
    protected boolean mIsTakingVideo = false;

    protected int mState = STATE_STOPPED;

    // Used for testing.
    Task<Void> mZoomTask = new Task<>();
    Task<Void> mExposureCorrectionTask = new Task<>();
    Task<Void> mFlashTask = new Task<>();
    Task<Void> mWhiteBalanceTask = new Task<>();
    Task<Void> mHdrTask = new Task<>();
    Task<Void> mLocationTask = new Task<>();
    Task<Void> mVideoQualityTask = new Task<>();
    Task<Void> mStartVideoTask = new Task<>();
    Task<Void> mPlaySoundsTask = new Task<>();

    CameraController(CameraView.CameraCallbacks callback) {
        mCameraCallbacks = callback;
        mCrashHandler = new Handler(Looper.getMainLooper());
        mHandler = WorkerHandler.get("CameraViewController");
        mHandler.getThread().setUncaughtExceptionHandler(this);
        mFrameManager = new FrameManager(2, this);
    }

    void setPreview(CameraPreview cameraPreview) {
        mPreview = cameraPreview;
        mPreview.setSurfaceCallback(this);
    }

    //region Error handling

    private static class NoOpExceptionHandler implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(Thread t, Throwable e) {
            // No-op.
        }
    }

    @Override
    public void uncaughtException(final Thread thread, final Throwable throwable) {
        // Something went wrong. Thread is terminated (about to?).
        // Move to other thread and release resources.
        if (!(throwable instanceof CameraException)) {
            // This is unexpected, either a bug or something the developer should know.
            // Release and crash the UI thread so we get bug reports.
            LOG.e("uncaughtException:", "Unexpected exception:", throwable);
            destroy();
            mCrashHandler.post(new Runnable() {
                @Override
                public void run() {
                    RuntimeException exception;
                    if (throwable instanceof RuntimeException) {
                        exception = (RuntimeException) throwable;
                    } else {
                        exception = new RuntimeException(throwable);
                    }
                    throw exception;
                }
            });
        } else {
            // At the moment all CameraExceptions are unrecoverable, there was something
            // wrong when starting, stopping, or binding the camera to the preview.
            final CameraException error = (CameraException) throwable;
            LOG.e("uncaughtException:", "Interrupting thread with state:", ss(), "due to CameraException:", error);
            thread.interrupt();
            mHandler = WorkerHandler.get("CameraViewController");
            mHandler.getThread().setUncaughtExceptionHandler(this);
            LOG.i("uncaughtException:", "Calling stopImmediately and notifying.");
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopImmediately();
                    mCameraCallbacks.dispatchError(error);
                }
            });
        }
    }

    final void destroy() {
        LOG.i("destroy:", "state:", ss());
        // Prevent CameraController leaks. Don't set to null, or exceptions
        // inside the standard stop() method might crash the main thread.
        mHandler.getThread().setUncaughtExceptionHandler(new NoOpExceptionHandler());
        // Stop if needed.
        stopImmediately();
    }

    //endregion

    //region Start&Stop

    private String ss() {
        switch (mState) {
            case STATE_STOPPING: return "STATE_STOPPING";
            case STATE_STOPPED: return "STATE_STOPPED";
            case STATE_STARTING: return "STATE_STARTING";
            case STATE_STARTED: return "STATE_STARTED";
        }
        return "null";
    }

    // Starts the preview asynchronously.
    final void start() {
        LOG.i("Start:", "posting runnable. State:", ss());
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                LOG.i("Start:", "executing. State:", ss());
                if (mState >= STATE_STARTING) return;
                mState = STATE_STARTING;
                LOG.i("Start:", "about to call onStart()", ss());
                onStart();
                LOG.i("Start:", "returned from onStart().", "Dispatching.", ss());
                mState = STATE_STARTED;
                mCameraCallbacks.dispatchOnCameraOpened(mCameraOptions);
            }
        });
    }

    // Stops the preview asynchronously.
    final void stop() {
        LOG.i("Stop:", "posting runnable. State:", ss());
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                LOG.i("Stop:", "executing. State:", ss());
                if (mState <= STATE_STOPPED) return;
                mState = STATE_STOPPING;
                LOG.i("Stop:", "about to call onStop()");
                onStop();
                LOG.i("Stop:", "returned from onStop().", "Dispatching.");
                mState = STATE_STOPPED;
                mCameraCallbacks.dispatchOnCameraClosed();
            }
        });
    }

    // Stops the preview synchronously, ensuring no exceptions are thrown.
    final void stopImmediately() {
        try {
            // Don't check, try stop again.
            LOG.i("stopImmediately:", "State was:", ss());
            if (mState == STATE_STOPPED) return;
            mState = STATE_STOPPING;
            onStop();
            mState = STATE_STOPPED;
            LOG.i("stopImmediately:", "Stopped. State is:", ss());
        } catch (Exception e) {
            // Do nothing.
            LOG.i("stopImmediately:", "Swallowing exception while stopping.", e);
            mState = STATE_STOPPED;
        }
    }

    // Forces a restart.
    protected final void restart() {
        LOG.i("Restart:", "posting runnable");
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                LOG.i("Restart:", "executing. Needs stopping:", mState > STATE_STOPPED, ss());
                // Don't stop if stopped.
                if (mState > STATE_STOPPED) {
                    mState = STATE_STOPPING;
                    onStop();
                    mState = STATE_STOPPED;
                    LOG.i("Restart:", "stopped. Dispatching.", ss());
                    mCameraCallbacks.dispatchOnCameraClosed();
                }

                LOG.i("Restart: about to start. State:", ss());
                mState = STATE_STARTING;
                onStart();
                mState = STATE_STARTED;
                LOG.i("Restart: returned from start. Dispatching. State:", ss());
                mCameraCallbacks.dispatchOnCameraOpened(mCameraOptions);
            }
        });
    }

    // Starts the preview.
    // At the end of this method camera must be available, e.g. for setting parameters.
    @WorkerThread
    abstract void onStart();

    // Stops the preview.
    @WorkerThread
    abstract void onStop();

    // Returns current state.
    final int getState() {
        return mState;
    }

    //endregion

    //region Simple setters

    // This is called before start() and never again.
    final void setDisplayOffset(int displayOffset) {
        mDisplayOffset = displayOffset;
    }

    // This can be called multiple times.
    final void setDeviceOrientation(int deviceOrientation) {
        mDeviceOrientation = deviceOrientation;
    }

    final void setPictureSizeSelector(SizeSelector selector) {
        mPictureSizeSelector = selector;
    }

    final void setVideoMaxSize(long videoMaxSizeBytes) {
        mVideoMaxSize = videoMaxSizeBytes;
    }

    final void setVideoMaxDuration(int videoMaxDurationMillis) {
        mVideoMaxDuration = videoMaxDurationMillis;
    }

    final void setVideoCodec(VideoCodec codec) {
        mVideoCodec = codec;
    }


    //endregion

    //region Abstract setters and APIs

    // Should restart the session if active.
    abstract void setMode(Mode mode);

    // Should restart the session if active.
    abstract void setFacing(Facing facing);

    // If closed, no-op. If opened, check supported and apply.
    abstract void setZoom(float zoom, PointF[] points, boolean notify);

    // If closed, no-op. If opened, check supported and apply.
    abstract void setExposureCorrection(float EVvalue, float[] bounds, PointF[] points, boolean notify);

    // If closed, keep. If opened, check supported and apply.
    abstract void setFlash(Flash flash);

    // If closed, keep. If opened, check supported and apply.
    abstract void setWhiteBalance(WhiteBalance whiteBalance);

    // If closed, keep. If opened, check supported and apply.
    abstract void setHdr(Hdr hdr);

    // If closed, keep. If opened, check supported and apply.
    abstract void setLocation(Location location);

    // Just set.
    abstract void setAudio(Audio audio);

    // Throw if capturing. If in video session, recompute capture size, and, if needed, preview size.
    abstract void setVideoQuality(VideoQuality videoQuality);

    abstract void takePicture();

    abstract void takePictureSnapshot(AspectRatio viewAspectRatio);

    abstract void takeVideo(@NonNull File file);

    abstract void stopVideo();

    abstract void startAutoFocus(@Nullable Gesture gesture, PointF point);

    abstract void setPlaySounds(boolean playSounds);

    //endregion

    //region final getters

    @Nullable
    final CameraOptions getCameraOptions() {
        return mCameraOptions;
    }

    final Facing getFacing() {
        return mFacing;
    }

    final Flash getFlash() {
        return mFlash;
    }

    final WhiteBalance getWhiteBalance() {
        return mWhiteBalance;
    }

    final VideoQuality getVideoQuality() {
        return mVideoQuality;
    }

    final VideoCodec getVideoCodec() {
        return mVideoCodec;
    }

    final long getVideoMaxSize() {
        return mVideoMaxSize;
    }

    final int getVideoMaxDuration() {
        return mVideoMaxDuration;
    }

    final Mode getMode() {
        return mMode;
    }

    final Hdr getHdr() {
        return mHdr;
    }

    final Location getLocation() {
        return mLocation;
    }

    final Audio getAudio() {
        return mAudio;
    }

    final SizeSelector getPictureSizeSelector() {
        return mPictureSizeSelector;
    }

    final float getZoomValue() {
        return mZoomValue;
    }

    final float getExposureCorrectionValue() {
        return mExposureCorrectionValue;
    }

    final boolean isTakingVideo() {
        return mIsTakingVideo;
    }

    //endregion

    //region Orientation utils

    private int computeSensorToViewOffset() {
        if (mFacing == Facing.FRONT) {
            return (360 - ((mSensorOffset + mDisplayOffset) % 360)) % 360;
        } else {
            return (mSensorOffset - mDisplayOffset + 360) % 360;
        }
    }

    private int computeSensorToOutputOffset() {
        if (mFacing == Facing.FRONT) {
            return (mSensorOffset - mDeviceOrientation + 360) % 360;
        } else {
            return (mSensorOffset + mDeviceOrientation) % 360;
        }
    }

    // Returns the offset between two reference systems.
    final int offset(int fromReference, int toReference) {
        if (fromReference == toReference) return 0;
        // We only know how to compute offsets with respect to REF_SENSOR.
        // That's why we separate the two cases.
        if (fromReference == REF_SENSOR) {
            return toReference == REF_VIEW ?
                    computeSensorToViewOffset() :
                    computeSensorToOutputOffset();
        }
        // Maybe the sensor is the other.
        if (toReference == REF_SENSOR) {
            return -offset(toReference, fromReference) + 360;
        }
        // None of them is the sensor. Use a difference.
        return (offset(REF_SENSOR, toReference) - offset(REF_SENSOR, fromReference) + 360) % 360;
    }

    final boolean flip(int reference1, int reference2) {
        return offset(reference1, reference2) % 180 != 0;
    }

    final Size getPictureSize(int reference) {
        if (mPictureSize == null) return null;
        return flip(REF_SENSOR, reference) ? mPictureSize.flip() : mPictureSize;
    }

    final Size getPreviewSize(int reference) {
        if (mPreviewSize == null) return null;
        return flip(REF_SENSOR, reference) ? mPreviewSize.flip() : mPreviewSize;
    }


    //endregion

    //region Size utils

    /**
     * This is called either on cameraView.start(), or when the underlying surface changes.
     * It is possible that in the first call the preview surface has not already computed its
     * dimensions.
     * But when it does, the {@link CameraPreview.SurfaceCallback} should be called,
     * and this should be refreshed.
     */
    protected final Size computePictureSize() {
        // The external selector is expecting stuff in the view world, not in the sensor world.
        // Use the list in the camera options, then flip the result if needed.
        boolean flip = flip(REF_SENSOR, REF_VIEW);
        SizeSelector selector;

        if (mMode == Mode.PICTURE) {
            selector = SizeSelectors.or(mPictureSizeSelector, SizeSelectors.biggest());
        } else {
            // The Camcorder internally checks for cameraParameters.getSupportedVideoSizes() etc.
            // And we want the picture size to be the biggest picture consistent with the video aspect ratio.
            // -> Use the external picture selector, but enforce the ratio constraint.
            CamcorderProfile profile = getCamcorderProfile();
            AspectRatio targetRatio = AspectRatio.of(profile.videoFrameWidth, profile.videoFrameHeight);
            if (flip) targetRatio = targetRatio.inverse();
            LOG.i("size:", "computeCaptureSize:", "videoQuality:", mVideoQuality, "targetRatio:", targetRatio);
            SizeSelector matchRatio = SizeSelectors.aspectRatio(targetRatio, 0);
            selector = SizeSelectors.or(
                    SizeSelectors.and(matchRatio, mPictureSizeSelector),
                    SizeSelectors.and(matchRatio),
                    mPictureSizeSelector
            );
        }

        List<Size> list = new ArrayList<>(mCameraOptions.getSupportedPictureSizes());
        Size result = selector.select(list).get(0);
        LOG.i("computePictureSize:", "result:", result, "flip:", flip);
        if (flip) result = result.flip();
        return result;
    }

    protected final Size computePreviewSize(List<Size> previewSizes) {
        // instead of flipping everything to the view world, we can just flip the
        // surface size to the sensor world
        AspectRatio targetRatio = AspectRatio.of(mPictureSize.getWidth(), mPictureSize.getHeight());
        Size targetMinSize = mPreview.getSurfaceSize();
        boolean flip = flip(REF_SENSOR, REF_VIEW);
        if (flip) targetMinSize = targetMinSize.flip();
        LOG.i("size:", "computePreviewSize:", "targetRatio:", targetRatio, "targetMinSize:", targetMinSize);
        SizeSelector matchRatio = SizeSelectors.and( // Match this aspect ratio and sort by biggest
                SizeSelectors.aspectRatio(targetRatio, 0),
                SizeSelectors.biggest());
        SizeSelector matchSize = SizeSelectors.and( // Bigger than this size, and sort by smallest
                SizeSelectors.minHeight(targetMinSize.getHeight()),
                SizeSelectors.minWidth(targetMinSize.getWidth()),
                SizeSelectors.smallest());
        SizeSelector matchAll = SizeSelectors.or(
                SizeSelectors.and(matchRatio, matchSize), // Try to respect both constraints.
                matchSize, // If couldn't match aspect ratio, at least respect the size
                matchRatio, // If couldn't respect size, at least match aspect ratio
                SizeSelectors.biggest() // If couldn't match any, take the biggest.
        );
        Size result = matchAll.select(previewSizes).get(0);
        LOG.i("computePreviewSize:", "result:", result, "flip:", flip);
        return result;
    }

    @NonNull
    protected final CamcorderProfile getCamcorderProfile() {
        switch (mVideoQuality) {
            case HIGHEST:
                return CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_HIGH);

            case MAX_2160P:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                        CamcorderProfile.hasProfile(CamcorderProfile.QUALITY_2160P)) {
                    return CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_2160P);
                }
                // Don't break.

            case MAX_1080P:
                if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_1080P)) {
                    return CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_1080P);
                }
                // Don't break.

            case MAX_720P:
                if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_720P)) {
                    return CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_720P);
                }
                // Don't break.

            case MAX_480P:
                if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_480P)) {
                    return CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_480P);
                }
                // Don't break.

            case MAX_QVGA:
                if (CamcorderProfile.hasProfile(mCameraId, CamcorderProfile.QUALITY_QVGA)) {
                    return CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_QVGA);
                }
                // Don't break.

            case LOWEST:
            default:
                // Fallback to lowest.
                return CamcorderProfile.get(mCameraId, CamcorderProfile.QUALITY_LOW);
        }
    }

    //endregion
}
