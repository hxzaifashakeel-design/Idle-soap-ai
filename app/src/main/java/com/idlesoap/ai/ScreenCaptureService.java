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
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.content.pm.ServiceInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

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
    private static final int UPSCALE_FACTOR = 2;

    private static final class OcrRegion {

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

    // Region positions are based on your supplied gameplay screenshot.
    private static final OcrRegion[] OCR_REGIONS = {

            new OcrRegion(
                    "MONEY",
                    0.01f, 0.03f, 0.24f, 0.08f),

            new OcrRegion(
                    "EARNINGS",
                    0.01f, 0.085f, 0.24f, 0.12f),

            new OcrRegion(
                    "SOAP STATUS",
                    0.35f, 0.07f, 0.65f, 0.13f),

            new OcrRegion(
                    "SOAP NAME",
                    0.40f, 0.16f, 0.60f, 0.205f),

            new OcrRegion(
                    "BOOSTS",
                    0.005f, 0.245f, 0.15f, 0.55f),

            new OcrRegion(
                    "CUTTING PROGRESS",
                    0.29f, 0.60f, 0.70f, 0.705f),

            new OcrRegion(
                    "CUT UPGRADE",
                    0.18f, 0.675f, 0.38f, 0.86f),

            new OcrRegion(
                    "INCOME UPGRADE",
                    0.40f, 0.675f, 0.60f, 0.86f),

            new OcrRegion(
                    "SPEED UPGRADE",
                    0.62f, 0.675f, 0.82f, 0.86f)
    };

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private HandlerThread handlerThread;
    private Handler imageHandler;

    private int screenWidth;
    private int screenHeight;

    private long lastSavedTime = 0L;
    private long lastOcrTime = 0L;

    private TextRecognizer textRecognizer;
    private volatile boolean ocrInProgress = false;

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

        int resultCode =
                intent.getIntExtra(EXTRA_RESULT_CODE, 0);

        Intent resultData =
                intent.getParcelableExtra(EXTRA_RESULT_DATA);

        if (resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startCaptureNotification();

        try {

            MediaProjectionManager manager =
                    (MediaProjectionManager)
                    getSystemService(
                            Context.MEDIA_PROJECTION_SERVICE);

            projection = manager.getMediaProjection(
                    resultCode, resultData);

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

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Screen Capture",
                            NotificationManager.IMPORTANCE_LOW);

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }

        Intent stopIntent =
                new Intent(this, ScreenCaptureService.class);

        stopIntent.setAction(ACTION_STOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent stopPendingIntent =
                PendingIntent.getService(
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

        DisplayMetrics metrics =
                getResources().getDisplayMetrics();

        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        int density = metrics.densityDpi;

        if (Build.VERSION.SDK_INT >= 30) {

            WindowManager wm =
                    (WindowManager) getSystemService(WINDOW_SERVICE);

            android.graphics.Rect bounds =
                    wm.getMaximumWindowMetrics().getBounds();

            screenWidth = bounds.width();
            screenHeight = bounds.height();
        }

        handlerThread =
                new HandlerThread("IdleSoapAI-Capture");

        handlerThread.start();

        imageHandler =
                new Handler(handlerThread.getLooper());

        textRecognizer =
                TextRecognition.getClient(
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
                },
                imageHandler);

        virtualDisplay = projection.createVirtualDisplay(
                "IdleSoapAI-Capture",
                screenWidth,
                screenHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                imageHandler);
    }

    private void saveLatestFrame(ImageReader reader) {

        Image image = null;
        Bitmap paddedBitmap = null;
        Bitmap screenBitmap = null;

        try {

            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            long now = SystemClock.elapsedRealtime();

            if (now - lastSavedTime < 1000L) {
                return;
            }

            Image.Plane plane = image.getPlanes()[0];

            ByteBuffer buffer = plane.getBuffer();

            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();

            int rowPadding =
                    rowStride - pixelStride * screenWidth;

            int paddedWidth =
                    screenWidth + rowPadding / pixelStride;

            paddedBitmap = Bitmap.createBitmap(
                    paddedWidth,
                    screenHeight,
                    Bitmap.Config.ARGB_8888);

            paddedBitmap.copyPixelsFromBuffer(buffer);

            screenBitmap = Bitmap.createBitmap(
                    paddedBitmap,
                    0,
                    0,
                    screenWidth,
                    screenHeight);

            // High-quality preview. OCR uses the original bitmap.
            File previewFile =
                    new File(getCacheDir(), "latest_screen.jpg");

            try (FileOutputStream stream =
                         new FileOutputStream(previewFile)) {

                screenBitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        90,
                        stream);

                stream.flush();
            }

            lastSavedTime = now;

            if (textRecognizer != null
                    && !ocrInProgress
                    && now - lastOcrTime >= OCR_INTERVAL_MS) {

                Bitmap ocrFrame =
                        screenBitmap.copy(
                                Bitmap.Config.ARGB_8888, false);

                if (ocrFrame != null) {

                    lastOcrTime = now;
                    ocrInProgress = true;

                    processNextRegion(
                            ocrFrame,
                            0,
                            new StringBuilder());
                }
            }

        } catch (Exception e) {

            Log.e("IdleSoapAI", "Frame capture failed", e);

        } finally {

            if (screenBitmap != null
                    && screenBitmap != paddedBitmap) {
                screenBitmap.recycle();
            }

            if (paddedBitmap != null) {
                paddedBitmap.recycle();
            }

            if (image != null) {
                image.close();
            }
        }
    }

    private void processNextRegion(
            Bitmap source,
            int regionIndex,
            StringBuilder snapshot) {

        if (regionIndex >= OCR_REGIONS.length) {

            saveOcrResult(snapshot.toString());

            ocrInProgress = false;

            if (!source.isRecycled()) {
                source.recycle();
            }

            return;
        }

        OcrRegion region = OCR_REGIONS[regionIndex];

        Bitmap rawCrop = null;
        Bitmap enlarged = null;

        try {

            int left = Math.max(
                    0,
                    Math.min(
                            source.getWidth() - 1,
                            Math.round(source.getWidth() * region.left)));

            int top = Math.max(
                    0,
                    Math.min(
                            source.getHeight() - 1,
                            Math.round(source.getHeight() * region.top)));

            int right = Math.max(
                    left + 1,
                    Math.min(
                            source.getWidth(),
                            Math.round(source.getWidth() * region.right)));

            int bottom = Math.max(
                    top + 1,
                    Math.min(
                            source.getHeight(),
                            Math.round(source.getHeight() * region.bottom)));

            rawCrop = Bitmap.createBitmap(
                    source,
                    left,
                    top,
                    right - left,
                    bottom - top);

            enlarged = Bitmap.createScaledBitmap(
                    rawCrop,
                    rawCrop.getWidth() * UPSCALE_FACTOR,
                    rawCrop.getHeight() * UPSCALE_FACTOR,
                    true);

            final Bitmap cropToRelease = rawCrop;
            final Bitmap enlargedToRelease = enlarged;

            snapshot.append("[")
                    .append(region.name)
                    .append("]\n");

            InputImage input =
                    InputImage.fromBitmap(enlarged, 0);

            textRecognizer.process(input)

                    .addOnSuccessListener(result -> {

                        String text = result.getText();

                        if (text != null && !text.trim().isEmpty()) {
                            snapshot.append(text.trim()).append("\n");
                        }
                    })

                    .addOnFailureListener(error -> {

                        Log.e(
                                "IdleSoapAI",
                                "OCR failed for " + region.name,
                                error);

                        snapshot.append("Recognition error\n");
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
                                snapshot);
                    });

        } catch (Exception e) {

            Log.e(
                    "IdleSoapAI",
                    "Could not process region " + region.name,
                    e);

            if (enlarged != null && !enlarged.isRecycled()) {
                enlarged.recycle();
            }

            if (rawCrop != null && !rawCrop.isRecycled()) {
                rawCrop.recycle();
            }

            snapshot.append("Recognition error\n");

            processNextRegion(
                    source,
                    regionIndex + 1,
                    snapshot);
        }
    }

    private void saveOcrResult(String recognizedText) {

        File output =
                new File(getCacheDir(), "latest_ocr.txt");

        File temporary =
                new File(getCacheDir(), "latest_ocr.tmp");

        try (FileOutputStream stream =
                     new FileOutputStream(temporary)) {

            String value =
                    recognizedText == null ? "" : recognizedText;

            stream.write(
                    value.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8));

            stream.flush();

        } catch (Exception e) {

            Log.e("IdleSoapAI", "Could not write OCR snapshot", e);
            return;
        }

        if (output.exists() && !output.delete()) {

            Log.e(
                    "IdleSoapAI",
                    "Could not replace previous OCR snapshot");

            temporary.delete();
            return;
        }

        if (!temporary.renameTo(output)) {

            Log.e(
                    "IdleSoapAI",
                    "Could not publish OCR snapshot");

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

        stopForeground(true);

        if (textRecognizer != null) {
            textRecognizer.close();
            textRecognizer = null;
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
                  }
