package itkach.aard2.widget;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewClientCompat;

import com.google.android.material.color.MaterialColors;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;

import itkach.aard2.R;
import itkach.aard2.SlobHelper;
import itkach.aard2.article.ArticleCollectionActivity;
import itkach.aard2.descriptor.BlobDescriptor;
import itkach.aard2.prefs.ArticleViewPrefs;
import itkach.aard2.prefs.UserStylesPrefs;
import itkach.aard2.utils.StyleJsUtils;
import itkach.aard2.utils.Utils;

public class ArticleWebView extends SearchableWebView {
    public static final String TAG = ArticleWebView.class.getSimpleName();

    public static final String LOCALHOST = SlobHelper.LOCALHOST;

    private static final Set<String> EXTERNAL_SCHEMES = new HashSet<String>() {{
        add("https");
        add("ftp");
        add("sftp");
        add("mailto");
        add("geo");
    }};

    private final String defaultStyleTitle;
    private final String autoStyleTitle;

    private boolean isExternal(Uri uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();

        return EXTERNAL_SCHEMES.contains(scheme) || ("http".equals(scheme) && !LOCALHOST.equals(host));
    }

    private SortedSet<String> styleTitles = new TreeSet<>();

    private String currentSlobId;
    private String currentSlobUri;

    private final ConnectivityManager connectivityManager;
    private final Timer timer;
    private final TimerTask applyStylePref;

    private boolean forceLoadRemoteContent;

