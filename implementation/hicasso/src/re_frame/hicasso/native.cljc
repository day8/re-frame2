(ns re-frame.hicasso.native
  "The two React hooks that join a React island to the Hicasso frame it is
  mounted in — `use-sub` reads a subscription, `use-frame` answers the
  frame's operations — and nothing else. An island is a UIx `defui` or a
  raw React function component, mounted through `h/defhost` or `[:>]`; it
  requires this namespace only when it needs Hicasso state, and nothing in
  `re-frame.hicasso` requires it, so an application with no island carries
  none of it. React's own hooks are reached by direct `[\"react\"]` interop
  and none are wrapped here: what React cannot supply is the frame, so the
  frame is all these two supply.

  docs/design/hicasso/product/lanes/design-laws.md, Native boundary."
  #?(:cljs (:require ["react" :as react]
                     [re-frame.adapter.context :as rf.adapter.context]
                     [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector])))

#?(:cljs
   (do
     (defn use-frame
       "Frame-locked operations for the frame this island is mounted in —
  `rf/capture-frame`'s bundle, `{:frame :dispatch :dispatch-sync :subscribe}`.

  A React hook: top level of the component, unconditional. The frame is
  the surrounding tree's — the one React context the boundary shell reads
  — and no argument reaches another; for a named frame call
  `(rf/capture-frame frame-id)` directly. The map is the same object on
  every render under one frame INCARNATION, so it is safe in effect deps
  and safe to close over; destroy the frame and recreate it under the same
  id and the next render gets the successor's ops, while a callback still
  holding the predecessor's is refused by core's `:rf.error/frame-destroyed`
  fence. A memo keyed on the frame keyword would pass every stability test
  and fail exactly that one, which is why the row is incarnation-pinned.
  Rendering outside every frame refuses with `:rf.error/no-frame-context`.

      (defui col-resizer [_]
        (let [{:keys [dispatch]} (n/use-frame)]
          ($ :div {:on-pointer-up (fn [_] (dispatch [:col/commit]))})))

  docs/design/hicasso/product/lanes/design-laws.md, Native boundary."
       []
       (let [frame-kw (rf.hicasso.impl.collector/resolve-frame!
                        (react/useContext rf.adapter.context/frame-context)
                        're-frame.hicasso.native/use-frame)]
         (:ops (rf.hicasso.impl.collector/frame-row frame-kw))))

     (defn use-sub
       "The current value of the subscription `query-v` names, read under
  the frame this island is mounted in.

  A React hook: top level, unconditional, one call per read — two calls
  are two subscriptions where a `defview` body's several `h/sub` reads are
  one. It hands `useSyncExternalStore` the same `subscribe` and
  `getSnapshot` a boundary reading this key gets, so the read builds the
  same cell, joins the same reader membership and residue census, wakes on
  the same commit and appears in the same `re-frame.hicasso.tool` rosters
  Xray reads. A re-render that changed no read performs no re-subscribe;
  unmount releases what mount acquired, StrictMode's double mount included.
  A commit observed through it is a BLOCKING update — React's rule for an
  external store — so nothing here is transition-aware, and it is not a
  door to a promise-driven resource. Rendering outside every frame refuses
  with `:rf.error/no-frame-context`.

      (defui ticker [{:keys [sym]}]
        ($ :span (n/use-sub [:quote/price sym])))

  docs/design/hicasso/product/lanes/design-laws.md, Native boundary."
       [query-v]
       (let [frame-kw  (rf.hicasso.impl.collector/resolve-frame!
                         (react/useContext rf.adapter.context/frame-context)
                         're-frame.hicasso.native/use-sub)
             ;; The refusal sits BEFORE the store hook: a render that throws
             ;; never reaches React's hook reconciliation, so no hook count
             ;; can disagree with a previous render's.
             sub-key   [frame-kw query-v]
             ^js entry (rf.hicasso.impl.collector/hook-entry sub-key)]
         ;; The snapshot is an epoch, not the value: one monotone number
         ;; React compares with `Object.is`, and the value is read after it
         ;; from the same synchronous instant. The third argument is the
         ;; same closure for the same reason.
         (react/useSyncExternalStore (.-subscribe entry) (.-snapshot entry)
                                     (.-snapshot entry))
         (rf.hicasso.impl.collector/hook-read sub-key)))))
