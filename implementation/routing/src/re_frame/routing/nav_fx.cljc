(ns re-frame.routing.nav-fx
  "Standard navigation fxs (`:rf.nav/push-url`, `:rf.nav/replace-url`)
  + URL-owner resolution for re-frame2 routing.

  Per Spec 012 §Multi-frame routing, URL
  ownership is **explicit only**. `:rf.nav/push-url` / `:rf.nav/replace-url`
  MUST consult the calling frame's `:url-bound?` metadata before touching
  the browser history. A frame is URL-bound only when it carries an
  explicit `(make-frame {:id :my-frame :url-bound? true})` — including
  `:rf/default`, which has **no** owns-by-default floor: the runtime never
  infers URL ownership from absence. Non-URL-bound frames no-op the fx
  (history.pushState would race with the URL-owning frame). When no frame
  declares `:url-bound? true`, there is no URL owner and the outbound
  history mutations no-op (see `url-owner-frame-id`).

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the two `fx/reg-fx` calls so a `:reload` re-wires them on
  a fresh registrar."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.routing.nav-fx-schemas :as rf.routing.nav-fx-schemas]
            [re-frame.routing.strategy :as rf.routing.strategy]))

(defn url-bound?-from-config
  "Read `:url-bound?` from a frame's stored config map. `nil` when
  unset."
  [config]
  (when (map? config)
    (:url-bound? config)))

(defn url-owner-frame-id
  "Return the frame that has EXPLICITLY declared browser-history ownership via
  `(make-frame {:id … :url-bound? true})`, or `nil` when none has.

  URL ownership is an explicit host/bootstrap policy, not an absence repair:
  the runtime never infers `:rf/default` as the owner. `nil` means no owner is
  declared (a routing-config state, surfaced by the callers — outbound history
  mutations no-op, the inbound listener skips). `:rf/default` may still BE the
  owner, but only when it carries an explicit `{:url-bound? true}` like any
  other frame.

  The registration preflight (`re-frame.routing.url-bound`) refuses a second
  live `:url-bound? true` frame, so at most one frame in the store carries the
  binding and this reads it off the store (`rf.frame/frame-meta`, nil for a
  destroyed frame). Destroying the owner, or re-registering it with
  `:url-bound? false`, leaves no owner until a frame declares the binding.

  Public so the ownership-resolution contract is directly assertable."
  []
  (some (fn [frame-id]
          (when (true? (url-bound?-from-config (rf.frame/frame-meta frame-id)))
            frame-id))
        (rf.frame/frame-ids)))

(defn url-bound-frame?
  "Return true when the frame named `frame-id` is the URL owner. Per Spec 012
  §Multi-frame routing only the owner pushes browser history.

  `nil` frame-id (or no declared owner) is never the owner; the runtime does
  not synthesise `:rf/default` ownership from absence."
  [frame-id]
  (and (some? frame-id)
       (= frame-id (url-owner-frame-id))))

;; `:rf.nav/push-url` and `:rf.nav/replace-url` share one body: gate on
;; the calling frame's URL ownership, then drive the browser history. The
;; ONLY per-fx variation is the STRATEGY op it drives (`:push!` vs
;; `:replace!`), so the body lives once in `history-mutation-handler` and
;; each handler names its strategy-op key.
;;
;; The URL-owning frame's `:url-strategy`
;; (default `history-url-strategy`, path-form) is consulted HERE — one of
;; the four egress/ingress consult points. The pushed URL arrives PATH-FORM
;; (`route-url` and the cascade never encode). This fx is the single
;; outbound encoding authority: it encodes the path-form URL to its final href
;; ONCE via the strategy's `:encode`, then hands that href to the RAW
;; `:push!`/`:replace!` leg, which drives `window.history` WITHOUT re-encoding
;; (`history-*` = pushState/replaceState of the path unchanged; `hash-*` =
;; pushState/replaceState of the `#<path>` href it is given). So a hash app
;; pushes `#/active`, a history app `/active`, and a `/demos`-based hash app
;; `/demos#/active` (base OUTSIDE the fragment) — the same href `:encode`
;; renders into the `route-link` <a>, from the same fx, with no branch here.
;;
;; Each entry gets exactly one fx outcome, and the fx runner reports it, so
;; the body emits no trace of its own (Spec 012 §Trace events): the
;; `:platforms #{:client}` gate skips the fx on `:server` before the body
;; runs, a body that returns is `:rf.fx/handled`, and a body that throws is
;; the always-on `:rf.error/fx-handler-exception`.

