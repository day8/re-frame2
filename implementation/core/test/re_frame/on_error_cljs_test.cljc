(ns re-frame.on-error-cljs-test
  "Per rf2-bacs4 — exercise the corpus-wide `register-error-listener!`
  registry (the single always-on error observability surface; the
  per-frame `:on-error` recovery policy was REMOVED per rf2-hiqtk8 —
  recovery is framework-owned via the per-category typed defaults, not an
  app-steering policy):

    - A registered listener fires per `:rf.error/*` event.
    - Listener exceptions are swallowed; siblings still run.
    - Unregistering a listener stops it receiving subsequent events.
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
            [re-frame.frame :as frame]
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
      (rf/register-listener! :errors
        :test/recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event :err/throw
                       (fn [{:keys [db]} _]
                         {:db (throw (ex-info "kaboom" {:cause :test}))}))
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
      (rf/register-listener! :errors
        :test/throws
        (fn [_record]
          (throw (ex-info "listener went boom" {}))))
      (rf/register-listener! :errors
        :test/sibling
        (fn [record] (swap! seen conj record)))
      (rf/reg-event :err/throw2
                       (fn [{:keys [db]} _]
                         {:db (throw (ex-info "handler kaboom" {}))}))
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
      (rf/register-listener! :errors
        :test/recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event :err/throw3 (fn [{:keys [db]} _] {:db (throw (ex-info "x" {}))}))
      (rf/dispatch-sync [:err/throw3])
      (is (= 1 (count @seen)) "listener fired before unregister")
      (rf/unregister-listener! :errors :test/recorder)
      (rf/dispatch-sync [:err/throw3])
      (is (= 1 (count @seen)) "listener silent after unregister")
      ;; Re-register under the same id and dispatch again.
      (rf/register-listener! :errors
        :test/recorder
        (fn [record] (swap! seen conj record)))
      (rf/dispatch-sync [:err/throw3])
      (is (= 2 (count @seen))
          "listener fired again after re-registration under the same id"))))

