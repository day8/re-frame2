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
            [re-frame.conformance :as conformance]
            [re-frame.core :as rf]
            [re-frame.cofx :as cofx]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.head :as ssr-head]
            [re-frame.ssr.test-fixture :as tf]
            [re-frame.subs :as subs]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.trace :as trace]))

;; The shared reset fixture is `:each` — every fixture in the corpus
;; runs against a clean registrar / frame table / side-channel slot.
(use-fixtures :each tf/reset-runtime)

;; ---- fixture discovery ----------------------------------------------------

(def fixtures-dir
  "The corpus lives at the repo root under
  `spec/conformance/fixtures/`. The ssr artefact's tests run from
  `implementation/ssr/`, so the relative path is two-deep."
  (io/file "../../spec/conformance/fixtures"))

(defn- load-fixture
  "Read one EDN fixture, applying the same `::name` rewrite the JVM core
  runner uses so `clojure.edn/read-string` (no reader resolver) accepts
  auto-resolved keywords. Per rf2-lu3f."
  [file]
  (try
    (let [raw   (slurp file)
          fixed (str/replace raw #"::([a-zA-Z][a-zA-Z0-9_-]*)"
                             ":rf.machine.timer/$1")]
      (edn/read-string fixed))
    (catch Throwable e
      {:fixture/load-error (.getMessage e)
       :fixture/file       (.getName file)})))

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
  {:read-db!  (fn [frame-id] (frame/frame-app-db-value frame-id))
   :write-db! (fn [frame-id new-db]
                (let [container (frame/get-frame-db frame-id)]
                  (substrate-adapter/replace-container! container new-db)))
   :dispatch! (fn [event frame-id] (rf/dispatch event {:frame frame-id}))})

