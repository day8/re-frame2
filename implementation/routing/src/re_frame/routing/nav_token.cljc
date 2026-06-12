(ns re-frame.routing.nav-token
  "Navigation-token stale-result suppression for re-frame2 routing.

  Per Spec 012 §Navigation tokens — stale-result suppression. Owns:
    - `:nav-token` cofx — injects the current navigation epoch token
      (`[:rf.runtime/routing :current :nav-token]`) into an `:on-match`
      handler's `:coeffects` under key `:nav-token`, so the handler can
      capture it and thread it into an async continuation;
    - `:rf.route/with-nav-token` fx — wraps an async-completion fx
      entry (`:do`) with a stale-result check: match → run; mismatch →
      suppress and emit `:rf.route.nav-token/stale-suppressed`.

  ## Lowered onto the uniform reply envelope (EP-0011, rf2-zqefg3.5)

  The receipt-side stale check is NOT a bespoke per-family token
  comparison: it is an ORDINARY reply-envelope `:suppress` gate. The
  carried `:nav-token` is the value of the ONE data-only suppression gate
  `{:route/nav-token <token>}`, validated against the live
  `[:rf.runtime/routing :current :nav-token]` via the shared
  `re-frame.reply/stale?` (through `re-frame.routing.reply`). The route
  work-id is `[:rf.work/route route-id nav-token loader-id]`; the
  suppression trace is joined to `:work/id`. The PUBLIC API (the cofx and
  the `:rf.route/with-nav-token` fx) is PRESERVED — internal lowering
  only. See `spec/Managed-Effects.md` §The uniform reply envelope and
  Spec 012 §Lowering onto the uniform reply envelope.

  The test-only `:rf.test/simulate-http-resolution` fixture analogue of
  this fx lives in `re-frame.routing.test-support` (rf2-dbiv8) — behind
  an explicit test-support require, so it never reaches a production
  registry. This namespace carries only production surface.

  Spec-Schemas carries the `:rf.fx/with-nav-token-args` shape.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade registers the fx so a `:reload` of the façade re-wires it on a
  fresh registrar. Per the rf2-2yabr cohesion split: NAV-TOKEN seam."
  (:require [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.interop :as interop]
            [re-frame.routing.reply :as route-reply]
            [re-frame.trace :as trace]))

