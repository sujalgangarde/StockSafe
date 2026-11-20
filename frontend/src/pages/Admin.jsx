import React, { useState } from 'react';

const BASE = import.meta.env.VITE_API_BASE || '';

// Platform admin (the SaaS owner). Open /admin. The key is the PLATFORM_ADMIN_KEY of the server.
export default function Admin() {
  const [key, setKey] = useState('');
  const [tenants, setTenants] = useState(null);
  const [health, setHealth] = useState(null);
  const [err, setErr] = useState('');

  const load = async () => {
    setErr('');
    try {
      const h = { 'X-Admin-Key': key };
      const [t, hl] = await Promise.all([fetch(`${BASE}/api/v1/admin/tenants`, { headers: h }), fetch(`${BASE}/api/v1/admin/health`, { headers: h })]);
      if (!t.ok) throw new Error('Wrong key or admin is switched off');
      setTenants(await t.json()); setHealth(await hl.json());
    } catch (e) { setErr(e.message); }
  };

  return (
    <div className="main" style={{ margin: '0 auto' }}>
      <h2>StockSafe platform admin</h2>
      <div className="row"><input type="password" placeholder="Admin key" value={key} onChange={(e) => setKey(e.target.value)} /><button onClick={load}>Load</button></div>
      {err && <div className="err">{err}</div>}
      {health && <div className="grid">
        <div className="card"><div className="muted">Database</div><div className="stat">{health.database}</div></div>
        <div className="card"><div className="muted">Events waiting to send</div><div className="stat">{health.outboxPending}</div></div>
        <div className="card"><div className="muted">Oldest waiting (seconds)</div><div className="stat">{health.oldestPendingSeconds}</div></div>
        <div className="card"><div className="muted">Tenants</div><div className="stat">{tenants?.length}</div></div></div>}
      {tenants && <div className="card" style={{ overflowX: 'auto' }}><table>
        <thead><tr><th>Business</th><th>Plan</th><th>Subscription</th><th>Shops</th><th>Products</th><th>Joined</th></tr></thead>
        <tbody>{tenants.map((t) => <tr key={t.id}><td>{t.name}</td><td><span className={`tag ${t.plan_code === 'pro' ? 'ok' : ''}`}>{t.plan_code}</span></td>
          <td>{t.subscription_status || '—'}</td><td>{t.shops_used}</td><td>{t.products_used}</td><td>{new Date(t.created_at).toLocaleDateString()}</td></tr>)}</tbody></table></div>}
    </div>
  );
}
