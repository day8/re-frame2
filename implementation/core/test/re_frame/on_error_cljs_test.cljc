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

  Runs under `:node-test` / `:browser-test` / `clojure -M:test`. The CLJS
  production-mode counterpart lives in `re-frame.on-error-elision-prod-test`
  — that suite pins the same contract under `:advanced` + `goog.DEBUG=false`.

  ## Posture (rf2-mlh1h)

  POSTURE-INDEPENDENT throughout, and that is the point of the namespace.
  Every assertion here captures through the ALWAYS-ON `:errors` stream, never
  the dev-only `:trace` stream, so this namespace is deliberately NOT on
  `scripts/test-core-prod-gate.sh`'s exclusion roster and runs under
  `-Dre-frame.debug=false` as well as in the ordinary suite. It is therefore
  the production witness for the rf2-mszrz / rf2-n4x74b component-attribution
  contract (`:failing-id` + `:reason` lifted onto the record for the
  interceptor and coeffect categories, and NOT stamped for handler-exception,
  whose failing id already equals `:event-id`). Keep it that way: an
  assertion here that only holds in dev belongs in the dev-posture twin
  (`re-frame.interceptor-test`), not in this file."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.error-emit :as error-emit]
            [re-frame.source-coords :as source-coords]
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

(deftest captured-refusal-escapes-no-caller-and-reaches-no-host-channel
  ;; rf2-fu75 — the witness for Spec 009 §What IS available in production
  ;; channel #5. That channel used to say that "a re-frame2 event handler that
  ;; throws in production still surfaces" at `window.onerror`. It does not, and
  ;; the code says so in terms: `re-frame.interceptor/invoke-before` and
  ;; `invoke-after` CAPTURE the throw into `:rf/interceptor-error` rather than
  ;; re-throwing it, and `re-frame.router/emit-pipeline-exception!`'s docstring
  ;; states the reason — "the drain must not abort". Nothing propagates out of
  ;; `dispatch-sync`, so the host's uncaught machinery never sees the failure
  ;; and this `:errors` stream is the ONLY channel it reaches.
  ;;
  ;; The stale claim cost a finder four browser runs (rf2-06lp): they hunted a
  ;; `:rf.mutation/execute` guard that was never missing, because the refusal it
  ;; DID raise reached no channel they were watching. This suite is where the
  ;; corrected sentence becomes checkable — the complaint-catalogue conformance
  ;; gates bind register↔spec by ERROR ID, so a prose correction reds nothing
  ;; on its own.
  ;;
  ;; TWO-SIDED ON PURPOSE, and neither half alone is the contract. Assert only
  ;; "nothing escaped" and a runtime that swallowed the failure entirely passes.
  ;; Assert only "a record arrived" and a runtime that ALSO re-threw passes —
  ;; which is precisely the shape the old spec sentence described. Both must
  ;; hold at once, per case.
  ;;
  ;; THREE refusal classes, because the capture is not handler-specific: a
  ;; throwing handler, the rf2-04tx effect-map-envelope refusal, and a
  ;; completely unregistered event id. Deliberately NOT asserting that nothing
  ;; is PRINTED: whether a dev build should ship a default console sink is the
  ;; open half of rf2-fu75, and a test pinning the absence of one would
  ;; pre-decide a question left to the operator.
  (let [seen     (atom [])
        settled! (fn [event]
                   ;; ::returned iff the dispatch returned normally; the
                   ;; throwable itself otherwise, so a failure names it.
                   (try (rf/dispatch-sync event) ::returned
                        (catch #?(:clj Throwable :cljs :default) e e)))]
    (rf/register-listener! :errors :test/recorder
      (fn [record] (swap! seen conj record)))

    (testing "a throwing event handler — captured, not re-thrown"
      (rf/reg-event :fu75/throws (fn [_ _] (throw (ex-info "kaboom" {}))))
      (is (= ::returned (settled! [:fu75/throws]))
          "the chain exception did NOT propagate out of dispatch-sync")
      (is (= [:rf.error/handler-exception] (mapv :error @seen))
          "and the :errors stream carried it — the failure was not swallowed"))

    (testing "the rf2-04tx effect-map envelope refusal — same silence"
      (reset! seen [])
      (rf/reg-event :fu75/foreign-fx (fn [_ _] {:db {} :fu75/not-an-fx-key 1}))
      (is (= ::returned (settled! [:fu75/foreign-fx]))
          "the refusal did NOT propagate out of dispatch-sync")
      (is (= [:rf.error/effect-map-shape] (mapv :error @seen))
          "and the :errors stream carried it"))

    (testing "an unregistered event id — the typo case, equally silent"
      (reset! seen [])
      (is (= ::returned (settled! [:fu75/no-such-handler-at-all]))
          "dispatching an unknown id did NOT propagate out of dispatch-sync")
      (is (= [:rf.error/no-such-handler] (mapv :error @seen))
          "and the :errors stream carried it"))))

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
        ;;
        ;; WHAT-STAYS counter-pin (rf2-alk8a): a DISPATCHED event vector is
        ;; PAYLOAD, not identity, so it stays `:rf/redacted` under the
        ;; unresolvable frame. alk8a's raw egress is SUBSCRIBE-realm-only (see
        ;; `listener-fires-on-frame-destroyed-subscribe`), NOT a blanket raw —
        ;; the `:op :subscribe` stamp is the discriminator, and no `:op` rides
        ;; this dispatch emit's op-nil path.
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
        ;; rf2-alk8a (Option A — RAW): the attempted query-v is IDENTITY
        ;; (rf2-zwgqe / Spec 015), so it egresses on `:event` VERBATIM even
        ;; under an unresolvable frame — the subscribe emitters stamp
        ;; `:op :subscribe`, routing `:event` raw through
        ;; `raw-identity-query-vector-event?`. rf2-t55hxg.18's fail-closed guards
        ;; policy-walked VALUE slots (dispatched event vectors — see
        ;; `listener-fires-on-frame-destroyed-dispatch`, still `:rf/redacted`),
        ;; NOT identity slots, which never consult frame policy.
        (is (= [:any-sub] (:event r))
            ":event egresses the query vector raw (identity)")
        (is (= :subscribe (:op r))
            ":op :subscribe stamped on the always-on subscribe-realm record")))))

