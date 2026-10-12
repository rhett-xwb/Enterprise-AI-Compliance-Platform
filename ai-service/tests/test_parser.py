import hashlib
import io
import os
import uuid
import zipfile

import pymupdf
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.parser import ParseFailure, parse
from app.main import app


def request(tmp_path, data, mime="application/pdf"):
    key = str(uuid.uuid4()) + ".bin"
    (tmp_path / key).write_bytes(data)
    return dict(taskId=1, documentVersionId=2, storageKey=key, sizeBytes=len(data),
                contentType=mime, contentSha256=hashlib.sha256(data).hexdigest())


def pdf(text=True, pages=2):
    doc = pymupdf.open()
    for number in range(pages):
        page = doc.new_page()
        if text: page.insert_text((50, 80), f"Page {number+1}: Travel reimbursement requires approval.")
    return doc.tobytes()


def docx():
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as z:
        z.writestr("word/document.xml", '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>差旅制度</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>住宿标准</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>')
    return data.getvalue()


def test_pdf_preserves_pages_without_ocr(tmp_path):
    def forbidden(image): raise AssertionError("electronic text should not use OCR")
    result = parse(request(tmp_path, pdf()), tmp_path, ocr_fn=forbidden)
    assert [p["pageNumber"] for p in result["pages"]] == [1, 2]
    assert all(p["method"] == "TEXT" for p in result["pages"])
    assert "approval" in result["pages"][0]["text"]


def test_short_electronic_text_is_not_replaced_with_ocr(tmp_path):
    doc = pymupdf.open(); doc.new_page().insert_text((50, 80), "OK")
    def forbidden(image): raise AssertionError("short electronic text must be preserved")
    result = parse(request(tmp_path, doc.tobytes()), tmp_path, ocr_fn=forbidden)
    assert result["pages"][0]["text"] == "OK"


def test_scanned_pdf_and_image_dispatch_ocr_and_quality_warnings(tmp_path):
    result = parse(request(tmp_path, pdf(False)), tmp_path, ocr_fn=lambda image: ("识别文字", 0.6))
    assert all(p["warnings"] == ["LOW_CONFIDENCE"] for p in result["pages"])
    data = io.BytesIO(); Image.new("RGB", (100, 100), "white").save(data, format="PNG")
    result = parse(request(tmp_path, data.getvalue(), "image/png"), tmp_path, ocr_fn=lambda image: ("图片文字", 0.99))
    assert result["pages"][0]["pageNumber"] == 1
    assert result["pages"][0]["confidence"] == 0.99


def test_docx_keeps_paragraphs_and_table_order_without_fabricated_pages(tmp_path):
    result = parse(request(tmp_path, docx(), "application/vnd.openxmlformats-officedocument.wordprocessingml.document"), tmp_path)
    assert [p["text"] for p in result["pages"]] == ["差旅制度", "住宿标准"]
    assert all(p["pageNumber"] is None for p in result["pages"])
    assert result["pages"][1]["location"] == "段落 2"
    assert result["pages"][1]["sectionTitle"] == "差旅制度"


@pytest.mark.parametrize("case,code", [("digest", "FILE_INTEGRITY"), ("path", "FILE_REFERENCE_INVALID"), ("pages", "PAGE_LIMIT"), ("empty", "EMPTY_DOCUMENT")])
def test_limits_and_invalid_references(tmp_path, case, code):
    body = request(tmp_path, pdf(False) if case == "empty" else pdf())
    if case == "digest": body["contentSha256"] = "0" * 64
    if case == "path": body["storageKey"] = "../secret"
    with pytest.raises(ParseFailure) as error:
        parse(body, tmp_path, max_pages=1 if case == "pages" else 100, ocr_fn=lambda image: ("", None))
    assert error.value.code == code


def test_fastapi_auth_schema_failure_and_real_electronic_parse(tmp_path, monkeypatch):
    monkeypatch.setenv("PARSER_SECRET", "test-internal-secret-" * 3)
    monkeypatch.setenv("DOCUMENT_STORAGE_ROOT", str(tmp_path))
    with TestClient(app) as client:
        body = request(tmp_path, pdf())
        assert client.post("/internal/parse", json=body).status_code == 401
        headers = {"X-Parser-Secret": os.environ["PARSER_SECRET"]}
        assert client.post("/internal/parse", json={**body, "url": "http://example.com"}, headers=headers).status_code == 422
        response = client.post("/internal/parse", json=body, headers=headers)
        assert response.status_code == 200, response.text
        assert response.json()["pages"][1]["pageNumber"] == 2
        bad = request(tmp_path, b"not a pdf")
        response = client.post("/internal/parse", json=bad, headers=headers)
        assert response.status_code == 422
        assert response.json()["detail"]["errorCode"] == "PARSE_FAILED"
        app.state.slots.acquire()
        try:
            assert client.post("/internal/parse", json=body, headers=headers).status_code == 429
        finally: app.state.slots.release()


def test_hard_timeout_releases_capacity(tmp_path, monkeypatch):
    monkeypatch.setenv("PARSER_SECRET", "test-internal-secret-" * 3)
    monkeypatch.setenv("DOCUMENT_STORAGE_ROOT", str(tmp_path))
    with TestClient(app) as client:
        app.state.timeout = 0.00001
        response = client.post("/internal/parse", json=request(tmp_path, pdf()), headers={"X-Parser-Secret": os.environ["PARSER_SECRET"]})
        assert response.status_code == 504
        assert response.json()["detail"]["errorCode"] == "PARSE_TIMEOUT"
        assert app.state.slots.acquire(blocking=False)
        app.state.slots.release()
