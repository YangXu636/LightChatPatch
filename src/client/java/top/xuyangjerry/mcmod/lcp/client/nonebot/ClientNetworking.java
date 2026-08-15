package top.xuyangjerry.mcmod.lcp.client.nonebot;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import top.xuyangjerry.mcmod.lcp.LightChatPatch;
import top.xuyangjerry.mcmod.lcp.config.LcpConfig;
import top.xuyangjerry.mcmod.lcp.client.forward.ForwardMessageManager;
import top.xuyangjerry.mcmod.lcp.client.message.ReplyDataManager;
import top.xuyangjerry.mcmod.lcp.network.ClientHelloPayload;
import top.xuyangjerry.mcmod.lcp.network.ForwardMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.ImagePayload;
import top.xuyangjerry.mcmod.lcp.network.MentionMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.MessageUuidPayload;
import top.xuyangjerry.mcmod.lcp.network.ReplyMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.RichMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.SyncModdedPlayersPayload;
import top.xuyangjerry.mcmod.lcp.network.BannedWordsPayload;
import top.xuyangjerry.mcmod.lcp.network.MutePayload;
import top.xuyangjerry.mcmod.lcp.client.bws.BannedWordsClientManager;
import top.xuyangjerry.mcmod.lcp.client.mute.MuteClientManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ClientNetworking {

    private static final String MOD_VERSION = "1.0.0";
    /**
     * 服务端是否安装了 LCP mod：
     * - null = 未判定（连接后 5 秒内等待 S2C Payload）
     * - true = 已确认服务端装 mod（收到任一 LCP 自定义 S2C Payload）
     * - false = 已确认服务端未装 mod（超时无任何 LCP Payload 或收到 modded=false 标志）
     */
    private static volatile Boolean serverModInstalled = null;
    private static final long MOD_DETECT_TIMEOUT_MS = 5000;
    private static volatile long lastJoinMs = 0;
    /**
     * 问题4②：服务端同步给客户端的"当前有哪些已安装 LCP mod 的玩家"列表。
     * 列表由服务端 SyncModdedPlayersPayload 推送，内容只包含"在线且已握过手"的玩家。
     * ForwardTargetScreen（转发选人界面）优先使用此列表来显示目标，
     * 这样 P1 在服务器上就能看到 P2（如果 P2 也装了 mod），否则 P1 UI 只会显示 P1 自己的假列表。
     */
    private static final List<SyncModdedPlayersPayload.ModdedPlayerEntry> serverSideModdedPlayers =
            new ArrayList<>();

    private ClientNetworking() {
    }

    /**
     * 问题1 + 问题3 + 问题2增强：构造发送者标签。
     * - QQ消息 sender 格式为 "[QQname]"（服务器端已包装）：直接返回纯文本，不再包 <>。
     * - MC玩家消息 sender 格式为纯名字：按原版风格包装为 "<玩家名>"，
     *   若对应在线 MC 玩家，附加 SHOW_ENTITY 悬浮事件（EntityType=PLAYER），
     *   使客户端能在右键菜单中识别该玩家并提供禁言/解禁选项。
     * - 空或null：返回 "<>" 兜底。
     * 不使用任何 ClickEvent（SuggestCommand 与显示无关，且会引入不必要的交互副作用）。
     */
    private static MutableComponent buildSenderTag(String senderName) {
        if (senderName == null || senderName.isEmpty()) {
            return Component.literal("<>");
        }
        // QQ 格式：已包含方括号 [xxx]，直接作为纯文本返回
        if (senderName.startsWith("[")) {
            return Component.literal(senderName);
        }
        // MC 玩家：原版风格 <玩家名>，附加 SHOW_ENTITY 悬浮事件
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.getServer() != null) {
            ServerPlayer player = mc.level.getServer().getPlayerList().getPlayerByName(senderName);
            if (player != null) {
                UUID playerUuid = player.getUUID();
                HoverEvent.EntityTooltipInfo tooltipInfo = new HoverEvent.EntityTooltipInfo(
                        EntityTypes.PLAYER, playerUuid, Optional.of(player.getDisplayName()));
                HoverEvent showEntity = new HoverEvent.ShowEntity(tooltipInfo);
                return Component.literal("<" + senderName + ">")
                        .withStyle(Style.EMPTY.withHoverEvent(showEntity));
            }
        }
        // 未找到玩家，回退为纯文本
        return Component.literal("<" + senderName + ">");
    }

    /**
     * 对 <被回复者>: 标签做同样处理。
     * 注意：被回复者可能是 QQ "[name]" 或 MC 玩家名 "name"，
     * 统一先通过 buildSenderTag 处理，再加 ": "。
     */
    private static MutableComponent buildReplyTargetTag(String targetName) {
        return buildSenderTag(targetName).append(Component.literal(": "));
    }

    /**
     * 服务端是否已安装 LCP mod（已判定完成时）。
     * 若仍在判定窗口内（5 秒），保守返回 true（允许 UI 显示，网络发送时会被服务端忽略/报错）。
     */
    public static boolean isServerModInstalled() {
        if (serverModInstalled == null) {
            long elapsed = System.currentTimeMillis() - lastJoinMs;
            if (elapsed >= MOD_DETECT_TIMEOUT_MS) {
                // 超时仍未收到任何 LCP S2C Payload：视为未装 mod
                serverModInstalled = false;
            }
        }
        return serverModInstalled == null ? true : serverModInstalled;
    }

    /** 标记服务端已装 mod（收到任一 LCP S2C Payload 时调用） */
    private static void markServerModded() {
        serverModInstalled = true;
    }

    public static void registerPayloads() {
        // ClientHelloPayload 已在 main 入口点注册 serverboundPlay，此处无需重复注册
    }

    public static void registerReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(ImagePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> handleImage(payload));
        });

        ClientPlayNetworking.registerGlobalReceiver(RichMessagePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> handleRichMessage(payload));
        });

        ClientPlayNetworking.registerGlobalReceiver(ForwardMessagePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> handleForwardMessage(payload));
        });

        ClientPlayNetworking.registerGlobalReceiver(ReplyMessagePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> handleReplyMessage(payload));
        });

        ClientPlayNetworking.registerGlobalReceiver(MentionMessagePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> handleMentionMessage(payload));
        });

        ClientPlayNetworking.registerGlobalReceiver(MessageUuidPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> handleMessageUuid(payload));
        });

        // 问题4②：接收服务端同步的"有mod玩家列表"（转发选人界面用）
        ClientPlayNetworking.registerGlobalReceiver(SyncModdedPlayersPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> handleSyncModdedPlayers(payload));
        });

        // BWS = Banned Words：接收服务端同步的违禁词词库
        ClientPlayNetworking.registerGlobalReceiver(BannedWordsPayload.TYPE, (payload, context) -> {
            context.client().execute(() -> handleBannedWords(payload));
        });

        // Mute = 禁言系统：接收服务端同步的禁言列表
        ClientPlayNetworking.registerGlobalReceiver(MutePayload.TYPE, (payload, context) -> {
            context.client().execute(() -> MuteClientManager.handleSync(payload));
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            sender.sendPacket(new ClientHelloPayload(MOD_VERSION));
            LightChatPatch.LOGGER.info("[LCP.NoneBot] Sent ClientHello to server (version {})", MOD_VERSION);
            // 重置服务端装mod检测状态
            serverModInstalled = null;
            lastJoinMs = System.currentTimeMillis();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            client.execute(() -> {
                ClientImageCache.clear();
                // 断开后清除检测状态（下次重连重新判定）
                serverModInstalled = null;
                // 清除过期的服务端同步有mod玩家列表（下一次连接重新同步）
                synchronized (serverSideModdedPlayers) {
                    serverSideModdedPlayers.clear();
                }
                LightChatPatch.LOGGER.info("[LCP.NoneBot] Cleared image cache on disconnect");
            });
        });
    }

    /** 问题4②：处理服务端同步的"已安装LCP的玩家列表"。 */
    private static void handleSyncModdedPlayers(SyncModdedPlayersPayload payload) {
        markServerModded();
        List<SyncModdedPlayersPayload.ModdedPlayerEntry> newList = payload.entries();
        synchronized (serverSideModdedPlayers) {
            serverSideModdedPlayers.clear();
            if (newList != null) {
                serverSideModdedPlayers.addAll(newList);
            }
        }
        LightChatPatch.LOGGER.info("[LCP.NoneBot] Synced modded players from server ({} entries)",
                newList == null ? 0 : newList.size());
    }

    /**
     * BWS = Banned Words：处理服务端发来的违禁词 Payload。
     * 仅 ACTION_SYNC 会携带完整词库；其他 action 预留。
     */
    private static void handleBannedWords(BannedWordsPayload payload) {
        markServerModded();
        String action = payload.action();
        if (BannedWordsPayload.ACTION_SYNC.equals(action)) {
            BannedWordsClientManager.handleSync(payload);
        } else {
            LightChatPatch.LOGGER.debug("[LCP.NoneBot] Received BannedWordsPayload with action={}", action);
        }
    }

    /**
     * 问题4②：返回"当前已知的、服务端同步过来的、有LCP mod玩家名称列表"（除自己外）。
     * 服务端未同步时返回空列表，调用方需要回退到本地 TabList 等方式获取，
     * 但 ForwardTargetScreen 会优先使用此列表。
     */
    public static List<String> getServerSideModdedPlayerNames() {
        List<SyncModdedPlayersPayload.ModdedPlayerEntry> entries = getServerSideModdedPlayerEntries();
        List<String> result = new ArrayList<>();
        for (SyncModdedPlayersPayload.ModdedPlayerEntry e : entries) {
            result.add(e.name());
        }
        return result;
    }

    /**
     * 问题4②+转发修复：返回服务端同步的完整 modded 玩家条目列表（含自己，含 UUID）。
     * ForwardTargetScreen 用此列表构建选人界面：
     *   - 含自己 → 允许向自己转发
     *   - 含 UUID → fallback 时可通过 PlayerInfo 获取皮肤头像
     */
    public static List<SyncModdedPlayersPayload.ModdedPlayerEntry> getServerSideModdedPlayerEntries() {
        List<SyncModdedPlayersPayload.ModdedPlayerEntry> result = new ArrayList<>();
        synchronized (serverSideModdedPlayers) {
            for (SyncModdedPlayersPayload.ModdedPlayerEntry entry : serverSideModdedPlayers) {
                if (entry == null) continue;
                String n = entry.name();
                if (n == null || n.isEmpty()) continue;
                result.add(entry);
            }
        }
        return result;
    }

    private static void handleImage(ImagePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        markServerModded();

        int imageId = ClientImageCache.addImage(payload.imageData(), payload.width(), payload.height());
        if (imageId < 0) {
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[LCP.NoneBot] [Image receive failed]"));
            return;
        }

        String tag = ClientImageCache.makeTag(imageId);
        Component message;
        if (payload.sender() != null && !payload.sender().isEmpty()) {
            // QQ来源（sender以"["开头）的聊天消息整体（含前缀）使用客户端配置的 qqMessageColor
            int color = payload.sender().startsWith("[")
                    ? LcpConfig.getInstance().getQqMessageColor()
                    : 0xFFFFFFFF;
            message = buildSenderTag(payload.sender()).withStyle(Style.EMPTY.withColor(color))
                    .append(Component.literal(" [图片] (" + payload.width() + "x" + payload.height() + ") " + tag)
                            .withStyle(Style.EMPTY.withColor(color)));
        } else {
            message = Component.literal("[图片] (" + payload.width() + "x" + payload.height() + ") " + tag)
                    .withStyle(Style.EMPTY.withColor(0xFFFFFFFF));
        }
        mc.gui.hud.getChat().addClientSystemMessage(message);
        if (payload.uuid() != null && !payload.uuid().isEmpty()) {
            top.xuyangjerry.mcmod.lcp.client.message.MessageJumpManager.getInstance().registerLastMessage(payload.uuid());
        }
    }

    private static void handleRichMessage(RichMessagePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        markServerModded();

        String sender = payload.sender();
        String content = payload.content();
        if (content == null || content.isEmpty()) return;

        Component message;
        if (sender != null && !sender.isEmpty()) {
            // QQ来源（sender以"["开头）的聊天消息整体（含前缀）使用客户端配置的 qqMessageColor
            int color = sender.startsWith("[")
                    ? LcpConfig.getInstance().getQqMessageColor()
                    : 0xFFFFFFFF;
            message = buildSenderTag(sender).withStyle(Style.EMPTY.withColor(color))
                    .append(Component.literal(" " + content)
                            .withStyle(Style.EMPTY.withColor(color)));
        } else {
            message = Component.literal(content)
                    .withStyle(Style.EMPTY.withColor(0xFFFFFFFF));
        }
        mc.gui.hud.getChat().addClientSystemMessage(message);
        // 注册服务器UUID
        if (payload.uuid() != null && !payload.uuid().isEmpty()) {
            top.xuyangjerry.mcmod.lcp.client.message.MessageJumpManager.getInstance().registerLastMessage(payload.uuid());
        }
    }

    private static void handleForwardMessage(ForwardMessagePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (payload.messages() == null || payload.messages().isEmpty()) return;
        markServerModded();

        String tag = ForwardMessageManager.getInstance().addForwardMessage(payload);
        String forwarder = payload.forwarder();
        int count = payload.messages().size();
        String displayText = "[" + forwarder + " 转发的聊天记录 · " + count + "条] " + tag;
        // QQ来源（forwarder以"["开头）的转发消息使用客户端配置的 qqMessageColor
        int fwdColor = (forwarder != null && forwarder.startsWith("["))
                ? LcpConfig.getInstance().getQqMessageColor()
                : 0xFF66CCFF;
        Component message = Component.literal(displayText)
                .withStyle(Style.EMPTY.withColor(fwdColor));
        mc.gui.hud.getChat().addClientSystemMessage(message);
        // 注册服务器UUID
        if (payload.uuid() != null && !payload.uuid().isEmpty()) {
            top.xuyangjerry.mcmod.lcp.client.message.MessageJumpManager.getInstance().registerLastMessage(payload.uuid());
        }
        LightChatPatch.LOGGER.info("[LCP] Received forward message: tag={}, forwarder={}, count={}", tag, forwarder, count);
    }

    private static void handleReplyMessage(ReplyMessagePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        markServerModded();

        // 存储回复数据，供 ReplyHoverRenderer 查询
        ReplyDataManager.getInstance().addReplyData(payload);

        // QQ风格回复：上方灰色斜体显示被回复内容预览，下方为回复内容
        // [Reply #uuid] 标签保留在末尾用于功能关联（悬浮/跳转/持久化），
        // 但用极暗颜色使其视觉上不可见
        MutableComponent message = Component.empty();
        // 上方预览行：↩ <被回复者>: 被回复内容（截断）
        String originalPreview = truncateForPreview(payload.originalContent(), 40);
        MutableComponent previewLine = Component.literal("↩ ");
        if (payload.originalSender() != null && !payload.originalSender().isEmpty()) {
            previewLine = previewLine.append(buildReplyTargetTag(payload.originalSender()));
        }
        previewLine = previewLine.append(Component.literal(originalPreview + "\n"));
        message.append(previewLine.withStyle(Style.EMPTY.withColor(0xFF888888).withItalic(true)));
        // 回复行：<回复者> 回复内容（sender 为空时只显示回复内容）
        MutableComponent replyLine;
        // 问题2：QQ来源（sender以"["开头）的回复消息正文，使用客户端配置的 qqMessageColor；
        // 预览行保持灰色斜体、隐藏 [Reply #] 标签保持填充色不变。
        int textColor = (payload.sender() != null && payload.sender().startsWith("["))
                ? LcpConfig.getInstance().getQqMessageColor()
                : 0xFFFFFFFF;
        if (payload.sender() != null && !payload.sender().isEmpty()) {
            replyLine = buildSenderTag(payload.sender()).withStyle(Style.EMPTY.withColor(textColor))
                    .append(Component.literal(" " + payload.replyContent())
                            .withStyle(Style.EMPTY.withColor(textColor)));
        } else {
            replyLine = Component.literal(payload.replyContent())
                    .withStyle(Style.EMPTY.withColor(textColor));
        }
        message.append(replyLine);
        // 隐藏的 [Reply #uuid] 标签：颜色与回复背景填充色相同，视觉不可见但 getString() 可提取
        message.append(Component.literal(" [Reply #" + payload.originalMessageUuid() + "]")
                .withStyle(Style.EMPTY.withColor(top.xuyangjerry.mcmod.lcp.client.ChatScreenHandler.REPLY_FILL_COLOR)));
        mc.gui.hud.getChat().addClientSystemMessage(message);
        // 注册回复消息自身的UUID（用于被别人回复时定位）
        if (payload.replyMessageUuid() != null && !payload.replyMessageUuid().isEmpty()) {
            top.xuyangjerry.mcmod.lcp.client.message.MessageJumpManager.getInstance().registerLastMessage(payload.replyMessageUuid());
        }
        LightChatPatch.LOGGER.info("[LCP] Received reply message: sender={}, replyTo={}, replyContentLen={}, replyUuid={}",
                payload.sender(), payload.originalSender(),
                payload.replyContent() != null ? payload.replyContent().length() : 0,
                payload.replyMessageUuid());
    }

    /**
     * 截断预览内容：超过阈值时保留头尾，中间用省略号代替。
     */
    private static String truncateForPreview(String content, int maxLen) {
        if (content == null || content.isEmpty()) return "";
        if (content.length() <= maxLen) return content;
        int head = maxLen / 2;
        int tail = maxLen - head - 3;
        if (content.length() <= head + tail + 3) return content;
        return content.substring(0, head) + "..." + content.substring(content.length() - tail);
    }

    private static void handleMentionMessage(MentionMessagePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        markServerModded();

        // 构造带高亮@的消息组件
        Component message = buildMentionComponent(payload.sender(), payload.content(), payload.mentionedPlayers());
        mc.gui.hud.getChat().addClientSystemMessage(message);
        // 注册服务器UUID
        if (payload.uuid() != null && !payload.uuid().isEmpty()) {
            top.xuyangjerry.mcmod.lcp.client.message.MessageJumpManager.getInstance().registerLastMessage(payload.uuid());
        }

        // 判断是否需要播放声音提示：
        // - @全体：所有装mod玩家都提示
        // - @具体玩家：被@的玩家提示
        String selfName = mc.player != null ? mc.player.getName().getString() : "";
        boolean shouldPlaySound = false;
        if (selfName != null && !selfName.isEmpty() && payload.mentionedPlayers() != null) {
            if (payload.mentionedPlayers().contains(top.xuyangjerry.mcmod.lcp.client.message.MentionManager.ALL)) {
                // @全体：所有人都提示（排除发送者自己，避免自己@全体时也响）
                if (!selfName.equals(payload.sender())) {
                    shouldPlaySound = true;
                }
            } else if (payload.mentionedPlayers().contains(selfName)) {
                shouldPlaySound = true;
            }
        }
        if (shouldPlaySound) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP,
                    0.6f, 1.5f);
        }

        LightChatPatch.LOGGER.info("[LCP] Received mention message: sender={}, mentions={}",
                payload.sender(), payload.mentionedPlayers());
    }

    /**
     * 构造带@高亮的消息组件：@Player 部分黄色加粗，其余文字用QQ消息配置色（QQ来源时）。
     */
    private static Component buildMentionComponent(String sender, String content, List<String> mentions) {
        // 问题2：QQ来源（sender以"["开头）时，正文使用客户端配置的 qqMessageColor；@ 高亮保持黄字加粗不变
        int textColor = (sender != null && sender.startsWith("["))
                ? LcpConfig.getInstance().getQqMessageColor()
                : 0xFFFFFFFF;
        MutableComponent result = buildSenderTag(sender)
                .append(Component.literal(" "))
                .withStyle(Style.EMPTY.withColor(textColor));

        if (mentions == null || mentions.isEmpty()) {
            result.append(Component.literal(content).withStyle(Style.EMPTY.withColor(textColor)));
            return result;
        }

        // 按玩家名长度降序排序，优先匹配长名字
        List<String> sorted = new ArrayList<>(mentions);
        sorted.sort((a, b) -> Integer.compare(b.length(), a.length()));

        int idx = 0;
        while (idx < content.length()) {
            int nextStart = -1;
            String matchedTag = null;

            for (String player : sorted) {
                String tag = "@" + player;
                int pos = content.indexOf(tag, idx);
                if (pos < 0) continue;
                int afterIdx = pos + tag.length();
                boolean validBefore = (pos == 0) || isBoundary(content.charAt(pos - 1));
                boolean validAfter = (afterIdx >= content.length()) || isBoundary(content.charAt(afterIdx));
                if (validBefore && validAfter && (nextStart < 0 || pos < nextStart)) {
                    nextStart = pos;
                    matchedTag = tag;
                }
            }

            if (nextStart < 0) {
                result.append(Component.literal(content.substring(idx))
                        .withStyle(Style.EMPTY.withColor(textColor)));
                break;
            }

            if (nextStart > idx) {
                result.append(Component.literal(content.substring(idx, nextStart))
                        .withStyle(Style.EMPTY.withColor(textColor)));
            }

            result.append(Component.literal(matchedTag)
                    .withStyle(Style.EMPTY.withColor(0xFFFFFF55).withBold(true)));

            idx = nextStart + matchedTag.length();
        }

        return result;
    }

    private static boolean isBoundary(char c) {
        return c == ' ' || c == '\n' || c == '\t' || c == '\r';
    }

    /**
     * 处理服务器发来的原版玩家消息UUID同步。
     * 服务器为每条原版玩家消息生成统一UUID后通过此Payload发送，
     * 客户端在 allMessages 中查找内容匹配的消息并注册UUID。
     */
    private static void handleMessageUuid(MessageUuidPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        markServerModded();

        if (payload.uuid() == null || payload.uuid().isEmpty()) return;
        if (payload.contentText() == null || payload.contentText().isEmpty()) return;

        // 在 allMessages 中查找内容匹配的消息并注册UUID
        boolean registered = top.xuyangjerry.mcmod.lcp.client.message.MessageJumpManager
                .getInstance().registerByContent(payload.uuid(), payload.contentText());
        if (!registered) {
            LightChatPatch.LOGGER.warn("[LCP] handleMessageUuid: failed to match message, uuid={}, contentLen={}",
                    payload.uuid(), payload.contentText().length());
        }
    }
}
