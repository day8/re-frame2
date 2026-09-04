(ns re-frame.http-managed-demo-frame-isolation-cljs-test
  "rf2-o8ek audit — the managed-HTTP counter demo, mounted TWICE, must not
  cross-cancel.

  This is the end-to-end half of the audit's first escaping path. The demo at
  `examples/core/managed_http_counter/` seeds a request-id-keyed handle by hand
  so `:rf.http/managed-abort` has something real to resolve, and it writes its
  stable id (`:http-counter/long`) once — exactly the reusable-app-code shape
  Spec 014 §Frame scope exists for. rf2-o8ek made the registry keys frame-
  scoped, and the demo was updated to stamp `:frame` on its seeded handle, so
  it LOOKED migrated. Its abort closure still called the one-arg
  `clear-in-flight!`, which rf2-o8ek redefined as an ANY-FRAME sweep.

  Mounted in two frames, cancelling the first therefore invoked only the first
  frame's closure — correctly — and then deleted BOTH registry slots. The
  second frame's request stayed `:loading` with nothing left in the registry to
  abort it: a silent failure, because no abort fires in the second frame and no
  error is raised anywhere.

  The sibling JVM suite (`re-frame.http-frame-scoped-cancellation-test`) pins
  the registry contract this rests on. This test pins the DEMO, through its own
  events and the live `:rf.http/managed-abort` fx, because the defect was that
  a call site can be half-migrated while every registry-level test stays green.

  It belongs in the framework test tree, NOT under `examples/` (examples stay
  test-free per rf2-8cevm). It `:require`s `managed-http-counter.core` — an
  entry ns whose ns-load registers the demo's events / subs / fx — which
  resolves under the consolidated `:node-test` CLJS build, whose source paths
  carry both `http/test` and `../examples/core`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [managed-http-counter.core :as mhc]))

;; `:ambient-frame nil` — every dispatch below names its frame explicitly.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil}))

(defn- mount-and-start!
  "Boot a fresh anon frame and drive the demo's opening move, which seeds the
  request-id-keyed handle. Returns the frame ID — which is what the registry
  keys on and what `require-frame-stamp!` hands the abort seam, so it is also
  the value these assertions look slots up by."
  [doc]
  (let [f (rf.frame/make-anon-frame-record! {:doc doc})]
    (rf/dispatch-sync [:http-counter/start-long] {:frame f})
    f))

(deftest cancelling-one-mount-leaves-the-other-mounts-request-live
  (testing "rf2-o8ek audit — two mounts of the demo share one stable
            :request-id. Cancelling the first must abort the first and leave
            the second live, registered, and still abortable"
    (let [a (mount-and-start! "managed-http-counter mount A")
          b (mount-and-start! "managed-http-counter mount B")]
      (is (some? (rf.http.registry/lookup-in-flight a mhc/long-request-id))
          "mount A seeded its own frame-scoped slot")
      (is (some? (rf.http.registry/lookup-in-flight b mhc/long-request-id))
          "so did mount B, under the same raw id")
      (is (= :loading (:http-counter/status (rf/app-db-value a))))
      (is (= :loading (:http-counter/status (rf/app-db-value b))))

      ;; Cancel in A only, through the live :rf.http/managed-abort fx.
      (rf/dispatch-sync [:http-counter/cancel] {:frame a})

      (testing "mount A resolves normally"
        (is (nil? (rf.http.registry/lookup-in-flight a mhc/long-request-id))
            "A's slot is cleared by its own abort closure")
        (is (= :idle (:http-counter/status (rf/app-db-value a)))
            "A's UI eases back to idle")
        (is (= :rf.http/aborted (get-in (rf/app-db-value a) [:http-counter/error :kind]))
            "A records the classified abort"))

      (testing "mount B is untouched — the audit's silent failure"
        (is (some? (rf.http.registry/lookup-in-flight b mhc/long-request-id))
            "B's registry slot survives A's cancel; the one-arg ANY-FRAME clear deleted it")
        (is (= :loading (:http-counter/status (rf/app-db-value b)))
            "B is still loading, as it should be — nobody cancelled it")
        (is (nil? (:http-counter/error (rf/app-db-value b)))
            "and B recorded no abort, because none reached it"))

      (testing "B stays CANCELLABLE, which is what a swept slot silently costs"
        (rf/dispatch-sync [:http-counter/cancel] {:frame b})
        (is (nil? (rf.http.registry/lookup-in-flight b mhc/long-request-id)))
        (is (= :idle (:http-counter/status (rf/app-db-value b)))
            "B's own cancel resolves it — impossible once its slot has been swept away")
        (is (= :rf.http/aborted (get-in (rf/app-db-value b) [:http-counter/error :kind]))))

      (testing "each mount's reply was routed to its OWN frame"
        (is (= :idle (:http-counter/status (rf/app-db-value a)))
            "A did not receive a second reply from B's cancel")))))
