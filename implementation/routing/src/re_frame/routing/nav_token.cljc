(ns re-frame.routing.nav-token
  "Navigation-token stale-result suppression for re-frame2 routing.

  Per Spec 012 §Navigation tokens — stale-result suppression. Owns:
    - `:nav-token` cofx — injects the current navigation epoch token
      (`[:rf/runtime :routing :current :nav-token]`) into an `:on-match`
      handler's `:coeffects` under key `:nav-token`, so the handler can
      capture it and thread it into an async continuation;
    - `:rf.test/simulate-http-resolution` — the test-only fixture
      analogue of the production-grade fx below;
    - `:rf.route/with-nav-token` fx — wraps an async-completion fx
      entry (`:do`) with a stale-result check: match → run; mismatch →
      suppress and emit `:rf.route.nav-token/stale-suppressed`.

  Spec-Schemas carries the `:rf.fx/with-nav-token-args` shape.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade registers the test-only event + the fx so a `:reload` of the
  façade re-wires both on a fresh registrar. Per the rf2-2yabr cohesion
  split: NAV-TOKEN seam."
  (:require [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.interop :as interop]
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
`[:rf/runtime :routing :current :nav-token]` and injected under
`:coeffects :nav-token`. Declare with `(inject-cofx :nav-token)` on an
`:on-match`-reached handler; capture the value and thread it into an
async continuation so a superseding navigation suppresses the stale
result. Per Spec 012 §Navigation tokens — stale-result suppression."})

(defn nav-token-cofx
  "Handler fn for the `:nav-token` cofx. Reads the current navigation
  epoch token from the injected `:db` coeffect (the runtime pre-populates
  `:coeffects :db` with the frame's `app-db` value before the
  interceptor chain runs) and injects it under `:coeffects :nav-token`.

  1-arity is the canonical form. 2-arity accepts an explicit value
  override — useful in tests / conformance harnesses that want to assert
  the threading shape without standing up a route slice.

  Meaningful only inside a handler reached via an `:on-match` drain (or a
  follow-up of one), where `[:rf/runtime :routing :current :nav-token]`
  holds the epoch the navigation cascade allocated. Read from any other
  handler it reflects whatever navigation is currently active — which is
  the correct \"is this still the live navigation?\" reading the
  stale-suppression pattern wants."
  ([ctx]
   (let [db    (get-in ctx [:coeffects :db])
         token (get-in db [:rf/runtime :routing :current :nav-token])]
     (assoc-in ctx [:coeffects :nav-token] token)))
  ([ctx token]
   (assoc-in ctx [:coeffects :nav-token] token)))

(defn simulate-http-resolution-handler
  "`:rf.test/simulate-http-resolution` event-fx handler. Registered by
  the façade so a `:reload` re-wires it on a fresh registrar."
  [{:keys [db frame]} [_ {:keys [on-success-event carried-nav-token]}]]
  (let [current (get-in db [:rf/runtime :routing :current :nav-token])]
    (cond
      (= carried-nav-token current)
      ;; Token matches — dispatch the continuation.
      {:fx [[:dispatch on-success-event]]}

      :else
      ;; Stale — suppress.
      ;; rf2-7d30s — frame-attribute the suppression (matches the
      ;; production `with-nav-token-handler` path) so it lands in the
      ;; emitting frame's epoch / Xray.
      (do (trace/emit-error! :rf.route.nav-token/stale-suppressed
                             (cond-> {:carried-token     carried-nav-token
                                      :current-token     current
                                      :rf.trace/event-id (when (vector? on-success-event)
                                                           (first on-success-event))
                                      :recovery          :replaced-with-default}
                               frame (assoc :frame frame)))
          {}))))

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

(def with-nav-token-meta
  "Metadata for the `:rf.route/with-nav-token` fx registration: the
  docstring + the inline Malli schema per Spec-Schemas.md
  §`:rf.fx/with-nav-token-args`. Inline rather than a registered
  schema-id so validation works in consumers that don't pre-register the
  keyword in their Malli registry; the registered-id form remains
  available to apps that want to centralise schemas (per Spec 010
  §Schema registration)."
  {:doc  "Per Spec 012 §Navigation tokens. Threads the carried
`:nav-token` against the current `[:rf/runtime :routing :current :nav-token]`. Match → run
`:do` (any fx entry); mismatch → suppress and emit
`:rf.route.nav-token/stale-suppressed`."
   :schema [:map
            [:do        [:vector :any]]
            [:nav-token :any]]})

(defn with-nav-token-handler
  "`:rf.route/with-nav-token` fx handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar."
  [{:keys [frame] :as _ctx} args]
  ;; Destructure `:do` via `get` rather than `:keys` so the binding name
  ;; doesn't shadow `clojure.core/do` inside the body. Per Spec 012
  ;; §Threading the `:do` slot is the wrapped fx entry to perform.
  (let [do-entry        (get args :do)
        nav-token       (get args :nav-token)
        frame-id        (or frame :rf/default)
        frame-record    (frame/frame frame-id)
        db              (frame/frame-app-db-value frame-id)
        current         (get-in db [:rf/runtime :routing :current :nav-token])]
    (cond
      (= nav-token current)
      ;; Token matches — route the inner fx entry through
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

      :else
      ;; Stale — suppress. Same trace shape as
      ;; `:rf.test/simulate-http-resolution` so a single conformance
      ;; assertion covers both production and test paths.
      ;; rf2-7d30s — `frame-id` (resolved above) frame-attributes the
      ;; suppression so it lands in the emitting frame's epoch / Xray.
      (trace/emit-error! :rf.route.nav-token/stale-suppressed
                         {:carried-token     nav-token
                          :current-token     current
                          :rf.trace/event-id (inner-fx-event-id do-entry)
                          :frame             frame-id
                          :recovery          :replaced-with-default}))))
