package main.java.me.dniym.commands;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.identity.IdentityService;
import main.java.me.dniym.identity.ItemIdentity;
import main.java.me.dniym.identity.ItemIntegritySystem;
import main.java.me.dniym.identity.TrackabilityPolicy;
import main.java.me.dniym.identity.audit.DatabaseService;
import main.java.me.dniym.identity.presence.HolderRef;
import main.java.me.dniym.identity.presence.PresenceRecord;
import main.java.me.dniym.identity.presence.PresenceStore;
import main.java.me.dniym.utils.Scheduler;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.TileState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

final class ItemIntegrityInspectCommand {

    static final String PERMISSION = "illegalstack.itemintegrity.inspect";
    private static final int BLOCK_LOOKUP_DISTANCE = 6;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT);

    private ItemIntegrityInspectCommand() {
    }

    static boolean handle(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return false;
        }
        if (args[0].equalsIgnoreCase("metrics")) {
            if (hasPermission(sender)) {
                ItemIntegritySystem current = system(sender);
                if (current != null) sender.sendMessage(ChatColor.AQUA + "[ItemIntegrity] " + current.scannerMetrics());
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("inspect")) {
            if (!hasPermission(sender)) {
                return true;
            }
            if (args.length >= 2 && args[1].equalsIgnoreCase("block")) {
                inspectBlock(sender);
            } else {
                inspectHand(sender);
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("lookup")) {
            if (!hasPermission(sender)) {
                return true;
            }
            if (args.length < 2 || args[1].isBlank()) {
                sender.sendMessage(ChatColor.RED + "Uso: /istack lookup <itemId>");
                return true;
            }
            lookup(sender, args[1].trim());
            return true;
        }
        if (args[0].equalsIgnoreCase("restart") && args.length >= 2 && args[1].equalsIgnoreCase("sql")) {
            if (!hasAdminPermission(sender)) {
                return true;
            }
            compactSql(sender);
            return true;
        }
        return false;
    }

    private static boolean hasPermission(CommandSender sender) {
        if (sender.hasPermission(PERMISSION)) {
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Sem permissao: " + PERMISSION);
        return false;
    }

    private static boolean hasAdminPermission(CommandSender sender) {
        if (sender.hasPermission("illegalstack.admin")) {
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Sem permissao: illegalstack.admin");
        return false;
    }

    private static ItemIntegritySystem system(CommandSender sender) {
        ItemIntegritySystem system = IllegalStack.getPlugin().getItemIntegritySystem();
        if (system == null) {
            sender.sendMessage(ChatColor.RED + "[ItemIntegrity] Sistema ainda nao inicializado.");
        }
        return system;
    }

    private static void inspectHand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Este comando so pode ser usado por jogador.");
            return;
        }
        ItemIntegritySystem system = system(sender);
        if (system == null) {
            return;
        }

        ItemStack stack = player.getInventory().getItemInMainHand();
        sender.sendMessage(header("ItemIntegrity Inspect - Main Hand"));
        if (stack == null || stack.getType() == Material.AIR) {
            line(sender, "Material", "AIR");
            line(sender, "Trackable", "false");
            line(sender, "Integrity exempt", "false");
            warn(sender, "UNTRACKED / NO ITEM ID");
            return;
        }

        line(sender, "Material", stack.getType().name());
        ItemMeta meta = stack.hasItemMeta() ? stack.getItemMeta() : null;
        if (meta != null && meta.hasDisplayName()) {
            line(sender, "Display name", meta.getDisplayName());
        }
        line(sender, "Trackable", String.valueOf(TrackabilityPolicy.isTrackable(stack)));
        line(sender, "Integrity exempt", String.valueOf(TrackabilityPolicy.isExempt(stack)));

        ItemIdentity identity = system.identityService().readIdentity(stack);
        printIdentity(sender, identity);
        if (identity == null) {
            return;
        }
        printPresence(sender, "Presence", system.presenceStore().getCanonical(identity));
    }

    private static void inspectBlock(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Este comando so pode ser usado por jogador.");
            return;
        }
        ItemIntegritySystem system = system(sender);
        if (system == null) {
            return;
        }

        Block block = player.getTargetBlockExact(BLOCK_LOOKUP_DISTANCE);
        sender.sendMessage(header("ItemIntegrity Inspect - Block"));
        if (block == null) {
            warn(sender, "Nenhum bloco alvo em ate " + BLOCK_LOOKUP_DISTANCE + " blocos.");
            return;
        }

        line(sender, "Material", block.getType().name());
        line(sender, "World", block.getWorld().getName());
        line(sender, "Block", block.getX() + "," + block.getY() + "," + block.getZ());

        if (!(block.getState() instanceof TileState tileState)) {
            warn(sender, "Bloco nao possui TileState/PDC rastreavel.");
            warn(sender, "NO ITEM ID");
            return;
        }
        if (!(tileState instanceof ShulkerBox)) {
            warn(sender, "Bloco alvo nao e uma shulker box.");
        }

        ItemIdentity identity = system.identityService().readIdentity(tileState);
        printIdentity(sender, identity);
        if (identity == null) {
            return;
        }
        printPresence(sender, "Presence", system.presenceStore().getCanonical(identity));
    }

    private static void lookup(CommandSender sender, String itemId) {
        ItemIntegritySystem system = system(sender);
        if (system == null) {
            return;
        }

        PresenceStore store = system.presenceStore();
        Optional<PresenceRecord> canonical = store.getCanonical(itemId);
        Optional<PresenceRecord> divergent = store.getLastDivergentObservation(itemId);

        sender.sendMessage(header("ItemIntegrity Lookup"));
        if (canonical.isEmpty() && divergent.isEmpty()) {
            warn(sender, "Item ID nao encontrado: " + itemId);
            return;
        }

        ItemIdentity identity = canonical.or(() -> divergent).map(PresenceRecord::identity).orElse(null);
        sender.sendMessage(ChatColor.AQUA + "IDENTITY:");
        printIdentity(sender, identity);
        line(sender, "Status", canonical.isPresent() ? "ACTIVE (canonical cache)" : "UNKNOWN (sem canonical)");

        sender.sendMessage(ChatColor.AQUA + "CANONICAL PRESENCE:");
        printPresenceBody(sender, canonical);

        sender.sendMessage(ChatColor.AQUA + "LAST DIVERGENT OBSERVATION:");
        if (divergent.isPresent()) {
            printPresenceRecord(sender, divergent.get());
        } else {
            line(sender, "Divergent", "nenhuma observacao divergente registrada");
        }
    }

    private static void compactSql(CommandSender sender) {
        ItemIntegritySystem system = system(sender);
        if (system == null) {
            return;
        }
        sender.sendMessage(ChatColor.YELLOW + "[ItemIntegrity] Compactacao do SQLite iniciada.");
        sender.sendMessage(ChatColor.GRAY + "Preserva identidades/presencas e provas; reduz historico ao estado recente e limpa resumos antigos.");
        system.compactSqlForCurrentModel().thenAccept(result ->
                Scheduler.runTask(IllegalStack.getPlugin(), () -> printSqlMaintenanceResult(sender, result)));
    }

    private static void printSqlMaintenanceResult(CommandSender sender, DatabaseService.SqlMaintenanceResult result) {
        sender.sendMessage(header("ItemIntegrity SQL Maintenance"));
        if (!result.success()) {
            warn(sender, "Falhou: " + result.message());
            return;
        }
        line(sender, "Status", result.message());
        line(sender, "Duracao", Math.max(0L, result.finishedAtEpochMs() - result.startedAtEpochMs()) + "ms");
        line(sender, "Tamanho antes", formatBytes(result.sizeBeforeBytes()));
        line(sender, "Tamanho depois", formatBytes(result.sizeAfterBytes()));
        line(sender, "Economia", formatBytes(result.savedBytes()));
        line(sender, "item_events", result.itemEventsBefore() + " -> " + result.itemEventsAfter());
        line(sender, "item_event_rollups", String.valueOf(result.eventRollups()));
        line(sender, "integrity_cases", result.casesBefore() + " -> " + result.casesAfter());
        line(sender, "integrity_case_rollups", String.valueOf(result.caseRollups()));
        warn(sender, "VACUUM validado e checkpoint concluido. Nao apague WAL/SHM manualmente; o SQLite gerencia esses arquivos.");
    }

    private static void printIdentity(CommandSender sender, ItemIdentity identity) {
        if (identity == null) {
            warn(sender, "UNTRACKED / NO ITEM ID");
            return;
        }
        line(sender, "Item ID", identity.id());
        line(sender, "Origin", identity.origin().name());
        line(sender, "registered_at", formatTime(identity.registeredAtEpochMs()));
        line(sender, "registered_world", value(identity.registeredWorldRaw()));
        line(sender, "registered_x/y/z", coords(identity.registeredX(), identity.registeredY(), identity.registeredZ()));
    }

    private static void printPresence(CommandSender sender, String label, Optional<PresenceRecord> record) {
        sender.sendMessage(ChatColor.AQUA + label + ":");
        printPresenceBody(sender, record);
    }

    private static void printPresenceBody(CommandSender sender, Optional<PresenceRecord> record) {
        if (record.isEmpty()) {
            line(sender, "Presence", "nenhum PresenceRecord no cache atual");
            return;
        }
        printPresenceRecord(sender, record.get());
    }

    private static void printPresenceRecord(CommandSender sender, PresenceRecord record) {
        HolderRef holder = record.holder();
        line(sender, "PresenceState", record.state().name());
        line(sender, "HolderType", holder.type().name());
        line(sender, "holder logico", holder.describe());
        if (holder instanceof HolderRef.EnderChestHolder ender) {
            line(sender, "player UUID", ender.playerId().toString());
            line(sender, "player name", value(ender.playerName()));
            line(sender, "slot", String.valueOf(ender.slot()));
        } else if (holder instanceof HolderRef.PlayerHolder playerHolder) {
            line(sender, "player UUID", playerHolder.playerId().toString());
            line(sender, "player name", value(playerHolder.playerName()));
            line(sender, "slot", value(playerHolder.slot()));
        } else if (holder instanceof HolderRef.ItemEntityHolder entityHolder) {
            line(sender, "entity UUID", entityHolder.entityUuid().toString());
            line(sender, "world/coords", location(entityHolder.world(), entityHolder.x(), entityHolder.y(), entityHolder.z()));
        } else if (holder instanceof HolderRef.ContainerHolder containerHolder) {
            line(sender, "world/coords", location(containerHolder.world(), containerHolder.x(), containerHolder.y(), containerHolder.z()));
            line(sender, "slot", value(containerHolder.slot()));
        } else if (holder instanceof HolderRef.EntityHolder entityHolder) {
            line(sender, "entity UUID", entityHolder.entityUuid().toString());
            line(sender, "world/coords", location(entityHolder.world(), entityHolder.x(), entityHolder.y(), entityHolder.z()));
            line(sender, "detail", value(entityHolder.detail()));
        } else if (holder instanceof HolderRef.VirtualHolder virtualHolder) {
            line(sender, "virtual label", virtualHolder.label());
            line(sender, "owner/viewer", value(virtualHolder.viewerOrOwner()));
            line(sender, "slot", value(virtualHolder.slot()));
        }
        line(sender, "revision", String.valueOf(record.presenceRevision()));
        line(sender, "lastConfirmedAt", formatTime(record.lastConfirmedAtMs()));
    }

    private static String header(String title) {
        return ChatColor.AQUA + "----- " + title + " -----";
    }

    private static void line(CommandSender sender, String key, String value) {
        sender.sendMessage(ChatColor.GOLD + key + ": " + ChatColor.GRAY + value);
    }

    private static void warn(CommandSender sender, String value) {
        sender.sendMessage(ChatColor.YELLOW + value);
    }

    private static String formatTime(long epochMs) {
        if (epochMs <= 0) {
            return "unknown";
        }
        ZoneId zone = IllegalStack.getPlugin().getItemIntegritySystem() != null
                ? IllegalStack.getPlugin().getItemIntegritySystem().config().timezone()
                : ZoneId.systemDefault();
        return Instant.ofEpochMilli(epochMs).atZone(zone).format(TIME_FORMAT) + " (" + epochMs + ")";
    }

    private static String coords(Integer x, Integer y, Integer z) {
        if (x == null || y == null || z == null) {
            return "unknown";
        }
        return x + "," + y + "," + z;
    }

    private static String location(String world, Integer x, Integer y, Integer z) {
        return value(world) + " " + coords(x, y, z);
    }

    private static String value(Object value) {
        return value == null ? "unknown" : String.valueOf(value);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "unknown";
        }
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int unit = 0;
        while (value >= 1024.0 && unit < units.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }
}
