(ns re-frame.ssr
  "Server-side rendering and hydration façade.

  Requiring this namespace installs the SSR event, effect, coeffect, error
  projector, and listener registrations. It also eagerly loads the head and
  streaming server namespaces so their late-bound host hooks are available.

  The façade exposes pure HTML emission and hashing, client hydration,
  per-frame request and response side channels, error projection, streaming
  primitives, and the headless adapter. Core reaches this optional artefact
  only through `re-frame.late-bind`; this artefact may depend on core."
  (:require [re-frame.cofx :as cofx]
            [re-frame.emit :as rf-emit]
            [re-frame.events :as events]
            [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            ;; Loaded eagerly so both hydration boot re-exports resolve.
            [re-frame.ssr.boot :as boot]
            [re-frame.ssr.emit :as emit]
            ;; The cross-host suspense COMPONENT — the streaming authoring
            ;; surface on every substrate.
            [re-frame.ssr.suspense :as suspense]
            [re-frame.ssr.error-listener :as error-listener]
            [re-frame.ssr.error-projector :as error-projector]
            ;; Publishes the head hooks at namespace load.
            re-frame.ssr.head
            [re-frame.ssr.hash :as hash]
            [re-frame.ssr.hydrate :as hydrate]
            [re-frame.ssr.request :as request]
            [re-frame.ssr.response :as response]
            ;; Plain-data schemas attached to the seven server effects.
            [re-frame.ssr.server-fx-schemas :as server-fx-schemas]
            [re-frame.ssr.substrate :as substrate]
            ;; The S5 structural-tree -> HTML serialiser (rf2-3omxp).
            [re-frame.ssr.ui-tree :as ui-tree]
            ;; Publishes streaming server hooks at namespace load.
            re-frame.ssr.streaming
            ;; CLJS-only DOM consumer for streamed boundary chunks.
            #?(:cljs [re-frame.ssr.streaming.client :as streaming-client])
            ;; Listener registration lives on the tooling surface so production
            ;; code that does not use SSR does not retain trace tooling.
            [re-frame.trace.tooling :as trace-tooling]))

;; ---- public-surface re-exports --------------------------------------------
;;
;; `def`s expose the supported sub-namespace functions at the façade.

(def render-to-string                emit/render-to-string)
;; The S5 tree->HTML seam: an already-rendered version-1 structural tree
;; (from the JVM emitter) -> HTML string. Distinct from `render-to-string`,
;; which consumes hiccup and renders it (calls views, resolves subs).
;; Per Spec 004B §The SSR consumption boundary (rf2-3omxp).
(def emit-ui-tree                    ui-tree/emit-ui-tree)
(def install-render-to-string!       emit/install-render-to-string!)
;; rf2-8vi4q — `format-view-source-coord` (and its emitter-side companion
;; `inject-coord-on-root-hiccup`) are deleted: dev-mode view annotation
;; now lives at the reg-view registration boundary
;; (`re-frame.views.jvm-source-coord-annotation`), not in the emitter.
(def render-tree-hash                hash/render-tree-hash)
;; framework-private: tests reach into `#'ssr/canonical-edn` for the
;; JVM↔CLJS canonical-EDN parity check (hash_check_cljs_test).
(def ^:private canonical-edn         hash/canonical-edn)
(def ^:private fnv-1a-32             hash/fnv-1a-32)
(def adapter                         substrate/adapter)
(def verify-hydration!               hydrate/verify-hydration!)
;; `hydrate!` preserves read → hydrate → verify ordering. The DOM reader is
;; CLJS-only.
(def hydrate!                        boot/hydrate!)
;; A page is N roots; `hydrate-page!` boots them with per-root failure
;; isolation, so one failing root cannot stop the others (Spec 011
;; §Failed-root isolation).
(def hydrate-page!                   boot/hydrate-page!)
#?(:cljs (def read-server-payload    boot/read-server-payload))
(def default-response                response/default-response)
;; The response accumulator is a per-frame side channel, not application
;; state, so it cannot enter the hydration payload.
;; Tests reach the var via `(resolve 're-frame.ssr/response-slots)`.
(def ^:private response-slots        response/response-slots)
(def public-error-keys               error-projector/public-error-keys)
(def fallback-public-error           error-projector/fallback-public-error)
(def default-error-projector-fn      error-projector/default-error-projector-fn)
(def reg-error-projector             error-projector/reg-error-projector)
(def project-error                   error-projector/project-error)
(def apply-error-projection!         error-listener/apply-error-projection!)
;; Host adapters route render-time exceptions through the same projector as
;; drain-time errors so both paths materialise an equivalent wire response.
(def project-render-exception!       error-listener/project-render-exception!)
(def get-response                    error-listener/get-response)
;; `peek-response` is a pure read (no projector drain);
;; `flush-response!` drains pending error projections then reads.
;; `get-response` is kept as the drain-then-read host-adapter alias.
;; `flush-response-result!` is the drain-then-read variant that ALSO returns
;; the projected `:public-error` so host adapters classify drain-time
;; outcomes (4xx app arm vs 5xx error arm) without re-inferring from status.
(def peek-response                   error-listener/peek-response)
(def flush-response!                 error-listener/flush-response!)
(def flush-response-result!          error-listener/flush-response-result!)
;; framework-private at the public surface — Spec 011 §Per-request
;; frame teardown. Tests reach the var via `(resolve ...)`.
(def ^:private pending-error-traces  error-listener/pending-error-traces)
;; The error-view containment peek + one-shot clear (rf2-oytx7j) — a host
;; adapter detects a reactive sub that recovered-to-nil inside the error view
;; and falls back to the locked template without re-projecting.
(def pending-error-trace?            error-listener/pending-error-trace?)
(def clear-pending-error-traces!     error-listener/clear-pending-error-traces!)
;; Private at the façade. Tests reach via
;; `re-frame.ssr.test-fixture/reset-runtime` (the canonical reset surface)
;; or by `:require`-ing `re-frame.ssr.request` directly. Production
;; consumers go through `set-request!` / `get-request` / `clear-request!`.
(def ^:private request-slots         request/request-slots)
(def set-request!                    request/set-request!)
(def get-request                     request/get-request)
(def clear-request!                  request/clear-request!)
(def on-frame-destroyed!             request/on-frame-destroyed!)

;; ---- streaming SSR public surface -----------------------------------------
;;
;; Per Spec 011 §Streaming SSR. The AUTHORING surface is the `boundary`
;; component — one `.cljc` form that works on every host. The `:rf/suspense-
;; boundary` keyword it expands to on the server is internal wire syntax
;; between the component and the shell walker, not something an author
;; writes (a keyword head is an HTML element on every client substrate).
;; The server-side machinery below ships through these façade fns; host
;; adapters (ssr-ring/streaming) consume them via the late-bind hooks the
;; streaming ns publishes.

;; The cross-host suspense boundary component:
;;
;;     [ssr/boundary {:id :card.revenue :fallback [card-skeleton :revenue]}
;;      [card-view :revenue]]
;;
;; Server: expands to the `:rf/suspense-boundary` marker the shell walker
;; defers on. Client: renders the body, or the declared `:fallback` when
;; the boundary is in the failed set the final payload carried.
(def boundary                       suspense/boundary)
(def streaming-render-shell         re-frame.ssr.streaming/render-shell)
(def streaming-render-continuation  re-frame.ssr.streaming/render-continuation)
(def streaming-build-final-payload  re-frame.ssr.streaming/build-final-payload)
(def streaming-fallback-template    re-frame.ssr.streaming/fallback-template)
(def streaming-resolved-template    re-frame.ssr.streaming/resolved-template)
(def streaming-failed-template      re-frame.ssr.streaming/failed-template)
(def streaming-hydrate-delta-script re-frame.ssr.streaming/hydrate-delta-script)
;; Client-side streaming runtime. CLJS-only (DOM consumer):
;; `(ssr/streaming-install! {:frame …})` installs the MutationObserver
;; that swaps `<template>` fallbacks for resolved subtrees + merges the
;; per-subtree hydration deltas as chunks stream in, reconciling against
;; the final `__rf_payload` `:rf/hydrate` (which `ssr/hydrate!` applies).
;; Host opt-in — a streaming-aware bootstrap calls it; non-streaming
;; pages skip it.
#?(:cljs (def streaming-install!    streaming-client/install!))

;; ---- SSR blocking-resource drain ------------------------------------------
;;
;; The optional resources artefact owns the drain loop and timeout policy.
;; SSR calls its late-bound hook before rendering so blocking resources are
;; either settled or converted to a settled first-load failure. An application
;; without resources resolves the absent hook as a no-op.

(def ^:private default-ssr-blocking-timeout-ms
  "Default wall-clock render-deadline budget for the SSR blocking-resource
  drain (`drain-blocking-resources!`) when the host passes no
  `:ssr-blocking-timeout-ms`. A bounded budget is structurally required — an
  unbounded drain lets a never-settling blocking resource hang the request
  (Spec 016 §SSR and hydration: a blocked SSR render MUST NOT hang
  indefinitely). 5s matches a typical upstream HTTP read timeout; hosts tune
  it per deployment."
  5000)

(defn- default-blocking-pump!
  "The default SSR blocking-drain event PUMP — a host-platform yield of
  `tick-ms` so an in-flight blocking-resource reply (running on the managed-
  HTTP transport thread) makes progress and dispatches its reply event
  between the drain loop's re-checks. SSR runs on the JVM, so the yield is a
  `Thread/sleep`; on CLJS (which never server-renders) it is a no-op. The
  reply event itself drains synchronously when it lands on the frame's router,
  so a single bounded yield per tick is enough; the drain loop's wall-clock
  deadline caps the total wait (Spec 016 §SSR and hydration steps 3-4)."
  [tick-ms]
  #?(:clj  (when (and (number? tick-ms) (pos? tick-ms))
             (Thread/sleep (long tick-ms)))
     :cljs nil)
  nil)

