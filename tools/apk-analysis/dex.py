"""Minimal DEX parser + Dalvik disassembler (no external deps).
python3 -I dex.py classes.dex outdir
Writes: classes.txt (class -> fields/methods), strings.txt, and smali-like dumps per package prefix filter.
"""
import struct, sys, os

def uleb(d, p):
    r = s = 0
    while True:
        b = d[p]; p += 1
        r |= (b & 0x7f) << s; s += 7
        if not b & 0x80: return r, p

def sleb(d, p):
    r = s = 0
    while True:
        b = d[p]; p += 1
        r |= (b & 0x7f) << s; s += 7
        if not b & 0x80:
            if b & 0x40: r -= 1 << s
            return r, p

# opcode -> (name, format)
OPS = {}
def _o(rng, name, fmt):
    for i, op in enumerate(rng): OPS[op] = (name[i] if isinstance(name, list) else name, fmt)
_o([0x00], 'nop', '10x'); _o([0x01], 'move', '12x'); _o([0x02], 'move/from16', '22x'); _o([0x03], 'move/16', '32x')
_o([0x04], 'move-wide', '12x'); _o([0x05], 'move-wide/from16', '22x'); _o([0x06], 'move-wide/16', '32x')
_o([0x07], 'move-object', '12x'); _o([0x08], 'move-object/from16', '22x'); _o([0x09], 'move-object/16', '32x')
_o([0x0a], 'move-result', '11x'); _o([0x0b], 'move-result-wide', '11x'); _o([0x0c], 'move-result-object', '11x'); _o([0x0d], 'move-exception', '11x')
_o([0x0e], 'return-void', '10x'); _o([0x0f], 'return', '11x'); _o([0x10], 'return-wide', '11x'); _o([0x11], 'return-object', '11x')
_o([0x12], 'const/4', '11n'); _o([0x13], 'const/16', '21s'); _o([0x14], 'const', '31i'); _o([0x15], 'const/high16', '21h')
_o([0x16], 'const-wide/16', '21s'); _o([0x17], 'const-wide/32', '31i'); _o([0x18], 'const-wide', '51l'); _o([0x19], 'const-wide/high16', '21h')
_o([0x1a], 'const-string', '21c:s'); _o([0x1b], 'const-string/jumbo', '31c:s'); _o([0x1c], 'const-class', '21c:t')
_o([0x1d], 'monitor-enter', '11x'); _o([0x1e], 'monitor-exit', '11x'); _o([0x1f], 'check-cast', '21c:t')
_o([0x20], 'instance-of', '22c:t'); _o([0x21], 'array-length', '12x'); _o([0x22], 'new-instance', '21c:t'); _o([0x23], 'new-array', '22c:t')
_o([0x24], 'filled-new-array', '35c:t'); _o([0x25], 'filled-new-array/range', '3rc:t'); _o([0x26], 'fill-array-data', '31t')
_o([0x27], 'throw', '11x'); _o([0x28], 'goto', '10t'); _o([0x29], 'goto/16', '20t'); _o([0x2a], 'goto/32', '30t')
_o([0x2b], 'packed-switch', '31t'); _o([0x2c], 'sparse-switch', '31t')
_o(range(0x2d, 0x32), ['cmpl-float', 'cmpg-float', 'cmpl-double', 'cmpg-double', 'cmp-long'], '23x')
_o(range(0x32, 0x38), ['if-eq', 'if-ne', 'if-lt', 'if-ge', 'if-gt', 'if-le'], '22t')
_o(range(0x38, 0x3e), ['if-eqz', 'if-nez', 'if-ltz', 'if-gez', 'if-gtz', 'if-lez'], '21t')
_o(range(0x3e, 0x44), 'unused', '10x')
_o(range(0x44, 0x52), ['aget', 'aget-wide', 'aget-object', 'aget-boolean', 'aget-byte', 'aget-char', 'aget-short', 'aput', 'aput-wide', 'aput-object', 'aput-boolean', 'aput-byte', 'aput-char', 'aput-short'], '23x')
_o(range(0x52, 0x60), ['iget', 'iget-wide', 'iget-object', 'iget-boolean', 'iget-byte', 'iget-char', 'iget-short', 'iput', 'iput-wide', 'iput-object', 'iput-boolean', 'iput-byte', 'iput-char', 'iput-short'], '22c:f')
_o(range(0x60, 0x6e), ['sget', 'sget-wide', 'sget-object', 'sget-boolean', 'sget-byte', 'sget-char', 'sget-short', 'sput', 'sput-wide', 'sput-object', 'sput-boolean', 'sput-byte', 'sput-char', 'sput-short'], '21c:f')
_o(range(0x6e, 0x73), ['invoke-virtual', 'invoke-super', 'invoke-direct', 'invoke-static', 'invoke-interface'], '35c:m')
_o([0x73], 'unused', '10x')
_o(range(0x74, 0x79), ['invoke-virtual/range', 'invoke-super/range', 'invoke-direct/range', 'invoke-static/range', 'invoke-interface/range'], '3rc:m')
_o(range(0x79, 0x7b), 'unused', '10x')
_un = ['neg-int', 'not-int', 'neg-long', 'not-long', 'neg-float', 'neg-double', 'int-to-long', 'int-to-float', 'int-to-double', 'long-to-int', 'long-to-float', 'long-to-double', 'float-to-int', 'float-to-long', 'float-to-double', 'double-to-int', 'double-to-long', 'double-to-float', 'int-to-byte', 'int-to-char', 'int-to-short']
_o(range(0x7b, 0x90), _un, '12x')
_bin = ['add', 'sub', 'mul', 'div', 'rem', 'and', 'or', 'xor', 'shl', 'shr', 'ushr']
_bn = [b + '-int' for b in _bin] + [b + '-long' for b in _bin] + [b + '-float' for b in _bin[:5]] + [b + '-double' for b in _bin[:5]]
_o(range(0x90, 0xb0), _bn, '23x')
_o(range(0xb0, 0xd0), [b + '/2addr' for b in _bn], '12x')
_o(range(0xd0, 0xd8), ['add-int/lit16', 'rsub-int', 'mul-int/lit16', 'div-int/lit16', 'rem-int/lit16', 'and-int/lit16', 'or-int/lit16', 'xor-int/lit16'], '22s')
_o(range(0xd8, 0xe3), ['add-int/lit8', 'rsub-int/lit8', 'mul-int/lit8', 'div-int/lit8', 'rem-int/lit8', 'and-int/lit8', 'or-int/lit8', 'xor-int/lit8', 'shl-int/lit8', 'shr-int/lit8', 'ushr-int/lit8'], '22b')
_o(range(0xe3, 0x100), 'unused', '10x')
SIZES = {'10x': 1, '12x': 1, '11n': 1, '11x': 1, '10t': 1, '20t': 2, '22x': 2, '21t': 2, '21s': 2, '21h': 2, '21c': 2, '23x': 2, '22b': 2, '22t': 2, '22s': 2, '22c': 2, '30t': 3, '32x': 3, '31i': 3, '31t': 3, '31c': 3, '35c': 3, '3rc': 3, '51l': 5}

