(ns re-frame.views.provider
  "Frame-provider name family (Reagent shells) + per-render identity
  machinery for the Reagent-side views ns. Re-frame.views re-exports the
  publicly-referenced surface (`frame-provider`, `frame-provider-existing`,
  `build-frame-provider`, `current-frame`, `mint-instance-token!`,
  `current-render-key`, `*render-key*`).

  Four cohesive concerns live here:

  1. `frame-provider` — the user-facing Reagent component for the
     UI-OWNED frame lifecycle boundary (EP-0024 §Scope, carry, and
     ownership): it CREATES a frame on mount, PROVIDES its id to
     descendants, and DESTROYS it on unmount. The create/destroy +
     StrictMode/idempotency handling lives once in
     `re-frame.views.owned-frame`; this Reagent shell embeds that ns's
     shared React lifecycle component via Reagent's `:r>` interop head.

  2. `frame-provider-existing` — the user-facing Reagent component for
     the SCOPE-ONLY provider (EP-0024): it provides an ALREADY-CREATED
     frame id through the SAME React context and creates / refreshes /
     destroys NOTHING. It takes `:frame` ONLY; any lifecycle opt
     (`:id` / `:images` / …) fails loud. It reuses the scope-only inner
     provide tier (`build-frame-provider` / `frame-provider-component`),
     which is also the substrate hook the Reagent adapter installs at
     `:register-context-provider` and the element the OWNED lifecycle
     component reaches once it has created the frame. (`rf/with-frame`
     remains for lexical/non-React ambient scoping; scope-INTO-React is
     this component, because a dynamic var cannot cross React's render
     boundary.) The React Context object is owned by
     `re-frame.adapter.context` so adapters across substrates
     (Reagent / UIx / Helix) read the same context.

  3. Per-render instance-token machinery — `mint-instance-token!`,
     `reagent-component-token`. Tokens disambiguate concurrently-
     mounted instances of the same view-kind. The `*render-key*`
     dynamic var lives in `re-frame.views` so the wrapper's binding
     and consumer reads share the same canonical Var.

  4. `current-frame` — the resolution chain subscribe / dispatch
     consult at render time (Spec 002 §Reading the frame from React
     context). Pairs with the `:contextType` static `reg-view*`
     attaches to its wrapper output.

  This ns does NOT statically `:require` `reagent.core`. The in-flight
  Reagent component is read through the late-bind hook
  `:adapter/current-component`; with no adapter installed the hook
  returns nil and the React-context tier of the resolution chain is
  skipped (equivalent to a non-Reagent render context — JVM /
  headless tests rely on this)."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.frame :as frame]
            [re-frame.late-bind :as late-bind]
            [re-frame.views.owned-frame :as owned-frame]))

;; ---- the React context for frame propagation -----------------------------
;;
;; The React Context object lives in `re-frame.adapter.context` so
;; every React-shaped adapter reads the *same* context — not a
;; parallel one. The Reagent code below references it via the alias
;; rather than minting a new createContext call.

(def frame-context adapter-context/frame-context)

