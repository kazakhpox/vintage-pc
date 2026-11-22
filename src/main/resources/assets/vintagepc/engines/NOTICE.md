# Сторонние движки, встроенные в Vintage PC

Файлы распространяются без изменений, под своими лицензиями (не под MIT мода):

| Папка | Проект | Версия | Лицензия | Исходники |
|---|---|---|---|---|
| `js-dos/` | js-dos + emulators (DOSBox) | 8.5.4 | GPL-2.0 | https://github.com/caiiiycuk/js-dos |
| `v86/libv86.js`, `v86/v86.wasm` | v86 | 0.5.476 | BSD-2-Clause (`v86/LICENSE`) | https://github.com/copy/v86 |
| `v86/seabios.bin` | SeaBIOS | из репозитория v86 | LGPL-3.0 | https://www.seabios.org |
| `v86/vgabios.bin` | Bochs/LGPL VGA BIOS | из репозитория v86 | LGPL-2.1 | https://github.com/copy/v86/tree/master/bios |
| `webamp/` | Webamp | 2.3.1 | MIT | https://github.com/captbaritone/webamp |

Любой движок можно заменить своей версией: положите файлы с тем же путём в
`.minecraft/vintagepc/engines/` — локальная папка имеет приоритет над встроенной.
