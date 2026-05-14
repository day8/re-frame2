(ns re-frame.schemas-conformance-test
  "Per rf2-2l08g (audit rf2-x8x4p §TE5). Drives every
  `spec/conformance/fixtures/schema-*.edn` fixture (plus
  `error-schema-failure.edn`) through the live runtime — `reg-app-schema`,
  `validate-app-db!`, the `:schemas/validate-event!` / `:schemas/validate-cofx!` /
  `:schemas/validate-sub-return!` late-bind hooks, and the
  `:rf.error/schema-validation-failure` trace contract — and asserts
  the conformance-corpus's recorded outcome against what the artefact
  actually produces.

  This is the schemas artefact's own conformance gate. Pre-rf2-2l08g
  the schema fixtures rode the CORE artefact's
  `re-frame.conformance-test` only (rf2-d0wem patterned for machines,
  rf2-i3qc0 for ssr; analogous gap for schemas). Two drawbacks:

    1. The gate ran at the wrong artefact. A schemas-touching PR
       could break a schema fixture and only surface at core's gate —
       at which point the failure has to be triaged across two
       artefacts. Now it surfaces here, alongside the elision-toggle
       and validator-table tests in the same gate.
    2. The schemas artefact's per-artefact test suite did NOT
       exercise the corpus that defines its public contract. The
       unit tests in `schemas_test` cover the elision toggle and the
       projector mapping; the corpus covers the dispatch-time
       behaviour (event-payload rejection skips the handler, cofx
       rejection skips the handler, sub-return rejection replaces
       with default, app-db slice violation emits with :where :app-db).
       Both surfaces need running for every schemas-touching change.

  ## What this runner does

  For each `schema-*.edn` / `error-schema-failure.edn` fixture:

    1. Resets the runtime (registrar, frames, schemas-by-frame,
       flows) and re-inits the plain-atom adapter. Per-test reset is
       essential — the late-bind hook table is module-load state, but
       the registry / schemas / frames atoms are global and would
       leak across fixtures.
    2. Realises the fixture's `:fixture/handlers` (event, sub, cofx)
       into native fns via the `re-frame.conformance` DSL interpreter
       (a re-use of core's pre-existing helpers; the interpreter is
       in `core/src` so the schemas artefact has it on the classpath
       without pulling core's test tree).
    3. Wires `:fixture/registry :app-schema` into `reg-app-schema`.
    4. Registers the default frame with the fixture's
       `:fixture/frame-config` (e.g. `:on-create [:init]`).
    5. Drives `:fixture/dispatches` through `rf/dispatch-sync`.
    6. Asserts each of:
       - `:final-app-db` (submap match — partial expectations on
         nested slices work the same way),
       - `:sub-values` (per-query expected return value),
       - `:trace-emissions` (partial match, order-preserving subset
         per `spec/conformance/README.md` §Fixture lifecycle).

  ## Capability claim

  Per `spec/conformance/README.md` §Capability tagging, the runner
  declares the surface its host implements. A fixture whose
  `:fixture/capabilities` are NOT a subset of `claimed-capabilities`
  is reported as out-of-claim and does not block the suite.

  The claim covers `:schemas/runtime` (app-db slice validation,
  baseline since rf2-p7va), `:schemas/event-payload` (rf2-jwm4),
  `:schemas/cofx` (rf2-7leq), `:schemas/sub-return` (rf2-wcam), plus
  the bare `:core/*` capabilities every schema fixture cross-cuts
  (event / sub / error / trace).

  ## Coverage scope

  Only `schema-*.edn` plus `error-schema-failure.edn`. The cross-spec
  fixtures (e.g. those that combine schemas with routing or SSR) and
  the FSM / ssr / routing / flows fixtures live at their respective
  artefact gates (or at core's full-corpus gate). Running them here
  would be redundant and slow the gate; missing them would re-create
  the gap rf2-2l08g closes."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.conformance :as conformance]
            [re-frame.core :as rf]
            [re-frame.cofx :as cofx]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            ;; Per rf2-t0hq + rf2-qyfie — the Malli adapter ns must be
            ;; required at boot to publish the late-bind hook the
            ;; default validator routes through. The conformance corpus
            ;; expects Malli-backed validation outcomes; absent the
            ;; require the validator soft-passes (no failure traces).
            [re-frame.schemas.malli]
            [re-frame.schemas.test-fixture :as tf]
            [re-frame.subs :as subs]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.trace :as trace]))

