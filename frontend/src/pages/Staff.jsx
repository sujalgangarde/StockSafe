import React, { useCallback, useEffect, useState } from 'react';
import { api } from '../api.js';

export default function Staff() {
  const [rows, setRows] = useState([]);
  const [f, setF] = useState({ email: '', fullName: '', role: 'STAFF' });
  const [msg, setMsg] = useState({});
  const load = useCallback(() => api('/staff').then(setRows), []);
  useEffect(() => { load().catch(() => {}); }, [load]);

  const add = async (e) => {
    e.preventDefault(); setMsg({});
    try {
      const r = await api('/staff', { method: 'POST', body: f });
      setMsg({ ok: r.temporaryPassword ? `Added. Temporary password (shown once): ${r.temporaryPassword}` : 'Added to your shop.' });
      setF({ email: '', fullName: '', role: 'STAFF' }); load();
    } catch (ex) { setMsg({ err: ex.message }); }
  };
  const remove = async (id) => { if (confirm('Remove this person?')) { await api(`/staff/${id}`, { method: 'DELETE' }); load(); } };

  return (
    <>
      <form className="card" onSubmit={add}><h3>Add a team member</h3>
        <div className="row">
          <input required type="email" placeholder="Email" value={f.email} onChange={(e) => setF({ ...f, email: e.target.value })} />
          <input required placeholder="Name" value={f.fullName} onChange={(e) => setF({ ...f, fullName: e.target.value })} />
          <select value={f.role} onChange={(e) => setF({ ...f, role: e.target.value })}>
            <option value="STAFF">Staff (can sell)</option><option value="MANAGER">Manager (receive, adjust, import)</option></select>
          <button>Add</button></div>
        {msg.err && <div className="err">{msg.err}</div>}{msg.ok && <div className="ok-msg">{msg.ok}</div>}</form>
      <div className="card"><table><thead><tr><th>Name</th><th>Email</th><th>Role</th><th /></tr></thead>
        <tbody>{rows.map((r) => <tr key={r.id}><td>{r.full_name}</td><td>{r.email}</td><td><span className="tag">{r.role}</span></td>
          <td>{r.role !== 'OWNER' && <button className="ghost" onClick={() => remove(r.id)}>Remove</button>}</td></tr>)}</tbody></table></div>
    </>
  );
}
