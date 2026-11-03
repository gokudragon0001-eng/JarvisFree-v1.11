package com.rsking.jarvis;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.RecognitionListener;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.media.AudioManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private static final int REQ_AUDIO = 41;
    private static final int REQ_CONTACTS = 42;
    private static final int REQ_DEVICE_ADMIN = 43;
    private String pendingContactName = "";
    private String pendingMessageText = "";
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private TextView status, heard, response;
    private EditText prompt;
    private Button mic, ask, keyButton, accessibilityButton, wakeButton, remoteButton;
    private boolean pendingWakeStart = false;
    private boolean listeningAfterWake = false;
    private SharedPreferences prefs;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final JSONArray conversation = new JSONArray();
    private static final String MODEL = "gemini-3.5-flash-lite";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("jarvis_settings", MODE_PRIVATE);
        status = findViewById(R.id.statusText);
        heard = findViewById(R.id.heardText);
        response = findViewById(R.id.responseText);
        prompt = findViewById(R.id.promptInput);
        mic = findViewById(R.id.micButton);
        ask = findViewById(R.id.askButton);
        keyButton = findViewById(R.id.keyButton);
        accessibilityButton = findViewById(R.id.accessibilityButton);
        wakeButton = findViewById(R.id.wakeButton);
        remoteButton = findViewById(R.id.remoteButton);
        tts = new TextToSpeech(this, this);
        mic.setOnClickListener(v -> startListening());
        ask.setOnClickListener(v -> submitText());
        keyButton.setOnClickListener(v -> showKeyDialog());
        accessibilityButton.setOnClickListener(v -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
            catch (Exception e) { respond("Could not open Accessibility settings."); }
        });
        wakeButton.setOnClickListener(v -> startWakeWordService());
        remoteButton.setOnClickListener(v -> showRemoteAccessStatus());
        status.setText(hasKey() ? "READY • AI connected when online" : "READY • Add your free Gemini API key to enable AI");
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("Speech recognition service unavailable. Text chat can still work.");
            mic.setEnabled(false);
        }
        // First launch asks for microphone permission; after approval, start the opted-in
        // persistent wake listener. Android displays its ongoing notification while active.
        boolean launchedByWake = getIntent() != null && getIntent().getBooleanExtra("wake_activated", false);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            if (!launchedByWake) startWakeWordService();
        } else {
            pendingWakeStart = !launchedByWake;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }
        handleIncomingIntent(getIntent());
    }

    @Override public void onInit(int result) {
        if (result == TextToSpeech.SUCCESS) {
            int lang = tts.setLanguage(new Locale("en", "US"));
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED)
                tts.setLanguage(Locale.getDefault());
        }
    }

    private void lockDeviceScreen() {
        android.app.admin.DevicePolicyManager dpm =
            (android.app.admin.DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        android.content.ComponentName admin = new android.content.ComponentName(this, JarvisDeviceAdminReceiver.class);
        if (dpm != null && dpm.isAdminActive(admin)) {
            try { dpm.lockNow(); }
            catch (SecurityException e) { respond("Screen lock permission nahi mili. Device Admin dobara enable karein."); }
        } else {
            Intent enable = new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            enable.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
            enable.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "JARVIS ko sirf aapke 'screen lock' voice command par phone lock karne ke liye Device Admin permission chahiye. Aap kabhi bhi is permission ko Settings se revoke kar sakte hain.");
            startActivityForResult(enable, REQ_DEVICE_ADMIN);
        }
    }

    private void showRemoteAccessStatus() {
        new AlertDialog.Builder(this)
            .setTitle("JARVIS Remote Access")
            .setMessage("Remote screen viewing and remote touch are NOT connected in this build.\n\nTo enable them safely, JARVIS still needs a secure pairing/signaling server, encrypted WebRTC transport (and TURN relay for networks that need it), plus an owner-approved Android screen-capture session.\n\nNo phone is accessible through a download link alone. The phone owner must install JARVIS, approve each screen-sharing prompt, and be able to stop the session.\n\nCurrent build: local voice commands and optional Accessibility text entry only.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Accessibility settings", (d, w) -> {
                try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                catch (Exception e) { respond("Could not open Accessibility settings."); }
            }).show();
    }

    private boolean hasKey() { return !prefs.getString("api_key", "").trim().isEmpty(); }

    private void showKeyDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Paste Gemini API key");
        input.setText(prefs.getString("api_key", ""));
        new AlertDialog.Builder(this).setTitle("Free AI setup")
            .setMessage("Create a key in Google AI Studio, then paste it here. Free-tier limits and eligibility depend on Google.")
            .setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String key = input.getText().toString().trim();
                prefs.edit().putString("api_key", key).apply();
                status.setText(key.isEmpty() ? "AI key not set" : "AI key saved • Internet required");
            })
            .setNeutralButton("Get free key", (d, w) -> openUrl("https://aistudio.google.com/apikey"))
            .setNegativeButton("Cancel", null).show();
    }

    private void startWakeWordService() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingWakeStart = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        try {
            Intent service = new Intent(this, WakeWordService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            respond("Hello wake word is active. Say Hello, then give your command. Keep the notification visible; listening uses battery and may use internet speech recognition.");
        } catch (Exception e) { respond("Could not start wake phrase listener. Open JARVIS again and try."); }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("wake_activated", false)) listeningAfterWake = true;
        if (intent.hasExtra("jarvis_command")) {
            String command = intent.getStringExtra("jarvis_command");
            if (command != null && !command.trim().isEmpty()) {
                heard.setText(command);
                prompt.setText(command);
                processInput(command);
                if (intent.getBooleanExtra("wake_activated", false)) {
                    new android.os.Handler(getMainLooper()).postDelayed(this::startWakeWordService, 2500);
                }
            }
        } else if (intent.getBooleanExtra("wake_activated", false)) {
            listeningAfterWake = true;
            respond("Ji, main sun raha hoon. Batayein?");
            new android.os.Handler(getMainLooper()).postDelayed(this::startListening, 700);
        }
        intent.removeExtra("wake_activated");
        intent.removeExtra("jarvis_command");
    }

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO); return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return;
        if (recognizer != null) recognizer.destroy();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle b) { status.setText("LISTENING… Speak now"); }
            public void onBeginningOfSpeech() { }
            public void onRmsChanged(float v) { }
            public void onBufferReceived(byte[] b) { }
            public void onEndOfSpeech() { status.setText("Processing command…"); }
            public void onError(int e) { status.setText("Could not hear that. Tap and try again."); }
            public void onResults(Bundle b) {
                ArrayList<String> matches = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String command = matches.get(0);
                    heard.setText(command);
                    prompt.setText(command);
                    processInput(command);
                    if (listeningAfterWake) {
                        listeningAfterWake = false;
                        new android.os.Handler(getMainLooper()).postDelayed(() -> startWakeWordService(), 1800);
                    }
                }
            }
            public void onPartialResults(Bundle b) { }
            public void onEvent(int t, Bundle b) { }
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ur-PK");
        intent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to JARVIS");
        recognizer.startListening(intent);
    }

    private void submitText() {
        String text = prompt.getText().toString().trim();
        if (text.isEmpty()) return;
        heard.setText(text);
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(prompt.getWindowToken(), 0);
        processInput(text);
    }

    private void processInput(String raw) {
        String c = raw.toLowerCase(Locale.ROOT).trim();

        // Lock-screen command: Android requires the user to explicitly enable JARVIS
        // as a device administrator before this can lock the phone.
        if (containsAny(c, "hello screen lock", "screen lock", "screen kholo nahi lock", "phone lock", "mobile lock", "lock screen", "phone ko lock karo", "mobile lock karo")) {
            lockDeviceScreen();
            return;
        }

        // If the user first said "open WhatsApp" and then speaks a contact name,
        // resolve that name against the phone's saved contacts.
        if (prefs.getBoolean("awaiting_whatsapp_contact", false)) {
            prefs.edit().putBoolean("awaiting_whatsapp_contact", false).apply();
            String contactName = raw.replaceFirst("(?i)^\\s*(hello\\s+)?", "").trim();
            if (!contactName.isEmpty()) { openWhatsAppContact(contactName, ""); return; }
        }

        // After a WhatsApp chat is opened, the next spoken phrase is treated as
        // message text and inserted into the chat composer. It is NEVER sent.
        if (prefs.getBoolean("awaiting_whatsapp_text", false)) {
            if (containsAny(c, "cancel", "cancel message", "radd karo", "rehne do", "message cancel")) {
                prefs.edit().putBoolean("awaiting_whatsapp_text", false).apply();
                respond("Theek hai, message typing cancel kar di.");
                return;
            }
            if (containsAny(c, "send", "bhej do", "send message")) {
                respond("Message send karne ke liye WhatsApp mein Send button aap khud press karein. Apna message bol kar type karwa sakte hain.");
                return;
            }
            String message = raw.trim().replaceFirst("(?i)^\\s*(hello\\s+)?(type|likho|message|message bolo)\\s*", "").trim();
            if (message.isEmpty()) { respond("Ji, WhatsApp message bol dein."); return; }
            typeMessageInWhatsApp(message);
            return;
        }

        // Contact-opening commands: open the named person's WhatsApp chat without
        // composing or sending a message. Explicit contact phrases are checked first.
        String contactToOpen = extractContactOpenName(raw);
        if (!contactToOpen.isEmpty()) { openWhatsAppContact(contactToOpen, ""); return; }

        // Combined phrase: "open WhatsApp Ali" / "WhatsApp kholo Ali".
        String inlineContact = raw.replaceFirst("(?i)^\\s*(hello\\s+)?(open\\s+whatsapp|whatsapp\\s+kholo)\\s*", "").trim();
        if (!inlineContact.equalsIgnoreCase(raw.trim()) && !inlineContact.isEmpty()) {
            openWhatsAppContact(inlineContact, ""); return;
        }

        // Try a dynamically discovered installed app name after the common app shortcuts.
        if (c.startsWith("open ") || c.endsWith(" kholo") || c.endsWith(" ko kholo") || c.endsWith(" open karo") || c.endsWith(" khol do")) {
            String requested = c.replaceFirst("(?i)^open\\s+", "")
                .replaceFirst("(?i)\\s+(ko\\s+)?(kholo|open karo|khol do)$", "").trim();
            if (openInstalledAppByName(requested)) return;
            // A spoken person's name followed by "kholo/open karo" means open their WhatsApp chat.
            if (!requested.isEmpty() && !isKnownSystemTarget(requested)) { openWhatsAppContact(requested, ""); return; }
        }
        // Local device actions run on-device and do not need an AI key.
        if (containsAny(c, "open whatsapp", "whatsapp kholo", "whatsapp کھولو", "whatsapp kholo")) {
            prefs.edit().putBoolean("awaiting_whatsapp_contact", true).apply();
            openPackage("com.whatsapp", "WhatsApp");
            respond("WhatsApp khul gaya. Ab jis contact ka naam bolenge, uski chat open karunga.");
            return;
        }
        if (containsAny(c, "open instagram", "instagram kholo", "instagram کھولو")) { openPackage("com.instagram.android", "Instagram"); return; }
        if (containsAny(c, "open youtube", "youtube kholo", "youtube کھولو")) { openPackage("com.google.android.youtube", "YouTube"); return; }
        if (containsAny(c, "open facebook", "facebook kholo", "facebook کھولو")) { openPackage("com.facebook.katana", "Facebook"); return; }
        if (containsAny(c, "open chrome", "chrome kholo", "browser kholo")) { openPackage("com.android.chrome", "Chrome"); return; }
        if (containsAny(c, "open maps", "google maps kholo", "maps kholo")) { openPackage("com.google.android.apps.maps", "Google Maps"); return; }
        if (containsAny(c, "open gmail", "gmail kholo")) { openPackage("com.google.android.gm", "Gmail"); return; }
        if (containsAny(c, "open spotify", "spotify kholo")) { openPackage("com.spotify.music", "Spotify"); return; }
        if (containsAny(c, "open telegram", "telegram kholo")) { openPackage("org.telegram.messenger", "Telegram"); return; }
        if (containsAny(c, "open snapchat", "snapchat kholo")) { openPackage("com.snapchat.android", "Snapchat"); return; }
        if (containsAny(c, "open tiktok", "tiktok kholo")) { openPackage("com.zhiliaoapp.musically", "TikTok"); return; }
        if (containsAny(c, "open contacts", "contacts kholo")) { launch(new Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI), "Opening contacts"); return; }
        if (containsAny(c, "open phone", "dialer kholo", "open dialer")) { launch(new Intent(Intent.ACTION_DIAL), "Opening phone dialer"); return; }
        if (containsAny(c, "open camera", "camera kholo", "camera کھولو")) { launch(new Intent("android.media.action.IMAGE_CAPTURE"), "Opening camera"); return; }
        if (containsAny(c, "open settings", "settings kholo", "settings کھولو")) { launch(new Intent(android.provider.Settings.ACTION_SETTINGS), "Opening settings"); return; }
        if (containsAny(c, "wifi settings", "open wifi", "wifi kholo", "wi-fi settings")) { launch(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS), "Opening Wi-Fi settings"); return; }
        if (containsAny(c, "bluetooth settings", "open bluetooth", "bluetooth kholo")) { launch(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS), "Opening Bluetooth settings"); return; }
        if (containsAny(c, "volume up", "volume barhao", "awaz barhao", "volume increase")) { changeVolume(AudioManager.ADJUST_RAISE); return; }
        if (containsAny(c, "volume down", "volume kam", "awaz kam", "volume decrease")) { changeVolume(AudioManager.ADJUST_LOWER); return; }
        if (c.startsWith("type ") || c.startsWith("likho ") || c.startsWith("text likho ")) {
            String text = raw.replaceFirst("(?i)^\\s*(type|likho|text likho)\\s*", "").trim();
            if (text.isEmpty()) { respond("Please say the text after the word type."); return; }
            boolean typed = JarvisAccessibilityService.typeIntoFocusedField(this, text);
            respond(typed ? "Text entered in the focused field. Please review it before sending." : "Enable JARVIS Phone Control in Accessibility settings, open a text field, then try again.");
            return;
        }
        if (c.startsWith("youtube search ") || c.startsWith("search youtube for ") || c.startsWith("youtube par ") && c.endsWith(" search karo")) {
            String q = raw.replaceFirst("(?i)^\\s*(youtube search|search youtube for|youtube par)\\s*", "").replaceFirst("(?i)\\s*search karo$", "").trim();
            if (!q.isEmpty()) { openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(q)); respond("YouTube par " + q + " search kar raha hoon."); return; }
        }
        if (c.startsWith("open website ") || c.startsWith("website kholo ") || c.startsWith("open site ")) {
            String address = raw.replaceFirst("(?i)^\\s*(open website|website kholo|open site)\\s*", "").trim();
            if (!address.isEmpty()) { if (!address.matches("(?i)^https?://.*")) address = "https://" + address; openUrl(address); respond("Website khol raha hoon: " + address); return; }
        }
        if (c.startsWith("search ") || c.startsWith("google ") || c.contains("search for")) {
            String q = raw.replaceFirst("(?i)^\\s*(search( for)?|google)\\s*", "").trim();
            openUrl("https://www.google.com/search?q=" + Uri.encode(q)); respond("Searching Google for " + q); return;
        }
        if (c.startsWith("whatsapp message to ") || c.startsWith("message whatsapp ") || c.startsWith("whatsapp ko message ")) {
            String rest = raw.replaceFirst("(?i)^\\s*(whatsapp message to|message whatsapp|whatsapp ko message)\\s*", "").trim();
            int split = rest.indexOf(':');
            if (split < 1 || split == rest.length() - 1) { respond("Is tarah bolain: WhatsApp message to Irfan: Hello Irfan"); return; }
            openWhatsAppContact(rest.substring(0, split).trim(), rest.substring(split + 1).trim());
            return;
        }
        if (containsAny(c, "send whatsapp", "whatsapp message", "message on whatsapp")) {
            Intent share = new Intent(Intent.ACTION_SEND); share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, raw); share.setPackage("com.whatsapp");
            try { startActivity(share); respond("WhatsApp opened. Choose the contact, review the message, then press Send yourself."); }
            catch (Exception e) { respond("WhatsApp is not installed."); }
            return;
        }
        askGemini(raw);
    }

    private String extractContactOpenName(String raw) {
        String value = raw.trim();
        String[] prefixes = {
            "(?i)^open\\s+chat\\s+with\\s+",
            "(?i)^chat\\s+with\\s+",
            "(?i)^whatsapp\\s+chat\\s+",
            "(?i)^whatsapp\\s+par\\s+",
            "(?i)^contact\\s+"
        };
        for (String prefix : prefixes) {
            if (value.matches(prefix + ".+")) {
                String name = value.replaceFirst(prefix, "").trim();
                name = name.replaceFirst("(?i)\\s+(ko\\s+)?(open|kholo|khol do|par chat kholo)$", "").trim();
                if (!name.isEmpty()) return name;
            }
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("open contact ")) return value.substring("open contact ".length()).trim();
        if (lower.startsWith("contact kholo ")) return value.substring("contact kholo ".length()).trim();
        if (lower.endsWith(" ka whatsapp kholo")) return value.substring(0, value.length() - " ka whatsapp kholo".length()).trim();
        if (lower.endsWith(" ko whatsapp kholo")) return value.substring(0, value.length() - " ko whatsapp kholo".length()).trim();
        return "";
    }

    private boolean isKnownSystemTarget(String name) {
        return containsAny(name, "whatsapp", "instagram", "youtube", "facebook", "chrome", "browser", "camera", "settings", "wifi", "bluetooth", "contacts", "phone", "dialer", "maps", "gmail", "spotify", "telegram", "snapchat", "tiktok");
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }

    private void askGemini(String question) {
        String key = prefs.getString("api_key", "").trim();
        if (key.isEmpty()) { respond("AI setup is not complete. Tap the API KEY button and add your free Gemini key."); return; }
        status.setText("JARVIS AI is thinking…");
        ask.setEnabled(false); mic.setEnabled(false);
        network.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST"); conn.setConnectTimeout(15000); conn.setReadTimeout(30000);
                conn.setDoOutput(true); conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("x-goog-api-key", key);
                JSONObject userPart = new JSONObject().put("text", question);
                JSONArray contents;
                synchronized (conversation) {
                    conversation.put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(userPart)));
                    while (conversation.length() > 16) conversation.remove(0);
                    contents = new JSONArray(conversation.toString());
                }
                JSONObject system = new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text",
                    "You are JARVIS, a concise and helpful phone assistant. Reply in the same language as the user, including Urdu or Roman Urdu. You may explain how to use phone features, but never claim to have performed a device action unless the app actually did it. For actions not supported by the app, clearly say so.")));
                JSONObject body = new JSONObject().put("systemInstruction", system).put("contents", contents);
                try (OutputStream os = conn.getOutputStream()) { os.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
                int code = conn.getResponseCode();
                BufferedReader reader = new BufferedReader(new InputStreamReader(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream(), StandardCharsets.UTF_8));
                StringBuilder result = new StringBuilder(); String line;
                while ((line = reader.readLine()) != null) result.append(line);
                if (code < 200 || code >= 300) throw new Exception("Gemini API error " + code + ": " + result);
                JSONObject json = new JSONObject(result.toString());
                String answer = json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim();
                synchronized (conversation) {
                    conversation.put(new JSONObject().put("role", "model").put("parts", new JSONArray().put(new JSONObject().put("text", answer))));
                    while (conversation.length() > 16) conversation.remove(0);
                }
                runOnUiThread(() -> respond(answer));
            } catch (Exception ex) {
                synchronized (conversation) {
                    int last = conversation.length() - 1;
                    if (last >= 0 && "user".equals(conversation.optJSONObject(last).optString("role"))) conversation.remove(last);
                }
                String err = ex.getMessage() == null ? "Connection failed" : ex.getMessage();
                runOnUiThread(() -> respond("AI connection issue: " + err + ". Check internet, API key and free-tier availability."));
            } finally {
                if (conn != null) conn.disconnect();
                runOnUiThread(() -> { ask.setEnabled(true); mic.setEnabled(true); });
            }
        });
    }

    private void changeVolume(int direction) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audio == null) { respond("Volume control is unavailable."); return; }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
        respond(direction == AudioManager.ADJUST_RAISE ? "Volume increased." : "Volume decreased.");
    }

    private boolean openInstalledAppByName(String requested) {
        if (requested.isEmpty()) return false;
        String wanted = requested.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "").trim();
        android.content.Intent query = new android.content.Intent(android.content.Intent.ACTION_MAIN, null);
        query.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
        android.content.pm.ResolveInfo best = null; int bestScore = 0;
        for (android.content.pm.ResolveInfo info : getPackageManager().queryIntentActivities(query, 0)) {
            CharSequence label = info.loadLabel(getPackageManager());
            if (label == null) continue;
            String candidate = label.toString().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
            int score = candidate.equals(wanted) ? 100 : (candidate.startsWith(wanted) || wanted.startsWith(candidate) ? 70 : (candidate.contains(wanted) ? 50 : 0));
            if (score > bestScore) { best = info; bestScore = score; }
        }
        if (best != null && bestScore >= 50) {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(best.activityInfo.packageName);
            if (launchIntent != null) { launch(launchIntent, "Opening " + best.loadLabel(getPackageManager())); return true; }
        }
        return false;
    }

    private void typeMessageInWhatsApp(String message) {
        if (message == null || message.trim().isEmpty()) {
            respond("Jo message likhwana hai woh bolain.");
            return;
        }
        // Return to the previously opened app (WhatsApp) before querying the
        // accessibility focus; otherwise JARVIS itself would be the foreground app.
        respond("WhatsApp mein message type kar raha hoon. Send aap khud press karein.");
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            try { moveTaskToBack(true); } catch (Exception ignored) { }
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                boolean typed = JarvisAccessibilityService.typeIntoFocusedField(this, message);
                runOnUiThread(() -> {
                    if (typed) {
                        prefs.edit().putBoolean("awaiting_whatsapp_text", false).apply();
                        // Keep compose mode off until another contact is opened, avoiding
                        // accidental insertion of unrelated future commands.
                        Toast.makeText(this, "Message typed. Review it and press Send yourself.", Toast.LENGTH_LONG).show();
                    } else {
                        respond("WhatsApp ke message box mein typing nahi ho saki. Chat khol kar message box select karein, phir Hello bol kar message dobara bolain. Accessibility permission bhi enabled honi chahiye.");
                    }
                });
            }, 650);
        }, 250);
    }

    private void openWhatsAppContact(String name, String message) {
        if (name.isEmpty()) { respond("Contact ka naam batayein."); return; }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            pendingContactName = name; pendingMessageText = message;
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS); return;
        }
        android.database.Cursor cursor = null;
        try {
            // Search all saved phone contacts; no individual contact name is hard-coded.
            cursor = getContentResolver().query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER},
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? COLLATE NOCASE",
                new String[]{"%" + name.trim() + "%"},
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC");
            if (cursor == null) { respond("Phone contacts read nahi ho sake. Contacts permission check karein."); return; }

            // Prefer exact name, then starts-with, then partial-name matches.
            String wanted = name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            String foundName = null;
            String phone = null;
            int bestScore = -1;
            while (cursor.moveToNext()) {
                String candidate = cursor.getString(0);
                String candidatePhone = cursor.getString(1);
                if (candidate == null || candidatePhone == null) continue;
                String normalized = candidate.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
                int score = normalized.equals(wanted) ? 100
                    : (normalized.startsWith(wanted) ? 80
                    : (normalized.contains(wanted) ? 60 : 0));
                if (score > bestScore && candidatePhone.replaceAll("[^0-9]", "").length() >= 7) {
                    bestScore = score;
                    foundName = candidate;
                    phone = candidatePhone;
                }
                if (bestScore == 100) break;
            }
            if (foundName == null || phone == null) {
                respond("Contacts mein " + name + " nahi mila. Naam dobara bolain ya contact save karein.");
                return;
            }
            phone = phone.replaceAll("[^0-9+]", "").replaceAll("^\\+", "");
            // Convert local Pakistani numbers to international format for WhatsApp links.
            if (phone.startsWith("0")) phone = "92" + phone.substring(1);
            if (phone.isEmpty()) { respond("Is contact ka valid phone number nahi mila."); return; }
            String link = "https://wa.me/" + Uri.encode(phone);
            if (message != null && !message.trim().isEmpty()) link += "?text=" + Uri.encode(message.trim());
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(link));
            intent.setPackage("com.whatsapp");
            try {
                startActivity(intent);
                if (message == null || message.trim().isEmpty()) {
                    prefs.edit().putBoolean("awaiting_whatsapp_text", true).apply();
                    respond(foundName + " ka WhatsApp chat open ho raha hai. Ab Hello bol kar apna message bolain; main text type karunga, send nahi karunga.");
                } else {
                    prefs.edit().putBoolean("awaiting_whatsapp_text", false).apply();
                    respond(foundName + " ka WhatsApp chat open kar raha hoon. Message review karein aur Send khud press karein.");
                }
            } catch (Exception noWhatsApp) {
                intent.setPackage(null);
                startActivity(intent);
                respond("WhatsApp direct open nahi hua. Chat link browser mein khol raha hoon.");
            }
        } catch (Exception e) { respond("WhatsApp chat open nahi ho saka. WhatsApp installed aur contact number sahi hona chahiye."); }
        finally { if (cursor != null) cursor.close(); }
    }

    private void openPackage(String pkg, String label) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { respond(label + " is not installed."); return; }
        launch(i, "Opening " + label);
    }
    private void launch(Intent i, String message) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); respond(message); }
        catch (Exception ex) { respond("I could not perform that action."); }
    }
    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show(); }
    }
    private void respond(String text) {
        response.setText(text);
        if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_reply");
        status.setText("READY • Speak or type another command");
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == REQ_AUDIO && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingWakeStart) { pendingWakeStart = false; startWakeWordService(); }
            else startListening();
        } else if (request == REQ_AUDIO) { pendingWakeStart = false; Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show(); }
        else if (request == REQ_CONTACTS) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                String n = pendingContactName, m = pendingMessageText; pendingContactName = ""; pendingMessageText = ""; openWhatsAppContact(n, m);
            } else respond("Contact search ke liye Contacts permission zaroori hai. Aap WhatsApp manually khol sakte hain.");
        }
    }
    @Override protected void onDestroy() {
        if (recognizer != null) recognizer.destroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        network.shutdownNow(); super.onDestroy();
    }
}
