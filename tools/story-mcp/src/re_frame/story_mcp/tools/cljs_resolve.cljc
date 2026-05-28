(ns re-frame.story-mcp.tools.cljs-resolve
  "Cross-platform CLJS-var resolution for the JVM-side MCP tools
  (rf2-8yvyp, split out of the former `tools.helpers`).

  Several Story surfaces are CLJS-only (the registered-substrates
  registry, the in-browser a11y panel atom). The JVM-side MCP server
  reaches them via `clojure.core/resolve` against a fully-qualified
  symbol — when the ns hasn't been required on JVM (the standalone
  deploy), the resolve yields nil rather than blowing up, and the
  caller reads an empty surface (the documented correct answer).

  ## Caching

  `registered-substrates-var` is resolved ONCE at ns-load (rf2-ee38b.17
  folded the duplicate `tools.dev/registered-substrates-var` into this
  one). The substrate set is stable across the process lifetime, so
  the cached var is the single resolution site for the whole story-mcp
  surface — both the read-run-opts hot path (preview/run/snapshot) and
  `dev/tool-list-substrates` deref the cached var rather than
  re-resolving per call."
  (:require [re-frame.story :as story]))

(defn resolve-cljs-var
  "Resolve a fully-qualified symbol (`ns/sym`) to the underlying var
  on the JVM, returning `nil` on miss. Wraps `clojure.core/resolve` in
  a try/catch so a CLJS-only `def` (whose ns hasn't been required on
  JVM) yields nil rather than blowing up.

  Used by handlers that need a CLJS-side surface (the in-browser a11y
  panel atom, the CLJS substrate registry) — the JVM-standalone deploy
  reads an empty surface, and that's the documented correct answer."
  [sym]
  (try (resolve sym) (catch Throwable _ nil)))

#?(:clj
   ;; `story/registered-substrates` is CLJS-only — resolved ONCE at ns-load,
   ;; here, as the single resolution site for the whole story-mcp surface
   ;; (rf2-ee38b.17 folded the duplicate `tools.dev/registered-substrates-var`
   ;; into this one). The substrate set is stable across the process
   ;; lifetime, so `read-run-opts` (preview/run/snapshot hot path) and
   ;; `dev/tool-list-substrates` both deref the cached var rather than
   ;; re-resolving per call. JVM-standalone deploy reads nil and yields an
   ;; empty set; the nREPL-attached CLJS deploy reads the var.
   (defonce ^:private registered-substrates-var
     (resolve-cljs-var 'story/registered-substrates)))

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
  agent-supplied `:substrate` arg through `safe-keyword` (rf2-lqjbk).

  The JVM-standalone deploy reads `#{}`; the nREPL-attached CLJS
  deploy reads the live registry's keys. Mirrors `registered-substrates`
  in caching posture — the cached var is the single resolution site."
  []
  #?(:clj  (if registered-substrates-var
             (try (set (registered-substrates-var)) (catch Throwable _ #{}))
             #{})
     :cljs (try (set (story/registered-substrates))
                (catch :default _ #{}))))
