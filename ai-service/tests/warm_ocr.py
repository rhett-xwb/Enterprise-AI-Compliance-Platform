import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.parser import ocr
from PIL import Image

if __name__ == "__main__":
    text, confidence = ocr(Image.open(sys.argv[1]))
    print({"text": text, "confidence": confidence})
    if "审批申请" not in text: raise RuntimeError("OCR acceptance text not recognized")
