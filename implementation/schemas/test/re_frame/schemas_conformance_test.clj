(ns re-frame.schemas-conformance-test
  "Drives every `spec/conformance/fixtures/schema-*.edn` fixture (plus
  `error-schema-failure.edn`) through the live runtime — `reg-app-schema`,
  `validate-app-schema!`, the `:schemas/validate-event!` /
  `:schemas/validate-sub!` late-bind hooks, the EP-0017 recordable-cofx
  `:rf.error/cofx-value-invalid` path, and the
  `:rf.error/schema-validation-failure` trace contract — and asserts
  the conformance-corpus's recorded outcome against what the artefact
  actually produces.

  This is the schemas artefact's conformance gate. It keeps fixture failures
  local to the artefact while unit tests cover elision and validator details.

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
    3. Wires `:fixture/registry :app-schemas` into `reg-app-schema`;
       app-db schemas are not registrar entries.
    4. Registers the default frame with the fixture's
       `:fixture/frame-config` (e.g. `:initial-events [[:init]]`).
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
  would be redundant and slow the gate."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.conformance :as rf.conformance]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Load-bearing beyond its alias: loading the facade is what
            ;; publishes the Malli validate/explain hooks (rf2-v96fh) and
            ;; binds core's `reg-app-schema` re-export through late-bind.
            ;; clj-kondo reports the ALIAS unused here; the require is not.
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.subs :as rf.subs]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

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

(defn- read-one-form
  "Read `text` as EXACTLY ONE top-level EDN form, or throw. `read-string`
  returns only the FIRST and silently discards the rest, so a fixture whose
  expectation block closes early passes having verified less than it claims
  (rf2-5mr6). Throws rather than returning `:fixture/load-error`, which the
  runner classifies as a SKIP — as silent as the defect. Full rationale on
  `re-frame.conformance-test/read-one-form` (rf2-98ni)."
  [text fixture-name]
  (let [eof  (Object.)
        rdr  (java.io.PushbackReader. (java.io.StringReader. text))
        fail (fn [why data]
               (throw (ex-info (str "conformance fixture " fixture-name " " why
                                    " (rf2-98ni, rf2-5mr6)")
                               (assoc data :fixture/file fixture-name))))
        rd   (fn []
               (try (edn/read {:eof eof} rdr)
                    (catch Exception e
                      (fail (str "is not readable EDN: " (.getMessage e))
                            {:fixture/reader-error (.getMessage e)}))))
        form (rd)]
    (when (identical? eof form)
      (fail "holds no top-level EDN form" {:fixture/forms 0}))
    (when-not (identical? eof (rd))
      (fail (str "must hold exactly ONE top-level EDN form — a plain read"
                 " returns the first and silently discards the rest")
            {:fixture/forms :more-than-one}))
    form))

(defn- load-fixture
  "Read one EDN fixture as EXACTLY ONE top-level EDN form, or throw.

  The fixture text is parsed VERBATIM. Schema fixtures carry no
  auto-resolved `::name` keywords, and the machines / ssr runners'
  `::name` -> `:rf.machine.timer/name` rewrite has no business here: it
  resolves nothing in the fixture's own namespace; it merely restamps the
  token with an unrelated machines-timer identity, which could make a
  future schema fixture pass or fail under a false id. If schema fixtures
  ever need an alias facility, the conformance format should define one
  explicitly (rf2-6r9j.49)."
  [file]
  (read-one-form (slurp file) (.getName file)))

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
  {:read-db!  (fn [frame-id] (rf.frame/frame-app-db-value frame-id))
   ;; EP-0001 (rf2-adwcv6): write the app-db PARTITION via swap-frame-db! —
   ;; app-db-container is now a read-only projection over the one physical
   ;; frame-state container.
   :write-db! (fn [frame-id new-db]
                (rf.frame/swap-frame-db! frame-id (constantly new-db)))
   :dispatch! (fn [event frame-id] (rf/dispatch event {:frame frame-id}))})

