package main.java.me.dniym.audit.playerdata;

import java.io.*;
import java.util.ArrayDeque;

/** Bounded read-only projection. Skipped branches are validated without constructing tag objects. */
final class NbtProjection {
    record Result(byte[] bytes, boolean partial) {}
    private final DataInputStream in;
    private boolean partial;
    private int nodes;
    private NbtProjection(byte[] bytes) { in = new DataInputStream(new ByteArrayInputStream(bytes)); }

    static Result project(byte[] raw) throws IOException {
        var reader = new NbtProjection(raw);
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        int type = reader.in.readUnsignedByte();
        if (type != 10) throw new IOException("Root NBT invalido");
        out.writeByte(type); out.writeUTF(reader.in.readUTF());
        reader.payload(type, 0, "", out);
        if (reader.in.available() != 0) throw new IOException("NBT com dados extras");
        return new Result(bytes.toByteArray(), reader.partial);
    }

    private void payload(int type, int depth, String name, DataOutputStream out) throws IOException {
        boolean essential = depth <= 4 && (name.equals("id") || name.equals("count") || name.equals("Count") || name.equals("Slot"));
        if ((depth >= 32 || ++nodes > 100000) && !essential) {
            partial = true; skip(type); empty(type, out); return;
        }
        switch (type) {
            case 1 -> out.writeByte(in.readByte());
            case 2 -> out.writeShort(in.readShort());
            case 3, 5 -> out.writeInt(in.readInt());
            case 4, 6 -> out.writeLong(in.readLong());
            case 7, 11, 12 -> {
                int count = count(); int width = type == 7 ? 1 : type == 11 ? 4 : 8;
                long length = (long) count * width; check(length);
                out.writeInt(count); out.write(in.readNBytes((int) length));
            }
            case 8 -> out.writeUTF(in.readUTF());
            case 9 -> {
                int child = in.readUnsignedByte(), count = count(); validList(child, count);
                boolean inventory = depth == 1 && (name.equals("Inventory") || name.equals("EnderItems"));
                int retained = Math.min(count, inventory ? 4096 : 100000);
                if (retained != count) partial = true;
                out.writeByte(child); out.writeInt(retained);
                for (int i = 0; i < count; i++) {
                    if (inventory) nodes = 0;
                    if (i < retained) payload(child, depth + 1, "", out); else skip(child);
                }
            }
            case 10 -> {
                int child;
                while ((child = in.readUnsignedByte()) != 0) {
                    String key = in.readUTF(); out.writeByte(child); out.writeUTF(key);
                    // Keep independent player inventory roots reachable after a large unrelated branch.
                    if (depth == 0) nodes = 0;
                    payload(child, depth + 1, key, out);
                }
                out.writeByte(0);
            }
            default -> throw new IOException("Tag NBT desconhecida");
        }
    }

    private void empty(int type, DataOutputStream out) throws IOException {
        switch (type) {
            case 1, 10 -> out.writeByte(0);
            case 2 -> out.writeShort(0);
            case 3, 5, 7, 11, 12 -> out.writeInt(0);
            case 4, 6 -> out.writeLong(0);
            case 8 -> out.writeUTF("");
            case 9 -> { out.writeByte(0); out.writeInt(0); }
            default -> throw new IOException("Tag NBT desconhecida");
        }
    }
    private int count() throws IOException { int n = in.readInt(); if (n < 0) throw new IOException("Contagem NBT negativa"); return n; }
    private void check(long n) throws IOException { if (n < 0 || n > in.available()) throw new IOException("Tamanho NBT invalido"); }
    private void skipBytes(long n) throws IOException { check(n); in.skipNBytes(n); }
    private void validList(int type, int count) throws IOException {
        if (type < 0 || type > 12 || type == 0 && count != 0 || count > in.available()) throw new IOException("Lista NBT invalida");
    }
    private record Pending(int type, int count) {}
    private void skip(int type) throws IOException {
        var pending = new ArrayDeque<Pending>(); pending.push(new Pending(type, 1));
        while (!pending.isEmpty()) {
            var next = pending.pop();
            if (next.count == 0) continue;
            if (next.count > 1) pending.push(new Pending(next.type, next.count - 1));
            switch (next.type) {
                case 1 -> skipBytes(1); case 2 -> skipBytes(2);
                case 3, 5 -> skipBytes(4); case 4, 6 -> skipBytes(8);
                case 7 -> skipBytes(count()); case 8 -> skipBytes(in.readUnsignedShort());
                case 11 -> skipBytes((long) count() * 4); case 12 -> skipBytes((long) count() * 8);
                case 9 -> { int child = in.readUnsignedByte(), n = count(); validList(child, n); pending.push(new Pending(child, n)); }
                case 10 -> {
                    int child = in.readUnsignedByte();
                    if (child != 0) { skipBytes(in.readUnsignedShort()); pending.push(new Pending(10, 1)); pending.push(new Pending(child, 1)); }
                }
                default -> throw new IOException("Tag NBT desconhecida");
            }
            if (pending.size() > 4096) throw new IOException("Estrutura NBT excessiva");
        }
    }
}
