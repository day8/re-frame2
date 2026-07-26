(ns re-frame.always-on-validation-production-test
  "rf2-bza6e — the two schema-validation surfaces Spec 010 keeps ALWAYS-ON in
  production, pinned under the REAL gate.

  ## Why this namespace exists

  Spec 010 §Validation order elides validation by default: every `validate-*!`
  body sits inside `(when re-frame.interop/debug-enabled? …)`, and
  `spec/Security.md` §Production gates lists \"schema-validation calls\" among
  what the production gate strips. Under `-Dre-frame.debug=false` roughly a
  hundred validation call sites genuinely stop running, and four of the
  namespaces rf2-r9bra triaged fail in the `jvm-core-prod-gate` lane for
  exactly that reason — correctly, because they are dev-posture tests.

  Spec 010 carves out TWO surfaces from that elision, and until this namespace
  NEITHER had a test in any lane that runs under the gate:

    1. **Recordable-coeffect `:schema`** (010:165, 010:179) — \"a PRODUCTION
       HARD ERROR, not the dev-only schema-validation trace\". A recordable
       coeffect's value — supplied on the dispatch token, replayed from a
       record, or freshly generated — is validated as it is satisfied; a
       mismatch emits `:rf.error/cofx-value-invalid` and THROWS, halting the
       run before the handler sees it. The spec's rationale: \"Folding an
       out-of-contract value into the durable causal ledger is corrupt state,
       so the check fires in production too.\"

    2. **The `:rf.schema/at-boundary` interceptor** (010:204, 010:220) —
       \"Boundary validation runs even when global validation is elided.\" It
       is the opt-back-in for handlers fed by an untrusted system boundary
       (HTTP response, websocket frame, postMessage, query string), and it is
       INVERTED relative to everything else: a no-op in dev (step-1 already
       ran) and load-bearing only in production.

  That is precisely the shape that dies silently under a load-time gate — a
  validation call everyone assumes survives, sitting beside a hundred that
  provably do not. rf2-9c2jf was the same class of defect: `dispatch-sync`
  running its handler ZERO times under the documented gate, green the whole
  time, because nothing had ever executed that posture.

  ## How it is pinned

  Modelled on `re-frame.privacy-production-egress-test` (rf2-r9bra). Every
  assertion outside the one `^:prod-gate`-tagged deftest is
  POSTURE-INDEPENDENT and must hold in dev AND under the real gate, so this
  namespace runs in the ordinary `clojure -M:test` suite and joins
  `scripts/test-core-prod-gate.sh` automatically (that lane's roster is an
  EXCLUSION list — a new namespace joins by default).

  Posture-independence is not a weakening here, it is the contract: both
  surfaces are specified to behave IDENTICALLY in both postures. What differs
  is only WHICH code enforces surface 2 — step-1 `validate-event!` in dev, the
  boundary interceptor in production — and the observable (the handler does
  not run) is the same either way.

  The single `^:prod-gate` deftest at the foot is the discriminator that stops
  the rest passing vacuously. It runs ONLY in the gate lane (the default
  `:test` alias excludes the tag) and asserts the NEGATIVE control: in this
  same JVM, a handler carrying the identical `:schema` but NOT referencing
  `:rf.schema/at-boundary` accepts a non-conforming event, because ordinary
  step-1 validation really has been elided. Without it, \"the handler did not
  run\" would prove nothing about which mechanism stopped it.

  Deliberately NOT used: `with-redefs` on `interop/debug-enabled?`. The flag is
  read once at namespace-load time; a rebind cannot reach it (rf2-f7qj4)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (error-emit/clear-error-listeners!)
  (rf/init! plain-atom/adapter)
  ;; `registrar/clear-all!` drops the framework-standard interceptor
  ;; registrations; `init!` re-seeds them, including `:rf.schema/at-boundary`
  ;; (`re-frame.spec/register-schema-interceptors!`). Reloading the schemas
  ;; artefact republishes the late-bind validation hooks the two surfaces
  ;; under test reach through.
  (require 're-frame.schemas :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- record-always-on-errors
  "Capture through the ALWAYS-ON error registry — NOT `register-listener!
  :trace`, which is dev-only and sees nothing under the production gate.
  These records are what an off-box shipper receives from a production build."
  [body-fn]
  (let [seen (atom [])]
    (error-emit/register-error-listener! ::rec (fn [r] (swap! seen conj r)))
    (try (body-fn)
         (finally (error-emit/unregister-error-listener! ::rec)))
    @seen))

(defn- record-of [records error-kw]
  (first (filter #(= error-kw (:error %)) records)))

(defn- db-of [] (rf/app-db-value :rf/default))

;; ===========================================================================
;; Surface 1 — recordable-coeffect `:schema` is a PRODUCTION hard error
;; ===========================================================================

(deftest supplied-recordable-value-violating-its-schema-throws-in-every-posture
  (testing "rf2-bza6e / Spec 010:179 — a recordable coeffect SUPPLIED on the
            dispatch token (the replay-hole shape) is validated against its
            `reg-cofx` `:schema` before it folds into the handler context. A
            mismatch throws `:rf.error/cofx-value-invalid` and the handler
            never runs, in dev AND under `-Dre-frame.debug=false`. This is the
            durable-ledger contract: an out-of-contract recordable value is
            corrupt state whatever the build."
    (let [ran (atom false)]
      (rf/reg-cofx :prod/positive
        {:recordable? true :schema [:int {:min 1}]}
        (fn [] 1))
      (rf/reg-event :prod/uses-positive
        {:rf.cofx/requires [:prod/positive]}
        (fn [{:keys [db]} _]
          (reset! ran true)
          {:db (assoc db :folded true)}))
      (let [records (atom nil)
            ex      (atom nil)]
        (reset! records
                (record-always-on-errors
                  (fn []
                    (reset! ex (try (rf/dispatch-sync [:prod/uses-positive]
                                                      {:rf.cofx {:prod/positive -5}})
                                    nil
                                    (catch clojure.lang.ExceptionInfo e e))))))
        (is (some? @ex)
            "the dispatch THREW rather than folding the out-of-contract value")
        (is (= :rf.error/cofx-value-invalid (:rf.error/id (ex-data @ex)))
            "the throw is the specified hard error, not a generic failure")
        (is (= :prod/positive (:rf.cofx/id (ex-data @ex)))
            "the ex-data names the offending recordable fact")
        (is (false? @ran)
            "the handler never ran — the run halted before the fold")
        (is (nil? (:folded (db-of)))
            "app-db is untouched: nothing from the halted run committed")
        (let [err (record-of @records :rf.error/cofx-value-invalid)]
          (is (some? err)
              (str "the ALWAYS-ON error record fired. Red here under the gate "
                   "means recordable-coeffect validation is elided in "
                   "production, contradicting Spec 010:179 — an "
                   "operator-grade finding, not a test-spelling problem."))
          ;; The offending COFX id rides the throw's `ex-data`, asserted above.
          ;; It is deliberately absent from the always-on record: this category
          ;; passes the EVENT id as `:failing-id`, and `emit-error-both!` lifts
          ;; `:failing-id` / `:reason` onto the record only when they differ
          ;; from `:event-id`. So the record names the dispatch, not the fact.
          (is (= :prod/uses-positive (:event-id err))
              "the off-box record attributes the failure to the dispatch")
          (is (= :rf/default (:frame err))
              "and to the owning frame, so a shipper can route it"))))))

(deftest conforming-recordable-value-still-folds-in-every-posture
  (testing "rf2-bza6e — the negative control for surface 1. A CONFORMING
            supplied value passes the always-on check and folds normally, so
            the assertion above is about the schema and not about recordable
            coeffects being broken outright."
    (let [seen (atom nil)]
      (rf/reg-cofx :prod/positive
        {:recordable? true :schema [:int {:min 1}]}
        (fn [] 1))
      (rf/reg-event :prod/uses-positive
        {:rf.cofx/requires [:prod/positive]}
        (fn [{:keys [db prod/positive]} _]
          (reset! seen positive)
          {:db (assoc db :folded positive)}))
      (rf/dispatch-sync [:prod/uses-positive] {:rf.cofx {:prod/positive 7}})
      (is (= 7 @seen) "the conforming supplied value reached the handler")
      (is (= 7 (:folded (db-of))) "and committed to app-db"))))

(deftest generated-recordable-value-violating-its-schema-throws-in-every-posture
  (testing "rf2-bza6e / Spec 010:165 — the same hard error on the GENERATED
            arm. A generator-backed recordable fact runs at processing-start
            and its produced value is validated BEFORE the write-back into the
            durable `:rf.cofx` record, so a bad generator cannot poison the
            epoch ledger in production any more than in dev."
    (let [ran (atom false)]
      (rf/reg-cofx :prod/generated-positive
        {:recordable? true :schema [:int {:min 1}]}
        (fn [] -5))                                  ;; violates its own schema
      (rf/reg-event :prod/uses-generated
        {:rf.cofx/requires [:prod/generated-positive]}
        (fn [_ _] (reset! ran true) {}))
      (let [records (atom nil)
            ex      (atom nil)]
        (reset! records
                (record-always-on-errors
                  (fn []
                    (reset! ex (try (rf/dispatch-sync [:prod/uses-generated])
                                    nil
                                    (catch clojure.lang.ExceptionInfo e e))))))
        (is (some? @ex) "the generated mismatch threw")
        (is (= :rf.error/cofx-value-invalid (:rf.error/id (ex-data @ex))))
        (is (false? @ran) "the handler never ran")
        (is (some? (record-of @records :rf.error/cofx-value-invalid))
            "the always-on error record fired for the generated arm too")))))

;; ===========================================================================
;; Surface 2 — `:rf.schema/at-boundary` runs even when global validation is
;;             elided
;; ===========================================================================

(deftest at-boundary-rejects-a-non-conforming-event-in-every-posture
  (testing "rf2-bza6e / Spec 010:220 — a handler that REFERENCES
            `:rf.schema/at-boundary` does not run on an event that fails its
            `:schema`, in dev AND under `-Dre-frame.debug=false`. The
            enforcing code differs by posture (step-1 `validate-event!` in
            dev, the boundary interceptor in production); the observable — the
            handler is skipped and app-db is untouched — does not.

            Red here under the gate means an untrusted system-boundary payload
            reaches its handler unvalidated in production. That is a live
            defect of the rf2-9c2jf class, not a test-spelling problem."
    (let [calls (atom 0)]
      (rf/reg-event :prod/boundary
        {:schema       [:cat [:= :prod/boundary] :int]
         :interceptors [:rf.schema/at-boundary]}
        (fn [{:keys [db]} [_ n]]
          (swap! calls inc)
          {:db (assoc db :n n)}))
      (rf/dispatch-sync [:prod/boundary "not-an-int"])
      (is (zero? @calls)
          "the handler was SKIPPED on the non-conforming payload")
      (is (nil? (:n (db-of)))
          "and nothing from it committed to app-db"))))

(deftest at-boundary-passes-a-conforming-event-through-in-every-posture
  (testing "rf2-bza6e — the negative control for surface 2. The boundary
            interceptor is not simply breaking every dispatch: a CONFORMING
            payload flows through and the handler runs exactly once, in both
            postures."
    (let [calls (atom 0)]
      (rf/reg-event :prod/boundary
        {:schema       [:cat [:= :prod/boundary] :int]
         :interceptors [:rf.schema/at-boundary]}
        (fn [{:keys [db]} [_ n]]
          (swap! calls inc)
          {:db (assoc db :n n)}))
      (rf/dispatch-sync [:prod/boundary 42])
      (is (= 1 @calls) "the conforming payload reached the handler")
      (is (= 42 (:n (db-of))) "and committed"))))

;; ===========================================================================
;; The discriminator — gate lane ONLY
;; ===========================================================================

(deftest ^:prod-gate ordinary-event-schema-validation-really-is-elided-here
  (testing "rf2-bza6e — the control that stops every posture-independent
            assertion above from passing vacuously.

            This deftest runs ONLY in the `jvm-core-prod-gate` lane (the
            default `:test` alias excludes `^:prod-gate`), so it is a
            statement about the JVM it is running in. It asserts the NEGATIVE:
            a handler carrying the IDENTICAL `:schema` but NOT referencing
            `:rf.schema/at-boundary` ACCEPTS a non-conforming event here,
            because ordinary step-1 validation has been elided by the load-time
            gate exactly as Spec 010 §Validation order says it should be.

            That is what makes `at-boundary-rejects-a-non-conforming-event-in-
            every-posture` meaningful under the gate: the rejection cannot be
            step-1 doing the work, because step-1 demonstrably is not running
            in this JVM."
    (is (false? interop/debug-enabled?)
        "precondition: this JVM really is under the production gate")
    (let [calls (atom 0)]
      (rf/reg-event :prod/unguarded
        {:schema [:cat [:= :prod/unguarded] :int]}      ;; no at-boundary ref
        (fn [{:keys [db]} [_ n]]
          (swap! calls inc)
          {:db (assoc db :n n)}))
      (rf/dispatch-sync [:prod/unguarded "not-an-int"])
      (is (= 1 @calls)
          (str "under the gate an unguarded handler runs on a non-conforming "
               "event — dev-time step-1 validation is elided. If this is RED, "
               "step-1 validation is still running in this JVM and the "
               "at-boundary assertions above are not proving what they claim."))
      (is (= "not-an-int" (:n (db-of)))
          "the unvalidated payload committed, as the elision contract implies"))))
