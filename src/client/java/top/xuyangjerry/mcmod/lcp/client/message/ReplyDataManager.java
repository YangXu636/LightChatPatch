package top.xuyangjerry.mcmod.lcp.client.message;

import top.xuyangjerry.mcmod.lcp.network.ReplyMessagePayload;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回复数据管理器：存储回复消息的关联数据（被回复者、被回复内容、原始消息UUID）。
 * 供 ReplyHoverRenderer 和 ReplyDetailPopup 查询。
 */
public final class ReplyDataManager {

    private static final ReplyDataManager INSTANCE = new ReplyDataManager();
    private static final int MAX_CACHE = 500;

    private final Map<String, ReplyMessagePayload> replyData = new ConcurrentHashMap<>();

    private ReplyDataManager() {
    }

    public static ReplyDataManager getInstance() {
        return INSTANCE;
    }

    /**
     * 存入回复数据，返回用于标签的 UUID。
     */
    public String addReplyData(ReplyMessagePayload payload) {
        String uuid = payload.originalMessageUuid();
        if (uuid == null || uuid.isEmpty()) return null;

        replyData.put(uuid, payload);
        trimCache();
        return uuid;
    }

    public ReplyMessagePayload getReplyData(String uuid) {
        if (uuid == null) return null;
        return replyData.get(uuid);
    }

    /**
     * 从文本中提取 [Reply #uuid] 标签中的 UUID。
     */
    public static String extractUuid(String text) {
        if (text == null) return null;
        int idx = text.indexOf("[Reply #");
        if (idx < 0) return null;
        int end = text.indexOf("]", idx);
        if (end < 0) return null;
        return text.substring(idx + "[Reply #".length(), end);
    }

    public void clear() {
        replyData.clear();
    }

    private void trimCache() {
        while (replyData.size() > MAX_CACHE) {
            String oldest = replyData.keySet().iterator().next();
            replyData.remove(oldest);
        }
    }
}
