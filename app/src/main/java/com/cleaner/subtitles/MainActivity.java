package com.cleaner.subtitles;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private String cleanedTextResult = "";
    private ActivityResultLauncher<String[]> selectFileLauncher;
    private ActivityResultLauncher<String> saveFileLauncher;

    // Регулярное выражение для поиска таймкодов вида 00:00:01,000 --> 00:00:04,000
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(\\d{1,2}:?\\d{2}:\\d{2}[.,]\\d{3})\\s*-->\\s*(\\d{1,2}:?\\d{2}:\\d{2}[.,]\\d{3})"
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Button btn = new Button(this);
        btn.setText("📂 Выбрать файл субтитров (.srt / .vtt / .smi)");
        btn.setTextSize(18);
        btn.setPadding(40, 50, 40, 50);

        selectFileLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    if (processSubtitleFile(uri)) {
                        saveFileLauncher.launch("structured_subtitles.txt");
                    }
                }
            }
        );

        saveFileLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"),
            uri -> {
                if (uri != null) {
                    saveToStorage(uri);
                }
            }
        );

        btn.setOnClickListener(v -> selectFileLauncher.launch(new String[]{"*/*"}));
        setContentView(btn);
    }

    private boolean processSubtitleFile(Uri uri) {
        try (InputStream inputStream = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            StringBuilder sb = new StringBuilder();
            String line;
            String lastLine = "";

            long lastEndTimeMs = -1;
            long currentStartTimeMs = -1;
            int currentParagraphLength = 0;
            boolean startNewSentence = true;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.matches("^\\d+$") || line.startsWith("WEBVTT") || line.startsWith("SAMI") || line.startsWith("<SAMI>")) {
                    continue;
                }

                // Извлечение таймкодов и расчет временных пауз
                Matcher matcher = TIME_PATTERN.matcher(line);
                if (matcher.find()) {
                    currentStartTimeMs = parseTimeToMs(matcher.group(1));
                    long newEndTimeMs = parseTimeToMs(matcher.group(2));

                    if (lastEndTimeMs > 0 && currentStartTimeMs > 0) {
                        long gap = currentStartTimeMs - lastEndTimeMs;

                        // Пауза больше 1.2 секунды ИЛИ накопилось > 350 символов в абзаце
                        if (gap >= 1200 || currentParagraphLength > 350) {
                            ensureSentenceEnd(sb);
                            sb.append("\n\n");
                            currentParagraphLength = 0;
                            startNewSentence = true;
                        }
                    }

                    lastEndTimeMs = newEndTimeMs;
                    continue;
                }

                // Очистка от HTML, CSS и спецсимволов
                line = line.replaceAll("<!--.*?-->", "")
                           .replaceAll("<[^>]+>", "")
                           .replaceAll("\\{[^}]*\\}", "")
                           .replaceAll("&nbsp;|&#160;", " ")
                           .replaceAll("&amp;", "&")
                           .replaceAll("&quot;", "\"")
                           .replaceAll("&lt;", "<")
                           .replaceAll("&gt;", ">")
                           .replaceAll("&#39;", "'")
                           .replaceAll("\\s+", " ")
                           .trim();

                if (!line.isEmpty() && !line.equals(lastLine)) {
                    // Форматирование первой буквы предложения
                    if (startNewSentence) {
                        line = capitalizeFirstChar(line);
                        startNewSentence = false;
                    }

                    sb.append(line).append(" ");
                    currentParagraphLength += line.length();
                    lastLine = line;
                }
            }

            // Финальная сборка и зачистка
            ensureSentenceEnd(sb);
            cleanedTextResult = sb.toString().trim();

            Toast.makeText(this, "Текст структурирован по абзацам!", Toast.LENGTH_SHORT).show();
            return true;

        } catch (Exception e) {
            Toast.makeText(this, "Ошибка обработки: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void ensureSentenceEnd(StringBuilder sb) {
        String str = sb.toString().trim();
        if (str.isEmpty()) return;

        char lastChar = str.charAt(str.length() - 1);
        if (lastChar != '.' && lastChar != '!' && lastChar != '?') {
            sb.trimToSize();
            // Убираем последний пробел перед точкой
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
                sb.deleteCharAt(sb.length() - 1);
            }
            sb.append(".");
        }
    }

    private String capitalizeFirstChar(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private long parseTimeToMs(String timeStr) {
        try {
            timeStr = timeStr.replace(',', '.');
            String[] parts = timeStr.split(":");
            if (parts.length == 3) {
                long hours = Long.parseLong(parts[0]);
                long minutes = Long.parseLong(parts[1]);
                double seconds = Double.parseDouble(parts[2]);
                return (long) ((hours * 3600 + minutes * 60 + seconds) * 1000);
            } else if (parts.length == 2) {
                long minutes = Long.parseLong(parts[0]);
                double seconds = Double.parseDouble(parts[1]);
                return (long) ((minutes * 60 + seconds) * 1000);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private void saveToStorage(Uri uri) {
        try (OutputStream outputStream = getContentResolver().openOutputStream(uri)) {
            outputStream.write(cleanedTextResult.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Успешно сохранено!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка сохранения: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
