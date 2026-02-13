package com.example.smartquit;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class AdminConsoleActivity extends AppCompatActivity {

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
    private SwitchCompat testModeToggle;
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_TEST_MODE = "test_mode";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_console);

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

        // Set up Test Mode toggle
        testModeToggle = findViewById(R.id.testModeToggle);
        if (testModeToggle != null) {
            boolean isTestMode = getTestModePreference();
            testModeToggle.setChecked(isTestMode);
            testModeToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
                saveTestModePreference(isChecked);
                Toast.makeText(AdminConsoleActivity.this,
                        isChecked ? "Test Mode Enabled" : "Production Mode Enabled",
                        Toast.LENGTH_SHORT).show();
            });
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
     * Save test mode preference to SharedPreferences
     */
    private void saveTestModePreference(boolean isTestMode) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_TEST_MODE, isTestMode);
        editor.apply();
    }

    /**
     * Get test mode preference (default: true)
     */
    private boolean getTestModePreference() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_TEST_MODE, true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null && timerRunnable != null) {
            handler.removeCallbacks(timerRunnable);
        }
    }
}
