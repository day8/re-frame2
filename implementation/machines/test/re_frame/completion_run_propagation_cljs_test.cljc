(ns re-frame.completion-run-propagation-cljs-test
  "rf2-ix8fd — run propagation crosses the machine COMPLETION edge.

  rf2-gbzv9 carried Spec 002 §Run propagation across the SPAWN edge: the
  newborn actor's first event inherits the spawning envelope. The way BACK is
  two reserved carriers the runtime mints into the spawning parent when a
  child finishes: `[:rf.machine.spawn/done …]` (a `:final?` leaf, either spawn
  form) and `[:rf.machine.spawn/error …]` (an `:error?` leaf, or an uncaught
  action exception, under a parent declaring `:spawn :on-error`). Both used to
  be FRESH router dispatches carrying only `{:frame … :source :machine-spawn}`.
  So a per-call override reached every fx a child fired, but NOT the fx the
  parent fired on resuming.

  A carrier is minted while the child's handler processes the event that
  FINISHED it, so it is a child of THAT event. Pinned here:

   1. a per-call `:fx-overrides` on `dispatch-sync` into the parent reaches the
      fx its continuation fires. That covers a `:spawn` + `:on-done` advance, a
      `:spawn-all` `:on-all-complete`, and an `:on-error` from an error leaf and
      from an action exception. `:origin` / `:trace-id` arrive too;
   2. every carrier keeps `:source :machine-spawn` and is NOT machine-internal,
      so it keeps its FIFO place;
   3. the lineage is the FINISHING event's. A child finished by a separate
      event carrying an override hands that override to the parent, while a
      child finished by a plain event hands over nothing, even when its SPAWN
      carried one;
   4. a per-frame `:fx-overrides` still reaches the continuation, and with no
      override at all the real fx runs (the probe is honest).

  Named `*-cljs-test.cljc` so BOTH the JVM run and the shadow-cljs node run
  discover it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.late-bind :as rf.late-bind]
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

;; ---------------------------------------------------------------------------
;; Fixtures under test.
;; ---------------------------------------------------------------------------

(def ^:private stub-overrides {:cp/probe :cp/probe.stub})

(defn- register-probe!
  "Register the probe fx and its stub; return the atom both record into. The
  stub also records the dispatch envelope it ran under, read off `(:envelope
  m)` — the production-visible surface, so this holds in both postures."
  []
  (let [fired (atom [])]
    (rf/reg-fx :cp/probe
      (fn [_ args] (swap! fired conj (assoc args :via :real))))
    (rf/reg-fx :cp/probe.stub
      (fn [m args]
        (let [env (:envelope m)]
          (swap! fired conj (assoc args
                                   :via      :stub
                                   :origin   (:origin env)
                                   :trace-id (:trace-id env))))))
    fired))

(defn- register-machines!
  "Children fire NO probe, so every probe hit is the PARENT's continuation. The
  child finishes on its `:start`: `:finish` reaches a plain `:final?` leaf,
  `:fail` an `:error?` leaf, and `:boom` throws from an action. `:cp/idler` has
  no `:start` and waits for an external `:finish`."
  []
  (rf/reg-machine :cp/child
    {:initial :running
     :states  {:running {:on {:finish :done
                              :fail   :failed
                              :boom   {:action (fn [_] (throw (ex-info "cp/boom" {})))}}}
               :done    {:final? true}
               :failed  {:final? true :error? true}}})
  (letfn [(on-done-parent [start]
            {:initial :idle
             :data    {}
             :states  {:idle     {:on {:go :waiting}}
                       :waiting  {:spawn  (cond-> {:machine-id (if start :cp/child :cp/idler)
                                                   :on-done    (fn [{d :data}] (assoc d :child-done? true))}
                                            start (assoc :start start))
                                  :always {:guard  (fn [{d :data}] (true? (:child-done? d)))
                                           :target :finished}}
                       :finished {:entry (fn [_] {:fx [[:cp/probe {:at :on-done}]]})}}})
          (on-error-parent [start]
            {:initial :idle
             :states  {:idle    {:on {:go :waiting}}
                       :waiting {:spawn {:machine-id :cp/child
                                         :start      start
                                         :on-error   {:target :errored}}}
                       :errored {:entry (fn [_] {:fx [[:cp/probe {:at :on-error}]]})}}})]
    (rf/reg-machine :cp/idler
      {:initial :running
       :states  {:running {:on {:finish :done}}
                 :done    {:final? true}}})
    (rf/reg-machine :cp/done-parent  (on-done-parent [:finish]))
    (rf/reg-machine :cp/idle-parent  (on-done-parent nil))
    (rf/reg-machine :cp/error-parent (on-error-parent [:fail]))
    (rf/reg-machine :cp/throw-parent (on-error-parent [:boom]))
    (rf/reg-machine :cp/all-parent
      {:initial :idle
       :states  {:idle    {:on {:go :forking}}
                 :forking {:spawn-all {:children        [{:id :a :machine-id :cp/child :start [:finish]}
                                                         {:id :b :machine-id :cp/child :start [:finish]}]
                                       :join            :all
                                       :on-all-complete [:all/done]}
                           :on {:all/done :ready}}
                 :ready   {:entry (fn [_] {:fx [[:cp/probe {:at :on-all-complete}]]})}}})))

(defn- with-dispatch-observer
  "Run `body-fn` with `:router/dispatch!` wrapped by a PASS-THROUGH observer
  recording every `[event opts]` into `sink`, then delegating to the real hook.
  Restores in a `finally`."
  [sink body-fn]
  (let [real (rf.late-bind/get-fn :router/dispatch!)]
    (try
      (rf.late-bind/set-fn! :router/dispatch!
                            (fn [event opts]
                              (swap! sink conj [event opts])
                              (real event opts)))
      (body-fn)
      (finally
        (rf.late-bind/set-fn! :router/dispatch! real)))))

