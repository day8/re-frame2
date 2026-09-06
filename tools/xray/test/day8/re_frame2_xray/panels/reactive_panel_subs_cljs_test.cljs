(ns day8.re-frame2-xray.panels.reactive-panel-subs-cljs-test
  "Tests for the Reactive panel's pure-data projection
  (rf2-wyvf2 · spec/021 §3).

  Exercises `project-record` over the substrate's structured
  `:rf/epoch-record` projections (`:sub-runs`, `:renders`) plus the
  flow counts tallied from `:trace-events` — pure data, no re-frame
  frame required.

  The canonical op / projection shapes (Spec 009 §:op-type vocabulary +
  spec/018):

  - `:sub-runs` entry → `{:sub-id _ :query-v _ :recomputed? true}`; the
    substrate's `sub-run-row` hardcodes `:recomputed? true`, so `:subs-ran`
    IS the run-set.
  - `:rf.sub/skip` op on `:trace-events` → the memo-hit evidence
    (`re-frame.subs.memo/emit-sub-skip!`), projected to the distinct
    `:subs-skipped` slice (spec/021 §3.4) — a sub reactively considered
    whose input was value-equal, so it did NOT recompute.
  - `:renders`  entry → `{:render-key [view-id idx] ...}`.
  - flow ops on `:trace-events` → `:rf.flow/computed` / `:rf.flow/skip`.

  (The earlier suite asserted the *buggy* contract — it grepped raw
  `:trace-events` for `:rf.sub/computed` / `:rf.sub/skipped`, names the
  substrate never emits — so it stayed green while the panel showed
  zero subs ran.)"
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-xray.panels.reactive-panel-subs :as subs]))

;; ---- helpers -----------------------------------------------------------

(defn- sub-run
  "A `:sub-runs` projection row as the substrate actually emits it —
  `sub-run-row` (capture.cljc) hardcodes `:recomputed? true`, and a
  `:rf.sub/skip` memo-hit projects no row at all, so there is no
  `:recomputed? false` shape to fabricate here."
  [sub-id]
  {:sub-id sub-id :query-v [sub-id] :recomputed? true})

(defn- render [view-id]
  {:render-key [view-id 0] :triggered-by nil :elapsed-ms nil})

(defn- flow-ev [op] {:operation op})

(defn- skip-ev
  "A `:rf.sub/skip` memo-hit trace event as the substrate emits it
  (`re-frame.subs.memo/emit-sub-skip!`) — rides `:trace-events`, NOT
  `:sub-runs`. The query-v is the bare unparameterized shape `[sub-id]`."
  ([sub-id] (skip-ev sub-id []))
  ([sub-id input-paths-unchanged]
   {:operation :rf.sub/skip
    :tags      {:rf.sub/id                    sub-id
                :rf.sub/query-v               [sub-id]
                :rf.sub/reason                :input-value-equal
                :rf.sub/input-paths-unchanged input-paths-unchanged}}))

(defn- skip-qv
  "A `:rf.sub/skip` op for a CONCRETE parameterized query (rf2-cj2yx) —
  the registered `sub-id` plus the EXACT `query-v` the cache/trace
  short-circuited (e.g. `[:item/derived 1]`), the two carried separately
  so the projection can dedup/exclude by the concrete query while keeping
  the id for source-coordinate lookup."
  [sub-id query-v]
  {:operation :rf.sub/skip
   :tags      {:rf.sub/id                    sub-id
               :rf.sub/query-v               query-v
               :rf.sub/reason                :input-value-equal
               :rf.sub/input-paths-unchanged []}})

;; ---- focused-epoch-record ---------------------------------------------

(deftest focused-epoch-record-finds-by-id
  (testing "focused-epoch-record returns the record matching :epoch-id"
    (let [history [{:epoch-id :a} {:epoch-id :b} {:epoch-id :c}]]
      (is (= {:epoch-id :b} (subs/focused-epoch-record history :b))))))

