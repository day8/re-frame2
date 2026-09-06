(ns re-frame.hicasso.substrate
  "Hicasso's own reactive-substrate adapter — the value an application
  installs with `(rf/init! substrate/adapter)` before it mounts anything.

  ## Why Hicasso owns one

  An adapter is not a renderer. Hicasso already renders itself, through
  `react-dom/client`, and interprets its own Hiccup; what the substrate
  contract asks for is the OBSERVATION half — the container `app-db` lives
  in, and a derived value that says when it moved. The two halves are
  independent, and owning both is what lets a Hicasso application depend on
  core plus Hicasso and nothing else: without this namespace an interactive
  one would have to add a SECOND dependency coordinate —
  `day8/re-frame2-uix` or a Reagent one — purely to obtain the second half,
  and then never write a line of that substrate's notation.

  This is an OPTION, not a replacement. Reagent, reagent-slim and UIx
  remain first-class, independently supported adapters, and installing one
  of them under a Hicasso tree is supported — a Hicasso subtree and a UIx
  subtree resolve the same frame, because every React-shaped adapter reads
  the one shared context object
  (`re-frame.adapter.context/frame-context`).

  ## What it stands on

  `re-frame.substrate.spine` — core's shared spine for \"React-shaped adapters
  that lack a native reactive-atom primitive\", which is what Hicasso is. The
  spine's `make-derived-value` wires ONE WATCH PER SOURCE at construction and
  coalesces through a per-adapter epoch scheduler, so a derived value is live
  the moment it exists.

  That push-from-birth property is the one the collector needs and the one
  `re-frame.substrate.plain-atom` cannot give it. Plain-atom's derived value
  reifies `IDeref` and disposal and nothing else — no watch, recomputed on
  every deref, which is right for a headless SSR render and wrong under a live
  view. `re-frame.hicasso.impl.collector/wire-cell!` installs a watch on the
  reaction it subscribes and marks its cell dirty when the value moves; under
  plain-atom that watch fires never, so the runtime paints once and is deaf
  thereafter.

  Watchability alone would not settle it either. A `reagent.ratom/Reaction` is
  `IWatchable` and still notifies nobody until `deref-capture` teaches it its
  sources, which is why the collector ACTIVATES a subscription
  (`re-frame.interop/activate-derived-value!`) before watching it. On this
  spine that activation is a routed no-op, because the watch is already wired.

  So Hicasso supplies React's own `useMemo` / `useCallback` / `useContext` and
  takes no new dependency: it already requires `react`, and the spine already
  lives in core.

  ## An OPTIONAL module, on purpose

  Nothing under `src/` requires this namespace, so a build that never asks for
  it carries neither it nor the spine — the same reachability property
  `re-frame.hicasso.forms`, `.overlay`, `.motion` and `.native` have, and
  `scripts/check_optional_module_reachability.py` is what checks it at the
  door. That is what keeps the cost off an application that deliberately
  installs Reagent instead, and it is why the adapter is not re-exported from
  the public door.

  ## Why the namespace is `substrate` and not `adapter`

  A ClojureScript namespace and a Var cannot share a JS path. Were this
  namespace called `re-frame.hicasso.adapter`, it would occupy the same path
  as an `adapter` Var on the public door, and the compiler's `:ns-var-clash`
  would leave one of the two reading `nil`. The machinery is therefore homed
  under `substrate`.

  INTERNAL, apart from `adapter`. Hicasso re-exports none of the spine's
  hook surfaces: a body reads through `h/sub` and the collector, and a second
  read path would be a second commit discipline.

  Normative owner:
  [`spec/006-ReactiveSubstrate.md`](../../../../../spec/006-ReactiveSubstrate.md)
  §The adapter API contract."
  (:require ["react" :as react]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.spine :as rf.substrate.spine]
            [re-frame.views.frame-boundary :as rf.views.frame-boundary]))

;; ---------------------------------------------------------------------------
;; The spine
;; ---------------------------------------------------------------------------

(def ^:no-doc spine-fns
  "INTERNAL — the substrate-spine surface map this adapter is assembled from.

  Public only so the shared React-adapter conformance suite in core
  (`re-frame.adapter.react-shared-suite`) can assert the surfaces the spine
  produced: their presence, their kind, and that no two of them are the same
  fn object, which is what catches a cross-wired spine key. None of it is
  application API — Hicasso publishes `adapter` and reads subscriptions
  through `h/sub`."
  (rf.substrate.spine/make-react-spine
    {:substrate-name        "Hicasso"
     :gensym-prefix-sub     "rf-hic-sub-"
     :gensym-prefix-derived "rf-hic-derived-"
     :gensym-prefix-use-sub "rf-hic-use-sub-"
     :use-memo              react/useMemo
     :use-callback          react/useCallback
     :use-context           react/useContext}))

;; ---------------------------------------------------------------------------
;; The contract's frame-provider slot
;; ---------------------------------------------------------------------------

(defn- frame-provider
  "The contract's `:register-context-provider` component — a plain React
  function component writing the shared frame context.

  This is the LOWER-LEVEL contract slot rather than an authoring verb.
  Hicasso scopes a root's frame on the way in
  (`re-frame.hicasso.impl.mount/provider`, from `h/mount!`), so a Hicasso
  application never writes this head; the slot exists because the contract has
  it, because core's own provider tier reaches for it, and because writing the
  same one context every React-shaped adapter reads is what lets a mixed
  Hicasso / UIx provider tree compose.

  Plain React rather than a minted boundary: there is no marshalling to do
  here — the props arrive as React hands them over — so there is nothing for a
  wrapper to preserve and no reason to depend on one."
  [^js props]
  (let [frame-kw (rf.frame/require-frame-provider-target!
                   (.-frame props)
                   're-frame.hicasso.substrate/frame-provider)]
    (rf.views.frame-boundary/require-live-frame-for-scope!
      frame-kw 're-frame.hicasso.substrate/frame-provider)
    (apply rf.adapter.context/provider-element
           frame-kw
           (rf.adapter.context/normalize-children (.-children props)))))

;; ---------------------------------------------------------------------------
;; The adapter Var
;; ---------------------------------------------------------------------------

(def adapter
  "The Hicasso adapter map — the substrate contract plus
  `:kind :rf.adapter/hicasso`. Install it before the first frame exists:

      (require '[re-frame.core :as rf]
               '[re-frame.hicasso :as h]
               '[re-frame.hicasso.substrate :as substrate])

      (rf/init! substrate/adapter)
      (h/mount! [app] (js/document.getElementById \"app\"))

  Installation is explicit and there is no default-adapter registry, so a
  Hicasso application that installs Reagent or UIx instead keeps working and
  never reaches this namespace.

  `spine/make-react-adapter` owns the shared React-hook routing and lifecycle
  wiring — the nine-key contract map, the five routed late-bind hooks, and the
  two chained installs (the warn-once clear and the SSR hiccup emitter). The
  frame-provider stays here because the spine carries no substrate's own
  element machinery."
  (rf.substrate.spine/make-react-adapter spine-fns
                            {:kind           :rf.adapter/hicasso
                             :frame-provider frame-provider}))
