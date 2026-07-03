(ns day8.re-frame2-template.template-emission-test
  "Static-parse tests for the template's emitted cljs scaffold.

   The sibling `template_test.clj` verifies the generated file *shape*:
   names, deps.edn coords, shadow-cljs.edn wiring. It does NOT verify
   that the emitted cljs files would actually compile against the
   re-frame2 framework. A rename of a public var (the bang-rename, the
   views-macros cut, an interceptor relocation) ships green from
   shape-only checks because the template's resource tree is a string —
   not a compile target — at template-build time.

   This test closes the gap *cheaply* — no shadow-cljs, no Node, no
   network. For each substrate's generated app it:

     1. Parses the emitted `events_test.cljs` ns form, asserts the
        expected requires are present (catches drift in the template's
        own scaffold — e.g. someone bumps an alias but forgets the
        require).
     2. Walks the file body for every `<alias>/<symbol>` reference,
        resolves the alias against the ns form's requires, and asserts
        the underlying symbol is actually defined in the framework
        source under `implementation/core/src/`. This is the rename
        smoke test: if `re-frame.core/dispatch-sync` got renamed to
        `dispatch-sync!`, the emitted test ships stale and this check
        fires.
     3. Repeats step 2 for `events.cljs`, `subs.cljs`, and `views.cljs`
        — same drift hazard, no extra harness cost.

   The deeper-fidelity option (run shadow-cljs on the generated app) is
   left for after alpha publish; until then, static parse catches the
   most likely regression."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [clojure.tools.reader :as tr]
            [clojure.tools.reader.reader-types :as rt]
            [day8.re-frame2-template.test-support
             :refer [tmp-dir delete-recursively run-template!
                     run-template-opts! repo-root]]))

;; --- Helpers ---------------------------------------------------------------
;;
;; tmp-dir / delete-recursively / template-resource-dir / run-template! /
;; repo-root live in the shared `test-support` ns.

;; --- Static-parse machinery ----------------------------------------------

