package com.azhar.youtude;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {

    // ===== এখানে আপনার ব্লগের ঠিকানা। বদলাতে চাইলে শুধু এই লাইনটা বদলান =====
    private static final String HOME_URL = "https://youutude.blogspot.com/?m=0";
    // ===========================================================================

    private WebView web;
    private FrameLayout root;
    private volatile String sharedText = "";
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        // ওয়েব অ্যাপ থেকে (PIN দেওয়ার পর) স্ক্রিন পিনিং খুলে অ্যাপ বন্ধ করার জন্য
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void exitKiosk() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try { stopLockTask(); } catch (Exception ignored) { }
                        finishAndRemoveTask();
                    }
                });
            }

            @JavascriptInterface
            public void setPlaying(final boolean on) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Intent svc = new Intent(MainActivity.this, PlaybackService.class);
                            if (on) {
                                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc); else startService(svc);
                            } else {
                                stopService(svc);
                            }
                        } catch (Exception ignored) { }
                    }
                });
            }

            // YouTube থেকে Share করা লেখা একবার দিয়ে খালি করে দেয়
            @JavascriptInterface
            public String getShared() {
                String t = sharedText;
                sharedText = "";
                return t == null ? "" : t;
            }

            @JavascriptInterface
            public boolean isLock() {
                return lockOn();
            }

            @JavascriptInterface
            public void setLock(final boolean on) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try { if (on) startLockTask(); else stopLockTask(); } catch (Exception ignored) { }
                    }
                });
            }
        }, "AndroidApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri u = request.getUrl();
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
                if (scheme.equals("file")) return false;
                // Blogger-এর মোবাইল টেমপ্লেট (?m=1) এলে সাধারণ টেমপ্লেটে ফিরিয়ে নেওয়া
                if (host.endsWith("blogspot.com") && "1".equals(u.getQueryParameter("m"))) {
                    view.loadUrl(HOME_URL);
                    return true;
                }
                boolean ok = scheme.equals("https") && (
                        host.endsWith("blogspot.com") ||
                        host.equals("script.google.com") ||
                        host.endsWith(".google.com") ||
                        host.endsWith("googleusercontent.com"));
                // অনুমতি নেই এমন যেকোনো ঠিকানা (যেমন YouTube) — চুপচাপ বন্ধ
                return !ok;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadUrl("file:///android_asset/offline.html");
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                web.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                hideCustom();
            }
        });

        if (savedInstanceState == null) takeShare(getIntent());

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME_URL);
        }
    }

    // এখন সত্যিই পিন করা আছে কিনা (ফোনের নিজের অবস্থা থেকে)
    private boolean lockOn() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        } catch (Exception e) {
            return false;
        }
    }

    private void hideCustom() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        web.setVisibility(View.VISIBLE);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
    }

    private void takeShare(Intent in) {
        if (in == null) return;
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            String t = in.getStringExtra(Intent.EXTRA_TEXT);
            if (t != null && t.length() > 0) sharedText = t;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeShare(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        hideSystemBars();
    }

    @Override
    protected void onPause() {
        // ইচ্ছা করেই web.onPause() ডাকা হয় না, যাতে স্ক্রিন বন্ধ করলেও শব্দ চলে
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustom();
        } else if (web.canGoBack()) {
            web.goBack();
        }
        // ইচ্ছা করেই অ্যাপ বন্ধ হয় না: বাচ্চারা ভুলে বেরিয়ে যেতে পারবে না
    }

    @Override
    protected void onDestroy() {
        try { stopService(new Intent(this, PlaybackService.class)); } catch (Exception ignored) { }
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
