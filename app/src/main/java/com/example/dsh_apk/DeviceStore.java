package com.example.dsh_apk;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class DeviceStore {
    static final class Device {
        final String id;
        final String name;
        final String note;
        final String url;

        Device(String id, String name, String note, String url) {
            this.id = id;
            this.name = name;
            this.note = note;
            this.url = url;
        }
    }

    private final SharedPreferences prefs;

    DeviceStore(Context context) {
        prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
    }

    List<Device> list() {
        String raw = prefs.getString("devices", null);
        if (raw == null) {
            List<Device> initial = new ArrayList<>();
            String previousUrl = prefs.getString("url",
                    prefs.getString("token_url", null));
            if (previousUrl != null) {
                Device first = new Device(UUID.randomUUID().toString(), "我的电脑", "",
                        previousUrl);
                initial.add(first);
                save(initial, first.id);
            } else {
                save(initial, "");
            }
            return initial;
        }
        List<Device> devices = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                devices.add(new Device(item.getString("id"), item.getString("name"),
                        item.optString("note"), item.getString("url")));
            }
        } catch (Exception ignored) {
            // Keep the stored document for inspection rather than replacing it.
        }
        return devices;
    }

    String selectedId() {
        return prefs.getString("selected_device", "");
    }

    void select(String id) {
        prefs.edit().putString("selected_device", id).apply();
    }

    String selectedWorkspace(String deviceId) {
        return prefs.getString("workspace_" + deviceId, "");
    }

    void selectWorkspace(String deviceId, String workspaceId) {
        prefs.edit().putString("workspace_" + deviceId, workspaceId).apply();
    }

    String selectedSession(String deviceId, String workspaceId) {
        return prefs.getString("session_" + deviceId + "_" + workspaceId, "");
    }

    void selectSession(String deviceId, String workspaceId, String sessionId) {
        prefs.edit().putString("session_" + deviceId + "_" + workspaceId, sessionId).apply();
    }

    void save(List<Device> devices, String selectedId) {
        JSONArray array = new JSONArray();
        for (Device device : devices) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", device.id);
                item.put("name", device.name);
                item.put("note", device.note);
                item.put("url", device.url);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            array.put(item);
        }
        prefs.edit().putString("devices", array.toString())
                .putString("selected_device", selectedId).apply();
    }
}
