package com.proggerx.dcross2000.service;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import com.proggerx.dcross2000.audiofx.GainAudioProcessor;
import com.proggerx.dcross2000.audiofx.GainRenderersFactory;
import com.proggerx.dcross2000.mix.PlayheadClock;
import com.proggerx.dcross2000.mix.TransitionSpec;

/**
 * Native player for cached tracks, growing files and radio.
 *
 * <p>Two decoders are used. The active one plays the current song and the standby
 * one holds the next song. Gapless playback still appends the next source to the
 * active decoder, exactly like a plain ExoPlayer. A user drawn {@link TransitionSpec}
 * instead preloads the next song into the standby decoder and automates both of
 * them: the outgoing song alone during the lead, both during the overlap and the
 * incoming song alone during the tail.</p>
 *
 * <p>Each decoder owns a {@link GainAudioProcessor}, so envelopes can boost a
 * quiet track above unity instead of only fading it down.</p>
 */
@OptIn(markerClass = UnstableApi.class)
final class LocalPlayback {
    interface Listener {
        void onState(DownloadFile file, int state, boolean playWhenReady);
        void onTransition(DownloadFile previous, DownloadFile current);
        void onError(DownloadFile file, PlaybackException error);
    }

    private static final int SLOT_COUNT = 2;
    private static final int TICK_MS = 20;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Context context;
    private final Listener listener;
    private final DataSource.Factory files;
    private final DataSource.Factory radio = new NoRangeHttpDataSource.Factory();
    private final int audioSessionId;

    private final ExoPlayer[] players = new ExoPlayer[SLOT_COUNT];
    private final GainAudioProcessor[] gains = new GainAudioProcessor[SLOT_COUNT];
    private final Source[] sources = new Source[SLOT_COUNT];

    /** Slot currently reported to the service as "the" song. */
    private int active;
    /** Outgoing slot while a drawn transition is running, -1 otherwise. */
    private int fading = -1;
    /** Slot where the armed transition was preloaded, -1 when not loaded. */
    private int nextSlot = -1;

    private Fade fade;
    private float playbackSpeed = 1f;

    /** Gapless next source appended to the active decoder. */
    private Source queued;
    /** Armed transition waiting for a free decoder slot. */
    private Source nextSource;
    private TransitionSpec nextSpec;

    private boolean ticking;
    private volatile Snapshot snapshot = new Snapshot(0, 0, false, 1f);
    private volatile boolean released;

