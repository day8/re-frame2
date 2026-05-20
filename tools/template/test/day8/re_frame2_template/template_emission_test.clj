(ns day8.re-frame2-template.template-emission-test
  "Static-parse tests for the template's emitted cljs scaffold (rf2-owbpr,
   rf2-c2770 port to deps-new).

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

   The deeper-fidelity option (option (a) in the bead — run shadow-cljs
   on the generated app) is left for after alpha publish; until then,
   static parse catches the most likely regression."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [clojure.tools.reader :as tr]
            [clojure.tools.reader.reader-types :as rt]
            [org.corfield.new :as deps-new]))

;; --- Helpers (mirror template_test.clj — kept local so the two test
;; ns-es don't accidentally share state via top-level defs) -----------------

(defn- tmp-dir [prefix]
  (let [f (java.nio.file.Files/createTempDirectory
            prefix
            (into-array java.nio.file.attribute.FileAttribute []))]
    (.toAbsolutePath f)))

(defn- delete-recursively [^java.nio.file.Path path]
  (when (java.nio.file.Files/exists path (into-array java.nio.file.LinkOption []))
    (with-open [stream (java.nio.file.Files/walk
                         path
                         (into-array java.nio.file.FileVisitOption []))]
      (->> stream
           .iterator
           iterator-seq
           reverse
           (run! #(try
                    (java.nio.file.Files/deleteIfExists ^java.nio.file.Path %)
                    (catch java.io.IOException _ nil)))))))

(defn- template-resource-dir []
  (let [cwd (io/file (System/getProperty "user.dir"))]
    (loop [d cwd]
      (cond
        (nil? d)
        (throw (ex-info "Couldn't locate tools/template/resources above cwd"
                        {:cwd cwd}))

        (.isDirectory (io/file d "tools/template/resources"))
        (.getCanonicalPath (io/file d "tools/template/resources"))

        (.isDirectory (io/file d "resources/day8/re_frame2_template"))
        (.getCanonicalPath (io/file d "resources"))

        :else
        (recur (.getParentFile d))))))

(defn- run-template!
  ([tmp project-name substrate]
   (run-template! tmp project-name substrate nil))
  ([tmp project-name substrate include-story?]
   (let [dir-str   (.toString ^java.nio.file.Path tmp)
         proj-name (-> project-name name (string/replace #"^.*?/" ""))
         proj-dir  (io/file dir-str proj-name)
         opts      (cond-> {:template   'day8/re-frame2-template
                            :name       (symbol project-name)
                            :target-dir (.getCanonicalPath proj-dir)
                            :src-dirs   [(template-resource-dir)]
                            :overwrite  :delete}
                     substrate              (assoc :substrate substrate)
                     (some? include-story?) (assoc :include-story? include-story?))]
     (deps-new/create opts)
     proj-dir)))

;; --- Static-parse machinery ----------------------------------------------

(defn- repo-root
  "Absolute path of the repo root, derived from the JVM's `user.dir`. The
  template test JVM is launched from `tools/template/`, so the framework
  source tree we read for ground-truth lives at `../../implementation/`."
  []
  (let [cwd (io/file (System/getProperty "user.dir"))]
    ;; Walk up until we find a sibling `implementation/core/src/re_frame/`
    ;; directory. This makes the test robust to either:
    ;;   a) test JVM launched from tools/template/ (the clein default)
    ;;   b) test JVM launched from the repo root (manual `clojure
    ;;      -X:test` invocation)
    (loop [d cwd]
      (cond
        (nil? d)
        (throw (ex-info "Couldn't locate repo root (no implementation/core/src/re_frame above cwd)"
                        {:cwd cwd}))

        (.isDirectory (io/file d "implementation/core/src/re_frame"))
        d

        :else
        (recur (.getParentFile d))))))

