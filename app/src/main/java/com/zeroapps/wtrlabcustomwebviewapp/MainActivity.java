package com.zeroapps.wtrlabcustomwebviewapp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

  private static final boolean SHOW_STATUS_BAR = true;
  private static final boolean SHOW_NAVIGATION_BAR = false;

  private static final String TARGET_URL = "https://wtr-lab.com/en/library";

  private static final String OFFLINE_URL = "file:///android_asset/pages/offline.html";

  private static final String ASSET_PREFIX = "file:///android_asset";

  private WebView webView;

  private volatile boolean isShowingOfflinePage = false;
  private volatile String lastSuccessUrl = TARGET_URL;
  private volatile String retryUrl = TARGET_URL;

  private static final Set<String> ALLOWED_HOSTS = new HashSet<>(
      Arrays.asList("wtr-lab.com", "accounts.google.com"));

  private static final String AD_REMOVER_JS = "(function(){"
      + "if(window.__adKiller) return; window.__adKiller = true;"
      + "var selectors = ['div.wtr-placeholder.fix-size'];" + "function removeAds(){"
      + "  selectors.forEach(function(s){" + "    document.querySelectorAll(s).forEach(function(e){"
      + "      while(e.firstChild){" + "        e.removeChild(e.firstChild);" + "      }"
      + "    });" + "  });" + "}" + "removeAds();" + "new MutationObserver(removeAds)"
      + "  .observe(document.documentElement, {childList:true, subtree:true});" + "})();";

  private boolean isAllowedUrl(String url) {
    if (url == null) {
      return false;
    }

    try {
      Uri uri = Uri.parse(url);

      String scheme = uri.getScheme();
      String host = uri.getHost();

      if (scheme == null || host == null) {
        return false;
      }

      if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
        return false;
      }

      host = host.toLowerCase(Locale.ROOT);

      if (ALLOWED_HOSTS.contains(host)) {
        return true;
      }
      return host.endsWith(".wtr-lab.com");

    } catch (Exception e) {
      return false;
    }
  }

  private static boolean isWebUrl(String url) {
    if (url == null) {
      return false;
    }

    return url.startsWith("http://") || url.startsWith("https://");
  }

  private void doRetry() {
    if (webView == null) {
      return;
    }

    String urlToLoad;

    if (isAllowedUrl(retryUrl)) {
      urlToLoad = retryUrl;
    } else if (isAllowedUrl(lastSuccessUrl)) {
      urlToLoad = lastSuccessUrl;
    } else {
      urlToLoad = TARGET_URL;
    }

    webView.stopLoading();

    isShowingOfflinePage = false;
    webView.setVisibility(View.VISIBLE);
    webView.loadUrl(urlToLoad);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    setupFullscreenLayout();

    setContentView(R.layout.activity_main);

    webView = findViewById(R.id.webview);

    View container = findViewById(R.id.container);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      container.setOnApplyWindowInsetsListener((v, insets) -> {
        Insets bars = insets
            .getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());

        Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());

        int left = bars.left;
        int top = bars.top;
        int right = bars.right;
        int bottom = bars.bottom;

        if (SHOW_STATUS_BAR) {
          top = Math.max(top, cutout.top);
        }

        if (SHOW_NAVIGATION_BAR) {
          bottom = Math.max(bottom, cutout.bottom);
        }

        if (SHOW_STATUS_BAR || SHOW_NAVIGATION_BAR) {
          left = Math.max(left, cutout.left);
          right = Math.max(right, cutout.right);
        }

        v.setPadding(left, top, right, bottom);

        return WindowInsets.CONSUMED;
      });
    }

    webView.setVerticalScrollBarEnabled(false);
    webView.setHorizontalScrollBarEnabled(false);

    WebSettings webSettings = webView.getSettings();

    webSettings.setJavaScriptEnabled(true);
    webSettings.setDomStorageEnabled(true);
    webSettings.setSupportMultipleWindows(true);
    webSettings.setJavaScriptCanOpenWindowsAutomatically(false);

    webView.addJavascriptInterface(new Object() {
      @android.webkit.JavascriptInterface
      public void retry() {
        runOnUiThread(() -> doRetry());
      }
    }, "AndroidInterface");

    webView.setWebViewClient(new WebViewClient() {

      
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {

        String url = request.getUrl().toString();

        if (!isAllowedUrl(url)) {
          return true;
        }

        if (!isNetworkAvailable()) {
          loadOfflinePage(url);
          return true;
        }

        isShowingOfflinePage = false;

        return false;
      }

      @Override
      public boolean shouldOverrideUrlLoading(WebView view, String url) {

        if (!isAllowedUrl(url)) {
          return true;
        }

        if (!isNetworkAvailable()) {
          loadOfflinePage(url);
          return true;
        }

        isShowingOfflinePage = false;

        return false;
      }

      public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {

        String url = request.getUrl().toString();

        if (request.isForMainFrame() && !isAllowedUrl(url)) {

          return new WebResourceResponse("text/plain", "UTF-8", 403, "Blocked",
              new java.util.HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
        }

        return super.shouldInterceptRequest(view, request);
      }

      @Override
      public void onPageStarted(WebView view, String url, Bitmap favicon) {

        super.onPageStarted(view, url, favicon);

        if (url == null) {
          return;
        }
        if (isWebUrl(url) && !isAllowedUrl(url)) {
          view.stopLoading();
          return;
        }

        if (isAllowedUrl(url)) {
          isShowingOfflinePage = false;
        }
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);

        if (url == null) {
          return;
        }

        if (url.startsWith(ASSET_PREFIX)) {
          view.setVisibility(View.VISIBLE);
          return;
        }

        if (isAllowedUrl(url) && !isShowingOfflinePage) {
          lastSuccessUrl = url;
          retryUrl = url;

          view.setVisibility(View.VISIBLE);
          view.evaluateJavascript(AD_REMOVER_JS, null);
        }
      }

      @Override
      public void onReceivedError(WebView view, WebResourceRequest request,
          WebResourceError error) {

        super.onReceivedError(view, request, error);

        if (!request.isForMainFrame()) {
          return;
        }

        String url = request.getUrl().toString();

        if (!isAllowedUrl(url)) {
          return;
        }

        if (!isShowingOfflinePage) {
          loadOfflinePage(url);
        }
      }

      @Override
      public void onReceivedError(WebView view, int errorCode, String description,
          String failingUrl) {

        super.onReceivedError(view, errorCode, description, failingUrl);

        if (failingUrl == null) {
          return;
        }
        if (!isAllowedUrl(failingUrl)) {
          return;
        }
        if (!isShowingOfflinePage && failingUrl.equals(view.getUrl())) {
          loadOfflinePage(failingUrl);
        }
      }

      @Override
      public void onReceivedHttpError(WebView view, WebResourceRequest request,
          WebResourceResponse errorResponse) {

        super.onReceivedHttpError(view, request, errorResponse);

        if (!request.isForMainFrame()) {
          return;
        }

        String url = request.getUrl().toString();
        if (!isAllowedUrl(url)) {
          return;
        }

        if (errorResponse.getStatusCode() >= 400 && !isShowingOfflinePage) {

          loadOfflinePage(url);
        }
      }
    });

    webView.setWebChromeClient(new WebChromeClient() {
      @Override
      public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        return false;
      }
    });
    
    if (isAllowedUrl(TARGET_URL) && isNetworkAvailable()) {
      webView.loadUrl(TARGET_URL);
    } else if (isAllowedUrl(TARGET_URL)) {
      loadOfflinePage(TARGET_URL);
    }
  }

  private void setupFullscreenLayout() {
    boolean anyHidden = !SHOW_STATUS_BAR || !SHOW_NAVIGATION_BAR;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      if (anyHidden) {
        getWindow().setDecorFitsSystemWindows(false);

        WindowManager.LayoutParams params = getWindow().getAttributes();

        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

        getWindow().setAttributes(params);

      } else {
        getWindow().setDecorFitsSystemWindows(true);

        WindowManager.LayoutParams params = getWindow().getAttributes();

        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;

        getWindow().setAttributes(params);
      }

    } else if (!SHOW_STATUS_BAR && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {

      WindowManager.LayoutParams params = getWindow().getAttributes();

      params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

      getWindow().setAttributes(params);
    }
  }

  private boolean isNetworkAvailable() {
    try {
      ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(
          Context.CONNECTIVITY_SERVICE);

      if (connectivityManager != null) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
          NetworkCapabilities capabilities = connectivityManager
              .getNetworkCapabilities(connectivityManager.getActiveNetwork());

          return capabilities != null
              && (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                  || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                  || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                  || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN));

        } else {
          NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();

          return activeNetworkInfo != null && activeNetworkInfo.isConnected();
        }
      }

    } catch (SecurityException e) {
      return true;
    }

    return false;
  }

  private void loadOfflinePage(String failedUrl) {

    if (failedUrl != null && isWebUrl(failedUrl) && !isAllowedUrl(failedUrl)) {
      return;
    }
    if (isShowingOfflinePage) {
      return;
    }
    isShowingOfflinePage = true;
    if (isAllowedUrl(failedUrl)) {
      retryUrl = failedUrl;

    } else if (isAllowedUrl(lastSuccessUrl)) {
      retryUrl = lastSuccessUrl;

    } else {
      retryUrl = TARGET_URL;
    }

    if (webView != null) {
      webView.post(() -> webView.loadUrl(OFFLINE_URL));
    }
  }

  private void applySystemBars() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      WindowInsetsController controller = getWindow().getInsetsController();

      if (controller == null) {
        return;
      }

      int toHide = 0;
      int toShow = 0;

      if (SHOW_STATUS_BAR) {
        toShow |= WindowInsets.Type.statusBars();
      } else {
        toHide |= WindowInsets.Type.statusBars();
      }

      if (SHOW_NAVIGATION_BAR) {
        toShow |= WindowInsets.Type.navigationBars();
      } else {
        toHide |= WindowInsets.Type.navigationBars();
      }

      if (toShow != 0) {
        controller.show(toShow);
      }

      if (toHide != 0) {
        controller.hide(toHide);
      }

      controller
          .setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

    } else {
      int flags = View.SYSTEM_UI_FLAG_VISIBLE;

      if (!SHOW_STATUS_BAR) {
        flags |= View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
      }

      if (!SHOW_NAVIGATION_BAR) {
        flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
      }

      if (!SHOW_STATUS_BAR || !SHOW_NAVIGATION_BAR) {
        flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
      }

      getWindow().getDecorView().setSystemUiVisibility(flags);
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);

    if (hasFocus) {
      applySystemBars();
    }
  }

  @Override
  public void onBackPressed() {
    if (webView != null && webView.canGoBack()) {
      webView.goBack();
    } else {
      super.onBackPressed();
    }
  }
}