package com.differentfreelancer.audiocutterpro;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Locale;

public final class PitchedWavExporter {

    public interface ProgressListener {
        void onProgress(int current, int total, String fileName);
    }

    private PitchedWavExporter() {}

    public static void exportAll(
            Context context,
            Uri sourceUri,
            Uri treeUri,
            long[] boundariesMs,
            String prefix,
            float semitones,
            ProgressListener listener
    ) throws Exception {
        if (boundariesMs == null || boundariesMs.length < 2) {
            throw new IllegalArgumentException("Belum ada bagian audio untuk diexport.");
        }

        int total = boundariesMs.length - 1;
        for (int i = 0; i < total; i++) {
            String fileName = prefix + "_" + String.format(Locale.US, "%02d", i + 1) + ".wav";
            if (listener != null) listener.onProgress(i + 1, total, fileName);

            long startUs = boundariesMs[i] * 1000L;
            long endUs = boundariesMs[i + 1] * 1000L;

            DecodedPcm pcm = decodeSegment(context, sourceUri, startUs, endUs);
            short[] processed = PitchShifter.shift(
                    pcm.samples,
                    pcm.channels,
                    semitones
            );
            writeWav(
                    context.getContentResolver(),
                    treeUri,
                    fileName,
                    processed,
                    pcm.sampleRate,
                    pcm.channels
            );
        }
    }

    private static DecodedPcm decodeSegment(
            Context context,
            Uri sourceUri,
            long startUs,
            long endUs
    ) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        ShortArrayBuilder collector = new ShortArrayBuilder();

        int sampleRate = 44100;
        int channels = 2;
        int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

