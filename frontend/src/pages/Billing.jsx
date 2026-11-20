import React, { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';
import { useApp } from '../App.jsx';

export default function Billing() {
  const { me, reload } = useApp();
  const [b, setB] = useState(null);
  const [plans, setPlans] = useState([]);
  const [msg, setMsg] = useState({});
  const load = useCallback(async () => { setB(await api('/billing')); setPlans(await api('/billing/plans')); }, []);
  useEffect(() => { load().catch(() => {}); }, [load]);
  if (!b) return null;
  const owner = me.role === 'OWNER';

  const run = async (fn, ok) => { setMsg({}); try { const r = await fn(); setMsg({ ok }); await load(); reload(); return r; } catch (e) { setMsg({ err: e.message }); } };
  const upgrade = async () => {
    const r = await run(() => api('/billing/upgrade', { method: 'POST' }), 'Upgrade started.');
    if (r?.checkoutUrl) window.location.href = r.checkoutUrl;
  };
  const sim = (event) => run(() => api('/billing/dev/simulate', { method: 'POST', body: { event } }), `Simulated: ${event}`);
  const sub = b.subscription;

  return (
    <>
      <div className="grid">
        {plans.map((p) => (
          <div className="card" key={p.code} style={p.code === b.plan ? { borderColor: 'var(--teal)', borderWidth: 2 } : {}}>
            <h3>{p.name} {p.code === b.plan && <span className="tag ok">current</span>}</h3>
            <div className="stat">₹{p.price_inr_month}<span className="muted">/month</span></div>
            <ul className="muted"><li>{p.max_shops ?? 'Unlimited'} shop(s)</li><li>{p.max_products ?? 'Unlimited'} products</li>
              <li>{p.forecasting ? 'Reorder forecasting' : 'No forecasting'}</li><li>{p.whatsapp_alerts ? 'WhatsApp alerts' : 'Email alerts'}</li></ul>
            {owner && p.code === 'pro' && b.plan === 'free' && <button onClick={upgrade}>{b.trialAvailable ? 'Start 14-day free trial' : 'Upgrade to Pro'}</button>}
            {owner && p.code === 'free' && b.plan === 'pro' && <button className="ghost" onClick={() => confirm('Go back to Free?') && run(() => api('/billing/downgrade', { method: 'POST' }), 'Moved to Free.')}>Downgrade</button>}
          </div>))}
      </div>
      {msg.err && <div className="err">{msg.err}</div>}{msg.ok && <div className="ok-msg">{msg.ok}</div>}
      {sub && <div className="card"><h3>Subscription</h3>
        <p><span className={`tag ${sub.status === 'PAST_DUE' ? 'bad' : 'ok'}`}>{sub.status}</span></p>
        {sub.trial_ends_at && <p className="muted">Trial ends {new Date(sub.trial_ends_at).toLocaleDateString()}</p>}
        {sub.status === 'PAST_DUE' && <p className="err">Payment failed. Pro stays on until {new Date(sub.grace_ends_at).toLocaleDateString()}. Please update your payment method.</p>}
        {b.lastSaga && <p className="muted">Last upgrade: {b.lastSaga.state}{b.lastSaga.error ? ` (${b.lastSaga.error})` : ''}</p>}</div>}
      {b.mock && owner && <div className="card"><h3>Test mode</h3><div className="muted">Razorpay is not connected. Simulate what it would send:</div>
        <div className="row">{['subscription.authenticated', 'subscription.charged', 'payment.failed', 'subscription.cancelled'].map((e) => <button key={e} className="ghost" onClick={() => sim(e)}>{e}</button>)}</div></div>}
      <div className="card"><h3>Invoices</h3>
        {b.invoices.length === 0 ? <div className="muted">No invoices yet.</div> :
          <table><thead><tr><th>Date</th><th>Amount</th><th>Status</th></tr></thead>
            <tbody>{b.invoices.map((i, k) => <tr key={k}><td>{new Date(i.created_at).toLocaleDateString()}</td><td>₹{i.amount_inr}</td><td>{i.status}</td></tr>)}</tbody></table>}</div>
    </>
  );
}