;; Handler-body realisation reuses the SHARED primitives owned by
;; `re-frame.conformance` (core/src, already on this artefact's classpath):
;;   - `collect-cofx-keys`       — walk steps, pull `[:cofx-key K]` refs
;;   - `realise-cofx-supplier`   — DSL body → value-returning cofx supplier
;;   - `normalize-event-handler` — collapse the `[body-shape handler]` pair
;;                                 into the single `reg-event` form
;; Migrated off private copies per rf2-wy414k. Only the schemas-specific
;; wiring below (adapter helpers, app-schema registration, fixture discovery,
;; capability claims, the execution loop, reporting) stays local.

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
            (rf/reg-cofx cofx-id meta (rf.conformance/realise-cofx-supplier body))))))
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
      (let [handler  (rf.conformance/normalize-event-handler
                       (rf.conformance/realise-event-handler steps))
            meta     (get event-meta id {})
            ks       (rf.conformance/collect-cofx-keys steps)
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
      (let [{:keys [kind inputs body]} (rf.conformance/realise-sub steps)
            meta                       (get sub-meta id {})]
        (case kind
          :layer-1 (if (seq meta) (rf/reg-sub id meta body) (rf/reg-sub id body))
          ;; Use the fn-form `subs/reg-sub` — the public `rf/reg-sub`
          ;; is a JVM macro (Spec 001 §Source-coordinate capture); a
          ;; macro var isn't `apply`-able.
          :layer-2 (apply rf.subs/reg-sub id
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
              handler (rf.conformance/realise-fx-handler id body helpers)]
          (rf/reg-fx id (assoc meta :handler-fn handler) handler))))
    ;; NOTE: app-schemas are intentionally NOT registered here — see
    ;; `realise-app-schemas` below. Per rf2-wkxng / rf2-6m0se,
    ;; `destroy-frame!` now drops the frame's app-db schemas
    ;; (parity with the machines / SSR / privacy destroy hooks), so
    ;; schema registration must follow the runner's destroy+make-frame
    ;; cycle. Event / sub / cofx / fx registrations are global on
    ;; the registrar and survive destroy-frame!, so they continue to
    ;; live here so `:initial-events` can fire against them.
    nil))

(defn- realise-app-schemas
  "Register the fixture's app-db schemas. Called AFTER the runner's
  destroy+make-frame cycle so the new frame's slate carries exactly
  the fixture's declarations and nothing else.

  Per rf2-wkxng / rf2-6m0se the destroy step now drops every schema
  registered against the frame (parity with the machines / SSR /
  privacy destroy hooks). Pre-fix the runner relied on the leak —
  registering app-schemas inside `realise-handlers` BEFORE
  `destroy-frame!` and counting on the schemas to survive. With the
  leak closed, app-schema registration is sequenced explicitly
  after `make-frame`."
  [fixture]
  ;; Per rf2-cq1ak the fixture key is `:app-schemas` (plural) — app-db
  ;; schemas are NOT a registrar kind.
  (doseq [[path schema] (get-in fixture [:fixture/registry :app-schemas])]
    (rf/reg-app-schema path schema)))

;; ---- matchers ------------------------------------------------------------
;;
;; The expectation matchers are the SHARED primitives owned by
;; `re-frame.conformance` (core/src) — `submap?` (recursive submap match on
;; `:final-app-db` / trace tags) and `check-trace-emissions` (order-preserving
;; partial trace subset). Migrated off private copies per rf2-wy414k; the
;; schemas-specific expectation wiring (which matchers to run against which
;; fixture slice) stays local in `run-fixture` below.

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
;;
;; `:sub-values` query resolution (`[query-v]` implicit-frame vs
;; `[frame-id [query-v]]` explicit-frame) is the shared `conformance/resolve-sub`
;; primitive (core/src) — migrated off a private copy per rf2-wy414k.

