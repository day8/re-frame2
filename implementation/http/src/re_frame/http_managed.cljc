(ns re-frame.http-managed
  "Spec 014 — `:rf.http/managed` and family.

  A first-class managed HTTP request fx with built-in decoding,
  retry-with-backoff, abort, schema-driven decode, and reply-to-origin
  dispatch. Per spec/014-HTTPRequests.md.

  ## Public surface (registered at ns-load)

  - `:rf.http/managed`                  — issue a managed request
  - `:rf.http/managed-abort`            — abort by `:request-id`
  - `:rf.http/managed-canned-success`   — test stub
  - `:rf.http/managed-canned-failure`   — test stub

  Plus `(with-managed-request-stubs stubs body)` helper for test ergonomics.

  ## Hosts

  - **CLJS:** Fetch-API-backed.
  - **JVM:** `java.net.http.HttpClient`-backed. Per-row CLJS-only keys
    (`:abort-signal`, `:mode`, `:cache`, `:referrer`, `:integrity`) are
    no-ops on JVM with a one-line trace per occurrence.

  ## Production elision

  Trace events (`:rf.http/retry-attempt`, `:rf.warning/decode-defaulted`,
  the `:rf.error/*` from failures) gate on `interop/debug-enabled?`.
  The `:rf.http/managed` fx itself is dev+prod (user-facing). The canned
  stub fxs gate on `interop/debug-enabled?` so they elide in production.

  ## Artefact

  Ships in `day8/re-frame2-http`, separate from the core artefact.
  Loading this ns publishes the late-bind hooks `re-frame.core` reaches
  through AND registers the `:rf.http/managed` family of fxs.

  ## File split

  Thin public façade over per-concern sibling namespaces (flat dash-form
  naming — `re-frame.http.X` would collide with `goog.provide` on CLJS):

   - `re-frame.http-encoding`        — pure encoding / decode pipeline /
                                       backoff.
   - `re-frame.http-registry`        — in-flight + actor-id indexes,
                                       supersede semantics, abort-on-
                                       actor-destroy.
   - `re-frame.http-middleware`      — per-frame request-side interceptor
                                       chain.
   - `re-frame.http-transport`       — shared Fetch (CLJS) + HttpClient
                                       (JVM) transport + attempt loop.
   - `re-frame.http-handlers`        — `:rf.http/managed` /
                                       `:rf.http/managed-abort` fx
                                       handler bodies.
   - `re-frame.http-machine-wrapper` — machine-shape wrapper, canned
                                       stub handlers,
                                       with-managed-request-stubs*.
   - `re-frame.util-json`            — pure-Clojure JSON reader shared by
                                       the decode pipeline.

  This façade re-exports the public surface and performs the load-time
  side-effects: `:rf.http/*` fx registrations and `late-bind/set-fn!`
  hook publications."
  (:require [re-frame.fx                   :as fx]
            [re-frame.http-handlers        :as handlers]
            [re-frame.http-machine-wrapper :as machine-wrapper]
            [re-frame.http-middleware      :as middleware]
            [re-frame.http-privacy-headers :as privacy-headers]
            [re-frame.http-registry        :as registry]
            [re-frame.interop              :as interop]
            [re-frame.late-bind            :as late-bind]))

;; ---- public-surface re-exports --------------------------------------------

;; Registry surface — tests deref the atoms and call the snapshot fns.
(def in-flight                   registry/in-flight)
(def actor-in-flight             registry/actor-in-flight)
(def clear-all-in-flight!        registry/clear-all-in-flight!)
(def in-flight-snapshot          registry/in-flight-snapshot)
(def actor-in-flight-snapshot    registry/actor-in-flight-snapshot)
(def abort-on-actor-destroy      registry/abort-on-actor-destroy)

