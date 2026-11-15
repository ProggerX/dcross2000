package com.proggerx.dcross2000.service;

import android.content.Context;

import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.proggerx.dcross2000.domain.MusicDirectory.Entry;
import com.proggerx.dcross2000.mix.Curve;
import com.proggerx.dcross2000.mix.TransitionSpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** Drives the two decoder engine through a user drawn overlap. */
@OptIn(markerClass = UnstableApi.class)
public class CrossfadePlaybackTest {
    private static final int SECONDS = 3;

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final List<File> files = new ArrayList<>();
    private LocalPlayback playback;

    @After
    public void tearDown() {
        if(playback != null) onMain(() -> { playback.release(); return null; });
        for(File file : files) {
            file.delete();
        }
    }

    @Test
    public void drawnTransitionSwitchesOnceAndKeepsPlaying() throws Exception {
        DownloadFile first = song("first");
        DownloadFile second = song("second");
        File firstAudio = wave(first);
        File secondAudio = wave(second);

        AtomicInteger transitions = new AtomicInteger();
        AtomicReference<DownloadFile> from = new AtomicReference<>();
        AtomicReference<DownloadFile> to = new AtomicReference<>();
        AtomicReference<Boolean> fadingAtSwitch = new AtomicReference<>();
        AtomicReference<Integer> incomingPosition = new AtomicReference<>();
        AtomicReference<PlaybackException> error = new AtomicReference<>();
        CountDownLatch switched = new CountDownLatch(1);

        onMain(() -> {
            playback = new LocalPlayback(context, new LocalPlayback.Listener() {
                @Override public void onState(DownloadFile file, int state, boolean playWhenReady) {
                }

                @Override public void onTransition(DownloadFile previous, DownloadFile current) {
                    transitions.incrementAndGet();
                    from.set(previous);
                    to.set(current);
                    fadingAtSwitch.set(playback.isFading());
                    incomingPosition.set(playback.getCurrentPosition());
                    switched.countDown();
                }

                @Override public void onError(DownloadFile file, PlaybackException playbackException) {
                    error.set(playbackException);
                }
            });
            playback.prepare(first, firstAudio.getAbsolutePath(), 0, true, 1f, 1f);
            return null;
        });

        long start = android.os.SystemClock.elapsedRealtime();
        TransitionSpec spec = new TransitionSpec(first.getSong().getId(), second.getSong().getId(),
                1000, 1000, 0, Curve.equalPowerOut(), Curve.equalPowerIn());
        onMain(() -> {
            playback.setNext(first, second, secondAudio.getAbsolutePath(), 1f, spec);
            return null;
        });

        assertTrue("Transition fired", switched.await(6, TimeUnit.SECONDS));
        long elapsed = android.os.SystemClock.elapsedRealtime() - start;
        assertNull("No playback error", error.get());
        assertSame(first, from.get());
        assertSame(second, to.get());
        assertTrue("Overlap is running when the song switches", fadingAtSwitch.get());
        assertTrue("Incoming song starts at the cue point", incomingPosition.get() < 400);
        assertTrue("Transition starts before the outgoing song ends", elapsed < SECONDS * 1000 + 500);
        assertTrue("Transition does not fire too early", elapsed > 600);

        assertTrue("Overlap completes", waitFor(() -> !onMain(playback::isFading), 3000));
        assertEquals("Exactly one automatic switch", 1, transitions.get());
        assertTrue("Second song is audible", waitFor(() -> {
            int position = onMain(playback::getCurrentPosition);
            return position > 100 && position < SECONDS * 1000;
        }, 2000));
    }

    @Test
    public void leadAutomatesTheOutgoingSongBeforeTheIncomingEnters() throws Exception {
        DownloadFile first = song("first");
        DownloadFile second = song("second");
        File firstAudio = wave(first);
        File secondAudio = wave(second);

        AtomicInteger transitions = new AtomicInteger();
        CountDownLatch switched = new CountDownLatch(1);
        onMain(() -> {
            playback = new LocalPlayback(context, new LocalPlayback.Listener() {
                @Override public void onState(DownloadFile file, int state, boolean playWhenReady) {
                }

                @Override public void onTransition(DownloadFile previous, DownloadFile current) {
                    transitions.incrementAndGet();
                    switched.countDown();
                }

                @Override public void onError(DownloadFile file, PlaybackException playbackException) {
                }
            });
            playback.prepare(first, firstAudio.getAbsolutePath(), 0, true, 1f, 1f);
            return null;
        });

        // Lead ramps the outgoing song from 1.5x to 1.0x before the incoming enters.
        float[] tempo = new float[Curve.POINTS];
        for(int i = 0; i < Curve.POINTS; i++) {
            float t = i / (float) (Curve.POINTS - 1);
            tempo[i] = TransitionSpec.tempoToNormalized(1.5f - 0.5f * t);
        }
        TransitionSpec spec = new TransitionSpec(first.getSong().getId(), second.getSong().getId(),
                1200, 600, 0, Curve.constant(1f), Curve.constant(1f),
                Curve.of(tempo), TransitionSpec.defaultTempoCurve());
        onMain(() -> {
            playback.setNext(first, second, secondAudio.getAbsolutePath(), 1f, spec);
            return null;
        });

        assertTrue("Lead starts", waitFor(() -> onMain(playback::isFading), 4000));
        assertEquals("Incoming song waits for the lead", 0, transitions.get());
        assertTrue("Lead applies the outgoing tempo",
                waitFor(() -> onMain(playback::getPlaybackRate) > 1.2f, 1500));
        assertEquals("Incoming song still waits", 0, transitions.get());
        assertTrue("Incoming enters after the lead", switched.await(4, TimeUnit.SECONDS));
        assertEquals(1, transitions.get());
    }

