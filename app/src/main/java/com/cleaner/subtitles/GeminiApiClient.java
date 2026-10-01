package com.cleaner.subtitles;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GeminiApiClient {

    private static final String API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface ApiCallback {
        void onSuccess(String resultText);
        void onError(String errorMessage);
    }

    public void processTextWithGemini(String apiKey, String inputText, ApiCallback callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                // Полная очистка ключа от пробелов, переносов строк и табуляций
                String cleanKey = apiKey.trim().replaceAll("\\s+", "");
                URL url = new URL(API_URL + cleanKey);

                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);

                String systemPrompt = "Ты — профессиональный редактор и корректор расшифровок публичных лекций. " +
                        "Тебе дан сырой структурированный текст субтитров. " +
                        "Твоя задача:\n" +
                        "1. Расставить правильные знаки препинания (точки, запятые, тире, двоеточия) и заглавные буквы.\n" +
                        "2. Разбить текст на естественные, логически завершенные абзацы по смыслу и смене темы.\n" +
                        "3. Исправить явные ошибки автоматического распознавания речи (фонетические опечатки).\n" +
                        "4. СТРОГО СОХРАНЯТЬ авторскую речь: не сокращай, не перефразируй и не добавляй ничего от себя.\n" +
                        "Верни ТОЛЬКО отредактированный текст без каких-либо вводных фраз и комментариев.";

                JSONObject root = new JSONObject();
                JSONArray contents = new JSONArray();
                JSONObject contentObj = new JSONObject();
                JSONArray parts = new JSONArray();

                JSONObject promptPart = new JSONObject();
                promptPart.put("text", systemPrompt + "\n\nВот текст для редакторской правки:\n\n" + inputText);
                parts.put(promptPart);

                contentObj.put("parts", parts);
                contents.put(contentObj);
                root.put("contents", contents);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = root.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            response.append(responseLine.trim());
                        }

                        JSONObject jsonResponse = new JSONObject(response.toString());
                        String resultText = jsonResponse.getJSONArray("candidates")
                                .getJSONObject(0)
                                .getJSONObject("content")
                                .getJSONArray("parts")
                                .getJSONObject(0)
                                .getString("text");

                        mainHandler.post(() -> callback.onSuccess(resultText));
                    }
                } else {
                    // Чтение расшифровки ошибки от Google при любом статусе, отличном от 200 OK
                    InputStream errorStream = conn.getErrorStream();
                    String errorDetails = "";
                    if (errorStream != null) {
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = br.readLine()) != null) {
                                sb.append(line);
                            }
                            errorDetails = sb.toString();
                        }
                    }
                    String finalErr = "HTTP " + responseCode + (errorDetails.isEmpty() ? "" : ": " + errorDetails);
                    mainHandler.post(() -> callback.onError(finalErr));
                }

            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Ошибка подключения: " + e.getLocalizedMessage()));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }
}
