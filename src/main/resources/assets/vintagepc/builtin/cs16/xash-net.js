/*
 * Сеть Xash3D FWGS (webxash3d-fwgs 1.2) поверх Vintage PC.
 * Основано на классе Net из npm-пакета xash3d-fwgs (MIT): движок вызывает socket/bind/sendto/recvfrom
 * через Module.net, а мы гоним UDP-датаграммы настоящими IPv4/UDP-кадрами по виртуальному Ethernet
 * (VPCNet.udp): у каждого ПК стабильный IP 10.98.x.y, broadcast 255.255.255.255 — поиск LAN-серверов.
 *
 * Сборка движка не экспортирует кучу и помощники Emscripten, поэтому XashNet.hook(mod) ставит
 * Module.instantiateWasm, сам создаёт экземпляр xash.wasm и берёт из его экспортов memory/malloc.
 */
window.XashMem = class XashMem {
  constructor() { this.exports = null; this._buf = null; }
  views() {
    const b = this.exports.memory.buffer;
    if (b !== this._buf) {
      this._buf = b;
      this.HEAP8 = new Int8Array(b); this.HEAPU8 = new Uint8Array(b); this.HEAP16 = new Int16Array(b);
      this.HEAP32 = new Int32Array(b); this.HEAPU32 = new Uint32Array(b);
    }
    return this;
  }
  setValue(ptr, v) { this.views().HEAP32[ptr >> 2] = v; }
  errnoPtr() { const f = this.exports.getErrnoLocation; return f ? f() : 0; }
  _malloc(n) { return this.exports.malloc(n); }
  AsciiToString(ptr) { const h = this.views().HEAPU8; let s = ''; while (h[ptr]) s += String.fromCharCode(h[ptr++]); return s; }
  intArrayFromString(str, noNull) { const a = Array.from(new TextEncoder().encode(str)); if (!noNull) a.push(0); return a; }
  writeArrayToMemory(arr, ptr) { this.views().HEAP8.set(arr, ptr); }
  writeSockaddr(sa, family, addr, port, addrlenPtr) {
    const v = this.views(); v.HEAPU8.fill(0, sa, sa + 16);
    v.HEAP16[sa >> 1] = family; v.HEAPU8[sa + 2] = (port >> 8) & 255; v.HEAPU8[sa + 3] = port & 255;
    String(addr).split('.').forEach((x, i) => v.HEAPU8[sa + 4 + i] = +x & 255);
    if (addrlenPtr) v.HEAP32[addrlenPtr >> 2] = 16;
    return 0;
  }
  readSockaddr(sa) {
    const h = this.views().HEAPU8;
    return { family: h[sa] | (h[sa + 1] << 8), addr: [h[sa + 4], h[sa + 5], h[sa + 6], h[sa + 7]].join('.'), port: (h[sa + 2] << 8) | h[sa + 3] };
  }
};
window.XashNet = class XashNet {
  constructor(segment) {
    this.sockets = new Map();
    this.lastSocketID = 1000;
    this.incoming = [];
    this.udp = VPCNet.udp(segment, 0, (p) => {           // 0 — принимаем на любой порт
      if (this.incoming.length > 512) this.incoming.shift();
      this.incoming.push({ data: p.data, ip: p.srcIp.split('.').map(Number), port: p.srcPort });
    });
    this.ip = this.udp.ip;
  }
  /** Подключить к параметрам модуля Emscripten: перехват создания экземпляра, чтобы получить память. */
  hook(mod, wasmUrl) {
    const mem = this.em = new XashMem();
    mod.instantiateWasm = (imports, receive) => {
      WebAssembly.instantiateStreaming(fetch(wasmUrl), imports).then(({ instance, module }) => {
        mem.exports = instance.exports;
        receive(instance, module);
      }).catch((e) => { console.error('xash.wasm', e); mod.onAbort?.(e); });
      return {};
    };
    mod.net = this;
  }
  errno(code) { const ptr = this.em.errnoPtr(); if (ptr) this.em.setValue(ptr, code); }
  readSockaddr(ptr) {
    const h = this.em.views().HEAPU8;
    return [[h[ptr + 4], h[ptr + 5], h[ptr + 6], h[ptr + 7]], (h[ptr + 2] << 8) | h[ptr + 3]];
  }
  boundPort(fd) { const s = this.sockets.get(fd); return (s && s.addr && s.addr.port) || 27005; }
  recvfrom(fd, bufPtr, bufLen, flags, sockaddrPtr, socklenPtr) {
    const packet = this.incoming.shift();
    if (!packet) { this.errno(73); return -1; }       // EWOULDBLOCK
    const em = this.em.views(), u8 = packet.data, n = Math.min(bufLen, u8.length);
    if (n > 0) em.HEAPU8.set(u8.subarray(0, n), bufPtr);
    if (sockaddrPtr) {
      em.HEAP16[sockaddrPtr >> 1] = 2;
      em.HEAP8[sockaddrPtr + 2] = (packet.port >> 8) & 0xff;
      em.HEAP8[sockaddrPtr + 3] = packet.port & 0xff;
      for (let i = 0; i < 4; i++) em.HEAP8[sockaddrPtr + 4 + i] = packet.ip[i];
    }
    if (socklenPtr) em.HEAP32[socklenPtr >> 2] = 16;
    return n;
  }
  sendto(fd, bufPtr, bufLen, flags, sockaddrPtr) {
    const [ip, port] = this.readSockaddr(sockaddrPtr);
    this.udp.sendto(ip.join('.'), port, this.em.views().HEAPU8.slice(bufPtr, bufPtr + bufLen), this.boundPort(fd));
    return bufLen;
  }
  sendtoBatch(fd, bufsPtr, lensPtr, count, flags, sockaddrPtr) {
    const [ip, port] = this.readSockaddr(sockaddrPtr), v = this.em.views(), h32 = v.HEAP32;
    let total = 0;
    for (let i = 0; i < count; i++) {
      const size = h32[(lensPtr >> 2) + i], p = h32[(bufsPtr >> 2) + i];
      this.udp.sendto(ip.join('.'), port, v.HEAPU8.slice(p, p + size), this.boundPort(fd));
      total += size;
    }
    return total;
  }
  socket(family, type, protocol) { const id = this.lastSocketID++; this.sockets.set(id, { id, family, type, protocol }); return id; }
  gethostbyname() { return 0; }
  gethostname(namePtr) { this.em.writeArrayToMemory(this.em.intArrayFromString(this.ip, true), namePtr); return 0; }
  getsockname(fd, sockaddrPtr, socklenPtr) {
    const s = this.sockets.get(fd); if (!s) return -1;
    this.em.writeSockaddr(sockaddrPtr, s.family, this.ip, s.addr?.port ?? 0, socklenPtr);
    return 0;
  }
  bind(fd, sockaddrPtr, socklenPtr) {
    const s = this.sockets.get(fd); if (!s) return -1;
    s.addr = this.em.readSockaddr(sockaddrPtr, socklenPtr);
    return 0;
  }
  closesocket(fd) { return this.sockets.delete(fd) ? 0 : -1; }
  /** Имя → адрес: принимаем IP-литералы (10.98.x.y) и наш собственный хост. */
  getaddrinfo(hostnamePtr, restrictPtr, hintsPtr, addrinfoPtr) {
    const host = this.em.AsciiToString(hostnamePtr);
    const ip = /^\d+\.\d+\.\d+\.\d+$/.test(host) ? host : this.ip;
    const sa = this.em._malloc(16);
    this.em.writeSockaddr(sa, 2, ip, 0);
    const ai = this.em._malloc(32), v = this.em.views();
    v.HEAP32[(ai + 4) >> 2] = 2; v.HEAP32[(ai + 8) >> 2] = 2; v.HEAP32[(ai + 12) >> 2] = 17;
    v.HEAPU32[(ai + 24) >> 2] = 0; v.HEAPU32[(ai + 20) >> 2] = sa; v.HEAP32[(ai + 16) >> 2] = 16;
    v.HEAPU32[(ai + 28) >> 2] = 0;
    v.HEAPU32[addrinfoPtr >> 2] = ai;
    return 0;
  }
  freeaddrinfo() {}
};
