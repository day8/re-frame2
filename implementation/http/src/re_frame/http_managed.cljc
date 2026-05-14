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

  ## Artefact (rf2-5kpd, fifth per-feature split per rf2-5vjj Strategy B)

  This namespace ships in `day8/re-frame2-http`, separate from the core
  artefact (`day8/re-frame2`). The core artefact's `re-frame.core`
  re-exports of `install-managed-request-stubs!` /
  `uninstall-managed-request-stubs!` / `with-managed-request-stubs*` /
  `with-managed-request-stubs` look this namespace's entry points up via
  the `re-frame.late-bind` hook table — loading this namespace publishes
  the hooks AND registers the `:rf.http/managed`,
  `:rf.http/managed-abort`, `:rf.http/managed-canned-success`, and
  `:rf.http/managed-canned-failure` fxs. Apps that don't issue any
  managed-HTTP requests don't drag the in-flight request registry, the
  Fetch / HttpClient transport adapters, the encode / decode pipeline,
  the retry-with-backoff machinery, the eight-category `:rf.http/*`
  failure taxonomy, or any of the `:rf.http/*` keyword strings onto the
  classpath.

  ## File split (rf2-3i9b, rf2-p7da, rf2-0eyp2)

  This namespace was 1790 LoC pre-split; it's now a thin public façade.
  The implementation is in per-concern sibling namespaces (flat
  dash-form naming, NOT dot-form — per rf2-2vbm `re-frame.http.X` would
  collide with `goog.provide('re_frame.http')` on CLJS):

   - `re-frame.http-encoding`       — URL/query/body encoding, decode
                                      pipeline, `:accept` normalisation,
                                      build-reply-event /
                                      `dispatch-reply-via-late-bind!`,
                                      backoff. All pure fns. (The earlier
                                      `failure-map` / `realise-body` /
                                      `default-accept-fn` helpers were
                                      inlined into their call sites per
                                      rf2-sz4n0.)
   - `re-frame.http-registry`       — in-flight request + actor-id
                                      indexes, supersede semantics,
                                      `abort-on-actor-destroy` (rf2-wvkn),
                                      spawned-actor detection.
   - `re-frame.http-middleware`     — per-frame request-side interceptor
                                      chain (rf2-6y3q).
   - `re-frame.http-transport`     — shared Fetch (CLJS) + HttpClient
                                      (JVM) transport + attempt loop;
                                      platform-specific fragments are
                                      gated with reader conditionals
                                      (rf2-921qy).
   - `re-frame.http-handlers`      — `:rf.http/managed` /
                                      `:rf.http/managed-abort` fx
                                      handler bodies (rf2-0eyp2).
   - `re-frame.http-machine-wrapper`— machine-shape wrapper (rf2-ijm7),
                                      canned stub handlers,
                                      with-managed-request-stubs*.
   - `re-frame.util-json`           — pure-Clojure JSON reader extracted
                                      per rf2-p7da; shared by the decode
                                      pipeline. (Currently shipped in
                                      the http artefact; lift to core
                                      if a second consumer appears.)

  This façade re-exports the public surface of those sub-namespaces
  AND performs the artefact's load-time side-effects: the
  `:rf.http/*` fx registrations and the `late-bind/set-fn!` hook
  publications that `re-frame.core` reaches through."
  (:require [re-frame.fx                   :as fx]
            [re-frame.http-handlers        :as handlers]
            [re-frame.http-machine-wrapper :as machine-wrapper]
            [re-frame.http-middleware      :as middleware]
            [re-frame.http-privacy-headers :as privacy-headers]
            [re-frame.http-registry        :as registry]
            [re-frame.interop              :as interop]
            [re-frame.late-bind            :as late-bind]))

;; ---- public-surface re-exports --------------------------------------------
;;
;; These `def`s make the sub-namespace fns reachable as
;; `re-frame.http-managed/<name>` so consumers (the `re-frame.core` late-
;; bind bridge, the test fixtures, examples that
;; `:require [re-frame.http-managed :as http-managed]`) see the same
;; surface they did pre-split.

;; Registry surface — tests deref the atoms and call the snapshot fns.
(def in-flight                   registry/in-flight)
(def actor-in-flight             registry/actor-in-flight)
(def clear-all-in-flight!        registry/clear-all-in-flight!)
(def in-flight-snapshot          registry/in-flight-snapshot)
(def actor-in-flight-snapshot    registry/actor-in-flight-snapshot)
(def abort-on-actor-destroy      registry/abort-on-actor-destroy)

;; Middleware surface — per rf2-6y3q. Tests deref @http-managed/interceptors.
(def interceptors                middleware/interceptors)
(def reg-http-interceptor        middleware/reg-http-interceptor)
(def clear-http-interceptor      middleware/clear-http-interceptor)
(def clear-all-http-interceptors! middleware/clear-all-http-interceptors!)

;; Stub surface — per Spec 014 §Testing.
(def install-managed-request-stubs!   machine-wrapper/install-managed-request-stubs!)
(def uninstall-managed-request-stubs! machine-wrapper/uninstall-managed-request-stubs!)
(def with-managed-request-stubs*      machine-wrapper/with-managed-request-stubs*)

;; Privacy surface — Spec 014 §Privacy (rf2-bma05). Header denylist lives
;; in `re-frame.http-privacy-headers`; the orchestrating composers
;; (request-sensitive?, prepare-emit-*) stay in `re-frame.http-privacy`.
(def declare-sensitive-header!  privacy-headers/declare-sensitive-header!)
(def clear-sensitive-headers!   privacy-headers/clear-sensitive-headers!)
(def default-header-denylist    privacy-headers/default-header-denylist)

;; ---- registration ---------------------------------------------------------
;;
;; The `:rf.http/managed` and `:rf.http/managed-abort` fx handler bodies
;; live in `re-frame.http-handlers` (per rf2-0eyp2). The façade only
;; performs the `(fx/reg-fx ...)` registrations and the canned-stub
;; registrations gated on `interop/debug-enabled?`. Apps that don't
;; load `re-frame.http-managed` don't carry the handlers either —
;; the registration site is the load-time anchor.

(fx/reg-fx :rf.http/managed
           {:doc "Spec 014 — managed HTTP request."}
           handlers/managed-handler)

(fx/reg-fx :rf.http/managed-abort
           {:doc "Spec 014 — abort an in-flight :rf.http/managed by request-id."}
           handlers/managed-abort-handler)

;; The two canned-stub fxs are gated on `interop/debug-enabled?` so they
;; elide in production. Per Spec 014 §Testing — "Don't ship the canned-
;; stub fxs as production-eligible". The gate applies on BOTH hosts: on
;; JVM `debug-enabled?` is `true` so the registrations run; on CLJS the
;; gate folds to `(when goog/DEBUG ...)` and `:advanced + goog.DEBUG=
;; false` DCEs the entire body — fx-id keywords, doc string, handler
;; var references and all (rf2-omsae).
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

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.core needs to call into the test-time helpers but per
;; rf2-5kpd ships in the core artefact — it cannot `:require` this
;; namespace because the http artefact is optional (apps that don't
;; issue any managed-HTTP requests don't carry it). Publish entry
;; points through the late-bind hook registry; consumers look the fns
;; up at call time. The contracts live in each sub-namespace's
;; docstring (see e.g. `registry/abort-on-actor-destroy`,
;; `middleware/reg-http-interceptor`); the listing below is alphabetical
;; for scan-ability.

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
