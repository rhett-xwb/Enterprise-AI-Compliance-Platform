"""Generate synthetic acceptance files; no enterprise data."""
import io
import sys
import zipfile
from pathlib import Path

import pymupdf
from PIL import Image, ImageDraw, ImageFont


def create(directory):
    directory = Path(directory); directory.mkdir(parents=True, exist_ok=True)
    doc = pymupdf.open()
    for line in ("Travel policy: approval is required before booking.", "Reimbursement policy: keep invoices and receipts."):
        doc.new_page().insert_text((50, 80), line, fontsize=14)
    doc.save(directory / "electronic.pdf")
    image = Image.new("RGB", (1200, 420), "white")
    font_path = Path("C:/Windows/Fonts/msyh.ttc")
    chinese = font_path.exists()
    font = ImageFont.truetype(str(font_path) if chinese else "DejaVuSans.ttf", 48)
    lines = ["差旅报销制度", "出差前必须提交审批申请", "报销时需要提供发票"] if chinese else ["Travel reimbursement policy", "Approval is required", "Keep invoices and receipts"]
    draw = ImageDraw.Draw(image)
    for i, line in enumerate(lines): draw.text((70, 55 + i*110), line, font=font, fill="black")
    image.save(directory / "policy.png")
    image.save(directory / "policy.jpg", quality=95)
    scan = pymupdf.open(); scan.new_page(width=600, height=210).insert_image(pymupdf.Rect(0, 0, 600, 210), filename=str(directory / "policy.png")); scan.save(directory / "scan.pdf")
    with zipfile.ZipFile(directory / "policy.docx", "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
        z.writestr("_rels/.rels", '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
        z.writestr("word/document.xml", '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>差旅制度</w:t></w:r></w:p><w:p><w:r><w:t>出差前必须提交审批申请</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>报销时需要提供发票</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>')
    (directory / "broken.pdf").write_bytes(b"%PDF-1.7\ninvalid body\n%%EOF")


if __name__ == "__main__": create(sys.argv[1])
