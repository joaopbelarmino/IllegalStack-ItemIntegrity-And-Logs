package main.java.me.dniym.audit;

import main.java.me.dniym.IllegalStack;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class AuditConfig {
    public record Threshold(long warning, long critical, int score) {}

    private final IllegalStack plugin;
    private final File file;
    private volatile YamlConfiguration yaml;

    public AuditConfig(IllegalStack plugin) throws IOException {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "item-audit.yml");
        reload();
    }

    public synchronized void reload() throws IOException {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IOException("Nao foi possivel criar " + plugin.getDataFolder());
        }
        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        defaults(loaded);
        loaded.options().copyDefaults(true);
        loaded.save(file);
        yaml = loaded;
    }

    private void defaults(YamlConfiguration y) {
        y.addDefault("audit.enabled", true);
        y.addDefault("audit.playerdata.world", "world");
        y.addDefault("audit.playerdata.scan-enabled", true);
        y.addDefault("audit.playerdata.initial-index-on-startup", true);
        y.addDefault("audit.playerdata.reconcile-interval-seconds", 300);
        y.addDefault("audit.playerdata.batch-size", 250);
        y.addDefault("audit.playerdata.retry-delay-ms", 250);
        y.addDefault("audit.containers.enabled", true);
        y.addDefault("audit.containers.debounce-ticks", 60);
        y.addDefault("audit.containers.max-dirty-ticks", 600);
        y.addDefault("audit.containers.snapshot-budget-per-cycle", 8);
        y.addDefault("audit.containers.dirty-capacity", 10000);
        y.addDefault("audit.containers.index-on-chunk-load", true);
        y.addDefault("audit.containers.destroyed-retention-days", 30);
        y.addDefault("audit.database.file", "item-audit.db");
        y.addDefault("audit.database.queue-capacity", 10000);
        y.addDefault("audit.database.batch-size", 100);
        y.addDefault("audit.gui.allow-item-removal", true);
        y.addDefault("audit.gui.require-confirmation", true);
        y.addDefault("audit.backup.retention-days", 7);
        y.addDefault("audit.backup.manual-retention-days", 0);
        y.addDefault("audit.suspicious.enabled", true);
        y.addDefault("audit.suspicious.minimum-score", 30);
        threshold(y, Material.NETHERITE_BLOCK, 64, 256, 40);
        threshold(y, Material.DIAMOND_BLOCK, 256, 1024, 20);
        threshold(y, Material.EMERALD_BLOCK, 512, 2048, 10);
        threshold(y, Material.ELYTRA, 16, 64, 20);
        threshold(y, Material.SPAWNER, 32, 128, 25);
    }

    private void threshold(YamlConfiguration y, Material material, long warning, long critical, int score) {
        String p = "audit.suspicious.materials." + material.name() + ".";
        y.addDefault(p + "warning", warning); y.addDefault(p + "critical", critical); y.addDefault(p + "score", score);
    }

    public boolean enabled() { return yaml.getBoolean("audit.enabled", true); }
    public boolean playerdataEnabled() { return yaml.getBoolean("audit.playerdata.scan-enabled", true); }
    public boolean initialIndex() { return yaml.getBoolean("audit.playerdata.initial-index-on-startup", true); }
    public String playerdataWorld() { return yaml.getString("audit.playerdata.world", "world"); }
    public long reconcileTicks() { return Math.max(20, yaml.getLong("audit.playerdata.reconcile-interval-seconds", 300) * 20L); }
    public int playerBatch() { return Math.max(1, yaml.getInt("audit.playerdata.batch-size", 250)); }
    public long retryDelayMs() { return Math.max(50, yaml.getLong("audit.playerdata.retry-delay-ms", 250)); }
    public boolean containersEnabled() { return yaml.getBoolean("audit.containers.enabled", true); }
    public long debounceTicks() { return Math.max(1, yaml.getLong("audit.containers.debounce-ticks", 60)); }
    public long maxDirtyTicks() { return Math.max(debounceTicks(), yaml.getLong("audit.containers.max-dirty-ticks", 600)); }
    public int snapshotBudget() { return Math.max(1, yaml.getInt("audit.containers.snapshot-budget-per-cycle", 8)); }
    public int dirtyCapacity() { return Math.max(64, yaml.getInt("audit.containers.dirty-capacity", 10000)); }
    public boolean indexChunkLoad() { return yaml.getBoolean("audit.containers.index-on-chunk-load", true); }
    public int destroyedRetentionDays() { return Math.max(1, yaml.getInt("audit.containers.destroyed-retention-days", 30)); }
    public int dbQueueCapacity() { return Math.max(100, yaml.getInt("audit.database.queue-capacity", 10000)); }
    public int dbBatchSize() { return Math.max(1, yaml.getInt("audit.database.batch-size", 100)); }
    public File databaseFile() { return new File(plugin.getDataFolder(), yaml.getString("audit.database.file", "item-audit.db")); }
    public boolean removalEnabled() { return yaml.getBoolean("audit.gui.allow-item-removal", true); }
    public boolean confirmationRequired() { return yaml.getBoolean("audit.gui.require-confirmation", true); }
    public int backupRetentionDays() { return Math.max(1, yaml.getInt("audit.backup.retention-days", 7)); }
    public int manualBackupRetentionDays() { return Math.max(0, yaml.getInt("audit.backup.manual-retention-days", 0)); }
    public boolean suspiciousEnabled() { return yaml.getBoolean("audit.suspicious.enabled", true); }
    public int minimumScore() { return Math.max(0, yaml.getInt("audit.suspicious.minimum-score", 30)); }

    public Map<String, Threshold> thresholds() {
        Map<String, Threshold> out = new LinkedHashMap<>();
        var section = yaml.getConfigurationSection("audit.suspicious.materials");
        if (section == null) return out;
        for (String key : section.getKeys(false)) {
            String p = "audit.suspicious.materials." + key + ".";
            out.put(key.toLowerCase(Locale.ROOT), new Threshold(yaml.getLong(p + "warning"),
                    yaml.getLong(p + "critical"), yaml.getInt(p + "score")));
        }
        return out;
    }
}
