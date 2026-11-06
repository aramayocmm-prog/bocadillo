"""Minimal Android binary XML (AXML) decoder. Usage: python3 -I axml.py file.xml [arsc]"""
import struct, sys

def read_string_pool(data, off):
    _t, hsz, csz, scount, stcount, flags, sstart, ststart = struct.unpack_from('<HHIIIIII', data, off)
    utf8 = flags & (1 << 8)
    offs = struct.unpack_from('<%dI' % scount, data, off + hsz)
    base = off + sstart
    out = []
    for o in offs:
        p = base + o
        if utf8:
            n = data[p]; p += 1
            if n & 0x80: p += 1
            n = data[p]; p += 1
            if n & 0x80:
                n = ((n & 0x7f) << 8) | data[p]; p += 1
            out.append(data[p:p + n].decode('utf-8', 'replace'))
        else:
            n = struct.unpack_from('<H', data, p)[0]; p += 2
            if n & 0x8000:
                n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', data, p)[0]; p += 2
            out.append(data[p:p + n * 2].decode('utf-16le', 'replace'))
    return out, off + csz

def fmt_val(typ, d, strings, resnames):
    if typ == 0x03: return strings[d] if d < len(strings) else '?'
    if typ == 0x10: return str(d if d < 2**31 else d - 2**32)
    if typ == 0x11: return hex(d)
    if typ == 0x12: return 'true' if d else 'false'
    if typ == 0x01: return '@' + resnames.get(d, hex(d))
    if typ == 0x02: return '?' + resnames.get(d, hex(d))
    if typ in (0x1c, 0x1d, 0x1e, 0x1f): return '#%08x' % d
    if typ == 0x04: return str(struct.unpack('<f', struct.pack('<I', d))[0])
    if typ == 0x05:
        units = ['px', 'dp', 'sp', 'pt', 'in', 'mm']
        return '%g%s' % (complex_val(d), units[d & 0xf] if (d & 0xf) < 6 else '?')
    if typ == 0x06:
        return '%g%%' % (complex_val(d) * 100)
    return 'type%x:%x' % (typ, d)

def complex_val(d):
    mant = (d & 0xffffff00) >> 8
    if mant & 0x800000: mant -= 0x1000000
    r = (d >> 4) & 3
    return mant * [1.0, 1 / 128, 1 / 32768, 1 / 8388608][r]

def decode(data, resnames=None):
    resnames = resnames or {}
    strings, resids = [], []
    off = 8
    out, depth, nsmap = [], 0, {}
    while off < len(data):
        typ, hsz, csz = struct.unpack_from('<HHI', data, off)
        if typ == 0x0001:
            strings, _ = read_string_pool(data, off)
        elif typ == 0x0180:
            resids = struct.unpack_from('<%dI' % ((csz - hsz) // 4), data, off + hsz)
        elif typ == 0x0100:
            pfx, uri = struct.unpack_from('<II', data, off + 16)
            nsmap[uri] = strings[pfx]
        elif typ == 0x0102:
            ns, name, aStart, aSize, aCount = struct.unpack_from('<iIHHH', data, off + 16)
            attrs = []
            p = off + 16 + aStart
            for i in range(aCount):
                ans, aname, araw, _sz, _r, atyp, adata = struct.unpack_from('<iIiHBBI', data, p)
                an = strings[aname]
                if not an and aname < len(resids):
                    an = resnames.get(resids[aname], hex(resids[aname]))
                pre = (nsmap.get(ans, 'android') + ':') if ans != -1 else ''
                attrs.append('%s%s="%s"' % (pre, an, fmt_val(atyp, adata, strings, resnames)))
                p += aSize
            out.append('  ' * depth + '<%s %s>' % (strings[name], ' '.join(attrs)))
            depth += 1
        elif typ == 0x0103:
            depth -= 1
        elif typ == 0x0104:
            idx = struct.unpack_from('<I', data, off + 16)[0]
            out.append('  ' * depth + 'TEXT: ' + strings[idx])
        off += csz
    return '\n'.join(out)

if __name__ == '__main__':
    rn = {}
    if len(sys.argv) > 2:
        import json
        rn = {int(k): v for k, v in json.load(open(sys.argv[2])).items()}
    print(decode(open(sys.argv[1], 'rb').read(), rn))
