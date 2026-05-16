(ns day8.re-frame2-causa-mcp.eval-form
  "Mini-DSL for composing the CLJS eval forms causa-mcp tools ship over
  nREPL (rf2-8xzoe T-Insp tranche shared infra; sibling to pair2-mcp's
  `re-frame-pair2-mcp.tools.eval-form` which serves the same role with a
  different runtime ns prefix).

  Every inspection tool builds a CLJS source string addressed at
  `day8.re-frame2-causa.runtime/<accessor>`, hands it to
  `nrepl/cljs-eval-value`, and unwraps the returned envelope. Two costs
  motivate the DSL over raw string concatenation:

  1. The runtime namespace prefix appears at one site per tool — one
     rename, nine edits without this ns; one edit with it.
  2. Tests can assert against the DSL data (`[::call sym args]`) rather
     than regex over generated source — whitespace / formatting drifts
     in the form-builder don't break tests.

  ## DSL shapes

      [::call  sym args]      →  `(day8.re-frame2-causa.runtime/<sym> <emitted arg> …)`
      [::call* qsym args]     →  `(<qsym> <emitted arg> …)`  — fully-qualified
      [::let   bindings body] →  `(let [<binding-name> <binding-val> …] <body>)`
      [::raw   source-string] →  `<source-string>`  — escape hatch
      other value             →  `(pr-str v)` — EDN-printed literal

  ## Origin-tag wrapper

  Every causa-mcp eval form runs inside a
  `(binding [day8.re-frame2-causa.runtime/*current-origin* :causa-mcp]
     ...)`
  wrapper so the runtime stamps `:tags :origin :causa-mcp` on every
  mutation. `wrap-origin` is the helper inspection tools call once on the
  outer form before `emit`.

  ## Why the prefix isn't `re-frame.runtime/*` directly

  Per `tools/causa-mcp/spec/000-Vision.md` §Two namespaces, two sides,
  the causa runtime accessors live under `day8.re-frame2-causa.runtime`
  — the Node-side MCP server renders forms addressed at that ns; the
  browser-side preload installs the runtime. The MCP server never
  `:require`s the runtime ns (different process). The string prefix is
  the contract."
  (:refer-clojure :exclude [emit])
  (:require [clojure.string :as str]))

(def runtime-ns
  "The CLJS namespace every `rt-call` resolves into. Centralised here so
  a future runtime rename is one edit, not many."
  "day8.re-frame2-causa.runtime")

(def origin-var
  "Fully-qualified name of the runtime's `*current-origin*` dynamic var.
  Used by `wrap-origin` to thread the `:causa-mcp` origin tag through
  every mutation the runtime performs on the server's behalf."
  (str runtime-ns "/*current-origin*"))

(defn rt-call
  "Build an IR node for a `runtime-ns`-relative function call. The
  symbol is unqualified; args are emit-time `pr-str`'d unless they're
  DSL nodes (which recurse) or [[rt-raw]] fragments."
  [sym & args]
  [::call sym (vec args)])

(defn rt-call*
  "Build an IR node for a fully-qualified function call. The symbol /
  string is emitted verbatim (no `runtime-ns` prefix)."
  [qsym & args]
  [::call* qsym (vec args)])

(defn rt-let
  "Build an IR node for a `let` block. `bindings` is a flat seq of
  alternating names (symbols) and value-forms; trailing `body-forms`
  are wrapped in an implicit `do` if more than one is supplied."
  [bindings & body-forms]
  [::let (vec bindings) (vec body-forms)])

(defn rt-raw
  "Wrap a raw CLJS source string so `emit` inlines it verbatim instead
  of `pr-str`'ing it as an EDN literal."
  [source-string]
  [::raw source-string])

(defn- node? [x]
  (and (vector? x)
       (#{::call ::call* ::let ::raw} (first x))))

(declare emit)

(defn- emit-arg
  "Render a single arg / binding-value to source. DSL nodes recurse;
  collections of nodes recurse element-wise; everything else is
  `pr-str`'d as an EDN literal."
  [v]
  (cond
    (node? v)   (emit v)
    (and (vector? v) (some node? v))
    (str "[" (str/join " " (map emit-arg v)) "]")
    (and (list? v) (some node? v))
    (str "(" (str/join " " (map emit-arg v)) ")")
    :else       (pr-str v)))

(defn- emit-name [n]
  (when-not (symbol? n)
    (throw (ex-info "rt-let binding name must be a symbol"
                    {:name n :type (type n)})))
  (name n))

(defn- emit-body [forms]
  (case (count forms)
    0 "nil"
    1 (emit-arg (first forms))
    (str "(do " (str/join " " (map emit-arg forms)) ")")))

(defn emit
  "Render an IR node to a CLJS source string. The single recursion
  point for the DSL — every tool's eval form passes through here."
  [form]
  (cond
    (not (node? form))
    (pr-str form)

    :else
    (let [[tag] form]
      (case tag
        ::raw  (let [[_ s] form] s)

        ::call (let [[_ sym args] form]
                 (str "(" runtime-ns "/" (name sym)
                      (when (seq args)
                        (apply str (for [a args] (str " " (emit-arg a)))))
                      ")"))

        ::call* (let [[_ qsym args] form]
                  (str "(" (str qsym)
                       (when (seq args)
                         (apply str (for [a args] (str " " (emit-arg a)))))
                       ")"))

        ::let (let [[_ bindings body-forms] form
                    pairs (partition 2 bindings)]
                (str "(let ["
                     (str/join
                       " "
                       (for [[n v] pairs]
                         (str (emit-name n) " " (emit-arg v))))
                     "] "
                     (emit-body body-forms)
                     ")"))))))

(defn wrap-origin
  "Wrap an emitted CLJS source string in a `(binding
  [day8.re-frame2-causa.runtime/*current-origin* :causa-mcp] ...)`
  block so every mutation the runtime performs on behalf of the MCP
  server carries the `:origin :causa-mcp` tag. Inspection tools call
  this once at the outermost wrap step before shipping over nREPL.

  The dynamic-var rebinding is per-call (the synchronous extent of the
  eval'd form only); per the runtime ns docstring this is the
  documented async-tagging gap (Lock #4 / I6 of
  `tools/causa-mcp/spec/Principles.md`)."
  [form-str]
  (str "(binding [" origin-var " :causa-mcp] " form-str ")"))
