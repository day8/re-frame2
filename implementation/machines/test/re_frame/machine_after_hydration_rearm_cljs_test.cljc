(ns re-frame.machine-after-hydration-rearm-cljs-test
  "A machine hydrated in an `:after`-bearing state gets its client timer back,
  armed at the snapshot's epoch (Spec 011 §`:after` is no-op under SSR).

  Snapshots come from running the machine on a real `:platform :server` frame,
  which must arm nothing. Every positive case FIRES the captured host-clock
  thunk, because a populated timer table alone would pass for an inert entry.
  Runs on both hosts."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            ;; Loading `re-frame.machines` installs the artefact's late-bind
            ;; hooks + reserved fxs; under a single-ns run nothing else does.
            [re-frame.machines]
            [re-frame.machines.hydrate :as rf.machines.hydrate]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A frame of `platform` under an unused id. `make-frame` opts are FLAT: a
  nested `{:config {…}}` would leave the platform at the `:client` default."
  [platform]
  (let [fid (keyword "rf.hydrearm" (str (name platform) (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform platform})
    fid))

(defn- inner
  "`frame-id`'s `:after` timer table, or `{}`."
  [frame-id]
  (get @rf.machines.timer/after-timers frame-id {}))

(defn- server-runtime-db
  "Run `machine-id` through `events` on a real SERVER frame, assert it armed no
  host timer, and return that frame's runtime-db — a genuine hydration slice."
  [machine-id events]
  (let [sfid (fresh-frame! :server)]
    (doseq [e events]
      (rf/dispatch-sync [machine-id e] {:frame sfid}))
    (is (empty? (inner sfid)) "precondition: the SERVER armed no `:after` host timer")
    (rf.frame/frame-runtime-db-value sfid)))

(defn- fire!
  "Run a captured host-clock thunk with `next-tick` collapsed inline, so the
  timer's dispatch drains through the real router before the next assertion."
  [thunk]
  (with-redefs [rf.interop/next-tick (fn [f] (f) nil)]
    (thunk)))

(defn- hydrate-into!
  "Install `runtime-db` on a fresh CLIENT frame and run the machines hydration
  seam with the host clock captured. Returns `[frame-id arms]`, `arms` being
  the `[ms thunk]` of every timer armed, in order."
  [runtime-db]
  (let [cfid (fresh-frame! :client)
        arms (atom [])]
    (with-redefs [rf.interop/schedule-after! (fn [thunk ms] (swap! arms conj [ms thunk]) ::handle)]
      (rf.frame/replace-runtime-db! cfid runtime-db)
      (rf.machines.hydrate/rearm-after-timers! cfid))
    [cfid @arms]))

(def ^:private flat-machine
  "`:waiting` carries a 5s `:after`; its `:entry` counts runs, the entry-replay control."
  {:initial :idle
   :data    {:entries 0}
   :actions {:bump-entries (fn [{data :data}]
                             {:data (update data :entries inc)})}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:entry :bump-entries
                       :after {5000 {:target :timeout}}}
             :timeout {}}})

(def ^:private compound-machine
  "An ancestor AND its active leaf both declare `:after`."
  {:initial :idle
   :data    {}
   :states  {:idle {:on {:go [:outer :inner]}}
             :outer {:initial :inner
                     :after   {9000 {:target :outer-fired}}
                     :states  {:inner {:after {4000 {:target :inner-fired}}}
                               :inner-fired {}}}
             :outer-fired {}}})

(def ^:private parallel-machine
  "An `:after`-bearing region, a timer-free region, and a ROOT-owned `:after`."
  {:type    :parallel
   :data    {}
   :after   {7000 {:target [:left :left-done]}}
   :regions {:left  {:initial :idle
                     :states  {:idle {:on {:go :ticking}}
                               :ticking {:after {3000 {:target :left-done}}}
                               :left-done {}}}
             :right {:initial :steady
                     :states  {:steady {}}}}})

