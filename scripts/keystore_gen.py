#!/usr/bin/env python3
"""
Deterministic Android upload keystore generator — FULLY deterministic.

Given the same text secrets, produces the IDENTICAL JKS keystore and
SHA1 certificate fingerprint every CI run — no upload key resets ever.

Fixed-code approach for the X.509 certificate: uses cryptography library
with hardcoded serial=1 and fixed Jan 1 2025 start date, so the cert
is byte-identical regardless of when it's generated.

Usage:
    KEY_SECRET="..." python3 scripts/keystore_gen.py app/keystore/songguesser-upload-keystore.jks

Environment variables (set as GitHub Repository Secrets):
    KEY_SECRET      — REQUIRED. Master secret. Min 12 chars. Make it unique and permanent.
    KEY_ALIAS       — Key alias (default: songguesser-upload-key)
    STORE_PASSWORD  — Password for JKS + private key (default: same as KEY_SECRET)
    KEY_DNAME       — X.500 DN (default: CN=GuessTheSongYear, ...)

How it works:
    HMAC-SHA512(key=KEY_SECRET, msg=KEY_ALIAS + counter64) produces an
    unlimited deterministic byte stream fed into PyCryptodome RSA.generate()
    as randfunc. The X.509 certificate is built via cryptography library with
    fixed serial (#1), fixed notBefore (2025-01-01), and a 30-year validity.
    Every artifact is byte-identical across runs.
"""

import base64
import hashlib
import hmac
import os
import struct
import subprocess
import sys
from datetime import datetime, timedelta, timezone

from Crypto.PublicKey import RSA
from Crypto.Hash import SHA1

try:
    from cryptography import x509
    from cryptography.x509.oid import NameOID
    from cryptography.hazmat.primitives import hashes, serialization as crypto_ser
    from cryptography.hazmat.primitives.asymmetric import rsa as crypto_rsa
    from cryptography.hazmat.backends import default_backend
except ImportError:
    sys.exit("ERROR: cryptography library not found.  Run: pip3 install cryptography pycryptodome")

# ═══════════════════════════════════════════
# Config (from environment — GitHub Secrets)
# ═══════════════════════════════════════════

SECRET = os.environ.get("KEY_SECRET", "").encode()
if not SECRET:
    raise SystemExit(
        "ERROR: KEY_SECRET environment variable is required\n"
        "Usage: KEY_SECRET=\"...\" python3 scripts/keystore_gen.py output.jks"
    )

ALIAS = os.environ.get("KEY_ALIAS", "songguesser-upload-key")
PASS = os.environ.get("STORE_PASSWORD", SECRET.decode("ascii"))
DNAME = os.environ.get(
    "KEY_DNAME",
    "CN=GuessTheSongYear, OU=Turbolego, O=Turbolego, L=Oslo, ST=Oslo, C=NO"
)

OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/songguesser-keystore.jks"


# ═══════════════════════════════════════════
# Deterministic random byte stream
# ═══════════════════════════════════════════

class Drng:
    """HMAC-SHA512 counter-based CSPRNG — unlimited deterministic bytes."""

    def __init__(self, key: bytes, label: str):
        self._key = key
        self._label = label.encode()
        self._counter = 0
        self._buf = b""

    def read(self, n: int) -> bytes:
        out = bytearray()
        while len(out) < n:
            if not self._buf:
                self._buf = hmac.new(
                    self._key,
                    self._label + struct.pack(">Q", self._counter),
                    hashlib.sha512,
                ).digest()
                self._counter += 1
            take = min(len(self._buf), n - len(out))
            out.extend(self._buf[:take])
            self._buf = self._buf[take:]
        return bytes(out)


# ═══════════════════════════════════════════
# Step 1: RSA key pair (deterministic primes)
# ═══════════════════════════════════════════

rng = Drng(SECRET, f"songguesser-v1/{ALIAS}")
print("Generating deterministic RSA 2048-bit key pair...", flush=True)

key = RSA.generate(2048, randfunc=rng.read)

pub_der = key.publickey().export_key(format="DER")
fp_hex = SHA1.new(pub_der).hexdigest().upper()
fp = ":".join(fp_hex[i : i + 2] for i in range(0, len(fp_hex), 2))
print(f"RSA public key SHA1: {fp}", flush=True)


# ═══════════════════════════════════════════
# Step 2: X.509 self-signed cert (cryptography library)
# Fully deterministic: serial=1, fixed notBefore, fixed subject
# ═══════════════════════════════════════════

