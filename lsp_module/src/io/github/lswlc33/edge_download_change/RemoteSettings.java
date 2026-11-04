package io.github.lswlc33.edge_download_change;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;

/**
 * Injected side channel to the module app.
 *
 * Settings come from three places, in order of availability:
 *
 * <ol>
 *   <li>a cache written into <b>Edge's own</b> prefs ({@value #PREFS_CACHE}) every time a real
 *       source answered - this is what keeps the configured downloader across a cold start
 *       while the module app cannot be reached;</li>
 *   <li>the framework's remote preferences (read-only, and empty for this module because the
 *       app stores its settings in a plain private file);</li>
 *   <li>the module app's ContentProvider - the live source, but Android refuses to start a
 *       stopped app's provider, which is the state right after the module was installed or
 *       updated, so this path is retried in the background instead of falling back to the
 *       default downloader.</li>
 * </ol>
 *
 * Status and log lines are pushed back through the provider and end up in the app's log view.
 * A status report is retried until it is delivered, so the app does not stay on "not
 * activated" just because the report happened during the stopped-app window.
 */
final class RemoteSettings {

    private static final long CACHE_MS = 3_000L;
    private static final long FLUSH_DELAY_MS = 1_200L;
    private static final long HEARTBEAT_MS = 60_000L;
    /** First heartbeat goes out soon so the app shows the activation without waiting a minute. */
    private static final long FIRST_HEARTBEAT_MS = 3_000L;
    private static final long STATE_RETRY_MS = 2_000L;
    private static final int MAX_BUFFER_LINES = 400;

    /**
     * Last settings that were actually readable, kept in Edge's own shared preferences.
     *
     * Device report: after installing the module the app is in the stopped state, so the
     * provider call fails; the injected side then fell back to the "system downloader"
     * default and every download went there until the user opened the module app.
     */
    private static final String PREFS_CACHE = "edc_settings_cache";

    private static final ArrayDeque<String> BUFFER = new ArrayDeque<String>();
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static volatile boolean sEnabled = true;
    private static volatile String sDownloader = Downloaders.ID_SYSTEM;
    private static volatile String sCustomPackage = "";
    private static volatile long sFetchedAt;
    private static volatile boolean sCacheLoaded;
    private static volatile boolean sFlushScheduled;
    private static volatile boolean sHeartbeatStarted;
    private static volatile boolean sStateRetryScheduled;
    private static volatile String sPendingStateEvent;
    private static volatile String sPendingStateDetail;
    private static volatile int sStateAttempts;
    private static volatile long sProviderBrokenAt;
    private static volatile String sLastSettingsLog;

    /** Provider refresh scheduling (independent of the log backoff). */
    private static volatile boolean sSyncScheduled;
    private static volatile int sSyncAttempts;

    /** Retry the provider after this long even if it failed before (app may have been started). */
    private static final long PROVIDER_RETRY_MS = 30_000L;
    private static final int MAX_STATE_ATTEMPTS = 30;

    private RemoteSettings() {}

    // ------------------------------------------------------------------ settings

    static boolean enabled() {
        refreshIfStale();
        return sEnabled;
    }

    static String downloaderId() {
        refreshIfStale();
        return sDownloader;
    }

    static String customPackage() {
        refreshIfStale();
        return sCustomPackage;
    }

    /** Human readable name of the currently selected download target. */
    static String downloaderLabel() {
        return Downloaders.labelOf(downloaderId(), customPackage());
    }

    /**
     * Loads the settings ahead of the first download (called once the hooks are in place), so
     * a first-time provider failure has a chance to be retried before the user taps a link.
     */
    static void prefetch() {
        refreshIfStale();
    }

    private static void refreshIfStale() {
        loadCacheOnce();
        if (SystemClock.elapsedRealtime() - sFetchedAt < CACHE_MS) return;
        sFetchedAt = SystemClock.elapsedRealtime();
        if (readRemotePreferences()) {
            saveCache();
            return;
        }
        // The provider is the only live source; in the stopped-app window the call fails, so
        // it is retried with a short backoff instead of leaving the default in place.
        scheduleSync();
    }

    /**
     * @return true when the framework had real values for us. An empty remote preference set
     *         is not "loaded": the module app never publishes into the framework store (its
     *         settings are a plain private SharedPreferences file), so treating the empty
     *         result as authoritative is what pinned the downloader to "system".
     */
    private static boolean readRemotePreferences() {
        try {
            XposedInterface api = DownloadHooks.api();
            if (api == null) return false;
            SharedPreferences prefs = api.getRemotePreferences(BuildInfo.PREFS_SETTINGS);
            if (!prefs.contains(BuildInfo.KEY_DOWNLOADER)
                    && !prefs.contains(BuildInfo.KEY_ENABLED)) {
                logSettingsOnce("remote preferences empty -> provider is the source");
                return false;
            }
            boolean changed = apply(prefs.getBoolean(BuildInfo.KEY_ENABLED, sEnabled),
                    prefs.getString(BuildInfo.KEY_DOWNLOADER, sDownloader),
                    prefs.getString(BuildInfo.KEY_CUSTOM_PACKAGE, sCustomPackage));
            if (changed) logSettings("remote preferences");
            return true;
        } catch (Throwable t) {
            logSettingsOnce("remote preferences unavailable: " + t);
            return false;
        }
    }

