package com.example.smartquit;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

@Dao
public interface AppSessionDao {
    @Insert
    void insertSession(AppSession session);

    @Query("SELECT * FROM app_sessions ORDER BY startTime DESC")
    List<AppSession> getAllSessions();

    @Query("SELECT * FROM app_sessions WHERE date = :date ORDER BY startTime DESC")
    List<AppSession> getSessionsByDate(String date);

    @Query("DELETE FROM app_sessions")
    void deleteAllSessions();

    @Query("DELETE FROM app_sessions WHERE date = :date")
    int deleteSessionsByDate(String date);
}
