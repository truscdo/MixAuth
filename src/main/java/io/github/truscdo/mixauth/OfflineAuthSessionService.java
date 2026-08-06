package io.github.truscdo.mixauth;

import io.github.truscdo.mixauth.localization.AuthTranslations;
import net.minecraft.core.NonNullList;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.IEventBus;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OfflineAuthSessionService {
    private static final long ACTION_DENY_MESSAGE_INTERVAL_MILLIS = 1_000L;
    private static final long INVENTORY_SPOOF_INTERVAL_MILLIS = 1_000L;
    private static final int AUTH_BLINDNESS_DURATION_TICKS = 15 * 20;
    private static final double LOCKED_POSITION_TOLERANCE_SQUARED = 0.0001D;
    private static final Set<String> ALLOWED_PENDING_COMMANDS = Set.of("register", "login");
    private static final Map<UUID, PendingOfflineAuth> PENDING_OFFLINE_AUTHS = new ConcurrentHashMap<>();

    private OfflineAuthSessionService() {
    }

    /**
     * 批量注册本模块的所有事件监听器。
     */
    public static void registerEventHandlers(IEventBus bus) {
        bus.addListener(OfflineAuthSessionService::onPlayerLoggedOut);
        bus.addListener(OfflineAuthSessionService::onServerTick);
        bus.addListener(OfflineAuthSessionService::onCommand);
        bus.addListener(OfflineAuthSessionService::onServerChat);
        bus.addListener(OfflineAuthSessionService::onAttackEntity);
        bus.addListener(OfflineAuthSessionService::onEntityInteractSpecific);
        bus.addListener(OfflineAuthSessionService::onEntityInteract);
        bus.addListener(OfflineAuthSessionService::onRightClickBlock);
        bus.addListener(OfflineAuthSessionService::onRightClickItem);
        bus.addListener(OfflineAuthSessionService::onLeftClickBlock);
        bus.addListener(OfflineAuthSessionService::onUseItemOnBlock);
        bus.addListener(OfflineAuthSessionService::onItemToss);
        bus.addListener(OfflineAuthSessionService::onPlayerContainerOpen);
        bus.addListener(OfflineAuthSessionService::onItemStackedOnOther);
    }

    static long promptIntervalMillis() {
        return AuthServerConfig.promptIntervalMillis();
    }

    private static long loginTimeoutMillis() {
        return AuthServerConfig.loginTimeoutMillis();
    }

    static void beginPendingAuth(ServerPlayer player, OfflineAuthStage stage) {
        PendingOfflineAuth pendingOfflineAuth = new PendingOfflineAuth(player, stage);
        PENDING_OFFLINE_AUTHS.put(player.getGameProfile().getId(), pendingOfflineAuth);
        applyPendingRestrictions(player, pendingOfflineAuth);
        spoofInventoryView(player, pendingOfflineAuth);
        sendAuthPrompt(player, stage);
    }

    static PendingOfflineAuth getPendingAuth(ServerPlayer player) {
        return PENDING_OFFLINE_AUTHS.get(player.getGameProfile().getId());
    }

    static void completeAuthentication(ServerPlayer player, String messageKey, Object... args) {
        clearPendingAuth(player);
        restoreInventoryView(player);
        player.sendSystemMessage(AuthTranslations.componentForPlayer(player, messageKey, args));
    }

    static PendingOfflineAuth clearPendingAuth(ServerPlayer player) {
        PendingOfflineAuth pendingOfflineAuth = PENDING_OFFLINE_AUTHS.remove(player.getGameProfile().getId());
        if (pendingOfflineAuth == null) {
            LogUtil.getLogger().warn("Attempted to complete authentication for player {} who has no pending auth",
                    player.getGameProfile().getName());
            return null;
        }

        player.removeEffect(MobEffects.BLINDNESS);
        if (pendingOfflineAuth.originalBlindnessEffect != null) {
            player.addEffect(new MobEffectInstance(pendingOfflineAuth.originalBlindnessEffect));
        }

        if (player.gameMode.getGameModeForPlayer() != pendingOfflineAuth.originalGameType) {
            player.setGameMode(pendingOfflineAuth.originalGameType);
        }
        return pendingOfflineAuth;
    }

    static void sendAuthPrompt(ServerPlayer player, OfflineAuthStage stage) {
        player.sendSystemMessage(AuthTranslations.componentForPlayer(player, switch (stage) {
            case REGISTER -> "auth.prompt.register";
            case LOGIN -> "auth.prompt.login";
            case RECONNECT_LOGIN -> "auth.prompt.reconnect_login";
        }));
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            clearPendingAuth(player);
        }
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING_OFFLINE_AUTHS.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        Set<UUID> onlinePlayers = new HashSet<>();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            UUID playerUuid = player.getGameProfile().getId();
            onlinePlayers.add(playerUuid);
            PendingOfflineAuth pendingOfflineAuth = PENDING_OFFLINE_AUTHS.get(playerUuid);
            if (pendingOfflineAuth == null) {
                continue;
            }

            if (pendingOfflineAuth.stage == OfflineAuthStage.LOGIN && now >= pendingOfflineAuth.loginDeadlineAtMillis) {
                clearPendingAuth(player);
                player.connection
                        .disconnect(AuthTranslations.componentForPlayer(player, "auth.error.offline_login_timeout"));
                continue;
            }

            applyPendingRestrictions(player, pendingOfflineAuth);
            if (now >= pendingOfflineAuth.nextInventorySpoofAtMillis) {
                spoofInventoryView(player, pendingOfflineAuth);
            }
            if (now >= pendingOfflineAuth.nextPromptAtMillis) {
                sendAuthPrompt(player, pendingOfflineAuth.stage);
                pendingOfflineAuth.nextPromptAtMillis = now + promptIntervalMillis();
            }
        }

        PENDING_OFFLINE_AUTHS.keySet().removeIf(uuid -> !onlinePlayers.contains(uuid));
    }

    public static void onCommand(CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }

        PendingOfflineAuth pendingOfflineAuth = guardPendingAuth(player);
        if (pendingOfflineAuth == null) {
            return;
        }

        String commandName = extractCommandName(event.getParseResults().getReader().getString());
        if (ALLOWED_PENDING_COMMANDS.contains(commandName)) {
            return;
        }

        event.setCanceled(true);
        denyPendingAction(player, pendingOfflineAuth, "auth.error.only_register_or_login_commands");
    }

    public static void onServerChat(ServerChatEvent event) {
        PendingOfflineAuth pendingOfflineAuth = guardPendingAuth(event.getPlayer());
        if (pendingOfflineAuth == null) {
            return;
        }

        event.setCanceled(true);
        denyPendingAction(event.getPlayer(), pendingOfflineAuth, "auth.error.cannot_chat_before_login");
    }

    public static void onAttackEntity(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        PendingOfflineAuth pendingOfflineAuth = guardPendingAuth(player);
        if (pendingOfflineAuth == null)
            return;
        event.setCanceled(true);
        denyPendingAction(player, pendingOfflineAuth, "auth.error.cannot_attack_or_interact_before_login");
    }

    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        PendingOfflineAuth pendingOfflineAuth = guardPendingAuth(player);
        if (pendingOfflineAuth == null)
            return;
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
        denyPendingAction(player, pendingOfflineAuth, "auth.error.cannot_attack_or_interact_before_login");
    }

    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        PendingOfflineAuth pendingOfflineAuth = guardPendingAuth(player);
        if (pendingOfflineAuth == null)
            return;
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
        denyPendingAction(player, pendingOfflineAuth, "auth.error.cannot_attack_or_interact_before_login");
    }

    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        PendingOfflineAuth pendingOfflineAuth = guardPendingAuth(player);
        if (pendingOfflineAuth == null)
            return;
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
        denyPendingAction(player, pendingOfflineAuth, "auth.error.cannot_interact_blocks_items_before_login");
    }

    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        PendingOfflineAuth pendingOfflineAuth = guardPendingAuth(player);
        if (pendingOfflineAuth == null)
            return;
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
        denyPendingAction(player, pendingOfflineAuth, "auth.error.cannot_interact_blocks_items_before_login");
    }

    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        PendingOfflineAuth pendingOfflineAuth = guardPendingAuth(player);
        if (pendingOfflineAuth == null)
            return;
        event.setCanceled(true);
        denyPendingAction(player, pendingOfflineAuth, "auth.error.cannot_interact_blocks_items_before_login");
    }

    public static void onUseItemOnBlock(UseItemOnBlockEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player))
            return;
        PendingOfflineAuth pendingOfflineAuth = guardPendingAuth(player);
        if (pendingOfflineAuth == null)
            return;
        event.cancelWithResult(ItemInteractionResult.FAIL);
        denyPendingAction(player, pendingOfflineAuth, "auth.error.cannot_interact_blocks_items_before_login");
    }

    public static void onItemToss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player))
            return;
        PendingOfflineAuth pendingOfflineAuth = guardPendingAuth(player);
        if (pendingOfflineAuth == null)
            return;
        event.setCanceled(true);
        spoofInventoryView(player, pendingOfflineAuth);
        denyPendingAction(player, pendingOfflineAuth, "auth.error.cannot_drop_before_login");
    }

    public static void onPlayerContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        PendingOfflineAuth pendingOfflineAuth = guardPendingAuth(player);
        if (pendingOfflineAuth == null)
            return;

        if (event.getContainer() == player.inventoryMenu) {
            spoofInventoryView(player, pendingOfflineAuth);
            return;
        }

        player.closeContainer();
        spoofInventoryView(player, pendingOfflineAuth);
        denyPendingAction(player, pendingOfflineAuth, "auth.error.cannot_open_containers_before_login");
    }

    public static void onItemStackedOnOther(ItemStackedOnOtherEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player))
            return;
        PendingOfflineAuth pendingOfflineAuth = guardPendingAuth(player);
        if (pendingOfflineAuth == null)
            return;
        event.setCanceled(true);
        spoofInventoryView(player, pendingOfflineAuth);
        denyPendingAction(player, pendingOfflineAuth, "auth.error.cannot_use_inventory_before_login");
    }

    private static void applyPendingRestrictions(ServerPlayer player, PendingOfflineAuth pendingOfflineAuth) {
        if (player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
            player.setGameMode(GameType.SPECTATOR);
        }

        ensureAuthBlindness(player);
        if (player.distanceToSqr(pendingOfflineAuth.lockedX, pendingOfflineAuth.lockedY,
                pendingOfflineAuth.lockedZ) > LOCKED_POSITION_TOLERANCE_SQUARED) {
            player.teleportTo(player.serverLevel(), pendingOfflineAuth.lockedX, pendingOfflineAuth.lockedY,
                    pendingOfflineAuth.lockedZ, player.getYRot(), player.getXRot());
        }

        player.setDeltaMovement(Vec3.ZERO);
    }

    private static void spoofInventoryView(ServerPlayer player, PendingOfflineAuth pendingOfflineAuth) {
        AbstractContainerMenu inventoryMenu = player.inventoryMenu;
        NonNullList<ItemStack> emptyItems = NonNullList.withSize(inventoryMenu.getItems().size(), ItemStack.EMPTY);
        player.connection.send(new ClientboundContainerSetContentPacket(
                inventoryMenu.containerId,
                inventoryMenu.getStateId(),
                emptyItems,
                ItemStack.EMPTY));
        pendingOfflineAuth.nextInventorySpoofAtMillis = System.currentTimeMillis() + INVENTORY_SPOOF_INTERVAL_MILLIS;
    }

    private static void restoreInventoryView(ServerPlayer player) {
        player.inventoryMenu.sendAllDataToRemote();
    }

    private static void ensureAuthBlindness(ServerPlayer player) {
        MobEffectInstance blindness = player.getEffect(MobEffects.BLINDNESS);
        if (blindness != null && blindness.getDuration() > 40) {
            return;
        }

        player.addEffect(
                new MobEffectInstance(MobEffects.BLINDNESS, AUTH_BLINDNESS_DURATION_TICKS, 0, false, false, false));
    }

    private static void denyPendingAction(ServerPlayer player, PendingOfflineAuth pendingOfflineAuth,
            String translationKey, Object... args) {
        long now = System.currentTimeMillis();
        if (now < pendingOfflineAuth.nextActionDeniedMessageAtMillis) {
            return;
        }

        player.sendSystemMessage(AuthTranslations.componentForPlayer(player, translationKey, args));
        sendAuthPrompt(player, pendingOfflineAuth.stage);
        pendingOfflineAuth.nextActionDeniedMessageAtMillis = now + ACTION_DENY_MESSAGE_INTERVAL_MILLIS;
    }

    /**
     * 获取玩家的待处理认证状态。如果没有待处理认证，返回 null。
     */
    private static PendingOfflineAuth guardPendingAuth(ServerPlayer player) {
        PendingOfflineAuth pending = PENDING_OFFLINE_AUTHS.get(player.getGameProfile().getId());
        return pending;
    }

    private static String extractCommandName(String rawCommand) {
        String normalized = rawCommand == null ? "" : rawCommand.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        int whitespaceIndex = normalized.indexOf(' ');
        if (whitespaceIndex >= 0) {
            normalized = normalized.substring(0, whitespaceIndex);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    enum OfflineAuthStage {
        REGISTER,
        LOGIN,
        RECONNECT_LOGIN;
    }

    static final class PendingOfflineAuth {
        final GameType originalGameType;
        final MobEffectInstance originalBlindnessEffect;
        final double lockedX;
        final double lockedY;
        final double lockedZ;
        OfflineAuthStage stage;
        int failedLoginAttempts;
        long loginDeadlineAtMillis;
        long nextPromptAtMillis;
        long nextActionDeniedMessageAtMillis;
        long nextInventorySpoofAtMillis;

        PendingOfflineAuth(ServerPlayer player, OfflineAuthStage stage) {
            long now = System.currentTimeMillis();
            this.originalGameType = player.gameMode.getGameModeForPlayer();
            MobEffectInstance blindness = player.getEffect(MobEffects.BLINDNESS);
            this.originalBlindnessEffect = blindness == null ? null : new MobEffectInstance(blindness);
            this.lockedX = player.getX();
            this.lockedY = player.getY();
            this.lockedZ = player.getZ();
            this.stage = stage;
            this.loginDeadlineAtMillis = stage == OfflineAuthStage.LOGIN ? now + loginTimeoutMillis() : 0L;
            this.nextPromptAtMillis = now + promptIntervalMillis();
            this.nextActionDeniedMessageAtMillis = 0L;
            this.nextInventorySpoofAtMillis = 0L;
        }
    }
}