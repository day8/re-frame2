(ns re-frame.build.spec-resource
  "Read a committed `spec/` data file at MACRO-EXPANSION time, so that a
  ClojureScript build that inlines the value still depends on the bytes.

  ## Why a shared reader

  Several macros inline a committed file: the Freehand conformance
  fixtures, the api-manifest sidecar. Inlining at macro-expansion time by
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

  The JVM lanes need no such edge (`clojure -M:test` and the manifest
  generator have no persistent analyzer cache, so every run re-reads the
  file) and do not carry that root on their classpath, so outside a
  ClojureScript compile the same file is located by walking up from the
  compile CWD instead.

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
  rather than copied, and why every consumer calls it."
  (:require [clojure.java.io :as io]))

(def ^:private classpath-root
  "The repository directory that is the classpath root for these reads —
  the shadow-cljs `:source-path` the ClojureScript lane resolves resource
  paths against, and what the other lanes walk up the tree looking for."
  "spec")

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
  forced lazily because the JVM lanes never take this branch and
  shadow-cljs is not on their classpath."
  (delay (resolve-after-require 'shadow.resource/slurp-resource)))

(defn- spec-file
  "Locate the committed file at `path` by walking up from the compile CWD
  looking for the [[classpath-root]] directory — the locator for lanes
  where that root is not on the classpath. Throws an actionable error
  rather than yielding an absent value."
  [path]
  (loop [dir (io/file (System/getProperty "user.dir"))]
    (when (nil? dir)
      (throw (ex-info (str "Build-time resource reader: " classpath-root "/" path
                           " not found walking up from "
                           (System/getProperty "user.dir") ".")
                      {:path path :root classpath-root})))
    (let [candidate (io/file dir classpath-root path)]
      (if (.exists candidate)
        candidate
        (recur (.getParentFile dir))))))

(defn slurp-resource
  "Return the text of the committed `spec/` data file at `path` (relative
  to that root, e.g. `conformance/freehand/fixtures/fh-props-001.edn`),
  read in the macro-expansion environment `env`.

  Under a ClojureScript compile — `&env` carries the compiling `:ns` —
  the read goes through shadow-cljs, which registers `path` as a build
  dependency of that namespace: the edge that makes a data-only edit
  invalidate the cached consumer. Anywhere else there is no cache to
  invalidate and the file is read from the tree directly."
  [env path]
  (if (:ns env)
    (@recording-slurp env path)
    (slurp (spec-file path))))
