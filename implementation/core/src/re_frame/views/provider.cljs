(ns re-frame.views.provider
  "Frame-boundary components (Reagent shells) + per-render identity
  machinery for the Reagent-side views ns. Re-frame.views re-exports the
  publicly-referenced surface (`frame-provider`, `frame-root`,
  `build-frame-provider`, `current-frame`, `mint-instance-token!`,
  `current-render-key`, `*render-key*`).

  Four cohesive concerns live here:

  1. `frame-provider` — the Reagent SCOPE-only component, built on the
     scope-only provide tier (`build-frame-provider` /
     `frame-provider-component`) the Reagent adapter also installs at
     `:register-context-provider`.

  2. `frame-root` — the Reagent ENSURE component, a shell over
     `re-frame.views.frame-boundary`'s shared `frame-root-fc`, embedded via
     Reagent's `:r>` interop head.

     Spec 002 §`frame-provider` — the SCOPE-only component and §`frame-root`
     — the ENSURE component hold both contracts. The React Context object is
     owned by `re-frame.adapter.context`, so every substrate reads the same
     one.

  3. Per-render instance-token machinery — `mint-instance-token!`,
     `instance-token`. Tokens disambiguate concurrently-
     mounted instances of the same view-kind. The `*render-key*`
     dynamic var lives in `re-frame.views` so the wrapper's binding
     and consumer reads share the same canonical Var.

  4. `current-frame` — the resolution chain subscribe / dispatch
     consult (Spec 006 §One frame-resolution rule for React hooks). While
     a Reagent component renders, the frame boundary above it in the tree
     answers first — through the `:contextType` static `reg-view*`
     attaches, or the shared context-slot read for any other form.

  This ns does NOT statically `:require` `reagent.core`. The in-flight
  Reagent component is read through the late-bind hook
  `:adapter/current-component`; with no adapter installed the hook
  returns nil and the React-context tier of the resolution chain is
  skipped (equivalent to a non-Reagent render context — JVM /
  headless tests rely on this)."
  (:require [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.views.frame-boundary :as rf.views.frame-boundary]))

;; ---- the React context for frame propagation -----------------------------
;;
;; The React Context object lives in `re-frame.adapter.context` so
;; every React-shaped adapter reads the *same* context — not a
;; parallel one. The Reagent code below references it via the alias
;; rather than minting a new createContext call.

(def frame-context rf.adapter.context/frame-context)

