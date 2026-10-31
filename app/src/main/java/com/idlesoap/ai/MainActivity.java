package com.idlesoap.ai;

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

import java.io.File;

public class MainActivity extends Activity {

    private static final int SCREEN_CAPTURE_REQUEST = 1001;

    private TextView status;
    private ImageView preview;
    private Handler handler = new Handler();
    private long lastModified = 0;

    private final Runnable previewUpdater = new Runnable() {
        @Override
        public void run() {
            File file = new File(
                    getCacheDir(), "latest_screen.jpg");

            if (file.exists() &&
                    file.lastModified() != lastModified) {

                Bitmap bitmap =
                        BitmapFactory.decodeFile(file.getAbsolutePath());

                if (bitmap != null) {
                    preview.setImageBitmap(bitmap);
                    lastModified = file.lastModified();
                    status.setText(
                            "Capture active. Latest screen frame received.");
                }
            }

            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(24, 32, 24, 24);
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
        subtitle.setPadding(0, 12, 0, 20);
        layout.addView(subtitle);

        status = new TextView(this);
        status.setText("Screen capture is not active.");
        status.setTextSize(16);
        status.setTextColor(0xFFFFFFFF);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 12, 0, 20);
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
        previewTitle.setTextSize(16);
        previewTitle.setPadding(0, 24, 0, 12);
        layout.addView(previewTitle);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackgroundColor(0xFF252B38);

        LinearLayout.LayoutParams imageParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 600);

        layout.addView(preview, imageParams);

        startButton.setOnClickListener(v -> {
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

            stopService(stopIntent);

            status.setText("Screen capture stopped.");
        });

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(layout);
        setContentView(scrollView);
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
                            "Permission granted. Starting capture...");

                    lastModified = 0;
                    handler.removeCallbacks(previewUpdater);
                    handler.post(previewUpdater);

                } catch (Exception e) {
                    status.setText(
                            "Could not start capture: " + e.getMessage());
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
        super.onDestroy();
    }
            }
