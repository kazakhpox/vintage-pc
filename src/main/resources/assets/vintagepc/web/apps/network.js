/* «Сетевое окружение»: выбор рабочей группы и список компьютеров других игроков в ней. */
VPC.presence = (() => {
  const peers = new Map(), listeners = new Set();
  let room = null, net = null;
  function rejoin() {
    if (room) room.close();
    net = VPC.netName;
    peers.clear();
    room = VPCNet.room('lobby-' + net);
    room.on((m, sender) => {
      if (m.t !== 'hello') return;
      peers.set(sender + '/' + m.pc, { player: sender, pc: m.pc, ip: m.ip, apps: m.apps || [], seen: Date.now() });
      if (m.ask) say();
      listeners.forEach(f => f());
    });
    say(true);
  }
  function say(ask = false) { room && room.send({ t: 'hello', pc: VPC.pcId, ip: VPCNet.udp ? ipOf() : '', apps: VPC.presence.hosting, ask }); }
  function ipOf() { const n = VPCNet.virtualIp(); return [24, 16, 8, 0].map(s => (n >>> s) & 255).join('.'); }
  setInterval(() => {
    if (VPC.netName !== net) rejoin(); else say();
    for (const [k, p] of peers) if (Date.now() - p.seen > 10000) peers.delete(k);
    listeners.forEach(f => f());
  }, 3000);
  VPCNet.onStatus(({ online }) => { if (online) setTimeout(rejoin, 300); });
  return { peers, hosting: [], onChange: (f) => { listeners.add(f); return () => listeners.delete(f); }, rejoin };
})();

VPC.register({
  id: 'network', name: 'Сетевое окружение', icon: '🖧',
  open() {
    const w = VPC.window({ title: 'Сетевое окружение', icon: '🖧', width: 420, height: 320, onClose: () => off() });
    const input = VPC.el('input', { value: VPC.netName, style: 'width:140px' });
    const save = VPC.el('button', { textContent: 'Применить', onclick: () => { VPC.netName = input.value.replace(/[^A-Za-z0-9_.-]/g, '_') || 'home'; input.value = VPC.netName; VPC.presence.rejoin(); render(); } });
    const me = VPC.el('p');
    const list = VPC.el('div', { className: 'sunken-panel', style: 'height:170px;background:#fff;overflow:auto' });
    w.body.append(VPC.el('div', { className: 'field-row' }, VPC.el('label', { textContent: 'Рабочая группа:' }), input, save), me, list);
    function render() {
      me.textContent = VPCNet.online
        ? `Вы: ${VPCNet.player} (${VPC.presence.peers.size} ПК в сети «${VPC.netName}»). Сеть идёт через сервер Minecraft.`
        : 'Нет связи с сервером Minecraft.';
      const t = VPC.el('table', { style: 'width:100%' }, VPC.el('tr', {}, ...['Компьютер', 'Игрок', 'IP', 'Хостит'].map(h => VPC.el('th', { textContent: h }))));
      for (const p of VPC.presence.peers.values())
        t.append(VPC.el('tr', {}, ...[`🖥️ PC-${p.pc}`, p.player, p.ip, p.apps.join(', ')].map(x => VPC.el('td', { textContent: x }))));
      list.replaceChildren(t);
    }
    const off = VPC.presence.onChange(render);
    render();
  },
});
