import os
os.environ.setdefault("API_BEARER_TOKEN", "ci-test-token")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("S3_ENDPOINT_URL", "https://example.invalid")
os.environ.setdefault("S3_BUCKET", "test-bucket")
os.environ.setdefault("S3_ACCESS_KEY_ID", "test-access")
os.environ.setdefault("S3_SECRET_ACCESS_KEY", "test-secret")
os.environ.setdefault("ELEVENLABS_API_KEY", "test-elevenlabs-key")
os.environ.setdefault("ALLOWED_ORIGINS", "*")

from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)

def test_health_is_public():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["ok"] is True

def test_studio_api_requires_bearer_token():
    response = client.post("/v1/voice/tts", data={"text":"hello", "voice_id":"test"})
    assert response.status_code == 401

def test_studio_api_rejects_wrong_token():
    response = client.post("/v1/voice/tts", headers={"Authorization":"Bearer wrong"}, data={"text":"hello", "voice_id":"test"})
    assert response.status_code == 401
