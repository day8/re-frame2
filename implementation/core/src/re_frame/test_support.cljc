(ns re-frame.test-support
  "Test fixture helpers shared between JVM and CLJS test suites.

  ## See also — `re-frame.test-helpers` (rf2-v7kjq)

  Sibling namespace covering the **view-tree assertion axis** — hiccup
  walkers (`find-by-testid`, `text-content`, `extract-handler`), handler
  invocation (`invoke-handler`), and the `testid` authoring helper. The
  single-frame view test composes those walkers with
  `make-reset-runtime-fixture` + `poll-until` from this namespace — see
  `docs/core/testing/views.md`.

  This namespace owns the **runtime-state assertion axis**: registrar,
  frames, `app-db`, drain, in-flight requests, fixture machinery.

  A test that exercises events / subs / machines reaches here. A test
  that asserts on rendered view content reaches `re-frame.test-helpers`.
  A test doing both `:require`s both. See [Spec 008 §Audience-split]
  (../../../../../spec/008-Testing.md#audience-split--re-frametest-support-vs-re-frametest-helpers-rf2-v7kjq)
  for the axis rationale.

  ## Why this namespace exists (rf2-am9d, follow-up to rf2-coks / rf2-p8g8)

  Tests need per-test isolation of *user-test-registered* handlers, subs,
  views, machines, fx, etc. — without wiping *framework-shipped*
  registrations that landed at namespace-load time and (under CLJS)
  cannot be re-loaded at runtime.

  Earlier fixtures in the CLJS test suite reached for `rf.registrar/clear-all!`,
  which is fundamentally hostile to CLJS isolation:

    - `re-frame.routing` registers `:rf.route/transitioned`, `:rf.route/navigate`,
      `:rf.nav/scroll`, the `:rf/route` and `:rf.route/{id,params,query,
      transition,error}` reg-subs (and friends) at ns-load.
    - `re-frame.machines` registers the `:rf/machine` sub at ns-load.
    - Example apps (e.g. `nine-states.core`) register their handlers /
      subs / views / machines at ns-load.

  CLJS has no `(require ... :reload)` analogue, so once those slots are
  wiped they cannot be reinstated for downstream tests in the same run.
  rf2-coks documented the resulting cross-test pollution.

  The right pattern is **snapshot/restore**: capture
  `@rf.registrar/kind->id->metadata` before the test, allow the test to
  register additional ids, then reset the registrar to the captured map
  on the way out. Framework-shipped registrations survive (they're in
  the snapshot); user-test registrations are rolled back; the next test
  starts from the same baseline as this one.

  The same shape works on the JVM. JVM tests can additionally rely on
  `:reload` to resurrect registrations after `clear-all!`, but that's
  the expensive route; snapshot/restore is faster and substrate-agnostic.

  ## Public API

  ### Fixture machinery
  - [[snapshot-registrar]] — capture the current registrar state.
  - [[restore-registrar!]] — restore the registrar to a captured snapshot.
  - [[make-reset-runtime-fixture]] — `clojure.test`/`cljs.test` `:each`
    fixture that snapshot/restores the registrar AND resets the
    per-process state held by frames / flows (when the flows artefact
    is loaded, rf2-tfw3) / adapter / machine counters / trace
    listeners. Pins a STABLE ns-load baseline (captured at fixture-build
    time) and reinstates it before each test's snapshot, so example /
    framework tests are run-order-independent inside the shared
    `:node-test` bundle (rf2-7hwnu).

  ### Test-flavoured helpers (rf2-0l3s / rf2-hkr5 / rf2-8j9m6)
  - [[assert-path-equals]] — assert `(= expected (get-in app-db path))`
    against the resolved frame; failure reports via `clojure.test/is`.
    Mirrors the `:rf.assert/path-equals` event (Story `:play` blocks);
    same name root so a reader who knows one surface navigates the other.

  ### Trace-recorder bracket (rf2-64iuw)
  - [[with-trace-recorder!]] — register a trace-tooling listener for the
    bracketed body, accumulate matching events into a recording atom
    bound by name, unregister on exit. Supersedes the per-file
    `collect-traces` / `record-traces!` / `record-by-op!` / etc.
    boilerplate that nine adapter test files used to carry (rf2-5r7eh
    audit + rf2-64iuw consolidation).

  ### Deterministic-wait helpers (rf2-ka3n6 / rf2-fun38)
  - [[poll-until]] — bounded-deadline poll for `(pred)` to return
    truthy. JVM returns the truthy value synchronously (throws on
    timeout); CLJS returns a `js/Promise` that resolves with the
    truthy value (rejects on timeout). Replaces incidental fixed
    `Thread/sleep N` / `js/setTimeout` for waits that are observable
    in state (router drain, pipeline-run settle, sub re-fire,
    in-flight registry entries appearing/clearing). NOT for
    timer-semantics tests — those should keep their sleep and annotate
    that intent locally (the sleep IS the contract under test)."
  (:require [clojure.string]
            [re-frame.registrar :as rf.registrar]
            [re-frame.error :as rf.error]
            ;; The runtime fixture resets the ONE `rf.frame/frames` registry, which
            ;; (EP-0024 — the second live-frame registry dissolved into it) clears
            ;; every record AND its `:generation`. A frame seated via
            ;; `rf/make-frame {:id …}` registers there, and a stale entry leaking
            ;; across tests would make the next `make-frame`/`seat-*` treat the id
            ;; as already-seated (or fail loud on the duplicate id) — the single
            ;; reset is the whole clear (rf2-32siq3.32 / rf2-rjml45 / rf2-ji3tvy).
            [re-frame.frame :as rf.frame]
            ;; EP-0027 (rf2-7ae2to): re-seed the framework-standard `:rf/set-db`
            ;; event into BOTH the regular registrar AND the EP-0023 image
            ;; standard registry on each reset (mirroring how `init!` re-seeds
            ;; it after a `rf.registrar/clear-all!`). A sibling test ns whose
            ;; fixture calls `rf.image-assembly/clear-standards!` (frame-resolution,
            ;; ep0023-conformance, facade-frame-read, image-assembly-cache) would
            ;; otherwise leave the image standard registry EMPTY for the next ns,
            ;; so an image-loaded frame seeding via `:initial-events [[:rf/set-db
            ;; …]]` could not resolve `:rf/set-db` through its sealed generation.
            ;; (`events` is already in the dep graph via `router`; no new cycle.)
            [re-frame.events :as rf.events]
            ;; EP-0026 §Default Image: `make-frame {}` now projects the DEFAULT
            ;; image over the active SOURCE STORE. Every `reg-*` writes a
            ;; provenance-tagged descriptor into `source-store` (in lockstep with
            ;; the registrar resolver map — `rf.registrar/register!`), so the source
            ;; store ACCUMULATES across the consolidated node-test bundle exactly
            ;; the way the registrar would without snapshot/restore. Two sibling
            ;; test namespaces registering the same `[kind id]` under different
            ;; provenance namespaces leave a cross-namespace collision in the
            ;; shared store, and the next test's `make-frame {}` default projection
            ;; FAILS LOUD (`:rf.error/image-duplicate-id`) — correctly, per the
            ;; default-image semantics. The fixture isolates each test's source
            ;; store (snapshot/restore + generation-cache clear) so a default
            ;; projection sees ONLY this test's own registrations. The
            ;; resolved-generation cache is keyed on the source-store generation,
            ;; so clearing it on reset stops a stale default generation leaking.
            [re-frame.source-store :as rf.source-store]
            [re-frame.image-assembly :as rf.image-assembly]
            ;; The flows / schemas / machines / routing / http-managed /
            ;; epoch artefacts ship in separate Maven coordinates and are
            ;; reached only through late-bind hooks — see the
            ;; `reset-hook-table` var docstring below for the per-artefact
            ;; rationale (rf2-tfw3 / rf2-p7va / rf2-xbtj / rf2-k682 /
            ;; rf2-5kpd / rf2-lt4e). This ns must not statically require
            ;; any of them.
            [re-frame.late-bind :as rf.late-bind]
            ;; Per rf2-qwm0a: the public-tooling listener + buffer
            ;; surface lives in `re-frame.trace.tooling` (split off
            ;; from `re-frame.trace` for production CLJS bundle DCE).
            ;; Test fixtures need `clear-listeners!` between scenarios;
            ;; we reach it through the tooling sibling directly.
            [re-frame.trace.tooling :as rf.trace.tooling]
            ;; Clear the always-on event-emit listener registry on each
            ;; reset so a forwarder registered in one test doesn't see
            ;; events fired by a sibling test.
            [re-frame.event-emit :as rf.event-emit]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            #?(:clj  [clojure.test :as ctest]
               :cljs [cljs.test :as ctest :include-macros true])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- registrar snapshot/restore -------------------------------------------

(defn- restore-source-store!
  "Reset the live source store to the suite's ns-load `baseline` (EP-0026
  source-store isolation; the store leg of the per-test rollback). NOTE
  (rf2-h1vqa4): a suite registering top-level state AFTER its
  `use-fixtures` form places those rows outside this baseline — register
  above the fixture form, or re-seed in the suite's :init-fn (frames
  resolve through the STORE: the default image is assembled from it)."
  [baseline]
  (reset! rf.source-store/kind->id->ns->descriptor baseline)
  nil)

