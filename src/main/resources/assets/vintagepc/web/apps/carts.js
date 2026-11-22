/*
 * Картриджи: игры-папки из .minecraft/vintagepc/cartridges/<id>/cartridge.json.
 * Оболочка получает только список манифестов; файлы игры стримятся при запуске.
 *   engine "jsdos" → /web/player-jsdos.html   (DOS-игры, IPX по сети)
 *   engine "v86"   → /web/player-v86.html     (Windows 9x/DOS на эмуляторе x86, NE2000 в сети)
 *   engine "html"  → /carts/<id>/<entry>      (WASM-порты: reVC, Xash3D, ioquake3...)
 */
VPC.pending.push(fetch('/api/carts').then(r => r.json()).then(list => {
  for (const c of list) {
    const icon = !c.icon ? (c.engine === 'v86' ? '🖥️' : c.engine === 'jsdos' ? '💾' : '🎮') : c.icon.includes('.') ? '🎮' : c.icon;
    VPC.register({
      id: 'cart-' + c.id, name: c.name, icon,
      open() {
        if (c.error) return VPC.alert(c.name, c.error);
        const net = encodeURIComponent(VPC.netName), pc = encodeURIComponent(VPC.pcId);
        const src = c.engine === 'jsdos' ? `/web/player-jsdos.html?cart=${c.id}&net=${net}&pc=${pc}`
          : c.engine === 'v86' ? `/web/player-v86.html?cart=${c.id}&net=${net}&pc=${pc}`
          : `/carts/${c.id}/${(c.html && c.html.entry) || 'index.html'}?net=${net}&pc=${pc}`;
        const w = VPC.window({ title: c.name, icon, width: c.width || 680, height: c.height || 540,
          onClose: () => { const i = VPC.presence.hosting.indexOf(c.name); if (i >= 0) VPC.presence.hosting.splice(i, 1); } });
        if (c.net && c.net.mode && c.net.mode !== 'none') VPC.presence.hosting.push(c.name);
        VPC.frame(w, src);
      },
    });
  }
}).catch(e => console.warn('Картриджи не загружены', e)));