(defn- run-fixture
  "Run one fixture; return a result map shaped like the core runner's."
  [fixture]
  (try
    (with-trace-recorder! [traces]
      (let [fid          (:fixture/id fixture)
            _            (realise-handlers fixture)
            frame-config (or (:fixture/frame-config fixture) {})
            ;; `reset-runtime` already created :rf/default WITHOUT any
            ;; :initial-events. `make-frame` against an existing id is a
            ;; surgical update that does NOT re-fire :initial-events (Spec 002).
            ;; Destroy first so the fixture's :initial-events cascade fires
            ;; under its declared frame config.
            _            (rf/destroy-frame! :rf/default)
            ;; Per rf2-wkxng / rf2-6m0se: register app-db schemas
            ;; AFTER the destroy step (the new
            ;; `:schemas/on-frame-destroyed!` hook drops the frame's
            ;; schemas on destroy, so registering them BEFORE the
            ;; destroy would leak them through). Schemas must also
            ;; precede `make-frame` so the :initial-events cascade fires
            ;; with the schemas in place — the initial-events' db commit
            ;; will trigger validate-app-schema! against the new slate.
            _            (realise-app-schemas fixture)
            ;; EP-0002 (rf2-5q7um6): the shared `tf/reset-runtime` pins
            ;; `*current-frame* :rf/default` for the body. Unbind it around
            ;; `make-frame` so the fixture's `:initial-events` cascade fires
            ;; SYNCHRONOUSLY — the frame engine async-queues initial-events when
            ;; `*current-frame*` is bound (Spec 002 §make-frame from inside a
            ;; handler), which would land the seed AFTER the first dispatch.
            _            (binding [rf.frame/*current-frame* nil]
                           (rf/make-frame (assoc frame-config :id :rf/default)))
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
                  (let [[frame-id qv] (rf.conformance/resolve-sub :rf/default query-v)]
                    {:query    query-v
                     :expected expected-val
                     :actual   (rf/subscribe-once qv {:frame frame-id})})))
              trace-failures (rf.conformance/check-trace-emissions
                               @traces (:trace-emissions expect))]
          {:fixture-id     fid
           :passed?        (and (or (nil? expected-db) (rf.conformance/submap? expected-db final-db))
                                (every? #(= (:expected %) (:actual %)) sub-checks)
                                (empty? trace-failures)
                                (empty? @dispatch-error-failures))
           :dispatch-error-failures @dispatch-error-failures
           :final-db       final-db
           :expected-db    expected-db
           :sub-checks     sub-checks
           :trace-failures trace-failures})))
    (catch Throwable e
      {:fixture-id (:fixture/id fixture)
       :passed?    false
       :error      (.getMessage e)
       :exception  e})))

;; ---- loader regression ---------------------------------------------------

(deftest loader-does-not-restamp-auto-resolved-keywords
  ;; rf2-6r9j.49. The machines / ssr runners rewrite `::name` to
  ;; `:rf.machine.timer/name` for their synthetic timer events. This runner
  ;; carried a copy "for symmetry"; on a schemas fixture it would not have
  ;; RESOLVED the token; it would have restamped it with an unrelated
  ;; namespace, so a future fixture could pass or fail under a false id.
  ;; The loader now parses verbatim, so the token is REJECTED instead,
  ;; which is acceptable until the conformance format defines such syntax.
  (let [f (java.io.File/createTempFile "rf2-schemas-conformance-probe" ".edn")]
    (try
      (spit f "{:fixture/id :probe/x :k ::probe}")
      (let [outcome (try {:form (load-fixture f)}
                         (catch Exception e {:threw (.getMessage e)}))]
        (is (not= :rf.machine.timer/probe (-> outcome :form :k))
            "a bare `::probe` must never arrive as a machines-timer keyword")
        (is (contains? outcome :threw)
            "an auto-resolved keyword is not EDN, so the loader rejects it"))
      (finally (.delete f)))))

;; ---- the test entrypoint -------------------------------------------------

(deftest run-schemas-conformance-corpus
  (let [results (atom [])]
    (doseq [[fname fixture] (all-schemas-fixtures)]
      ;; There is no load-error arm. `load-fixture` THROWS on an
      ;; unreadable / empty / multi-form fixture (rf2-98ni, rf2-5mr6), so a
      ;; parse failure escapes `all-schemas-fixtures` before this loop and
      ;; fails the gate, rather than disappearing as a skip (rf2-6r9j.48).
      (cond
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
                     (or (:capabilities s) (:spec-version s)))))
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
