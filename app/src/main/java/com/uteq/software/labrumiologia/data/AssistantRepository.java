package com.uteq.software.labrumiologia.data;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class AssistantRepository {
    private static final String URL_RESPONSES = "https://api.openai.com/v1/responses";
    private final ApiKeyStore keyStore;
    private final JSONObject knowledge;
    private final Context context;

    public static final class Reply {
        public final String answer;
        public final String sources;
        public Reply(String answer, String sources) { this.answer = answer; this.sources = sources; }
    }

    public AssistantRepository(Context context) {
        this.context = context.getApplicationContext();
        keyStore = new ApiKeyStore(context);
        knowledge = readKnowledge(context);
    }

    public Reply ask(String question, String equipmentId) {
        String key = keyStore.getKey();
        if (key == null) return new Reply("Configure su API key de OpenAI para continuar.", null);
        try {
            JSONArray stores = vectorStores(equipmentId);
            if (stores.length() > 0) {
                try {
                    JSONObject request = baseRequest("gpt-4o-mini", question,
                            "Responde en español usando solamente los documentos del equipo. " +
                            "Si no contienen la respuesta, responde exactamente [[SIN_INFORMACION]]. " +
                            "No inventes procedimientos ni parámetros.");
                    request.put("tools", new JSONArray().put(new JSONObject()
                            .put("type", "file_search").put("vector_store_ids", stores)
                            .put("max_num_results", 4)));
                    request.put("tool_choice", "required");
                    String answer = outputText(call(key, request));
                    if (!answer.isEmpty() && !answer.contains("[[SIN_INFORMACION]]")) {
                        return new Reply(answer, null);
                    }
                } catch (IllegalStateException error) {
                    if (isAuthenticationError(error)) throw error;
                }
            }

            String guides = loadGuides(equipmentId);
            if (!guides.isEmpty()) {
                JSONObject request = baseRequest("gpt-4o-mini",
                        "PREGUNTA:\n" + question + "\n\nGUÍAS DEL LABORATORIO:\n" + guides,
                        "Responde en español únicamente con las guías proporcionadas. Si no " +
                        "contienen la respuesta, responde exactamente [[SIN_INFORMACION]]. " +
                        "No inventes procedimientos ni parámetros.");
                String answer = outputText(call(key, request));
                if (!answer.isEmpty() && !answer.contains("[[SIN_INFORMACION]]")) {
                    return new Reply(answer, null);
                }
            }

            JSONObject request = baseRequest("gpt-5-mini", question,
                    "Busca en la web y responde brevemente en español. Prioriza al fabricante " +
                    "y fuentes técnicas oficiales. Aclara que la información proviene de la web " +
                    "y que el protocolo del laboratorio tiene prioridad. No inventes datos.");
            request.put("tools", new JSONArray().put(new JSONObject().put("type", "web_search")));
            request.put("tool_choice", "required");
            request.put("max_tool_calls", 1);
            String answer = outputText(call(key, request));
            return new Reply(answer.isEmpty() ? "No encontré información verificable para responder." : answer, null);
        } catch (Exception error) {
            String message = error.getMessage();
            return new Reply(message == null || message.isEmpty() ? "Error al consultar OpenAI." : message, null);
        }
    }

    private static boolean isAuthenticationError(IllegalStateException error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        return message.contains("api key") && (message.contains("no es válida") || message.contains("incorrect"));
    }

    private String loadGuides(String equipmentId) {
        StringBuilder result = new StringBuilder();
        appendDirectory(result, "docs/_general");
        if (equipmentId != null && !equipmentId.isEmpty()) {
            appendDirectory(result, "docs/" + equipmentId);
        }
        int maxCharacters = 24_000;
        return result.length() > maxCharacters ? result.substring(0, maxCharacters) : result.toString();
    }

    private void appendDirectory(StringBuilder target, String directory) {
        try {
            String[] files = context.getAssets().list(directory);
            if (files == null) return;
            for (String file : files) {
                if (!file.endsWith(".md") && !file.endsWith(".txt")) continue;
                try (InputStream stream = context.getAssets().open(directory + "/" + file)) {
                    target.append("\n--- ").append(file).append(" ---\n").append(read(stream));
                }
            }
        } catch (Exception ignored) { }
    }

    private JSONArray vectorStores(String equipmentId) {
        JSONArray result = new JSONArray();
        JSONObject equipments = knowledge.optJSONObject("equipments");
        String equipment = equipments == null ? "" : equipments.optString(equipmentId, "");
        String shared = knowledge.optString("shared", "");
        if (!equipment.isEmpty()) result.put(equipment);
        if (!shared.isEmpty()) result.put(shared);
        return result;
    }

    private static JSONObject baseRequest(String model, String question, String instructions) throws Exception {
        return new JSONObject().put("model", model).put("input", question)
                .put("instructions", instructions).put("max_output_tokens", 300).put("store", false);
    }

    private static JSONObject call(String key, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(URL_RESPONSES).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(30_000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
        int status = connection.getResponseCode();
        String raw = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
        if (status == 401) throw new IllegalStateException("La API key de OpenAI no es válida o no tiene acceso.");
        if (status >= 400) {
            JSONObject error = raw.isEmpty() ? null : new JSONObject(raw).optJSONObject("error");
            String detail = error == null ? "" : error.optString("message", "");
            throw new IllegalStateException(detail.isEmpty() ? "OpenAI respondió HTTP " + status : detail);
        }
        return new JSONObject(raw);
    }

    private static String outputText(JSONObject response) {
        JSONArray output = response.optJSONArray("output");
        if (output == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            JSONArray content = item == null ? null : item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part != null && "output_text".equals(part.optString("type"))) {
                    if (result.length() > 0) result.append('\n');
                    result.append(part.optString("text", ""));
                }
            }
        }
        return result.toString().trim();
    }

    private static JSONObject readKnowledge(Context context) {
        try (InputStream stream = context.getAssets().open("equipment_knowledge.json")) {
            return new JSONObject(read(stream));
        } catch (Exception ignored) { return new JSONObject(); }
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
        }
        return text.toString().trim();
    }
}
