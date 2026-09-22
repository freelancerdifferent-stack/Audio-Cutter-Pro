package com.differentfreelancer.audiocutterpro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    private final Paint zoomBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zoomBadgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] waveform = new float[0];
    private final List<Long> cuts = new ArrayList<>();

    private long durationMs = 1;
    private long playheadMs = 0;

    private float zoom = 1f;
    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 40f;
    private long viewportStartMs = 0;

    private int draggingCut = -1;
    private boolean panning = false;
    private boolean hadMultiTouch = false;
    private float downX;
    private float downY;
    private float lastX;
    private final int touchSlop;

    private OnSeekListener seekListener;
    private OnCutMoveListener cutMoveListener;
    private final ScaleGestureDetector scaleDetector;

    public WaveformView(Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        scaleDetector = buildScaleDetector(context);
        init();
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        scaleDetector = buildScaleDetector(context);
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

        zoomBadgePaint.setColor(Color.argb(180, 9, 11, 16));
        zoomBadgeTextPaint.setColor(Color.WHITE);
        zoomBadgeTextPaint.setTextSize(dp(11));
        zoomBadgeTextPaint.setFakeBoldText(true);
    }

    private ScaleGestureDetector buildScaleDetector(Context context) {
        return new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                hadMultiTouch = true;
                panning = false;
                draggingCut = -1;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                if (getWidth() <= 0 || durationMs <= 0) return false;

                long oldVisible = getVisibleDurationMs();
                float focusRatio = clamp(detector.getFocusX() / getWidth(), 0f, 1f);
                long focusTime = viewportStartMs + (long) (focusRatio * oldVisible);

                float newZoom = clamp(zoom * detector.getScaleFactor(), MIN_ZOOM, MAX_ZOOM);
                if (Math.abs(newZoom - zoom) < 0.0001f) return true;

                zoom = newZoom;
                long newVisible = getVisibleDurationMs();
                viewportStartMs = focusTime - (long) (focusRatio * newVisible);
                clampViewport();
                invalidate();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                clampViewport();
                invalidate();
            }
        });
    }

    public void setWaveform(float[] values, long durationMs) {
        waveform = values == null ? new float[0] : values;
        this.durationMs = Math.max(1, durationMs);
        resetZoom();
    }

    public void setCuts(List<Long> values) {
        cuts.clear();
        if (values != null) cuts.addAll(values);
        invalidate();
    }

    public void setPlayhead(long ms) {
        playheadMs = Math.max(0, Math.min(durationMs, ms));
        invalidate();
    }

    public void resetZoom() {
        zoom = 1f;
        viewportStartMs = 0;
        invalidate();
    }

    public float getZoom() {
        return zoom;
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

        long visibleDuration = getVisibleDurationMs();
        long viewportEndMs = Math.min(durationMs, viewportStartMs + visibleDuration);

        if (waveform.length > 0 && durationMs > 0) {
            int first = (int) Math.floor((viewportStartMs / (double) durationMs) * waveform.length);
            int last = (int) Math.ceil((viewportEndMs / (double) durationMs) * waveform.length);
            first = Math.max(0, Math.min(waveform.length - 1, first));
            last = Math.max(first + 1, Math.min(waveform.length, last));

            int visibleSamples = Math.max(1, last - first);
            float step = w / (float) visibleSamples;

            for (int i = first; i < last; i++) {
                float amp = Math.max(0.02f, Math.min(1f, waveform[i]));
                float x = (i - first) * step;
                float half = amp * (h * 0.43f);
                canvas.drawLine(x, cy - half, x, cy + half, wavePaint);
            }
        }

        for (int i = 0; i < cuts.size(); i++) {
            long cutTime = cuts.get(i);
            if (cutTime < viewportStartMs || cutTime > viewportEndMs) continue;

            float x = timeToX(cutTime);
            canvas.drawLine(x, 0, x, h, cutPaint);
            canvas.drawText(String.valueOf(i + 1), Math.min(w - dp(20), x + dp(4)), dp(14), textPaint);
        }

        if (playheadMs >= viewportStartMs && playheadMs <= viewportEndMs) {
            float px = timeToX(playheadMs);
            canvas.drawLine(px, 0, px, h, playPaint);
        }

        if (zoom > 1.01f) {
            drawZoomBadge(canvas, w);
        }
    }

    private void drawZoomBadge(Canvas canvas, int width) {
        String label = String.format(Locale.US, "%.1f×", zoom);
        float padX = dp(8);
        float badgeH = dp(25);
        float textW = zoomBadgeTextPaint.measureText(label);
        float badgeW = textW + padX * 2;
        float left = width - badgeW - dp(8);
        float top = dp(8);

        canvas.drawRoundRect(
                left,
                top,
                left + badgeW,
                top + badgeH,
                dp(8),
                dp(8),
                zoomBadgePaint
        );
        canvas.drawText(
                label,
                left + padX,
                top + badgeH - dp(7),
                zoomBadgeTextPaint
        );
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (durationMs <= 0) return false;

        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                hadMultiTouch = false;
                panning = false;
                downX = event.getX();
                downY = event.getY();
                lastX = downX;
                draggingCut = findNearbyCut(downX);
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                hadMultiTouch = true;
                draggingCut = -1;
                panning = false;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (scaleDetector.isInProgress() || event.getPointerCount() > 1) {
                    hadMultiTouch = true;
                    return true;
                }

                float x = clamp(event.getX(), 0f, getWidth());
                float dxFromDown = x - downX;
                float dyFromDown = event.getY() - downY;

                if (draggingCut >= 0) {
                    long time = xToTime(x);
                    long min = draggingCut == 0 ? 50 : cuts.get(draggingCut - 1) + 50;
                    long max = draggingCut == cuts.size() - 1 ? durationMs - 50 : cuts.get(draggingCut + 1) - 50;
                    long clamped = Math.max(min, Math.min(max, time));
                    cuts.set(draggingCut, clamped);
                    invalidate();
                    return true;
                }

                if (zoom > 1.01f &&
                        (panning || (Math.abs(dxFromDown) > touchSlop && Math.abs(dxFromDown) > Math.abs(dyFromDown)))) {
                    panning = true;
                    long visibleDuration = getVisibleDurationMs();
                    float dx = x - lastX;
                    long deltaMs = (long) (-(dx / Math.max(1f, getWidth())) * visibleDuration);
                    viewportStartMs += deltaMs;
                    clampViewport();
                    lastX = x;
                    invalidate();
                    return true;
                }

                lastX = x;
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                hadMultiTouch = true;
                return true;

            case MotionEvent.ACTION_UP:
                if (draggingCut >= 0) {
                    if (cutMoveListener != null) {
                        cutMoveListener.onCutMoved(draggingCut, cuts.get(draggingCut));
                    }
                } else if (!panning && !hadMultiTouch && !scaleDetector.isInProgress()) {
                    float totalDx = Math.abs(event.getX() - downX);
                    float totalDy = Math.abs(event.getY() - downY);
                    if (totalDx <= touchSlop && totalDy <= touchSlop) {
                        long time = xToTime(clamp(event.getX(), 0f, getWidth()));
                        playheadMs = time;
                        if (seekListener != null) seekListener.onSeek(time);
                    }
                }

                draggingCut = -1;
                panning = false;
                hadMultiTouch = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                draggingCut = -1;
                panning = false;
                hadMultiTouch = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                invalidate();
                return true;
        }

        return true;
    }

    private int findNearbyCut(float x) {
        float threshold = dp(24);
        int best = -1;
        float bestDistance = Float.MAX_VALUE;

        long visibleEnd = viewportStartMs + getVisibleDurationMs();

        for (int i = 0; i < cuts.size(); i++) {
            long cut = cuts.get(i);
            if (cut < viewportStartMs || cut > visibleEnd) continue;

            float distance = Math.abs(timeToX(cut) - x);
            if (distance < threshold && distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private long getVisibleDurationMs() {
        return Math.max(1L, (long) (durationMs / zoom));
    }

    private float timeToX(long ms) {
        long visible = getVisibleDurationMs();
        return ((ms - viewportStartMs) / (float) visible) * getWidth();
    }

    private long xToTime(float x) {
        if (getWidth() <= 0) return viewportStartMs;
        long visible = getVisibleDurationMs();
        long t = viewportStartMs + (long) ((x / getWidth()) * visible);
        return Math.max(0, Math.min(durationMs, t));
    }

    private void clampViewport() {
        long visible = getVisibleDurationMs();
        long maxStart = Math.max(0, durationMs - visible);
        viewportStartMs = Math.max(0, Math.min(maxStart, viewportStartMs));

        if (zoom <= MIN_ZOOM + 0.001f) {
            zoom = MIN_ZOOM;
            viewportStartMs = 0;
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