class Dex:
    def __init__(self, data):
        self.d = d = data
        h = struct.unpack_from('<8sI20s20I', d, 0)
        (_, _, _, self.fsize, _, _, _, _, _, sids_n, sids_o, tids_n, tids_o, pids_n, pids_o,
         fids_n, fids_o, mids_n, mids_o, cdefs_n, cdefs_o, _, _) = h
        self.strings = []
        for i in range(sids_n):
            so = struct.unpack_from('<I', d, sids_o + 4 * i)[0]
            n, p = uleb(d, so)
            e = d.index(b'\x00', p)
            self.strings.append(d[p:e].decode('utf-8', 'replace'))
        self.types = [self.strings[struct.unpack_from('<I', d, tids_o + 4 * i)[0]] for i in range(tids_n)]
        self.protos = []
        for i in range(pids_n):
            sh, rt, po = struct.unpack_from('<III', d, pids_o + 12 * i)
            params = []
            if po:
                n = struct.unpack_from('<I', d, po)[0]
                params = [self.types[x] for x in struct.unpack_from('<%dH' % n, d, po + 4)]
            self.protos.append((self.types[rt], params))
        self.fields = []
        for i in range(fids_n):
            c, t, nm = struct.unpack_from('<HHI', d, fids_o + 8 * i)
            self.fields.append((self.types[c], self.types[t], self.strings[nm]))
        self.methods = []
        for i in range(mids_n):
            c, pr, nm = struct.unpack_from('<HHI', d, mids_o + 8 * i)
            self.methods.append((self.types[c], self.protos[pr], self.strings[nm]))
        self.classes = []
        for i in range(cdefs_n):
            (ci, af, sc, ifo, sf, ao, cdo, svo) = struct.unpack_from('<8I', d, cdefs_o + 32 * i)
            cls = {'name': self.types[ci], 'super': self.types[sc] if sc != 0xffffffff else None,
                   'flags': af, 'ifaces': [], 'sfields': [], 'ifields': [], 'dmethods': [], 'vmethods': [],
                   'static_values': []}
            if ifo:
                n = struct.unpack_from('<I', d, ifo)[0]
                cls['ifaces'] = [self.types[x] for x in struct.unpack_from('<%dH' % n, d, ifo + 4)]
            if cdo:
                p = cdo
                a, p = uleb(d, p); b, p = uleb(d, p); c2, p = uleb(d, p); e, p = uleb(d, p)
                for key, cnt in (('sfields', a), ('ifields', b)):
                    idx = 0
                    for _ in range(cnt):
                        df, p = uleb(d, p); fl, p = uleb(d, p); idx += df
                        cls[key].append((idx, fl))
                for key, cnt in (('dmethods', c2), ('vmethods', e)):
                    idx = 0
                    for _ in range(cnt):
                        df, p = uleb(d, p); fl, p = uleb(d, p); co, p = uleb(d, p); idx += df
                        cls[key].append((idx, fl, co))
            if svo:
                try: cls['static_values'] = self.enc_array(svo)[0]
                except Exception: pass
            self.classes.append(cls)

    def enc_value(self, p):
        d = self.d
        b = d[p]; p += 1
        vt, va = b & 0x1f, b >> 5
        n = va + 1
        raw = d[p:p + n] if vt not in (0x1c, 0x1d, 0x1e, 0x1f) else b''
        if vt == 0x1c: return self.enc_array(p)
        if vt == 0x1d:
            _t, p = uleb(d, p); sz, p = uleb(d, p)
            for _ in range(sz):
                _n, p = uleb(d, p); _v, p = self.enc_value(p)
            return '<annotation>', p
        if vt == 0x1e: return None, p
        if vt == 0x1f: return bool(va), p
        p += n
        iv = int.from_bytes(raw, 'little', signed=vt in (0x00, 0x02, 0x04, 0x06))
        if vt == 0x10: return struct.unpack('<f', (raw.rjust(4, b'\0')))[0], p
        if vt == 0x11: return struct.unpack('<d', (raw.rjust(8, b'\0')))[0], p
        if vt == 0x17: return repr(self.strings[iv]), p
        if vt == 0x18: return self.types[iv], p
        if vt in (0x19, 0x1b): return self.fname(iv), p
        if vt == 0x1a: return self.mname(iv), p
        return iv, p

    def enc_array(self, p):
        sz, p = uleb(self.d, p)
        out = []
        for _ in range(sz):
            v, p = self.enc_value(p); out.append(v)
        return out, p

    def fname(self, i):
        c, t, n = self.fields[i]; return '%s->%s:%s' % (c, n, t)

    def mname(self, i):
        c, (r, ps), n = self.methods[i]; return '%s->%s(%s)%s' % (c, n, ''.join(ps), r)

    def disasm(self, co):
        d = self.d
        regs, ins, outs, tries, dbg, n = struct.unpack_from('<HHHHII', d, co)
        base = co + 16
        code = struct.unpack_from('<%dH' % n, d, base)
        out = ['    .registers %d (ins %d)' % (regs, ins)]
        pc = 0
        payloads = {}
        while pc < n:
            w = code[pc]
            op = w & 0xff
            if op == 0 and w in (0x100, 0x200, 0x300):
                # payload
                if w == 0x300:
                    ew, cnt = struct.unpack_from('<HI', d, base + pc * 2 + 2)
                    vals = d[base + pc * 2 + 8: base + pc * 2 + 8 + ew * cnt]
                    if ew == 4:
                        ints = struct.unpack('<%di' % cnt, vals)
                        fl = struct.unpack('<%df' % cnt, vals)
                        out.append('  %04x: array-data w4 ints=%s floats=%s' % (pc, list(ints), ['%g' % x for x in fl]))
                    else:
                        out.append('  %04x: array-data w%d %s' % (pc, ew, vals.hex()))
                    pc += (ew * cnt + 1) // 2 + 4
                elif w == 0x100:
                    cnt, first = struct.unpack_from('<Hi', d, base + pc * 2 + 2)
                    out.append('  %04x: packed-switch-data first=%d n=%d' % (pc, first, cnt))
                    pc += cnt * 2 + 4
                else:
                    cnt = code[pc + 1]
                    keys = struct.unpack_from('<%di' % cnt, d, base + pc * 2 + 4)
                    out.append('  %04x: sparse-switch-data keys=%s' % (pc, list(keys)))
                    pc += cnt * 4 + 2
                continue
            name, fmt = OPS[op]
            kind = fmt.split(':')[1] if ':' in fmt else None
            fmt = fmt.split(':')[0]
            sz = SIZES[fmt]
            a = (w >> 8)
            s = ''
            def ref(i):
                if kind == 's': return repr(self.strings[i])
                if kind == 't': return self.types[i]
                if kind == 'f': return self.fname(i)
                if kind == 'm': return self.mname(i)
                return str(i)
            if fmt == '10x': s = ''
            elif fmt == '12x': s = 'v%d, v%d' % (a & 0xf, a >> 4)
            elif fmt == '11n':
                lit = a >> 4; lit = lit - 16 if lit & 8 else lit
                s = 'v%d, %d' % (a & 0xf, lit)
            elif fmt == '11x': s = 'v%d' % a
            elif fmt == '10t': s = '-> %04x' % (pc + (a - 256 if a & 0x80 else a))
            elif fmt == '20t': s = '-> %04x' % (pc + struct.unpack('<h', struct.pack('<H', code[pc + 1]))[0])
            elif fmt == '22x': s = 'v%d, v%d' % (a, code[pc + 1])
            elif fmt == '21t': s = 'v%d -> %04x' % (a, pc + struct.unpack('<h', struct.pack('<H', code[pc + 1]))[0])
            elif fmt == '21s':
                v = struct.unpack('<h', struct.pack('<H', code[pc + 1]))[0]
                s = 'v%d, %d' % (a, v)
            elif fmt == '21h':
                v = code[pc + 1]
                if op == 0x15:
                    iv = v << 16
                    s = 'v%d, 0x%x (float %g)' % (a, iv, struct.unpack('<f', struct.pack('<I', iv))[0])
                else:
                    lv = v << 48
                    s = 'v%d, (double %g)' % (a, struct.unpack('<d', struct.pack('<Q', lv))[0])
            elif fmt == '21c': s = 'v%d, %s' % (a, ref(code[pc + 1]))
            elif fmt == '23x': s = 'v%d, v%d, v%d' % (a, code[pc + 1] & 0xff, code[pc + 1] >> 8)
            elif fmt == '22b':
                lit = code[pc + 1] >> 8; lit = lit - 256 if lit & 0x80 else lit
                s = 'v%d, v%d, %d' % (a, code[pc + 1] & 0xff, lit)
            elif fmt == '22t': s = 'v%d, v%d -> %04x' % (a & 0xf, a >> 4, pc + struct.unpack('<h', struct.pack('<H', code[pc + 1]))[0])
            elif fmt == '22s': s = 'v%d, v%d, %d' % (a & 0xf, a >> 4, struct.unpack('<h', struct.pack('<H', code[pc + 1]))[0])
            elif fmt == '22c': s = 'v%d, v%d, %s' % (a & 0xf, a >> 4, ref(code[pc + 1]))
            elif fmt == '30t': s = '-> %04x' % (pc + struct.unpack('<i', struct.pack('<HH', code[pc + 1], code[pc + 2]))[0])
            elif fmt == '32x': s = 'v%d, v%d' % (code[pc + 1], code[pc + 2])
            elif fmt == '31i':
                iv = code[pc + 1] | (code[pc + 2] << 16)
                si = iv - (1 << 32) if iv & 0x80000000 else iv
                s = 'v%d, %d (0x%x, float %g)' % (a, si, iv, struct.unpack('<f', struct.pack('<I', iv))[0])
            elif fmt == '31t': s = 'v%d, @%04x' % (a, pc + struct.unpack('<i', struct.pack('<HH', code[pc + 1], code[pc + 2]))[0])
            elif fmt == '31c': s = 'v%d, %s' % (a, ref(code[pc + 1] | (code[pc + 2] << 16)))
            elif fmt == '35c':
                cnt = a >> 4; g = a & 0xf
                c, fe, dc = code[pc + 2] & 0xf, (code[pc + 2] >> 4) & 0xf, (code[pc + 2] >> 8) & 0xf
                ee = code[pc + 2] >> 12
                rr = [c, fe, dc, ee, g][:cnt]
                s = '{%s}, %s' % (', '.join('v%d' % r for r in rr), ref(code[pc + 1]))
            elif fmt == '3rc':
                s = '{v%d .. v%d}, %s' % (code[pc + 2], code[pc + 2] + a - 1, ref(code[pc + 1]))
            elif fmt == '51l':
                lv = code[pc + 1] | (code[pc + 2] << 16) | (code[pc + 3] << 32) | (code[pc + 4] << 48)
                s = 'v%d, %d (double %g)' % (a, lv, struct.unpack('<d', struct.pack('<Q', lv))[0])
            out.append('  %04x: %s %s' % (pc, name, s))
            pc += sz
        return out

