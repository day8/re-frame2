# Frames and reusable UI

## Every operation has a frame

re-frame2 does not infer a process-global default when frame context is absent. A `defview` resolves one frame during render and commits that identity with its subscriptions, events, effects, and resource leases.

If no frame is available, the operation fails with `:rf.error/no-frame-context`. This is preferable to rendering plausible data from the wrong application/session/preview.

## Root frame

```clojure
(ui/render! root app {}
  {:frame {:id :app/main
           :images [main-image]
           :initial-events [[::initialize]]}})
```

The root frame config uses re-frame2's ensure semantics:

- create `:app/main` if absent;
- seed images/run initial events only for creation;
- reuse the live frame without reseeding on later renders/hot reload;
- provide the ID through React context;
- do not destroy the frame on unmount.

Frame destruction is an explicit application lifecycle operation.

To mount a root against a frame already created by infrastructure/tests, use the scope shape:

```clojure
(ui/render! root app {}
  {:frame {:frame existing-frame}})
```

`existing-frame` may be the live frame value returned by `rf/make-frame` or its registered ID. This form never creates, refreshes, seeds, or destroys the target.

## Existing-frame scope

Render a subtree against an already-created frame:

```clojure
[ui/frame {:frame :preview/document}
 [document-preview {:document-id id}]]
```

The scope shape requires the frame to be live. It creates, refreshes, seeds, and destroys nothing.

Every descendant `ui/sub`, event vector, `ui/dispatch-fn`, and `ui/lease` uses `:preview/document` unless explicitly pinned or nested under another scope.

## Ensure a named nested frame

```clojure
[ui/frame
 {:id :sandbox/editor
  :images [editor-image]
  :initial-events [[::editor-opened document-id]]}
 [editor {:document-id document-id}]]
```

This is useful when the UI is the natural place that ensures a durable named frame exists, but its lifetime still outlives an incidental React remount. Explicit application code decides when to call `rf/destroy-frame!`.

Do not generate a new frame ID during render. A changing ID means a changing state universe and will retarget every child dependency/event.

## Reusable views inherit

A reusable view should not hardcode an application frame:

```clojure
(ui/defview save-button [{:keys [document-id]}]
  (let [saving? (ui/sub [::saving? document-id])]
    [:button
     {:disabled saving?
      :on-click [::save document-id]}
     (if saving? "Saving…" "Save")]))
```

The same component works under main, preview, test, story, or tenant frames. The surrounding scope determines which registrations/state instance it observes.

Hardcode a frame only for infrastructure that is explicitly cross-frame, such as a frame comparison tool.

## Frame changes are commit-safe

Suppose a parent changes a subtree from frame A to frame B while React is concurrently rendering:

- visible old DOM callbacks continue to dispatch to committed frame A;
- the speculative render probes subscriptions in frame B but owns none;
- commit acquires B dependencies before releasing A dependencies;
- layout commit publishes B as the callback/resource target;
- user interaction after commit dispatches to B.

There is no interval where an A DOM button sends to speculative B.

## Explicit-frame subscription

```clojure
(ui/sub :left/frame [::document id])
```

Use this in comparison/diagnostic components where one view intentionally reads multiple frames:

```clojure
(ui/defview diff-view [{:keys [id]}]
  (let [left  (ui/sub :left/frame [::document id])
        right (ui/sub :right/frame [::document id])]
    [document-diff {:left left :right right}]))
```

Events remain ambiguous in such a view unless their target is explicit. Prefer a child scoped to the intended frame, or create an explicit dispatcher from the frame API rather than relying on the ambient cell.

## Dispatcher for effects

```clojure
(let [dispatch! (ui/dispatch-fn)]
  (react/use-effect [socket]
    (fn []
      (socket/listen! socket
        #(dispatch! [::socket-message %])))))
```

`dispatch-fn` reads the cell's committed frame. It is stable and safe across the effect's async callback boundary. It fails while the view is Activity-disconnected/unmounted and after permanent disposal instead of guessing a new context.

For a deliberate explicit target, use `(rf/capture-frame frame-target)` and take its `:dispatch` operation. The returned bundle captures that target permanently. If it is created in a view and callback identity affects an effect, memoize the bundle by `frame-target` and include the resulting dispatch function in the effect dependencies.

## Foreign React components

Frame context crosses ordinary foreign React components when they render the supplied children:

```clojure
[ForeignLayout {}
 [save-button {:document-id id}]]
```

The foreign component does not need to know re-frame2. A foreign render callback creates its React subtree under the current context when expressed as pure `ui/render-fn`; return a named child `defview` from it when that subtree needs subscriptions or Hooks.

A foreign component that imperatively calls a callback receives `ui/event`, `ui/handler`, or a captured dispatcher; dynamic frame bindings do not survive that boundary by themselves.

## Portals

```clojure
(ui/portal modal-root
  [confirm-dialog {:confirm-event [::confirmed item-id]}])
```

React context follows the logical portal tree, so the dialog inherits the originating frame despite rendering under another DOM node. Xray records both logical parent and physical portal target.

## Frame destruction

```clojure
(rf/destroy-frame! :preview/document)
```

Destroy only when the application has ended that frame's lifetime. The adapter:

- releases derivation and resource ownership targeting the frame;
- removes pending cell invalidations;
- disposes frame state under core rules;
- reports a still-mounted view that now has no live target.

It does not migrate the subtree to its parent frame. Render a different tree or scope before/with destruction as appropriate for the application transition.

## Multiple independent roots

Each root can provide a different frame:

```clojure
(ui/render! main-root main-app {} {:frame {:id :app/main}})
(ui/render! admin-root admin-app {} {:frame {:id :app/admin}})
```

The adapter remains process-wide, but state, caches, events, resources, and view dependencies remain frame-qualified. Shared registration definitions are normal; live values are separate.

## Story and test frames

A story can construct a frame, seed it, render the same view, then destroy it:

```clojure
(rf/make-frame {:id :story/order-card
                :images [story-image]
                :initial-events [[::seed-order sample-order]]})

(ui/render! story-root order-card {:order-id 42}
  {:frame {:id :story/order-card}})
```

Because views inherit rather than hardcode, no production component changes for stories/tests.

## Common mistakes

| Mistake | Result | Better shape |
|---|---|---|
| Rendering without a root/provider | Loud no-frame error | Supply root `:frame`. |
| Hardcoding `:app/main` in reusable components | Stories/previews read wrong state | Inherit ambient frame. |
| Destroying in component cleanup | Strict Mode/HMR can erase durable state | Application lifecycle owns destroy. |
| Generating frame ID in render | State universe changes/remounts | Stable named ID from application data. |
| Reading frame A and ambient-dispatching an action meant for A while scoped to B | Wrong target | Scope a child or use explicit frame API. |
| Assuming a portal changes frame | Incorrect mental model | Portals keep logical React context. |
