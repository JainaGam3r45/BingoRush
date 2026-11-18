package com.jifercraft.bingo.season;

/** One block the season changed: where it is and what it was before. */
public final class BlockJournalEntry {

    // Not final: Gson fills them in when it reads the file.
    private String world;
    private int x;
    private int y;
    private int z;
    private String blockData;
    private String tag;
    private long recordedAt;

    public BlockJournalEntry() {
    }

    public BlockJournalEntry(String world, int x, int y, int z, String blockData, String tag, long recordedAt) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockData = blockData;
        this.tag = tag;
        this.recordedAt = recordedAt;
    }

    public String world() {
        return world;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    public String blockData() {
        return blockData;
    }

    public String tag() {
        return tag;
    }

    public long recordedAt() {
        return recordedAt;
    }

    public String coordinateKey() {
        return world + ":" + x + "," + y + "," + z;
    }
}
