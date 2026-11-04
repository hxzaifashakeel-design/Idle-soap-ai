package com.idlesoap.ai;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int SCREEN_CAPTURE_REQUEST = 1001;

    private TextView status;
    private TextView recognizedText;
    private TextView gameStateText;
    private TextView captureInfo;
    private ImageView preview;
    private OcrRegionOverlay regionOverlay;

    private Bitmap currentBitmap;
    private boolean displayFrozen = false;
    private boolean showOcrRegions = false;
    private boolean previewDecodePending = false;
    private volatile boolean activityDestroyed = false;

    private String latestRecognizedText =
            "Waiting for screen recognition...";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService previewDecodeExecutor =
            Executors.newSingleThreadExecutor();

    private long lastScreenModified = -1L;
    private long lastScreenLength = -1L;
    private long lastOcrModified = -1L;
    private long lastCaptureInfoModified = -1L;

    private final Runnable previewUpdater = new Runnable() {
        @Override
        public void run() {
            if (activityDestroyed) {
                return;
            }

            updateScreenPreview();
            updateRecognizedText();
            updateCaptureDiagnostics();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(24), dp(20), dp(24));
        layout.setBackgroundColor(0xFF141823);

        TextView title = new TextView(this);
        title.setText("IDLE SOAP AI");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("SCREEN RECOGNITION - PHASE 3");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xFFBBBBBB);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(10), 0, dp(18));
        layout.addView(subtitle);

        status = new TextView(this);
        status.setText("Screen capture is not active.");
        status.setTextSize(16);
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(12), 0, dp(16));
        layout.addView(status);

        Button startButton = new Button(this);
        startButton.setText("START SCREEN CAPTURE");
        layout.addView(startButton);

        Button stopButton = new Button(this);
        stopButton.setText("STOP SCREEN CAPTURE");
        layout.addView(stopButton);

        TextView previewTitle = new TextView(this);
        previewTitle.setText("LATEST CAPTURED FRAME");
        previewTitle.setTextColor(Color.WHITE);
        previewTitle.setTextSize(17);
        previewTitle.setPadding(0, dp(24), 0, dp(10));
        layout.addView(previewTitle);

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(0xFF252B38);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xFF252B38);
        previewFrame.addView(
                preview,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        regionOverlay = new OcrRegionOverlay(this);
        regionOverlay.setVisibility(View.GONE);
        regionOverlay.setClickable(false);
        regionOverlay.setFocusable(false);
        previewFrame.addView(
                regionOverlay,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        layout.addView(
                previewFrame,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(260)));

        Button regionToggleButton = new Button(this);
        regionToggleButton.setText("SHOW OCR REGIONS");
        layout.addView(regionToggleButton);
        regionToggleButton.setOnClickListener(v -> {
            showOcrRegions = !showOcrRegions;
            regionOverlay.setVisibility(
                    showOcrRegions ? View.VISIBLE : View.GONE);
            regionToggleButton.setText(
                    showOcrRegions ? "HIDE OCR REGIONS" : "SHOW OCR REGIONS");
            regionOverlay.invalidate();
        });

        captureInfo = new TextView(this);
        captureInfo.setText("Waiting for capture diagnostics...");
        captureInfo.setTextColor(Color.WHITE);
        captureInfo.setTextSize(13);
        captureInfo.setPadding(dp(12), dp(10), dp(12), dp(10));
        captureInfo.setBackgroundColor(0xFF252B38);
        layout.addView(
                captureInfo,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView ocrTitle = new TextView(this);
        ocrTitle.setText("WHAT THE AI CAN READ");
        ocrTitle.setTextColor(Color.WHITE);
        ocrTitle.setTextSize(17);
        ocrTitle.setPadding(0, dp(24), 0, dp(10));
        layout.addView(ocrTitle);

        recognizedText = new TextView(this);
        recognizedText.setText("Waiting for screen recognition...");
        recognizedText.setTextColor(Color.WHITE);
        recognizedText.setTextSize(14);
        recognizedText.setTextIsSelectable(true);
        recognizedText.setPadding(dp(12), dp(12), dp(12), dp(12));
        recognizedText.setBackgroundColor(0xFF252B38);
        layout.addView(
                recognizedText,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView gameStateTitle = new TextView(this);
        gameStateTitle.setText("DETECTED GAME STATE");
        gameStateTitle.setTextColor(Color.WHITE);
        gameStateTitle.setTextSize(18);
        gameStateTitle.setPadding(0, dp(24), 0, dp(10));
        layout.addView(gameStateTitle);

        gameStateText = new TextView(this);
        gameStateText.setText("Waiting for game information...");
        gameStateText.setTextColor(Color.WHITE);
        gameStateText.setTextSize(15);
        gameStateText.setPadding(dp(12), dp(12), dp(12), dp(12));
        gameStateText.setBackgroundColor(0xFF252B38);
        layout.addView(
                gameStateText,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        Button freezeButton = new Button(this);
        freezeButton.setText("FREEZE TEXT");
        Button copyButton = new Button(this);
        copyButton.setText("COPY TEXT");

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f);
        controls.addView(freezeButton, buttonParams);
        controls.addView(copyButton, buttonParams);
        layout.addView(controls);

        freezeButton.setOnClickListener(v -> {
            displayFrozen = !displayFrozen;
            if (displayFrozen) {
                freezeButton.setText("RESUME LIVE");
                status.setText("Display frozen. OCR continues running.");
            } else {
                freezeButton.setText("FREEZE TEXT");
                recognizedText.setText(latestRecognizedText);
                gameStateText.setText(
                        GameStateParser.createSummary(latestRecognizedText));
                status.setText("Live recognition resumed.");
            }
        });

        copyButton.setOnClickListener(v -> {
            String text = recognizedText.getText().toString();
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(
                    ClipData.newPlainText("Idle Soap AI OCR", text));
            Toast.makeText(
                    this,
                    "Recognition text copied!",
                    Toast.LENGTH_SHORT).show();
        });

        startButton.setOnClickListener(v -> {
            File oldOcr = new File(getCacheDir(), "latest_ocr.txt");
            oldOcr.delete();
            lastOcrModified = -1L;
            lastCaptureInfoModified = -1L;
            status.setText("Waiting for Android permission...");

            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(
                            Context.MEDIA_PROJECTION_SERVICE);
            Intent captureIntent = manager.createScreenCaptureIntent();
            startActivityForResult(captureIntent, SCREEN_CAPTURE_REQUEST);
        });

        stopButton.setOnClickListener(v -> {
            Intent stopIntent = new Intent(this, ScreenCaptureService.class);
            stopIntent.setAction(ScreenCaptureService.ACTION_STOP);
            stopService(stopIntent);
            status.setText("Screen capture stopped. Last frame is retained.");
        });

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(layout);
        setContentView(scrollView);

        handler.post(previewUpdater);
    }

    private void updateScreenPreview() {
        if (activityDestroyed || previewDecodePending) {
            return;
        }

        File file = new File(getCacheDir(), "latest_screen.jpg");
        if (!file.exists()) {
            return;
        }

        final long modified = file.lastModified();
        final long length = file.length();
        if (modified == lastScreenModified && length == lastScreenLength) {
            return;
        }

        previewDecodePending = true;
        try {
            previewDecodeExecutor.execute(() -> {
                Bitmap decodedBitmap = BitmapFactory.decodeFile(
                        file.getAbsolutePath());

                boolean posted = handler.post(() -> {
                    previewDecodePending = false;
                    if (activityDestroyed) {
                        if (decodedBitmap != null && !decodedBitmap.isRecycled()) {
                            decodedBitmap.recycle();
                        }
                        return;
                    }

                    if (decodedBitmap != null) {
                        Bitmap oldBitmap = currentBitmap;
                        currentBitmap = decodedBitmap;
                        preview.setImageBitmap(decodedBitmap);
                        regionOverlay.setSourceSize(
                                decodedBitmap.getWidth(),
                                decodedBitmap.getHeight());

                        if (oldBitmap != null && !oldBitmap.isRecycled()) {
                            oldBitmap.recycle();
                        }

                        lastScreenModified = modified;
                        lastScreenLength = length;
                    }
                });

                if (!posted && decodedBitmap != null && !decodedBitmap.isRecycled()) {
                    decodedBitmap.recycle();
                }
            });
        } catch (RuntimeException e) {
            previewDecodePending = false;
            status.setText("Preview error: " + e.getMessage());
        }
    }

    private void updateCaptureDiagnostics() {
        File file = new File(getCacheDir(), "latest_capture_info.txt");
        if (!file.exists()) {
            return;
        }

        long modified = file.lastModified();
        if (modified == lastCaptureInfoModified) {
            return;
        }

        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(file),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
            }
            captureInfo.setText(result.length() == 0
                    ? "No capture diagnostics available."
                    : result.toString());
            lastCaptureInfoModified = modified;
        } catch (Exception e) {
            captureInfo.setText("Could not read capture diagnostics: " + e.getMessage());
        }
    }

    private void updateRecognizedText() {
        File file = new File(getCacheDir(), "latest_ocr.txt");
        if (!file.exists()) {
            return;
        }

        long modified = file.lastModified();
        if (modified == lastOcrModified) {
            return;
        }

        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(file),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append('\n');
            }

            latestRecognizedText = result.length() == 0
                    ? "No text detected yet."
                    : result.toString();

            if (!displayFrozen) {
                recognizedText.setText(latestRecognizedText);
                gameStateText.setText(
                        GameStateParser.createSummary(latestRecognizedText));
            }
            lastOcrModified = modified;
        } catch (Exception e) {
            status.setText("Could not read OCR result: " + e.getMessage());
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SCREEN_CAPTURE_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                serviceIntent.setAction(ScreenCaptureService.ACTION_START);
                serviceIntent.putExtra(
                        ScreenCaptureService.EXTRA_RESULT_CODE,
                        resultCode);
                serviceIntent.putExtra(
                        ScreenCaptureService.EXTRA_RESULT_DATA,
                        data);

                try {
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }

                    status.setText("Capture started. Waiting for OCR...");
                    handler.removeCallbacks(previewUpdater);
                    handler.post(previewUpdater);
                } catch (Exception e) {
                    status.setText("Could not start capture: " + e.getMessage());
                }
            } else {
                status.setText("Screen capture permission was not granted.");
            }
        }
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        handler.removeCallbacks(previewUpdater);
        previewDecodeExecutor.shutdownNow();

        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
            currentBitmap = null;
        }

        super.onDestroy();
    }

    /** Draws normalized OCR regions over the same FIT_CENTER image content. */
    private static final class OcrRegionOverlay extends View {
        private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final int[] colors = {
                Color.rgb(255, 70, 70),
                Color.rgb(255, 170, 40),
                Color.rgb(80, 220, 255),
                Color.rgb(180, 120, 255),
                Color.rgb(255, 90, 190),
                Color.rgb(255, 235, 70),
                Color.rgb(80, 230, 120),
                Color.rgb(90, 160, 255),
                Color.rgb(255, 145, 70)
        };

        private int sourceWidth;
        private int sourceHeight;
        private final float density;

        OcrRegionOverlay(Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            boxPaint.setStyle(Paint.Style.STROKE);
            boxPaint.setStrokeWidth(2.5f * density);

            labelBackgroundPaint.setStyle(Paint.Style.FILL);
            labelBackgroundPaint.setColor(0xCC101010);

            labelTextPaint.setStyle(Paint.Style.FILL);
            labelTextPaint.setColor(Color.WHITE);
            labelTextPaint.setTextSize(10f * density);
            labelTextPaint.setFakeBoldText(true);
        }

        void setSourceSize(int width, int height) {
            sourceWidth = width;
            sourceHeight = height;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (sourceWidth <= 0 || sourceHeight <= 0
                    || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }

            float scale = Math.min(
                    (float) getWidth() / sourceWidth,
                    (float) getHeight() / sourceHeight);
            float drawnWidth = sourceWidth * scale;
            float drawnHeight = sourceHeight * scale;
            float offsetX = (getWidth() - drawnWidth) / 2f;
            float offsetY = (getHeight() - drawnHeight) / 2f;

            ScreenCaptureService.OcrRegion[] regions =
                    ScreenCaptureService.OCR_REGIONS;

            for (int i = 0; i < regions.length; i++) {
                ScreenCaptureService.OcrRegion region = regions[i];

                float left = offsetX + region.left * drawnWidth;
                float top = offsetY + region.top * drawnHeight;
                float right = offsetX + region.right * drawnWidth;
                float bottom = offsetY + region.bottom * drawnHeight;

                boxPaint.setColor(colors[i % colors.length]);
                RectF rectangle = new RectF(left, top, right, bottom);
                canvas.drawRect(rectangle, boxPaint);

                String label = region.name;
                float horizontalPadding = 4f * density;
                float labelHeight = 15f * density;
                float labelWidth = labelTextPaint.measureText(label)
                        + horizontalPadding * 2f;
                float labelTop = Math.max(offsetY, top - labelHeight);
                float labelBottom = labelTop + labelHeight;

                labelBackgroundPaint.setColor(0xCC101010);
                canvas.drawRect(
                        left,
                        labelTop,
                        left + labelWidth,
                        labelBottom,
                        labelBackgroundPaint);
                labelTextPaint.setColor(colors[i % colors.length]);
                canvas.drawText(
                        label,
                        left + horizontalPadding,
                        labelTop + 11f * density,
                        labelTextPaint);
            }
        }
    }
}
