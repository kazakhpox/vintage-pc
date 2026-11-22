VPC.register({
  id: 'notepad', name: 'Блокнот', icon: '📝',
  open() {
    const w = VPC.window({ title: 'Блокнот', icon: '📝', width: 460, height: 340 });
    const ta = VPC.el('textarea', { value: VPC.store.get('notepad', ''), style: 'width:100%;height:100%;box-sizing:border-box;resize:none;font-family:monospace' });
    ta.oninput = () => VPC.store.set('notepad', ta.value);
    w.body.style.margin = '0'; w.body.append(ta);
  },
});
