import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';

test.skip(process.env.E2E_STAGE6 !== 'true', '阶段六脚本启动真实中文向量检索服务');
test.setTimeout(360000);
test.use({ actionTimeout: 15000 });
let admin, employee;
test.beforeEach(async ({ request }) => {
  const login = async (username, password) => {
    const reply = await request.post('/api/auth/login', { data: { username, password } });
    expect(reply.status()).toBe(200);
    return { Authorization: `Bearer ${(await reply.json()).data.accessToken}` };
  };
  admin = await login(process.env.E2E_ADMIN_USERNAME, process.env.E2E_ADMIN_PASSWORD);
  employee = await login('browser-employee', process.env.E2E_USER_PASSWORD);
});
async function upload(request, versionNo = 'v1', id = null) {
  const version = { versionNo, effectiveAt: '2026-01-01T00:00:00', expiresAt: null };
  const metadata = id ? version : { document: { title: '阶段六差旅制度', roles: ['ADMIN', 'EMPLOYEE'], departmentIds: [] }, version };
  const response = await request.post(id ? `/api/documents/${id}/versions` : '/api/documents', { headers: admin, multipart: {
    metadata: { name: 'metadata.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(metadata)) },
    file: { name: 'policy.docx', mimeType: 'application/octet-stream', buffer: fs.readFileSync(path.join(process.env.E2E_STAGE5_SAMPLES, 'policy.docx')) },
  } });
  expect(response.status()).toBe(201);
  const d = (await response.json()).data, v = d.versions.find(v => v.versionNo === versionNo);
  return { id: d.document.id, v: v.id, base: `/api/documents/${d.document.id}/versions/${v.id}` };
}
async function parse(request, doc) {
  expect((await request.post(`${doc.base}/parse`, { headers: admin })).status()).toBe(202);
  await expect.poll(async () => (await (await request.get(`${doc.base}/parse`, { headers: admin })).json()).data.status, { timeout: 210000 }).toBe('SUCCEEDED');
}
async function index(request, doc, suffix = '') {
  const reply = await request.post(`${doc.base}/index${suffix}`, { headers: admin, timeout: 195000 });
  expect(reply.status(), await reply.text()).toBe(200);
  return (await reply.json()).data;
}
async function search(request, headers = employee, question = '出差前需要办理什么手续？', minScore = 0.5) {
  const reply = await request.post('/api/retrieval/search', { headers, data: { query: question, topK: 5, minScore }, timeout: 195000 });
  expect(reply.status(), await reply.text()).toBe(200);
  return (await reply.json()).data;
}

test('真实切片索引、固定问题召回、重复与重建、旧版本和权限失效', async ({ request }) => {
  const doc = await upload(request);
  try {
    expect((await request.post(`${doc.base}/index`, { headers: employee })).status()).toBe(403);
    expect((await request.post(`${doc.base}/index`, { headers: admin })).status()).toBe(409);
    await parse(request, doc);
    const first = await index(request, doc);
    expect((await index(request, doc)).generation).toBe(first.generation);
    expect(await search(request)).toEqual([]);
    expect((await request.post(`${doc.base}/publish`, { headers: admin })).status()).toBe(200);
    const evidence = [];
    for (const [question, expected] of [['出差前需要办理什么手续？', '审批申请'], ['报销需要提交什么凭证？', '发票']]) {
      const hits = await search(request, employee, question);
      expect(hits[0].text).toContain(expected);
      expect(hits[0].pageNumber).toBeNull();
      expect(hits.every(h => h.documentVersionId === doc.v)).toBe(true);
      evidence.push({ question, expected, hits });
    }
    expect(await search(request, employee, '出差', 1)).toEqual([]);
    const rebuilt = await index(request, doc, '?rebuild=true');
    expect(rebuilt.generation).not.toBe(first.generation);
    expect((await search(request)).length).toBeLessThanOrEqual(3);
    const newer = await upload(request, 'v2', doc.id); await parse(request, newer); await index(request, newer);
    expect((await request.post(`${newer.base}/publish`, { headers: admin })).status()).toBe(200);
    const current = await search(request); expect(current.length).toBeGreaterThan(0);
    expect(current.every(h => h.documentVersionId === newer.v)).toBe(true);
    const scope = await request.put(`/api/documents/${doc.id}`, { headers: admin, data: { title: '阶段六差旅制度', roles: ['ADMIN'], departmentIds: [] } });
    expect(scope.status()).toBe(200); expect(await search(request)).toEqual([]);
    expect((await search(request, admin)).length).toBeGreaterThan(0);
    expect((await request.post(`${newer.base}/retire`, { headers: admin })).status()).toBe(200);
    expect(await search(request, admin)).toEqual([]);
    fs.writeFileSync(path.join(process.env.E2E_EVIDENCE, 'stage6-recall.json'), JSON.stringify(evidence, null, 2));
  } finally { await request.delete(`/api/documents/${doc.id}`, { headers: admin }); }
});

test('浏览器索引发布与员工检索来源，桌面和移动端可读', async ({ page, request }) => {
  const doc = await upload(request); await parse(request, doc);
  try {
    await page.goto('/#/login');
    await page.getByRole('textbox', { name: '账号', exact: true }).fill(process.env.E2E_ADMIN_USERNAME);
    await page.getByLabel('密码', { exact: true }).fill(process.env.E2E_ADMIN_PASSWORD);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.getByRole('menuitem', { name: '制度管理', exact: true }).click();
    await page.getByRole('row').filter({ hasText: '阶段六差旅制度' }).getByRole('button', { name: '查看版本' }).click();
    await page.getByRole('button', { name: '索引管理', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '制度索引管理' });
    await dialog.getByRole('button', { name: '开始索引', exact: true }).click();
    await expect(dialog.getByRole('button', { name: '重建索引', exact: true })).toBeVisible({ timeout: 195000 });
    await expect(dialog.getByRole('button', { name: '刷新索引状态', exact: true })).toBeEnabled();
    await page.screenshot({ path: path.join(process.env.E2E_EVIDENCE, 'stage6-index.png') });
    await dialog.getByRole('button', { name: '关闭此对话框', exact: true }).click();
    await page.getByRole('button', { name: '发布', exact: true }).click();
    await page.getByRole('button', { name: '确认', exact: true }).click();
    await expect(page.getByText('已发布 / 处理就绪')).toBeVisible();
    await page.getByRole('button', { name: '退出登录', exact: true }).click();
    await page.getByRole('textbox', { name: '账号', exact: true }).fill('browser-employee');
    await page.getByLabel('密码', { exact: true }).fill(process.env.E2E_USER_PASSWORD);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.getByRole('menuitem', { name: '制度原文检索', exact: true }).click();
    await page.getByRole('textbox', { name: '查找问题', exact: true }).fill('出差前需要办理什么手续？');
    await page.getByRole('button', { name: '检索原文', exact: true }).click();
    await expect(page.locator('article').first()).toContainText('审批申请');
    await expect(page.locator('article').first()).toContainText('段落 2');
    const download = page.waitForEvent('download');
    await page.locator('article').first().getByRole('button', { name: '下载来源原文件' }).click();
    expect((await download).suggestedFilename()).toBe('policy.docx');
    await page.screenshot({ path: path.join(process.env.E2E_EVIDENCE, 'stage6-retrieval.png') });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(process.env.E2E_EVIDENCE, 'stage6-retrieval-mobile.png') });
    await request.delete(`/api/documents/${doc.id}`, { headers: admin });
    await page.getByRole('button', { name: '检索原文', exact: true }).click();
    await expect(page.getByText(/未找到足够相关的原文/)).toBeVisible();
    await page.route('**/api/retrieval/search', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, message: '验收检索服务暂不可用' }) }));
    await page.getByRole('button', { name: '检索原文', exact: true }).click();
    await expect(page.getByText('验收检索服务暂不可用')).toBeVisible();
    await page.unroute('**/api/retrieval/search');
    await page.getByRole('button', { name: '检索原文', exact: true }).click();
    await expect(page.getByText(/未找到足够相关的原文/)).toBeVisible();
  } finally { await request.delete(`/api/documents/${doc.id}`, { headers: admin }); }
});
