#!/usr/bin/env python3
"""Dev helper: run the Android-like smoke test (no java.desktop) and auto-create empty shim classes
for each missing java.awt/javax.swing class until the run gets past class-loading problems.
Generated shims land in compat/src/main/java and should be reviewed/hand-improved afterwards."""
import os, re, subprocess, sys
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHIM_SRC = os.path.join(HERE, 'compat/src/main/java')
OUT = '/tmp/shimout'
CP = open('/tmp/androidlike.cp').read().strip()
JAVA = '/opt/android-studio/jbr/bin/java'
JAVAC = '/opt/android-studio/jbr/bin/javac'
MODS = 'java.base,java.logging,java.xml,java.sql,java.naming,java.net.http,java.security.sasl,java.security.jgss,java.xml.crypto,jdk.unsupported,jdk.crypto.ec,jdk.localedata'

SUPERS = {
  'com.kolmob.awt.Container': 'Component', 'com.kolmob.awt.Window': 'Container', 'com.kolmob.awt.Frame': 'Window',
  'javax.swing.JComponent': 'com.kolmob.awt.Container', 'javax.swing.JFrame': 'com.kolmob.awt.Frame',
  'javax.swing.JDialog': 'com.kolmob.awt.Window', 'javax.swing.JWindow': 'com.kolmob.awt.Window',
}
IFACE_SUFFIX = ('Listener', 'Handler', 'Model', 'Renderer', 'Constants', 'LayoutManager', 'LayoutManager2', 'Icon', 'Action', 'Border', 'Observer', 'Editor', 'Strategy', 'Factory', 'Policy')

def shim_for(fq):
    pkg, name = fq.rsplit('.', 1)
    path = os.path.join(SHIM_SRC, *pkg.split('.'), name + '.java')
    if os.path.exists(path): return False
    os.makedirs(os.path.dirname(path), exist_ok=True)
    simple = name.split('$')[0]
    if simple.endswith(IFACE_SUFFIX) or simple in ('Icon', 'SwingConstants', 'Action', 'ImageObserver', 'Shape', 'Transferable'):
        open(path, 'w').write(f'package {pkg};\n\n/** Android shim (auto-generated, no behaviour): {fq} */\npublic interface {simple} {{ }}\n')
        return True
    if simple.endswith('Exception'):
        open(path, 'w').write(f'package {pkg};\n\n/** Android shim (auto-generated): {fq} */\npublic class {simple} extends Exception {{\n  public {simple}() {{ }}\n\n  public {simple}(String message) {{ super(message); }}\n\n  public {simple}(String message, int offset) {{ super(message); }}\n}}\n')
        return True
    sup = SUPERS.get(fq)
    if sup is None:
        if pkg.startswith('javax.swing') and (simple.startswith('J') or simple == 'AbstractButton') and simple != 'JComponent':
            sup = 'javax.swing.JComponent'
    imp = ''; ext = ''
    if sup:
        if '.' not in sup:
            sup = 'com.kolmob.awt.' + sup
        shim_for(sup)
        ext = ' extends ' + sup.split('.')[-1]
        if sup.rsplit('.', 1)[0] != pkg:
            imp = f'import {sup};\n\n'
    open(path, 'w').write(f'package {pkg};\n\n{imp}/** Android shim (auto-generated, no behaviour): {fq} */\npublic class {simple}{ext} {{ }}\n')
    return True

PRIM = {'void': '{ }', 'boolean': '{ return false; }', 'int': '{ return 0; }', 'long': '{ return 0; }', 'short': '{ return 0; }',
        'byte': '{ return 0; }', 'char': '{ return 0; }', 'float': '{ return 0; }', 'double': '{ return 0; }'}

def add_method(out):
    m = re.search(r"NoSuchMethodError: '([\w.$\[\]]+) ([\w.$]+)\.([\w<>$]+)\(([^)]*)\)'", out)
    if not m: return None
    ret, cls, meth, params = m.groups()
    if not (cls.startswith('com.kolmob.awt.') or cls.startswith('javax.swing') or cls.startswith('javax.imageio')): return None
    for t in [ret] + [x.strip() for x in params.split(',') if x.strip()]:
        base = t.replace('[]', '')
        if base.startswith(('com.kolmob.awt.', 'javax.swing', 'javax.imageio')): shim_for(base.replace('$', '.'))
    path = os.path.join(SHIM_SRC, *cls.split('.')[:-1], cls.split('.')[-1].split('$')[0] + '.java')
    src = open(path).read()
    simple = cls.split('.')[-1]
    plist = ', '.join(f'{t.strip()} p{i}' for i, t in enumerate([x for x in params.split(',') if x.strip()]))
    # static if the caller line is "Simple.meth(" ; otherwise instance
    stat = False
    for ln in re.findall(r'at ([\w.$]+)\.[\w<>$]+\((\w+)\.java:(\d+)\)', out)[:1]:
        for root, _, fs in os.walk(os.path.join(HERE, 'kolmafia-core/src/main/java')):
            if ln[1] + '.java' in fs:
                try:
                    line = open(os.path.join(root, ln[1] + '.java')).read().split('\n')[int(ln[2]) - 1]
                    stat = bool(re.search(r'\b' + re.escape(simple) + r'\s*\.\s*' + re.escape(meth) + r'\s*\(', line))
                except Exception: pass
                break
    body = PRIM.get(ret) or ('{ return new ' + ret[:-2] + '[0]; }' if ret.endswith('[]') and '[][]' not in ret else '{ return null; }')
    if meth == '<init>':
        decl = f'  public {simple}({plist}) {{ }}\n'
    else:
        decl = f'  public {"static " if stat else ""}{ret} {meth}({plist}) {body}\n'
    if decl.strip() in src: return 'already'
    k = src.rindex('}')
    open(path, 'w').write(src[:k] + '\n' + decl + src[k:])
    return f'{cls}.{meth}({params}) static={stat}'

def compile_shims():
    srcs = [os.path.join(r, f) for r, _, fs in os.walk(SHIM_SRC) for f in fs if f.endswith('.java')]
    subprocess.run(['rm', '-rf', OUT]); os.makedirs(OUT)
    p = subprocess.run([JAVAC, '--limit-modules', 'java.base', '-d', OUT] + srcs, capture_output=True, text=True)
    if p.returncode: print(p.stdout[-2000:], p.stderr[-2000:]); sys.exit('shim compile failed')

def run():
    cmd = [JAVA, '-XX:+UnlockDiagnosticVMOptions', '-XX:-BytecodeVerificationRemote', '--limit-modules', MODS, '-Djava.awt.headless=true', '-cp', OUT + ':' + CP, 'net.sourceforge.kolmafia.SmokeMain']
    return subprocess.run(cmd, capture_output=True, text=True, timeout=180)

for i in range(int(sys.argv[1]) if len(sys.argv) > 1 else 40):
    compile_shims()
    p = run(); out = p.stdout + p.stderr
    m = re.search(r'NoClassDefFoundError: ([\w/$]+)', out)
    am = add_method(out)
    if am:
        print('method', am)
        if am == 'already': print(out[-1500:]); break
        continue
    if not m:
        print(out[-3500:]); print('--- no more NoClassDefFoundError; exit', p.returncode); break
    fq = m.group(1).replace('/', '.')
    if not (fq.startswith('com.kolmob.awt.') or fq.startswith('javax.swing') or fq.startswith('javax.imageio') or fq.startswith('javax.accessibility')):
        print(out[-2500:]); print('--- non-AWT/Swing missing class:', fq); break
    print('shim', fq, shim_for(fq))
