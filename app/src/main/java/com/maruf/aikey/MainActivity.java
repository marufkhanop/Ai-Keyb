package com.maruf.aikey;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final SharedPreferences prefs = getSharedPreferences("AISettings", Context.MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#121214"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 56, 40, 56);
        sv.addView(root);
        setContentView(sv);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("Gboard AI Pro Settings");
        tvTitle.setTextSize(24);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setPadding(0, 0, 0, 8);
        root.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("Configure model endpoints, API keys, and layout preferences.");
        tvSub.setTextSize(13);
        tvSub.setTextColor(Color.parseColor("#9CA3AF"));
        tvSub.setPadding(0, 0, 0, 32);
        root.addView(tvSub);

        Button btnEnable = createPillButton("1. Enable Keyboard in System Settings", "#4F46E5");
        btnEnable.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        root.addView(btnEnable);

        // Keyboard Height Resizer Slider
        final TextView tvHeight = new TextView(this);
        int savedHeight = prefs.getInt("key_height", 50);
        tvHeight.setText("Keyboard Row Height: " + savedHeight + " dp");
        tvHeight.setTextColor(Color.WHITE);
        tvHeight.setTextSize(14);
        tvHeight.setPadding(0, 24, 0, 8);
        root.addView(tvHeight);

        SeekBar sbHeight = new SeekBar(this);
        sbHeight.setMax(30); // 40 to 70 range
        sbHeight.setProgress(savedHeight - 40);
        sbHeight.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int val = 40 + progress;
                tvHeight.setText("Keyboard Row Height: " + val + " dp");
                prefs.edit().putInt("key_height", val).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(sbHeight);

        // Configuration Inputs
        final EditText etEndpoint = createInputField("Endpoint URL (OpenRouter/OpenAI)", prefs.getString("endpoint", "https://openrouter.ai/api/v1/chat/completions"));
        root.addView(etEndpoint);

        final EditText etApiKey = createInputField("API Key (sk-or-...)", prefs.getString("api_key", ""));
        root.addView(etApiKey);

        final EditText etModel = createInputField("Model Name", prefs.getString("model", "google/gemma-2-9b-it:free"));
        root.addView(etModel);

        Button btnSave = createPillButton("Save Configuration", "#2563EB");
        btnSave.setOnClickListener(v -> {
            prefs.edit()
                .putString("endpoint", etEndpoint.getText().toString().trim())
                .putString("api_key", etApiKey.getText().toString().trim())
                .putString("model", etModel.getText().toString().trim())
                .apply();
            Toast.makeText(MainActivity.this, "Settings Saved Successfully!", Toast.LENGTH_SHORT).show();
        });
        root.addView(btnSave);
    }

    private Button createPillButton(String text, String colorHex) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setPadding(24, 28, 24, 28);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(colorHex));
        gd.setCornerRadius(24);
        b.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 14, 0, 14);
        b.setLayoutParams(lp);
        return b;
    }

    private EditText createInputField(String hint, String value) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(Color.parseColor("#6B7280"));
        et.setText(value);
        et.setTextColor(Color.WHITE);
        et.setTextSize(14);
        et.setPadding(24, 24, 24, 24);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#1F2024"));
        gd.setStroke(1, Color.parseColor("#374151"));
        gd.setCornerRadius(16);
        et.setBackground(gd);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 10, 0, 10);
        et.setLayoutParams(lp);
        return et;
    }
}