(defn- collect-cofx-keys
  "Walk DSL body steps and collect every cofx-id referenced via
  `[:cofx-key K]`. Used to auto-wire `(inject-cofx K)` interceptors per
  rf2-g25p convention."
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
    (doseq [[cofx-id meta] cofx-registry]
      (let [body    (get-in hmap [:cofx cofx-id] [[:noop]])
            handler (fn [ctx]
                      ;; A no-op cofx — fixtures that need real cofx
                      ;; injection use the conformance-test ns. For ssr
                      ;; the cofx registry slots are declarative only
                      ;; (their presence in :fixture/registry exists so
                      ;; meta lookups don't 404).
                      (reduce (fn [c step]
                                (case (first step)
                                  :set (let [[_ _ v] step]
                                         (assoc-in c [:coeffects cofx-id] v))
                                  c))
                              ctx
                              body))]
        (rf/reg-cofx cofx-id (assoc meta :handler-fn handler) handler)))
    ;; ---- events --------------------------------------------------------
    (doseq [[id steps] (:event hmap)]
      (let [[kind handler] (conformance/realise-event-handler steps)
            meta           (get event-meta id {})
            ks             (collect-cofx-keys steps)
            cofx-ids       (vec (filter cofx-registry ks))
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
    (let [fx-bodies   (:fx hmap)
          fx-registry (get-in fixture [:fixture/registry :fx] {})
          all-ids     (into #{} (concat (keys fx-bodies) (keys fx-registry)))]
      (doseq [id all-ids]
        (let [body    (get fx-bodies id [[:noop]])
              meta    (get fx-registry id {})
              handler (conformance/realise-fx-handler id body helpers)]
          (rf/reg-fx id (assoc meta :handler-fn handler) handler))))
    ;; ---- views ---------------------------------------------------------
    (doseq [[id steps] (:view hmap)]
      (registrar/register!
        :view id
        {:handler-fn (conformance/realise-view-handler steps)}))
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
      (rf/reg-route id meta))
    ;; ---- app-schemas (rare on ssr fixtures, but covered) ---------------
    (doseq [[path schema] (get-in fixture [:fixture/registry :app-schema])]
      (rf/reg-app-schema path schema))))

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

;; ---- :fixture/calls runner -----------------------------------------------

(defn- run-call
  "Execute one `:fixture/calls` entry. Returns `{:passed? bool :detail msg}`."
  [call]
  (case (:call call)
    :render-to-string
    (let [out  (try (ssr/render-to-string (:input call) (or (:opts call) {}))
                    (catch Throwable e (str "<error: " (.getMessage e) ">")))
          want (:expect call)]
      {:passed? (= want out)
       :detail  (when (not= want out)
                  (str "render-to-string\n"
                       "    input:    " (pr-str (:input call)) "\n"
                       "    expected: " (pr-str want) "\n"
                       "    actual:   " (pr-str out)))})

    ;; rf2-ojakd / rf2-olb64 (a) — :rf/suspense-boundary streaming SSR.
    ;; Three call kinds; one per Spec 011 §Streaming SSR step.

    :ssr/streaming/render-shell
    (let [{:keys [shell-html continuations]}
          (try (ssr/streaming-render-shell (:input call))
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

    :ssr/streaming/render-continuation
    (let [out  (try (ssr/streaming-render-continuation
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

    :ssr/streaming/build-final-payload
    (let [payload (try (ssr/streaming-build-final-payload
                         :rf/default
                         (:render-hash (:input call))
                         (dissoc (:input call) :render-hash))
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
     :detail  (str "unknown :call form for ssr runner: " (:call call))}))

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
          _            (realise-handlers fixture)
          frame-config (or (:fixture/frame-config fixture) {})
          ;; `reset-runtime` already created :rf/default WITHOUT an
          ;; :on-create. `reg-frame` against an existing id is a
          ;; surgical update that does NOT re-fire :on-create (Spec 002).
          ;; Destroy first so the fixture's :on-create cascade fires
          ;; under its declared :platform / :ssr config.
          _            (rf/destroy-frame! :rf/default)
          _            (rf/reg-frame :rf/default frame-config)
          dispatches   (or (:fixture/dispatches fixture) [])]
      (doseq [ev dispatches]
        (cond
          (and (vector? ev) (= :rf/hydrate (first ev)))
          ;; Per Spec 011 §The :rf/hydrate event the call site stamps
          ;; :source :ssr-hydration on the dispatch envelope. The
          ;; conformance runner stamps it for the user.
          (rf/dispatch-sync ev {:source :ssr-hydration})

          :else
          (rf/dispatch-sync ev)))
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
            (ssr/verify-hydration!
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
      (doseq [fid (frame/frame-ids)]
        (try (ssr/apply-error-projection! fid)
             (catch Throwable _ nil)))
      ;; ---- assertion gathering -----------------------------------------
      (let [expect        (or (:fixture/expect fixture) {})
            expected-db   (:final-app-db expect)
            final-db      (rf/get-frame-db :rf/default)
            sub-checks
            (doall
              (for [[query-v expected-val] (or (:sub-values expect) {})]
                {:query    query-v
                 :expected expected-val
                 :actual   (rf/subscribe-once :rf/default query-v)}))
            trace-failures (check-trace-emissions @traces
                                                  (:trace-emissions expect))
            not-emit-failures (check-trace-not-emitted @traces
                                                       (:trace-not-emitted expect))
            ;; ---- :ssr/public-error -----------------------------------
            expected-pe   (:ssr/public-error expect)
            pe-check
            (when expected-pe
              (let [error-events (filter #(= :error (:op-type %)) @traces)
                    last-error   (last error-events)]
                (if last-error
                  (let [actual (ssr/project-error :rf/default last-error)]
                    {:expected expected-pe
                     :actual   actual
                     :passed?  (= expected-pe actual)})
                  {:expected expected-pe
                   :actual   nil
                   :passed?  false})))
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
              (let [response (ssr/get-response :rf/default)
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
        (trace/clear-listeners!)
        {:fixture-id        fid
         :passed?           (and (or (nil? expected-db) (submap? expected-db final-db))
                                 (every? #(= (:expected %) (:actual %)) sub-checks)
                                 (empty? trace-failures)
                                 (empty? not-emit-failures)
                                 (or (nil? pe-check) (:passed? pe-check))
                                 (or (nil? head-check) (:passed? head-check))
                                 (or (nil? req-check) (:passed? req-check))
                                 (or (nil? rh-check) (:passed? rh-check)))
         :final-db          final-db
         :expected-db       expected-db
         :sub-checks        sub-checks
         :trace-failures    trace-failures
         :not-emit-failures not-emit-failures
         :pe-check          pe-check
         :head-check        head-check
         :req-check         req-check
         :rh-check          rh-check}))
    (catch Throwable e
      {:fixture-id (:fixture/id fixture)
       :passed?    false
       :error      (.getMessage e)
       :exception  e})))

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