(deftest listener-elapsed-ms-is-integer
  (testing "Per rf2-bacs4 §Record shape + rf2-ph8pa contract:
            `:elapsed-ms` MUST be an integer on every platform. CLJS
            `performance.now()` returns a float; the substrate
            rounds at the boundary so the contract holds."
    (let [seen (atom nil)]
      (rf/register-listener! :errors
        :test/recorder
        (fn [record] (reset! seen record)))
      (rf/reg-event :err/elapsed (fn [{:keys [db]} _] {:db (throw (ex-info "x" {}))}))
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
;; wire-walker (per-frame `[:rf.runtime/elision]` runtime-db registry, populated from app-schema
;; `:sensitive?` slot meta). Path-marked classification supersedes the
;; previous handler-level enforcement.
;; ============================================================================

(deftest non-sensitive-handler-error-payload-flows-through
  (testing "Without handler-meta `:sensitive?`, error records surface
            the elided event vector (subject to the per-path wire
            walker only) so off-box error observability sees the
            event taxonomy."
    (let [seen (atom nil)]
      (rf/register-listener! :errors
        :test/recorder
        (fn [record] (reset! seen record)))
      (rf/reg-event :err/normal-throw
                       (fn [{:keys [db]} _] {:db (throw (ex-info "boom" {}))}))
      (rf/dispatch-sync [:err/normal-throw {:k "v"}])
      (let [r @seen]
        (is (some? r))
        (is (vector? (:event r))
            "event vector flows through (subject only to per-path wire elision)")
        (is (= :err/normal-throw (first (:event r))))))))

;; ============================================================================
;; rf2-2hvga (= B / widen) — the corpus-wide error-emit listener (#4) is BROAD
;; ----------------------------------------------------------------------------
;; Every catalogued production-reachable RUNTIME `:rf.error/*` fans out through
;; the always-on listener — NOT just handler-exception. These DEV-mode tests
;; pin that the previously dev-trace-ONLY categories now ALSO reach the listener
;; (frame-destroyed via dispatch / dispatch-sync / subscribe; no-such-handler;
;; no-such-sub; compute-sub sub-exception). The CLJS production-mode counterpart
;; (under `:advanced` + `goog.DEBUG=false`) lives in
;; `re-frame.on-error-elision-prod-test` — production-survival is the crux.
;;
;; Recovery is framework-owned (the per-category typed defaults); the per-frame
;; `:on-error` recovery policy was REMOVED (rf2-hiqtk8, collapsing the rf2-2hvga
;; axis-2 / recovery-policy-eligible column). The listener is the single
;; always-on observability surface.
;; ============================================================================

(deftest listener-fires-on-frame-destroyed-dispatch
  (testing "Per rf2-2hvga (= B / recover-but-emit): `dispatch` to a
            destroyed / unknown frame RECOVERS (no-op) AND fans
            `:rf.error/frame-destroyed` out through the always-on
            corpus-wide listener (axis 1)."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      ;; dispatch into a frame that was never registered — recovers, emits.
      (is (nil? (rf/dispatch [:whatever] {:frame :gone/frame}))
          "dispatch into an unknown frame returns nil (recovers, no throw)")
      (is (= 1 (count @seen)) "listener fired exactly once")
      (let [r (first @seen)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (= :gone/frame (:frame r)) ":frame names the target frame")
        ;; EP-0015 issue 1 (rf2-t55hxg.18) — the `:event` slot is projected
        ;; through `elide-wire-value` against the record's frame. Here the
        ;; frame is UNRESOLVABLE (never registered), so there is no
        ;; classification policy to consult and the slot FAILS CLOSED: the
        ;; attempted vector is redacted whole rather than shipped verbatim
        ;; under no policy. The structural `:event-id` keyword still survives
        ;; for observability — it is not a user-value tree slot.
        (is (= :rf/redacted (:event r))
            ":event fails closed under an unresolvable frame (no empty-policy leak)")
        (is (= :whatever (:event-id r)))))))

(deftest listener-fires-on-frame-destroyed-dispatch-sync
  (testing "Per rf2-2hvga: `dispatch-sync` to a destroyed / unknown
            frame RECOVERS (no-op) AND fans `:rf.error/frame-destroyed`
            through the always-on listener."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (is (nil? (rf/dispatch-sync [:whatever] {:frame :gone/frame}))
          "dispatch-sync into an unknown frame returns nil (recovers)")
      (is (= 1 (count @seen)))
      (is (= :rf.error/frame-destroyed (:error (first @seen))))
      (is (= :gone/frame (:frame (first @seen)))))))

(deftest listener-fires-on-frame-destroyed-subscribe
  (testing "Per rf2-2hvga: `subscribe` to a destroyed / unknown frame
            RECOVERS (returns nil) AND fans `:rf.error/frame-destroyed`
            through the always-on listener. The teardown-race shape —
            subscribe arriving against a vanished frame — recovers
            safely while staying observable."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (is (nil? (rf/subscribe-once [:any-sub] {:frame :gone/frame}))
          "subscribe-once against an unknown frame returns nil (recovers)")
      (is (= 1 (count @seen)))
      (let [r (first @seen)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (= :gone/frame (:frame r)))
        ;; EP-0015 issue 1 (rf2-t55hxg.18) — `:event` (here the attempted
        ;; query-v) is projected against the record's frame. The frame is
        ;; UNRESOLVABLE, so the slot fails closed to `:rf/redacted` rather
        ;; than leaking the attempted query-v under no policy.
        (is (= :rf/redacted (:event r))
            ":event fails closed under an unresolvable frame (no empty-policy leak)")))))

