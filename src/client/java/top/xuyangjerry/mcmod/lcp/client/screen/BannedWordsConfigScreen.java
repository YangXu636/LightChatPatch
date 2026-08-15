package top.xuyangjerry.mcmod.lcp.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import top.xuyangjerry.mcmod.lcp.client.bws.BannedWordsClientManager;
import top.xuyangjerry.mcmod.lcp.client.nonebot.ClientNetworking;

import java.util.ArrayList;
import java.util.List;

/**
 * BWS = Banned Words 违禁词系统设置界面
 *
 * 布局：
 *   - 顶部：标题 + 状态提示（是否已同步/是否为 OP）
 *   - 中部：可滚动词库列表（可选择，选中高亮）
 *   - 底部：[新增] [修改] [删除] | [确定] 按钮组
 *
 * 仅 OP 玩家可以修改词库，非 OP 玩家只能查看。
 */
public class BannedWordsConfigScreen extends Screen {

    private static final Component TITLE = Component.translatable("light_chat_patch.bws.title");
    private static final int ENTRY_HEIGHT = 24;
    private static final int LIST_PADDING = 10;
    private static final int BUTTON_WIDTH = 80;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 6;

    private final Screen parent;
    private int scrollOffset = 0;
    private int selectedIndex = -1;

    private Button addButton;
    private Button modifyButton;
    private Button deleteButton;
    private Button confirmButton;
    private Button refreshButton;

    /** 当前可显示的词库快照（从 BannedWordsClientManager 获取）。 */
    private List<String> displayedWords = new ArrayList<>();

    /** 新增/修改时的临时输入框。 */
    private net.minecraft.client.gui.components.EditBox inputBox;
    private boolean inputMode = false;
    private String inputModeOldWord = null; // 修改模式下的旧词

    /** 操作结果回显。 */
    private String statusMessage = null;
    private long statusMessageTime = 0;

    private int listTop;
    private int listBottom;
    private int listLeft;
    private int listRight;

    public BannedWordsConfigScreen(Screen parent) {
        super(TITLE);
        this.parent = parent;
    }

