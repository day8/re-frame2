(ns re-frame.teardown-always-on-elision-prod-test
  "EP-0008 prod-elision coverage gap — rf2-9pxj70.

  EP-0008 promoted THREE teardown / write-race categories onto the
  ALWAYS-ON error-emit axis:

    :rf.error/frame-teardown-failed        (rf2-ini4wr — the bounded
                                            teardown report)
    :rf.error/write-after-destroy          (rf2-500ech — the dropped
                                            nil-container write)
    :rf.error/on-destroy-handler-exception (rf2-7b9r4l — the discriminable
                                            :on-destroy throw signal)

  Each was previously exercised ONLY by the DEV `:node-test` runner
  (`*_always_on_cljs_test.cljc` / `frame_teardown_report_cljs_test.cljc`,
  all matching `:ns-regexp \"cljs-test$\"`). The dev-mode tests assert the
  always-on listener FIRES, but they run with the trace surface LIVE — they
  cannot prove the central EP-0008 claim: that the always-on emission
  SURVIVES `:advanced` + `goog.DEBUG=false` where the dev trace is DCE'd.

  The sibling `re-frame.on-error-elision-prod-test` (rf2-2hvga / rf2-goum9x)
  pins exactly that production-survival contract for the OLDER always-on
  rows (handler-exception / frame-destroyed / no-such-* / fx). This file
  closes the gap for the NEW EP-0008-promoted rows under the prod build.

  NUANCE for `:rf.error/on-destroy-handler-exception` (rf2-7b9r4l): the
  dedicated category has TWO producers with DIFFERENT prod-survival:

    - COMMON path (the user `:on-destroy` handler throws): the dedicated
      record is re-emitted by `fire-on-destroy-event!` OBSERVING the
      router's `:rf.error/handler-exception` trace via
      `:trace.tooling/register-listener!` — a dev-only surface that DCEs.
      So the dedicated record does NOT survive prod for this path. The
      PRODUCTION SOURCE OF RECORD is the router's
      `:rf.error/handler-exception` (which DOES ride the always-on axis).
      This is the documented PARTIAL-COVERAGE NUANCE — dev-discrimination
      only by design, NOT a regression.

    - DEFENCE-IN-DEPTH branch (`dispatch-sync!` itself faults, rf2-bxud9v):
      `fire-on-destroy-event!` calls `emit-on-destroy-handler-exception!`
      DIRECTLY (no trace capture), so the dedicated record DOES survive
      prod — and this branch never produced a router handler-exception, so
      the always-on emission is its ONLY production observability.

  This file pins BOTH legs explicitly so the contract is unambiguous.

  Naming convention: files ending in `-elision-prod-test.cljs` are picked
  up ONLY by the `:browser-test-prod-elision` build (`:advanced` +
  `{goog.DEBUG false}`, `:ns-regexp \"-elision-prod-test$\"`, runner
  `re-frame.prod-elision-runner`). The default `:browser-test` / `:node-test`
  runners use regexes that do NOT match this suffix, so these tests run only
  under prod-mode compilation. (See `re-frame.on-error-elision-prod-test`
  for the convention header.)"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.error-emit :as error-emit]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn (fn []
                ;; Clear the always-on listener registry between tests —
                ;; the `defonce` atom would otherwise leak a listener.
                (error-emit/clear-error-listeners!))}))

;; ---------------------------------------------------------------------------
;; Cleanup-hook-key install helper (mirrors the dev-mode
;; `frame-teardown-report-cljs-test/with-hooks*`). The teardown cleanup
;; hooks are late-bound optional artefacts; we install a throwing fn under a
;; hook key for the extent of `f`, snapshotting + restoring the prior binding
;; so the install never leaks across tests.
;; ---------------------------------------------------------------------------

(defn- with-hooks*
  [hook-map f]
  (let [originals (into {} (map (fn [k] [k (late-bind/get-fn k)]) (keys hook-map)))]
    (try
      (doseq [[k v] hook-map] (late-bind/set-fn! k v))
      (f)
      (finally
        (doseq [[k orig] originals] (late-bind/set-fn! k orig))))))

(defn- throwing-hook [label]
  (fn [& _] (throw (ex-info (str "teardown hook threw: " label) {:hook label}))))

;; ===========================================================================
;; (a) :rf.error/frame-teardown-failed — the bounded teardown report survives
;; prod elision (rf2-ini4wr promotion).
;; ===========================================================================

