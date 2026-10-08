(ns re-frame.error-catalogue-channel-conformance-test
  "The JVM conformance gate over Spec 009 §Error event catalogue. It parses the
  catalogue table out of `spec/009-Instrumentation.md` and the `*Tags` schemas
  out of `spec/Spec-Schemas.md`, scans every artefact's `src/` tree, and checks:

    1. every row's `Channel` cell is `always-on` or `diagnostic`, and no
       category has two rows;
    2. the parsed always-on set equals
       `re-frame.always-on-axis-conformance-cljs-test/always-on-categories`,
       the literal that dual-runtime suite drives through the error-emit
       listener, so promotion is exercised rather than documentary;
    3. every category `src/` emits or throws through a chokepoint with a
       literal category is catalogued, and every category passed literally to
       an always-on chokepoint is catalogued `always-on`;
    4. every key a canonical `*Tags` schema declares is listed in its row's
       `:tags` cell, and `tags-column-paired-floor` records how many schemas
       that diff reaches.

  JVM-only (`.clj`): it slurps repo markdown and source files."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            ;; A test-only dep of core (through the schemas artefact).
            [malli.core :as malli]
            [re-frame.interop :as rf.interop]
            [re-frame.trace :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]
            ;; A test-only dep of core, so the hiccup-tier emitter is callable here.
            [re-frame.ssr.hydrate :as rf.ssr.hydrate]
            [re-frame.impl-source-corpus :as rf.impl-source-corpus]
            [re-frame.always-on-axis-conformance-cljs-test :as rf.always-on-axis-conformance-cljs-test]))

;; ---------------------------------------------------------------------------
;; Catalogue parser
;; ---------------------------------------------------------------------------

(def ^:private spec-009-file
  "Resolved from the core JVM test CWD (`implementation/core/`), falling back
  to `implementation/` for a REPL run."
  (let [nested (io/file "../../spec/009-Instrumentation.md")
        legacy (io/file "../spec/009-Instrumentation.md")]
    (if (.exists nested) nested legacy)))

