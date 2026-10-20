# -*- coding: utf-8 -*-
"""生成 bundle 签名用 Ed25519 密钥对。

用法: python tools/gen_bundle_key.py [--out keys/bundle_signing.pem]
输出: 私钥 PEM（离线保存，绝不入库/下发）；公钥 raw base64（内置进 APK 验签）
"""
import argparse, base64, os, sys
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join("keys", "bundle_signing.pem"))
    a = ap.parse_args()
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    if os.path.exists(a.out):
        print("已存在，拒绝覆盖:", a.out, file=sys.stderr); return 2
    sk = Ed25519PrivateKey.generate()
    pem = sk.private_bytes(serialization.Encoding.PEM,
                           serialization.PrivateFormat.PKCS8,
                           serialization.NoEncryption())
    open(a.out, "wb").write(pem)
    raw = sk.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    print("私钥已写入:", a.out)
    print("PUBKEY_B64=", base64.b64encode(raw).decode())
    print("PUBKEY_HEX=", raw.hex())
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
