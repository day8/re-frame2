# Server-rendered HTML the browser wakes up

Open the page and the list of articles is already there — fully formed,
before any of your JavaScript runs. The page arrives as finished HTML,
not an empty shell that fills in later. Then the browser takes over:
rather than blanking the screen and rebuilding, it adopts that HTML and
wakes it into a live app, so the **Hide bodies** button works like in
any single-page app.

That's server-side rendering (SSR), and the trick is that it's all
one app, run twice: the server renders the "recent articles" page to
an HTML string and ships it; the browser picks that string up and runs
it.

> The same code runs on both sides — only the output differs: a
> string on the server, a live DOM in the browser.

The same [event
handlers](../../../../docs/core/glossary.md#event-handler),
[subscriptions](../../../../docs/core/glossary.md#subscription), and
[views](../../../../docs/core/glossary.md#view) run on the JVM and in the
browser.

This is the smallest end-to-end example of every contract surface in
[Spec 011](../../../../spec/011-SSR.md) — the worked companion to
[Construction Prompt
CP-9](../../../../spec/Construction-Prompts.md). For the narrative version
of everything below, read [Server-side
rendering](../../../../docs/ssr/concepts.md).

## One app, two runtimes — why `.cljc`

There is no separate "server code" to keep in sync with the client. The
application — [event
handlers](../../../../docs/core/glossary.md#event-handler),
[subscriptions](../../../../docs/core/glossary.md#subscription),
[views](../../../../docs/core/glossary.md#view), the server's
`handle-request`, the browser's `run` entry point — lives in one
`core.cljc`, and reader conditionals pick the side. A `:clj`
`handle-request` returns the HTML-plus-payload on the JVM. A `:cljs`
`run` boots the browser from the baked payload. The views and event
handlers in between carry no reader conditionals at all — they're pure,
so "does this run on the server?" never comes up for them. Where it
does come up — a `localStorage` write the JVM has never heard of —
you declare it once with `:platforms`, rather than branch at the call
site.

One small file sits beside it: `mount.cljs`, holding the client's
adopt-vs-fresh React-root decision and nothing else. It isn't a second
copy of the app — the file list below says why that single branch earns
its own namespace.

## What this demonstrates

Read top to bottom, the example narrates a single request and the
client picking it up:

- A [frame](../../../../docs/core/glossary.md#frame) per request, on
  the server. `handle-request` mints a fresh frame for each call,
  drains it, renders it, and tears it down. A hundred concurrent
  requests are 100 isolated
  [app-dbs](../../../../docs/core/glossary.md#app-db) that can't see or
  race one another. The same frame isolation that's good for testing N
  apps in one process gives you request isolation for free.
- `:rf/server-init`, gated to the server. It carries `:platforms
  #{:server}` and declares `:rf.cofx/requires [:rf.server/request]`, so
  the request map arrives as a
  [coeffect](../../../../docs/core/glossary.md#coeffect) rather than a
  global the handler reaches for. Its job here is small: start the
  article fetch.
- Platform-gated [effects](../../../../docs/core/glossary.md#effect) via
  `:platforms`. The session-store fx is `#{:client}`, so a server
  drain skips it. A handler that returns it never learns which
  runtime it's on. No `typeof window` anywhere.
- The article fetch goes through managed HTTP, stubbed for the
  render. `:rf/server-init` returns an `:rf.http/managed` effect. The
  JVM smoke redirects it to a canned-success stub through the
  `:fx-overrides` seam, so the render exercises the full
  [pipeline](../../../../docs/core/glossary.md#event-pipeline) without real
  network traffic.
- Pure [hiccup](../../../../docs/core/glossary.md#hiccup) → HTML.
  `rf/render-to-string` is a pure function from hiccup to an HTML
  string — no React server-render dependency, no DOM, JVM-runnable.
- The hydration payload is the server→client contract. The server
  serialises the settled state into the `:rf/hydration-payload` shape
  and bakes it into a `<script id='__rf_payload'>` tag the client reads
  back.
- `:rf/hydrate` replaces, it doesn't merge. On the client,
  `:rf/hydrate` (a framework-owned event the app must not re-register)
  installs the payload in one atomic step under the locked
  `:replace-frame-state` policy. It replaces both
  [partitions](../../../../docs/core/glossary.md#the-two-partitions) at
  once: the `:rf/app-db` slice replaces app-db, and the `:rf/runtime-db`
  slice replaces the serialisable runtime-db projection (machine
  snapshots, route). The server is authoritative for the initial client
  state, so "replace" is the honest semantics — a defaulting merge
  would bury "which side won?" bugs at every key.
- `data-rf-render-hash` turns the classic SSR bug into a located
  one. With `:emit-hash?`, `render-to-string` stamps a structural
  hash of the render-tree on the root element. After first render the client
  recomputes it. On disagreement the runtime emits
  `:rf.ssr/hydration-mismatch` — telling you not just that the renders
  diverged but where.
- Hydration leaves the page reactive. The "Hide bodies" button
  dispatches `:articles/toggle-bodies` against a slice that has no
  server correspondent. That proves the client is fully live after the
  server's HTML is adopted, not a static snapshot.

## Why this shape

[Spec 011](../../../../spec/011-SSR.md) treats SSR as part of the target
architecture, not a future concession. The example leans into that by
exercising the load-bearing subtleties, not just the happy path:

- Two frame families, one schema. SSR has a per-request server
  frame (in `handle-request`) and a fixed client hydration frame
  (`:rf/default`, in `run`). Because
  [`reg-app-schema`](../../../../docs/core/glossary.md#schema) is
  frame-local, the `[:articles]` schema is held as a plain value and
  registered explicitly against each frame at its entry point — so
  the server commit and the client commit validate against the same
  contract. (It's `[:maybe …]` because the slice is legitimately absent
  until the fetch lands.) That validation is a development-time
  assertion on both sides: a production build performs the registration
  and elides the check ([Spec 010 §Production
  builds](../../../../spec/010-Schemas.md#production-builds)), so
  neither commit is verified in a release build. The symmetry is still
  the point — one declared contract, 2 frames — but a server that has
  to reject a malformed request does that in its handler, not through
  the schema ([Pattern-FormAction §Validation is the handler's
  job](../../../../spec/Pattern-FormAction.md#validation-is-the-handlers-job)).
- Per-request teardown is load-bearing. `handle-request` ends with
  `destroy-frame!` in a `finally`, on both the success and the throw
  path. A long-running server then can't leak a frame (and its request
  slot) per request, and a render or fetch error never strands a
  half-built one.
- The payload deliberately omits `:rf/frame-id`. The server renders
  under a per-request frame the client can't know ahead of time, and
  the client hydrates its own fixed frame. `ssr/hydrate!` treats a
  present payload frame-id as validation evidence — a mismatch fails
  closed with `:rf.error/hydration-frame-id-mismatch` — never as a way
  to pick the target frame. So an absent id is exactly right: the
  explicit client `:frame` stands, and the dynamic server output agrees
  with the static `index.html` next to this file (neither carries a
  frame-id). That `index.html` is an illustrative stand-in, not a
  byte-exact capture — it omits the `:rf/render-hash` and carries a
  sample routing slice this article-only render never populates.

In runnable form: the hand-written `index.html` ships with pre-rendered
HTML inside `<div id='app'>` and a pre-baked `<script
id='__rf_payload'>` — standing in for what `handle-request` would serve
if a real Clojure server sat in front. The browser-side `run` calls
`ssr/hydrate!` (read the payload → dispatch `:rf/hydrate` → verify the
render-hash) and renders against the now-seeded state through the
carried frame's `frame-provider`, handing that render-tree to
`mount.cljs`'s `mount!` — state first, DOM second.

## Files

```
ssr/
  core.cljc                    — the shared app: events, subs, views, the server's
                                 handle-request, the client's run entry point.
  mount.cljs                   — the client's adopt-vs-fresh React-root decision.
  index.html                   — pre-rendered shell + payload (mocks a real server emission).
```

`mount.cljs` is small on purpose. It holds one decision — payload
present ⇒ the first render through the adapter's client root hydrates,
reconciling against the server's markup and adopting it; payload absent ⇒
a fresh mount — as one `{:hydrate? …}` option on `reagent-adapter/render!`,
and `run` calls it instead of branching inline. The browser DOM-adoption
regression (`re-frame.ssr.ssr-startup-recipe-dom-cljs-test`, over in
`implementation/ssr/test/`) calls that same helper, so the proof
drives the branch this example ships rather than a hand-kept copy of it
that could quietly drift.

Which is why it's a namespace of its own rather than a `defn-` inside
`core.cljc`: the test has to reach the helper without `:require`-ing
`ssr.core`, whose registrations (`:auth.session/store`,
`:articles/loaded`) collide at image assembly with the other examples
sharing that test bundle. `mount.cljs` registers nothing, so loading it
pulls no example ids in. Everything else stays in `run` — including the
`:ssr/client-bootstrap` dispatch on a plain client load, which is app
logic, not mount mechanics.

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/ssr
```

Then open the URL it prints. The runner stages this folder's hand-written
[`index.html`](index.html) — the one carrying the pre-rendered shell and the
baked payload the client hydrates against — beside the compiled bundle, so
there is nothing to combine by hand.

## See also

- [`examples/capabilities/ssr/ssr_streaming/`](../ssr_streaming/) — the streaming-SSR counterpart.
- [`examples/real-apps/realworld_http/`](../../../real-apps/realworld_http/) — SSR boot folded into a broader app.
