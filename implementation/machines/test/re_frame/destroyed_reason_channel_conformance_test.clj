(ns re-frame.destroyed-reason-channel-conformance-test
  "rf2-hh1jhc / rf2-9q1zn2 — the destroyed-reason CHANNEL/REASON MATRIX pin.

  The runtime emits machine-destroy traces on two parallel channels
  (Spec 009 §Two-channel teardown):

    - `:rf.machine.lifecycle/destroyed` — the registrar-substrate
      observation. Frame-exit reaping is its SOLE trigger; the only
      `:reason` it carries is `:parent-frame-destroyed`.
    - `:rf.machine/destroyed` — the fx-substrate observation. Carries
      every non-frame-exit reason: `:rf.machine/finished` and
      `:explicit` (parent-cascade teardowns stamp `:explicit`).

  A reason belongs to EXACTLY ONE channel — the (channel, reason) pair is
  the contract, not two independent sets. This test pins that matrix as
  exact TUPLES against three kinds of evidence, so a channel move, a
  changed pair, or a new/dynamic reason on any surface goes RED:

    A. Doc ↔ doc — the four normative surfaces are parsed and compared as
       exact tuples (§`Authoritative surfaces` below):
         1. Spec 009 §`:op-type` vocabulary — the canonical matrix table.
         2. Spec-Schemas §`:rf/trace-event` — the fx `:machine`-family row
            and the `:rf.machine.lifecycle/destroyed` sole-reason row.
         3. Spec 005 §Final states D6 — the fx-channel enrichment vocab.
         4. Cross-Spec-Interactions §route-change teardown — the positive
            (fx-channel, `:explicit`) pair a view-unmount/route swap emits,
            AND that the retired `:parent-unmount-cascade` reason is never
            reintroduced there.

    B. Emit sites (STRUCTURAL, fails closed) — every destroy emitter is
       enumerated by READING the source forms (not a text regex), so the
       (channel, reason) tuple at each choke point is pinned exactly.
       A reason expression that is neither a documented literal for its
       channel nor the sanctioned forwarding symbol `reason` is an
       EXPLICIT failure with a source location — dynamic/unparsed reasons
       fail closed instead of being silently skipped.

    C. Executable fixtures — the runtime is DRIVEN and the emitted
       (channel, reason) tuple is asserted for `:explicit` and
       `:rf.machine/finished` (fx channel) and `:parent-frame-destroyed`
       (lifecycle channel). Mutating either
       argument of any emit reds the matching fixture.

  Authoritative surfaces: the 009 matrix table is the CANONICAL enum;
  Spec-Schemas and 005 D6 RESTATE it; Cross-Spec documents the one
  route-change pair. The emit sites are the RUNTIME truth; the executable
  fixtures are the behavioural ground truth. Every layer must agree.

  JVM-only (`.clj`): the doc/source layers `slurp` + reader-parse repo
  markdown and source, which only the JVM `clojure -M:test` runner can do;
  the executable layer drives the machine runtime on the plain-atom
  substrate (the machines `.cljc` runtime runs on the JVM)."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [java.io PushbackReader]))

;; ---------------------------------------------------------------------------
;; The ruling (rf2-hh1jhc) — the two channel vocabularies
;; ---------------------------------------------------------------------------

(def ^:private lifecycle-channel :rf.machine.lifecycle/destroyed)
(def ^:private fx-channel        :rf.machine/destroyed)
(def ^:private destroy-channels  #{lifecycle-channel fx-channel})

(def ^:private expected-lifecycle-reasons
  "The registrar channel's SOLE reason — frame-exit reaping."
  #{:parent-frame-destroyed})

(def ^:private expected-fx-reasons
  "The fx channel's complete `:reason` vocabulary (005 D6) — now closed over
  observable behaviour: every documented reason has an emit site and every
  emit site stamps a documented reason (the doc-vocabulary == emit-census
  equality below is the standing drift guard). Adding a reason means updating
  the 009 matrix, the Spec-Schemas rows, 005 D6, AND this literal — the
  co-edit is the point."
  #{:rf.machine/finished :explicit})

(def ^:private forwarding-reason-sym
  "The one sanctioned DYNAMIC `:reason` at an fx emit choke point: the bound
  parameter `reason` that the fx terminal (`emit-destroyed!`) and its
  forwarder (`destroy-resolved!`) pass through. Every other non-literal
  reason expression at a destroy emitter fails closed."
  'reason)

;; ---------------------------------------------------------------------------
;; File resolution (JVM test CWD is `implementation/machines/`)
;; ---------------------------------------------------------------------------

(defn- resolve-repo-file
  "Resolve `rel` (repo-root-relative) from the machines-artefact test CWD,
  with a fallback for a transitional REPL run from `implementation/`.
  Mirrors `re-frame.error-catalogue-channel-conformance-test`."
  [rel]
  (let [nested (io/file (str "../../" rel))
        legacy (io/file (str "../" rel))]
    (if (.exists nested) nested legacy)))

(def ^:private spec-009-file          (resolve-repo-file "spec/009-Instrumentation.md"))
(def ^:private spec-schemas-file      (resolve-repo-file "spec/Spec-Schemas.md"))
(def ^:private spec-005-file          (resolve-repo-file "spec/005-StateMachines.md"))
(def ^:private cross-spec-file        (resolve-repo-file "spec/Cross-Spec-Interactions.md"))

(def ^:private src-roots
  "The two source trees that carry destroy emit sites: the machines
  artefact (fx channel + the frame-destroy orchestrator) and core (the
  no-machines lifecycle fallback in `frame.cljc`)."
  (->> [(io/file "src") (io/file "../core/src")
        ;; transitional REPL-from-`implementation/` fallbacks
        (io/file "machines/src") (io/file "core/src")]
       (filter #(.isDirectory %))
       vec))

(defn- source-files []
  (->> src-roots
       (mapcat file-seq)
       (filter #(.isFile %))
       (filter (fn [f] (re-find #"\.clj[cs]?$" (.getName f))))))

;; ---------------------------------------------------------------------------
;; Surface parsers (doc ↔ doc)
;; ---------------------------------------------------------------------------

(def ^:private matrix-row-re
  "One row of the Spec 009 canonical matrix table:
     | `:reason` | `:channel` | emitted-by | meaning |
  Group 1 = the reason keyword, group 2 = the channel keyword,
  group 3 = the raw Emitted-by cell (whole cell, so the *reserved*
  marker is machine-readable). Leading whitespace allowed — the table
  is indented inside a list item."
  #"^\s*\|\s*`(:[\w./-]+)`\s*\|\s*`(:rf\.machine[\w./-]*)`\s*\|([^|]*)\|")

(defn- parse-009-matrix
  "Parse the canonical channel/reason matrix out of Spec 009 into
  `[{:reason kw :channel kw :emitted-by str} ...]`. Scoped to the table
  following the matrix heading sentence so no other 009 table matches."
  []
  (->> (slurp spec-009-file)
       str/split-lines
       (drop-while #(not (str/includes? % "the canonical channel/reason matrix")))
       (take-while #(not (str/includes? % "The enum is open")))
       (keep (fn [line]
               (when-let [[_ reason channel emitted-by] (re-find matrix-row-re line)]
                 {:reason     (keyword (subs reason 1))
                  :channel    (keyword (subs channel 1))
                  :emitted-by (str/trim emitted-by)})))
       vec))

(defn- backticked-keywords
  "Every backticked keyword in `s`, as keywords."
  [s]
  (->> (re-seq #"`(:[\w./-]+)`" s)
       (map (fn [[_ k]] (keyword (subs k 1))))))

(defn- find-line
  "The first line of `file` matching `pred`, or nil."
  [file pred]
  (->> (slurp file) str/split-lines (filter pred) first))

(defn- spec-schemas-fx-reasons
  "The fx-reason enumeration in Spec-Schemas' `:machine` family row —
  the span `carries \\`:reason\\` — one of <kws> — `. Returns a set."
  []
  (let [row (find-line spec-schemas-file #(str/starts-with? % "| `:machine` |"))
        [_ span] (when row (re-find #"carries `:reason` — one of (.*?) —" row))]
    (set (some-> span backticked-keywords))))

(defn- spec-schemas-lifecycle-row []
  (find-line spec-schemas-file
             #(str/starts-with? % "| `:rf.machine.lifecycle/destroyed` |")))

(defn- lifecycle-row-violation
  "Structural verdict for the Spec-Schemas `:rf.machine.lifecycle/destroyed`
  row (rf2-j9ojw shape-3). Returns a violation string when the row does NOT
  pin the SOLE reason `:parent-frame-destroyed`, or nil when it is exact.

  ANCHORED captures — NOT substrings — so an appended alternative fails
  closed. The old substring form let ``… `:parent-frame-destroyed` or
  `:bogus` …`` pass (the required prefix stayed a substring and `:bogus` was
  not a KNOWN competing reason). Here the `:tags` reason slot is captured as
  the map's FINAL entry, and the sole-reason sentence must name EXACTLY ONE
  backticked keyword between `is always` and its em-dash clause — a second
  keyword (an appended `or `:bogus``) breaks it."
  [row]
  (if (nil? row)
    "Spec-Schemas is missing the `:rf.machine.lifecycle/destroyed` row"
    (let [tags-body    (second (re-find #"`:tags \{([^}]*)\}`" row))
          tags-reasons (mapv (comp keyword #(subs % 1) second)
                             (re-seq #":reason (:[\w./-]+)" (or tags-body "")))
          [_ sole-span] (re-find #"`:reason` is always (.*?) [—–-] " row)
          sole-reasons  (some-> sole-span backticked-keywords vec)]
      (cond
        (nil? tags-body)
        "the lifecycle row's `:tags {…}` map did not parse"

        (not= [:parent-frame-destroyed] tags-reasons)
        (str "the `:tags` map must bind exactly `:reason :parent-frame-destroyed`; "
             "parsed tag reasons: " (pr-str tags-reasons))

        (not (str/ends-with? (str/trimr tags-body) ":reason :parent-frame-destroyed"))
        (str "`:reason :parent-frame-destroyed` must be the FINAL entry of the "
             "`:tags` map; tags body: " (pr-str tags-body))

        (nil? sole-span)
        "the row's sole-reason sentence (``:reason` is always `…` —`) did not parse"

        (not= [:parent-frame-destroyed] sole-reasons)
        (str "the sole-reason sentence must name EXACTLY `:parent-frame-destroyed` "
             "and no alternative; parsed " (pr-str sole-reasons)
             " from span " (pr-str sole-span))

        :else nil))))

(defn- spec-005-d6-reasons
  "The D6 enrichment vocabulary — the span `\\`:reason\\` tag — one of
  <kws>.` in the D6 sub-decision row. Returns a set."
  []
  (let [row (find-line spec-005-file #(str/starts-with? % "| D6 |"))
        ;; the span ends at the backtick-then-period closing the sentence —
        ;; a bare `\.` stop would cut inside `:rf.machine/finished`
        [_ span] (when row (re-find #"`:reason` tag — one of (.*?`)\. " row))]
    (set (some-> span backticked-keywords))))

(defn- cross-spec-route-teardown-tuple
  "The POSITIVE (channel, reason) pair Cross-Spec-Interactions documents for
  a route-change / view-unmount teardown: the sentence
  `... emits the fx-substrate \\`:rf.machine/destroyed\\` with
  \\`:reason :explicit\\``. Returns `[channel reason]` or nil."
  []
  (let [[_ ch reason]
        (re-find #"emits the fx-substrate `(:rf\.machine[\w./-]*)` with `:reason (:[\w./-]+)`"
                 (slurp cross-spec-file))]
    (when ch [(keyword (subs ch 1)) (keyword (subs reason 1))])))

;; ---------------------------------------------------------------------------
;; A. Doc ↔ doc conformance (exact tuples)
;; ---------------------------------------------------------------------------

(deftest matrix-parses-and-assigns-each-reason-to-exactly-one-channel
  (let [rows (parse-009-matrix)]
    (testing "sanity: the 009 matrix table parses"
      (is (.exists spec-009-file) (str "missing " spec-009-file))
      (is (>= (count rows) 3) (str "matrix rows parsed: " (pr-str rows))))
    (testing "each reason appears exactly once (one channel per reason)"
      (let [dups (->> rows (map :reason) frequencies
                      (filter (fn [[_ n]] (> n 1))) (map first))]
        (is (empty? dups) (str "reasons on more than one matrix row: " (pr-str dups)))))
    (testing "only the two teardown channels appear"
      (is (= destroy-channels (set (map :channel rows)))))))

(deftest matrix-carries-the-ruled-channel-vocabularies
  (let [rows (parse-009-matrix)
        by-channel (fn [ch] (->> rows (filter #(= ch (:channel %))) (map :reason) set))]
    (testing "lifecycle channel = frame-exit only (rf2-hh1jhc ruling)"
      (is (= expected-lifecycle-reasons (by-channel lifecycle-channel))))
    (testing "fx channel = the three non-frame-exit reasons (005 D6)"
      (is (= expected-fx-reasons (by-channel fx-channel))))))

(deftest spec-schemas-fx-row-matches-the-matrix
  (testing "Spec-Schemas `:machine` family row enumerates exactly the fx set"
    (is (= expected-fx-reasons (spec-schemas-fx-reasons))
        "the `carries `:reason` — one of …` span in Spec-Schemas' `:machine`
         row must equal the 009 matrix's fx-channel vocabulary")))

(deftest spec-schemas-lifecycle-row-is-single-reason
  (let [row (spec-schemas-lifecycle-row)]
    (is (some? row) "Spec-Schemas carries the `:rf.machine.lifecycle/destroyed` row")
    (testing "the row STRUCTURALLY pins the sole reason `:parent-frame-destroyed`
              — anchored `:tags` reason slot + sole-reason sentence, not
              substrings, so an appended alternative fails closed"
      (is (nil? (lifecycle-row-violation row)) (lifecycle-row-violation row)))
    (testing "no OTHER matrix reason leaks into the lifecycle row"
      (let [other-reasons (disj (set/union expected-fx-reasons expected-lifecycle-reasons)
                                :parent-frame-destroyed)
            leaked (set/intersection (set (backticked-keywords row)) other-reasons)]
        (is (empty? leaked)
            (str "reasons other than :parent-frame-destroyed named in the lifecycle row: "
                 (pr-str leaked)))))))

(deftest spec-005-d6-matches-the-matrix
  (testing "005 D6's enrichment vocabulary equals the fx set"
    (is (= expected-fx-reasons (spec-005-d6-reasons)))))

(deftest cross-spec-interactions-pins-the-route-teardown-tuple
  (testing "Cross-Spec-Interactions documents the POSITIVE route-change
            teardown pair as (fx-channel, :explicit) — a view-unmount / route
            swap tears the machine down on the fx channel, NOT the frame-exit
            lifecycle channel. Changing either half of the pair reds this."
    (is (= [fx-channel :explicit] (cross-spec-route-teardown-tuple))
        (str "Cross-Spec's route-change teardown sentence must document the "
             "exact pair [" fx-channel " :explicit]; parsed: "
             (pr-str (cross-spec-route-teardown-tuple)))))
  (testing "the retired `:parent-unmount-cascade` reason is never reintroduced
            on the lifecycle channel here (the rf2-hh1jhc contradiction) —
            teardown-on-route-change is the fx channel's `:explicit`, not a
            lifecycle `:parent-unmount-cascade`"
    (is (not (str/includes? (slurp cross-spec-file) ":parent-unmount-cascade"))
        "reintroducing the retired reason into Cross-Spec-Interactions
         requires the 009 matrix (and this test) to change first")))

;; ---------------------------------------------------------------------------
;; B. Emit sites — STRUCTURAL enumeration (reader-based; fails closed)
;; ---------------------------------------------------------------------------
;;
;; Rather than a text regex (which silently skips any `:reason` shape it does
;; not literally match), we READ the source forms and structurally walk them
;; to find every destroy emit choke point:
;;
;;   - `(trace/emit! <op-type> <channel> <arg>)` where <channel> is a destroy
;;     channel — the CHANNEL choke;
;;   - `(emit-destroyed! <arg-map>)` — the fx reason-origination call;
;;   - `(destroy-resolved! _ _ <reason> …)` — the fx reason forwarder.
;;
;; Each site's `:reason` must be a documented literal for its channel, or the
;; sanctioned forwarding symbol `reason`. Anything else — a foreign symbol, a
;; computed expression, an undocumented keyword — is an explicit failure with
;; a source location. A read error propagates rather than truncating the scan.

(defn- reader-ns-sym
  "The `ns` symbol declared by `file` (its first top-level form)."
  [file]
  (with-open [r (PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (let [form (read {:read-cond :allow :eof ::eof} r)]
        (when (and (seq? form) (= 'ns (first form))) (second form))))))

(defn- expand-reader-conditionals
  "Recursively replace every PRESERVED `ReaderConditional` in `form` with a
  plain list of ALL its branch bodies, so a single structural walk inspects
  BOTH the `:clj` and `:cljs` views (rf2-j9ojw shape-2 fail-closed). Reading
  with `:read-cond :allow` collapses to the JVM `:clj` branch only, so a
  destroy reason under a `#?(:cljs …)` branch was invisible; expanding both
  branches into siblings makes any branch's emit site reachable."
  [form]
  (cond
    (reader-conditional? form)
    (->> (:form form)                       ; (feature body feature body …)
         (partition 2)
         (map (comp expand-reader-conditionals second))
         (apply list))
    (map? form)   (into (empty form)
                        (map (fn [[k v]]
                               [(expand-reader-conditionals k)
                                (expand-reader-conditionals v)]))
                        form)
    (seq? form)   (apply list (map expand-reader-conditionals form))
    (vector? form) (mapv expand-reader-conditionals form)
    (set? form)   (into (empty form) (map expand-reader-conditionals) form)
    :else form))

(defn- read-all-conditional-forms
  "Every top-level form read from `rdr` with reader conditionals PRESERVED
  (`:read-cond :preserve`) then expanded across BOTH host feature views (see
  `expand-reader-conditionals`), with `*ns*` bound so auto-resolved (`::`)
  keywords resolve and `*default-data-reader-fn*` dropping any unknown tag to
  its value (so a preserved `:cljs` branch does not throw on a cljs-only
  reader tag). Fails CLOSED: a read error propagates (reds the gate) rather
  than silently truncating the scan."
  [^java.io.Reader rdr the-ns]
  (with-open [r (PushbackReader. rdr)]
    (binding [*read-eval*              false
              *ns*                     the-ns
              *default-data-reader-fn* (fn [_tag value] value)]
      (->> (repeatedly #(read {:read-cond :preserve :eof ::eof} r))
           (take-while #(not= ::eof %))
           (mapv expand-reader-conditionals)))))

(defn- read-source-forms
  "Every top-level form of `file`, read across both host feature views with
  `*ns*` bound to the file's LOADED namespace."
  [file]
  (read-all-conditional-forms
    (io/reader file)
    (or (find-ns (reader-ns-sym file))
        (create-ns (gensym "rf-destroy-scan")))))

(defn- read-conditional-forms
  "Read forms from a source STRING (mutation fixtures) across both host views."
  [s]
  (read-all-conditional-forms (java.io.StringReader. s)
                              (create-ns (gensym "rf-destroy-scan"))))

(defn- call-name
  "The unqualified name of `form`'s head symbol (ignoring any ns alias), or
  nil when `form` is not a symbol-headed list."
  [form]
  (when (and (seq? form) (symbol? (first form))) (name (first form))))

(defn- assoc-step-reason
  "For a THREADING STEP of a `cond->` (the step form, threaded acc elided) —
  e.g. `(assoc :system-id system-id)` — return `[::ok <reason-values>]` where
  `<reason-values>` are the literal `:reason` values that step injects (empty
  when it injects none), or `[::unproven]` when the step cannot be proven
  reason-safe. Supported reason-safe steps: `(assoc …)` / `(assoc! …)` whose
  KEY positions are all literal keywords. Anything else (a `merge`, an
  `into`, a function call, a non-literal key) fails closed."
  [step]
  (let [h (call-name step)]
    (if (contains? #{"assoc" "assoc!"} h)
      (let [kvs (rest step)]
        (if (and (even? (count kvs))
                 (every? keyword? (take-nth 2 kvs)))
          [::ok (keep (fn [[k v]] (when (= k :reason) v)) (partition 2 kvs))]
          [::unproven]))
      [::unproven])))

(defn- resolve-reason-arg
  "Structurally resolve the reason-bearing ARGUMENT form of a destroy emit
  call — the choke point rf2-j9ojw closes to fail CLOSED. Returns
  `{:proven? bool :reasons [values…]}`:

    - a MAP LITERAL is fully enumerable — its `:reason` value (or, when the
      key is absent, the sanctioned `:explicit`/`nil` default, i.e. NO
      reasons) is proven;
    - a `(cond-> <map-literal> test step …)` is proven iff every step is
      reason-safe (see `assoc-step-reason`), collecting the base map's
      `:reason` plus any literal `:reason` an `assoc` step injects;
    - EVERYTHING ELSE — a bare local symbol (`payload`), an
      `(assoc payload :reason :bogus)` constructor, a `merge`, a computed
      expression — is NOT structurally provable, so `:proven?` is false and
      the site fails closed rather than being silently read as the default."
  [arg]
  (cond
    (map? arg)
    {:proven? true
     :reasons (if (contains? arg :reason) [(:reason arg)] [])}

    (and (seq? arg) (contains? #{"cond->" "cond->>"} (call-name arg)))
    (let [base       (second arg)
          steps      (map second (partition 2 (drop 2 arg)))
          base-res   (resolve-reason-arg base)
          step-verds (map assoc-step-reason steps)]
      (if (and (:proven? base-res)
               (every? #(= ::ok (first %)) step-verds))
        {:proven? true
         :reasons (into (vec (:reasons base-res)) (mapcat second step-verds))}
        {:proven? false :reasons []}))

    :else {:proven? false :reasons []}))

(defn- or-default-reasons
  "Every `:or {reason <v>}` destructuring default bound to the SYMBOL
  `reason` anywhere in `forms` (the fx terminal's default). Returns a set of
  the bound values."
  [forms]
  (set
    (for [form forms
          sf (tree-seq coll? seq form)
          :when (and (map? sf) (contains? sf 'reason))]
      (get sf 'reason))))

(defn- destroy-emit-files
  "Production source files that carry a destroy emit choke point — a textual
  pre-filter; each is then structurally parsed. Not hardcoded, so a NEW emit
  site in a new file is picked up (and fails closed if its reason is dynamic)."
  []
  (->> (source-files)
       (filter (fn [f]
                 (let [s (slurp f)]
                   (or (str/includes? s "emit-destroyed!")
                       (str/includes? s "destroy-resolved!")
                       (and (str/includes? s "trace/emit!")
                            (or (str/includes? s (str fx-channel))
                                (str/includes? s (str lifecycle-channel))))))))
       vec))

(defn- site-findings
  "0 or 1 census finding for a single (already-expanded) subform `sf`. Each
  destroy choke point pins its (channel, reason) tuple; the reason ARGUMENT
  is structurally resolved (`resolve-reason-arg`) so `:proven?` records
  whether the shape can be enumerated at all — an unprovable shape fails
  closed downstream instead of defaulting to `:explicit`."
  [fname sf]
  (let [h (call-name sf), v (vec sf)]
    (cond
      (= h "emit!")
      (let [ch (nth v 2 nil)]
        (when (contains? destroy-channels ch)
          (let [{:keys [proven? reasons]} (resolve-reason-arg (nth v 3 nil))]
            [{:kind :channel-emit :file fname :form sf :channel ch
              :proven? proven? :reasons (vec reasons)}])))

      (= h "emit-destroyed!")
      (let [{:keys [proven? reasons]} (resolve-reason-arg (nth v 1 nil))]
        [{:kind :emit-destroyed :file fname :form sf
          :proven? proven? :reasons (vec reasons)}])

      (= h "destroy-resolved!")
      [{:kind :destroy-resolved :file fname :form sf
        :reason (nth v 3 ::missing)}]

      :else nil)))

(defn- enumerate-forms
  "Structurally walk `forms` (from one file or a mutation string), returning a
  vector of findings pinning each (channel, reason) choke point."
  [fname forms]
  (vec
    (for [form forms
          sf (tree-seq coll? seq form)
          :when (seq? sf)
          finding (site-findings fname sf)]
      finding)))

(defn- enumerate-emit-sites
  "Structurally walk every destroy-emit-bearing source file (both host views)."
  [files]
  (vec (mapcat (fn [f] (enumerate-forms (.getName f) (read-source-forms f)))
               files)))

(defn- valid-fx-reason?
  "An fx-channel reason is valid iff it is the sanctioned forwarding symbol
  `reason` or a documented, emitted fx keyword."
  [r]
  (or (= r forwarding-reason-sym)
      (and (keyword? r) (contains? expected-fx-reasons r))))

(defn- valid-lifecycle-reason? [r]
  (and (keyword? r) (contains? expected-lifecycle-reasons r)))

(defn- loc [finding]
  (str (:file finding) " :: " (pr-str (:form finding))))

(defn- emit-site-violations
  "Pure verdict for the structural emit census — a seq of human-readable
  violation strings (empty ⇒ every site pins a provable, documented tuple).
  Fails CLOSED: a reason ARGUMENT whose shape could not be structurally
  proven (`:proven?` false — an `(assoc … :reason …)`, a bare local payload,
  a `merge`, …) is a violation, NOT a silent `:explicit` default. Shared by
  the real-source gate and the mutation fixtures so both red on the same
  logic."
  [findings]
  (vec
    (for [{:keys [kind channel reason proven? reasons] :as fnd} findings
          msg
          (case kind
            :channel-emit
            (if-not proven?
              [(str "destroy channel-emit reason arg is not structurally provable "
                    "(fails closed) at " (loc fnd))]
              (keep (fn [r]
                      (cond
                        (= channel lifecycle-channel)
                        (when-not (valid-lifecycle-reason? r)
                          (str "lifecycle-channel emit must stamp a documented literal "
                               "lifecycle reason " expected-lifecycle-reasons "; saw "
                               (pr-str r) " at " (loc fnd)))
                        (= channel fx-channel)
                        (when-not (valid-fx-reason? r)
                          (str "fx-channel emit must stamp a documented fx literal "
                               expected-fx-reasons " or forward `reason`; saw "
                               (pr-str r) " at " (loc fnd)))))
                    reasons))

            :emit-destroyed
            (if-not proven?
              [(str "emit-destroyed! reason arg is not structurally provable — "
                    "cannot prove a supported literal map / :explicit default "
                    "(fails closed) at " (loc fnd))]
              (keep (fn [r]                          ; 0 reasons = the :explicit default
                      (when-not (valid-fx-reason? r)
                        (str "emit-destroyed! must pass a documented fx literal "
                             expected-fx-reasons " or forward `reason`; saw "
                             (pr-str r) " at " (loc fnd))))
                    reasons))

            :destroy-resolved
            (when-not (and (keyword? reason) (contains? expected-fx-reasons reason))
              [(str "destroy-resolved! must be called with a documented fx literal "
                    expected-fx-reasons " (a dynamic reason here fails closed); saw "
                    (pr-str reason) " at " (loc fnd))])

            nil)]
      msg)))

(deftest emit-sites-pin-exact-channel-reason-tuples
  (let [files    (destroy-emit-files)
        findings (enumerate-emit-sites files)]
    (testing "the structural scan reached the live emit sites"
      (is (seq files) "destroy-emit-bearing source files resolved from the test CWD")
      (is (seq findings) "at least one destroy emit choke point was enumerated"))

    ;; --- per-site (channel, reason) validity: fails CLOSED on any reason that
    ;;     is neither a documented literal for its channel nor forwarding
    ;;     `reason`, AND on any reason argument whose shape cannot be proven.
    (testing "every destroy emit site pins a provable, documented (channel,
              reason) tuple — dynamic / assoc / local reason shapes fail closed"
      (let [violations (emit-site-violations findings)]
        (is (empty? violations) (str/join "\n" violations))))

    ;; --- coverage: the emitted TUPLE SETS are exactly the matrix's.
    (let [lifecycle-emits (filter #(and (= :channel-emit (:kind %))
                                        (= lifecycle-channel (:channel %)))
                                  findings)
          fx-channel-emits (filter #(and (= :channel-emit (:kind %))
                                         (= fx-channel (:channel %)))
                                   findings)
          lifecycle-reason-set (set (mapcat :reasons lifecycle-emits))
          ;; every literal keyword that ORIGINATES an fx reason (emit-destroyed!
          ;; map values + destroy-resolved! positional + the `:or` default)
          fx-origination
          (set/union
            (set (filter keyword? (mapcat :reasons (filter #(= :emit-destroyed (:kind %)) findings))))
            (set (filter keyword? (map :reason (filter #(= :destroy-resolved (:kind %)) findings))))
            (or-default-reasons (mapcat read-source-forms files)))]
      (testing "the lifecycle channel is emitted from BOTH the orchestrator and
                the no-machines fallback, each with only the sole reason"
        (is (<= 2 (count lifecycle-emits))
            (str "expected >=2 lifecycle-channel emit sites (frame_destroy.cljc "
                 "orchestrator + frame.cljc fallback); saw "
                 (mapv :file lifecycle-emits)))
        (is (= expected-lifecycle-reasons lifecycle-reason-set)
            (str "lifecycle-channel emitted reasons must be exactly "
                 expected-lifecycle-reasons "; saw " (pr-str lifecycle-reason-set))))
      (testing "the fx channel has its terminal emit"
        (is (seq fx-channel-emits) "the fx-channel `emit-destroyed!` terminal was found"))
      (testing "the emitted fx reasons EXACTLY equal the documented fx
                vocabulary — the standing drift guard now that the channel is
                closed over observable behaviour (documented == emit census)"
        (is (= expected-fx-reasons fx-origination)
            (str "fx reasons originated at emit sites must be exactly "
                 expected-fx-reasons "; saw " (pr-str fx-origination)))))))

(deftest no-matrix-row-is-reserved
  (testing "the fx-channel `:reason` vocabulary is closed over observable
            behaviour — NO 009 matrix row may be marked *reserved* (a
            reserved-but-never-emitted member is the phantom this matrix once
            carried as `:parent-unmount-cascade`; reintroducing one forces an
            emitter + fixtures in the same change, not a speculative slot)"
    (let [reserved (->> (parse-009-matrix)
                        (filter #(str/includes? (:emitted-by %) "reserved"))
                        (map :reason)
                        set)]
      (is (empty? reserved)
          (str "no 009 matrix row may be marked reserved; saw "
               (pr-str reserved))))))

;; ---------------------------------------------------------------------------
;; B′. Mutation proofs — each previously-false-green shape now reds the gate
;; ---------------------------------------------------------------------------
;; These feed MUTATED inputs to the SAME pure verdict functions the gate above
;; uses (`resolve-reason-arg`, `emit-site-violations`, `enumerate-forms`,
;; `read-conditional-forms`, `lifecycle-row-violation`). Each asserts the
;; mutation trips a violation while the genuine shape stays clean — proving the
;; three false-green shapes rf2-j9ojw names now FAIL CLOSED.

(deftest mutation-shape1-census-fails-closed-on-unprovable-reason-arg
  (testing "an (assoc … :reason :bogus) reason arg is NOT structurally provable"
    (is (false? (:proven? (resolve-reason-arg (read-string "(assoc payload :reason :bogus)"))))))
  (testing "a bare local-symbol payload is NOT structurally provable"
    (is (false? (:proven? (resolve-reason-arg (read-string "payload"))))))
  (testing "an assoc step INSIDE a cond-> is captured (not silently missed)"
    (is (= [:bogus]
           (:reasons (resolve-reason-arg (read-string "(cond-> {} c (assoc :reason :bogus))"))))))
  (testing "the genuine emit-argument shapes stay provable"
    (is (:proven? (resolve-reason-arg (read-string "{:frame f :reason :rf.machine/finished}"))))
    (is (:proven? (resolve-reason-arg (read-string "{:frame f :reason reason}"))))     ; forwarding sym
    (is (:proven? (resolve-reason-arg (read-string "{:frame f}"))))                    ; :explicit default
    (is (:proven? (resolve-reason-arg
                    (read-string "(cond-> {:reason reason} (some? x) (assoc :work-generation g))")))))
  (testing "the census VERDICT reds on an (assoc …) emit-destroyed! site …"
    (is (seq (emit-site-violations
               (enumerate-forms "mut.cljc"
                                [(read-string "(emit-destroyed! (assoc payload :reason :bogus))")])))))
  (testing "… and on a bare local-payload emit-destroyed! site"
    (is (seq (emit-site-violations
               (enumerate-forms "mut.cljc" [(read-string "(emit-destroyed! payload)")])))))
  (testing "the census VERDICT stays clean on genuine literal / default sites"
    (is (empty? (emit-site-violations
                  (enumerate-forms "ok.cljc" [(read-string "(emit-destroyed! {:frame f})")]))))
    (is (empty? (emit-site-violations
                  (enumerate-forms "ok.cljc"
                                   [(read-string "(emit-destroyed! {:frame f :reason :explicit})")]))))))

(deftest mutation-shape2-census-reads-both-host-views
  (testing "a destroy emit under a #?(:cljs …) branch IS inspected — a CLJS-only
            undocumented fx reason reds the census (it was invisible to the
            single JVM `:clj` read)"
    (let [forms    (read-conditional-forms
                     (str "(ns mut)\n"
                          "#?(:cljs (re-frame.trace/emit! :rf.machine :rf.machine/destroyed "
                          "{:reason :cljs-only-bogus}))\n"))
          findings (enumerate-forms "mut.cljc" forms)]
      (is (seq findings) "the #?(:cljs …) destroy emit was enumerated")
      (is (seq (emit-site-violations findings))
          "a #?(:cljs …) :cljs-only-bogus fx reason must fail the census")))
  (testing "the same emit under #?(:clj …) with a documented reason stays clean"
    (let [forms (read-conditional-forms
                  (str "(ns mut)\n"
                       "#?(:clj (re-frame.trace/emit! :rf.machine :rf.machine/destroyed "
                       "{:reason :explicit}))\n"))]
      (is (empty? (emit-site-violations (enumerate-forms "mut.cljc" forms)))))))

(deftest mutation-shape3-spec-schemas-row-rejects-appended-alternative
  (let [genuine (spec-schemas-lifecycle-row)
        mutated (str/replace genuine
                             "`:reason` is always `:parent-frame-destroyed`"
                             "`:reason` is always `:parent-frame-destroyed` or `:bogus`")]
    (testing "sanity: the mutation actually rewrote the sole-reason sentence"
      (is (not= genuine mutated)))
    (testing "the genuine row passes the anchored structural verdict"
      (is (nil? (lifecycle-row-violation genuine)) (lifecycle-row-violation genuine)))
    (testing "an appended `or `:bogus`` on the sole-reason sentence fails closed
              (the old substring check admitted it)"
      (is (some? (lifecycle-row-violation mutated))))))

;; ---------------------------------------------------------------------------
;; C. Executable fixtures — drive the runtime, assert the exact emitted tuple
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest executable-explicit-destroy-emits-fx-explicit
  (testing "an explicit teardown (declarative :spawn exit cascade) emits EXACTLY
            (:rf.machine/destroyed, :explicit)"
    (rf/reg-machine :dre/child {:initial :running :data {} :states {:running {}}})
    (rf/reg-machine :dre/parent
                    {:initial :idle
                     :states  {:idle    {:on {:start :working}}
                               :working {:spawn {:machine-id :dre/child}
                                         :on    {:stop :idle}}}})
    (rf.machines.test-support/with-trace-capture captured
      (rf/dispatch-sync [:dre/parent [:start]])
      (rf/dispatch-sync [:dre/parent [:stop]])          ; exit :working → destroy-single!
      (let [fx (filter #(= fx-channel (:operation %)) @captured)]
        (is (seq fx) "an fx-channel :rf.machine/destroyed fired")
        (doseq [t fx]
          (is (= [fx-channel :explicit] [(:operation t) (-> t :tags :reason)])
              (str "explicit destroy must be the exact tuple [" fx-channel " :explicit]; saw "
                   (pr-str [(:operation t) (-> t :tags :reason)]))))))))

(deftest executable-finalize-emits-fx-finished
  (testing "a machine reaching a :final? state auto-destroys as EXACTLY
            (:rf.machine/destroyed, :rf.machine/finished)"
    (rf/reg-machine :drf/child
                    {:initial :running :data {}
                     :states  {:running {:on {:end :done}} :done {:final? true}}})
    (rf/reg-machine :drf/parent
                    {:initial :working :states {:working {:spawn {:machine-id :drf/child}}}})
    (rf.machines.test-support/with-trace-capture captured
      (rf/dispatch-sync [:drf/parent [:rf.machine.spawn/spawned]])
      (let [spawned (get-in (rf.machines.test-support/runtime-db)
                            [:rf.runtime/machines :spawned :drf/parent [:working]])]
        (rf/dispatch-sync [spawned [:end]]))          ; child → :final? → finalize
      (let [fin (filter #(= :rf.machine/finished (-> % :tags :reason)) @captured)]
        (is (seq fin) "a :rf.machine/finished destroy fired")
        (doseq [t fin]
          (is (= [fx-channel :rf.machine/finished] [(:operation t) (-> t :tags :reason)])
              (str "finalize must be the exact tuple [" fx-channel " :rf.machine/finished]; saw "
                   (pr-str [(:operation t) (-> t :tags :reason)]))))))))

(deftest executable-join-child-completion-emits-fx-finished
  (testing "a completed :spawn-all child emits EXACTLY
            (:rf.machine/destroyed, :rf.machine/finished) — at its OWN finality,
            not at join resolution. Completion is finality, so a folded child
            closes its own attempt; there is no separate reap and no
            cancellation-suppressing reason (the retired
            :rf.machine/join-reaped)."
    (let [mk-child (fn []
                     {:initial :running
                      :data    {:id nil}
                      :actions {:record-id (fn [{d :data ev :event}] {:data (assoc d :id (second ev))})}
                      :states  {:running {:on {:set-id {:action :record-id}
                                               :go     {:target :done}
                                               :fail   {:target :failed}}}
                                :done   {:final? true :output-key :id}
                                :failed {:final? true :error? true :output-key :id}}})]
      (rf/reg-machine :drj/a (mk-child))
      (rf/reg-machine :drj/b (mk-child))
      (rf/reg-machine :drj/sup
                      {:initial :idle
                       :states  {:idle   {:on {:start :racing}}
                                 :racing {:spawn-all
                                          {:children [{:id :a :machine-id :drj/a :start [:set-id :a]}
                                                      {:id :b :machine-id :drj/b :start [:set-id :b]}]
                                           :join             :any
                                           :on-some-complete [:race/won]}}}})
      (rf.machines.test-support/with-trace-capture captured
        (rf/dispatch-sync [:drj/sup [:start]])
        (let [ids  (get-in (rf.machines.test-support/runtime-db)
                           [:rf.runtime/machines :spawned :drj/sup [:racing] :children])
              a-id (:a ids)
              b-id (:b ids)]
          (rf/dispatch-sync [a-id [:go]])   ; :a completes (finality) -> :any resolves -> :b cancelled
          (let [a-destroys (filter #(and (= fx-channel (:operation %))
                                         (= a-id (:actor-id (:tags %))))
                                   @captured)
                b-destroys (filter #(and (= fx-channel (:operation %))
                                         (= b-id (:actor-id (:tags %))))
                                   @captured)]
            (is (seq a-destroys) "the completed child fired an fx destroy")
            (doseq [t a-destroys]
              (is (= [fx-channel :rf.machine/finished] [(:operation t) (-> t :tags :reason)])
                  (str "a completed join child must be the exact tuple [" fx-channel
                       " :rf.machine/finished]; saw "
                       (pr-str [(:operation t) (-> t :tags :reason)]))))
            (is (seq b-destroys) "the SURVIVOR fired an fx destroy at resolution")
            (doseq [t b-destroys]
              (is (= [fx-channel :explicit] [(:operation t) (-> t :tags :reason)])
                  (str "a cancelled survivor must be the exact tuple [" fx-channel
                       " :explicit]; saw "
                       (pr-str [(:operation t) (-> t :tags :reason)]))))))))))

(deftest executable-frame-destroy-emits-lifecycle-parent-frame-destroyed
  (testing "destroy-frame! with live machines emits EXACTLY
            (:rf.machine.lifecycle/destroyed, :parent-frame-destroyed) per actor"
    (rf/make-frame {:id :drl/auth :doc "destroyed-reason conformance frame"})
    (rf/reg-machine :drl/child {:initial :running :data {} :states {:running {}}})
    (rf/reg-machine :drl/boot
                    {:initial :idle :data {}
                     :states  {:idle {:on {:start {:action (fn [_]
                                                             {:fx [[:rf.machine/spawn
                                                                    {:machine-id :drl/child
                                                                     :id-prefix  :drl/child}]]})}}}}})
    (rf/dispatch-sync [:drl/boot [:start]] {:frame :drl/auth})
    (rf.machines.test-support/with-trace-capture captured
      (rf/destroy-frame! :drl/auth)
      (let [lc (filter #(= lifecycle-channel (:operation %)) @captured)]
        (is (seq lc) "a lifecycle-channel destroyed fired on frame destroy")
        (doseq [t lc]
          (is (= [lifecycle-channel :parent-frame-destroyed] [(:operation t) (-> t :tags :reason)])
              (str "frame destroy must be the exact tuple [" lifecycle-channel
                   " :parent-frame-destroyed]; saw "
                   (pr-str [(:operation t) (-> t :tags :reason)]))))))))
