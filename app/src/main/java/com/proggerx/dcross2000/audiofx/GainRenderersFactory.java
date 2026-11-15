package com.proggerx.dcross2000.audiofx;

import android.content.Context;

import androidx.annotation.OptIn;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

/** Puts a {@link GainAudioProcessor} into the audio chain of one ExoPlayer. */
@OptIn(markerClass = UnstableApi.class)
public final class GainRenderersFactory extends DefaultRenderersFactory {
    private final AudioProcessor[] processors;

    public GainRenderersFactory(Context context, AudioProcessor processor) {
        super(context);
        processors = new AudioProcessor[]{processor};
    }

    @Override
    protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                       boolean enableAudioTrackPlaybackParams) {
        return new DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                .setAudioProcessors(processors)
                .build();
    }
}
