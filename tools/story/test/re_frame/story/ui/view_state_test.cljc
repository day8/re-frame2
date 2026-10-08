(ns re-frame.story.ui.view-state-test
  "The View-State section's host-free projection (spec/019 §5): the
  fidelity ladder, the upgrade path, the provenance summaries and the
  composed model."
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
  (testing "exactly the three rungs, strongest → weakest, each with a
            distinct rank, tone and label — never collapsed to two"
    (is (= [:real-setup :db-seed :sub-overrides] rf.story.ui.view-state/ladder-order))
    (is (= [[1 :real] [2 :mid] [3 :low]]
           (mapv (juxt :rank :tone) rf.story.ui.view-state/ladder-rungs)))
    (is (apply distinct? (map :label rf.story.ui.view-state/ladder-rungs)))))

(deftest ladder-marks-active-rungs-keeps-inactive
  (testing "a pure design variant marks ONLY :sub-overrides active and keeps
            the stronger rungs as inactive upgrade targets"
    (is (= [false false true]
           (mapv :active? (rf.story.ui.view-state/fidelity-ladder design-plan))))))

;; ---------------------------------------------------------------------------
;; the upgrade path — keeps the artifact a variant
;; ---------------------------------------------------------------------------

(deftest lowest-active-rank-is-the-fidelity-floor
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
   :story.login/seeded {:db-seed {:login {:state :idle}}}
   ;; `:compose` is child-only, so a composed pin reaches the upgrade only
   ;; when the scaffold RE-EMITS the source's slots — i.e. when the source
   ;; also pins directly. These two are that mixed shape.
   :story.login/composed-pin     {:sub-overrides {[:login/own] "own pin"}
                                  :compose       [:fragment.login/pinned-email]
                                  :args          {:heading "context"}}
   :story.login/composed-context {:sub-overrides {[:login/own] "own pin"}
                                  :compose       [:fragment.login/context]}
   ;; A pinned layer carrying the fx-stub decorator its real events need,
   ;; under a source that declares no decorators of its own, and a sibling
   ;; source that does declare its own.
   :story.login/stubbed-pin      {:extends       :story.login/base
                                  :decorators    [[:story.login/fx-stub :rf.http/managed {}]]
                                  :sub-overrides {[:login/state] :authenticated}}
   :story.login/stubbed-child    {:extends :story.login/stubbed-pin
                                  :args    {:heading "Welcome back"}}
   :story.login/own-decorators   {:extends    :story.login/stubbed-pin
                                  :decorators [[:story.login/theme]]}})

(def ^:private upgrade-fragments
  "Raw fragment bodies for the `:compose` ids above: one pins a subscription,
  one carries only context."
  {:fragment.login/pinned-email {:sub-overrides {[:login/email] "PINNED"}}
   :fragment.login/context      {:args {:subtitle "from the fragment"}}})

(defn- emit-upgrade
  "The snippet `upgrade-snippet` emits for `source-id` against the raw lookups."
  [source-id rung]
  (rf.story.ui.view-state/upgrade-snippet source-id rung {:lookup          upgrade-lookup
                                                          :fragment-lookup upgrade-fragments}))

(defn- read-upgrade
  "Read the snippet `upgrade-snippet` emits back as EDN → `(op id body)`."
  [source-id rung]
  (edn/read-string (emit-upgrade source-id rung)))

(defn- compile-completed-upgrade
  "Complete the upgrade scaffold for `source-id` the way an author does —
  fill the `:setup` slot — and compile the child against the same raw
  lookups. Returns `[emitted-body plan]`."
  [source-id]
  (let [[_ id body] (read-upgrade source-id :real-setup)
        completed   (assoc body :setup [[:dispatch [:login/set-email "REAL"]]])]
    [body (rf.story.plan/variant-plan id {:lookup          (assoc upgrade-lookup id completed)
                                          :fragment-lookup upgrade-fragments})]))

