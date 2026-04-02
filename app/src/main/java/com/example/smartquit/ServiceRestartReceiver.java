package com.example.smartquit;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * BroadcastReceiver to handle service restart requests.
 * Implements idempotent retry logic with exponential backoff to ensure
 * SessionTrackerService stays running even after Android kills it.
 * 
 * This is particularly important for Android 14+ which has stricter
 * background processing restrictions.
 */
public class ServiceRestartReceiver extends BroadcastReceiver {

    private static final String TAG = "ServiceRestartReceiver";
    private static final String PREFS_NAME = "SmartQuitServicePrefs";
    private static final String KEY_RESTART_ATTEMPT = "restart_attempt_count";
    private static final String KEY_LAST_RESTART_TIME = "last_restart_time";
    
    // Maximum backoff delay: 5 minutes (will retry at least once every 5 mins)
    private static final long MAX_BACKOFF_DELAY = 5 * 60 * 1000;
    // Initial backoff delay: 5 seconds
    private static final long INITIAL_BACKOFF_DELAY = 5 * 1000;
    // Maximum retry attempts before resetting counter (for logging purposes)
    private static final int MAX_RETRY_ATTEMPTS = 100;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Received broadcast: " + action);
        
        if ("com.example.smartquit.RESTART_SERVICE".equals(action) ||
            "android.intent.action.BOOT_COMPLETED".equals(action) ||
            "android.intent.action.MY_PACKAGE_REPLACED".equals(action)) {
            
            handleServiceRestart(context);
        } else if ("com.example.smartquit.RECURRING_SERVICE_CHECK".equals(action)) {
            // Recurring check for aggressive OEMs (Xiaomi, Huawei, etc.)
            handleRecurringServiceCheck(context);
        }
    }
    
    /**
     * Handle recurring service check (fires every 5 minutes for MIUI protection).
     * This is simpler than handleServiceRestart - just check and restart if needed.
     */
    private void handleRecurringServiceCheck(Context context) {
        Log.d(TAG, "🔄 Recurring service check triggered");
        
        // Reschedule next check (since setExactAndAllowWhileIdle is one-shot on Android M+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            BootReceiver.scheduleRecurringServiceCheck(context);
        }
        
        // Check if service should be running
        if (!BootReceiver.shouldServiceRun(context)) {
            Log.d(TAG, "Service should not be running, skipping recurring check");
            return;
        }
        
        // Check if service is running
        if (isServiceRunning(context)) {
            Log.d(TAG, "✅ Recurring check: Service is running");
            return;
        }
        
        // Service not running - restart it
        Log.w(TAG, "⚠️ Recurring check: Service NOT running - restarting");
        
        // Log to Firebase
        try {
            android.os.Bundle bundle = new android.os.Bundle();
            bundle.putString("trigger", "recurring_check");
            bundle.putString("manufacturer", Build.MANUFACTURER);
            bundle.putString("model", Build.MODEL);
            bundle.putInt("android_sdk", Build.VERSION.SDK_INT);
            FirebaseAnalytics.getInstance(context).logEvent("service_restart_recurring", bundle);
        } catch (Exception e) {
            Log.w(TAG, "Failed to log to Firebase: " + e.getMessage());
        }
        
        // Attempt restart
        attemptServiceStart(context);
    }
    
    /**
     * Handle service restart with idempotent retry logic.
     * If service is already running, this is a no-op.
     * If service is not running, starts it and resets retry counter.
     * If start fails, schedules another retry with exponential backoff.
     */
    private void handleServiceRestart(Context context) {
        // Check if service should even be running
        if (!BootReceiver.shouldServiceRun(context)) {
            Log.d(TAG, "Service should not be running (user not registered), skipping restart");
            resetRestartAttempts(context);
            return;
        }
        
        // Check if service is already running (idempotent check)
        if (isServiceRunning(context)) {
            Log.d(TAG, "✅ Service is already running, no restart needed (idempotent)");
            resetRestartAttempts(context);
            BootReceiver.cancelServiceRestartAlarm(context);
            return;
        }
        
        // Service is not running, attempt restart
        int attemptCount = getRestartAttemptCount(context);
        long lastRestartTime = getLastRestartTime(context);
        long currentTime = System.currentTimeMillis();
        
        Log.d(TAG, "Service not running, attempting restart (attempt #" + (attemptCount + 1) + ")");
        
        // Log to Firebase
        try {
            FirebaseAnalytics.getInstance(context).logEvent("service_restart_attempt", 
                createRestartBundle(attemptCount + 1, currentTime - lastRestartTime));
        } catch (Exception e) {
            Log.w(TAG, "Failed to log to Firebase: " + e.getMessage());
        }
        
        // Attempt to start the service
        boolean startSuccess = attemptServiceStart(context);
        
        if (startSuccess) {
            Log.d(TAG, "✅ Service restart requested successfully");
            // Don't reset counter yet - wait for next check to confirm service is running
            incrementRestartAttempt(context);
            
            // Schedule a verification check in 10 seconds to ensure service started
            BootReceiver.scheduleServiceRestart(context, 10000);
        } else {
            // Start failed, schedule retry with exponential backoff
            incrementRestartAttempt(context);
            attemptCount = getRestartAttemptCount(context);
            
            long backoffDelay = calculateBackoffDelay(attemptCount);
            Log.d(TAG, "⚠️ Service start failed, scheduling retry in " + (backoffDelay / 1000) + "s");
            
            // Log failure to Firebase
            try {
                FirebaseCrashlytics.getInstance().log("Service restart failed, attempt #" + attemptCount);
            } catch (Exception e) {
                Log.w(TAG, "Failed to log to Crashlytics: " + e.getMessage());
            }
            
            BootReceiver.scheduleServiceRestart(context, backoffDelay);
        }
    }
    
    /**
     * Attempt to start the SessionTrackerService
     * @return true if the start command was issued successfully (doesn't guarantee service is running)
     */
    private boolean attemptServiceStart(Context context) {
        try {
            Intent serviceIntent = new Intent(context, SessionTrackerService.class);
            serviceIntent.setPackage(context.getPackageName());
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "❌ SecurityException starting service: " + e.getMessage());
            try {
                FirebaseCrashlytics.getInstance().recordException(e);
            } catch (Exception ignored) {}
            return false;
        } catch (IllegalStateException e) {
            // This can happen on Android 8+ if app is in background
            Log.e(TAG, "❌ IllegalStateException starting service (app in background?): " + e.getMessage());
            try {
                FirebaseCrashlytics.getInstance().recordException(e);
            } catch (Exception ignored) {}
            return false;
        } catch (Exception e) {
            Log.e(TAG, "❌ Exception starting service: " + e.getMessage());
            try {
                FirebaseCrashlytics.getInstance().recordException(e);
            } catch (Exception ignored) {}
            return false;
        }
    }
    
    /**
     * Check if SessionTrackerService is currently running
     */
    private boolean isServiceRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return false;
        
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (SessionTrackerService.class.getName().equals(service.service.getClassName())) {
                // Additional check: ensure it's our process
                if (service.pid != 0) {
                    Log.d(TAG, "Service found running with PID: " + service.pid);
                    return true;
                }
            }
        }
        return false;
    }
    
    /**
     * Calculate exponential backoff delay
     */
    private long calculateBackoffDelay(int attemptCount) {
        // Exponential backoff: 5s, 10s, 20s, 40s, 80s, 160s, 300s (max)
        long delay = INITIAL_BACKOFF_DELAY * (1L << Math.min(attemptCount, 6));
        return Math.min(delay, MAX_BACKOFF_DELAY);
    }
    
    private int getRestartAttemptCount(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_RESTART_ATTEMPT, 0);
    }
    
    private void incrementRestartAttempt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int current = prefs.getInt(KEY_RESTART_ATTEMPT, 0);
        // Reset if we've exceeded max attempts (for logging sanity)
        int newCount = (current >= MAX_RETRY_ATTEMPTS) ? 1 : current + 1;
        prefs.edit()
            .putInt(KEY_RESTART_ATTEMPT, newCount)
            .putLong(KEY_LAST_RESTART_TIME, System.currentTimeMillis())
            .apply();
    }
    
    private void resetRestartAttempts(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int previousAttempts = prefs.getInt(KEY_RESTART_ATTEMPT, 0);
        if (previousAttempts > 0) {
            Log.d(TAG, "Resetting restart attempt counter (was " + previousAttempts + ")");
        }
        prefs.edit()
            .putInt(KEY_RESTART_ATTEMPT, 0)
            .putLong(KEY_LAST_RESTART_TIME, System.currentTimeMillis())
            .apply();
    }
    
    private long getLastRestartTime(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_LAST_RESTART_TIME, 0);
    }
    
    private android.os.Bundle createRestartBundle(int attemptNumber, long timeSinceLastAttempt) {
        android.os.Bundle bundle = new android.os.Bundle();
        bundle.putInt("attempt_number", attemptNumber);
        bundle.putLong("time_since_last_attempt_ms", timeSinceLastAttempt);
        bundle.putInt("android_sdk_version", Build.VERSION.SDK_INT);
        return bundle;
    }
}
