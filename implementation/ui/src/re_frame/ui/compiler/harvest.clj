(ns re-frame.ui.compiler.harvest
  "Deterministic pre-seed of compile-time custom-element declarations.

  A view's custom-element property lowering (property vs attribute) is decided
  at MACROEXPANSION by reading the ambient build's `elements` slice through
  `build/element-properties`. Because macros expand top-to-bottom, a view
  expanded BEFORE its tag's `(ui/custom-element …)` declaration read an EMPTY
  slice and lowered the prop to an HTML attribute; the same forms in the
  opposite order lowered it to a JS property. Two source files with no `:require`
  edge compiled in an arbitrary order and could differ the same way. That
  violated deterministic compilation (rf2-vxgfnd.141, dimension 2).

  This namespace closes that by reading each build source's TOP-LEVEL LITERAL
  `(ui/custom-element …)` forms and seeding the `elements` slice BEFORE any view
  analyzes, so classification is a pure function of the build's declarations and
  NOT of evaluation order. It is a SYNTACTIC read, never a general interpreter:
  the ruled grammar is top-level, literal and compile-resolvable (`:properties`
  MUST be a literal set), so recognizing the exact `(<ui>/custom-element <kw>
  <map>)` shape — and rejecting anything the macro would reject rather than
  seeding it — is sound. A non-literal or malformed form is simply not seeded;
  the `ui/custom-element` macro still expands and reports it with the
  declaration's own source coordinates.

  Two callers seed through it:

  * the Shadow build hook, at `:compile-prepare`, folds EVERY authoritative UI
    source's declarations (not just the recompiled subset — rf2-u53yy.1 S1) into
    the flat all-members manifest held beside the open pass
    (`build/element-manifest` / `build/set-shadow-element-manifest`) — a pure
    build-state transform, since `cljs.env/*compiler*` is not bound during hook
    execution;

  * the plain-JVM / SSR path (which has no `:compile-prepare`) harvests the
    ambient namespace's own source ONCE, lazily, the first time a view in it
    classifies a custom element (`ensure-namespace-harvested!`), seeding through
    the ambient `build/contribute-element-checked!`.

  Both routes admit through the ONE cross-source conflict law: on the Shadow path
  `build/element-manifest` re-derives the verdict over all members, and on the
  plain-JVM path `build/contribute-element-checked!` is the write barrier — an
  `rf=`-equal duplicate co-exists; a contradictory same-tag declaration is refused
  (and reported with source coordinates on the plain-JVM path, or as a build-time
  error at prepare on the Shadow path). JVM-only: it reads source text at build
  time and is never emitted into any bundle."
  (:require [clojure.java.io :as io]
            [clojure.tools.reader :as rdr]
            [clojure.tools.reader.reader-types :as rt]
            [re-frame.ui.compiler.build :as build]))

;; ---------------------------------------------------------------------------
;; ns-form alias analysis — how THIS source names re-frame.ui/custom-element
;; ---------------------------------------------------------------------------

