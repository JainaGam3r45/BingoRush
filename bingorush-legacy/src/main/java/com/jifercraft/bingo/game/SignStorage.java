package com.jifercraft.bingo.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Persists registered game signs to data/signs.json. */
public final class SignStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<SavedSign>>() {}.getType();

    public static final class SavedSign {
        private String world;
        private int x;
        private int y;
        private int z;
        private String mapId;

        public SavedSign() {
        }

        public SavedSign(String world, int x, int y, int z, String mapId) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.mapId = mapId;
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

        public String mapId() {
            return mapId;
        }
    }

    private SignStorage() {
    }

    public static List<SavedSign> load(File file) {
        if (!file.exists()) {
            return new ArrayList<SavedSign>();
        }
        try {
            FileInputStream fis = new FileInputStream(file);
            InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8);
            try {
                List<SavedSign> list = GSON.fromJson(reader, LIST_TYPE);
                return list != null ? list : new ArrayList<SavedSign>();
            } finally {
                reader.close();
            }
        } catch (Exception e) {
            return new ArrayList<SavedSign>();
        }
    }

    public static void save(File file, List<SavedSign> signs) {
        String json = GSON.toJson(signs, LIST_TYPE);
        AtomicFiles.write(file, json);
    }
}
