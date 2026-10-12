// Called only by the isolated acceptance harness; outputs non-secret document IDs.
import fs from "node:fs";
function pdf(text) {
  const stream = `BT /F1 12 Tf 50 750 Td (${text}) Tj ET`;
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
  ];
  let content = "%PDF-1.7\n",
    offsets = [0];
  objects.forEach((object, index) => {
    offsets.push(content.length);
    content += `${index + 1} 0 obj\n${object}\nendobj\n`;
  });
  const xref = content.length;
  content += `xref\n0 6\n0000000000 65535 f \n${offsets
    .slice(1)
    .map((offset) => `${String(offset).padStart(10, "0")} 00000 n \n`)
    .join("")}trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(content);
}
const url = process.env.API_PROXY_TARGET;
async function call(route, options = {}) {
  const response = await fetch(url + route, options);
  if (!response.ok)
    throw new Error(`Fixture request failed: ${response.status}`);
  return (await response.json()).data;
}
const auth = await call("/api/auth/login", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    username: process.env.E2E_ADMIN_USERNAME,
    password: process.env.E2E_ADMIN_PASSWORD,
  }),
});
const headers = { Authorization: `Bearer ${auth.accessToken}` };
const version = (number) => ({
  versionNo: number,
  effectiveAt: "2026-01-01T00:00:00",
  expiresAt: null,
});
async function upload(route, metadata, text) {
  const form = new FormData();
  form.append(
    "metadata",
    new Blob([JSON.stringify(metadata)], { type: "application/json" }),
  );
  form.append(
    "file",
    new Blob([pdf(text)], { type: "application/pdf" }),
    "policy.pdf",
  );
  return call(route, { method: "POST", headers, body: form });
}
const main = await upload(
  "/api/documents",
  {
    document: {
      title: "差旅制度验收",
      roles: ["ADMIN", "LEGAL", "EMPLOYEE"],
      departmentIds: [],
    },
    version: version("v1"),
  },
  "Travel policy version 1",
);
const id = main.document.id,
  v1 = main.versions[0].id;
const second = await upload(
  `/api/documents/${id}/versions`,
  version("v2"),
  "Travel policy version 2",
);
const restricted = await upload(
  "/api/documents",
  {
    document: { title: "仅法务制度验收", roles: ["LEGAL"], departmentIds: [] },
    version: version("v1"),
  },
  "Legal only policy",
);
fs.writeFileSync(
  process.env.E2E_EVIDENCE + "/stage4-fixtures.json",
  JSON.stringify({
    id,
    v1,
    v2: second.versions[0].id,
    restrictedId: restricted.document.id,
    restrictedVersion: restricted.versions[0].id,
  }),
);
await call("/api/auth/logout", { method: "POST", headers });