(def ^:private catalogue-row-re
  "One catalogue row: group 1 is the back-ticked `:operation`, group 2 the RAW
  `Channel` cell, captured whole so a blank or typo'd cell still parses and is
  flagged rather than dropped."
  #"^\|\s*`(:rf\.[^`]+)`\s*\|\s*`?:[^|`]+`?\s*\|([^|]*)\|")

(def ^:private catalogue-heading-re
  "The bare `### Error event catalogue` heading, not the later prose
  subsection whose heading carries a suffix."
  #"^###\s+Error event catalogue\s*$")

(def ^:private section-heading-re
  "A `#`-`###` heading ends the catalogue section; a `####` subsection does not."
  #"^#{1,3}\s+\S")

(defn- catalogue-section-lines
  "The lines of the canonical catalogue section, heading excluded. Scoping
  keeps the doc's other `:rf.*` tables (the three-column quick reference) out
  of the parse."
  [lines]
  (->> lines
       (drop-while #(not (re-find catalogue-heading-re %)))
       (drop 1)
       (take-while #(not (re-find section-heading-re %)))))

(defn- parse-catalogue
  "`[{:category <kw> :channel <trimmed Channel cell>} …]`, in table order."
  []
  (->> (slurp spec-009-file)
       (str/split-lines)
       (catalogue-section-lines)
       (keep (fn [line]
               (when-let [[_ cat-str chan] (re-find catalogue-row-re line)]
                 {:category (keyword (subs cat-str 1))
                  :channel  (str/trim chan)})))
       vec))

(def ^:private catalogue-tag-row-re
  "A row for the tags arm. Only a back-ticked `:rf.` first cell parses, so a
  struck-through retired row does not."
  #"^\|\s*`(:rf\.[^`]+)`\s*\|")

(def ^:private allowed-channels #{"always-on" "diagnostic"})

;; ---------------------------------------------------------------------------
;; Source scan: the categories the runtime emits or throws
;; ---------------------------------------------------------------------------
;;
;; Literal-only by design. A category reaching a chokepoint as a VARIABLE
;; (`fx.cljc`'s `emit-fx-error!`, the router's computed handler-exception
;; category, the per-surface throwers that take the category as a parameter,
;; the SSR forwarders of a caller-built record) is not captured: the scan
;; under-reports and never false-positives.

;; The apostrophe is in the class so `:rf.warning/large-value-unschema'd`
;; parses whole; the area segment may be dotted (`:rf.http.interceptor/…`).
(def ^:private category-kw-class "[a-z0-9][a-z0-9'-]*")

(def ^:private emit-error-re
  "The category argument of `emit-error!` / `emit-warning!` /
  `dispatch-on-error!` / `emit-error-both!`, bare or ns-qualified."
  (re-pattern (str "(?:emit-error-both!|emit-error!|dispatch-on-error!|emit-warning!)\\s+"
                   "(:rf\\.[a-z][a-z0-9.]*/" category-kw-class ")")))

(def ^:private emit-bang-warning-re
  "The category of an `emit!` whose op-type is `:warning` / `:advisory`.
  Success-path and lifecycle `emit!`s are not diagnostic categories."
  (re-pattern (str "emit!\\s+:(?:warning|advisory)\\s+"
                   "(:rf\\.[a-z][a-z0-9.]*/" category-kw-class ")")))

(def ^:private throw-error-re
  "The first argument of `throw-error!` / `thrown-ex-info`, on the same line or
  the next. The emit scans cannot see a category the runtime only throws."
  (re-pattern (str "(?:throw-error!|thrown-ex-info)\\s+"
                   "(:rf\\.[a-z][a-z0-9.]*/" category-kw-class ")")))

(def ^:private always-on-mechanism-re
  "The positional always-on chokepoints: `dispatch-on-error!`, and
  `emit-error-both!`, whose first axis is `dispatch-on-error!`. Neither is
  debug-gated, so a category passed to them reaches production."
  (re-pattern (str "(?:emit-error-both!|dispatch-on-error!)\\s+"
                   "(:rf\\.[a-z][a-z0-9.]*/" category-kw-class ")")))

(def ^:private always-on-record-mechanism-re
  "The `:error` value of a literal record passed to `dispatch-error-record!`.
  The category is a map value, not a positional argument, so the positional
  pattern above matches nothing here. The bounded window spans the newline the
  record usually follows. `dispatch-frame-teardown-report!` builds its category
  inside its own body, so no call-site scan can harvest it; the always-on
  literal pins that one."
  (re-pattern (str "dispatch-error-record!(?:[\\s\\S]{0,64}?):error\\s+"
                   "(:rf\\.[a-z][a-z0-9.]*/" category-kw-class ")")))

(def ^:private dev-gated-record-categories
  "Categories whose only `dispatch-error-record!` sites sit behind
  `rf.interop/debug-enabled?`, so a `diagnostic` cell is correct for them: the
  text scan sees the call but not the gate. `:rf.error/malformed-schema` is
  dispatched from `core/router.cljc` inside `(when rf.interop/debug-enabled? …)`
  and from `schemas/validate.cljc`, whose caller `validate-app-schema!` is
  gated in a different fn."
  #{:rf.error/malformed-schema})

(defn- scan-categories
  "The categories `res` harvest (group 1) across every non-test source file."
  [& res]
  (->> (rf.impl-source-corpus/non-test-source-files)
       (mapcat (fn [f]
                 (let [src (slurp f)]
                   (mapcat #(map second (re-seq % src)) res))))
       (map (fn [s] (keyword (subs s 1))))
       set))

(defn- emitted-categories []
  (scan-categories emit-error-re emit-bang-warning-re throw-error-re))

(defn- always-on-mechanism-categories
  "Categories the code fans onto the always-on axis whatever their Channel cell."
  []
  (set/difference (scan-categories always-on-mechanism-re always-on-record-mechanism-re)
                  dev-gated-record-categories))

(def ^:private out-of-catalogue-allow-list
  "Emitted categories that deliberately have no catalogue row. Empty: Spec 009
  catalogues every emitted category, thrown-only ones included."
  #{})

;; ---------------------------------------------------------------------------
;; Channel column and emit sites
;; ---------------------------------------------------------------------------

(deftest catalogue-categories-are-unique
  (let [dups (->> (parse-catalogue)
                  (map :category)
                  frequencies
                  (keep (fn [[c n]] (when (> n 1) c))))]
    (is (empty? dups)
        (str "categories with more than one catalogue row: " (pr-str dups)))))

(deftest parsed-always-on-set-equals-the-exercise-literal
  (testing "a category catalogued `always-on` but missing from the exercise
            literal is never driven through the listener; a literal entry not
            catalogued `always-on` is stale"
    (let [catalogue-always-on (->> (parse-catalogue)
                                   (filter #(= "always-on" (:channel %)))
                                   (map :category)
                                   set)
          literal             rf.always-on-axis-conformance-cljs-test/always-on-categories]
      (is (= catalogue-always-on literal)
          (str "only-in-catalogue: "
               (pr-str (sort (set/difference catalogue-always-on literal)))
               " ; only-in-literal: "
               (pr-str (sort (set/difference literal catalogue-always-on))))))))

(deftest every-table-row-has-a-nonblank-valid-channel
  (let [invalid (remove (comp allowed-channels :channel) (parse-catalogue))]
    (is (empty? invalid)
        (str "catalogue rows whose Channel cell is blank or not one of "
             allowed-channels ": " (pr-str (map (juxt :category :channel) invalid))))))

(deftest source-scan-finds-the-emit-sites
  (testing "the walk reaches every artefact (cross-checked against an
            independently shaped enumeration of implementation/**/src/, which
            catches a walk that skips the nested adapter artefacts) and the
            literal `emit-error!` arm is live"
    (let [cats (emitted-categories)
          {:keys [missing extra]} (rf.impl-source-corpus/corpus-cross-check)]
      (is (empty? missing)
          (str "the walk MISSED production source under implementation/**/src/: "
               (pr-str (vec missing))))
      (is (empty? extra)
          (str "the walk reached source OUTSIDE implementation/**/src/: "
               (pr-str (vec extra))))
      (is (>= (count cats) 50) (str "found " (count cats) " categories"))
      (is (contains? cats :rf.error/on-destroy-handler-exception))
      (is (contains? cats :rf.error/no-such-handler)))))

(deftest every-emitted-category-is-catalogued
  (let [catalogued (->> (parse-catalogue) (map :category) set)
        missing    (set/difference (emitted-categories) catalogued out-of-catalogue-allow-list)]
    (is (empty? missing)
        (str "categories emitted from src with no Spec 009 catalogue row: "
             (pr-str (sort missing))
             " — add the row (with a Channel), or list the category in "
             "`out-of-catalogue-allow-list` with a rationale."))))

(deftest always-on-mechanism-emits-are-catalogued-always-on
  (testing "a category emitted through an always-on chokepoint reaches
            production whatever its Channel cell says, so a `diagnostic` cell
            there is stale"
    (let [channel-by-cat (into {} (map (juxt :category :channel)) (parse-catalogue))
          not-always-on  (->> (always-on-mechanism-categories)
                              (remove #(= "always-on" (channel-by-cat %)))
                              sort)]
      (is (empty? not-always-on)
          (str "categories emitted through dispatch-on-error! / emit-error-both! / "
               "dispatch-error-record! whose catalogue row is not `always-on`: "
               (pr-str (mapv (juxt identity channel-by-cat) not-always-on))
               " — graduate the row (and add it to the exercise literal), demote "
               "the emission to the trace-only fns, or, for a debug-gated site, "
               "list it in `dev-gated-record-categories`.")))))

(deftest dev-gated-record-list-stays-honest
  (testing "each exemption is still dispatched through `dispatch-error-record!`
            and still not catalogued `always-on`, so the list cannot rot into a
            blanket suppression"
    (let [harvested (scan-categories always-on-record-mechanism-re)
          always-on (->> (parse-catalogue)
                         (filter #(= "always-on" (:channel %)))
                         (map :category)
                         set)
          stale     (set/difference dev-gated-record-categories harvested)
          graduated (set/intersection dev-gated-record-categories always-on)]
      (is (empty? stale)
          (str "no longer dispatched through `dispatch-error-record!`, drop: "
               (pr-str (sort stale))))
      (is (empty? graduated)
          (str "now catalogued `always-on`, drop: " (pr-str (sort graduated)))))))

;; ---------------------------------------------------------------------------
;; The `:tags` column against the canonical `*Tags` schemas
;; ---------------------------------------------------------------------------
;;
;; Spec-Schemas.md defines one `*Tags` schema per trace-emitting catalogue row,
;; so the check is a keys-set difference: every key a schema declares must be
;; LISTED in its row's `:tags` cell. A schema pairs with a row through the
;; operation's NAME half (`:rf.error/resource-route-plan` →
;; `ResourceRoutePlanTags`) or the WHOLE operation (`:rf.fx/handled` →
;; `FxHandledTags`); the second spelling is how the corpus disambiguates two
;; operations sharing a name half (`RouteNavTokenStaleSuppressedTags`), so no
;; alias table is needed. A name claimed by two rows identifies neither and is
;; reported. Thrown-`ex-info` and union-record rows carry no `:tags` map,
;; derive no schema, and never pair.

(def ^:private spec-schemas-file
  "`spec/Spec-Schemas.md`, resolved the same way `spec-009-file` is."
  (let [nested (io/file "../../spec/Spec-Schemas.md")
        legacy (io/file "../spec/Spec-Schemas.md")]
    (if (.exists nested) nested legacy)))

(def ^:private tags-schema-def-re
  "The opening of a canonical tags schema: `(def <Pascal>Tags` at line start."
  #"(?m)^\(def ([A-Za-z0-9]+Tags)\s")

(defn- read-form-at
  "The form beginning at `idx` in `text`, read as EDN data; `:default` keeps an
  unexpected tagged literal from throwing."
  [^String text ^long idx]
  (with-open [r (java.io.PushbackReader.
                  (java.io.StringReader. (subs text idx)))]
    (edn/read {:eof nil :default (fn [_tag v] v)} r)))

(def ^:private schema-arm-combinators
  "Heads whose vector children are alternative shapes of one payload, so each
  child `[:map …]` is an arm (`HydrationMismatchTags` is an `:or` of two)."
  #{:or :and})

(defn- schema-arm-maps
  "Every top-level `[:map …]` arm of a schema form. Recursion stops at any other
  head, so a map inside an entry's value is never an arm."
  [schema]
  (when (vector? schema)
    (cond
      (= :map (first schema))
      [schema]

      (contains? schema-arm-combinators (first schema))
      (mapcat schema-arm-maps (filter vector? (rest schema))))))

(defn- schema-map-keys
  "The UNION of the direct entry keys of every arm of a parsed `(def XxxTags …)`
  form; a first-arm-only reading would silently stop diffing the other arm's
  keys. The first `[:map …]` found anywhere is unioned in too, so this reader
  can only widen."
  [form]
  (let [arm-maps (concat (schema-arm-maps (last form))
                         (->> (tree-seq coll? seq form)
                              (filter #(and (vector? %) (= :map (first %))))
                              (take 1)))]
    (into #{}
          (comp (mapcat rest) (filter vector?) (map first) (filter keyword?))
          arm-maps)))

(defn- parse-tags-schemas
  "`{\"HandlerExceptionTags\" #{:category :failing-id …}, …}`."
  ([] (parse-tags-schemas (slurp spec-schemas-file)))
  ([text]
   (let [m (re-matcher tags-schema-def-re text)]
     (loop [acc {}]
       (if (.find m)
         (recur (assoc acc (.group m 1)
                       (schema-map-keys (read-form-at text (.start m)))))
         acc)))))

(defn- tags-schema-form
  "The top-level Malli form a named `*Tags` schema declares (types kept)."
  [schema-name]
  (let [text    (slurp spec-schemas-file)
        matcher (re-matcher tags-schema-def-re text)]
    (loop []
      (when (.find matcher)
        (if (= schema-name (.group matcher 1))
          (last (read-form-at text (.start matcher)))
          (recur))))))

(def ^:private tags-cell-key-re
  "A `:tags` cell entry: a back-ticked span that is EXACTLY one keyword. A bare
  `:foo` in prose, or inside a multi-token span such as `#{:frame :recovery}`,
  is not a listing."
  #"`(:[\w.*+!?<>=/'-]+)`")

(def ^:private markdown-link-re
  "A markdown inline link. Stripped before keys are harvested: a keyword in
  link text is a cross-reference about a key, and counting it would let a cell
  misspell the key it lists."
  #"\[[^\]]*\]\([^)]*\)")

(def ^:private table-delimiter-re
  "A markdown column delimiter: a `|` after an EVEN run of backslashes (zero
  included). An odd run spends its last backslash escaping the pipe, which is
  then cell content (`:rf.error/infinite-missing-next-page-param` writes
  `next-param \\| nil`). The run is inspected, never consumed, so no cell
  loses a character; Java's lookbehind needs a bound, hence `{0,16}`."
  #"(?<=(?<!\\)(?:\\\\){0,16})\|")

(defn- cell-tag-keys
  "The keys a `:tags` cell lists: lone back-ticked keywords outside links."
  [cell]
  (into #{} (map (comp keyword #(subs % 1) second))
        (re-seq tags-cell-key-re (str/replace cell markdown-link-re " "))))

(defn- parse-catalogue-tag-rows
  "`[{:category <kw> :tags-cell <string> :field-count <int>} …]` for every
  active row of the catalogue section. The `:tags` cell is the sixth column;
  `-1` keeps a blank trailing cell. `:field-count` is the split's width, so a
  row whose columns slid can be detected."
  ([] (parse-catalogue-tag-rows (slurp spec-009-file)))
  ([text]
   (->> (str/split-lines text)
        (catalogue-section-lines)
        (keep (fn [line]
                (when-let [[_ cat-str] (re-find catalogue-tag-row-re line)]
                  (let [cells (str/split line table-delimiter-re -1)]
                    (when (>= (count cells) 7)
                      {:category    (keyword (subs cat-str 1))
                       :tags-cell   (str/trim (nth cells 6))
                       :field-count (count cells)})))))
        vec)))

(defn- pascal
  "`\"resource-route-plan\"` → `\"ResourceRoutePlan\"`."
  [s]
  (->> (str/split s #"[-.]") (map str/capitalize) (apply str)))

(defn- derived-schema-names
  "Both schema names an `:operation` may carry: the name half alone
  (`ResourceRoutePlanTags`), and the whole operation with its namespace tail
  (`FxHandledTags`, `RouteNavTokenStaleSuppressedTags`)."
  [category]
  (let [nspace (namespace category)
        tail   (when (str/starts-with? (str nspace) "rf.") (subs nspace 3))
        nm     (pascal (name category))]
    (cond-> [(str nm "Tags")]
      tail (conj (str (pascal tail) nm "Tags")))))

(defn- schema-claims
  "`{\"SchemaName\" [row …]}`: every schema an active row claims, by either
  spelling."
  [rows schemas]
  (reduce (fn [acc row]
            (reduce (fn [acc nm]
                      (cond-> acc
                        (contains? schemas nm) (update nm (fnil conj []) row)))
                    acc
                    (derived-schema-names (:category row))))
          {}
          rows))

(def ^:private envelope-only-tag-keys
  "The slot 009 excludes from every trace-event row's `:tags` cell by rule.
  `:recovery` is deliberately absent: `build-event` hoists it to the envelope,
  so a schema declaring it under `:tags` is a defect and must red."
  #{:category})

(defn tags-column-findings
  "`[{:category … :schema … :missing #{…}} …]`: every row paired to exactly one
  schema whose `:tags` cell omits a key that schema declares."
  [rows schemas]
  (->> (schema-claims rows schemas)
       (keep (fn [[schema-name group]]
               (when (= 1 (count group))
                 (let [row     (first group)
                       missing (set/difference (get schemas schema-name)
                                               envelope-only-tag-keys
                                               (cell-tag-keys (:tags-cell row)))]
                   (when (seq missing)
                     {:category (:category row)
                      :schema   schema-name
                      :missing  missing})))))
       (sort-by :category)
       vec))

(defn tags-column-recovery-listings
  "`[category …]`: every row whose `:tags` cell LISTS `:recovery`, a key
  `build-event` hoists out of `:tags` on every branch."
  [rows]
  (->> rows
       (keep (fn [{:keys [category tags-cell]}]
               (when (contains? (cell-tag-keys tags-cell) :recovery)
                 category)))
       sort
       vec))

(defn tags-column-ambiguities
  "`[{:schema … :categories [… …]} …]`: every schema name claimed by more than
  one row, which therefore pairs with neither."
  [rows schemas]
  (->> (schema-claims rows schemas)
       (keep (fn [[schema-name group]]
               (when (< 1 (count group))
                 {:schema schema-name :categories (mapv :category group)})))
       (sort-by :schema)
       vec))

(defn- paired-count
  "How many schemas resolve to exactly one row, the population the diff reaches."
  [rows schemas]
  (->> (schema-claims rows schemas) vals (filter #(= 1 (count %))) count))

(def ^:private tags-column-paired-floor
  "THE PAIRING LEDGER: how many canonical schemas the keys-set diff reaches.
  Deleting a paired schema, or renaming it to a name no row derives, un-pairs
  its row with no finding to show for it; this count is what moves. Change it
  only in the commit that adds or removes a pairing. Re-derive with
  `(paired-count (parse-catalogue-tag-rows) (parse-tags-schemas))`."
  90)

(deftest tags-column-pairing-is-live
  (let [rows    (parse-catalogue-tag-rows)
        schemas (parse-tags-schemas)
        paired  (paired-count rows schemas)]
    (is (= tags-column-paired-floor paired)
        (str "schemas PAIRED and diffed: " paired "; `tags-column-paired-floor` "
             "records " tags-column-paired-floor ". Lower: a pairing was lost (a "
             "`*Tags` schema deleted, or renamed to a name no row derives) — "
             "restore it or lower the ledger in this commit. Higher: raise the "
             "ledger so it cannot absorb the next lost pairing."))
    (is (contains? (get schemas "HandlerExceptionTags") :exception-message)
        "HandlerExceptionTags parsed with its declared keys")
    (is (empty? (->> schemas (filter (comp empty? val)) (map key) sort))
        (str "schemas that parsed to an EMPTY key set, which can never produce "
             "a finding: "
             (pr-str (->> schemas (filter (comp empty? val)) (map key) sort))))))

(deftest tags-column-pairing-is-unambiguous
  (let [ambiguous (tags-column-ambiguities (parse-catalogue-tag-rows)
                                           (parse-tags-schemas))]
    (is (empty? ambiguous)
        (str "schemas claimed by more than one catalogue row, so diffed against "
             "neither. Name the schema for its owning operation (as "
             "`RouteNavTokenStaleSuppressedTags` is) so the whole-operation "
             "spelling pairs it: " (pr-str ambiguous)))))

(deftest tags-column-keys-are-documented
  (let [findings (tags-column-findings (parse-catalogue-tag-rows)
                                       (parse-tags-schemas))]
    (is (empty? findings)
        (str "catalogue rows whose `:tags` cell omits keys their canonical "
             "schema declares — add the keys to the row, or correct the schema: "
             (pr-str (mapv (juxt :category :schema (comp sort :missing)) findings))))))

(deftest tags-column-never-lists-the-envelope-level-recovery-slot
  (let [listed (tags-column-recovery-listings (parse-catalogue-tag-rows))]
    (is (empty? listed)
        (str "catalogue rows whose `:tags` cell LISTS `:recovery`, which "
             "`build-event` hoists to the envelope on every branch — state it in "
             "the `Default :recovery` column instead: " (pr-str listed)))))

;; --- the two-tier `:rf.ssr/hydration-mismatch` category -----------------------
;;
;; The keys-set arm never looks at an emitted event, so it cannot see a schema
;; that models only one of a category's runtime shapes, or a cell naming a key
;; no producer supplies. This witness drives the shipped emitters and checks
;; the corpus against what comes out. The hiccup tier (`verify-hydration!`) is
;; `.cljc` and is called for real; the two adoption emitters are `.cljs`, so
;; their payload maps are read out of the source as data and driven through
;; the real `rf.trace/emit!`.

(def ^:private hydration-mismatch-adoption-sites
  "The two adoption-tier `(rf.trace/emit! :warning :rf.ssr/hydration-mismatch {…})`
  sites, relative to `implementation/`."
  ["core/src/re_frame/substrate/spine.cljs"
   "fresco/src/re_frame/fresco/impl/mount.cljs"])

(def ^:private adoption-emit-re
  #"emit!\s+:warning\s+:rf\.ssr/hydration-mismatch\s*")

(defn- adoption-payload-form
  "The literal payload map the adoption emit at `rel-path` passes, read as data."
  [rel-path]
  (let [src (slurp (io/file rf.impl-source-corpus/implementation-root rel-path))
        m   (re-matcher adoption-emit-re src)]
    (when (.find m)
      (read-form-at src (.end m)))))

(defn- source-quoted-symbol
  "The symbol a source form quotes. `clojure.edn` has no quote macro, so
  `'a/b` arrives as one symbol carrying the apostrophe."
  [v]
  (cond
    (and (seq? v) (= 'quote (first v))) (second v)
    (symbol? v)                         (symbol (str/replace-first (str v) "'" ""))
    :else                               v))

(def ^:private adoption-payload-keys
  "The slots both adoption emitters pass, which the witness transcribes."
  #{:error :where :recovery})

(defn- catalogue-tags-cell
  "The `:tags` cell of the active catalogue row for `category`."
  [category]
  (->> (parse-catalogue-tag-rows)
       (some (fn [row] (when (= category (:category row)) (:tags-cell row))))))

(def ^:private witness-frame-id
  "Names no registered frame, so `verify-hydration!` takes the `:server-hash`
  opt and the default `:ssr` knobs without this suite installing an adapter or
  resetting the shared registrar."
  :rf.frame/hydration-mismatch-witness)

(defn- capture-trace-events
  "Every trace event emitted while `f` runs."
  [f]
  (let [seen (atom [])
        id   (gensym "hydration-mismatch-witness")]
    (rf.trace.tooling/register-listener! id (fn [ev] (swap! seen conj ev)))
    (try (f) (finally (rf.trace.tooling/unregister-listener! id)))
    @seen))

(defn- emitted-hydration-mismatch
  "The hydration-mismatch trace event `f` emits."
  [f]
  (->> (capture-trace-events f)
       (filter #(= :rf.ssr/hydration-mismatch (:operation %)))
       first))

(deftest hydration-mismatch-schema-models-both-emitted-tiers
  (testing "the keys-set reader takes the union of both `:or` arms"
    (let [ks (get (parse-tags-schemas) "HydrationMismatchTags")]
      (is (contains? ks :server-hash) "hiccup-arm key reached the reader")
      (is (contains? ks :error)       "adoption-arm key reached the reader")))

  (testing "the arms are disjoint: each tier's payload matches its own arm only"
    (let [[hiccup-arm adoption-arm] (rest (tags-schema-form "HydrationMismatchTags"))
          hiccup   {:category    :rf.ssr/hydration-mismatch
                    :rf.error/id :rf.ssr/hydration-mismatch
                    :where       'rf/verify-hydration!
                    :server-hash "A" :client-hash "B"
                    :frame       :app
                    :failing-id  :rf/hydrate
                    :reason      "Hydration mismatch: server hash 'A' != client hash 'B'."}
          adoption {:error "Hydration failed because the initial UI does not match."
                    :where 're-frame.substrate.spine/make-render}]
      (is (malli/validate hiccup-arm hiccup))
      (is (malli/validate adoption-arm adoption))
      (is (not (malli/validate adoption-arm hiccup)))
      (is (not (malli/validate hiccup-arm adoption)))))

  ;; Trace emits are no-ops in the `:prod-gate` lane, so only the corpus half
  ;; above runs there.
  (when rf.interop/debug-enabled?
    (let [schema     (tags-schema-form "HydrationMismatchTags")
          declared   (get (parse-tags-schemas) "HydrationMismatchTags")
          documented (set/union (cell-tag-keys (catalogue-tags-cell :rf.ssr/hydration-mismatch))
                                envelope-only-tag-keys)
          described  (fn [label tags]
                       (is (malli/validate schema tags)
                           (str label ": emitted `:tags` must validate against the "
                                "shipped schema. Got: " (pr-str tags)))
                       (is (empty? (set/difference (set (keys tags)) declared))
                           (str label ": emitted keys no arm declares: "
                                (pr-str (sort (set/difference (set (keys tags)) declared)))))
                       (is (empty? (set/difference (set (keys tags)) documented))
                           (str label ": emitted keys the catalogue cell does not list: "
                                (pr-str (sort (set/difference (set (keys tags)) documented))))))]
      (testing "hiccup tier, driven through the shipped emitter"
        (described "hiccup"
                   (:tags (emitted-hydration-mismatch
                            #(rf.ssr.hydrate/verify-hydration!
                               witness-frame-id
                               "client-hash-B"
                               {:server-hash     "server-hash-A"
                                :first-diff-path [:main 0 :h1]})))))

      (testing "adoption tier: each site's own payload, driven through `rf.trace/emit!`"
        (doseq [rel-path hydration-mismatch-adoption-sites]
          (let [form  (adoption-payload-form rel-path)
                where (source-quoted-symbol (:where form))
                ev    (emitted-hydration-mismatch
                        #(rf.trace/emit! :warning :rf.ssr/hydration-mismatch
                                         {:error    "Hydration failed because the initial UI does not match."
                                          :where    where
                                          :recovery (:recovery form)}))
                tags  (:tags ev)]
            (is (= adoption-payload-keys (set (keys form)))
                (str rel-path "'s payload keys moved; the witness transcribes exactly "
                     adoption-payload-keys ". Found: " (pr-str (sort (keys form)))))
            (is (qualified-symbol? where)
                (str rel-path " stamps a quoted, namespace-qualified `:where`. Found: "
                     (pr-str (:where form))))
            (is (not (contains? tags :category))
                "a `:warning` envelope carries no `[:tags :category]`, as the cell says")
            (is (= :warned-and-replaced (:recovery ev))
                "`:recovery` is hoisted to the envelope on the `:warning` branch too")
            (described rel-path tags)
            (is (malli/validate schema (assoc tags :error nil))
                "an error with no `.message` emits a nil `:error`, which validates"))))

      (testing "no `:head-id`: `verify-hydration!` merges no such opt into the payload"
        (is (not (contains? declared :head-id)))
        (is (not (contains? documented :head-id))
            "the cell may state the negative in a multi-token span, never list the key")))))

;; --- the `:where` slots' declared type ----------------------------------------
;;
;; The keys-set arm discards declared types, so it cannot see a `:where` slot
;; loosened to `:any`. Every producer of these slots stamps a quoted symbol,
;; while other categories carry a keyword `:where`, so `:any` would admit a
;; spelling that is right elsewhere in the corpus.

(defn- where-entry
  "The `:where` entry of a named schema's top-level `[:map …]`."
  [schema-name]
  (->> (rest (tags-schema-form schema-name))
       (filter #(and (vector? %) (= :where (first %))))
       first))

(deftest no-frame-context-where-is-typed-not-any
  (is (= [:where {:optional true} :symbol] (where-entry "NoFrameContextTags"))))

(deftest remaining-tag-where-slots-are-typed-not-any
  ;; BadFrameProviderArgTags shares one `where` argument with NoFrameContextTags
  ;; through `require-frame-provider-target!`, so the two must agree.
  (doseq [schema-name ["BadFrameProviderArgTags" "ResourceSsrBlockingTimeoutTags"]]
    (is (= [:where {:optional true} :symbol] (where-entry schema-name)) schema-name)))

;; --- self-tests: each arm can red on the defect it exists to catch -----------

(def ^:private route-plan-schemas
  {"ResourceRoutePlanTags"
   #{:category :reason :route-id :frame :contributor :plan-cause}})

(defn- catalogue-fixture
  "A minimal catalogue section carrying `rows` verbatim, so the real parser is
  what is exercised."
  [& rows]
  (str/join "\n"
            (concat ["### Error event catalogue"
                     ""
                     "| `:operation` | `:op-type` | Channel | Trigger | Default `:recovery` | `:tags` |"
                     "|---|---|---|---|---|---|"]
                    rows
                    ["" "### Schemas" ""])))

(defn- route-plan-row
  "The parsed tag rows of a `:rf.error/resource-route-plan` fixture row whose
  `:tags` cell is `tags`."
  [tags]
  (parse-catalogue-tag-rows
    (catalogue-fixture
      (str "| `:rf.error/resource-route-plan` | `:error` | diagnostic "
           "| A route resource plan failed. | `:no-recovery` | " tags " |"))))

(deftest tags-column-key-in-link-text-is-not-a-documented-key
  (testing "a misspelt listed key still reds beside a link that spells it
            correctly: link text is prose about a key, not the row's list"
    (is (= [{:category :rf.error/resource-route-plan
             :schema   "ResourceRoutePlanTags"
             :missing  #{:plan-cause}}]
           (tags-column-findings
             (route-plan-row (str "`:route-id`, `:reason`, `:frame`, `:contributor`, "
                                  "`:plan-cauze` (see [`:plan-cause`](#error-event-catalogue))"))
             route-plan-schemas)))))

(deftest tags-column-arm-exempts-only-the-category-slot
  (testing "`:category` is exempt; every other undocumented schema key reds"
    (is (= [{:category :rf.error/resource-route-plan
             :schema   "ResourceRoutePlanTags"
             :missing  #{:reason :frame :contributor :plan-cause}}]
           (tags-column-findings (route-plan-row "`:route-id`") route-plan-schemas)))))

(deftest tags-column-recovery-arm-reds-by-name-and-greens-on-the-strike
  (let [row (fn [tags]
              (parse-catalogue-tag-rows
                (catalogue-fixture
                  (str "| `:rf.error/frame-context-corrupted` | `:error` | diagnostic "
                       "| The ambient frame context was a non-frame value. "
                       "| `:no-frame-context` | " tags " |"))))]
    (is (= [:rf.error/frame-context-corrupted]
           (tags-column-recovery-listings
             (row "`:received`, `:recovery` (`:no-frame-context`), `:reason`")))
        "a cell LISTING `:recovery` reds, naming its row")
    (is (empty? (tags-column-recovery-listings (row "`:received`, `:reason`")))
        "the struck cell is clean")))

(deftest tags-column-pairing-keys-off-the-whole-operation
  (testing "two operations sharing a name half derive one schema name, which
            identifies neither; the collision is reported rather than silently
            costing the pair"
    (is (= [{:schema     "StaleSuppressedTags"
             :categories [:rf.http/stale-suppressed
                          :rf.route.nav-token/stale-suppressed]}]
           (tags-column-ambiguities
             (parse-catalogue-tag-rows
               (catalogue-fixture
                 (str "| `:rf.http/stale-suppressed` | `:info` | diagnostic "
                      "| … | `:dropped` | `:rf.reply/status` |")
                 (str "| `:rf.route.nav-token/stale-suppressed` | `:error` "
                      "| diagnostic | … | `:dropped` | `:carried-token` |")))
             {"StaleSuppressedTags" #{:category :carried-token}})))))

(deftest tags-column-reader-honours-the-escaped-pipe
  (testing "every active row splits to the same field count, so no row's
            `:tags` cell is read from a slid column — the corpus's escaped-pipe
            row, `:rf.error/infinite-missing-next-page-param`, included"
    (let [rows  (parse-catalogue-tag-rows)
          modal (->> rows (map :field-count) frequencies (apply max-key val) key)
          slid  (->> rows
                     (remove #(= modal (:field-count %)))
                     (mapv (juxt :category :field-count)))]
      (is (empty? slid)
          (str "rows that split to a different field count than their " modal
               "-field siblings: " (pr-str slid) ". Escape a literal pipe inside "
               "a cell as `\\|`; if it is already escaped, the reader is at fault.")))))

(deftest table-delimiter-honours-backslash-run-parity
  (testing "an odd backslash run escapes the pipe, so the row keeps its columns;
            an even run leaves the pipe a delimiter, so the row gains one and
            the sixth field is `Default :recovery`. The corpus carries only odd
            runs, so only this fixture reaches the even half (checked against
            Python-Markdown's `tables` extension)."
    (doseq [[pipe-run expected] [["\\|"   [8 "`:route-id`, `:reason`"]]
                                 ["\\\\|" [9 "`:no-recovery`"]]]]
      (is (= expected
             ((juxt :field-count :tags-cell)
              (first (parse-catalogue-tag-rows
                       (catalogue-fixture
                         (str "| `:rf.error/resource-route-plan` | `:error` "
                              "| diagnostic | A plan step returns `next "
                              pipe-run " nil`. | `:no-recovery` "
                              "| `:route-id`, `:reason` |"))))))
          (pr-str pipe-run)))))
