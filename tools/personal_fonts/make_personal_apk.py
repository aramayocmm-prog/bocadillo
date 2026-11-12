#!/usr/bin/env python3
"""
MAC Editor para MEMES 1.1.0 — personal APK with the owner's own fonts.

The published APK (GitHub) only ships fonts whose licence allows redistribution. The owner's
other fonts must never be committed to the public repository or published, so they are added
to a copy of the APK on the owner's side:

  python3 make_personal_apk.py --apk MAC_Editor_para_MEMES-1.1.0-debug.apk \
      --fonts <folder with the fonts to include> --keystore app/debug.keystore \
      --out MAC_Editor_para_MEMES-1.1.0-personal.apk

What it does:
  * copies every entry of the APK unchanged (compressed data copied byte for byte), drops the
    old v1 signature files (META-INF/*.SF/*.RSA/*.EC/*.DSA/MANIFEST.MF) and the old signing block;
  * adds the fonts as assets/fonts/personal/<file> (stored, 4-byte aligned) and an index.json
    with family / style / SHA-1 read from each font's own name table (same rules as the app's
    FontMeta), which the app lists as «Mi biblioteca de fuentes»;
  * signs with APK Signature Scheme v2 using the same key as the APK (the repository's debug
    keystore), so it installs over 1.0 / 1.1 without uninstalling and without losing data.

Needs Python 3 and the "cryptography" package. No Android SDK.
"""
import argparse, hashlib, io, json, os, struct, sys, zlib

# ------------------------------------------------------------------ font names (as FontMeta)

def font_names(b: bytes):
    def u16(o): return (b[o] << 8) | b[o + 1]
    def u32(o): return (u16(o) << 16) | u16(o + 2)
    try:
        num = u16(4); name_off = -1
        for i in range(num):
            rec = 12 + 16 * i
            if b[rec:rec + 4] == b"name": name_off = u32(rec + 8); break
        if name_off < 0: return None
        count = u16(name_off + 2); strings = name_off + u16(name_off + 4)
        found = {}
        for i in range(count):
            r = name_off + 6 + 12 * i
            platform, encoding, lang, nid, ln, off = u16(r), u16(r + 2), u16(r + 4), u16(r + 6), u16(r + 8), u16(r + 10)
            if nid not in (1, 2, 16, 17): continue
            raw = b[strings + off: strings + off + ln]
            if platform == 3 and encoding in (0, 1) or platform == 0: text = raw.decode("utf-16-be", "replace")
            elif platform == 1 and encoding == 0: text = raw.decode("latin-1")
            else: continue
            text = text.strip()
            if text and (nid not in found or (platform == 3 and lang == 0x409)): found[nid] = text
        fam = found.get(16) or found.get(1)
        if not fam: return None
        return fam, found.get(17) or found.get(2) or ""
    except Exception:
        return None

# ------------------------------------------------------------------ zip reading / writing

def find_eocd(data: bytes):
    i = data.rfind(b"PK\x05\x06")
    if i < 0: raise SystemExit("not a zip")
    return i

def read_entries(data: bytes):
    e = find_eocd(data)
    n = struct.unpack_from("<H", data, e + 10)[0]
    cd_size, cd_off = struct.unpack_from("<II", data, e + 12)
    out = []; p = cd_off
    for _ in range(n):
        (sig, vmade, vneed, flags, method, mtime, mdate, crc, csize, usize, nlen, elen, clen,
         disk, iattr, eattr, lho) = struct.unpack_from("<IHHHHHHIIIHHHHHII", data, p)
        assert sig == 0x02014b50
        name = data[p + 46: p + 46 + nlen]
        cextra = data[p + 46 + nlen: p + 46 + nlen + elen]
        comment = data[p + 46 + nlen + elen: p + 46 + nlen + elen + clen]
        p += 46 + nlen + elen + clen
        lnlen, lelen = struct.unpack_from("<HH", data, lho + 26)
        start = lho + 30 + lnlen + lelen
        out.append(dict(name=name, vmade=vmade, vneed=vneed, flags=flags & ~0x08, method=method, mtime=mtime, mdate=mdate,
                        crc=crc, csize=csize, usize=usize, cextra=cextra, comment=comment, iattr=iattr, eattr=eattr,
                        data=data[start:start + csize]))
    return out, cd_off

