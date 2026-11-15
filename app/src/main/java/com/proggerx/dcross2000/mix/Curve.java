package com.proggerx.dcross2000.mix;

import java.io.Serializable;
import java.util.Arrays;

/**
 * A normalized gain envelope sampled at {@link #POINTS} evenly spaced positions.
 * Values are between 0 (silent) and 1 (full volume). The playback engine and the
 * editor share this representation, so a hand drawn line is played back as drawn.
 */
public final class Curve implements Serializable {
    public static final int POINTS = 64;

    private final float[] values;

    public Curve(float[] values) {
        if(values.length != POINTS) {
            throw new IllegalArgumentException("Expected " + POINTS + " points, got " + values.length);
        }
        this.values = new float[POINTS];
        for(int i = 0; i < POINTS; i++) {
            this.values[i] = clamp(values[i]);
        }
        // A transition always starts and ends where the user put the handles.
        this.values[0] = clamp(values[0]);
        this.values[POINTS - 1] = clamp(values[POINTS - 1]);
    }

    public static Curve of(float[] values) {
        return new Curve(values);
    }

    public static Curve samples(float[] values) {
        float[] copy = Arrays.copyOf(values, POINTS);
        return new Curve(copy);
    }

    public static Curve constant(float value) {
        float[] values = new float[POINTS];
        Arrays.fill(values, clamp(value));
        return new Curve(values);
    }

    public static Curve linearOut() {
        return ramp(1f, 0f);
    }

    public static Curve linearIn() {
        return ramp(0f, 1f);
    }

    public static Curve equalPowerOut() {
        return curve(t -> (float) Math.cos(t * Math.PI / 2));
    }

    public static Curve equalPowerIn() {
        return curve(t -> (float) Math.sin(t * Math.PI / 2));
    }

    public static Curve defaultOut() {
        return equalPowerOut();
    }

    public static Curve defaultIn() {
        return equalPowerIn();
    }

    private static Curve ramp(final float from, final float to) {
        return curve(t -> from + (to - from) * t);
    }

    private interface Shape {
        float valueAt(float t);
    }

    private static Curve curve(Shape shape) {
        float[] values = new float[POINTS];
        for(int i = 0; i < POINTS; i++) {
            values[i] = shape.valueAt(i / (float) (POINTS - 1));
        }
        return new Curve(values);
    }

    /** Linearly interpolates the stored samples. */
    public float valueAt(float t) {
        if(t <= 0f) return values[0];
        if(t >= 1f) return values[POINTS - 1];
        float scaled = t * (POINTS - 1);
        int lower = (int) scaled;
        int upper = Math.min(POINTS - 1, lower + 1);
        float fraction = scaled - lower;
        return values[lower] + (values[upper] - values[lower]) * fraction;
    }

    public float[] copyValues() {
        return Arrays.copyOf(values, POINTS);
    }

    /** Light smoothing for freehand strokes; endpoints never move. */
    public Curve smoothed() {
        float[] result = new float[POINTS];
        result[0] = values[0];
        result[POINTS - 1] = values[POINTS - 1];
        for(int i = 1; i < POINTS - 1; i++) {
            result[i] = (values[i - 1] + values[i] * 2f + values[i + 1]) / 4f;
        }
        return new Curve(result);
    }

    public boolean isFlat() {
        for(int i = 1; i < POINTS; i++) {
            if(Math.abs(values[i] - values[0]) > 0.001f) return false;
        }
        return true;
    }

    public float startValue() {
        return values[0];
    }

    public float endValue() {
        return values[POINTS - 1];
    }

    /** Compact, dependency free encoding: one hex byte per sample. */
    public String encode() {
        StringBuilder builder = new StringBuilder(POINTS * 2);
        for(float value : values) {
            int quantized = Math.round(clamp(value) * 255f);
            builder.append(Character.forDigit((quantized >> 4) & 0xF, 16));
            builder.append(Character.forDigit(quantized & 0xF, 16));
        }
        return builder.toString();
    }

    public static Curve decode(String encoded) {
        if(encoded == null || encoded.length() != POINTS * 2) {
            return null;
        }
        float[] values = new float[POINTS];
        try {
            for(int i = 0; i < POINTS; i++) {
                int high = Character.digit(encoded.charAt(i * 2), 16);
                int low = Character.digit(encoded.charAt(i * 2 + 1), 16);
                if(high < 0 || low < 0) return null;
                values[i] = ((high << 4) | low) / 255f;
            }
        } catch(IndexOutOfBoundsException e) {
            return null;
        }
        return new Curve(values);
    }

    private static float clamp(float value) {
        if(Float.isNaN(value)) return 0f;
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Curve && Arrays.equals(values, ((Curve) other).values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "Curve[" + encode() + "]";
    }
}
