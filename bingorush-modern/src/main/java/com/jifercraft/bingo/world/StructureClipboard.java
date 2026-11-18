package com.jifercraft.bingo.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * A waiting room in a plain JSON file: block offsets and block data,
 * captured from the world and pasted into match worlds.
 */
public final class StructureClipboard {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class BlockEntry {
        public int x;
        public int y;
        public int z;
        public String material;
        public String blockData;

        public BlockEntry() {
        }

        public BlockEntry(int x, int y, int z, String material, String blockData) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.material = material;
            this.blockData = blockData;
        }
    }

    public int width;
    public int height;
    public int length;
    public double centerOffsetX;
    public double centerOffsetY;
    public double centerOffsetZ;
    public List<BlockEntry> blocks = new ArrayList<>();

    public StructureClipboard() {
    }

    /** The blocks between two corners, with the spawn point relative to them. */
    public static StructureClipboard capture(Location pos1, Location pos2, Location center) {
        StructureClipboard clip = new StructureClipboard();
        World world = pos1.getWorld();
        if (world == null || pos2.getWorld() == null || !world.equals(pos2.getWorld())) {
            return clip;
        }

        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
        int minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
        int maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());

        clip.width = maxX - minX + 1;
        clip.height = maxY - minY + 1;
        clip.length = maxZ - minZ + 1;

        Location spawn = center != null ? center : new Location(world, minX + clip.width / 2.0, minY + 1.0, minZ + clip.length / 2.0);
        clip.centerOffsetX = spawn.getX() - minX;
        clip.centerOffsetY = spawn.getY() - minY;
        clip.centerOffsetZ = spawn.getZ() - minZ;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir()) {
                        continue;
                    }
                    String dataStr = block.getBlockData().getAsString();
                    clip.blocks.add(new BlockEntry(x - minX, y - minY, z - minZ, block.getType().name(), dataStr));
                }
            }
        }
        return clip;
    }

    /** Builds it with its corner at origin and returns where players appear. */
    public Location paste(Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return origin;
        }
        int originX = origin.getBlockX();
        int originY = origin.getBlockY();
        int originZ = origin.getBlockZ();

        for (BlockEntry entry : blocks) {
            Block block = world.getBlockAt(originX + entry.x, originY + entry.y, originZ + entry.z);
            // Block data from a newer version may not parse here; the plain
            // material is still better than a hole in the floor.
            BlockData data = null;
            if (entry.blockData != null && !entry.blockData.isBlank()) {
                try {
                    data = Bukkit.createBlockData(entry.blockData);
                } catch (IllegalArgumentException e) {
                    data = null;
                }
            }
            if (data != null) {
                block.setBlockData(data, false);
            } else {
                Material mat = Material.matchMaterial(entry.material);
                if (mat != null && mat.isBlock()) {
                    block.setType(mat, false);
                }
            }
        }

        return new Location(world, originX + centerOffsetX, originY + centerOffsetY, originZ + centerOffsetZ, origin.getYaw(), origin.getPitch());
    }

    public void clear(Location origin) {
        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        int originX = origin.getBlockX();
        int originY = origin.getBlockY();
        int originZ = origin.getBlockZ();

        for (BlockEntry entry : blocks) {
            world.getBlockAt(originX + entry.x, originY + entry.y, originZ + entry.z).setType(Material.AIR, false);
        }
    }

    /**
     * What players wait on until an admin captures a room of their own: a
     * 9x9 glass floor with a stone rim and a glass rail so nobody walks off.
     */
    public static StructureClipboard defaultPlatform() {
        StructureClipboard clip = new StructureClipboard();
        int size = 9;
        clip.width = size;
        clip.height = 4;
        clip.length = size;
        clip.centerOffsetX = 4.5;
        clip.centerOffsetY = 1.0;
        clip.centerOffsetZ = 4.5;

        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean edge = (x == 0 || x == size - 1 || z == 0 || z == size - 1);
                if (edge) {
                    clip.blocks.add(new BlockEntry(x, 0, z, "SMOOTH_STONE", null));
                    clip.blocks.add(new BlockEntry(x, 1, z, "GLASS", null));
                    clip.blocks.add(new BlockEntry(x, 2, z, "GLASS", null));
                } else {
                    clip.blocks.add(new BlockEntry(x, 0, z, "WHITE_STAINED_GLASS", null));
                }
            }
        }
        clip.blocks.add(new BlockEntry(0, 1, 0, "GLOWSTONE", null));
        clip.blocks.add(new BlockEntry(size - 1, 1, 0, "GLOWSTONE", null));
        clip.blocks.add(new BlockEntry(0, 1, size - 1, "GLOWSTONE", null));
        clip.blocks.add(new BlockEntry(size - 1, 1, size - 1, "GLOWSTONE", null));

        return clip;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** Null when the file is missing or broken; a broken one is logged. */
    public static StructureClipboard load(File file, Logger logger) {
        if (!file.exists()) {
            return null;
        }
        try {
            StructureClipboard clip = GSON.fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8),
                    StructureClipboard.class);
            if (clip == null || clip.blocks == null) {
                logger.warning(file.getName() + " has no blocks, using the built-in waiting room");
                return null;
            }
            return clip;
        } catch (IOException | JsonParseException e) {
            logger.warning("Could not read " + file.getName() + ", using the built-in waiting room: " + e.getMessage());
            return null;
        }
    }
}
