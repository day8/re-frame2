import hashlib, json, pathlib, subprocess, urllib.request, concurrent.futures

OUT = pathlib.Path(__file__).resolve().parent
ROOT = OUT.parents[4]
assert (ROOT / 'implementation/resources').is_dir(), ROOT
paths = [
 'spec/016-Resources.md', 'spec/015-Data-Classification.md',
 'docs/EP/EP-0003-resource-queries.md', 'docs/EP/EP-0016-resource-mutation-completion.md',
 'docs/EP/EP-0019-optimistic-mutation-rollback.md', 'docs/EP/EP-0021-infinite-resources.md',
 'implementation/core/src/re_frame/core_resources.cljc',
 'tools/xray/spec/024-Resources-Panel.md',
 'tools/xray/src/day8/re_frame2_xray/panels/resources.cljs',
 'tools/xray/src/day8/re_frame2_xray/panels/resources_helpers.cljc',
 'tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/handler_meta.cljs',
 'skills/re-frame2/patterns/resources.md', 'skills/re-frame2/patterns/resources-mutations.md',
 'implementation/shadow-cljs.edn', 'implementation/package.json', 'implementation/deps.edn',
]
for pattern in ['docs/resources/**/*.md', 'implementation/resources/**/*.clj*',
 'examples/real-apps/realworld_resources/*', 'examples/real-apps/realworld_http/*.cljs',
 'examples/real-apps/realworld_shared/*.cljs', 'examples/capabilities/resources/**/*.cljs',
 'examples/capabilities/ssr/resources_ssr/*',
 'implementation/adapters/reagent/test/re_frame/realworld_resources*',
 'implementation/core/test/re_frame/example_realworld_resources*',
 'spec/conformance/fixtures/resources-*.edn']:
    paths.extend(str(p.relative_to(ROOT)).replace('\\','/') for p in ROOT.glob(pattern) if p.is_file())
manifest = []
for rel in sorted(set(paths)):
    src = ROOT / rel
    data = src.read_bytes()
    dest = OUT / 'local-source' / rel
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)
    manifest.append({'path':rel,'sha256':hashlib.sha256(data).hexdigest(),'bytes':len(data)})
(OUT/'local-source-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print('gate root:', ROOT)
print('Source files preserved:',len(manifest))

packages=['@tanstack/query-core','@tanstack/react-query','@tanstack/react-query-devtools',
 '@reduxjs/toolkit','swr','@apollo/client','@tanstack/db','react','react-dom','esbuild','typescript']
def package(name):
    url='https://registry.npmjs.org/'+name+'/latest'
    with urllib.request.urlopen(url,timeout=30) as r: data=json.load(r)
    return {'name':name,'version':data['version'],'url':url,'integrity':data.get('dist',{}).get('integrity'), 'repository':data.get('repository')}
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
    versions=list(pool.map(package,packages))
(OUT/'upstream-versions.json').write_text(json.dumps(versions,indent=2),encoding='utf-8')
print(json.dumps(versions,indent=2))
