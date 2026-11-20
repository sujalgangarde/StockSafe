import React, { useState } from 'react';
import { api, auth } from '../api.js';

export default function Login({ onDone }) {
  const [mode, setMode] = useState('login');
  const [f, setF] = useState({ email: '', password: '', fullName: '', businessName: '' });
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault(); setErr(''); setBusy(true);
    try {
      const body = mode === 'login' ? { email: f.email, password: f.password } : f;
      auth.set(await api(`/auth/${mode}`, { method: 'POST', body }));
      onDone();
    } catch (ex) { setErr(ex.message); } finally { setBusy(false); }
  };

  return (
    <form className="center card" onSubmit={submit}>
      <h1 style={{ color: 'var(--teal)', margin: 0 }}>StockSafe</h1>
      <p className="muted">Stop waste. Stop stockouts. Know what is about to expire and what is about to run out.</p>
      {mode === 'signup' && <>
        <div className="row"><input required placeholder="Your name" value={f.fullName} onChange={set('fullName')} style={{ width: '100%' }} /></div>
        <div className="row"><input required placeholder="Shop or business name" value={f.businessName} onChange={set('businessName')} style={{ width: '100%' }} /></div>
      </>}
      <div className="row"><input required type="email" placeholder="Email" value={f.email} onChange={set('email')} style={{ width: '100%' }} /></div>
      <div className="row"><input required type="password" minLength={8} placeholder="Password (8+ characters)" value={f.password} onChange={set('password')} style={{ width: '100%' }} /></div>
      {err && <div className="err">{err}</div>}
      <button disabled={busy} style={{ width: '100%' }}>{mode === 'login' ? 'Log in' : 'Create free account'}</button>
      <p className="muted" style={{ textAlign: 'center' }}>
        {mode === 'login' ? 'New here? ' : 'Have an account? '}
        <a href="#m" onClick={(e) => { e.preventDefault(); setMode(mode === 'login' ? 'signup' : 'login'); }}>
          {mode === 'login' ? 'Sign up free' : 'Log in'}
        </a>
      </p>
    </form>
  );
}
