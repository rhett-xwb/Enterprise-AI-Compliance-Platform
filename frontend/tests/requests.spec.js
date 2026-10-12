import { test, expect } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs';
test.skip(process.env.E2E_STAGE8 !== 'true', '阶段八差旅申请验收');
test.setTimeout(360000);
test('差旅申请保存、预审、金额与附件失效、快照提交及本人范围', async ({ page, request }) => {
  async function login(username, password) {
    const r = await request.post('/api/auth/login', { data: { username, password } });
    expect(r.status()).toBe(200);
    return { Authorization: `Bearer ${(await r.json()).data.accessToken}` };
  }
  const employee = await login('browser-employee', process.env.E2E_USER_PASSWORD);
  const admin = await login(process.env.E2E_ADMIN_USERNAME, process.env.E2E_ADMIN_PASSWORD);
  const assignees = (await (await request.get('/api/workflows/assignees', { headers: admin })).json()).data;
  expect((await request.post('/api/workflows/definitions', { headers: admin, data: { name: '差旅验收流程', routes: [{ minimum: 0, maximum: null, nodes: [{ name: '人工审批', assigneeId: assignees[0].id }] }] } })).status()).toBe(200);
  await page.goto('/#/login');
  await page.getByRole('textbox', { name: '账号', exact: true }).fill('browser-employee');
  await page.getByLabel('密码', { exact: true }).fill(process.env.E2E_USER_PASSWORD);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await page.getByRole('menuitem', { name: '我的申请', exact: true }).click();
  await page.getByRole('textbox', { name: '申请标题', exact: true }).fill('阶段八浏览器差旅');
  await page.getByRole('textbox', { name: '目的地', exact: true }).fill('上海');
  const dates = page.locator('.el-date-editor input');
  await dates.nth(0).fill('2026-11-01'); await dates.nth(0).press('Tab');
  await dates.nth(1).fill('2026-11-03'); await dates.nth(1).press('Tab');
  await page.getByRole('textbox', { name: '差旅事由', exact: true }).fill('项目沟通');
  await page.getByRole('spinbutton', { name: '申请金额', exact: true }).fill('1234');
  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(page.getByRole('heading', { name: /申请 #.*内容版本 1/ })).toBeVisible();
  const list = (await (await request.get('/api/travel-requests', { headers: employee })).json()).data;
  const id = list.find(r => r.title === '阶段八浏览器差旅').id;
  expect((await request.get(`/api/travel-requests/${id}`, { headers: admin })).status()).toBe(404);
  await page.getByRole('button', { name: '开始预审', exact: true }).click();
  await expect(page.locator('.review').first()).toContainText('制度风险无法判断', { timeout: 195000 });
  await page.getByRole('spinbutton', { name: '申请金额', exact: true }).fill('2345');
  await expect(page.getByRole('button', { name: '再次预审', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '保存草稿', exact: true }).click();
  await expect(page.locator('.review').first()).toContainText('预审失效');
  await page.getByRole('button', { name: '再次预审', exact: true }).click();
  await expect(page.locator('.review').first()).toContainText('版本 2', { timeout: 195000 });
  await page.getByLabel('上传申请附件', { exact: true }).setInputFiles({ name: 'travel.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7\nfixture') });
  await expect(page.getByRole('heading', { name: /内容版本 3/ })).toBeVisible();
  await expect(page.locator('.review').first()).toContainText('预审失效');
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: '下载附件', exact: true }).click();
  expect((await download).suggestedFilename()).toBe('travel.pdf');
  await page.getByRole('button', { name: '再次预审', exact: true }).click();
  await expect(page.locator('.review').first()).toContainText('版本 3', { timeout: 195000 });
  await page.screenshot({ path: path.join(process.env.E2E_EVIDENCE, 'stage8-requests.png') });
  await page.getByRole('button', { name: '确认当前内容并提交申请', exact: true }).click();
  await expect(page.getByRole('alert').filter({ hasText: '申请已提交' })).toBeVisible();
  await expect(page.getByText(/提交记录 #/)).toBeVisible();
  await expect(page.getByRole('button', { name: '保存草稿', exact: true })).toHaveCount(0);
  const result = (await (await request.get(`/api/travel-requests/${id}`, { headers: employee })).json()).data;
  expect(result.status).toBe('IN_REVIEW'); expect(result.contentVersion).toBe(3); expect(result.form.amount).toBe(2345); expect(result.submission).not.toBeNull();
  expect((await request.post(`/api/travel-requests/${id}/submit`, { headers: employee, data: { contentVersion: 3, reviewId: result.reviews[0].id } })).status()).toBe(409);
  await page.reload(); await expect(page.getByRole('row').filter({ hasText: '阶段八浏览器差旅' })).toContainText('审批中');
  await page.getByRole('row').filter({ hasText: '阶段八浏览器差旅' }).getByRole('button', { name: '查看申请' }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
  await page.screenshot({ path: path.join(process.env.E2E_EVIDENCE, 'stage8-requests-mobile.png') });
});
test('差旅预审使用真实解析与中文检索依据，撤权隐藏建议并拒绝提交', async ({ request }) => {
  async function login(username, password) {
    const response = await request.post('/api/auth/login', { data: { username, password } });
    expect(response.status()).toBe(200);
    return { Authorization: `Bearer ${(await response.json()).data.accessToken}` };
  }
  const admin = await login(process.env.E2E_ADMIN_USERNAME, process.env.E2E_ADMIN_PASSWORD);
  const employee = await login('browser-employee', process.env.E2E_USER_PASSWORD);
  const uploaded = await request.post('/api/documents', { headers: admin, multipart: {
    metadata: { name: 'metadata.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ document: { title: '阶段八差旅预审依据', roles: ['ADMIN', 'EMPLOYEE'], departmentIds: [] }, version: { versionNo: 'v1', effectiveAt: '2026-01-01T00:00:00', expiresAt: null } })) },
    file: { name: 'policy.docx', mimeType: 'application/octet-stream', buffer: fs.readFileSync(path.join(process.env.E2E_STAGE5_SAMPLES, 'policy.docx')) },
  } });
  expect(uploaded.status()).toBe(201);
  const doc = (await uploaded.json()).data, base = `/api/documents/${doc.document.id}/versions/${doc.versions[0].id}`;
  try {
    expect((await request.post(`${base}/parse`, { headers: admin })).status()).toBe(202);
    await expect.poll(async () => (await (await request.get(`${base}/parse`, { headers: admin })).json()).data.status, { timeout: 210000 }).toBe('SUCCEEDED');
    expect((await request.post(`${base}/index`, { headers: admin, timeout: 195000 })).status()).toBe(200);
    expect((await request.post(`${base}/publish`, { headers: admin })).status()).toBe(200);
    const created = await request.post('/api/travel-requests', { headers: employee, data: { title: '有依据的预审', destination: '上海', startDate: '2026-11-01', endDate: '2026-11-03', purpose: '项目沟通', amount: 1000 } });
    expect(created.status()).toBe(200); const id = (await created.json()).data.id;
    const response = await request.post(`/api/travel-requests/${id}/reviews`, { headers: employee, data: { contentVersion: 1 }, timeout: 320000 });
    expect(response.status(), await response.text()).toBe(200);
    const review = (await response.json()).data.reviews[0];
    expect(review.status).toBe('REVIEWED'); expect(review.citations.length).toBeGreaterThan(0);
    expect(review.evidence[review.citations[0] - 1].text).toContain('审批申请');
    expect(review.evidence[review.citations[0] - 1].documentId).toBe(doc.document.id);
    expect((await request.put(`/api/documents/${doc.document.id}`, { headers: admin, data: { title: '阶段八差旅预审依据', roles: ['ADMIN'], departmentIds: [] } })).status()).toBe(200);
    const hidden = (await (await request.get(`/api/travel-requests/${id}`, { headers: employee })).json()).data.reviews[0];
    expect(hidden.status).toBe('RESTRICTED'); expect(hidden.evidence).toEqual([]); expect(hidden.advice).not.toContain('审批申请');
    expect((await request.post(`/api/travel-requests/${id}/submit`, { headers: employee, data: { contentVersion: 1, reviewId: review.id } })).status()).toBe(409);
    fs.writeFileSync(path.join(process.env.E2E_EVIDENCE, 'stage8-contract-evaluation.json'), JSON.stringify({ kind: 'controlled-model-real-retrieval', review, hidden }, null, 2));
  } finally { await request.delete(`/api/documents/${doc.document.id}`, { headers: admin }); }
});
