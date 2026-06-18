(ns re-frame.on-error-elision-prod-test
  "Per rf2-bacs4 — the corpus-wide `register-error-listener!` registry is
  the always-on error observability surface; off-box observability
  shippers (Sentry / Honeybadger / Rollbar) wire through it. It MUST fire
  even when the CLJS trace surface is compile-time elided in production
  builds (`:advanced` + `goog.DEBUG=false`). This file pins that the
  listener registry survives elision. (The per-frame `:on-error` recovery
  policy was REMOVED per rf2-hiqtk8 — recovery is framework-owned via the
  per-category typed defaults, not an app-steering policy.)

  Companion to `re-frame.trace-listener-elision-prod-test` (rf2-2zdu)
  and `re-frame.source-coord-dom-elision-prod-test` (rf2-uwg5). The
  shared runner is `re-frame.prod-elision-runner`; the shadow-cljs
  build is `:browser-test-prod-elision` (`:advanced` +
  `{goog.DEBUG false}`).

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build. The default
  `:browser-test` / `:node-test` runners use regexes that do NOT match
  this suffix, so these tests run only under prod-mode compilation."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter
     :init-fn (fn []
                ;; Per rf2-bacs4: clear the listener registry between
                ;; tests — defonce means it would otherwise leak.
                (error-emit/clear-error-listeners!))}))

;; ---- handler-exception recovers under goog.DEBUG=false --------------------

(deftest handler-throw-recovers-under-prod
  (testing "Under `:advanced` + `goog.DEBUG=false`, a handler throw is
            caught by the runtime (interceptor chain wraps it in
            `:rf/interceptor-error`) and the drain settles — the dispatch
            returns nil, the cascade does not abort. Recovery is
            framework-owned; there is no app-steering policy."
    (rf/reg-event :prod/quiet-throw
                     (fn [{:keys [db]} _]
                       {:db (throw (ex-info "boom" {}))}))
    (is (nil? (rf/dispatch-sync [:prod/quiet-throw]))
        "dispatch-sync returns nil; the drain settled after the exception")))

;; ---- rf2-bacs4 corpus-wide listener survives goog.DEBUG=false -----------

