package top.xuyangjerry.mcmod.lcp.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import top.xuyangjerry.mcmod.lcp.client.forward.ForwardMessageManager;
import top.xuyangjerry.mcmod.lcp.client.message.ChatMessageLocator;
import top.xuyangjerry.mcmod.lcp.client.message.MessageActions;
import top.xuyangjerry.mcmod.lcp.client.message.MessageInfo;
import top.xuyangjerry.mcmod.lcp.client.message.MessageJumpManager;
import top.xuyangjerry.mcmod.lcp.client.message.MentionManager;
import top.xuyangjerry.mcmod.lcp.client.mute.MuteClientManager;
import top.xuyangjerry.mcmod.lcp.LightChatPatch;
import top.xuyangjerry.mcmod.lcp.client.nonebot.ClientImageCache;
import top.xuyangjerry.mcmod.lcp.client.nonebot.ClientNetworking;
import top.xuyangjerry.mcmod.lcp.client.message.ReplyDataManager;
import top.xuyangjerry.mcmod.lcp.client.message.ReplyTracker;
import top.xuyangjerry.mcmod.lcp.client.popup.PopupWindow;
import top.xuyangjerry.mcmod.lcp.client.popup.PopupWindowManager;
import top.xuyangjerry.mcmod.lcp.client.screen.ForwardTargetScreen;
import top.xuyangjerry.mcmod.lcp.client.screen.LightChatPatchConfigScreen;
import top.xuyangjerry.mcmod.lcp.client.screen.PlayerFilterScreen;
import top.xuyangjerry.mcmod.lcp.config.ChatHistoryView;
import top.xuyangjerry.mcmod.lcp.config.LcpConfig;
import top.xuyangjerry.mcmod.lcp.history.ChatBoxHistoryManager;
import top.xuyangjerry.mcmod.lcp.history.ChatDraftManager;
import top.xuyangjerry.mcmod.lcp.history.ChatHistoryManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import top.xuyangjerry.mcmod.lcp.network.ForwardMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.MentionMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.ReplyMessagePayload;
import top.xuyangjerry.mcmod.lcp.client.mixin.ChatComponentAccess;
import top.xuyangjerry.mcmod.lcp.client.mixin.ChatScreenAccess;
import net.minecraft.client.gui.components.OptionsList;
import top.xuyangjerry.mcmod.lcp.client.mixin.OptionsSubScreenAccess;
import top.xuyangjerry.mcmod.lcp.client.mixin.ScreenAccess;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

public final class ChatScreenHandler {

    private static final WeakHashMap<ChatScreen, ChatScreenState> STATES = new WeakHashMap<>();
    private static boolean wasLevelNull = true;
    private static boolean chatBoxHistoryLoaded = false;
    private static boolean justSentMessage = false;
    private static int pendingHistorySaveTicks = -1;
    /**
     * 回复消息填充色（ARGB），同时用于 [Reply #uuid] 标签隐藏色。
     * alpha≈40%，RGB 为蓝灰色系，与回复竖条同色系但更暗。
     */
    public static final int REPLY_FILL_COLOR = 0x66408090;

    private ChatScreenHandler() {
    }

    private static ChatScreenState getState(ChatScreen screen) {
        return STATES.get(screen);
    }

    /**
     * 请求高亮指定 UUID 的消息（3秒，带淡出效果）。
     * 供转发弹窗等外部组件调用，触发与聊天栏点击回复相同的跳转高亮效果。
     */
    public static void requestHighlight(String uuid) {
        if (uuid == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof ChatScreen screen) {
            ChatScreenState state = STATES.get(screen);
            if (state != null) {
                state.highlightedMessageId = uuid;
                state.highlightEndTime = System.currentTimeMillis() + 3000;
            }
        }
    }

    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            if (wasLevelNull) {
                wasLevelNull = false;
                chatBoxHistoryLoaded = false;
                LightChatPatch.LOGGER.info("[ChatScreenHandler] Entered world, preserve={}", LcpConfig.getInstance().isPreserveChatHistory());
                // 杩涘叆鏂颁笘鐣屾椂寮€鍚柊鐨勪細璇濓紝渚夸簬鎸?sessionId 鍖哄垎鍘嗗彶
                ChatBoxHistoryManager.startNewSession();
                if (LcpConfig.getInstance().isPreserveChatHistory()) {
                    ChatHistoryManager.loadToRecentChat();
                    ChatBoxHistoryManager.loadChatBoxHistory();
                    chatBoxHistoryLoaded = ChatBoxHistoryManager.isLoadable();
                }
            } else if (!chatBoxHistoryLoaded && LcpConfig.getInstance().isPreserveChatHistory()) {
                ChatBoxHistoryManager.loadChatBoxHistory();
                chatBoxHistoryLoaded = ChatBoxHistoryManager.isLoadable();
            }

            if (pendingHistorySaveTicks > 0) {
                pendingHistorySaveTicks--;
            } else if (pendingHistorySaveTicks == 0) {
                pendingHistorySaveTicks = -1;
                if (LcpConfig.getInstance().isPreserveChatHistory() && !ChatBoxHistoryManager.isBatchLoading()) {
                    ChatBoxHistoryManager.saveChatBoxHistory();
                }
            }