def write_zip(entries):
    body = io.BytesIO(); cd = io.BytesIO()
    for e in entries:
        name = e["name"]
        off = body.tell()
        align = 4
        if name.endswith(b".so"): align = 16384
        extra = b""
        if e["method"] == 0:
            base = off + 30 + len(name)
            pad = (-(base + 6)) % align
            extra = struct.pack("<HHH", 0xD935, 2 + pad, align) + b"\0" * pad
        body.write(struct.pack("<IHHHHHIIIHH", 0x04034b50, e["vneed"], e["flags"], e["method"], e["mtime"], e["mdate"],
                               e["crc"], e["csize"], e["usize"], len(name), len(extra)))
        body.write(name); body.write(extra); body.write(e["data"])
        cd.write(struct.pack("<IHHHHHHIIIHHHHHII", 0x02014b50, e["vmade"], e["vneed"], e["flags"], e["method"], e["mtime"], e["mdate"],
                             e["crc"], e["csize"], e["usize"], len(name), len(e["cextra"]), len(e["comment"]), 0, e["iattr"], e["eattr"], off))
        cd.write(name); cd.write(e["cextra"]); cd.write(e["comment"])
    entries_bytes = body.getvalue(); cd_bytes = cd.getvalue()
    eocd = struct.pack("<IHHHHIIH", 0x06054b50, 0, 0, len(entries), len(entries), len(cd_bytes), len(entries_bytes), 0)
    return entries_bytes, cd_bytes, eocd

def stored_entry(name: str, data: bytes):
    return dict(name=name.encode("utf-8"), vmade=20, vneed=10, flags=0x800, method=0, mtime=0, mdate=0x21,  # 1980-01-01
                crc=zlib.crc32(data) & 0xFFFFFFFF, csize=len(data), usize=len(data), cextra=b"", comment=b"", iattr=0, eattr=0, data=data)

# ------------------------------------------------------------------ APK Signature Scheme v2

def lp(b: bytes) -> bytes: return struct.pack("<I", len(b)) + b

def v2_digest(sections):
    chunks = []
    for s in sections:
        for i in range(0, len(s), 1 << 20):
            c = s[i:i + (1 << 20)]
            chunks.append(hashlib.sha256(b"\xa5" + struct.pack("<I", len(c)) + c).digest())
    return hashlib.sha256(b"\x5a" + struct.pack("<I", len(chunks)) + b"".join(chunks)).digest()

def sign_v2(entries_bytes, cd_bytes, eocd, key, cert_der):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding, rsa, ec
    digest = v2_digest([entries_bytes, cd_bytes, eocd])
    if isinstance(key, rsa.RSAPrivateKey): alg = 0x0103
    elif isinstance(key, ec.EllipticCurvePrivateKey): alg = 0x0201
    else: raise SystemExit("unsupported key type")
    digests = lp(struct.pack("<I", alg) + lp(digest))
    signed_data = lp(digests) + lp(lp(cert_der)) + lp(b"")
    if alg == 0x0103: sig = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    else: sig = key.sign(signed_data, ec.ECDSA(hashes.SHA256()))
    spki = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    signer = lp(signed_data) + lp(lp(struct.pack("<I", alg) + lp(sig))) + lp(spki)
    value = lp(lp(signer))
    pair = struct.pack("<Q", 4 + len(value)) + struct.pack("<I", 0x7109871A) + value
    size = len(pair) + 8 + 16
    block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + b"APK Sig Block 42"
    eocd2 = eocd[:16] + struct.pack("<I", len(entries_bytes) + len(block)) + eocd[20:]
    return entries_bytes + block + cd_bytes + eocd2, digest, signed_data, sig

