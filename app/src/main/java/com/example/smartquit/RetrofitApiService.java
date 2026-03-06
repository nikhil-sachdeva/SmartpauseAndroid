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
    Call<UploadResponse> uploadSessions(@Body DailyUpload request);
    
    @retrofit2.http.GET("/api/v1/model/download/{user_id}")
    Call<ModelDownloadResponse> downloadModel(@retrofit2.http.Path("user_id") String userId);
    
    @retrofit2.http.GET("/api/v1/logs/{user_id}")
    Call<APILogsResponse> getAPILogs(
        @retrofit2.http.Path("user_id") String userId,
        @retrofit2.http.Query("limit") int limit,
        @retrofit2.http.Query("offset") int offset
    );
    
    @retrofit2.http.GET("/api/v1/home/weekly-usage/{user_id}")
    Call<WeeklyUsageResponse> getWeeklyUsage(@retrofit2.http.Path("user_id") String userId);
    
    /**
     * Request body for user registration
     */
    class RegistrationRequest {
        public String user_id;
        public DeviceInfo device_info;
        public java.util.List<String> apps_to_monitor;
        public boolean is_test_mode;  // Random allocation for A/B testing
        
        public RegistrationRequest(String userId, DeviceInfo deviceInfo, java.util.List<String> appsToMonitor, boolean isTestMode) {
            this.user_id = userId;
            this.device_info = deviceInfo;
            this.apps_to_monitor = appsToMonitor;
            this.is_test_mode = isTestMode;
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
        public boolean is_test_mode;  // Test/production mode allocation
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
        public int is_exploit;  // 0 = random/explore, 1 = Q-table exploit
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
     * Response from session upload endpoint (includes updated model)
     */
    class UploadResponse {
        public String status;
        public int sessions_count;
        public int queries_count;
        public int day_number;
        public int current_day;  // Add current day field
        public String date;
        public boolean baseline_exists;
        public BaselineStats baseline_stats;
        public String message;
        public ModelTraining model_training;
        public UpdatedModel updated_model;
    }

    /**
     * Training result information from upload
     */
    class ModelTraining {
        public String status;
        public int learned_transitions;
        public int q_table_size;
        public int training_steps;
        public boolean checkpoint_saved;
    }

    /**
     * Updated model data returned from upload
     */
    class UpdatedModel {
        public java.util.Map<String, java.util.List<Float>> q_table;  // Q-table as JSON
        public ModelMetadata metadata;
    }

    /**
     * Model metadata including hyperparameters
     */
    class ModelMetadata {
        public float epsilon;
        public float alpha;
        public float gamma;
        public int training_steps;
        public int q_table_states;
        public String last_updated;
    }

    /**
     * Model download response from backend
     */
    class ModelDownloadResponse {
        public String user_id;
        public int current_day;  // Add current day field
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

    /**
     * Weekly usage response from home endpoint
     */
    class WeeklyUsageResponse {
        public String user_id;
        public int period_days;
        public DateRange date_range;
        public java.util.List<String> apps_to_monitor;
        public java.util.List<DailyUsage> daily_usage;
        public java.util.Map<String, AppUsage> per_app_usage;
        public float total_usage_seconds;
        public String total_usage_formatted;
        public String message;
    }

    /**
     * Date range for weekly usage
     */
    class DateRange {
        public String start;
        public String end;
    }

    /**
     * Daily usage breakdown
     */
    class DailyUsage {
        public String date;
        public java.util.Map<String, Float> apps;
        public float total_seconds;
        public String total_formatted;
    }

    /**
     * Per-app usage statistics
     */
    class AppUsage {
        public float total_seconds;
        public String total_formatted;
    }
}
