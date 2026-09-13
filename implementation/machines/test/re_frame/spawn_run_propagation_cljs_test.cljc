(ns re-frame.spawn-run-propagation-cljs-test
  "rf2-gbzv9 — run propagation crosses the machine SPAWN edge.

  Spec 002 §Run propagation: per-call `:fx-overrides` / `:interceptor-overrides`
  and the `:trace-id` / `:origin` lineage ride every child dispatch the run
  queues, by envelope-field-copying. A machine spawn queues a child too: the
  `:rf.machine/spawn` fx dispatches the newborn actor's first event (its
  `:start`, or the synthetic `[:rf.machine.spawn/spawned]`). That dispatch used
  to be a FRESH router dispatch carrying only `{:frame … :source :machine-spawn}`,
  so a test that stubbed an effect per call for a machine that spawns children
  silently ran the REAL effect inside every child.

  Pinned here, for `:spawn` (explicit `:start`) and `:spawn-all` (synthetic
  kick-off):

   1. a per-call `:fx-overrides` on `dispatch-sync` into the spawning parent
      reaches an fx the CHILDREN fire, and `:origin` / `:trace-id` arrive on the
      child's dispatch envelope;
   2. the spawn dispatch keeps its own `:source :machine-spawn` (`:source` is
      never inherited) and is NOT tagged machine-internal, so the newborn's
      first event keeps its FIFO place in the queue;
   3. a per-frame `:fx-overrides` still reaches the children, and with no
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

(def ^:private stub-overrides {:sp/probe :sp/probe.stub})

(defn- register-probe!
  "Register the probe fx and its stub; return the atom both record into. The
  stub also records the dispatch envelope it ran under, read off `(:envelope
  m)` — the production-visible surface, so this holds in both postures."
  []
  (let [fired (atom [])]
    (rf/reg-fx :sp/probe
      (fn [_ args] (swap! fired conj (assoc args :via :real))))
    (rf/reg-fx :sp/probe.stub
      (fn [m args]
        (let [env (:envelope m)]
          (swap! fired conj (assoc args
                                   :via      :stub
                                   :source   (:source env)
                                   :origin   (:origin env)
                                   :trace-id (:trace-id env))))))
    fired))

(defn- register-machines!
  "A child that fires the probe from its initial `:entry` and from a `:begin`
  action; a `:spawn` parent handing it `:start [:begin]`; a `:spawn-all` parent
  spawning two of it with no `:start` (the synthetic kick-off)."
  []
  (rf/reg-machine :sp/child
    {:initial :running
     :states  {:running {:entry (fn [_] {:fx [[:sp/probe {:at :entry}]]})
                         :on    {:begin {:action (fn [_] {:fx [[:sp/probe {:at :begin}]]})}}}}})
  (rf/reg-machine :sp/spawner
    {:initial :idle
     :states  {:idle     {:on {:go :spawning}}
               :spawning {:spawn {:machine-id :sp/child :start [:begin]}}}})
  (rf/reg-machine :sp/forker
    {:initial :idle
     :states  {:idle    {:on {:go :forking}}
               :forking {:spawn-all {:children        [{:id :a :machine-id :sp/child}
                                                       {:id :b :machine-id :sp/child}]
                                     :join            :all
                                     :on-all-complete [:all/done]}
                         :on {:all/done :ready}}
               :ready   {}}}))

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

(defn- spawn-dispatch-opts
  "The opts of every observed spawn dispatch (`:source :machine-spawn`)."
  [sink]
  (keep (fn [[_ opts]] (when (= :machine-spawn (:source opts)) opts)) @sink))

(def ^:private cases
  ;; [parent-id, expected probe hits inside the children]
  [[:sp/spawner 2]    ;; one child: :entry + :begin
   [:sp/forker  2]])  ;; two children: :entry each

;; ---------------------------------------------------------------------------
;; Tests.
;; ---------------------------------------------------------------------------

(deftest per-call-overrides-and-lineage-reach-spawned-children
  (doseq [[parent-id expected] cases]
    (testing (str parent-id " — per-call :fx-overrides / :origin / :trace-id cross the spawn edge")
      (let [fired (register-probe!)
            sink  (atom [])]
        (register-machines!)
        (with-dispatch-observer sink
          #(rf/dispatch-sync [parent-id [:go]]
                             {:fx-overrides stub-overrides
                              :origin       :sp/tool
                              :trace-id     "sp-trace"}))
        (is (= [] (filterv #(= :real (:via %)) @fired))
            "no child ran the REAL probe — the per-call override reached every child")
        (is (= expected (count (filter #(= :stub (:via %)) @fired)))
            "every probe the children fired hit the stub")
        (is (every? #(= {:source :machine-spawn :origin :sp/tool :trace-id "sp-trace"}
                        (select-keys % [:source :origin :trace-id]))
                    @fired)
            ":origin and :trace-id arrived on the child's dispatch envelope; :source stayed :machine-spawn")
        (let [spawn-opts (spawn-dispatch-opts sink)]
          (is (seq spawn-opts) "the spawn dispatch was observed")
          (is (every? #(= :sp/probe.stub (get-in % [:fx-overrides :sp/probe])) spawn-opts)
              "the spawn dispatch carries the parent envelope's :fx-overrides")
          (is (every? #(and (= :sp/tool (:origin %)) (= "sp-trace" (:trace-id %))) spawn-opts)
              "the spawn dispatch carries the parent envelope's :origin and :trace-id")
          (is (not-any? :rf.machine/internal? spawn-opts)
              "the spawn dispatch is not machine-internal — the newborn's first event stays FIFO"))))))

(deftest per-frame-overrides-still-reach-spawned-children
  (doseq [[parent-id expected] cases]
    (testing (str parent-id " — control: no override runs the real probe")
      (let [fired (register-probe!)]
        (register-machines!)
        (rf/dispatch-sync [parent-id [:go]])
        (is (= expected (count (filter #(= :real (:via %)) @fired)))
            "with no override the children run the real probe")))
    (testing (str parent-id " — a per-frame :fx-overrides reaches the children")
      (let [fired (register-probe!)
            fid   (keyword "sp" (str "frame-" (name parent-id)))]
        (register-machines!)
        (rf/make-frame {:id fid :fx-overrides stub-overrides})
        (rf/dispatch-sync [parent-id [:go]] {:frame fid})
        (is (= [] (filterv #(= :real (:via %)) @fired))
            "no child ran the REAL probe under the per-frame override")
        (is (= expected (count (filter #(= :stub (:via %)) @fired)))
            "every probe the children fired hit the stub")
        (is (every? #(= :machine-spawn (:source %)) @fired)
            "the children's booting event is stamped :source :machine-spawn")))))