(defn- frame-provider-component
  "The single Reagent component that backs the SCOPE-ONLY provide tier. It
  takes the frame keyword as its first render-time arg and scopes that
  keyword to its subtree via React context. One built component services
  every frame — the keyword lives in the Provider's `:value`, not in a
  closure. This is the substrate hook the Reagent adapter installs at
  `:register-context-provider`, the provide tier the user-facing
  `frame-provider-existing` is built on, and the tier the OWNED
  `frame-provider` reaches (through `re-frame.adapter.context/
  provider-element`) once it has created the frame. It does NOT create or
  destroy a frame — pure scoping.

  Uses Reagent's `:r>` interop head so the props map flows to React as a
  raw JS object without passing through `reagent.impl.template/convert-
  prop-value`. That bypass is what makes a *namespaced* frame keyword
  (`:tenant/admin`) survive the React-context round trip on the classic
  Reagent adapter — stock Reagent's `convert-prop-value` calls `(name kw)`
  on named prop values, which drops the namespace before React sees the
  prop. The slim adapter (`day8/reagent-slim`) preserves keywords for
  non-HTML prop names so the namespace would survive there even via the
  `[:> Provider ...]` hiccup form; routing the canonical surface through
  `:r>` keeps the behaviour identical across adapters.

  Children remain hiccup — `:r>` only short-circuits prop conversion;
  children are translated by the renderer as usual. Per
  `re-frame.adapter.context/provider-element` for the React-element-
  building counterpart that adapter ns offers for substrate-side mounts."
  [frame-kw & children]
  (into [:r> (.-Provider frame-context) #js {:value frame-kw}] children))

(defn build-frame-provider
  "Used by re-frame.adapter.reagent/register-context-provider, by the
  user-facing SCOPE-only `frame-provider-existing`, and by the OWNED
  `frame-provider` lifecycle component as its scope-only provide tier.
  Returns a Reagent component that SCOPES a frame keyword to its subtree
  (no create/destroy).

  Zero-arity (rf2-4y60): the returned component takes the frame keyword
  at render time, and a single built component services every frame, so
  there is nothing to specialise at build time. Substrates whose
  `register-context-provider` slot receives a frame-keyword on call (per
  Spec 006 §Frame-provider via React context) discard it and call this
  with no args."
  []
  frame-provider-component)

(defn frame-provider
  "User-facing component — the UI-OWNED frame LIFECYCLE boundary (EP-0024
  §Scope, carry, and ownership). It CREATES a frame on mount, PROVIDES its
  id to descendants, and DESTROYS it on unmount. Per Spec 002
  §`frame-provider` (the owned UI boundary).

  This is NOT a scope-only component — scoping descendants to a frame that
  ALREADY EXISTS is `rf/frame-provider-existing` (scope-into-React) or
  `rf/with-frame` (lexical / non-React ambient scope). `frame-provider`
  OWNS a frame lifetime, so it takes the same `:id` / `:images` /
  `:initial-db` (plus record-config) opts as `rf/make-frame`, runs that one
  constructor on mount, and tears the frame down on unmount.

  `:id` is REQUIRED and must be a KEYWORD — it is the lifecycle token
  descendants route to and `destroy-frame!` tears down. A missing / nil /
  non-keyword `:id` is a CONFIGURATION ERROR: emits + throws
  `:rf.error/owned-frame-provider-missing-id`. (A `:frame` key with no
  `:id` is the common mistake and trips this guard — switch to `:id`, or
  use `rf/frame-provider-existing {:frame …}` to merely scope.)

  Reagent call shape:

      [rf/frame-provider {:id :session :images [session-image] :initial-events []}
       [header]
       [main-area]
       [footer]]

  Children are variadic (zero, one, or many). Same surface as the UIx and
  Helix variants, different rendering substrate. The three adapters share
  one React Context so a subtree under any frame-provider sees the right
  frame regardless of which substrate rendered the provider.

  Idempotent re-mount (hot reload / React StrictMode dev double-invoke /
  Story re-evaluation): re-mounting under the same `:id` MUST NOT destroy
  durable state — `make-frame` is idempotent replacement and the
  destroy-on-unmount is DEFERRED + cancelled by a re-acquire. Per
  `re-frame.views.owned-frame` for the shared lifecycle core.

  The lifecycle is realised through the shared React function component
  `owned-frame/owned-frame-fc`, embedded here via Reagent's `:r>` interop
  head: the `#js {:rfOpts …}` prop reaches React untouched (bypassing
  `convert-prop-value`, so the CLJS opts map survives intact), and the
  TRAILING HICCUP children are translated by Reagent's renderer into React
  children — exactly as `frame-provider-component` does for the scope-only
  Provider."
  [props & children]
  ;; Validate `:id` here (so the diagnostic names this fn), then embed the
  ;; shared owned-frame FC via `:r>`. `:r>` passes the `#js {:rfOpts …}` prop
  ;; through as a raw JS object (no `convert-prop-value`), and the trailing
  ;; hiccup children become React children — `owned-frame-fc` reads its scoped
  ;; subtree from React's standard `props.children` and the opts from `:rfOpts`.
  (owned-frame/require-owned-frame-id!
    (:id props)
    're-frame.views.provider/frame-provider)
  (into [:r> owned-frame/owned-frame-fc
         #js {:rfOpts (owned-frame/owned-frame-opts props)}]
        children))

(defn frame-provider-existing
  "User-facing SCOPE-ONLY component (EP-0024 §Scope, carry, and ownership).
  It provides an ALREADY-CREATED frame's id to descendants via the shared
  React context and creates / refreshes / destroys NOTHING — the frame is
  owned elsewhere (a direct `rf/make-frame`, a tool runtime, a parent
  `rf/frame-provider`). Inside the subtree, `(rf/frame-handle)` /
  `reg-view`-registered descendants resolve to the named frame. Per
  Spec 002 §`frame-provider-existing`.

  This is the scope-INTO-React counterpart to `rf/with-frame`: `with-frame`
  binds a dynamic var and so cannot cross React's render boundary, which is
  exactly why this React-context scope component exists. Use this when a
  frame already exists and you only need to scope a React subtree to it; use
  the OWNED `rf/frame-provider` when a component should own a frame's
  lifetime.

  `:frame` is REQUIRED and must be a KEYWORD frame id (EP-0002 carried
  invariant). There is NO `(or (:frame props) :rf/default)` floor: an
  explicit `(rf/frame-provider-existing {} …)` with no frame establishes no
  usable scope. A missing `:frame` is a CONFIGURATION ERROR — it emits
  `:rf.error/no-frame-context` and throws. A non-nil but non-keyword
  `:frame` (a string / number / …) emits the distinct
  `:rf.error/bad-frame-provider-arg` and throws (rf2-9kpigo), so a typo like
  `{:frame \"app\"}` fails loudly rather than being silently coerced to
  `:app` by the lower-level context reader. A frame-CONSTRUCTION / lifecycle
  opt (`:id` / `:images` / `:initial-events` / …) is a MISUSE: it
  emits + throws `:rf.error/frame-provider-existing-lifecycle-opt` pointing
  the caller at the OWNED `rf/frame-provider`, because a scope-only provider
  neither creates nor owns a frame.

  Reagent call shape:

      [rf/frame-provider-existing {:frame :session}
       [header]
       [main-area]
       [footer]]

  Children are variadic. Same surface as the UIx and Helix variants. The
  three adapters share one React Context. `build-frame-provider` is the
  lower-level substrate hook; this fn is the canonical user-facing
  scope-only surface."
  [props & children]
  ;; Reject lifecycle opts FIRST (a scope-only provider neither creates nor
  ;; owns), then validate / coerce the `:frame` arg, then scope via the
  ;; shared scope-only provide tier.
  (owned-frame/reject-lifecycle-opts!
    (dissoc props :children)
    're-frame.views.provider/frame-provider-existing)
  (let [frame-kw (frame/require-keyword-frame-provider-arg!
                   (:frame props)
                   're-frame.views.provider/frame-provider-existing)]
    (into [(build-frame-provider) frame-kw] children)))

;; ---- in-flight Reagent component -----------------------------------------
;;
;; The active adapter publishes its Reagent build's `current-component`
;; fn through `:adapter/current-component` at ns-load time. Static
;; `:require [reagent.core]` would hard-couple this ns to stock Reagent
;; and silently shadow the slim adapter's components in mixed-mode
;; environments.

(defn current-component
  "Resolve the in-flight Reagent component via the active adapter's hook.
  Returns nil when no adapter has registered the hook (JVM / headless
  builds; no-adapter tests)."
  []
  ;; Sticky hook (rf2-f72pd) — published once per loaded React-shaped
  ;; adapter; called per Reagent render path that consults its
  ;; component identity.
  (when-let [hook (late-bind/get-fn-cached :adapter/current-component)]
    (hook)))

;; ---- frame resolution at render time -------------------------------------

(def ^:dynamic *current-frame* nil)

(defn current-frame
  "Resolution chain (READER) per Spec 002 §Frame target resolution — the
  carried invariant (EP-0002). Returns the scope frame, or **nil** when no
  scope is established — it never synthesises `:rf/default` (the runtime
  never repairs absence). The two tiers it observes:

    1. Dynamic var (set by with-frame).
    2. Closest enclosing frame-provider via React context.

  Reagent-specific: the React-context tier reads `(.-context cmp)` on
  the in-flight Reagent component. Reagent's class-component machinery
  surfaces context to components whose `:contextType` matches the
  context object — that is the wiring `reg-view*` attaches via
  `{:contextType frame-context}`. Plain Reagent fns lack this wiring,
  so `(.-context cmp)` is React's empty default — the no-provider
  sentinel — and coercion returns nil (no scope). A public frame-scoped
  operation reading nil then fails loudly via
  `frame/require-current-frame!`; the `:rf.warning/plain-fn-under-non-
  default-frame-once` narrowness contract (which the later EP-0002 view
  bead sharpens into a no-frame-context path) keys off this same nil.

  The keyword/string coercion via
  `re-frame.adapter.context/coerce-context-value` is defensive cover
  for users who mount a Provider via raw `[:> (.-Provider frame-context)
  {:value :foo}]` hiccup directly. Under the classic Reagent adapter
  that path still passes through stock Reagent's `convert-prop-value`,
  which stringifies named values (and drops keyword namespaces — see
  `frame-provider-component`). The canonical user-facing surface
  (`rf/frame-provider`) bypasses `convert-prop-value` via `:r>` so the
  Provider's `:value` reaches React as the original keyword (namespace
  preserved). The helper survives the slim rewrite because the
  defensive-cover use case for raw-hiccup mounts is independent of
  which Reagent build is loaded.

  `coerce-context-value` returns nil for the no-provider sentinel (a
  namespaced keyword it does not special-case — it coerces only genuine
  frame keywords / prop-stringified keywords), so a plain-fn read with no
  enclosing Provider falls through to the trailing nil cleanly."
  []
  (or frame/*current-frame*
      (when-let [cmp (current-component)]
        (let [v (.-context cmp)]
          (when-not (= v adapter-context/no-provider-sentinel)
            (adapter-context/coerce-context-value v))))))

;; ---- per-render identity --------------------------------------------------
;;
;; Render-trace entries carry a `:render-key` of shape
;; `[<view-id> <instance-token>]`. Tokens are minted at mount time
;; from a process-wide counter and disambiguate concurrently-mounted
;; instances of the same kind — they're for in-run discrimination
;; only, no cross-run correlation.
;;
;; The `*render-key*` dynamic var and `current-render-key` reader
;; live in `re-frame.views` (the public ns) so the canonical Var sits
;; at the public path tests reach via `re-frame.views/*render-key*`.
;; The mint / per-component-instance machinery lives here with its
;; state (`instance-counter`, `.-rfInstanceToken`).

(defonce ^:private instance-counter (atom 0))

(defn mint-instance-token!
  "Return a fresh integer token for a freshly-mounted view instance. The
  counter is process-wide and monotonic; values are unique within a
  single process run but carry no cross-run correlation guarantee."
  []
  (swap! instance-counter inc))

(defn reagent-component-token
  "Return the per-component-instance token, minting one on first call.
  Stored on the Reagent component object as `.-rfInstanceToken` so the
  same mounted instance reuses the token across re-renders. When called
  outside a Reagent component (direct invocation in headless tests),
  mints a fresh token per call — that mirrors the per-mount-fresh
  semantics for tests that simulate one mount per call."
  []
  (if-let [cmp (current-component)]
    (or (.-rfInstanceToken ^js cmp)
        (let [tok (mint-instance-token!)]
          (set! (.-rfInstanceToken ^js cmp) tok)
          tok))
    (mint-instance-token!)))

(defn component-lifecycle-reaction
  "Return the per-component-instance lifecycle reaction (rf2-9hoos),
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
  yields nil (the active adapter publishes no reaction primitive)."
  [build-fn]
  (when-let [cmp (current-component)]
    (or (.-rfLifecycleReaction ^js cmp)
        (when-let [rea (build-fn)]
          (set! (.-rfLifecycleReaction ^js cmp) rea)
          rea))))
