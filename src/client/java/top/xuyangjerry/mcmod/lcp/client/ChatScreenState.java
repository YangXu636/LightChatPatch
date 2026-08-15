package top.xuyangjerry.mcmod.lcp.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import top.xuyangjerry.mcmod.lcp.client.message.MessageInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
    /** 右键菜单：禁言点击位置的目标玩家（仅 OP 可见，目标非自己时显示） */
    public static final int ACTION_MUTE_TARGET = 12;
    /** 右键菜单：解禁点击位置的目标玩家（仅 OP 可见，且目标已在禁言列表中时显示） */
    public static final int ACTION_UNMUTE_TARGET = 13;
    /** 打开 LCP 设置界面（在输入框下方的设置按钮中触发） */
    public static final int ACTION_OPEN_SETTINGS = 14;

    public MessageInfo hoverMessage = null;
    public final List<int[]> hoverButtons = new ArrayList<>();

    public boolean menuOpen = false;
    public int menuX = 0;
    public int menuY = 0;
    public MessageInfo menuMessage = null;
    public boolean menuMessageIsSelf = false;
    public boolean menuMessageIsReply = false;
    public final List<int[]> menuOptions = new ArrayList<>();
    /**
     * 右键菜单中禁言/解禁操作的目标玩家名（从 selector 或纯文本提取）。
     * 为 null 表示当前菜单未关联到有效玩家目标，禁言选项不应显示。
     */
    public String menuTargetPlayerName = null;
    /** 目标玩家 UUID 字符串（能获取到时填充，否则为空）。 */
    public String menuTargetPlayerUuid = null;

    public MessageInfo replyingTo = null;
    public int[] replyBarBounds = null;
    public int[] replyBarCloseBounds = null;
    public String replyingToMessageId = null;

    public String highlightedMessageId = null;
    public long highlightEndTime = 0;

    public boolean isRangeSelectMode = false;
    public final Set<GuiMessage> selectedMessages = new LinkedHashSet<>();
    public GuiMessage rangeStart = null;
    public GuiMessage rangeEnd = null;
    public int[] confirmButtonBounds = null;

    public Button imageButton = null;

    private boolean initialized = false;

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    public void resetHoverState() {
        hoverButtons.clear();
        hoverMessage = null;
    }

    public void exitSelectionMode() {
        isRangeSelectMode = false;
        selectedMessages.clear();
        rangeStart = null;
        rangeEnd = null;
        confirmButtonBounds = null;
    }
}