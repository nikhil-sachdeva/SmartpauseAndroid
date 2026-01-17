package com.example.smartquit;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {AppSession.class}, version = 1)
public abstract class AppDatabase extends RoomDatabase {
    public abstract AppSessionDao appSessionDao();

    private static volatile AppDatabase INSTANCE;

    public static AppDatabase getDatabase(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class, "smartquit_database")
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
