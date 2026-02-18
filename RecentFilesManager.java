package com.udfviewer.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Son açılan UDF dosyalarını SharedPreferences içinde saklar.
 * Her kayıt: uri, dosyaAdı, açılmaZamanı içerir.
 */
public class RecentFilesManager {

    private static final String PREFS_NAME = "udf_recent_files";
    private static final String KEY_RECENT = "recent_list";
    private static final int MAX_RECENT = 10;

    public static class RecentFile {
        public final String uriString;
        public final String fileName;
        public final long openedAt;

        public RecentFile(String uriString, String fileName, long openedAt) {
            this.uriString = uriString;
            this.fileName = fileName;
            this.openedAt = openedAt;
        }

        public Uri getUri() { return Uri.parse(uriString); }

        public String getFormattedDate() {
            long diff = System.currentTimeMillis() - openedAt;
            if (diff < 60_000) return "Az önce";
            if (diff < 3_600_000) return (diff / 60_000) + " dakika önce";
            if (diff < 86_400_000) return (diff / 3_600_000) + " saat önce";
            if (diff < 604_800_000) return (diff / 86_400_000) + " gün önce";
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault());
            return sdf.format(new java.util.Date(openedAt));
        }
    }

    private final SharedPreferences prefs;

    public RecentFilesManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void addFile(Uri uri, String fileName) {
        List<RecentFile> list = getRecentFiles();

        // Zaten varsa kaldır (üste taşımak için)
        list.removeIf(f -> f.uriString.equals(uri.toString()));

        // Başa ekle
        list.add(0, new RecentFile(uri.toString(), fileName, System.currentTimeMillis()));

        // Maksimum boyutu koru
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);

        saveList(list);
    }

    public List<RecentFile> getRecentFiles() {
        List<RecentFile> result = new ArrayList<>();
        String json = prefs.getString(KEY_RECENT, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                result.add(new RecentFile(
                        obj.getString("uri"),
                        obj.getString("name"),
                        obj.getLong("time")
                ));
            }
        } catch (Exception e) { /* ignore */ }
        return result;
    }

    public void removeFile(String uriString) {
        List<RecentFile> list = getRecentFiles();
        list.removeIf(f -> f.uriString.equals(uriString));
        saveList(list);
    }

    public void clearAll() {
        prefs.edit().remove(KEY_RECENT).apply();
    }

    private void saveList(List<RecentFile> list) {
        try {
            JSONArray arr = new JSONArray();
            for (RecentFile f : list) {
                JSONObject obj = new JSONObject();
                obj.put("uri", f.uriString);
                obj.put("name", f.fileName);
                obj.put("time", f.openedAt);
                arr.put(obj);
            }
            prefs.edit().putString(KEY_RECENT, arr.toString()).apply();
        } catch (Exception e) { /* ignore */ }
    }
}
