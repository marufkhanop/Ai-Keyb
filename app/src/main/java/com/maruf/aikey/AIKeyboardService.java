package com.maruf.aikey;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AIKeyboardService extends InputMethodService {

    private LinearLayout root, toolbar, aiTray, overlayClipboard, keysDeck;
    private TextView tvAiPrompt, tvAiResult, tvAiStatus;
    private Button btnCopyOnly, btnInsertHost;
    private Handler mainHandler;
    private Vibrator vibrator;
    private AudioManager audioManager;
    private PopupWindow symbolPopup;

    private boolean isShifted = false;
    private boolean isCapsLock = false;
    private long lastShiftClick = 0;
    private int deckMode = 0; // 0=Alpha, 1=Symbols 1, 2=Symbols 2
    private boolean isAiInputFocused = false;
    private int rowHeightDp = 50;

    // Keys Layout Definition
    private static final String[] R1_CHARS = {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"};
    private static final String[] R1_SUBS  = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};

    private static final String[] R2_CHARS = {"a", "s", "d", "f", "g", "h", "j", "k", "l"};
    private static final String[] R2_SUBS  = {"@", "#", "$", "%", "&", "-", "+", "(", ")"};

    private static final String[] R3_CHARS = {"z", "x", "c", "v", "b", "n", "m"};
    private static final String[] R3_SUBS  = {"*", "\"", "'", ":", ";", "!", "?"};

    private static final String[] SYM1_R1 = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
    private static final String[] SYM1_R2 = {"@", "#", "৳", "%", "&", "*", "-", "+", "(", ")"};
    private static final String[] SYM1_R3 = {"!", "\"", "'", ":", ";", "/", "?"};

    private static final String[] SYM2_R1 = {"~", "`", "|", "•", "√", "π", "÷", "×", "{", "}"};
    private static final String[] SYM2_R2 = {"£", "¥", "€", "¢", "^", "°", "=", "{", "}"};
    private static final String[] SYM2_R3 = {"\", "<", ">", "[", "]", "©", "®"};

    @Override
    public View onCreateInputView() {
        mainHandler = new Handler(Looper.getMainLooper());
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        SharedPreferences prefs = getSharedPreferences("AISettings", Context.MODE_PRIVATE);
        rowHeightDp = prefs.getInt("key_height", 50);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#1C1B1F"));

        buildToolbar();
        buildAiTray();
        buildClipboardOverlay();

        keysDeck = new LinearLayout(this);
        keysDeck.setOrientation(LinearLayout.VERTICAL);
        keysDeck.setPadding(6, 4, 6, 8);
        root.addView(keysDeck);

        renderKeyboard();
        listenToClipboardEvents();

        return root;
    }

    private void playFeedback() {
        try {
            if (vibrator != null) vibrator.vibrate(10);
            if (audioManager != null) audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.25f);
        } catch (Exception ignored) {}
    }

    private void buildToolbar() {
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(46)));
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(Color.parseColor("#1C1B1F"));
        toolbar.setPadding(dp(8), 0, dp(8), 0);

        toolbar.addView(createToolButton("📋", v -> toggleClipboardOverlay()));
        toolbar.addView(createToolButton("⚡", v -> toggleAiTray()));
        toolbar.addView(createToolButton("⚙", v -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }));

        tvAiStatus = new TextView(this);
        tvAiStatus.setTextColor(Color.parseColor("#938F99"));
        tvAiStatus.setTextSize(11);
        tvAiStatus.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1.0f);
        lp.setMargins(0, 0, dp(8), 0);
        tvAiStatus.setLayoutParams(lp);
        toolbar.addView(tvAiStatus);

        root.addView(toolbar);
    }

    private Button createToolButton(String symbol, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(symbol);
        b.setTextColor(Color.parseColor("#E6E1E5"));
        b.setTextSize(14);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        b.setOnClickListener(l);
        return b;
    }

    private void buildAiTray() {
        aiTray = new LinearLayout(this);
        aiTray.setOrientation(LinearLayout.VERTICAL);
        aiTray.setBackgroundColor(Color.parseColor("#211F26"));
        aiTray.setPadding(dp(10), dp(8), dp(10), dp(8));
        aiTray.setVisibility(View.GONE);

        // Target Prompt Input Field
        tvAiPrompt = new TextView(this);
        tvAiPrompt.setHint("Tap to type inside keyboard: (e.g. text #a angry)");
        tvAiPrompt.setHintTextColor(Color.parseColor("#79747E"));
        tvAiPrompt.setTextColor(Color.WHITE);
        tvAiPrompt.setTextSize(13);
        tvAiPrompt.setBackground(makeDrawable("#2B2930", "#36343B", 1, dp(8)));
        tvAiPrompt.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvAiPrompt.setOnClickListener(v -> {
            isAiInputFocused = true;
            tvAiPrompt.setBackground(makeDrawable("#2B2930", "#4D8EFF", 2, dp(8)));
        });
        aiTray.addView(tvAiPrompt);

        // Tone chips strip
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(0, dp(6), 0, dp(6));

        String[] emotions = {"polite", "angry", "happy", "witty", "professional", "apologetic", "sarcastic"};
        for (String emo : emotions) {
            final String tag = "#a " + emo;
            Button chip = new Button(this);
            chip.setText(tag);
            chip.setTextSize(11);
            chip.setTextColor(Color.parseColor("#938F99"));
            chip.setBackground(makeDrawable("#2B2930", "#36343B", 1, dp(14)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
            lp.setMargins(0, 0, dp(6), 0);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                String cur = tvAiPrompt.getText().toString();
                cur = cur.replaceAll("(?i)#a\\s*[a-zA-Z0-9_-]+", "").trim();
                tvAiPrompt.setText(cur + " " + tag);
            });
            chipRow.addView(chip);
        }
        chipScroll.addView(chipRow);
        aiTray.addView(chipScroll);

        // Control Buttons
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(0, dp(4), 0, dp(4));

        Button btnPaste = createActionButton("Paste", v -> pasteClipboardToAiPrompt());
        Button btnClear = createActionButton("Clear", v -> tvAiPrompt.setText(""));
        Button btnRun = createActionButton("⚡ Run", v -> executeAiSynthesis());
        btnRun.setBackground(makeDrawable("#4D8EFF", "#4D8EFF", 0, dp(6)));

        controls.addView(btnPaste);
        controls.addView(btnClear);
        controls.addView(btnRun);
        aiTray.addView(controls);

        // Result Container
        tvAiResult = new TextView(this);
        tvAiResult.setTextColor(Color.parseColor("#93C5FD"));
        tvAiResult.setTextSize(13);
        tvAiResult.setBackground(makeDrawable("#2B2930", "#48464C", 1, dp(8)));
        tvAiResult.setPadding(dp(12), dp(10), dp(12), dp(10));
        tvAiResult.setVisibility(View.GONE);
        aiTray.addView(tvAiResult);

        LinearLayout resActions = new LinearLayout(this);
        resActions.setOrientation(LinearLayout.HORIZONTAL);
        resActions.setGravity(Gravity.END);
        resActions.setPadding(0, dp(6), 0, dp(2));

        btnCopyOnly = createActionButton("Copy", v -> {
            copyTextToClipboard(tvAiResult.getText().toString());
            tvAiStatus.setText("Copied to clipboard");
        });
        btnInsertHost = createActionButton("Insert & Close", v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null && tvAiResult.getText().length() > 0) {
                ic.commitText(tvAiResult.getText().toString(), 1);
            }
            toggleAiTray();
        });
        btnInsertHost.setBackground(makeDrawable("#4D8EFF", "#4D8EFF", 0, dp(6)));

        resActions.addView(btnCopyOnly);
        resActions.addView(btnInsertHost);
        resActions.setVisibility(View.GONE);
        aiTray.addView(resActions);

        root.addView(aiTray);
    }

    private Button createActionButton(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setBackground(makeDrawable("#36343B", "#48464C", 1, dp(6)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1.0f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void toggleAiTray() {
        playFeedback();
        if (aiTray.getVisibility() == View.VISIBLE) {
            aiTray.setVisibility(View.GONE);
            isAiInputFocused = false;
        } else {
            overlayClipboard.setVisibility(View.GONE);
            aiTray.setVisibility(View.VISIBLE);
            isAiInputFocused = true;
            tvAiPrompt.setBackground(makeDrawable("#2B2930", "#4D8EFF", 2, dp(8)));
        }
    }

    private void pasteClipboardToAiPrompt() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
            if (cs != null) tvAiPrompt.setText(cs.toString());
        }
    }

    private void executeAiSynthesis() {
        playFeedback();
        String raw = tvAiPrompt.getText().toString().trim();
        if (raw.isEmpty()) return;

        String emotion = "polite and humanized";
        Pattern pattern = Pattern.compile("(?i)#a(?:\\s+([a-zA-Z0-9_-]+))?$");
        Matcher m = pattern.matcher(raw);
        String cleanPrompt = raw;

        if (m.find()) {
            if (m.group(1) != null) emotion = m.group(1).trim();
            cleanPrompt = raw.substring(0, m.start()).trim();
        }

        final String systemInstruction = "You are an authentic person typing inside a casual messaging conversation. "
            + "Rewrite or generate the reply reflecting a completely natural, humanized, " + emotion + " emotion. "
            + "Do not use conversational filler quotes, introductions, or AI disclaimers. Output the exact message only.";

        tvAiStatus.setText("Thinking...");
        tvAiResult.setVisibility(View.GONE);
        aiTray.getChildAt(aiTray.getChildCount() - 1).setVisibility(View.GONE);

        SharedPreferences prefs = getSharedPreferences("AISettings", Context.MODE_PRIVATE);
        String endpoint = prefs.getString("endpoint", "https://openrouter.ai/api/v1/chat/completions");
        String apiKey = prefs.getString("api_key", "");
        String model = prefs.getString("model", "google/gemma-2-9b-it:free");

        if (apiKey.isEmpty()) {
            tvAiStatus.setText("Key Missing");
            tvAiResult.setText("Please open Settings and enter your API Key.");
            tvAiResult.setVisibility(View.VISIBLE);
            return;
        }

        final String finalQuery = cleanPrompt;
        new Thread(() -> {
            try {
                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(20000);

                JSONObject body = new JSONObject();
                body.put("model", model);
                JSONArray msgs = new JSONArray();

                JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content", systemInstruction);
                msgs.put(sys);

                JSONObject usr = new JSONObject();
                usr.put("role", "user");
                usr.put("content", finalQuery);
                msgs.put(usr);

                body.put("messages", msgs);

                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) sb.append(line);
                    in.close();

                    JSONObject json = new JSONObject(sb.toString());
                    final String answer = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

                    mainHandler.post(() -> {
                        tvAiStatus.setText("Ready");
                        tvAiResult.setText(answer.trim());
                        tvAiResult.setVisibility(View.VISIBLE);
                        aiTray.getChildAt(aiTray.getChildCount() - 1).setVisibility(View.VISIBLE);
                    });
                } else {
                    mainHandler.post(() -> {
                        tvAiStatus.setText("Error");
                        tvAiResult.setText("HTTP Error " + code);
                        tvAiResult.setVisibility(View.VISIBLE);
                    });
                }
            } catch (Exception e) {
                mainHandler.post(() -> {
                    tvAiStatus.setText("Failed");
                    tvAiResult.setText(e.getMessage() != null ? e.getMessage() : "Network error");
                    tvAiResult.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    private void buildClipboardOverlay() {
        overlayClipboard = new LinearLayout(this);
        overlayClipboard.setOrientation(LinearLayout.VERTICAL);
        overlayClipboard.setBackgroundColor(Color.parseColor("#1C1B1F"));
        overlayClipboard.setPadding(dp(12), dp(8), dp(12), dp(8));
        overlayClipboard.setVisibility(View.GONE);
        root.addView(overlayClipboard);
    }

    private void toggleClipboardOverlay() {
        playFeedback();
        if (overlayClipboard.getVisibility() == View.VISIBLE) {
            overlayClipboard.setVisibility(View.GONE);
            keysDeck.setVisibility(View.VISIBLE);
        } else {
            aiTray.setVisibility(View.GONE);
            isAiInputFocused = false;
            keysDeck.setVisibility(View.GONE);
            overlayClipboard.setVisibility(View.VISIBLE);
            renderClipboardItems();
        }
    }

    private void renderClipboardItems() {
        overlayClipboard.removeAllViews();
        cleanExpiredClips();

        // Top bar with Bengali banner from screenshot
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("Clipboard");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

        Button btnDone = new Button(this);
        btnDone.setText("Done");
        btnDone.setTextColor(Color.WHITE);
        btnDone.setTextSize(12);
        btnDone.setBackground(makeDrawable("#2B2930", "#36343B", 1, dp(12)));
        btnDone.setOnClickListener(v -> toggleClipboardOverlay());

        topRow.addView(title);
        topRow.addView(btnDone);
        overlayClipboard.addView(topRow);

        TextView banner = new TextView(this);
        banner.setText("সাম্প্রতিক কপি করা লেখাগুলো ১ ঘণ্টা পর মুছে যাবে। সংরক্ষণ করে রাখতে পিন করুন!");
        banner.setTextColor(Color.parseColor("#79747E"));
        banner.setTextSize(11);
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(0, dp(6), 0, dp(10));
        overlayClipboard.addView(banner);

        ScrollView sv = new ScrollView(this);
        LinearLayout deck = new LinearLayout(this);
        deck.setOrientation(LinearLayout.VERTICAL);

        SharedPreferences sp = getSharedPreferences("AIClips", Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(sp.getString("items", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                final String clipText = obj.getString("text");
                final boolean isPinned = obj.getBoolean("pinned");
                final int idx = i;

                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setBackground(makeDrawable(isPinned ? "#36343B" : "#2B2930", "#48464C", 1, dp(8)));
                card.setPadding(dp(14), dp(12), dp(14), dp(12));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(0, 0, 0, dp(8));
                card.setLayoutParams(lp);

                TextView tvText = new TextView(this);
                tvText.setText((isPinned ? "📌 " : "") + clipText);
                tvText.setTextColor(Color.WHITE);
                tvText.setTextSize(13);
                tvText.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));

                Button btnPin = new Button(this);
                btnPin.setText(isPinned ? "Unpin" : "Pin");
                btnPin.setTextColor(Color.parseColor("#4D8EFF"));
                btnPin.setTextSize(11);
                btnPin.setBackgroundColor(Color.TRANSPARENT);
                btnPin.setOnClickListener(v -> {
                    togglePinClip(idx);
                    renderClipboardItems();
                });

                card.addView(tvText);
                card.addView(btnPin);
                card.setOnClickListener(v -> {
                    typeText(clipText);
                    toggleClipboardOverlay();
                });

                deck.addView(card);
            }
        } catch (Exception ignored) {}

        sv.addView(deck);
        overlayClipboard.addView(sv);
    }

    private void renderKeyboard() {
        keysDeck.removeAllViews();
        int h = dp(rowHeightDp);

        if (deckMode == 0) {
            keysDeck.addView(createDualRow(R1_CHARS, R1_SUBS, h));
            keysDeck.addView(createDualRow(R2_CHARS, R2_SUBS, h));
            keysDeck.addView(createShiftAlphaRow(R3_CHARS, R3_SUBS, h));
            keysDeck.addView(createBottomBar(h));
        } else if (deckMode == 1) {
            keysDeck.addView(createSimpleRow(SYM1_R1, h));
            keysDeck.addView(createSimpleRow(SYM1_R2, h));
            keysDeck.addView(createSymbolShiftRow(SYM1_R3, "=\<", h));
            keysDeck.addView(createBottomBar(h));
        } else {
            keysDeck.addView(createSimpleRow(SYM2_R1, h));
            keysDeck.addView(createSimpleRow(SYM2_R2, h));
            keysDeck.addView(createSymbolShiftRow(SYM2_R3, "?123", h));
            keysDeck.addView(createBottomBar(h));
        }
    }

    private LinearLayout createDualRow(String[] letters, String[] subs, int h) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, h));

        for (int i = 0; i < letters.length; i++) {
            final String ch = letters[i];
            final String sub = subs[i];

            LinearLayout key = new LinearLayout(this);
            key.setOrientation(LinearLayout.VERTICAL);
            key.setGravity(Gravity.CENTER);
            key.setBackground(makeKeycapDrawable("#2B2930"));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1.0f);
            lp.setMargins(dp(2), dp(3), dp(2), dp(3));
            key.setLayoutParams(lp);

            TextView tvSub = new TextView(this);
            tvSub.setText(sub);
            tvSub.setTextSize(9);
            tvSub.setTextColor(Color.parseColor("#79747E"));
            tvSub.setGravity(Gravity.END);
            tvSub.setPadding(0, dp(2), dp(6), 0);
            tvSub.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));

            TextView tvMain = new TextView(this);
            tvMain.setText((isShifted || isCapsLock) ? ch.toUpperCase() : ch.toLowerCase());
            tvMain.setTextSize(17);
            tvMain.setTextColor(Color.WHITE);
            tvMain.setGravity(Gravity.CENTER);

            key.addView(tvSub);
            key.addView(tvMain);

            key.setOnClickListener(v -> {
                playFeedback();
                typeText((isShifted || isCapsLock) ? ch.toUpperCase() : ch.toLowerCase());
                if (isShifted && !isCapsLock) {
                    isShifted = false;
                    renderKeyboard();
                }
            });

            key.setOnLongClickListener(v -> {
                playFeedback();
                showPopupAlternatives(v, sub, ch);
                return true;
            });

            row.addView(key);
        }
        return row;
    }

    private LinearLayout createShiftAlphaRow(String[] letters, String[] subs, int h) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, h));

        Button btnShift = new Button(this);
        btnShift.setText(isCapsLock ? "⇪" : "⇧");
        btnShift.setTextColor(Color.WHITE);
        btnShift.setTextSize(16);
        btnShift.setBackground(makeKeycapDrawable(isCapsLock ? "#4D8EFF" : (isShifted ? "#48464C" : "#211F26")));
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0, -1, 1.5f);
        spLp.setMargins(dp(2), dp(3), dp(2), dp(3));
        btnShift.setLayoutParams(spLp);
        btnShift.setOnClickListener(v -> {
            playFeedback();
            long now = System.currentTimeMillis();
            if (now - lastShiftClick < 320) {
                isCapsLock = !isCapsLock;
                isShifted = isCapsLock;
            } else {
                isCapsLock = false;
                isShifted = !isShifted;
            }
            lastShiftClick = now;
            renderKeyboard();
        });
        row.addView(btnShift);

        LinearLayout mid = createDualRow(letters, subs, h);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 7.0f));
        row.addView(mid);

        Button btnBack = new Button(this);
        btnBack.setText("⌫");
        btnBack.setTextColor(Color.WHITE);
        btnBack.setTextSize(16);
        btnBack.setBackground(makeKeycapDrawable("#211F26"));
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(0, -1, 1.5f);
        delLp.setMargins(dp(2), dp(3), dp(2), dp(3));
        btnBack.setLayoutParams(delLp);
        btnBack.setOnClickListener(v -> deleteText());
        row.addView(btnBack);

        return row;
    }

    private LinearLayout createSimpleRow(String[] chars, int h) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, h));

        for (String c : chars) {
            Button b = new Button(this);
            b.setText(c);
            b.setTextColor(Color.WHITE);
            b.setTextSize(16);
            b.setBackground(makeKeycapDrawable("#2B2930"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1.0f);
            lp.setMargins(dp(2), dp(3), dp(2), dp(3));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                playFeedback();
                typeText(c);
            });
            row.addView(b);
        }
        return row;
    }

    private LinearLayout createSymbolShiftRow(String[] chars, String toggleLabel, int h) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, h));

        Button btnToggle = new Button(this);
        btnToggle.setText(toggleLabel);
        btnToggle.setTextColor(Color.WHITE);
        btnToggle.setTextSize(13);
        btnToggle.setBackground(makeKeycapDrawable("#211F26"));
        LinearLayout.LayoutParams lpT = new LinearLayout.LayoutParams(0, -1, 1.5f);
        lpT.setMargins(dp(2), dp(3), dp(2), dp(3));
        btnToggle.setLayoutParams(lpT);
        btnToggle.setOnClickListener(v -> {
            playFeedback();
            deckMode = (deckMode == 1) ? 2 : 1;
            renderKeyboard();
        });
        row.addView(btnToggle);

        for (String c : chars) {
            Button b = new Button(this);
            b.setText(c);
            b.setTextColor(Color.WHITE);
            b.setTextSize(16);
            b.setBackground(makeKeycapDrawable("#2B2930"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1.0f);
            lp.setMargins(dp(2), dp(3), dp(2), dp(3));
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                playFeedback();
                typeText(c);
            });
            row.addView(b);
        }

        Button btnBack = new Button(this);
        btnBack.setText("⌫");
        btnBack.setTextColor(Color.WHITE);
        btnBack.setTextSize(16);
        btnBack.setBackground(makeKeycapDrawable("#211F26"));
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(0, -1, 1.5f);
        delLp.setMargins(dp(2), dp(3), dp(2), dp(3));
        btnBack.setLayoutParams(delLp);
        btnBack.setOnClickListener(v -> deleteText());
        row.addView(btnBack);

        return row;
    }

    private LinearLayout createBottomBar(int h) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(-1, h));

        Button bMode = new Button(this);
        bMode.setText(deckMode == 0 ? "?123" : "ABC");
        bMode.setTextColor(Color.WHITE);
        bMode.setTextSize(13);
        bMode.setBackground(makeKeycapDrawable("#211F26"));
        LinearLayout.LayoutParams lpM = new LinearLayout.LayoutParams(0, -1, 1.5f);
        lpM.setMargins(dp(2), dp(3), dp(2), dp(3));
        bMode.setLayoutParams(lpM);
        bMode.setOnClickListener(v -> {
            playFeedback();
            deckMode = (deckMode == 0) ? 1 : 0;
            renderKeyboard();
        });
        row.addView(bMode);

        Button bComma = new Button(this);
        bComma.setText(",");
        bComma.setTextColor(Color.WHITE);
        bComma.setTextSize(16);
        bComma.setBackground(makeKeycapDrawable("#211F26"));
        LinearLayout.LayoutParams lpC = new LinearLayout.LayoutParams(0, -1, 1.0f);
        lpC.setMargins(dp(2), dp(3), dp(2), dp(3));
        bComma.setLayoutParams(lpC);
        bComma.setOnClickListener(v -> {
            playFeedback();
            typeText(",");
        });
        bComma.setOnLongClickListener(v -> {
            showPopupAlternatives(v, "&", "%", "+", "-", "(", ")", "#");
            return true;
        });
        row.addView(bComma);

        Button bSpace = new Button(this);
        bSpace.setText("◀  English  ▶");
        bSpace.setTextColor(Color.parseColor("#938F99"));
        bSpace.setTextSize(12);
        bSpace.setBackground(makeKeycapDrawable("#2B2930"));
        LinearLayout.LayoutParams lpS = new LinearLayout.LayoutParams(0, -1, 4.5f);
        lpS.setMargins(dp(2), dp(3), dp(2), dp(3));
        bSpace.setLayoutParams(lpS);
        bSpace.setOnClickListener(v -> {
            playFeedback();
            typeText(" ");
        });
        row.addView(bSpace);

        Button bDot = new Button(this);
        bDot.setText(".");
        bDot.setTextColor(Color.WHITE);
        bDot.setTextSize(16);
        bDot.setBackground(makeKeycapDrawable("#211F26"));
        LinearLayout.LayoutParams lpD = new LinearLayout.LayoutParams(0, -1, 1.0f);
        lpD.setMargins(dp(2), dp(3), dp(2), dp(3));
        bDot.setLayoutParams(lpD);
        bDot.setOnClickListener(v -> {
            playFeedback();
            typeText(".");
        });
        bDot.setOnLongClickListener(v -> {
            showPopupAlternatives(v, "!", "?", "/", ":", ";", "'", "\"");
            return true;
        });
        row.addView(bDot);

        Button bEnter = new Button(this);
        bEnter.setText("↵");
        bEnter.setTextColor(Color.WHITE);
        bEnter.setTextSize(18);
        bEnter.setBackground(makeKeycapDrawable("#4D8EFF"));
        LinearLayout.LayoutParams lpE = new LinearLayout.LayoutParams(0, -1, 1.5f);
        lpE.setMargins(dp(2), dp(3), dp(2), dp(3));
        bEnter.setLayoutParams(lpE);
        bEnter.setOnClickListener(v -> {
            playFeedback();
            typeText("\n");
        });
        row.addView(bEnter);

        return row;
    }

    private void showPopupAlternatives(View anchor, String... alts) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setBackground(makeDrawable("#2B2930", "#48464C", 1, dp(10)));
        layout.setPadding(dp(6), dp(6), dp(6), dp(6));

        symbolPopup = new PopupWindow(layout, -2, -2, true);
        symbolPopup.setOutsideTouchable(true);

        for (String s : alts) {
            Button b = new Button(this);
            b.setText(s);
            b.setTextColor(Color.WHITE);
            b.setTextSize(15);
            b.setBackground(makeDrawable("#36343B", "#36343B", 0, dp(6)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(40));
            lp.setMargins(dp(2), 0, dp(2), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                playFeedback();
                typeText(s);
                symbolPopup.dismiss();
            });
            layout.addView(b);
        }

        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        symbolPopup.showAtLocation(anchor, Gravity.NO_GRAVITY, Math.max(16, loc[0] - dp(40)), loc[1] - dp(56));
    }

    private void typeText(String str) {
        if (isAiInputFocused && aiTray.getVisibility() == View.VISIBLE) {
            tvAiPrompt.append(str);
        } else {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText(str, 1);
        }
    }

    private void deleteText() {
        playFeedback();
        if (isAiInputFocused && aiTray.getVisibility() == View.VISIBLE) {
            String s = tvAiPrompt.getText().toString();
            if (s.length() > 0) tvAiPrompt.setText(s.substring(0, s.length() - 1));
        } else {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.deleteSurroundingText(1, 0);
        }
    }

    private void listenToClipboardEvents() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.addPrimaryClipChangedListener(() -> {
                if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
                    if (cs != null && cs.length() > 0) saveClipToStorage(cs.toString());
                }
            });
        }
    }

    private void copyTextToClipboard(String t) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("AI Text", t));
    }

    private void saveClipToStorage(String text) {
        SharedPreferences sp = getSharedPreferences("AIClips", Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(sp.getString("items", "[]"));
            JSONObject obj = new JSONObject();
            obj.put("text", text);
            obj.put("time", System.currentTimeMillis());
            obj.put("pinned", false);
            arr.put(0, obj);
            sp.edit().putString("items", arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void cleanExpiredClips() {
        SharedPreferences sp = getSharedPreferences("AIClips", Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(sp.getString("items", "[]"));
            JSONArray fresh = new JSONArray();
            long hourAgo = System.currentTimeMillis() - (3600 * 1000);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                if (obj.getBoolean("pinned") || obj.getLong("time") > hourAgo) {
                    fresh.put(obj);
                }
            }
            sp.edit().putString("items", fresh.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void togglePinClip(int index) {
        SharedPreferences sp = getSharedPreferences("AIClips", Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(sp.getString("items", "[]"));
            if (index < arr.length()) {
                JSONObject obj = arr.getJSONObject(index);
                obj.put("pinned", !obj.getBoolean("pinned"));
                arr.put(index, obj);
                sp.edit().putString("items", arr.toString()).apply();
            }
        } catch (Exception ignored) {}
    }

    private GradientDrawable makeKeycapDrawable(String bg) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bg));
        gd.setCornerRadius(dp(7));
        return gd;
    }

    private GradientDrawable makeDrawable(String bg, String stroke, int sWidth, int radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bg));
        gd.setCornerRadius(radius);
        if (sWidth > 0) gd.setStroke(sWidth, Color.parseColor(stroke));
        return gd;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}