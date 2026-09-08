package com.example.smartquit;

import android.content.Context;
import android.util.Log;

import com.google.firebase.analytics.FirebaseAnalytics;

import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.UUID;

/**
 * Provides an atomic, file-backed guard to ensure uploads run only once per day.
 * Uses FileChannel.lock() to perform atomic read-modify-write operations across processes.
 */
public class UploadGuard {

    private static final String TAG = "UploadGuard";
    private static final String LOCK_FILE = "upload_guard.json";
    private static final long STALE_INPROGRESS_MS = 10 * 60 * 1000L; // 10 minutes

    private static File getLockFile(Context ctx) {
        return new File(ctx.getFilesDir(), LOCK_FILE);
    }

    private static String todayDate() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            return java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
        } else {
            return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        }
    }

    /**
     * Try to atomically acquire permission to run today's upload. Returns a runId if acquired, or null otherwise.
     */
    public static String tryAcquire(Context ctx) {
        String runId = UUID.randomUUID().toString();
        File f = getLockFile(ctx);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw");
             FileChannel ch = raf.getChannel();
             FileLock lock = ch.lock()) {

            long fileLen = raf.length();
            String content = fileLen > 0 ? readFully(raf) : null;
            JSONObject obj = content != null ? new JSONObject(content) : new JSONObject();
            String lastSuccess = obj.optString("last_successful_date", "");
            JSONObject inprog = obj.has("in_progress") ? obj.optJSONObject("in_progress") : null;

            String today = todayDate();
            long now = System.currentTimeMillis();

            // If we already completed today, deny
            if (today.equals(lastSuccess)) {
                logDenied(ctx, "already_uploaded_today");
                return null;
            }

            // If someone else is in-progress and not stale, deny
            if (inprog != null) {
                long ts = inprog.optLong("ts", 0);
                if (now - ts < STALE_INPROGRESS_MS) {
                    logDenied(ctx, "in_progress_by_other");
                    return null;
                }
                // stale - allow takeover
            }

            // Acquire: set in_progress
            JSONObject newInProg = new JSONObject();
            newInProg.put("run_id", runId);
            newInProg.put("ts", now);
            obj.put("in_progress", newInProg);

            // write back
            String out = obj.toString();
            raf.setLength(0);
            raf.seek(0);
            raf.write(out.getBytes("UTF-8"));

            Log.d(TAG, "Acquired upload guard runId=" + runId);
            return runId;
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire upload guard: " + e.getMessage(), e);
            try { FirebaseAnalytics.getInstance(ctx).logEvent("upload_guard_acquire_failed", null); } catch (Exception ignored) {}
            return null;
        }
    }

    /**
     * Mark the upload as successful for today and clear in_progress if runId matches (atomic).
     */
    public static void markSuccess(Context ctx, String runId) {
        if (runId == null) return;
        File f = getLockFile(ctx);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw");
             FileChannel ch = raf.getChannel();
             FileLock lock = ch.lock()) {

            String content = raf.length() > 0 ? readFully(raf) : null;
            JSONObject obj = content != null ? new JSONObject(content) : new JSONObject();
            JSONObject inprog = obj.has("in_progress") ? obj.optJSONObject("in_progress") : null;
            if (inprog != null) {
                String currentRun = inprog.optString("run_id", "");
                if (!runId.equals(currentRun)) {
                    Log.w(TAG, "markSuccess: runId mismatch, not clearing in_progress");
                }
            }

            obj.put("last_successful_date", todayDate());
            obj.remove("in_progress");

            String out = obj.toString();
            raf.setLength(0);
            raf.seek(0);
            raf.write(out.getBytes("UTF-8"));

            Log.d(TAG, "Marked upload success for today by runId=" + runId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to mark upload success: " + e.getMessage(), e);
            try { FirebaseAnalytics.getInstance(ctx).logEvent("upload_guard_mark_success_failed", null); } catch (Exception ignored) {}
        }
    }

    /**
     * Clear in_progress if runId matches or if it's stale. Used after a failed upload.
     */
    public static void clearIfMatches(Context ctx, String runId) {
        if (runId == null) return;
        File f = getLockFile(ctx);
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw");
             FileChannel ch = raf.getChannel();
             FileLock lock = ch.lock()) {

            String content = raf.length() > 0 ? readFully(raf) : null;
            JSONObject obj = content != null ? new JSONObject(content) : new JSONObject();
            JSONObject inprog = obj.has("in_progress") ? obj.optJSONObject("in_progress") : null;
            if (inprog != null) {
                String currentRun = inprog.optString("run_id", "");
                long ts = inprog.optLong("ts", 0);
                long now = System.currentTimeMillis();
                if (runId.equals(currentRun) || now - ts > STALE_INPROGRESS_MS) {
                    obj.remove("in_progress");
                    String out = obj.toString();
                    raf.setLength(0);
                    raf.seek(0);
                    raf.write(out.getBytes("UTF-8"));
                    Log.d(TAG, "Cleared in_progress (runId=" + currentRun + ")");
                } else {
                    Log.d(TAG, "Not clearing in_progress - runId mismatch and not stale");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear in_progress: " + e.getMessage(), e);
        }
    }

    private static String readFully(RandomAccessFile raf) throws Exception {
        raf.seek(0);
        byte[] bytes = new byte[(int) raf.length()];
        raf.readFully(bytes);
        return new String(bytes, "UTF-8");
    }

    private static void logDenied(Context ctx, String reason) {
        try {
            android.os.Bundle b = new android.os.Bundle();
            b.putString("reason", reason);
            b.putString("manufacturer", android.os.Build.MANUFACTURER);
            b.putInt("android_sdk", android.os.Build.VERSION.SDK_INT);
            FirebaseAnalytics.getInstance(ctx).logEvent("upload_start_denied", b);
        } catch (Exception ignored) {}
    }
}
