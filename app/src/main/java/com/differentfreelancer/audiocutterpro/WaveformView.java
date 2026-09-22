package com.differentfreelancer.audiocutterpro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class WaveformView extends View {
    public interface OnSeekListener {
        void onSeek(long positionMs);
    }

    public interface OnCutMoveListener {
        void onCutMoved(int index, long positionMs);
    }

    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] waveform = new float[0];
    private final List<Long> cuts = new ArrayList<>();
    private long durationMs = 1;
    private long playheadMs = 0;
    private int draggingCut = -1;
    private OnSeekListener seekListener;
    private OnCutMoveListener cutMoveListener;

    public WaveformView(Context context) {
        super(context);
        init();
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setBackgroundColor(Color.rgb(17, 21, 29));
        wavePaint.setColor(Color.rgb(140, 145, 160));
        wavePaint.setStrokeWidth(dp(1));
        centerPaint.setColor(Color.rgb(55, 62, 75));
        centerPaint.setStrokeWidth(dp(1));
        cutPaint.setColor(Color.rgb(255, 178, 61));
        cutPaint.setStrokeWidth(dp(2));
        playPaint.setColor(Color.WHITE);
        playPaint.setStrokeWidth(dp(1.5f));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(11));
    }

    public void setWaveform(float[] values, long durationMs) {
        waveform = values == null ? new float[0] : values;
        this.durationMs = Math.max(1, durationMs);
        invalidate();
    }

    public void setCuts(List<Long> values) {
        cuts.clear();
        if (values != null) {
            cuts.addAll(values);
        }
        invalidate();
    }

    public void setPlayhead(long ms) {
        playheadMs = Math.max(0, Math.min(durationMs, ms));
        invalidate();
    }

    public void setOnSeekListener(OnSeekListener listener) {
        seekListener = listener;
    }

    public void setOnCutMoveListener(OnCutMoveListener listener) {
        cutMoveListener = listener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cy = h / 2f;
        canvas.drawLine(0, cy, w, cy, centerPaint);

        if (waveform.length > 0) {
            float step = w / (float) waveform.length;
            for (int i = 0; i < waveform.length; i++) {
                float amp = Math.max(0.02f, Math.min(1f, waveform[i]));
                float x = i * step;
                float half = amp * (h * 0.43f);
                canvas.drawLine(x, cy - half, x, cy + half, wavePaint);
            }
        }

        for (int i = 0; i < cuts.size(); i++) {
            float x = timeToX(cuts.get(i));
            canvas.drawLine(x, 0, x, h, cutPaint);
            canvas.drawText(String.valueOf(i + 1), Math.min(w - dp(20), x + dp(4)), dp(14), textPaint);
        }

        float px = timeToX(playheadMs);
        canvas.drawLine(px, 0, px, h, playPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (durationMs <= 0) return false;

        float x = Math.max(0, Math.min(getWidth(), event.getX()));
        long time = xToTime(x);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                draggingCut = findNearbyCut(x);
                if (draggingCut >= 0) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                } else {
                    playheadMs = time;
                    if (seekListener != null) seekListener.onSeek(time);
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (draggingCut >= 0) {
                    long min = draggingCut == 0 ? 50 : cuts.get(draggingCut - 1) + 50;
                    long max = draggingCut == cuts.size() - 1 ? durationMs - 50 : cuts.get(draggingCut + 1) - 50;
                    long clamped = Math.max(min, Math.min(max, time));
                    cuts.set(draggingCut, clamped);
                    invalidate();
                } else {
                    playheadMs = time;
                    if (seekListener != null) seekListener.onSeek(time);
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (draggingCut >= 0 && cutMoveListener != null) {
                    cutMoveListener.onCutMoved(draggingCut, cuts.get(draggingCut));
                }
                draggingCut = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
        }
        return super.onTouchEvent(event);
    }

    private int findNearbyCut(float x) {
        float threshold = dp(24);
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < cuts.size(); i++) {
            float distance = Math.abs(timeToX(cuts.get(i)) - x);
            if (distance < threshold && distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private float timeToX(long ms) {
        return (ms / (float) durationMs) * getWidth();
    }

    private long xToTime(float x) {
        if (getWidth() <= 0) return 0;
        return (long) ((x / getWidth()) * durationMs);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