        try {
            extractor.setDataSource(context, sourceUri, null);
            int trackIndex = findAudioTrack(extractor);
            MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IllegalArgumentException("Format audio tidak dikenal.");

            if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            }
            if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            }

            try {
                inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            } catch (Exception ignored) {}

            extractor.selectTrack(trackIndex);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!outputDone) {
                if (!inputDone) {
                    int inputIndex = decoder.dequeueInputBuffer(10_000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            long sampleTime = extractor.getSampleTime();

                            if (sampleTime < 0 || sampleTime > endUs + 500_000L) {
                                decoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                );
                                inputDone = true;
                            } else {
                                int size = extractor.readSampleData(inputBuffer, 0);
                                if (size < 0) {
                                    decoder.queueInputBuffer(
                                            inputIndex,
                                            0,
                                            0,
                                            0,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    );
                                    inputDone = true;
                                } else {
                                    decoder.queueInputBuffer(
                                            inputIndex,
                                            0,
                                            size,
                                            sampleTime,
                                            extractor.getSampleFlags()
                                    );
                                    extractor.advance();
                                }
                            }
                        }
                    }
                }

                int outputIndex = decoder.dequeueOutputBuffer(info, 10_000);

                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = decoder.getOutputFormat();
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    continue;
                }

                if (outputIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer out = decoder.getOutputBuffer(outputIndex);
                        if (out != null) {
                            appendClippedPcm(
                                    collector,
                                    out,
                                    info,
                                    sampleRate,
                                    channels,
                                    pcmEncoding,
                                    startUs,
                                    endUs
                            );
                        }
                    }

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            || info.presentationTimeUs >= endUs) {
                        outputDone = true;
                    }

                    decoder.releaseOutputBuffer(outputIndex, false);
                }
            }

            if (collector.size() == 0) {
                throw new IllegalStateException("Tidak ada PCM yang berhasil didecode pada bagian ini.");
            }

            int usable = collector.size() - (collector.size() % Math.max(1, channels));
            return new DecodedPcm(
                    Arrays.copyOf(collector.toArray(), usable),
                    sampleRate,
                    channels
            );
        } finally {
            extractor.release();
            if (decoder != null) {
                try { decoder.stop(); } catch (Exception ignored) {}
                try { decoder.release(); } catch (Exception ignored) {}
            }
        }
    }

    private static void appendClippedPcm(
            ShortArrayBuilder collector,
            ByteBuffer source,
            MediaCodec.BufferInfo info,
            int sampleRate,
            int channels,
            int pcmEncoding,
            long startUs,
            long endUs
    ) {
        if (channels <= 0 || sampleRate <= 0) return;

        int bytesPerSample = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
        int bytesPerFrame = bytesPerSample * channels;
        if (bytesPerFrame <= 0) return;

        int frameCount = info.size / bytesPerFrame;
        if (frameCount <= 0) return;

        long bufferStartUs = info.presentationTimeUs;
        long bufferDurationUs = (long) frameCount * 1_000_000L / sampleRate;
        long bufferEndUs = bufferStartUs + bufferDurationUs;

        if (bufferEndUs <= startUs || bufferStartUs >= endUs) return;

        int firstFrame = 0;
        int lastFrame = frameCount;

        if (startUs > bufferStartUs) {
            firstFrame = (int) Math.ceil((startUs - bufferStartUs) * sampleRate / 1_000_000.0);
        }
        if (endUs < bufferEndUs) {
            lastFrame = (int) Math.floor((endUs - bufferStartUs) * sampleRate / 1_000_000.0);
        }

        firstFrame = Math.max(0, Math.min(frameCount, firstFrame));
        lastFrame = Math.max(firstFrame, Math.min(frameCount, lastFrame));
        if (lastFrame <= firstFrame) return;

        ByteBuffer dup = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int byteStart = info.offset + firstFrame * bytesPerFrame;
        int byteEnd = info.offset + lastFrame * bytesPerFrame;

        byteStart = Math.max(info.offset, Math.min(info.offset + info.size, byteStart));
        byteEnd = Math.max(byteStart, Math.min(info.offset + info.size, byteEnd));

        dup.position(byteStart);
        dup.limit(byteEnd);
        ByteBuffer slice = dup.slice().order(ByteOrder.LITTLE_ENDIAN);

        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            FloatBuffer floats = slice.asFloatBuffer();
            while (floats.hasRemaining()) {
                float value = Math.max(-1f, Math.min(1f, floats.get()));
                collector.add((short) Math.round(value * 32767f));
            }
        } else {
            ShortBuffer shorts = slice.asShortBuffer();
            while (shorts.hasRemaining()) {
                collector.add(shorts.get());
            }
        }
    }

    private static void writeWav(
            ContentResolver resolver,
            Uri treeUri,
            String fileName,
            short[] samples,
            int sampleRate,
            int channels
    ) throws Exception {
        Uri outputUri = null;

        try {
            outputUri = createDocument(resolver, treeUri, "audio/wav", fileName);

            try (OutputStream out = resolver.openOutputStream(outputUri, "w")) {
                if (out == null) throw new IllegalStateException("Tidak bisa membuka file output.");

                int dataBytes = samples.length * 2;
                int byteRate = sampleRate * channels * 2;
                int blockAlign = channels * 2;

                writeAscii(out, "RIFF");
                writeIntLE(out, 36 + dataBytes);
                writeAscii(out, "WAVE");
                writeAscii(out, "fmt ");
                writeIntLE(out, 16);
                writeShortLE(out, 1);
                writeShortLE(out, channels);
                writeIntLE(out, sampleRate);
                writeIntLE(out, byteRate);
                writeShortLE(out, blockAlign);
                writeShortLE(out, 16);
                writeAscii(out, "data");
                writeIntLE(out, dataBytes);

                byte[] buffer = new byte[8192];
                int p = 0;
                for (short sample : samples) {
                    if (p + 2 > buffer.length) {
                        out.write(buffer, 0, p);
                        p = 0;
                    }
                    buffer[p++] = (byte) (sample & 0xFF);
                    buffer[p++] = (byte) ((sample >>> 8) & 0xFF);
                }
                if (p > 0) out.write(buffer, 0, p);
                out.flush();
            }
        } catch (Exception e) {
            if (outputUri != null) {
                try { resolver.delete(outputUri, null, null); } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        throw new IllegalArgumentException("File tidak memiliki track audio.");
    }

    private static Uri createDocument(
            ContentResolver resolver,
            Uri treeUri,
            String mime,
            String fileName
    ) throws Exception {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
        Uri out = DocumentsContract.createDocument(resolver, parent, mime, fileName);
        if (out == null) throw new IllegalStateException("Gagal membuat file " + fileName);
        return out;
    }

    private static void writeAscii(OutputStream out, String text) throws Exception {
        out.write(text.getBytes("US-ASCII"));
    }

    private static void writeIntLE(OutputStream out, int value) throws Exception {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeShortLE(OutputStream out, int value) throws Exception {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static final class DecodedPcm {
        final short[] samples;
        final int sampleRate;
        final int channels;

        DecodedPcm(short[] samples, int sampleRate, int channels) {
            this.samples = samples;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    private static final class ShortArrayBuilder {
        private short[] data = new short[32 * 1024];
        private int size = 0;

        void add(short value) {
            if (size >= data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = value;
        }

        int size() {
            return size;
        }

        short[] toArray() {
            return Arrays.copyOf(data, size);
        }
    }

    private static final class PitchShifter {
        private static final int FFT_SIZE = 1024;
        private static final int ANALYSIS_HOP = 256;
        private static final double TWO_PI = Math.PI * 2.0;

        static short[] shift(short[] interleaved, int channels, float semitones) {
            if (interleaved == null || interleaved.length == 0 || channels <= 0) {
                return interleaved;
            }

            if (Math.abs(semitones) < 0.001f) {
                return interleaved.clone();
            }

            int frames = interleaved.length / channels;
            float ratio = (float) Math.pow(2.0, semitones / 12.0);
            short[] output = new short[frames * channels];

            for (int ch = 0; ch < channels; ch++) {
                float[] mono = new float[frames];
                for (int i = 0; i < frames; i++) {
                    mono[i] = interleaved[i * channels + ch] / 32768f;
                }

                float[] shifted = shiftChannel(mono, ratio);
                for (int i = 0; i < frames; i++) {
                    float value = i < shifted.length ? shifted[i] : 0f;
                    value = Math.max(-1f, Math.min(1f, value));
                    output[i * channels + ch] = (short) Math.round(value * 32767f);
                }
            }

            return output;
        }

        private static float[] shiftChannel(float[] input, float ratio) {
            if (input.length < 64) return input.clone();

            float[] stretched = timeStretch(input, ratio);
            float[] result = new float[input.length];

            if (stretched.length == 0) return result;

            double step = ratio;
            for (int i = 0; i < result.length; i++) {
                double sourcePos = i * step;
                int a = (int) Math.floor(sourcePos);
                double frac = sourcePos - a;

                if (a < 0) {
                    result[i] = stretched[0];
                } else if (a >= stretched.length - 1) {
                    result[i] = stretched[stretched.length - 1];
                } else {
                    result[i] = (float) (
                            stretched[a] * (1.0 - frac)
                                    + stretched[a + 1] * frac
                    );
                }
            }

            return result;
        }

        private static float[] timeStretch(float[] input, float stretch) {
            int n = FFT_SIZE;
            int hopIn = ANALYSIS_HOP;
            int hopOut = Math.max(1, Math.round(hopIn * stretch));
            int bins = n / 2 + 1;

            int frameCount;
            if (input.length <= n) {
                frameCount = 1;
            } else {
                frameCount = 1 + (int) Math.ceil((input.length - n) / (double) hopIn);
            }

            int outLength = hopOut * Math.max(0, frameCount - 1) + n;
            float[] output = new float[outLength];
            float[] norm = new float[outLength];

            double[] window = new double[n];
            for (int i = 0; i < n; i++) {
                window[i] = 0.5 - 0.5 * Math.cos(TWO_PI * i / n);
            }

            double[] prevPhase = new double[bins];
            double[] synthPhase = new double[bins];
            boolean firstFrame = true;

            double[] real = new double[n];
            double[] imag = new double[n];

            for (int frame = 0; frame < frameCount; frame++) {
                int inPos = frame * hopIn;
                int outPos = frame * hopOut;

                Arrays.fill(real, 0.0);
                Arrays.fill(imag, 0.0);

                for (int i = 0; i < n; i++) {
                    int src = inPos + i;
                    double sample = src < input.length ? input[src] : 0.0;
                    real[i] = sample * window[i];
                }

                fft(real, imag, false);

                double[] magnitude = new double[bins];
                double[] phase = new double[bins];

                for (int k = 0; k < bins; k++) {
                    magnitude[k] = Math.hypot(real[k], imag[k]);
                    phase[k] = Math.atan2(imag[k], real[k]);

                    if (firstFrame) {
                        prevPhase[k] = phase[k];
                        synthPhase[k] = phase[k];
                    } else {
                        double expected = TWO_PI * k * hopIn / n;
                        double delta = phase[k] - prevPhase[k] - expected;
                        delta = principalArgument(delta);

                        double trueOmega = TWO_PI * k / n + delta / hopIn;
                        synthPhase[k] += trueOmega * hopOut;
                        prevPhase[k] = phase[k];
                    }
                }

                firstFrame = false;

                Arrays.fill(real, 0.0);
                Arrays.fill(imag, 0.0);

                for (int k = 0; k < bins; k++) {
                    real[k] = magnitude[k] * Math.cos(synthPhase[k]);
                    imag[k] = magnitude[k] * Math.sin(synthPhase[k]);
                }

                for (int k = 1; k < n / 2; k++) {
                    real[n - k] = real[k];
                    imag[n - k] = -imag[k];
                }

                fft(real, imag, true);

                for (int i = 0; i < n; i++) {
                    int dst = outPos + i;
                    if (dst >= output.length) break;

                    float w = (float) window[i];
                    output[dst] += (float) (real[i] * window[i]);
                    norm[dst] += w * w;
                }
            }

            for (int i = 0; i < output.length; i++) {
                if (norm[i] > 1e-7f) {
                    output[i] /= norm[i];
                }
            }

            int expectedLength = Math.max(1, Math.round(input.length * stretch));
            if (output.length == expectedLength) return output;

            return resizeByLinearSampling(output, expectedLength);
        }

        private static float[] resizeByLinearSampling(float[] input, int targetLength) {
            if (targetLength <= 0) return new float[0];
            if (input.length == targetLength) return input;
            if (input.length == 1) {
                float[] out = new float[targetLength];
                Arrays.fill(out, input[0]);
                return out;
            }

            float[] out = new float[targetLength];
            double scale = (input.length - 1.0) / Math.max(1, targetLength - 1);

            for (int i = 0; i < targetLength; i++) {
                double pos = i * scale;
                int a = (int) Math.floor(pos);
                int b = Math.min(input.length - 1, a + 1);
                double frac = pos - a;
                out[i] = (float) (input[a] * (1.0 - frac) + input[b] * frac);
            }

            return out;
        }

        private static double principalArgument(double value) {
            while (value > Math.PI) value -= TWO_PI;
            while (value < -Math.PI) value += TWO_PI;
            return value;
        }

        private static void fft(double[] real, double[] imag, boolean inverse) {
            int n = real.length;

            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) {
                    j ^= bit;
                }
                j ^= bit;

                if (i < j) {
                    double tr = real[i];
                    real[i] = real[j];
                    real[j] = tr;

                    double ti = imag[i];
                    imag[i] = imag[j];
                    imag[j] = ti;
                }
            }

            for (int len = 2; len <= n; len <<= 1) {
                double angle = TWO_PI / len * (inverse ? 1.0 : -1.0);
                double wLenR = Math.cos(angle);
                double wLenI = Math.sin(angle);

                for (int i = 0; i < n; i += len) {
                    double wr = 1.0;
                    double wi = 0.0;

                    for (int j = 0; j < len / 2; j++) {
                        int u = i + j;
                        int v = i + j + len / 2;

                        double vr = real[v] * wr - imag[v] * wi;
                        double vi = real[v] * wi + imag[v] * wr;

                        double ur = real[u];
                        double ui = imag[u];

                        real[u] = ur + vr;
                        imag[u] = ui + vi;
                        real[v] = ur - vr;
                        imag[v] = ui - vi;

                        double nextWr = wr * wLenR - wi * wLenI;
                        wi = wr * wLenI + wi * wLenR;
                        wr = nextWr;
                    }
                }
            }

            if (inverse) {
                for (int i = 0; i < n; i++) {
                    real[i] /= n;
                    imag[i] /= n;
                }
            }
        }
    }
}
