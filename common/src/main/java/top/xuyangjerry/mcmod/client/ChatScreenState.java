package top.xuyangjerry.mcmod.client;

import top.xuyangjerry.mcmod.client.message.MessageInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 每个 ChatScreen 实例对应一份状态，替代原 ChatScreenMixin 中的 @Unique 字段。
 * 由 ChatScreenHandler 通过 WeakHashMap 管理生命周期。
 */
public final class ChatScreenState {

    public static final int ACTION_COPY = 0;
    public static final int ACTION_REPLY = 1;
    public static final int ACTION_PLUS_ONE = 2;

    // --- Hover button state (每帧重置) ---
    public MessageInfo hoverMessage = null;
    public final List<int[]> hoverButtons = new ArrayList<>();

    // --- Right-click menu state ---
    public boolean menuOpen = false;
    public int menuX = 0;
    public int menuY = 0;
    public MessageInfo menuMessage = null;
    public boolean menuMessageIsSelf = false;
    public final List<int[]> menuOptions = new ArrayList<>();

    // --- Reply state (QQ-style) ---
    public MessageInfo replyingTo = null;
    public int[] replyBarBounds = null;
    public int[] replyBarCloseBounds = null;
    public String replyingToMessageId = null;

    // --- Highlight state ---
    public String highlightedMessageId = null;
    public long highlightEndTime = 0;

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
}
