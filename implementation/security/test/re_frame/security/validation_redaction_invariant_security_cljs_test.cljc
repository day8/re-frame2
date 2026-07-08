(ns re-frame.security.validation-redaction-invariant-security-cljs-test
  "Class-invariant security tier — uniform `:sensitive?` redaction across
  EVERY framework-side validation-failure emission site (rf2-o69h5).

  ## The class

  rf2-a5kzs#1 was a real leak: `validate-event!` ignored a per-slot
  `:sensitive?` inside an event schema, so a failing login payload shipped
  the password verbatim through `:received` / `:value` / `:explain` to every
  trace listener (and onward to off-box monitors and the AI/MCP boundary).
  It was the SAME class as the earlier schemas `:set` / `:and` / `:or` /
  `:multi` collection-nested leak — and the earlier fix did NOT generalise:
  several OTHER validation-failure emit sites built their own failure tags
  and emitted them WITHOUT consulting the schema's `:sensitive?` declaration:

    - `:where :machine-data`  (re-frame.machines.data-validation)
    - `:where :sub-override`  (re-frame.subs — a `:sub-overrides` pin that
                               fails the sub's own output schema; bypasses
                               `validate-sub!`)
    - `:where :flow-output`   (re-frame.flows — `:explain` left verbatim;
                               `:value` was path-elided only)

  ## The invariant (the deliverable)

  EVERY `:rf.error/schema-validation-failure` the framework emits MUST route
  its value-bearing slots through the ONE shared schema-aware redactor
  (`re-frame.schemas/redact-validation-tags`, reached off-namespace via the
  `:schemas/redact-validation-tags` late-bind hook). When the failing slot's
  schema declares ANY `:sensitive?` slot, the value-bearing slots
  (`:value` / `:received` / `:explain` / `:explain-humanized` / `:rf.fx/args`
  / `:rf.sub/query-v`) MUST be scrubbed to `:rf/redacted` and the trace
  stamped `:sensitive? true`. Per Spec 010 §`:sensitive?` — privacy in
  schema-validation error traces (the §Uniform across every validation
  `:where` surface invariant).

  This namespace fails RED if ANY validation kind ships an un-redacted
  value-bearing slot for a `:sensitive?`-marked surface. A new emit site
  that forgets the seam re-opens the class and trips this gate.

  ## Why property-style + a per-kind corpus

  A unique unguessable SENTINEL is planted at a `:sensitive?` slot of a
  schema, a type failure is forced there, and the test asserts the sentinel
  NEVER appears anywhere in the emitted trace (deep-walked) — across BOTH a
  fuzzer (arbitrary collection/map nestings of the sensitive slot, the
  rf2-g5auo shape space) AND a fixed per-kind corpus that drives each named
  validation surface. One escaped surface = one leak.

  ## Net property (verify-by-revert)

  Reverting any one emit site to its pre-rf2-o69h5 shape (emit the raw
  `:value` / `:received` / `:explain` without routing through
  `redact-validation-tags`) makes that kind's corpus assertion go RED — the
  sentinel surfaces unredacted. Confirmed by temporary local revert +
  restore (see PR Quality gates)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            ;; Publishes the Malli late-bind validate/explain hooks; without
            ;; it the default validator soft-passes and no failure fires.
            [re-frame.schemas.malli]
            [re-frame.schemas :as schemas]
            [re-frame.machines.data-validation :as machine-data]
            ;; rf2-g1a4ho — the two PRODUCTION-PATH drivers below register a
            ;; flow / a sub-override and drive the real drain + subscribe
            ;; cascades end-to-end. Requiring `re-frame.flows` publishes the
            ;; `:flows/run-flows-on-db` late-bind hook so the router's
            ;; always-present flows-after-interceptor actually runs the flow
            ;; transform on `dispatch-sync`; `plain-atom` is the substrate the
            ;; drain / subscribe paths run against.
            [re-frame.flows]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.security.gen :as gen]))

