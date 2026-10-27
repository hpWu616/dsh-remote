package com.example.dsh_apk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.database.Cursor;
import android.net.Uri;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";
    private static final int NETWORK_REQUEST = 10;
    private static final int MAX_DISPLAY = 250_000;
    private static final int PICK_ATTACHMENT = 21;
    private static final int SAVE_LOG = 22;
    private static final int MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024;
    private static final int NAVY = Color.rgb(24, 40, 73);
    private static final int BLUE = Color.rgb(52, 104, 220);
    private static final int MUTED = Color.rgb(105, 119, 143);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService probes = Executors.newFixedThreadPool(4);
    private final ExecutorService discoveryWorker = Executors.newSingleThreadExecutor();
    private final RemoteClient client = new RemoteClient();
    private final Map<String, String> deviceStatus = new HashMap<>();
    private final Map<String, List<RemoteClient.Workspace>> workspaceCache = new HashMap<>();
    private final Map<String, List<RemoteClient.Session>> sessionCache = new HashMap<>();
    private SecretStore secrets;
    private DeviceStore devices;
    private DeviceStore.Device selected;
    private LinearLayout deviceRows;
    private LinearLayout workspaceRows;
    private LinearLayout sessionRows;
    private LinearLayout messageRows;
    private LinearLayout devicePanel;
    private LinearLayout navigationPanel;
    private TextView attachmentLabel;
    private Uri pendingAttachment;
    private String pendingAttachmentName = "";
    private DeviceStore.Device exportingDevice;
    private String exportingSession = "";
    private ScrollView mainScroll;
    private Button newWorkspaceButton;
    private Button modelButton;
    private Button effortButton;
    private Button modeButton;
    private Button searchButton;
    private boolean webSearchEnabled;
    private TextView selectedTitle;
    private TextView selectedNote;
    private TextView selectedAddress;
    private EditText codeField;
    private EditText commandField;
    private EditText messageField;
    private TextView chatStatus;
    private TextView status;
    private TextView output;
    private Button checkButton;
    private Button pairButton;
    private Button runButton;
    private Button stopButton;
    private boolean busy;
    private boolean scanning;
    private boolean chatLoading;
    private boolean foreground;
    private boolean welcomeVisible;
    private String displayedSession = "";
    private String displayedModelSession = "";
    private String displayedModeSession = "";
    private int displayedMessagesHash;
    private final Runnable chatRefresh = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            loadMessages(false);
            main.postDelayed(this, 2500);
        }
    };
    private final Runnable workspaceRefresh = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            for (DeviceStore.Device device : devices.list()) {
                if (!secrets.token(device.url).isEmpty()) {
                    probes.execute(() -> loadWorkspaces(device));
                }
            }
            main.postDelayed(this, 10000);
        }
    };
    private int pendingAction;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        secrets = new SecretStore(this);
        client.setSecretStore(secrets);
        devices = new DeviceStore(this);
        List<DeviceStore.Device> saved = devices.list();
        for (DeviceStore.Device device : saved) {
            if (device.id.equals(devices.selectedId())) selected = device;
        }
        if (selected == null && !saved.isEmpty()) selected = saved.get(0);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        main.post(chatRefresh);
        main.postDelayed(workspaceRefresh, 10000);
        if (deviceRows != null && pendingAction == 0) {
            checkSavedDevices();
            if (Build.VERSION.SDK_INT < 37 || checkSelfPermission(LOCAL_NETWORK)
                    == PackageManager.PERMISSION_GRANTED) scanDevices(false);
        }
    }

    @Override protected void onPause() {
        foreground = false;
        main.removeCallbacks(chatRefresh);
        main.removeCallbacks(workspaceRefresh);
        super.onPause();
    }

    private void buildUi() {
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                int top = Build.VERSION.SDK_INT >= 35
                        ? insets.getInsets(WindowInsets.Type.statusBars()).top : 0;
                int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                int navigation = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
                boolean alreadyResized = ime > 0 && root.getHeight() > 0
                        && bounds.height() - root.getHeight() > ime / 2;
                int bottom = alreadyResized ? navigation : Math.max(ime, navigation);
                if (root.getPaddingTop() != top || root.getPaddingBottom() != bottom) {
                    root.setPadding(0, top, 0, bottom);
                }
                return insets;
            });
        }

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(12), dp(4), dp(12), dp(10));
        header.setBackgroundColor(Color.WHITE);
        root.addView(header);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(toolbar);
        Button navigation = button(toolbar, "☰", () -> {
            boolean open = navigationPanel.getVisibility() != View.VISIBLE;
            navigationPanel.setVisibility(open ? View.VISIBLE : View.GONE);
            if (open) mainScroll.smoothScrollTo(0, 0);
        });
        navigation.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(50)));
        navigation.setBackground(softBackground(Color.WHITE, dp(20), Color.TRANSPARENT));
        navigation.setTextColor(NAVY);
        navigation.setTextSize(17);
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.addView(brand, new LinearLayout.LayoutParams(0, dp(50), 1));
        ImageView brandMark = new ImageView(this);
        brandMark.setImageResource(R.drawable.ic_launcher_foreground);
        brand.addView(brandMark, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView brandName = new TextView(this);
        brandName.setText("deepseek");
        brandName.setTextSize(21);
        brandName.setTextColor(Color.rgb(20, 23, 29));
        brandName.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.addView(brandName);
        TextView brandBadge = new TextView(this);
        brandBadge.setText("HARNESS");
        brandBadge.setTextSize(9);
        brandBadge.setTextColor(Color.WHITE);
        brandBadge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brandBadge.setGravity(Gravity.CENTER);
        brandBadge.setPadding(dp(4), 0, dp(4), 0);
        brandBadge.setBackground(softBackground(Color.rgb(22, 24, 28), dp(2), Color.TRANSPARENT));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, dp(16));
        badgeParams.leftMargin = dp(5);
        brand.addView(brandBadge, badgeParams);
        Button newChat = button(toolbar, "＋", this::createSession);
        newChat.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(50)));
        newChat.setBackground(softBackground(Color.WHITE, dp(20), Color.TRANSPARENT));
        newChat.setTextColor(NAVY);
        newChat.setTextSize(19);

        status = new TextView(this);
        status.setTextColor(MUTED);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(4), 0, dp(2));
        status.setText("选择设备开始连接");
        header.addView(status);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(12), dp(18), dp(20));
        ScrollView scroll = new ScrollView(this);
        mainScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (welcomeVisible) updateWelcomeHeight();
        });
        scroll.addView(layout);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        navigationPanel = new LinearLayout(this);
        navigationPanel.setOrientation(LinearLayout.VERTICAL);
        navigationPanel.setPadding(dp(4), dp(8), dp(4), dp(20));
        layout.addView(navigationPanel);

        LinearLayout listHeader = new LinearLayout(this);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        navigationPanel.addView(listHeader);
        TextView listTitle = new TextView(this);
        listTitle.setText("局域网设备");
        listTitle.setTextSize(17);
        listTitle.setTextColor(NAVY);
        listTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        listHeader.addView(listTitle, new LinearLayout.LayoutParams(0, -2, 1));
        button(listHeader, "发现", () -> scanDevices(true));
        button(listHeader, "+ 添加", () -> editDevice(null));
        deviceRows = new LinearLayout(this);
        deviceRows.setOrientation(LinearLayout.VERTICAL);
        navigationPanel.addView(deviceRows);
        renderDeviceRows();

        LinearLayout workspaceHeader = new LinearLayout(this);
        workspaceHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams workspaceHeaderParams = new LinearLayout.LayoutParams(-1, -2);
        workspaceHeaderParams.topMargin = dp(20);
        navigationPanel.addView(workspaceHeader, workspaceHeaderParams);
        TextView workspaceLabel = new TextView(this);
        workspaceLabel.setText("全部 PC 工作区");
        workspaceLabel.setTextSize(17);
        workspaceLabel.setTextColor(NAVY);
        workspaceLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        workspaceHeader.addView(workspaceLabel, new LinearLayout.LayoutParams(0, -2, 1));
        newWorkspaceButton = button(workspaceHeader, "+ 新建", this::createWorkspace);
        workspaceRows = new LinearLayout(this);
        workspaceRows.setOrientation(LinearLayout.VERTICAL);
        navigationPanel.addView(workspaceRows);
        renderWorkspaces();

        LinearLayout sessionHeader = new LinearLayout(this);
        sessionHeader.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sessionHeaderParams = new LinearLayout.LayoutParams(-1, -2);
        sessionHeaderParams.topMargin = dp(20);
        navigationPanel.addView(sessionHeader, sessionHeaderParams);
        TextView sessionLabel = new TextView(this);
        sessionLabel.setText("当前工作区 · 对话");
        sessionLabel.setTextSize(17);
        sessionLabel.setTextColor(NAVY);
        sessionLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sessionHeader.addView(sessionLabel, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout sessionActions = new LinearLayout(this);
        navigationPanel.addView(sessionActions);
        button(sessionActions, "+ 新对话", this::createSession);
        button(sessionActions, "已归档", this::showArchivedSessions);
        button(sessionActions, "自动化任务", this::showTasks);
        sessionRows = new LinearLayout(this);
        sessionRows.setOrientation(LinearLayout.VERTICAL);
        navigationPanel.addView(sessionRows);
        messageRows = new LinearLayout(this);
        messageRows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams messagesParams = new LinearLayout.LayoutParams(-1, -2);
        messagesParams.topMargin = dp(10);
        layout.addView(messageRows, messagesParams);
        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setPadding(dp(8), dp(6), dp(8), dp(6));
        composer.setBackground(softBackground(Color.WHITE, dp(28), Color.rgb(233, 237, 244)));
        composer.setElevation(dp(8));
        LinearLayout.LayoutParams composerParams = new LinearLayout.LayoutParams(-1, -2);
        composerParams.setMargins(dp(12), dp(5), dp(12), dp(8));
        root.addView(composer, composerParams);
        messageField = new EditText(this);
        messageField.setHint("发消息给 DeepSeek-harness");
        messageField.setTextSize(15);
        messageField.setPadding(dp(12), dp(5), dp(12), dp(5));
        messageField.setBackground(softBackground(Color.rgb(248, 250, 253), dp(22), Color.TRANSPARENT));
        composer.addView(messageField, new LinearLayout.LayoutParams(-1, dp(44)));
        messageField.setSingleLine(true);
        messageField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        messageField.setOnFocusChangeListener((view, focused) -> {
            if (focused) mainScroll.postDelayed(() -> mainScroll.smoothScrollTo(0,
                    messageRows.getBottom()), 250);
        });
        messageField.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO || event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN) {
                sendMessage();
                return true;
            }
            return false;
        });
        attachmentLabel = new TextView(this);
        attachmentLabel.setTextColor(BLUE);
        attachmentLabel.setTextSize(12);
        attachmentLabel.setPadding(dp(10), dp(4), dp(10), dp(4));
        attachmentLabel.setVisibility(View.GONE);
        attachmentLabel.setOnClickListener(view -> {
            pendingAttachment = null;
            pendingAttachmentName = "";
            attachmentLabel.setVisibility(View.GONE);
        });
        composer.addView(attachmentLabel);
        LinearLayout modelActions = new LinearLayout(this);
        composer.addView(modelActions);
        modelButton = button(modelActions, "◇ 模型", this::showModelSelector);
        effortButton = button(modelActions, "思考等级", this::showEffortSelector);
        modeButton = button(modelActions, "模式", this::showModeSelector);
        LinearLayout composerActions = new LinearLayout(this);
        composer.addView(composerActions);
        button(composerActions, "＋", this::showComposerMenu);
        button(composerActions, "技能", this::showSkillPicker);
        searchButton = button(composerActions, "智能搜索", this::toggleSearch);
        Button sendButton = button(composerActions, "发送", this::sendMessage);
        primary(sendButton);
        compactComposerRow(modelActions);
        compactComposerRow(composerActions);
        chatStatus = new TextView(this);
        chatStatus.setTextColor(MUTED);
        chatStatus.setTextSize(12);
        chatStatus.setPadding(dp(8), dp(3), dp(8), 0);
        chatStatus.setVisibility(View.GONE);
        composer.addView(chatStatus);
        renderSessions();

        LinearLayout panel = new LinearLayout(this);
        devicePanel = panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(18), dp(18), dp(20));
        panel.setBackground(cardBackground());
        LinearLayout.LayoutParams panelParams = new LinearLayout.LayoutParams(-1, -2);
        panelParams.topMargin = dp(18);
        navigationPanel.addView(panel, panelParams);
        selectedTitle = new TextView(this);
        selectedTitle.setTextSize(21);
        selectedTitle.setTextColor(NAVY);
        selectedTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        panel.addView(selectedTitle);
        selectedNote = new TextView(this);
        selectedNote.setTextSize(13);
        selectedNote.setTextColor(MUTED);
        panel.addView(selectedNote);
        selectedAddress = new TextView(this);
        selectedAddress.setTextSize(12);
        selectedAddress.setTextColor(MUTED);
        panel.addView(selectedAddress);


        LinearLayout manage = new LinearLayout(this);
        panel.addView(manage);
        button(manage, "编辑资料", () -> editDevice(selected));
        button(manage, "删除设备", this::confirmRemove);

        codeField = field(panel, "首次连接 · PC 端 8 位配对码", "",
                InputType.TYPE_CLASS_NUMBER, false);
        commandField = field(panel, "远程命令", "", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE, true);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(actions);
        pairButton = button(actions, "配对设备", () -> start(1));
        checkButton = button(actions, "检查连接", () -> start(2));
        primary(pairButton);
        LinearLayout commandActions = new LinearLayout(this);
        panel.addView(commandActions);
        runButton = button(commandActions, "执行命令", () -> start(3));
        primary(runButton);
        stopButton = button(commandActions, "停止", () -> {
            client.cancel();
            setStatus("正在停止…");
        });
        stopButton.setEnabled(false);

        output = new TextView(this);
        output.setTextIsSelectable(true);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setTextSize(14);
        panel.addView(output);
        renderSelected();
        navigationPanel.setVisibility(View.GONE);
    }

    private EditText field(LinearLayout parent, String label, String value,
                           int inputType, boolean multiline) {
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setPadding(0, dp(14), 0, 0);
        parent.addView(caption);
        EditText edit = new EditText(this);
        edit.setSingleLine(!multiline);
        edit.setInputType(inputType);
        edit.setText(value);
        parent.addView(edit, new LinearLayout.LayoutParams(-1,
                multiline ? dp(100) : -2));
        return edit;
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setTextColor(BLUE);
        button.setBackground(softBackground(Color.rgb(240, 245, 255), dp(18),
                Color.rgb(227, 235, 250)));
        button.setElevation(0);
        button.setStateListAnimator(null);
        LinearLayout.LayoutParams params = parent.getOrientation() == LinearLayout.VERTICAL
                ? new LinearLayout.LayoutParams(-1, dp(46))
                : new LinearLayout.LayoutParams(0, dp(46), 1);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        parent.addView(button, params);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void compactComposerRow(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            params.height = dp(36);
            params.setMargins(dp(1), dp(1), dp(1), dp(1));
            child.setLayoutParams(params);
        }
    }

    private void primary(Button button) {
        button.setTextColor(Color.WHITE);
        button.setBackground(softBackground(BLUE, dp(18), Color.TRANSPARENT));
    }

    private GradientDrawable cardBackground() {
        return softBackground(Color.WHITE, dp(25), Color.rgb(237, 240, 246));
    }

    private GradientDrawable softBackground(int fill, int radius, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radius);
        if (stroke != Color.TRANSPARENT) shape.setStroke(dp(1), stroke);
        return shape;
    }

    private void renderDeviceRows() {
        deviceRows.removeAllViews();
        List<DeviceStore.Device> saved = devices.list();
        if (saved.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("尚未添加电脑\n点击“发现”查找同一局域网的 PC");
            empty.setTextSize(14);
            empty.setTextColor(MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(28), dp(12), dp(28));
            empty.setBackground(cardBackground());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(10);
            deviceRows.addView(empty, params);
        }
        for (DeviceStore.Device device : saved) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackground(cardBackground());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(10);
            deviceRows.addView(row, params);
            TextView dot = new TextView(this);
            dot.setText("●");
            dot.setTextSize(18);
            dot.setTextColor("在线".equals(deviceStatus.get(device.id))
                    ? Color.rgb(33, 164, 108) : Color.rgb(176, 185, 203));
            row.addView(dot);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(12), 0, dp(8), 0);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            TextView name = new TextView(this);
            name.setText(device.name);
            name.setTextSize(16);
            name.setTextColor(NAVY);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            labels.addView(name);
            TextView note = new TextView(this);
            note.setText(device.note.isEmpty() ? device.url : device.note);
            note.setTextSize(12);
            note.setTextColor(MUTED);
            labels.addView(note);
            TextView state = new TextView(this);
            state.setText(deviceStatus.getOrDefault(device.id, "未检查"));
            state.setTextSize(12);
            state.setTextColor(MUTED);
            row.addView(state);
            row.setOnClickListener(view -> selectDevice(device));
        }
    }

    private void selectDevice(DeviceStore.Device device) {
        if (busy) return;
        selected = device;
        devices.select(device.id);
        renderSelected();
        renderWorkspaces();
        renderSessions();
        setStatus("已选择 " + device.name);
        if (!secrets.token(device.url).isEmpty()) checkDevice(device);
    }

    private void renderSelected() {
        boolean exists = selected != null;
        devicePanel.setVisibility(exists ? View.VISIBLE : View.GONE);
        newWorkspaceButton.setVisibility(exists ? View.VISIBLE : View.GONE);
        newWorkspaceButton.setEnabled(exists && !busy);
        selectedTitle.setText(exists ? selected.name : "暂无设备");
        selectedNote.setText(exists ? selected.note : "点击上方添加设备");
        selectedAddress.setText(exists ? selected.url : "");
        pairButton.setEnabled(exists && !busy);
        checkButton.setEnabled(exists && !busy);
        runButton.setEnabled(exists && !busy);
    }

    private void renderWorkspaces() {
        workspaceRows.removeAllViews();
        for (DeviceStore.Device device : devices.list()) {
            List<RemoteClient.Workspace> items = workspaceCache.get(device.id);
            if (items == null) continue;
            for (RemoteClient.Workspace item : items) {
                LinearLayout card = new LinearLayout(this);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setBackground(cardBackground());
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                params.topMargin = dp(7);
                workspaceRows.addView(card, params);
                TextView row = new TextView(this);
                boolean current = selected != null && selected.id.equals(device.id)
                        && item.id.equals(devices.selectedWorkspace(device.id));
                String title = "default-workspace".equals(item.title)
                        ? "默认工作区" : item.title;
                row.setText((current ? "✓  " : "▢  ") + title + "  ·  " + device.name
                        + "\n" + item.path);
                row.setTextSize(14);
                row.setTextColor(current ? BLUE : NAVY);
                row.setPadding(dp(16), dp(13), dp(8), dp(13));
                card.addView(row, new LinearLayout.LayoutParams(0, -2, 1));
                row.setOnClickListener(view -> {
                    selectDevice(device);
                    devices.selectWorkspace(device.id, item.id);
                    renderWorkspaces();
                    renderSessions();
                    loadSessions(device, item.id);
                    setStatus("已选择 " + device.name + " / " + item.title);
                    navigationPanel.setVisibility(View.GONE);
                    mainScroll.smoothScrollTo(0, 0);
                });
                Button delete = new Button(this);
                delete.setText("删除");
                delete.setAllCaps(false);
                delete.setTextSize(13);
                delete.setTextColor(Color.rgb(185, 47, 57));
                card.addView(delete, new LinearLayout.LayoutParams(dp(72), dp(48)));
                delete.setOnClickListener(view -> confirmDeleteWorkspace(device, item));
            }
        }
        if (workspaceRows.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText("连接设备后，将在这里显示所有 PC 的工作区");
            empty.setTextColor(MUTED);
            empty.setPadding(0, dp(10), 0, 0);
            workspaceRows.addView(empty);
        }
    }

    private void confirmDeleteWorkspace(DeviceStore.Device device,
                                        RemoteClient.Workspace workspace) {
        String name = "default-workspace".equals(workspace.title)
                ? "默认工作区" : workspace.title;
        new AlertDialog.Builder(this).setTitle("删除工作区？")
                .setMessage("从 " + device.name + " 的 dsh 列表移除“" + name + "”？\n\n"
                        + "电脑上的文件夹和历史会话不会删除。\n" + workspace.path)
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> deleteWorkspace(device, workspace))
                .show();
    }

    private void deleteWorkspace(DeviceStore.Device device,
                                 RemoteClient.Workspace workspace) {
        String token = secrets.token(device.url);
        if (token.isEmpty()) { setStatus("请先配对 " + device.name); return; }
        setStatus("正在删除工作区 " + workspace.title + "…");
        worker.execute(() -> {
            try {
                client.deleteWorkspace(device.url, token, workspace.id);
                main.post(() -> {
                    List<RemoteClient.Workspace> current = workspaceCache.get(device.id);
                    if (current != null) {
                        List<RemoteClient.Workspace> remaining = new ArrayList<>(current);
                        remaining.removeIf(item -> item.id.equals(workspace.id));
                        workspaceCache.put(device.id, remaining);
                    }
                    sessionCache.remove(device.id + ":" + workspace.id);
                    devices.selectSession(device.id, workspace.id, "");
                    if (workspace.id.equals(devices.selectedWorkspace(device.id))) {
                        devices.selectWorkspace(device.id, "");
                    }
                    renderWorkspaces();
                    renderSessions();
                    setStatus("已从 " + device.name + " 移除工作区，文件夹和会话已保留");
                });
                loadWorkspaces(device);
            } catch (Exception error) {
                main.post(() -> setStatus("删除工作区失败：" + error.getMessage()));
            }
        });
    }

    private String currentWorkspace() {
        return selected == null ? "" : devices.selectedWorkspace(selected.id);
    }

    private void renderSessions() {
        if (sessionRows == null) return;
        sessionRows.removeAllViews();
        String active = selected == null ? "" : selected.id + ":" + currentWorkspace()
                + ":" + devices.selectedSession(selected.id, currentWorkspace());
        if (modelButton != null && !active.equals(displayedModelSession)) {
            displayedModelSession = active;
            modelButton.setText("◇ 模型");
            modelButton.setEnabled(selected != null && !currentWorkspace().isEmpty());
            effortButton.setText("思考等级");
            effortButton.setEnabled(selected != null && !currentWorkspace().isEmpty());
            if (selected != null && !currentWorkspace().isEmpty()) {
                loadModelLabel(selected,
                        devices.selectedSession(selected.id, currentWorkspace()), active);
            }
        }
        if (modeButton != null && !active.equals(displayedModeSession)) {
            displayedModeSession = active;
            modeButton.setText("模式");
            modeButton.setEnabled(selected != null && !currentWorkspace().isEmpty());
            if (selected != null && !currentWorkspace().isEmpty()) {
                loadModeLabel(selected,
                        devices.selectedSession(selected.id, currentWorkspace()), active);
            }
        }
        if (!active.equals(displayedSession)) {
            displayedSession = active;
            displayedMessagesHash = 0;
            messageRows.removeAllViews();
        }
        if (selected == null || currentWorkspace().isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText("先选择一台电脑和工作区");
            sessionRows.addView(hint);
            showWelcome("探索未至之境");
            return;
        }
        List<RemoteClient.Session> items = sessionCache.get(selected.id + ":" + currentWorkspace());
        boolean hasActive = false;
        if (items != null) for (RemoteClient.Session item : items) {
            if (!item.archived) { hasActive = true; break; }
        }
        if (!hasActive) {
            TextView hint = new TextView(this);
            hint.setText("暂无对话，点击“新对话”开始");
            hint.setPadding(0, dp(10), 0, dp(10));
            sessionRows.addView(hint);
            showWelcome("探索未至之境");
        } else for (RemoteClient.Session item : items) {
            if (item.archived) continue;
            TextView row = new TextView(this);
            boolean current = item.id.equals(devices.selectedSession(selected.id, currentWorkspace()));
            RemoteClient.Workspace workspace = selectedWorkspaceDetails();
            row.setText((current ? "✓ " : "• ") + item.title
                    + (workspace == null ? "" : "\n目录：" + workspace.path));
            row.setTextColor(current ? BLUE : NAVY);
            row.setTextSize(15);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setBackground(cardBackground());
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.topMargin = dp(6);
            sessionRows.addView(row, params);
            row.setOnClickListener(view -> {
                devices.selectSession(selected.id, currentWorkspace(), item.id);
                renderSessions();
                loadMessages(true);
                navigationPanel.setVisibility(View.GONE);
                mainScroll.smoothScrollTo(0, 0);
            });
            row.setOnLongClickListener(view -> {
                if (workspace != null) showSessionDetails(selected, workspace, item);
                return true;
            });
        }
    }

    private void showWelcome(String greeting) {
        welcomeVisible = true;
        messageRows.removeAllViews();
        messageRows.setGravity(Gravity.CENTER);
        updateWelcomeHeight();
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(dp(8), dp(8), dp(8), dp(8));
        ImageView logo = new ImageView(this);
        logo.setImageResource(com.example.dsh_apk.R.mipmap.ic_launcher);
        hero.addView(logo, new LinearLayout.LayoutParams(dp(58), dp(58)));
        TextView title = new TextView(this);
        title.setText(greeting);
        title.setTextColor(NAVY);
        title.setTextSize(23);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(20), 0, 0);
        hero.addView(title);
        messageRows.addView(hero, new LinearLayout.LayoutParams(-1, -2));
    }

    private void updateWelcomeHeight() {
        if (messageRows != null && mainScroll != null) {
            messageRows.setMinimumHeight(Math.max(dp(240), mainScroll.getHeight() - dp(32)));
        }
    }

    private void loadModelLabel(DeviceStore.Device device, String sessionId, String active) {
        String token = secrets.token(device.url);
        if (token.isEmpty()) return;
        probes.execute(() -> {
            try {
                RemoteClient.ModelCatalog catalog = client.models(device.url, token, sessionId);
                main.post(() -> {
                    if (active.equals(displayedModelSession) && modelButton != null) {
                        updateModelControls(catalog);
                    }
                });
            } catch (Exception ignored) { }
        });
    }

    private void loadModeLabel(DeviceStore.Device device, String sessionId, String active) {
        if (sessionId.isEmpty()) return;
        String token = secrets.token(device.url);
        if (token.isEmpty()) return;
        probes.execute(() -> {
            try {
                RemoteClient.Mode mode = client.mode(device.url, token, sessionId);
                main.post(() -> {
                    if (active.equals(displayedModeSession) && modeButton != null
                            && !mode.name.isEmpty())
                        modeButton.setContentDescription("模式，当前：" + mode.name);
                });
            } catch (Exception ignored) { }
        });
    }

    private void updateModelControls(RemoteClient.ModelCatalog catalog) {
        modelButton.setText("◇ 模型");
        RemoteClient.ModelChoice current = null;
        for (RemoteClient.ModelChoice choice : catalog.choices) {
            if (choice.provider.equals(catalog.selectedProvider)
                    && choice.id.equals(catalog.selectedModel)) { current = choice; break; }
        }
        String effort = catalog.selectedEffort;
        if (effort.isEmpty() && current != null) effort = current.defaultEffort;
        String label = effort;
        if (current != null) for (int i = 0; i < current.effortIds.size(); i++) {
            if (current.effortIds.get(i).equals(effort)) {
                label = current.effortNames.get(i); break;
            }
        }
        effortButton.setText(label.isEmpty() ? "思考等级" : "思考 · " + label);
        effortButton.setEnabled(current != null && !current.effortIds.isEmpty());
    }

    private void showModelSelector() {
        if (selected == null || currentWorkspace().isEmpty()) {
            setChatStatus("请先选择工作区"); return;
        }
        DeviceStore.Device device = selected;
        String workspaceId = currentWorkspace();
        String sessionId = devices.selectedSession(device.id, workspaceId);
        String token = secrets.token(device.url);
        if (token.isEmpty()) { setChatStatus("请先配对设备"); return; }
        setChatStatus("正在读取 PC 上的模型…");
        worker.execute(() -> {
            try {
                RemoteClient.ModelCatalog catalog = client.models(device.url, token, sessionId);
                main.post(() -> {
                    if (selected == null || !selected.id.equals(device.id)
                            || !currentWorkspace().equals(workspaceId)) return;
                    if (catalog.choices.isEmpty()) {
                        setChatStatus(catalog.failure.isEmpty()
                                ? "PC 上没有可选模型，请先在 DeepSeek-harness 中配置模型"
                                : "读取模型失败：" + catalog.failure);
                        return;
                    }
                    String[] labels = new String[catalog.choices.size()];
                    for (int i = 0; i < catalog.choices.size(); i++) {
                        RemoteClient.ModelChoice choice = catalog.choices.get(i);
                        boolean current = choice.provider.equals(catalog.selectedProvider)
                                && choice.id.equals(catalog.selectedModel);
                        labels[i] = (current ? "✓ " : "") + choice.providerName
                                + " · " + choice.name;
                    }
                    setChatStatus("当前模型：" + catalog.selectedName());
                    new AlertDialog.Builder(this).setTitle("选择对话模型")
                            .setItems(labels, (dialog, which) -> chooseModelEffort(device,
                                    workspaceId, sessionId, catalog.choices.get(which)))
                            .setNegativeButton("取消", null).show();
                });
            } catch (Exception error) {
                main.post(() -> setChatStatus("读取模型失败：" + error.getMessage()));
            }
        });
    }

    private void showEffortSelector() {
        if (selected == null || currentWorkspace().isEmpty()) return;
        DeviceStore.Device device = selected;
        String workspaceId = currentWorkspace();
        String sessionId = devices.selectedSession(device.id, workspaceId);
        if (sessionId.isEmpty()) { showModelSelector(); return; }
        worker.execute(() -> {
            try {
                RemoteClient.ModelCatalog catalog = client.models(device.url,
                        secrets.token(device.url), sessionId);
                main.post(() -> {
                    for (RemoteClient.ModelChoice choice : catalog.choices) {
                        if (choice.provider.equals(catalog.selectedProvider)
                                && choice.id.equals(catalog.selectedModel)) {
                            chooseModelEffort(device, workspaceId, sessionId, choice);
                            return;
                        }
                    }
                    showModelSelector();
                });
            } catch (Exception error) {
                main.post(() -> setChatStatus("读取思考等级失败：" + error.getMessage()));
            }
        });
    }

    private void showSkillPicker() {
        if (selected == null || currentWorkspace().isEmpty()) {
            setChatStatus("请先选择工作区"); return;
        }
        DeviceStore.Device device = selected;
        String workspaceId = currentWorkspace();
        String token = secrets.token(device.url);
        if (token.isEmpty()) { setChatStatus("请先配对设备"); return; }
        setChatStatus("正在读取 PC 上可用的技能…");
        worker.execute(() -> {
            try {
                String sessionId = devices.selectedSession(device.id, workspaceId);
                if (sessionId.isEmpty()) {
                    sessionId = client.createSession(device.url, token, workspaceId).id;
                    devices.selectSession(device.id, workspaceId, sessionId);
                    loadSessions(device, workspaceId);
                }
                List<RemoteClient.Skill> skills = client.skills(device.url, token, sessionId);
                main.post(() -> {
                    if (selected == null || !selected.id.equals(device.id)
                            || !currentWorkspace().equals(workspaceId)) return;
                    if (skills.isEmpty()) {
                        setChatStatus("没有可选技能；也可以直接输入 /技能名称");
                        return;
                    }
                    showSkillMenu(skills);
                });
            } catch (Exception error) {
                main.post(() -> setChatStatus("读取技能失败：" + error.getMessage()
                        + "；仍可手动输入 /技能名称"));
            }
        });
    }

    private void showComposerMenu() {
        LinearLayout menu = menuBody();
        menuHeading(menu, "添加");
        AlertDialog dialog = new AlertDialog.Builder(this).setView(menu).create();
        menuRow(menu, "◉  文件", "从手机选择文件并发送给 PC 对话",
                () -> { dialog.dismiss(); pickAttachment(); });
        menuRow(menu, "◎  目标", "查看或管理当前会话目标",
                () -> { dialog.dismiss(); showGoalMenu(); });
        menuRow(menu, "▤  计划", "进入或退出计划模式",
                () -> { dialog.dismiss(); showPlanMenu(); });
        menuRow(menu, "◇  反馈", "向 DeepSeek-harness 提交当前会话反馈",
                () -> { dialog.dismiss(); showTextCommandDialog("反馈", "/feedback ", "写下反馈内容"); });
        menuHeading(menu, "指令");
        menuRow(menu, "♧  权限", "切换当前会话的权限预设",
                () -> { dialog.dismiss(); showPermissionMenu(); });
        menuRow(menu, "◈  模型", "选择当前会话使用的模型",
                () -> { dialog.dismiss(); showModelSelector(); });
        menuRow(menu, "⌕  搜索", "查找当前对话中的消息",
                () -> { dialog.dismiss(); showConversationSearch(); });
        menuRow(menu, "↓  下载日志", "保存当前会话的日志",
                () -> { dialog.dismiss(); exportLog(); });
        showRoundedMenu(dialog);
    }

    private LinearLayout menuBody() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(14), dp(16), dp(14));
        body.setBackground(softBackground(Color.WHITE, dp(26), Color.rgb(238, 241, 246)));
        return body;
    }

    private void menuHeading(LinearLayout menu, String title) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(MUTED);
        label.setTextSize(12);
        label.setPadding(dp(10), dp(10), 0, dp(4));
        menu.addView(label);
    }

    private void menuRow(LinearLayout menu, String title, String description, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.setBackground(softBackground(Color.rgb(249, 250, 252), dp(15), Color.TRANSPARENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(4);
        menu.addView(row, params);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(NAVY);
        name.setTextSize(15);
        row.addView(name);
        if (!description.isEmpty()) {
            TextView note = new TextView(this);
            note.setText(description);
            note.setSingleLine(true);
            note.setEllipsize(android.text.TextUtils.TruncateAt.END);
            note.setTextColor(MUTED);
            note.setTextSize(11);
            note.setPadding(dp(22), dp(2), 0, 0);
            row.addView(note);
        }
        row.setOnClickListener(view -> action.run());
    }

    private void showRoundedMenu(AlertDialog dialog) {
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels
                    - dp(32), -2);
        }
    }

    private void showSkillMenu(List<RemoteClient.Skill> skills) {
        LinearLayout body = menuBody();
        menuHeading(body, "技能 · " + skills.size());
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索技能");
        search.setTextSize(14);
        search.setPadding(dp(14), dp(8), dp(14), dp(8));
        search.setBackground(softBackground(Color.rgb(246, 248, 252), dp(16), Color.TRANSPARENT));
        body.addView(search, new LinearLayout.LayoutParams(-1, dp(46)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, dp(380)));
        AlertDialog dialog = new AlertDialog.Builder(this).setView(body).create();
        Runnable render = () -> {
            rows.removeAllViews();
            String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            for (RemoteClient.Skill skill : skills) {
                if (!skill.name.toLowerCase(java.util.Locale.ROOT).contains(query)
                        && !skill.description.toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
                String summary = skill.description.replaceAll("\\s+", " ").trim();
                if (summary.length() > 65) summary = summary.substring(0, 65) + "…";
                menuRow(rows, "/" + skill.name, summary, () -> {
                    String draft = messageField.getText().toString().trim();
                    messageField.setText("/" + skill.name + " " + draft);
                    messageField.setSelection(messageField.length());
                    dialog.dismiss();
                    setChatStatus("已选技能；输入要求后发送");
                });
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                render.run();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        render.run();
        showRoundedMenu(dialog);
    }

    private void showGoalMenu() {
        String[] choices = { "查看当前目标", "新建目标", "编辑目标", "暂停目标", "继续目标", "清除目标" };
        new AlertDialog.Builder(this).setTitle("目标")
                .setItems(choices, (dialog, choice) -> {
                    switch (choice) {
                        case 0: runNativeCommand("/goal"); break;
                        case 1: showTextCommandDialog("新建目标", "/goal ", "描述完成目标"); break;
                        case 2: showTextCommandDialog("编辑目标", "/goal edit ", "新的目标内容"); break;
                        case 3: runNativeCommand("/goal pause"); break;
                        case 4: runNativeCommand("/goal resume"); break;
                        default: new AlertDialog.Builder(this).setTitle("清除当前目标？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("清除", (d, w) -> runNativeCommand("/goal clear"))
                                .show();
                    }
                }).show();
    }

    private void showPlanMenu() {
        new AlertDialog.Builder(this).setTitle("计划模式")
                .setItems(new String[]{ "进入计划模式", "退出计划模式" }, (dialog, which) -> {
                    if (which == 0) showTextCommandDialog("计划任务", "/plan ", "描述需要规划的任务");
                    else runNativeCommand("/plan off");
                }).show();
    }

    private void showPermissionMenu() {
        String[] names = { "查看当前权限", "仅可查看", "工作区内修改", "完全权限" };
        String[] commands = { "/permission", "/permission read-only",
                "/permission workspace-write", "/permission danger-full-access" };
        new AlertDialog.Builder(this).setTitle("权限预设")
                .setItems(names, (dialog, which) -> {
                    if (which != 3) { runNativeCommand(commands[which]); return; }
                    new AlertDialog.Builder(this).setTitle("切换到完全权限？")
                            .setMessage("此模式允许 DeepSeek-harness 在电脑上执行更广泛的操作。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("切换", (d, w) -> runNativeCommand(commands[which]))
                            .show();
                }).show();
    }

    private void showTextCommandDialog(String title, String prefix, String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setMinLines(2);
        input.setPadding(dp(20), dp(12), dp(20), dp(12));
        new AlertDialog.Builder(this).setTitle(title).setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) runNativeCommand(prefix + value);
                }).show();
    }

    private void runNativeCommand(String line) {
        if (selected == null || currentWorkspace().isEmpty()) {
            setChatStatus("请先选择电脑和工作区"); return;
        }
        DeviceStore.Device device = selected;
        String workspaceId = currentWorkspace();
        String token = secrets.token(device.url);
        if (token.isEmpty()) { setChatStatus("请先配对设备"); return; }
        String sessionId = devices.selectedSession(device.id, workspaceId);
        setChatStatus("正在执行 PC 原生命令…");
        worker.execute(() -> {
            try {
                String target = sessionId;
                if (target.isEmpty()) {
                    target = client.createSession(device.url, token, workspaceId).id;
                    devices.selectSession(device.id, workspaceId, target);
                    loadSessions(device, workspaceId);
                }
                String result = client.command(device.url, token, target, line);
                main.post(() -> {
                    renderSessions();
                    setChatStatus("命令已执行");
                    new AlertDialog.Builder(this).setTitle("DeepSeek-harness")
                            .setMessage(result.isEmpty() ? "已完成" : result)
                            .setPositiveButton("知道了", null).show();
                    loadMessages(false);
                });
            } catch (Exception error) {
                main.post(() -> setChatStatus("命令失败：" + error.getMessage()));
            }
        });
    }

    private void toggleSearch() {
        webSearchEnabled = !webSearchEnabled;
        searchButton.setText(webSearchEnabled ? "✓ 智能搜索" : "智能搜索");
        searchButton.setTextColor(webSearchEnabled ? BLUE : NAVY);
        setChatStatus(webSearchEnabled ? "发送时会先通过 PC 联网查找资料" : "已关闭智能搜索");
    }

    private void showConversationSearch() {
        if (selected == null || currentWorkspace().isEmpty()) {
            setChatStatus("请先选择会话"); return;
        }
        DeviceStore.Device device = selected;
        String sessionId = devices.selectedSession(device.id, currentWorkspace());
        if (sessionId.isEmpty()) { setChatStatus("当前没有可搜索的对话"); return; }
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint("搜索当前对话");
        query.setPadding(dp(20), dp(8), dp(20), dp(8));
        new AlertDialog.Builder(this).setTitle("搜索对话").setView(query)
                .setNegativeButton("取消", null)
                .setPositiveButton("搜索", (dialog, which) -> {
                    String term = query.getText().toString().trim();
                    if (term.isEmpty()) return;
                    setChatStatus("正在搜索对话…");
                    worker.execute(() -> {
                        try {
                            List<RemoteClient.Message> all = client.messages(device.url,
                                    secrets.token(device.url), sessionId);
                            List<RemoteClient.Message> found = new ArrayList<>();
                            for (RemoteClient.Message item : all) {
                                if (item.text.toLowerCase(java.util.Locale.ROOT).contains(
                                        term.toLowerCase(java.util.Locale.ROOT))) found.add(item);
                            }
                            main.post(() -> {
                                setChatStatus("找到 " + found.size() + " 条消息");
                                if (found.isEmpty()) return;
                                String[] labels = new String[found.size()];
                                for (int i = 0; i < found.size(); i++) {
                                    String text = found.get(i).text.replace('\n', ' ');
                                    labels[i] = ("user".equals(found.get(i).role) ? "我 · " : "DeepSeek-harness · ")
                                            + (text.length() > 65 ? text.substring(0, 65) + "…" : text);
                                }
                                new AlertDialog.Builder(this).setTitle("搜索结果")
                                        .setItems(labels, (d, index) -> new AlertDialog.Builder(this)
                                                .setMessage(MarkdownFormatter.render(found.get(index).text))
                                                .setPositiveButton("关闭", null).show())
                                        .show();
                            });
                        } catch (Exception error) {
                            main.post(() -> setChatStatus("搜索失败：" + error.getMessage()));
                        }
                    });
                }).show();
    }

    private void pickAttachment() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_ATTACHMENT);
    }

    private void exportLog() {
        if (selected == null || currentWorkspace().isEmpty()) {
            setChatStatus("请先选择会话"); return;
        }
        String session = devices.selectedSession(selected.id, currentWorkspace());
        if (session.isEmpty()) { setChatStatus("当前没有可导出的会话"); return; }
        exportingDevice = selected;
        exportingSession = session;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-ndjson");
        intent.putExtra(Intent.EXTRA_TITLE, "DeepSeek-harness-session-" + session + ".jsonl");
        startActivityForResult(intent, SAVE_LOG);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SAVE_LOG) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null
                    && exportingDevice != null && !exportingSession.isEmpty()) {
                Uri destination = data.getData();
                DeviceStore.Device device = exportingDevice;
                String session = exportingSession;
                setChatStatus("正在保存会话日志…");
                worker.execute(() -> {
                    try (java.io.OutputStream output = getContentResolver().openOutputStream(destination)) {
                        if (output == null) throw new java.io.IOException("无法写入所选位置");
                        client.downloadLog(device.url, secrets.token(device.url), session, output);
                        main.post(() -> setChatStatus("会话日志已保存"));
                    } catch (Exception error) {
                        main.post(() -> setChatStatus("保存日志失败：" + error.getMessage()));
                    }
                });
            }
            exportingDevice = null;
            exportingSession = "";
            return;
        }
        if (requestCode != PICK_ATTACHMENT || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String name = "附件";
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE },
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0) name = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)
                        && cursor.getLong(sizeColumn) > MAX_ATTACHMENT_BYTES) {
                    setChatStatus("附件最大支持 8 MB"); return;
                }
            }
        } catch (Exception error) {
            setChatStatus("读取附件失败：" + error.getMessage()); return;
        }
        pendingAttachment = uri;
        pendingAttachmentName = name == null ? "附件" : name;
        attachmentLabel.setText("📎 " + pendingAttachmentName + "    × 移除");
        attachmentLabel.setVisibility(View.VISIBLE);
        setChatStatus("附件已选择，发送时上传到当前 PC 会话");
    }

    private String encodeAttachment(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new java.io.IOException("无法打开附件");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_ATTACHMENT_BYTES)
                    throw new java.io.IOException("附件最大支持 8 MB");
                output.write(buffer, 0, count);
            }
            return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
        }
    }

    private void showModeSelector() {
        if (selected == null || currentWorkspace().isEmpty()) {
            setChatStatus("请先选择工作区"); return;
        }
        DeviceStore.Device device = selected;
        String workspaceId = currentWorkspace();
        String token = secrets.token(device.url);
        if (token.isEmpty()) { setChatStatus("请先配对设备"); return; }
        setChatStatus("正在读取 PC 工作模式…");
        worker.execute(() -> {
            try {
                List<RemoteClient.Mode> modes = client.modes(device.url, token);
                String sessionId = devices.selectedSession(device.id, workspaceId);
                String currentId = sessionId.isEmpty() ? ""
                        : client.mode(device.url, token, sessionId).id;
                main.post(() -> {
                    if (selected == null || !selected.id.equals(device.id)
                            || !currentWorkspace().equals(workspaceId)) return;
                    if (modes.isEmpty()) { setChatStatus("PC 上没有可用的工作模式"); return; }
                    String[] labels = new String[modes.size()];
                    for (int i = 0; i < modes.size(); i++) {
                        RemoteClient.Mode mode = modes.get(i);
                        labels[i] = (mode.id.equals(currentId) ? "✓  " : "    ") + mode.name;
                    }
                    new AlertDialog.Builder(this).setTitle("选择工作模式")
                            .setItems(labels, (dialog, which) -> createSessionWithMode(
                                    device, workspaceId, modes.get(which)))
                            .setNegativeButton("取消", null).show();
                });
            } catch (Exception error) {
                main.post(() -> setChatStatus("读取工作模式失败：" + error.getMessage()));
            }
        });
    }

    private void createSessionWithMode(DeviceStore.Device device, String workspaceId,
                                       RemoteClient.Mode mode) {
        setChatStatus("正在创建 " + mode.name + " 对话…");
        worker.execute(() -> {
            try {
                RemoteClient.Session created = client.createSession(device.url,
                        secrets.token(device.url), workspaceId, mode.id);
                devices.selectSession(device.id, workspaceId, created.id);
                loadSessions(device, workspaceId);
                main.post(() -> {
                    displayedModeSession = "";
                    renderSessions();
                    setChatStatus("已创建 " + mode.name + " 对话");
                });
            } catch (Exception error) {
                main.post(() -> setChatStatus("创建工作模式对话失败：" + error.getMessage()));
            }
        });
    }

    private void showTasks() {
        if (selected == null || currentWorkspace().isEmpty()) {
            setChatStatus("请先选择工作区和对话"); return;
        }
        DeviceStore.Device device = selected;
        String sessionId = devices.selectedSession(device.id, currentWorkspace());
        if (sessionId.isEmpty()) { setChatStatus("请先选择对话"); return; }
        worker.execute(() -> {
            try {
                List<RemoteClient.Task> tasks = client.tasks(device.url,
                        secrets.token(device.url), sessionId);
                main.post(() -> {
                    String[] labels = new String[tasks.size() + 1];
                    labels[0] = "+ 新建自动化任务";
                    for (int i = 0; i < tasks.size(); i++) {
                        RemoteClient.Task task = tasks.get(i);
                        labels[i + 1] = task.title + " · " + task.status
                                + " · " + task.scheduledAt;
                    }
                    new AlertDialog.Builder(this).setTitle("自动化任务")
                            .setItems(labels, (dialog, which) -> {
                                if (which == 0) showCreateTask(device, sessionId);
                                else showTaskDetails(device, sessionId, tasks.get(which - 1));
                            }).setNegativeButton("关闭", null).show();
                });
            } catch (Exception error) {
                main.post(() -> setChatStatus("读取自动化任务失败：" + error.getMessage()));
            }
        });
    }

    private void showTaskDetails(DeviceStore.Device device, String sessionId,
                                 RemoteClient.Task task) {
        new AlertDialog.Builder(this).setTitle(task.title)
                .setMessage("状态：" + task.status + "\n下次执行：" + task.scheduledAt)
                .setPositiveButton("删除任务", (dialog, which) -> worker.execute(() -> {
                    try {
                        client.deleteTask(device.url, secrets.token(device.url), sessionId, task.id);
                        main.post(() -> setChatStatus("任务已删除"));
                    } catch (Exception error) {
                        main.post(() -> setChatStatus("删除任务失败：" + error.getMessage()));
                    }
                })).setNegativeButton("关闭", null).show();
    }

    private void showCreateTask(DeviceStore.Device device, String sessionId) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);
        EditText title = field(form, "任务名称", "", InputType.TYPE_CLASS_TEXT, false);
        EditText prompt = field(form, "执行内容", "", InputType.TYPE_CLASS_TEXT, true);
        EditText delay = field(form, "间隔（分钟，至少 1）", "60",
                InputType.TYPE_CLASS_NUMBER, false);
        new AlertDialog.Builder(this).setTitle("新建自动化任务")
                .setView(form)
                .setPositiveButton("仅执行一次", (dialog, which) -> saveTask(device, sessionId,
                        title.getText().toString(), prompt.getText().toString(),
                        delay.getText().toString(), false))
                .setNeutralButton("定期重复", (dialog, which) -> saveTask(device, sessionId,
                        title.getText().toString(), prompt.getText().toString(),
                        delay.getText().toString(), true))
                .setNegativeButton("取消", null).show();
    }

    private void saveTask(DeviceStore.Device device, String sessionId, String title,
                          String prompt, String minutesText, boolean repeat) {
        int minutes;
        try { minutes = Integer.parseInt(minutesText.trim()); }
        catch (NumberFormatException error) { setChatStatus("请输入分钟数"); return; }
        if (title.trim().isEmpty() || prompt.trim().isEmpty() || minutes < 1
                || minutes > 525600) { setChatStatus("请填写任务名称、内容和有效分钟数"); return; }
        worker.execute(() -> {
            try {
                client.createTask(device.url, secrets.token(device.url), sessionId,
                        title.trim(), prompt.trim(), minutes, repeat);
                main.post(() -> setChatStatus("自动化任务已创建"));
            } catch (Exception error) {
                main.post(() -> setChatStatus("创建任务失败：" + error.getMessage()));
            }
        });
    }

    private void chooseModelEffort(DeviceStore.Device device, String workspaceId,
                                   String sessionId, RemoteClient.ModelChoice choice) {
        if (choice.effortIds.isEmpty()) {
            selectModel(device, workspaceId, sessionId, choice, "");
            return;
        }
        String[] labels = choice.effortNames.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(choice.name + " · 推理等级")
                .setItems(labels, (dialog, which) -> selectModel(device, workspaceId,
                        sessionId, choice, choice.effortIds.get(which)))
                .setNegativeButton("取消", null).show();
    }

    private void selectModel(DeviceStore.Device device, String workspaceId,
                             String sessionId, RemoteClient.ModelChoice choice, String effort) {
        setChatStatus("正在切换到 " + choice.name + "…");
        worker.execute(() -> {
            try {
                String targetSession = sessionId;
                if (targetSession.isEmpty()) {
                    targetSession = client.createSession(device.url,
                            secrets.token(device.url), workspaceId).id;
                    devices.selectSession(device.id, workspaceId, targetSession);
                }
                client.selectModel(device.url, secrets.token(device.url), targetSession,
                        choice, effort);
                String selectedSession = targetSession;
                main.post(() -> {
                    if (selected != null && selected.id.equals(device.id)
                            && currentWorkspace().equals(workspaceId)) {
                        devices.selectSession(device.id, workspaceId, selectedSession);
                        displayedModelSession = "";
                        renderSessions();
                        modelButton.setText("◇ 模型");
                        effortButton.setText(effort.isEmpty() ? "思考等级" : "思考 · " + effort);
                        setChatStatus("已选择 " + choice.providerName + " · " + choice.name
                                + (effort.isEmpty() ? "" : " · " + effort));
                        loadSessions(device, workspaceId);
                    }
                });
            } catch (Exception error) {
                main.post(() -> setChatStatus("切换模型失败：" + error.getMessage()));
            }
        });
    }

    private RemoteClient.Workspace selectedWorkspaceDetails() {
        if (selected == null) return null;
        List<RemoteClient.Workspace> items = workspaceCache.get(selected.id);
        if (items == null) return null;
        for (RemoteClient.Workspace item : items) {
            if (item.id.equals(currentWorkspace())) return item;
        }
        return null;
    }

    private void showArchivedSessions() {
        if (selected == null || currentWorkspace().isEmpty()) {
            setChatStatus("请先选择工作区");
            return;
        }
        DeviceStore.Device device = selected;
        RemoteClient.Workspace workspace = selectedWorkspaceDetails();
        List<RemoteClient.Session> all = sessionCache.get(device.id + ":" + currentWorkspace());
        List<RemoteClient.Session> archived = new ArrayList<>();
        if (all != null) for (RemoteClient.Session item : all) {
            if (item.archived) archived.add(item);
        }
        if (archived.isEmpty()) {
            setChatStatus("当前工作区没有已归档会话");
            return;
        }
        String[] titles = new String[archived.size()];
        for (int i = 0; i < archived.size(); i++) titles[i] = archived.get(i).title;
        new AlertDialog.Builder(this).setTitle("已归档会话")
                .setItems(titles, (dialog, which) ->
                        showSessionDetails(device, workspace, archived.get(which)))
                .setNegativeButton("关闭", null).show();
    }

    private void showSessionDetails(DeviceStore.Device device, RemoteClient.Workspace workspace,
                                    RemoteClient.Session session) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(8), dp(18), dp(8));
        TextView info = new TextView(this);
        info.setText("电脑：" + device.name + "\n工作区："
                + (workspace == null ? "未知" : workspace.title)
                + "\n文件夹：" + (workspace == null ? "未知" : workspace.path)
                + "\n会话 ID：" + session.id
                + "\n状态：" + (session.archived ? "已归档" : "使用中"));
        info.setTextIsSelectable(true);
        info.setTextColor(NAVY);
        form.addView(info);
        TextView historyStatus = new TextView(this);
        historyStatus.setPadding(0, dp(12), 0, dp(4));
        historyStatus.setText("正在读取历史记录…");
        form.addView(historyStatus);
        ScrollView scroll = new ScrollView(this);
        LinearLayout historyRows = new LinearLayout(this);
        historyRows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(historyRows);
        form.addView(scroll, new LinearLayout.LayoutParams(-1, dp(280)));
        LinearLayout actions = new LinearLayout(this);
        form.addView(actions);
        Button older = button(actions, "加载更早记录", () -> {});
        older.setEnabled(false);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(session.title).setView(form)
                .setPositiveButton(session.archived ? "恢复会话" : "从列表移除", null)
                .setNegativeButton("关闭", null).create();
        dialog.show();
        List<RemoteClient.Message> history = new ArrayList<>();
        int[] before = {-1};
        older.setOnClickListener(view -> loadHistoryPage(device, session.id, before,
                history, historyRows, historyStatus, older, dialog));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            historyStatus.setText(session.archived ? "正在恢复会话…" : "正在归档会话…");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            worker.execute(() -> {
                try {
                    client.archiveSession(device.url, secrets.token(device.url),
                            session.id, !session.archived);
                    main.post(() -> {
                        dialog.dismiss();
                        setChatStatus(session.archived ? "会话已恢复" : "会话已从列表移除，可在已归档中恢复");
                        loadSessions(device, workspace == null ? currentWorkspace() : workspace.id);
                    });
                } catch (Exception e) {
                    main.post(() -> {
                        historyStatus.setText("操作失败：" + e.getMessage());
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    });
                }
            });
        });
        loadHistoryPage(device, session.id, before, history, historyRows,
                historyStatus, older, dialog);
    }

    private void loadHistoryPage(DeviceStore.Device device, String sessionId, int[] before,
                                 List<RemoteClient.Message> history, LinearLayout rows,
                                 TextView statusText, Button older, AlertDialog dialog) {
        int cursor = before[0];
        older.setEnabled(false);
        statusText.setText("正在读取历史记录…");
        probes.execute(() -> {
            try {
                RemoteClient.HistoryPage page = client.history(device.url,
                        secrets.token(device.url), sessionId, cursor);
                main.post(() -> {
                    if (!dialog.isShowing()) return;
                    if (cursor < 0) history.clear();
                    history.addAll(0, page.items);
                    rows.removeAllViews();
                    for (RemoteClient.Message message : history) {
                        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
                        params.topMargin = dp(8);
                        rows.addView(createMessageCard(message), params);
                    }
                    ((ScrollView) rows.getParent()).scrollTo(0, 0);
                    before[0] = page.before;
                    older.setText("加载更早记录");
                    older.setEnabled(page.hasMore && page.before > 0);
                    statusText.setText(history.isEmpty() ? "暂无历史消息"
                            : "已显示 " + history.size() + " 条消息"
                            + (page.hasMore ? "，可加载更早记录" : ""));
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (dialog.isShowing()) {
                        statusText.setText("读取历史失败：" + e.getMessage());
                        older.setText("重试读取");
                        older.setEnabled(true);
                    }
                });
            }
        });
    }

    private void loadSessions(DeviceStore.Device device, String workspaceId) {
        if (workspaceId.isEmpty()) return;
        String token = secrets.token(device.url);
        if (token.isEmpty()) return;
        probes.execute(() -> {
            try {
                List<RemoteClient.Session> items = client.sessions(device.url, token, workspaceId);
                main.post(() -> {
                    sessionCache.put(device.id + ":" + workspaceId, items);
                    if (selected != null && selected.id.equals(device.id)
                            && currentWorkspace().equals(workspaceId)) {
                        String selectedSession = devices.selectedSession(device.id, workspaceId);
                        boolean found = false;
                        for (RemoteClient.Session item : items) {
                            if (!item.archived && item.id.equals(selectedSession)) found = true;
                        }
                        if (!found) {
                            String replacement = "";
                            for (RemoteClient.Session item : items) {
                                if (!item.archived) { replacement = item.id; break; }
                            }
                            devices.selectSession(device.id, workspaceId, replacement);
                        }
                        renderSessions();
                        loadMessages(false);
                    }
                });
            } catch (Exception e) {
                main.post(() -> setStatus("加载对话失败：" + e.getMessage()));
            }
        });
    }

    private void createSession() {
        if (selected == null || currentWorkspace().isEmpty()) {
            setStatus("请先选择工作区"); return;
        }
        DeviceStore.Device device = selected;
        String workspaceId = currentWorkspace();
        String token = secrets.token(device.url);
        if (token.isEmpty()) { setStatus("请先配对设备"); return; }
        setStatus("正在创建对话…");
        worker.execute(() -> {
            try {
                RemoteClient.Session created = client.createSession(device.url, token, workspaceId);
                devices.selectSession(device.id, workspaceId, created.id);
                loadSessions(device, workspaceId);
                main.post(() -> setStatus("已创建对话，可输入消息"));
            } catch (Exception e) {
                main.post(() -> setStatus("创建对话失败：" + e.getMessage()));
            }
        });
    }

    private void loadMessages(boolean reportError) {
        if (chatLoading || selected == null || currentWorkspace().isEmpty()) return;
        DeviceStore.Device device = selected;
        String workspaceId = currentWorkspace();
        String sessionId = devices.selectedSession(device.id, workspaceId);
        String token = secrets.token(device.url);
        if (sessionId.isEmpty() || token.isEmpty()) return;
        chatLoading = true;
        probes.execute(() -> {
            try {
                List<RemoteClient.Message> messages = client.messages(device.url, token, sessionId);
                main.post(() -> {
                    if (selected != null && selected.id.equals(device.id)
                            && currentWorkspace().equals(workspaceId)
                            && devices.selectedSession(device.id, workspaceId).equals(sessionId)) {
                        int hash = 1;
                        for (RemoteClient.Message message : messages) {
                            hash = 31 * hash + message.role.hashCode();
                            hash = 31 * hash + message.text.hashCode();
                            hash = 31 * hash + message.reasoning.hashCode();
                        }
                        if (hash != displayedMessagesHash) {
                            displayedMessagesHash = hash;
                            messageRows.removeAllViews();
                            welcomeVisible = false;
                            messageRows.setGravity(Gravity.TOP);
                            messageRows.setMinimumHeight(0);
                            for (RemoteClient.Message message : messages) {
                                addMessageCard(message);
                            }
                            if (messages.isEmpty()) showWelcome("探索未至之境");
                            mainScroll.post(() -> mainScroll.smoothScrollTo(0,
                                    messageRows.getBottom()));
                            if (!messages.isEmpty()
                                    && "assistant".equals(messages.get(messages.size() - 1).role)) {
                                setChatStatus("DeepSeek-harness 已回复");
                            }
                        }
                    }
                    chatLoading = false;
                });
            } catch (Exception e) {
                main.post(() -> {
                    chatLoading = false;
                    if (reportError) setStatus("加载对话失败：" + e.getMessage());
                });
            }
        });
    }

    private void addMessageCard(RemoteClient.Message message) {
        LinearLayout card = createMessageCard(message);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setGravity("user".equals(message.role) ? Gravity.RIGHT : Gravity.LEFT);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                "user".equals(message.role) ? -2 : -1, -2);
        cardParams.topMargin = dp(10);
        wrapper.addView(card, new LinearLayout.LayoutParams(
                "user".equals(message.role)
                        ? getResources().getDisplayMetrics().widthPixels - dp(110) : -1, -2));
        messageRows.addView(wrapper, cardParams);
    }

    private LinearLayout createMessageCard(RemoteClient.Message message) {
        boolean mine = "user".equals(message.role);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(13), dp(16), dp(15));
        GradientDrawable background = softBackground(mine ? Color.rgb(234, 241, 255)
                : Color.WHITE, dp(24), Color.TRANSPARENT);
        card.setBackground(background);
        TextView author = new TextView(this);
        author.setText(mine ? "我" : "✦  DeepSeek-harness");
        author.setTextColor(mine ? BLUE : MUTED);
        author.setTextSize(12);
        author.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(author);

        if (!mine && !message.reasoning.trim().isEmpty()) {
            TextView thought = new TextView(this);
            thought.setText(MarkdownFormatter.render(message.reasoning));
            thought.setTextColor(MUTED);
            thought.setTextSize(13);
            thought.setLineSpacing(dp(2), 1.05f);
            thought.setPadding(dp(4), dp(6), dp(4), dp(8));
            thought.setVisibility(View.GONE);
            Button toggle = new Button(this);
            toggle.setText("查看思考过程  ▾");
            toggle.setTextSize(12);
            toggle.setAllCaps(false);
            toggle.setTextColor(MUTED);
            toggle.setBackgroundColor(Color.TRANSPARENT);
            toggle.setOnClickListener(view -> {
                boolean show = thought.getVisibility() != View.VISIBLE;
                thought.setVisibility(show ? View.VISIBLE : View.GONE);
                toggle.setText(show ? "收起思考过程  ▴" : "查看思考过程  ▾");
            });
            card.addView(toggle);
            card.addView(thought);
        }

        TextView content = new TextView(this);
        content.setText(MarkdownFormatter.render(message.text));
        content.setTextColor(NAVY);
        content.setTextSize(15);
        content.setLineSpacing(dp(3), 1.08f);
        content.setTextIsSelectable(true);
        content.setMovementMethod(LinkMovementMethod.getInstance());
        content.setPadding(0, dp(7), 0, 0);
        card.addView(content);
        return card;
    }

    private void sendMessage() {
        if (selected == null || currentWorkspace().isEmpty()) {
            setChatStatus("请先选择工作区"); return;
        }
        DeviceStore.Device device = selected;
        String workspaceId = currentWorkspace();
        String sessionId = devices.selectedSession(device.id, workspaceId);
        String message = messageField.getText().toString().trim();
        boolean search = webSearchEnabled && !message.isEmpty();
        Uri attachment = pendingAttachment;
        String attachmentName = pendingAttachmentName;
        if (message.isEmpty() && attachment == null) { setChatStatus("请输入消息或添加附件"); return; }
        String token = secrets.token(device.url);
        if (token.isEmpty()) { setChatStatus("请先配对设备"); return; }
        setChatStatus(sessionId.isEmpty() ? "正在创建对话并发送消息…" : "正在发送消息…");
        worker.execute(() -> {
            try {
                String targetSession = sessionId;
                if (targetSession.isEmpty()) {
                    RemoteClient.Session created = client.createSession(device.url, token, workspaceId);
                    targetSession = created.id;
                    String newSessionId = targetSession;
                    main.post(() -> {
                        devices.selectSession(device.id, workspaceId, newSessionId);
                        renderSessions();
                    });
                }
                String receiptId = "";
                if (attachment != null) {
                    String encoded = encodeAttachment(attachment);
                    receiptId = client.uploadFile(device.url, token, targetSession,
                            attachmentName, encoded);
                }
                client.sendMessage(device.url, token, targetSession, message, receiptId, search);
                loadSessions(device, workspaceId);
                main.post(() -> {
                    messageField.setText("");
                    if (attachment != null && attachment.equals(pendingAttachment)) {
                        pendingAttachment = null;
                        pendingAttachmentName = "";
                        attachmentLabel.setVisibility(View.GONE);
                    }
                    setChatStatus("消息已发送，等待 DeepSeek-harness 回复…");
                    loadMessages(true);
                });
            } catch (Exception e) {
                main.post(() -> setChatStatus("发送失败：" + e.getMessage()));
            }
        });
    }

    private void setChatStatus(String message) {
        chatStatus.setText(message);
        chatStatus.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void createWorkspace() {
        if (selected == null || busy) return;
        DeviceStore.Device device = selected;
        String token = secrets.token(device.url);
        if (token.isEmpty()) { setStatus("请先配对 " + device.name); return; }
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("工作区名称");
        new AlertDialog.Builder(this).setTitle("在 " + device.name + " 新建工作区")
                .setView(name).setNegativeButton("取消", null)
                .setPositiveButton("创建", (dialog, which) -> {
                    String value = name.getText().toString().trim();
                    if (value.isEmpty()) { setStatus("请输入工作区名称"); return; }
                    showFolderPicker(device, token, value);
                }).show();
    }

    private void showFolderPicker(DeviceStore.Device device, String token, String name) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView pathLabel = new TextView(this);
        pathLabel.setText("正在读取电脑文件夹…");
        pathLabel.setTextIsSelectable(true);
        form.addView(pathLabel);
        EditText pathEntry = new EditText(this);
        pathEntry.setSingleLine(true);
        pathEntry.setHint("电脑文件夹路径（也可点击下方文件夹）");
        pathEntry.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        form.addView(pathEntry);
        LinearLayout navigation = new LinearLayout(this);
        form.addView(navigation);
        Button upButton = button(navigation, "上一级", () -> {});
        Button openButton = button(navigation, "打开路径", () -> {});
        ScrollView scroll = new ScrollView(this);
        LinearLayout folderRows = new LinearLayout(this);
        folderRows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(folderRows);
        form.addView(scroll, new LinearLayout.LayoutParams(-1, dp(280)));
        TextView folderStatus = new TextView(this);
        folderStatus.setTextColor(MUTED);
        form.addView(folderStatus);
        LinearLayout actions = new LinearLayout(this);
        form.addView(actions);
        Button chooseButton = button(actions, "使用当前文件夹创建工作区", () -> {});
        primary(chooseButton);
        chooseButton.setEnabled(false);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("选择 " + device.name + " 上的文件夹")
                .setView(form).setNegativeButton("取消", null).create();
        dialog.show();
        RemoteClient.FolderListing[] current = {null};
        int[] requestSerial = {0};
        upButton.setOnClickListener(view -> {
            if (current[0] != null && !current[0].path.isEmpty()) {
                loadFolders(device, token, current[0].parent, pathLabel, pathEntry,
                        folderRows, folderStatus, chooseButton, current, requestSerial);
            }
        });
        openButton.setOnClickListener(view -> loadFolders(device, token,
                pathEntry.getText().toString().trim(), pathLabel, pathEntry,
                folderRows, folderStatus, chooseButton, current, requestSerial));
        chooseButton.setOnClickListener(view -> {
            if (current[0] == null) return;
            String path = current[0].path;
            dialog.dismiss();
            setStatus("正在创建工作区…");
            worker.execute(() -> {
                try {
                    RemoteClient.Workspace created = client.createWorkspace(device.url,
                            token, name, path);
                    main.post(() -> {
                        devices.selectWorkspace(device.id, created.id);
                        setStatus("已创建 " + device.name + " / " + created.title);
                    });
                    loadWorkspaces(device);
                } catch (Exception e) {
                    main.post(() -> setStatus("创建失败：" + e.getMessage()));
                }
            });
        });
        loadFolders(device, token, "", pathLabel, pathEntry, folderRows,
                folderStatus, chooseButton, current, requestSerial);
    }

    private void loadFolders(DeviceStore.Device device, String token, String path,
                             TextView pathLabel, EditText pathEntry, LinearLayout folderRows,
                             TextView folderStatus, Button chooseButton,
                             RemoteClient.FolderListing[] current, int[] requestSerial) {
        int request = ++requestSerial[0];
        chooseButton.setEnabled(false);
        current[0] = null;
        folderStatus.setText("正在读取文件夹…");
        probes.execute(() -> {
            try {
                RemoteClient.FolderListing listing = client.folders(device.url, token, path);
                main.post(() -> {
                    if (request != requestSerial[0]) return;
                    current[0] = listing;
                    pathLabel.setText(listing.path.isEmpty()
                            ? "选择电脑磁盘" : "当前文件夹：" + listing.path);
                    pathEntry.setText(listing.path);
                    folderRows.removeAllViews();
                    for (RemoteClient.Folder folder : listing.folders) {
                        TextView row = new TextView(this);
                        row.setText("📁  " + folder.name);
                        row.setTextSize(15);
                        row.setTextColor(NAVY);
                        row.setPadding(dp(10), dp(12), dp(10), dp(12));
                        folderRows.addView(row);
                        row.setOnClickListener(view -> loadFolders(device, token, folder.path,
                                pathLabel, pathEntry, folderRows, folderStatus, chooseButton,
                                current, requestSerial));
                    }
                    folderStatus.setText(listing.path.isEmpty() ? "选择磁盘，再选择文件夹"
                            : listing.folders.isEmpty() ? "此文件夹没有子文件夹，可直接选择"
                            : "点击文件夹进入，再选择当前位置");
                    chooseButton.setEnabled(!listing.path.isEmpty());
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (request == requestSerial[0]) {
                        folderStatus.setText("无法打开文件夹：" + e.getMessage());
                        chooseButton.setEnabled(false);
                    }
                });
            }
        });
    }

    private void loadWorkspaces(DeviceStore.Device device) {
        try {
            List<RemoteClient.Workspace> items = client.workspaces(device.url,
                    secrets.token(device.url));
            main.post(() -> {
                workspaceCache.put(device.id, items);
                String selectedId = devices.selectedWorkspace(device.id);
                boolean present = false;
                for (RemoteClient.Workspace item : items) {
                    if (item.id.equals(selectedId)) { present = true; break; }
                }
                if (!present) {
                    devices.selectWorkspace(device.id, items.isEmpty() ? "" : items.get(0).id);
                }
                renderWorkspaces();
                if (selected != null && selected.id.equals(device.id)) {
                    renderSessions();
                    loadSessions(device, devices.selectedWorkspace(device.id));
                }
            });
        } catch (Exception error) {
            main.post(() -> {
                workspaceCache.remove(device.id);
                renderWorkspaces();
                if (selected != null && selected.id.equals(device.id)) {
                    setStatus("加载工作区失败：" + error.getMessage());
                }
            });
        }
    }

    private void editDevice(DeviceStore.Device existing) {
        if (busy) return;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), 0);
        EditText name = field(form, "设备名称", existing == null ? "" : existing.name,
                InputType.TYPE_CLASS_TEXT, false);
        EditText note = field(form, "备注（选填）", existing == null ? "" : existing.note,
                InputType.TYPE_CLASS_TEXT, false);
        EditText address = field(form, "连接地址", existing == null ? "" : existing.url,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, false);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "添加设备" : "编辑设备")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String label = name.getText().toString().trim();
                    if (label.isEmpty()) { name.setError("请输入设备名称"); return; }
                    String typed = address.getText().toString().trim();
                    String memo = note.getText().toString().trim();
                    if (DiscoveryClient.isPrivateAddressUrl(typed)
                            || DiscoveryClient.isPrivateSecureUrl(typed)) {
                        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK)
                                != PackageManager.PERMISSION_GRANTED) {
                            pendingAction = 5;
                            requestPermissions(new String[]{LOCAL_NETWORK}, NETWORK_REQUEST);
                            return;
                        }
                        Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                        save.setEnabled(false);
                        setStatus("正在核验 " + typed + " 上的插件…");
                        discoveryWorker.execute(() -> {
                            try {
                                DiscoveryClient discovery = new DiscoveryClient();
                                DiscoveryClient.Found found = DiscoveryClient.isPrivateSecureUrl(typed)
                                        ? discovery.probeHttps(typed) : discovery.probe(typed);
                                main.post(() -> {
                                    if (!dialog.isShowing()) return;
                                    save.setEnabled(true);
                                    confirmSecureDevice(existing, label, memo,
                                            found, address, dialog);
                                });
                            } catch (Exception e) {
                                main.post(() -> {
                                    if (!dialog.isShowing()) return;
                                    save.setEnabled(true);
                                    address.setError(e.getMessage());
                                    setStatus("未连到该地址上的新版 PC 插件");
                                });
                            }
                        });
                        return;
                    }
                    try {
                        saveDevice(existing, label, memo,
                                existing != null && typed.equals(existing.url)
                                        ? existing.url : RemoteClient.normalizeUrl(typed),
                                address, dialog);
                    } catch (Exception e) { address.setError(e.getMessage()); }
                }));
        dialog.show();
    }

    private void confirmSecureDevice(DeviceStore.Device existing, String label, String memo,
                                     DiscoveryClient.Found found, EditText address,
                                     AlertDialog editor) {
        if (found.secureUrl.isEmpty()) {
            address.setError("请先更新这台 PC 的 dsh Remote 插件以启用 HTTPS");
            return;
        }
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 24; i += 4) {
            if (i > 0) code.append(' ');
            code.append(found.fingerprint, i, i + 4);
        }
        new AlertDialog.Builder(this)
                .setTitle("核对电脑身份")
                .setMessage("请打开该 PC 插件的「手机连接」，核对下方身份码是否完全一致：\n\n"
                        + code + "\n\n若不同，请取消，不要输入配对码。")
                .setNegativeButton("取消", null)
                .setPositiveButton("一致，保存", (ignored, which) -> {
                    String previousToken = existing == null ? "" : secrets.token(existing.url);
                    boolean sameHost = existing != null && (existing.url.startsWith(
                            "http://dsh-" + found.id + ".local:")
                            || found.fingerprint.equals(secrets.fingerprint(existing.url)));
                    secrets.saveFingerprint(found.secureUrl, found.fingerprint);
                    saveDevice(existing, label, memo, found.secureUrl, address, editor);
                    if (sameHost && !previousToken.isEmpty() && !editor.isShowing()) {
                        try { secrets.saveToken(previousToken, found.secureUrl); }
                        catch (Exception e) { setStatus("设备已保存，请重新输入配对码"); }
                    }
                }).show();
    }

    private void saveDevice(DeviceStore.Device existing, String label, String memo,
                            String url, EditText address, AlertDialog dialog) {
        List<DeviceStore.Device> list = new ArrayList<>(devices.list());
        for (DeviceStore.Device device : list) {
            if (device.url.equals(url) && (existing == null || !device.id.equals(existing.id))) {
                address.setError("此地址已添加");
                return;
            }
        }
        DeviceStore.Device updated = new DeviceStore.Device(existing == null
                ? UUID.randomUUID().toString() : existing.id, label, memo, url);
        if (existing != null) list.removeIf(item -> item.id.equals(existing.id));
        list.add(updated);
        devices.save(list, updated.id);
        if (existing != null && !existing.url.equals(url)) {
            secrets.removeToken(existing.url);
            secrets.removeFingerprint(existing.url);
            devices.selectWorkspace(updated.id, "");
            workspaceCache.remove(updated.id);
        }
        selected = updated;
        renderDeviceRows();
        renderSelected();
        renderWorkspaces();
        setStatus("已保存 " + label);
        dialog.dismiss();
    }

    private void confirmRemove() {
        if (selected == null || busy) return;
        DeviceStore.Device target = selected;
        new AlertDialog.Builder(this).setTitle("删除 " + target.name + "？")
                .setMessage("这会清除手机保存的该设备配对信息。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    List<DeviceStore.Device> list = new ArrayList<>(devices.list());
                    list.removeIf(item -> item.id.equals(target.id));
                    secrets.removeToken(target.url);
                    secrets.removeFingerprint(target.url);
                    deviceStatus.remove(target.id);
                    selected = list.isEmpty() ? null : list.get(0);
                    devices.save(list, selected == null ? "" : selected.id);
                    renderDeviceRows();
                    renderSelected();
                    workspaceCache.remove(target.id);
                    renderWorkspaces();
                    setStatus("设备已删除");
                }).show();
    }

    private void checkSavedDevices() {
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK)
                != PackageManager.PERMISSION_GRANTED) return;
        for (DeviceStore.Device device : devices.list()) {
            if (!secrets.token(device.url).isEmpty()) checkDevice(device);
        }
    }

    private void scanDevices(boolean manual) {
        if (scanning) return;
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK)
                != PackageManager.PERMISSION_GRANTED) {
            if (manual) {
                pendingAction = 4;
                requestPermissions(new String[]{LOCAL_NETWORK}, NETWORK_REQUEST);
            }
            return;
        }
        scanning = true;
        if (manual) setStatus("正在查找同一局域网中的电脑…");
        discoveryWorker.execute(() -> {
            try {
                List<DiscoveryClient.Found> found = new DiscoveryClient().scan(this);
                main.post(() -> {
                    List<DeviceStore.Device> list = new ArrayList<>(devices.list());
                    int added = 0;
                    boolean changed = false;
                    for (DiscoveryClient.Found item : found) {
                        boolean known = false;
                        for (int i = 0; i < list.size(); i++) {
                            DeviceStore.Device saved = list.get(i);
                            if (saved.url.equals(item.url)) {
                                known = true;
                                break;
                            }
                            if (saved.url.equals(item.addressUrl)) {
                                try {
                                    String oldToken = secrets.token(saved.url);
                                    if (!oldToken.isEmpty()) {
                                        secrets.saveToken(oldToken, item.url);
                                        secrets.removeToken(saved.url);
                                    }
                                    DeviceStore.Device moved = new DeviceStore.Device(saved.id,
                                            saved.name, saved.note, item.url);
                                    list.set(i, moved);
                                    if (selected != null && selected.id.equals(saved.id)) {
                                        selected = moved;
                                    }
                                    changed = true;
                                } catch (Exception ignored) {
                                    // Keep the working address if token migration failed.
                                }
                                known = true;
                                break;
                            }
                        }
                        if (!known) {
                            list.add(new DeviceStore.Device(UUID.randomUUID().toString(),
                                    item.name, "自动发现", item.url));
                            added++;
                        }
                    }
                    if (added > 0 || changed) {
                        if (selected == null) selected = list.get(0);
                        devices.save(list, selected.id);
                        renderDeviceRows();
                        renderSelected();
                    }
                    if (manual) setStatus(found.isEmpty() ? "未发现电脑，请检查 PC 插件和局域网"
                            : "发现 " + found.size() + " 台电脑，新增 " + added + " 台");
                    else if (added > 0 || changed) setStatus("已自动同步局域网设备");
                    scanning = false;
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (manual) setStatus("设备发现失败：" + e.getMessage());
                    scanning = false;
                });
            }
        });
    }

    private void checkDevice(DeviceStore.Device device) {
        String token = secrets.token(device.url);
        deviceStatus.put(device.id, "检查中");
        renderDeviceRows();
        probes.execute(() -> {
            try {
                client.check(device.url, token);
                main.post(() -> {
                    deviceStatus.put(device.id, "在线");
                    renderDeviceRows();
                    if (selected != null && selected.id.equals(device.id) && !busy) {
                        setStatus(device.name + " · 在线");
                    }
                });
                loadWorkspaces(device);
            } catch (Exception e) {
                main.post(() -> {
                    deviceStatus.put(device.id, "离线");
                    workspaceCache.remove(device.id);
                    renderDeviceRows();
                    renderWorkspaces();
                    if (selected != null && selected.id.equals(device.id) && !busy) {
                        setStatus(device.name + " · 离线");
                    }
                });
            }
        });
    }

    private void start(int action) {
        if (busy || selected == null) return;
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK)
                != PackageManager.PERMISSION_GRANTED) {
            pendingAction = action;
            setStatus("等待局域网权限授权…");
            requestPermissions(new String[]{LOCAL_NETWORK}, NETWORK_REQUEST);
            return;
        }
        DeviceStore.Device device = selected;
        String url = device.url;
        String token = secrets.token(url);
        String code = codeField.getText().toString().trim();
        String command = commandField.getText().toString();
        if (action == 1 && !code.matches("[0-9]{8}")) {
            setStatus("请输入 PC 端显示的 8 位配对码");
            return;
        }
        if (action != 1 && token.isEmpty()) {
            setStatus("请先与 " + device.name + " 配对");
            return;
        }
        if (action == 3 && command.trim().isEmpty()) {
            setStatus("请输入要执行的命令");
            return;
        }
        busy = true;
        renderSelected();
        stopButton.setEnabled(action == 3);
        if (action == 3) output.setText("");
        setStatus(action == 1 ? "正在配对 " + device.name + "…"
                : action == 3 ? "正在执行命令…" : "正在连接 " + device.name + "…");
        worker.execute(() -> {
            try {
                if (action == 1) {
                    String newToken = client.pair(url, code);
                    secrets.saveToken(newToken, url);
                    client.check(url, newToken);
                    loadWorkspaces(device);
                    main.post(() -> {
                        codeField.setText("");
                        deviceStatus.put(device.id, "在线");
                        renderDeviceRows();
                        setStatus(device.name + " 已连接，下次打开自动连接");
                    });
                } else if (action == 3) {
                    client.execute(url, token, command,
                            devices.selectedWorkspace(device.id), (type, data) -> main.post(() -> {
                        if ("exit".equals(type)) setStatus("命令结束：" + data);
                        else appendOutput(data);
                    }));
                } else {
                    client.check(url, token);
                    main.post(() -> {
                        deviceStatus.put(device.id, "在线");
                        renderDeviceRows();
                        setStatus(device.name + " 已连接");
                    });
                }
            } catch (Exception e) {
                main.post(() -> {
                    deviceStatus.put(device.id, "离线");
                    renderDeviceRows();
                    setStatus(device.name + " 连接失败：" + e.getMessage());
                });
            } finally {
                main.post(() -> {
                    busy = false;
                    renderSelected();
                    stopButton.setEnabled(false);
                });
            }
        });
    }

    private void appendOutput(String text) {
        int existing = output.length();
        if (existing >= MAX_DISPLAY) return;
        output.append(text.substring(0, Math.min(text.length(), MAX_DISPLAY - existing)));
        if (existing + text.length() > MAX_DISPLAY) output.append("\n[显示已截断]");
    }

    private void setStatus(String message) {
        status.setText(message);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != NETWORK_REQUEST) return;
        int action = pendingAction;
        pendingAction = 0;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (action == 4) scanDevices(true);
            else if (action == 5) setStatus("已获得局域网权限，请再次点击保存");
            else start(action);
        } else {
            setStatus("未获局域网权限；请在系统设置中允许后重试");
        }
    }

    @Override
    protected void onDestroy() {
        client.cancel();
        worker.shutdownNow();
        probes.shutdownNow();
        discoveryWorker.shutdownNow();
        super.onDestroy();
    }
}