(defn- read-cljs-forms
  "Read every top-level form from a .cljs file. Each form is returned in
  source order. Uses `clojure.tools.reader` (not `clojure.edn`) because
  the emitted views.cljs contains `#(...)` function literals and the
  framework cljc files use reader conditionals. We bind `*read-eval*`
  off and treat unknown tags as identity so any future `#js` etc. in
  the scaffold doesn't trip us."
  [^java.io.File f]
  (let [eof    (Object.)
        reader (rt/source-logging-push-back-reader (slurp f))
        ;; tools.reader respects *data-readers*; supply a permissive
        ;; default-data-reader so unknown tags don't throw.
        opts   {:eof eof :read-cond :allow :features #{:cljs}
                :default (fn [_tag value] value)}]
    (binding [tr/*read-eval* false
              tr/*default-data-reader-fn* (fn [_tag value] value)]
      (loop [acc []]
        (let [form (tr/read opts reader)]
          (if (identical? form eof)
            acc
            (recur (conj acc form))))))))

(defn- parse-ns-requires
  "Given a `(ns ns-sym ...)` form, return a map
     {alias-symbol  required-ns-symbol
      ...
      ::required #{ns-sym ...}
      ::referred {referred-sym required-ns-symbol ...}}
  for every `:require` / `:require-macros` clause. Aliases come from
  `[ns :as alias]`; `:refer`-ed symbols (`[ns :refer [a b]]`) contribute
  to `::referred` (mapping each bare symbol to its source ns). Bare
  requires (`[ns]` / `ns`) contribute to `::required` only. Side-effecting
  requires (no `:as`) still count as 'required' for the purpose of the
  load-order assertion below.

  Both `:require` and `:require-macros` are walked: the Reagent scaffold
  brings `re-frame.core` in via the one-require form
  `[re-frame.core :as rf]` and calls `rf/reg-view` qualified, so the
  qualified-symbol audit sees that central macro. Walking `:require-macros`
  too keeps any `:refer`-ed symbols visible to the surface-drift audit,
  and `:refer`-ed symbols from either clause feed the bare-symbol audit
  below."
  [ns-form]
  (let [clauses    (drop 2 ns-form) ;; skip `ns` + ns-sym
        require?   #(and (sequential? %)
                         (#{:require :require-macros} (first %)))
        req-bodies (->> (filter require? clauses)
                        (mapcat rest))]
    (reduce
      (fn [acc spec]
        (cond
          (symbol? spec)
          (update acc ::required (fnil conj #{}) spec)

          (and (vector? spec) (symbol? (first spec)))
          (let [ns-sym    (first spec)
                spec-tail (rest spec)            ;; the `:as alias`, `:refer […]`, … pairs
                opt-pairs (partition 2 spec-tail)
                alias-sym (some (fn [[k v]] (when (= k :as) v)) opt-pairs)
                referred  (some (fn [[k v]] (when (= k :refer) v)) opt-pairs)]
            (cond-> (update acc ::required (fnil conj #{}) ns-sym)
              alias-sym (assoc alias-sym ns-sym)
              (and (sequential? referred))
              (update ::referred
                      (fnil into {})
                      (->> referred
                           (filter symbol?)
                           (map (fn [s] [s ns-sym]))))))

          :else acc))
      {::required #{} ::referred {}}
      req-bodies)))

(defn- collect-qualified-symbols
  "Walk `forms` (any nested structure) and collect every symbol with a
  non-nil namespace component. Returns a set."
  [forms]
  (let [acc (volatile! #{})]
    (walk/postwalk
      (fn [x]
        (when (and (symbol? x) (some? (namespace x)))
          (vswap! acc conj x))
        x)
      forms)
    @acc))

(defn- collect-bare-symbols
  "Walk `forms` and collect every unqualified symbol (no namespace
  component). Returns a set. Used to detect bare, `:refer`-ed framework
  symbols — e.g. the Reagent scaffold's `(reg-view …)` calls, which are
  unqualified and therefore invisible to `collect-qualified-symbols`."
  [forms]
  (let [acc (volatile! #{})]
    (walk/postwalk
      (fn [x]
        (when (and (symbol? x) (nil? (namespace x)))
          (vswap! acc conj x))
        x)
      forms)
    @acc))

(defn- framework-ns-file
  "Map a re-frame.X namespace symbol to its source-of-truth file under
  `implementation/` or `tools/`. Returns the `java.io.File`, or nil if
  we don't know about this ns (we only audit framework namespaces —
  the user's own `<app>.events` / `.subs` aren't audited here; the
  bead is about drift against the framework surface).

  Search order:

    1. `implementation/core/src/re_frame/<rel>.{cljc,cljs}` — the core
       coord ships everything under `re-frame.*` except adapters and
       downstream tool coords.
    2. `implementation/adapters/<flavour>/src/re_frame/adapter/<name>.cljs`
       — `re-frame.adapter.helix`, `re-frame.adapter.uix`, etc. live in
       their own per-substrate coords.
    3. `tools/story/src/re_frame/story.cljc` (+ `re-frame.story.*`
       sub-namespaces) — Story ships as its own downstream tool coord
       (`day8/re-frame2-story`) under `tools/story/`. The template's
       with-stories core requires `re-frame.story`; the audit needs
       to find it where it actually lives."
  [root ns-sym]
  (let [name- (name ns-sym)]
    (cond
      (string/starts-with? name- "re-frame.")
      (let [rel (-> name-
                    (subs (count "re-frame."))
                    (string/replace "-" "_")
                    (string/replace "." "/"))
            adapter-flavour
            (when (string/starts-with? name- "re-frame.adapter.")
              ;; "re-frame.adapter.helix" → "helix"; "re-frame.adapter.uix" → "uix";
              ;; "re-frame.adapter.context" stays in core (caught by candidates below).
              (let [leaf (subs name- (count "re-frame.adapter."))]
                ;; Only treat per-substrate adapter coords as out-of-core;
                ;; `re-frame.adapter.context` ships from core.
                (when (#{"helix" "uix" "reagent" "reagent-slim"} leaf)
                  leaf)))
            candidates (cond-> [(io/file root "implementation/core/src/re_frame" (str rel ".cljc"))
                                (io/file root "implementation/core/src/re_frame" (str rel ".cljs"))
                                ;; tools/story/ — re-frame.story + re-frame.story.* live here.
                                (io/file root "tools/story/src/re_frame" (str rel ".cljc"))
                                (io/file root "tools/story/src/re_frame" (str rel ".cljs"))]
                         adapter-flavour
                         (conj (io/file root "implementation/adapters"
                                        adapter-flavour
                                        "src/re_frame/adapter"
                                        (str adapter-flavour ".cljs"))))]
        (some (fn [f] (when (.isFile f) f)) candidates))

      :else nil)))

(def ^:private ^java.util.regex.Pattern def-pattern
  ;; Reader conditionals (cljc) are not stripped — we scan raw source
  ;; text, so a symbol defined in *either* the :clj or :cljs branch is
  ;; treated as defined. That's exactly what we want for a
  ;; surface-existence check: cljs consumers of a cljc namespace see
  ;; the :cljs branch's defs.
  ;;
  ;; The regex tolerates one or more leading metadata clauses
  ;; (`^:private`, `^:no-doc`, `^{...}`, `^TypeHint`) between the
  ;; defining form and the symbol name.
  ;;
  ;; Compiled once at ns-load — the audit loop scans each framework file
  ;; many times (×N framework files × N references × 3 substrates =
  ;; thousands of scans per suite run), so the pattern is built once.
  ;; The `^{...}` metadata-map alternative tolerates ONE level of brace
  ;; nesting (`\{(?:[^{}]++|\{[^{}]*+\})*+\}`) so a `def` whose docstring
  ;; embeds a literal map — e.g. `frame-provider`'s `Usage: [rf/frame-provider
  ;; {:frame :todo} …]` — is still detected. A naive `\{[^}]*\}` would stop
  ;; at the FIRST inner `}`, missing such a def's symbol so that any
  ;; template reference to it would trip the audit (the carried-frame
  ;; scaffold references `rf/frame-provider`, whose framework def carries
  ;; an embedded-brace docstring).
  ;;
  ;; POSSESSIVE quantifiers. The nested `(?:…|…)*` shape is a classic
  ;; catastrophic-backtracking hazard: on a large source file
  ;; (`re-frame.core/core.cljc`, ~3.1K lines) a greedy form can recurse deep
  ;; enough to throw `StackOverflowError` when an audited emitted file
  ;; references a `re-frame.core` symbol whose scan pushes the call stack a
  ;; little further. Making the inner alternation (`[^{}]++` / `[^{}]*+`) and
  ;; the outer meta-clause repetition (`*+`) POSSESSIVE means the engine
  ;; never backtracks into already-matched metadata — the match is linear
  ;; and stack-flat, with identical match semantics (the language matched is
  ;; unchanged; only the backtracking is removed). `def`/`defn` names are
  ;; `\s+`-terminated, so a possessive meta-clause never over-consumes the
  ;; symbol it must capture.
  (let [meta-clause "(?:\\^(?:\\w[\\w/.:?<>=*+!\\-]*|\\{(?:[^{}]++|\\{[^{}]*+\\})*+\\})\\s+)*+"
        sym-char    "[a-zA-Z*+!?<>=$%_\\-][\\w*+!?<>=$%\\-]*"]
    (re-pattern
      (str "\\(def(?:n-?|macro|multi|once|protocol|record|type)?\\s+"
           meta-clause
           "(" sym-char ")"))))

(defn- scan-defined-symbols
  "Slurp+regex a single framework .cljc/.cljs source and return the
  set of symbols it introduces with a top-level `def` / `defn` /
  `defn-` / `defmacro` / `defmulti` / `defonce` / `defprotocol` /
  `defrecord` / `deftype`. Memoised entry point is `defined-symbols`
  below — direct callers should prefer that."
  [^java.io.File f]
  (into #{}
        (map (fn [[_ sym]] (symbol sym)))
        (re-seq def-pattern (slurp f))))

(def ^:private defined-symbols-cache
  ;; Keyed by canonical absolute path. The framework source tree is
  ;; immutable for the lifetime of a test JVM (no hot-reload mid-suite
  ;; under `clojure -M:test`), so path is a sufficient key — mtime is
  ;; not required. Cleared automatically when the JVM exits.
  (atom {}))

(defn- defined-symbols
  "Memoised wrapper over `scan-defined-symbols`. The audit loop calls
  this with the same framework source file once per (substrate ×
  referenced-symbol) pair — without memoisation each emitted-file's
  audit re-slurps and re-regexes every framework `.cljc`/`.cljs` it
  references. With memoisation the cost collapses to one slurp+regex
  per framework source file per JVM."
  [^java.io.File f]
  (let [k (.getAbsolutePath f)]
    (or (get @defined-symbols-cache k)
        (let [v (scan-defined-symbols f)]
          (swap! defined-symbols-cache assoc k v)
          v))))

;; --- The events_test.cljs assertions ------------------------------------

(def ^:private expected-events-test-requires
  "ns symbols the emitted events_test.cljs MUST require. Drift here
  means the test scaffold has fallen out of sync with the registrar /
  fixture / substrate API."
  '#{cljs.test
     re-frame.core
     re-frame.substrate.plain-atom
     re-frame.test-support})

(defn- assert-events-test-shape!
  [substrate ^java.io.File root]
  (let [test-file (io/file root "test/acme/my_app/events_test.cljs")
        _        (is (.isFile test-file)
                     (str "events_test.cljs emitted for " substrate))
        forms    (read-cljs-forms test-file)
        ns-form  (first forms)
        _        (is (and (sequential? ns-form) (= 'ns (first ns-form)))
                     "first form is the ns form")
        requires (parse-ns-requires ns-form)
        required (::required requires)]

    ;; --- (1) The framework surfaces we require -----------------------------
    (doseq [needed expected-events-test-requires]
      (is (contains? required needed)
          (str "events_test.cljs (" substrate ") requires " needed
               " — current requires: " (sort required))))

    ;; --- (2) The user's events / subs nses are required, ns-loading
    ;;        their registrations into the registrar before the deftests
    ;;        run. Without this, dispatch/frame-db would hit empty.
    (is (contains? required 'acme.my-app.events)
        "events_test.cljs requires the user's events ns")
    (is (contains? required 'acme.my-app.subs)
        "events_test.cljs requires the user's subs ns")

    ;; --- (3) The aliases used in the body resolve to required nses ---
    (let [body-forms (rest forms)
          quals      (collect-qualified-symbols body-forms)]
      (doseq [sym quals]
        (let [alias-sym (symbol (namespace sym))]
          (when-not (#{"js" "cljs.core" "clojure.core"} (str alias-sym))
            (is (contains? requires alias-sym)
                (str "qualified symbol " sym " in events_test.cljs ("
                     substrate ") uses alias " alias-sym
                     " but no matching :as is declared in the ns form"))))))))

;; --- Strict EP-0017 cofx mint policy guard ---------------------------------
;;
;; EP-0017's strict-test posture: a generated app that later adds a
;; generator-backed recordable coeffect must not be able to write a green
;; test that forgot to supply the fact (the default live policy would
;; silently mint a fresh per-run value — the opposite of
;; supply-data-don't-stub). The emitted `events_test.cljs` fixture therefore
;; establishes a STRICT mint policy by default — via `{:preset :test}` (which
;; sets `:rf.cofx/mint-policy :strict`) or an explicit
;; `:rf.cofx/mint-policy :strict`. This guard reads the emitted file and
;; fails if the strict posture is dropped, or if the supply-data /
;; explicit-live testing idiom is not documented for the user. It also
;; locks out non-EP-0017/EP-0018 vocabulary (`:rf.world/inputs`,
;; grouped/nested cofx, `inject-cofx`).

(defn- assert-events-test-strict-mint-policy!
  [substrate ^java.io.File root]
  (let [test-file (io/file root "test/acme/my_app/events_test.cljs")]
    (is (.isFile test-file)
        (str "events_test.cljs emitted for " substrate))
    (when (.isFile test-file)
      (let [text (slurp test-file)]
        ;; (1) The fixture establishes a strict mint policy by default —
        ;; either the `:test` preset (which expands to
        ;; `:rf.cofx/mint-policy :strict`) or an explicit strict policy.
        (is (or (re-find #":preset\s+:test" text)
                (re-find #":rf\.cofx/mint-policy\s+:strict" text))
            (str "events_test.cljs (" substrate ") must run under a STRICT "
                 "EP-0017 cofx mint policy by default — register the test "
                 "frame with `{:preset :test}` or set "
                 "`:rf.cofx/mint-policy :strict`. Without it a generated app "
                 "that adds a generator-backed recordable coeffect can ship a "
                 "green test that forgot to supply the fact (the live policy "
                 "silently mints a fresh per-run value — the opposite of "
                 "EP-0017's supply-data-don't-stub posture)."))
        ;; (2) The supply-data idiom is documented for recordable coeffects:
        ;; supply facts FLAT under `:rf.cofx`.
        (is (re-find #":rf\.cofx\b" text)
            (str "events_test.cljs (" substrate ") must document the EP-0017 "
                 "supply-data idiom — supply required recordable facts FLAT "
                 "under `:rf.cofx` in the dispatch opts."))
        ;; (3) The intentional-nondeterminism escape hatch is documented.
        (is (re-find #":rf\.cofx/mint-policy\s+:explicit-live" text)
            (str "events_test.cljs (" substrate ") must document the "
                 "`:rf.cofx/mint-policy :explicit-live` opt-out — the per-call "
                 "escape a test uses when it INTENTIONALLY accepts "
                 "nondeterministic generation."))
        ;; (4) No non-EP-0017/EP-0018 vocabulary leaks into the scaffold.
        (doseq [[re what]
                [[#":rf\.world/inputs" ":rf.world/inputs (retired — declare :rf.cofx/requires)"]
                 [#"inject-cofx"        "inject-cofx (removed — declare :rf.cofx/requires)"]]]
          (is (not (re-find re text))
              (str "events_test.cljs (" substrate ") must not use legacy "
                   "EP-0017/EP-0018 vocabulary: " what ".")))))))

;; --- Generic ns-surface drift check (events / subs / views / events_test)
;; ----------------------------------------------------------------------------

(defn- audit-framework-symbol!
  "Assert `sym` is defined in framework ns `target-ns`'s source file.
  `label` describes how the symbol was referenced (qualified vs bare
  `:refer`) for the failure message."
  [substrate ^java.io.File file root target-ns sym label]
  (if-let [framework-file (framework-ns-file root target-ns)]
    (let [defined (defined-symbols framework-file)]
      (is (contains? defined sym)
          (str (.getName file) " (" substrate ") " label " "
               target-ns "/" sym
               " but it is NOT defined in "
               (.getPath framework-file)
               " — likely a rename/cut. Defined symbols there: "
               (sort defined))))
    ;; If the framework file isn't found, that's its own drift
    ;; signal — the template requires a namespace the framework
    ;; doesn't ship.
    (is false
        (str (.getName file) " (" substrate ") " label " " target-ns
             " but no source file found under implementation/core/src/"))))

(defn- audit-framework-surface!
  "For `file`, parse it, collect every framework reference, and assert
  each is defined in its framework ns's source file. This is the
  surface-drift smoke test: catches a rename / cut / relocation of a
  public var that the template scaffold still references. Two reference
  shapes are audited:

    1. `<alias>/<sym>` qualified references whose alias resolves to a
       `re-frame.*` ns (e.g. `rf/dispatch`, `rf/reg-view` — the Reagent
       scaffold uses the one-require form `[re-frame.core :as rf]` and
       calls `rf/reg-view` qualified). Without this, a rename of
       `reg-view` would ship the Reagent scaffold broken-but-green.
    2. Bare, `:refer`-ed framework symbols whose source ns
       is `re-frame.*` (none in the current scaffolds, but the audit
       still covers the shape so a future `:refer` stays honest)."
  [substrate ^java.io.File file root]
  (when (.isFile file)
    (let [forms      (read-cljs-forms file)
          ns-form    (first forms)
          requires   (parse-ns-requires ns-form)
          body-forms (rest forms)
          ;; (1) A vector of [referenced-symbol resolved-ns] pairs for
          ;; every qualified symbol whose alias resolves to a re-frame.*
          ;; ns.
          qual-refs  (->> (collect-qualified-symbols body-forms)
                          (keep (fn [qsym]
                                  (let [alias-sym (symbol (namespace qsym))
                                        target-ns (get requires alias-sym)]
                                    (when (and target-ns
                                               (string/starts-with?
                                                 (name target-ns)
                                                 "re-frame."))
                                      [qsym target-ns]))))
                          ;; Dedup by (target-ns + symbol-name) — the same
                          ;; reference often appears many times in a file
                          ;; (e.g. rf/dispatch in views).
                          (group-by (fn [[qsym target-ns]]
                                      [target-ns (symbol (name qsym))]))
                          keys)
          ;; (2) Bare, `:refer`-ed framework symbols that actually appear
          ;; in the body. We intersect the ns form's `:refer` set with the
          ;; bare symbols the body uses, so an unused `:refer` (dead, but
          ;; harmless) isn't flagged — only symbols the scaffold relies on.
          referred   (::referred requires)
          bare-used  (collect-bare-symbols body-forms)
          bare-refs  (->> referred
                          (keep (fn [[sym target-ns]]
                                  (when (and (contains? bare-used sym)
                                             (string/starts-with?
                                               (name target-ns)
                                               "re-frame."))
                                    [sym target-ns]))))]
      (doseq [[target-ns sym] qual-refs]
        (audit-framework-symbol! substrate file root target-ns sym
                                 "references"))
      (doseq [[sym target-ns] bare-refs]
        (audit-framework-symbol! substrate file root target-ns sym
                                 "refers (bare)")))))

;; --- scratch.cljs with-frame shape audit -----------------------------------
;;
;; The emitted dev/scratch.cljs is the user's REPL on-ramp. The two
;; frame-scope macros are non-overlapping:
;;
;;   `with-frame`     — pin form: first arg MUST be a keyword (or
;;                      keyword-resolving symbol). Vector arg is a
;;                      compile-time error.
;;   `with-new-frame` — eval/destroy form: first arg MUST be a
;;                      2-element `[sym expr]` vector. Keyword arg is
;;                      a compile-time error.
;;
;; This audit walks every `with-frame` / `with-new-frame` form in
;; scratch and asserts the first-arg shape is right for the chosen
;; macro.

(defn- with-frame-call?
  "True when `form` is `(rf-or-alias/with-frame …)` — covers both
  alias-qualified (`rf/with-frame`) and bare (`with-frame`) usage."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (= "with-frame" (name (first form)))))

(defn- with-new-frame-call?
  "True when `form` is `(rf-or-alias/with-new-frame …)`."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (= "with-new-frame" (name (first form)))))

(defn- valid-with-frame-first-arg?
  "Pin form: keyword or symbol (resolves to keyword). A vector arg is a
  compile-time error."
  [arg]
  (or (keyword? arg) (symbol? arg)))

(defn- valid-with-new-frame-first-arg?
  "Eval/destroy form: 2-elem [sym expr] vector. Anything else is a
  compile-time error."
  [arg]
  (and (vector? arg)
       (= 2 (count arg))
       (symbol? (first arg))))

(defn- collect-calls
  "Walk `forms` and return every form matching `pred`, including
  those nested inside `(comment …)` blocks (which is where the emitted
  scratch ns puts its examples)."
  [pred forms]
  (let [acc (volatile! [])]
    (walk/postwalk
      (fn [x]
        (when (pred x)
          (vswap! acc conj x))
        x)
      forms)
    @acc))

(defn- assert-scratch-with-frame-shape!
  [substrate ^java.io.File root]
  (let [scratch (io/file root "dev/scratch.cljs")]
    (is (.isFile scratch)
        (str "dev/scratch.cljs emitted for " substrate))
    (let [forms          (read-cljs-forms scratch)
          pin-calls      (collect-calls with-frame-call? forms)
          new-frame-calls (collect-calls with-new-frame-call? forms)]
      (is (or (seq pin-calls) (seq new-frame-calls))
          (str "scratch.cljs (" substrate
               ") contains at least one (with-frame …) or "
               "(with-new-frame …) example — the REPL on-ramp should "
               "demonstrate at least one frame-scope shape"))
      (doseq [call pin-calls]
        (let [first-arg (second call)]
          (is (valid-with-frame-first-arg? first-arg)
              (str "scratch.cljs (" substrate ") (with-frame "
                   (pr-str first-arg) " …) — first arg must be a "
                   "keyword or symbol (pin form). Per rf2-twoc5, a "
                   "vector argument is a compile-time error; use "
                   "`with-new-frame` for eval-bind-run-destroy."))))
      (doseq [call new-frame-calls]
        (let [first-arg (second call)]
          (is (valid-with-new-frame-first-arg? first-arg)
              (str "scratch.cljs (" substrate ") (with-new-frame "
                   (pr-str first-arg) " …) — first arg must be a "
                   "2-element [sym expr] vector. Per rf2-twoc5, a "
                   "keyword argument is a compile-time error; use "
                   "`with-frame` to pin to an existing frame-id.")))))))

;; --- scratch.cljs frame-context audit --------------------------------------
;;
;; EP-0002 removed the implicit `:rf/default` floor: a frame-scoped call
;; (`dispatch` / `dispatch-sync` / `subscribe`) evaluated with no
;; established scope raises `:rf.error/no-frame-context`
;; (implementation/core/src/re_frame/core.cljc §current-frame-id). The
;; emitted dev/scratch.cljs is executable generated code — its REPL
;; on-ramp must NOT ship forms that throw on first eval.
;;
;; `assert-scratch-with-frame-shape!` above checks the first-arg shape of
;; the `with-frame` / `with-new-frame` forms, but a bare `(rf/dispatch
;; [:counter/increment])` sitting OUTSIDE any frame-scope form, with no
;; `:frame` opt, still passes that audit while being frameless. This audit
;; closes that gap: every frame-scoped call in scratch.cljs must EITHER
;;   (a) sit lexically inside a `with-frame` / `with-new-frame` body (which
;;       pins / creates a frame for the duration), OR
;;   (b) carry an explicit frame target inline:
;;         - `dispatch` / `dispatch-sync`: a `:frame` key in the opts map
;;           (the trailing map arg), e.g.
;;           `(rf/dispatch [:e] {:frame :rf/default})`.
;;         - `subscribe`: the 2-arity `(rf/subscribe <frame-id> [query])`
;;           form, whose first arg is a keyword/symbol frame-id rather
;;           than the query vector.

(def ^:private frame-scoped-call-names
  "Bare names (alias-stripped) of the frame-scoped REPL ops scratch.cljs
  may use. Each requires a resolvable frame at eval time (EP-0002)."
  #{"dispatch" "dispatch-sync" "subscribe"})

(defn- frame-scope-form?
  "True when `form` opens a frame scope for its body —
  `(with-frame …)` or `(with-new-frame …)` (alias-qualified or bare)."
  [form]
  (or (with-frame-call? form) (with-new-frame-call? form)))

(defn- frame-scoped-call?
  "True when `form` is `(<rf-or-alias>/dispatch …)` / `…/dispatch-sync` /
  `…/subscribe` — a call whose target frame must resolve at eval time."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (contains? frame-scoped-call-names (name (first form)))))

(defn- dispatch-has-explicit-frame?
  "True when a `dispatch` / `dispatch-sync` call carries `:frame` in its
  trailing opts map — `(rf/dispatch [:e] {:frame :rf/default})`."
  [form]
  (let [opts (last form)]
    (and (> (count form) 2)
         (map? opts)
         (contains? opts :frame))))

(defn- subscribe-has-explicit-frame?
  "True when a `subscribe` call uses the 2-arity `(rf/subscribe <frame-id>
  [query])` form — its first arg is a keyword/symbol frame-id, not the
  query vector."
  [form]
  (let [first-arg (second form)]
    (and (= 3 (count form))
         (or (keyword? first-arg) (symbol? first-arg)))))

(defn- call-carries-explicit-frame?
  "Does this frame-scoped call name its frame inline (so it does not need a
  surrounding `with-frame` / `with-new-frame` scope)?"
  [form]
  (case (name (first form))
    ("dispatch" "dispatch-sync") (dispatch-has-explicit-frame? form)
    "subscribe"                  (subscribe-has-explicit-frame? form)
    false))

(defn- frameless-scoped-calls
  "Walk `forms` top-down. Track whether we are lexically inside a
  `with-frame` / `with-new-frame` body. Return every frame-scoped call
  that is NEITHER inside a frame scope NOR carries an explicit inline
  frame target — i.e. the calls that would throw `:rf.error/no-frame-
  context` on first eval."
  [forms]
  (let [acc (volatile! [])]
    (letfn [(walk-form [form scoped?]
              (cond
                (frame-scope-form? form)
                ;; Inside a frame scope: walk the body with scoped? = true.
                ;; (The scope head — frame-id / [sym expr] — is still walked
                ;; so a nested call there is checked under the outer scope.)
                (doseq [child (rest form)]
                  (walk-form child true))

                (frame-scoped-call? form)
                (do
                  (when (and (not scoped?)
                             (not (call-carries-explicit-frame? form)))
                    (vswap! acc conj form))
                  ;; Still descend into args (a nested subscribe in opts, etc.).
                  (doseq [child (rest form)]
                    (walk-form child scoped?)))

                (seq? form)
                (doseq [child form] (walk-form child scoped?))

                (vector? form)
                (doseq [child form] (walk-form child scoped?))

                (map? form)
                (doseq [child (concat (keys form) (vals form))]
                  (walk-form child scoped?))

                :else nil))]
      (doseq [form forms] (walk-form form false)))
    @acc))

(defn- assert-scratch-frame-context!
  "Reject any frameless frame-scoped REPL call in the emitted scratch.cljs.
  Complements the first-arg shape audit above."
  [substrate ^java.io.File root]
  (let [scratch (io/file root "dev/scratch.cljs")]
    (is (.isFile scratch)
        (str "dev/scratch.cljs emitted for " substrate))
    (let [forms     (read-cljs-forms scratch)
          frameless (frameless-scoped-calls forms)]
      (is (empty? frameless)
          (str "scratch.cljs (" substrate ") has frame-scoped REPL "
               "call(s) with no resolvable frame: "
               (pr-str (mapv #(take 2 %) frameless)) ". EP-0002 removed "
               "the :rf/default floor — such a call throws "
               ":rf.error/no-frame-context on first eval. Put it inside a "
               "(with-frame :rf/default …) / (with-new-frame [f …] …) body, "
               "or give it an explicit frame: {:frame :rf/default} opts for "
               "dispatch / dispatch-sync, or the 2-arity "
               "(subscribe <frame-id> [query]) form for subscribe.")))))

;; --- Xray layout-host contract audit ---------------------------------------
;;
;; The scaffold ships an Xray layout host in the emitted
;; `resources/public/index.html` (`<aside data-rf-xray-host>`) and sizes
;; it in `resources/public/css/app.css` (`.rf2-xray-host`). The current
;; published host contract (tools/xray/spec/011-Launch-Modes.md §Layout
;; host contract; mirrored in examples/_shared/css/structure.css and
;; examples/core/counter/index.html) is a RIGHT-side, *resizable*
;; host:
;;
;;   - DOM order: `<main id="app">` FIRST, `<aside data-rf-xray-host>`
;;     SECOND (flex flow then lays the panel to the right).
;;   - `flex-basis` reads `var(--rf-xray-inline-width, 560px)` so the
;;     host-owned resize knob + persisted drag-resize width + cascade
;;     overrides take effect. A literal `420px` ignores them.
;;   - `box-sizing: border-box` + `border-left` so the separator lives
;;     inside the documented width.
;;
;; This audit reads the *generated* HTML/CSS (not the template resource
;; directly) so it also catches a future templating step that mangles
;; the host. It fails on a stale `flex: 0 0 420px` / left-column shape,
;; which is not the right-side host contract.

(defn- assert-xray-host-contract!
  [substrate ^java.io.File root]
  ;; root here is the *emitted project* root (named `proj` at call sites
  ;; below). We shadow the framework-root `root` deliberately — this
  ;; helper only inspects the generated app.
  (let [index-html (io/file root "resources/public/index.html")
        app-css    (io/file root "resources/public/css/app.css")]
    (is (.isFile index-html)
        (str "resources/public/index.html emitted for " substrate))
    (is (.isFile app-css)
        (str "resources/public/css/app.css emitted for " substrate))
    (when (.isFile index-html)
      (let [html      (slurp index-html)
            ;; Position of the layout host vs the app mount. The host
            ;; MUST follow `<main id="app">` (right-side); a host that
            ;; precedes it is the stale left-side shape.
            host-idx  (.indexOf html "data-rf-xray-host")
            app-idx   (.indexOf html "id=\"app\"")]
        (is (not (neg? host-idx))
            (str "index.html (" substrate
                 ") declares an Xray layout host (data-rf-xray-host)"))
        (is (not (neg? app-idx))
            (str "index.html (" substrate ") declares the app mount (id=\"app\")"))
        (when (and (not (neg? host-idx)) (not (neg? app-idx)))
          (is (< app-idx host-idx)
              (str "index.html (" substrate
                   ") must order `<main id=\"app\">` BEFORE "
                   "`<aside data-rf-xray-host>` — current right-side "
                   "host contract (tools/xray/spec/011-Launch-Modes.md "
                   "§Layout host contract). A host that precedes the app "
                   "mount is the stale left-side layout.")))))
    (when (.isFile app-css)
      (let [css (slurp app-css)]
        ;; The stale shape: a literal flex-basis that can't resize.
        (is (not (re-find #"flex:\s*0\s+0\s+420px" css))
            (str "app.css (" substrate
                 ") still ships the stale literal `flex: 0 0 420px` "
                 "Xray host — it can't consume the persisted resize "
                 "width. Use `flex: 0 0 var(--rf-xray-inline-width, "
                 "560px)`."))
        ;; The current contract: flex-basis reads the resize knob.
        (is (re-find #"flex:\s*0\s+0\s+var\(--rf-xray-inline-width" css)
            (str "app.css (" substrate
                 ") Xray host must read `--rf-xray-inline-width` for its "
                 "flex-basis (host-owned resize knob; "
                 "tools/xray/spec/011-Launch-Modes.md §Resizing the "
                 "inline host)."))
        ;; The 560px default fallback matches Xray's recommended default.
        (is (re-find #"var\(--rf-xray-inline-width,\s*560px\)" css)
            (str "app.css (" substrate
                 ") Xray host flex-basis fallback must be 560px (Xray's "
                 "recommended default, rf2-9ovfb)."))
        ;; box-sizing + border-left so the separator lives inside the
        ;; documented width.
        (is (re-find #"(?s)\.rf2-xray-host\s*\{[^}]*box-sizing:\s*border-box" css)
            (str "app.css (" substrate
                 ") .rf2-xray-host must set `box-sizing: border-box` so "
                 "the 1px border-left stays inside the documented width."))
        (is (re-find #"(?s)\.rf2-xray-host\s*\{[^}]*border-left:" css)
            (str "app.css (" substrate
                 ") .rf2-xray-host must declare a `border-left` separator "
                 "(right-side host contract)."))
        ;; The :empty collapse must survive — release builds drop the
        ;; preload and the host must not ship a blank gutter.
        (is (re-find #"\.rf2-xray-host:empty\s*\{[^}]*display:\s*none" css)
            (str "app.css (" substrate
                 ") must keep the `.rf2-xray-host:empty { display: none }` "
                 "collapse so release builds (no Xray preload) don't ship "
                 "a blank gutter."))))))

;; --- Stale Xray-layout-wording scan ----------------------------------------
;;
;; `assert-xray-host-contract!` above pins the RUNTIME shape (DOM order +
;; resize CSS var) for index.html / app.css only. The prose in the other
;; emitted text files (deps.edn / shadow-cljs.edn comments) sits outside
;; that net: a "left layout column" / "left column" description there would
;; contradict the right-side layout-host contract. This guard scans EVERY
;; emitted text file for the stale left-side wording so a prose regression
;; can't ship green while the narrow runtime tests stay happy.

(def ^:private stale-xray-wording-re
  #"(?i)left[- ]?(layout )?column")

(defn- assert-no-stale-xray-wording!
  "Walk the whole emitted tree and fail on any text file that still
   describes the Xray host as a left column. The host is a RIGHT-side
   layout host (README/CSS contract); the deps/shadow comments must say
   so too. Only files that actually mention Xray/`data-rf-xray-host` are
   checked, so the scan can't false-fire on unrelated app prose that
   happens to use the phrase 'left column'."
  [substrate ^java.io.File root]
  (doseq [^java.io.File f (file-seq root)
          :when (.isFile f)
          ;; Restrict to the scaffold's text artefacts; defensive against
          ;; anything binary a stray build step might leave behind.
          :when (re-find #"(?i)\.(edn|cljs?|clj|md|html?|css|json|ya?ml|txt|js|cjs)$"
                         (.getName f))]
    (let [text (slurp f)]
      (when (re-find #"(?i)data-rf-xray-host|xray" text)
        (is (not (re-find stale-xray-wording-re text))
            (str "emitted file " (.getName f) " (" substrate
                 ") still describes the Xray host with stale left-side "
                 "wording (\"left layout column\" / \"left column\"). The "
                 "host is a RIGHT-side layout host — match the README/CSS "
                 "contract (tools/xray/spec/011-Launch-Modes.md §Layout "
                 "host contract)."))))))

(defn- run-for-substrate!
  [substrate]
  (let [tmp  (tmp-dir (str "rf2-emission-" (name substrate) "-"))
        root (repo-root)]
    (try
      (let [proj (run-template! tmp "acme/my-app" substrate)]
        (assert-events-test-shape! substrate proj)
        (assert-events-test-strict-mint-policy! substrate proj)
        (assert-scratch-with-frame-shape! substrate proj)
        (assert-scratch-frame-context! substrate proj)
        (assert-xray-host-contract! substrate proj)
        (assert-no-stale-xray-wording! substrate proj)
        (doseq [rel ["test/acme/my_app/events_test.cljs"
                     "src/acme/my_app/events.cljs"
                     "src/acme/my_app/schema.cljs"
                     "src/acme/my_app/subs.cljs"
                     "src/acme/my_app/views.cljs"
                     "dev/scratch.cljs"]]
          (audit-framework-surface! substrate (io/file proj rel) root)))
      (finally
        (delete-recursively tmp)))))

;; --- Normative spec-prose Xray-host drift guard ----------------------------
;;
;; `assert-xray-host-contract!` pins the RUNTIME shape of the EMITTED
;; index.html / app.css, and `assert-no-stale-xray-wording!` scans the
;; EMITTED text tree. But the template's OWN normative capability doc —
;; `tools/template/spec/002-Generated-Shape.md` §Xray devtools — is not an
;; emitted artefact, so it sits outside both nets. A stale contract there —
;; a `[data-rf-xray-host]` `<aside>` "inside a .rf2-app-shell with
;; .rf2-xray-host fixed at 420px and #app taking the remaining width" —
;; would leave the user-facing scaffold spec describing a layout the
;; scaffold does not emit, while the emitted-file tests stay green. This
;; guard scans the spec prose directly so the stale 420px / left-side
;; wording cannot be reintroduced into the normative doc.
;;
;; The doc must match the published right-side host contract
;; (tools/xray/spec/011-Launch-Modes.md §Layout host contract), which the
;; emitted CSS/HTML already implement and the runtime audit above pins.

(defn- xray-devtools-section
  "The §Xray devtools section of 002-Generated-Shape.md (from its `## Xray`
  heading to the next `## ` heading). Scoping the greps to this section
  keeps an honest mention of `420px` elsewhere in the doc (none today)
  from tripping the guard, and makes the failure message point at the
  right place."
  [^String doc]
  (let [start (.indexOf doc "## Xray devtools")]
    (when-not (neg? start)
      (let [end (let [i (.indexOf doc "\n## " (inc start))]
                  (if (neg? i) (count doc) i))]
        (subs doc start end)))))

(deftest spec-002-xray-host-prose-current-test
  ;; The normative scaffold-contract doc must describe the CURRENT
  ;; right-side Xray layout host, not a stale fixed-420px / left-column
  ;; shape.
  (testing "002-Generated-Shape.md §Xray devtools matches the emitted right-side host contract"
    (let [doc-file (io/file (repo-root)
                            "tools/template/spec/002-Generated-Shape.md")]
      (is (.isFile doc-file)
          "tools/template/spec/002-Generated-Shape.md exists")
      (when (.isFile doc-file)
        (let [doc     (slurp doc-file)
              section (xray-devtools-section doc)]
          (is (some? section)
              "002-Generated-Shape.md has a `## Xray devtools` section to audit")
          (when section
            ;; --- the stale contract must be gone -------------------------
            (is (not (re-find #"420px" section))
                (str "002-Generated-Shape.md §Xray devtools still claims the "
                     "Xray host is fixed at `420px`. The emitted app.css uses "
                     "`flex: 0 0 var(--rf-xray-inline-width, 560px)` (pinned by "
                     "assert-xray-host-contract! above) — a literal 420px width "
                     "ignores the host-owned resize knob. Remove the stale "
                     "420px claim (rf2-7s8ou3 / tools/xray/spec/011-Launch-Modes.md)."))
            (is (not (re-find #"(?i)left[- ]?(layout )?column" section))
                (str "002-Generated-Shape.md §Xray devtools still describes the "
                     "Xray host as a left column. The emitted index.html orders "
                     "`<main id=\"app\">` BEFORE `<aside data-rf-xray-host>`, so "
                     "the host is a RIGHT-side layout host. Match the emitted "
                     "layout + the Xray spec (rf2-7s8ou3)."))
            ;; --- the current contract must be stated --------------------
            ;; Positive assertions so the negatives can't pass vacuously by
            ;; deleting/emptying the section.
            (is (re-find #"var\(--rf-xray-inline-width,\s*560px\)" section)
                (str "002-Generated-Shape.md §Xray devtools must document the "
                     "current flex-basis `var(--rf-xray-inline-width, 560px)` "
                     "(host-owned resize knob, 560px default — matches the "
                     "emitted app.css)."))
            (is (re-find #"(?i)min-width.{0,4}320px" section)
                "002-Generated-Shape.md §Xray devtools must document the `min-width: 320px` floor")
            (is (re-find #"(?i)box-sizing.{0,4}border-box" section)
                "002-Generated-Shape.md §Xray devtools must document `box-sizing: border-box`")
            (is (re-find #"(?i)border-left" section)
                "002-Generated-Shape.md §Xray devtools must document the `border-left` separator")
            (is (re-find #"(?i):empty" section)
                "002-Generated-Shape.md §Xray devtools must document the `:empty` collapse")
            (is (re-find #"(?i)right" section)
                (str "002-Generated-Shape.md §Xray devtools must describe the "
                     "host as a RIGHT-side layout host (DOM order `<main "
                     "id=\"app\">` first, `<aside data-rf-xray-host>` second)."))))))))

;; --- Tests -----------------------------------------------------------------

(deftest reagent-emission-static-parse-test
  (testing "Reagent-substrate emission has well-formed ns requires and no surface drift"
    (run-for-substrate! :reagent)))

(deftest uix-emission-static-parse-test
  (testing "UIx-substrate emission has well-formed ns requires and no surface drift"
    (run-for-substrate! :uix)))

(deftest helix-emission-static-parse-test
  (testing "Helix-substrate emission has well-formed ns requires and no surface drift"
    (run-for-substrate! :helix)))

;; --- :include-story? -----------------------------------------------------
;;
;; The with-stories core variant and the emitted stories.cljs both
;; pull on `re-frame.story` — the same drift hazard as the rest of the
;; scaffold (someone renames a public var, the template ships stale).
;; The audit reuses the same surface-existence check used for the
;; default Reagent path; framework-ns-file above already knows where
;; tools/story/ lives.

(deftest reagent-with-stories-emission-static-parse-test
  (testing ":include-story? true on Reagent emits a with-stories core
            and stories.cljs that reference only defined re-frame.* +
            re-frame.story symbols"
    (let [tmp  (tmp-dir "rf2-emission-story-")
          root (repo-root)]
      (try
        (let [proj (run-template! tmp "acme/my-app" :reagent true)]
          (doseq [rel ["src/acme/my_app/core.cljs"
                       "src/acme/my_app/stories.cljs"]]
            (audit-framework-surface! :reagent (io/file proj rel) root)))
        (finally
          (delete-recursively tmp))))))

;; --- :css :tailwind --------------------------------------------------------
;;
;; The Tailwind overlay overwrites the plain-CSS `app.css` + `index.html`
;; with the `_css_tailwind/` variants (rf2-nxqcov). The Xray-host layout
;; contract MUST survive that swap: the emitted index.html/app.css still
;; have to satisfy `assert-xray-host-contract!` (right-side host DOM order,
;; resizable flex-basis, :empty collapse) and `assert-no-stale-xray-wording!`
;; (no stale left-column prose). Reusing those audits on the tailwind tree
;; keeps the Tailwind variant honest against the same runtime shape the
;; plain path is pinned to, and additionally pins the Tailwind-specific
;; markers (the @import entry + the dev CDN script).

(deftest css-tailwind-emission-static-parse-test
  (testing ":css :tailwind swaps in the Tailwind v4 app.css + index.html
            while preserving the Xray-host layout contract"
    (let [tmp (tmp-dir "rf2-emission-tailwind-")]
      (try
        (let [proj    (run-template-opts! tmp "acme/my-app"
                                          {:css :tailwind})
              app-css (slurp (io/file proj "resources/public/css/app.css"))
              index   (slurp (io/file proj "resources/public/index.html"))]
          ;; The layout-host contract survives the overlay.
          (assert-xray-host-contract! :tailwind proj)
          (assert-no-stale-xray-wording! :tailwind proj)
          ;; Tailwind-specific markers.
          (is (re-find #"@import\s+\"tailwindcss\"" app-css)
              "tailwind app.css imports tailwindcss (v4 CSS-first entry)")
          (is (re-find #"@tailwindcss/browser@4" index)
              "tailwind index.html loads the @tailwindcss/browser@4 dev CDN"))
        (finally
          (delete-recursively tmp))))))
