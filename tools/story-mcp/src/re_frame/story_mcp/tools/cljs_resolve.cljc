(ns re-frame.story-mcp.tools.cljs-resolve
  "Cross-platform CLJS-var resolution for the JVM-side MCP tools.

  Several Story surfaces are CLJS-only (the registered-substrates
  registry, the in-browser a11y panel atom) — `def`s behind a
  `#?(:cljs …)` reader conditional, so no JVM Var exists for them. The
  JVM-side MCP server probes for them via `clojure.core/resolve` against
  a fully-qualified symbol; on the JVM that probe yields nil (the Var
  isn't there to find), and the caller reads an empty surface (the
  documented correct answer for a JVM host). When the SAME `.cljc`
  namespaces are compiled into and hosted by a CLJS runtime, the `:cljs`
  reader branch calls the Var directly — no `resolve`, the live registry.
  There is no JVM→browser bridge: a JVM process cannot deref a CLJS atom
  living in a browser heap, and does not try to.

  ## Caching

  `registered-substrates-var` is resolved ONCE at ns-load, the single
  resolution site for the whole story-mcp surface. The substrate set is
  stable across the process lifetime, so both the read-run-opts hot path
  (preview/run/snapshot) and `dev/tool-list-substrates` deref the cached
  var rather than re-resolving per call."
  (:require [re-frame.story :as story]))

(defn resolve-cljs-var
  "Resolve a fully-qualified symbol (`fully.qualified.ns/sym`) to the
  underlying var on the JVM, returning `nil` on miss. Wraps
  `clojure.core/resolve` in a try/catch so a CLJS-only `def` (which has
  no JVM Var) yields nil rather than blowing up.

  Callers MUST pass the FULLY-QUALIFIED ns (`re-frame.story/…`), not an
  alias-qualified symbol — even though `resolve` does honour the calling
  ns's aliases, passing the real ns keeps the contract self-describing
  and matches the sibling `'re-frame.story.ui.a11y/violations-by-frame`
  resolution site.

  Used by handlers that need a CLJS-side surface (the in-browser a11y
  panel atom, the CLJS substrate registry) — the JVM host reads an empty
  surface, and that's the documented correct answer."
  [sym]
  (try (resolve sym) (catch Throwable _ nil)))

#?(:clj
   ;; `re-frame.story/registered-substrates` is CLJS-only — probed ONCE at
   ;; ns-load, here, as the single resolution site for the whole story-mcp
   ;; surface. The substrate set is stable across the process lifetime, so
   ;; `read-run-opts` (preview/run/snapshot hot path) and
   ;; `dev/tool-list-substrates` both deref the cached var rather than
   ;; re-probing per call. On a JVM host the CLJS-only Var doesn't exist, so
   ;; the probe is nil and the set is empty; the `:cljs` accessor branch below
   ;; reads the live registry directly when these namespaces are hosted in a
   ;; CLJS runtime. The fully-qualified symbol matches the docstring + the
   ;; a11y resolution site.
   (defonce ^:private registered-substrates-var
     (resolve-cljs-var 're-frame.story/registered-substrates)))

(defn registered-substrates
  "The sorted vec of CLJS-registered substrate ids, or `[]` on a
  JVM-standalone deploy (the var is unresolvable). The single accessor
  over the cached `registered-substrates-var` — `dev/tool-list-substrates`
  and `read-run-opts` both read through here so the CLJS var is resolved
  exactly once at ns-load."
  []
  #?(:clj  (try
             (if registered-substrates-var
               (sort (vec (registered-substrates-var)))
               [])
             (catch Throwable _ []))
     :cljs (try (sort (vec (story/registered-substrates)))
                (catch :default _ []))))

(defn registered-substrates-set
  "The set form of the CLJS-registered substrate ids — used by
  `args/read-run-opts` as the bounded allowlist when coercing the
  agent-supplied `:substrate` arg through `safe-keyword`.

  The JVM-standalone deploy reads `#{}`; the nREPL-attached CLJS
  deploy reads the live registry's keys. Mirrors `registered-substrates`
  in caching posture — the cached var is the single resolution site."
  []
  #?(:clj  (if registered-substrates-var
             (try (set (registered-substrates-var)) (catch Throwable _ #{}))
             #{})
     :cljs (try (set (story/registered-substrates))
                (catch :default _ #{}))))
