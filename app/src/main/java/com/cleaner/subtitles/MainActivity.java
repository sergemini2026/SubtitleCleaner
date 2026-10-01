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

public class MainActivity extends AppCompatActivity {

    private String cleanedTextResult = "";
    private ActivityResultLauncher<String[]> selectFileLauncher;
    private ActivityResultLauncher<String> saveFileLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Button btn = new Button(this);
        btn.setText("📂 Выбрать файл субтитров (.srt / .vtt)");
        btn.setTextSize(18);
        btn.setPadding(40, 50, 40, 50);

        // Регистрация вызова системного проводника для выбора
        selectFileLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    if (processSubtitleFile(uri)) {
                        // После успешной обработки сразу вызываем проводник для сохранения
                        saveFileLauncher.launch("cleaned_subtitles.txt");
                    }
                }
            }
        );

        // Регистрация вызова системного проводника для сохранения
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

            while ((line = reader.readLine()) != null) {
                line = line.strip();

                // Пропуск пустых строк, таймкодов, номеров кадров и заголовка VTT
                if (line.isEmpty() || 
                    line.matches("^\\d+$") || 
                    line.matches("^\\d{2}:\\d{2}.*") || 
                    line.startsWith("WEBVTT") || 
                    line.contains("-->")) {
                    continue;
                }

                // Очистка HTML/VTT тегов
                line = line.replaceAll("<[^>]+>", "").strip();

                if (!line.isEmpty() && !line.equals(lastLine)) {
                    sb.append(line).append(" ");
                    lastLine = line;
                }
            }

            cleanedTextResult = sb.toString().trim();
            Toast.makeText(this, "Текст очищен. Выберите место для сохранения.", Toast.LENGTH_SHORT).show();
            return true;

        } catch (Exception e) {
            Toast.makeText(this, "Ошибка чтения файла: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
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