(def nav-token-cofx-meta
  "Metadata for the `:nav-token` cofx registration. Per Spec 012
  §Navigation tokens — stale-result suppression step 2: the cofx
  injects the current navigation epoch token so an `:on-match`-reached
  handler can capture the token live at scheduling time and thread it
  into an async continuation (via `:rf.route/with-nav-token` or its own
  follow-up event payload).

  Universal platform: the route slice exists on both client and server,
  so the cofx resolves under SSR and browser alike."
  {:doc "The current navigation epoch token, read from
`[:rf.runtime/routing :current :nav-token]` and injected under
`:coeffects :nav-token`. Declare with `(inject-cofx :nav-token)` on an
`:on-match`-reached handler; capture the value and thread it into an
async continuation so a superseding navigation suppresses the stale
result. Per Spec 012 §Navigation tokens — stale-result suppression."})

(defn nav-token-cofx
  "Handler fn for the `:nav-token` cofx. Reads the current navigation
  epoch token from the injected `:rf.db/runtime` coeffect (the runtime
  pre-populates `:coeffects :rf.db/runtime` with the frame's runtime-db
  partition value before the interceptor chain runs) and injects it under
  `:coeffects :nav-token`. EP-0001 (rf2-vzld77): the route slice is durable
  routing runtime-db state.

  1-arity is the canonical form. 2-arity accepts an explicit value
  override — useful in tests / conformance harnesses that want to assert
  the threading shape without standing up a route slice.

  Meaningful only inside a handler reached via an `:on-match` drain (or a
  follow-up of one), where `[:rf.runtime/routing :current :nav-token]`
  holds the epoch the navigation cascade allocated. Read from any other
  handler it reflects whatever navigation is currently active — which is
  the correct \"is this still the live navigation?\" reading the
  stale-suppression pattern wants."
  ([ctx]
   (let [rdb   (get-in ctx [:coeffects :rf.db/runtime])
         token (get-in rdb [:rf.runtime/routing :current :nav-token])]
     (assoc-in ctx [:coeffects :nav-token] token)))
  ([ctx token]
   (assoc-in ctx [:coeffects :nav-token] token)))

(defn- inner-fx-event-id
  "Best-effort extraction of an `event-id` from an `:do` fx entry. For
  the canonical `[:dispatch [<event-id> args...]]` shape the event-id is
  the head of the inner event vector; for any other fx entry we fall
  back to the outer fx-id (e.g. `:rf.http/managed`) so the `:event-id`
  tag still identifies what was suppressed."
  [do-entry]
  (when (vector? do-entry)
    (let [[fx-id inner-event-vec] do-entry]
      (if (and (= :dispatch fx-id)
               (vector? inner-event-vec)
               (seq inner-event-vec))
        (first inner-event-vec)
        fx-id))))

(defn emit-stale-suppressed!
  "Emit the `:rf.route.nav-token/stale-suppressed` trace for a superseded
  route-loader completion. The facts ride on top of the shared
  `re-frame.reply/suppress` outcome (EP-0011 §Route Loader Completion):
  the suppression trace is joined to `:work/id`, alongside the existing
  carried-token / current-token / event-id tags. Shared by the
  production `:rf.route/with-nav-token` handler and the test-only
  `:rf.test/simulate-http-resolution` fixture so one conformance
  assertion covers both paths.

  `event-id` is the suppressed continuation's event-id (per
  `inner-fx-event-id`); `frame-id` (when non-nil) frame-attributes the
  suppression so it lands in the emitting frame's epoch / Xray
  (rf2-7d30s). The work-id is built from the route context
  (`{:route-id … :nav-token <carried> :loader-id …}`) — `route-reply/
  suppress` carries it on the reply + trace.

  rf2-6mfkp3 — the canonical EP-0011 reply-envelope facts ride ADDITIVELY
  (`:rf.reply/status :stale`, `:rf.reply/work-status :suppressed`,
  `:rf.reply/stale-reason`) — the SAME shape the resource
  (`:rf.resource/stale-suppressed`) and machine (`:rf.machine/done` /
  `:rf.machine.timer/stale-after`) families stamp — so a superseded route
  loader (an EP-0011 managed async family) is classifiable on the
  production trace via the identical canonical facts as every other family,
  not only the route-specific `:carried-token` / `:current-token` tokens.
  The facts are read off the `route-reply/suppress` `:reply` (the
  `:status :stale` / `:work/status :suppressed` / `:stale/reason` the
  shared substrate produced)."
  [{:keys [carried-token current-token event-id frame-id route-id loader-id]}]
  (let [{:keys [reply trace]} (route-reply/suppress
                          {;; rf2-azcmd3 — `route-id` is the route id CAPTURED
                           ;; at scheduling time (carried with the nav-token),
                           ;; NOT the live route slice id read at stale-arrival.
                           ;; If route A's stale completion arrives after a
                           ;; navigation to route B, the live slice id is B's —
                           ;; using it would mint a corrupt work-id
                           ;; `[:rf.work/route :route/B nav-A loader]` mixing
                           ;; B's route id with A's carried nav-token. The
                           ;; carried id keeps the work-id attributed to the
                           ;; route-loader ATTEMPT (Spec 012 §742 / EP-0011
                           ;; §Work-id correlation). nil when the caller did
                           ;; not capture one — preferred over a false
                           ;; live-route attribution.
                           :route-id  route-id
                           :nav-token carried-token
                           :loader-id loader-id
                           :frame     frame-id}
                          current-token)]
    (trace/emit-error! :rf.route.nav-token/stale-suppressed
                       (cond-> {:carried-token     carried-token
                                :current-token     current-token
                                :rf.trace/event-id event-id
                                ;; The suppression trace is joined to
                                ;; `:work/id` per EP-0011 §Route Loader
                                ;; Completion — the route-loader work-id
                                ;; `[:rf.work/route route-id nav-token
                                ;; loader-id]` the shared substrate built.
                                :work/id           (:work/id trace)
                                ;; rf2-waawic — the shared carried/current
                                ;; correlation facts `re-frame.reply/suppress`
                                ;; computes (Managed-Effects §Tracing), so the
                                ;; uniform reply-envelope view reads the route
                                ;; stale gate without route-family-specific
                                ;; parsing. The bespoke `:carried-token` /
                                ;; `:current-token` above are preserved.
                                :rf.reply/carried  (:rf.reply/carried trace)
                                :rf.reply/current  (:rf.reply/current trace)
                                ;; rf2-6mfkp3 — the canonical EP-0011 status /
                                ;; work-status / stale-reason vocabulary
                                ;; (Managed-Effects §9), read off the shared
                                ;; substrate `:reply`. Route loaders are a
                                ;; managed async family: a superseded route
                                ;; completion is classifiable on the production
                                ;; trace via the SAME canonical facts as the
                                ;; resource / machine / HTTP families.
                                :rf.reply/status      (:status reply)
                                :rf.reply/work-status (:work/status reply)
                                :rf.reply/stale-reason (:stale/reason reply)
                                :recovery          :replaced-with-default}
                         frame-id (assoc :frame frame-id)))))

(def with-nav-token-meta
  "Metadata for the `:rf.route/with-nav-token` fx registration: the
  docstring + the inline Malli schema per Spec-Schemas.md
  §`:rf.fx/with-nav-token-args`. Inline rather than a registered
  schema-id so validation works in consumers that don't pre-register the
  keyword in their Malli registry; the registered-id form remains
  available to apps that want to centralise schemas (per Spec 010
  §Schema registration)."
  {:doc  "Per Spec 012 §Navigation tokens. Threads the carried
`:nav-token` against the current `[:rf.runtime/routing :current :nav-token]`. Match → run
`:do` (any fx entry); mismatch → suppress and emit
`:rf.route.nav-token/stale-suppressed`."
   :schema [:map
            [:do        [:vector :any]]
            [:nav-token :any]
            ;; rf2-azcmd3 — OPTIONAL captured route id. When the loader
            ;; captured the route id at scheduling time and threads it here,
            ;; a cross-route stale completion attributes its work-id to the
            ;; route-loader ATTEMPT rather than the route live at arrival.
            [:route-id {:optional true} :any]]})

(defn with-nav-token-handler
  "`:rf.route/with-nav-token` fx handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar.

  The stale check is an ordinary reply-envelope `:suppress` gate
  (EP-0011, rf2-zqefg3.5): the carried `:nav-token` and the live route
  slice token are compared through the shared `re-frame.reply/stale?`
  (via `re-frame.routing.reply/suppress?`) — match runs the wrapped
  `:do`; mismatch suppresses it and emits `:rf.route.nav-token/
  stale-suppressed` joined to the route work-id. The public fx surface
  (`{:do … :nav-token …}`) is unchanged."
  [{:keys [frame] :as _ctx} args]
  ;; Destructure `:do` via `get` rather than `:keys` so the binding name
  ;; doesn't shadow `clojure.core/do` inside the body. Per Spec 012
  ;; §Threading the `:do` slot is the wrapped fx entry to perform.
  (let [do-entry        (get args :do)
        nav-token       (get args :nav-token)
        ;; rf2-azcmd3 — the CAPTURED route id (optional). Captured at
        ;; scheduling time alongside the nav-token and threaded into the
        ;; async continuation, so a cross-route stale completion attributes
        ;; its work-id to the route-loader ATTEMPT, not whatever route is live
        ;; when the stale completion arrives. Absent ⇒ nil (preferred over a
        ;; false live-route attribution).
        carried-route-id (get args :route-id)
        ;; EP-0002 carried invariant — the fx context carries the cascade
        ;; envelope frame as `:frame`; a nil stamp is an invariant failure
        ;; (`:rf.error/no-frame-context`), never a synthesised `:rf/default`.
        frame-id        (frame/require-frame-stamp!
                          frame :rf.route/with-nav-token
                          {:where 'rf.route/with-nav-token-handler})
        frame-record    (frame/frame frame-id)
        ;; EP-0001 (rf2-vzld77): the route slice is durable routing runtime-db state.
        rdb             (frame/frame-runtime-db-value frame-id)
        slice           (get-in rdb [:rf.runtime/routing :current])
        current         (:nav-token slice)]
    (if-not (route-reply/suppress? nav-token current)
      ;; Gate matches (token current) — route the inner fx entry through
      ;; `fx/handle-one-fx`. Routing it through the same machinery means
      ;; `:dispatch`, `:dispatch-later`, `:rf.http/managed`, et al. all
      ;; work uniformly. `handle-one-fx` rather than `do-fx` so the
      ;; cascade's single `:event/do-fx` boundary marker stays on the
      ;; outer walk (the inner re-entry must not double-emit it — the
      ;; epoch projection's six-domino bucketing keys off that marker
      ;; per `trace/projection.cljc`). The active-platform resolution
      ;; mirrors `router/run-fx-effects!` so a server-only or
      ;; client-only inner fx skips with the standard
      ;; `:rf.fx/skipped-on-platform` trace.
      (let [active-platform (or (get-in frame-record [:config :platform])
                                (interop/active-platform))]
        (fx/handle-one-fx frame-id do-entry active-platform {} nil))

      ;; Stale — suppress through the shared reply-envelope correctness
      ;; boundary. Same trace shape as `:rf.test/simulate-http-resolution`
      ;; (now joined to `:work/id`) so a single conformance assertion
      ;; covers both production and test paths. rf2-7d30s — `frame-id`
      ;; frame-attributes the suppression so it lands in the emitting
      ;; frame's epoch / Xray.
      (emit-stale-suppressed!
        {:carried-token nav-token
         :current-token current
         :event-id      (inner-fx-event-id do-entry)
         :frame-id      frame-id
         ;; rf2-azcmd3 — use the CAPTURED route id (carried with the
         ;; nav-token), NOT `(:id slice)` (the route live at stale-arrival).
         ;; A cross-route stale completion would otherwise attribute the
         ;; stale loader to the CURRENT route id.
         :route-id      carried-route-id
         :loader-id     (inner-fx-event-id do-entry)}))))
