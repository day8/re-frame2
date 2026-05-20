(ns re-frame.on-error-elision-prod-test
  "Per rf2-hqbeh — the per-frame `:on-error` slot is a runtime
  error-recovery surface and MUST fire even when the CLJS trace surface
  is compile-time elided in production builds (`:advanced` +
  `goog.DEBUG=false`).

  Before rf2-hqbeh the `:on-error` policy rode the trace surface
  exclusively; with the surface elided, registered `:on-error`
  callbacks did not fire in CLJS prod. A user-registered Sentry-shape
  forwarder went silent on the production-build path it was written
  for. This file pins the fix.

  Per rf2-bacs4 — the corpus-wide
  `register-error-emit-listener!` registry is the second always-on
  fan-out path from the same error-emit substrate; off-box
  observability shippers (Sentry / Honeybadger / Rollbar) wire through
  it. This file pins that the listener registry survives elision
  alongside the per-frame `:on-error` policy.

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
                (error-emit/clear-error-emit-listeners!))}))

;; ---- :on-error survives goog.DEBUG=false ----------------------------------

(deftest on-error-fires-under-prod-when-handler-throws
  (testing "Per rf2-hqbeh: under `:advanced` + `goog.DEBUG=false`, a
            frame's `:on-error` policy fn MUST fire when an event
            handler throws — the trace surface is gone, but the
            always-on error-emit substrate delivers the structured
            error event to the policy fn so production monitoring
            integrations still observe the failure."
    (let [seen (atom [])]
      ;; Re-register :rf/default with an :on-error policy. The fixture
      ;; make-reset-runtime-fixture installs :rf/default with no policy; we
      ;; re-register to attach one. Per Spec 002 §Re-registration —
      ;; surgical update, the existing app-db / sub-cache survive.
      (rf/reg-frame :rf/default
                    {:on-error (fn [error-event]
                                 (swap! seen conj error-event)
                                 nil)})
      (rf/reg-event-db :prod/throw
                       (fn [_db _]
                         (throw (ex-info "kaboom" {:cause :test}))))
      (rf/dispatch-sync [:prod/throw])
      (is (= 1 (count @seen))
          ":on-error fired exactly once for the thrown handler — prod-elision contract holds")
      (let [ev (first @seen)]
        (is (= :rf.error/handler-exception (:operation ev))
            ":on-error received the structured :operation discriminator")
        (is (= :error (:op-type ev))
            "and the :op-type :error discriminator")
        (is (= :prod/throw (get-in ev [:tags :event-id]))
            "and :event-id under :tags identifying the failing handler")
        (is (= :rf/default (get-in ev [:tags :frame]))
            "and :frame identifying the policy's host frame")))))

(deftest on-error-no-op-when-not-registered-under-prod
  (testing "When no `:on-error` policy is configured on the frame, the
            always-on substrate is a no-op and the handler exception
            still propagates through the rest of the runtime per the
            documented per-category recovery. The dispatch settles —
            the drain does not abort."
    (rf/reg-event-db :prod/quiet-throw
                     (fn [_db _]
                       (throw (ex-info "boom" {}))))
    ;; No :on-error registered; just confirm dispatch returns without
    ;; throwing. The runtime catches the handler exception (interceptor
    ;; chain wraps it in :rf/interceptor-error) and the drain settles.
    (is (nil? (rf/dispatch-sync [:prod/quiet-throw]))
        "dispatch-sync returns nil; the drain settled after the exception")))

(deftest on-error-policy-exception-is-swallowed-under-prod
  (testing "Per Spec 009 §1052: when the `:on-error` policy fn itself
            throws, the runtime MUST NOT recursively invoke the policy
            on its own exception. The always-on substrate catches the
            policy's throw silently — the cascade does not abort, the
            drain settles."
    (rf/reg-frame :rf/default
                  {:on-error (fn [_error-event]
                               (throw (ex-info "policy itself threw" {})))})
    (rf/reg-event-db :prod/policy-throw
                     (fn [_db _]
                       (throw (ex-info "handler threw" {}))))
    ;; Should NOT throw — the policy fn's exception is caught inside
    ;; error-emit/dispatch-on-error!.
    (is (nil? (rf/dispatch-sync [:prod/policy-throw]))
        "dispatch-sync returned nil despite both handler AND policy throwing")))

;; ---- rf2-bacs4 corpus-wide listener survives goog.DEBUG=false -----------

