"""Internal dense retrieval. Java owns authorization and publication state."""
import hmac
import os
import threading
import uuid
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from fastembed import TextEmbedding
from qdrant_client import QdrantClient, models

MODEL = "BAAI/bge-small-zh-v1.5"
MODEL_REVISION = "46fbe35fd4374a00fee7de77dfddaeb6dd6a2c59"
COLLECTION = "policy_bge_small_zh_v1_5_512"


class Chunk(BaseModel):
    model_config = ConfigDict(extra="forbid")
    key: uuid.UUID
    text: str = Field(min_length=1, max_length=400)
    sourceIndex: int = Field(ge=0)
    pageNumber: int | None = Field(default=None, gt=0)
    location: str = Field(max_length=255)
    sectionTitle: str | None = Field(default=None, max_length=255)


class IndexRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    documentId: int = Field(gt=0)
    documentVersionId: int = Field(gt=0)
    generation: uuid.UUID
    roles: list[str] = Field(max_length=3)
    departmentIds: list[int] = Field(max_length=100)
    chunks: list[Chunk] = Field(min_length=1, max_length=10000)


class SearchRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    query: str = Field(min_length=1, max_length=500)
    keys: list[uuid.UUID] = Field(max_length=10000)
    topK: int = Field(default=5, ge=1, le=20)
    minScore: float = Field(default=0.5, ge=0, le=1)


@asynccontextmanager
async def lifespan(app):
    secret = os.environ.get("RETRIEVAL_SECRET", "")
    if len(secret) < 32:
        raise RuntimeError("RETRIEVAL_SECRET must contain at least 32 characters")
    app.state.secret = secret
    app.state.lock = threading.Lock()
    cache = Path(os.environ.get("EMBEDDING_CACHE", "../.cache/embedding"))
    snapshot = cache / "models--Qdrant--bge-small-zh-v1.5" / "snapshots" / MODEL_REVISION
    if not snapshot.is_dir():
        raise RuntimeError("Pinned embedding snapshot missing; run scripts/prepare-embedding.py first")
    app.state.embedding = TextEmbedding(model_name=MODEL, specific_model_path=str(snapshot), local_files_only=True, threads=2)
    app.state.db = QdrantClient(path=str(Path(os.environ.get("VECTOR_STORAGE_ROOT", "../data/vectors")).resolve()), force_disable_check_same_thread=True)
    if not app.state.db.collection_exists(COLLECTION):
        app.state.db.create_collection(COLLECTION, vectors_config=models.VectorParams(size=512, distance=models.Distance.COSINE))
    yield
    app.state.db.close()


app = FastAPI(title="Internal policy retrieval", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


def authorize(secret):
    if not hmac.compare_digest(secret, app.state.secret):
        raise HTTPException(401, detail={"errorCode": "UNAUTHORIZED"})


@app.get("/health")
def health():
    return {"status": "UP", "model": MODEL, "dimension": 512}


@app.post("/internal/index")
def index(request: IndexRequest, x_retrieval_secret: str = Header(default="")):
    authorize(x_retrieval_secret)
    if len({c.key for c in request.chunks}) != len(request.chunks) or any(not c.text.strip() for c in request.chunks):
        raise HTTPException(422, detail={"errorCode": "INVALID_CHUNKS"})
    if not app.state.lock.acquire(blocking=False):
        raise HTTPException(429, detail={"errorCode": "RETRIEVAL_BUSY"})
    try:
        # Upsert stable generation keys: retries never append duplicate effective entries.
        vectors = app.state.embedding.passage_embed([c.text for c in request.chunks], batch_size=32)
        points = [models.PointStruct(id=str(c.key), vector=v.tolist(), payload={
            **c.model_dump(mode="json"), "documentId": request.documentId,
            "documentVersionId": request.documentVersionId, "generation": str(request.generation),
            "knowledgeDomain": "POLICY", "roles": request.roles, "departmentIds": request.departmentIds,
            "embeddingModel": MODEL,
        }) for c, v in zip(request.chunks, vectors, strict=True)]
        app.state.db.upsert(COLLECTION, points=points, wait=True)
        return {"generation": str(request.generation), "model": MODEL, "keys": [str(c.key) for c in request.chunks]}
    finally:
        app.state.lock.release()


@app.post("/internal/search")
def search(request: SearchRequest, x_retrieval_secret: str = Header(default="")):
    authorize(x_retrieval_secret)
    if not request.keys:
        return {"model": MODEL, "hits": []}
    if not app.state.lock.acquire(blocking=False):
        raise HTTPException(429, detail={"errorCode": "RETRIEVAL_BUSY"})
    try:
        vector = next(app.state.embedding.query_embed(request.query)).tolist()
        hits = app.state.db.query_points(COLLECTION, query=vector,
            query_filter=models.Filter(must=[models.HasIdCondition(has_id=[str(k) for k in request.keys])]),
            limit=request.topK, score_threshold=request.minScore, with_payload=False).points
        return {"model": MODEL, "hits": [{"key": str(h.id), "score": h.score} for h in hits]}
    finally:
        app.state.lock.release()
