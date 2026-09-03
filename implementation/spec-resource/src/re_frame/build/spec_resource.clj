(ns re-frame.build.spec-resource
  "Read a committed `spec/` data file at MACRO-EXPANSION time, so that a
  ClojureScript build that inlines the value still depends on the bytes.

  ## Why a shared reader

  A macro inlines a committed file — today the api-manifest
  sidecar. Inlining at macro-expansion time by
  itself HIDES that file from the build — a compile that caches the
  expanding namespace has no edge back to the bytes it froze, so a
  DATA-ONLY edit leaves the cached namespace asserting the previous
  values. A confident green over an expectation nobody is checking is the
  worst answer a data-driven gate can give.

  `shadow.resource/slurp-resource` records the file's classpath path and
  last-modified against the compiling namespace, and shadow-cljs
  re-checks both before reusing that namespace's cache: edit the data,
  the consumer recompiles, no cache clearing and no ritual. `spec/` is a
  shadow-cljs `:source-path` — the one classpath root under which every
  committed spec-side data file a build inlines is resolvable — so a
  consumer names its file relative to that root.

  Every consumer is a ClojureScript macro, so that recorded edge is the
  only lane this reader has: [[slurp-resource]] requires a ClojureScript
  macro-expansion `&env` and rejects anything else rather than reaching
  the tree by a second, unrecorded route.

  ## Why the reader is resolved, and why resolving it is subtle

  RESOLVED rather than required: shadow-cljs is on the classpath of the
  ClojureScript lane and of no other, so naming it in a `:require` would
  make the JVM lanes — which need none of it — unloadable without it.

  `requiring-resolve` is the obvious way to do that, and it is racy here.
  It performs its first `resolve` BEFORE it enters the require lock, and
  Clojure interns a Var when it ANALYSES a `def` but binds its root only
  when it EVALUATES it. `shadow/resource.clj` ships un-AOT'd, so it
  compiles from source on first use and that window widens to a whole
  `defn`'s analysis and bytecode generation. shadow-cljs macroexpands
  namespaces in PARALLEL: a thread arriving mid-load gets the
  interned-but-UNBOUND Var and calling it throws `Attempting to call
  unbound fn`, failing the compile on thread scheduling rather than on
  anything in the tree.

  [[resolve-after-require]] inverts the order — the serialized require
  path FIRST, the resolve after — so the Var is observed only once its
  namespace has finished loading. A per-consumer `delay` does NOT fix
  this: it makes one consumer single-flight with ITSELF and leaves two
  consumers free to race each other. That is why this reader is shared
  rather than copied, and why every consumer calls it.")

(defn resolve-after-require
  "Resolve the Var named by the qualified symbol `sym`, entering
  Clojure's SERIALIZED require path before resolving rather than after.

  This is `requiring-resolve` with its two steps in the safe order.
  `locking clojure.lang.RT/REQUIRE_LOCK` is the same monitor
  `requiring-resolve` itself takes, so the load is serialized against
  every other `requiring-resolve` in the process; when `require` returns,
  the namespace has finished loading and its Vars are bound. Only then is
  the Var looked up.

  Throws when the namespace loads but does not define the Var — the
  actionable form of the version-skew failure that would otherwise
  surface as a null call."
  [sym]
  (locking clojure.lang.RT/REQUIRE_LOCK
    (require (symbol (namespace sym))))
  (or (resolve sym)
      (throw (ex-info (str "Build-time resource reader: " sym " is not defined after "
                           "loading " (namespace sym) ".")
                      {:sym sym}))))

(def ^:private recording-slurp
  "shadow-cljs's recording classpath reader, resolved once and cached.

  Cached because the resolution is the expensive, order-sensitive part;
  forced lazily so the namespace stays loadable without shadow-cljs —
  its own JVM test suite loads it on a classpath that has none."
  (delay (resolve-after-require 'shadow.resource/slurp-resource)))

(defn slurp-resource
  "Return the text of the committed `spec/` data file at `path` (relative
  to that root, e.g. `conformance/fixtures/after-hierarchy.edn`),
  read in the ClojureScript macro-expansion environment `env`.

  `env` MUST be a ClojureScript macro `&env` — it carries the compiling
  `:ns`. The read then goes through shadow-cljs, which registers `path`
  as a build dependency of that namespace: the edge that makes a
  data-only edit invalidate the cached consumer, and the whole reason
  this reader exists. Any other `env` is a caller error and says so."
  [env path]
  (when-not (:ns env)
    (throw (ex-info (str "Build-time resource reader: " path " must be read from a "
                         "ClojureScript macro-expansion environment (an `&env` "
                         "carrying :ns). Reading it any other way would inline the "
                         "bytes with no build dependency recorded against them.")
                    {:path path})))
  (@recording-slurp env path))
