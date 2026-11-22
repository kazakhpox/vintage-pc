/*
 * Сетевой мост для Websockets Doom (Cloudflare doom-wasm / Chocolate Doom).
 *
 * Движок открывает WebSocket к «ретранслятору» и шлёт пакеты [кому u32 LE][от кого u32 LE][данные],
 * а получает [от кого u32 LE][данные]. У сервера игры uid = 1, у клиентов случайный; «кому 0» — регистрация.
 * Вместо внешнего ретранслятора подменяем WebSocket: пакеты идут Ethernet-кадрами
 * (EtherType 0x88B7, MAC 02:44:4D:00:uid) через коммутатор на сервере Minecraft,
 * в отдельном сегменте на каждую партию — значит, unicast доходит только адресату.
 */
window.VPCDoomNet = {
  URL: 'ws://vpc-doom.invalid/',
  install(segment) {
    const RealWS = window.WebSocket, TYPE = 0x88B7;
    const mac = (uid) => new Uint8Array([2, 0x44, 0x4D, (uid >>> 16) & 255, (uid >>> 8) & 255, uid & 255]);
    class DoomSocket extends EventTarget {
      constructor(url) {
        super();
        this.url = url; this.readyState = 0; this.binaryType = 'arraybuffer'; this.protocol = ''; this.extensions = '';
        this.bufferedAmount = 0; this.uid = 0;
        this.link = VPCNet.eth(segment, (f) => {
          if (((f[12] << 8) | f[13]) !== TYPE || !this.uid) return;
          const me = mac(this.uid);
          for (let i = 0; i < 6; i++) if (f[i] !== me[i]) return;
          this._emit('message', { data: f.slice(14 + 4).buffer }); // отдаём [от кого][данные], без поля «кому»
        });
        setTimeout(() => { this.readyState = 1; this._emit('open', {}); }, 50);
      }
      send(data) {
        const d = new Uint8Array(data instanceof ArrayBuffer ? data : data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength));
        if (d.length < 8) return;
        const v = new DataView(d.buffer), to = v.getUint32(0, true), from = v.getUint32(4, true);
        if (from) this.uid = from;
        if (to === 0) return; // регистрация у ретранслятора — нам не нужна
        const f = new Uint8Array(14 + d.length);
        f.set(mac(to), 0); f.set(mac(from), 6); f[12] = TYPE >> 8; f[13] = TYPE & 255; f.set(d, 14);
        this.link.send(f);
      }
      close() { this.readyState = 3; this.link.close(); this._emit('close', { code: 1000, reason: '', wasClean: true }); }
      _emit(type, props) {
        const ev = Object.assign(new Event(type), props);
        this.dispatchEvent(ev);
        const h = this['on' + type]; if (h) h.call(this, ev);
      }
    }
    DoomSocket.CONNECTING = 0; DoomSocket.OPEN = 1; DoomSocket.CLOSING = 2; DoomSocket.CLOSED = 3;
    window.WebSocket = function (url, protocols) {
      return String(url).startsWith(VPCDoomNet.URL) ? new DoomSocket(url) : new RealWS(url, protocols);
    };
    Object.assign(window.WebSocket, { CONNECTING: 0, OPEN: 1, CLOSING: 2, CLOSED: 3 });
  },
};