(use-fixtures :each tf/reset-runtime)

;; ---- fixture discovery ----------------------------------------------------

(def fixtures-dir
  "The corpus lives at the repo root under
  `spec/conformance/fixtures/`. The schemas artefact's tests run from
  `implementation/schemas/`, so the relative path is two-deep."
  (io/file "../../spec/conformance/fixtures"))

(defn- load-fixture
  "Read one EDN fixture. The corpus does not use auto-resolved keywords
  in schema fixtures (the `::name` rewrite the machines / ssr runners
  carry is for `:rf.machine.timer/*` synthetic events). We apply the
  same rewrite here for symmetry — a no-op on the schemas subset, but
  the runner stays robust if a future fixture grows a `::` form."
  [file]
  (try
    (let [raw   (slurp file)
          fixed (str/replace raw #"::([a-zA-Z][a-zA-Z0-9_-]*)"
                             ":rf.machine.timer/$1")]
      (edn/read-string fixed))
    (catch Throwable e
      {:fixture/load-error (.getMessage e)
       :fixture/file       (.getName file)})))

(defn- schema-fixture-file?
  "True for the schemas-relevant fixture filenames:
    - `schema-*.edn` — the four step-by-step schema validation
      points (app-db slice, event payload, cofx, sub-return).
    - `error-schema-failure.edn` — same surface, framed from the
      error-trace angle (Spec 010 §Validation order step 4)."
  [file]
  (let [n (.getName file)]
    (or (and (str/starts-with? n "schema-")
             (str/ends-with? n ".edn"))
        (= n "error-schema-failure.edn"))))

