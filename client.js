window.__ModuleLoader__.load({
  id: '@hpwu616/dsh-remote-host',
  factory(require) {
    const React = require('react');
    const h = React.createElement;

    function PairingCard() {
      const [details, setDetails] = React.useState(null);
      const [expanded, setExpanded] = React.useState(true);
      const [showCode, setShowCode] = React.useState(false);
      const [changing, setChanging] = React.useState(false);
      const relayInput = React.useRef(null);
      const transport = window.__DSH_TRANSPORT__;
      const send = transport?.fetch ? transport.fetch.bind(transport) : fetch;
      React.useEffect(() => {
        let active = true;
        const refresh = () => send('api/dsh-remote-pair-code', { cache: 'no-store' })
          .then(response => {
            if (!response.ok) throw new Error('unavailable');
            return response.json();
          })
          .then(value => { if (active) setDetails(value); })
          .catch(() => { if (active) setDetails(null); });
        refresh();
        const timer = setInterval(refresh, 5000);
        return () => { active = false; clearInterval(timer); };
      }, []);

      const setPairing = () => {
        if (!details || changing) return;
        setChanging(true);
        send('api/dsh-remote-pairing', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ enabled: !details.enabled }),
        }).then(response => {
          if (!response.ok) throw new Error('unavailable');
          setDetails({ ...details, enabled: !details.enabled });
          setShowCode(false);
        }).catch(() => setDetails(null)).finally(() => setChanging(false));
      };
      const saveRelay = () => {
        if (changing) return;
        const signalUrl = relayInput.current?.value.trim() || '';
        setChanging(true);
        send('api/dsh-remote-relay', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ url: signalUrl }),
        }).then(response => {
          if (!response.ok) throw new Error('invalid relay URL');
          setDetails({ ...details, relayUrl: signalUrl,
            relayStatus: signalUrl ? 'connecting' : 'off' });
        }).catch(() => window.alert('远程信令地址无效或保存失败'))
          .finally(() => setChanging(false));
      };

      const buttonStyle = {
        border: '1px solid var(--dsw-alias-border-l1, #d8dce7)',
        borderRadius: 8, background: 'transparent', color: 'inherit',
        padding: '6px 10px', cursor: 'pointer', fontSize: 12,
      };
      return h('div', {
        style: {
          width: '100%', boxSizing: 'border-box',
          padding: expanded ? 16 : 12,
          background: 'var(--dsw-alias-bg-base, transparent)',
          border: '1px solid var(--dsw-alias-border-l1, currentColor)',
          borderRadius: 12,
          color: 'var(--dsw-alias-label-primary, inherit)',
          fontSize: 13,
        },
      },
      !expanded
        ? h('button', { type: 'button', style: buttonStyle,
          onClick: () => setExpanded(true), 'aria-label': '展开手机连接' }, '手机连接  ▾')
        : h(React.Fragment, null,
          h('div', { style: { display: 'flex', alignItems: 'center',
            justifyContent: 'space-between', marginBottom: 10 } },
          h('strong', null, '手机连接'),
          h('button', { type: 'button', style: buttonStyle,
            onClick: () => { setShowCode(false); setExpanded(false); },
            'aria-label': '收起手机连接' }, '收起')),
          h('div', { style: { display: 'flex', alignItems: 'center',
            justifyContent: 'space-between', marginBottom: 8 } },
          h('span', null, '允许新手机配对'),
          h('button', { type: 'button', style: buttonStyle, disabled: changing || !details,
            onClick: setPairing, 'aria-label': details?.enabled ? '关闭配对' : '开启配对' },
          details?.enabled ? '已开启' : '已关闭')),
          details?.enabled && h('div', { style: { fontSize: 11, opacity: 0.65,
            marginBottom: 5 } }, '一次性配对码'),
          details?.enabled &&
          h('div', { style: { display: 'flex', alignItems: 'center', gap: 8,
            justifyContent: 'space-between' } },
          h('strong', { style: { letterSpacing: 2, fontSize: 22, userSelect: 'text' } },
            details ? (showCode ? details.code : '••••••••') : '不可用'),
          h('button', { type: 'button', style: buttonStyle,
            onClick: () => setShowCode(!showCode),
            'aria-label': showCode ? '隐藏配对码' : '显示配对码' },
          showCode ? '隐藏' : '显示')),
          h('div', { style: { borderTop: '1px solid var(--dsw-alias-border-l1, #d8dce7)',
            marginTop: 12, paddingTop: 10, fontSize: 11, lineHeight: 1.7 } },
          h('div', { style: { opacity: 0.65, marginBottom: 3 } }, '电脑网络'),
          details?.host && h('div', { style: { overflowWrap: 'anywhere', marginBottom: 5,
            fontWeight: 600 } }, `电脑名称  ${details.host}`),
          details?.fingerprint && h('div', { style: { marginBottom: 6 } },
            h('div', null, `HTTPS 端口  ${details.port}`),
            h('div', { style: { fontWeight: 600, letterSpacing: 1,
              userSelect: 'text' } }, `电脑身份码  ${details.fingerprint.slice(0, 24)
              .match(/.{1,4}/g).join(' ')}`)),
          details?.networks?.length
            ? details.networks.map((network, index) => h('div', {
              key: `${network.address}-${index}`, style: { overflowWrap: 'anywhere' },
            },
            h('div', null, `IPv4  ${network.address}`),
            h('div', null, `网段  ${network.subnet}`),
            h('div', null, `HTTPS 地址  https://${network.address}:${details.port}`)))
            : h('div', null, '未检测到局域网 IPv4 地址')),
          h('div', { style: { marginTop: 14, paddingTop: 12,
            borderTop: '1px solid var(--dsw-alias-border-l1, #d8dce7)' } },
          h('div', { style: { fontWeight: 600, marginBottom: 7 } },
            '跨网络连接 · Cloudflare 加密中继'),
          h('div', { style: { opacity: 0.65, marginBottom: 6 } },
            `信令状态：${details?.relayStatus || '未配置'}`),
          h('div', { style: { display: 'flex', gap: 6 } },
            h('input', { type: 'url', key: details?.relayUrl || 'empty', ref: relayInput,
              defaultValue: details?.relayUrl || '',
              placeholder: 'wss://你的 Worker 地址/signal',
              style: { minWidth: 0, flex: 1, padding: '6px 8px',
                borderRadius: 8, border: '1px solid #d8dce7' } }),
            h('button', { type: 'button', style: buttonStyle,
              disabled: changing, onClick: saveRelay }, '保存')),
          details?.relayUrl && details?.hostId && h('div', {
            style: { overflowWrap: 'anywhere', userSelect: 'text', marginTop: 8 },
          }, `手机远程地址  relay://${new URL(details.relayUrl).host}/${details.hostId}`)),
          h('div', { style: { marginTop: 4, fontWeight: 600 } },
            details ? `服务端口  ${details.port}` : '服务端口  获取中'),
          h('div', { style: { fontSize: 11, opacity: 0.7, marginTop: 12,
            lineHeight: 1.6, overflowWrap: 'anywhere' } },
          details ? '局域网自动发现 · 局域网和 USB 使用同一端口'
            : '正在获取连接信息…')));
    }

    function PairingItem({ view }) {
      return view === 'summary'
        ? '配对手机、查看电脑地址与连接端口'
        : h(PairingCard);
    }

    return {
      inject: ['slots'],
      apply(ctx) {
        ctx.slots.inject('plugins.item', () => ctx.slots.register({
          name: 'plugins.item', id: 'dsh-remote-pairing',
          label: '手机连接',
        }, PairingItem));
      },
    };
  },
});
