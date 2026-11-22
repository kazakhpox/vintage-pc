VPC.register({
  id: 'mines', name: 'Сапёр', icon: '💣',
  open() {
    const N = 9, M = 10;
    const w = VPC.window({ title: 'Сапёр', icon: '💣', width: 204, height: 260 });
    const info = VPC.el('div', { className: 'status-bar-field' });
    const grid = VPC.el('div', { className: 'mines', style: `grid-template-columns:repeat(${N},20px)` });
    const reset = VPC.el('button', { textContent: '🙂', onclick: start });
    w.body.append(VPC.el('div', { style: 'display:flex;gap:6px;margin-bottom:4px' }, reset, info), grid);
    let cells, over, left;
    function start() {
      grid.innerHTML = ''; over = false; left = N * N - M; reset.textContent = '🙂'; info.textContent = `Мин: ${M}`;
      cells = [...Array(N * N)].map((_, i) => ({ i, mine: false, open: false, flag: false, b: VPC.el('button') }));
      [...cells].sort(() => Math.random() - .5).slice(0, M).forEach(c => c.mine = true);
      cells.forEach(c => {
        c.b.onclick = () => reveal(c);
        c.b.oncontextmenu = (e) => { e.preventDefault(); if (!c.open && !over) { c.flag = !c.flag; c.b.textContent = c.flag ? '🚩' : ''; } };
        grid.append(c.b);
      });
    }
    const nb = (c) => { const x = c.i % N, y = (c.i / N) | 0, r = []; for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) { const nx = x + dx, ny = y + dy; if ((dx || dy) && nx >= 0 && ny >= 0 && nx < N && ny < N) r.push(cells[ny * N + nx]); } return r; };
    const colors = ['', 'blue', 'green', 'red', 'navy', 'maroon', 'teal', 'black', 'gray'];
    function reveal(c) {
      if (over || c.open || c.flag) return;
      c.open = true; c.b.classList.add('open');
      if (c.mine) { over = true; c.b.textContent = '💥'; reset.textContent = '😵'; cells.filter(x => x.mine).forEach(x => x.b.textContent ||= '💣'); return; }
      const n = nb(c).filter(x => x.mine).length;
      c.b.textContent = n || ''; c.b.style.color = colors[n];
      if (--left === 0) { over = true; reset.textContent = '😎'; }
      if (!n) nb(c).forEach(reveal);
    }
    start();
  },
});
