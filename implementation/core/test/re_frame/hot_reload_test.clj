(ns re-frame.hot-reload-test
  "Targeted JVM coverage for Spec 001 §Hot-reload semantics.

  Spec 001 guarantees five properties; this namespace pins three:
    1. Re-registering an :event handler is non-destructive to in-flight
       work — the handler currently in process-event! finishes against
       its captured fn.
    2. Re-registering a :sub disposes cached reactions across every
       frame — no stale value can be served from any frame's sub-cache.
    3. Re-registering a :frame is a surgical metadata update — live
       app-db (including the [:rf.runtime/machines :snapshots] snapshot for active machine
       instances) is preserved.

  CLJS-only Reagent rendering coverage (rule 4 — view re-registration)
  lives in hot_reload_cljs_test.cljs.

  The fixture in this namespace deliberately does NOT reset the
  registrar between the pre- and post-re-registration assertions:
  re-registration IS the unit of behaviour under test. Each deftest
  performs both registrations within one test body."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-69r7ui): there is no `:rf/default` floor — the runtime
  ;; never synthesises a frame from absence, so a bare dispatch / subscribe
  ;; raises :rf.error/no-frame-context. The tests below that exercise the
  ;; default app frame register it EXPLICITLY and target it via an explicit
  ;; `{:frame :rf/default}` opt (`:rf/default` is an ordinary, legal frame
  ;; id a small app / test may register and select — Spec 002 §`:rf/default`
  ;; is an ordinary id).
  (rf/reg-frame :rf/default {:doc "the explicit app frame for this test ns"})
  ;; Framework events / fx are registered at namespace-load time in
  ;; routing.cljc and ssr.cljc; clear-all! wiped them. Re-eval those
  ;; registrations so :rf/hydrate, :rf.nav/push-url etc. resurrect.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr    :reload)
  ;; The snapshot test uses `rf/make-machine-handler`; re-load machines so
  ;; the artefact's reg surface is live in isolated runs too (the snapshot
  ;; slot lives in runtime-db per EP-0001).
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- (1) :event re-register is non-destructive to in-flight work ---------

(deftest event-handler-re-register-non-destructive
  (testing "the handler currently in process-event! finishes with its captured fn,
            even when an external thread re-registers the same id mid-flight"
    (let [observations (atom [])
          ;; CountDownLatches give us deterministic synchronisation: no
          ;; Thread/sleep, no race. The handler signals it's mid-flight,
          ;; the test thread re-registers, and signals the handler to
          ;; continue. The handler then finishes against its captured fn.
          enter-latch   (CountDownLatch. 1)
          proceed-latch (CountDownLatch. 1)
          v1-fn (fn [{:keys [db]} [_ tag]]
                  (swap! observations conj [:v1-start tag])
                  (.countDown enter-latch)
                  ;; Wait for the test thread to perform the re-registration.
                  (.await proceed-latch 5 TimeUnit/SECONDS)
                  ;; This line runs AFTER re-registration has landed in the
                  ;; registry. The interceptor closure has v1-fn captured —
                  ;; the body keeps running v1, proving non-destructive.
                  (swap! observations conj [:v1-end tag])
                  {:db (assoc db :ran-version :v1)})
          v2-fn (fn [{:keys [db]} [_ tag]]
                  (swap! observations conj [:v2 tag])
                  {:db (assoc db :ran-version :v2)})]
      (rf/reg-event :step v1-fn)
      ;; Run the dispatch on a worker thread so the test thread can
      ;; re-register while the handler is parked at proceed-latch.
      (let [drain-thread
            (Thread.
              ^Runnable (fn []
                          (rf/dispatch-sync [:step :a] {:frame :rf/default})))]
        (.start drain-thread)
        ;; Wait for the v1 body to be in-flight.
        (.await enter-latch 5 TimeUnit/SECONDS)
        ;; Mid-flight re-registration. After this swap, the registry's
        ;; :step entry is v2; but the running handler's interceptor
        ;; closure still points at v1.
        (rf/reg-event :step v2-fn)
        ;; Release the handler so it can finish its captured body.
        (.countDown proceed-latch)
        (.join drain-thread 5000))
      ;; The in-flight handler ran v1-start AND v1-end (its captured body
      ;; completed, undisturbed by the registry swap). v2 was NOT invoked
      ;; for the in-flight event.
      (is (= [[:v1-start :a] [:v1-end :a]] @observations)
          "in-flight handler ran v1 body to completion")
      (is (= :v1 (:ran-version (rf/app-db-value :rf/default)))
          ":db effect from the v1 closure committed")
      ;; The next dispatch picks up the new body via fresh registry lookup.
      (rf/dispatch-sync [:step :b] {:frame :rf/default})
      (is (= [[:v1-start :a] [:v1-end :a] [:v2 :b]] @observations)
          "post-drain dispatches resolve to v2")
      (is (= :v2 (:ran-version (rf/app-db-value :rf/default)))
          ":db effect from v2 committed for the post-drain event")))

  (testing "in a multi-event chain, the handler currently executing keeps its
            captured body even when it re-registers itself mid-handler"
    ;; A second deterministic flavour of (1) that does NOT span threads:
    ;; the handler mutates its own registry slot during its run and the
    ;; rest of the same handler invocation must continue with the old fn.
    (let [observations (atom [])
          v1-fn (fn v1 [{:keys [db]} [_ n]]
                  (swap! observations conj [:v1-pre n])
                  ;; Mid-handler self re-registration.
                  (rf/reg-event :tick
                    (fn v2 [{:keys [db]} [_ n]]
                      (swap! observations conj [:v2 n])
                      {:db (assoc db :seen-version :v2)}))
                  ;; The remainder of THIS invocation runs in the v1 closure.
                  (swap! observations conj [:v1-post n])
                  {:db (assoc db :seen-version :v1)})]
      (rf/reg-event :tick v1-fn)
      (rf/dispatch-sync [:tick 1] {:frame :rf/default})
      (is (= [[:v1-pre 1] [:v1-post 1]] @observations)
          "handler's body completes against its captured v1 fn")
      (is (= :v1 (:seen-version (rf/app-db-value :rf/default))))
      ;; A subsequent dispatch sees the new body.
      (rf/dispatch-sync [:tick 2] {:frame :rf/default})
      (is (= [[:v1-pre 1] [:v1-post 1] [:v2 2]] @observations)
          "the next dispatch resolves the new v2 body")
      (is (= :v2 (:seen-version (rf/app-db-value :rf/default)))))))

