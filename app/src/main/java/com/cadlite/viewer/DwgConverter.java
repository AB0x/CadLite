package com.cadlite.viewer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts DWG to DXF on-device using LibreDWG compiled to WebAssembly, run in an
 * invisible WebView. No network access: every request is served from app assets.
 * The WebView is only created when a DWG is opened, so DXF viewing stays lightweight.
 */
final class DwgConverter {

    interface Callback { void done(File dxf, String error); }

    private static final String HOST = "https://dwg.local/";

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private Callback cb;
    private OutputStream out;
    private boolean finished;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    void convert(Context ctx, ViewGroup host, final File dwg, final File dxf, Callback callback) {
        this.cb = callback;
        try {
            out = new FileOutputStream(dxf);
        } catch (IOException e) {
            callback.done(null, e.getMessage());
            return;
        }
        final Context app = ctx.getApplicationContext();
        web = new WebView(ctx);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setBlockNetworkLoads(false); // requests are intercepted below; nothing leaves the device
        web.addJavascriptInterface(new Bridge(dxf), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                String url = req.getUrl().toString();
                if (!url.startsWith(HOST)) return deny();
                String path = url.substring(HOST.length());
                try {
                    switch (path) {
                        case "index.html": return res("text/html", app.getAssets().open("dwg/index.html"));
                        case "libredwg-web.js": return res("text/javascript", app.getAssets().open("dwg/libredwg-web.js"));
                        case "libredwg-web.wasm": return res("application/wasm", app.getAssets().open("dwg/libredwg-web.wasm"));
                        case "input.dwg": return res("application/octet-stream", new FileInputStream(dwg));
                        default: return deny();
                    }
                } catch (IOException e) {
                    return deny();
                }
            }
        });
        // A tiny invisible view keeps JS timers running on all WebView versions.
        host.addView(web, new ViewGroup.LayoutParams(1, 1));
        web.setAlpha(0f);
        web.loadUrl(HOST + "index.html");
        main.postDelayed(new Runnable() {
            @Override public void run() { finish(null, "Timed out converting DWG"); }
        }, 180_000);
    }

    private static WebResourceResponse res(String mime, InputStream in) {
        WebResourceResponse r = new WebResourceResponse(mime, null, in);
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        r.setResponseHeaders(h);
        return r;
    }

    private static WebResourceResponse deny() {
        return new WebResourceResponse("text/plain", null, 404, "Not Found", null, null);
    }

    private void finish(final File dxf, final String err) {
        main.post(new Runnable() {
            @Override public void run() {
                if (finished) return;
                finished = true;
                main.removeCallbacksAndMessages(null);
                try { if (out != null) out.close(); } catch (IOException ignored) {}
                if (web != null) {
                    if (web.getParent() instanceof ViewGroup) ((ViewGroup) web.getParent()).removeView(web);
                    web.destroy();
                    web = null;
                }
                cb.done(dxf, err);
            }
        });
    }

    /** Called from JS on the WebView's background bridge thread. */
    final class Bridge {
        private final File dxf;
        Bridge(File dxf) { this.dxf = dxf; }

        /** Receives a chunk of DXF bytes encoded 1:1 as Latin-1 characters. */
        @JavascriptInterface public void chunk(String s) {
            try { out.write(s.getBytes(StandardCharsets.ISO_8859_1)); }
            catch (IOException e) { finish(null, e.getMessage()); }
        }
        @JavascriptInterface public void done() {
            try { out.flush(); } catch (IOException ignored) {}
            finish(dxf, null);
        }
        @JavascriptInterface public void fail(String msg) { finish(null, msg); }
    }
}
