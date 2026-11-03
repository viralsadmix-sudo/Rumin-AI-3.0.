import boto3
from botocore.client import Config
from .config import settings

def client():
    s = settings()
    return boto3.client("s3", endpoint_url=s.s3_endpoint_url,
        aws_access_key_id=s.s3_access_key_id, aws_secret_access_key=s.s3_secret_access_key,
        region_name=s.s3_region, config=Config(signature_version="s3v4"))

def put_bytes(key: str, data: bytes, content_type: str):
    client().put_object(Bucket=settings().s3_bucket, Key=key, Body=data, ContentType=content_type)

def get_bytes(key: str) -> bytes:
    obj = client().get_object(Bucket=settings().s3_bucket, Key=key)
    return obj["Body"].read()

def signed_url(key: str, expires: int = 3600) -> str:
    return client().generate_presigned_url("get_object", Params={"Bucket": settings().s3_bucket, "Key": key}, ExpiresIn=expires)

def put_fileobj(key: str, fileobj, content_type: str):
    client().upload_fileobj(fileobj, settings().s3_bucket, key, ExtraArgs={"ContentType": content_type})

def download_file(key: str, destination):
    client().download_file(settings().s3_bucket, key, str(destination))

def upload_file(key: str, source, content_type: str):
    client().upload_file(str(source), settings().s3_bucket, key, ExtraArgs={"ContentType": content_type})
