(ns re-frame.routing-concurrency-stress-test
  "Per rf2-ksbur — JVM concurrency stress coverage for the routing
  surface. Mirrors rf2-1gpx8 (machine actor) and rf2-35rgj (cross-frame
  router) patterns: 5000-iter, 8-thread default, env-overridable, with
  invariants pinned per scenario.

  The deterministic routing-test suite covers correctness single-shot;
  this namespace pins the routing surface under contention. Per the
  rf2-q4twq decomposition, routing has three concurrency-shaped
  surfaces never exercised under load by the deterministic suite:

    1. **N concurrent `:rf/url-changed` from N frames.** Each frame
       drains independently (Spec 002 §Rules rule 1 — frames are
       independent state machines, their drain-locks don't share). N
       threads each fire `iters` URL-driven nav events at their OWN
       per-thread frame. Invariant: every URL-changed event lands on
       its frame's `:rf/route` slice exactly once — no drops, no
       duplicates. Asserted via per-frame `:on-match` counter.

    2. **Popstate / hashchange firing mid-push.** On JVM the popstate
       analogue is `:rf.route/handle-url-change` (per Spec 012 §URL
       changes are events — popstate, initial load, SSR all funnel
       through this event). Forward navigation (push) is `:rf/url-changed`.
       Each thread alternates the two event types on its own frame.
       Invariant: total on-match count = iters per frame; ordering is
       stable per-thread (last URL pushed wins the slice). The two
       event handlers share `url-change-fx` — the race window is the
       drain interleaving on a single frame. Per-frame partitioning
       means each frame's drain-lock serialises its own work; we're
       pinning that serialisation under sustained churn.

    3. **`reg-route` / `unregister!` race during dispatch.** Thread A
       drives a sustained URL-change stream against a stable route.
       Thread B re-registers (and occasionally unregisters) routes on
       a tight loop. The route-table cache (`route-table-cache` —
       rf2-9ihwx) invalidates by registrar map identity; identity
       equality is racy under concurrent `register!` calls. Invariant:
       no exceptions surface from `match-url` and the stable-route
       slice still settles correctly across all `iters` dispatches.

  Threads start in lockstep via `CountDownLatch.countDown` to maximise
  contention on the shared registrar (Spec 002 §Public registrar query
  API). Bounded `Future.deref` joins so a hung drain surfaces as a
  visible failure rather than a stuck CI run.

  CLJS is single-threaded; route registry mutation + URL-driven nav
  cannot race there. The CLJS browser-history mock pattern landed
  recently as part of rf2-wp0w4's PR #1176; this JVM stress is
  complementary, not redundant.

  Tagged `^:stress` per the rf2-q4twq convention so the default test
  runner can opt out via `:exclude :stress` when CI wall-clock budget
  is tight."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            ;; rf2-k682: this test lives in the routing artefact's test
            ;; classpath, so requiring re-frame.routing here is the
            ;; primary trigger that loads the namespace, fires its
            ;; late-bind hook registrations + framework `:rf.route/*`
            ;; reg-sub installations, and registers the framework
            ;; `:rf.nav/*` fxs. Without this require the rf/reg-route
            ;; calls below would throw :rf.error/routing-artefact-missing.
            [re-frame.routing :as routing]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace])
  (:import [java.util.concurrent CountDownLatch]
           [java.util.concurrent.atomic AtomicLong]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
  (rf/init! plain-atom/adapter)
  ;; Framework events / fx (routing.cljc) are registered at ns-load;
  ;; clear-all! wiped them. Reload to resurrect.
  (require 're-frame.routing :reload)
  (routing/reset-counters!)
  (test-fn))

(use-fixtures :each reset-runtime)

;; Per-thread iteration count. Kept at the rf2-ynk7 / rf2-35rgj /
;; rf2-1gpx8 standard 5000 so CI stays under ~60s wall-clock at the
;; default thread count. Operators dial up via the env override; CI
;; dials down by lowering it (e.g. RF2_KSBUR_STRESS_ITERS=500 for a
;; smoke-test pass).
(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_KSBUR_STRESS_ITERS") Long/parseLong)
      5000))

;; Eight parallel threads — matches rf2-ynk7's `concurrent-dispatch-stress`
;; (`n-submitters 8`) and rf2-1gpx8. Higher contention than the typical
;; 4-core CI box; the per-frame partitioning means we're not
;; over-saturating any one drain-lock, we're driving N independent
;; URL-change cycles in parallel and asserting the registrar lookup +
;; route-table cache invalidation + per-frame slice writes don't
;; tangle across frames.
(def ^:private n-threads 8)

