package com.example.smartquit;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Worker fallback to trigger upload when AlarmManager PendingIntent cannot be created.
 */
public class UploadWorker extends Worker {

    private static final String TAG = "UploadWorker";

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            int retryCount = getInputData().getInt("retry_count", 0);
            Intent intent = new Intent(getApplicationContext(), UploadAlarmReceiver.class);
            intent.setAction(UploadAlarmReceiver.ACTION_UPLOAD_RETRY);
            intent.putExtra("retry_count", retryCount);
            // Send broadcast to the receiver as a fallback trigger
            getApplicationContext().sendBroadcast(intent);
            Log.d(TAG, "Triggered UploadAlarmReceiver via WorkManager (retry_count=" + retryCount + ")");
            return Result.success();
        } catch (Exception e) {
            Log.e(TAG, "UploadWorker failed: " + e.getMessage(), e);
            return Result.retry();
        }
    }
}
