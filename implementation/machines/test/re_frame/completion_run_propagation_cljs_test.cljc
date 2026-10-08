(ns re-frame.completion-run-propagation-cljs-test
  "Run propagation crosses the machine COMPLETION edge (Spec 002 §Run
  propagation): the `[:rf.machine.spawn/done …]` / `[:rf.machine.spawn/error …]`
  carrier a finishing child mints into its parent is a child of the event that
  FINISHED the child, so that event's `:fx-overrides`, `:origin` and `:trace-id`
  reach the fx the parent fires on resuming. A carrier keeps
  `:source :machine-spawn` and is never machine-internal, so it keeps its FIFO
  place."
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

(def ^:private stub-overrides {:cp/probe :cp/probe.stub})

(defn- register-probe!
  "Register the probe fx and its stub; return the atom both record into. The
  stub also records the `:origin` / `:trace-id` of the envelope it ran under."
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
  recording every `[event opts]` into `sink`."
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
  "The dispatch opts of every observed completion carrier — an event
  `[<parent-id> [<carrier-id> …]]` whose inner id is a reserved carrier."
  [sink]
  (keep (fn [[event opts]]
          (let [inner (second event)]
            (when (and (vector? inner) (contains? carrier-ids (first inner)))
              opts)))
        @sink))

(def ^:private cases
  ;; [parent-id, the continuation that fires its probe]
  [[:cp/done-parent  :on-done]
   [:cp/all-parent   :on-all-complete]
   [:cp/error-parent :on-error]
   [:cp/throw-parent :on-error]])

(defn- spawned-child
  "The actor id `:cp/idle-parent` spawned from its `:waiting` state."
  []
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned :cp/idle-parent [:waiting]]))

(deftest overrides-and-lineage-reach-the-parent-continuation
  (doseq [[parent-id at] cases]
    (testing (str parent-id " — per-call :fx-overrides / :origin / :trace-id")
      (let [fired (register-probe!)
            sink  (atom [])]
        (register-machines!)
        (with-dispatch-observer sink
          #(rf/dispatch-sync [parent-id [:go]]
                             {:fx-overrides stub-overrides
                              :origin       :cp/tool
                              :trace-id     "cp-trace"}))
        (is (= [{:at at :via :stub :origin :cp/tool :trace-id "cp-trace"}] @fired))
        (is (= #{{:source :machine-spawn}}
               (set (map #(select-keys % [:source :rf.machine/internal?]) (carrier-opts sink))))
            "every carrier keeps :source :machine-spawn and is not machine-internal")))
    (testing (str parent-id " — a per-frame :fx-overrides")
      (let [fired (register-probe!)
            fid   (keyword "cp" (str "frame-" (name parent-id)))]
        (register-machines!)
        (rf/make-frame {:id fid :fx-overrides stub-overrides})
        (rf/dispatch-sync [parent-id [:go]] {:frame fid})
        (is (= [:stub] (map :via @fired)))))))

;; Two deftests, not two `testing` blocks: the fixture resets the runtime per
;; deftest, and the first case leaves `:cp/idle-parent` in `:finished`.

(deftest an-override-on-the-finishing-event-reaches-the-parent
  (let [fired (register-probe!)]
    (register-machines!)
    (rf/dispatch-sync [:cp/idle-parent [:go]])
    (rf/dispatch-sync [(spawned-child) [:finish]]
                      {:fx-overrides stub-overrides
                       :origin       :cp/finisher
                       :trace-id     "fin-trace"})
    (is (= [{:at :on-done :via :stub :origin :cp/finisher :trace-id "fin-trace"}] @fired)
        "the continuation ran under the FINISHING event's override and lineage")))

(deftest an-override-on-the-spawn-does-not-reach-a-plainly-finished-completion
  (let [fired (register-probe!)]
    (register-machines!)
    (rf/dispatch-sync [:cp/idle-parent [:go]]
                      {:fx-overrides stub-overrides :origin :cp/spawner})
    (rf/dispatch-sync [(spawned-child) [:finish]])
    (is (= [{:at :on-done :via :real}] @fired)
        "the finishing event carried no override, so neither did its carrier")))
