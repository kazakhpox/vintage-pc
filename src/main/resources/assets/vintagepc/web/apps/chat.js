/* Сетевой чат в духе WinPopup / NetMeeting Chat. */
VPC.register({
  id: 'chat', name: 'WinPopup', icon: '💬',
  open() {
    const net = VPC.netName, room = VPCNet.room('chat-' + net);
    const w = VPC.window({ title: `WinPopup — ${net}`, icon: '💬', width: 380, height: 300, onClose: () => room.close() });
    const log = VPC.el('div', { className: 'log sunken-panel' });
    const input = VPC.el('input', { style: 'flex:1' });
    const add = (who, text) => { log.append(VPC.el('div', { textContent: `<${who}> ${text}` })); log.scrollTop = 1e9; };
    const send = () => { const text = input.value.trim().slice(0, 500); if (!text) return; room.send({ text }); add(VPCNet.player, text); input.value = ''; };
    input.onkeydown = (e) => e.key === 'Enter' && send();
    room.on((m, sender) => typeof m.text === 'string' && add(sender, m.text));
    w.body.append(log, VPC.el('div', { className: 'field-row', style: 'margin-top:4px' }, input, VPC.el('button', { textContent: 'Отправить', onclick: send })));
    add('*', `Вы в сети «${net}». Сообщения видят все ПК этой рабочей группы.`);
  },
});
