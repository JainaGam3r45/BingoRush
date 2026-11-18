package com.jifercraft.bingo.season;

import java.util.Objects;

/** Where a gift stands and what kind it is. */
public final class GiftLocation {

    private final String id;
    private final String typeId;
    private final String world;
    private final int x;
    private final int y;
    private final int z;

    public GiftLocation(String id, String typeId, String world, int x, int y, int z) {
        this.id = id != null ? id : (world + ":" + x + "," + y + "," + z);
        this.typeId = typeId != null ? typeId : "default";
        this.world = world != null ? world : "world";
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public String id() {
        return id;
    }

    public String typeId() {
        return typeId;
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

    public String coordinateKey() {
        return world + ":" + x + "," + y + "," + z;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GiftLocation that = (GiftLocation) o;
        return x == that.x && y == that.y && z == that.z
                && Objects.equals(world, that.world);
    }

    @Override
    public int hashCode() {
        return Objects.hash(world, x, y, z);
    }

    @Override
    public String toString() {
        return "GiftLocation{" +
                "id='" + id + '\'' +
                ", typeId='" + typeId + '\'' +
                ", coord='" + coordinateKey() + '\'' +
                '}';
    }
}
