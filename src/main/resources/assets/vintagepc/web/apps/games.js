/*
 * Ярлыки WASM-игр из .minecraft/vintagepc/games/games.js (window.VPC_GAMES = [...]).
 * Каждая игра открывается в окне-iframe; ей передаются параметры сети:
 *   ?ws=PORT&token=...&net=<рабочая группа>&player=<ник>
 * Игра подключает ../../web/vpc-net.js и использует VPCNet.room / VPCNet.udp / VPCNet.eth.
 */
for (const g of (window.VPC_GAMES || [])) {
  VPC.register({
    id: 'game-' + g.name, name: g.name, icon: g.icon || '🎮',
    open() {
      const w = VPC.window({ title: g.name, icon: g.icon || '🎮', width: g.width || 660, height: g.height || 520 });
      const sep = g.url.includes('?') ? '&' : '?';
      const src = '../games/' + g.url + sep + VPCNet.params() + '&net=' + encodeURIComponent(VPC.netName) + '&player=' + encodeURIComponent(VPCNet.player);
      w.body.style.margin = '0';
      w.body.append(VPC.el('iframe', { src, allow: 'autoplay; fullscreen; gamepad; pointer-lock' }));
      if (g.lan) { VPC.presence.hosting.push(g.name); const close = w.close; w.close = () => { const i = VPC.presence.hosting.indexOf(g.name); if (i >= 0) VPC.presence.hosting.splice(i, 1); close(); }; }
    },
  });
}

/* Winamp (Webamp) — если игрок положил webamp.bundle.min.js и музыку в games/webamp/. */
VPC.register({
  id: 'winamp', name: 'Winamp', icon: '⚡',
  async open() {
    try { if (!window.Webamp) await VPC.loadScript('../games/webamp/webamp.bundle.min.js'); }
    catch { return VPC.alert('Winamp', 'Положите webamp.bundle.min.js (npm-пакет webamp) в .minecraft/vintagepc/games/webamp/'); }
    try { await VPC.loadScript('../games/webamp/playlist.js'); } catch {}
    const webamp = new Webamp({ initialTracks: window.WEBAMP_TRACKS || [] });
    webamp.renderWhenReady(document.getElementById('windows'));
  },
});
