import React, { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { useApp } from '../App.jsx';

export default function Alerts() {
  const { shopId, canManage, me } = useApp();
  const [alerts, setAlerts] = useState([]);
  const [rules, setRules] = useState([]);
  const [notes, setNotes] = useState([]);
  const [forecast, setForecast] = useState(null);
  const [fErr, setFErr] = useState('');

  const load = useCallback(async () => {
    setAlerts(await api(`/alerts?shopId=${shopId}`));
    setRules(await api('/alert-rules'));
    setNotes(await api('/notifications'));
    try { setForecast(await api(`/forecast?shopId=${shopId}`)); setFErr(''); } catch (e) { setForecast(null); setFErr(e.message); }
  }, [shopId]);
  useEffect(() => { load().catch(() => {}); }, [load]);

  const resolve = async (id) => { await api(`/alerts/${id}/resolve`, { method: 'POST' }); load(); };
  const toggle = async (r) => { await api(`/alert-rules/${r.id}`, { method: 'PUT', body: { type: r.type, enabled: !r.enabled } }); load(); };
  const days = async (r) => { const d = prompt('Alert when expiry is within how many days?', r.threshold_days); if (d) { await api(`/alert-rules/${r.id}`, { method: 'PUT', body: { type: r.type, thresholdDays: Number(d) } }); load(); } };
  const retry = async (id) => { await api(`/notifications/${id}/retry`, { method: 'POST' }); load(); };

  return (
    <>
      <div className="card"><h3>Open alerts</h3>
        {alerts.length === 0 ? <div className="muted">No open alerts.</div> : alerts.map((a) => (
          <div className="row" key={a.id}><span className={`tag ${a.type === 'LOW_STOCK' ? 'warn' : 'bad'}`}>{a.type.replace('_', ' ')}</span>
            <span style={{ flex: 1 }}>{a.message}</span><button className="ghost" onClick={() => resolve(a.id)}>Dismiss</button></div>))}</div>
      <div className="card"><h3>Reorder suggestions {me.plan !== 'pro' && <span className="tag">Pro</span>}</h3>
        {fErr ? <div className="muted">{fErr}</div> : forecast?.length === 0 ? <div className="muted">Not enough sales history yet.</div> :
          <table><thead><tr><th>Product</th><th>Sells/day</th><th>In stock</th><th>Days left</th><th>Order</th></tr></thead>
            <tbody>{forecast?.map((f) => <tr key={f.product_id}><td>{f.product_name}</td><td>{Number(f.avg_daily).toFixed(1)}</td><td>{f.stock}</td><td>{f.days_of_cover}</td><td><strong>{f.suggested_qty}</strong></td></tr>)}</tbody></table>}</div>
      <div className="card"><h3>Alert rules</h3>
        {rules.map((r) => (
          <div className="row" key={r.id}><span>{r.type === 'NEAR_EXPIRY' ? `Expiry within ${r.threshold_days} days` : 'Low stock (at reorder level)'}</span>
            <span className={`tag ${r.enabled ? 'ok' : ''}`}>{r.enabled ? 'on' : 'off'}</span>
            {canManage && <><button className="ghost" onClick={() => toggle(r)}>{r.enabled ? 'Turn off' : 'Turn on'}</button>
              {r.type === 'NEAR_EXPIRY' && <button className="ghost" onClick={() => days(r)}>Change days</button>}</>}</div>))}</div>
      <div className="card"><h3>Messages sent</h3>
        <table><thead><tr><th>When</th><th>Channel</th><th>Message</th><th>Status</th><th /></tr></thead>
          <tbody>{notes.slice(0, 15).map((n) => (
            <tr key={n.id}><td>{new Date(n.created_at).toLocaleString()}</td><td>{n.channel}</td><td>{n.subject}</td>
              <td><span className={`tag ${n.status === 'SENT' ? 'ok' : n.status === 'FAILED' ? 'bad' : 'warn'}`}>{n.status}</span></td>
              <td>{n.status === 'FAILED' && canManage && <button className="ghost" onClick={() => retry(n.id)}>Retry</button>}</td></tr>))}</tbody></table></div>
    </>
  );
}