    @JavascriptInterface
    public void setStyleTitles(String[] titles) {
        Log.d(TAG, String.format("Got %d style titles", titles.length));
        if (titles.length == 0) {
            return;
        }
        SortedSet<String> newStyleTitlesSet = new TreeSet<>(Arrays.asList(titles));
        if (!styleTitles.equals(newStyleTitlesSet)) {
            styleTitles = newStyleTitlesSet;
            ArticleViewPrefs.setAvailableStyles(currentSlobUri, styleTitles);
        }

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            for (String title : titles) {
                Log.d(TAG, title);
            }
        }
    }

    public ArticleWebView(Context context) {
        this(context, null);
    }

    public ArticleWebView(Context context, AttributeSet attrs) {
        super(context, attrs);

        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(!ArticleViewPrefs.disableJavaScript());
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        if (ArticleViewPrefs.enableForceDark()) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true);
        }

        Resources r = getResources();
        defaultStyleTitle = r.getString(R.string.default_style_title);
        autoStyleTitle = r.getString(R.string.auto_style_title);

        addJavascriptInterface(this, "$SLOB");

        timer = new Timer();

        final Runnable applyStyleRunnable = this::applyStylePref;

        applyStylePref = new TimerTask() {
            @Override
            public void run() {
                Handler handler = getHandler();
                if (handler != null) {
                    handler.post(applyStyleRunnable);
                }
            }
        };

        setWebViewClient(new WebViewClient());

        setOnLongClickListener(view -> {
            HitTestResult hitTestResult = getHitTestResult();
            int resultType = hitTestResult.getType();
            Log.d(TAG, String.format(
                    "Long tap on element %s (%s)",
                    resultType,
                    hitTestResult.getExtra()));
            if (resultType == HitTestResult.SRC_ANCHOR_TYPE ||
                    resultType == HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                String url = hitTestResult.getExtra();
                Uri uri = Uri.parse(url);
                if (isExternal(uri)) {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                    share.putExtra(Intent.EXTRA_TEXT, url);
                    getContext().startActivity(Intent.createChooser(share, "Share Link"));
                    return true;
                }
            }
            return false;
        });

        applyTextZoomPref();
    }

    public void setForceLoadRemoteContent(boolean forceLoadRemoteContent) {
        this.forceLoadRemoteContent = forceLoadRemoteContent;
    }

    private boolean allowRemoteContent() {
        if (forceLoadRemoteContent) {
            return true;
        }
        String prefValue = ArticleViewPrefs.getRemoteContentPreference();
        if (prefValue.equals(ArticleViewPrefs.PREF_REMOTE_CONTENT_ALWAYS)) {
            return true;
        }
        if (prefValue.equals(ArticleViewPrefs.PREF_REMOTE_CONTENT_NEVER)) {
            return false;
        }
        if (prefValue.equals(ArticleViewPrefs.PREF_REMOTE_CONTENT_WIFI)) {
            NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
            if (networkInfo != null) {
                int networkType = networkInfo.getType();
                return networkType == ConnectivityManager.TYPE_WIFI || networkType == ConnectivityManager.TYPE_ETHERNET;
            }
        }
        return false;
    }

    public String[] getAvailableStyles() {
        List<String> names = UserStylesPrefs.listStyleNames();
        Collections.sort(names);
        names.addAll(styleTitles);
        names.add(defaultStyleTitle);
        names.add(autoStyleTitle);
        return names.toArray(new String[0]);
    }

    private String getAutoStyle() {
        if (Utils.isNightMode(getContext())) {
            for (String title : styleTitles) {
                String titleLower = title.toLowerCase(Locale.ROOT);
                if (titleLower.contains("night") || titleLower.contains("dark")) {
                    return title;
                }
            }
        }
        Log.d(TAG, "Auto style will return " + defaultStyleTitle);
        return defaultStyleTitle;
    }

    private void setStyle(String styleTitle) {
        String js;
        if (UserStylesPrefs.hasStyle(styleTitle)) {
            String css = UserStylesPrefs.getStylesheet(styleTitle);
            String elementId = getCurrentSlobId();
            js = String.format("javascript:" + StyleJsUtils.getUserStyleJs(), elementId, css);
        } else {
            js = String.format(
                    "javascript:" + StyleJsUtils.getClearUserStyleJs() + StyleJsUtils.getSetCannedStyleJs(),
                    getCurrentSlobId(), styleTitle);
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, js);
        }
        loadUrl(js);
    }

    public void applyTextZoomPref() {
        int textZoom = ArticleViewPrefs.getPreferredZoomLevel();
        WebSettings settings = getSettings();
        settings.setTextZoom(textZoom);
    }

    private void saveTextZoomPref() {
        int textZoom = getSettings().getTextZoom();
        ArticleViewPrefs.setPreferredZoomLevel(textZoom);
    }

    private String getCurrentSlobId() {
        return currentSlobId;
    }

    private void loadAvailableStylesPref() {
        if (currentSlobUri == null) {
            Log.w(TAG, "Can't load article view available styles pref - slob uri is null");
            return;
        }
        Log.d(TAG, "Available styles before pref load: " + styleTitles.size());
        styleTitles = ArticleViewPrefs.getAvailableStyles(currentSlobUri);
        Log.d(TAG, "Loaded available styles: " + styleTitles.size());
    }

    public void saveStylePref(String styleTitle) {
        if (currentSlobUri == null) {
            Log.w(TAG, "Can't save article view style pref - slob uri is null");
            return;
        }
        ArticleViewPrefs.setDefaultStyle(currentSlobUri, styleTitle);
    }

    private boolean isAutoStyle(String title) {
        return title.equals(autoStyleTitle);
    }

    @JavascriptInterface
    public String getPreferredStyle() {
        if (currentSlobUri == null) {
            return "";
        }
        String styleTitle = ArticleViewPrefs.getDefaultStyle(currentSlobUri, autoStyleTitle);
        String result = isAutoStyle(styleTitle) ? getAutoStyle() : styleTitle;
        Log.d(TAG, "getPreferredStyle() will return " + result);
        return result;
    }

    @JavascriptInterface
    public String exportStyleSwitcherAs() {
        return "$styleSwitcher";
    }

    @JavascriptInterface
    public void onStyleSet(String title) {
        Log.d(TAG, "Style set! " + title);
        applyStylePref.cancel();
    }

    public void applyStylePref() {
        String styleTitle = getPreferredStyle();
        setStyle(styleTitle);
    }

    public boolean textZoomIn() {
        WebSettings settings = getSettings();
        int newZoom = settings.getTextZoom() + 20;
        if (newZoom <= 200) {
            settings.setTextZoom(newZoom);
            saveTextZoomPref();
            return true;
        } else {
            return false;
        }
    }

    public boolean textZoomOut() {
        WebSettings settings = getSettings();
        int newZoom = settings.getTextZoom() - 20;
        if (newZoom >= 40) {
            settings.setTextZoom(newZoom);
            saveTextZoomPref();
            return true;
        } else {
            return false;
        }
    }

    public void resetTextZoom() {
        getSettings().setTextZoom(100);
        saveTextZoomPref();
    }


    @Override
    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        beforeLoadUrl(url);
        super.loadUrl(url, additionalHttpHeaders);
    }

    @Override
    public void loadUrl(String url) {
        beforeLoadUrl(url);
        super.loadUrl(url);
    }

    private void beforeLoadUrl(String url) {
        setCurrentSlobIdFromUrl(url);
        if (!url.startsWith("javascript:")) {
            updateBackgrounColor();
        }
    }

    private void updateBackgrounColor() {
        int color = Color.WHITE;
        String preferredStyle = getPreferredStyle().toLowerCase(Locale.ROOT);
        // webview's default background may "show through" before page
        // load started and/or before page's style applies (and even after that if
        // style doesn't explicitly set background).
        // this is a hack to preemptively set "right" background and prevent
        // extra flash
        //
        // TODO Hack it even more - allow style title to include background color spec
        // so that this can work with "strategically" named user css
        if (preferredStyle.contains("night") || preferredStyle.contains("dark")) {
            color = Color.BLACK;
        }
        setBackgroundColor(color);
    }

    private void setCurrentSlobIdFromUrl(String url) {
        if (!url.startsWith("javascript:")) {
            Uri uri = Uri.parse(url);
            BlobDescriptor bd = BlobDescriptor.fromUri(uri);
            if (bd != null) {
                currentSlobId = bd.slobId;
                currentSlobUri = SlobHelper.getInstance().getSlobUri(currentSlobId);
                loadAvailableStylesPref();
            } else {
                currentSlobId = null;
                currentSlobUri = null;
            }
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, String.format("currentSlobId set from url %s to %s, uri %s",
                        url, currentSlobId, currentSlobUri));
            }
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        timer.cancel();
    }

    public class WebViewClient extends WebViewClientCompat {
        private final byte[] EMPTY_BYTE = new byte[0];

        private final Map<String, List<Long>> times = new HashMap<>();

        @Override
        public void onReceivedError(@NonNull WebView view, @NonNull WebResourceRequest request, @NonNull WebResourceErrorCompat error) {
            super.onReceivedError(view, request, error);
            Log.e(TAG, "error while loading article resource: " + request.getUrl() +": " + error.getDescription() );

        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            Log.d(TAG, "onPageStarted: " + url);
            if (url.startsWith("about:")) {
                return;
            }
            List<Long> tsList = times.get(url);
            if (tsList != null) {
                Log.d(TAG, "onPageStarted: already ready seen " + url);
                tsList.add(System.currentTimeMillis());
            } else {
                tsList = new ArrayList<>();
                times.put(url, tsList);
                tsList.add(System.currentTimeMillis());
                view.loadUrl("javascript:" + StyleJsUtils.getStyleSwitcherJs());
                try {
                    timer.schedule(applyStylePref, 250, 200);
                } catch (IllegalStateException ex) {
                    Log.w(TAG, "Failed to schedule applyStylePref in view " + view.getId(), ex);
                }
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.d(TAG, "onPageFinished: " + url);
            if (!Utils.isNightMode(view.getContext())) {
                view.setBackgroundColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurface));
            }
            if (url.startsWith("about:")) {
                return;
            }
            List<Long> tsList = times.get(url);
            if (tsList != null) {
                long ts = tsList.remove(tsList.size() - 1);
                Log.d(TAG, "onPageFinished: finished: " + url + " in " + (System.currentTimeMillis() - ts));
                if (tsList.isEmpty()) {
                    Log.d(TAG, "onPageFinished: really done with " + url);
                    times.remove(url);
                    applyStylePref.cancel();
                }
            } else {
                Log.w(TAG, "onPageFinished: Unexpected page finished event for " + url);
            }
            view.loadUrl("javascript:" + StyleJsUtils.getStyleSwitcherJs() + ";$SLOB.setStyleTitles($styleSwitcher.getTitles())");
            applyStylePref();
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (uri.isRelative()) {
                return null;
            }
            String host = uri.getHost();
            if (host == null || host.toLowerCase(Locale.ROOT).equals(LOCALHOST)) {
                return null;
            }
            if (allowRemoteContent()) {
                return null;
            }
            return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(EMPTY_BYTE));
        }

        @Override
        public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
            Uri uri = request.getUrl();
            Log.d(TAG, String.format("shouldOverrideUrlLoading: %s (current %s)", uri, view.getUrl()));
            String scheme = uri.getScheme();
            String host = uri.getHost();

            if (isExternal(uri)) {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
                getContext().startActivity(browserIntent);
                return true;
            }

            String fragment = uri.getFragment();
            if (fragment != null) {
                Uri current = Uri.parse(view.getUrl());
                Log.d(TAG, "shouldOverrideUrlLoading URL with fragment: " + uri);
                if (scheme.equals(current.getScheme()) &&
                        host.equals(current.getHost()) &&
                        uri.getPort() == current.getPort() &&
                        uri.getPath().equals(current.getPath())) {
                    Log.d(TAG, "NOT overriding loading of same page link " + uri);
                    return false;
                }
            }

            if (scheme.equals("http") && host.equals(LOCALHOST) && uri.getQueryParameter("blob") == null) {
                Intent intent = new Intent(getContext(), ArticleCollectionActivity.class);
                intent.setData(uri);
                getContext().startActivity(intent);
                Log.d(TAG, "Overriding loading of " + uri);
                return true;
            }
            Log.d(TAG, "NOT overriding loading of " + uri);
            return false;
        }
    }
}
