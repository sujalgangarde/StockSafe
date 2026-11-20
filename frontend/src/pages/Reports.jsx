import React, { useEffect, useState } from 'react';
import { api, download } from '../api.js';
import { useApp } from '../App.jsx';
import { Table } from './Dashboard.jsx';

const TYPES = {
  'near-expiry': { title: 'Near expiry', cols: [['name', 'Product'], ['batch_no', 'Batch'], ['expiry_date', 'Expiry'], ['qty', 'Qty']] },
  'low-stock': { title: 'Low stock', cols: [['name', 'Product'], ['qty', 'In stock'], ['reorder_level', 'Reorder at']] },
  'dead-stock': { title: 'Dead stock (no sales in 90 days)', cols: [['name', 'Product'], ['qty', 'Qty']] },
};

export default function Reports() {
  const { shopId } = useApp();
  const [type, setType] = useState('near-expiry');
  const [days, setDays] = useState(30);
  const [rows, setRows] = useState([]);
  useEffect(() => { api(`/shops/${shopId}/reports/${type}?days=${type === 'dead-stock' ? 90 : days}`).then(setRows); }, [shopId, type, days]);
  const t = TYPES[type];
  return (
    <div className="card"><h3>{t.title}</h3>
      <div className="row">
        <select value={type} onChange={(e) => setType(e.target.value)}>{Object.entries(TYPES).map(([k, v]) => <option key={k} value={k}>{v.title}</option>)}</select>
        {type === 'near-expiry' && <label className="muted">within <input type="number" min="1" style={{ width: 70 }} value={days} onChange={(e) => setDays(Number(e.target.value) || 30)} /> days</label>}
        <button className="ghost" onClick={() => download(`/shops/${shopId}/reports/${type}/export?format=csv&days=${days}`, `${type}.csv`)}>Download CSV</button>
        <button className="ghost" onClick={() => download(`/shops/${shopId}/reports/${type}/export?format=pdf&days=${days}`, `${type}.pdf`)}>Download PDF</button></div>
      <Table rows={rows} cols={t.cols} empty="Nothing to show." />
    </div>
  );
}