    @Test
    public void tailKeepsAutomatingTheIncomingSongAfterTheOutgoingStops() throws Exception {
        DownloadFile first = song("first");
        DownloadFile second = song("second");
        File firstAudio = wave(first);
        File secondAudio = wave(second);

        AtomicInteger transitions = new AtomicInteger();
        CountDownLatch switched = new CountDownLatch(1);
        onMain(() -> {
            playback = new LocalPlayback(context, new LocalPlayback.Listener() {
                @Override public void onState(DownloadFile file, int state, boolean playWhenReady) {
                }

                @Override public void onTransition(DownloadFile previous, DownloadFile current) {
                    transitions.incrementAndGet();
                    switched.countDown();
                }

                @Override public void onError(DownloadFile file, PlaybackException playbackException) {
                }
            });
            playback.prepare(first, firstAudio.getAbsolutePath(), 0, true, 1f, 1f);
            return null;
        });

        TransitionSpec spec = new TransitionSpec(first.getSong().getId(), second.getSong().getId(),
                500, 500, 700, 0, Curve.constant(0.5f), Curve.constant(0.5f),
                TransitionSpec.defaultTempoCurve(), TransitionSpec.defaultTempoCurve());
        onMain(() -> {
            playback.setNext(first, second, secondAudio.getAbsolutePath(), 1f, spec);
            return null;
        });

        assertTrue("Transition fired", switched.await(6, TimeUnit.SECONDS));
        assertTrue("Tail keeps the transition running", onMain(playback::isFading));
        int before = onMain(playback::getCurrentPosition);
        assertTrue("Incoming song advances through the tail", waitFor(
                () -> onMain(playback::getCurrentPosition) > before + 150, 1500));
        assertTrue("Transition ends", waitFor(() -> !onMain(playback::isFading), 3000));
        assertEquals("Still one switch", 1, transitions.get());
        assertTrue("Second song keeps playing", waitFor(
                () -> onMain(playback::getCurrentPosition) > 300, 2000));
    }

    @Test
    public void cancelStopsTheOverlapWithoutLosingTheOutgoingSong() throws Exception {
        DownloadFile first = song("first");
        DownloadFile second = song("second");
        File firstAudio = wave(first);
        File secondAudio = wave(second);

        AtomicInteger transitions = new AtomicInteger();
        CountDownLatch switched = new CountDownLatch(1);
        onMain(() -> {
            playback = new LocalPlayback(context, new LocalPlayback.Listener() {
                @Override public void onState(DownloadFile file, int state, boolean playWhenReady) {
                }

                @Override public void onTransition(DownloadFile previous, DownloadFile current) {
                    transitions.incrementAndGet();
                    switched.countDown();
                }

                @Override public void onError(DownloadFile file, PlaybackException playbackException) {
                }
            });
            playback.prepare(first, firstAudio.getAbsolutePath(), 0, true, 1f, 1f);
            return null;
        });

        TransitionSpec spec = new TransitionSpec(first.getSong().getId(), second.getSong().getId(),
                1200, 1200, 0, Curve.equalPowerOut(), Curve.equalPowerIn());
        onMain(() -> {
            playback.setNext(first, second, secondAudio.getAbsolutePath(), 1f, spec);
            return null;
        });

        assertTrue("Transition fired", switched.await(6, TimeUnit.SECONDS));
        onMain(() -> {
            playback.cancelTransition();
            return null;
        });
        assertFalse("Overlap is gone", onMain(playback::isFading));
        assertEquals("Canceling does not switch again", 1, transitions.get());
        assertTrue("The audible song keeps moving", waitFor(
                () -> onMain(playback::getCurrentPosition) > 200, 2000));
    }

    private DownloadFile song(String name) throws Exception {
        Entry song = new Entry(UUID.randomUUID().toString());
        song.setTitle(name);
        song.setSuffix("wav");
        song.setDuration(SECONDS);
        return new DownloadFile(context, song, false);
    }

    private File wave(DownloadFile file) throws Exception {
        File audio = file.getSaveFile();
        files.add(audio);
        File parent = audio.getParentFile();
        assertTrue("Audio directory exists", parent.isDirectory() || parent.mkdirs());
        int bytes = 8000 * 2 * SECONDS;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x46464952).putInt(36 + bytes).putInt(0x45564157);
        header.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1);
        header.putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
        header.putInt(0x61746164).putInt(bytes);
        try(FileOutputStream output = new FileOutputStream(audio)) {
            output.write(header.array());
            output.write(new byte[bytes]);
        }
        return audio;
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        while(android.os.SystemClock.elapsedRealtime() < deadline) {
            if(condition.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    private static <T> T onMain(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> result.set(action.get()));
        return result.get();
    }
}