(defn- all-schemas-fixtures
  "Every fixture file relevant to the schemas runner. Returns
  `[[filename fixture] ...]` in stable lex order."
  []
  (->> (file-seq fixtures-dir)
       (filter #(.isFile %))
       (filter schema-fixture-file?)
       (sort-by #(.getName %))
       (mapv (fn [f] [(.getName f) (load-fixture f)]))))

;; ---- claimed capability + spec-version sets ------------------------------

(def claimed-capabilities
  "The schemas-surface capabilities plus the `:core/*` cross-cuts that
  every schema fixture declares. The four `:schemas/*` tags map 1:1 to
  the four validation points in Spec 010 §Validation order."
  #{:core/event-handler
    :core/sub
    :core/fx
    :core/error
    :core/trace
    :schemas/runtime
    :schemas/event-payload
    :schemas/cofx
    :schemas/sub-return})

(def claimed-spec-versions
  "Conformance corpus spec versions this runner claims to conform
  against. Matches the core runner's set at rf2-2l08g time."
  #{"1.0"})

(defn- runnable-capability-set?
  [fixture]
  (let [caps (or (:fixture/capabilities fixture) #{})]
    (every? claimed-capabilities caps)))

(defn- spec-version-claimed?
  [fixture]
  (let [v (:fixture/spec-version fixture)]
    (or (nil? v) (contains? claimed-spec-versions v))))

;; ---- handler realisation --------------------------------------------------
;;
;; The conformance corpus represents handler bodies as data; the
;; `re-frame.conformance` interpreter (in core/src — already on this
;; artefact's classpath) lifts the DSL into native fns. The wiring
;; mirrors the schemas slice of `re-frame.conformance-test/realise-handlers`
;; minus the surfaces the schema fixtures never touch (machines /
;; flows / routes / heads / views).

(defn- adapter-helpers
  "Helper map for `realise-fx-handler` — exposes the frame's app-db
  via the substrate adapter plus the dispatch surface. Mirrors core's
  runner shape."
  []
  {:read-db!  (fn [frame-id] (frame/frame-app-db-value frame-id))
   :write-db! (fn [frame-id new-db]
                (let [container (frame/get-frame-db frame-id)]
                  (substrate-adapter/replace-container! container new-db)))
   :dispatch! (fn [event frame-id] (rf/dispatch event {:frame frame-id}))})

(defn- collect-cofx-keys
  "Walk DSL body steps and collect every cofx-id referenced via
  `[:cofx-key K]`. Used to auto-wire `(inject-cofx K)` interceptors per
  the rf2-g25p convention.

  Per Spec 010 §Where schemas attach §On every reg-*, the cofx-id is
  the slot key — so a handler body that reads `[:cofx-key :app-version]`
  declares its dependency on a cofx whose namespace-or-bare key is
  `:app-version`. The runner uses that declaration to auto-inject the
  matching namespaced cofx-ids (e.g. `:app-version/bad`)."
  [steps]
  (let [out (atom #{})]
    ((fn walk [form]
       (cond
         (and (vector? form) (= :cofx-key (first form)))
         (swap! out conj (second form))

         (coll? form)
         (doseq [x form] (walk x))))
     steps)
    @out))

(defn- realise-cofx-handler
  "DSL → a cofx handler fn (ctx) → ctx. Mirrors core's runner: a
  `:set` step's value is the value injected at `[:coeffects cofx-id]`.
  The path slot is symbolic — convention is `[k]` where `k` is the
  bare key the handler reads via `[:cofx-key k]`.

  Per rf2-g25p, the body is realised against the inject-cofx ctx —
  values pass through `eval-value` so reflection forms resolve against
  the inbound coeffects/event the same way they do in event-handler
  bodies."
  [cofx-id steps]
  (fn [ctx]
    (let [eval-value (requiring-resolve 're-frame.conformance/eval-value*)
          dsl-ctx    {:db    (get-in ctx [:coeffects :db])
                      :event (get-in ctx [:coeffects :event])
                      :cofx  (:coeffects ctx)}]
      (reduce (fn [c step]
                (case (first step)
                  :set  (let [[_ _path value] step
                              v (eval-value value dsl-ctx)]
                          (assoc-in c [:coeffects cofx-id] v))
                  :noop c
                  c))
              ctx
              steps))))

(defn- realise-handlers
  "Register every handler the fixture declares (events / subs / cofx /
  fx) and wire app-schemas. Mirrors core's realise-handlers for the
  slice schema fixtures actually use."
  [fixture]
  (let [hmap          (or (:fixture/handlers fixture) {})
        event-meta    (get-in fixture [:fixture/registry :event] {})
        sub-meta      (get-in fixture [:fixture/registry :sub] {})
        cofx-registry (get-in fixture [:fixture/registry :cofx] {})
        cofx-bodies   (get hmap :cofx)
        helpers       (adapter-helpers)
        ;; cofx that auto-wire as inject-cofx interceptors on event
        ;; handlers (per rf2-g25p — the runner's first-pass auto-
        ;; injection convention). Stable lex order on cofx-id so the
        ;; last-write-wins outcome is deterministic.
        cofx-by-key
        (->> cofx-registry
             (sort-by key)
             (group-by (fn [[cofx-id _]] (keyword (namespace cofx-id))))
             (reduce-kv (fn [acc k pairs]
                          (assoc acc k (mapv first pairs)))
                        {}))]
    ;; ---- cofx ----------------------------------------------------------
    ;; Per Spec 010 §step 2 (rf2-7leq): cofx registrations carry :spec
    ;; metadata; the runtime calls `:schemas/validate-cofx!` after
    ;; injection. We register both bodies AND any registry-only entries.
    (let [all-cofx-ids (into #{} (concat (keys cofx-bodies) (keys cofx-registry)))]
      (doseq [cofx-id all-cofx-ids]
        (let [body    (get cofx-bodies cofx-id [[:noop]])
              meta    (get cofx-registry cofx-id {})
              handler (realise-cofx-handler cofx-id body)]
          (rf/reg-cofx cofx-id (assoc meta :handler-fn handler) handler))))
    ;; ---- events --------------------------------------------------------
    ;; Per Spec 010 §step 1 (rf2-jwm4): event meta carries :spec; the
    ;; runtime calls `:schemas/validate-event!` before the handler runs.
    ;; Per rf2-g25p: scan the body for [:cofx-key K]; for each K,
    ;; auto-wire (inject-cofx C) for every C whose namespace matches K.
    (doseq [[id steps] (:event hmap)]
      (let [[kind handler] (conformance/realise-event-handler steps)
            meta           (get event-meta id {})
            ks             (collect-cofx-keys steps)
            cofx-ids       (vec (mapcat (fn [k]
                                          (or (get cofx-by-key k)
                                              (when (contains? cofx-registry k) [k])))
                                        ks))
            interceptors   (mapv cofx/inject-cofx cofx-ids)]
        (case kind
          :db (if (seq meta)
                (rf/reg-event-db id meta interceptors handler)
                (if (seq interceptors)
                  (rf/reg-event-db id interceptors handler)
                  (rf/reg-event-db id handler)))
          :fx (if (seq meta)
                (rf/reg-event-fx id meta interceptors handler)
                (if (seq interceptors)
                  (rf/reg-event-fx id interceptors handler)
                  (rf/reg-event-fx id handler))))))
    ;; ---- subs ----------------------------------------------------------
    ;; Per Spec 010 §step 6 (rf2-wcam): sub meta carries :spec; the
    ;; runtime calls `:schemas/validate-sub-return!` after each compute.
    (doseq [[id steps] (:sub hmap)]
      (let [{:keys [kind inputs body]} (conformance/realise-sub steps)
            meta                       (get sub-meta id {})]
        (case kind
          :layer-1 (if (seq meta) (rf/reg-sub id meta body) (rf/reg-sub id body))
          ;; Use the fn-form `subs/reg-sub` — the public `rf/reg-sub`
          ;; is a JVM macro (Spec 001 §Source-coordinate capture); a
          ;; macro var isn't `apply`-able.
          :layer-2 (apply subs/reg-sub id
                          (concat (when (seq meta) [meta])
                                  (interleave (repeat :<-) inputs)
                                  [body])))))
    ;; ---- fxs -----------------------------------------------------------
    ;; Schema fixtures rarely register fx bodies, but cover the case for
    ;; symmetry with the other runners (and for any future fixture that
    ;; combines schemas with an fx surface).
    (let [fx-bodies   (:fx hmap)
          fx-registry (get-in fixture [:fixture/registry :fx] {})
          all-ids     (into #{} (concat (keys fx-bodies) (keys fx-registry)))]
      (doseq [id all-ids]
        (let [body    (get fx-bodies id [[:noop]])
              meta    (get fx-registry id {})
              handler (conformance/realise-fx-handler id body helpers)]
          (rf/reg-fx id (assoc meta :handler-fn handler) handler))))
    ;; NOTE: app-schemas are intentionally NOT registered here — see
    ;; `realise-app-schemas` below. Per rf2-wkxng / rf2-6m0se,
    ;; `destroy-frame!` now drops the frame's app-db schemas
    ;; (parity with the machines / SSR / privacy destroy hooks), so
    ;; schema registration must follow the runner's destroy+reg-frame
    ;; cycle. Event / sub / cofx / fx registrations are global on
    ;; the registrar and survive destroy-frame!, so they continue to
    ;; live here so `:on-create` can fire against them.
    nil))

(defn- realise-app-schemas
  "Register the fixture's app-db schemas. Called AFTER the runner's
  destroy+reg-frame cycle so the new frame's slate carries exactly
  the fixture's declarations and nothing else.

  Per rf2-wkxng / rf2-6m0se the destroy step now drops every schema
  registered against the frame (parity with the machines / SSR /
  privacy destroy hooks). Pre-fix the runner relied on the leak —
  registering app-schemas inside `realise-handlers` BEFORE
  `destroy-frame!` and counting on the schemas to survive. With the
  leak closed, app-schema registration is sequenced explicitly
  after `reg-frame`."
  [fixture]
  (doseq [[path schema] (get-in fixture [:fixture/registry :app-schema])]
    (rf/reg-app-schema path schema)))

;; ---- trace capture -------------------------------------------------------

(defn- collect-traces
  "Register a trace listener for the fixture's run; the returned atom
  accumulates every captured trace event."
  [fixture-id]
  (let [traces (atom [])]
    (trace/register-trace-cb! [fixture-id]
                              (fn [ev] (swap! traces conj ev)))
    traces))

;; ---- matchers ------------------------------------------------------------

(defn- submap?
  "True if every key of `expected` is present in `actual` with a
  matching value. Recurses into nested maps so partial expectations on
  nested slices work the same way (e.g. a fixture asserting only a
  subset of trace tags)."
  [expected actual]
  (cond
    (and (map? expected) (map? actual))
    (every? (fn [[k v]]
              (let [a (get actual k)]
                (if (and (map? v) (map? a))
                  (submap? v a)
                  (= v a))))
            expected)

    :else (= expected actual)))

(defn- trace-matches?
  "Partial match — every key of `exp` appears in `act` with a matching
  value. Nested-map keys partial-match the same way."
  [exp act]
  (every? (fn [[k v]]
            (let [a (get act k)]
              (cond
                (and (map? v) (map? a))
                (every? (fn [[kk vv]] (= vv (get a kk))) v)
                :else (= v a))))
          exp))

(defn- check-trace-emissions
  "Order-preserving subset match — every expected trace must appear in
  `actual` in declaration order. Extras tolerated (the runtime may
  emit bookkeeping traces the fixture doesn't care about)."
  [actual expected]
  (loop [actual   actual
         expected expected
         failures []]
    (cond
      (empty? expected) failures
      (empty? actual)
      (conj failures (str "expected trace not seen: " (pr-str (first expected))))
      :else
      (let [exp (first expected)
            i   (->> actual
                     (map-indexed vector)
                     (some (fn [[i a]] (when (trace-matches? exp a) i))))]
        (if i
          (recur (drop (inc i) actual) (rest expected) failures)
          (recur actual (rest expected)
                 (conj failures (str "expected trace not seen: " (pr-str exp)))))))))

;; ---- :fixture/dispatches runner ------------------------------------------

(defn- run-dispatch [ev]
  (cond
    (map? ev)
    (let [{event :event :as opts} ev]
      (rf/dispatch-sync event (dissoc opts :event)))

    :else
    (rf/dispatch-sync ev)))

;; ---- single-fixture execution -------------------------------------------

(defn- resolve-sub
  "A `:sub-values` query may be either:
    [query-v]                 — implicit :rf/default frame
    [frame-id [query-v]]      — explicit frame
  Returns `[frame-id query-v]`. Mirrors core's runner."
  [entry]
  (if (and (vector? entry)
           (= 2 (count entry))
           (vector? (second entry)))
    [(first entry) (second entry)]
    [:rf/default entry]))

(defn- run-fixture
  "Run one fixture; return a result map shaped like the core runner's."
  [fixture]
  (try
    (let [fid          (:fixture/id fixture)
          traces       (collect-traces fid)
          _            (realise-handlers fixture)
          frame-config (or (:fixture/frame-config fixture) {})
          ;; `reset-runtime` already created :rf/default WITHOUT an
          ;; :on-create. `reg-frame` against an existing id is a
          ;; surgical update that does NOT re-fire :on-create (Spec 002).
          ;; Destroy first so the fixture's :on-create cascade fires
          ;; under its declared frame config.
          _            (rf/destroy-frame! :rf/default)
          ;; Per rf2-wkxng / rf2-6m0se: register app-db schemas
          ;; AFTER the destroy step (the new
          ;; `:schemas/on-frame-destroyed!` hook drops the frame's
          ;; schemas on destroy, so registering them BEFORE the
          ;; destroy would leak them through). Schemas must also
          ;; precede `reg-frame` so the :on-create cascade fires
          ;; with the schemas in place — the on-create's db commit
          ;; will trigger validate-app-db! against the new slate.
          _            (realise-app-schemas fixture)
          _            (rf/reg-frame :rf/default frame-config)
          dispatches   (or (:fixture/dispatches fixture) [])]
      (doseq [ev dispatches]
        (run-dispatch ev))
      (let [expect        (or (:fixture/expect fixture) {})
            expected-db   (:final-app-db expect)
            final-db      (rf/get-frame-db :rf/default)
            sub-checks
            (doall
              (for [[query-v expected-val] (or (:sub-values expect) {})]
                (let [[frame-id qv] (resolve-sub query-v)]
                  {:query    query-v
                   :expected expected-val
                   :actual   (rf/subscribe-once frame-id qv)})))
            trace-failures (check-trace-emissions @traces
                                                  (:trace-emissions expect))]
        (trace/clear-trace-cbs!)
        {:fixture-id     fid
         :passed?        (and (or (nil? expected-db) (submap? expected-db final-db))
                              (every? #(= (:expected %) (:actual %)) sub-checks)
                              (empty? trace-failures))
         :final-db       final-db
         :expected-db    expected-db
         :sub-checks     sub-checks
         :trace-failures trace-failures}))
    (catch Throwable e
      {:fixture-id (:fixture/id fixture)
       :passed?    false
       :error      (.getMessage e)
       :exception  e})))

;; ---- the test entrypoint -------------------------------------------------

(deftest run-schemas-conformance-corpus
  (let [results (atom [])]
    (doseq [[fname fixture] (all-schemas-fixtures)]
      (cond
        (:fixture/load-error fixture)
        (swap! results conj {:fixture-id fname
                             :skipped?   true
                             :reason     "load error"
                             :error      (:fixture/load-error fixture)})

        (not (spec-version-claimed? fixture))
        (swap! results conj {:fixture-id   (:fixture/id fixture)
                             :fname        fname
                             :skipped?     true
                             :reason       "spec-version not in claimed set"
                             :spec-version (:fixture/spec-version fixture)})

        (not (runnable-capability-set? fixture))
        (swap! results conj {:fixture-id   (:fixture/id fixture)
                             :fname        fname
                             :skipped?     true
                             :reason       "capabilities outside schemas-runner claim"
                             :capabilities (:fixture/capabilities fixture)})

        :else
        (swap! results conj (assoc (run-fixture fixture) :fname fname))))
    (let [all     @results
          run     (remove :skipped? all)
          passed  (filter :passed? run)
          failed  (remove :passed? run)
          skipped (filter :skipped? all)]
      ;; Silent-on-success (rf2-try1x): summary prints only on failure.
      (when (seq failed)
        (println)
        (println "Schemas conformance corpus (schema-*.edn + error-schema-failure.edn):")
        (println "  total fixtures:" (count all))
        (println "  runnable:      " (count run))
        (println "  passed:        " (count passed))
        (println "  failed:        " (count failed))
        (println "  skipped:       " (count skipped))
        (when (seq skipped)
          (println)
          (println "Skipped:")
          (doseq [s skipped]
            (println "  " (:fixture-id s) "—" (:reason s)
                     (or (:capabilities s) (:spec-version s) (:error s)))))
        (println)
        (println "Failures:")
        (doseq [f failed]
          (println "  " (:fixture-id f))
          (when (:error f)
            (println "    error:" (:error f)))
          (when-let [td (:expected-db f)]
            (when (not= td (:final-db f))
              (println "    expected app-db:" td)
              (println "    actual   app-db:" (:final-db f))))
          (doseq [sc (:sub-checks f)]
            (when (not= (:expected sc) (:actual sc))
              (println "    sub" (:query sc)
                       "expected:" (:expected sc)
                       "actual:" (:actual sc))))
          (doseq [tf (:trace-failures f)]
            (println "    trace:" tf))))
      (is (zero? (count failed))
          (str "All claim-runnable schemas conformance fixtures must pass; "
               (count failed) " failed.")))))
