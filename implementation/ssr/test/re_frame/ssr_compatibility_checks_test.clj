(ns re-frame.ssr-compatibility-checks-test
  "Per Spec 011 §The :rf/hydrate event: the :rf.ssr/check-version
  and :rf.ssr/check-schema-digest fxs are the hydration-side compatibility
  checks the :rf/hydrate handler dispatches after replacing the client
  app-db. Each fx is best-effort — a mismatch emits a structured warning
  trace; the hydration proceeds (degraded-but-running, never crash).

  Coverage:

    - matching values → silent (no mismatch trace fires)
    - mismatching values → :rf.ssr/version-mismatch / :rf.ssr/schema-digest-
      mismatch trace fires with :expected + :actual + :recovery shape.

  ## Posture split

  Read this one carefully, because the honest answer here is not the
  convenient one. Both fxs are BEST-EFFORT: they change no state, return no
  value a handler can see, and stop nothing. Their entire observable output
  IS the trace. So under `-Dre-frame.debug=false`, where every emit site is
  elided, the mismatch deftests would fail and — worse — the MATCHING
  deftests would pass VACUOUSLY, since `(empty? (traces-of …))` is satisfied
  by a ring that is empty for every input. Guarding all of it and dropping
  the namespace from the roster would report GREEN for a namespace that
  proves nothing: the exact false green this lane exists to close.

  Every trace assertion, positive and negative alike, therefore sits inside
  a `(when interop/debug-enabled? …)` arm. What earns the namespace its
  place in `scripts/test-ssr-prod-gate.sh` is production-real coverage of
  the two properties the docstring above claims:

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

;; Shared reset fixture lives in `re-frame.ssr.test-fixture`.
(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- traces-of [traces op]
  (filterv #(= op (:operation %)) traces))

;; ===========================================================================
;; PRODUCTION-REAL surface
;; ===========================================================================
;;
;; Everything below this block observes the two fxs through the DEV trace
;; bus, which is the whole of their output and none of which survives
;; `-Dre-frame.debug=false`.  These two deftests are what this namespace
;; contributes to `scripts/test-ssr-prod-gate.sh`: the registrations the
;; runtime dispatches on, and the best-effort contract the ns docstring
;; promises.

(deftest compatibility-check-fxs-are-registered-client-only
  (testing "both compatibility-check fx ids resolve in the fx
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
  (testing "'best-effort' is a PRODUCTION contract, not a trace:
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
;; :rf.ssr/check-version and :rf.ssr/check-schema-digest
;; ===========================================================================
;;
;; Per Spec 011 §The :rf/hydrate event: each fx receives a scalar (the
;; server's value) per the reference handler, OR a map {:expected ... :actual ...}
;; for explicit comparisons.
;; Matching → silent; mismatching → a :rf.ssr/version-mismatch /
;; :rf.ssr/schema-digest-mismatch warning trace.

(deftest matching-checks-are-silent
  (testing "matching expected + actual → neither a mismatch nor a skipped trace"
    (doseq [[label fx mismatch-op]
            [;; :rf/version is canonically an INTEGER pattern-protocol version
             ;; (Spec-Schemas §:rf/hydration-payload), so the check-version
             ;; probes compare integers, not semver strings.
             ["check-version, map form"
              [:rf.ssr/check-version {:expected 1 :actual 1}]
              :rf.ssr/version-mismatch]
             ["check-schema-digest, map form"
              [:rf.ssr/check-schema-digest {:expected "sha256:deadbeefcafef00d"
                                            :actual   "sha256:deadbeefcafef00d"}]
              :rf.ssr/schema-digest-mismatch]
             ;; The version-side scalar resolves its client-side "actual" from
             ;; the SSR artefact's compiled-in constant, not a host hook, so a
             ;; scalar carrying the value the server stamped compares equal and
             ;; there is no no-hook "skipped" path.
             ["check-version, scalar equal to the SSR constant"
              [:rf.ssr/check-version rf.ssr.payload-policy/pattern-protocol-version]
              :rf.ssr/version-mismatch]]]
      (rf/reg-event ::probe-check-match
        {:platforms #{:client}}
        (fn [_ _] {:fx [fx]}))
      (let [f (rf.frame/make-anon-frame-record! {:platform :client})]
        (with-trace-recorder! [traces]
          (rf/dispatch-sync [::probe-check-match] {:frame f})
          ;; Dev-instrumentation arm (see ns docstring). BOTH of
          ;; these are negatives over the trace ring, and both pass vacuously
          ;; under the gate, where the ring is empty whatever the fx did.
          (when rf.interop/debug-enabled?
            (is (empty? (traces-of @traces mismatch-op))
                (str label " — matching values → no mismatch trace"))
            (is (empty? (traces-of @traces :rf.ssr/compatibility-check-skipped))
                (str label " — both sides resolved → no skipped trace either"))))))))

(deftest mismatching-map-checks-emit-a-warning-trace
  (testing "differing expected + actual → one warning trace carrying both"
    (doseq [[label fx op expected actual]
            [["check-version"
              [:rf.ssr/check-version {:expected 1 :actual 2}]
              :rf.ssr/version-mismatch 1 2]
             ["check-schema-digest"
              [:rf.ssr/check-schema-digest {:expected "sha256:deadbeefcafef00d"
                                            :actual   "sha256:0000000000000000"}]
              :rf.ssr/schema-digest-mismatch
              "sha256:deadbeefcafef00d" "sha256:0000000000000000"]]]
      (rf/reg-event ::probe-check-mismatch
        {:platforms #{:client}}
        (fn [_ _] {:fx [fx]}))
      (let [f (rf.frame/make-anon-frame-record! {:platform :client})]
        (with-trace-recorder! [traces]
          (rf/dispatch-sync [::probe-check-mismatch] {:frame f})
          ;; Dev-instrumentation arm (see ns docstring). The trace
          ;; is this fx's ONLY output; its production behaviour — do not throw,
          ;; do not halt the drain — is pinned by
          ;; `compatibility-checks-are-best-effort-and-never-halt-the-drain`.
          (when rf.interop/debug-enabled?
            (let [hits (traces-of @traces op)]
              (is (= 1 (count hits))
                  (str label " — expected one " op " trace; saw: "
                       (pr-str (mapv :operation @traces))))
              (when (seq hits)
                (let [ev (first hits)]
                  (is (= :warning (:op-type ev)) label)
                  (is (= expected (-> ev :tags :expected)) label)
                  (is (= actual (-> ev :tags :actual)) label)
                  (is (= :warned-and-applied (:recovery ev))
                      (str label " — :recovery rides at top-level per Spec 009")))))))))))

;; ===========================================================================
;; SCALAR-form paths
;; ===========================================================================
;;
;; The explicit-map form is covered above. The reference :rf/hydrate
;; handler dispatches the SCALAR form
;; `[:rf.ssr/check-version <server-value>]`; the fx then resolves the
;; client-side "actual". For version that is the SSR artefact's
;; compiled-in `payload-policy/pattern-protocol-version` constant — it
;; ALWAYS resolves (no host hook), so a scalar equal to the constant compares
;; silently and a scalar that differs emits `:rf.ssr/version-mismatch`. For
;; schema-digest the client value comes from the
;; `:schemas/app-schemas-digest` late-bind hook, which emits
;; `:rf.ssr/compatibility-check-skipped` when the schemas artefact is absent
;; (covered below). Pin both paths so a regression that silently drops a
;; trace is caught.

(deftest check-version-scalar-differs-from-ssr-constant-emits-mismatch
  (testing "scalar form differing from the SSR-owned constant → :rf.ssr/version-mismatch"
    ;; There is no host hook — the client-side "actual" IS the
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
          ;; Dev-instrumentation arm (see ns docstring).
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
            ;; Dev-instrumentation arm (see ns docstring).
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
