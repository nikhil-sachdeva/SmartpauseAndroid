package com.example.smartquit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.util.Calendar;

/**
 * Service to initialize daily upload scheduling at 3 AM
 */
public class SessionUploadService extends Service {

    private static final String TAG = "SessionUploadService";
    private static final int UPLOAD_JOB_ID = 100;
    private static final int NOTIFICATION_ID = 2;
    private static final String CHANNEL_ID = "SessionUploadChannel";
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_USER_ID = "user_id";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "SessionUploadService started");
        
        // For Android 12+ (API 31), avoid starting foreground service from background
        // This service only needs to schedule the job, then it can stop
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d(TAG, "Android 12+ detected - running as regular service to avoid foreground restrictions");
            // Just schedule the job and stop immediately
            BootReceiver.scheduleDaily3AMUpload(this);
            stopSelf();
            return START_NOT_STICKY;
        } else {
            // For older Android versions, we can still use foreground service
            Notification notification = createNotification();
            try {
                startForeground(NOTIFICATION_ID, notification);
                Log.d(TAG, "Started as foreground service (Android < 12)");
            } catch (Exception e) {
                Log.e(TAG, "Error starting foreground service: " + e.getMessage());
                // If foreground fails, continue as regular service
            }
        }
        
        // Schedule the upload job
        BootReceiver.scheduleDaily3AMUpload(this);
        
        // Stop the service after scheduling (job scheduler will handle uploads)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Give a moment for foreground service to establish, then stop
            new android.os.Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    stopSelf();
                }
            }, 1000);
        }
        
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Create notification channel for Android 8.0+
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Session Upload Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Schedules daily session uploads");
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Create notification for the foreground service
     */
    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("SmartQuit")
                .setContentText("Scheduling session uploads...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }
}
