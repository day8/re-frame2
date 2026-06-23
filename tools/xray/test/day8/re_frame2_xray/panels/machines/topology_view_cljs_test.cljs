(ns day8.re-frame2-xray.panels.machines.topology-view-cljs-test
  "Pure-data tests for the topology-view composition helpers (rf2-vcnvj).
  Focuses on `static-context-shape` — the root-Context-chrome projection
  the Static-Machines topology path feeds the chart so the root context
  renders on the blank-state topology without a live snapshot.

  Also pins the `Topology` → `machine-canvas/Chart` prop boundary
  (rf2-xf5on): the view's `:trace-events` contract promises
  `fired-this-epoch` edge highlights, so the fired-edge ids it computes
  must reach the chart mount's `:fired-edge-ids` prop."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.panels.machines.topology-view :as tv]))

(defn- toy-definition
  "Minimal machine: :empty --:populate--> :populated --:submit-->
  :submitting (final). Mirrors the trace-state test's fixture so the
  fired-edge ids agree by construction with the live chart's projection."
  []
  {:initial :empty
   :states  {:empty      {:on {:populate :populated}}
             :populated  {:on {:submit :submitting}}
             :submitting {:final? true}}})

(defn- canonical-edge-id
  "The live-chart canonical edge id for `from→to via event`, projected
  through the SAME `chart.layout/project-definition` the live MachineChart
  uses — so the test asserts the EXACT ids the chart paints, not a
  re-implemented scheme."
  [definition from-path to-path event]
  (->> (:edges (chart-layout/project-definition definition))
       (some (fn [e]
               (when (and (= from-path (:from-path e))
                          (= to-path   (:to-path e))
                          (= event     (:event e)))
                 (:id e))))))

(defn- find-chart-props
  "Walk the hiccup `Topology` returns and return the props map of the
  `[machine-canvas/Chart {...}]` mount (or nil if absent). Depth-first;
  returns the first match."
  [hiccup]
  (cond
    (and (vector? hiccup)
         (= machine-canvas/Chart (first hiccup)))
    (second hiccup)

    (vector? hiccup)
    (some find-chart-props hiccup)

    (seq? hiccup)
    (some find-chart-props hiccup)

    :else nil))

(deftest static-context-shape-maps-keys-to-type-captions
  (testing "rf2-vcnvj — derives `(key → type-caption)` from the
            definition's declared `:data`, NOT the live values (the door
            machine's `{:opened-count 0 :held-open? false :trail []}`
            shape)."
    (let [def   {:initial :locked
                 :data    {:opened-count 0 :held-open? false :trail []
                           :note "x" :tag :a :nested {} :many #{}}
                 :states  {:locked {}}}
          shape (tv/static-context-shape def)]
      (is (= {:opened-count "number"
              :held-open?   "boolean"
              :trail        "vector"
              :note         "string"
              :tag          "keyword"
              :nested       "map"
              :many         "set"}
             shape)
          "each key maps to its value's type caption (shape, not value)"))))

(deftest static-context-shape-nil-when-no-data
  (testing "rf2-vcnvj — a machine with no `:data` yields nil so the root
            Context panel stays hidden."
    (is (nil? (tv/static-context-shape {:initial :a :states {:a {}}})))
    (is (nil? (tv/static-context-shape {:initial :a :data nil :states {:a {}}})))
    (is (nil? (tv/static-context-shape nil)))))

(deftest static-context-declared-schema-is-authoritative
  (testing "rf2-3q4k5b (EP-0005) — when a machine declares a `[:schemas :data]`,
            the static Context shape is read AUTHORITATIVELY off the schema
            (not the `:data` sample) and `static-context-inferred?` is FALSE,
            so the chart drops the `inferred from :data` badge."
    (let [def {:initial :anon
               ;; A deliberately misleading partial :data sample — the
               ;; declared schema must win.
               :data    {:retries nil}
               :schemas {:data [:map
                                [:retries :int]
                                [:token {:optional true} [:maybe :string]]]}
               :states  {:anon {}}}]
      (is (= {:retries "number" :token "string?"}
             (tv/static-context-shape def))
          "shape comes from the SCHEMA's :map entries, authoritative over the
           sample")
      (is (false? (tv/static-context-inferred? def))
          "declared schema → not inferred (chart drops the inferred badge)"))))

(deftest static-context-inferred-when-no-schema
  (testing "rf2-3q4k5b (EP-0005) — absent a `[:schemas :data]`, the shape falls
            back to the one-sample inference and `static-context-inferred?`
            is TRUE (rf2-5tz9p's badge stays)."
    (let [def {:initial :idle
               :data    {:hits 0 :trail []}
               :states  {:idle {}}}]
      (is (= {:hits "number" :trail "vector"}
             (tv/static-context-shape def)))
      (is (true? (tv/static-context-inferred? def))
          "no schema → inferred (chart keeps the `inferred from :data` badge)"))
    (testing "and defaults true when there is no shape at all"
      (is (true? (tv/static-context-inferred? {:initial :a :states {:a {}}}))))))

(deftest topology-threads-inferred-flag-to-chart
  (testing "rf2-3q4k5b (EP-0005) — the `Topology` mount forwards the
            declared-over-inferred provenance to `machine-canvas/Chart`'s
            `:context-band-inferred?` prop: false for a declared schema, true
            for an inferred sample."
    (let [declared-def {:initial :anon
                        :schemas {:data [:map [:retries :int]]}
                        :states  {:anon {}}}
          inferred-def {:initial :idle
                        :data    {:hits 0}
                        :states  {:idle {}}}
          declared-props (find-chart-props
                           (tv/Topology {:machine-id :m :definition declared-def}))
          inferred-props (find-chart-props
                           (tv/Topology {:machine-id :m :definition inferred-def}))]
      (is (false? (:context-band-inferred? declared-props))
          "declared schema → :context-band-inferred? false reaches the chart")
      (is (true? (:context-band-inferred? inferred-props))
          "inferred sample → :context-band-inferred? true reaches the chart"))))

;; ---- Topology → Chart fired-edge prop boundary (rf2-xf5on) --------------

(deftest topology-forwards-fired-edge-ids-to-chart
  (testing "rf2-xf5on — the fired-this-epoch edge ids the view computes from
            `:trace-events` reach the `machine-canvas/Chart` mount's
            `:fired-edge-ids` prop (the docstring's contract). Before the
            fix the prop was absent and the fired treatment was silently
            dropped."
    (let [def         (toy-definition)
          populate-id (canonical-edge-id def [:empty] [:populated] :populate)
          events      [{:operation :rf.machine/transition
                        :tags      {:machine-id :cart}
                        :from      [:empty] :to [:populated]
                        :event     :populate}]
          props       (find-chart-props
                        (tv/Topology {:machine-id   :cart
                                      :definition   def
                                      :trace-events events}))]
      (is (some? props)
          "the :else branch mounts machine-canvas/Chart")
      (is (string? populate-id))
      (is (contains? props :fired-edge-ids)
          "Chart mount carries the :fired-edge-ids prop")
      (is (= #{populate-id} (:fired-edge-ids props))
          "and it is exactly the canonical fired-edge id set the live
           chart paints"))))

(deftest topology-passes-empty-fired-edges-when-no-transition-this-epoch
  (testing "rf2-xf5on — case-B (no transition this epoch) forwards `#{}`,
            identical to passing no fired highlight."
    (let [def   (toy-definition)
          props (find-chart-props
                  (tv/Topology {:machine-id   :cart
                                :definition   def
                                :trace-events []}))]
      (is (some? props))
      (is (= #{} (:fired-edge-ids props))
          "empty fired set → no fired highlight on the blank-epoch chart"))))

;; ---- Topology → Chart parallel region-map snapshot boundary (rf2-di7mda) -
;;
;; A PARALLEL machine's live snapshot `:state` is a region-map (Spec 005 +
;; machines-viz API §:current-state) of N simultaneously-active leaves. When
;; the wrapper falls back to a live snapshot (no focused transition / history
;; hit), the region-map MUST forward through to the chart's `:current-state`
;; UNCHANGED so the multi-active highlight (`chart.layout/highlight-ids`)
;; lights EVERY active region leaf. The bug narrowed the snapshot fallback to
;; keyword/vector only, dropping the region-map before the chart could render
;; the N-active highlight (a live parallel machine showed no active regions
;; while the wrapper still claimed `data-current-state-source = "snapshot"`).

(defn- parallel-ingest-definition
  "Canonical Spec 005 parallel fixture (mirrors the machines-viz
  `highlight-ids` region-map test): two regions, each with its OWN
  same-named `:done` leaf — so the region-scoped highlight must
  attribute each region's active leaf to its OWN node."
  []
  {:type    :parallel
   :regions {:fetch    {:initial :loading
                        :states  {:loading {:on {:loaded :done}}
                                  :done    {:final? true}}}
            :validate {:initial :checking
                       :states  {:checking {:on {:ok :done}}
                                 :done     {:final? true}}}}})

(deftest topology-forwards-parallel-region-map-snapshot-to-chart
  (testing "rf2-di7mda — a parallel `:snapshot-state` region-map reaches the
            `machine-canvas/Chart` mount's `:current-state` UNCHANGED, so the
            chart's multi-active highlight lights every active region leaf.
            Before the fix the wrapper dropped the map (narrowed to
            keyword/vector) and a live parallel machine rendered no active
            regions."
    (let [def          (parallel-ingest-definition)
          ;; BOTH regions reached their (same-named) :done leaf — the
          ;; multi-active live snapshot a running parallel machine carries.
          region-map   {:fetch :done :validate :done}
          props        (find-chart-props
                         (tv/Topology {:machine-id     :ingest
                                       :definition     def
                                       :snapshot-state region-map}))]
      (is (some? props)
          "the :else branch mounts machine-canvas/Chart")
      (is (= region-map (:current-state props))
          "the parallel region-map forwards through UNCHANGED — not dropped,
           not narrowed to a single path")
      ;; End-to-end: the forwarded map drives the chart's multi-active
      ;; highlight — every active region leaf lights up (one id per region,
      ;; region-scoped so the two same-named :done leaves stay distinct).
      (let [ids (chart-layout/highlight-ids (:current-state props))]
        (is (= 2 (count ids))
            "TWO active leaves light up (one per region), not zero (dropped)
             and not one (narrowed)")
        (is (= #{(chart-layout/region-scoped-id :fetch [:done])
                 (chart-layout/region-scoped-id :validate [:done])}
               ids)
            "each region's active :done leaf is attributed to its OWN
             region-scoped node-id — every active region leaf lit")))))

(deftest topology-keyword-and-vector-snapshot-still-forward
  (testing "rf2-di7mda — the single-active arms are unchanged: a flat-keyword
            snapshot forwards as a 1-element path, a vector path forwards
            verbatim (regression guard around the region-map arm addition)."
    (let [def       (toy-definition)
          kw-props  (find-chart-props
                      (tv/Topology {:machine-id :cart :definition def
                                    :snapshot-state :populated}))
          vec-props (find-chart-props
                      (tv/Topology {:machine-id :cart :definition def
                                    :snapshot-state [:populated]}))]
      (is (= [:populated] (:current-state kw-props))
          "flat keyword snapshot → 1-element path forwarded")
      (is (= [:populated] (:current-state vec-props))
          "vector path snapshot → forwarded verbatim"))))