(defn- read-cljs-forms
  "Read every top-level form from a .cljs file. Each form is returned in
  source order. Uses `clojure.tools.reader` (not `clojure.edn`) because
  the emitted views.cljs contains `#(...)` function literals and the
  framework cljc files use reader conditionals. We bind `*read-eval*`
  off and treat unknown tags as identity so any future `#js` etc. in
  the scaffold doesn't trip us."
  [^java.io.File f]
  (let [eof    (Object.)
        pbr    (rt/source-logging-push-back-reader (slurp f))
        ;; tools.reader respects *data-readers*; supply a permissive
        ;; default-data-reader so unknown tags don't throw.
        opts   {:eof eof :read-cond :allow :features #{:cljs}
                :default (fn [_tag value] value)}]
    (binding [tr/*read-eval* false
              tr/*default-data-reader-fn* (fn [_tag value] value)]
      (loop [acc []]
        (let [form (tr/read opts pbr)]
          (if (identical? form eof)
            acc
            (recur (conj acc form))))))))

(defn- parse-ns-requires
  "Given a `(ns ns-sym ...)` form, return a map
     {alias-symbol  required-ns-symbol
      ...
      ::required #{ns-sym ...}}
  for every `:require` / `:refer-clojure` clause. Aliases come from
  `[ns :as alias]`; bare requires (`[ns]` / `ns`) contribute to
  `::required` only. Side-effecting requires (no `:as`) still count
  as 'required' for the purpose of the load-order assertion below."
  [ns-form]
  (let [clauses    (drop 2 ns-form) ;; skip `ns` + ns-sym
        require?   #(and (sequential? %) (= :require (first %)))
        req-clause (first (filter require? clauses))
        body       (when req-clause (rest req-clause))]
    (reduce
      (fn [acc spec]
        (cond
          (symbol? spec)
          (update acc ::required (fnil conj #{}) spec)

          (and (vector? spec) (symbol? (first spec)))
          (let [nsym  (first spec)
                rest- (rest spec)
                pairs (partition 2 rest-)
                as-kv (some (fn [[k v]] (when (= k :as) v)) pairs)]
            (cond-> (update acc ::required (fnil conj #{}) nsym)
              as-kv (assoc as-kv nsym)))

          :else acc))
      {::required #{}}
      body)))

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
  ;; Compiled once at ns-load — the previous shape rebuilt the
  ;; java.util.regex.Pattern per call (×N framework files × N
  ;; references × 3 substrates = thousands of compiles per suite run).
  (let [meta-clause "(?:\\^(?:\\w[\\w/.:?<>=*+!\\-]*|\\{[^}]*\\})\\s+)*"
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
    ;;        run. Without this, dispatch/get-frame-db would hit empty.
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

;; --- Generic ns-surface drift check (events / subs / views / events_test)
;; ----------------------------------------------------------------------------

(defn- audit-framework-surface!
  "For `file`, parse it, collect every `<alias>/<sym>` reference whose
  alias resolves to a `re-frame.*` ns, and assert each `<sym>` is
  defined in that framework ns's source file. This is the surface-drift
  smoke test: catches a rename / cut / relocation of a public var that
  the template scaffold still references."
  [substrate ^java.io.File file root]
  (when (.isFile file)
    (let [forms      (read-cljs-forms file)
          ns-form    (first forms)
          requires   (parse-ns-requires ns-form)
          body-forms (rest forms)
          ;; A vector of [referenced-symbol resolved-ns] pairs for every
          ;; qualified symbol whose alias resolves to a re-frame.* ns.
          refs       (->> (collect-qualified-symbols body-forms)
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
                          keys)]
      (doseq [[target-ns sym] refs]
        (if-let [framework-file (framework-ns-file root target-ns)]
          (let [defined (defined-symbols framework-file)]
            (is (contains? defined sym)
                (str (.getName file) " (" substrate ") references "
                     target-ns "/" sym
                     " but it is NOT defined in "
                     (.getPath framework-file)
                     " — likely a rename/cut. Defined symbols there: "
                     (sort defined))))
          ;; If the framework file isn't found, that's its own drift
          ;; signal — the template requires a namespace the framework
          ;; doesn't ship.
          (is false
              (str (.getName file) " (" substrate ") requires " target-ns
                   " but no source file found under implementation/core/src/")))))))

;; --- scratch.cljs with-frame shape audit (rf2-ah0gi) -----------------------
;;
;; The emitted dev/scratch.cljs is the user's REPL on-ramp. Any
;; `(rf/with-frame …)` call in the (comment …) block MUST use Shape 1
;; (a keyword) or Shape 2 (a 2-elem `[sym expr]` vector) per Spec 002
;; §with-frame and the macro definition at
;; `implementation/core/src/re_frame/core_reg_view_macro.cljc`. A map
;; first-arg (or any other literal) falls through to Shape 1 and binds
;; `*current-frame*` to a non-frame value — runtime breaks far from
;; the call site. The audit walks every `with-frame` form in scratch
;; and asserts the first-arg shape.

(defn- with-frame-call?
  "True when `form` is `(rf-or-alias/with-frame …)` — covers both
  alias-qualified (`rf/with-frame`) and bare (`with-frame`) usage."
  [form]
  (and (seq? form)
       (symbol? (first form))
       (= "with-frame" (name (first form)))))

(defn- valid-with-frame-first-arg?
  "Shape 1 = keyword; Shape 2 = 2-elem [sym expr] vector. Anything
  else is the bug rf2-ah0gi found."
  [arg]
  (or (keyword? arg)
      (and (vector? arg)
           (= 2 (count arg))
           (symbol? (first arg)))))

(defn- collect-with-frame-calls
  "Walk `forms` and return every `(with-frame …)` call form, including
  those nested inside `(comment …)` blocks (which is where the emitted
  scratch ns puts its examples)."
  [forms]
  (let [acc (volatile! [])]
    (walk/postwalk
      (fn [x]
        (when (with-frame-call? x)
          (vswap! acc conj x))
        x)
      forms)
    @acc))

(defn- assert-scratch-with-frame-shape!
  [substrate ^java.io.File root]
  (let [scratch (io/file root "dev/scratch.cljs")]
    (is (.isFile scratch)
        (str "dev/scratch.cljs emitted for " substrate))
    (let [forms (read-cljs-forms scratch)
          calls (collect-with-frame-calls forms)]
      (is (seq calls)
          (str "scratch.cljs (" substrate
               ") contains at least one (with-frame …) example — "
               "the REPL on-ramp should demonstrate the shape"))
      (doseq [call calls]
        (let [first-arg (second call)]
          (is (valid-with-frame-first-arg? first-arg)
              (str "scratch.cljs (" substrate ") (with-frame "
                   (pr-str first-arg) " …) — first arg must be a "
                   "keyword (Shape 1) or a 2-elem [sym expr] vector "
                   "(Shape 2). Per Spec 002 §with-frame, anything "
                   "else binds *current-frame* to a non-frame "
                   "value and breaks downstream dispatch/subscribe.")))))))

(defn- run-for-substrate!
  [substrate]
  (let [tmp  (tmp-dir (str "rf2-emission-" (name substrate) "-"))
        root (repo-root)]
    (try
      (let [proj (run-template! tmp "acme/my-app" substrate)]
        (assert-events-test-shape! substrate proj)
        (assert-scratch-with-frame-shape! substrate proj)
        (doseq [rel ["test/acme/my_app/events_test.cljs"
                     "src/acme/my_app/events.cljs"
                     "src/acme/my_app/subs.cljs"
                     "src/acme/my_app/views.cljs"
                     "dev/scratch.cljs"]]
          (audit-framework-surface! substrate (io/file proj rel) root)))
      (finally
        (delete-recursively tmp)))))

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

;; --- :include-story? (rf2-t009p) -----------------------------------------
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