;; ---- (2) :sub re-register evicts cache across all frames -----------------

(deftest sub-re-register-evicts-cache-cross-frame
  (testing "re-registering a :sub disposes its cached reaction in every frame"
    (rf/reg-frame :left  {:doc "left frame"})
    (rf/reg-frame :right {:doc "right frame"})
    (rf/reg-event :seed (fn [{:keys [db]} [_ n]] {:db {:n n}}))
    ;; Two frames each carrying their own :n.
    (rf/dispatch-sync [:seed 3] {:frame :left})
    (rf/dispatch-sync [:seed 5] {:frame :right})
    ;; v1 of :answer is the identity over :n.
    (rf/reg-sub :answer (fn [db _] (:n db)))
    ;; Pin the cache slot in each frame by holding a reaction reference;
    ;; subscribe-once would auto-unsubscribe and the slot would be evicted
    ;; by ref-counting, masking the cache-cross-frame contract.
    (let [pin-left  (rf/subscribe [:answer] {:frame :left})
          pin-right (rf/subscribe [:answer] {:frame :right})
          left-cache  (:sub-cache (frame/frame :left))
          right-cache (:sub-cache (frame/frame :right))]
      (is (= 3 @pin-left)  "left frame's :answer reads :n=3 under v1")
      (is (= 5 @pin-right) "right frame's :answer reads :n=5 under v1")
      (is (contains? @left-cache  [:answer])
          "left frame has a cache slot for [:answer]")
      (is (contains? @right-cache [:answer])
          "right frame has a cache slot for [:answer]")
      ;; v2 of :answer multiplies by 100.
      (rf/reg-sub :answer (fn [db _] (* 100 (:n db))))
      ;; Both frames' cache slots must be GONE — the registrar's
      ;; replacement-hook walked frame-ids and dissoced from each.
      (is (not (contains? @left-cache  [:answer]))
          "left frame's [:answer] cache slot was evicted")
      (is (not (contains? @right-cache [:answer]))
          "right frame's [:answer] cache slot was evicted")
      ;; Next subscribe in each frame builds a fresh reaction with the v2 body.
      (is (= 300 (rf/subscribe-once [:answer] {:frame :left}))
          "left frame's next subscribe uses v2 (3 * 100)")
      (is (= 500 (rf/subscribe-once [:answer] {:frame :right}))
          "right frame's next subscribe uses v2 (5 * 100)"))))

