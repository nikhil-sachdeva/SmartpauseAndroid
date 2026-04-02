package com.example.smartquit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Vibrator;
import android.util.Log;
import java.util.Calendar;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Random;

import androidx.core.app.NotificationCompat;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

public class SessionTrackerService extends Service {

    private Handler handler;
    private String lastForegroundApp = "NULL";
    private long lastAppChangeTime = System.currentTimeMillis();
    private long lastSessionEndTime = 0;  // Track when the last session ended
    private AppDatabase db;
    private String userId = "user_001";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "SessionTrackerChannel";
    private static final long VIBRATION_TRIGGER_DURATION = 30 * 1000; // 30 sec
    private static final long VIBRATION_DURATION = 15 * 1000; // 15 seconds continuous vibration
    private static final long VIBRATION_CYCLE_INTERVAL = 30 * 1000; // 30 seconds (vibrate 15s, silent 15s, repeat)
    private static final long GROUP_BREAK_THRESHOLD = 45 * 1000; // 45 seconds
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_APPS_TO_MONITOR = "apps_to_monitor";
    private static final String KEY_TEST_MODE = "is_test_mode";  // Use same key as RegistrationActivity
    private static final String KEY_CURRENT_GROUP_ID = "current_group_id";
    private static final String KEY_DAILY_TARGET_APP_USAGE = "daily_target_app_usage_seconds";
    private static final String KEY_DAILY_USAGE_DATE = "daily_usage_date";
    private java.util.List<String> appsToMonitor = new ArrayList<>();
    private boolean isVibrating = false;
    private Vibrator vibrator;
    private int currentGroupId = 1;  // Track current group ID
    private long currentGroupStartTime = System.currentTimeMillis();  // Track when current group started
    private String currentGroupFirstApp = "NULL";  // Track the first app in the current group session
    private boolean hasVibrationOccurredInGroup = false;  // Track if vibration has occurred in current group
    private int numVibrationsInGroup = 0;  // Track number of vibrations in current group (0-5 budget)
    
    // Vibration tracking
    private int numVibrationsInSession = 0;  // Count vibrations in current app session
    private String appBeingVibratedFor = "";  // App that triggered vibration
    private boolean userLeftDuringVibration = false;  // Flag if user left app during vibration
    private int currentQueryId = -1;  // Track the current query ID for compliance updates
    
    // WakeLock to keep CPU running when screen is off (critical for MIUI/Xiaomi phones)
    private PowerManager.WakeLock wakeLock;
    private static final String WAKELOCK_TAG = "SmartPause:SessionTracking";
    
    // Screen lock tracking
    private boolean isScreenUnlocked = true;  // Assume screen is unlocked initially
    private ScreenLockReceiver screenLockReceiver;
    private ModelUpdateReceiver modelUpdateReceiver;
    private Runnable trackingRunnable;
    
    // State representation variables for Q-learning
    private int currentStateNumQueries = 0;  // Number of queries executed so far in group
    private int currentStateNumVibrations = 0;  // Number of vibrations in current group
    private int currentStateFirstAppTarget = 0;  // 0 or 1 (first app in group is target app)
    private int currentStateQuarterOfDay = 0;  // 0, 1, 2, 3 (quarter of day - 6 hour blocks)
    private String currentStateArray = "[0,0,0,0]";  // Current state as array string
    private RetrofitApiService.BaselineStats cachedBaselineStats = null;  // Cache baseline stats
    private float cachedEpsilon = 0.1f;  // Cache epsilon for epsilon-greedy Q-learning (default 0.1)
    private org.json.JSONObject cachedQTable = null;  // Cache Q-table from model
    private long lastThresholdCheckTime = 0;  // Track last time we checked threshold intervals
    private int lastThresholdInterval = 0;  // Track number of queries executed (0 = no queries yet)
    
    // Last Q-table decision metadata for logging
    private String lastDecisionType = "unknown";  // Type of decision: "explore", "exploit", "fallback"
    private float lastDecisionEpsilon = 0.0f;  // Epsilon value used in last decision
    
    // Cumulative target app usage tracking for production mode queries
    private long cumulativeTargetAppUsageSeconds = 0;  // Total time spent in target apps after first query
    private long targetAppSessionStartTime = 0;  // When current target app session started
    private boolean firstQueryTriggered = false;  // Whether first query at median time has occurred
    
    // Daily total target app usage tracking (for notification display)
    private long totalDailyTargetAppUsageSeconds = 0;  // Total target app usage today
    private long lastCountdownLogTime = 0;  // Last time we logged countdown (to avoid log flooding)
    private long lastTestModeCountdownLogTime = 0;  // Last time we logged test-mode countdown
    private long lastNotificationHealthCheckTime = 0;  // Last time we verified notification is showing
    private static final long NOTIFICATION_HEALTH_CHECK_INTERVAL = 60 * 1000;  // Check every 60 seconds
    
    private FirebaseAnalytics firebaseAnalytics;  // Firebase Analytics instance
    private FirebaseCrashlytics firebaseCrashlytics;  // Firebase Crashlytics instance

