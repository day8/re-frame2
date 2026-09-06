(ns re-frame.ssr-conformance-test
  "Per rf2-i3qc0 (audit rf2-asmj1 §TC4). Drives every
  `spec/conformance/fixtures/ssr-*.edn` fixture through the live ssr
  runtime — `render-to-string`, the `:rf/hydrate` event, the `:rf.server/*`
  fx family, the per-request response accumulator, `reg-head` /
  `render-head` / `active-head`, and the default error projector — and
  asserts the conformance-corpus's recorded outcome against what the
  artefact actually produces.

  This is the ssr artefact's own conformance gate. Pre-rf2-i3qc0 the
  ssr fixtures rode the CORE artefact's `re-frame.conformance-test`
  (rf2-d0wem patterned for machines; analogous gap for ssr). Two
  drawbacks:

    1. Core's runner doesn't implement the SSR-specific assertion
       channels — `:ssr/active-head`, `:ssr/request-result`,
       `:ssr/rendered-head-contains`, `:ssr/html-attr-present`,
       `:trace-not-emitted`. Fixtures that asserted ONLY through those
       channels passed silently because the matcher was absent.
    2. The gate ran at the wrong artefact. An ssr-touching PR could
       break a fixture and only surface at core's gate — at which
       point the failure has to be triaged across two artefacts.

  This namespace closes both gaps. It runs at the ssr artefact's gate
  (so an ssr regression fails the ssr CI step), and it implements the
  SSR-specific matchers (so the corpus's hydration / head / response /
  projector contracts are checked against the live runtime).

  ## What this runner does

  For each `ssr-*.edn` fixture:

    1. Resets the runtime (registrar, frames, side-channel atoms,
       ns-load-time registrations) via `re-frame.ssr.test-fixture`.
    2. Realises the fixture's `:fixture/handlers` (event, sub, fx, view,
       head) into native fns via the `re-frame.conformance` DSL
       interpreter (a re-use of core's pre-existing helpers; the
       interpreter is in `core/src` so the ssr artefact has it on the
       classpath without pulling core's test tree).
    3. Registers routes from `:fixture/registry :route`.
    4. Registers the default frame with the fixture's `:fixture/frame-config`
       (including the `:platform :server` and `:ssr {:public-error-id ...}`
       config the projector / fx-gating need).
    5. Drives `:fixture/dispatches` through `rf/dispatch-sync` — the
       `:rf/hydrate` events carry `:source :ssr-hydration` per Spec 011
       §The :rf/hydrate event.
    6. Runs any `:fixture/calls` (e.g. `:render-to-string`) as pure
       function assertions.
    7. Simulates a post-hydrate client render hash via
       `re-frame.ssr/verify-hydration!` when the fixture declares
       `:fixture/render-after-hydrate`.
    8. Drains any buffered error traces through the active projector
       (`apply-error-projection!`) so the response accumulator's
       `:status` reflects the projector's verdict.
    9. Asserts each of:
       - `:final-app-db` (submap match — partial expectations on
         nested slices work the same way),
       - `:sub-values`,
       - `:trace-emissions` (partial match, order-preserving subset
         per `spec/conformance/README.md` §Fixture lifecycle),
       - `:trace-not-emitted` (no captured trace matches any expected
         not-trace shape),
       - `:ssr/public-error` (last error trace projected via
         `project-error`),
       - `:ssr/active-head` (the head model produced by the active
         route's head fn),
       - `:ssr/request-result` (the resolved per-request response
         accumulator via `get-response`),
       - `:ssr/rendered-head-contains` (substring assertions on
         `head-model->html`),
       - `:ssr/html-attr-present` (the rendered root element carries
         the named data-attribute).

  ## Posture split (rf2-lwtlk / rf2-76gom)

  This runner executes under BOTH `clojure -M:test` and the REAL
  production gate `-Dre-frame.debug=false`
  (`scripts/test-ssr-prod-gate.sh`), and the two postures do not observe
  the same channels.

    - `:trace-emissions` / `:trace-not-emitted` read `@traces`, the DEV
      trace bus. Every emit site on it sits inside `interop/debug-enabled?`,
      a LOAD-TIME gate, so under `-Dre-frame.debug=false` the ring is empty
      for every fixture. Both channels are kept VERBATIM and adjudicated in
      dev posture only. The negative channel is the one that matters most
      here: `:trace-not-emitted` over an empty ring passes automatically —
      it would hold with the runtime removed — so leaving it outside the
      guard would have been a false green, not extra coverage.

    - The `run-start` half of `:trace-emissions` DOES have a
      production-visible counterpart, and it is used rather than skipped. A
      `{:operation :rf.event/run-start :tags {:rf.trace/event-id X}}` claim
      says event X RAN, and the always-on `:events` substrate (Spec 009
      §Event-emit listener) records exactly that, in production.
      `check-always-on-events` adjudicates those claims under BOTH postures;
      an event that never ran reds it. Nine of the fifteen fixtures depend
      on this for their only trace-shaped coverage under the gate.

    - `:ssr/public-error` sources its error event from whichever axis
      carries it — dev bus first (a superset in dev), else the always-on
      `:errors` axis, synthesised into the projector's envelope by
      `error-record->trace-event`. Sourcing it from `@traces` ALONE
      reported `:actual nil` under the gate for every fixture whatever the
      framework did (rf2-76gom); the always-on axis is the projector's
      production status source of truth, so under the gate this channel now
      adjudicates the wire.

    - Every other channel — `:final-app-db`, `:sub-values`,
      `:ssr/active-head`, `:ssr/request-result`,
      `:ssr/rendered-head-contains`, `:ssr/html-attr-present`,
      `:fixture/calls`, `:expect-error` — is posture-independent and runs
      under the gate unchanged.

  Two claims in the corpus have NO production counterpart and are dev-only
  by design: `:rf.ssr/hydration-mismatch` (emitted solely through
  `trace/emit-error!`, `hydrate.cljc`) and the `:source :ssr-hydration`
  slot on a `run-start` trace (the always-on event record carries no
  `:source`). Both remain asserted verbatim in the dev arm.

  ## Capability claim

  Per `spec/conformance/README.md` §Capability tagging, the runner
  declares the surface its host implements. A fixture whose
  `:fixture/capabilities` are NOT a subset of `claimed-capabilities`
  is reported as out-of-claim and does not block the suite.

  The claim here covers the ssr surface plus the bare `:core/*`
  capabilities every ssr fixture cross-cuts (event / sub / fx /
  error). Routing's `:routing/match-url` is claimed because
  `ssr-error-known-mapping.edn` exercises a no-such-route URL — the
  routing artefact is a test-only dep and is reloaded by the shared
  reset fixture.

  ## Coverage scope

  Only `ssr-*.edn` fixtures. The Mode B machines runner
  (`machines_conformance_test`) and the core artefact's full-corpus
  runner own everything else; running them again here would be
  redundant and would slow the gate."
  (:require [clojure.set]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.conformance :as rf.conformance]
            [re-frame.core :as rf]
            [re-frame.events :as rf.events]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.head :as rf.ssr.head]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.subs :as rf.subs]
            [re-frame.trace :as rf.trace]))

