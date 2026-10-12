import hmac
import multiprocessing
import os
import threading
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field

from .parser import child_parse


class ParseRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    documentVersionId: int = Field(gt=0)
    taskId: int = Field(gt=0)
    storageKey: str = Field(pattern=r"^[a-f0-9-]{36}\.bin$")
    contentSha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    contentType: str = Field(max_length=100)
    sizeBytes: int = Field(gt=0, le=10 * 1024 * 1024)


@asynccontextmanager
async def lifespan(app):
    secret = os.environ.get("PARSER_SECRET", "")
    if len(secret) < 32:
        raise RuntimeError("PARSER_SECRET must contain at least 32 characters")
    app.state.secret = secret
    app.state.root = str(Path(os.environ["DOCUMENT_STORAGE_ROOT"]).resolve(strict=True))
    app.state.timeout = int(os.environ.get("PARSER_TIMEOUT_SECONDS", "180"))
    app.state.max_pages = int(os.environ.get("PARSER_MAX_PAGES", "100"))
    app.state.slots = threading.BoundedSemaphore(int(os.environ.get("PARSER_CONCURRENCY", "1")))
    if not 1 <= app.state.timeout <= 600 or not 1 <= app.state.max_pages <= 100:
        raise RuntimeError("Invalid parser limits")
    yield


app = FastAPI(title="Internal document parser", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)


@app.get("/health")
def health():
    return {"status": "UP"}


@app.post("/internal/parse")
def parse(request: ParseRequest, x_parser_secret: str = Header(default="")):
    if not hmac.compare_digest(x_parser_secret, app.state.secret):
        raise HTTPException(401, detail={"errorCode": "UNAUTHORIZED"})
    if not app.state.slots.acquire(blocking=False):
        raise HTTPException(429, detail={"errorCode": "PARSER_BUSY"})
    ctx = multiprocessing.get_context("spawn")
    receiver, sender = ctx.Pipe(duplex=False)
    process = ctx.Process(target=child_parse, args=(request.model_dump(), app.state.root, app.state.max_pages, sender))
    try:
        process.start()
        sender.close()
        if not receiver.poll(app.state.timeout):
            raise HTTPException(504, detail={"errorCode": "PARSE_TIMEOUT"})
        try:
            reply = receiver.recv()
        except EOFError:
            raise HTTPException(422, detail={"errorCode": "PARSER_CRASH"})
        if "errorCode" in reply:
            raise HTTPException(422, detail={"errorCode": reply["errorCode"]})
        return reply["result"]
    finally:
        if process.pid is not None:
            if process.is_alive(): process.terminate()
            process.join(timeout=5)
            if process.is_alive(): process.kill(); process.join(timeout=5)
        receiver.close()
        sender.close()
        app.state.slots.release()
