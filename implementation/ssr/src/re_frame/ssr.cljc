(ns re-frame.ssr
  "Server-side rendering and hydration. Per Spec 011.

  Public façade for the day8/re-frame2-ssr artefact. Per Spec 006
  §Adapter shipping convention — this artefact depends on core; core
  never depends on this artefact. Cross-references from core to ssr
  flow through `re-frame.late-bind`.

  Façade over eight flat sub-namespaces (`emit`, `hash`, `substrate`,
  `hydrate`, `response`, `error-projector`, `error-listener`,
  `request`). All `reg-fx` / `reg-cofx` / `reg-event-fx` /
  `reg-error-projector` / `register-trace-cb!` side-effects fire HERE
  so `(require 're-frame.ssr :reload)` after `(registrar/clear-all!)`
  re-installs every registration. Sub-namespaces export pure handler
  fns only.

  Implements:
    - Pure hiccup → HTML emitter (HTML5 void elements, doctype prefix,
      attr/text escaping, `:tag#id.cls` parsing, registered-view
      resolution). Per Spec 011 §The render-tree → HTML emitter.
    - `:rf/hydrate` event with `:replace-app-db` semantics; the server-
      supplied payload's `:rf/app-db` replaces the client app-db.
    - The six `:rf.server/*` response-shape fxs gated by `:platforms
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
            [re-frame.events :as events]
            [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
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
            [re-frame.ssr.substrate :as substrate]
            ;; rf2-ojakd / rf2-olb64 (a) — streaming SSR primitive
            ;; (:rf/suspense-boundary). Loaded eagerly so the three
            ;; late-bind hooks (:ssr.streaming/render-shell!,
            ;; :ssr.streaming/render-continuation!,
            ;; :ssr.streaming/build-final-payload) land at ns-load time
            ;; before any host adapter calls them.
            re-frame.ssr.streaming
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

;; ---- :rf/hydrate event + :rf.ssr/check-* fxs ------------------------------
;;
;; Spec 011 §The :rf/hydrate event + §Hydration-mismatch detection +
;; rf2-69ad2 compatibility checks.

(events/reg-event-fx :rf/hydrate hydrate/hydrate-event-handler)

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

;; ---- the six :rf.server/* response-shape fxs ------------------------------
;;
;; Per Spec 011 §HTTP response contract.

(fx/reg-fx :rf.server/set-status
  {:doc       "Set the HTTP response status. Last-write-wins. A second
write in the same drain emits :rf.warning/multiple-status-set per
[Spec 011 §Multiple-status policy]."
   :platforms #{:server}}
  response/set-status-fx)

(fx/reg-fx :rf.server/set-header
  {:doc       "Replace any existing header with the same name (case-
insensitive) and write [name value]. Per Spec 011 §Header replacement
vs append."
   :platforms #{:server}}
  response/set-header-fx)

(fx/reg-fx :rf.server/append-header
  {:doc       "Append [name value] to headers — preserves any existing
header with the same name. Required for Set-Cookie-style multi-valued
headers. Per Spec 011 §Header replacement vs append."
   :platforms #{:server}}
  response/append-header-fx)

(fx/reg-fx :rf.server/set-cookie
  {:doc       "Add a structured cookie to the :cookies vector. Cookie
attributes are stored as a structured map (RFC 6265 wire-form
serialisation is host-adapter business). Per Spec 011 §Cookie shape."
   :platforms #{:server}}
  response/set-cookie-fx)

(fx/reg-fx :rf.server/delete-cookie
  {:doc       "Sugar over :rf.server/set-cookie with :max-age 0 and an
empty :value. The host adapter materialises the delete-marker semantics
on the wire. Per Spec 011 §Cookie shape."
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
   :platforms #{:server}}
  response/safe-redirect-fx)

;; ---- :rf.server/request cofx ----------------------------------------------
;;
;; Per Spec 011 §Server-only `reg-cofx` for request context.

(cofx/reg-cofx :rf.server/request
  {:doc       "The active HTTP request. Server only. Surfaces the
host-supplied request map (Ring shape under rf2-ny6v7's Ring adapter;
host-defined for other adapters) so handlers can read URL, headers,
session cookies, etc. without threading the request as an event arg.

The host adapter populates the slot via `re-frame.ssr/set-request!`
once per request before the drain begins. Apps consume via
`(inject-cofx :rf.server/request)` in any server-side event handler.

The 2-arity form accepts an explicit value override — useful in tests
and conformance harnesses that drive the drain without a host adapter:
`(inject-cofx :rf.server/request {:uri \"/articles\" ...})`.

Per Spec 011 §Server-only `reg-cofx` for request context."
   :platforms #{:server}}
  request/request-cofx)

;; ---- error-projector registry + trace-listener ----------------------------
;;
;; Per Spec 011 §Default projector + §Server error projection.

(reg-error-projector :rf.ssr/default-error-projector
                     {:doc "Built-in default projector. Spec 011 §Default projector mapping."}
                     default-error-projector-fn)

(trace-tooling/register-trace-cb! ::error-projection
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
