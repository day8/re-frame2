# Streaming: `ssr/boundary`

You know [plain SSR](concepts.md) — one drain, one HTML string, one payload. This page
is one job: **ship a shell on the first byte, then stream slow regions in**.

React 18 / Next.js `loading.js` use `<Suspense>`; re-frame2 uses one component.
Runnable tree:
[`examples/capabilities/ssr/ssr_streaming/`](../../examples/capabilities/ssr/ssr_streaming).
The Ring adapter is in the [tutorial](tutorial.md#step-7--swap-in-the-ring-adapter).

!!! note "Don't reach for streaming by default"

    No independently-slow regions → plain `ssr-handler` is enough. A
    boundary on the non-streaming emitter fails loud.

## Mark slow regions

```clojure
;; Adapted from examples/capabilities/ssr/ssr_streaming/core.cljc
(require '[re-frame.ssr :as ssr])

(rf/reg-view ^{:rf/id :article/page} article-page []
  [:main.article-page
   [:header [:h1 @(rf/subscribe [:article/title])]]
   [:div.article-body @(rf/subscribe [:article/body])]
   [:section.article-extras
    [ssr/boundary
     {:id :region.comments :fallback [comments-skeleton]}
     [comments]]
    [ssr/boundary
     {:id :region.author-feed :fallback [author-feed-skeleton]}
     [author-feed]]]])
```

**One view, both runtimes.** On the server each boundary defers its body; in the
browser the same form renders that body. There is no server-flavoured copy of
the view and no reader conditional — this is an ordinary `reg-view` that happens
to name two regions as "allowed to arrive late".

Reference views inside a boundary by **Var** (`comments`, which `rf/reg-view`
defs for you) or by `(rf/view :id)` lookup — a bare `[:article/comments]` head is
an *HTML element*, never a view, so it paints `<comments>` on every host, server
included.

That rule used to have an exception, and the exception was the dangerous part:
the JVM SSR emitters resolved keyword heads through the view registry, so the
same hiccup meant "registered view" on the server and "an HTML element" in the
browser. The server rendered it *correctly*, which meant no server-side test
could catch it and only the client went wrong — silently, as wrong pixels rather
than an error. That exception is gone (rf2-j81hs): both emitters now treat a
keyword head as an element, matching every client substrate.

That same rule is why the boundary is a **component** and not a hiccup keyword.
`:rf/suspense-boundary` still exists, but it is internal wire syntax between
`ssr/boundary` and the streaming shell walker — never something you write. Left
in a client render tree its name passes the DOM tag grammar, so React paints a
phantom `<suspense-boundary>` element rather than raising anything. And it could
not simply be taught client semantics either: stock Reagent's element dispatch is
an external dependency, and UIx views are `defui` / `$` forms where a
hiccup keyword head cannot occur at all. A callable component is the one form
that works everywhere.

The streaming walker emits the shell on the first byte, with each boundary's
`:fallback` markup carried inside an **inert** `<template data-rf2-suspense-fallback>`
marker. A `<template>`'s content is inert by the HTML spec — a detached
`DocumentFragment` that never paints — so the fallbacks are *not* visible first-byte
UI. They become visible only once the client runtime (`ssr/streaming-install!`,
below) materialises each inert `<template>` into a live, painted mount. Each
boundary's subtree then streams as its own chunk, carrying a per-subtree app-db delta
so that region's subscriptions see the right state when its resolved content swaps in.

## Failure isolation

If one boundary's render throws, *that region* keeps its fallback (with a
`:rf.ssr/suspense-boundary-failed` trace) and the rest of the page streams on. A
flaky comments service cannot 500 the whole page — blast radius is one boundary.

## Wiring

Use the streaming Ring constructor (re-exported on `re-frame.ssr.ring`):

```clojure
(require '[re-frame.ssr.ring :as ssr-ring])

(def handler
  (ssr-ring/stream-handler
    {:initial-events [[:rf/server-init]]
     :root-view      [(rf/view :article/page)]
     :payload        [:articles :comments]}))   ;; same fail-closed allowlist as ssr-handler
```

