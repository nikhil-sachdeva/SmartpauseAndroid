package com.example.smartquit;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * AlarmManager-based upload receiver for 3AM daily uploads.
 * 
 * This is specifically designed for Xiaomi/MIUI and other Chinese OEM phones
 * that aggressively kill JobScheduler jobs at night. AlarmManager with
 * setExactAndAllowWhileIdle() is more reliable for these devices.
 * 
 * Features:
 * - Uses WakeLock to keep CPU awake during upload
 * - Retries with exponential backoff on failure
 * - Exactly-once upload per day (tracks last successful upload date)
 * - Reschedules for next 3AM after success
 */
public class UploadAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "UploadAlarmReceiver";
    private static final String API_BASE_URL = "https://smartpauseappv2.vercel.app";
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_LAST_SUCCESSFUL_UPLOAD_DATE = "last_successful_upload_date";
    private static final String KEY_UPLOAD_IN_PROGRESS = "upload_in_progress";
    
    public static final String ACTION_UPLOAD_3AM = "com.example.smartquit.ACTION_UPLOAD_3AM";
    public static final String ACTION_UPLOAD_RETRY = "com.example.smartquit.ACTION_UPLOAD_RETRY";
    
    private static final int UPLOAD_ALARM_ID = 300;
    private static final int RETRY_ALARM_ID = 301;
    private static final long RETRY_DELAY_MS = 60000; // 1 minute
    private static final int MAX_RETRIES = 10;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "========== UPLOAD ALARM RECEIVED ==========");
        Log.d(TAG, "Action: " + action);
        Log.d(TAG, "Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        Log.d(TAG, "Device: " + Build.MANUFACTURER + " " + Build.MODEL);
        
        // Log to Firebase
        try {
            FirebaseAnalytics.getInstance(context).logEvent("upload_alarm_received", null);
        } catch (Exception e) {
            Log.w(TAG, "Failed to log to Firebase: " + e.getMessage());
        }
        
        if (ACTION_UPLOAD_3AM.equals(action) || ACTION_UPLOAD_RETRY.equals(action)) {
            // Acquire WakeLock to keep CPU awake during upload
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "SmartPause:UploadWakeLock");
            wakeLock.acquire(5 * 60 * 1000L); // 5 minute max
            
            Log.d(TAG, "✅ WakeLock acquired for upload");
            
            int retryCount = intent.getIntExtra("retry_count", 0);
            performUpload(context, wakeLock, retryCount);
        }
    }
    
    /**
     * Perform the upload with WakeLock protection
     */
    private void performUpload(Context context, PowerManager.WakeLock wakeLock, int retryCount) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String todayDate = getTodayDate();
        String lastSuccessfulUploadDate = prefs.getString(KEY_LAST_SUCCESSFUL_UPLOAD_DATE, "");
        
        // Check if already uploaded today (idempotency check)
        if (todayDate.equals(lastSuccessfulUploadDate)) {
            Log.d(TAG, "✅ Already successfully uploaded today (" + todayDate + "). Skipping.");
            releaseWakeLock(wakeLock);
            scheduleNext3AMUpload(context);
            return;
        }
        
        // Check if upload is in progress
        boolean uploadInProgress = prefs.getBoolean(KEY_UPLOAD_IN_PROGRESS, false);
        if (uploadInProgress) {
            long uploadStartTime = prefs.getLong("upload_start_time", System.currentTimeMillis());
            long timeDiff = System.currentTimeMillis() - uploadStartTime;
            
            if (timeDiff < 300000) { // Less than 5 minutes
                Log.d(TAG, "⚠️ Upload already in progress (" + (timeDiff/1000) + "s ago). Skipping.");
                releaseWakeLock(wakeLock);
                return;
            }
            Log.w(TAG, "Upload in-progress flag is stale. Proceeding.");
        }
        
        String userId = prefs.getString(KEY_USER_ID, null);
        if (userId == null) {
            Log.e(TAG, "❌ User ID not found. Cannot upload.");
            releaseWakeLock(wakeLock);
            return;
        }
        
        // Mark upload as in progress
        prefs.edit()
            .putBoolean(KEY_UPLOAD_IN_PROGRESS, true)
            .putLong("upload_start_time", System.currentTimeMillis())
            .apply();
        
        Log.d(TAG, "📤 Starting upload for user: " + userId + " (retry #" + retryCount + ")");
        
        // Log network state
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
            String networkType = activeNetwork != null ? activeNetwork.getTypeName() : "NONE";
            Log.d(TAG, "📡 Network state: connected=" + isConnected + ", type=" + networkType);
            
            if (!isConnected) {
                Log.e(TAG, "❌ No network connectivity! Upload may fail.");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not check network state: " + e.getMessage());
        }
        
        // Perform upload in background thread
        new Thread(() -> {
            try {
                AppDatabase db = AppDatabase.getDatabase(context);
                
                // Get all sessions
                List<AppSession> sessions = db.appSessionDao().getAllSessions();
                List<Query> queries = db.queryDao().getAllQueries();
                
                if ((sessions == null || sessions.isEmpty()) && (queries == null || queries.isEmpty())) {
                    Log.d(TAG, "⚠️ No sessions or queries to upload.");
                    prefs.edit().putBoolean(KEY_UPLOAD_IN_PROGRESS, false).apply();
                    releaseWakeLock(wakeLock);
                    scheduleNext3AMUpload(context);
                    return;
                }
                
                Log.d(TAG, "Found " + (sessions != null ? sessions.size() : 0) + " sessions, " +
                          (queries != null ? queries.size() : 0) + " queries");
                
                // Convert to API models
                List<RetrofitApiService.Session> apiSessions = new ArrayList<>();
                if (sessions != null) {
                    for (AppSession appSession : sessions) {
                        RetrofitApiService.Session session = new RetrofitApiService.Session();
                        session.app_name = appSession.appName;
                        session.start_time = appSession.startTime;
                        session.end_time = appSession.endTime;
                        session.duration_seconds = (float) appSession.durationSeconds;
                        session.num_vibrations = appSession.numVibrations;
                        session.user_complied = appSession.userComplied;
                        session.group_id = appSession.groupId;
                        apiSessions.add(session);
                    }
                }
                
                List<RetrofitApiService.QueryData> apiQueries = new ArrayList<>();
                if (queries != null) {
                    for (Query query : queries) {
                        RetrofitApiService.QueryData queryData = new RetrofitApiService.QueryData();
                        queryData.group_id = query.groupId;
                        queryData.timestamp = query.timestamp;
                        queryData.current_app = query.currentApp;
                        
                        try {
                            String stateStr = query.state.replaceAll("[\\[\\]\\s]", "");
                            String[] stateParts = stateStr.split(",");
                            queryData.state = new ArrayList<>();
                            for (String part : stateParts) {
                                queryData.state.add(Integer.parseInt(part));
                            }
                        } catch (Exception e) {
                            queryData.state = java.util.Arrays.asList(0, 0, 0, 0);
                        }
                        
                        queryData.action = query.action;
                        queryData.compliance = query.compliance;
                        queryData.is_exploit = query.isExploit;
                        apiQueries.add(queryData);
                    }
                }
                
                // Create upload request
                RetrofitApiService.DailyUpload uploadRequest = new RetrofitApiService.DailyUpload();
                uploadRequest.user_id = userId;
                uploadRequest.date = todayDate;
                uploadRequest.sessions = apiSessions;
                uploadRequest.queries = apiQueries;
                
                // Make API call with 120 second timeout (extra long for Vercel cold starts + slow networks)
                OkHttpClient okHttpClient = new OkHttpClient.Builder()
                        .connectTimeout(120, TimeUnit.SECONDS)
                        .readTimeout(120, TimeUnit.SECONDS)
                        .writeTimeout(120, TimeUnit.SECONDS)
                        .build();
                
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                Retrofit retrofit = new Retrofit.Builder()
                        .baseUrl(API_BASE_URL)
                        .client(okHttpClient)
                        .addConverterFactory(GsonConverterFactory.create(gson))
                        .build();
                
                RetrofitApiService apiService = retrofit.create(RetrofitApiService.class);
                
                // Synchronous call (we're already on background thread with WakeLock)
                Call<RetrofitApiService.UploadResponse> call = apiService.uploadSessions(uploadRequest);
                Response<RetrofitApiService.UploadResponse> response = call.execute();
                
                if (response.isSuccessful() && response.body() != null) {
                    Log.d(TAG, "✅ HTTP response successful: " + response.code());
                    handleUploadSuccess(context, db, prefs, response.body(), wakeLock);
                } else if (response.code() == 429) {
                    // Rate limited - means we already uploaded recently, no need to retry
                    Log.d(TAG, "⏳ HTTP 429 Rate Limited - already uploaded recently, skipping retries");
                    prefs.edit()
                        .putBoolean(KEY_UPLOAD_IN_PROGRESS, false)
                        .remove("upload_start_time")
                        .apply();
                    
                    // Log to Firebase
                    try {
                        android.os.Bundle bundle = new android.os.Bundle();
                        bundle.putString("date", getTodayDate());
                        bundle.putString("trigger", "alarm_manager");
                        bundle.putString("manufacturer", Build.MANUFACTURER);
                        FirebaseAnalytics.getInstance(context).logEvent("upload_rate_limited", bundle);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to log to Firebase");
                    }
                    
                    // Cancel retries and schedule next 3AM (don't retry since we already uploaded)
                    cancelRetryAlarm(context);
                    scheduleNext3AMUpload(context);
                    releaseWakeLock(wakeLock);
                    Log.d(TAG, "========== UPLOAD SKIPPED (RATE LIMITED) ==========\n");
                } else {
                    String errorBody = "";
                    try {
                        if (response.errorBody() != null) {
                            errorBody = response.errorBody().string();
                        }
                    } catch (Exception e) {
                        errorBody = "Could not read error body";
                    }
                    Log.e(TAG, "❌ Upload failed: HTTP " + response.code() + " - " + errorBody);
                    handleUploadFailure(context, prefs, wakeLock, retryCount);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Upload exception: " + e.getMessage(), e);
                FirebaseCrashlytics.getInstance().recordException(e);
                handleUploadFailure(context, prefs, wakeLock, retryCount);
            }
        }).start();
    }
    
    private void handleUploadSuccess(Context context, AppDatabase db, SharedPreferences prefs, 
                                      RetrofitApiService.UploadResponse response, PowerManager.WakeLock wakeLock) {
        Log.d(TAG, "========== UPLOAD SUCCESS ==========");
        Log.d(TAG, "✅ Upload completed at: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        Log.d(TAG, "Response current_day: " + response.current_day);
        Log.d(TAG, "Response has baseline_stats: " + (response.baseline_stats != null));
        Log.d(TAG, "Response has updated_model: " + (response.updated_model != null));
        
        String todayDate = getTodayDate();
        prefs.edit()
            .putString(KEY_LAST_SUCCESSFUL_UPLOAD_DATE, todayDate)
            .putBoolean(KEY_UPLOAD_IN_PROGRESS, false)
            .remove("upload_start_time")
            .apply();
        
        Log.d(TAG, "✅ Marked upload date: " + todayDate);
        
        // Save model data
        ModelStorageService.saveCurrentDay(context, response.current_day);
        if (response.baseline_stats != null) {
            ModelStorageService.saveBaselineStats(context, response.baseline_stats);
        }
        if (response.updated_model != null) {
            ModelStorageService.saveQTableFromUpload(context, response.updated_model);
        }
        
        // Clear database
        try {
            db.appSessionDao().deleteAllSessions();
            db.queryDao().deleteAllQueries();
            Log.d(TAG, "✅ Database cleared");
        } catch (Exception e) {
            Log.e(TAG, "Error clearing database", e);
        }
        
        // Log success to Firebase
        try {
            android.os.Bundle bundle = new android.os.Bundle();
            bundle.putString("date", todayDate);
            bundle.putString("trigger", "alarm_manager");
            bundle.putString("manufacturer", Build.MANUFACTURER);
            FirebaseAnalytics.getInstance(context).logEvent("upload_success_alarm", bundle);
        } catch (Exception e) {
            Log.w(TAG, "Failed to log to Firebase");
        }
        
        // Cancel any retry alarms
        cancelRetryAlarm(context);
        
        // Schedule next 3AM upload
        scheduleNext3AMUpload(context);
        
        releaseWakeLock(wakeLock);
        Log.d(TAG, "========== UPLOAD COMPLETED SUCCESSFULLY ==========\n");
    }
    
    private void handleUploadFailure(Context context, SharedPreferences prefs, 
                                      PowerManager.WakeLock wakeLock, int retryCount) {
        prefs.edit()
            .putBoolean(KEY_UPLOAD_IN_PROGRESS, false)
            .remove("upload_start_time")
            .apply();
        
        if (retryCount < MAX_RETRIES) {
            Log.d(TAG, "⏳ Scheduling retry #" + (retryCount + 1) + " in 1 minute...");
            scheduleRetryAlarm(context, retryCount + 1);
        } else {
            Log.e(TAG, "❌ Max retries reached. Will try again tomorrow at 3AM.");
            // Log failure to Firebase
            try {
                android.os.Bundle bundle = new android.os.Bundle();
                bundle.putInt("retry_count", retryCount);
                bundle.putString("manufacturer", Build.MANUFACTURER);
                FirebaseAnalytics.getInstance(context).logEvent("upload_max_retries", bundle);
            } catch (Exception e) {
                Log.w(TAG, "Failed to log to Firebase");
            }
            scheduleNext3AMUpload(context);
        }
        
        releaseWakeLock(wakeLock);
        Log.d(TAG, "========== UPLOAD FAILED ==========\n");
    }
    
    private void releaseWakeLock(PowerManager.WakeLock wakeLock) {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "✅ WakeLock released");
        }
    }
    
    private String getTodayDate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        } else {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        }
    }
    
    /**
     * Schedule the 3AM upload alarm using AlarmManager
     * This is more reliable than JobScheduler on Xiaomi/MIUI devices
     */
    public static void scheduleNext3AMUpload(Context context) {
        Log.d(TAG, "========== SCHEDULING 3AM UPLOAD ALARM ==========");
        Log.d(TAG, "Device: " + Build.MANUFACTURER + " " + Build.MODEL);
        Log.d(TAG, "Android SDK: " + Build.VERSION.SDK_INT);
        Log.d(TAG, "Current time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        
        if (alarmManager == null) {
            Log.e(TAG, "❌ AlarmManager is null! Cannot schedule alarm.");
            return;
        }
        
        // Calculate next 3AM
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 3);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
            Log.d(TAG, "3AM already passed today, scheduling for tomorrow");
        }
        
        Intent intent = new Intent(context, UploadAlarmReceiver.class);
        intent.setAction(ACTION_UPLOAD_3AM);
        
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, UPLOAD_ALARM_ID, intent, flags);
        
        // Cancel any existing alarm
        alarmManager.cancel(pendingIntent);
        
        // Schedule with setAlarmClock for highest priority (shows in status bar, survives Doze)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AlarmManager.AlarmClockInfo alarmClockInfo = new AlarmManager.AlarmClockInfo(
                    calendar.getTimeInMillis(), pendingIntent);
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent);
            Log.d(TAG, "✅ 3AM upload alarm set with setAlarmClock (highest priority)");
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
            Log.d(TAG, "✅ 3AM upload alarm set with setExactAndAllowWhileIdle");
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, calendar.getTimeInMillis(), pendingIntent);
            Log.d(TAG, "✅ 3AM upload alarm set with setExact");
        }
        
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        Log.d(TAG, "Next upload scheduled for: " + sdf.format(calendar.getTime()));
    }
    
    private void scheduleRetryAlarm(Context context, int retryCount) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        
        Intent intent = new Intent(context, UploadAlarmReceiver.class);
        intent.setAction(ACTION_UPLOAD_RETRY);
        intent.putExtra("retry_count", retryCount);
        
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, RETRY_ALARM_ID, intent, flags);
        
        long triggerTime = System.currentTimeMillis() + RETRY_DELAY_MS;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
        }
        
        Log.d(TAG, "Retry alarm #" + retryCount + " scheduled for 1 minute from now");
    }
    
    private void cancelRetryAlarm(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        
        Intent intent = new Intent(context, UploadAlarmReceiver.class);
        intent.setAction(ACTION_UPLOAD_RETRY);
        
        int flags = PendingIntent.FLAG_NO_CREATE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, RETRY_ALARM_ID, intent, flags);
        
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
            Log.d(TAG, "✅ Retry alarm cancelled");
        }
    }
}
