(ns re-frame.adapter.use-frame
  "Shared React-hook hold helper for the React-hook adapters (UIx):
  `use-frame` — `capture-frame` in hook position (rf2-y6dz8t).

  capture-frame is THE hold primitive; `reg-view` injection (Reagent) and
  `use-frame` (UIx) are its two ergonomic spellings. One primitive,
  three faces. This hook returns EXACTLY what `(rf/capture-frame)` returns —
  the frame-locked ops map `{:frame :dispatch :dispatch-sync :subscribe}` —
  for the ambient provider frame, so a hooks component stops retyping
  `(let [dispatch (:dispatch (rf/capture-frame))] …)` at every interactive
  call site. It is deliberately NOT a wider surface: no options map, no
  variants — anything that cannot be specced as capture-frame-in-hook-
  position belongs elsewhere. Per Spec 006 §Cross-substrate affordance
  summary and Spec 002 §capture-frame.

  ## Why here (core, CLJS-only)

  The hook body is substrate-agnostic — it uses `React/useContext` /
  `React/useRef` directly (like the spine's `use-subscribe`, the established
  shared-hook precedent), not any UIx-specific
  hook — so the UIx `use-frame` surface re-exports this ONE
  implementation with zero drift. It lives in the core artefact because
  core already `:require`s React (via `re-frame.adapter.context`), and both
  adapters depend on core. The Reagent family does NOT ship it: `reg-view`
  injection is Reagent's spelling of the same primitive.

  ## Frame resolution

  Identical to the spine's ambient `use-subscribe` (Spec 006 §Frame
  resolution (1-arg form), EP-0002): the hook subscribes to the shared
  frame React-context so a `frame-provider` swap re-renders the caller, but
  DISCARDS the raw context value and resolves through the carried-invariant
  chain (`frame/require-current-frame!`: dynamic-var → React-context → loud
  `:rf.error/no-frame-context`). No scope, no `:rf/default` floor — absence
  fails loud, exactly as the no-arg `capture-frame` does.

  ## Reference stability, and what it is keyed on

  The returned ops map is REFERENCE-STABLE across re-renders for the same
  resolved frame INCARNATION (safe in `useEffect` deps / memoized child
  props): a render-phase `useRef` memo-by-value keyed on the resolved
  frame by CLJS `=` (the rf2-mwft2 discipline — CLJS keywords are not
  `Object.is`-stable, so a raw deps-array memo would rebuild per render)
  AND on that frame's incarnation token by `identical?`. A provider swap
  re-renders the caller and returns a fresh map locked to the new frame.

  The incarnation half is load-bearing (rf2-40kv). A frame keyword is an
  ADDRESS, not an identity: destroy `:watchlist` and create another under
  the same id and the two are `=`, while `capture-frame` PINS the exact
  incarnation live when it ran (rf2-9pyles / rf2-tdjv7p — every op on the
  bundle refuses a superseded target rather than leaking into its
  successor). A memo keyed on the keyword alone therefore passes every
  stability test and fails exactly this one: it keeps handing out the DEAD
  incarnation's bundle for the rest of the mount, and every dispatch and
  subscribe through it recover-but-emits `:rf.error/frame-destroyed`
  against a frame the caller can plainly see is alive. Keying on
  `frame/frame-incarnation-token` — the `:drain-lock` identity, constant
  across one incarnation and distinct across a reconstruction — makes the
  reincarnation a memo MISS, so the next render carries ops pinned to the
  successor. This is the same rule Hicasso's `n/use-frame` keeps by
  memoising on the runtime's incarnation row."
  (:require ["react" :as React]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.core :as rf-core]
            [re-frame.frame :as frame]))

(defn use-frame
  "React hook (UIx): return the frame api for the ambient frame —
  EXACTLY what `(rf/capture-frame)` returns, captured in hook position:

      {:frame         <resolved-frame>
       :dispatch      (fn ([event] [event opts]))
       :dispatch-sync (fn ([event] [event opts]))
       :subscribe     (fn [query-v])}

      (defui counter-buttons []
        (let [count              (use-subscribe [:counter/value])
              {:keys [dispatch]} (use-frame)]
          ($ :button {:on-click #(dispatch [:counter/inc])} \"+\")))

  The ambient frame resolves through the same carried-invariant chain as
  the ambient `use-subscribe` — dynamic-var tier first, then the
  surrounding `frame-provider` / `frame-root` via React context — and with
  no scope in effect raises `:rf.error/no-frame-context` (never a synthetic
  `:rf/default`). The captured frame is authoritative: a per-call `:frame`
  in the dispatch opts cannot override it.

  The returned map is reference-stable across re-renders for the same
  resolved frame INCARNATION; a surrounding provider swapping frames
  re-renders the caller and yields a map locked to the new frame, and so
  does destroying the resolved frame and creating another under the same
  id — the bundle is pinned to the incarnation it was captured against,
  never to the address.

  No options map and no explicit-frame arity — this is capture-frame in
  hook position, nothing more. For an explicit frame there is no hook tax:
  call `(rf/capture-frame frame-id)` directly (no scope required)."
  []
  ;; Hook subscription to provider-value changes (re-render when the
  ;; surrounding frame-provider swaps frames) — same discipline as the
  ;; spine's ambient `use-subscribe`: the raw context value (which may be
  ;; the no-provider sentinel) is DISCARDED; resolution runs through the
  ;; carried-invariant chain below.
  (React/useContext adapter-context/frame-context)
  (let [;; All hooks run unconditionally BEFORE the loud resolution throw so
        ;; a scoped→unscoped render transition can never reorder hooks.
        ref   (React/useRef nil)
        frame (frame/require-current-frame!
                :use-frame {:where 're-frame.adapter.use-frame/use-frame})
        ;; The identity half of the key (rf2-40kv). `capture-frame` pins the
        ;; incarnation live when IT runs; read the same identity here so the
        ;; memo's notion of "same frame" is the bundle's. nil — the id is not
        ;; live at render — is a legitimate key: the bundle it pairs with is
        ;; the unpinned, address-directed capture, and the frame appearing
        ;; later moves the token off nil and correctly misses.
        token (frame/frame-incarnation-token frame)
        prev  (.-current ref)]
    ;; Render-phase memo-by-value (rf2-mwft2 pattern): rebuild the ops map
    ;; ONLY when the resolved frame changes by `=` OR its incarnation changes
    ;; by `identical?`. The ref write during render is sanctioned for exactly
    ;; this pattern — idempotent given identical inputs, never mutated after
    ;; commit.
    (if (and (some? prev)
             (= (aget prev 0) frame)
             (identical? (aget prev 1) token))
      (aget prev 2)
      (let [ops (rf-core/capture-frame frame)]
        (set! (.-current ref) #js [frame token ops])
        ops))))
