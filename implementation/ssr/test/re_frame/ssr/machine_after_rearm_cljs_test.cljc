(ns re-frame.ssr.machine-after-rearm-cljs-test
  "The `:rf/hydrate` SEAM re-arms machine `:after` timers.

  `machine_after_hydration_rearm_cljs_test` (machines artefact) pins the
  walk and the arming. This namespace pins the WIRING: a real
  server-rendered payload (the machine run on a `:platform :server` frame,
  projected by `payload-policy/project-runtime-db`, assembled by
  `payload-policy/build-payload`) dispatched through the real `:rf/hydrate`
  ends with a live client timer whose captured thunk, INVOKED, performs the
  transition — and a server-side hydrate arms nothing.

  Handlers and machines are registered INSIDE each test body: in the shared
  node process a sibling namespace's `registrar/clear-all!` wipes
  ns-load-time registrations."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            ;; Loading the machines artefact publishes the
            ;; `:machines/rearm-after-hydration!` hook the hydrate handler
            ;; gates on, and registers `:rf.machine/hydrate-rearm`.
            [re-frame.machines]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.router :as rf.router]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]))

;; COLD-START the adapter slot: this ns shares the node bundle with suites
;; that seat other adapters, and `init!` raises when handed a different one.
(use-fixtures :once
  (fn [f]
    (rf/destroy-adapter!)
    (rf/init! rf.ssr/adapter)
    (try (f) (finally (rf/destroy-adapter!)))))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private counter (atom 0))

(defn- fresh-frame!
  "A frame of the given platform under an id no other test in this shared
  process has used. `make-frame` opts are FLAT — a nested `{:config {…}}`
  would store `:config {:config {…}}` and the platform would silently read
  as the default."
  [platform]
  (let [fid (keyword "rf.ssrrearm" (str (name platform) (swap! counter inc)))]
    (rf/make-frame {:id fid :platform platform})
    fid))

(defn- inner
  "`frame-id`'s inner `:after` timer table, or `{}` when it holds none."
  [frame-id]
  (get @rf.machines.timer/after-timers frame-id {}))

(defn- fire!
  "Run a captured host-clock thunk to completion. The thunk dispatches the
  synthetic `:rf.machine.timer/after-elapsed` event through the REAL async
  router, whose drain is scheduled on `interop/next-tick`; collapsing that
  to an inline call is the established seam for observing an async drain
  deterministically on both hosts."
  [thunk]
  (with-redefs [rf.interop/next-tick (fn [f] (f) nil)]
    (thunk)))

(def ^:private waiting-machine
  {:initial :idle
   :data    {:entries 0}
   :actions {:bump (fn [{data :data}] {:data (update data :entries inc)})}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:entry :bump
                       :after {5000 {:target :timeout}}}
             :timeout {}}})

(defn- server-render!
  "Register `machine-id`, run it into its `:after`-bearing state on a real
  SERVER frame, and return the SHIPPED hydration payload for that frame.
  Asserts the server armed nothing on the way through, so the payload can
  never be one the server would not actually have produced."
  [machine-id]
  (rf/reg-machine machine-id waiting-machine)
  (let [sfid (fresh-frame! :server)]
    (rf/dispatch-sync [machine-id [:go]] {:frame sfid})
    (is (empty? (inner sfid))
        "precondition: the SERVER armed no `:after` host timer")
    (rf.ssr.payload-policy/build-payload
      nil
      (rf/app-db-value sfid)
      "server-hash-1"
      {:runtime-db (rf.ssr.payload-policy/project-runtime-db
                     (rf.frame/frame-runtime-db-value sfid) sfid)})))

;; ---------------------------------------------------------------------------
;; The seam works end to end
;; ---------------------------------------------------------------------------

(deftest rf-hydrate-re-arms-the-machines-after-timer-and-it-fires
  (testing "dispatching `:rf/hydrate` with a server-produced payload leaves
            the client frame holding a live `:after` timer whose expiry
            performs the declared transition"
    (let [payload (server-render! :ssrrearm/one)
          snap    (get-in payload [:rf/runtime-db :rf.runtime/machines
                                   :snapshots :ssrrearm/one])
          epoch   (get-in snap [:data :rf/after-epoch [:waiting]])
          thunks  (atom [])
          armed   (atom [])
          cfid    (fresh-frame! :client)
          client-snap #(get-in (rf.frame/frame-runtime-db-value cfid)
                               [:rf.runtime/machines :snapshots :ssrrearm/one])]
      (is (and (integer? epoch) (pos? epoch))
          "precondition: the per-decl-path epoch rode the wire")

      (with-redefs [rf.interop/schedule-after! (fn [t ms]
                                             (swap! thunks conj t)
                                             (swap! armed conj ms)
                                             ::handle)]
        (rf.router/dispatch-sync! [:rf/hydrate payload] {:frame cfid}))

      (let [table (inner cfid)]
        (is (= [{:parent :ssrrearm/one :spawn [:waiting] :delay 5000}] (keys table))
            "the `:rf/hydrate` seam armed exactly this one timer")
        (is (= epoch (:epoch (val (first table))))
            "armed at the epoch the PAYLOAD carried — hydration must not bump")
        (is (= [5000] @armed)
            "for the FULL declared delay; nothing on the wire records a
             schedule instant to compute a remainder from"))

      (is (= :waiting (:state (client-snap))))
      (fire! (first @thunks))
      (is (= [:timeout 1] [(:state (client-snap)) (get-in (client-snap) [:data :entries])])
          (str "the hydrated timer FIRED and drove the declared transition, and "
               "`:entry` ran exactly once — the server's. The re-arm reconstructs "
               "host work; it does not replay history.")))))

;; ---------------------------------------------------------------------------
;; A server-side hydrate arms nothing
;; ---------------------------------------------------------------------------

(deftest a-server-side-hydrate-arms-nothing
  (testing "hydrating onto a `:platform :server` frame — the isomorphic
            loopback / test-harness shape — starts no host clocks"
    (let [payload (server-render! :ssrrearm/srv)
          target  (fresh-frame! :server)]
      (with-redefs [rf.interop/schedule-after! (fn [_t _ms] ::handle)]
        (rf.router/dispatch-sync! [:rf/hydrate payload] {:frame target}))
      (is (= :waiting (get-in (rf.frame/frame-runtime-db-value target)
                              [:rf.runtime/machines :snapshots :ssrrearm/srv :state]))
          "the payload DID install — so the empty table below is the gate
           working, not the hydration failing")
      (is (empty? (inner target))
          "no timers on a server-side hydrate"))))
