package top.xuyangjerry.mcmod.lcp.client.message;

import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.SelectorContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 消息信息解析类。
 *
 * 关键理解（关于用户提到的 selector）：
 *   - 【原版玩家消息】的发送者部分是 Component.selector()（即 SelectorContents），
 *     例如 chat.type.text 的第一个参数 args[0] 通常是 SelectorContents 或 LiteralContents，
 *     当使用 SelectorContents 时，原版客户端会自动为其附加 show_entity 悬浮文本（显示玩家实体信息tooltip）。
 *   - 【mod发送的消息】（如 QQ 转发、图片、回复等）使用纯文本 Component.literal()，
 *     发送者前缀是 "<name>" 或 "[name]" 格式的纯字符串，不带 selector 悬浮。
 *
 * 本类新增：
 *   1. from() 中记录原始的 senderComponent（若为 SelectorContents 则保留）
 *   2. 新增 extractPlayerNameFromComponent(Component) 方法：从任意 Component 子树中
 *      深度优先遍历寻找 SelectorContents 或 匹配在线玩家名的 LiteralContents，
 *      返回提取到的玩家名。这用于【右键菜单点击消息中的某个玩家名】时识别目标玩家。
 */
public final class MessageInfo {
    private static final String CHAT_TYPE_TEXT = "chat.type.text";
    private static final String SYSTEM_SENDER = "系统";

    private final String sender;
    private final String content;
    private final boolean isPlayerMessage;
    private final GuiMessage message;
    /**
     * 原始发送者 Component（可能是 SelectorContents 包装的玩家名，带悬浮tooltip；也可能是 LiteralContents）。
     * 如果无法从 TranslatableContents 的 args[0] 提取到对应的子 Component，则为 null。
     */
    private final Component senderComponent;

    private MessageInfo(String sender, String content, boolean isPlayerMessage, GuiMessage message, Component senderComponent) {
        this.sender = sender;
        this.content = content;
        this.isPlayerMessage = isPlayerMessage;
        this.message = message;
        this.senderComponent = senderComponent;
    }

    public String getSender() { return sender; }
    public String getContent() { return content; }
    public boolean isPlayerMessage() { return isPlayerMessage; }
    public GuiMessage getMessage() { return message; }
    public Component getSenderComponent() { return senderComponent; }

    public static MessageInfo from(GuiMessage message) {
        Component component = message.content();
        if (component.getContents() instanceof TranslatableContents tc) {
            String key = tc.getKey();
            if (CHAT_TYPE_TEXT.equals(key)) {
                Object[] args = tc.getArgs();
                if (args.length >= 2) {
                    // args[0] 是发送者组件：可能是 SelectorContents（原版），也可能是 LiteralContents（某些包装）
                    Component senderComp = args[0] instanceof Component c ? c : null;
                    String sender = senderComp != null ? senderComp.getString() : String.valueOf(args[0]);
                    String content = args[1] instanceof Component c ? c.getString() : String.valueOf(args[1]);
                    return new MessageInfo(sender, content, true, message, senderComp);
                }
            }
            if ("commands.message.display.incoming".equals(key) ||
                "commands.message.display.outgoing".equals(key)) {
                return null;
            }
            if (key.startsWith("chat.type.")) {
                return new MessageInfo(SYSTEM_SENDER, component.getString(), false, message, null);
            }
            return new MessageInfo(SYSTEM_SENDER, component.getString(), false, message, null);
        }
        String text = component.getString();

        // 回复消息格式
        if (text.startsWith("↩ ")) {
            int newlineIdx = text.indexOf('\n');
            if (newlineIdx > 0) {
                String replyLine = text.substring(newlineIdx + 1);
                if (replyLine.startsWith("<") && replyLine.indexOf('>') > 1) {
                    int endIdx = replyLine.indexOf('>');
                    String sender = replyLine.substring(1, endIdx);
                    return new MessageInfo(sender, text, true, message, null);
                }
            }
            return new MessageInfo(SYSTEM_SENDER, text, false, message, null);
        }

        if (text.startsWith("<") && text.indexOf('>') > 1) {
            int endIdx = text.indexOf('>');
            String sender = text.substring(1, endIdx);
            String content = text.substring(endIdx + 1).trim();
            return new MessageInfo(sender, content, true, message, null);
        }
        // 转发卡片消息
        int fwIdx = text.indexOf("[Forward #");
        if (fwIdx > 0) {
            int bracketEnd = text.indexOf(']');
            if (bracketEnd > 0 && bracketEnd < fwIdx) {
                String title = text.substring(0, bracketEnd + 1);
                int spaceIdx = title.indexOf(' ');
                String sender = spaceIdx > 1 ? title.substring(1, spaceIdx) : title.substring(1, bracketEnd);
                return new MessageInfo(sender, text, true, message, null);
            }
        }
        // [昵称] 文本 格式（QQ消息等）
        if (text.startsWith("[") && text.indexOf(']') > 1) {
            int bracketEnd = text.indexOf(']');
            String sender = text.substring(1, bracketEnd);
            String content = text.substring(bracketEnd + 1).trim();
            if (!content.isEmpty()) {
                return new MessageInfo(sender, content, true, message, null);
            }
        }
        return new MessageInfo(SYSTEM_SENDER, text, false, message, null);
    }

