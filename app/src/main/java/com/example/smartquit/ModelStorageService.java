package com.example.smartquit;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;

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
                Log.d(TAG, "✅ Saved baseline stats");
            }
            
            editor.apply();
            
            // Save agent data to file
            if (modelData.agent_data != null && !modelData.agent_data.isEmpty()) {
                File modelFile = new File(context.getFilesDir(), AGENT_DATA_FILENAME);
                try (FileOutputStream fos = new FileOutputStream(modelFile)) {
                    fos.write(modelData.agent_data.getBytes());
                    Log.d(TAG, "✅ Saved agent data to file: " + modelFile.getAbsolutePath());
                }
            }
            
            Log.d(TAG, "✅ Model and baseline stats saved successfully");
            Log.d(TAG, "   Model version: " + modelData.model_version);
            Log.d(TAG, "   Updated at: " + modelData.updated_at);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error saving model", e);
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