(deftest listener-fires-on-frame-destroyed-after-destroy-frame
  (testing "Per rf2-2hvga + Spec 002 §Destroy: after `destroy-frame!`,
            a subsequent dispatch / subscribe RECOVERS and emits
            `:rf.error/frame-destroyed` through the always-on listener —
            the genuine use-after-destroy case (not just unknown-id)."
    (rf/make-frame {:id :doomed/frame :doc "to be destroyed"})
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
;;     :rf.error/unregistered-cofx — invalid operations; the listener fires and
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
            The fx is dropped; the cascade continues.
            Per rf2-g0mep the record also NAMES the unknown fx-id."
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
        (is (= :rf/default (:frame r)))
        ;; rf2-g0mep — `:event-id` is the DISPATCHING event, so the
        ;; unregistered fx-id is a DISTINCT failing component and Spec 009's
        ;; attribution rule applies: `fx.cljc` stamps `:failing-id` on the
        ;; trace-payload and `emit-error-both!` lifts it onto this record.
        ;; Without it an off-box shipper learns an fx was missing but not which.
        (is (= :goum9x/never-registered (:failing-id r))
            ":failing-id names the UNKNOWN fx-id")
        ;; The fx ARGS stay on the dev trace: an unregistered fx-id has no
        ;; registration to read a `:sensitive` declaration off, so they must
        ;; not reach this production-surviving record.
        (is (not (contains? r :rf.fx/args))
            "the unaddressable args do NOT ride the always-on record")))))

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

;; ----------------------------------------------------------------------------
;; rf2-mlh1h — the residue of the rf2-mszrz attribution contract the four
;; twins above do not reach.
;;
;; They pin `:failing-id` on the always-on record, which is the load-bearing
;; half and the half rf2-mlh1h was filed about. But rf2-mszrz's contract also
;; discriminates the two INTERCEPTOR PHASES, and `:phase` is deliberately NOT
;; lifted: `error-emit/emit-error-both!` lifts exactly `:failing-id` +
;; `:reason`, keeping the record tight. So in production the phase is
;; observable through the `:reason` STRING and nowhere else — and both
;; interceptor twins above assert only `(string? (:reason r))`. Drop the phase
;; from that string and an off-box shipper silently loses the ability to tell
;; a `:before` failure from an `:after` one, with nothing red in either
;; posture.
;;
;; The dev-posture assertions in `re-frame.interceptor-test`'s
;; `pipeline-exception-attributed-to-true-component` read `:phase` off the
;; trace tags directly. That arm is correct and stays; this is its
;; production-axis counterpart, in the rf2-7vk3z twin shape.
;; ----------------------------------------------------------------------------

