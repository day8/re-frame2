(ns re-frame.on-error-test
  "Per rf2-hqbeh — exercise the per-frame `:on-error` slot end-to-end:

    - Policy fn fires when a registered handler throws.
    - Policy fn receives a structured error event with
      `:operation :rf.error/handler-exception`, `:op-type :error`,
      and `:tags {:event-id ..., :frame ..., :exception ..., ...}`.
    - Policy fn's own exceptions are caught inside the always-on
      substrate per Spec 009 §1052 — the drain settles, the cascade
      does not abort.
    - Re-registration of the frame replaces the `:on-error` slot;
      the old policy fn does NOT fire after replacement.

  Per rf2-bacs4 — exercise the corpus-wide
  `register-error-listener!` registry alongside the per-frame
  slot:

    - A registered listener fires per `:rf.error/*` event.
    - Listener exceptions are swallowed; siblings still run.
    - Unregistering a listener stops it receiving subsequent events.
    - The listener and the per-frame `:on-error` policy fn are
      INDEPENDENT — a buggy listener cannot block the policy fn, and
      a buggy policy fn cannot block listeners.
    - The error-record shape is TIGHT: exactly
      `{:error :event :event-id :frame :time :exception :elapsed-ms}`.
    - `:elapsed-ms` is an integer on every platform (rf2-ph8pa
      contract).

  Dev-side coverage (runs under `:node-test` / `:browser-test` /
  `clojure -M:test`). The CLJS production-mode counterpart lives in
  `re-frame.on-error-elision-prod-test` — that suite pins the same
  contract under `:advanced` + `goog.DEBUG=false`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                ;; Per rf2-bacs4: the listener registry is a `defonce`
                ;; atom that survives test re-runs. Clear before each
                ;; test so a listener registered by one test doesn't
                ;; leak into the next.
                (error-emit/clear-error-listeners!))}))

;; ============================================================================
;; rf2-hqbeh — per-frame :on-error policy
;; ============================================================================

(deftest on-error-fires-when-handler-throws
  (testing "Per rf2-hqbeh / Spec 009 §Error-handler policy: a frame
            with `:on-error` set sees the structured error event when a
            registered handler throws."
    (let [seen (atom [])]
      (rf/reg-frame :rf/default
                    {:on-error (fn [error-event]
                                 (swap! seen conj error-event)
                                 nil)})
      (rf/reg-event-db :on-error-test/throw
                       (fn [_db _]
                         (throw (ex-info "boom" {:cause :test}))))
      (rf/dispatch-sync [:on-error-test/throw])
      (is (= 1 (count @seen))
          ":on-error fired exactly once")
      (let [ev (first @seen)]
        (is (= :rf.error/handler-exception (:operation ev)))
        (is (= :error (:op-type ev)))
        (is (= :on-error-test/throw (get-in ev [:tags :event-id])))
        (is (= :rf/default (get-in ev [:tags :frame])))
        (is (= :no-recovery (get-in ev [:tags :recovery])))))))

(deftest on-error-policy-exception-does-not-propagate
  (testing "Per Spec 009 §1052: an `:on-error` policy fn that throws
            does NOT take down the drain — its exception is caught
            inside the always-on substrate."
    (rf/reg-frame :rf/default
                  {:on-error (fn [_error-event]
                               (throw (ex-info "policy threw" {})))})
    (rf/reg-event-db :on-error-test/policy-throw
                     (fn [_db _]
                       (throw (ex-info "handler threw" {}))))
    ;; The dispatch must return normally — the policy fn's exception is
    ;; caught by error-emit/dispatch-on-error!.
    (is (nil? (rf/dispatch-sync [:on-error-test/policy-throw])))))

(deftest re-registration-replaces-on-error
  (testing "Per Spec 002 §Re-registration — surgical update: re-
            registering the frame replaces the `:on-error` slot; the
            previous policy fn does NOT fire after replacement."
    (let [first-policy-fires  (atom 0)
          second-policy-fires (atom 0)]
      (rf/reg-frame :rf/default
                    {:on-error (fn [_ev] (swap! first-policy-fires inc))})
      (rf/reg-frame :rf/default
                    {:on-error (fn [_ev] (swap! second-policy-fires inc))})
      (rf/reg-event-db :on-error-test/throw-after-rereg
                       (fn [_db _]
                         (throw (ex-info "boom" {}))))
      (rf/dispatch-sync [:on-error-test/throw-after-rereg])
      (is (zero? @first-policy-fires)
          "old :on-error did not fire after re-registration")
      (is (= 1 @second-policy-fires)
          "new :on-error fired exactly once"))))

