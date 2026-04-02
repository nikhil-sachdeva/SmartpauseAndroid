package com.example.smartquit;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String API_BASE_URL = "https://smartpauseappv2.vercel.app";

    private AppDatabase db;
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_REGISTERED = "is_registered";
    private static final String KEY_USER_ID = "user_id";
    private Button grantAccessButton;
    private Button adminConsoleButton;
    private TextView trackingStatusText;
    private TextView userIdText;
    private TableLayout weeklyUsageTable;
    private LinearLayout barChartContainer;
    private TextView totalUsageText;
    private TextView weeklyUsageTitle;
    private View barChartCard;
    private View tableCard;
    private ProgressBar loadingProgressBar;
    private TextView errorText;
    private static final String KEY_LAST_PERMISSION_GRANTED_DATE = "last_permission_granted_date";
    private static final String KEY_UPLOAD_SCHEDULER_STARTED = "upload_scheduler_started";

    private RetrofitApiService apiService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        adminConsoleButton = findViewById(R.id.adminConsoleButton);
        if (adminConsoleButton != null)
            adminConsoleButton.setVisibility(View.GONE);
        // Check if user is registered - redirect to registration if not
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean isRegistered = prefs.getBoolean(KEY_REGISTERED, false);

        if (!isRegistered) {
            // First time user - launch registration activity
            Intent registrationIntent = new Intent(MainActivity.this, RegistrationActivity.class);
            startActivity(registrationIntent);
            finish();
            return;
        }

        // Check if onboarding is complete - redirect to onboarding if not
        if (!OnboardingActivity.isOnboardingComplete(this)) {
            Intent onboardingIntent = new Intent(MainActivity.this, OnboardingActivity.class);
            startActivity(onboardingIntent);
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        db = AppDatabase.getDatabase(this);
        adminConsoleButton = findViewById(R.id.adminConsoleButton);
        if (adminConsoleButton != null)
            adminConsoleButton.setVisibility(View.GONE);

        // Check if user has granted usage access permission
        if (!hasUsageAccessPermission()) {
            // Show grant access button
            grantAccessButton = findViewById(R.id.grantAccessButton);
            if (grantAccessButton != null) {
                grantAccessButton.setVisibility(View.VISIBLE);
                grantAccessButton.setOnClickListener(v -> openUsageAccessSettings());
            }

            // Hide other buttons
            adminConsoleButton = findViewById(R.id.adminConsoleButton);
            if (adminConsoleButton != null)
                adminConsoleButton.setVisibility(View.GONE);

            Toast.makeText(this, "Please grant usage access permission to use this app", Toast.LENGTH_LONG).show();
            return;
        }

        // User has permission, show normal buttons
        grantAccessButton = findViewById(R.id.grantAccessButton);
        if (grantAccessButton != null) {
            grantAccessButton.setVisibility(View.GONE);
        }

        // Request battery optimization exemption for reliable background operation
        requestBatteryOptimizationExemption();

        // Initialize service resilience and start the background session tracker
        // service
        // This ensures the service restarts automatically if killed by the system
        BootReceiver.initializeServiceResilience(this);

        // Start the session upload scheduler service (only once per app session)
        startUploadSchedulerIfNeeded();

        // Set up the Admin Console button
        adminConsoleButton = findViewById(R.id.adminConsoleButton);
        if (adminConsoleButton != null) {
            adminConsoleButton.setVisibility(View.VISIBLE);
            adminConsoleButton.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, AdminConsoleActivity.class);
                startActivity(intent);
            });
        }

        // Initialize UI elements for weekly usage
        trackingStatusText = findViewById(R.id.trackingStatusText);
        userIdText = findViewById(R.id.userIdText);
        weeklyUsageTable = findViewById(R.id.weeklyUsageTable);
        barChartContainer = findViewById(R.id.barChartContainer);
        totalUsageText = findViewById(R.id.totalUsageText);
        weeklyUsageTitle = findViewById(R.id.weeklyUsageTitle);
        barChartCard = findViewById(R.id.barChartCard);
        tableCard = findViewById(R.id.tableCard);
        loadingProgressBar = findViewById(R.id.loadingProgressBar);
        errorText = findViewById(R.id.errorText);

        // Initialize API service
        initializeApiService();

        // Update tracking status
        updateTrackingStatus();

        // Show user ID after permission is granted
        updateUserIdDisplay();

        // Fetch latest model and user data
        fetchLatestModelAndUserData();

        // Load weekly usage data
        loadWeeklyUsageData();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Update tracking status when app resumes
        updateTrackingStatus();

        // Check if permission was granted while activity was paused
        if (hasUsageAccessPermission()) {
            grantAccessButton = findViewById(R.id.grantAccessButton);
            if (grantAccessButton != null && grantAccessButton.getVisibility() == View.VISIBLE) {
                // Permission was just granted, update UI
                grantAccessButton.setVisibility(View.GONE);

                // Start the background services
                // Initialize service resilience and start the service
                BootReceiver.initializeServiceResilience(this);

                // Start the session upload scheduler service (only if not already started)
                startUploadSchedulerIfNeeded();

                // Show admin console button
                adminConsoleButton = findViewById(R.id.adminConsoleButton);
                if (adminConsoleButton != null) {
                    adminConsoleButton.setVisibility(View.VISIBLE);
                }

                Toast.makeText(this, "Permission granted! App is now tracking sessions.", Toast.LENGTH_SHORT).show();

                // Initialize and load weekly usage
                initializeWeeklyUsageUI();
                loadWeeklyUsageData();
                updateTrackingStatus();
                updateUserIdDisplay();
            } else {
                // Permission was already granted - refresh data on app resume
                // Fetch latest model data (current_day, baseline_stats, Q-table)
                fetchLatestModelAndUserData();

                // Refresh weekly usage data
                loadWeeklyUsageData();
            }
        }
    }

    /**
     * Check if the app has been granted usage access permission
     */
    private boolean hasUsageAccessPermission() {
        try {
            AppOpsManager appOpsManager = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (appOpsManager == null) {
                Log.w(TAG, "AppOpsManager is null");
                return false;
            }

            int mode;

            // Handle different Android versions with better error handling
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    mode = appOpsManager.unsafeCheckOpNoThrow(
                            AppOpsManager.OPSTR_GET_USAGE_STATS,
                            android.os.Process.myUid(),
                            getPackageName());
                } else {
                    mode = appOpsManager.checkOpNoThrow(
                            AppOpsManager.OPSTR_GET_USAGE_STATS,
                            android.os.Process.myUid(),
                            getPackageName());
                }

                boolean hasPermission = mode == AppOpsManager.MODE_ALLOWED;
                Log.d(TAG, "Usage access permission check - Mode: " + mode + ", Allowed: " + hasPermission +
                        ", Android version: " + Build.VERSION.SDK_INT);
                return hasPermission;

            } catch (SecurityException e) {
                Log.w(TAG, "SecurityException when checking usage access permission: " + e.getMessage());
                return false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error checking usage access permission", e);
            return false;
        }
    }

    /**
     * Open the usage access settings page
     */
    private void openUsageAccessSettings() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        startActivity(intent);
    }

    /**
     * Request battery optimization exemption for reliable 24/7 background service
     * This is critical for Chinese OEM phones (Xiaomi, Huawei, Oppo, etc.)
     */
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            String packageName = getPackageName();
            Log.d("MainActivity requestBatteryOptimizationExemption",
                    pm != null ? "PowerManager obtained successfully" : "Failed to get PowerManager");
            Log.d("MainActivity requestBatteryOptimizationExemption",
                    pm != null && pm.isIgnoringBatteryOptimizations(packageName)
                            ? "App is already ignoring battery optimizations"
                            : "App is NOT ignoring battery optimizations");

            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.d("MainActivity requestBatteryOptimizationExemption", "Requesting battery optimization exemption");
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + packageName));
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e("MainActivity requestBatteryOptimizationExemption",
                            "Failed to request battery optimization exemption: " + e.getMessage());
                    // Fallback: open battery optimization settings
                    try {
                        Intent fallbackIntent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                        startActivity(fallbackIntent);
                    } catch (Exception e2) {
                        Log.e("MainActivity requestBatteryOptimizationExemption",
                                "Failed to open battery settings: " + e2.getMessage());
                    }
                }
            } else {
                Log.d("MainActivity requestBatteryOptimizationExemption", "Already exempt from battery optimization - skipping intent");
            }
        }
    }

    /**
     * Check if SessionTrackerService is running and update status
     */
    private void updateTrackingStatus() {
        if (trackingStatusText == null)
            return;

        if (!hasUsageAccessPermission()) {
            trackingStatusText.setText("⚠️ Tracking disabled - permission required");
            trackingStatusText.setTextColor(getColor(android.R.color.holo_orange_dark));
            return;
        }

        boolean isServiceRunning = isSessionTrackerServiceRunning();

        if (isServiceRunning) {
            trackingStatusText.setText("🟢 Tracking active");
            trackingStatusText.setTextColor(getColor(android.R.color.holo_green_dark));
        } else {
            trackingStatusText.setText("🔴 Tracking stopped");
            trackingStatusText.setTextColor(getColor(android.R.color.holo_red_dark));
        }
    }

    /**
     * Check if SessionTrackerService is currently running
     */
    private boolean isSessionTrackerServiceRunning() {
        try {
            android.app.ActivityManager activityManager = (android.app.ActivityManager) getSystemService(
                    Context.ACTIVITY_SERVICE);
            if (activityManager == null)
                return false;

            for (android.app.ActivityManager.RunningServiceInfo service : activityManager
                    .getRunningServices(Integer.MAX_VALUE)) {
                if (SessionTrackerService.class.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking if service is running", e);
        }
        return false;
    }

    /**
     * Initialize Retrofit API service
     */
    private void initializeApiService() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(API_BASE_URL)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();
        apiService = retrofit.create(RetrofitApiService.class);
    }

    /**
     * Initialize weekly usage UI elements when permission is granted
     */
    private void initializeWeeklyUsageUI() {
        trackingStatusText = findViewById(R.id.trackingStatusText);
        userIdText = findViewById(R.id.userIdText);
        weeklyUsageTable = findViewById(R.id.weeklyUsageTable);
        barChartContainer = findViewById(R.id.barChartContainer);
        totalUsageText = findViewById(R.id.totalUsageText);
        weeklyUsageTitle = findViewById(R.id.weeklyUsageTitle);
        barChartCard = findViewById(R.id.barChartCard);
        tableCard = findViewById(R.id.tableCard);
        loadingProgressBar = findViewById(R.id.loadingProgressBar);
        errorText = findViewById(R.id.errorText);
    }

    /**
     * Load weekly usage data from API
     */
    private void loadWeeklyUsageData() {
        if (apiService == null) {
            showNoDataUI("API service not initialized");
            return;
        }

        // Show loading state
        showLoadingUI();

        // Get user ID from SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String userId = prefs.getString(KEY_USER_ID, null);

        if (userId == null) {
            showNoDataUI("User not registered");
            return;
        }

        // Make API call
        Call<RetrofitApiService.WeeklyUsageResponse> call = apiService.getWeeklyUsage(userId);
        call.enqueue(new Callback<RetrofitApiService.WeeklyUsageResponse>() {
            @Override
            public void onResponse(Call<RetrofitApiService.WeeklyUsageResponse> call,
                    Response<RetrofitApiService.WeeklyUsageResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    RetrofitApiService.WeeklyUsageResponse data = response.body();
                    if (data.daily_usage != null && !data.daily_usage.isEmpty()) {
                        showDataUI(data);
                    } else {
                        showNoDataUI("No usage data available");
                    }
                } else {
                    showNoDataUI("Failed to load data");
                }
            }

            @Override
            public void onFailure(Call<RetrofitApiService.WeeklyUsageResponse> call, Throwable t) {
                showNoDataUI("Network error: " + t.getMessage());
            }
        });
    }

    /**
     * Fetch latest model, baseline stats, and user data from API
     */
    private void fetchLatestModelAndUserData() {
        if (apiService == null) {
            Log.w(TAG, "API service not initialized for model fetch");
            return;
        }

        // Get user ID from SharedPreferences
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String userId = prefs.getString(KEY_USER_ID, null);

        if (userId == null) {
            Log.w(TAG, "User ID not found for model fetch");
            return;
        }

        Log.d(TAG, "Fetching latest model and user data for user: " + userId);

        // Make API call to download model
        Call<RetrofitApiService.ModelDownloadResponse> call = apiService.downloadModel(userId);
        call.enqueue(new Callback<RetrofitApiService.ModelDownloadResponse>() {
            @Override
            public void onResponse(Call<RetrofitApiService.ModelDownloadResponse> call,
                    Response<RetrofitApiService.ModelDownloadResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    RetrofitApiService.ModelDownloadResponse data = response.body();
                    Log.d(TAG, "Successfully fetched model data - Current day: " + data.current_day +
                            ", Model version: " + data.model_version + ", Updated: " + data.updated_at);

                    // Process the received data
                    processModelDownloadResponse(data);
                } else {
                    Log.w(TAG, "Failed to fetch model data: " + response.code() + " " + response.message());
                }
            }

            @Override
            public void onFailure(Call<RetrofitApiService.ModelDownloadResponse> call, Throwable t) {
                Log.w(TAG, "Network error fetching model data: " + t.getMessage());
            }
        });
    }

    /**
     * Process the model download response and update local data
     */
    private void processModelDownloadResponse(RetrofitApiService.ModelDownloadResponse data) {
        try {
            Log.d(TAG, "Processing model download response...");

            // Save all model data via ModelStorageService (baseline_stats, Q-table,
            // current_day, etc.)
            // This will also notify SessionTrackerService to refresh its cached data
            ModelStorageService.saveModel(this, data);

            // Log current day update
            Log.d(TAG, "✅ Model data saved - Current day: " + data.current_day +
                    ", Model version: " + data.model_version);

            // Log baseline stats if available
            if (data.baseline_stats != null) {
                Log.d(TAG,
                        "Baseline stats - Target app usage: " + data.baseline_stats.median_target_app_usage_seconds
                                + "s" +
                                ", Session usage: " + data.baseline_stats.median_session_usage_seconds + "s" +
                                ", Query interval: " + data.baseline_stats.query_interval_seconds + "s" +
                                ", Epsilon: " + data.baseline_stats.epsilon);
            } else {
                Log.d(TAG, "No baseline stats available");
            }

            // Log model info
            if (data.agent_data != null) {
                String agentDataStr = data.agent_data instanceof String
                        ? (String) data.agent_data
                        : new Gson().toJson(data.agent_data);
                Log.d(TAG,
                        "Agent data received - Format: " + data.format + ", Size: " + agentDataStr.length() + " chars");
            } else {
                Log.d(TAG, "No agent data available");
            }

            // Log social media apps if available
            if (data.social_media_apps != null && !data.social_media_apps.isEmpty()) {
                Log.d(TAG, "Social media apps: " + data.social_media_apps.toString());
            }

            // Update tracking status text to show data freshness
            runOnUiThread(() -> {
                if (trackingStatusText != null && hasUsageAccessPermission() && isSessionTrackerServiceRunning()) {
                    String statusText = "🟢 Tracking active";
                    if (data.current_day > 0) {
                        statusText += " • Day " + data.current_day;
                    }
                    if (data.updated_at != null) {
                        statusText += " • Updated " + formatUpdateTime(data.updated_at);
                    }
                    trackingStatusText.setText(statusText);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error processing model download response", e);
        }
    }

    /**
     * Format the update time for display
     */
    private String formatUpdateTime(String updated_at) {
        try {
            // Simple formatting - just return a relative time indicator
            return "today";
        } catch (Exception e) {
            return "recently";
        }
    }

    /**
     * Show loading state
     */
    private void showLoadingUI() {
        if (loadingProgressBar != null)
            loadingProgressBar.setVisibility(View.VISIBLE);
        if (errorText != null)
            errorText.setVisibility(View.GONE);

        // Hide all data-related elements during loading
        if (weeklyUsageTitle != null)
            weeklyUsageTitle.setVisibility(View.GONE);
        if (totalUsageText != null)
            totalUsageText.setVisibility(View.GONE);
        if (barChartCard != null)
            barChartCard.setVisibility(View.GONE);
        if (tableCard != null)
            tableCard.setVisibility(View.GONE);
    }

    /**
     * Show "no data available" state
     */
    private void showNoDataUI(String message) {
        if (loadingProgressBar != null)
            loadingProgressBar.setVisibility(View.GONE);
        if (errorText != null) {
            errorText.setText("📄 No data available");
            errorText.setVisibility(View.VISIBLE);
        }

        // Hide all data-related elements when no data
        if (weeklyUsageTitle != null)
            weeklyUsageTitle.setVisibility(View.GONE);
        if (totalUsageText != null)
            totalUsageText.setVisibility(View.GONE);
        if (barChartCard != null)
            barChartCard.setVisibility(View.GONE);
        if (tableCard != null)
            tableCard.setVisibility(View.GONE);

        Log.d(TAG, "No data: " + message);
    }

    /**
     * Show data with table and chart
     */
    private void showDataUI(RetrofitApiService.WeeklyUsageResponse data) {
        if (loadingProgressBar != null)
            loadingProgressBar.setVisibility(View.GONE);
        if (errorText != null)
            errorText.setVisibility(View.GONE);

        // Show all data-related elements when data is available
        if (weeklyUsageTitle != null)
            weeklyUsageTitle.setVisibility(View.VISIBLE);
        if (totalUsageText != null)
            totalUsageText.setVisibility(View.VISIBLE);
        if (barChartCard != null)
            barChartCard.setVisibility(View.VISIBLE);
        if (tableCard != null)
            tableCard.setVisibility(View.VISIBLE);

        // Calculate total usage from daily_usage list
        float totalUsageSeconds = 0;
        for (RetrofitApiService.DailyUsage dailyUsage : data.daily_usage) {
            totalUsageSeconds += dailyUsage.total_seconds;
        }

        if (totalUsageText != null) {
            totalUsageText.setText(String.format("Total: %s", formatDuration((long) (totalUsageSeconds * 1000))));
        }

        // Populate table and chart
        populateUsageTable(data.daily_usage);
        populateBarChart(data.per_app_usage);
    }

    /**
     * Populate the usage table with per-app daily breakdown
     */
    private void populateUsageTable(List<RetrofitApiService.DailyUsage> dailyUsage) {
        if (weeklyUsageTable == null)
            return;

        weeklyUsageTable.removeAllViews();

        // Add header row
        TableRow headerRow = new TableRow(this);
        headerRow.setBackgroundColor(Color.parseColor("#E3F2FD"));

        TextView dayHeader = new TextView(this);
        dayHeader.setText("Day");
        dayHeader.setPadding(16, 12, 16, 12);
        dayHeader.setTypeface(null, Typeface.BOLD);
        headerRow.addView(dayHeader);

        TextView appHeader = new TextView(this);
        appHeader.setText("App");
        appHeader.setPadding(16, 12, 16, 12);
        appHeader.setTypeface(null, Typeface.BOLD);
        headerRow.addView(appHeader);

        TextView usageHeader = new TextView(this);
        usageHeader.setText("Usage");
        usageHeader.setPadding(16, 12, 16, 12);
        usageHeader.setTypeface(null, Typeface.BOLD);
        headerRow.addView(usageHeader);

        weeklyUsageTable.addView(headerRow);

        // Add data rows - show per-app breakdown for each day
        for (RetrofitApiService.DailyUsage usage : dailyUsage) {
            if (usage.apps != null && !usage.apps.isEmpty()) {
                // Show each app separately
                for (Map.Entry<String, Float> appEntry : usage.apps.entrySet()) {
                    TableRow dataRow = new TableRow(this);

                    TextView dayText = new TextView(this);
                    dayText.setText(usage.date);
                    dayText.setPadding(16, 12, 16, 12);
                    dataRow.addView(dayText);

                    TextView appText = new TextView(this);
                    appText.setText(getAppDisplayName(appEntry.getKey()));
                    appText.setPadding(16, 12, 16, 12);
                    dataRow.addView(appText);

                    TextView usageText = new TextView(this);
                    usageText.setText(formatDuration((long) (appEntry.getValue() * 1000)));
                    usageText.setPadding(16, 12, 16, 12);
                    dataRow.addView(usageText);

                    weeklyUsageTable.addView(dataRow);
                }
            } else {
                // No app breakdown available, show total
                TableRow dataRow = new TableRow(this);

                TextView dayText = new TextView(this);
                dayText.setText(usage.date);
                dayText.setPadding(16, 12, 16, 12);
                dataRow.addView(dayText);

                TextView appText = new TextView(this);
                appText.setText("All Apps");
                appText.setPadding(16, 12, 16, 12);
                dataRow.addView(appText);

                TextView usageText = new TextView(this);
                usageText.setText(formatDuration((long) (usage.total_seconds * 1000)));
                usageText.setPadding(16, 12, 16, 12);
                dataRow.addView(usageText);

                weeklyUsageTable.addView(dataRow);
            }
        }
    }

    /**
     * Get a user-friendly display name for an app package
     */
    private String getAppDisplayName(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            // Fallback: extract last part of package name and capitalize
            String[] parts = packageName.split("\\.");
            String lastPart = parts[parts.length - 1];
            return lastPart.substring(0, 1).toUpperCase() + lastPart.substring(1);
        }
    }

    /**
     * Populate the bar chart with per-app total usage
     */
    private void populateBarChart(java.util.Map<String, RetrofitApiService.AppUsage> perAppUsage) {
        if (barChartContainer == null)
            return;

        barChartContainer.removeAllViews();

        if (perAppUsage == null || perAppUsage.isEmpty()) {
            TextView noDataText = new TextView(this);
            noDataText.setText("No app usage data available");
            noDataText.setPadding(16, 16, 16, 16);
            barChartContainer.addView(noDataText);
            return;
        }

        // Find max value for scaling
        float maxUsage = 0;
        for (RetrofitApiService.AppUsage usage : perAppUsage.values()) {
            if (usage.total_seconds > maxUsage) {
                maxUsage = usage.total_seconds;
            }
        }
        if (maxUsage == 0)
            maxUsage = 1; // Avoid division by zero

        // Define colors for different apps
        int[] appColors = {
            Color.parseColor("#6200EE"),  // Purple
            Color.parseColor("#03DAC5"),  // Teal
            Color.parseColor("#FF5722"),  // Deep Orange
            Color.parseColor("#2196F3"),  // Blue
            Color.parseColor("#4CAF50"),  // Green
            Color.parseColor("#FF9800"),  // Orange
            Color.parseColor("#E91E63"),  // Pink
            Color.parseColor("#9C27B0")   // Purple
        };
        int colorIndex = 0;

        for (Map.Entry<String, RetrofitApiService.AppUsage> entry : perAppUsage.entrySet()) {
            String appPackage = entry.getKey();
            RetrofitApiService.AppUsage usage = entry.getValue();

            LinearLayout barItem = new LinearLayout(this);
            barItem.setOrientation(LinearLayout.HORIZONTAL);
            barItem.setPadding(0, 8, 0, 8);

            // App name label
            TextView appLabel = new TextView(this);
            appLabel.setText(getAppDisplayName(appPackage));
            appLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));
            appLabel.setGravity(Gravity.CENTER_VERTICAL);
            appLabel.setTextSize(12);
            appLabel.setMaxLines(1);
            appLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
            barItem.addView(appLabel);

            // Bar container
            LinearLayout barContainer = new LinearLayout(this);
            barContainer.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
            barContainer.setOrientation(LinearLayout.HORIZONTAL);

            // Bar
            View bar = new View(this);
            int barWidth = (int) (200 * usage.total_seconds / maxUsage);
            bar.setLayoutParams(new LinearLayout.LayoutParams(barWidth, 24));
            bar.setBackgroundColor(appColors[colorIndex % appColors.length]);
            barContainer.addView(bar);

            barItem.addView(barContainer);

            // Usage value
            TextView usageLabel = new TextView(this);
            usageLabel.setText(formatDuration((long) (usage.total_seconds * 1000)));
            usageLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f));
            usageLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            usageLabel.setTextSize(12);
            barItem.addView(usageLabel);

            barChartContainer.addView(barItem);
            colorIndex++;
        }
    }

    /**
     * Start upload scheduler service only once per app session to prevent duplicate
     * uploads
     */
    private void startUploadSchedulerIfNeeded() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean uploadSchedulerStarted = prefs.getBoolean(KEY_UPLOAD_SCHEDULER_STARTED, false);

        // Only start if not already started in this app session
        if (!uploadSchedulerStarted) {
            Log.d(TAG, "Starting upload scheduler service (first time this session)");

            Intent uploadServiceIntent = new Intent(this, SessionUploadService.class);
            // Android 12+ restricts starting foreground services from background
            // SessionUploadService only schedules jobs, so it doesn't need foreground
            // status
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Android 12+: Start as regular service to avoid restrictions
                    startService(uploadServiceIntent);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 8-11: Can use foreground service
                    startForegroundService(uploadServiceIntent);
                } else {
                    // Android < 8: Regular service
                    startService(uploadServiceIntent);
                }

                // Mark as started for this app session
                prefs.edit().putBoolean(KEY_UPLOAD_SCHEDULER_STARTED, true).apply();
                Log.d(TAG, "✅ Upload scheduler service started successfully");

            } catch (Exception e) {
                Log.e(TAG, "Error starting upload scheduler service", e);
            }
        } else {
            Log.d(TAG, "Upload scheduler service already started this session - skipping to prevent duplicates");
        }
    }

    /**
     * Update user ID display based on permission status
     */
    private void updateUserIdDisplay() {
        if (hasUsageAccessPermission()) {
            // Show user ID if permission is granted
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String userId = prefs.getString(KEY_USER_ID, "");

            if (!userId.isEmpty()) {
                userIdText.setText("User ID: " + userId);
                userIdText.setVisibility(View.VISIBLE);
            } else {
                // User ID not yet assigned, show loading
                userIdText.setText("User ID: Loading...");
                userIdText.setVisibility(View.VISIBLE);
            }
        } else {
            // Hide user ID if permission is not granted
            userIdText.setVisibility(View.GONE);
        }
    }

    /**
     * Format duration from milliseconds to human readable format
     */
    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes % 60);
        } else if (minutes > 0) {
            return String.format("%dm", minutes);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
