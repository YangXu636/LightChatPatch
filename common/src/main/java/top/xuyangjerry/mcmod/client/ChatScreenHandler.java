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
import top.xuyangjerry.mcmod.LightChatPatch;
import top.xuyangjerry.mcmod.client.message.ReplyTracker;
import top.xuyangjerry.mcmod.client.screen.ForwardTargetScreen;
import top.xuyangjerry.mcmod.client.screen.LightChatPatchConfigScreen;
import top.xuyangjerry.mcmod.client.screen.PlayerFilterScreen;
import top.xuyangjerry.mcmod.config.ChatHistoryView;
import top.xuyangjerry.mcmod.config.LcpConfig;
import top.xuyangjerry.mcmod.history.ChatBoxHistoryManager;
import top.xuyangjerry.mcmod.history.ChatDraftManager;
import top.xuyangjerry.mcmod.history.ChatHistoryManager;
import top.xuyangjerry.mcmod.mixin.client.ChatComponentAccess;
import top.xuyangjerry.mcmod.mixin.client.ChatScreenAccess;
import top.xuyangjerry.mcmod.mixin.client.OptionsSubScreenAccess;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 聊天屏幕核心逻辑处理器。从原 ChatScreenMixin 提取，改为普通 Java 类。
 * 由 Fabric API 事件 / NeoForge 事件调用，不再依赖 @Inject mixin。
 */
public final class ChatScreenHandler {

