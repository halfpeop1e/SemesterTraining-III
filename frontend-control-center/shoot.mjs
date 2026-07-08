import { chromium } from 'playwright';
const BASE = process.env.BASE || 'http://localhost:4177';
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 900 } });
const errors = [];
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));

await page.goto(BASE, { waitUntil: 'networkidle' });
await page.waitForTimeout(1200);
await page.getByText('线路信号', { exact: true }).first().click();
await page.waitForTimeout(4500); // 等仿真推进几步

// 抓取 SVG 里显示的列车 ID 文本
const trainLabels = await page.$$eval('svg text', els => {
  const ids = new Set();
  els.forEach(e => { const t = e.textContent.trim(); if (/^T\d+$/.test(t)) ids.add(t); });
  return Array.from(ids).sort();
});
console.log('LINE SIGNAL 显示的列车ID:', JSON.stringify(trainLabels));

// 抓取统计栏
const stats = await page.$$eval('.ant-statistic-content-value', els => els.map(e => e.textContent.trim()));
console.log('统计栏:', JSON.stringify(stats));

// 抓取头部仿真时刻
const simclock = await page.$$eval('span', els => { const m = els.map(e=>e.textContent).find(t=>/仿真\s*\d+s/.test(t)); return m || null; });
console.log('仿真时刻:', simclock);

await page.screenshot({ path: '/tmp/ls-real.png' });
console.log('screenshot saved');
console.log('console errors:', errors.length ? errors.slice(0,6) : 'none');
await browser.close();
