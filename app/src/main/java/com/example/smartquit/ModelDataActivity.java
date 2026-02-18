package com.example.smartquit;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.view.View;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ModelDataActivity extends AppCompatActivity {

    private TextView currentUserIdText;
    private Button loadDataButton;
    private TextView loadingIndicator;
    private TextView errorMessage;
    private LinearLayout contentLayout;
    private LinearLayout statsContainer;
    private ListView sessionsListView;
    private ListView agentDataListView;
    private Spinner qtableStatesSpinner;
    private TextView selectedStateDetails;
    private AppDatabase db;
    private OkHttpClient httpClient;
    private JSONObject currentQTable;

    private static final String BASE_URL = "https://smartquit-cyber.onrender.com";
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_USER_ID = "user_id";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_model_data);

        initializeViews();
        setupHttpClient();
        setupClickListeners();
        
        db = AppDatabase.getDatabase(this);
    }

    private void initializeViews() {
        currentUserIdText = findViewById(R.id.currentUserIdText);
        loadDataButton = findViewById(R.id.loadDataButton);
        loadingIndicator = findViewById(R.id.loadingIndicator);
        errorMessage = findViewById(R.id.errorMessage);
        contentLayout = findViewById(R.id.contentLayout);
        statsContainer = findViewById(R.id.statsContainer);
        sessionsListView = findViewById(R.id.sessionsListView);
        agentDataListView = findViewById(R.id.agentDataListView);
        qtableStatesSpinner = findViewById(R.id.qtableStatesSpinner);
        selectedStateDetails = findViewById(R.id.selectedStateDetails);
        
        // Initially hide content and error
        contentLayout.setVisibility(View.GONE);
        errorMessage.setVisibility(View.GONE);
        loadingIndicator.setVisibility(View.GONE);
        
        // Display current user ID
        String userId = getCurrentUserId();
        if (userId != null) {
            currentUserIdText.setText("Current User: " + userId);
        } else {
            currentUserIdText.setText("No user registered");
        }
    }

    private void setupHttpClient() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    private void setupClickListeners() {
        loadDataButton.setOnClickListener(v -> loadModelData());
        
        // Auto-load data on activity start after a short delay
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            loadModelData();
        }, 500);
    }

    private void loadModelData() {
        String userId = getCurrentUserId();
        
        android.util.Log.d("ModelDataActivity", "Loading data for user: " + userId);
        
        if (userId == null || userId.isEmpty()) {
            showError("No user registered. Please complete registration first.");
            return;
        }
        
        showLoading(true);
        hideError();
        hideContent();
        
        String url = BASE_URL + "/api/v1/model/download/" + userId;
        android.util.Log.d("ModelDataActivity", "API URL: " + url);
        
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    showLoading(false);
                    showError("Network error: " + e.getMessage());
                });
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                runOnUiThread(() -> showLoading(false));
                
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> showError("HTTP " + response.code() + ": " + response.message()));
                    return;
                }
                
                try {
                    String responseBody = response.body().string();
                    JSONObject modelData = new JSONObject(responseBody);
                    
                    // Save model data locally for SessionTrackerService to use
                    saveModelDataLocally(modelData);
                    
                    runOnUiThread(() -> {
                        displayModelData(modelData);
                        showContent();
                    });
                    
                } catch (JSONException | IOException e) {
                    runOnUiThread(() -> showError("Error parsing response: " + e.getMessage()));
                }
            }
        });
    }
    
    private void saveModelDataLocally(JSONObject modelData) {
        try {
            // Convert JSONObject to ModelDownloadResponse format for ModelStorageService
            RetrofitApiService.ModelDownloadResponse response = new RetrofitApiService.ModelDownloadResponse();
            response.user_id = modelData.optString("user_id");
            response.current_day = modelData.optInt("current_day", 0);  // Extract current day
            response.model_version = modelData.optInt("model_version", 0);
            response.updated_at = modelData.optString("updated_at");
            response.format = modelData.optString("format", "json");
            
            // Extract baseline stats
            JSONObject baselineStatsJson = modelData.optJSONObject("baseline_stats");
            JSONObject qTableInfoJson = modelData.optJSONObject("q_table_info");
            
            if (baselineStatsJson != null) {
                response.baseline_stats = new RetrofitApiService.BaselineStats();
                response.baseline_stats.median_target_app_usage_seconds = (float) baselineStatsJson.optDouble("median_target_app_usage_seconds", 0);
                response.baseline_stats.median_session_usage_seconds = (float) baselineStatsJson.optDouble("median_session_usage_seconds", 60);
                response.baseline_stats.query_interval_seconds = (float) baselineStatsJson.optDouble("query_interval_seconds", 1);
                
                // Try to get epsilon from baseline_stats first, then fall back to q_table_info
                if (baselineStatsJson.has("epsilon")) {
                    response.baseline_stats.epsilon = (float) baselineStatsJson.optDouble("epsilon", 0.1);
                    android.util.Log.d("ModelDataActivity", "📥 Extracted epsilon from baseline_stats: " + response.baseline_stats.epsilon);
                } else if (qTableInfoJson != null && qTableInfoJson.has("epsilon")) {
                    response.baseline_stats.epsilon = (float) qTableInfoJson.optDouble("epsilon", 0.1);
                    android.util.Log.d("ModelDataActivity", "📥 Extracted epsilon from q_table_info (fallback): " + response.baseline_stats.epsilon);
                } else {
                    response.baseline_stats.epsilon = 0.1f;
                    android.util.Log.w("ModelDataActivity", "⚠️  Epsilon not found in server response, using default: 0.1");
                }
            }
            
            // Extract agent data (Q-table)
            Object agentDataObj = modelData.opt("agent_data");
            if (agentDataObj != null) {
                if (agentDataObj instanceof JSONObject) {
                    response.agent_data = ((JSONObject) agentDataObj).toString();
                } else if (agentDataObj instanceof String) {
                    response.agent_data = (String) agentDataObj;
                }
            }
            
            // Save using ModelStorageService
            ModelStorageService.saveModel(this, response);
            
            // Verify epsilon was saved correctly by reading it back
            float savedEpsilon = ModelStorageService.getEpsilon(this);
            android.util.Log.d("ModelDataActivity", "🔍 Verification: Read back saved epsilon: " + savedEpsilon);
            
            // Refresh SessionTrackerService
            SessionTrackerService.refreshModelDataForRunningService(this);
            
            String epsilonMsg = response.baseline_stats != null ? " (ε=" + response.baseline_stats.epsilon + ")" : "";
            android.util.Log.d("ModelDataActivity", "✅ Model data saved locally and SessionTrackerService refreshed" + epsilonMsg);
            
            // Show toast with epsilon value for user feedback
            final String toastMessage = "Model updated" + epsilonMsg;
            runOnUiThread(() -> Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show());
            
        } catch (Exception e) {
            android.util.Log.e("ModelDataActivity", "Error saving model data locally: " + e.getMessage(), e);
        }
    }

    private void displayModelData(JSONObject data) {
        try {
            displayBaselineStats(data);
            displaySessionsData(data.optJSONArray("sessions"));
            displayAgentData(data.opt("agent_data"), data.optJSONObject("q_table_info"));
        } catch (Exception e) {
            showError("Error displaying data: " + e.getMessage());
        }
    }

    private void displayBaselineStats(JSONObject data) throws JSONException {
        statsContainer.removeAllViews();
        
        // Get actual baseline stats from API response
        JSONObject baselineStats = data.optJSONObject("baseline_stats");
        JSONObject qTableInfo = data.optJSONObject("q_table_info");
        
        // Calculate some basic stats from sessions for additional info
        JSONArray sessions = data.optJSONArray("sessions");
        int totalSessions = sessions != null ? sessions.length() : 0;
        
        // Calculate total usage time
        long totalSeconds = 0;
        Set<String> uniqueApps = new HashSet<>();
        
        if (sessions != null) {
            for (int i = 0; i < sessions.length(); i++) {
                JSONObject session = sessions.getJSONObject(i);
                totalSeconds += session.optLong("duration_seconds", 0);
                uniqueApps.add(session.optString("app_name", "Unknown"));
            }
        }
        
        String lastActivity = "N/A";
        if (sessions != null && sessions.length() > 0) {
            try {
                JSONObject lastSession = sessions.getJSONObject(sessions.length() - 1);
                lastActivity = lastSession.optString("end_time", "N/A");
                if (!lastActivity.equals("N/A")) {
                    lastActivity = lastActivity.split("T")[0]; // Get just the date part
                }
            } catch (Exception e) {
                lastActivity = "N/A";
            }
        }
        
        // Display User Info
        addStatCard("User ID", data.optString("user_id", "N/A"));
        addStatCard("Model Version", String.valueOf(data.optInt("model_version", 0)));
        
        // Display Q-Table Info (Priority display)
        if (qTableInfo != null) {
            addStatCard("Q-Table States", String.valueOf(qTableInfo.optInt("states_count", 0)));
            addStatCard("Training Steps", String.valueOf(qTableInfo.optInt("training_steps", 0)));
            addStatCard("Epsilon (Exploration)", String.format("%.3f", qTableInfo.optDouble("epsilon", 0.0)));
            addStatCard("Alpha (Learning Rate)", String.format("%.3f", qTableInfo.optDouble("alpha", 0.0)));
            addStatCard("Gamma (Discount)", String.format("%.3f", qTableInfo.optDouble("gamma", 0.0)));
            addStatCard("Model Status", qTableInfo.optBoolean("has_q_table", false) ? "✅ Active" : "⚠️ No Model");
        } else {
            addStatCard("Q-Table Info", "Not Available");
        }
        
        // Display Actual Baseline Statistics
        if (baselineStats != null) {
            int medianTargetUsageSeconds = baselineStats.optInt("median_target_app_usage_seconds", 0);
            int medianSessionUsageSeconds = baselineStats.optInt("median_session_usage_seconds", 0);
            int queryInterval = baselineStats.optInt("query_interval_seconds", 0);
            
            addStatCard("Median Target Usage", medianTargetUsageSeconds + " seconds (" + (medianTargetUsageSeconds / 60) + " min)");
            addStatCard("Median Session Usage", medianSessionUsageSeconds + " seconds (" + (medianSessionUsageSeconds / 60) + " min)");
            addStatCard("Query Interval", queryInterval + " seconds");
            
            // Display epsilon from baseline_stats if available (this is what's actually used operationally)
            if (baselineStats.has("epsilon")) {
                addStatCard("Epsilon (Operational)", String.format("%.3f", baselineStats.optDouble("epsilon", 0.1)));
            }
        } else {
            addStatCard("Baseline Stats", "Not Available");
        }
        
        // Display calculated session stats for additional context
        addStatCard("Total Sessions", String.valueOf(totalSessions));
        addStatCard("Total Usage Time", (totalSeconds / 60) + " min");
        addStatCard("Unique Apps", String.valueOf(uniqueApps.size()));
        addStatCard("Last Activity", lastActivity);
        
        // Display model update timestamp
        String updatedAt = data.optString("updated_at", "N/A");
        if (!updatedAt.equals("N/A")) {
            try {
                updatedAt = updatedAt.split("T")[0]; // Get just the date part
            } catch (Exception e) {
                updatedAt = "N/A";
            }
        }
        addStatCard("Last Updated", updatedAt);
    }

    private void addStatCard(String title, String value) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(24, 24, 24, 24);
        card.setBackgroundColor(getResources().getColor(android.R.color.background_light));
        
        // Create card with fixed width for consistent layout
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        cardParams.setMargins(8, 8, 8, 8);
        card.setLayoutParams(cardParams);
        
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(12);
        titleView.setTextColor(getResources().getColor(android.R.color.darker_gray));
        titleView.setGravity(android.view.Gravity.CENTER);
        
        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextSize(16);
        valueView.setTextColor(getResources().getColor(android.R.color.holo_blue_dark));
        valueView.setTypeface(null, android.graphics.Typeface.BOLD);
        valueView.setGravity(android.view.Gravity.CENTER);
        
        card.addView(titleView);
        card.addView(valueView);
        
        // Create new row every 3 cards or if this is the first card
        int childCount = statsContainer.getChildCount();
        LinearLayout currentRow;
        
        if (childCount == 0 || (childCount > 0 && 
            ((LinearLayout)statsContainer.getChildAt(childCount - 1)).getChildCount() >= 3)) {
            // Create new row
            currentRow = new LinearLayout(this);
            currentRow.setOrientation(LinearLayout.HORIZONTAL);
            currentRow.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            currentRow.setPadding(0, 8, 0, 8);
            statsContainer.addView(currentRow);
        } else {
            // Use existing row
            currentRow = (LinearLayout) statsContainer.getChildAt(childCount - 1);
        }
        
        currentRow.addView(card);
    }

    private void displaySessionsData(JSONArray sessions) {
        List<String> sessionStrings = new ArrayList<>();
        
        if (sessions != null) {
            for (int i = 0; i < sessions.length(); i++) {
                try {
                    JSONObject session = sessions.getJSONObject(i);
                    String sessionStr = String.format("%s | %ds | Group %s | %s",
                            session.optString("app_name", "N/A"),
                            session.optInt("duration_seconds", 0),
                            session.optString("group_id", "N/A"),
                            session.optString("date", "N/A")
                    );
                    sessionStrings.add(sessionStr);
                } catch (JSONException e) {
                    sessionStrings.add("Error parsing session data");
                }
            }
        }
        
        if (sessionStrings.isEmpty()) {
            sessionStrings.add("No session data available");
        }
        
        ModelDataListAdapter sessionsAdapter = new ModelDataListAdapter(this, sessionStrings);
        sessionsListView.setAdapter(sessionsAdapter);
    }

    private void displayAgentData(Object agentData, JSONObject qTableInfo) {
        List<String> agentStrings = new ArrayList<>();
        
        try {
            // Store Q-table data for dropdown
            if (agentData instanceof JSONObject) {
                currentQTable = (JSONObject) agentData;
                setupQTableStatesDropdown(currentQTable);
            } else {
                currentQTable = null;
                setupQTableStatesDropdown(null);
            }
            
            // Display Q-table information first (most important)
            if (qTableInfo != null) {
                agentStrings.add("=== Q-TABLE OVERVIEW ===");
                agentStrings.add("States Count: " + qTableInfo.optInt("states_count", 0));
                agentStrings.add("Has Model: " + (qTableInfo.optBoolean("has_q_table", false) ? "Yes" : "No"));
                agentStrings.add("Training Steps: " + qTableInfo.optInt("training_steps", 0));
                agentStrings.add("Epsilon (Exploration): " + String.format("%.3f", qTableInfo.optDouble("epsilon", 0.0)));
                agentStrings.add("Alpha (Learning Rate): " + String.format("%.3f", qTableInfo.optDouble("alpha", 0.0)));
                agentStrings.add("Gamma (Discount Factor): " + String.format("%.3f", qTableInfo.optDouble("gamma", 0.0)));
                agentStrings.add("");
            }
            
            // Display Q-table data (show sample states)
            if (agentData instanceof JSONObject) {
                JSONObject qTable = (JSONObject) agentData;
                JSONArray stateKeys = qTable.names();
                
                if (stateKeys != null && stateKeys.length() > 0) {
                    agentStrings.add("=== Q-TABLE SAMPLE STATES ===");
                    agentStrings.add("State Format: num_queries_num_vibrations_first_app_target_quarter_of_day");
                    agentStrings.add("Actions: 0=no_vibrate, 1=vibrate");
                    agentStrings.add("");
                    
                    // Show first 10 states as examples
                    int maxStates = Math.min(10, stateKeys.length());
                    for (int i = 0; i < maxStates; i++) {
                        String stateKey = stateKeys.getString(i);
                        JSONObject actions = qTable.getJSONObject(stateKey);
                        
                        // Parse state key to show meaningful description
                        String stateDescription = parseStateKey(stateKey);
                        
                        double noVibrateValue = actions.optDouble("0", 0.0);
                        double vibrateValue = actions.optDouble("1", 0.0);
                        
                        agentStrings.add(String.format("State: %s", stateKey));
                        agentStrings.add(String.format("  %s", stateDescription));
                        agentStrings.add(String.format("  No Vibrate: %.3f | Vibrate: %.3f", noVibrateValue, vibrateValue));
                        
                        // Show which action is preferred
                        String preference = vibrateValue > noVibrateValue ? "Prefers VIBRATE" : 
                                          noVibrateValue > vibrateValue ? "Prefers NO VIBRATE" : "NEUTRAL";
                        agentStrings.add(String.format("  Decision: %s", preference));
                        agentStrings.add("");
                    }
                    
                    if (stateKeys.length() > 10) {
                        agentStrings.add(String.format("... and %d more states", stateKeys.length() - 10));
                    }
                } else {
                    agentStrings.add("=== Q-TABLE DATA ===");
                    agentStrings.add("No Q-table states available");
                    agentStrings.add("Model may not be trained yet");
                }
            } else {
                agentStrings.add("=== AGENT DATA ===");
                agentStrings.add("Agent data format not recognized");
                agentStrings.add("Data type: " + (agentData != null ? agentData.getClass().getSimpleName() : "null"));
            }
            
        } catch (JSONException e) {
            agentStrings.add("Error parsing Q-table data: " + e.getMessage());
        } catch (Exception e) {
            agentStrings.add("Unexpected error: " + e.getMessage());
        }
        
        if (agentStrings.isEmpty()) {
            agentStrings.add("No Q-table data available");
        }
        
        ModelDataListAdapter agentAdapter = new ModelDataListAdapter(this, agentStrings);
        agentDataListView.setAdapter(agentAdapter);
    }
    
    private void setupQTableStatesDropdown(JSONObject qTable) {
        List<String> stateOptions = new ArrayList<>();
        List<String> stateKeys = new ArrayList<>();
        
        if (qTable != null) {
            try {
                JSONArray keys = qTable.names();
                if (keys != null) {
                    for (int i = 0; i < keys.length(); i++) {
                        String stateKey = keys.getString(i);
                        String stateDescription = parseStateKey(stateKey);
                        stateOptions.add(String.format("%s - %s", stateKey, stateDescription));
                        stateKeys.add(stateKey);
                    }
                }
            } catch (JSONException e) {
                android.util.Log.e("ModelDataActivity", "Error parsing Q-table states: " + e.getMessage());
            }
        }
        
        if (stateOptions.isEmpty()) {
            stateOptions.add("No Q-table states available");
            stateKeys.add("");
        }
        
        // Setup spinner adapter
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, stateOptions);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        qtableStatesSpinner.setAdapter(adapter);
        
        // Setup spinner selection listener
        qtableStatesSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < stateKeys.size() && !stateKeys.get(position).isEmpty()) {
                    displaySelectedStateDetails(stateKeys.get(position));
                } else {
                    selectedStateDetails.setText("No state selected");
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedStateDetails.setText("No state selected");
            }
        });
    }
    
    private void displaySelectedStateDetails(String stateKey) {
        if (currentQTable == null) {
            selectedStateDetails.setText("No Q-table data available");
            return;
        }
        
        try {
            JSONObject actions = currentQTable.getJSONObject(stateKey);
            double noVibrateValue = actions.optDouble("0", 0.0);
            double vibrateValue = actions.optDouble("1", 0.0);
            
            String stateDescription = parseStateKey(stateKey);
            String preference = vibrateValue > noVibrateValue ? "PREFERS VIBRATE ✓" : 
                              noVibrateValue > vibrateValue ? "PREFERS NO VIBRATE ✗" : "NEUTRAL ≈";
            
            String details = String.format(
                "State: %s\n\n" +
                "Description: %s\n\n" +
                "Q-Values:\n" +
                "• No Vibrate (Action 0): %.4f\n" +
                "• Vibrate (Action 1): %.4f\n\n" +
                "Decision: %s\n\n" +
                "Confidence: %.4f",
                stateKey,
                stateDescription,
                noVibrateValue,
                vibrateValue,
                preference,
                Math.abs(vibrateValue - noVibrateValue)
            );
            
            selectedStateDetails.setText(details);
            
        } catch (JSONException e) {
            selectedStateDetails.setText("Error loading state details: " + e.getMessage());
        }
    }
    
    private String parseStateKey(String stateKey) {
        try {
            String[] parts = stateKey.split("_");
            if (parts.length == 4) {
                String numQueries = "Queries: " + parts[0];
                String numVibrations = "Vibrations: " + parts[1];
                String firstAppTarget = parts[2].equals("1") ? "First app: Target" : "First app: Non-target";
                String quarterOfDay;
                switch (parts[3]) {
                    case "0": quarterOfDay = "Quarter: Night/Early (0-6)"; break;
                    case "1": quarterOfDay = "Quarter: Morning (6-12)"; break;
                    case "2": quarterOfDay = "Quarter: Afternoon (12-18)"; break;
                    case "3": quarterOfDay = "Quarter: Evening (18-24)"; break;
                    default: quarterOfDay = "Quarter: Unknown";
                }

                return String.format("%s, %s, %s, %s", numQueries, numVibrations, firstAppTarget, quarterOfDay);
            }
        } catch (Exception e) {
            // Fall back to raw state key
        }
        return "State: " + stateKey;
    }

    private void showLoading(boolean show) {
        loadingIndicator.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showError(String message) {
        errorMessage.setText(message);
        errorMessage.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorMessage.setVisibility(View.GONE);
    }

    private void showContent() {
        contentLayout.setVisibility(View.VISIBLE);
    }

    private void hideContent() {
        contentLayout.setVisibility(View.GONE);
    }

    private String getCurrentUserId() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getString(KEY_USER_ID, null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (httpClient != null) {
            httpClient.dispatcher().executorService().shutdown();
        }
    }
}