(deftest upgrade-snippet-reads-back-as-one-reg-variant-form
  (testing "every shape the generator emits parses — the rung note sits on its
            own line, never after the body's last value, where `;` would
            swallow the envelope's closing `})`"
    (doseq [[source-id rung] [[:story.login/error :real-setup]
                              [:story.login/error :db-seed]
                              [:story.login/locked :real-setup]
                              [:story.login/seeded :real-setup]
                              [:story.login/composed-pin :real-setup]
                              [:story.login/composed-context :real-setup]
                              [:story.nope/unregistered :real-setup]]]
      (let [[op id body] (read-upgrade source-id rung)]
        (is (= 'rf.story/reg-variant op) (str source-id " → " rung))
        (is (= (rf.story.ui.view-state/upgraded-variant-id source-id) id))
        (is (map? body))))))

(deftest upgrade-snippet-drops-the-pin-instead-of-extending-the-pinned-source
  (testing "the upgrade scaffold stays a reg-variant and adds the rung slot,
            but does NOT :extends the pinned source — :extends inherits
            :sub-overrides, so extending it would keep the pin"
    (let [[op id body] (read-upgrade :story.login/error :real-setup)]
      (is (= 'rf.story/reg-variant op) "stays a reg-variant — artifact kind unchanged")
      (is (= :story.login/error-upgraded id))
      (is (= {:extends :story.login/base
              :doc     "wrong password"
              :args    {:message "Invalid password"}
              :setup   [[:dispatch [:your/setup-event {}]]]}
             body)
          "extends the nearest pin-free ancestor, carries the source's own
           slots, adds :setup — and drops the pin and the :source stamp")))
  (testing "the db-seed upgrade scaffolds the :db-seed slot"
    (is (= {} (:db-seed (nth (read-upgrade :story.login/error :db-seed) 2)))))
  (testing "a pin inherited from higher up the chain — extend above it, name
            the pinned layer that is not carried"
    (let [snip (rf.story.ui.view-state/upgrade-snippet :story.login/locked :real-setup
                                                       {:lookup upgrade-lookup})]
      (is (= {:extends :story.login/base
              :args    {:message "Locked out"}
              :setup   [[:dispatch [:your/setup-event {}]]]}
             (nth (edn/read-string snip) 2)))
      (is (str/includes? snip ";; not carried: :story.login/error"))))
  (testing "a source that pins nothing is still extended, so its context flows down"
    (is (= :story.login/seeded
           (:extends (nth (read-upgrade :story.login/seeded :real-setup) 2))))))

(deftest completed-upgrade-compiles-without-the-sub-overrides-rung
  (testing "the COMPLETED scaffold compiles to a plan resting on real setup
            alone — graded on the compiled artifact, not the snippet text"
    (testing "control — the source is a pinned picture"
      (is (contains? (get-in (rf.story.plan/variant-plan :story.login/error
                                                         {:lookup upgrade-lookup})
                             [:world :fidelity])
                     :sub-overrides)))
    (let [[_ plan] (compile-completed-upgrade :story.login/error)]
      (is (= #{:real-setup} (get-in plan [:world :fidelity])))
      (is (= {:heading "Sign in" :message "Invalid password"} (get-in plan [:world :args]))
          "context from the extended ancestor and the source both reach the plan"))))

(deftest completed-upgrade-drops-pins-composed-from-fragments
  (testing "a source that pins directly AND composes a pinning fragment: the
            scaffold re-emits the source's own slots, so a `:compose` copied
            as-is would carry the fragment's pin into the child"
    (testing "control — the source rests on both pins"
      (is (= {[:login/own] "own pin" [:login/email] "PINNED"}
             (get-in (rf.story.plan/variant-plan :story.login/composed-pin
                                                 {:lookup          upgrade-lookup
                                                  :fragment-lookup upgrade-fragments})
                     [:world :render :sub-overrides]))))
    (let [[body plan] (compile-completed-upgrade :story.login/composed-pin)]
      (is (= #{:real-setup} (get-in plan [:world :fidelity])))
      (is (empty? (get-in plan [:world :render :sub-overrides]))
          "no pinned subscription reaches the child's render path")
      (is (not (contains? body :compose)) "the pinning fragment is dropped from :compose")
      (is (= {:heading "context"} (get-in plan [:world :args]))
          "the source's own context carries forward")
      (is (str/includes? (emit-upgrade :story.login/composed-pin :real-setup)
                         ";; not composed: :fragment.login/pinned-email (pins [:login/email])")
          "the dropped fragment and its pin are named for the author"))))

(deftest completed-upgrade-keeps-pin-free-composed-fragments
  (testing "control — a composed fragment that pins nothing stays in :compose,
            and its context still reaches the completed child"
    (let [[body plan] (compile-completed-upgrade :story.login/composed-context)]
      (is (= [:fragment.login/context] (:compose body)))
      (is (= #{:real-setup} (get-in plan [:world :fidelity])))
      (is (= {:subtitle "from the fragment"} (get-in plan [:world :args])))
      (is (not (str/includes? (emit-upgrade :story.login/composed-context :real-setup)
                              "not composed"))
          "nothing was dropped, so nothing is named"))))

(deftest completed-upgrade-carries-decorators-from-a-skipped-pinned-layer
  (testing "extending above a pinned layer must not strip the decorators that
            layer supplied — the fx stubs real setup events need.
            `:decorators` merge child-wins, so the nearest skipped layer's are
            copied when the source declares none"
    (let [stub        [[:story.login/fx-stub :rf.http/managed {}]]
          source-plan (rf.story.plan/variant-plan :story.login/stubbed-child
                                                  {:lookup upgrade-lookup})]
      (testing "control — the pinned source runs under the pinned layer's decorator"
        (is (= stub (get-in source-plan [:world :decorators]))))
      (let [[body plan] (compile-completed-upgrade :story.login/stubbed-child)]
        (is (= :story.login/base (:extends body)) "still extends above the pin")
        (is (= stub (:decorators body)) "the skipped layer's decorators are copied")
        (is (= (get-in source-plan [:world :decorators]) (get-in plan [:world :decorators]))
            "the completed child runs under the source's decorator stack")
        (is (= #{:real-setup} (get-in plan [:world :fidelity])))))
    (testing "a source that declares its own decorators keeps them, and nothing is copied over them"
      (is (= [[:story.login/theme]]
             (:decorators (nth (read-upgrade :story.login/own-decorators :real-setup) 2)))))))

;; ---------------------------------------------------------------------------
;; provenance summaries — source shown
;; ---------------------------------------------------------------------------

(deftest setup-summary-shows-event-provenance
  (testing "the setup summary counts setup + script steps and names the
            dispatched event ids — where the state came from"
    (is (= {:rung         :real-setup
            :present?     true
            :setup-count  2
            :script-count 1
            :events       [:cart/add :cart/add :cart/checkout]
            :source       :events}
           (rf.story.ui.view-state/setup-summary events-plan))))
  (testing "a design variant has no setup provenance"
    (is (false? (:present? (rf.story.ui.view-state/setup-summary design-plan))))))

(deftest network-summary-shows-route-provenance
  (is (= {:present?    true
          :route-count 1
          :routes      [[:get "/api/cart"]]
          :lowered-to  {:rf.http/managed :rf.http/managed-test-stub}}
         (rf.story.ui.view-state/network-summary events-explain)))
  (testing "no routes → absent"
    (is (false? (:present? (rf.story.ui.view-state/network-summary design-explain))))))

(deftest fx-overrides-summary-excludes-managed-http
  (testing "the fx-override summary lists non-HTTP fx overrides, EXCLUDING
            :rf.http/managed (the Network summary owns that route channel)"
    (is (= {:present? true :fx-count 1 :fx-ids [:analytics/track]}
           (rf.story.ui.view-state/fx-overrides-summary events-plan)))))

(deftest override-rows-show-exact-query-vectors
  (testing "the override rows name the EXACT query vectors pinned + the
            pinned values, plus the plan-time output-schema validation"
    (is (= {:present?   true
            :rows       #{{:query-v [:login/state] :value :error}
                          {:query-v [:login/msg] :value "Invalid password"}}
            :validation {:status :ok :violations []}}
           (update (rf.story.ui.view-state/override-rows design-explain) :rows set))))
  (testing "no overrides → absent"
    (is (false? (:present? (rf.story.ui.view-state/override-rows events-explain))))))

;; ---------------------------------------------------------------------------
;; live :where :sub-override schema-fail projection (the honesty surface)
;; ---------------------------------------------------------------------------

(deftest sub-override-failures-filters-the-where-sub-override-events
  (testing "only :rf.error/schema-validation-failure events whose :where
            is :sub-override are surfaced — the override-violated-its-
            output-schema honesty case"
    (let [events [{:op-type :error :operation :rf.error/schema-validation-failure
                   :id 1 :time 100 :tags {:where :sub-override :sub-id :login/state
                                          :explain {:errors [{:path [] :message "bad"}]}}}
                  {:op-type :error :operation :rf.error/schema-validation-failure
                   :id 2 :time 200 :tags {:where :sub-return :sub-id :other}}
                  {:op-type :error :operation :rf.error/schema-validation-failure
                   :id 3 :time 300 :tags {:where :app-db}}
                  {:op-type :event :operation :some/event :id 4 :time 400 :tags {}}]
          fails (rf.story.ui.view-state/sub-override-failures events)]
      (is (= [[:sub-override :login/state]]
             (mapv (juxt :where :failing-id) fails))
          "only the :sub-override failure"))))

;; ---------------------------------------------------------------------------
;; the composed model + error trapping
;; ---------------------------------------------------------------------------

(deftest view-state-model-composes-the-full-surface
  (testing "the composed model carries the ladder, provenance, overrides,
            failures, upgrade targets, and the low-fidelity flag"
    (let [m (rf.story.ui.view-state/view-state-model design-plan design-explain [])]
      (is (= 3 (count (:ladder m))))
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
;; compile-model — the ambient arg layers
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