(deftest listener-fires-on-frame-destroyed-after-destroy-frame
  (testing "Per rf2-2hvga + Spec 002 §Destroy: after `destroy-frame!`,
            a subsequent dispatch / subscribe RECOVERS and emits
            `:rf.error/frame-destroyed` through the always-on listener —
            the genuine use-after-destroy case (not just unknown-id)."
    (rf/reg-frame :doomed/frame {:doc "to be destroyed"})
    (rf/destroy-frame! :doomed/frame)
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (is (nil? (rf/dispatch [:x] {:frame :doomed/frame})))
      (is (nil? (rf/subscribe-once [:y] {:frame :doomed/frame})))
      (is (= 2 (count @seen)) "both dispatch and subscribe emitted")
      (is (every? #(= :rf.error/frame-destroyed (:error %)) @seen))
      (is (every? #(= :doomed/frame (:frame %)) @seen)))))

(deftest listener-fires-on-no-such-handler
  (testing "Per rf2-2hvga (= B / widen): a dispatch to a never-registered
            handler fans `:rf.error/no-such-handler` through the
            always-on listener — a production-meaningful runtime error
            that was previously dev-trace-only."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      ;; :rf/default exists (fixture) but :no/handler-here is unregistered.
      (rf/dispatch-sync [:no/handler-here])
      (let [r (some (fn [x] (when (= :rf.error/no-such-handler (:error x)) x)) @seen)]
        (is (some? r) "listener received :rf.error/no-such-handler")
        (is (= :no/handler-here (:event-id r)))
        (is (= [:no/handler-here] (:event r)))
        (is (= :rf/default (:frame r)))))))

(deftest listener-fires-on-no-such-sub
  (testing "Per rf2-2hvga (= B / widen): a subscribe to a never-registered
            sub fans `:rf.error/no-such-sub` through the always-on
            listener (previously dev-trace-only)."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (is (nil? (rf/subscribe-once [:no/such-sub-here] {:frame :rf/default}))
          "subscribe-once to an unregistered sub recovers to nil")
      (let [r (some (fn [x] (when (= :rf.error/no-such-sub (:error x)) x)) @seen)]
        (is (some? r) "listener received :rf.error/no-such-sub")
        (is (= :no/such-sub-here (:event-id r)))
        (is (= [:no/such-sub-here] (:event r)))
        (is (= :rf/default (:frame r)))))))

(deftest listener-fires-on-compute-sub-exception
  (testing "Per rf2-2hvga (= B / widen) — SETTLES rf2-kjf3m.3: a sub that
            throws while resolving via the PURE `compute-sub` path fans
            `:rf.error/sub-exception` through the always-on listener.
            Previously trace-only — under production hardening it would
            recover to nil with no always-on emission (the fail-open
            class rf2-vvwmi closed for the reactive path)."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-sub :kjf3m/throwing (fn [_db _q] (throw (ex-info "compute-boom" {}))))
      (is (nil? (rf/compute-sub [:kjf3m/throwing] {}))
          "compute-sub recovers to nil on a body throw")
      (let [r (some (fn [x] (when (= :rf.error/sub-exception (:error x)) x)) @seen)]
        (is (some? r) "listener received :rf.error/sub-exception from compute-sub")
        (is (some? (:exception r)) ":exception present on the record")))))

;; ============================================================================
;; rf2-goum9x — the fx / cofx production error categories fan out through the
;; ----------------------------------------------------------------------------
;; always-on error-emit listener, not the dev trace alone. Before this fix
;; these three categories went ONLY through `trace/emit-error!` (DCE'd under
;; CLJS `:advanced` + `goog.DEBUG=false`), so a thrown registered fx, an
;; unknown fx-id, an override misconfiguration, or an unknown cofx-id vanished
;; from production observability — even though Spec 009 §Error event catalogue
;; lists them as production-reachable runtime errors and Spec 011 maps
;; `:rf.error/fx-handler-exception` to a 500 off the always-on substrate.
;;
;;   - :rf.error/fx-handler-exception — recovers (the fx is skipped,
;;       siblings still fire, app-db is NOT rolled back).
;;   - :rf.error/no-such-fx / :rf.error/override-fallthrough /
;;     :rf.error/no-such-cofx — invalid operations; the listener fires and
;;       the framework applies its built-in recovery.
;; ============================================================================

(deftest listener-fires-on-fx-handler-exception
  (testing "Per rf2-goum9x: a registered fx that throws fans
            `:rf.error/fx-handler-exception` through the always-on
            listener (previously dev-trace-only). The throw is recovered
            (the fx is skipped) and the cascade continues."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-fx :goum9x/throwing-fx
                 (fn [_ _] (throw (ex-info "fx-boom" {:cause :test}))))
      (rf/reg-event :goum9x/run-throwing-fx
                       (fn [_ _] {:fx [[:goum9x/throwing-fx {:to "alice"}]]}))
      (rf/dispatch-sync [:goum9x/run-throwing-fx])
      (let [r (some (fn [x] (when (= :rf.error/fx-handler-exception (:error x)) x)) @seen)]
        (is (some? r) "listener received :rf.error/fx-handler-exception")
        (is (= [:goum9x/run-throwing-fx] (:event r))
            "the originating event vector rides :event")
        (is (= :goum9x/run-throwing-fx (:event-id r))
            "the originating event-id rides :event-id")
        (is (= :rf/default (:frame r)))
        (is (some? (:exception r)) ":exception present on the record")))))

