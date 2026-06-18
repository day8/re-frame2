(ns re-frame.on-destroy-handler-exception-always-on-cljs-test
  "EP-0008 / rf2-7b9r4l — `:rf.error/on-destroy-handler-exception` promoted
  onto the ALWAYS-ON axis.

  `frame/fire-on-destroy-event!` runs the user `:on-destroy` event during
  `destroy-frame!`. A throw MUST NOT abort teardown (Spec 002 §`:on-destroy`
  handler throw semantics, rf2-r1ciy decision b). The dedicated
  `:rf.error/on-destroy-handler-exception` category is the DISCRIMINABLE
  teardown signal — the router ALSO surfaces the throw as a generic
  `:rf.error/handler-exception` (the production source of record for the
  handler throw), but the discriminator (it happened during destroy) rode
  only the DCE'd dev trace before this promotion. EP-0008 routes the
  dedicated category through the always-on error-emit axis so an operator on
  a `goog.DEBUG=false` host can tell a teardown failure (a resource-leakage
  class) from a generic handler throw.

  Pins the promotion's two acceptance legs (per EP §Conformance — every
  always-on category is exercised through `register-error-listener!`,
  proving production survival):

    (a) the common path: a throwing `:on-destroy` handler (the router
        converts it to a `:rf.error/handler-exception` trace, which
        `fire-on-destroy-event!` captures and re-emits under the dedicated
        category) fans a `:rf.error/on-destroy-handler-exception` record out
        through the corpus-wide `register-error-listener!` substrate — the
        always-on axis, NOT gated by `interop/debug-enabled?`.

    (b) the rf2-bxud9v defence-in-depth re-throw branch: if `dispatch-sync!`
        ITSELF faults (not the user handler — a fault inside the dispatch
        infrastructure), the dedicated category still fans out on the
        always-on axis. This branch never produced a router
        `:rf.error/handler-exception`, so before EP-0008 it had ZERO
        production observability.

  Both records carry `:recovery :ignored` (teardown continues best-effort).

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build AND the JVM `clojure -M:test` runner both pick it up. The destroy
  path is plain CLJC; no DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.error-emit :as error-emit]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

;; ---------------------------------------------------------------------------
;; Fixture — fresh registrar + plain-atom adapter per test; the always-on
;; error-listener registry cleared so a listener from one test cannot leak.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (error-emit/clear-error-listeners!))}))

;; ===========================================================================
;; (a) Common path — a throwing :on-destroy handler fans the dedicated
;; category out on the ALWAYS-ON axis.
;; ===========================================================================

(deftest throwing-on-destroy-fans-out-on-always-on-axis
  (testing "Per rf2-7b9r4l / Spec 009 §Error event catalogue: a throwing
            `:on-destroy` handler produces a
            `:rf.error/on-destroy-handler-exception` record on the always-on
            `register-error-listener!` substrate — the discriminable
            teardown signal that survives `goog.DEBUG=false`, NOT just the
            DCE'd dev trace."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-event :ondestroy/blow-up
                       (fn [{:keys [db]} _] {:db (throw (ex-info "intentional :on-destroy throw"
                                                 {:purpose :test-fixture}))}))
      (rf/reg-frame :ondestroy/worker
                    {:doc        "throwing :on-destroy"
                     :on-destroy [:ondestroy/blow-up]})
      ;; Teardown MUST NOT propagate the throw.
      (is (nil? (rf/destroy-frame! :ondestroy/worker))
          "destroy-frame! returns nil even though :on-destroy threw")
      (let [reports (filter #(= :rf.error/on-destroy-handler-exception (:error %)) @seen)]
        (is (= 1 (count reports))
            "exactly ONE always-on record for the dedicated category")
        (let [r (first reports)]
          (is (= :rf.error/on-destroy-handler-exception (:error r))
              "the dedicated discriminable teardown category")
          (is (= :ondestroy/worker (:frame r))
              ":frame names the frame being torn down")
          (is (= [:ondestroy/blow-up] (:event r))
              ":event carries the :on-destroy event vector")
          (is (= :ondestroy/blow-up (:event-id r))
              ":event-id is the event-vector head")
          (is (some? (:exception r))
              ":exception carries the thrown object")
          (is (number? (:time r)) ":time is a wall-clock millis number")))))

  (testing "Per rf2-7b9r4l: the teardown still completes end-to-end despite
            the throw — the frame is fully removed from the registry."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-event :ondestroy/blow-up-2
                       (fn [{:keys [db]} _] {:db (throw (ex-info "again" {}))}))
      (rf/reg-frame :ondestroy/teardown
                    {:on-destroy [:ondestroy/blow-up-2]})
      (rf/destroy-frame! :ondestroy/teardown)
      (is (nil? (frame/frame :ondestroy/teardown))
          "the frame is fully torn down (teardown continued past the throw)")
      (is (seq (filter #(= :rf.error/on-destroy-handler-exception (:error %)) @seen))
          "the always-on record still fired"))))

