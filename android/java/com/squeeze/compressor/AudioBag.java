package com.squeeze.compressor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds a compressed audio track until the muxer knows both of its tracks.
 *
 * Encoded samples are spilled to a temp file with a small in-memory index, so even
 * a three-hour soundtrack costs almost no heap. AAC tracks are copied bit-for-bit;
 * anything else (Vorbis, Opus, MP3, FLAC, PCM) is decoded and re-encoded to AAC.
 */
final class AudioBag implements AutoCloseable {

    private static final int TIMEOUT = 10000;

    private final File file;
    private final RandomAccessFile raf;
    private final List<long[]> index = new ArrayList<>(); // {ptsUs, flags, size}
    private byte[] scratch = new byte[0];
    MediaFormat fmt;
    int count;

    private AudioBag(File dir) throws Exception {
        this.file = File.createTempFile("sqa", ".bin", dir);
        this.raf = new RandomAccessFile(file, "rw");
    }

    private void put(ByteBuffer src, int offset, int size, long pts, int flags) throws Exception {
        if (size <= 0) return;
        ByteBuffer dup = src.duplicate();
        int end = Math.min(dup.capacity(), offset + size);
        if (offset > dup.limit()) dup.position(dup.limit());
        else dup.position(offset);
        dup.limit(Math.max(dup.position(), Math.min(end, dup.limit())));
        int n = dup.remaining();
        // One scratch array for every sample in the track, grown when needed — a
        // long clip used to allocate a byte[] per AAC frame just to write it out.
        if (n > scratch.length) scratch = new byte[Math.max(n, Math.max(1 << 12, scratch.length * 2))];
        dup.get(scratch, 0, n);
        raf.write(scratch, 0, n);
        index.add(new long[]{pts, flags, n});
        count++;
    }

    void play(MediaMuxer mux, int track) throws Exception {
        raf.seek(0);
        byte[] chunk = new byte[1 << 16];
        // One buffer and one BufferInfo for the whole track, not one per sample.
        ByteBuffer bb = null;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        for (long[] e : index) {
            int size = (int) e[2];
            if (bb == null || bb.capacity() < size) bb = ByteBuffer.allocateDirect(size);
            bb.clear();
            int read = 0;
            while (read < size) {
                int n = raf.read(chunk, 0, Math.min(chunk.length, size - read));
                if (n < 0) break;
                bb.put(chunk, 0, n);
                read += n;
            }
            bb.position(0);
            bb.limit(read);
            bi.set(0, read, e[0], (int) e[1]);
            mux.writeSampleData(track, bb, bi);
        }
    }

    public void close() {
        try { raf.close(); } catch (Exception ignored) { }
        file.delete();
    }

    static boolean isAac(MediaFormat af) {
        String m = af == null ? null : af.getString(MediaFormat.KEY_MIME);
        return m != null && m.equals("audio/mp4a-latm");
    }

    /** Copy the encoded samples out of the container untouched. */
    static AudioBag passthrough(MediaExtractor ex, int aIdx, MediaFormat af, File dir,
                                Engine.Progress p, float from, float to) throws Exception {
        AudioBag bag = new AudioBag(dir);
        int max = af.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                ? af.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 256 * 1024;
        max = Math.max(max, 128 * 1024);
        ByteBuffer buf = ByteBuffer.allocateDirect(max);
        long dur = af.containsKey(MediaFormat.KEY_DURATION) ? af.getLong(MediaFormat.KEY_DURATION) : 0;

        ex.selectTrack(aIdx);
        while (true) {
            buf.clear();
            int sz = ex.readSampleData(buf, 0);
            if (sz < 0) break;
            bag.put(buf, 0, sz, ex.getSampleTime(), 0);
            long t = ex.getSampleTime();
            if (p != null && dur > 0) {
                p.on(from + (to - from) * Math.min(1f, t / (float) dur), 0);
            }
            ex.advance();
        }
        bag.fmt = af;
        return bag;
    }

    /** Decode to PCM and re-encode as AAC at the requested bitrate. */
    static AudioBag transcode(Context ctx, MediaExtractor ex, int aIdx, MediaFormat af, int bitrate,
                              File dir, Engine.Progress p, float from, float to) throws Exception {
        String mime = af.getString(MediaFormat.KEY_MIME);
        int sr = af.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? af.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int ch = af.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? af.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
        long dur = af.containsKey(MediaFormat.KEY_DURATION) ? af.getLong(MediaFormat.KEY_DURATION) : 0;

        AudioBag bag = new AudioBag(dir);

        MediaFormat ef = MediaFormat.createAudioFormat("audio/mp4a-latm", sr, ch);
        ef.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(32000, bitrate));
        ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);

        MediaCodec dec = MediaCodec.createDecoderByType(mime);
        dec.configure(af, null, null, 0);
        MediaCodec enc = MediaCodec.createEncoderByType("audio/mp4a-latm");
        enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        dec.start();
        enc.start();

        ex.selectTrack(aIdx);

        boolean inputDone = false, decDone = false, encDone = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long lastPts = 0;

        while (!encDone) {
            if (!inputDone) {
                int i = dec.dequeueInputBuffer(TIMEOUT);
                if (i >= 0) {
                    ByteBuffer ib = dec.getInputBuffer(i);
                    int sz = ex.readSampleData(ib, 0);
                    if (sz < 0) {
                        dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        dec.queueInputBuffer(i, 0, sz, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }

            if (!decDone) {
                int o = dec.dequeueOutputBuffer(info, TIMEOUT);
                if (o >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(o);
                    if (info.size > 0 && ob != null) {
                        int ei = enc.dequeueInputBuffer(TIMEOUT);
                        if (ei >= 0) {
                            ByteBuffer eib = enc.getInputBuffer(ei);
                            eib.clear();
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            eib.put(ob);
                            enc.queueInputBuffer(ei, 0, info.size, info.presentationTimeUs, 0);
                        }
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    dec.releaseOutputBuffer(o, false);
                    if (eos) {
                        decDone = true;
                        enc.signalEndOfInputStream();
                    }
                }
            }

            int eo = enc.dequeueOutputBuffer(info, TIMEOUT);
            if (eo == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                bag.fmt = enc.getOutputFormat();
            } else if (eo >= 0) {
                ByteBuffer eb = enc.getOutputBuffer(eo);
                if (info.size > 0 && eb != null && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    bag.put(eb, info.offset, info.size, info.presentationTimeUs, 0);
                    lastPts = info.presentationTimeUs;
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                enc.releaseOutputBuffer(eo, false);
                if (eos) encDone = true;
            }

            if (p != null && dur > 0) {
                p.on(from + (to - from) * Math.min(1f, lastPts / (float) dur), 0);
            }
        }

        try { dec.stop(); } catch (Exception ignored) { }
        dec.release();
        try { enc.stop(); } catch (Exception ignored) { }
        enc.release();

        if (bag.fmt == null) {
            bag.close();
            throw new IllegalStateException("No audio track produced");
        }
        return bag;
    }
}