;; Reset per-test so app-schema registrations don't bleed across cases.
;;
;; EP-0002 (rf2-gjq3ow): `reg-app-schema` + `validate-app-schema!` are
;; context-required frame-local (no `:rf/default` floor). The adapter-less
;; reset fixture establishes no ambient scope, so the outer fixture below
;; pins `:rf/default` as the carried scope for the test body — the
;; carried-invariant equivalent of `(with-frame :rf/default …)`. Without it
;; `app-db-validation-redacts-sensitive` raises `:rf.error/no-frame-context`
;; at `reg-app-schema`. Binding the dynamic var is sufficient: the scope
;; reader reads the var and `reg-app-schema` keys only the schemas
;; side-table — no registered frame / container needed (so no adapter, and
;; not `ensure-default-frame!`, which would require one).
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:clear-app-schemas? true})
  (fn [test-fn]
    (binding [frame/*current-frame* :rf/default]
      (test-fn))))

;; ---------------------------------------------------------------------------
;; Sentinel — a value that must NEVER appear unredacted in any trace slot.
;; ---------------------------------------------------------------------------

(def ^:private sentinel "S3CR3T-rf2-o69h5-VALIDATION-DO-NOT-LEAK")

(defn- contains-sentinel?
  "Deep-walk `x`; true when the sentinel string appears anywhere (as a
  value - matched as a SUBSTRING - inside a collection, or inside a
  stringified form). Thin wrapper over the shared `gen/contains-string?`
  (rf2-n5bkm7); the sentinel is matched as a substring (`exact? false`)."
  [x]
  (gen/contains-string? x sentinel false))

;; ---------------------------------------------------------------------------
;; Trace capture — register a listener, run `f`, return the single
;; `:rf.error/schema-validation-failure` trace it emitted.
;; ---------------------------------------------------------------------------

(defn- capture-op
  "Run `f` (which is expected to drive ONE error emit) with a trace listener
  attached; return the first trace event whose `:operation` is `op` (or nil if
  none fired)."
  [op f]
  (let [traces (atom [])
        kw     (keyword "rf2-o69h5" (name (gensym "listen")))]
    (trace-tooling/register-listener! kw (fn [ev] (swap! traces conj ev)))
    (try (f) (finally (trace-tooling/unregister-listener! kw)))
    #?(:clj (schemas/clear-sensitive-paths-cache!))
    (first (filter #(= op (:operation %)) @traces))))

(defn- capture-failure
  "Run `f` (which is expected to drive ONE validation failure emit) with a
  trace listener attached; return the first `:rf.error/schema-validation-failure`
  trace event (or nil if none fired)."
  [f]
  (capture-op :rf.error/schema-validation-failure f))

(defn- assert-no-leak
  "Core invariant assertion for one captured failure trace: it fired, it is
  stamped `:sensitive? true`, and the sentinel appears NOWHERE in it."
  [label trace]
  (is (some? trace) (str label ": a schema-validation-failure trace fired"))
  (when trace
    (is (true? (:sensitive? trace))
        (str label ": top-level :sensitive? stamp"))
    (is (not (contains-sentinel? trace))
        (str label ": the sentinel leaked into the trace: " (pr-str (:tags trace))))))

;; ---------------------------------------------------------------------------
;; A sensitive schema + a value that fails AT the sensitive slot, planting
;; the sentinel there. Shared across every kind's corpus driver.
;;   schema:  [:map [:secret {:sensitive? true} :string]]
;;   value:   {:secret [sentinel]}   ;; a vector where a :string is required
;; The failing value carries the sentinel; absent redaction it rides verbatim
;; in :value / :received / :explain.
;; ---------------------------------------------------------------------------

(def ^:private sensitive-map-schema
  [:map [:secret {:sensitive? true} :string]])

(def ^:private failing-sensitive-value
  {:secret [sentinel]})

;; An EVENT schema whose payload map marks a slot sensitive (the rf2-a5kzs#1
;; shape): `[:cat [:= id] [:map [:password {:sensitive? true} :string]]]`.
(def ^:private sensitive-event-schema
  [:cat [:= :auth/login]
   [:map [:password {:sensitive? true} :string]]])

(def ^:private failing-sensitive-event
  [:auth/login {:password [sentinel]}])

;; ---------------------------------------------------------------------------
;; CONFORMING SENSITIVE SIBLING (rf2-3qam7b) — the secret rides at a slot that
;; CONFORMS, while a DIFFERENT, non-sensitive slot FAILS. The whole-payload
;; slots (`:value` / `:received` / `:explain`) ship the WHOLE checked value,
;; so the conforming sensitive sibling rides inside them. A leaf-precise
;; decision keyed on the (non-sensitive) failing slot is sibling-blind and
;; leaked the conforming secret — the rf2-3qam7b leak. Per PER-SLOT DECISION
;; SCOPING the whole-payload slots redact under the ROOT check, so the
;; conforming sibling must never egress.
;;   schema: [:map [:jwt {:sensitive? true} :string] [:count :int]]
;;   value:  {:jwt sentinel :count "not-an-int"}   ;; :jwt conforms, :count fails
;; ---------------------------------------------------------------------------

(def ^:private sibling-sensitive-map-schema
  [:map
   [:jwt   {:sensitive? true} :string]
   [:count :int]])

(def ^:private failing-sibling-value
  ;; :jwt CONFORMS (a string) and carries the sentinel; :count FAILS (a string
  ;; where an :int is required). The failing slot is the NON-sensitive sibling.
  {:jwt sentinel :count "not-an-int"})

;; An EVENT schema where the `:cat` payload map carries a CONFORMING sensitive
;; sibling next to a non-sensitive failing one (the bead's event-surface pin).
(def ^:private sibling-sensitive-event-schema
  [:cat [:= :auth/profile]
   [:map
    [:password {:sensitive? true} :string]
    [:age      :int]]])

(def ^:private failing-sibling-event
  ;; :password CONFORMS (carries the sentinel); :age FAILS (string, not int).
  [:auth/profile {:password sentinel :age "old"}])

;; ---------------------------------------------------------------------------
;; PER-KIND CORPUS — drive every framework validation surface that emits
;; `:rf.error/schema-validation-failure` and assert no value-bearing slot
;; ships the sentinel for the `:sensitive?`-marked schema.
;; ---------------------------------------------------------------------------

(deftest event-validation-redacts-sensitive
  (testing "rf2-a5kzs#1 — :where :event (validate-event!) redacts the
            sensitive payload"
    (let [trace (capture-failure
                  #(schemas/validate-event!
                     :auth/login failing-sensitive-event
                     {:schema sensitive-event-schema}))]
      (assert-no-leak ":where :event" trace)
      (is (= :rf/redacted (-> trace :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> trace :tags :received)) ":received redacted")
      (is (= :rf/redacted (-> trace :tags :explain)) ":explain redacted"))))

;; NB the :where :cofx redaction test was REMOVED per rf2-nkf4l3 — the
;; injection-time validate-cofx! it exercised was retired (EP-0017). The live
;; cofx schema surface (`re-frame.cofx/validate-recordable-value!` →
;; `:rf.error/cofx-value-invalid`) routes its value-bearing slots through the
;; same `redact-validation-tags` seam; its redaction is covered by the core
;; artefact's cofx satisfaction tests.

(deftest fx-validation-redacts-sensitive
  (testing ":where :fx-args (validate-fx!) redacts the sensitive fx args (incl.
            the per-surface :rf.fx/args slot)"
    (let [trace (capture-failure
                  #(schemas/validate-fx!
                     :fx/secret :some/event failing-sensitive-value
                     {:schema sensitive-map-schema}))]
      (assert-no-leak ":where :fx-args" trace)
      (is (= :rf/redacted (-> trace :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> trace :tags :rf.fx/args)) ":rf.fx/args redacted"))))

(deftest sub-return-validation-redacts-sensitive
  (testing ":where :sub-return (validate-sub!) redacts the sensitive return
            value (incl. the per-surface :rf.sub/query-v lookup key)"
    (let [trace (capture-failure
                  #(schemas/validate-sub!
                     :sub/secret [:sub/secret sentinel] failing-sensitive-value
                     {:schema sensitive-map-schema}))]
      (assert-no-leak ":where :sub-return" trace)
      (is (= :rf/redacted (-> trace :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> trace :tags :rf.sub/query-v)) ":rf.sub/query-v redacted"))))

(deftest app-db-validation-redacts-sensitive
  (testing ":where :app-db (validate-app-schema!) redacts the sensitive
            post-commit slice"
    (rf/reg-app-schema [:root] sensitive-map-schema)
    (let [trace (capture-failure
                  #(schemas/validate-app-schema!
                     {:root failing-sensitive-value} :root/bad))]
      (assert-no-leak ":where :app-db" trace)
      (is (= :rf/redacted (-> trace :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> trace :tags :explain)) ":explain redacted"))))

(deftest machine-data-validation-redacts-sensitive
  (testing "rf2-o69h5 — :where :machine-data (data-validation/validate-snapshot-data!)
            redacts the sensitive :data map. Pre-fix this emitted :value /
            :received / :explain verbatim."
    (let [trace (capture-failure
                  #(machine-data/validate-snapshot-data!
                     :my/machine {:data failing-sensitive-value}
                     sensitive-map-schema :macrostep))]
      (assert-no-leak ":where :machine-data" trace)
      (is (= :rf/redacted (-> trace :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> trace :tags :received)) ":received redacted")
      (is (= :rf/redacted (-> trace :tags :explain)) ":explain redacted"))))

;; ---------------------------------------------------------------------------
;; CONFORMING SENSITIVE SIBLING CORPUS (rf2-3qam7b) — for each kind, the
;; sentinel rides at a CONFORMING sensitive slot while a DIFFERENT non-sensitive
;; slot fails. Because every kind's whole-payload slots carry the whole checked
;; value, the conforming sibling rides inside them and must NOT egress. The
;; leaf-precise (sibling-blind) decision the pre-rf2-3qam7b run-validation /
;; app-db paths used leaked it; per PER-SLOT DECISION SCOPING the whole-payload
;; slots redact under the ROOT check. Verify-by-revert: restoring the
;; leaf-precise `schema-sensitive-at?` decision on `run-validation` /
;; `validate-app-schema!`'s whole-payload slots makes these RED (the sentinel
;; surfaces in :received / :explain / :value).
;; ---------------------------------------------------------------------------

(deftest event-validation-redacts-conforming-sensitive-sibling
  (testing "rf2-3qam7b — :where :event: a CONFORMING sensitive :password rides
            next to a non-sensitive failing :age; the whole event vector (every
            value-bearing slot) redacts under the root check and the conforming
            sibling never egresses"
    (let [trace (capture-failure
                  #(schemas/validate-event!
                     :auth/profile failing-sibling-event
                     {:schema sibling-sensitive-event-schema}))]
      (assert-no-leak ":where :event (conforming sibling)" trace)
      (is (= :rf/redacted (-> trace :tags :received)) ":received redacted")
      (is (= :rf/redacted (-> trace :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> trace :tags :explain)) ":explain redacted"))))

;; NB the :where :cofx conforming-sibling test was REMOVED per rf2-nkf4l3 —
;; the injection-time validate-cofx! was retired (EP-0017). The whole-payload
;; conforming-sibling invariant is still covered for the event / fx / sub /
;; app-db / machine-data surfaces below + above.

(deftest fx-validation-redacts-conforming-sensitive-sibling
  (testing "rf2-3qam7b — :where :fx-args: a CONFORMING sensitive :jwt rides next
            to a non-sensitive failing :count; the whole fx args redact (incl.
            :rf.fx/args)"
    (let [trace (capture-failure
                  #(schemas/validate-fx!
                     :fx/effect :some/event failing-sibling-value
                     {:schema sibling-sensitive-map-schema}))]
      (assert-no-leak ":where :fx-args (conforming sibling)" trace)
      (is (= :rf/redacted (-> trace :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> trace :tags :rf.fx/args)) ":rf.fx/args redacted"))))

(deftest sub-return-validation-redacts-conforming-sensitive-sibling
  (testing "rf2-3qam7b — :where :sub-return: a CONFORMING sensitive :jwt rides
            next to a non-sensitive failing :count; the whole return value
            redacts"
    (let [trace (capture-failure
                  #(schemas/validate-sub!
                     :sub/view [:sub/view] failing-sibling-value
                     {:schema sibling-sensitive-map-schema}))]
      (assert-no-leak ":where :sub-return (conforming sibling)" trace)
      (is (= :rf/redacted (-> trace :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> trace :tags :received)) ":received redacted"))))

(deftest app-db-validation-redacts-conforming-sensitive-sibling-whole-explain
  (testing "rf2-3qam7b — :where :app-db: a CONFORMING sensitive :jwt rides next
            to a non-sensitive failing :count. The NARROWED `:value` slot (the
            failing :count leaf) rides verbatim — the precise-narrowing win for
            the genuinely narrowed slot — but the WHOLE-PAYLOAD `:explain` slot
            (the whole reg-slice, conforming :jwt included) redacts under the
            root check, so the conforming sibling never egresses"
    (rf/reg-app-schema [:root] sibling-sensitive-map-schema)
    (let [trace (capture-failure
                  #(schemas/validate-app-schema!
                     {:root failing-sibling-value} :root/bad))]
      (assert-no-leak ":where :app-db (conforming sibling)" trace)
      ;; The narrowed :value is the failing non-sensitive :count leaf, verbatim.
      (is (= "not-an-int" (-> trace :tags :value))
          ":value (narrowed to the failing :count leaf) rides verbatim")
      ;; The whole-payload :explain carries the conforming :jwt — redacted.
      (is (= :rf/redacted (-> trace :tags :explain)) ":explain redacted"))))

(deftest machine-data-validation-redacts-conforming-sensitive-sibling
  (testing "rf2-3qam7b — :where :machine-data: a CONFORMING sensitive :jwt rides
            next to a non-sensitive failing :count in the :data map; the whole
            :data redacts via the shared seam (root check)"
    (let [trace (capture-failure
                  #(machine-data/validate-snapshot-data!
                     :my/machine {:data failing-sibling-value}
                     sibling-sensitive-map-schema :macrostep))]
      (assert-no-leak ":where :machine-data (conforming sibling)" trace)
      (is (= :rf/redacted (-> trace :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> trace :tags :explain)) ":explain redacted"))))

;; ---------------------------------------------------------------------------
;; PRODUCTION-PATH coverage for `:where :flow-output` and `:where
;; :sub-override` (rf2-g1a4ho).
;;
;; The two corpus drivers below REGISTER a real flow / a real sub-override on
;; a `:sensitive?`-marked output schema and drive the ACTUAL production
;; cascade — `dispatch-sync` → router flows-after-interceptor → private
;; `validate-output!` for flows; `subscribe` → `resolve-sub-override` →
;; private `maybe-validate-sub-override!` for the sub-override seam — far
;; enough to emit `:rf.error/schema-validation-failure`. They then assert the
;; captured trace has NO sentinel and IS stamped `:sensitive?`.
;;
;; This couples the security invariant to the real call sites: a refactor in
;; `flows.cljc`'s `validate-output!` or `subs.cljc`'s
;; `maybe-validate-sub-override!` that drops the `(redact schema)` application,
;; emits a new un-listed value-bearing slot, or loses the `:sensitive?` stamp
;; now goes RED here — the false-green gap the prior shape-only tests left
;; open (the production emit could bypass the seam while the handcrafted
;; tag-shape test stayed green). Verify-by-revert: removing the
;; `redact (->> (redact schema))` cond-> arm from either production path makes
;; the corresponding test below fail (the sentinel surfaces in `:explain` /
;; `:value`).
;;
;; The flow driver is `.cljc` (the flow path is cross-runtime); the
;; sub-override driver is `#?(:cljs ...)` because the `:sub-overrides`
;; subscribe seam is a CLJS-only dev surface (`re-frame.subs`'
;; `maybe-validate-sub-override!` / `resolve-sub-override` are guarded
;; `#?(:cljs …)`).
;; ---------------------------------------------------------------------------

(defn- with-runtime*
  "Install the plain-atom substrate + ensure the `:rf/default` frame for the
  extent of `thunk`, then dispose. The shared adapter-less reset fixture
  (which `:clear-app-schemas?`-resets and pins `:rf/default` as the ambient
  scope) does NOT install an adapter — the flow drain / subscribe production
  paths need one. The outer fixture's `frame/*current-frame* :rf/default`
  binding supplies the ambient scope; we just stand up the substrate and the
  default frame here, and the next test's fixture run disposes the adapter on
  the way in (we dispose eagerly too for symmetry)."
  [thunk]
  (try (rf/init! plain-atom/adapter) (catch #?(:clj Throwable :cljs :default) _ nil))
  (frame/ensure-default-frame!)
  (try (thunk)
       (finally
         ;; Drop the flow registry + dirty-check rows so a sibling test in
         ;; this ns does not re-run our flow on its own dispatch.
         (when-let [reset! (late-bind/get-fn :flows/reset-flows!)]
           (try (reset!) (catch #?(:clj Throwable :cljs :default) _ nil))))))

(deftest flow-output-production-path-redacts-sensitive
  (testing "rf2-g1a4ho — :where :flow-output PRODUCTION PATH: a flow whose
            output fails a :sensitive?-marked schema drives the real
            dispatch-sync drain cascade and the emitted
            schema-validation-failure trace redacts every value-bearing slot
            + stamps :sensitive?. Reverting flows.cljc's `(redact schema)`
            arm makes this RED."
    (with-runtime*
      (fn []
        ;; Seed an event that writes the flow's input; the flow's :derive
        ;; copies the sentinel-bearing value to its :output-path, where it fails
        ;; the :sensitive? output schema. The failing value carries the
        ;; sentinel so, absent redaction, it rides verbatim in :value /
        ;; :explain on the trace bus.
        (rf/reg-event :flow/seed (fn [{:keys [db]} _] {:db {:in failing-sensitive-value}}))
        (rf/reg-flow :flow/secret {:inputs [[:in]] :output-path [:derived] :schema sensitive-map-schema} (fn [in] in))
        (let [trace (capture-failure
                      #(rf/dispatch-sync [:flow/seed]))]
          (assert-no-leak ":where :flow-output (production path)" trace)
          (is (= :flow-output (-> trace :tags :where))
              ":where :flow-output — drove the real flow-output emit site")
          (is (= :rf/redacted (-> trace :tags :value)) ":value redacted")
          (is (= :rf/redacted (-> trace :tags :explain)) ":explain redacted"))))))

(deftest recordable-cofx-schema-failure-production-path-redacts-sensitive
  (testing "rf2-hdi6wr — :rf.error/cofx-value-invalid PRODUCTION PATH: a
            recordable reg-cofx whose declared `:schema` marks a slot
            `{:sensitive? true}`, SUPPLIED/replayed a sentinel-bearing value
            that FAILS that schema, drives the real dispatch-sync recordable-
            value validation (re-frame.cofx/validate-recordable-value! ->
            emit-cofx-value-invalid!). BOTH the emitted trace AND the thrown
            ex-data are off-box egress; pre-fix both carried the raw secret in
            `:value` and Malli `:explain`. Asserts the sentinel appears NOWHERE
            in either, `:value`/`:explain` are `:rf/redacted`, and `:sensitive?`
            is stamped. Reverting cofx.cljc's `(redact-fn schema …)` wiring on
            this emit makes this RED."
    (with-runtime*
      (fn []
        ;; A recordable cofx whose schema marks `:token` sensitive. The
        ;; supplied value carries the sentinel at that slot but as a VECTOR
        ;; where a :string is required, so it fails the schema.
        (rf/reg-cofx :cofx/recordable-secret
          {:recordable? true
           :schema      [:map [:token {:sensitive? true} :string]]}
          (fn [] {:token "ignored-supplied-wins"}))
        (rf/reg-event :cofx/uses-recordable-secret
          {:rf.cofx/requires [:cofx/recordable-secret]}
          (fn [_ _] {}))
        (let [thrown (atom nil)
              ;; Supply the failing value on the token (the replay / supplied
              ;; path); dispatch-sync throws the hard error. The cofx
              ;; recordable-value path emits :rf.error/cofx-value-invalid
              ;; (NOT :rf.error/schema-validation-failure), so capture that op.
              trace  (capture-op
                       :rf.error/cofx-value-invalid
                       (fn []
                         (try
                           (rf/dispatch-sync
                             [:cofx/uses-recordable-secret]
                             {:rf.cofx {:cofx/recordable-secret
                                        {:token [sentinel]}}})
                           (catch #?(:clj clojure.lang.ExceptionInfo
                                     :cljs cljs.core/ExceptionInfo) e
                             (reset! thrown e)))))]
          ;; (a) The off-box TRACE redacts.
          (is (some? trace)
              ":rf.error/cofx-value-invalid trace fired")
          (when trace
            (is (true? (:sensitive? trace)) "trace top-level :sensitive? stamp")
            (is (= :rf/redacted (-> trace :tags :value)) "trace :value redacted")
            (is (= :rf/redacted (-> trace :tags :explain)) "trace :explain redacted")
            (is (not (contains-sentinel? trace))
                (str "the sentinel leaked into the cofx-value-invalid trace: "
                     (pr-str (:tags trace)))))
          ;; (b) The thrown ex-data (public error data) redacts too.
          (is (some? @thrown) "dispatch threw cofx-value-invalid")
          (when-let [d (some-> @thrown ex-data)]
            (is (= :rf.error/cofx-value-invalid (:rf.error/id d))
                "the throw carries :rf.error/cofx-value-invalid")
            (is (true? (:sensitive? d)) "ex-data :sensitive? stamp")
            (is (= :rf/redacted (:value d)) "ex-data :value redacted")
            (is (= :rf/redacted (:explain d)) "ex-data :explain redacted")
            (is (not (contains-sentinel? d))
                (str "the sentinel leaked into the cofx-value-invalid ex-data: "
                     (pr-str d)))))))))

(deftest generated-recordable-cofx-schema-failure-no-pre-validation-leak
  (testing "rf2-0mjgx6 — :rf.cofx/generated PRODUCTION PATH: a GENERATOR-backed
            recordable reg-cofx whose declared `:schema` marks a slot
            `{:sensitive? true}` and whose GENERATED value FAILS that schema
            must NOT ship the raw produced value on the dev `:rf.cofx/generated`
            trace BEFORE the redacted `:rf.error/cofx-value-invalid` fires.
            Pre-fix `re-frame.cofx/run-generator` emitted `:rf.cofx/generated`
            (carrying the raw value under `:rf.cofx/value`) THEN validated, so
            a schema-invalid sensitive generated value leaked verbatim to every
            trace listener / epoch :trace-events / MCP / log sink — the
            marks-projection chokepoint redacts only explicit `:sensitive`
            reg-marks, not schema-slot `:sensitive?`. The fix validates BEFORE
            the emit so the throw aborts before the raw value reaches ANY trace.
            Asserts: NO `:rf.cofx/generated` trace carries the sentinel, and the
            `:rf.error/cofx-value-invalid` trace + thrown ex-data redact."
    (with-runtime*
      (fn []
        ;; A GENERATOR-backed recordable cofx (no :provided?) whose schema marks
        ;; :token sensitive; the generator MINTS a value that fails the schema
        ;; (a vector where a :string is required), carrying the sentinel.
        (rf/reg-cofx :cofx/generated-secret
          {:recordable? true
           :schema      [:map [:token {:sensitive? true} :string]]}
          (fn [] {:token [sentinel]}))
        (rf/reg-event :cofx/uses-generated-secret
          {:rf.cofx/requires [:cofx/generated-secret]}
          (fn [_ _] {}))
        (let [thrown    (atom nil)
              all       (atom [])
              kw        (keyword "rf2-0mjgx6" (name (gensym "listen")))]
          (trace-tooling/register-listener! kw (fn [ev] (swap! all conj ev)))
          (try
            ;; Do NOT supply the fact on the token — the generator runs at
            ;; processing-start under the router's :live default, produces the
            ;; failing value, and the schema check throws cofx-value-invalid.
            (rf/dispatch-sync [:cofx/uses-generated-secret])
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core/ExceptionInfo) e
              (reset! thrown e))
            (finally (trace-tooling/unregister-listener! kw)))
          ;; (a) NO :rf.cofx/generated trace leaks the sentinel (the prior
          ;;     pre-validation emit). It is acceptable for the op to be absent
          ;;     entirely (validation aborts before the emit) — the invariant is
          ;;     that NONE that DID fire carry the secret.
          (let [generated (filter #(= :rf.cofx/generated (:operation %)) @all)]
            (doseq [g generated]
              (is (not (contains-sentinel? g))
                  (str "the sentinel leaked on a :rf.cofx/generated trace BEFORE "
                       "validation redacted it: " (pr-str (:tags g))))))
          ;; (b) The redacted cofx-value-invalid trace IS present and clean.
          (let [inv (first (filter #(= :rf.error/cofx-value-invalid (:operation %)) @all))]
            (is (some? inv) ":rf.error/cofx-value-invalid trace fired")
            (when inv
              (is (true? (:sensitive? inv)) "cofx-value-invalid :sensitive? stamp")
              (is (= :rf/redacted (-> inv :tags :value)) "cofx-value-invalid :value redacted")
              (is (not (contains-sentinel? inv))
                  (str "the sentinel leaked into the cofx-value-invalid trace: "
                       (pr-str (:tags inv))))))
          ;; (c) The thrown ex-data redacts too.
          (is (some? @thrown) "dispatch threw cofx-value-invalid")
          (when-let [d (some-> @thrown ex-data)]
            (is (= :rf.error/cofx-value-invalid (:rf.error/id d)))
            (is (= :rf/redacted (:value d)) "ex-data :value redacted")
            (is (not (contains-sentinel? d))
                (str "the sentinel leaked into the thrown ex-data: " (pr-str d))))
          ;; (d) The sentinel appears NOWHERE across the WHOLE captured stream.
          (is (not (contains-sentinel? @all))
              "the generated sentinel must not appear anywhere in the trace stream"))))))

(deftest valid-recordable-cofx-schema-path-unaffected
  (testing "rf2-hdi6wr (c) — a recordable reg-cofx whose value CONFORMS to its
            `:sensitive?`-bearing schema runs clean: no cofx-value-invalid
            trace, no throw, the value is delivered to the handler verbatim.
            The redaction wiring must not perturb the valid path."
    (with-runtime*
      (fn []
        (let [seen (atom ::unset)]
          (rf/reg-cofx :cofx/recordable-ok
            {:recordable? true
             :schema      [:map [:token {:sensitive? true} :string]]}
            (fn [] {:token "generated-default"}))
          (rf/reg-event :cofx/uses-recordable-ok
            {:rf.cofx/requires [:cofx/recordable-ok]}
            (fn [{:keys [cofx/recordable-ok]} _]
              (reset! seen recordable-ok) {}))
          (let [trace (capture-op
                        :rf.error/cofx-value-invalid
                        (fn []
                          (rf/dispatch-sync
                            [:cofx/uses-recordable-ok]
                            {:rf.cofx {:cofx/recordable-ok {:token "ok-value"}}})))]
            (is (nil? trace)
                "a conforming recordable value emits NO cofx-value-invalid trace")
            (is (= {:token "ok-value"} @seen)
                "the conforming value is delivered to the handler verbatim")))))))

#?(:cljs
   (deftest sub-override-production-path-redacts-sensitive
     (testing "rf2-g1a4ho — :where :sub-override PRODUCTION PATH (CLJS): a
               :sub-overrides HIT pinning a value that fails the sub's
               :sensitive?-marked output schema drives the real subscribe ->
               resolve-sub-override -> maybe-validate-sub-override! path; the
               emitted trace redacts every value-bearing slot + stamps
               :sensitive?. Reverting subs.cljc's `(redact schema)` arm makes
               this RED."
       (with-runtime*
         (fn []
           ;; A sub declaring a :sensitive?-marked output schema. The
           ;; override pins a value that fails it, planting the sentinel.
           (rf/reg-sub :sub/secret {:schema sensitive-map-schema}
                       (fn [_db _] {:secret "ok"}))
           ;; Publish the override resolver the Story carriage would publish:
           ;; an exact-query-vector HIT returns `[value]`.
           (late-bind/set-fn! :subs/resolve-sub-override
             (fn [query-v]
               (when (= query-v [:sub/secret])
                 [failing-sensitive-value])))
           (try
             (let [trace (capture-failure
                           #(deref (rf/subscribe [:sub/secret])))]
               (assert-no-leak ":where :sub-override (production path)" trace)
               (is (= :sub-override (-> trace :tags :where))
                   ":where :sub-override — drove the real override emit site")
               (is (= :rf/redacted (-> trace :tags :value)) ":value redacted")
               (is (= :rf/redacted (-> trace :tags :received)) ":received redacted")
               (is (= :rf/redacted (-> trace :tags :rf.sub/query-v))
                   ":rf.sub/query-v redacted"))
             (finally
               (late-bind/set-fn! :subs/resolve-sub-override nil))))))))

;; SUPPLEMENTARY (rf2-g1a4ho) — the handcrafted tag-shape tests below now
;; backstop the production-path drivers above: they exercise the shared seam
;; against the EXACT tag map each site builds, so a shape drift (a new
;; value-bearing slot added without listing it in `value-bearing-slots`) is
;; caught here even if a future production-path refactor stops building that
;; slot. They are no longer the ONLY coverage for these two emit sites.

(deftest sub-override-tag-shape-redacts-through-seam
  (testing "rf2-o69h5 — the :where :sub-override tag shape redacts every
            value-bearing slot through the shared seam"
    (let [tags  {:where          :sub-override
                 :rf.sub/id      :sub/secret
                 :failing-id     :sub/secret
                 :schema-id      :sub/secret
                 :rf.sub/query-v [:sub/secret sentinel]
                 :received       failing-sensitive-value
                 :value          failing-sensitive-value
                 :explain        {:value failing-sensitive-value}
                 :recovery       :replaced-with-default}
          out   (schemas/redact-validation-tags sensitive-map-schema tags)]
      (is (true? (:sensitive? out)) ":sensitive? stamped")
      (is (= :rf/redacted (:value out)) ":value redacted")
      (is (= :rf/redacted (:received out)) ":received redacted")
      (is (= :rf/redacted (:explain out)) ":explain redacted")
      (is (= :rf/redacted (:rf.sub/query-v out)) ":rf.sub/query-v redacted")
      (is (not (contains-sentinel? out))
          (str "the sentinel survived the :sub-override redaction: " (pr-str out))))))

(deftest flow-output-tag-shape-redacts-through-seam
  (testing "rf2-o69h5 — the :where :flow-output tag shape redacts :explain (the
            slot the prior path left verbatim) AND :value through the shared seam"
    (let [tags  {:category   :rf.error/schema-validation-failure
                 :where      :flow-output
                 :rf.flow/id :flow/secret
                 :failing-id :flow/secret
                 :schema-id  :flow/secret
                 :path       [:derived]
                 :value      failing-sensitive-value
                 :explain    {:value failing-sensitive-value}
                 :recovery   :no-recovery}
          out   (schemas/redact-validation-tags sensitive-map-schema tags)]
      (is (true? (:sensitive? out)) ":sensitive? stamped")
      (is (= :rf/redacted (:value out)) ":value redacted")
      (is (= :rf/redacted (:explain out)) ":explain redacted")
      (is (not (contains-sentinel? out))
          (str "the sentinel survived the :flow-output redaction: " (pr-str out))))))

;; ---------------------------------------------------------------------------
;; PROPERTY — across arbitrary collection/map nestings of a sensitive slot,
;; NO callable validation kind leaks the sentinel. Reuses the rf2-g5auo shape
;; generator idea but sweeps every callable validation entry point per draw.
;; ---------------------------------------------------------------------------

(def ^:private gen-key
  (gen/gen-elem [:auth :token :secret :pw :ssn :payload :inner :node :a :b]))

(defn- gen-shape
  "Generator returning `[schema db-value]` with a sentinel-bearing
  :sensitive? leaf at a random collection/map nesting (the rf2-g5auo shape
  space). `depth` bounds recursion."
  [depth]
  (if (<= depth 0)
    (fn [rng]
      [[[:string {:sensitive? true}] [sentinel]] rng])
    (fn [rng]
      (let [[wrap rng1] (gen/rand-nth rng [:map :vector :sequential :map-of :map-of-key :tuple
                                           :set :and :or :multi :orn])
            [[inner-schema inner-val] rng2] ((gen-shape (dec depth)) rng1)]
        (case wrap
          :map        (let [[k rng3] (gen-key rng2)]
                        [[[:map [k inner-schema]] {k inner-val}] rng3])
          :vector     [[[:vector inner-schema] [inner-val]] rng2]
          :sequential [[[:sequential inner-schema] [inner-val]] rng2]
          :map-of     [[[:map-of :string inner-schema] {"k" inner-val}] rng2]
          ;; rf2-gocef0 / rf2-6ijdgh — sensitive :map-of KEY: the sentinel is
          ;; planted AS the key under a `:sensitive?` key schema; the walker's
          ;; :map-of key-scrub branch must scrub it from :path / :reason across
          ;; every callable validation kind. Both key and value carry the
          ;; sentinel; both must redact.
          :map-of-key [[[:map-of [:string {:sensitive? true}] inner-schema] {sentinel inner-val}] rng2]
          :tuple      [[[:tuple :int inner-schema] [0 inner-val]] rng2]
          :set        (let [[k rng3] (gen-key rng2)]
                        [[[:set [:map [k inner-schema]]] #{{k inner-val}}] rng3])
          :and        (let [[k rng3] (gen-key rng2)]
                        [[[:map [k {:sensitive? true} [:and inner-schema]]] {k inner-val}] rng3])
          :or         (let [[k rng3] (gen-key rng2)]
                        [[[:map [k {:sensitive? true} [:or inner-schema]]] {k inner-val}] rng3])
          :multi      (let [[k rng3] (gen-key rng2)]
                        [[[:map [k {:sensitive? true}
                                 [:multi {:dispatch :rf2/d}
                                  [:x [:map [:rf2/d :keyword] [:v inner-schema]]]]]]
                          {k {:rf2/d :x :v inner-val}}] rng3])
          :orn        (let [[k rng3] (gen-key rng2)]
                        [[[:map [k {:sensitive? true} [:orn [:x inner-schema]]]] {k inner-val}] rng3]))))))

(def ^:private gen-nested-sensitive
  (fn [rng]
    (let [[depth rng1] (gen/next-int rng 5)]
      ((gen-shape (inc depth)) rng1))))

(defn- all-kinds-redact?
  "Given a `[schema value]` draw, run EVERY callable validation kind and
  return true iff none leaked the sentinel and each stamped :sensitive?."
  [[schema value]]
  (let [event-schema [:cat [:= :gen/id] schema]
        event-value  [:gen/id value]
        checks
        [;; :where :event — the payload schema is the generated shape, the
         ;; event wraps it under a :cat (the rf2-a5kzs#1 shape).
         (capture-failure
           #(schemas/validate-event! :gen/id event-value {:schema event-schema}))
         ;; :where :fx-args
         (capture-failure
           #(schemas/validate-fx! :gen/fx :gen/ev value {:schema schema}))
         ;; :where :sub-return
         (capture-failure
           #(schemas/validate-sub! :gen/sub [:gen/sub sentinel] value {:schema schema}))
         ;; :where :machine-data
         (capture-failure
           #(machine-data/validate-snapshot-data!
              :gen/machine {:data value} schema :macrostep))]]
    (every? (fn [trace]
              (and (some? trace)
                   (true? (:sensitive? trace))
                   (not (contains-sentinel? trace))))
            checks)))

(deftest every-kind-redacts-at-arbitrary-nesting
  (testing "rf2-o69h5 — across arbitrary collection/map nestings of a
            :sensitive? slot, EVERY callable validation kind (event /
            fx / sub-return / machine-data) redacts the sentinel and stamps
            :sensitive?. One escaped kind/shape = one leak."
    (let [result (gen/for-all
                   gen-nested-sensitive 120 17
                   all-kinds-redact?)]
      (is (nil? result)
          (str "a validation kind leaked the sensitive sentinel (or missed the "
               ":sensitive? stamp) for a generated nesting: "
               (pr-str (when result (dissoc result :threw))))))))

;; ---------------------------------------------------------------------------
;; NO OVER-REDACTION — a NON-sensitive validation failure rides verbatim
;; (the seam is precise, not a blanket scrub).
;; ---------------------------------------------------------------------------

(deftest non-sensitive-failure-not-over-redacted
  (testing "rf2-o69h5 — a failure on a schema with no :sensitive? slot rides
            verbatim across the kinds; the seam does not over-redact"
    (let [plain-schema [:map [:n :int]]
          plain-value  {:n "not-an-int"}
          event-trace  (capture-failure
                         #(schemas/validate-event!
                            :plain/ev [:plain/ev plain-value]
                            {:schema [:cat [:= :plain/ev] plain-schema]}))
          machine-trace (capture-failure
                          #(machine-data/validate-snapshot-data!
                             :plain/machine {:data plain-value} plain-schema :macrostep))]
      (is (some? event-trace))
      (is (not (contains? (:tags event-trace) :sensitive?))
          ":where :event — no :sensitive? stamp when nothing is sensitive")
      (is (not= :rf/redacted (-> event-trace :tags :value))
          ":where :event — :value rides verbatim")
      (is (some? machine-trace))
      (is (not= :rf/redacted (-> machine-trace :tags :value))
          ":where :machine-data — :value rides verbatim"))))
