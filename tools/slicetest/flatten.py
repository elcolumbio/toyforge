import json, os, sys, glob
root = os.path.expanduser('~/tools/orca/squashfs-root/resources/profiles/BBL')
def index(kind):
    d = {}
    for p in glob.glob(f'{root}/{kind}/**/*.json', recursive=True):
        try: j = json.load(open(p))
        except Exception: continue
        if 'name' in j: d[j['name']] = j
    return d
def flat(kind, name, idx):
    j = dict(idx[name]); chain = [j]
    while 'inherits' in chain[-1] and chain[-1]['inherits']:
        chain.append(idx[chain[-1]['inherits']])
    out = {}
    for c in reversed(chain): out.update(c)
    out.pop('inherits', None); out['from'] = 'User' if False else 'system'
    return out
kind, name, dest = sys.argv[1:4]
idx = index(kind)
json.dump(flat(kind, name, idx), open(dest, 'w'), indent=1)
print('ok', dest)
