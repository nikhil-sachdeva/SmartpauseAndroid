package com.example.smartquit;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Calendar;

/**
 * Service for storing and retrieving ML model and baseline stats locally
 */
public class ModelStorageService {
    
    private static final String TAG = "ModelStorageService";
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_MODEL_VERSION = "model_version";
    private static final String KEY_MODEL_UPDATED = "model_updated_at";
    private static final String KEY_BASELINE_STATS = "baseline_stats";
    private static final String KEY_CURRENT_DAY = "current_day";  // Add current day key
    private static final String KEY_REGISTRATION_TIMESTAMP = "registration_timestamp";
    private static final String KEY_TEST_MODE = "is_test_mode";
    private static final String MODEL_FILENAME = "smartquit_model.bin";
    private static final String AGENT_DATA_FILENAME = "agent_data.json";
    
    /**
     * Save model and baseline stats to local storage
     */
    public static void saveModel(Context context, RetrofitApiService.ModelDownloadResponse modelData) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            Gson gson = new Gson();
            
            // Save metadata
            editor.putInt(KEY_MODEL_VERSION, modelData.model_version);
            editor.putString(KEY_MODEL_UPDATED, modelData.updated_at);
            editor.putInt(KEY_CURRENT_DAY, modelData.current_day);  // Save current day
            
            // Save baseline stats as JSON
            if (modelData.baseline_stats != null) {
                String baselineStatsJson = gson.toJson(modelData.baseline_stats);
                editor.putString(KEY_BASELINE_STATS, baselineStatsJson);
                Log.d(TAG, "✅ Saved baseline stats: median_session=" + modelData.baseline_stats.median_session_usage_seconds + "s, epsilon=" + modelData.baseline_stats.epsilon);
            }
            
            editor.apply();
            
            // Save agent data (Q-table) to file
            // agent_data can be either a String or a JSON Object from the API
            if (modelData.agent_data != null) {
                String agentDataString;
                if (modelData.agent_data instanceof String) {
                    agentDataString = (String) modelData.agent_data;
                } else {
                    // Convert Object (Map/List) to JSON string
                    agentDataString = gson.toJson(modelData.agent_data);
                }
                
                if (!agentDataString.isEmpty()) {
                    File modelFile = new File(context.getFilesDir(), AGENT_DATA_FILENAME);
                    try (FileOutputStream fos = new FileOutputStream(modelFile)) {
                        fos.write(agentDataString.getBytes());
                        Log.d(TAG, "✅ Saved Q-table data to file: " + modelFile.getAbsolutePath());
                        Log.d(TAG, "   Q-table format: " + modelData.format);
                        Log.d(TAG, "   Q-table size: " + agentDataString.length() + " characters");
                    }
                } else {
                    Log.w(TAG, "⚠️  Empty agent data (Q-table) received from server");
                }
            } else {
                Log.w(TAG, "⚠️  No agent data (Q-table) received from server");
            }
            
            Log.d(TAG, "✅ Model and baseline stats saved successfully");
            Log.d(TAG, "   Model version: " + modelData.model_version);
            Log.d(TAG, "   Updated at: " + modelData.updated_at);
            
