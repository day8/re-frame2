(ns re-frame.ui.viewcell
  "React glue for the S2b reactive core — the thin `useRef` /
  `useSyncExternalStore` / `useLayoutEffect` layer that drives
  `re-frame.ui.reactive`'s host-agnostic ViewCell + commit reconciler.
  The compiled CLJS emitter selects one exact PRODUCTION wrapper by capability:
  the sub combinations own a ViewCell, event combinations own a committed
  EventOwner, and the combined forms own exactly both. Reactive and event sites
  ALSO consume the frame context — every sub/event site is an ambient-
  frame reader. `render-frame` covers a frame-only view (a `(frame)` site but no
  sub/event), while a genuinely inert view has no wrapper at all.
  In DEV the stable Fast Refresh Inner always calls `render-dev`, providing the
  fixed superset hook skeleton before a view gains or loses any capability
  without charging production for it.

  The contract, per 03 §3:

    - ONE `useSyncExternalStore` per view (all sites share the cell's
      scalar revision snapshot). A revision advance re-renders the
      memoized component even when props are `rf=` (the sub-driven repaint
      channel, distinct from the prop-driven one memo gates).
    - render RESOLVES + PROBES without ownership (via `reactive/with-capture`
      → `sub` → the observation port's `resolve-target`/`probe`); the
      LAYOUT commit acquires the CAPTURED targets. Each render's effect closes
      over that render's exact immutable capture. Abandoned renders (StrictMode
      double-render, time-sliced tear-off) acquire no ownership and publish no
      speculative capture state to the ViewCell.
    - the reconcile layout effect runs after EVERY committed render and is
      idempotent (kept-check retains unchanged leases untouched); the
      lifecycle layout effect (empty deps) owns connect/disconnect, so
      React unmount / Activity hide releases owners and reveal reacquires
      and corrects before paint.

  ## Host checkpoint → React (S2d)

  Sub deltas do NOT re-render synchronously. A moving site's `on-change`
  (registered at commit) marks the cell dirty in `reactive`'s dirty
  registry (constant-work, coalesced once per cell per PENDING WINDOW — the
  window closes at the next host checkpoint, so every epoch a
  run-to-completion drain commits folds into one render batch, as does any
  further drain finishing before that checkpoint; rf2-vxgfnd.166, 03 §3);
  one flush on the host MICROTASK queue (`reactive/schedule-flush!` →
  `queue-microtask!`, a true microtask that runs before the next paint —
  rf2-vxgfnd.40, NOT `goog.async.nextTick`) advances the cell's revision,
  `getSnapshot` moves, and this component re-renders — so a watch-fired
  movement is corrected before a torn frame can show. React BATCHING is
  inherited, not
  hand-rolled: `useSyncExternalStore` routes every revision advance
  through React's own scheduler, so N cells flushed in one batch settle in
  ONE render pass, and adding an explicit batch wrapper here would only
  fight React's ownership of the schedule. The synchronous forcing door is
  `reactive/flush-frame!` / `ui.test/flush!` (the Q51 scope ruling —
  03 §3); the commit reconciler's own step-8 advance already runs inside
  this layout effect (React's commit phase), correcting moved evidence
  before paint without any flush call."
  (:require ["react" :as react]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.ui.events :as events]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.sub-overrides :as sub-overrides]))

;; ---------------------------------------------------------------------------
;; Root-incarnation context (03 §4; rf2-vxgfnd.85/.92)
;;
;; The ROOT scoping for per-cell root-incarnation ownership. `client/mount!`
;; mints ONE incarnation per Root (`reactive/make-root-incarnation`) and wraps
;; the root element in this context's Provider, so EVERY ViewCell under a root —
;; across the whole tree, however deeply nested — reads the SAME incarnation via
;; `useContext` and enrols under it (`attach-root!`). That true root scope is why
;; an entirely-hidden sibling subtree is still reaped at root unmount: the
;; ownership follows the ROOT, not the topmost still-mounted subtree. nil above a
;; root that supplies no provider (bare/test mounts) — `attach-root!` is skipped.
;; ---------------------------------------------------------------------------

(defonce root-incarnation-context
  (react/createContext nil))

