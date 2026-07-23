package top.xuyangjerry.mcmod.client;

import net.minecraft.client.GuiMessage;
import top.xuyangjerry.mcmod.client.message.MessageInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 每个 ChatScreen 实例对应一份状态，替代原 ChatScreenMixin 中的 @Unique 字段。
 * 由 ChatScreenHandler 通过 WeakHashMap 管理生命周期。
 */
public final class ChatScreenState {

    public static final int ACTION_COPY = 0;
    public static final int ACTION_REPLY = 1;
    public static final int ACTION_PLUS_ONE = 2;
    public static final int ACTION_VIEW_ORIGINAL = 3;
    public static final int ACTION_FORWARD = 4;
    public static final int ACTION_SET_RANGE_START = 5;
    public static final int ACTION_SET_RANGE_END = 6;
    public static final int ACTION_CLEAR_RANGE = 7;
    public static final int ACTION_CONFIRM_FORWARD = 8;
    public static final int ACTION_EXIT_SELECT_MODE = 9;
    public static final int ACTION_SELECT_BY_SENDER = 10;
    public static final int ACTION_SELECT_ALL_IN_RANGE = 11;

    // --- Hover button state (每帧重置) ---
    public MessageInfo hoverMessage = null;
    public final List<int[]> hoverButtons = new ArrayList<>();

    // --- Right-click menu state ---
    public boolean menuOpen = false;
    public int menuX = 0;
    public int menuY = 0;
    public MessageInfo menuMessage = null;
    public boolean menuMessageIsSelf = false;
    public boolean menuMessageIsReply = false;
    public final List<int[]> menuOptions = new ArrayList<>();

    // --- Reply state (QQ-style) ---
    public MessageInfo replyingTo = null;
    public int[] replyBarBounds = null;
    public int[] replyBarCloseBounds = null;
    public String replyingToMessageId = null;

    // --- Highlight state ---
    public String highlightedMessageId = null;
    public long highlightEndTime = 0;

    // --- Selection mode state (多选转发) ---
    public boolean isRangeSelectMode = false;
    public final Set<GuiMessage> selectedMessages = new LinkedHashSet<>();
    public GuiMessage rangeStart = null;
    public GuiMessage rangeEnd = null;
    public int[] confirmButtonBounds = null;

    // --- 初始化标志 ---
    private boolean initialized = false;

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    /**
     * 每帧渲染前重置悬停按钮状态
     */
    public void resetHoverState() {
        hoverButtons.clear();
        hoverMessage = null;
    }

    /**
     * 退出选择模式，清空所有选择状态
     */
    public void exitSelectionMode() {
        isRangeSelectMode = false;
        selectedMessages.clear();
        rangeStart = null;
        rangeEnd = null;
        confirmButtonBounds = null;
    }
}
