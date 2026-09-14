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
            [#?(:clj clojure.edn :cljs cljs.reader) :as edn]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.view-state :as rf.story.ui.view-state]))

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
    (is (= [:real-setup :db-seed :sub-overrides] rf.story.ui.view-state/ladder-order))
    (is (= 3 (count rf.story.ui.view-state/ladder-rungs)))
    (testing "each rung carries a distinct rank + tone + label"
      (is (= [1 2 3] (mapv :rank rf.story.ui.view-state/ladder-rungs)))
      (is (= [:real :mid :low] (mapv :tone rf.story.ui.view-state/ladder-rungs)))
      (is (= 3 (count (distinct (map :label rf.story.ui.view-state/ladder-rungs))))))))

(deftest ladder-marks-active-rungs-keeps-inactive
  (testing "a pure design variant marks ONLY :sub-overrides active, keeps
            the stronger rungs as inactive upgrade targets (never dropped)"
    (let [ladder (rf.story.ui.view-state/fidelity-ladder design-plan)
          by-rung (into {} (map (juxt :rung identity) ladder))]
      (is (= 3 (count ladder)) "all three rungs are always shown")
      (is (true?  (:active? (by-rung :sub-overrides))))
      (is (false? (:active? (by-rung :real-setup))))
      (is (false? (:active? (by-rung :db-seed))))))
  (testing "an events-driven variant marks :real-setup active"
    (let [by-rung (into {} (map (juxt :rung identity)
                                (rf.story.ui.view-state/fidelity-ladder events-plan)))]
      (is (true?  (:active? (by-rung :real-setup))))
      (is (false? (:active? (by-rung :sub-overrides))))))
  (testing "a bare variant marks no rung active"
    (is (every? (complement :active?) (rf.story.ui.view-state/fidelity-ladder bare-plan)))))

