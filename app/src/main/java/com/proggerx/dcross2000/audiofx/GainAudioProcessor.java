package com.proggerx.dcross2000.audiofx;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Sample gain stage that supports values above 1.0. ExoPlayer's player volume
 * is clamped to 0..1, so normalizing a quiet track upwards needs a processor.
 *
 * <p>The gain is applied per sample and hard clipped, which keeps peaks from
 * wrapping around when a boost is larger than the available headroom.</p>
 */
@OptIn(markerClass = UnstableApi.class)
public final class GainAudioProcessor extends BaseAudioProcessor {
    private volatile float targetGain = 1f;
    /** Only touched on the audio thread. */
    private float currentGain = 1f;

    public void setGain(float gain) {
        this.targetGain = Math.max(0f, Math.min(4f, gain));
    }

    public float getGain() {
        return targetGain;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat inputAudioFormat) {
        int encoding = inputAudioFormat.encoding;
        if(encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT) {
            return inputAudioFormat;
        }
        // Unknown encodings pass through untouched.
        return AudioFormat.NOT_SET;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int size = inputBuffer.remaining();
        if(size == 0) return;

        float target = targetGain;
        // Small changes are automation and get ramped; large jumps are state
        // changes (muting a preloaded decoder) and take effect immediately.
        float start = Math.abs(target - currentGain) > 0.5f ? target : currentGain;
        ByteBuffer output = replaceOutputBuffer(size);
        output.order(ByteOrder.nativeOrder());

        if(inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
            FloatBuffer in = inputBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer();
            FloatBuffer out = output.asFloatBuffer();
            int samples = in.remaining();
            if(samples <= 0) {
                inputBuffer.position(inputBuffer.limit());
            } else if(Math.abs(start - 1f) < 0.0001f && Math.abs(target - 1f) < 0.0001f) {
                out.put(in);
                inputBuffer.position(inputBuffer.limit());
            } else {
                // Ramp across the buffer instead of stepping at its edges, so
                // automation has no zipper noise and no tick-alignment bias.
                float step = (target - start) / samples;
                float gain = start;
                while(in.hasRemaining()) {
                    out.put(in.get() * gain);
                    gain += step;
                }
                inputBuffer.position(inputBuffer.limit());
            }
        } else {
            ShortBuffer in = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer();
            ShortBuffer out = output.asShortBuffer();
            int samples = in.remaining();
            if(samples <= 0) {
                inputBuffer.position(inputBuffer.limit());
            } else if(Math.abs(start - 1f) < 0.0001f && Math.abs(target - 1f) < 0.0001f) {
                out.put(in);
                inputBuffer.position(inputBuffer.limit());
            } else {
                float step = (target - start) / samples;
                float gain = start;
                while(in.hasRemaining()) {
                    int value = Math.round(in.get() * gain);
                    out.put((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value)));
                    gain += step;
                }
                inputBuffer.position(inputBuffer.limit());
            }
        }
        currentGain = target;

        output.position(size);
        output.flip();
    }
}
