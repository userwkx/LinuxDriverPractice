package com.kieran.ledcontroller;

import android.annotation.SuppressLint;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hosts the WebView-based UI so the Android side only needs to bridge hardware data to JavaScript.
 */
public class MainActivity extends AppCompatActivity {

    private static final boolean ENABLE_POLLING = true;
    private static final long PHYSICAL_CLICK_POLL_INTERVAL_MS = 1000L;
    private static final String PHYSICAL_CLICK_NODE = "/proc/coordinator";
    private static final String LIGHT_MODE_ASSET = "file:///android_asset/index_light.html";
    private static final String DARK_MODE_ASSET = "file:///android_asset/index_black.html";

    private static final Pattern FIRST_NUMBER_PATTERN = Pattern.compile("(\\d+)");

    private LedController ledController;
    private WebView webView;
    private Button themeToggleButton;
    private boolean darkModeEnabled;
    private boolean pageReady;
    private String pendingStatePayload;
    private Integer pendingPhysicalClicks;
    private final ScheduledExecutorService physicalClickExecutor =
            Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> physicalClickFuture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        themeToggleButton = findViewById(R.id.themeToggleButton);
        ledController = new LedController();
        setupThemeToggleButton();
        setupWebView();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (ENABLE_POLLING) {
            ledController.startStateMonitor(
                    state -> runOnUiThread(() -> handleLedState(state)));
        }
        startPhysicalClickMonitor();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ledController.stopStateMonitor();
        stopPhysicalClickMonitor();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ledController.release();
        stopPhysicalClickMonitor();
        physicalClickExecutor.shutdownNow();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webView.setBackgroundColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            WebView.setWebContentsDebuggingEnabled(debuggable);
        }
        webView.addJavascriptInterface(new WebAppBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                flushPendingPayloads();
            }
        });
        loadCurrentTheme();
    }

    private void setupThemeToggleButton() {
        if (themeToggleButton == null) {
            return;
        }
        updateThemeButtonLabel();
        themeToggleButton.setOnClickListener(v -> {
            darkModeEnabled = !darkModeEnabled;
            loadCurrentTheme();
        });
    }

    private void loadCurrentTheme() {
        if (webView == null) {
            return;
        }
        pageReady = false;
        String assetToLoad = darkModeEnabled ? DARK_MODE_ASSET : LIGHT_MODE_ASSET;
        webView.loadUrl(assetToLoad);
        updateThemeButtonLabel();
    }

    private void updateThemeButtonLabel() {
        if (themeToggleButton == null) {
            return;
        }
        int labelRes = darkModeEnabled
                ? R.string.switch_to_light_mode
                : R.string.switch_to_dark_mode;
        themeToggleButton.setText(labelRes);
        themeToggleButton.setContentDescription(getString(labelRes));
    }

    private void handleLedState(LedState state) {
        if (state == null) {
            return;
        }

        try {
            JSONObject payload = new JSONObject();
            String rawState = ledController.getLastRawState();
            payload.put("modeRaw", state.getMode());
            payload.put("mode", mapModeToDisplay(state.getMode()));
            payload.put("level", state.getLevel());
            payload.put("delayOn", state.getDelayOn());
            payload.put("delayOff", state.getDelayOff());
            payload.put("raw", rawState == null ? "" : rawState.trim());
            pendingStatePayload = payload.toString();
        } catch (JSONException ignored) {
            pendingStatePayload = null;
        }

        if (pageReady) {
            pushLedState();
        }
    }

    private String mapModeToDisplay(String mode) {
        if (mode == null) {
            return "OFF";
        }
        switch (mode.toLowerCase(Locale.US)) {
            case "on":
                return "ON";
            case "off":
                return "OFF";
            case "blink":
                return "BLINK";
            case "breath":
            case "breathe":
            case "breathing":
                return "BREATH";
            default:
                return mode.toUpperCase(Locale.US);
        }
    }

    private void pushLedState() {
        if (webView == null || pendingStatePayload == null) {
            return;
        }
        webView.evaluateJavascript("window.applyState(" + pendingStatePayload + ")", null);
    }

    private void startPhysicalClickMonitor() {
        if (physicalClickFuture != null && !physicalClickFuture.isCancelled()) {
            return;
        }
        physicalClickFuture = physicalClickExecutor.scheduleWithFixedDelay(() -> {
            int count = readPhysicalClickCount();
            runOnUiThread(() -> dispatchPhysicalClicks(count));
        }, 0, PHYSICAL_CLICK_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopPhysicalClickMonitor() {
        if (physicalClickFuture != null) {
            physicalClickFuture.cancel(true);
            physicalClickFuture = null;
        }
    }

    private int readPhysicalClickCount() {
        try (BufferedReader reader = new BufferedReader(new FileReader(PHYSICAL_CLICK_NODE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = FIRST_NUMBER_PATTERN.matcher(line);
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            // fall through to return last known value
        }
        return pendingPhysicalClicks != null ? pendingPhysicalClicks : 0;
    }

    private void dispatchPhysicalClicks(int count) {
        pendingPhysicalClicks = count;
        if (!pageReady || webView == null) {
            return;
        }
        webView.evaluateJavascript("updatePhysicalCount(" + count + ")", null);
    }

    private void flushPendingPayloads() {
        if (pendingStatePayload != null) {
            pushLedState();
        }
        if (pendingPhysicalClicks != null && webView != null) {
            webView.evaluateJavascript("updatePhysicalCount(" + pendingPhysicalClicks + ")", null);
        }
    }

    private final class WebAppBridge {

        @JavascriptInterface
        public void controlLed(String rawCommand) {
            if (rawCommand == null || ledController == null) {
                return;
            }
            String command = rawCommand.trim().toUpperCase(Locale.US);
            switch (command) {
                case "ON":
                    ledController.turnOn();
                    break;
                case "OFF":
                    ledController.turnOff();
                    break;
                case "BLINK":
                    ledController.blink();
                    break;
                case "BREATH":
                    ledController.breath();
                    break;
                default:
                    break;
            }
        }

        @JavascriptInterface
        public void requestStatusSync() {
            runOnUiThread(MainActivity.this::flushPendingPayloads);
        }
    }
}