(defn- frame-provider-component
  "The single Reagent component that backs the SCOPE-ONLY provide tier. It
  takes the frame keyword as its first render-time arg and scopes that
  keyword to its subtree via React context. One built component services
  every frame — the keyword lives in the Provider's `:value`, not in a
  closure. This is the substrate hook the Reagent adapter installs at
  `:register-context-provider` and the tier `frame-provider` scopes through;
  `frame-root` reaches the same Provider through
  `re-frame.adapter.context/provider-element` instead, because
  `frame-root-fc` returns a raw React element. It does NOT create or destroy
  a frame — pure scoping.

  Uses Reagent's `:r>` interop head so the props map flows to React as a
  raw JS object without passing through `reagent.impl.template/convert-
  prop-value`. That bypass is what makes a *namespaced* frame keyword
  (`:tenant/admin`) survive the React-context round trip on the classic
  Reagent adapter — stock Reagent's `convert-prop-value` calls `(name kw)`
  on named prop values, which drops the namespace before React sees the
  prop.

  Children remain hiccup — `:r>` only short-circuits prop conversion;
  children are translated by the renderer as usual. Per
  `re-frame.adapter.context/provider-element` for the React-element-
  building counterpart that adapter ns offers for substrate-side mounts."
  [frame-kw & children]
  (into [:r> (.-Provider frame-context) #js {:value frame-kw}] children))

(defn build-frame-provider
  "Used by re-frame.adapter.reagent/register-context-provider and by the
  user-facing `frame-provider` as its scope-only provide tier. Returns a
  Reagent component that SCOPES a frame keyword to its subtree (no
  create/destroy).

  Zero-arity: the returned component takes the frame keyword
  at render time, and a single built component services every frame, so
  there is nothing to specialise at build time. Substrates whose
  `register-context-provider` slot receives a frame-keyword on call (per
  Spec 006 §Frame-provider via React context) discard it and call this
  with no args."
  []
  frame-provider-component)

(defn frame-provider
  "User-facing SCOPE-only component: scope descendants to an ALREADY-CREATED
  frame, failing loud when it is absent. Per Spec 002 §`frame-provider` — the
  SCOPE-only component, which holds the `:frame` grammar and every refusal.

      [rf/frame-provider {:frame :session}
       [header]
       [main-area]]

  Children are variadic. Scopes through the shared provide tier
  (`build-frame-provider` / `frame-provider-component`), whose `:r>` head
  keeps a namespaced frame keyword intact."
  [props & children]
  (when (contains? props :id)
    (rf.views.frame-boundary/reject-frame-provider-id!
      (:id props)
      're-frame.views.provider/frame-provider))
  ;; SCOPE: validate the `:frame` target (keyword id or live frame value,
  ;; normalized to the id), fail loud if the frame is absent, then scope via
  ;; the shared scope-only provide tier.
  (let [frame-kw (rf.frame/require-frame-provider-target!
                   (:frame props)
                   're-frame.views.provider/frame-provider)]
    (rf.views.frame-boundary/require-live-frame-for-scope!
      frame-kw
      're-frame.views.provider/frame-provider)
    (into [(build-frame-provider) frame-kw] children)))

(defn frame-root
  "User-facing ENSURE component: create the frame if absent, reuse it without
  re-seeding if present, and scope it to descendants. Takes the
  `rf/make-frame` opts; `:id` is required and must be a keyword. Per Spec 002
  §`frame-root` — the ENSURE component, which holds the commit-owned two-pass
  contract and every refusal.

      [rf/frame-root {:id :session :images [session-image] :initial-events []}
       [header]
       [main-area]
       [footer]]

  A Reagent shell over the shared `rf.views.frame-boundary/frame-root-fc`,
  embedded via Reagent's `:r>` interop head: the `#js {:rfOpts …}` prop
  reaches React untouched (bypassing `convert-prop-value`, so the CLJS opts
  map survives intact), and the trailing hiccup children become the React
  children `frame-root-fc` reads from `props.children`."
  [props & children]
  (when (contains? props :frame)
    (rf.views.frame-boundary/reject-frame-root-frame!
      (:frame props)
      're-frame.views.provider/frame-root))
  ;; Validate `:id` here, so the diagnostic names this fn.
  (rf.views.frame-boundary/require-frame-root-id!
    (:id props)
    're-frame.views.provider/frame-root)
  (into [:r> rf.views.frame-boundary/frame-root-fc
         #js {:rfOpts (rf.views.frame-boundary/frame-root-opts props)}]
        children))

;; ---- in-flight Reagent component -----------------------------------------
;;
;; The Reagent adapter's map carries its Reagent build's `current-component`
;; fn as its `:current-component` capability, which `install-adapter!`
;; publishes through `:adapter/current-component`. Static
;; `:require [reagent.core]` would hard-couple this substrate-neutral ns
;; to Reagent and put Reagent on every UIx and Fresco classpath.

(defn current-component
  "Resolve the in-flight Reagent component via the active adapter's hook.
  Returns nil when no adapter has registered the hook (JVM / headless
  builds; no-adapter tests)."
  []
  ;; Sticky hook — published when the adapter is installed; called per
  ;; Reagent render path that consults its component identity.
  (when-let [hook (rf.late-bind/get-fn :adapter/current-component)]
    (hook)))

;; ---- frame resolution at render time -------------------------------------

(defn- tree-frame
  "The frame the boundary above rendering component `cmp` names, or nil.
  A component whose `:contextType` is the frame context (a `reg-view*`
  head) has React's public read in `(.-context cmp)`; every other form — a
  plain fn, a component called rather than mounted — reads the shared
  context slot. Both classify through the same rules, so a raw-hiccup
  Provider's prop-stringified keyword resolves either way."
  [cmp]
  (if (identical? frame-context (.-contextType (.-constructor ^js cmp)))
    (rf.adapter.context/context-value->current-frame (.-context ^js cmp))
    (rf.adapter.context/context-slot-frame)))

(defn current-frame
  "Resolution chain (READER) per Spec 006 §One frame-resolution rule for
  React hooks — the carried invariant (EP-0002). Returns the scope frame,
  or **nil** when no scope is established — it never synthesises
  `:rf/default` (the runtime never repairs absence). In order:

    1. While a Reagent component renders, the closest enclosing frame
       boundary — a `frame-provider` (SCOPE) or `frame-root` (ENSURE) —
       read from the tree: `(.-context cmp)` under the `:contextType`
       `reg-view*` attaches, else the shared context-slot read, so a plain
       fn resolves its provider too. The tree wins over a `with-frame`
       around a synchronous render, as it does for a hook.
    2. The dynamic var (`with-frame`, or the router's per-handler
       binding), which answers where no boundary is above, whenever no
       component is rendering, and for a binding the render's own code
       took.
    3. nil — a public frame-scoped operation then fails loudly via
       `rf.frame/require-current-frame!`.

  The first tier is gated on Reagent's in-flight component because the
  context slot means something only during a render: outside one the
  dynamic scope must stay in charge, and a slot left stale by a server
  render that threw must never outrank it.

  It is skipped, too, when the in-effect binding was taken inside this
  component's render (`rf.frame/bound-inside-render?`): a `with-frame` in
  the view body or in an `:epoch` listener or trace callback the render
  notifies, or the router's binding for a handler the render ran
  synchronously, such as a `make-frame` `:initial-events` step. That
  binding is the nearest, most explicit statement of the frame, while one
  around the render belongs to a different extent. A component that starts
  rendering inside such a binding is a different one, so its tree answers.

  The coercion inside `context-value->current-frame` is defensive cover for
  a Provider mounted via raw `[:> (.-Provider frame-context) {:value :foo}]`
  hiccup: stock Reagent's `convert-prop-value` stringifies named values on
  that path (and drops keyword namespaces — see `frame-provider-component`).
  The canonical `rf/frame-provider` bypasses it via `:r>`, so its keyword
  arrives intact."
  []
  (or (when-let [cmp (current-component)]
        (when-not (rf.frame/bound-inside-render? cmp)
          (tree-frame cmp)))
      rf.frame/*current-frame*))

;; ---- per-render identity --------------------------------------------------
;;
;; Render-trace entries carry a `:render-key` of shape
;; `[<view-id> <instance-token>]`. Tokens come from one process-wide
;; counter (`rf.adapter.context/mint-instance-token!`) and disambiguate
;; concurrently-mounted instances of the same kind — they're for in-run
;; discrimination only, no cross-run correlation.
;;
;; The `*render-key*` dynamic var and `current-render-key` reader
;; live in `re-frame.views` (the public ns) so the canonical Var sits
;; at the public path tests reach via `re-frame.views/*render-key*`.

(def mint-instance-token!
  "Return a fresh integer token for a view instance. See
  `rf.adapter.context/mint-instance-token!`."
  rf.adapter.context/mint-instance-token!)

(defn instance-token
  "The token of the view instance rendering now, stable across its
  re-renders: kept on the Reagent component object as `.-rfInstanceToken`,
  or the one a React-hook registered-view head offers
  (`rf.adapter.context/claim-instance-token!`). Outside any mounted
  instance (a direct headless invocation) mints a fresh token per call,
  which mirrors one mount per call."
  []
  (if-let [cmp (current-component)]
    (or (.-rfInstanceToken ^js cmp)
        (let [tok (mint-instance-token!)]
          (set! (.-rfInstanceToken ^js cmp) tok)
          tok))
    (or (rf.adapter.context/claim-instance-token!)
        (mint-instance-token!))))

(defn component-lifecycle-reaction
  "Return the per-component-instance lifecycle reaction,
  building it once via `(build-fn)` on first call and caching it on the
  Reagent component object as `.-rfLifecycleReaction` so re-renders of
  the same mounted instance reuse it. The reaction's disposal (on
  `componentWillUnmount`) is what fires the `:rf.view/unmounted` emit —
  caching guarantees one reaction per instance, hence one unmount emit.

  When called outside a Reagent component (direct headless invocation of
  the wrapper) there is no instance to cache against and no real unmount
  lifecycle to observe, so this returns nil and the caller skips the
  deref — headless direct invocations do not emit `:rf.view/unmounted`
  (there is no teardown to trace). Likewise returns nil when `build-fn`
  yields nil (the active adapter publishes no reaction primitive).

  THE HOLDER IS CLEARED WHEN THE REACTION IT HOLDS DISPOSES.
  Caching the reaction on the instance is what guarantees ONE unmount emit per
  instance; it also means the holder can outlive what it holds. React.StrictMode
  puts a SIMULATED unmount between the mount and the remount and reuses the same
  component instance, so the transient teardown disposes this reaction. A holder
  left pointing at it would leave the remounted instance holding a corpse:
  `build-fn` would never re-run, and the instance's GENUINE unmount would emit
  nothing at all — one `:rf.view/unmounted` before the real unmount and none
  within it. Clearing the holder from the reaction's own dispose keeps the
  invariant the cache is there for — one LIVE reaction per mounted instance —
  and a normal single mount never reaches it, because nothing disposes the
  reaction until the instance is gone.

  The clear is identity-guarded: a rebuild may have already installed a
  successor by the time a late dispose callback runs, and clearing the holder
  then would strand the remounted instance in the same way."
  [build-fn]
  (when-let [cmp (current-component)]
    (or (.-rfLifecycleReaction ^js cmp)
        (when-let [rea (build-fn)]
          (set! (.-rfLifecycleReaction ^js cmp) rea)
          (rf.interop/add-on-dispose! rea
            (fn clear-lifecycle-holder [_]
              (when (identical? rea (.-rfLifecycleReaction ^js cmp))
                (set! (.-rfLifecycleReaction ^js cmp) nil))))
          rea))))