    private static void loadCacheOnce() {
        if (sCacheLoaded) return;
        Context context = DownloadHooks.application();
        if (context == null) return;      // retried on the next call
        sCacheLoaded = true;
        try {
            SharedPreferences cache = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE);
            if (!cache.contains(BuildInfo.KEY_DOWNLOADER)) return;
            boolean changed = apply(cache.getBoolean(BuildInfo.KEY_ENABLED, sEnabled),
                    cache.getString(BuildInfo.KEY_DOWNLOADER, sDownloader),
                    cache.getString(BuildInfo.KEY_CUSTOM_PACKAGE, sCustomPackage));
            if (changed) logSettings("cache");
        } catch (Throwable t) {
            logSettingsOnce("settings cache unreadable: " + t);
        }
    }

    private static void saveCache() {
        Context context = DownloadHooks.application();
        if (context == null) return;
        try {
            context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE).edit()
                    .putBoolean(BuildInfo.KEY_ENABLED, sEnabled)
                    .putString(BuildInfo.KEY_DOWNLOADER, sDownloader)
                    .putString(BuildInfo.KEY_CUSTOM_PACKAGE, sCustomPackage)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** Merges a settings bundle and returns true when something actually changed. */
    private static boolean apply(boolean enabled, String downloader, String custom) {
        boolean changed = enabled != sEnabled
                || (downloader != null && downloader.length() > 0 && !downloader.equals(sDownloader))
                || (custom != null && !custom.equals(sCustomPackage));
        sEnabled = enabled;
        if (downloader != null && downloader.length() > 0) sDownloader = downloader;
        if (custom != null) sCustomPackage = custom;
        return changed;
    }

    private static void applySettings(Bundle values) {
        if (values == null) return;
        boolean changed = apply(values.getBoolean("enabled", sEnabled),
                values.getString("downloader"), values.getString("custom"));
        if (changed) logSettings("module app");
    }

    private static void logSettings(String source) {
        StringBuilder sb = new StringBuilder("settings from ").append(source)
                .append(": enabled=").append(sEnabled)
                .append(", downloader=").append(sDownloader);
        if (sCustomPackage.length() > 0) sb.append(", custom=").append(sCustomPackage);
        String line = sb.toString();
        if (line.equals(sLastSettingsLog)) return;
        sLastSettingsLog = line;
        log(line);
    }

    private static void logSettingsOnce(String message) {
        if (message.equals(sLastSettingsLog)) return;
        sLastSettingsLog = message;
        log(message);
    }

    /**
     * Queries the provider with backoff (0.5 s, 1 s, 2 s, ... capped at 15 s) until it answers.
     * It can answer late - the module app may be started by a later launch or by the user -
     * so the loop does not give up permanently.
     */
    private static void scheduleSync() {
        synchronized (RemoteSettings.class) {
            if (sSyncScheduled) return;
            sSyncScheduled = true;
            sSyncAttempts++;
            int shift = Math.min(sSyncAttempts - 1, 5);
            long delay = Math.min(500L << shift, 15_000L);
            Bg.postDelayed(new Runnable() {
                @Override
                public void run() {
                    synchronized (RemoteSettings.class) {
                        sSyncScheduled = false;
                    }
                    Bundle values = queryProvider();
                    if (values != null) {
                        applySettings(values);
                        sFetchedAt = SystemClock.elapsedRealtime();
                        sSyncAttempts = 0;
                        saveCache();
                    } else {
                        scheduleSync();
                    }
                }
            }, delay);
        }
    }

    private static Bundle queryProvider() {
        Context context = DownloadHooks.application();
        if (context == null) return null;
        try {
            return context.getContentResolver().call(
                    Uri.parse("content://" + BuildInfo.AUTHORITY), BuildInfo.METHOD_SETTINGS, null, null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ reporting

    /** Reports the injected process state (drives the app's activation indicator). */
    static void reportState(String event, String detail) {
        sPendingStateEvent = event;
        sPendingStateDetail = detail;
        // A new event starts a new retry budget (the heartbeat calls this once a minute, so
        // the app keeps getting a chance to learn that the module is alive).
        sStateAttempts = 0;
        Context context = DownloadHooks.application();
        if (context == null) {
            // Edge's Application does not exist yet during early startup; keep the report
            // and retry as soon as a context is available instead of losing it.
            DownloadHooks.log(4, "state report deferred (no context yet): " + event);
            scheduleStateRetry();
            return;
        }
        sendState(context, event, detail);
    }

    /** Called when Edge's application context first becomes available. */
    static void onContextAvailable() {
        retryPendingState();
        flushNow();
    }

    private static void scheduleStateRetry() {
        if (sStateRetryScheduled || sStateAttempts >= MAX_STATE_ATTEMPTS) return;
        sStateRetryScheduled = true;
        sStateAttempts++;
        Bg.postDelayed(new Runnable() {
            @Override
            public void run() {
                sStateRetryScheduled = false;
                retryPendingState();
            }
        }, STATE_RETRY_MS);
    }

    private static void retryPendingState() {
        final String event = sPendingStateEvent;
        if (event == null) return;
        final Context context = DownloadHooks.application();
        if (context == null) {
            scheduleStateRetry();
            return;
        }
        sendState(context, event, sPendingStateDetail);
    }

    private static void sendState(final Context context, final String event, final String detail) {
        final String process = DownloadHooks.processName;
        Bg.run(new Runnable() {
            @Override
            public void run() {
                // State reports bypass the log backoff: the app's activation indicator is
                // what tells the user whether the module works at all.
                if (send(context, event, detail, process, frameworkString(), true)) {
                    sStateAttempts = 0;
                    if (event.equals(sPendingStateEvent)) {
                        sPendingStateEvent = null;
                        sPendingStateDetail = null;
                    }
                } else {
                    scheduleStateRetry();
                }
            }
        });
    }

    static void startHeartbeat() {
        if (sHeartbeatStarted) return;
        sHeartbeatStarted = true;
        // First beat soon after startup (so the app shows the activation quickly), then
        // once a minute.
        Bg.postDelayed(new Runnable() {
            @Override
            public void run() {
                reportState("heartbeat", "alive");
                Bg.postDelayed(this, HEARTBEAT_MS);
            }
        }, FIRST_HEARTBEAT_MS);
    }

    private static String frameworkString() {
        try {
            XposedInterface api = DownloadHooks.api();
            if (api != null) {
                return api.getFrameworkName() + " " + api.getFrameworkVersion()
                        + " (API " + api.getApiVersion() + ")";
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** Queues a log line; batched delivery keeps the provider calls cheap. */
    static void log(String message) {
        synchronized (BUFFER) {
            while (BUFFER.size() >= MAX_BUFFER_LINES) BUFFER.pollFirst();
            BUFFER.addLast(TIME.format(new Date()) + "  " + message);
        }
        if (sFlushScheduled) return;
        sFlushScheduled = true;
        Bg.postDelayed(new Runnable() {
            @Override
            public void run() {
                sFlushScheduled = false;
                flushNow();
            }
        }, FLUSH_DELAY_MS);
    }

    /** Flushes buffered log lines immediately (used when a context becomes available). */
    static void flushNow() {
        final String payload = takePending();
        if (payload == null) return;
        Bg.run(new Runnable() {
            @Override
            public void run() {
                deliver(payload);
            }
        });
    }

    private static String takePending() {
        synchronized (BUFFER) {
            if (BUFFER.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            while (!BUFFER.isEmpty()) {
                sb.append(BUFFER.pollFirst()).append('\n');
            }
            return sb.toString();
        }
    }

    private static void deliver(String payload) {
        final Context context = DownloadHooks.application();
        if (context == null) {
            // Very early in Edge's startup there is no context yet; keep the lines.
            requeue(payload);
            return;
        }
        if (isProviderBroken()) {
            requeue(payload);
            return;
        }
        if (!send(context, "log", payload, DownloadHooks.processName, frameworkString(), false)) {
            requeue(payload);
        }
    }

    private static void requeue(String payload) {
        String[] lines = payload.split("\n");
        synchronized (BUFFER) {
            for (int i = Math.max(0, lines.length - MAX_BUFFER_LINES); i < lines.length; i++) {
                if (lines[i].length() > 0) BUFFER.addLast(lines[i]);
            }
        }
    }

    private static boolean isProviderBroken() {
        long brokenAt = sProviderBrokenAt;
        return brokenAt != 0 && SystemClock.elapsedRealtime() - brokenAt < PROVIDER_RETRY_MS;
    }

    /**
     * @param force bypass the log backoff (used for state reports); still records the failure
     *              so log lines do not hammer a dead provider.
     */
    private static boolean send(Context context, String event, String text, String process,
            String framework, boolean force) {
        if (!force && isProviderBroken()) return false;
        try {
            Bundle extras = new Bundle();
            extras.putString("event", event);
            if (text != null) extras.putString("text", text);
            if (process != null) extras.putString("process", process);
            if (framework != null) extras.putString("framework", framework);
            Bundle response = context.getContentResolver().call(
                    Uri.parse("content://" + BuildInfo.AUTHORITY), BuildInfo.METHOD_REPORT, null, extras);
            sProviderBrokenAt = 0;
            // The provider answers with the current settings, keeping this side in sync
            // without an extra round trip.
            applySettings(response);
            sFetchedAt = SystemClock.elapsedRealtime();
            sSyncAttempts = 0;
            saveCache();
            return true;
        } catch (Throwable t) {
            // The module app may be force-stopped (or just installed); back off instead of
            // retrying every line. State reports have their own retry loop.
            sProviderBrokenAt = SystemClock.elapsedRealtime();
            return false;
        }
    }
}