(deftest fx-handler-exception-recovery-is-isolated-siblings-fire
  (testing "Per rf2-goum9x acceptance: the fx-handler-exception fan-out
            preserves fx-walk semantics — the throwing fx is skipped, the
            SIBLING fx in the same :fx vector still fire, and app-db is
            NOT rolled back."
    (let [fired (atom [])]
      (rf/register-listener! :errors :test/recorder (fn [_] nil))
      (rf/reg-fx :goum9x/ok-a   (fn [_ _] (swap! fired conj :a)))
      (rf/reg-fx :goum9x/boom   (fn [_ _] (throw (ex-info "boom" {}))))
      (rf/reg-fx :goum9x/ok-b   (fn [_ _] (swap! fired conj :b)))
      (rf/reg-event :goum9x/mixed
                       (fn [{:keys [db]} _]
                         {:db (assoc db :committed? true)
                          :fx [[:goum9x/ok-a]
                               [:goum9x/boom]
                               [:goum9x/ok-b]]}))
      (rf/dispatch-sync [:goum9x/mixed])
      (is (= [:a :b] @fired)
          "siblings on either side of the throwing fx still fired")
      (is (= true (:committed? (rf/app-db-value :rf/default)))
          ":db committed before the :fx walk — the fx throw does NOT roll it back"))))

(deftest listener-fires-on-no-such-fx
  (testing "Per rf2-goum9x: an unknown fx-id fans `:rf.error/no-such-fx`
            through the always-on listener (previously dev-trace-only).
            The fx is dropped; the cascade continues."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-event :goum9x/run-unknown-fx
                       (fn [_ _] {:fx [[:goum9x/never-registered {:x 1}]]}))
      (rf/dispatch-sync [:goum9x/run-unknown-fx])
      (let [r (some (fn [x] (when (= :rf.error/no-such-fx (:error x)) x)) @seen)]
        (is (some? r) "listener received :rf.error/no-such-fx")
        (is (= [:goum9x/run-unknown-fx] (:event r)))
        (is (= :goum9x/run-unknown-fx (:event-id r)))
        (is (= :rf/default (:frame r)))))))

(deftest listener-fires-on-override-fallthrough
  (testing "Per rf2-goum9x: an `:fx-overrides` entry redirecting to an
            unregistered fx-id fans `:rf.error/override-fallthrough`
            through the always-on listener (previously dev-trace-only).
            The runtime falls back to the registered fx."
    (let [seen  (atom [])
          fired (atom false)]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-fx :goum9x/real-fx (fn [_ _] (reset! fired true)))
      (rf/reg-event :goum9x/run-bad-override
                       (fn [_ _] {:fx [[:goum9x/real-fx]]}))
      ;; Per-call override redirects :goum9x/real-fx to an id that is NOT
      ;; registered → override-fallthrough; runtime uses :goum9x/real-fx.
      (let [f (frame/make-anon-frame-record! {})]
        (rf/dispatch-sync [:goum9x/run-bad-override]
                          {:frame f
                           :fx-overrides {:goum9x/real-fx :goum9x/not-registered}})
        (let [r (some (fn [x] (when (= :rf.error/override-fallthrough (:error x)) x)) @seen)]
          (is (some? r) "listener received :rf.error/override-fallthrough")
          (is (= f (:frame r)) "frame-attributed to the dispatching frame"))
        (is (true? @fired)
            "fell back to the registered fx (:replaced-with-default recovery)")))))

