package top.xuyangjerry.mcmod.lcp.client.forward;

import top.xuyangjerry.mcmod.lcp.network.ForwardMessagePayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合并转发消息管理器：存储接收到的合并转发消息，供聊天框渲染使用。
 * 合并转发消息通过特殊的消息文本标记来识别（hover 时显示卡片）。
 */
public final class ForwardMessageManager {

    private static final ForwardMessageManager INSTANCE = new ForwardMessageManager();

    private final Map<String, ForwardMessagePayload> forwardMessages = new ConcurrentHashMap<>();
    private int nextId = 1;

    private ForwardMessageManager() {
    }

    public static ForwardMessageManager getInstance() {
        return INSTANCE;
    }

    /**
     * 存储一条转发消息，返回用于标记的标签文本。
     */
    public synchronized String addForwardMessage(ForwardMessagePayload payload) {
        String id = "FW" + nextId++;
        forwardMessages.put(id, payload);
        if (forwardMessages.size() > 200) {
            String oldest = "FW" + (nextId - 200);
            forwardMessages.remove(oldest);
        }
        return makeTag(id);
    }

    /**
     * 用指定 id 恢复一条转发消息（用于重进世界后从历史文件恢复）。
     * 若该 id 已存在则覆盖。同时确保 nextId 不会与已恢复的 id 冲突。
     *
     * @param id      形如 "FW3" 的转发消息 id（不含方括号）
     * @param payload 已反序列化的转发消息负载
     */
    public synchronized void restoreForwardMessage(String id, ForwardMessagePayload payload) {
        if (id == null || id.isEmpty() || payload == null) return;
        forwardMessages.put(id, payload);
        int numeric = parseForwardNumericId(id);
        if (numeric > 0 && numeric >= nextId) {
            nextId = numeric + 1;
        }
    }

    private static int parseForwardNumericId(String id) {
        if (id == null || !id.startsWith("FW")) return -1;
        try {
            return Integer.parseInt(id.substring(2));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    public ForwardMessagePayload getForwardMessage(String id) {
        return forwardMessages.get(id);
    }

    public static String makeTag(String id) {
        return "[Forward #" + id + "]";
    }

    public static String extractId(String text) {
        if (text == null) return null;
        int idx = text.indexOf("[Forward #");
        if (idx < 0) return null;
        int end = text.indexOf("]", idx);
        if (end < 0) return null;
        return text.substring(idx + "[Forward #".length(), end);
    }

    /**
     * 将转发消息转换为普通文本行（用于右键菜单等需要文本内容的场景）。
     */
    public static List<String> toTextLines(ForwardMessagePayload payload) {
        List<String> lines = new ArrayList<>();
        lines.add("[" + payload.forwarder() + " 转发的聊天记录]");
        // QQ来源（forwarder以"["开头）的转发消息，子消息前缀应为 [sender]
        boolean fromQq = payload.forwarder() != null && payload.forwarder().startsWith("[");
        for (ForwardMessagePayload.ForwardedMessage msg : payload.messages()) {
            String prefix = fromQq ? ("[" + msg.sender() + "] ") : (msg.sender() + " ");
            lines.add(prefix + msg.content());
        }
        return lines;
    }
}