            // 分批加载历史消息：每 tick 加载一条，确保 addedTime 唯一
            if (chatBoxHistoryLoaded) {
                ChatBoxHistoryManager.tickLoadHistoryBatch();
            }
        } else if (mc.level == null) {
            if (!wasLevelNull && LcpConfig.getInstance().isPreserveChatHistory()
                    && !ChatBoxHistoryManager.isBatchLoading()) {
                ChatBoxHistoryManager.saveChatBoxHistory();
            }
            wasLevelNull = true;
            chatBoxHistoryLoaded = false;
            pendingHistorySaveTicks = -1;
        }
    }

    public static boolean onChatScreenInit(ChatScreen screen) {
        int maxLen = LcpConfig.getInstance().getChatMaxLength();
        if (maxLen != 256) {
            EditBox input = ((ChatScreenAccess) screen).lcp$getInput();
            if (input != null) {
                input.setMaxLength(maxLen);
            }
        }

        // 添加图片发送按钮（每次 init 都添加，因为 re-init 会清除之前添加的 widget）
        addImageSendButton(screen);

        ChatScreenState state = STATES.get(screen);
        if (state != null) {
            // state 已存在（从子屏幕回到 ChatScreen）：跳过首次初始化逻辑，
            // 但仍返回 true 让 per-screen 事件重新注册
            // （Fabric 在 re-init 时会清空 per-screen 事件回调，需要重新注册）
            return true;
        }

        state = new ChatScreenState();
        STATES.put(screen, state);

        LightChatPatch.LOGGER.info("[ChatScreenHandler] ChatScreen init, preserve={}", LcpConfig.getInstance().isPreserveChatHistory());
        if (LcpConfig.getInstance().isPreserveChatHistory()) {
            ChatHistoryManager.loadToRecentChat();
            ((ChatScreenAccess) screen).lcp$setHistoryPos(
                    Minecraft.getInstance().gui.hud.getChat().getRecentChat().size());
            if (!chatBoxHistoryLoaded) {
                ChatBoxHistoryManager.loadChatBoxHistory();
                chatBoxHistoryLoaded = true;
            }
        }

        trackExistingMessages();
        trimChatMessages();

        if (LcpConfig.getInstance().isSaveDraftOnClose()) {
            String draft = ChatDraftManager.loadDraft();
            if (draft != null && !draft.isEmpty()) {
                ChatScreenAccess access = (ChatScreenAccess) screen;
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

    public static boolean onChatScreenKeyPress(ChatScreen screen, int key, int scancode, int modifiers) {
        ChatScreenState state = getState(screen);
        if (state == null) {
            return false;
        }

        // @候选列表激活时，拦截导航键
        MentionManager mentionMgr = MentionManager.getInstance();
        if (mentionMgr.isActive()) {
            if (key == GLFW.GLFW_KEY_UP) {
                mentionMgr.moveUp();
                return true;
            }
            if (key == GLFW.GLFW_KEY_DOWN) {
                mentionMgr.moveDown();
                return true;
            }
            if (key == GLFW.GLFW_KEY_TAB || key == GLFW.GLFW_KEY_ENTER) {
                int atPos = mentionMgr.getAtPosition();
                String mentionText = mentionMgr.confirm();
                if (mentionText != null && atPos >= 0) {
                    insertMentionText(screen, atPos, mentionText);
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                mentionMgr.cancel();
                return true;
            }
        }

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            // 弹窗优先关闭（从最上层开始）
            if (PopupWindowManager.getInstance().closeTop()) {
                return true;
            }
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

        LcpConfig config = LcpConfig.getInstance();
        boolean isSendKey = key == config.getSendKey();
        boolean isNewlineKey = config.getNewlineKey() != GLFW.GLFW_KEY_UNKNOWN && key == config.getNewlineKey();
        boolean isShiftPressed = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;

        if (isSendKey && state.replyingTo != null) {
            String input = getInputValue(screen);
            if (input != null && !input.isBlank()) {
                if (!input.startsWith("/")) {
                    // 通过 ReplyMessagePayload 发送回复，服务端分流：
                    // 装mod玩家收到 Payload 显示内联格式（完整内容），未装mod玩家收到纯文本（截断后）
                    // 传完整内容，截断由服务端在给未装mod玩家拼接纯文本时处理
                    String fullContent = state.replyingTo.getContent();
                    String originalUuid = state.replyingToMessageId;
                    if (originalUuid == null) {
                        originalUuid = MessageJumpManager.getInstance().getMessageId(state.replyingTo.getMessage());
                    }

                    Minecraft mc = Minecraft.getInstance();
                    String senderName = mc.player != null ? mc.player.getName().getString() : "?";

                    ReplyMessagePayload replyPayload = new ReplyMessagePayload(
                            senderName,
                            state.replyingTo.getSender(),
                            fullContent,
                            input,
                            originalUuid
                    );
                    ClientPlayNetworking.send(replyPayload);

                    // 本地预存回复数据（自己也会收到服务端回显，但提前存避免时序问题）
                    ReplyDataManager.getInstance().addReplyData(replyPayload);

                    // 清空输入框和回复状态
                    ((ChatScreenAccess) screen).lcp$getInput().setValue("");
                    state.replyingTo = null;
                    state.replyingToMessageId = null;

                    // 关闭聊天框
                    mc.gui.setScreen(null);
                    return true;
                }
                state.replyingTo = null;
                state.replyingToMessageId = null;
            }
        }

        if (isSendKey || isNewlineKey) {
            EditBox input = ((ChatScreenAccess) screen).lcp$getInput();
            if (input != null && input.isFocused()) {
                if (isSendKey && isShiftPressed) {
                    input.insertText("\n");
                    return true;
                } else if (isSendKey && isNewlineKey) {
                    return false;
                } else if (isSendKey) {
                    String text = input.getValue();
                    if (text != null && !text.isBlank()) {
                        // 检测@消息：如果包含@在线玩家，走 MentionMessagePayload 而非原版聊天
                        if (!text.startsWith("/") && text.length() <= 256) {
                            List<String> mentions = MentionManager.extractMentions(text);
                            if (!mentions.isEmpty()) {
                                sendMentionMessage(text, mentions);
                                input.setValue("");
                                justSentMessage = true;
                                MentionManager.getInstance().cancel();
                                Minecraft mc = Minecraft.getInstance();
                                mc.gui.setScreen(null);
                                return true;
                            }
                        }

                        ChatHistoryManager.addMessage(
                                LcpConfig.getInstance().getChatHistoryView(), text);
                        justSentMessage = true;

                        if (text.length() > 256) {
                            sendLongMessage(text);
                            Minecraft mc = Minecraft.getInstance();
                            mc.gui.hud.getChat().addRecentChat(text);
                            mc.gui.setScreen(null);
                            return true;
                        }
                    }
                    return false;
                } else if (isNewlineKey) {
                    input.insertText("\n");
                    return true;
                }
            }
        }

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

            ((ChatScreenAccess) screen).lcp$setHistoryPos(
                    Minecraft.getInstance().gui.hud.getChat().getRecentChat().size());

            return true;
        }

        // Ctrl+V：优先检测剪贴板中的图片，若存在则发送图片；否则放行让原 ChatScreen 处理文本粘贴
        // 服务端未装 mod 时，剪贴板图也无法发送（走图片通道依赖 ImagePayload C2S）
        if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 && key == GLFW.GLFW_KEY_V) {
            if (ClientNetworking.isServerModInstalled()) {
                if (top.xuyangjerry.mcmod.lcp.client.nonebot.ClientImageSender.trySendClipboardImage(screen)) {
                    return true;
                }
            } else {
                // 检查是否为图片粘贴：若是，提示服务端未装 mod 无法发送图片
                if (top.xuyangjerry.mcmod.lcp.client.nonebot.ClientImageSender.isClipboardHasImage()) {
                    Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(
                            Component.literal("[LCP] 服务端未安装mod，无法发送图片")
                                    .withStyle(Style.EMPTY.withColor(0xFFFF5555)));
                    return true;
                }
            }
        }

        return false;
    }

    private static void sendLongMessage(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) return;

        text = text.trim();

        if (text.startsWith("/")) {
            mc.player.connection.sendCommand(text.substring(1));
        } else {
            for (int i = 0; i < text.length(); i += 256) {
                int end = Math.min(i + 256, text.length());
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
                    end--;
                }
                String chunk = text.substring(i, end);
                if (!chunk.isEmpty()) {
                    mc.player.connection.sendChat(chunk);
                }
            }
        }
    }

    /**
     * 发送含@的消息：通过 MentionMessagePayload 发送，服务端分流给装mod/未装mod玩家。
     * 不走原版聊天通道，避免装mod玩家看到重复消息。
     */
    private static void sendMentionMessage(String content, List<String> mentions) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        String senderName = mc.player.getName().getString();
        MentionMessagePayload payload = new MentionMessagePayload(
                senderName, content, new ArrayList<>(mentions));
        ClientPlayNetworking.send(payload);
    }

    public static void onChatScreenRemove(ChatScreen screen) {
        // 关闭聊天框时清空所有弹窗
        PopupWindowManager.getInstance().closeAll();
        // 恢复默认光标
        PopupWindow.restoreDefaultCursor();

        // 注意：不移除 STATES 中的 state。
        // 从子屏幕（如 PlayerFilterScreen）回到 ChatScreen 时，screen 对象不变，
        // 保留 state 可保持选择模式等状态。
        // 窗口最小化/恢复时不触发 remove，per-screen 事件不会丢失。

        if (LcpConfig.getInstance().isSaveDraftOnClose()) {
            if (justSentMessage) {
                ChatDraftManager.clearDraft();
                justSentMessage = false;
            } else {
                String inputText = getInputValue(screen);
                if (inputText != null && !inputText.isBlank()) {
                    ChatDraftManager.saveDraft(inputText);
                } else {
                    ChatDraftManager.clearDraft();
                }
            }
        }

        if (LcpConfig.getInstance().isPreserveChatHistory()) {
            // 立即保存，避免延迟期间退出世界导致消息丢失
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

    /**
     * 根据输入框文本和光标位置更新@候选列表状态。
     */
    private static void updateMentionState(ChatScreen screen) {
        try {
            EditBox input = ((ChatScreenAccess) screen).lcp$getInput();
            if (input == null || !input.isFocused()) {
                MentionManager.getInstance().cancel();
                return;
            }
            String text = input.getValue();
            // 命令（以/开头）不触发@功能
            if (text.startsWith("/")) {
                MentionManager.getInstance().cancel();
                return;
            }
            int cursor = input.getCursorPosition();
            MentionManager.getInstance().update(text, cursor);
        } catch (Exception e) {
            MentionManager.getInstance().cancel();
        }
    }

    /**
     * 渲染@候选列表（在输入框上方）。
     */
    private static void drawMentionCandidateList(ChatScreen screen, GuiGraphicsExtractor g) {
        MentionManager mm = MentionManager.getInstance();
        if (!mm.isActive()) return;

        List<String> candidates = mm.getCandidates();
        if (candidates.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        var font = mc.font;

        EditBox input;
        try {
            input = ((ChatScreenAccess) screen).lcp$getInput();
        } catch (Exception e) {
            return;
        }
        if (input == null) return;

        // 原生风格：项高度与 MC CommandSuggestions 一致
        int itemHeight = 12;
        int maxVisible = Math.min(candidates.size(), 8);
        int padding = 4;
        int listWidth = 0;
        int visibleCount = Math.min(candidates.size(), maxVisible);
        for (int i = 0; i < visibleCount; i++) {
            listWidth = Math.max(listWidth, font.width("@" + candidates.get(i)) + padding * 2 + 4);
        }
        listWidth = Math.max(listWidth, 100);

        // 列表X位置与@符号平齐：input.getX() + @前文本的像素宽度
        int atPos = mm.getAtPosition();
        String inputText = input.getValue();
        int listX;
        if (atPos > 0 && atPos <= inputText.length()) {
            listX = input.getX() + font.width(inputText.substring(0, atPos));
        } else {
            listX = input.getX();
        }
        // 防止列表超出屏幕右侧
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        if (listX + listWidth > screenWidth - 4) {
            listX = screenWidth - listWidth - 4;
        }
        if (listX < 4) {
            listX = 4;
        }
        int listBottom = input.getY() - 3;
        int listTop = listBottom - maxVisible * itemHeight - 2;

        // 原生风格背景：深色半透明 + 上下渐变边框
        g.fill(listX - 3, listTop - 1, listX + listWidth + 1, listBottom + 1, 0xE0000000);
        // 顶部高光
        g.fill(listX - 3, listTop - 1, listX + listWidth + 1, listTop, 0xFF2D2D2D);
        // 底部阴影
        g.fill(listX - 3, listBottom, listX + listWidth + 1, listBottom + 1, 0xFF1A1A1A);

        int selected = mm.getSelectedIndex();
        int scrollOffset = 0;
        if (selected >= maxVisible) {
            scrollOffset = selected - maxVisible + 1;
        }

        for (int i = 0; i < maxVisible; i++) {
            int idx = i + scrollOffset;
            if (idx >= candidates.size()) break;
            int itemY = listTop + i * itemHeight;
            String candidate = candidates.get(idx);
            boolean isAll = MentionManager.ALL.equals(candidate);

            // 选中项：原生风格蓝色高亮（与MC CommandSuggestions 一致）
            if (idx == selected) {
                g.fill(listX - 2, itemY - 1, listX + listWidth, itemY + itemHeight - 1, 0xFF3060D0);
                g.fill(listX - 2, itemY - 1, listX + listWidth, itemY, 0xFF5080E0);
            }

            // @全体 用金色（类似原生命令高亮），普通玩家用白色
            int textColor = isAll ? 0xFFFFAA00 : 0xFFFFFFFF;
            String displayText = isAll ? "@\u5168\u4f53" : "@" + candidate;
            g.text(font, displayText, listX + padding, itemY + 2, textColor);

            // @全体 后面加一个"(所有人)"提示
            if (isAll) {
                String hint = "(所有人)";
                int hintX = listX + padding + font.width(displayText) + 4;
                g.text(font, hint, hintX, itemY + 2, 0xFF888888);
            }
        }
    }

    /**
     * 将选中的@玩家名插入到输入框的@位置，替换掉@后的查询文本。
     */
    private static void insertMentionText(ChatScreen screen, int atPosition, String mentionText) {
        try {
            EditBox input = ((ChatScreenAccess) screen).lcp$getInput();
            if (input == null) return;
            String text = input.getValue();
            int cursor = input.getCursorPosition();
            if (atPosition < 0 || atPosition > text.length() || cursor < atPosition) return;
            String before = text.substring(0, atPosition);
            String after = text.substring(cursor);
            String newText = before + mentionText + after;
            input.setValue(newText);
            int newCursor = before.length() + mentionText.length();
            input.setCursorPosition(newCursor);
            input.setFocused(true);
        } catch (Exception e) {
            // ignore
        }
    }

    private static void trackExistingMessages() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        ChatComponent chat = mc.gui.hud.getChat();
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

    public static void onChatScreenRender(ChatScreen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        ChatScreenState state = getState(screen);
        if (state == null) {
            return;
        }

        state.resetHoverState();
        updateMentionState(screen);
        trackExistingMessages();
        drawHighlightedMessage(g, state);

        // @候选列表优先渲染：无论鼠标是否悬停在消息上都要显示
        drawMentionCandidateList(screen, g);

        if (state.replyingTo != null) {
            drawReplyBar(screen, g, state, mouseX, mouseY);
        }

        if (state.isRangeSelectMode) {
            drawSelectionModeOverlay(screen, g, state, mouseX, mouseY);
        }

        if (state.menuOpen) {
            drawMenu(g, mouseX, mouseY, state);
            renderPopups(g, mouseX, mouseY);
            return;
        }

        if (state.isRangeSelectMode) {
            renderPopups(g, mouseX, mouseY);
            return;
        }

        if (!LcpConfig.getInstance().isEnableHoverButtons()) {
            // 即使禁用 hover 按钮，也保留 NoneBot 图片和合并转发卡片的悬浮预览
            GuiMessage imgMsg = ChatMessageLocator.findMessageAtMouse(mouseX, mouseY);
            String text = null;
            if (imgMsg != null) {
                text = imgMsg.content() != null ? imgMsg.content().getString() : "";
            } else {
                // addClientSystemMessage 添加的消息可能不在 allMessages 中，尝试从 trimmedMessages 获取
                text = ChatMessageLocator.getMessageTextAtMouse(mouseX, mouseY);
            }
            if (text != null) {
                if (top.xuyangjerry.mcmod.lcp.client.message.ReplyHoverRenderer
                        .tryRender(g, text, mouseX, mouseY)) {
                    renderPopups(g, mouseX, mouseY);
                    return;
                }
                if (top.xuyangjerry.mcmod.lcp.client.forward.ForwardCardRenderer
                        .tryRender(g, text, mouseX, mouseY)) {
                    renderPopups(g, mouseX, mouseY);
                    return;
                }
                top.xuyangjerry.mcmod.lcp.client.nonebot.ImageHoverRenderer
                        .tryRender(g, text, mouseX, mouseY);
            }
            renderPopups(g, mouseX, mouseY);
            return;
        }

        GuiMessage msg = ChatMessageLocator.findMessageAtMouse(mouseX, mouseY);
        String hoverText = null;
        MessageInfo info = null;
        if (msg != null) {
            info = MessageInfo.from(msg);
            hoverText = msg.content() != null ? msg.content().getString() : "";
        }
        // addClientSystemMessage 添加的系统消息可能不在 allMessages 中，尝试从 trimmedMessages 获取
        if (hoverText == null || hoverText.isEmpty()) {
            hoverText = ChatMessageLocator.getMessageTextAtMouse(mouseX, mouseY);
        }
        if (hoverText == null || hoverText.isEmpty()) {
            renderPopups(g, mouseX, mouseY);
            return;
        }
        // 回复消息悬浮预览（优先于转发和图片）
        if (top.xuyangjerry.mcmod.lcp.client.message.ReplyHoverRenderer
                .tryRender(g, hoverText, mouseX, mouseY)) {
            if (info != null) state.hoverMessage = info;
            renderPopups(g, mouseX, mouseY);
            return;
        }

        if (top.xuyangjerry.mcmod.lcp.client.forward.ForwardCardRenderer
                .tryRender(g, hoverText, mouseX, mouseY)) {
            if (info != null) state.hoverMessage = info;
            renderPopups(g, mouseX, mouseY);
            return;
        }

        if (top.xuyangjerry.mcmod.lcp.client.nonebot.ImageHoverRenderer
                .tryRender(g, hoverText, mouseX, mouseY)) {
            if (info != null) state.hoverMessage = info;
            renderPopups(g, mouseX, mouseY);
            return;
        }

        // 对于非系统消息（普通玩家消息），继续显示悬浮按钮
        if (info == null) {
            renderPopups(g, mouseX, mouseY);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            renderPopups(g, mouseX, mouseY);
            return;
        }

        int targetVisualLine = ChatMessageLocator.getVisualLineIndexAtMouse(mouseY);

        boolean isSelf = mc.player.getName().getString().equals(info.getSender());
        state.hoverMessage = info;
        drawHoverButtons(g, mouseX, mouseY, targetVisualLine, isSelf, state, mc.font);

        renderPopups(g, mouseX, mouseY);
    }

    /**
     * 渲染所有弹窗（在最上层）
     */
    private static void renderPopups(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (PopupWindowManager.getInstance().hasWindows()) {
            PopupWindowManager.getInstance().render(g, mouseX, mouseY);
        }
    }

    /**
     * 处理鼠标拖动（弹窗拖动/调整大小）。仅在弹窗正在交互时消耗事件。
     */
    public static boolean onChatScreenMouseDrag(ChatScreen screen, double mouseX, double mouseY, int button) {
        if (PopupWindowManager.getInstance().hasWindows()) {
            PopupWindowManager.getInstance().mouseDragged((int) mouseX, (int) mouseY, button);
            // 只有弹窗正在拖拽/缩放时才屏蔽聊天栏
            if (PopupWindowManager.getInstance().isInteracting()) return true;
        }
        return false;
    }

    /**
     * 处理鼠标释放。仅在弹窗正在交互时消耗事件。
     */
    public static boolean onChatScreenMouseRelease(ChatScreen screen, double mouseX, double mouseY, int button) {
        if (PopupWindowManager.getInstance().hasWindows()) {
            boolean wasInteracting = PopupWindowManager.getInstance().isInteracting();
            PopupWindowManager.getInstance().mouseReleased((int) mouseX, (int) mouseY, button);
            if (wasInteracting) return true;
        }
        return false;
    }

    /**
     * 处理鼠标滚轮（弹窗内滚动/图片缩放）
     * 仅在弹窗区域内消耗滚动事件
     */
    public static boolean onChatScreenMouseScroll(ChatScreen screen, double mouseX, double mouseY, double scrollAmount) {
        if (PopupWindowManager.getInstance().hasWindows()) {
            return PopupWindowManager.getInstance().mouseScrolled(mouseX, mouseY, scrollAmount);
        }
        return false;
    }

    private static void drawHoverButtons(GuiGraphicsExtractor g, int mouseX, int mouseY, int targetVisualLine,
                                          boolean isSelf, ChatScreenState state, net.minecraft.client.gui.Font font) {
        if (targetVisualLine < 0) {
            return;
        }

        // 服务端未装 mod 时禁用需要 C2S Payload 的功能（回复/转发），保留本地可用的复制/+1/查看原文
        boolean serverModded = ClientNetworking.isServerModInstalled();

        int lineTopY = ChatMessageLocator.getLineScreenYTop(targetVisualLine);
        int lineBottomY = ChatMessageLocator.getLineScreenYBottom(targetVisualLine);
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

        boolean isReplyMessage = ReplyTracker.getInstance().getOriginalMessageId(state.hoverMessage.getMessage()) != null;

        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Integer> widths = new java.util.ArrayList<>();
        java.util.List<Integer> actions = new java.util.ArrayList<>();

        labels.add(copyLabel);
        widths.add(font.width(copyLabel) + 8);
        actions.add(ChatScreenState.ACTION_COPY);

        // 回复：需要服务端 mod（ReplyMessagePayload C2S），且 hovered 消息有对应原始消息可回
        if (serverModded) {
            labels.add(replyLabel);
            widths.add(font.width(replyLabel) + 8);
            actions.add(ChatScreenState.ACTION_REPLY);
        }

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

        // 转发：需要服务端 mod（ForwardMessagePayload C2S）
        if (serverModded) {
            labels.add(forwardLabel);
            widths.add(font.width(forwardLabel) + 8);
            actions.add(ChatScreenState.ACTION_FORWARD);
        }

        int totalWidth = 0;
        for (int w : widths) {
            totalWidth += w;
        }
        if (!widths.isEmpty()) {
            totalWidth += (widths.size() - 1) * gap;
        }

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
            g.centeredText(font, labels.get(i), bx + bw / 2, buttonY + 1, 0xFFFFFFFF);

            state.hoverButtons.add(new int[]{bx, buttonY, bx + bw, buttonY + buttonHeight, actions.get(i)});
            x += bw + gap;
        }
    }

    private static void drawMenu(GuiGraphicsExtractor g, int mouseX, int mouseY, ChatScreenState state) {
        state.menuOptions.clear();

        int menuX = state.menuX;
        int menuY = state.menuY;
        int optionHeight = 16;

        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        // 服务端未装 mod 时禁用需要 C2S Payload 的功能（回复/转发/多选确认转发）
        boolean serverModded = ClientNetworking.isServerModInstalled();

        if (state.isRangeSelectMode) {
            java.util.List<String> labels = new java.util.ArrayList<>();
            java.util.List<Integer> actions = new java.util.ArrayList<>();

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
                if (serverModded) {
                    labels.add(confirmLabel);
                    actions.add(ChatScreenState.ACTION_CONFIRM_FORWARD);
                }
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
                g.text(font, labels.get(i), menuX + 6, oy + 4, 0xFFFFFFFF);
                state.menuOptions.add(new int[]{menuX, oy, menuX + menuWidth, oy + optionHeight, actions.get(i)});
            }
            return;
        }

        boolean showPlusOne = !state.menuMessageIsSelf || LcpConfig.getInstance().isPlusOneSelf();
        String copyLabel = Component.translatable("light_chat_patch.action.copy").getString();
        String replyLabel = Component.translatable("light_chat_patch.action.reply").getString();
        String plusOneLabel = Component.translatable("light_chat_patch.action.plus_one").getString();
        String viewOriginalLabel = Component.translatable("light_chat_patch.action.view_original").getString();
        String forwardLabel = Component.translatable("light_chat_patch.action.forward").getString();

        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<Integer> actions = new java.util.ArrayList<>();
        // 每个选项的文字颜色（ARGB），与 labels/actions 索引对齐
        java.util.List<Integer> labelColors = new java.util.ArrayList<>();
        final int DEFAULT_COLOR = 0xFFFFFFFF;
        final int MUTE_COLOR = 0xFFFF5555;   // 红色：禁言
        final int UNMUTE_COLOR = 0xFF55FFFF; // 青色：解禁

        labels.add(copyLabel);
        actions.add(ChatScreenState.ACTION_COPY);
        labelColors.add(DEFAULT_COLOR);
        if (serverModded) {
            labels.add(replyLabel);
            actions.add(ChatScreenState.ACTION_REPLY);
            labelColors.add(DEFAULT_COLOR);
        }

        if (showPlusOne) {
            labels.add(plusOneLabel);
            actions.add(ChatScreenState.ACTION_PLUS_ONE);
            labelColors.add(DEFAULT_COLOR);
        }

        if (state.menuMessageIsReply) {
            labels.add(viewOriginalLabel);
            actions.add(ChatScreenState.ACTION_VIEW_ORIGINAL);
            labelColors.add(DEFAULT_COLOR);
        }

        if (serverModded) {
            labels.add(forwardLabel);
            actions.add(ChatScreenState.ACTION_FORWARD);
            labelColors.add(DEFAULT_COLOR);
        }

        // ========== 禁言 / 解禁 选项（仅服务端装 mod 且目标玩家有效时显示） ==========
        if (serverModded && state.menuTargetPlayerName != null) {
            Minecraft mc = Minecraft.getInstance();
            String selfName = mc.player != null ? mc.player.getName().getString() : null;
            // 不能禁言自己
            if (!state.menuTargetPlayerName.equals(selfName)) {
                // 查询该玩家当前是否被禁言
                boolean alreadyMuted = false;
                if (state.menuTargetPlayerUuid != null) {
                    alreadyMuted = MuteClientManager.findByUuid(state.menuTargetPlayerUuid) != null;
                }
                if (!alreadyMuted) {
                    alreadyMuted = MuteClientManager.findByName(state.menuTargetPlayerName) != null;
                }

                if (alreadyMuted) {
                    labels.add("解禁 " + state.menuTargetPlayerName);
                    actions.add(ChatScreenState.ACTION_UNMUTE_TARGET);
                    labelColors.add(UNMUTE_COLOR);
                } else {
                    labels.add("禁言 " + state.menuTargetPlayerName);
                    actions.add(ChatScreenState.ACTION_MUTE_TARGET);
                    labelColors.add(MUTE_COLOR);
                }
            }
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
            int color = i < labelColors.size() ? labelColors.get(i) : DEFAULT_COLOR;
            g.text(font, labels.get(i), menuX + 6, oy + 4, color);

            state.menuOptions.add(new int[]{menuX, oy, menuX + menuWidth, oy + optionHeight, actions.get(i)});
        }
    }

    // ==================== 右键菜单：目标玩家提取（禁言/解禁用） ====================

    /**
     * 在打开右键菜单时填充目标玩家信息。
     * 检测顺序：
     *   1. 鼠标精确字符位置的 Style → SHOW_ENTITY（原版玩家名 selector 标识，最可靠，含 UUID）
     *   2. MessageInfo 的发送者组件 → SelectorContents / <name> / [name] 格式
     *   3. 整条消息的 Component 树扫描
     *
     * 结果保存在 state.menuTargetPlayerName / menuTargetPlayerUuid。
     */
    private static void fillMenuTargetPlayer(ChatScreenState state, int mx, int my, MessageInfo info) {
        state.menuTargetPlayerName = null;
        state.menuTargetPlayerUuid = null;

        // 1. 先取鼠标位置字符的 Style（最优先：原版 selector 玩家名带 SHOW_ENTITY）
        Style clickedStyle = ChatMessageLocator.getStyleAtMouse(mx, my);
        Component senderComp = info != null ? info.getSenderComponent() : null;
        Component rootComp = info != null && info.getMessage() != null ? info.getMessage().content() : null;

        MessageInfo.PlayerTarget target = MessageInfo.extractPlayerTarget(clickedStyle, senderComp, rootComp);
        if (target != null) {
            state.menuTargetPlayerName = target.name;
            state.menuTargetPlayerUuid = target.uuidStr;
            return;
        }

        // 2. 回退：直接用 info.getSender()（整行消息的发送者，比如 "转发中某条消息的发送者" 场景）
        if (info != null && info.getSender() != null && !info.getSender().isEmpty()
                && !"系统".equals(info.getSender()) && !"[LCP.NoneBot]".equals(info.getSender())) {
            state.menuTargetPlayerName = info.getSender();
        }
    }

    public static boolean onChatScreenMouseClick(ChatScreen screen, double mouseX, double mouseY, int button) {
        ChatScreenState state = getState(screen);
        if (state == null) {
            return false;
        }

        int mx = (int) mouseX;
        int my = (int) mouseY;

        // 弹窗优先处理鼠标点击（仅在弹窗区域内消耗事件，空白区域放行）
        if (PopupWindowManager.getInstance().hasWindows()) {
            boolean consumed = PopupWindowManager.getInstance().mouseClicked(mx, my, button);
            if (consumed) return true;
        }

        if (button == 0 && state.imageButton != null && state.imageButton.isMouseOver(mouseX, mouseY)) {
            if (ClientNetworking.isServerModInstalled()) {
                top.xuyangjerry.mcmod.lcp.client.nonebot.ClientImageSender.openImageFileChooser(screen);
            } else {
                Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(
                        Component.literal("[LCP] 服务端未安装mod，无法发送图片")
                                .withStyle(Style.EMPTY.withColor(0xFFFF5555)));
            }
            return true;
        }

        if (state.replyingTo != null && state.replyBarCloseBounds != null) {
            int[] b = state.replyBarCloseBounds;
            if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                state.replyingTo = null;
                return true;
            }
            handleReplyBarClick(state, mx, my);
        }

        if (state.menuOpen) {
            for (int[] bounds : state.menuOptions) {
                if (mx >= bounds[0] && mx <= bounds[2] && my >= bounds[1] && my <= bounds[3]) {
                    performAction(screen, bounds[4], state.menuMessage);
                    state.menuOpen = false;
                    return true;
                }
            }
            // 点击菜单外：右键则在新位置重新打开菜单，左键则关闭
            if (button == 1) {
                // 先关闭原菜单，再根据点击位置决定新菜单内容
                state.menuOpen = false;
                if (state.isRangeSelectMode) {
                    GuiMessage msg = ChatMessageLocator.findMessageAtMouse(mx, my);
                    if (msg != null) {
                        MessageInfo info = MessageInfo.from(msg);
                        if (info != null) {
                            state.menuOpen = true;
                            state.menuX = mx;
                            state.menuY = my;
                            state.menuMessage = info;
                            fillMenuTargetPlayer(state, mx, my, info);
                            return true;
                        }
                    }
                    state.menuOpen = true;
                    state.menuX = mx;
                    state.menuY = my;
                    state.menuMessage = null;
                    fillMenuTargetPlayer(state, mx, my, null);
                    return true;
                } else if (LcpConfig.getInstance().isEnableRightClickMenu()) {
                    GuiMessage msg = ChatMessageLocator.findMessageAtMouse(mx, my);
                    if (msg != null) {
                        MessageInfo info = MessageInfo.from(msg);
                        if (info != null) {
                            Minecraft mc = Minecraft.getInstance();
                            boolean isSelf = mc.player != null
                                    && mc.player.getName().getString().equals(info.getSender());
                            boolean isReply = ReplyTracker.getInstance()
                                    .getOriginalMessageId(info.getMessage()) != null;
                            state.menuOpen = true;
                            state.menuX = mx;
                            state.menuY = my;
                            state.menuMessage = info;
                            state.menuMessageIsSelf = isSelf;
                            state.menuMessageIsReply = isReply;
                            fillMenuTargetPlayer(state, mx, my, info);
                            return true;
                        }
                    }
                }
                return true;
            }
            state.menuOpen = false;
            return true;
        }

        if (state.isRangeSelectMode) {
            if (state.confirmButtonBounds != null) {
                int[] b = state.confirmButtonBounds;
                if (mx >= b[0] && mx <= b[2] && my >= b[1] && my <= b[3]) {
                    confirmForward(screen, state);
                    return true;
                }
            }

            if (button == 1) {
                GuiMessage msg = ChatMessageLocator.findMessageAtMouse(mx, my);
                if (msg != null) {
                    MessageInfo info = MessageInfo.from(msg);
                    if (info != null) {
                        state.menuOpen = true;
                        state.menuX = mx;
                        state.menuY = my;
                        state.menuMessage = info;
                        fillMenuTargetPlayer(state, mx, my, info);
                        return true;
                    }
                }
                state.menuOpen = true;
                state.menuX = mx;
                state.menuY = my;
                state.menuMessage = null;
                fillMenuTargetPlayer(state, mx, my, null);
                return true;
            }

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
            return true;
        }

        if (button == 0 && state.hoverMessage != null) {
            // 先检查是否点击了 hover 按钮
            for (int[] bounds : state.hoverButtons) {
                if (mx >= bounds[0] && mx <= bounds[2] && my >= bounds[1] && my <= bounds[3]) {
                    performAction(screen, bounds[4], state.hoverMessage);
                    return true;
                }
            }
        }

        // 非多选模式下，左键单击回复消息跳转到原始消息；转发/图片消息打开弹窗
        if (button == 0 && !state.isRangeSelectMode) {
            String text = ChatMessageLocator.getMessageTextAtMouse(mx, my);
            if (text != null && !text.isEmpty()) {
                // 检查是否是回复消息：左键直接跳转到原始消息
                String replyUuid = ReplyDataManager.extractUuid(text);
                if (replyUuid != null) {
                    boolean jumped = MessageJumpManager.getInstance().jumpToMessageById(replyUuid);
                    if (jumped) {
                        // 跳转成功：高亮目标消息3秒
                        state.highlightedMessageId = replyUuid;
                        state.highlightEndTime = System.currentTimeMillis() + 3000;
                    } else {
                        // 跳转失败：可能是消息已被挤出聊天框，提示用户
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.gui != null && mc.gui.hud.getChat() != null) {
                            mc.gui.hud.getChat().addClientSystemMessage(
                                    net.minecraft.network.chat.Component.literal("§7原始消息不在当前聊天范围内"));
                        }
                    }
                    return true;
                }
                // 检查是否是转发消息
                String forwardId = ForwardMessageManager.extractId(text);
                if (forwardId != null) {
                    ForwardMessagePayload payload = ForwardMessageManager.getInstance().getForwardMessage(forwardId);
                    if (payload != null) {
                        PopupWindowManager.getInstance().openForwardDetail(forwardId, payload, mx, my);
                        return true;
                    }
                }
                // 检查是否是图片消息
                int imageId = ClientImageCache.extractImageId(text);
                if (imageId >= 0) {
                    PopupWindowManager.getInstance().openImageDetail(imageId, mx, my);
                    return true;
                }
            }
        }

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
                        fillMenuTargetPlayer(state, mx, my, info);
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
                // 手动加入历史记录（handleChatInput 走原版通道，不会触发 ChatScreenHandler 的按键流程）
                top.xuyangjerry.mcmod.lcp.history.ChatHistoryManager.addMessage(
                        LcpConfig.getInstance().getChatHistoryView(), "+1");
                screen.handleChatInput("+1", true);
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
                    tryOpenRangeActionScreen(screen, state);
                }
            }
            case ChatScreenState.ACTION_SET_RANGE_END -> {
                if (state != null && info != null) {
                    state.rangeEnd = info.getMessage();
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
                    List<GuiMessage> targetMessages;
                    if (state.rangeStart != null && state.rangeEnd != null) {
                        targetMessages = getMessagesInRange(state);
                    } else {
                        ChatComponent chat = mc.gui.hud.getChat();
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
                        mc.gui.setScreen(new PlayerFilterScreen(mc.gui.screen(), state, new ArrayList<>(senders)));
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
            // 右键菜单：禁言目标玩家（红色选项，仅 OP 可用，服务端判断权限）
            case ChatScreenState.ACTION_MUTE_TARGET -> {
                if (state != null && state.menuTargetPlayerName != null) {
                    String targetName = state.menuTargetPlayerName;
                    String targetUuid = state.menuTargetPlayerUuid;
                    // 右键菜单默认禁言原因留空；后续可扩展弹出输入框让玩家输入原因
                    MuteClientManager.sendMuteRequest(targetName, targetUuid, "");
                }
            }
            // 右键菜单：解禁目标玩家（青色选项，仅 OP 可用）
            case ChatScreenState.ACTION_UNMUTE_TARGET -> {
                if (state != null && state.menuTargetPlayerName != null) {
                    String targetName = state.menuTargetPlayerName;
                    String targetUuid = state.menuTargetPlayerUuid;
                    MuteClientManager.sendUnmuteRequest(targetName, targetUuid);
                }
            }
        }
    }

    private static void drawReplyBar(ChatScreen screen, GuiGraphicsExtractor g, ChatScreenState state, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.gui.Font font = mc.font;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int lineHeight = 12;
        int barHeight = lineHeight * 2 + 6;

        EditBox input = ((ChatScreenAccess) screen).lcp$getInput();
        int barY = input.getY() - barHeight - 2;
        int paddingX = 4;
        int closeSize = 10;

        String titleText = Component.translatable("light_chat_patch.reply.replying_to",
                state.replyingTo.getSender()).getString();
        String contentPreview = truncateReplyContent(state.replyingTo.getContent());

        int titleWidth = font.width(titleText);
        int contentWidth = font.width(contentPreview);
        int barWidth = Math.min(Math.max(titleWidth, contentWidth) + paddingX * 2 + closeSize + 4, screenWidth - 8);
        int barX = 4;

        boolean hovered = mouseX >= barX && mouseX <= barX + barWidth
                && mouseY >= barY && mouseY <= barY + barHeight;
        int bgColor = hovered ? 0xD0336699 : 0xD0225577;
        g.fill(barX, barY, barX + barWidth, barY + barHeight, bgColor);

        g.text(font, titleText, barX + paddingX, barY + 2, 0xFFFFFFFF);
        g.text(font, contentPreview, barX + paddingX, barY + lineHeight + 4, 0xFFAAAAAA);

        int closeX = barX + barWidth - closeSize - 2;
        int closeY = barY + 2;
        boolean closeHovered = mouseX >= closeX && mouseX <= closeX + closeSize
                && mouseY >= closeY && mouseY <= closeY + closeSize;
        int closeColor = closeHovered ? 0xFFFF6666 : 0xFFFFFFFF;
        g.text(font, "x", closeX, closeY, closeColor);

        state.replyBarBounds = new int[]{barX, barY, barX + barWidth, barY + barHeight};
        state.replyBarCloseBounds = new int[]{closeX, closeY, closeX + closeSize, closeY + closeSize};
    }

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

    private static void drawHighlightedMessage(GuiGraphicsExtractor g, ChatScreenState state) {
        if (state.highlightedMessageId == null || System.currentTimeMillis() > state.highlightEndTime) {
            return;
        }

        GuiMessage target = MessageJumpManager.getInstance().findMessageById(state.highlightedMessageId);
        if (target == null) {
            return;
        }

        int[] range = ChatMessageLocator.getMessageTrimmedRange(target);
        if (range == null) return;
        int tStart = range[0];
        int tEnd = range[1];

        Minecraft mc = Minecraft.getInstance();
        ChatComponent chat = mc.gui.hud.getChat();
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

    /**
     * 为回复消息绘制视觉标识，使其与普通聊天文本有清晰区分。
     *
     * <p>设计：左侧 3px 蓝色竖条 + 低 alpha 填充。竖条窄不挡文字，填充极轻不遮挡命令补全。
     *
     * <p>渐隐：与原版聊天消息条目完全同步。HUD 状态下使用原版 {@code AlphaCalculator.timeBased}
     * 相同公式（基于 {@code Hud.getGuiTicks() - msg.addedTime()}）：
     * <ul>
     *   <li>age ≤ 180 ticks：完全可见 (1.0)</li>
     *   <li>180 < age < 200：二次缓出渐隐</li>
     *   <li>age ≥ 200：完全消失，不再渲染</li>
     * </ul>
     * 聊天框打开时（focused=true）永不渐隐，与原版 FULLY_VISIBLE 一致。
     *
     * @param focused true 表示聊天框已打开，false 表示 HUD 状态
     */
    public static void drawReplyMessageBackgrounds(GuiGraphicsExtractor g, boolean focused) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.gui == null) return;
        ChatComponent chat = mc.gui.hud.getChat();
        if (chat == null) return;
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();
        if (allMessages == null || allMessages.isEmpty()) return;

        double chatScale = mc.options.chatScale().get();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int chatBottom = Mth.floor((screenHeight - 40) / (float) chatScale);
        double chatLineSpacing = mc.options.chatLineSpacing().get();
        int entryHeight = Math.max(1, (int) (9.0 * (1.0 + chatLineSpacing)));
        double chatHeightOpt = focused
                ? mc.options.chatHeightFocused().get()
                : mc.options.chatHeightUnfocused().get();
        int chatHeight = Mth.floor(160.0 * chatHeightOpt + 20.0);
        int linesPerPage = chatHeight / entryHeight;
        int scrollbarPos = access.lcp$getChatScrollbarPos();

        int leftX = 4;
        int rightX = ChatMessageLocator.getChatRightEdge();

        // 当前 GUI tick，与原版 ChatComponent.addMessage 中设置 addedTime 使用同一时间源
        int currentTick = mc.gui.hud.getGuiTicks();

        for (GuiMessage msg : allMessages) {
            String text = msg.content() != null ? msg.content().getString() : "";
            if (top.xuyangjerry.mcmod.lcp.client.message.ReplyDataManager.extractUuid(text) == null) {
                continue;
            }

            // 使用与原版 AlphaCalculator.timeBased 完全相同的公式计算不透明度
            float opacity;
            if (focused) {
                opacity = 1.0f;
            } else {
                opacity = computeMessageAlpha(currentTick, msg.addedTime());
            }
            if (opacity < 0.01f) continue;

            int[] range = ChatMessageLocator.getMessageTrimmedRange(msg);
            if (range == null) continue;
            int tStart = range[0];
            int tEnd = range[1];

            int visStart = tStart - scrollbarPos;
            int visEnd = tEnd - scrollbarPos;
            if (visStart >= linesPerPage || visEnd < 0) continue;

            int topVisual = Math.max(0, visStart);
            int bottomVisual = Math.min(linesPerPage - 1, visEnd);

            int screenTopY = (int) ((chatBottom - (bottomVisual + 1) * entryHeight) * chatScale);
            int screenBottomY = (int) ((chatBottom - topVisual * entryHeight) * chatScale);

            drawReplyMessageMarker(g, leftX, rightX, screenTopY, screenBottomY, opacity);
        }
    }

    /**
     * 原版 ChatComponent.AlphaCalculator.timeBased 的同公式实现。
     * 公式：t = clamp((1 - age/200) * 10, 0, 1); return t * t;
     * age ≤ 180 时返回 1.0，age ≥ 200 时返回 0.0，中间二次缓出。
     */
    private static float computeMessageAlpha(int currentTick, int addedTime) {
        int age = currentTick - addedTime;
        double t = 1.0 - (age / 200.0);
        t = t * 10.0;
        t = Mth.clamp(t, 0.0, 1.0);
        t = t * t;
        return (float) t;
    }

    /**
     * 绘制单条回复消息的视觉标识：左侧竖条 + 低 alpha 填充。
     */
    private static void drawReplyMessageMarker(GuiGraphicsExtractor g,
                                               int leftX, int rightX,
                                               int screenTopY, int screenBottomY,
                                               float opacity) {
        // 竖条色：蓝绿色，与聊天白/黄字形成强对比
        final int BAR_R = 0x55, BAR_G = 0xCC, BAR_B = 0xFF;
        // 填充色：同色系中等 alpha（与 REPLY_FILL_COLOR 的 RGB 一致）
        final int FILL_R = 0x40, FILL_G = 0x80, FILL_B = 0x90;

        int barA = Math.max(0, Math.min(255, Math.round(255f * opacity)));
        int fillA = Math.max(0, Math.min(255, Math.round(102f * opacity)));

        int barColor = (barA << 24) | (BAR_R << 16) | (BAR_G << 8) | BAR_B;
        int fillColor = (fillA << 24) | (FILL_R << 16) | (FILL_G << 8) | FILL_B;

        // 1. 低 alpha 填充（极轻，不遮挡命令补全）
        g.fill(leftX, screenTopY, rightX, screenBottomY, fillColor);
        // 2. 左侧 3px 竖条（最清晰的标识）
        g.fill(leftX, screenTopY, leftX + 3, screenBottomY, barColor);
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

    private static void drawSelectionModeOverlay(ChatScreen screen, GuiGraphicsExtractor g, ChatScreenState state, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.gui.Font font = mc.font;
        ChatComponent chat = mc.gui.hud.getChat();
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

        // 使用 parent() 引用匹配消息在 trimmedMessages 中的范围，
        // 不依赖 addedTime，避免多条消息共享同一 addedTime 时的范围错误
        java.util.IdentityHashMap<GuiMessage, int[]> msgToRange = new java.util.IdentityHashMap<>();
        if (!trimmed.isEmpty()) {
            GuiMessage currentMsg = trimmed.get(0).parent();
            int rangeStart = 0;
            for (int i = 0; i < trimmed.size(); i++) {
                GuiMessage.Line line = trimmed.get(i);
                if (line.parent() != currentMsg) {
                    msgToRange.put(currentMsg, new int[]{rangeStart, i - 1});
                    currentMsg = line.parent();
                    rangeStart = i;
                }
            }
            msgToRange.put(currentMsg, new int[]{rangeStart, trimmed.size() - 1});
        }

        Set<GuiMessage> messagesToRender = new LinkedHashSet<>(state.selectedMessages);
        if (state.rangeStart != null) messagesToRender.add(state.rangeStart);
        if (state.rangeEnd != null) messagesToRender.add(state.rangeEnd);

        for (GuiMessage msg : messagesToRender) {
            int[] range = msgToRange.get(msg);
            if (range == null) continue;

            int tStart = range[0];
            int tEnd = range[1];

            int visStart = tStart - scrollbarPos;
            int visEnd = tEnd - scrollbarPos;

            if (visStart >= linesPerPage || visEnd < 0) continue;

            int topVisual = Math.max(0, visStart);
            int bottomVisual = Math.min(linesPerPage - 1, visEnd);

            int screenTopY = (int) ((chatBottom - (bottomVisual + 1) * entryHeight) * chatScale);
            int screenBottomY = (int) ((chatBottom - topVisual * entryHeight) * chatScale);

            boolean isSelected = state.selectedMessages.contains(msg);
            if (isSelected) {
                g.fill(chatLeftEdge, screenTopY, chatRightEdge, screenBottomY, 0x500000CC);
            }

            // 使用引用比较而非 addedTime
            boolean isRangeStart = state.rangeStart == msg;
            boolean isRangeEnd = state.rangeEnd == msg;
            if (isRangeStart) {
                g.fill(chatLeftEdge, screenTopY, chatLeftEdge + 3, screenBottomY, 0xFF00CC00);
            }
            if (isRangeEnd) {
                g.fill(chatLeftEdge, screenTopY, chatLeftEdge + 3, screenBottomY, 0xFFCC0000);
            }
        }

        String hint = Component.translatable("light_chat_patch.select.mode_hint").getString();
        int hintWidth = font.width(hint) + 8;
        int hintX = (mc.getWindow().getGuiScaledWidth() - hintWidth) / 2;
        int hintY = 4;
        g.fill(hintX, hintY, hintX + hintWidth, hintY + 14, 0xB0000000);
        g.centeredText(font, hint, mc.getWindow().getGuiScaledWidth() / 2, hintY + 3, 0xFFFFFFFF);

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
        g.centeredText(font, confirmLabel, btnX + btnWidth / 2, btnY + 2, 0xFFFFFFFF);
        state.confirmButtonBounds = new int[]{btnX, btnY, btnX + btnWidth, btnY + btnHeight};
    }

    public static void applyRangeSelection(ChatScreenState state) {
        if (state.rangeStart == null || state.rangeEnd == null) return;

        normalizeRange(state);
        List<GuiMessage> messagesInRange = getMessagesInRange(state);

        if (LcpConfig.getInstance().isRangeSelectToggle()) {
            for (GuiMessage msg : messagesInRange) {
                if (state.selectedMessages.contains(msg)) {
                    state.selectedMessages.remove(msg);
                } else {
                    state.selectedMessages.add(msg);
                }
            }
        } else {
            state.selectedMessages.addAll(messagesInRange);
        }

        state.rangeStart = null;
        state.rangeEnd = null;
    }

    private static void tryOpenRangeActionScreen(ChatScreen screen, ChatScreenState state) {
        if (state.rangeStart == null || state.rangeEnd == null) return;
        normalizeRange(state);
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(new top.xuyangjerry.mcmod.lcp.client.screen.RangeActionScreen(screen, state));
    }

    private static void normalizeRange(ChatScreenState state) {
        if (state.rangeStart == null || state.rangeEnd == null) return;

        Minecraft mc = Minecraft.getInstance();
        ChatComponent chat = mc.gui.hud.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();

        int startIdx = -1, endIdx = -1;
        for (int i = 0; i < allMessages.size(); i++) {
            if (allMessages.get(i) == state.rangeStart) startIdx = i;
            if (allMessages.get(i) == state.rangeEnd) endIdx = i;
        }

        if (startIdx >= 0 && endIdx >= 0 && startIdx > endIdx) {
            GuiMessage tmp = state.rangeStart;
            state.rangeStart = state.rangeEnd;
            state.rangeEnd = tmp;
        }
    }

    public static List<GuiMessage> getMessagesInRange(ChatScreenState state) {
        List<GuiMessage> result = new ArrayList<>();
        if (state.rangeStart == null || state.rangeEnd == null) return result;

        Minecraft mc = Minecraft.getInstance();
        ChatComponent chat = mc.gui.hud.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();

        int startIdx = -1, endIdx = -1;
        for (int i = 0; i < allMessages.size(); i++) {
            if (allMessages.get(i) == state.rangeStart) startIdx = i;
            if (allMessages.get(i) == state.rangeEnd) endIdx = i;
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

    private static Set<GuiMessage> getRangeSelectedMessages(ChatScreenState state) {
        Set<GuiMessage> result = new LinkedHashSet<>();
        if (state.rangeStart == null || state.rangeEnd == null) {
            return result;
        }

        Minecraft mc = Minecraft.getInstance();
        ChatComponent chat = mc.gui.hud.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();

        int startIdx = -1, endIdx = -1;
        for (int i = 0; i < allMessages.size(); i++) {
            if (allMessages.get(i) == state.rangeStart) startIdx = i;
            if (allMessages.get(i) == state.rangeEnd) endIdx = i;
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

    private static List<GuiMessage> getAllSelectedMessages(ChatScreenState state) {
        Minecraft mc = Minecraft.getInstance();
        ChatComponent chat = mc.gui.hud.getChat();
        ChatComponentAccess access = (ChatComponentAccess) chat;
        List<GuiMessage> allMessages = access.lcp$getAllMessages();

        List<GuiMessage> sorted = new ArrayList<>();
        for (GuiMessage msg : allMessages) {
            if (state.selectedMessages.contains(msg)) {
                sorted.add(msg);
            }
        }
        java.util.Collections.reverse(sorted);
        return sorted;
    }

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

        // 选区完毕不退出选择模式，保持选择状态（用户可继续修改选择）
        // state.exitSelectionMode();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.gui.setScreen(new ForwardTargetScreen(mc.gui.screen(), messages));
        }
    }

    private static void trimChatMessages() {
        int max = LcpConfig.getInstance().getChatMaxVisibleMessages();
        if (max <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;

        ChatComponent chat = mc.gui.hud.getChat();
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

    private static final java.util.Set<Screen> processedChatOptionsScreens = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public static void onChatOptionsScreenInit(Screen screen) {
        // 防重复：用 WeakHashMap 跟踪已处理的 screen 实例
        if (processedChatOptionsScreens.contains(screen)) return;
        processedChatOptionsScreens.add(screen);

        OptionsSubScreenAccess access = (OptionsSubScreenAccess) screen;
        OptionsList list = access.lcp$getList();

        Button button = Button.builder(
                        Component.translatable("light_chat_patch.options.button"),
                        b -> Minecraft.getInstance().gui.setScreen(new LightChatPatchConfigScreen(screen)))
                .build();

        list.addSmall(button, null);
    }

    /**
     * 在聊天框输入框右侧添加图片发送按钮。
     * 服务端未装 mod 时不添加按钮（因为无法发送图片）。
     */
    private static void addImageSendButton(ChatScreen screen) {
        if (!ClientNetworking.isServerModInstalled()) {
            // 服务端未装 mod：隐藏图片按钮，也不设置 state.imageButton 引用
            ChatScreenState state = getState(screen);
            if (state != null) state.imageButton = null;
            return;
        }
        try {
            EditBox input = ((ChatScreenAccess) screen).lcp$getInput();
            if (input == null) return;

            net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
            String label = Component.translatable("light_chat_patch.action.send_image").getString();
            int btnWidth = font.width(label) + 8;
            int btnHeight = 12;
            int btnX = input.getX() + input.getWidth() + 2;
            int btnY = input.getY();

            int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            if (btnX + btnWidth > screenWidth) {
                btnX = screenWidth - btnWidth;
            }

            Button imageButton = Button.builder(
                            Component.translatable("light_chat_patch.action.send_image"),
                            b -> top.xuyangjerry.mcmod.lcp.client.nonebot.ClientImageSender.openImageFileChooser(screen))
                    .bounds(btnX, btnY, btnWidth, btnHeight)
                    .build();

            ((ScreenAccess) screen).lcp$addRenderableWidget(imageButton);

            ChatScreenState state = getState(screen);
            if (state != null) {
                state.imageButton = imageButton;
            }
        } catch (Exception e) {
            LightChatPatch.LOGGER.error("[LCP] Failed to add image send button", e);
        }
    }

}