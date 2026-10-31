package com.idlesoap.ai;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions; 

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

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

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;

    private HandlerThread handlerThread;
    private Handler imageHandler;

    private int screenWidth;
    private int screenHeight;
    private long lastSavedTime = 0;
    private TextRecognizer textRecognizer;
private volatile boolean ocrInProgress = false;
private long lastOcrTime = 0;

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

        int resultCode = intent.getIntExtra(
                EXTRA_RESULT_CODE, 0);

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
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);

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
        imageHandler = new Handler(handlerThread.getLooper());
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
        Bitmap bitmap = null;
        Bitmap cropped = null;

        try {
            image = reader.acquireLatestImage();

            if (image == null) {
                return;
            }

            long now = SystemClock.elapsedRealtime();

            if (now - lastSavedTime < 1000) {
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

            bitmap = Bitmap.createBitmap(
                    paddedWidth,
                    screenHeight,
                    Bitmap.Config.ARGB_8888);

            bitmap.copyPixelsFromBuffer(buffer);

            cropped = Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    screenWidth,
                    screenHeight);

            File output = new File(
                    getCacheDir(),
                    "latest_screen.jpg");

            try (FileOutputStream stream =
                         new FileOutputStream(output)) {

                cropped.compress(
                        Bitmap.CompressFormat.JPEG,
                        65,
                        stream);

                stream.flush();
            }

            lastSavedTime = now;
            if (textRecognizer != null &&
        !ocrInProgress &&
        now - lastOcrTime >= 2000) {

    Bitmap ocrFrame =
            cropped.copy(Bitmap.Config.ARGB_8888, false);

    if (ocrFrame != null) {
        lastOcrTime = now;
        runTextRecognition(ocrFrame);
    }
            }

        } catch (Exception e) {
            Log.e("IdleSoapAI", "Frame save failed", e);

        } finally {

            if (cropped != null && cropped != bitmap) {
                cropped.recycle();
            }

            if (bitmap != null) {
                bitmap.recycle();
            }

            if (image != null) {
                image.close();
            }
        }
    }
private void runTextRecognition(Bitmap frame) {

    if (textRecognizer == null || ocrInProgress) {
        frame.recycle();
        return;
    }

    ocrInProgress = true;

    try {
        InputImage input =
                InputImage.fromBitmap(frame, 0);

        textRecognizer.process(input)
                .addOnSuccessListener(result -> {
                    saveOcrResult(result.getText());
                })
                .addOnFailureListener(error -> {
                    Log.e("IdleSoapAI", "OCR failed", error);
                    saveOcrResult(
                            "OCR error: " + error.getMessage());
                })
                .addOnCompleteListener(task -> {
                    ocrInProgress = false;

                    if (!frame.isRecycled()) {
                        frame.recycle();
                    }
                });

    } catch (Exception e) {
        ocrInProgress = false;

        if (!frame.isRecycled()) {
            frame.recycle();
        }

        Log.e("IdleSoapAI", "OCR processing error", e);
        saveOcrResult("OCR error: " + e.getMessage());
    }
}

private void saveOcrResult(String recognizedText) {

    File output = new File(
            getCacheDir(), "latest_ocr.txt");

    try (FileOutputStream stream =
                 new FileOutputStream(output);

         OutputStreamWriter writer =
                 new OutputStreamWriter(
                         stream, StandardCharsets.UTF_8)) {

        if (recognizedText == null ||
                recognizedText.trim().isEmpty()) {
            writer.write("No text detected yet.");
        } else {
            writer.write(recognizedText);
        }

        writer.flush();

    } catch (Exception e) {
        Log.e("IdleSoapAI", "Could not save OCR result", e);
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
