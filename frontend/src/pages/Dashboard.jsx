import React, { useCallback, useEffect, useRef, useState } from 'react';
import { api, subscribe } from '../api.js';
import { useApp } from '../App.jsx';

export default function Dashboard() {
  const { shopId, me } = useApp();
  const [near, setNear] = useState([]);
  const [low, setLow] = useState([]);
  const [alerts, setAlerts] = useState([]);
  const [feed, setFeed] = useState([]);
  const [live, setLive] = useState(false);
  const timer = useRef(null);

  const load = useCallback(async () => {
    const [n, l, a] = await Promise.all([
      api(`/shops/${shopId}/reports/near-expiry?days=30`),
      api(`/shops/${shopId}/reports/low-stock`),
      api(`/alerts?shopId=${shopId}`).catch(() => []),
    ]);
    setNear(n); setLow(l); setAlerts(a);
  }, [shopId]);

  useEffect(() => { load().catch(() => {}); }, [load]);

  useEffect(() => {
    const stop = subscribe(shopId, (name, data) => {
      setFeed((f) => [{ name, data, at: new Date().toLocaleTimeString() }, ...f].slice(0, 8));
      clearTimeout(timer.current);                       // refresh lists shortly after a burst of events
      timer.current = setTimeout(() => load().catch(() => {}), 500);
    }, setLive);
    return stop;
  }, [shopId, load]);

  const expired = near.filter((r) => r.expired).length;
  return (
    <>
      <div className="grid">
        <div className="card"><div className="muted">Expiring in 30 days</div><div className="stat">{near.length}</div>
          {expired > 0 && <span className="tag bad">{expired} already expired</span>}</div>
        <div className="card"><div className="muted">Low stock</div><div className="stat">{low.length}</div></div>
        <div className="card"><div className="muted">Open alerts</div><div className="stat">{alerts.length}</div></div>
        <div className="card"><div className="muted">Products used</div>
          <div className="stat">{me.usage.products.used}<span className="muted"> / {String(me.usage.products.limit)}</span></div></div>
      </div>
      <div className="card">
        <h3><span className={`live ${live ? '' : 'off'}`} />Live updates</h3>
        {feed.length === 0 ? <div className="muted">Waiting for activity…</div> : feed.map((e, i) => (
          <div key={i} className="muted">{e.at} · {label(e)}</div>
        ))}
      </div>
      <div className="card">
        <h3>Use these first (nearest expiry)</h3>
        <Table rows={near.slice(0, 8)} empty="Nothing is close to expiry." cols={[['name', 'Product'], ['batch_no', 'Batch'], ['expiry_date', 'Expiry'], ['qty', 'Qty']]} />
      </div>
      <div className="card">
        <h3>Running low</h3>
        <Table rows={low.slice(0, 8)} empty="All stocked up." cols={[['name', 'Product'], ['qty', 'In stock'], ['reorder_level', 'Reorder at']]} />
      </div>
    </>
  );
}

function label(e) {
  if (e.name === 'alert.created') return `New alert: ${e.data.message}`;
  if (e.name === 'alert.resolved') return 'An alert was resolved';
  if (e.name === 'stock.changed') return `Stock ${e.data.type.toLowerCase()} recorded`;
  if (e.name === 'import.progress') return `Import ${e.data.processed}/${e.data.total} rows`;
  return e.name;
}

export function Table({ rows, cols, empty }) {
  if (!rows.length) return <div className="muted">{empty}</div>;
  return (
    <div style={{ overflowX: 'auto' }}>
      <table>
        <thead><tr>{cols.map(([, h]) => <th key={h}>{h}</th>)}</tr></thead>
        <tbody>{rows.map((r, i) => <tr key={i}>{cols.map(([k]) => <td key={k}>{String(r[k] ?? '')}</td>)}</tr>)}</tbody>
      </table>
    </div>
  );
}