(deftest clean-on-destroy-emits-no-record
  (testing "Per rf2-7b9r4l: a non-throwing `:on-destroy` emits NO
            `:rf.error/on-destroy-handler-exception` record."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-event :ondestroy/clean (fn [{:keys [db]} _] {:db db}))
      (rf/reg-frame :ondestroy/ok {:on-destroy [:ondestroy/clean]})
      (rf/destroy-frame! :ondestroy/ok)
      (is (empty? (filter #(= :rf.error/on-destroy-handler-exception (:error %)) @seen))
          "no record when :on-destroy completes cleanly"))))

;; ===========================================================================
;; (b) The rf2-bxud9v defence-in-depth re-throw branch — `dispatch-sync!`
;; ITSELF faults. This branch never produced a router handler-exception, so
;; the always-on emission here is its ONLY production observability.
;; ===========================================================================

(deftest dispatch-sync-infra-fault-fans-out-on-always-on-axis
  (testing "Per rf2-7b9r4l / rf2-bxud9v: if `dispatch-sync!` itself throws
            (a fault inside the dispatch infrastructure, NOT the user
            handler), `fire-on-destroy-event!`'s defence-in-depth catch arm
            still fans a `:rf.error/on-destroy-handler-exception` record out
            on the always-on axis — the ONLY production coverage for this
            branch (it never produced a router :rf.error/handler-exception)."
    (let [seen     (atom [])
          original (late-bind/get-fn :router/dispatch-sync!)]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-frame :ondestroy/infra-fault
                    {:on-destroy [:ondestroy/never-reached]})
      ;; Make the dispatch-sync! infrastructure itself fault.
      (late-bind/set-fn! :router/dispatch-sync!
                         (fn [& _] (throw (ex-info "dispatch infra fault" {}))))
      (try
        (is (nil? (rf/destroy-frame! :ondestroy/infra-fault))
            "teardown does not propagate the infra fault")
        (finally
          (late-bind/set-fn! :router/dispatch-sync! original)))
      (let [reports (filter #(= :rf.error/on-destroy-handler-exception (:error %)) @seen)]
        (is (= 1 (count reports))
            "the defence-in-depth branch fanned out on the always-on axis")
        (let [r (first reports)]
          (is (= :ondestroy/infra-fault (:frame r))
              ":frame names the frame being torn down")
          (is (some? (:exception r))
              ":exception carries the infra-fault throwable")))
      (is (nil? (frame/frame :ondestroy/infra-fault))
          "the frame is still fully torn down despite the infra fault"))))

;; ===========================================================================
;; (c) Nested / overlapping destroy — the transient capture listener uses a
;; UNIQUE per-destroy key (rf2-ntv9i9.1 #2), so a nested destroy cannot clobber
;; the outer destroy's listener and drop its dedicated record.
;; ---------------------------------------------------------------------------
;; `fire-on-destroy-event!` installs a transient listener on the always-on
;; error-emit registry for the duration of the `:on-destroy` dispatch. Before
;; rf2-ntv9i9.1 the listener key was the CONSTANT `::on-destroy-throw-watch`.
;; Spec 002 (rf2-r1ciy) supports a nested `destroy-frame!` for a DIFFERENT id
;; from inside an `:on-destroy` handler. With a constant key the inner destroy
;; RE-REGISTERED under the same key (replacing the outer's listener) and then
;; DROPPED it on the inner's finally — so when the OUTER frame's own throwing
;; `:on-destroy` later fired its `:rf.error/handler-exception`, no listener was
;; watching and the dedicated `:rf.error/on-destroy-handler-exception` was
;; SILENTLY DROPPED. The unique per-destroy key gives each extent its own
;; listener: both records survive.
;; ===========================================================================

(deftest nested-destroy-does-not-clobber-outer-on-destroy-capture
  (testing "rf2-ntv9i9.1 #2 — an outer frame A whose throwing `:on-destroy`
            ALSO triggers a nested `destroy-frame!` of a DIFFERENT frame B
            (whose `:on-destroy` ALSO throws) yields TWO independent
            `:rf.error/on-destroy-handler-exception` records — one per frame.
            With the former constant listener key the inner (B) destroy
            clobbered A's transient capture listener, so A's dedicated record
            was dropped. The unique per-destroy key keeps both."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      ;; B: the inner frame; its :on-destroy throws.
      (rf/reg-event :ondestroy/inner-throw
                       (fn [{:keys [db]} _] {:db (throw (ex-info "inner B :on-destroy threw" {}))}))
      (rf/reg-frame :ondestroy/inner-B
                    {:on-destroy [:ondestroy/inner-throw]})
      ;; A's :on-destroy: destroy B (nested), THEN throw. The nested destroy of
      ;; B runs fully (incl. B's own transient capture install + finally
      ;; remove) BEFORE A's throw — so a constant key would have removed A's
      ;; listener by the time A's handler-exception fired.
      (rf/reg-event :ondestroy/outer-throw
                       (fn [{:keys [db]} _]
                         (rf/destroy-frame! :ondestroy/inner-B)
                         {:db (throw (ex-info "outer A :on-destroy threw" {}))}))
      (rf/reg-frame :ondestroy/outer-A
                    {:on-destroy [:ondestroy/outer-throw]})
      (is (nil? (rf/destroy-frame! :ondestroy/outer-A))
          "the outer destroy returns nil despite both :on-destroy throws")
      (let [reports  (filter #(= :rf.error/on-destroy-handler-exception (:error %)) @seen)
            by-frame (set (map :frame reports))]
        (is (= 2 (count reports))
            "TWO dedicated records — one for A, one for B (no clobber)")
        (is (contains? by-frame :ondestroy/outer-A)
            "the OUTER (A) record survived — the unique key was not clobbered
             by the nested B destroy (the regression: this was dropped)")
        (is (contains? by-frame :ondestroy/inner-B)
            "the INNER (B) record fired too")))))

;; ===========================================================================
;; The late-bind hook the producer reaches error-emit through is published.
;; ===========================================================================

(deftest dispatch-on-error-late-bind-hook-is-published
  (testing "Per rf2-7b9r4l: frame.cljc reaches `dispatch-on-error!` via the
            `:error-emit/dispatch-on-error` late-bind hook (a static require
            would close the error-emit -> elision -> frame load cycle); the
            hook is published at error-emit ns-load, so the lookup never
            misses in production."
    (is (some? (late-bind/get-fn :error-emit/dispatch-on-error))
        "the hook is registered at error-emit ns-load")))
