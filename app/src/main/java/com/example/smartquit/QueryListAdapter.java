package com.example.smartquit;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

public class QueryListAdapter extends ArrayAdapter<Query> {

    public QueryListAdapter(Context context, List<Query> queries) {
        super(context, 0, queries);
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Query query = getItem(position);

        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.list_item_query, parent, false);
        }

        TextView groupIdTextView = convertView.findViewById(R.id.groupIdTextView);
        TextView timestampTextView = convertView.findViewById(R.id.timestampTextView);
        TextView currentAppTextView = convertView.findViewById(R.id.currentAppTextView);
        TextView stateTextView = convertView.findViewById(R.id.stateTextView);
        TextView actionTextView = convertView.findViewById(R.id.actionTextView);
        TextView complianceTextView = convertView.findViewById(R.id.complianceTextView);
        TextView decisionTypeTextView = convertView.findViewById(R.id.decisionTypeTextView);

        groupIdTextView.setText("Group: " + query.groupId);
        timestampTextView.setText("Time: " + query.timestamp);
        currentAppTextView.setText("App: " + query.currentApp);
        stateTextView.setText("State: " + query.state);
        
        String actionText = query.action == 1 ? "Vibration Triggered" : "No Vibration";
        actionTextView.setText("Action: " + actionText);
        
        String complianceText = query.compliance == 1 ? "User Complied" : "User Did Not Comply";
        complianceTextView.setText(complianceText);
        
        String decisionType = query.isExploit == 1 ? "Q-Table Exploit" : "Random/Explore";
        decisionTypeTextView.setText("Decision: " + decisionType);

        return convertView;
    }
}
