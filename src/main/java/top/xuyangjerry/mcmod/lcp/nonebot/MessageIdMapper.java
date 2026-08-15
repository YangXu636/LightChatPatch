package top.xuyangjerry.mcmod.lcp.nonebot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MC消息UUID与QQ消息ID的双向映射管理器。
 * 用于支持QQ群回复消息与MC消息的跨平台引用。
 * 带容量上限的LRU缓存，避免无限增长。
 */
public final class MessageIdMapper {
    private static final int MAX_SIZE = 1000;
    private static final MessageIdMapper INSTANCE = new MessageIdMapper();

    private final Map<String, String> mcToQq = new LinkedHashMap<>(MAX_SIZE, 0.75f, true);
    private final Map<String, String> qqToMc = new LinkedHashMap<>(MAX_SIZE, 0.75f, true);

    private MessageIdMapper() {
    }

    public static MessageIdMapper getInstance() {
        return INSTANCE;
    }

    /**
     * 记录 MC UUID 与 QQ message_id 的双向映射。
     */
    public synchronized void put(String mcUuid, String qqMessageId) {
        if (mcUuid == null || qqMessageId == null || mcUuid.isEmpty() || qqMessageId.isEmpty()) {
            return;
        }
        mcToQq.put(mcUuid, qqMessageId);
        qqToMc.put(qqMessageId, mcUuid);
        evictIfNeeded();
    }

    /**
     * 根据 MC UUID 查询对应的 QQ message_id。
     */
    public synchronized String getQqMessageId(String mcUuid) {
        if (mcUuid == null) return null;
        return mcToQq.get(mcUuid);
    }

    /**
     * 根据 QQ message_id 查询对应的 MC UUID。
     */
    public synchronized String getMcUuid(String qqMessageId) {
        if (qqMessageId == null) return null;
        return qqToMc.get(qqMessageId);
    }

    private void evictIfNeeded() {
        while (mcToQq.size() > MAX_SIZE) {
            Map.Entry<String, String> oldest = mcToQq.entrySet().iterator().next();
            mcToQq.remove(oldest.getKey());
            qqToMc.remove(oldest.getValue());
        }
        while (qqToMc.size() > MAX_SIZE) {
            Map.Entry<String, String> oldest = qqToMc.entrySet().iterator().next();
            qqToMc.remove(oldest.getKey());
            mcToQq.remove(oldest.getValue());
        }
    }
}