On the client, opt in with `ssr/streaming-install!` (same carried `:frame` as
`hydrate!`), and **hydrate from its `:on-ready` callback**:

```clojure
(ssr/streaming-install!
  {:frame    :app/main
   :on-ready (fn [_outcomes]
               (let [payload (ssr/hydrate! {:frame :app/main})
                     el      (js/document.getElementById "app")
                     tree    [rf/frame-provider {:frame :app/main}
                              [(rf/view :article/page)]]]
                 (if payload
                   (reset! react-root (rdc/hydrate-root el tree))
                   (do (reset! react-root (rdc/create-root el))
                       (rdc/render @react-root tree)))))})
```

The runtime materialises the inert fallback `<template>`s into visible mounts,
then swaps each mount's content for its resolved chunk — merging that chunk's
delta — as the chunks arrive. A streaming page therefore *requires* the client
runtime to paint fallbacks at all: a non-JS client sees the shell structure but
no skeletons until the final payload lands.

`:on-ready` fires once, when the last chunk has landed, every delta is consumed,
and every `<rf-suspense>` mount the runtime created has been **unwrapped**. That
unwrapping is why hydration waits: those mounts are transport, and no render tree
on any host can express them, so hydrating while they are still in the DOM is a
structural mismatch at every boundary — React discards the streamed page and
re-renders it, and you pay for streaming without getting any.

!!! warning "Don't guess at readiness"

    Don't poll for `__rf_payload`, don't hydrate on a timer, and above all don't
    fall through to `create-root` because the payload "hasn't arrived yet" — on a
    live stream that is true for most of the page's life, and `create-root`
    throws away the markup the server streamed. The protocol is: progressive
    pre-hydration paint, then **one** ordinary whole-root hydration, triggered by
    readiness.

## Failed boundaries render their declared fallback

A boundary whose server render threw ships its fallback markup and no delta, and
the final payload names it in a failed set. The client's `ssr/boundary` reads
that and re-renders the `:fallback` it declared — the exact markup the failed
chunk left in the DOM.

This is why your views need no defensive nil branch duplicating the skeleton: the
boundary that declared the fallback is the one that shows it. `comments` renders
comments; deciding whether to show `comments-skeleton` instead is the boundary's
job, stated once.

??? note "Correctness lock"

    Streamed deltas are a *speed* optimisation. The **final** chunk is the canonical
    full payload — if speculative deltas and the payload disagree, the payload wins.
    Streaming latency with a single authoritative `:rf/hydrate`.

!!! warning "Each boundary `:id` must be unique"

    The `:id` matches a streamed chunk to its placeholder — you pick it (never
    autogenerated); it must be stable across the render. Duplicate ids →
    `:rf.error/suspense-boundary-duplicate-id`, last-registered chunk wins, earlier
    boundary stuck on fallback (fail-soft, not a 500).

## Troubleshooting

| Symptom | Error / behaviour | Fix |
|---|---|---|
| Boundary on non-streaming emitter | `:rf.error/ssr-suspense-boundary-outside-stream` | Use `stream-handler`, not plain `ssr-handler` / `render-to-string` |
| Duplicate boundary `:id` | `:rf.error/suspense-boundary-duplicate-id` — earlier region stuck on fallback | Unique, stable ids per region |
| One region throws on the server | `:rf.ssr/suspense-boundary-failed` — that region keeps fallback; page continues | Fix the region's data path; rest of page still streams |
| Hydrate mid-stream / on a timer | Structural mismatch; React may discard streamed markup | Hydrate only from `streaming-install!`'s `:on-ready` |
| No JS client | Shell structure only; no skeletons until final payload | Expected — fallbacks need the client runtime to paint |

## See also

- [ssr_streaming example](../../examples/capabilities/ssr/ssr_streaming)
- [API: streaming](../api/re-frame.ssr.md) / [stream-handler](../api/re-frame.ssr.ring.md)
- Next.js mapping: [Coming from Next.js](coming-from-nextjs.md)
