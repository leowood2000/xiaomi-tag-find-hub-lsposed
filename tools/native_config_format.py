"""Offline format tools. No ADB, phone writes, hooks, or obfuscated names.

Only shared-snapshot format 1 has been verified. This is not an installer.
"""
import base64
import struct
from dataclasses import dataclass
from pathlib import Path

MASK = (1 << 64) - 1
MAX_SIZE = 64 * 1024 * 1024


def _rot(n, bits):
    return ((n << bits) | (n >> (64 - bits))) & MASK


def _fmix(n):
    n ^= n >> 33
    n = (n * 0xff51afd7ed558ccd) & MASK
    n ^= n >> 33
    n = (n * 0xc4ceb9fe1a85ec53) & MASK
    return n ^ (n >> 33)


def snapshot_leaf(namespace, account=""):
    """Guava-compatible Murmur3 x64_128(seed=0), Base64 URL with padding."""
    if not namespace or any(c in namespace + account for c in "\0/\\"):
        raise ValueError("Invalid snapshot identity")
    data = namespace.encode() + b"\0" + account.encode()
    h1 = h2 = 0
    c1, c2 = 0x87c37b91114253d5, 0x4cf5ad432745937f
    end = len(data) // 16 * 16
    for pos in range(0, end, 16):
        k1, k2 = struct.unpack_from("<QQ", data, pos)
        h1 ^= (_rot(k1 * c1 & MASK, 31) * c2) & MASK
        h1 = (_rot(h1, 27) + h2) & MASK
        h1 = (h1 * 5 + 0x52dce729) & MASK
        h2 ^= (_rot(k2 * c2 & MASK, 33) * c1) & MASK
        h2 = (_rot(h2, 31) + h1) & MASK
        h2 = (h2 * 5 + 0x38495ab5) & MASK
    tail = data[end:]
    if len(tail) > 8:
        k2 = int.from_bytes(tail[8:], "little")
        h2 ^= _rot(k2 * c2 & MASK, 33) * c1 & MASK
    if tail:
        k1 = int.from_bytes(tail[:8], "little")
        h1 ^= _rot(k1 * c1 & MASK, 31) * c2 & MASK
    h1 ^= len(data)
    h2 ^= len(data)
    h1 = (h1 + h2) & MASK
    h2 = (h2 + h1) & MASK
    h1, h2 = _fmix(h1), _fmix(h2)
    h1 = (h1 + h2) & MASK
    h2 = (h2 + h1) & MASK
    return base64.urlsafe_b64encode(struct.pack("<QQ", h1, h2)).decode() + ".pb"


def varint(n):
    if not 0 <= n <= MASK:
        raise ValueError("Out of range")
    out = bytearray()
    while n > 127:
        out.append((n & 127) | 128)
        n >>= 7
    return bytes(out + bytes([n]))


def _read(data, pos):
    n = 0
    for shift in range(0, 70, 7):
        if pos >= len(data):
            raise ValueError("Truncated varint")
        byte = data[pos]
        pos += 1
        if shift == 63 and byte > 1:
            raise ValueError("Overflowed varint")
        n |= (byte & 127) << shift
        if byte < 128:
            return n, pos
    raise ValueError("Invalid varint")


def fields(data):
    pos = 0
    while pos < len(data):
        start = pos
        tag, pos = _read(data, pos)
        number, wire = tag >> 3, tag & 7
        if not 0 < number < (1 << 29):
            raise ValueError("Invalid field number")
        if wire == 0:
            value, pos = _read(data, pos)
        elif wire in (1, 2, 5):
            if wire == 2:
                size, pos = _read(data, pos)
            else:
                size = 8 if wire == 1 else 4
            if size > len(data) - pos:
                raise ValueError("Truncated field")
            value = data[pos:pos + size]
            pos += size
        else:
            raise ValueError("Unsupported wire type")
        yield number, wire, value, data[start:pos]


def _blob(number, value):
    return varint(number * 8 + 2) + varint(len(value)) + value


@dataclass(frozen=True)
class Snapshot:
    prefix: bytes
    metadata: bytes
    payload: bytes

    @classmethod
    def parse(cls, data):
        if len(data) < 9 or len(data) > MAX_SIZE:
            raise ValueError("Invalid snapshot size")
        if struct.unpack_from("<II", data) != (1, 0):
            raise ValueError("Unverified format version or flags")
        size, pos = _read(data, 8)
        if size >= len(data) - pos:
            raise ValueError("Truncated metadata or missing payload")
        metadata = data[pos:pos + size]
        list(fields(metadata))
        return cls(data[:8], metadata, data[pos + size:])

    def encode(self):
        return self.prefix + varint(len(self.metadata)) + self.metadata + self.payload

    def overrides(self):
        result = {}
        for number, wire, value, raw in fields(self.metadata):
            if number != 5:
                continue
            if wire != 2:
                raise ValueError("Unexpected override wire type")
            keys = [v for n, w, v, _ in fields(value) if n == 1 and w == 2]
            values = [v for n, w, v, _ in fields(value) if n == 2 and w == 2]
            if len(keys) != 1 or len(values) != 1:
                raise ValueError("Ambiguous override entry")
            key = keys[0].decode("utf-8")
            if key in result:
                raise ValueError("Duplicate override key")
            list(fields(values[0]))
            result[key] = raw
        return result


def add_bool(data, name, value):
    """Return patched bytes plus an ownership token for scoped rollback."""
    snapshot = Snapshot.parse(data)
    if not name or type(value) is not bool:
        raise ValueError("Expected named boolean")
    if name in snapshot.overrides():
        raise ValueError("Refusing to replace an existing override")
    key = name.encode()
    flag = b"\x10" + varint(int(value)) + _blob(10, key)
    entry = _blob(5, _blob(1, key) + _blob(2, flag))
    return Snapshot(snapshot.prefix, snapshot.metadata + entry, snapshot.payload).encode(), entry


def remove_added_bool(data, name, ownership_token):
    """Remove only our exact entry; preserve concurrent unrelated changes."""
    snapshot = Snapshot.parse(data)
    current = snapshot.overrides().get(name)
    if current is None:
        return data
    if current != ownership_token:
        raise ValueError("Override changed after patch; refusing rollback")
    metadata = b"".join(raw for _, _, _, raw in fields(snapshot.metadata) if raw != current)
    return Snapshot(snapshot.prefix, metadata, snapshot.payload).encode()


def require_unique_snapshot(directory, namespace, account=""):
    """Inspect an explicitly provided local directory; never guess newest salt.

    A device bridge must independently establish that this is the active root.
    This function does not claim that a unique on-disk file is the active store.
    """
    root = Path(directory).resolve(strict=True)
    if not root.is_dir():
        raise ValueError("Snapshot root is not a directory")
    matches = list(root.glob("*/" + snapshot_leaf(namespace, account)))
    if len(matches) != 1:
        raise ValueError("Expected exactly one matching snapshot; refusing to guess")
    path = matches[0]
    resolved = path.resolve(strict=True)
    if not resolved.is_relative_to(root) or path.is_symlink() or path.parent.is_symlink():
        raise ValueError("Snapshot path escapes root or uses a symlink")
    size = resolved.stat().st_size
    if not 9 <= size <= MAX_SIZE:
        raise ValueError("Invalid snapshot size")
    Snapshot.parse(resolved.read_bytes())
    return resolved
