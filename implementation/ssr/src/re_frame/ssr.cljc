(ns re-frame.ssr
  "Server-side rendering and hydration. Per Spec 011.

  Public façade for the day8/re-frame2-ssr artefact. This artefact
  depends on core; core never depends on it — cross-references from
  core flow through `re-frame.late-bind`.

  Thin façade over eight flat sub-namespaces:

    - `re-frame.ssr.emit`            — hiccup → HTML emitter,
                                       `render-to-string`, source-coord
                                       annotation, substrate wire-up.
    - `re-frame.ssr.hash`            — `canonical-edn`, `fnv-1a-32`,
                                       `render-tree-hash`.
    - `re-frame.ssr.adapter`         — the SSR adapter map + helpers.
    - `re-frame.ssr.hydrate`         — `:rf/hydrate` handler, the two
                                       `:rf.ssr/check-*` fx handlers,
                                       `verify-hydration!`.
    - `re-frame.ssr.response`        — response accumulator + handlers
                                       for the six `:rf.server/*` fxs.
    - `re-frame.ssr.error-projector` — projector registry + default +
                                       `project-error`.
    - `re-frame.ssr.error-listener`  — trace listener + per-frame buffer
                                       + drain + `get-response`.
    - `re-frame.ssr.request`         — `request-slots`, set/get/clear
                                       helpers, `on-frame-destroyed!`,
                                       the `:rf.server/request` cofx.

  All `reg-fx` / `reg-cofx` / `reg-event-fx` / `reg-error-projector` /
  `register-trace-cb!` side-effects fire HERE so a
  `(require 're-frame.ssr :reload)` after `(registrar/clear-all!)`
  re-installs every registration. Sub-namespaces export pure handler
  fns only."
  (:require [re-frame.cofx :as cofx]
            [re-frame.events :as events]
            [re-frame.fx :as fx]
            [re-frame.late-bind :as late-bind]
            [re-frame.ssr.adapter :as adapter-ns]
            [re-frame.ssr.emit :as emit]
            [re-frame.ssr.error-listener :as error-listener]
            [re-frame.ssr.error-projector :as error-projector]
            ;; Static `:require` of `re-frame.ssr.head` is load-bearing
            ;; — head publishes its late-bind hooks at ns-load time, so
            ;; this require ensures the hooks are available before any
            ;; user code calls `rf/reg-head` / `rf/render-head` /
            ;; `rf/active-head`.
            re-frame.ssr.head
            [re-frame.ssr.hash :as hash]
            [re-frame.ssr.hydrate :as hydrate]
            [re-frame.ssr.request :as request]
            [re-frame.ssr.response :as response]
            [re-frame.trace :as trace]))

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
(def adapter                         adapter-ns/adapter)
(def verify-hydration!               hydrate/verify-hydration!)
(def default-response                response/default-response)
;; framework-private — Spec 011 §Response storage substrate. The
;; accumulator lives in a side-channel atom keyed by frame-id, NOT in
;; app-db, so it cannot ride the hydration payload to the client and
;; per-fx writes are O(small-map) rather than full app-db replacement.
(def ^:private response-slots        response/response-slots)
(def public-error-keys               error-projector/public-error-keys)
(def fallback-public-error           error-projector/fallback-public-error)
(def default-error-projector-fn      error-projector/default-error-projector-fn)
(def reg-error-projector             error-projector/reg-error-projector)
(def project-error                   error-projector/project-error)
(def apply-error-projection!         error-listener/apply-error-projection!)
(def get-response                    error-listener/get-response)
;; `peek-response` is pure (no projector drain); `flush-response!`
;; drains pending error projections then reads. `get-response` is the
;; drain-then-read host-adapter alias.
(def peek-response                   error-listener/peek-response)
(def flush-response!                 error-listener/flush-response!)
;; framework-private — Spec 011 §Per-request frame teardown.
(def ^:private pending-error-traces  error-listener/pending-error-traces)
;; framework-private at the public surface — production consumers go
;; through `set-request!` / `get-request` / `clear-request!`.
(def ^:private request-slots         request/request-slots)
(def set-request!                    request/set-request!)
(def get-request                     request/get-request)
(def clear-request!                  request/clear-request!)
(def on-frame-destroyed!             request/on-frame-destroyed!)

;; ---- :rf/hydrate event + :rf.ssr/check-* fxs ------------------------------
;; Spec 011 §The :rf/hydrate event + §Hydration-mismatch detection.

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
§Redirect precedence."
   :platforms #{:server}}
  response/redirect-fx)

;; ---- :rf.server/request cofx ----------------------------------------------
;;
;; Per Spec 011 §Server-only `reg-cofx` for request context.

(cofx/reg-cofx :rf.server/request
  {:doc       "The active HTTP request. Server only. Surfaces the
host-supplied request map (Ring shape under the Ring adapter; host-
defined for other adapters) so handlers can read URL, headers,
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

(trace/register-trace-cb! ::error-projection
                          error-listener/error-projection-listener)

;; ---- late-bind hook registration ------------------------------------------

(late-bind/set-fn! :ssr/render-tree-hash    render-tree-hash)
(late-bind/set-fn! :ssr/render-to-string    render-to-string)
(late-bind/set-fn! :ssr/reg-error-projector reg-error-projector)
(late-bind/set-fn! :ssr/project-error       project-error)
;; Per-request frame teardown. `frame/destroy-frame!` looks up this
;; hook and clears the SSR side-channel atoms (`pending-error-traces`,
;; `request-slots`, `response-slots`) for the destroyed frame.
(late-bind/set-fn! :ssr/on-frame-destroyed  on-frame-destroyed!)