(deftest focused-epoch-record-nil-focus-falls-back-to-head
  (testing "NIL :epoch-id (LIVE / cold-start) → head record (rf2-h0120
            head-fallback — the natural debugging UX)"
    (let [history [{:epoch-id :a} {:epoch-id :b} {:epoch-id :c}]]
      (is (= {:epoch-id :c} (subs/focused-epoch-record history nil))))))

(deftest focused-epoch-record-nil-when-evicted
  (testing "rf2-uo0rc.1 — a PINNED :epoch-id no longer in the buffer
            (evicted from the per-frame ring) resolves to nil, NOT a
            silent head-fallback. Per spec/021 §10.7 every panel renders
            the evicted placeholder; the Views panel must not show the
            LATEST cascade while the operator believes they are inspecting
            the pinned (evicted) epoch. Routes through the shared
            focus-resolver/find-epoch-record, matching Issues / Trace /
            Epoch / App-DB."
    (let [history [{:epoch-id :a} {:epoch-id :b} {:epoch-id :c}]]
      (is (nil? (subs/focused-epoch-record history :missing))
          "evicted pinned epoch must be nil (not the head record)"))))

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
        (is (= [] (:views-rendered p)))
        (is (= 0 (-> p :counts :subs-ran)))
        (is (= 0 (-> p :counts :views-rendered)))
        (is (= 0 (-> p :counts :flows-recomputed)))
        (is (= 0 (-> p :counts :flows-skipped)))))))

;; ---- project-record: subs ran (:recomputed? true) ---------------------

(deftest project-subs-ran
  (testing "every :sub-runs entry (:recomputed? true — the only shape the
            substrate emits) becomes a :subs-ran row; :subs-ran IS the
            whole run-set"
    (let [record {:sub-runs [(sub-run :cart/state)
                             (sub-run :cart/items)
                             (sub-run :cart/total)]}
          p (subs/project-record record)]
      (is (= 3 (count (:subs-ran p))))
      (is (= [:cart/state :cart/items :cart/total]
             (mapv :sub-id (:subs-ran p)))
          "order preserved from :sub-runs")
      (is (= 3 (-> p :counts :subs-ran))))))

;; ---- project-record: subs skipped (memo-hit :rf.sub/skip) -------------
;;
;; The canonical `:rf.sub/skip` evidence (rf2-ty5r5o) — the substrate
;; emits it on `:trace-events` (NOT `:sub-runs`) on a memo hit; the
;; projection reads it into the distinct `:subs-skipped` slice. This
;; replaces the deleted fabricated `:recomputed? false` sub-run tests
;; (a shape the substrate never emits).

