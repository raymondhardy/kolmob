#!/usr/bin/env python3
"""Generate signature-only stubs for upstream Swing UI classes (net.sourceforge.kolmafia.swingui.*).
Core code references a few GUI hooks; on Android there is no Swing, so the stubs keep upstream
core sources unmodified. Input: tools/swingui_stub_classes.txt (one FQCN per line)."""
import os, re, sys

UP = os.path.expanduser('~/workspace/kolmafia/src/main/java')
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(HERE, 'kolmafia-overrides/java')
SWINGUI_DIR = os.path.join(UP, 'net/sourceforge/kolmafia/swingui')
ALL_SWINGUI = {}
for _root in ('net/sourceforge/kolmafia/swingui', 'com/sun/java/forums', 'ca/bcit/geekkit', 'darrylbu/util', 'com/informit/guides'):
    for _r, _d, _fs in os.walk(os.path.join(UP, _root)):
        for _f in _fs:
            if _f.endswith('.java'):
                _rel = os.path.relpath(_r, UP).replace('/', '.')
                ALL_SWINGUI[_f[:-5]] = _rel + '.' + _f[:-5]
ALL_SWINGUI['ActionVerifyPanel'] = 'net.java.dev.spellcast.utilities.ActionVerifyPanel'
SWINGX_MAP = {'JXTable': 'javax.swing.JTable', 'JXTree': 'javax.swing.JTree', 'JXCollapsiblePane': 'javax.swing.JPanel', 'JXTaskPane': 'javax.swing.JPanel', 'JXList': 'javax.swing.JList'}
DROP_TYPES = ['FormLayout', 'FlatLaf']
STUBS = set()      # simple names that are being generated
WORK = []          # worklist of FQCNs

def unknown_refs(text):
    if any(re.search(r'\b' + d + r'\b', text) for d in DROP_TYPES): return ['__DROP__']
    return [n for n in ALL_SWINGUI if n not in STUBS and re.search(r'\b' + re.escape(n) + r'\b', text)]

KEEP_IMPORT = ('java.', 'javax.', 'net.sourceforge.kolmafia', 'net.java.dev.spellcast', 'com.sun.java.forums', 'com.informit', 'ca.bcit', 'darrylbu')

def clean(src):
    out = []; i = 0; n = len(src)
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j; continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2); i = n if j < 0 else j + 2; continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3); i = n if j < 0 else j + 3; out.append('""'); continue
        if c == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            out.append('""'); i = j + 1; continue
        if c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            out.append("' '"); i = j + 1; continue
        out.append(c); i += 1
    return ''.join(out)

def match_brace(s, i):
    d = 0
    while i < len(s):
        if s[i] == '{': d += 1
        elif s[i] == '}':
            d -= 1
            if d == 0: return i
        i += 1
    return len(s) - 1

def strip_annotations(h):
    h = re.sub(r'@\w+(\.\w+)*\s*(\([^)]*\))?', ' ', h)
    return re.sub(r'\s+', ' ', h).strip()

def default_for(rt):
    rt = rt.strip()
    if rt == 'void': return '{ }'
    if rt == 'boolean': return '{ return false; }'
    if rt in ('byte', 'short', 'int', 'long', 'char', 'float', 'double'): return '{ return 0; }'
    if rt.endswith('[]') and '[][]' not in rt: return '{ return new ' + rt[:-2] + '[0]; }'
    if rt == 'String': return '{ return ""; }'
    base = re.sub(r'<.*$', '', rt)
    if base in ('List', 'Collection', 'Iterable'): return '{ return java.util.List.of(); }'
    if base == 'Set': return '{ return java.util.Set.of(); }'
    if base == 'Map': return '{ return java.util.Map.of(); }'
    if base == 'Optional': return '{ return java.util.Optional.empty(); }'
    return '{ return null; }'

def members(body):
    res = []; cur = []; i = 0; n = len(body)
    while i < n:
        c = body[i]
        if c == ';':
            res.append((''.join(cur).strip(), None)); cur = []; i += 1
        elif c == '{':
            h = ''.join(cur).strip()
            j = match_brace(body, i)
            if '=' in re.sub(r'\([^)]*\)', '', h) and '(' not in h.split('=')[0]:
                k = body.find(';', j)
                res.append((h, None)); cur = []; i = (n if k < 0 else k + 1)
            else:
                res.append((h, body[i + 1:j])); cur = []; i = j + 1
        else:
            cur.append(c); i += 1
    return res

