(ns re-frame.http.managed
  "Spec 014 — `:rf.http/managed` and family.

  A first-class managed HTTP request fx with built-in decoding,
  retry-with-backoff, abort, schema-driven decode, and reply-to-origin
  dispatch. Per spec/014-HTTPRequests.md.

  ## Public surface (registered at ns-load)

  - `:rf.http/managed`                  — issue a managed request
  - `:rf.http/managed-abort`            — abort by `:request-id`
  - `:rf.fx/reg-http-interceptor`       — register an HTTP interceptor
                                          (data-shaped fx for EDN fixtures;
                                          see §Middleware below). The map carries
                                          `:before` / `:after` alongside
                                          the standard registration slots.
  - `:rf.fx/clear-http-interceptor`     — clear an HTTP interceptor
                                          (data-shaped fx)

  Plus the registry / middleware / privacy re-exports detailed below.

  ## Test support

  The stubbing macros and the canned-stub fxs live in
  `re-frame.http.test-support`. A test reaching for `with-managed-request-stubs`
  / `install-managed-request-stubs!` / `uninstall-managed-request-stubs!` /
  `with-managed-request-stubs*` — or the canned effect ids via
  `:fx-overrides` — requires `re-frame.http.test-support`. Production and SSR
  code must not require that namespace.

  ## Hosts

  - **CLJS:** Fetch-API-backed.
  - **JVM:** `java.net.http.HttpClient`-backed. Per-row CLJS-only keys
    (`:abort-signal`, `:mode`, `:cache`, `:referrer`, `:integrity`) are
    no-ops on JVM with a one-line trace per occurrence.

  ## HTTP carriers

  App-specific sensitive HTTP carrier NAMES (secret-bearing header /
  query-param names beyond the immutable built-in denylists) are declared on
  the `:rf.http/managed` registration's `:carriers` metadata. An app extends
  the denylists by
  re-registering `:rf.http/managed` with a `:carriers` block:

  ```clojure
  (rf/reg-fx :rf.http/managed
    {:carriers {:headers      [\"X-Honeycomb-Team\"]
                :query-params [\"shop_token\"]}}
    http-managed/managed-handler)
  ```

  `:headers` is a vector of names (vector-only — the header denylist is
  immutable; a default-off header would be a real leak). `:query-params`
  accepts a vector of names OR a `{:include [..] :except [..]}` policy map
  whose `:except` subtracts a built-in default for THIS app's own dev trace
  (effective policy `(defaults − except) ∪ include`; `:include` wins for a
  name in both). The names lower-case and UNION onto the immutable built-in
  carrier denylists at trace-emit time (`re-frame.http.privacy/managed-carriers`).
  Carriers are process-global because there is one effect registration; a
  malformed `:carriers` block fails loudly.

  ## Production elision

  Trace events (`:rf.http/retry-attempt`,
  the `:rf.error/*` from failures) gate on `interop/debug-enabled?`. The
  `:rf.http/managed` fx itself is dev+prod (user-facing).

  ## Optional artefact

  This namespace ships in `day8/re-frame2-http`, separate from the core
  artefact (`day8/re-frame2`). The core artefact's `re-frame.core`
  re-exports look entry points up through `re-frame.late-bind`. Loading this
  namespace publishes the middleware and registry hooks and registers the
  production effects. The scoped-stub hook is published only by
  `re-frame.http.test-support`; calling
  `rf/with-managed-request-stubs*` without requiring `re-frame.http.test-support`
  raises `:rf.error/http-artefact-missing`. The raw install/uninstall pair is
  available only from the test-support namespace.

  Apps that don't issue any managed-HTTP requests don't drag the
  in-flight request registry, the Fetch / HttpClient transport
  adapters, the encode / decode pipeline, the retry-with-backoff
  machinery, the eight-category `:rf.http/*` failure taxonomy, or any
  of the `:rf.http/*` keyword strings onto the classpath.

  ## Ownership

  This namespace is the public facade and load-time registration anchor. The
  implementation lives in per-concern sibling namespaces:

   - `re-frame.http.encoding`       — URL/query/body encoding, decode
                                      pipeline, `:accept` normalisation,
                                      build-reply-event /
                                      `dispatch-reply-via-late-bind!`,
                                      backoff. All pure fns.
   - `re-frame.http.registry`       — in-flight request + actor-id
                                      indexes, supersede semantics,
                                      actor-destroy cancellation,
                                      spawned-actor detection.
   - `re-frame.http.middleware`     — per-frame request-side interceptor
                                      chain.
   - `re-frame.http.transport`     — shared Fetch (CLJS) + HttpClient
                                      (JVM) transport + attempt loop;
                                      platform-specific fragments are
                                      selected at the host boundary.
   - `re-frame.http.handlers`      — `:rf.http/managed` /
                                      `:rf.http/managed-abort` fx
                                      handler bodies.
   - `re-frame.http.machine-wrapper`— machine-shape wrapper.
   - `re-frame.http.test-support`   — test-only surface: stub macros
                                      + canned-stub handler bodies
                                      + canned-stub fx registrations +
                                      late-bind hook publication.
   - `re-frame.http.json`           — JSON reader (Cheshire on the JVM, native
                                      `JSON.parse` on CLJS); shared by
                                      the decode pipeline.

  This façade re-exports the public surface of those sub-namespaces
  AND performs the artefact's load-time side-effects: the
  `:rf.http/*` fx registrations and the `late-bind/set-fn!` hook
  publications that `re-frame.core` reaches through."
  (:require [re-frame.fx                   :as rf.fx]
            [re-frame.http.handlers        :as rf.http.handlers]
            [re-frame.http.machine-wrapper :as rf.http.machine-wrapper]
            [re-frame.http.middleware      :as rf.http.middleware]
            [re-frame.http.privacy         :as rf.http.privacy]
            [re-frame.http.privacy-headers :as rf.http.privacy-headers]
            [re-frame.http.registry        :as rf.http.registry]
            [re-frame.late-bind            :as rf.late-bind]))

