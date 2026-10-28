(ns re-frame.ssr
  "Server-side rendering and hydration façade.

  Requiring this namespace installs the SSR event, effect, coeffect and error
  projector registrations, and publishes the late-bind hooks core reaches it
  through, its two error-capture hooks among them. It also eagerly loads the
  head namespace so its late-bound host hook is available.

  The façade exposes pure HTML emission, the hydration payload, client
  hydration, per-frame request and response side channels, error projection,
  and the headless adapter. Core reaches this optional artefact
  only through `re-frame.late-bind`; this artefact may depend on core."
  (:require [re-frame.cofx :as rf.cofx]
            [re-frame.events :as rf.events]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.late-bind :as rf.late-bind]
            ;; Loaded eagerly so both hydration boot re-exports resolve.
            [re-frame.ssr.boot :as rf.ssr.boot]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.error-listener :as rf.ssr.error-listener]
            [re-frame.ssr.error-projector :as rf.ssr.error-projector]
            ;; Publishes the `:ssr/reg-head` hook at namespace load, and is
            ;; the home of the head READ (`head-model`).
            [re-frame.ssr.head :as rf.ssr.head]
            [re-frame.ssr.hydrate :as rf.ssr.hydrate]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.request :as rf.ssr.request]
            [re-frame.ssr.response :as rf.ssr.response]
            ;; Loaded with the façade: the published description of each
            ;; server effect's args shape, for docs and tools.
            [re-frame.ssr.server-fx-schemas]
            [re-frame.ssr.substrate :as rf.ssr.substrate]))

;; ---- public-surface re-exports --------------------------------------------
;;
;; `def`s expose the supported sub-namespace functions at the façade.