(defn- history-mutation-handler
  "Shared body for the `:rf.nav/push-url` / `:rf.nav/replace-url` fx
  handlers. `strategy-op` is the strategy-map key naming the history drive
  (`:push!` or `:replace!`).

  On CLJS the URL-owning frame's `:url-strategy` is resolved, its
  `:encode` maps the PATH-FORM `url` to the final href ONCE (identity for
  history, `#`-prefix for hash, base-OUTSIDE-fragment for a based hash app),
  and that href is handed to the RAW `strategy-op` leg (`:push!` / `:replace!`)
  which drives `window.history` WITHOUT re-encoding. Non-URL-bound frames skip
  the mutation: the frame's route slice at `[:rf.runtime/routing :current]`
  still updates — only the browser-URL sync is suppressed. Per Spec 012
  §Multi-frame routing this is the right default for story-variant / devcard /
  per-test fixtures. The JVM has no `window`, so a JVM frame running as
  `:client` has no history to drive."
  [frame url strategy-op]
  (when (url-bound-frame? frame)
    #?(:cljs (let [strat (rf.routing.strategy/url-strategy-for-frame-id frame)]
               ((get strat strategy-op) ((:encode strat) url)))
       :clj  nil)))

(def push-url-meta
  "Metadata for the `:rf.nav/push-url` fx registration. Spec 012
  §Multi-frame routing.

  EP-0015 note: the arg is a full app URL string.
  We deliberately DO NOT mark it `:sensitive` — unlike the scroll fx (whose
  `:from`/`:to` descriptors are purely DIAGNOSTIC carriers the handler
  ignores), the pushed URL IS the navigation's behavioural identity: the
  Spec 012 `:effects-routed` conformance contract (and the epoch `:effects`
  projection, both derived from `:rf.fx/handled`'s `:rf.fx/args`) assert that
  `:rf.nav/push-url` routes the ACTUAL same-origin URL — which the
  open-redirect gate (`url/safe-in-app-url?`) has already cleared. A blanket
  redaction of every pushed URL would over-reach (a bare `/cart` path is not
  a carrier) AND break that behavioural surface. The carrier-bearing URL
  classes EP-0015 actually targets — the route-MISS / malformed / blocked
  URLs — are scrubbed at their diagnostic emit sites via
  `re-frame.privacy.url/redact-url-carriers`.

  Carries the `:rf.fx.nav/push-url-args` `:schema` per
  [Spec-Schemas §Standard fx args schemas] ('the `:rf.nav/*` fx ship with
  `:schema` set to the corresponding schema above'). A non-string arg is
  rejected at the Spec 010 §step-5 `:fx-args` boundary — the fx is
  skipped BEFORE `window.history` is touched — rather than reaching
  `pushState`."
  {:platforms #{:client}
   :schema    rf.routing.nav-fx-schemas/push-url-args
   :doc       "Push the URL to the browser history (HTML5 pushState).
Honours the calling frame's `:url-bound?` metadata: non-URL-bound frames
no-op the fx so they don't race with the URL-owning frame (per Spec 012
§Multi-frame routing)."})

(defn push-url-handler
  "`:rf.nav/push-url` fx handler. Registered by the façade so a `:reload`
  re-wires it on a fresh registrar.

  A browser throws `SecurityError` when asked to push an absolute
  cross-origin URL. The `url/external-url?` gate at the nav-event sinks
  fails such URLs closed before they reach this fx, so a residual throw is a
  defect worth reporting: it propagates to the fx runner, which records the
  always-on `:rf.error/fx-handler-exception` and still runs the sibling fxs."
  [{:keys [frame]} url]
  (history-mutation-handler frame url :push!))

(def replace-url-meta
  "Metadata for the `:rf.nav/replace-url` fx registration. Spec 012
  §Multi-frame routing.

  EP-0015 note: same behavioural-identity URL arg
  as `:rf.nav/push-url` — deliberately NOT marked `:sensitive` for the same
  reason (the `:effects-routed` contract asserts the real routed URL, the
  open-redirect gate already cleared it, and a blanket redaction over-reaches
  bare paths). Carrier-bearing route-miss / blocked URLs are scrubbed at
  their diagnostic emit sites (`re-frame.privacy.url/redact-url-carriers`).

  Carries the `:rf.fx.nav/replace-url-args` `:schema`, the
  same `:string` gate its `:rf.nav/push-url` sibling carries — the
  two history fxs must not have asymmetric args-validation any more than
  asymmetric failure reporting."
  {:platforms #{:client}
   :schema    rf.routing.nav-fx-schemas/replace-url-args
   :doc       "Replace the URL in the browser history (HTML5 replaceState).
Honours the calling frame's `:url-bound?` metadata: non-URL-bound frames
no-op the fx so they don't race with the URL-owning frame (per Spec 012
§Multi-frame routing)."})

(defn replace-url-handler
  "`:rf.nav/replace-url` fx handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar.

  Shares `:rf.nav/push-url`'s body, so a throwing `.replaceState`
  (`SecurityError` on a residual cross-origin URL, an invalid-URL
  restriction) reaches the fx runner the same way and is recorded as
  `:rf.error/fx-handler-exception`."
  [{:keys [frame]} url]
  (history-mutation-handler frame url :replace!))