(def ^:private custom-element-fqn 're-frame.ui/custom-element)
(def ^:private ui-lib 're-frame.ui)

(defn- lib-spec-seq
  "Every `:require` / `:require-macros` library spec in an `(ns …)` form."
  [ns-form]
  (mapcat (fn [clause]
            (when (and (seq? clause)
                       (contains? #{:require :require-macros} (first clause)))
              (rest clause)))
          (rest ns-form)))

(defn- ui-heads
  "The set of head symbols that, in THIS source, name
  `re-frame.ui/custom-element`: the fully-qualified symbol always, plus each
  `:as` alias of `re-frame.ui`, plus a bare `custom-element` when it is
  `:refer`red from `re-frame.ui`. Reading the source's OWN `(ns …)` aliases is
  what makes recognition robust across `[re-frame.ui :as ui]`,
  `re-frame.ui/custom-element`, and `:refer [custom-element]` without resolving
  vars."
  [ns-form]
  (reduce
   (fn [heads spec]
     (let [[lib & opts] (if (sequential? spec) spec [spec])
           opts (apply hash-map opts)]
       (if (= lib ui-lib)
         (cond-> heads
           (symbol? (:as opts))
           (conj (symbol (name (:as opts)) "custom-element"))
           (and (sequential? (:refer opts))
                (some #{'custom-element} (:refer opts)))
           (conj 'custom-element))
         heads)))
   #{custom-element-fqn}
   (lib-spec-seq ns-form)))

;; ---------------------------------------------------------------------------
;; Literal-form recognition — mirrors the macro's `:properties` grammar checks
;; ---------------------------------------------------------------------------

(defn- valid-tag? [tag]
  (and (keyword? tag) (nil? (namespace tag))
       (clojure.string/includes? (name tag) "-")))

(defn- valid-properties? [props]
  (and (set? props)
       (every? #(and (keyword? %) (nil? (namespace %))) props)))

(defn- declaration
  "If `form` is a recognized, LITERAL `(<ui>/custom-element <tag> <opts>)` — the
  exact shape the macro accepts — return `{:tag tag :properties #{…}}`, else
  nil. Anything the macro would reject (non-literal tag, non-map opts, unknown
  option, non-literal `:properties`) is NOT recognized here: the macro still
  expands the form and reports the error with its own coordinates, so the
  declaration never silently seeds a bad classification (rf2-vxgfnd.141)."
  [heads form]
  (when (and (seq? form) (contains? heads (first form)))
    (let [[_ tag opts] form]
      (when (and (valid-tag? tag)
                 (map? opts)
                 (empty? (remove #{:properties} (keys opts))))
        (let [props (get opts :properties #{})]
          (when (valid-properties? props)
            {:tag tag :properties props}))))))

;; ---------------------------------------------------------------------------
;; Tolerant source reading — well-formed CLJS/CLJC source, top-level forms only
;; ---------------------------------------------------------------------------

(defn- alias-map
  "The `alias -> namespace` map for `::alias/kw` resolution, read from the ns
  form's `:as` clauses. Populated before reading the body so an auto-resolved
  keyword in a form preceding a declaration does not abort the read."
  [ns-form]
  (reduce (fn [m spec]
            (let [[lib & opts] (if (sequential? spec) spec [spec])
                  {:keys [as]} (apply hash-map opts)]
              (if (and lib (symbol? as)) (assoc m as lib) m)))
          {}
          (lib-spec-seq ns-form)))

(defn- read-all-forms
  "Read every top-level form of `src` (a CLJS/CLJC/CLJ source string) tolerantly:
  reader conditionals resolve under `:cljs`, every tagged literal (`#js`,
  `#uuid`, custom) degrades to its value, `#=` eval is disabled, and `::alias/kw`
  resolves through `aliases`. A read failure ends the scan gracefully — the
  `ui/custom-element` macro remains the authority, so a source we cannot fully
  read merely falls back to evaluation-order behaviour rather than breaking the
  build. Returns the vector of forms read."
  [^String src aliases]
  (let [reader (rt/string-push-back-reader src)
        eof (Object.)
        opts {:eof eof :read-cond :allow :features #{:cljs}}]
    (binding [rdr/*default-data-reader-fn* (fn [_tag v] v)
              rdr/*read-eval* false
              rdr/*alias-map* aliases]
      (loop [forms []]
        (let [form (try (rdr/read opts reader)
                        (catch Throwable _ eof))]
          (if (identical? form eof)
            forms
            (recur (conj forms form))))))))

(defn- ns-form [forms]
  (first (filter #(and (seq? %) (= 'ns (first %))) forms)))

(defn declarations-in-source
  "PURE: every literal custom-element declaration in CLJS/CLJC/CLJ source string
  `src`, as `[{:tag … :properties #{…}} …]` in source order. Resolves the
  source's own `(ns …)` aliases so the recognized head is exactly how that
  source spells `re-frame.ui/custom-element`. Order in the returned vector is
  irrelevant to classification (the conflict barrier makes duplicates idempotent
  and contradictions refuse); it is kept only for deterministic seeding."
  [src]
  (let [;; A cheap first pass gets the ns form (and thus aliases) so the full
        ;; read can resolve ::alias/kw; the ns form itself never carries one.
        head-forms (read-all-forms src {})
        nsf (ns-form head-forms)
        forms (if nsf (read-all-forms src (alias-map nsf)) head-forms)
        heads (if nsf (ui-heads nsf) #{custom-element-fqn})]
    (into [] (keep #(declaration heads %)) forms)))

;; ---------------------------------------------------------------------------
;; Seeding — the two callers
;; ---------------------------------------------------------------------------

(defn source-seed
  "The `[source tag decl]` triples the build hook folds into the flat all-members
  manifest (`build/element-manifest`), for one build source declaring namespace
  `ns-sym` with `src` text. `ns-sym` is the declaring source (the conflict/owner
  unit)."
  [ns-sym src]
  (mapv (fn [{:keys [tag properties]}]
          [ns-sym tag {:properties properties}])
        (declarations-in-source src)))

;; --- plain-JVM / SSR lazy own-source harvest -------------------------------

(defonce ^{:private true
           :doc "Namespaces already harvested on the plain-JVM/SSR path, keyed
  [build-id ns-sym]. The declarations are stable per source-load, so a namespace
  is read at most once per build identity. Cleared by `reset-harvested!` for
  test isolation; never consulted on the Shadow pass path (that seeds from the
  build graph at `:compile-prepare`)."}
  harvested (atom #{}))

(defn reset-harvested! []
  (reset! harvested #{})
  nil)

(defn- classpath-source
  "Source text of namespace `ns-sym` located on the classpath, or nil. Tries the
  CLJS/CLJC/CLJ extensions in the order the compiler would."
  [ns-sym]
  (let [base (-> (str ns-sym)
                 (clojure.string/replace "-" "_")
                 (clojure.string/replace "." "/"))]
    (some (fn [ext]
            (when-let [url (io/resource (str base ext))]
              (slurp url)))
          [".cljc" ".cljs" ".clj"])))

(defn- current-file-source
  "Source text of the file currently being compiled (`*file*`), or nil. Covers
  a source loaded off the classpath (e.g. `clojure.main <script>`); nil for the
  REPL sentinel `NO_SOURCE_PATH`, whose forms are already ordered."
  []
  (let [f *file*]
    (when (and (string? f) (not= f "NO_SOURCE_PATH"))
      (or (let [file (io/file f)]
            (when (.isFile file) (slurp file)))
          (when-let [url (io/resource f)] (slurp url))))))

(defn- ns->source
  "The source text for namespace `ns-sym`: the classpath resource (the exact
  file the compiler loaded) with the file currently being compiled as a
  fallback for off-classpath sources. nil (REPL forms, generated namespaces)
  makes the harvest a graceful no-op."
  [ns-sym]
  (or (classpath-source ns-sym)
      (current-file-source)))

(defn ensure-namespace-harvested!
  "Plain-JVM / SSR path: seed namespace `ns-sym`'s OWN literal custom-element
  declarations into the ambient build ONCE, before its views classify. Reads the
  namespace's source from the classpath and admits each declaration through the
  ambient conflict barrier (`build/contribute-element-checked!`), so a view
  expanded before a declaration textually below it still classifies against the
  whole source. Idempotent, memoized per [build-id ns-sym]; a no-op when the
  source cannot be located (a REPL/generated namespace, whose forms are already
  ordered)."
  [ns-sym]
  (when (symbol? ns-sym)                 ; a missing ns must never memoize a
    (let [key [(build/current-build-id) ns-sym]]  ; nil key that poisons every ns
      (when-not (contains? @harvested key)
        (swap! harvested conj key)
        (when-let [src (ns->source ns-sym)]
          (doseq [{:keys [tag properties]} (declarations-in-source src)]
            (build/contribute-element-checked! ns-sym tag {:properties properties}))))))
  nil)
