(ns realworld-resources.http
  "This app's HTTP surface for the RealWorld-on-resources example.

   Because resources and mutations both lower onto `:rf.http/managed` — the one
   built-in transport for either (../../../docs/resources/glossary.md#managed-http)
   — a single override can stand in for the entire API, reads and writes alike.

   What lives here:
   - `api-base` / `full-url` — the app's API base URL (a knob you never turn in
     the default run mode; the demo backend never contacts it).
   - the transport-neutral Conduit contract helpers (query encoding, retry data,
     failure projection, pagination arithmetic), re-exposed from
     `realworld-shared.http` so the resource / mutation / view nses keep a single
     `rh/*` import point.
   - the demo-backend fx, `:realworld-resources.demo/http-stub`, a thin seam that
     wires the SHARED in-process Conduit demo backend
     (`realworld-shared.demo-backend`) under this app's fx-id. `core.cljs`
     references it from the demo frame's `:fx-overrides` — there is no explicit
     install call to make.

   Three ways to point the app at a backend (see the README §Running against a
   real backend):
   - canned demo backend (the default) — the frame overrides `:rf.http/managed`
     with the shared in-process backend, so the app runs with no network at all
     and `api-base` is never actually contacted (it matches on the path suffix).
   - the official hosted API — point `api-base` at `https://api.realworld.show/api`
     and drop the demo override. The frame-wide `:realworld/bearer-auth`
     interceptor (core.cljs) already attaches the token, so authenticated calls
     just work against the real API.
   - a local reference backend — the upstream Node/Postgres backend on
     `http://localhost:3000/api`.

   For reference: the RealWorld spec lives at
   https://github.com/gothinkster/realworld, the current official hosted API is
   https://api.realworld.show/api, and the upstream spec ships a Node/Postgres
   reference backend on http://localhost:3000/api."
  (:require [re-frame.core :as rf]
            ;; The transport-neutral Conduit contract — query encoding, the read
            ;; retry policy, the failure taxonomy, and pagination arithmetic —
            ;; shared with the managed-HTTP example and re-exposed below as this
            ;; app's HTTP surface.
            [realworld-shared.http :as wh]
            ;; The in-process Conduit demo backend shared by both RealWorld
            ;; examples — wired under an app-local fx-id at the bottom.
            [realworld-shared.demo-backend :as demo]))

(def api-base
  "Default API base URL — the current official hosted Conduit API. The resource
   and mutation `:request` fns build full URLs from this. In the default run mode
   it's effectively a knob you never turn: the demo stub overrides
   `:rf.http/managed` and matches on the path suffix, so the base is never
   contacted. Drop the stub to run against the real hosted API, or set this to
   `http://localhost:3000/api` for the local upstream reference backend. See the
   README §Running against a real backend."
  "https://api.realworld.show/api")

(defn full-url [path]
  (str api-base path))

;; ============================================================================
;; SHARED CONTRACT — re-exposed as this app's HTTP surface
;; ============================================================================
;;
;; The definitions live once, in realworld-shared.http; here they're the app's
;; public helpers so the resource / mutation / view nses keep a single `rh/*`
;; import point.
;;
;;   query-string      — Conduit query encoding (nils dropped, values encoded)
;;   data-fetch-retry  — reads retry (transport / 5xx / timeout, never 4xx);
;;                       writes get none. A read `:request` drops this policy
;;                       into its managed-HTTP args and `:retry` rides the
;;                       resource lowering untouched.
;;   page-size /       — the fixed Conduit page size + the 1-indexed page →
;;   page->limit-offset  `{:limit :offset}` arithmetic (the one place it lives)
;;   page-count        — articles-count → total pages (ceil, floored at 1)
;;   failure->message  — project a `:rf.http/*` failure into a readable sentence

(def query-string      wh/query-string)
(def data-fetch-retry  wh/data-fetch-retry)
(def page-size         wh/page-size)
(def page->limit-offset wh/page->limit-offset)
(def page-count        wh/page-count)
(def failure->message  wh/failure->message)

;; ============================================================================
;; DEMO BACKEND
;; ============================================================================
;;
;; The example ships without a backend, so the demo overrides `:rf.http/managed`
;; with the in-process Conduit demo backend both RealWorld examples share
;; (`realworld-shared.demo-backend`): one canonical corpus + a URL/method
;; request router + a canned-reply adapter. Since the resource / mutation runtime
;; lowers every fetch AND write onto `:rf.http/managed`, this one override gets to
;; play the entire Conduit API. `core.cljs` wires it as the `:rf.http/managed`
;; override on the demo frame via `:fx-overrides` — there is no explicit install
;; call to make.

(rf/reg-fx :realworld-resources.demo/http-stub
  {:doc       "This example's `:rf.http/managed` override: the shared in-process
               Conduit demo backend (`realworld-shared.demo-backend`), wired
               under an app-local fx-id and referenced from the frame's
               `:fx-overrides` in core.cljs. All the routing + canned-reply
               machinery (including the `::decode-failure` session-restore path)
               lives in the shared backend; this is just the app-local seam."
   ;; This stub is the CLASSIFIED OWNER of the request body it receives. The
   ;; login / register requests and the settings mutation carry the plaintext
   ;; password at [:request :body :user :password] (the Conduit `{user {…}}`
   ;; envelope). The real `:rf.http/managed` handler would scrub a
   ;; `:sensitive?`-marked request body, but the frame's `:fx-overrides` remap
   ;; `:rf.http/managed` to THIS stub, BYPASSING that scrub. When the override
   ;; fires, `handle-one-fx` stamps the always-emitted `:rf.fx/handled` trace
   ;; with THIS fx's id and its RAW args, and the classification projector
   ;; redacts `:rf.fx/args` off the RESOLVED fx's own `:sensitive`. So the stub
   ;; must declare its own — without it the password would ride raw on the one
   ;; wire every tool reads (docs/core/how-to/keep-secrets-out-of-traces.md)."
   :sensitive [[:request :body :user :password]]
   :platforms #{:server :client}}
  (fn fx-managed-demo-stub [frame-ctx args-map]
    (demo/respond frame-ctx args-map)))
