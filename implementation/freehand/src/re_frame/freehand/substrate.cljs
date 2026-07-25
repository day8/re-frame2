(ns re-frame.freehand.substrate
  "Freehand's own reactive-substrate adapter — the value the door publishes as
  `v/adapter` and an application installs with `(rf/init! v/adapter)`.

  ## Why Freehand owns one

  An adapter is not a renderer. Freehand already renders itself, through
  `react-dom/client`; what the substrate contract asks for is the OBSERVATION
  half — the state container app-db lives in, and a derived value that says when
  it moved. The two halves are independent, and until this namespace existed
  Freehand shipped only the first: an interactive Freehand application had to
  install UIx or Reagent to obtain the second.

  The gap was one interface. `re-frame.substrate.plain-atom`'s CLJS derived
  value reifies `IDeref` but NOT `IWatchable`, and the observation port installs
  its watch only when the reaction is watchable — so on plain-atom a moving
  subscription raises no notification at all, and its `:render` slot throws
  `:rf.error/render-on-headless-adapter`. `re-frame.substrate.spine`'s derived
  value DOES reify `IWatchable`. That single difference is the whole reason a
  Freehand page needed a wrapper library it never rendered through.

  So the spine is what this namespace stands on, and the spine lives in CORE
  (`re-frame.substrate.spine`) — shared by every React-shaped adapter,
  parameterised on the host's raw hook functions. Freehand supplies React's own
  `useMemo` / `useCallback` / `useContext` and takes no new dependency: it
  already requires `react` and `react-dom/client`.

  ## EP-0036 is preserved

  Reagent, UIx and reagent-slim remain first-class, independently supported
  RENDERER adapters. This one competes with none of them: it is the substrate's
  own observation wiring for a substrate that renders itself. Bring-your-own
  adapter also stays legitimate for a mixed application under the single-adapter
  runtime — a Freehand root is still torn down with `v/unmount!` before an
  external adapter is destroyed, and an external adapter's synchronous test
  helpers do not acquire Freehand's ViewCell-settling semantics.

  ## What this namespace adds beyond the spine

  Two lifecycle compositions, and they are the only Freehand-specific work here.

  **Disposal is two-phase.** Freehand calls `createRoot` itself and records the
  result in its OWN per-document registry, so those roots are invisible to the
  spine's active-root registry. Disposing the spine while they are mounted would
  pull the state containers out from under live subscriptions. So disposal
  drains the Freehand roots first and disposes the spine second, and the
  ordering decision itself is [[dispose-outcome]].

  The drain is the SAFETY NET for a root an application forgot to unmount, and
  it is deliberately narrower than `v/unmount!`: it releases claims, releases the
  root's frame reference and unmounts React, but does not run a frame's destroy
  recipe. Core closes public delegation before it calls this teardown, so a
  destroy recipe's `read-container` would raise `:rf.error/adapter-disposed` —
  and it does not need to run, because the frame's containers belong to the
  adapter being destroyed. Destroying a root-owned frame is `v/unmount!`'s job on
  a live adapter, which is why the contract asks an application to unmount its
  roots first.

  **`flush-render!` settles ViewCells.** The inherited spine verb runs
  `react-dom/flushSync`, which commits work SCHEDULED inside its callback. But a
  ViewCell invalidation notifies nothing synchronously: `cell/mark-dirty!` marks
  the cell and arms a later microtask. A caller that flushed a thunk which
  dispatches would therefore see `flushSync` return with the cell still pending
  and the DOM still stale. The override runs the pending-window close INSIDE the
  commit boundary and then converges — see [[flush-render!]].

  Frame reading needs nothing: [[re-frame.freehand.shell/frame-context-frame]]
  reads the shared React frame context directly, so the ambient frame resolves
  below a Freehand root without an `:adapter/current-frame` publication of its
  own (`make-react-adapter` routes that hook anyway, for the wider
  `(rf/current-frame-id)` chain).

  ## Why this namespace is not `re-frame.freehand.adapter`

  Because a ClojureScript namespace and a Var cannot share a name. The ruled
  authoring spelling is `re-frame.freehand/adapter`, and a sibling namespace
  called `re-frame.freehand.adapter` occupies the same JS path as that Var: the
  compiler reports `:ns-var-clash` and one of the two silently reads `nil`. So
  the machinery is homed under `substrate` and the door publishes `adapter` —
  the same split the donor substrate arrived at, for the same reason.

  INTERNAL. The authoring surface is `v/adapter`; nothing else here is
  application API.

  Normative owner:
  [`spec/006-ReactiveSubstrate.md`](../../../../../spec/006-ReactiveSubstrate.md)
  §The adapter API contract."
  (:require ["react" :as react]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.frame :as frame]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.root :as root]
            [re-frame.substrate.spine :as spine]
            [re-frame.views.frame-boundary :as boundary]))

