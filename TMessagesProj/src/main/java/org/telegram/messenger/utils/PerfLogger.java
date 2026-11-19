package org.telegram.messenger.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.system.Os;
import android.system.OsConstants;
import android.view.FrameMetrics;
import android.view.Window;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Local performance logger for battery/smoothness testing.
 * Writes once per second to Download/NagramLogs/nagram_perf_<time>.txt. Nothing is sent anywhere.
 * Columns: time, cpu% (this process), frames, avg/max frame ms, frames over 33 ms, battery current (uA), battery %, refresh rate.
 */
public final class PerfLogger {

    private static final String PREFS = "systemConfig";
    private static final String KEY_ENABLED = "perfLogEnabled";
    private static final String DIR_NAME = "NagramLogs";
    private static final long SAMPLE_MS = 1000L;
    private static final long SLOW_FRAME_NS = 33_000_000L;

    private static final Object lock = new Object();
    private static Context appContext;
    private static HandlerThread thread;
    private static Handler handler;
    private static BufferedWriter writer;
    private static String fileName;
    private static boolean running;

    private static Window attachedWindow;
    private static Window.OnFrameMetricsAvailableListener frameListener;

    // frame stats accumulated between samples (guarded by lock)
    private static long frames;
    private static long slowFrames;
    private static long frameSumNs;
    private static long frameMaxNs;

    private static long lastCpuTicks = -1;
    private static long lastSampleMs;

    // current screen (set from BaseFragment.onResume) and per-thread CPU bookkeeping
    private static volatile String screen = "-";
    private static long gpuSumNs;
    private static final java.util.HashMap<Integer, Long> lastThreadTicks = new java.util.HashMap<>();

    /** Name of the fragment currently shown. Called from BaseFragment.onResume. */
    public static void setScreen(String name) {
        String n = name == null ? "-" : name;
        if (n.equals(screen)) return;
        screen = n;
        synchronized (lock) {
            if (running) {
                writeLine("EVENT time=" + timeNow() + " screen=" + n);
            }
        }
    }

