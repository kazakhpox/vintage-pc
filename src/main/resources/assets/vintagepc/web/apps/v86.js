/*
 * Эмулятор x86 (v86) с настоящей Windows 95/98 или DOS. Сетевая карта NE2000 эмулятора
 * подключена к виртуальному свитчу на сервере Minecraft (канал eth:<рабочая группа>),
 * поэтому IPX-игры (Heroes III, Red Alert, Warcraft II, Doom через IPX...) и TCP/IP
 * между ПК разных игроков работают как в настоящей локалке.
 *
 * Файлы кладёт игрок (их нельзя распространять с модом):
 *   .minecraft/vintagepc/games/v86/libv86.js, v86.wasm, seabios.bin, vgabios.bin
 *   .minecraft/vintagepc/games/v86/config.js  — window.V86_CONFIG = { hda: {url:'win98.img'}, memory_size: 128*1024*1024, ... }
 * В гостевой ОС: драйвер NE2000 (ISA, порт 0x300, IRQ 10 по умолчанию в v86) + протокол IPX/SPX
 * и/или TCP/IP со статическими адресами (например 192.168.0.N/24).
 */
VPC.register({
  id: 'v86', name: 'Windows 98 (v86)', icon: '🖥️',
  async open() {
    const base = '../games/v86/';
    try {
      if (!window.V86) await VPC.loadScript(base + 'libv86.js');
      if (!window.V86_CONFIG) await VPC.loadScript(base + 'config.js');
    } catch (e) {
      return VPC.alert('v86', 'Положите libv86.js, v86.wasm, BIOS и config.js в .minecraft/vintagepc/games/v86/');
    }
    const net = VPC.netName;
    const w = VPC.window({ title: `v86 — сеть «${net}»`, icon: '🖥️', width: 660, height: 520, onClose: () => { link.close(); emu.destroy && emu.destroy(); } });
    const screen = VPC.el('div', {}, VPC.el('div', { style: 'white-space:pre;font:14px monospace;line-height:14px' }), VPC.el('canvas'));
    w.body.style.background = '#000'; w.body.append(screen);
    const cfg = Object.assign({
      wasm_path: base + 'v86.wasm',
      bios: { url: base + 'seabios.bin' },
      vga_bios: { url: base + 'vgabios.bin' },
      memory_size: 64 * 1024 * 1024,
      vga_memory_size: 8 * 1024 * 1024,
      autostart: true,
    }, window.V86_CONFIG, {
      screen_container: screen,
      // Сетевая карта есть, но вместо relay-сервера по WebSocket кадры гоняем сами через шину эмулятора.
      // Если ваша версия v86 не создаёт NE2000 без relay_url — укажите в config.js
      // net_device: { type: 'ne2k', relay_url: '' } (или network_relay_url) по документации своей версии.
      net_device: Object.assign({ type: 'ne2k' }, (window.V86_CONFIG || {}).net_device),
    });
    const emu = new V86(cfg);
    const link = VPCNet.eth(net, (frame) => emu.bus.send('net0-receive', frame));
    emu.add_listener('net0-send', (frame) => link.send(frame));
    VPC.presence.hosting.includes('v86') || VPC.presence.hosting.push('v86');
  },
});
