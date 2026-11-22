# Counter-Strike 1.6 для Vintage PC (бета)

> Проверено: движок и файловая система запускаются. Игровой процесс и сетевая игра не проверены — для этого нужны данные игры.

В картридже только открытый движок и код — **данных игры Valve здесь нет**.
Положите в эту папку `valve.zip` из своей копии CS 1.6 (папки `valve/` и `cstrike/` в корне архива).
Собрать его можно скриптом из https://github.com/modesage/cs1.6-browser (`get_cs_assets/`)
или вручную из `steamapps/common/Half-Life/` своей Steam-копии.

| Файл | Проект | Лицензия |
|---|---|---|
| `engine/xash.js`, `xash.wasm`, `filesystem_stdio.wasm`, `libref_webgl2.wasm`, `valve_extras.pk3` | Xash3D FWGS + webxash3d-fwgs (npm `xash3d-fwgs` 1.2.2) | GPL-3.0 (движок) / MIT (обёртка) |
| `engine/cl_dlls/*`, `engine/dlls/cs_emscripten_wasm32.wasm` | cs16-client (npm `cs16-client` 0.1.2) | MIT |
| `engine/dlls/yapb_emscripten_wasm32.wasm`, `engine/yapb_addons.pk3` | YaPB (боты) из `cs16-client` 0.1.2 | MIT |
| `engine/jszip.min.js` | JSZip 3.10.1 | MIT / GPL-3.0 |
| `xash-net.js` | адаптация `Net` из `xash3d-fwgs` (MIT) к сети Vintage PC | MIT |

Озвучка, карты и прочие ресурсы Valve из `extras.pk3` пакета cs16-client в мод намеренно **не включены**.
Страница запуска основана на подходе https://github.com/modesage/cs1.6-browser.
