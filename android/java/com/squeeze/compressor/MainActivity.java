package com.squeeze.compressor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Thin native shell around the Squeeze UI. The HTML layer owns the look; this class
 * owns file access (SAF), the compression workers and the output folder.
 *
 * Two lanes, on purpose. Photos and archives run on a small pool so a batch of forty
 * JPEGs does not queue up behind a two-minute video; video and audio stay on a single
 * thread because MediaCodec instances are a device-wide resource and running two
 * transcodes at once makes both of them slower and occasionally broken. The lanes run
 * at the same time, which is the whole point: a video transcode plus a photo batch
 * finishes in roughly the time of the slower one, not the sum.
 *
 * Cancel is cooperative all the way down: the worker stops between files, the codec
 * loops call {@link Engine#checkCancel()} between frames, half-written scratch files
 * are deleted and every unfinished file goes back into the queue for a retry.
 *
 * Files can arrive three ways: the in-app picker, a share sheet (ACTION_SEND) or a
 * "open with" tap from another app (ACTION_VIEW). The queue is kept in preferences so
 * reopening the app does not lose it.
 */
public class MainActivity extends Activity {

    private static final int PICK = 1001;

    /** Progress events closer together than this are dropped; the bar does not need 200 fps. */
    private static final long PROGRESS_MS = 160;

    private static final String PREFS = "squeeze";
    private static final String KEY_QUEUE = "queue";

    private WebView web;
    private volatile String config = "{}";
    private volatile boolean cancelled;
    private volatile boolean busy;
    private volatile boolean testing;
    private volatile boolean pageReady;
    private volatile Uri lastOutput;
    private PowerManager.WakeLock wake;

    /** JS queued before the WebView has finished loading, replayed in order afterwards. */
    private final List<String> pendingJs = new ArrayList<>();

    private final List<Engine.Job> queue = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        getWindow().setStatusBarColor(Color.parseColor("#060A14"));
        getWindow().setNavigationBarColor(Color.parseColor("#060A14"));

        restoreQueue();

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#060A14"));
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                pageReady = true;
                pushRestored();
                flushJs();
                pushAwake();
            }
        });
        web.addJavascriptInterface(new Bridge(), "Native");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /* ------------------------------------------------------------------ */

    private void js(final String script) {
        if (!pageReady) {
            synchronized (pendingJs) { pendingJs.add(script); }
            return;
        }
        jsNow(script);
    }

    private void jsNow(final String script) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (web != null) web.evaluateJavascript(script, null);
            }
        });
    }

    private void flushJs() {
        List<String> copy;
        synchronized (pendingJs) {
            copy = new ArrayList<>(pendingJs);
            pendingJs.clear();
        }
        for (String s : copy) jsNow(s);
    }

    /** The queue survives a restart: same files, same order, ready to retry. */
    private void pushRestored() {
        JSONArray arr = new JSONArray();
        for (Engine.Job j : queue) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", j.name);
                o.put("size", j.size);
                o.put("uri", j.uri.toString());
                arr.put(o);
            } catch (Exception ignored) { }
        }
        if (arr.length() > 0) jsNow("Sq.onRestore(" + JSONObject.quote(arr.toString()) + ")");
    }

    private void pushAwake() {
        jsNow("Sq.onAwake(" + (wake != null && wake.isHeld() ? "true" : "false") + ")");
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void persistQueue() {
        JSONArray arr = new JSONArray();
        for (Engine.Job j : queue) {
            try {
                JSONObject o = new JSONObject();
                o.put("uri", j.uri.toString());
                o.put("name", j.name);
                o.put("size", j.size);
                arr.put(o);
            } catch (Exception ignored) { }
        }
        prefs().edit().putString(KEY_QUEUE, arr.toString()).apply();
    }

    private void restoreQueue() {
        String raw = prefs().getString(KEY_QUEUE, null);
        if (raw == null || raw.length() == 0) return;
        int dropped = 0;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Uri u;
                try {
                    u = Uri.parse(o.getString("uri"));
                } catch (Exception e) {
                    dropped++;
                    continue;
                }
                // A temporary grant from a share sheet may be gone by the next launch;
                // restoring an unreadable row would only fail later, so drop it here.
                if (!readable(u)) { dropped++; continue; }
                Engine.Job j = new Engine.Job();
                j.uri = u;
                j.name = o.optString("name", "file");
                j.size = o.optLong("size", 0);
                queue.add(j);
            }
        } catch (Exception ignored) { }
        if (dropped > 0) persistQueue();
    }

    private boolean readable(Uri u) {
        if (u == null) return false;
        try {
            InputStream in = getContentResolver().openInputStream(u);
            if (in == null) return false;
            in.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Share-to-Squeeze and open-with land here. */
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (a == null) return;

        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(a)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u == null) u = intent.getData();
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) uris.addAll(many);
        } else if (Intent.ACTION_VIEW.equals(a)) {
            if (intent.getData() != null) uris.add(intent.getData());
        } else {
            return;
        }
        if (uris.isEmpty()) return;
        addUris(uris);
        // Make the shared file survive a restart when the sender allowed it.
        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) { }
        }
    }

    private void launchPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, PICK);
        } catch (Exception e) {
            js("Sq.onError('No file picker available on this device')");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null) return;

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            int n = data.getClipData().getItemCount();
            for (int i = 0; i < n; i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        addUris(uris);
    }

    /** Queue a list of URIs and tell the UI about them. */
    private void addUris(List<Uri> uris) {
        JSONArray arr = new JSONArray();
        for (Uri u : uris) {
            if (u == null) continue;
            String name = null;
            long size = 0;
            try {
                Cursor c = getContentResolver().query(u, null, null, null, null);
                if (c != null) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (c.moveToFirst()) {
                        if (ni >= 0) name = c.getString(ni);
                        if (si >= 0) size = c.getLong(si);
                    }
                    c.close();
                }
            } catch (Exception ignored) { }
            if (name == null) name = u.getLastPathSegment();
            if (name == null) name = "file_" + System.currentTimeMillis();
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);

            Engine.Job job = new Engine.Job();
            job.uri = u;
            job.name = name;
            job.size = size;
            queue.add(job);

            try {
                JSONObject o = new JSONObject();
                o.put("name", name);
                o.put("size", size);
                o.put("uri", u.toString());
                arr.put(o);
            } catch (Exception ignored) { }
        }
        if (arr.length() == 0) return;
        persistQueue();
        js("Sq.onFiles(" + JSONObject.quote(arr.toString()) + ")");
    }

    private void startJob() {
        if (busy) return;
        final List<Engine.Job> jobs = new ArrayList<>(queue);
        if (jobs.isEmpty()) {
            js("Sq.onError('Add some files first')");
            return;
        }
        busy = true;
        cancelled = false;
        Engine.resetCancel();
        keepAwake(true);

        Thread t = new Thread(new Runnable() {
            public void run() {
                runJobs(jobs);
            }
        }, "squeeze-worker");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private static ThreadFactory named(final String prefix) {
        return new ThreadFactory() {
            private int n = 0;
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, prefix + "-" + (++n));
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        };
    }

    /**
     * Runs the whole batch on two lanes: a small pool for photos and archives, one
     * thread for everything that needs MediaCodec. Results are collected by queue
     * index so the UI rows stay in the order the user added the files.
     */
    private void runJobs(final List<Engine.Job> jobs) {
        Engine.Job cfg = new Engine.Job();
        try {
            JSONObject o = new JSONObject(config);
            cfg.mode = o.optString("mode", "balanced");
            cfg.target = (long) (o.optDouble("targetMB", 150) * 1024 * 1024);
            cfg.maxDim = o.optInt("maxDim", 1920);
            cfg.keepOriginal = o.optBoolean("keepOriginal", true);
            cfg.stripMeta = o.optBoolean("stripMeta", true);
        } catch (Exception ignored) { }

        final JSONObject[] results = new JSONObject[jobs.size()];
        final ExecutorService light = Executors.newFixedThreadPool(
                Plan.LIGHT_WORKERS, named("squeeze-light"));
        final ExecutorService codec = Executors.newSingleThreadExecutor(named("squeeze-codec"));

        for (int i = 0; i < jobs.size(); i++) {
            final int index = i;
            final Engine.Job job = jobs.get(i);
            job.mode = cfg.mode;
            job.target = cfg.target;
            job.maxDim = cfg.maxDim;
            job.keepOriginal = cfg.keepOriginal;
            job.stripMeta = cfg.stripMeta;

            Runnable task = new Runnable() {
                public void run() {
                    // Between files: nothing new starts once cancel has been tapped.
                    if (cancelled || Engine.cancelled()) return;
                    results[index] = runOne(index, job);
                }
            };
            if (Plan.light(Engine.ext(job.name))) light.submit(task);
            else codec.submit(task);
        }

        light.shutdown();
        codec.shutdown();
        try {
            while (!light.isTerminated() || !codec.isTerminated()) {
                light.awaitTermination(200, TimeUnit.MILLISECONDS);
                codec.awaitTermination(200, TimeUnit.MILLISECONDS);
                if (cancelled) {
                    // The flag is checked inside the codec loop too, so this returns
                    // within a frame or two instead of after the whole transcode.
                    light.shutdownNow();
                    codec.shutdownNow();
                    light.awaitTermination(4, TimeUnit.SECONDS);
                    codec.awaitTermination(6, TimeUnit.SECONDS);
                    break;
                }
            }
        } catch (InterruptedException ignored) { }

        final boolean stopped = cancelled || Engine.cancelled();
        if (stopped) {
            // No half-written file in Downloads/Squeeze, no scratch left behind.
            Engine.sweepPartials(this);
            Engine.resetCancel();
        }

        JSONArray done = new JSONArray();
        long totalSaved = 0;
        for (JSONObject o : results) {
            if (o == null) continue;
            done.put(o);
            long in = o.optLong("in", 0), out = o.optLong("out", 0);
            if (in > out) totalSaved += (in - out);
        }

        try {
            JSONObject summary = new JSONObject();
            summary.put("files", done);
            summary.put("saved", totalSaved);
            if (stopped) summary.put("cancelled", true);
            js("Sq.onDone(" + JSONObject.quote(summary.toString()) + ")");
        } catch (Exception ignored) { }

        busy = false;
        keepAwake(false);

        if (stopped) persistQueue();      // the queue is what the user retries with
        else prefs().edit().remove(KEY_QUEUE).apply();
    }

    /** One job, on whichever lane it landed on. Returns its result row (or null). */
    private JSONObject runOne(final int index, final Engine.Job job) {
        final long t0 = System.currentTimeMillis();
        final boolean byteMetered = Plan.byteMetered(Engine.ext(job.name));
        final long[] sent = new long[]{0L, -2L};   // last send time, last eta
        final int[] lastPct = new int[]{-1};

        js(progressJson(index, "working", 0, 0, null, Speed.UNKNOWN));

        Engine.Progress cb = new Engine.Progress() {
            public void on(float f, long out) {
                float frac = Math.min(1f, Math.max(0f, f));
                int pct = Math.round(frac * 100f);
                long elapsed = System.currentTimeMillis() - t0;
                long eta = Speed.eta(byteMetered, out, job.size, frac, elapsed);
                long now = System.currentTimeMillis();
                boolean stale = now - sent[0] < PROGRESS_MS;
                boolean etaMoved = Math.abs(eta - sent[1]) >= 2;
                if (stale && pct == lastPct[0] && !etaMoved) return;
                sent[0] = now;
                sent[1] = eta;
                lastPct[0] = pct;
                js(progressJson(index, "working", pct, out, null, eta));
            }
        };

        Engine.Result r = null;
        String err = null;
        try {
            r = Engine.compress(MainActivity.this, job, cb);
        } catch (Throwable t) {
            // A cancel is not a failure: the row goes back to waiting, not to "failed".
            if (Engine.isCancel(t)) return null;
            String m = t.getMessage();
            err = (m != null && m.length() > 0) ? m : t.getClass().getSimpleName();
        }
        if (r != null && !r.ok) err = r.error;
        if (r == null && err == null) err = "Compression failed";

        try {
            if (err != null) {
                // Say what went wrong instead of pretending the file was compressed.
                js(progressJson(index, "failed", 100, 0, err, Speed.UNKNOWN));
                JSONObject o = new JSONObject();
                o.put("index", index);
                o.put("in", job.size);
                o.put("out", job.size);
                o.put("error", err);
                o.put("name", job.name);
                return o;
            }

            long out = r.out;
            // The engine's own note ("AAC 96 kbps", "Smallest reachable is 38 MB") is
            // the most useful line in the row, so it goes out with the result.
            if (r.note != null) js("Sq.onNote(" + index + "," + JSONObject.quote(r.note) + ")");
            js(progressJson(index, "done", 100, out, null, 0));
            if (r.uri != null) lastOutput = r.uri;

            JSONObject o = new JSONObject();
            o.put("index", index);
            o.put("in", job.size);
            o.put("out", out);
            o.put("name", r.name != null ? r.name : job.name);
            if (r.note != null) o.put("note", r.note);
            if (r.codec != null) o.put("codec", r.codec);
            return o;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Run the built-in proof: build a synthetic clip on this device and push it through
     * the real engine. Runs on its own thread so the UI keeps its progress bar.
     */
    private void runSelfTest() {
        if (testing) return;
        testing = true;
        keepAwake(true);
        Thread t = new Thread(new Runnable() {
            public void run() {
                String json;
                try {
                    JSONObject o = SelfTest.run(MainActivity.this, new SelfTest.Step() {
                        public void on(String label, int pct) {
                            js(stepJson(label, pct));
                        }
                    });
                    json = o.toString();
                } catch (Throwable t2) {
                    json = errorJson(t2);
                }
                js("Sq.onSelfTest(" + JSONObject.quote(json) + ")");
                testing = false;
                keepAwake(false);
            }
        }, "squeeze-selftest");
        t.start();
    }

    private String errorJson(Throwable t) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("fails", 1);
            o.put("rows", new JSONArray());
            String m = t.getMessage();
            o.put("error", (m != null && m.length() > 0) ? m : t.getClass().getSimpleName());
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"fails\":1}";
        }
    }

    private String stepJson(String label, int pct) {
        try {
            JSONObject o = new JSONObject();
            o.put("label", label);
            o.put("pct", Math.max(0, Math.min(100, pct)));
            return "Sq.onSelfTestStep(" + JSONObject.quote(o.toString()) + ")";
        } catch (Exception e) {
            return "0";
        }
    }

    /**
     * @param eta seconds remaining, or {@link Speed#UNKNOWN} when it cannot be known.
     *            The UI hides the field rather than printing a guess.
     */
    private String progressJson(int index, String status, int pct, long out, String error, long eta) {
        try {
            JSONObject o = new JSONObject();
            o.put("index", index);
            o.put("status", status);
            o.put("progress", pct);
            o.put("out", out);
            if (eta != Speed.UNKNOWN) o.put("eta", eta);
            if (error != null) o.put("error", error);
            return "Sq.onProgress(" + JSONObject.quote(o.toString()) + ")";
        } catch (Exception e) {
            return "0";
        }
    }

    /** Downloads/Squeeze, opened the way the system file manager understands. */
    private void openOutputFolder() {
        // 1. The Downloads collection itself, as a directory document.
        if (tryView(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                DocumentsContract.Document.MIME_TYPE_DIR)) return;

        // 2. The Squeeze folder inside it, as a document tree node.
        Uri tree = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Download/Squeeze");
        if (tryView(tree, DocumentsContract.Document.MIME_TYPE_DIR)) return;

        // 3. The newest file we actually produced — always something real to open.
        if (lastOutput != null) {
            if (tryView(lastOutput, Engine.mimeOf(lastOutput.getLastPathSegment()))) return;
            if (tryView(lastOutput, "*/*")) return;
        }

        // 4. A picker rooted at Downloads, so the user still lands in the right place.
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivity(i);
            return;
        } catch (Exception ignored) { }

        js("Sq.onError('No file manager on this device to open Downloads/Squeeze')");
    }

    private boolean tryView(Uri uri, String mime) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void keepAwake(boolean on) {
        try {
            if (on) {
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "squeeze:job");
                wake.setReferenceCounted(false);
                wake.acquire(60 * 60 * 1000L);
            } else {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (wake != null && wake.isHeld()) wake.release();
                wake = null;
            }
        } catch (Exception ignored) { }
        pushAwake();
    }

    @Override
    public void onBackPressed() {
        js("(function(){var s=document.querySelector('.sheet.on');if(s){closeSheets();}else{}})()");
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        keepAwake(false);
        if (web != null) {
            web.removeJavascriptInterface("Native");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /* ------------------------------------------------------------------ */

    private class Bridge {
        @JavascriptInterface
        public void pickFiles() {
            runOnUiThread(new Runnable() {
                public void run() { launchPicker(); }
            });
        }

        @JavascriptInterface
        public void configure(String json) {
            config = json;
        }

        @JavascriptInterface
        public void start() {
            startJob();
        }

        @JavascriptInterface
        public void cancel() {
            cancelled = true;
            Engine.requestCancel();
        }

        @JavascriptInterface
        public void clearQueue() {
            queue.clear();
            prefs().edit().remove(KEY_QUEUE).apply();
        }

        @JavascriptInterface
        public void openOutputFolder() {
            runOnUiThread(new Runnable() {
                public void run() { MainActivity.this.openOutputFolder(); }
            });
        }

        @JavascriptInterface
        public void selfTest() {
            runSelfTest();
        }

        @JavascriptInterface
        public void encoders() {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    js("Sq.onCodecs(" + JSONObject.quote(SelfTest.inventory().toString()) + ")");
                }
            }, "squeeze-codecs");
            t.start();
        }

        @JavascriptInterface
        public void freeSpace() {
            js("Sq.onSpace(" + Engine.freeSpace(MainActivity.this) + ")");
        }

        @JavascriptInterface
        public void toast(String msg) {
            js("Sq.onError(" + JSONObject.quote(msg) + ")");
        }
    }
}
