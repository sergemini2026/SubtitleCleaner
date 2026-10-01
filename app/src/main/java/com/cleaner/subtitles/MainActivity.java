package com.cleaner.subtitles;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
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

    private EditText etApiKey;
    private Button btnSelectFile;
    private Button btnProcessAi;
    private ProgressBar progressBar;
    private TextView tvStatus;

    private SharedPreferences prefs;
    private final GeminiApiClient geminiClient = new GeminiApiClient();

    private static final String PREF_KEY_API = "gemini_api_key";

    private static final Set<String> FORBIDDEN_END_WORDS = new HashSet<>(Arrays.asList(
            "в", "во", "на", "с", "со", "из", "к", "ко", "о", "об", "обо",
            "для", "по", "под", "подо", "над", "надо", "при", "без", "безо",
            "до", "от", "ото", "через", "за", "между", "перед", "передо", "про", "сквозь",
            "и", "а", "но", "да", "или", "либо", "что", "чтобы", "как", "где", "когда",
            "если", "хотя", "будто", "словно", "чем", "зачем", "почему", "куда", "откуда",
            "который", "которая", "которое", "которые", "также", "тоже", "ибо",
            "ли", "же", "бы", "даже", "ни", "не"
    ));

    private static final Set<String> CONJUNCTIONS_FOR_COMMA = new HashSet<>(Arrays.asList(
            "что", "чтобы", "потому", "но", "а", "как", "где", "который", "которая",
            "которое", "которые", "если", "когда", "или", "также", "тоже", "зачем",
            "почему", "куда", "откуда", "чем", "хотя", "будто", "словно"
    ));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("SubtitleCleanerPrefs", MODE_PRIVATE);

        // Построение пользовательского интерфейса
        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🔑 Gemini API Key:");
        tvTitle.setTextSize(16);

        etApiKey = new EditText(this);
        etApiKey.setHint("Вставьте ваш API ключ здесь");
        etApiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etApiKey.setText(prefs.getString(PREF_KEY_API, ""));

        btnSelectFile = new Button(this);
        btnSelectFile.setText("📂 1. Выбрать и очистить файл субтитров");
        btnSelectFile.setTextSize(16);

        btnProcessAi = new Button(this);
        btnProcessAi.setText("✨ 2. AI анализ (Онлайн)");
        btnProcessAi.setTextSize(16);
        btnProcessAi.setEnabled(false);

        progressBar = new ProgressBar(this);
        progressBar.setVisibility(View.GONE);

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, 20, 0, 20);
        tvStatus.setTextSize(14);

        layout.addView(tvTitle);
        layout.addView(etApiKey);
        layout.addView(btnSelectFile);
        layout.addView(btnProcessAi);
        layout.addView(progressBar);
        layout.addView(tvStatus);
        scrollView.addView(layout);

        setContentView(scrollView);

        selectFileLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    saveApiKey();
                    if (processSubtitleFile(uri)) {
                        btnProcessAi.setEnabled(true);
                        tvStatus.setText("Файл первично очищен! Теперь можно сохранить его как есть или обработать через Gemini AI.");
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

        btnSelectFile.setOnClickListener(v -> selectFileLauncher.launch(new String[]{"*/*"}));

        btnProcessAi.setOnClickListener(v -> {
            saveApiKey();
            String key = etApiKey.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(this, "Введите Gemini API Key!", Toast.LENGTH_SHORT).show();
                return;
            }

            if (cleanedTextResult.isEmpty()) {
                Toast.makeText(this, "Сначала выберите файл субтитров!", Toast.LENGTH_SHORT).show();
                return;
            }

            progressBar.setVisibility(View.VISIBLE);
            btnProcessAi.setEnabled(false);
            tvStatus.setText("Отправка текста в Gemini AI...");

            geminiClient.processTextWithGemini(key, cleanedTextResult, new GeminiApiClient.ApiCallback() {
                @Override
                public void onSuccess(String resultText) {
                    progressBar.setVisibility(View.GONE);
                    btnProcessAi.setEnabled(true);
                    cleanedTextResult = resultText;
                    tvStatus.setText("Текст идеально обработан нейросетью! Выберите место для сохранения.");
                    saveFileLauncher.launch("ai_edited_subtitles.txt");
                }

                @Override
                public void onError(String errorMessage) {
                    progressBar.setVisibility(View.GONE);
                    btnProcessAi.setEnabled(true);
                    tvStatus.setText("Ошибка: " + errorMessage);
                    Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void saveApiKey() {
        String key = etApiKey.getText().toString().trim();
        prefs.edit().putString(PREF_KEY_API, key).apply();
    }

    private boolean processSubtitleFile(Uri uri) {
        try (InputStream inputStream = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            StringBuilder rawContent = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                rawContent.append(line).append(" ");
            }

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

                if (currentSentence.size() >= 4 && CONJUNCTIONS_FOR_COMMA.contains(nextWordLower)) {
                    if (!word.endsWith(",") && !word.endsWith(".") && !word.endsWith("!") && !word.endsWith("?")) {
                        currentSentence.set(currentSentence.size() - 1, word + ",");
                    }
                }

                boolean targetLengthReached = currentSentence.size() >= 12;
                boolean canEndHere = !FORBIDDEN_END_WORDS.contains(wordLower);
                boolean nextIsConjunction = CONJUNCTIONS_FOR_COMMA.contains(nextWordLower);
                boolean forceBreak = currentSentence.size() >= 26;

                if (((targetLengthReached && canEndHere && !nextIsConjunction) || (forceBreak && canEndHere) || isLastWordInFile)) {
                    String formattedSentence = buildSentenceString(currentSentence);
                    result.append(formattedSentence);

                    sentenceCountInParagraph++;
                    currentSentence.clear();

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
            Toast.makeText(this, "Файл прочитан и подготовлен!", Toast.LENGTH_SHORT).show();
            return true;

        } catch (Exception e) {
            Toast.makeText(this, "Ошибка обработки: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private int getNextParagraphLength(Random random) {
        int roll = random.nextInt(100);
        if (roll < 20) {
            return 2 + random.nextInt(2);
        } else if (roll < 70) {
            return 4 + random.nextInt(4);
        } else {
            return 8 + random.nextInt(4);
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
