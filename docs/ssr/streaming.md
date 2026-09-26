# Streaming: `ssr/boundary`

`ssr-handler` sends the page when all of it has rendered, so one slow region, such as
a comments section, holds up everything above it. Streaming sends the page shell on
the first byte with a fallback in place of each slow region, then sends each region as
its own chunk. A page with no independently slow region gains nothing from it; stay
with `ssr-handler`.

The runnable version is
[`examples/capabilities/ssr/ssr_streaming/`](../../examples/capabilities/ssr/ssr_streaming).

## Mark a slow region

Wrap the region in `ssr/boundary`, with an `:id` and a `:fallback` to show until it
arrives. Here the tutorial's article list gets a comments region:

```clojure
;; cf. examples/capabilities/ssr/ssr_streaming/core.cljc
;; in app.core, beside the tutorial's :articles/slice; requires rf and ssr
(rf/reg-sub :comments/recent (fn [db _] (:comments db)))

(rf/reg-view ^{:rf/id :comments/skeleton} comments-skeleton []
  [:section.comments [:p "Loading comments…"]])

(rf/reg-view ^{:rf/id :comments/list} comment-list []
  (into [:section.comments]
        (for [{:keys [id body]} @(subscribe [:comments/recent])]
          ^{:key id} [:p body])))

(rf/reg-view ^{:rf/id :app/root} root-view []
  (let [arts @(subscribe [:articles/slice])]
    [:main.page
     [:h1 "Recent articles"]
     (into [:ul] (for [{:keys [id title]} arts]
                   ^{:key id} [:li [:h3 title]]))
     [ssr/boundary {:id :region.comments :fallback [comments-skeleton]}
      [comment-list]]]))
```

The same view runs on both sides. On the server the boundary defers its body to a
later chunk; in the browser it renders the body. There is no server-only copy of the
view and no reader conditional.

Reference views inside a boundary by Var (`comment-list`, which `rf/reg-view` defines
for you) or by `(rf/view :id)`. A bare keyword head such as `[:comments/list]` is an
HTML element, never a view, on every host including the server: it paints
`<list>`, and a server-side test sees the same wrong element the browser would.

Each boundary `:id` must be unique on the page and stable across renders, because it
pairs an arriving chunk with its placeholder. You choose it; nothing generates one.

## Serve it

Use `stream-handler` in place of `ssr-handler`. It takes the same options:

```clojure
(require '[re-frame.ssr.ring :as ssr.ring])   ;; JVM only: inside #?(:clj …) in a .cljc ns

(def handler
  (ssr.ring/stream-handler
    {:initial-events [[:rf/server-init]]
     :root-view      (fn [] ((rf/view :app/root)))   ;; the fn form, so the tree is hashed
     :payload        [:articles :comments]}))       ;; the same allowlist as ssr-handler
```

A boundary rendered by `ssr-handler` or `render-to-string` throws
`:rf.error/ssr-suspense-boundary-outside-stream`.

## Hydrate on the client

Call `ssr/streaming-install!` with the frame you will hydrate, and hydrate from its
`:on-ready` callback. This is the tutorial's [client boot](tutorial.md#step-4--hydrate-on-the-client)
moved inside that callback:

```clojure
;; client-side requires, alongside rf and ssr:
;;   #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])
#?(:cljs (defonce app-root (reagent-adapter/client-root)))

#?(:cljs
   (defn run []
     (rf/init! reagent-adapter/adapter)
     (rf/make-frame {:id :app :platform :client})
     (ssr/streaming-install!
       {:frame    :app
        :on-ready (fn [_outcomes]
                    (let [payload (ssr/hydrate! {:frame          :app
                                                 :render-tree-fn (fn [] ((rf/view :app/root)))})
                          el      (js/document.getElementById "app")
                          tree    [rf/frame-provider {:frame :app} [(rf/view :app/root)]]]
                      ;; nil here means the payload arrived but was rejected, so
                      ;; mount fresh. A page with no payload never reaches :on-ready.
                      (reagent-adapter/render! app-root tree el
                        {:hydrate? (some? payload)})))})))
```

`:on-ready` fires once, after the last chunk has landed, every delta has been applied,
and the runtime has removed the `<rf-suspense>` wrapper elements it put around each
region while streaming. Hydrating earlier would meet those wrappers, which no render
tree contains, so React would find a mismatch at every boundary, discard the streamed
markup and render the page again.

So don't poll for `__rf_payload`, hydrate on a timer, or fall back to a fresh mount (a
`render!` without `:hydrate?`) because the payload has not arrived yet. On a live
stream that is true for most of the page's life, and a fresh mount throws away the
markup the server streamed.

