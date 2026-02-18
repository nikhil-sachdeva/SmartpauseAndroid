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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private AppDatabase db;
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_REGISTERED = "is_registered";
    private Button grantAccessButton;
    private Button adminConsoleButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        
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
        
        // Request battery optimization exemption for reliable background service
        requestBatteryOptimizationExemption();

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
            adminConsoleButton.setVisibility(View.VISIBLE);
            adminConsoleButton.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, AdminConsoleActivity.class);
                startActivity(intent);
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        
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
                    adminConsoleButton.setVisibility(View.VISIBLE);
                    adminConsoleButton.setOnClickListener(v -> {
                        Intent intent = new Intent(MainActivity.this, AdminConsoleActivity.class);
                        startActivity(intent);
                    });
                }
                
                Toast.makeText(this, "Permission granted! App is now tracking sessions.", Toast.LENGTH_SHORT).show();
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
                return false;
            }
            
            int mode;
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
            
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            Log.e("MainActivity", "Error checking usage access permission", e);
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
     * Request battery optimization exemption to keep the service running
     * This prevents Android from stopping the service to save battery
     */
    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            String packageName = getPackageName();
            
            if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Log.d(TAG, "Battery optimization is enabled - requesting exemption");
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + packageName));
                    startActivity(intent);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to request battery optimization exemption: " + e.getMessage());
                }
            } else {
                Log.d(TAG, "Battery optimization already disabled for this app");
            }
        }
    }
}
