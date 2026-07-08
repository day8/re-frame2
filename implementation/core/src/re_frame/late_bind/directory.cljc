(ns re-frame.late-bind.directory
  "Authoritative inventory of every key published through the
  `re-frame.late-bind` hook registry.

  Plain CLJC data — one entry per published key — so the inventory is:

    - editor-friendly (jump-to-definition, no docstring scrolling),
    - diff-friendly (each row is its own line),
    - machine-readable (the drift test in
      `implementation/core/test/re_frame/late_bind_drift_test.clj`
      walks this var and compares it against `set-fn!` call sites
      across every artefact).

  Each entry is a map:

    {:key          fully-qualified late-bind hook key (keyword, REQUIRED)
     :producer-ns  symbol naming the namespace that publishes the key
                   (REQUIRED — may be a vector when an adapter routing
                   chain has multiple publishers)
     :design-bead  decision-bead id that introduced or shaped the key
                   (string, optional)
     :description  one-line summary of what the hook does
                   (string, REQUIRED)
     :chained?     true when the hook is registered cumulatively rather
                   than once-per-key (see `:adapter/clear-warn-once-caches!`
                   and the routed `:adapter/*` hooks — chained / routed
                   hooks publish from multiple namespaces and the drift
                   test treats `:producer-ns` as a vector covering them
                   all)}

  Adding a new hook? Add the entry HERE and call
  `(re-frame.late-bind/set-fn! ...)` in the producing ns — the drift
  test fails on either omission. Do NOT update both the producer ns
  and a free-floating prose comment; the drift check is the only
  source of truth.")

#?(:clj (set! *warn-on-reflection* true))

(def hooks
  "Vector of hook-directory entries — see the ns docstring for shape.

  Ordering: grouped into four primary purposes, and within
  each purpose by namespace prefix so additions land near their siblings:

    1. CYCLE-BREAKING  — leaf namespaces (router, subs) calling into
       higher-level namespaces without `:require`ing them, breaking a
       compile-time load cycle. Lookup-or-no-op is degenerate (the
       producer always loads at boot); the indirection exists solely to
       silence cljs.compiler's circular-dependency error.

    2. CROSS-ARTEFACT  — re-frame.core (or a leaf consumer in core)
       reaching into an OPTIONAL feature artefact (schemas, flows,
       routing, machines, ssr, epoch, http, elision) without a
       static `:require`. When the artefact is absent from the
       classpath the lookup returns nil and the consumer no-ops (or
       throws a clear `:rf.error/<artefact>-artefact-missing` via
       `require-fn!`). Also covers the always-on observability surface
       (`:event-emit/*`, `:error-emit/*`) — production-survivor seams
       that fire on every dispatch. NOTE the `classification` rows are NOT
       an absent-optional-artefact case: `re-frame.classification`
       ships IN core and is side-effect-required at boot (core.cljc),
       so the hooks are bound in every canonical build — the
       indirection is the production-DCE/elision seam (the dev-only
       classification projection surface constant-folds out of `:advanced` +
       `goog.DEBUG=false` bundles), not decoupling from an artefact
       that could be missing. See the per-key notes on those rows.

    3. ADAPTER-INJECTION — substrate adapters (Reagent / reagent-slim /
       UIx / Helix / test-react) publish CLJS-side primitives the core
       runtime consumes (`:adapter/current-frame`, `:adapter/ratom`,
       `:adapter/after-render`, …) and React-shaped view machinery
       (`:reagent/set-hiccup-emitter!`, `:views/*`). Routed via
       `substrate-adapter/route-hook!` so the installed-adapter
       identity dispatches; chained across every loaded adapter so a
       single SSR ns-load auto-wires every adapter's slot.

    4. HOT-RELOAD / DEV-TOOLING — `:trace/*`, `:trace.tooling/*`,
       `:trace.cascade/*`, `:privacy/*`. Dev-only surfaces gated on
       `interop/debug-enabled?` whose producer namespaces DCE under
       `:advanced` + `goog.DEBUG=false`; the late-bind indirection lets
       production CLJS bundles short-circuit cleanly (lookup returns
       nil, consumer no-ops) without surface-pruning the call sites.

  Multi-category keys (e.g. `:views/*` are both adapter-injection AND
  cycle-break depending on the consumer; `:event-emit/*` is both cross-
  artefact AND production observability) are placed in the dominant
  category — the one a reader first looks under when answering 'what
  kind of forward-reference is this layer for?' The drift test is
  unaffected by ordering; the grouping is documentation-only."
  [;; ===========================================================================
   ;; GROUP 1 — CYCLE-BREAKING
   ;;
   ;; Leaf namespaces calling into higher-level namespaces without
   ;; `:require`ing them. The indirection exists to break compile-time
   ;; load cycles; the producer always loads at boot, so lookup never
   ;; misses in production.
   ;; ===========================================================================

   ;; ---- re-frame.router (foundational dispatch seam) ------------------------
   {:key         :router/dispatch!
    :producer-ns 're-frame.router
    :description "Enqueue an event for processing by the drain loop."}
   {:key         :router/dispatch-sync!
    :producer-ns 're-frame.router
    :description "Process an event synchronously, bypassing the drain queue."}

   ;; ---- EP-0023 inline-registration lowering -------------------------------
   ;; `re-frame.image-assembly` lowers an image's inline `:registrations` fn
   ;; bodies (`:impl`) into the runnable descriptor shape per kind so they route
   ;; through dispatch identically to a `:include-ns`-selected registration
   ;; (EP-0023 §Image Fragments — "the same runtime descriptor shape"). Each
   ;; kind's ns publishes its lowering; image-assembly cannot static-require any
   ;; of them (subs requires live-frame requires image-assembly → cycle).
   {:key         :image/lower-inline-event
    :producer-ns 're-frame.events
    :design-bead "rf2-ffc6s0"
    :description "Lower an inline :reg-event descriptor's :impl fn body into the runnable event-handler slots (:handler-fn + the :interceptors chain carrying the :rf/event-handler wrapper) so an image's inline event routes through a frame-targeted dispatch. Consumed by re-frame.image-assembly during assembly."}
   {:key         :image/lower-inline-sub
    :producer-ns 're-frame.subs
    :design-bead "rf2-ffc6s0"
    :description "Lower an inline :reg-sub descriptor's :impl computation fn into the runnable layer-1 (:input-kind :db) sub slots (:handler-fn + :input-kind + :input-signals) so an image's inline sub computes through a frame-targeted subscribe. Consumed by re-frame.image-assembly during assembly."}
   {:key         :image/lower-inline-fx
    :producer-ns 're-frame.fx
    :design-bead "rf2-ffc6s0"
    :description "Lower an inline :reg-fx descriptor's :impl fn body into the runnable fx slot (:handler-fn) so an image's inline fx runs when an event handler emits it. Consumed by re-frame.image-assembly during assembly."}
   {:key         :image/lower-inline-cofx
    :producer-ns 're-frame.cofx
    :design-bead "rf2-ffc6s0"
    :description "Lower an inline :reg-cofx descriptor's :impl supplier fn into the runnable cofx slots (:handler-fn + the :recordable? / :provided? grade flags) so an image's inline cofx is delivered through a frame-targeted cascade. Consumed by re-frame.image-assembly during assembly."}

   ;; ---- re-frame.subs --------------------------------------------------------
   {:key         :subs/subscribe-once
    :producer-ns 're-frame.subs
    :description "Subscribe and immediately deref (snapshot value, no reaction)."}
   {:key         :subs.cache/dispose-all-for-frame-destroy!
    :producer-ns 're-frame.subs.cache
    :design-bead "rf2-x3m8c"
    :description "Dispose every cached subscription in a destroyed frame's sub-cache, emitting one `:rf.sub/dispose` per slot with `:rf.sub/reason :frame-destroy`. Invoked by `frame/destroy-frame!` via late-bind so `re-frame.frame` carries no static dep on `re-frame.subs.cache` (which requires `frame`)."}

   ;; ---- re-frame.fx (:dispatch-later host-timer side table) -----------------
   ;; Core (NOT an optional artefact): `re-frame.fx` is side-effect-required at
   ;; boot, so these hooks are bound in every canonical build. Reached via
   ;; late-bind because `re-frame.frame/destroy-frame!` (and the test-support
   ;; reset fixture) cannot static-require `re-frame.fx` — fx requires nothing
   ;; of frame, and a back-require would invert the load order — exactly the
   ;; `:subs.cache/dispose-all-for-frame-destroy!` cycle-break above.
   {:key         :fx/on-frame-destroyed!
    :producer-ns 're-frame.fx
    :design-bead "rf2-uxz52g"
    :description "Cancel + drop the destroyed frame's still-pending `:dispatch-later` host timers (re-frame.fx/dispatch-later-timers, keyed by [frame-id timer-id] → host handle). Each `:dispatch-later` arms a host-clock timer whose thunk dispatches the deferred event into the frame; left armed across destroy it fires a dead-on-arrival dispatch into a torn-down frame and its armed handle + captured closure leak until the delay elapses (unbounded under frame churn). Host-side transient state (NOT runtime-db, off the epoch/SSR egress wire), mirroring the resources / machines timer tables. Invoked by `frame/destroy-frame!` by key."}
   {:key         :fx/reset-dispatch-later-timers!
    :producer-ns 're-frame.fx
    :design-bead "rf2-uxz52g"
    :description "Test-isolation reset: cancel + drop EVERY frame's pending `:dispatch-later` host timers (re-frame.fx/dispatch-later-timers). Host-side transient state the runtime / frames reset does not touch; the shared CLJS make-reset-runtime-fixture reset-hooks table fires it per test so a stale armed timer from a sibling test can't fire mid-next-test (mirrors :machines/reset-timers!)."}

   ;; ===========================================================================
   ;; GROUP 2 — CROSS-ARTEFACT
   ;;
   ;; re-frame.core reaches into an OPTIONAL feature artefact (schemas,
   ;; flows, routing, machines, ssr, epoch, http, elision) without a
   ;; static `:require`. When the artefact is absent from the
   ;; classpath the lookup returns nil and the consumer no-ops or
   ;; throws `:rf.error/<artefact>-artefact-missing` via `require-fn!`.
   ;; Also covers always-on observability (`:event-emit/*`,
   ;; `:error-emit/*`) — production-survivor seams that fire on every
   ;; dispatch and ship in their own namespaces.
   ;;
   ;; EXCEPTION — the `:classification/*` rows are NOT an absent-optional-
   ;; artefact case. `re-frame.classification` ships IN core and is side-
   ;; effect-required at boot (core.cljc), so the hooks are bound in
   ;; every canonical build — it is NEVER absent. For the DEV-GATED
   ;; projection hook the late-bind hop is the production-DCE/elision
   ;; SEAM, not decoupling: its emit-time surface rides
   ;; `interop/debug-enabled?` and is gated at the trace/emit! call sites,
   ;; so the hop keeps the lookup off the always-on registration path. The
   ;; always-on `:classification/redact-event-by-registration` prod redactor is
   ;; reached through the SAME indirection. `re-frame.classification/validate-classification!`
   ;; is always-on, same-artefact, and already bundled, so the seam rationale
   ;; does not apply to it: reg-event / reg-fx / reg-cofx / reg-sub call it by
   ;; DIRECT REQUIRE. See the per-key notes below.
   ;; ===========================================================================

   ;; ---- re-frame.elision (frame-owned app-db egress registry) ---------------
   ;; EP-0015 §8: durable app-db classification is frame-owned
   ;; (`re-frame.frame-classification`) — the app-db egress registry is fed by
   ;; the frame, not by schemas.
   {:key         :elision/sensitive-declarations
    :producer-ns 're-frame.elision
    :design-bead "rf2-w3n5u"
    :description "Return the frame's frame-owned sensitive app-db path declarations."}
   {:key         :elision/clear-warning-cache!
    :producer-ns 're-frame.elision
    :design-bead "rf2-w3n5u"
    :description "Reset the once-per-(frame,path) :rf.warning/large-value-unschema'd cache."}

   ;; ---- re-frame.classification (EP-0025 data classification) --------------
   ;; NOT an optional-artefact decoupling: `re-frame.classification` ships IN
   ;; core and is boot side-effect-required (core.cljc), so these hooks are bound
   ;; in every canonical build. For the DEV-GATED projection hook the indirection
   ;; is the production-DCE/elision SEAM — its emit-time surface rides
   ;; `interop/debug-enabled?` and is gated at the trace/emit! call sites, so the
   ;; late-bind hop keeps the lookup off the always-on registration path:
   ;;   :classification/registration-classification,
   ;;   :classification/project-trace-event.
   ;; ALWAYS-ON production survivor still reached through the indirection:
   ;;   :classification/redact-event-by-registration (the production egress redactor).
   ;; `re-frame.classification/validate-classification!` is reached by DIRECT
   ;; REQUIRE rather than a late-bind hook: it is the ONE always-on, NON-dev-gated
   ;; surface, so the DCE-seam rationale does not apply. EP-0025 removed the
   ;; imperative add-marks / set-marks API and ALL sub-output propagation, so the
   ;; former :marks/resolve-sub-output-marks / :marks/mark-sub-output! /
   ;; :marks/clear-sub-output-marks! hooks are GONE (no propagation table).
   {:key         :classification/registration-classification
    :producer-ns 're-frame.classification
    :design-bead "rf2-w46fpt"
    :description "Read the classification declaration for a (kind, id), or nil — DERIVED at read time from registrar/handler-meta (rf2-ehexnw), no side-table, uniformly for every kind. EP-0025: there is no derived-output sensitivity (no propagation). Hook retained for the directory contract; re-frame.machines (snapshot / SSR trace egress) calls `re-frame.classification/registration-classification` by direct require."}
   {:key         :classification/project-trace-event
    :producer-ns 're-frame.classification
    :design-bead "rf2-vw7f5"
    :description "Emit-time chokepoint for trace bus — walks the assembled trace event's tags and substitutes sentinels at declared paths (Spec 015 §Egress projection). EP-0025: no value-match, no propagation — path-based redaction only."}
   {:key         :classification/redact-event-by-registration
    :producer-ns 're-frame.classification
    :design-bead "rf2-qe6v1u"
    :description "ALWAYS-ON (NOT a DCE seam, rf2-eq7m0x — the registration classification is populated in production too; only the emit-time TRACE projection is dev-gated): apply an event handler's REGISTRATION-OWNED :sensitive / :large classification to a [event-id arg-map] vector (EP-0015 — event args are registration-owned transient payloads). Consumed by re-frame.projection for the :rf.observe/error / handled-event :event slot."}

   ;; ---- re-frame.frame-classification (EP-0015 §9 observability) ----
   ;; `re-frame.frame/reg-frame` consults this to validate the surviving
   ;; frame-owned :observability sink policy. Reached via late-bind because
   ;; frame-classification requires frame, so a static require would cycle.
   ;;
   ;; EP-0025: the durable app-db classification install hooks (validate+extract /
   ;; install! / install-from-config!) AND the :frame-classification/http-carriers
   ;; resolver hook were REMOVED — the frame :sensitive / :large {:app-db …}
   ;; annotation and the :sensitive {:http …} carrier block no longer exist.
   ;; Durable app-db classification rides the commit-plane effects
   ;; (re-frame.elision); HTTP carriers ride the :rf.http/managed reg-fx
   ;; registration (:carriers block, resolved by re-frame.http.privacy); and
   ;; reg-frame now only VALIDATES the surviving :observability policy.
   {:key         :frame-classification/validate!
    :producer-ns 're-frame.frame-classification
    :design-bead "rf2-ueg1tn"
    :description "Validate a reg-frame config's surviving frame-owned policy key (:observability sink policy). Fails loud (:rf.error/bad-frame-classification) on an unknown observability key / malformed sink entry; the retired :sensitive (HTTP carriers moved to :rf.http/managed; app-db moved to commit-plane effects) and :large frame keys now fail loud here (EP-0025). Pure, installs nothing — called EARLY by reg-frame (before the container exists) so a bad declaration leaves no half-registered frame (EP-0015 §9). EP-0025: the :frame-classification/http-carriers resolver hook is GONE — HTTP carrier classification moved onto the :rf.http/managed reg-fx registration (:carriers block), resolved by the http artefact (re-frame.http.privacy/managed-carriers reads registrar/handler-meta directly)."}

   ;; ---- re-frame.flows -------------------------------------------------------
   ;; Both the public `rf/reg-flow` / `rf/clear-flow` surfaces AND the
   ;; `:rf.fx/reg-flow` / `:rf.fx/clear-flow` runtime fxs route through
   ;; the same two hooks. The api-shape `(arg opts)` carries the `:frame`
   ;; opt the fx-side path needs, so no separate `*-fx!` hook pair is
   ;; required.
   {:key         :flows/reg-flow
    :producer-ns 're-frame.flows
    :description "Register a flow definition with the runtime (public-API + :rf.fx/reg-flow)."}
   {:key         :flows/clear-flow
    :producer-ns 're-frame.flows
    :description "Remove a previously-registered flow definition (public-API + :rf.fx/clear-flow)."}
   {:key         :flows/run-flows-on-db
    :producer-ns 're-frame.flows
    :design-bead "rf2-u0zz5"
    :description "Run the frame's flows over the pending frame-state, returning the flow-augmented APP-DB. Called `[frame db]` (app-db only) or `[frame db runtime-db]` (both pending partitions — EP-0001 §535-551, rf2-4eisfr). Bare `:inputs` resolve against app-db; `[:rf.db/runtime …]` inputs resolve against runtime-db (any flow may read runtime-db; only writes are reserved). Invoked by the router's outermost flows-after-interceptor to transform the handler's pending `:db` effect (after the rest of the `:after` chain, before the `:db` install)."}
   {:key         :flows/reset-last-inputs!
    :producer-ns 're-frame.flows
    :description "Reset memoised last-input snapshots (test isolation)."}
   {:key         :flows/snapshot-last-inputs
    :producer-ns 're-frame.flows
    :design-bead "rf2-4wqu6"
    :description "Snapshot a frame's dirty-check (`last-inputs`) rows as a plain `{flow-id inputs}` map. The router's flows-after-interceptor captures this BEFORE the flow transform advances the rows so a post-commit schema/machine-data rollback can roll the dirty-check bookkeeping back in lock-step with app-db (paired with `:flows/restore-last-inputs!`)."}
   {:key         :flows/restore-last-inputs!
    :producer-ns 're-frame.flows
    :design-bead "rf2-4wqu6"
    :description "Restore a frame's dirty-check (`last-inputs`) rows to a previously-captured snapshot. Invoked by `commit-db-effect!` when post-commit validation rolls app-db back to its pre-handler value — without it the eagerly-advanced rows survive a rollback and the next clean drain skips the flow on `=`-equal inputs, never re-materialising the output. Frame-scoped (rf2-94ol5)."}
   {:key         :flows/snapshot-abandoned-paths
    :producer-ns 're-frame.flows
    :design-bead "rf2-z980k8"
    :description "Snapshot a frame's pending abandoned output paths (recorded by an in-drain same-frame `reg-flow` `:output-path` move). The router's flows-after-interceptor captures this BEFORE the flow transform drains/clears them so a post-commit schema/machine-data rollback can re-record them in lock-step with the discarded pending `:db` (paired with `:flows/restore-abandoned-paths!`). Frame-scoped (rf2-94ol5)."}
   {:key         :flows/restore-abandoned-paths!
    :producer-ns 're-frame.flows
    :design-bead "rf2-z980k8"
    :description "Re-record a frame's pending abandoned output paths from a previously-captured snapshot. Invoked by `commit-db-effect!` when post-commit validation rolls app-db back — the pending `:db` that carried the vacated state is discarded, so a drained-but-not-durably-vacated `:output-path` move must re-attempt next drain rather than be silently lost. Frame-scoped (rf2-94ol5)."}
   {:key         :flows/reset-flows!
    :producer-ns 're-frame.flows
    :description "Clear the per-frame flow registry (test isolation)."}
   {:key         :flows/teardown-on-frame-destroy!
    :producer-ns 're-frame.flows
    :design-bead "rf2-wbtjn"
    :description "Drop the destroyed frame's per-frame flow registry slot, its `last-inputs` rows, and any `:flow` registrar entries whose last owning frame was destroyed. Invoked by `frame/destroy-frame!` symmetric with the machines teardown hook (rf2-vsigt) — without this hook a long-running SSR JVM with per-request frame churn grows the flow registry unboundedly."}

   ;; ---- re-frame.schemas -----------------------------------------------------
   {:key         :schemas/validate-event!
    :producer-ns 're-frame.schemas
    :description "Validate an event vector against the registered event schema."}
   {:key         :schemas/validate-app-schema!
    :producer-ns 're-frame.schemas
    :description "Validate the app-db snapshot against the registered app-db schema."}
   {:key         :schemas/validate-fx!
    :producer-ns 're-frame.schemas
    :design-bead "rf2-xp2o3"
    :description "Validate an fx-handler's args against the registered fx schema (Spec 010 step 5)."}
   {:key         :schemas/validate-sub!
    :producer-ns 're-frame.schemas
    :description "Validate a subscription's return value against its schema."}
   {:key         :schemas/frame-schema-entries
    :producer-ns 're-frame.schemas
    :description "Return the per-frame schema-registration entries (for snapshotting)."}
   {:key         :schemas/reg-app-schema
    :producer-ns 're-frame.schemas
    :description "Register a path-scoped schema for app-db values."}
   {:key         :schemas/reg-app-schemas
    :producer-ns 're-frame.schemas
    :description "Bulk-register multiple path-scoped app-db schemas."}
   {:key         :schemas/app-schema-at
    :producer-ns 're-frame.schemas
    :description "Look up the schema registered at a path (introspection)."}
   {:key         :schemas/app-schema-meta-at
    :producer-ns 're-frame.schemas
    :design-bead "rf2-mg6ya"
    :description "Return the full registration-metadata map (source-coords + :path/:schema/:frame) for a path, or nil. The source-coord introspection surface pair-tools / 10x read; the lighter app-schema-at returns only the schema value."}
   {:key         :schemas/app-schemas
    :producer-ns 're-frame.schemas
    :description "Return all path → schema registrations (introspection)."}
   {:key         :schemas/app-schemas-digest
    :producer-ns 're-frame.schemas
    :description "Cheap digest of the registered-schema set (cache-key surface)."}
   {:key         :schemas/snapshot-by-frame
    :producer-ns 're-frame.schemas
    :description "Snapshot the per-frame schema registry for restore."}
   {:key         :schemas/restore-by-frame!
    :producer-ns 're-frame.schemas
    :description "Restore a previously-snapshotted per-frame schema registry."}
   {:key         :schemas/clear-by-frame!
    :producer-ns 're-frame.schemas
    :description "Clear the schema registry entries for a frame (test isolation)."}
   {:key         :schemas/on-frame-destroyed!
    :producer-ns 're-frame.schemas
    :design-bead "rf2-6m0se"
    :description "Drop the destroyed frame's app-db schema entries (consumed by frame/destroy-frame!)."}
   {:key         :schemas/set-schema-validator!
    :producer-ns 're-frame.schemas
    :design-bead "rf2-froe"
    :description "Install a pluggable schema-validator fn (overrides default)."}
   {:key         :schemas/set-schema-explainer!
    :producer-ns 're-frame.schemas
    :design-bead "rf2-froe"
    :description "Install a pluggable schema-explainer fn paired with the validator."}
   {:key         :schemas/set-schema-printer!
    :producer-ns 're-frame.schemas
    :design-bead "rf2-wla45"
    :description "Install a pluggable schema-print companion fn (Spec 010 §Schema digest line 491). The digest pipeline hashes this fn's UTF-8 output; non-Malli ports register their own serialiser so digests reflect the registered validator's serialisation contract."}
   {:key         :schemas/set-schema-fns!
    :producer-ns 're-frame.schemas
    :design-bead "rf2-13meg"
    :description "Install the validator/explainer/printer bundle atomically from a single map (the honest bundle setter — replaces the old set-schema-validator! map-arity). Returns the installed bundle map {:validate … :explain … :print …} (rf2-qdtcx2)."}
   {:key         :schemas/validate-with-registered-fn
    :producer-ns 're-frame.schemas
    :design-bead "rf2-r2uh"
    :description "Boundary seam: validate using the registered validator fn."}
   {:key         :schemas/explain-with-registered-fn
    :producer-ns 're-frame.schemas
    :design-bead "rf2-r2uh"
    :description "Boundary seam: explain using the registered explainer fn."}
   {:key         :schemas/redact-validation-tags
    :producer-ns 're-frame.schemas
    :design-bead "rf2-o69h5"
    :description "THE shared schema-aware redaction seam for every validation-failure trace emitted outside the schemas artefact (production boundary interceptor, machine :data validation, the :sub-override path, flow-output validation): scrubs the value-bearing slots (:value/:received/:explain/:explain-humanized/:rf.fx/args/:rf.sub/query-v) and stamps :sensitive? true when the schema declares any :sensitive? slot — the same redaction the dev-time validate-*! hot path applies. Falls through verbatim when the schemas artefact is absent (no schema to redact against). Generalised from rf2-a5kzs's event-only redact-event-tags."}
   {:key         :schemas/malli-validate
    :producer-ns 're-frame.schemas.malli
    :design-bead "rf2-t0hq"
    :description "Default-installed Malli validator (malli.core/validate)."}
   {:key         :schemas/malli-explain
    :producer-ns 're-frame.schemas.malli
    :design-bead "rf2-t0hq"
    :description "Default-installed Malli explainer (malli.core/explain)."}
   {:key         :schemas/humanize-explain!
    :producer-ns 're-frame.schemas.malli
    :design-bead "rf2-2ek7t"
    :description "Humanize the raw m/explain output into operator-readable shape. The only in-tree publisher is re-frame.schemas.malli, which installs (malli.error/humanize ...); per-validator ports install their own humanizer from their own ns. Absent the hook, the substrate ships only :explain (raw); Xray's violation block falls back to the raw map. Consulted by validate.cljc's emit-validation-failure! helper when building the :rf.error/schema-validation-failure trace payload — augments tags with :explain-humanized when present."}
   {:key         :schemas/extract-large-paths-from-schema
    :producer-ns 're-frame.schemas
    :design-bead "rf2-nwv63"
    :description "Walk a Malli EDN form at a base-path; return {path declaration} entries for :large? true slots. Consumed by re-frame.elision."}
   {:key         :schemas/extract-sensitive-paths-from-schema
    :producer-ns 're-frame.schemas
    :design-bead "rf2-kj51z"
    :description "Walk a Malli EDN form at a base-path; return paths whose props carry :sensitive? true. Consumed by re-frame.elision."}

   ;; ---- re-frame.machines ----------------------------------------------------
   {:key         :machines/reg-machine
    :producer-ns 're-frame.machines
    :design-bead "rf2-8bp3"
    :description "Register a state machine definition (plain-fn surface)."}
   {:key         :machines/make-machine-handler
    :producer-ns 're-frame.machines
    :description "Create the event-handler that drives a machine instance."}
   {:key         :machines/machine-transition
    :producer-ns 're-frame.machines
    :description "Apply a transition to a machine instance."}
   {:key         :machines/machines
    :producer-ns 're-frame.machines
    :description "Return all registered machine definitions (introspection)."}
   {:key         :machines/machine-meta
    :producer-ns 're-frame.machines
    :description "Return registration metadata for a named machine."}
   {:key         :machines/machine-by-system-id
    :producer-ns 're-frame.machines
    :description "Look up a live machine instance by its system id."}
   {:key         :machines/reset-timers!
    :producer-ns 're-frame.machines
    :description "Cancel in-flight `:after` wall-clock timers (test isolation)."}
   {:key         :machines/install-runtime!
    :producer-ns 're-frame.machines
    :description "EP-0026 §Framework Standard Registrations: re-register the machine runtime (`:rf.machine/*` fx + `:rf/machine*` subs) into BOTH the regular registrar AND the image standard registry. Consulted by `re-frame.test-support`'s reset fixture after a sibling ns's `registrar/clear-all!` / `image-assembly/clear-standards!` wipes them, so a Story variant frame's sealed generation (which carries only the standard union + its image selection, no registrar fallback) still resolves the framework machine runtime. The machine analogue of `re-frame.events/register-set-db-standard!` (the `:rf/set-db` re-seed). A no-op when machines is not loaded."}
   {:key         :machines/reset-spawn-order!
    :producer-ns 're-frame.machines
    :design-bead "rf2-vsigt"
    :description "Drop every recorded per-frame spawn-order vector (test isolation)."}
   {:key         :machines/on-frame-destroyed!
    :producer-ns 're-frame.machines
    :description "Per-frame `:after` timer-table cleanup hook called from frame/destroy-frame! (rf2-ysa94)."}
   {:key         :machines/on-frame-restored!
    :producer-ns 're-frame.machines
    :design-bead "rf2-u5kmf8"
    :description "Epoch-restore host-transient quiesce for machine `:after` timers. Consulted by `re-frame.epoch.tool-pair/perform-restore!` AFTER a successful install: releases the restored frame's in-flight `:after` host-clock handles (the orphaned async host work the unwound epochs spawned — NOT frame-state) so a leaked wall-clock timer never fires against the restored state. Emits one `:rf.machine.timer/cancelled :reason :on-restore` per entry. The restore counterpart of `:machines/on-frame-destroyed!` (Managed-Effects §SSR, preload, hydration, and restore: \"epoch restore MUST NOT revive host work\")."}
   {:key         :machines/teardown-on-frame-destroy!
    :producer-ns 're-frame.machines
    :design-bead "rf2-vsigt"
    :description "Frame-destroy machine-cascade orchestrator: walks active machines in reverse-creation order, runs each `:exit` cascade, applies the unified teardown projection (snapshot + system-id + spawn-slot prune), unregisters handlers, and emits `:rf.machine.lifecycle/destroyed` per actor with `:reason :parent-frame-destroyed`. Invoked by `frame/destroy-frame!` BEFORE sub-cache / adapter teardown per Spec 005 §Cross-Spec Interactions §1."}
   {:key         :machines/spawn-fx
    :producer-ns 're-frame.machines
    :description "Effect handler for :rf.machine/spawn."}
   {:key         :machines/destroy-machine-fx
    :producer-ns 're-frame.machines
    :description "Effect handler for :rf.machine/destroy."}
   {:key         :machines/resolve-actor-handler-meta
    :producer-ns 're-frame.machines
    :design-bead "rf2-a2sn1"
    :description "Lazy actor-handler resolver. Core's `re-frame.router.diagnostics/handle-no-handler!` consults this BEFORE surfacing `:rf.error/no-such-handler`: given an unresolved `[<actor-id> <event>]` dispatch, it materialises a spawned actor's handler-meta purely from its (revertible) app-db snapshot (`:rf/machine-type` → registered TYPE spec or inline `:definition`). Returns nil when no live snapshot exists, so core surfaces the genuine `:no-such-handler`. Eliminates the per-instance registrar registration so `restore-epoch!` (app-db-only) reverts actor liveness with zero registrar drift — closes the Goal-2 dynamic-actor revertibility leak."}
   {:key         :machines/actor-resolvable?
    :producer-ns 're-frame.machines
    :design-bead "rf2-a2sn1"
    :description "Epoch-restore precondition companion to :machines/resolve-actor-handler-meta. Returns true iff a recorded actor-id's snapshot in a candidate restore RUNTIME-DB partition (EP-0001 rf2-3aizt1 — snapshots are runtime-db state at [:rf.runtime/machines :snapshots <id>]) resolves to a live machine spec via its `:rf/machine-type` (registered TYPE or inline `:definition`). Lets `re-frame.epoch.tool-pair/missing-references` treat a spawned-actor snapshot as a VALID restore target — its per-instance handler is, by design, never registered — rather than a `:rf.epoch/restore-missing-handler`."}
   {:key         :machines/spec-from-snapshot
    :producer-ns 're-frame.machines
    :design-bead "rf2-rlt3sv"
    :description "Epoch-restore version-drift precondition companion to :machines/actor-resolvable?. Resolves a SPAWNED actor's CURRENT definition the same way dispatch does — from the snapshot's `:rf/machine-type` (a registered TYPE keyword resolved through the registrar, or an inline `:definition` spec map carried verbatim). `re-frame.epoch.tool-pair/machine-version-mismatch` reads the resolved spec's `[:meta :rf/snapshot-version]` so a spawned actor whose TYPE was hot-reloaded forward surfaces `:rf.epoch/restore-version-mismatch` instead of silently accepting an incompatible older snapshot (the snapshot key is an instance id, not a registered handler, so the singleton registrar probe never matched it)."}
   {:key         :machines/spawn-all-init-fx
    :producer-ns 're-frame.machines
    :description "Effect handler seeding the :spawn-all join state during spawn."}
   {:key         :machines/after-schedule-fx
    :producer-ns 're-frame.machines
    :description "Effect handler scheduling a delayed machine transition."}
   {:key         :machines/after-cancel-fx
    :producer-ns 're-frame.machines
    :description "Effect handler cancelling a previously-scheduled transition."}
   {:key         :machines/update-snapshot-fx
    :producer-ns 're-frame.machines
    :description "Effect handler for the :rf.machine/update-snapshot snapshot-level escape hatch."}
   {:key         :machines/validate-machine-data!
    :producer-ns 're-frame.machines
    :design-bead "rf2-jbbp7"
    :description "Post-commit walker for the `:where :machine-data` boundary (Spec 005 §Schema validation, Spec 010 §Per-step recovery row 7). Iterates `[:rf.runtime/machines :snapshots]` in the runtime-db partition, validates each snapshot's `:data` against the registered machine's `:data-schema`. Router AND-conjoins with `:schemas/validate-app-schema!` to gate the `:rf.db/runtime` commit; a failure rolls the cascade back exactly like a `:where :app-db` violation."}
   {:key         :machines/owning-actor-id
    :producer-ns 're-frame.machines
    :design-bead "rf2-ma0wvq"
    :description "Spawned-actor ownership resolver `(fn [frame-id event-id]) -> actor-id|nil` (Spec 014 §Abort on actor destroy). Returns the spawned actor's address that owns `event-id` — i.e. `event-id` itself when it is registered in the frame's runtime-db spawn registry (`re-frame.machines.paths/spawned-path`) — else nil. The INVERSION of the old http→machines coupling (rf2-ma0wvq): the machines artefact OWNS the `:spawned` registry shape, so the structural membership walk lives next to it; re-frame.http.registry consults this hook (instead of re-stating the path + walking it itself) to decide whether a managed request belongs to a spawned actor, and falls back to nil when the machines artefact is absent. Step-1 set semantics = registry membership (declarative `:spawn` / `:spawn-all` only), set-identical to the pre-inversion walk; a separate step-2 bead widens it to imperative spawns under its own destroy-cancellation test."}
   {:key         :machines/project-ssr-runtime-db
    :producer-ns 're-frame.machines
    :design-bead "rf2-jm2u63"
    :description "SSR hydration projector for the durable `:rf.runtime/machines` slice `(fn [runtime-db frame-id]) -> machines-slice`. `re-frame.ssr.payload-policy/project-runtime-db` consults it so each machine snapshot's `:data` is redacted/elided per the owning machine's projection-relative `:sensitive` / `:large` declaration (lowered per actor into the per-frame elision registry, `:source :machine` — EP-0025 reversed the EP-0005 `:data-schema`→marks bridge; schema validates, it does not classify durable `:data`) under `:rf.egress/ssr-hydration` (the same frame-owned classification the trace-egress chokepoint uses, via `re-frame.classification/frame-snapshot-classification` re-rooting then `re-frame.classification/redact-with-paths`) BEFORE it rides the hydration wire — closing the raw-classified-machine-data hydration leak (EP-0015 §6/§8). Mirrors the resources `:ssr/extend-runtime-db-projection` model; absent the machines artefact the slice rides unchanged."}

   ;; ---- re-frame.routing -----------------------------------------------------
   {:key         :routing/reg-route
    :producer-ns 're-frame.routing
    :description "Register a route pattern and handler."}
   {:key         :routing/clear-route
    :producer-ns 're-frame.routing
    :description "Remove a registered route; emits :rf.route/cleared."}
   {:key         :routing/match-url
    :producer-ns 're-frame.routing
    :description "Match a URL against the registered routes."}
   {:key         :routing/route-url
    :producer-ns 're-frame.routing
    :description "Build a URL for a route by id + params."}
   {:key         :routing/reset-counters!
    :producer-ns 're-frame.routing
    :description "Reset the route-registration counter (test isolation)."}
   {:key         :routing/reset-nav-counters!
    :producer-ns 're-frame.routing
    :design-bead "rf2-oosjmh"
    :description "Reset the host-side nav-token / pending-nav counter high-water marks (re-frame.routing.nav-counters/nav-counters-cache). They are host-side transient state (not runtime-db), so a runtime/frames reset does not clear them; the shared CLJS make-reset-runtime-fixture reset-hooks table fires this per test so \"nav-1\" / \"pn-1\" assertions stay deterministic (test isolation)."}
   {:key         :routing/reset-url-claims!
    :producer-ns 're-frame.routing
    :design-bead "rf2-3l7xxz"
    :description "Reset the process-global URL-ownership claim-order vector (re-frame.routing.nav-fx/url-claim-order) that records, in claim order, which frames carry :url-bound? true. url-owner-frame-id resolves the FIRST-CLAIMED still-live binding (the incumbent) so a later duplicate cannot steal the browser URL. Like the nav-counters it is process-global state a runtime/frames reset does not clear; the shared CLJS make-reset-runtime-fixture reset-hooks table fires this per test so a prior test's claim cannot leak (test isolation)."}
   {:key         :routing/route-sub-fn
    :producer-ns 're-frame.routing
    :description "Subscription fn returning the currently-matched route."}
   {:key         :routing/route-sub-egress-path
    :producer-ns 're-frame.routing
    :design-bead "rf2-mtzv5m"
    :description "Resolve a framework route read sub-id (`:rf/route` / `:rf.route/query` / `:rf.route/params`) to the runtime-db storage position its sub value projects onto (`[:rf.runtime/routing :current …]`), or nil for a non-route sub. Consumed by `re-frame.elision/elide-wire-value`'s `:query-v` opt so the direct-read off-box egress surfaces (Pair MCP read-sub / list-subscriptions :include-values / snapshot :sub-cache / Xray) re-seed the walk at the slice's storage position — letting the per-frame elision registry's re-rooted absolute route decls (`:source :route`, lowered at activation) match the bare slice the sub returns. Core stays decoupled from routing (rf2-k682); absent the artefact the hook is unbound and a route sub value walks at the whole-value root (no route slice to leak)."}
   {:key         :routing/project-route-sub-egress
    :producer-ns 're-frame.routing
    :design-bead "rf2-mtzv5m"
    :description "Project a route read sub's value for egress — `(fn [sub-id value opts]) -> projected-value` — applying the route's projection-relative `:sensitive` / `:large` classification (re-rooted under `[:rf.runtime/routing :current …]` into the per-frame elision registry at activation) by re-seeding `elide-wire-value` at the slice's runtime-db storage position. A no-op pass-through for a non-route sub (NARROW: no generic sub-output propagation). Consumed by the trace chokepoint `re-frame.classification/project-sub-tags` for the `:rf.sub/run` `:rf.sub/value` / `:rf.sub/prev-value` slots; the off-box direct-read surfaces reach the same re-seeding via the `:routing/route-sub-egress-path` hook through `elide-wire-value`'s `:query-v` opt. The direct-read sibling of the SSR `re-frame.ssr.payload-policy/project-routing-egress` fix (rf2-4xut98). Absent the routing artefact the hook is unbound."}
   {:key         :routing/route-link
    :producer-ns 're-frame.routing
    :description "Reagent / SSR `[rf/route-link ...]` view component renderer."}
   {:key         :routing/current-url
    :producer-ns 're-frame.routing
    :description "Read the current browser URL as pathname+search+hash (CLJS) / \"/\" (JVM)."}
   {:key         :routing/install-url-listener!
    :producer-ns 're-frame.routing
    :design-bead "rf2-aerrz5"
    :description "Wire the URL-owner frame's browser URL-change listener (popstate or hashchange per its :url-strategy). CLJS-only."}
   {:key         :routing/remove-url-listener!
    :producer-ns 're-frame.routing
    :design-bead "rf2-aerrz5"
    :description "Tear down the URL-change listener installed by install-url-listener!. CLJS-only."}
   {:key         :routing/install-history-listener!
    :producer-ns 're-frame.routing
    :description "Alias for install-url-listener! (rf2-aerrz5). Wire a listener that drives the URL-owner frame on Back/Forward (rf2-6qgbs.4). CLJS-only."}
   {:key         :routing/remove-history-listener!
    :producer-ns 're-frame.routing
    :description "Alias for remove-url-listener! (rf2-aerrz5). Tear down the listener installed by install-history-listener!. CLJS-only."}
   {:key         :routing/on-frame-destroyed!
    :producer-ns 're-frame.routing
    :design-bead "rf2-1hncp2"
    :description "Release the destroyed frame's host-side transient routing caches — the scroll-position cache (re-frame.routing.scroll/scroll-positions-cache, rf2-1hncp2) AND the nav-token / pending-nav counter high-water marks (re-frame.routing.nav-counters/nav-counters-cache, rf2-oosjmh). Neither is runtime-db state — they live in module-level atoms (host-derived, ephemeral, off the epoch/SSR egress wire; the counters host-side specifically so an epoch restore cannot rewind + recycle a token), so they need explicit per-frame teardown like the other transient caches. Invoked by frame/destroy-frame! symmetric with the ssr / machines / flows / schemas teardown hooks; no-op when re-frame.routing is absent (the artefact is optional)."}

   ;; ---- re-frame.resources (EP-0003 slice 2) -------------------------------
   ;; The optional Resources artefact (Spec 016) publishes its public-API
   ;; surface here so re-frame.core reaches it without a static :require.
   ;; The routing / SSR integrations are published as cross-feature
   ;; extension hooks the host artefacts (routing / ssr) CONSULT —
   ;; late-bound both ways so neither side carries the other.
   {:key         :resources/reg-resource
    :producer-ns 're-frame.resources
    :design-bead "rf2-p10npe"
    :description "Register a resource — a named, cached read of remote/external state (Spec 016 §Registration). Doubles as the feature-inspection PROBE key for the :resources feature (re-frame.features)."}
   {:key         :resources/clear-resource
    :producer-ns 're-frame.resources
    :design-bead "rf2-p10npe"
    :description "Remove a registered resource (registration-lifecycle, NOT data invalidation). Per Spec 016 §Registration."}
   {:key         :resources/resource-meta
    :producer-ns 're-frame.resources
    :design-bead "rf2-p10npe"
    :description "Return the registered resource's spec map for a resource id, or nil. Per Spec 016 §Introspection."}
   {:key         :resources/resource-state
    :producer-ns 're-frame.resources
    :design-bead "rf2-p10npe"
    :description "Return a resource instance's runtime state for an explicit-frame target {:resource :scope :params :frame}. Per Spec 016 §Introspection."}
   {:key         :resources/resources
    :producer-ns 're-frame.resources
    :design-bead "rf2-p10npe"
    :description "Return resource introspection for a frame target — registered resources + the live per-frame resource-instance table. Per Spec 016 §Introspection."}
   ;; ---- mutations (EP-0003 §Mutations, first public-beta gate) -------------
   ;; The causal-write counterpart of the resource registration surface.
   {:key         :resources/reg-mutation
    :producer-ns 're-frame.resources
    :design-bead "rf2-dwme29"
    :description "Register a mutation — a named, causal WRITE to remote state that, on success, invalidates / patches / populates cached resource reads (Spec 016 §Deferred slices / EP-0003 §Mutations). Run with [:rf.mutation/execute …]; observe via the passive [:rf.mutation/*] subs keyed by instance id."}
   {:key         :resources/clear-mutation
    :producer-ns 're-frame.resources
    :design-bead "rf2-dwme29"
    :description "Remove a registered mutation (registration-lifecycle, NOT a form-error reset; the causal runtime-instance reset is the [:rf.mutation/clear …] event). Per EP-0003 §Mutations."}
   {:key         :resources/mutation-meta
    :producer-ns 're-frame.resources
    :design-bead "rf2-dwme29"
    :description "Return the registered mutation's spec map for a mutation id, or nil. Per EP-0003 §Mutations."}
   {:key         :resources/mutation-state
    :producer-ns 're-frame.resources
    :design-bead "rf2-dwme29"
    :description "Return a mutation INSTANCE's durable runtime row for an explicit-frame target {:instance :frame}, or nil. Per EP-0003 §Mutations."}
   {:key         :resources/mutations
    :producer-ns 're-frame.resources
    :design-bead "rf2-dwme29"
    :description "Return mutation introspection for a frame target — registered mutation ids + the live per-frame mutation-instance table (keyed by instance id). Per EP-0003 §Mutations."}
   ;; ---- named resource-scope resolvers (EP-0016 D3 slice 2) ----------------
   ;; The third resources kind: pure named db-derived scope resolvers.
   {:key         :resources/reg-resource-scope
    :producer-ns 're-frame.resources
    :design-bead "rf2-hls77w"
    :description "Register a PURE named scope resolver under a scope-id (Spec 016 §Named resource-scope resolvers / EP-0016 D3) — the one scope-resolution currency reused by resource registration, route resources, ensure / subscriptions, invalidation descriptors, and clear-scope. Per rf2-bqstzr the 3-slot grammar is (reg-resource-scope scope-id metadata resolve-fn): the :resolve fn is the value slot, metadata carries the declared :inputs {name [:db <rf-path>]}; omit :inputs for the whole-db fn sugar. The shipped input source is [:db <rf-path>]; [:runtime …] is reserved. A nil resolve result is FAIL-CLOSED. Referenced via {:from-db <scope-id>}."}
   {:key         :resources/clear-resource-scope
    :producer-ns 're-frame.resources
    :design-bead "rf2-hls77w"
    :description "Remove a registered resource-scope resolver (registration-lifecycle, the clear- decrement counterpart of reg-resource-scope). A pure resolver holds no per-frame runtime state. Per Spec 016 §Named resource-scope resolvers."}
   {:key         :resources/resolve-resource-scope
    :producer-ns 're-frame.resources
    :design-bead "rf2-hls77w"
    :description "Resolver helper: resolve a named scope resolver against a SUPPLIED db value, returning the canonical scope or nil — a plain function over the resolver registry, NOT an effect (no app-state / dispatch side effects). Not a pure data helper, though: like every resolution site it emits :rf.resource/scope-resolved dev-time trace evidence. Canonical use is the logout/account-switch idiom (resolve the concrete old scope from the handler's coeffect db, then pass it to :rf.resource/clear-scope concretely). Per Spec 016 §clear-scope resolves the concrete scope from the coeffect db (EP-0016 issue 7)."}
   {:key         :resources/project-scope-resolved-egress
    :producer-ns 're-frame.resources
    :design-bead "rf2-84l82t"
    :description "OFF-BOX trace egress projector for a :rf.resource/scope-resolved row's resolver-owned values (EP-0015). The row carries the resolver's resolved :input-values (raw app-db reads) + the derived :scope (the identity tuple embedding them) — owner-local values the generic value-path trace egress walk cannot classify once copied into trace tags. Given the row's :tags, UNCONDITIONALLY FAILS CLOSED: redacts :input-values + :scope to :rf/redacted + stamps :sensitive? for every db-reading resolver (no declassify hatch — EP-0025 retired the :rf.egress/output-sensitivity propagation model, so a resolver cannot declassify its derived scope), preserving the structural :resource-id / declared :inputs names / :kind / :resolved-nil?. Consulted by the epoch tool-pair's off-box :trace-events projection (omit-off-box-resource-scope-values, gated on the off-box :include-sensitive? default; the trusted-local opt-in lifts it). No-op / nil when no resources artefact is loaded (an app with no resources emits no scope-resolved rows). The on-box listener keeps the raw dev evidence; the leak is at off-box / epoch / MCP egress. Per Spec 015 §10 / Derivations §Tool redaction."}
   {:key         :resources/project-resource-trace-egress
    :producer-ns 're-frame.resources
    :design-bead "rf2-8x0gfa"
    :description "OFF-BOX trace egress projector for the BROADER resource/mutation trace family's scoped-key slots (EP-0015; the family-level companion to :resources/project-scope-resolved-egress). The :rf.resource/* + :rf.mutation/* rows emitted by re-frame.resources.events / …timers / …mutation-events copy owner-local SCOPED KEYS into trace tags — :resource/key (a single [scope resource-id params] vector), :resource/keys / :matched / :removed / :keys / :exempt / :committed / :restored / :conflicted / :refetched (vectors of scoped keys), and the optimistic-rollback :dispositions (per-key maps each embedding :resource/key). A generic value-path trace egress walk is structurally blind to these once copied into tags. Given a row's :tags + the frame-id, projects each scoped key through the resource OWNER's whole-entry disposition (no derived-sensitive arm — EP-0025 retired the resolver-input sensitivity propagation): a :sensitive?/:large? owner tokenizes scope+params to opaque content-addressed {:rf/redacted <digest>} (distinct values stay distinct so per-key joins survive), a plain owner rides verbatim, and an UNREGISTERED owner FAILS CLOSED (redacted). The resource-id (position 1 of the projected key) + every non-key tag ride verbatim; the row is stamped :sensitive? when any slot redacted. Consulted by the epoch tool-pair's off-box :trace-events projection (omit-off-box-resource-trace-keys), gated on the off-box :include-sensitive? default (the trusted-local opt-in lifts it). No-op / nil when no resources artefact is loaded. The on-box listener keeps the raw dev evidence; the leak is at off-box / epoch / MCP egress. Per Spec 015 §10 / Derivations §Tool redaction."}
   {:key         :resources/reset-resources!
    :producer-ns 're-frame.resources.test-support
    :design-bead "rf2-p10npe"
    :description "Test-isolation reset: clear the :resource-kind + :mutation-kind + :resource-scope-kind registrar entries + the host-side generation high-water marks + the host-side work-ledger / timer / revalidation handles. Published from re-frame.resources.test-support (kept behind an explicit test-support require, rf2-dbiv8 posture); fired by the shared CLJS make-reset-runtime-fixture reset-hooks table, no-op when test-support is absent."}
   {:key         :resources/on-frame-destroyed!
    :producer-ns 're-frame.resources
    :design-bead "rf2-afpdkn"
    :description "Release the destroyed frame's host-side TRANSIENT resource caches — the work-ledger host handles (AbortControllers / timer handles keyed by [frame-id work-id], re-frame.resources.work-ledger/handle-table), the stale/GC timer handles (re-frame.resources.timers/timer-table, rf2-nbjewi), the focus/reconnect revalidation window listeners (re-frame.resources.revalidate-listeners/listener-table, rf2-vtblcq), AND the resource generation high-water mark (re-frame.resources.state/generation-cache). None is runtime-db state; all are module-level atoms off the epoch/SSR egress wire (the generation host-side so an epoch restore cannot rewind+recycle it). Invoked by frame/destroy-frame! by key; no-op when re-frame.resources is absent (the artefact is optional, post-v1). Per Spec 016 [Runtime-Subsystems] clause 5."}
   {:key         :resources/install-revalidation-listeners!
    :producer-ns 're-frame.resources
    :design-bead "rf2-vtblcq"
    :description "Install host window focus / network-reconnect listeners that drive active-stale revalidation for a frame (Spec 016 §Deferred slices). The listeners dispatch [:rf.resource/window-focused] / [:rf.resource/network-reconnected] at the named frame; the event handlers scan the frame's active-owner STALE entries and refetch them in the background with cause :focus / :reconnect (a cause, never an owner — generation + stale-suppression protect late replies). CLJS-only host listeners (window focus/visibilitychange + online); the JVM arm is a no-op (no DOM under SSR/JVM). Listeners are cancelled on frame destroy via the single :resources/on-frame-destroyed! hook. Published so re-frame.core can reach it without a static :require, mirroring routing's :routing/install-history-listener!."}
   {:key         :resources/remove-revalidation-listeners!
    :producer-ns 're-frame.resources
    :design-bead "rf2-vtblcq"
    :description "Tear down the window focus / online revalidation listeners installed by :resources/install-revalidation-listeners! for a frame. No-op when none is installed (and on the JVM). For test isolation and single-page hosts that rotate which frame owns revalidation. CLJS-only host detach; both arms drop the side-table slot."}
   {:key         :routing/extra-route-keys
    :producer-ns 're-frame.resources.route
    :design-bead "rf2-p10npe"
    :description "Cross-feature LATE-BOUND route-metadata accepted-key extension (Spec 016 §Route integration). Returns a SET of extra bare route-metadata keys routing unions into its accepted set; the Resources artefact publishes #{:resources} so routing accepts the :resources route key (mirrors how :head is a cross-feature key owned by SSR). Resources is the first publisher; consumed by re-frame.routing.registry/accepted-route-keys."}
   {:key         :routing/on-route-entry
    :producer-ns 're-frame.resources.route
    :design-bead "rf2-vdyrls"
    :description "Cross-feature LATE-BOUND route-entry resource plan (Spec 016 §Route integration). Routing's commit-navigation (the shared successful-commit assembler for both the programmatic + URL-driven nav paths) consults it by key with {:route-meta :route-id :params :query :fragment :nav-token :prev-id :prev-nav-token :ctx}; the Resources artefact returns {:fx [...] :blocking #{<scoped-key> …} :plan-error err?} — the :rf.resource/ensure dispatches (owner [:route route-id nav-token], cause [:route-entry route-id nav-token]) + the prior route's :rf.resource/release-owner are spliced into the commit fx; the blocking set is written into [:rf.runtime/routing :resource-blocking nav-token] atomically with the commit; a params/scope PLANNING failure (:plan-error) is recorded on the route slice's :error. No-op (nil) when no Resources artefact / no :resources route metadata + no prior owner. Consumed by re-frame.routing.events/commit-navigation."}
   {:key         :routing/route-blocking?
    :producer-ns 're-frame.resources.route
    :design-bead "rf2-vdyrls"
    :description "Cross-feature LATE-BOUND blocking-transition predicate (Spec 016 §Route integration). Routing's :rf.route.internal/settle-transition consults it by key with the runtime-db value; the Resources artefact returns true while any BLOCKING route resource for the route slice's current nav-token is still unsettled (the route's SSR wait point), so the settle keeps the transition :loading past the :on-match drain. The resource reply handlers drain the blocking slot + land :idle themselves on the last blocking settle. False (no-op consult) when no Resources artefact is loaded. Consumed by re-frame.routing.events/settle-transition-handler."}
   {:key         :ssr/extend-runtime-db-projection
    :producer-ns 're-frame.resources.ssr
    :design-bead "rf2-p10npe"
    :description "Cross-feature LATE-BOUND SSR hydration-payload runtime-db projection extension (Spec 016 §SSR and hydration). Takes the full runtime-db value, returns a {subsystem-key durable-projection} map SSR's project-runtime-db merges into its allowlist-shaped slice; the Resources artefact projects ONLY the durable :entries of :rf.runtime/resources (per-entry redacted/omitted by the resource's :sensitive?/:large? classification; the reverse indexes are recomputable-from-entries). Resources is the first publisher; consumed by re-frame.ssr.payload-policy/project-runtime-db."}
   {:key         :resources/drain-blocking-ssr!
    :producer-ns 're-frame.resources.ssr
    :design-bead "rf2-er7qx2"
    :description "Cross-feature LATE-BOUND SSR blocking-resource DRAIN hook (Spec 016 §SSR and hydration steps 3-4). The SSR render path (re-frame.ssr/drain-blocking-resources!, called by the Ring / streaming host adapters AFTER frame setup + route resolution and BEFORE the render walk) consults it by key with the carried frame-id + {:deadline-ms :pump! :tick-ms}; the Resources artefact runs the drain LOOP — reads the live nav-token blocking set, pumps the event loop via the :pump! thunk so an in-flight reply lands, and on the wall-clock deadline settles every still-unsettled blocking entry to a first-load failure in the frame's runtime-db so the render sees a structured :error rather than a hung :loading skeleton — returning {:settled? :timed-out :route-blocking-failure}. No-op {:settled? true} when no Resources artefact is loaded (an SSR app without resources never blocks on them). Consumed by re-frame.ssr/drain-blocking-resources!."}
   {:key         :resources/hydrate-runtime-db
    :producer-ns 're-frame.resources.ssr
    :design-bead "rf2-ctk2av"
    :description "Cross-feature LATE-BOUND SSR hydration RECONCILE hook (Spec 016 §SSR and hydration / §Restore and replay) — the client-side counterpart of :ssr/extend-runtime-db-projection. Takes the runtime-db the :rf/hydrate handler is about to install (+ the carried frame id) and returns it with the :rf.runtime/resources subtree reconciled: reverse indexes recomputed from entries (never trusted from the wire), SSR owners orphaned, transient :current-work cleared, server clock skew surfaced. Resources is the first consumer; consulted by re-frame.ssr.hydrate/hydrate-event-handler*. Absent hook (no resources artefact) leaves the runtime-db unchanged."}
   {:key         :resources/reconcile-on-restore
    :producer-ns 're-frame.resources.ssr
    :design-bead "rf2-7r5mc2"
    :description "Cross-feature LATE-BOUND epoch-restore RECONCILE hook (Spec 016 §Restore and replay parts 2/4/5) — the time-travel counterpart of :resources/hydrate-runtime-db. Epoch restore installs the UNPROJECTED captured snapshot (still carrying :current-work + non-terminal work-ledger rows), so this does everything the hydration reconcile does (recompute reverse indexes from entries, orphan SSR / stale-nav owners, clear transient :current-work) PLUS two restore-specific settles the SSR wire projection had already applied: it settles every mid-flight :loading/:fetching entry to its last STABLE status (:loaded if data, :error if a failed first load, :idle if never loaded) and records every restored NON-terminal work-ledger row as DANGLING (terminal :suppressed / :dangling) so a pre-restore in-flight reply is suppressed by the work-id + generation check. Resources is the first consumer; consulted by re-frame.epoch.tool-pair/reconcile-runtime-db-on-restore inside perform-restore!. Called with {:defer-traces? true} (rf2-obi8rr) so its :rf.resource/restored / :rf.resource/owner-released success rows ride back as metadata instead of firing inline (the reconcile runs BEFORE the atomic install, which can still fail). Absent hook (no resources artefact) installs the runtime-db verbatim (the pre-rf2-7r5mc2 behaviour)."}
   {:key         :resources/commit-restore-reconcile!
    :producer-ns 're-frame.resources.ssr
    :design-bead "rf2-obi8rr"
    :description "Cross-feature LATE-BOUND epoch-restore trace COMMIT hook (Spec 016 §Restore and replay / §Xray and AI tooling) — the post-install half of :resources/reconcile-on-restore. The reconcile runs BEFORE the atomic replace-frame-state! install and defers its success rows (:rf.resource/restored + :rf.resource/owner-released), riding the trace intents back as metadata; epoch perform-restore! consults this hook with the reconciled runtime-db ONLY on the install-success branch so those success rows fire exactly once the restore truly installed — never for a destroyed-frame install (the rf2-s93722 post-liveness teardown race) that returns nil and writes nothing. Resources is the first consumer; consulted by re-frame.epoch.tool-pair/commit-resources-restore-traces! inside perform-restore!. No-op when no resources artefact is loaded, when the frame-state carries no runtime-db partition, or when the runtime-db carries no deferred intents (a resource-free restore)."}

   ;; ---- re-frame.http.managed -----------------------------------------------
   ;; The stub-family hook publishes from `re-frame.http.test-support` — the
   ;; single discoverable home for HTTP test surfaces. The raw install/uninstall
   ;; pair has no façade wrapper, so it carries no late-bind hook — tests call
   ;; it directly via the home namespace.
   {:key         :http/with-managed-request-stubs*
    :producer-ns 're-frame.http.test-support
    :description "Function form of the with-managed-request-stubs macro."}
   {:key         :http/clear-all-in-flight!
    :producer-ns 're-frame.http.managed
    :description "Abort every in-flight managed request (test isolation)."}
   {:key         :http/abort-in-flight!
    :producer-ns 're-frame.http.managed
    :design-bead "rf2-rak684"
    :description "Best-effort abort the in-flight managed request registered under a frame-qualified request-id (fires its :abort-fn with a reason, default :user; no-op when nothing is registered). The shared abort-by-request-id seam the Resources out-of-cascade teardown paths (clear-resource / frame destroy) reach through so they can abort a managed request without the resources artefact statically :require-ing the http transport."}
   {:key         :http/reg-http-interceptor
    :producer-ns 're-frame.http.managed
    :design-bead "rf2-6y3q"
    :description "Register a per-frame request-side HTTP interceptor."}
   {:key         :http/clear-http-interceptor
    :producer-ns 're-frame.http.managed
    :design-bead "rf2-6y3q"
    :description "Clear a single registered HTTP interceptor."}
   {:key         :http/clear-all-http-interceptors!
    :producer-ns 're-frame.http.managed
    :design-bead "rf2-6y3q"
    :description "Clear every registered HTTP interceptor (test isolation)."}
   {:key         :http/abort-on-actor-destroy
    :producer-ns 're-frame.http.managed
    :design-bead "rf2-wvkn"
    :description ":spawn cancellation cascade tied to actor destruction."}
   {:key         :http/abort-in-flight-for-frame!
    :producer-ns 're-frame.http.managed
    :design-bead "rf2-u5kmf8"
    :description "Epoch-restore host-transient quiesce for NON-resource managed HTTP. Consulted by `re-frame.epoch.tool-pair/perform-restore!` AFTER a successful install: aborts every in-flight `:rf.http/managed` request the restored frame issued with the reply-suppressing `:reason :epoch-restored` (no delivery to the original `:rf/reply-to`) and emits the EP-0011 `:status :stale` / `:work/status :suppressed` envelope facts. The non-ledger-backed counterpart of the resources work-id dangling, so a pre-restore in-flight reply cannot mutate the restored state (Managed-Effects §SSR, preload, hydration, and restore: \"epoch restore MUST NOT revive host work\")."}
   {:key         :http/register-managed-machine!
    :producer-ns 're-frame.http.managed
    :design-bead "rf2-ijm7"
    :description "Register the machine-shape wrapper for managed HTTP requests."}

   ;; ---- re-frame.ssr ---------------------------------------------------------
   {:key         :ssr/render-tree-hash
    :producer-ns 're-frame.ssr
    :description "Compute the stable hash of a rendered tree (SSR cache key)."}
   {:key         :ssr/render-to-string
    :producer-ns 're-frame.ssr
    :description "Render a view tree to an HTML string for SSR."}
   {:key         :ssr/reg-error-projector
    :producer-ns 're-frame.ssr
    :description "Register a fn projecting SSR render errors to user-facing markup."}
   {:key         :ssr/project-error
    :producer-ns 're-frame.ssr
    :description "Apply the registered error-projector to an SSR render error."}
   {:key         :ssr/on-frame-destroyed
    :producer-ns 're-frame.ssr
    :design-bead "rf2-fcj33"
    :description "Clear the SSR side-channel atoms (pending-error-traces, request-slots, response-slots) for a destroyed frame, per Spec 011 §Per-request frame teardown contract. The response-slots entry joined under rf2-jbcmt when the `:rf/response` accumulator moved off `app-db` to plug a hydration-payload leak + per-fx full-app-db swap. Also invokes `:ssr/head-on-frame-destroyed` (if registered) so the head ns can release per-frame snapshot bookkeeping (rf2-4dra9)."}

   ;; ---- re-frame.ssr.head (head/meta contract) -----------------------------
   {:key         :ssr/reg-head
    :producer-ns 're-frame.ssr.head
    :design-bead "rf2-4dra9"
    :description "Register a head-fragment producer fn `(fn [db route] head-model)` under id, per Spec 011 §Head/meta contract."}
   {:key         :ssr/render-head
    :producer-ns 're-frame.ssr.head
    :design-bead "rf2-4dra9"
    :description "Apply the head fn registered under `head-id` against a frame's app-db and active route, returning the produced `:rf/head-model`."}
   {:key         :ssr/active-head
    :producer-ns 're-frame.ssr.head
    :design-bead "rf2-4dra9"
    :description "Look up the active route's `:head` metadata; if set, call `render-head` and return the model. Otherwise return the default head per Spec 011 §Default head."}
   {:key         :ssr/head-snapshot
    :producer-ns 're-frame.ssr.head
    :design-bead "rf2-4dra9"
    :description "Read the per-frame `{head-id → last-produced head-model}` snapshot. Cleared on frame destroy via the `:ssr.head/on-frame-destroyed` hook chained from re-frame.ssr's teardown."}
   {:key         :ssr/head-model-html
    :producer-ns 're-frame.ssr.head
    :design-bead "rf2-4dra9"
    :description "Render a `:rf/head-model` map to its inner-head HTML fragment in canonical order: title → meta → link → script → JSON-LD."}
   {:key         :ssr/head-on-frame-destroyed
    :producer-ns 're-frame.ssr.head
    :design-bead "rf2-4dra9"
    :description "Clear the per-frame head-snapshot entry on destroy. `re-frame.ssr/on-frame-destroyed!` invokes this hook by key after clearing its own side-channel atoms."}

   ;; ---- re-frame.ssr.streaming (chunked SSR) -------------------------------
   ;; Three keys host adapters (e.g. ssr-ring/streaming) call to drive
   ;; the chunked-rendering pipeline.
   {:key         :ssr.streaming/render-shell!
    :producer-ns 're-frame.ssr.streaming
    :design-bead "rf2-ojakd"
    :description "Render the initial SSR shell HTML chunk for a frame and stash the in-flight render context."}
   {:key         :ssr.streaming/render-continuation!
    :producer-ns 're-frame.ssr.streaming
    :design-bead "rf2-ojakd"
    :description "Render a continuation chunk (resolved-suspense fragment) for an in-flight streaming render. Returns {:id :html :delta :failed? :continuations} — :continuations are nested boundaries discovered during this render that the host appends at the tail of its FIFO drain queue (rf2-sgvn6)."}
   {:key         :ssr.streaming/build-final-payload
    :producer-ns 're-frame.ssr.streaming
    :design-bead "rf2-ojakd"
    :description "Build the final hydration payload after every streaming chunk has been emitted."}

   ;; ---- re-frame.epoch (Tool-Pair surface) ---------------------------------
   {:key         :epoch/settle!
    :producer-ns 're-frame.epoch
    :design-bead "rf2-nj6p7"
    :description "Settle one DEQUEUED EVENT's epoch (commit to history). Per Spec 002 §Drain versus event the epoch boundary is the dequeued event, not the drain — the router calls this once per process-event! (incl. each :fx-dispatched child), harvesting that one event's cascade buffer. Skips an empty buffer (rejected/aborted dispatch)."}
   {:key         :epoch/commit-halt-record!
    :producer-ns 're-frame.epoch
    :design-bead "rf2-nj6p7"
    :description "Commit a :halted-* epoch record for a drain halt whose halting event never ran (the per-event depth-exceed boundary). Unlike :epoch/settle! it does NOT skip an empty buffer — under per-event epochs the already-settled events each harvested their own buffer, so the buffer is empty at halt; this synthesises the halting event's :halted-depth record from an explicit trigger. Already-settled siblings are durable (no whole-drain rollback)."}
   {:key         :epoch/capture-event
    :producer-ns 're-frame.epoch
    :description "Capture an event into the in-flight epoch buffer."}
   {:key         :epoch/run-cause
    :producer-ns 're-frame.epoch
    :design-bead "rf2-25zo2"
    :description "Walk a frame's in-flight event-run buffer and return {:cause-event-id :cause-subs :rendered-so-far} for :rf.view/rendered attribution — the event-pipeline-run (one traversal), keyed off the buffer's :rf.event/run-start marker (rf2-p4cd9c: run sense, not the reactive graph). Consumed by re-frame.views at view-render emit time so the Xray Reactive panel can graph cause→effect for re-renders. Returns nil when the epoch artefact is absent."}
   {:key         :epoch/record-render!
    :producer-ns 're-frame.epoch
    :design-bead "rf2-qs6dl"
    :description "Attribute a post-settle render emit (a :view/render / :rf.view/rendered op firing at React commit time, after the causing cascade settled) back to the cascade that caused it — the frame's most-recently-settled epoch. Called by re-frame.epoch.capture/capture-event! when a render op arrives with no in-flight cascade; back-fills the render into the causing epoch record and re-fans it to epoch listeners so snapshot consumers re-sync. Fixes the one-epoch :renders lag."}
   {:key         :epoch/record-sub-run!
    :producer-ns 're-frame.epoch
    :design-bead "rf2-wi900"
    :description "Subs sibling of :epoch/record-render!. Attribute a post-settle sub-run emit (a :sub/run / :rf.sub/skip op firing at React deref time, after the causing cascade settled, because reactions recompute lazily) back to the cascade that caused it — the frame's most-recently-settled epoch. Called by re-frame.epoch.capture/capture-event! when a sub-run op arrives with no in-flight cascade; back-fills the sub-run (and its :value-changed? / :prev-value / :value attribution) into the causing epoch record and re-fans it to epoch listeners. Fixes the one-epoch :sub-runs lag visible in Xray's per-cascade Views subs table."}
   {:key         :epoch/record-unmount!
    :producer-ns 're-frame.epoch
    :design-bead "rf2-59hx3"
    :description "Teardown sibling of :epoch/record-render! / :epoch/record-sub-run!. Attribute a post-settle view-unmount emit (a :rf.view/unmounted op firing at React componentWillUnmount / useEffect-cleanup time, after the cascade that removed the view settled) back to the cascade that caused the teardown — the frame's most-recently-settled epoch. Called by re-frame.epoch.capture/capture-event! when an unmount op arrives with no in-flight cascade. Pre-rf2-59hx3 the unmount fell through to the orphan-drop branch and was silently dropped, so a view teardown produced NO signal anywhere; this back-fills it into the causing epoch's :trace-events (no structured row — an unmount is neither a :renders nor a :sub-runs entry) and re-fans the record to epoch listeners, where Xray's VIEWS-step unmounted-views-rows surfaces it. Fixes the invisible view-teardown gap (button-deck button 13)."}
   {:key         :epoch/epoch-history
    :producer-ns 're-frame.epoch
    :description "Return the committed-epoch ring buffer (introspection)."}
   {:key         :epoch/restore-epoch!
    :producer-ns 're-frame.epoch
    :description "Restore app-db / schemas to a previously-captured epoch."}
   {:key         :epoch/replace-frame-state!
    :producer-ns 're-frame.epoch
    :design-bead "rf2-t3lftq"
    :description "Atomically install a PARTIAL frame-state map (any subset of {:rf.db/app ... :rf.db/runtime ...}) — a present key replaces that partition, an absent key is preserved. The ONE frame-state write surface (API-shrink #3 consolidated the former replace-app-db! / reset-app-db! / replace-runtime-db! / replace-frame-state! four-mutator family into this). Epoch-backed Tool-Pair write: records a synthetic :rf/epoch-record so restore-epoch! rewinds past it, returns boolean, shares the drain-guard + rejects a map with no recognized / an unrecognized partition key + per-present-partition schema-validation contract."}
   {:key         :epoch/register-epoch-listener!
    :producer-ns 're-frame.epoch
    :description "Register an epoch-settled callback."}
   {:key         :epoch/unregister-epoch-listener!
    :producer-ns 're-frame.epoch
    :description "Unregister a previously-registered epoch-settled callback."}
   {:key         :epoch/configure!
    :producer-ns 're-frame.epoch
    :description "Configure epoch buffer size / capture policy."}
   {:key         :epoch/reset-config!
    :producer-ns 're-frame.epoch
    :design-bead "rf2-yw1w1u"
    :description "Restore epoch-history config to the shipped default baseline (test isolation) so a prior test's (rf/configure! {:epoch-history ...}) merge can't leak :depth / :trace-events-keep / :redact-fn. Fired by re-frame.test-support's reset-hook table so test namespaces don't reset the private re-frame.epoch.state/config var directly."}
   {:key         :epoch/clear-history!
    :producer-ns 're-frame.epoch
    :description "Clear the committed-epoch ring buffer (test isolation)."}
   {:key         :epoch/clear-epoch-listeners!
    :producer-ns 're-frame.epoch
    :description "Clear every registered epoch-settled callback (test isolation)."}
   {:key         :epoch/on-frame-destroyed
    :producer-ns 're-frame.epoch
    :description "Tear down a frame's epoch state when the frame is destroyed. Invoked as (f frame-id fs-before fs-after): fs-before is the in-flight event's pre-run frame-state snapshot (frame/*run-frame-state-before*), fs-after the destroy-time frame-state value captured before teardown — the real :frame-state-before/:frame-state-after slots (and their :db-* app-db projections) a mid-drain :halted-destroy epoch record carries per Spec-Schemas §:rf/epoch-record §Outcomes (rf2-9neiq / rf2-3aizt1). Both nil for an out-of-run destroy."}
   {:key         :epoch/projected-record
    :producer-ns 're-frame.epoch
    :design-bead "rf2-mrsck"
    :description "Project an :rf/epoch-record for off-box egress: route :db-before / :db-after / :trigger-event / :trace-events through elide-wire-value with off-box defaults; bookkeeping and structured projections pass through. Per Security.md §Epoch privacy posture."}
   {:key         :epoch/projected-history
    :producer-ns 're-frame.epoch
    :design-bead "rf2-mrsck"
    :description "Convenience wrapper returning (mapv projected-record (epoch-history frame-id))."}

   ;; ---- re-frame.event-emit (always-on event observability) ----------------
   {:key         :event-emit/dispatch-on-event
    :producer-ns 're-frame.event-emit
    :design-bead "rf2-rirbq"
    :description "Always-on per-event fan-out for production observability (Datadog / Honeycomb / Sentry). Survives `:advanced` + `goog.DEBUG=false`; parallel to (not a fallback for) the dev-only trace surface. Router invokes once per processed event after the cascade settles."}

   ;; ---- re-frame.error-emit (always-on error observability) ----------------
   {:key         :error-emit/dispatch-on-error
    :producer-ns 're-frame.error-emit
    :design-bead "rf2-bacs4"
    :description "Always-on per-`:rf.error/*` fan-out: builds the tight error-record once (elided), then fans it out to the corpus-wide listener registry (rf2-bacs4 — Sentry / Honeybadger / Rollbar shippers), ALWAYS fired. Per-listener invocations try/catch wrapped (a buggy listener cannot block siblings). Recovery is framework-owned (the per-category typed defaults); the per-frame `:on-error` recovery policy was REMOVED (rf2-hiqtk8, superseding the rf2-2hvga axis-2 / recovery-policy-eligible column). Survives `:advanced` + `goog.DEBUG=false`. Invoked from EVERY production-reachable `:rf.error/*` site: router (handler-exception, flow-eval, frame-destroyed), fx (reserved-fx typed throws), subs/memo + subs (reactive + compute-sub exceptions), subs (frame-destroyed, no-such-sub on subscribe), router/diagnostics (no-such-handler)."}
   {:key         :error-emit/emit-error-both
    :producer-ns 're-frame.error-emit
    :design-bead "rf2-c4oycd"
    :description "The shared two-channel `:rf.error/*` fan-out (rf2-c4oycd) — fires the always-on `dispatch-on-error!` listener record (axis 1, production-survivable) AND the dev-only `trace/emit-error!` surface (axis 2, DCE'd under `:advanced` + `goog.DEBUG=false`) in one call. Collapses the open-coded two-step that was duplicated at ~12 emit sites across `subs` / `subs.memo` / `cofx` / `router.diagnostics` (+ `fx`'s `emit-fx-error!` + the 4 bespoke `router` wrappers). Takes `[category event event-id frame exception elapsed-ms time trace-tags]` — `trace-tags` is the category-specific dev-trace map threaded unchanged. Reached via this hook by `fx` / `subs` / `subs.memo` / `cofx` / `router.diagnostics` (which cannot static-require `error-emit` — the `error-emit` → `elision` → `frame` load cycle); `router` static-requires `error-emit` and calls it directly. Survives `:advanced` + `goog.DEBUG=false`."}
   {:key         :error-emit/dispatch-frame-teardown-report
    :producer-ns 're-frame.error-emit
    :design-bead "rf2-ini4wr"
    :description "Always-on ONE-bounded-record-per-destroy frame-teardown report (EP-0008 promotion criterion / Spec 009 §Channel-promotion catalogue rows). `frame/destroy-frame!` accumulates per-hook failures during the best-effort teardown walk and, through a FINALLY-shaped flush boundary, fires this hook once with the `:frame` + the collected `:hook-failures` vector — so a mid-teardown abort still ships the entries gathered so far. Builds the catalogue-shaped `:rf.error/frame-teardown-failed` record (`:recovery :ignored`) and fans it out to the corpus-wide error listeners. NOT one record per failed hook (the per-hook detail stays on the dev-only `:rf.warning/teardown-hook-exception` trace, DCE'd in prod). `frame` reaches it via late-bind because a static require closes a `error-emit` → `elision` → `frame` load cycle. Survives `:advanced` + `goog.DEBUG=false`. No-op when no hook failed."}
   {:key         :error-emit/dispatch-error-record
    :producer-ns 're-frame.error-emit
    :design-bead "rf2-hhutya"
    :description "Always-on GENERAL non-event error record (EP-0008 SSR promotion). The non-event counterpart of `:error-emit/dispatch-on-error`: fans a PRE-BUILT union record `{:error <kw> :frame <id-or-nil> :time <ms> + flat category keys}` out to the corpus-wide error-emit listener registry unchanged. Carries the `:rf.error/*` categories that are NOT a dispatched-event / subscribe failure and so do not fit the event-centric positional shape — the teardown report (under the hood) and the EP-0008 promoted SSR categories (`:rf.error/ssr-render-failed`, `:rf.error/ssr-streaming-writer-failed`, `:rf.error/malformed-hydration-payload` incl. the pre-frame FRAMELESS parse path, `:rf.error/ssr-head-resolution-failed`, `:rf.error/sanitised-on-projection`, `:rf.error/ssr-ring-error-view-failed`). Invoked from the SSR / ssr-ring host layers (which ship above core's require graph) via this hook, ALONGSIDE their dev-gated `trace/emit-error!`. The shared union shape (settled jointly with the teardown report, NOT a second ad-hoc shape) is designed compatibly with the EP-0015 §S8 sink routing. Survives `:advanced` + `goog.DEBUG=false`."}
   {:key         :error-emit/register-error-listener!
    :producer-ns 're-frame.error-emit
    :design-bead "rf2-87f7fb"
    :description "Register a TRANSIENT always-on error listener under a key on the corpus-wide error-emit registry (the same surface `rf/register-error-listener!` exports). Published so `frame/fire-on-destroy-event!` can observe the router's always-on `:rf.error/handler-exception` fan-out for the duration of a throwing `:on-destroy` dispatch and re-emit the discriminable `:rf.error/on-destroy-handler-exception` category — WITHOUT a static `re-frame.frame` → `re-frame.error-emit` require (that closes the `error-emit` → `elision` → `frame` load cycle). Replaces the dev-only `:trace.tooling/register-listener!` capture the common path used pre-EP-0008 (which DCE'd under `goog.DEBUG=false`, so the dedicated teardown discriminator did not survive prod, rf2-87f7fb). Survives `:advanced` + `goog.DEBUG=false`."}
   {:key         :error-emit/unregister-error-listener!
    :producer-ns 're-frame.error-emit
    :design-bead "rf2-87f7fb"
    :description "Drop a listener registered through `:error-emit/register-error-listener!`. Paired with it in `frame/fire-on-destroy-event!`'s finally-shaped teardown of the transient `:on-destroy`-throw capture listener (rf2-87f7fb), so the listener never leaks past the dispatch. Survives `:advanced` + `goog.DEBUG=false`."}

   ;; ---- re-frame.observability (EP-0015 §9 frame sink routing) --------------
   {:key         :observability/route-handled-event
    :producer-ns 're-frame.observability
    :design-bead "rf2-t55hxg.7"
    :description "EP-0015 §9 (the central claim, made production-live): route ONE `:rf.observe/handled-event` record per processed event to the owning frame's declared `:observability :handled-events` sinks. Builds the canonical handled-event record (`:frame` / `:event-id` / `:event` / `:status` / `:elapsed-ms` / `:effects` / `:correlation`), projects it through `project-egress` under the frame's classification and the entry's `:rf.egress/profile` (default `:rf.egress/off-box-observability`), and delivers the ALREADY-PROJECTED record to each entry's registered `:sink` fn. The off-box default omits the `:event` args slot entirely (EP-0015 issue 4); a buggy sink is try/catch isolated. Fail-closed: a NO-OP when the frame is unresolved (destroyed / never-registered) or declares no `:handled-events` policy — never synthesises `:rf/default`, never borrows another frame's policy. Called once per processed event from the router's cascade trailers, ALONGSIDE the always-on `event-emit` listener fan-out. Reached via late-bind because a static `router` → `observability` → `projection` → `elision` → `frame` require closes a load cycle. Survives `:advanced` + `goog.DEBUG=false`."}
   {:key         :observability/route-error
    :producer-ns 're-frame.observability
    :design-bead "rf2-t55hxg.7"
    :description "EP-0015 §9: route ONE `:rf.observe/error` record per `:rf.error/*` site to the owning frame's declared `:observability :errors` sinks. Builds the canonical error record (`:frame` / `:error` / `:event-id` / `:event` / `:exception` / `:elapsed-ms` / `:time` / `:correlation`), projects it through `project-egress` (the `:event` tree slot redacts under frame policy; `:exception` is dropped under `:rf.egress/public-error`, walked otherwise), and delivers the projected record to each entry's `:sink`. Fail-closed: a NO-OP on an unresolved frame or absent `:errors` policy. Called from `error-emit/dispatch-on-error!`, ALONGSIDE the always-on corpus-wide error-listener fan-out. Reached via late-bind (load-cycle break). Survives `:advanced` + `goog.DEBUG=false`."}
   {:key         :observability/route-error-record
    :producer-ns 're-frame.observability
    :design-bead "rf2-ntv9i9.1"
    :description "EP-0015 §9 / Spec 015 §Frame-owned observability sink policy: the NON-EVENT counterpart of `:observability/route-error`. Routes a PRE-BUILT EP-0008 union error record `{:error <kw> :frame <id-or-nil> :time <ms> + flat category keys}` (the frame-teardown report, the promoted SSR categories) to the owning frame's declared `:observability :errors` sinks. Projects the record into a canonical `:rf.observe/error` shape — summary slots pass through, the host `:exception` rides the top-level slot the projector DROPS under `:rf.egress/public-error`, and every remaining flat category slot (`:hook-failures` / `:phase` / `:reason` / `:projector-id` / …) is lifted onto `:tags` so the projector REDACTS it under frame classification (same generic tags-lift the SSR `error-emit-projection-listener` performs) — then routes through `project-egress`. Closes the rf2-ntv9i9.1 gap where non-event always-on records reached ONLY the corpus-wide `register-error-listener!` registry and bypassed the frame-owned sink model. Fail-closed: a NO-OP on an unresolved / frameless (`:frame nil`) record or absent `:errors` policy. Called from `error-emit/dispatch-error-record!`, ALONGSIDE the always-on corpus-wide error-listener fan-out. Reached via late-bind (load-cycle break). Survives `:advanced` + `goog.DEBUG=false`."}

   ;; ===========================================================================
   ;; GROUP 3 — ADAPTER-INJECTION
   ;;
   ;; Substrate adapters (Reagent / reagent-slim / UIx / Helix / test-
   ;; react) publish CLJS-side primitives the core runtime consumes
   ;; (`:adapter/current-frame`, `:adapter/ratom`, `:adapter/after-
   ;; render`, …) and React-shaped view machinery
   ;; (`:reagent/set-hiccup-emitter!`, `:views/*`). Routed via
   ;; `substrate-adapter/route-hook!` so the installed-adapter identity
   ;; dispatches; chained across every loaded adapter so a single SSR
   ;; ns-load auto-wires every adapter's slot.
   ;; ===========================================================================

   ;; ---- re-frame.adapter.reagent --------------------------------------------
   {:key         :reagent/set-hiccup-emitter!
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim
                   re-frame.adapter.uix
                   re-frame.adapter.helix
                   re-frame.adapter.test-react]
    :chained?    true
    :design-bead "rf2-4z7bp"
    :description "Install the substrate-specific hiccup emitter for SSR. Chained — every loaded React-shaped adapter contributes its own install step so a single SSR ns-load auto-wires every adapter's render-to-string slot."}

   ;; ---- re-frame.views (CLJS, warn-once chain) ------------------------------
   {:key         :views/reading-render-key
    :producer-ns 're-frame.views
    :design-bead "rf2-vh1k3"
    :description "Return the render-key of the view whose render is currently deref-ing a subscription (nil outside a view render). The reactive :sub/run emit stamps it onto its tag so the epoch back-fill can tell a view's genuine re-render (its own input changed) from a mount-burst tail that re-derefs unchanged subs."}
   {:key         :views/record-view-deref!
    :producer-ns 're-frame.views
    :design-bead "rf2-9hoos"
    :description "Record a view→sub edge: push the deref'd query-v into the in-flight render's deref sink so :rf.view/rendered carries the view's OWN read-set (:deref-subs), the precise per-view reactive reason (vs the cascade-wide :cause-subs, which over-reports). Called by re-frame.subs/subscribe under interop/debug-enabled?; no-op outside a view render."}
   {:key         :views/emit-view-unmounted!
    :producer-ns 're-frame.views
    :design-bead "rf2-te71r"
    :description "Emit :rf.view/unmounted for a view instance's teardown. Consumed by the shared React-hook spine (make-wrap-view) so UIx/Helix views emit on unmount via a React.useEffect cleanup, restoring parity with the Reagent family's phase-A (rf2-9hoos) reaction-dispose unmount hook. Reaching the emit through late-bind keeps the spine free of a static require on the CLJS-only views ns; both sides gate on interop/debug-enabled?."}

   ;; ---- :adapter/* — chained / routed across every CLJS adapter -------------
   {:key         :adapter/clear-warn-once-caches!
    :producer-ns '[re-frame.views.warn-once
                   re-frame.views
                   re-frame.adapter.helix
                   re-frame.adapter.uix]
    :chained?    true
    :design-bead "rf2-4edk"
    :description "Chained reset of EVERY adapter/views warn-once defonce cache the standard make-reset-runtime-fixture must wipe between tests. Per rf2-z79p8 every contributor enrols through the single governance chokepoint re-frame.late-bind/register-warn-once-clear-fn! (which chains the clear-fn here AND records it in the warn-once-clear governance registry). Members: re-frame.views.warn-once's warned-non-dom-roots, re-frame.views's rf2-9hoos seen-render-keys (:mount? discriminator), the React-hook spine's per-adapter source-coord cache (re-frame.substrate.spine, used by helix/uix), and the slim hiccup interpreter's warned-keyword-prop (re-frame.adapter.reagent-slim, rf2-qy6cl). The warn-once-clear governance assertion enumerates the registry and proves each member is wiped by this chain so a future cache cannot silently escape the fixture. (A 5th member, warned-plain-fn-frame-pairs, was removed in rf2-k4xous once its warning was retired per EP-0002.)"}
   {:key         :adapter/current-frame
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim
                   re-frame.adapter.uix
                   re-frame.adapter.helix
                   re-frame.adapter.test-react]
    :chained?    true
    :design-bead "rf2-d4sf"
    :description "React-context-tier frame-id reader (each adapter routes via current-adapter)."}
   {:key         :adapter/current-component
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim]
    :chained?    true
    :design-bead "rf2-wbnl"
    :description "Resolve the in-flight Reagent component (routed via current-adapter)."}
   {:key         :adapter/ratom
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim]
    :chained?    true
    :design-bead "rf2-s36l"
    :description "Substrate-specific ratom constructor (re-frame.interop/ratom). Per rf2-jicu2 not published by UIx/Helix — those substrates ship no reactive-atom primitive and re-frame.interop's reactive surfaces have zero production call sites under them."}
   {:key         :adapter/ratom?
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim]
    :chained?    true
    :design-bead "rf2-s36l"
    :description "Substrate-specific ratom predicate (re-frame.interop/ratom?). Per rf2-jicu2 not published by UIx/Helix; absent-hook fallback returns false."}
   {:key         :adapter/make-reaction
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim]
    :chained?    true
    :design-bead "rf2-s36l"
    :description "Substrate-specific make-reaction (re-frame.interop/make-reaction). Per rf2-jicu2 not published by UIx/Helix; absent-hook returns nil."}
   {:key         :adapter/add-on-dispose!
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim
                   re-frame.adapter.uix
                   re-frame.adapter.helix]
    :chained?    true
    :design-bead "rf2-s36l"
    :description "Substrate-specific add-on-dispose! (re-frame.interop/add-on-dispose!). Per rf2-jicu2 UIx/Helix route to the re-frame-owned re-frame.disposable/IDisposable protocol; Reagent/reagent-slim dispatch both that protocol and their substrate's own IDisposable."}
   {:key         :adapter/dispose!
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim
                   re-frame.adapter.uix
                   re-frame.adapter.helix]
    :chained?    true
    :design-bead "rf2-s36l"
    :description "Substrate-specific dispose! (re-frame.interop/dispose!). Per rf2-jicu2 UIx/Helix route to the re-frame-owned re-frame.disposable/IDisposable protocol; Reagent/reagent-slim dispatch both that protocol and their substrate's own IDisposable."}
   {:key         :adapter/reactive?
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim]
    :chained?    true
    :design-bead "rf2-s36l"
    :description "Substrate-specific reactive? predicate (re-frame.interop/reactive?). Per rf2-jicu2 not published by UIx/Helix; absent-hook fallback returns false."}
   {:key         :adapter/derived-container?
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim]
    :chained?    true
    :design-bead "rf2-8wrzz.3"
    :description "Container-class hook the core's replace-container! choke point consults to reject writes to a make-derived-value result (Spec 006 §make-derived-value). Tri-valued (rf2-oitw37): truthy = DERIVED (reject), false = BASE (the installed adapter classifies it as one of ITS writable base containers — delegate, and the choke point skips its atom-marker heuristic so a custom non-atom base container is not misclassified), and the re-frame.substrate.adapter/container-class-unknown sentinel = NO opinion (the choke point falls back to the host atom-marker heuristic). Published by the ratom family (Reagent / reagent-slim): their Reaction reifies IAtom exactly like a base r/atom, so the atom-marker fall-back cannot tell them apart — the hook keys on the substrate disposal protocol (a Reaction is disposable, a base r/atom is not) and answers truthy/false exhaustively. Custom adapters whose base container is NOT atom-shaped publish their own routed hook the same way. Not published by plain-atom / test-react / UIx / Helix, whose derived values are NOT atom-shaped (the atom-marker fall-back classifies them correctly); the routed chain-bottom fallback returns the container-class-unknown sentinel."}
   {:key         :adapter/after-render
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim
                   re-frame.adapter.uix
                   re-frame.adapter.helix]
    :chained?    true
    :design-bead "rf2-s36l"
    :description "Substrate-specific after-render hook (re-frame.interop/after-render). Per rf2-334d9 the UIx + Helix adapters publish a `React.useLayoutEffect`-backed impl via the spine's after-render machinery (Mike decision rf2-neiqf). Reagent + reagent-slim route through their substrate's native render scheduler."}
   {:key         :adapter/wrap-view
    :producer-ns '[re-frame.adapter.uix
                   re-frame.adapter.helix]
    :chained?    true
    :design-bead "rf2-00li"
    :description "Substrate-side source-coord injection on rendered React elements."}

   ;; ===========================================================================
   ;; GROUP 4 — HOT-RELOAD / DEV-TOOLING
   ;;
   ;; Dev-only surfaces gated on `interop/debug-enabled?` whose producer
   ;; namespaces DCE under `:advanced` + `goog.DEBUG=false`. The late-
   ;; bind indirection lets production CLJS bundles short-circuit
   ;; cleanly (lookup returns nil, consumer no-ops) without surface-
   ;; pruning the call sites.
   ;; ===========================================================================

   ;; ---- re-frame.trace (re-frame.registrar replace-warning seam) ------------
   {:key         :trace/emit!
    :producer-ns 're-frame.trace
    :description "Emit a trace event (registrar replace-warning seam)."}
   {:key         :trace/emit-error!
    :producer-ns 're-frame.trace
    :description "Emit a trace error event (registrar replace-warning seam)."}

   ;; ---- re-frame.trace.tooling (dev-tooling buffer + listener surface,
   ;; separate from trace.cljc for production DCE; trace.cljc reaches the buffer
   ;; push + listener fan-out through this single hook). The public
   ;; surface fns (`register-listener!` / `unregister-listener!` /
   ;; `clear-listeners!` / `trace-buffer` / `clear-trace-buffer!` /
   ;; `configure-trace-buffer!` / `configure`) are exposed directly from
   ;; `re-frame.trace.tooling`; consumers (tests, tools, SSR's listener
   ;; registration) call them through `re-frame.trace.tooling/<name>`
   ;; rather than going through a wrapper in `re-frame.trace`. Keeping
   ;; the seam at exactly one hook key avoids paying for N keyword
   ;; interns at module-init time in every consumer that loads
   ;; `re-frame.trace` (which is everyone).
   {:key         :trace.tooling/deliver!
    :producer-ns 're-frame.trace.tooling
    :design-bead "rf2-qwm0a"
    :description "Per-event buffer-push + listener fan-out invoked by trace.cljc's `deliver!`."}
   {:key         :trace.tooling/configure-trace-buffer!
    :producer-ns 're-frame.trace.tooling
    :design-bead "rf2-qwm0a"
    :description "Set the trace ring buffer depth. Late-bound from `re-frame.core/configure!`'s `:trace-buffer` key so a no-tooling production build silently no-ops."}
   {:key         :trace.tooling/register-listener!
    :producer-ns 're-frame.trace.tooling
    :design-bead "rf2-r1ciy"
    :description "Register a dev-trace listener. Hook retained for the directory contract (the family is published as one rf2-r1ciy seam), but the live consumers — tests, tools, and SSR's listener registration — call `re-frame.trace.tooling/register-listener!` directly. The former `:on-destroy`-throw capture that drove this hook from `re-frame.frame/fire-on-destroy-event!` migrated to the production-survivable always-on axis (`:error-emit/register-error-listener!`) under EP-0008 / rf2-87f7fb."}
   {:key         :trace.tooling/unregister-listener!
    :producer-ns 're-frame.trace.tooling
    :design-bead "rf2-r1ciy"
    :description "Drop a dev-trace listener. Sibling of `:trace.tooling/register-listener!` — same rf2-r1ciy seam; same direct-call consumers (tests / tools / SSR)."}

   ;; ---- re-frame.trace.tooling — per-frame trace rings ----------------------
   ;;
   ;; The four hooks below carry the per-frame ring + B4 dedup machinery.
   ;; They're consulted from
   ;; `re-frame.frame/reg-frame` + `destroy-frame!` (lifecycle) and
   ;; `re-frame.registrar/register!` / `unregister!` / `clear-kind!`
   ;; (B4 dedup). All routed via late-bind so production CLJS bundles
   ;; that never load `re-frame.trace.tooling` short-circuit cleanly
   ;; — the ring + dedup machinery is dev-only and DCEs out wholesale.
   {:key         :trace.tooling/dedup-allow?
    :producer-ns 're-frame.trace.tooling
    :design-bead "rf2-g1b2m"
    :description "B4 hot-reload dedup-by-shape gate. Registrar emits consult this to suppress unchanged re-emits (`(operation, kind, id, meta) -> allow?`). Identical shape on re-register → false; changed shape or no prior entry → true."}
   {:key         :trace.tooling/clear-dedup-table!
    :producer-ns 're-frame.trace.tooling
    :design-bead "rf2-g1b2m"
    :description "Reset the B4 dedup table. `re-frame.registrar/clear-all!` calls this so test fixtures start from a clean slate. Production builds (no tooling sibling) no-op."}
   {:key         :trace.tooling/release-frame-ring!
    :producer-ns 're-frame.trace.tooling
    :design-bead "rf2-g1b2m"
    :description "Frame-destroy ring cleanup. `re-frame.frame/destroy-frame!` invokes this once the destroyed-trace has fired so the destroyed frame leaves no residual ring state in memory."}
   {:key         :trace.tooling/set-frame-events-retained!
    :producer-ns 're-frame.trace.tooling
    :design-bead "rf2-g1b2m"
    :description "Apply a per-frame `:rf.trace/events-retained` override. `re-frame.frame/reg-frame` invokes this when the config carries the key; raises / lowers the ring's slot cap (one slot per event / pipeline run), trimming evictions in-place when lowering."}
   {:key         :frame/current-frame-id
    :producer-ns 're-frame.frame
    :design-bead "rf2-g1b2m"
    :description "Read the currently-bound frame id from `re-frame.frame/*current-frame*`. Consulted by `re-frame.trace.tooling/push-to-ring!` as the routing fallback when the trace event itself does not carry a `:frame` tag (e.g. sub recompute / view render emits inside an in-flight cascade)."}

   ;; ---- re-frame.trace.cascade (focused-event-only cascade-DAG aggregator) ----
   ;;
   ;; The three hooks let `re-frame.epoch/settle!` reach the aggregator
   ;; (`:trace.cascade/capture-for-epoch!`) without requiring the
   ;; cascade ns; the focus-predicate pair (`set-focus-predicate!` /
   ;; `clear-focus-predicate!`) publishes the install / withdraw surface
   ;; through the same registry the rest of the substrate uses. (No Xray
   ;; consumer ships against the focus-predicate hooks today — the only
   ;; in-tree caller is the core `trace_cascade_captured_test`, which
   ;; drives the aggregator directly via `re-frame.trace.cascade/<name>`.)
   ;; The cascade ns is JVM-autoloaded from `re-frame.core` only; CLJS
   ;; production builds DCE the ns so the hooks are simply unbound on
   ;; the prod side.
   {:key         :trace.cascade/capture-for-epoch!
    :producer-ns 're-frame.trace.cascade
    :design-bead "rf2-931pm"
    :description "Focused-event-only per-epoch cascade-DAG aggregator. `epoch/settle!` invokes once per drain-settle after the cascade buffer has been harvested; no-op when the installed focus predicate returns false."}
   {:key         :trace.cascade/set-focus-predicate!
    :producer-ns 're-frame.trace.cascade
    :design-bead "rf2-931pm"
    :description "Install the predicate the aggregator consults at end-of-epoch (`(fn [frame-id epoch-id event-id] truthy?)`). Hook published for a focus-publishing consumer to drive at mount; no Xray consumer ships against it today — the only in-tree caller is the core `trace_cascade_captured_test`, which calls `re-frame.trace.cascade/set-focus-predicate!` directly."}
   {:key         :trace.cascade/clear-focus-predicate!
    :producer-ns 're-frame.trace.cascade
    :design-bead "rf2-931pm"
    :description "Restore the no-op default focus predicate (no epoch focused). Withdraw counterpart of `:trace.cascade/set-focus-predicate!`; same no-Xray-consumer status — only the core `trace_cascade_captured_test` calls `re-frame.trace.cascade/clear-focus-predicate!` directly."}

   ;; NOTE: `:subs/resolve-sub-override` — the SUBSTITUTIVE
   ;; dev-only sub-override seam consulted by `re-frame.subs/subscribe`
   ;; inside its `interop/debug-enabled?` gate — is PUBLISHED from the
   ;; Story side (`re-frame.story.sub-overrides`, under `tools/`), NOT
   ;; from `implementation/`. Core only CONSULTS it (consult ≠ publish),
   ;; so it deliberately has NO entry here: this directory + its drift
   ;; test (`late_bind_drift_test`) scope the inventory to keys published
   ;; from `implementation/**/src`. Every other tools-published hook
   ;; (`:tap-stub-event`, `:run-play-step`, …) is likewise absent. The
   ;; consult site documents the contract in full; keeping core tools-free
   ;; (bundle-isolation) is exactly why the resolver lives Story-side.
   ])

(defn hook-keys
  "Set of every late-bind hook key documented in the directory."
  []
  (into #{} (map :key) hooks))

(defn entry
  "Look up the directory entry for `hook-key`, or nil."
  [hook-key]
  (some (fn [e] (when (= hook-key (:key e)) e)) hooks))
