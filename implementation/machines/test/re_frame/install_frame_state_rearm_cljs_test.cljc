(ns re-frame.install-frame-state-rearm-cljs-test
  "Persist a live frame's machines and install them into a fresh frame with
  `[:rf/install-frame-state saved]` — the XState `getPersistedSnapshot` /
  `createActor(machine, {snapshot})` loop, run through re-frame2's one
  production-legal install event (Spec 002 §Installing a persisted
  frame-state).

  What must hold after the install:

    - the parent and its spawned child are restored exactly as saved, with
      the spawn registry;
    - NO entry action re-runs and no `:rf.machine/started` is emitted — the
      snapshot IS the state;
    - the parent's live `:after` is re-armed for its full declared delay at the
      epoch the saved snapshot carries, and FIRING it performs the transition
      and tears the child down;
    - the restored child answers its own events.

  The re-arm is the part a raw install lacks: without the
  `:rf.machine/hydrate-rearm` fx the event requests, the restored parent
  would sit in its timed state with no timer at all.

  Both hosts: a `.cljc` named `*-cljs-test`, so it runs under
  `clojure -M:test` from `implementation/machines` (JVM) and under the node
  runner (`npm run test:cljs`)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            #?(:cljs [cljs.reader])
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            ;; Loading `re-frame.machines` installs the artefact's late-bind
            ;; hooks + reserved fxs; under a single-ns run nothing else does.
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A client frame under an id no other test in this process has used."
  []
  (let [fid (keyword "rf.install" (str "client" (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform :client})
    fid))

(defn- inner
  "`frame-id`'s `:after` timer table, or `{}` when it holds none."
  [frame-id]
  (get @rf.machines.timer/after-timers frame-id {}))

(defn- fire!
  "Run a captured host-clock thunk to completion: its dispatch goes through
  the real async router, whose drain is collapsed to an inline call here so
  the assertions after it see the result."
  [thunk]
  (with-redefs [rf.interop/next-tick (fn [f] (f) nil)]
    (thunk)))

(defn- round-trip
  "Serialise `v` to a string and read it back — what a localStorage save and
  load does to it."
  [v]
  #?(:clj  (read-string (pr-str v))
     :cljs (cljs.reader/read-string (pr-str v))))

(def ^:private entry-log (atom []))

(defn- logging
  "An entry action recording `tag`, so any replayed entry is visible."
  [tag]
  (fn [_] (swap! entry-log conj tag) {}))

(def ^:private kid-machine
  {:initial :active
   :data    {:progress 1}
   :states  {:active {:entry (logging :kid-entry)
                      :on    {:tick {:action (fn [{data :data}]
                                               {:data (update data :progress inc)})}}}}})

(def ^:private parent-machine
  "`:loading` spawns the child and carries a 5s `:after`."
  {:initial :idle
   :data    {}
   :states  {:idle      {:entry (logging :idle-entry)
                         :on    {:load :loading}}
             :loading   {:entry (logging :loading-entry)
                         :spawn {:machine-id :inst/kid}
                         :after {5000 {:target :timed-out}}}
             :timed-out {}}})

(defn- persisted-machines!
  "Run the parent into `:loading` on a live client frame and return what an
  app would persist: `[:rf.runtime/machines]` read through
  `frame-state-value`, round-tripped through `pr-str` / `read-string`."
  []
  (let [src (fresh-frame!)]
    (with-redefs [rf.interop/schedule-after! (fn [_thunk _ms] ::handle)]
      (rf/dispatch-sync [:inst/par [:load]] {:frame src}))
    (is (= :loading (rf.machines.test-support/machine-state src :inst/par))
        "precondition: the live parent is in its timed state")
    (let [machines (get-in (rf/frame-state-value src) [:rf.db/runtime :rf.runtime/machines])
          saved    (round-trip machines)]
      (is (= machines saved)
          "precondition: the machines subtree round-trips as data")
      saved)))

;; ---------------------------------------------------------------------------
;; The loop
;; ---------------------------------------------------------------------------

(deftest an-installed-machine-is-restored-and-its-after-timer-re-armed
  (testing "installing a persisted machines subtree restores parent + child
            with no entry replay, and re-arms the parent's `:after` at the
            persisted epoch — a timer that FIRES"
    (rf/reg-machine :inst/kid kid-machine)
    (rf/reg-machine :inst/par parent-machine)
    (reset! entry-log [])
    (let [saved  (persisted-machines!)
          kid    (get-in saved [:spawned :inst/par [:loading]])
          epoch  (get-in saved [:snapshots :inst/par :data :rf/after-epoch [:loading]])
          dst    (fresh-frame!)
          thunks (atom [])
          arms   (atom [])]
      (is (keyword? kid) "precondition: the child's address rode the saved spawn registry")
      (is (= {:idle-entry 1 :loading-entry 1 :kid-entry 1} (frequencies @entry-log))
          "precondition: the live session ran each entry once")
      (is (seq (rf.machines.test-support/events-of :rf.machine/started))
          "precondition: the live session's machines announced their start")
      (reset! entry-log [])
      (rf.machines.test-support/reset-captured!)

      (with-redefs [rf.interop/schedule-after! (fn [thunk ms]
                                                 (swap! thunks conj thunk)
                                                 (swap! arms conj ms)
                                                 ::handle)]
        (rf/dispatch-sync [:rf/install-frame-state
                           {:rf.db/runtime {:rf.runtime/machines saved}}]
                          {:frame dst}))

      (testing "the snapshots, the spawn registry and the child are restored"
        (is (= :loading (rf.machines.test-support/machine-state dst :inst/par)))
        (is (= kid (get-in (rf.machines.test-support/runtime-db dst)
                           [:rf.runtime/machines :spawned :inst/par [:loading]])))
        (is (= {:progress 1}
               (select-keys (rf.machines.test-support/machine-data dst kid) [:progress]))))

      (testing "nothing is replayed"
        (is (= [] @entry-log) "no entry action ran again")
        (is (empty? (rf.machines.test-support/events-of :rf.machine/started))
            "a restored machine emits no :rf.machine/started"))

      (testing "the `:after` is re-armed for its full delay at the persisted epoch"
        (is (= [5000] @arms))
        (let [[k entry] (first (inner dst))]
          (is (= {:parent :inst/par :spawn [:loading] :delay 5000} k))
          (is (= epoch (:epoch entry))
              "armed at the epoch the saved snapshot carries — not bumped")))

      (testing "the restored child answers its own events"
        (rf/dispatch-sync [kid [:tick]] {:frame dst})
        (is (= 2 (:progress (rf.machines.test-support/machine-data dst kid)))))

      (testing "the re-armed timer fires the declared transition"
        (fire! (first @thunks))
        (is (= :timed-out (rf.machines.test-support/machine-state dst :inst/par)))
        (is (nil? (rf.machines.test-support/snapshot dst kid))
            "leaving :loading tore the spawned child down")))))

(deftest an-install-without-a-machines-subtree-arms-nothing
  (testing "the re-arm is requested only when the payload carries
            `:rf.runtime/machines`"
    (rf/reg-machine :inst/kid kid-machine)
    (rf/reg-machine :inst/par parent-machine)
    (let [dst  (fresh-frame!)
          arms (atom [])]
      (with-redefs [rf.interop/schedule-after! (fn [_thunk ms] (swap! arms conj ms) ::handle)]
        (rf/dispatch-sync [:rf/install-frame-state {:rf.db/app {:restored true}}]
                          {:frame dst}))
      (is (= {:restored true} (rf/app-db-value dst)))
      (is (= [] @arms))
      (is (empty? (inner dst))))))
