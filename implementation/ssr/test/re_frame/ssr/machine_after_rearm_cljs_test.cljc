(ns re-frame.ssr.machine-after-rearm-cljs-test
  "rf2-jqvgp — the `:rf/hydrate` SEAM re-arms machine `:after` timers.

  `machine_after_hydration_rearm_cljs_test` (machines artefact) pins the
  walk and the arming. This namespace pins the WIRING: that a real
  server-rendered payload, dispatched through the real `:rf/hydrate`
  event, ends with a live client timer — and that the three ways the seam
  is supposed to arm NOTHING all hold.

  Nothing here is hand-written wire shape. The snapshot is produced by
  running the machine on a `:platform :server` frame, projected by
  `payload-policy/project-runtime-db` (the shipped projector, machines
  hook and all) and assembled by `payload-policy/build-payload` (the
  shipped assembler), so the test tracks the real payload rather than a
  literal that can drift away from it.

  ## It fires the timer

  The positive case captures the host-clock thunk and INVOKES it, then
  asserts the machine transitioned. A timer table with an entry in it
  proves nothing about whether the entry is wired to anything.

  Both hosts: a `.cljc` named `*-cljs-test`, so it runs under
  `clojure -M:test` from `implementation/ssr` (JVM) and under the node
  runner (`npm run test:cljs`). Handlers and machines are registered
  INSIDE each test body under per-test ids — in the shared node process a
  sibling namespace's `registrar/clear-all!` wipes ns-load-time
  registrations, which would silently turn a dispatch into a no-op."
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

(use-fixtures :once (fn [f] (rf/init! rf.ssr/adapter) (f)))

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
          cfid    (fresh-frame! :client)]
      (is (= :waiting (:state snap))
          "precondition: the `:after`-bearing state rode the wire")
      (is (and (integer? epoch) (pos? epoch))
          "precondition: so did the per-decl-path epoch")
      (is (empty? (inner cfid)) "precondition: the client holds no timers yet")

      (with-redefs [rf.interop/schedule-after! (fn [t ms]
                                             (swap! thunks conj t)
                                             (swap! armed conj ms)
                                             ::handle)]
        (rf.router/dispatch-sync! [:rf/hydrate payload] {:frame cfid}))

      (let [table (inner cfid)]
        (is (= 1 (count table))
            (str "the `:rf/hydrate` seam armed exactly one timer. Before "
                 "rf2-jqvgp this was 0 and the machine was stuck in "
                 ":waiting for the life of the page."))
        (is (= {:parent :ssrrearm/one :spawn [:waiting] :delay 5000}
               (ffirst table)))
        (is (= epoch (:epoch (val (first table))))
            "armed at the epoch the PAYLOAD carried — hydration must not bump")
        (is (= [5000] @armed)
            "for the FULL declared delay; nothing on the wire records a
             schedule instant to compute a remainder from"))

      (is (= :waiting (get-in (rf.frame/frame-runtime-db-value cfid)
                              [:rf.runtime/machines :snapshots :ssrrearm/one :state])))
      (fire! (first @thunks))
      (is (= :timeout (get-in (rf.frame/frame-runtime-db-value cfid)
                              [:rf.runtime/machines :snapshots :ssrrearm/one :state]))
          "the hydrated timer FIRED and drove the declared transition")
      (is (= 1 (get-in (rf.frame/frame-runtime-db-value cfid)
                       [:rf.runtime/machines :snapshots :ssrrearm/one :data :entries]))
          (str "and `:entry` still ran exactly once — the server's. The re-arm "
               "reconstructs host work; it does not replay history.")))))

;; ---------------------------------------------------------------------------
;; The three ways it arms nothing
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

(deftest a-rejected-payload-arms-nothing
  (testing "a payload the handler fails CLOSED on installs nothing and
            therefore arms nothing"
    (let [good (server-render! :ssrrearm/rej)
          cfid (fresh-frame! :client)]
      (with-redefs [rf.interop/schedule-after! (fn [_t _ms] ::handle)]
        ;; (a) malformed — a present-but-non-map `:rf/app-db` slice.
        (rf.router/dispatch-sync! [:rf/hydrate (assoc good :rf/app-db "not-a-map")]
                               {:frame cfid})
        (is (empty? (inner cfid)) "malformed payload: rejected, nothing armed")
        (is (nil? (get-in (rf.frame/frame-runtime-db-value cfid)
                          [:rf.runtime/machines :snapshots :ssrrearm/rej]))
            "and nothing installed either — the rejection is total")

        ;; (b) wrong frame — a payload stamped for a DIFFERENT frame id.
        (rf.router/dispatch-sync! [:rf/hydrate (assoc good :rf/frame-id :ssrrearm/somewhere-else)]
                               {:frame cfid})
        (is (empty? (inner cfid)) "wrong-frame payload: rejected, nothing armed")

        ;; CONTROL — the same payload, unmangled, on the same frame DOES arm.
        ;; Without this the two assertions above would pass for a payload
        ;; that could never arm anything in the first place.
        (rf.router/dispatch-sync! [:rf/hydrate good] {:frame cfid})
        (is (= 1 (count (inner cfid)))
            "control: the well-formed payload arms, so the rejections above
             are about the rejection and not about the payload")))))

(deftest a-payload-with-no-runtime-db-slice-arms-nothing
  (testing "a client-only payload (no `:rf/runtime-db`) requests no re-arm —
            there is no server-settled machine state to reconstruct from"
    (let [good (server-render! :ssrrearm/nort)
          cfid (fresh-frame! :client)]
      (with-redefs [rf.interop/schedule-after! (fn [_t _ms] ::handle)]
        (rf.router/dispatch-sync! [:rf/hydrate (dissoc good :rf/runtime-db)]
                               {:frame cfid}))
      (is (empty? (inner cfid))
          "no runtime-db slice, no re-arm"))))
