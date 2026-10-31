package com.idlesoap.ai;

import android.content.ClipboardManager;
import android.content.ClipData;
import android.widget.Toast;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int SCREEN_CAPTURE_REQUEST = 1001;

    private TextView status;
    private TextView recognizedText;
    private ImageView preview;

    private Bitmap currentBitmap;
    private boolean displayFrozen = false;

private String latestRecognizedText =
        "Waiting for screen recognition...";

    private Handler handler = new Handler();

    private long lastScreenModified = -1;
    private long lastScreenLength = -1;
    private long lastOcrModified = -1;

    private final Runnable previewUpdater = new Runnable() {
        @Override
        public void run() {
            updateScreenPreview();
            updateRecognizedText();

            handler.postDelayed(this, 1000);
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
        title.setTextColor(0xFFFFFFFF);
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
        status.setTextColor(0xFFFFFFFF);
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
        previewTitle.setTextColor(0xFFFFFFFF);
        previewTitle.setTextSize(17);
        previewTitle.setPadding(0, dp(24), 0, dp(10));
        layout.addView(previewTitle);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xFF252B38);

        LinearLayout.LayoutParams imageParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(260));

        layout.addView(preview, imageParams);

        TextView ocrTitle = new TextView(this);
        ocrTitle.setText("WHAT THE AI CAN READ");
        ocrTitle.setTextColor(0xFFFFFFFF);
        ocrTitle.setTextSize(17);
        ocrTitle.setPadding(0, dp(24), 0, dp(10));
        layout.addView(ocrTitle);

        recognizedText = new TextView(this);
        recognizedText.setText("Waiting for screen recognition...");
        recognizedText.setTextColor(0xFFFFFFFF);
        recognizedText.setTextSize(14);
        recognizedText.setTextIsSelectable(true);
        recognizedText.setPadding(dp(12), dp(12), dp(12), dp(12));
        recognizedText.setBackgroundColor(0xFF252B38);

        layout.addView(
                recognizedText,
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
        status.setText(
                "Display frozen. OCR continues running.");
    } else {
        freezeButton.setText("FREEZE TEXT");
        recognizedText.setText(latestRecognizedText);
        status.setText("Live recognition resumed.");
    }
});

copyButton.setOnClickListener(v -> {

    String text = recognizedText.getText().toString();

    ClipboardManager clipboard =
            (ClipboardManager)
            getSystemService(CLIPBOARD_SERVICE);

    clipboard.setPrimaryClip(
            ClipData.newPlainText("Idle Soap AI OCR", text));

    Toast.makeText(
            this,
            "Recognition text copied!",
            Toast.LENGTH_SHORT).show();
});
        

        startButton.setOnClickListener(v -> {

            File oldOcr =
                    new File(getCacheDir(), "latest_ocr.txt");

            oldOcr.delete();

            lastOcrModified = -1;

            status.setText("Waiting for Android permission...");

            MediaProjectionManager manager =
                    (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);

            Intent captureIntent =
                    manager.createScreenCaptureIntent();

            startActivityForResult(
                    captureIntent,
                    SCREEN_CAPTURE_REQUEST);
        });

        stopButton.setOnClickListener(v -> {

            Intent stopIntent =
                    new Intent(this, ScreenCaptureService.class);

            stopIntent.setAction(
                    ScreenCaptureService.ACTION_STOP);

            stopService(stopIntent);

            status.setText("Screen capture stopped.");
        });

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(layout);
        setContentView(scrollView);

        handler.post(previewUpdater);
    }

    private void updateScreenPreview() {

        File file =
                new File(getCacheDir(), "latest_screen.jpg");

        if (!file.exists()) {
            return;
        }

        long modified = file.lastModified();
        long length = file.length();

        if (modified != lastScreenModified ||
                length != lastScreenLength) {

            Bitmap bitmap =
                    BitmapFactory.decodeFile(file.getAbsolutePath());

            if (bitmap != null) {

                Bitmap oldBitmap = currentBitmap;

                currentBitmap = bitmap;
                preview.setImageBitmap(bitmap);

                if (oldBitmap != null &&
                        !oldBitmap.isRecycled()) {
                    oldBitmap.recycle();
                }

                lastScreenModified = modified;
                lastScreenLength = length;
            }
        }
    }

    private void updateRecognizedText() {

    File file =
            new File(getCacheDir(), "latest_ocr.txt");

    if (!file.exists()) {
        return;
    }

    long modified = file.lastModified();

    if (modified == lastOcrModified) {
        return;
    }

    StringBuilder result = new StringBuilder();

    try (BufferedReader reader =
                 new BufferedReader(
                         new InputStreamReader(
                                 new FileInputStream(file),
                                 StandardCharsets.UTF_8))) {

        String line;

        while ((line = reader.readLine()) != null) {
            result.append(line).append('\n');
        }

        latestRecognizedText =
                result.length() == 0
                        ? "No text detected yet."
                        : result.toString();

        if (!displayFrozen) {
            recognizedText.setText(latestRecognizedText);
        }

        lastOcrModified = modified;

    } catch (Exception e) {

        status.setText(
                "Could not read OCR result: " + e.getMessage());
    }
    }

    private int dp(int value) {
        return (int) (value *
                getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode, resultCode, data);

        if (requestCode == SCREEN_CAPTURE_REQUEST) {

            if (resultCode == RESULT_OK && data != null) {

                Intent serviceIntent =
                        new Intent(this, ScreenCaptureService.class);

                serviceIntent.setAction(
                        ScreenCaptureService.ACTION_START);

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

                    status.setText(
                            "Capture started. Waiting for OCR...");

                    handler.removeCallbacks(previewUpdater);
                    handler.post(previewUpdater);

                } catch (Exception e) {

                    status.setText(
                            "Could not start capture: " +
                                    e.getMessage());
                }

            } else {

                status.setText(
                        "Screen capture permission was not granted.");
            }
        }
    }

    @Override
    protected void onDestroy() {

        handler.removeCallbacks(previewUpdater);

        if (currentBitmap != null &&
                !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
            currentBitmap = null;
        }

        super.onDestroy();
    }
}
