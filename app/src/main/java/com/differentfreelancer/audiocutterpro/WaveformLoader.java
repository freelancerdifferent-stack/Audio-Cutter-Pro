package com.differentfreelancer.audiocutterpro;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

public final class WaveformLoader {
    public static final class Result {
        public final float[] amplitudes;
        public final long durationMs;

        public Result(float[] amplitudes, long durationMs) {
            this.amplitudes = amplitudes;
            this.durationMs = durationMs;
        }
    }

    private WaveformLoader() {}

    public static Result load(Context context, Uri uri, int bins) throws Exception {
        bins = Math.max(200, Math.min(4000, bins));
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, uri, null);
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }
            if (trackIndex < 0 || format == null) {
                throw new IllegalArgumentException("Tidak ada track audio di file ini.");
            }

            long durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION)
                    : getDurationUsFallback(context, uri);
            if (durationUs <= 0) durationUs = 1;

            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IllegalArgumentException("Format audio tidak dikenal.");

            extractor.selectTrack(trackIndex);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            float[] peaks = new float[bins];
            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer in = codec.getInputBuffer(inIndex);
                        if (in != null) {
                            in.clear();
                            int size = extractor.readSampleData(in, 0);
                            long pts = extractor.getSampleTime();
                            if (size < 0 || pts < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inIndex, 0, size, pts, extractor.getSampleFlags());
                                extractor.advance();
                            }
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer out = codec.getOutputBuffer(outIndex);
                        if (out != null) {
                            ByteBuffer dup = out.duplicate();
                            dup.position(info.offset);
                            dup.limit(info.offset + info.size);
                            ShortBuffer shorts = dup.slice().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();

                            int bin = (int) Math.min(bins - 1,
                                    Math.max(0, (info.presentationTimeUs * bins) / durationUs));
                            int max = 0;
                            while (shorts.hasRemaining()) {
                                int v = Math.abs((int) shorts.get());
                                if (v > max) max = v;
                            }
                            float amp = Math.min(1f, max / 32768f);
                            if (amp > peaks[bin]) peaks[bin] = amp;
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIndex, false);
                }
            }

            smooth(peaks);
            return new Result(peaks, durationUs / 1000L);
        } catch (Exception decodeError) {
            try {
                return fallbackCompressedEnvelope(context, uri, bins);
            } catch (Exception ignored) {
                throw decodeError;
            }
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) {}
                try { codec.release(); } catch (Exception ignored) {}
            }
        }
    }

    private static Result fallbackCompressedEnvelope(Context context, Uri uri, int bins) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(context, uri, null);
            int track = -1;
            MediaFormat f = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat candidate = ex.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    f = candidate;
                    break;
                }
            }
            if (track < 0 || f == null) throw new IllegalArgumentException("Tidak ada track audio.");
            ex.selectTrack(track);

            long durationUs = f.containsKey(MediaFormat.KEY_DURATION)
                    ? f.getLong(MediaFormat.KEY_DURATION)
                    : getDurationUsFallback(context, uri);
            if (durationUs <= 0) durationUs = 1;

            int maxInput = f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 512 * 1024;
            ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(maxInput, 64 * 1024));
            float[] values = new float[bins];
            int maxSeen = 1;

            while (true) {
                long timeUs = ex.getSampleTime();
                if (timeUs < 0) break;
                buffer.clear();
                int size = ex.readSampleData(buffer, 0);
                if (size < 0) break;
                maxSeen = Math.max(maxSeen, size);
                int bin = (int) Math.min(bins - 1, Math.max(0, (timeUs * bins) / durationUs));
                values[bin] = Math.max(values[bin], size);
                ex.advance();
            }
            for (int i = 0; i < values.length; i++) {
                values[i] = Math.min(1f, values[i] / maxSeen);
            }
            smooth(values);
            return new Result(values, durationUs / 1000L);
        } finally {
            ex.release();
        }
    }

    private static void smooth(float[] a) {
        if (a.length < 3) return;
        float[] copy = a.clone();
        for (int i = 1; i < a.length - 1; i++) {
            a[i] = Math.max(copy[i], (copy[i - 1] + copy[i] + copy[i + 1]) / 3f);
        }
    }

    private static long getDurationUsFallback(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return ms == null ? 0 : Long.parseLong(ms) * 1000L;
        } catch (Exception e) {
            return 0;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }
}