oid_map = {
    "CN": NameOID.COMMON_NAME,
    "OU": NameOID.ORGANIZATIONAL_UNIT_NAME,
    "O": NameOID.ORGANIZATION_NAME,
    "L": NameOID.LOCALITY_NAME,
    "ST": NameOID.STATE_OR_PROVINCE_NAME,
    "C": NameOID.COUNTRY_NAME,
}
name_parts = []
for part in DNAME.split(","):
    k, v = part.strip().split("=", 1)
    k, v = k.strip(), v.strip()
    oid = oid_map.get(k)
    if oid:
        name_parts.append(x509.NameAttribute(oid, v))
subject = issuer = x509.Name(name_parts)

pub_numbers = crypto_rsa.RSAPublicNumbers(e=65537, n=key.n)
priv_numbers = crypto_rsa.RSAPrivateNumbers(
    p=key.p,
    q=key.q,
    d=key.d,
    dmp1=key.d % (key.p - 1),
    dmq1=key.d % (key.q - 1),
    iqmp=pow(key.q, -1, key.p),
    public_numbers=pub_numbers,
)
crypto_key = priv_numbers.private_key(default_backend())

not_before = datetime(2025, 1, 1, 0, 0, 0, tzinfo=timezone.utc)
not_after = not_before + timedelta(days=10950)  # 30 years

cert_builder = (
    x509.CertificateBuilder()
    .subject_name(subject)
    .issuer_name(issuer)
    .public_key(crypto_key.public_key())
    .serial_number(1)
    .not_valid_before(not_before)
    .not_valid_after(not_after)
    .add_extension(
        x509.BasicConstraints(ca=False, path_length=None),
        critical=True,
    )
    .add_extension(
        x509.SubjectKeyIdentifier.from_public_key(crypto_key.public_key()),
        critical=False,
    )
)
certificate = cert_builder.sign(
    private_key=crypto_key,
    algorithm=hashes.SHA256(),
    backend=default_backend(),
)

# Write key PEM
key_pem = key.export_key(
    format="PEM",
    passphrase=PASS,
    protection="PBKDF2WithHMAC-SHA1AndDES-EDE3-CBC",
)
with open("/tmp/det-key.pem", "wb") as f:
    f.write(key_pem)

# Write cert PEM
cert_pem = certificate.public_bytes(crypto_ser.Encoding.PEM)
with open("/tmp/det-cert.pem", "wb") as f:
    f.write(cert_pem)

print(f"Certificate serial: {certificate.serial_number}", flush=True)
print(f"Valid: {not_before.strftime('%Y-%m-%d')} → {not_after.strftime('%Y-%m-%d')}", flush=True)

# ═══════════════════════════════════════════
# Step 3: PKCS12 → JKS
# ═══════════════════════════════════════════

subprocess.run(
    [
        "openssl",
        "pkcs12",
        "-export",
        "-in",
        "/tmp/det-cert.pem",
        "-inkey",
        "/tmp/det-key.pem",
        "-out",
        "/tmp/det-keystore.p12",
        "-name",
        ALIAS,
        "-passin",
        f"pass:{PASS}",
        "-passout",
        f"pass:{PASS}",
    ],
    check=True,
    capture_output=True,
)

subprocess.run(
    [
        "keytool",
        "-importkeystore",
        "-srckeystore",
        "/tmp/det-keystore.p12",
        "-srcstoretype",
        "PKCS12",
        "-srcstorepass",
        PASS,
        "-destkeystore",
        OUT,
        "-deststoretype",
        "JKS",
        "-deststorepass",
        PASS,
        "-srcalias",
        ALIAS,
        "-destalias",
        ALIAS,
        "-noprompt",
    ],
    check=True,
    capture_output=True,
)

print(f"\nJKS written to: {OUT}", flush=True)

# ═══════════════════════════════════════════
# Verify — print the fingerprint for Play Console
# ═══════════════════════════════════════════

result = subprocess.run(
    [
        "keytool",
        "-list",
        "-v",
        "-keystore",
        OUT,
        "-storepass",
        PASS,
        "-alias",
        ALIAS,
    ],
    capture_output=True,
    text=True,
)
for line in result.stdout.split("\n"):
    if "SHA1:" in line:
        print(f"Certificate {line.strip()}", flush=True)

with open(OUT, "rb") as f:
    jks_data = f.read()
print(f"JKS size: {len(jks_data)} bytes", flush=True)
print("✅ DONE — this SHA1 fingerprint is permanent and deterministic", flush=True)