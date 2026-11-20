import React, { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { NavLink, Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { api, auth } from './api.js';
import Login from './pages/Login.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Products from './pages/Products.jsx';
import Stock from './pages/Stock.jsx';
import Import from './pages/Import.jsx';
import Alerts from './pages/Alerts.jsx';
import Reports from './pages/Reports.jsx';
import Billing from './pages/Billing.jsx';
import Staff from './pages/Staff.jsx';
import Admin from './pages/Admin.jsx';

const Ctx = createContext(null);
export const useApp = () => useContext(Ctx);

export default function App() {
  const [signedIn, setSignedIn] = useState(!!auth.get());
  const [me, setMe] = useState(null);
  const [shops, setShops] = useState([]);
  const [shopId, setShopId] = useState(localStorage.getItem('stocksafe.shop') || '');
  const nav = useNavigate();

  const load = useCallback(async () => {
    try {
      const [m, s] = await Promise.all([api('/me'), api('/shops')]);
      setMe(m); setShops(s);
      setShopId((cur) => (s.find((x) => x.id === cur) ? cur : s[0]?.id || ''));
    } catch { setSignedIn(false); auth.clear(); }
  }, []);

  useEffect(() => { if (signedIn) load(); }, [signedIn, load]);
  useEffect(() => { if (shopId) localStorage.setItem('stocksafe.shop', shopId); }, [shopId]);

  if (window.location.pathname.startsWith('/admin')) return <Admin />;
  if (!signedIn) return <Login onDone={() => { setSignedIn(true); nav('/'); }} />;
  if (!me) return <div className="center">Loading…</div>;

  const logout = async () => {
    const a = auth.get();
    try { await api('/auth/logout', { method: 'POST', body: { refreshToken: a.refreshToken } }); } catch { /* ignore */ }
    auth.clear(); setSignedIn(false); setMe(null);
  };
  const canManage = me.role === 'OWNER' || me.role === 'MANAGER';

  return (
    <Ctx.Provider value={{ me, shops, shopId, setShopId, reload: load, canManage }}>
      <div className="layout">
        <nav className="side">
          <h1>StockSafe</h1><small>Stop waste. Stop stockouts.</small>
          <NavLink to="/">Dashboard</NavLink>
          <NavLink to="/products">Products</NavLink>
          <NavLink to="/stock">Stock</NavLink>
          {canManage && <NavLink to="/import">Import</NavLink>}
          <NavLink to="/alerts">Alerts</NavLink>
          <NavLink to="/reports">Reports</NavLink>
          <NavLink to="/billing">Billing</NavLink>
          {me.role === 'OWNER' && <NavLink to="/staff">Staff</NavLink>}
          <a href="#logout" onClick={(e) => { e.preventDefault(); logout(); }}>Log out</a>
        </nav>
        <main className="main">
          <div className="bar">
            <strong>{me.tenantName}</strong>
            <span className={`tag ${me.plan === 'pro' ? 'ok' : ''}`}>{me.plan.toUpperCase()}</span>
            <span className="muted">{me.role}</span>
            {shops.length > 0 && (
              <select value={shopId} onChange={(e) => setShopId(e.target.value)}>
                {shops.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            )}
          </div>
          {shops.length === 0 ? <CreateShop /> : (
            <Routes>
              <Route path="/" element={<Dashboard />} />
              <Route path="/products" element={<Products />} />
              <Route path="/stock" element={<Stock />} />
              <Route path="/import" element={<Import />} />
              <Route path="/alerts" element={<Alerts />} />
              <Route path="/reports" element={<Reports />} />
              <Route path="/billing" element={<Billing />} />
              <Route path="/staff" element={<Staff />} />
              <Route path="*" element={<Navigate to="/" />} />
            </Routes>
          )}
        </main>
      </div>
    </Ctx.Provider>
  );
}

function CreateShop() {
  const { me, reload } = useApp();
  const [name, setName] = useState('');
  const [template, setTemplate] = useState('MEDICAL');
  const [err, setErr] = useState('');
  if (me.role !== 'OWNER') return <div className="card">No shop yet. Ask the owner to create one.</div>;
  const go = async () => {
    try { await api('/shops', { method: 'POST', body: { name, template } }); reload(); } catch (e) { setErr(e.message); }
  };
  return (
    <div className="card">
      <h2>Create your shop</h2>
      <div className="row">
        <input placeholder="Shop name" value={name} onChange={(e) => setName(e.target.value)} />
        <select value={template} onChange={(e) => setTemplate(e.target.value)}>
          <option value="MEDICAL">Medical store (expiry required)</option>
          <option value="GROCERY">Grocery (expiry required)</option>
          <option value="HARDWARE">Hardware</option>
          <option value="CUSTOM">Custom</option>
        </select>
        <button disabled={!name} onClick={go}>Create</button>
      </div>
      {err && <div className="err">{err}</div>}
    </div>
  );
}
