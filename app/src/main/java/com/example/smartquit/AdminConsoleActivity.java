package com.example.smartquit;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.tabs.TabLayout;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class AdminConsoleActivity extends AppCompatActivity {

    private static final String TAG = "AdminConsoleActivity";
    private static final String API_BASE_URL = "https://smartpauseappv2.vercel.app";
    private static final String KEY_USER_ID = "user_id";

    private ListView sessionsListView;
    private ListView queriesListView;
    private TabLayout tabLayout;
    private TextView nextUploadTimerView;
    private TextView monitoredAppsTextView;
    private AppDatabase db;
    private SessionListAdapter adapter;
    private QueryListAdapter queryAdapter;
    private Handler handler;
    private Runnable timerRunnable;
    private TextView modeDisplayText;
    private Button uploadAllNowButton;
    private TextView uploadStatusText;
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_TEST_MODE = "is_test_mode";  // Use same key as RegistrationActivity
    private static final String ADMIN_PASSWORD = "CYBER";
    private View mainContentLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_console);

        // Hide main content initially
        mainContentLayout = findViewById(R.id.mainContentLayout);
        if (mainContentLayout != null) {
            mainContentLayout.setVisibility(View.GONE);
        }

        // Show password dialog
        showPasswordDialog();
    }

    private void showPasswordDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Admin Console");
        builder.setMessage("Enter password to access Admin Console");

        // Set up password input
        final EditText passwordInput = new EditText(this);
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passwordInput.setHint("Password");
        
        // Add padding to the EditText
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(padding, padding, padding, 0);
        container.addView(passwordInput);
        builder.setView(container);

        builder.setPositiveButton("Enter", null); // Set to null, handle in OnShowListener
        builder.setNegativeButton("Cancel", (dialog, which) -> {
            dialog.cancel();
            finish(); // Close activity if cancelled
        });
        builder.setCancelable(false);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(dialogInterface -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String enteredPassword = passwordInput.getText().toString();
                if (ADMIN_PASSWORD.equals(enteredPassword)) {
                    dialog.dismiss();
                    initializeAdminConsole();
                } else {
                    Toast.makeText(this, "Incorrect password", Toast.LENGTH_SHORT).show();
                    passwordInput.setText("");
                }
            });
        });

        dialog.show();
    }

    private void initializeAdminConsole() {
        // Show main content
        if (mainContentLayout != null) {
            mainContentLayout.setVisibility(View.VISIBLE);
        }

        sessionsListView = findViewById(R.id.sessionsListView);
        queriesListView = findViewById(R.id.queriesListView);
        tabLayout = findViewById(R.id.tabLayout);
        nextUploadTimerView = findViewById(R.id.nextUploadTimerView);
        monitoredAppsTextView = findViewById(R.id.monitoredAppsTextView);
        db = AppDatabase.getDatabase(this);
        handler = new Handler(Looper.getMainLooper());

        // Set up tabs
        tabLayout.addTab(tabLayout.newTab().setText("Sessions"));
        tabLayout.addTab(tabLayout.newTab().setText("Queries"));

        // Set up tab selection listener
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() == 0) {
                    // Show sessions
                    sessionsListView.setVisibility(View.VISIBLE);
                    queriesListView.setVisibility(View.GONE);
                } else {
                    // Show queries
                    sessionsListView.setVisibility(View.GONE);
                    queriesListView.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        // Set up View Logs button
        Button viewLogsButton = findViewById(R.id.viewLogsButton);
        if (viewLogsButton != null) {
            viewLogsButton.setOnClickListener(v -> {
                Intent intent = new Intent(AdminConsoleActivity.this, LogsActivity.class);
                startActivity(intent);
            });
        }

        // Set up Model Data button
        Button modelDataButton = findViewById(R.id.modelDataButton);
        if (modelDataButton != null) {
            modelDataButton.setOnClickListener(v -> {
                Intent intent = new Intent(AdminConsoleActivity.this, ModelDataActivity.class);
                startActivity(intent);
            });
        }

        // Set up Upload All Now button
        uploadAllNowButton = findViewById(R.id.uploadAllNowButton);
        uploadStatusText = findViewById(R.id.uploadStatusText);
        if (uploadAllNowButton != null) {
            uploadAllNowButton.setOnClickListener(v -> uploadAllSessionsAndQueries());
        }

        // Set up Mode display (read-only)
        modeDisplayText = findViewById(R.id.modeDisplayText);
        if (modeDisplayText != null) {
            boolean isTestMode = getTestModePreference();
            String modeText = isTestMode ? "Mode: TEST" : "Mode: PRODUCTION";
            modeDisplayText.setText(modeText);
        }

        loadMonitoredApps();
        loadSessions();
        loadQueries();
        startUploadCountdown();
    }

    private void loadSessions() {
        new Thread(() -> {
            List<AppSession> sessions = db.appSessionDao().getAllSessions();
            
            runOnUiThread(() -> {
                if (sessions.isEmpty()) {
                    Toast.makeText(AdminConsoleActivity.this, "No sessions recorded yet.", Toast.LENGTH_SHORT).show();
                } else {
                    adapter = new SessionListAdapter(AdminConsoleActivity.this, new ArrayList<>(sessions));
                    sessionsListView.setAdapter(adapter);
                }
            });
        }).start();
    }

    private void loadQueries() {
        new Thread(() -> {
            List<Query> queries = db.queryDao().getAllQueries();
            
            runOnUiThread(() -> {
                if (queries.isEmpty()) {
                    Toast.makeText(AdminConsoleActivity.this, "No queries recorded yet.", Toast.LENGTH_SHORT).show();
                } else {
                    queryAdapter = new QueryListAdapter(AdminConsoleActivity.this, new ArrayList<>(queries));
                    queriesListView.setAdapter(queryAdapter);
                }
            });
        }).start();
    }

    /**
     * Load and display monitored apps list
     */
    private void loadMonitoredApps() {
        android.content.SharedPreferences prefs = getSharedPreferences("SmartQuitPrefs", MODE_PRIVATE);
        String appsJson = prefs.getString("apps_to_monitor", "[]");
        
        try {
            org.json.JSONArray jsonArray = new org.json.JSONArray(appsJson);
            StringBuilder appsText = new StringBuilder("Monitored Apps:\n");
            
            if (jsonArray.length() == 0) {
                appsText.append("None");
            } else {
                for (int i = 0; i < jsonArray.length(); i++) {
                    appsText.append("• ").append(jsonArray.getString(i)).append("\n");
                }
            }
            
            if (monitoredAppsTextView != null) {
                monitoredAppsTextView.setText(appsText.toString());
            }
        } catch (org.json.JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * Start countdown timer showing time until next upload at 3 AM
     */
    private void startUploadCountdown() {
        timerRunnable = new Runnable() {
            @Override
            public void run() {
                long timeUntilNextUpload = getTimeUntilNextUpload();
                String timerText = formatTimeUntilUpload(timeUntilNextUpload);
                
                if (nextUploadTimerView != null) {
                    nextUploadTimerView.setText("Next upload in: " + timerText);
                }
                
                // Update every second
                handler.postDelayed(this, 1000);
            }
        };
        
        handler.post(timerRunnable);
    }

    /**
     * Calculate milliseconds until next 3 AM
     */
    private long getTimeUntilNextUpload() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 3);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        // If 3 AM has already passed today, schedule for tomorrow 3 AM
        if (calendar.getTimeInMillis() <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1);
        }

        return calendar.getTimeInMillis() - System.currentTimeMillis();
    }

    /**
     * Format milliseconds into human-readable time string
     */
    private String formatTimeUntilUpload(long milliseconds) {
        long seconds = (milliseconds / 1000) % 60;
        long minutes = (milliseconds / (1000 * 60)) % 60;
        long hours = (milliseconds / (1000 * 60 * 60)) % 24;
        long days = milliseconds / (1000 * 60 * 60 * 24);
        
        if (days > 0) {
            return String.format("%d d %d h %d m %d s", days, hours, minutes, seconds);
        } else if (hours > 0) {
            return String.format("%d h %d m %d s", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%d m %d s", minutes, seconds);
        } else {
            return String.format("%d s", seconds);
        }
    }

    /**
     * Get test mode preference - now read-only, set during registration (default: false = production)
     */
    private boolean getTestModePreference() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_TEST_MODE, false);  // Default to production mode
    }

    /**
     * Upload all sessions and queries immediately
     */
    private void uploadAllSessionsAndQueries() {
        // Disable button and show status
        runOnUiThread(() -> {
            uploadAllNowButton.setEnabled(false);
            uploadAllNowButton.setText("Uploading...");
            if (uploadStatusText != null) {
                uploadStatusText.setVisibility(View.VISIBLE);
                uploadStatusText.setText("Preparing upload...");
            }
        });

        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String userId = prefs.getString(KEY_USER_ID, null);

                if (userId == null) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Error: User ID not found", Toast.LENGTH_SHORT).show();
                        resetUploadButton();
                    });
                    return;
                }

                // Get all sessions from database
                List<AppSession> sessions = db.appSessionDao().getAllSessions();
                List<Query> queries = db.queryDao().getAllQueries();

                if ((sessions == null || sessions.isEmpty()) && (queries == null || queries.isEmpty())) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "No sessions or queries to upload", Toast.LENGTH_SHORT).show();
                        resetUploadButton();
                    });
                    return;
                }

                int sessionCount = sessions != null ? sessions.size() : 0;
                int queryCount = queries != null ? queries.size() : 0;

                runOnUiThread(() -> {
                    if (uploadStatusText != null) {
                        uploadStatusText.setText("Uploading " + sessionCount + " sessions and " + queryCount + " queries...");
                    }
                });

                Log.d(TAG, "Starting manual upload: " + sessionCount + " sessions, " + queryCount + " queries");

                // Get today's date
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                String todayDate = sdf.format(new Date());

                // Convert to API models
                List<RetrofitApiService.Session> apiSessions = new ArrayList<>();
                if (sessions != null) {
                    for (AppSession appSession : sessions) {
                        RetrofitApiService.Session session = new RetrofitApiService.Session();
                        session.app_name = appSession.appName;
                        session.start_time = appSession.startTime;
                        session.end_time = appSession.endTime;
                        session.duration_seconds = (float) appSession.durationSeconds;
                        session.num_vibrations = appSession.numVibrations;
                        session.user_complied = appSession.userComplied;
                        session.group_id = appSession.groupId;
                        apiSessions.add(session);
                    }
                }

                List<RetrofitApiService.QueryData> apiQueries = new ArrayList<>();
                if (queries != null) {
                    for (Query query : queries) {
                        RetrofitApiService.QueryData queryData = new RetrofitApiService.QueryData();
                        queryData.group_id = query.groupId;
                        queryData.timestamp = query.timestamp;
                        queryData.current_app = query.currentApp;

                        // Parse state string
                        try {
                            String stateStr = query.state.replaceAll("[\\[\\]\\s]", "");
                            String[] stateParts = stateStr.split(",");
                            queryData.state = new ArrayList<>();
                            for (String part : stateParts) {
                                queryData.state.add(Integer.parseInt(part));
                            }
                        } catch (Exception e) {
                            queryData.state = java.util.Arrays.asList(0, 0, 0, 0);
                        }

                        queryData.action = query.action;
                        queryData.compliance = query.compliance;
                        queryData.is_exploit = query.isExploit;
                        apiQueries.add(queryData);
                    }
                }

                // Create upload request
                RetrofitApiService.DailyUpload uploadRequest = new RetrofitApiService.DailyUpload();
                uploadRequest.user_id = userId;
                uploadRequest.date = todayDate;
                uploadRequest.sessions = apiSessions;
                uploadRequest.queries = apiQueries;

                // Make API call
                OkHttpClient okHttpClient = new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .writeTimeout(30, TimeUnit.SECONDS)
                        .build();

                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                Retrofit retrofit = new Retrofit.Builder()
                        .baseUrl(API_BASE_URL)
                        .client(okHttpClient)
                        .addConverterFactory(GsonConverterFactory.create(gson))
                        .build();

                RetrofitApiService apiService = retrofit.create(RetrofitApiService.class);
                Call<RetrofitApiService.UploadResponse> call = apiService.uploadSessions(uploadRequest);

                call.enqueue(new Callback<RetrofitApiService.UploadResponse>() {
                    @Override
                    public void onResponse(Call<RetrofitApiService.UploadResponse> call, Response<RetrofitApiService.UploadResponse> response) {
                        if (response.isSuccessful()) {
                            RetrofitApiService.UploadResponse uploadResponse = response.body();
                            Log.d(TAG, "✅ Manual upload successful!");

                            // Save model data from response
                            if (uploadResponse != null && uploadResponse.updated_model != null) {
                                ModelStorageService.saveQTableFromUpload(AdminConsoleActivity.this, uploadResponse.updated_model);
                            }
                            if (uploadResponse != null && uploadResponse.baseline_stats != null) {
                                ModelStorageService.saveBaselineStats(AdminConsoleActivity.this, uploadResponse.baseline_stats);
                            }

                            // Clear database after successful upload
                            new Thread(() -> {
                                db.appSessionDao().deleteAllSessions();
                                db.queryDao().deleteAllQueries();
                                Log.d(TAG, "✅ Cleared all sessions and queries from database");

                                runOnUiThread(() -> {
                                    Toast.makeText(AdminConsoleActivity.this,
                                            "✅ Uploaded " + sessionCount + " sessions and " + queryCount + " queries!",
                                            Toast.LENGTH_LONG).show();
                                    if (uploadStatusText != null) {
                                        uploadStatusText.setText("✅ Upload complete! Database cleared.");
                                    }
                                    resetUploadButton();
                                    // Refresh the lists
                                    loadSessions();
                                    loadQueries();
                                });
                            }).start();
                        } else {
                            Log.e(TAG, "❌ Upload failed: " + response.code());
                            runOnUiThread(() -> {
                                Toast.makeText(AdminConsoleActivity.this,
                                        "Upload failed: HTTP " + response.code(), Toast.LENGTH_LONG).show();
                                if (uploadStatusText != null) {
                                    uploadStatusText.setText("❌ Upload failed: HTTP " + response.code());
                                }
                                resetUploadButton();
                            });
                        }
                    }

                    @Override
                    public void onFailure(Call<RetrofitApiService.UploadResponse> call, Throwable t) {
                        Log.e(TAG, "❌ Upload failed: " + t.getMessage(), t);
                        runOnUiThread(() -> {
                            Toast.makeText(AdminConsoleActivity.this,
                                    "Upload failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                            if (uploadStatusText != null) {
                                uploadStatusText.setText("❌ Upload failed: " + t.getMessage());
                            }
                            resetUploadButton();
                        });
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Error during upload", e);
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    resetUploadButton();
                });
            }
        }).start();
    }

    /**
     * Reset the upload button to its default state
     */
    private void resetUploadButton() {
        if (uploadAllNowButton != null) {
            uploadAllNowButton.setEnabled(true);
            uploadAllNowButton.setText("Upload All Sessions & Queries Now");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && timerRunnable != null) {
            handler.removeCallbacks(timerRunnable);
        }
    }
}
