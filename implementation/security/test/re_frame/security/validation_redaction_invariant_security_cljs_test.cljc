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
            [clojure.walk :as walk]
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
  value, inside a collection, or inside a stringified form)."
  [x]
  (let [hit (volatile! false)]
    (walk/postwalk
      (fn [node]
        (when (and (string? node) (re-find (re-pattern sentinel) node))
          (vreset! hit true))
        node)
      x)
    (or @hit
        (re-find (re-pattern sentinel) (pr-str x)))))

;; ---------------------------------------------------------------------------
;; Trace capture — register a listener, run `f`, return the single
;; `:rf.error/schema-validation-failure` trace it emitted.
;; ---------------------------------------------------------------------------

(defn- capture-failure
  "Run `f` (which is expected to drive ONE validation failure emit) with a
  trace listener attached; return the first `:rf.error/schema-validation-failure`
  trace event (or nil if none fired)."
  [f]
  (let [traces (atom [])
        kw     (keyword "rf2-o69h5" (name (gensym "listen")))]
    (trace-tooling/register-listener! kw (fn [ev] (swap! traces conj ev)))
    (try (f) (finally (trace-tooling/unregister-listener! kw)))
    #?(:clj (schemas/clear-sensitive-paths-cache!))
    (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                   @traces))))

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

(deftest cofx-validation-redacts-sensitive
  (testing ":where :cofx (validate-cofx!) redacts the sensitive injected value"
    (let [trace (capture-failure
                  #(schemas/validate-cofx!
                     :rf.cofx/secret :some/event failing-sensitive-value
                     {:schema sensitive-map-schema}))]
      (assert-no-leak ":where :cofx" trace)
      (is (= :rf/redacted (-> trace :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> trace :tags :received)) ":received redacted"))))

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
        ;; Seed an event that writes the flow's input; the flow's :output
        ;; copies the sentinel-bearing value to its :path, where it fails
        ;; the :sensitive? output schema. The failing value carries the
        ;; sentinel so, absent redaction, it rides verbatim in :value /
        ;; :explain on the trace bus.
        (rf/reg-event-db :flow/seed (fn [_ _] {:in failing-sensitive-value}))
        (rf/reg-flow {:id     :flow/secret
                      :inputs [[:in]]
                      :output (fn [in] in)            ;; pass the bad value through
                      :path   [:derived]
                      :schema sensitive-map-schema})
        (let [trace (capture-failure
                      #(rf/dispatch-sync [:flow/seed]))]
          (assert-no-leak ":where :flow-output (production path)" trace)
          (is (= :flow-output (-> trace :tags :where))
              ":where :flow-output — drove the real flow-output emit site")
          (is (= :rf/redacted (-> trace :tags :value)) ":value redacted")
          (is (= :rf/redacted (-> trace :tags :explain)) ":explain redacted"))))))

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
      (let [[wrap rng1] (gen/rand-nth rng [:map :vector :sequential :map-of :tuple
                                           :set :and :or :multi :orn])
            [[inner-schema inner-val] rng2] ((gen-shape (dec depth)) rng1)]
        (case wrap
          :map        (let [[k rng3] (gen-key rng2)]
                        [[[:map [k inner-schema]] {k inner-val}] rng3])
          :vector     [[[:vector inner-schema] [inner-val]] rng2]
          :sequential [[[:sequential inner-schema] [inner-val]] rng2]
          :map-of     [[[:map-of :string inner-schema] {"k" inner-val}] rng2]
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
         ;; :where :cofx
         (capture-failure
           #(schemas/validate-cofx! :gen/cofx :gen/ev value {:schema schema}))
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
            :sensitive? slot, EVERY callable validation kind (event / cofx /
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
