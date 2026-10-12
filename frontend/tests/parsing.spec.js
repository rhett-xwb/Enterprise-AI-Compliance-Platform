import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
test.skip(process.env.E2E_STAGE5 !== 'true', '阶段五脚本启动真实解析服务');
test.setTimeout(360000);
let headers;
test.beforeEach(async ({ request }) => {
  const login = await request.post('/api/auth/login', { data: { username: process.env.E2E_ADMIN_USERNAME, password: process.env.E2E_ADMIN_PASSWORD } });
  headers = { Authorization: `Bearer ${(await login.json()).data.accessToken}` };
});
async function upload(request, filename) {
  const response = await request.post('/api/documents', { headers, multipart: {
    metadata: { name: 'metadata.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({
      document: { title: `阶段五-${filename}`, roles: ['ADMIN', 'EMPLOYEE'], departmentIds: [] },
      version: { versionNo: 'v1', effectiveAt: '2026-01-01T00:00:00', expiresAt: null },
    })) },
    file: { name: filename, mimeType: 'application/octet-stream', buffer: fs.readFileSync(path.join(process.env.E2E_STAGE5_SAMPLES, filename)) },
  } });
  expect(response.status()).toBe(201);
  const d = (await response.json()).data;
  return { id: d.document.id, v: d.versions[0].id, base: `/api/documents/${d.document.id}/versions/${d.versions[0].id}` };
}
async function done(request, base) {
  let task;
  await expect.poll(async () => {
    const reply = await request.get(`${base}/parse`, { headers });
    expect(reply.status()).toBe(200);
    task = (await reply.json()).data;
    return task?.status;
  }, { timeout: 210000, intervals: [1000, 2000] }).toMatch(/SUCCEEDED|FAILED/);
  return task;
}
test('真实解析四类文件，保留来源且解析后仍禁止发布', async ({ request }) => {
  const evidence = [];
  for (const filename of ['electronic.pdf', 'scan.pdf', 'policy.png', 'policy.jpg', 'policy.docx']) {
    const doc = await upload(request, filename);
    try {
      const first = await request.post(`${doc.base}/parse`, { headers });
      expect(first.status()).toBe(202);
      const repeat = await request.post(`${doc.base}/parse`, { headers });
      expect((await repeat.json()).data.id).toBe((await first.json()).data.id);
      const task = await done(request, doc.base);
      expect(task.status, JSON.stringify(task)).toBe('SUCCEEDED');
      const response = await request.get(`${doc.base}/sources`, { headers });
      const sources = (await response.json()).data;
      expect(sources.length).toBeGreaterThan(0);
      if (filename === 'electronic.pdf') {
        expect(sources.map(p => p.pageNumber)).toEqual([1, 2]);
        expect(sources[0].text).toContain('Travel policy');
        expect(sources[1].text).toContain('Reimbursement policy');
      } else if (filename.endsWith('docx')) {
        expect(sources.every(p => p.pageNumber === null)).toBe(true);
        expect(sources[1].text).toContain('审批申请');
      } else {
        expect(sources[0].method).toBe('OCR');
        expect(sources[0].pageNumber).toBe(1);
        expect(sources[0].text).toContain('审批申请');
      }
      expect((await request.post(`${doc.base}/publish`, { headers })).status()).toBe(409);
      const after = await request.post(`${doc.base}/parse`, { headers });
      expect((await after.json()).data.attempts).toBe(1);
      evidence.push({ filename, task, sources });
    } finally { await request.delete(`/api/documents/${doc.id}`, { headers }); }
  }
  fs.writeFileSync(path.join(process.env.E2E_EVIDENCE, 'stage5-parsed-samples.json'), JSON.stringify(evidence, null, 2));
});
test('错误文件明确失败，重试复用任务且不产生文本；员工拒绝读取解析草稿', async ({ request }) => {
  const doc = await upload(request, 'broken.pdf');
  try {
    await request.post(`${doc.base}/parse`, { headers });
    const first = await done(request, doc.base);
    expect(first.status).toBe('FAILED'); expect(first.errorCode).toBe('PARSE_FAILED');
    await request.post(`${doc.base}/parse?retry=true`, { headers });
    const second = await done(request, doc.base);
    expect(second.id).toBe(first.id); expect(second.attempts).toBe(2);
    expect((await (await request.get(`${doc.base}/sources`, { headers })).json()).data).toEqual([]);
    const login = await request.post('/api/auth/login', { data: { username: 'browser-employee', password: process.env.E2E_USER_PASSWORD } });
    const employee = { Authorization: `Bearer ${(await login.json()).data.accessToken}` };
    expect((await request.get(`${doc.base}/sources`, { headers: employee })).status()).toBe(403);
    expect((await request.post(`${doc.base}/parse`, { headers: employee })).status()).toBe(403);
  } finally { await request.delete(`/api/documents/${doc.id}`, { headers }); }
});
test('浏览器发起解析并核对段落来源，切换入口清除解析窗口', async ({ page, request }) => {
  const doc = await upload(request, 'policy.docx');
  try {
    await page.goto('/#/login');
    await page.getByRole('textbox', { name: '账号', exact: true }).fill(process.env.E2E_ADMIN_USERNAME);
    await page.getByLabel('密码', { exact: true }).fill(process.env.E2E_ADMIN_PASSWORD);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.getByRole('menuitem', { name: '制度管理', exact: true }).click();
    await page.getByRole('row').filter({ hasText: '阶段五-policy.docx' }).getByRole('button', { name: '查看版本' }).click();
    await page.getByRole('button', { name: '开始解析', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '解析结果与来源' });
    await expect(dialog.getByText('出差前必须提交审批申请', { exact: true })).toBeVisible({ timeout: 30000 });
    await expect(dialog.getByRole('heading', { name: /段落 2/ })).toBeVisible();
    await page.screenshot({ path: path.join(process.env.E2E_EVIDENCE, 'stage5-source-preview.png'), fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(process.env.E2E_EVIDENCE, 'stage5-source-mobile.png'), fullPage: true });
    await dialog.getByRole('button', { name: '关闭此对话框' }).click();
    await expect(page.getByRole('button', { name: '发布', exact: true })).toBeDisabled();
    await page.getByRole('menuitem', { name: '制度查询', exact: true }).click();
    await expect(dialog).toHaveCount(0);
  } finally { await request.delete(`/api/documents/${doc.id}`, { headers }); }
});
