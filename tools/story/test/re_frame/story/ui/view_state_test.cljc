(ns re-frame.story.ui.view-state-test
  "JVM-portable regression net for the View-State fidelity-controls' pure
  projection (rf2-ba86n.7, spec/019 §1/§5 + spec/017 §View-state
  subscription overrides).

  Covers the host-free surface — no host, no Reagent:

  - `fidelity-ladder`        — the fixed THREE labelled rungs, each
    marked active/inactive per the plan's `[:world :fidelity]`; the
    ladder is never collapsed to two.
  - `lowest-active-rank` / `upgrade-targets` — the upgrade path (stronger
    rungs above the floor), and that an upgrade keeps the artifact a
    variant (`upgrade-snippet` is a `reg-variant` `:extends` scaffold).
  - the provenance summaries (`setup-summary`, `network-summary`,
    `fx-overrides-summary`, `override-rows`) — source/provenance shown.
  - `sub-override-failures` — the live `:where :sub-override` schema-fail
    projection (the honesty surface for an override the real derivation
    could never produce).
  - `view-state-model` / `compile-model` — the composed model + error
    trapping over the pure plan compiler.

  CLJS render (the rung-view hiccup, the dialog, the guardrail) is left
  to a smoke / cljs test; this corpus pins the pure projection only."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story.ui.view-state :as vs]))

;; ---------------------------------------------------------------------------
;; Representative compiled-plan + explain fixtures, mirroring the slots the
;; plan compiler emits (re-frame.story.plan/build-plan): a pure design
;; variant (sub-overrides only), an events-driven variant, and a hybrid +
;; network + fx-override variant.
;; ---------------------------------------------------------------------------

(def ^:private design-plan
  "A pure design variant — pins one sub, no setup/seed → `#{:sub-overrides}`."
  {:variant/id :story.login/error
   :world {:fidelity #{:sub-overrides}
           :render   {:sub-overrides {[:login/state] :error
                                      [:login/msg]   "Invalid password"}}}})

(def ^:private design-explain
  {:sub-overrides {:overrides  {[:login/state] :error
                                [:login/msg]   "Invalid password"}
                   :validation {:status :ok :violations []}}
   :fidelity      #{:sub-overrides}})

(def ^:private events-plan
  "An events-driven variant — real setup → `#{:real-setup}`."
  {:variant/id :story.cart/with-items
   :world  {:fidelity #{:real-setup}
            :setup    [[:dispatch [:cart/add {:sku "A"}]]
                       [:dispatch [:cart/add {:sku "B"}]]]
            :frame    {:fx-overrides {:analytics/track (fn [_])
                                      :rf.http/managed :stub}}
            :network  {[:get "/api/cart"] {:reply {:ok {:items []}}}}}
   :script [[:dispatch [:cart/checkout]]]})

(def ^:private events-explain
  {:network  {:routes     {[:get "/api/cart"] {:reply {:ok {:items []}}}}
              :lowered-to {:rf.http/managed :rf.http/managed-test-stub}}
   :fidelity #{:real-setup}})

(def ^:private bare-plan
  "A render-as-mounted variant — no fidelity rung at all."
  {:variant/id :story.badge/default
   :world {}})

;; ---------------------------------------------------------------------------
;; fidelity-ladder — three DISTINCT rungs, never collapsed
;; ---------------------------------------------------------------------------

(deftest ladder-is-exactly-three-rungs-strongest-to-weakest
  (testing "the fidelity ladder is exactly the three rungs, ordered
            strongest → weakest — never collapsed to two"
    (is (= [:real-setup :db-seed :sub-overrides] vs/ladder-order))
    (is (= 3 (count vs/ladder-rungs)))
    (testing "each rung carries a distinct rank + tone + label"
      (is (= [1 2 3] (mapv :rank vs/ladder-rungs)))
      (is (= [:real :mid :low] (mapv :tone vs/ladder-rungs)))
      (is (= 3 (count (distinct (map :label vs/ladder-rungs))))))))

(deftest ladder-marks-active-rungs-keeps-inactive
  (testing "a pure design variant marks ONLY :sub-overrides active, keeps
            the stronger rungs as inactive upgrade targets (never dropped)"
    (let [ladder (vs/fidelity-ladder design-plan)
          by-rung (into {} (map (juxt :rung identity) ladder))]
      (is (= 3 (count ladder)) "all three rungs are always shown")
      (is (true?  (:active? (by-rung :sub-overrides))))
      (is (false? (:active? (by-rung :real-setup))))
      (is (false? (:active? (by-rung :db-seed))))))
  (testing "an events-driven variant marks :real-setup active"
    (let [by-rung (into {} (map (juxt :rung identity)
                                (vs/fidelity-ladder events-plan)))]
      (is (true?  (:active? (by-rung :real-setup))))
      (is (false? (:active? (by-rung :sub-overrides))))))
  (testing "a bare variant marks no rung active"
    (is (every? (complement :active?) (vs/fidelity-ladder bare-plan)))))

(deftest sub-overrides-rung-labelled-low-fidelity-never-proof
  (testing "the :sub-overrides rung is labelled lowest-fidelity and as
            proving nothing — the honest reading the surface surfaces"
    (let [r (first (filter #(= :sub-overrides (:rung %)) vs/ladder-rungs))]
      (is (= :low (:tone r)))
      (is (= 3 (:rank r)))
      (is (str/includes? (:note r) "never proof"))
      (is (str/includes? (:proves r) "nothing")))))

;; ---------------------------------------------------------------------------
;; the upgrade path — keeps the artifact a variant
;; ---------------------------------------------------------------------------

(deftest lowest-active-rank-is-the-fidelity-floor
  (is (= 3 (vs/lowest-active-rank design-plan)))
  (is (= 1 (vs/lowest-active-rank events-plan)))
  (testing "a hybrid floors at its WEAKEST active rung"
    (is (= 3 (vs/lowest-active-rank
               {:world {:fidelity #{:real-setup :sub-overrides}}}))))
  (testing "a bare variant has no floor"
    (is (nil? (vs/lowest-active-rank bare-plan)))))

(deftest upgrade-targets-are-the-stronger-rungs
  (testing "a pure sub-override variant can upgrade to db-seed + real-setup"
    (is (= [:real-setup :db-seed]
           (mapv :rung (vs/upgrade-targets design-plan)))))
  (testing "a real-setup variant has no stronger rung to upgrade to"
    (is (empty? (vs/upgrade-targets events-plan))))
  (testing "a bare variant has nothing to upgrade FROM"
    (is (empty? (vs/upgrade-targets bare-plan)))))

(deftest upgrade-snippet-keeps-the-artifact-a-variant
  (testing "the upgrade scaffold is a reg-variant :extends-ing the source
            — same artifact kind, drops :sub-overrides, adds the rung slot"
    (let [snip (vs/upgrade-snippet :story.login/error :real-setup)]
      (is (str/includes? snip "story/reg-variant")
          "stays a reg-variant — artifact kind unchanged")
      (is (str/includes? snip ":extends :story.login/error")
          "extends the source so component/decorators/args carry forward")
      (is (str/includes? snip ":setup")
          "adds the :real-setup authoring slot")
      (is (str/includes? snip "drop :sub-overrides"))))
  (testing "the db-seed upgrade scaffolds the :db-seed slot"
    (is (str/includes? (vs/upgrade-snippet :story.login/error :db-seed)
                       ":db-seed")))
  (testing "the derived upgraded id sits in the source's namespace"
    (is (= :story.login/error-upgraded
           (vs/upgraded-variant-id :story.login/error)))))

;; ---------------------------------------------------------------------------
;; provenance summaries — source shown
;; ---------------------------------------------------------------------------

(deftest setup-summary-shows-event-provenance
  (testing "the setup summary counts setup + script steps and names the
            dispatched event ids — where the state came from"
    (let [s (vs/setup-summary events-plan)]
      (is (true? (:present? s)))
      (is (= 2 (:setup-count s)))
      (is (= 1 (:script-count s)))
      (is (= [:cart/add :cart/add :cart/checkout] (:events s)))
      (is (= :events (:source s)))))
  (testing "a design variant has no setup provenance"
    (is (false? (:present? (vs/setup-summary design-plan))))))

(deftest network-summary-shows-route-provenance
  (let [n (vs/network-summary events-explain)]
    (is (true? (:present? n)))
    (is (= 1 (:route-count n)))
    (is (= [[:get "/api/cart"]] (:routes n)))
    (is (= {:rf.http/managed :rf.http/managed-test-stub} (:lowered-to n))))
  (testing "no routes → absent"
    (is (false? (:present? (vs/network-summary design-explain))))))

(deftest fx-overrides-summary-excludes-managed-http
  (testing "the fx-override summary lists non-HTTP fx overrides, EXCLUDING
            :rf.http/managed (the Network summary owns that route channel)"
    (let [f (vs/fx-overrides-summary events-plan)]
      (is (true? (:present? f)))
      (is (= 1 (:fx-count f)))
      (is (= [:analytics/track] (:fx-ids f)))
      (is (not (some #{:rf.http/managed} (:fx-ids f)))))))

(deftest override-rows-show-exact-query-vectors
  (testing "the override rows name the EXACT query vectors pinned + the
            pinned values, plus the plan-time output-schema validation"
    (let [o (vs/override-rows design-explain)]
      (is (true? (:present? o)))
      (is (= 2 (count (:rows o))))
      (is (= #{[:login/state] [:login/msg]}
             (set (map :query-v (:rows o)))))
      (is (= :ok (get-in o [:validation :status])))))
  (testing "no overrides → absent"
    (is (false? (:present? (vs/override-rows events-explain))))))

;; ---------------------------------------------------------------------------
;; live :where :sub-override schema-fail projection (the honesty surface)
;; ---------------------------------------------------------------------------

(deftest sub-override-failures-filters-the-where-sub-override-events
  (testing "only :rf.error/schema-validation-failure events whose :where
            is :sub-override are surfaced — the override-violated-its-
            output-schema honesty case (rf2-7pgiz fold-in)"
    (let [events [{:op-type :error :operation :rf.error/schema-validation-failure
                   :id 1 :time 100 :tags {:where :sub-override :sub-id :login/state
                                          :explain {:errors [{:path [] :message "bad"}]}}}
                  {:op-type :error :operation :rf.error/schema-validation-failure
                   :id 2 :time 200 :tags {:where :sub-return :sub-id :other}}
                  {:op-type :error :operation :rf.error/schema-validation-failure
                   :id 3 :time 300 :tags {:where :app-db}}
                  {:op-type :event :operation :some/event :id 4 :time 400 :tags {}}]
          fails (vs/sub-override-failures events)]
      (is (= 1 (count fails)) "only the :sub-override failure")
      (is (= :sub-override (:where (first fails))))
      (is (= :login/state (:failing-id (first fails))))))
  (testing "no failures → empty"
    (is (empty? (vs/sub-override-failures [])))))

;; ---------------------------------------------------------------------------
;; the composed model + error trapping
;; ---------------------------------------------------------------------------

(deftest view-state-model-composes-the-full-surface
  (testing "the composed model carries the ladder, provenance, overrides,
            failures, upgrade targets, and the low-fidelity flag"
    (let [m (vs/view-state-model design-plan design-explain [])]
      (is (= 3 (count (:ladder m))))
      (is (= 3 (:lowest-rank m)))
      (is (true? (:low-fidelity? m)) "sub-overrides is the floor")
      (is (true? (get-in m [:overrides :present?])))
      (is (= [:real-setup :db-seed] (mapv :rung (:upgrade-targets m))))))
  (testing "an events-driven plan is NOT low-fidelity and has no upgrade"
    (let [m (vs/view-state-model events-plan events-explain [])]
      (is (false? (:low-fidelity? m)))
      (is (empty? (:upgrade-targets m)))
      (is (true? (get-in m [:network :present?]))))))

(deftest compile-model-traps-unknown-variant
  (testing "an unregistered keyword target surfaces :error, not a throw"
    (let [m (vs/compile-model :story.nope/missing [])]
      (is (string? (:error m)))
      (is (not (str/blank? (:error m)))))))