(deftest error-emit-listener-fires-under-prod
  (testing "Per rf2-bacs4: under `:advanced` + `goog.DEBUG=false`, a
            registered corpus-wide error-emit listener MUST fire for
            every handler exception — the trace surface is gone but
            the always-on error-emit substrate delivers the tight
            record so off-box observability shippers (Sentry /
            Honeybadger / Rollbar) still see every framework error."
    (let [seen (atom [])]
      (rf/register-error-emit-listener!
        :prod/recorder
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :prod/err-throw
                       (fn [_db _]
                         (throw (ex-info "kaboom" {:cause :test}))))
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
      (rf/register-error-emit-listener!
        :prod/throws
        (fn [_record] (throw (ex-info "listener went boom" {}))))
      (rf/register-error-emit-listener!
        :prod/sibling
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :prod/two-listeners
                       (fn [_db _] (throw (ex-info "handler boom" {}))))
      (is (nil? (rf/dispatch-sync [:prod/two-listeners]))
          "dispatch-sync returned nil despite the listener throw")
      (is (= 1 (count @seen))
          "the sibling listener still received the record under prod"))))

(deftest listener-and-policy-fire-independently-under-prod
  (testing "Per rf2-bacs4 §independent paths under prod: when both a
            per-frame `:on-error` policy fn AND a corpus-wide listener
            are registered, BOTH fire on a handler exception. Neither
            blocks the other under `:advanced` + `goog.DEBUG=false`."
    (let [listener-saw (atom nil)
          policy-saw   (atom nil)]
      (rf/reg-frame :rf/default
                    {:on-error (fn [ev] (reset! policy-saw ev) nil)})
      (rf/register-error-emit-listener!
        :prod/recorder
        (fn [record] (reset! listener-saw record)))
      (rf/reg-event-db :prod/both
                       (fn [_db _] (throw (ex-info "boom" {}))))
      (rf/dispatch-sync [:prod/both])
      (is (some? @policy-saw)
          "per-frame :on-error fired under prod")
      (is (some? @listener-saw)
          "corpus-wide listener fired under prod — both paths survive elision")
      (is (= :rf.error/handler-exception (:error @listener-saw)))
      (is (= :prod/both (:event-id @listener-saw))))))

;; ---- (removed) rf2-vnjfg handler-meta :sensitive? redaction under prod ---
;;
;; The handler-meta `:sensitive?` annotation has been removed. Per-path
;; elision (the per-frame `:rf/elision` registry, populated from app-schema
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
      (rf/register-error-emit-listener!
        :rf2-3un2g/sentry-recorder
        (fn [record] (reset! listener-saw record)))
      (rf/reg-event-db :rf2-3un2g/prod-coord-throw
                       (fn [_db _]
                         (throw (ex-info "boom" {}))))
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

(deftest source-coord-rides-policy-event-tags-under-prod
  (testing "Per rf2-3un2g Policy B: under `:advanced` + `goog.DEBUG=false`,
            the structured `error-event` passed to the per-frame
            `:on-error` policy fn MUST include `:source-coord` under
            `:tags` for handlers registered via the public macro path.
            In-app recovery surfaces get the same observability signal
            as Sentry shippers."
    (let [policy-saw (atom nil)]
      (rf/reg-frame :rf/default
                    {:on-error (fn [ev] (reset! policy-saw ev) nil)})
      (rf/reg-event-db :rf2-3un2g/prod-policy-coord-throw
                       (fn [_db _]
                         (throw (ex-info "boom" {}))))
      (rf/dispatch-sync [:rf2-3un2g/prod-policy-coord-throw])
      (is (some? @policy-saw)
          ":on-error policy fired under :advanced + goog.DEBUG=false")
      (let [sc (get-in @policy-saw [:tags :source-coord])]
        (is (some? sc)
            ":source-coord rides :tags on the structured error-event in prod")
        (is (symbol?  (:ns sc)))
        (is (integer? (:line sc)))
        (is (string?  (:file sc)))))))

(deftest registry-meta-stripped-of-coord-keys-under-prod
  (testing "Per rf2-3un2g Policy A: under `:advanced` + `goog.DEBUG=false`
            the public `rf/handler-meta` MUST NOT carry `:ns` / `:file`
            / `:line` / `:column` coord-keys. Causa Open-in-editor and
            re-frame-pair are dev-only — production bundles strip the
            coord-keys from the registry-meta surface; coords for
            error-emit ride the always-on parallel registry instead."
    (rf/reg-event-db :rf2-3un2g/prod-meta-strip
                     {:doc "stripped"}
                     (fn [db _] db))
    (let [meta (rf/handler-meta :event :rf2-3un2g/prod-meta-strip)]
      (is (some? meta))
      (is (= "stripped" (:doc meta))
          "user-supplied :doc is preserved — only coord-keys strip")
      (is (not (contains? meta :ns))     ":ns absent in prod meta")
      (is (not (contains? meta :file))   ":file absent in prod meta")
      (is (not (contains? meta :line))   ":line absent in prod meta")
      (is (not (contains? meta :column)) ":column absent in prod meta"))))

;; ---- end rf2-3un2g block -------------------------------------------------

;; (removed) sensitive-handler-error-record-redacted-under-prod
;; The handler-meta `:sensitive?` annotation has been removed. Redaction on
;; the error-emit substrate is now driven exclusively by the per-path elision
;; wire-walker — see the rf2-3un2g block above for the prod-survivable
;; substrate contract.
