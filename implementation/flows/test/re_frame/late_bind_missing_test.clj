(ns re-frame.late-bind-missing-test
  "Per rf2-5b6x — assert the documented missing-artefact error contract for
  the flows artefact's `re-frame.core` re-exports.

  Each per-feature split (schemas / machines / routing / flows / http /
  ssr) raises a documented `:rf.error/<artefact>-artefact-missing`
  ex-info when a consumer calls a re-exported surface but the artefact
  is absent from the classpath. The contract was previously only
  documented in prose; this test pins the runtime behaviour against
  regression.

  Strategy: the flows artefact IS on the classpath here (the test ns
  requires `re-frame.flows`, which fires the late-bind hook
  registrations at ns-load). To simulate the absent-artefact state we
  flip the relevant late-bind hook to nil for the duration of the
  assertion, then restore it in `finally`. Identical mechanism as the
  test would use on CLJS.

  Per Spec 002 §The late-bind seam, rf2-tfw3 (flows split), and the
  prose at the call sites in `re-frame.core`."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            ;; Loading flows registers its late-bind hooks. The
            ;; `with-hook-as-nil` helper below re-establishes the absent
            ;; state by flipping the hook value at runtime; restoration
            ;; in `finally` keeps cross-test isolation intact.
            [re-frame.flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(defn- with-hook-as-nil
  "Run `f` with the named late-bind hook set to nil. Restores the
  original value after `f` returns or throws."
  [hook-key f]
  (let [original (late-bind/get-fn hook-key)]
    (try
      (late-bind/set-fn! hook-key nil)
      (f)
      (finally
        (late-bind/set-fn! hook-key original)))))

;; rf2-wad2fl (front-porch shrink): `clear-flow` was demoted off the
;; `re-frame.core` façade — it is reached through `re-frame.flows/clear-flow`
;; now, so the façade artefact-missing contract no longer applies to it. The
;; `reg-flow` registration MACRO remains on the façade (source-coord capture);
;; its missing-artefact contract is tested below, alongside the
;; framework-internal hook no-op contracts.

(deftest reg-flow-raises-when-flows-artefact-missing
  (testing "rf/reg-flow (macro) raises :rf.error/flows-artefact-missing when the :flows/reg-flow hook is nil"
    ;; The macro expands to a runtime late-bind lookup, so flipping the
    ;; hook at runtime is enough — we don't need to touch the test's
    ;; compile-time classpath.
    (with-hook-as-nil :flows/reg-flow
      (fn []
        (let [thrown (try (rf/reg-flow {:id     :late-bind-missing/probe
                                        :inputs []
                                        :derive (fn [] 0)
                                        :output-path   [:probe]})
                          nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (is (some? thrown)
              "reg-flow throws when the flows artefact is absent")
          ;; rf2-vvixub — the message is the human :reason sentence + the
          ;; trailing [:rf.error/<id>] greppability token (Spec 009 §The
          ;; thrown-error shape). Assert the token substring + the canonical
          ;; :rf.error/id discriminator, NOT exact keyword-equality.
          (is (re-find #"\[:rf\.error/flows-artefact-missing\]" (.getMessage thrown))
              "the message carries the [:rf.error/flows-artefact-missing] token")
          (let [data (ex-data thrown)]
            (is (= :rf.error/flows-artefact-missing (:rf.error/id data))
                "ex-data carries the canonical :rf.error/id discriminator")
            ;; Per rf2-hoiu the throw lives in `re-frame.core-flows/reg-flow`
            ;; — the sibling-namespace fn-form delegate the macro routes
            ;; through. Per rf2-j8icl the `:where` symbol is namespace-
            ;; qualified to the user-facing surface (`rf/reg-flow`).
            (is (= 'rf/reg-flow (:where data))
                "ex-data carries :where = 'rf/reg-flow")
            (is (= :no-recovery (:recovery data))
                "ex-data carries :recovery = :no-recovery")))))))

;; ---------------------------------------------------------------------------
;; Framework-internal hooks (rf2-g9t87)
;;
;; The four hooks below are framework-internal: their consumers are
;; `re-frame.router/flows-after-interceptor` (outermost `:after` flow
;; transform), `re-frame.frame/destroy-frame!` (per-frame teardown
;; cascade), and `re-frame.test-support/make-reset-runtime-fixture`
;; (test-fixture reset bracket). None of
;; these surface to user-facing `rf/...` fns, so the absent-artefact
;; semantics is "graceful no-op", not "raise a typed ex-info".
;;
;; The contract being pinned: each consumer reads the hook through
;; `late-bind/get-fn` and short-circuits when the lookup returns nil.
;; A regression that called the nil hook unconditionally would surface
;; here as a NullPointerException; the assertion shape `(is (nil? ...))`
;; / `(is (do ... :ok))` confirms the no-op path.
;; ---------------------------------------------------------------------------

(deftest run-flows-on-db-no-ops-when-flows-artefact-missing
  (testing "router's flows-after-interceptor no-ops without throwing when :flows/run-flows-on-db hook is nil"
    ;; Consumer is `re-frame.router/flows-after-interceptor` (private;
    ;; reach via the user-visible drain). Drive a dispatch through the
    ;; router — the absent-flows-artefact branch is the steady-state for
    ;; apps that never registered any flows. With the hook nil, the
    ;; outermost flow-transform `:after` collapses to a single nil-check
    ;; no-op and the drain completes.
    (with-hook-as-nil :flows/run-flows-on-db
      (fn []
        (rf/init! plain-atom/adapter)
        ;; EP-0002 (rf2-5q7um6): `init!` no longer creates `:rf/default`,
        ;; and a bare `dispatch-sync` under no established scope raises
        ;; `:rf.error/no-frame-context`. Register `:rf/default` and pin it
        ;; as the established scope so this absent-flows-artefact drain test
        ;; carries a frame stamp (the carried invariant).
        (frame/ensure-default-frame!)
        (binding [frame/*current-frame* :rf/default]
          (rf/reg-event :flows-absent/init (fn [{:keys [db]} _] {:db {:probe :ok}}))
          (is (do (rf/dispatch-sync [:flows-absent/init]) :ok)
              "dispatch completes without throwing when the run-flows! hook is absent")
          (is (= {:probe :ok} (rf/app-db-value :rf/default))
              ":db commit lands even though the outermost-`:after` flow walker is a no-op"))))))

(deftest reset-last-inputs!-no-ops-when-flows-artefact-missing
  (testing "test-support's reset-runtime fixture no-ops when :flows/reset-last-inputs! hook is nil"
    ;; Consumer is `re-frame.test-support`'s reset-hook-table (row for
    ;; `:flows/reset-last-inputs!`). The driver `run-reset-hooks!` walks
    ;; the table and short-circuits rows whose hook is unregistered.
    ;; With the hook nil, the fixture bracket fires its full cascade
    ;; (snap/restore registrar, dispose adapter, reinstall, run test-fn)
    ;; without touching the absent-flows machinery.
    (with-hook-as-nil :flows/reset-last-inputs!
      (fn []
        (let [ran? (atom false)
              fix  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})]
          (fix (fn [] (reset! ran? true)))
          (is @ran? "fixture invoked the test-fn without throwing on the absent hook"))))))

(deftest reset-flows!-no-ops-when-flows-artefact-missing
  (testing "test-support's reset-runtime fixture no-ops when :flows/reset-flows! hook is nil"
    ;; Consumer is `re-frame.test-support`'s reset-hook-table (row for
    ;; `:flows/reset-flows!`) plus the `finally` branch in
    ;; `make-reset-runtime-fixture` that calls the same hook. Both paths
    ;; reach the hook through `late-bind/get-fn` and short-circuit on
    ;; nil — proven by driving a full fixture cycle with the hook
    ;; cleared.
    (with-hook-as-nil :flows/reset-flows!
      (fn []
        (let [ran? (atom false)
              fix  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})]
          (fix (fn [] (reset! ran? true)))
          (is @ran? "fixture invoked the test-fn without throwing on the absent hook"))))))

(deftest teardown-on-frame-destroy!-no-ops-when-flows-artefact-missing
  (testing "frame/destroy-frame! completes without throwing when :flows/teardown-on-frame-destroy! hook is nil"
    ;; Consumer is `frame/destroy-frame!`'s `safe-call-hook!` invocation
    ;; for `:flows/teardown-on-frame-destroy!`. The hook is reached via
    ;; the same late-bind lookup; on nil, the safe-call wrapper
    ;; short-circuits.
    (with-hook-as-nil :flows/teardown-on-frame-destroy!
      (fn []
        (rf/init! plain-atom/adapter)
        (rf/reg-frame :late-bind-missing/scratch {:doc "scratch frame for teardown probe"})
        (is (some? (get @frame/frames :late-bind-missing/scratch))
            "frame registered")
        (is (do (frame/destroy-frame! :late-bind-missing/scratch) :ok)
            "destroy-frame! completes without throwing when the flows-teardown hook is absent")
        (is (nil? (get @frame/frames :late-bind-missing/scratch))
            "frame was destroyed despite the absent teardown hook")))))
