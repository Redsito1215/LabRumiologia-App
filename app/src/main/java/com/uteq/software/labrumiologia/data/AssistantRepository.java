package com.uteq.software.labrumiologia.data;

import android.content.Context;
import android.util.Log;

import com.uteq.software.labrumiologia.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Cliente del backend RAG. Las credenciales de OpenAI nunca se incluyen en el APK. */
public class AssistantRepository {
    private static final String TAG = "AssistantRepository";
    private final LocalGuide fallback;

    public AssistantRepository(Context context) {
        fallback = new LocalGuide(context);
    }

    public LocalGuide.Reply ask(String question, String equipmentId) {
        try {
            return askBackend(question, equipmentId);
        } catch (Exception error) {
            Log.w(TAG, "Backend no disponible; usando guías locales", error);
            return fallback.ask(question, equipmentId);
        }
    }

    private LocalGuide.Reply askBackend(String question, String equipmentId) throws Exception {
        String base = BuildConfig.RAG_BASE_URL == null ? "" : BuildConfig.RAG_BASE_URL.trim();
        if (base.isEmpty()) throw new IllegalStateException("RAG_BASE_URL no configurada");
        if (!base.endsWith("/")) base += "/";

        HttpURLConnection connection = (HttpURLConnection) new URL(base + "chat").openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(25_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        // El servidor valida este ID y resuelve sus file_ids/vector_store_ids autorizados.
        connection.setRequestProperty("X-Equipment-Id", equipmentId == null ? "" : equipmentId);
        connection.setRequestProperty("X-App-Token", BuildConfig.APP_ACCESS_TOKEN);

        JSONObject body = new JSONObject();
        body.put("question", question);
        connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));

        int status = connection.getResponseCode();
        String raw = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (status >= 400) throw new IllegalStateException("HTTP " + status + ": " + raw);

        JSONObject json = new JSONObject(raw);
        String answer = json.optString("answer", "").trim();
        if (answer.isEmpty()) throw new IllegalStateException("Respuesta vacía del backend");
        JSONArray items = json.optJSONArray("sources");
        StringBuilder sources = new StringBuilder();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                String title = items.optJSONObject(i) != null
                        ? items.optJSONObject(i).optString("title", "") : "";
                if (!title.isEmpty()) sources.append("• ").append(title).append('\n');
            }
        }
        String sourceText = sources.length() == 0 ? null : "Fuentes:\n" + sources.toString().trim();
        return new LocalGuide.Reply(answer, sourceText);
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
        }
        return text.toString();
    }
}
