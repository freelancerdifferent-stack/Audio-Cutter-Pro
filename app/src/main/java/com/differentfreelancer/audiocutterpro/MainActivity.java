package com.differentfreelancer.audiocutterpro;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_PICK_FILE = 1001;
    private static final int REQ_PICK_EXPORT_FOLDER = 1002;

    private final int BG = Color.rgb(9, 11, 16);
    private final int CARD = Color.rgb(17, 21, 29);
    private final int CARD_2 = Color.rgb(23, 28, 38);
    private final int BORDER = Color.rgb(48, 55, 69);
    private final int TEXT = Color.rgb(245, 247, 250);
    private final int MUTED = Color.rgb(158, 166, 183);
    private final int ACCENT = Color.rgb(124, 92, 252);
    private final int ORANGE = Color.rgb(255, 178, 61);

    private Uri sourceUri;
    private MediaPlayer player;
    private long durationMs;
    private final ArrayList<Long> cuts = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView fileNameText;
    private TextView timeText;
    private TextView cutCountText;
    private TextView statusText;
    private WaveformView waveformView;
    private SeekBar seekBar;
    private Button playButton;
    private Button addCutButton;
    private Button exportButton;
    private Button undoButton;
    private Button resetButton;
    private EditText targetInput;
    private EditText prefixInput;
    private LinearLayout segmentsContainer;
    private SeekBar pitchBar;
    private TextView pitchValueText;
    private float pitchSemitones = 0f;

    private boolean userSeeking = false;

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null && durationMs > 0) {
                try {
                    int pos = player.getCurrentPosition();
                    if (!userSeeking) {
                        seekBar.setProgress((int) ((pos * 10000L) / Math.max(1, durationMs)));
                    }
                    waveformView.setPlayhead(pos);
                    timeText.setText(formatTime(pos) + " / " + formatTime(durationMs));
                    playButton.setText(player.isPlaying() ? "PAUSE" : "PLAY");
                } catch (Exception ignored) {}
            }
            handler.postDelayed(this, 40);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        handler.post(progressRunnable);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = text("Audio Cutter Pro", 28, TEXT, true);
        root.addView(title);

        TextView subtitle = text("Manual cut untuk voice game • 29 part • MP3 / MP4 / M4A", 14, MUTED, false);
        subtitle.setPadding(0, dp(4), 0, dp(18));
        root.addView(subtitle);

        LinearLayout sourceCard = card();
        root.addView(sourceCard, cardParams());

        Button pickButton = primaryButton("PILIH AUDIO / VIDEO");
        sourceCard.addView(pickButton, matchWrap());
        pickButton.setOnClickListener(v -> pickFile());

        fileNameText = text("Belum ada file dipilih", 14, MUTED, false);
        fileNameText.setPadding(0, dp(10), 0, 0);
        sourceCard.addView(fileNameText);

        waveformView = new WaveformView(this);
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170)
        );
        waveParams.topMargin = dp(14);
        sourceCard.addView(waveformView, waveParams);

        TextView waveHint = text("Cubit 2 jari untuk zoom in/out. Geser waveform kiri/kanan saat zoom. Tap untuk seek, drag garis kuning untuk menggeser cut.", 12, MUTED, false);
        waveHint.setPadding(0, dp(8), 0, 0);
        sourceCard.addView(waveHint);

        seekBar = new SeekBar(this);
        seekBar.setMax(10000);
        seekBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        seekBar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        sourceCard.addView(seekBar, matchWrap());

        timeText = text("00:00.000 / 00:00.000", 14, TEXT, true);
        timeText.setGravity(Gravity.CENTER);
        sourceCard.addView(timeText);

        LinearLayout playback = horizontal();
        playback.setPadding(0, dp(10), 0, 0);
        sourceCard.addView(playback, matchWrap());

        Button minusButton = secondaryButton("-100 ms");
        playButton = primaryButton("PLAY");
        Button plusButton = secondaryButton("+100 ms");
        playback.addView(minusButton, weightedButton());
        playback.addView(playButton, weightedButton());
        playback.addView(plusButton, weightedButton());

        playButton.setOnClickListener(v -> togglePlay());
        minusButton.setOnClickListener(v -> nudge(-100));
        plusButton.setOnClickListener(v -> nudge(100));

        waveformView.setOnSeekListener(positionMs -> seekTo(positionMs));
        waveformView.setOnCutMoveListener((index, positionMs) -> {
            if (index >= 0 && index < cuts.size()) {
                cuts.set(index, positionMs);
                Collections.sort(cuts);
                refreshCuts();
                setStatus("Cut #" + (index + 1) + " digeser ke " + formatTime(positionMs), false);
            }
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && durationMs > 0) {
                    long pos = (progress * durationMs) / 10000L;
                    waveformView.setPlayhead(pos);
                    timeText.setText(formatTime(pos) + " / " + formatTime(durationMs));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { userSeeking = true; }
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                userSeeking = false;
                seekTo((seekBar.getProgress() * durationMs) / 10000L);
            }
        });

        LinearLayout cutterCard = card();
        LinearLayout.LayoutParams cutterParams = cardParams();
        cutterParams.topMargin = dp(14);
        root.addView(cutterCard, cutterParams);

        LinearLayout headerRow = horizontal();
        cutterCard.addView(headerRow, matchWrap());
        TextView cutTitle = text("Titik Cut", 18, TEXT, true);
        cutCountText = text("0 / 28 cut", 14, ORANGE, true);
        cutCountText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        headerRow.addView(cutTitle, new LinearLayout.LayoutParams(0, dp(44), 1f));
        headerRow.addView(cutCountText, new LinearLayout.LayoutParams(0, dp(44), 1f));

        LinearLayout targetRow = horizontal();
        targetRow.setPadding(0, dp(6), 0, dp(10));
        cutterCard.addView(targetRow, matchWrap());

        targetInput = input("29");
        targetInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        prefixInput = input("commentator");
        targetRow.addView(labeledInput("Target part", targetInput), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams prefixParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f);
        prefixParams.leftMargin = dp(8);
        targetRow.addView(labeledInput("Nama file", prefixInput), prefixParams);

        addCutButton = primaryButton("+ TAMBAH CUT DI PLAYHEAD");
        addCutButton.setEnabled(false);
        cutterCard.addView(addCutButton, matchWrap());

        LinearLayout editRow = horizontal();
        editRow.setPadding(0, dp(8), 0, 0);
        cutterCard.addView(editRow, matchWrap());
        undoButton = secondaryButton("UNDO");
        resetButton = secondaryButton("RESET");
        editRow.addView(undoButton, weightedButton());
        editRow.addView(resetButton, weightedButton());

        addCutButton.setOnClickListener(v -> addCutAtPlayhead());
        undoButton.setOnClickListener(v -> undoCut());
        resetButton.setOnClickListener(v -> resetCuts());

        LinearLayout pitchCard = card();
        LinearLayout.LayoutParams pitchCardParams = cardParams();
        pitchCardParams.topMargin = dp(14);
        root.addView(pitchCard, pitchCardParams);

        LinearLayout pitchHeader = horizontal();
        pitchCard.addView(pitchHeader, matchWrap());

        TextView pitchTitle = text("Pitch", 18, TEXT, true);
        pitchValueText = text("0.0 st", 16, ORANGE, true);
        pitchValueText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        pitchHeader.addView(pitchTitle, new LinearLayout.LayoutParams(0, dp(42), 1f));
        pitchHeader.addView(pitchValueText, new LinearLayout.LayoutParams(0, dp(42), 1f));

        TextView pitchHint = text("Atur karakter suara tanpa mengubah kecepatan. Rentang -12 sampai +12 semitone.", 12, MUTED, false);
        pitchHint.setPadding(0, 0, 0, dp(6));
        pitchCard.addView(pitchHint);

        pitchBar = new SeekBar(this);
        pitchBar.setMax(48);
        pitchBar.setProgress(24);
        pitchBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        pitchBar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        pitchCard.addView(pitchBar, matchWrap());

        LinearLayout pitchActions = horizontal();
        pitchCard.addView(pitchActions, matchWrap());
        Button pitchDown = secondaryButton("-0.5");
        Button pitchReset = primaryButton("RESET PITCH");
        Button pitchUp = secondaryButton("+0.5");
        pitchActions.addView(pitchDown, weightedButton());
        pitchActions.addView(pitchReset, weightedButton());
        pitchActions.addView(pitchUp, weightedButton());

        pitchBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                pitchSemitones = (progress - 24) / 2f;
                updatePitchLabel();
                if (fromUser) applyPitchLiveIfPlaying();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        pitchDown.setOnClickListener(v -> {
            pitchBar.setProgress(Math.max(0, pitchBar.getProgress() - 1));
            applyPitchLiveIfPlaying();
        });
        pitchUp.setOnClickListener(v -> {
            pitchBar.setProgress(Math.min(48, pitchBar.getProgress() + 1));
            applyPitchLiveIfPlaying();
        });
        pitchReset.setOnClickListener(v -> {
            pitchBar.setProgress(24);
            pitchSemitones = 0f;
            updatePitchLabel();
            applyPitchLiveIfPlaying();
        });

        LinearLayout exportCard = card();
        LinearLayout.LayoutParams exportParams = cardParams();
        exportParams.topMargin = dp(14);
        root.addView(exportCard, exportParams);

        TextView exportTitle = text("Export Semua", 18, TEXT, true);
        exportCard.addView(exportTitle);

        TextView exportDesc = text("Pitch 0: export lossless seperti sumber. Pitch selain 0: export WAV 16-bit dengan pitch yang sudah diterapkan.", 13, MUTED, false);
        exportDesc.setPadding(0, dp(4), 0, dp(10));
        exportCard.addView(exportDesc);

        exportButton = primaryButton("EXPORT SEMUA PART");
        exportButton.setEnabled(false);
        exportCard.addView(exportButton, matchWrap());
        exportButton.setOnClickListener(v -> chooseExportFolder());

        statusText = text("Pilih file untuk mulai.", 13, MUTED, false);
        statusText.setPadding(0, dp(10), 0, 0);
        exportCard.addView(statusText);

        TextView partsTitle = text("Daftar Part", 18, TEXT, true);
        partsTitle.setPadding(0, dp(22), 0, dp(8));
        root.addView(partsTitle);

        segmentsContainer = new LinearLayout(this);
        segmentsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(segmentsContainer, matchWrap());

        setContentView(scroll);
        refreshCuts();
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "audio/*", "video/mp4", "video/*"
        });
        startActivityForResult(intent, REQ_PICK_FILE);
    }

    private void chooseExportFolder() {
        if (sourceUri == null || durationMs <= 0) {
            toast("Pilih file audio terlebih dahulu.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_EXPORT_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        if (requestCode == REQ_PICK_FILE) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri,
                        data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                );
            } catch (Exception ignored) {}
            loadSource(uri);
        } else if (requestCode == REQ_PICK_EXPORT_FOLDER) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );
            } catch (Exception ignored) {}
            exportAll(uri);
        }
    }

    private void loadSource(Uri uri) {
        sourceUri = uri;
        cuts.clear();
        durationMs = 0;
        waveformView.setWaveform(new float[0], 1);
        waveformView.setCuts(cuts);
        fileNameText.setText(uri.getLastPathSegment() == null ? "File terpilih" : uri.getLastPathSegment());
        setStatus("Menyiapkan player dan membaca waveform…", false);

        releasePlayer();
        player = new MediaPlayer();
        try {
            player.setDataSource(this, uri);
            player.setOnPreparedListener(mp -> {
                durationMs = Math.max(1, mp.getDuration());
                addCutButton.setEnabled(true);
                exportButton.setEnabled(true);
                seekBar.setProgress(0);
                timeText.setText("00:00.000 / " + formatTime(durationMs));
                refreshCuts();
                updatePitchLabel();
                setStatus("Audio siap. Putar, atur pitch bila perlu, lalu tambahkan titik cut.", false);
            });
            player.setOnCompletionListener(mp -> playButton.setText("PLAY"));
            player.prepareAsync();
        } catch (Exception e) {
            setStatus("Gagal membuka media: " + e.getMessage(), true);
            return;
        }

        executor.execute(() -> {
            try {
                WaveformLoader.Result result = WaveformLoader.load(this, uri, 1200);
                runOnUiThread(() -> {
                    if (sourceUri != null && sourceUri.equals(uri)) {
                        if (durationMs <= 0) durationMs = result.durationMs;
                        waveformView.setWaveform(result.amplitudes, Math.max(durationMs, result.durationMs));
                        setStatus("Waveform siap. Tap untuk seek, drag garis cut untuk koreksi.", false);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("Audio bisa diputar, waveform gagal dibuat: " + e.getMessage(), true));
            }
        });
    }

    private void togglePlay() {
        if (player == null) return;
        try {
            if (player.isPlaying()) {
                player.pause();
            } else {
                applyPitchForPlayback();
                player.start();
            }
        } catch (Exception e) {
            setStatus("Player error: " + e.getMessage(), true);
        }
    }

    private void nudge(int deltaMs) {
        if (player == null) return;
        try {
            long target = Math.max(0, Math.min(durationMs, player.getCurrentPosition() + deltaMs));
            seekTo(target);
        } catch (Exception ignored) {}
    }

    private void seekTo(long positionMs) {
        if (player == null || durationMs <= 0) return;
        long target = Math.max(0, Math.min(durationMs, positionMs));
        try {
            player.seekTo((int) target);
            waveformView.setPlayhead(target);
            seekBar.setProgress((int) ((target * 10000L) / Math.max(1, durationMs)));
            timeText.setText(formatTime(target) + " / " + formatTime(durationMs));
        } catch (Exception ignored) {}
    }

    private void addCutAtPlayhead() {
        if (player == null || durationMs <= 0) return;

        int targetParts = getTargetParts();
        if (cuts.size() >= targetParts - 1) {
            toast("Target " + targetParts + " part sudah tercapai.");
            return;
        }

        long position = player.getCurrentPosition();
        if (position < 80 || position > durationMs - 80) {
            toast("Titik cut terlalu dekat awal/akhir.");
            return;
        }
        for (Long existing : cuts) {
            if (Math.abs(existing - position) < 80) {
                toast("Sudah ada cut sangat dekat titik ini.");
                return;
            }
        }

        cuts.add(position);
        Collections.sort(cuts);
        refreshCuts();
        setStatus("Cut ditambahkan di " + formatTime(position) + ".", false);
    }

    private void undoCut() {
        if (cuts.isEmpty()) return;
        long removed = cuts.remove(cuts.size() - 1);
        refreshCuts();
        setStatus("Cut " + formatTime(removed) + " dibatalkan.", false);
    }

    private void resetCuts() {
        cuts.clear();
        refreshCuts();
        setStatus("Semua cut dihapus.", false);
    }

    private void refreshCuts() {
        waveformView.setCuts(cuts);
        int target = getTargetParts();
        cutCountText.setText(cuts.size() + " / " + Math.max(0, target - 1) + " cut");

        segmentsContainer.removeAllViews();
        if (durationMs <= 0) {
            TextView empty = text("Belum ada audio.", 13, MUTED, false);
            segmentsContainer.addView(empty);
            return;
        }

        long[] b = boundaries();
        for (int i = 0; i < b.length - 1; i++) {
            final int partIndex = i;
            LinearLayout row = horizontal();
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(panelDrawable(CARD, BORDER, dp(12)));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (i > 0) rowParams.topMargin = dp(6);
            segmentsContainer.addView(row, rowParams);

            TextView number = text(String.format(Locale.US, "%02d", i + 1), 16, ORANGE, true);
            number.setGravity(Gravity.CENTER);
            row.addView(number, new LinearLayout.LayoutParams(dp(48), dp(48)));

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER_VERTICAL);
            TextView range = text(formatTime(b[i]) + "  →  " + formatTime(b[i + 1]), 13, TEXT, true);
            TextView len = text(String.format(Locale.US, "%.2f detik", (b[i + 1] - b[i]) / 1000f), 12, MUTED, false);
            info.addView(range);
            info.addView(len);
            row.addView(info, new LinearLayout.LayoutParams(0, dp(48), 1f));

            Button preview = compactButton("PUTAR");
            row.addView(preview, new LinearLayout.LayoutParams(dp(78), dp(48)));
            preview.setOnClickListener(v -> {
                seekTo(b[partIndex]);
                try { applyPitchForPlayback(); player.start(); } catch (Exception ignored) {}
            });
        }

        undoButton.setEnabled(!cuts.isEmpty());
        resetButton.setEnabled(!cuts.isEmpty());

        int parts = cuts.size() + 1;
        if (parts == target) {
            cutCountText.setTextColor(Color.rgb(93, 226, 141));
        } else {
            cutCountText.setTextColor(ORANGE);
        }
    }

    private void exportAll(Uri treeUri) {
        if (sourceUri == null) return;
        long[] boundaries = boundaries();
        String prefix = sanitizePrefix(prefixInput.getText().toString());
        exportButton.setEnabled(false);
        setStatus("Menyiapkan export…", false);

        executor.execute(() -> {
            try {
                if (Math.abs(pitchSemitones) < 0.001f) {
                    AudioSegmentExporter.exportAll(
                            this,
                            sourceUri,
                            treeUri,
                            boundaries,
                            prefix,
                            (current, total, fileName) -> runOnUiThread(() ->
                                    setStatus("Export " + current + "/" + total + " • " + fileName, false)
                            )
                    );
                } else {
                    final float exportPitch = pitchSemitones;
                    PitchedWavExporter.exportAll(
                            this,
                            sourceUri,
                            treeUri,
                            boundaries,
                            prefix,
                            exportPitch,
                            (current, total, fileName) -> runOnUiThread(() ->
                                    setStatus(
                                            "Export " + current + "/" + total + " • " + fileName
                                                    + " • Pitch " + formatPitch(exportPitch),
                                            false
                                    )
                            )
                    );
                }
                runOnUiThread(() -> {
                    exportButton.setEnabled(true);
                    setStatus("Selesai! " + (boundaries.length - 1) + " file berhasil disimpan.", false);
                    toast("Export selesai");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    exportButton.setEnabled(true);
                    setStatus("Export gagal: " + e.getMessage(), true);
                });
            }
        });
    }

    private long[] boundaries() {
        long[] result = new long[cuts.size() + 2];
        result[0] = 0;
        for (int i = 0; i < cuts.size(); i++) result[i + 1] = cuts.get(i);
        result[result.length - 1] = durationMs;
        return result;
    }

    private int getTargetParts() {
        try {
            int value = Integer.parseInt(targetInput == null ? "29" : targetInput.getText().toString().trim());
            return Math.max(1, Math.min(200, value));
        } catch (Exception e) {
            return 29;
        }
    }

    private String sanitizePrefix(String value) {
        String s = value == null ? "" : value.trim().replaceAll("[^a-zA-Z0-9_-]+", "_");
        if (s.isEmpty()) s = "commentator";
        return s;
    }

    private void updatePitchLabel() {
        if (pitchValueText == null) return;
        pitchValueText.setText(formatPitch(pitchSemitones));
        pitchValueText.setTextColor(Math.abs(pitchSemitones) < 0.001f ? MUTED : ORANGE);
    }

    private String formatPitch(float semitones) {
        return String.format(Locale.US, "%+.1f st", semitones);
    }

    private float getPitchRatio() {
        return (float) Math.pow(2.0, pitchSemitones / 12.0);
    }

    private void applyPitchLiveIfPlaying() {
        if (player == null) return;
        try {
            if (player.isPlaying()) {
                applyPitchForPlayback();
            }
        } catch (Exception ignored) {}
    }

    private void applyPitchForPlayback() {
        if (player == null) return;
        PlaybackParams params = new PlaybackParams();
        params.setSpeed(1.0f);
        params.setPitch(getPitchRatio());
        params.setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT);
        player.setPlaybackParams(params);
    }

    private String formatTime(long ms) {
        ms = Math.max(0, ms);
        long minutes = ms / 60000;
        long seconds = (ms % 60000) / 1000;
        long millis = ms % 1000;
        return String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millis);
    }

    private void setStatus(String message, boolean error) {
        statusText.setText(message);
        statusText.setTextColor(error ? Color.rgb(255, 105, 105) : MUTED);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(14), dp(14), dp(14));
        layout.setBackground(panelDrawable(CARD, BORDER, dp(16)));
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout labeledInput(String label, EditText input) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = text(label, 12, MUTED, false);
        labelView.setPadding(0, 0, 0, dp(4));
        box.addView(labelView);
        box.addView(input, matchWrap());
        return box;
    }

    private EditText input(String value) {
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setTextColor(TEXT);
        edit.setTextSize(16);
        edit.setSingleLine(true);
        edit.setPadding(dp(12), 0, dp(12), 0);
        edit.setBackground(panelDrawable(CARD_2, BORDER, dp(10)));
        return edit;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button primaryButton(String label) {
        Button b = buttonBase(label);
        b.setTextColor(Color.WHITE);
        b.setBackground(panelDrawable(ACCENT, ACCENT, dp(12)));
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = buttonBase(label);
        b.setTextColor(TEXT);
        b.setBackground(panelDrawable(CARD_2, BORDER, dp(12)));
        return b;
    }

    private Button compactButton(String label) {
        Button b = buttonBase(label);
        b.setTextSize(11);
        b.setTextColor(TEXT);
        b.setBackground(panelDrawable(CARD_2, BORDER, dp(10)));
        return b;
    }

    private Button buttonBase(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        b.setMinHeight(dp(48));
        b.setPadding(dp(8), 0, dp(8), 0);
        return b;
    }

    private GradientDrawable panelDrawable(int fill, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        d.setStroke(dp(1), stroke);
        return d;
    }

    private LinearLayout.LayoutParams cardParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(50), 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(progressRunnable);
        releasePlayer();
        executor.shutdownNow();
        super.onDestroy();
    }
}
