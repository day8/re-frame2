(ns re-frame.ssr-compatibility-checks-test
  "Per rf2-69ad2 / Spec 011 §The :rf/hydrate event: the :rf.ssr/check-version
  and :rf.ssr/check-schema-digest fxs are the hydration-side compatibility
  checks the :rf/hydrate handler dispatches after replacing the client
  app-db. Each fx is best-effort — a mismatch emits a structured warning
  trace; the hydration proceeds (degraded-but-running, never crash).

  Coverage (2 per fx, matching the bead's acceptance criteria):

    - matching values → silent (no mismatch trace fires)
    - mismatching values → :rf.ssr/version-mismatch / :rf.ssr/schema-digest-
      mismatch trace fires with :expected + :actual + :recovery shape.

  ## Posture split (rf2-lwtlk)

  Read this one carefully, because the honest answer here is not the
  convenient one. Both fxs are BEST-EFFORT: they change no state, return no
  value a handler can see, and stop nothing. Their entire observable output
  IS the trace. So under `-Dre-frame.debug=false`, where every emit site is
  elided, the mismatch deftests fail and — worse — the MATCHING deftests
  would pass VACUOUSLY, since `(empty? (traces-of …))` is satisfied by a ring
  that is empty for every input. Guarding all of it and deleting the roster
  line would have reported GREEN for a namespace that proved nothing: the
  exact false green this lane exists to close.

  Every trace assertion, positive and negative alike, is therefore kept
  VERBATIM inside a `(when interop/debug-enabled? …)` arm marked
  `rf2-lwtlk`. What earns the namespace its place in
  `scripts/test-ssr-prod-gate.sh` is NEW, production-real coverage of the
  two properties the docstring above claims and that nothing asserted in
  either posture before:

    - the two fx ids are REGISTERED, carrying the `:platforms #{:client}`
      gate the runtime actually dispatches on — `registrar/lookup`, not a
      trace;
    - `best-effort` means what it says. A MISMATCHING check does not throw,
      does not abort the drain, and does not stop the effects queued after
      it. `compatibility-checks-are-best-effort-and-never-halt-the-drain`
      pins the whole of degraded-but-running-never-crash — the only part of
      this surface a production server ever experiences."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

;; Shared reset fixture lives in `re-frame.ssr.test-fixture` (rf2-i3qc0).
(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- traces-of [traces op]
  (filterv #(= op (:operation %)) traces))

;; ===========================================================================
;; PRODUCTION-REAL surface (rf2-lwtlk)
;; ===========================================================================
;;
;; Everything below this block observes the two fxs through the DEV trace
;; bus, which is the whole of their output and none of which survives
;; `-Dre-frame.debug=false`.  These two deftests are what this namespace
;; contributes to `scripts/test-ssr-prod-gate.sh`: the registrations the
;; runtime dispatches on, and the best-effort contract the ns docstring
;; promises but nothing previously asserted in either posture.

(deftest compatibility-check-fxs-are-registered-client-only
  (testing "rf2-lwtlk — both compatibility-check fx ids resolve in the fx
            registry with the :platforms #{:client} gate. This is the
            RETAINED half of each registration (`:doc` is stripped in
            production per Spec 001 §Production elision contract) and it is
            what decides whether the fx runs at all."
    (doseq [id [:rf.ssr/check-version :rf.ssr/check-schema-digest]]
      (let [meta (rf.registrar/lookup :fx id)]
        (is (some? meta) (str id " resolves in the :fx registry"))
        (is (fn? (:handler-fn meta)) (str id " carries a :handler-fn"))
        (is (= #{:client} (:platforms meta))
            (str id " is client-only per Spec 011 §The :rf/hydrate event"))))))

(deftest compatibility-checks-are-best-effort-and-never-halt-the-drain
  (testing "rf2-lwtlk — 'best-effort' is a PRODUCTION contract, not a trace:
            a MISMATCHING check must not throw and must not stop the effects
            queued behind it, so a version- or digest-skewed client hydrates
            degraded-but-running. Under `-Dre-frame.debug=false` nothing is
            emitted, which is exactly when this property matters most and is
            the only way to observe it."
    (let [ran (atom [])]
      ;; A marker fx queued AFTER both checks. If a mismatching check threw,
      ;; or aborted the drain, this never runs.
      (rf.fx/reg-fx ::after-checks
                 {:platforms #{:client}}
                 (fn [_ v] (swap! ran conj v)))
      (rf/reg-event ::probe-best-effort
        {:platforms #{:client}}
        (fn [{:keys [db]} _]
          {:db (assoc db :probe/handler-ran true)
           :fx [[:rf.ssr/check-version       {:expected 1 :actual 2}]
                [:rf.ssr/check-schema-digest {:expected "sha256:aaaa"
                                              :actual   "sha256:bbbb"}]
                [::after-checks :marker]]}))

      (let [f (rf.frame/make-anon-frame-record! {:platform :client})]
        (is (nil? (rf/dispatch-sync [::probe-best-effort] {:frame f}))
            "a double mismatch does not throw out of dispatch-sync")
        (is (true? (:probe/handler-ran (rf.frame/frame-app-db-value f)))
            "the handler's :db write committed alongside the failing checks")
        (is (= [:marker] @ran)
            "the effect queued AFTER both mismatching checks still ran —
             the drain was never halted")))))

;; ===========================================================================
;; :rf.ssr/check-version
;; ===========================================================================
;;
;; Per Spec 011 §The :rf/hydrate event: the fx receives a scalar (the
;; server's version) per the reference handler, OR a map {:expected ... :actual ...}
;; for explicit comparisons (per the rf2-69ad2 fx-input-shape clarification).
;; Matching → silent; mismatching → :rf.ssr/version-mismatch warning trace.

(deftest check-version-matching-is-silent
  (testing "matching expected + actual → no :rf.ssr/version-mismatch trace"
    (rf/reg-event ::probe-check-version-match
      {:platforms #{:client}}
      (fn [_ _]
        ;; rf2-g00l2t: :rf/version is canonically an INTEGER pattern-
        ;; protocol version (Spec-Schemas §:rf/hydration-payload), so the
        ;; check-version probes compare integers, not semver strings.
        {:fx [[:rf.ssr/check-version {:expected 1 :actual 1}]]}))

    (let [f (rf.frame/make-anon-frame-record! {:platform :client})]
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [::probe-check-version-match] {:frame f})
        ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring). BOTH of
        ;; these are negatives over the trace ring, and both pass vacuously
        ;; under the gate, where the ring is empty whatever the fx did.
        (when rf.interop/debug-enabled?
          (is (empty? (traces-of @traces :rf.ssr/version-mismatch))
              "matching version → no mismatch trace")
          (is (empty? (traces-of @traces :rf.ssr/compatibility-check-skipped))
              "both sides supplied → no skipped trace either"))))))

(deftest check-version-mismatch-emits-trace
  (testing "differing expected + actual → :rf.ssr/version-mismatch warning trace"
    (rf/reg-event ::probe-check-version-mismatch
      {:platforms #{:client}}
      (fn [_ _]
        {:fx [[:rf.ssr/check-version {:expected 1 :actual 2}]]}))

    (let [f (rf.frame/make-anon-frame-record! {:platform :client})]
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [::probe-check-version-mismatch] {:frame f})
        ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring). The trace
        ;; is this fx's ONLY output; its production behaviour — do not throw,
        ;; do not halt the drain — is pinned by
        ;; `compatibility-checks-are-best-effort-and-never-halt-the-drain`.
        (when rf.interop/debug-enabled?
          (let [hits (traces-of @traces :rf.ssr/version-mismatch)]
            (is (= 1 (count hits))
                (str "expected one :rf.ssr/version-mismatch trace; saw: "
                     (pr-str (mapv :operation @traces))))
            (when (seq hits)
              (let [ev (first hits)]
                (is (= :warning              (:op-type ev)))
                (is (= 1                     (-> ev :tags :expected)))
                (is (= 2                     (-> ev :tags :actual)))
                (is (= :warned-and-applied   (:recovery ev))
                    ":recovery rides at top-level per Spec 009")))))))))

;; ===========================================================================
;; :rf.ssr/check-schema-digest
;; ===========================================================================
;;
;; Same shape as version-check. Matching → silent; mismatch → warning trace.
;; Scalar form is what the reference :rf/hydrate handler dispatches (the
;; payload's :rf/schema-digest); the fx looks up the client-side digest
;; via the `:schemas/app-schemas-digest` late-bind hook. When the schemas
;; artefact isn't on the classpath the hook is absent and the fx emits
;; :rf.ssr/compatibility-check-skipped (covered by the scalar-form path
;; running under the real schemas artefact below).

(deftest check-schema-digest-matching-is-silent
  (testing "matching expected + actual → no :rf.ssr/schema-digest-mismatch trace"
    (rf/reg-event ::probe-check-digest-match
      {:platforms #{:client}}
      (fn [_ _]
        {:fx [[:rf.ssr/check-schema-digest
               {:expected "sha256:deadbeefcafef00d"
                :actual   "sha256:deadbeefcafef00d"}]]}))

    (let [f (rf.frame/make-anon-frame-record! {:platform :client})]
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [::probe-check-digest-match] {:frame f})
        ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring). Vacuous
        ;; under the gate — both are negatives over an empty ring.
        (when rf.interop/debug-enabled?
          (is (empty? (traces-of @traces :rf.ssr/schema-digest-mismatch))
              "matching digest → no mismatch trace")
          (is (empty? (traces-of @traces :rf.ssr/compatibility-check-skipped))
              "both sides supplied → no skipped trace either"))))))

(deftest check-schema-digest-mismatch-emits-trace
  (testing "differing expected + actual → :rf.ssr/schema-digest-mismatch warning trace"
    (rf/reg-event ::probe-check-digest-mismatch
      {:platforms #{:client}}
      (fn [_ _]
        {:fx [[:rf.ssr/check-schema-digest
               {:expected "sha256:deadbeefcafef00d"
                :actual   "sha256:0000000000000000"}]]}))

    (let [f (rf.frame/make-anon-frame-record! {:platform :client})]
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [::probe-check-digest-mismatch] {:frame f})
        ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring).
        (when rf.interop/debug-enabled?
          (let [hits (traces-of @traces :rf.ssr/schema-digest-mismatch)]
            (is (= 1 (count hits))
                (str "expected one :rf.ssr/schema-digest-mismatch trace; saw: "
                     (pr-str (mapv :operation @traces))))
            (when (seq hits)
              (let [ev (first hits)]
                (is (= :warning                              (:op-type ev)))
                (is (= "sha256:deadbeefcafef00d"             (-> ev :tags :expected)))
                (is (= "sha256:0000000000000000"             (-> ev :tags :actual)))
                (is (= :warned-and-applied                   (:recovery ev)))))))))))

;; ===========================================================================
;; SCALAR-form paths — rf2-ooj41 / rf2-qfb1i
;; ===========================================================================
;;
;; Per rf2-ooj41 (audit ssr coverage + robustness): the explicit-map form is
;; covered above. The reference :rf/hydrate handler dispatches the SCALAR
;; form `[:rf.ssr/check-version <server-value>]`; the fx then resolves the
;; client-side "actual". For version (rf2-qfb1i) that is the SSR artefact's
;; compiled-in `payload-policy/pattern-protocol-version` constant — it
;; ALWAYS resolves (no host hook), so a scalar equal to the constant compares
;; silently and a scalar that differs emits `:rf.ssr/version-mismatch`. For
;; schema-digest the client value still comes from the
;; `:schemas/app-schemas-digest` late-bind hook, which emits
;; `:rf.ssr/compatibility-check-skipped` when the schemas artefact is absent
;; (covered below). Pin both paths so a regression that silently drops a
;; trace is caught.

(deftest check-version-scalar-matches-ssr-constant
  (testing "scalar form equal to the SSR-owned pattern-protocol constant → silent match (no skipped, no mismatch)"
    ;; rf2-qfb1i: the version-side scalar resolves the client-side "actual"
    ;; from the SSR artefact's compiled-in constant, not a host hook. A
    ;; scalar carrying the same value the server stamped (= the constant)
    ;; compares equal and is silent — the former no-hook "skipped" baseline
    ;; is gone (the check is real by default).
    (rf/reg-event ::probe-check-version-scalar-matches
      {:platforms #{:client}}
      (fn [_ _]
        {:fx [[:rf.ssr/check-version rf.ssr.payload-policy/pattern-protocol-version]]}))

    (let [f (rf.frame/make-anon-frame-record! {:platform :client})]
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [::probe-check-version-scalar-matches] {:frame f})
        ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring). Vacuous
        ;; under the gate — both are negatives over an empty ring.
        (when rf.interop/debug-enabled?
          (is (empty? (traces-of @traces :rf.ssr/compatibility-check-skipped))
              "version scalar resolves via the SSR constant → never skipped")
          (is (empty? (traces-of @traces :rf.ssr/version-mismatch))
              "scalar == the SSR constant → silent match"))))))

(deftest check-version-scalar-differs-from-ssr-constant-emits-mismatch
  (testing "scalar form differing from the SSR-owned constant → :rf.ssr/version-mismatch"
    ;; rf2-qfb1i: no host hook installed — the client-side "actual" IS the
    ;; SSR artefact's compiled-in constant. A scalar (server value) that
    ;; differs from it is genuine skew and emits :rf.ssr/version-mismatch,
    ;; proving the SSR constant is the "actual" the comparison runs against.
    (let [server-version (inc rf.ssr.payload-policy/pattern-protocol-version)]
      (rf/reg-event ::probe-check-version-scalar-differs
        {:platforms #{:client}}
        (fn [_ _]
          {:fx [[:rf.ssr/check-version server-version]]}))

      (let [f (rf.frame/make-anon-frame-record! {:platform :client})]
        (with-trace-recorder! [traces]
          (rf/dispatch-sync [::probe-check-version-scalar-differs] {:frame f})
          ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring).
          (when rf.interop/debug-enabled?
            (let [hits (traces-of @traces :rf.ssr/version-mismatch)]
              (is (empty? (traces-of @traces :rf.ssr/compatibility-check-skipped))
                  "version scalar resolves via the SSR constant → never skipped")
              (is (= 1 (count hits))
                  (str "expected one :rf.ssr/version-mismatch trace; saw: "
                       (pr-str (mapv :operation @traces))))
              (when (seq hits)
                (let [ev (first hits)]
                  (is (= server-version (-> ev :tags :expected))
                      "scalar arg is :expected (server side)")
                  (is (= rf.ssr.payload-policy/pattern-protocol-version (-> ev :tags :actual))
                      ":actual sourced from the SSR-owned pattern-protocol constant"))))))))))

(deftest check-schema-digest-scalar-with-no-hook-emits-skipped
  (testing "scalar form + absent :schemas/app-schemas-digest hook → skipped"
    ;; Test deps pull `re-frame.schemas` onto the classpath so its ns-load
    ;; registers `:schemas/app-schemas-digest`; explicitly clear the hook
    ;; for this test so we can pin the missing-hook path. Restore on exit.
    (let [prior-hook (rf.late-bind/get-fn :schemas/app-schemas-digest)]
      (swap! rf.late-bind/hooks dissoc :schemas/app-schemas-digest)
      (try
        (rf/reg-event ::probe-check-digest-scalar-no-hook
          {:platforms #{:client}}
          (fn [_ _]
            {:fx [[:rf.ssr/check-schema-digest "sha256:deadbeefcafef00d"]]}))

        (let [f (rf.frame/make-anon-frame-record! {:platform :client})]
          (with-trace-recorder! [traces]
            (rf/dispatch-sync [::probe-check-digest-scalar-no-hook] {:frame f})
            ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring).
            (when rf.interop/debug-enabled?
              (let [hits (traces-of @traces :rf.ssr/compatibility-check-skipped)]
                (is (= 1 (count hits)))
                (when (seq hits)
                  (let [ev (first hits)]
                    (is (= :rf.ssr/check-schema-digest (-> ev :tags :check)))
                    (is (= "sha256:deadbeefcafef00d"   (-> ev :tags :expected)))
                    (is (= :skipped                    (:recovery ev)))))))))
        (finally
          (when prior-hook
            (rf.late-bind/set-fn! :schemas/app-schemas-digest prior-hook)))))))