(def render-to-string                rf.ssr.emit/render-to-string)
;; There is no emitter-side view annotation: dev-mode view annotation
;; lives at the reg-view registration boundary
;; (`re-frame.views.jvm-source-coord-annotation`), not in the emitter.
;; The two halves of the head contract's READ side, re-exported here so
;; they sit on the same door as `render-to-string`: `head-model` resolves a frame's `:rf/head-model`,
;; `head-model->html` serialises one. The REGISTRAR `reg-head` stays at
;; home on `re-frame.ssr.head` (and is reachable through `re-frame.core`
;; via the `:ssr/reg-head` late-bind hook).
(def head-model                      rf.ssr.head/head-model)
(def head-model->html                rf.ssr.head/head-model->html)
(def adapter                         rf.ssr.substrate/adapter)
;; `hydrate!` preserves read → hydrate ordering. The DOM reader is
;; CLJS-only.
(def hydrate!                        rf.ssr.boot/hydrate!)
;; A page is N roots; `hydrate-page!` boots them with per-root failure
;; isolation, so one failing root cannot stop the others (Spec 011
;; §Failed-root isolation).
(def hydrate-page!                   rf.ssr.boot/hydrate-page!)
#?(:cljs (def read-server-payload    rf.ssr.boot/read-server-payload))
(def default-response                rf.ssr.response/default-response)
;; The response accumulator is a per-frame side channel, not application
;; state, so it cannot enter the hydration payload.
;; Tests reach the var via `(resolve 're-frame.ssr/response-slots)`.
(def ^:private response-slots        rf.ssr.response/response-slots)
(def public-error-keys               rf.ssr.error-projector/public-error-keys)
(def fallback-public-error           rf.ssr.error-projector/fallback-public-error)
(def default-error-projector-fn      rf.ssr.error-projector/default-error-projector-fn)
(def reg-error-projector             rf.ssr.error-projector/reg-error-projector)
(def project-error                   rf.ssr.error-projector/project-error)
(def apply-error-projection!         rf.ssr.error-listener/apply-error-projection!)
;; Host adapters route render-time exceptions through the same projector as
;; drain-time errors so both paths materialise an equivalent wire response.
(def project-render-exception!       rf.ssr.error-listener/project-render-exception!)
(def get-response                    rf.ssr.error-listener/get-response)
;; `peek-response`, `get-response` and `flush-response!` are pure reads.
;; `flush-response-result!` is the settle: it drains pending error
;; projections, then returns the response AND the projected `:public-error`
;; so host adapters classify drain-time outcomes (4xx app arm vs 5xx error
;; arm) without re-inferring from status.
(def peek-response                   rf.ssr.error-listener/peek-response)
(def flush-response!                 rf.ssr.error-listener/flush-response!)
(def flush-response-result!          rf.ssr.error-listener/flush-response-result!)
;; framework-private at the public surface — Spec 011 §Per-request
;; frame teardown. Tests reach the var via `(resolve ...)`.
(def ^:private pending-error-traces  rf.ssr.error-listener/pending-error-traces)
;; The error-view containment peek + one-shot clear — a host
;; adapter detects a reactive sub that recovered-to-nil inside the error view
;; and falls back to the locked template without re-projecting.
(def pending-error-trace?            rf.ssr.error-listener/pending-error-trace?)
(def clear-pending-error-traces!     rf.ssr.error-listener/clear-pending-error-traces!)
;; Private at the façade. Tests reach via
;; `re-frame.ssr.test-fixture/reset-runtime` (the canonical reset surface)
;; or by `:require`-ing `re-frame.ssr.request` directly. Production
;; consumers go through `set-request!` / `get-request` / `clear-request!`.
(def ^:private request-slots         rf.ssr.request/request-slots)
(def set-request!                    rf.ssr.request/set-request!)
(def get-request                     rf.ssr.request/get-request)
(def clear-request!                  rf.ssr.request/clear-request!)
(def on-frame-destroyed!             rf.ssr.request/on-frame-destroyed!)

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

  The host render path (the Ring `build-full-response*`) calls this AFTER
  frame setup + route resolution and BEFORE the render walk, so the walk sees
  a SETTLED resource state. The
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
   (if-let [drain (rf.late-bind/get-fn :resources/drain-blocking-ssr!)]
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

;; ---- hydration payload ----------------------------------------------------
;;
;; Spec 011 §Hydration is a defined protocol. A host that runs its own request lifecycle
;; builds the payload here, so it ships what `re-frame.ssr.ring/ssr-handler`
;; would rather than re-implementing a security-relevant projection.

(defn hydration-payload
  "Build the `:rf/hydration-payload` for the SSR frame `frame` (its id, or the
  frame value `make-frame` returns): the map a host that runs its own request
  lifecycle `pr-str`s into the page's `__rf_payload` script. It applies the
  same policy and projections as `re-frame.ssr.ring/ssr-handler`, under this
  frame's classification:

    :rf/app-db     — the app-db keys and paths the `:payload` policy allows,
                     with any path the frame classifies `:sensitive`
                     redacted unless a `:payload` path names it as a leaf;
    :rf/runtime-db — only the durable slices (route, machine snapshots,
                     resource entries), classified values redacted; absent
                     when there is nothing durable to ship;
    :rf/version    — the SSR pattern-protocol version.

  `opts` are `ssr-handler`'s payload options:
    :payload         — REQUIRED. A vector of top-level app-db keys and paths
                       (`[]` ships nothing), e.g. `[:articles [:session :csrf]]`
                       — a path that names a classified leaf ships it raw —
                       or `:rf.ssr.payload/whole-app-db`. Absent, it throws
                       `:rf.error/ssr-missing-payload-policy`.
    :client-frame-id — the stable client frame id to stamp as
                       `:rf/frame-id`. Leave it out for a per-request frame:
                       its id would fail the client's frame-id check.
    :schema-digest   — stamped as `:rf/schema-digest`.

  Call it once the frame has settled and before `destroy-frame!`: a frame that
  is not live projects as fully redacted."
  [frame opts]
  (let [frame-id                    (rf.frame/frame-value->id frame)
        {:rf.db/keys [app runtime]} (rf.frame/frame-state-value frame-id)]
    (rf.ssr.payload-policy/build-payload
      (:client-frame-id opts)
      (rf.ssr.payload-policy/project-app-db-egress
        (rf.ssr.payload-policy/apply-policy app opts)
        frame-id
        (:payload opts))
      (assoc opts :runtime-db (rf.ssr.payload-policy/project-runtime-db runtime frame-id)))))

;; ---- :rf/hydrate event + :rf.ssr/check-* fxs ------------------------------
;;
;; Spec 011 §The :rf/hydrate event.

