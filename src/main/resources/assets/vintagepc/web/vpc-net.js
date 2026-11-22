/*
 * VPCNet — сеть Vintage PC. Подключается к моду по ws://<этот же хост>/lan (cookie-токен),
 * поэтому работает в оболочке и в любой игре-картридже, открытой с того же локального сервера.
 *
 * Одна модель для всего: каналы — виртуальные сегменты Ethernet и «комнаты», а сервер Minecraft —
 * обучаемый L2-коммутатор. В какую физическую сеть попадут каналы, решают кабели в мире:
 * мод привязывает их к сети роутера, к которому подключён этот ПК (VPCNet.link).
 *
 *   VPCNet.room(name)                 — JSON-сообщения всем (чат, лобби, простые JS-игры)
 *   VPCNet.eth(net, onFrame)          — сырые кадры (v86 / NE2000)
 *   VPCNet.udp(net, port, onPacket)   — UDP/IPv4 поверх того же Ethernet (WASM-порты Quake/Xash и т.п.)
 *   VPCNet.humbleNet(net)             — сетевой бэкенд js-dos 8 (IPX DOSBox) поверх Ethernet
 */
(function () {
  const enc = new TextEncoder(), dec = new TextDecoder();
  const subs = new Map(), status = new Set(), linkFns = new Set();
  const pending = [];
  let ws = null, player = 'offline', online = false, link = { up: false, label: '', pcs: 0, via: '', wifi: { card: false, target: '', towers: [] } };
  const q = new URLSearchParams(location.search);

  function connect() {
    ws = new WebSocket(`ws://${location.host}/lan?pc=${encodeURIComponent(api.pc)}`);
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => { subs.forEach((_, ch) => ws.send(JSON.stringify({ op: 'join', ch }))); pending.splice(0).forEach(m => ws.send(m)); };
    ws.onmessage = (ev) => {
      if (typeof ev.data === 'string') {
        const m = JSON.parse(ev.data);
        if (m.op === 'link') { link = { up: m.up, label: m.label, pcs: m.pcs, via: m.via, wifi: m.wifi }; linkFns.forEach(f => f(link)); return; }
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

  const safe = (s) => String(s).replace(/[^A-Za-z0-9_.-]/g, '_').slice(0, 40) || 'default';
  const fnv = (s) => { let h = 2166136261; for (const c of s) h = Math.imul(h ^ c.charCodeAt(0), 16777619); return h >>> 0; };
  const BCAST = new Uint8Array([255, 255, 255, 255, 255, 255]);
  const sameMac = (a, o, b) => { for (let i = 0; i < 6; i++) if (a[o + i] !== b[i]) return false; return true; };
  const ipStr = (n) => [24, 16, 8, 0].map(s => (n >>> s) & 255).join('.');
  const ipNum = (s) => s.split('.').reduce((a, x) => ((a << 8) | +x) >>> 0, 0);
  const macForIp = (ip) => new Uint8Array([2, 0x56, 0x50, (ip >>> 16) & 255, (ip >>> 8) & 255, ip & 255]);

  function frame(dst, src, type, payload) {
    const f = new Uint8Array(14 + payload.length);
    f.set(dst, 0); f.set(src, 6); f[12] = type >> 8; f[13] = type & 255; f.set(payload, 14);
    return f;
  }

  const api = {
    get player() { return player; },
    get online() { return online; },
    pc: q.get('pc') || 'pc',
    net: q.get('net'),
    onStatus(f) { status.add(f); f({ player, online }); },
    /** Физическое подключение ПК: { up, label (имя роутера / "crossover"), pcs, via: "cable"|"wifi"|"", wifi: {card, target, towers} }. */
    get link() { return link; },
    onLink(f) { linkFns.add(f); f(link); return () => linkFns.delete(f); },

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
    /** Подключиться к вышке Wi-Fi (id из link.wifi.towers) или отключиться (''). */
    wifiConnect(id) { api.control({ op: 'wifi', tower: id || '' }); },
    control(obj) { const m = JSON.stringify(obj); if (ws && ws.readyState === 1) ws.send(m); else if (pending.length < 20) pending.push(m); },

    room(name) {
      const ch = 'room:' + safe(name), fns = new Set();
      const off = api.join(ch, (d, sender) => { let m; try { m = JSON.parse(dec.decode(d)); } catch { return; } fns.forEach(f => f(m, sender)); });
      return { send: (obj) => api.send(ch, JSON.stringify(obj)), on: (f) => fns.add(f), close: off };
    },

    eth(net, onFrame) {
      const ch = 'eth:' + safe(net);
      const off = api.join(ch, (d) => onFrame(d));
      return { send: (f) => api.send(ch, f), close: off };
    },

    /** Стабильный виртуальный IP 10.98.x.y для этого игрока и ПК. */
    virtualIp() {
      const h = fnv(player + '/' + api.pc);
      return ((10 << 24) | (98 << 16) | (((h >>> 8) & 255) << 8) | (1 + (h & 255) % 254)) >>> 0;
    },

    /**
     * UDP поверх виртуального Ethernet: настоящие заголовки IPv4/UDP, MAC вычисляется из IP,
     * отвечаем на ARP — так что и Windows внутри v86 видит такой «компьютер».
     */
    udp(net, port, onPacket) {
      const me = api.virtualIp(), myMac = macForIp(me);
      const link = api.eth(net, (f) => {
        const type = (f[12] << 8) | f[13];
        if (type === 0x0806 && f.length >= 42 && ((f[20] << 8) | f[21]) === 1) {          // ARP-запрос «кто 10.98.x.y?»
          const v = new DataView(f.buffer, f.byteOffset);
          if (v.getUint32(38) !== me) return;
          const r = new Uint8Array(28), rv = new DataView(r.buffer);
          rv.setUint16(0, 1); rv.setUint16(2, 0x0800); r[4] = 6; r[5] = 4; rv.setUint16(6, 2);
          r.set(myMac, 8); rv.setUint32(14, me); r.set(f.subarray(22, 28), 18); r.set(f.subarray(28, 32), 24);
          link.send(frame(f.subarray(6, 12), myMac, 0x0806, r));
          return;
        }
        if (type !== 0x0800 || f.length < 42 || f[23] !== 17) return;
        const v = new DataView(f.buffer, f.byteOffset);
        const ihl = (f[14] & 15) * 4, dst = v.getUint32(30), u = 14 + ihl;
        if (dst !== me && dst !== 0xFFFFFFFF && (dst & 0xFF) !== 0xFF) return;
        const dport = v.getUint16(u + 2);
        if (port && dport !== port) return;
        const len = v.getUint16(u + 4);
        onPacket({ srcIp: ipStr(v.getUint32(26)), srcPort: v.getUint16(u), dstPort: dport, data: f.slice(u + 8, u + len) });
      });
      return {
        ip: ipStr(me),
        sendto(ip, dport, data, sport = port || dport) {
          const dst = typeof ip === 'number' ? ip : ipNum(ip);
          const ipH = new Uint8Array(20 + 8 + data.length), v = new DataView(ipH.buffer);
          v.setUint8(0, 0x45); v.setUint16(2, ipH.length); v.setUint8(8, 64); v.setUint8(9, 17);
          v.setUint32(12, me); v.setUint32(16, dst);
          let sum = 0; for (let i = 0; i < 20; i += 2) sum += v.getUint16(i);
          while (sum >> 16) sum = (sum & 0xFFFF) + (sum >> 16);
          v.setUint16(10, ~sum & 0xFFFF);
          v.setUint16(20, sport); v.setUint16(22, dport); v.setUint16(24, 8 + data.length);
          ipH.set(data, 28);
          const bcast = dst === 0xFFFFFFFF || (dst & 0xFF) === 0xFF;
          return link.send(frame(bcast ? BCAST : macForIp(dst), myMac, 0x0800, ipH));
        },
        close: link.close,
      };
    },

    /**
     * Сетевой бэкенд для js-dos 8 (интерфейс sharedNet / HumbleNet): peerId ↔ MAC 02:4A:xx:xx:xx:xx,
     * кадры EtherType 0x88B5 (данные) и 0x88B6 (поиск хоста по имени). DOSBox гоняет через него IPX.
     */
    humbleNet(net, salt = '') {
      const peerId = (fnv(player + '/' + api.pc + '/' + salt) & 0x7FFFFFFF) || 1;
      const mac = (id) => new Uint8Array([2, 0x4A, (id >>> 24) & 255, (id >>> 16) & 255, (id >>> 8) & 255, id & 255]);
      const myMac = mac(peerId), queue = [], aliases = new Set(), pending = new Map();
      const link = api.eth(net, (f) => {
        const type = (f[12] << 8) | f[13], v = new DataView(f.buffer, f.byteOffset);
        if (f[6] !== 2 || f[7] !== 0x4A) return;
        const from = v.getUint32(8);
        if (type === 0x88B5 && sameMac(f, 0, myMac)) { queue.push({ peerId: from, data: f.slice(14) }); n.connected.add(from); }
        if (type === 0x88B6) {
          const m = JSON.parse(dec.decode(f.subarray(14)));
          if (m.q && aliases.has(m.q)) link.send(frame(f.subarray(6, 12), myMac, 0x88B6, enc.encode(JSON.stringify({ a: m.q }))));
          if (m.a && pending.has(m.a)) pending.get(m.a).push({ alias: m.a, peerId: from });
        }
      });
      const n = {
        peerId, connected: new Set(),
        wait() {},
        sendBinary(data, to) {
          if (to === peerId || to === 0) throw new Error('Cannot send to self');
          n.connected.add(to);
          return link.send(frame(mac(to), myMac, 0x88B5, new Uint8Array(data))) ? data.byteLength : 0;
        },
        recvBinary() { return queue.shift() || null; },
        disconnect(id) { n.connected.delete(id); },
        shutdown() { link.close(); },
        registerAlias(a) { aliases.add(a); return Promise.resolve(); },
        unregisterAlias(a) { aliases.delete(a); },
        queryAliases(qs) {
          const name = qs.replace(/^=/, '');
          if (aliases.has(name)) return Promise.resolve([{ alias: name, peerId }]);
          pending.set(name, []);
          link.send(frame(BCAST, myMac, 0x88B6, enc.encode(JSON.stringify({ q: name }))));
          return new Promise(ok => setTimeout(() => { const r = pending.get(name) || []; pending.delete(name); ok(r); }, 700));
        },
      };
      return n;
    },
  };

  window.VPCNet = api;
  // Ошибки страницы — в logs/latest.log игры (помогает разбирать «белый экран»)
  const report = (msg) => { try { api.control({ op: 'log', msg: String(msg).slice(0, 500), page: location.pathname }); } catch {} };
  addEventListener('error', (e) => report(e.message + ' @ ' + (e.filename || '') + ':' + (e.lineno || '')));
  addEventListener('unhandledrejection', (e) => report('promise: ' + (e.reason && e.reason.message || e.reason)));
  connect();
})();
