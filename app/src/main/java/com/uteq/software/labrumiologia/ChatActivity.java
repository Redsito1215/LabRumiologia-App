package com.uteq.software.labrumiologia;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.uteq.software.labrumiologia.data.AssistantRepository;
import com.uteq.software.labrumiologia.data.LocalGuide;
import com.uteq.software.labrumiologia.ui.ChatAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {
    private static final int REQ_MIC = 210;

    private String equipmentId;
    private ChatAdapter adapter;
    private TextInputEditText input;
    private MaterialButton btnSend;
    private MaterialButton btnMic;
    private TextView voiceStatus;
    private AssistantRepository assistant;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean listening;
    private boolean voiceMode;
    private final List<Voice> spanishVoices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        equipmentId = getIntent().getStringExtra(DetectionActivity.EXTRA_EQUIPMENT_ID);
        String label = getIntent().getStringExtra(DetectionActivity.EXTRA_EQUIPMENT_LABEL);
        ((TextView) findViewById(R.id.chatEquipmentLabel))
                .setText(getString(R.string.chat_title, label != null ? label : equipmentId));

        RecyclerView messages = findViewById(R.id.chatMessages);
        messages.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChatAdapter();
        messages.setAdapter(adapter);
        assistant = new AssistantRepository(this);

        input = findViewById(R.id.chatInput);
        btnSend = findViewById(R.id.btnSend);
        btnMic = findViewById(R.id.btnMic);
        voiceStatus = findViewById(R.id.voiceStatus);

        btnSend.setOnClickListener(v -> {
            voiceMode = false;
            sendMessage(textFromInput());
        });
        btnMic.setOnClickListener(v -> toggleVoice());
        adapter.add(new ChatAdapter.Message("Sistema", getString(R.string.chat_intro), null));

        tts = new TextToSpeech(this, this);
        setupSpeechRecognizer();
    }

    @Override
    public void onInit(int status) {
        ttsReady = status == TextToSpeech.SUCCESS;
        if (!ttsReady || tts == null) return;
        tts.setLanguage(new Locale("es", "ES"));
        tts.setSpeechRate(0.95f);
        tts.setPitch(1.0f);
        loadSpanishVoices();
        applyFemaleVoice();
    }

    private void loadSpanishVoices() {
        spanishVoices.clear();
        Set<Voice> all = tts.getVoices();
        if (all == null) return;
        for (Voice v : all) {
            if (v == null || v.getLocale() == null) continue;
            if (!"es".equalsIgnoreCase(v.getLocale().getLanguage())) continue;
            spanishVoices.add(v);
        }
        if (spanishVoices.isEmpty()) return;
        Collections.sort(spanishVoices, Comparator
                .comparingInt((Voice v) -> -v.getQuality())
                .thenComparing(v -> v.getLocale().toLanguageTag())
                .thenComparing(Voice::getName));
    }

    private void applyFemaleVoice() {
        if (spanishVoices.isEmpty()) return;
        tts.setVoice(spanishVoices.get(preferredVoiceIndex()));
    }

    /** Prioriza es-ES / es-MX y voces con nombre femenino habituales del motor. */
    private int preferredVoiceIndex() {
        for (int i = 0; i < spanishVoices.size(); i++) {
            Voice v = spanishVoices.get(i);
            String name = v.getName().toLowerCase(Locale.ROOT);
            String tag = v.getLocale().toLanguageTag().toLowerCase(Locale.ROOT);
            boolean esRegion = tag.startsWith("es-es") || tag.startsWith("es-mx") || tag.startsWith("es-us");
            boolean femaleHint = name.contains("female") || name.contains("femen")
                    || name.contains("woman") || name.contains("wavenet-a")
                    || name.contains("wavenet-c") || name.contains("neural2-a")
                    || name.contains("neural2-c") || name.contains("es-es-x-eee")
                    || name.contains("es-us-x-sfb");
            if (esRegion && femaleHint) return i;
        }
        for (int i = 0; i < spanishVoices.size(); i++) {
            String tag = spanishVoices.get(i).getLocale().toLanguageTag().toLowerCase(Locale.ROOT);
            if (tag.startsWith("es-es") || tag.startsWith("es-mx")) return i;
        }
        return 0;
    }

    private void setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            btnMic.setEnabled(false);
            setVoiceStatus(getString(R.string.voice_unavailable));
            return;
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                setVoiceStatus(getString(R.string.voice_listening));
            }

            @Override
            public void onBeginningOfSpeech() {
                setVoiceStatus(getString(R.string.voice_listening));
            }

            @Override
            public void onRmsChanged(float rmsdB) {
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                setVoiceStatus(getString(R.string.voice_processing));
            }

            @Override
            public void onError(int error) {
                listening = false;
                updateMicUi(false);
                setVoiceStatus(getString(R.string.voice_error));
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                updateMicUi(false);
                ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (texts == null || texts.isEmpty()) {
                    setVoiceStatus(getString(R.string.voice_error));
                    return;
                }
                String heard = texts.get(0).trim();
                if (heard.isEmpty()) {
                    setVoiceStatus(getString(R.string.voice_error));
                    return;
                }
                input.setText(heard);
                voiceMode = true;
                setVoiceStatus(getString(R.string.voice_heard, heard));
                sendMessage(heard);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
    }

    private void toggleVoice() {
        if (listening) {
            stopListening();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startListening();
    }

    private void startListening() {
        if (speechRecognizer == null) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        if (tts != null) tts.stop();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        listening = true;
        updateMicUi(true);
        setVoiceStatus(getString(R.string.voice_listening));
        speechRecognizer.startListening(intent);
    }

    private void stopListening() {
        listening = false;
        updateMicUi(false);
        if (speechRecognizer != null) speechRecognizer.stopListening();
        setVoiceStatus(null);
    }

    private void updateMicUi(boolean active) {
        btnMic.setBackgroundTintList(
                ContextCompat.getColorStateList(this, active ? R.color.box_selected : R.color.primary));
    }

    private void setVoiceStatus(String text) {
        if (text == null || text.isEmpty()) {
            voiceStatus.setVisibility(View.GONE);
            voiceStatus.setText("");
            return;
        }
        voiceStatus.setVisibility(View.VISIBLE);
        voiceStatus.setText(text);
    }

    private String textFromInput() {
        return input.getText() != null ? input.getText().toString().trim() : "";
    }

    private void sendMessage(String question) {
        if (question == null || question.isEmpty()) return;

        adapter.add(new ChatAdapter.Message("Usted", question, null));
        input.setText("");
        btnSend.setEnabled(false);
        btnMic.setEnabled(false);
        adapter.add(new ChatAdapter.Message("Asistente", getString(R.string.chat_consulting), null, true));

        final boolean speakReply = voiceMode;
        voiceMode = false;

        io.execute(() -> {
            LocalGuide.Reply reply = assistant.ask(question, equipmentId);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                btnSend.setEnabled(true);
                btnMic.setEnabled(true);
                adapter.removeLastIfPlaceholder();
                adapter.add(new ChatAdapter.Message("Asistente", withoutSources(reply.answer), null));
                if (speakReply) speak(reply.answer);
            });
        });
    }

    private static String withoutSources(String text) {
        if (text == null) return "";
        return text.replaceFirst("(?is)\\n\\s*(fuente|fuentes|sources?)\\s*:.*$", "").trim();
    }

    private void speak(String text) {
        if (!ttsReady || tts == null || text == null || text.isEmpty()) return;
        String clean = text
                .replaceAll("(?m)^#+\\s*", "")
                .replaceAll("[*`_#>]", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.length() > 900) clean = clean.substring(0, 900) + "…";
        setVoiceStatus(getString(R.string.voice_speaking));
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "lab_reply");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening();
            } else {
                Toast.makeText(this, R.string.mic_permission_required, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        listening = false;
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }
}
