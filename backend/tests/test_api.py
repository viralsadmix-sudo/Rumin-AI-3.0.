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
import app.main as main_module

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

def test_readiness_checks_managed_dependencies(monkeypatch):
    class FakeRedis:
        def ping(self):
            return True
    class FakeStorage:
        def head_bucket(self, **kwargs):
            return {}
    monkeypatch.setattr(main_module.Redis, "from_url", lambda url: FakeRedis())
    monkeypatch.setattr(main_module, "storage_client", lambda: FakeStorage())
    response = client.get("/ready", headers={"Authorization": "Bearer ci-test-token"})
    assert response.status_code == 200
    payload = response.json()
    assert payload["checks"]["redis"] is True
    assert payload["checks"]["object_storage"] is True
    assert payload["checks"]["elevenlabs"] is True

def test_video_upload_accepts_android_octet_stream_mime(monkeypatch):
    stored = {}
    class FakeQueue:
        def enqueue(self, *args, **kwargs):
            return type("Job", (), {"id": kwargs["job_id"]})()
    def fake_put_fileobj(key, fileobj, content_type):
        stored["content_type"] = content_type
    monkeypatch.setattr(main_module, "put_fileobj", fake_put_fileobj)
    monkeypatch.setattr(main_module, "queue", lambda: FakeQueue())
    response = client.post(
        "/v1/video/jobs",
        headers={"Authorization": "Bearer ci-test-token"},
        files={"file": ("phone-video.mp4", b"fake-video-data", "application/octet-stream")},
        data={"prompt": "", "subtitle_language": "auto", "burn_subtitles": "false"},
    )
    assert response.status_code == 200
    assert stored["content_type"] == "video/mp4"
    assert response.json()["status"] == "queued"
