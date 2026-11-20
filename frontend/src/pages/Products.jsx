import React, { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { useApp } from '../App.jsx';

export default function Products() {
  const { shopId, canManage, reload } = useApp();
  const [q, setQ] = useState('');
  const [rows, setRows] = useState([]);
  const [f, setF] = useState({ name: '', barcode: '', unit: 'pcs', gstRate: '12', reorderLevel: '10', extra: '' });
  const [msg, setMsg] = useState({});

  const load = useCallback(() => api(`/shops/${shopId}/products?q=${encodeURIComponent(q)}`).then(setRows), [shopId, q]);
  useEffect(() => { const t = setTimeout(() => load().catch(() => {}), 250); return () => clearTimeout(t); }, [load]);

  const add = async (e) => {
    e.preventDefault(); setMsg({});
    try {
      // "extra" = custom fields typed as key=value, one per line
      const customAttributes = Object.fromEntries(f.extra.split('\n').map((l) => l.split('=')).filter((p) => p[0]?.trim() && p[1])
        .map(([k, ...v]) => [k.trim(), v.join('=').trim()]));
      await api(`/shops/${shopId}/products`, { method: 'POST', body: {
        name: f.name, barcode: f.barcode || null, unit: f.unit, gstRate: Number(f.gstRate),
        reorderLevel: Number(f.reorderLevel), customAttributes } });
      setF({ ...f, name: '', barcode: '', extra: '' }); setMsg({ ok: 'Product added' }); load(); reload();
    } catch (ex) { setMsg({ err: ex.message }); }
  };
  const del = async (id) => { if (confirm('Delete this product?')) { await api(`/shops/${shopId}/products/${id}`, { method: 'DELETE' }); load(); reload(); } };

  return (
    <>
      {canManage && (
        <form className="card" onSubmit={add}>
          <h3>Add product</h3>
          <div className="row">
            <input required placeholder="Name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
            <input placeholder="Barcode" value={f.barcode} onChange={(e) => setF({ ...f, barcode: e.target.value })} />
            <input style={{ width: 80 }} placeholder="Unit" value={f.unit} onChange={(e) => setF({ ...f, unit: e.target.value })} />
            <input style={{ width: 90 }} type="number" min="0" max="28" step="0.01" placeholder="GST %" value={f.gstRate} onChange={(e) => setF({ ...f, gstRate: e.target.value })} />
            <input style={{ width: 110 }} type="number" min="0" placeholder="Reorder at" value={f.reorderLevel} onChange={(e) => setF({ ...f, reorderLevel: e.target.value })} />
          </div>
          <textarea rows={2} placeholder={'Custom fields, one per line:\nmanufacturer=Cipla'} value={f.extra} onChange={(e) => setF({ ...f, extra: e.target.value })} style={{ width: '100%' }} />
          <div className="row"><button>Add</button></div>
          {msg.err && <div className="err">{msg.err}</div>}{msg.ok && <div className="ok-msg">{msg.ok}</div>}
        </form>
      )}
      <div className="card">
        <div className="row"><input placeholder="Search by name or barcode…" value={q} onChange={(e) => setQ(e.target.value)} style={{ flex: 1 }} /></div>
        <div style={{ overflowX: 'auto' }}><table>
          <thead><tr><th>Name</th><th>Barcode</th><th>Unit</th><th>GST</th><th>Reorder</th><th>Custom</th><th /></tr></thead>
          <tbody>{rows.map((p) => (
            <tr key={p.id}><td>{p.name}</td><td>{p.barcode}</td><td>{p.unit}</td><td>{p.gstRate}%</td><td>{p.reorderLevel}</td>
              <td className="muted">{Object.entries(p.customAttributes || {}).map(([k, v]) => `${k}: ${v}`).join(', ')}</td>
              <td>{canManage && <button className="ghost" onClick={() => del(p.id)}>Delete</button>}</td></tr>))}
          </tbody></table></div>
        {rows.length === 0 && <div className="muted">No products found.</div>}
      </div>
    </>
  );
}
