package com.idlesoap.ai;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(32, 32, 32, 32);
        layout.setBackgroundColor(Color.rgb(20, 24, 35));

        TextView title = new TextView(this);
        title.setText("IDLE SOAP AI");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView status = new TextView(this);
        status.setText(
            "AI Agent Setup\n\n" +
            "Phase 1: App foundation\n" +
            "Status: Ready for development\n\n" +
            "The game-control modules aren't active yet."
        );
        status.setTextSize(16);
        status.setTextColor(Color.LTGRAY);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 32, 0, 32);
        layout.addView(status);

        Button startButton = new Button(this);
        startButton.setText("START SETUP");
        layout.addView(startButton);

        startButton.setOnClickListener(v -> {
            status.setText(
                "Setup started!\n\n" +
                "Next: screen recognition and Android permissions."
            );
            startButton.setEnabled(false);
        });

        setContentView(layout);
    }
}