(deftest listener-fires-on-unregistered-cofx
  (testing "EP-0017 (succeeds rf2-goum9x): a `:rf.cofx/requires` declaration
            referencing an UNREGISTERED cofx-id (the typo case) fans
            `:rf.error/unregistered-cofx` through the always-on listener. Per
            EP-0017 §7 the dispatch is rejected (no-recovery) — typos die loudly
            rather than silently re-reading the host."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-event :goum9x/run-unknown-cofx
                       {:rf.cofx/requires [:goum9x/never-registered-cofx]}
                       (fn [_ _] {}))
      (try (rf/dispatch-sync [:goum9x/run-unknown-cofx])
           (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
      (let [r (some (fn [x] (when (= :rf.error/unregistered-cofx (:error x)) x)) @seen)]
        (is (some? r) "listener received :rf.error/unregistered-cofx")
        (is (= :rf/default (:frame r)))))))

;; ============================================================================
;; rf2-n4x74b — component-attributed off-box record carries :failing-id.
;; ----------------------------------------------------------------------------
;; For the categories whose failing component is DISTINCT from the dispatched
;; event — a user interceptor (`:rf.error/interceptor-exception`) or a coeffect
;; supplier (`:rf.error/coeffect-exception`) — the always-on record's
;; `:event-id` slot carries the EVENT id, so the classified failing component id
;; (interceptor / cofx id) + `:reason` used to ride ONLY the dev-trace tags
;; (DCE'd under `goog.DEBUG=false`). An off-box shipper saw the category but not
;; WHICH component failed. The fix lifts `:failing-id` + `:reason` onto the
;; always-on record for these categories (and ONLY these — handler-exception and
;; the sub-* categories, whose failing id already EQUALS `:event-id`, are
;; unchanged, keeping the tight record shape).
;; ============================================================================

(deftest interceptor-exception-record-carries-failing-interceptor-id
  (testing "Per rf2-n4x74b: a user interceptor whose :after throws fans
            `:rf.error/interceptor-exception` through the always-on listener
            with the EVENT id in `:event-id` AND the failing INTERCEPTOR id in
            `:failing-id` (distinct from the event) — so an off-box shipper
            can tell WHICH interceptor failed in production, not just the
            category. `:reason` rides too."
    (let [seen (atom [])
          boom-after (rf/->interceptor
                       :id :n4x74b/boom-after
                       :after (fn [_ctx] (throw (ex-info "after boom" {}))))]
      (rf/register-listener! :errors :test/recorder
                             (fn [record] (swap! seen conj record)))
      (rf/reg-interceptor :n4x74b/boom-after boom-after)
      (rf/reg-event :n4x74b/with-throwing-interceptor
                    {:interceptors [:n4x74b/boom-after]}
                    (fn [{:keys [db]} _] {:db (assoc db :x 1)}))
      (rf/dispatch-sync [:n4x74b/with-throwing-interceptor])
      (let [r (some (fn [x] (when (= :rf.error/interceptor-exception (:error x)) x))
                    @seen)]
        (is (some? r) "listener received :rf.error/interceptor-exception")
        (is (= :n4x74b/with-throwing-interceptor (:event-id r))
            ":event-id carries the EVENT id (unchanged)")
        (is (= :n4x74b/boom-after (:failing-id r))
            ":failing-id carries the failing INTERCEPTOR id (distinct from the
             event) — the off-box record now attributes the component (rf2-n4x74b)")
        (is (string? (:reason r))
            ":reason rides the always-on record too")
        (is (some? (:exception r)) ":exception present")))))

(deftest interceptor-before-exception-record-carries-failing-interceptor-id
  (testing "Per rf2-n4x74b: the :before-phase variant — an interceptor whose
            :before throws is attributed to the interceptor id on the always-on
            record too (the same classification applies to both phases)."
    (let [seen (atom [])
          boom-before (rf/->interceptor
                        :id :n4x74b/boom-before
                        :before (fn [_ctx] (throw (ex-info "before boom" {}))))]
      (rf/register-listener! :errors :test/recorder
                             (fn [record] (swap! seen conj record)))
      (rf/reg-interceptor :n4x74b/boom-before boom-before)
      (rf/reg-event :n4x74b/before-throws
                    {:interceptors [:n4x74b/boom-before]}
                    (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:n4x74b/before-throws])
      (let [r (some (fn [x] (when (= :rf.error/interceptor-exception (:error x)) x))
                    @seen)]
        (is (some? r) "listener received :rf.error/interceptor-exception")
        (is (= :n4x74b/before-throws (:event-id r)) ":event-id carries the event id")
        (is (= :n4x74b/boom-before (:failing-id r))
            ":failing-id carries the failing interceptor id on the :before path too")))))

(deftest coeffect-exception-record-carries-failing-cofx-id
  (testing "Per rf2-n4x74b: a coeffect supplier that throws during context
            assembly fans `:rf.error/coeffect-exception` through the always-on
            listener. The always-on record carries the failing COFX id in
            `:failing-id` (the supplier id, distinct from the dispatched event)
            so off-box shippers attribute the failing supplier — not just the
            category. `:reason` rides too."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                             (fn [record] (swap! seen conj record)))
      (rf/reg-cofx :n4x74b/boom-cofx
        (fn [] (throw (ex-info "cofx supplier boom" {}))))
      (rf/reg-event :n4x74b/needs-boom-cofx
        {:rf.cofx/requires [:n4x74b/boom-cofx]}
        (fn [_ _] {}))
      (try (rf/dispatch-sync [:n4x74b/needs-boom-cofx])
           (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
      (let [r (some (fn [x] (when (= :rf.error/coeffect-exception (:error x)) x))
                    @seen)]
        (is (some? r) "listener received :rf.error/coeffect-exception")
        (is (= :n4x74b/boom-cofx (:failing-id r))
            ":failing-id carries the failing COFX supplier id (distinct from the
             event-id slot) — the off-box record attributes the component (rf2-n4x74b)")
        (is (string? (:reason r))
            ":reason rides the always-on record too")))))

(deftest handler-exception-record-omits-redundant-failing-id
  (testing "Per rf2-n4x74b: for handler-exception the failing id EQUALS the
            event id, so NO redundant `:failing-id` is stamped — the tight
            record shape is unchanged (the lift is guarded on failing-id being
            DISTINCT from event-id)."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                             (fn [record] (swap! seen conj record)))
      (rf/reg-event :n4x74b/handler-throws
                    (fn [{:keys [db]} _] {:db (throw (ex-info "handler boom" {}))}))
      (rf/dispatch-sync [:n4x74b/handler-throws])
      (let [r (some (fn [x] (when (= :rf.error/handler-exception (:error x)) x))
                    @seen)]
        (is (some? r) "listener received :rf.error/handler-exception")
        (is (not (contains? r :failing-id))
            "handler-exception record carries NO :failing-id (it would be
             redundant with :event-id — the tight shape is preserved)")
        (is (= :n4x74b/handler-throws (:event-id r))
            ":event-id carries the event id (the failing handler IS the event)")))))

;; ============================================================================
;; rf2-bxud9v — sub error records carry the failing SUB's source-coord.
;; ----------------------------------------------------------------------------
;; The always-on `error-coords-by-id` registry is keyed by `[registry-kind
;; id]`. A `reg-sub` stores coords under `[:sub sub-id]`, but
;; `dispatch-on-error!` previously looked source coords up under the
;; hardcoded `[:event event-id]` — so the production error records for the
;; sub categories (whose `:event-id` slot carries a SUB id) OMITTED the
;; failing sub's `:source-coord`. The fix is a kind-aware lookup
;; (`error-emit/error-source-coord`) that resolves the sub categories under
;; `[:sub …]`. These tests pin that the records now carry the right coord.
;; ============================================================================

(deftest sub-input-fn-error-record-carries-sub-source-coord
  (testing "Per rf2-bxud9v: a parametric `input-fn` throw fans
            `:rf.error/sub-input-fn-exception` through the always-on
            listener with `query-id` in `:event-id`. The record's
            `:source-coord` must resolve under `[:sub …]` (kind-aware
            lookup) — the sub was registered via the public `reg-sub`
            macro path, so its coords ride the always-on registry."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      ;; Parametric sub: input-fn (arity-1) THROWS at materialization.
      ;; Registered via the public macro so `[:sub …]` coords are captured.
      (rf/reg-sub :bxud9v/input-throws
                  (fn [_q] (throw (ex-info "input-fn-boom" {})))
                  (fn [_in _q] :unreachable))
      ;; Reactive subscribe path → `compute-and-cache!` runs the input-fn,
      ;; catches the throw, emits :rf.error/sub-input-fn-exception, recovers nil.
      (is (nil? (rf/subscribe-once [:bxud9v/input-throws] {:frame :rf/default}))
          "subscribe recovers to nil on an input-fn throw")
      (let [r (some (fn [x] (when (= :rf.error/sub-input-fn-exception (:error x)) x)) @seen)]
        (is (some? r) "listener received :rf.error/sub-input-fn-exception")
        (is (= :bxud9v/input-throws (:event-id r))
            "the failing sub-id rides :event-id")
        (is (some? (:exception r)) ":exception present (input-fn threw)")
        ;; The kind-aware lookup resolves the coord under [:sub sub-id].
        (let [sc (:source-coord r)]
          (is (some? sc)
              "record carries :source-coord resolved under [:sub …] (rf2-bxud9v)")
          (is (symbol?  (:ns   sc)) ":source-coord :ns is the defining ns symbol")
          (is (integer? (:line sc)) ":source-coord :line is the reg-sub line")
          (is (string?  (:file sc)) ":source-coord :file is the defining file"))))))

