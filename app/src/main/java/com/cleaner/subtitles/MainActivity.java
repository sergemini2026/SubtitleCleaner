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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private String cleanedTextResult = "";
    private ActivityResultLauncher<String[]> selectFileLauncher;
    private ActivityResultLauncher<String> saveFileLauncher;

    // Предлоги (не ставим точку ПОСЛЕ этих слов)
    private static final Set<String> PREPOSITIONS = new HashSet<>(Arrays.asList(
            "в", "во", "на", "с", "со", "из", "к", "ко", "о", "об", "обо",
            "для", "по", "под", "подо", "над", "надо", "при", "без", "безо",
            "до", "от", "ото", "через", "за", "между", "перед", "передо", "про", "сквозь"
    ));

    // Союзы и подчинительные слова (не ставим точку ПЕРЕД ними, а ставим запятую)
    private static final Set<String> CONJUNCTIONS = new HashSet<>(Arrays.asList(
            "что", "чтобы", "потому", "но", "а", "как", "где", "который", "которая",
            "которое", "которые", "если", "когда", "или", "также", "тоже", "зачем",
            "почему", "куда", "откуда", "чем", "хотя", "будто", "словно"
    ));

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

            StringBuilder rawContent = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                rawContent.append(line).append(" ");
            }

            // 1. Полная очистка от разметки, комментариев, таймкодов и тегов
            String text = rawContent.toString();

            text = text.replaceAll("<!--[\\s\\S]*?-->", " ");
            text = text.replaceAll("<[^>]+>", " ");
            text = text.replaceAll("\\[.*?\\]", " ");
            text = text.replaceAll("\\{[^}]*\\}", " ");
            text = text.replaceAll("\\d{1,2}:?\\d{2}:\\d{2}[.,]\\d{3}\\s*-->\\s*\\d{1,2}:?\\d{2}:\\d{2}[.,]\\d{3}", " ");
            text = text.replaceAll("(?m)^\\d+$", " ");
            text = text.replaceAll("&nbsp;|&#160;", " ")
                       .replaceAll("&amp;", "&")
                       .replaceAll("&quot;", "\"")
                       .replaceAll("&lt;", "<")
                       .replaceAll("&gt;", ">")
                       .replaceAll("&#39;", "'");

            text = text.replaceAll("\\s+", " ").trim();

            if (text.isEmpty()) {
                Toast.makeText(this, "Файл не содержит текста!", Toast.LENGTH_SHORT).show();
                return false;
            }

            // 2. Разбиение на слова и применение грамматической эвристики
            String[] words = text.split(" ");
            List<String> cleanWords = new ArrayList<>();

            for (String w : words) {
                if (!w.equalsIgnoreCase("WEBVTT") && !w.equalsIgnoreCase("SAMI") && !w.equalsIgnoreCase("STYLE")) {
                    cleanWords.add(w);
                }
            }

            StringBuilder result = new StringBuilder();
            List<String> currentSentence = new ArrayList<>();

            Random random = new Random();
            int sentenceCountInParagraph = 0;
            // Длина текущего абзаца случайно от 6 до 10 предложений
            int targetParagraphLength = 6 + random.nextInt(5);

            for (int i = 0; i < cleanWords.size(); i++) {
                String word = cleanWords.get(i);
                String wordLower = word.toLowerCase();

                currentSentence.add(word);

                boolean isLastWordInFile = (i == cleanWords.size() - 1);
                String nextWordLower = (!isLastWordInFile) ? cleanWords.get(i + 1).toLowerCase() : "";

                // Автоматическая подстановка запятой перед союзом
                if (currentSentence.size() >= 5 && CONJUNCTIONS.contains(nextWordLower)) {
                    if (!word.endsWith(",") && !word.endsWith(".") && !word.endsWith("!") && !word.endsWith("?")) {
                        currentSentence.set(currentSentence.size() - 1, word + ",");
                    }
                }

                // Условия завершения предложения
                boolean targetLengthReached = currentSentence.size() >= 12;
                boolean notPreposition = !PREPOSITIONS.contains(wordLower);
                boolean nextNotConjunction = !CONJUNCTIONS.contains(nextWordLower);
                boolean forceBreak = currentSentence.size() >= 22;

                if ((targetLengthReached && notPreposition && nextNotConjunction) || forceBreak || isLastWordInFile) {
                    String formattedSentence = buildSentenceString(currentSentence);
                    result.append(formattedSentence);

                    sentenceCountInParagraph++;
                    currentSentence.clear();

                    // Формирование абзаца с псевдослучайной длиной (6–10 предложений)
                    if (sentenceCountInParagraph >= targetParagraphLength) {
                        result.append("\n\n");
                        sentenceCountInParagraph = 0;
                        targetParagraphLength = 6 + random.nextInt(5); // Новое случайное число для следующего абзаца
                    } else {
                        result.append(" ");
                    }
                }
            }

            cleanedTextResult = result.toString().trim();
            Toast.makeText(this, "Текст успешно структурирован!", Toast.LENGTH_SHORT).show();
            return true;

        } catch (Exception e) {
            Toast.makeText(this, "Ошибка обработки: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private String buildSentenceString(List<String> words) {
        if (words.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            if (i == 0) {
                w = Character.toUpperCase(w.charAt(0)) + w.substring(1);
            }
            sb.append(w);
            if (i < words.size() - 1) {
                sb.append(" ");
            }
        }

        String sentence = sb.toString().trim();
        char lastChar = sentence.charAt(sentence.length() - 1);
        if (lastChar != '.' && lastChar != '!' && lastChar != '?' && lastChar != ',') {
            sentence += ".";
        } else if (lastChar == ',') {
            sentence = sentence.substring(0, sentence.length() - 1) + ".";
        }

        return sentence;
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
