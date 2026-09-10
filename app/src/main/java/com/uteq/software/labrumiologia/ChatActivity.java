package com.uteq.software.labrumiologia;

import android.Manifest;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.uteq.software.labrumiologia.data.AssistantRepository;
import com.uteq.software.labrumiologia.data.EquipmentRepository;
import com.uteq.software.labrumiologia.model.EquipmentInfo;
import com.uteq.software.labrumiologia.ui.ChatAdapter;
import com.uteq.software.labrumiologia.ui.RecordingWaveView;

import java.io.IOException;
import java.io.InputStream;
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
    private EditText input;
    private ImageButton btnSend;
    private ImageButton btnMic;
    private TextView btnVoiceChange;
    private TextView voiceStatus;
    private View recordingPanel;
    private RecordingWaveView recordingWave;
    private AssistantRepository assistant;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean listening;
    private boolean voiceMode;
    private boolean requestInFlight;
    private int selectedVoiceIndex = -1;
    private final List<Voice> spanishVoices = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        equipmentId = getIntent().getStringExtra(DetectionActivity.EXTRA_EQUIPMENT_ID);
        String label = getIntent().getStringExtra(DetectionActivity.EXTRA_EQUIPMENT_LABEL);
        
        setupHeader(label);
        setupEquipmentCard();

        RecyclerView messages = findViewById(R.id.chatMessages);
        messages.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChatAdapter();
        messages.setAdapter(adapter);
        assistant = new AssistantRepository(this);

        input = findViewById(R.id.chatInput);
        btnSend = findViewById(R.id.btnSend);
        btnMic = findViewById(R.id.btnMic);
        btnVoiceChange = findViewById(R.id.btnVoiceChange);
        voiceStatus = findViewById(R.id.voiceStatus);
        recordingPanel = findViewById(R.id.recordingPanel);
        recordingWave = findViewById(R.id.recordingWave);
        recordingPanel.setOnClickListener(v -> stopListening());

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { updateComposerUi(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnSend.setOnClickListener(v -> {
            voiceMode = false;
            sendMessage(textFromInput());
        });
        btnMic.setOnClickListener(v -> toggleVoice());
        btnVoiceChange.setOnClickListener(v -> showVoicePicker());
        btnVoiceChange.setEnabled(false);
        updateComposerUi();
        
        setupSuggestedQuestions();

        tts = new TextToSpeech(this, this);
        setupSpeechRecognizer();

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
    }

    private void setupHeader(String label) {
        TextView headerTitle = findViewById(R.id.chatEquipmentName);
        headerTitle.setText(label != null ? label : equipmentId);
    }

    private void setupEquipmentCard() {
        EquipmentInfo info = equipmentId != null ? new EquipmentRepository(this).get(equipmentId) : null;
        TextView nameSub = findViewById(R.id.chatEquipmentNameSub);
        TextView brand = findViewById(R.id.chatEquipmentBrand);
        TextView classView = findViewById(R.id.chatEquipmentClass);
        ImageView thumb = findViewById(R.id.chatEquipmentThumb);
        TextView docCount = findViewById(R.id.docCountText);
        View btnDocs = findViewById(R.id.btnDocuments);

        if (info != null) {
            nameSub.setText(info.name);
            brand.setText(getString(R.string.marca_label, info.brand != null ? info.brand : "N/A"));
            classView.setText(getString(R.string.clase_yolo_label, info.id));
            bindCatalogPhoto(thumb, info.id);
        } else {
            nameSub.setText(equipmentId);
            brand.setText(getString(R.string.marca_label, "N/A"));
            classView.setText(getString(R.string.clase_yolo_label, equipmentId != null ? equipmentId : "N/A"));
        }

        List<String> pdfs = getAvailablePdfs(equipmentId);
        if (pdfs.isEmpty()) {
            btnDocs.setVisibility(View.GONE);
        } else {
            btnDocs.setVisibility(View.VISIBLE);
            docCount.setText(String.format(Locale.getDefault(), "%d docs", pdfs.size()));
            btnDocs.setOnClickListener(v -> openPdf(pdfs.get(0), "Guía técnica"));
        }
    }

    private List<String> getAvailablePdfs(String equipmentId) {
        List<String> results = new ArrayList<>();
        if (equipmentId == null) return results;
        try {
            String[] assets = getAssets().list("docs");
            if (assets != null) {
                for (String f : assets) {
                    if (f.toLowerCase().contains(equipmentId.toLowerCase()) && f.endsWith(".pdf")) {
                        results.add("docs/" + f);
                    }
                }
            }
        } catch (IOException ignored) {}
        return results;
    }

    private void openPdf(String path, String title) {
        Intent intent = new Intent(this, PdfViewerActivity.class);
        intent.putExtra(PdfViewerActivity.EXTRA_PDF_PATH, path);
        intent.putExtra(PdfViewerActivity.EXTRA_PDF_TITLE, title);
        startActivity(intent);
    }

    private void setupSuggestedQuestions() {
        findViewById(R.id.chipPrender).setOnClickListener(v -> sendMessage("¿Cómo se prende el equipo?"));
        findViewById(R.id.chipTemp).setOnClickListener(v -> sendMessage("¿Qué temperatura debo usar?"));
        findViewById(R.id.chipRiesgos).setOnClickListener(v -> sendMessage("¿Qué riesgos tiene?"));
        findViewById(R.id.chipLimpiar).setOnClickListener(v -> sendMessage("¿Cómo se limpia?"));
    }

    private void bindCatalogPhoto(ImageView imageView, String classId) {
        if (classId == null) return;
        String assetPath = "equipment_photos/" + classId + ".jpg";
        try (InputStream in = getAssets().open(assetPath)) {
            Bitmap ref = BitmapFactory.decodeStream(in);
            if (ref != null) imageView.setImageBitmap(ref);
        } catch (IOException ignored) {}
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
        btnVoiceChange.setEnabled(!spanishVoices.isEmpty());
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
        selectedVoiceIndex = preferredVoiceIndex();
        applySelectedVoice(false);
    }

    private void showVoicePicker() {
        if (!ttsReady || spanishVoices.isEmpty()) {
            setVoiceStatus(getString(R.string.voice_none));
            return;
        }
        String[] labels = new String[spanishVoices.size()];
        for (int i = 0; i < spanishVoices.size(); i++) {
            Voice voice = spanishVoices.get(i);
            labels[i] = voice.getLocale().getDisplayName(new Locale("es")) + " · " + voice.getName();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.voice_change)
                .setSingleChoiceItems(labels, Math.max(0, selectedVoiceIndex), (dialog, which) -> {
                    selectedVoiceIndex = which;
                    applySelectedVoice(true);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applySelectedVoice(boolean preview) {
        if (tts == null || selectedVoiceIndex < 0 || selectedVoiceIndex >= spanishVoices.size()) return;
        Voice voice = spanishVoices.get(selectedVoiceIndex);
        tts.setVoice(voice);
        setVoiceStatus(getString(R.string.voice_selected, voice.getLocale().getDisplayName(new Locale("es"))));
        if (preview) tts.speak(getString(R.string.voice_preview), TextToSpeech.QUEUE_FLUSH, null, "voice_preview");
    }

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
            @Override public void onReadyForSpeech(Bundle params) { setVoiceStatus(getString(R.string.voice_listening)); }
            @Override public void onBeginningOfSpeech() { setVoiceStatus(getString(R.string.voice_listening)); }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { setVoiceStatus(getString(R.string.voice_processing)); }
            @Override public void onError(int error) { listening = false; hideRecordingPanel(); updateMicUi(false); setVoiceStatus(getString(R.string.voice_error)); }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                hideRecordingPanel();
                updateMicUi(false);
                ArrayList<String> texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (texts == null || texts.isEmpty()) { setVoiceStatus(getString(R.string.voice_error)); return; }
                String heard = texts.get(0).trim();
                if (heard.isEmpty()) { setVoiceStatus(getString(R.string.voice_error)); return; }
                input.setText(heard);
                voiceMode = true;
                setVoiceStatus(getString(R.string.voice_heard, heard));
                sendMessage(heard);
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void toggleVoice() {
        if (listening) { stopListening(); return; }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        startListening();
    }

    private void startListening() {
        if (speechRecognizer == null) return;
        if (tts != null) tts.stop();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        listening = true;
        recordingPanel.setVisibility(View.VISIBLE);
        recordingPanel.setAlpha(0f);
        recordingPanel.setTranslationY(24f);
        recordingPanel.animate().alpha(1f).translationY(0f).setDuration(180).start();
        recordingWave.start();
        updateMicUi(true);
        setVoiceStatus(getString(R.string.voice_listening));
        speechRecognizer.startListening(intent);
    }

    private void stopListening() {
        listening = false;
        hideRecordingPanel();
        updateMicUi(false);
        if (speechRecognizer != null) speechRecognizer.stopListening();
        setVoiceStatus(null);
    }

    private void updateMicUi(boolean active) {
        int background = ContextCompat.getColor(this, active ? R.color.primary : R.color.primary_soft);
        int foreground = ContextCompat.getColor(this, active ? R.color.white : R.color.primary);
        btnMic.setBackgroundTintList(ColorStateList.valueOf(background));
        btnMic.setColorFilter(foreground);
        btnMic.setAlpha(btnMic.isEnabled() ? 1.0f : 0.35f);
    }

    private void hideRecordingPanel() {
        if (recordingWave != null) recordingWave.stop();
        if (recordingPanel == null || recordingPanel.getVisibility() != View.VISIBLE) return;
        recordingPanel.animate().alpha(0f).translationY(20f).setDuration(140)
                .withEndAction(() -> recordingPanel.setVisibility(View.GONE)).start();
    }

    private void updateComposerUi() {
        if (btnSend == null || btnMic == null || input == null) return;
        boolean canSend = !requestInFlight && !textFromInput().isEmpty();
        btnSend.setEnabled(canSend);
        btnSend.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(
                this, canSend ? R.color.primary : R.color.primary_soft)));
        btnSend.setColorFilter(ContextCompat.getColor(this, canSend ? R.color.white : R.color.on_surface_muted));
        btnSend.setAlpha(canSend ? 1.0f : 0.55f);
        btnSend.setElevation(canSend ? 8f * getResources().getDisplayMetrics().density : 0f);
        btnMic.setEnabled(!requestInFlight);
        updateMicUi(listening);
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
        if (question == null || question.isEmpty() || requestInFlight) return;

        adapter.add(new ChatAdapter.Message("Usted", question, null));
        input.setText("");
        requestInFlight = true;
        updateComposerUi();
        adapter.add(new ChatAdapter.Message("Asistente", getString(R.string.chat_consulting), null, true));

        final boolean speakReply = voiceMode;
        voiceMode = false;

        io.execute(() -> {
            AssistantRepository.Reply reply = assistant.ask(question, equipmentId);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                requestInFlight = false;
                updateComposerUi();
                adapter.removeLastIfPlaceholder();
                
                String cleanAnswer = sanitizeAnswer(reply.answer);
                adapter.add(new ChatAdapter.Message("Asistente", cleanAnswer, null));
                
                if (speakReply) speak(cleanAnswer);
            });
        });
    }

    private String sanitizeAnswer(String answer) {
        if (answer == null) return "";
        return answer.replaceFirst("(?is)\\n\\s*(fuente|fuentes|sources?)\\s*:.*$", "").trim();
    }

    private void speak(String text) {
        if (!ttsReady || tts == null || text == null || text.isEmpty()) return;
        String clean = text.replaceAll("(?m)^#+\\s*", "").replaceAll("[*`_#>]", "").replaceAll("\\s+", " ").trim();
        clean = compactForSpeech(clean);
        setVoiceStatus(getString(R.string.voice_speaking));
        tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "lab_reply");
    }

    private static String compactForSpeech(String text) {
        String[] sentences = text.split("(?<=[.!?])\\s+");
        StringBuilder shortReply = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence.trim().isEmpty()) continue;
            if (shortReply.length() > 0) shortReply.append(' ');
            shortReply.append(sentence.trim());
            if (shortReply.length() >= 260 || shortReply.toString().split("(?<=[.!?])").length >= 2) break;
        }
        String result = shortReply.length() == 0 ? text : shortReply.toString();
        return result.length() > 340 ? result.substring(0, 340).trim() + "…" : result;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) startListening();
        }
    }

    @Override
    protected void onDestroy() {
        listening = false;
        if (recordingWave != null) recordingWave.stop();
        if (speechRecognizer != null) { speechRecognizer.destroy(); speechRecognizer = null; }
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        io.shutdownNow();
        super.onDestroy();
    }
}
