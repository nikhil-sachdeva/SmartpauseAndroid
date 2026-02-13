package com.example.smartquit;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.List;

public class ModelDataListAdapter extends BaseAdapter {

    private Context context;
    private List<String> dataItems;
    private LayoutInflater inflater;

    public ModelDataListAdapter(Context context, List<String> dataItems) {
        this.context = context;
        this.dataItems = dataItems;
        this.inflater = LayoutInflater.from(context);
    }

    @Override
    public int getCount() {
        return dataItems.size();
    }

    @Override
    public Object getItem(int position) {
        return dataItems.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = inflater.inflate(android.R.layout.simple_list_item_1, parent, false);
        }

        TextView textView = convertView.findViewById(android.R.id.text1);
        textView.setText(dataItems.get(position));
        textView.setPadding(16, 12, 16, 12);
        textView.setTextSize(14);

        return convertView;
    }

    public void updateData(List<String> newData) {
        this.dataItems = newData;
        notifyDataSetChanged();
    }
}