(defn provide-root-incarnation
  "Wrap `element` in the root-incarnation context Provider carrying
  `incarnation` — the seam `client/mount!` (and `render!`) render through so
  every ViewCell in the root's tree shares ONE incarnation (rf2-vxgfnd.92).
  A nil `incarnation` still provides (a root with no reactive cells is inert)."
  [incarnation element]
  (react/createElement (.-Provider root-incarnation-context)
                       #js {:value incarnation}
                       element))

(defn- use-cell
  "Create/read this component instance's ViewCell and root-incarnation.

  This is the common two-hook prefix shared by every wrapped production shape
  and the stable dev shell.  Keeping the capability-specific hooks out of this
  helper makes their absence structural in advanced output."
  [view-id]
  (let [body-revision (if ^boolean js/goog.DEBUG
                        (reactive/view-generation view-id)
                        0)
        cell-ref (react/useRef nil)]
    (when (nil? (.-current cell-ref))
      (set! (.-current cell-ref)
            (reactive/make-cell view-id body-revision)))
    (let [cell             (.-current cell-ref)
          root-incarnation (react/useContext root-incarnation-context)]
      ;; Same-signature Fast Refresh keeps this Fiber/ViewCell but advances the
      ;; body revision. Sync BEFORE capture so the selected render records the
      ;; exact descriptor revision it executed. Production folds this branch
      ;; away with the entire HMR slot.
      (when ^boolean js/goog.DEBUG
        (reactive/advance-generation! cell body-revision))
      [cell root-incarnation])))

