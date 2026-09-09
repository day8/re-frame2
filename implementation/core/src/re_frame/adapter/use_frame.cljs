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
  `React/useRef` directly (like the spine's `use-sub`, the established
  shared-hook precedent), not any UIx-specific
  hook — so the UIx `use-frame` surface re-exports this ONE
  implementation with zero drift. It lives in the core artefact because
  core already `:require`s React (via `re-frame.adapter.context`), and both
  adapters depend on core. The Reagent family does NOT ship it: `reg-view`
  injection is Reagent's spelling of the same primitive.

  ## Frame resolution — React context, and nothing else

  Identical to the spine's ambient `use-sub` (Spec 006 §Frame resolution
  (1-arg form), EP-0002): the hook reads the shared frame React-context —
  which both subscribes the caller to a `frame-provider` swap and IS the
  answer — and classifies that value once, through
  `rf.adapter.context/context-value->current-frame`. There is no
  dynamic-var tier: a `with-frame` / `bind-fn` scope around a synchronous
  render does not reach a hook, and no boundary above means a loud
  `:rf.error/no-frame-context` with no `:rf/default` floor, exactly as the
  no-arg `capture-frame` does.

  WHY THE HOOK AND THE IMPERATIVE READ DIVERGE HERE (rf2-kuky.61 / .62).
  The two faces of `capture-frame` run at different instants. The
  imperative call runs INSIDE the extent that scoped it; this hook runs
  when React renders, by which time a body's extent has unwound — so the
  var tier can only answer for a different render than the one asking, and
  whether it answers at all depends on the scheduling mode (`act()`,
  `flushSync` and a server render put the body on the calling stack; a
  scheduled update does not). A hook reading a JS-thread global during a
  React render is hidden context in the one place React identity matters
  (`spec/Principles.md` §Low hidden context). The rule this buys is the
  one the design asks for: the same component tree resolves the same frame
  under either scheduling mode. The explicit override for a test or a
  harness is a `frame-provider` wrapper, which survives a scheduling
  change; dynamic scope stays the imperative tier's.

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
  `rf.frame/frame-incarnation-token` — the `:drain-lock` identity, constant
  across one incarnation and distinct across a reconstruction — makes the
  reincarnation a memo MISS, so the next render carries ops pinned to the
  successor. This is the same rule Fresco's `n/use-frame` keeps by
  memoising on the runtime's incarnation row."
  (:require ["react" :as React]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]))

(defn use-frame
  "React hook (UIx): return the frame api for the ambient frame —
  EXACTLY what `(rf/capture-frame)` returns, captured in hook position:

      {:frame         <resolved-frame>
       :dispatch      (fn ([event] [event opts]))
       :dispatch-sync (fn ([event] [event opts]))
       :subscribe     (fn [query-v])}

      (defui counter-buttons []
        (let [count              (use-sub [:counter/value])
              {:keys [dispatch]} (use-frame)]
          ($ :button {:on-click #(dispatch [:counter/inc])} \"+\")))

  The ambient frame is the surrounding `frame-provider` / `frame-root`
  read from React context, and nothing else — the same one rule the
  ambient `use-sub` follows. A `with-frame` / `bind-fn` dynamic scope
  around a synchronous render does NOT reach this hook; with no boundary
  above it this raises `:rf.error/no-frame-context` (never a synthetic
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
  ;; ONE TIER (rf2-kuky.62). The `useContext` read does both jobs at once:
  ;; it subscribes the caller to provider-value changes (so a
  ;; `frame-provider` swap re-renders it) AND it is the resolution itself.
  ;; The renderer-agnostic `useContext` return is the right value to
  ;; classify on both renderers — under `react-dom/server` React reads the
  ;; SECONDARY `_currentValue2` slot, which the private-slot reader in
  ;; `re-frame.adapter.context` has to fall back to by hand.
  (let [ctx-value (React/useContext rf.adapter.context/frame-context)
        ;; All hooks run unconditionally BEFORE the loud resolution throw so
        ;; a scoped→unscoped render transition can never reorder hooks.
        ref   (React/useRef nil)
        ;; `context-value->current-frame` is the SHARED classifier: the
        ;; no-provider sentinel → nil ('no scope', the benign case), a frame
        ;; keyword → that frame, anything else → the distinct
        ;; `:rf.error/frame-context-corrupted` diagnostic and nil.
        ;; `require-frame-stamp!` then emits + throws the same
        ;; `:rf.error/no-frame-context` payload `require-current-frame!`
        ;; used to emit here, so nothing about error reporting moved.
        frame (rf.frame/require-frame-stamp!
                (rf.adapter.context/context-value->current-frame ctx-value)
                :use-frame {:where 're-frame.adapter.use-frame/use-frame})
        ;; The identity half of the key (rf2-40kv). `capture-frame` pins the
        ;; incarnation live when IT runs; read the same identity here so the
        ;; memo's notion of "same frame" is the bundle's. nil — the id is not
        ;; live at render — is a legitimate key: the bundle it pairs with is
        ;; the unpinned, address-directed capture, and the frame appearing
        ;; later moves the token off nil and correctly misses.
        token (rf.frame/frame-incarnation-token frame)
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
      (let [ops (rf/capture-frame frame)]
        (set! (.-current ref) #js [frame token ops])
        ops))))
