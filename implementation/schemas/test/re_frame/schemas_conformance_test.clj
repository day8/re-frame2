(ns re-frame.schemas-conformance-test
  "Per rf2-2l08g (audit rf2-x8x4p §TE5). Drives every
  `spec/conformance/fixtures/schema-*.edn` fixture (plus
  `error-schema-failure.edn`) through the live runtime — `reg-app-schema`,
  `validate-app-schema!`, the `:schemas/validate-event!` /
  `:schemas/validate-sub!` late-bind hooks, the EP-0017 recordable-cofx
  `:rf.error/cofx-value-invalid` path, and the
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
    3. Wires `:fixture/registry :app-schemas` into `reg-app-schema`
       (rf2-cq1ak — the fixture key is plural; app-db schemas are NOT
       a registrar kind).
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
  `:schemas/cofx` (rf2-hqwki4 — the landed EP-0017 recordable-cofx
  `:schema` path; a declared recordable value that fails its
  registration's `:schema` emits `:rf.error/cofx-value-invalid` and skips
  the handler), `:schemas/sub-return` (rf2-wcam), plus the bare `:core/*`
  capabilities every schema fixture cross-cuts (event / sub / error /
  trace).

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
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            ;; Per rf2-v96fh (schema implies validation) requiring
            ;; `re-frame.schemas` above already loads `re-frame.schemas.malli`
            ;; for its ns-load side effect (publishes the validate/explain
            ;; late-bind hooks), so the default validator is LIVE — the
            ;; conformance corpus's Malli-backed outcomes hold without this
            ;; explicit require. The require is kept as a harmless, explicit
            ;; statement of the Malli dependency (rf2-a5kzs finding 4).
            [re-frame.schemas.malli]
            [re-frame.schemas.test-fixture :as tf]
            [re-frame.subs :as subs]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.trace :as trace]))

(use-fixtures :each tf/reset-runtime)

;; ---- fixture discovery ----------------------------------------------------

(def fixtures-dir
  "The conformance corpus lives at the repo root under
  `spec/conformance/fixtures/`.

  Anchored to a CLASSPATH RESOURCE, not the working directory (rf2-ywrwkl,
  the same fix rf2-55j4s3 applied to 3 sibling core tests). The earlier
  `(io/file \"../../spec/conformance/fixtures\")` form assumed the JVM cwd
  was `implementation/schemas/` so that `../../` reached the repo root. That
  holds for the canonical per-artefact gate (`clojure -M:test` run from
  `implementation/schemas/`, which is what CI runs) but SILENTLY MIS-SCOPES
  under the combined `implementation/deps.edn :test` alias: run from
  `implementation/`, `../../` resolves ABOVE the repo root, `file-seq`
  returns nothing, and the corpus discovers zero fixtures (the rf2-3hamsq
  floor turns that mis-discovery RED instead of silent-green).

  This test namespace's own source file is on the test classpath (the
  artefact's `:test {:extra-paths [\"test\"]}`), so resolving it via
  `io/resource` pins the anchor to the on-disk source location regardless
  of cwd or which alias loaded the namespace. Walking five parents
  (`schemas_conformance_test.clj → re_frame → test → schemas →
  implementation → repo root`) reaches the repo root, then we descend into
  `spec/conformance/fixtures`."
  (let [res (io/resource "re_frame/schemas_conformance_test.clj")]
    (assert res
            (str "schemas-conformance-test cannot locate its own source on "
                 "the classpath — the schemas test/ dir must be on the test "
                 "classpath for fixture discovery to anchor."))
    (-> (io/file res)        ; .../schemas/test/re_frame/schemas_conformance_test.clj
        .getParentFile       ; .../schemas/test/re_frame
        .getParentFile       ; .../schemas/test
        .getParentFile       ; .../schemas
        .getParentFile       ; .../implementation
        .getParentFile       ; repo root
        (io/file "spec" "conformance" "fixtures")
        .getCanonicalFile)))

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
  ;; :schemas/cofx — CLAIMED (rf2-hqwki4): the EP-0017 recordable-cofx
  ;; `:schema` path has landed (`re-frame.cofx/validate-recordable-value!`,
  ;; reached from `deliver-declared-cofx` for both supplied/replayed and
  ;; generated values). A declared recordable value that fails its
  ;; registration's `:schema` emits `:rf.error/cofx-value-invalid` and skips
  ;; the handler. The `schema-cofx-validates.edn` fixture now exercises THAT
  ;; landed path (the old inject-cofx injection-time validation it pinned —
  ;; `:rf.error/schema-validation-failure :where :cofx` — was retired with
  ;; `inject-cofx`).
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
   ;; EP-0001 (rf2-adwcv6): write the app-db PARTITION via swap-frame-db! —
   ;; app-db-container is now a read-only projection over the one physical
   ;; frame-state container.
   :write-db! (fn [frame-id new-db]
                (frame/swap-frame-db! frame-id (constantly new-db)))
   :dispatch! (fn [event frame-id] (rf/dispatch event {:frame frame-id}))})

