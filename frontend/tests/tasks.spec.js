import { test, expect } from '@playwright/test';
import path from 'node:path';
test.skip(process.env.E2E_STAGE10 !== 'true', '阶段十任务监控验收');
test('管理员任务监控、空状态、故障恢复及非管理员隔离', async ({ page, request }) => {
  page.on('pageerror', error => { throw error; });
  async function login(username, password) {
    await page.goto('/#/login'); await page.getByRole('textbox', { name: '账号', exact: true }).fill(username);
    await page.getByLabel('密码', { exact: true }).fill(password); await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page.getByRole('heading', { name: /欢迎回来/ })).toBeVisible();
  }
  await login(process.env.E2E_ADMIN_USERNAME, process.env.E2E_ADMIN_PASSWORD);
  await page.getByRole('menuitem', { name: '文档处理任务', exact: true }).click();
  await expect(page.getByRole('heading', { name: '文档处理任务' })).toBeVisible();
  await expect(page.getByText('异步任务尚未启用，当前沿用原有处理方式。')).toBeVisible();
  await expect(page.getByText('暂无异步处理任务', { exact: true })).toBeVisible();
  await page.screenshot({ path: path.join(process.env.E2E_EVIDENCE, 'stage10-tasks-desktop.png'), fullPage: true });
  await page.route('**/api/document-tasks/metrics', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 503, message: '监控暂时不可用', data: null }) }));
  await page.getByRole('button', { name: '刷新任务' }).click(); await expect(page.getByRole('main').getByRole('alert').filter({ hasText: '监控暂时不可用' })).toBeVisible();
  await page.unroute('**/api/document-tasks/metrics'); await page.getByRole('button', { name: '刷新任务' }).click(); await expect(page.getByText('暂无异步处理任务', { exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 }); await page.screenshot({ path: path.join(process.env.E2E_EVIDENCE, 'stage10-tasks-mobile.png'), fullPage: true });
  await page.getByRole('button', { name: '退出登录' }).click();
  await login('browser-legal', process.env.E2E_USER_PASSWORD); await expect(page.getByRole('menuitem', { name: '文档处理任务', exact: true })).toHaveCount(0);
  const token = await page.evaluate(() => sessionStorage.getItem('compliance.accessToken'));
  expect((await request.get('/api/document-tasks', { headers: { Authorization: `Bearer ${token}` } })).status()).toBe(403);
  await page.goto('/#/document-tasks'); await expect(page).toHaveURL(/forbidden/);
});
