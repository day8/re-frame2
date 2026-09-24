(ns re-frame.teardown-always-on-elision-prod-test
  "EP-0008 prod-elision coverage for the teardown / write-race categories.

  EP-0008 puts THREE teardown / write-race categories on the ALWAYS-ON
  error-emit axis:

    :rf.error/frame-teardown-failed        (the bounded
                                            teardown report)
    :rf.error/write-after-destroy          (the dropped
                                            nil-container write)
    :rf.error/on-destroy-handler-exception (the discriminable
                                            :on-destroy throw signal)

  The DEV `:node-test` runner (`*_always_on_cljs_test.cljc` /
  `frame_teardown_report_cljs_test.cljc`, all matching `:ns-regexp
  \"cljs-test$\"`) asserts the always-on listener FIRES, but runs with the
  trace surface LIVE — it cannot prove the central EP-0008 claim: that the
  always-on emission SURVIVES `:advanced` + `goog.DEBUG=false` where the dev
  trace is DCE'd.

  The sibling `re-frame.on-error-elision-prod-test` pins that
  production-survival contract for the other always-on rows
  (handler-exception / frame-destroyed / no-such-* / fx). This file pins it
  for the EP-0008 rows under the prod build.

  CONTRACT for `:rf.error/on-destroy-handler-exception`: the dedicated
  category has TWO producers, and BOTH survive prod:

    - COMMON path (the user `:on-destroy` handler throws): the router
      converts the throw to an always-on `:rf.error/handler-exception`
      record (`emit-pipeline-exception!` → `rf.error-emit/dispatch-on-
      error!`, which is NOT `goog.DEBUG`-gated). `fire-on-destroy-event!`
      installs a TRANSIENT listener on that SAME always-on axis (via the
      `:error-emit/register-error-listener!` late-bind hook) for the
      duration of the dispatch, captures the record, and re-emits the
      dedicated `:rf.error/on-destroy-handler-exception` category. Because
      the capture rides the always-on axis (NOT the dev-only
      `trace.tooling` listener registry), the
      dedicated discriminable record SURVIVES `:advanced` +
      `goog.DEBUG=false` — exactly what the Spec 009 catalogue promises.
      The router's generic `:rf.error/handler-exception` ALSO fires (the
      production source of record for the handler throw itself); the
      dedicated category is the discriminator (it happened during
      destroy).

    - DEFENCE-IN-DEPTH branch (the private teardown cascade faults):
      `fire-on-destroy-event!` calls `emit-on-destroy-handler-exception!`
      DIRECTLY (no capture), so the dedicated record DOES survive prod —
      and this branch never produces a router handler-exception, so the
      always-on emission is its ONLY production observability.

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
            [re-frame.frame :as rf.frame]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn []
                ;; Clear the always-on listener registry between tests —
                ;; the `defonce` atom would otherwise leak a listener.
                (rf.error-emit/clear-error-listeners!))}))

;; ---------------------------------------------------------------------------
;; Cleanup-hook-key install helper (mirrors the dev-mode
;; `frame-teardown-report-cljs-test/with-hooks*`). The teardown cleanup
;; hooks are late-bound optional artefacts; we install a throwing fn under a
;; hook key for the extent of `f`, snapshotting + restoring the prior binding
;; so the install never leaks across tests.
;; ---------------------------------------------------------------------------

(defn- with-hooks*
  [hook-map f]
  (let [originals (into {} (map (fn [k] [k (rf.late-bind/get-fn k)]) (keys hook-map)))]
    (try
      (doseq [[k v] hook-map] (rf.late-bind/set-fn! k v))
      (f)
      (finally
        (doseq [[k orig] originals] (rf.late-bind/set-fn! k orig))))))

(defn- throwing-hook [label]
  (fn [& _] (throw (ex-info (str "teardown hook threw: " label) {:hook label}))))

;; ===========================================================================
;; (a) :rf.error/frame-teardown-failed — the bounded teardown report survives
;; prod elision.
;; ===========================================================================

