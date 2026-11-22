package dev.vintagepc.client;

import dev.vintagepc.VintagePC;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.List;

/**
 * Распаковывает оболочку (HTML/CSS/JS из jar) в .minecraft/vintagepc/web/.
 * Папка .minecraft/vintagepc/games/ не трогается — туда игрок кладёт свои WASM-порты,
 * образы дисков и данные игр (их нельзя распространять в моде из-за лицензий).
 */
final class WebAssets {
    static final List<String> FILES = List.of(
            "index.html", "shell.css", "vpc-net.js", "shell.js",
            "apps/notepad.js", "apps/minesweeper.js", "apps/network.js", "apps/chat.js",
            "apps/tictactoe.js", "apps/v86.js", "apps/games.js", "games-example.js",
            "lib/98.css", "lib/ms_sans_serif.woff2", "lib/ms_sans_serif_bold.woff2", "lib/98.css.LICENSE");

    static Path root() { return FMLPaths.GAMEDIR.get().resolve("vintagepc"); }

    static Path extract() {
        Path web = root().resolve("web");
        try {
            for (String f : FILES) {
                try (InputStream in = WebAssets.class.getResourceAsStream("/assets/vintagepc/web/" + f)) {
                    if (in == null) { VintagePC.LOG.warn("missing web asset {}", f); continue; }
                    Path dst = web.resolve(f);
                    Files.createDirectories(dst.getParent());
                    Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Path games = root().resolve("games");
            Files.createDirectories(games);
            if (!Files.exists(games.resolve("games.js")))
                Files.copy(web.resolve("games-example.js"), games.resolve("games.js"));
        } catch (IOException e) {
            VintagePC.LOG.error("Не удалось распаковать оболочку", e);
        }
        return web.resolve("index.html");
    }

    private WebAssets() {}
}
