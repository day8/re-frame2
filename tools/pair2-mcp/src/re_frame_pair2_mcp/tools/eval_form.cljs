(ns re-frame-pair2-mcp.tools.eval-form
  "Mini-DSL for composing the CLJS eval forms tools ship over nREPL
  (rf2-dpzpe).

  Eight call-sites — `dispatch`, `trace-window`, `watch-epochs`,
  `snapshot`, `get-path`, `subscribe`, `subscribe`'s drain/unsubscribe
  loop forms, `subscription-info`, `unsubscribe`, plus `precheck-form`
  — build CLJS source strings by raw `str` concatenation. Two costs:

  1. The runtime namespace prefix (`re-frame-pair2.runtime/`)
     appeared verbatim at 17+ sites — one rename, seventeen edits.
  2. Tests had to `cljs.reader/read-string` a substring back out
     of the source to assert on the embedded EDN
     (`subscribe-test.cljs` line 96-98). The string-as-IR shape
     made the test fragile against whitespace / formatting drifts
     in the form-builder.

  This namespace gives a tiny tagged-vector IR with a single
  `emit` recursion:

      [::call  sym args]          →  `(re-frame-pair2.runtime/<sym> <emitted arg> ...)`
      [::call* qsym args]         →  `(<qsym> <emitted arg> ...)`  — fully-qualified
      [::let   bindings body]     →  `(let [<binding-name> <binding-val> ...] <body>)`
      [::raw   source-string]     →  `<source-string>`  — escape hatch
      [::quote v]                 →  `(quote v)`  — embedded EDN literal
      other value                 →  `(pr-str v)`  — EDN-printed literal

  `bindings` is a flat seq `[name form name form ...]`; binding NAMES
  must be symbols (emitted by `(name sym)`) so body forms can refer
  to them by name. Binding VALUES follow the same emit rules as
  call args.

  ## Usage idioms

      (def form (emit (rt-call 'subscribe! opts)))

      (def form
        (emit (rt-let ['snap (rt-call 'snapshot)]
                      (rt-raw \"(:app-db snap)\"))))

  ## Design notes

  - `rt-call` carries args as data; numbers / keywords / strings / maps
    / vectors are all `pr-str`'d at emit-time (Clojure-style EDN
    literal semantics — a string is a string-literal, not raw source).
    For raw-source fragments (e.g. referring to a let-bound name, a
    `loop`/`recur`, or a `(if ...)` host form), wrap with
    [[rt-raw]].

  - The runtime prefix lives in ONE place — `runtime-ns`. A rename of
    the runtime ns flows to every callsite by editing one string.

  - Tests assert against the DSL data:

        (= [::call 'subscribe! [{:topic :trace ...}]]
           (rt-call 'subscribe! {:topic :trace ...}))

    No regex over generated source, no whitespace fragility."
  (:refer-clojure :exclude [emit])
  (:require [clojure.string :as str]))

(def runtime-ns
  "The CLJS namespace every `rt-call` resolves into. Centralised here so
  a future runtime rename is one edit, not seventeen."
  "re-frame-pair2.runtime")

(defn rt-call
  "Build an IR node for a `runtime-ns`-relative function call. The
  symbol is unqualified; args are emit-time `pr-str`'d unless they're
  DSL nodes (which recurse) or [[rt-raw]] fragments."
  [sym & args]
  [::call sym (vec args)])

(defn rt-call*
  "Build an IR node for a fully-qualified function call. The symbol /
  string is emitted verbatim (no `runtime-ns` prefix). Used for
  `re-frame.core/elide-wire-value` and other cross-namespace calls
  inside an eval form."
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
  of `pr-str`'ing it as an EDN literal. The escape hatch for arbitrary
  CLJS expressions (loop/recur, fn literals, dotted-form host interop,
  references to let-bound names)."
  [source-string]
  [::raw source-string])

(defn- node? [x]
  (and (vector? x)
       (#{::call ::call* ::let ::raw} (first x))))

(declare emit)

(defn- emit-arg
  "Render a single arg / binding-value to source. DSL nodes recurse;
  collections of nodes recurse element-wise; everything else
  (strings, keywords, numbers, scalar maps, scalar vectors, …) is
  `pr-str`'d as an EDN literal.

  rf2-lbfzu — without the collection-recursion arm, a mixed
  data-and-nodes vector like `(rt-call 'foo [bar (rt-raw \"x\")])`
  would `pr-str` the whole vector including the `[::raw \"x\"]` IR
  node, producing garbage source. The recursion lets callers mix
  scalar data with IR nodes naturally; pure-scalar collections
  still go through `pr-str` unchanged (no contains-node walk →
  same byte-for-byte output)."
  [v]
  (cond
    (node? v)   (emit v)
    (and (vector? v) (some node? v))
    (str "[" (str/join " " (map emit-arg v)) "]")
    (and (list? v) (some node? v))
    (str "(" (str/join " " (map emit-arg v)) ")")
    :else       (pr-str v)))

(defn- emit-name [n]
  ;; Binding names must be symbols — they appear verbatim in the source
  ;; (no quoting) so body forms can refer to them by name.
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
                  ;; rf2-lbfzu — `(str qsym)` handles both shapes
                  ;; uniformly: a symbol prints as its fully-qualified
                  ;; name, a string prints verbatim. The prior `if`
                  ;; had two identical arms.
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
