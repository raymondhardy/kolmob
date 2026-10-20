import re,sys,collections
txt=open(sys.argv[1],errors='ignore').read()
errs=re.split(r'\n(?=[^\n]*\.java:\d+: error:)',txt)
cat=collections.Counter(); samples={}
for e in errs:
    m=re.search(r'\.java:\d+: error: (.*)',e)
    if not m: continue
    msg=m.group(1).strip()
    sym=re.search(r'symbol:\s+(.*)',e); loc=re.search(r'location:\s+(.*)',e)
    key=msg
    if 'cannot find symbol' in msg and sym:
        key='SYM '+sym.group(1).strip()+' @ '+(loc.group(1).strip() if loc else '?')
    elif 'does not exist' in msg: key=msg
    cat[key]+=1
    samples.setdefault(key,e.split('\n')[0])
print("TOTAL",sum(cat.values()))
for k,v in cat.most_common(int(sys.argv[2]) if len(sys.argv)>2 else 60):
    print(v,k)
