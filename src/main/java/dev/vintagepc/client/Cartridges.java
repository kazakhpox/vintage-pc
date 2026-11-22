package dev.vintagepc.client;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * «Картриджи» — игры как независимые папки в .minecraft/vintagepc/cartridges/&lt;id&gt;/ с манифестом cartridge.json.
 * Ничего не распаковывается и не грузится в память заранее: ОС получает только список манифестов,
 * а файлы игры стримятся по HTTP (с Range) в момент запуска.
 *
 * Пример манифеста — см. README, раздел «Картриджи».
 */
final class Cartridges {
    static final String MANIFEST = "cartridge.json";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private final Path root, cache;

    Cartridges(Path root, Path cache) { this.root = root; this.cache = cache; }

    Path dir(String id) {
        if (!id.matches("[A-Za-z0-9_.-]{1,64}") || id.startsWith(".")) return null;
        Path d = root.resolve(id);
        return Files.isRegularFile(d.resolve(MANIFEST)) ? d : null;
    }

    /** Список картриджей для рабочего стола (манифест + id). Битые манифесты пропускаются с ошибкой в поле error. */
    String listJson() throws IOException {
        JsonArray arr = new JsonArray();
        if (Files.isDirectory(root)) {
            try (Stream<Path> s = Files.list(root)) {
                for (Path d : s.sorted().toList()) {
                    Path m = d.resolve(MANIFEST);
                    if (!Files.isRegularFile(m)) continue;
                    JsonObject o;
                    try (Reader r = Files.newBufferedReader(m, StandardCharsets.UTF_8)) {
                        o = JsonParser.parseReader(r).getAsJsonObject();
                    } catch (Exception e) {
                        o = new JsonObject();
                        o.addProperty("name", d.getFileName().toString());
                        o.addProperty("error", "cartridge.json: " + e.getMessage());
                    }
                    o.addProperty("id", d.getFileName().toString());
                    if (!o.has("name")) o.addProperty("name", d.getFileName().toString());
                    arr.add(o);
                }
            }
        }
        return GSON.toJson(arr);
    }

    JsonObject manifest(Path dir) throws IOException {
        try (Reader r = Files.newBufferedReader(dir.resolve(MANIFEST), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    /**
     * Для js-dos: если в манифесте нет готового "bundle", собираем .jsdos из содержимого папки
     * (диск C:) + сгенерированный .jsdos/dosbox.conf. Результат кэшируется и пересобирается,
     * только когда в папке что-то поменялось.
     */
    Path jsdosBundle(String id) throws IOException {
        Path dir = dir(id);
        if (dir == null) return null;
        JsonObject m = manifest(dir);
        JsonObject dos = m.has("jsdos") ? m.getAsJsonObject("jsdos") : new JsonObject();
        if (dos.has("bundle")) return safeResolve(dir, dos.get("bundle").getAsString());

        Files.createDirectories(cache);
        Path out = cache.resolve(id + ".jsdos");
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile)
                    .filter(p -> !dir.relativize(p).toString().replace('\\', '/').equals(MANIFEST))
                    .filter(p -> !dir.relativize(p).getName(0).toString().startsWith("."))
                    .sorted().toList();
        }
        FileTime newest = Files.getLastModifiedTime(dir.resolve(MANIFEST));
        for (Path p : files) { FileTime t = Files.getLastModifiedTime(p); if (t.compareTo(newest) > 0) newest = t; }
        if (Files.exists(out) && Files.getLastModifiedTime(out).compareTo(newest) > 0) return out;

        Path tmp = cache.resolve(id + ".jsdos.tmp");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16))) {
            zip.setLevel(Deflater.BEST_SPEED);
            zip.putNextEntry(new ZipEntry(".jsdos/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(".jsdos/dosbox.conf"));
            zip.write(dosboxConf(dos).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (Path p : files) {
                zip.putNextEntry(new ZipEntry(dir.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, zip);
                zip.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return out;
    }

    static String dosboxConf(JsonObject dos) {
        if (dos.has("conf")) return dos.get("conf").getAsString();
        StringBuilder sb = new StringBuilder()
                .append("[sdl]\nautolock=false\n\n")
                .append("[dosbox]\nmachine=").append(str(dos, "machine", "svga_s3"))
                .append("\nmemsize=").append(str(dos, "memsize", "16")).append("\n\n")
                .append("[cpu]\ncore=auto\ncputype=auto\ncycles=").append(str(dos, "cycles", "auto")).append("\n\n")
                .append("[mixer]\nrate=44100\n\n[sblaster]\nsbtype=sb16\n\n")
                .append("[ipx]\nipx=true\n\n")
                .append("[autoexec]\necho off\nmount c .\nc:\n");
        if (dos.has("autoexec")) for (JsonElement e : dos.getAsJsonArray("autoexec")) sb.append(e.getAsString()).append('\n');
        return sb.toString();
    }

    private static String str(JsonObject o, String k, String def) { return o.has(k) ? o.get(k).getAsString() : def; }

    /** Разрешает путь внутри корня, отбрасывая "..", абсолютные пути и скрытые файлы. */
    static Path safeResolve(Path base, String rel) {
        if (rel.contains("\0")) return null;
        Path p = base.resolve(rel.replace('\\', '/').replaceAll("^/+", "")).normalize();
        if (!p.startsWith(base.normalize())) return null;
        for (Path part : base.normalize().relativize(p)) if (part.toString().startsWith(".")) return null;
        return p;
    }
}
