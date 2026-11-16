package com.proggerx.dcross2000.mix;

import java.io.Serializable;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A user drawn transition between two specific songs.
 *
 * <pre>
 *   |&lt;--- lead ---&gt;|&lt;-- overlap --&gt;|&lt;--- tail ---&gt;|
 *   A automation   B enters       A stops        B alone ends
 * </pre>
 *
 * <p>During the lead only the outgoing song is automated, during the overlap
 * both sound, and during the tail only the incoming song is automated.</p>
 *
 * <p>Volume curves are normalized 0..1 and map to {@link #MIN_GAIN}..
 * {@link #MAX_GAIN}, so 0.5 means unchanged volume and anything above that is a
 * boost used to even out quiet tracks. Tempo curves are normalized the same way
 * and map to {@link #MIN_TEMPO}..{@link #MAX_TEMPO}.</p>
 */
public final class TransitionSpec implements Serializable {
    public static final int MIN_FADE_MS = 300;
    public static final int MAX_FADE_MS = 60000;
    public static final int MAX_LEAD_MS = 60000;
    public static final int MAX_TAIL_MS = 60000;
    public static final int MAX_B_START_MS = 900000;
    public static final int DEFAULT_OVERLAP_MS = 6000;

    public static final float MIN_TEMPO = 0.5f;
    public static final float MAX_TEMPO = 1.5f;
    /** Normalized value that maps to 1x playback. */
    public static final float NORMAL_TEMPO = 0.5f;

    public static final float MIN_GAIN = 0f;
    public static final float MAX_GAIN = 2f;
    /** Normalized value that maps to unchanged volume. */
    public static final float NORMAL_GAIN = 0.5f;

    private static final int VERSION = 3;

    public final String fromId;
    public final String toId;
    public final int leadMs;
    public final int fadeMs;
    public final int tailMs;
    public final int bStartMs;
    public final Curve out;
    public final Curve in;
    public final Curve outTempo;
    public final Curve inTempo;

    public TransitionSpec(String fromId, String toId, int leadMs, int fadeMs, int tailMs, int bStartMs,
                          Curve out, Curve in, Curve outTempo, Curve inTempo) {
        this.fromId = fromId;
        this.toId = toId;
        this.fadeMs = clamp(fadeMs, MIN_FADE_MS, MAX_FADE_MS);
        this.leadMs = clamp(leadMs, 0, MAX_LEAD_MS);
        this.tailMs = clamp(tailMs, 0, MAX_TAIL_MS);
        this.bStartMs = clamp(bStartMs, 0, MAX_B_START_MS);
        this.out = out == null ? defaultOutVolume() : out;
        this.in = in == null ? defaultInVolume() : in;
        this.outTempo = outTempo == null ? defaultTempoCurve() : outTempo;
        this.inTempo = inTempo == null ? defaultTempoCurve() : inTempo;
    }

    /** Compatibility constructor: no tail. */
    public TransitionSpec(String fromId, String toId, int leadMs, int fadeMs, int bStartMs,
                          Curve out, Curve in, Curve outTempo, Curve inTempo) {
        this(fromId, toId, leadMs, fadeMs, 0, bStartMs, out, in, outTempo, inTempo);
    }

    /** Compatibility constructor: no tail, normal tempo. */
    public TransitionSpec(String fromId, String toId, int leadMs, int fadeMs, int bStartMs,
                          Curve out, Curve in) {
        this(fromId, toId, leadMs, fadeMs, 0, bStartMs, out, in, null, null);
    }

    public static TransitionSpec defaults(String fromId, String toId) {
        return new TransitionSpec(fromId, toId, 0, DEFAULT_OVERLAP_MS, 0, 0,
                defaultOutVolume(), defaultInVolume(), null, null);
    }

    /** Total automation window at the end of the outgoing song. */
    public int windowMs() {
        return leadMs + fadeMs + tailMs;
    }

    /** How long the outgoing song keeps playing after its automation begins. */
    public int outgoingWindowMs() {
        return leadMs + fadeMs;
    }

    /** How long the incoming song is automated, from its entry to normal playback. */
    public int incomingWindowMs() {
        return fadeMs + tailMs;
    }

    public TransitionSpec withTiming(int leadMs, int fadeMs, int tailMs, int bStartMs) {
        return new TransitionSpec(fromId, toId, leadMs, fadeMs, tailMs, bStartMs,
                out, in, outTempo, inTempo);
    }

    public TransitionSpec withCurves(Curve out, Curve in) {
        return new TransitionSpec(fromId, toId, leadMs, fadeMs, tailMs, bStartMs,
                out, in, outTempo, inTempo);
    }

    public TransitionSpec withTempos(Curve outTempo, Curve inTempo) {
        return new TransitionSpec(fromId, toId, leadMs, fadeMs, tailMs, bStartMs,
                out, in, outTempo, inTempo);
    }

    /** Converts a normalized volume curve sample into a gain multiplier. */
    public static float gainAt(Curve curve, float t) {
        if(curve == null) return 1f;
        return MIN_GAIN + curve.valueAt(t) * (MAX_GAIN - MIN_GAIN);
    }

    public static float gainToNormalized(float gain) {
        return clamp((gain - MIN_GAIN) / (MAX_GAIN - MIN_GAIN), 0f, 1f);
    }

    /** Converts a normalized tempo curve sample into a playback rate. */
    public static float tempoAt(Curve curve, float t) {
        if(curve == null) return 1f;
        return MIN_TEMPO + curve.valueAt(t) * (MAX_TEMPO - MIN_TEMPO);
    }

    public static float tempoToNormalized(float tempo) {
        return clamp((tempo - MIN_TEMPO) / (MAX_TEMPO - MIN_TEMPO), 0f, 1f);
    }

    public static Curve defaultTempoCurve() {
        return Curve.constant(NORMAL_TEMPO);
    }

    /** Equal power fade scaled so it starts at unchanged volume. */
    public static Curve defaultOutVolume() {
        return scale(Curve.equalPowerOut(), NORMAL_GAIN);
    }

    /** Equal power fade scaled so it ends at unchanged volume. */
    public static Curve defaultInVolume() {
        return scale(Curve.equalPowerIn(), NORMAL_GAIN);
    }

    private static boolean matches(Curve curve, Curve expected) {
        for(int i = 0; i < Curve.POINTS; i++) {
            float t = i / (float) (Curve.POINTS - 1);
            if(Math.abs(curve.valueAt(t) - expected.valueAt(t)) > 0.01f) {
                return false;
            }
        }
        return true;
    }

    private static Curve scale(Curve curve, float factor) {
        float[] values = curve.copyValues();
        for(int i = 0; i < values.length; i++) {
            values[i] *= factor;
        }
        return Curve.of(values);
    }

    public boolean samePair(String from, String to) {
        return fromId != null && fromId.equals(from) && toId != null && toId.equals(to);
    }

    public String encode() {
        try {
            JSONObject json = new JSONObject();
            json.put("v", VERSION);
            json.put("from", fromId);
            json.put("to", toId);
            json.put("lead", leadMs);
            json.put("fade", fadeMs);
            json.put("tail", tailMs);
            json.put("bstart", bStartMs);
            json.put("out", out.encode());
            json.put("in", in.encode());
            json.put("ot", outTempo.encode());
            json.put("it", inTempo.encode());
            return json.toString();
        } catch(JSONException e) {
            return null;
        }
    }

    public static TransitionSpec decode(String encoded) {
        if(encoded == null) return null;
        try {
            JSONObject json = new JSONObject(encoded);
            int version = json.optInt("v", 0);
            if(version < 1 || version > VERSION) return null;
            String from = json.getString("from");
            String to = json.getString("to");
            Curve out = Curve.decode(json.getString("out"));
            Curve in = Curve.decode(json.getString("in"));
            if(out == null || in == null) return null;

            int fade = json.getInt("fade");
            int lead = json.optInt("lead", 0);
            if(version == 1) {
                // v1 stored when B entered, measured from the end of A.
                lead = Math.max(0, json.optInt("start", fade) - fade);
            }
            Curve outTempo = json.has("ot") ? Curve.decode(json.getString("ot")) : null;
            Curve inTempo = json.has("it") ? Curve.decode(json.getString("it")) : null;
            Curve migratedOut = out;
            Curve migratedIn = in;
            if(version < 3) {
                // v1/v2 volume curves were absolute 0..1; rescale to the new 0..2 gain range.
                migratedOut = scale(json.has("out") ? out : Curve.constant(1f), NORMAL_GAIN);
                migratedIn = scale(json.has("in") ? in : Curve.constant(1f), NORMAL_GAIN);
            } else if(version == 3) {
                // The reset button used to write the raw 0..1 equal power curves,
                // which play back as a 2x boost at the ends. Those exact shapes
                // are safe to rescale; hand drawn curves are left alone.
                if(matches(out, Curve.equalPowerOut())) {
                    migratedOut = defaultOutVolume();
                }
                if(matches(in, Curve.equalPowerIn())) {
                    migratedIn = defaultInVolume();
                }
            }
            return new TransitionSpec(from, to, lead, fade, json.optInt("tail", 0),
                    json.optInt("bstart", 0), migratedOut, migratedIn, outTempo, inTempo);
        } catch(JSONException e) {
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