    private static final WeakHashMap<ChatScreen, ChatScreenState> STATES = new WeakHashMap<>();
    private static boolean wasLevelNull = true;
    private static boolean chatBoxHistoryLoaded = false;
    private static boolean justSentMessage = false;

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
        if (mc.level != null) {
            if (wasLevelNull) {
                wasLevelNull = false;
                chatBoxHistoryLoaded = false;
                LightChatPatch.LOGGER.info("[ChatScreenHandler] Entered world, preserve={}", LcpConfig.getInstance().isPreserveChatHistory());
                if (LcpConfig.getInstance().isPreserveChatHistory()) {
                    ChatHistoryManager.loadToRecentChat();
                    ChatBoxHistoryManager.loadChatBoxHistory();
                    // 检查是否加载成功（worldId 可能尚未就绪）
                    chatBoxHistoryLoaded = ChatBoxHistoryManager.isLoadable();
                }
            } else if (!chatBoxHistoryLoaded && LcpConfig.getInstance().isPreserveChatHistory()) {
                // 重试加载聊天框历史（首次进入世界时 worldId 可能未就绪）
                ChatBoxHistoryManager.loadChatBoxHistory();
                chatBoxHistoryLoaded = ChatBoxHistoryManager.isLoadable();
            }
        } else if (mc.level == null) {
            if (!wasLevelNull && LcpConfig.getInstance().isPreserveChatHistory()) {
                ChatBoxHistoryManager.saveChatBoxHistory();
            }
            wasLevelNull = true;
            chatBoxHistoryLoaded = false;
        }
    }

    /**
     * 在 ChatScreen 初始化时调用。返回 true 表示首次初始化（调用方应注册 per-screen 事件）。
     */
    public static boolean onChatScreenInit(ChatScreen screen) {
        // 每次初始化都设置输入框最大字符长度（re-init 可能重建 EditBox）
        int maxLen = LcpConfig.getInstance().getChatMaxLength();
        if (maxLen != 256) {
            EditBox input = ((ChatScreenAccess) screen).lcp$getInput();
            if (input != null) {
                input.setMaxLength(maxLen);
            }
        }

        ChatScreenState state = STATES.get(screen);
        if (state != null) {
            return false; // 已初始化，是 re-init
        }

        state = new ChatScreenState();
        STATES.put(screen, state);

        // 加载历史到 recentChat
        LightChatPatch.LOGGER.info("[ChatScreenHandler] ChatScreen init, preserve={}", LcpConfig.getInstance().isPreserveChatHistory());
        if (LcpConfig.getInstance().isPreserveChatHistory()) {
            ChatHistoryManager.loadToRecentChat();
            // 重置 historyPos 到末尾，确保 Up 键能正确导航到最近加载的历史
            ((ChatScreenAccess) screen).lcp$setHistoryPos(
                    Minecraft.getInstance().gui.getChat().getRecentChat().size());
            // 也加载聊天框历史（onClientTick 可能因 worldId 未就绪而失败）
            if (!chatBoxHistoryLoaded) {
                ChatBoxHistoryManager.loadChatBoxHistory();
                chatBoxHistoryLoaded = true;
            }
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
                // onChatScreenInit 在 ChatScreen.init() 之后执行，EditBox 已创建
                // 直接设置 EditBox 的值，而非仅设置 initial 字段
                EditBox input = access.lcp$getInput();
                if (input != null) {
                    input.setValue(draft);
                }
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

        // Escape 关闭右键菜单、退出选择模式或取消回复
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (state.menuOpen) {
                state.menuOpen = false;
                return true;
            }
            if (state.isRangeSelectMode) {
                state.exitSelectionMode();
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
                    String truncatedContent = truncateReplyContent(state.replyingTo.getContent());
                    String replyText = Component.translatable("light_chat_patch.reply.format",
                            state.replyingTo.getSender(), truncatedContent, input).getString();
                    ((ChatScreenAccess) screen).lcp$getInput().setValue(replyText);

                    // 设置 pendingReply，等待服务端回环后建立关联
                    if (state.replyingToMessageId != null) {
                        ReplyTracker.getInstance().setPendingReply(
                                new ReplyTracker.PendingReply(
                                        state.replyingToMessageId,
                                        state.replyingTo.getSender(),
                                        state.replyingTo.getContent(),
                                        replyText
                                )
                        );
                    }
                }
                state.replyingTo = null;
                state.replyingToMessageId = null;
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
                    // 在交给原版处理前，记录玩家手动发送的内容（包括指令）
                    String text = input.getValue();
                    if (text != null && !text.isBlank()) {
                        ChatHistoryManager.addMessage(
                                LcpConfig.getInstance().getChatHistoryView(), text);
                        justSentMessage = true;
                    }
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
     * 在 ChatScreen 关闭时调用。保存草稿和聊天框历史。
     * 发送历史已在按发送键时记录，不再从 recentChat 保存。
     */
    public static void onChatScreenRemove(ChatScreen screen) {
        // 不手动移除 STATES，让 WeakHashMap 在 ChatScreen 被 GC 时自动清理
        // 这样从子界面（RangeActionScreen/PlayerFilterScreen）返回时状态得以保留

        if (LcpConfig.getInstance().isSaveDraftOnClose()) {
            if (justSentMessage) {
                // 发送消息导致的关闭，清除草稿
                ChatDraftManager.clearDraft();
                justSentMessage = false;
            } else {
                // 非发送导致的关闭（如死亡/退出），保存草稿
                String inputText = getInputValue(screen);
                if (inputText != null && !inputText.isBlank()) {
                    ChatDraftManager.saveDraft(inputText);
                } else {
                    ChatDraftManager.clearDraft();
                }
            }
        }

        if (LcpConfig.getInstance().isPreserveChatHistory()) {
            ChatBoxHistoryManager.saveChatBoxHistory();
        }
    }

    private static String getInputValue(ChatScreen screen) {
        try {
            EditBox input = ((ChatScreenAccess) screen).lcp$getInput();
            return input.getValue();
        } catch (Exception e) {
            return null;
        }
    }

    private static void trackExistingMessages() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        ChatComponent chat = mc.gui.getChat();
        if (chat == null) return;
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();
        MessageJumpManager jumpMgr = MessageJumpManager.getInstance();
        ReplyTracker replyTracker = ReplyTracker.getInstance();
        for (GuiMessage msg : allMessages) {
            String id = jumpMgr.getMessageId(msg);
            replyTracker.onNewMessage(msg, id);
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

        // 选择模式：绘制选择覆盖层 + 确认按钮
        if (state.isRangeSelectMode) {
            drawSelectionModeOverlay(screen, g, state, mouseX, mouseY);
        }

        // 右键菜单优先
        if (state.menuOpen) {
            drawMenu(g, mouseX, mouseY, state);
            return;
        }

        // 选择模式下不显示常规悬停按钮
        if (state.isRangeSelectMode) {
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
        if (info == null) {
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
        String viewOriginalLabel = Component.translatable("light_chat_patch.action.view_original").getString();
        String forwardLabel = Component.translatable("light_chat_patch.action.forward").getString();

        // 检查悬停消息是否为回复消息
        boolean isReplyMessage = ReplyTracker.getInstance().getOriginalMessageId(state.hoverMessage.getMessage()) != null;

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

        if (isReplyMessage) {
            labels.add(viewOriginalLabel);
            widths.add(font.width(viewOriginalLabel) + 8);
            actions.add(ChatScreenState.ACTION_VIEW_ORIGINAL);
        }

        labels.add(forwardLabel);
        widths.add(font.width(forwardLabel) + 8);
        actions.add(ChatScreenState.ACTION_FORWARD);

        // 计算按钮组总宽度
        int totalWidth = 0;
        for (int w : widths) {
            totalWidth += w;
        }
        if (!widths.isEmpty()) {
            totalWidth += (widths.size() - 1) * gap;
        }

        // 悬浮菜单始终靠右：如果超出屏幕右边界，则靠屏幕右边缘
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        if (x + totalWidth > screenWidth) {
            x = screenWidth - totalWidth;
        }
        if (x < 0) {
            x = 0;
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

        // 右键菜单在鼠标点击位置
        int menuX = state.menuX;
        int menuY = state.menuY;
        int optionHeight = 16;

        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        // 选择模式下的右键菜单
        if (state.isRangeSelectMode) {
            java.util.List<String> labels = new java.util.ArrayList<>();
            java.util.List<Integer> actions = new java.util.ArrayList<>();

            // 有范围选择时，显示范围操作菜单
            if (state.rangeStart != null && state.rangeEnd != null) {
                String rangeActionKey = LcpConfig.getInstance().isRangeSelectToggle()
                        ? "light_chat_patch.action.toggle_range"
                        : "light_chat_patch.action.select_all_in_range";
                String rangeActionLabel = Component.translatable(rangeActionKey).getString();
                String selectBySenderLabel = Component.translatable("light_chat_patch.action.select_by_sender").getString();
                String clearRangeLabel = Component.translatable("light_chat_patch.action.clear_range").getString();

                labels.add(rangeActionLabel);
                actions.add(ChatScreenState.ACTION_SELECT_ALL_IN_RANGE);

                labels.add(selectBySenderLabel);
                actions.add(ChatScreenState.ACTION_SELECT_BY_SENDER);

                labels.add(clearRangeLabel);
                actions.add(ChatScreenState.ACTION_CLEAR_RANGE);
            } else {
                // 无范围选择时，显示常规选择菜单
                String setStartLabel = Component.translatable("light_chat_patch.action.set_range_start").getString();
                String setEndLabel = Component.translatable("light_chat_patch.action.set_range_end").getString();
                String selectBySenderLabel = Component.translatable("light_chat_patch.action.select_by_sender").getString();
                int selectedCount = getAllSelectedMessages(state).size();
                String confirmLabel = Component.translatable("light_chat_patch.select.confirm", selectedCount).getString();
                String exitLabel = Component.translatable("light_chat_patch.action.exit_select_mode").getString();

                labels.add(setStartLabel);
                actions.add(ChatScreenState.ACTION_SET_RANGE_START);

                labels.add(setEndLabel);
                actions.add(ChatScreenState.ACTION_SET_RANGE_END);

                labels.add(selectBySenderLabel);
                actions.add(ChatScreenState.ACTION_SELECT_BY_SENDER);

                labels.add(confirmLabel);
                actions.add(ChatScreenState.ACTION_CONFIRM_FORWARD);

                labels.add(exitLabel);
                actions.add(ChatScreenState.ACTION_EXIT_SELECT_MODE);
            }

            int menuWidth = 80;
            for (String label : labels) {
                int w = font.width(label) + 12;
                if (w > menuWidth) menuWidth = w;
            }
            int menuHeight = labels.size() * optionHeight;

            if (menuX + menuWidth > screenWidth) menuX = screenWidth - menuWidth;
            if (menuX < 0) menuX = 0;
            if (menuY + menuHeight > screenHeight) menuY = screenHeight - menuHeight;
            if (menuY < 0) menuY = 0;

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
            return;
        }

        // 常规右键菜单
        boolean showPlusOne = !state.menuMessageIsSelf || LcpConfig.getInstance().isPlusOneSelf();
        String copyLabel = Component.translatable("light_chat_patch.action.copy").getString();
        String replyLabel = Component.translatable("light_chat_patch.action.reply").getString();
        String plusOneLabel = Component.translatable("light_chat_patch.action.plus_one").getString();
        String viewOriginalLabel = Component.translatable("light_chat_patch.action.view_original").getString();
        String forwardLabel = Component.translatable("light_chat_patch.action.forward").getString();

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

        if (state.menuMessageIsReply) {
            labels.add(viewOriginalLabel);
            actions.add(ChatScreenState.ACTION_VIEW_ORIGINAL);
        }

        labels.add(forwardLabel);
        actions.add(ChatScreenState.ACTION_FORWARD);

        int menuWidth = 80;
        for (String label : labels) {
            int w = font.width(label) + 12;
            if (w > menuWidth) menuWidth = w;
        }
        int menuHeight = labels.size() * optionHeight;

        // 确保菜单完全在屏幕内
        if (menuX + menuWidth > screenWidth) menuX = screenWidth - menuWidth;
        if (menuX < 0) menuX = 0;
        if (menuY + menuHeight > screenHeight) menuY = screenHeight - menuHeight;
        if (menuY < 0) menuY = 0;

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

        // 选择模式下的点击处理
        if (state.isRangeSelectMode) {
            // 点击确认转发按钮
            if (state.confirmButtonBounds != null) {
                int[] b = state.confirmButtonBounds;
                if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                    confirmForward(screen, state);
                    return true;
                }
            }

            // 右键：打开选择模式菜单
            if (button == 1) {
                GuiMessage msg = ChatMessageLocator.findMessageAtMouse(mx, my);
                if (msg != null) {
                    MessageInfo info = MessageInfo.from(msg);
                    if (info != null) {
                        state.menuOpen = true;
                        state.menuX = mx;
                        state.menuY = my;
                        state.menuMessage = info;
                        return true;
                    }
                }
                // 右键空白处也打开菜单（不需要对应消息）
                state.menuOpen = true;
                state.menuX = mx;
                state.menuY = my;
                state.menuMessage = null;
                return true;
            }

            // 左键：切换单条消息选中状态
            if (button == 0) {
                GuiMessage msg = ChatMessageLocator.findMessageAtMouse(mx, my);
                if (msg != null) {
                    MessageInfo info = MessageInfo.from(msg);
                    if (info != null) {
                        if (state.selectedMessages.contains(msg)) {
                            state.selectedMessages.remove(msg);
                        } else {
                            state.selectedMessages.add(msg);
                        }
                        return true;
                    }
                }
            }
            return true; // 选择模式下消费所有点击
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
                        boolean isReply = ReplyTracker.getInstance().getOriginalMessageId(msg) != null;
                        state.menuOpen = true;
                        state.menuX = mx;
                        state.menuY = my;
                        state.menuMessage = info;
                        state.menuMessageIsSelf = isSelf;
                        state.menuMessageIsReply = isReply;
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
            case ChatScreenState.ACTION_VIEW_ORIGINAL -> {
                if (state != null) {
                    String originalId = ReplyTracker.getInstance().getOriginalMessageId(info.getMessage());
                    if (originalId != null) {
                        GuiMessage original = MessageJumpManager.getInstance().findMessageById(originalId);
                        if (original != null) {
                            boolean jumped = MessageJumpManager.getInstance().jumpToMessage(original);
                            if (jumped) {
                                state.highlightedMessageId = originalId;
                                state.highlightEndTime = System.currentTimeMillis() + 3000;
                            }
                        }
                    }
                }
            }
            case ChatScreenState.ACTION_FORWARD -> {
                // 进入选择模式，反转选中当前消息
                if (state != null) {
                    if (!state.isRangeSelectMode) {
                        state.isRangeSelectMode = true;
                        state.selectedMessages.clear();
                        state.rangeStart = null;
                        state.rangeEnd = null;
                    }
                    if (info != null) {
                        if (state.selectedMessages.contains(info.getMessage())) {
                            state.selectedMessages.remove(info.getMessage());
                        } else {
                            state.selectedMessages.add(info.getMessage());
                        }
                    }
                }
            }
            case ChatScreenState.ACTION_SET_RANGE_START -> {
                if (state != null && info != null) {
                    state.rangeStart = info.getMessage();
                    // 设置起点后检查是否已形成选区（起点和终点都有）
                    tryOpenRangeActionScreen(screen, state);
                }
            }
            case ChatScreenState.ACTION_SET_RANGE_END -> {
                if (state != null && info != null) {
                    state.rangeEnd = info.getMessage();
                    // 设置终点后检查是否已形成选区
                    tryOpenRangeActionScreen(screen, state);
                }
            }
            case ChatScreenState.ACTION_CLEAR_RANGE -> {
                if (state != null) {
                    state.rangeStart = null;
                    state.rangeEnd = null;
                }
            }
            case ChatScreenState.ACTION_SELECT_ALL_IN_RANGE -> {
                if (state != null) {
                    applyRangeSelection(state);
                }
            }
            case ChatScreenState.ACTION_SELECT_BY_SENDER -> {
                if (state != null) {
                    Minecraft mc = Minecraft.getInstance();
                    // 如果有范围选择，只收集范围内发送者
                    List<GuiMessage> targetMessages;
                    if (state.rangeStart != null && state.rangeEnd != null) {
                        targetMessages = getMessagesInRange(state);
                    } else {
                        ChatComponent chat = mc.gui.getChat();
                        ChatComponentAccess access = (ChatComponentAccess) chat;
                        targetMessages = access.lcp$getAllMessages();
                    }
                    java.util.Set<String> senders = new java.util.LinkedHashSet<>();
                    for (GuiMessage m : targetMessages) {
                        MessageInfo mi = MessageInfo.from(m);
                        if (mi != null) {
                            senders.add(mi.getSender());
                        }
                    }
                    if (!senders.isEmpty()) {
                        mc.setScreen(new PlayerFilterScreen(mc.screen, state, new ArrayList<>(senders)));
                    }
                }
            }
            case ChatScreenState.ACTION_CONFIRM_FORWARD -> {
                if (state != null) {
                    confirmForward(screen, state);
                }
            }
            case ChatScreenState.ACTION_EXIT_SELECT_MODE -> {
                if (state != null) {
                    state.exitSelectionMode();
                }
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

        // 第二行：消息内容预览（超长时按字符数头尾截断，中间用省略号代替）
        String contentPreview = truncateReplyContent(state.replyingTo.getContent());

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

    /**
     * 按字符数截断回复内容：超过阈值时保留头尾，中间用省略号代替。
     * 阈值和头尾保留数从配置读取。
     */
    private static String truncateReplyContent(String content) {
        if (content == null || content.isEmpty()) return "";
        LcpConfig config = LcpConfig.getInstance();
        int threshold = config.getReplyTruncateThreshold();
        if (content.length() <= threshold) return content;
        int head = config.getReplyTruncateHead();
        int tail = config.getReplyTruncateTail();
        if (content.length() <= head + tail) return content;
        return content.substring(0, head) + "..." + content.substring(content.length() - tail);
    }

    private static void drawHighlightedMessage(GuiGraphics g, ChatScreenState state) {
        if (state.highlightedMessageId == null || System.currentTimeMillis() > state.highlightEndTime) {
            return;
        }

        GuiMessage target = MessageJumpManager.getInstance().findMessageById(state.highlightedMessageId);
        if (target == null) {
            return;
        }

        // 使用与转发选择模式一致的定位方式（基于 addedTime）
        int[] range = ChatMessageLocator.getMessageTrimmedRange(target);
        if (range == null) return;
        int tStart = range[0];
        int tEnd = range[1];

        Minecraft mc = Minecraft.getInstance();
        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        int scrollbarPos = access.lcp$getChatScrollbarPos();

        double chatScale = mc.options.chatScale().get();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int chatBottom = Mth.floor((screenHeight - 40) / (float) chatScale);
        double chatLineSpacing = mc.options.chatLineSpacing().get();
        int entryHeight = Math.max(1, (int) (9.0 * (1.0 + chatLineSpacing)));
        double chatHeightFocused = mc.options.chatHeightFocused().get();
        int chatHeight = Mth.floor(160.0 * chatHeightFocused + 20.0);
        int linesPerPage = chatHeight / entryHeight;

        int visStart = tStart - scrollbarPos;
        int visEnd = tEnd - scrollbarPos;

        if (visStart >= linesPerPage || visEnd < 0) return;

        int topVisual = Math.max(0, visStart);
        int bottomVisual = Math.min(linesPerPage - 1, visEnd);

        // topVisual 是较小行索引（屏幕下方，Y值大）
        // bottomVisual 是较大行索引（屏幕上方，Y值小）
        // 填充范围：从最高行的顶部（小Y）到最低行的底部（大Y）
        int screenTopY = (int) ((chatBottom - (bottomVisual + 1) * entryHeight) * chatScale);
        int screenBottomY = (int) ((chatBottom - topVisual * entryHeight) * chatScale);

        int leftX = 4;
        int rightX = ChatMessageLocator.getChatRightEdge();

        float alpha = 1.0f;
        long remaining = state.highlightEndTime - System.currentTimeMillis();
        if (remaining < 1000) {
            alpha = remaining / 1000.0f;
        }

        int color = (int) (alpha * 100) << 24 | 66 << 16 | 134 << 8 | 244;
        g.fill(leftX, screenTopY, rightX, screenBottomY, color);
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

    // ==================== Selection Mode (多选转发) ====================

    private static void drawSelectionModeOverlay(ChatScreen screen, GuiGraphics g, ChatScreenState state, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.gui.Font font = mc.font;
        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();
        List<GuiMessage.Line> trimmed = access.lcp$getTrimmedMessages();

        double chatScale = mc.options.chatScale().get();
        if (chatScale <= 0.0) return;

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int chatBottom = Mth.floor((screenHeight - 40) / (float) chatScale);
        double chatLineSpacing = mc.options.chatLineSpacing().get();
        int entryHeight = Math.max(1, (int) (9.0 * (1.0 + chatLineSpacing)));
        int scrollbarPos = access.lcp$getChatScrollbarPos();

        int chatRightEdge = ChatMessageLocator.getChatRightEdge();
        int chatLeftEdge = 0;

        double chatHeightFocused = mc.options.chatHeightFocused().get();
        int chatHeight = Mth.floor(160.0 * chatHeightFocused + 20.0);
        int linesPerPage = chatHeight / entryHeight;

        // 建立 addedTime -> GuiMessage 的映射（时间戳唯一标识消息）
        java.util.Map<Integer, GuiMessage> timeToMessage = new java.util.HashMap<>();
        for (GuiMessage msg : allMessages) {
            timeToMessage.put(msg.addedTime(), msg);
        }

        // 建立 addedTime -> {startLine, endLine} 的映射（按时间戳分组 trimmed lines）
        java.util.Map<Integer, int[]> timeToRange = new java.util.HashMap<>();
        if (!trimmed.isEmpty()) {
            int currentTime = trimmed.get(0).addedTime();
            int rangeStart = 0;
            for (int i = 0; i < trimmed.size(); i++) {
                GuiMessage.Line line = trimmed.get(i);
                if (line.addedTime() != currentTime) {
                    timeToRange.put(currentTime, new int[]{rangeStart, i - 1});
                    currentTime = line.addedTime();
                    rangeStart = i;
                }
            }
            timeToRange.put(currentTime, new int[]{rangeStart, trimmed.size() - 1});
        }

        // 收集需要渲染的消息：已选消息 + rangeStart/rangeEnd（确保标记始终显示）
        Set<GuiMessage> messagesToRender = new LinkedHashSet<>(state.selectedMessages);
        if (state.rangeStart != null) messagesToRender.add(state.rangeStart);
        if (state.rangeEnd != null) messagesToRender.add(state.rangeEnd);

        for (GuiMessage msg : messagesToRender) {
            int msgTime = msg.addedTime();
            int[] range = timeToRange.get(msgTime);
            if (range == null) continue;

            int tStart = range[0];
            int tEnd = range[1];

            int visStart = tStart - scrollbarPos;
            int visEnd = tEnd - scrollbarPos;

            if (visStart >= linesPerPage || visEnd < 0) continue;

            int topVisual = Math.max(0, visStart);
            int bottomVisual = Math.min(linesPerPage - 1, visEnd);

            // topVisual 是较小行索引（屏幕下方，Y值大）
            // bottomVisual 是较大行索引（屏幕上方，Y值小）
            // 填充范围：从最高行的顶部（小Y）到最低行的底部（大Y）
            int screenTopY = (int) ((chatBottom - (bottomVisual + 1) * entryHeight) * chatScale);
            int screenBottomY = (int) ((chatBottom - topVisual * entryHeight) * chatScale);

            boolean isSelected = state.selectedMessages.contains(msg);
            if (isSelected) {
                g.fill(chatLeftEdge, screenTopY, chatRightEdge, screenBottomY, 0x500000CC);
            }

            boolean isRangeStart = state.rangeStart != null && msg.addedTime() == state.rangeStart.addedTime();
            boolean isRangeEnd = state.rangeEnd != null && msg.addedTime() == state.rangeEnd.addedTime();
            if (isRangeStart) {
                g.fill(chatLeftEdge, screenTopY, chatLeftEdge + 3, screenBottomY, 0xFF00CC00);
            }
            if (isRangeEnd) {
                g.fill(chatLeftEdge, screenTopY, chatLeftEdge + 3, screenBottomY, 0xFFCC0000);
            }
        }

        // 绘制顶部提示
        String hint = Component.translatable("light_chat_patch.select.mode_hint").getString();
        int hintWidth = font.width(hint) + 8;
        int hintX = (mc.getWindow().getGuiScaledWidth() - hintWidth) / 2;
        int hintY = 4;
        g.fill(hintX, hintY, hintX + hintWidth, hintY + 14, 0xB0000000);
        g.drawCenteredString(font, hint, mc.getWindow().getGuiScaledWidth() / 2, hintY + 3, 0xFFFFFFFF);

        // 绘制确认转发按钮
        int selectedCount = getAllSelectedMessages(state).size();
        String confirmLabel = Component.translatable("light_chat_patch.select.confirm", selectedCount).getString();
        int btnWidth = font.width(confirmLabel) + 16;
        int btnHeight = 14;
        EditBox input = ((ChatScreenAccess) screen).lcp$getInput();
        int btnX = mc.getWindow().getGuiScaledWidth() - btnWidth - 4;
        int btnY = input.getY() - btnHeight - 2;

        boolean btnHovered = mouseX >= btnX && mouseX <= btnX + btnWidth && mouseY >= btnY && mouseY <= btnY + btnHeight;
        int bgColor = btnHovered ? 0xD0448844 : 0xB0226644;
        g.fill(btnX, btnY, btnX + btnWidth, btnY + btnHeight, bgColor);
        g.drawCenteredString(font, confirmLabel, btnX + btnWidth / 2, btnY + 2, 0xFFFFFFFF);
        state.confirmButtonBounds = new int[]{btnX, btnY, btnX + btnWidth, btnY + btnHeight};
    }

    /**
     * 范围选择操作：根据配置决定全选还是反转
     * @param state 状态
     */
    public static void applyRangeSelection(ChatScreenState state) {
        if (state.rangeStart == null || state.rangeEnd == null) return;

        normalizeRange(state);
        List<GuiMessage> messagesInRange = getMessagesInRange(state);

        if (LcpConfig.getInstance().isRangeSelectToggle()) {
            // 反转模式
            for (GuiMessage msg : messagesInRange) {
                if (state.selectedMessages.contains(msg)) {
                    state.selectedMessages.remove(msg);
                } else {
                    state.selectedMessages.add(msg);
                }
            }
        } else {
            // 全选模式
            state.selectedMessages.addAll(messagesInRange);
        }

        // 清除范围标记
        state.rangeStart = null;
        state.rangeEnd = null;
    }

    /**
     * 尝试打开选区操作界面：当起始点和终止点都已设置时，规范化并打开 RangeActionScreen
     */
    private static void tryOpenRangeActionScreen(ChatScreen screen, ChatScreenState state) {
        if (state.rangeStart == null || state.rangeEnd == null) return;
        normalizeRange(state);
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new top.xuyangjerry.mcmod.client.screen.RangeActionScreen(screen, state));
    }

    /**
     * 规范化选区：确保 rangeStart 在 allMessages 中位于 rangeEnd 之前
     * 如果终止点在起始点上方，则对调两者身份
     */
    private static void normalizeRange(ChatScreenState state) {
        if (state.rangeStart == null || state.rangeEnd == null) return;

        Minecraft mc = Minecraft.getInstance();
        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();

        int startIdx = -1, endIdx = -1;
        int startTime = state.rangeStart.addedTime();
        int endTime = state.rangeEnd.addedTime();
        for (int i = 0; i < allMessages.size(); i++) {
            int time = allMessages.get(i).addedTime();
            if (time == startTime) startIdx = i;
            if (time == endTime) endIdx = i;
        }

        if (startIdx >= 0 && endIdx >= 0 && startIdx > endIdx) {
            GuiMessage tmp = state.rangeStart;
            state.rangeStart = state.rangeEnd;
            state.rangeEnd = tmp;
        }
    }

    /**
     * 获取范围内的消息列表
     */
    public static List<GuiMessage> getMessagesInRange(ChatScreenState state) {
        List<GuiMessage> result = new ArrayList<>();
        if (state.rangeStart == null || state.rangeEnd == null) return result;

        Minecraft mc = Minecraft.getInstance();
        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();

        int startIdx = -1, endIdx = -1;
        int rangeStartTime = state.rangeStart.addedTime();
        int rangeEndTime = state.rangeEnd.addedTime();
        for (int i = 0; i < allMessages.size(); i++) {
            int time = allMessages.get(i).addedTime();
            if (time == rangeStartTime) startIdx = i;
            if (time == rangeEndTime) endIdx = i;
        }
        if (startIdx < 0 || endIdx < 0) return result;

        if (startIdx > endIdx) {
            int tmp = startIdx;
            startIdx = endIdx;
            endIdx = tmp;
        }

        for (int i = startIdx; i <= endIdx; i++) {
            result.add(allMessages.get(i));
        }
        return result;
    }

    /**
     * 获取范围选择内的消息集合（用于渲染预览）
     */
    private static Set<GuiMessage> getRangeSelectedMessages(ChatScreenState state) {
        Set<GuiMessage> result = new LinkedHashSet<>();
        if (state.rangeStart == null || state.rangeEnd == null) {
            return result;
        }

        Minecraft mc = Minecraft.getInstance();
        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();

        int startIdx = -1, endIdx = -1;
        int rangeStartTime = state.rangeStart.addedTime();
        int rangeEndTime = state.rangeEnd.addedTime();
        for (int i = 0; i < allMessages.size(); i++) {
            int time = allMessages.get(i).addedTime();
            if (time == rangeStartTime) startIdx = i;
            if (time == rangeEndTime) endIdx = i;
        }
        if (startIdx < 0 || endIdx < 0) return result;

        if (startIdx > endIdx) {
            int tmp = startIdx;
            startIdx = endIdx;
            endIdx = tmp;
        }
        for (int i = startIdx; i <= endIdx; i++) {
            result.add(allMessages.get(i));
        }
        return result;
    }

    /**
     * 获取所有选中消息，按 allMessages 中的顺序排序
     */
    private static List<GuiMessage> getAllSelectedMessages(ChatScreenState state) {
        Minecraft mc = Minecraft.getInstance();
        ChatComponent chat = mc.gui.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();

        List<GuiMessage> sorted = new ArrayList<>();
        for (GuiMessage msg : allMessages) {
            if (state.selectedMessages.contains(msg)) {
                sorted.add(msg);
            }
        }
        // allMessages 是 newest-first，转发需要 oldest-first（最早的先发）
        java.util.Collections.reverse(sorted);
        return sorted;
    }

    /**
     * 确认转发：收集所有选中消息，打开目标选择界面
     */
    private static void confirmForward(ChatScreen screen, ChatScreenState state) {
        List<GuiMessage> selected = getAllSelectedMessages(state);
        if (selected.isEmpty()) return;

        List<String[]> messages = new ArrayList<>();
        for (GuiMessage msg : selected) {
            MessageInfo info = MessageInfo.from(msg);
            if (info != null) {
                messages.add(new String[]{"<" + info.getSender() + ">", info.getContent()});
            }
        }

        if (messages.isEmpty()) return;

        state.exitSelectionMode();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.setScreen(new ForwardTargetScreen(mc.screen, messages));
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
