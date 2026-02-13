package com.example.smartquit;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

/**
 * Retrofit API Service for SmartPauseApp backend communication
 */
public interface RetrofitApiService {
    
    @POST("/api/v1/users/register")
    Call<RegistrationResponse> registerUser(@Body RegistrationRequest request);
    
    @POST("/api/v1/sessions/upload")
    Call<Object> uploadSessions(@Body DailyUpload request);
    
    @retrofit2.http.GET("/api/v1/model/download/{user_id}")
    Call<ModelDownloadResponse> downloadModel(@retrofit2.http.Path("user_id") String userId);
    
    @retrofit2.http.GET("/api/v1/logs/{user_id}")
    Call<APILogsResponse> getAPILogs(
        @retrofit2.http.Path("user_id") String userId,
        @retrofit2.http.Query("limit") int limit,
        @retrofit2.http.Query("offset") int offset
    );
    
    /**
     * Request body for user registration
     */
    class RegistrationRequest {
        public String user_id;
        public DeviceInfo device_info;
        public java.util.List<String> apps_to_monitor;
        
        public RegistrationRequest(String userId, DeviceInfo deviceInfo, java.util.List<String> appsToMonitor) {
            this.user_id = userId;
            this.device_info = deviceInfo;
            this.apps_to_monitor = appsToMonitor;
        }
    }
    
    /**
     * Device information for registration
     */
    class DeviceInfo {
        public String device_name;
        public String device_model;
        public String android_version;
        public String app_version;
        
        public DeviceInfo(String deviceName, String deviceModel, String androidVersion, String appVersion) {
            this.device_name = deviceName;
            this.device_model = deviceModel;
            this.android_version = androidVersion;
            this.app_version = appVersion;
        }
    }
    
    /**
     * Response from registration endpoint
     */
    class RegistrationResponse {
        public String status;
        public String user_id;
        public java.util.List<String> apps_to_monitor;
        public String message;
    }

    /**
     * Session data for upload
     */
    class Session {
        public String app_name;
        public String start_time;
        public String end_time;
        public float duration_seconds;
        public int num_vibrations;
        public boolean user_complied;
        public int group_id;
    }

    /**
     * Query data for upload (vibration decision points)
     */
    class QueryData {
        public int group_id;
        public String timestamp;
        public String current_app;
        public java.util.List<Integer> state;  // State array as list of integers
        public int action;
        public int compliance;
    }

    /**
     * Daily upload request with all sessions and queries from a day
     */
    class DailyUpload {
        public String user_id;
        public java.util.List<Session> sessions;
        public java.util.List<QueryData> queries;  // Add queries to upload
        public String date; // YYYY-MM-DD format
    }

    /**
     * Model download response from backend
     */
    class ModelDownloadResponse {
        public String user_id;
        public int model_version;
        public String updated_at;
        public BaselineStats baseline_stats;
        public java.util.Map<String, Object> reward_config;
        public java.util.List<String> social_media_apps;
        public String agent_data;  // Serialized model data (JSON or hex)
        public String format;  // "json" or "binary"
    }

    /**
     * Baseline statistics for user
     */
    class BaselineStats {
        public float median_target_app_usage_seconds;
        public float median_session_usage_seconds;
        public float query_interval_seconds;
        public float epsilon;  // For epsilon-greedy Q-learning
    }

    /**
     * API logs response from backend
     */
    class APILogsResponse {
        public String user_id;
        public int total_logs;
        public int returned;
        public int offset;
        public int limit;
        public java.util.List<APILogEntry> logs;
    }

    /**
     * Individual API log entry
     */
    class APILogEntry {
        public int id;
        public String endpoint;
        public String method;
        public int status_code;
        public String error_message;
        public String created_at;
        public String updated_at;
    }
}
