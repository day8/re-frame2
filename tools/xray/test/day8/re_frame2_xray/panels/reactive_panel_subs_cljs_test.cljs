(ns day8.re-frame2-xray.panels.reactive-panel-subs-cljs-test
  "Tests for the Reactive panel's pure-data projection
  (rf2-wyvf2 · spec/021 §3).

  Exercises `project-record` over the substrate's structured
  `:rf/epoch-record` projections (`:sub-runs`, `:renders`) plus the
  flow counts tallied from `:trace-events` — pure data, no re-frame
  frame required.

  The canonical op / projection shapes (Spec 009 §:op-type vocabulary +
  spec/018):

  - `:sub-runs` entry → `{:sub-id _ :query-v _ :recomputed? bool}`;
    `:recomputed?` splits subs-ran (true) from memo-hit skips (false).
  - `:renders`  entry → `{:render-key [view-id idx] ...}`.
  - flow ops on `:trace-events` → `:rf.flow/computed` / `:rf.flow/skip`.

  (The earlier suite asserted the *buggy* contract — it grepped raw
  `:trace-events` for `:rf.sub/computed` / `:rf.sub/skipped`, names the
  substrate never emits — so it stayed green while the panel showed
  zero subs ran.)"
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-xray.panels.reactive-panel-subs :as subs]))

;; ---- helpers -----------------------------------------------------------

(defn- sub-run [sub-id recomputed?]
  {:sub-id sub-id :query-v [sub-id] :recomputed? recomputed?})

(defn- render [view-id]
  {:render-key [view-id 0] :triggered-by nil :elapsed-ms nil})

(defn- flow-ev [op] {:operation op})

;; ---- focused-epoch-record ---------------------------------------------

(deftest focused-epoch-record-finds-by-id
  (testing "focused-epoch-record returns the record matching :epoch-id"
    (let [history [{:epoch-id :a} {:epoch-id :b} {:epoch-id :c}]]
      (is (= {:epoch-id :b} (subs/focused-epoch-record history :b))))))

(deftest focused-epoch-record-falls-back-to-head-when-no-match
  (testing "Missing :epoch-id (LIVE) or evicted id → head record"
    (let [history [{:epoch-id :a} {:epoch-id :b} {:epoch-id :c}]]
      (is (= {:epoch-id :c} (subs/focused-epoch-record history nil)))
      (is (= {:epoch-id :c} (subs/focused-epoch-record history :missing))))))

(deftest focused-epoch-record-empty-history-nil
  (testing "Empty history returns nil"
    (is (nil? (subs/focused-epoch-record [] :anything)))
    (is (nil? (subs/focused-epoch-record nil :anything)))))

;; ---- project-record: empty --------------------------------------------

(deftest project-empty-record
  (testing "Empty / nil record projects to zeroed vectors + counts"
    (doseq [r [nil {}]]
      (let [p (subs/project-record r)]
        (is (= [] (:subs-ran p)))
        (is (= [] (:subs-skipped p)))
        (is (= [] (:views-rendered p)))
        (is (= 0 (-> p :counts :subs-ran)))
        (is (= 0 (-> p :counts :subs-skipped)))
        (is (= 0 (-> p :counts :views-rendered)))
        (is (= 0 (-> p :counts :flows-recomputed)))
        (is (= 0 (-> p :counts :flows-skipped)))))))

;; ---- project-record: subs ran (:recomputed? true) ---------------------

(deftest project-subs-ran
  (testing ":sub-runs entries with :recomputed? true become :subs-ran rows"
    (let [record {:sub-runs [(sub-run :cart/state true)
                             (sub-run :cart/items true)
                             (sub-run :cart/total true)]}
          p (subs/project-record record)]
      (is (= 3 (count (:subs-ran p))))
      (is (= :cart/state (-> p :subs-ran first :sub-id)))
      (is (= 3 (-> p :counts :subs-ran)))
      (is (= 0 (-> p :counts :subs-skipped))))))

;; ---- project-record: subs skipped (memo hits) -------------------------

