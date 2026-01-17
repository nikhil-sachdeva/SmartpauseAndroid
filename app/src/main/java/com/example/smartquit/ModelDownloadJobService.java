package com.example.smartquit;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Job service to handle daily model downloads at 3:30 AM
 * (30 minutes after session uploads at 3 AM)
 */
public class ModelDownloadJobService extends JobService {

    private static final String TAG = "ModelDownloadJobService";
    private static final String API_BASE_URL = "https://smartquit-cyber.onrender.com";
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_USER_ID = "user_id";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 5000; // 5 seconds between retries

    private int retryCount = 0;

    @Override
    public boolean onStartJob(JobParameters params) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String currentTime = sdf.format(new java.util.Date(System.currentTimeMillis()));
        Log.d(TAG, "========== MODEL DOWNLOAD JOB TRIGGERED AT: " + currentTime + " ==========");
        
        // Run download in background thread
        new Thread(() -> {
            downloadModel(params);
        }).start();
        
        return true; // Job is running on a background thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.d(TAG, "Model download job stopped");
        return true; // Reschedule if job is stopped prematurely
    }

    /**
     * Download model from backend and save locally
     */
    private void downloadModel(JobParameters params) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String userId = prefs.getString(KEY_USER_ID, null);

            if (userId == null) {
                Log.e(TAG, "❌ User ID not found. Cannot download model.");
                jobFinished(params, false);
                return;
            }

            Log.d(TAG, "========== MODEL DOWNLOAD JOB STARTED ==========");
            Log.d(TAG, "User ID: " + userId);
            Log.d(TAG, "Retry count: " + retryCount + "/" + MAX_RETRIES);

            // Make API call to download model
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(API_BASE_URL)
                    .addConverterFactory(GsonConverterFactory.create(gson))
                    .build();

            RetrofitApiService apiService = retrofit.create(RetrofitApiService.class);
            Call<RetrofitApiService.ModelDownloadResponse> call = apiService.downloadModel(userId);

            call.enqueue(new Callback<RetrofitApiService.ModelDownloadResponse>() {
                @Override
                public void onResponse(Call<RetrofitApiService.ModelDownloadResponse> call, 
                                     Response<RetrofitApiService.ModelDownloadResponse> response) {
                    if (response.isSuccessful() && response.body() != null) {
                        RetrofitApiService.ModelDownloadResponse modelData = response.body();
                        
                        Log.d(TAG, "✅ Model download successful!");
                        Log.d(TAG, "   Model version: " + modelData.model_version);
                        Log.d(TAG, "   Updated at: " + modelData.updated_at);
                        
                        // Save model and baseline stats locally
                        ModelStorageService.saveModel(ModelDownloadJobService.this, modelData);
                        
                        Log.d(TAG, "========== MODEL DOWNLOAD JOB COMPLETED SUCCESSFULLY ==========\n");
                        retryCount = 0; // Reset retry count on success
                        
                        // Schedule next model download for tomorrow 3:30 AM
                        scheduleNextModelDownload();
                        jobFinished(params, false);
                    } else {
                        Log.e(TAG, "❌ Model download failed with HTTP code: " + response.code());
                        try {
                            String errorBody = response.errorBody() != null ? response.errorBody().string() : "No error body";
                            Log.e(TAG, "Error response: " + errorBody);
                        } catch (Exception e) {
                            Log.e(TAG, "Could not read error body", e);
                        }
                        
                        handleDownloadFailure(params);
                    }
                }

                @Override
                public void onFailure(Call<RetrofitApiService.ModelDownloadResponse> call, Throwable t) {
                    Log.e(TAG, "❌ Model download failed with network error: " + t.getMessage(), t);
                    handleDownloadFailure(params);
                }

                /**
                 * Handle download failure with retry logic
                 */
                private void handleDownloadFailure(JobParameters params) {
                    if (retryCount < MAX_RETRIES) {
                        retryCount++;
                        Log.d(TAG, "⏳ Retrying model download (" + retryCount + "/" + MAX_RETRIES + ") after " + RETRY_DELAY_MS + "ms");
                        
                        // Retry after delay
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            downloadModel(params);
                        }, RETRY_DELAY_MS);
                    } else {
                        Log.e(TAG, "❌ Model download failed after " + MAX_RETRIES + " retries");
                        Log.d(TAG, "Will retry at next scheduled time...");
                        Log.d(TAG, "========== MODEL DOWNLOAD JOB FAILED (WILL RETRY) ==========\n");
                        retryCount = 0;
                        jobFinished(params, true); // Reschedule
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "❌ Error in downloadModel", e);
            Log.d(TAG, "========== MODEL DOWNLOAD JOB FAILED (EXCEPTION) ==========\n");
            jobFinished(params, true); // Reschedule
        }
    }

    /**
     * Schedule the next model download job for tomorrow's 3:30 AM
     */
    private void scheduleNextModelDownload() {
        BootReceiver.scheduleDaily330AMModelDownload(this);
    }
}
