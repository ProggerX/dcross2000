package com.proggerx.dcross2000.mix;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.proggerx.dcross2000.service.DownloadFile;

/**
 * Decodes a cached song once and keeps a small peak envelope for the editor.
 * Peaks are cached next to the app cache, keyed by song id and file revision.
 */
public final class WaveformCache {
    private static final String TAG = WaveformCache.class.getSimpleName();

    public static final int BUCKETS = 512;

    private static final int FRAME_SAMPLES = 1024;
    private static final long DECODE_TIMEOUT_MS = 25000;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onPeaks(float[] peaks);
    }

    private WaveformCache() {
    }

    public static void load(final Context context, final DownloadFile file, final Callback callback) {
        if(file == null || !file.isCompleteFileAvailable()) {
            callback.onPeaks(null);
            return;
        }
        final File source = file.getCompleteFile();
        final File cache = cacheFile(context, file, source);
        if(cache != null && cache.isFile()) {
            final float[] cached = read(cache);
            if(cached != null) {
                callback.onPeaks(cached);
                return;
            }
        }
        executor.execute(() -> {
            float[] peaks = null;
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                peaks = generate(source);
                if(peaks != null && cache != null) {
                    write(cache, peaks);
                }
            } catch(Throwable error) {
                Log.w(TAG, "Unable to decode waveform for " + source.getName(), error);
            }
            final float[] result = peaks;
            main.post(() -> callback.onPeaks(result));
        });
    }

    private static File cacheFile(Context context, DownloadFile file, File source) {
        String id = file.getSong() == null ? null : file.getSong().getId();
        if(id == null) return null;
        StringBuilder name = new StringBuilder();
        for(int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            name.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        File directory = new File(context.getCacheDir(), "waveform");
        if(!directory.isDirectory() && !directory.mkdirs()) return null;
        return new File(directory, name + "-" + source.length() + "-" + source.lastModified() + ".wfm");
    }

    private static float[] read(File cache) {
        try (DataInputStream in = new DataInputStream(new FileInputStream(cache))) {
            int count = in.readInt();
            if(count != BUCKETS) return null;
            float[] peaks = new float[count];
            for(int i = 0; i < count; i++) {
                peaks[i] = in.readFloat();
            }
            return peaks;
        } catch(Exception e) {
            return null;
        }
    }

    private static void write(File cache, float[] peaks) {
        File temporary = new File(cache.getParentFile(), cache.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(temporary))) {
            out.writeInt(peaks.length);
            for(float peak : peaks) {
                out.writeFloat(peak);
            }
            out.flush();
            if(!temporary.renameTo(cache)) {
                temporary.delete();
            }
        } catch(Exception e) {
            temporary.delete();
        }
    }

    static float[] generate(File source) throws Exception {
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
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();

        float[] frames = new float[4096];
        int frameCount = 0;
        int frameFill = 0;
        float framePeak = 0f;
        boolean sawOutput = false;
        boolean inputDone = false;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        long deadline = System.currentTimeMillis() + DECODE_TIMEOUT_MS;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while(System.currentTimeMillis() < deadline) {
                if(!inputDone) {
                    int input = codec.dequeueInputBuffer(10000);
                    if(input >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(input);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if(size < 0) {
                            codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(input, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int output = codec.dequeueOutputBuffer(info, 10000);
                if(output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    if(outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                } else if(output >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(output);
                    if(buffer != null && info.size > 0) {
                        sawOutput = true;
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        if(encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            FloatBuffer samples = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer();
                            while(samples.hasRemaining()) {
                                float value = Math.abs(samples.get());
                                framePeak = Math.max(framePeak, value);
                                if(++frameFill >= FRAME_SAMPLES) {
                                    if(frameCount == frames.length) frames = Arrays.copyOf(frames, frames.length * 2);
                                    frames[frameCount++] = framePeak;
                                    framePeak = 0f;
                                    frameFill = 0;
                                }
                            }
                        } else {
                            ShortBuffer samples = buffer.order(ByteOrder.nativeOrder()).asShortBuffer();
                            while(samples.hasRemaining()) {
                                float value = Math.abs(samples.get()) / 32768f;
                                framePeak = Math.max(framePeak, value);
                                if(++frameFill >= FRAME_SAMPLES) {
                                    if(frameCount == frames.length) frames = Arrays.copyOf(frames, frames.length * 2);
                                    frames[frameCount++] = framePeak;
                                    framePeak = 0f;
                                    frameFill = 0;
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(output, false);
                    if((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
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

        if(!sawOutput || frameCount == 0) {
            return null;
        }
        if(frameFill > 0) {
            if(frameCount == frames.length) frames = Arrays.copyOf(frames, frames.length * 2);
            frames[frameCount++] = framePeak;
        }
        return resample(frames, frameCount);
    }

    private static float[] resample(float[] frames, int count) {
        float[] peaks = new float[BUCKETS];
        float maximum = 0f;
        for(int i = 0; i < BUCKETS; i++) {
            int start = (int) ((long) i * count / BUCKETS);
            int end = (int) Math.max(start + 1, (long) (i + 1) * count / BUCKETS);
            float peak = 0f;
            for(int j = start; j < end && j < count; j++) {
                peak = Math.max(peak, frames[j]);
            }
            peaks[i] = peak;
            maximum = Math.max(maximum, peak);
        }
        if(maximum > 0.0001f) {
            for(int i = 0; i < BUCKETS; i++) {
                peaks[i] = Math.min(1f, peaks[i] / maximum);
            }
        }
        return peaks;
    }

    /** Slices [startFraction, endFraction) out of a peak array. */
    public static float[] slice(float[] peaks, float startFraction, float endFraction) {
        if(peaks == null) return null;
        int start = Math.max(0, Math.min(peaks.length - 1, Math.round(startFraction * peaks.length)));
        int end = Math.max(start + 1, Math.min(peaks.length, Math.round(endFraction * peaks.length)));
        return Arrays.copyOfRange(peaks, start, end);
    }
}
