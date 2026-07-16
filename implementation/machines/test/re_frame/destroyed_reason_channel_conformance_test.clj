(ns re-frame.destroyed-reason-channel-conformance-test
  "rf2-hh1jhc / rf2-9q1zn2 — the destroyed-reason CHANNEL/REASON MATRIX pin.

  The runtime emits machine-destroy traces on two parallel channels
  (Spec 009 §Two-channel teardown):

    - `:rf.machine.lifecycle/destroyed` — the registrar-substrate
      observation. Frame-exit reaping is its SOLE trigger; the only
      `:reason` it carries is `:parent-frame-destroyed`.
    - `:rf.machine/destroyed` — the fx-substrate observation. Carries
      every non-frame-exit reason: `:rf.machine/finished`,
      `:rf.machine/join-reaped`, and `:explicit` (parent-cascade
      teardowns stamp `:explicit`).

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
       (channel, reason) tuple is asserted for `:explicit`,
       `:rf.machine/finished`, `:rf.machine/join-reaped` (fx channel) and
       `:parent-frame-destroyed` (lifecycle channel). Mutating either
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
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom])
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
  #{:rf.machine/finished :rf.machine/join-reaped :explicit})

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
      (is (>= (count rows) 4) (str "matrix rows parsed: " (pr-str rows))))
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
    (testing "the tags schema pins the sole reason as the LAST entry of the map"
      (is (str/includes? row ":reason :parent-frame-destroyed}")
          "the row's `:tags` map must close with `:reason :parent-frame-destroyed`
           (the closing brace pins it as an exact, single reason — an extra
           reason in the map would break this)"))
    (testing "the row states the sole-reason contract"
      (is (str/includes? row "`:reason` is always `:parent-frame-destroyed`")))
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

(defn- read-source-forms
  "Every top-level form of `file`, read with `:read-cond :allow` (the JVM
  `:clj` view) and `*ns*` bound to the file's LOADED namespace so
  auto-resolved (`::`) keywords resolve. Fails CLOSED: a read error
  propagates (reds the gate) rather than silently truncating the scan."
  [file]
  (let [the-ns (or (find-ns (reader-ns-sym file))
                   (create-ns (gensym "rf-destroy-scan")))]
    (with-open [r (PushbackReader. (io/reader file))]
      (binding [*read-eval* false *ns* the-ns]
        (doall
          (take-while #(not= ::eof %)
                      (repeatedly #(read {:read-cond :allow :eof ::eof} r))))))))

(defn- call-name
  "The unqualified name of `form`'s head symbol (ignoring any ns alias), or
  nil when `form` is not a symbol-headed list."
  [form]
  (when (and (seq? form) (symbol? (first form))) (name (first form))))

(defn- reason-key-values
  "Every value bound to a literal `:reason` key in any map literal nested in
  `form` (digs through `cond->` / `assoc`-free map literals)."
  [form]
  (for [sf (tree-seq coll? seq form)
        :when (and (map? sf) (contains? sf :reason))]
    (:reason sf)))

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

(defn- enumerate-emit-sites
  "Structurally walk every destroy-emit-bearing source file; return a vector
  of findings pinning each (channel, reason) choke point."
  [files]
  (vec
    (for [f files
          form (read-source-forms f)
          sf (tree-seq coll? seq form)
          :when (seq? sf)
          finding
          (let [h (call-name sf), v (vec sf)]
            (cond
              (= h "emit!")
              (let [ch (nth v 2 nil)]
                (when (contains? destroy-channels ch)
                  [{:kind :channel-emit :file (.getName f) :form sf
                    :channel ch :reasons (vec (reason-key-values (nth v 3 nil)))}]))

              (= h "emit-destroyed!")
              [{:kind :emit-destroyed :file (.getName f) :form sf
                :reasons (vec (reason-key-values (nth v 1 nil)))}]

              (= h "destroy-resolved!")
              [{:kind :destroy-resolved :file (.getName f) :form sf
                :reason (nth v 3 ::missing)}]

              :else nil))]
      finding)))

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

(deftest emit-sites-pin-exact-channel-reason-tuples
  (let [files    (destroy-emit-files)
        findings (enumerate-emit-sites files)]
    (testing "the structural scan reached the live emit sites"
      (is (seq files) "destroy-emit-bearing source files resolved from the test CWD")
      (is (seq findings) "at least one destroy emit choke point was enumerated"))

    ;; --- per-site (channel, reason) validity: fails CLOSED on any reason that
    ;;     is neither a documented literal for its channel nor forwarding `reason`.
    (doseq [{:keys [kind channel reasons reason] :as fnd} findings]
      (case kind
        :channel-emit
        (cond
          (= channel lifecycle-channel)
          (doseq [r reasons]
            (is (valid-lifecycle-reason? r)
                (str "lifecycle-channel emit must stamp a documented literal "
                     "lifecycle reason " expected-lifecycle-reasons "; saw "
                     (pr-str r) " at " (loc fnd))))
          (= channel fx-channel)
          (doseq [r reasons]
            (is (valid-fx-reason? r)
                (str "fx-channel emit must stamp a documented fx literal "
                     expected-fx-reasons " or forward `reason`; saw "
                     (pr-str r) " at " (loc fnd)))))

        :emit-destroyed
        (doseq [r reasons]                         ; 0 reasons = the :explicit default
          (is (valid-fx-reason? r)
              (str "emit-destroyed! must pass a documented fx literal "
                   expected-fx-reasons " or forward `reason`; saw "
                   (pr-str r) " at " (loc fnd))))

        :destroy-resolved
        (is (and (keyword? reason) (contains? expected-fx-reasons reason))
            (str "destroy-resolved! must be called with a documented fx literal "
                 expected-fx-reasons " (a dynamic reason here fails "
                 "closed); saw " (pr-str reason) " at " (loc fnd)))))

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
;; C. Executable fixtures — drive the runtime, assert the exact emitted tuple
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest executable-explicit-destroy-emits-fx-explicit
  (testing "an explicit teardown (declarative :spawn exit cascade) emits EXACTLY
            (:rf.machine/destroyed, :explicit)"
    (rf/reg-machine :dre/child {:initial :running :data {} :states {:running {}}})
    (rf/reg-machine :dre/parent
                    {:initial :idle
                     :states  {:idle    {:on {:start :working}}
                               :working {:spawn {:machine-id :dre/child}
                                         :on    {:stop :idle}}}})
    (mtest/with-trace-capture captured
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
    (mtest/with-trace-capture captured
      (rf/dispatch-sync [:drf/parent [:rf.machine.spawn/spawned]])
      (let [spawned (get-in (mtest/runtime-db)
                            [:rf.runtime/machines :spawned :drf/parent [:working]])]
        (rf/dispatch-sync [spawned [:end]]))          ; child → :final? → finalize
      (let [fin (filter #(= :rf.machine/finished (-> % :tags :reason)) @captured)]
        (is (seq fin) "a :rf.machine/finished destroy fired")
        (doseq [t fin]
          (is (= [fx-channel :rf.machine/finished] [(:operation t) (-> t :tags :reason)])
              (str "finalize must be the exact tuple [" fx-channel " :rf.machine/finished]; saw "
                   (pr-str [(:operation t) (-> t :tags :reason)]))))))))

(deftest executable-join-reap-emits-fx-join-reaped
  (testing "a LIVE (non-:final?) completed :spawn-all child reaped at join
            resolution emits EXACTLY (:rf.machine/destroyed, :rf.machine/join-reaped)"
    (let [mk-child (fn [parent-id done-kw error-kw]
                     {:initial :running
                      :data    {:id nil}
                      :actions {:record-id     (fn [{d :data ev :event}] {:data (assoc d :id (second ev))})
                                :dispatch-done (fn [{d :data}] {:fx [[:dispatch [parent-id [done-kw (:id d)]]]]})
                                :dispatch-err  (fn [{d :data}] {:fx [[:dispatch [parent-id [error-kw (:id d)]]]]})}
                      :states  {:running {:on {:set-id {:action :record-id}
                                               :go     {:target :done   :action :dispatch-done}
                                               :fail   {:target :failed :action :dispatch-err}}}
                                :done   {}
                                :failed {}}})]
      (rf/reg-machine :drj/a (mk-child :drj/sup :asset/loaded :asset/failed))
      (rf/reg-machine :drj/b (mk-child :drj/sup :asset/loaded :asset/failed))
      (rf/reg-machine :drj/sup
                      {:initial :idle
                       :states  {:idle   {:on {:start :racing}}
                                 :racing {:spawn-all
                                          {:children [{:id :a :machine-id :drj/a :start [:set-id :a]}
                                                      {:id :b :machine-id :drj/b :start [:set-id :b]}]
                                           :join             :any
                                           :on-child-done    :asset/loaded
                                           :on-child-error   :asset/failed
                                           :on-some-complete [:race/won]}}}})
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:drj/sup [:start]])
        (let [a-id (get-in (mtest/runtime-db)
                           [:rf.runtime/machines :spawned :drj/sup [:racing] :children :a])]
          (rf/dispatch-sync [a-id [:go]])              ; :a completes → :any resolves → :a reaped
          (let [reaped (filter #(and (= fx-channel (:operation %))
                                     (= a-id (:actor-id (:tags %))))
                               @captured)]
            (is (seq reaped) "the reaped completed child fired an fx destroy")
            (doseq [t reaped]
              (is (= [fx-channel :rf.machine/join-reaped] [(:operation t) (-> t :tags :reason)])
                  (str "join reap must be the exact tuple [" fx-channel " :rf.machine/join-reaped]; saw "
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
    (mtest/with-trace-capture captured
      (rf/destroy-frame! :drl/auth)
      (let [lc (filter #(= lifecycle-channel (:operation %)) @captured)]
        (is (seq lc) "a lifecycle-channel destroyed fired on frame destroy")
        (doseq [t lc]
          (is (= [lifecycle-channel :parent-frame-destroyed] [(:operation t) (-> t :tags :reason)])
              (str "frame destroy must be the exact tuple [" lifecycle-channel
                   " :parent-frame-destroyed]; saw "
                   (pr-str [(:operation t) (-> t :tags :reason)]))))))))