    // ==================== 玩家名提取工具（用于 selector 检测 / 右键菜单目标定位） ====================

    /**
     * 玩家提取结果：包含玩家名和 UUID（能获取到时）。
     */
    public static final class PlayerTarget {
        public final String name;
        /** UUID 字符串（无连字符格式，或带连字符都可能），无法获取时为 null。 */
        public final String uuidStr;
        /** 来源标识："show_entity"（原版selector最可靠） / "selector" / "text"（纯文本匹配）。 */
        public final String source;

        public PlayerTarget(String name, String uuidStr, String source) {
            this.name = name;
            this.uuidStr = uuidStr;
            this.source = source;
        }
    }

    /**
     * 从鼠标位置的 Style（最优先，含 SHOW_ENTITY）和 Component 树中提取玩家目标。
     *
     * 检测优先级：
     *   1. Style.getHoverEvent() == SHOW_ENTITY 且 type == player（原版玩家名selector最可靠标识，带UUID）
     *   2. 点击位置 Component 中的 SelectorContents
     *   3. 整棵 Component 树中的 SelectorContents / <name> / [name] 文本匹配
     *
     * @param clickedStyle 鼠标精确位置字符的 Style（通过 ChatMessageLocator.getStyleAtMouse 获取）
     * @param clickedComponent 点击位置的 Component（可为 null）
     * @param rootComponent 整条消息根 Component（可为 null，用于回退扫描）
     * @return 玩家目标（至少含 name），失败返回 null
     */
    public static PlayerTarget extractPlayerTarget(Style clickedStyle, Component clickedComponent, Component rootComponent) {
        // 优先级 1：Style 带 SHOW_ENTITY 且 type=player → 原版 selector，100% 可信，且有 UUID
        if (clickedStyle != null) {
            PlayerTarget fromHover = extractFromShowEntity(clickedStyle);
            if (fromHover != null) return fromHover;
        }

        // 优先级 2/3：Component 树扫描（SelectorContents + 纯文本格式）
        String name = extractPlayerNameFromComponents(clickedComponent, rootComponent);
        if (name != null) {
            return new PlayerTarget(name, null, "text");
        }
        return null;
    }

    /**
     * 从 Style 的 SHOW_ENTITY 悬浮事件中提取玩家目标。
     * 仅当 EntityType == PLAYER 时认为是有效玩家。
     */
    public static PlayerTarget extractFromShowEntity(Style style) {
        if (style == null) return null;
        HoverEvent hover = style.getHoverEvent();
        if (hover == null || !(hover instanceof HoverEvent.ShowEntity showEntity)) return null;

        HoverEvent.EntityTooltipInfo info = showEntity.entity();
        if (info == null || info.type != EntityTypes.PLAYER) return null;

        String name = null;
        if (info.name != null && info.name.isPresent()) {
            name = info.name.get().getString();
        }
        String uuidStr = info.uuid != null ? info.uuid.toString() : null;

        if (name == null || name.isEmpty()) {
            // 没有显示名时无法操作，直接返回 null
            return null;
        }
        return new PlayerTarget(name, uuidStr, "show_entity");
    }