(defn- collect-cofx-keys
  "Walk DSL body steps and collect every cofx-id referenced via
  `[:cofx-key K]`. Used to auto-wire generated cofx interceptors under
  event metadata `:interceptors` per the rf2-g25p convention.

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

(defn- realise-cofx-supplier
  "DSL → a value-returning cofx supplier `(fn [] value)` (EP-0017 model,
  rf2-hqwki4). Mirrors core's runner: a `:set` step's value IS the value the
  supplier returns; the runtime delivers it FLAT under the cofx-id when a
  handler declares it via `:rf.cofx/requires`. The ctx→ctx `inject-cofx` form
  that placed the value at `[:coeffects cofx-id]` is RETIRED with
  `inject-cofx`.

  Per rf2-g25p the `:set` value passes through `eval-value*`; multiple `:set`
  steps run in order and the final step wins (single-delivery convention)."
  [steps]
  (fn []
    (let [eval-value (requiring-resolve 're-frame.conformance/eval-value*)]
      (reduce (fn [v step]
                (case (first step)
                  :set  (let [[_ _path value] step]
                          (eval-value value {}))
                  :noop v
                  v))
              nil
              steps))))

(defn- normalize-event-handler
  "Collapse the DSL `[body-shape handler]` pair `conformance/realise-event-
  handler` returns into the single EP-0018 `reg-event` shape — a
  `(cofx-in → effects-map-or-nil)` fn.

  `body-shape` is a DSL-INTERNAL interpreter distinction, NOT a public
  `:event/kind` (EP-0018 removed the public event sub-kind model: a registered
  event is just kind `:event`). An `:event-db` body is `(fn [db event] new-db)`;
  it is lifted to `(fn [cofx event] {:db (handler db event)})` — read db from
  the coeffects, lower the returned db into a `{:db …}` effect (same observable
  behaviour). An `:event-fx` body is already the single form and passes
  through. Registering through this normaliser keeps the registration site free
  of any kind branch."
  [[body-shape handler]]
  (case body-shape
    :db (fn [{:keys [db]} event] {:db (handler db event)})
    :fx handler))

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
        ;; cofx that auto-wire onto a consuming event's `:rf.cofx/requires`
        ;; declaration (EP-0017 model — rf2-hqwki4). Stable lex order on
        ;; cofx-id so the last-write-wins outcome is deterministic.
        cofx-by-key
        (->> cofx-registry
             (sort-by key)
             (group-by (fn [[cofx-id _]] (keyword (namespace cofx-id))))
             (reduce-kv (fn [acc k pairs]
                          (assoc acc k (mapv first pairs)))
                        {}))]
    ;; ---- cofx ----------------------------------------------------------
    ;; EP-0017 model (rf2-hqwki4): cofx registrations carry value-returning
    ;; suppliers (NOT ctx→ctx injection handlers) plus their metadata
    ;; (`:recordable?` / `:provided?` / `:schema`). The runtime delivers the
    ;; recordable value flat under the cofx-id when a handler declares it via
    ;; `:rf.cofx/requires`, and validates it against `:schema`
    ;; (`re-frame.cofx/validate-recordable-value!`, a production hard error on
    ;; mismatch → `:rf.error/cofx-value-invalid`). A `:provided?` recordable
    ;; fact carries NO supplier — its value rides the dispatch token's
    ;; `:rf.cofx` map; `reg-cofx` rejects `:provided?` + a supplier, so register
    ;; it bare. The old `inject-cofx`-time `:schema` validation path is retired
    ;; with `inject-cofx`.
    (let [all-cofx-ids (into #{} (concat (keys cofx-bodies) (keys cofx-registry)))]
      (doseq [cofx-id all-cofx-ids]
        (let [body (get cofx-bodies cofx-id [[:noop]])
              meta (get cofx-registry cofx-id {})]
          (if (:provided? meta)
            (rf/reg-cofx cofx-id meta)
            (rf/reg-cofx cofx-id meta (realise-cofx-supplier body))))))
    ;; ---- events --------------------------------------------------------
    ;; Per Spec 010 §step 1 (rf2-jwm4): event meta carries :schema; the
    ;; runtime calls `:schemas/validate-event!` before the handler runs.
    ;; EP-0017 model (rf2-hqwki4): a body that reads `[:cofx-key K]` declares
    ;; the consumed coeffect ids via the `:rf.cofx/requires` registration-
    ;; metadata key (the ctx→ctx `inject-cofx` interceptor wiring is retired).
    ;; The runtime delivers each declared recordable value flat under its
    ;; cofx-id and validates it against `:schema`.
    ;; EP-0018 Slice Z: there is ONE public event registration form —
    ;; `reg-event`, a `(cofx-in → effects-map-or-nil)` handler. There is no
    ;; public event sub-kind axis. `conformance/realise-event-handler` returns a
    ;; `[body-shape handler]` pair where `body-shape` is a DSL-INTERNAL
    ;; interpreter distinction (does the body read cofx / emit fx → event-fx
    ;; body-shape, else event-db body-shape) — NOT a public `:event/kind`.
    ;; `normalize-event-handler` collapses both DSL body-shapes to the single
    ;; effects-map handler so the registration site below never branches on a
    ;; kind: an event-db body `(fn [db event] new-db)` is lifted to
    ;; `(fn [cofx event] {:db …})` (read db from the coeffects, lower the
    ;; returned db into a `{:db …}` effect — same observable behaviour); an
    ;; event-fx body is already the single form and passes through.
    (doseq [[id steps] (:event hmap)]
      (let [handler  (normalize-event-handler
                       (conformance/realise-event-handler steps))
            meta     (get event-meta id {})
            ks       (collect-cofx-keys steps)
            cofx-ids (vec (mapcat (fn [k]
                                    (or (get cofx-by-key k)
                                        (when (contains? cofx-registry k) [k])))
                                  ks))
            meta'    (cond-> meta
                       (seq cofx-ids)
                       (assoc :rf.cofx/requires cofx-ids))]
        (if (seq meta')
          (rf/reg-event id meta' handler)
          (rf/reg-event id handler))))
    ;; ---- subs ----------------------------------------------------------
    ;; Per Spec 010 §step 6 (rf2-wcam): sub meta carries :schema; the
    ;; runtime calls `:schemas/validate-sub!` after each compute.
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
  ;; Per rf2-cq1ak the fixture key is `:app-schemas` (plural) — app-db
  ;; schemas are NOT a registrar kind.
  (doseq [[path schema] (get-in fixture [:fixture/registry :app-schemas])]
    (rf/reg-app-schema path {:schema schema})))

;; ---- trace capture -------------------------------------------------------

(defn- collect-traces
  "Register a trace listener for the fixture's run; the returned atom
  accumulates every captured trace event."
  [fixture-id]
  (let [traces (atom [])]
    (trace/register-listener! [fixture-id]
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

(defn- run-dispatch
  "Drive one `:fixture/dispatches` entry. `dispatch-error-failures` is an atom
  collecting `:expect-error` mismatch strings.

  A map entry with `:expect-error` (EP-0017 boundary-throw shape, rf2-hqwki4)
  asserts the dispatch RAISES that `:rf.error/id`. Context-assembly throws (the
  cofx delivery errors — `:rf.error/cofx-value-invalid` / missing-required /
  unregistered) escape `dispatch-sync` rather than being captured into the
  interceptor chain, so the runner catches the throw here and compares the
  ex-data `:rf.error/id`. The remaining opts (e.g. `:rf.cofx`) pass through to
  `dispatch-sync`. Mirrors core's `re-frame.conformance-test` runner."
  [ev dispatch-error-failures]
  (cond
    (and (map? ev) (contains? ev :expect-error))
    (let [{event :event want :expect-error} ev
          opts (dissoc ev :event :expect-error)
          got  (try (rf/dispatch-sync event opts) ::no-throw
                    (catch clojure.lang.ExceptionInfo e
                      (:rf.error/id (ex-data e)))
                    (catch Throwable e e))]
      (cond
        (= got ::no-throw)
        (swap! dispatch-error-failures conj
               (str "dispatch " (pr-str event) " expected to throw "
                    want " but did not throw"))
        (not= got want)
        (swap! dispatch-error-failures conj
               (str "dispatch " (pr-str event) " expected error " want
                    " but got " (if (instance? Throwable got)
                                  (str "a non-ExceptionInfo throw: "
                                       (some-> ^Throwable got .getMessage))
                                  got)))))

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
          ;; will trigger validate-app-schema! against the new slate.
          _            (realise-app-schemas fixture)
          ;; EP-0002 (rf2-5q7um6): the shared `tf/reset-runtime` pins
          ;; `*current-frame* :rf/default` for the body. Unbind it around
          ;; `reg-frame` so the fixture's `:on-create` cascade fires
          ;; SYNCHRONOUSLY — `frame/reg-frame` async-queues on-create when
          ;; `*current-frame*` is bound (Spec 002 §reg-frame from inside a
          ;; handler), which would land the seed AFTER the first dispatch.
          _            (binding [frame/*current-frame* nil]
                         (rf/reg-frame :rf/default frame-config))
          dispatches   (or (:fixture/dispatches fixture) [])
          ;; EP-0017 `:expect-error` mismatches (rf2-hqwki4) — a context-assembly
          ;; throw the dispatch declared but did not raise (or raised wrong).
          dispatch-error-failures (atom [])]
      (doseq [ev dispatches]
        (run-dispatch ev dispatch-error-failures))
      (let [expect        (or (:fixture/expect fixture) {})
            expected-db   (:final-app-db expect)
            final-db      (rf/app-db-value :rf/default)
            sub-checks
            (doall
              (for [[query-v expected-val] (or (:sub-values expect) {})]
                (let [[frame-id qv] (resolve-sub query-v)]
                  {:query    query-v
                   :expected expected-val
                   :actual   (rf/subscribe-once frame-id qv)})))
            trace-failures (check-trace-emissions @traces
                                                  (:trace-emissions expect))]
        (trace/clear-listeners!)
        {:fixture-id     fid
         :passed?        (and (or (nil? expected-db) (submap? expected-db final-db))
                              (every? #(= (:expected %) (:actual %)) sub-checks)
                              (empty? trace-failures)
                              (empty? @dispatch-error-failures))
         :dispatch-error-failures @dispatch-error-failures
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
      ;; rf2-3hamsq — non-empty floor. A lone (zero? (count failed))
      ;; passes GREEN over an empty / fully-skipped / orphaned corpus
      ;; (a wrong cwd, a fixtures-dir rename, or a capability-vocab
      ;; rename that moves every fixture out of claim) — the gate then
      ;; verifies NOTHING. Assert that fixtures actually executed:
      ;;   - (pos? (count run)) catches the fully-empty case;
      ;;   - the expected-minimum (>= 4) catches partial mass-orphaning
      ;;     without pinning an exact count (today's runnable count is 5:
      ;;     the four Spec 010 validation points + error-schema-failure).
      (is (pos? (count run))
          "at least one claim-runnable schemas conformance fixture must have executed")
      (is (>= (count run) 4)
          (str "schemas corpus runnable-fixture floor (>= 4): only "
               (count run) " executed — a fixtures-dir/cwd fault or a "
               "capability-vocab rename has orphaned the corpus."))
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
            (println "    trace:" tf))
          (doseq [df (:dispatch-error-failures f)]
            (println "    dispatch-error:" df))))
      (is (zero? (count failed))
          (str "All claim-runnable schemas conformance fixtures must pass; "
               (count failed) " failed.")))))
