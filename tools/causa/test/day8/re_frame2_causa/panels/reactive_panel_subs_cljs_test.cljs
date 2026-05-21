(ns day8.re-frame2-causa.panels.reactive-panel-subs-cljs-test
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
            [day8.re-frame2-causa.panels.reactive-panel-subs :as subs]))

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
   :tags (cond-> {:view-id view-id :render-key [view-id 0] :mount? mount?}
           (some? deref-subs) (assoc :deref-subs deref-subs))})

(defn- unmounted-ev [view-id]
  {:operation :rf.view/unmounted
   :tags {:view-id view-id :render-key [view-id 0]}})

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
    (let [events [{:operation :rf.view/rendered :tags {:mount? true}} ; no view-id
                  {:operation :sub/run :tags {:sub-id :s1}}           ; not a view op
                  (rendered-ev :v/ok true nil)]
          rows   (subs/view-rows events #{})]
      (is (= 1 (count rows)))
      (is (= :v/ok (-> rows first :view-id))))))

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
      (is (= 2 (-> p :counts :view-rows))))))

(deftest project-record-degrades-without-topology
  (testing "rf2-8ve8z — nil topology: every sub falls to Level 1; the
            panel still renders (no crash)."
    (let [record {:sub-runs [(sub-run+ :a true true) (sub-run+ :b true false)]}
          p (subs/project-record record)]
      (is (= [:a :b] (mapv :sub-id (:level-1-subs p))))
      (is (empty? (:level-2-subs p))))))
