/*
 * VPCNet — сеть Vintage PC. Работает и в оболочке, и в любой игре, открытой из неё
 * (оболочка передаёт ?ws=PORT&token=...&net=ИМЯ_СЕТИ в URL).
 *
 *   VPCNet.join('room:lobby', (data, sender) => ...)   // data: Uint8Array
 *   VPCNet.send('room:lobby', bytes | string)
 *   const r = VPCNet.room('chat');  r.on(obj => ...); r.send({text:'hi'})   // JSON-обёртка
 *   VPCNet.eth(netName, frame => ...) → { send(frame) }                    // сырые Ethernet-кадры
 *   VPCNet.udp(roomName) → виртуальный UDP с IP 10.98.x.y для WASM-портов игр
 *
 * Все пакеты идут: страница → ws://127.0.0.1 (мод) → пакеты Minecraft → сервер → другие игроки.
 */
(function () {
  const q = new URLSearchParams(location.search);
  const enc = new TextEncoder(), dec = new TextDecoder();
  const subs = new Map();            // канал → Set(callback)
  const status = new Set();
  let ws = null, player = q.get('player') || 'offline', online = false;

  function connect() {
    if (!q.get('ws')) return;
    ws = new WebSocket(`ws://127.0.0.1:${q.get('ws')}/lan?token=${q.get('token')}`);
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => subs.forEach((_, ch) => ws.send(JSON.stringify({ op: 'join', ch })));
    ws.onmessage = (ev) => {
      if (typeof ev.data === 'string') {
        const m = JSON.parse(ev.data);
        if (m.player) player = m.player;
        if ('online' in m) online = m.online;
        if ('value' in m) online = m.value;
        status.forEach(f => f({ player, online }));
        return;
      }
      const b = new Uint8Array(ev.data);
      const cl = b[0], ch = dec.decode(b.subarray(1, 1 + cl));
      const sl = b[1 + cl], sender = dec.decode(b.subarray(2 + cl, 2 + cl + sl));
      const data = b.slice(2 + cl + sl);
      (subs.get(ch) || []).forEach(f => { try { f(data, sender); } catch (e) { console.error(e); } });
    };
    ws.onclose = () => { online = false; status.forEach(f => f({ player, online })); setTimeout(connect, 2000); };
  }

  const api = {
    get player() { return player; },
    get online() { return online; },
    get available() { return !!q.get('ws'); },
    params: () => `ws=${q.get('ws') || ''}&token=${q.get('token') || ''}`,
    onStatus(f) { status.add(f); f({ player, online }); },
    join(ch, f) {
      if (!subs.has(ch)) {
        subs.set(ch, new Set());
        if (ws && ws.readyState === 1) ws.send(JSON.stringify({ op: 'join', ch }));
      }
      subs.get(ch).add(f);
      return () => api.leave(ch, f);
    },
    leave(ch, f) {
      const s = subs.get(ch); if (!s) return;
      s.delete(f);
      if (!s.size) { subs.delete(ch); if (ws && ws.readyState === 1) ws.send(JSON.stringify({ op: 'leave', ch })); }
    },
    send(ch, data) {
      if (!ws || ws.readyState !== 1) return false;
      const c = enc.encode(ch), d = typeof data === 'string' ? enc.encode(data) : new Uint8Array(data);
      const out = new Uint8Array(1 + c.length + d.length);
      out[0] = c.length; out.set(c, 1); out.set(d, 1 + c.length);
      ws.send(out);
      return true;
    },
    room(name) {
      const ch = 'room:' + safe(name), fns = new Set();
      const off = api.join(ch, (d, sender) => { let m; try { m = JSON.parse(dec.decode(d)); } catch { return; } fns.forEach(f => f(m, sender)); });
      return { send: (obj) => api.send(ch, JSON.stringify(obj)), on: (f) => fns.add(f), close: off };
    },
    eth(net, onFrame) {
      const ch = 'eth:' + safe(net);
      const off = api.join(ch, (d) => onFrame(d));
      return { send: (frame) => api.send(ch, frame), close: off };
    },
    /* Виртуальный UDP: заголовок [srcIP4][srcPort2][dstIP4][dstPort2] + данные. dstIP 255.255.255.255 — broadcast. */
    udp(name, onPacket) {
      const ch = 'room:udp-' + safe(name), me = api.virtualIp();
      const off = api.join(ch, (d) => {
        const v = new DataView(d.buffer, d.byteOffset);
        const dst = v.getUint32(6);
        if (dst !== me && dst !== 0xFFFFFFFF) return;
        onPacket({ srcIp: ipStr(v.getUint32(0)), srcPort: v.getUint16(4), dstPort: v.getUint16(10), data: d.subarray(12) });
      });
      return {
        ip: ipStr(me),
        sendto(ip, port, data, srcPort = port) {
          const p = new Uint8Array(12 + data.length), v = new DataView(p.buffer);
          v.setUint32(0, me); v.setUint16(4, srcPort); v.setUint32(6, ipNum(ip)); v.setUint16(10, port);
          p.set(data, 12);
          return api.send(ch, p);
        },
        close: off,
      };
    },
    virtualIp() { // 10.98.x.y — стабильный адрес по нику игрока
      let h = 2166136261; for (const c of player) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
      return ((10 << 24) | (98 << 16) | ((h >>> 8) & 0xFF) << 8 | (1 + (h & 0xFF) % 254)) >>> 0;
    },
  };
  function safe(s) { return String(s).replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 40) || 'default'; }
  function ipStr(n) { return [24, 16, 8, 0].map(s => (n >>> s) & 255).join('.'); }
  function ipNum(s) { return s.split('.').reduce((a, x) => ((a << 8) | +x) >>> 0, 0); }

  window.VPCNet = api;
  connect();
})();
