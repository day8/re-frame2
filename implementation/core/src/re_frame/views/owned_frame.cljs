(ns re-frame.views.owned-frame
  "Substrate-agnostic core of the UI-OWNED frame-provider lifecycle boundary
  (EP-0024 §Scope, carry, and ownership). This ns owns the create-on-mount /
  destroy-on-unmount semantics that make `rf/frame-provider` an OWNED lifecycle
  boundary — the component that CREATES a frame on mount, PROVIDES its id to
  descendants, and DESTROYS it on unmount.

  The sibling `rf/frame-provider-existing` is the SCOPE-ONLY counterpart: it
  provides an ALREADY-CREATED frame id through the same React context and
  creates / refreshes / destroys NOTHING. It lives in the per-adapter shells
  (Reagent's `re-frame.views.provider`, the UIx / Helix `defui` / `defnc`
  shells) and reuses the scope-only provide tier
  (`re-frame.substrate.spine/build-frame-provider-element` /
  `re-frame.adapter.context/provider-element`); it does NOT touch this ns. The
  fail-loud guard for lifecycle opts handed to the scope-only provider lives
  here (`reject-lifecycle-opts!`) so both kinds of provider validate against
  one place.

  The shared OWNED contract (EP-0024):

    - **create-on-mount** — `acquire-owned-frame!` runs the unified
      `re-frame.live-frame/make-frame` over the provider's `{:id :images
      :initial-db …}` opts, returning the resolved frame id.
    - **provide-frame-id-to-descendants** — the per-adapter shell wraps
      children in the shared React Context (via
      `re-frame.adapter.context/provider-element`) with the resolved id.
    - **destroy-on-unmount** — `release-owned-frame!` tears the frame down.
    - **idempotent re-mount** — re-mounting under the SAME `:id` (hot reload,
      Story re-evaluation, React StrictMode dev double-invoke) MUST NOT destroy
      durable state (app-db, sub-cache, queue). Per EP-0024 §Duplicate id
      policy this couples to `make-frame`'s idempotent replacement: a re-acquire
      under the same id refreshes config + resolved generation while preserving
      durable state, and the destroy is DEFERRED + cancellable so a re-mount
      that re-acquires the id before the deferred destroy fires CANCELS it.

  Why deferred destroy (the StrictMode / hot-reload tolerance, EP-0024
  §per-adapter spec): React StrictMode mounts a component, runs its effect,
  runs the cleanup, then runs the effect AGAIN (dev mount → unmount → mount).
  A synchronous `destroy-frame!` in the cleanup would tear down durable state
  between the two mounts. Instead `release-owned-frame!` SCHEDULES the destroy
  via a 0ms host timer keyed by frame id; `acquire-owned-frame!` cancels any
  pending destroy for that id first. So:

    - StrictMode (synchronous unmount→remount in the same task): the remount's
      acquire cancels the deferred destroy before the timer fires — the frame
      is never destroyed, durable state survives, no double-create churn.
    - Hot reload (re-eval under the same id): same idempotent-replacement path;
      `make-frame` refreshes config + generation, durable state preserved.
    - Genuine unmount (no remount re-acquires the id): the deferred destroy
      fires on the next macrotask, AFTER React has finished its synchronous
      teardown — so `destroy-frame!` does not race the unmounting render's
      in-flight per-frame work (subscriptions / event drains). This is the
      ordering the EP requires.

  CLJS-only (in core, like `re-frame.adapter.context`): it reaches React-shaped
  lifecycle through ONE shared React function component (`owned-frame-fc`) so
  the create/provide/destroy + StrictMode/idempotency handling lives once, not
  per substrate. The per-adapter `frame-provider` shells (Reagent's `:r>`
  embed, UIx's `defui`, Helix's `defnc`) only read their props in the native
  idiom and hand a clean opts map + children to this core — zero per-substrate
  lifecycle drift, mirroring how `re-frame.substrate.spine/build-frame-provider-
  element` factors the scope-only element-build. The plain-atom (JVM-runnable)
  build never loads this ns."
  (:require ["react"               :as React]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.error        :as rf-error]
            [re-frame.frame        :as frame]
            [re-frame.interop      :as interop]
            [re-frame.live-frame   :as live-frame]))

;; ---- pending-destroy registry --------------------------------------------
;;
;; `frame-id -> host-timer-handle`. A frame id has at most one pending
;; deferred destroy at a time. `acquire-owned-frame!` cancels the pending
;; destroy (if any) before (re)creating; `release-owned-frame!` schedules one.
;; defonce so a hot-reload of this ns does not orphan an in-flight cancel
;; (the same registry survives the reload).

(defonce ^:private pending-destroys (atom {}))

(defn- cancel-pending-destroy!
  "Cancel and forget any deferred `destroy-frame!` scheduled for `frame-id`.
  Called by `acquire-owned-frame!` so a re-mount under the same id (StrictMode
  remount, hot reload) reclaims the still-alive frame instead of letting the
  earlier release's deferred destroy fire. Returns true when a pending destroy
  was cancelled."
  [frame-id]
  (when-let [handle (get @pending-destroys frame-id)]
    (interop/clear-timeout! handle)
    (swap! pending-destroys dissoc frame-id)
    true))

(defn acquire-owned-frame!
  "CREATE-ON-MOUNT (EP-0024). Run the unified `make-frame` over the owned
  frame-provider's `opts` (`{:id :images :initial-db …}` plus any record-config
  keys) and return the resolved frame id. Idempotent under the same `:id`:
  re-acquiring refreshes config + resolved generation while preserving durable
  state (EP-0024 §Duplicate id policy) AND cancels any pending deferred destroy
  for that id, so a StrictMode remount / hot reload reclaims the live frame
  rather than recreating it.

  `opts` must carry an explicit `:id` keyword — an owned provider OWNS a named
  frame lifetime, so the id is the lifecycle token. `make-frame`'s own
  validation fails loud on a bad shape. Returns the frame id (read off the
  returned frame value via `frame/frame-value->id`)."
  [opts]
  (let [frame-value (live-frame/make-frame opts)
        frame-id    (frame/frame-value->id frame-value)]
    ;; Cancel AFTER make-frame so a same-id re-acquire that hit the idempotent
    ;; path keeps the live frame; the cancel drops the stale pending-destroy.
    (cancel-pending-destroy! frame-id)
    frame-id))

(defn release-owned-frame!
  "DESTROY-ON-UNMOUNT (EP-0024), DEFERRED. Schedule `destroy-frame!` for
  `frame-id` on a 0ms host timer rather than tearing down synchronously, so a
  React StrictMode remount (dev mount→unmount→mount) or a hot reload that
  re-acquires the same id BEFORE the timer fires cancels the destroy (via
  `acquire-owned-frame!`) and preserves durable state. A genuine unmount — no
  re-acquire — lets the timer fire on the next macrotask, AFTER React's
  synchronous teardown, so the destroy does not race the unmounting render's
  in-flight subscriptions / event drains.

  Re-entrant-safe: a second release for the same id replaces the prior pending
  handle (cancelling it first) so at most one deferred destroy is outstanding
  per id. The deferred body clears its own registry slot before destroying so a
  release that fires between two genuine lifecycles cannot leak a stale handle.
  `destroy-frame!` is itself idempotent, so a no-op (already-destroyed) id is
  harmless."
  [frame-id]
  ;; Replace any prior pending destroy for this id (cancel-then-reschedule) so
  ;; we never stack two timers against one frame.
  (cancel-pending-destroy! frame-id)
  (let [handle (interop/set-timeout!
                 (fn deferred-destroy []
                   ;; Drop our slot first: by the time we fire, this handle is
                   ;; the live one (a re-acquire would have cancelled us), so
                   ;; clearing here keeps the registry honest if destroy throws.
                   (swap! pending-destroys dissoc frame-id)
                   (frame/destroy-frame! frame-id))
                 0)]
    (swap! pending-destroys assoc frame-id handle)
    nil))

(defn ^:no-doc pending-destroy?
  "TEST/TOOLING ONLY. True when a deferred `destroy-frame!` is currently
  scheduled (and not yet fired/cancelled) for `frame-id`. Lets a test assert
  the StrictMode-tolerant cancel happened without reaching the host timer."
  [frame-id]
  (contains? @pending-destroys frame-id))

(defn ^:no-doc owned-frame-opts
  "Normalise the owned frame-provider's prop map into the `make-frame` opts the
  lifecycle consumes. Strips the `:children` carrier (the substrate element
  macros fold trailing children onto `:children`; it is not a frame option) and
  passes EVERY OTHER key through — `:id`, `:images`, `:initial-db`, and the
  record-config keys `make-frame` honours (`:fx-overrides`, `:on-create`, …)."
  [props]
  (dissoc props :children))

(defn ^:no-doc require-owned-frame-id!
  "Validate the OWNED frame-provider's `:id` (EP-0024). An owned provider OWNS
  a named frame lifetime, so `:id` is mandatory and must be a keyword — it is
  the lifecycle token descendants route to and `destroy-frame!` tears down on
  unmount. A missing / nil / non-keyword `:id` is a CONFIGURATION ERROR: emit +
  throw `:rf.error/owned-frame-provider-missing-id` so a tooling-generated or
  hand-authored owned provider fails loudly at the boundary rather than minting
  an anonymous, unaddressable frame. (The OWNED provider takes `:id`; the
  SCOPE-only `rf/frame-provider-existing` takes `:frame` — a `:frame` key with
  no `:id` is the common mistake and trips this guard.) Returns the keyword
  `:id`."
  [id where-sym]
  (if (keyword? id)
    id
    (rf-error/throw-error!
      :rf.error/owned-frame-provider-missing-id
      where-sym
      (str "frame-provider is the UI-OWNED lifecycle boundary (EP-0024): it "
           "CREATES the frame on mount, so it needs an explicit keyword `:id` "
           "to own and destroy. Got " (pr-str id) ". Pass `{:id :your/frame …}` "
           "(plus optional :images / :initial-db). To merely SCOPE descendants "
           "to a frame that ALREADY EXISTS, use `rf/frame-provider-existing "
           "{:frame :your/frame}` (or `rf/with-frame` outside React render).")
      {:recovery :no-frame-context
       :extra    {:received id}})))