    /**
     * 从 Component 子树中深度搜索，尝试提取一个"玩家名"：
     *   优先级 1：找到 SelectorContents（原版选择器），取 pattern 或其解析结果
     *   优先级 2：找到 LiteralContents 的文本中匹配 <Player> 或 [name] 格式，或匹配在线玩家名
     *
     * 用于：右键点击聊天消息中某一字符位置时，从点击的 Style 对应的 Component
     *       （通常是被点击字符所在的最小子组件）中反查玩家名，用于禁言/回复目标定位。
     *
     * @param clickedComponent 被点击位置的 Component（通常是 ChatComponent.getClickedComponent 或通过 Style 定位的子节点）
     * @param rootComponent 整条消息的根 Component（用于回退搜索整棵树）
     * @return 提取到的玩家名，失败返回 null
     */
    public static String extractPlayerNameFromComponents(Component clickedComponent, Component rootComponent) {
        // 1. 先尝试点击的精确组件
        if (clickedComponent != null) {
            String found = scanComponentForPlayer(clickedComponent);
            if (found != null) return found;
        }
        // 2. 回退扫描整条消息（可能点击位置不在精确子组件上）
        if (rootComponent != null && rootComponent != clickedComponent) {
            return scanComponentForPlayer(rootComponent);
        }
        return null;
    }

    /** 扫描单棵 Component 子树，尝试提取玩家名。 */
    private static String scanComponentForPlayer(Component root) {
        if (root == null) return null;
        final String[] result = {null};
        visitComponentTree(root, comp -> {
            if (result[0] != null) return; // 已找到
            // 情况1：SelectorContents（原版玩家名选择器）
            if (comp.getContents() instanceof SelectorContents sc) {
                String pattern = sc.selector().source();
                if (pattern != null && !pattern.isEmpty()) {
                    // 典型情况：@p[name=Steve] 或 直接选择器
                    // 优先用 getString()（已解析成显示名的结果），否则用 pattern
                    String disp = comp.getString();
                    if (disp != null && !disp.isEmpty()) {
                        result[0] = disp;
                        return;
                    }
                    // 尝试从 @p[name=xxx] 中提取 xxx
                    int nameIdx = pattern.indexOf("name=");
                    if (nameIdx >= 0) {
                        int closeIdx = pattern.indexOf(']', nameIdx);
                        String name = closeIdx > nameIdx ? pattern.substring(nameIdx + 5, closeIdx) : pattern.substring(nameIdx + 5);
                        if (!name.isEmpty()) {
                            result[0] = name;
                            return;
                        }
                    }
                    result[0] = pattern;
                }
                return;
            }
            // 情况2：Component.getString() 匹配 <玩家名> 或 [玩家名] 格式
            String text = comp.getString();
            if (text != null && !text.isEmpty()) {
                String extracted = extractPlayerNameFromText(text);
                if (extracted != null) {
                    result[0] = extracted;
                }
            }
        });
        return result[0];
    }

    /** 从文本中尝试提取玩家名：<name>、[name] 或直接匹配在线玩家名。 */
    public static String extractPlayerNameFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        // <Player>
        if (t.startsWith("<") && t.endsWith(">") && t.length() > 2) {
            String name = t.substring(1, t.length() - 1);
            if (isValidPlayerName(name)) return name;
        }
        // [Player]（QQ昵称格式，或纯文本选择器）
        if (t.startsWith("[") && t.endsWith("]") && t.length() > 2) {
            String name = t.substring(1, t.length() - 1);
            if (isValidPlayerName(name)) return name;
        }
        // 纯文本，可能是直接的玩家名（3-16字符，合法玩家名字符）
        if (isValidPlayerName(t)) return t;
        return null;
    }

    /** 粗略的玩家名合法性判断：3-16个字符，仅字母数字下划线。QQ昵称可能更长，这里放宽到1-32非空字符。 */
    private static boolean isValidPlayerName(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.length() > 48) return false; // 宽松，QQ 昵称可能有中文或较长
        // 至少有一个非空白字符
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) return true;
        }
        return false;
    }

    /** 深度优先遍历 Component 树（自身+所有子组件），对每个节点调用 visitor。 */
    public static void visitComponentTree(Component root, Consumer<Component> visitor) {
        if (root == null || visitor == null) return;
        List<Component> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            Component cur = stack.remove(stack.size() - 1);
            visitor.accept(cur);
            // 子组件后进先出保证顺序一致
            List<Component> children = cur.getSiblings();
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.add(children.get(i));
            }
        }
    }
}
