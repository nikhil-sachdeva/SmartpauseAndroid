package com.example.smartquit;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;

/**
 * BroadcastReceiver to reschedule the upload job after device boot or app update
 * This ensures uploads happen even if the app process is killed or device restarts
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    private static final int UPLOAD_JOB_ID = 100;
    private static final int MODEL_DOWNLOAD_JOB_ID = 101;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "Broadcast received: " + action);

        // Reschedule jobs on device boot or app update
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            Log.d(TAG, "Rescheduling jobs after boot/update");
            scheduleDaily3AMUpload(context);
            scheduleDaily330AMModelDownload(context);
        }
    }

    /**
     * Schedule daily upload at 3 AM local time
     * Uses one-time jobs that reschedule themselves, ensuring uploads only happen at 3 AM
     */
    public static void scheduleDaily3AMUpload(Context context) {
        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        
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

    /**
     * Schedule daily model download at 3:30 AM local time (30 minutes after upload at 3 AM)
     * Uses one-time jobs that reschedule themselves
     */
    public static void scheduleDaily330AMModelDownload(Context context) {
        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        
        // Calculate time until next 3:30 AM
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 3);
        calendar.set(Calendar.MINUTE, 30);


        // If 3:30 AM has already passed today, schedule for tomorrow 3:30 AM
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        long delayMillis = calendar.getTimeInMillis() - System.currentTimeMillis();

        Log.d("Download in"  + TAG, "========== SCHEDULING MODEL DOWNLOAD JOB ==========");
        Log.d("Download in"  + TAG, "Current time: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(System.currentTimeMillis()));
        Log.d("Download in"  + TAG, "Next model download scheduled for: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(calendar.getTime()));
        Log.d("Download in"  + TAG, "Time until next download: " + (delayMillis / 1000 / 60) + " minutes");

        // Use one-time job with minimum latency
        JobInfo.Builder builder = new JobInfo.Builder(MODEL_DOWNLOAD_JOB_ID, new ComponentName(context, ModelDownloadJobService.class))
                .setMinimumLatency(delayMillis)  // Wait until next 3:30 AM before first execution
                .setPersisted(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setRequiresDeviceIdle(false);
        }
        JobInfo jobInfo = builder.build();
        int result = jobScheduler.schedule(jobInfo);
        
        if (result == JobScheduler.RESULT_SUCCESS) {
            Log.d(TAG, "✅ Model download job scheduled successfully");
            Log.d(TAG, "✅ First download will occur at next 3:30 AM");
            Log.d(TAG, "========== MODEL DOWNLOAD JOB SCHEDULED ==========\n");
        } else {
            Log.e(TAG, "❌ Failed to schedule model download job. Result: " + result);
            Log.d(TAG, "========== MODEL DOWNLOAD JOB SCHEDULING FAILED ==========\n");
        }
    }
}
