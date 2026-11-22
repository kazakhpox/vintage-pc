/* «Сетевое окружение»: выбор рабочей группы и список компьютеров других игроков в ней. */
VPC.presence = (() => {
  const peers = new Map(), listeners = new Set();
  let room = null, net = null;
  function rejoin() {
    if (room) room.close();
    net = VPC.netName;
    peers.clear();
    listeners.forEach(f => f());
    room = VPCNet.room('lobby-' + net);
    room.on((m, sender) => {
      if (m.t !== 'hello') return;
      peers.set(sender + '/' + m.pc, { player: sender, pc: m.pc, ip: m.ip, apps: m.apps || [], seen: Date.now() });
      if (m.ask) say();
      listeners.forEach(f => f());
    });
    say(true);
  }
  function say(ask = false) { room && room.send({ t: 'hello', pc: VPC.pcId, ip: ipOf(), apps: VPC.presence.hosting, ask }); }
  function ipOf() { const n = VPCNet.virtualIp(); return [24, 16, 8, 0].map(s => (n >>> s) & 255).join('.'); }
  setInterval(() => {
    say();
    for (const [k, p] of peers) if (Date.now() - p.seen > 10000) peers.delete(k);
    listeners.forEach(f => f());
  }, 3000);
  VPCNet.onStatus(({ online }) => { if (online) setTimeout(rejoin, 300); });
  VPCNet.onLink((l) => setTimeout(rejoin, 300)); // воткнули кабель в другой роутер — другие соседи
  return { peers, hosting: [], onChange: (f) => { listeners.add(f); return () => listeners.delete(f); }, rejoin };
})();

VPC.register({
  id: 'network', name: 'Сетевое окружение', icon: '🖧',
  open() {
    const w = VPC.window({ title: 'Сетевое окружение', icon: '🖧', width: 440, height: 320, onClose: () => { off(); offLink(); } });
    const me = VPC.el('p'), conn = VPC.el('div', { className: 'status-bar-field', style: 'padding:4px;margin-bottom:6px' });
    const list = VPC.el('div', { className: 'sunken-panel', style: 'height:170px;background:#fff;overflow:auto' });
    w.body.append(conn, me, list);
    function render() {
      const l = VPCNet.link;
      conn.textContent = !VPCNet.online ? '⚠ Нет связи с сервером Minecraft.'
        : !l.up ? '❌ Сетевой кабель не подключён. Проложите кабель от компьютера к роутеру (или напрямую к одному другому ПК).'
        : `${l.via === 'wifi' ? '📶 Беспроводная сеть «' + l.label + '»' : l.label === 'crossover' ? '🖧 Прямое подключение кабелем' : '🖧 Сеть «' + l.label + '»'} · компьютеров в сети: ${l.pcs}`;
      me.textContent = l.up ? `Вы: ${VPCNet.player}. Включённые компьютеры в сети:` : '';
      const t = VPC.el('table', { style: 'width:100%' }, VPC.el('tr', {}, ...['Компьютер', 'Игрок', 'IP', 'Запущено'].map(h => VPC.el('th', { textContent: h }))));
      for (const p of VPC.presence.peers.values())
        t.append(VPC.el('tr', {}, ...[`🖥️ PC-${p.pc}`, p.player, p.ip, p.apps.join(', ')].map(x => VPC.el('td', { textContent: x }))));
      list.replaceChildren(t);
    }
    const off = VPC.presence.onChange(render), offLink = VPCNet.onLink(render);
    render();
  },
});
