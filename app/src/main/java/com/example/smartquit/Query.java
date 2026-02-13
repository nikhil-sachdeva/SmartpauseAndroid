package com.example.smartquit;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "queries")
public class Query {
    @PrimaryKey(autoGenerate = true)
    public int id;
    
    public String userId;
    public int groupId;
    public String date;
    public String timestamp;  // ISO format timestamp
    public String currentApp;
    public String state;  // JSON array string like "[0,1,2,1]"
    public int action;  // 0 or 1 (no vibrate or vibrate)
    public int compliance;  // 0 or 1 (did not comply or complied)

    public Query(String userId, int groupId, String date, String timestamp, String currentApp, 
                 String state, int action, int compliance) {
        this.userId = userId;
        this.groupId = groupId;
        this.date = date;
        this.timestamp = timestamp;
        this.currentApp = currentApp;
        this.state = state;
        this.action = action;
        this.compliance = compliance;
    }

    @Override
    public String toString() {
        return "Query{" +
                "id=" + id +
                ", userId='" + userId + '\'' +
                ", groupId=" + groupId +
                ", date='" + date + '\'' +
                ", timestamp='" + timestamp + '\'' +
                ", currentApp='" + currentApp + '\'' +
                ", state='" + state + '\'' +
                ", action=" + action +
                ", compliance=" + compliance +
                '}';
    }
}
