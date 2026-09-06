package com.uteq.software.labrumiologia.data;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.uteq.software.labrumiologia.BuildConfig;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/** Asistente RAG local (guías en assets) + Gemini para redactar la respuesta. */
public class LocalGuide {
    private static final String TAG = "LocalGuide";

    public static class Reply {
        public final String answer;
        public final String sources;

        public Reply(String answer, String sources) {
            this.answer = answer;
            this.sources = sources;
        }
    }

    private final Context context;

    public LocalGuide(Context context) {
        this.context = context.getApplicationContext();
    }

    public Reply ask(String question, String equipmentId) {
        List<Chunk> chunks = searchLocalDocs(question, equipmentId);
        if (chunks.isEmpty()) {
            return new Reply(
                    "No dispongo de información suficiente en las guías del laboratorio. "
                            + "Consulte al docente o al responsable del laboratorio.",
                    null
            );
        }

        String apiKey = BuildConfig.GEMINI_API_KEY;
        if (apiKey != null && !apiKey.isEmpty()) {
            try {
                return askGemini(question, equipmentId, chunks, apiKey);
            } catch (Exception e) {
                Log.w(TAG, "Gemini falló: " + e.getMessage());
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (isCreditsDepleted(msg)) {
                    return buildFallbackReply(
                            chunks,
                            "Los créditos de Google AI Studio de esta clave están agotados.\n\n"
                                    + "Entre a https://aistudio.google.com → su proyecto → facturación/créditos,\n"
                                    + "o cree una API key nueva con otra cuenta de Google.\n\n"
                                    + answerFromGuides(question, chunks)
                    );
                }
                return buildFallbackReply(
                        chunks,
                        "Gemini no respondió. Detalle: " + msg + "\n\n"
                                + answerFromGuides(question, chunks)
                );
            }
        }

        return buildFallbackReply(
                chunks,
                "No se pudo conectar con el backend de OpenAI File Search.\n\n"
                        + "Compruebe que el computador y el teléfono estén en la misma red, "
                        + "y que el servidor del backend continúe encendido.\n\n"
                        + summarizeChunks(chunks)
        );
    }

    private static final String[] MODEL_FALLBACKS = {
            "gemini-3.6-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.5-flash",
            "gemini-3.1-flash-lite",
            "gemini-flash-lite-latest",
    };

