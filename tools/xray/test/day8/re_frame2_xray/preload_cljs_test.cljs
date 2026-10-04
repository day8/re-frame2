(ns day8.re-frame2-xray.preload-cljs-test
  "Tests for the Xray preload foundation.

  ## Two contracts under test

  1. **Idempotency.** Loading the preload twice (shadow-cljs
     `:after-load` simulation) must not double-register the trace
     callback nor double-attach the keydown listener. The framework's
     `register-listener!` semantics already collapse same-id
     registrations to one entry, but the warning trace the framework
     emits on replacement would pollute the dev console on every hot-
     reload; the preload's `defonce` sentinels prevent the warning
     altogether.

  2. **Trace collector wiring.** After preload load, every dispatch /
     drain step / fx invocation that the framework's trace bus emits
     must appear in Xray's ring buffer. This test fires a dispatch
     against a registered handler and asserts the trace stream lands
     in Xray's buffer.

  ## Why these tests run on node-test (not browser-test)

  The foundation under test is registrations and pure-data
  ring-buffer manipulation. Neither the DOM nor a substrate's React-
  context tier is exercised. Browser-side concerns (mount, keydown
  listener, shell render) live in the per-panel browser tests —
  keeping the foundation's tests
  on node-test keeps them fast and host-portable."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.ssr :as rf.ssr]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace]
            [re-frame.trace.projection :as rf.trace.projection]
            [day8.re-frame2-xray.panels.l2-timeline :as l2-timeline]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixtures -----------------------------------------------------------
;;
;; Per `re-frame.test-support` the canonical CLJS test isolation
;; pattern is snapshot/restore. `(rf.registrar/clear-all!)` is hostile here:
;; the framework-shipped registrations land at ns-load time and cannot
;; be re-loaded, so wiping the registrar between tests would leave any
;; subsequent test ns starting against an empty registry — and the
;; cross-test pollution would show up as failures in unrelated suites
;; that happen to run after ours.
;;
;; The fixture below adds an `init-fn` that flips Xray's defonce
;; sentinels back and clears Xray's per-process trace buffer. Each
;; test starts from the same baseline; framework state is preserved
;; via snapshot/restore from `make-reset-runtime-fixture`.

(defn- xray-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-collector/reset-for-test!))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn xray-init!}))

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
    (trace-collector/reset-for-test!)
    (rf.trace/emit! :info :rf.test/idempotency-check {:source :test})
    (is (= 1 (count (trace-collector/buffer-for-test)))
        "duplicate registrations must not deliver the same event twice")))

;; ---- (2) trace collector wiring -----------------------------------------

(deftest a-hydration-mismatch-reaches-xrays-buffer
  (testing "`verify-hydration!` runs after the first render, outside any
            dispatch, so its mismatch names its frame but carries no
            dispatch id. The framework's per-frame ring skips it, so Xray's
            secondary ring must keep it, and it surfaces as an issue in the
            `:ungrouped` bundle"
    (preload/register-trace-collector!)
    (rf.ssr/verify-hydration! :rf/default [:div "client"] {:server-hash "deadbeef"})
    (let [mismatch? #(= :rf.ssr/hydration-mismatch (:operation %))
          retained  (filter mismatch? (trace-collector/buffer-for-test))
          ev        (first retained)]
      (is (not-any? mismatch? (rf/trace-buffer :rf/default {:flat true}))
          "PRECONDITION: the framework's per-frame ring skips it")
      (is (= 1 (count retained)) "Xray's buffer keeps it")
      (is (= :rf/default (rf.trace/trace-event-frame ev)) "it names its frame")
      (is (nil? (get-in ev [:tags :rf.trace/dispatch-id])) "outside any run")
      (let [bundle (some #(when (= :ungrouped (:dispatch-id %)) %)
                         (rf.trace.projection/group-by-event
                           (trace-collector/buffer-for-test)))]
        (is (some mismatch? (:other bundle)) "grouped under :ungrouped")
        (is (l2-timeline/event-bundle-has-issue? bundle)
            "the bundle reads as an issue, so its L2 row takes the issue wash")))))

(deftest xray-frameless-ring-releases-evicted-events
  (testing "eviction leaves a plain vector holding only the kept
            events, on overflow and on shrink. A `subvec` view would keep its
            whole backing vector reachable and later `conj`s would extend that
            backing, so the visible count would stay at the depth while every
            evicted payload stayed live. A `PersistentVector` holds exactly its `count`
            elements, so its type bounds what the ring retains — which a
            count-only assertion cannot see."
    (trace-collector/set-frameless-ring-depth! 3)
    (try
      (dotimes [i 50]
        (trace-collector/seed-trace-for-test! {:id i :tags {}}))
      (let [ring (trace-collector/frameless-events)]
        (is (= [47 48 49] (mapv :id ring))
            "overflow keeps the newest events, oldest-first")
        (is (instance? PersistentVector ring)
            (str "overflow materializes a fresh vector, not a view over every "
                 "event ever pushed; got " (pr-str (type ring)))))
      (trace-collector/set-frameless-ring-depth! 2)
      (let [ring (trace-collector/frameless-events)]
        (is (= [48 49] (mapv :id ring))
            "shrinking drops the oldest events immediately")
        (is (instance? PersistentVector ring)
            (str "shrinking materializes a fresh vector, releasing the dropped "
                 "events; got " (pr-str (type ring)))))
      (trace-collector/clear-frameless-ring!)
      (is (= [] (trace-collector/frameless-events)) "clear still empties the ring")
      (finally
        (trace-collector/set-frameless-ring-depth!
          trace-collector/default-frameless-ring-depth)))))
