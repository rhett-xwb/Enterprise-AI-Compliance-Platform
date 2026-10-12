"""Bounded, source-preserving extraction. No remote file fetching."""
import hashlib
import io
import re
import zipfile
from functools import lru_cache
from pathlib import Path

import pymupdf
from defusedxml import ElementTree
from PIL import Image, ImageOps


class ParseFailure(Exception):
    def __init__(self, code):
        self.code = code


def clean(text):
    return "\n".join(line.rstrip() for line in text.replace("\x00", "").replace("\r", "").splitlines()).strip()


@lru_cache(maxsize=1)
def ocr_engine():
    from paddleocr import PaddleOCR
    return PaddleOCR(device="cpu", enable_mkldnn=False, use_doc_orientation_classify=False,
                       use_doc_unwarping=False, use_textline_orientation=False,
                       text_detection_model_name="PP-OCRv5_mobile_det",
                       text_recognition_model_name="PP-OCRv5_mobile_rec")


def ocr(image):
    # One engine per isolated document process, shared by its pages.
    import numpy as np
    result = list(ocr_engine().predict(np.asarray(image.convert("RGB"))))
    texts, scores = [], []
    for item in result:
        texts.extend(item.get("rec_texts", []))
        scores.extend(float(v) for v in item.get("rec_scores", []))
    return clean("\n".join(texts)), (sum(scores) / len(scores) if scores else None)


def source(index, page, location, text, method, confidence=None, section=None):
    if len(text) > 100_000:
        raise ParseFailure("TEXT_LIMIT")
    warnings = []
    if not text:
        warnings.append("EMPTY_SOURCE")
    elif method == "OCR" and (confidence is None or confidence < 0.85):
        warnings.append("LOW_CONFIDENCE")
    return dict(sourceIndex=index, pageNumber=page, location=location, text=text,
                method=method, confidence=confidence, sectionTitle=section, warnings=warnings)


def parse(request, root, max_pages=100, ocr_fn=ocr):
    key = request["storageKey"]
    if not re.fullmatch(r"[a-f0-9-]{36}\.bin", key):
        raise ParseFailure("FILE_REFERENCE_INVALID")
    root = Path(root).resolve(strict=True)
    path = root / key
    if path.is_symlink() or not path.is_file() or path.resolve().parent != root:
        raise ParseFailure("FILE_UNAVAILABLE")
    if not 0 < path.stat().st_size <= 10 * 1024 * 1024:
        raise ParseFailure("FILE_SIZE_LIMIT")
    data = path.read_bytes()
    if len(data) != request["sizeBytes"] or hashlib.sha256(data).hexdigest() != request["contentSha256"]:
        raise ParseFailure("FILE_INTEGRITY")
    mime = request["contentType"]
    pages = []
    if mime == "application/pdf":
        with pymupdf.open(stream=data, filetype="pdf") as doc:
            if doc.needs_pass:
                raise ParseFailure("PDF_ENCRYPTED")
            if len(doc) > max_pages:
                raise ParseFailure("PAGE_LIMIT")
            for i, page in enumerate(doc):
                text = clean(page.get_text("text", sort=True))
                confidence, method = None, "TEXT"
                if not text:
                    # 144 DPI; reject abnormal page dimensions before rasterizing.
                    if page.rect.width * page.rect.height * 4 > 40_000_000:
                        raise ParseFailure("IMAGE_LIMIT")
                    pix = page.get_pixmap(matrix=pymupdf.Matrix(2, 2), alpha=False, colorspace=pymupdf.csRGB)
                    text, confidence = ocr_fn(Image.frombytes("RGB", (pix.width, pix.height), pix.samples))
                    method = "OCR"
                pages.append(source(i, i + 1, f"第 {i + 1} 页", text, method, confidence))
    elif mime in ("image/png", "image/jpeg"):
        with Image.open(io.BytesIO(data)) as image:
            if image.format not in ("PNG", "JPEG") or image.width * image.height > 40_000_000:
                raise ParseFailure("IMAGE_LIMIT")
            text, confidence = ocr_fn(ImageOps.exif_transpose(image))
            pages.append(source(0, 1, "第 1 页（图片）", text, "OCR", confidence))
    elif mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            infos = archive.infolist()
            if len(infos) > 1000 or sum(i.file_size for i in infos) > 30 * 1024 * 1024:
                raise ParseFailure("ARCHIVE_LIMIT")
            for info in infos:
                if info.filename.startswith(("/", "\\")) or ".." in info.filename.replace("\\", "/").split("/"):
                    raise ParseFailure("FILE_REFERENCE_INVALID")
            body = ElementTree.fromstring(archive.read("word/document.xml"))
            ns = {"w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main"}
            section = None
            # Include table paragraphs in document order. DOCX has no reliable page map.
            for number, paragraph in enumerate(body.findall(".//w:body//w:p", ns), 1):
                parts = []
                for node in paragraph.iter():
                    tag = node.tag.split("}")[-1]
                    if tag == "t": parts.append(node.text or "")
                    elif tag in ("br", "cr"): parts.append("\n")
                    elif tag == "tab": parts.append("\t")
                text = clean("".join(parts))
                style = paragraph.find("w:pPr/w:pStyle", ns)
                if style is not None and "heading" in str(style.attrib).lower():
                    section = text[:255]
                if text:
                    pages.append(source(len(pages), None, f"段落 {number}", text, "DOCX", section=section))
                if len(pages) > 2000:
                    raise ParseFailure("SOURCE_LIMIT")
    else:
        raise ParseFailure("UNSUPPORTED_TYPE")
    if not any(p["text"] for p in pages):
        raise ParseFailure("EMPTY_DOCUMENT")
    if sum(len(p["text"]) for p in pages) > 2_000_000:
        raise ParseFailure("TEXT_LIMIT")
    return dict(taskId=request["taskId"], documentVersionId=request["documentVersionId"],
                contentSha256=request["contentSha256"], parserVersion="stage5-v1", pages=pages)


def child_parse(request, root, max_pages, pipe):
    try:
        pipe.send({"result": parse(request, root, max_pages)})
    except ParseFailure as exc:
        pipe.send({"errorCode": exc.code})
    except Exception:
        # Never return paths, source text, library traces or secrets in error responses.
        pipe.send({"errorCode": "PARSE_FAILED"})
    finally:
        pipe.close()
