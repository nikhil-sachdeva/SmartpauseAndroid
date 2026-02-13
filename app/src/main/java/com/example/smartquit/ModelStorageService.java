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

/**
 * Service for storing and retrieving ML model and baseline stats locally
 */
public class ModelStorageService {
    
    private static final String TAG = "ModelStorageService";
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_MODEL_VERSION = "model_version";
    private static final String KEY_MODEL_UPDATED = "model_updated_at";
    private static final String KEY_BASELINE_STATS = "baseline_stats";
    private static final String MODEL_FILENAME = "smartquit_model.bin";
    private static final String AGENT_DATA_FILENAME = "agent_data.json";
    
    /**
     * Save model and baseline stats to local storage
     */
    public static void saveModel(Context context, RetrofitApiService.ModelDownloadResponse modelData) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();
            
            // Save metadata
            editor.putInt(KEY_MODEL_VERSION, modelData.model_version);
            editor.putString(KEY_MODEL_UPDATED, modelData.updated_at);
            
            // Save baseline stats as JSON
            if (modelData.baseline_stats != null) {
                Gson gson = new Gson();
                String baselineStatsJson = gson.toJson(modelData.baseline_stats);
                editor.putString(KEY_BASELINE_STATS, baselineStatsJson);
                Log.d(TAG, "✅ Saved baseline stats: median_session=" + modelData.baseline_stats.median_session_usage_seconds + "s, epsilon=" + modelData.baseline_stats.epsilon);
            }
            
            editor.apply();
            
            // Save agent data (Q-table) to file
            if (modelData.agent_data != null && !modelData.agent_data.isEmpty()) {
                File modelFile = new File(context.getFilesDir(), AGENT_DATA_FILENAME);
                try (FileOutputStream fos = new FileOutputStream(modelFile)) {
                    fos.write(modelData.agent_data.getBytes());
                    Log.d(TAG, "✅ Saved Q-table data to file: " + modelFile.getAbsolutePath());
                    Log.d(TAG, "   Q-table format: " + modelData.format);
                    Log.d(TAG, "   Q-table size: " + modelData.agent_data.length() + " characters");
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
     * Load baseline stats from local storage
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
        } catch (Exception e) {
            Log.e(TAG, "❌ Error loading baseline stats", e);
        }
        return null;
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