;; SSR is a framework-authorised runtime-db writer. The `:rf/hydrate` handler installs the
;; hydration metadata into the reserved `[:rf.runtime/ssr :hydration]` slot
;; by returning a `:rf.db/runtime` effect, so it mints framework-write
;; authority via the general `:rf/framework-authority?` registration-meta key
;; (Conventions §Reserved registration meta) — without it, every hydrate
;; dispatch would trip the `:rf.warning/app-handler-runtime-effect`
;; ownership diagnostic in development. Its payload is a whole frame-state,
;; which can carry a value the host permits onto the wire raw, so it is
;; classified `:sensitive` as a whole: every trace and egress record of the
;; event carries `:rf/redacted` in its place.
(rf.events/reg-event :rf/hydrate
                     {:rf/framework-authority? true
                      :sensitive               [[]]}
                     rf.ssr.hydrate/hydrate-event-handler)

(rf.fx/reg-fx :rf.ssr/check-version
  {:doc       "Compare the payload's :rf/version (server) against the
client runtime's version. A mismatch emits a structured
:rf.ssr/version-mismatch trace; the hydration handler still applies
(best-effort). Per Spec 011 §The :rf/hydrate event."
   :platforms #{:client}}
  rf.ssr.hydrate/check-version-fx)

(rf.fx/reg-fx :rf.ssr/check-schema-digest
  {:doc       "Compare the payload's :rf/schema-digest (server) against
the client's registered app-schema digest. A mismatch emits a structured
:rf.ssr/schema-digest-mismatch trace. Per Spec 011 §The :rf/hydrate event."
   :platforms #{:client}}
  rf.ssr.hydrate/check-schema-digest-fx)

;; ---- the six :rf.server/* response-shape fxs ------------------------------
;;
;; Per Spec 011 §HTTP response contract.

(rf.fx/reg-fx :rf.server/set-status
  {:doc       "Set the HTTP response status. Last-write-wins per
[Spec 011 §Multiple-status policy]."
   :platforms #{:server}}
  rf.ssr.response/set-status-fx)

(rf.fx/reg-fx :rf.server/set-header
  {:doc       "Replace any existing header with the same name (case-
insensitive) and write [name value]. Per Spec 011 §Header replacement
vs append."
   :platforms #{:server}}
  rf.ssr.response/set-header-fx)

(rf.fx/reg-fx :rf.server/append-header
  {:doc       "Append [name value] to headers — preserves any existing
header with the same name. Required for Set-Cookie-style multi-valued
headers. Per Spec 011 §Header replacement vs append."
   :platforms #{:server}}
  rf.ssr.response/append-header-fx)

(rf.fx/reg-fx :rf.server/set-cookie
  {:doc       "Add a structured cookie to the :cookies vector. Cookie
attributes are stored as a structured map (RFC 6265 wire-form
serialisation is host-adapter business). Per Spec 011 §Cookie shape."
   :platforms #{:server}}
  rf.ssr.response/set-cookie-fx)

(rf.fx/reg-fx :rf.server/delete-cookie
  {:doc       "Sugar over :rf.server/set-cookie with :max-age 0 and an
empty :value. The host adapter materialises the delete-marker semantics
on the wire. Per Spec 011 §Cookie shape."
   :platforms #{:server}}
  rf.ssr.response/delete-cookie-fx)

(rf.fx/reg-fx :rf.server/redirect
  {:doc       "Set :redirect on the response accumulator after the URL gate.
Relative-only by default: :allow names the hosts permitted beyond the
request's own origin, and :allow :any is the explicit trusted spelling.
Defaults :status to 302. Last-write-wins. Per Spec 011 §HTTP response
contract §Standard fx and §Redirect precedence.

Args:
  {:location \"/dashboard\"
   :allow    [\"app.example.com\" \"alt.example.com\"]   ;; or :any
   :status   302}

Validation order: a malformed call throws first (a non-string :location,
a bad :status or :allow, a :relative-only?, :url or :to key, CR/LF/NUL in
:location — :allow :any included). The gate then rejects, in order: a
javascript:/data:/vbscript: scheme prefix, before parsing —
:rf.error/redirect-scheme-rejected; a URL that does not parse —
:rf.error/redirect-invalid-url; any scheme other than http/https —
:rf.error/redirect-scheme-rejected; a scheme with no host —
:rf.error/redirect-invalid-url; with no :allow, a URL that is not a
relative reference — :rf.error/redirect-host-disallowed (:reason
:relative-only-violation); with :allow hosts, a non-relative URL whose host
is not among them — :rf.error/redirect-host-disallowed (:reason
:not-in-allowlist). A URL that passes sets :redirect."
   :platforms #{:server}}
  rf.ssr.response/redirect-fx)

;; ---- :rf.server/request cofx ----------------------------------------------
;;
;; Per Spec 011 §Server-only `reg-cofx` for request context.

(rf.cofx/reg-cofx :rf.server/request
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
   :platforms #{:server}
   :recordable? false}
  rf.ssr.request/request-cofx)

;; ---- error-projector registry ---------------------------------------------
;;
;; Per Spec 011 §Default projector + §Server error projection.

(reg-error-projector :rf.ssr/default-error-projector
                     {:doc "Built-in default projector. Spec 011 §Default projector mapping."}
                     default-error-projector-fn)

;; ---- error capture --------------------------------------------------------
;;
;; Server error projection captures through two late-bind hooks that core's
;; delivery code calls beside its listener fan-out. Neither is a listener,
;; because a test's listener clear removes a listener, and a listener can take
;; a dev console print — an error listener each record it claims by returning
;; exactly `true`, a trace listener every unobserved warning by being
;; registered. SSR's capture is a permanent framework consumer that must
;; survive the clear and own neither print; core ignores a hook's return
;; value, so the capture never claims a record.
;;
;; The always-on capture survives `interop/debug-enabled? = false`, so the
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
;; (routing's URL-driven miss, the one this projector maps to
;; 404), `:rf.error/drain-depth-exceeded` and the promoted SSR
;; categories. A sub that throws mid-render projects a fail-closed 5xx under
;; production hardening instead of recovering to nil and producing an HTTP
;; 200; an unroutable URL projects 404 instead of a soft-404 200.
(rf.late-bind/set-fn! :ssr/capture-error-record
                      rf.ssr.error-listener/error-emit-projection-listener)

;; The dev capture covers the same categories on the DEV trace delivery,
;; plus the dev-only ones, which have no always-on record and so project
;; only while `interop/debug-enabled?` holds. Categories on BOTH axes —
;; `:rf.error/sub-exception`, `:rf.error/no-such-handler`,
;; `:rf.error/drain-depth-exceeded`, and the `:boundary? true`
;; `:rf.error/schema-validation-failure`, whose check runs in every build
;; (Spec 010 §Production builds) — buffer twice in dev; the always-on
;; axis is their production status source of truth, and the 1-arity
;; `apply-error-projection!` keeps the highest projected status, so the
;; duplicate buffer entry is benign.
(rf.late-bind/set-fn! :ssr/capture-error-trace
                      rf.ssr.error-listener/error-projection-listener)

;; ---- late-bind hook registration ------------------------------------------
;;
;; Core's `reg-error-projector` REGISTRAR re-export looks its producing fn
;; up through this hook table — core never statically `:require`s
;; `re-frame.ssr`. When the ssr artefact is not on the classpath the
;; lookup returns nil and the consumer raises
;; `:rf.error/ssr-artefact-missing`.
;;
;; The QUERY surface publishes no hook: `re-frame.ssr` is the only door for
;; `render-to-string` / `project-error`, so there is
;; nothing on the façade to late-bind.

(rf.late-bind/set-fn! :ssr/reg-error-projector reg-error-projector)
;; A destroy clears the SSR side-channel atoms (`pending-error-traces`,
;; `request-slots`, `response-slots`) for the frame, or a long-lived server
;; keeps one entry per request served. Spec 002 §Lifecycle contributions.
(rf.frame/contribute-lifecycle!
  {:id                 :ssr
   :phase              #{:post-dead}
   :config-keys        #{:ssr}
   :on-frame-destroyed (fn [frame-id _phase] (on-frame-destroyed! frame-id))})

;; `re-frame.ssr.head` is required above so its `:ssr/reg-head` late-bind
;; hook lands at ssr-ns load time on both JVM and CLJS. Reading a head is a
;; pure read, so the head ns keeps no per-frame bookkeeping and teardown has
;; nothing to release for it.
