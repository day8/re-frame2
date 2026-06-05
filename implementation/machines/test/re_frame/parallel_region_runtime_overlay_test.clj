(ns re-frame.parallel-region-runtime-overlay-test
  "Per rf2-z522n (finding 1). Regression for the STALE region frame/platform
  bug.

  `re-frame.machines.parallel/region-machine` MEMOISES the synthetic
  single-machine spec for each region in metadata on the parent machine, at
  REGISTRATION time. The cached spec captures `:rf/platform` / `:rf/frame`
  from whatever the parent machine held when the cache was first populated —
  which can be the UNSTAMPED registration-time machine (before
  `prepare-machine-ctx` stamps the live runtime values). On a later
  transition, `reduce-regions` re-stamped only `:rf/parent-id` onto the
  cached spec, so the region's pure logic ran with the STALE/missing
  `:rf/platform` / `:rf/frame`.

  Consequences this test guards:
    - A parallel-region `:after` ran `build-after-fx` against the stale
      `:rf/platform`. Under SSR (`:platform :server`) the region timer was
      treated as a CLIENT timer (`:scheduled` + a host-clock
      `:after-schedule` fx) instead of being skipped
      (`:skipped-on-server`).
    - Region timer/action traces carried the stale (missing) `:rf/frame`,
      so epoch-capture / frame-isolation attribution dropped or
      mis-attributed them.

  The fix overlays the LIVE `:rf/platform` / `:rf/frame` (alongside
  `:rf/parent-id`) from the parent machine onto the cached region spec
  before EVERY region step, at the `reduce-regions` choke-point. These
  tests drive `parallel/machine-transition` directly (pure) with an
  explicitly-stamped parent so the assertion does not depend on a live SSR
  frame."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.result :as result]
            [re-frame.trace]))

;; ---- helpers ---------------------------------------------------------------

(defn- record-traces!
  "Register a trace listener for the duration of `body-fn`; return the
  captured trace vec. (`trace/emit!` delivers to listeners synchronously,
  so a PURE `machine-transition` call surfaces its traces here without a
  dispatch cycle.)"
  [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn) (finally (rf/unregister-listener! ::rec)))
    @seen))

(defn- of-op [evs op] (filterv #(= op (:operation %)) evs))

;; A parallel machine: region :climate transitions :idle → :cooling on
;; [:start]; :cooling declares an :after timer. The sibling :lights region
;; just rests. The `:after` is what exercises `build-after-fx`'s
;; platform-gated server-skip path.
(def base-spec
  {:type    :parallel
   :data    {}
   :regions {:climate {:initial :idle
                       :states  {:idle    {:on {:start :cooling}}
                                 :cooling {:after {5000 :idle}}}}
             :lights  {:initial :off
                       :states  {:off {}}}}})

(defn- parallel-snapshot
  "Build a live initial snapshot for `machine` (region → initial-state map
  + seeded :data / spawn-counter)."
  [machine]
  (parallel/build-initial-snapshot machine {:bootstrap-pending? false}))

(deftest server-region-after-skips-host-timer-despite-stale-cache
  (testing "rf2-z522n: a parallel-region :after under a `:platform :server`
   frame emits :rf.machine.timer/skipped-on-server (NOT :scheduled) and
   schedules NO host `:rf.machine/after-schedule` fx — even when the region
   cache was first populated from an unstamped (client/nil-platform)
   machine."
    ;; 1. Install the region cache and PRIME it from the UNSTAMPED machine
    ;;    (no :rf/platform / :rf/frame) — reproducing the bug's origin: the
    ;;    cached region spec captures a nil platform.
    (let [unstamped (parallel/install-region-cache base-spec)]
      (parallel/region-machine unstamped :climate)   ;; fault the stale entry in
      (parallel/region-machine unstamped :lights)
      ;; 2. The LIVE transition runs under a `:platform :server` frame.
      (let [server-machine (assoc unstamped
                                  :rf/platform  :server
                                  :rf/frame     :test/server-frame
                                  :rf/parent-id :overlay/server)
            snap   (parallel-snapshot server-machine)
            traces (record-traces!
                     (fn []
                       (parallel/machine-transition
                         server-machine snap [:start])))
            skipped   (of-op traces :rf.machine.timer/skipped-on-server)
            scheduled (of-op traces :rf.machine.timer/scheduled)]
        (is (= 1 (count skipped))
            "the region :after emitted the SERVER-SKIP trace (live :server platform won over the stale cached nil)")
        (is (empty? scheduled)
            "no client :scheduled trace — the host timer was correctly skipped")
        (is (= :test/server-frame (-> skipped first :tags :frame))
            "the skip trace carries the LIVE frame, not the stale/missing cached one")
        (is (= :server (-> skipped first :tags :platform))
            "the skip trace records :platform :server")))))

(deftest client-region-after-schedules-and-carries-live-frame
  (testing "rf2-z522n: the symmetric client path — a parallel-region :after
   under a `:platform :client` frame DOES schedule (`:scheduled`) and the
   trace carries the live frame — confirming the overlay threads the real
   runtime frame through region pure logic, not a stale cached value."
    (let [unstamped (parallel/install-region-cache base-spec)]
      ;; Prime the cache from the unstamped machine again.
      (parallel/region-machine unstamped :climate)
      (let [client-machine (assoc unstamped
                                  :rf/platform  :client
                                  :rf/frame     :test/client-frame
                                  :rf/parent-id :overlay/client)
            snap   (parallel-snapshot client-machine)
            traces (record-traces!
                     (fn []
                       (parallel/machine-transition
                         client-machine snap [:start])))
            scheduled (of-op traces :rf.machine.timer/scheduled)]
        (is (= 1 (count scheduled))
            "client platform schedules the region :after")
        (is (= :test/client-frame (-> scheduled first :tags :frame))
            "the :scheduled trace carries the LIVE client frame")))))

(deftest overlay-does-not-mutate-the-cached-region-spec
  (testing "rf2-z522n: overlaying live runtime keys per step must not corrupt
   the SHARED cached region spec — two transitions on different platforms
   must each see their OWN platform (the cache stays platform-agnostic;
   the overlay is per-step)."
    (let [unstamped (parallel/install-region-cache base-spec)]
      (parallel/region-machine unstamped :climate)
      ;; Server transition first.
      (let [srv  (assoc unstamped :rf/platform :server :rf/frame :test/srv
                        :rf/parent-id :ov/srv)
            srv-tr (record-traces!
                     (fn [] (parallel/machine-transition
                              srv (parallel-snapshot srv) [:start])))]
        (is (= 1 (count (of-op srv-tr :rf.machine.timer/skipped-on-server)))
            "server run skipped"))
      ;; Then a client transition reusing the SAME cached region specs.
      (let [cli  (assoc unstamped :rf/platform :client :rf/frame :test/cli
                        :rf/parent-id :ov/cli)
            cli-tr (record-traces!
                     (fn [] (parallel/machine-transition
                              cli (parallel-snapshot cli) [:start])))]
        (is (= 1 (count (of-op cli-tr :rf.machine.timer/scheduled)))
            "client run scheduled — the cache was NOT poisoned by the prior server overlay")
        (is (empty? (of-op cli-tr :rf.machine.timer/skipped-on-server))
            "no stale server-skip leaked from the prior run")))))
