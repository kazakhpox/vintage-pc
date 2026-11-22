/* «Беспроводная сеть»: список вышек Wi-Fi измерения и подключение к ним (нужна Wi-Fi-карта в корпусе). */
VPC.register({
  id: 'wifi', name: 'Беспроводная сеть', icon: '📶',
  open() {
    const w = VPC.window({ title: 'Беспроводные сети', icon: '📶', width: 360, height: 300, onClose: () => off() });
    const info = VPC.el('p'), list = VPC.el('div', { className: 'sunken-panel', style: 'height:170px;background:#fff;overflow:auto' });
    w.body.append(info, list);
    function render() {
      const l = VPCNet.link, wf = l.wifi || { card: false, towers: [] };
      if (!wf.card) { info.textContent = 'Беспроводной адаптер не найден. Вставьте Wi-Fi-карту в корпус компьютера.'; list.replaceChildren(); return; }
      info.textContent = l.via === 'cable' ? 'Компьютер подключён кабелем — проводная сеть имеет приоритет.'
        : l.via === 'wifi' ? `Подключено: ${l.label} · компьютеров в сети: ${l.pcs}` : 'Выберите сеть:';
      const rows = wf.towers.map(t => {
        const bars = t.distance < 200 ? '▂▄▆█' : t.distance < 1000 ? '▂▄▆_' : t.distance < 5000 ? '▂▄__' : '▂___';
        const on = wf.target === t.id;
        return VPC.el('div', { style: 'display:flex;gap:8px;align-items:center;padding:4px;border-bottom:1px solid #ddd' },
          VPC.el('span', { textContent: '📶 ' + bars, style: 'font-family:monospace' }),
          VPC.el('span', { textContent: `${t.name} (${t.distance} м)`, style: 'flex:1' + (on ? ';font-weight:bold' : '') }),
          VPC.el('button', { textContent: on ? 'Отключить' : 'Подключить', onclick: () => VPCNet.wifiConnect(on ? '' : t.id) }));
      });
      list.replaceChildren(...(rows.length ? rows : [VPC.el('p', { textContent: 'Сетей не найдено. Постройте вышку Wi-Fi: основание, 4+ секции мачты и антенна сверху.', style: 'padding:6px' })]));
    }
    const off = VPCNet.onLink(render);
  },
});
