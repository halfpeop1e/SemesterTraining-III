import { Card } from 'antd';

export default function Legend() {
  const items = [
    { label: '占用区段', color: 'rgba(252,92,101,0.35)', border: 'rgba(252,92,101,0.6)' },
    { label: '空闲区段', color: 'rgba(148,163,184,0.08)', border: 'rgba(148,163,184,0.2)' },
    { label: '信号正常', color: '#06d6a0', border: '#06d6a0' },
    { label: '信号未接入', color: '#8c8c8c', border: '#64748b' },
    { label: '道岔定位', color: '#3b82f6', border: '#3b82f6' },
    { label: '道岔反位', color: '#f97316', border: '#f97316' },
    { label: 'MA 正常', color: '#06d6a0', border: '#06d6a0' },
    { label: 'MA 降级', color: '#fc5c65', border: '#fc5c65' },
  ];

  return (
    <Card size="small" style={{ position: 'absolute', right: 12, bottom: 12, width: 150, zIndex: 10, opacity: 0.95 }} bodyStyle={{ padding: '10px 12px' }}>
      <div className="text-xs font-semibold text-slate-300 mb-2">图例</div>
      <div className="space-y-1.5">
        {items.map((it) => (
          <div key={it.label} className="flex items-center gap-2">
            <span className="w-3 h-3 rounded-sm border" style={{ background: it.color, borderColor: it.border }} />
            <span className="text-[11px] text-slate-400">{it.label}</span>
          </div>
        ))}
      </div>
    </Card>
  );
}
