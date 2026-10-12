"""Download a fixed public model snapshot into the project cache before service startup."""
from pathlib import Path
from huggingface_hub import snapshot_download

root = Path(__file__).resolve().parent.parent
snapshot_download("Qdrant/bge-small-zh-v1.5", revision="46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59",
                  cache_dir=str(root / ".cache" / "embedding"),
                  allow_patterns=["config.json", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json", "model_optimized.onnx"])
print("Pinned Chinese embedding snapshot ready")