;; ---- Scenario 1: N concurrent :rf/url-changed from N frames -------------

(deftest ^:stress url-changed-cross-frame-stress
  ;; rf2-ksbur scenario 1.
  ;;
  ;; Each thread owns its own frame and fires `stress-iters` `:rf/url-changed`
  ;; events at it. Per Spec 012 §Multi-frame routing each non-default
  ;; frame may opt in to its own `:rf/route` slice without owning the
  ;; URL (`:url-bound? false` — the documented default for story /
  ;; devcard / per-test fixtures). Per Spec 002 §Rules rule 1 frames
  ;; are independent — their drain-locks don't share.
  ;;
  ;; The `:on-match` event on the route bumps:
  ;;   - a **per-thread atom** (counter for THIS thread's frame)
  ;;   - a **global AtomicLong** (cross-frame cross-check)
  ;;
  ;; Two distinct counter views detect drops vs. doubles independently
  ;; — divergence indicates the per-thread observation lost track even
  ;; when the global total happened to balance.
  (testing (str n-threads " threads × " stress-iters
                " iters :rf/url-changed — no drops, no doubles")
    (let [global-counter      (AtomicLong. 0)
          per-thread-counters (vec (repeatedly n-threads #(atom 0)))
          per-thread
          (vec
            (for [i (range n-threads)]
              {:idx        i
               :frame-id   (keyword "ksbur.stress" (str "f" i))
               :counter    (nth per-thread-counters i)
               ;; Per-thread :on-match event id so the closure binds
               ;; THIS thread's counter atom (registrar is global; one
               ;; shared id would only bind the last closure).
               :tick-event (keyword "ksbur.stress" (str "tick-f" i))}))
          ;; Stable route shared across threads — a single registry
          ;; entry whose `:on-match` fans out to per-thread tick events.
          ;; Per-frame `dispatch-sync` of `:rf/url-changed` produces
          ;; a `:rf.route/handle-url-change` style cascade that emits
          ;; the on-match events to each thread's own frame.
          on-match-events (mapv (fn [{:keys [tick-event]}] [tick-event])
                                per-thread)]
      ;; Set up frames + on-match handlers + route on the main thread
      ;; before any futures launch. These registrar / frame-registry
      ;; writes are serialised here; the futures only DISPATCH against
      ;; them.
      (doseq [{:keys [frame-id tick-event counter]} per-thread]
        (rf/reg-frame frame-id {:doc        "per-thread frame"
                                :url-bound? false})
        (rf/reg-event-db tick-event
                         {:frame frame-id}
                         (fn [db _]
                           (.incrementAndGet global-counter)
                           (swap! counter inc)
                           (update db :n (fnil inc 0)))))
      ;; A single shared route. Each frame runs its own `:on-match` —
      ;; but the `:on-match` vector is the same across frames since
      ;; the framework dispatches each on-match event to the calling
      ;; frame (the `{:frame frame-id}` opt on the per-thread tick
      ;; reg-event-db ensures the event lands on the right frame).
      ;; Wait — :on-match dispatches honour the calling frame, so we
      ;; actually need ONE on-match event registered per frame. Use
      ;; per-frame routes so the `:on-match` payload is per-frame.
      ;;
      ;; Re-register: per-thread routes carrying the per-thread
      ;; on-match event id. Each thread's :rf/url-changed dispatch
      ;; matches that thread's URL pattern and fires its own
      ;; on-match → per-thread counter bump.
      (registrar/clear-kind! :route)
      (doseq [{:keys [idx tick-event]} per-thread]
        (rf/reg-route (keyword "ksbur.stress" (str "route-" idx))
                      {:path     (str "/p" idx "/:slug")
                       :on-match [[tick-event]]}))

      (let [latch   (CountDownLatch. 1)
            futures (vec
                      (for [{:keys [idx frame-id]} per-thread]
                        (future
                          (.await latch)
                          (dotimes [k stress-iters]
                            (rf/dispatch-sync
                              [:rf/url-changed
                               (str "/p" idx "/" k)]
                              {:frame frame-id})))))]
        (.countDown latch)
        ;; Bounded join — 120s for 8 × 5000 cycles on a slow box.
        (doseq [f futures]
          (let [v (deref f 120000 ::timeout)]
            (is (not= ::timeout v)
                "thread completed within 120s wall-clock")))

        ;; --- Invariant 1: no event dropped (per-thread sum) -------
        (let [per-thread-totals (mapv deref per-thread-counters)
              actual-sum        (reduce + per-thread-totals)
              expected-sum      (* n-threads stress-iters)]
          (is (= expected-sum actual-sum)
              (str "Per-thread counter sum: expected "
                   expected-sum " (= " n-threads " threads × "
                   stress-iters " iters); got " actual-sum
                   ". Per-thread breakdown: " per-thread-totals))
          ;; Per-thread exact-match rules out the case where one thread
          ;; silently dropped half its events while another doubled up
          ;; (sum could still balance).
          (is (every? #(= stress-iters %) per-thread-totals)
              (str "Each thread must have processed exactly "
                   stress-iters " :on-match events; got "
                   per-thread-totals)))

        ;; --- Invariant 2: no double-action (global atomic) --------
        (let [global-actual   (.get global-counter)
              global-expected (* n-threads stress-iters)]
          (is (= global-expected global-actual)
              (str "Global AtomicLong: expected "
                   global-expected " on-match firings (no double-"
                   "action); got " global-actual)))

        ;; --- Invariant 3: per-frame slice converged on the LAST URL
        ;;     pushed by that thread. Pre-existing routing semantics:
        ;;     each thread's serial dispatch-sync sequence settles the
        ;;     slice to the final URL it pushed. Cross-frame parallelism
        ;;     does not perturb that: each frame's own drain serialises
        ;;     its own work.
        (doseq [{:keys [idx frame-id]} per-thread]
          (let [slice    (:rf/route (rf/get-frame-db frame-id))
                expected (str "/p" idx "/" (dec stress-iters))]
            (is (= (keyword "ksbur.stress" (str "route-" idx))
                   (:id slice))
                (str "Frame " frame-id ": :rf/route :id should match "
                     "this thread's per-thread route"))
            ;; Slug is captured as a path param; the LAST iter's slug
            ;; (= dec stress-iters) wins.
            (is (= (str (dec stress-iters))
                   (get-in slice [:params :slug]))
                (str "Frame " frame-id ": slice should reflect the "
                     "LAST URL pushed (" expected ")"))))))))

;; ---- Scenario 2: popstate firing mid-push -------------------------------

(deftest ^:stress popstate-mid-push-stress
  ;; rf2-ksbur scenario 2.
  ;;
  ;; Per Spec 012 §URL changes are events forward navigation
  ;; (`:rf/url-changed`) and popstate / initial load
  ;; (`:rf.route/handle-url-change`) share `url-change-fx`. The race
  ;; window is the drain interleaving when both arrive at the same
  ;; frame in tight succession — pre-fix this could double-process the
  ;; popstate's slice rewrite under sustained churn.
  ;;
  ;; Each thread owns its own frame and alternates the two event types
  ;; on a tight loop. Per-frame partitioning means each frame's
  ;; drain-lock serialises ITS own work — same-frame dispatch-sync
  ;; from a single thread cannot race itself, but the test pins the
  ;; cross-event-type behaviour: the `:on-match` cascade must fire
  ;; exactly once per dispatched URL change regardless of which event
  ;; ID drove it, with no popstate-vs-push reordering visible at the
  ;; counter level.
  (testing (str n-threads " threads × " stress-iters
                " iters mixed url-changed/handle-url-change "
                "— exact on-match count, no double-process")
    (let [global-counter      (AtomicLong. 0)
          per-thread-counters (vec (repeatedly n-threads #(atom 0)))
          per-thread
          (vec
            (for [i (range n-threads)]
              {:idx        i
               :frame-id   (keyword "ksbur.pop" (str "f" i))
               :counter    (nth per-thread-counters i)
               :tick-event (keyword "ksbur.pop" (str "tick-f" i))}))]
      (doseq [{:keys [frame-id tick-event counter]} per-thread]
        (rf/reg-frame frame-id {:doc        "per-thread frame"
                                :url-bound? false})
        (rf/reg-event-db tick-event
                         {:frame frame-id}
                         (fn [db _]
                           (.incrementAndGet global-counter)
                           (swap! counter inc)
                           (update db :n (fnil inc 0)))))
      (doseq [{:keys [idx tick-event]} per-thread]
        (rf/reg-route (keyword "ksbur.pop" (str "route-" idx))
                      {:path     (str "/q" idx "/:slug")
                       :on-match [[tick-event]]}))

      (let [latch   (CountDownLatch. 1)
            futures (vec
                      (for [{:keys [idx frame-id]} per-thread]
                        (future
                          (.await latch)
                          (dotimes [k stress-iters]
                            ;; Alternate forward (`:rf/url-changed`) and
                            ;; popstate (`:rf.route/handle-url-change`).
                            ;; Both funnel through `url-change-fx`; the
                            ;; on-match cascade fires exactly once per
                            ;; dispatch.
                            (let [url   (str "/q" idx "/" k)
                                  evt-id (if (even? k)
                                           :rf/url-changed
                                           :rf.route/handle-url-change)]
                              (rf/dispatch-sync [evt-id url]
                                                {:frame frame-id}))))))]
        (.countDown latch)
        (doseq [f futures]
          (let [v (deref f 120000 ::timeout)]
            (is (not= ::timeout v)
                "thread completed within 120s wall-clock")))

        ;; --- Invariant 1: no event dropped, no doubles -------------
        (let [per-thread-totals (mapv deref per-thread-counters)
              actual-sum        (reduce + per-thread-totals)
              expected-sum      (* n-threads stress-iters)]
          (is (= expected-sum actual-sum)
              (str "Per-thread counter sum: expected "
                   expected-sum "; got " actual-sum
                   ". Per-thread breakdown: " per-thread-totals))
          (is (every? #(= stress-iters %) per-thread-totals)
              (str "Each thread must have processed exactly "
                   stress-iters " on-match events (mixed url-changed "
                   "+ handle-url-change); got " per-thread-totals)))

        ;; --- Invariant 2: global atomic exact ---------------------
        (let [global-actual   (.get global-counter)
              global-expected (* n-threads stress-iters)]
          (is (= global-expected global-actual)
              (str "Global AtomicLong: expected "
                   global-expected " on-match firings; got "
                   global-actual)))

        ;; --- Invariant 3: per-frame slice converged ---------------
        (doseq [{:keys [idx frame-id]} per-thread]
          (let [slice (:rf/route (rf/get-frame-db frame-id))]
            (is (= (keyword "ksbur.pop" (str "route-" idx))
                   (:id slice))
                (str "Frame " frame-id ": slice :id should match"))
            (is (= (str (dec stress-iters))
                   (get-in slice [:params :slug]))
                (str "Frame " frame-id ": slice should reflect the "
                     "LAST URL pushed by this thread"))))))))

;; ---- Scenario 3: reg-route / unregister race during dispatch ------------

(deftest ^:stress reg-route-race-during-dispatch-stress
  ;; rf2-ksbur scenario 3.
  ;;
  ;; The `route-table-cache` invalidates by registrar map identity
  ;; (rf2-9ihwx — `(identical? source (:source-id cache))`). Identity
  ;; equality on a CAS-swapped registrar is racy: under sustained
  ;; concurrent `register!` (which `swap!`s the registrar map) the
  ;; cache may be rebuilt many times, but `match-url` must NEVER see a
  ;; partial cache or a torn pairs vector.
  ;;
  ;; Setup:
  ;;   - **One stable route** the dispatcher threads target. Its
  ;;     metadata is fixed for the duration of the test; its
  ;;     `:on-match` handler bumps a per-frame counter.
  ;;   - **Churn thread** repeatedly registers and unregisters a pool
  ;;     of `noise` routes. Each register! / unregister! swaps the
  ;;     registrar map identity, invalidating the route-table-cache.
  ;;     Yields between operations so dispatcher threads make forward
  ;;     progress.
  ;;   - **N dispatcher threads** each fire `iters` `:rf/url-changed`
  ;;     events at the stable route on their own frame.
  ;;
  ;; Invariants:
  ;;   - **No exception** from any `match-url` call (a torn cache or
  ;;     NPE in the `:rf.route/compiled` lookup would surface here).
  ;;   - **Per-frame on-match counter = iters** — every dispatched
  ;;     URL still resolved the stable route despite the registry
  ;;     churn.
  ;;   - **Stable route remains registered** post-test (the churn
  ;;     thread never touches its id).
  (testing (str n-threads " threads × " stress-iters
                " iters dispatch + concurrent reg/unreg churn — "
                "no torn cache, no drops")
    (let [stable-route        :ksbur.race/stable
          per-thread-counters (vec (repeatedly n-threads #(atom 0)))
          per-thread
          (vec
            (for [i (range n-threads)]
              {:idx        i
               :frame-id   (keyword "ksbur.race" (str "f" i))
               :counter    (nth per-thread-counters i)
               :tick-event (keyword "ksbur.race" (str "tick-f" i))}))
          ;; Pool of noise route ids the churn thread cycles through.
          ;; Distinct paths so they don't shadow the stable route's
          ;; rank tuple.
          noise-ids (mapv #(keyword "ksbur.race" (str "noise-" %))
                          (range 32))
          ;; Errors from match-url / dispatch surface here.
          errors    (atom [])]
      (doseq [{:keys [frame-id tick-event counter]} per-thread]
        (rf/reg-frame frame-id {:doc        "per-thread frame"
                                :url-bound? false})
        (rf/reg-event-db tick-event
                         {:frame frame-id}
                         (fn [db _]
                           (swap! counter inc)
                           (update db :n (fnil inc 0)))))
      ;; Stable route shared across all dispatcher threads. Each
      ;; thread's :on-match dispatch lands on its OWN frame (per
      ;; reg-event-db {:frame frame-id} above) — but the route
      ;; metadata's :on-match vector references each thread's tick
      ;; event in turn. Use one route per thread so the on-match
      ;; payload is per-thread.
      ;;
      ;; Wait: a single stable route can declare on-match events that
      ;; live on different frames? Per Spec 012 §Per-route data loading
      ;; and how :rf.route/navigate dispatches on-match: events are
      ;; dispatched to the calling frame. A route with ALL N tick events
      ;; would fan all N out to the dispatching thread's frame — wrong.
      ;; Use per-thread routes again, with the stable route as the
      ;; *shape*; the churn thread targets `noise-ids` exclusively.
      (registrar/clear-kind! :route)
      (doseq [{:keys [idx tick-event]} per-thread]
        (rf/reg-route (keyword "ksbur.race" (str "stable-" idx))
                      {:path     (str "/r" idx "/:slug")
                       :on-match [[tick-event]]}))
      ;; Sentinel: a single id we explicitly do not touch in the
      ;; churn thread, so we can assert it remains registered post-test.
      (rf/reg-route stable-route {:path "/sentinel"})

      (let [latch   (CountDownLatch. 1)
            stop?   (atom false)
            ;; Churn thread: register / unregister noise routes on a
            ;; tight loop, alternating to maximise route-table-cache
            ;; invalidation churn. Yields between operations so the
            ;; dispatcher threads make forward progress.
            churn-thread
            (Thread.
              ^Runnable
              (fn []
                (try
                  (.await latch)
                  (let [phase (atom 0)]
                    (while (not @stop?)
                      (let [p  (swap! phase inc)
                            id (nth noise-ids (mod p (count noise-ids)))]
                        (if (even? p)
                          (rf/reg-route id
                                        {:path (str "/noise-"
                                                    (mod p (count noise-ids))
                                                    "/:x")})
                          (registrar/unregister! :route id)))
                      (Thread/yield)))
                  (catch Throwable t
                    (swap! errors conj {:thread :churn :error t})))))
            dispatcher-futures
            (vec
              (for [{:keys [idx frame-id]} per-thread]
                (future
                  (try
                    (.await latch)
                    (dotimes [k stress-iters]
                      (rf/dispatch-sync
                        [:rf/url-changed (str "/r" idx "/" k)]
                        {:frame frame-id}))
                    (catch Throwable t
                      (swap! errors conj
                             {:thread :dispatcher
                              :idx    idx
                              :error  t}))))))]
        (.start churn-thread)
        (.countDown latch)
        ;; Wait for all dispatchers; then signal churn to stop.
        (doseq [f dispatcher-futures]
          (let [v (deref f 120000 ::timeout)]
            (is (not= ::timeout v)
                "dispatcher completed within 120s wall-clock")))
        (reset! stop? true)
        (.join churn-thread 10000)

        ;; --- Invariant 1: no exceptions -------------------------------
        (is (empty? @errors)
            (str "Expected zero errors from dispatch / churn across "
                 stress-iters " iters; got " (count @errors)
                 (when (seq @errors)
                   (str ". First few: "
                        (pr-str (vec (take 3 (map #(update % :error
                                                            (fn [t] (.getMessage ^Throwable t)))
                                                  @errors))))))))

        ;; --- Invariant 2: per-frame on-match count exact -------------
        (let [per-thread-totals (mapv deref per-thread-counters)]
          (is (every? #(= stress-iters %) per-thread-totals)
              (str "Each frame's on-match counter must equal "
                   stress-iters " (every URL still resolved despite "
                   "registry churn); got " per-thread-totals)))

        ;; --- Invariant 3: sentinel route still registered ------------
        (is (some? (registrar/lookup :route stable-route))
            (str "Sentinel route " stable-route " must still be "
                 "registered post-test (the churn thread never "
                 "touches its id)"))

        ;; --- Invariant 4: per-frame slice converged on stable route -
        (doseq [{:keys [idx frame-id]} per-thread]
          (let [slice (:rf/route (rf/get-frame-db frame-id))]
            (is (= (keyword "ksbur.race" (str "stable-" idx))
                   (:id slice))
                (str "Frame " frame-id ": slice :id should match "
                     "this thread's stable route despite churn"))))))))