(deftest hydrated-after-timer-is-armed-at-the-persisted-epoch-and-fires
  (rf/reg-machine :hyd/flat flat-machine)
  (let [rt    (server-runtime-db :hyd/flat [[:go]])
        epoch (get-in rt [:rf.runtime/machines :snapshots :hyd/flat :data :rf/after-epoch [:waiting]])]
    (is (pos-int? epoch) "precondition: the per-decl-path epoch rode the snapshot")
    (rf.machines.test-support/reset-captured!)
    (let [[cfid arms] (hydrate-into! rt)]
      (is (= {{:parent :hyd/flat :spawn [:waiting] :delay 5000}
              {:epoch epoch :state :waiting :delay-source :literal}}
             (update-vals (inner cfid) #(select-keys % [:epoch :state :delay-source])))
          "exactly one timer, at the snapshot's epoch: bumping it would make the restored snapshot stale against its own timer")
      (is (= [5000] (mapv first arms))
          "for the FULL declared delay: nothing durable records a schedule instant (Spec 005 §Clock abstraction)")
      (is (= [{:actor-id :hyd/flat :state :waiting :delay 5000 :delay-source :literal :epoch epoch :frame cfid}]
             (mapv #(select-keys (:tags %) [:actor-id :state :delay :delay-source :epoch :frame])
                   (rf.machines.test-support/events-of :rf.machine.timer/scheduled)))
          "one ordinary `/scheduled` row carrying the persisted epoch, so scheduled→fired pairs")
      (is (= [:waiting 1] [(rf.machines.test-support/machine-state cfid :hyd/flat)
                           (:entries (rf.machines.test-support/machine-data cfid :hyd/flat))])
          "the durable state is untouched and the server's `:entry` is not replayed")
      (fire! (second (first arms)))
      (is (= :timeout (rf.machines.test-support/machine-state cfid :hyd/flat))
          "the hydrated timer fired the declared transition"))))

(deftest hydration-re-arms-every-node-on-a-compound-active-path
  ;; Entry schedules only NEWLY-entered nodes, so only a walk of the active
  ;; configuration reconstructs the still-active ancestor's timer.
  (rf/reg-machine :hyd/compound compound-machine)
  (let [rt          (server-runtime-db :hyd/compound [[:go]])
        epochs      (get-in rt [:rf.runtime/machines :snapshots :hyd/compound :data :rf/after-epoch])
        [cfid arms] (hydrate-into! rt)]
    (is (= {{:parent :hyd/compound :spawn [:outer] :delay 9000}        (get epochs [:outer])
            {:parent :hyd/compound :spawn [:outer :inner] :delay 4000} (get epochs [:outer :inner])}
           (update-vals (inner cfid) :epoch))
        "both nodes on the active path, each at its OWN per-decl-path epoch")
    (fire! (get (into {} arms) 9000))
    (is (= [:outer-fired] (rf.machines.test-support/machine-state cfid :hyd/compound))
        "the ANCESTOR's hydrated timer fired")))

(deftest hydration-re-arms-region-and-root-parallel-afters
  (rf/reg-machine :hyd/par parallel-machine)
  (let [rt          (server-runtime-db :hyd/par [[:go]])
        data        (get-in rt [:rf.runtime/machines :snapshots :hyd/par :data])
        [cfid arms] (hydrate-into! rt)]
    (is (= {{:parent :hyd/par :spawn [:left :ticking] :delay 3000}
            {:epoch (get-in data [:rf/after-epoch-by-region :left [:ticking]]) :region :left :state :ticking}
            {:parent :hyd/par :spawn [] :delay 7000}
            {:epoch (get-in data [:rf/after-epoch []]) :region nil :state :rf/parallel-root}}
           (update-vals (inner cfid) #(select-keys % [:epoch :region :state])))
        (str "the region timer under its region-prefixed id at the PER-REGION epoch, recording its "
             "region for the work-id; the root timer at decl-path [] on the FLAT slot with the root sentinel"))
    (fire! (get (into {} arms) 3000))
    (is (= {:left :left-done :right :steady}
           (rf.machines.test-support/machine-state cfid :hyd/par))
        "the hydrated region timer fired and moved that region alone")))

(deftest an-unresolvable-snapshot-arms-nothing
  ;; Hydration is best-effort (Spec 011 §The `:rf/hydrate` event): a snapshot
  ;; whose machine type resolves to nothing contributes nothing and takes no
  ;; sibling down with it.
  (rf/reg-machine :hyd/mixed flat-machine)
  (let [rt     (assoc-in (server-runtime-db :hyd/mixed [[:go]])
                         [:rf.runtime/machines :snapshots :hyd/ghost]
                         {:state :waiting :data {} :rf/machine-type :hyd/never-registered})
        [cfid] (hydrate-into! rt)]
    (is (= #{{:parent :hyd/mixed :spawn [:waiting] :delay 5000}}
           (set (keys (inner cfid)))))))