(deftest sub-exception-record-carries-sub-source-coord
  (testing "Per rf2-bxud9v: a reactive sub whose body throws fans
            `:rf.error/sub-exception` through the always-on listener;
            its `:source-coord` resolves under `[:sub …]` now that the
            reactive call site supplies `query-id` in `:event-id` and the
            lookup is kind-aware."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      ;; Layer-1 sub whose body throws on the reactive run.
      (rf/reg-sub :bxud9v/body-throws
                  (fn [_db _q] (throw (ex-info "body-boom" {}))))
      (is (nil? (rf/subscribe-once [:bxud9v/body-throws] {:frame :rf/default}))
          "reactive subscribe recovers to nil on a body throw")
      (let [r (some (fn [x] (when (= :rf.error/sub-exception (:error x)) x)) @seen)]
        (is (some? r) "listener received :rf.error/sub-exception")
        (is (= :bxud9v/body-throws (:event-id r))
            "the failing sub-id rides :event-id (rf2-bxud9v)")
        (is (some? (:exception r)) ":exception present on the record")
        (let [sc (:source-coord r)]
          (is (some? sc)
              "record carries :source-coord resolved under [:sub …] (rf2-bxud9v)")
          (is (symbol?  (:ns   sc)))
          (is (integer? (:line sc)))
          (is (string?  (:file sc))))))))

