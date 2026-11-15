package com.proggerx.dcross2000.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import java.util.Arrays;

import com.proggerx.dcross2000.mix.Curve;
import com.proggerx.dcross2000.mix.TransitionSpec;

/**
 * Zoomed view of one transition. Both waveforms and all four envelopes live on
 * the same surface:
 *
 * <ul>
 *     <li>The outgoing waveform and curves span the lead plus overlap.</li>
 *     <li>The incoming waveform and curves span the overlap plus tail.</li>
 *     <li>Volume curves use the 0..1 normalized range, where the middle dashed
 *     line is unchanged volume and anything above it is a boost.</li>
 *     <li>Tempo curves use the same normalized storage, with the 1x line in
 *     the middle.</li>
 * </ul>
 *
 * The pencil always writes the curve selected by the current side and parameter.
 */
public final class TransitionCurveView extends View {
    public enum Side { OUTGOING, INCOMING }
    public enum Parameter { VOLUME, TEMPO }

    private static final int GRID = 0x22000000;
    private static final int SECTION_SHADE = 0x0D000000;
    private static final int MARKER_IN = 0xFFFFC107;
    private static final int MARKER_OUT = 0x66888888;
    private static final int OUTGOING = 0xFFFFA000;
    private static final int INCOMING = 0xFF5B8DEF;
    private static final int OUTGOING_WAVE = 0x33FFA000;
    private static final int INCOMING_WAVE = 0x335B8DEF;
    private static final int CENTER = 0x22000000;
    private static final int UNITY = 0x33000000;
    private static final int PLAYHEAD = 0xFFFF1744;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerInPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerOutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outgoingWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint incomingWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unityPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final float density;

    private float[] outgoingPeaks;
    private float[] incomingPeaks;
    private final float[] outVolume = new float[Curve.POINTS];
    private final float[] inVolume = new float[Curve.POINTS];
    private final float[] outTempo = new float[Curve.POINTS];
    private final float[] inTempo = new float[Curve.POINTS];

    private float outStart;
    private float outEnd = 1f;
    private float inStart;
    private float inEnd = 1f;
    private Side side = Side.OUTGOING;
    private Parameter parameter = Parameter.VOLUME;
    private int lastIndex = -1;
    private boolean drawing;
    private float playhead = -1f;
    private Runnable onChanged;

    public TransitionCurveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        Arrays.fill(outVolume, TransitionSpec.NORMAL_GAIN);
        Arrays.fill(inVolume, TransitionSpec.NORMAL_GAIN);
        Arrays.fill(outTempo, TransitionSpec.NORMAL_TEMPO);
        Arrays.fill(inTempo, TransitionSpec.NORMAL_TEMPO);