            // Notify SessionTrackerService about model update
            notifySessionTrackerService(context);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error saving model", e);
        }
    }
    
    /**
     * Notify SessionTrackerService that model data has been updated
     * This will refresh cached Q-table and baseline stats
     */
    private static void notifySessionTrackerService(Context context) {
        try {
            // Send broadcast to SessionTrackerService to refresh cached data
            android.content.Intent refreshIntent = new android.content.Intent("com.example.smartquit.MODEL_UPDATED");
            context.sendBroadcast(refreshIntent);
            Log.d(TAG, "✅ Sent model update broadcast to SessionTrackerService");
        } catch (Exception e) {
            Log.e(TAG, "Error sending model update broadcast", e);
        }
    }

    /**
     * Load baseline stats from local storage.
     * In TEST MODE: Returns default stats if none are saved (for time-based intervention).
     */
    public static RetrofitApiService.BaselineStats getBaselineStats(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String baselineStatsJson = prefs.getString(KEY_BASELINE_STATS, null);
            
            if (baselineStatsJson != null && !baselineStatsJson.isEmpty()) {
                Gson gson = new Gson();
                RetrofitApiService.BaselineStats stats = gson.fromJson(baselineStatsJson, RetrofitApiService.BaselineStats.class);
                Log.d(TAG, "✅ Loaded baseline stats");
                return stats;
            }
            
            // In test mode, return default stats if none are saved
            if (isTestMode(context)) {
                Log.d(TAG, "📱 Test mode - using default baseline stats (no server stats available)");
                return getDefaultBaselineStats();
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error loading baseline stats", e);
        }
        return null;
    }
    
    /**
     * Get default baseline stats for test mode when no server stats are available.
     * These defaults allow intervention to function without requiring server uploads.
     */
    public static RetrofitApiService.BaselineStats getDefaultBaselineStats() {
        RetrofitApiService.BaselineStats defaultStats = new RetrofitApiService.BaselineStats();
        defaultStats.median_session_usage_seconds = 60.0f;    // 60 seconds = first query at 1 minute
        defaultStats.median_target_app_usage_seconds = 300.0f; // 5 minutes default
        defaultStats.query_interval_seconds = 60.0f;          // Query every 60 seconds after first
        defaultStats.epsilon = 0.1f;                          // Standard epsilon for exploration
        
        Log.d(TAG, "📱 Created default baseline stats: median_session=60s, epsilon=0.1");
        return defaultStats;
    }
    
    /**
     * Get epsilon value for epsilon-greedy Q-learning
     * @return epsilon value (between 0 and 1), or 0.1 as default if not available
     */
    public static float getEpsilon(Context context) {
        RetrofitApiService.BaselineStats stats = getBaselineStats(context);
        if (stats != null) {
            Log.d(TAG, "✅ Retrieved epsilon from baseline stats: " + stats.epsilon);
            return stats.epsilon;
        }
        // Default epsilon if not available
        Log.w(TAG, "⚠️  No baseline stats available, using default epsilon: 0.1");
        return 0.1f;
    }
    
    /**
     * Load agent data from local file
     */
    public static String getAgentData(Context context) {
        try {
            File modelFile = new File(context.getFilesDir(), AGENT_DATA_FILENAME);
            if (modelFile.exists()) {
                byte[] buffer = new byte[(int) modelFile.length()];
                try (FileInputStream fis = new FileInputStream(modelFile)) {
                    fis.read(buffer);
                    String agentData = new String(buffer);
                    Log.d(TAG, "✅ Loaded agent data from file");
                    return agentData;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error loading agent data", e);
        }
        return null;
    }
    
    /**
     * Get current model version
     */
    public static int getModelVersion(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_MODEL_VERSION, 0);
    }
    
    /**
     * Get last model update time
     */
    public static String getLastModelUpdate(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_MODEL_UPDATED, "Never");
    }
    
    /**
     * Check if model is available locally
     */
    public static boolean isModelAvailable(Context context) {
        return getBaselineStats(context) != null && getAgentData(context) != null;
    }
    
    /**
     * Validate that both baseline stats and Q-table are available
     */
    public static boolean isCompleteModelAvailable(Context context) {
        return getBaselineStats(context) != null && getAgentData(context) != null;
    }

    /**
     * Get Q-table statistics for debugging
     */
    public static String getQTableInfo(Context context) {
        try {
            String agentData = getAgentData(context);
            if (agentData != null && !agentData.isEmpty()) {
                // Try to parse as JSON to get basic info
                JSONObject qTable = new JSONObject(agentData);
                return "Q-table loaded with " + qTable.length() + " states";
            }
        } catch (Exception e) {
            return "Error parsing Q-table: " + e.getMessage();
        }
        return "No Q-table available";
    }

    /**
     * Save Q-table and model metadata from upload response
     */
    public static void saveQTableFromUpload(Context context, RetrofitApiService.UpdatedModel updatedModel) {
        try {
            if (updatedModel.q_table == null || updatedModel.metadata == null) {
                Log.w(TAG, "⚠️ No Q-table or metadata in upload response");
                return;
            }

            // Convert Q-table Map to JSONObject
            org.json.JSONObject qTableJson = new org.json.JSONObject();
            for (String key : updatedModel.q_table.keySet()) {
                java.util.List<Float> values = updatedModel.q_table.get(key);
                org.json.JSONArray valueArray = new org.json.JSONArray();
                for (Float value : values) {
                    valueArray.put(value);
                }
                qTableJson.put(key, valueArray);
            }

            // Save Q-table data to file
            File modelFile = new File(context.getFilesDir(), AGENT_DATA_FILENAME);
            try (FileOutputStream fos = new FileOutputStream(modelFile)) {
                fos.write(qTableJson.toString().getBytes());
                Log.d(TAG, "✅ Saved updated Q-table from upload response: " + updatedModel.q_table.size() + " states");
            }

            // Update model metadata in SharedPreferences
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putInt(KEY_MODEL_VERSION, updatedModel.metadata.training_steps);
            editor.putString(KEY_MODEL_UPDATED, updatedModel.metadata.last_updated);
            // Note: current_day comes from upload response, not updatedModel.metadata
            
            // Update baseline stats with new epsilon
            RetrofitApiService.BaselineStats existingStats = getBaselineStats(context);
            if (existingStats != null) {
                // Preserve existing baseline stats but update epsilon
                existingStats.epsilon = updatedModel.metadata.epsilon;
                Gson gson = new Gson();
                String baselineStatsJson = gson.toJson(existingStats);
                editor.putString(KEY_BASELINE_STATS, baselineStatsJson);
                Log.d(TAG, "✅ Updated epsilon from upload: " + updatedModel.metadata.epsilon);
            }
            
            editor.apply();

            // Notify SessionTrackerService about model update
            notifySessionTrackerService(context);
            
            Log.d(TAG, "✅ Q-table and metadata updated from upload response");
            Log.d(TAG, "   Q-table size: " + updatedModel.q_table.size() + " states");
            Log.d(TAG, "   Training steps: " + updatedModel.metadata.training_steps);
            Log.d(TAG, "   Epsilon: " + updatedModel.metadata.epsilon);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error saving Q-table from upload response", e);
        }
    }

    /**
     * Save current day from upload response
     */
    public static void saveCurrentDay(Context context, int currentDay) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_CURRENT_DAY, currentDay).apply();
        Log.d(TAG, "✅ Saved current day: " + currentDay);
    }
    
    /**
     * Save baseline stats from upload response
     * This ensures baseline stats are always available even if model download failed
     */
    public static void saveBaselineStats(Context context, RetrofitApiService.BaselineStats baselineStats) {
        if (baselineStats == null) {
            Log.w(TAG, "⚠️ No baseline stats to save");
            return;
        }
        
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Gson gson = new Gson();
            String baselineStatsJson = gson.toJson(baselineStats);
            prefs.edit().putString(KEY_BASELINE_STATS, baselineStatsJson).apply();
            
            Log.d(TAG, "✅ Saved baseline stats from upload:");
            Log.d(TAG, "   median_session_usage_seconds: " + baselineStats.median_session_usage_seconds);
            Log.d(TAG, "   median_target_app_usage_seconds: " + baselineStats.median_target_app_usage_seconds);
            Log.d(TAG, "   epsilon: " + baselineStats.epsilon);
            
            // Notify SessionTrackerService to refresh cached baseline stats
            notifySessionTrackerService(context);
        } catch (Exception e) {
            Log.e(TAG, "❌ Error saving baseline stats", e);
        }
    }
    
    /**
     * Get current day for vibration eligibility check
     */
    public static int getCurrentDay(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_CURRENT_DAY, 0);  // Default to day 0
    }
    
    /**
     * Check if user is in test mode
     */
    public static boolean isTestMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_TEST_MODE, false);
    }
    
    /**
     * Get registration timestamp
     */
    public static long getRegistrationTimestamp(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_REGISTRATION_TIMESTAMP, 0);
    }
    
    /**
     * Check if it's after 3am of day 2 since registration (for test mode).
     * Day 0 = registration day, Day 1 = next day, Day 2 = intervention starts after 3am.
     * 
     * Example: Register on April 1st at any time
     * - Day 0: April 1st (baseline)
     * - Day 1: April 2nd (baseline)
     * - Day 2: April 3rd 3:00 AM onwards = intervention begins
     */
    public static boolean isAfterDay2ThreeAM(Context context) {
        long registrationTimestamp = getRegistrationTimestamp(context);
        if (registrationTimestamp == 0) {
            Log.w(TAG, "⚠️ No registration timestamp found");
            return false;
        }
        
        // Get the 3 AM of day 2 since registration
        Calendar registrationCal = Calendar.getInstance();
        registrationCal.setTimeInMillis(registrationTimestamp);
        
        // Reset to start of registration day (midnight)
        registrationCal.set(Calendar.HOUR_OF_DAY, 0);
        registrationCal.set(Calendar.MINUTE, 0);
        registrationCal.set(Calendar.SECOND, 0);
        registrationCal.set(Calendar.MILLISECOND, 0);
        
        // Add 2 days to get to day 2
        registrationCal.add(Calendar.DAY_OF_YEAR, 2);
        
        // Set to 3 AM
        registrationCal.set(Calendar.HOUR_OF_DAY, 3);
        
        long day2ThreeAM = registrationCal.getTimeInMillis();
        long now = System.currentTimeMillis();
        
        boolean isAfter = now >= day2ThreeAM;
        
        Log.d(TAG, "🕐 Test mode intervention check:");
        Log.d(TAG, "   Registration: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(registrationTimestamp));
        Log.d(TAG, "   Day 2 @ 3AM:  " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(day2ThreeAM));
        Log.d(TAG, "   Now:          " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(now));
        Log.d(TAG, "   Intervention allowed: " + isAfter);
        
        return isAfter;
    }
    
    /**
     * Check if vibrations/queries are allowed.
     * 
     * For TEST MODE: Uses local time-based check - intervention starts after 3am of day 2 since registration.
     * For PRODUCTION MODE: Requires baseline stats from server with valid median_session_usage_seconds.
     */
    public static boolean areVibrationsAllowed(Context context) {
        // Check if in test mode
        if (isTestMode(context)) {
            // Test mode: Use local time-based check (after 3am of day 2)
            boolean allowed = isAfterDay2ThreeAM(context);
            Log.d(TAG, "📱 Test mode - Vibrations allowed (time-based): " + allowed);
            return allowed;
        }
        
        // Production mode: Check baseline stats from server
        RetrofitApiService.BaselineStats stats = getBaselineStats(context);
        if (stats == null) {
            Log.d(TAG, "📱 Production mode - No baseline stats, vibrations not allowed");
            return false;
        }
        // Ensure median_session_usage_seconds is valid (positive value)
        if (stats.median_session_usage_seconds <= 0) {
            Log.w(TAG, "⚠️ Baseline stats exist but median_session_usage_seconds is invalid: " + stats.median_session_usage_seconds);
            return false;
        }
        Log.d(TAG, "📱 Production mode - Baseline stats valid, vibrations allowed");
        return true;
    }

    /**
     * Clear local model data
     */
    public static void clearModel(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            editor.remove(KEY_MODEL_VERSION);
            editor.remove(KEY_MODEL_UPDATED);
            editor.remove(KEY_BASELINE_STATS);
            editor.apply();
            
            File modelFile = new File(context.getFilesDir(), AGENT_DATA_FILENAME);
            if (modelFile.exists() && modelFile.delete()) {
                Log.d(TAG, "✅ Cleared local model data");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error clearing model", e);
        }
    }
}
