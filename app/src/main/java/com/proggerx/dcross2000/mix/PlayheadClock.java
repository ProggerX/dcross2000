package com.proggerx.dcross2000.mix;

import android.os.SystemClock;

/**
 * Turns a decoder's coarse playhead updates into a smooth, audio-anchored
 * clock.
 *
 * <p>ExoPlayer only refreshes {@code getCurrentPosition()} every few hundred
 * milliseconds. Reading it directly makes a playhead jump in steps. This clock
 * anchors to each new media position and projects forward with the playback
 * rate in between, so motion is smooth while the timeline still follows the
 * audio and quickly re-aligns when the decoder reports again.</p>
 */
public final class PlayheadClock {
    private static final long MAX_PROJECTION_MS = 400;

    private long lastRaw = -1;
    private long anchorAt;
    private float anchorSpeed = 1f;
    private long smoothed;

    public void reset() {
        lastRaw = -1;
        anchorAt = 0L;
        anchorSpeed = 1f;
        smoothed = 0L;
    }

    public long update(long rawPosition, float playbackSpeed, boolean playing) {
        long now = SystemClock.elapsedRealtime();
        if(rawPosition != lastRaw) {
            lastRaw = rawPosition;
            anchorAt = now;
            anchorSpeed = playbackSpeed > 0f && Float.isFinite(playbackSpeed) ? playbackSpeed : 1f;
            smoothed = rawPosition;
        } else if(playing && anchorAt != 0L) {
            long projected = lastRaw + (long) ((now - anchorAt) * anchorSpeed);
            smoothed = Math.min(projected, lastRaw + MAX_PROJECTION_MS);
        } else {
            smoothed = rawPosition;
        }
        return smoothed;
    }
}
