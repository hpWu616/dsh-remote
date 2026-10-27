package com.example.dsh_apk;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class RemoteClient {
    static final class ModelChoice {
        final String provider;
        final String providerName;
        final String id;
        final String name;
        final List<String> effortIds;
        final List<String> effortNames;
        final String defaultEffort;

        ModelChoice(String provider, String providerName, String id, String name,
                    List<String> effortIds, List<String> effortNames, String defaultEffort) {
            this.provider = provider;
            this.providerName = providerName;
            this.id = id;
            this.name = name;
            this.effortIds = effortIds;
            this.effortNames = effortNames;
            this.defaultEffort = defaultEffort;
        }
    }

    static final class ModelCatalog {
        final List<ModelChoice> choices;
        final String selectedProvider;
        final String selectedModel;
        final String selectedEffort;
        final String failure;

        ModelCatalog(List<ModelChoice> choices, String selectedProvider,
                     String selectedModel, String selectedEffort, String failure) {
            this.choices = choices;
            this.selectedProvider = selectedProvider;
            this.selectedModel = selectedModel;
            this.selectedEffort = selectedEffort;
            this.failure = failure;
        }

        String selectedName() {
            for (ModelChoice choice : choices) {
                if (choice.provider.equals(selectedProvider) && choice.id.equals(selectedModel)) {
                    return choice.name + (selectedEffort.isEmpty() ? "" : " · " + selectedEffort);
                }
            }
            return selectedModel.isEmpty() ? "选择模型" : selectedModel;
        }
    }

    static final class Mode {
        final String id;
        final String name;
        final String description;
        Mode(String id, String name, String description) {
            this.id = id; this.name = name; this.description = description;
        }
    }

    static final class Skill {
        final String name;
        final String description;
        Skill(String name, String description) {
            this.name = name; this.description = description;
        }
    }

    List<Skill> skills(String baseUrl, String token, String sessionId) throws Exception {
        JSONArray rows = getJson(baseUrl + "/v1/sessions/" + sessionId + "/skills", token)
                .getJSONArray("items");
        List<Skill> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            result.add(new Skill(row.getString("name"), row.optString("description", "")));
        }
        return result;
    }

    static final class Task {
        final String id;
        final String title;
        final String status;
        final String scheduledAt;
        Task(String id, String title, String status, String scheduledAt) {
            this.id = id; this.title = title; this.status = status;
            this.scheduledAt = scheduledAt;
        }
    }

    List<Task> tasks(String baseUrl, String token, String sessionId) throws Exception {
        JSONArray rows = getJson(baseUrl + "/v1/sessions/" + sessionId + "/tasks", token)
                .getJSONArray("items");
        List<Task> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            result.add(new Task(row.getString("id"), row.optString("title", "未命名任务"),
                    row.optString("status", ""), row.optString("scheduledAt", "")));
        }
        return result;
    }

    void createTask(String baseUrl, String token, String sessionId,
                    String title, String prompt, int minutes, boolean repeat) throws Exception {
        JSONObject body = new JSONObject().put("title", title).put("prompt", prompt)
                .put(repeat ? "every_seconds" : "after_seconds", minutes * 60);
        postJson(baseUrl + "/v1/sessions/" + sessionId + "/tasks", token, body);
    }

    void deleteTask(String baseUrl, String token, String sessionId, String taskId)
            throws Exception {
        HttpURLConnection connection = open(baseUrl + "/v1/sessions/" + sessionId
                + "/tasks/" + taskId, token);
        connection.setReadTimeout(10000);
        connection.setRequestMethod("DELETE");
        try {
            if (connection.getResponseCode() != 204) {
                try (InputStream ignored = response(connection)) { }
            }
        } finally { connection.disconnect(); }
    }

    List<Mode> modes(String baseUrl, String token) throws Exception {
        JSONArray rows = getJson(baseUrl + "/v1/modes", token).getJSONArray("items");
        List<Mode> result = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            result.add(new Mode(row.getString("id"), row.optString("name", row.getString("id")),
                    row.optString("description", "")));
        }
        return result;
    }

    Mode mode(String baseUrl, String token, String sessionId) throws Exception {
        JSONObject row = getJson(baseUrl + "/v1/sessions/" + sessionId + "/mode", token)
                .getJSONObject("mode");
        return new Mode(row.optString("id", ""), row.optString("name", ""), "");
    }

    ModelCatalog models(String baseUrl, String token, String sessionId) throws Exception {
        String url = baseUrl + "/v1/models" + (sessionId.isEmpty() ? "" : "?sessionId="
                + URLEncoder.encode(sessionId, "UTF-8"));
        JSONObject document = getJson(url, token);
        JSONArray groups = document.getJSONArray("groups");
        List<ModelChoice> choices = new ArrayList<>();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.getJSONObject(i);
            JSONArray models = group.getJSONArray("models");
            for (int j = 0; j < models.length(); j++) {
                JSONObject model = models.getJSONObject(j);
                JSONObject reasoning = model.optJSONObject("reasoning");
                JSONArray efforts = reasoning == null ? null : reasoning.optJSONArray("efforts");
                List<String> effortIds = new ArrayList<>();
                List<String> effortNames = new ArrayList<>();
                if (efforts != null) for (int k = 0; k < efforts.length(); k++) {
                    JSONObject effort = efforts.getJSONObject(k);
                    effortIds.add(effort.getString("id"));
                    effortNames.add(effort.optString("name", effort.getString("id")));
                }
                choices.add(new ModelChoice(group.getString("id"), group.getString("name"),
                        model.getString("id"), model.getString("name"), effortIds,
                        effortNames, reasoning == null ? "" : reasoning.optString("defaultEffort", "")));
            }
        }
        JSONObject selected = document.optJSONObject("selected");
        JSONArray failures = document.optJSONArray("failures");
        String failure = "";
        if (failures != null && failures.length() > 0) {
            failure = failures.getJSONObject(0).optString("message", "");
        }
        return new ModelCatalog(choices,
                selected == null ? "" : selected.optString("provider", ""),
                selected == null ? "" : selected.optString("model", ""),
                selected == null ? "" : selected.optString("reasoningEffort", ""), failure);
    }

    void selectModel(String baseUrl, String token, String sessionId,
                     ModelChoice choice, String effort) throws Exception {
        JSONObject request = new JSONObject().put("provider", choice.provider)
                .put("model", choice.id);
        if (!effort.isEmpty()) request.put("reasoningEffort", effort);
        postJson(baseUrl + "/v1/sessions/" + sessionId + "/model", token,
                request);
    }

    static final class Workspace {
        final String id;
        final String title;
        final String path;

        Workspace(String id, String title, String path) {
            this.id = id;
            this.title = title;
            this.path = path;
        }
    }
    static final class Session {
        final String id;
        final String title;
        final boolean archived;
        Session(String id, String title, boolean archived) {
            this.id = id; this.title = title; this.archived = archived;
        }
    }
    static final class Folder {
        final String name;
        final String path;
        Folder(String name, String path) { this.name = name; this.path = path; }
    }
    static final class FolderListing {
        final String path;
        final String parent;
        final List<Folder> folders;
        FolderListing(String path, String parent, List<Folder> folders) {
            this.path = path; this.parent = parent; this.folders = folders;
        }
    }
    static final class Message {
        final String role;
        final String text;
        final String reasoning;
        Message(String role, String text, String reasoning) {
            this.role = role; this.text = text; this.reasoning = reasoning;
        }
    }
    static final class HistoryPage {
        final List<Message> items;
        final boolean hasMore;
        final int before;
        HistoryPage(List<Message> items, boolean hasMore, int before) {
            this.items = items; this.hasMore = hasMore; this.before = before;
        }
    }
    interface Listener {
        void onLine(String type, String data);
    }

    private volatile HttpURLConnection active;

    static String normalizeUrl(String input) throws Exception {
        URI uri = new URI(input.trim());
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getPort() < 1 || uri.getPort() > 65535
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty()
                    && !"/".equals(uri.getRawPath()))) {
            throw new IllegalArgumentException("请输入 http://主机:端口，例如 http://127.0.0.1:19388");
        }
        if (!"127.0.0.1".equals(uri.getHost()) && !"10.7.32.43".equals(uri.getHost())
                && !uri.getHost().matches("dsh-[a-f0-9]{12}\\.local")) {
            throw new IllegalArgumentException("请选择发现的电脑，或使用已配置的 USB 地址");
        }
        return "http://" + (uri.getHost().contains(":") ? "[" + uri.getHost() + "]" : uri.getHost())
                + ":" + uri.getPort();
    }

    void check(String baseUrl, String token) throws Exception {
        HttpURLConnection connection = open(baseUrl + "/v1/health", token);
        try {
            connection.setRequestMethod("GET");
            connection.setReadTimeout(10000);
            response(connection);
        } finally {
            connection.disconnect();
        }
    }

    List<Workspace> workspaces(String baseUrl, String token) throws Exception {
        HttpURLConnection connection = open(baseUrl + "/v1/workspaces", token);
        connection.setReadTimeout(10000);
        try (InputStream stream = response(connection)) {
            String json = readText(stream);
            JSONArray items = new JSONObject(json).getJSONArray("items");
            List<Workspace> result = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                result.add(new Workspace(item.getString("id"), item.getString("title"),
                        item.optString("path")));
            }
            return result;
        } finally {
            connection.disconnect();
        }
    }

    FolderListing folders(String baseUrl, String token, String path) throws Exception {
        JSONObject document = getJson(baseUrl + "/v1/directories?path="
                + URLEncoder.encode(path, "UTF-8"), token);
        JSONArray items = document.getJSONArray("items");
        List<Folder> result = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            result.add(new Folder(item.getString("name"), item.getString("path")));
        }
        return new FolderListing(document.getString("path"),
                document.optString("parent", ""), result);
    }

    Workspace createWorkspace(String baseUrl, String token, String name, String path)
            throws Exception {
        HttpURLConnection connection = open(baseUrl + "/v1/workspaces", token);
        connection.setReadTimeout(10000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = new JSONObject().put("name", name).put("path", path).toString()
                .getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            try (InputStream stream = response(connection)) {
                JSONObject item = new JSONObject(readText(stream)).getJSONObject("workspace");
                return new Workspace(item.getString("id"), item.getString("title"),
                        item.optString("path"));
            }
        } finally {
            connection.disconnect();
        }
    }

    void deleteWorkspace(String baseUrl, String token, String workspaceId) throws Exception {
        HttpURLConnection connection = open(baseUrl + "/v1/workspaces/" + workspaceId, token);
        connection.setReadTimeout(10000);
        connection.setRequestMethod("DELETE");
        try {
            if (connection.getResponseCode() != 204) {
                try (InputStream ignored = response(connection)) { }
            }
        } finally { connection.disconnect(); }
    }

    List<Session> sessions(String baseUrl, String token, String workspaceId) throws Exception {
        JSONObject document = getJson(baseUrl + "/v1/workspaces/" + workspaceId + "/sessions", token);
        JSONArray items = document.getJSONArray("items");
        List<Session> result = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            result.add(new Session(item.getString("id"), item.optString("title", "新会话"),
                    item.optBoolean("archived", false)));
        }
        return result;
    }

    Session createSession(String baseUrl, String token, String workspaceId) throws Exception {
        return createSession(baseUrl, token, workspaceId, "");
    }

    Session createSession(String baseUrl, String token, String workspaceId, String mode)
            throws Exception {
        JSONObject item = postJson(baseUrl + "/v1/workspaces/" + workspaceId + "/sessions",
                token, mode.isEmpty() ? new JSONObject()
                        : new JSONObject().put("agentPreset", mode)).getJSONObject("session");
        return new Session(item.getString("id"), item.optString("title", "新会话"), false);
    }

    HistoryPage history(String baseUrl, String token, String sessionId, int before)
            throws Exception {
        String url = baseUrl + "/v1/sessions/" + sessionId + "/history"
                + (before > 0 ? "?before=" + before : "");
        JSONObject document = getJson(url, token);
        JSONArray items = document.getJSONArray("items");
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            messages.add(new Message(item.getString("role"), item.getString("text"),
                    item.optString("reasoning", "")));
        }
        return new HistoryPage(messages, document.optBoolean("hasMore", false),
                document.optInt("before", -1));
    }

    void archiveSession(String baseUrl, String token, String sessionId, boolean archived)
            throws Exception {
        postJson(baseUrl + "/v1/sessions/" + sessionId + "/archive", token,
                new JSONObject().put("archived", archived));
    }

    List<Message> messages(String baseUrl, String token, String sessionId) throws Exception {
        JSONArray items = getJson(baseUrl + "/v1/sessions/" + sessionId + "/messages", token)
                .getJSONArray("items");
        List<Message> result = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            result.add(new Message(item.getString("role"), item.getString("text"),
                    item.optString("reasoning", "")));
        }
        return result;
    }

    void sendMessage(String baseUrl, String token, String sessionId, String message)
            throws Exception {
        sendMessage(baseUrl, token, sessionId, message, "");
    }

    void sendMessage(String baseUrl, String token, String sessionId, String message,
                     String receiptId) throws Exception {
        sendMessage(baseUrl, token, sessionId, message, receiptId, false);
    }

    void sendMessage(String baseUrl, String token, String sessionId, String message,
                     String receiptId, boolean search) throws Exception {
        JSONObject body = new JSONObject().put("message", message);
        if (!receiptId.isEmpty()) body.put("receiptId", receiptId);
        if (search) body.put("search", true);
        postJson(baseUrl + "/v1/sessions/" + sessionId + "/messages", token,
                body);
    }

    String uploadFile(String baseUrl, String token, String sessionId,
                      String name, String encoded) throws Exception {
        return postJson(baseUrl + "/v1/sessions/" + sessionId + "/files", token,
                new JSONObject().put("name", name).put("data", encoded))
                .getString("receiptId");
    }

    String command(String baseUrl, String token, String sessionId, String line)
            throws Exception {
        JSONObject result = postJson(baseUrl + "/v1/sessions/" + sessionId
                + "/commands", token, new JSONObject().put("line", line));
        String description = result.optString("text", "");
        if ("error".equals(result.optString("kind")))
            throw new IOException(description.isEmpty() ? "命令执行失败" : description);
        return description;
    }

    void downloadLog(String baseUrl, String token, String sessionId,
                     OutputStream destination) throws Exception {
        HttpURLConnection connection = open(baseUrl + "/v1/sessions/" + sessionId + "/log", token);
        connection.setReadTimeout(30000);
        try (InputStream input = response(connection)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) destination.write(buffer, 0, count);
        } finally { connection.disconnect(); }
    }

    private JSONObject getJson(String url, String token) throws Exception {
        HttpURLConnection connection = open(url, token);
        connection.setReadTimeout(10000);
        try (InputStream stream = response(connection)) {
            return new JSONObject(readText(stream));
        } finally { connection.disconnect(); }
    }

    private JSONObject postJson(String url, String token, JSONObject body) throws Exception {
        HttpURLConnection connection = open(url, token);
        connection.setReadTimeout(30000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            try (InputStream stream = response(connection)) {
                return new JSONObject(readText(stream));
            }
        } finally { connection.disconnect(); }
    }

    String pair(String baseUrl, String code) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/v1/pair")
                .openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = new JSONObject().put("code", code).toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    response(connection), StandardCharsets.UTF_8))) {
                String result = reader.readLine();
                String token = new JSONObject(result).getString("token");
                if (token.length() < 32) throw new IOException("服务器返回了无效令牌");
                return token;
            }
        } finally {
            connection.disconnect();
        }
    }

    void execute(String baseUrl, String token, String command, String workspaceId,
                 Listener listener) throws Exception {
        HttpURLConnection connection = open(baseUrl + "/v1/exec", token);
        active = connection;
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] body = new JSONObject().put("command", command)
                    .put("workspaceId", workspaceId).toString()
                    .getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            try (InputStream stream = response(connection);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(stream,
                         StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    JSONObject event = new JSONObject(line);
                    listener.onLine(event.optString("type"), event.optString("data",
                            event.optString("message", "")));
                }
            }
        } finally {
            active = null;
            connection.disconnect();
        }
    }

    void cancel() {
        HttpURLConnection connection = active;
        if (connection != null) connection.disconnect();
    }

    private HttpURLConnection open(String url, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(0);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }

    private InputStream response(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status != 200 && status != 201 && status != 202) {
            InputStream error = connection.getErrorStream();
            String detail = "";
            if (error != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(error,
                        StandardCharsets.UTF_8))) {
                    detail = reader.readLine();
                }
            }
            throw new IOException("HTTP " + status + (detail == null ? "" : ": " + detail));
        }
        return connection.getInputStream();
    }

    private String readText(InputStream stream) throws IOException {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream,
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
