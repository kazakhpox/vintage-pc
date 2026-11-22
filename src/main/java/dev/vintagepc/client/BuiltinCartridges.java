package dev.vintagepc.client;

import com.google.gson.JsonParser;
import dev.vintagepc.VintagePC;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Встроенные свободные картриджи (Freedoom и т.п.) лежат в jar мода и при первом запуске
 * копируются в .minecraft/vintagepc/cartridges/ — дальше это обычные картриджи-папки.
 * Обновляются, только если номер "builtin" в манифесте jar больше установленного;
 * удалённый игроком картридж не возвращается (метка .&lt;id&gt;.installed остаётся, а папки нет).
 */
final class BuiltinCartridges {
    private static final String BASE = "/assets/vintagepc/builtin/";

    static void installAsync(Path cartridges) {
        Thread t = new Thread(() -> {
            try { install(cartridges); }
            catch (Exception e) { VintagePC.LOG.warn("Не удалось установить встроенные картриджи", e); }
        }, "VintagePC-builtin");
        t.setDaemon(true);
        t.start();
    }

    static synchronized void install(Path cartridges) throws IOException {
        Map<String, List<String>> byCart = new LinkedHashMap<>();
        try (InputStream in = BuiltinCartridges.class.getResourceAsStream(BASE + "index.txt")) {
            if (in == null) return;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                line = line.trim();
                if (line.isEmpty() || !line.contains("/")) continue;
                byCart.computeIfAbsent(line.substring(0, line.indexOf('/')), k -> new ArrayList<>()).add(line);
            }
        }
        for (var e : byCart.entrySet()) {
            String id = e.getKey();
            Path dir = cartridges.resolve(id);
            Path marker = cartridges.resolve("." + id + ".installed");
            if (Files.exists(marker) && !Files.exists(dir)) continue; // игрок удалил картридж сам
            int want = version(BuiltinCartridges.class.getResourceAsStream(BASE + id + "/cartridge.json"));
            Path manifest = dir.resolve(Cartridges.MANIFEST);
            int have = Files.exists(manifest) ? version(Files.newInputStream(manifest)) : -1;
            if (have >= want) continue;
            VintagePC.LOG.info("Устанавливаю встроенный картридж {} (версия {})", id, want);
            Path tmp = cartridges.resolve("." + id + ".installing");
            Files.createDirectories(tmp);
            for (String f : e.getValue()) {
                try (InputStream in = BuiltinCartridges.class.getResourceAsStream(BASE + f)) {
                    if (in == null) throw new FileNotFoundException(f);
                    Path dst = tmp.resolve(f.substring(id.length() + 1));
                    Files.createDirectories(dst.getParent());
                    Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // Сохраняем файлы игрока (например, сейвы), которых нет в сборке
            if (Files.isDirectory(dir)) {
                try (var s = Files.walk(dir)) {
                    for (Path p : s.filter(Files::isRegularFile).toList()) {
                        Path rel = dir.relativize(p), dst = tmp.resolve(rel.toString());
                        if (!Files.exists(dst)) { Files.createDirectories(dst.getParent()); Files.move(p, dst); }
                    }
                }
                deleteTree(dir);
            }
            Files.move(tmp, dir, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(marker, Integer.toString(want));
        }
    }

    private static int version(InputStream in) {
        if (in == null) return 0;
        try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            var o = JsonParser.parseReader(r).getAsJsonObject();
            return o.has("builtin") ? o.get("builtin").getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private BuiltinCartridges() {}
}
