(ns re-frame.routing.nav-fx
  "Standard navigation fxs (`:rf.nav/push-url`, `:rf.nav/replace-url`)
  + URL-owner resolution for re-frame2 routing.

  Per Spec 012 §Multi-frame routing and rf2-w50qm: `:rf.nav/push-url` /
  `:rf.nav/replace-url` MUST consult the calling frame's `:url-bound?`
  metadata before touching the browser history. The default frame
  (`:rf/default`) is URL-bound; non-default frames are not, unless they
  opt in via `(reg-frame :my-frame {:url-bound? true})`. Non-URL-bound
  frames no-op the fx (history.pushState would race with the
  URL-owning frame). The check honours the framework default:
  `:rf/default` is URL-bound when no explicit `:url-bound?` slot is
  declared.

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the two `fx/reg-fx` calls so a `:reload` re-wires them on
  a fresh registrar. Per the rf2-2yabr cohesion split: NAV-FX seam."
  (:require [re-frame.registrar :as registrar]
            [re-frame.trace :as trace]))

(defn url-bound?-from-config
  "Read `:url-bound?` from a frame's stored config map. `nil` when
  unset."
  [config]
  (when (map? config)
    (:url-bound? config)))

;; ---- URL-ownership claim order (rf2-3l7xxz) --------------------------------
;;
;; The browser URL has exactly ONE owner; the spec (Spec 012 §Multi-frame
;; routing) says when a SECOND `:url-bound? true` frame registers, the
;; **existing owner is unchanged** and the duplicate's history-mutation fxs
;; no-op (the duplicate may still update its own route slice). So ownership
;; must resolve to the frame that claimed `:url-bound? true` FIRST — the
;; incumbent — never to whichever id happens to sort earlier.
;;
;; The registrar's `kind->id->metadata` is an UNORDERED map (`re-frame.
;; registrar`), so registration order is not recoverable from it. The prior
;; resolver sorted the `:url-bound? true` frames by `(str id)` and took the
;; first — which let a LATER duplicate whose id sorts BEFORE the incumbent
;; STEAL ownership (rf2-3l7xxz): outbound history pushes and inbound popstate
;; would then target the wrong frame, the exact thrash the "existing owner is
;; unchanged" rule forbids.
;;
;; The fix records claim ORDER in this process-global vector. The url-bound
;; exclusivity registration hook (`re-frame.routing.url-bound`, which already
;; runs on every `:frame` registration) calls `record-url-claim!` / `drop-
;; url-claim!` so the vector tracks, in claim order, which frames currently
;; carry `:url-bound? true`. Process-global like routing's other
;; intentionally-cross-frame slots (the route `reg-counter`, the
;; `route-table-cache` — Spec 012 §Process-global slots are intentionally not
;; per-frame): the browser URL is one process resource, so its single owner is
;; resolved across all frames, not per-frame.
;;
;; `url-owner-frame-id` returns the FIRST still-valid claimant — validated
;; against the LIVE registry so the resolver self-heals if the incumbent later
;; drops its binding (re-registers `:url-bound? false`) or is unregistered:
;; ownership then falls to the next-claimed frame that still carries
;; `:url-bound? true`, in claim order. Fail-closed: a frame never owns the URL
;; unless it is the first-claimed live `:url-bound? true` frame.
;;
;; rf2-68k8as — the hook is a FUTURE observer (`add-registration-hook!` does not
;; replay), so a frame that claimed `:url-bound? true` BEFORE routing loaded has
;; no entry here. The façade calls `reconcile-existing-url-bindings!` right after
;; installing the hook to seed the unambiguous pre-existing incumbent. The
;; claim-free resolver fallback below NEVER picks by id sort (the old steal bug):
;; a sole bound frame owns, but 2+ bound frames with unrecoverable claim order
;; fail closed to nil.

(defonce ^:private url-claim-order
  ;; A vector of frame-ids in the order they first claimed `:url-bound? true`,
  ;; with no duplicates. The HEAD that still carries a live `:url-bound? true`
  ;; binding is the URL owner. Process-global (the browser URL is one process
  ;; resource); reset between tests via `reset-url-claims!` (wired into the
  ;; shared reset-hook table) so a prior test's claim cannot leak.
  (atom []))

(defn record-url-claim!
  "Record `frame-id` as having claimed `:url-bound? true`, appended to
  `url-claim-order` iff not already present (so a hot-reload re-registration of
  an existing owner keeps its claim position — it does NOT jump to the back,
  and a later duplicate never displaces it). Returns nil. Called by the
  url-bound exclusivity registration hook (rf2-3l7xxz)."
  [frame-id]
  (swap! url-claim-order
         (fn [order]
           (if (some #(= frame-id %) order)
             order
             (conj order frame-id))))
  nil)

(defn drop-url-claim!
  "Drop `frame-id` from `url-claim-order` — called when a frame re-registers
  WITHOUT `:url-bound? true` (it relinquished the binding) or is torn down, so
  a stale claim cannot keep a non-bound frame at the head of the order.
  Idempotent. Returns nil (rf2-3l7xxz)."
  [frame-id]
  (swap! url-claim-order (fn [order] (filterv #(not= frame-id %) order)))
  nil)

(defn reset-url-claims!
  "Test-time helper: drop the whole `url-claim-order` vector so a prior test's
  URL-ownership claim does not leak into the next (test isolation, rf2-3l7xxz).
  Wired into the shared `make-reset-runtime-fixture` reset-hook table via the
  `:routing/reset-url-claims!` late-bind key. Returns nil."
  []
  (reset! url-claim-order [])
  nil)

(defn url-owner-frame-id
  "Return the single frame that has EXPLICITLY declared browser-history
  ownership via `(reg-frame :id {:url-bound? true})`, or `nil` when no
  frame has declared it.

  EP-0002 (rf2-nn0jqa) — URL ownership is an explicit host/bootstrap
  policy, NOT an absence repair. The prior contract anchored ownership on
  `:rf/default` (the default frame owned the URL unless it opted out);
  under the carried invariant the runtime must NOT infer `:rf/default` as
  the owner. An app declares its one URL-owning frame explicitly; `nil`
  here means no owner is declared (a routing-config state, surfaced by the
  callers — outbound history mutations no-op, the inbound popstate listener
  skips). `:rf/default` may still BE the owner, but only when it carries an
  explicit `{:url-bound? true}` like any other frame.

  rf2-3l7xxz — ownership resolves to the FIRST-CLAIMED still-live
  `:url-bound? true` frame (the incumbent), NOT the alphabetically-first.
  When a second `:url-bound? true` frame registers, Spec 012 §Multi-frame
  routing says the existing owner is UNCHANGED and the duplicate's
  history-mutation fxs no-op — so a later duplicate whose id sorts before the
  incumbent must NOT steal the URL (the bug the prior `(sort-by (str id))`
  resolver had). Claim order is tracked in `url-claim-order` (recorded by the
  url-bound exclusivity hook); this resolver walks it in claim order and
  returns the first id that STILL carries a live `:url-bound? true` binding in
  the registry — so the incumbent always wins, and ownership self-heals to the
  next claimant if the incumbent later drops its binding or is unregistered.

  rf2-68k8as — when no claim is recorded yet (frames registered before the
  hook installed AND before the façade's `reconcile-existing-url-bindings!`
  seeded them), the claim-free fallback NEVER steals: a sole bound frame owns
  (order is trivially known), but TWO OR MORE bound frames with unknowable
  claim order FAIL CLOSED to nil rather than id-sorting. The old id-sort
  fallback was the URL-owner-steal bug — a later duplicate that sorted before
  the true first-claimant won the alphabetical tiebreak and stole the URL.

  Public (rather than `defn-`) so the ownership-resolution contract is
  directly assertable — the single declared-owner case the step-deck
  testbed relies on (rf2-6qgbs.3). A reimplemented gate cannot catch a
  regression in THIS resolution; the test must reach the real fn."
  []
  (let [frames (registrar/registrations :frame)
        bound? (fn [frame-id]
                 (true? (url-bound?-from-config (get frames frame-id))))]
    ;; Walk the claim order; the first frame whose binding is still live owns
    ;; the URL. A claimed-but-since-relinquished/destroyed frame is skipped
    ;; (self-healing).
    ;;
    ;; rf2-68k8as — when NO claim is recorded yet but `:url-bound? true`
    ;; frame(s) exist in the registry (frames registered before the hook was
    ;; installed and before the façade's `reconcile-existing-url-bindings!`
    ;; seeded them, or a raw programmatic path), fall back to claim-free
    ;; resolution that NEVER steals:
    ;;   - exactly ONE bound frame → it owns (order is trivially known);
    ;;   - TWO OR MORE bound frames → claim order is genuinely unknowable
    ;;     (the registrar map is unordered), so FAIL CLOSED to nil rather than
    ;;     picking by id sort. The prior `(sort-by (str id))` fallback WAS the
    ;;     URL-owner-steal bug: a later duplicate whose id sorted before the
    ;;     true first-claimant won the alphabetical tiebreak and stole the URL,
    ;;     exactly what Spec 012 §Multi-frame routing forbids ('resolving by id
    ;;     ordering would have let it'). nil here means 'no deterministic owner
    ;;     for this ambiguous load-order' — outbound pushes no-op and popstate
    ;;     skips until one binding is re-registered/removed through the live
    ;;     hook, which re-establishes a deterministic claim order.
    ;; Normal operation (the façade installs the hook AND reconciles at load
    ;; time) never reaches the multi-binding fallback — every claim is recorded
    ;; in `url-claim-order`, so the first `some` branch resolves the incumbent.
    (or (some (fn [frame-id] (when (bound? frame-id) frame-id))
              @url-claim-order)
        (let [bound (->> frames
                         (filter (fn [[_id meta]]
                                   (true? (url-bound?-from-config meta))))
                         (map first))]
          (when (= 1 (count bound))
            (first bound))))))

(defn url-bound-frame?
  "Return true when the frame named `frame-id` is the one active URL
  owner. Per Spec 012 §Multi-frame routing, duplicate `:url-bound? true`
  declarations are reported AND non-owners are prevented from pushing.

  EP-0002 — `nil` frame-id (or no declared owner) is never the owner: the
  runtime no longer synthesises `:rf/default` ownership from absence."
  [frame-id]
  (and (some? frame-id)
       (= frame-id (url-owner-frame-id))))

;; `:rf.nav/push-url` and `:rf.nav/replace-url` share one body: gate on
;; the calling frame's URL ownership, then either drive the browser
;; history (CLJS) or emit the standard `:rf.fx/skipped-on-platform`
;; trace (JVM / non-owner). The ONLY per-fx variation is the history
;; method (`history.pushState` vs `history.replaceState`) and the
;; `:rf.fx/id` tag, so the body lives once in `history-mutation-handler`
;; and each handler closes over its method + fx-id.

#?(:cljs
   (defn- run-history-mutation!
     "Drive a `window.history` mutation thunk under a shared
     defence-in-depth try/catch (CLJS only). A browser throws
     `SecurityError` when asked to push/replace an absolute cross-origin
     URL (or on other history-API misuse) — left uncaught that crashes
     the fx drain (a DoS, worse than the redirect it would otherwise be).
     The `url/external-url?` gate at the nav-event sinks (rf2-cylse.4)
     already fails such URLs closed before they reach these fxs, so this
     catch is a second line of defence: any residual throw is downgraded
     to a structured `:rf.fx/<fx-id>-failed` trace, keeping the drain
     alive. Factoring it here means BOTH `:rf.nav/push-url` and
     `:rf.nav/replace-url` share one mutation-safety boundary, rather than
     push having a catch and replace running bare (rf2-u8qe7y finding 2)."
     [failed-trace-id fx-id frame url mutate!]
     (try
       (mutate!)
       (catch :default e
         (trace/emit! :rf.fx failed-trace-id
                      (cond-> {:rf.fx/id fx-id :url url :error (.-message e)}
                        frame (assoc :frame frame)))))))

(defn- history-mutation-handler
  "Shared body for the `:rf.nav/push-url` / `:rf.nav/replace-url` fx
  handlers. `fx-id` tags the platform-skip / non-owner traces and (with
  the `-failed` suffix) the CLJS mutation-failure trace; `failed-trace-id`
  is that pre-built `:rf.fx/<fx-id>-failed` keyword. `mutate!` is the
  0-arg thunk that drives `window.history` on CLJS (a no-op on JVM — the
  caller passes a CLJS-only thunk under a reader conditional); on CLJS it
  is run through `run-history-mutation!` so a browser throw fails closed
  to the structured failure trace rather than crashing the fx drain.
  Non-URL-bound frames skip the history mutation: the frame's route slice
  at `[:rf.runtime/routing :current]` still updates — only the browser-URL
  sync is suppressed. Per Spec 012 §Multi-frame routing this is the right
  default for story-variant / devcard / per-test fixtures."
  [fx-id failed-trace-id frame url mutate!]
  (if (url-bound-frame? frame)
    #?(:cljs (run-history-mutation! failed-trace-id fx-id frame url mutate!)
       :clj  (trace/emit! :rf.fx :rf.fx/skipped-on-platform
                          {:rf.fx/id fx-id :url url}))
    (trace/emit! :rf.fx :rf.fx/skipped-on-platform
                 {:rf.fx/id fx-id
                  :url      url
                  :frame    frame
                  :reason   :frame-not-url-bound})))

(def push-url-meta
  "Metadata for the `:rf.nav/push-url` fx registration. Spec 012
  §Multi-frame routing (rf2-w50qm).

  EP-0015 note (rf2-1wmni6 / rf2-pbbo68): the arg is a full app URL string.
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
  `re-frame.routing.egress/redact-url-carriers` (rf2-n1f4rh / rf2-jfaucw)."
  {:platforms #{:client}
   :doc       "Push the URL to the browser history (HTML5 pushState).
Honours the calling frame's `:url-bound?` metadata: non-URL-bound frames
no-op the fx so they don't race with the URL-owning frame (per Spec 012
§Multi-frame routing — rf2-w50qm)."})

(defn push-url-handler
  "`:rf.nav/push-url` fx handler. Registered by the façade so a `:reload`
  re-wires it on a fresh registrar.

  rf2-cylse.4 (defence-in-depth): the `.pushState` call runs through the
  shared `run-history-mutation!` try/catch (factored in
  `history-mutation-handler`, rf2-u8qe7y finding 2). A browser throws
  `SecurityError` when asked to push an absolute cross-origin URL — left
  uncaught that crashes the fx drain (a DoS, worse than the redirect it
  would otherwise be). The `url/external-url?` gate at the nav-event sinks
  (rf2-cylse.4) already fails such URLs closed before they reach this fx,
  so this catch is a second line of defence: any residual throw is
  downgraded to a `:rf.fx/push-url-failed` trace, keeping the drain
  alive."
  [{:keys [frame]} url]
  (history-mutation-handler
    :rf.nav/push-url :rf.fx/push-url-failed frame url
    #?(:cljs #(.pushState js/window.history nil "" url)
       :clj nil)))

(def replace-url-meta
  "Metadata for the `:rf.nav/replace-url` fx registration. Spec 012
  §Multi-frame routing (rf2-w50qm).

  EP-0015 note (rf2-1wmni6 / rf2-pbbo68): same behavioural-identity URL arg
  as `:rf.nav/push-url` — deliberately NOT marked `:sensitive` for the same
  reason (the `:effects-routed` contract asserts the real routed URL, the
  open-redirect gate already cleared it, and a blanket redaction over-reaches
  bare paths). Carrier-bearing route-miss / blocked URLs are scrubbed at
  their diagnostic emit sites (`egress/redact-url-carriers`)."
  {:platforms #{:client}
   :doc       "Replace the URL in the browser history (HTML5 replaceState).
Honours the calling frame's `:url-bound?` metadata: non-URL-bound frames
no-op the fx so they don't race with the URL-owning frame (per Spec 012
§Multi-frame routing — rf2-w50qm)."})

(defn replace-url-handler
  "`:rf.nav/replace-url` fx handler. Registered by the façade so a
  `:reload` re-wires it on a fresh registrar.

  rf2-u8qe7y finding 2 (defence-in-depth): the `.replaceState` call runs
  through the SAME shared `run-history-mutation!` try/catch as
  `:rf.nav/push-url`, so a browser throw (`SecurityError` on a residual
  cross-origin URL, jsdom/stub mismatch, invalid-URL restriction) fails
  closed to a `:rf.fx/replace-url-failed` trace instead of escaping the fx
  drain. Previously replace ran the history method bare while push had the
  catch — the two sibling history fxs had asymmetric drain-survival
  behaviour under the same failure class."
  [{:keys [frame]} url]
  (history-mutation-handler
    :rf.nav/replace-url :rf.fx/replace-url-failed frame url
    #?(:cljs #(.replaceState js/window.history nil "" url) :clj nil)))