(defn drain-blocking-resources!
  "Drain the current nav-token's BLOCKING resources for SSR `frame-id` until
  they settle or the render deadline fires, consulting the late-bound
  `:resources/drain-blocking-ssr!` hook the resources artefact publishes
  (Spec 016 §SSR and hydration steps 3-4). A no-op returning `{:settled? true}`
  when the resources artefact is absent (the hook is nil) — an SSR app without
  resources never blocks on them.

  The host render path (the Ring `build-full-response*` / streaming
  `render-streaming-shell!`) calls this AFTER frame setup + route resolution
  and BEFORE the render walk, so the walk sees a SETTLED resource state. The
  resources drain loop reads the live blocking set, pumps the event loop via
  the supplied `:pump!` thunk so an in-flight reply lands, and on deadline
  settles every still-unsettled blocking entry to a first-load failure in the
  frame's runtime-db (so the render sees a structured `:error`, never a hung
  `:loading`).

  `opts` keys (all optional):
    :ssr-blocking-timeout-ms — the wall-clock budget (default
                               `default-ssr-blocking-timeout-ms`).
    :pump!                   — the 1-arity `(fn [tick-ms] …)` event-pump
                               thunk. Defaults to the host platform yield
                               (`default-blocking-pump!`) so an async
                               blocking reply thread makes progress between
                               re-checks; a test may inject a deterministic
                               pump (or nil to drive a sync stub to timeout).
    :tick-ms                 — poll-granularity hint passed to `pump!`
                               (default 5ms).

  Returns the drain result map `{:settled? :timed-out :route-blocking-failure}`
  (see `re-frame.resources.ssr/drain-blocking-resources!`)."
  ([frame-id] (drain-blocking-resources! frame-id nil))
  ([frame-id {:keys [ssr-blocking-timeout-ms tick-ms] :as opts}]
   (if-let [drain (late-bind/get-fn :resources/drain-blocking-ssr!)]
     (drain frame-id
            {:deadline-ms (or ssr-blocking-timeout-ms default-ssr-blocking-timeout-ms)
             ;; an EXPLICIT `:pump!` (even nil — a sync test stub that drives a
             ;; never-settling resource straight to the deadline) wins; absence
             ;; defaults to the host-platform yield so an async reply lands.
             :pump!       (if (contains? opts :pump!)
                            (:pump! opts)
                            default-blocking-pump!)
             :tick-ms     (or tick-ms 5)})
     {:settled? true :timed-out [] :route-blocking-failure nil})))

