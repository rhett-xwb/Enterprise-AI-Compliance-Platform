import { test, expect } from "@playwright/test";
import path from "node:path";
test.beforeEach(async ({ page }) => {
  page.on("pageerror", (error) => {
    throw error;
  });
});
async function login(page, username, password) {
  await page.goto("/#/login");
  await page.getByRole("textbox", { name: "账号", exact: true }).fill(username);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
}
const evidence = process.env.E2E_EVIDENCE || "../.cache";
test("登录失败提示、未登录跳转与管理员真实列表、刷新、退出撤销", async ({
  page,
  request,
}) => {
  await page.goto("/#/users");
  await expect(page).toHaveURL(/login/);
  await page.screenshot({
    path: path.join(evidence, "login-desktop.png"),
    fullPage: true,
  });
  await login(page, process.env.E2E_ADMIN_USERNAME, "incorrect-password");
  await expect(page.getByText("账号或密码错误，或账号已停用")).toBeVisible();
  await login(
    page,
    process.env.E2E_ADMIN_USERNAME,
    process.env.E2E_ADMIN_PASSWORD,
  );
  await expect(page.getByRole("heading", { name: /欢迎回来/ })).toBeVisible();
  const token = await page.evaluate(() =>
    sessionStorage.getItem("compliance.accessToken"),
  );
  await page.getByRole("menuitem", { name: "用户管理", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: "browser-employee", exact: true }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("cell", { name: "browser-legal", exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: path.join(evidence, "users-desktop.png"),
    fullPage: true,
  });
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page).toHaveURL(/login/);
  expect(
    await page.evaluate(() => sessionStorage.getItem("compliance.accessToken")),
  ).toBeNull();
  const reply = await request.get("/api/auth/me", {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(reply.status()).toBe(401);
});
for (const role of ["employee", "legal"]) {
  test(`${role} 真实登录、角色菜单、前后端越权阻断与移动端`, async ({
    page,
    request,
  }) => {
    await login(page, `browser-${role}`, process.env.E2E_USER_PASSWORD);
    await expect(page.getByRole("heading", { name: /欢迎回来/ })).toBeVisible();
    await expect(
      page.getByRole("menuitem", { name: "用户管理", exact: true }),
    ).toHaveCount(0);
    await expect(
      page.getByRole("menuitem", { name: "制度查询", exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("menuitem", { name: "制度管理", exact: true }),
    ).toHaveCount(role === "legal" ? 1 : 0);
    const token = await page.evaluate(() =>
      sessionStorage.getItem("compliance.accessToken"),
    );
    expect(
      (
        await request.get("/api/users", {
          headers: { Authorization: `Bearer ${token}` },
        })
      ).status(),
    ).toBe(403);
    await page.goto("/#/users");
    await expect(
      page.getByRole("main").getByText("无访问权限", { exact: true }),
    ).toBeVisible();
    await page.goto("/#/documents");
    await expect(
      page.getByRole("heading", { name: "制度查询", exact: true }),
    ).toBeVisible();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({
      path: path.join(evidence, `${role}-mobile.png`),
      fullPage: true,
    });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  });
}
test("列表加载、空数据、服务器错误重试及失效令牌", async ({ page }) => {
  await login(
    page,
    process.env.E2E_ADMIN_USERNAME,
    process.env.E2E_ADMIN_PASSWORD,
  );
  await expect(page.getByRole("heading", { name: /欢迎回来/ })).toBeVisible();
  let finish;
  const pending = new Promise((resolve) => {
    finish = resolve;
  });
  await page.route("**/api/users?*", async (route) => {
    await pending;
    await route.fulfill({ json: { code: 0, data: { records: [], total: 0 } } });
  });
  await page.getByRole("menuitem", { name: "用户管理", exact: true }).click();
  await expect(page.locator(".el-skeleton")).toBeVisible();
  finish();
  await expect(page.getByText("暂无用户记录")).toBeVisible();
  await page.unroute("**/api/users?*");
  await page.route("**/api/users?*", (route) =>
    route.fulfill({
      status: 500,
      json: { code: 500, message: "验收服务器错误" },
    }),
  );
  await page.getByRole("button", { name: "刷新" }).click();
  await expect(page.getByText("验收服务器错误")).toBeVisible();
  await page.unroute("**/api/users?*");
  await page.getByRole("button", { name: "重新加载" }).click();
  await expect(
    page.getByRole("cell", { name: "browser-employee", exact: true }),
  ).toBeVisible();
  await page.evaluate(() =>
    sessionStorage.setItem("compliance.accessToken", "invalid-token"),
  );
  await page.reload();
  await expect(page).toHaveURL(/login/);
  await expect(page.getByText("登录已失效，请重新登录")).toBeVisible();
});
test("网络失败恢复及不安全回跳地址", async ({ page }) => {
  await page.goto("/#/login?redirect=https://example.test");
  await page.route("**/api/auth/login", (route) => route.abort());
  await page
    .getByRole("textbox", { name: "账号", exact: true })
    .fill(process.env.E2E_ADMIN_USERNAME);
  await page
    .getByLabel("密码", { exact: true })
    .fill(process.env.E2E_ADMIN_PASSWORD);
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(
    page.getByText("无法连接服务器，请检查网络或稍后重试"),
  ).toBeVisible();
  await page.unroute("**/api/auth/login");
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("heading", { name: /欢迎回来/ })).toBeVisible();
});
test("已有会话遇到身份查询断网后可恢复，退出失败可重试", async ({ page }) => {
  await login(
    page,
    process.env.E2E_ADMIN_USERNAME,
    process.env.E2E_ADMIN_PASSWORD,
  );
  await expect(page.getByRole("heading", { name: /欢迎回来/ })).toBeVisible();
  await page.route("**/api/auth/me", (route) => route.abort());
  await page.reload();
  await expect(page.getByText("暂时无法获取身份，请重试连接")).toBeVisible();
  await page.unroute("**/api/auth/me");
  await page.getByRole("button", { name: "重试连接", exact: true }).click();
  await expect(page.getByRole("heading", { name: /欢迎回来/ })).toBeVisible();
  await page.route("**/api/auth/logout", (route) => route.abort());
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(
    page.getByText("无法连接服务器，请检查网络或稍后重试"),
  ).toBeVisible();
  expect(
    await page.evaluate(() => sessionStorage.getItem("compliance.accessToken")),
  ).toBeTruthy();
  await page.unroute("**/api/auth/logout");
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page).toHaveURL(/login/);
});
test("后端角色调整在下一次导航更新菜单与访问权限", async ({
  page,
  request,
}) => {
  await login(page, "browser-legal", process.env.E2E_USER_PASSWORD);
  await expect(
    page.getByRole("menuitem", { name: "制度管理", exact: true }),
  ).toBeVisible();
  const adminReply = await request.post("/api/auth/login", {
    data: {
      username: process.env.E2E_ADMIN_USERNAME,
      password: process.env.E2E_ADMIN_PASSWORD,
    },
  });
  const headers = {
    Authorization: `Bearer ${(await adminReply.json()).data.accessToken}`,
  };
  const list = await request.get("/api/users", { headers });
  const legal = (await list.json()).data.records.find(
    (user) => user.username === "browser-legal",
  );
  try {
    expect(
      (
        await request.put(`/api/users/${legal.id}/roles`, {
          headers,
          data: { roles: ["EMPLOYEE"] },
        })
      ).ok(),
    ).toBe(true);
    await page.getByRole("menuitem", { name: "制度管理", exact: true }).click();
    await expect(
      page.getByRole("main").getByText("无访问权限", { exact: true }),
    ).toBeVisible();
    await expect(
      page.getByRole("menuitem", { name: "制度管理", exact: true }),
    ).toHaveCount(0);
  } finally {
    await request.put(`/api/users/${legal.id}/roles`, {
      headers,
      data: { roles: ["LEGAL"] },
    });
    await request.post("/api/auth/logout", { headers });
  }
});
