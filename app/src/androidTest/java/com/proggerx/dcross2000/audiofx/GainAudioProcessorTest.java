package com.proggerx.dcross2000.audiofx;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import static org.junit.Assert.assertEquals;

@OptIn(markerClass = UnstableApi.class)
public class GainAudioProcessorTest {
    @Test
    public void boostsAndClipsSixteenBitSamples() throws Exception {
        GainAudioProcessor processor = new GainAudioProcessor();
        processor.configure(new AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT));
        processor.setGain(2f);

        ByteBuffer input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
        input.asShortBuffer().put(new short[]{1000, -1000, 20000, -20000});
        input.position(0);
        input.limit(8);
        processor.queueInput(input);

        ByteBuffer output = processor.getOutput();
        ShortBuffer samples = output.order(ByteOrder.nativeOrder()).asShortBuffer();
        assertEquals(2000, samples.get(0));
        assertEquals(-2000, samples.get(1));
        assertEquals(Short.MAX_VALUE, samples.get(2));
        assertEquals(Short.MIN_VALUE, samples.get(3));
        processor.reset();
    }

    @Test
    public void unityGainLeavesSamplesUntouched() throws Exception {
        GainAudioProcessor processor = new GainAudioProcessor();
        processor.configure(new AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT));
        processor.setGain(1f);

        ByteBuffer input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder());
        input.asShortBuffer().put(new short[]{321, -543});
        input.position(0);
        input.limit(4);
        processor.queueInput(input);

        ShortBuffer samples = processor.getOutput().order(ByteOrder.nativeOrder()).asShortBuffer();
        assertEquals(321, samples.get(0));
        assertEquals(-543, samples.get(1));
        processor.reset();
    }
}
