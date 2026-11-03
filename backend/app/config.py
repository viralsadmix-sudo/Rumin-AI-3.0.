import os
from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
    app_env: str = "production"
    api_bearer_token: str
    redis_url: str
    s3_endpoint_url: str
    s3_bucket: str
    s3_access_key_id: str
    s3_secret_access_key: str
    s3_region: str = "auto"
    elevenlabs_api_key: str
    elevenlabs_model_id: str = "eleven_multilingual_v2"
    gemini_api_key: str = ""
    max_upload_mb: int = 500
    allowed_origins: str = "*"

@lru_cache
def settings() -> Settings:
    return Settings()
