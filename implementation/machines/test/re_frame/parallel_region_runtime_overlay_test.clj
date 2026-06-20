(ns re-frame.parallel-region-runtime-overlay-test
  "Region frame/platform runtime overlay.

  `re-frame.machines.parallel/region-machine` MEMOISES the synthetic
  single-machine spec for each region in metadata on the parent machine, at
  REGISTRATION time. The cached spec captures `:rf/platform` / `:rf/frame`
  from whatever the parent machine held when the cache was first populated —
  which can be the UNSTAMPED registration-time machine (before
  `prepare-machine-ctx` stamps the live runtime values).

  `reduce-regions` overlays the LIVE `:rf/platform` / `:rf/frame` (alongside
  `:rf/parent-id`) from the parent machine onto the cached region spec before
  EVERY region step, at the `reduce-regions` choke-point — so the region's
  pure logic always runs with the live runtime values, never a stale/missing
  cached `:rf/platform` / `:rf/frame`.

  Consequences this test guards:
    - A parallel-region `:after` runs `build-after-fx` against the LIVE
      `:rf/platform`. Under SSR (`:platform :server`) the region timer is
      skipped (`:skipped-on-server`), not treated as a CLIENT timer
      (`:scheduled` + a host-clock `:after-schedule` fx).
    - Region timer/action traces carry the LIVE `:rf/frame`, so
      epoch-capture / frame-isolation attribution is correct.

  These tests drive `parallel/machine-transition` directly (pure) with an
  explicitly-stamped parent so the assertion does not depend on a live SSR
  frame."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.result :as result]
            [re-frame.machines.test-support :as mtest]))

;; ---- helpers ---------------------------------------------------------------

(defn- record-traces!
  "Register a trace listener for the duration of `body-fn`; return the
  captured trace vec. (`trace/emit!` delivers to listeners synchronously,
  so a PURE `machine-transition` call surfaces its traces here without a
  dispatch cycle.) Routed through the shared `mtest/with-trace-capture` —
  guaranteed unregister in a `finally`."
  [body-fn]
  (mtest/with-trace-capture seen
    (body-fn)
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

;; ---- transition-local `:rf/cofx` must not survive the cache ----------------

;; A parallel machine whose region action captures the causal `:rf.cofx`
;; coeffect threaded onto the action ctx. The `:capture` action records what it
;; saw so the test can assert presence / absence per transition. `(:type
;; :parallel)` exercises the same `region-machine` cache + `region-spec-overlaid`
;; choke-point as the platform/frame overlay above.
(def cofx-capture-spec
  {:type    :parallel
   :data    {}
   :regions {:a {:initial :one
                 :states  {:one {:on {:go {:action :capture}}}}}
             :b {:initial :rest
                 :states  {:rest {}}}}})

(deftest region-cofx-does-not-leak-from-cache-into-later-transition
  (testing "rf2-gqr4vs: priming the region cache from a parent carrying
   `:rf/cofx`, then running a LATER transition whose parent carries NO
   `:rf/cofx`, must surface NO `:rf.cofx` on the region action ctx — the
   transition-local coeffect cannot survive on the cached region spec."
    (let [seen      (atom [])
          ;; A shared `:capture` action recording the cofx slot it observed.
          spec      (assoc-in cofx-capture-spec [:actions :capture]
                              (fn [{cofx :rf.cofx :as ctx}]
                                (swap! seen conj {:has? (contains? ctx :rf.cofx)
                                                  :cofx cofx})
                                {:data (:data ctx)}))
          installed (parallel/install-region-cache spec)]
      ;; 1. PRIME the cache from a parent carrying a causal coeffect token —
      ;;    reproducing the bug origin: the cached region spec captures the
      ;;    coeffect-carrying parent.
      (parallel/region-machine (assoc installed :rf/cofx {:rf/time-ms 1}) :a)
      (parallel/region-machine (assoc installed :rf/cofx {:rf/time-ms 1}) :b)
      ;; 2. The LIVE transition's parent carries NO `:rf/cofx` (a pure-fn / no-
      ;;    router caller, or a dispatch that simply did not thread the token).
      (let [snap (parallel-snapshot installed)]
        (parallel/machine-transition installed snap [:go]))
      (is (= 1 (count @seen)) "the region :capture action ran exactly once")
      (is (false? (:has? (first @seen)))
          "the action ctx carried NO :rf.cofx — the cached coeffect did not leak
           (callback-ctx keys off presence; the overlay dissoc'd the stale slot)")
      (is (nil? (:cofx (first @seen)))
          "and the destructured value is nil, never the primed {:rf/time-ms 1}"))))

(deftest region-cofx-overlays-live-value-when-parent-carries-it
  (testing "rf2-gqr4vs (symmetric): when the LIVE parent DOES carry `:rf/cofx`,
   the region action ctx surfaces THAT value — the overlay threads the current
   dispatch's coeffect through region pure logic, even when the cache was primed
   under a DIFFERENT coeffect."
    (let [seen      (atom [])
          spec      (assoc-in cofx-capture-spec [:actions :capture]
                              (fn [{cofx :rf.cofx :as ctx}]
                                (swap! seen conj {:has? (contains? ctx :rf.cofx)
                                                  :cofx cofx})
                                {:data (:data ctx)}))
          installed (parallel/install-region-cache spec)]
      ;; Prime under one coeffect …
      (parallel/region-machine (assoc installed :rf/cofx {:rf/time-ms 1}) :a)
      ;; … then transition under a DIFFERENT live coeffect.
      (let [live (assoc installed :rf/cofx {:rf/time-ms 99})
            snap (parallel-snapshot live)]
        (parallel/machine-transition live snap [:go]))
      (is (= 1 (count @seen)) "the region :capture action ran exactly once")
      (is (true? (:has? (first @seen)))
          "the action ctx carried :rf.cofx (the live parent supplied it)")
      (is (= {:rf/time-ms 99} (:cofx (first @seen)))
          "and it was the LIVE value, not the primed {:rf/time-ms 1}"))))
