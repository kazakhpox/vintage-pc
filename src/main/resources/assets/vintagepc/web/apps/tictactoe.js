/* Пример сетевой игры поверх VPCNet.room — шаблон для своих LAN-игр. */
VPC.register({
  id: 'ttt', name: 'Крестики-нолики (LAN)', icon: '❌',
  open() {
    const net = VPC.netName, room = VPCNet.room('ttt-' + net), me = VPCNet.player + '/' + VPC.pcId;
    const w = VPC.window({ title: 'Крестики-нолики', icon: '❌', width: 260, height: 330, onClose: () => { room.close(); stopHost(); clearInterval(lobbyTimer); } });
    const status = VPC.el('p'), lobby = VPC.el('div'), board = VPC.el('div', { className: 'ttt' });
    let game = null, hostTimer = null; // game = { id, x, o, cells, turn }
    const host = VPC.el('button', { textContent: 'Создать игру', onclick: () => {
      stopHost(); game = { id: me, x: me, o: null, cells: Array(9).fill(''), turn: 'X' };
      VPC.presence.hosting.push('Крестики-нолики');
      hostTimer = setInterval(() => !game.o && room.send({ t: 'open', id: me }), 1500);
      room.send({ t: 'open', id: me }); render();
    } });
    function stopHost() { clearInterval(hostTimer); const i = VPC.presence.hosting.indexOf('Крестики-нолики'); if (i >= 0) VPC.presence.hosting.splice(i, 1); }
    const open = new Map();
    room.on((m) => {
      if (m.t === 'open' && m.id !== me) { open.set(m.id, Date.now()); renderLobby(); }
      if (m.t === 'join' && game && m.id === game.id && !game.o) { game.o = m.who; room.send({ t: 'start', id: game.id, o: m.who }); stopHost(); render(); }
      if (m.t === 'start' && m.o === me) { game = { id: m.id, x: m.id, o: me, cells: Array(9).fill(''), turn: 'X' }; render(); }
      if (m.t === 'move' && game && m.id === game.id) { apply(m.i); }
    });
    function renderLobby() {
      lobby.replaceChildren(...[...open].filter(([, t]) => Date.now() - t < 5000).map(([id]) =>
        VPC.el('button', { textContent: `Играть с ${id.split('/')[0]}`, onclick: () => room.send({ t: 'join', id, who: me }) })));
    }
    const mySide = () => game && (game.x === me ? 'X' : game.o === me ? 'O' : null);
    function apply(i) {
      if (game.cells[i] || winner()) return;
      game.cells[i] = game.turn; game.turn = game.turn === 'X' ? 'O' : 'X'; render();
    }
    function winner() {
      const c = game.cells, L = [[0,1,2],[3,4,5],[6,7,8],[0,3,6],[1,4,7],[2,5,8],[0,4,8],[2,4,6]];
      for (const [a, b, d] of L) if (c[a] && c[a] === c[b] && c[a] === c[d]) return c[a];
      return c.every(Boolean) ? 'ничья' : null;
    }
    function render() {
      board.replaceChildren(...(game ? game.cells : Array(9).fill('')).map((v, i) => VPC.el('button', { textContent: v, onclick: () => {
        if (!game || !game.o || game.turn !== mySide()) return;
        room.send({ t: 'move', id: game.id, i }); apply(i);
      } })));
      const win = game && winner();
      status.textContent = !game ? 'Создайте игру или подключитесь:' : !game.o ? 'Ждём соперника в сети…'
        : win ? `Итог: ${win}` : game.turn === mySide() ? `Ваш ход (${mySide()})` : 'Ход соперника';
    }
    const lobbyTimer = setInterval(renderLobby, 2000);
    w.body.append(host, status, lobby, board);
    render();
  },
});