    @Override
    protected void init() {
        listTop = 32;
        listBottom = this.height - 50;
        listLeft = LIST_PADDING + 20;
        listRight = this.width - LIST_PADDING - 20;

        refreshDisplayedWords();
        scrollOffset = 0;

        int btnY = this.height - 32;
        int centerX = this.width / 2;

        // 底部按钮行
        int totalWidth = BUTTON_WIDTH * 4 + BUTTON_GAP * 3;
        int startX = centerX - totalWidth / 2;

        addButton = Button.builder(Component.translatable("light_chat_patch.bws.add"), b -> onAddClicked())
                .bounds(startX, btnY, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        modifyButton = Button.builder(Component.translatable("light_chat_patch.bws.modify"), b -> onModifyClicked())
                .bounds(startX + BUTTON_WIDTH + BUTTON_GAP, btnY, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        deleteButton = Button.builder(Component.translatable("light_chat_patch.bws.delete"), b -> onDeleteClicked())
                .bounds(startX + (BUTTON_WIDTH + BUTTON_GAP) * 2, btnY, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        confirmButton = Button.builder(Component.translatable("light_chat_patch.bws.confirm"), b -> onConfirmClicked())
                .bounds(startX + (BUTTON_WIDTH + BUTTON_GAP) * 3, btnY, BUTTON_WIDTH, BUTTON_HEIGHT).build();

        this.addRenderableWidget(addButton);
        this.addRenderableWidget(modifyButton);
        this.addRenderableWidget(deleteButton);
        this.addRenderableWidget(confirmButton);

        // 刷新按钮（右上角）
        refreshButton = Button.builder(
                Component.translatable("light_chat_patch.bws.tooltip.refresh"),
                b -> onRefreshClicked())
                .bounds(this.width - LIST_PADDING - 20, listTop, 80, BUTTON_HEIGHT).build();
        this.addRenderableWidget(refreshButton);

        // 初始按钮状态
        updateButtonStates();
    }

    private void refreshDisplayedWords() {
        displayedWords = BannedWordsClientManager.getCachedWords();
        // 重新选中时保持选中，若越界则调整
        if (selectedIndex >= displayedWords.size()) {
            selectedIndex = displayedWords.isEmpty() ? -1 : displayedWords.size() - 1;
        }
    }

    private void updateButtonStates() {
        boolean canModify = !inputMode && selectedIndex >= 0 && selectedIndex < displayedWords.size();
        modifyButton.active = canModify;
        deleteButton.active = canModify;
        addButton.active = !inputMode;

        if (inputMode) {
            confirmButton.setMessage(Component.translatable("light_chat_patch.bws.confirm"));
        } else {
            confirmButton.setMessage(CommonComponents.GUI_DONE);
        }
    }

    // ==================== 按钮事件 ====================

    private void onAddClicked() {
        inputMode = true;
        inputModeOldWord = null;
        selectedIndex = -1;
        showInputBox("");
        updateButtonStates();
    }

    private void onModifyClicked() {
        if (selectedIndex < 0 || selectedIndex >= displayedWords.size()) return;
        inputMode = true;
        inputModeOldWord = displayedWords.get(selectedIndex);
        showInputBox(inputModeOldWord);
        updateButtonStates();
    }

    private void onDeleteClicked() {
        if (selectedIndex < 0 || selectedIndex >= displayedWords.size()) return;
        String word = displayedWords.get(selectedIndex);
        BannedWordsClientManager.sendRemoveRequest(word);
        BannedWordsClientManager.optimisticRemove(word);
        showStatusMessage(Component.translatable("light_chat_patch.bws.status.deleteSent", word).getString());
        selectedIndex = -1;
        refreshDisplayedWords();
        updateButtonStates();
    }

    private void onConfirmClicked() {
        if (inputMode && inputBox != null) {
            String input = inputBox.getValue().trim();
            if (input.isEmpty()) {
                showStatusMessage(Component.translatable("light_chat_patch.bws.status.enterWord").getString());
                return;
            }
            if (inputModeOldWord != null) {
                BannedWordsClientManager.sendModifyRequest(inputModeOldWord, input);
                BannedWordsClientManager.optimisticModify(inputModeOldWord, input);
                showStatusMessage(Component.translatable("light_chat_patch.bws.status.modifySent", inputModeOldWord, input).getString());
            } else {
                BannedWordsClientManager.sendAddRequest(input);
                BannedWordsClientManager.optimisticAdd(input);
                showStatusMessage(Component.translatable("light_chat_patch.bws.status.addSent", input).getString());
            }
            removeInputBox();
            inputMode = false;
            inputModeOldWord = null;
            refreshDisplayedWords();
            updateButtonStates();
        } else {
            this.minecraft.gui.setScreen(parent);
        }
    }

    private void onRefreshClicked() {
        BannedWordsClientManager.sendListRequest();
        showStatusMessage(Component.translatable("light_chat_patch.bws.status.refreshSent").getString());
    }

    // ==================== 输入框 ====================

    private void showInputBox(String initialValue) {
        if (inputBox != null) {
            this.removeWidget(inputBox);
        }
        int inputWidth = Math.min(200, listRight - listLeft);
        int inputX = listLeft + (listRight - listLeft - inputWidth) / 2;
        int inputY = listBottom + 4;
        inputBox = new net.minecraft.client.gui.components.EditBox(this.font, inputX, inputY, inputWidth, BUTTON_HEIGHT, Component.empty());
        inputBox.setMaxLength(64);
        inputBox.setValue(initialValue != null ? initialValue : "");
        inputBox.setResponder(s -> {
            // 实时响应，无需额外逻辑
        });
        this.addRenderableWidget(inputBox);
        this.setFocused(inputBox);
    }

    private void removeInputBox() {
        if (inputBox != null) {
            this.removeWidget(inputBox);
            inputBox = null;
        }
    }

    // ==================== 状态消息 ====================

    private void showStatusMessage(String msg) {
        statusMessage = msg;
        statusMessageTime = System.currentTimeMillis();
    }

    // ==================== 渲染 ====================

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);

        // 状态行
        String syncStatus = BannedWordsClientManager.isSynced()
                ? Component.translatable("light_chat_patch.bws.status.count", displayedWords.size()).getString()
                : Component.translatable("light_chat_patch.bws.status.syncing").getString();
        g.text(this.font, Component.literal(syncStatus), listLeft, 22, 0xFFAAAAAA);

        // 操作结果回显
        if (statusMessage != null && System.currentTimeMillis() - statusMessageTime < 5000) {
            g.text(this.font, Component.literal(statusMessage), this.width / 2 - 80, this.height - 46, 0xFFFFFF55);
        }

        // 可滚动区域
        g.enableScissor(listLeft, listTop, listRight, listBottom);

        int contentHeight = displayedWords.size() * ENTRY_HEIGHT;
        int maxScroll = Math.max(0, contentHeight - (listBottom - listTop));
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        int y = listTop - scrollOffset;
        for (int i = 0; i < displayedWords.size(); i++) {
            int entryY = listTop + i * ENTRY_HEIGHT - scrollOffset;
            if (entryY + ENTRY_HEIGHT < listTop || entryY >= listBottom) continue;

            boolean selected = (i == selectedIndex);
            String word = displayedWords.get(i);

            // 背景
            int bgColor = selected ? 0xFF000000 : (i % 2 == 0 ? 0xFF1a1a1a : 0xFF2a2a2a);
            g.fill(listLeft, entryY, listRight, entryY + ENTRY_HEIGHT - 1, bgColor);
            if (selected) {
                g.outline(listLeft, entryY, listRight - listLeft, ENTRY_HEIGHT - 1, 0xFFFFFF55);
            }

            // 文字
            int textColor = selected ? 0xFFFFFFFF : 0xFFCCCCCC;
            int textX = listLeft + 4;
            g.text(this.font, Component.literal(word), textX, entryY + (ENTRY_HEIGHT - 9) / 2, textColor);
        }

        g.disableScissor();

        // 滚动条
        if (contentHeight > (listBottom - listTop)) {
            int contentH = listBottom - listTop;
            int totalH = contentHeight;
            int scrollBarH = Math.max(20, (int) ((float) contentH * contentH / totalH));
            int scrollBarY = listTop + (int) ((float) scrollOffset / maxScroll * (contentH - scrollBarH));
            int scrollBarX = listRight + 2;
            g.fill(scrollBarX, scrollBarY, scrollBarX + 4, scrollBarY + scrollBarH, 0x80FFFFFF);
        }
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseY >= listTop && mouseY <= listBottom) {
            int contentHeight = displayedWords.size() * ENTRY_HEIGHT;
            int maxScroll = Math.max(0, contentHeight - (listBottom - listTop));
            scrollOffset = Math.max(0, Math.min(scrollOffset - (int) (scrollY * 12), maxScroll));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean handled) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (super.mouseClicked(event, handled)) return true;

        // 点击词库条目选中
        if (mouseX >= listLeft && mouseX <= listRight && mouseY >= listTop && mouseY <= listBottom) {
            int clickedRow = (int) ((mouseY - listTop + scrollOffset) / ENTRY_HEIGHT);
            if (clickedRow >= 0 && clickedRow < displayedWords.size()) {
                selectedIndex = clickedRow;
                updateButtonStates();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Enter 键在输入框聚焦时视为确认输入
        if (inputMode && inputBox != null && inputBox.isFocused()) {
            if (event.key() == 257) { // GLFW_KEY_ENTER
                onConfirmClicked();
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        removeInputBox();
        inputMode = false;
        this.minecraft.gui.setScreen(parent);
    }
}