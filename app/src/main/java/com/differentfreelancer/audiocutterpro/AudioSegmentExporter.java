package com.differentfreelancer.audiocutterpro;

import android.content.ContentResolver;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.OutputStream;
import java.nio.ByteBuffer;

public final class AudioSegmentExporter {

    public interface ProgressListener {
        void onProgress(int current, int total, String fileName);
    }

    private AudioSegmentExporter() {}

    public static String detectExtension(Context context, Uri sourceUri) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, sourceUri, null);
            MediaFormat format = findAudioFormat(extractor);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if ("audio/mpeg".equals(mime)) return ".mp3";
            if ("audio/mp4a-latm".equals(mime) || "audio/aac".equals(mime)) return ".m4a";
            throw new IllegalArgumentException("Format audio belum didukung: " + mime + ". Gunakan MP3, MP4, atau M4A/AAC.");
        } finally {
            extractor.release();
        }
    }

    public static void exportAll(
            Context context,
            Uri sourceUri,
            Uri treeUri,
            long[] boundariesMs,
            String prefix,
            ProgressListener listener
    ) throws Exception {
        if (boundariesMs == null || boundariesMs.length < 2) {
            throw new IllegalArgumentException("Belum ada bagian audio untuk diexport.");
        }
        String ext = detectExtension(context, sourceUri);
        int total = boundariesMs.length - 1;

        for (int i = 0; i < total; i++) {
            String fileName = prefix + "_" + String.format("%02d", i + 1) + ext;
            if (listener != null) listener.onProgress(i + 1, total, fileName);
            long startUs = boundariesMs[i] * 1000L;
            long endUs = boundariesMs[i + 1] * 1000L;
            if (".mp3".equals(ext)) {
                exportMp3Segment(context, sourceUri, treeUri, fileName, startUs, endUs);
            } else {
                exportAacSegment(context, sourceUri, treeUri, fileName, startUs, endUs);
            }
        }
    }

    private static void exportMp3Segment(
            Context context,
            Uri sourceUri,
            Uri treeUri,
            String fileName,
            long startUs,
            long endUs
    ) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        Uri outputUri = null;
        try {
            extractor.setDataSource(context, sourceUri, null);
            int trackIndex = findAudioTrack(extractor);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (!"audio/mpeg".equals(mime)) {
                throw new IllegalArgumentException("Track audio bukan MP3.");
            }
            extractor.selectTrack(trackIndex);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            while (extractor.getSampleTime() >= 0 && extractor.getSampleTime() < startUs) {
                extractor.advance();
            }

            outputUri = createDocument(context.getContentResolver(), treeUri, "audio/mpeg", fileName);
            try (OutputStream out = context.getContentResolver().openOutputStream(outputUri, "w")) {
                if (out == null) throw new IllegalStateException("Tidak bisa membuka file output.");
                int maxSize = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                        ? Math.max(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 64 * 1024)
                        : 512 * 1024;
                ByteBuffer buffer = ByteBuffer.allocateDirect(maxSize);

                while (true) {
                    long sampleTime = extractor.getSampleTime();
                    if (sampleTime < 0 || sampleTime >= endUs) break;
                    buffer.clear();
                    int size = extractor.readSampleData(buffer, 0);
                    if (size < 0) break;
                    byte[] bytes = new byte[size];
                    buffer.position(0);
                    buffer.get(bytes, 0, size);
                    out.write(bytes);
                    extractor.advance();
                }
                out.flush();
            }
        } catch (Exception e) {
            if (outputUri != null) {
                try { context.getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) {}
            }
            throw e;
        } finally {
            extractor.release();
        }
    }

    private static void exportAacSegment(
            Context context,
            Uri sourceUri,
            Uri treeUri,
            String fileName,
            long startUs,
            long endUs
    ) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        ParcelFileDescriptor pfd = null;
        Uri outputUri = null;
        boolean muxerStarted = false;

        try {
            extractor.setDataSource(context, sourceUri, null);
            int trackIndex = findAudioTrack(extractor);
            MediaFormat format = extractor.getTrackFormat(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (!"audio/mp4a-latm".equals(mime) && !"audio/aac".equals(mime)) {
                throw new IllegalArgumentException("Track audio bukan AAC.");
            }

            extractor.selectTrack(trackIndex);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            while (extractor.getSampleTime() >= 0 && extractor.getSampleTime() < startUs) {
                extractor.advance();
            }

            outputUri = createDocument(context.getContentResolver(), treeUri, "audio/mp4", fileName);
            pfd = context.getContentResolver().openFileDescriptor(outputUri, "rw");
            if (pfd == null) throw new IllegalStateException("Tidak bisa membuka file output.");

            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outputTrack = muxer.addTrack(format);
            muxer.start();
            muxerStarted = true;

            int maxSize = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? Math.max(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 64 * 1024)
                    : 512 * 1024;
            ByteBuffer buffer = ByteBuffer.allocateDirect(maxSize);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long firstPts = -1;

            while (true) {
                long sampleTime = extractor.getSampleTime();
                if (sampleTime < 0 || sampleTime >= endUs) break;

                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;

                if (firstPts < 0) firstPts = sampleTime;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = Math.max(0, sampleTime - firstPts);
                info.flags = extractor.getSampleFlags();

                buffer.position(0);
                buffer.limit(size);
                muxer.writeSampleData(outputTrack, buffer, info);
                extractor.advance();
            }
        } catch (Exception e) {
            if (outputUri != null) {
                try { context.getContentResolver().delete(outputUri, null, null); } catch (Exception ignored) {}
            }
            throw e;
        } finally {
            if (muxer != null) {
                try {
                    if (muxerStarted) muxer.stop();
                } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
            extractor.release();
        }
    }

    private static int findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        throw new IllegalArgumentException("File tidak memiliki track audio.");
    }

    private static MediaFormat findAudioFormat(MediaExtractor extractor) {
        return extractor.getTrackFormat(findAudioTrack(extractor));
    }

    private static Uri createDocument(ContentResolver resolver, Uri treeUri, String mime, String name) throws Exception {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
        Uri out = DocumentsContract.createDocument(resolver, parent, mime, name);
        if (out == null) throw new IllegalStateException("Gagal membuat file " + name);
        return out;
    }
}