(deftest non-recovery-categories-fan-out-to-listener
  (testing "Per rf2-2hvga (= B / widen): every catalogued production-
            reachable runtime `:rf.error/*` — frame-destroyed,
            no-such-handler, no-such-sub, sub-exception — fans out
            through the always-on listener (the single observability
            surface). Recovery is the framework's per-category typed
            default; there is no app-steering policy (rf2-hiqtk8)."
    (let [listener-saw (atom #{})]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! listener-saw conj (:error record))))
      ;; no-such-handler on :rf/default.
      (rf/dispatch-sync [:no/handler-here2])
      ;; no-such-sub on :rf/default.
      (rf/subscribe-once [:no/such-sub-here2] {:frame :rf/default})
      ;; frame-destroyed via dispatch into an unknown frame.
      (rf/dispatch [:x] {:frame :gone/frame2})
      ;; compute-sub sub-exception.
      (rf/reg-sub :nrp/throwing (fn [_db _q] (throw (ex-info "boom" {}))))
      (rf/compute-sub [:nrp/throwing] {})
      (is (contains? @listener-saw :rf.error/no-such-handler))
      (is (contains? @listener-saw :rf.error/no-such-sub))
      (is (contains? @listener-saw :rf.error/frame-destroyed))
      (is (contains? @listener-saw :rf.error/sub-exception)
          "the always-on listener received every category"))))

;; ============================================================================
;; rf2-u0zz5 — atomicity contract: any pre-install throw aborts the event
;; ----------------------------------------------------------------------------
;; The `:db` install is the single, deferred, all-or-nothing commit
;; boundary. ANY throw before it — handler, interceptor `:after`, or the
;; flow transform — aborts the event: NO install, app-db UNCHANGED, NO
;; `:rf.event/db-changed`, NO `:fx`. (The flow-transform variant is pinned
;; in re-frame.flows-trace-test; here we pin the handler and
;; interceptor-`:after` variants — proving the abort is UNIFORM across all
;; pre-install throw sources.)
;; ============================================================================

(deftest handler-throw-leaves-app-db-unchanged-no-db-changed
  (testing "A handler throw aborts the event before the install: app-db
            is UNCHANGED and NO :rf.event/db-changed is emitted."
    (let [traces (atom [])]
      ;; Seed a known app-db value via a clean dispatch first.
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:seeded 1}}))
      (rf/dispatch-sync [:seed])
      (is (= {:seeded 1} (rf/app-db-value :rf/default)) "seeded")
      ;; Now register a handler that mutates :db and then throws. Even
      ;; though it produced a :db value before throwing, nothing installs.
      (rf/reg-event :throws
                       (fn [{:keys [db]} _]
                         ;; Build a would-be new db, then throw — the
                         ;; handler returns nothing; its effect never
                         ;; reaches the install.
                         {:db (let [_ (assoc db :would-be 2)]
                           (throw (ex-info "boom" {})))}))
      (rf/register-listener! :trace ::rec (fn [ev] (swap! traces conj ev)))
      (try
        (rf/dispatch-sync [:throws])
        (finally (rf/unregister-listener! :trace ::rec)))
      (is (= {:seeded 1} (rf/app-db-value :rf/default))
          "app-db is UNCHANGED — the handler threw before the install")
      (is (not-any? #(= :rf.event/db-changed (:operation %)) @traces)
          "NO :rf.event/db-changed on a handler throw — nothing was installed"))))

