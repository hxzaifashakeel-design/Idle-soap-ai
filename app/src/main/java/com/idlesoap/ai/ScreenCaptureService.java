package com.idlesoap.ai;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import android.app.Service;
import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScreenCaptureService extends Service {

    public static final String ACTION_START =
            "com.idlesoap.ai.START_CAPTURE";

    public static final String ACTION_STOP =
            "com.idlesoap.ai.STOP_CAPTURE";

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "screen_capture";
    private static final int NOTIFICATION_ID = 101;

    private static final long OCR_INTERVAL_MS = 3500L;
    private static final long FRAME_SAVE_INTERVAL_MS = 1000L;
    private static final int UPSCALE_FACTOR = 2;
    private static final int PREVIEW_MAX_SIDE = 640;
    private static final int PREVIEW_JPEG_QUALITY = 75;

    // Package-visible so MainActivity can draw the exact same OCR rectangles.
    static final class OcrRegion {
        final String name;
        final float left;
        final float top;
        final float right;
        final float bottom;

        OcrRegion(String name, float left, float top,
                  float right, float bottom) {
            this.name = name;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    // Portrait coordinates are retained for the earlier tall game layout.
    private static final OcrRegion[] PORTRAIT_OCR_REGIONS = {
        new OcrRegion("MONEY", 0.005f, 0.025f, 0.195f, 0.065f),
        new OcrRegion("EARNINGS", 0.015f, 0.066f, 0.19f, 0.095f),
        new OcrRegion("SOAP STATUS", 0.29f, 0.055f, 0.52f, 0.108f),
        new OcrRegion("SOAP PERCENT", 0.39f, 0.075f, 0.51f, 0.112f),
        new OcrRegion("SOAP NAME", 0.36f, 0.135f, 0.50f, 0.16f),
        new OcrRegion("BOOSTS", 0.005f, 0.195f, 0.13f, 0.44f),
        new OcrRegion("PROGRESS LEVEL", 0.24f, 0.51f, 0.56f, 0.565f),
        new OcrRegion("LEVEL UP BUTTON", 0.24f, 0.545f, 0.56f, 0.59f),
        new OcrRegion("CUT UPGRADE", 0.15f, 0.565f, 0.31f, 0.69f),
        new OcrRegion("INCOME UPGRADE", 0.325f, 0.565f, 0.48f, 0.69f),
        new OcrRegion("SPEED UPGRADE", 0.505f, 0.565f, 0.66f, 0.69f)
};

    // First calibration for the wide split-screen layout.
    // Verify these rectangles with the on-device overlay before treating them as final.
    private static final OcrRegion[] LANDSCAPE_OCR_REGIONS = {
        new OcrRegion("MONEY", 0.005f, 0.085f, 0.16f, 0.15f),
        new OcrRegion("EARNINGS", 0.005f, 0.15f, 0.18f, 0.21f),
        new OcrRegion("SOAP STATUS", 0.40f, 0.065f, 0.68f, 0.15f),
        new OcrRegion("SOAP PERCENT", 0.445f, 0.09f, 0.555f, 0.14f),
        new OcrRegion("SOAP NAME", 0.43f, 0.22f, 0.58f, 0.29f),
        new OcrRegion("BOOSTS", 0.005f, 0.22f, 0.15f, 0.62f),
        new OcrRegion("PROGRESS LEVEL", 0.38f, 0.62f, 0.67f, 0.705f),
        new OcrRegion("LEVEL UP BUTTON", 0.405f, 0.66f, 0.595f, 0.705f),
        new OcrRegion("CUT UPGRADE", 0.35f, 0.70f, 0.45f, 0.90f),
        new OcrRegion("INCOME UPGRADE", 0.45f, 0.70f, 0.55f, 0.90f),
        new OcrRegion("SPEED UPGRADE", 0.55f, 0.70f, 0.65f, 0.90f)
};

    static OcrRegion[] getOcrRegionsForSize(int width, int height) {
        return width >= height
                ? LANDSCAPE_OCR_REGIONS
                : PORTRAIT_OCR_REGIONS;
    }

    static String getOcrLayoutName(int width, int height) {
        return width >= height
                ? "LANDSCAPE / SPLIT-SCREEN"
                : "PORTRAIT";
    }

    private MediaProjection projection;
    private volatile VirtualDisplay virtualDisplay;
    private volatile ImageReader imageReader;

    private HandlerThread handlerThread;
    private Handler imageHandler;

    private volatile int screenWidth;
    private volatile int screenHeight;
    private int screenDensityDpi;

    private volatile int pendingResizeWidth;
    private volatile int pendingResizeHeight;
    private volatile int resizeCallbackCount;
    private volatile int lastResizeCallbackWidth;
    private volatile int lastResizeCallbackHeight;
    private volatile int lastFrameWidth;
    private volatile int lastFrameHeight;

    private volatile boolean ocrInProgress = false;
    private volatile boolean previewSavePending = false;

    private long lastSavedTime = 0L;
    private long lastOcrTime = 0L;

    private TextRecognizer textRecognizer;
    private ExecutorService previewExecutor;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (projection != null) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);

        if (resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startCaptureNotification();

        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(
                            Context.MEDIA_PROJECTION_SERVICE);

            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                stopSelf();
                return START_NOT_STICKY;
            }

            startScreenCapture();
        } catch (Exception e) {
            Log.e("IdleSoapAI", "Capture failed", e);
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startCaptureNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Capture",
                    NotificationManager.IMPORTANCE_LOW);

            NotificationManager manager =
                    getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }

        Intent stopIntent = new Intent(this, ScreenCaptureService.class);
        stopIntent.setAction(ACTION_STOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 102, stopIntent, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
                .setContentTitle("Idle Soap AI")
                .setContentText("Screen capture is active")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .addAction(
                        android.R.drawable.ic_media_pause,
                        "Stop capture",
                        stopPendingIntent)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startScreenCapture() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensityDpi = metrics.densityDpi;

        if (Build.VERSION.SDK_INT >= 30) {
            WindowManager wm =
                    (WindowManager) getSystemService(WINDOW_SERVICE);
            android.graphics.Rect bounds =
                    wm.getMaximumWindowMetrics().getBounds();
            screenWidth = bounds.width();
            screenHeight = bounds.height();
        }

        handlerThread = new HandlerThread("IdleSoapAI-Capture");
        handlerThread.start();
        imageHandler = new Handler(handlerThread.getLooper());

        previewExecutor = Executors.newSingleThreadExecutor();
        textRecognizer = TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS);

        imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2);
        imageReader.setOnImageAvailableListener(
                this::saveLatestFrame,
                imageHandler);

        projection.registerCallback(
                new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        stopSelf();
                    }

                    @Override
                    public void onCapturedContentResize(int width, int height) {
                        if (width <= 0 || height <= 0) {
                            return;
                        }

                        resizeCallbackCount++;
                        lastResizeCallbackWidth = width;
                        lastResizeCallbackHeight = height;

                        if (virtualDisplay == null) {
                            pendingResizeWidth = width;
                            pendingResizeHeight = height;
                            return;
                        }

                        resizeCapture(width, height);
                    }
                },
                imageHandler);

        virtualDisplay = projection.createVirtualDisplay(
                "IdleSoapAI-Capture",
                screenWidth,
                screenHeight,
                screenDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                imageHandler);

        writeCaptureDiagnostics("Display created; waiting for first frame");

        // Apply a resize callback that arrived during display creation.
        int pendingWidth = pendingResizeWidth;
        int pendingHeight = pendingResizeHeight;
        pendingResizeWidth = 0;
        pendingResizeHeight = 0;

        if (pendingWidth > 0 && pendingHeight > 0) {
            imageHandler.post(() -> resizeCapture(pendingWidth, pendingHeight));
        }
    }

    private void resizeCapture(int width, int height) {
        if (width <= 0 || height <= 0 || imageHandler == null) {
            return;
        }

        if (Looper.myLooper() != imageHandler.getLooper()) {
            imageHandler.post(() -> resizeCapture(width, height));
            return;
        }

        VirtualDisplay display = virtualDisplay;
        if (display == null) {
            pendingResizeWidth = width;
            pendingResizeHeight = height;
            return;
        }

        if (width == screenWidth && height == screenHeight) {
            writeCaptureDiagnostics("Resize callback received; size unchanged");
            return;
        }

        ImageReader previousReader = imageReader;
        ImageReader replacementReader = null;

        try {
            replacementReader = ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    2);
            replacementReader.setOnImageAvailableListener(
                    this::saveLatestFrame,
                    imageHandler);

            // Android requires both the VirtualDisplay and output Surface to match.
            display.resize(width, height, screenDensityDpi);
            display.setSurface(replacementReader.getSurface());

            imageReader = replacementReader;
            screenWidth = width;
            screenHeight = height;
            lastFrameWidth = 0;
            lastFrameHeight = 0;
            lastSavedTime = 0L;
            lastOcrTime = 0L;

            if (previousReader != null) {
                previousReader.close();
            }

            writeCaptureDiagnostics("Capture resized successfully");
            Log.i("IdleSoapAI", "Capture resized to " + width + " x " + height);
        } catch (Exception e) {
            // Try to keep the previous output surface active if resizing failed.
            try {
                if (previousReader != null) {
                    display.setSurface(previousReader.getSurface());
                }
            } catch (Exception restoreError) {
                Log.e("IdleSoapAI", "Could not restore previous capture surface", restoreError);
            }

            if (replacementReader != null && replacementReader != imageReader) {
                replacementReader.close();
            }

            writeCaptureDiagnostics("Resize failed: " + e.getClass().getSimpleName());
            Log.e("IdleSoapAI", "Could not resize screen capture", e);
        }
    }

    private void saveLatestFrame(ImageReader reader) {
        Image image = null;
        Bitmap paddedBitmap = null;
        Bitmap screenBitmap = null;

        try {
            // Ignore a delayed callback from an ImageReader that has been replaced.
            if (reader != imageReader) {
                image = reader.acquireLatestImage();
                return;
            }

            image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }

            int frameWidth = image.getWidth();
            int frameHeight = image.getHeight();
            if (frameWidth <= 0 || frameHeight <= 0) {
                return;
            }

            long now = SystemClock.elapsedRealtime();
            if (now - lastSavedTime < FRAME_SAVE_INTERVAL_MS) {
                return;
            }

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();

            if (pixelStride <= 0 || rowStride <= 0) {
                return;
            }

            int rowPadding = rowStride - pixelStride * frameWidth;
            int paddedWidth = frameWidth + Math.max(0, rowPadding / pixelStride);

            paddedBitmap = Bitmap.createBitmap(
                    paddedWidth,
                    frameHeight,
                    Bitmap.Config.ARGB_8888);
            buffer.rewind();
            paddedBitmap.copyPixelsFromBuffer(buffer);

            screenBitmap = Bitmap.createBitmap(
                    paddedBitmap,
                    0,
                    0,
                    frameWidth,
                    frameHeight);

            lastFrameWidth = frameWidth;
            lastFrameHeight = frameHeight;
            screenWidth = frameWidth;
            screenHeight = frameHeight;
            writeCaptureDiagnostics("Frame received");

            // A reduced preview is generated separately; OCR keeps full resolution.
            schedulePreviewSave(screenBitmap);
            lastSavedTime = now;

            if (textRecognizer != null
                    && !ocrInProgress
                    && now - lastOcrTime >= OCR_INTERVAL_MS) {
                Bitmap ocrFrame = screenBitmap.copy(
                        Bitmap.Config.ARGB_8888,
                        false);

                if (ocrFrame != null) {
                    lastOcrTime = now;

                    ocrInProgress = true;

                    OcrRegion[] activeRegions = getOcrRegionsForSize(
                            ocrFrame.getWidth(),
                            ocrFrame.getHeight());

                    processNextRegion(
                            ocrFrame,
                            0,
                            new String[activeRegions.length]);
                }
            }
        } catch (Exception e) {
            Log.e("IdleSoapAI", "Frame capture failed", e);
        } finally {
            if (screenBitmap != null
                    && screenBitmap != paddedBitmap
                    && !screenBitmap.isRecycled()) {
                screenBitmap.recycle();
            }

            if (paddedBitmap != null && !paddedBitmap.isRecycled()) {
                paddedBitmap.recycle();
            }

            if (image != null) {
                image.close();
            }
        }
    }

    private void schedulePreviewSave(Bitmap source) {
        if (source == null
                || source.isRecycled()
                || previewExecutor == null
                || previewExecutor.isShutdown()
                || previewSavePending) {
            return;
        }

        previewSavePending = true;
        Bitmap previewBitmap = null;

        try {
            int sourceMaxSide = Math.max(source.getWidth(), source.getHeight());
            float scale = Math.min(1.0f, (float) PREVIEW_MAX_SIDE / sourceMaxSide);
            int targetWidth = Math.max(1, Math.round(source.getWidth() * scale));
            int targetHeight = Math.max(1, Math.round(source.getHeight() * scale));

            previewBitmap = Bitmap.createScaledBitmap(
                    source,
                    targetWidth,
                    targetHeight,
                    true);

            if (previewBitmap == source) {
                previewBitmap = source.copy(Bitmap.Config.ARGB_8888, false);
            }

            if (previewBitmap == null) {
                previewSavePending = false;
                return;
            }
        } catch (Exception e) {
            previewSavePending = false;
            Log.e("IdleSoapAI", "Could not create reduced preview", e);
            return;
        }

        final Bitmap bitmapToSave = previewBitmap;

        try {
            previewExecutor.execute(() -> {
                File output = new File(getCacheDir(), "latest_screen.jpg");
                File temporary = new File(getCacheDir(), "latest_screen.tmp");

                try {
                    try (FileOutputStream stream = new FileOutputStream(temporary)) {
                        boolean compressed = bitmapToSave.compress(
                                Bitmap.CompressFormat.JPEG,
                                PREVIEW_JPEG_QUALITY,
                                stream);

                        if (!compressed) {
                            throw new IllegalStateException("Preview compression failed");
                        }

                        stream.flush();
                    }

                    if (!temporary.renameTo(output)) {
                        if (output.exists() && !output.delete()) {
                            throw new IllegalStateException("Could not replace preview file");
                        }

                        if (!temporary.renameTo(output)) {
                            throw new IllegalStateException("Could not publish preview file");
                        }
                    }
                } catch (Exception e) {
                    temporary.delete();
                    Log.e("IdleSoapAI", "Could not save preview", e);
                } finally {
                    if (!bitmapToSave.isRecycled()) {
                        bitmapToSave.recycle();
                    }

                    previewSavePending = false;
                }
            });
        } catch (RuntimeException e) {
            if (!bitmapToSave.isRecycled()) {
                bitmapToSave.recycle();
            }

            previewSavePending = false;
            Log.e("IdleSoapAI", "Preview worker unavailable", e);
        }
    }

    private void writeCaptureDiagnostics(String note) {
        int width = screenWidth;
        int height = screenHeight;
        int frameWidth = lastFrameWidth;
        int frameHeight = lastFrameHeight;
        int callbackWidth = lastResizeCallbackWidth;
        int callbackHeight = lastResizeCallbackHeight;

        String aspect = "unknown";

        if (height > 0) {
            aspect = String.format(
                    Locale.US,
                    "%.3f",
                    (double) width / (double) height);
        }

        String callbackText = resizeCallbackCount == 0
                ? "not received"
                : resizeCallbackCount + " time(s), last "
                    + callbackWidth + " x " + callbackHeight;

        String diagnostics =
                "CAPTURE DIAGNOSTICS\n"
                + "ImageReader size: " + width + " x " + height + " px\n"
                + "Latest frame: "
                + (frameWidth > 0 ? frameWidth + " x " + frameHeight : "waiting")
                + " px\n"
                + "Aspect ratio (width/height): " + aspect + "\n"
                + "Density: " + screenDensityDpi + " dpi\n"
                + "Resize callback: " + callbackText + "\n"
                + "OCR layout: " + getOcrLayoutName(width, height) + "\n"
                + "OCR regions: "
                + getOcrRegionsForSize(width, height).length + "\n"
                + "Preview: max side " + PREVIEW_MAX_SIDE
                + " px, JPEG quality " + PREVIEW_JPEG_QUALITY + "\n"
                + "Status: " + note + "\n";

        File output = new File(getCacheDir(), "latest_capture_info.txt");
        File temporary = new File(getCacheDir(), "latest_capture_info.tmp");

        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            stream.write(diagnostics.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stream.flush();
        } catch (Exception e) {
            Log.e("IdleSoapAI", "Could not write capture diagnostics", e);
            temporary.delete();
            return;
        }

        if (!temporary.renameTo(output)) {
            if (output.exists()) {
                output.delete();
            }

            if (!temporary.renameTo(output)) {
                Log.e("IdleSoapAI", "Could not publish capture diagnostics");
                temporary.delete();
            }
        }
    }

    private void processNextRegion(
            Bitmap source,
            int regionIndex,
            String[] regionResults) {

        OcrRegion[] regions = getOcrRegionsForSize(
                source.getWidth(),
                source.getHeight());

        if (regionIndex >= regions.length) {
            StringBuilder snapshot = new StringBuilder();

            for (int i = 0; i < regions.length; i++) {
                snapshot.append("[")
                        .append(regions[i].name)
                        .append("]\n");

                String value = regionResults[i];

                if (value != null && !value.trim().isEmpty()) {
                    snapshot.append(value.trim()).append("\n");
                } else {
                    snapshot.append("Not detected\n");
                }
            }

            saveOcrResult(snapshot.toString());
            ocrInProgress = false;

            if (!source.isRecycled()) {
                source.recycle();
            }

            return;
        }

        OcrRegion region = regions[regionIndex];
        Bitmap rawCrop = null;
        Bitmap enlarged = null;

        try {
            int left = Math.max(0, Math.min(
                    source.getWidth() - 1,
                    Math.round(source.getWidth() * region.left)));

            int top = Math.max(0, Math.min(
                    source.getHeight() - 1,
                    Math.round(source.getHeight() * region.top)));

            int right = Math.max(left + 1, Math.min(
                    source.getWidth(),
                    Math.round(source.getWidth() * region.right)));

            int bottom = Math.max(top + 1, Math.min(
                    source.getHeight(),
                    Math.round(source.getHeight() * region.bottom)));

            rawCrop = Bitmap.createBitmap(
                    source,
                    left,
                    top,
                    right - left,
                    bottom - top);

            int upscaleFactor = UPSCALE_FACTOR;

if ("SOAP PERCENT".equals(region.name)) {
    upscaleFactor = 4;
} else if ("LEVEL UP BUTTON".equals(region.name)) {
    upscaleFactor = 3;
}

enlarged = Bitmap.createScaledBitmap(
        rawCrop,
        rawCrop.getWidth() * upscaleFactor,
        rawCrop.getHeight() * upscaleFactor,
        true);

            final Bitmap cropToRelease = rawCrop;
            final Bitmap enlargedToRelease = enlarged;

            InputImage input = InputImage.fromBitmap(enlarged, 0);

            textRecognizer.process(input)
                    .addOnSuccessListener(result -> {
                        regionResults[regionIndex] = result.getText();
                    })
                    .addOnFailureListener(error -> {
                        Log.e("IdleSoapAI", "OCR failed for " + region.name, error);
                        regionResults[regionIndex] = "Recognition error";
                    })
                    .addOnCompleteListener(task -> {
                        if (enlargedToRelease != cropToRelease
                                && !enlargedToRelease.isRecycled()) {
                            enlargedToRelease.recycle();
                        }

                        if (!cropToRelease.isRecycled()) {
                            cropToRelease.recycle();
                        }

                        processNextRegion(
                                source,
                                regionIndex + 1,
                                regionResults);
                    });

        } catch (Exception e) {
            Log.e("IdleSoapAI", "Could not process " + region.name, e);

            if (enlarged != null && !enlarged.isRecycled()) {
                enlarged.recycle();
            }

            if (rawCrop != null && !rawCrop.isRecycled()) {
                rawCrop.recycle();
            }

            regionResults[regionIndex] = "Recognition error";

            processNextRegion(
                    source,
                    regionIndex + 1,
                    regionResults);
        }
    }

    private void saveOcrResult(String recognizedText) {
        File output = new File(getCacheDir(), "latest_ocr.txt");
        File temporary = new File(getCacheDir(), "latest_ocr.tmp");

        try (FileOutputStream stream = new FileOutputStream(temporary)) {
            String value = recognizedText == null ? "" : recognizedText;

            stream.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stream.flush();

        } catch (Exception e) {
            Log.e("IdleSoapAI", "Could not write OCR snapshot", e);
            return;
        }

        if (output.exists() && !output.delete()) {
            Log.e("IdleSoapAI", "Could not replace previous OCR snapshot");
            temporary.delete();
            return;
        }

        if (!temporary.renameTo(output)) {
            Log.e("IdleSoapAI", "Could not publish OCR snapshot");
            temporary.delete();
        }
    }

    @Override
    public void onDestroy() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (projection != null) {
            projection.stop();
            projection = null;
        }

        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }

        if (previewExecutor != null) {
            previewExecutor.shutdown();
            previewExecutor = null;
        }

        if (textRecognizer != null) {
            textRecognizer.close();
            textRecognizer = null;
        }

        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