    private Reply askGemini(String question, String equipmentId, List<Chunk> chunks, String apiKey)
            throws Exception {
        StringBuilder ctx = new StringBuilder();
        StringBuilder sources = new StringBuilder();
        for (Chunk c : chunks) {
            ctx.append(c.text).append("\n\n");
            sources.append("• ").append(c.title).append("\n");
        }

        String prompt =
                "Eres un asistente del Laboratorio de Rumiología (UTEQ). "
                        + "Responde en español, de forma clara y práctica, solo con base en el contexto. "
                        + "Si el contexto no alcanza, dilo sin inventar datos.\n"
                        + "Al final, en una línea que empiece con 'Fuentes:', cita los documentos usados.\n\n"
                        + "Equipo detectado: " + equipmentId + "\n\n"
                        + "Contexto de las guías:\n" + ctx + "\n"
                        + "Pregunta del estudiante: " + question;

        Exception lastError = null;
        String configured = BuildConfig.GEMINI_MODEL;
        List<String> models = new ArrayList<>();
        if (configured != null && !configured.isEmpty()) models.add(configured);
        for (String m : MODEL_FALLBACKS) {
            if (!models.contains(m)) models.add(m);
        }

        for (String model : models) {
            try {
                return callGemini(model, apiKey, prompt, sources);
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "Modelo " + model + " falló: " + e.getMessage());
            }
        }
        throw lastError != null ? lastError : new Exception("ningún modelo Gemini respondió");
    }

    private Reply callGemini(String model, String apiKey, String prompt, StringBuilder sources)
            throws Exception {
        URL url = new URL(
                "https://generativelanguage.googleapis.com/v1beta/models/"
                        + model
                        + ":generateContent"
        );
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(45_000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("x-goog-api-key", apiKey);

        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject part = new JSONObject();
        part.put("text", prompt);
        JSONObject content = new JSONObject();
        content.put("parts", new JSONArray().put(part));
        contents.put(content);
        body.put("contents", contents);
        conn.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));

        int code = conn.getResponseCode();
        String raw = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        if (code >= 400) {
            throw new Exception("HTTP " + code + ": " + parseGeminiError(raw));
        }

        JSONObject json = new JSONObject(raw);
        JSONArray candidates = json.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new Exception("respuesta vacía del modelo");
        }
        JSONObject first = candidates.getJSONObject(0);
        JSONObject contentOut = first.optJSONObject("content");
        if (contentOut == null) {
            throw new Exception("bloqueo o filtro del modelo");
        }
        String answer = contentOut.getJSONArray("parts").getJSONObject(0).getString("text").trim();

        String sourcesBlock = context.getString(com.uteq.software.labrumiologia.R.string.sources_label)
                + "\n"
                + sources.toString().trim();
        return new Reply(answer, sourcesBlock);
    }

    private static String parseGeminiError(String raw) {
        try {
            JSONObject json = new JSONObject(raw);
            JSONObject err = json.optJSONObject("error");
            if (err != null) {
                return err.optString("message", raw);
            }
        } catch (Exception ignored) {
        }
        return raw.length() > 200 ? raw.substring(0, 200) + "…" : raw;
    }

    private static String readStream(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder resp = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) resp.append(line);
        }
        return resp.toString();
    }

    private Reply buildFallbackReply(List<Chunk> chunks, String header) {
        StringBuilder sources = new StringBuilder(
                context.getString(com.uteq.software.labrumiologia.R.string.sources_label)).append("\n");
        for (int i = 0; i < Math.min(3, chunks.size()); i++) {
            sources.append("• ").append(chunks.get(i).title).append("\n");
        }
        return new Reply(header.trim(), sources.toString().trim());
    }

    private static boolean isCreditsDepleted(String message) {
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("prepayment credits") || m.contains("credits are depleted");
    }

    private static String answerFromGuides(String question, List<Chunk> chunks) {
        String q = question.toLowerCase(Locale.ROOT);
        String[] headings;
        if (q.contains("segur") || q.contains("epp") || q.contains("riesg")) {
            headings = new String[]{"Seguridad", "EPP", "Riesgos"};
        } else if (q.contains("manten") || q.contains("limp")) {
            headings = new String[]{"Mantenimiento", "Limpieza"};
        } else if (q.contains("uso") || q.contains("usar") || q.contains("funciona") || q.contains("oper")) {
            headings = new String[]{
                    "Procedimiento básico",
                    "Uso en rumiología",
                    "Uso básico",
                    "Uso",
                    "Operación",
                    "Procedimiento",
                    "Condiciones de operación",
            };
        } else {
            headings = new String[]{"Uso básico", "Uso", "Función", "Identificación"};
        }

        StringBuilder answer = new StringBuilder();
        for (Chunk chunk : chunks) {
            String section = extractSection(chunk.text, headings);
            if (!section.isEmpty()) {
                if (answer.length() > 0) answer.append("\n\n");
                answer.append(plainText(section));
            }
        }
        if (answer.length() == 0) {
            return summarizeChunks(chunks);
        }
        return answer.toString().trim();
    }

    private static String extractSection(String markdown, String[] headings) {
        for (String heading : headings) {
            String pattern = "(?is)(?:^|\\n)#+\\s*" + java.util.regex.Pattern.quote(heading) + ".*\\n(.*?)(?=\\n#+\\s|$)";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(markdown);
            if (m.find()) {
                String body = m.group(1).trim();
                if (!body.isEmpty()) return body;
            }
        }
        return "";
    }

    private static String summarizeChunks(List<Chunk> chunks) {
        StringBuilder sb = new StringBuilder("Resumen de las guías:\n\n");
        int n = Math.min(2, chunks.size());
        for (int i = 0; i < n; i++) {
            String text = plainText(chunks.get(i).text);
            if (text.length() > 400) text = text.substring(0, 400).trim() + "…";
            sb.append(text).append("\n\n");
        }
        return sb.toString().trim();
    }

    private static String plainText(String markdown) {
        return markdown
                .replaceAll("(?m)^#+\\s*", "")
                .replaceAll("\\*\\*", "")
                .replaceAll("`", "")
                .replaceAll("(?m)^-\\s+", "• ")
                .trim();
    }

    private List<Chunk> searchLocalDocs(String question, String equipmentId) {
        List<Chunk> hits = new ArrayList<>();
        String q = question.toLowerCase(Locale.ROOT);
        String[] tokens = q.split("\\s+");
        AssetManager am = context.getAssets();
        try {
            String[] roots = am.list("docs");
            if (roots == null) return hits;
            for (String folder : roots) {
                if (equipmentId != null && !equipmentId.isEmpty()
                        && !folder.equals(equipmentId) && !folder.equals("_general")) {
                    continue;
                }
                String[] files = am.list("docs/" + folder);
                if (files == null) continue;
                for (String file : files) {
                    if (!file.endsWith(".md") && !file.endsWith(".txt")) continue;
                    String path = "docs/" + folder + "/" + file;
                    String text = readAsset(path);
                    if (text.isEmpty()) continue;
                    int score = score(text.toLowerCase(Locale.ROOT), tokens, equipmentId);
                    if (score > 0) {
                        hits.add(new Chunk(file, text, score));
                    }
                }
            }
            if (hits.isEmpty() && equipmentId != null && !equipmentId.isEmpty()) {
                for (String file : am.list("docs/" + equipmentId)) {
                    if (file == null || (!file.endsWith(".md") && !file.endsWith(".txt"))) continue;
                    String text = readAsset("docs/" + equipmentId + "/" + file);
                    if (!text.isEmpty()) {
                        hits.add(new Chunk(file, text, 1));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        hits.sort((a, b) -> Integer.compare(b.score, a.score));
        if (hits.size() > 4) return hits.subList(0, 4);
        return hits;
    }

    private static int score(String text, String[] tokens, String equipmentId) {
        int s = 0;
        if (equipmentId != null && text.contains(equipmentId.replace("_", " "))) s += 3;
        for (String t : tokens) {
            if (t.length() > 2 && text.contains(t)) s++;
        }
        return s;
    }

    private String readAsset(String path) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(context.getAssets().open(path), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static class Chunk {
        final String title;
        final String text;
        final int score;

        Chunk(String title, String text, int score) {
            this.title = title;
            this.text = text;
            this.score = score;
        }
    }
}
