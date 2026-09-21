import pathlib, re, json
HERE=pathlib.Path(__file__).resolve().parent
ROOT=HERE.parents[4]
consumer=HERE/'consumer'
consumer.mkdir(exist_ok=True)
doc=(ROOT/'docs/resources/tutorial/02-server-data.md').read_text(encoding='utf-8')
deps=re.search(r'```clojure\s*(\{:deps.*?\n)```',doc,re.S).group(1)
deps=deps.replace('../re-frame2', ROOT.as_posix())
(consumer/'deps.edn').write_text(deps,encoding='utf-8')
(consumer/'literal-deps.edn').write_text(deps,encoding='utf-8')
(consumer/'probe.clj').write_text('(println "consumer:" (System/getProperty "user.dir"))\n(require \'re-frame.http.managed)\n(println "managed HTTP loaded")\n',encoding='utf-8')
comparator=HERE/'tanstack'
comparator.mkdir(exist_ok=True)
versions=json.loads((HERE/'upstream-versions.json').read_text())
chosen=['@tanstack/react-query','@tanstack/query-core','@tanstack/react-query-devtools','react','react-dom','esbuild','typescript']
(comparator/'package.json').write_text(json.dumps({'private':True,'type':'module','dependencies':{p['name']:p['version'] for p in versions if p['name'] in chosen}},indent=2),encoding='utf-8')
print('Prepared isolated tutorial dependency probe and pinned TanStack consumer.')