;; Middleware surface. Tests deref @http-managed/interceptors.
(def interceptors                middleware/interceptors)
(def reg-http-interceptor        middleware/reg-http-interceptor)
(def clear-http-interceptor      middleware/clear-http-interceptor)
(def clear-all-http-interceptors! middleware/clear-all-http-interceptors!)

;; Stub surface — per Spec 014 §Testing.
(def install-managed-request-stubs!   machine-wrapper/install-managed-request-stubs!)
(def uninstall-managed-request-stubs! machine-wrapper/uninstall-managed-request-stubs!)
(def with-managed-request-stubs*      machine-wrapper/with-managed-request-stubs*)

;; Privacy surface — Spec 014 §Privacy. Header denylist lives in
;; `re-frame.http-privacy-headers`; the orchestrating composers
;; (request-sensitive?, prepare-emit-*) stay in `re-frame.http-privacy`.
(def declare-sensitive-header!  privacy-headers/declare-sensitive-header!)
(def clear-sensitive-headers!   privacy-headers/clear-sensitive-headers!)
(def default-header-denylist    privacy-headers/default-header-denylist)

;; ---- registration ---------------------------------------------------------
;; The handler bodies live in `re-frame.http-handlers`; the façade
;; performs the registrations and gates the canned stubs on
;; `interop/debug-enabled?` so they DCE in production.

(fx/reg-fx :rf.http/managed
           {:doc "Spec 014 — managed HTTP request."}
           handlers/managed-handler)

(fx/reg-fx :rf.http/managed-abort
           {:doc "Spec 014 — abort an in-flight :rf.http/managed by request-id."}
           handlers/managed-abort-handler)

;; Canned-stub fxs are gated on `interop/debug-enabled?` so they DCE in
;; production builds. Per Spec 014 §Testing.
(when interop/debug-enabled?
  (fx/reg-fx :rf.http/managed-canned-success
             {:doc "Spec 014 — synthesised success reply (test stub)."}
             machine-wrapper/canned-success-handler)
  (fx/reg-fx :rf.http/managed-canned-failure
             {:doc "Spec 014 — synthesised failure reply (test stub)."}
             machine-wrapper/canned-failure-handler))

;; ---- with-managed-request-stubs (macro form) -----------------------------
;;
;; The macro stays in the façade because (a) it's `:require`d by users
;; as `re-frame.http-managed/with-managed-request-stubs` and (b) it
;; expands to a call into `with-managed-request-stubs*` (the fn form
;; that lives in `re-frame.http-machine-wrapper`). Keeping the macro
;; here preserves the call-site source coords that the macroexpander
;; embeds at expansion time.

#?(:clj
   (defmacro with-managed-request-stubs
     "Test-time helper. `stubs` is `{[method url] {:reply <:ok|:failure>}}`.
     Installs a per-call fx-override on `:rf.http/managed` that consults
     the stub map, synthesises the configured reply, and runs `body`.

     Per Spec 014 §Testing."
     [stubs & body]
     `(with-managed-request-stubs* ~stubs (fn [] ~@body))))

;; ---- late-bind hook registration (alphabetised) -------------------------

(late-bind/set-fn! :http/abort-on-actor-destroy           abort-on-actor-destroy)
(late-bind/set-fn! :http/clear-all-http-interceptors!     clear-all-http-interceptors!)
(late-bind/set-fn! :http/clear-all-in-flight!             clear-all-in-flight!)
(late-bind/set-fn! :http/clear-http-interceptor           clear-http-interceptor)
(late-bind/set-fn! :http/install-managed-request-stubs!   install-managed-request-stubs!)
(late-bind/set-fn! :http/reg-http-interceptor             reg-http-interceptor)
(late-bind/set-fn! :http/register-managed-machine!        machine-wrapper/register-managed-machine!)
(late-bind/set-fn! :http/uninstall-managed-request-stubs! uninstall-managed-request-stubs!)
(late-bind/set-fn! :http/with-managed-request-stubs*      with-managed-request-stubs*)
(machine-wrapper/register-managed-machine!)
