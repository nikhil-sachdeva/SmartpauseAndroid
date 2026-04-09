package com.example.smartquit;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;

/**
 * BroadcastReceiver to handle device boot, app updates, and service restart scheduling.
 * This ensures the SessionTrackerService runs even if the app process is killed or device restarts.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    private static final int UPLOAD_JOB_ID = 100;
    private static final int SERVICE_RESTART_ALARM_ID = 200;
    private static final String PREFS_NAME = "SmartQuitServicePrefs";
    private static final String KEY_SERVICE_SHOULD_RUN = "service_should_run";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Broadcast received: " + action);

        // Handle device boot or app update
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Log.d(TAG, "Rescheduling jobs and starting service after boot/update");
            scheduleDaily3AMUpload(context);
            
            // Also schedule AlarmManager-based upload (more reliable on Xiaomi/MIUI)
            UploadAlarmReceiver.scheduleNext3AMUpload(context);
            Log.d(TAG, "✅ Scheduled AlarmManager-based 3AM upload (Xiaomi/MIUI protection)");
            
            // Start SessionTrackerService and health check worker if service should be running
            if (shouldServiceRun(context)) {
                Log.d(TAG, "Service should run - starting service and scheduling health check");
                startSessionTrackerService(context);
                
                // Schedule periodic health check (idempotent - won't duplicate if already scheduled)
                ServiceHealthCheckWorker.schedulePeriodicHealthCheck(context);
            } else {
                Log.d(TAG, "Service should not run - skipping service start");
            }
        }
    }
    
    /**
     * Check if the service should be running (user has completed registration)
     */
    public static boolean shouldServiceRun(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_SERVICE_SHOULD_RUN, false);
    }
    
    /**
     * Set whether the service should be running
     */
    public static void setServiceShouldRun(Context context, boolean shouldRun) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_SERVICE_SHOULD_RUN, shouldRun).apply();
        Log.d(TAG, "Service should run flag set to: " + shouldRun);
    }
    
    /**
     * Start the SessionTrackerService with proper handling for different Android versions
     */
    public static void startSessionTrackerService(Context context) {
        try {
            Intent serviceIntent = new Intent(context, SessionTrackerService.class);
            serviceIntent.setPackage(context.getPackageName());
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.d(TAG, "✅ SessionTrackerService start requested");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start SessionTrackerService: " + e.getMessage());
            // Schedule a retry via AlarmManager
            scheduleServiceRestart(context, 5000); // Retry in 5 seconds
        }
    }
    
    /**
     * Schedule a service restart using AlarmManager with exponential backoff
     * @param delayMillis Time in milliseconds to wait before restart
     */
    public static void scheduleServiceRestart(Context context, long delayMillis) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(context, ServiceRestartReceiver.class);
            intent.setAction("com.example.smartquit.RESTART_SERVICE");
            
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, SERVICE_RESTART_ALARM_ID, intent, flags);
            
            long triggerTime = System.currentTimeMillis() + delayMillis;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            }
            
            Log.d(TAG, "✅ Service restart scheduled in " + delayMillis + "ms");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to schedule service restart: " + e.getMessage());
        }
    }
    
    /**
     * Cancel any scheduled service restart alarms
     */
    public static void cancelServiceRestartAlarm(Context context) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(context, ServiceRestartReceiver.class);
            intent.setAction("com.example.smartquit.RESTART_SERVICE");
            
            int flags = PendingIntent.FLAG_NO_CREATE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, SERVICE_RESTART_ALARM_ID, intent, flags);
            
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent);
                pendingIntent.cancel();
                Log.d(TAG, "✅ Service restart alarm cancelled");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to cancel service restart alarm: " + e.getMessage());
        }
    }
    
    /**
     * Initialize service resilience mechanisms.
     * Should be called after user registration or when app starts.
     */
    public static void initializeServiceResilience(Context context) {
        // Set flag that service should be running
        setServiceShouldRun(context, true);
        
        // Schedule periodic health check via WorkManager (fires every 15 min)
        ServiceHealthCheckWorker.schedulePeriodicHealthCheck(context);
        
        // Schedule aggressive recurring alarm for MIUI/Xiaomi phones (fires every 5 min)
        // This runs in addition to WorkManager as extra protection
        scheduleRecurringServiceCheck(context);
        
        // Schedule JobScheduler-based 3AM upload (standard method)
        scheduleDaily3AMUpload(context);
        
        // Schedule AlarmManager-based 3AM upload (more reliable on Xiaomi/MIUI)
        // This runs in ADDITION to JobScheduler - whichever fires first will upload
        // The idempotency checks ensure no duplicate uploads
        UploadAlarmReceiver.scheduleNext3AMUpload(context);
        
        // Start the service
        startSessionTrackerService(context);
        
        Log.d(TAG, "✅ Service resilience initialized (including aggressive MIUI protection + AlarmManager uploads)");
    }
    
    /**
     * Schedule a recurring alarm to check and restart service every 5 minutes.
     * This is specifically designed for aggressive Chinese OEMs (Xiaomi, Huawei, Oppo, etc.)
     * that kill services even when WorkManager is running.
     */
    private static final int RECURRING_CHECK_ALARM_ID = 201;
    private static final long RECURRING_CHECK_INTERVAL = 5 * 60 * 1000; // 5 minutes
    
    public static void scheduleRecurringServiceCheck(Context context) {
        try {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(context, ServiceRestartReceiver.class);
            intent.setAction("com.example.smartquit.RECURRING_SERVICE_CHECK");
            
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, RECURRING_CHECK_ALARM_ID, intent, flags);
            
            // Cancel any existing recurring alarm first
            alarmManager.cancel(pendingIntent);
            
            // Schedule recurring alarm every 5 minutes using setRepeating
            // Note: On Android 6+ exact alarms may be deferred during Doze, but this is still better than nothing
            long triggerTime = System.currentTimeMillis() + RECURRING_CHECK_INTERVAL;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Use setExactAndAllowWhileIdle for first trigger, then reschedule in receiver
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            } else {
                // Pre-M: use setRepeating
                alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, triggerTime, RECURRING_CHECK_INTERVAL, pendingIntent);
            }
            
            Log.d(TAG, "✅ Recurring service check alarm scheduled (every 5 minutes)");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to schedule recurring service check: " + e.getMessage());
        }
    }

    /**
     * Schedule daily upload at 3 AM local time
     * Uses one-time jobs that reschedule themselves, ensuring uploads only happen at 3 AM
     */
    public static void scheduleDaily3AMUpload(Context context) {
        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        
        // Cancel any existing upload jobs to prevent duplicates
        jobScheduler.cancel(UPLOAD_JOB_ID);
        Log.d(TAG, "✅ Cancelled any existing upload jobs to prevent duplicates");
        
        // Calculate time until next 3 AM
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 3);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);

        // If 3 AM has already passed today, schedule for tomorrow 3 AM
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        long delayMillis = calendar.getTimeInMillis() - System.currentTimeMillis();

        Log.d(TAG, "========== SCHEDULING UPLOAD JOB ==========");
        Log.d(TAG, "Current time: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(System.currentTimeMillis()));
        Log.d(TAG, "Next upload scheduled for: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(calendar.getTime()));
        Log.d(TAG, "Time until next upload: " + (delayMillis / 1000 / 60) + " minutes");

        // Use one-time job with minimum latency (not periodic, to avoid conflict)
        JobInfo.Builder builder = new JobInfo.Builder(UPLOAD_JOB_ID, new ComponentName(context, SessionUploadJobService.class))
                .setMinimumLatency(delayMillis)  // Wait until next 3 AM before first execution
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)  // REQUIRED: Ensure network is available
                .setPersisted(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setRequiresDeviceIdle(false);
        }
        JobInfo jobInfo = builder.build();
        int result = jobScheduler.schedule(jobInfo);
        
        if (result == JobScheduler.RESULT_SUCCESS) {
            Log.d(TAG, "✅ Upload job scheduled successfully");
            Log.d(TAG, "✅ First upload will occur at next 3 AM");
            Log.d(TAG, "========== JOB SCHEDULED ==========\n");
        } else {
            Log.e(TAG, "❌ Failed to schedule upload job. Result: " + result);
            Log.d(TAG, "========== JOB SCHEDULING FAILED ==========\n");
        }
    }
}
