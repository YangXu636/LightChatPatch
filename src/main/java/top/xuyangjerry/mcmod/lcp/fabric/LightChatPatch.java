package top.xuyangjerry.mcmod.lcp.fabric;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.PlayerChatMessage;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.xuyangjerry.mcmod.lcp.bws.BannedWordsManager;
import top.xuyangjerry.mcmod.lcp.config.LcpConfig;
import top.xuyangjerry.mcmod.lcp.mute.MuteManager;
import top.xuyangjerry.mcmod.lcp.nonebot.NoneBotConfig;
import top.xuyangjerry.mcmod.lcp.nonebot.NoneBotServerManager;
import top.xuyangjerry.mcmod.lcp.network.BannedWordsPayload;
import top.xuyangjerry.mcmod.lcp.network.ClientHelloPayload;
import top.xuyangjerry.mcmod.lcp.network.ForwardMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.ImagePayload;
import top.xuyangjerry.mcmod.lcp.network.ImageUploadPayload;
import top.xuyangjerry.mcmod.lcp.network.MentionMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.MessageUuidPayload;
import top.xuyangjerry.mcmod.lcp.network.MutePayload;
import top.xuyangjerry.mcmod.lcp.network.ReplyMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.RichMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.SyncModdedPlayersPayload;
import top.xuyangjerry.mcmod.lcp.network.UuidRequestPayload;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LightChatPatch implements ModInitializer {
	public static final String MOD_ID = "light_chat_patch";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		top.xuyangjerry.mcmod.lcp.LightChatPatch.init();
		LcpConfig.load();
		NoneBotConfig.load();
		registerPayloads();
		registerServerEvents();
		registerCommands();
		LOGGER.info("Light Chat Patch initialized");
	}

	private void registerPayloads() {
		PayloadTypeRegistry.clientboundPlay().register(ImagePayload.TYPE, ImagePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(RichMessagePayload.TYPE, RichMessagePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ForwardMessagePayload.TYPE, ForwardMessagePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ReplyMessagePayload.TYPE, ReplyMessagePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MentionMessagePayload.TYPE, MentionMessagePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(MessageUuidPayload.TYPE, MessageUuidPayload.CODEC);
		// 问题4②：服务端同步的"有mod玩家列表"包
		PayloadTypeRegistry.clientboundPlay().register(SyncModdedPlayersPayload.TYPE, SyncModdedPlayersPayload.CODEC);
		// BWS = Banned Words：S2C 同步词库给 OP 玩家
		PayloadTypeRegistry.clientboundPlay().register(BannedWordsPayload.TYPE, BannedWordsPayload.CODEC);
		// Mute 禁言系统：S2C 同步禁言列表给 OP 玩家
		PayloadTypeRegistry.clientboundPlay().register(MutePayload.TYPE, MutePayload.CODEC);

		PayloadTypeRegistry.serverboundPlay().register(ClientHelloPayload.TYPE, ClientHelloPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ImageUploadPayload.TYPE, ImageUploadPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ForwardMessagePayload.TYPE, ForwardMessagePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ReplyMessagePayload.TYPE, ReplyMessagePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(MentionMessagePayload.TYPE, MentionMessagePayload.CODEC);
		// BWS = Banned Words：C2S OP 玩家从客户端 GUI 增/删/改词库
		PayloadTypeRegistry.serverboundPlay().register(BannedWordsPayload.TYPE, BannedWordsPayload.CODEC);
		// Mute 禁言系统：C2S OP 玩家从客户端 GUI/右键菜单 发起禁言/解禁
		PayloadTypeRegistry.serverboundPlay().register(MutePayload.TYPE, MutePayload.CODEC);
		// UUID 统一：C2S 客户端请求服务端生成消息UUID
		PayloadTypeRegistry.serverboundPlay().register(UuidRequestPayload.TYPE, UuidRequestPayload.CODEC);
	}

	/**
	 * 服务端事件注册：放在 ModInitializer 中而非 DedicatedServerModInitializer，
	 * 确保单人游戏（Integrated Server）也能正常接收 ImageUploadPayload 等数据包。
	 */
	private void registerServerEvents() {
		ServerLifecycleEvents.SERVER_STARTED.register((server) -> {
			NoneBotServerManager.onServerStarted(server);
			// BWS = Banned Words：服务端启动时加载违禁词词库
			BannedWordsManager.onServerStarted(server);
			// Mute 禁言系统：启动初始化
			MuteManager.onServerStarted(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register((server) -> {
			NoneBotServerManager.onServerStopping(server);
			// BWS = Banned Words：服务端关闭时保存违禁词词库
			BannedWordsManager.onServerStopping(server);
			// Mute 禁言系统：清空（非持久化）
			MuteManager.onServerStopping(server);
		});
		ServerMessageEvents.CHAT_MESSAGE.register(NoneBotServerManager::onChatMessage);
		ServerMessageEvents.GAME_MESSAGE.register(NoneBotServerManager::onGameMessage);

		// Mute 禁言系统：ALLOW_CHAT_MESSAGE 中拦截被禁言玩家的原版聊天消息
		// BWS 违禁词过滤：在 ALLOW_CHAT_MESSAGE 中过滤并直接广播，确保所有玩家看到过滤结果
		net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
			if (MuteManager.checkMutedAndTip(sender)) {
				return false; // 拦截，不继续广播
			}
			// BWS 过滤：获取消息文本并过滤
			String originalText = message.decoratedContent().getString();
			String filteredText = top.xuyangjerry.mcmod.lcp.bws.BannedWordsManager.filter(originalText);
			if (!originalText.equals(filteredText)) {
				// 有违禁词：直接广播过滤后文本，然后拦截原始消息
				Component filteredComponent = Component.literal(filteredText);
				for (ServerPlayer p : BannedWordsManager.getServer().getPlayerList().getPlayers()) {
					p.sendSystemMessage(filteredComponent);
				}
				LightChatPatch.LOGGER.info("[LCP][BWS] 过滤违禁词后重新广播: {} -> {}",
						originalText, filteredText);
				return false; // 拦截原始消息
			}
			return true; // 放行
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				NoneBotServerManager.onPlayerJoin(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				NoneBotServerManager.onPlayerDisconnect(handler.getPlayer()));

		ServerPlayNetworking.registerGlobalReceiver(ClientHelloPayload.TYPE,
				(payload, context) -> {
					NoneBotServerManager.onClientHello(context.player(), payload.version());
					// Mute：客户端握手后，若玩家是 OP 则同步禁言列表
					MuteManager.syncToPlayerIfOp(context.player());
					// BWS：客户端握手后，若玩家是 OP 则同步违禁词词库
					BannedWordsManager.syncToPlayerIfOp(context.player());
				});

		ServerPlayNetworking.registerGlobalReceiver(ImageUploadPayload.TYPE,
				(payload, context) -> {
					// Mute 禁言系统：拦截被禁言玩家的图片上传
					if (MuteManager.checkMutedAndTip(context.player())) return;
					NoneBotServerManager.onPlayerImageUpload(context.player(),
							payload.imageData(), payload.width(), payload.height());
				});

		ServerPlayNetworking.registerGlobalReceiver(ForwardMessagePayload.TYPE,
				(payload, context) -> {
					// Mute 禁言系统：拦截被禁言玩家的转发消息
					if (MuteManager.checkMutedAndTip(context.player())) return;
					NoneBotServerManager.onForwardMessage(context.player(), payload);
				});

		ServerPlayNetworking.registerGlobalReceiver(ReplyMessagePayload.TYPE,
				(payload, context) -> {
					// Mute 禁言系统：拦截被禁言玩家的回复消息
					if (MuteManager.checkMutedAndTip(context.player())) return;
					NoneBotServerManager.onReplyMessage(context.player(), payload);
				});

		ServerPlayNetworking.registerGlobalReceiver(MentionMessagePayload.TYPE,
				(payload, context) -> {
					// Mute 禁言系统：拦截被禁言玩家的@消息
					if (MuteManager.checkMutedAndTip(context.player())) return;
					NoneBotServerManager.onMentionMessage(context.player(), payload);
				});

		// BWS = Banned Words：接收客户端（OP玩家）发来的词库更新请求，仅日志输出
		ServerPlayNetworking.registerGlobalReceiver(BannedWordsPayload.TYPE,
				(payload, context) -> BannedWordsManager.handleClientRequest(context.player(), payload));

		// Mute 禁言系统：接收客户端（OP玩家）发来的禁言/解禁请求
		ServerPlayNetworking.registerGlobalReceiver(MutePayload.TYPE,
				(payload, context) -> {
					String result = MuteManager.handleClientRequest(context.player(), payload);
					context.player().sendSystemMessage(Component.literal("[LCP][Mute] " + result));
				});

		// UUID 统一：接收客户端发来的 UUID 请求，服务端生成后通过 MessageUuidPayload 回传
		ServerPlayNetworking.registerGlobalReceiver(UuidRequestPayload.TYPE,
				(payload, context) -> {
					String uuid = NoneBotServerManager.generateMessageUuidPublic(
							payload.sender(), payload.contentText());
					if (uuid != null && context.player() != null) {
						ServerPlayNetworking.send(context.player(),
								new MessageUuidPayload(uuid, payload.contentText()));
					}
				});
	}

	// ==================== /lcp 命令注册 ====================

	private void registerCommands() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			// ========== /lcp bws ... 违禁词系统 ==========
			var bwsListNode = Commands.literal("list").executes(this::bwsList);
			var bwsAddNode = Commands.literal("add")
					.then(Commands.argument("word", StringArgumentType.greedyString())
							.executes(ctx -> bwsAdd(ctx, StringArgumentType.getString(ctx, "word"))));
			var bwsRemoveNode = Commands.literal("remove")
					.then(Commands.argument("word", StringArgumentType.greedyString())
							.executes(ctx -> bwsRemove(ctx, StringArgumentType.getString(ctx, "word"))));
			var bwsModifyNode = Commands.literal("modify")
					.then(Commands.argument("oldWord", StringArgumentType.word())
							.then(Commands.argument("newWord", StringArgumentType.greedyString())
									.executes(ctx -> bwsModify(
											ctx,
											StringArgumentType.getString(ctx, "oldWord"),
											StringArgumentType.getString(ctx, "newWord")))));
			var bwsRoot = Commands.literal("bws")
					.requires(src -> src.permissions().hasPermission(
							net.minecraft.server.permissions.Permissions.COMMANDS_OWNER))
					.then(bwsListNode)
					.then(bwsAddNode)
					.then(bwsRemoveNode)
					.then(bwsModifyNode);

			// ========== /lcp mute ... 禁言系统 ==========
			// 公共叶子节点: /lcp mute add <targets> <seconds>  （带或不带 reason）
			var secondsNode = Commands.argument("seconds", IntegerArgumentType.integer(1))
					.executes(ctx -> muteAdd(
							ctx,
							EntityArgument.getPlayers(ctx, "targets"),
							IntegerArgumentType.getInteger(ctx, "seconds") * 20,
							""))
					.then(Commands.argument("reason", StringArgumentType.greedyString())
							.executes(ctx -> muteAdd(
									ctx,
									EntityArgument.getPlayers(ctx, "targets"),
									IntegerArgumentType.getInteger(ctx, "seconds") * 20,
									StringArgumentType.getString(ctx, "reason"))));

			// 公共叶子节点: /lcp mute add <targets> infinite  （带或不带 reason）
			var infiniteNode = Commands.literal("infinite")
					.executes(ctx -> muteAdd(
							ctx,
							EntityArgument.getPlayers(ctx, "targets"),
							MuteManager.INFINITE,
							""))
					.then(Commands.argument("reason", StringArgumentType.greedyString())
							.executes(ctx -> muteAdd(
									ctx,
									EntityArgument.getPlayers(ctx, "targets"),
									MuteManager.INFINITE,
									StringArgumentType.getString(ctx, "reason"))));

			// /lcp mute add <targets> <seconds|infinite> [reason]
			var addNode = Commands.literal("add")
					.then(Commands.argument("targets", EntityArgument.players())
							.then(secondsNode)
							.then(infiniteNode));

			// /lcp mute remove <targets>
			var removeNode = Commands.literal("remove")
					.then(Commands.argument("targets", EntityArgument.players())
							.suggests(MUTE_PLAYER_SUGGESTION_PROVIDER)
							.executes(ctx -> muteRemove(
									ctx,
									EntityArgument.getPlayers(ctx, "targets"))));

			var muteRoot = Commands.literal("mute")
					.requires(src -> src.permissions().hasPermission(
							net.minecraft.server.permissions.Permissions.COMMANDS_MODERATOR))
					.then(Commands.literal("list").executes(this::muteList))
					.then(addNode)
					.then(removeNode);

			// ========== 根节点 /lcp ==========
			dispatcher.register(
					Commands.literal("lcp")
							.then(bwsRoot)
							.then(muteRoot)
			);
		});
	}

	/** 自定义补全器：仅显示被禁言的在线玩家。 */
	private static final SuggestionProvider<CommandSourceStack> MUTE_PLAYER_SUGGESTION_PROVIDER =
			(context, builder) -> {
				Collection<ServerPlayer> mutedPlayers = MuteManager.getMutedPlayers();
				List<String> names = new java.util.ArrayList<>();
				for (ServerPlayer p : mutedPlayers) {
					names.add(p.getName().getString());
				}
				return SharedSuggestionProvider.suggest(names, builder);
			};

	// ==================== /lcp mute 命令实现 ====================

	private int muteList(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack src = ctx.getSource();
		List<String> lines = MuteManager.getMuteDescriptions();
		if (lines.isEmpty()) {
			src.sendSystemMessage(Component.literal("[LCP][Mute] 当前没有被禁言的玩家"));
		} else {
			StringBuilder sb = new StringBuilder();
			sb.append("[LCP][Mute] 禁言列表（共 ").append(lines.size()).append(" 人）:\n");
			for (String l : lines) sb.append(l).append('\n');
			src.sendSystemMessage(Component.literal(sb.toString()));
		}
		LightChatPatch.LOGGER.info("[LCP][Mute] 命令 /lcp mute list -> 禁言 {} 人", lines.size());
		return lines.size();
	}

	private int muteAdd(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets, int durationTicks, String reason) {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer muter;
		try {
			muter = src.getPlayerOrException();
		} catch (Exception e) {
			src.sendSystemMessage(Component.literal("[LCP][Mute] 此命令必须由玩家执行（控制台无法判断OP等级对比）"));
			return 0;
		}
		int success = 0;
		for (ServerPlayer target : targets) {
			String result = MuteManager.mute(muter, target, reason, durationTicks);
			src.sendSystemMessage(Component.literal("[LCP][Mute] " + target.getName().getString() + "：" + result));
			if (result.startsWith("已禁言")) success++;
		}
		return success;
	}

	private int muteRemove(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> targets) {
		CommandSourceStack src = ctx.getSource();
		ServerPlayer muter;
		try {
			muter = src.getPlayerOrException();
		} catch (Exception e) {
			src.sendSystemMessage(Component.literal("[LCP][Mute] 此命令必须由玩家执行（控制台无法判断OP等级对比）"));
			return 0;
		}
		int success = 0;
		for (ServerPlayer target : targets) {
			String result = MuteManager.unmute(muter, target);
			src.sendSystemMessage(Component.literal("[LCP][Mute] " + target.getName().getString() + "：" + result));
			if (result.startsWith("已解禁")) success++;
		}
		return success;
	}

	// ==================== /lcp bws 命令实现 ====================

	private int bwsList(CommandContext<CommandSourceStack> ctx) {
		List<String> words = BannedWordsManager.getAllWords();
		CommandSourceStack src = ctx.getSource();
		if (words.isEmpty()) {
			src.sendSystemMessage(Component.literal("[LCP][BWS] 违禁词词库为空"));
			return 0;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("[LCP][BWS] 违禁词列表 (共 ").append(words.size()).append(" 条):\n");
		for (int i = 0; i < words.size(); i++) {
			sb.append("  ").append(i + 1).append(". ").append(words.get(i)).append("\n");
		}
		src.sendSystemMessage(Component.literal(sb.toString()));
		return words.size();
	}

	private int bwsAdd(CommandContext<CommandSourceStack> ctx, String word) {
		boolean ok = BannedWordsManager.addWord(word);
		String msg = ok ? ("已添加违禁词: " + word) : ("违禁词已存在: " + word);
		ctx.getSource().sendSystemMessage(Component.literal("[LCP][BWS] " + msg));
		LightChatPatch.LOGGER.info("[LCP][BWS] 命令 /lcp bws add {} -> {}", word, ok ? "成功" : "失败(已存在)");
		return ok ? 1 : 0;
	}

	private int bwsRemove(CommandContext<CommandSourceStack> ctx, String word) {
		boolean ok = BannedWordsManager.removeWord(word);
		String msg = ok ? ("已删除违禁词: " + word) : ("违禁词不存在: " + word);
		ctx.getSource().sendSystemMessage(Component.literal("[LCP][BWS] " + msg));
		LightChatPatch.LOGGER.info("[LCP][BWS] 命令 /lcp bws remove {} -> {}", word, ok ? "成功" : "失败(不存在)");
		return ok ? 1 : 0;
	}

	private int bwsModify(CommandContext<CommandSourceStack> ctx, String oldWord, String newWord) {
		boolean ok = BannedWordsManager.modifyWord(oldWord, newWord);
		String msg;
		if (ok) {
			msg = "已修改: " + oldWord + " -> " + newWord;
		} else {
			msg = "修改失败：旧词不存在 或 新词已存在 或 新旧词相同";
		}
		ctx.getSource().sendSystemMessage(Component.literal("[LCP][BWS] " + msg));
		LightChatPatch.LOGGER.info("[LCP][BWS] 命令 /lcp bws modify {} -> {} : {}",
				oldWord, newWord, ok ? "成功" : "失败");
		return ok ? 1 : 0;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}