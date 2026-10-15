(ns day8.re-frame2-xray.panels-e2e.reactive-data-e2e-cljs-test
  "Real recompute and memo-hit evidence uses the observed frame topology."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.image :as rf.image]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.reactive-panel-subs :as reactive-subs]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]
            [day8.re-frame2-xray.test-helpers.host-fixtures.counter :as counter]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- inc-and-recompute!
  "Drive a REAL `[:counter/inc]` cascade, then force `:counter/value` to
  recompute so the substrate emits a real `:rf.sub/run`.

  The plain-atom substrate recomputes a derived value on every deref (no
  eager reactive flush), so a post-settle `@(subscribe ...)` runs the sub
  body and emits the `:rf.sub/run` the framework back-fills
  into the just-settled `:counter/inc` epoch's `:sub-runs`. We then mirror
  the framework epoch ring into Xray's `:epoch-history` (the same sync the
  production epoch-collector does) so the panel subs see the back-filled
  record."
  []
  (rf/dispatch-sync [:counter/inc] {:frame e2e/default-host-frame})
  @(rf/subscribe [:counter/value] {:frame e2e/default-host-frame})
  (e2e/sync-xray-trace-mirror!)
  (e2e/sync-xray-epoch-history!))

(deftest reactive-data-subs-ran-names-real-sub
  (testing "a REAL `[:counter/inc]` cascade recomputes the
            `:counter/value` sub; the reactive-data projection's
            `:subs-ran` names that real sub, captured through the
            substrate's `sub-run-row` (NO injection seam). Reddens on a
            `sub-run-row` key rename in capture.cljc."
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (inc-and-recompute!)
        (let [d        (e2e/sub-xray [:rf.xray/reactive-data])
              subs-ran (:subs-ran d)]

          (let [row (some #(when (= :counter/value (:sub-id %)) %) subs-ran)]

            (is (= [true [:counter/value]]
                   ((juxt :recomputed? :query-v) row)))
            )
          (is (pos? (-> d :counts :subs-ran))
              ":counts :subs-ran tallies the real run-set"))))))

(deftest reactive-data-composite-shape-holds-through-real-pipeline
  (testing "the composite `:rf.xray/reactive-data` resolves
            its documented shape over a REAL cascade: the flow-graph slots
            (`:level-1-subs` / `:view-rows` / `:sub-readers`) are present,
            `:subs-ran` is the run-set, and the `:subs-skipped` memo-hit
            slice is present (empty here, no memo hit driven)."
    (e2e/with-host-and-xray-frames
      {:install-host counter/install-and-init!}
      (fn []
        (inc-and-recompute!)
        (let [d (e2e/sub-xray [:rf.xray/reactive-data])]
          (is (every? #(contains? d %)
                      [:subs-ran :level-1-subs :view-rows :sub-readers]))
          (is (= [] (:subs-skipped d))
              "no memo hit driven → empty (a run alone is not a skip)")
          ;; :counter/value reads app-db directly → a Level-1 sub.
          (is (some #(= :counter/value (:sub-id %)) (:level-1-subs d))
              (str ":counter/value should partition to Level 1 (reads "
                   "app-db directly). level-1-subs: "
                   (pr-str (:level-1-subs d)))))))))

;; ---- causal memo-hit → :subs-skipped -----------------------------------

(defn- install-ab-host!
  "A tiny host whose LAYER-2 sub (`:ab/derived-a`) composes a LAYER-1 sub
  (`:ab/read-a`) — the shape that lets a real cascade drive BOTH categories
  the panel must keep distinct in ONE epoch: bumping the UNRELATED `:b`
  path forces `:ab/read-a` to RECOMPUTE (db changed) but its VALUE is
  unchanged (`:a` held), so its layer-2 reader `:ab/derived-a` sees a
  value-equal input and MEMO-HITS — the substrate emits a canonical
  `:rf.sub/skip` for it.

  Dispatches `[:ab/init]` at install (like the counter fixture's
  `install-and-init!`) so a cascade EXISTS when Xray installs — that seeds
  Xray's target frame to this host frame (`default-target-frame` is nil /
  UNSELECTED, so an install with no cascade would leave the epoch-history
  mirror empty)."
  []
  (rf/reg-event :ab/init   (fn [_ _] {:db {:a 0 :b 0}}))
  (rf/reg-event :ab/bump-b (fn [{:keys [db]} _] {:db (update db :b inc)}))
  (rf/reg-sub :ab/read-a (fn [db _] (:a db)))
  (rf/reg-sub :ab/derived-a {:inputs [[:ab/read-a]]} (fn [[a] _] (* 10 a)))
  (rf/dispatch-sync [:ab/init])
  nil)

