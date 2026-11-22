/* Оболочка в стиле Windows 98: рабочий стол, окна, панель задач, меню «Пуск». */
(function () {
  const apps = [];
  let z = 10, wid = 0;
  const $ = (s) => document.querySelector(s);
  const el = (tag, props = {}, ...kids) => { const e = Object.assign(document.createElement(tag), props); kids.forEach(k => e.append(k)); return e; };
  const pcId = VPCNet.pc;

  const VPC = {
    el, pcId,
    /** Настройки этого компьютера (переживают перезапуск). */
    store: {
      get(k, d) { try { const v = localStorage.getItem(pcId + ':' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
      set(k, v) { try { localStorage.setItem(pcId + ':' + k, JSON.stringify(v)); } catch {} },
    },
    /** Локальное имя сегмента. Какие ПК его видят, определяют кабели и роутер в мире Minecraft. */
    netName: 'lan',

    register(app) { apps.push(app); },
    /** Асинхронные источники ярлыков (картриджи) — ждём их перед отрисовкой рабочего стола. */
    pending: [],

    window({ title, icon = '🗔', width = 420, height = 320, onClose }) {
      const id = ++wid;
      const body = el('div', { className: 'window-body' });
      const btnMin = el('button', { ariaLabel: 'Minimize' }), btnMax = el('button', { ariaLabel: 'Maximize' }), btnClose = el('button', { ariaLabel: 'Close' });
      const bar = el('div', { className: 'title-bar' }, el('div', { className: 'title-bar-text', textContent: `${icon} ${title}` }),
        el('div', { className: 'title-bar-controls' }, btnMin, btnMax, btnClose));
      const w = el('div', { className: 'window' }, bar, body);
      const off = 30 + (id % 8) * 24;
      Object.assign(w.style, { left: off + 'px', top: off + 'px', width: width + 'px', height: height + 'px' });
      $('#windows').append(w);
      const task = el('button', { textContent: `${icon} ${title}` });
      $('#tasks').append(task);

      const api = {
        body, el: w,
        focus() {
          document.querySelectorAll('#windows .window').forEach(x => x.classList.add('inactive'));
          document.querySelectorAll('#tasks button').forEach(x => x.classList.remove('active'));
          w.classList.remove('inactive'); w.hidden = false; w.style.zIndex = ++z; task.classList.add('active');
        },
        setTitle(t) { bar.firstChild.textContent = `${icon} ${t}`; task.textContent = `${icon} ${t}`; },
        close() { try { onClose && onClose(); } finally { w.remove(); task.remove(); } },
      };
      w.addEventListener('mousedown', api.focus);
      btnClose.onclick = (e) => { e.stopPropagation(); api.close(); };
      btnMin.onclick = (e) => { e.stopPropagation(); w.hidden = true; task.classList.remove('active'); };
      btnMax.onclick = () => w.classList.toggle('maximized');
      task.onclick = () => (w.hidden || w.style.zIndex != z) ? api.focus() : (w.hidden = true, task.classList.remove('active'));
      bar.addEventListener('dblclick', () => w.classList.toggle('maximized'));
      bar.addEventListener('mousedown', (e) => {
        if (e.target.closest('button') || w.classList.contains('maximized')) return;
        const sx = e.clientX - w.offsetLeft, sy = e.clientY - w.offsetTop;
        const iframes = w.querySelectorAll('iframe'); iframes.forEach(f => f.style.pointerEvents = 'none');
        const move = (ev) => { w.style.left = ev.clientX - sx + 'px'; w.style.top = Math.max(0, ev.clientY - sy) + 'px'; };
        const up = () => { removeEventListener('mousemove', move); removeEventListener('mouseup', up); iframes.forEach(f => f.style.pointerEvents = ''); };
        addEventListener('mousemove', move); addEventListener('mouseup', up);
      });
      w.style.resize = 'both'; w.style.overflow = 'hidden';
      api.focus();
      return api;
    },

    alert(title, text) {
      const w = VPC.window({ title, icon: 'ℹ️', width: 320, height: 140 });
      w.body.append(el('p', { textContent: text }), el('button', { textContent: 'OK', onclick: () => w.close() }));
    },

    /** Всплывающая подсказка у трея, как в Windows XP. */
    balloon(title, text) {
      const b = el('div', { className: 'balloon' }, el('b', { textContent: title }), el('div', { textContent: text }));
      document.body.append(b);
      setTimeout(() => b.remove(), 5000);
    },

    loadScript(src) {
      return new Promise((ok, fail) => document.head.append(el('script', { src, onload: ok, onerror: () => fail(new Error('Нет файла ' + src)) })));
    },

    frame(w, src) {
      w.body.style.margin = '0';
      const f = el('iframe', { src, allow: 'autoplay; fullscreen; gamepad; pointer-lock; cross-origin-isolated' });
      w.body.append(f);
      setTimeout(() => f.focus(), 100);
      return f;
    },

    async boot() {
      VPCNet.control({ op: 'boot', ua: navigator.userAgent });
      await Promise.allSettled(VPC.pending);
      const desk = $('#desktop'), menu = $('#startitems');
      for (const a of apps) {
        const ic = el('div', { className: 'icon', title: a.name }, el('span', { className: 'glyph', textContent: a.icon }), el('span', { textContent: a.name }));
        ic.onclick = () => { document.querySelectorAll('.icon').forEach(x => x.classList.remove('sel')); ic.classList.add('sel'); };
        ic.ondblclick = () => a.open();
        if (a.desktop !== false) desk.append(ic);
        menu.append(el('li', { onclick: () => { $('#startmenu').hidden = true; a.open(); } }, el('span', { textContent: a.icon }), el('span', { textContent: a.name })));
      }
      menu.append(el('hr'), el('li', { onclick: () => {
        $('#startmenu').hidden = true;
        const w = VPC.window({ title: 'Завершение работы Windows', icon: '⏻', width: 340, height: 150 });
        w.body.append(el('p', { textContent: 'Выключить компьютер? Все запущенные программы будут закрыты.' }),
          el('button', { textContent: 'Да', onclick: () => VPCNet.control({ op: 'shutdown', pc: pcId }) }), ' ',
          el('button', { textContent: 'Отмена', onclick: () => w.close() }));
      } }, el('span', { textContent: '⏻' }), el('span', { textContent: 'Завершение работы…' })));
      $('#startbtn').onclick = (e) => { e.stopPropagation(); $('#startmenu').hidden = !$('#startmenu').hidden; };
      document.addEventListener('mousedown', (e) => { if (!e.target.closest('#startmenu,#startbtn')) $('#startmenu').hidden = true; });
      const tick = () => $('#clock').textContent = new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
      tick(); setInterval(tick, 10000);
      let wasUp = null;
      const net = () => {
        const l = VPCNet.link, ok = VPCNet.online && l.up;
        $('#netled').textContent = !ok ? (l.wifi && l.wifi.card ? '📶❌' : '🖧❌') : l.via === 'wifi' ? '📶' : '🖧';
        $('#netled').classList.toggle('off', !ok);
        $('#netled').title = !VPCNet.online ? 'Нет связи с сервером' : !l.up ? 'Сетевой кабель не подключён'
          : `${l.via === 'wifi' ? 'Беспроводная сеть' : 'Подключение по локальной сети'}: ${l.label === 'crossover' ? 'прямой кабель' : l.label} · компьютеров: ${l.pcs}`;
        if (wasUp !== null && wasUp !== ok) VPC.balloon(ok ? (l.via === 'wifi' ? 'Беспроводная сеть подключена' : 'Подключение по локальной сети') : 'Сетевой кабель не подключён',
          ok ? `Сеть: ${l.label === 'crossover' ? 'прямой кабель' : l.label}` : 'Проверьте кабель и роутер.');
        wasUp = ok;
      };
      VPCNet.onStatus(net); VPCNet.onLink(net);
    },
  };
  /*
   * MCEF отправляет каждое нажатие мыши с clickCount = 1, поэтому Chromium никогда не генерирует dblclick.
   * Распознаём двойной щелчок сами: два клика в одно место за 450 мс → событие dblclick.
   * Если среда когда-нибудь начнёт присылать настоящий dblclick — отключаемся, чтобы не было двойных срабатываний.
   */
  let last = null, nativeDbl = false;
  document.addEventListener('dblclick', (e) => { if (e.isTrusted) nativeDbl = true; }, true);
  document.addEventListener('click', (e) => {
    if (nativeDbl || e.button !== 0) return;
    const now = performance.now();
    if (last && now - last.t < 450 && Math.abs(e.clientX - last.x) < 6 && Math.abs(e.clientY - last.y) < 6 && last.target === e.target) {
      last = null;
      e.target.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, clientX: e.clientX, clientY: e.clientY, view: window }));
    } else last = { t: now, x: e.clientX, y: e.clientY, target: e.target };
  }, true);

  window.VPC = VPC;
})();
