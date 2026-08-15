package top.xuyangjerry.mcmod.lcp.client.screen;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import top.xuyangjerry.mcmod.lcp.network.ForwardMessagePayload;
import top.xuyangjerry.mcmod.lcp.network.SyncModdedPlayersPayload;
import top.xuyangjerry.mcmod.lcp.client.nonebot.ClientNetworking;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class ForwardTargetScreen extends Screen {
    private static final Component TITLE = Component.translatable("light_chat_patch.forward.title");
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 200;
    private static final int ENTRY_HEIGHT = 24;
    private static final int LIST_TOP = 60;
    private static final int LIST_BOTTOM_OFFSET = 40;
    private static final int FACE_SIZE = 16;
    /** 问题6："全体成员"目标的特殊标记名（ForwardMessagePayload.target用此值表示"转发给全体"） */
    public static final String TARGET_ALL_MEMBERS = ForwardMessagePayload.TARGET_ALL_MEMBERS;

    private final Screen parent;
    private final List<String[]> messages;

    private List<? extends Player> players = List.of();
    /** 转发修复：服务端同步的完整 modded 玩家条目列表（含自己，含 UUID），用于头像和向自己转发。 */
    private List<SyncModdedPlayersPayload.ModdedPlayerEntry> serverSyncedEntries = List.of();
    /** 转发修复：实际用于构建选人界面的目标列表（名字顺序），与 entries 保持同步。 */
    private List<String> finalTargetNames = new ArrayList<>();
    private final Set<String> selectedTargets = new LinkedHashSet<>();
    private List<AbstractWidget> entries = new ArrayList<>();
    private int listHeight;
    private int contentHeight;
    private int scrollOffset = 0;
    private int selectedIndex = 0;
    private Button confirmButton;
    /** 问题6：单人存档局域网开放 → 显示所有玩家；多人服务器 → 仅显示自己 */
    private boolean lanOpenMode = false;
    /** 问题6：可转发目标数（含"全体成员"虚拟条目），用于键盘导航范围 */
    private int totalEntryCount = 0;

    public ForwardTargetScreen(Screen parent, List<String[]> messages) {
        super(TITLE);
        this.parent = parent;
        this.messages = messages;
    }

    public ForwardTargetScreen(Screen parent, String senderName, String messageContent) {
        this(parent, java.util.Collections.singletonList(new String[]{senderName, messageContent}));
    }

    @Override
    protected void init() {
        if (this.minecraft != null && this.minecraft.level != null) {
            // level.players() 返回客户端可见的所有玩家（包括专用服务器上的其他玩家），
            // MC 原版会将同一世界中的所有玩家实体发送给每个客户端，所以这里无需按模式过滤。
            this.lanOpenMode = this.minecraft.hasSingleplayerServer();
            this.players = new ArrayList<>(this.minecraft.level.players());

            // 转发修复：优先使用服务端同步的完整 modded 玩家条目列表（含自己、含 UUID）。
            this.serverSyncedEntries = ClientNetworking.getServerSideModdedPlayerEntries();

            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.info(
                    "[LCP] ForwardTargetScreen init: mode={}, visible players={}, server-synced modded={}",
                    this.lanOpenMode ? "LAN-OPEN" : "MULTIPLAYER-SERVER",
                    this.players.size(),
                    this.serverSyncedEntries.size());
            for (var p : this.players) {
                top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.info(
                        "[LCP]   visible player: {}", p.getName().getString());
            }
            for (var e : this.serverSyncedEntries) {
                top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.info(
                        "[LCP]   server-synced modded: {} ({})", e.name(), e.uuid());
            }
        } else {
            this.players = new ArrayList<>();
            this.serverSyncedEntries = List.of();
            top.xuyangjerry.mcmod.lcp.LightChatPatch.LOGGER.warn(
                    "[LCP] ForwardTargetScreen init: minecraft.level is null, cannot get player list");
        }

        // 清理旧条目并重建
        for (var entry : this.entries) {
            this.removeWidget(entry);
        }
        this.entries = new ArrayList<>();

        this.listHeight = this.height - LIST_TOP - LIST_BOTTOM_OFFSET;
        // 转发修复：构建目标列表 —— 优先服务端同步条目（含自己），fallback 本地可见玩家（含自己）
        this.finalTargetNames = new ArrayList<>();
        java.util.Map<String, UUID> nameToUuid = new java.util.HashMap<>();
        if (this.serverSyncedEntries != null && !this.serverSyncedEntries.isEmpty()) {
            for (SyncModdedPlayersPayload.ModdedPlayerEntry e : this.serverSyncedEntries) {
                String n = e.name();
                if (n == null || n.isEmpty()) continue;
                this.finalTargetNames.add(n);
                nameToUuid.put(n, e.uuid());
            }
        } else {
            for (var p : this.players) {
                if (p != null && p.getName() != null) {
                    String name = p.getName().getString();
                    // 转发修复：不再排除自己 —— 允许向自己转发
                    this.finalTargetNames.add(name);
                    nameToUuid.put(name, p.getUUID());
                }
            }
        }
        // 总条目数 = 全体成员(1) + 转发目标数
        this.totalEntryCount = 1 + this.finalTargetNames.size();
        this.contentHeight = this.totalEntryCount * ENTRY_HEIGHT;
        this.scrollOffset = 0;

        int listX = this.width / 2 - BUTTON_WIDTH / 2 - 24;
        int listW = BUTTON_WIDTH + 24;

        // 条目0 → "全体成员"虚拟条目
        AbstractWidget allEntry = new AllMembersEntry(0, listX, 0, listW, ENTRY_HEIGHT);
        this.entries.add(allEntry);
        this.addWidget(allEntry);

        // 条目1..N → 转发目标列表
        int idx = 1;
        for (String targetName : this.finalTargetNames) {
            Player targetPlayer = findPlayerByName(targetName);
            UUID targetUuid = nameToUuid.get(targetName);
            PlayerEntry entry = (targetPlayer != null)
                    ? new PlayerEntry(targetPlayer, targetUuid, idx, listX, 0, listW, ENTRY_HEIGHT)
                    : new PlayerEntry(targetName, targetUuid, idx, listX, 0, listW, ENTRY_HEIGHT);
            this.entries.add(entry);
            this.addWidget(entry);
            idx++;
        }

        this.confirmButton = Button.builder(
                        Component.translatable("light_chat_patch.forward.confirm"),
                        btn -> handleConfirm())
                .bounds(this.width / 2 - BUTTON_WIDTH - 2, this.height - 30, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(this.confirmButton);

        this.addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, btn -> this.minecraft.gui.setScreen(parent))
                .bounds(this.width / 2 + 2, this.height - 30, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.centeredText(this.font, this.title, this.width / 2, 40, 0xFFFFFFFF);

        if (this.finalTargetNames.isEmpty()) {
            Component empty = Component.translatable("light_chat_patch.forward.no_players");
            g.centeredText(this.font, empty, this.width / 2, LIST_TOP + listHeight / 2, 0xFFAAAAAA);
            return;
        }

        int listX = this.width / 2 - BUTTON_WIDTH / 2 - 24;
        int listW = BUTTON_WIDTH + 24;

        g.enableScissor(listX, LIST_TOP, listX + listW, LIST_TOP + this.listHeight);

        for (int i = 0; i < this.entries.size(); i++) {
            AbstractWidget entry = this.entries.get(i);
            int y = LIST_TOP + i * ENTRY_HEIGHT - this.scrollOffset;
            entry.setY(y);
            boolean inView = y + ENTRY_HEIGHT > LIST_TOP && y < LIST_TOP + this.listHeight;
            if (inView) {
                entry.extractRenderState(g, mouseX, mouseY, partialTick);
            }
        }

        g.disableScissor();

        if (this.contentHeight > this.listHeight) {
            int scrollbarX = listX + listW - 6;
            int trackHeight = this.listHeight;
            int thumbHeight = Math.max(20, trackHeight * this.listHeight / this.contentHeight);
            int maxScroll = Math.max(1, this.contentHeight - this.listHeight);
            int thumbY = LIST_TOP + this.scrollOffset * (trackHeight - thumbHeight) / maxScroll;
            g.fill(scrollbarX, LIST_TOP, scrollbarX + 4, LIST_TOP + trackHeight, 0x80000000);
            g.fill(scrollbarX, thumbY, scrollbarX + 4, thumbY + thumbHeight, 0xFFAAAAAA);
        }

        this.confirmButton.setMessage(Component.translatable("light_chat_patch.forward.confirm_count", selectedTargets.size()));
        this.confirmButton.active = !selectedTargets.isEmpty();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int listX = this.width / 2 - BUTTON_WIDTH / 2 - 24;
        int listW = BUTTON_WIDTH + 24;
        if (mouseX >= listX && mouseX < listX + listW && mouseY >= LIST_TOP && mouseY < LIST_TOP + this.listHeight) {
            this.scrollOffset -= (int) (scrollY * ENTRY_HEIGHT);
            this.scrollOffset = Math.max(0, Math.min(this.scrollOffset, Math.max(0, this.contentHeight - this.listHeight)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void toggleTarget(String name) {
        if (selectedTargets.contains(name)) {
            selectedTargets.remove(name);
        } else {
            selectedTargets.add(name);
        }
    }

    private void handleConfirm() {
        if (this.minecraft == null || this.minecraft.player == null) return;

        String forwarder = this.minecraft.player.getName().getString();
        List<ForwardMessagePayload.ForwardedMessage> forwarded = new ArrayList<>();
        for (String[] msg : messages) {
            forwarded.add(new ForwardMessagePayload.ForwardedMessage(msg[0], msg[1], 0));
        }

        // 问题6：区分"全体成员"和具体玩家
        for (String targetName : selectedTargets) {
            if (TARGET_ALL_MEMBERS.equals(targetName)) {
                // 全体成员：使用特殊目标名，由服务端 NoneBotServerManager.onForwardMessage 处理
                // → 发给所有在线玩家 + 同步到QQ群
                ForwardMessagePayload payload = new ForwardMessagePayload(forwarder, TARGET_ALL_MEMBERS, forwarded);
                ClientPlayNetworking.send(payload);
            } else {
                // 普通单个玩家
                ForwardMessagePayload payload = new ForwardMessagePayload(forwarder, targetName, forwarded);
                ClientPlayNetworking.send(payload);
            }
        }

        // 转发完毕：退出聊天模式（关闭整个聊天屏幕）
        this.minecraft.gui.setScreen(null);
    }

    // 问题6：虚拟条目 —— "全体成员"
    private class AllMembersEntry extends AbstractWidget {
        private final int index;

        AllMembersEntry(int index, int x, int y, int width, int height) {
            super(x, y, width, height,
                    Component.translatable("light_chat_patch.forward.all_members")
                            .append(" (")
                            .append(Component.literal("@all"))
                            .append(")"));
            this.index = index;
            this.visible = true;
            this.active = true;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            boolean isSelected = selectedTargets.contains(TARGET_ALL_MEMBERS);
            boolean hovered = mouseX >= this.getX() && mouseX < this.getX() + this.width
                    && mouseY >= this.getY() && mouseY < this.getY() + this.height;
            boolean keyboardSelected = selectedIndex == index;

            int bgColor;
            if (isSelected) {
                bgColor = (keyboardSelected || hovered) ? 0x80CC4400 : 0x50CC4400;
            } else if (keyboardSelected || hovered) {
                bgColor = 0xB0333333;
            } else {
                bgColor = 0x80000000;
            }
            g.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bgColor);

            // 全体成员图标（简单的群图标：几个方块+@符号）
            int iconX = this.getX() + 4;
            int iconY = this.getY() + 4;
            g.fill(iconX, iconY, iconX + 16, iconY + 16, 0xFFE08040);
            g.text(font, "@", iconX + 3, iconY + 4, 0xFFFFFFFF, false);

            g.text(font, this.getMessage(), this.getX() + 24, this.getY() + (this.height - 8) / 2, 0xFFFFCC88);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean handled) {
            if (this.getY() + this.height <= LIST_TOP || this.getY() >= LIST_TOP + ForwardTargetScreen.this.listHeight) {
                return false;
            }
            return super.mouseClicked(event, handled);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean handled) {
            toggleTarget(TARGET_ALL_MEMBERS);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            this.defaultButtonNarrationText(narrationElementOutput);
        }
    }

    /** 问题4②：按名字在 level.players() 中查找对应 Player 实例，找不到时返回 null。 */
    private Player findPlayerByName(String name) {
        if (name == null || name.isEmpty()) return null;
        if (this.minecraft == null || this.minecraft.level == null) return null;
        for (Player p : this.minecraft.level.players()) {
            if (p != null && name.equals(p.getName().getString())) {
                return p;
            }
        }
        return null;
    }

    private class PlayerEntry extends AbstractWidget {
        private final Player player;
        /** 转发修复：当找不到 player 实体（不在客户端可见范围）时，改用纯名字 fallbackName。 */
        private final String fallbackName;
        /** 转发修复：玩家的 UUID，用于通过 PlayerInfo 获取皮肤头像（fallback 模式下）。 */
        private final UUID playerUuid;
        private final int index;

        PlayerEntry(Player player, UUID uuid, int index, int x, int y, int width, int height) {
            super(x, y, width, height, player.getName());
            this.player = player;
            this.fallbackName = null;
            this.playerUuid = uuid;
            this.index = index;
            this.visible = true;
            this.active = true;
        }

        /** 转发修复：服务端同步了名字但客户端可见范围没这个玩家时 → 用纯名显示，并通过 PlayerInfo 获取皮肤。 */
        PlayerEntry(String fallbackName, UUID uuid, int index, int x, int y, int width, int height) {
            super(x, y, width, height, Component.literal(fallbackName));
            this.player = null;
            this.fallbackName = fallbackName;
            this.playerUuid = uuid;
            this.index = index;
            this.visible = true;
            this.active = true;
        }

        /** 获取此条目表示的玩家名（优先 player，其次 fallbackName）。 */
        private String getPlayerName() {
            if (player != null) return player.getName().getString();
            return fallbackName != null ? fallbackName : "?";
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            String name = getPlayerName();
            boolean isSelected = selectedTargets.contains(name);
            boolean hovered = mouseX >= this.getX() && mouseX < this.getX() + this.width
                    && mouseY >= this.getY() && mouseY < this.getY() + this.height;
            boolean keyboardSelected = selectedIndex == index;

            int bgColor;
            if (isSelected) {
                bgColor = (keyboardSelected || hovered) ? 0x800088CC : 0x500088CC;
            } else if (keyboardSelected || hovered) {
                bgColor = 0xB0333333;
            } else {
                bgColor = 0x80000000;
            }
            g.fill(this.getX(), this.getY(), this.getX() + this.width, this.getY() + this.height, bgColor);

            // 转发修复：优先从 Player 实体获取皮肤；fallback 时通过 PlayerInfo（ClientPacketListener）获取
            PlayerSkin skin = null;
            if (this.player instanceof AbstractClientPlayer clientPlayer) {
                skin = clientPlayer.getSkin();
            } else if (this.playerUuid != null && minecraft != null && minecraft.getConnection() != null) {
                var info = minecraft.getConnection().getPlayerInfo(this.playerUuid);
                if (info != null) {
                    skin = info.getSkin();
                }
            }
            if (skin != null) {
                PlayerFaceExtractor.extractRenderState(g, skin, this.getX() + 4, this.getY() + 4, FACE_SIZE);
            }

            g.text(font, name, this.getX() + 24, this.getY() + (this.height - 8) / 2, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean handled) {
            if (this.getY() + this.height <= LIST_TOP || this.getY() >= LIST_TOP + ForwardTargetScreen.this.listHeight) {
                return false;
            }
            return super.mouseClicked(event, handled);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean handled) {
            toggleTarget(getPlayerName());
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            this.defaultButtonNarrationText(narrationElementOutput);
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == 265 || keyCode == 263) {
            navigate(-1);
            return true;
        }
        if (keyCode == 264 || keyCode == 262) {
            navigate(1);
            return true;
        }
        if (keyCode == 32 || keyCode == 257) {
            // 问题6：索引0=全体成员，1..N=玩家；范围用totalEntryCount
            if (selectedIndex >= 0 && selectedIndex < totalEntryCount) {
                if (selectedIndex == 0) {
                    toggleTarget(TARGET_ALL_MEMBERS);
                } else {
                    int playerIdx = selectedIndex - 1;
                    if (playerIdx >= 0 && playerIdx < finalTargetNames.size()) {
                        toggleTarget(finalTargetNames.get(playerIdx));
                    }
                }
                return true;
            }
        }
        return super.keyPressed(event);
    }

    private void navigate(int direction) {
        // 问题6：条目数 = 1(全体) + players.size()
        int count = totalEntryCount;
        if (count <= 0) return;
        selectedIndex += direction;
        selectedIndex = Math.max(0, Math.min(selectedIndex, count - 1));

        int visibleTop = scrollOffset / ENTRY_HEIGHT;
        int visibleBottom = visibleTop + listHeight / ENTRY_HEIGHT;

        if (selectedIndex < visibleTop) {
            scrollOffset = selectedIndex * ENTRY_HEIGHT;
        } else if (selectedIndex >= visibleBottom) {
            scrollOffset = (selectedIndex - listHeight / ENTRY_HEIGHT + 1) * ENTRY_HEIGHT;
        }
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, contentHeight - listHeight)));
    }
}