(deftest frame-teardown-failed-report-survives-prod
  (testing "Per rf2-ini4wr / EP-0008: under `:advanced` + `goog.DEBUG=false`,
            N cleanup hooks throwing during `destroy-frame!` fan EXACTLY ONE
            always-on `:rf.error/frame-teardown-failed` record (carrying N
            `:hook-failures` entries) out through the corpus-wide
            `register-error-listener!` substrate — the always-on axis is NOT
            gated by `interop/debug-enabled?`, so the report survives where
            the per-hook `:rf.warning/teardown-hook-exception` dev trace is
            DCE'd. This is the production-build counterpart of the dev-mode
            `frame-teardown-report-cljs-test` (a)/(c) legs."
    (let [seen (atom [])]
      (rf/register-error-listener! :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-frame :prod.teardown/n-failures {:doc "three hooks will throw"})
      (with-hooks*
        {:ssr/on-frame-destroyed           (throwing-hook :ssr)
         :schemas/on-frame-destroyed!      (throwing-hook :schemas)
         :flows/teardown-on-frame-destroy! (throwing-hook :flows)}
        (fn [] (rf/destroy-frame! :prod.teardown/n-failures)))
      (let [reports (filter #(= :rf.error/frame-teardown-failed (:error %)) @seen)]
        (is (= 1 (count reports))
            "exactly ONE always-on report under prod elision — not three")
        (let [r (first reports)]
          (is (= :rf.error/frame-teardown-failed (:error r)))
          (is (= :prod.teardown/n-failures (:frame r))
              ":frame names the destroyed frame")
          (is (= 3 (count (:hook-failures r)))
              "the report carries one :hook-failures entry per failed hook")
          (is (= #{:ssr/on-frame-destroyed
                   :schemas/on-frame-destroyed!
                   :flows/teardown-on-frame-destroy!}
                 (set (map :hook (:hook-failures r))))
              "every failed hook key is represented under prod")
          (is (= :ignored (:recovery r))
              ":recovery :ignored — teardown is best-effort")
          (is (number? (:time r)) ":time is a wall-clock millis number"))))))

(deftest clean-destroy-emits-no-report-under-prod
  (testing "Per rf2-ini4wr: a clean destroy (no failing hook) emits NO
            `:rf.error/frame-teardown-failed` report under prod — the
            always-on fan-out short-circuits on an empty :hook-failures
            vector (no per-destroy flood in a `goog.DEBUG=false` SSR host)."
    (let [seen (atom [])]
      (rf/register-error-listener! :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-frame :prod.teardown/clean {:doc "no hooks throw"})
      (rf/destroy-frame! :prod.teardown/clean)
      (is (empty? (filter #(= :rf.error/frame-teardown-failed (:error %)) @seen))
          "no report when teardown completes cleanly under prod"))))

(deftest partial-teardown-abort-still-flushes-under-prod
  (testing "Per rf2-ini4wr EP-0008 R1: the finally-shaped flush survives prod
            elision too. Two cleanup hooks throw (accumulating two entries),
            then a downstream NON-hook teardown step throws unrecoverably —
            the throw propagates out of `destroy-frame!`, yet the always-on
            report STILL flushes the two entries gathered before the abort.
            Mirrors the dev-mode (b) leg under `:advanced` + `goog.DEBUG=
            false`."
    (let [seen (atom [])]
      (rf/register-error-listener! :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-frame :prod.teardown/abort {:doc "aborts mid-teardown"})
      (with-hooks*
        {:ssr/on-frame-destroyed      (throwing-hook :ssr)
         :schemas/on-frame-destroyed! (throwing-hook :schemas)}
        (fn []
          (with-redefs [frame/emit-frame-destroyed-trace!
                        (fn [_id] (throw (ex-info "mid-teardown collapse" {})))]
            (is (thrown? js/Error (rf/destroy-frame! :prod.teardown/abort))
                "the downstream teardown step's throw propagates"))))
      (let [reports (filter #(= :rf.error/frame-teardown-failed (:error %)) @seen)]
        (is (= 1 (count reports))
            "the report STILL flushed despite the mid-teardown abort under prod")
        (is (= 2 (count (:hook-failures (first reports))))
            "the report carries the TWO entries gathered before the abort")))))

;; ===========================================================================
;; (b) :rf.error/write-after-destroy — the dropped nil-container write rides
;; the always-on axis under prod (rf2-500ech promotion).
;; ===========================================================================

(deftest write-after-destroy-survives-prod
  (testing "Per rf2-500ech / EP-0008: under `:advanced` + `goog.DEBUG=false`,
            a `replace-container!` against a nil container (the scheduled-
            drain-vs-frame-destruction race) fans ONE
            `:rf.error/write-after-destroy` record out through the always-on
            `register-error-listener!` substrate — the promoted error
            category survives where the retired `:rf.warning/write-after-
            destroy` dev trace is DCE'd. This is the production-build
            counterpart of the dev-mode `write-after-destroy-always-on-cljs-
            test` (a)/(b) leg."
    (let [seen (atom [])]
      (rf/register-error-listener! :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      ;; The real production path: every frame :db write flows through this
      ;; choke point; a destroyed frame's container has gone nil.
      (adapter/replace-container! nil {:dropped :write})
      (let [reports (filter #(= :rf.error/write-after-destroy (:error %)) @seen)]
        (is (= 1 (count reports))
            "exactly ONE always-on record for the dropped write under prod")
        (let [r (first reports)]
          (is (= :rf.error/write-after-destroy (:error r))
              "the promoted error category — not the retired warning")
          (is (nil? (:event r))
              "no event vector — a dropped write, not a throw on a dispatch")
          (is (nil? (:frame r))
              "no frame — it was destroyed (the whole point of the race)")
          (is (nil? (:exception r))
              "no exception — a suppressed write, not a throw")
          (is (number? (:time r)) ":time is a wall-clock millis number"))))))

;; ===========================================================================
;; (c) :rf.error/on-destroy-handler-exception — the discriminable :on-destroy
;; throw signal survives prod (rf2-7b9r4l promotion).
;; ===========================================================================

(deftest on-destroy-common-path-prod-coverage-is-handler-exception
  (testing "Per rf2-7b9r4l PARTIAL-COVERAGE NUANCE: for the COMMON path (the
            user `:on-destroy` handler itself throws), the DEDICATED
            `:rf.error/on-destroy-handler-exception` category is
            dev-discrimination-ONLY by design — it is re-emitted by
            `fire-on-destroy-event!` observing the router's
            `:rf.error/handler-exception` TRACE, and that trace-capture
            mechanism (`:trace.tooling/register-listener!`) is a silent
            no-op under `:advanced` + `goog.DEBUG=false` (the trace surface
            is DCE'd). So under prod the dedicated record does NOT fire for
            the common path. The PRODUCTION SOURCE OF RECORD for the throw
            is the router's `:rf.error/handler-exception`, which DOES ride
            the always-on axis and survives elision (per rf2-7b9r4l: 'the
            underlying handler throw is ALSO caught by the router and emitted
            as :rf.error/handler-exception, which DOES ride the always-on
            axis -- so the failure is NOT fully silent in production'). This
            test pins exactly that contract under the prod build: NO
            dedicated record, but the handler-exception record survives. The
            discriminable dedicated signal for a path with NO router
            coverage — the dispatch-infra defence-in-depth branch — is pinned
            by `dispatch-sync-infra-fault-survives-prod` below."
    (let [seen (atom [])]
      (rf/register-error-listener! :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :prod.ondestroy/blow-up
                       (fn [_ _] (throw (ex-info "intentional :on-destroy throw"
                                                 {:purpose :test-fixture}))))
      (rf/reg-frame :prod.ondestroy/worker
                    {:doc        "throwing :on-destroy"
                     :on-destroy [:prod.ondestroy/blow-up]})
      (is (nil? (rf/destroy-frame! :prod.ondestroy/worker))
          "destroy-frame! returns nil even though :on-destroy threw under prod")
      (is (empty? (filter #(= :rf.error/on-destroy-handler-exception (:error %)) @seen))
          "the DEDICATED category does NOT survive prod for the common path
           (it rides the DCE'd trace-capture mechanism — dev-discrimination
           only, per rf2-7b9r4l)")
      (let [hx (filter #(= :rf.error/handler-exception (:error %)) @seen)]
        (is (= 1 (count hx))
            "the router's :rf.error/handler-exception IS the production
             source of record — it rides the always-on axis and survives
             elision")
        (let [r (first hx)]
          (is (= [:prod.ondestroy/blow-up] (:event r))
              ":event carries the :on-destroy event vector")
          (is (= :prod.ondestroy/blow-up (:event-id r))
              ":event-id is the event-vector head")
          (is (= :prod.ondestroy/worker (:frame r))
              ":frame names the frame being torn down")
          (is (some? (:exception r))
              ":exception carries the thrown object"))))
    (is (nil? (frame/frame :prod.ondestroy/worker))
        "the frame is fully torn down (teardown continued past the throw)")))

(deftest dispatch-sync-infra-fault-survives-prod
  (testing "Per rf2-7b9r4l / rf2-bxud9v: under `:advanced` + `goog.DEBUG=
            false`, if `dispatch-sync!` ITSELF faults (a fault inside the
            dispatch infrastructure, NOT the user handler),
            `fire-on-destroy-event!`'s defence-in-depth catch arm still fans
            a `:rf.error/on-destroy-handler-exception` record out on the
            always-on axis. This branch NEVER produced a router
            `:rf.error/handler-exception`, so the always-on emission here is
            its ONLY production observability — and this is the only test
            that proves it survives elision."
    (let [seen     (atom [])
          original (late-bind/get-fn :router/dispatch-sync!)]
      (rf/register-error-listener! :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-frame :prod.ondestroy/infra-fault
                    {:on-destroy [:prod.ondestroy/never-reached]})
      (late-bind/set-fn! :router/dispatch-sync!
                         (fn [& _] (throw (ex-info "dispatch infra fault" {}))))
      (try
        (is (nil? (rf/destroy-frame! :prod.ondestroy/infra-fault))
            "teardown does not propagate the infra fault under prod")
        (finally
          (late-bind/set-fn! :router/dispatch-sync! original)))
      (let [reports (filter #(= :rf.error/on-destroy-handler-exception (:error %)) @seen)]
        (is (= 1 (count reports))
            "the defence-in-depth branch fanned out on the always-on axis under prod")
        (is (some? (:exception (first reports)))
            ":exception carries the infra-fault throwable"))
      (is (nil? (frame/frame :prod.ondestroy/infra-fault))
          "the frame is still fully torn down despite the infra fault"))))
