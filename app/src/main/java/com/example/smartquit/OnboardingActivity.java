package com.example.smartquit;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * Onboarding activity that guides users through the three required permissions:
 * 1. Usage Access Permission - to track app usage
 * 2. Notification Permission - for the persistent foreground service notification
 * 3. Battery Optimization Exemption - to prevent service from being killed
 */
public class OnboardingActivity extends AppCompatActivity {

    private static final String TAG = "OnboardingActivity";
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_ONBOARDING_COMPLETE = "onboarding_complete";

    // Current step (1-3)
    private int currentStep = 1;
    private static final int TOTAL_STEPS = 3;

    // UI Elements
    private TextView step1Indicator, step2Indicator, step3Indicator;
    private View line1, line2;
    private CardView stepCard;
    private TextView stepIcon, stepTitle, stepDescription, stepStatus;
    private Button actionButton, continueButton, finishButton;
    private LinearLayout completionLayout;

    // Permission request launcher for notifications (Android 13+)
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        // Initialize UI
        initializeViews();

        // Set up permission launcher for Android 13+
        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    Log.d(TAG, "Notification permission result: " + isGranted);
                    updateStepUI();
                });

        // Set up button listeners
        actionButton.setOnClickListener(v -> handleActionButton());
        continueButton.setOnClickListener(v -> moveToNextStep());
        finishButton.setOnClickListener(v -> finishOnboarding());

        // Display initial step
        updateStepUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh UI when returning from settings
        updateStepUI();
    }

    private void initializeViews() {
        step1Indicator = findViewById(R.id.step1Indicator);
        step2Indicator = findViewById(R.id.step2Indicator);
        step3Indicator = findViewById(R.id.step3Indicator);
        line1 = findViewById(R.id.line1);
        line2 = findViewById(R.id.line2);
        stepCard = findViewById(R.id.stepCard);
        stepIcon = findViewById(R.id.stepIcon);
        stepTitle = findViewById(R.id.stepTitle);
        stepDescription = findViewById(R.id.stepDescription);
        stepStatus = findViewById(R.id.stepStatus);
        actionButton = findViewById(R.id.actionButton);
        continueButton = findViewById(R.id.continueButton);
        finishButton = findViewById(R.id.finishButton);
        completionLayout = findViewById(R.id.completionLayout);
    }

    private void updateStepUI() {
        // Check if all permissions are granted
        if (hasUsageAccessPermission() && hasNotificationPermission() && hasBatteryOptimizationExemption()) {
            showCompletionScreen();
            return;
        }

        // Find the first incomplete step
        if (!hasUsageAccessPermission()) {
            currentStep = 1;
        } else if (!hasNotificationPermission()) {
            currentStep = 2;
        } else if (!hasBatteryOptimizationExemption()) {
            currentStep = 3;
        }

        // Update step indicators
        updateStepIndicators();

        // Update step content
        updateStepContent();

        // Show/hide continue button based on current step completion
        boolean currentStepComplete = isCurrentStepComplete();
        continueButton.setVisibility(currentStepComplete ? View.VISIBLE : View.GONE);

        // Hide completion layout and finish button
        completionLayout.setVisibility(View.GONE);
        finishButton.setVisibility(View.GONE);
        stepCard.setVisibility(View.VISIBLE);
    }

    private void updateStepIndicators() {
        // Step 1
        if (hasUsageAccessPermission()) {
            step1Indicator.setText("✓");
            step1Indicator.setBackgroundResource(R.drawable.step_circle_complete);
            line1.setBackgroundColor(getColor(R.color.step_complete));
        } else if (currentStep == 1) {
            step1Indicator.setText("1");
            step1Indicator.setBackgroundResource(R.drawable.step_circle_active);
        } else {
            step1Indicator.setText("1");
            step1Indicator.setBackgroundResource(R.drawable.step_circle_inactive);
        }

        // Step 2
        if (hasNotificationPermission()) {
            step2Indicator.setText("✓");
            step2Indicator.setBackgroundResource(R.drawable.step_circle_complete);
            line2.setBackgroundColor(getColor(R.color.step_complete));
        } else if (currentStep == 2) {
            step2Indicator.setText("2");
            step2Indicator.setBackgroundResource(R.drawable.step_circle_active);
            line1.setBackgroundColor(getColor(R.color.step_complete));
        } else {
            step2Indicator.setText("2");
            step2Indicator.setBackgroundResource(R.drawable.step_circle_inactive);
        }

        // Step 3
        if (hasBatteryOptimizationExemption()) {
            step3Indicator.setText("✓");
            step3Indicator.setBackgroundResource(R.drawable.step_circle_complete);
        } else if (currentStep == 3) {
            step3Indicator.setText("3");
            step3Indicator.setBackgroundResource(R.drawable.step_circle_active);
            line2.setBackgroundColor(getColor(R.color.step_complete));
        } else {
            step3Indicator.setText("3");
            step3Indicator.setBackgroundResource(R.drawable.step_circle_inactive);
        }
    }

    private void updateStepContent() {
        switch (currentStep) {
            case 1:
                stepIcon.setText("📊");
                stepTitle.setText("Usage Access Permission");
                stepDescription.setText("This allows SmartPause to see which apps you're using so it can help you manage your screen time effectively.");
                if (hasUsageAccessPermission()) {
                    stepStatus.setText("✅ Granted");
                    stepStatus.setTextColor(getColor(android.R.color.holo_green_dark));
                    actionButton.setText("Continue");
                    actionButton.setBackgroundTintList(getColorStateList(R.color.step_complete));
                } else {
                    stepStatus.setText("⚠️ Not granted");
                    stepStatus.setTextColor(getColor(android.R.color.holo_orange_dark));
                    actionButton.setText("Grant Permission");
                    actionButton.setBackgroundTintList(getColorStateList(android.R.color.holo_green_dark));
                }
                break;

            case 2:
                stepIcon.setText("🔔");
                stepTitle.setText("Notification Permission");
                stepDescription.setText("SmartPause needs to show a notification so Android keeps it running in the background to track your usage.");
                if (hasNotificationPermission()) {
                    stepStatus.setText("✅ Granted");
                    stepStatus.setTextColor(getColor(android.R.color.holo_green_dark));
                    actionButton.setText("Continue");
                    actionButton.setBackgroundTintList(getColorStateList(R.color.step_complete));
                } else {
                    stepStatus.setText("⚠️ Not granted");
                    stepStatus.setTextColor(getColor(android.R.color.holo_orange_dark));
                    actionButton.setText("Grant Permission");
                    actionButton.setBackgroundTintList(getColorStateList(android.R.color.holo_green_dark));
                }
                break;

            case 3:
                stepIcon.setText("🔋");
                stepTitle.setText("Battery Optimization");
                stepDescription.setText("Disable battery optimization for SmartPause so it can run continuously without being stopped by your phone.");
                if (hasBatteryOptimizationExemption()) {
                    stepStatus.setText("✅ Exempted");
                    stepStatus.setTextColor(getColor(android.R.color.holo_green_dark));
                    actionButton.setText("Continue");
                    actionButton.setBackgroundTintList(getColorStateList(R.color.step_complete));
                } else {
                    stepStatus.setText("⚠️ Not exempted");
                    stepStatus.setTextColor(getColor(android.R.color.holo_orange_dark));
                    actionButton.setText("Disable Optimization");
                    actionButton.setBackgroundTintList(getColorStateList(android.R.color.holo_green_dark));
                }
                break;
        }
    }

    private void handleActionButton() {
        // For step 3 (battery optimization), always show the intent regardless of current status
        // This ensures users explicitly confirm battery settings during onboarding
        if (currentStep == 3) {
            requestBatteryOptimizationExemption();
            return;
        }
        
        if (isCurrentStepComplete()) {
            moveToNextStep();
            return;
        }

        switch (currentStep) {
            case 1:
                openUsageAccessSettings();
                break;
            case 2:
                requestNotificationPermission();
                break;
            case 3:
                requestBatteryOptimizationExemption();
                break;
        }
    }

    private void moveToNextStep() {
        if (currentStep < TOTAL_STEPS) {
            currentStep++;
            updateStepUI();
        } else {
            // All steps complete
            updateStepUI();
        }
    }

    private boolean isCurrentStepComplete() {
        switch (currentStep) {
            case 1:
                return hasUsageAccessPermission();
            case 2:
                return hasNotificationPermission();
            case 3:
                return hasBatteryOptimizationExemption();
            default:
                return false;
        }
    }

    private void showCompletionScreen() {
        stepCard.setVisibility(View.GONE);
        continueButton.setVisibility(View.GONE);
        completionLayout.setVisibility(View.VISIBLE);
        finishButton.setVisibility(View.VISIBLE);

        // Update all indicators to complete
        step1Indicator.setText("✓");
        step1Indicator.setBackgroundResource(R.drawable.step_circle_complete);
        step2Indicator.setText("✓");
        step2Indicator.setBackgroundResource(R.drawable.step_circle_complete);
        step3Indicator.setText("✓");
        step3Indicator.setBackgroundResource(R.drawable.step_circle_complete);
        line1.setBackgroundColor(getColor(R.color.step_complete));
        line2.setBackgroundColor(getColor(R.color.step_complete));
    }

    private void finishOnboarding() {
        // Mark onboarding as complete
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply();

        // Go to main activity
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // ========== Permission Check Methods ==========

    private boolean hasUsageAccessPermission() {
        try {
            AppOpsManager appOpsManager = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (appOpsManager == null) return false;

            int mode;
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
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            Log.e(TAG, "Error checking usage access permission", e);
            return false;
        }
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires explicit notification permission
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        } else {
            // Pre-Android 13: check if notifications are enabled for the app
            return NotificationManagerCompat.from(this).areNotificationsEnabled();
        }
    }

    private boolean hasBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return true; // Pre-M doesn't have battery optimization
    }

    // ========== Permission Request Methods ==========

    private void openUsageAccessSettings() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        startActivity(intent);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - request permission
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            // Pre-Android 13 - open notification settings
            Intent intent = new Intent();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
                intent.putExtra("app_package", getPackageName());
                intent.putExtra("app_uid", getApplicationInfo().uid);
            }
            startActivity(intent);
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to request battery optimization exemption: " + e.getMessage());
                // Fallback: open battery optimization settings
                try {
                    Intent fallbackIntent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    startActivity(fallbackIntent);
                } catch (Exception e2) {
                    Log.e(TAG, "Failed to open battery settings: " + e2.getMessage());
                }
            }
        }
    }

    /**
     * Check if onboarding has been completed
     */
    public static boolean isOnboardingComplete(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false);
    }

    /**
     * Reset onboarding status (for testing)
     */
    public static void resetOnboarding(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, false).apply();
    }
}
