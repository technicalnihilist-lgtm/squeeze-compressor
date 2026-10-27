package com.squeeze.compressor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;

import java.io.File;

/**
 * Audio-only compression. Keeps the audio track when it already fits, otherwise
 * re-encodes to AAC at whatever bitrate the size target allows.
 */
final class AudioCompressor {

    static Engine.Result run(Context ctx, Engine.Job job, Engine.Progress p) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, job.uri, null);
            int aIdx = -1;
            MediaFormat af = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("audio/")) {
                    aIdx = i;
                    af = f;
                    break;
                }
            }
            if (aIdx < 0 || af == null) return null;

            // Lossless mode never touches the samples.
            if ("lossless".equals(job.mode)) {
                return Engine.copyRaw(ctx, job, p, "Lossless — original samples preserved");
            }
            if (job.size > 0 && job.size <= job.target) {
                return Engine.copyRaw(ctx, job, p, "Already under target");
            }

            long durUs = af.containsKey(MediaFormat.KEY_DURATION) ? af.getLong(MediaFormat.KEY_DURATION) : 0;
            int srcRate = af.containsKey(MediaFormat.KEY_BIT_RATE) ? af.getInteger(MediaFormat.KEY_BIT_RATE) : 192000;
            int rate = srcRate;
            if (durUs > 0) {
                long budget = (long) (job.target * 8 * 0.96);
                rate = (int) (budget / Math.max(1.0, durUs / 1_000_000.0));
            }
            rate = Math.min(rate, srcRate);
            rate = Math.max(48000, Math.min(rate, 256000));

            String outName = Engine.base(job.name) + "_squeezed.m4a";
            Engine.Sink sink = Engine.Sink.create(ctx, outName, "audio/mp4");
            AudioBag bag = null;
            try {
                File dir = ctx.getCacheDir();
                if (AudioBag.isAac(af) && rate >= srcRate) {
                    bag = AudioBag.passthrough(ex, aIdx, af, dir, p, 0.05f, 0.7f);
                } else {
                    bag = AudioBag.transcode(ctx, ex, aIdx, af, rate, dir, p, 0.05f, 0.7f);
                }
                if (bag.count == 0 || bag.fmt == null) {
                    sink.delete();
                    if (bag != null) bag.close();
                    return null;
                }
                MediaMuxer mux = new MediaMuxer(sink.fd().getFileDescriptor(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int track = mux.addTrack(bag.fmt);
                mux.start();
                bag.play(mux, track);
                mux.stop();
                mux.release();
                if (p != null) p.on(0.8f, 0);
                bag.close();
                bag = null;
                sink.publish();

                long out = sink.size();
                if (job.size > 0 && out >= job.size) {
                    sink.delete();
                    return Engine.copyRaw(ctx, job, p, "Already optimal at this bitrate");
                }
                Engine.Result r = new Engine.Result();
                r.ok = true;
                r.out = out;
                r.name = outName;
                r.uri = sink.uri;
                r.note = "AAC " + (rate / 1000) + " kbps";
                return r;
            } catch (Exception e) {
                sink.delete();
                if (bag != null) bag.close();
                return null;
            }
        } finally {
            ex.release();
        }
    }
}
