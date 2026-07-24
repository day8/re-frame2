(ns re-frame.freehand.phase
  "The root RENDER PHASE — `:server` or `:client` — and the one flip that
  moves a hydrating root from the first to the second.

  A `v/client-only` site is **phase-conditional**: it renders its mandatory
  capability-free fallback in `:server` phase and its client subtree in
  `:client` phase. This namespace owns the phase itself: the root-scoped
  React context that carries it, the boundary component each site lowers
  to, and the flipper a hydrating root installs.

  ## The default is `:client`, so nothing but hydration changes

  The context default is `:client`. Every tree with NO phase provider above
  it — an ordinary `v/mount`, and the structural render on either host once
  it leaves this file's reach — reads that default and renders the client
  subtree on its first and only render. There is no fallback pass to flip
  away from, so a non-hydrating mount pays nothing and behaves exactly as it
  did before `v/client-only` existed.

  Only [[with-phase-flip]] — called by `v/hydrate-root`'s ADOPTION path and
  by nothing else — installs a provider, boots it `:server`, and flips it
  once.

  ## One root-scoped write, one update

  The phase is ONE root-scoped value written EXACTLY ONCE. That single write
  moves the root from `:server` to `:client`, and every client-only site in
  the root swaps in the one update it produces. There is deliberately no
  per-site flip state: N independent flips would swap N regions across N
  updates, so a page with several client-only regions would TEAR — some
  regions live while their neighbours still show fallbacks — and would pay N
  re-renders for one logical transition.

  Context propagation is what makes that a single update rather than a
  root-wide re-render: React re-renders a context CONSUMER directly, ignoring
  the memoised ViewCell boundaries above it, so only the client-only sites
  themselves re-render.

  Normative owner: [`spec/011-SSR.md` §Phase flip](../../../../spec/011-SSR.md#phase-flip);
  the boundary itself is [`spec/004-Views.md` §Client-only
  subtrees](../../../../spec/004-Views.md#client-only-subtrees)."
  (:require ["react" :as react]
            [re-frame.interop :as interop]
            [re-frame.trace :as trace]))

;; ---------------------------------------------------------------------------
;; The phase context
;; ---------------------------------------------------------------------------

(defonce ^:private phase-context
  ;; `defonce`, so a hot reload of this namespace cannot mint a SECOND context
  ;; object: a live provider and a freshly-reloaded consumer would then read
  ;; different contexts, and every mounted client-only site would silently
  ;; fall back to the default while a provider above it said otherwise.
  (react/createContext :client))

(defn ^:private ClientOnly
  ;; ONE client-only site. A React function component that SUBSCRIBES to the
  ;; root-scoped phase (`useContext`), returning the fallback in `:server`
  ;; phase and the client subtree in `:client` phase.
  ;;
  ;; A function component adds no DOM of its own, so the `:server`-phase
  ;; render is STRUCTURALLY the fallback and nothing else — which is exactly
  ;; the markup the server emitted, so React's hydration adopts it cleanly
  ;; rather than reporting a mismatch against a wrapper the server never
  ;; rendered.
  [^js props]
  (if (= :server (react/useContext phase-context))
    (.-fallback props)
    (.-children props)))

(when ^boolean js/goog.DEBUG
  (set! (.-displayName ClientOnly) "rf.freehand/client-only"))

(defn boundary
  "The React element ONE `(v/client-only {:fallback fb} child)` site lowers
  to. `fallback` and `child` are the two arms, ALREADY walked into React
  elements by the interpreted walk that saw them — so the client subtree's
  event sites belong to the candidate the enclosing body was rendering
  under, exactly as they would if the site were an ordinary child."
  [fallback child]
  (react/createElement ClientOnly #js {:fallback fallback :children child}))

;; ---------------------------------------------------------------------------
;; The flip
;; ---------------------------------------------------------------------------

(defn ^:private emit-phase-flip!
  ;; The ONE `:rf.ssr/phase-flip` diagnostic, at the flip commit. It rides the
  ;; diagnostic channel through `re-frame.trace/emit!`, and the
  ;; `interop/debug-enabled?` gate DCEs the whole call under `:advanced` +
  ;; `goog.DEBUG=false` (Spec 009 §Production builds).
  ;;
  ;; NOT an event, and it mints no epoch: the Spec 009 epoch unit is a
  ;; dequeued event, and this is render-layer bookkeeping on one root. It
  ;; fires from a React passive effect, outside any dispatch scope, so `emit!`
  ;; reads a nil handler scope and correlates it to nothing.
  [root-id]
  (when interop/debug-enabled?
    (trace/emit! :info :rf.ssr/phase-flip {:root-id root-id})))

(defn ^:private PhaseFlipper
  ;; Drives the flip for ONE hydrating root. It boots `:server` — so the
  ;; first render, which IS the hydration render, produces fallbacks matching
  ;; the server markup — and then flips to `:client` as the root's next
  ;; ordinary update.
  ;;
  ;; A PASSIVE effect (`useEffect`) does the flip, and that is what makes the
  ;; Spec 011 timing constraint hold BY CONSTRUCTION rather than by care:
  ;; passive effects run after the commit and after paint, so the flip runs
  ;; strictly after React has adopted — and so verified — the `:server`-phase
  ;; render. A flip that beat that check would swap in a `:client`-phase tree
  ;; the server never rendered and manufacture a spurious mismatch.
  ;;
  ;; A painted fallback frame before the swap is BY DESIGN. The fallback is
  ;; not a spinner to be hidden as fast as possible — it is mandatory,
  ;; presentable UI the reader has been looking at since first paint — so
  ;; there is nothing to race and no `flushSync` here.
  ;;
  ;; The same `:server` commit CLOSES the root's adoption window: the hydration
  ;; commit has landed, so a later recoverable error is no longer a hydration
  ;; mismatch. A hydrating root installs this flipper unconditionally on
  ;; adoption, so closing the window and scheduling the flip are the one effect
  ;; here — there is no separate no-flipper hydration path that would need
  ;; another closer.
  ;;
  ;; A root that FAILS to boot never commits this component, so its effect
  ;; never runs and it never flips: its server fallback markup stays on the
  ;; page, inert, which is exactly what capability-free markup is for. No
  ;; additional diagnostic is emitted — the boot failure already fired one.
  ;;
  ;; Per-root and independent: each flipper is keyed to its own root's commit,
  ;; so a slow or dead sibling never delays this flip and no page-wide barrier
  ;; exists to recouple roots that failed-root isolation decoupled.
  [^js props]
  (let [root-id   (.-rfRootId props)
        pair      (react/useState :server)
        phase     (aget pair 0)
        set-phase (aget pair 1)]
    (react/useEffect
      (fn phase-flip []
        ;; One effect, two commits. On the `:server` commit — the hydration
        ;; commit — it closes the adoption window and schedules the flip; on
        ;; the `:client` commit — the flip commit — it emits the trace. The
        ;; phase is monotonic (`:server` -> `:client`, once), so the trace
        ;; fires exactly once per hydrating root.
        (if (= :server phase)
          (do
            (when-some [adoption (.-rfAdoption props)]
              (set! (.-adopting adoption) false))
            (set-phase :client))
          (emit-phase-flip! root-id))
        js/undefined)
      #js [phase])
    (react/createElement (.-Provider phase-context)
                         #js {:value phase}
                         (.-children props))))

(when ^boolean js/goog.DEBUG
  (set! (.-displayName PhaseFlipper) "rf.freehand/phase-flipper"))

(defn with-phase-flip
  "Wrap a HYDRATING root's `element` in the phase flipper, and answer the
  wrapped element. Only `v/hydrate-root`'s adoption path calls this.

  A non-hydrating mount installs no flipper, so its tree carries no phase
  provider and every `v/client-only` site under it reads the `:client`
  default and renders its client subtree on the first render.
  `v/render-static` likewise installs none and stays pure `:server`.

  `adoption` is the root-local `#js {:adopting true}` window flag the
  hydration-mismatch reporter reads: the flipper clears it on its `:server`
  commit — the hydration commit — after which the reporter still delegates but
  no longer labels a recoverable error a hydration mismatch. Every hydrating
  root installs this flipper, so its `:server` commit is the one path that
  closes the window."
  [root-id element adoption]
  (react/createElement PhaseFlipper
                       #js {:key "rf-phase" :rfRootId root-id :rfAdoption adoption}
                       element))