def emit_type(src_clean, name, indent, is_nested):
    m = re.search(r'((?:public|protected|private|abstract|static|final|sealed)\s+)*(class|interface|enum|record)\s+' + re.escape(name) + r'\b[^{]*\{', src_clean)
    if not m: return None
    header = re.sub(r'\s+', ' ', m.group(0)[:-1]).strip()
    kind = m.group(2)
    if is_nested:
        mx = re.search(r'\bextends\s+([\w.]+)(<.*>)?(?:\s+implements\s+.*)?$', header)
        base = re.sub(r'\s+implements\s+.*$', '', header)
        base = re.sub(r'\s+extends\s+.*$', '', base)
        if mx:
            parent = mx.group(1).split('.')[-1]
            if '__DROP__' in unknown_refs(mx.group(0)): return None
            if parent in ALL_SWINGUI and parent != name:
                if parent not in STUBS:
                    STUBS.add(parent); WORK.append(ALL_SWINGUI[parent])
                for u in unknown_refs(mx.group(2) or ''):
                    if u != '__DROP__' and u not in STUBS:
                        STUBS.add(u); WORK.append(ALL_SWINGUI[u])
                base += ' extends ' + parent + (mx.group(2) or '')
            elif re.search(r'\b(class|interface)\s+' + re.escape(parent) + r'\b', src_clean):
                base += ' extends ' + parent + (mx.group(2) or '')
        header = base
    else:
        if '__DROP__' in unknown_refs(header):
            # Swing-only third-party superclass (e.g. SwingX JXTable): keep the class, drop the superclass.
            header = re.sub(r'\s+extends\s+[\w.]+(<[^{]*>)?', '', header, count=1) if re.search(r'\bextends\b', header) else header
            if '__DROP__' in unknown_refs(header):
                header = re.sub(r'\s+implements\s+.*$', '', header)
        for u in unknown_refs(header):
            if u != name and u != '__DROP__':
                STUBS.add(u); WORK.append(ALL_SWINGUI[u])
    start = m.end() - 1
    end = match_brace(src_clean, start)
    full = src_clean[m.start():end + 1]
    if kind == 'enum':
        return indent + re.sub(r'\s+', ' ', full) if False else indent + full + '\n'
    body = src_clean[start + 1:end]
    lines = [indent + header.replace('private ', '') + ' {']
    is_iface = kind == 'interface'
    have_noarg = False; ctor_seen = False
    for h, b in members(body):
        h = strip_annotations(h)
        if not h: continue
        mods = set(re.findall(r'\b(public|protected|private|static|final|abstract|native|default|synchronized|transient|volatile)\b', h.split('(')[0]))
        visible = is_iface or 'private' not in mods
        if h in ('static', 'synchronized') or not re.search(r'\w\s+\w|\(', h):
            continue  # static/instance initializer block
        tm = re.match(r'((?:public|protected|private|abstract|static|final|sealed)\s+)*(class|interface|enum|record)\s+(\w+)', h)
        if tm:
            nested = emit_type(body, tm.group(3), indent + '  ', True)
            if nested: lines.append(nested.rstrip('\n'))
            continue
        if unknown_refs(h):
            continue
        if '(' in h.split('=')[0]:
            if not visible: continue
            pre = h[:h.index('(')]
            mname = pre.split()[-1].strip() if pre.split() else ''
            if mname == name:
                ctor_seen = True
                params = h[h.index('('):]
                params = re.sub(r'\bthrows\b.*$', '', params).strip()
                if re.fullmatch(r'\(\s*\)', params): have_noarg = True
                lines.append(f'{indent}  {" ".join(w for w in pre.split()[:-1])} {name}{params} {{ }}'.replace('  ', ' ').replace(f'{indent} ', indent + '  ', 1))
                continue
            rtype = ' '.join(w for w in pre.split() if w not in mods and w not in ('public','protected','private','static','final','abstract','native','default','synchronized'))
            rtype = re.sub(r'^<[^>]*>\s*', '', rtype) if rtype.startswith('<') and '>' in rtype and len(rtype.split()) > 1 else rtype
            rt_tokens = pre.split(); rt = rt_tokens[-2] if len(rt_tokens) >= 2 else 'void'
            decl = h.replace('synchronized ', '').replace('native ', '')
            decl = re.sub(r'\bthrows\b[^{;]*$', '', decl).strip()
            if (is_iface and b is None and 'default' not in mods and 'static' not in mods) or ('abstract' in mods and b is None):
                lines.append(f'{indent}  {decl};')
            else:
                lines.append(f'{indent}  {decl} {default_for(rt)}')
            continue
        # field
        if not visible or 'private' in mods: continue
        decl = h.split('=')[0].strip()
        fm = re.match(r'(.*?)(\w+)((?:\s*\[\s*\])*)$', decl)
        if not fm: continue
        head = fm.group(1).strip()
        names = [fm.group(2)]
        if head.endswith(','):
            # multi-declarator: split "type a, b, c"
            parts = [x.strip() for x in decl.split(',')]
            first = parts[0].split()
            names = [first[-1]] + parts[1:]
            head = ' '.join(first[:-1])
        ftype = head
        if is_iface and 'static' not in mods: ftype = 'static final ' + ftype
        base = ftype.split()[-1] if ftype.split() else ''
        for nm in names:
            nm = nm.strip()
            arr = '[]' in nm or '[]' in decl
            dflt = 'false' if base == 'boolean' and not arr else ('0' if base in ('byte','short','int','long','char','float','double') and not arr else 'null')
            lines.append(f'{indent}  {ftype} {nm} = {dflt};')
    if not is_iface and not have_noarg and kind != 'record':
        lines.append(f'{indent}  public {name}() {{ }}')
    lines.append(indent + '}')
    return '\n'.join(lines) + '\n'

