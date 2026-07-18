# 05 — Frames

A frame is a running re-frame2 universe: app-db, subscriptions, event queue, epoch
history. Views live *inside* one.

On day one you need one frame and one component to stand it up: `frame-root`, which you
already wrote in [01](01-getting-started.md). Read the next section, then come back to
the rest of this page when a page needs more than one world on it.

Two components manage the view-to-frame relationship, and their names say which verb
each performs.

## `frame-root` — ensure

```clojure
;; guide:target dom
(ui/mount [ui/frame-root {:id :app :initial-events [[:app/init]]}
            [app-view]]
          root-el)
```

Creates the frame if absent, seeds `:initial-events` exactly once, and **never
destroys it on unmount**. Mount again under the same `:id` (which is what a hot
reload does) and the live frame is reused without re-seeding. Your day-1 mount and
your only boot ceremony.

## `frame-provider` — scope

```clojure
[ui/frame-provider {:frame preview-frame}
 [document-view {:id doc-id}]]
```

Pure context over an **already-live** frame — it creates nothing, seeds nothing,
destroys nothing. Story, Xray, SSR hydration, and multi-frame demos use it to point a
subtree at a frame something else owns. The `:frame` value is a live handle, minted
by whatever owns the frame: boot code, a test harness, Story.

Mixing them up fails loudly with a did-you-mean: `frame-root` given `:frame`, or
`frame-provider` given `:id`, names the sibling you meant.

## Multi-frame pages

Frames are **isolated**. A page can mount several:

```clojure
;; guide:target dom
(ui/mount [:div.page
           [ui/frame-root {:id :shop   :initial-events [[:shop/init]]}   [shop-app]]
           [ui/frame-root {:id :assist :initial-events [[:assist/init]]} [assistant]]]
          page-el
          {:root-id :page/home})
```

Each subtree's `sub` and handlers bind to *its* frame and only its frame — there is
no spelling for a cross-frame read in application code. Events in `:shop` cannot
re-render `:assist`.

!!! note "Root identity"
    A root form that mounts two views must author its `:root-id` — the derived
    default only exists when there is exactly one mounted view. Full identity story:
    [11](11-ssr.md).

Frames also pair naturally with server rendering: a page is N hydration roots
referencing whichever frames they need; several roots can share one frame; each root
fails or hydrates independently ([11](11-ssr.md)).

!!! tip "See it live"
    Mount the two-frame page, open Xray, and dispatch into `:shop` — its epoch
    counter advances while `:assist` sits still. Frame isolation is something you can
    watch.

## Holding a frame: `(frame)` and `dispatch-fn`

Data handlers and `sub` mean typical views never touch the frame directly. For the
rare imperative need — an effect that dispatches later, an interop callback that must
carry the frame out of the tree:

```clojure
;; guide:no-fixture — illustrative fragment, elided body
(ui/defview media-bridge [{:keys [stream-id]}]
  (let [dispatch! (ui/dispatch-fn)]
    (effect [stream-id]
      (let [stop (listen! stream-id #(dispatch! [::sample %]))]
        stop))
    …))
```

`(ui/dispatch-fn)` is one stable function per view instance, bound to the committed
frame. It fails loudly if called after the view disconnects — a leaked foreign
listener becomes an error you see, not a dispatch into the void.

The fuller ops map is `(frame)` (`:frame`, `:dispatch`, `:dispatch-sync`,
`:subscribe` — the standard `capture-frame` bundle) when you need more than dispatch:

```clojure
(ui/defview error-reporter []
  (let [{:keys [frame dispatch]} (frame)]
    (effect :connect
      (let [handler #(dispatch [:errors/reported frame (.-message %)])]
        (js/window.addEventListener "error" handler)
        #(js/window.removeEventListener "error" handler)))
    nil))
```

Mount one per frame and each reports as itself — no globals, no ambient frame
guessing.

## Lifecycle facts

- A view with no provider above it → `:rf.error/no-frame-context` at the first
  `sub`/handler. The runtime never invents a frame.
- Unmount releases the subtree's subscriptions and leases. So does hiding it inside
  a React `Activity` — local state preserved; everything reacquired (and corrected)
  on reveal.
- Destroying a *frame* (tooling/tests) marks its views' cells dead: still-mounted
  views render a loud error rather than silently migrating to another frame.