(deftest error-emit-listener-fires-under-prod
  (testing "Per rf2-bacs4: under `:advanced` + `goog.DEBUG=false`, a
            registered corpus-wide error-emit listener MUST fire for
            every handler exception — the trace surface is gone but
            the always-on error-emit substrate delivers the tight
            record so off-box observability shippers (Sentry /
            Honeybadger / Rollbar) still see every framework error."
    (let [seen (atom [])]
      (rf/register-listener! :errors
        :prod/recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event :prod/err-throw
                       (fn [{:keys [db]} _]
                         {:db (throw (ex-info "kaboom" {:cause :test}))}))
      (rf/dispatch-sync [:prod/err-throw])
      (is (= 1 (count @seen))
          "listener fired exactly once — prod-elision contract holds")
      (let [r (first @seen)]
        (is (= :rf.error/handler-exception (:error r)))
        (is (= [:prod/err-throw] (:event r)))
        (is (= :prod/err-throw   (:event-id r)))
        (is (= :rf/default       (:frame r)))
        (is (number? (:time r)))
        (is (integer? (:elapsed-ms r))
            ":elapsed-ms is an integer under :advanced + goog.DEBUG=false
             — the substrate boundary rounds the CLJS float-precision
             performance.now() value")))))

(deftest error-emit-listener-exception-swallowed-under-prod
  (testing "A buggy listener cannot break the cascade under prod. The
            substrate catches listener throws silently — the dispatch
            settles and sibling listeners still run. Mirrors the dev-
            mode contract from `re-frame.on-error-test`; pinned here
            so the prod-build behaviour is locked too."
    (let [seen (atom [])]
      (rf/register-listener! :errors
        :prod/throws
        (fn [_record] (throw (ex-info "listener went boom" {}))))
      (rf/register-listener! :errors
        :prod/sibling
        (fn [record] (swap! seen conj record)))
      (rf/reg-event :prod/two-listeners
                       (fn [{:keys [db]} _] {:db (throw (ex-info "handler boom" {}))}))
      (is (nil? (rf/dispatch-sync [:prod/two-listeners]))
          "dispatch-sync returned nil despite the listener throw")
      (is (= 1 (count @seen))
          "the sibling listener still received the record under prod"))))

;; ---- (removed) rf2-vnjfg handler-meta :sensitive? redaction under prod ---
;;
;; The handler-meta `:sensitive?` annotation has been removed. Per-path
;; elision (the per-frame `[:rf.runtime/elision]` runtime-db registry, populated from app-schema
;; `:sensitive?` slot meta) is the load-bearing privacy surface on the
;; error-emit path under prod.

;; ---- rf2-3un2g :source-coord rides the prod error-emit substrate --------

(deftest source-coord-rides-error-record-under-prod
  (testing "Per rf2-3un2g Policy B: under `:advanced` + `goog.DEBUG=false`,
            the tight error-record passed to corpus-wide listeners MUST
            include `:source-coord` for handlers registered via the
            public macro path. The coord rides the always-on parallel
            `error-coords-by-id` registry — it survives prod elision so
            Sentry-style shippers see source-line info even when the
            trace surface is gone and registry-meta has been stripped
            of coord-keys."
    (let [listener-saw (atom nil)]
      (rf/register-listener! :errors
        :rf2-3un2g/sentry-recorder
        (fn [record] (reset! listener-saw record)))
      (rf/reg-event :rf2-3un2g/prod-coord-throw
                       (fn [{:keys [db]} _]
                         {:db (throw (ex-info "boom" {}))}))
      (rf/dispatch-sync [:rf2-3un2g/prod-coord-throw])
      (is (some? @listener-saw)
          "listener fired under :advanced + goog.DEBUG=false")
      (let [sc (:source-coord @listener-saw)]
        (is (some? sc)
            ":source-coord present on the prod-mode error-record")
        ;; :ns is a symbol; :line is an integer; :file is a string.
        ;; :column is absent under prod (the prod-coords-form omits it).
        (is (symbol?  (:ns sc))
            ":source-coord :ns is a symbol in prod")
        (is (integer? (:line sc))
            ":source-coord :line is an integer in prod")
        (is (string?  (:file sc))
            ":source-coord :file is a string in prod")
        (is (not (contains? sc :column))
            ":source-coord :column is ABSENT in prod (DCE'd from the
             coords-form literal under :advanced + goog.DEBUG=false)")))))

(deftest registry-meta-stripped-of-coord-keys-under-prod
  (testing "Per rf2-3un2g Policy A: under `:advanced` + `goog.DEBUG=false`
            the public `rf/handler-meta` MUST NOT carry `:ns` / `:file`
            / `:line` / `:column` coord-keys. Xray Open-in-editor and
            re-frame-pair are dev-only — production bundles strip the
            coord-keys from the registry-meta surface; coords for
            error-emit ride the always-on parallel registry instead.

            Per rf2-9wwkcm (Spec 001 §Production elision contract, Policy 3)
            the user-supplied pure-documentation key `:doc` is ALSO stripped
            from the public meta in production — it has zero production
            runtime / observability use. Load-bearing keys (`:tags` /
            `:schema` / `:sensitive?` / `:large?` / `:interceptors` / …)
            are retained; `:tags` below pins that the strip is surgical."
    (rf/reg-event :rf2-3un2g/prod-meta-strip
                     {:doc "stripped" :tags #{:probe}}
                     (fn [{:keys [db]} _] {:db db}))
    (let [meta (rf/handler-meta :event :rf2-3un2g/prod-meta-strip)]
      (is (some? meta))
      (is (not (contains? meta :doc))
          "user-supplied :doc is stripped in prod (pure documentation, rf2-9wwkcm)")
      (is (= #{:probe} (:tags meta))
          "load-bearing :tags retained — the doc strip is surgical")
      (is (not (contains? meta :ns))     ":ns absent in prod meta")
      (is (not (contains? meta :file))   ":file absent in prod meta")
      (is (not (contains? meta :line))   ":line absent in prod meta")
      (is (not (contains? meta :column)) ":column absent in prod meta"))))

;; ---- end rf2-3un2g block -------------------------------------------------

;; ==========================================================================
;; rf2-2hvga (= B / widen) — every widened :rf.error/* category survives
;; production elision. THE CRUX: these categories were previously dev-trace-
;; ONLY (`trace/emit-error!`), which DCEs under `:advanced` + `goog.DEBUG=
;; false`, so a frame-destroyed / no-such-handler / no-such-sub / compute-sub
;; throw lost its diagnostic entirely in production. Now they ALSO fan out
;; through the always-on error-emit listener (surface #4), which survives
;; `goog.DEBUG=false`. Each test here is the production-mode counterpart
;; of the dev-mode test in `re-frame.on-error-test`. Recovery is
;; framework-owned (the per-category typed defaults); there is no
;; app-steering policy (rf2-hiqtk8).
;; ==========================================================================

(deftest frame-destroyed-dispatch-listener-survives-prod
  (testing "Per rf2-2hvga: under `:advanced` + `goog.DEBUG=false`, a
            `dispatch` to an unknown frame RECOVERS (no-op) AND fans
            `:rf.error/frame-destroyed` through the always-on listener —
            the dev trace is gone, the listener record survives."
    (let [seen (atom [])]
      (rf/register-listener! :errors :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (is (nil? (rf/dispatch [:whatever] {:frame :gone/frame})))
      (is (= 1 (count @seen)) "listener fired under prod elision")
      (let [r (first @seen)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (= :gone/frame (:frame r)))
        ;; EP-0015 issue 1 (rf2-t55hxg.18) — the `:event` slot is projected
        ;; against the record's frame, which is UNRESOLVABLE here. With no
        ;; classification policy to consult the slot FAILS CLOSED to
        ;; `:rf/redacted` (it must not leak the attempted event vector under
        ;; an empty policy) — and the fail-closed posture holds in production
        ;; (`goog.DEBUG=false`), not just dev. Mirrors the dev-mode
        ;; counterpart `listener-fires-on-frame-destroyed-dispatch`. The
        ;; structural `:event-id` keyword still survives for observability.
        (is (= :rf/redacted (:event r))
            ":event fails closed under an unresolvable frame (prod survival)")
        (is (= :whatever (:event-id r)))))))

(deftest frame-destroyed-dispatch-sync-listener-survives-prod
  (testing "Per rf2-2hvga: `dispatch-sync` to an unknown frame emits
            `:rf.error/frame-destroyed` through the always-on listener
            under `goog.DEBUG=false`."
    (let [seen (atom [])]
      (rf/register-listener! :errors :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (is (nil? (rf/dispatch-sync [:whatever] {:frame :gone/frame})))
      (is (= 1 (count @seen)))
      (is (= :rf.error/frame-destroyed (:error (first @seen))))
      (is (= :gone/frame (:frame (first @seen)))))))

(deftest frame-destroyed-subscribe-listener-survives-prod
  (testing "Per rf2-2hvga: `subscribe` to an unknown / destroyed frame
            RECOVERS (nil) AND emits `:rf.error/frame-destroyed` through
            the always-on listener under `goog.DEBUG=false` — the
            teardown-race shape stays observable in production."
    (let [seen (atom [])]
      (rf/register-listener! :errors :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (is (nil? (rf/subscribe-once :gone/frame [:any-sub])))
      (is (= 1 (count @seen)))
      (let [r (first @seen)]
        (is (= :rf.error/frame-destroyed (:error r)))
        (is (= :gone/frame (:frame r)))
        ;; EP-0015 issue 1 (rf2-t55hxg.18) — the `:event` slot is projected
        ;; against the record's frame, which is UNRESOLVABLE here. With no
        ;; classification policy to consult the slot FAILS CLOSED to
        ;; `:rf/redacted` (it must not leak the attempted query-v under an
        ;; empty policy) — and the fail-closed posture holds in production
        ;; (`goog.DEBUG=false`), not just dev.
        (is (= :rf/redacted (:event r))
            ":event fails closed under an unresolvable frame (prod survival)")))))

(deftest no-such-handler-listener-survives-prod
  (testing "Per rf2-2hvga (= B / widen): a dispatch to a never-registered
            handler emits `:rf.error/no-such-handler` through the
            always-on listener under `goog.DEBUG=false` (previously
            dev-trace-only — silent in production)."
    (let [seen (atom [])]
      (rf/register-listener! :errors :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/dispatch-sync [:no/handler-here])
      (let [r (some (fn [x] (when (= :rf.error/no-such-handler (:error x)) x)) @seen)]
        (is (some? r) "listener received :rf.error/no-such-handler under prod")
        (is (= :no/handler-here (:event-id r)))
        (is (= :rf/default (:frame r)))))))

(deftest no-such-sub-listener-survives-prod
  (testing "Per rf2-2hvga (= B / widen): a subscribe to a never-registered
            sub emits `:rf.error/no-such-sub` through the always-on
            listener under `goog.DEBUG=false`."
    (let [seen (atom [])]
      (rf/register-listener! :errors :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (is (nil? (rf/subscribe-once :rf/default [:no/such-sub-here])))
      (let [r (some (fn [x] (when (= :rf.error/no-such-sub (:error x)) x)) @seen)]
        (is (some? r) "listener received :rf.error/no-such-sub under prod")
        (is (= :no/such-sub-here (:event-id r)))
        (is (= :rf/default (:frame r)))))))

(deftest compute-sub-exception-listener-survives-prod
  (testing "Per rf2-2hvga (= B / widen) — SETTLES rf2-kjf3m.3: a sub that
            throws while resolving via the PURE `compute-sub` path emits
            `:rf.error/sub-exception` through the always-on listener
            under `goog.DEBUG=false`. THIS is the fail-open class kjf3m.3
            flagged: under production hardening compute-sub previously
            recovered to nil with NO always-on emission → a silent 200
            for an SSR harness driving subs via compute-sub. Now the
            listener record survives elision."
    (let [seen (atom [])]
      (rf/register-listener! :errors :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-sub :kjf3m/throwing (fn [_db _q] (throw (ex-info "compute-boom" {}))))
      (is (nil? (rf/compute-sub [:kjf3m/throwing] {}))
          "compute-sub still recovers to nil under prod")
      (let [r (some (fn [x] (when (= :rf.error/sub-exception (:error x)) x)) @seen)]
        (is (some? r)
            "listener received :rf.error/sub-exception from compute-sub under prod")
        (is (some? (:exception r)))))))

;; ==========================================================================
;; rf2-goum9x — the fx / cofx production error categories survive prod
;; elision. THE CRUX: a thrown registered fx
;; (`:rf.error/fx-handler-exception`), an unknown fx-id
;; (`:rf.error/no-such-fx`), an override misconfiguration
;; (`:rf.error/override-fallthrough`), and an unknown cofx-id
;; (`:rf.error/no-such-cofx`) were previously dev-trace-ONLY, so they lost
;; their diagnostic entirely under `:advanced` + `goog.DEBUG=false` — even
;; though Spec 009 catalogues them as production-reachable and Spec 011 maps
;; fx-handler-exception to a 500 off the always-on substrate. Now they fan
;; out through the always-on listener (axis 1), which survives elision.
;; ==========================================================================

(deftest fx-handler-exception-listener-survives-prod
  (testing "Per rf2-goum9x: under `:advanced` + `goog.DEBUG=false`, a
            registered fx that throws fans `:rf.error/fx-handler-exception`
            through the always-on listener — so the SSR error projector
            (which rides this substrate) can fail-closed to 500 and off-box
            shippers see the failure. The dev trace is gone; the listener
            record survives."
    (let [seen (atom [])]
      (rf/register-listener! :errors :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-fx :goum9x/prod-throwing-fx
                 (fn [_ _] (throw (ex-info "fx-boom" {}))))
      (rf/reg-event :goum9x/prod-run-throwing-fx
                       (fn [_ _] {:fx [[:goum9x/prod-throwing-fx]]}))
      (rf/dispatch-sync [:goum9x/prod-run-throwing-fx])
      (let [r (some (fn [x] (when (= :rf.error/fx-handler-exception (:error x)) x)) @seen)]
        (is (some? r) "listener received :rf.error/fx-handler-exception under prod")
        (is (= :goum9x/prod-run-throwing-fx (:event-id r)))
        (is (= :rf/default (:frame r)))
        (is (some? (:exception r)))))))

(deftest no-such-fx-listener-survives-prod
  (testing "Per rf2-goum9x: an unknown fx-id fans `:rf.error/no-such-fx`
            through the always-on listener under `goog.DEBUG=false`."
    (let [seen (atom [])]
      (rf/register-listener! :errors :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-event :goum9x/prod-unknown-fx
                       (fn [_ _] {:fx [[:goum9x/prod-never {}]]}))
      (rf/dispatch-sync [:goum9x/prod-unknown-fx])
      (let [r (some (fn [x] (when (= :rf.error/no-such-fx (:error x)) x)) @seen)]
        (is (some? r) "listener received :rf.error/no-such-fx under prod")
        (is (= :goum9x/prod-unknown-fx (:event-id r)))
        (is (= :rf/default (:frame r)))))))

(deftest override-fallthrough-listener-survives-prod
  (testing "Per rf2-goum9x: an `:fx-overrides` redirect to an unregistered
            fx-id fans `:rf.error/override-fallthrough` through the
            always-on listener under `goog.DEBUG=false`."
    (let [seen (atom [])]
      (rf/register-listener! :errors :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-fx :goum9x/prod-real-fx (fn [_ _] nil))
      (rf/reg-event :goum9x/prod-bad-override
                       (fn [_ _] {:fx [[:goum9x/prod-real-fx]]}))
      (rf/dispatch-sync [:goum9x/prod-bad-override]
                        {:fx-overrides {:goum9x/prod-real-fx :goum9x/prod-missing}})
      (let [r (some (fn [x] (when (= :rf.error/override-fallthrough (:error x)) x)) @seen)]
        (is (some? r) "listener received :rf.error/override-fallthrough under prod")
        (is (= :rf/default (:frame r)))))))

(deftest reserved-fx-override-listener-survives-prod
  (testing "Per rf2-uh5ic5 / Spec 009 §Error event catalogue: under
            `:advanced` + `goog.DEBUG=false`, a rejected `:fx-overrides`
            entry targeting a REJECT-tier reserved fx-id fans
            `:rf.error/reserved-fx-override` through the always-on
            `register-error-listener!` substrate. This is the REAL producer
            path: under prod the dev per-call reject in `handle-one-fx`
            DCEs with the trace surface, so the router routes the effective
            override map through `re-frame.fx/strip-rejected-overrides`
            (the `:production-strip` site), which fans the error out on the
            always-on axis. Driven through the genuine `dispatch-sync` →
            fx-walk path — proving the production prod-strip producer (not
            just the dev per-call one) survives elision."
    (let [seen (atom [])]
      (rf/register-listener! :errors :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      ;; :rf.fx/reg-flow is a REJECT-tier reserved fx (installs durable
      ;; per-frame flow-registry state). A prod build cannot stub it out.
      (rf/reg-event :uh5ic5/install-flow
                       (fn [_ _] {:fx [[:rf.fx/reg-flow
                                        {:id     :uh5ic5/prod-flow
                                         :inputs [[:uh5ic5 :seed]]
                                         :output (fn [_] 1)
                                         :path   [:uh5ic5 :out]}]]}))
      (rf/dispatch-sync [:uh5ic5/install-flow]
                        {:fx-overrides {:rf.fx/reg-flow (fn [_ _] :should-not-fire)}})
      (let [r (some (fn [x] (when (= :rf.error/reserved-fx-override (:error x)) x)) @seen)]
        (is (some? r)
            "listener received :rf.error/reserved-fx-override under prod
             (the strip-rejected-overrides production producer survives elision)")
        (is (= :uh5ic5/install-flow (:event-id r))
            ":event-id is the dispatched event-vector head")
        (is (= :rf/default (:frame r))
            ":frame names the frame the override was rejected in")))))

(deftest unregistered-cofx-listener-survives-prod
  (testing "EP-0017 (succeeds rf2-goum9x): a `:rf.cofx/requires` declaring an
            UNREGISTERED cofx-id fans `:rf.error/unregistered-cofx` through the
            always-on listener under `goog.DEBUG=false` (the typo case is a
            production-survivable correctness contract)."
    (let [seen (atom [])]
      (rf/register-listener! :errors :prod/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-event :goum9x/prod-unknown-cofx
                       {:rf.cofx/requires [:goum9x/prod-no-cofx]}
                       (fn [_ _] {}))
      (try (rf/dispatch-sync [:goum9x/prod-unknown-cofx])
           (catch :default _ nil))
      (let [r (some (fn [x] (when (= :rf.error/unregistered-cofx (:error x)) x)) @seen)]
        (is (some? r) "listener received :rf.error/unregistered-cofx under prod")
        (is (= :rf/default (:frame r)))))))

;; (removed) sensitive-handler-error-record-redacted-under-prod
;; The handler-meta `:sensitive?` annotation has been removed. Redaction on
;; the error-emit substrate is now driven exclusively by the per-path elision
;; wire-walker — see the rf2-3un2g block above for the prod-survivable
;; substrate contract.