;; ---- :rf/hydrate event + :rf.ssr/check-* fxs ------------------------------
;;
;; Spec 011 §The :rf/hydrate event + §Hydration-mismatch detection.

;; SSR is a framework-authorised runtime-db writer. The `:rf/hydrate` handler installs the
;; hydration metadata into the reserved `[:rf.runtime/ssr :hydration]` slot
;; by returning a `:rf.db/runtime` effect, so it mints framework-write
;; authority via the general `:rf/framework-authority?` registration-meta key
;; (Conventions §Reserved registration meta) — without it, every hydrate
;; dispatch would trip the `:rf.warning/app-handler-runtime-effect`
;; ownership diagnostic in development.
(events/reg-event :rf/hydrate
                     {:rf/framework-authority? true}
                     hydrate/hydrate-event-handler)

(fx/reg-fx :rf.ssr/check-version
  {:doc       "Compare the payload's :rf/version (server) against the
client runtime's version. A mismatch emits a structured
:rf.ssr/version-mismatch trace; the hydration handler still applies
(best-effort). Per Spec 011 §The :rf/hydrate event."
   :platforms #{:client}}
  hydrate/check-version-fx)

(fx/reg-fx :rf.ssr/check-schema-digest
  {:doc       "Compare the payload's :rf/schema-digest (server) against
the client's registered app-schema digest. A mismatch emits a structured
:rf.ssr/schema-digest-mismatch trace. Per Spec 011 §The :rf/hydrate event."
   :platforms #{:client}}
  hydrate/check-schema-digest-fx)

;; ---- the seven :rf.server/* response-shape fxs ----------------------------
;;
;; Per Spec 011 §HTTP response contract.

(fx/reg-fx :rf.server/set-status
  {:doc       "Set the HTTP response status. Last-write-wins. A second
write in the same drain emits :rf.warning/multiple-status-set per
[Spec 011 §Multiple-status policy]."
   :schema    server-fx-schemas/set-status-args
   :platforms #{:server}}
  response/set-status-fx)

(fx/reg-fx :rf.server/set-header
  {:doc       "Replace any existing header with the same name (case-
insensitive) and write [name value]. Per Spec 011 §Header replacement
vs append."
   :schema    server-fx-schemas/set-header-args
   :platforms #{:server}}
  response/set-header-fx)

(fx/reg-fx :rf.server/append-header
  {:doc       "Append [name value] to headers — preserves any existing
header with the same name. Required for Set-Cookie-style multi-valued
headers. Per Spec 011 §Header replacement vs append."
   :schema    server-fx-schemas/append-header-args
   :platforms #{:server}}
  response/append-header-fx)

(fx/reg-fx :rf.server/set-cookie
  {:doc       "Add a structured cookie to the :cookies vector. Cookie
attributes are stored as a structured map (RFC 6265 wire-form
serialisation is host-adapter business). Per Spec 011 §Cookie shape."
   :schema    server-fx-schemas/set-cookie-args
   :platforms #{:server}}
  response/set-cookie-fx)

(fx/reg-fx :rf.server/delete-cookie
  {:doc       "Sugar over :rf.server/set-cookie with :max-age 0 and an
empty :value. The host adapter materialises the delete-marker semantics
on the wire. Per Spec 011 §Cookie shape."
   :schema    server-fx-schemas/delete-cookie-args
   :platforms #{:server}}
  response/delete-cookie-fx)

(fx/reg-fx :rf.server/redirect
  {:doc       "Set :redirect on the response accumulator. Defaults
:status to 302 if absent. Multiple writes emit
:rf.warning/multiple-redirects (last-write-wins). Per Spec 011
§Redirect precedence.

Caller-trusted :location — accepts arbitrary URL strings without
allowlist or relative-only gating. For caller-untrusted location strings
(e.g. a `?next=` URL param), use :rf.server/safe-redirect (below).
The trusted path still rejects header-splitting characters."
   :schema    server-fx-schemas/redirect-args
   :platforms #{:server}}
  response/redirect-fx)

(fx/reg-fx :rf.server/safe-redirect
  {:doc       "Set :redirect after a five-step validation gate (per
Spec 011 §HTTP response contract §Standard fx). Mitigation for the
open-redirect class — an attacker-controlled `?next=...` URL parameter
cannot redirect off-origin when the app uses :rf.server/safe-redirect
instead of :rf.server/redirect.

Args:
  {:location       \"/dashboard\"
   :relative-only? true                                ;; reject hosts
   :allow          [\"app.example.com\" \"alt.example.com\"]
   :status         302}

Validation order: (1) URL must parse — :rf.error/safe-redirect-invalid-url;
(2) reject javascript:/data:/vbscript: schemes —
:rf.error/safe-redirect-scheme-rejected; (3) :relative-only? + host —
:rf.error/safe-redirect-host-disallowed (:reason :relative-only-violation);
(4) :allow allowlist mismatch — :rf.error/safe-redirect-host-disallowed
(:reason :not-in-allowlist); (5) pass — set Location header."
   :schema    server-fx-schemas/safe-redirect-args
   :platforms #{:server}}
  response/safe-redirect-fx)

;; ---- :rf.server/request cofx ----------------------------------------------
;;
;; Per Spec 011 §Server-only `reg-cofx` for request context.

(cofx/reg-cofx :rf.server/request
  {:doc       "The active host-supplied HTTP request. Server only.

This is an ambient, per-frame read. It is suitable for transient decisions
whose results do not enter app-db or runtime-db. Replay invokes the supplier
again, after the request slot may have been cleared, so durable state must not
depend directly on this value.

For a durable request-derived fact, the host sanitizes the request at the
boundary and provides only the derived value in the event payload or in a
provided, recordable coeffect leaf. Never record the whole request: it may
contain credentials, personal data, streams, and other host handles.

The host calls `set-request!` before draining the frame. Handlers declare
`{:rf.cofx/requires [:rf.server/request]}` and read the value at
`:rf.server/request`. Tests without a host adapter populate the same slot
explicitly."
   :platforms #{:server}}
  request/request-cofx)

;; ---- error-projector registry + trace-listener ----------------------------
;;
;; Per Spec 011 §Default projector + §Server error projection.

(reg-error-projector :rf.ssr/default-error-projector
                     {:doc "Built-in default projector. Spec 011 §Default projector mapping."}
                     default-error-projector-fn)

;; The always-on listener survives `interop/debug-enabled? = false`, so the
;; SSR `:rf/public-error` projection contract holds even when the trace
;; surface is gated off. It consumes `:rf.error/*` records GENERICALLY, so
;; its coverage is exactly the always-on axis's promoted set (Spec 009
;; §Channel-promotion catalogue): the event-centric
;; `error-emit/dispatch-on-error!` categories — `:rf.error/handler-exception`
;; (router), the `:rf.error/fx-handler-exception` family (fx),
;; `:rf.error/flow-eval-exception` (flows), `:rf.error/sub-exception`
;; (reactive sub-run) and `:rf.error/no-such-handler` `:kind :event`
;; (router.diagnostics) — plus the NON-EVENT union records fanned through
;; `dispatch-error-record!`: `:rf.error/no-such-handler` `:kind :route`
;; (rf2-ov56u — routing's URL-driven miss, the one this projector maps to
;; 404), `:rf.error/drain-depth-exceeded` (rf2-fcbrjo) and the promoted SSR
;; categories. A sub that throws mid-render projects a fail-closed 5xx under
;; production hardening instead of recovering to nil and producing an HTTP
;; 200; an unroutable URL projects 404 instead of a soft-404 200.
(rf-emit/register-error-listener! ::error-projection
                                  error-listener/error-emit-projection-listener)

;; The development trace listener covers the same categories on the DEV
;; trace bus, plus the ones that ride it ALONE and so DCE under
;; `interop/debug-enabled? = false`: `:rf.error/no-such-route` (the
;; `route-url` caller-misuse throw, catalogued diagnostic) and
;; `:rf.error/schema-validation-failure` (boundary validation is itself
;; production-elided per Spec 010 §Production builds, so there is no
;; production reject to project). Categories on BOTH axes —
;; `:rf.error/sub-exception`, `:rf.error/no-such-handler`,
;; `:rf.error/drain-depth-exceeded` — buffer twice in dev; the always-on
;; axis is their production status source of truth. Both listeners share id
;; `::error-projection` to keep the contract surface addressable as one
;; logical projector — `apply-error-projection!` 1-arity is
;; last-write-wins, so the duplicate buffer entry under dev is benign.
(trace-tooling/register-listener! ::error-projection
                                  error-listener/error-projection-listener)

;; ---- late-bind hook registration ------------------------------------------
;;
;; Core's `render-to-string`, `render-tree-hash`,
;; `reg-error-projector`, and `project-error` re-exports look the
;; producing fns up through this hook table — core never statically
;; `:require`s `re-frame.ssr`. When the ssr artefact is not on the
;; classpath the lookups return nil and the consumer raises
;; `:rf.error/ssr-artefact-missing`.

(late-bind/set-fn! :ssr/render-tree-hash    render-tree-hash)
(late-bind/set-fn! :ssr/render-to-string    render-to-string)
(late-bind/set-fn! :ssr/reg-error-projector reg-error-projector)
(late-bind/set-fn! :ssr/project-error       project-error)
;; Frame teardown looks up this hook and clears the SSR side-channel atoms
;; (`pending-error-traces`, `request-slots`, `response-slots`) for the
;; destroyed frame.
(late-bind/set-fn! :ssr/on-frame-destroyed  on-frame-destroyed!)

;; `re-frame.ssr.head` is required above so its late-bind hooks (`:ssr/reg-head`, `:ssr/render-head`,
;; `:ssr/active-head`, `:ssr/head-snapshot`, `:ssr/head-model-html`) AND
;; the per-frame head-snapshot cleanup hook
;; (`:ssr.head/on-frame-destroyed`) land at ssr-ns load time on both JVM
;; and CLJS. `on-frame-destroyed!` above invokes the head cleanup hook
;; by key — load order between this ns and head.cljc is symmetric.