(deftest reactive-data-subs-skipped-names-real-memo-hit
  (testing "a REAL memo hit (the layer-2 `:ab/derived-a` whose
            layer-1 input `:ab/read-a` recomputed to the SAME value after an
            unrelated `:b` bump) emits a canonical `:rf.sub/skip` that
            back-fills into the epoch and reaches `:rf.xray/reactive-data`'s
            `:subs-skipped` — kept DISTINCT from `:subs-ran` (which carries
            the value-changed? false recompute of `:ab/read-a`). No injection
            seam; drives the substrate's real emit-skip path."
    (e2e/with-host-and-xray-frames
      {:install-host install-ab-host!}
      (fn []
        (rf/with-frame e2e/default-host-frame
          (let [r (rf/subscribe [:ab/derived-a])]
            @r                                  ; prime the memo (first-run)
            (rf/dispatch-sync [:ab/bump-b] {:frame e2e/default-host-frame})
            @r                                  ; read-a recomputes (value 0, unchanged)
            @r))                                ; derived-a memo-hits → :rf.sub/skip
        (e2e/sync-xray-trace-mirror!)
        (e2e/sync-xray-epoch-history!)
        (let [d       (e2e/sub-xray [:rf.xray/reactive-data])
              skipped (:subs-skipped d)
              ran     (:subs-ran d)
              ran-ids (into #{} (map :sub-id) ran)
              skip-ids (into #{} (map :sub-id) skipped)]

          (let [row (some #(when (= :ab/derived-a (:sub-id %)) %) skipped)]
            (is (= [:input-value-equal [[:ab/read-a]]]
                   ((juxt :reason :input-paths-unchanged) row)))
            )
          ;; distinctness: the memo-hit sub is NOT in the run-set, and the
          ;; recompute that produced the same value stays in the run-set.
          (is (not (contains? ran-ids :ab/derived-a))
              ":ab/derived-a skipped — it must NOT appear in :subs-ran")
          (is (not (contains? skip-ids :ab/read-a))
              ":ab/read-a recomputed (it fired) — it must NOT appear in :subs-skipped")
          (is (pos? (-> d :counts :subs-skipped))
              ":counts :subs-skipped tallies the real memo-hit set"))))))

;; ---- static topology resolves through the OBSERVED frame -----------------

(def ^:private review-host :review/host)
(def ^:private review-inspector :review/inspector)

(defn- review-host-image
  "The observed app: a layer-1 `:review/base` and a DECLARED-INPUT
  `:review/derived` over it, registered ONLY in this image. Nothing registers
  either globally, so the host's topology is reachable only through the host
  frame's own generation."
  []
  (rf.image/image
    {:id            :review/host-image
     :registrations
     {:reg-sub [[:review/base {:file "review/host.cljs" :line 10}
                 (fn [db _q] (:base db))]
                [:review/derived {:inputs [[:review/base]]
                                  :file   "review/host.cljs" :line 20}
                 (fn [[base] _q] (inc base))]]}}))

(defn- review-inspector-image
  "The inspector, seated in its OWN image as Xray is: the ACTUAL
  `:rf.xray/reactive-data` composite (and its panel-local toggle input),
  selected from the namespace `install!` registered it under, plus inline
  stand-ins for its three other inputs. It also carries a SAME-ID
  `:review/derived` declared as a direct app-db reader, so an ambient
  topology read inside the composite finds the INSPECTOR's declaration."
  []
  (rf.image/image
    {:id            :review/inspector-image
     :select-ns     {:include ["day8.re-frame2-xray.panels.reactive-panel-subs"]}
     :registrations
     {:reg-sub [[:rf.xray/focus (fn [db _q] (:focus db))]
                [:rf.xray/epoch-history (fn [db _q] (:epoch-history db))]
                [:rf.xray/setting (fn [_db _q] false)]
                [:review/derived {:file "review/inspector.cljs" :line 99}
                 (fn [db _q] (:inspector db))]]}}))

(def ^:private review-epoch
  {:epoch-id    :review/e1
   :dispatch-id 1
   :sub-runs    [{:sub-id :review/base :query-v [:review/base]
                  :recomputed? true :value-changed? true}
                 {:sub-id :review/derived :query-v [:review/derived]
                  :recomputed? true :value-changed? true}]})

(deftest reactive-data-resolves-topology-through-the-observed-frame
  (testing "with DISJOINT host and inspector images, the composite
            partitions the host's subs by the HOST's declared topology: the
            derived sub is Level 2 with its input edge and host source
            coordinate, not a Level-1 app-db reader borrowed from the
            inspector's same-id declaration or defaulted from an absent one"
    (reactive-subs/install!)
    (rf.live-frame/make-frame {:id review-host :images [(review-host-image)]} [])
    (rf.live-frame/make-frame
      {:id             review-inspector
       :images         [(review-inspector-image)]
       :initial-events [[:rf/set-db {:focus         {:frame       review-host
                                                     :epoch-id    :review/e1
                                                     :dispatch-id 1}
                                     :epoch-history [review-epoch]}]]})
    (is (not (contains? (rf.registrar/registrations :sub) :review/derived))
        "control — the host's derived sub is absent from the global pool, so
         only the host frame's generation can classify it")
    (let [d (rf/with-frame review-inspector
              @(rf/subscribe [:rf.xray/reactive-data]))]
      (is (true? (:has-event-bundle? d))
          "precondition — the composite selected the focused host epoch")
      (is (= review-host (:frame d)))
      (is (= [:review/base] (mapv :sub-id (:level-1-subs d)))
          (str "only the host's layer-1 reader is Level 1; level-1-subs: "
               (pr-str (:level-1-subs d))))
      (is (= [{:sub-id         :review/derived
               :query-v        [:review/derived]
               :changed?       true
               :input-kind     :static
               :inputs         [:review/base]
               :input-query-vs [[:review/base]]
               :coord          {:file "review/host.cljs" :line 20 :ns nil}}]
             (:level-2-subs d))
          (str "the host's derived sub is Level 2 with the host's edge and "
               "source coordinate; level-2-subs: " (pr-str (:level-2-subs d)))))))
