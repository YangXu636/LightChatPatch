package top.xuyangjerry.mcmod.lcp.nonebot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * NoneBot WebSocket 消息协议定义。
 *
 * MC -> NoneBot（服务端推送）:
 *   {"type":"chat","player":"Steve","message":"hello","timestamp":123}
 *   {"type":"event","event":"player_join","player":"Steve","detail":"","timestamp":123}
 *   {"type":"player_image","player":"Steve","base64":"iVBOR...","width":128,"height":128}
 *
 * NoneBot -> MC（NoneBot 下发）:
 *   {"type":"send_message","sender":"QQ用户","message":"你好"}
 *   {"type":"send_message","sender":"QQ用户","message":"回复内容","reply_to_sender":"Steve","reply_to_content":"原文"}
 *   {"type":"send_image","sender":"QQ用户","base64":"iVBOR...","width":128,"height":128}
 *   {"type":"command","command":"say Welcome!"}
 *   {"type":"ping"}
 *
 * MC -> NoneBot 响应:
 *   {"type":"pong","timestamp":123}
 */
public class NoneBotMessage {
    private static final Gson GSON = new GsonBuilder().create();

    private String type;
    private String player;
    private String message;
    private String base64;
    private int width;
    private int height;
    private long timestamp;
    private String command;
    private String event;
    private String detail;
    // QQ群回复消息相关字段
    private String reply_to_sender;
    private String reply_to_content;
    // 被回复消息的平台ID（QQ→MC方向携带，用于反查映射）
    private String reply_to_qq_message_id;
    // MC消息UUID（MC→QQ方向携带，用于建立映射和QQ→MC回传）
    private String mc_message_uuid;
    // MC回复QQ时，目标平台消息ID（MC→QQ方向携带，用于QQ原生回复段）
    private String target_qq_message_id;
    // QQ->MC方向：当前消息的平台消息ID（通用，不局限于QQ），用于建立 映射
    private String source_message_id;
    // NoneBot->MC方向：QQ群消息的真实发送者昵称（QQ用户昵称）
    private String sender;
    // QQ->MC方向：被回复消息的 MC UUID（仅当原始消息是 MC→QQ 转发过来时才有）。
    // 当此值存在时，优先作为 ReplyMessagePayload.originalMessageUuid，避免再拼接 qq_ 前缀。
    private String reply_to_mc_message_uuid;
    // MC->QQ回复消息：被回复原始消息的发送者昵称，QQ端找不到原生reply引用时降级显示用
    private String original_sender;
    // MC->QQ回复消息：被回复原始消息的完整文本内容，降级显示用
    private String original_content;
    // MC->QQ回复消息：被回复原始消息的MC UUID，用于Python端反查MC_TO_QQ_MAP获取QQ原生回复引用
    private String original_mc_uuid;
    // 通用扩展字段：MC->QQ和QQ->MC方向都可以附加更多信息（避免频繁加字段）
    private String extra;
    // QQ->MC 方向：send_message_with_media 携带的图片列表（每个元素包含 base64/width/height）
    private java.util.List<ImageData> images;

    /** send_message_with_media 使用的内嵌图片数据结构 */
    public static class ImageData {
        private String base64;
        private int width;
        private int height;

        public ImageData() { }

        public String getBase64() { return base64; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
    }

    public NoneBotMessage() {
    }

    private NoneBotMessage(String type) {
        this.type = type;
        this.timestamp = System.currentTimeMillis();
    }

    // ===== MC -> NoneBot 消息工厂 =====

    public static NoneBotMessage chat(String player, String message) {
        NoneBotMessage msg = new NoneBotMessage("chat");
        msg.player = player;
        msg.message = message;
        return msg;
    }

    /**
     * MC→QQ 聊天消息（携带 MC UUID 用于建立映射）。
     */
    public static NoneBotMessage chat(String player, String message, String mcUuid) {
        NoneBotMessage msg = chat(player, message);
        msg.mc_message_uuid = mcUuid;
        return msg;
    }

    /**
     * MC→QQ 回复消息（携带 MC UUID 和目标 QQ message_id，QQ 端用原生回复段）。
     * 同时携带 original_sender 和 original_content，用于 QQ 端找不到原生 reply 引用时降级显示。
     * originalMcUuid 为被回复原始消息的 MC UUID，用于 Python 端反查 MC_TO_QQ_MAP。
     */
    public static NoneBotMessage replyChat(String player, String message, String mcUuid,
                                           String targetQqMessageId,
                                           String originalSender, String originalContent,
                                           String originalMcUuid) {
        NoneBotMessage msg = new NoneBotMessage("reply_chat");
        msg.player = player;
        msg.message = message;
        msg.mc_message_uuid = mcUuid;
        msg.target_qq_message_id = targetQqMessageId;
        msg.original_sender = originalSender;
        msg.original_content = originalContent;
        msg.original_mc_uuid = originalMcUuid;
        return msg;
    }

