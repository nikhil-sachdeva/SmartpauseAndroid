package com.example.smartquit;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "app_sessions")
public class AppSession {
    @PrimaryKey(autoGenerate = true)
    public int id;
    
    public String userId;
    public String appName;
    public String startTime;
    public String endTime;
    public long durationSeconds;
    public String date;
    public int numVibrations;  // Number of vibrations during this session
    public boolean userComplied;  // Whether user left app during vibration
    public int groupId;  // Group ID for session grouping (starts at 1)

    public AppSession(String userId, String appName, String startTime, String endTime, long durationSeconds, String date) {
        this.userId = userId;
        this.appName = appName;
        this.startTime = startTime;
        this.endTime = endTime;
        this.durationSeconds = durationSeconds;
        this.date = date;
        this.numVibrations = 0;
        this.userComplied = false;
        this.groupId = 1;
    }

    @Override
    public String toString() {
        return "AppSession{" +
                "id=" + id +
                ", userId='" + userId + '\'' +
                ", appName='" + appName + '\'' +
                ", startTime='" + startTime + '\'' +
                ", endTime='" + endTime + '\'' +
                ", durationSeconds=" + durationSeconds +
                ", date='" + date + '\'' +
                ", numVibrations=" + numVibrations +
                ", userComplied=" + userComplied +
                ", groupId=" + groupId +
                '}';
    }
}
