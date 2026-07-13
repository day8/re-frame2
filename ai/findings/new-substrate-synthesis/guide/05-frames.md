# 05 — Frames

A frame is a running re-frame2 universe: app-db, subscriptions, event queue, epoch
history. Views live *inside* one. Two components manage that relationship, and their names
say which verb they perform.

*(Stage note: the mount grammar and frame-plan extraction are Stage 1; runtime ENSURE
preflight and provider scoping shipped with the S2 reactive core — all on main today.
The `(frame)` ops map *(lands S2)* is the remaining S2 piece; `ui/dispatch-fn` and
`effect` land S3.)*

## `frame-root` — ensure

```clojure
(ui/mount [ui/frame-root {:id :app :initial-events [[:app/init]]}
            [app-view]]
          root-el)
```

Creates the frame if absent, seeds `:initial-events` exactly once, and **never destroys it
on unmount** — mount again under the same `:id` (which is what a hot reload does) and the
live frame is reused without re-seeding. Your day-1 mount and your only boot ceremony.

## `frame-provider` — scope

```clojure
[ui/frame-provider {:frame preview-frame}
 [document-view {:id doc-id}]]
```

Pure context over an **already-live** frame — it creates nothing, seeds nothing, destroys
nothing. This is what Story, Xray, SSR hydration, and multi-frame demos use to point a
subtree at a frame that something else owns. The `:frame` value is a live frame
*handle* — minted by whatever owns the frame: your boot/event infrastructure, a test
harness, Story.

Mixing them up fails loudly with a did-you-mean: `frame-root` given `:frame`, or
`frame-provider` given `:id`, names the sibling you meant.

## Multi-frame pages

Frames are **isolated**. A page can mount several:

```clojure
(ui/mount [:div.page
           [ui/frame-root {:id :shop   :initial-events [[:shop/init]]}   [shop-app]]
           [ui/frame-root {:id :assist :initial-events [[:assist/init]]} [assistant]]]
          page-el
          {:root-id :page/home})
```

Each subtree's `sub` and handlers bind to *its* frame and only its frame — there is no
spelling for a cross-frame read in application code. Events in `:shop` cannot re-render
`:assist`. (One detail worth knowing: a root form that mounts two views, as this one
does, must author its `:root-id` — the derived default only exists when there is
exactly one mounted view, which is why the mount above spells out `:page/home`.
[08](08-ssr.md) has the full identity story.) Frames also pair naturally with server rendering — a page is N hydration
*roots* referencing whichever frames they need, several roots can share one frame, and
each root fails or hydrates independently ([08](08-ssr.md)). And you can mount one app N
times side-by-side (each instance its own frame universe) for comparison demos and tests.

## Holding a frame: `(frame)`

Data handlers and `sub` mean typical views never touch the frame directly. For the rare
imperative need — an effect that dispatches later, an interop callback that must carry the
frame out of the tree:

```clojure
;; guide:no-fixture — illustrative fragment, elided body
(ui/defview media-bridge [{:keys [stream-id]}]
  (let [dispatch! (ui/dispatch-fn)]
    (effect [stream-id]
      (let [stop (listen! stream-id #(dispatch! [::sample %]))]
        stop))
    …))
```

`(ui/dispatch-fn)` is one stable function per view instance, bound to the committed frame.
It fails loudly if called after the view disconnects — which turns a leaked foreign
listener into an error you see, instead of a dispatch into the void. The fuller ops map is
`(frame)` (`:frame`, `:dispatch`, `:dispatch-sync`, `:subscribe` — the standard
`capture-frame` bundle) when you need more than dispatch — say, a bridge that must
*name* its frame in the payload it sends out of the tree:

```clojure
(ui/defview error-reporter []
  (let [{:keys [frame dispatch]} (frame)]
    (effect :connect
      (let [handler #(dispatch [:errors/reported frame (.-message %)])]
        (js/window.addEventListener "error" handler)
        #(js/window.removeEventListener "error" handler)))
    nil))
```

Mount one per frame and each reports as itself — no globals, no ambient frame guessing.

## Lifecycle facts worth knowing

- A view mounted with no provider anywhere above it → `:rf.error/no-frame-context` at the
  first `sub`/handler. The runtime never invents a frame.
- Unmount releases the subtree's subscriptions and leases. So does hiding it inside a
  React `Activity` — with local state preserved and everything reacquired (and corrected)
  on reveal.
- Destroying a *frame* (tooling/tests) marks its views' cells dead: still-mounted views
  scoped to it render a loud error rather than silently migrating to another frame.
