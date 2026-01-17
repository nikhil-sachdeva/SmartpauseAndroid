package com.example.smartquit;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

/**
 * Adapter for displaying API logs in ListView
 */
public class LogsAdapter extends ArrayAdapter<RetrofitApiService.APILogEntry> {
    
    public LogsAdapter(Context context, List<RetrofitApiService.APILogEntry> logs) {
        super(context, 0, logs);
    }
    
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        RetrofitApiService.APILogEntry log = getItem(position);
        
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.list_item_log, parent, false);
        }
        
        TextView endpointTextView = convertView.findViewById(R.id.endpointTextView);
        TextView methodTextView = convertView.findViewById(R.id.methodTextView);
        TextView statusTextView = convertView.findViewById(R.id.statusTextView);
        TextView timeTextView = convertView.findViewById(R.id.timeTextView);
        TextView errorTextView = convertView.findViewById(R.id.errorTextView);
        
        // Set endpoint and method
        endpointTextView.setText("Endpoint: " + log.endpoint);
        methodTextView.setText("Method: " + log.method);
        
        // Set status code with color coding
        String statusText = "Status: " + log.status_code;
        statusTextView.setText(statusText);
        if (log.status_code >= 200 && log.status_code < 300) {
            statusTextView.setTextColor(getContext().getResources().getColor(android.R.color.holo_green_dark));
        } else if (log.status_code >= 400 && log.status_code < 500) {
            statusTextView.setTextColor(getContext().getResources().getColor(android.R.color.holo_orange_dark));
        } else if (log.status_code >= 500) {
            statusTextView.setTextColor(getContext().getResources().getColor(android.R.color.holo_red_dark));
        }
        
        // Set timestamp
        timeTextView.setText("Time: " + formatTime(log.created_at));
        
        // Set error message if present
        if (log.error_message != null && !log.error_message.isEmpty()) {
            errorTextView.setText("Error: " + log.error_message);
            errorTextView.setVisibility(View.VISIBLE);
        } else {
            errorTextView.setVisibility(View.GONE);
        }
        
        return convertView;
    }
    
    /**
     * Format ISO timestamp to readable format
     */
    private String formatTime(String isoTimestamp) {
        if (isoTimestamp == null || isoTimestamp.isEmpty()) {
            return "Unknown";
        }
        try {
            // Parse ISO format: 2026-01-17T15:30:45.123456
            // Display as: Jan 17, 3:30 PM
            String[] parts = isoTimestamp.split("T");
            if (parts.length == 2) {
                String date = parts[0];
                String time = parts[1].split("\\.")[0];
                
                // Parse date
                String[] dateParts = date.split("-");
                int month = Integer.parseInt(dateParts[1]);
                int day = Integer.parseInt(dateParts[2]);
                
                // Parse time
                String[] timeParts = time.split(":");
                int hour = Integer.parseInt(timeParts[0]);
                int minute = Integer.parseInt(timeParts[1]);
                
                String[] monthNames = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", 
                                     "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
                String ampm = hour >= 12 ? "PM" : "AM";
                if (hour > 12) hour -= 12;
                if (hour == 0) hour = 12;
                
                return String.format("%s %d, %d:%02d %s", monthNames[month - 1], day, hour, minute, ampm);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return isoTimestamp;
    }
}
