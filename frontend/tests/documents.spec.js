import { test, expect } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
test.skip(process.env.E2E_STAGE4 !== "true", "阶段四隔离验收脚本提供就绪数据");
test("真实 HTTP 拒绝超过上限的文件和伪装 PDF", async ({ request }) => {
  const response = await request.post("/api/auth/login", {
    data: {
      username: process.env.E2E_ADMIN_USERNAME,
      password: process.env.E2E_ADMIN_PASSWORD,
    },
  });
  const headers = {
    Authorization: `Bearer ${(await response.json()).data.accessToken}`,
  };
  const metadata = {
    document: { title: "非法文件验收", roles: ["ADMIN"], departmentIds: [] },
    version: {
      versionNo: "v1",
      effectiveAt: "2026-01-01T00:00:00",
      expiresAt: null,
    },
  };
  try {
    for (const [bytes, expected] of [
      [Buffer.from("not a PDF"), 400],
      [Buffer.alloc(10 * 1024 * 1024 + 1), 413],
    ]) {
      const result = await request.post("/api/documents", {
        headers,
        multipart: {
          metadata: {
            name: "metadata.json",
            mimeType: "application/json",
            buffer: Buffer.from(JSON.stringify(metadata)),
          },
          file: { name: "bad.pdf", mimeType: "application/pdf", buffer: bytes },
        },
      });
      expect(result.status()).toBe(expected);
    }
  } finally {
    await request.post("/api/auth/logout", { headers });
  }
});
async function login(page, role) {
  await page.goto("/#/login");
  await page
    .getByRole("textbox", { name: "账号", exact: true })
    .fill(
      role === "admin" ? process.env.E2E_ADMIN_USERNAME : `browser-${role}`,
    );
  await page
    .getByLabel("密码", { exact: true })
    .fill(
      role === "admin"
        ? process.env.E2E_ADMIN_PASSWORD
        : process.env.E2E_USER_PASSWORD,
    );
  await page.getByRole("button", { name: "登录", exact: true }).click();
  await expect(page.getByRole("heading", { name: /欢迎回来/ })).toBeVisible();
}
function fixture() {
  return JSON.parse(
    fs.readFileSync(
      path.join(process.env.E2E_EVIDENCE, "stage4-fixtures.json"),
      "utf8",
    ),
  );
}
test("员工查询、版本原文件下载与受限制度拒绝", async ({ page, request }) => {
  await login(page, "employee");
  await page.getByRole("menuitem", { name: "制度查询", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: "差旅制度验收", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("cell", { name: "仅法务制度验收", exact: true }),
  ).toHaveCount(0);
  await page
    .getByRole("row")
    .filter({ hasText: "差旅制度验收" })
    .getByRole("button", { name: "查看版本" })
    .click();
  await expect(
    page.getByRole("cell", { name: "v1", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("cell", { name: "v2", exact: true })).toHaveCount(
    0,
  );
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "下载原文件" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe("policy.pdf");
  const bytes = fs.readFileSync(await download.path());
  expect(bytes.toString().startsWith("%PDF-1.7")).toBe(true);
  const token = await page.evaluate(() =>
    sessionStorage.getItem("compliance.accessToken"),
  );
  const f = fixture();
  expect(
    (
      await request.get(
        `/api/documents/${f.restrictedId}/versions/${f.restrictedVersion}/file`,
        { headers: { Authorization: `Bearer ${token}` } },
      )
    ).status(),
  ).toBe(404);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({
    path: path.join(process.env.E2E_EVIDENCE, "documents-mobile.png"),
    fullPage: true,
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
test("法务上传草稿、新版本、范围编辑与删除留痕", async ({ page, request }) => {
  await login(page, "legal");
  await page.getByRole("menuitem", { name: "制度管理", exact: true }).click();
  await page.getByRole("button", { name: "上传新制度" }).click();
  await page
    .getByRole("textbox", { name: "制度名称", exact: true })
    .fill("浏览器上传制度");
  await page.getByRole("textbox", { name: "版本号", exact: true }).fill("v1");
  await page
    .getByRole("dialog")
    .getByRole("combobox")
    .first()
    .fill("2026-01-01 00:00:00");
  await page.getByRole("textbox", { name: "制度名称", exact: true }).click();
  await page.locator('input[type="file"]').setInputFiles({
    name: "浏览器制度.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from("%PDF-1.7\n%%EOF\n"),
  });
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "保存", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "浏览器上传制度", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("cell", { name: "草稿 / 待处理", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "发布", exact: true }),
  ).toBeDisabled();
  await page.getByRole("menuitem", { name: "制度查询", exact: true }).click();
  await expect(page.getByRole("heading", { name: "制度查询", exact: true })).toBeVisible();
  await expect(page.getByRole("cell", { name: "浏览器上传制度", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "上传新版本" })).toHaveCount(0);
  await page.getByRole("menuitem", { name: "制度管理", exact: true }).click();
  await page.getByRole("row").filter({ hasText: "浏览器上传制度" })
    .getByRole("button", { name: "查看版本" }).click();
  await expect(page.getByRole("heading", { name: "浏览器上传制度", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "上传新版本" }).click();
  await page.getByRole("textbox", { name: "版本号", exact: true }).fill("v2");
  await page
    .getByRole("dialog")
    .getByRole("combobox")
    .first()
    .fill("2026-01-02 00:00:00");
  await page.getByRole("textbox", { name: "版本号", exact: true }).click();
  await page.locator('input[type="file"]').setInputFiles({
    name: "v2.pdf",
    mimeType: "application/pdf",
    buffer: Buffer.from("%PDF-1.7\n%%EOF\n"),
  });
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "保存", exact: true })
    .click();
  await expect(
    page.getByRole("cell", { name: "v2", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "编辑范围" }).click();
  await page.getByRole("dialog").getByText("普通员工", { exact: true }).click();
  await expect(
    page.getByRole("checkbox", { name: "普通员工" }),
  ).not.toBeChecked();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "保存", exact: true })
    .click();
  await expect(
    page.getByRole("cell", { name: "修改名称与范围" }),
  ).toBeVisible();
  await page.screenshot({
    path: path.join(process.env.E2E_EVIDENCE, "document-management.png"),
    fullPage: true,
  });
  const token = await page.evaluate(() =>
    sessionStorage.getItem("compliance.accessToken"),
  );
  const list = await request.get("/api/documents?management=true", {
    headers: { Authorization: `Bearer ${token}` },
  });
  const own = (await list.json()).data.records.find(
    (r) => r.title === "浏览器上传制度",
  );
  await page.getByRole("button", { name: "删除制度", exact: true }).click();
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: "浏览器上传制度", exact: true }),
  ).toHaveCount(0);
  expect(
    (
      await request.get(`/api/documents/${own.id}`, {
        headers: { Authorization: `Bearer ${token}` },
      })
    ).status(),
  ).toBe(404);
});
test("管理员发布新版本、历史下载、停用及当前查询排除", async ({
  page,
  request,
}) => {
  await login(page, "admin");
  await page.getByRole("menuitem", { name: "制度管理", exact: true }).click();
  await page
    .getByRole("row")
    .filter({ hasText: "差旅制度验收" })
    .getByRole("button", { name: "查看版本" })
    .click();
  const v2 = page
    .getByRole("row")
    .filter({ has: page.getByRole("cell", { name: "v2", exact: true }) });
  await v2.getByRole("button", { name: "发布", exact: true }).click();
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await expect(v2.getByText("已发布 / 处理就绪")).toBeVisible();
  const v1 = page
    .getByRole("row")
    .filter({ has: page.getByRole("cell", { name: "v1", exact: true }) });
  await expect(v1.getByText("已停用 / 处理就绪")).toBeVisible();
  const downloadPromise = page.waitForEvent("download");
  await v1.getByRole("button", { name: "下载原文件" }).click();
  expect((await downloadPromise).suggestedFilename()).toBe("policy.pdf");
  await v2.getByRole("button", { name: "停用", exact: true }).click();
  await page.getByRole("button", { name: "确认", exact: true }).click();
  await expect(
    page.getByRole("cell", { name: "尚未发布", exact: true }).first(),
  ).toBeVisible();
  const token = await page.evaluate(() =>
    sessionStorage.getItem("compliance.accessToken"),
  );
  const response = await request.get("/api/documents", {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    (await response.json()).data.records.some((r) => r.id === fixture().id),
  ).toBe(false);
});
