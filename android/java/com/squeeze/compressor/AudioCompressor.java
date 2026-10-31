package com.squeeze.compressor;

import android.content.Context;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;

/**
 * Audio-only compression. Keeps the track untouched when it already fits, otherwise
 * re-encodes to AAC at whatever bitrate the target allows.
 */
final class AudioCompressor {

    static Engine.Result run(Context ctx, Engine.Job job, Engine.Progress p) throws Exception {
        VideoCompressor.Src src = VideoCompressor.Src.open(ctx, job.uri);
        File tmp = null;
        AudioBag bag = null;
        try {
            if (src.aIdx < 0 || src.af == null) return null;
            MediaFormat af = src.af;

            if ("lossless".equals(job.mode)) {
                return Engine.copyRaw(ctx, job, p, "Lossless — original samples preserved");
            }
            if (job.size > 0 && job.size <= job.target) {
                return Engine.copyRaw(ctx, job, p, "Already under target");
            }

            long durUs = VideoCompressor.longOr(af, MediaFormat.KEY_DURATION, 0);
            int srcRate = VideoCompressor.intOr(af, MediaFormat.KEY_BIT_RATE, 192000);
            if (durUs <= 0) durUs = VideoCompressor.duration(af, job.size, srcRate);

            double secs = Math.max(0.5, durUs / 1_000_000.0);
            long rate = (long) (job.target * 8 * 0.96 / secs);
            rate = Math.min(rate, srcRate);
            rate = Math.max(24000, Math.min(rate, 320000));

            boolean passthrough = AudioBag.isAac(af) && rate >= srcRate;

            if (passthrough) {
                bag = AudioBag.passthrough(src.ex, src.aIdx, af, Engine.workDir(ctx), p, 0.05f, 0.7f);
            } else {
                bag = AudioBag.transcode(ctx, src.ex, src.aIdx, af, (int) rate,
                        Engine.workDir(ctx), p, 0.05f, 0.7f);
            }
            if (bag == null || bag.count == 0 || bag.fmt == null) {
                return Engine.fail("No audio samples could be read from this file");
            }

            tmp = Engine.tempFile(ctx, ".m4a");
            MediaMuxer mux = new MediaMuxer(tmp.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = mux.addTrack(bag.fmt);
            mux.start();
            bag.play(mux, track);
            mux.stop();
            mux.release();
            bag.close();
            bag = null;

            if (tmp.length() <= 0) return Engine.fail("Audio re-encode produced an empty file");
            if (job.size > 0 && tmp.length() >= job.size) {
                tmp.delete();
                return Engine.copyRaw(ctx, job, p, "Already optimal at this bitrate");
            }

            String outName = Engine.base(job.name) + "_squeezed.m4a";
            Engine.Result r = new Engine.Result();
            Engine.publishTemp(ctx, job, tmp, outName, "audio/mp4", p, r);
            r.codec = passthrough ? "AAC (copied)" : "AAC";
            r.note = passthrough ? "Audio copied bit-for-bit"
                    : "AAC " + (rate / 1000) + " kbps";
            if (tmp.length() > job.target) {
                r.note = "Smallest reachable is " + Engine.mb(tmp.length());
            }
            tmp.delete();
            return r;
        } finally {
            if (bag != null) bag.close();
            if (tmp != null && tmp.exists()) tmp.delete();
            src.close();
        }
    }
}