    LocalPlayback(Context context, Listener listener) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Create local playback on the main looper");
        }
        this.context = context;
        this.listener = listener;
        files = new DefaultDataSource.Factory(context);
        audioSessionId = ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE)).generateAudioSessionId();
        player(0);
    }

    void prepare(DownloadFile file, String uri, int position, boolean playing, float gain, float speed) {
        Source source = source(file, uri, gain);
        execute(() -> {
            resetLocked();
            playbackSpeed = speed;
            sources[0] = source;
            ExoPlayer player = player(0);
            player.setMediaSource(source.mediaSource, position);
            player.setPlaybackSpeed(speed);
            player.setPlayWhenReady(playing);
            applyVolumes();
            player.prepare();
            updateSnapshot();
            restartTickerIfNeeded();
        });
    }

    /** Gapless next source; kept for callers that do not use drawn transitions. */
    void setNext(DownloadFile expectedCurrent, DownloadFile file, String uri, float gain) {
        setNext(expectedCurrent, file, uri, gain, null);
    }

    /**
     * @param transition when non-null the next song is preloaded into the standby
     *                   decoder and the saved envelopes are applied as an overlap.
     */
    void setNext(DownloadFile expectedCurrent, DownloadFile file, String uri, float gain,
                 @Nullable TransitionSpec transition) {
        Source source = source(file, uri, gain);
        execute(() -> {
            if (sources[active] == null || sources[active].file != expectedCurrent) return;

            if (transition == null) {
                nextSource = null;
                nextSpec = null;
                if (nextSlot >= 0 && nextSlot != active && nextSlot != fading) {
                    clearSlot(nextSlot);
                }
                nextSlot = -1;
                queued = source;
                ExoPlayer player = player(active);
                if (player.getMediaItemCount() > 1) {
                    player.removeMediaItems(1, player.getMediaItemCount());
                }
                player.addMediaSource(source.mediaSource);
                restartTickerIfNeeded();
                return;
            }

            queued = null;
            ExoPlayer player = player(active);
            if (player.getMediaItemCount() > 1) {
                player.removeMediaItems(1, player.getMediaItemCount());
            }
            if (nextSlot >= 0 && nextSlot != active && nextSlot != fading) {
                clearSlot(nextSlot);
            }
            nextSlot = -1;
            nextSource = source;
            nextSpec = transition;
            loadNextIfPossible();
            restartTickerIfNeeded();
        });
    }

    void clearNext() {
        execute(() -> {
            queued = null;
            ExoPlayer player = players[active];
            if (player != null && player.getMediaItemCount() > 1) {
                player.removeMediaItems(1, player.getMediaItemCount());
            }
            nextSource = null;
            nextSpec = null;
            if (nextSlot >= 0 && nextSlot != active && nextSlot != fading) {
                clearSlot(nextSlot);
            }
            nextSlot = -1;
            restartTickerIfNeeded();
        });
    }

    /** Stops an in-flight transition, e.g. when the user skips or seeks. */
    void cancelTransition() {
        execute(() -> {
            abortFadeLocked();
            applyVolumes();
            updateSnapshot();
            restartTickerIfNeeded();
        });
    }

    void setGain(DownloadFile file, float gain) {
        execute(() -> {
            for(Source source : sources) {
                if (source != null && source.file == file) {
                    source.gain = gain;
                }
            }
            if (queued != null && queued.file == file) queued.gain = gain;
            applyVolumes();
        });
    }

    void play() {
        execute(() -> {
            ExoPlayer player = players[active];
            if (player != null) player.play();
            if (fading >= 0 && players[fading] != null) players[fading].play();
            updateSnapshot();
            restartTickerIfNeeded();
        });
    }

    void pause() {
        execute(() -> {
            ExoPlayer player = players[active];
            if (player != null) player.pause();
            if (fading >= 0 && players[fading] != null) players[fading].pause();
            updateSnapshot();
        });
    }

    void stop() {
        execute(() -> {
            for(int i = 0; i < SLOT_COUNT; i++) {
                if (players[i] != null) players[i].stop();
            }
            queued = null;
            nextSource = null;
            nextSpec = null;
            if (nextSlot >= 0 && nextSlot != active && nextSlot != fading) {
                clearSlot(nextSlot);
            }
            nextSlot = -1;
            fade = null;
            fading = -1;
            updateSnapshot();
            restartTickerIfNeeded();
        });
    }

    void seekTo(int position) {
        // Publish the target now so a read right after the call sees it.
        Snapshot old = snapshot;
        snapshot = new Snapshot(position, old.duration, old.playing, old.speed);
        execute(() -> {
            abortFadeLocked();
            ExoPlayer player = players[active];
            if (player != null) player.seekTo(position);
            applyVolumes();
            updateSnapshot();
            loadNextIfPossible();
            restartTickerIfNeeded();
        });
    }

    void setSpeed(float speed) {
        execute(() -> {
            playbackSpeed = speed;
            // While a drawn transition runs, the tempo curves own the rate of
            // both decoders. The service-wide speed is restored afterwards.
            if (fade == null) {
                ExoPlayer player = players[active];
                if (player != null) player.setPlaybackSpeed(speed);
            }
            updateSnapshot();
        });
    }

    int getAudioSessionId() { return audioSessionId; }
    int getDuration() { return snapshot.duration; }
    int getCurrentPosition() { return snapshot.position(); }
    /** Current playback rate of the active decoder (tempo curves change it). */
    float getPlaybackRate() { return snapshot.speed; }

    /** True from the start of the lead until the transition window is over. */
    boolean isFading() { return fade != null; }

    /** Every song currently held by a decoder, including the fading one. */
    java.util.List<DownloadFile> getLoadedFiles() {
        java.util.List<DownloadFile> loaded = new java.util.ArrayList<>(SLOT_COUNT + 2);
        if(queued != null) loaded.add(queued.file);
        if(nextSource != null && !loaded.contains(nextSource.file)) loaded.add(nextSource.file);
        for(Source source : sources) {
            if(source != null && !loaded.contains(source.file)) loaded.add(source.file);
        }
        return loaded;
    }

    boolean isQueued(DownloadFile file) {
        if (file == null) return false;
        if (queued != null && queued.file == file) return true;
        if (nextSource != null && nextSource.file == file) return true;
        if (nextSlot >= 0 && sources[nextSlot] != null && sources[nextSlot].file == file) return true;
        return false;
    }

    void reset() {
        execute(() -> {
            resetLocked();
            updateSnapshot();
        });
    }

    void release() {
        execute(() -> {
            released = true;
            handler.removeCallbacksAndMessages(null);
            ticking = false;
            for(int i = 0; i < SLOT_COUNT; i++) {
                if (players[i] != null) {
                    players[i].release();
                    players[i] = null;
                }
                sources[i] = null;
                gains[i] = null;
            }
        });
    }

    private void resetLocked() {
        fade = null;
        fading = -1;
        active = 0;
        queued = null;
        nextSource = null;
        nextSpec = null;
        nextSlot = -1;
        for(int i = 0; i < SLOT_COUNT; i++) {
            clearSlot(i);
        }
        ticking = false;
        handler.removeCallbacks(ticker);
    }

    private void clearSlot(int slot) {
        ExoPlayer player = players[slot];
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        if (gains[slot] != null) {
            gains[slot].setGain(0f);
        }
        sources[slot] = null;
    }

    private void loadNextIfPossible() {
        if (nextSource == null || nextSpec == null) return;
        if (fading >= 0) return;
        int slot = 1 - active;
        if (sources[slot] != null) return;
        nextSlot = slot;
        ExoPlayer player = player(slot);
        player.stop();
        player.clearMediaItems();
        sources[slot] = nextSource;
        player.setMediaSource(nextSource.mediaSource, nextSpec.bStartMs);
        player.setPlaybackSpeed(1f);
        player.setPlayWhenReady(false);
        applyVolumes();
        player.prepare();
    }

    private void abortFadeLocked() {
        Fade current = fade;
        if (current == null) return;
        int outgoing = current.outgoing;
        int incoming = current.incoming;
        boolean started = current.incomingStarted;
        fade = null;
        fading = -1;
        if (started) {
            clearSlot(outgoing);
            active = incoming;
        } else {
            clearSlot(incoming);
            active = outgoing;
        }
        ExoPlayer player = players[active];
        if (player != null) player.setPlaybackSpeed(playbackSpeed);
    }

    private void maybeStartFade() {
        if (fade != null || nextSpec == null || nextSlot < 0 || sources[nextSlot] == null) return;
        ExoPlayer player = players[active];
        if (player == null || !player.isPlaying()) return;
        long duration = player.getDuration();
        if (duration == C.TIME_UNSET || duration <= 0) return;
        long remaining = duration - player.getCurrentPosition();
        int lookahead = nextSpec.aEndMs + nextSpec.outgoingWindowMs();
        if (remaining > lookahead) return;
        ExoPlayer incoming = players[nextSlot];
        if (incoming == null || incoming.getPlaybackState() != Player.STATE_READY) return;

        int windowStart = (int) Math.max(0L,
                duration - nextSpec.aEndMs - nextSpec.outgoingWindowMs());
        startFade(windowStart);
    }

    private void startFade(int windowStartMs) {
        Source outgoingSource = sources[active];
        Source incomingSource = sources[nextSlot];
        TransitionSpec spec = nextSpec;
        if (outgoingSource == null || incomingSource == null || spec == null) return;

        int outgoingSlot = active;
        int incomingSlot = nextSlot;
        if (players[incomingSlot] == null) return;

        fade = new Fade(spec, outgoingSlot, incomingSlot, windowStartMs);
        fade.elapsedMs = fadeProgressMs(fade);
        fading = outgoingSlot;
        nextSlot = -1;
        nextSource = null;
        nextSpec = null;

        // The outgoing song remains active during the lead; if the window is
        // already part way in, advanceFade starts the incoming song immediately.
        advanceFade();
        restartTickerIfNeeded();
    }

    private void advanceFade() {
        Fade current = fade;
        if (current == null) return;

        if (!current.incomingStarted && current.incomingDue()) {
            current.incomingStarted = true;
            ExoPlayer incoming = players[current.incoming];
            if (incoming != null) incoming.setPlayWhenReady(true);
            active = current.incoming;
            applyVolumes();
            updateSnapshot();
            // Reporting the transition here reuses the regular "song changed"
            // path: scrobbling, bookmarks, notification and media session.
            if (sources[current.outgoing] != null && sources[current.incoming] != null) {
                listener.onTransition(sources[current.outgoing].file, sources[current.incoming].file);
            }
        }

        ExoPlayer outgoing = players[current.outgoing];
        boolean outgoingEnded = outgoing != null && outgoing.getPlaybackState() == Player.STATE_ENDED;
        if (!current.outgoingStopped && (current.outgoingDue() || outgoingEnded)) {
            // Remember the smoothed audio clock where the outgoing side stops
            // so the tail continues from the incoming playhead without jumping.
            ExoPlayer tail = players[current.incoming];
            current.tailElapsedBase = current.elapsedMs;
            current.tailPositionBase = tail != null
                    ? current.inClock.update(tail.getCurrentPosition(),
                    tail.getPlaybackParameters().speed, tail.isPlaying()) : 0L;
            clearSlot(current.outgoing);
            current.outgoingStopped = true;
        }

        applyVolumes();
        applyTempos(current);

        if (current.progress() >= 1f) {
            endFade();
        }
    }

    private void endFade() {
        Fade current = fade;
        if (current == null) return;
        int outgoing = current.outgoing;
        int incoming = current.incoming;
        boolean started = current.incomingStarted;
        fade = null;
        fading = -1;

        clearSlot(outgoing);
        if (started) {
            active = incoming;
            ExoPlayer player = players[incoming];
            if (player != null) player.setPlaybackSpeed(playbackSpeed);
        } else {
            clearSlot(incoming);
            active = outgoing;
            ExoPlayer player = players[outgoing];
            if (player != null) player.setPlaybackSpeed(playbackSpeed);
        }
        applyVolumes();
        updateSnapshot();
        loadNextIfPossible();
        restartTickerIfNeeded();
    }

    private void applyTempos(Fade current) {
        if (!current.outgoingStopped) {
            float outTempo = TransitionSpec.tempoAt(current.spec.outTempo, current.outProgress());
            setTempo(players[current.outgoing], current, true, outTempo);
        }
        if (current.incomingStarted) {
            float inTempo = TransitionSpec.tempoAt(current.spec.inTempo, current.inProgress());
            setTempo(players[current.incoming], current, false, inTempo);
        }
    }

    private void setTempo(ExoPlayer player, Fade current, boolean outgoing, float tempo) {
        if (player == null) return;
        float previous = outgoing ? current.lastOutTempo : current.lastInTempo;
        if (Math.abs(previous - tempo) < 0.005f) return;
        if (outgoing) current.lastOutTempo = tempo;
        else current.lastInTempo = tempo;
        player.setPlaybackSpeed(Math.max(TransitionSpec.MIN_TEMPO, Math.min(TransitionSpec.MAX_TEMPO, tempo)));
    }

    private void applyVolumes() {
        Fade current = fade;
        for(int i = 0; i < SLOT_COUNT; i++) {
            Source source = sources[i];
            GainAudioProcessor gainStage = gains[i];
            if (source == null || gainStage == null) continue;
            float gain = source.gain;
            if (current != null) {
                if (i == current.outgoing) {
                    gain = current.outgoingStopped ? 0f
                            : gain * TransitionSpec.gainAt(current.spec.out, current.outProgress());
                } else if (i == current.incoming) {
                    gain = current.incomingStarted
                            ? gain * TransitionSpec.gainAt(current.spec.in, current.inProgress()) : 0f;
                } else {
                    gain = 0f;
                }
            } else if (i != active) {
                // A preloaded transition partner stays silent until the fade starts.
                gain = 0f;
            }
            gainStage.setGain(Math.max(0f, Math.min(TransitionSpec.MAX_GAIN, gain)));
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            ticking = false;
            if (released) return;
            Fade current = fade;
            if (current != null) {
                current.elapsedMs = fadeProgressMs(current);
                advanceFade();
            } else if (nextSpec != null) {
                maybeStartFade();
            }
            updateSnapshot();
            boolean more = fade != null || nextSpec != null;
            if (more) {
                ticking = true;
                boolean audible = fade != null || (players[active] != null && players[active].isPlaying());
                handler.postDelayed(this, audible ? TICK_MS : TICK_MS * 6L);
            }
        }
    };

    private void restartTickerIfNeeded() {
        if (fade != null || nextSpec != null) {
            if (!ticking) {
                ticking = true;
                handler.postDelayed(ticker, TICK_MS);
            }
        } else if (ticking) {
            ticking = false;
            handler.removeCallbacks(ticker);
        }
    }

    private void execute(Runnable action) {
        if (Looper.myLooper() == handler.getLooper()) {
            if (!released) action.run();
        } else {
            handler.post(() -> { if (!released) action.run(); });
        }
    }

    private ExoPlayer player(int slot) {
        ExoPlayer player = players[slot];
        if (player == null) {
            GainAudioProcessor gainStage = new GainAudioProcessor();
            gains[slot] = gainStage;
            player = new ExoPlayer.Builder(context)
                    .setRenderersFactory(new GainRenderersFactory(context, gainStage))
                    .build();
            // Sharing the session keeps the user's equalizer active on both decoders.
            player.setAudioSessionId(audioSessionId);
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false);
            player.setWakeMode(C.WAKE_MODE_LOCAL);
            player.addListener(listener(slot));
            players[slot] = player;
        }
        return player;
    }

    private Player.Listener listener(final int slot) {
        return new Player.Listener() {
            @Override public void onMediaItemTransition(MediaItem item, int reason) {
                if (slot != active || reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return;
                if (queued == null || sources[slot] == null) return;
                DownloadFile previous = sources[slot].file;
                Source next = queued;
                queued = null;
                sources[slot] = next;
                player(slot).removeMediaItem(0);
                applyVolumes();
                updateSnapshot();
                listener.onTransition(previous, next.file);
            }

            @Override public void onEvents(Player ignored, Player.Events events) {
                updateSnapshot();
                if (slot == active && sources[slot] != null) {
                    listener.onState(sources[slot].file, player(slot).getPlaybackState(),
                            player(slot).getPlayWhenReady());
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                Source source = sources[slot];
                if (source == null) return;
                if (slot == active && slot != fading) {
                    listener.onError(source.file, error);
                } else {
                    // A failed preload must never take the audible song down.
                    if (fade != null && slot == fade.outgoing && fade.incomingStarted) {
                        clearSlot(slot);
                        fade.outgoingStopped = true;
                    } else {
                        clearSlot(slot);
                        if (nextSlot == slot) nextSlot = -1;
                        nextSpec = null;
                        nextSource = null;
                    }
                }
            }
        };
    }

    private Source source(DownloadFile file, String uri, float gain) {
        MediaItem item = SessionPlayer.mediaItem(file.getSong()).buildUpon()
                .setUri(uri.startsWith("/") ? Uri.fromFile(new java.io.File(uri)) : Uri.parse(uri))
                .setTag(file).build();
        return new Source(file, gain, new ProgressiveMediaSource.Factory(file.isStream() ? radio : files)
                .createMediaSource(item));
    }

    private void updateSnapshot() {
        ExoPlayer player = players[active];
        if (player == null) {
            snapshot = new Snapshot(0, 0, false, 1f);
            return;
        }
        long duration = player.getDuration();
        snapshot = new Snapshot((int) Math.min(Integer.MAX_VALUE, Math.max(0, player.getCurrentPosition())),
                duration == C.TIME_UNSET ? 0 : (int) Math.min(Integer.MAX_VALUE, duration),
                player.isPlaying(), player.getPlaybackParameters().speed);
    }

    private static final class Source {
        final DownloadFile file;
        float gain;
        final MediaSource mediaSource;
        Source(DownloadFile file, float gain, MediaSource mediaSource) {
            this.file = file;
            this.gain = gain;
            this.mediaSource = mediaSource;
        }
    }

    /** Progress measured from the outgoing, then incoming, audio playhead. */
    private int fadeProgressMs(Fade current) {
        ExoPlayer outgoing = players[current.outgoing];
        if (outgoing != null && !current.outgoingStopped) {
            long position = current.outClock.update(outgoing.getCurrentPosition(),
                    outgoing.getPlaybackParameters().speed, outgoing.isPlaying());
            return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, position - current.windowStartMs));
        }
        ExoPlayer incoming = players[current.incoming];
        if (incoming != null && current.incomingStarted && current.tailElapsedBase >= 0) {
            long position = current.inClock.update(incoming.getCurrentPosition(),
                    incoming.getPlaybackParameters().speed, incoming.isPlaying());
            long value = current.tailElapsedBase + (position - current.tailPositionBase);
            return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
        }
        return current.elapsedMs;
    }

    private static final class Fade {
        final TransitionSpec spec;
        final int outgoing;
        final int incoming;
        final long windowStartMs;
        int elapsedMs;
        final PlayheadClock outClock = new PlayheadClock();
        final PlayheadClock inClock = new PlayheadClock();
        int tailElapsedBase = -1;
        long tailPositionBase;
        boolean incomingStarted;
        boolean outgoingStopped;
        float lastOutTempo = -1f;
        float lastInTempo = -1f;

        Fade(TransitionSpec spec, int outgoing, int incoming, long windowStartMs) {
            this.spec = spec;
            this.outgoing = outgoing;
            this.incoming = incoming;
            this.windowStartMs = windowStartMs;
        }

        float progress() {
            return Math.max(0f, Math.min(1f,
                    elapsedMs / (float) Math.max(1, spec.windowMs())));
        }

        float outProgress() {
            return Math.max(0f, Math.min(1f,
                    elapsedMs / (float) Math.max(1, spec.outgoingWindowMs())));
        }

        float inProgress() {
            return Math.max(0f, Math.min(1f,
                    (elapsedMs - spec.leadMs) / (float) Math.max(1, spec.incomingWindowMs())));
        }

        boolean incomingDue() {
            return elapsedMs >= spec.leadMs;
        }

        boolean outgoingDue() {
            return elapsedMs >= spec.outgoingWindowMs();
        }
    }

    private static final class Snapshot {
        final int position;
        final int duration;
        final boolean playing;
        final float speed;
        final long capturedAt = SystemClock.elapsedRealtime();
        Snapshot(int position, int duration, boolean playing, float speed) {
            this.position = position;
            this.duration = duration;
            this.playing = playing;
            this.speed = speed;
        }
        int position() {
            long value = position + (playing ? (long) ((SystemClock.elapsedRealtime() - capturedAt) * speed) : 0);
            return (int) Math.min(duration > 0 ? duration : Integer.MAX_VALUE, value);
        }
    }
}
