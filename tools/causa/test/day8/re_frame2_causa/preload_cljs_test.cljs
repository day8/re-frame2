(ns day8.re-frame2-causa.preload-cljs-test
  "Tests for the Causa Phase 1 foundation (rf2-n6x4q).

  ## Three contracts under test

  1. **Idempotency.** Loading the preload twice (shadow-cljs
     `:after-load` simulation) must not double-register the trace
     callback nor double-attach the keydown listener. The framework's
     `register-trace-listener!` semantics already collapse same-id
     registrations to one entry, but the warning trace the framework
     emits on replacement would pollute the dev console on every hot-
     reload; the preload's `defonce` sentinels prevent the warning
     altogether.

  2. **Frame isolation.** Causa's `:rf.causa/*` registrations target a
     frame named `:rf/causa` (per rf2-tijr Option C). Writing into
     `:rf/causa` must not bleed into the host's `:rf/default` frame.
     This test exercises the contract directly: it allocates both
     frames, dispatches into each, and asserts the dbs diverge.

  3. **Trace collector wiring.** After preload load, every dispatch /
     drain step / fx invocation that the framework's trace bus emits
     must appear in Causa's ring buffer. This test fires a dispatch
     against a registered handler and asserts the trace stream lands
     in Causa's buffer.

  ## Why these tests run on node-test (not browser-test)

  The foundation work this bead lands is registrations and pure-data
  ring-buffer manipulation. Neither the DOM nor a substrate's React-
  context tier is exercised. Browser-side concerns (mount, keydown
  listener, shell render) live in browser-test files filed under
  per-panel browser tests — keeping the foundation's tests
  on node-test keeps them fast and host-portable."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace :as trace]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------
;;
;; Per `re-frame.test-support` (rf2-am9d) the canonical CLJS test isolation
;; pattern is snapshot/restore. `(registrar/clear-all!)` is hostile here:
;; the framework-shipped registrations land at ns-load time and cannot
;; be re-loaded, so wiping the registrar between tests leaves any
;; subsequent test ns starting against an empty registry — and the
;; cross-test pollution shows up as failures in unrelated suites that
;; happened to run after ours.
;;
;; The fixture below adds an `init-fn` that flips Causa's defonce
;; sentinels back and clears Causa's per-process trace buffer. Each
;; test starts from the same baseline; framework state is preserved
;; via snapshot/restore from `reset-runtime-fixture`.

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- (1) idempotency ----------------------------------------------------

(deftest preload-trace-collector-is-idempotent
  (testing "calling register-trace-collector! twice attaches the collector once"
    ;; First call: the sentinel flips and the framework adds the
    ;; collector to its listener map. We don't poke into the
    ;; framework's private listener map here — we verify the
    ;; *observable* contract: a single emit produces a single buffer
    ;; append, even after a second registration call.
    (preload/register-trace-collector!)
    (preload/register-trace-collector!)
    (trace-bus/clear-buffer!)
    (trace/emit! :info :rf.test/idempotency-check {:source :test})
    (is (= 1 (count (trace-bus/buffer)))
        "duplicate registrations must not deliver the same event twice")))

(deftest registry-handlers-are-idempotent
  (testing "calling register-causa-handlers! twice registers the sub once"
    (registry/register-causa-handlers!)
    (let [first-handler (registrar/handler :sub :rf.causa/trace-buffer)]
      (is (some? first-handler) "first call registers :rf.causa/trace-buffer")
      (registry/register-causa-handlers!)
      (is (identical? first-handler
                      (registrar/handler :sub :rf.causa/trace-buffer))
          "second call does not replace the handler"))))

;; ---- (2) frame isolation ------------------------------------------------

(deftest causa-frame-state-is-isolated-from-host
  (testing "writes to :rf/causa do not bleed into :rf/default"
    (registry/register-causa-handlers!)
    ;; Register a host event under :rf/default that writes a marker
    ;; into the host's db.
    (rf/reg-event-db :test/host-write
      (fn [db _] (assoc db :host-touched? true)))
    ;; A Causa-side event under the :rf.causa/* prefix that writes a
    ;; marker into whatever frame is active at dispatch time. Causa's
    ;; runtime always dispatches under `with-frame :rf/causa`, so the
    ;; write lands in the Causa frame.
    (rf/reg-event-db :rf.causa/test-write
      (fn [db _] (assoc db :causa-touched? true)))

    ;; Allocate the Causa frame (the preload doesn't create it; it's
    ;; allocated lazily on first use).
    (frame/reg-frame :rf/causa {})

    ;; Host dispatch — lands in :rf/default.
    (rf/dispatch-sync [:test/host-write])
    ;; Causa dispatch — wrapped in with-frame so it lands in :rf/causa.
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/test-write]))

    (let [default-db (frame/frame-app-db-value :rf/default)
          causa-db   (frame/frame-app-db-value :rf/causa)]
      (is (true? (:host-touched? default-db))
          "host write reaches :rf/default")
      (is (nil? (:causa-touched? default-db))
          ":rf/default did NOT receive Causa's write")
      (is (true? (:causa-touched? causa-db))
          "Causa write reaches :rf/causa")
      (is (nil? (:host-touched? causa-db))
          ":rf/causa did NOT receive host's write"))))

;; ---- (3) trace collector wiring -----------------------------------------

(deftest trace-events-land-in-causa-buffer
  (testing "framework trace emissions appear in Causa's ring buffer"
    ;; Wire up the trace collector exactly as the preload would.
    (preload/register-trace-collector!)
    ;; A synthetic emit through the framework's trace bus — same path
    ;; used by every drain step / dispatch / fx call.
    (trace/emit! :info :rf.test/synthetic-event
                 {:source :test
                  :hint   "Phase 1 trace-collector smoke"})
    (let [buf (trace-bus/buffer)]
      (is (= 1 (count buf))
          "Causa's buffer receives the synthetic emit")
      (let [ev (first buf)]
        (is (= :rf.test/synthetic-event (:operation ev))
            "buffer carries the emitted operation")
        (is (= :info (:op-type ev))
            "buffer carries the emitted op-type")
        (is (= :test (:source ev))
            ":source hoisted to top level per Spec 009")))))

(deftest causa-buffer-evicts-oldest-on-overflow
  (testing "the Causa buffer respects its configured depth"
    (preload/register-trace-collector!)
    (trace-bus/set-buffer-depth! 3)
    (try
      (dotimes [i 5]
        (trace/emit! :info :rf.test/synthetic-overflow
                     {:n i :source :test}))
      (let [buf (trace-bus/buffer)]
        (is (= 3 (count buf))
            "buffer caps at the configured depth")
        (is (= [2 3 4] (mapv #(get-in % [:tags :n]) buf))
            "oldest entries evicted; newest retained in order"))
      (finally
        (trace-bus/set-buffer-depth! 1000)))))
