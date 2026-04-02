package com.example.smartquit;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

import java.util.concurrent.TimeUnit;

/**
 * WorkManager Worker that performs periodic health checks on SessionTrackerService.
 * 
 * This is a critical component for Android 14+ service resilience because:
 * 1. WorkManager survives app death, device reboot, and Doze mode
 * 2. It runs regardless of battery optimization settings
 * 3. Provides idempotent restart attempts (if service is already running, does nothing)
 * 
 * The worker runs every 15 minutes and ensures the service is always running
 * when it should be.
 */
public class ServiceHealthCheckWorker extends Worker {

    private static final String TAG = "ServiceHealthCheckWorker";
    private static final String WORK_NAME = "service_health_check";
    
    // Health check interval: 15 minutes (minimum for periodic work)
    private static final long HEALTH_CHECK_INTERVAL_MINUTES = 15;

    public ServiceHealthCheckWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        Log.d(TAG, "🏥 Performing service health check...");
        
        try {
            // Check if service should be running
            if (!BootReceiver.shouldServiceRun(context)) {
                Log.d(TAG, "Service should not be running (user not registered), health check passed");
                return Result.success();
            }
            
            // Check if service is running
            boolean isRunning = isServiceRunning(context);
            
            if (isRunning) {
                Log.d(TAG, "✅ Service is healthy and running");
                
                // Log successful health check to Firebase (occasionally)
                try {
                    android.os.Bundle bundle = new android.os.Bundle();
                    bundle.putBoolean("service_running", true);
                    bundle.putInt("android_sdk_version", Build.VERSION.SDK_INT);
                    FirebaseAnalytics.getInstance(context).logEvent("service_health_check_success", bundle);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to log health check to Firebase: " + e.getMessage());
                }
                
                return Result.success();
            } else {
                Log.w(TAG, "⚠️ Service is NOT running! Attempting restart...");
                
                // Log service not running to Firebase
                try {
                    android.os.Bundle bundle = new android.os.Bundle();
                    bundle.putBoolean("service_running", false);
                    bundle.putInt("android_sdk_version", Build.VERSION.SDK_INT);
                    bundle.putString("trigger", "health_check_worker");
                    FirebaseAnalytics.getInstance(context).logEvent("service_not_running", bundle);
                    FirebaseCrashlytics.getInstance().log("Service not running detected by health check worker");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to log to Firebase: " + e.getMessage());
                }
                
                // Attempt to restart the service
                boolean restartSuccess = attemptServiceRestart(context);
                
                if (restartSuccess) {
                    Log.d(TAG, "✅ Service restart requested from health check");
                    
                    // Schedule a verification via ServiceRestartReceiver
                    BootReceiver.scheduleServiceRestart(context, 10000);
                    
                    return Result.success();
                } else {
                    Log.e(TAG, "❌ Failed to restart service from health check");
                    
                    // Delegate to ServiceRestartReceiver for exponential backoff retry
                    BootReceiver.scheduleServiceRestart(context, 5000);
                    
                    // Return retry to have WorkManager attempt again
                    return Result.retry();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Exception during health check: " + e.getMessage());
            try {
                FirebaseCrashlytics.getInstance().recordException(e);
            } catch (Exception ignored) {}
            
            // Schedule restart attempt
            BootReceiver.scheduleServiceRestart(context, 5000);
            
            return Result.retry();
        }
    }
    
    /**
     * Attempt to restart the SessionTrackerService
     */
    private boolean attemptServiceRestart(Context context) {
        try {
            Intent serviceIntent = new Intent(context, SessionTrackerService.class);
            serviceIntent.setPackage(context.getPackageName());
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Exception starting service: " + e.getMessage());
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
                return service.pid != 0;
            }
        }
        return false;
    }
    
    /**
     * Schedule the periodic health check worker.
     * This should be called when:
     * 1. User completes registration
     * 2. App is opened
     * 3. Device boots (from BootReceiver)
     * 
     * The scheduling is idempotent - if already scheduled, this is a no-op.
     */
    public static void schedulePeriodicHealthCheck(Context context) {
        try {
            // Only schedule if service should be running
            if (!BootReceiver.shouldServiceRun(context)) {
                Log.d(TAG, "Not scheduling health check - service should not run");
                return;
            }
            
            // Build constraints - we want this to run even without network
            Constraints constraints = new Constraints.Builder()
                    .setRequiresBatteryNotLow(false)  // Run even on low battery
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)
                    .build();
            
            // Create periodic work request
            PeriodicWorkRequest healthCheckRequest = new PeriodicWorkRequest.Builder(
                    ServiceHealthCheckWorker.class,
                    HEALTH_CHECK_INTERVAL_MINUTES,
                    TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setInitialDelay(1, TimeUnit.MINUTES)  // Small initial delay
                    .build();
            
            // Enqueue with KEEP policy - if already scheduled, don't replace
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    healthCheckRequest);
            
            Log.d(TAG, "✅ Periodic health check scheduled (every " + HEALTH_CHECK_INTERVAL_MINUTES + " minutes)");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to schedule periodic health check: " + e.getMessage());
            try {
                FirebaseCrashlytics.getInstance().recordException(e);
            } catch (Exception ignored) {}
        }
    }
    
    /**
     * Cancel the periodic health check.
     * Call this if the user explicitly stops the service.
     */
    public static void cancelPeriodicHealthCheck(Context context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
            Log.d(TAG, "✅ Periodic health check cancelled");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to cancel periodic health check: " + e.getMessage());
        }
    }
    
    /**
     * Run an immediate one-time health check.
     * Useful for triggering an immediate service status check.
     */
    public static void runImmediateHealthCheck(Context context) {
        try {
            androidx.work.OneTimeWorkRequest immediateCheck = new androidx.work.OneTimeWorkRequest.Builder(
                    ServiceHealthCheckWorker.class)
                    .build();
            
            WorkManager.getInstance(context).enqueue(immediateCheck);
            Log.d(TAG, "✅ Immediate health check enqueued");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to enqueue immediate health check: " + e.getMessage());
        }
    }
}
