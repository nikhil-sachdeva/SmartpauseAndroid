package com.example.smartquit;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

public class SessionListAdapter extends ArrayAdapter<AppSession> {

    public SessionListAdapter(Context context, List<AppSession> sessions) {
        super(context, 0, sessions);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        AppSession session = getItem(position);

        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.list_item_session, parent, false);
        }

        TextView appNameTextView = convertView.findViewById(R.id.appNameTextView);
        TextView startTimeTextView = convertView.findViewById(R.id.startTimeTextView);
        TextView endTimeTextView = convertView.findViewById(R.id.endTimeTextView);
        TextView durationTextView = convertView.findViewById(R.id.durationTextView);
        TextView dateTextView = convertView.findViewById(R.id.dateTextView);
        TextView vibrationsTextView = convertView.findViewById(R.id.vibrationsTextView);
        TextView complianceTextView = convertView.findViewById(R.id.complianceTextView);
        TextView groupIdTextView = convertView.findViewById(R.id.groupIdTextView);

        appNameTextView.setText("App: " + session.appName);
        startTimeTextView.setText("Start: " + session.startTime);
        endTimeTextView.setText("End: " + session.endTime);
        durationTextView.setText("Duration: " + session.durationSeconds + "s");
        dateTextView.setText("Date: " + session.date);
        vibrationsTextView.setText("Vibrations: " + session.numVibrations);
        complianceTextView.setText("User Complied: " + (session.userComplied ? "Yes" : "No"));
        groupIdTextView.setText("Group: " + session.groupId);

        return convertView;
    }
}
