(ns day8.re-frame2-template.template-emission-test
  "Static-parse tests for the template's emitted cljs scaffold.

   The sibling `template_test.clj` verifies the generated file *shape*.
   It does NOT verify that the emitted cljs files would actually compile
   against the re-frame2 framework: a rename of a public var ships green
   from shape-only checks because the template's resource tree is a
   string — not a compile target — at template-build time.

   This test closes the gap *cheaply* — no shadow-cljs, no Node, no
   network. For each substrate's generated app it:

     1. Parses the emitted `events_test.cljs` ns form and asserts the
        expected requires are present.
     2. Walks every emitted `.cljs` file for each `<alias>/<symbol>`
        reference, resolves the alias against the ns form's requires, and
        asserts the underlying symbol is actually defined in the framework
        source under `implementation/`. If `re-frame.core/dispatch-sync`
        were renamed, the emitted scaffold would ship stale and this fires.
     3. Pins the hot-reload lifecycle facts of the emitted entry namespace
        (rf2-r0kk7): one `^:dev/after-load` hook that renders, `init`
        delegating to it, and exactly one retained React root.

   The behavioural companion (`emitted_test_run_test.clj`) compiles and
   runs the same scaffold; this one catches the most likely regression
   in seconds."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [clojure.tools.reader :as tr]
            [clojure.tools.reader.reader-types :as rt]
            [day8.re-frame2-template.test-support
             :refer [tmp-dir delete-recursively run-template! repo-root]]))

;; --- Static-parse machinery ----------------------------------------------

