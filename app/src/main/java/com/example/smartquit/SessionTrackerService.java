package com.example.smartquit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Vibrator;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;

public class SessionTrackerService extends Service {

    private Handler handler;
    private String lastForegroundApp = "NULL";
    private long lastAppChangeTime = System.currentTimeMillis();
    private AppDatabase db;
    private String userId = "user_001";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "SessionTrackerChannel";
    private static final long VIBRATION_TRIGGER_DURATION = 1 * 30 * 1000; // 30 sec
    private static final long VIBRATION_DURATION = 30 * 1000; // 30 seconds continuous vibration
    private static final long VIBRATION_CYCLE_INTERVAL = 60 * 1000; // 60 seconds (vibrate 30s, silent 30s, repeat)
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_APPS_TO_MONITOR = "apps_to_monitor";
    private static final String KEY_TEST_MODE = "test_mode";
    private java.util.List<String> appsToMonitor = new ArrayList<>();
    private boolean isVibrating = false;
    private Vibrator vibrator;
    
    // Vibration tracking
    private int numVibrationsInSession = 0;  // Count vibrations in current app session
    private String appBeingVibratedFor = "";  // App that triggered vibration
    private boolean userLeftDuringVibration = false;  // Flag if user left app during vibration

    @Override
    public void onCreate() {
        super.onCreate();
        db = AppDatabase.getDatabase(this);
        handler = new Handler();
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        loadAppsToMonitor();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        Log.d("SessionTrackerService", "Service created and started in foreground");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("SessionTrackerService", "onStartCommand called");
        startTracking();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startTracking() {
        final int delay = 1000; // 1 second

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                String currentApp = getForegroundTask();
                Log.d("SessionTrackerService", "Current foreground app: " + currentApp);

                // Track app session changes
                if (!currentApp.equals(lastForegroundApp) && !lastForegroundApp.equals("NULL")) {
                    long currentTime = System.currentTimeMillis();
                    long durationSeconds = (currentTime - lastAppChangeTime) / 1000;

                    // Check if user left the monitored app during vibration
                    if (isVibrating && !appBeingVibratedFor.equals(currentApp)) {
                        userLeftDuringVibration = true;
                        Log.d("SessionTrackerService", "User left " + appBeingVibratedFor + " during vibration");
                    }

                    // Save the previous app session to database with vibration info
                    saveAppSession(lastForegroundApp, durationSeconds);
                    
                    // Reset vibration counters for new session
                    numVibrationsInSession = 0;
                    userLeftDuringVibration = false;
                    
                    // Stop vibration when app changes
                    stopVibration();
                }

                if (!currentApp.equals(lastForegroundApp)) {
                    lastForegroundApp = currentApp;
                    lastAppChangeTime = System.currentTimeMillis();
                }

                // Check if current app should trigger vibration
                long appDurationMillis = System.currentTimeMillis() - lastAppChangeTime;
                if (isAppInMonitorList(currentApp) && !isVibrating && shouldTriggerVibration(currentApp, appDurationMillis)) {
                    startVibration(currentApp);
                }

                handler.postDelayed(this, delay);
            }
        }, delay);
    }

    private String getForegroundTask() {
        String currentApp = "NULL";
        try {
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            long time = System.currentTimeMillis();
            java.util.List<android.app.usage.UsageStats> appList = usm.queryUsageStats(
                    android.app.usage.UsageStatsManager.INTERVAL_DAILY, time - 10000 * 1000, time);
            if (appList != null && appList.size() > 0) {
                java.util.SortedMap<Long, android.app.usage.UsageStats> mySortedMap = new java.util.TreeMap<>();
                for (android.app.usage.UsageStats usageStats : appList) {
                    mySortedMap.put(usageStats.getLastTimeUsed(), usageStats);
                }
                if (mySortedMap != null && !mySortedMap.isEmpty()) {
                    currentApp = mySortedMap.get(mySortedMap.lastKey()).getPackageName();
                }
            }
        } catch (Exception e) {
            Log.e("SessionTrackerService", "Error getting foreground task", e);
        }
        Log.d("SessionTrackerService", "Current App in foreground is: " + currentApp);
        return currentApp;
    }

    private void saveAppSession(String appName, long durationSeconds) {
        long currentTime = System.currentTimeMillis();
        long startTime = currentTime - (durationSeconds * 1000);

        String startTimeISO = getISODateTime(startTime);
        String endTimeISO = getISODateTime(currentTime);
        String dateISO = getISODate(currentTime);

        AppSession session = new AppSession(userId, appName, startTimeISO, endTimeISO, durationSeconds, dateISO);
        
        // Set vibration and compliance data
        session.numVibrations = numVibrationsInSession;
        session.userComplied = userLeftDuringVibration;
        
        Log.d("SessionTrackerService", "Session details: app=" + appName + ", vibrations=" + numVibrationsInSession + ", userComplied=" + userLeftDuringVibration);

        new Thread(() -> {
            db.appSessionDao().insertSession(session);
            Log.d("SessionTrackerService", "Saved session: " + session.toString());
        }).start();
    }

    /**
     * Load apps to monitor from SharedPreferences
     */
    private void loadAppsToMonitor() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String appsJson = prefs.getString(KEY_APPS_TO_MONITOR, "[]");
            JSONArray jsonArray = new JSONArray(appsJson);
            appsToMonitor.clear();
            for (int i = 0; i < jsonArray.length(); i++) {
                appsToMonitor.add(jsonArray.getString(i));
            }
            Log.d("SessionTrackerService", "Loaded apps to monitor: " + appsToMonitor.toString());
        } catch (JSONException e) {
            Log.e("SessionTrackerService", "Error loading apps to monitor", e);
            appsToMonitor = new ArrayList<>();
        }
    }

    /**
     * Check if an app is in the monitored apps list
     */
    private boolean isAppInMonitorList(String appPackageName) {
        return appsToMonitor.contains(appPackageName);
    }

    /**
     * Determine if vibration should trigger based on config mode and app duration
     * Test mode: vibrate after 1 min, 3 min, 5 min, etc. (1 + 2*numVibrations)
     * Production mode: vibrate after short_session_threshold_seconds with 50% randomness
     */
    private boolean shouldTriggerVibration(String currentApp, long appDurationMillis) {
        boolean isTestMode = getTestModePreference();
        
        if (isTestMode) {
            // Test mode: use original logic (1 + 2*numVibrations minutes)
            return appDurationMillis >= (1 + 2L * numVibrationsInSession) * VIBRATION_TRIGGER_DURATION;
        } else {
            // Production mode: use baseline stats
            RetrofitApiService.BaselineStats baselineStats = ModelStorageService.getBaselineStats(this);
            
            if (baselineStats == null) {
                // No baseline stats available, don't trigger vibration
                Log.d("SessionTrackerService", "No baseline stats available, skipping vibration");
                return false;
            }
            
            // Use short_session_threshold_seconds with 50% randomness
            long thresholdMillis = (long) (baselineStats.short_session_threshold_seconds * 1000);
            
            // Add 50% randomness (±25%)
            double randomFactor = 0.75 + (Math.random() * 0.5); // Range: 0.75 to 1.25
            long adjustedThresholdMillis = (long) (thresholdMillis * randomFactor);
            
            Log.d("SessionTrackerService", "Production mode - threshold: " + thresholdMillis + "ms, " +
                    "random factor: " + String.format("%.2f", randomFactor) + ", " +
                    "adjusted: " + adjustedThresholdMillis + "ms, " +
                    "app duration: " + appDurationMillis + "ms");
            
            return appDurationMillis >= adjustedThresholdMillis;
        }
    }

    /**
     * Get test mode preference (default: true)
     */
    private boolean getTestModePreference() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_TEST_MODE, true);
    }

    /**
     * Check if an app is in the monitored apps list
     */
    private boolean isAppInMonitorList(String appPackageName) {
        return appsToMonitor.contains(appPackageName);
    }

    /**
     * Start continuous vibration for 30 seconds, then repeat every 60 seconds (30s vibration + 30s silence)
     * Uses a repeating pattern since Android limits single vibration to ~5 seconds
     */
    private void startVibration(String appName) {
        if (vibrator == null || isVibrating) {
            return;
        }
        
        isVibrating = true;
        appBeingVibratedFor = appName;
        numVibrationsInSession++;
        Log.d("SessionTrackerService", "Starting vibration #" + numVibrationsInSession + " for app: " + appName + " (30 seconds continuous)");
        
        // Create a vibration pattern for 30 seconds of continuous vibration
        // Pattern: vibrate 100ms, silence 100ms, repeated to fill 30 seconds
        // 301 elements * 100ms = 30,100ms ≈ 30 seconds
        long[] pattern = new long[301];
        pattern[0] = 0; // Start immediately
        for (int i = 1; i < pattern.length; i++) {
            pattern[i] = 100; // 100ms vibrate, 100ms silence alternating
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Use VibrationEffect for API 26+
            android.os.VibrationEffect effect = android.os.VibrationEffect.createWaveform(pattern, -1);
            vibrator.vibrate(effect);
        } else {
            // For API < 26, use the pattern with repeat -1 (don't repeat)
            vibrator.vibrate(pattern, -1);
        }
        
        // Stop vibration after 30 seconds (when pattern naturally completes)
        // and schedule next vibration cycle after additional 30 seconds of silence
        handler.postDelayed(() -> {
            stopVibrationAfterDuration(appName);
        }, VIBRATION_DURATION);
    }

    /**
     * Stop vibration and wait 30 seconds before next cycle (30s silence between cycles)
     */
    private void stopVibrationAfterDuration(String appName) {
        // Check if user left the app during vibration
        if (isVibrating && !appName.equals(lastForegroundApp)) {
            userLeftDuringVibration = true;
            Log.d("SessionTrackerService", "User left app during vibration");
        } else {
            userLeftDuringVibration = false;
        }
        
        stopVibration();
        
        // Schedule next vibration cycle after 30 seconds of silence
        handler.postDelayed(() -> {

            // Only reschedule if still in same app and app is monitored
            if (!appName.equals(lastForegroundApp)) {
                Log.d("SessionTrackerService", "App changed, not rescheduling vibration");
            } else if (isAppInMonitorList(appName)) {
                Log.d("SessionTrackerService", "Rescheduling next vibration cycle for " + appName + " (after 30s silence)");
                startVibration(appName);
            }
        }, VIBRATION_DURATION); // Wait 30 seconds of silence before next vibration
    }

    /**
     * Stop vibration
     */
    private void stopVibration() {
        if (vibrator != null && isVibrating) {
            isVibrating = false;
            vibrator.cancel();
            Log.d("SessionTrackerService", "Stopping vibration. User complied: " + userLeftDuringVibration);
        }
    }

    private String getISODateTime(long timeInMillis) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.time.ZonedDateTime zdt = java.time.Instant.ofEpochMilli(timeInMillis)
                    .atZone(java.time.ZoneId.systemDefault());
            return zdt.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } else {
            // For API < 26, use SimpleDateFormat without timezone (backend handles both with/without timezone)
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            sdf.setTimeZone(java.util.TimeZone.getDefault());
            return sdf.format(new java.util.Date(timeInMillis));
        }
    }

    private String getISODate(long timeInMillis) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.time.ZonedDateTime zdt = java.time.Instant.ofEpochMilli(timeInMillis)
                    .atZone(java.time.ZoneId.systemDefault());
            return zdt.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        } else {
            // For API < 26, use SimpleDateFormat to format YYYY-MM-DD
            java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd");
            dateFormat.setTimeZone(java.util.TimeZone.getDefault());
            return dateFormat.format(new java.util.Date(timeInMillis));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Session Tracker",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Session tracking service running in background");
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SmartQuit Session Tracker")
                .setContentText("Recording app sessions...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVibration();
        handler.removeCallbacksAndMessages(null);
        Log.d("SessionTrackerService", "Service destroyed");
    }
}