(deftest frame-teardown-failed-report-survives-prod
  (testing "Per EP-0008: under `:advanced` + `goog.DEBUG=false`,
            N cleanup hooks throwing during `destroy-frame!` fan EXACTLY ONE
            always-on `:rf.error/frame-teardown-failed` record (carrying N
            `:hook-failures` entries) out through the corpus-wide
            `register-error-listener!` substrate — the always-on axis is NOT
            gated by `interop/debug-enabled?`, so the report survives where
            the per-hook `:rf.warning/teardown-hook-exception` dev trace is
            DCE'd. This is the production-build counterpart of the dev-mode
            `frame-teardown-report-cljs-test` (a)/(c) legs."
    (let [seen (atom [])]
      (rf.error-emit/register-error-listener! :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      ;; ENGINE seat — this prod-elision suite must not root the image path
      ;; (see elision_probe.cljs).
      (rf.frame/upsert-frame! :prod.teardown/n-failures {:doc "three hooks will throw"})
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
  (testing "A clean destroy (no failing hook) emits NO
            `:rf.error/frame-teardown-failed` report under prod — the
            always-on fan-out short-circuits on an empty :hook-failures
            vector (no per-destroy flood in a `goog.DEBUG=false` SSR host)."
    (let [seen (atom [])]
      (rf.error-emit/register-error-listener! :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf.frame/upsert-frame! :prod.teardown/clean {:doc "no hooks throw"})
      (rf/destroy-frame! :prod.teardown/clean)
      (is (empty? (filter #(= :rf.error/frame-teardown-failed (:error %)) @seen))
          "no report when teardown completes cleanly under prod"))))

(deftest partial-teardown-abort-still-flushes-under-prod
  (testing "Per EP-0008 R1: the finally-shaped flush survives prod
            elision too. Two cleanup hooks throw (accumulating two entries),
            then a downstream NON-hook teardown step throws unrecoverably —
            the throw propagates out of `destroy-frame!`, yet the always-on
            report STILL flushes the two entries gathered before the abort.
            Mirrors the dev-mode (b) leg under `:advanced` + `goog.DEBUG=
            false`."
    (let [seen (atom [])]
      (rf.error-emit/register-error-listener! :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf.frame/upsert-frame! :prod.teardown/abort {:doc "aborts mid-teardown"})
      (with-hooks*
        {:ssr/on-frame-destroyed      (throwing-hook :ssr)
         :schemas/on-frame-destroyed! (throwing-hook :schemas)}
        (fn []
          (with-redefs [rf.frame/emit-frame-destroyed-trace!
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
;; the always-on axis under prod.
;; ===========================================================================

(deftest write-after-destroy-survives-prod
  (testing "Per EP-0008: under `:advanced` + `goog.DEBUG=false`,
            a `replace-container!` against a nil container (the scheduled-
            drain-vs-frame-destruction race) fans ONE
            `:rf.error/write-after-destroy` record out through the always-on
            `register-error-listener!` substrate — the error category
            survives where a dev-only trace would be DCE'd. This is the production-build
            counterpart of the dev-mode `write-after-destroy-always-on-cljs-
            test` (a)/(b) leg."
    (let [seen (atom [])]
      (rf.error-emit/register-error-listener! :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      ;; The real production path: every frame :db write flows through this
      ;; choke point; a destroyed frame's container has gone nil.
      (rf.substrate.adapter/replace-container! nil {:dropped :write})
      (let [reports (filter #(= :rf.error/write-after-destroy (:error %)) @seen)]
        (is (= 1 (count reports))
            "exactly ONE always-on record for the dropped write under prod")
        (let [r (first reports)]
          (is (= :rf.error/write-after-destroy (:error r))
              "the error category — not a warning")
          (is (nil? (:event r))
              "no event vector — a dropped write, not a throw on a dispatch")
          (is (nil? (:frame r))
              "no frame — it was destroyed (the whole point of the race)")
          (is (nil? (:exception r))
              "no exception — a suppressed write, not a throw")
          (is (number? (:time r)) ":time is a wall-clock millis number"))))))

;; ===========================================================================
;; (c) :rf.error/on-destroy-handler-exception — the discriminable :on-destroy
;; throw signal survives prod.
;; ===========================================================================

(deftest on-destroy-common-path-dedicated-record-survives-prod
  (testing "Per Spec 009 §Error event catalogue: for the COMMON
            path (the user `:on-destroy` handler itself throws), the
            DEDICATED `:rf.error/on-destroy-handler-exception` category
            SURVIVES `:advanced` + `goog.DEBUG=false`. `fire-on-destroy-
            event!` captures the router's ALWAYS-ON
            `:rf.error/handler-exception` record (the router fans it out
            via `rf.error-emit/dispatch-on-error!`, NOT `goog.DEBUG`-gated)
            through a TRANSIENT listener installed on the SAME always-on
            axis (the `:error-emit/register-error-listener!` late-bind
            hook), then re-emits the dedicated category — so the
            discriminable teardown signal rides a production-survivable
            surface. A capture on the dev-only `trace.tooling` listener
            registry would DCE under prod, so the dedicated record would NOT
            fire for the common path despite the catalogue promising it
            does; this test catches that. BOTH the dedicated discriminator
            AND the router's generic `:rf.error/handler-exception` (the
            production source of record for the handler throw itself) must
            survive."
    (let [seen (atom [])]
      (rf.error-emit/register-error-listener! :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-event :prod.ondestroy/blow-up
                       (fn [{:keys [db]} _] {:db (throw (ex-info "intentional :on-destroy throw"
                                                 {:purpose :test-fixture}))}))
      (rf.frame/upsert-frame! :prod.ondestroy/worker {:doc        "throwing :on-destroy"
                      :on-destroy [:prod.ondestroy/blow-up]})
      (is (nil? (rf/destroy-frame! :prod.ondestroy/worker))
          "destroy-frame! returns nil even though :on-destroy threw under prod")
      (let [reports (filter #(= :rf.error/on-destroy-handler-exception (:error %)) @seen)]
        (is (= 1 (count reports))
            "the DEDICATED discriminable teardown category SURVIVES prod for
             the common path — EXACTLY ONE record")
        (let [r (first reports)]
          (is (= :rf.error/on-destroy-handler-exception (:error r)))
          (is (= :prod.ondestroy/worker (:frame r))
              ":frame names the frame being torn down")
          (is (= [:prod.ondestroy/blow-up] (:event r))
              ":event carries the :on-destroy event vector")
          (is (= :prod.ondestroy/blow-up (:event-id r))
              ":event-id is the event-vector head")
          (is (some? (:exception r))
              ":exception carries the thrown object")
          (is (number? (:time r)) ":time is a wall-clock millis number")))
      (let [hx (filter #(= :rf.error/handler-exception (:error %)) @seen)]
        (is (= 1 (count hx))
            "the router's :rf.error/handler-exception ALSO survives — the
             production source of record for the handler throw itself")
        (let [r (first hx)]
          (is (= [:prod.ondestroy/blow-up] (:event r))
              ":event carries the :on-destroy event vector")
          (is (= :prod.ondestroy/blow-up (:event-id r))
              ":event-id is the event-vector head")
          (is (= :prod.ondestroy/worker (:frame r))
              ":frame names the frame being torn down")
          (is (some? (:exception r))
              ":exception carries the thrown object"))))
    (is (nil? (rf.frame/frame :prod.ondestroy/worker))
        "the frame is fully torn down (teardown continued past the throw)")))

(deftest teardown-cascade-infra-fault-survives-prod
  (testing "Under `:advanced` + `goog.DEBUG=
            false`, if the internal teardown cascade ITSELF faults (a fault inside the
            dispatch infrastructure, NOT the user handler),
            `fire-on-destroy-event!`'s defence-in-depth catch arm still fans
            a `:rf.error/on-destroy-handler-exception` record out on the
            always-on axis. This branch NEVER produces a router
            `:rf.error/handler-exception`, so the always-on emission here is
            its ONLY production observability — and this is the only test
            that proves it survives elision."
    (let [seen     (atom [])
          original (rf.late-bind/get-fn :router/run-frame-destroy-event!)]
      (rf.error-emit/register-error-listener! :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf.frame/upsert-frame! :prod.ondestroy/infra-fault {:on-destroy [:prod.ondestroy/never-reached]})
      (rf.late-bind/set-fn! :router/run-frame-destroy-event!
                         (fn [& _] (throw (ex-info "dispatch infra fault" {}))))
      (try
        (is (nil? (rf/destroy-frame! :prod.ondestroy/infra-fault))
            "teardown does not propagate the infra fault under prod")
        (finally
          (rf.late-bind/set-fn! :router/run-frame-destroy-event! original)))
      (let [reports (filter #(= :rf.error/on-destroy-handler-exception (:error %)) @seen)]
        (is (= 1 (count reports))
            "the defence-in-depth branch fanned out on the always-on axis under prod")
        (is (some? (:exception (first reports)))
            ":exception carries the infra-fault throwable"))
      (is (nil? (rf.frame/frame :prod.ondestroy/infra-fault))
          "the frame is still fully torn down despite the infra fault"))))