(deftest sub-overrides-rung-labelled-low-fidelity-never-proof
  (testing "the :sub-overrides rung is labelled lowest-fidelity and as
            proving nothing — the honest reading the surface surfaces"
    (let [r (first (filter #(= :sub-overrides (:rung %)) rf.story.ui.view-state/ladder-rungs))]
      (is (= :low (:tone r)))
      (is (= 3 (:rank r)))
      (is (str/includes? (:note r) "never proof"))
      (is (str/includes? (:proves r) "nothing")))))

;; ---------------------------------------------------------------------------
;; the upgrade path — keeps the artifact a variant
;; ---------------------------------------------------------------------------

(deftest lowest-active-rank-is-the-fidelity-floor
  (is (= 3 (rf.story.ui.view-state/lowest-active-rank design-plan)))
  (is (= 1 (rf.story.ui.view-state/lowest-active-rank events-plan)))
  (testing "a hybrid floors at its WEAKEST active rung"
    (is (= 3 (rf.story.ui.view-state/lowest-active-rank
               {:world {:fidelity #{:real-setup :sub-overrides}}}))))
  (testing "a bare variant has no floor"
    (is (nil? (rf.story.ui.view-state/lowest-active-rank bare-plan)))))

(deftest upgrade-targets-are-the-stronger-rungs
  (testing "a pure sub-override variant can upgrade to db-seed + real-setup"
    (is (= [:real-setup :db-seed]
           (mapv :rung (rf.story.ui.view-state/upgrade-targets design-plan)))))
  (testing "a real-setup variant has no stronger rung to upgrade to"
    (is (empty? (rf.story.ui.view-state/upgrade-targets events-plan))))
  (testing "a bare variant has nothing to upgrade FROM"
    (is (empty? (rf.story.ui.view-state/upgrade-targets bare-plan)))))

(def ^:private upgrade-lookup
  "Raw variant bodies in the side-table shape — `:extends` intact, parents
  unmerged, the registrar's `:source` coords stamp included — for the
  `upgrade-snippet` tests. `:story.login/error` pins a sub on top of a
  pin-free parent; `:story.login/locked` inherits that pin one layer down;
  `:story.login/seeded` pins nothing."
  {:story.login/base   {:args {:heading "Sign in"}}
   :story.login/error  {:extends       :story.login/base
                        :doc           "wrong password"
                        :args          {:message "Invalid password"}
                        :sub-overrides {[:login/state] :error}
                        :source        {:ns 'story.login :file "stories.cljs" :line 12 :column 1}}
   :story.login/locked {:extends :story.login/error
                        :args    {:message "Locked out"}}
   :story.login/seeded {:db-seed {:login {:state :idle}}}})

(defn- read-upgrade
  "Read the snippet `upgrade-snippet` emits back as EDN → `(op id body)`."
  [source-id rung]
  (edn/read-string
    (rf.story.ui.view-state/upgrade-snippet source-id rung {:lookup upgrade-lookup})))

(deftest upgrade-snippet-reads-back-as-one-reg-variant-form
  (testing "every shape the generator emits parses — the rung note sits on its
            own line, never after the body's last value, where `;` would
            swallow the envelope's closing `})` (rf2-mw9th half 1)"
    (doseq [[source-id rung] [[:story.login/error :real-setup]
                              [:story.login/error :db-seed]
                              [:story.login/locked :real-setup]
                              [:story.login/seeded :real-setup]
                              [:story.nope/unregistered :real-setup]]]
      (let [[op id body] (read-upgrade source-id rung)]
        (is (= 'story/reg-variant op) (str source-id " → " rung))
        (is (= (rf.story.ui.view-state/upgraded-variant-id source-id) id))
        (is (map? body))))))

(deftest upgrade-snippet-drops-the-pin-instead-of-extending-the-pinned-source
  (testing "the upgrade scaffold stays a reg-variant and adds the rung slot,
            but does NOT :extends the pinned source — :extends inherits
            :sub-overrides, so extending it would keep the pin (rf2-mw9th
            half 2; this test used to pin `:extends <source>`)"
    (let [[op id body] (read-upgrade :story.login/error :real-setup)]
      (is (= 'story/reg-variant op) "stays a reg-variant — artifact kind unchanged")
      (is (= :story.login/error-upgraded id))
      (is (= :story.login/base (:extends body))
          "extends the nearest pin-free ancestor, never the pinned source")
      (is (not (contains? body :sub-overrides)) "the pin is dropped")
      (is (= {:message "Invalid password"} (:args body))
          "the source's own slots carry forward")
      (is (= "wrong password" (:doc body)))
      (is (not (contains? body :source)) "the registrar's coords stamp is not re-emitted")
      (is (= [[:dispatch [:your/setup-event {}]]] (:setup body))
          "adds the :real-setup authoring slot")))
  (testing "the db-seed upgrade scaffolds the :db-seed slot"
    (is (= {} (:db-seed (nth (read-upgrade :story.login/error :db-seed) 2)))))
  (testing "a pin inherited from higher up the chain — extend above it, name
            the pinned layer that is not carried"
    (let [snip (rf.story.ui.view-state/upgrade-snippet :story.login/locked :real-setup
                                                       {:lookup upgrade-lookup})
          body (nth (edn/read-string snip) 2)]
      (is (= :story.login/base (:extends body)))
      (is (= {:message "Locked out"} (:args body)))
      (is (not (contains? body :sub-overrides)))
      (is (str/includes? snip ";; not carried: :story.login/error"))))
  (testing "a source that pins nothing is still extended, so its context flows down"
    (is (= :story.login/seeded
           (:extends (nth (read-upgrade :story.login/seeded :real-setup) 2)))))
  (testing "the derived upgraded id sits in the source's namespace"
    (is (= :story.login/error-upgraded
           (rf.story.ui.view-state/upgraded-variant-id :story.login/error)))))

(deftest completed-upgrade-compiles-without-the-sub-overrides-rung
  (testing "the COMPLETED scaffold compiles to a plan resting on real setup
            alone — graded on the compiled artifact, not the snippet text"
    (let [[_ id body] (read-upgrade :story.login/error :real-setup)
          completed   (assoc body :setup [[:dispatch [:login/submit {:password "x"}]]])
          lookup      (assoc upgrade-lookup id completed)]
      (testing "control — the source is a pinned picture"
        (is (contains? (get-in (rf.story.plan/variant-plan :story.login/error
                                                           {:lookup upgrade-lookup})
                               [:world :fidelity])
                       :sub-overrides)))
      (let [plan (rf.story.plan/variant-plan id {:lookup lookup})]
        (is (= #{:real-setup} (get-in plan [:world :fidelity])))
        (is (= {:heading "Sign in" :message "Invalid password"} (get-in plan [:world :args]))
            "context from the extended ancestor and the source both reach the plan")))))

;; ---------------------------------------------------------------------------
;; provenance summaries — source shown
;; ---------------------------------------------------------------------------

(deftest setup-summary-shows-event-provenance
  (testing "the setup summary counts setup + script steps and names the
            dispatched event ids — where the state came from"
    (let [s (rf.story.ui.view-state/setup-summary events-plan)]
      (is (true? (:present? s)))
      (is (= 2 (:setup-count s)))
      (is (= 1 (:script-count s)))
      (is (= [:cart/add :cart/add :cart/checkout] (:events s)))
      (is (= :events (:source s)))))
  (testing "a design variant has no setup provenance"
    (is (false? (:present? (rf.story.ui.view-state/setup-summary design-plan))))))

(deftest network-summary-shows-route-provenance
  (let [n (rf.story.ui.view-state/network-summary events-explain)]
    (is (true? (:present? n)))
    (is (= 1 (:route-count n)))
    (is (= [[:get "/api/cart"]] (:routes n)))
    (is (= {:rf.http/managed :rf.http/managed-test-stub} (:lowered-to n))))
  (testing "no routes → absent"
    (is (false? (:present? (rf.story.ui.view-state/network-summary design-explain))))))

(deftest fx-overrides-summary-excludes-managed-http
  (testing "the fx-override summary lists non-HTTP fx overrides, EXCLUDING
            :rf.http/managed (the Network summary owns that route channel)"
    (let [f (rf.story.ui.view-state/fx-overrides-summary events-plan)]
      (is (true? (:present? f)))
      (is (= 1 (:fx-count f)))
      (is (= [:analytics/track] (:fx-ids f)))
      (is (not (some #{:rf.http/managed} (:fx-ids f)))))))

(deftest override-rows-show-exact-query-vectors
  (testing "the override rows name the EXACT query vectors pinned + the
            pinned values, plus the plan-time output-schema validation"
    (let [o (rf.story.ui.view-state/override-rows design-explain)]
      (is (true? (:present? o)))
      (is (= 2 (count (:rows o))))
      (is (= #{[:login/state] [:login/msg]}
             (set (map :query-v (:rows o)))))
      (is (= :ok (get-in o [:validation :status])))))
  (testing "no overrides → absent"
    (is (false? (:present? (rf.story.ui.view-state/override-rows events-explain))))))

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
          fails (rf.story.ui.view-state/sub-override-failures events)]
      (is (= 1 (count fails)) "only the :sub-override failure")
      (is (= :sub-override (:where (first fails))))
      (is (= :login/state (:failing-id (first fails))))))
  (testing "no failures → empty"
    (is (empty? (rf.story.ui.view-state/sub-override-failures [])))))

;; ---------------------------------------------------------------------------
;; the composed model + error trapping
;; ---------------------------------------------------------------------------

(deftest view-state-model-composes-the-full-surface
  (testing "the composed model carries the ladder, provenance, overrides,
            failures, upgrade targets, and the low-fidelity flag"
    (let [m (rf.story.ui.view-state/view-state-model design-plan design-explain [])]
      (is (= 3 (count (:ladder m))))
      (is (= 3 (:lowest-rank m)))
      (is (true? (:low-fidelity? m)) "sub-overrides is the floor")
      (is (true? (get-in m [:overrides :present?])))
      (is (= [:real-setup :db-seed] (mapv :rung (:upgrade-targets m))))))
  (testing "an events-driven plan is NOT low-fidelity and has no upgrade"
    (let [m (rf.story.ui.view-state/view-state-model events-plan events-explain [])]
      (is (false? (:low-fidelity? m)))
      (is (empty? (:upgrade-targets m)))
      (is (true? (get-in m [:network :present?]))))))

(deftest compile-model-traps-unknown-variant
  (testing "an unregistered keyword target surfaces :error, not a throw"
    (let [m (rf.story.ui.view-state/compile-model :story.nope/missing [])]
      (is (string? (:error m)))
      (is (not (str/blank? (:error m)))))))

;; ---------------------------------------------------------------------------
;; compile-model — the ambient arg layers (rf2-851t0)
;;
;; The run resolves an `[:arg key]` through global-args and the parent
;; story's `:args`; the pure compiler folds those only when handed
;; `:run-args`. The panel must compile the variant the way it runs, or a
;; variant that runs fine shows a missing-arg error.
;; ---------------------------------------------------------------------------

(deftest compile-model-folds-story-and-global-args
  (let [globals-before (rf.story.config/get-global-args)]
    (rf.story.registrar/clear-all!)
    (try
      (rf.story.registrar/reg-story*   :story.vs.args {:args {:heading "Sign in"}})
      (rf.story.registrar/reg-variant* :story.vs.args/uses-story-arg
                                       {:setup [[:dispatch [:vs/set [:arg :heading]]]]})
      (rf.story.registrar/reg-variant* :story.vs.args/uses-global-arg
                                       {:setup [[:dispatch [:vs/set [:arg :theme]]]]})
      (testing "a placeholder only the STORY resolves compiles, as it runs"
        (let [m (rf.story.ui.view-state/compile-model :story.vs.args/uses-story-arg [])]
          (is (nil? (:error m)) (str "expected clean compile, got: " (:error m)))
          (is (= [:vs/set] (get-in m [:setup :events]))
              "non-vacuity: the compiled model carries the variant's setup")))
      (testing "a placeholder only GLOBAL-args resolves compiles too"
        (rf.story.config/set-global-args! {:theme :dark})
        (let [m (rf.story.ui.view-state/compile-model :story.vs.args/uses-global-arg [])]
          (is (nil? (:error m)) (str "expected clean compile, got: " (:error m)))))
      (testing "the pure compiler stays explicit — the fold is the panel's"
        (is (thrown-with-msg?
              #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo)
              #"story-missing-arg"
              (rf.story.plan/variant-plan :story.vs.args/uses-story-arg))))
      (finally
        (rf.story.config/set-global-args! globals-before)
        (rf.story.registrar/clear-all!)))))
