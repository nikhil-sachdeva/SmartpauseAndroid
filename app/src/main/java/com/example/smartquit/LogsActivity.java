package com.example.smartquit;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Activity to display API logs for the current user
 */
public class LogsActivity extends AppCompatActivity {
    
    private static final String TAG = "LogsActivity";
    private static final String API_BASE_URL = "https://smartquit-cyber.onrender.com";
    private static final String PREFS_NAME = "SmartQuitPrefs";
    private static final String KEY_USER_ID = "user_id";
    private static final int PAGE_SIZE = 50;
    
    private ListView logsListView;
    private LogsAdapter adapter;
    private List<RetrofitApiService.APILogEntry> logs = new ArrayList<>();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logs);
        
        logsListView = findViewById(R.id.logsListView);
        
        // Load and display logs
        loadLogs();
    }
    
    /**
     * Load API logs from backend
     */
    private void loadLogs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String userId = prefs.getString(KEY_USER_ID, null);
        
        if (userId == null) {
            Toast.makeText(this, "User ID not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        Log.d(TAG, "Loading API logs for user: " + userId);
        
        // Make API call
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(API_BASE_URL)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build();
        
        RetrofitApiService apiService = retrofit.create(RetrofitApiService.class);
        Call<RetrofitApiService.APILogsResponse> call = apiService.getAPILogs(userId, PAGE_SIZE, 0);
        
        call.enqueue(new Callback<RetrofitApiService.APILogsResponse>() {
            @Override
            public void onResponse(Call<RetrofitApiService.APILogsResponse> call, 
                                 Response<RetrofitApiService.APILogsResponse> response) {
                if (response.isSuccessful() && response.body() != null) {
                    RetrofitApiService.APILogsResponse logsResponse = response.body();
                    logs = logsResponse.logs;
                    
                    Log.d(TAG, "Loaded " + logs.size() + " API logs");
                    
                    // Create adapter and display logs
                    adapter = new LogsAdapter(LogsActivity.this, new ArrayList<>(logs));
                    logsListView.setAdapter(adapter);
                    
                    Toast.makeText(LogsActivity.this, 
                            "Loaded " + logs.size() + " API calls (Total: " + logsResponse.total_logs + ")",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Log.e(TAG, "Failed to load logs: " + response.code());
                    Toast.makeText(LogsActivity.this, "Failed to load logs", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onFailure(Call<RetrofitApiService.APILogsResponse> call, Throwable t) {
                Log.e(TAG, "Error loading logs: " + t.getMessage(), t);
                Toast.makeText(LogsActivity.this, "Error loading logs: " + t.getMessage(), 
                        Toast.LENGTH_SHORT).show();
            }
        });
    }
}
