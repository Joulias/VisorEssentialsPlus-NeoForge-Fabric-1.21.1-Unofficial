package org.vmstudio.essentials.core.client.gui.overlays;

import org.vmstudio.essentials.core.client.config.WristUiConfig;
import org.vmstudio.essentials.core.client.tasks.BowItemTask;
import org.vmstudio.essentials.core.common.VisorEssentials;
import org.vmstudio.visor.api.VisorAPI;
import org.vmstudio.visor.api.client.events.gui.CursorFocusChangedVREvent;
import org.vmstudio.visor.api.client.gui.overlays.VROverlay;
import org.vmstudio.visor.api.client.gui.overlays.VROverlayHelper;
import org.vmstudio.visor.api.client.gui.overlays.framework.screen.VROverlayScreenInScreen;
import org.vmstudio.visor.api.client.gui.overlays.options.OverlayOptionGroup;
import org.vmstudio.visor.api.client.gui.overlays.options.types.OverlayOptionsPose;
import org.vmstudio.visor.api.client.player.pose.PlayerPoseType;
import org.vmstudio.visor.api.client.player.pose.PoseAnchor;
import org.vmstudio.visor.api.common.HandType;
import org.vmstudio.visor.api.common.addon.VisorAddon;
import org.vmstudio.visor.api.common.eventbus.listener.VREventHandler;
import org.vmstudio.visor.api.common.eventbus.listener.VREventListener;
import org.vmstudio.visor.api.common.player.VRPose;
import org.vmstudio.essentials.core.client.gui.screens.VRInvScreen;
import org.vmstudio.essentials.core.client.gui.screens.VRCreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public class VROverlayInventory extends VROverlayScreenInScreen<Screen> implements VREventListener {
    public static final String ID = "inventory";

    protected final OverlayOptionsPose optionsPose;
    private @Nullable String contextActionOwner;
    private @Nullable Component contextActionLabel;
    private @Nullable Runnable contextAction;
    private final Map<String, UtilityAction> utilityActions = new LinkedHashMap<>();
    private @Nullable VRInvScreen inventoryScreen;
    private @Nullable VRCreativeModeInventoryScreen creativeScreen;

    public VROverlayInventory(@NotNull VisorAddon owner,
                              @NotNull String id) {
        super(owner, id, null);
        optionsPose = getOption(OverlayOptionsPose.ID, OverlayOptionsPose.class);
        setEnabled(true);
        VisorAPI.eventBus().registerListener(owner,this);
    }

    @VREventHandler
    public void disableWorldHands(CursorFocusChangedVREvent event){
        var usedHand = getUsedHand();
        if(usedHand == null
                || event.getHand() != usedHand
                || event.getNewOverlay() == null
                || event.getNewOverlay() instanceof VROverlayDraggedItem){
            return;
        }
        //don't focus hand that is used by inventory
        if(isVisible()){
            event.setCanceled(true);
        }
    }

    @Override
    protected void onPreTick() {
        if (creativeScreen != null && !canKeepCreativeWristOpen()) {
            releaseEmbeddedCreative(true);
        }
        if(isCanBeVisible()) {
            VROverlayHelper.applyPose(
                    this,
                    optionsPose.getPositionAnchor(),
                    optionsPose.getRotationAnchor(),
                    optionsPose.getScale(),
                    optionsPose.isAimedRotation(),
                    optionsPose.getPositionOffset(),
                    optionsPose.getRotationOffset()
            );
        }
    }

    @Override
    protected void onTick() {
        if(!isVisible()) return;

        VRCreativeModeInventoryScreen activeCreative = creativeScreen;
        if (activeCreative != null) {
            activeCreative.tick();
            cursorBoundsX = activeCreative.getInteractionX();
            cursorBoundsY = activeCreative.getInteractionY();
            cursorBoundsWidth = activeCreative.getInteractionWidth();
            cursorBoundsHeight = activeCreative.getInteractionHeight();
            return;
        }

        var overlayContainer =
                VisorAPI.client().getGuiManager()
                        .getOverlayManager().getOverlay(
                                VROverlayContainer.ID,
                                VROverlayContainer.class
                        );
        AbstractContainerMenu menu =
                overlayContainer.isEnabled() ?
                        overlayContainer.getScreen().getMenu() : minecraft.player.inventoryMenu;

        VRInvScreen activeInventory = inventoryScreen;
        if (activeInventory == null) {
            initializeInventoryScreen(menu);
            activeInventory = inventoryScreen;
        } else {
            boolean craftingAllowed = !overlayContainer.isEnabled();
            if (craftingAllowed != activeInventory.isFullInventory()
                    || menu != activeInventory.getMenu()) {
                initializeInventoryScreen(menu);
                activeInventory = inventoryScreen;
            }
        }

        activeInventory.tick();

        cursorBoundsX = activeInventory.visorEssentials$getEdgeX();
        cursorBoundsY = activeInventory.visorEssentials$getEdgeY();
        cursorBoundsWidth = activeInventory.visorEssentials$getEdgeWidth();
        cursorBoundsHeight = activeInventory.visorEssentials$getEdgeHeight();

    }

    private void initializeInventoryScreen(AbstractContainerMenu menu) {
        VRInvScreen newScreen =
                new VRInvScreen(menu, minecraft.player.getInventory());
        inventoryScreen = newScreen;
        creativeScreen = null;
        screen = newScreen;
        newScreen.setContextAction(contextActionLabel, contextAction);
        newScreen.setCreativeModeAction(this::openCreativeWrist);
        utilityActions.forEach((ownerId, action) -> newScreen.setUtilityAction(
                ownerId,
                action.icon(),
                action.tooltip(),
                action.visible(),
                action.toggled(),
                screenUtilityAction(ownerId, action)
        ));
        newScreen.init(minecraft, width, height);
        newScreen.removeInjectedWidgets();
    }
    private void openCreativeWrist() {
        if (!canOpenCreativeWrist()) {
            return;
        }

        LocalPlayer player = minecraft.player;
        VRCreativeModeInventoryScreen newScreen =
                new VRCreativeModeInventoryScreen(
                        player,
                        player.connection.enabledFeatures(),
                        minecraft.options.operatorItemsTab().get(),
                        this::popOutCreativeMenu,
                        this::backToInventory
                );
        inventoryScreen = null;
        creativeScreen = newScreen;
        screen = newScreen;
        newScreen.init(minecraft, width, height);
        newScreen.removeInjectedWidgets();
    }

    private boolean canOpenCreativeWrist() {
        LocalPlayer player = minecraft.player;
        if (player == null
                || minecraft.gameMode == null
                || !minecraft.gameMode.hasInfiniteItems()
                || minecraft.screen != null
                || inventoryScreen == null
                || !inventoryScreen.isFullInventory()
                || screen != inventoryScreen
                || player.containerMenu != player.inventoryMenu) {
            return false;
        }

        VROverlayContainer containerOverlay =
                VisorAPI.client().getGuiManager()
                        .getOverlayManager().getOverlay(
                                VROverlayContainer.ID,
                                VROverlayContainer.class
                        );
        return !containerOverlay.isEnabled();
    }

    private boolean canKeepCreativeWristOpen() {
        LocalPlayer player = minecraft.player;
        VRCreativeModeInventoryScreen activeCreative = creativeScreen;
        return isCanBeVisible()
                && minecraft.gameMode != null
                && minecraft.gameMode.hasInfiniteItems()
                && screen == activeCreative
                && player.containerMenu == activeCreative.getMenu();
    }

    private void backToInventory() {
        releaseEmbeddedCreative(true);
        if (VisorEssentials.customInventory
                && WristUiConfig.getInstance().armInventoryEnabled()
                && minecraft.screen == null
                && minecraft.level != null
                && minecraft.player != null) {
            initializeInventoryScreen(minecraft.player.inventoryMenu);
        }
    }

    private void popOutCreativeMenu() {
        VRCreativeModeInventoryScreen activeCreative = creativeScreen;
        LocalPlayer player = minecraft.player;
        if (activeCreative == null || player == null) {
            return;
        }

        releaseEmbeddedCreative(false);
        try {
            CreativeModeInventoryScreen popout =
                    new CreativeModeInventoryScreen(
                            player,
                            player.connection.enabledFeatures(),
                            minecraft.options.operatorItemsTab().get()
                    );
            minecraft.setScreen(popout);
        } catch (RuntimeException exception) {
            if (player.containerMenu == activeCreative.getMenu()) {
                player.containerMenu = player.inventoryMenu;
            }
            throw exception;
        }
    }

    private void releaseEmbeddedCreative(boolean restoreInventoryMenu) {
        VRCreativeModeInventoryScreen oldScreen = creativeScreen;
        if (oldScreen == null) {
            return;
        }

        creativeScreen = null;
        if (screen == oldScreen) {
            screen = null;
        }
        oldScreen.removed();

        LocalPlayer player = minecraft.player;
        if (restoreInventoryMenu
                && player != null
                && player.containerMenu == oldScreen.getMenu()) {
            // Do not call LocalPlayer.closeContainer() here: it also invokes
            // Minecraft.setScreen(null), which could close an unrelated pause,
            // settings, or mod screen that caused the wrist to hide.
            player.containerMenu = player.inventoryMenu;
        }
    }

    /**
     * Allows an optional addon to place one temporary, owner-scoped action on
     * the wrist inventory without taking ownership of the inventory screen.
     */
    public boolean setContextAction(@NotNull String ownerId,
                                    @NotNull Component label,
                                    @NotNull Runnable action) {
        if (!VisorEssentials.customInventory) {
            return false;
        }
        if (contextActionOwner != null && !contextActionOwner.equals(ownerId)) {
            return false;
        }
        contextActionOwner = ownerId;
        contextActionLabel = label;
        contextAction = action;
        if (inventoryScreen != null) {
            inventoryScreen.setContextAction(label, action);
        }
        return true;
    }

    public void clearContextAction(@NotNull String ownerId) {
        if (!ownerId.equals(contextActionOwner)) {
            return;
        }
        contextActionOwner = null;
        contextActionLabel = null;
        contextAction = null;
        if (inventoryScreen != null) {
            inventoryScreen.setContextAction(null, null);
        }
    }

    /**
     * Adds or replaces a compact addon-owned button inside the full wrist
     * inventory. Multiple addons may contribute one action each without
     * taking ownership of the inventory or of the temporary context action.
     */
    public boolean setUtilityAction(@NotNull String ownerId,
                                    @NotNull ItemStack icon,
                                    @NotNull Component tooltip,
                                    @NotNull BooleanSupplier visible,
                                    @NotNull Runnable action) {
        return setUtilityAction(ownerId, icon, tooltip, visible, () -> false, action);
    }

    public boolean setUtilityAction(@NotNull String ownerId,
                                    @NotNull ItemStack icon,
                                    @NotNull Component tooltip,
                                    @NotNull BooleanSupplier visible,
                                    @NotNull BooleanSupplier toggled,
                                    @NotNull Runnable action) {
        return putUtilityAction(
                ownerId,
                icon,
                tooltip,
                visible,
                toggled,
                action,
                null,
                null
        );
    }

    /**
     * Adds an addon action to an exclusive group. When this action is about to
     * be enabled, every active action in the same group is closed first. The
     * original overloads remain independent and retain their previous
     * behavior.
     */
    public boolean setExclusiveUtilityAction(@NotNull String ownerId,
                                             @NotNull String groupId,
                                             @NotNull ItemStack icon,
                                             @NotNull Component tooltip,
                                             @NotNull BooleanSupplier visible,
                                             @NotNull BooleanSupplier toggled,
                                             @NotNull Runnable closeAction,
                                             @NotNull Runnable toggleAction) {
        return putUtilityAction(
                ownerId,
                icon,
                tooltip,
                visible,
                toggled,
                toggleAction,
                groupId,
                closeAction
        );
    }

    private boolean putUtilityAction(@NotNull String ownerId,
                                     @NotNull ItemStack icon,
                                     @NotNull Component tooltip,
                                     @NotNull BooleanSupplier visible,
                                     @NotNull BooleanSupplier toggled,
                                     @NotNull Runnable action,
                                     @Nullable String exclusiveGroup,
                                     @Nullable Runnable closeAction) {
        if (!isUtilityActionAvailable()) {
            return false;
        }
        UtilityAction utilityAction = new UtilityAction(
                icon.copy(),
                tooltip,
                visible,
                toggled,
                action,
                exclusiveGroup,
                closeAction
        );
        utilityActions.put(ownerId, utilityAction);
        if (inventoryScreen != null) {
            inventoryScreen.setUtilityAction(
                    ownerId,
                    icon.copy(),
                    tooltip,
                    visible,
                    toggled,
                    screenUtilityAction(ownerId, utilityAction)
            );
        }
        return true;
    }

    private Runnable screenUtilityAction(@NotNull String ownerId,
                                         @NotNull UtilityAction action) {
        return action.exclusiveGroup() == null
                ? action.action()
                : () -> runUtilityAction(ownerId);
    }

    private void runUtilityAction(@NotNull String ownerId) {
        UtilityAction selectedAction = utilityActions.get(ownerId);
        if (selectedAction == null) {
            return;
        }

        String exclusiveGroup = selectedAction.exclusiveGroup();
        if (exclusiveGroup != null && !selectedAction.toggled().getAsBoolean()) {
            utilityActions.entrySet().stream()
                    .filter(entry -> !ownerId.equals(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .filter(action -> exclusiveGroup.equals(action.exclusiveGroup()))
                    .filter(action -> action.toggled().getAsBoolean())
                    .map(UtilityAction::closeAction)
                    .filter(closeAction -> closeAction != null)
                    .toList()
                    .forEach(Runnable::run);
        }

        selectedAction.action().run();
    }

    public boolean isUtilityActionAvailable() {
        return isEnabled()
                && VisorEssentials.customInventory
                && WristUiConfig.getInstance().armInventoryEnabled();
    }

    public void clearUtilityAction(@NotNull String ownerId) {
        utilityActions.remove(ownerId);
        if (inventoryScreen != null) {
            inventoryScreen.clearUtilityAction(ownerId);
        }
    }

    @Override
    protected void onUpdatePose(float partialTicks) {
        VROverlayHelper.applyPose(
                this,
                optionsPose.getPositionAnchor(),
                optionsPose.getRotationAnchor(),
                optionsPose.getScale(),
                optionsPose.isAimedRotation(),
                optionsPose.getPositionOffset(),
                optionsPose.getRotationOffset()
        );
    }

    @Override
    public boolean updateVisibility() {
        if(!isCanBeVisible()){
            return false;
        }

        var cursorHandler = VisorAPI.client().getGuiManager().getCursorHandler();
        var cursorHand = cursorHandler.getCursorHand();
        var focusedOverlay = cursorHandler.getFocusedOverlay();
        boolean focused = focusedOverlay == this
                || isAimedAtOverlay(
                VisorAPI.client().getVRLocalPlayer()
                        .getPoseData(PlayerPoseType.RENDER)
                        .getHand(cursorHand),
                this,
                false,
                0f,
                0f
        ) || isAimedAtOverlay(
                        VisorAPI.client().getVRLocalPlayer()
                                .getPoseData(PlayerPoseType.RENDER)
                                .getHmd(),
                this,
                true,
                0.6f,
                1f
        );
        if(!focused){
            return false;
        }



        return true;
    }

    private boolean isCanBeVisible(){
        if (!VisorEssentials.customInventory) {
            return false;
        }
        if (!WristUiConfig.getInstance().armInventoryEnabled()) {
            return false;
        }
        if (!VisorAPI.client().getVRLocalPlayer()
                .getRawController(HandType.OFFHAND)
                .isTracking()) {
            return false;
        }
        if(minecraft.screen != null){
            return false;
        }
        if (minecraft.isPaused()
                || minecraft.level == null
                || minecraft.player == null
                || minecraft.getEntityRenderDispatcher().camera == null) {
            return false;
        }
        if(BowItemTask.getInstance().isNotched()){
            return false;
        }
        return true;
    }

    @Override
    protected void onVisibilityChanged() {
        var usedHand = getUsedHand();
        VisorAPI.client().getGuiManager().getCursorHandler()
                .clearFocus(usedHand);
    }

    @Override
    public void onDisable() {
        if (creativeScreen != null) {
            releaseEmbeddedCreative(true);
        }
        if (inventoryScreen != null) {
            inventoryScreen.removed();
        }
        inventoryScreen = null;
        creativeScreen = null;
        screen = null;
    }

    private boolean isAimedAtOverlay(@NotNull VRPose vrPose,
                                    @NotNull VROverlay overlay,
                                    boolean checkUpsideDown,
                                    float overlayBoundsExtraX,
                                    float overlayBoundsExtraY
    ) {

        var cursorHandler = VisorAPI.client().getGuiManager().getCursorHandler();
        if (!cursorHandler.isFacingOverlay(
                vrPose,
                overlay,
                checkUpsideDown
        )) {
            return false;
        }

        Vector3f newCursor = cursorHandler.findCursorPosition3D(
                vrPose,
                overlay.getPose().getPosition(),
                overlay.getPose().getRotation(),
                overlay.getPose().getScale(),
                overlay.getAspectRatio()
        );
        if (overlayBoundsExtraX != 0 || overlayBoundsExtraY != 0) {
            float multX = overlayBoundsExtraX / 2;
            float multY = overlayBoundsExtraY / 2;
            float x = 0, y =0;

            if ((newCursor.x < 0.5 && newCursor.x >= -multX)
                    || (newCursor.x > 0.5 && newCursor.x <= 1 + multX)) {
                x = 0.5f;
            } else {
                x = newCursor.x;
            }
            if ((newCursor.y < 0.5 && newCursor.y >= -multY)
                    || (newCursor.y > 0.5 && newCursor.y <= 1 + multY)) {
                y = 0.5f;
            } else {
                y = newCursor.y;
            }
            newCursor = new Vector3f(x, y, 0);
        }


        return overlay.isWithinCursorBounds(
                newCursor.x,
                newCursor.y
        );
    }

    public HandType getUsedHand(){
        return switch(optionsPose.getPositionAnchor()){
            case MAIN_HAND -> HandType.MAIN;
            case OFFHAND -> HandType.OFFHAND;
            default -> null;
        };
    }

    @Override
    protected @NotNull List<OverlayOptionGroup<?>> createOptions() {
        return List.of(
                new OverlayOptionsPose(
                        this,
                        it-> {
                            it.setTickPose(true);
                            it.setAimedRotation(false);
                            it.setPositionAnchor(PoseAnchor.OFFHAND);
                            it.setPositionOffset(
                                    -0.07f,
                                    -0.081f,
                                    0.2f
                            );
                            it.setRotationAnchor(PoseAnchor.OFFHAND);
                            it.setRotationOffset(
                                    0f,
                                    (float) (Math.PI/2),
                                    (float) Math.PI
                            );
                            it.setScale(0.5f);
                        }

                )
        );
    }

    private record UtilityAction(ItemStack icon,
                                 Component tooltip,
                                 BooleanSupplier visible,
                                 BooleanSupplier toggled,
                                 Runnable action,
                                 @Nullable String exclusiveGroup,
                                 @Nullable Runnable closeAction) {
    }
}