(deftest project-subs-skipped
  (testing ":sub-runs entries with :recomputed? false become :subs-skipped rows (§3.4)"
    (let [record {:sub-runs [(sub-run :user/name false)
                             (sub-run :cart/eligibility false)]}
          p (subs/project-record record)]
      (is (= 2 (count (:subs-skipped p))))
      (is (= :user/name (-> p :subs-skipped first :sub-id)))
      (is (= 2 (-> p :counts :subs-skipped)))
      (is (= 0 (-> p :counts :subs-ran))))))

;; ---- project-record: split a mixed :sub-runs vector -------------------

(deftest project-subs-split-by-recomputed
  (testing "A mixed :sub-runs vector splits ran vs skipped, order preserved"
    (let [record {:sub-runs [(sub-run :first true)
                             (sub-run :second false)
                             (sub-run :third true)]}
          p (subs/project-record record)]
      (is (= [:first :third] (mapv :sub-id (:subs-ran p))))
      (is (= [:second]       (mapv :sub-id (:subs-skipped p)))))))

;; ---- project-record: views rendered -----------------------------------

(deftest project-views-rendered
  (testing ":renders entries lift :view-id from :render-key, preserve :render-key"
    (let [record {:renders [(render :checkout/CheckoutButton)
                           (render :cart/Summary)]}
          p (subs/project-record record)]
      (is (= 2 (count (:views-rendered p))))
      (is (= [:checkout/CheckoutButton :cart/Summary]
             (mapv :view-id (:views-rendered p))))
      (is (= [:checkout/CheckoutButton 0] (-> p :views-rendered first :render-key)))
      (is (= 2 (-> p :counts :views-rendered))))))

;; ---- project-record: flow counts from :trace-events -------------------

(deftest project-flow-counts-from-trace
  (testing "Flow counts tally :rf.flow/computed and :rf.flow/skip (canonical names)"
    (let [record {:trace-events [(flow-ev :rf.flow/computed)
                                (flow-ev :rf.flow/computed)
                                (flow-ev :rf.flow/skip)]}
          p (subs/project-record record)]
      (is (= 2 (-> p :counts :flows-recomputed)))
      (is (= 1 (-> p :counts :flows-skipped))))))

;; ---- project-record: full record composes -----------------------------

(deftest project-full-record
  (testing "All projections compose from one record"
    (let [record {:sub-runs     [(sub-run :a true) (sub-run :b false) (sub-run :c true)]
                  :renders      [(render :v-x) (render :v-y)]
                  :trace-events [(flow-ev :rf.flow/computed)]}
          p (subs/project-record record)]
      (is (= 2 (-> p :counts :subs-ran)))
      (is (= 1 (-> p :counts :subs-skipped)))
      (is (= 2 (-> p :counts :views-rendered)))
      (is (= 1 (-> p :counts :flows-recomputed)))
      (is (= [:v-x :v-y] (mapv :view-id (:views-rendered p)))))))

;; ===========================================================================
;; phase-B Views redesign — three-table data layer (rf2-8ve8z)
;; ===========================================================================

;; ---- helpers -----------------------------------------------------------

(defn- sub-run+ [sub-id recomputed? changed?]
  {:sub-id sub-id :query-v [sub-id]
   :recomputed? recomputed? :value-changed? changed?})

(defn- rendered-ev
  "A captured `:rf.view/rendered` trace event — tags carry the phase-A
  rf2-9hoos fields."
  [view-id mount? deref-subs]
  {:operation :rf.view/rendered
   :tags (cond-> {:rf.view/id view-id :rf.view/render-key [view-id 0] :rf.view/mount? mount?}
           (some? deref-subs) (assoc :rf.view/deref-subs deref-subs))})

(defn- unmounted-ev [view-id]
  {:operation :rf.view/unmounted
   :tags {:rf.view/id view-id :rf.view/render-key [view-id 0]}})

;; ---- changed-vs-structural classifier ---------------------------------

