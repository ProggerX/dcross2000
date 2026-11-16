package com.proggerx.dcross2000.mix;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import java.io.File;

import com.proggerx.dcross2000.audiofx.GainAudioProcessor;
import com.proggerx.dcross2000.audiofx.GainRenderersFactory;

/**
 * Plays the lead, overlap and tail defined by a {@link TransitionSpec} so the
 * editor can be tuned by ear. Uses two short lived decoders with their own gain
 * stages, so boosts above unity match real playback, and loops until stopped.
 */
@OptIn(markerClass = UnstableApi.class)
public final class TransitionPreviewPlayer {
    private static final int TICK_MS = 20;
    private static final int PREROLL_MS = 2000;
    private static final int LOOP_DELAY_MS = 700;

    public interface Listener {
        /** @param fraction position inside the transition window, negative when outside it. */
        void onProgress(float fraction);
    }

    /** Supplies the editor's current spec so every iteration uses fresh values. */
    public interface SpecProvider {
        TransitionSpec getSpec();
    }

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExoPlayer outgoing;
    private final ExoPlayer incoming;
    private final GainAudioProcessor outgoingGain;
    private final GainAudioProcessor incomingGain;
    private final PlayheadClock playhead = new PlayheadClock();

    private Listener listener;
    private SpecProvider specProvider;

    private TransitionSpec spec;
    private File outgoingFile;
    private File incomingFile;
    private int outgoingDurationMs;
    private int windowStartPosition;
    private int outgoingStartPosition;
    private boolean incomingStarted;
    private boolean outgoingStopped;
    private boolean running;
    private boolean paused;