        gridPaint.setColor(GRID);
        centerPaint.setColor(CENTER);
        centerPaint.setStrokeWidth(Math.max(1f, density));
        shadePaint.setColor(SECTION_SHADE);
        markerInPaint.setColor(MARKER_IN);
        markerInPaint.setStrokeWidth(2f * density);
        markerOutPaint.setColor(MARKER_OUT);
        markerOutPaint.setStrokeWidth(2f * density);
        outgoingWavePaint.setStyle(Paint.Style.STROKE);
        outgoingWavePaint.setColor(OUTGOING_WAVE);
        outgoingWavePaint.setStrokeWidth(Math.max(1f, density));
        incomingWavePaint.setStyle(Paint.Style.STROKE);
        incomingWavePaint.setColor(INCOMING_WAVE);
        incomingWavePaint.setStrokeWidth(Math.max(1f, density));
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        unityPaint.setColor(UNITY);
        unityPaint.setStrokeWidth(Math.max(1f, density));
        unityPaint.setPathEffect(new DashPathEffect(new float[]{8f * density, 6f * density}, 0f));
        playheadPaint.setColor(PLAYHEAD);
        playheadPaint.setStrokeWidth(2.5f * density);
        playheadPaint.setStyle(Paint.Style.STROKE);
    }

    public void setOnChanged(Runnable onChanged) {
        this.onChanged = onChanged;
    }

    /**
     * Sets where each side is automated inside the window.
     *
     * @param outStart fraction where outgoing automation begins (normally 0)
     * @param outEnd   fraction where the outgoing song stops (lead + overlap)
     * @param inStart  fraction where the incoming song enters (the lead)
     * @param inEnd    fraction where incoming automation ends (normally 1)
     */
    public void setSpans(float outStart, float outEnd, float inStart, float inEnd) {
        this.outStart = clamp01(outStart);
        this.outEnd = Math.max(this.outStart, clamp01(outEnd));
        this.inStart = clamp01(inStart);
        this.inEnd = Math.max(this.inStart, clamp01(inEnd));
        invalidate();
    }

    public void setWaveforms(float[] outgoing, float[] incoming) {
        outgoingPeaks = outgoing;
        incomingPeaks = incoming;
        invalidate();
    }

    public void setCurves(float[] outVolume, float[] inVolume, float[] outTempo, float[] inTempo) {
        copyInto(this.outVolume, outVolume);
        copyInto(this.inVolume, inVolume);
        copyInto(this.outTempo, outTempo);
        copyInto(this.inTempo, inTempo);
        invalidate();
    }

    /** Live preview position; negative hides the line. */
    public void setPlayhead(float fraction) {
        playhead = fraction < 0f ? -1f : clamp01(fraction);
        invalidate();
    }

    public void setMode(Side side, Parameter parameter) {
        this.side = side;
        this.parameter = parameter;
        invalidate();
    }

    public float[] getOutVolume() { return Arrays.copyOf(outVolume, Curve.POINTS); }
    public float[] getInVolume() { return Arrays.copyOf(inVolume, Curve.POINTS); }
    public float[] getOutTempo() { return Arrays.copyOf(outTempo, Curve.POINTS); }
    public float[] getInTempo() { return Arrays.copyOf(inTempo, Curve.POINTS); }

    private static void copyInto(float[] target, float[] source) {
        if(source == null || source.length != Curve.POINTS) return;
        System.arraycopy(source, 0, target, 0, Curve.POINTS);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if(width <= 0 || height <= 0) return;

        rect.set(0, 0, width, height);
        canvas.drawRoundRect(rect, height / 20f, height / 20f, gridPaint);

        for(int i = 1; i <= 3; i++) {
            float y = height * i / 4f;
            canvas.drawLine(0, y, width, y, gridPaint);
        }
        float center = height / 2f;
        canvas.drawLine(0, center, width, center, centerPaint);

        // Lead and tail are single-song regions.
        if(inStart > 0.001f) {
            canvas.drawRect(0, 0, inStart * width, height, shadePaint);
        }
        if(outEnd < 0.999f) {
            canvas.drawRect(outEnd * width, 0, width, height, shadePaint);
        }

        drawWaveform(canvas, outgoingPeaks, outStart, outEnd, true, outgoingWavePaint);
        drawWaveform(canvas, incomingPeaks, inStart, inEnd, false, incomingWavePaint);

        boolean tempo = parameter == Parameter.TEMPO;

        // Reference curves stay visible but recede.
        drawCurve(canvas, outVolume, outStart, outEnd, false, alpha(OUTGOING, tempo ? 90 : 140), 1.5f);
        drawCurve(canvas, inVolume, inStart, inEnd, false, alpha(INCOMING, tempo ? 90 : 140), 1.5f);
        drawCurve(canvas, outTempo, outStart, outEnd, true, alpha(OUTGOING, tempo ? 140 : 60), 1.5f);
        drawCurve(canvas, inTempo, inStart, inEnd, true, alpha(INCOMING, tempo ? 140 : 60), 1.5f);

        // Pencil target on top.
        int color = side == Side.OUTGOING ? OUTGOING : INCOMING;
        if(side == Side.OUTGOING) {
            drawCurve(canvas, parameter == Parameter.VOLUME ? outVolume : outTempo,
                    outStart, outEnd, tempo, color, 3f);
        } else {
            drawCurve(canvas, parameter == Parameter.VOLUME ? inVolume : inTempo,
                    inStart, inEnd, tempo, color, 3f);
        }

        // Middle line = unchanged volume, or 1x tempo.
        float unityY = (1f - TransitionSpec.NORMAL_GAIN) * height;
        canvas.drawLine(0, unityY, width, unityY, unityPaint);

        canvas.drawLine(inStart * width, 0, inStart * width, height, markerInPaint);
        if(outEnd < 0.999f) {
            canvas.drawLine(outEnd * width, 0, outEnd * width, height, markerOutPaint);
        }

        if(playhead >= 0f) {
            float x = playhead * width;
            canvas.drawLine(x, 0, x, height, playheadPaint);
            path.reset();
            float size = 7f * density;
            path.moveTo(x - size, 0);
            path.lineTo(x + size, 0);
            path.lineTo(x, size * 1.4f);
            path.close();
            playheadPaint.setStyle(Paint.Style.FILL);
            canvas.drawPath(path, playheadPaint);
            playheadPaint.setStyle(Paint.Style.STROKE);
        }
    }

    private void drawWaveform(Canvas canvas, float[] peaks, float from, float to, boolean up, Paint paint) {
        if(peaks == null || peaks.length == 0) return;
        int width = getWidth();
        int height = getHeight();
        int start = Math.round(from * width);
        int end = Math.round(to * width);
        int span = Math.max(1, end - start);
        float center = height / 2f;
        float amplitude = height * 0.42f;
        int step = Math.max(1, span / peaks.length);
        for(int x = start; x < end; x += step) {
            int index = (int) ((long) (x - start) * peaks.length / span);
            float peak = peaks[Math.min(peaks.length - 1, index)];
            float half = Math.max(0.5f, peak * amplitude);
            if(up) {
                canvas.drawLine(x, center, x, center - half, paint);
            } else {
                canvas.drawLine(x, center, x, center + half, paint);
            }
        }
    }

    private void drawCurve(Canvas canvas, float[] values, float from, float to, boolean tempo,
                           int color, float widthDp) {
        int width = getWidth();
        int height = getHeight();
        if(values == null || width <= 0) return;
        float start = from * width;
        float span = Math.max(1f, (to - from) * width);

        path.reset();
        for(int i = 0; i < Curve.POINTS; i++) {
            float x = start + span * i / (float) (Curve.POINTS - 1);
            float y = (1f - values[i]) * height;
            if(i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        curvePaint.setColor(color);
        curvePaint.setStrokeWidth(widthDp * density);
        curvePaint.setPathEffect(tempo ? new DashPathEffect(new float[]{8f * density, 6f * density}, 0f) : null);
        canvas.drawPath(path, curvePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if(!isEnabled()) return false;
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                ViewParent parent = getParent();
                if(parent != null) parent.requestDisallowInterceptTouchEvent(true);
                drawing = true;
                lastIndex = -1;
                write(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if(drawing) write(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                drawing = false;
                lastIndex = -1;
                ViewParent container = getParent();
                if(container != null) container.requestDisallowInterceptTouchEvent(false);
                if(event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void write(float touchX, float touchY) {
        int width = getWidth();
        int height = getHeight();
        if(width <= 0 || height <= 0) return;

        float fraction = clamp01(touchX / width);
        float from = side == Side.OUTGOING ? outStart : inStart;
        float to = side == Side.OUTGOING ? outEnd : inEnd;
        if(fraction < from - 0.0001f || fraction > to + 0.0001f) {
            lastIndex = -1;
            return;
        }
        float span = Math.max(0.0001f, to - from);
        int index = Math.round(clamp01((fraction - from) / span) * (Curve.POINTS - 1));
        float value = clamp01(1f - touchY / height);

        float[] values = target();
        if(lastIndex < 0 || lastIndex == index) {
            values[index] = value;
        } else {
            int start = Math.min(lastIndex, index);
            int end = Math.max(lastIndex, index);
            float fromValue = values[lastIndex];
            for(int i = start; i <= end; i++) {
                float t = (i - start) / (float) Math.max(1, end - start);
                values[i] = fromValue + (value - fromValue) * t;
            }
            values[index] = value;
        }
        lastIndex = index;
        invalidate();
        if(onChanged != null) onChanged.run();
    }

    private float[] target() {
        if(side == Side.OUTGOING) {
            return parameter == Parameter.VOLUME ? outVolume : outTempo;
        }
        return parameter == Parameter.VOLUME ? inVolume : inTempo;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int alpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
