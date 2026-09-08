package main.java.me.dniym.identity;

import main.java.me.dniym.identity.presence.HolderRef;
import main.java.me.dniym.identity.presence.HolderType;
import main.java.me.dniym.identity.presence.PresenceRecord;
import main.java.me.dniym.identity.presence.PresenceState;
import main.java.me.dniym.identity.presence.PresenceStore;
import org.bukkit.Bukkit;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.Optional;
import java.util.UUID;

public final class VirtualCustodyService {

    private final IdentityService identityService;
    private final PresenceStore presenceStore;

    public VirtualCustodyService(IdentityService identityService, PresenceStore presenceStore) {
        this.identityService = identityService;
        this.presenceStore = presenceStore;
    }

    public boolean tryCommitIntoVirtual(ItemIdentity identity, HolderRef.VirtualHolder virtualHolder) {
        Optional<PresenceRecord> canonicalOpt = presenceStore.getCanonical(identity);
        if (canonicalOpt.isEmpty()) {
            presenceStore.commitHandoff(identity, virtualHolder, PresenceState.LIVE_CONFIRMED);
            return true;
        }

        PresenceRecord canonical = canonicalOpt.get();
        HolderRef current = canonical.holder();
        if (HolderRef.sameOwner(current, virtualHolder)) {
            presenceStore.observe(identity, virtualHolder, PresenceState.LIVE_CONFIRMED);
            return true;
        }
        if (isVirtual(current)) {
            presenceStore.commitHandoff(identity, virtualHolder, PresenceState.LIVE_CONFIRMED);
            return true;
        }

        if (current instanceof HolderRef.PlayerHolder playerHolder) {
            Player previousOwner = Bukkit.getPlayer(playerHolder.playerId());
            if (previousOwner == null || physicallyContains(previousOwner, identity.id())) {
                return false;
            }
            presenceStore.commitHandoff(identity, virtualHolder, PresenceState.LIVE_CONFIRMED);
            return true;
        }

        return false;
    }

    public boolean tryCommitVirtualToPlayer(ItemIdentity identity, HolderRef.PlayerHolder newHolder) {
        Optional<PresenceRecord> canonicalOpt = presenceStore.getCanonical(identity);
        if (canonicalOpt.isEmpty() || !isVirtual(canonicalOpt.get().holder())) {
            return false;
        }

        HolderRef.VirtualHolder virtualHolder = (HolderRef.VirtualHolder) canonicalOpt.get().holder();
        if (isCarriedShulkerCustody(virtualHolder)
                && carriedShulkerStillContains(newHolder, virtualHolder, identity.id())) {
            return false;
        }
        UUID previousOwnerId = parseUuid(virtualHolder.viewerOrOwner());
        if (previousOwnerId != null && !previousOwnerId.equals(newHolder.playerId())) {
            Player previousOwner = Bukkit.getPlayer(previousOwnerId);
            if (previousOwner != null && physicallyContains(previousOwner, identity.id())) {
                return false;
            }
        }

        presenceStore.commitHandoff(identity, newHolder, PresenceState.LIVE_CONFIRMED);
        return true;
    }

    public boolean tryCommitMissingPlayerItemToExternal(ItemIdentity identity, UUID previousPlayerId,
                                                        String previousPlayerName) {
        Optional<PresenceRecord> canonicalOpt = presenceStore.getCanonical(identity);
        if (canonicalOpt.isEmpty()) {
            return false;
        }

        PresenceRecord canonical = canonicalOpt.get();
        if (canonical.state().isTerminal() || !(canonical.holder() instanceof HolderRef.PlayerHolder playerHolder)) {
            return false;
        }
        if (!playerHolder.playerId().equals(previousPlayerId)) {
            return false;
        }

        Player previousOwner = Bukkit.getPlayer(previousPlayerId);
        if (previousOwner == null || physicallyContains(previousOwner, identity.id())) {
            return false;
        }

        HolderRef.VirtualHolder external = new HolderRef.VirtualHolder(HolderType.EXTERNAL_PLUGIN,
                "command_or_external_storage", previousPlayerId.toString(), null);
        presenceStore.commitHandoff(identity, external, PresenceState.LIVE_CONFIRMED);
        return true;
    }

    public boolean isVirtual(HolderRef holder) {
        return holder != null
                && (holder.type() == HolderType.VIRTUAL_INVENTORY || holder.type() == HolderType.EXTERNAL_PLUGIN);
    }

    public boolean physicallyContains(Player player, String itemId) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        for (ItemStack item : inventory.getContents()) {
            if (hasIdentity(item, itemId)) {
                return true;
            }
        }
        return hasIdentity(inventory.getItemInOffHand(), itemId);
    }

    private boolean hasIdentity(ItemStack item, String itemId) {
        ItemIdentity identity = identityService.readIdentity(item);
        return identity != null && identity.id().equals(itemId);
    }

    private boolean isCarriedShulkerCustody(HolderRef.VirtualHolder holder) {
        return holder.label().startsWith("carried_shulker:");
    }

    private boolean carriedShulkerStillContains(HolderRef.PlayerHolder playerHolder,
                                                HolderRef.VirtualHolder virtualHolder, String itemId) {
        Player player = Bukkit.getPlayer(playerHolder.playerId());
        if (player == null || !player.isOnline()) {
            return false;
        }
        String outerItemId = virtualHolder.label().substring("carried_shulker:".length());
        ItemStack[] contents = player.getInventory().getContents();
        for (ItemStack item : contents) {
            if (matchingShulkerContains(item, outerItemId, itemId)) {
                return true;
            }
        }
        return contents.length <= 40
                && matchingShulkerContains(player.getInventory().getItemInOffHand(), outerItemId, itemId);
    }

    private boolean matchingShulkerContains(ItemStack item, String outerItemId, String nestedItemId) {
        if (!hasIdentity(item, outerItemId)
                || !(item.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox shulker)) {
            return false;
        }
        for (ItemStack content : shulker.getInventory().getContents()) {
            if (hasIdentity(content, nestedItemId)) {
                return true;
            }
        }
        return false;
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
