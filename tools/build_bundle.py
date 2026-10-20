# -*- coding: utf-8 -*-
"""打包并签名热更 bundle。

用法:
  python tools/build_bundle.py --src bundle_src --out bundle --key keys/bundle_signing.pem \
      --version 1 --min-app 1 --url-base https://cdn.jsdelivr.net/gh/USER/REPO@main/bundle

签名载荷(UTF-8, 换行分隔, 顺序固定):
  v1\n{bundle_version}\n{min_app_version}\n{published_at}\n{zip_sha256}\n{files_digest}\n{url}
"""
import argparse, base64, datetime, hashlib, json, os, sys, zipfile
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

SCHEMA = 1

def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()

def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--key", required=True)
    ap.add_argument("--version", type=int, required=True)
    ap.add_argument("--min-app", type=int, default=1)
    ap.add_argument("--url-base", required=True)
    ap.add_argument("--notes", default="")
    ap.add_argument("--published", default=None)
    a = ap.parse_args()

    files = []
    for dp, dn, fn in os.walk(a.src):
        for f in fn:
            fp = os.path.join(dp, f)
            rel = os.path.relpath(fp, a.src).replace("\\", "/")
            if rel == "manifest.json":
                continue
            files.append({"path": rel, "sha256": sha256_file(fp), "bytes": os.path.getsize(fp)})
    if not files:
        print("src 为空", file=sys.stderr); return 2
    files.sort(key=lambda x: x["path"])

    canon = json.dumps(files, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    files_digest = sha256_bytes(canon.encode())

    os.makedirs(a.out, exist_ok=True)
    zip_name = "bundle_v%d.zip" % a.version
    zip_path = os.path.join(a.out, zip_name)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for f in files:
            src = os.path.join(a.src, f["path"])
            zi = zipfile.ZipInfo(f["path"], date_time=(1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            z.writestr(zi, open(src, "rb").read())
    zip_sha = sha256_file(zip_path)

    url = a.url_base.rstrip("/") + "/" + zip_name
    published = a.published or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    payload = "\n".join(["v1", str(a.version), str(a.min_app), published, zip_sha, files_digest, url])
    sk = serialization.load_pem_private_key(open(a.key, "rb").read(), password=None)
    if not isinstance(sk, Ed25519PrivateKey):
        print("私钥不是 Ed25519", file=sys.stderr); return 3
    sig = base64.b64encode(sk.sign(payload.encode())).decode()

    manifest = {
        "schema": SCHEMA,
        "bundle_version": a.version,
        "min_app_version": a.min_app,
        "published_at": published,
        "url": url,
        "zip_sha256": zip_sha,
        "files_digest": files_digest,
        "files": files,
        "notes": a.notes,
        "sig_ed25519": sig,
    }
    mp = os.path.join(a.out, "manifest.json")
    open(mp, "w", encoding="utf-8", newline="\n").write(json.dumps(manifest, ensure_ascii=False, indent=1))
    print("zip      :", zip_path, "%.1f KB" % (os.path.getsize(zip_path) / 1024))
    print("zip_sha256:", zip_sha)
    print("files    :", len(files), "个, digest =", files_digest[:16], "...")
    print("manifest :", mp)
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