(deftest compute-view-reason-reactive-when-own-sub-changed
  (testing "rf2-8ve8z — a view that derefs a sub that changed this cascade
            gets a :reactive reason listing the INTERSECTION (its own
            changed reads), in deref order."
    (let [reason (subs/compute-view-reason [[:cart/total] [:cart/count]]
                                           #{:cart/total})]
      (is (= :reactive (:kind reason)))
      (is (= [:cart/total] (:subs reason))
          "only the changed sub the view reads lands in the reason"))))

(deftest compute-view-reason-structural-when-no-own-sub-changed
  (testing "rf2-8ve8z — a view that derefs subs but NONE changed → the
            structural (`← parent re-render`) reason, UNNAMED."
    (let [reason (subs/compute-view-reason [[:cart/total]] #{:other/sub})]
      (is (= :structural (:kind reason)))
      (is (nil? (:subs reason))))))

(deftest compute-view-reason-structural-when-no-derefs
  (testing "rf2-8ve8z — a pure structural render (no :deref-subs) → the
            structural reason. nil-safe on the deref-subs arg."
    (is (= :structural (:kind (subs/compute-view-reason nil #{:cart/total}))))
    (is (= :structural (:kind (subs/compute-view-reason [] #{:cart/total}))))))

(deftest compute-view-reason-preserves-deref-order-and-dedupes
  (testing "rf2-8ve8z — reason :subs follow deref order and de-duplicate."
    (let [reason (subs/compute-view-reason
                   [[:b] [:a] [:a] [:c]] #{:a :b :c})]
      (is (= [:b :a :c] (:subs reason))))))

;; ---- view-rows projection ---------------------------------------------

(deftest view-rows-maps-mount-rerender-unmount
  (testing "rf2-8ve8z — :mount? true → :mount, false → :rerender, the
            :rf.view/unmounted op → :unmount."
    (let [events [(rendered-ev :v/a true  [[:s1]])
                  (rendered-ev :v/b false [[:s1]])
                  (unmounted-ev :v/c)]
          rows   (subs/view-rows events #{:s1})]
      (is (= [:mount :rerender :unmount] (mapv :action rows)))
      (is (= [:v/a :v/b :v/c] (mapv :view-id rows))))))

(deftest view-rows-reactive-vs-structural-reason
  (testing "rf2-8ve8z — a render whose deref'd sub changed → :reactive
            reason; a render whose deref'd sub did NOT change → structural."
    (let [events [(rendered-ev :v/reactive   false [[:changed]])
                  (rendered-ev :v/structural false [[:unchanged]])
                  (rendered-ev :v/no-derefs  false nil)]
          rows   (subs/view-rows events #{:changed})
          by-id  (into {} (map (juxt :view-id identity)) rows)]
      (is (= :reactive   (-> by-id :v/reactive :reason :kind)))
      (is (= [:changed]  (-> by-id :v/reactive :reason :subs)))
      (is (= :structural (-> by-id :v/structural :reason :kind)))
      (is (= :structural (-> by-id :v/no-derefs :reason :kind))))))

(deftest view-rows-unmount-reason-is-none
  (testing "rf2-8ve8z — an unmount row carries no reason (:none)."
    (let [rows (subs/view-rows [(unmounted-ev :v/gone)] #{})]
      (is (= :none (-> rows first :reason :kind))))))

(deftest view-rows-skips-events-without-view-id-and-non-view-ops
  (testing "rf2-8ve8z — nil-safe: events without a :view-id and non-view
            ops are skipped, never crash."
    (let [events [{:operation :rf.view/rendered :tags {:rf.view/mount? true}} ; no view-id
                  {:operation :rf.sub/run :tags {:rf.sub/id :s1}}           ; not a view op
                  (rendered-ev :v/ok true nil)]
          rows   (subs/view-rows events #{})]
      (is (= 1 (count rows)))
      (is (= :v/ok (-> rows first :view-id))))))

(deftest view-rows-carries-cause-and-timing
  (testing "rf2-ad7zx.6 / rf2-8wrzz.1 — a :rf.view/rendered op carrying
            :rf.view/triggered-by + :rf.view/elapsed-ms threads those
            through onto the view row for the flow graph's cause + timing."
    (let [ev   {:operation :rf.view/rendered
                :tags {:rf.view/id :v/a :rf.view/render-key [:v/a 0]
                       :rf.view/mount? false
                       :rf.view/deref-subs [[:s1]]
                       :rf.view/triggered-by :s1
                       :rf.view/elapsed-ms 2.4}}
          row  (first (subs/view-rows [ev] #{:s1}))]
      (is (= :s1 (:triggered-by row)) "the cause sub rides the row")
      (is (= 2.4 (:elapsed-ms row)) "the render timing rides the row"))))

(deftest view-rows-omits-cause-and-timing-when-absent
  (testing "rf2-ad7zx.6 — a structural render with no cause / timing slots
            simply omits :triggered-by + :elapsed-ms (no nil keys)."
    (let [row (first (subs/view-rows [(rendered-ev :v/s false [[:unchanged]])] #{}))]
      (is (not (contains? row :triggered-by)))
      (is (not (contains? row :elapsed-ms))))))

;; ---- teardown sections (rf2-ad7zx.6) ----------------------------------

(deftest unmounted-views-lists-unmount-ops
  (testing "rf2-ad7zx.6 — UNMOUNTED VIEWS reads :rf.view/unmounted ops,
            de-duplicated, first-seen order."
    (let [events [(unmounted-ev :v/modal)
                  (unmounted-ev :v/tooltip)
                  (unmounted-ev :v/modal)]] ; dup → once
      (is (= [{:view-id :v/modal} {:view-id :v/tooltip}]
             (subs/unmounted-views events))))))

(deftest unmounted-views-nil-safe-and-skips-non-unmount-ops
  (is (= [] (subs/unmounted-views nil)))
  (is (= [] (subs/unmounted-views [(rendered-ev :v/a true nil)]))))

(deftest destroyed-subscriptions-reads-dispose-op-when-present
  (testing "rf2-ad7zx.6 / rf2-uo4e2 — DESTROYED SUBSCRIPTIONS reads
            :rf.sub/dispose ops (singular form per spec/023's
            rf2-2v3p7 typo fix; pre-rf2-uo4e2 the fixture used the
            past-tense form which never matched any framework-emitted
            trace)."
    (is (= [] (subs/destroyed-subscriptions [(rendered-ev :v/a true nil)]))
        "no dispose op → empty (live-build reality)")
    (let [events [{:operation :rf.sub/dispose :tags {:rf.sub/id :s/modal}}
                  {:operation :rf.sub/dispose :tags {:sub-id :s/tip}}]]
      (is (= [{:sub-id :s/modal} {:sub-id :s/tip}]
             (subs/destroyed-subscriptions events))
          "reads the framework-emitted :rf.sub/dispose op"))))

;; ---- Level 1 / Level 2+ partition -------------------------------------

(def ^:private topology
  "A static sub-topology snapshot: :inputs [] = Level 1; non-empty =
  Level 2+. Carries :ns/:line/:file so the code coord resolves."
  {:cart/state {:inputs [] :ns 'cart :line 10 :file "cart.cljs"}
   :cart/items {:inputs [] :ns 'cart :line 14 :file "cart.cljs"}
   :cart/total {:inputs [:cart/state :cart/items]
                :ns 'cart :line 22 :file "cart.cljs"}})

(deftest partition-splits-by-inputs-empty
  (testing "rf2-8ve8z — :inputs [] subs land in Level 1; non-empty land
            in Level 2+, carrying their input-sub names + coord."
    (let [{:keys [level-1 level-2]}
          (subs/partition-subs-by-level
            [(sub-run+ :cart/state true true)
             (sub-run+ :cart/total true true)]
            topology)]
      (is (= [:cart/state] (mapv :sub-id level-1)))
      (is (= [:cart/total] (mapv :sub-id level-2)))
      (is (= [:cart/state :cart/items] (-> level-2 first :inputs))
          "Level 2+ row carries its declared input-sub names")
      (is (= "cart.cljs" (-> level-1 first :coord :file))
          "Level 1 row carries the topology source coord"))))

(deftest partition-carries-changed-flag
  (testing "rf2-8ve8z — :value-changed? rides onto each row's :changed?."
    (let [{:keys [level-1]}
          (subs/partition-subs-by-level
            [(sub-run+ :cart/state true true)
             (sub-run+ :cart/items true false)]
            topology)]
      (is (= [true false] (mapv :changed? level-1))))))

(deftest partition-missing-from-topology-defaults-level-1
  (testing "rf2-8ve8z — nil-safe: a sub absent from the topology defaults
            to Level 1 with no inputs / no coord (degrade, never crash)."
    (let [{:keys [level-1 level-2]}
          (subs/partition-subs-by-level
            [(sub-run+ :mystery/sub true false)] nil)]
      (is (= [:mystery/sub] (mapv :sub-id level-1)))
      (is (empty? level-2))
      (is (nil? (-> level-1 first :coord))
          "no coord when topology absent"))))

;; ---- shared-subscription edges (rf2-y23uw) ----------------------------

(deftest sub-readers-maps-each-sub-to-views-that-read-it
  (testing "rf2-y23uw — sub-readers builds {sub-id [view-id ...]}: every
            view that derefs a sub this cascade lands in that sub's reader
            list (the shared-sub edge)."
    (let [events [(rendered-ev :v/a false [[:s/x] [:s/y]])
                  (rendered-ev :v/b false [[:s/x]])
                  (rendered-ev :v/c false [[:s/z]])]
          readers (subs/sub-readers events)]
      (is (= [:v/a :v/b] (:s/x readers))
          ":s/x is read by both v/a and v/b (shared sub)")
      (is (= [:v/a] (:s/y readers)))
      (is (= [:v/c] (:s/z readers))))))

(deftest sub-readers-preserves-first-seen-view-order-and-dedupes
  (testing "rf2-y23uw — a view that re-derefs the same sub, or two ops for
            the same view-id, contribute the view-id once; reader order is
            first-seen across the trace."
    (let [events [(rendered-ev :v/b false [[:s/x] [:s/x]]) ; re-deref same sub
                  (rendered-ev :v/a false [[:s/x]])
                  (rendered-ev :v/b false [[:s/x]])]        ; same view again
          readers (subs/sub-readers events)]
      (is (= [:v/b :v/a] (:s/x readers))
          "first-seen order, de-duplicated"))))

(deftest sub-readers-nil-safe-and-skips-non-view-ops
  (testing "rf2-y23uw — nil-safe; non-view ops, ops without :view-id, and
            structural renders (no :deref-subs) contribute no edges."
    (is (= {} (subs/sub-readers nil)))
    (is (= {} (subs/sub-readers [])))
    (let [events [{:operation :rf.sub/run :tags {:rf.sub/id :s/x}}
                  (rendered-ev :v/structural false nil)
                  (rendered-ev :v/ok false [[:s/x]])]
          readers (subs/sub-readers events)]
      (is (= {:s/x [:v/ok]} readers)
          "only the rendered view that derefs a sub contributes an edge"))))

(deftest partition-attaches-readers-to-sub-rows
  (testing "rf2-y23uw — partition-subs-by-level attaches each sub's
            :readers (which views read it) onto the L1 / L2 row; absent
            when no view read the sub."
    (let [readers {:cart/state [:cart/Header :cart/Summary]
                   :cart/total [:cart/Summary]}
          {:keys [level-1 level-2]}
          (subs/partition-subs-by-level
            [(sub-run+ :cart/state true true)
             (sub-run+ :cart/items true false) ; no reader → :readers absent
             (sub-run+ :cart/total true true)]
            topology readers)]
      (is (= [:cart/Header :cart/Summary] (-> level-1 first :readers))
          ":cart/state's readers ride its L1 row")
      (is (not (contains? (second level-1) :readers))
          ":cart/items has no reader → :readers slot omitted")
      (is (= [:cart/Summary] (-> level-2 first :readers))
          ":cart/total's reader rides its L2 row"))))

;; ---- project-record composes the phase-B slots ------------------------

(deftest project-record-emits-level-and-view-slots
  (testing "rf2-8ve8z — project-record composes :level-1-subs /
            :level-2-subs (from topology) + :view-rows (from
            :trace-events view ops) alongside the legacy slots."
    (let [record {:sub-runs [(sub-run+ :cart/state true true)
                             (sub-run+ :cart/total true true)]
                  :trace-events [(rendered-ev :cart/Summary false [[:cart/total]])
                                 (unmounted-ev :cart/Gone)]}
          p (subs/project-record record topology)]
      (is (= [:cart/state] (mapv :sub-id (:level-1-subs p))))
      (is (= [:cart/total] (mapv :sub-id (:level-2-subs p))))
      (is (= 2 (count (:view-rows p))))
      (is (= :reactive (-> p :view-rows first :reason :kind))
          ":cart/Summary derefs :cart/total which changed → reactive")
      (is (= [:cart/total] (-> p :view-rows first :reason :subs)))
      (is (= :unmount (-> p :view-rows second :action)))
      (is (= 2 (-> p :counts :view-rows)))
      ;; rf2-y23uw — shared-sub edges thread through project-record.
      (is (= {:cart/total [:cart/Summary]} (:sub-readers p))
          ":sub-readers maps :cart/total → the views that read it")
      (is (= [:cart/Summary] (-> p :level-2-subs first :readers))
          ":cart/total's L2 row carries its :readers")
      ;; rf2-ad7zx.6 — teardown sections compose through project-record.
      (is (= [{:view-id :cart/Gone}] (:unmounted-views p))
          ":unmounted-views projects the unmount op")
      (is (= [] (:destroyed-subs p))
          ":destroyed-subs empty (no dispose op in the live build)")
      (is (= 1 (-> p :counts :unmounted-views)))
      (is (= 0 (-> p :counts :destroyed-subs))))))

(deftest project-record-degrades-without-topology
  (testing "rf2-8ve8z — nil topology: every sub falls to Level 1; the
            panel still renders (no crash)."
    (let [record {:sub-runs [(sub-run+ :a true true) (sub-run+ :b true false)]}
          p (subs/project-record record)]
      (is (= [:a :b] (mapv :sub-id (:level-1-subs p))))
      (is (empty? (:level-2-subs p))))))

;; ===========================================================================
;; SUB VALUES inspector (rf2-e46qs phase 3 of rf2-oqa60)
;; ===========================================================================
;;
;; The SUB VALUES section beneath the flow graph renders each RUN sub's
;; current cascade value through the first-class edn-inspector widget
;; (`views.edn-inspector`). The projection layer here surfaces a
;; `:sub-values` slot — one row per RUN sub, carrying the sub-id, the
;; changed/unchanged flag, an explicit `:has-value?` presence flag (so a
;; redacted/absent value is distinguishable from an actual `nil`), the
;; `:value` itself (when present), the source `:coord` (for the
;; jump-to-source affordance), and a CSS-safe `:slug` for the testid.
;;
;; Skipped subs (memo hits — `:recomputed?` false) are deliberately
;; omitted: they carry no fresh value this cascade.

(defn- sub-run-with-value
  "Sub-run entry mirroring the substrate's `:sub-runs` projection: carries
  `:recomputed?` + `:value-changed?` + an explicit `:value`. Use this
  helper for the SUB VALUES inspector tests where the per-sub value
  drives the inspector mount."
  [sub-id recomputed? changed? value]
  {:sub-id sub-id :query-v [sub-id]
   :recomputed? recomputed? :value-changed? changed?
   :value value})

(deftest sub-values-projects-one-row-per-ran-sub
  (testing "rf2-e46qs — one row per RUN sub; each carries the sub-id,
            changed?, has-value? presence, the value, and a CSS-safe
            testid slug."
    (let [rows (subs/sub-values
                 [(sub-run-with-value :cart/state true true {:items [1 2]})
                  (sub-run-with-value :cart/total true false 0)])]
      (is (= 2 (count rows)))
      (is (= :cart/state (-> rows first :sub-id)))
      (is (true? (-> rows first :changed?)))
      (is (true? (-> rows first :has-value?)))
      (is (= {:items [1 2]} (-> rows first :value)))
      (is (string? (-> rows first :slug)))
      (is (= "_cart_state" (-> rows first :slug))
          "slug folds keyword punctuation to underscore for the testid"))))

(deftest sub-values-skipped-subs-are-omitted
  (testing "rf2-e46qs — `:recomputed?` false subs carry no fresh value
            this cascade; the inspector projection excludes them."
    (let [rows (subs/sub-values
                 [(sub-run-with-value :cart/state true true {:a 1})
                  (sub-run-with-value :cart/skipped false false :stale)])]
      (is (= [:cart/state] (mapv :sub-id rows))
          "the skipped (memo-hit) sub is omitted from the inspector"))))

(deftest sub-values-honours-has-value-presence-distinct-from-nil
  (testing "rf2-e46qs — a sub-run without a `:value` key (redacted /
            pre-attribution) is `:has-value? false`; a sub-run with
            `:value nil` is `:has-value? true` with `:value nil`. The
            view layer renders the no-value placeholder for the former
            and mounts the widget with `nil` for the latter."
    (let [rows (subs/sub-values
                 [{:sub-id :no/value :recomputed? true :value-changed? true}
                  {:sub-id :explicit/nil :recomputed? true :value-changed? true
                   :value nil}])]
      (is (= 2 (count rows)))
      (is (false? (-> rows first :has-value?))
          "no `:value` key → `:has-value? false`")
      (is (not (contains? (first rows) :value))
          "no `:value` key → `:value` slot omitted from the row")
      (is (true? (-> rows second :has-value?))
          "explicit `:value nil` → `:has-value? true`")
      (is (contains? (second rows) :value)
          "explicit `:value nil` → row carries the `:value` slot")
      (is (nil? (-> rows second :value))))))

(deftest sub-values-carries-source-coord-from-topology
  (testing "rf2-e46qs — the `:coord` slot rides from the static topology
            snapshot so the inspector row can offer a jump-to-source
            affordance. Absent topology → no `:coord` slot (degrade)."
    (let [rows (subs/sub-values
                 [(sub-run-with-value :cart/state true true {:a 1})]
                 topology)]
      (is (= "cart.cljs" (-> rows first :coord :file))))
    (let [rows (subs/sub-values
                 [(sub-run-with-value :cart/state true true {:a 1})]
                 nil)]
      (is (not (contains? (first rows) :coord))
          "no topology → no `:coord` slot"))))

(deftest sub-values-nil-safe
  (testing "rf2-e46qs — nil / empty inputs project to an empty row vector."
    (is (= [] (subs/sub-values nil)))
    (is (= [] (subs/sub-values [])))
    (is (= [] (subs/sub-values nil nil)))))

(deftest project-record-emits-sub-values-slot
  (testing "rf2-e46qs — project-record threads :sub-values into the
            composite shape + its count rides the :counts map."
    (let [record {:sub-runs [(sub-run-with-value :cart/state true true {:a 1})
                             (sub-run-with-value :cart/total true false 0)
                             (sub-run-with-value :cart/skip false false :stale)]}
          p (subs/project-record record topology)]
      (is (= [:cart/state :cart/total] (mapv :sub-id (:sub-values p)))
          "the inspector projection rides on :sub-values")
      (is (= 2 (-> p :counts :sub-values))
          ":counts gets a :sub-values tally")
      (is (true? (-> p :sub-values first :has-value?)))
      (is (= {:a 1} (-> p :sub-values first :value))))))

(deftest partition-subs-by-level-threads-value-onto-rows
  (testing "rf2-e46qs — :value rides through partition-subs-by-level onto
            both Level 1 and Level 2+ rows so the flow-graph layer has
            access to the inspector data (e.g. for a future click-into-
            node drill-down)."
    (let [{:keys [level-1 level-2]}
          (subs/partition-subs-by-level
            [(sub-run-with-value :cart/state true true {:items [1 2]})
             (sub-run-with-value :cart/total true true 42)]
            topology)]
      (is (= {:items [1 2]} (-> level-1 first :value)))
      (is (true? (-> level-1 first :has-value?)))
      (is (= 42 (-> level-2 first :value)))
      (is (true? (-> level-2 first :has-value?))))))