(def ^:private carrier-ids #{:rf.machine.spawn/done :rf.machine.spawn/error})

(defn- carrier-opts
  "`[carrier-id opts]` for every observed completion carrier — an event
  `[<parent-id> [<carrier-id> …]]` whose inner id is a reserved carrier."
  [sink]
  (keep (fn [[event opts]]
          (let [inner (second event)]
            (when (and (vector? inner) (contains? carrier-ids (first inner)))
              [(first inner) opts])))
        @sink))

(def ^:private cases
  ;; [parent-id, the carrier that resumes it]
  [[:cp/done-parent  :rf.machine.spawn/done]
   [:cp/all-parent   :rf.machine.spawn/done]
   [:cp/error-parent :rf.machine.spawn/error]
   [:cp/throw-parent :rf.machine.spawn/error]])

(defn- spawned-child
  "The actor id `:cp/idle-parent` spawned from its `:waiting` state."
  []
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned :cp/idle-parent [:waiting]]))

;; ---------------------------------------------------------------------------
;; Tests.
;; ---------------------------------------------------------------------------

(deftest per-call-overrides-and-lineage-reach-the-parent-continuation
  (doseq [[parent-id carrier-id] cases]
    (testing (str parent-id " — per-call :fx-overrides / :origin / :trace-id cross the completion edge")
      (let [fired (register-probe!)
            sink  (atom [])]
        (register-machines!)
        (with-dispatch-observer sink
          #(rf/dispatch-sync [parent-id [:go]]
                             {:fx-overrides stub-overrides
                              :origin       :cp/tool
                              :trace-id     "cp-trace"}))
        (is (= [] (filterv #(= :real (:via %)) @fired))
            "the parent's continuation did NOT run the REAL probe")
        (is (= 1 (count (filter #(= :stub (:via %)) @fired)))
            "the parent's continuation fired its probe exactly once, into the stub")
        (is (every? #(= {:origin :cp/tool :trace-id "cp-trace"}
                        (select-keys % [:origin :trace-id]))
                    @fired)
            ":origin and :trace-id arrived on the continuation's envelope")
        (let [carriers (carrier-opts sink)]
          (is (seq carriers) "a completion carrier was observed")
          (is (every? #(= carrier-id (first %)) carriers)
              (str "the parent was resumed by " carrier-id))
          (is (every? #(= :cp/probe.stub (get-in (second %) [:fx-overrides :cp/probe])) carriers)
              "every carrier carries the finishing envelope's :fx-overrides")
          (is (every? #(and (= :cp/tool (:origin (second %)))
                            (= "cp-trace" (:trace-id (second %))))
                      carriers)
              "every carrier carries the finishing envelope's :origin and :trace-id")
          (is (every? #(= :machine-spawn (:source (second %))) carriers)
              "every carrier keeps its own :source :machine-spawn")
          (is (not-any? #(:rf.machine/internal? (second %)) carriers)
              "no carrier is machine-internal — it keeps its FIFO place"))))))

;; Two deftests, not two `testing` blocks: the fixture resets the runtime per
;; deftest, and the first case leaves `:cp/idle-parent` in `:finished`.

(deftest an-override-on-the-finishing-event-reaches-the-parent
  (testing "an override on the event that FINISHES the child reaches the parent"
    (let [fired (register-probe!)]
      (register-machines!)
      (rf/dispatch-sync [:cp/idle-parent [:go]])
      (let [child (spawned-child)]
        (is (some? child) "the idle child was spawned and is waiting")
        (rf/dispatch-sync [child [:finish]]
                          {:fx-overrides stub-overrides
                           :origin       :cp/finisher
                           :trace-id     "fin-trace"}))
      (is (= [{:at :on-done :via :stub :origin :cp/finisher :trace-id "fin-trace"}] @fired)
          "the continuation ran under the FINISHING event's override and lineage"))))

(deftest an-override-on-the-spawn-does-not-reach-a-plainly-finished-completion
  (testing "an override on the SPAWN does not reach a completion a plain event caused"
    (let [fired (register-probe!)]
      (register-machines!)
      (rf/dispatch-sync [:cp/idle-parent [:go]]
                        {:fx-overrides stub-overrides :origin :cp/spawner})
      (rf/dispatch-sync [(spawned-child) [:finish]])
      (is (= [{:at :on-done :via :real}] @fired)
          "the finishing event carried no override, so neither did its carrier"))))

(deftest per-frame-overrides-still-reach-the-parent-continuation
  (doseq [[parent-id _] cases]
    (testing (str parent-id " — control: no override runs the real probe")
      (let [fired (register-probe!)]
        (register-machines!)
        (rf/dispatch-sync [parent-id [:go]])
        (is (= 1 (count (filter #(= :real (:via %)) @fired)))
            "with no override the parent's continuation runs the real probe")))
    (testing (str parent-id " — a per-frame :fx-overrides reaches the continuation")
      (let [fired (register-probe!)
            fid   (keyword "cp" (str "frame-" (name parent-id)))]
        (register-machines!)
        (rf/make-frame {:id fid :fx-overrides stub-overrides})
        (rf/dispatch-sync [parent-id [:go]] {:frame fid})
        (is (= [] (filterv #(= :real (:via %)) @fired))
            "no REAL probe ran under the per-frame override")
        (is (= 1 (count (filter #(= :stub (:via %)) @fired)))
            "the continuation's probe hit the stub")))))