(deftest skipped-subs-reads-rf-sub-skip-ops
  (testing "skipped-subs projects each :rf.sub/skip op off :trace-events —
            de-duplicated by sub-id — carrying the memo-hit tags."
    (let [events [(skip-ev :user/name)
                  (skip-ev :cart/eligibility [[:cart/state]])
                  (skip-ev :user/name)] ; dup → once
          rows   (subs/skipped-subs events #{})]
      (is (= [:user/name :cart/eligibility] (mapv :sub-id rows))
          "distinct memo-hit subs, first-seen order")
      (is (= :input-value-equal (-> rows first :reason)))
      (is (= [] (-> rows first :input-paths-unchanged))
          "layer-1 skip carries [] input-paths-unchanged")
      (is (= [[:cart/state]] (-> rows second :input-paths-unchanged))
          "layer-2 skip names its upstream query-vectors"))))

(deftest skipped-subs-excludes-subs-that-also-ran
  (testing "rf2-ty5r5o / rf2-cj2yx — a sub that RECOMPUTED this epoch DID
            fire, so it is a :subs-ran row and MUST NOT also appear in
            :subs-skipped (the two categories stay distinct); a pure memo-hit
            survives. Exclusion keys on the CONCRETE query-v run-set."
    (let [events [(skip-ev :read-a)     ; also ran (below) → excluded
                  (skip-ev :derived-a)] ; pure skip → kept
          ;; ran-set is now concrete query-vs (rf2-cj2yx), not bare sub-ids.
          rows   (subs/skipped-subs events #{[:read-a]})]
      (is (= [:derived-a] (mapv :sub-id rows))
          ":read-a is excluded (it ran); :derived-a survives"))))

;; ---- concrete-query identity (rf2-cj2yx) -------------------------------
;;
;; The cache/trace identify a short-circuited reaction by its full query
;; vector, so the disclosure must dedup + cross-exclude by concrete query-v
;; — NOT the registered sub-id, which collapses distinct parameterizations.

(deftest skipped-subs-preserves-distinct-parameterizations
  (testing "rf2-cj2yx — two memo-hit skips sharing a registered sub-id but
            with DISTINCT concrete query-vs BOTH survive (dedup is by
            concrete query-v, not the registered id)."
    (let [events [(skip-qv :item/derived [:item/derived 1])
                  (skip-qv :item/derived [:item/derived 2])]
          rows   (subs/skipped-subs events #{})]
      (is (= 2 (count rows)) "both parameterizations survive")
      (is (= [[:item/derived 1] [:item/derived 2]] (mapv :query-v rows))
          "each row carries its own concrete query-v")
      (is (= [:item/derived :item/derived] (mapv :sub-id rows))
          "the registered id rides both rows for source-coord lookup"))))

(deftest skipped-subs-dedups-repeated-same-concrete-query
  (testing "rf2-cj2yx — repeated evidence for the SAME concrete query (a
            post-settle deref burst) collapses to one row."
    (let [events [(skip-qv :item/derived [:item/derived 2])
                  (skip-qv :item/derived [:item/derived 2])]
          rows   (subs/skipped-subs events #{})]
      (is (= [[:item/derived 2]] (mapv :query-v rows))
          "the same concrete query collapses to a single row"))))

(deftest skipped-subs-recompute-excludes-only-exact-query
  (testing "rf2-cj2yx — the focused counterexample: `[:item/derived 1]`
            recomputes while `[:item/derived 2]` memo-hits. The recompute
            excludes ONLY its exact query; the different same-id memo-hit is
            preserved + disambiguated, not suppressed."
    (let [events       [(skip-qv :item/derived [:item/derived 1])  ; ran below → excluded
                        (skip-qv :item/derived [:item/derived 2])] ; memo-hit → kept
          ran-query-vs #{[:item/derived 1]}
          rows         (subs/skipped-subs events ran-query-vs)]
      (is (= [[:item/derived 2]] (mapv :query-v rows))
          "only the exact recomputed query is excluded; the sibling survives"))))

(deftest skipped-subs-unparameterized-behavior-unchanged
  (testing "rf2-cj2yx — an ordinary unparameterized skip (query-v `[sub-id]`)
            still projects one row and still excludes when that exact query
            recomputed: the common-case behavior is unchanged."
    (is (= [:user/name]
           (mapv :sub-id (subs/skipped-subs [(skip-ev :user/name)] #{})))
        "a lone unparameterized skip survives")
    (is (= []
           (subs/skipped-subs [(skip-ev :user/name)] #{[:user/name]}))
        "the `[sub-id]` run-set excludes the matching `[sub-id]` skip")))

(deftest skipped-subs-falls-back-to-sub-id-when-query-v-absent
  (testing "rf2-cj2yx — documented fallback: a skip op lacking a query-v
            uses the registered `[sub-id]` shape as its identity, so it
            still projects a row and still excludes against a `[sub-id]` run."
    (let [ev {:operation :rf.sub/skip
              :tags {:rf.sub/id :legacy/sub :rf.sub/reason :input-value-equal}}]
      (is (= [{:sub-id :legacy/sub :query-v [:legacy/sub]}]
             (mapv #(select-keys % [:sub-id :query-v])
                   (subs/skipped-subs [ev] #{})))
          "query-v falls back to the bare [sub-id] shape")
      (is (= [] (subs/skipped-subs [ev] #{[:legacy/sub]}))
          "the fallback identity still cross-excludes against a [sub-id] run"))))

(deftest skipped-subs-nil-safe
  (is (= [] (subs/skipped-subs nil nil)))
  (is (= [] (subs/skipped-subs [] #{}))
      "no skip ops → empty")
  (is (= [] (subs/skipped-subs [(flow-ev :rf.flow/skip)] #{}))
      "a :rf.flow/skip is NOT a sub skip"))

(deftest project-record-surfaces-subs-skipped-distinct-from-ran
  (testing "rf2-ty5r5o — project-record reads the memo-hit :rf.sub/skip
            evidence into :subs-skipped, kept DISTINCT from :subs-ran even
            when a :subs-ran row carries :value-changed? false (a recompute
            that produced the same value is NOT a memo-hit skip)."
    (let [record {:sub-runs     [{:sub-id :read-a :query-v [:read-a]
                                  :recomputed? true :value-changed? false}] ; RAN, value unchanged
                  :trace-events [(skip-ev :read-a)              ; also skipped → excluded
                                 (skip-ev :derived-a [[:read-a]])]}
          p      (subs/project-record record)]
      ;; :read-a ran with :value-changed? false → a subs-ran row.
      (is (= [:read-a] (mapv :sub-id (:subs-ran p))))
      (is (= [false] (mapv :value-changed? (:subs-ran p)))
          "a value-changed? false recompute stays in subs-ran (NOT skipped)")
      ;; only the pure memo-hit lands in :subs-skipped.
      (is (= [:derived-a] (mapv :sub-id (:subs-skipped p)))
          ":subs-skipped names only the sub that skipped without running")
      (is (= 1 (-> p :counts :subs-ran)))
      (is (= 1 (-> p :counts :subs-skipped))))))

(deftest project-empty-record-zeroes-subs-skipped
  (testing "rf2-ty5r5o — empty / nil record → [] :subs-skipped + 0 count."
    (doseq [r [nil {}]]
      (let [p (subs/project-record r)]
        (is (= [] (:subs-skipped p)))
        (is (= 0 (-> p :counts :subs-skipped)))))))

(deftest project-record-preserves-parameterized-skip-past-same-id-recompute
  (testing "rf2-cj2yx — end to end: `[:item/derived 1]` recomputes (a
            :sub-runs row) while `[:item/derived 2]` memo-hits. project-record
            builds the run-set from CONCRETE query-vs, so it excludes only the
            exact recomputed query and keeps the `[:item/derived 2]` skip —
            Xray no longer claims no concrete instance was skipped when one
            was."
    (let [record {:sub-runs     [{:sub-id :item/derived :query-v [:item/derived 1]
                                  :recomputed? true :value-changed? true}]
                  :trace-events [(skip-qv :item/derived [:item/derived 2])]}
          p      (subs/project-record record)]
      (is (= [[:item/derived 1]] (mapv :query-v (:subs-ran p)))
          ":item/derived 1 recomputed → a subs-ran row")
      (is (= [[:item/derived 2]] (mapv :query-v (:subs-skipped p)))
          ":item/derived 2 memo-hit survives (NOT suppressed by the same-id recompute)")
      (is (= [:item/derived] (mapv :sub-id (:subs-skipped p)))
          "the registered id still rides the skip row")
      (is (= 1 (-> p :counts :subs-skipped))))))

(deftest project-record-both-parameterizations-skipped-survive
  (testing "rf2-cj2yx — two same-id skipped queries with no recompute BOTH
            survive through project-record (distinct concrete rows)."
    (let [record {:sub-runs     []
                  :trace-events [(skip-qv :item/derived [:item/derived 1])
                                 (skip-qv :item/derived [:item/derived 2])]}
          p      (subs/project-record record)]
      (is (= [[:item/derived 1] [:item/derived 2]] (mapv :query-v (:subs-skipped p)))
          "both concrete parameterizations survive as distinct rows")
      (is (= 2 (-> p :counts :subs-skipped))))))

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
    (let [record {:sub-runs     [(sub-run :a) (sub-run :b) (sub-run :c)]
                  :renders      [(render :v-x) (render :v-y)]
                  :trace-events [(flow-ev :rf.flow/computed)]}
          p (subs/project-record record)]
      (is (= 3 (-> p :counts :subs-ran)))
      (is (= [:a :b :c] (mapv :sub-id (:subs-ran p))))
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
  "A static sub-topology snapshot in the rf2-e3acps shape: `:input-kind`
  discriminates `:db` (Level 1) / `:static` / `:parametric`, and
  `:inputs` carries the literal declared QUERY-VECTORS for `:static`
  (`[[:cart/state] [:cart/items]]`) or the `:parametric` sentinel for an
  `input-fn` sub. Carries :ns/:line/:file so the code coord resolves."
  {:cart/state {:input-kind :db :inputs [] :ns 'cart :line 10 :file "cart.cljs"}
   :cart/items {:input-kind :db :inputs [] :ns 'cart :line 14 :file "cart.cljs"}
   :cart/total {:input-kind :static
                :inputs [[:cart/state] [:cart/items]]
                :ns 'cart :line 22 :file "cart.cljs"}
   ;; rf2-e3acps — a parametric input-fn sub: static topology reports the
   ;; :parametric sentinel (realized edges are per-concrete-query-v cache
   ;; state, not statically enumerable).
   :cart/line  {:input-kind :parametric :inputs :parametric
                :ns 'cart :line 30 :file "cart.cljs"}})

(deftest partition-splits-by-input-kind
  (testing "rf2-8ve8z + rf2-e3acps — :input-kind :db subs land in Level 1;
            :static / :parametric land in Level 2+, carrying their input-sub
            names + coord (parametric carries NO static inputs)."
    (let [{:keys [level-1 level-2]}
          (subs/partition-subs-by-level
            [(sub-run+ :cart/state true true)
             (sub-run+ :cart/total true true)]
            topology)]
      (is (= [:cart/state] (mapv :sub-id level-1)))
      (is (= [:cart/total] (mapv :sub-id level-2)))
      (is (= [:cart/state :cart/items] (-> level-2 first :inputs))
          "Level 2+ :static row carries its declared input-sub names (query-vector heads)")
      (is (= :static (-> level-2 first :input-kind))
          "the Level 2 row carries the :input-kind discriminator")
      (is (= "cart.cljs" (-> level-1 first :coord :file))
          "Level 1 row carries the topology source coord"))))

(deftest partition-parametric-sub-is-level-2-with-no-static-edges
  (testing "rf2-e3acps — a :parametric sub is Level 2+ (composes upstream
            subs) but reports NO STATIC input edges; the static partition
            must not fabricate un-materialized parametric edges + must not
            crash on the :parametric (non-vector) :inputs sentinel."
    (let [{:keys [level-1 level-2]}
          (subs/partition-subs-by-level
            [(sub-run+ :cart/state true true)
             (sub-run+ :cart/line  true true)]
            topology)]
      (is (= [:cart/state] (mapv :sub-id level-1))
          ":db reader is Level 1")
      (is (= [:cart/line] (mapv :sub-id level-2))
          "parametric sub is Level 2+ (NOT misbucketed as Level 1)")
      (is (= [] (-> level-2 first :inputs))
          "parametric sub draws no STATIC edges (realized edges live in the live/cache view)")
      (is (= :parametric (-> level-2 first :input-kind))
          "the parametric discriminator rides the row so the panel can badge it"))))

(deftest topology-input-sub-ids-handles-static-and-parametric
  (testing "rf2-e3acps — topology-input-sub-ids projects :static query-vectors
            to their sub-id heads and returns [] for the :parametric sentinel."
    (is (= [:cart/state :cart/items]
           (subs/topology-input-sub-ids (:cart/total topology)))
        ":static → query-vector heads (sub-ids)")
    (is (= []
           (subs/topology-input-sub-ids (:cart/line topology)))
        ":parametric sentinel → [] (no static edges)")
    (is (= []
           (subs/topology-input-sub-ids (:cart/state topology)))
        ":db reader → []")))

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

