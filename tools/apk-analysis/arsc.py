"""Minimal resources.arsc decoder: dumps id->type/name map and string values."""
import struct, sys, json
sys.path.insert(0, __file__.rsplit('/', 1)[0])
from axml import read_string_pool, fmt_val

def decode(data):
    off = 12
    gstrings, gend = read_string_pool(data, off)
    off = gend
    names, values = {}, []
    while off < len(data):
        typ, hsz, csz = struct.unpack_from('<HHI', data, off)
        if typ != 0x0200:
            off += csz; continue
        pid = struct.unpack_from('<I', data, off + 8)[0]
        typeStrOff, _, keyStrOff = struct.unpack_from('<III', data, off + 268)
        tnames, _ = read_string_pool(data, off + typeStrOff)
        knames, _ = read_string_pool(data, off + keyStrOff)
        p = off + hsz
        end = off + csz
        while p < end:
            ctyp, chsz, ccsz = struct.unpack_from('<HHI', data, p)
            if ctyp == 0x0201:
                tid, _r0, _r1, ecount, estart = struct.unpack_from('<BBHII', data, p + 8)
                cfg_size = struct.unpack_from('<I', data, p + 20)[0]
                cfg = data[p + 20:p + 20 + cfg_size]
                lang = cfg[8:10].decode('latin1').strip('\x00') if cfg_size > 10 else ''
                eoffs = struct.unpack_from('<%dI' % ecount, data, p + chsz)
                for i, eo in enumerate(eoffs):
                    if eo == 0xffffffff: continue
                    ep = p + estart + eo
                    esz, eflags, ekey = struct.unpack_from('<HHI', data, ep)
                    rid = (pid << 24) | (tid << 16) | i
                    nm = '%s/%s' % (tnames[tid - 1], knames[ekey])
                    names[rid] = nm
                    if not (eflags & 1):
                        _vs, _z, vt, vd = struct.unpack_from('<HBBI', data, ep + 8)
                        values.append((nm, lang, fmt_val(vt, vd, gstrings, {})))
                    else:
                        parent, cnt = struct.unpack_from('<II', data, ep + 8)
                        q = ep + esz
                        items = []
                        for _ in range(cnt):
                            mname, _vs, _z, vt, vd = struct.unpack_from('<IHBBI', data, q)
                            items.append('%s=%s' % (hex(mname), fmt_val(vt, vd, gstrings, {})))
                            q += 12
                        values.append((nm, lang, '{' + ', '.join(items) + '}'))
            p += ccsz
        off += csz
    return names, values

if __name__ == '__main__':
    names, values = decode(open(sys.argv[1], 'rb').read())
    json.dump({str(k): v for k, v in names.items()}, open(sys.argv[2], 'w'))
    for nm, lang, v in values:
        print('%s [%s] = %s' % (nm, lang or 'def', v))