;; ---- public-surface re-exports --------------------------------------------
;;
;; These `def`s make the sub-namespace fns reachable as
;; `re-frame.http.managed/<name>` so consumers (the `re-frame.core` late-
;; bind bridge, the test fixtures, examples that
;; `:require [re-frame.http.managed :as http-managed]`) see the same
;; public surface while storage and lifecycle ownership stay in the sibling.

;; Registry surface — the in-flight indexes are internal storage. Tests
;; observe them through the immutable snapshot helpers and seed test state
;; through `seed-in-flight-for-test!` (which preserves both-index invariants
;; via `record-in-flight!`); the raw `in-flight` / `actor-in-flight` atoms
;; are not re-exported because direct mutation would bypass the two-index
;; cleanup invariant.
(def clear-all-in-flight!        rf.http.registry/clear-all-in-flight!)
(def in-flight-snapshot          rf.http.registry/in-flight-snapshot)
(def actor-in-flight-snapshot    rf.http.registry/actor-in-flight-snapshot)
(def seed-in-flight-for-test!    rf.http.registry/seed-in-flight-for-test!)
(def abort-on-actor-destroy      rf.http.registry/abort-on-actor-destroy)

;; The per-frame interceptor chain is
;; internal storage; tests observe it through the immutable
;; `interceptors-snapshot` helper rather than deref'ing the `interceptors`
;; atom. Registration and clearing
;; route through `reg-http-interceptor` / `clear-http-interceptor`.
(def reg-http-interceptor        rf.http.middleware/reg-http-interceptor)
(def clear-http-interceptor      rf.http.middleware/clear-http-interceptor)
(def clear-all-http-interceptors! rf.http.middleware/clear-all-http-interceptors!)
(def interceptors-snapshot       rf.http.middleware/interceptors-snapshot)

