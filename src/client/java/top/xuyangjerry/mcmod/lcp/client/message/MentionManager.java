package top.xuyangjerry.mcmod.lcp.client.message;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * @提及候选管理器。
 * 负责检测输入框中的@符号、过滤在线玩家、管理候选列表的选中状态。
 *
 * 候选列表激活条件：
 * 1. 光标前最近的@符号后没有空格或换行
 * 2. @前是行首、空格或换行
 * 3. @后的文本（query）能匹配到至少一个候选（包括"@全体"特殊项）
 *
 * 特殊候选：
 * - "@全体"：始终作为第一个候选项出现（query 为空或匹配"全体"）
 * - 所有在线玩家（包括自己）
 */
public final class MentionManager {

    private static final MentionManager INSTANCE = new MentionManager();

    /**
     * @全体 的特殊标识。发送时 content 中保留 "@全体" 文本，
     * mentionedPlayers 列表中用此常量标记全体。
     */
    public static final String ALL = "全体";

    private boolean active = false;
    private List<String> candidates = new ArrayList<>();
    private int selectedIndex = 0;
    private int atPosition = -1;
    private String query = "";

    private MentionManager() {
    }

    public static MentionManager getInstance() {
        return INSTANCE;
    }

    /**
     * 根据输入框文本和光标位置更新候选列表状态。
     *
     * @param text      输入框完整文本
     * @param cursorPos 光标位置（0~text.length()）
     */
    public void update(String text, int cursorPos) {
        if (text == null || cursorPos < 0 || cursorPos > text.length()) {
            cancel();
            return;
        }

        // 从光标位置向前查找最近的@
        int atIdx = -1;
        for (int i = cursorPos - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '@') {
                atIdx = i;
                break;
            }
            if (c == ' ' || c == '\n' || c == '\t') {
                // 遇到空格/换行停止搜索
                break;
            }
        }

        if (atIdx < 0) {
            cancel();
            return;
        }

        // @前必须是行首或空格/换行
        if (atIdx > 0) {
            char before = text.charAt(atIdx - 1);
            if (before != ' ' && before != '\n' && before != '\t') {
                cancel();
                return;
            }
        }

        // @后到光标的文本（query），不能包含空格或换行
        String queryText = text.substring(atIdx + 1, cursorPos);
        for (int i = 0; i < queryText.length(); i++) {
            char c = queryText.charAt(i);
            if (c == ' ' || c == '\n' || c == '\t') {
                cancel();
                return;
            }
        }

        this.atPosition = atIdx;
        this.query = queryText;

        // 构建候选列表：@全体 + 所有在线玩家（含自己）
        List<String> onlinePlayers = getOnlinePlayerNames();
        List<String> filtered = new ArrayList<>();
        String lowerQuery = queryText.toLowerCase();

        // @全体 仅在有 OP 权限时显示（需服务端验证，这里做前置过滤）
        if (hasOpPermission() && (lowerQuery.isEmpty() || ALL.toLowerCase().startsWith(lowerQuery))) {
            filtered.add(ALL);
        }

        for (String name : onlinePlayers) {
            if (lowerQuery.isEmpty() || name.toLowerCase().startsWith(lowerQuery)) {
                filtered.add(name);
            }
        }

        if (filtered.isEmpty()) {
            cancel();
            return;
        }

        if (!active) {
            selectedIndex = 0;
        } else {
            // 保持选中索引在有效范围
            if (selectedIndex >= filtered.size()) {
                selectedIndex = 0;
            }
        }

        candidates = filtered;
        active = true;
    }

    public boolean isActive() {
        return active;
    }

    public List<String> getCandidates() {
        return candidates;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public int getAtPosition() {
        return atPosition;
    }

    public String getSelectedPlayer() {
        if (!active || selectedIndex < 0 || selectedIndex >= candidates.size()) return null;
        return candidates.get(selectedIndex);
    }

    /**
     * 判断当前选中的是否是 @全体。
     */
    public boolean isSelectedAll() {
        String selected = getSelectedPlayer();
        return ALL.equals(selected);
    }

    public void moveUp() {
        if (!active || candidates.isEmpty()) return;
        selectedIndex = (selectedIndex - 1 + candidates.size()) % candidates.size();
    }

    public void moveDown() {
        if (!active || candidates.isEmpty()) return;
        selectedIndex = (selectedIndex + 1) % candidates.size();
    }

    /**
     * 确认选择，返回要插入到输入框的完整@文本（如 "@Steve " 或 "@全体 "）。
     */
    public String confirm() {
        String player = getSelectedPlayer();
        cancel();
        if (player == null) return null;
        return "@" + player + " ";
    }

    public void cancel() {
        active = false;
        candidates = new ArrayList<>();
        selectedIndex = 0;
        atPosition = -1;
        query = "";
    }

    /**
     * 从消息内容中提取被@的玩家名列表（自动获取在线玩家）。
     * 包含特殊标记 "全体"（如果消息中包含 "@全体"）。
     *
     * @param content 消息完整内容
     * @return 被@的玩家名列表（不含@符号，去重，可能包含 "全体"）
     */
    public static List<String> extractMentions(String content) {
        List<String> result = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return result;
        }

        // 先检查 @全体
        if (containsMentionTag(content, ALL)) {
            result.add(ALL);
        }

        // 再检查每个在线玩家
        List<String> onlinePlayers = getOnlinePlayerNames();
        for (String player : onlinePlayers) {
            if (containsMentionTag(content, player)) {
                if (!result.contains(player)) {
                    result.add(player);
                }
            }
        }

        return result;
    }

    /**
     * 检查 content 中是否包含有效的 @tag（前后为边界）。
     */
    private static boolean containsMentionTag(String content, String player) {
        String tag = "@" + player;
        int idx = content.indexOf(tag);
        while (idx >= 0) {
            int afterIdx = idx + tag.length();
            boolean validBefore = (idx == 0) || isBoundary(content.charAt(idx - 1));
            boolean validAfter = (afterIdx >= content.length()) || isBoundary(content.charAt(afterIdx));
            if (validBefore && validAfter) {
                return true;
            }
            idx = content.indexOf(tag, idx + 1);
        }
        return false;
    }

    private static boolean isBoundary(char c) {
        return c == ' ' || c == '\n' || c == '\t' || c == '\r';
    }

    private static List<String> getOnlinePlayerNames() {
        List<String> names = new ArrayList<>();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return names;

        for (Player player : level.players()) {
            names.add(player.getName().getString());
        }
        return names;
    }

    private static String getSelfName() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.getName().getString() : "";
    }

    /**
     * 检查本地玩家是否有 OP 权限（客户端推测，最终以服务端为准）。
     * MC 26.2 移除了 getPermissionLevel()，改用新的 PermissionSet + Permission 系统。
     * 单人游戏中根据是否开启作弊判断；多人游戏中使用 Permissions.COMMANDS_GAMEMASTER（等级 >= 2）判断。
     */
    private static boolean hasOpPermission() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        // 单人游戏：房主视为有 OP 权限
        if (mc.getSingleplayerServer() != null) {
            return mc.getSingleplayerServer().isSingleplayerOwner(
                    new net.minecraft.server.players.NameAndId(mc.player.getGameProfile()));
        }
        // 多人游戏：通过新权限系统判断（等级 >= 2，对应 COMMANDS_GAMEMASTER）
        return mc.player.permissions().hasPermission(
                net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }
}