(defn snapshot-registrar
  "Capture the current registrar contents.

  Returns the value of `@rf.registrar/kind->id->metadata` — a plain map of
  `kind → id → metadata` — at the moment of the call. Pair with
  [[restore-registrar!]] to roll the registrar back to this point.

  The returned value is the persistent map the registrar atom holds; it
  is safe to keep across mutations (subsequent `register!` calls produce
  new persistent maps and don't alter the captured one)."
  []
  @rf.registrar/kind->id->metadata)

(defn restore-registrar!
  "Reset the registrar to a previously captured snapshot.

  Any registrations made since the snapshot are dropped; any registrations
  removed since the snapshot are restored. Use this in test fixtures to
  undo per-test pollution while preserving framework / example
  registrations that landed at ns-load time."
  [snapshot]
  (reset! rf.registrar/kind->id->metadata snapshot)
  nil)

(defn- merge-registrar-snapshots
  "Two-level merge of two registrar snapshots (`kind → id → metadata`).

  Entries from `overlay` win on a per-`[kind id]` collision; ids present
  only in `base` survive; ids present only in `overlay` are added. Because
  the registrar value is a map of `kind → (map of id → metadata)`, the
  merge must be two-deep — `(merge-with merge base overlay)` — so that two
  snapshots that each populate a *different* id under the same `kind` are
  unioned rather than one `kind` map clobbering the other.

  Used by [[make-reset-runtime-fixture]] to fold a stable ns-load baseline
  back over whatever the registrar currently holds, so a test ns's own
  ns-load registrations are present regardless of what a sibling ns's
  `:each` fixture last restored the registrar to (rf2-7hwnu)."
  [base overlay]
  (merge-with merge base overlay))

;; ---- bundle co-load hygiene: the fixture's `:app-ns` rows -----------------
;;
;; A CLJS node runner loads EVERY test namespace into ONE bundle before any
;; test runs, so two co-loaded example apps that register the same per-app id
;; (the canonical case `:rf.route/not-found`; the RealWorld twins also share
;; `:settings/load`, `:auth/initialise`, …) leave TWO provenance rows in the
;; shared source store — and every suite whose fixture baseline is captured
;; after the second app loads then fails default-image assembly loud
;; (`:rf.error/image-duplicate-id`) on `make-frame {}`.
;;
;; That is a BASELINE concern, and `make-reset-runtime-fixture` already owns
;; the baseline: it pins the registrar + source store at fixture-build time and
;; restores both around every test. `:app-ns` therefore folds the whole cycle
;; into the fixture rather than spelling it a second time beside it — capture
;; the app's rows at BUILD time, before the baselines are taken, and reinstate
;; them per test.
;;
;; SELF-HIDING IS THE INVARIANT: a suite names its OWN app's root namespace,
;; never a sibling's. That is sufficient because of WHEN a fixture is built — a
;; test ns's `use-fixtures` form is evaluated at that ns's load, immediately
;; after its `:require` chain brought the app live — so an app's rows are
;; removed the moment they appear, before any RIVAL app has loaded, and no other
;; suite's baseline can hold them.
;;
;; SO A SUITE THAT USES AN APP DECLARES IT — every suite, not only the one that
;; happens to load it first. That is not a tidiness rule; it is what the option
;; requires, and the way it bites is worth writing down because it is invisible.
;; `reinstate-and-snapshot!` folds the ns-load baseline OVER the live registrar
;; (`merge-with merge live baseline`), so a suite silently resolves ids that were
;; never in its own baseline but merely sat LIVE in the registrar because some
;; other test ns's `:require` chain registered them. Measured on this tree: the
;; RealWorld password-classification suite requires the app's feature namespaces
;; but NOT its `core`, and reached `:auth/classify-token` — a `core`
;; registration — purely through that leftover. Claiming the app on the suite
;; that owns it removes the leftover, and the borrowing suite then loses an id it
;; never declared. Declaring `:app-ns` on the borrower is the repair: it
;; reinstates the whole app from the union, `core` included.
;;
;; The other unreached case is two rival apps in ONE suite's baseline, and it is
;; loud rather than silent: that suite's `make-frame {}` raises
;; `:rf.error/image-duplicate-id`. Its repair is the same one — name the app on
;; the suite that loaded it — and never to name a SIBLING from somewhere else,
;; which is the capture-before-the-app-finished-loading shape that broke the
;; predecessor.

(defonce ^:private app-ns-rows
  ;; prefix string → {[kind id provenance-ns] → source-store descriptor}
  ;;
  ;; Rows `:app-ns` removed at fixture-build time, UNIONED across fixture
  ;; builds and read back at TEST time. Both halves are load-bearing, because
  ;; CLJS loads a required namespace ONCE: the second suite to build a fixture
  ;; for an app finds the rows already gone and captures only what its own
  ;; requires added, so it has to reinstate what an earlier suite captured; and
  ;; a part of an app that loads LATE is captured by whichever fixture builds
  ;; after it, so a suite built earlier has to see that too.
  ;;
  ;; UNION, NOT MEMO. The predecessor this option replaces kept a
  ;; first-capture-WINS memo, so a capture taken before an app had finished
  ;; loading pinned an incomplete set for every later suite — measured, with a
  ;; route row left live and a sibling suite's frame creation failing.
  (atom {}))

(defn- capture-app-ns-rows!
  "Remove every live source-store row whose provenance namespace starts with
  `prefix` from BOTH stores and union the captured rows into `app-ns-rows`
  under `prefix`. Called once per fixture build, BEFORE the fixture takes its
  baselines, so the rows are absent from every baseline in the bundle.

  The registrar leg is OWNERSHIP-GUARDED. The registrar's single `(kind, id)`
  slot is the LAST writer's, and for an id BOTH apps register that may be a
  SIBLING's — so drop it only when its current writer is itself under `prefix`,
  leaving a sibling's live registration standing. This app's own source row is
  forgotten either way, which is what keeps registrar and source-store
  authority coherent. NOT `rf.registrar/unregister!`: that forgets EVERY
  provenance slot for `(kind, id)` and would clobber a sibling's row for a
  shared id."
  [prefix]
  (let [rows (vec (for [[kind id->ns] @rf.source-store/kind->id->ns->descriptor
                        [id ns->d]    id->ns
                        [pns d]       ns->d
                        :when (and (string? pns)
                                   (clojure.string/starts-with? pns prefix))]
                    [[kind id pns] d]))]
    (doseq [[[kind id pns] _] rows]
      (swap! rf.registrar/kind->id->metadata update kind
             (fn [m]
               (let [cur    (get m id)
                     cur-ns (or (get cur rf.source-store/provenance-ns-key)
                                (some-> (:ns cur) str))]
                 (if (and cur
                          (string? cur-ns)
                          (clojure.string/starts-with? cur-ns prefix))
                   (dissoc m id)
                   m))))
      (rf.source-store/forget-descriptor! kind id pns))
    (swap! app-ns-rows update prefix (fnil into {}) rows)
    nil))

(defn- reinstate-app-ns-rows!
  "Reinstate every row captured for `prefix` through `rf.registrar/register!`,
  so registrar and source store go back in lockstep (an image-loaded frame
  resolves through the STORE; a raw registrar-atom write would be invisible to
  its generation).

  Reads `app-ns-rows` HERE rather than closing over the build-time capture:
  the union is what makes repeated suites for one app work in either order, and
  a suite that built its fixture first must still see rows a later fixture
  build captured."
  [prefix]
  (doseq [[_ d] (get @app-ns-rows prefix)]
    (rf.registrar/register! (:kind d) (:id d) d))
  nil)

;; ---- full per-test runtime reset ------------------------------------------

(def ^:private reset-hook-table
  "Late-bind hook keys fired by `make-reset-runtime-fixture` to drop per-process
  test state — one row per optional artefact. Each entry pairs the hook key
  with a `:phase` (when it fires relative to `rf.substrate.adapter/dispose-adapter!`) and
  the design bead that introduced the artefact. The driver
  `run-reset-hooks!` walks the table in registration order and no-ops a row
  when its hook is unregistered (artefact absent from the classpath).

  Order is load-bearing — non-late-bind steps interleave with the hooks:
    1. `(reset! rf.frame/frames {})`
    2. `:pre-dispose` hooks (flows resets, schemas clear)
    3. `(rf.substrate.adapter/dispose-adapter!)`
    4. `:post-dispose` hooks (machines, routing, http, epoch, adapter-warn)
  Splitting the table by `:phase` lets the driver fire each contiguous run
  in one pass while keeping the cross-cutting prose in one place.

  Per-row rationale:

    :flows/reset-flows!              — drop ALL per-frame flow state: the
                                       flow registry, the paired dirty-check
                                       last-inputs containers, and the pending
                                       abandoned-output-paths containers.
    :schemas/clear-by-frame!         — clear per-frame schema registrations.
                                       Paired with `:schemas/snapshot-by-frame`
                                       + `:schemas/restore-by-frame!` for
                                       snapshot/restore around the test body.
    :machines/reset-timers!          — cancel in-flight `:after` wall-clock
                                       timers so a stale timer from a
                                       sibling test can't survive.
    :fx/reset-dispatch-later-timers! — cancel every frame's pending
                                       `:dispatch-later` host timers
                                       (rf2-uxz52g) so a stale armed timer
                                       from a sibling test can't fire mid-
                                       next-test. Host-side transient state
                                       the `frames` reset above does not
                                       touch (mirrors `:machines/reset-
                                       timers!`); always bound (re-frame.fx
                                       ships in core).
    :machines/reset-spawn-order!     — drop the per-frame spawn-order
                                       channel (rf2-vsigt) so a stale
                                       entry from a sibling test can't
                                       contaminate a frame-destroy walk.
    :routing/reset-counters!         — reset the route-registration counter
                                       so reg-index is deterministic across
                                       fixture runs.
    :routing/reset-nav-counters!     — reset the host-side nav-token /
                                       pending-nav counter high-water marks
                                       (rf2-oosjmh). They are host-side
                                       transient state now, so the
                                       `frames` reset above no longer clears
                                       them; without this a prior test's
                                       counter leaks and the nav-N / pn-N
                                       id assertions drift.
    :routing/reset-url-claims!       — reset the process-global URL-ownership
                                       claim-order vector (rf2-3l7xxz,
                                       re-frame.routing.nav-fx/url-claim-order).
                                       Like the nav-counters it is process-
                                       global state the `frames` reset does not
                                       touch; without this a prior test's
                                       :url-bound? claim leaks and the
                                       first-claimed-incumbent ownership
                                       resolution drifts across tests.
    :routing/reset-url-listener!     — tear down the browser URL-change
                                       listener a `:url-bound? true` frame's
                                       lifecycle installed (rf2-g8pbwg). The
                                       listener is module-level host state
                                       (`re-frame.routing.history/history-
                                       listener-atom`), NOT torn down by the
                                       `rf.frame/frames` reset above (a raw atom
                                       reset does not run `destroy-frame!`'s
                                       teardown chain) — without this a
                                       listener installed by one test would
                                       survive into the next.
    :resources/reset-resources!      — reset the resources artefact's
                                       host-side transient state (Spec 016):
                                       clear the `:resource` + `:mutation`
                                       registrar kinds, the generation
                                       high-water cache
                                       (re-frame.resources.state/generation-cache),
                                       and the host work-ledger / timer /
                                       revalidation-listener handles
                                       (rf2-afpdkn / rf2-nbjewi / rf2-vtblcq).
                                       Like the routing nav-counters, the
                                       generation cache is host-side transient
                                       state now, so the `frames` reset above
                                       no longer clears it; without this a
                                       prior test's generation high-water mark
                                       leaks across tests. Published from
                                       `re-frame.resources.test-support`, so the
                                       row no-ops when that test-support require
                                       is absent (production builds).
    :http/clear-all-in-flight!       — drop the in-flight managed-request
                                       registry.
    :http/clear-all-http-interceptors! — clear the per-frame request-side
                                       HTTP interceptor chain registry
                                       (internal to `re-frame.http.middleware`),
                                       which lives OUTSIDE the registrar the
                                       snapshot/restore covers. A `:before` /
                                       `:after` interceptor registered in one
                                       test would otherwise mutate every
                                       subsequent test's outgoing request /
                                       reply payload (rf2-q14tde). Published
                                       for test isolation alongside
                                       `:http/clear-all-in-flight!`; the row
                                       no-ops when the http artefact is absent.
    :epoch/clear-history!            — drop the per-frame epoch ring buffer.
    :epoch/clear-epoch-listeners!          — drop the epoch-settled callback
                                       registry.
    :epoch/reset-config!             — restore epoch-history config to the
                                       shipped default baseline (rf2-yw1w1u).
                                       `(rf/configure! {:epoch-history ...})`
                                       MERGES, so without this a prior test's
                                       `:depth` / `:trace-events-keep` /
                                       `:redact-fn` would leak into the next.
                                       Suites that want a non-default value
                                       re-apply it through `configure!` in
                                       their `:init-fn` (which runs after the
                                       post-dispose reset hooks).
    :adapter/clear-warn-once-caches! — clear per-adapter
                                       `warned-non-dom-roots` warn-once
                                       caches. Chained — re-frame.views
                                       and the uix adapter each
                                       register a clear-step.

  Adding a new artefact's reset becomes a one-row addition here."
  [{:hook :flows/reset-flows!              :phase :pre-dispose}
   {:hook :schemas/clear-by-frame!         :phase :pre-dispose}
   {:hook :machines/reset-timers!          :phase :post-dispose}
   {:hook :fx/reset-dispatch-later-timers! :phase :post-dispose}
   {:hook :machines/reset-spawn-order!     :phase :post-dispose}
   {:hook :routing/reset-counters!         :phase :post-dispose}
   {:hook :routing/reset-nav-counters!     :phase :post-dispose}
   {:hook :routing/reset-url-claims!       :phase :post-dispose}
   {:hook :routing/reset-url-listener!     :phase :post-dispose}
   {:hook :resources/reset-resources!      :phase :post-dispose}
   {:hook :http/clear-all-in-flight!       :phase :post-dispose}
   {:hook :http/clear-all-http-interceptors! :phase :post-dispose}
   {:hook :epoch/clear-history!            :phase :post-dispose}
   {:hook :epoch/clear-epoch-listeners!          :phase :post-dispose}
   {:hook :epoch/reset-config!             :phase :post-dispose}
   {:hook :adapter/clear-warn-once-caches! :phase :post-dispose}])

(defn- run-reset-hooks!
  "Driver: fire every `reset-hook-table` row whose `:phase` matches and
  whose producer has registered a fn. Rows with unregistered hooks no-op
  (artefact absent from the classpath)."
  [phase]
  (run! (fn [{:keys [hook]}]
          (when-let [f (rf.late-bind/get-fn hook)]
            (f)))
        (filter #(= phase (:phase %)) reset-hook-table)))

;; ---- shared reset halves (fn-form + async map-form) -----------------------
;;
;; The per-test reset is the SAME work whether it runs inside the synchronous
;; fn-form fixture (`(fn [test-fn] …)`) or split across an async map-form
;; fixture's `:before` / `:after`. Both shapes share these three halves so the
;; hairy EP-0026 (source-store isolation) / EP-0027 (`:rf/set-db` re-seed) /
;; rf2-7hwnu (stable ns-load baseline) sequencing lives in ONE place. The
;; halves are split at the boundaries the fn-form's try/finally already drew:
;;
;;   1. `reinstate-and-snapshot!`  — the pre-try work: fold the ns-load
;;      registrar baseline back over the live registrar, restore the source
;;      store, clear the generation cache, and CAPTURE the restore context
;;      (registrar snapshot + the late-bound schemas snapshot/clear/restore
;;      fns + the per-frame schemas snapshot, taken BEFORE the frames reset).
;;   2. `reset-runtime!`           — the reset body proper: frames reset, the
;;      pre/post-dispose hook phases, adapter dispose+install, the framework-
;;      standard re-seeds, `:clear-kinds` / `:clear-app-schemas?`. Runs inside
;;      the caller's try so the finish half still fires if it throws.
;;   3. `finish-runtime-reset!`    — the finally body: restore the registrar +
;;      per-frame schemas + source store, reset the frames + flows registries.
;;
;; What the halves DELIBERATELY leave to each shape: establishing the ambient
;; frame scope. The fn-form wraps the body in a dynamic `binding` (unwound when
;; the fixture fn returns — correct for a synchronous body). The async map-form
;; cannot: a `binding` established in `:before` is gone by the time an async
;; test body resumes on a later tick, so it `set!`s the var (a persistent root
;; assignment that SURVIVES the async boundary) in `:before` and tears it down
;; in `:after`. See `make-reset-runtime-fixture`'s `:async?` option.

(defn- reinstate-and-snapshot!
  "Pre-reset half (the fn-form's pre-try block). Fold the stable ns-load
  registrar baseline back over whatever the live registrar holds (rf2-7hwnu —
  run-order independence), restore the source store to its ns-load baseline and
  clear the resolved-generation cache (EP-0026 source-store isolation), then
  capture and return the restore context the reset + finish halves consume:

    {:snap         <registrar snapshot to restore to on the way out>
     :clear-fn     <late-bound schemas clear-by-frame! fn, or nil>
     :restore-fn   <late-bound schemas restore-by-frame! fn, or nil>
     :schemas-snap <per-frame schemas snapshot, taken BEFORE the frames reset>}

  No frames / adapter mutation happens here — that is `reset-runtime!`, run
  inside the caller's try so `finish-runtime-reset!` still fires if it throws."
  [ns-load-baseline source-store-baseline]
  (restore-registrar!
    (merge-registrar-snapshots (snapshot-registrar) ns-load-baseline))
  (restore-source-store! source-store-baseline)
  (rf.image-assembly/clear-generation-cache!)
  (let [snapshot-fn (rf.late-bind/get-fn :schemas/snapshot-by-frame)]
    {:snap         (snapshot-registrar)
     :clear-fn     (rf.late-bind/get-fn :schemas/clear-by-frame!)
     :restore-fn   (rf.late-bind/get-fn :schemas/restore-by-frame!)
     :schemas-snap (when snapshot-fn (snapshot-fn))}))

(defn- reset-runtime!
  "The per-test runtime reset body shared by both fixture shapes. Reset the ONE
  `frames` registry (clearing every record + its `:generation`), run the
  pre/post-dispose late-bind hook phases, dispose then (re)install the adapter
  and ensure the conventional `:rf/default` app frame, re-seed the framework
  standards (`:rf/set-db`; the machine runtime when loaded), apply
  `:clear-kinds` / `:clear-app-schemas?`, and LAST reinstate the `:app-ns`
  rows the fixture removed at build time. Establishes everything EXCEPT the
  ambient frame scope — each shape owns how it makes that scope survive (see
  the section comment above).

  The `:app-ns` reinstatement is last so it lands after `:clear-kinds` (rows
  the suite declared as its OWN app are not what `:clear-kinds` is clearing)
  and before the caller's `:init-fn`, which both shapes run next — the setup
  ordering an app's `init!` depends on is therefore unchanged: it still
  registers its plans and stubs against a live registrar before it makes any
  frame."
  [{:keys [adapter clear-kinds clear-app-schemas? app-ns]} clear-fn]
  (reset! rf.frame/frames {})
  (run-reset-hooks! :pre-dispose)
  (rf.substrate.adapter/dispose-adapter!)
  (run-reset-hooks! :post-dispose)
  (rf.trace.tooling/clear-listeners!)
  (rf.event-emit/clear-event-listeners!)
  (when adapter
    (rf.substrate.adapter/install-adapter! adapter)
    (rf.frame/ensure-default-frame!))
  (rf.events/register-set-db-standard!)
  (when-let [install (rf.late-bind/get-fn :machines/install-runtime!)]
    (install))
  (doseq [k clear-kinds]
    (rf.registrar/clear-kind! k))
  (when (and clear-app-schemas? clear-fn)
    (clear-fn))
  (when app-ns
    (reinstate-app-ns-rows! app-ns)))

(defn- finish-runtime-reset!
  "The post-test finally body shared by both fixture shapes. Restore the
  registrar to `:snap` and the per-frame schemas to `:schemas-snap`, restore the
  source store to its ns-load baseline + clear the generation cache (EP-0026
  symmetry with the registrar restore), then reset the frames + flows
  registries so nothing a test body seated survives into the next fixture run."
  [{:keys [snap restore-fn schemas-snap]} source-store-baseline]
  (restore-registrar! snap)
  (when restore-fn (restore-fn schemas-snap))
  (restore-source-store! source-store-baseline)
  (rf.image-assembly/clear-generation-cache!)
  (reset! rf.frame/frames {})
  (when-let [reset-flows! (rf.late-bind/get-fn :flows/reset-flows!)]
    (reset-flows!)))

(defn make-reset-runtime-fixture
  "Build a `clojure.test` / `cljs.test` `:each` fixture that resets the
  per-process re-frame runtime around each test.

  ## Run-order independence (rf2-7hwnu)

  ## Source-store isolation (EP-0026 §Default Image)

  `make-frame {}` (omitted `:images`) resolves the DEFAULT image — the implicit
  selector over the whole active SOURCE STORE plus the framework standards. Every
  `reg-*` writes a provenance-tagged descriptor into the source store in lockstep
  with the registrar resolver map (`rf.registrar/register!`), so without isolation
  the source store accumulates across the consolidated node-test bundle: two
  sibling namespaces registering the same `[kind id]` under different provenance
  namespaces leave a cross-namespace collision in the shared store, and the next
  test's default projection FAILS LOUD (`:rf.error/image-duplicate-id`). The
  fixture captures the source store's ns-load contents at build time (the same
  stable-baseline moment as the registrar) and RESTORES the store to that baseline
  before and after each test, clearing the resolved-generation cache (keyed on the
  source-store generation) on each reset. Each test's default projection therefore
  sees only its own registrations plus the ns-load set — no cross-bundle leakage.

  The registrar baseline is captured ONCE, when the fixture is built —
  i.e. when the test ns's `(use-fixtures :each ...)` form is evaluated,
  which is AT THIS TEST NS'S LOAD (after its `:require` chain has
  registered its framework + example handlers / subs / views / machines /
  fx). `cljs.test` runs every test ns in one shared bundle; a sibling ns's
  `:each` fixture can restore the registrar to a snapshot that predates
  this ns's load, stranding this ns's registrations (e.g. leaving the
  whole `:event` registrar empty for an alphabetically-later example ns).
  Before each test, the fixture folds the stable ns-load baseline back
  over the live registrar (via [[merge-registrar-snapshots]]) and only
  THEN snapshots — so the snapshot it restores to is always populated with
  this ns's own registrations, regardless of run order. This subsumes the
  bespoke outer-fixture workarounds the todomvc and conformance-corpus
  tests previously carried.

  Per call (i.e. per test), the fixture:

    0. Reinstates the stable ns-load baseline over the live registrar
       (run-order independence — see above), and RESTORES the source store
       to its ns-load baseline + clears the resolved-generation cache
       (EP-0026 source-store isolation — see below).
    1. Captures the current (baseline-reinstated) registrar (so user-test
       registrations can be rolled back without losing ns-load-time
       framework / example registrations).
    2. Resets `rf.frame/frames` to `{}`, plus the flows registry (via
       `flows/reset-flows!` per rf2-4gvb4 — atoms are private behind
       an accessor seam) and the schemas per-frame registry (via the
       encapsulated `:schemas/clear-by-frame!` hook → `clear-schemas-
       by-frame!` per rf2-l5r974 — the raw `schemas-by-frame` atom is
       not re-exported) (when those artefacts are loaded — reset is
       late-bound so JVM tests that don't pull them in are unaffected).
    3. Disposes the currently-installed substrate adapter.
    4. Cancels the machines' in-flight `:after` wall-clock timers.
    5. Clears trace listeners and adapter warn-once caches
       (`warned-non-dom-roots` across re-frame.views and the
       uix adapter).
    6. If an `:adapter` was supplied, installs it and ensures the
       `:rf/default` frame. Otherwise leaves adapter installation to
       the test (or to a separate fixture).
    7. If an `:app-ns` was supplied, reinstates the app rows it removed at
       fixture-build time (registrar + source store in lockstep) — so this
       suite sees its own app, and no other suite's baseline ever did.
    8. If an `:init-fn` was supplied, invokes it (zero-arg). Use this
       hook for per-suite setup that needs the registrar / adapter
       live — e.g. seeding test data into the just-installed adapter's
       app-db.
    9. Runs the test.
   10. Restores the registrar to the captured snapshot.
   11. Resets `rf.frame/frames` back to `{}` for symmetry, and (when their
       artefacts are loaded) the flows registry (via the
       `:flows/reset-flows!` late-bind hook) and the schemas per-frame
       registry (via the `:schemas/clear-by-frame!` late-bind hook).

  Steps 10–11 run in a `finally` block so they fire even on test
  exceptions. The source-store restore they carry is also what takes the
  `:app-ns` rows of step 7 back out — the baseline they restore to never
  held them — so an exceptional teardown leaves no app row behind.

  Options (all optional):
    :adapter      — substrate adapter to install. If omitted, no adapter
                    is installed by the fixture.
    :app-ns       — BUNDLE CO-LOAD HYGIENE. A provenance-namespace PREFIX
                    string naming THIS suite's own app, e.g. `\"realworld-http.\"`
                    — every source-store row whose `:rf.provenance/ns` starts
                    with it is this suite's app. Name your own app's root
                    namespace and cover its WHOLE tree (`\"linearlite.\"`, not
                    `\"linearlite.core\"`); NEVER name a sibling app's.
                    The rows are captured and removed from the live registrar +
                    source store when this fixture is BUILT (i.e. at this test
                    ns's load, after its `:require` chain), so they are absent
                    from every suite's ns-load baseline; they are reinstated
                    through `rf.registrar/register!` before each test, after
                    the reset and before `:init-fn`, and the ordinary
                    source-store restore takes them back out on the way out
                    (including on a test exception).
                    Why: a CLJS node runner loads EVERY test namespace into one
                    bundle before any test runs, so two co-loaded example apps
                    registering the same per-app id (`:rf.route/not-found`; the
                    RealWorld twins' shared `:settings/load`, …) leave two
                    provenance rows in the shared source store, and any suite
                    whose baseline was captured after the second app loaded
                    then fails `make-frame {}` loud with
                    `:rf.error/image-duplicate-id`. When every app suite hides
                    ITSELF, no suite needs to know its sibling's name. A
                    workspace with one app in its bundle never meets the
                    collision and never needs this key.
                    EVERY suite that USES the app declares it, not only the one
                    that loads it first — see the §bundle co-load hygiene
                    section comment for why a suite can otherwise resolve ids it
                    never declared, and lose them when the owner claims the app.
    :init-fn      — zero-arg fn run after adapter install, before the test.
    :clear-kinds  — collection of registry kinds to `clear-kind!`
                    AFTER the snapshot capture and BEFORE the test
                    body runs. Use this when example apps loaded by
                    ns-load time register entries under a kind your
                    test wants a clean slate for. The snapshot still
                    includes those entries, so they're restored on the
                    way out — they only disappear for the duration of
                    the test.
    :ambient-frame
                  — frame id (default `:rf/default`) the fixture binds as
                    `re-frame.frame/*current-frame*` around the test body
                    when an adapter is installed — the carried-invariant
                    ambient scope for bare dispatches (EP-0002). Pass `nil`
                    to OPT OUT: tests that create their own top-level frames
                    via `make-frame` / `with-new-frame` need a clear ambient
                    scope so a frame's `:initial-events` drain synchronously
                    rather than being treated as a mid-cascade child-frame
                    creation. No-op for adapter-less fixtures (they never
                    establish an ambient scope).
    :clear-app-schemas?
                  — boolean. When true, clear the schemas artefact's
                    per-frame side-table (`schemas/schemas-by-frame`)
                    AFTER the snapshot capture and BEFORE the test body
                    runs. App-db schemas live OUTSIDE the registrar
                    (rf2-cq1ak), so this is a separate hook from
                    `:clear-kinds`. The snapshot still includes the
                    per-frame schemas, so they're restored on the way
                    out — they only disappear for the duration of the
                    test.
    :async?       — boolean (default false). Declare the suite ASYNC-CAPABLE.
                    The RETURN SHAPE that delivers that is PLATFORM-DECIDED
                    (rf2-e8ea), not something the caller picks:
                      • `:cljs` → the map-form fixture `{:before … :after …}`,
                        the only shape `cljs.test` will run an `(async done …)`
                        row under (§Async map-form variant below).
                      • `:clj`  → INERT; the fn-form is returned regardless.
                        `clojure.test` has no async tests to be capable OF,
                        and no map-fixture support at all — it INVOKES a
                        fixture, and a Clojure map is `IFn`, so a `{:before …}`
                        fixture composes to a key lookup returning nil and the
                        test body NEVER RUNS (\"Ran 0 tests\", silent GREEN).
                        The fn-form IS the correct async-capable JVM shape.
                    So a `.cljc` suite with async CLJS rows writes a plain
                    `:async? true` — no reader conditional at the call site,
                    and the map-on-JVM silent swallow is unrepresentable.

  ## Async map-form variant (`:async? true`, CLJS)

  This whole section is about CLJS. On the JVM `:async? true` selects nothing
  — see the option's entry above — so everything below describes what
  `:async? true` does when the host is `:cljs`.

  `cljs.test` HARD-ERRORS on a fn-form fixture for an `(async done …)` test
  (\"Async tests require fixtures to be specified as maps\" / \"Fixtures may
  not be of mixed types\"). The reason is structural, not a lint: the fn-form
  establishes the body's ambient frame scope with a dynamic `binding`, which
  is UNWOUND the instant the fixture fn returns — and an async test returns
  immediately (it resolves later, on a fresh tick, via `done`). So the body's
  late-resuming `dispatch-sync` / `subscribe` would run with the binding long
  gone. `:async? true` returns the map shape instead:

    - `:before` runs the full reset AND establishes the ambient frame scope
      with `(set! re-frame.frame/*current-frame* <ambient-frame>)` — a
      PERSISTENT root assignment (not a dynamic binding), so the scope is
      still in effect when the async body resumes on a later tick. A BARE
      `dispatch-sync` in the async body therefore drains and lands, exactly
      as it does under the fn-form in a sync test.
    - `:after` tears the ambient scope back down (`set!` to nil) and runs the
      shared restore half (registrar / source-store / schemas / frames /
      flows baselines). `cljs.test` guarantees `:after` runs AFTER the test's
      `done`, so the restore does not race the async body.

  All other options (`:adapter`, `:app-ns`, `:init-fn`, `:clear-kinds`,
  `:clear-app-schemas?`, `:ambient-frame`) behave identically across both
  shapes. `:ambient-frame nil` / an adapter-less fixture opts out of the
  ambient scope under `:async?` too (no `set!`), for tests that drive their
  own top-level frames.

  FN/MAP MIXING HAZARD. `cljs.test` runs every test ns in ONE shared JS
  runtime, and a fn-form fixture's teardown `(reset! rf.frame/frames {})` clears
  the `:rf/default` frame for whatever ns runs next. The `:async? true`
  fixture is robust to this because its `:before` re-installs the adapter and
  re-ensures `:rf/default` itself every test — it never relies on a
  `:rf/default` left standing by a sibling ns. (A naive hand-rolled map
  fixture that only `set!`s `*current-frame* :rf/default` WITHOUT re-ensuring
  the frame would silence the no-frame-context throw yet silently NOT drain:
  `dispatch-sync` resolves `:rf/default`, finds no frame record, and no-ops
  via the recover-and-emit `:rf.error/frame-destroyed` path. Re-ensuring the
  frame in `:before` is what closes that gap.)

  Returns a `{:before :after}` map when `:async? true` AND the host is CLJS;
  a fixture fn otherwise — which is the default on both hosts, and the ONLY
  shape ever returned on the JVM. Either is suitable for
  `(use-fixtures :each …)` on its own host.

  Example (CLJS):

      (use-fixtures :each
        (test-support/make-reset-runtime-fixture
          {:adapter reagent-adapter/adapter}))

  Example with example-app collision avoidance — schemas tests want a
  clean app-schema slate without losing nine-states.core's other
  registrations:

      (use-fixtures :each
        (test-support/make-reset-runtime-fixture
          {:adapter             reagent-adapter/adapter
           :clear-app-schemas?  true}))

  Example (CLJS, an example-app suite in a bundle that co-loads a rival app
  sharing its id vocabulary — the suite names its OWN app, never the rival):

      (use-fixtures :each
        (test-support/make-reset-runtime-fixture
          {:adapter reagent-adapter/adapter
           :app-ns  \"realworld-http.\"
           :init-fn init!}))

  Example (JVM, default plain-atom adapter):

      (use-fixtures :each
        (test-support/make-reset-runtime-fixture
          {:adapter plain-atom/adapter}))

  Example (CLJS, a suite with async tests — map-form):

      (use-fixtures :each
        (test-support/make-reset-runtime-fixture
          {:adapter plain-atom/adapter :async? true}))

      (deftest drains-in-async-body
        (async done
          (js/setTimeout
            (fn []
              ;; a BARE dispatch-sync — no explicit {:frame …} — drains and
              ;; lands because :before set! the ambient scope persistently.
              (rf/dispatch-sync [:counter/inc])
              (is (= 1 (:n (rf/app-db-value :rf/default))))
              (done))
            0)))

  Example (`.cljc`, a cross-host suite whose CLJS rows are async) — the SAME
  plain `:async? true`, because the shape is platform-decided for you:

      (use-fixtures :each
        (test-support/make-reset-runtime-fixture
          {:adapter plain-atom/adapter :async? true}))"
  ([] (make-reset-runtime-fixture {}))
  ([{:keys [adapter init-fn app-ns] :as opts}]
   ;; `:app-ns` (bundle co-load hygiene) — FIRST, before either baseline below
   ;; is taken, so this suite's own app rows are absent from every baseline in
   ;; the bundle and are reinstated per test by `reset-runtime!`. See the
   ;; §bundle co-load hygiene section comment above for the invariant.
   (when app-ns
     (capture-app-ns-rows! app-ns))
   ;; `:ambient-frame` (EP-0002, rf2-9o48ih): the frame the fixture establishes
   ;; as the ambient scope when an adapter is installed. Default `:rf/default`
   ;; when the key is OMITTED; an explicit `:ambient-frame nil` OPTS OUT.
   ;; Resolved via `contains?` (not an `:or {… :rf/default}` destructure
   ;; default) so this conventional-scope default is not mistaken for a
   ;; frame-RESOLUTION absence-repair floor — it is an explicit fixture-
   ;; option default, not the runtime synthesising a frame from absence
   ;; (the no-rf-default-floor lint keys off the `:or`/`(or …)` shapes).
   ;;
   ;; Stable ns-load baseline (rf2-7hwnu). `make-reset-runtime-fixture` is
   ;; called when the test ns's `(use-fixtures :each ...)` form is evaluated —
   ;; i.e. AT THIS TEST NS'S LOAD, after its `:require` chain has registered
   ;; its framework + example handlers / subs / views / machines / fx.
   ;; Capturing the registrar + source store HERE — once, at fixture-build
   ;; time — pins every registration this ns brought live as a stable baseline,
   ;; folded back over the live registrar before each test by
   ;; `reinstate-and-snapshot!` (run-order independence; see that helper and
   ;; the shared-halves section comment above for the full EP-0026 rationale).
   (let [ns-load-baseline      (snapshot-registrar)
         source-store-baseline* @rf.source-store/kind->id->ns->descriptor
         source-store-baseline (fn [] source-store-baseline*)
         ambient-frame         (if (contains? opts :ambient-frame)
                                 (:ambient-frame opts)
                                 :rf/default)
         ;; EP-0002: establish the ambient frame scope ONLY when an adapter is
         ;; installed (which also ensured `:rf/default`) AND `:ambient-frame`
         ;; was not opted out to nil. Adapter-less / opted-out fixtures run the
         ;; body frameless — those tests own their own frame creation, so a
         ;; synthetic ambient `:rf/default` would make a top-level `make-frame`
         ;; look mid-cascade (the in-flight-cascade heuristic, rf2-cufbh) and
         ;; async-queue its `:initial-events`.
         scope?                (boolean (and adapter ambient-frame))
         ;; rf2-4775uc — `:init-fn` (per-suite setup that needs the registrar /
         ;; adapter live, e.g. an app's `register-all!`) runs UNDER the same
         ;; ambient scope as the body, so a frame-local op in setup
         ;; (`reg-app-schema` / a bare `dispatch`) does not throw
         ;; `:rf.error/no-frame-context`. For the fn-form that scope is the
         ;; enclosing `binding`; for the async map-form it is the persistent
         ;; `set!` already in effect when this runs.
         run-init!             (fn [] (when init-fn (init-fn)))
         ;; rf2-e8ea — `:async?` declares the suite ASYNC-CAPABLE; the SHAPE
         ;; that delivers that is decided HERE, per host, because the two
         ;; runners disagree about what a fixture even is:
         ;;
         ;;   • `cljs.test` — `execution-strategy` classifies an ns's fixtures
         ;;     (`{:map :async :fn :sync}`); a `:sync` ns whose body returns an
         ;;     async object throws `::async-disabled`, which `test-var-block*`
         ;;     catches and RE-THROWS as "Async tests require fixtures to be
         ;;     specified as maps.  Testing aborted." — outside per-test
         ;;     accounting, so it unwinds the whole bundle. On CLJS an async-
         ;;     capable fixture MUST therefore be the map.
         ;;   • `clojure.test` — has no map-fixture support at all. Its
         ;;     `compose-fixtures` INVOKES each fixture (`(f1 (fn [] (f2 g)))`)
         ;;     and a Clojure map is `IFn`, so a `{:before …}` fixture composes
         ;;     to a key lookup returning nil and the test body NEVER RUNS: the
         ;;     namespace reports "Ran 0 tests" and reads GREEN. (Hit in-tree
         ;;     while implementation/ssr/test/re_frame/ssr/streaming_component_
         ;;     cljs_test.cljc was being written — see its comment.) And
         ;;     `clojure.test` has no async tests to be capable OF, so the
         ;;     fn-form IS the correct async-capable JVM shape.
         ;;
         ;; Guarding here rather than at the call site makes the map-on-JVM
         ;; silent swallow UNREPRESENTABLE: a `.cljc` suite with async CLJS
         ;; rows writes a plain `:async? true` and gets the right shape on each
         ;; host by construction, with no reader conditional to get wrong.
         map-shape?            #?(:clj false :cljs (boolean (:async? opts)))]
     (if map-shape?
       ;; ---- async map-form (cljs.test only) — see §Async map-form variant.
       ;; cljs.test runs tests sequentially (each async test's `done` gates the
       ;; next), so the :before/:after pair never overlaps and a single atom
       ;; safely threads the restore context (captured in :before, consumed in
       ;; :after) between them.
       (let [ctx-atom (atom nil)]
         {:before
          (fn before-reset-runtime []
            (let [ctx (reinstate-and-snapshot! ns-load-baseline (source-store-baseline))]
              (reset! ctx-atom ctx)
              (reset-runtime! opts (:clear-fn ctx))
              ;; Establish the ambient scope PERSISTENTLY — `set!` on the root
              ;; var, NOT a dynamic `binding`. A binding would be unwound the
              ;; instant this :before returns, long before the async test body
              ;; resumes on a later tick; the `set!` survives so a bare
              ;; `dispatch-sync` in that body resolves `:rf/default`, finds the
              ;; frame `reset-runtime!` just ensured, and drains + lands.
              (when scope?
                (set! rf.frame/*current-frame* ambient-frame))
              (run-init!)))
          :after
          (fn after-reset-runtime []
            ;; Tear the persistent ambient scope back down (the fn-form's
            ;; binding-unwind equivalent), then run the shared restore half.
            ;; cljs.test runs :after AFTER the test's `done`, so this never
            ;; races the async body.
            (when scope?
              (set! rf.frame/*current-frame* nil))
            (finish-runtime-reset! @ctx-atom (source-store-baseline))
            (reset! ctx-atom nil))})
       ;; ---- sync fn-form (default; clojure.test + non-async cljs.test) ----
       ;; EP-0002 (rf2-nn0jqa): when an adapter is installed the fixture
       ;; ensured `:rf/default` and binds it as the body's ambient scope — the
       ;; carried-invariant equivalent of wrapping every test in
       ;; `(with-frame :rf/default …)`, so a bare `dispatch-sync` lands. An
       ;; inner `with-frame` re-binds and an explicit `{:frame …}` opt still
       ;; wins. The `finally` restore fires even on a test exception.
       (fn [test-fn]
         (let [ctx (reinstate-and-snapshot! ns-load-baseline (source-store-baseline))]
           (try
             (reset-runtime! opts (:clear-fn ctx))
             (if scope?
               (binding [rf.frame/*current-frame* ambient-frame]
                 (run-init!)
                 (test-fn))
               (do
                 (run-init!)
                 (test-fn)))
             (finally
               (finish-runtime-reset! ctx (source-store-baseline))))))))))

;; ---- test-flavoured helpers (rf2-0l3s / rf2-hkr5) -------------------------
;;
;; A thin wrapper over `frame-app-db-value` for ergonomic test code. The
;; fixture machinery above carries the heavy lifting; this helper is
;; composition sugar.
;;
;; Per Spec 008 §Built-in test-runner namespace it lives under
;; re-frame.test-support so users `(:require [re-frame.test-support :as t])`
;; once and reach the full testing surface — including assert-path-equals —
;; without an additional require.

(defn- resolve-frame
  "Frame-resolution chain shared by the helpers below:
     1. `:frame` key in opts when supplied;
     2. `(rf.frame/current-frame)` — picks up `with-frame` bindings,
        defaults to `:rf/default`."
  [opts]
  (or (:frame opts) (rf.frame/current-frame)))

(defn assert-path-equals
  "Assert `(= expected-val (get-in app-db path))` against the resolved
  frame's `app-db`. Mismatch is reported via `clojure.test/is` — the
  failure carries the actual value so the diagnostic is one line.

  Call shapes:

    (assert-path-equals path expected-val)
    (assert-path-equals path expected-val {:frame :test/foo})

  Frame resolution: `:frame` opt → `(current-frame)` → `:rf/default`.

  Returns `true` when the assertion passes, `false` otherwise — the
  `clojure.test` failure has already been reported in either case, so
  callers rarely care about the boolean.

  Mirrors the `:rf.assert/path-equals` event used inside Story `:play`
  blocks (per Spec 007 §Play functions). The fn-side and the event-side
  share the same name root so a reader navigating between the two
  surfaces does not need a translation table.

  Per Spec 008 §Normative surface and the rf2-hkr5 / rf2-0l3s / rf2-8j9m6
  decisions."
  ([path expected-val]
   (assert-path-equals path expected-val nil))
  ([path expected-val opts]
   (let [opts     (or opts {})
         frame-id (resolve-frame opts)
         actual   (get-in (rf.frame/frame-app-db-value frame-id) path)
         pass?    (= expected-val actual)]
     (ctest/do-report
       {:type     (if pass? :pass :fail)
        :message  (str "assert-path-equals mismatch at path " (pr-str path)
                       " on frame " frame-id)
        :expected expected-val
        :actual   actual})
     pass?)))

;; ---- deterministic wait helper (rf2-ka3n6 / rf2-fun38) -------------------
;;
;; Replaces incidental fixed `Thread/sleep N` / `js/setTimeout` waits that
;; exist to let an *observable* event (router drain, cascade settle, sub
;; re-fire, in-flight registry entries appearing/clearing) complete.
;;
;; NOT for timer-semantics tests — those should keep their sleep and
;; annotate that intent locally (the sleep IS the contract under test:
;; grace-period elapse, throttle/debounce window, host-clock advancement,
;; "prove a thing did NOT happen within window N").
;;
;; Per-platform shape (rf2-fun38):
;;   JVM:  synchronous — returns the truthy value, throws on timeout.
;;   CLJS: async       — returns a `js/Promise`. Resolves with the truthy
;;                       value on success, rejects with an `ex-info`-style
;;                       error on timeout. Designed to compose with
;;                       `cljs.test/async`:
;;
;;                         (deftest something
;;                           (async done
;;                             (-> (test-support/poll-until
;;                                   #(some? (rf/app-db-value :rf/default)))
;;                                 (.then (fn [db] (is (...)) (done)))
;;                                 (.catch (fn [e] (is false (.-message e))
;;                                                 (done))))))
;;
;; Single name across platforms — read sites are mechanical conversions.
;; The opts map is identical (`:timeout-ms` / `:interval-ms` / `:label`).
;; A central helper means CI flake budgets land in one place.

(defn- poll-timeout-error
  "Shared timeout-error constructor — same shape JVM / CLJS so test code
  that pattern-matches on the canonical `:rf.error/id
  :rf.error/poll-until-timeout` discriminator (per Spec 009) works on
  either runtime."
  [label elapsed-ms]
  (rf.error/thrown-ex-info :rf.error/poll-until-timeout
                        'rf/poll-until
                        (str "poll-until timed out"
                             (when label (str " — " label)))
                        {:extra {:elapsed-ms elapsed-ms
                                 :label      label}}))

#?(:clj
   (defn poll-until
     "Bounded-deadline poll for `(pred)` to return truthy. Returns the
     truthy value on success; throws `ex-info` on timeout carrying
     `:rf.error/id` `:rf.error/poll-until-timeout` (the canonical
     discriminator, per Spec 009), plus `:elapsed-ms` and the supplied
     `:label` (when given) to identify the assertion site.

     `opts` (all optional):
       :timeout-ms   default 2000 — overall deadline.
       :interval-ms  default 5    — sleep between probes.
       :label        string/keyword used in the timeout message.

     Use this in JVM tests that previously called `(Thread/sleep N)` to
     wait for the async router to drain, a pipeline run to settle, or
     a sub to re-fire. The deadline is generous; tests fail fast on a
     truly stuck condition, not on CI scheduler jitter."
     ([pred] (poll-until pred nil))
     ([pred opts]
      (let [{:keys [timeout-ms interval-ms label]
             :or   {timeout-ms 2000 interval-ms 5}} opts
            start    (System/currentTimeMillis)
            deadline (+ start timeout-ms)]
        (loop []
          ;; A transient throw from `pred` (e.g. reading state mid-transition) is
          ;; a falsy probe — keep polling until the deadline. Mirrors the CLJS arm
          ;; below, so the single cross-platform helper has uniform
          ;; pred-exception semantics.
          (let [v (try (pred) (catch Throwable _ false))]
            (cond
              v v
              (>= (System/currentTimeMillis) deadline)
              (throw (poll-timeout-error
                       label (- (System/currentTimeMillis) start)))
              :else (do (Thread/sleep ^long interval-ms) (recur)))))))))

#?(:cljs
   (defn poll-until
     "Bounded-deadline poll for `(pred)` to return truthy. Returns a
     `js/Promise` that resolves with the truthy value on success or
     rejects with an `ex-info`-style error carrying `:rf.error/id`
     `:rf.error/poll-until-timeout` (the canonical discriminator, per
     Spec 009), plus `:elapsed-ms` and `:label` on timeout.

     `opts` (all optional):
       :timeout-ms   default 2000 — overall deadline.
       :interval-ms  default 5    — gap (ms) between probes; scheduled
                                    via `js/setTimeout`.
       :label        string/keyword used in the timeout message.

     `pred` is invoked synchronously on each tick. If `pred` itself
     returns a `js/Promise`, the returned promise is awaited and its
     resolved value drives the truthy check — so `pred` can be either
     synchronous (the common case) or `async`/Promise-returning.

     Use this in CLJS tests under `cljs.test/async` that previously
     chained nested `js/setTimeout` calls to wait for a router drain,
     pipeline run, or sub re-fire. The Promise composes with `.then` /
     `.catch` and integrates cleanly with `async done`.

     Put the rejection handler UPSTREAM of the single trailing step that
     calls `done`, and call `done` exactly once with nothing after it
     (rf2-d3tc / rf2-qpns / rf2-fyba). `cljs.test/run-block` hands `done` a
     continuation that runs the WHOLE REMAINDER of the run synchronously, so
     a `.catch` sitting downstream of `done` claims whatever a later
     namespace throws as this row's failure — printing it against this row's
     label — and then calls `done` a SECOND time, which re-forces
     `run-block`'s unrealized delay and re-runs the offending namespace.
     The handler reports and releases; it never finishes:

         (deftest drains
           (async done
             (-> (test-support/poll-until
                   #(= 3 (:n (rf/app-db-value :rf/default)))
                   {:label \"counter reached 3\"})
                 (.then  (fn [_] (is (= 3 ...))))
                 (.catch (fn [e] (is false (.-message e)) nil))
                 (.then  (fn [_] (done))))))

     Teardown that both paths share belongs in that trailing step, where it
     is written once and still runs once per path."
     ([pred] (poll-until pred nil))
     ([pred opts]
      (let [{:keys [timeout-ms interval-ms label]
             :or   {timeout-ms 2000 interval-ms 5}} opts
            start    (.now js/Date)
            deadline (+ start timeout-ms)]
        (js/Promise.
          (fn [resolve reject]
            (letfn [(settle [v]
                      (cond
                        v (resolve v)
                        (>= (.now js/Date) deadline)
                        (reject (poll-timeout-error
                                  label (- (.now js/Date) start)))
                        :else (js/setTimeout tick interval-ms)))
                    (tick []
                      (let [raw (try (pred) (catch :default _ false))]
                        (if (instance? js/Promise raw)
                          (-> ^js/Promise raw
                              (.then settle)
                              (.catch (fn [_] (settle false))))
                          (settle raw))))]
              (tick))))))))

;; ---- trace-recorder bracket (rf2-64iuw) ----------------------------------
;;
;; Every adapter test ns that wants to capture a stream of trace events
;; used to define its own `defn- <verb>-traces[!]` wrapper around
;; `rf.trace.tooling/register-listener!` plus an atom. The verb
;; (collect-/record-), the `!` suffix, the return shape (bare atom vs
;; `{:traces a :stop! f}`), and the cleanup convention (manual key-keyed
;; `unregister-listener!` vs no cleanup at all) all diverged file-by-file
;; for the same underlying pattern. rf2-64iuw folds them into a single
;; bracket macro: register-on-entry, unregister-on-exit (try/finally),
;; with-redefs-shaped body.
;;
;; Macro lives in the `#?(:clj ...)` arm so CLJS test files reach it via
;; `(:require-macros [re-frame.test-support :refer [with-trace-recorder!]])`
;; — mirrors how `re-frame.core`'s call-site macros (`with-frame`,
;; `with-new-frame`, …) ship.

#?(:clj
   (defmacro with-trace-recorder!
     "Bracket `body` with a fresh trace-tooling listener that accumulates
     matching trace events into an atom bound to `recs-sym`. The listener
     is registered before `body` runs and unregistered in a `finally` on
     the way out — even if `body` throws.

     Shape:

         (with-trace-recorder! [recs] opts? body+)

     `recs` is a symbol that will be `let`-bound to the recording atom for
     `body`'s scope. Deref it inside the body (after the events of interest
     fire) to read the captured vector / map.

     `opts` (optional map literal; keys evaluated at macroexpansion):

       :pred   1-arg fn `(fn [ev] truthy?)` — only events for which
               `(pred ev)` is truthy are conj'd. Default: every event
               accepted.
       :shape  `:flat` (default) — atom holds a vector of events,
               appended via `(swap! a conj ev)`.
               `:by-op` — atom holds a map keyed by `(:operation ev)`,
               each value a vector of matching events. Equivalent to
               the prior `record-by-op!` / `record-op!` per-file helpers.
       :key    listener key (any value). Default: a freshly-gensym'd
               keyword unique to this expansion site, so two
               `with-trace-recorder!` brackets in the same deftest do
               not collide on the trace-tooling listener registry.

     Returns the value of `body`'s final form.

     Replaces the per-file `collect-traces` / `collect-warnings` /
     `collect-dispose-traces!` / `record-render-traces!` / `record-traces!`
     / `record-op!` / `record-by-op!` / `collect-traces!` helpers (rf2-5r7eh
     audit / rf2-64iuw consolidation). See [Spec 008 §Test-flavoured
     helpers](../../../../../spec/008-Testing.md) for the broader
     test-support surface.

     Example — flat shape, default filter, simple read:

         (with-trace-recorder! [traces]
           (rf/dispatch-sync [:my-event])
           (is (= 1 (count (filter #(= :rf.event/run-start (:operation %))
                                   @traces)))))

     Example — filter on `:warning` op-type, by-op shape:

         (with-trace-recorder! [observed
                                {:pred  #(contains? #{:rf.view/render
                                                      :rf.view/rendered}
                                                    (:operation %))
                                 :shape :by-op}]
           (render-twice!)
           (is (= 2 (count (:rf.view/render @observed))))
           (is (= 2 (count (:rf.view/rendered @observed)))))"
     {:arglists '([[recs-sym opts?] body+])}
     [bindings & body]
     (when-not (and (vector? bindings)
                    (or (= 1 (count bindings))
                        (= 2 (count bindings))))
       (throw (ex-info "with-trace-recorder! expects [recs-sym] or [recs-sym opts-map]"
                       {:bindings bindings})))
     (let [recs-sym (first bindings)
           opts     (or (second bindings) {})
           {:keys [pred shape key]
            :or   {pred  `(constantly true)
                   shape :flat}} opts
           init     (case shape
                      :flat  `(atom [])
                      :by-op `(atom {}))
           on-event (case shape
                      :flat  `(fn [ev#] (when (~pred ev#)
                                          (swap! ~recs-sym conj ev#)))
                      :by-op `(fn [ev#] (when (~pred ev#)
                                          (swap! ~recs-sym update
                                                 (:operation ev#)
                                                 (fnil conj []) ev#))))
           key-form (or key `(keyword (gensym "rf-trace-recorder-")))]
       `(let [~recs-sym       ~init
              listener-key#   ~key-form]
          (rf.trace.tooling/register-listener! listener-key# ~on-event)
          (try
            ~@body
            (finally
              (rf.trace.tooling/unregister-listener! listener-key#)))))))