A page that can also be served without streaming must check for the payload before
calling `streaming-install!`, or use the [non-streaming
boot](concepts.md#the-client-side-hydrate-then-verify).

## How a streamed page arrives

1. The shell arrives first. Each boundary's `:fallback` markup sits inside an inert
   `<template data-rf2-suspense-fallback>`. Template content never paints, so the
   fallbacks are not visible until `streaming-install!` turns each one into a visible
   mount.
2. Each region then arrives as its own chunk, also inside a `<template>`, with an
   app-db delta for that region, so its subscriptions see the right state when the
   runtime swaps the content in. Deltas go through the same `:payload` allowlist as
   the final payload.
3. The final chunk carries the complete hydration payload. The deltas are only a head
   start: if a delta and the payload disagree, the payload wins.

Because fallbacks and regions both arrive inside `<template>`s, a client that runs no
JavaScript, which includes most crawlers and link unfurlers, sees the shell with every
boundary region empty. Keep content that crawlers need outside any boundary.

## When a region fails

If a region's render throws on the server, that region keeps its fallback, a
`:rf.ssr/suspense-boundary-failed` trace fires, and the rest of the page streams on. A
failing comments service costs the comments region, not the page.

The final payload lists the failed boundaries. On the client, `ssr/boundary` reads
that list and renders its own `:fallback` for a failed region, which is the markup the
server left in the DOM. So `comment-list` needs no nil branch that repeats the
skeleton: it renders comments, and the boundary decides when to show
`comments-skeleton` instead.

## Troubleshooting

| Symptom | Error / behaviour | Fix |
|---|---|---|
| Boundary rendered without streaming | `:rf.error/ssr-suspense-boundary-outside-stream` | Serve the page with `stream-handler`, not `ssr-handler` / `render-to-string` |
| Boundary missing `:id` or `:fallback` | `:rf.error/suspense-boundary-invalid-attrs` | Give every boundary both keys |
| Duplicate boundary `:id` | `:rf.error/suspense-boundary-duplicate-id`; the last boundary registered gets the chunk and the earlier one stays on its fallback | Unique, stable ids per region |
| One region throws on the server | `:rf.ssr/suspense-boundary-failed`; that region keeps its fallback and the page continues | Fix the region's data or view |
| React discards the streamed markup | Hydrated mid-stream or on a timer | Hydrate only from `streaming-install!`'s `:on-ready` |
| `stream-handler` throws at construction | `:rf.error/ssr-streaming-unsupported-opt`: `:html-shell` or `:renderer` passed | Use the shell-hook options, or `ssr-handler` if you need a one-piece shell or the Node renderer |
| Page cut off part-way, status 200 | `:rf.error/ssr-streaming-writer-failed` (`:phase` names the chunk) | Read the record's exception. The status was already sent, so it cannot become an error page |
| A crawler or a client without JS sees empty regions | Fallbacks and regions both arrive inside inert `<template>`s | Expected. Keep content crawlers need outside any boundary |

## Advanced

### Production notes

- **Decide the response before the first byte.** The shell renders on the request
  thread, before the status and headers go out. A `:rf.server/redirect` from the
  drain returns a bodiless redirect and no stream at all; a shell that throws, or a
  projected 5xx, returns the ordinary error page (`:error-view`) under its projected
  status. After that the status is committed, so redirects and status writes belong
  in `:initial-events` and route handlers, never in a boundary region.
- **A failure after the head is committed truncates the page.** Other than a
  boundary's own render, which keeps its fallback, a throw while writing the rest of
  the stream closes the stream and emits the always-on
  `:rf.error/ssr-streaming-writer-failed` record, whose `:phase` names the chunk in
  flight. It cannot become an error page.
- **One thread per stream.** Each in-flight response holds one daemon thread; there is
  no framework pool or cap, so size your server's worker and accept-queue limits for
  the streams you expect. A body nobody reads is torn down after 60 seconds without
  progress.
- **`Content-Length` is removed**, whatever the drain set, so the server can choose
  chunked framing.
- **Boundaries nest.** A boundary inside another boundary's body registers while the
  outer region renders, and streams after every region already queued.
- **No `:html-shell` or `:renderer`.** `stream-handler` rejects both at construction
  (`:rf.error/ssr-streaming-unsupported-opt`), because it writes the document in
  pieces. Shape the envelope with `:head`, `:body-end`, `:script-src` and
  `:app-element-id` instead. The Node renderer is therefore not available for
  streamed pages.
- **`:on-ready` can run immediately.** If the whole response was already buffered
  when the bundle booted, `streaming-install!` finalises synchronously and calls
  `:on-ready` before it returns, so define everything the callback uses first.
- **`streaming-install!` returns `stop!`.** Calling it abandons the stream: nothing
  finalises and `:on-ready` never fires. The runtime disconnects itself when the
  final payload lands, so most apps never call it.

[`stream-handler`](../api/re-frame.ssr.ring.md#stream-handler) and
[`streaming-install!`](../api/re-frame.ssr.md#streaming-install) have the exact
contracts.

### Why the boundary is a component

`ssr/boundary` expands on the server to an internal `:rf/suspense-boundary` marker
that the streaming shell walker defers on. Never write that marker yourself. A hiccup
keyword head is an HTML element on every host, so a marker left in a client render
tree passes the DOM tag grammar and React paints a `<suspense-boundary>` element
without raising anything. The marker cannot be given client meaning either: Reagent's
element dispatch is an external dependency, and UIx views are `defui` / `$` forms
where a hiccup keyword head cannot occur. A callable component works on every host.
