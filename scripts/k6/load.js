// Load test. Install k6 on Windows:  winget install k6
// Run (core on localhost:8080):      k6 run scripts/k6/load.js
// Record the p95 numbers it prints in the README table.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080/api/v1';

export const options = {
  stages: [
    { duration: '20s', target: 20 },
    { duration: '60s', target: 20 },
    { duration: '10s', target: 0 },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

const json = (token) => ({ headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } });

export function setup() {
  const email = `k6-${Date.now()}@test.com`;
  const s = http.post(`${BASE}/auth/signup`, JSON.stringify({ email, password: 'Passw0rd!', fullName: 'k6', businessName: 'Load Test Store' }),
    { headers: { 'Content-Type': 'application/json' } }).json();
  const token = s.accessToken;
  const shop = http.post(`${BASE}/shops`, JSON.stringify({ name: 'Main', template: 'CUSTOM' }), json(token)).json().id;
  const products = [];
  for (let i = 0; i < 50; i++) {              // 50 products, each with plenty of stock
    const p = http.post(`${BASE}/shops/${shop}/products`, JSON.stringify({ name: `Item ${i}`, barcode: `K6-${i}` }), json(token)).json().id;
    http.post(`${BASE}/shops/${shop}/stock/receive`, JSON.stringify({ productId: p, batchNo: 'B0', cost: 1, mrp: 2, qty: 1000000 }), json(token));
    products.push(p);
  }
  return { token, shop, products };
}

export default function (d) {
  const p = d.products[Math.floor(Math.random() * d.products.length)];
  const r = Math.random();
  let res;
  if (r < 0.7) {         // 70% sell
    res = http.post(`${BASE}/shops/${d.shop}/stock/sell`, JSON.stringify({ productId: p, qty: 1 }), json(d.token));
  } else if (r < 0.9) {  // 20% search
    res = http.get(`${BASE}/shops/${d.shop}/products?q=Item%20${Math.floor(Math.random() * 50)}`, json(d.token));
  } else {               // 10% report
    res = http.get(`${BASE}/shops/${d.shop}/reports/low-stock`, json(d.token));
  }
  check(res, { 'status is 2xx': (x) => x.status >= 200 && x.status < 300 });
}