(defn- read-cljs-forms
  "Read every top-level form from a ClojureScript source file, resolving
  reader conditionals under `#{:cljs}`. Uses `clojure.tools.reader` (not
  `clojure.edn`) because the emitted views.cljs contains `#(...)` function
  literals and `#js` tags. `*read-eval*` is off and unknown tags read as
  identity."
  [^java.io.File f]
  (let [eof    (Object.)
        reader (rt/source-logging-push-back-reader (slurp f))
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
  `[ns :as alias]`; `:refer`-ed symbols contribute to `::referred`. Bare
  requires (`[ns]` / `ns`) contribute to `::required` only."
  [ns-form]
  (let [clauses    (drop 2 ns-form)
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
                opt-pairs (partition 2 (rest spec))
                alias-sym (some (fn [[k v]] (when (= k :as) v)) opt-pairs)
                referred  (some (fn [[k v]] (when (= k :refer) v)) opt-pairs)]
            (cond-> (update acc ::required (fnil conj #{}) ns-sym)
              alias-sym (assoc alias-sym ns-sym)
              (sequential? referred)
              (update ::referred
                      (fnil into {})
                      (->> referred
                           (filter symbol?)
                           (map (fn [s] [s ns-sym]))))))

          :else acc))
      {::required #{} ::referred {}}
      req-bodies)))

(defn- collect-symbols
  "Walk `forms` and collect every symbol satisfying `pred`."
  [pred forms]
  (let [acc (volatile! #{})]
    (walk/postwalk
      (fn [x]
        (when (and (symbol? x) (pred x))
          (vswap! acc conj x))
        x)
      forms)
    @acc))

(defn- framework-ns-file
  "Map a `re-frame.*` namespace symbol to its source file under
  `implementation/`: core ships everything except the per-substrate
  adapters, which live in their own coordinates. Returns nil for a
  namespace this audit does not know (the app's own nses, `uix.*`,
  `reagent.*`)."
  [root ns-sym]
  (let [name- (name ns-sym)]
    (when (string/starts-with? name- "re-frame.")
      (let [rel     (-> name-
                        (subs (count "re-frame."))
                        (string/replace "-" "_")
                        (string/replace "." "/"))
            adapter (when (string/starts-with? name- "re-frame.adapter.")
                      (let [leaf (subs name- (count "re-frame.adapter."))]
                        (when (#{"uix" "reagent" "reagent-slim"} leaf)
                          leaf)))
            candidates (cond-> [(io/file root "implementation/core/src/re_frame" (str rel ".cljc"))
                                (io/file root "implementation/core/src/re_frame" (str rel ".cljs"))]
                         adapter
                         (conj (io/file root "implementation/adapters" adapter
                                        "src/re_frame/adapter" (str adapter ".cljs"))))]
        (some (fn [f] (when (.isFile f) f)) candidates)))))

(def ^:private ^java.util.regex.Pattern def-pattern
  ;; Reader conditionals are not stripped — raw source text is scanned, so
  ;; a symbol defined in either branch counts as defined, which is what a
  ;; surface-existence check wants. The regex tolerates leading metadata
  ;; clauses (`^:private`, `^{...}` with one level of brace nesting) and is
  ;; POSSESSIVE throughout so the nested alternation cannot backtrack into
  ;; a StackOverflowError on `re-frame.core`'s ~3K lines. `defui` is in the
  ;; list because the UIx adapter defines `frame-root` with it.
  (let [meta-clause "(?:\\^(?:\\w[\\w/.:?<>=*+!\\-]*|\\{(?:[^{}]++|\\{[^{}]*+\\})*+\\})\\s+)*+"
        sym-char    "[a-zA-Z*+!?<>=$%_\\-][\\w*+!?<>=$%\\-]*"]
    (re-pattern
      (str "\\(def(?:n-?|macro|multi|once|protocol|record|type|ui)?\\s+"
           meta-clause
           "(" sym-char ")"))))

(def ^:private defined-symbols-cache
  ;; Keyed by canonical path; the framework tree is immutable for the life
  ;; of a test JVM, so no mtime is needed.
  (atom {}))

(defn- defined-symbols
  "The set of symbols a framework source file introduces with a top-level
  `def*` form. Memoised per file — the audit visits the same file once per
  (substrate × referenced symbol)."
  [^java.io.File f]
  (let [k (.getAbsolutePath f)]
    (or (get @defined-symbols-cache k)
        (let [v (into #{}
                      (map (fn [[_ sym]] (symbol sym)))
                      (re-seq def-pattern (slurp f)))]
          (swap! defined-symbols-cache assoc k v)
          v))))

;; --- The events_test.cljs assertions ------------------------------------

(def ^:private expected-events-test-requires
  "ns symbols the emitted events_test.cljs MUST require. Drift here means
  the test scaffold has fallen out of sync with the registrar / fixture /
  substrate API."
  '#{cljs.test
     re-frame.core
     re-frame.substrate.plain-atom
     re-frame.test-support})

(defn- assert-events-test-shape!
  [substrate ^java.io.File root]
  (let [test-file (io/file root "test/acme/my_app/events_test.cljs")
        _         (is (.isFile test-file)
                      (str "events_test.cljs emitted for " substrate))
        forms     (read-cljs-forms test-file)
        ns-form   (first forms)
        _         (is (and (sequential? ns-form) (= 'ns (first ns-form)))
                      "first form is the ns form")
        requires  (parse-ns-requires ns-form)
        required  (::required requires)
        text      (slurp test-file)]
    (doseq [needed expected-events-test-requires]
      (is (contains? required needed)
          (str "events_test.cljs (" substrate ") requires " needed
               " — current requires: " (sort required))))
    ;; The user's events / subs nses are required so their registrations
    ;; land before the deftests run.
    (is (contains? required 'acme.my-app.events)
        "events_test.cljs requires the user's events ns")
    (is (contains? required 'acme.my-app.subs)
        "events_test.cljs requires the user's subs ns")
    ;; Every alias used in the body is declared.
    (doseq [sym (collect-symbols #(some? (namespace %)) (rest forms))]
      (let [alias-sym (symbol (namespace sym))]
        (when-not (#{"js" "cljs.core" "clojure.core"} (str alias-sym))
          (is (contains? requires alias-sym)
              (str "qualified symbol " sym " in events_test.cljs uses alias "
                   alias-sym " but no matching :as is declared")))))
    ;; The fixture runs under a STRICT cofx mint policy, so a generated app
    ;; that adds a generator-backed coeffect cannot ship a green test that
    ;; forgot to supply the fact.
    (is (or (re-find #":preset\s+:test" text)
            (re-find #":rf\.cofx/mint-policy\s+:strict" text))
        (str "events_test.cljs (" substrate ") must run under a strict cofx "
             "mint policy — `{:preset :test}` or `:rf.cofx/mint-policy :strict`"))
    (doseq [[re what] [[#":rf\.world/inputs" ":rf.world/inputs (retired)"]
                       [#"inject-cofx"        "inject-cofx (removed)"]]]
      (is (not (re-find re text))
          (str "events_test.cljs (" substrate ") must not use legacy "
               "coeffect vocabulary: " what)))))

;; --- Framework-surface drift audit ----------------------------------------

(defn- audit-framework-symbol!
  [substrate ^java.io.File file root target-ns sym label]
  (if-let [framework-file (framework-ns-file root target-ns)]
    (is (contains? (defined-symbols framework-file) sym)
        (str (.getName file) " (" substrate ") " label " "
             target-ns "/" sym " but it is NOT defined in "
             (.getPath framework-file) " — likely a rename/cut."))
    (is false
        (str (.getName file) " (" substrate ") " label " " target-ns
             " but no source file was found under implementation/"))))

(defn- audit-framework-surface!
  "Parse `file`, collect every `re-frame.*` reference — `<alias>/<sym>`
  through a declared alias, or a bare `:refer`-ed symbol the body uses —
  and assert each is defined in its framework ns's source file."
  [substrate ^java.io.File file root]
  (when (.isFile file)
    (let [forms      (read-cljs-forms file)
          requires   (parse-ns-requires (first forms))
          body-forms (rest forms)
          re-frame?  #(string/starts-with? (name %) "re-frame.")
          qual-refs  (->> (collect-symbols #(some? (namespace %)) body-forms)
                          (keep (fn [qsym]
                                  (let [target-ns (get requires (symbol (namespace qsym)))]
                                    (when (and target-ns (re-frame? target-ns))
                                      [target-ns (symbol (name qsym))]))))
                          set)
          bare-used  (collect-symbols #(nil? (namespace %)) body-forms)
          bare-refs  (->> (::referred requires)
                          (keep (fn [[sym target-ns]]
                                  (when (and (contains? bare-used sym) (re-frame? target-ns))
                                    [target-ns sym]))))]
      (doseq [[target-ns sym] qual-refs]
        (audit-framework-symbol! substrate file root target-ns sym "references"))
      (doseq [[target-ns sym] bare-refs]
        (audit-framework-symbol! substrate file root target-ns sym "refers (bare)")))))

(def ^:private emitted-cljs-files
  ["src/acme/my_app/core.cljs"
   "src/acme/my_app/events.cljs"
   "src/acme/my_app/subs.cljs"
   "src/acme/my_app/views.cljs"
   "test/acme/my_app/events_test.cljs"])

(defn- run-for-substrate!
  [substrate]
  (let [tmp  (tmp-dir (str "rf2-emission-" (name substrate) "-"))
        root (repo-root)]
    (try
      (let [proj (run-template! tmp "acme/my-app" substrate)]
        (assert-events-test-shape! substrate proj)
        (doseq [rel emitted-cljs-files]
          (is (.isFile (io/file proj rel)) (str rel " emitted for " substrate))
          (audit-framework-surface! substrate (io/file proj rel) root)))
      (finally
        (delete-recursively tmp)))))

(deftest reagent-emission-static-parse-test
  (testing "the Reagent emission has well-formed ns requires and no surface drift"
    (run-for-substrate! :reagent)))

(deftest uix-emission-static-parse-test
  (testing "the UIx emission has well-formed ns requires and no surface drift"
    (run-for-substrate! :uix)))

;; --- The hot-reload lifecycle (rf2-r0kk7) ----------------------------------
;;
;; MEASURED on shadow-cljs 3.4.10: a `:browser` build whose only entry point
;; is a module `:init-fn` does NOT re-render after a hot reload. shadow loads
;; the new code, logs "reloading code but no :after-load hooks are
;; configured!", and `#app` goes on painting the OLD view. The `:init-fn` is
;; called once, at bundle load. So every emitted entry namespace carries a
;; `^:dev/after-load` hook that renders, `init` delegates to it, and the React
;; root is created exactly once and retained across reloads. These are the
;; facts pinned here, on the emitted `core.cljs` itself. On Reagent the
;; retained root is the adapter-owned client root (rf2-k5r9t): one
;; `rf.adapter.reagent/client-root` allocation, rendered through with
;; `rf.adapter.reagent/render!`; UIx holds a `uix-dom` root itself.

(defn- hook-body
  "The source text of the `^:dev/after-load <hook>` form: from its metadata
  tag to the next top-level `(def`. nil when the hook is absent."
  [^String core ^String hook]
  (let [i (.indexOf core (str "^:dev/after-load " hook))]
    (when-not (neg? i)
      (let [tail (subs core i)
            j    (.indexOf tail "\n(def")]
        (if (neg? j) tail (subs tail 0 j))))))

(deftest entry-namespace-hot-reload-lifecycle-test
  (testing "every emitted core.cljs carries one ^:dev/after-load mount! that
            renders, an init that delegates to it, and exactly one retained
            React root (rf2-r0kk7)"
    (doseq [[substrate renders create-root]
            [[:reagent "rf.adapter.reagent/render!" "rf.adapter.reagent/client-root"]
             [:uix     "uix-dom/render-root"     "uix-dom/create-root"]]]
      (let [tmp (tmp-dir "rf2-emission-after-load-")]
        (try
          (let [core (slurp (io/file (run-template! tmp "acme/my-app" substrate)
                                     "src/acme/my_app/core.cljs"))
                body (hook-body core "mount!")]
            (is (= 1 (count (re-seq #"\(defn\s+\^:dev/after-load" core)))
                (str substrate ": core.cljs defines exactly one ^:dev/after-load hook"))
            (is (some? body)
                (str substrate ": the hook is `mount!`"))
            (is (and body (string/includes? body renders))
                (str substrate ": the hook body calls " renders
                     " — it is what repaints an edited view"))
            (is (re-find #"(?s)\^:export\s+init[\s\S]*?\(mount!\)" core)
                (str substrate ": init calls (mount!) so boot and reload share one render path"))
            (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote create-root)) core)))
                (str substrate ": exactly one " create-root " call — one retained root"))
            (is (string/includes? core "defonce")
                (str substrate ": the React root is held in a defonce cell"))
            (is (re-find #"frame-root\s+\{:id\s+app-frame\s+:initial-events\s+\[\[:counter/initialise\]\]\}" core)
                (str substrate ": the frame-root element seeds via :initial-events "
                     "[[:counter/initialise]] — the seed boundary a reload never replays")))
          (finally
            (delete-recursively tmp)))))))
