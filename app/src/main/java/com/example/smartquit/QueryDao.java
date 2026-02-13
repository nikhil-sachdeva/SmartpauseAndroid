package com.example.smartquit;

import androidx.room.Dao;
import androidx.room.Insert;

import java.util.List;

@Dao
public interface QueryDao {
    @Insert
    void insert(Query query);
    
    @Insert
    long insertAndReturnId(Query query);

    @androidx.room.Query("SELECT * FROM queries ORDER BY timestamp DESC")
    List<Query> getAllQueries();

    @androidx.room.Query("DELETE FROM queries")
    void deleteAllQueries();

    @androidx.room.Query("SELECT COUNT(*) FROM queries")
    int getQueryCount();
    
    @androidx.room.Query("UPDATE queries SET compliance = :compliance WHERE id = :queryId")
    void updateCompliance(int queryId, int compliance);
}
