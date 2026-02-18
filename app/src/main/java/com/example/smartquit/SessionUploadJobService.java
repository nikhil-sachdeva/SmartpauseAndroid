package com.example.smartquit;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Job service to handle daily session uploads at 3 AM
 */
public class SessionUploadJobService extends JobService {

    private static final String TAG = "SessionUploadJobService";
    private static final String API_BASE_URL = "https://smartquit-cyber.onrender.com";
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_USER_ID = "user_id";
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 30000; // 30 seconds between retries

    private int retryCount = 0;

    @Override
    public boolean onStartJob(JobParameters params) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String currentTime = sdf.format(new java.util.Date(System.currentTimeMillis()));
        Log.d(TAG, "========== UPLOAD JOB TRIGGERED AT: " + currentTime + " ==========");
        
        // Run upload in background thread
        new Thread(() -> {
            uploadSessions(params);
        }).start();
        
        return true; // Job is running on a background thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        Log.d(TAG, "Upload job stopped");
        return true; // Reschedule if job is stopped prematurely
    }

    /**
     * Upload ALL sessions from local DB and clear the database
     */
    private void uploadSessions(JobParameters params) {
        try {
            AppDatabase db = AppDatabase.getDatabase(this);
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String userId = prefs.getString(KEY_USER_ID, null);

            if (userId == null) {
                Log.e(TAG, "❌ User ID not found. Cannot upload sessions.");
                jobFinished(params, false);
                return;
            }

            Log.d(TAG, "========== UPLOAD JOB STARTED ==========");
            Log.d(TAG, "User ID: " + userId);
            Log.d(TAG, "Retry count: " + retryCount + "/" + MAX_RETRIES);
            Log.d(TAG, "Current time: " + System.currentTimeMillis());

            // Get ALL sessions from local database
            List<AppSession> sessions = db.appSessionDao().getAllSessions();
            
            if (sessions == null || sessions.isEmpty()) {
                Log.d(TAG, "⚠️ No sessions to upload. Database is empty.");
                Log.d(TAG, "========== UPLOAD JOB COMPLETED (NO SESSIONS) ==========\n");
                jobFinished(params, false);
                return;
            }

            Log.d(TAG, "✅ Found " + sessions.size() + " sessions to upload");

            // Get ALL queries from local database
            List<Query> queries = db.queryDao().getAllQueries();
            
            if (queries != null && !queries.isEmpty()) {
                Log.d(TAG, "✅ Found " + queries.size() + " queries to upload");
            } else {
                Log.d(TAG, "⚠️ No queries to upload.");
            }

            // Get today's date for the upload
            String todayDate = getTodayDate();
            Log.d(TAG, "Upload date: " + todayDate);

            // Convert AppSession to Session (API model)
            List<RetrofitApiService.Session> apiSessions = new ArrayList<>();
            for (AppSession appSession : sessions) {
                RetrofitApiService.Session session = new RetrofitApiService.Session();
                session.app_name = appSession.appName;
                session.start_time = appSession.startTime;
                session.end_time = appSession.endTime;
                session.duration_seconds = (float) appSession.durationSeconds;
                session.num_vibrations = appSession.numVibrations;  // Use actual count
                session.user_complied = appSession.userComplied;  // Use actual compliance
                session.group_id = appSession.groupId;  // Include group ID
                apiSessions.add(session);
                
                Log.d(TAG, "  Session: " + appSession.appName);
                Log.d(TAG, "    Start: " + appSession.startTime + " (length=" + appSession.startTime.length() + ")");
                Log.d(TAG, "    End: " + appSession.endTime + " (length=" + appSession.endTime.length() + ")");
                Log.d(TAG, "    Duration: " + appSession.durationSeconds + "s");
                Log.d(TAG, "    Date: " + appSession.date);
                Log.d(TAG, "    Vibrations: " + appSession.numVibrations);
                Log.d(TAG, "    User Complied: " + appSession.userComplied);
                Log.d(TAG, "    Group ID: " + appSession.groupId);
            }

            // Convert Query to QueryData (API model)
            List<RetrofitApiService.QueryData> apiQueries = new ArrayList<>();
            if (queries != null) {
                for (Query query : queries) {
                    RetrofitApiService.QueryData queryData = new RetrofitApiService.QueryData();
                    queryData.group_id = query.groupId;
                    queryData.timestamp = query.timestamp;
                    queryData.current_app = query.currentApp;
                    
                    // Parse state string "[0,1,2,1]" to List<Integer>
                    try {
                        String stateStr = query.state.replaceAll("[\\[\\]\\s]", "");
                        String[] stateParts = stateStr.split(",");
                        queryData.state = new ArrayList<>();
                        for (String part : stateParts) {
                            queryData.state.add(Integer.parseInt(part));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing state for query: " + query.state, e);
                        // Provide default state if parsing fails
                        queryData.state = java.util.Arrays.asList(0, 0, 0, 0);
                    }
                    
                    queryData.action = query.action;
                    queryData.compliance = query.compliance;
                    apiQueries.add(queryData);
                    
                    Log.d(TAG, "  Query: " + query.currentApp);
                    Log.d(TAG, "    Group ID: " + query.groupId);
                    Log.d(TAG, "    Timestamp: " + query.timestamp);
                    Log.d(TAG, "    State: " + query.state);
                    Log.d(TAG, "    Action: " + query.action);
                    Log.d(TAG, "    Compliance: " + query.compliance);
                }
            }

            // Create upload request
            RetrofitApiService.DailyUpload uploadRequest = new RetrofitApiService.DailyUpload();
            uploadRequest.user_id = userId;
            uploadRequest.date = todayDate;
            uploadRequest.sessions = apiSessions;
            uploadRequest.queries = apiQueries;

            Log.d(TAG, "📤 Uploading " + apiSessions.size() + " sessions and " + apiQueries.size() + " queries to API...");
            Log.d(TAG, "Upload request date format: " + todayDate);
            Log.d(TAG, "Sample session start_time format: " + (apiSessions.size() > 0 ? apiSessions.get(0).start_time : "N/A"));

            // Make API call
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Retrofit retrofit = new Retrofit.Builder()
                    .baseUrl(API_BASE_URL)
                    .addConverterFactory(GsonConverterFactory.create(gson))
                    .build();

            RetrofitApiService apiService = retrofit.create(RetrofitApiService.class);
            Call<RetrofitApiService.UploadResponse> call = apiService.uploadSessions(uploadRequest);

            call.enqueue(new Callback<RetrofitApiService.UploadResponse>() {
                @Override
                public void onResponse(Call<RetrofitApiService.UploadResponse> call, Response<RetrofitApiService.UploadResponse> response) {
                    if (response.isSuccessful()) {
                        RetrofitApiService.UploadResponse uploadResponse = response.body();
                        Log.d(TAG, "✅ Upload successful!");
                        Log.d(TAG, "Response: " + uploadResponse.message);
                                                // Save current day from upload response
                        ModelStorageService.saveCurrentDay(SessionUploadJobService.this, uploadResponse.current_day);
                        Log.d(TAG, "Current day: " + uploadResponse.current_day);
                                                // Save updated Q-table and metadata from upload response
                        if (uploadResponse.updated_model != null) {
                            Log.d(TAG, "✅ Processing updated model from upload response...");
                            ModelStorageService.saveQTableFromUpload(SessionUploadJobService.this, uploadResponse.updated_model);
                            
                            if (uploadResponse.model_training != null) {
                                Log.d(TAG, "   Training result: " + uploadResponse.model_training.learned_transitions + " transitions learned");
                                Log.d(TAG, "   Q-table size: " + uploadResponse.model_training.q_table_size + " states");
                                Log.d(TAG, "   Training steps: " + uploadResponse.model_training.training_steps);
                                Log.d(TAG, "   Checkpoint saved: " + uploadResponse.model_training.checkpoint_saved);
                            }
                            
                            if (uploadResponse.updated_model.metadata != null) {
                                Log.d(TAG, "   Updated epsilon: " + uploadResponse.updated_model.metadata.epsilon);
                                Log.d(TAG, "   Model last updated: " + uploadResponse.updated_model.metadata.last_updated);
                            }
                        } else {
                            Log.w(TAG, "⚠️ No updated model data in upload response");
                        }
                        
                        // Clear ALL sessions and queries from database after successful upload
                        new Thread(() -> {
                            try {
                                db.appSessionDao().deleteAllSessions();
                                db.queryDao().deleteAllQueries();
                                Log.d(TAG, "✅ Cleared all sessions and queries from database. Starting fresh for next day.");
                                
                                // Schedule next upload for tomorrow 3 AM
                                scheduleNextUpload();
                                
                                Log.d(TAG, "========== UPLOAD JOB COMPLETED SUCCESSFULLY ==========\n");
                            } catch (Exception e) {
                                Log.e(TAG, "❌ Error clearing data or scheduling next upload", e);
                                Log.d(TAG, "========== UPLOAD JOB COMPLETED WITH WARNING ==========\n");
                            }
                        }).start();
                        
                        retryCount = 0; // Reset retry count on success
                        jobFinished(params, false);
                    } else {
                        Log.e(TAG, "❌ Upload failed with HTTP code: " + response.code());
                        try {
                            String errorBody = response.errorBody() != null ? response.errorBody().string() : "No error body";
                            Log.e(TAG, "Error response: " + errorBody);
                        } catch (Exception e) {
                            Log.e(TAG, "Could not read error body", e);
                        }
                        
                        handleUploadFailure(params);
                    }
                }

                @Override
                public void onFailure(Call<RetrofitApiService.UploadResponse> call, Throwable t) {
                    Log.e(TAG, "❌ Upload failed with network error: " + t.getMessage(), t);
                    handleUploadFailure(params);
                }

                /**
                 * Handle upload failure with retry logic
                 */
                private void handleUploadFailure(JobParameters params) {
                    if (retryCount < MAX_RETRIES) {
                        retryCount++;
                        Log.d(TAG, "⏳ Retrying upload (" + retryCount + "/" + MAX_RETRIES + ") after " + RETRY_DELAY_MS + "ms");
                        
                        // Retry after delay
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            uploadSessions(params);
                        }, RETRY_DELAY_MS);
                    } else {
                        Log.e(TAG, "❌ Upload failed after " + MAX_RETRIES + " retries");
                        Log.e(TAG, "Will retry upload at next scheduled time...");
                        Log.d(TAG, "========== UPLOAD JOB FAILED (WILL RETRY) ==========\n");
                        retryCount = 0;
                        jobFinished(params, true); // Reschedule
                    }
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "❌ Error in uploadSessions", e);
            Log.d(TAG, "========== UPLOAD JOB FAILED (EXCEPTION) ==========\n");
            jobFinished(params, true); // Reschedule
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
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            return sdf.format(calendar.getTime());
        }
    }

    /**
     * Reschedule the next upload job for tomorrow's 3 AM
     */
    private void scheduleNextUpload() {
        BootReceiver.scheduleDaily3AMUpload(this);
        Log.d(TAG, "✅ Next upload job scheduled for tomorrow 3 AM");
    }
}