    public TransitionPreviewPlayer(Context context) {
        this.context = context.getApplicationContext();
        outgoingGain = new GainAudioProcessor();
        incomingGain = new GainAudioProcessor();
        outgoing = new ExoPlayer.Builder(this.context)
                .setRenderersFactory(new GainRenderersFactory(this.context, outgoingGain))
                .build();
        incoming = new ExoPlayer.Builder(this.context)
                .setRenderersFactory(new GainRenderersFactory(this.context, incomingGain))
                .build();
        outgoing.setWakeMode(C.WAKE_MODE_LOCAL);
        incoming.setWakeMode(C.WAKE_MODE_LOCAL);
        outgoingGain.setGain(1f);
        incomingGain.setGain(0f);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setSpecProvider(SpecProvider provider) {
        this.specProvider = provider;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isPlaying() {
        return running && !paused;
    }

    public boolean isPaused() {
        return running && paused;
    }

    /**
     * @param outgoingDurationMs duration of the outgoing song; the preview can
     *                           only position itself when this is known.
     */
    public void play(File outgoingFile, int outgoingDurationMs, File incomingFile, TransitionSpec spec) {
        stop();
        if(outgoingFile == null || incomingFile == null || spec == null) return;
        if(outgoingDurationMs <= spec.outgoingWindowMs() + spec.aEndMs) return;

        this.spec = spec;
        this.outgoingFile = outgoingFile;
        this.incomingFile = incomingFile;
        this.outgoingDurationMs = outgoingDurationMs;
        windowStartPosition = Math.max(0,
                outgoingDurationMs - spec.aEndMs - spec.outgoingWindowMs());
        outgoingStartPosition = Math.max(0, windowStartPosition - PREROLL_MS);
        incomingStarted = false;
        outgoingStopped = false;
        paused = false;
        playhead.reset();
        running = true;
        arm();
        handler.post(ticker);
    }

    public void pause() {
        if(!running || paused) return;
        paused = true;
        handler.removeCallbacks(ticker);
        outgoing.pause();
        incoming.pause();
    }

    public void resume() {
        if(!running || !paused) return;
        paused = false;
        // Do not project the paused interval onto the playhead.
        playhead.reset();
        outgoing.play();
        if(incomingStarted) incoming.play();
        handler.post(ticker);
    }

    public void stop() {
        running = false;
        paused = false;
        incomingStarted = false;
        outgoingStopped = false;
        if(listener != null) listener.onProgress(-1f);
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(loop);
        outgoing.stop();
        outgoing.clearMediaItems();
        incoming.stop();
        incoming.clearMediaItems();
        outgoing.setPlaybackSpeed(1f);
        incoming.setPlaybackSpeed(1f);
        outgoingGain.setGain(1f);
        incomingGain.setGain(0f);
    }

    public void release() {
        stop();
        outgoing.release();
        incoming.release();
    }

    private void arm() {
        MediaItem outItem = MediaItem.fromUri(Uri.fromFile(outgoingFile));
        MediaItem inItem = MediaItem.fromUri(Uri.fromFile(incomingFile));
        DefaultDataSource.Factory factory = new DefaultDataSource.Factory(context);
        outgoing.setMediaSource(new ProgressiveMediaSource.Factory(factory).createMediaSource(outItem),
                outgoingStartPosition);
        incoming.setMediaSource(new ProgressiveMediaSource.Factory(factory).createMediaSource(inItem),
                Math.max(0, spec.bStartMs));
        outgoing.setPlaybackSpeed(1f);
        incoming.setPlaybackSpeed(1f);
        outgoingGain.setGain(1f);
        incomingGain.setGain(0f);
        incoming.setPlayWhenReady(false);
        outgoing.setPlayWhenReady(true);
        outgoing.prepare();
        incoming.prepare();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if(!running || paused) return;
            long actualDuration = outgoing.getDuration();
            if(actualDuration != C.TIME_UNSET
                    && actualDuration > spec.outgoingWindowMs() + spec.aEndMs) {
                windowStartPosition = (int) Math.max(0L,
                        actualDuration - spec.aEndMs - spec.outgoingWindowMs());
            }
            long position = playhead.update(outgoing.getCurrentPosition(),
                    outgoing.getPlaybackParameters().speed, outgoing.isPlaying());
            if(position < windowStartPosition) {
                // The pre-roll is not part of the drawn transition.
                outgoingGain.setGain(1f);
                outgoing.setPlaybackSpeed(1f);
                if(listener != null) listener.onProgress(-1f);
                handler.postDelayed(this, TICK_MS);
                return;
            }
            int elapsedMs = (int) Math.max(0L, Math.min(Integer.MAX_VALUE,
                    position - windowStartPosition));

            // Curves follow live edits; timing still belongs to this iteration.
            TransitionSpec live = specProvider != null ? specProvider.getSpec() : null;
            if(live == null) live = spec;

            float progress = Math.max(0f, Math.min(1f, elapsedMs / (float) spec.windowMs()));
            float outProgress = Math.max(0f, Math.min(1f,
                    elapsedMs / (float) Math.max(1, spec.outgoingWindowMs())));
            float inProgress = Math.max(0f, Math.min(1f,
                    (elapsedMs - spec.leadMs) / (float) Math.max(1, spec.incomingWindowMs())));
            if(listener != null) listener.onProgress(progress);

            if(!incomingStarted && elapsedMs >= spec.leadMs
                    && incoming.getPlaybackState() == Player.STATE_READY) {
                incomingStarted = true;
                incoming.setPlayWhenReady(true);
            }
            if(!outgoingStopped && elapsedMs >= spec.outgoingWindowMs()) {
                outgoingStopped = true;
                outgoing.pause();
                outgoingGain.setGain(0f);
            }

            if(!outgoingStopped) {
                outgoingGain.setGain(TransitionSpec.gainAt(live.out, outProgress));
                outgoing.setPlaybackSpeed(TransitionSpec.tempoAt(live.outTempo, outProgress));
            }
            if(incomingStarted) {
                incomingGain.setGain(TransitionSpec.gainAt(live.in, inProgress));
                incoming.setPlaybackSpeed(TransitionSpec.tempoAt(live.inTempo, inProgress));
            }

            if(progress >= 1f) {
                running = false;
                outgoing.stop();
                incoming.stop();
                handler.postDelayed(loop, LOOP_DELAY_MS);
                return;
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if(outgoingFile == null || incomingFile == null) return;
            // Re-arm from the editor's latest state, not the spec this
            // iteration started with.
            TransitionSpec latest = specProvider != null ? specProvider.getSpec() : spec;
            if(latest == null) latest = spec;
            if(latest == null) return;
            play(outgoingFile, outgoingDurationMs, incomingFile, latest);
        }
    };
}