(defn- record-for
  "Dispatch `event`, return the first ALWAYS-ON record whose `:error` is
  `category`. Captures through the `:errors` stream — never the dev-only
  `:trace` stream, which emits nothing under `-Dre-frame.debug=false`, so
  every assertion built on this helper means the same thing in both postures."
  [category event]
  (let [seen (atom [])]
    (rf/register-listener! :errors :test/recorder
                           (fn [record] (swap! seen conj record)))
    (try (rf/dispatch-sync event)
         (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
    (some (fn [x] (when (= category (:error x)) x)) @seen)))

(deftest interceptor-exception-record-discriminates-phase-in-production
  (testing "Per rf2-mlh1h: `:phase` is not lifted onto the tight always-on
            record, so an off-box shipper tells a `:before` failure from an
            `:after` one through `:reason` alone. Both phases pinned here, in
            every posture."
    (rf/reg-interceptor :mlh1h/boom-before
      (rf/->interceptor :id     :mlh1h/boom-before
                        :before (fn [_ctx] (throw (ex-info "before boom" {})))))
    (rf/reg-event :mlh1h/before-throws
                  {:interceptors [:mlh1h/boom-before]}
                  (fn [{:keys [db]} _] {:db db}))
    (rf/reg-interceptor :mlh1h/boom-after
      (rf/->interceptor :id    :mlh1h/boom-after
                        :after (fn [_ctx] (throw (ex-info "after boom" {})))))
    (rf/reg-event :mlh1h/after-throws
                  {:interceptors [:mlh1h/boom-after]}
                  (fn [{:keys [db]} _] {:db db}))
    (let [before (record-for :rf.error/interceptor-exception [:mlh1h/before-throws])
          after  (record-for :rf.error/interceptor-exception [:mlh1h/after-throws])]
      (is (some? before) "the always-on record fired for the :before throw")
      (is (some? after) "the always-on record fired for the :after throw")
      ;; NOT a vacuous negative: the record above is asserted present first,
      ;; and it is the always-on one, which fires in both postures.
      (is (nil? (:phase before))
          ":phase is absent from the record by design — it rides the dev trace
           tags only, and the tight record shape is the contract")
      (is (re-find #"`before` phase" (:reason before))
          ":reason names the :before phase — the ONLY production-visible
           discriminator between the two interceptor phases")
      (is (re-find #"`after` phase" (:reason after))
          ":reason names the :after phase")
      (is (re-find #":mlh1h/boom-before" (:reason before))
          ":reason names the failing interceptor, agreeing with :failing-id"))))

(deftest coeffect-exception-record-keeps-the-event-in-event-id
  (testing "Per rf2-mlh1h: the lift is GUARDED on `:failing-id` differing from
            `:event-id`, so the coeffect twin above only means what it claims
            if `:event-id` really carries the dispatched EVENT. Pin the other
            half of that pair — otherwise a supplier id in BOTH slots would
            satisfy the twin while telling the shipper nothing."
    (rf/reg-cofx :mlh1h/boom-cofx (fn [] (throw (ex-info "cofx boom" {}))))
    (rf/reg-event :mlh1h/needs-boom-cofx
                  {:rf.cofx/requires [:mlh1h/boom-cofx]}
                  (fn [_ _] {}))
    (let [r (record-for :rf.error/coeffect-exception [:mlh1h/needs-boom-cofx])]
      (is (some? r) "the always-on record fired")
      (is (= :mlh1h/needs-boom-cofx (:event-id r))
          ":event-id is the dispatched EVENT, not the failing supplier")
      (is (= :mlh1h/boom-cofx (:failing-id r))
          ":failing-id is the failing SUPPLIER")
      (is (not= (:event-id r) (:failing-id r))
          "the two are DISTINCT — exactly the condition the lift is guarded on"))))

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

;; ============================================================================
;; rf2-xgkgx — frame-destroyed source-coord is OPERATION-REALM AWARE.
;; ----------------------------------------------------------------------------
;; `:rf.error/frame-destroyed` is the one realm-AMBIGUOUS category: an event-id
;; and a sub-id may legitimately SHARE a keyword because they live in SEPARATE
;; registries (`[:event id]` vs `[:sub id]`). The pre-fix resolution probed
;; `[:sub]` then `[:event]`, so a `:dispatch` frame-destroyed whose id ALSO
;; named a subscription was attributed to the WRONG realm (the subscription's
;; coord). The UI frame-bundle stale-op seam (`re-frame.ui.frames`) stamps the
;; exact failing `:op` onto the always-on record; `error-emit/error-source-coord`
;; now pivots on it — `:dispatch` / `:dispatch-sync` → the EVENT coord,
;; `:subscribe` → the SUBSCRIPTION coord, `:capture` → NEITHER. The `:op` is a
;; PRIVATE steering input (read from the record's attribution, never promoted to
;; a public schema slot).
;;
;; These drive the exact seam the frame-bundle uses (`emit-error-both!` with the
;; `:op` in the trailing record-attrs) against `[:event id]` / `[:sub id]` coords
;; seeded directly into the always-on `error-coords-by-id` registry, and assert
;; the delivered record's resolved `:source-coord` names the correct realm. Both
;; collision directions plus the no-coordinate (programmatic) case are covered.
;; ============================================================================

(def ^:private xgkgx-event-coord
  {:ns 're-frame.on-error-cljs-test.collide-events :file "collide_events.cljc" :line 11})

(def ^:private xgkgx-sub-coord
  {:ns 're-frame.on-error-cljs-test.collide-subs :file "collide_subs.cljc" :line 22})

(defn- xgkgx-frame-destroyed-records
  "Fire a frame-destroyed record shaped EXACTLY like the UI frame-bundle's
  stale-op seam (`re-frame.ui.frames/emit-and-throw-frame-destroyed!` →
  `emit-error-both!` with `{:op op}` in the trailing record-attrs) and return
  the vector of always-on records the listener saw. Exactly ONE record per emit
  pins the one-record-per-runtime-error law across the arms."
  [op id]
  (let [seen (atom [])]
    (rf/register-listener! :errors :xgkgx/recorder
                           (fn [record] (swap! seen conj record)))
    (error-emit/emit-error-both!
      :rf.error/frame-destroyed
      (when id [id])   ;; attempted event/query vector (nil for :capture)
      id               ;; event-id / sub-id — the vector head (nil for :capture)
      :rf/default      ;; a live frame — no fail-closed :event noise
      nil              ;; no exception — an invalid op, not a caught throw
      0                ;; elapsed-ms — not a timed path
      0                ;; time
      {:frame :rf/default :op op :reason :frame-destroyed} ;; dev-trace tags (axis 2)
      {:op op})        ;; category-specific attribution for the always-on record (axis 1)
    @seen))

(deftest frame-destroyed-dispatch-resolves-the-event-coord-on-same-keyword-collision
  (testing "rf2-xgkgx — a `:dispatch` frame-destroyed record whose id is
            registered as BOTH an event AND a same-keyword subscription
            resolves the EVENT coord. The pre-fix `[:sub]`-first probe picked
            the subscription's coord (the WRONG realm)."
    (source-coords/forget-error-coords!)
    (source-coords/remember-error-coords! :event :xgkgx.collide/a xgkgx-event-coord)
    (source-coords/remember-error-coords! :sub   :xgkgx.collide/a xgkgx-sub-coord)
    (let [records (xgkgx-frame-destroyed-records :dispatch :xgkgx.collide/a)]
      (is (= 1 (count records)) "exactly one always-on record per frame-destroyed emit")
      (let [r (first records)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (= xgkgx-event-coord (:source-coord r))
            ":dispatch resolves the EVENT coord, not the same-keyword sub's")))))

(deftest frame-destroyed-dispatch-sync-shares-the-dispatch-event-realm
  (testing "rf2-xgkgx — `:dispatch-sync` shares the dispatch realm: it resolves
            the EVENT coord, never the same-keyword subscription's."
    (source-coords/forget-error-coords!)
    (source-coords/remember-error-coords! :event :xgkgx.collide/b xgkgx-event-coord)
    (source-coords/remember-error-coords! :sub   :xgkgx.collide/b xgkgx-sub-coord)
    (let [records (xgkgx-frame-destroyed-records :dispatch-sync :xgkgx.collide/b)]
      (is (= 1 (count records)))
      (is (= xgkgx-event-coord (:source-coord (first records)))
          ":dispatch-sync resolves the EVENT coord"))))

(deftest frame-destroyed-subscribe-resolves-the-sub-coord-on-same-keyword-collision
  (testing "rf2-xgkgx — the INVERSE collision direction: a `:subscribe`
            frame-destroyed record whose id is registered as BOTH resolves the
            SUBSCRIPTION coord, never the same-keyword event's."
    (source-coords/forget-error-coords!)
    (source-coords/remember-error-coords! :event :xgkgx.collide/c xgkgx-event-coord)
    (source-coords/remember-error-coords! :sub   :xgkgx.collide/c xgkgx-sub-coord)
    (let [records (xgkgx-frame-destroyed-records :subscribe :xgkgx.collide/c)]
      (is (= 1 (count records)))
      (is (= xgkgx-sub-coord (:source-coord (first records)))
          ":subscribe resolves the SUB coord, not the same-keyword event's"))))

(deftest frame-destroyed-capture-fabricates-no-coord
  (testing "rf2-xgkgx — the `:capture` arm (a `(frame)` read that resolved a
            dead incarnation BEFORE any op ran) fabricates NEITHER coord: no id
            rides the record, and no `:source-coord` slot appears even with
            same-keyword registrations present."
    (source-coords/forget-error-coords!)
    (source-coords/remember-error-coords! :event :xgkgx.collide/d xgkgx-event-coord)
    (source-coords/remember-error-coords! :sub   :xgkgx.collide/d xgkgx-sub-coord)
    (let [records (xgkgx-frame-destroyed-records :capture nil)]
      (is (= 1 (count records)))
      (let [r (first records)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (not (contains? r :source-coord))
            ":capture never fabricates a source-coord")))))

(deftest frame-destroyed-programmatic-registration-omits-the-coord
  (testing "rf2-xgkgx — a `:dispatch` frame-destroyed for an id with NO captured
            coords (a programmatic registration that bypassed the macro path)
            omits the `:source-coord` slot rather than niling it."
    (source-coords/forget-error-coords!)
    (let [records (xgkgx-frame-destroyed-records :dispatch :xgkgx.no-coord/e)]
      (is (= 1 (count records)))
      (let [r (first records)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (not (contains? r :source-coord))
            "no captured coords ⇒ the :source-coord slot is ABSENT, not nil")))))

;; ============================================================================
;; rf2-7xlvt — CORE capture-frame stale-op source-coord is OPERATION-REALM EXACT
;; ----------------------------------------------------------------------------
;; The synchronous capture pre-check seam — `router/emit-captured-frame-
;; superseded!`, reached from `core/capture-dispatch!` + `core/capture-
;; subscribe!` — recover-but-emits `:rf.error/frame-destroyed` when a
;; `capture-frame` op finds its pinned incarnation superseded. Before rf2-7xlvt
;; it DROPPED the already-known operation realm, so `error-emit/error-source-
;; coord` fell back to the legacy `[:sub]`-then-`[:event]` probe (the correct
;; fallback for the realm-ambiguous bare router / subs emitters, UNSOUND here
;; where the realm IS known). That misattributed a stale captured DISPATCH to a
;; same-keyword SUBSCRIPTION's coord, and a stale captured SUBSCRIBE to an
;; unrelated EVENT's coord (instead of OMITTING `:source-coord`). This seam
;; KNOWS the realm (`:dispatch` / `:dispatch-sync` / `:subscribe`); it now
;; carries it through the shared frame-destroyed emit boundary so the resolved
;; coord names the correct definition (dispatch → `[:event]`, subscribe →
;; `[:sub]`) — or is omitted when the realm's own coord is genuinely absent.
;;
;; These drive the REAL capture seam (a `capture-frame` api pinned to a live
;; frame, then destroyed + reseated as a same-id successor) against distinct
;; sentinel `[:event id]` / `[:sub id]` coords seeded into the always-on
;; `error-coords-by-id` registry — the same registry the runtime resolves —
;; and assert the delivered record's `:source-coord` names the correct realm.
;; ============================================================================

(def ^:private xlvt-event-coord
  {:ns 're-frame.on-error-cljs-test.xlvt-events :file "xlvt_events.cljc" :line 7})

(def ^:private xlvt-sub-coord
  {:ns 're-frame.on-error-cljs-test.xlvt-subs :file "xlvt_subs.cljc" :line 13})

(defn- capture-superseded-record
  "Pin a `capture-frame` api to a LIVE frame, destroy that frame and reseat a
  same-id successor B (superseding the pin exactly like the bead's repro), run
  `seed` to populate the always-on `error-coords-by-id` registry, then invoke
  the captured `op-key` op (`:dispatch` / `:dispatch-sync` / `:subscribe`) with
  the vector `[id]`. The synchronous `capture-target-superseded?` pre-check sees
  the pin gone, so the op recover-but-emits through `emit-captured-frame-
  superseded!` (never leaking into B). Returns the vector of always-on records
  the listener saw — exactly one per stale op."
  [op-key id seed]
  (let [fid  :xlvt/target]
    (rf/make-frame {:id fid :doc "capture target A"})
    (let [h (rf/capture-frame fid)]
      (rf/destroy-frame! fid)
      (rf/make-frame {:id fid :doc "same-id successor B"})
      (let [seen (atom [])]
        (source-coords/forget-error-coords!)
        (seed)
        (rf/register-listener! :errors :xlvt/recorder
                               (fn [record] (swap! seen conj record)))
        (case op-key
          :dispatch      ((:dispatch h) [id])
          :dispatch-sync ((:dispatch-sync h) [id])
          :subscribe     ((:subscribe h) [id]))
        @seen))))

(defn- seed-both [id]
  (fn []
    (source-coords/remember-error-coords! :event id xlvt-event-coord)
    (source-coords/remember-error-coords! :sub   id xlvt-sub-coord)))

(deftest stale-captured-dispatch-resolves-the-event-coord-not-the-collision-sub
  (testing "rf2-7xlvt — a stale captured `:dispatch` whose id is BOTH an event
            AND a same-keyword sub resolves the EVENT coord. Pre-fix the realm
            was dropped and the `[:sub]`-first fallback stole the sub's coord."
    (let [records (capture-superseded-record :dispatch :audit/collide
                                             (seed-both :audit/collide))]
      (is (= 1 (count records)) "exactly one always-on record per stale op")
      (let [r (first records)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (= xlvt-event-coord (:source-coord r))
            "stale captured dispatch resolves the EVENT coord, never the collision sub's")))))

(deftest stale-captured-dispatch-sync-shares-the-event-realm
  (testing "rf2-7xlvt — a stale captured `:dispatch-sync` shares the dispatch
            realm: the EVENT coord, never the same-keyword sub's."
    (let [records (capture-superseded-record :dispatch-sync :audit/collide
                                             (seed-both :audit/collide))]
      (is (= 1 (count records)))
      (is (= xlvt-event-coord (:source-coord (first records)))
          "stale captured dispatch-sync resolves the EVENT coord"))))

(deftest stale-captured-subscribe-resolves-the-sub-coord-not-the-collision-event
  (testing "rf2-7xlvt — the inverse collision direction: a stale captured
            `:subscribe` whose id is BOTH resolves the SUB coord, never the
            same-keyword event's."
    (let [records (capture-superseded-record :subscribe :audit/collide
                                             (seed-both :audit/collide))]
      (is (= 1 (count records)))
      (is (= xlvt-sub-coord (:source-coord (first records)))
          "stale captured subscribe resolves the SUB coord"))))

(deftest stale-captured-subscribe-omits-coord-when-only-event-registered
  (testing "rf2-7xlvt — a stale captured `:subscribe` for an id that is ONLY an
            EVENT (no sub coord) OMITS `:source-coord` rather than stealing the
            unrelated event's. Pre-fix the `[:sub]`-then-`[:event]` fallback
            returned the EVENT coord — the wrong realm."
    (let [records (capture-superseded-record
                    :subscribe :audit/collide
                    (fn [] (source-coords/remember-error-coords!
                             :event :audit/collide xlvt-event-coord)))]
      (is (= 1 (count records)))
      (let [r (first records)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (not (contains? r :source-coord))
            "no sub coord ⇒ :source-coord is ABSENT, never the unrelated event's")))))

(deftest stale-captured-dispatch-omits-coord-when-only-sub-registered
  (testing "rf2-7xlvt — the inverse omit: a stale captured `:dispatch` for an id
            that is ONLY a SUB (no event coord) OMITS `:source-coord` rather than
            stealing the unrelated sub's. Pre-fix the `[:sub]`-first fallback
            returned the SUB coord — the wrong realm."
    (let [records (capture-superseded-record
                    :dispatch :audit/collide
                    (fn [] (source-coords/remember-error-coords!
                             :sub :audit/collide xlvt-sub-coord)))]
      (is (= 1 (count records)))
      (let [r (first records)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (not (contains? r :source-coord))
            "no event coord ⇒ :source-coord is ABSENT, never the unrelated sub's")))))

;; ============================================================================
;; rf2-a2x2w — the LATE captured-op frame-destroyed rejections are REALM-EXACT
;;   + `:op` realm attribution is RATIFIED PUBLIC on captured-op recovery records
;; ----------------------------------------------------------------------------
;; rf2-7xlvt (above) repaired the SYNCHRONOUS capture PRE-CHECK seam
;; (`emit-captured-frame-superseded!`). Two later rejection seams still DROPPED
;; the realm — so `error-source-coord` fell back to the legacy
;; `[:sub]`-then-`[:event]` probe (correct only for the realm-ambiguous bare
;; router/subs emitters): (1) the router's A→B incarnation-mismatch fences in
;; `dispatch!` / `dispatch-sync!` and the subscribe mismatch in `subs`, reached
;; when the pre-check saw A LIVE but a same-id successor B reseated before the
;; bare-id resolve; (2) loss of A after the token match but before enqueue /
;; drain-lock acquire (the exactly-once window — pinned in
;; `re-frame.capture-frame-test`). These drive seam (1): a ONE-SHOT interposition
;; on `frame/frame-incarnation-live?` destroys A and reseats B AT the pre-check,
;; so the pre-check PASSES (A "live") and the LATE fence catches the reseated B.
;; They assert the delivered always-on record's `:source-coord` names the EXACT
;; realm (dispatch / dispatch-sync → `[:event id]`, subscribe → `[:sub id]`) or
;; is OMITTED when the realm's own coord is absent — and that the ratified-public
;; `:op` realm slot rides the captured-op record (absent on ordinary records).
;; ============================================================================

(defn- late-superseded-records
  "Pin a `capture-frame` api to a LIVE frame A, then run the captured `op-key` op
  (`:dispatch` / `:dispatch-sync` / `:subscribe`) with a ONE-SHOT interposition
  on `frame/frame-incarnation-live?`: at the capture's synchronous pre-check (the
  ONE liveness check `capture-target-superseded?` makes) destroy A and reseat a
  same-id successor B BEFORE handing back A's (true) liveness. The pre-check thus
  sees A LIVE (not superseded), delegates to the ordinary bare-id resolve, which
  lands on B — firing the LATE A→B fence (rf2-dlld6 / rf2-7w1im), the seam this
  bead makes realm-exact. `seed` populates the always-on error-coord registry.
  Returns the vector of always-on records the `:errors` listener saw."
  [op-key id seed]
  (let [fid :a2x2w/target]
    (rf/make-frame {:id fid :doc "capture target A"})
    (let [h       (rf/capture-frame fid)
          a-token (frame/frame-incarnation-token fid)
          seen    (atom [])
          real    frame/frame-incarnation-live?
          fired   (atom false)]
      (source-coords/forget-error-coords!)
      (seed)
      (rf/register-listener! :errors :a2x2w/recorder
                             (fn [record] (swap! seen conj record)))
      (with-redefs [frame/frame-incarnation-live?
                    (fn [id* token]
                      (let [live? (real id* token)]
                        (when (and (not @fired) (= id* fid)
                                   (identical? token a-token) live?)
                          (reset! fired true)   ;; set BEFORE the swap so the
                          ;; destroy/create's own liveness reads take the real path
                          (rf/destroy-frame! fid)
                          (rf/make-frame {:id fid :doc "same-id successor B"}))
                        live?))]
        (case op-key
          :dispatch      ((:dispatch h) [id])
          :dispatch-sync ((:dispatch-sync h) [id])
          :subscribe     ((:subscribe h) [id])))
      @seen)))

(deftest late-superseded-dispatch-resolves-the-event-coord-not-the-collision-sub
  (testing "rf2-a2x2w (gap 1, dispatch) — a captured `:dispatch` rejected at the
            LATE A→B router fence whose id is BOTH an event AND a same-keyword sub
            resolves the EVENT coord, realm-exact via the threaded `:op`; pre-fix
            the late fence dropped the realm and the `[:sub]`-first fallback stole
            the sub's coord."
    (let [records (late-superseded-records :dispatch :audit/collide (seed-both :audit/collide))]
      (is (= 1 (count records)) "exactly one always-on record per late rejection")
      (let [r (first records)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (= xlvt-event-coord (:source-coord r))
            "late captured dispatch resolves the EVENT coord, never the collision sub's")
        (is (= :dispatch (:op r))
            "rf2-a2x2w: the ratified-public `:op` realm rides the captured-op record")))))

(deftest late-superseded-dispatch-sync-shares-the-event-realm
  (testing "rf2-a2x2w (gap 1, dispatch-sync) — a captured `:dispatch-sync` at the
            LATE fence shares the dispatch event realm: the EVENT coord."
    (let [records (late-superseded-records :dispatch-sync :audit/collide (seed-both :audit/collide))]
      (is (= 1 (count records)))
      (is (= xlvt-event-coord (:source-coord (first records)))
          "late captured dispatch-sync resolves the EVENT coord")
      (is (= :dispatch-sync (:op (first records)))))))

(deftest late-superseded-subscribe-resolves-the-sub-coord-not-the-collision-event
  (testing "rf2-a2x2w (gap 1, subscribe) — a captured `:subscribe` at the LATE
            subscribe fence whose id is BOTH resolves the SUB coord, never the
            same-keyword event's."
    (let [records (late-superseded-records :subscribe :audit/collide (seed-both :audit/collide))]
      (is (= 1 (count records)))
      (is (= xlvt-sub-coord (:source-coord (first records)))
          "late captured subscribe resolves the SUB coord")
      (is (= :subscribe (:op (first records)))))))

(deftest late-superseded-subscribe-omits-coord-when-only-event-registered
  (testing "rf2-a2x2w (gap 1 — the EXACT bead repro) — a captured `:subscribe`
            rejected at the LATE subscribe fence for an id that is ONLY an EVENT
            (no sub coord) OMITS `:source-coord` rather than STEALING the
            unrelated event's. Pre-fix the late subscribe fence dropped the realm
            and the `[:sub]`-then-`[:event]` fallback returned the EVENT coord —
            exactly the wrong-realm record the bead's repro captured."
    (let [records (late-superseded-records
                    :subscribe :audit/collide
                    (fn [] (source-coords/remember-error-coords!
                             :event :audit/collide xlvt-event-coord)))]
      (is (= 1 (count records)))
      (let [r (first records)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (not (contains? r :source-coord))
            "no sub coord ⇒ :source-coord ABSENT, never the unrelated event's")
        (is (= :subscribe (:op r)) "the `:subscribe` realm still rides the record")))))

(deftest late-superseded-dispatch-omits-coord-when-only-sub-registered
  (testing "rf2-a2x2w (gap 1, inverse omit) — a captured `:dispatch` at the LATE
            fence for an id that is ONLY a SUB OMITS `:source-coord` rather than
            stealing the unrelated sub's."
    (let [records (late-superseded-records
                    :dispatch :audit/collide
                    (fn [] (source-coords/remember-error-coords!
                             :sub :audit/collide xlvt-sub-coord)))]
      (is (= 1 (count records)))
      (let [r (first records)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (not (contains? r :source-coord))
            "no event coord ⇒ :source-coord ABSENT, never the unrelated sub's")
        (is (= :dispatch (:op r)))))))

(deftest ordinary-address-directed-frame-destroyed-omits-op-and-keeps-fallback
  (testing "rf2-a2x2w (keyset ratification — the negative) — an ORDINARY
            address-directed DISPATCH into an unknown frame carries NO `:op`
            realm slot (the tight record keyset is PRESERVED). rf2-alk8a narrows
            this to the DISPATCH path only: a dispatched event vector is PAYLOAD,
            so it keeps op-omission, its per-path elision, and its
            unresolvable-frame fail-closed. The ordinary SUBSCRIBE path now
            stamps `:op :subscribe` because a query vector is IDENTITY (see
            `ordinary-subscribe-frame-destroyed-stamps-op-egresses-raw-resolves-sub-coord`)."
    (let [seen (atom [])]
      (rf/register-listener! :errors :a2x2w/plain (fn [record] (swap! seen conj record)))
      (rf/dispatch-sync [:a2x2w/nope] {:frame :a2x2w/gone-frame})
      (is (= 1 (count @seen)))
      (let [r (first @seen)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (not (contains? r :op))
            "ordinary address-directed DISPATCH frame-destroyed carries NO `:op` — tight keyset preserved")))))

;; ============================================================================
;; rf2-alk8a — the ORDINARY address-directed SUBSCRIBE frame-destroyed path
;;   stamps `:op :subscribe`, egresses the query vector RAW, and resolves the
;;   `:source-coord` under the EXACT `[:sub id]` realm (never the legacy
;;   `[:sub]`-then-`[:event]` fallback that could steal a same-keyword event's
;;   coord). These drive the REAL runtime path (`rf/subscribe-once` into a
;;   missing frame), NOT a simulated `emit-error-both!` call — so they pin the
;;   emitter stamp end-to-end. RAW because a subscription's query vector is
;;   IDENTITY (rf2-zwgqe / Spec 015 "pass identifiers, not secrets"); the
;;   rf2-t55hxg.18 fail-closed guards policy-walked VALUE slots, which identity
;;   slots never consult.
;; ============================================================================

(deftest ordinary-subscribe-frame-destroyed-stamps-op-egresses-raw-resolves-sub-coord
  (testing "rf2-alk8a — an ORDINARY address-directed subscribe into a missing
            frame stamps `:op :subscribe`, egresses the query vector RAW on
            `:event`, and resolves its `:source-coord` under the EXACT `[:sub
            id]` realm even when the id is ALSO registered as a same-keyword
            event."
    (source-coords/forget-error-coords!)
    ;; the id collides: distinct coords under BOTH `[:event id]` and `[:sub id]`.
    (source-coords/remember-error-coords! :event :alk8a/collide xgkgx-event-coord)
    (source-coords/remember-error-coords! :sub   :alk8a/collide xgkgx-sub-coord)
    (let [seen (atom [])]
      (rf/register-listener! :errors :alk8a/recorder (fn [record] (swap! seen conj record)))
      (is (nil? (rf/subscribe-once [:alk8a/collide] {:frame :alk8a/gone-frame}))
          "subscribe into a missing frame recovers to nil")
      (is (= 1 (count @seen)))
      (let [r (first @seen)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (= :subscribe (:op r))
            "the ordinary subscribe path now stamps the `:subscribe` realm (rf2-alk8a)")
        (is (= [:alk8a/collide] (:event r))
            ":event egresses the query vector RAW (identity, rf2-zwgqe)")
        (is (= xgkgx-sub-coord (:source-coord r))
            ":source-coord resolves the SUB coord realm-exact, never the collision event's")))))

(deftest ordinary-subscribe-frame-destroyed-omits-coord-for-same-keyword-event-only
  (testing "rf2-alk8a (SOURCE-COORD UPGRADE — the anti-stealing case) — an
            ordinary subscribe into a missing frame for an id registered ONLY as
            a same-keyword EVENT OMITS `:source-coord` rather than STEALING the
            event's. Pre-fix the op-nil path's `[:sub]`-then-`[:event]` fallback
            returned the unrelated EVENT coord; the `:op :subscribe` stamp
            resolves `[:sub id]` (a miss) and omits the slot."
    (source-coords/forget-error-coords!)
    (source-coords/remember-error-coords! :event :alk8a/event-only xgkgx-event-coord)
    (let [seen (atom [])]
      (rf/register-listener! :errors :alk8a/recorder2 (fn [record] (swap! seen conj record)))
      (is (nil? (rf/subscribe-once [:alk8a/event-only] {:frame :alk8a/gone-frame})))
      (is (= 1 (count @seen)))
      (let [r (first @seen)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (= :subscribe (:op r)) "the `:subscribe` realm still rides the record")
        (is (= [:alk8a/event-only] (:event r)) ":event raw (identity)")
        (is (not (contains? r :source-coord))
            "no sub coord ⇒ :source-coord ABSENT, never the same-keyword event's")))))

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
