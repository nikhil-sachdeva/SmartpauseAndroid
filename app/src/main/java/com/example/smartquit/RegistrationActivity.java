package com.example.smartquit;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class RegistrationActivity extends AppCompatActivity {

    private LinearLayout appCheckboxContainer;
    private Button registerButton;
    private ProgressBar progressBar;
    private TextView instructionText;
    private List<String> selectedApps = new ArrayList<>(); // Stores package IDs
    private java.util.Map<String, String> appNameToPackageId = new java.util.HashMap<>(); // Maps display names to package IDs
    private String userId;
    private static final String API_BASE_URL = "https://smartquit-cyber.onrender.com"; // Change to your backend URL
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_USER_ID = "user_id";
    private static final String KEY_REGISTERED = "is_registered";
    private static final String KEY_APPS_TO_MONITOR = "apps_to_monitor";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_registration);

        appCheckboxContainer = findViewById(R.id.appCheckboxContainer);
        registerButton = findViewById(R.id.registerButton);
        progressBar = findViewById(R.id.progressBar);
        instructionText = findViewById(R.id.instructionText);

        // Generate unique user ID
        userId = UUID.randomUUID().toString();

        // Populate installed apps
        loadInstalledApps();

        // Set up register button
        registerButton.setOnClickListener(v -> registerUser());
    }

    private void loadInstalledApps() {
        new Thread(() -> {
            PackageManager packageManager = getPackageManager();
            
            // Get only launchable apps (apps with icons that can be launched)
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            java.util.List<ResolveInfo> resolveInfoList = packageManager.queryIntentActivities(mainIntent, 0);

            // Create app info list with icons
            java.util.List<AppInfo> appInfoList = new ArrayList<>();
            appNameToPackageId.clear();
            
            // Icon size in pixels (20x20)
            int iconSizePx = (int) (20 * getResources().getDisplayMetrics().density);
            
            for (ResolveInfo resolveInfo : resolveInfoList) {
                try {
                    String appName = resolveInfo.loadLabel(packageManager).toString();
                    String packageId = resolveInfo.activityInfo.packageName;
                    Drawable icon = resolveInfo.loadIcon(packageManager);

                    // Scale icon to fixed 20x20 size
                    if (icon != null) {
                        icon = scaleDrawable(icon, iconSizePx, iconSizePx);
                    }
                    
                    appInfoList.add(new AppInfo(appName, packageId, icon));
                    appNameToPackageId.put(appName, packageId);
                } catch (Exception e) {
                    Log.e("REGISTRATION", "Error loading app info", e);
                }
            }
            
            // Sort by app name
            java.util.Collections.sort(appInfoList, (a, b) -> a.displayName.compareToIgnoreCase(b.displayName));

            runOnUiThread(() -> {
                appCheckboxContainer.removeAllViews();
                
//                int iconSizePx = (int) (20 * getResources().getDisplayMetrics().density);
                
                for (AppInfo appInfo : appInfoList) {
                    CheckBox checkBox = new CheckBox(RegistrationActivity.this);
                    checkBox.setText(appInfo.displayName);
                    
                    // Set the icon for the checkbox
                    if (appInfo.icon != null) {
                        checkBox.setCompoundDrawablesWithIntrinsicBounds(appInfo.icon, null, null, null);
                        checkBox.setCompoundDrawablePadding(8);
                    }
                    
                    // Set padding for better spacing
                    checkBox.setPadding(12, 4, 12, 4);
                    checkBox.setTextSize(17);
                    
                    // Store package ID when checkbox state changes
                    checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                        if (isChecked) {
                            if (!selectedApps.contains(appInfo.packageId)) {
                                selectedApps.add(appInfo.packageId);
                            }
                        } else {
                            selectedApps.remove(appInfo.packageId);
                        }
                    });
                    appCheckboxContainer.addView(checkBox);
                }
                
                instructionText.setText("Loaded " + appInfoList.size() + " apps. Select the apps you want to monitor:");
            });
        }).start();
    }

    /**
     * Helper class to store app display name, package ID, and icon
     */
    private static class AppInfo {
        String displayName;
        String packageId;
        Drawable icon;
        
        AppInfo(String displayName, String packageId, Drawable icon) {
            this.displayName = displayName;
            this.packageId = packageId;
            this.icon = icon;
        }
    }

    /**
     * Scale a Drawable to a specific width and height
     */
    private Drawable scaleDrawable(Drawable drawable, int width, int height) {
        try {
            // Convert drawable to bitmap
            Bitmap bitmap;
            if (drawable instanceof BitmapDrawable) {
                bitmap = ((BitmapDrawable) drawable).getBitmap();
            } else {
                bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                drawable.draw(canvas);
            }
            
            // Scale bitmap to desired size
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
            
            // Return as drawable
            return new BitmapDrawable(getResources(), scaledBitmap);
        } catch (Exception e) {
            Log.e("REGISTRATION", "Error scaling drawable", e);
            return drawable;
        }
    }

    private void registerUser() {
        if (userId == null || userId.isEmpty()) {
            Toast.makeText(this, "Error generating user ID", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d("REGISTRATION", "========== REGISTRATION DEBUG ==========");
        Log.d("REGISTRATION", "Selected Apps Count: " + selectedApps.size());
        Log.d("REGISTRATION", "Selected Apps: " + selectedApps.toString());
        Log.d("REGISTRATION", "User ID: " + userId);
        
        // Show progress bar
        progressBar.setVisibility(ProgressBar.VISIBLE);
        registerButton.setEnabled(false);

        // Collect device info
        RetrofitApiService.DeviceInfo deviceInfo = new RetrofitApiService.DeviceInfo(
                Build.DEVICE,
                Build.MODEL,
                Build.VERSION.RELEASE,
                "1.0" // App version
        );

        // Create registration request - ALWAYS send apps list, even if empty
        java.util.List<String> appsToSend = selectedApps.isEmpty() ? null : new ArrayList<>(selectedApps);
        Log.d("REGISTRATION", "Apps to send (null if empty): " + appsToSend);
        
        RetrofitApiService.RegistrationRequest request = new RetrofitApiService.RegistrationRequest(
                userId,
                deviceInfo,
                appsToSend
        );

        Log.d("REGISTRATION", "Request created. Apps field: " + request.apps_to_monitor);

        // Create Retrofit instance
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(API_BASE_URL)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();

        RetrofitApiService apiService = retrofit.create(RetrofitApiService.class);
        
        // Make registration call
        Call<RetrofitApiService.RegistrationResponse> call = apiService.registerUser(request);
        call.enqueue(new Callback<RetrofitApiService.RegistrationResponse>() {
            @Override
            public void onResponse(Call<RetrofitApiService.RegistrationResponse> call, Response<RetrofitApiService.RegistrationResponse> response) {
                progressBar.setVisibility(ProgressBar.GONE);
                
                if (response.isSuccessful() && response.body() != null) {
                    Log.d("REGISTRATION", "Registration successful!");
                    Log.d("REGISTRATION", "Response apps_to_monitor: " + response.body().apps_to_monitor);
                    
                    // Save registration status to SharedPreferences
                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putString(KEY_USER_ID, userId);
                    editor.putBoolean(KEY_REGISTERED, true);
                    
                    // Save apps to monitor as JSON array
                    org.json.JSONArray appsJsonArray = new org.json.JSONArray(selectedApps);
                    editor.putString(KEY_APPS_TO_MONITOR, appsJsonArray.toString());
                    editor.apply();

                    Toast.makeText(RegistrationActivity.this, 
                            "Registration successful!", Toast.LENGTH_SHORT).show();
                    
                    Log.d("REGISTRATION", "User registered: " + userId);
                    Log.d("REGISTRATION", "Apps to monitor: " + response.body().apps_to_monitor);
                    Log.d("REGISTRATION", "========== END REGISTRATION DEBUG ==========\n");

                    // Launch MainActivity
                    Intent intent = new Intent(RegistrationActivity.this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                } else {
                    registerButton.setEnabled(true);
                    Toast.makeText(RegistrationActivity.this, 
                            "Registration failed: " + response.message(), Toast.LENGTH_SHORT).show();
                    Log.e("REGISTRATION", "Response error: " + response.code() + " " + response.message());
                    Log.e("REGISTRATION", "========== END REGISTRATION DEBUG ==========\n");
                }
            }

            @Override
            public void onFailure(Call<RetrofitApiService.RegistrationResponse> call, Throwable t) {
                progressBar.setVisibility(ProgressBar.GONE);
                registerButton.setEnabled(true);
                Toast.makeText(RegistrationActivity.this, 
                        "Network error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
                Log.e("REGISTRATION", "Network error", t);
                Log.e("REGISTRATION", "========== END REGISTRATION DEBUG ==========\n");
            }
        });
    }
}