def gen(fqcn):
    path = os.path.join(UP, *fqcn.split('.')) + '.java'
    if not os.path.exists(path):
        print('MISSING upstream', fqcn); return
    raw = open(path, encoding='utf-8', errors='ignore').read()
    c = clean(raw)
    for _k, _v in SWINGX_MAP.items():
        c = re.sub(r'\b' + _k + r'\b', _v, c)
    pkg = re.search(r'package\s+([\w.]+);', c).group(1)
    imps = []
    for m in re.finditer(r'import\s+(static\s+)?([\w.*]+);', c):
        if m.group(2).startswith(KEEP_IMPORT): imps.append(m.group(0))
    cls = fqcn.split('.')[-1]
    typ = emit_type(c, cls, '', False)
    if typ is None:
        print('PARSE FAIL', fqcn); return
    outp = os.path.join(OUT, *fqcn.split('.')) + '.java'
    os.makedirs(os.path.dirname(outp), exist_ok=True)
    with open(outp, 'w') as f:
        f.write(f'// GENERATED by tools/gen_swingui_stubs.py from upstream; Android stub, signatures only.\npackage {pkg};\n\n')
        keep = [i for i in sorted(set(imps)) if not any(re.search(r'\.' + re.escape(u) + r'(\.|;)', i) for u in unknown_refs(i))]
        f.write('\n'.join(keep) + '\n\n' + typ)

if __name__ == '__main__':
    lst = os.path.join(HERE, 'tools/swingui_stub_classes.txt')
    for line in open(lst):
        line = line.strip()
        if line and not line.startswith('#'):
            STUBS.add(line.split('.')[-1]); WORK.append(line)
    done = set()
    while WORK:
        f = WORK.pop(0)
        if f in done: continue
        done.add(f); gen(f)
    print('generated', len(done), 'stub classes')
    # hand-written replacements for stubs that must really work on Android
    import shutil
    hw = os.path.join(HERE, 'tools/handwritten_stubs')
    n = 0
    for root_, _, files_ in os.walk(hw):
        for f_ in files_:
            src = os.path.join(root_, f_)
            dst = os.path.join(OUT, os.path.relpath(src, hw))
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(src, dst)
            n += 1
    print('applied', n, 'hand-written stub replacements')
