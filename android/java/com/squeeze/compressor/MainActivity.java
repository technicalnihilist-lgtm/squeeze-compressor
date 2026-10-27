package com.squeeze.compressor;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin native shell around the Squeeze UI. The HTML layer owns the look; this class
 * owns file access (SAF), the compression worker thread and the output folder.
 */
public class MainActivity extends Activity {

    private static final int PICK = 1001;

    private WebView web;
    private volatile String config = "{}";
    private volatile boolean cancelled;
    private volatile boolean busy;
    private PowerManager.WakeLock wake;

    private final List<Engine.Job> queue = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        getWindow().setStatusBarColor(Color.parseColor("#060A14"));
        getWindow().setNavigationBarColor(Color.parseColor("#060A14"));

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
        web.addJavascriptInterface(new Bridge(), "Native");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    /* ------------------------------------------------------------------ */

    private void js(final String script) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (web != null) web.evaluateJavascript(script, null);
            }
        });
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

        JSONArray arr = new JSONArray();
        for (Uri u : uris) {
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
            if (name == null) name = "file_" + System.currentTimeMillis();

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
        keepAwake(true);

        Thread t = new Thread(new Runnable() {
            public void run() {
                runJobs(jobs);
            }
        }, "squeeze-worker");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    private void runJobs(List<Engine.Job> jobs) {
        Engine.Job cfg = new Engine.Job();
        try {
            JSONObject o = new JSONObject(config);
            cfg.mode = o.optString("mode", "balanced");
            cfg.target = (long) (o.optDouble("targetMB", 150) * 1024 * 1024);
            cfg.maxDim = o.optInt("maxDim", 1920);
            cfg.keepOriginal = o.optBoolean("keepOriginal", true);
            cfg.stripMeta = o.optBoolean("stripMeta", true);
        } catch (Exception ignored) { }

        JSONArray results = new JSONArray();
        long totalSaved = 0;

        for (int i = 0; i < jobs.size(); i++) {
            if (cancelled) break;
            final Engine.Job job = jobs.get(i);
            job.mode = cfg.mode;
            job.target = cfg.target;
            job.maxDim = cfg.maxDim;
            job.keepOriginal = cfg.keepOriginal;
            job.stripMeta = cfg.stripMeta;

            final int index = i;
            js(progressJson(index, "working", 0, 0));

            Engine.Progress cb = new Engine.Progress() {
                public void on(float f, long out) {
                    js(progressJson(index, "working", Math.round(f * 100f), out));
                }
            };

            Engine.Result r;
            try {
                r = Engine.compress(MainActivity.this, job, cb);
            } catch (Exception e) {
                r = null;
            }

            long out = r != null && r.ok ? r.out : job.size;
            if (r != null && r.ok && job.size > out) totalSaved += (job.size - out);
            js(progressJson(index, "done", 100, out));

            try {
                JSONObject o = new JSONObject();
                o.put("out", out);
                o.put("name", r != null && r.name != null ? r.name : job.name);
                results.put(o);
            } catch (Exception ignored) { }
        }

        try {
            JSONObject done = new JSONObject();
            done.put("files", results);
            done.put("saved", totalSaved);
            js("Sq.onDone(" + JSONObject.quote(done.toString()) + ")");
        } catch (Exception ignored) { }

        queue.clear();
        busy = false;
        keepAwake(false);
    }

    private String progressJson(int index, String status, int pct, long out) {
        try {
            JSONObject o = new JSONObject();
            o.put("index", index);
            o.put("status", status);
            o.put("progress", pct);
            o.put("out", out);
            return "Sq.onProgress(" + JSONObject.quote(o.toString()) + ")";
        } catch (Exception e) {
            return "0";
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
        }

        @JavascriptInterface
        public void freeSpace() {
            long free = 0;
            try {
                free = getFilesDir().getUsableSpace();
            } catch (Exception ignored) { }
            js("Sq.onSpace(" + free + ")");
        }

        @JavascriptInterface
        public void toast(String msg) {
            js("Sq.onError(" + JSONObject.quote(msg) + ")");
        }
    }
}