    @Override
    public void onCreate() {
        super.onCreate();
        
        // Initialize Firebase
        firebaseAnalytics = FirebaseAnalytics.getInstance(this);
        firebaseCrashlytics = FirebaseCrashlytics.getInstance();
        
        db = AppDatabase.getDatabase(this);
        handler = new Handler();
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        
        // Acquire partial WakeLock to keep CPU running when screen is off
        // This is critical for MIUI/Xiaomi phones that aggressively kill background services
        acquireWakeLock();
        
        // Load user ID from SharedPreferences (critical for session tracking)
        loadUserId();
        
        loadAppsToMonitor();
        loadGroupId();  // Load group ID from preferences
        loadLastSessionEndTime();  // Load last session end time for proper grouping
        loadDailyTargetAppUsage();  // Load today's target app usage (or reset for new day)
        
        // Initialize state representation
        updateCurrentState("NULL");
        Log.d("SessionTrackerService", "Initial state: " + currentStateArray + " (" + getCurrentStateDescription() + ")");
        
        // Load baseline stats and Q-table for production mode decisions
        cachedBaselineStats = ModelStorageService.getBaselineStats(this);
        if (cachedBaselineStats != null) {
            Log.d("SessionTrackerService", "Baseline stats loaded: median_session=" + cachedBaselineStats.median_session_usage_seconds + "s");
        } else {
            Log.w("SessionTrackerService", "No baseline stats available at startup");
        }
        
        // Load epsilon for epsilon-greedy Q-learning
        cachedEpsilon = ModelStorageService.getEpsilon(this);
        Log.d("SessionTrackerService", "Epsilon (ε) loaded: " + cachedEpsilon);
        
        if (!getTestModePreference()) {
            loadQTableFromModel();
        }
        
        createNotificationChannel();
        
        // Start foreground service with proper type for Android 14+
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires foreground service type
                startForeground(NOTIFICATION_ID, createNotification(), 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, createNotification());
            }
        } catch (Exception e) {
            Log.e("SessionTrackerService", "Error starting foreground service: " + e.getMessage());
            
            // Log to Firebase
            android.os.Bundle bundle = new android.os.Bundle();
            bundle.putString("error_message", e.getMessage());
            bundle.putString("error_type", "foreground_service_start_failure");
            firebaseAnalytics.logEvent("service_startup_failure", bundle);
            firebaseCrashlytics.recordException(e);
        }
        
        // Register screen lock/unlock receiver
        screenLockReceiver = new ScreenLockReceiver();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(screenLockReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(screenLockReceiver, filter);
        }
        
        // Register model update receiver
        modelUpdateReceiver = new ModelUpdateReceiver();
        IntentFilter modelUpdateFilter = new IntentFilter("com.example.smartquit.MODEL_UPDATED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(modelUpdateReceiver, modelUpdateFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(modelUpdateReceiver, modelUpdateFilter);
        }
        
        Log.d("SessionTrackerService", "Service created and started in foreground");
        
        // Log successful service creation to Firebase
        android.os.Bundle bundle = new android.os.Bundle();
        bundle.putInt("group_id", currentGroupId);
        bundle.putInt("monitored_apps_count", appsToMonitor.size());
        bundle.putBoolean("test_mode", getTestModePreference());
        bundle.putBoolean("has_baseline_stats", cachedBaselineStats != null);
        bundle.putBoolean("has_qtable", cachedQTable != null);
        firebaseAnalytics.logEvent("service_created_successfully", bundle);
        
        // Cancel any pending restart alarms since service is now running
        BootReceiver.cancelServiceRestartAlarm(this);
        
        // Schedule periodic health check via WorkManager (idempotent)
        ServiceHealthCheckWorker.schedulePeriodicHealthCheck(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("SessionTrackerService", "onStartCommand called");
        
        // Mark that service should be running
        BootReceiver.setServiceShouldRun(this, true);
        
        // Cancel any pending restart alarms since service is now running
        BootReceiver.cancelServiceRestartAlarm(this);
        
        // Ensure foreground service is running with notification
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires foreground service type
                startForeground(NOTIFICATION_ID, createNotification(), 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, createNotification());
            }
        } catch (Exception e) {
            Log.e("SessionTrackerService", "Error in onStartCommand foreground: " + e.getMessage());
            firebaseCrashlytics.recordException(e);
        }
        
        startTracking();
        
        // Return START_STICKY to have Android restart service if killed
        // Also START_REDELIVER_INTENT could be used but STICKY is more appropriate here
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startTracking() {
        final int delay = 1000; // 1 second
        
        // Log initial tracking state
        Log.d("SessionTrackerService", "=== TRACKING STARTED ===");
        Log.d("SessionTrackerService", "Apps to monitor: " + appsToMonitor.toString());
        Log.d("SessionTrackerService", "User ID: " + userId);
        Log.d("SessionTrackerService", "Screen unlocked: " + isScreenUnlocked);

        trackingRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    // Only track if screen is unlocked
                    if (!isScreenUnlocked) {
                        handler.postDelayed(this, delay);
                        return;
                    }

                    String currentApp = getForegroundTask();
                    if (currentApp != null && !currentApp.isEmpty()) {
                        processAppChange(currentApp);
                        
                        // In production mode, actively check for query intervals
                        if (!getTestModePreference()) {
                            checkAndExecuteQueryInterval(currentApp);
                        }
                    } else {
                        Log.w("SessionTrackerService", "getForegroundTask returned null or empty");
                    }
                    
                    // Periodically verify notification is still showing (every 60 seconds)
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastNotificationHealthCheckTime >= NOTIFICATION_HEALTH_CHECK_INTERVAL) {
                        lastNotificationHealthCheckTime = currentTime;
                        verifyNotificationShowing();
                    }
                } catch (Exception e) {
                    Log.e("SessionTrackerService", "Error in tracking loop", e);
                    
                    // Log tracking loop failure to Firebase
                    android.os.Bundle bundle = new android.os.Bundle();
                    bundle.putString("error_message", e.getMessage());
                    bundle.putString("last_app", lastForegroundApp);
                    bundle.putString("error_type", "tracking_loop_failure");
                    firebaseAnalytics.logEvent("tracking_failure", bundle);
                    firebaseCrashlytics.recordException(e);
                }

                handler.postDelayed(this, delay);
            }
        };
        
        handler.post(trackingRunnable);
    }

    /**
     * Process app change
     */
    private void processAppChange(String currentApp) {
        // Calculate current state representation
        updateCurrentState(currentApp);
        
        // Skip logging for launchers - only log real app usage
        if (!isLauncherOrNull(currentApp)) {
            // Log current app and state every second
            Log.d("SessionTrackerState", "App: " + currentApp + 
                   ", State: " + currentStateArray + 
                   ", Details: num_queries=" + currentStateNumQueries + 
                   ", num_vibrations=" + currentStateNumVibrations + 
                   ", first_app_target=" + currentStateFirstAppTarget +
                   ", quarter_of_day=" + currentStateQuarterOfDay);
        }
        
        // Track app session changes - save session when leaving an actual app (not NULL or launcher)
        if (!currentApp.equals(lastForegroundApp) && !isLauncherOrNull(lastForegroundApp)) {
            long currentTime = System.currentTimeMillis();
            long durationSeconds = (currentTime - lastAppChangeTime) / 1000;

            // Check if user left the monitored app during vibration
            if (isVibrating && !appBeingVibratedFor.equals(currentApp)) {
                userLeftDuringVibration = true;
                Log.d("SessionTrackerService", "User left " + appBeingVibratedFor + " during vibration");
                
                // Update compliance in the query since user left during vibration
                updateQueryCompliance(currentQueryId, 1);
            }
            
            // Track cumulative target app usage in production mode (after first query)
            if (!getTestModePreference() && firstQueryTriggered && isAppInMonitorList(lastForegroundApp)) {
                if (targetAppSessionStartTime > 0) {
                    long targetAppDuration = (currentTime - targetAppSessionStartTime) / 1000;
                    cumulativeTargetAppUsageSeconds += targetAppDuration;
                    totalDailyTargetAppUsageSeconds += targetAppDuration;  // Track daily total
                    saveDailyTargetAppUsage();  // Persist to SharedPreferences
                    updateNotification();  // Update notification with new usage
                    Log.d("SessionTrackerService", "Target app session ended: " + lastForegroundApp + 
                          " (duration: " + targetAppDuration + "s, cumulative: " + cumulativeTargetAppUsageSeconds + "s, daily: " + totalDailyTargetAppUsageSeconds + "s)");
                    targetAppSessionStartTime = 0;
                }
            } else if (isAppInMonitorList(lastForegroundApp)) {
                // Also track daily usage even before first query (test mode too)
                long targetAppDuration = (currentTime - lastAppChangeTime) / 1000;
                totalDailyTargetAppUsageSeconds += targetAppDuration;
                saveDailyTargetAppUsage();
                updateNotification();
                Log.d("SessionTrackerService", "Target app usage tracked: " + lastForegroundApp + 
                      " (duration: " + targetAppDuration + "s, daily total: " + totalDailyTargetAppUsageSeconds + "s)");
            }

            // Save the previous app session to database with vibration info (async)
            saveAppSession(lastForegroundApp, durationSeconds);
            
            // Reset vibration counters for new session
            numVibrationsInSession = 0;
            userLeftDuringVibration = false;
            currentQueryId = -1;  // Reset query ID
            
            // Stop vibration when app changes
            stopVibration();
        }

        if (!currentApp.equals(lastForegroundApp)) {
            lastForegroundApp = currentApp;
            lastAppChangeTime = System.currentTimeMillis();
            lastTestModeCountdownLogTime = 0;
            
            // Start tracking target app usage in production mode (after first query)
            if (!getTestModePreference() && firstQueryTriggered && isAppInMonitorList(currentApp) && !isLauncherOrNull(currentApp)) {
                targetAppSessionStartTime = lastAppChangeTime;
                Log.d("SessionTrackerService", "Target app session started: " + currentApp);
            }
            
            // Only log app changes for real apps, not launchers
            if (!isLauncherOrNull(currentApp)) {
                Log.d("SessionTrackerService", "App changed to: " + currentApp);
            } else {
                Log.d("SessionTrackerService", "Launcher in foreground - skipping logging");
            }
        }

        // Check if current app should trigger vibration (exclude NULL and launchers)
        if (!isLauncherOrNull(currentApp)) {
            long appDurationMillis = System.currentTimeMillis() - lastAppChangeTime;
            if (getTestModePreference() && isAppInMonitorList(currentApp) && currentQueryId == -1
                    && appDurationMillis < VIBRATION_TRIGGER_DURATION) {
                long now = System.currentTimeMillis();
                if (now - lastTestModeCountdownLogTime >= 5000) {
                    long remainingSeconds = (VIBRATION_TRIGGER_DURATION - appDurationMillis) / 1000;
                    Log.d("SessionTrackerService", "⏱️ Test mode - Query countdown: " + remainingSeconds +
                            "s remaining for app " + currentApp);
                    lastTestModeCountdownLogTime = now;
                }
            }
            if (isAppInMonitorList(currentApp) && !isVibrating && shouldTriggerVibration(currentApp, appDurationMillis)) {
                // Check if vibrations are allowed (requires baseline stats)
                if (ModelStorageService.areVibrationsAllowed(this)) {
                    startVibration(currentApp);
                } else {
                    Log.d("SessionTrackerService", "Vibrations not allowed yet - no baseline stats available");
                }
            }
        }
    }
    
    /**
     * Update current state representation variables based on current context
     */
    private void updateCurrentState(String currentApp) {
        // 1. Number of queries - track queries executed in current group (capped at 10 for state space)
        currentStateNumQueries = Math.min(lastThresholdInterval, 10);
        
        // 2. Number of vibrations - track vibrations in current group (capped at 5 for state space)
        currentStateNumVibrations = Math.min(numVibrationsInGroup, 5);
        
        // 3. First app in group is target (0 or 1) - check if first app in group is a target app
        currentStateFirstAppTarget = isAppInMonitorList(currentGroupFirstApp) ? 1 : 0;
        
        // 4. Quarter of day (0, 1, 2, 3) - based on current hour (6-hour blocks)
        Calendar calendar = Calendar.getInstance();
        int currentHour = calendar.get(Calendar.HOUR_OF_DAY);
        if (currentHour >= 0 && currentHour < 6) {
            currentStateQuarterOfDay = 0;  // 0-6: Night/Early Morning
        } else if (currentHour >= 6 && currentHour < 12) {
            currentStateQuarterOfDay = 1;  // 6-12: Morning
        } else if (currentHour >= 12 && currentHour < 18) {
            currentStateQuarterOfDay = 2;  // 12-18: Afternoon
        } else {
            currentStateQuarterOfDay = 3;  // 18-24: Evening/Night
        }
        
        // Update state array string
        currentStateArray = "[" + currentStateNumQueries + "," + 
                           currentStateNumVibrations + "," + 
                           currentStateFirstAppTarget + "," + 
                           currentStateQuarterOfDay + "]";
    }
    
    /**
     * Get current state as formatted string for logging
     */
    private String getCurrentStateDescription() {
        String queriesDesc = "Queries:" + currentStateNumQueries;
        String vibrationsDesc = "Vibrations:" + currentStateNumVibrations;
        String firstAppDesc = (currentStateFirstAppTarget == 1) ? "Target" : "Non-target";
        String quarterDesc = "";
        switch (currentStateQuarterOfDay) {
            case 0: quarterDesc = "Night/Early(0-6)"; break;
            case 1: quarterDesc = "Morning(6-12)"; break;
            case 2: quarterDesc = "Afternoon(12-18)"; break;
            case 3: quarterDesc = "Evening(18-24)"; break;
        }
        
        return String.format("%s, %s, %s first app, %s", 
                           queriesDesc, vibrationsDesc, firstAppDesc, quarterDesc);
    }

    private String getForegroundTask() {
        String currentApp = "NULL";
        
        // Try newer method first (API 21+)
        currentApp = getForegroundTaskFromUsageStats();
        
        // Fallback to older method if needed
        if ("NULL".equals(currentApp)) {
            currentApp = getForegroundTaskFromActivityManager();
        }
        
        // Only log non-launcher apps
        if (!isLauncherOrNull(currentApp)) {
            Log.d("SessionTrackerService", "Current App in foreground is: " + currentApp);
        }
        return currentApp;
    }
    
    private String getForegroundTaskFromUsageStats() {
        String currentApp = "NULL";
        try {
            android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                Log.e("SessionTrackerService", "UsageStatsManager is NULL - permission may not be granted");
                return "NULL";
            }
            
            long time = System.currentTimeMillis();
            
            // Use queryUsageStats as the primary method - more reliable across devices
            java.util.List<android.app.usage.UsageStats> appList = null;
            try {
                appList = usm.queryUsageStats(
                        android.app.usage.UsageStatsManager.INTERVAL_DAILY, time - 10000, time);
            } catch (SecurityException e) {
                Log.w("SessionTrackerService", "SecurityException accessing usage stats: " + e.getMessage());
                return "NULL";
            } catch (Exception e) {
                Log.w("SessionTrackerService", "Exception querying usage stats: " + e.getMessage());
                return "NULL";
            }
            
            if (appList == null || appList.isEmpty()) {
                // Fallback to wider window if no recent data (last minute)
                try {
                    appList = usm.queryUsageStats(
                            android.app.usage.UsageStatsManager.INTERVAL_DAILY, time - 60000, time);
                } catch (Exception e) {
                    Log.w("SessionTrackerService", "Exception in fallback usage stats query: " + e.getMessage());
                    return "NULL";
                }
            }
            
            if (appList != null && appList.size() > 0) {
                // Find the most recently used app (within last few seconds preferably)
                android.app.usage.UsageStats mostRecent = null;
                long mostRecentTime = 0;
                
                for (android.app.usage.UsageStats usageStats : appList) {
                    try {
                        String packageName = usageStats.getPackageName();
                        // Add null check and basic validation for package name
                        if (packageName == null || packageName.isEmpty()) {
                            Log.d("SessionTrackerService", "Skipping null/empty package name");
                            continue;
                        }
                        
                        long lastUsed = usageStats.getLastTimeUsed();
                        if (lastUsed > mostRecentTime) {
                            // Don't filter out launchers here - we need them to detect home button
                            if (!packageName.equals("com.example.smartquit")) {
                                mostRecentTime = lastUsed;
                                mostRecent = usageStats;
                            }
                        }
                    } catch (Exception e) {
                        // Log parsing errors but continue processing other packages
                        Log.d("SessionTrackerService", "Error parsing usage stats entry: " + e.getMessage());
                        continue;
                    }
                }
                
                if (mostRecent != null) {
                    try {
                        String packageName = mostRecent.getPackageName();
                        long ageMillis = time - mostRecentTime;
                        
                        if (packageName.startsWith("com.android.systemui") || 
                            packageName.startsWith("android") ||
                            packageName.startsWith("com.google.android.gms")) {
                            // Skip system UI but keep looking
                            Log.d("SessionTrackerService", "Skipping system package: " + packageName);
                        } else {
                            currentApp = packageName;
                            // Only log non-launcher apps to reduce noise
                            if (!isLauncherOrNull(currentApp)) {
                                Log.d("SessionTrackerService", "UsageStats found: " + currentApp + " (age: " + ageMillis + "ms)");
                            }
                        }
                    } catch (Exception e) {
                        Log.w("SessionTrackerService", "Error processing most recent usage stats: " + e.getMessage());
                    }
                }
            } else {
                Log.w("SessionTrackerService", "queryUsageStats returned empty - checking permissions");
            }
        } catch (Exception e) {
            Log.e("SessionTrackerService", "Error in UsageStats detection", e);
        }
        return currentApp;
    }
    
    private String getForegroundTaskFromActivityManager() {
        String currentApp = "NULL";
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                // This method is deprecated but can help with immediate detection
                java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                if (tasks != null && !tasks.isEmpty()) {
                    android.app.ActivityManager.RunningTaskInfo topTask = tasks.get(0);
                    if (topTask.topActivity != null) {
                        currentApp = topTask.topActivity.getPackageName();
                        // Only log non-launcher apps
                        if (!isLauncherOrNull(currentApp)) {
                            Log.d("SessionTrackerService", "ActivityManager found: " + currentApp);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d("SessionTrackerService", "ActivityManager fallback not available (expected on newer Android)");
        }
        return currentApp;
    }
    
    private boolean isSystemPackage(String packageName) {
        return packageName.startsWith("com.android.systemui") ||
               packageName.startsWith("com.android.launcher") ||
               packageName.startsWith("android") ||
               packageName.equals("com.example.smartquit") ||
               packageName.contains("launcher") ||
               packageName.contains("homescreen");
    }
    
    /**
     * Check if app is a launcher or NULL (home screen)
     * These apps should not be recorded as sessions
     */
    private boolean isLauncherOrNull(String packageName) {
        if (packageName == null || "NULL".equals(packageName)) {
            return true;
        }
        // Check for various launcher patterns across different Android devices
        return packageName.startsWith("com.android.launcher") ||
               packageName.contains("launcher") ||
               packageName.contains("homescreen") ||
               packageName.contains(".launcher") ||
               packageName.equals("com.google.android.apps.nexuslauncher") ||
               packageName.equals("com.android.launcher3") ||
               packageName.equals("com.sec.android.app.launcher") ||  // Samsung
               packageName.equals("com.huawei.android.launcher") ||   // Huawei
               packageName.equals("com.mi.android.globallauncher") || // Xiaomi
               packageName.equals("com.oneplus.launcher") ||          // OnePlus
               packageName.equals("com.oppo.launcher");               // Oppo
    }


    private void saveAppSession(String appName, long durationSeconds) {
        long currentTime = System.currentTimeMillis();
        long startTime = currentTime - (durationSeconds * 1000);

        String startTimeISO = getISODateTime(startTime);
        String endTimeISO = getISODateTime(currentTime);
        String dateISO = getISODate(currentTime);

        AppSession session = new AppSession(userId, appName, startTimeISO, endTimeISO, durationSeconds, dateISO);
        
        // Determine group ID: new group if break >= 2 minutes since last session
        long timeSinceLastSession = startTime - lastSessionEndTime;
        Log.d("SessionTrackerService", "Group check - Last session ended: " + lastSessionEndTime + 
              ", Current session starts: " + startTime + 
              ", Gap: " + (timeSinceLastSession / 1000) + "s" +
              ", Threshold: " + (GROUP_BREAK_THRESHOLD / 1000) + "s");
              
        if (lastSessionEndTime > 0 && timeSinceLastSession >= GROUP_BREAK_THRESHOLD) {
            currentGroupId++;
            currentGroupStartTime = startTime;  // Reset group start time
            // Set first app in new group (skip if current app is NULL or launcher)
            if (!isLauncherOrNull(appName)) {
                currentGroupFirstApp = appName;
            }
            hasVibrationOccurredInGroup = false;  // Reset vibration flag for new group
            lastThresholdInterval = 0;  // Reset threshold interval tracking for new group
            numVibrationsInGroup = 0;  // Reset vibration count for new group
            cumulativeTargetAppUsageSeconds = 0;  // Reset cumulative usage for new group
            targetAppSessionStartTime = 0;  // Reset target app session tracking
            firstQueryTriggered = false;  // Reset first query flag for new group
            lastCountdownLogTime = 0;  // Reset countdown log timer for new group
            saveGroupId();
            Log.d("SessionTrackerService", "NEW GROUP CREATED: " + currentGroupId + " (gap was " + (timeSinceLastSession / 1000) + "s)" + 
                  ", First app: " + currentGroupFirstApp);
        } else {
            // If this is the very first session (no lastSessionEndTime), set first app (skip NULL/launcher)
            if (lastSessionEndTime == 0 && !isLauncherOrNull(appName)) {
                currentGroupFirstApp = appName;
                Log.d("SessionTrackerService", "FIRST SESSION EVER - Setting group first app: " + currentGroupFirstApp);
            }
            // For continuing groups, update first app if it's still NULL/launcher and we now have a real app
            if (isLauncherOrNull(currentGroupFirstApp) && !isLauncherOrNull(appName)) {
                currentGroupFirstApp = appName;
                Log.d("SessionTrackerService", "UPDATING first app in group to: " + currentGroupFirstApp);
            }
            Log.d("SessionTrackerService", "CONTINUING GROUP: " + currentGroupId + " (gap was " + (timeSinceLastSession / 1000) + "s)" +
                  ", Group first app: " + currentGroupFirstApp);
        }
        
        session.groupId = currentGroupId;
        lastSessionEndTime = currentTime;
        
        // Set vibration and compliance data
        session.numVibrations = numVibrationsInSession;
        session.userComplied = userLeftDuringVibration;
        
        Log.d("SessionTrackerService", "Session details: app=" + appName + 
              ", duration=" + durationSeconds + "s" +
              ", vibrations=" + numVibrationsInSession + 
              ", userComplied=" + userLeftDuringVibration + 
              ", groupId=" + currentGroupId +
              ", finalState=" + currentStateArray + 
              " (" + getCurrentStateDescription() + ")");

        new Thread(() -> {
            try {
                db.appSessionDao().insertSession(session);
                Log.d("SessionTrackerService", "Saved session: " + session.toString());
            } catch (Exception e) {
                Log.e("SessionTrackerService", "Failed to save session to database", e);
                
                // Log session recording failure to Firebase
                android.os.Bundle bundle = new android.os.Bundle();
                bundle.putString("error_message", e.getMessage());
                bundle.putString("app_name", appName);
                bundle.putLong("duration_seconds", durationSeconds);
                bundle.putInt("group_id", currentGroupId);
                bundle.putString("error_type", "session_save_failure");
                firebaseAnalytics.logEvent("session_recording_failure", bundle);
                firebaseCrashlytics.recordException(e);
            }
        }).start();
    }

    /**
     * Load user ID from SharedPreferences
     */
    private void loadUserId() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String storedUserId = prefs.getString("user_id", null);
            if (storedUserId != null && !storedUserId.isEmpty()) {
                userId = storedUserId;
                Log.d("SessionTrackerService", "Loaded user ID from prefs: " + userId);
            } else {
                Log.w("SessionTrackerService", "No user ID found in SharedPreferences, using default: " + userId);
            }
        } catch (Exception e) {
            Log.e("SessionTrackerService", "Error loading user ID", e);
        }
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
     * Load current group ID from SharedPreferences (defaults to 1)
     */
    private void loadGroupId() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            currentGroupId = prefs.getInt(KEY_CURRENT_GROUP_ID, 1);
            Log.d("SessionTrackerService", "Loaded current group ID: " + currentGroupId);
        } catch (Exception e) {
            Log.e("SessionTrackerService", "Error loading group ID", e);
            currentGroupId = 1;
        }
    }

    /**
     * Save current group ID to SharedPreferences
     */
    private void saveGroupId() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(KEY_CURRENT_GROUP_ID, currentGroupId);
            editor.apply();
            Log.d("SessionTrackerService", "Saved group ID: " + currentGroupId);
        } catch (Exception e) {
            Log.e("SessionTrackerService", "Error saving group ID", e);
        }
    }

    /**
     * Load last session end time from database to maintain proper grouping across service restarts
     */
    private void loadLastSessionEndTime() {
        new Thread(() -> {
            try {
                // Query for the most recent session - get all sessions and filter by user
                java.util.List<AppSession> allSessions = db.appSessionDao().getAllSessions();
                if (!allSessions.isEmpty()) {
                    // Find the session with the most recent end time
                    AppSession lastSession = null;
                    long latestEndTime = 0;
                    
                    for (AppSession session : allSessions) {
                        // Filter by userId and find most recent
                        if (!userId.equals(session.userId)) {
                            continue;
                        }
                        
                        try {
                            long sessionEndTime;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                java.time.ZonedDateTime zdt = java.time.ZonedDateTime.parse(session.endTime);
                                sessionEndTime = zdt.toInstant().toEpochMilli();
                            } else {
                                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                                sdf.setTimeZone(java.util.TimeZone.getDefault());
                                sessionEndTime = sdf.parse(session.endTime).getTime();
                            }
                            
                            if (sessionEndTime > latestEndTime) {
                                latestEndTime = sessionEndTime;
                                lastSession = session;
                            }
                        } catch (Exception parseException) {
                            Log.e("SessionTrackerService", "Error parsing session end time: " + session.endTime, parseException);
                        }
                    }
                    
                    if (lastSession != null) {
                        lastSessionEndTime = latestEndTime;
                        Log.d("SessionTrackerService", "Loaded last session end time: " + lastSessionEndTime + " (" + lastSession.endTime + ")");
                    }
                } else {
                    lastSessionEndTime = 0;
                    Log.d("SessionTrackerService", "No previous sessions found, starting fresh");
                }
            } catch (Exception e) {
                Log.e("SessionTrackerService", "Error loading last session end time", e);
                lastSessionEndTime = 0;
            }
        }).start();
    }

    /**
     * Check if an app is in the monitored apps list
     */
    private boolean isAppInMonitorList(String appPackageName) {
        return appsToMonitor.contains(appPackageName);
    }

    /**
     * Determine if vibration should trigger based on config mode and app duration
     * Test mode: When a new target app session starts, after 30 seconds, decide to vibrate with 20% probability
     *           This query happens for EVERY new app, irrespective of group boundaries
     * Production mode: use Q-learning model at t, 2t, 3t... second intervals (t = median_session_usage_seconds)
     */
    private boolean shouldTriggerVibration(String currentApp, long appDurationMillis) {
        // Check if user is eligible for querying (must be day >= 2)
        if (!ModelStorageService.areVibrationsAllowed(this)) {
            Log.d("SessionTrackerService", "User not eligible for queries yet (current_day < 2) - no Q-table decisions or queries allowed");
            return false;
        }
        
        // Check if vibration budget is exhausted (5 vibrations per group)
        if (numVibrationsInGroup >= 5) {
            Log.d("SessionTrackerService", "Vibration budget exhausted (" + numVibrationsInGroup + "/5) - no more vibrations or queries for this group");
            return false;
        }
        
        boolean isTestMode = getTestModePreference();
        
        if (isTestMode) {
            // Test mode: After 30 seconds in target app, vibrate with 20% probability
            // Only check once per app session (when currentQueryId is -1)
            if (appDurationMillis >= VIBRATION_TRIGGER_DURATION && currentQueryId == -1) {
                // Decide whether to vibrate with 20% probability
                boolean shouldVibrate = new Random().nextDouble() < 0.2;
                
                // Record query regardless of vibration decision
                // action: 1 if vibrate, 0 if not vibrate
                int action = shouldVibrate ? 1 : 0;
                currentQueryId = saveQueryAndReturnId(currentApp, action, 0, 0);  // compliance=0, isExploit=0 (test mode is always random)
                
                String decisionStr = shouldVibrate ? "VIBRATE (20% chance hit)" : "NO VIBRATE (80% chance)";
                Log.d("SessionTrackerService", "Test mode - 30s elapsed in app " + currentApp + 
                      ", decision: " + decisionStr + " [state: " + currentStateArray + "]");
                
                return shouldVibrate;
            }
            return false;
        } else {
            // Production mode: queries and vibrations handled by checkAndExecuteQueryInterval()
            // This method should NOT trigger anything in production mode to avoid duplicate queries
            return false;
        }
    }
    
    /**
     * Use Q-learning model to determine if vibration should trigger
     * Queries only during monitored apps at intervals: t, t+1min, t+2min, t+3min... 
     * where t = median_session_usage_seconds from baseline stats
     */
    private boolean shouldTriggerVibrationUsingModel(String currentApp) {
        // Only query during monitored apps (exclude launchers and NULL)
        if (!isAppInMonitorList(currentApp) || isLauncherOrNull(currentApp)) {
            return false;
        }
        
        // Get baseline stats for threshold
        if (cachedBaselineStats == null) {
            cachedBaselineStats = ModelStorageService.getBaselineStats(this);
            if (cachedBaselineStats != null) {
                Log.d("SessionTrackerService", "Reloaded baseline stats: median_session=" + cachedBaselineStats.median_session_usage_seconds + "s");
            }
        }
        
        if (cachedBaselineStats == null) {
            Log.w("SessionTrackerService", "No baseline stats available, skipping Q-model vibration");
            return false;
        }
        
        long thresholdSeconds = (long) cachedBaselineStats.median_session_usage_seconds;
        long groupElapsedTime = System.currentTimeMillis() - currentGroupStartTime;
        long groupElapsedSeconds = groupElapsedTime / 1000;
        
        // Calculate which query interval we're in: t (interval 1), t+60s (interval 2), t+120s (interval 3)...
        int currentThresholdInterval;
        long intervalTime;
        if (groupElapsedSeconds < thresholdSeconds) {
            // Haven't reached first query time yet
            currentThresholdInterval = 0;
            intervalTime = 0;
        } else {
            // First query at t seconds, then every 60 seconds after that
            currentThresholdInterval = 1 + (int)((groupElapsedSeconds - thresholdSeconds) / 60);
            intervalTime = thresholdSeconds + ((currentThresholdInterval - 1) * 60);
        }
        
        // Only trigger if we've reached a new interval and haven't checked this interval yet
        if (currentThresholdInterval > 0 && currentThresholdInterval > lastThresholdInterval) {
            lastThresholdInterval = currentThresholdInterval;
            
            // Load Q-table if not cached
            if (cachedQTable == null) {
                loadQTableFromModel();
            }
            
            if (cachedQTable == null) {
                Log.d("SessionTrackerService", "No Q-table available, using fallback logic");
                return false;
            }
            
            // Use current state to query Q-table
            boolean shouldVibrate = queryQTableForVibrationDecision();
            
            // NOTE: Query is NOT saved here anymore - checkAndExecuteQueryInterval() handles all query saving in production mode
            // This method ONLY returns the vibration decision
            
            Log.d("SessionTrackerService", "Production mode - Q-model decision at " + 
                  intervalTime + "s (interval #" + currentThresholdInterval + "): " + 
                  (shouldVibrate ? "VIBRATE" : "NO VIBRATE") + 
                  " (state: " + currentStateArray + ")" +
                  " [ε=" + lastDecisionEpsilon + ", type=" + lastDecisionType + "]");
            
            return shouldVibrate;
        } else {
            // Either haven't reached threshold yet, or already checked this interval
            if (currentThresholdInterval == 0) {
                Log.d("SessionTrackerService", "Production mode - Group time (" + groupElapsedSeconds + 
                      "s) under threshold (" + thresholdSeconds + "s), no query yet");
            }
            return false;
        }
    }
    
    /**
     * Actively check and execute query intervals in production mode
     * Called every second from tracking loop to ensure no query intervals are missed
     * Only queries when a monitored app is in the foreground
     */
    private void checkAndExecuteQueryInterval(String currentApp) {
        // Check if user is eligible for querying (must be day >= 2)
        if (!ModelStorageService.areVibrationsAllowed(this)) {
            return;
        }
        
        // Only query during monitored apps (exclude launchers and NULL)
        if (!isAppInMonitorList(currentApp) || isLauncherOrNull(currentApp)) {
            return;
        }
        
        // Check if query budget is exhausted (cap at 10 queries per group)
        if (lastThresholdInterval >= 10) {
            return;
        }
        
        // Check if vibration budget is exhausted
        if (numVibrationsInGroup >= 5) {
            return;
        }
        
        // Get baseline stats for threshold
        if (cachedBaselineStats == null) {
            cachedBaselineStats = ModelStorageService.getBaselineStats(this);
        }
        
        if (cachedBaselineStats == null) {
            return;
        }
        
        long medianSeconds = (long) cachedBaselineStats.median_session_usage_seconds;
        long groupElapsedTime = System.currentTimeMillis() - currentGroupStartTime;
        long groupElapsedSeconds = groupElapsedTime / 1000;
        
        // First query at median time t
        if (!firstQueryTriggered) {
            if (groupElapsedSeconds < medianSeconds) {
                // Haven't reached first query time yet - log countdown every 10 seconds
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastCountdownLogTime >= 10000) {  // Log every 10 seconds
                    long remainingSeconds = medianSeconds - groupElapsedSeconds;
                    Log.d("SessionTrackerService", "⏱️  Countdown to first query: " + remainingSeconds + "s remaining (target app: " + currentApp + ")");
                    lastCountdownLogTime = currentTime;
                }
                return;
            }
            
            // Trigger first query at median threshold
            firstQueryTriggered = true;
            // Note: lastThresholdInterval incremented after query is saved to keep accurate count
            
            // Start tracking target app usage if currently in a target app
            if (isAppInMonitorList(currentApp) && !isLauncherOrNull(currentApp)) {
                targetAppSessionStartTime = System.currentTimeMillis();
            }
            
            Log.d("SessionTrackerService", "First query at median threshold: " + medianSeconds + "s");
            // Fall through to execute the query logic below
        }
        
        // Execute query (for first query and all subsequent queries)
        if (firstQueryTriggered) {
            // For subsequent queries (after first), check if we've accumulated 60s of target app usage
            if (lastThresholdInterval >= 1) {
                // Add current target app session time if applicable
                long currentCumulativeUsage = cumulativeTargetAppUsageSeconds;
                if (isAppInMonitorList(currentApp) && !isLauncherOrNull(currentApp) && targetAppSessionStartTime > 0) {
                    long currentSessionDuration = (System.currentTimeMillis() - targetAppSessionStartTime) / 1000;
                    currentCumulativeUsage += currentSessionDuration;
                }
                
                // Only trigger if we've accumulated 60s of target app usage
                if (currentCumulativeUsage < 60) {
                    // Log countdown every 10 seconds
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastCountdownLogTime >= 10000) {  // Log every 10 seconds
                        long remainingSeconds = 60 - currentCumulativeUsage;
                        Log.d("SessionTrackerService", "⏱️  Countdown to next query: " + remainingSeconds + "s remaining (cumulative target app usage: " + currentCumulativeUsage + "s/60s)");
                        lastCountdownLogTime = currentTime;
                    }
                    return;
                }
                
                Log.d("SessionTrackerService", "Query after 60s cumulative target app usage: " + 
                      currentCumulativeUsage + "s");
            }
            
            // Load Q-table if not cached
            if (cachedQTable == null) {
                loadQTableFromModel();
            }
            
            if (cachedQTable == null) {
                return;
            }
            
            // Use current state to query Q-table
            boolean shouldVibrate = queryQTableForVibrationDecision();
            
            // Record query
            int action = shouldVibrate ? 1 : 0;
            int compliance = 0;
            int isExploit = lastDecisionType.equals("exploit") ? 1 : 0;  // 1 if exploit, 0 if explore/random/fallback
            currentQueryId = saveQueryAndReturnId(currentApp, action, compliance, isExploit);
            
            // Calculate cumulative usage for logging
            long loggingCumulativeUsage = cumulativeTargetAppUsageSeconds;
            if (isAppInMonitorList(currentApp) && !isLauncherOrNull(currentApp) && targetAppSessionStartTime > 0) {
                long currentSessionDuration = (System.currentTimeMillis() - targetAppSessionStartTime) / 1000;
                loggingCumulativeUsage += currentSessionDuration;
            }
            
            Log.d("SessionTrackerService", "Production mode - Query at " + 
                  (lastThresholdInterval == 0 ? "median threshold (" + medianSeconds + "s)" : 
                   "60s cumulative target app usage (" + loggingCumulativeUsage + "s total)") + 
                  ": " + (shouldVibrate ? "VIBRATE" : "NO VIBRATE") + 
                  " (state: " + currentStateArray + ")" +
                  " [ε=" + lastDecisionEpsilon + ", type=" + lastDecisionType + "]");
            
            // Reset cumulative usage counter after every query to start counting for next query
            cumulativeTargetAppUsageSeconds = 0;
            targetAppSessionStartTime = System.currentTimeMillis();  // Restart tracking from now
            lastCountdownLogTime = 0;  // Reset countdown log timer for next cycle
            Log.d("SessionTrackerService", "Reset cumulative usage counter after query");
            
            lastThresholdInterval++;  // Increment to track that we've done a query
            
            // Check if query limit is reached (max 10 per group)
            if (lastThresholdInterval >= 10) {
                Log.d("SessionTrackerService", "Query limit reached (10/10) - no more queries will be executed for this group");
            }
            
            // Trigger vibration if decision is to vibrate
            if (shouldVibrate && !isVibrating) {
                // Check if vibrations are allowed (current_day >= 2)
                if (ModelStorageService.areVibrationsAllowed(this)) {
                    startVibration(currentApp);
                } else {
                    int currentDay = ModelStorageService.getCurrentDay(this);
                    Log.d("SessionTrackerService", "Vibrations not allowed yet - current day: " + currentDay + " (need >= 2)");
                }
            }
        }
    }
    
    /**
     * Public method to manually refresh cached model data
     * Can be called from activities, dashboard, or other components
     */
    public static void refreshModelDataForRunningService(Context context) {
        try {
            // Send broadcast to refresh model data
            Intent refreshIntent = new Intent("com.example.smartquit.MODEL_UPDATED");
            context.sendBroadcast(refreshIntent);
            Log.d("SessionTrackerService", "📢 Sent manual model refresh broadcast");
        } catch (Exception e) {
            Log.e("SessionTrackerService", "Error sending manual model refresh broadcast", e);
        }
    }
    
    /**
     * Refresh cached model data (Q-table and baseline stats)
     * Call this when model is updated via upload response or download
     */
    public void refreshCachedModelData() {
        Log.d("SessionTrackerService", "🔄 Refreshing cached model data...");
        
        // Store old epsilon for comparison
        float oldEpsilon = cachedEpsilon;
        
        cachedQTable = null;
        cachedBaselineStats = null;
        cachedEpsilon = 0.1f;  // Reset to default
        lastThresholdInterval = 0;  // Reset threshold tracking
        
        // Load baseline stats first
        cachedBaselineStats = ModelStorageService.getBaselineStats(this);
        
        // Load epsilon for epsilon-greedy Q-learning
        cachedEpsilon = ModelStorageService.getEpsilon(this);
        
        if (!getTestModePreference()) {
            loadQTableFromModel();
            
            // Validate that refresh was successful
            boolean hasStats = (cachedBaselineStats != null);
            boolean hasQTable = (cachedQTable != null);
            
            Log.d("SessionTrackerService", "📊 Model refresh results:");
            Log.d("SessionTrackerService", "   Baseline stats available: " + hasStats);
            if (hasStats) {
                Log.d("SessionTrackerService", "   Median Session: " + cachedBaselineStats.median_session_usage_seconds + "s");
            }
            Log.d("SessionTrackerService", "   Epsilon (ε): " + cachedEpsilon + " (was: " + oldEpsilon + ")");
            Log.d("SessionTrackerService", "   Q-table loaded: " + hasQTable);
            
            if (hasQTable) {
                Log.d("SessionTrackerService", "   " + ModelStorageService.getQTableInfo(this));
            }
            
            Log.d("SessionTrackerService", "✅ Model refresh completed");
        } else {
            Log.d("SessionTrackerService", "⚠️ Test mode enabled - skipping Q-table refresh");
        }
    }
    
    /**
     * Load Q-table from downloaded model data
     */
    private void loadQTableFromModel() {
        try {
            String agentData = ModelStorageService.getAgentData(this);
            if (agentData != null && !agentData.isEmpty()) {
                // Parse agent data as JSON (should be Q-table)
                cachedQTable = new org.json.JSONObject(agentData);
                Log.d("SessionTrackerService", "✅ Loaded Q-table with " + cachedQTable.length() + " states");
            } else {
                Log.d("SessionTrackerService", "No agent data available in ModelStorageService");
                cachedQTable = null;
            }
        } catch (Exception e) {
            Log.e("SessionTrackerService", "Error loading Q-table: " + e.getMessage());
            cachedQTable = null;
            
            // Log Q-table loading failure to Firebase
            android.os.Bundle bundle = new android.os.Bundle();
            bundle.putString("error_message", e.getMessage());
            bundle.putString("error_type", "qtable_load_failure");
            firebaseAnalytics.logEvent("model_loading_failure", bundle);
            firebaseCrashlytics.recordException(e);
        }
    }
    
    /**
     * Query Q-table using current state to make vibration decision
     */
    private boolean queryQTableForVibrationDecision() {
        try {
            // Create state key in the format: "[num_queries,num_vibrations,first_app_target,quarter_of_day]"
            // Must match the bracket-comma format used in currentStateArray
            String stateKey = "[" + currentStateNumQueries + "," + 
                            currentStateNumVibrations + "," + 
                            currentStateFirstAppTarget + "," + 
                            currentStateQuarterOfDay + "]";
            
            Log.d("SessionTrackerService", "Querying Q-table with state: " + stateKey + 
                  " (" + getCurrentStateDescription() + ")");
            
            // Try exact match first, then try with spaces (in case Q-table was saved with JSON formatting)
            boolean stateExists = cachedQTable.has(stateKey);
            if (!stateExists) {
                // Try with spaces: "[0, 0, 0, 1]" format
                String stateKeyWithSpaces = "[" + currentStateNumQueries + ", " + 
                                           currentStateNumVibrations + ", " + 
                                           currentStateFirstAppTarget + ", " + 
                                           currentStateQuarterOfDay + "]";
                stateExists = cachedQTable.has(stateKeyWithSpaces);
                if (stateExists) {
                    stateKey = stateKeyWithSpaces;  // Use the spaced version
                    Log.d("SessionTrackerService", "Found state with spaces format: " + stateKey);
                }
            }
            
            if (stateExists) {
                // Get Q-values for this state
                // Q-table stores states as: {"[2, 0, 1, 1]": [Q_no_vibrate, Q_vibrate], ...}
                org.json.JSONArray stateQValues = cachedQTable.getJSONArray(stateKey);
                
                double noVibrateQ = stateQValues.optDouble(0, 0.0);  // Action 0: no vibrate
                double vibrateQ = stateQValues.optDouble(1, 0.0);    // Action 1: vibrate
                
                boolean shouldVibrate;
                String decisionReason;
                
                // Epsilon-greedy policy: explore with probability epsilon, exploit otherwise
                Random random = new Random();
                double randomValue = random.nextDouble();
                
                Log.d("SessionTrackerService", "🎲 Epsilon-greedy decision: randomValue=" + String.format("%.3f", randomValue) + 
                      ", epsilon=" + String.format("%.3f", cachedEpsilon) + 
                      (randomValue < cachedEpsilon ? " -> EXPLORING (randomness)" : " -> EXPLOITING (Q-table)"));
                
                if (randomValue < cachedEpsilon) {
                    // Explore: choose random action based on randomness, ignore Q-values
                    shouldVibrate = random.nextBoolean();
                    decisionReason = "EXPLORE (random action, ignoring Q-table; ε=" + cachedEpsilon + ", rand=" + String.format("%.3f", randomValue) + ")";
                    lastDecisionType = "explore";
                    lastDecisionEpsilon = cachedEpsilon;
                    Log.d("SessionTrackerService", "   -> Exploration selected: action chosen randomly (" + (shouldVibrate ? "VIBRATE" : "NO VIBRATE") + ")");
                } else {
                    // Exploit: choose action with higher Q-value from the learned model
                    if (noVibrateQ == 0.0 && vibrateQ == 0.0) {
                        // Both Q-values are 0, no learning yet - choose randomly as fallback
                        shouldVibrate = random.nextBoolean();
                        decisionReason = "EXPLOIT (both Q=0, random fallback; ε=" + cachedEpsilon + ", rand=" + String.format("%.3f", randomValue) + ")";
                        lastDecisionType = "exploit-random";
                        lastDecisionEpsilon = cachedEpsilon;
                        Log.d("SessionTrackerService", "   -> Exploitation selected: both Q-values are 0, using random fallback (" + (shouldVibrate ? "VIBRATE" : "NO VIBRATE") + ")");
                    } else {
                        // Use Q-table: choose action with higher Q-value
                        shouldVibrate = vibrateQ > noVibrateQ;
                        double qDifference = Math.abs(vibrateQ - noVibrateQ);
                        decisionReason = "EXPLOIT (using Q-table; ε=" + cachedEpsilon + ", Q-diff=" + String.format("%.3f", qDifference) + ")";
                        lastDecisionType = "exploit";
                        lastDecisionEpsilon = cachedEpsilon;
                        Log.d("SessionTrackerService", "   -> Exploitation selected: using Q-table to pick best action. Q[no-vibrate]=" + 
                              String.format("%.3f", noVibrateQ) + ", Q[vibrate]=" + String.format("%.3f", vibrateQ) + 
                              " (" + (shouldVibrate ? "VIBRATE" : "NO VIBRATE") + " is better)");
                    }
                }
                
                Log.d("SessionTrackerService", "Q-values for state " + stateKey + 
                      " - No vibrate: " + noVibrateQ + 
                      ", Vibrate: " + vibrateQ + 
                      " -> Decision: " + (shouldVibrate ? "VIBRATE" : "NO VIBRATE") +
                      " (" + decisionReason + ")");
                
                return shouldVibrate;
            } else {
                Log.w("SessionTrackerService", "State " + stateKey + " not found in Q-table! " +
                      "Available states: " + cachedQTable.length() + ", defaulting to no vibration");
                
                // Store fallback decision type
                lastDecisionType = "fallback";
                lastDecisionEpsilon = cachedEpsilon;
                
                // Log sample available states for debugging - show how they're actually formatted
                org.json.JSONArray keys = cachedQTable.names();
                if (keys != null && keys.length() > 0) {
                    StringBuilder availableStates = new StringBuilder();
                    int maxStates = Math.min(5, keys.length());
                    for (int i = 0; i < maxStates; i++) {
                        availableStates.append("\"").append(keys.optString(i)).append("\" ");
                    }
                    Log.d("SessionTrackerService", "Sample available state keys (actual format): " + availableStates.toString());
                }
                
                return false;  // Default to no vibration if state not found
            }
        } catch (Exception e) {
            Log.e("SessionTrackerService", "Error querying Q-table: " + e.getMessage());
            lastDecisionType = "error";
            lastDecisionEpsilon = cachedEpsilon;
            return false;
        }
    }

    /**
     * Get test mode preference (default: true)
     */
    private boolean getTestModePreference() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_TEST_MODE, false);  // Default to production mode
    }

    /**
     * Start continuous vibration for 15 seconds
     * Test mode: Vibrates whenever a decision is made to vibrate after 30s in target app
     * Uses a repeating pattern since Android limits single vibration to ~5 seconds
     */
    private void startVibration(String appName) {
        if (vibrator == null || isVibrating) {
            return;
        }
        
        isVibrating = true;
        appBeingVibratedFor = appName;
        numVibrationsInSession++;
        numVibrationsInGroup = Math.min(numVibrationsInGroup + 1, 5);  // Increment group vibrations, cap at 5
        Log.d("SessionTrackerService", "Starting vibration #" + numVibrationsInSession + " for app: " + appName + " (15 seconds continuous)" +
               " (group vibration #" + numVibrationsInGroup + "/5)" +
               " - State: " + getCurrentStateDescription() + 
               " (" + currentStateArray + ")");
        
        // Create a vibration pattern for 15 seconds of continuous vibration
        // Pattern: vibrate 100ms, silence 100ms, repeated to fill 15 seconds
        // 151 elements * 100ms = 15,100ms ≈ 15 seconds
        long[] pattern = new long[151];
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
        
        // Save query and store its ID for compliance update
        // Only save new query if not in test mode (test mode already saved query during decision)
        boolean isTestMode = getTestModePreference();
        if (!isTestMode && currentQueryId == -1) {
            int isExploit = lastDecisionType.equals("exploit") ? 1 : 0;
            currentQueryId = saveQueryAndReturnId(appName, 1, 0, isExploit); // action=1 (vibrate), compliance=0 (not left yet)
        } else if (isTestMode && currentQueryId != -1) {
            // In test mode, query was already saved during decision phase, just log
            Log.d("SessionTrackerService", "Test mode - Using existing query ID " + currentQueryId + " for vibration");
        }
        
        // Stop vibration after 15 seconds (when pattern naturally completes)
        handler.postDelayed(() -> {
            stopVibrationAfterDuration(appName);
        }, VIBRATION_DURATION);
    }

    /**
     * Stop vibration after 15 seconds
     * Vibrations only occur when a query decides to vibrate - no automatic rescheduling
     * Next vibration will only happen when the next query decides to vibrate
     */
    private void stopVibrationAfterDuration(String appName) {
        if (isVibrating && !appName.equals(lastForegroundApp)) {
            userLeftDuringVibration = true;
            Log.d("SessionTrackerService", "User left app during vibration");
            updateQueryCompliance(currentQueryId, 1);
        } else {
            userLeftDuringVibration = false;
        }
        
        stopVibration();
        
        // No automatic rescheduling - vibrations ONLY happen when a query decides to vibrate
        // This ensures numVibrationsInGroup can never exceed number of queries
        Log.d("SessionTrackerService", "Vibration ended. Next vibration will only occur if next query decides to vibrate.");
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

    private String getCurrentUserId() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString("user_id", userId);  // Fallback to default userId
    }
    
    private String getISODateTime(long timeInMillis) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+: Use ZonedDateTime with timezone offset
            java.time.ZonedDateTime zdt = java.time.Instant.ofEpochMilli(timeInMillis)
                    .atZone(java.time.ZoneId.systemDefault());
            return zdt.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } else {
            // Android < 8.0: Include timezone offset manually for consistency
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
            sdf.setTimeZone(java.util.TimeZone.getDefault());
            String dateTimeStr = sdf.format(new java.util.Date(timeInMillis));
            
            // Manually add timezone offset for older Android versions
            java.util.TimeZone tz = java.util.TimeZone.getDefault();
            int offsetInMillis = tz.getOffset(timeInMillis);
            int offsetHours = offsetInMillis / (1000 * 60 * 60);
            int offsetMinutes = Math.abs(offsetInMillis / (1000 * 60)) % 60;
            String offsetStr = String.format("%+03d:%02d", offsetHours, offsetMinutes);
            
            return dateTimeStr + offsetStr;
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
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager == null) {
                Log.e("SessionTrackerService", "NotificationManager is null - cannot create channel");
                return;
            }
            
            // Delete existing channel if it exists (to reset any user changes)
            NotificationChannel existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID);
            if (existingChannel != null && existingChannel.getImportance() < NotificationManager.IMPORTANCE_LOW) {
                // User has disabled the channel - recreate it
                Log.w("SessionTrackerService", "Notification channel was disabled - recreating");
                notificationManager.deleteNotificationChannel(CHANNEL_ID);
            }
            
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "SmartPause Session Tracking",
                    NotificationManager.IMPORTANCE_LOW);  // LOW = silent but always visible
            channel.setDescription("Required for SmartPause to track your app usage. Disabling may stop the service.");
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);  // Show on lock screen
            channel.setBypassDnd(false);  // Don't bypass DND
            channel.enableVibration(false);  // No vibration for this notification
            channel.enableLights(false);  // No LED
            notificationManager.createNotificationChannel(channel);
            Log.d("SessionTrackerService", "✅ Notification channel created/verified");
        }
    }
    
    /**
     * Ensure notification channel exists and is enabled.
     * Call this before showing notifications to handle cases where user disabled the channel.
     */
    private void ensureNotificationChannelExists() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                NotificationChannel channel = notificationManager.getNotificationChannel(CHANNEL_ID);
                if (channel == null) {
                    Log.w("SessionTrackerService", "⚠️ Notification channel missing - recreating");
                    createNotificationChannel();
                } else if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    Log.w("SessionTrackerService", "⚠️ Notification channel disabled by user");
                    // Log to Firebase for analytics
                    android.os.Bundle bundle = new android.os.Bundle();
                    bundle.putString("issue", "notification_channel_disabled");
                    firebaseAnalytics.logEvent("notification_issue", bundle);
                }
            }
        }
    }

    private Notification createNotification() {
        // Ensure channel exists before creating notification
        ensureNotificationChannelExists();
        
        String usageText = formatDuration(totalDailyTargetAppUsageSeconds);
        
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Today's monitored apps usage: " + usageText)
                .setContentText("SmartPause is monitoring your apps")
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        
        // For Android 12+, ensure notification shows immediately
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        }
        
        return builder.build();
    }

    /**
     * Update the notification with current daily target app usage
     */
    private void updateNotification() {
        try {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, createNotification());
            } else {
                Log.e("SessionTrackerService", "❌ NotificationManager is null - cannot update notification");
            }
        } catch (Exception e) {
            Log.e("SessionTrackerService", "❌ Error updating notification", e);
            firebaseCrashlytics.recordException(e);
        }
    }
    
    /**
     * Verify notification is still showing and re-display if needed.
     * Call this periodically to ensure notification visibility.
     */
    private void verifyNotificationShowing() {
        try {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.service.notification.StatusBarNotification[] activeNotifications = notificationManager.getActiveNotifications();
                boolean found = false;
                for (android.service.notification.StatusBarNotification sbn : activeNotifications) {
                    if (sbn.getId() == NOTIFICATION_ID) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    Log.w("SessionTrackerService", "⚠️ Notification not showing - re-displaying");
                    notificationManager.notify(NOTIFICATION_ID, createNotification());
                    
                    // Log to Firebase
                    android.os.Bundle bundle = new android.os.Bundle();
                    bundle.putString("issue", "notification_disappeared");
                    firebaseAnalytics.logEvent("notification_issue", bundle);
                }
            }
        } catch (Exception e) {
            Log.e("SessionTrackerService", "Error verifying notification", e);
        }
    }

    /**
     * Format duration in seconds to human-readable format (e.g., "1h 23m", "45m", "30s")
     */
    private String formatDuration(long totalSeconds) {
        if (totalSeconds < 60) {
            return totalSeconds + "s";
        } else if (totalSeconds < 3600) {
            long minutes = totalSeconds / 60;
            long seconds = totalSeconds % 60;
            if (seconds > 0) {
                return minutes + "m " + seconds + "s";
            }
            return minutes + "m";
        } else {
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            if (minutes > 0) {
                return hours + "h " + minutes + "m";
            }
            return hours + "h";
        }
    }

    /**
     * Load daily target app usage from SharedPreferences
     * Resets to 0 if date has changed (new day)
     */
    private void loadDailyTargetAppUsage() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String today = getISODate(System.currentTimeMillis());
        String savedDate = prefs.getString(KEY_DAILY_USAGE_DATE, "");
        
        if (today.equals(savedDate)) {
            // Same day, load saved usage
            totalDailyTargetAppUsageSeconds = prefs.getLong(KEY_DAILY_TARGET_APP_USAGE, 0);
            Log.d("SessionTrackerService", "Loaded daily usage: " + totalDailyTargetAppUsageSeconds + "s for " + today);
        } else {
            // New day, reset usage
            totalDailyTargetAppUsageSeconds = 0;
            prefs.edit()
                .putLong(KEY_DAILY_TARGET_APP_USAGE, 0)
                .putString(KEY_DAILY_USAGE_DATE, today)
                .apply();
            Log.d("SessionTrackerService", "New day detected (" + today + "), reset daily usage to 0");
        }
    }

    /**
     * Save daily target app usage to SharedPreferences
     */
    private void saveDailyTargetAppUsage() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String today = getISODate(System.currentTimeMillis());
        prefs.edit()
            .putLong(KEY_DAILY_TARGET_APP_USAGE, totalDailyTargetAppUsageSeconds)
            .putString(KEY_DAILY_USAGE_DATE, today)
            .apply();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        
        Log.w("SessionTrackerService", "⚠️ Service being destroyed - scheduling restart");
        
        // Log service destruction to Firebase
        android.os.Bundle bundle = new android.os.Bundle();
        bundle.putLong("uptime_millis", System.currentTimeMillis() - currentGroupStartTime);
        bundle.putInt("current_group_id", currentGroupId);
        bundle.putInt("android_sdk_version", Build.VERSION.SDK_INT);
        bundle.putBoolean("service_should_run", BootReceiver.shouldServiceRun(this));
        firebaseAnalytics.logEvent("service_destroyed", bundle);
        firebaseCrashlytics.log("Service destroyed, uptime: " + (System.currentTimeMillis() - currentGroupStartTime) + "ms");
        
        stopVibration();
        handler.removeCallbacksAndMessages(null);
        
        // Unregister screen lock receiver
        if (screenLockReceiver != null) {
            try {
                unregisterReceiver(screenLockReceiver);
            } catch (IllegalArgumentException e) {
                Log.w("SessionTrackerService", "Screen lock receiver not registered");
            }
        }
        
        // Unregister model update receiver
        if (modelUpdateReceiver != null) {
            try {
                unregisterReceiver(modelUpdateReceiver);
            } catch (IllegalArgumentException e) {
                Log.w("SessionTrackerService", "Model update receiver not registered");
            }
        }
        
        // Release WakeLock
        releaseWakeLock();
        
        // Schedule service restart if it should still be running
        // This provides resilience against Android killing the service
        if (BootReceiver.shouldServiceRun(this)) {
            Log.d("SessionTrackerService", "Scheduling service restart in 5 seconds...");
            BootReceiver.scheduleServiceRestart(this, 5000);
        }
        
        Log.d("SessionTrackerService", "Service destroyed");
    }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.w("SessionTrackerService", "⚠️ Task removed - scheduling service restart");
        
        // Log task removal to Firebase
        android.os.Bundle bundle = new android.os.Bundle();
        bundle.putLong("uptime_millis", System.currentTimeMillis() - currentGroupStartTime);
        bundle.putInt("current_group_id", currentGroupId);
        bundle.putString("last_app", lastForegroundApp);
        bundle.putInt("android_sdk_version", Build.VERSION.SDK_INT);
        firebaseAnalytics.logEvent("service_task_removed", bundle);
        firebaseCrashlytics.log("Service task removed");
        
        // Check if service should still be running
        if (!BootReceiver.shouldServiceRun(getApplicationContext())) {
            Log.d("SessionTrackerService", "Service should not run, not restarting");
            return;
        }
        
        // Attempt immediate restart
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getApplicationContext().startForegroundService(restartServiceIntent);
            } else {
                getApplicationContext().startService(restartServiceIntent);
            }
            Log.d("SessionTrackerService", "✅ Service restart requested after task removal");
        } catch (Exception e) {
            Log.e("SessionTrackerService", "❌ Failed to restart service after task removal: " + e.getMessage());
            
            // Log restart failure to Firebase
            android.os.Bundle errorBundle = new android.os.Bundle();
            errorBundle.putString("error_message", e.getMessage());
            errorBundle.putString("error_type", "task_removed_restart_failure");
            errorBundle.putInt("android_sdk_version", Build.VERSION.SDK_INT);
            firebaseAnalytics.logEvent("service_restart_failure", errorBundle);
            firebaseCrashlytics.recordException(e);
            
            // Schedule restart via AlarmManager as fallback (idempotent)
            BootReceiver.scheduleServiceRestart(getApplicationContext(), 5000);
        }
    }
    
    @Override
    public void onLowMemory() {
        super.onLowMemory();
        Log.w("SessionTrackerService", "⚠️ Low memory warning received");
        
        // Log low memory event to Firebase
        android.os.Bundle bundle = new android.os.Bundle();
        bundle.putInt("android_sdk_version", Build.VERSION.SDK_INT);
        bundle.putLong("uptime_millis", System.currentTimeMillis() - currentGroupStartTime);
        firebaseAnalytics.logEvent("service_low_memory", bundle);
        firebaseCrashlytics.log("Service received low memory warning");
        
        // Preemptively schedule restart in case system kills us
        if (BootReceiver.shouldServiceRun(this)) {
            BootReceiver.scheduleServiceRestart(this, 30000); // 30 seconds
        }
    }
    
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        
        // Log significant memory trim events
        if (level >= TRIM_MEMORY_MODERATE) {
            Log.w("SessionTrackerService", "⚠️ Memory trim level: " + level);
            
            android.os.Bundle bundle = new android.os.Bundle();
            bundle.putInt("trim_level", level);
            bundle.putInt("android_sdk_version", Build.VERSION.SDK_INT);
            firebaseAnalytics.logEvent("service_memory_trim", bundle);
            
            // If we're at critical level, schedule restart just in case
            if (level >= TRIM_MEMORY_COMPLETE && BootReceiver.shouldServiceRun(this)) {
                BootReceiver.scheduleServiceRestart(this, 10000);
            }
        }
    }

    /**
     * BroadcastReceiver to handle model update events
     */
    private class ModelUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.example.smartquit.MODEL_UPDATED".equals(intent.getAction())) {
                Log.d("SessionTrackerService", "📥 Model update notification received - refreshing cached data");
                refreshCachedModelData();
            }
        }
    }
    
    /**
     * BroadcastReceiver to handle screen lock/unlock events
     */
    private class ScreenLockReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                isScreenUnlocked = false;
                
                // End the current session when screen is locked (only save if not NULL or launcher)
                if (!isLauncherOrNull(lastForegroundApp)) {
                    long currentTime = System.currentTimeMillis();
                    long durationSeconds = (currentTime - lastAppChangeTime) / 1000;
                    
                    // Save the app session
                    saveAppSession(lastForegroundApp, durationSeconds);
                    
                    // Reset state
                    lastForegroundApp = "NULL";
                    numVibrationsInSession = 0;
                    userLeftDuringVibration = false;
                    
                    // Stop vibration
                    stopVibration();
                    
                    Log.d("SessionTrackerService", "Screen locked - ended session for " + lastForegroundApp);
                }
                
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                isScreenUnlocked = true;
                
                // Reset app tracking when screen is unlocked
                lastAppChangeTime = System.currentTimeMillis();
                
                // Update state for screen unlock event
                updateCurrentState("NULL");
                
                Log.d("SessionTrackerService", "Screen unlocked - resuming tracking with state: " + currentStateArray);
            }
        }
    }
    
    /**
     * Save query to database (vibration decision point)
     * Records the state, action, and compliance at the moment of decision
     */
    private void saveQuery(String currentApp, int action, int compliance, int isExploit) {
        new Thread(() -> {
            try {
                String timestamp = getCurrentTimestamp();
                String date = getTodayDate();
                
                Query query = new Query(
                    userId,
                    currentGroupId,
                    date,
                    timestamp,
                    currentApp,
                    currentStateArray,  // State as JSON array string
                    action,
                    compliance,
                    isExploit
                );
                
                db.queryDao().insert(query);
                
                Log.d("SessionTrackerService", "✅ Query saved: app=" + currentApp + 
                      ", state=" + currentStateArray + 
                      ", action=" + action + 
                      ", compliance=" + compliance + 
                      ", isExploit=" + isExploit +
                      ", group_id=" + currentGroupId);
                      
            } catch (Exception e) {
                Log.e("SessionTrackerService", "Error saving query", e);
                
                // Log query recording failure to Firebase
                android.os.Bundle bundle = new android.os.Bundle();
                bundle.putString("error_message", e.getMessage());
                bundle.putString("current_app", currentApp);
                bundle.putString("state", currentStateArray);
                bundle.putInt("action", action);
                bundle.putInt("compliance", compliance);
                bundle.putInt("group_id", currentGroupId);
                bundle.putString("error_type", "query_save_failure");
                firebaseAnalytics.logEvent("query_recording_failure", bundle);
                firebaseCrashlytics.recordException(e);
            }
        }).start();
    }
    
    /**
     * Save query to database and return its ID (for compliance tracking)
     * Records the state, action, and compliance at the moment of decision
     * Returns the query ID so compliance can be updated later
     */
    private int saveQueryAndReturnId(String currentApp, int action, int compliance, int isExploit) {
        final int[] queryId = {-1};
        Thread thread = new Thread(() -> {
            try {
                String timestamp = getCurrentTimestamp();
                String date = getTodayDate();
                
                Query query = new Query(
                    userId,
                    currentGroupId,
                    date,
                    timestamp,
                    currentApp,
                    currentStateArray,  // State as JSON array string
                    action,
                    compliance,
                    isExploit
                );
                
                long id = db.queryDao().insertAndReturnId(query);
                queryId[0] = (int) id;
                
                Log.d("SessionTrackerService", "✅ Query saved with ID " + queryId[0] + ": app=" + currentApp + 
                      ", state=" + currentStateArray + 
                      ", action=" + action + 
                      ", compliance=" + compliance + 
                      ", isExploit=" + isExploit +
                      ", group_id=" + currentGroupId);
                      
            } catch (Exception e) {
                Log.e("SessionTrackerService", "Error saving query", e);
                
                // Log query recording failure to Firebase
                android.os.Bundle bundle = new android.os.Bundle();
                bundle.putString("error_message", e.getMessage());
                bundle.putString("current_app", currentApp);
                bundle.putString("state", currentStateArray);
                bundle.putInt("action", action);
                bundle.putInt("compliance", compliance);
                bundle.putInt("group_id", currentGroupId);
                bundle.putString("error_type", "query_save_failure");
                firebaseAnalytics.logEvent("query_recording_failure", bundle);
                firebaseCrashlytics.recordException(e);
            }
        });
        thread.start();
        try {
            thread.join();  // Wait for the thread to complete to get the ID
        } catch (InterruptedException e) {
            Log.e("SessionTrackerService", "Interrupted while waiting for query ID", e);
        }
        return queryId[0];
    }
    
    /**
     * Update compliance in an existing query
     * Called when user leaves the app during or after vibration
     */
    private void updateQueryCompliance(int queryId, int compliance) {
        if (queryId <= 0) {
            Log.w("SessionTrackerService", "Cannot update compliance - invalid query ID: " + queryId);
            return;
        }
        
        new Thread(() -> {
            try {
                db.queryDao().updateCompliance(queryId, compliance);
                Log.d("SessionTrackerService", "✅ Updated query " + queryId + " with compliance=" + compliance);
            } catch (Exception e) {
                Log.e("SessionTrackerService", "Error updating query compliance", e);
                
                // Log compliance update failure to Firebase
                android.os.Bundle bundle = new android.os.Bundle();
                bundle.putString("error_message", e.getMessage());
                bundle.putInt("query_id", queryId);
                bundle.putInt("compliance", compliance);
                bundle.putString("error_type", "compliance_update_failure");
                firebaseAnalytics.logEvent("compliance_update_failure", bundle);
                firebaseCrashlytics.recordException(e);
            }
        }).start();
    }
    
    /**
     * Get current timestamp in ISO format
     */
    private String getCurrentTimestamp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
            return now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } else {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US);
            return sdf.format(new java.util.Date());
        }
    }
    
    /**
     * Get today's date in YYYY-MM-DD format
     */
    private String getTodayDate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.time.LocalDate today = java.time.LocalDate.now();
            return today.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        } else {
            java.util.Calendar calendar = java.util.Calendar.getInstance();
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            return sdf.format(calendar.getTime());
        }
    }
    
    /**
     * Acquire a partial WakeLock to keep the CPU running when screen is off.
     * This is critical for MIUI/Xiaomi and other aggressive OEM phones that
     * kill background services during idle/overnight periods.
     * 
     * Uses PARTIAL_WAKE_LOCK which keeps the CPU running but allows screen to be off.
     */
    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (powerManager != null) {
                    wakeLock = powerManager.newWakeLock(
                            PowerManager.PARTIAL_WAKE_LOCK,
                            WAKELOCK_TAG);
                    wakeLock.setReferenceCounted(false);  // Non-reference counted for safety
                }
            }
            
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
                Log.d("SessionTrackerService", "✅ WakeLock acquired - CPU will stay active when screen is off");
            }
        } catch (Exception e) {
            Log.e("SessionTrackerService", "❌ Failed to acquire WakeLock: " + e.getMessage());
            firebaseCrashlytics.recordException(e);
        }
    }
    
    /**
     * Release the WakeLock. Called when service is destroyed.
     */
    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d("SessionTrackerService", "✅ WakeLock released");
            }
        } catch (Exception e) {
            Log.e("SessionTrackerService", "❌ Failed to release WakeLock: " + e.getMessage());
            // Don't crash - just log
        }
    }
}