;; ---------------------------------------------------------------------------
;; The spine
;; ---------------------------------------------------------------------------
;;
;; ONE substrate-owned spine, built from React's own hooks. `defonce`, so a
;; namespace reload hands the reloaded code the SAME spine a live adapter was
;; installed from rather than a fresh one whose epoch scheduler and warn-once
;; cache no root is using.
;;
;; The spine also generates a legacy `use-subscribe` hook surface, and it is
;; deliberately NOT re-exported: Freehand reads through `v/sub` and the ViewCell
;; alone, and a second read path would be a second commit discipline. Advanced
;; compilation removes the unused hook machinery.

(defonce ^:private spine-fns
  (spine/make-react-spine
   {:substrate-name        "Freehand"
    :gensym-prefix-sub     "rf-fh-sub-"
    :gensym-prefix-derived "rf-fh-derived-"
    :gensym-prefix-use-sub "rf-fh-use-sub-"
    :use-memo              react/useMemo
    :use-callback          react/useCallback
    :use-context           react/useContext}))

(defn- frame-provider-component
  "The adapter contract's `:register-context-provider` component — a plain React
  function component writing the shared frame context.

  This is the LOWER-LEVEL contract slot, not an authoring verb. Freehand
  publishes no subtree `v/frame-provider` (that head is rf2-h1ae3's, and no
  placeholder is minted here); a Freehand root scopes its frame through
  [[re-frame.freehand.shell/provide-frame]] on the way in. The slot exists
  because the contract has it and because core's own provider tier
  (`re-frame.views.provider`) reaches for it, so it validates its target and
  writes the same one context every React-shaped adapter reads — which is what
  lets a mixed Freehand/UIx provider tree compose. Plain React rather than a
  wrapper component: there is no marshalling to do, so there is no wrapper to
  depend on."
  [props]
  (let [frame-id (frame/require-frame-provider-target!
                  (.-frame props)
                  're-frame.freehand.substrate/frame-provider)]
    (boundary/require-live-frame-for-scope!
     frame-id 're-frame.freehand.substrate/frame-provider)
    (apply adapter-context/provider-element
           frame-id
           (adapter-context/normalize-children (.-children props)))))

(defonce ^:private spine-adapter
  (spine/make-react-adapter
   spine-fns
   {:kind :rf.adapter/freehand :frame-provider frame-provider-component}))

;; ---------------------------------------------------------------------------
;; Disposal — Freehand roots first, then the spine
;; ---------------------------------------------------------------------------
;;
;; PRESENCE, not JS truthiness, decides whether either phase failed: a throw can
;; carry a legitimately falsy value (`false`, `nil`), and a truthy test would
;; discard it and report a clean disposal. Each slot holds this identity
;; sentinel until a throw records into it, so a falsy failure survives and is
;; thrown in primary order. Compared only with `identical?`.

(def ^:private no-dispose-error #js {})

(defn- attach-secondary-cleanup!
  "Return `primary` UNCHANGED, having retained a PRESENT secondary failure on it
  as `rfFreehandAdapterCleanupError` for diagnostics. The first cleanup error
  stays primary and later ones ride it as evidence — the standing convention.

  Presence, not truthiness, gates the attach, so a falsy secondary is real. A
  PRIMITIVE primary (nil / false / a number) cannot carry a property, so there
  the secondary rides an always-on console diagnostic instead and stays
  observable; `primary` is never coerced or replaced to make room for it."
  [primary secondary-present? secondary]
  (when secondary-present?
    (let [attached?
          (try
            (js/Object.defineProperty
             primary "rfFreehandAdapterCleanupError"
             #js {:value secondary :configurable true})
            true
            (catch :default _ false))]
      (when (and (not attached?) (exists? js/console))
        (.warn js/console
               (str "[re-frame.freehand] a spine cleanup failure could not ride "
                    "the primary adapter-disposal rejection (a primitive reason "
                    "cannot carry a diagnostic property); the primary is "
                    "rethrown unchanged. Secondary cleanup error:")
               secondary))))
  primary)

(defn ^:no-doc dispose-outcome
  "The disposal outcome policy, decided by failure PRESENCE and never by JS
  truthiness: a ROOT-DRAIN failure is primary (a present spine failure rides it
  as a diagnostic), else a spine-only failure is thrown, else disposal was
  clean. Returns `[:throw reason]` or `[:ok]`.

  Extracted as the ONE place the ordering decision lives, so the dual-failure
  law is checkable without a DOM."
  [drain-present? drain-error spine-present? spine-error]
  (cond
    drain-present?
    [:throw (attach-secondary-cleanup! drain-error spine-present? spine-error)]

    spine-present? [:throw spine-error]
    :else          [:ok]))

(defn- dispose-adapter!
  "Dispose the adapter: drain every live Freehand root, THEN dispose the generic
  React spine.

  BOTH phases are attempted whatever either does. The order is the load-bearing
  part — a Freehand root's `unmount!` is what releases its subscriptions,
  disconnects its ViewCells and releases its frame reference, and all three of
  those need the containers the spine is about to tear down. The drain failing
  does not excuse the spine from being disposed; the spine failing does not
  hide the drain's failure, which stays primary with the spine's retained on it."
  []
  (let [drain-error (volatile! no-dispose-error)
        spine-error (volatile! no-dispose-error)]
    (try
      (root/drain-live-roots!)
      (catch :default e
        (vreset! drain-error e)))
    (try
      ((:dispose-adapter! spine-adapter))
      (catch :default e
        (vreset! spine-error e)))
    (let [[outcome reason]
          (dispose-outcome
           (not (identical? @drain-error no-dispose-error)) @drain-error
           (not (identical? @spine-error no-dispose-error)) @spine-error)]
      (when (= :throw outcome) (throw reason))))
  nil)

;; ---------------------------------------------------------------------------
;; flush-render! — the ViewCell-settling override
;; ---------------------------------------------------------------------------

(defn ^:no-doc flush-render!
  "Run `f` (default: nothing) and return with the Freehand DOM SETTLED — the
  adapter's `:flush-render!`, overriding the generic spine verb.

  The inherited spine verb is `react-dom/flushSync`, which commits the React
  work SCHEDULED inside its callback. That is not enough here, because a
  ViewCell invalidation schedules nothing synchronously: `cell/mark-dirty!`
  records the cause, enrols the cell in the open pending window and arms a LATER
  microtask. So a caller that flushed a thunk which dispatches — moving a
  subscribed value — would watch `flushSync` return while the cell was still
  pending and the DOM still stale.

  The fix is placement. The thunk AND the pending-window close (`cell/flush!`)
  both run inside ONE synchronous commit boundary, so the dirty cells' revisions
  advance, their `useSyncExternalStore` listeners fire, and React commits the
  result before this call returns.

  Write-all-queued-events THEN one read/render is preserved: a `dispatch-sync`
  inside the thunk runs its whole run-to-completion drain — every queued event,
  each committing its own epoch — BEFORE the single coalesced close renders. This
  is not a per-epoch renderer. Any redundant microtask armed during the drain
  later finds the window already closed and renders nothing.

  That ordering holds for a drain the thunk OPENS. A flush forced from INSIDE a
  drain that is already open — an event handler, or anything it calls, reaching
  for this verb — is the torn case, and it fails loud instead: the shared core
  guard throws `:rf.error/flush-in-open-epoch` before the window is touched, so
  no partial render phase is published while queued update/commit work is still
  outstanding. Same law, same id and same one implementation as the compiled-view
  substrate's flush, because [[re-frame.frame/guard-open-drain!]] closes over the
  router's drain state alone and no cell state (rf2-87ouj).

  Then it converges, because a commit can legitimately re-dirty: a layout effect
  that dispatches enrols a cell AFTER the pass that flushed it. Each further
  pass is its own commit boundary, and the whole drain rides
  [[re-frame.freehand.cell/converge-flush!]] — so a cascade that re-dirties
  every pass fails loud with `:rf.error/flush-convergence-exceeded` rather than
  spinning this synchronous call forever.

  A thunk that throws propagates unchanged, and the pending window is not closed
  on its behalf. The 0-arity closes whatever is already pending; a no-op flush
  stays a no-op."
  ([] (flush-render! (fn [] nil)))
  ([f]
   (frame/guard-open-drain! 're-frame.freehand.substrate/flush-render!)
   (let [spine-flush (:flush-render! spine-adapter)]
     (spine-flush (fn [] (f) (cell/flush!)))
     (cell/converge-flush! 're-frame.freehand.substrate/flush-render!
                           (fn [] (spine-flush cell/flush!))))
   nil))

;; ---------------------------------------------------------------------------
;; The adapter Var
;; ---------------------------------------------------------------------------

(def adapter
  "The Freehand adapter map — the closed substrate contract plus
  `:kind :rf.adapter/freehand`. Install it before the first frame exists:

      (require '[re-frame.freehand :as v])
      (rf/init! v/adapter)

  Published on the door as `v/adapter`. Installation is explicit; there is no
  default-adapter registry and no automatic selection. Unmount your Freehand
  roots with `v/unmount!` before `(rf/destroy-adapter!)`: disposal drains a
  forgotten root so it cannot outlive the adapter, but only the orderly verb can
  destroy a frame the root owned."
  (assoc spine-adapter
         :dispose-adapter! dispose-adapter!
         :flush-render!    flush-render!))