def verify_v2(apk: bytes, pub):
    """Independent check of what was written: finds the block, recomputes the digest, checks the signature."""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    e = find_eocd(apk)
    cd_size, cd_off = struct.unpack_from("<II", apk, e + 12)
    assert apk[cd_off - 16:cd_off] == b"APK Sig Block 42", "no signing block"
    size = struct.unpack_from("<Q", apk, cd_off - 24)[0]
    bstart = cd_off - size - 8
    assert struct.unpack_from("<Q", apk, bstart)[0] == size
    pairs = apk[bstart + 8: cd_off - 24]
    p = 0; value = None
    while p < len(pairs):
        ln = struct.unpack_from("<Q", pairs, p)[0]; pid = struct.unpack_from("<I", pairs, p + 8)[0]
        if pid == 0x7109871A: value = pairs[p + 12: p + 8 + ln]
        p += 8 + ln
    assert value is not None
    signers = value[4:]; signer = signers[4:4 + struct.unpack_from("<I", signers, 0)[0]]
    sd_len = struct.unpack_from("<I", signer, 0)[0]; signed_data = signer[4:4 + sd_len]
    rest = signer[4 + sd_len:]
    sigs = rest[4:4 + struct.unpack_from("<I", rest, 0)[0]]
    item = sigs[4:4 + struct.unpack_from("<I", sigs, 0)[0]]
    sig = item[8:8 + struct.unpack_from("<I", item, 4)[0]]
    pub.verify(sig, signed_data, padding.PKCS1v15(), hashes.SHA256())
    eocd_orig = apk[e:e + 16] + struct.pack("<I", bstart) + apk[e + 20:]
    digest = v2_digest([apk[:bstart], apk[cd_off:e], eocd_orig])
    d = signed_data[4:]; d = d[4:]  # digests seq -> first item
    stored = d[8:8 + struct.unpack_from("<I", d, 4)[0]]
    assert stored == digest, "digest mismatch"
    return True

# ------------------------------------------------------------------ main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True); ap.add_argument("--fonts", required=True)
    ap.add_argument("--keystore", required=True); ap.add_argument("--storepass", default="android")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    from cryptography.hazmat.primitives.serialization import pkcs12, Encoding
    key, cert, _ = pkcs12.load_key_and_certificates(open(a.keystore, "rb").read(), a.storepass.encode())
    cert_der = cert.public_bytes(Encoding.DER)

    data = open(a.apk, "rb").read()
    entries, _ = read_entries(data)
    drop = lambda n: n.startswith(b"META-INF/") and (n.endswith((b".SF", b".RSA", b".EC", b".DSA")) or n == b"META-INF/MANIFEST.MF")
    entries = [e for e in entries if not drop(e["name"]) and not e["name"].startswith(b"assets/fonts/personal/")]
    index = []; added = 0; skipped = []
    for fn in sorted(os.listdir(a.fonts)):
        if not fn.lower().endswith((".ttf", ".otf")): continue
        b = open(os.path.join(a.fonts, fn), "rb").read()
        names = font_names(b)
        if names is None: skipped.append(fn); continue
        safe = fn.replace("/", "_")
        entries.append(stored_entry("assets/fonts/personal/" + safe, b))
        index.append({"file": safe, "family": names[0], "style": names[1], "sha1": hashlib.sha1(b).hexdigest()[:12]})
        added += 1
    entries.append(stored_entry("assets/fonts/personal/index.json", json.dumps(index, ensure_ascii=False, indent=0).encode("utf-8")))
    eb, cdb, eocd = write_zip(entries)
    apk, *_ = sign_v2(eb, cdb, eocd, key, cert_der)
    verify_v2(apk, cert.public_key())
    open(a.out, "wb").write(apk)
    print(f"{a.out}: {added} fonts added, {len(skipped)} skipped {skipped}; v2 signature verified; {len(apk)/1e6:.1f} MB")

if __name__ == "__main__":
    main()
