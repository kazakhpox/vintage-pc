/* Winamp 2.9 (Webamp, встроен в мод). Музыка — из .minecraft/vintagepc/music/ (mp3/ogg/wav/flac). */
VPC.register({
  id: 'winamp', name: 'Winamp', icon: '⚡',
  async open() {
    if (VPC.webamp) return;
    try { if (!window.Webamp) await VPC.loadScript('/engines/webamp/webamp.bundle.min.js'); }
    catch { return VPC.alert('Winamp', 'Движок Webamp не найден.'); }
    const files = await fetch('/api/music').then(r => r.json()).catch(() => []);
    if (!files.length) VPC.alert('Winamp', 'Положите mp3 в .minecraft/vintagepc/music/ — они появятся в плейлисте.');
    const tracks = files.map(f => ({ url: '/music/' + f.split('/').map(encodeURIComponent).join('/'), metaData: { title: f.replace(/\.[^.]+$/, '').split('/').pop(), artist: '' } }));
    const host = VPC.el('div', { style: 'position:absolute;left:0;top:0;z-index:99999' });
    document.body.append(host);
    VPC.webamp = new Webamp({ initialTracks: tracks });
    VPC.webamp.onClose(() => { VPC.webamp.dispose(); host.remove(); VPC.webamp = null; });
    await VPC.webamp.renderWhenReady(host);
  },
});
