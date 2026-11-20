import React, { useEffect, useRef, useState } from 'react';
import { api, download } from '../api.js';
import { useApp } from '../App.jsx';

const SAMPLES = {
  products: 'barcode,name,unit,gst_rate,reorder_level,manufacturer\n890100000001,Paracetamol 500,strip,12,20,Cipla\n',
  stock: 'barcode,batch_no,expiry_date,cost,mrp,qty\n890100000001,B-1001,2027-06-30,8.5,12,100\n',
};

export default function Import() {
  const { shopId, reload } = useApp();
  const [kind, setKind] = useState('products');
  const [job, setJob] = useState(null);
  const [errors, setErrors] = useState([]);
  const [msg, setMsg] = useState('');
  const poll = useRef(null);

  const watch = (id) => {
    clearInterval(poll.current);
    poll.current = setInterval(async () => {
      const j = await api(`/imports/${id}`);
      setJob(j);
      if (j.status === 'DONE' || j.status === 'FAILED') {
        clearInterval(poll.current); reload();
        setErrors(await api(`/imports/${id}/errors?limit=200`));
      }
    }, 1000);
  };
  useEffect(() => () => clearInterval(poll.current), []);

  const upload = async (e) => {
    const file = e.target.files[0]; if (!file) return;
    setMsg(''); setErrors([]);
    try {
      const text = await file.text();
      const r = await api(`/shops/${shopId}/imports/${kind}?fileName=${encodeURIComponent(file.name)}`,
        { method: 'POST', body: text, headers: { 'Content-Type': 'text/csv' } });
      setJob({ id: r.jobId, status: 'RUNNING', processed_rows: 0, total_rows: 0, success_rows: 0, error_rows: 0 });
      watch(r.jobId);
    } catch (ex) { setMsg(ex.message); }
    e.target.value = '';
  };
  const resume = async () => { try { await api(`/imports/${job.id}/resume`, { method: 'POST' }); watch(job.id); } catch (ex) { setMsg(ex.message); } };
  const sample = () => { const b = new Blob([SAMPLES[kind]], { type: 'text/csv' }); const a = document.createElement('a'); a.href = URL.createObjectURL(b); a.download = `${kind}-sample.csv`; a.click(); };
  const pct = job && job.total_rows ? Math.round((job.processed_rows / job.total_rows) * 100) : 0;

  return (
    <>
      <div className="card"><h3>Bulk import (CSV)</h3>
        <div className="row"><select value={kind} onChange={(e) => setKind(e.target.value)}>
          <option value="products">Products</option><option value="stock">Stock (batches)</option></select>
          <input type="file" accept=".csv,text/csv" onChange={upload} />
          <button className="ghost" onClick={sample}>Download sample</button></div>
        <div className="muted">Import products first, then stock. Stock rows find the product by barcode. Good rows are saved even if other rows have errors.</div>
        {msg && <div className="err">{msg}</div>}</div>
      {job && (
        <div className="card"><h3>Import {job.status}</h3>
          <div style={{ background: '#e2e8f0', borderRadius: 6, height: 10 }}><div style={{ width: `${pct}%`, background: 'var(--teal)', height: 10, borderRadius: 6 }} /></div>
          <p>{job.processed_rows} of {job.total_rows} rows · <span className="tag ok">{job.success_rows} saved</span> <span className="tag bad">{job.error_rows} with errors</span></p>
          {job.status === 'FAILED' && <><div className="err">{job.message}</div><button onClick={resume}>Resume from where it stopped</button></>}
          {errors.length > 0 && <>
            <div className="row"><strong>Rows that need fixing</strong>
              <button className="ghost" onClick={() => download(`/imports/${job.id}/errors.csv`, 'import-errors.csv')}>Download full error report</button></div>
            <div style={{ overflowX: 'auto' }}><table><thead><tr><th>Row</th><th>Problem</th><th>Data</th></tr></thead>
              <tbody>{errors.map((x) => <tr key={x.row_no}><td>{x.row_no}</td><td>{x.message}</td><td className="muted">{x.raw}</td></tr>)}</tbody></table></div></>}
        </div>)}
    </>
  );
}
