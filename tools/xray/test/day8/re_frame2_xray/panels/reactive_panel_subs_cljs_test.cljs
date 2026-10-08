(ns day8.re-frame2-xray.panels.reactive-panel-subs-cljs-test
  "Tests for the Reactive panel's pure-data projection
  (spec/021 §3).

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

  (`:rf.sub/computed` / `:rf.sub/skipped` are names the substrate never
  emits, so a projection grepping raw `:trace-events` for them would
  read zero subs ran.)"
  (:require [cljs.test :refer-macros [are deftest is testing]]
            [day8.re-frame2-xray.panels.reactive-flow-graph :as graph]
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
  [sub-id]
  {:operation :rf.sub/skip
   :tags      {:rf.sub/id                    sub-id
               :rf.sub/query-v               [sub-id]
               :rf.sub/reason                :input-value-equal
               :rf.sub/input-paths-unchanged []}})

(defn- skip-qv
  "A `:rf.sub/skip` op for a CONCRETE parameterized query —
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

(deftest focused-epoch-record-rejects-only-the-pinned-no-epoch-shape
  (testing "a PINNED event bundle that settled NO epoch (a `:dispatch-id`
            beside a nil `:epoch-id`) resolves to no record — never the
            head, which would render the LATEST cascade under a selection
            that is not that epoch's. The discriminator changes nothing
            else: an UNSET focus head-falls-back, and a real pinned epoch
            resolves its own record."
    (let [history [{:epoch-id :a} {:epoch-id :b} {:epoch-id :c}]]
      (is (nil? (subs/focused-epoch-record history nil 999)))
      (is (= {:epoch-id :c} (subs/focused-epoch-record history nil nil)))
      (is (= {:epoch-id :b} (subs/focused-epoch-record history :b 999))))))

;; ---- project-record: counts -------------------------------------------

(deftest project-record-counts-from-sub-runs-renders-and-flow-ops
  (testing "the counts come from `:sub-runs`, `:renders` and the canonical
            `:rf.flow/computed` / `:rf.flow/skip` ops on `:trace-events`
            (spec/021 §3.5)"
    (is (= {:subs-ran 1 :subs-skipped 0 :views-rendered 1 :view-rows 0
            :unmounted-views 0 :destroyed-subs 0
            :flows-recomputed 2 :flows-skipped 1}
           (:counts (subs/project-record
                      {:sub-runs     [(sub-run :cart/state)]
                       :renders      [(render :cart/Summary)]
                       :trace-events [(flow-ev :rf.flow/computed)
                                      (flow-ev :rf.flow/computed)
                                      (flow-ev :rf.flow/skip)]}))))))

;; ---- project-record: subs skipped (memo-hit :rf.sub/skip) -------------
;;
;; The canonical `:rf.sub/skip` evidence — the substrate emits it on
;; `:trace-events` (NOT `:sub-runs`) on a memo hit; the projection reads it
;; into the distinct `:subs-skipped` slice. There are no
;; `:recomputed? false` sub-run rows here: the substrate never emits that
;; shape.

(deftest skipped-subs-reads-rf-sub-skip-ops
  (testing "skipped-subs projects each :rf.sub/skip op off :trace-events —
            de-duplicated, first-seen order."
    (let [events [(skip-ev :user/name)
                  (skip-ev :cart/eligibility)
                  (skip-ev :user/name)] ; dup → once
          rows   (subs/skipped-subs events #{})]
      (is (= [:user/name :cart/eligibility] (mapv :sub-id rows))))))

;; ---- concrete-query identity ------------------------------------------
;;
;; The cache/trace identify a short-circuited reaction by its full query
;; vector, so the disclosure must dedup + cross-exclude by concrete query-v
;; — NOT the registered sub-id, which collapses distinct parameterizations.

(deftest skipped-subs-recompute-excludes-only-exact-query
  (testing "the focused counterexample: `[:item/derived 1]`
            recomputes while `[:item/derived 2]` memo-hits. The recompute
            excludes ONLY its exact query; the different same-id memo-hit is
            preserved + disambiguated, not suppressed."
    (let [events       [(skip-qv :item/derived [:item/derived 1])  ; ran below → excluded
                        (skip-qv :item/derived [:item/derived 2])] ; memo-hit → kept
          ran-query-vs #{[:item/derived 1]}
          rows         (subs/skipped-subs events ran-query-vs)]
      (is (= [[:item/derived 2]] (mapv :query-v rows))
          "only the exact recomputed query is excluded; the sibling survives"))))

(deftest project-record-surfaces-subs-skipped-distinct-from-ran
  (testing "project-record reads the memo-hit :rf.sub/skip
            evidence into :subs-skipped, kept DISTINCT from the run-set even
            when a run carries :value-changed? false (a recompute that
            produced the same value is NOT a memo-hit skip)."
    (let [record {:sub-runs     [{:sub-id :read-a :query-v [:read-a]
                                  :recomputed? true :value-changed? false}] ; RAN, value unchanged
                  :trace-events [(skip-ev :read-a)       ; also skipped → excluded
                                 (skip-ev :derived-a)]}
          p      (subs/project-record record)]
      (is (= [:derived-a] (mapv :sub-id (:subs-skipped p)))
          ":subs-skipped names only the sub that skipped without running"))))

(deftest project-record-preserves-parameterized-skip-past-same-id-recompute
  (testing "end to end: `[:item/derived 1]` recomputes (a
            :sub-runs row) while `[:item/derived 2]` memo-hits. project-record
            builds the run-set from CONCRETE query-vs, so it excludes only the
            exact recomputed query and keeps the `[:item/derived 2]` skip —
            Xray does not claim no concrete instance was skipped when one
            was."
    (let [record {:sub-runs     [{:sub-id :item/derived :query-v [:item/derived 1]
                                  :recomputed? true :value-changed? true}]
                  :trace-events [(skip-qv :item/derived [:item/derived 2])]}
          p      (subs/project-record record)]
      (is (= [[:item/derived 2]] (mapv :query-v (:subs-skipped p)))
          ":item/derived 2 memo-hit survives (NOT suppressed by the same-id recompute)")
      (is (= [:item/derived] (mapv :sub-id (:subs-skipped p)))
          "the registered id rides the skip row"))))

(deftest project-record-both-parameterizations-skipped-survive
  (testing "two same-id skipped queries with no recompute BOTH
            survive through project-record (distinct concrete rows)."
    (let [record {:sub-runs     []
                  :trace-events [(skip-qv :item/derived [:item/derived 1])
                                 (skip-qv :item/derived [:item/derived 2])]}
          p      (subs/project-record record)]
      (is (= [[:item/derived 1] [:item/derived 2]] (mapv :query-v (:subs-skipped p)))))))

;; ===========================================================================
;; the Views three-table data layer
;; ===========================================================================

;; ---- helpers -----------------------------------------------------------

(defn- sub-run+ [sub-id recomputed? changed?]
  {:sub-id sub-id :query-v [sub-id]
   :recomputed? recomputed? :value-changed? changed?})

(defn- rendered-ev
  "A captured `:rf.view/rendered` trace event — tags carry the
  view-render fields (`:rf.view/id`, `:rf.view/render-key`,
  `:rf.view/mount?`, `:rf.view/deref-subs`)."
  [view-id mount? deref-subs]
  {:operation :rf.view/rendered
   :tags (cond-> {:rf.view/id view-id :rf.view/render-key [view-id 0] :rf.view/mount? mount?}
           (some? deref-subs) (assoc :rf.view/deref-subs deref-subs))})

(defn- unmounted-ev [view-id]
  {:operation :rf.view/unmounted
   :tags {:rf.view/id view-id :rf.view/render-key [view-id 0]}})

;; ---- changed-vs-structural classifier ---------------------------------

(deftest compute-view-reason-classifies-by-the-views-own-changed-reads
  (testing "a view that derefs a sub that changed this cascade gets a
            :reactive reason naming the INTERSECTION — its own changed
            reads. A view none of whose reads changed gets the UNNAMED
            structural (`← parent re-render`) reason (spec/021 §3.5)."
    (are [deref-subs changed expected] (= expected (subs/compute-view-reason deref-subs changed))
      [[:cart/total] [:cart/count]] #{:cart/total} {:kind :reactive :subs [:cart/total]}
      [[:cart/total]]               #{:other/sub}  {:kind :structural})))

;; ---- view-rows projection ---------------------------------------------

(deftest view-rows-maps-mount-rerender-unmount
  (testing ":mount? true → :mount, false → :rerender, the
            :rf.view/unmounted op → :unmount."
    (let [events [(rendered-ev :v/a true  [[:s1]])
                  (rendered-ev :v/b false [[:s1]])
                  (unmounted-ev :v/c)]
          rows   (subs/view-rows events #{:s1})]
      (is (= [[:v/a :mount] [:v/b :rerender] [:v/c :unmount]]
             (mapv (juxt :view-id :action) rows))))))

(deftest view-rows-carries-cause-and-timing
  (testing "a :rf.view/rendered op carrying
            :rf.view/triggered-by + :rf.view/elapsed-ms threads those
            through onto the view row for the flow graph's cause + timing."
    (let [ev   {:operation :rf.view/rendered
                :tags {:rf.view/id :v/a :rf.view/render-key [:v/a 0]
                       :rf.view/mount? false
                       :rf.view/deref-subs [[:s1]]
                       :rf.view/triggered-by :s1
                       :rf.view/elapsed-ms 2.4}}
          row  (first (subs/view-rows [ev] #{:s1}))]
      (is (= [:s1 2.4] ((juxt :triggered-by :elapsed-ms) row))))))

;; ---- teardown sections ------------------------------------------------

(deftest unmounted-views-lists-unmount-ops
  (testing "UNMOUNTED VIEWS reads :rf.view/unmounted ops,
            de-duplicated, first-seen order."
    (let [events [(unmounted-ev :v/modal)
                  (unmounted-ev :v/tooltip)
                  (unmounted-ev :v/modal)]] ; dup → once
      (is (= [{:view-id :v/modal} {:view-id :v/tooltip}]
             (subs/unmounted-views events))))))

(deftest destroyed-subscriptions-reads-dispose-op-when-present
  (testing "DESTROYED SUBSCRIPTIONS reads
            :rf.sub/dispose ops (the singular form the framework emits;
            a past-tense form would match no framework-emitted
            trace)."
    (let [events [{:operation :rf.sub/dispose :tags {:rf.sub/id :s/modal}}
                  {:operation :rf.sub/dispose :tags {:sub-id :s/tip}}]]
      (is (= [{:sub-id :s/modal} {:sub-id :s/tip}]
             (subs/destroyed-subscriptions events))
          "reads the framework-emitted :rf.sub/dispose op"))))

;; ---- Level 1 / Level 2+ partition -------------------------------------

(def ^:private topology
  "A static sub-topology snapshot in the static-topology shape: `:input-kind`
  discriminates `:db` (Level 1) / `:static` / `:parametric`, and
  `:inputs` carries the literal declared QUERY-VECTORS for `:static`
  (`[[:cart/state] [:cart/items]]`) or the `:parametric` sentinel for an
  `input-fn` sub. Carries :ns/:line/:file so the code coord resolves."
  {:cart/state {:input-kind :db :inputs [] :ns 'cart :line 10 :file "cart.cljs"}
   :cart/items {:input-kind :db :inputs [] :ns 'cart :line 14 :file "cart.cljs"}
   :cart/total {:input-kind :static
                :inputs [[:cart/state] [:cart/items]]
                :ns 'cart :line 22 :file "cart.cljs"}
   ;; A parametric input-fn sub: static topology reports the
   ;; :parametric sentinel (realized edges are per-concrete-query-v cache
   ;; state, not statically enumerable).
   :cart/line  {:input-kind :parametric :inputs :parametric
                :ns 'cart :line 30 :file "cart.cljs"}})

(deftest partition-splits-by-input-kind
  (testing ":input-kind :db subs land in Level 1;
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
      (is (= "cart.cljs" (-> level-1 first :coord :file))
          "Level 1 row carries the topology source coord"))))

(deftest partition-parametric-sub-is-level-2-with-no-static-edges
  (testing "a :parametric sub is Level 2+ (composes upstream
            subs) but reports NO STATIC input edges; the static partition
            must not fabricate un-materialized parametric edges + must not
            crash on the :parametric (non-vector) :inputs sentinel."
    (let [{:keys [level-2]}
          (subs/partition-subs-by-level [(sub-run+ :cart/line true true)] topology)]
      (is (= [:cart/line] (mapv :sub-id level-2))
          "parametric sub is Level 2+ (NOT misbucketed as Level 1)")
      (is (= [] (-> level-2 first :inputs))
          "parametric sub draws no STATIC edges (realized edges live in the live/cache view)"))))

(deftest partition-carries-changed-flag
  (testing ":value-changed? rides onto each row's :changed?."
    (let [{:keys [level-1]}
          (subs/partition-subs-by-level
            [(sub-run+ :cart/state true true)
             (sub-run+ :cart/items true false)]
            topology)]
      (is (= [true false] (mapv :changed? level-1))))))

(deftest partition-missing-from-topology-defaults-level-1
  (testing "nil-safe: a sub absent from the topology defaults
            to Level 1 (degrade, never crash)."
    (let [{:keys [level-1]}
          (subs/partition-subs-by-level
            [(sub-run+ :mystery/sub true false)] nil)]
      (is (= [:mystery/sub] (mapv :sub-id level-1))))))

;; ---- shared-subscription edges ----------------------------------------

(deftest sub-readers-maps-each-sub-to-views-that-read-it
  (testing "sub-readers builds {sub-id [view-id ...]}: every
            view that derefs a sub this cascade lands in that sub's reader
            list (the shared-sub edge)."
    (let [events [(rendered-ev :v/a false [[:s/x] [:s/y]])
                  (rendered-ev :v/b false [[:s/x]])
                  (rendered-ev :v/c false [[:s/z]])]
          readers (subs/sub-readers events)]
      (is (= {:s/x [:v/a :v/b] :s/y [:v/a] :s/z [:v/c]} readers)))))

(deftest sub-readers-preserves-first-seen-view-order-and-dedupes
  (testing "a view that re-derefs the same sub, or two ops for
            the same view-id, contribute the view-id once; reader order is
            first-seen across the trace."
    (let [events [(rendered-ev :v/b false [[:s/x] [:s/x]]) ; re-deref same sub
                  (rendered-ev :v/a false [[:s/x]])
                  (rendered-ev :v/b false [[:s/x]])]        ; same view again
          readers (subs/sub-readers events)]
      (is (= [:v/b :v/a] (:s/x readers))
          "first-seen order, de-duplicated"))))

(deftest partition-attaches-readers-to-sub-rows
  (testing "partition-subs-by-level attaches each sub's
            :readers (which views read it) onto the L1 / L2 row; none
            when no view read the sub."
    (let [readers {:cart/state [:cart/Header :cart/Summary]
                   :cart/total [:cart/Summary]}
          {:keys [level-1 level-2]}
          (subs/partition-subs-by-level
            [(sub-run+ :cart/state true true)
             (sub-run+ :cart/items true false) ; no reader → :readers absent
             (sub-run+ :cart/total true true)]
            topology readers)]
      (is (= [[:cart/Header :cart/Summary] nil [:cart/Summary]]
             (mapv :readers (concat level-1 level-2)))))))

;; ---- project-record composes the level and view slots -----------------

(deftest project-record-emits-level-and-view-slots
  (testing "project-record composes :level-1-subs /
            :level-2-subs (from topology, carrying the readers drawn from
            :trace-events view ops) and the :unmounted-views teardown slot."
    (let [record {:sub-runs [(sub-run+ :cart/state true true)
                             (sub-run+ :cart/total true true)]
                  :trace-events [(rendered-ev :cart/Summary false [[:cart/total]])
                                 (unmounted-ev :cart/Gone)]}
          p (subs/project-record record topology)]
      (is (= [:cart/state] (mapv :sub-id (:level-1-subs p))))
      (is (= [:cart/total] (mapv :sub-id (:level-2-subs p))))
      (is (= [:cart/Summary] (-> p :level-2-subs first :readers))
          ":cart/total's L2 row carries its :readers")
      (is (= [{:view-id :cart/Gone}] (:unmounted-views p))
          ":unmounted-views projects the unmount op"))))

;; ---- list instances reach the graph as instances ----------------------
;;
;; Producer-derived: the record is shaped the way the substrate emits it —
;; one `:sub-runs` row per concrete query-v, one `:rf.view/rendered` op per
;; component instance with its own render-key and read-set — and the
;; projection is fed straight to the graph layout the panel renders.

(defn- row-rendered-ev [token id]
  {:operation :rf.view/rendered
   :tags {:rf.view/id         :app/todo-row
          :rf.view/render-key [:app/todo-row token]
          :rf.view/mount?     false
          :rf.view/deref-subs [[:todo/by-id id]]}})

(deftest project-record-routes-list-instances-instance-to-instance
  (testing "three todo rows over one parametric sub: the projection keeps
            each instance's query-v and read-set, and the graph joins sub
            instance i to view instance i"
    (let [record {:sub-runs (vec (for [id [1 2 3]]
                                   {:sub-id :todo/by-id :query-v [:todo/by-id id]
                                    :recomputed? true :value-changed? true}))
                  :trace-events (mapv row-rendered-ev [11 12 13] [1 2 3])}
          p      (subs/project-record record nil)
          g      (graph/layout p)
          edges  (filter #(= :sub-view (:kind %)) (:edges g))]
      (is (= #{[(pr-str [:todo/by-id 1]) (pr-str [:app/todo-row 11])]
               [(pr-str [:todo/by-id 2]) (pr-str [:app/todo-row 12])]
               [(pr-str [:todo/by-id 3]) (pr-str [:app/todo-row 13])]}
             (set (map (juxt :from-key :to-key) edges)))
          "one edge per instance pair — no edge lands on another row's box"))))

(deftest partition-carries-declared-input-query-vs
  (testing "a :static Level-2 row carries its concrete query-v and its
            declared input query-vs beside the id heads"
    (let [{:keys [level-2]}
          (subs/partition-subs-by-level [(sub-run+ :cart/total true true)] topology)]
      (is (= {:query-v [:cart/total] :input-query-vs [[:cart/state] [:cart/items]]}
             (select-keys (first level-2) [:query-v :input-query-vs]))))))

