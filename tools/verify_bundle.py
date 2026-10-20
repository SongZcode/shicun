# -*- coding: utf-8 -*-
"""严格校验 bundle（发布前自检 / 客户端对拍）。

校验项（任一失败 -> 退出码非 0）：
  1. manifest 可解析
  2. Ed25519 签名有效（固定七行载荷）
  3. files_digest 与 files 清单一致
  4. zip 存在且 zip_sha256 一致
  5. zip 内文件集合 == manifest.files 的 path 集合
  6. 逐个文件：内容 sha256 与 bytes 均一致

用法:
  python tools/verify_bundle.py --dir bundle --pubkey-b64 <PUBKEY_B64>
"""
import argparse, base64, hashlib, json, os, sys, zipfile
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--pubkey-b64", required=True)
    a = ap.parse_args()

    fails = []

    def check(ok, msg, detail=""):
        print(("[OK]   " if ok else "[FAIL] ") + msg + (("  -- " + detail) if (detail and not ok) else ""))
        if not ok:
            fails.append(msg)
        return ok

    mpath = os.path.join(a.dir, "manifest.json")
    if not os.path.exists(mpath):
        print("[FAIL] manifest.json 不存在:", mpath)
        return 2
    m = json.load(open(mpath, encoding="utf-8"))
    print("bundle_version =", m.get("bundle_version"), "| files =", len(m.get("files", [])))

    # 1) 签名
    payload = "\n".join(["v1", str(m["bundle_version"]), str(m["min_app_version"]),
                         m["published_at"], m["zip_sha256"], m["files_digest"], m["url"]])
    try:
        pub = Ed25519PublicKey.from_public_bytes(base64.b64decode(a.pubkey_b64))
        pub.verify(base64.b64decode(m["sig_ed25519"]), payload.encode())
        check(True, "Ed25519 签名")
    except Exception as e:
        check(False, "Ed25519 签名", repr(e))

    # 2) files_digest
    canon = json.dumps(m["files"], ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    check(hashlib.sha256(canon.encode()).hexdigest() == m["files_digest"], "files_digest")

    # 3) zip
    zp = os.path.join(a.dir, os.path.basename(m["url"]))
    if not check(os.path.exists(zp), "zip 存在", zp):
        return 1
    h = hashlib.sha256(open(zp, "rb").read()).hexdigest()
    check(h == m["zip_sha256"], "zip_sha256")

    # 4) 文件集合 + 逐文件内容
    with zipfile.ZipFile(zp) as z:
        names = set(z.namelist())
        expect = {f["path"] for f in m["files"]}
        check(names == expect, "zip 文件集合",
              "多出 %s / 缺少 %s" % (sorted(names - expect), sorted(expect - names)))
        for f in m["files"]:
            p = f["path"]
            if p not in names:
                check(False, "文件内容 " + p, "zip 内不存在")
                continue
            data = z.read(p)
            ok_sha = hashlib.sha256(data).hexdigest() == f["sha256"]
            ok_len = len(data) == f["bytes"]
            check(ok_sha and ok_len, "文件内容 " + p,
                  "sha 不一致" if not ok_sha else "长度不一致 %d != %d" % (len(data), f["bytes"]))

    print()
    if fails:
        print("RESULT: FAILED (%d 项)" % len(fails))
        return 1
    print("RESULT: ALL OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