(deftest interceptor-after-throw-leaves-app-db-unchanged-no-db-changed
  (testing "An interceptor :after throw aborts the event before the
            install — even though the handler produced a :db effect: app-db
            is UNCHANGED and NO :rf.event/db-changed is emitted."
    (let [traces (atom [])
          ;; A user interceptor whose :after throws AFTER the handler has
          ;; produced its :db effect.
          boom-after (rf/->interceptor
                       :id :test/boom-after
                       :after (fn [_ctx] (throw (ex-info "after boom" {}))))]
      ;; EP-0022 reference-only flip: register the interceptor VALUE under its
      ;; own id and reference it from the chain.
      (rf/reg-interceptor :test/boom-after boom-after)
      (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:seeded 1}}))
      (rf/dispatch-sync [:seed])
      (is (= {:seeded 1} (rf/app-db-value :rf/default)) "seeded")
      ;; This handler successfully writes :db; the interceptor's :after
      ;; then throws — so a :db effect IS present when the throw fires,
      ;; yet nothing installs (the error path returns before the commit).
      (rf/reg-event :writes-then-after-throws
                       {:interceptors [:test/boom-after]}
                       (fn [{:keys [db]} _] {:db (assoc db :written 99)}))
      (rf/register-listener! :trace ::rec (fn [ev] (swap! traces conj ev)))
      (try
        (rf/dispatch-sync [:writes-then-after-throws])
        (finally (rf/unregister-listener! :trace ::rec)))
      (is (= {:seeded 1} (rf/app-db-value :rf/default))
          "app-db is UNCHANGED — the interceptor :after threw before the install
           even though the handler's :db effect was present")
      (is (not-any? #(= :rf.event/db-changed (:operation %)) @traces)
          "NO :rf.event/db-changed on an interceptor :after throw"))))