;; The shared reset fixture is `:each` — every fixture in the corpus
;; runs against a clean registrar / frame table / side-channel slot.
(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; ---- fixture discovery ----------------------------------------------------

(def fixtures-dir
  "The conformance corpus lives at the repo root under
  `spec/conformance/fixtures/`.

  Anchored to a CLASSPATH RESOURCE, not the working directory (rf2-ywrwkl,
  the same fix rf2-55j4s3 applied to 3 sibling core tests). The earlier
  `(io/file \"../../spec/conformance/fixtures\")` form assumed the JVM cwd
  was `implementation/ssr/` so that `../../` reached the repo root. That
  holds for the canonical per-artefact gate (`clojure -M:test` run from
  `implementation/ssr/`, which is what CI runs) but SILENTLY MIS-SCOPES
  under the combined `implementation/deps.edn :test` alias: run from
  `implementation/`, `../../` resolves ABOVE the repo root, `file-seq`
  returns nothing, and the corpus discovers zero fixtures (the rf2-3hamsq
  floor turns that mis-discovery RED instead of silent-green).

  This test namespace's own source file is on the test classpath (the
  artefact's `:test {:extra-paths [\"test\"]}`), so resolving it via
  `io/resource` pins the anchor to the on-disk source location regardless
  of cwd or which alias loaded the namespace. Walking five parents
  (`ssr_conformance_test.clj → re_frame → test → ssr → implementation →
  repo root`) reaches the repo root, then we descend into
  `spec/conformance/fixtures`."
  (let [res (io/resource "re_frame/ssr_conformance_test.clj")]
    (assert res
            (str "ssr-conformance-test cannot locate its own source on the "
                 "classpath — the ssr test/ dir must be on the test "
                 "classpath for fixture discovery to anchor."))
    (-> (io/file res)        ; .../ssr/test/re_frame/ssr_conformance_test.clj
        .getParentFile       ; .../ssr/test/re_frame
        .getParentFile       ; .../ssr/test
        .getParentFile       ; .../ssr
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
  "Read one EDN fixture, applying the same `::name` rewrite the JVM core
  runner uses so `clojure.edn/read-string` (no reader resolver) accepts
  auto-resolved keywords. Per rf2-lu3f."
  [file]
  (let [raw   (slurp file)
        fixed (str/replace raw #"::([a-zA-Z][a-zA-Z0-9_-]*)"
                           ":rf.machine.timer/$1")]
    (read-one-form fixed (.getName file))))

(defn- all-ssr-fixtures
  "Every fixture file whose name matches `ssr-*.edn`. Returns
  `[[filename fixture] ...]` in stable lex order."
  []
  (->> (file-seq fixtures-dir)
       (filter #(.isFile %))
       (filter #(let [n (.getName %)]
                  (and (str/starts-with? n "ssr-")
                       (str/ends-with? n ".edn"))))
       (sort-by #(.getName %))
       (mapv (fn [f] [(.getName f) (load-fixture f)]))))

;; ---- claimed capability + spec-version sets ------------------------------

(def claimed-capabilities
  "The ssr-surface plus the `:core/*` capabilities every ssr fixture
  cross-cuts. Routing's `:routing/match-url` is in the set because the
  `ssr-error-known-mapping` fixture exercises a no-such-route URL,
  which the routing artefact emits as `:rf.error/no-such-handler` —
  the trigger the runtime's default error projector maps to 404."
  #{:core/event-handler
    :core/sub
    :core/fx
    :core/error
    :ssr/render-to-string
    ;; rf2-5lqar2 — the render-tree canonical-traversal hash pin (011:386).
    :ssr/render-tree-hash
    :ssr/hydration
    :ssr/hydration-payload
    :ssr/response-contract
    :ssr/head-contract
    :ssr/error-projection
    ;; rf2-ojakd / rf2-olb64 (a) — streaming SSR primitive
    ;; (:rf/suspense-boundary) + chunked-HTTP wire shape.
    :ssr/suspense-boundary
    :ssr/chunked-response
    :routing/match-url})

(def claimed-spec-versions
  "Conformance corpus spec versions this runner claims to conform
  against. Matches the core runner's set at rf2-i3qc0 time."
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
;; artefact's classpath) lifts the DSL into native fns. The wiring here
;; mirrors the relevant slice of `re-frame.conformance-test/realise-handlers`
;; minus the surfaces the ssr fixtures never touch (cofx schema /
;; machines / flows). When a future fixture wants those it lands in
;; core's full-corpus runner; this gate covers the ssr lifecycle only.

(defn- realise-head-handler
  "A head body is `[[:return-head <model>]]` per the head fixtures.
  Lift to `(fn [_db _route] model)`."
  [steps]
  (let [step (first steps)]
    (when (and (vector? step) (= :return-head (first step)))
      (let [model (second step)]
        (fn [_db _route] model)))))

(defn- adapter-helpers
  "Helper map for `realise-fx-handler` — gives the fx-body DSL access
  to the frame's app-db via the substrate adapter and to the dispatch
  surface. Mirrors core's runner shape."
  []
  {:read-db!  (fn [frame-id] (rf.frame/frame-app-db-value frame-id))
   ;; EP-0001 (rf2-adwcv6): write the app-db PARTITION via swap-frame-db! —
   ;; app-db-container is now a read-only projection over the one physical
   ;; frame-state container.
   :write-db! (fn [frame-id new-db]
                (rf.frame/swap-frame-db! frame-id (constantly new-db)))
   :dispatch! (fn [event frame-id] (rf/dispatch event {:frame frame-id}))})

(defn- collect-cofx-keys
  "Walk DSL body steps and collect every cofx-id referenced via
  `[:cofx-key K]`. Used to auto-wire `:rf.cofx/requires` declarations per
  rf2-g25p convention (EP-0017 — `inject-cofx` is removed; a handler takes
  delivery by declaring the id, not by an injector interceptor)."
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
  of any kind branch, so the SSR runner can no longer mask fixture drift by
  accepting a pre-collapse db-kind fixture handler (rf2-hl4bdk)."
  [[body-shape handler]]
  (case body-shape
    :db (fn [{:keys [db]} event] {:db (handler db event)})
    :fx handler))

(defn- realise-handlers
  "Register every handler the fixture declares (events / subs / fxs /
  views / heads / cofxes / routes / app-schemas). Mirrors core's
  realise-handlers for the slice ssr fixtures actually use."
  [fixture]
  (let [hmap          (or (:fixture/handlers fixture) {})
        event-meta    (get-in fixture [:fixture/registry :event] {})
        sub-meta      (get-in fixture [:fixture/registry :sub] {})
        cofx-registry (get-in fixture [:fixture/registry :cofx] {})
        helpers       (adapter-helpers)]
    ;; ---- cofx -----------------------------------------------------------
    ;; EP-0017 distinguishes TWO cofx shapes; the SSR runner now exercises
    ;; both (rf2-sb47ni):
    ;;
    ;;   - AMBIENT value-returning cofx — `reg-cofx` takes a supplier
    ;;     `(fn [] value)`; the runtime runs it at context assembly and
    ;;     delivers the value flat under the cofx-id to a handler that
    ;;     declares `:rf.cofx/requires [cofx-id]`. The supplier returns the
    ;;     fixture's declared value (or nil for a bare `[[:noop]]`).
    ;;
    ;;   - PROVIDED recordable cofx (`{:provided? true …}`, typically
    ;;     `:recordable? true`) — a BOUNDARY-supplied fact with NO supplier;
    ;;     its VALUE rides the dispatch token flat under `:rf.cofx`, not a
    ;;     generator. Post-#4104 `reg-cofx` REJECTS `provided? true` + a
    ;;     supplier as `:rf.error/cofx-registration-invalid` (the shape
    ;;     rf2-xuhdni fixed in the core runner), so register WITHOUT one. A
    ;;     declared provided fact that is absent from the token throws
    ;;     `:rf.error/missing-required-cofx` at delivery. This mirrors the
    ;;     core runner so an SSR/hydration fixture can exercise the EP-0017
    ;;     replay contract: provided facts ride flat on the token, absence
    ;;     fails loudly (Spec 002 §Satisfaction algorithm + §The :rf.cofx
    ;;     envelope is the host-integration path SSR/hydration uses).
    (doseq [[cofx-id meta] cofx-registry]
      (if (:provided? meta)
        (rf/reg-cofx cofx-id meta)
        (let [body     (get-in hmap [:cofx cofx-id] [[:noop]])
              supplier (fn []
                         (reduce (fn [v step]
                                   (case (first step)
                                     :set (let [[_ _ sv] step] sv)
                                     v))
                                 nil
                                 body))]
          (rf/reg-cofx cofx-id meta supplier))))
    ;; ---- events --------------------------------------------------------
    ;; EP-0017: a handler takes delivery of a cofx by DECLARING it in the
    ;; `:rf.cofx/requires` registration metadata — not via an `inject-cofx`
    ;; interceptor (removed). Scan the body for `[:cofx-key K]` references and
    ;; auto-wire `:rf.cofx/requires [K …]` for every K with a registered cofx.
    ;; EP-0018 Slice Z: there is ONE public event registration form —
    ;; `reg-event`, a `(cofx-in → effects-map-or-nil)` handler. There is no
    ;; public event sub-kind axis. `conformance/realise-event-handler` returns a
    ;; `[body-shape handler]` pair where `body-shape` is a DSL-INTERNAL
    ;; interpreter distinction (event-db vs event-fx body) — NOT a public
    ;; `:event/kind`. `normalize-event-handler` collapses both DSL body-shapes
    ;; to the single effects-map handler so the registration site never branches
    ;; on a kind (rf2-hl4bdk).
    (doseq [[id steps] (:event hmap)]
      (let [handler   (normalize-event-handler
                        (rf.conformance/realise-event-handler steps))
            base-meta (get event-meta id {})
            ks        (collect-cofx-keys steps)
            cofx-ids  (vec (filter cofx-registry ks))
            meta      (cond-> base-meta
                        (seq cofx-ids) (assoc :rf.cofx/requires cofx-ids))]
        (if (seq meta)
          ;; FN form (nil provenance): a fixture overriding a framework
          ;; event id (e.g. :rf/hydrate) must REPLACE its source-store slot,
          ;; not collide at default-image assembly (rf2-h1vqa4).
          (rf.events/reg-event id meta handler)
          (rf.events/reg-event id handler))))
    ;; ---- subs ----------------------------------------------------------
    (doseq [[id steps] (:sub hmap)]
      (let [{:keys [kind inputs body]} (rf.conformance/realise-sub steps)
            meta                       (get sub-meta id {})]
        (case kind
          :layer-1 (if (seq meta) (rf/reg-sub id meta body) (rf/reg-sub id body))
          ;; Use the fn-form `subs/reg-sub` — the public `rf/reg-sub`
          ;; is a JVM macro (Spec 001 §Source-coordinate capture).
          ;; A declared dependency list rides the metadata map
          ;; (rf2-kuky.50), so the fixture's inputs go in as DATA.
          :layer-2 (rf.subs/reg-sub id (assoc meta :inputs (vec inputs)) body))))
    ;; ---- fxs -----------------------------------------------------------
    (let [fx-bodies   (:fx hmap)
          fx-registry (get-in fixture [:fixture/registry :fx] {})
          all-ids     (into #{} (concat (keys fx-bodies) (keys fx-registry)))]
      (doseq [id all-ids]
        (let [body    (get fx-bodies id [[:noop]])
              meta    (get fx-registry id {})
              handler (rf.conformance/realise-fx-handler id body helpers)]
          (rf/reg-fx id (assoc meta :handler-fn handler) handler))))
    ;; ---- views ---------------------------------------------------------
    (doseq [[id steps] (:view hmap)]
      (rf.registrar/register!
        :view id
        {:handler-fn (rf.conformance/realise-view-handler steps)}))
    ;; ---- heads ---------------------------------------------------------
    ;; The head ns's `reg-head` is the public registration surface;
    ;; here we wire the head handler-fn the fixture's :return-head body
    ;; declares.
    (doseq [[id steps] (:head hmap)]
      (when-let [hfn (realise-head-handler steps)]
        (rf/reg-head id hfn)))
    ;; ---- routes (also from :registry :route) ---------------------------
    (doseq [[id meta] (sort-by (comp str key)
                               (get-in fixture [:fixture/registry :route]))]
      ;; rf2-wvh95f F1: lift the path pattern into the 3-slot VALUE.
      (rf/reg-route id (dissoc meta :path) (:path meta)))
    ;; ---- app-schemas (rare on ssr fixtures, but covered) ---------------
    ;; Per rf2-cq1ak the fixture key is `:app-schemas` (plural) — app-db
    ;; schemas are NOT a registrar kind.
    (doseq [[path schema] (get-in fixture [:fixture/registry :app-schemas])]
      (rf/reg-app-schema path schema))))

;; ---- trace capture -------------------------------------------------------

(defn- collect-traces
  "Register a DEV trace listener for the fixture's run; the returned atom
  accumulates every captured trace event.

  DEV-ONLY BY CONSTRUCTION. `trace/register-listener!` feeds the
  development trace bus, and every emit site on it sits inside
  `interop/debug-enabled?` — a load-time gate. Under
  `-Dre-frame.debug=false` this atom stays EMPTY for every fixture, which
  is why the always-on captures below exist alongside it."
  [fixture-id]
  (let [traces (atom [])]
    (rf.trace/register-listener! [fixture-id]
                              (fn [ev] (swap! traces conj ev)))
    traces))

;; ---- always-on capture (rf2-76gom) ---------------------------------------
;;
;; Surface #4 — the `error-emit` / `event-emit` substrates, which survive
;; `-Dre-frame.debug=false` and are what a production JVM SSR host actually
;; ships off-box. These are NOT a fallback rendering of the dev bus: the
;; always-on error axis is the SSR projector's production status source of
;; truth (`re-frame.ssr.error-listener/error-emit-projection-listener`), so
;; a fixture adjudicated against it is adjudicating the wire.

(def ^:private always-on-error-listener-id ::always-on-errors)
(def ^:private always-on-event-listener-id ::always-on-events)

(defn- error-record->trace-event
  "Synthesise the `{:operation :op-type :tags}` envelope the projector
  pipeline consumes from an EP-0008 union error record `{:error <kw>
  :frame <id> :time <ms> + flat category keys}`.

  This is the SAME generic lift `error-emit-projection-listener`
  performs — every non-`:error` slot rides onto `:tags`, `:recovery`
  defaults to `:no-recovery` — so an event handed to `ssr/project-error`
  from here is byte-for-byte the event the runtime's own always-on
  projection listener would have handed it."
  [record]
  {:op-type   :error
   :operation (:error record)
   :tags      (-> (dissoc record :error)
                  (update :recovery #(or % :no-recovery)))})

(defn- collect-always-on-errors
  "Register a listener on the ALWAYS-ON `:errors` stream for the fixture's
  run; the returned atom accumulates every record, already in the
  projector's trace-event envelope."
  []
  (let [records (atom [])]
    (rf/register-listener! :errors always-on-error-listener-id
                           (fn [record]
                             (swap! records conj (error-record->trace-event record))))
    records))

(defn- collect-always-on-events
  "Register a listener on the ALWAYS-ON `:events` stream for the fixture's
  run; the returned atom accumulates one record per PROCESSED event (Spec
  009 §Event-emit listener). This is the production counterpart of the dev
  bus's `:rf.event/run-start` trace."
  []
  (let [records (atom [])]
    (rf/register-listener! :events always-on-event-listener-id
                           (fn [record] (swap! records conj record)))
    records))

(defn- clear-always-on-listeners! []
  (rf/unregister-listener! :errors always-on-error-listener-id)
  (rf/unregister-listener! :events always-on-event-listener-id))

;; ---- matchers ------------------------------------------------------------

(defn- submap?
  "True if every key of `expected` is present in `actual` with a
  matching value. Recurses into nested maps."
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
  `actual` in declaration order. Extras tolerated."
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

(defn- check-trace-not-emitted
  "Every `not-trace` shape must NOT appear in the captured traces."
  [actual not-traces]
  (vec
    (keep (fn [nt]
            (when (some #(trace-matches? nt %) actual)
              (str "trace that should NOT have fired did: " (pr-str nt))))
          not-traces)))

;; ---- the always-on counterpart of :trace-emissions (rf2-lwtlk) ------------

(defn- expected-event-ids
  "The event-ids a fixture's `:trace-emissions` names on its
  `:rf.event/run-start` claims, in declaration order.

  A `run-start` claim says THIS EVENT RAN. On the dev bus that is a
  `:rf.event/run-start` trace; on the always-on `:events` substrate (Spec
  009 §Event-emit listener) it is one record per PROCESSED event carrying
  `:event-id`. The claim therefore has a production counterpart, and
  `check-always-on-events` adjudicates it in BOTH postures."
  [expected]
  (into []
        (keep (fn [exp]
                (when (= :rf.event/run-start (:operation exp))
                  (get-in exp [:tags :rf.trace/event-id]))))
        expected))

(defn- check-always-on-events
  "Order-preserving subset match of the fixture's `run-start` event-ids
  against the ALWAYS-ON `:events` records. Extras tolerated, exactly like
  `check-trace-emissions`.

  This runs under BOTH postures and is the reason nine fixtures adjudicate
  something real under `-Dre-frame.debug=false` instead of being skipped:
  an event that never ran produces no always-on record and reds here."
  [expected event-records]
  (loop [actual   (mapv :event-id event-records)
         wanted   (expected-event-ids expected)
         failures []]
    (cond
      (empty? wanted) failures
      (empty? actual)
      (into failures
            (map #(str "expected always-on event record not seen: " (pr-str %)))
            wanted)
      :else
      (let [want (first wanted)
            i    (->> actual
                      (map-indexed vector)
                      (some (fn [[i a]] (when (= want a) i))))]
        (if i
          (recur (drop (inc i) actual) (rest wanted) failures)
          (recur actual (rest wanted)
                 (conj failures
                       (str "expected always-on event record not seen: "
                            (pr-str want)))))))))

;; ---- :fixture/calls runner -----------------------------------------------

(defn- run-call
  "Execute one `:fixture/calls` entry. Returns `{:passed? bool :detail msg}`."
  [call]
  ;; Resolve the fixture's portable `[:view-ref <id> & args]` markers once,
  ;; up front, so every call kind below sees a tree this host's emitter can
  ;; actually render (rf2-j81hs — a keyword head is a DOM element on every
  ;; host, so EDN cannot name a view as a head). Recurses into maps, which
  ;; is what reaches the `:subtree` inside a render-continuation input.
  (let [call (update call :input rf.conformance/realise-view-refs)]
  (case (:call call)
    :render-to-string
    (let [out  (try (rf.ssr/render-to-string (:input call) (or (:opts call) {}))
                    (catch Throwable e (str "<error: " (.getMessage e) ">")))
          want (:expect call)]
      {:passed? (= want out)
       :detail  (when (not= want out)
                  (str "render-to-string\n"
                       "    input:    " (pr-str (:input call)) "\n"
                       "    expected: " (pr-str want) "\n"
                       "    actual:   " (pr-str out)))})

    ;; rf2-5lqar2 — the render-tree canonical-traversal pin Spec 011:386
    ;; promises. `render-tree-hash` is the FNV-1a 32-bit hash over the
    ;; canonical-EDN traversal (depth-first shape; sorted attribute keys;
    ;; nil pruned). The fixture pins the reference hash VALUE for a small
    ;; corpus plus same-hash LAW pairs; the value branch is per-host, the
    ;; law pairs are pattern-level.
    :render-tree-hash
    (let [out  (try (rf.ssr/render-tree-hash (:input call))
                    (catch Throwable e (str "<error: " (.getMessage e) ">")))
          want (:expect call)]
      {:passed? (= want out)
       :detail  (when (not= want out)
                  (str "render-tree-hash\n"
                       "    input:    " (pr-str (:input call)) "\n"
                       "    expected: " (pr-str want) "\n"
                       "    actual:   " (pr-str out)))})

    ;; rf2-ojakd / rf2-olb64 (a) — :rf/suspense-boundary streaming SSR.
    ;; Three call kinds; one per Spec 011 §Streaming SSR step.

    :ssr.streaming/render-shell
    (let [{:keys [shell-html continuations]}
          (try (rf.ssr/streaming-render-shell (:input call))
               (catch Throwable e {:shell-html (str "<error: " (.getMessage e) ">")
                                   :continuations []}))
          want (:expect call)
          shell-fails  (->> (:shell-html-includes want)
                            (remove #(.contains ^String shell-html ^String %)))
          conts-want   (:continuations want)
          conts-actual (mapv #(select-keys % [:id]) continuations)
          conts-mismatch? (and conts-want (not= conts-want conts-actual))]
      {:passed? (and (empty? shell-fails) (not conts-mismatch?))
       :detail  (when (or (seq shell-fails) conts-mismatch?)
                  (str "ssr/streaming/render-shell\n"
                       (when (seq shell-fails)
                         (str "    shell-html missing substrings: " (pr-str shell-fails) "\n"
                              "    shell-html actual:\n      " shell-html "\n"))
                       (when conts-mismatch?
                         (str "    continuations expected: " (pr-str conts-want) "\n"
                              "    continuations actual:   " (pr-str conts-actual) "\n"))))})

    :ssr.streaming/render-continuation
    (let [out  (try (rf.ssr/streaming-render-continuation
                      :rf/default (:input call))
                    (catch Throwable e {:html (str "<error: " (.getMessage e) ">")
                                        :failed? true}))
          want (:expect call)
          missing-substrs (when (:html-includes want)
                            (remove #(.contains ^String (:html out) ^String %)
                                    (:html-includes want)))
          html-mismatch?   (and (:html-equals want)
                                (not= (:html-equals want) (:html out)))
          failed-mismatch? (and (contains? want :failed?)
                                (not= (:failed? want) (:failed? out)))]
      {:passed? (and (empty? missing-substrs) (not html-mismatch?) (not failed-mismatch?))
       :detail  (when (or (seq missing-substrs) html-mismatch? failed-mismatch?)
                  (str "ssr/streaming/render-continuation\n"
                       (when (seq missing-substrs)
                         (str "    html missing substrings: " (pr-str missing-substrs) "\n"
                              "    html actual: " (pr-str (:html out)) "\n"))
                       (when html-mismatch?
                         (str "    html expected: " (pr-str (:html-equals want)) "\n"
                              "    html actual:   " (pr-str (:html out)) "\n"))
                       (when failed-mismatch?
                         (str "    failed? expected: " (:failed? want) "\n"
                              "    failed? actual:   " (:failed? out) "\n"))))})

    :ssr.streaming/build-final-payload
    (let [payload (try (rf.ssr/streaming-build-final-payload
                         :rf/default
                         (:render-hash (:input call))
                         ;; rf2-lm2yzy — the WIRE :rf/frame-id is decoupled from
                         ;; the projection frame. This fixture's synthetic server
                         ;; frame is the STABLE `:rf/default`, so name it as the
                         ;; `:client-frame-id` wire id — the payload then carries
                         ;; `:rf/frame-id`, matching the fixture's pinned canonical
                         ;; keys. (A production per-request gensym frame is
                         ;; anonymous and OMITS `:rf/frame-id`; covered by the
                         ;; ssr_streaming_test omit case + the ssr-ring shipped-
                         ;; handler regression.)
                         (assoc (dissoc (:input call) :render-hash)
                                :client-frame-id :rf/default))
                       (catch Throwable e {:error (.getMessage e)}))
          want    (:expect call)
          keys-want   (:payload-keys want)
          keys-actual (set (keys payload))
          version-want (:rf/version want)
          version-actual (:rf/version payload)
          missing (when keys-want
                    (clojure.set/difference keys-want keys-actual))
          version-mismatch? (and version-want
                                 (not= version-want version-actual))]
      {:passed? (and (empty? missing) (not version-mismatch?))
       :detail  (when (or (seq missing) version-mismatch?)
                  (str "ssr/streaming/build-final-payload\n"
                       (when (seq missing)
                         (str "    missing payload keys: " (pr-str missing) "\n"))
                       (when version-mismatch?
                         (str "    :rf/version expected: " version-want
                              " actual: " version-actual "\n"))))})

    {:passed? false
     :detail  (str "unknown :call form for ssr runner: " (:call call))})))

;; ---- SSR-specific matchers (the ones core's runner doesn't implement) ---

(defn- active-head-for
  "Read the active head model for `frame-id` if the runtime carries
  one. Returns nil when no head has been rendered or when the head ns
  doesn't expose an active-head fn."
  [frame-id]
  (try
    (when-let [active (resolve 're-frame.ssr.head/active-head)]
      ((deref active) frame-id))
    (catch Throwable _ nil)))

(defn- rendered-head-html
  "Render the active head model to its HTML fragment string. Returns
  `\"\"` when no head model is set."
  [frame-id]
  (try
    (when-let [h2html (resolve 're-frame.ssr.head/head-model->html)]
      (when-let [model (active-head-for frame-id)]
        ((deref h2html) model)))
    (catch Throwable _ "")))

;; ---- single-fixture execution -------------------------------------------

(defn- run-fixture
  "Run one fixture; return a result map shaped like the core runner's."
  [fixture]
  (try
    (let [fid          (:fixture/id fixture)
          traces       (collect-traces fid)
          ;; rf2-76gom — the always-on axes, captured alongside the dev bus.
          ;; Registered BEFORE `realise-handlers` / `make-frame` so the
          ;; `:initial-events` cascade (`:rf/server-init`) is observed.
          ao-errors    (collect-always-on-errors)
          ao-events    (collect-always-on-events)
          _            (realise-handlers fixture)
          frame-config (or (:fixture/frame-config fixture) {})
          ;; `reset-runtime` already created :rf/default WITHOUT any
          ;; :initial-events. `make-frame` against an existing id is a
          ;; surgical update that does NOT re-fire :initial-events (Spec 002).
          ;; Destroy first so the fixture's :initial-events cascade fires
          ;; under its declared :platform / :ssr config.
          _            (rf/destroy-frame! :rf/default)
          _            (rf/make-frame (assoc frame-config :id :rf/default))
          dispatches   (or (:fixture/dispatches fixture) [])
          ;; rf2-sb47ni — accumulate per-dispatch boundary-throw mismatches
          ;; (the `:expect-error` cofx-delivery assertions). Mirrors the core
          ;; runner's `dispatch-error-failures` channel.
          dispatch-error-failures (atom [])]
      ;; EP-0002 (rf2-9o48ih): a bare `dispatch-sync` with no explicit
      ;; `{:frame …}` opt resolves its target from the established frame
      ;; scope, never from an invented `:rf/default` floor (the carried
      ;; invariant). This single-frame SSR runner targets `:rf/default`;
      ;; establish that scope explicitly so the dispatches resolve to it.
      (rf/with-frame :rf/default
      (doseq [ev dispatches]
        (cond
          ;; ---- map-form dispatch (rf2-sb47ni) --------------------------
          ;; A map dispatch carries `:event` plus optional opts. EP-0017
          ;; SSR/hydration fixtures use this to supply a PROVIDED recordable
          ;; fact flat on the token via `:rf.cofx {…}`, and to assert the
          ;; boundary throw a MISSING declared provided fact raises via
          ;; `:expect-error`. The remaining opts (`:rf.cofx`, `:source`)
          ;; pass through to `dispatch-sync` verbatim.
          (and (map? ev) (contains? ev :expect-error))
          ;; EP-0017 cofx-delivery errors throw during context assembly,
          ;; escaping `dispatch-sync` (not captured into the chain). Catch
          ;; here and compare the ex-data `:rf.error/id`. Mirrors the core
          ;; runner's `:expect-error` branch.
          (let [event (:event ev)
                want  (:expect-error ev)
                opts  (dissoc ev :event :expect-error)
                got   (try (rf/dispatch-sync event opts) ::no-throw
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
          ;; A map dispatch with no `:expect-error` — `:event` plus opts
          ;; (e.g. `:rf.cofx` provided-fact delivery) flow to dispatch-sync.
          (rf/dispatch-sync (:event ev) (dissoc ev :event))

          (and (vector? ev) (= :rf/hydrate (first ev)))
          ;; Per Spec 011 §The :rf/hydrate event the call site stamps
          ;; :source :ssr-hydration on the dispatch envelope. The
          ;; conformance runner stamps it for the user.
          (rf/dispatch-sync ev {:source :ssr-hydration})

          :else
          (rf/dispatch-sync ev))))
      ;; ---- :fixture/render-after-hydrate -------------------------------
      ;; SSR hydration fixtures simulate the client-side first render
      ;; by feeding the runtime's `verify-hydration!` a synthetic
      ;; render hash. The runtime owns the comparison + the
      ;; `:rf.ssr/hydration-mismatch` trace; we just pass the client
      ;; hash through.
      (when-let [render-spec (:fixture/render-after-hydrate fixture)]
        (let [client-hash     (:simulated-client-render-hash render-spec)
              first-diff-path (:first-diff-path render-spec)
              hydrate-ev      (some (fn [e]
                                      (when (and (vector? e)
                                                 (= :rf/hydrate (first e)))
                                        e))
                                    dispatches)
              payload         (when hydrate-ev (second hydrate-ev))
              server-hash     (:rf/render-hash payload)
              frame-id        (:rf/frame-id payload :rf/default)]
          (when (and client-hash server-hash)
            (rf.ssr/verify-hydration!
              frame-id client-hash
              {:first-diff-path first-diff-path
               :server-hash     server-hash}))))
      ;; ---- :fixture/calls ----------------------------------------------
      (let [calls       (or (:fixture/calls fixture) [])
            call-results (mapv run-call calls)
            call-fails  (filterv (complement :passed?) call-results)]
        (when (seq call-fails)
          (throw (ex-info (str "calls failed: "
                               (str/join "; " (map :detail call-fails)))
                          {:call-failures call-fails}))))
      ;; ---- drain pending error projections -----------------------------
      ;; Per Spec 011 §Server error projection the runtime's listener
      ;; buffers error trace events; draining stamps the projector's
      ;; :status onto the per-frame response slot. We flush before
      ;; reading final-app-db / request-result so the assertions see
      ;; the post-drain state.
      (doseq [fid (rf.frame/frame-ids)]
        (try (rf.ssr/apply-error-projection! fid)
             (catch Throwable _ nil)))
      ;; ---- assertion gathering -----------------------------------------
      (let [expect        (or (:fixture/expect fixture) {})
            expected-db   (:final-app-db expect)
            final-db      (rf/app-db-value :rf/default)
            sub-checks
            (doall
              (for [[query-v expected-val] (or (:sub-values expect) {})]
                {:query    query-v
                 :expected expected-val
                 :actual   (rf/subscribe-once query-v {:frame :rf/default})}))
            ;; ---- :trace-emissions / :trace-not-emitted ----------------
            ;; POSTURE SPLIT (rf2-lwtlk). Both channels read `@traces`, the
            ;; DEV trace bus, whose every emit site sits inside the
            ;; load-time `interop/debug-enabled?` gate. Under
            ;; `-Dre-frame.debug=false` the ring is empty for EVERY fixture,
            ;; so `check-trace-emissions` reports a miss for a trace the
            ;; framework was never going to emit, and — the quieter half —
            ;; `check-trace-not-emitted` passes AUTOMATICALLY, a negative
            ;; over an empty ring that would hold with the whole runtime
            ;; ripped out. Kept VERBATIM, adjudicated in dev posture only.
            ;;
            ;; The production counterpart is `always-on-failures` below; the
            ;; two are complementary, not a substitution.
            trace-failures (when rf.interop/debug-enabled?
                             (check-trace-emissions @traces
                                                    (:trace-emissions expect)))
            not-emit-failures (when rf.interop/debug-enabled?
                                (check-trace-not-emitted @traces
                                                         (:trace-not-emitted expect)))
            ;; ---- the always-on counterpart (rf2-lwtlk) ----------------
            ;; POSTURE-INDEPENDENT — runs under both. Every `run-start`
            ;; claim in `:trace-emissions` asserts that an event RAN, and
            ;; that fact survives the gate on the `:events` substrate. So
            ;; the nine fixtures whose dev-bus claims elide still adjudicate
            ;; a real, production-visible fact here rather than nothing.
            always-on-failures (check-always-on-events (:trace-emissions expect)
                                                       @ao-events)
            always-on-claims   (count (expected-event-ids (:trace-emissions expect)))
            ;; ---- :ssr/public-error -----------------------------------
            expected-pe   (:ssr/public-error expect)
            ;; rf2-76gom — SOURCE THE ERROR FROM WHICHEVER AXIS CARRIES IT.
            ;; `@traces` is the DEV bus: under the production gate it is
            ;; empty, so sourcing the error event from it alone reported
            ;; `:actual nil` for BOTH public-error fixtures whatever the
            ;; framework did — the check adjudicated a dev artefact, not the
            ;; wire, and no framework fix could have moved it. The ALWAYS-ON
            ;; `:errors` axis is the SSR projector's production status
            ;; source of truth (`error-emit-projection-listener` is what
            ;; stamps `:status` on a `-Dre-frame.debug=false` JVM), and
            ;; `error-record->trace-event` hands `project-error` the same
            ;; envelope that listener builds. Dev posture is unchanged: the
            ;; dev bus is a superset there and still wins.
            last-error    (or (last (filter #(= :error (:op-type %)) @traces))
                              (last @ao-errors))
            pe-check
            (when expected-pe
              (if last-error
                (let [actual (rf.ssr/project-error :rf/default last-error)]
                  {:expected expected-pe
                   :actual   actual
                   :passed?  (= expected-pe actual)})
                {:expected expected-pe
                 :actual   nil
                 :passed?  false}))
            ;; ---- :ssr/active-head ------------------------------------
            expected-head (:ssr/active-head expect)
            head-check
            (when expected-head
              (let [actual (active-head-for :rf/default)]
                {:expected expected-head
                 :actual   actual
                 :passed?  (= expected-head actual)}))
            ;; ---- :ssr/request-result ---------------------------------
            ;; Submap match against the resolved response accumulator.
            ;; `:html` / `:payload` slots aren't computed here (the
            ;; runner doesn't render the HTML envelope — that's the
            ;; host adapter's job, exercised by `ssr-end-to-end-test`
            ;; and `ssr-ring/ring_test`). When the fixture asserts
            ;; `:html :absent` (redirect short-circuit), we honour it
            ;; by treating the absent slot as `:absent`.
            expected-rr   (:ssr/request-result expect)
            req-check
            (when expected-rr
              (let [response (rf.ssr/get-response :rf/default)
                    rr       {:response response
                              :html     :absent
                              :payload  :absent}]
                {:expected expected-rr
                 :actual   rr
                 :passed?  (submap? expected-rr rr)}))
            ;; ---- :ssr/rendered-head-contains -------------------------
            expected-rh   (:ssr/rendered-head-contains expect)
            rh-check
            (when expected-rh
              (let [html (rendered-head-html :rf/default)
                    misses (filterv (fn [s] (not (str/includes? (or html "") s)))
                                    expected-rh)]
                {:misses misses
                 :html   html
                 :passed? (empty? misses)}))]
        (rf.trace/clear-listeners!)
        {:fixture-id        fid
         :passed?           (and (or (nil? expected-db) (submap? expected-db final-db))
                                 (every? #(= (:expected %) (:actual %)) sub-checks)
                                 (empty? trace-failures)
                                 (empty? not-emit-failures)
                                 (empty? always-on-failures)
                                 (empty? @dispatch-error-failures)
                                 (or (nil? pe-check) (:passed? pe-check))
                                 (or (nil? head-check) (:passed? head-check))
                                 (or (nil? req-check) (:passed? req-check))
                                 (or (nil? rh-check) (:passed? rh-check)))
         :final-db          final-db
         :expected-db       expected-db
         :sub-checks        sub-checks
         :trace-failures    trace-failures
         :not-emit-failures not-emit-failures
         :always-on-failures always-on-failures
         :always-on-claims  always-on-claims
         :dispatch-error-failures @dispatch-error-failures
         :pe-check          pe-check
         :head-check        head-check
         :req-check         req-check
         :rh-check          rh-check}))
    (catch Throwable e
      {:fixture-id (:fixture/id fixture)
       :passed?    false
       :error      (.getMessage e)
       :exception  e})
    (finally
      ;; rf2-76gom — the always-on registries are corpus-wide and are NOT
      ;; cleared by `tf/reset-runtime` (the `re-frame.ssr` façade's own
      ;; `::error-projection` listener lives there and must survive). Drop
      ;; ONLY the two stand-ins this run registered, on every exit path.
      (clear-always-on-listeners!))))

;; ---- the test entrypoint -------------------------------------------------

(deftest run-ssr-conformance-corpus
  (let [results (atom [])]
    (doseq [[fname fixture] (all-ssr-fixtures)]
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
                             :reason       "capabilities outside ssr-runner claim"
                             :capabilities (:fixture/capabilities fixture)})

        :else
        (swap! results conj (assoc (run-fixture fixture) :fname fname))))
    (let [all     @results
          run     (remove :skipped? all)
          passed  (filter :passed? run)
          failed  (remove :passed? run)
          skipped (filter :skipped? all)]
      ;; rf2-3hamsq — non-empty floor. The lone (zero? (count failed))
      ;; below passes GREEN over an empty / fully-skipped / orphaned
      ;; corpus (wrong cwd, fixtures-dir rename, or a capability-vocab
      ;; rename that orphans every ssr-* fixture) — verifying NOTHING.
      ;; Assert that fixtures actually executed:
      ;;   - (pos? (count run)) catches the fully-empty case;
      ;;   - the expected-minimum (>= 10) catches partial mass-orphaning
      ;;     without pinning an exact count (today's runnable count is 14
      ;;     ssr-*.edn fixtures; the set grows).
      (is (pos? (count run))
          "at least one claim-runnable ssr-*.edn fixture must have executed")
      (is (>= (count run) 10)
          (str "ssr corpus runnable-fixture floor (>= 10): only "
               (count run) " executed — a fixtures-dir/cwd fault or a "
               "capability-vocab rename has orphaned the corpus."))
      ;; rf2-lwtlk — the ALWAYS-ON claim floor, the same non-empty guard one
      ;; level down. `check-always-on-events` is what keeps the corpus
      ;; load-bearing under `-Dre-frame.debug=false`, where the dev-bus
      ;; channels are (correctly) not adjudicated. A translation that
      ;; silently stopped producing claims — a fixture rewrite that dropped
      ;; its `:trace-emissions`, an `:rf.trace/event-id` rename — would leave
      ;; the production posture asserting nothing on this channel while
      ;; still reporting green. Today's corpus yields 17.
      (is (>= (reduce + 0 (map #(:always-on-claims % 0) run)) 12)
          (str "always-on event-claim floor (>= 12): the corpus produced "
               (reduce + 0 (map #(:always-on-claims % 0) run))
               " `:rf.event/run-start` claims translatable onto the "
               "always-on `:events` substrate. Under the production gate "
               "this channel is the one adjudicating :trace-emissions at "
               "all — a collapse here is a silent loss of coverage."))
      ;; Silent-on-success (rf2-try1x): summary prints only on failure.
      (when (seq failed)
        (println)
        (println "SSR conformance corpus (ssr-*.edn fixtures):")
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
          (doseq [nef (:not-emit-failures f)]
            (println "    not-emit:" nef))
          (doseq [aof (:always-on-failures f)]
            (println "    always-on:" aof))
          (doseq [def (:dispatch-error-failures f)]
            (println "    expect-error:" def))
          (when-let [pe (:pe-check f)]
            (when-not (:passed? pe)
              (println "    public-error expected:" (:expected pe))
              (println "    public-error actual:  " (:actual pe))))
          (when-let [hc (:head-check f)]
            (when-not (:passed? hc)
              (println "    active-head expected:" (:expected hc))
              (println "    active-head actual:  " (:actual hc))))
          (when-let [rc (:req-check f)]
            (when-not (:passed? rc)
              (println "    request-result expected:" (:expected rc))
              (println "    request-result actual:  " (:actual rc))))
          (when-let [rh (:rh-check f)]
            (when-not (:passed? rh)
              (println "    rendered-head missing:" (:misses rh))
              (println "    rendered-head html:   " (:html rh))))))
      (is (zero? (count failed))
          (str "All claim-runnable ssr-*.edn conformance fixtures must pass; "
               (count failed) " failed.")))))