    // 兼容旧调用（不传 original_* 时置空）
    public static NoneBotMessage replyChat(String player, String message, String mcUuid, String targetQqMessageId) {
        return replyChat(player, message, mcUuid, targetQqMessageId, null, null, null);
    }

    public static NoneBotMessage event(String event, String player, String detail) {
        NoneBotMessage msg = new NoneBotMessage("event");
        msg.event = event;
        msg.player = player;
        msg.detail = detail != null ? detail : "";
        return msg;
    }

    public static NoneBotMessage playerImage(String player, String base64, int width, int height) {
        NoneBotMessage msg = new NoneBotMessage("player_image");
        msg.player = player;
        msg.base64 = base64;
        msg.width = width;
        msg.height = height;
        return msg;
    }

    public static NoneBotMessage pong() {
        return new NoneBotMessage("pong");
    }

    /**
     * QQ→MC 方向：MC 端处理完消息后，回传生成的 mc_uuid 给 Python 端，
     * 供 Python 建立 mc_uuid ↔ source_message_id 映射。
     * 映射由且仅由 Python 端维护，Java 端不再本地查表。
     * sourceMessageId 为通用平台消息ID（不局限于QQ），由 Python 端传入。
     */
    public static NoneBotMessage mcUuidAck(String sourceMessageId, String mcUuid) {
        NoneBotMessage msg = new NoneBotMessage("mc_uuid_ack");
        msg.source_message_id = sourceMessageId;
        msg.mc_message_uuid = mcUuid;
        return msg;
    }

    // ===== NoneBot -> MC 消息工厂 =====

    public static NoneBotMessage sendMessage(String message) {
        NoneBotMessage msg = new NoneBotMessage("send_message");
        msg.message = message;
        return msg;
    }

    public static NoneBotMessage sendMessage(String sender, String message) {
        NoneBotMessage msg = new NoneBotMessage("send_message");
        msg.sender = sender;
        msg.message = message;
        return msg;
    }

    public static NoneBotMessage sendImage(String base64, int width, int height) {
        NoneBotMessage msg = new NoneBotMessage("send_image");
        msg.base64 = base64;
        msg.width = width;
        msg.height = height;
        return msg;
    }

    public static NoneBotMessage sendImage(String sender, String base64, int width, int height) {
        NoneBotMessage msg = new NoneBotMessage("send_image");
        msg.sender = sender;
        msg.base64 = base64;
        msg.width = width;
        msg.height = height;
        return msg;
    }

    public static NoneBotMessage command(String command) {
        NoneBotMessage msg = new NoneBotMessage("command");
        msg.command = command;
        return msg;
    }

    public static NoneBotMessage ping() {
        return new NoneBotMessage("ping");
    }

    // ===== 序列化 =====

    public String toJson() {
        return GSON.toJson(this);
    }

    public static NoneBotMessage fromJson(String json) {
        return GSON.fromJson(json, NoneBotMessage.class);
    }

    // ===== Getters =====

    public String getType() { return type; }
    public String getPlayer() { return player; }
    public String getMessage() { return message; }
    public String getBase64() { return base64; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public long getTimestamp() { return timestamp; }
    public String getCommand() { return command; }
    public String getEvent() { return event; }
    public String getDetail() { return detail; }
    public String getReplyToSender() { return reply_to_sender; }
    public String getReplyToContent() { return reply_to_content; }
    public String getReplyToQqMessageId() { return reply_to_qq_message_id; }
    public String getMcMessageUuid() { return mc_message_uuid; }
    public void setMcMessageUuid(String uuid) { this.mc_message_uuid = uuid; }
    public String getTargetQqMessageId() { return target_qq_message_id; }
    public String getSourceMessageId() { return source_message_id; }
    /** NoneBot -> MC 方向：QQ 群消息真实发送者昵称（如群成员名/系统），未提供时为 null */
    public String getSender() { return sender; }
    /** QQ->MC方向：被回复原始消息的 MC_UUID（如果原始消息是 MC->QQ 转发的消息，则 Java 端直接复用它作为 ReplyMessagePayload.originalMessageUuid）。 */
    public String getReplyToMcMessageUuid() { return reply_to_mc_message_uuid; }
    /** MC->QQ 回复消息：被回复原始消息发送者昵称（找不到原生reply引用时降级显示） */
    public String getOriginalSender() { return original_sender; }
    /** MC->QQ 回复消息：被回复原始消息完整文本（降级显示用） */
    public String getOriginalContent() { return original_content; }
    /** MC->QQ 回复消息：被回复原始消息的 MC UUID（用于 Python 端反查 MC_TO_QQ_MAP） */
    public String getOriginalMcUuid() { return original_mc_uuid; }
    /** 通用扩展字段 */
    public String getExtra() { return extra; }
    /** QQ->MC send_message_with_media 附带的图片列表，未携带时返回 null */
    public java.util.List<ImageData> getImages() { return images; }
}