(defn- use-sub-revision!
  "Install the one aggregated subscription hook and return Story overrides."
  [cell]
  (let [overrides (sub-overrides/use-current)
        subscribe (react/useCallback
                    (fn [listener] (reactive/subscribe cell listener))
                    #js [cell])]
    (react/useSyncExternalStore subscribe
                                (fn [] (reactive/get-snapshot cell))
                                (fn [] 0))
    overrides))

(defn- capture-with-overrides
  "Capture a sub-bearing body under Story's dev-only mounted override door."
  [cell overrides thunk]
  (if interop/debug-enabled?
    (do
      ;; Stable bundle sentinel: the advanced production gate asserts this
      ;; whole debug branch is absent.
      (when (and (some? overrides) (not (map? overrides)))
        (throw
         (js/Error. "rf-ui-mounted-override-binding expects a map")))
      (binding [reactive/*sub-overrides* overrides]
        (reactive/with-capture cell thunk)))
    (reactive/with-capture cell thunk)))

(defn- use-commit-and-lifecycle!
  "Publish an observation-only capture and own React visibility lifecycle."
  [cell root-incarnation capture]
  ;; Reconcile after every committed render. There is deliberately no cleanup:
  ;; retained observation owners live on the cell, not on a render.
  (react/useLayoutEffect
    (fn reconcile []
      (reactive/commit! cell capture)
      js/undefined))
  ;; Connect via commit above; cleanup releases ownership on both unmount and
  ;; Activity hide. Root enrolment deliberately survives hide so root teardown
  ;; can reap an entirely-hidden subtree.
  (react/useLayoutEffect
    (fn lifecycle []
      (when (some? root-incarnation)
        (reactive/attach-root! cell root-incarnation))
      (fn cleanup [] (reactive/disconnect! cell)))
    #js [root-incarnation]))

(defn- use-frame-context!
  "Register the calling component as a real React CONSUMER of the shared frame
  context (rf2-vxgfnd.228/.253).

  A compiled view whose body resolves the AMBIENT frame MUST re-render when an
  ancestor `frame-provider` RETARGETS its frame (A→B), even when the view's own
  props stay `rf=`-equal. That is EVERY reactive view, not only `(frame)` /
  `frame-ops` sites: a `sub` target resolves against the ambient frame
  (rf2-vxgfnd.253) — so `render-subs` and `render-subs-and-events` consume this
  context too. These sites read the
  context value through the carried-invariant chain / `_currentValue`, which does
  NOT subscribe the component to context changes; only `useContext` does. So
  without this hook React correctly memo-bails the non-consumer child on a pure
  provider retarget, its body never reruns, and its held ops / subscriptions
  stay locked to the OLD frame (Spec 004-Views §context-change
  repaint; the same discipline `re-frame.adapter.use-frame/use-frame` uses).

  The read VALUE is RETAINED (rf2-4rwtd): event wrappers thread it into
  `events/with-capture` as the committed destination, and every sub wrapper
  binds it into `re-frame.frame/*current-frame*` around the compiled body
  (`with-current-frame`) so ambient `(sub …)` resolution hits the
  precedence-first dynamic tier. Called as a compile-time-selected LEADING hook,
  so it is stable in hook order for every render of a given compiled view's
  component (never conditional at runtime).

  The frame VALUE is the PUBLIC `useContext` RETURN (rf2-2rzx0), not a re-read
  of the private `_currentValue` slot. `useContext` is renderer-agnostic — it
  resolves the closest Provider under BOTH the client renderer and
  `react-dom/server`. `function-component-current-frame` reads `_currentValue`
  directly, which React 19.2's SERVER renderer does NOT populate (it uses
  `_currentValue2`), so sourcing the frame there lost the Provider under
  `react-dom/server` and a server-rendered compiled sub ViewCell could
  still raise `:rf.error/no-frame-context`. Resolution keeps the same explicit
  precedence and the same validation: an ambient `frame/*current-frame*` still
  wins, otherwise the hook value flows through the SHARED sentinel / coercion /
  corruption rules (`adapter-context/context-value->current-frame`) — identical
  to the reader's classification, since on the client `useContext` observes the
  same value `_currentValue` holds."
  []
  (let [ctx-frame (react/useContext adapter-context/frame-context)]
    ;; Dynamic binding before React context (the carried-invariant precedence);
    ;; the single `useContext` call above owns the repaint SUBSCRIPTION and now
    ;; also supplies the renderer-agnostic frame value.
    (or frame/*current-frame*
        (adapter-context/context-value->current-frame ctx-frame))))

(defn- with-current-frame
  "Wrap compiled body `thunk` so `re-frame.frame/*current-frame*` is bound to
  `frame-id` (the ViewCell's React-context frame, read by `use-frame-context!`)
  for the DURATION of the body evaluation (rf2-4rwtd).

  Ambient `(sub …)` sites in the body resolve their frame through
  `frame/require-current-frame!` → `resolve-current-frame` → the installed
  adapter's `:adapter/current-frame` reader. EVERY such reader consults the
  dynamic tier FIRST (`(or frame/*current-frame* …)` — the function-component,
  Reagent class-component, and plain-atom readers alike), so establishing this
  binding makes ambient resolution correct REGARDLESS of which adapter's reader
  the hook routes to. Without it a compiled view's sub reads raise
  `:rf.error/no-frame-context` under a ratom-family (Reagent / reagent-slim)
  host, whose reader returns nil for a ui function component's `(.-context cmp)`
  / provider ratom (a sub-read-only asymmetry — ui's own event / `(frame)`
  paths resolve via the direct context reader and are immune).

  Scoped to the SYNCHRONOUS body eval only: a compiled view's children are
  ELEMENTS React renders LATER, outside this dynamic extent, each re-establishing
  its OWN frame — so no ambient frame leaks across React's lazy child rendering."
  [frame-id thunk]
  (fn [] (binding [frame/*current-frame* frame-id] (thunk))))

;; ---------------------------------------------------------------------------
;; Committed event sites (S3a)
;; ---------------------------------------------------------------------------

;; A shared, never-mutated empty deps array for mount-only layout effects.
;; React only reads the deps element-wise, so one instance serves every event
;; view and saves an array allocation on every render.
(def ^:private empty-deps #js [])

(defn- use-event-owner
  [view-id]
  (let [owner-ref (react/useRef nil)]
    (when (nil? (.-current owner-ref))
      (set! (.-current owner-ref) (events/make-owner view-id)))
    (.-current owner-ref)))

(defn- use-event-commit-and-lifecycle!
  [owner capture]
  ;; Each effect closes over this render's immutable capture. An abandoned
  ;; render never reaches this publication point.
  (react/useLayoutEffect
    (fn commit-events []
      (events/commit! owner capture)
      js/undefined))
  (react/useLayoutEffect
    (fn event-lifecycle []
      (fn cleanup [] (events/disconnect! owner)))
    empty-deps))

(defn- capture-reactive-and-events
  [owner frame-id capture-reactive]
  (let [[[element reactive-capture] event-capture]
        (events/with-capture owner frame-id capture-reactive)]
    [element reactive-capture event-capture]))

(defn render-events
  "Production wrapper for an event-bearing view with no reactive sites.  It is
  a real frame-context consumer so provider retargets produce a fresh candidate
  whose locked destination is published at commit.

  The `useContext` frame flows two ways from the single leading hook: it is the
  committed event destination threaded into `events/with-capture`, AND it is
  BOUND into `re-frame.frame/*current-frame*` around the synchronous body
  (`with-current-frame`) so a `(frame)` site the body also carries — the
  event+`(frame)` view this wrapper is selected for — resolves the provider
  through the dynamic tier, not the `_currentValue` slot `react-dom/server`
  leaves empty (rf2-wobnf). The bound thunk is what `with-capture` evaluates, so
  event capture still sees the same frame it always did."
  [view-id thunk]
  (let [frame-id                (use-frame-context!)
        owner                   (use-event-owner view-id)
        [element event-capture] (events/with-capture owner frame-id
                                                      (with-current-frame frame-id thunk))]
    (use-event-commit-and-lifecycle! owner event-capture)
    element))

(defn render-subs-and-events
  [view-id thunk]
  (let [frame-id               (use-frame-context!)
        [cell root-incarnation] (use-cell view-id)
        overrides              (use-sub-revision! cell)
        owner                  (use-event-owner view-id)
        [element capture event-capture]
        (capture-reactive-and-events
         owner frame-id
         #(capture-with-overrides cell overrides (with-current-frame frame-id thunk)))]
    (use-commit-and-lifecycle! cell root-incarnation capture)
    (use-event-commit-and-lifecycle! owner event-capture)
    element))

(defn render-frame
  "Production wrapper for a FRAME-ONLY view: a `(frame)` site but no sub sites
  (rf2-vxgfnd.228). It observes no reactive source, so it needs no ViewCell —
  only frame-context CONSUMPTION so a provider retarget re-renders it.
  Structurally zero `useSyncExternalStore` / passive hooks; one `useContext`.

  The `useContext` frame is BOUND into `re-frame.frame/*current-frame*` around
  the synchronous body (`with-current-frame`) — like every sub wrapper — so the
  body's `(frame)` site resolves the closest live provider through the dynamic
  tier rather than the private `_currentValue` slot, which `react-dom/server`
  leaves empty under React 19.2 (it populates `_currentValue2`). Without the
  binding a server-rendered frame-only view raised `:rf.error/no-frame-context`
  even under a live `frame-provider` (rf2-wobnf, extending the rf2-2rzx0 hook
  fix into this wrapper)."
  [_view-id thunk]
  (let [frame-id (use-frame-context!)]
    ((with-current-frame frame-id thunk))))

(defn render-subs
  "Production wrapper for a view with sub sites.

  Consumes the shared frame context (leading `use-frame-context!`): every sub
  target resolves against the AMBIENT frame, so an ancestor `frame-provider`
  RETARGET (A→B) must re-render this view even when its own props stay
  `rf=`-equal, or `React.memo` bails the non-consumer and the ViewCell stays
  subscribed to A (rf2-vxgfnd.253, extending .228 from frame-only views to every
  ambient-frame reader). Any `(frame)` site the body also carries rides the same
  single subscription — there is no separate `-frame` wrapper."
  [view-id thunk]
  (let [frame-id               (use-frame-context!)
        [cell root-incarnation] (use-cell view-id)
        overrides              (use-sub-revision! cell)
        [element capture]      (capture-with-overrides
                                cell overrides (with-current-frame frame-id thunk))]
    (use-commit-and-lifecycle! cell root-incarnation capture)
    element))

(defn render-dev
  "Stable Fast Refresh wrapper: the full hook superset regardless of the
  currently-compiled body capabilities — including frame-context consumption
  (rf2-vxgfnd.228), so a body that gains or loses its `(frame)` site across an
  HMR edit keeps ONE stable hook signature and a dev view always repaints on a
  provider retarget."
  [view-id thunk]
  (let [frame-id               (use-frame-context!)
        [cell root-incarnation] (use-cell view-id)
        overrides              (use-sub-revision! cell)
        owner                  (use-event-owner view-id)
        [element capture event-capture]
        (capture-reactive-and-events
         owner frame-id
         #(capture-with-overrides cell overrides (with-current-frame frame-id thunk)))]
    (use-commit-and-lifecycle! cell root-incarnation capture)
    (use-event-commit-and-lifecycle! owner event-capture)
    element))

(def render
  "Internal compatibility alias for pre-S2b direct-render fixtures. New
  compiled output names its exact capability wrapper."
  render-subs)
