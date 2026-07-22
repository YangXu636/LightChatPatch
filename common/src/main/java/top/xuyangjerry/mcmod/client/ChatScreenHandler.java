package top.xuyangjerry.mcmod.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import top.xuyangjerry.mcmod.client.message.ChatMessageLocator;
import top.xuyangjerry.mcmod.client.message.MessageActions;
import top.xuyangjerry.mcmod.client.message.MessageInfo;
import top.xuyangjerry.mcmod.client.message.MessageJumpManager;
import top.xuyangjerry.mcmod.client.screen.LightChatPatchConfigScreen;
import top.xuyangjerry.mcmod.config.ChatHistoryView;
import top.xuyangjerry.mcmod.config.LcpConfig;
import top.xuyangjerry.mcmod.history.ChatDraftManager;
import top.xuyangjerry.mcmod.history.ChatHistoryManager;
import top.xuyangjerry.mcmod.mixin.client.ChatComponentAccess;
import top.xuyangjerry.mcmod.mixin.client.ChatScreenAccess;
import top.xuyangjerry.mcmod.mixin.client.OptionsSubScreenAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * 聊天屏幕核心逻辑处理器。从原 ChatScreenMixin 提取，改为普通 Java 类。
 * 由 Fabric API 事件 / NeoForge 事件调用，不再依赖 @Inject mixin。
 */
public final class ChatScreenHandler {

    private static final WeakHashMap<ChatScreen, ChatScreenState> STATES = new WeakHashMap<>();
    private static boolean wasLevelNull = true;

    private ChatScreenHandler() {
    }

    // ==================== 状态管理 ====================

    private static ChatScreenState getState(ChatScreen screen) {
        return STATES.get(screen);
    }

