import secrets
from fastapi import Header, HTTPException
from .config import settings

def require_bearer(authorization: str | None = Header(default=None)):
    expected = settings().api_bearer_token
    supplied = authorization.removeprefix("Bearer ").strip() if authorization else ""
    if not expected or not secrets.compare_digest(supplied, expected):
        raise HTTPException(status_code=401, detail="Unauthorized")
