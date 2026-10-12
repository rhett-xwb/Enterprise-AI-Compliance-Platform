import { test, expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
test.skip(process.env.E2E_STAGE7 !== 'true', '阶段七受控模型契约验收');
test.setTimeout(360000);
let admin, employee;
test.beforeEach(async ({ request }) => {
  async function login(username, password) {
    const reply = await request.post('/api/auth/login', { data: { username, password } });
    expect(reply.status()).toBe(200);
    return { Authorization: `Bearer ${(await reply.json()).data.accessToken}` };
  }
  admin = await login(process.env.E2E_ADMIN_USERNAME, process.env.E2E_ADMIN_PASSWORD);
  employee = await login('browser-employee', process.env.E2E_USER_PASSWORD);
});
async function ready(request, id = null, versionNo = 'v1') {
  const version = { versionNo, effectiveAt: '2026-01-01T00:00:00', expiresAt: null };
  const reply = await request.post(id ? `/api/documents/${id}/versions` : '/api/documents', { headers: admin, multipart: {
    metadata: { name: 'metadata.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(id ? version : { document: { title: '阶段七差旅制度', roles: ['ADMIN', 'EMPLOYEE'], departmentIds: [] }, version })) },
    file: { name: 'policy.docx', mimeType: 'application/octet-stream', buffer: fs.readFileSync(path.join(process.env.E2E_STAGE5_SAMPLES, 'policy.docx')) },
  } });
  expect(reply.status()).toBe(201);
  const d = (await reply.json()).data, v = d.versions.find(v => v.versionNo === versionNo);
  const base = `/api/documents/${d.document.id}/versions/${v.id}`;
  expect((await request.post(`${base}/parse`, { headers: admin })).status()).toBe(202);
  await expect.poll(async () => (await (await request.get(`${base}/parse`, { headers: admin })).json()).data.status, { timeout: 210000 }).toBe('SUCCEEDED');
  expect((await request.post(`${base}/index`, { headers: admin, timeout: 195000 })).status()).toBe(200);
  expect((await request.post(`${base}/publish`, { headers: admin })).status()).toBe(200);
  return { id: d.document.id, v: v.id, base };
}
async function ask(request, question) {
  const reply = await request.post('/api/questions', { headers: employee, data: { question }, timeout: 320000 });
  expect(reply.status(), await reply.text()).toBe(200);
  return (await reply.json()).data;
}
test('真实检索接入受控模型：引用、历史、旧版、权限、无依据与失败恢复', async ({ request }) => {
  expect((await request.get('/api/questions')).status()).toBe(401);
  const noEvidence = await ask(request, '未收录制度问题'); expect(noEvidence.status).toBe('INSUFFICIENT');
  const doc = await ready(request);
  const evidence = [{ category: '无资料', actual: noEvidence, kind: 'controlled-contract' }];
  try {
    const answer = await ask(request, '出差前需要办理什么手续？');
    expect(answer.status).toBe('ANSWERED'); expect(answer.answer).toContain('审批申请');
    expect(answer.citations[0].source.text).toContain('审批申请'); expect(answer.citations[0].source.pageNumber).toBeNull();
    const source = await request.get(`/api/questions/${answer.id}/citations/${answer.citations[0].id}`, { headers: employee }); expect(source.status()).toBe(200);
    expect((await request.get(`/api/questions/${answer.id}`, { headers: admin })).status()).toBe(404);
    evidence.push({ category: '正常引用', expected: '审批申请；当前版本；真实段落', actual: answer, kind: 'controlled-contract' });
    for (const [category, query, expected] of [
      ['无答案', '出差审批 无答案测试', 'INSUFFICIENT'],
      ['模型限流', '出差审批 失败测试', 'FAILED'],
      ['伪造引用', '出差审批 伪造引用', 'FAILED'],
    ]) {
      const actual = await ask(request, query); expect(actual.status).toBe(expected);
      evidence.push({ category, query, actual, kind: 'controlled-contract' });
    }
    const retry = await ask(request, '出差前需要办理什么手续？'); expect(retry.status).toBe('ANSWERED');
    const newer = await ready(request, doc.id, 'v2');
    const old = (await (await request.get(`/api/questions/${answer.id}`, { headers: employee })).json()).data;
    expect(old.citations[0].state).toBe('OUTDATED'); expect(old.citations[0].source.documentVersionId).toBe(doc.v);
    const current = await ask(request, '出差前需要办理什么手续？'); expect(current.citations.every(c => c.source.documentVersionId === newer.v)).toBe(true);
    expect((await request.put(`/api/documents/${doc.id}`, { headers: admin, data: { title: '阶段七差旅制度', roles: ['ADMIN'], departmentIds: [] } })).status()).toBe(200);
    const hidden = (await (await request.get(`/api/questions/${answer.id}`, { headers: employee })).json()).data;
    expect(hidden.status).toBe('RESTRICTED'); expect(hidden.citations).toEqual([]); expect(hidden.answer).not.toContain('审批申请');
    expect((await request.get(`/api/questions/${answer.id}/citations/${answer.citations[0].id}`, { headers: employee })).status()).toBe(404);
    expect((await ask(request, '出差前需要办理什么手续？')).status).toBe('INSUFFICIENT');
    evidence.push({ category: '旧版与权限', old, hidden, kind: 'controlled-contract' });
    fs.writeFileSync(path.join(process.env.E2E_EVIDENCE, 'stage7-contract-evaluation.json'), JSON.stringify(evidence, null, 2));
  } finally { await request.delete(`/api/documents/${doc.id}`, { headers: admin }); }
});
test('浏览器问答来源与历史，失败后恢复及移动布局', async ({ page, request }) => {
  const doc = await ready(request);
  try {
    await page.goto('/#/login');
    await page.getByRole('textbox', { name: '账号', exact: true }).fill('browser-employee');
    await page.getByLabel('密码', { exact: true }).fill(process.env.E2E_USER_PASSWORD);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await page.getByRole('menuitem', { name: '制度问答', exact: true }).click();
    const input = page.getByRole('textbox', { name: '制度问题', exact: true });
    await input.fill('出差前需要办理什么手续？'); await page.getByRole('button', { name: '提交问题', exact: true }).click();
    const result = page.getByRole('article', { name: '问答结果' });
    await expect(result).toContainText('有依据的回答', { timeout: 195000 }); await expect(result).toContainText('审批申请');
    await result.getByRole('button', { name: /查看引用来源/ }).first().click();
    const dialog = page.getByRole('dialog', { name: '引用来源' }); await expect(dialog).toContainText('段落');
    const download = page.waitForEvent('download'); await dialog.getByRole('button', { name: '下载来源原文件' }).click(); expect((await download).suggestedFilename()).toBe('policy.docx');
    await dialog.getByRole('button', { name: '关闭此对话框', exact: true }).click();
    await page.screenshot({ path: path.join(process.env.E2E_EVIDENCE, 'stage7-questions.png') });
    await input.fill('出差审批 失败测试'); await page.getByRole('button', { name: '提交问题', exact: true }).click();
    await expect(result).toContainText('问答未完成'); await expect(page.getByRole('button', { name: '提交问题', exact: true })).toBeEnabled();
    await input.fill('出差前需要办理什么手续？'); await page.getByRole('button', { name: '提交问题', exact: true }).click(); await expect(result).toContainText('有依据的回答');
    await page.reload(); await expect(page.locator('.history')).toContainText('出差前需要办理什么手续？');
    await page.locator('.history').getByRole('button', { name: '出差前需要办理什么手续？', exact: true }).first().click(); await expect(result).toContainText('有依据的回答');
    await page.setViewportSize({ width: 390, height: 844 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true);
    await page.screenshot({ path: path.join(process.env.E2E_EVIDENCE, 'stage7-questions-mobile.png') });
  } finally { await request.delete(`/api/documents/${doc.id}`, { headers: admin }); }
});