    /**
     * 客户端 tick 事件。检测玩家进入世界，提前加载历史发送记录。
     */
    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && wasLevelNull) {
            wasLevelNull = false;
            if (LcpConfig.getInstance().isPreserveChatHistory()) {
                ChatHistoryManager.loadToRecentChat();
            }
        } else if (mc.level == null) {
            wasLevelNull = true;
        }
    }

    /**
     * 在 ChatScreen 初始化时调用。返回 true 表示首次初始化（调用方应注册 per-screen 事件）。
     */
    public static boolean onChatScreenInit(ChatScreen screen) {
        ChatScreenState state = STATES.get(screen);
        if (state != null) {
            return false; // 已初始化，是 re-init
        }

        state = new ChatScreenState();
        STATES.put(screen, state);

        // 加载历史到 recentChat
        if (LcpConfig.getInstance().isPreserveChatHistory()) {
            ChatHistoryManager.loadToRecentChat();
        }

        // 将当前所有消息注册到 MessageJumpManager
        trackExistingMessages();

        // 限制聊天框显示消息数
        trimChatMessages();

        // 加载草稿
        if (LcpConfig.getInstance().isSaveDraftOnClose()) {
            String draft = ChatDraftManager.loadDraft();
            if (draft != null && !draft.isEmpty()) {
                ChatScreenAccess access = (ChatScreenAccess) screen;
                access.lcp$setInitial(draft);
                access.lcp$setIsDraft(true);
            }
        }

        return true;
    }

    // ==================== Feature 1: 历史视图切换 (Ctrl+Tab) ====================

    /**
     * 键盘按键处理。返回 true 表示事件已消费（应取消原处理）。
     */
    public static boolean onChatScreenKeyPress(ChatScreen screen, int key, int scancode, int modifiers) {
        ChatScreenState state = getState(screen);
        if (state == null) {
            return false;
        }

        // Escape 关闭右键菜单或取消回复
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (state.menuOpen) {
                state.menuOpen = false;
                return true;
            }
            if (state.replyingTo != null) {
                state.replyingTo = null;
                return true;
            }
        }

        // Enter / Return：如果有回复状态，构造回复格式
        LcpConfig config = LcpConfig.getInstance();
        boolean isSendKey = key == config.getSendKey();
        boolean isNewlineKey = config.getNewlineKey() != GLFW.GLFW_KEY_UNKNOWN && key == config.getNewlineKey();
        boolean isShiftPressed = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;

        if (isSendKey && state.replyingTo != null) {
            String input = getInputValue(screen);
            if (input != null && !input.isBlank()) {
                if (!input.startsWith("/")) {
                    String replyText = Component.translatable("light_chat_patch.reply.format",
                            state.replyingTo.getSender(), state.replyingTo.getContent(), input).getString();
                    ((ChatScreenAccess) screen).lcp$getInput().setValue(replyText);
                }
                state.replyingTo = null;
            }
        }

        // 自定义发送/换行键：拦截默认行为
        if (isSendKey || isNewlineKey) {
            EditBox input = ((ChatScreenAccess) screen).lcp$getInput();
            if (input != null && input.isFocused()) {
                if (isSendKey && isShiftPressed) {
                    input.insertText("\n");
                    return true;
                } else if (isSendKey && isNewlineKey) {
                    return false;
                } else if (isSendKey) {
                    return false;
                } else if (isNewlineKey) {
                    input.insertText("\n");
                    return true;
                }
            }
        }

        // Ctrl+Tab 切换历史视图 (Ctrl+Shift+Tab 反向)
        if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 && key == GLFW.GLFW_KEY_TAB) {
            ChatHistoryView current = LcpConfig.getInstance().getChatHistoryView();
            ChatHistoryView[] views = ChatHistoryView.values();
            int dir = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1;
            int index = (current.ordinal() + dir + views.length) % views.length;
            ChatHistoryView next = views[index];
            LcpConfig.getInstance().setChatHistoryView(next);
            LcpConfig.save();

            if (LcpConfig.getInstance().isPreserveChatHistory()) {
                ChatHistoryManager.loadToRecentChat();
            }

            // 重置 historyPos 到末尾
            ((ChatScreenAccess) screen).lcp$setHistoryPos(
                    Minecraft.getInstance().gui.getChat().getRecentChat().size());

            return true;
        }

        return false;
    }

    // ==================== Feature 2: 草稿保存 + 历史持久化 ====================

    /**
     * 在 ChatScreen 关闭时调用。保存草稿并持久化历史。
     */
    public static void onChatScreenRemove(ChatScreen screen) {
        ChatScreenState state = STATES.get(screen);
        STATES.remove(screen); // 清理状态

        if (!LcpConfig.getInstance().isSaveDraftOnClose()) {
            persistRecentChat();
            return;
        }

        // 通过 input 是否为空判断是否发送了消息（发送后 input 会被清空）
        String inputText = getInputValue(screen);
        if (inputText != null && !inputText.isBlank()) {
            ChatDraftManager.saveDraft(inputText);
        } else {
            ChatDraftManager.clearDraft();
        }

        persistRecentChat();
    }

    private static String getInputValue(ChatScreen screen) {
        try {
            EditBox input = ((ChatScreenAccess) screen).lcp$getInput();
            return input.getValue();
        } catch (Exception e) {
            return null;
        }
    }

    private static void persistRecentChat() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        List<String> history = new ArrayList<>(mc.gui.getChat().getRecentChat());
        ChatHistoryManager.saveHistory(LcpConfig.getInstance().getChatHistoryView(), history);
    }

    private static void trackExistingMessages() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        ChatComponent chat = mc.gui.getChat();
        if (chat == null) return;
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();
        MessageJumpManager jumpMgr = MessageJumpManager.getInstance();
        for (GuiMessage msg : allMessages) {
            jumpMgr.getMessageId(msg);
        }
    }

    // ==================== Feature 3: 消息操作 (悬停按钮 + 右键菜单) ====================

    /**
     * 在 ChatScreen 渲染后调用。绘制悬停按钮或右键菜单。
     */
    public static void onChatScreenRender(ChatScreen screen, GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        ChatScreenState state = getState(screen);
        if (state == null) {
            return;
        }

        state.resetHoverState();

        trackExistingMessages();

        drawHighlightedMessage(g, state);

        // 绘制回复条（QQ-style，在输入框上方）
        if (state.replyingTo != null) {
            drawReplyBar(screen, g, state, mouseX, mouseY);
        }

        // 右键菜单优先
        if (state.menuOpen) {
            drawMenu(g, mouseX, mouseY, state);
            return;
        }

        // 悬停按钮
        if (!LcpConfig.getInstance().isEnableHoverButtons()) {
            return;
        }

        GuiMessage msg = ChatMessageLocator.findMessageAtMouse(mouseX, mouseY);
        if (msg == null) {
            return;
        }

        MessageInfo info = MessageInfo.from(msg);
        if (info == null || !info.isPlayerMessage()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        boolean isSelf = mc.player.getName().getString().equals(info.getSender());

        state.hoverMessage = info;
        drawHoverButtons(g, mouseX, mouseY, isSelf, state, mc.font);
    }

    private static void drawHoverButtons(GuiGraphics g, int mouseX, int mouseY, boolean isSelf,
                                          ChatScreenState state, net.minecraft.client.gui.Font font) {
        int visualLineIndex = ChatMessageLocator.getVisualLineIndexAtMouse(mouseY);
        if (visualLineIndex < 0) {
            return;
        }

        int lineTopY = ChatMessageLocator.getLineScreenYTop(visualLineIndex);
        int lineBottomY = ChatMessageLocator.getLineScreenYBottom(visualLineIndex);
        int lineCenterY = (lineTopY + lineBottomY) / 2;

        int buttonHeight = 12;
        int buttonY = lineCenterY - buttonHeight / 2;
        int x = ChatMessageLocator.getChatRightEdge() + 4;
        int gap = 2;

        boolean showPlusOne = !isSelf || LcpConfig.getInstance().isPlusOneSelf();
        String copyLabel = Component.translatable("light_chat_patch.action.copy").getString();
        String replyLabel = Component.translatable("light_chat_patch.action.reply").getString();
        String plusOneLabel = Component.translatable("light_chat_patch.action.plus_one").getString();

        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Integer> widths = new java.util.ArrayList<>();
        java.util.List<Integer> actions = new java.util.ArrayList<>();

        labels.add(copyLabel);
        widths.add(font.width(copyLabel) + 8);
        actions.add(ChatScreenState.ACTION_COPY);

        labels.add(replyLabel);
        widths.add(font.width(replyLabel) + 8);
        actions.add(ChatScreenState.ACTION_REPLY);

        if (showPlusOne) {
            labels.add(plusOneLabel);
            widths.add(font.width(plusOneLabel) + 8);
            actions.add(ChatScreenState.ACTION_PLUS_ONE);
        }

        for (int i = 0; i < labels.size(); i++) {
            int bx = x;
            int bw = widths.get(i);

            boolean hovered = mouseX >= bx && mouseX <= bx + bw
                    && mouseY >= buttonY && mouseY <= buttonY + buttonHeight;
            int bgColor = hovered ? 0xD0444444 : 0xB0000000;

            g.fill(bx, buttonY, bx + bw, buttonY + buttonHeight, bgColor);
            g.drawCenteredString(font, labels.get(i), bx + bw / 2, buttonY + 1, 0xFFFFFFFF);

            state.hoverButtons.add(new int[]{bx, buttonY, bx + bw, buttonY + buttonHeight, actions.get(i)});
            x += bw + gap;
        }
    }

    private static void drawMenu(GuiGraphics g, int mouseX, int mouseY, ChatScreenState state) {
        state.menuOptions.clear();

        int menuX = state.menuX;
        int menuY = state.menuY;
        int optionHeight = 16;

        boolean showPlusOne = !state.menuMessageIsSelf || LcpConfig.getInstance().isPlusOneSelf();
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        String copyLabel = Component.translatable("light_chat_patch.action.copy").getString();
        String replyLabel = Component.translatable("light_chat_patch.action.reply").getString();
        String plusOneLabel = Component.translatable("light_chat_patch.action.plus_one").getString();

        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Integer> actions = new java.util.ArrayList<>();

        labels.add(copyLabel);
        actions.add(ChatScreenState.ACTION_COPY);

        labels.add(replyLabel);
        actions.add(ChatScreenState.ACTION_REPLY);

        if (showPlusOne) {
            labels.add(plusOneLabel);
            actions.add(ChatScreenState.ACTION_PLUS_ONE);
        }

        int menuWidth = 80;
        for (String label : labels) {
            int w = font.width(label) + 12;
            if (w > menuWidth) menuWidth = w;
        }
        int menuHeight = labels.size() * optionHeight;

        // 保持菜单在屏幕内
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        if (menuX + menuWidth > screenWidth) {
            menuX = screenWidth - menuWidth - 4;
        }
        if (menuY + menuHeight > screenHeight) {
            menuY = screenHeight - menuHeight - 4;
        }

        // 背景边框
        g.fill(menuX - 1, menuY - 1, menuX + menuWidth + 1, menuY + menuHeight + 1, 0xFF222222);
        g.fill(menuX, menuY, menuX + menuWidth, menuY + menuHeight, 0xE0000000);

        for (int i = 0; i < labels.size(); i++) {
            int oy = menuY + i * optionHeight;
            boolean hovered = mouseX >= menuX && mouseX <= menuX + menuWidth
                    && mouseY >= oy && mouseY <= oy + optionHeight;

            if (hovered) {
                g.fill(menuX, oy, menuX + menuWidth, oy + optionHeight, 0xFF555555);
            }
            g.drawString(font, labels.get(i), menuX + 6, oy + 4, 0xFFFFFFFF);

            state.menuOptions.add(new int[]{menuX, oy, menuX + menuWidth, oy + optionHeight, actions.get(i)});
        }
    }

    /**
     * 鼠标点击处理。返回 true 表示事件已消费（应取消原处理）。
     */
    public static boolean onChatScreenMouseClick(ChatScreen screen, double mouseX, double mouseY, int button) {
        ChatScreenState state = getState(screen);
        if (state == null) {
            return false;
        }

        int mx = (int) mouseX;
        int my = (int) mouseY;

        // 点击回复条的 × 按钮取消回复
        if (state.replyingTo != null && state.replyBarCloseBounds != null) {
            int[] b = state.replyBarCloseBounds;
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                state.replyingTo = null;
                return true;
            }
            handleReplyBarClick(state, mx, my);
        }

        // 右键菜单已打开：处理选项点击或外部点击关闭
        if (state.menuOpen) {
            for (int[] bounds : state.menuOptions) {
                if (mx >= bounds[0] && mx <= bounds[2] && my >= bounds[1] && my <= bounds[3]) {
                    performAction(screen, bounds[4], state.menuMessage);
                    state.menuOpen = false;
                    return true;
                }
            }
            // 点击菜单外部：关闭并消费
            state.menuOpen = false;
            return true;
        }

        // 左键点击悬停按钮
        if (button == 0 && state.hoverMessage != null) {
            for (int[] bounds : state.hoverButtons) {
                if (mx >= bounds[0] && mx <= bounds[2] && my >= bounds[1] && my <= bounds[3]) {
                    performAction(screen, bounds[4], state.hoverMessage);
                    return true;
                }
            }
        }

        // 右键点击玩家消息：打开菜单
        if (button == 1 && LcpConfig.getInstance().isEnableRightClickMenu()) {
            GuiMessage msg = ChatMessageLocator.findMessageAtMouse(mx, my);
            if (msg != null) {
                MessageInfo info = MessageInfo.from(msg);
                if (info != null && info.isPlayerMessage()) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.player != null) {
                        boolean isSelf = mc.player.getName().getString().equals(info.getSender());
                        state.menuOpen = true;
                        state.menuX = mx;
                        state.menuY = my;
                        state.menuMessage = info;
                        state.menuMessageIsSelf = isSelf;
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static void performAction(ChatScreen screen, int action, MessageInfo info) {
        ChatScreenState state = getState(screen);
        switch (action) {
            case ChatScreenState.ACTION_COPY -> MessageActions.copy(info.getContent());
            case ChatScreenState.ACTION_REPLY -> {
                if (state != null) {
                    state.replyingTo = info;
                    state.replyingToMessageId = MessageJumpManager.getInstance().getMessageId(info.getMessage());
                }
            }
            case ChatScreenState.ACTION_PLUS_ONE -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player == null) return;
                boolean isSelf = mc.player.getName().getString().equals(info.getSender());
                if (isSelf && !LcpConfig.getInstance().isPlusOneSelf()) return;
                screen.handleChatInput(info.getContent(), true);
            }
        }
    }

    // ==================== QQ-style Reply Bar ====================

    private static void drawReplyBar(ChatScreen screen, GuiGraphics g, ChatScreenState state, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.gui.Font font = mc.font;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int lineHeight = 12;
        int barHeight = lineHeight * 2 + 6; // 两行：标题 + 内容预览

        EditBox input = ((ChatScreenAccess) screen).lcp$getInput();
        int barY = input.getY() - barHeight - 2;
        int paddingX = 4;
        int closeSize = 10;

        // 第一行：回复标题
        String titleText = Component.translatable("light_chat_patch.reply.replying_to",
                state.replyingTo.getSender()).getString();
        
        // 第二行：消息内容预览（截断）
        String contentPreview = state.replyingTo.getContent();
        int maxContentWidth = screenWidth - 20;
        if (font.width(contentPreview) > maxContentWidth) {
            contentPreview = font.substrByWidth(Component.literal(contentPreview), maxContentWidth - 10).getString() + "...";
        }

        int titleWidth = font.width(titleText);
        int contentWidth = font.width(contentPreview);
        int barWidth = Math.min(Math.max(titleWidth, contentWidth) + paddingX * 2 + closeSize + 4, screenWidth - 8);
        int barX = 4;

        // 背景
        boolean hovered = mouseX >= barX && mouseX <= barX + barWidth
                && mouseY >= barY && mouseY <= barY + barHeight;
        int bgColor = hovered ? 0xD0336699 : 0xD0225577;
        g.fill(barX, barY, barX + barWidth, barY + barHeight, bgColor);

        // 第一行：标题
        g.drawString(font, titleText, barX + paddingX, barY + 2, 0xFFFFFFFF);

        // 第二行：内容预览（灰色）
        g.drawString(font, contentPreview, barX + paddingX, barY + lineHeight + 4, 0xFFAAAAAA);

        // × 按钮区域（在右上角）
        int closeX = barX + barWidth - closeSize - 2;
        int closeY = barY + 2;
        boolean closeHovered = mouseX >= closeX && mouseX <= closeX + closeSize
                && mouseY >= closeY && mouseY <= closeY + closeSize;
        int closeColor = closeHovered ? 0xFFFF6666 : 0xFFFFFFFF;
        g.drawString(font, "x", closeX, closeY, closeColor);

        state.replyBarBounds = new int[]{barX, barY, barX + barWidth, barY + barHeight};
        state.replyBarCloseBounds = new int[]{closeX, closeY, closeX + closeSize, closeY + closeSize};
    }

    private static void drawHighlightedMessage(GuiGraphics g, ChatScreenState state) {
        if (state.highlightedMessageId == null || System.currentTimeMillis() > state.highlightEndTime) {
            return;
        }

        GuiMessage target = MessageJumpManager.getInstance().findMessageById(state.highlightedMessageId);
        if (target == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();
        List<GuiMessage.Line> trimmed = access.lcp$getTrimmedMessages();

        int messageIndex = -1;
        for (int i = 0; i < allMessages.size(); i++) {
            if (allMessages.get(i) == target) {
                messageIndex = i;
                break;
            }
        }

        if (messageIndex < 0) {
            return;
        }

        int trimmedStartIndex = 0;
        int trimmedEndIndex = -1;
        int messageCount = 0;

        for (int i = 0; i < trimmed.size(); i++) {
            if (messageCount == messageIndex) {
                if (trimmedStartIndex == 0 && i > 0) {
                    trimmedStartIndex = i;
                }
            }
            if (trimmed.get(i).endOfEntry()) {
                if (messageCount == messageIndex) {
                    trimmedEndIndex = i;
                    break;
                }
                messageCount++;
            }
        }

        if (trimmedEndIndex < 0) {
            return;
        }

        if (trimmedStartIndex == 0) {
            int prevCount = 0;
            for (int i = 0; i < trimmed.size() && i <= trimmedEndIndex; i++) {
                if (trimmed.get(i).endOfEntry()) {
                    if (prevCount == messageIndex - 1) {
                        trimmedStartIndex = i + 1;
                        break;
                    }
                    prevCount++;
                }
            }
            if (trimmedStartIndex == 0) {
                trimmedStartIndex = 0;
            }
        }

        int scrollbarPos = access.lcp$getChatScrollbarPos();
        int startVisualIndex = trimmedStartIndex - scrollbarPos;
        int endVisualIndex = trimmedEndIndex - scrollbarPos;

        if (endVisualIndex < 0 || startVisualIndex >= trimmed.size()) {
            return;
        }

        startVisualIndex = Math.max(0, startVisualIndex);
        endVisualIndex = Math.min(trimmed.size() - 1, endVisualIndex);

        double chatScale = mc.options.chatScale().get();
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int chatBottom = Mth.floor((screenHeight - 40) / (float) chatScale);
        double chatLineSpacing = mc.options.chatLineSpacing().get();
        int entryHeight = Math.max(1, (int) (9.0 * (1.0 + chatLineSpacing)));

        int topY = (int) ((chatBottom - (startVisualIndex + 1) * entryHeight) * chatScale);
        int bottomY = (int) ((chatBottom - endVisualIndex * entryHeight) * chatScale);

        int leftX = 4;
        int rightX = (int) (chatScale * (Math.ceil((Mth.floor(280.0 * chatScale + 40.0)) / chatScale) + 4));

        float alpha = 1.0f;
        long remaining = state.highlightEndTime - System.currentTimeMillis();
        if (remaining < 1000) {
            alpha = remaining / 1000.0f;
        }

        int color = (int) (alpha * 100) << 24 | 66 << 16 | 134 << 8 | 244;
        g.fill(leftX, topY, rightX, bottomY, color);
    }

    private static void handleReplyBarClick(ChatScreenState state, int mouseX, int mouseY) {
        if (!LcpConfig.getInstance().isClickReplyToJump()) {
            return;
        }

        int[] barBounds = state.replyBarBounds;
        if (barBounds == null || state.replyingToMessageId == null) {
            return;
        }

        boolean clickedInBar = mouseX >= barBounds[0] && mouseX <= barBounds[2]
                && mouseY >= barBounds[1] && mouseY <= barBounds[3];
        if (!clickedInBar) {
            return;
        }

        int[] closeBounds = state.replyBarCloseBounds;
        if (closeBounds != null) {
            boolean clickedClose = mouseX >= closeBounds[0] && mouseX <= closeBounds[2]
                    && mouseY >= closeBounds[1] && mouseY <= closeBounds[3];
            if (clickedClose) {
                return;
            }
        }

        GuiMessage target = MessageJumpManager.getInstance().findMessageById(state.replyingToMessageId);
        if (target != null) {
            boolean jumped = MessageJumpManager.getInstance().jumpToMessage(target);
            if (jumped) {
                state.highlightedMessageId = state.replyingToMessageId;
                state.highlightEndTime = System.currentTimeMillis() + 3000;
            }
        }
    }

    // ==================== Chat Message Limit ====================

    private static void trimChatMessages() {
        int max = LcpConfig.getInstance().getChatMaxVisibleMessages();
        if (max <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;

        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage.Line> trimmed = access.lcp$getTrimmedMessages();
        List<GuiMessage> all = access.lcp$getAllMessages();

        while (trimmed.size() > max) {
            trimmed.remove(0);
        }
        while (all.size() > max) {
            all.remove(0);
        }
    }

    // ==================== ControlsScreen: 添加配置按钮 ====================

    /**
     * 在 ControlsScreen（按键控制）初始化时添加配置按钮。
     * 通过 OptionsList.addSmall 添加到内容区，与鼠标设置/按键绑定等子按钮对齐。
     */
    public static void onControlsScreenInit(Screen screen) {
        Button button = Button.builder(
                        Component.translatable("light_chat_patch.options.button"),
                        b -> Minecraft.getInstance().setScreen(new LightChatPatchConfigScreen(screen)))
                .build();

        OptionsSubScreenAccess access = (OptionsSubScreenAccess) screen;
        access.lcp$getList().addSmall(button, null);
    }
}
