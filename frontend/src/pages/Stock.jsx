import React, { useEffect, useState } from 'react';
import { api } from '../api.js';
import { useApp } from '../App.jsx';

export default function Stock() {
  const { shopId, canManage } = useApp();
  const [products, setProducts] = useState([]);
  const [pid, setPid] = useState('');
  const [batches, setBatches] = useState([]);
  const [rcv, setRcv] = useState({ batchNo: '', expiryDate: '', cost: '', mrp: '', qty: '' });
  const [sell, setSell] = useState({ qty: '1' });
  const [msg, setMsg] = useState({});

  useEffect(() => { api(`/shops/${shopId}/products?size=100`).then((p) => { setProducts(p); setPid((c) => c || p[0]?.id || ''); }); }, [shopId]);
  const loadBatches = () => pid && api(`/shops/${shopId}/products/${pid}/batches`).then(setBatches);
  useEffect(() => { loadBatches(); }, [pid, shopId]); // eslint-disable-line

  const run = async (fn, ok) => {
    setMsg({});
    try { const r = await fn(); setMsg({ ok: typeof ok === 'function' ? ok(r) : ok }); loadBatches(); } catch (e) { setMsg({ err: e.message }); }
  };
  const receive = (e) => { e.preventDefault(); run(() => api(`/shops/${shopId}/stock/receive`, { method: 'POST', body: {
    productId: pid, batchNo: rcv.batchNo, expiryDate: rcv.expiryDate || null, cost: Number(rcv.cost), mrp: Number(rcv.mrp), qty: Number(rcv.qty) } }), 'Stock received'); };
  const doSell = (e) => { e.preventDefault(); run(() => api(`/shops/${shopId}/stock/sell`, { method: 'POST', body: { productId: pid, qty: Number(sell.qty) } }),
    (r) => 'Sold from: ' + r.map((a) => `${a.batchNo} ×${a.qty}`).join(', ')); };
  const adjust = (batchId) => {
    const d = prompt('Change quantity by (use a minus sign to remove, e.g. -2):'); if (!d) return;
    const reason = prompt('Reason (damaged, counted, returned…):'); if (!reason) return;
    run(() => api(`/shops/${shopId}/stock/adjust`, { method: 'POST', body: { batchId, delta: Number(d), reason } }), 'Adjusted');
  };

  const today = new Date().toISOString().slice(0, 10);
  return (
    <>
      <div className="card"><div className="row"><strong>Product</strong>
        <select value={pid} onChange={(e) => setPid(e.target.value)}>{products.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select></div>
        {msg.err && <div className="err">{msg.err}</div>}{msg.ok && <div className="ok-msg">{msg.ok}</div>}</div>
      <form className="card" onSubmit={doSell}><h3>Sell</h3>
        <div className="muted">The batch that expires first is used first (FEFO).</div>
        <div className="row"><input type="number" min="1" style={{ width: 100 }} value={sell.qty} onChange={(e) => setSell({ qty: e.target.value })} /><button disabled={!pid}>Sell</button></div></form>
      {canManage && (
        <form className="card" onSubmit={receive}><h3>Receive stock</h3>
          <div className="row">
            <input required placeholder="Batch no" value={rcv.batchNo} onChange={(e) => setRcv({ ...rcv, batchNo: e.target.value })} />
            <input type="date" value={rcv.expiryDate} onChange={(e) => setRcv({ ...rcv, expiryDate: e.target.value })} />
            <input required type="number" min="0" step="0.01" style={{ width: 90 }} placeholder="Cost" value={rcv.cost} onChange={(e) => setRcv({ ...rcv, cost: e.target.value })} />
            <input required type="number" min="0" step="0.01" style={{ width: 90 }} placeholder="MRP" value={rcv.mrp} onChange={(e) => setRcv({ ...rcv, mrp: e.target.value })} />
            <input required type="number" min="1" style={{ width: 90 }} placeholder="Qty" value={rcv.qty} onChange={(e) => setRcv({ ...rcv, qty: e.target.value })} />
            <button disabled={!pid}>Receive</button></div></form>)}
      <div className="card"><h3>Batches</h3><div style={{ overflowX: 'auto' }}><table>
        <thead><tr><th>Batch</th><th>Expiry</th><th>Cost</th><th>MRP</th><th>In stock</th><th /></tr></thead>
        <tbody>{batches.map((b) => (
          <tr key={b.id}><td>{b.batch_no}</td>
            <td>{b.expiry_date || '—'} {b.expiry_date && b.expiry_date < today && <span className="tag bad">expired</span>}</td>
            <td>{b.cost}</td><td>{b.mrp}</td><td>{b.qty}</td>
            <td>{canManage && <button className="ghost" onClick={() => adjust(b.id)}>Adjust</button>}</td></tr>))}</tbody></table></div>
        {batches.length === 0 && <div className="muted">No batches yet.</div>}</div>
    </>
  );
}
