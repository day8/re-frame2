(ns re-frame.ssr
  "Server-side rendering and hydration. Per Spec 011.

  Public façade for the day8/re-frame2-ssr artefact. Per Spec 006
  §Adapter shipping convention — this artefact depends on core; core
  never depends on this artefact. Cross-references from core to ssr
  flow through `re-frame.late-bind`.

  Façade over eight flat sub-namespaces (`emit`, `hash`, `substrate`,
  `hydrate`, `response`, `error-projector`, `error-listener`,
  `request`). All `reg-fx` / `reg-cofx` / `reg-event` /
  `reg-error-projector` / `register-error-listener!` /
  `register-listener!` side-effects fire HERE so
  `(require 're-frame.ssr :reload)` after `(registrar/clear-all!)`
  re-installs every registration. Sub-namespaces export pure handler
  fns only.

  Implements:
    - Pure hiccup → HTML emitter (HTML5 void elements, doctype prefix,
      attr/text escaping, `:tag#id.cls` parsing, registered-view
      resolution). Per Spec 011 §The render-tree → HTML emitter.
    - `:rf/hydrate` event with `:replace-frame-state` semantics; the server-
      supplied payload's `:rf/app-db` replaces the client frame-state
      (app-db + serializable runtime-db).
    - The seven `:rf.server/*` response-shape fxs gated by `:platforms
      #{:server}`; the accumulator at `[:rf/response]` is consumed by
      the host adapter after drain. Per §HTTP response contract.
    - `reg-error-projector` + default `:rf.ssr/default-error-projector`,
      plus the SSR error path that calls the active projector when an
      error trace fires inside a server frame. Per §Server error
      projection.
    - `:rf.server/request` cofx + per-frame request slot
      (`set-request!` / `get-request` / `clear-request!`). Per
      §Server-only `reg-cofx` for request context.

  Conformance fixtures cover all of the above (ssr/render-to-string,
  ssr/hydrate, ssr/hydration-mismatch, ssr/head-emits, ssr/head-hydration,
  ssr/error-known-mapping, ssr/error-sanitisation, ssr/cookie,
  ssr/redirect, ssr/set-status, fx/platforms)."
  (:require [re-frame.cofx :as cofx]
            [re-frame.emit :as rf-emit]
            [re-frame.events :as events]
            [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            ;; rf2-lq2ou — client-side hydration boot helper. The
            ;; symmetric counterpart of the server-side
            ;; `re-frame.ssr.ring/ssr-handler`: reads `__rf_payload` →
            ;; dispatches `:rf/hydrate` → verifies. Loaded eagerly so the
            ;; `ssr/hydrate!` / `ssr/read-server-payload` re-exports
            ;; resolve at the façade. Per Spec 011 §Client-side hydration
            ;; boot helper.
            [re-frame.ssr.boot :as boot]
            [re-frame.ssr.emit :as emit]
            [re-frame.ssr.error-listener :as error-listener]
            [re-frame.ssr.error-projector :as error-projector]
            ;; rf2-4dra9 — head/meta contract surface (Spec 011 §Head/meta).
            ;; The head ns publishes its late-bind hooks at ns-load time;
            ;; static `:require` here ensures the hooks are available
            ;; before any user code calls `rf/reg-head` / `rf/render-head`
            ;; / `rf/active-head`. The dependency is acyclic — head.cljc
            ;; only pulls re-frame.frame / re-frame.registrar.
            re-frame.ssr.head
            [re-frame.ssr.hash :as hash]
            [re-frame.ssr.hydrate :as hydrate]
            [re-frame.ssr.request :as request]
            [re-frame.ssr.response :as response]
            ;; rf2-kjf3m.2 — the standard `:rf.fx.server/*-args` /
            ;; `:rf.server/cookie` Malli args schemas Spec 011 §Standard fx
            ;; (line 438) + [Spec-Schemas §Standard fx args schemas] name as
            ;; registered. Attached as the `:schema` key on the seven
            ;; `:rf.server/*` reg-fx calls below so the Spec 010 §step-5
            ;; fx-args `:schema` boundary check actually fires on the server
            ;; fx args (it previously did not — the calls carried only :doc
            ;; + :platforms).
            [re-frame.ssr.server-fx-schemas :as server-fx-schemas]
            [re-frame.ssr.substrate :as substrate]
            ;; rf2-ojakd / rf2-olb64 (a) — streaming SSR primitive
            ;; (:rf/suspense-boundary). Loaded eagerly so the three
            ;; late-bind hooks (:ssr.streaming/render-shell!,
            ;; :ssr.streaming/render-continuation!,
            ;; :ssr.streaming/build-final-payload) land at ns-load time
            ;; before any host adapter calls them.
            re-frame.ssr.streaming
            ;; rf2-3hhv5 — CLIENT-side streaming-SSR runtime
            ;; (`install!`). CLJS-only (it installs a MutationObserver +
            ;; swaps DOM); re-exported as `ssr/streaming-install!` so a
            ;; streaming-aware bootstrap calls one façade fn. NOT eagerly
            ;; needed server-side; required CLJS-only so a JVM lint of
            ;; this `.cljc` does not pull a `.cljs`-only ns.
            #?(:cljs [re-frame.ssr.streaming.client :as streaming-client])
            ;; Per rf2-qwm0a SSR's error-projection trace listener
            ;; targets the tooling sibling directly. The framework's
            ;; `re-frame.trace` no longer re-exports the listener
            ;; surface (production-DCE split).
            [re-frame.trace.tooling :as trace-tooling]))

;; ---- public-surface re-exports --------------------------------------------
;;
;; `def`s expose the sub-namespace fns as `re-frame.ssr/<name>` so
;; consumers see the same surface they did pre-split.

(def render-to-string                emit/render-to-string)
(def install-render-to-string!       emit/install-render-to-string!)
(def ^:private format-view-source-coord emit/format-view-source-coord)
(def render-tree-hash                hash/render-tree-hash)
;; framework-private: tests reach into `#'ssr/canonical-edn` for the
;; JVM↔CLJS canonical-EDN parity check (hash_check_cljs_test).
(def ^:private canonical-edn         hash/canonical-edn)
(def ^:private fnv-1a-32             hash/fnv-1a-32)
(def adapter                         substrate/adapter)
(def verify-hydration!               hydrate/verify-hydration!)
;; rf2-lq2ou — client-side hydration boot helper (symmetric with the
;; server-side `re-frame.ssr.ring/ssr-handler`). `hydrate!` fuses the
;; read → dispatch `:rf/hydrate` → `verify-hydration!` ordering Spec 011
;; §Client flow mandates; `read-server-payload` (CLJS-only) is the DOM
;; `__rf_payload` reader it builds on.
(def hydrate!                        boot/hydrate!)
#?(:cljs (def read-server-payload    boot/read-server-payload))
(def default-response                response/default-response)
;; framework-private — Spec 011 §Response storage substrate (rf2-jbcmt).
;; The accumulator lives in a side-channel atom keyed by frame-id, NOT
;; in app-db, so it cannot ride the hydration payload to the client and
;; per-fx writes are O(small-map) rather than a full app-db replacement.
;; Tests reach the var via `(resolve 're-frame.ssr/response-slots)`.
(def ^:private response-slots        response/response-slots)
(def public-error-keys               error-projector/public-error-keys)
(def fallback-public-error           error-projector/fallback-public-error)
(def default-error-projector-fn      error-projector/default-error-projector-fn)
(def reg-error-projector             error-projector/reg-error-projector)
(def project-error                   error-projector/project-error)
(def apply-error-projection!         error-listener/apply-error-projection!)
;; rf2-zwgsv — Spec 011 §Server error projection unification. Host
;; adapters wrap their render pipeline call (`render-to-string`) in a
;; try/catch and route render-time throws through this fn so the
;; projector's status + body materialise on the wire — symmetric with
;; the drain-time fx/handler-exception path that already flows through
;; the trace-listener buffer.
(def project-render-exception!       error-listener/project-render-exception!)
(def get-response                    error-listener/get-response)
;; Audit rf2-asmj1 P5 / cluster rf2-sljs1 — split the side-effect from
;; the pure read. `peek-response` is a pure read (no projector drain);
;; `flush-response!` drains pending error projections then reads.
;; `get-response` is kept as the drain-then-read host-adapter alias.
(def peek-response                   error-listener/peek-response)
(def flush-response!                 error-listener/flush-response!)
;; framework-private at the public surface — Spec 011 §Per-request
;; frame teardown. Tests reach the var via `(resolve ...)`.
(def ^:private pending-error-traces  error-listener/pending-error-traces)
;; rf2-i3qc0 — private at the façade so the public surface stays symmetric
;; with `response-slots` and `pending-error-traces`. Tests reach via
;; `re-frame.ssr.test-fixture/reset-runtime` (the canonical reset surface)
;; or by `:require`-ing `re-frame.ssr.request` directly. Production
;; consumers go through `set-request!` / `get-request` / `clear-request!`.
(def ^:private request-slots         request/request-slots)
(def set-request!                    request/set-request!)
(def get-request                     request/get-request)
(def clear-request!                  request/clear-request!)
(def on-frame-destroyed!             request/on-frame-destroyed!)

;; ---- streaming SSR public surface (rf2-ojakd / rf2-olb64 (a)) -------------
;;
;; Per Spec 011 §Streaming SSR — the `:rf/suspense-boundary` hiccup marker
;; ships through these three façade fns. Host adapters (ssr-ring/streaming)
;; consume them via the late-bind hooks the streaming ns also publishes.
(def streaming-render-shell         re-frame.ssr.streaming/render-shell)
(def streaming-render-continuation  re-frame.ssr.streaming/render-continuation)
(def streaming-build-final-payload  re-frame.ssr.streaming/build-final-payload)
(def streaming-fallback-template    re-frame.ssr.streaming/fallback-template)
(def streaming-resolved-template    re-frame.ssr.streaming/resolved-template)
(def streaming-failed-template      re-frame.ssr.streaming/failed-template)
(def streaming-hydrate-delta-script re-frame.ssr.streaming/hydrate-delta-script)
;; rf2-3hhv5 — CLIENT-side streaming runtime. CLJS-only (DOM consumer):
;; `(ssr/streaming-install! {:frame …})` installs the MutationObserver
;; that swaps `<template>` fallbacks for resolved subtrees + merges the
;; per-subtree hydration deltas as chunks stream in, reconciling against
;; the final `__rf_payload` `:rf/hydrate` (which `ssr/hydrate!` applies).
;; Host opt-in — a streaming-aware bootstrap calls it; non-streaming
;; pages skip it.
#?(:cljs (def streaming-install!    streaming-client/install!))

;; ---- SSR blocking-resource drain (Spec 016 §SSR and hydration steps 3-4) --
;;
;; rf2-er7qx2 — the resources artefact (optional) defines SSR blocking
;; resources as SETTLED before render or TIMED OUT into a settled first-load
;; failure. The drain LOOP + timeout POLICY live in the resources artefact
;; (`re-frame.resources.ssr/drain-blocking-resources!`, published as the
;; `:resources/drain-blocking-ssr!` late-bind hook); the SSR render path (Ring
;; / streaming host adapters) consults it BEFORE rendering so the render walk
;; never sees a hung `:loading` / skeleton an in-flight (or never-settling)
;; blocking resource would otherwise leave. Late-bound BOTH ways — an SSR app
;; without resources carries none of this code path (the hook is nil → no-op);
;; a resources app that never SSRs never reaches it.

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
     {:settled? true :timed-out #{} :route-blocking-failure nil})))

;; ---- :rf/hydrate event + :rf.ssr/check-* fxs ------------------------------
;;
;; Spec 011 §The :rf/hydrate event + §Hydration-mismatch detection +
;; rf2-69ad2 compatibility checks.

;; EP-0001 (rf2-3939ig): ssr is one of the legitimate runtime-db writers
;; Spec 002 §Write authority names. The `:rf/hydrate` handler installs the
;; hydration metadata into the reserved `[:rf.runtime/ssr :hydration]` slot
;; by returning a `:rf.db/runtime` effect, so it mints framework-write
;; authority via the general `:rf/framework-authority?` registration-meta key
;; (Conventions §Reserved registration meta) — without it, every hydrate
;; dispatch would trip the `:rf.warning/app-handler-runtime-effect`
;; ownership diagnostic in dev (reserved BY CONVENTION, Mike ruling #4).
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
   :schema    server-fx-schemas/set-status-args   ;; :rf.fx.server/set-status-args (rf2-kjf3m.2)
   :platforms #{:server}}
  response/set-status-fx)

(fx/reg-fx :rf.server/set-header
  {:doc       "Replace any existing header with the same name (case-
insensitive) and write [name value]. Per Spec 011 §Header replacement
vs append."
   :schema    server-fx-schemas/set-header-args   ;; :rf.fx.server/set-header-args (rf2-kjf3m.2)
   :platforms #{:server}}
  response/set-header-fx)

(fx/reg-fx :rf.server/append-header
  {:doc       "Append [name value] to headers — preserves any existing
header with the same name. Required for Set-Cookie-style multi-valued
headers. Per Spec 011 §Header replacement vs append."
   :schema    server-fx-schemas/append-header-args ;; :rf.fx.server/append-header-args (rf2-kjf3m.2)
   :platforms #{:server}}
  response/append-header-fx)

(fx/reg-fx :rf.server/set-cookie
  {:doc       "Add a structured cookie to the :cookies vector. Cookie
attributes are stored as a structured map (RFC 6265 wire-form
serialisation is host-adapter business). Per Spec 011 §Cookie shape."
   :schema    server-fx-schemas/set-cookie-args   ;; :rf.fx.server/set-cookie-args = [:ref :rf.server/cookie] (rf2-kjf3m.2)
   :platforms #{:server}}
  response/set-cookie-fx)

(fx/reg-fx :rf.server/delete-cookie
  {:doc       "Sugar over :rf.server/set-cookie with :max-age 0 and an
empty :value. The host adapter materialises the delete-marker semantics
on the wire. Per Spec 011 §Cookie shape."
   :schema    server-fx-schemas/delete-cookie-args ;; :rf.fx.server/delete-cookie-args (rf2-kjf3m.2)
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
Per rf2-zfm8v."
   :schema    server-fx-schemas/redirect-args     ;; :rf.fx.server/redirect-args (rf2-kjf3m.2)
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
(:reason :not-in-allowlist); (5) pass — set Location header. Per
rf2-zfm8v (Mike decision, Option A, 2026-05-14)."
   :schema    server-fx-schemas/safe-redirect-args ;; :rf.fx.server/safe-redirect-args (rf2-wtd8z finding 1)
   :platforms #{:server}}
  response/safe-redirect-fx)

;; ---- :rf.server/request cofx ----------------------------------------------
;;
;; Per Spec 011 §Server-only `reg-cofx` for request context.

(cofx/reg-cofx :rf.server/request
  {:doc       "The active HTTP request. Server only. AMBIENT value-returning
coeffect (EP-0017 §2): surfaces the host-supplied request map (Ring shape
under rf2-ny6v7's Ring adapter; host-defined for other adapters) so handlers
can read URL, headers, session cookies, etc. without threading the request as
an event arg.

GRADE — AMBIENT, host-transient read. This cofx is for NON-DURABLE request
reads: branching on `:request-method`, reading a header for a decision that
does NOT fold into durable app-db / runtime-db. EP-0017 §1: the ambient grade
is legal only where NO durable write depends on the value — its supplier
re-runs on replay, so it never re-presents the value a recorded run actually
saw (and after per-request frame teardown it reads nil). A handler that folds a
request-derived fact into the hydration payload (durable app-db) through THIS
cofx is the SSR analogue of the ambient-localStorage replay hole (rf2-aqwvhh /
the rf2-16ck78 family).

DURABLE request-derived facts — the boundary pattern (rf2-aqwvhh). When a setup
handler writes durable state from the request (auth user / session state read
from a cookie, an `accept-language` folded into a locale slice, …), the host
adapter SANITIZES the request at the boundary and supplies the derived fact as
a recordable leaf, so it lands on the causal token and replay re-presents it
verbatim. Two slice-A-legal shapes, both replayable:

  - EVENT PAYLOAD — the host dispatches the setup event WITH the derived fact:
    `[:auth/server-init {:user (extract-user request)}]`. The fact rides
    `:event`, recorded as part of the dispatch.
  - PROVIDED recordable `:rf.cofx` LEAF — register an app-owned
    `{:recordable? true :provided? true}` cofx (e.g. `:auth.session/user`) and
    the host adapter STAMPS the sanitized value onto the boot dispatch token
    (`{:rf.cofx {:auth.session/user …}}`). The handler declares
    `:rf.cofx/requires [:auth.session/user]`; a record missing it fails LOUDLY
    with `:rf.error/missing-required-cofx` rather than silently re-reading the
    host. NEVER stamp the whole request map onto the token — it carries
    secrets / PII and is a host handle (Spec 011 §Request storage substrate);
    stamp only the sanitized derived projection.

The host adapter populates the per-frame slot via `re-frame.ssr/set-request!`
once per request before the drain begins. A server-side event handler consumes
the AMBIENT read by declaring `{:rf.cofx/requires [:rf.server/request]}` on its
registration and reading the value FLAT under `:rf.server/request`. The supplier
runs at context assembly, reading the active frame's slot; it is never recorded,
and replay re-runs it.

Tests and conformance harnesses that drive the drain without a host adapter
`re-frame.ssr/set-request!` the target frame's slot before dispatching (the
visible seam — there is no `inject-cofx` value override anymore: `inject-cofx`
is removed in EP-0017).

Per Spec 011 §Server-only `reg-cofx` for request context + §Durable
request-derived facts."
   :platforms #{:server}}
  request/request-cofx)

;; ---- error-projector registry + trace-listener ----------------------------
;;
;; Per Spec 011 §Default projector + §Server error projection.

(reg-error-projector :rf.ssr/default-error-projector
                     {:doc "Built-in default projector. Spec 011 §Default projector mapping."}
                     default-error-projector-fn)

;; rf2-fb598 — primary install site: the always-on error-emit substrate.
;; Per Spec 011 §Server error projection / Spec 009 §What IS available in
;; production §Error-emit listener, this listener survives
;; `interop/debug-enabled? = false` (production hardening per rf2-vnjfg)
;; so the SSR `:rf/public-error` projection contract holds even when the
;; trace surface is gated off. Catches every `:rf.error/*` category that
;; flows through `re-frame.error-emit/dispatch-on-error!` —
;; `:rf.error/handler-exception` (router), `:rf.error/fx-handler-
;; exception` family (fx), `:rf.error/flow-eval-exception` (flows), and
;; `:rf.error/sub-exception` (reactive sub-run — rf2-vvwmi routed it onto
;; the always-on substrate so a sub that throws mid-`render-to-string`
;; projects a fail-closed 5xx under production hardening instead of
;; recovering to nil → silent HTTP 200).
(rf-emit/register-error-listener! ::error-projection
                                  error-listener/error-emit-projection-listener)

;; rf2-fb598 — secondary install site: the dev-only trace surface,
;; preserved for `:rf.error/*` categories that emit ONLY via
;; `trace/emit-error!` (no always-on emission path): `:rf.error/no-such-
;; handler`, `:rf.error/no-such-route`, `:rf.error/schema-validation-
;; failure`, `:rf.error/drain-depth-exceeded`. (`:rf.error/sub-exception`
;; ALSO fires here for the dev trace surface, but its production status
;; source of truth is now the always-on substrate above — rf2-vvwmi.)
;; These categories DCE under `interop/debug-enabled? = false`; the
;; substrate-shipping projector relies on the dev gate for them. Both
;; listeners share id `::error-projection` to keep the contract surface
;; addressable as one logical projector — `apply-error-projection!`
;; 1-arity is last-write-wins, so the duplicate buffer entry under dev
;; is benign.
(trace-tooling/register-listener! ::error-projection
                                  error-listener/error-projection-listener)

;; ---- late-bind hook registration ------------------------------------------
;;
;; Per rf2-uo7v re-frame.ssr ships in `day8/re-frame2-ssr`. The core
;; artefact's `re-frame.core/render-to-string`, `render-tree-hash`,
;; `reg-error-projector`, and `project-error` re-exports look the
;; producing fns up through this hook table — core never statically
;; `:require`s `re-frame.ssr`. When the ssr artefact is not on the
;; classpath the lookups return nil and the consumer raises
;; `:rf.error/ssr-artefact-missing`.

(late-bind/set-fn! :ssr/render-tree-hash    render-tree-hash)
(late-bind/set-fn! :ssr/render-to-string    render-to-string)
(late-bind/set-fn! :ssr/reg-error-projector reg-error-projector)
(late-bind/set-fn! :ssr/project-error       project-error)
;; rf2-fcj33 + rf2-jbcmt — per-request frame teardown. `frame/destroy-frame!`
;; looks up this hook and clears the SSR side-channel atoms
;; (`pending-error-traces`, `request-slots`, `response-slots`) for the
;; destroyed frame. `response-slots` joined the set under rf2-jbcmt when
;; the `:rf/response` accumulator moved off `app-db` to plug the hydration-
;; payload leak / per-fx full-app-db swap.
(late-bind/set-fn! :ssr/on-frame-destroyed  on-frame-destroyed!)

;; rf2-4dra9 — `re-frame.ssr.head` is required from the top-of-file ns
;; form so its late-bind hooks (`:ssr/reg-head`, `:ssr/render-head`,
;; `:ssr/active-head`, `:ssr/head-snapshot`, `:ssr/head-model-html`) AND
;; the per-frame head-snapshot cleanup hook
;; (`:ssr.head/on-frame-destroyed`) land at ssr-ns load time on both JVM
;; and CLJS. `on-frame-destroyed!` above invokes the head cleanup hook
;; by key — load order between this ns and head.cljc is symmetric.
