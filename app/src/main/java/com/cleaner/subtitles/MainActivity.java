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

    // СЛОВА, НА КОТОРЫХ КАТЕГОРИЧЕСКИ НЕЛЬЗЯ ЗАКАНЧИВАТЬ ПРЕДЛОЖЕНИЕ (предлоги, союзы, частицы)
    private static final Set<String> FORBIDDEN_END_WORDS = new HashSet<>(Arrays.asList(
            // Предлоги
            "в", "во", "на", "с", "со", "из", "к", "ко", "о", "об", "обо",
            "для", "по", "под", "подо", "над", "надо", "при", "без", "безо",
            "до", "от", "ото", "через", "за", "между", "перед", "передо", "про", "сквозь",
            // Союзы и связки
            "и", "а", "но", "да", "или", "либо", "что", "чтобы", "как", "где", "когда",
            "если", "хотя", "будто", "словно", "чем", "зачем", "почему", "куда", "откуда",
            "который", "которая", "которое", "которые", "также", "тоже", "ибо",
            // Частицы
            "ли", "же", "бы", "даже", "ни", "не"
    ));

    // Союзы для подстановки запятых ПЕРЕД ними
    private static final Set<String> CONJUNCTIONS_FOR_COMMA = new HashSet<>(Arrays.asList(
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

            // 1. Очистка от служебного мусора, тегов, комментариев и таймкодов
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

            // 2. Разбиение на чистые слова
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
            int targetParagraphLength = getNextParagraphLength(random);

            for (int i = 0; i < cleanWords.size(); i++) {
                String word = cleanWords.get(i);
                String wordLower = word.toLowerCase().replaceAll("[^a-zа-я0-яё]", "");

                currentSentence.add(word);

                boolean isLastWordInFile = (i == cleanWords.size() - 1);
                String nextWordLower = (!isLastWordInFile) 
                        ? cleanWords.get(i + 1).toLowerCase().replaceAll("[^a-zа-я0-яё]", "") 
                        : "";

                // Авто-запятая перед союзом
                if (currentSentence.size() >= 4 && CONJUNCTIONS_FOR_COMMA.contains(nextWordLower)) {
                    if (!word.endsWith(",") && !word.endsWith(".") && !word.endsWith("!") && !word.endsWith("?")) {
                        currentSentence.set(currentSentence.size() - 1, word + ",");
                    }
                }

                // ПРАВИЛА ЗАКРЫТИЯ ПРЕДЛОЖЕНИЯ
                boolean targetLengthReached = currentSentence.size() >= 12;
                boolean canEndHere = !FORBIDDEN_END_WORDS.contains(wordLower);
                boolean nextIsConjunction = CONJUNCTIONS_FOR_COMMA.contains(nextWordLower);
                boolean forceBreak = currentSentence.size() >= 26;

                // Закрываем предложение только если набрана длина, слово НЕ в запрещенном списке, и дальше не идет союз
                if (((targetLengthReached && canEndHere && !nextIsConjunction) || (forceBreak && canEndHere) || isLastWordInFile)) {
                    String formattedSentence = buildSentenceString(currentSentence);
                    result.append(formattedSentence);

                    sentenceCountInParagraph++;
                    currentSentence.clear();

                    // Формирование абзаца с динамической разбивкой (от 2 до 11 предложений)
                    if (sentenceCountInParagraph >= targetParagraphLength) {
                        result.append("\n\n");
                        sentenceCountInParagraph = 0;
                        targetParagraphLength = getNextParagraphLength(random);
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

    // Генерация случайной длины абзаца с живым ритмом
    private int getNextParagraphLength(Random random) {
        int roll = random.nextInt(100);
        if (roll < 20) {
            return 2 + random.nextInt(2);  // 20% шанса: короткий абзац (2-3 предложения)
        } else if (roll < 70) {
            return 4 + random.nextInt(4);  // 50% шанса: средний абзац (4-7 предложений)
        } else {
            return 8 + random.nextInt(4);  // 30% шанса: длинный абзац (8-11 предложений)
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
        
        // Гарантируем корректную точку на конце
        if (lastChar != '.' && lastChar != '!' && lastChar != '?') {
            if (lastChar == ',') {
                sentence = sentence.substring(0, sentence.length() - 1);
            }
            sentence += ".";
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
