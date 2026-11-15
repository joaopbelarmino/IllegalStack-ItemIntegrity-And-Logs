package main.java.me.dniym.audit.container;

import org.bukkit.inventory.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class SnapshotCodec {
    private SnapshotCodec() {}

    public static byte[] serialize(ItemStack[] contents) {
        return ItemStack.serializeItemsAsBytes(contents == null ? new ItemStack[0] : contents);
    }

    public static ItemStack[] deserialize(byte[] bytes) { return ItemStack.deserializeItemsFromBytes(bytes); }

    public static byte[] compress(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, raw.length / 2));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(raw); }
        return out.toByteArray();
    }

    public static byte[] decompress(byte[] compressed, int maxBytes) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int total = 0, read;
            while ((read = gzip.read(buffer)) >= 0) {
                total += read; if (total > maxBytes) throw new IOException("Snapshot excede limite");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    public static String hash(byte[] raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
