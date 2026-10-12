import uuid
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from app.retrieval import app, COLLECTION


@pytest.fixture
def retrieval_env(monkeypatch, tmp_path):
    monkeypatch.setenv("RETRIEVAL_SECRET", "retrieval-test-secret-32-characters-only")
    monkeypatch.setenv("VECTOR_STORAGE_ROOT", str(tmp_path / "vectors"))
    monkeypatch.setenv("EMBEDDING_CACHE", str(Path(__file__).resolve().parents[2] / ".cache" / "embedding"))
    return {"X-Retrieval-Secret": "retrieval-test-secret-32-characters-only"}


def test_real_chinese_recall_idempotency_allowlist_and_persistence(retrieval_env):
    keys = [str(uuid.uuid4()) for _ in range(3)]
    texts = ["出差前必须提交审批申请，主管同意后才能出发。", "报销时需要提供发票和费用明细。", "员工请假必须向主管申请，年假按工作年限确定。"]
    request = {"documentId": 1, "documentVersionId": 1, "generation": str(uuid.uuid4()), "roles": ["EMPLOYEE"], "departmentIds": [],
               "chunks": [{"key": k, "text": t, "sourceIndex": i, "location": f"段落 {i+1}"} for i, (k, t) in enumerate(zip(keys, texts))]}
    with TestClient(app) as client:
        for _ in range(2):
            assert client.post("/internal/index", json=request, headers=retrieval_env).status_code == 200
        assert app.state.db.count(COLLECTION, exact=True).count == 3
        questions = ["出差前要办理什么手续？", "报销需要提交什么凭证？", "员工如何申请年假？"]
        for question, key in zip(questions, keys):
            reply = client.post("/internal/search", json={"query": question, "keys": keys, "topK": 1, "minScore": 0.5}, headers=retrieval_env)
            assert reply.status_code == 200
            assert reply.json()["hits"][0]["key"] == key
        # Unauthorized highest-scoring candidate must not crowd out authorized entries.
        reply = client.post("/internal/search", json={"query": questions[0], "keys": [keys[1]], "minScore": 0}, headers=retrieval_env)
        assert [h["key"] for h in reply.json()["hits"]] == [keys[1]]
        assert client.post("/internal/search", json={"query": "出差", "keys": []}, headers=retrieval_env).json()["hits"] == []
    with TestClient(app) as client:
        assert app.state.db.count(COLLECTION, exact=True).count == 3
        assert client.post("/internal/search", json={"query": "出差前要办理什么手续？", "keys": keys, "topK": 1}, headers=retrieval_env).json()["hits"][0]["key"] == keys[0]


def test_internal_auth_validation_and_busy_fail_closed(retrieval_env):
    with TestClient(app) as client:
        body = {"query": "出差", "keys": [str(uuid.uuid4())]}
        assert client.post("/internal/search", json=body).status_code == 401
        assert client.post("/internal/search", json={**body, "roles": ["ADMIN"]}, headers=retrieval_env).status_code == 422
        assert client.post("/internal/search", json={**body, "topK": 100}, headers=retrieval_env).status_code == 422
        with app.state.lock:
            assert client.post("/internal/search", json=body, headers=retrieval_env).status_code == 429