;; ---- scope-only fail-loud guard (rf/frame-provider-existing) --------------
;;
;; The SCOPE-only provider (`rf/frame-provider-existing`) provides an
;; already-created frame and creates / refreshes / destroys NOTHING. Per the
;; EP-0024 name-family ruling it takes `:frame` ONLY — any frame-CONSTRUCTION /
;; lifecycle opt (`:id`, `:images`, `:initial-db`, `:on-create`, … — i.e. the
;; opts the OWNED provider consumes) is a hard MISUSE and MUST fail loud, so a
;; caller cannot silently expect a scope-only provider to create or own a
;; frame. The guard lives here (beside the owned-provider id guard) so both
;; provider kinds validate against one place; every per-adapter
;; `frame-provider-existing` shell calls it before delegating to the scope-only
;; provide tier.

(def ^:private lifecycle-opt-keys
  "Frame-CONSTRUCTION / lifecycle opt keys that belong to the OWNED
  `rf/frame-provider`. Passing any of these to the SCOPE-only
  `rf/frame-provider-existing` is a misuse (it neither creates nor owns a
  frame). `:children` is the substrate trailing-children carrier, not a
  lifecycle opt, so it is excluded."
  #{:id :images :initial-db :on-create :fx-overrides :realm})

(defn ^:no-doc reject-lifecycle-opts!
  "Fail loud when a SCOPE-only `rf/frame-provider-existing` is handed any
  frame-CONSTRUCTION / lifecycle opt (`:id` / `:images` / `:initial-db` /
  `:on-create` / …). `props` is the provider's already-native-read prop map
  (minus `:children`); `where-sym` names the calling shell for the diagnostic.
  Emits + throws `:rf.error/frame-provider-existing-lifecycle-opt` so a caller
  who expected scope-only-provides-an-existing-frame semantics but passed
  construction opts is told to use the OWNED `rf/frame-provider` instead. A
  clean `{:frame …}`-only prop map passes through (returns nil)."
  [props where-sym]
  (let [offending (filter (set lifecycle-opt-keys) (keys props))]
    (when (seq offending)
      (rf-error/throw-error!
        :rf.error/frame-provider-existing-lifecycle-opt
        where-sym
        (str "frame-provider-existing is the SCOPE-ONLY provider (EP-0024): it "
             "provides an ALREADY-CREATED frame through React context and "
             "creates / refreshes / destroys nothing. It takes `:frame` ONLY. "
             "Got lifecycle opt(s) " (pr-str (vec offending)) ". To CREATE and "
             "OWN a frame for as long as the component is mounted, use the "
             "owned `rf/frame-provider {:id :your/frame :images […] …}` "
             "instead.")
        {:recovery :use-frame-provider
         :extra    {:offending-keys (vec offending)}}))))

;; ---- the shared React lifecycle component --------------------------------
;;
;; ONE React function component backs the owned frame-provider on every
;; React-shaped substrate (Reagent via `:r>`, UIx/Helix via `createElement`).
;; The create/provide/destroy + StrictMode/idempotency handling lives here,
;; not per adapter. Two-phase, matching the React resource-ownership idiom:
;;
;;   - CREATE during RENDER (synchronously, before children render) so a
;;     descendant that subscribes/dispatches on first render observes a live
;;     frame. `make-frame` is idempotent replacement, so creating during render
;;     (which React may run more than once before commit — concurrent features,
;;     StrictMode dev double-INVOKE of the render fn) is safe: a same-id
;;     re-create refreshes config + generation, preserving durable state. The
;;     resolved id is stashed in a `useRef` so re-renders reuse it.
;;   - DESTROY on UNMOUNT via `useEffect` empty-deps cleanup, DEFERRED (see
;;     `release-owned-frame!`) so StrictMode's mount→unmount→mount and hot
;;     reload do not tear down durable state. The effect SETUP re-drives
;;     `cancel-pending-destroy!` (rf2-i02deh): StrictMode replays the effect as
;;     setup → cleanup → setup on the SAME fiber WITHOUT re-running render, so
;;     the render-phase acquire cannot cancel the remount's pending destroy —
;;     the setup-phase cancel is what makes the deferred-destroy window
;;     StrictMode-tolerant.

(defn ^:no-doc owned-frame-fc
  "The React function component that owns one frame's UI lifetime — ONE
  implementation shared by every React-shaped substrate. `props` is a JS
  object carrying:

    - `:rfOpts`   — the `make-frame` opts (an opaque CLJS value; passed through
                    React props untouched, because every per-adapter shell builds
                    this element via raw `createElement` / Reagent `:r>`, both of
                    which pass JS prop VALUES through without marshalling — the
                    keyword-mangling seam never sees it).
    - `children`  — React's STANDARD children prop. Each shell hands its scoped
                    subtree as trailing `createElement` args (Reagent's `:r>`
                    translates trailing hiccup into React children; UIx/Helix
                    pass their native `:children` React elements), so they arrive
                    here as ordinary React children.

  Returns the shared frame Context Provider element (via
  `adapter-context/provider-element`) scoping the CREATED frame's id to those
  children.

  Render-phase create (NOT effect-phase): lazily acquire the frame the first
  time this instance renders, caching the resolved id in a `useRef` so
  re-renders reuse it (and so the provider's context `:value` is stable).
  Acquiring during render guarantees the frame exists BEFORE children render and
  read it — an effect would run after the first child render and race
  descendant subscribes/dispatches.

  Effect-phase destroy: an empty-deps `useEffect` arms a cleanup that calls
  `release-owned-frame!` (DEFERRED destroy) on unmount. Empty deps ⇒ the cleanup
  runs once per genuine unmount. StrictMode's extra mount→unmount→mount cycle is
  absorbed because the destroy is deferred AND the effect SETUP re-drives
  `cancel-pending-destroy!` on every run: StrictMode replays the effect as
  setup → cleanup → setup on the SAME fiber, and the render phase does NOT
  re-run on the effect re-setup (the fiber's cached `id-ref.current` survives),
  so the render-phase `acquire-owned-frame!` is NOT a reliable cancel site for
  the remount. Cancelling in the effect setup (rf2-i02deh) is — the remount's
  setup reclaims the still-live frame before the prior cleanup's 0ms timer can
  fire. A genuine first mount finds no pending destroy (no-op); a genuine
  unmount arms the deferred destroy with no re-setup to cancel it, so real
  teardown is unaffected."
  [^js props]
  (let [opts     (.-rfOpts props)
        children (.-children props)
        id-ref   (React/useRef nil)
        ;; Render-phase acquire (idempotent). Cache the resolved id so the
        ;; context value and the unmount-effect's release target are stable.
        frame-id (or (.-current id-ref)
                     (let [fid (acquire-owned-frame! opts)]
                       (set! (.-current id-ref) fid)
                       fid))]
    (React/useEffect
      (fn arm-owned-frame-teardown []
        ;; StrictMode/hot-reload tolerance (rf2-i02deh). React StrictMode runs
        ;; this effect as setup → cleanup → setup AGAIN on the SAME fiber (the
        ;; `useRef` persists). The cleanup below schedules the DEFERRED destroy;
        ;; the re-setup must CANCEL it, or the 0ms timer fires and tears down
        ;; durable state between the two mounts. The render phase does NOT
        ;; re-run on a StrictMode effect re-setup (the fiber's `id-ref.current`
        ;; is still populated), so `acquire-owned-frame!` — the render-phase
        ;; cancel site — never runs again. Re-drive the cancel HERE, on every
        ;; effect setup, so the remount's effect-setup reclaims the still-live
        ;; frame (Spec 002 §StrictMode double-invoke / §idempotent re-mount).
        ;; A genuine first mount has no pending destroy, so this is a no-op
        ;; there; a genuine unmount still arms the deferred destroy via the
        ;; cleanup, and nothing re-acquires the id to cancel it — so real
        ;; teardown is unaffected.
        (cancel-pending-destroy! (.-current id-ref))
        (fn cleanup-owned-frame []
          (release-owned-frame! (.-current id-ref))))
      ;; Empty deps: arm once on mount, clean up once on unmount.
      #js [])
    (apply adapter-context/provider-element
           frame-id
           (cond
             (nil? children)        nil
             (array? children)      (array-seq children)
             (sequential? children) children
             :else                  [children]))))

(defn ^:no-doc owned-frame-react-element
  "Build the raw React element for the UI-owned frame-provider, for the
  React-hook substrates (UIx / Helix). The substrate-agnostic CORE those
  adapters' native `frame-provider` shells delegate to, parallel to
  `re-frame.substrate.spine/build-frame-provider-element` for the scope-only
  path. Given the provider's already-native-read prop map `props`
  (`{:id :images :initial-db …}`), the React children value `children`, and
  `where-sym` (the calling shell's fn symbol, for the fail-loud `:id`
  diagnostic): validate `:id`, separate the `make-frame` opts, and return a
  `createElement` over `owned-frame-fc` so React owns the create-on-mount /
  destroy-on-unmount lifecycle.

  The Reagent shell does NOT use this — it embeds `owned-frame-fc` via Reagent's
  `:r>` interop head (so trailing hiccup children translate to React children),
  building the element in `re-frame.views.provider`."
  [props children where-sym]
  (require-owned-frame-id! (:id props) where-sym)
  (apply React/createElement
         owned-frame-fc
         #js {:rfOpts (owned-frame-opts props)}
         (cond
           (nil? children)        nil
           (array? children)      (array-seq children)
           (sequential? children) children
           :else                  [children])))
