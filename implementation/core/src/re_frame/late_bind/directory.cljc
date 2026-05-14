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
                   chain has multiple publishers, see rf2-0d35)
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

  Ordering: grouped by namespace prefix (router → flows → schemas →
  machines → routing → http → subs → ssr → reagent → views →
  adapter → epoch → trace) so additions land near their siblings."
  [;; ---- re-frame.router (rf2-d4sf foundational dispatch seam) ----------------
   {:key         :router/dispatch!
    :producer-ns 're-frame.router
    :description "Enqueue an event for processing by the drain loop."}
   {:key         :router/dispatch-sync!
    :producer-ns 're-frame.router
    :description "Process an event synchronously, bypassing the drain queue."}

   ;; ---- re-frame.flows -------------------------------------------------------
   ;; Both the public `rf/reg-flow` / `rf/clear-flow` surfaces AND the
   ;; `:rf.fx/reg-flow` / `:rf.fx/clear-flow` runtime fxs route through
   ;; the same two hooks (rf2-7ppmo). The api-shape `(arg opts)` already
   ;; carries the `:frame` opt the fx-side path needs, so a separate
   ;; `*-fx!` hook pair was dead indirection.
   {:key         :flows/reg-flow
    :producer-ns 're-frame.flows
    :description "Register a flow definition with the runtime (public-API + :rf.fx/reg-flow)."}
   {:key         :flows/clear-flow
    :producer-ns 're-frame.flows
    :description "Remove a previously-registered flow definition (public-API + :rf.fx/clear-flow)."}
   {:key         :flows/run-flows!
    :producer-ns 're-frame.flows
    :description "Re-evaluate every registered flow for the current frame."}
   {:key         :flows/reset-last-inputs!
    :producer-ns 're-frame.flows
    :description "Reset memoised last-input snapshots (test isolation)."}
   {:key         :flows/reset-flows!
    :producer-ns 're-frame.flows
    :description "Clear the per-frame flow registry (test isolation)."}

   ;; ---- re-frame.schemas (rf2-p7va boundary; rf2-r2uh / rf2-froe / rf2-t0hq) -
   {:key         :schemas/validate-event!
    :producer-ns 're-frame.schemas
    :description "Validate an event vector against the registered event schema."}
   {:key         :schemas/validate-app-db!
    :producer-ns 're-frame.schemas
    :description "Validate the app-db snapshot against the registered app-db schema."}
   {:key         :schemas/validate-cofx!
    :producer-ns 're-frame.schemas
    :description "Validate a cofx map against the registered cofx schema."}
   {:key         :schemas/validate-fx!
    :producer-ns 're-frame.schemas
    :design-bead "rf2-xp2o3"
    :description "Validate an fx-handler's args against the registered fx schema (Spec 010 step 5)."}
   {:key         :schemas/validate-sub-return!
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
   {:key         :schemas/validate-with-registered-fn
    :producer-ns 're-frame.schemas
    :design-bead "rf2-r2uh"
    :description "Boundary seam: validate using the registered validator fn."}
   {:key         :schemas/explain-with-registered-fn
    :producer-ns 're-frame.schemas
    :design-bead "rf2-r2uh"
    :description "Boundary seam: explain using the registered explainer fn."}
   {:key         :schemas/malli-validate
    :producer-ns 're-frame.schemas.malli
    :design-bead "rf2-t0hq"
    :description "Default-installed Malli validator (malli.core/validate)."}
   {:key         :schemas/malli-explain
    :producer-ns 're-frame.schemas.malli
    :design-bead "rf2-t0hq"
    :description "Default-installed Malli explainer (malli.core/explain)."}
   {:key         :schemas/extract-large-paths-from-schema
    :producer-ns 're-frame.schemas
    :design-bead "rf2-nwv63"
    :description "Walk a Malli EDN form at a base-path; return {path declaration} entries for :large? true slots. Consumed by re-frame.elision."}
   {:key         :schemas/extract-sensitive-paths-from-schema
    :producer-ns 're-frame.schemas
    :design-bead "rf2-kj51z"
    :description "Walk a Malli EDN form at a base-path; return paths whose props carry :sensitive? true. Consumed by re-frame.elision."}

   ;; ---- re-frame.machines (rf2-xbtj / rf2-8bp3) ------------------------------
   {:key         :machines/reg-machine
    :producer-ns 're-frame.machines
    :design-bead "rf2-8bp3"
    :description "Register a state machine definition (plain-fn surface)."}
   {:key         :machines/create-machine-handler
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
   {:key         :machines/on-frame-destroyed!
    :producer-ns 're-frame.machines
    :description "Per-frame `:after` timer-table cleanup hook called from frame/destroy-frame! (rf2-ysa94)."}
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
   {:key         :machines/invoke-all-init-fx
    :producer-ns 're-frame.machines
    :description "Effect handler invoking machine :init transitions during spawn."}
   {:key         :machines/after-schedule-fx
    :producer-ns 're-frame.machines
    :description "Effect handler scheduling a delayed machine transition."}
   {:key         :machines/after-cancel-fx
    :producer-ns 're-frame.machines
    :description "Effect handler cancelling a previously-scheduled transition."}

   ;; ---- re-frame.routing (rf2-k682) -----------------------------------------
   {:key         :routing/reg-route
    :producer-ns 're-frame.routing
    :description "Register a route pattern and handler."}
   {:key         :routing/match-url
    :producer-ns 're-frame.routing
    :description "Match a URL against the registered routes."}
   {:key         :routing/route-url
    :producer-ns 're-frame.routing
    :description "Build a URL for a route by id + params."}
   {:key         :routing/reset-counters!
    :producer-ns 're-frame.routing
    :description "Reset the route-registration counter (test isolation)."}
   {:key         :routing/route-sub-fn
    :producer-ns 're-frame.routing
    :description "Subscription fn returning the currently-matched route."}
   {:key         :routing/route-link
    :producer-ns 're-frame.routing
    :description "Reagent / SSR `[rf/route-link ...]` view component renderer."}

   ;; ---- re-frame.http-managed (rf2-5kpd / rf2-6y3q / rf2-wvkn / rf2-ijm7) ----
   {:key         :http/install-managed-request-stubs!
    :producer-ns 're-frame.http-managed
    :description "Install request stubs for managed HTTP testing."}
   {:key         :http/uninstall-managed-request-stubs!
    :producer-ns 're-frame.http-managed
    :description "Uninstall previously-installed managed-request stubs."}
   {:key         :http/with-managed-request-stubs*
    :producer-ns 're-frame.http-managed
    :description "Function form of the with-managed-request-stubs macro."}
   {:key         :http/clear-all-in-flight!
    :producer-ns 're-frame.http-managed
    :description "Abort every in-flight managed request (test isolation)."}
   {:key         :http/reg-http-interceptor
    :producer-ns 're-frame.http-managed
    :design-bead "rf2-6y3q"
    :description "Register a per-frame request-side HTTP interceptor."}
   {:key         :http/clear-http-interceptor
    :producer-ns 're-frame.http-managed
    :design-bead "rf2-6y3q"
    :description "Clear a single registered HTTP interceptor."}
   {:key         :http/clear-all-http-interceptors!
    :producer-ns 're-frame.http-managed
    :design-bead "rf2-6y3q"
    :description "Clear every registered HTTP interceptor (test isolation)."}
   {:key         :http/abort-on-actor-destroy
    :producer-ns 're-frame.http-managed
    :design-bead "rf2-wvkn"
    :description ":invoke cancellation cascade tied to actor destruction."}
   {:key         :http/register-managed-machine!
    :producer-ns 're-frame.http-managed
    :design-bead "rf2-ijm7"
    :description "Register the machine-shape wrapper for managed HTTP requests."}

   ;; ---- re-frame.subs --------------------------------------------------------
   {:key         :subs/subscribe-value
    :producer-ns 're-frame.subs
    :description "Subscribe and immediately deref (snapshot value, no reaction)."}

   ;; ---- re-frame.ssr (rf2-uo7v) ---------------------------------------------
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

   ;; ---- re-frame.ssr.head (rf2-4dra9 — head/meta contract) ------------------
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

   ;; ---- re-frame.adapter.reagent (rf2-0hxm) ---------------------------------
   {:key         :reagent/set-hiccup-emitter!
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim
                   re-frame.adapter.uix]
    :chained?    true
    :design-bead "rf2-4z7bp"
    :description "Install the substrate-specific hiccup emitter for SSR. Chained — every loaded React-shaped adapter contributes its own install step so a single SSR ns-load auto-wires every adapter's render-to-string slot."}

   ;; ---- re-frame.views (CLJS, rf2-4edk warn-once chain) ---------------------
   {:key         :views/maybe-warn-plain-fn-under-non-default-frame!
    :producer-ns 're-frame.views.warn-once
    :description "Emit the once-per-pair warning when a plain fn renders under a non-default frame."}
   {:key         :views/clear-plain-fn-warned-pairs!
    :producer-ns 're-frame.views.warn-once
    :description "Clear the warned-pairs cache (test isolation)."}

   ;; ---- :adapter/* — chained / routed across every CLJS adapter (rf2-0d35) --
   {:key         :adapter/clear-warn-once-caches!
    :producer-ns '[re-frame.views.warn-once
                   re-frame.adapter.helix
                   re-frame.adapter.uix]
    :chained?    true
    :design-bead "rf2-4edk"
    :description "Chained reset of every adapter's warn-once defonce caches."}
   {:key         :adapter/current-frame
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim
                   re-frame.adapter.uix
                   re-frame.adapter.helix]
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
                   re-frame.adapter.reagent-slim
                   re-frame.adapter.uix
                   re-frame.adapter.helix]
    :chained?    true
    :design-bead "rf2-s36l"
    :description "Substrate-specific ratom constructor (re-frame.interop/ratom)."}
   {:key         :adapter/ratom?
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim
                   re-frame.adapter.uix
                   re-frame.adapter.helix]
    :chained?    true
    :design-bead "rf2-s36l"
    :description "Substrate-specific ratom predicate (re-frame.interop/ratom?)."}
   {:key         :adapter/make-reaction
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim
                   re-frame.adapter.uix
                   re-frame.adapter.helix]
    :chained?    true
    :design-bead "rf2-s36l"
    :description "Substrate-specific make-reaction (re-frame.interop/make-reaction)."}
   {:key         :adapter/add-on-dispose!
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim
                   re-frame.adapter.uix
                   re-frame.adapter.helix]
    :chained?    true
    :design-bead "rf2-s36l"
    :description "Substrate-specific add-on-dispose! (re-frame.interop/add-on-dispose!)."}
   {:key         :adapter/dispose!
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim
                   re-frame.adapter.uix
                   re-frame.adapter.helix]
    :chained?    true
    :design-bead "rf2-s36l"
    :description "Substrate-specific dispose! (re-frame.interop/dispose!)."}
   {:key         :adapter/reactive?
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim
                   re-frame.adapter.uix
                   re-frame.adapter.helix]
    :chained?    true
    :design-bead "rf2-s36l"
    :description "Substrate-specific reactive? predicate (re-frame.interop/reactive?)."}
   {:key         :adapter/after-render
    :producer-ns '[re-frame.adapter.reagent
                   re-frame.adapter.reagent-slim
                   re-frame.adapter.uix
                   re-frame.adapter.helix]
    :chained?    true
    :design-bead "rf2-s36l"
    :description "Substrate-specific after-render hook (re-frame.interop/after-render)."}
   {:key         :adapter/wrap-view
    :producer-ns '[re-frame.adapter.uix
                   re-frame.adapter.helix]
    :chained?    true
    :design-bead "rf2-00li"
    :description "Substrate-side source-coord injection on rendered React elements."}

   ;; ---- re-frame.epoch (rf2-lt4e Tool-Pair surface) -------------------------
   {:key         :epoch/settle!
    :producer-ns 're-frame.epoch
    :description "Settle the current in-flight epoch (commit to history)."}
   {:key         :epoch/discard-buffer!
    :producer-ns 're-frame.epoch
    :description "Discard the in-flight epoch buffer without committing."}
   {:key         :epoch/capture-event
    :producer-ns 're-frame.epoch
    :description "Capture an event into the in-flight epoch buffer."}
   {:key         :epoch/epoch-history
    :producer-ns 're-frame.epoch
    :description "Return the committed-epoch ring buffer (introspection)."}
   {:key         :epoch/restore-epoch
    :producer-ns 're-frame.epoch
    :description "Restore app-db / schemas to a previously-captured epoch."}
   {:key         :epoch/reset-frame-db!
    :producer-ns 're-frame.epoch
    :description "Reset a frame's app-db to the epoch-recorded snapshot."}
   {:key         :epoch/register-epoch-cb!
    :producer-ns 're-frame.epoch
    :description "Register an epoch-settled callback."}
   {:key         :epoch/remove-epoch-cb!
    :producer-ns 're-frame.epoch
    :description "Unregister a previously-registered epoch-settled callback."}
   {:key         :epoch/configure!
    :producer-ns 're-frame.epoch
    :description "Configure epoch buffer size / capture policy."}
   {:key         :epoch/clear-history!
    :producer-ns 're-frame.epoch
    :description "Clear the committed-epoch ring buffer (test isolation)."}
   {:key         :epoch/clear-epoch-cbs!
    :producer-ns 're-frame.epoch
    :description "Clear every registered epoch-settled callback (test isolation)."}
   {:key         :epoch/on-frame-destroyed
    :producer-ns 're-frame.epoch
    :description "Tear down a frame's epoch state when the frame is destroyed."}

   ;; ---- re-frame.trace (re-frame.registrar replace-warning seam) ------------
   {:key         :trace/emit!
    :producer-ns 're-frame.trace
    :description "Emit a trace event (registrar replace-warning seam)."}
   {:key         :trace/emit-error!
    :producer-ns 're-frame.trace
    :description "Emit a trace error event (registrar replace-warning seam)."}

   ;; ---- re-frame.event-emit (rf2-rirbq — always-on event observability) -----
   {:key         :event-emit/dispatch-on-event
    :producer-ns 're-frame.event-emit
    :design-bead "rf2-rirbq"
    :description "Always-on per-event fan-out for production observability (Datadog / Honeycomb / Sentry). Survives `:advanced` + `goog.DEBUG=false`; parallel to (not a fallback for) the dev-only trace surface. Router invokes once per processed event after the cascade settles."}

   ;; ---- re-frame.error-emit (rf2-bacs4 — always-on error observability) -----
   {:key         :error-emit/dispatch-on-error
    :producer-ns 're-frame.error-emit
    :design-bead "rf2-bacs4"
    :description "Always-on per-`:rf.error/*` fan-out: builds the tight error-record once (elided), then fans out to BOTH the corpus-wide listener registry (rf2-bacs4 — Sentry / Honeybadger / Rollbar shippers) AND the per-frame `:on-error` policy fn (rf2-hqbeh — in-app recovery). Both fan-out paths independent and try/catch wrapped. Survives `:advanced` + `goog.DEBUG=false`. Router invokes from the handler-exception path."}

   ;; ---- re-frame.privacy (rf2-isdwf — sensitive-without-redaction cache) ----
   {:key         :privacy/clear-suppression-cache!
    :producer-ns 're-frame.privacy
    :design-bead "rf2-isdwf"
    :description "Reset the per-(kind, id) :rf.warning/sensitive-without-redaction suppression cache; called on frame destroy so re-registrations after teardown re-emit the warning if still mis-configured."}])

(defn hook-keys
  "Set of every late-bind hook key documented in the directory."
  []
  (into #{} (map :key) hooks))

(defn entry
  "Look up the directory entry for `hook-key`, or nil."
  [hook-key]
  (some (fn [e] (when (= hook-key (:key e)) e)) hooks))
