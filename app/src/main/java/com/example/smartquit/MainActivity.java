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
    private static final String API_BASE_URL = "https://smartquit-cyber.onrender.com";

    private AppDatabase db;
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_REGISTERED = "is_registered";
    private static final String KEY_USER_ID = "user_id";
    private Button grantAccessButton;
    private Button adminConsoleButton;
    private TextView trackingStatusText;
    private TableLayout weeklyUsageTable;
    private LinearLayout barChartContainer;
    private TextView totalUsageText;
    private TextView weeklyUsageTitle;
    private View barChartCard;
    private View tableCard;
    private ProgressBar loadingProgressBar;
    private TextView errorText;
    private RetrofitApiService apiService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        adminConsoleButton = findViewById(R.id.adminConsoleButton);
        if(adminConsoleButton != null) adminConsoleButton.setVisibility(View.GONE);
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
        
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        db = AppDatabase.getDatabase(this);
        adminConsoleButton = findViewById(R.id.adminConsoleButton);
        if (adminConsoleButton != null) adminConsoleButton.setVisibility(View.GONE);
           
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
            if (adminConsoleButton != null) adminConsoleButton.setVisibility(View.GONE);
            
            Toast.makeText(this, "Please grant usage access permission to use this app", Toast.LENGTH_LONG).show();
            return;
        }

        // User has permission, show normal buttons
        grantAccessButton = findViewById(R.id.grantAccessButton);
        if (grantAccessButton != null) {
            grantAccessButton.setVisibility(View.GONE);
        }

        // Start the background session tracker service
        Intent serviceIntent = new Intent(this, SessionTrackerService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error starting SessionTrackerService: " + e.getMessage());
            // Fallback to regular service if foreground service fails
            try {
                startService(serviceIntent);
            } catch (Exception fallbackError) {
                Log.e(TAG, "Error starting SessionTrackerService as regular service: " + fallbackError.getMessage());
            }
        }

        // Start the session upload scheduler service
        Intent uploadServiceIntent = new Intent(this, SessionUploadService.class);
        // Android 12+ restricts starting foreground services from background
        // SessionUploadService only schedules jobs, so it doesn't need foreground status
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

        // Set up the Admin Console button
        adminConsoleButton = findViewById(R.id.adminConsoleButton);
        if (adminConsoleButton != null) {
            adminConsoleButton.setVisibility(View.GONE);
        }
        
        // Initialize UI elements for weekly usage
        trackingStatusText = findViewById(R.id.trackingStatusText);
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
                Intent serviceIntent = new Intent(this, SessionTrackerService.class);
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent);
                    } else {
                        startService(serviceIntent);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error starting SessionTrackerService in onResume: " + e.getMessage());
                    // Fallback to regular service if foreground service fails
                    try {
                        startService(serviceIntent);
                    } catch (Exception fallbackError) {
                        Log.e(TAG, "Error starting SessionTrackerService as regular service in onResume: " + fallbackError.getMessage());
                    }
                }

                // Start the session upload scheduler service
                Intent uploadServiceIntent = new Intent(this, SessionUploadService.class);
                // Android 12+ restricts starting foreground services from background
                // SessionUploadService only schedules jobs, so it doesn't need foreground status
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
                 
                // Show admin console button
                adminConsoleButton = findViewById(R.id.adminConsoleButton);
                if (adminConsoleButton != null) {
                    adminConsoleButton.setVisibility(View.GONE);
                }
                
                Toast.makeText(this, "Permission granted! App is now tracking sessions.", Toast.LENGTH_SHORT).show();
                
                // Initialize and load weekly usage
                initializeWeeklyUsageUI();
                loadWeeklyUsageData();
                updateTrackingStatus();
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
                            getPackageName()
                    );
                } else {
                    mode = appOpsManager.checkOpNoThrow(
                            AppOpsManager.OPSTR_GET_USAGE_STATS,
                            android.os.Process.myUid(),
                            getPackageName()
                    );
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
     * Check if SessionTrackerService is running and update status
     */
    private void updateTrackingStatus() {
        if (trackingStatusText == null) return;
        
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
            android.app.ActivityManager activityManager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager == null) return false;
            
            for (android.app.ActivityManager.RunningServiceInfo service : activityManager.getRunningServices(Integer.MAX_VALUE)) {
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
     * Show loading state
     */
    private void showLoadingUI() {
        if (loadingProgressBar != null) loadingProgressBar.setVisibility(View.VISIBLE);
        if (errorText != null) errorText.setVisibility(View.GONE);
        
        // Hide all data-related elements during loading
        if (weeklyUsageTitle != null) weeklyUsageTitle.setVisibility(View.GONE);
        if (totalUsageText != null) totalUsageText.setVisibility(View.GONE);
        if (barChartCard != null) barChartCard.setVisibility(View.GONE);
        if (tableCard != null) tableCard.setVisibility(View.GONE);
    }

    /**
     * Show "no data available" state
     */
    private void showNoDataUI(String message) {
        if (loadingProgressBar != null) loadingProgressBar.setVisibility(View.GONE);
        if (errorText != null) {
            errorText.setText("📄 No data available");
            errorText.setVisibility(View.VISIBLE);
        }
        
        // Hide all data-related elements when no data
        if (weeklyUsageTitle != null) weeklyUsageTitle.setVisibility(View.GONE);
        if (totalUsageText != null) totalUsageText.setVisibility(View.GONE);
        if (barChartCard != null) barChartCard.setVisibility(View.GONE);
        if (tableCard != null) tableCard.setVisibility(View.GONE);
        
        Log.d(TAG, "No data: " + message);
    }

    /**
     * Show data with table and chart
     */
    private void showDataUI(RetrofitApiService.WeeklyUsageResponse data) {
        if (loadingProgressBar != null) loadingProgressBar.setVisibility(View.GONE);
        if (errorText != null) errorText.setVisibility(View.GONE);
        
        // Show all data-related elements when data is available
        if (weeklyUsageTitle != null) weeklyUsageTitle.setVisibility(View.VISIBLE);
        if (totalUsageText != null) totalUsageText.setVisibility(View.VISIBLE);
        if (barChartCard != null) barChartCard.setVisibility(View.VISIBLE);
        if (tableCard != null) tableCard.setVisibility(View.VISIBLE);
        
        // Calculate total usage from daily_usage list
        float totalUsageSeconds = 0;
        for (RetrofitApiService.DailyUsage dailyUsage : data.daily_usage) {
            totalUsageSeconds += dailyUsage.total_seconds;
        }
        
        if (totalUsageText != null) {
            totalUsageText.setText(String.format("Total: %s", formatDuration((long)(totalUsageSeconds * 1000))));
        }
        
        // Populate table and chart
        populateUsageTable(data.daily_usage);
        populateBarChart(data.daily_usage);
    }

    /**
     * Populate the usage table
     */
    private void populateUsageTable(List<RetrofitApiService.DailyUsage> dailyUsage) {
        if (weeklyUsageTable == null) return;
        
        weeklyUsageTable.removeAllViews();
        
        // Add header row
        TableRow headerRow = new TableRow(this);
        headerRow.setBackgroundColor(Color.parseColor("#E3F2FD"));
        
        TextView dayHeader = new TextView(this);
        dayHeader.setText("Day");
        dayHeader.setPadding(16, 12, 16, 12);
        dayHeader.setTypeface(null, Typeface.BOLD);
        headerRow.addView(dayHeader);
        
        TextView usageHeader = new TextView(this);
        usageHeader.setText("Usage");
        usageHeader.setPadding(16, 12, 16, 12);
        usageHeader.setTypeface(null, Typeface.BOLD);
        headerRow.addView(usageHeader);
        
        weeklyUsageTable.addView(headerRow);
        
        // Add data rows
        for (RetrofitApiService.DailyUsage usage : dailyUsage) {
            TableRow dataRow = new TableRow(this);
            
            TextView dayText = new TextView(this);
            dayText.setText(usage.date);
            dayText.setPadding(16, 12, 16, 12);
            dataRow.addView(dayText);
            
            TextView usageText = new TextView(this);
            usageText.setText(formatDuration((long)(usage.total_seconds * 1000)));
            usageText.setPadding(16, 12, 16, 12);
            dataRow.addView(usageText);
            
            weeklyUsageTable.addView(dataRow);
        }
    }

    /**
     * Populate the bar chart
     */
    private void populateBarChart(List<RetrofitApiService.DailyUsage> dailyUsage) {
        if (barChartContainer == null) return;
        
        barChartContainer.removeAllViews();
        
        // Find max value for scaling
        float maxUsage = 0;
        for (RetrofitApiService.DailyUsage usage : dailyUsage) {
            if (usage.total_seconds > maxUsage) {
                maxUsage = usage.total_seconds;
            }
        }
        if (maxUsage == 0) maxUsage = 1; // Avoid division by zero
        
        for (RetrofitApiService.DailyUsage usage : dailyUsage) {
            LinearLayout barItem = new LinearLayout(this);
            barItem.setOrientation(LinearLayout.HORIZONTAL);
            barItem.setPadding(0, 8, 0, 8);
            
            // Day label
            TextView dayLabel = new TextView(this);
            dayLabel.setText(usage.date);
            dayLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            dayLabel.setGravity(Gravity.CENTER_VERTICAL);
            barItem.addView(dayLabel);
            
            // Bar container
            LinearLayout barContainer = new LinearLayout(this);
            barContainer.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
            barContainer.setOrientation(LinearLayout.HORIZONTAL);
            
            // Bar
            View bar = new View(this);
            int barWidth = (int) (200 * usage.total_seconds / maxUsage);
            bar.setLayoutParams(new LinearLayout.LayoutParams(barWidth, 24));
            bar.setBackgroundColor(Color.parseColor("#6200EE"));
            barContainer.addView(bar);
            
            barItem.addView(barContainer);
            
            // Usage value
            TextView usageLabel = new TextView(this);
            usageLabel.setText(formatDuration((long)(usage.total_seconds * 1000)));
            usageLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            usageLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            barItem.addView(usageLabel);
            
            barChartContainer.addView(barItem);
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