;; ---- (2b) :sub re-register evicts the transitive :<- dependent closure ----
;;
;; Per Cross-Spec-Interactions §18.2 — re-registering a sub invalidates not
;; just its own cache slot but every DOWNSTREAM cache slot that depended on
;; it through `:<-`. rf2-kboyu: before the fix, `invalidate-sub-on-replace!`
;; only evicted keys whose query-vector HEAD equalled the re-registered id;
;; a cached `[:sum] :<- [:a]` kept its old input reaction and served the OLD
;; `:a` body's value after `:a` was re-registered.

(deftest sub-re-register-evicts-transitive-dependents
  (testing "re-registering an upstream sub evicts its DOWNSTREAM :<- dependents
            so they recompute against the new upstream body"
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 10}}))
    (rf/dispatch-sync [:seed] {:frame :rf/default})
    ;; v1 of :a is the identity over :n (= 10); :sum is :a + 0 (= 10).
    (rf/reg-sub :a (fn [db _] (:n db)))
    (rf/reg-sub :sum :<- [:a] (fn [a _] (+ a 0)))
    ;; Pin the DOWNSTREAM reaction so ref-counting cannot evict it; this is
    ;; the slot that, pre-fix, survives the re-registration with a stale
    ;; input reaction. Subscribing [:sum] also subscribes [:a] (its input).
    (let [pin-sum (rf/subscribe [:sum] {:frame :rf/default})
          cache   (:sub-cache (frame/frame :rf/default))]
      (is (= 10 @pin-sum) ":sum reads 10 under v1 of :a")
      (is (contains? @cache [:sum]) "downstream [:sum] is cached")
      (is (contains? @cache [:a])   "upstream [:a] is cached")
      ;; Re-register :a so its body now doubles :n (= 20). The body fn is a
      ;; fresh closure; the OLD reaction for [:a] (and [:sum]'s held input)
      ;; close over the v1 body.
      (rf/reg-sub :a (fn [db _] (* 2 (:n db))))
      ;; Both the re-registered sub's own slot AND the transitive dependent
      ;; MUST be gone — the closure walk evicted [:a] (head match) and [:sum]
      ;; (depends on [:a] via :<-).
      (is (not (contains? @cache [:a]))
          "[:a] (the re-registered sub) cache slot was evicted")
      (is (not (contains? @cache [:sum]))
          "[:sum] (transitive :<- dependent) cache slot was evicted")
      ;; The next subscribe rebuilds [:sum] (and its [:a] input) against the
      ;; new :a body: 2 * 10 = 20, NOT the stale 10.
      (is (= 20 (rf/subscribe-once [:sum] {:frame :rf/default}))
          "next subscribe of [:sum] observes the new :a body (2 * 10 = 20)")
      ;; A held reaction taken AFTER the re-registration also reads the new
      ;; body — the stale input reaction is no longer reachable from the cache.
      (let [pin-sum-v2 (rf/subscribe [:sum] {:frame :rf/default})]
        (is (= 20 @pin-sum-v2)
            "a freshly-held [:sum] reaction reads the new :a body (20)")))))

(deftest sub-re-register-transitive-eviction-terminates-on-cyclic-inputs
  (testing "the transitive closure walk terminates even when the recorded
            :<- topology contains a cycle — each key is admitted at most once"
    ;; A pathological dev-time graph: :p :<- [:q] and :q :<- [:p]. The
    ;; registrar permits the registrations; we synthesise the cyclic cache
    ;; entries directly so the closure walk is exercised against a cycle
    ;; without needing the reactive build to succeed.
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 1}}))
    (rf/dispatch-sync [:seed] {:frame :rf/default})
    (rf/reg-sub :base (fn [db _] (:n db)))
    (let [cache (:sub-cache (frame/frame :rf/default))]
      ;; Hand-craft a cycle in the cache's :inputs topology. No live
      ;; reactions are needed for the closure walk (it reads only :inputs);
      ;; :reaction nil means the dispose loop skips disposal for these slots.
      (swap! cache assoc
             [:p]    {:reaction nil :inputs [[:q] [:base]] :ref-count 1}
             [:q]    {:reaction nil :inputs [[:p]]         :ref-count 1}
             [:base] (or (get @cache [:base])
                         {:reaction nil :inputs [] :ref-count 1}))
      ;; Re-registering :base must evict :base AND its transitive dependents
      ;; :p (direct :<- [:base]) and :q (depends on :p) — and TERMINATE despite
      ;; the :p ↔ :q cycle.
      (rf/reg-sub :base (fn [db _] (* 100 (:n db))))
      (is (not (contains? @cache [:base])) "[:base] evicted (re-registered)")
      (is (not (contains? @cache [:p]))    "[:p] evicted (transitive dependent)")
      (is (not (contains? @cache [:q]))    "[:q] evicted (cyclic transitive dependent)"))))