;; Privacy surface — Spec 014 §Privacy. Header denylist lives
;; in `re-frame.http.privacy-headers`; the orchestrating composers
;; (request-sensitive?, prepare-emit-*) stay in `re-frame.http.privacy`.
;; App carrier names are declared on the `:rf.http/managed`
;; `reg-fx` registration metadata via the `:carriers {:headers [..]
;; :query-params [..]}` block (the transient-payload case — see the
;; ## HTTP carriers section in the ns docstring). The immutable built-in
;; default denylist is re-exported for tests.
(def default-header-denylist    rf.http.privacy-headers/default-header-denylist)

;; The `:rf.http/managed` fx handler body — re-exported so an app can
;; RE-REGISTER `:rf.http/managed` to declare its `:carriers` block without
;; knowing the internal handler fn (EP-0025 §HTTP carriers):
;;
;;   (rf/reg-fx :rf.http/managed
;;     {:carriers {:headers ["X-My-Auth"] :query-params ["shop_token"]}}
;;     http-managed/managed-handler)
;;
;; `re-frame.http.privacy/managed-carriers` reads the registration's
;; `:carriers` block and unions the (lower-cased) names onto the immutable
;; built-in carrier denylists at trace-emit time.
(def managed-handler            rf.http.handlers/managed-handler)

;; ---- registration ---------------------------------------------------------
;;
;; The `:rf.http/managed` and `:rf.http/managed-abort` fx handler bodies
;; live in `re-frame.http.handlers`. The facade only
;; performs the `(fx/reg-fx ...)` registrations for the production-eligible
;; managed-HTTP fxs. Apps that don't load `re-frame.http.managed` don't
;; carry the handlers either — the registration site is the load-time
;; anchor. The two canned-stub fxs register from
;; `re-frame.http.test-support`.

(rf.fx/reg-fx :rf.http/managed
           {:doc "Spec 014 — managed HTTP request."}
           rf.http.handlers/managed-handler)

(rf.fx/reg-fx :rf.http/managed-abort
           {:doc "Spec 014 — abort an in-flight :rf.http/managed by request-id."}
           rf.http.handlers/managed-abort-handler)

;; ---- middleware fx wrappers -----------------------------------------------
;;
;; `reg-http-interceptor` / `clear-http-interceptor` are direct fn-call APIs
;; — load-time registration of cross-cutting HTTP interceptors (Spec 014
;; §Middleware). Most apps register at app bootstrap
;; and never call again. But the conformance corpus (Spec 011 §Conformance)
;; is pure-data EDN, has no fn-call seam, and drives behaviour exclusively
;; through `:fx` ops + `:fx-overrides`. The two fxs below let portable
;; EDN fixtures register/clear interceptors via the same DSL channel they
;; use for everything else — `[:fx [[:rf.fx/reg-http-interceptor {...}]]]`
;; / `[:fx [[:rf.fx/clear-http-interceptor {...}]]]`.
;;
;; The fx and fn forms use the same data shape; the fx body routes `:id` into
;; the fn's positional argument:
;;   :rf.fx/reg-http-interceptor   {:id <kw> :before <fn> :after <fn>?
;;                                  :frame <id>? :doc ... :tags ... }
;;   :rf.fx/clear-http-interceptor {:id <kw> :frame <id>?}
;;
;; Both fxs are dev+prod (`:platforms #{:client :server}`). Authors can
;; register interceptors via an event-handler at boot, which is a clean
;; alternative to the fn-call shape for codebases that prefer to drive
;; bootstrap through the dispatch surface.

(rf.fx/reg-fx :rf.fx/reg-http-interceptor
           {:doc "Spec 014 §Middleware — register an
                  HTTP interceptor as an fx. Args is a map carrying
                  `:id` (kw), at least one of `:before` / `:after`,
                  optional `:frame` (id), plus any
                  `:rf/registration-metadata` slots. The fx body splits
                  `:id` off and passes the remaining map straight through
                   to the fn-form.

                  EP-0002 — the carried frame is the fx-context `:frame`
                  (the cascade envelope stamp); the args `:frame` is the
                  per-call *override*. The body threads the fx-context
                  frame in when the args omit one, so the fn-form receives
                  an explicit frame and never falls through to a
                  synthesised `:rf/default` (a frameless cascade would have
                  failed at dispatch already)."}
           (fn [{ctx-frame :frame} {:keys [id] :as args}]
             (rf.http.middleware/reg-http-interceptor
               id (-> (dissoc args :id)
                      (update :frame #(or % ctx-frame))))))

(rf.fx/reg-fx :rf.fx/clear-http-interceptor
           {:doc "Spec 014 §Middleware — clear a request-side
                  interceptor by id as an fx. Args is the map
                  `{:frame <id> :id <kw>}` (NOT positional, matching the
                  fx-convention shape — sibling to `:rf.fx/reg-http-interceptor`
                  which also takes a map). The public call-site surface is
                  the fn-form `clear-http-interceptor`'s opts form
                  `(id {:frame f})`; this fx is the data-shaped surface for
                  EDN-driven callers. It already holds a resolved frame, so it
                  routes through the artefact-internal `clear-http-interceptor*`
                  seam (frame-first) rather than the public opts form (rf2-s32bf).

                  EP-0002 — the carried frame is the fx-context `:frame`
                  (the cascade envelope stamp); the args `:frame` is the
                  per-call *override*. The args frame wins, else the
                  fx-context frame, threaded explicitly into the internal
                  frame-first seam so it never repairs to a synthesised
                  `:rf/default`."}
           (fn [{ctx-frame :frame} {:keys [frame id]}]
             (rf.http.middleware/clear-http-interceptor* (or frame ctx-frame) id)))

;; The canned effects and stub helpers are registered only when
;; `re-frame.http.test-support` is explicitly required. The namespace boundary,
;; not the debug flag, keeps test effects out of JVM/SSR production loads.

;; ---- late-bind hook registration ------------------------------------------
;;
;; Core cannot require this namespace because the HTTP artefact is optional.
;; Publish entry
;; points through the late-bind hook registry; consumers look the fns
;; up at call time.
;;
;; The stub-family late-bind hook (`:http/with-managed-request-stubs*`)
;; publishes from `re-frame.http.test-support`, so stub helpers and canned
;; effects share one require gate. Raw install/uninstall helpers publish no
;; hook and are called directly from that namespace.

(rf.late-bind/set-fn! :http/abort-in-flight!                 rf.http.registry/abort-in-flight!)
(rf.late-bind/set-fn! :http/abort-on-actor-destroy           abort-on-actor-destroy)
;; Epoch restore aborts pre-restore host work after installing the epoch. The
;; abort suppresses app replies and records stale-suppression facts. This remains
;; late-bound so the epoch artefact does not acquire an HTTP dependency.
(rf.late-bind/set-fn! :http/abort-in-flight-for-frame!       rf.http.registry/abort-in-flight-for-frame!)
;; Frame destroy aborts the destroyed frame's still-in-flight PLAIN managed HTTP
;; (rf2-j538f7.8). Called from core `frame/destroy-frame!` AFTER machine +
;; resource teardown, suppressing app replies (`:reason :frame-destroyed`) so no
;; late completion routes into the dead frame. Late-bound so core does not
;; acquire an HTTP dependency; the frame-teardown counterpart of the restore hook.
(rf.late-bind/set-fn! :http/on-frame-destroyed!              rf.http.registry/abort-in-flight-on-frame-destroyed!)
(rf.late-bind/set-fn! :http/clear-all-http-interceptors!     clear-all-http-interceptors!)
(rf.late-bind/set-fn! :http/clear-all-in-flight!             clear-all-in-flight!)
(rf.late-bind/set-fn! :http/clear-http-interceptor           clear-http-interceptor)
(rf.late-bind/set-fn! :http/reg-http-interceptor             reg-http-interceptor)
(rf.late-bind/set-fn! :http/register-managed-machine!        rf.http.machine-wrapper/register-managed-machine!)
;; rf2-32ffq1 — the core classification projector consults this to redact a
;; `:rf.http/managed` entry's args at the generic fx-arg-bearing trace slots
;; (the `:rf.event/fx` aggregate + `:rf.fx/handled`-shaped slots), honouring
;; the DYNAMIC per-call `:sensitive?` flag + carrier denylists that a static
;; registration `:sensitive` path cannot express.
(rf.late-bind/set-fn! :http/project-managed-fx-args          rf.http.privacy/project-managed-fx-args)
(rf.http.machine-wrapper/register-managed-machine!)
