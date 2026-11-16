package com.proggerx.dcross2000.mix;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import com.proggerx.dcross2000.service.DownloadFile;

/**
 * Decodes just the part of a song the editor is showing and keeps a 10 ms peak
 * envelope for it. Decoding whole songs took tens of seconds on some devices,
 * while a six second transition window decodes almost instantly.
 */
public final class WaveformCache {
    private static final String TAG = WaveformCache.class.getSimpleName();

    /** Time resolution of the decoded envelope. */
    public static final int BUCKET_MS = 10;

    private static final int MAX_CACHE_BYTES = 4 * 1024 * 1024;
    private static final long DECODE_TIMEOUT_MS = 20000;

    private static final LruCache<String, Waveform> cache = new LruCache<String, Waveform>(MAX_CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Waveform value) {
            return value == null ? 1 : Math.max(1, value.low.length * 8);
        }
    };
    private static final Map<String, List<Callback>> pending = new ConcurrentHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static final ExecutorService executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "WaveformDecode");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }
    });

    public interface Callback {
        void onWaveform(Waveform waveform);
    }

    /**
     * A decoded envelope split into a low band (kick, bass) and a high band
     * (snare, hats, transients), plus the time resolution it was captured at.
     */
    public static final class Waveform {
        public final float[] low;
        public final float[] high;
        public final int bucketMs;
        public final int durationMs;

        public Waveform(float[] low, float[] high, int bucketMs, int durationMs) {
            this.low = low == null ? new float[0] : low;
            this.high = high == null ? new float[0] : high;
            this.bucketMs = Math.max(1, bucketMs);
            this.durationMs = Math.max(0, durationMs);
        }

        public boolean isEmpty() {
            return low.length == 0;
        }
    }

    private WaveformCache() {
    }

    /**
     * Decodes the envelope for [startMs, endMs) of the given song, calling back
     * on the main thread. Results are cached in memory and duplicate requests
     * share one decode.
     */
    public static void loadRange(final Context context, final DownloadFile file,
                                 final int startMs, final int endMs, final Callback callback) {
        if(file == null || !file.isCompleteFileAvailable() || endMs <= startMs) {
            callback.onWaveform(null);
            return;
        }

        final File source = file.getCompleteFile();
        final String id = file.getSong() == null ? null : file.getSong().getId();
        final int start = Math.max(0, startMs / BUCKET_MS * BUCKET_MS);
        final int end = Math.max(start + BUCKET_MS, (endMs + BUCKET_MS - 1) / BUCKET_MS * BUCKET_MS);
        final String key = (id == null ? source.getName() : id) + "|" + source.length()
                + "|" + start + "|" + end;

        Waveform cached = cache.get(key);
        if(cached != null) {
            callback.onWaveform(cached);
            return;
        }

        List<Callback> waiting = pending.get(key);
        if(waiting != null) {
            waiting.add(callback);
            return;
        }

        waiting = new ArrayList<>();
        waiting.add(callback);
        pending.put(key, waiting);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                Waveform waveform = null;
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                    waveform = decodeRange(source, start, end);
                    if(waveform != null) {
                        cache.put(key, waveform);
                    }
                } catch(Throwable error) {
                    Log.w(TAG, "Unable to decode waveform for " + source.getName(), error);
                }

                final Waveform result = waveform;
                List<Callback> callbacks = pending.remove(key);
                if(callbacks == null) return;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        for(Callback callback : callbacks) {
                            callback.onWaveform(result);
                        }
                    }
                });
            }
        });
    }

    /**
     * The raw high band is mostly a wall of cymbal noise. Subtracting a moving
     * average leaves the attacks, which is what the eye needs for beat
     * alignment.
     */
    private static void emphasiseOnsets(float[] values) {
        int count = values.length;
        if(count == 0) return;
        int radius = 8; // 160 ms at 10 ms buckets
        double[] prefix = new double[count + 1];
        for(int i = 0; i < count; i++) {
            prefix[i + 1] = prefix[i] + values[i];
        }
        float[] onsets = new float[count];
        float maximum = 0f;
        for(int i = 0; i < count; i++) {
            int from = Math.max(0, i - radius);
            int to = Math.min(count, i + radius + 1);
            float average = (float) ((prefix[to] - prefix[from]) / (to - from));
            float onset = Math.max(0f, values[i] - average);
            onsets[i] = onset;
            if(onset > maximum) maximum = onset;
        }
        if(maximum > 0f) {
            for(int i = 0; i < count; i++) {
                values[i] = Math.min(1f, onsets[i] / maximum);
            }
        }
    }

    static Waveform decodeRange(File source, int startMs, int endMs) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(source.getAbsolutePath());
        int track = -1;
        String mime = null;
        for(int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String candidate = format.getString(MediaFormat.KEY_MIME);
            if(candidate != null && candidate.startsWith("audio/")) {
                track = i;
                mime = candidate;
                break;
            }
        }
        if(track < 0 || mime == null) {
            extractor.release();
            return null;
        }
        extractor.selectTrack(track);
        MediaFormat format = extractor.getTrackFormat(track);
        int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) : 2;
        long durationMs = format.containsKey(MediaFormat.KEY_DURATION)
                ? format.getLong(MediaFormat.KEY_DURATION) / 1000L : -1L;

        int end = endMs;
        if(durationMs > 0 && end > durationMs) {
            end = (int) durationMs;
        }
        if(end <= startMs) {
            extractor.release();
            return null;
        }

        int buckets = Math.max(1, (end - startMs + BUCKET_MS - 1) / BUCKET_MS);
        float[] lowPeaks = new float[buckets];
        float[] highPeaks = new float[buckets];
        int framesPerBucket = Math.max(1, sampleRate * BUCKET_MS / 1000);
        long seekUs = startMs * 1000L;

        // One-pole crossover filters on the mono downmix.
        float alphaLow = 1f - (float) Math.exp(-2.0 * Math.PI * 200.0 / sampleRate);
        float alphaHigh = 1f - (float) Math.exp(-2.0 * Math.PI * 1500.0 / sampleRate);

        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();

        boolean inputDone = false;
        boolean outputDone = false;
        boolean sawOutput = false;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        long skip = -1L;
        int bucket = 0;
        int fill = 0;
        int channel = 0;
        float mono = 0f;
        float lowerState = 0f;
        float upperState = 0f;
        float lowPeak = 0f;
        float highPeak = 0f;
        long deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MS;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        try {
            extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            while(!outputDone && System.currentTimeMillis() < deadline) {
                boolean fed = false;
                while(!inputDone) {
                    int input = codec.dequeueInputBuffer(0);
                    if(input < 0) break;
                    ByteBuffer buffer = codec.getInputBuffer(input);
                    int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                    if(size < 0) {
                        codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(input, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                        fed = true;
                    }
                }

                int drained = 0;
                while(!outputDone) {
                    int output = codec.dequeueOutputBuffer(info, fed ? 0 : 3000);
                    if(output == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if(output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat outputFormat = codec.getOutputFormat();
                        if(outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                        }
                        continue;
                    }
                    if(output < 0) break;

                    ByteBuffer buffer = codec.getOutputBuffer(output);
                    if(buffer != null && info.size > 0) {
                        sawOutput = true;
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);

                        if(skip < 0L) {
                            // Time of the first sample after the seek.
                            long firstMs = info.presentationTimeUs / 1000L;
                            skip = Math.max(0L, startMs - firstMs) * sampleRate * channels / 1000L;
                        }

                        if(encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            FloatBuffer samples = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer();
                            float[] chunk = new float[samples.remaining()];
                            samples.get(chunk);
                            for(float sample : chunk) {
                                if(skip > 0L) {
                                    skip--;
                                    continue;
                                }
                                mono += sample;
                                if(++channel < channels) continue;
                                float value = mono / channels;
                                mono = 0f;
                                channel = 0;
                                lowerState += alphaLow * (value - lowerState);
                                upperState += alphaHigh * (value - upperState);
                                lowPeak = Math.max(lowPeak, Math.abs(lowerState));
                                highPeak = Math.max(highPeak, Math.abs(value - upperState));
                                if(++fill >= framesPerBucket) {
                                    lowPeaks[bucket] = Math.min(1f, lowPeak);
                                    highPeaks[bucket] = Math.min(1f, highPeak);
                                    bucket++;
                                    lowPeak = 0f;
                                    highPeak = 0f;
                                    fill = 0;
                                    if(bucket >= buckets) {
                                        outputDone = true;
                                        break;
                                    }
                                }
                            }
                        } else if(encoding == AudioFormat.ENCODING_PCM_8BIT) {
                            byte[] chunk = new byte[buffer.remaining()];
                            buffer.get(chunk);
                            for(byte sample : chunk) {
                                if(skip > 0L) {
                                    skip--;
                                    continue;
                                }
                                mono += ((sample & 0xFF) - 128) / 128f;
                                if(++channel < channels) continue;
                                float value = mono / channels;
                                mono = 0f;
                                channel = 0;
                                lowerState += alphaLow * (value - lowerState);
                                upperState += alphaHigh * (value - upperState);
                                lowPeak = Math.max(lowPeak, Math.abs(lowerState));
                                highPeak = Math.max(highPeak, Math.abs(value - upperState));
                                if(++fill >= framesPerBucket) {
                                    lowPeaks[bucket] = Math.min(1f, lowPeak);
                                    highPeaks[bucket] = Math.min(1f, highPeak);
                                    bucket++;
                                    lowPeak = 0f;
                                    highPeak = 0f;
                                    fill = 0;
                                    if(bucket >= buckets) {
                                        outputDone = true;
                                        break;
                                    }
                                }
                            }
                        } else {
                            ShortBuffer samples = buffer.order(ByteOrder.nativeOrder()).asShortBuffer();
                            short[] chunk = new short[samples.remaining()];
                            samples.get(chunk);
                            for(short sample : chunk) {
                                if(skip > 0L) {
                                    skip--;
                                    continue;
                                }
                                mono += sample / 32768f;
                                if(++channel < channels) continue;
                                float value = mono / channels;
                                mono = 0f;
                                channel = 0;
                                lowerState += alphaLow * (value - lowerState);
                                upperState += alphaHigh * (value - upperState);
                                lowPeak = Math.max(lowPeak, Math.abs(lowerState));
                                highPeak = Math.max(highPeak, Math.abs(value - upperState));
                                if(++fill >= framesPerBucket) {
                                    lowPeaks[bucket] = Math.min(1f, lowPeak);
                                    highPeaks[bucket] = Math.min(1f, highPeak);
                                    bucket++;
                                    lowPeak = 0f;
                                    highPeak = 0f;
                                    fill = 0;
                                    if(bucket >= buckets) {
                                        outputDone = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    codec.releaseOutputBuffer(output, false);
                    drained++;
                    if((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }

                if(!fed && drained == 0 && inputDone) {
                    try {
                        Thread.sleep(1);
                    } catch(InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        } finally {
            try {
                codec.stop();
            } catch(Exception ignored) {
            }
            codec.release();
            extractor.release();
        }

        if(!sawOutput) {
            return null;
        }
        if(fill > 0 && bucket < buckets) {
            lowPeaks[bucket] = Math.min(1f, lowPeak);
            highPeaks[bucket] = Math.min(1f, highPeak);
        }
        emphasiseOnsets(highPeaks);
        return new Waveform(lowPeaks, highPeaks, BUCKET_MS, end - startMs);
    }
}