;; ---- (3) :frame re-register preserves snapshot ---------------------------

(deftest frame-re-register-preserves-snapshot
  (testing "reg-frame on an already-registered id is a SURGICAL metadata
            update — live app-db (including [:rf.runtime/machines :snapshots] snapshot for
            active machine instances) is preserved"
    ;; Register a frame with an arbitrary doc.
    (rf/reg-frame :tenant {:doc       "v1 metadata"
                           :tenant-id :acme})
    ;; Build a tiny machine and dispatch into it so [:rf.runtime/machines :snapshots] is
    ;; populated. We use a machine handler so the snapshot lands at
    ;; [:rf.runtime/machines :snapshots :traffic-light] per Spec 005.
    (rf/reg-event :traffic-light
      (machines/make-machine-handler
        {:initial :red
         :data    {:ticks 0}
         :actions {:tick-action
                   (fn [{data :data}]
                     {:data (update data :ticks inc)})}
         :states
         {:red    {:on {:tick {:target :green :action :tick-action}}}
          :green  {:on {:tick {:target :yellow :action :tick-action}}}
          :yellow {:on {:tick {:target :red    :action :tick-action}}}}}))
    ;; Drive the machine forward twice in :tenant.
    (rf/dispatch-sync [:traffic-light [:tick]] {:frame :tenant})
    (rf/dispatch-sync [:traffic-light [:tick]] {:frame :tenant})
    ;; Capture pre-reregistration state.
    ;; EP-0001 (rf2-vzld77): machine snapshots are durable runtime-db state.
    (let [pre-rt          (rf/runtime-db-value :tenant)
          pre-snapshot    (get-in pre-rt [:rf.runtime/machines :snapshots :traffic-light])
          pre-app-db-cont (frame/app-db-container :tenant)]
      (is (= :yellow (:state pre-snapshot))
          "machine snapshot landed in [:rf.runtime/machines :snapshots] under :traffic-light")
      (is (= 2 (get-in pre-snapshot [:data :ticks]))
          "machine data accumulated across two transitions")
      ;; SURGICAL metadata update: re-register with new metadata.
      (rf/reg-frame :tenant {:doc       "v2 metadata"
                             :tenant-id :acme
                             :version   2})
      ;; The :app-db CONTAINER is the same identity (no replace happened).
      (is (identical? pre-app-db-cont (frame/app-db-container :tenant))
          "frame's app-db container is preserved (same identity)")
      ;; The [:rf.runtime/machines :snapshots] snapshot is preserved verbatim.
      (let [post-rt (rf/runtime-db-value :tenant)]
        (is (= pre-snapshot
               (get-in post-rt [:rf.runtime/machines :snapshots :traffic-light]))
            "machine snapshot is preserved across frame re-registration"))
      ;; Metadata was updated.
      (is (= "v2 metadata"
             (:doc (rf/frame-meta :tenant)))
          ":doc reflects the v2 metadata")
      (is (= 2 (:version (rf/frame-meta :tenant)))
          "new :version key is present in the merged metadata")
      ;; The machine still progresses against its preserved snapshot.
      (rf/dispatch-sync [:traffic-light [:tick]] {:frame :tenant})
      (let [final-snapshot (get-in (rf/runtime-db-value :tenant) [:rf.runtime/machines :snapshots :traffic-light])]
        (is (= :red (:state final-snapshot))
            "post-rereg :tick advances :yellow → :red — snapshot was live")
        (is (= 3 (get-in final-snapshot [:data :ticks]))
            "machine data continues to accumulate from the preserved snapshot")))))
