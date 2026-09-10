package com.uteq.software.labrumiologia.data;

import android.content.Context;
import android.util.Log;

import com.uteq.software.labrumiologia.BuildConfig;
import com.uteq.software.labrumiologia.model.EquipmentInfo;

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
    private final EquipmentRepository equipmentRepository;

    public AssistantRepository(Context context) {
        fallback = new LocalGuide(context);
        equipmentRepository = new EquipmentRepository(context);
    }

    public LocalGuide.Reply ask(String question, String equipmentId) {
        try {
            LocalGuide.Reply remote = askBackend(question, equipmentId);
            return isInsufficient(remote.answer) ? catalogReply(question, equipmentId, remote) : remote;
        } catch (Exception error) {
            Log.w(TAG, "Backend no disponible o error de red: " + error.getMessage());
            // Si el backend falla, usamos la guía local
            LocalGuide.Reply localReply = fallback.ask(question, equipmentId);
            
            // Si no hay respuesta local útil, devolvemos un mensaje genérico sin detalles de error técnico
            if (localReply == null || localReply.answer == null || localReply.answer.isEmpty()) {
                return new LocalGuide.Reply("Lo siento, no puedo responder en este momento. Por favor, verifica tu conexión o intenta más tarde.", null);
            }
            
            return isInsufficient(localReply.answer) ? catalogReply(question, equipmentId, localReply) : localReply;
        }
    }

    private LocalGuide.Reply catalogReply(String question, String equipmentId, LocalGuide.Reply original) {
        EquipmentInfo info = equipmentRepository.get(equipmentId);
        if (info == null) return original;
        String q = question == null ? "" : question.toLowerCase();
        String answer;
        if (q.contains("temperatura") || q.contains("grados")) {
            answer = "Temperatura o condición de trabajo: " + safe(info.tempRange)
                    + ". Use únicamente el valor indicado en la práctica y verifíquelo antes de iniciar.";
        } else if (q.contains("riesgo") || q.contains("segur") || q.contains("peligro")) {
            answer = safe(info.safety);
        } else if (q.contains("limpi") || q.contains("manten")) {
            answer = "Apague y desconecte el equipo antes de limpiarlo. No moje controles ni conexiones. "
                    + "Retire residuos con el método autorizado y reporte cualquier daño al responsable del laboratorio.";
        } else if (q.contains("prend") || q.contains("encend") || q.contains("inici")) {
            answer = "Antes de encender " + info.name + ", compruebe que esté limpio, correctamente conectado y listo para la práctica. "
                    + safe(info.usage);
        } else {
            answer = safe(info.description) + " " + safe(info.function);
        }
        return new LocalGuide.Reply(answer.trim(), null);
    }

    private static boolean isInsufficient(String answer) {
        if (answer == null || answer.trim().isEmpty()) return true;
        String text = answer.toLowerCase();
        return text.contains("no se encontró información específica")
                || text.contains("no encontré esa información")
                || text.contains("no dispongo de información suficiente")
                || text.contains("no hay una base de conocimiento");
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "Consulte la guía validada del laboratorio" : value.trim();
    }

    private LocalGuide.Reply askBackend(String question, String equipmentId) throws Exception {
        String base = BuildConfig.RAG_BASE_URL == null ? "" : BuildConfig.RAG_BASE_URL.trim();
        if (base.isEmpty()) throw new IllegalStateException("RAG_BASE_URL no configurada");
        if (!base.endsWith("/")) base += "/";

        HttpURLConnection connection = (HttpURLConnection) new URL(base + "chat").openConnection();
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(15_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("X-Equipment-Id", equipmentId == null ? "" : equipmentId);
        connection.setRequestProperty("X-App-Token", BuildConfig.APP_ACCESS_TOKEN);

        JSONObject body = new JSONObject();
        body.put("question", question);
        connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));

        int status = connection.getResponseCode();
        if (status >= 400) {
            throw new IllegalStateException("Error del servidor: HTTP " + status);
        }

        String raw = read(connection.getInputStream());
        JSONObject json = new JSONObject(raw);
        String answer = json.optString("answer", "").trim();
        
        if (answer.isEmpty()) return new LocalGuide.Reply("No encontré información específica sobre eso en los manuales.", null);

        JSONArray items = json.optJSONArray("sources");
        StringBuilder sources = new StringBuilder();
        if (items != null && items.length() > 0) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject srcObj = items.optJSONObject(i);
                if (srcObj != null) {
                    String title = srcObj.optString("title", "").trim();
                    if (!title.isEmpty()) {
                        sources.append("• ").append(title).append("\n");
                    }
                }
            }
        }
        
        String sourceText = sources.length() == 0 ? null : sources.toString().trim();
        return new LocalGuide.Reply(answer, sourceText);
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append("\n");
        }
        return text.toString().trim();
    }
}