def main():
    d = Dex(open(sys.argv[1], 'rb').read())
    outdir = sys.argv[2]
    os.makedirs(outdir, exist_ok=True)
    with open(os.path.join(outdir, 'strings.txt'), 'w') as f:
        for s in d.strings: f.write(repr(s) + '\n')
    with open(os.path.join(outdir, 'classes.txt'), 'w') as f:
        for c in d.classes:
            f.write('class %s extends %s implements %s\n' % (c['name'], c['super'], ','.join(c['ifaces'])))
            for i, (fi, fl) in enumerate(c['sfields']):
                v = c['static_values'][i] if i < len(c['static_values']) else ''
                f.write('  static field %s %s = %s\n' % (d.fields[fi][2], d.fields[fi][1], v))
            for fi, fl in c['ifields']:
                f.write('  field %s %s\n' % (d.fields[fi][2], d.fields[fi][1]))
            for mi, fl, co in c['dmethods'] + c['vmethods']:
                f.write('  method %s\n' % d.mname(mi).split('->')[1])
    pref = sys.argv[3] if len(sys.argv) > 3 else 'Lcom/shinycore'
    sdir = os.path.join(outdir, 'smali'); os.makedirs(sdir, exist_ok=True)
    for c in d.classes:
        if not c['name'].startswith(pref): continue
        fn = c['name'][1:-1].replace('/', '.') + '.smali'
        with open(os.path.join(sdir, fn), 'w') as f:
            f.write('.class %s\n.super %s\n.implements %s\n' % (c['name'], c['super'], ','.join(c['ifaces'])))
            for i, (fi, fl) in enumerate(c['sfields']):
                v = c['static_values'][i] if i < len(c['static_values']) else ''
                f.write('.field static %s:%s = %s\n' % (d.fields[fi][2], d.fields[fi][1], v))
            for fi, fl in c['ifields']:
                f.write('.field %s:%s\n' % (d.fields[fi][2], d.fields[fi][1]))
            for mi, fl, co in c['dmethods'] + c['vmethods']:
                f.write('\n.method %s flags=0x%x\n' % (d.mname(mi).split('->')[1], fl))
                if co:
                    try: f.write('\n'.join(d.disasm(co)) + '\n')
                    except Exception as e: f.write('  ; disasm error %r\n' % e)
                f.write('.end method\n')

if __name__ == '__main__':
    main()