    private static String timeNow() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private static final Runnable sampler = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                sample();
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (handler != null) handler.postDelayed(this, SAMPLE_MS);
        }
    };

    private PerfLogger() {
    }

    public static boolean isEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) {
            start(context);
        } else {
            stop();
        }
    }

    /** Starts a new log file. Safe to call repeatedly; no-op if already running. */
    public static void start(Context context) {
        synchronized (lock) {
            if (running) return;
            appContext = context.getApplicationContext();
            try {
                writer = openWriter();
            } catch (Exception e) {
                FileLog.e(e);
                return;
            }
            thread = new HandlerThread("PerfLogger");
            thread.start();
            handler = new Handler(thread.getLooper());
            running = true;
            resetFrameStats();
            lastCpuTicks = -1;
            lastThreadTicks.clear();
            lastSampleMs = System.currentTimeMillis();
            writeLine("# Nagram perf log. Started " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            writeLine("# One line per second. Fields:");
            writeLine("# cpu = CPU of this app process, % of one core (200 = two cores busy)");
            writeLine("# frames = frame callbacks received in the last second (at 120 Hz a single window gives at most ~120)");
            writeLine("# frame_avg_ms / frame_max_ms = frame time; a frame over ~8.3 ms at 120 Hz is a dropped frame");
            writeLine("# gpu_avg_ms = GPU time per frame; high value = GPU-heavy effects (blur, shaders, big layers)");
            writeLine("# slow = frames over 33 ms plus dropped frames; these are visible stutters");
            writeLine("# uA = battery current in microamps (BATTERY_PROPERTY_CURRENT_NOW; sign depends on device)");
            writeLine("# batt = battery %, charge = charging state, temp_C = battery temperature");
            writeLine("# screen_on / thermal (0 none,1 light,2 moderate,3 severe...) = device state");
            writeLine("# rss_MB = app memory; net_rx_KB / net_tx_KB = network traffic of this app in the last second");
            writeLine("# hz = max screen refresh rate requested; threads = top 3 threads by CPU (name:cpu%)");
            writeLine("# screen = currently shown fragment; EVENT lines mark screen changes");
            handler.postDelayed(sampler, SAMPLE_MS);
        }
    }

    public static void stop() {
        synchronized (lock) {
            if (!running) return;
            running = false;
            detachWindowLocked();
            if (handler != null) handler.removeCallbacks(sampler);
            if (thread != null) thread.quitSafely();
            thread = null;
            handler = null;
            closeWriter();
        }
    }

    /** Subscribe to frame timings of the given window (call from Activity onResume / after fragment change). */
    public static void attachWindow(Window window) {
        synchronized (lock) {
            if (!running || window == null) return;
            detachWindowLocked();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
            frameListener = (w, metrics, dropCountSinceLastInvocation) -> onFrame(metrics, dropCountSinceLastInvocation);
            attachedWindow = window;
            window.addOnFrameMetricsAvailableListener(frameListener, handler);
        }
    }

    public static void detachWindow() {
        synchronized (lock) {
            detachWindowLocked();
        }
    }

    private static void detachWindowLocked() {
        if (attachedWindow != null && frameListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                attachedWindow.removeOnFrameMetricsAvailableListener(frameListener);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        attachedWindow = null;
        frameListener = null;
    }

    private static void onFrame(FrameMetrics metrics, int dropped) {
        long ns = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
        long gpuNs = metrics.getMetric(FrameMetrics.GPU_DURATION);
        synchronized (lock) {
            frames++;
            frameSumNs += ns;
            gpuSumNs += gpuNs;
            if (ns > frameMaxNs) frameMaxNs = ns;
            if (ns > SLOW_FRAME_NS) slowFrames++;
            slowFrames += Math.max(0, dropped);
        }
    }

    private static void resetFrameStats() {
        synchronized (lock) {
            frames = 0;
            slowFrames = 0;
            frameSumNs = 0;
            frameMaxNs = 0;
            gpuSumNs = 0;
        }
    }

    private static void sample() {
        long now = System.currentTimeMillis();
        double elapsedSec = Math.max(0.001, (now - lastSampleMs) / 1000.0);
        lastSampleMs = now;

        // CPU of this process: utime+stime from /proc/self/stat (fields 14,15), in clock ticks
        double cpuPct = Double.NaN;
        long ticks = readCpuTicks();
        if (ticks >= 0) {
            if (lastCpuTicks >= 0) {
                long clk = Os.sysconf(OsConstants._SC_CLK_TCK);
                cpuPct = 100.0 * ((ticks - lastCpuTicks) / (double) clk) / elapsedSec;
            }
            lastCpuTicks = ticks;
        }

        long frameCount;
        long slow;
        long sumNs;
        long maxNs;
        long gpuSum;
        synchronized (lock) {
            frameCount = frames;
            slow = slowFrames;
            sumNs = frameSumNs;
            maxNs = frameMaxNs;
            gpuSum = gpuSumNs;
            resetFrameStats();
        }
        double avgMs = frameCount > 0 ? sumNs / (double) frameCount / 1_000_000.0 : 0;
        double maxMs = maxNs / 1_000_000.0;
        double gpuAvgMs = frameCount > 0 ? gpuSum / (double) frameCount / 1_000_000.0 : 0;

        long currentUa = 0;
        int capacity = -1;
        int tempTenthsC = Integer.MIN_VALUE;
        String chargeState = "?";
        boolean screenOn = true;
        String thermal = "na";
        if (appContext != null) {
            BatteryManager bm = (BatteryManager) appContext.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                currentUa = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
            android.content.Intent battery = appContext.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                tempTenthsC = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                chargeState = status == BatteryManager.BATTERY_STATUS_CHARGING ? "charging"
                        : status == BatteryManager.BATTERY_STATUS_FULL ? "full"
                        : status == BatteryManager.BATTERY_STATUS_DISCHARGING ? "discharging"
                        : status == BatteryManager.BATTERY_STATUS_NOT_CHARGING ? "not_charging" : "unknown";
            }
            android.os.PowerManager pm = (android.os.PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                screenOn = pm.isInteractive();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    thermal = String.valueOf(pm.getCurrentThermalStatus());
                }
            }
        }
        long rssKb = readRssKb();
        long rxBytes = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid());
        long txBytes = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid());
        long rxDelta = Math.max(0, rxBytes - lastRxBytes);
        long txDelta = Math.max(0, txBytes - lastTxBytes);
        lastRxBytes = rxBytes;
        lastTxBytes = txBytes;

        String topThreads = topThreadsCpu(elapsedSec);

        String line = String.format(Locale.US,
                "time=%s cpu=%s frames=%d frame_avg_ms=%.2f gpu_avg_ms=%.2f frame_max_ms=%.2f slow=%d uA=%d batt=%d%% charge=%s temp_C=%s screen_on=%s thermal=%s rss_MB=%d net_rx_KB=%d net_tx_KB=%d hz=%.0f threads=%s screen=%s",
                new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(now)),
                Double.isNaN(cpuPct) ? "na" : String.format(Locale.US, "%.1f", cpuPct),
                frameCount, avgMs, gpuAvgMs, maxMs, slow, currentUa, capacity, chargeState,
                tempTenthsC == Integer.MIN_VALUE ? "na" : String.format(Locale.US, "%.1f", tempTenthsC / 10.0),
                screenOn, thermal, rssKb / 1024, rxDelta / 1024, txDelta / 1024,
                (double) AndroidUtilities.screenMaxRefreshRate,
                topThreads, screen);
        synchronized (lock) {
            writeLine(line);
        }
    }

    private static long lastRxBytes;
    private static long lastTxBytes;

    private static long readRssKb() {
        try (RandomAccessFile raf = new RandomAccessFile("/proc/self/status", "r")) {
            String line;
            while ((line = raf.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * CPU per thread name over the last sample interval, top 3 by usage, e.g. "RenderThread:45.2|main:12.0|tgnet:5.1".
     * Threads with the same name are summed (tgnet/worker threads are numerous).
     */
    private static String topThreadsCpu(double elapsedSec) {
        long clk;
        try {
            clk = Os.sysconf(OsConstants._SC_CLK_TCK);
        } catch (Exception e) {
            return "na";
        }
        java.util.HashMap<String, Double> byName = new java.util.HashMap<>();
        java.util.HashMap<Integer, Long> currentTicks = new java.util.HashMap<>();
        File[] tasks = new File("/proc/self/task").listFiles();
        if (tasks == null) return "na";
        for (File task : tasks) {
            try {
                int tid = Integer.parseInt(task.getName());
                String comm = readFirstLine(new File(task, "comm")).trim();
                String stat = readFirstLine(new File(task, "stat"));
                int close = stat.lastIndexOf(')');
                String[] parts = stat.substring(close + 2).split(" ");
                long ticks = Long.parseLong(parts[11]) + Long.parseLong(parts[12]);
                currentTicks.put(tid, ticks);
                Long prev = lastThreadTicks.get(tid);
                if (prev == null) continue;
                double pct = 100.0 * ((ticks - prev) / (double) clk) / elapsedSec;
                if (pct <= 0) continue;
                Double sum = byName.get(comm);
                byName.put(comm, (sum == null ? 0 : sum) + pct);
            } catch (Exception ignored) {
            }
        }
        lastThreadTicks.clear();
        lastThreadTicks.putAll(currentTicks);

        StringBuilder sb = new StringBuilder();
        int n = 0;
        while (n < 3 && !byName.isEmpty()) {
            String bestName = null;
            double best = -1;
            for (java.util.Map.Entry<String, Double> e : byName.entrySet()) {
                if (e.getValue() > best) {
                    best = e.getValue();
                    bestName = e.getKey();
                }
            }
            if (bestName == null) break;
            if (sb.length() > 0) sb.append('|');
            sb.append(bestName).append(':').append(String.format(Locale.US, "%.1f", best));
            byName.remove(bestName);
            n++;
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static String readFirstLine(File f) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            return raf.readLine();
        }
    }


    /** utime+stime of this process in clock ticks, or -1 if unavailable. */
    private static long readCpuTicks() {
        try (RandomAccessFile raf = new RandomAccessFile("/proc/self/stat", "r")) {
            String stat = raf.readLine();
            int close = stat.lastIndexOf(')');
            String[] parts = stat.substring(close + 2).split(" ");
            // parts[0] is field 3 (state); utime is field 14 -> index 11; stime field 15 -> index 12
            return Long.parseLong(parts[11]) + Long.parseLong(parts[12]);
        } catch (Exception e) {
            return -1;
        }
    }

    private static void writeLine(String line) {
        if (writer == null) return;
        try {
            writer.write(line);
            writer.write('\n');
            writer.flush();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void closeWriter() {
        if (writer == null) return;
        try {
            writer.close();
        } catch (Exception e) {
            FileLog.e(e);
        }
        writer = null;
    }

    private static BufferedWriter openWriter() throws Exception {
        String name = "nagram_perf_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
        fileName = name;
        OutputStream os;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // MediaStore writes to public Download without MANAGE_EXTERNAL_STORAGE
            ContentResolver resolver = appContext.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("MediaStore insert failed");
            os = resolver.openOutputStream(uri);
            if (os == null) throw new IllegalStateException("openOutputStream failed");
            FileLog.d("PerfLogger: writing Download/" + DIR_NAME + "/" + name);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            os = new FileOutputStream(new File(dir, name));
            FileLog.d("PerfLogger: writing " + dir + "/" + name);
        }
        return new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));
    }

    public static String getCurrentFileName() {
        return fileName;
    }
}