(deftest no-on-error-registered-is-a-no-op
  (testing "When the frame's config carries no `:on-error`, the always-
            on substrate quietly no-ops and the rest of the runtime
            proceeds with the documented per-category recovery."
    (rf/reg-event-db :on-error-test/no-policy-throw
                     (fn [_db _]
                       (throw (ex-info "boom" {}))))
    ;; :rf/default is registered by the fixture with no :on-error.
    (is (nil? (rf/dispatch-sync [:on-error-test/no-policy-throw])))))

;; ============================================================================
;; rf2-bacs4 — corpus-wide register-error-listener!
;; ============================================================================

(deftest error-listener-fires-on-handler-exception
  (testing "Per rf2-bacs4: a registered listener receives exactly one
            tight error-record per `:rf.error/handler-exception`. The
            record's shape is fixed: `:error :event :event-id :frame
            :time :exception :elapsed-ms`, plus `:source-coord` when
            the failing handler was registered via the public macro
            path (per rf2-3un2g §Always-on error-coord registry —
            programmatic registrations omit the slot rather than nil
            it; the macro-path test below sees it present)."
    (let [seen (atom [])]
      (rf/register-error-listener!
        :test/recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :err/throw
                       (fn [_db _]
                         (throw (ex-info "kaboom" {:cause :test}))))
      (rf/dispatch-sync [:err/throw])
      (is (= 1 (count @seen))
          "listener fired exactly once for one thrown handler")
      (let [r (first @seen)]
        (is (= :rf.error/handler-exception (:error r)))
        (is (= [:err/throw]    (:event r)))
        (is (= :err/throw      (:event-id r)))
        (is (= :rf/default     (:frame r)))
        (is (some? (:exception r))           ":exception present")
        (is (number? (:time r))              ":time is a wall-clock millis number")
        (is (integer? (:elapsed-ms r))       ":elapsed-ms is an integer ms count")
        (is (not (neg? (:elapsed-ms r)))     ":elapsed-ms is non-negative")
        ;; Per rf2-3un2g: `:source-coord` rides the tight record when the
        ;; failing handler was registered via the public macro path. The
        ;; coord rides the always-on parallel `error-coords-by-id`
        ;; registry, so this slot survives `goog.DEBUG=false` (Sentry-
        ;; style observability preserved in production).
        (is (= #{:error :event :event-id :frame :time :exception :elapsed-ms
                 :source-coord}
               (set (keys r)))
            "record carries the tight rf2-bacs4 keys plus rf2-3un2g
             :source-coord — no trace-bus enrichment beyond that")
        (let [sc (:source-coord r)]
          (is (symbol?  (:ns   sc)))
          (is (integer? (:line sc)))
          (is (string?  (:file sc))))))))

(deftest error-listener-exception-is-swallowed
  (testing "Per the substrate contract: a buggy listener cannot break
            the cascade OR prevent sibling listeners from running.
            Listener throws are caught inside the substrate and
            silently dropped — no recursive emit, no propagation to
            user code."
    (let [seen (atom [])]
      (rf/register-error-listener!
        :test/throws
        (fn [_record]
          (throw (ex-info "listener went boom" {}))))
      (rf/register-error-listener!
        :test/sibling
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :err/throw2
                       (fn [_db _]
                         (throw (ex-info "handler kaboom" {}))))
      ;; Must NOT throw — the listener's exception is swallowed.
      (is (nil? (rf/dispatch-sync [:err/throw2]))
          "dispatch-sync returned nil despite the listener throw")
      (is (= 1 (count @seen))
          "the sibling listener still received the record — fan-out is
           defensive across listeners"))))

(deftest error-listener-unregister-stops-delivery
  (testing "Per rf2-bacs4: unregistering a listener stops it receiving
            subsequent events. Re-registering under the same id
            reattaches it."
    (let [seen (atom [])]
      (rf/register-error-listener!
        :test/recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :err/throw3 (fn [_db _] (throw (ex-info "x" {}))))
      (rf/dispatch-sync [:err/throw3])
      (is (= 1 (count @seen)) "listener fired before unregister")
      (rf/unregister-error-listener! :test/recorder)
      (rf/dispatch-sync [:err/throw3])
      (is (= 1 (count @seen)) "listener silent after unregister")
      ;; Re-register under the same id and dispatch again.
      (rf/register-error-listener!
        :test/recorder
        (fn [record] (swap! seen conj record)))
      (rf/dispatch-sync [:err/throw3])
      (is (= 2 (count @seen))
          "listener fired again after re-registration under the same id"))))

(deftest listener-and-on-error-policy-fire-independently
  (testing "Per rf2-bacs4: the corpus-wide listener registry and the
            per-frame `:on-error` policy fn fire from ONE normative
            emission site along TWO independent fan-out paths. Both
            see the same handler exception; a buggy listener cannot
            block the policy fn, and a buggy policy fn cannot block
            listeners."
    (let [listener-saw (atom nil)
          policy-saw   (atom nil)]
      (rf/reg-frame :rf/default
                    {:on-error (fn [ev] (reset! policy-saw ev) nil)})
      (rf/register-error-listener!
        :test/recorder
        (fn [record] (reset! listener-saw record)))
      (rf/reg-event-db :err/both
                       (fn [_db _] (throw (ex-info "boom" {}))))
      (rf/dispatch-sync [:err/both])
      ;; Both paths fired.
      (is (some? @policy-saw)   "per-frame :on-error policy fired")
      (is (some? @listener-saw) "corpus-wide listener fired")
      ;; The two paths carry DIFFERENT shapes — the policy fn receives
      ;; the legacy structured error-event (with `:operation`/`:tags`),
      ;; the listener receives the tight record (rf2-bacs4 shape).
      (is (= :rf.error/handler-exception (:operation @policy-saw))
          "policy fn received the legacy structured shape")
      (is (= :rf.error/handler-exception (:error @listener-saw))
          "listener received the tight record shape")
      (is (= :err/both (:event-id @listener-saw))))))

(deftest listener-isolation-policy-throw-does-not-block-listener
  (testing "Per rf2-bacs4 §independent paths: a policy-fn throw is
            caught by the substrate and does NOT prevent the
            corpus-wide listener from firing — the two fan-out paths
            are mutually isolated."
    (let [listener-saw (atom nil)]
      (rf/reg-frame :rf/default
                    {:on-error (fn [_ev] (throw (ex-info "policy boom" {})))})
      (rf/register-error-listener!
        :test/recorder
        (fn [record] (reset! listener-saw record)))
      (rf/reg-event-db :err/policy-throws
                       (fn [_db _] (throw (ex-info "handler boom" {}))))
      (is (nil? (rf/dispatch-sync [:err/policy-throws]))
          "dispatch settled despite both handler and policy throwing")
      (is (some? @listener-saw)
          "corpus-wide listener fired even though policy fn threw"))))

(deftest listener-isolation-listener-throw-does-not-block-policy
  (testing "Per rf2-bacs4 §independent paths: a listener throw is
            caught by the substrate and does NOT prevent the
            per-frame `:on-error` policy fn from firing."
    (let [policy-saw (atom nil)]
      (rf/reg-frame :rf/default
                    {:on-error (fn [ev] (reset! policy-saw ev) nil)})
      (rf/register-error-listener!
        :test/throws
        (fn [_record] (throw (ex-info "listener boom" {}))))
      (rf/reg-event-db :err/listener-throws
                       (fn [_db _] (throw (ex-info "handler boom" {}))))
      (is (nil? (rf/dispatch-sync [:err/listener-throws]))
          "dispatch settled despite both handler and listener throwing")
      (is (some? @policy-saw)
          "per-frame :on-error policy fired even though listener threw"))))

(deftest listener-elapsed-ms-is-integer
  (testing "Per rf2-bacs4 §Record shape + rf2-ph8pa contract:
            `:elapsed-ms` MUST be an integer on every platform. CLJS
            `performance.now()` returns a float; the substrate
            rounds at the boundary so the contract holds."
    (let [seen (atom nil)]
      (rf/register-error-listener!
        :test/recorder
        (fn [record] (reset! seen record)))
      (rf/reg-event-db :err/elapsed (fn [_db _] (throw (ex-info "x" {}))))
      (rf/dispatch-sync [:err/elapsed])
      (let [r @seen]
        (is (some? r))
        (is (integer? (:elapsed-ms r))
            ":elapsed-ms is integer on every platform (no float leak from
             CLJS performance.now())")))))

;; ============================================================================
;; (removed) rf2-vnjfg handler-meta :sensitive? error-path redaction
;; ----------------------------------------------------------------------------
;; The handler-meta `:sensitive?` annotation has been removed. Redaction on
;; the error-emit substrate is now driven exclusively by the per-path elision
;; wire-walker (per-frame `:rf/elision` registry, populated from app-schema
;; `:sensitive?` slot meta). Path-marked classification supersedes the
;; previous handler-level enforcement.
;; ============================================================================

(deftest non-sensitive-handler-error-payload-flows-through
  (testing "Without handler-meta `:sensitive?`, error records surface
            the elided event vector (subject to the per-path wire
            walker only) so off-box error observability sees the
            event taxonomy."
    (let [seen (atom nil)]
      (rf/register-error-listener!
        :test/recorder
        (fn [record] (reset! seen record)))
      (rf/reg-event-db :err/normal-throw
                       (fn [_db _] (throw (ex-info "boom" {}))))
      (rf/dispatch-sync [:err/normal-throw {:k "v"}])
      (let [r @seen]
        (is (some? r))
        (is (vector? (:event r))
            "event vector flows through (subject only to per-path wire elision)")
        (is (= :err/normal-throw (first (:event r))))))))
