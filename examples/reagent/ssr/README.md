# ssr — Spec 011 SSR + hydration worked example

One app. It runs twice. The server renders a "recent articles" page
to an HTML string and ships it; the client picks that HTML up, adopts
it, and wakes it into the live app — *without* a second,
server-flavoured copy of the code. That last clause is the whole
trick, and the reason it works is that the same [event
handlers](../../../docs/guide/glossary.md#event-handler),
[subscriptions](../../../docs/guide/glossary.md#subscription), and
[views](../../../docs/guide/glossary.md#view) run on the JVM as in the
browser. The only difference is the output: a string instead of a DOM.

This is the smallest end-to-end demonstration of every contract
surface in [Spec 011](../../../spec/011-SSR.md) — the worked companion
to [Construction Prompt
CP-9](../../../spec/Construction-Prompts.md). For the narrative version
of everything below, read [Server-side
rendering](../../../docs/ssr/concepts.md).

## One artefact, two runtimes — why `.cljc`

The whole point is that there's no "server code" to keep in sync with
the client. So both live in one `core.cljc`, and the reader macros pick
the side: a `:clj` `handle-request` returns HTML-plus-payload on the
JVM, a `:cljs` `run` boots the browser from the baked payload. The
[views](../../../docs/guide/glossary.md#view) and
[event handlers](../../../docs/guide/glossary.md#event-handler) in
between carry no reader conditionals at all — they're pure by
construction, so the "does this run on the server?" question simply
never comes up for them. (Where it *does* come up — a `localStorage`
write the JVM has never heard of — the answer is declared once with
`:platforms`, not branched at the call site.)

## What this demonstrates

Read top to bottom, the example narrates a single request and its
client pickup:

- **A [frame](../../../docs/guide/glossary.md#frame) per request, on
  the server.** `handle-request` mints a fresh gensym frame for each
  call, drains it, renders it, and tears it down. A hundred concurrent
  requests are a hundred isolated
  [app-dbs](../../../docs/guide/glossary.md#app-db) that can't see or
  race one another — the same isolation that makes frames good for
  testing N apps in one process, now buying you request isolation for
  free.
- **`:rf/server-init`, gated to the server.** It carries `:platforms
  #{:server}` and declares `:rf.cofx/requires [:rf.server/request]`, so
  the request map arrives as a
  [coeffect](../../../docs/guide/glossary.md#coeffect) rather than a
  global the handler reaches for. Its job here is small: kick off the
  article fetch.
- **Server-only [effects](../../../docs/guide/glossary.md#effect) via
  `:platforms #{:server}` / `#{:client}`.** The session-store fx is
  `#{:client}`, so a server drain skips it and the handler that
  returned it never learns which runtime it's on. No `typeof window`
  anywhere.
- **The article fetch goes through managed HTTP, stubbed for the
  render.** `:rf/server-init` dispatches `:rf.http/managed`; the JVM
  smoke redirects it to a per-frame canned-success stub via the
  `:fx-overrides` seam, so the render exercises the full
  [cascade](../../../docs/guide/glossary.md#event-cascade) without real
  network traffic.
- **Pure [hiccup](../../../docs/guide/glossary.md#hiccup) → HTML.**
  `rf/render-to-string` is a pure function from the registered views to
  an HTML string — no React server-render dependency, no DOM, JVM-runnable.
- **The hydration payload is the server→client contract.** The server
  serialises the settled state into the `:rf/hydration-payload` shape
  and bakes it into a `<script id='__rf_payload'>` tag the client reads back.
- **`:rf/hydrate` *replaces*, it doesn't merge.** On the client,
  `:rf/hydrate` (a framework-owned event the app must not re-register)
  installs the payload in one atomic step under the locked
  `:replace-frame-state` policy — both
  [partitions](../../../docs/guide/glossary.md#the-two-partitions) at
  once: the `:rf/app-db` slice replaces app-db and the `:rf/runtime-db`
  slice replaces the serialisable runtime-db projection (machine
  snapshots, route). The server is authoritative for the initial client
  state, so "replace" is the honest semantics — a defaulting merge
  would bury "which side won?" bugs at every key.
- **`data-rf-render-hash` turns the classic SSR bug into a located
  one.** `render-to-string` stamps a structural hash of the
  render-tree on the root element; after first render the client
  recomputes it and, on disagreement, the runtime emits
  `:rf.ssr/hydration-mismatch` — telling you not just *that* the
  renders diverged but *where*.
- **Hydration actually leaves the page reactive.** The "Hide bodies"
  button dispatches `:articles/toggle-bodies` against a slice that has
  no server correspondent — proof that after the server's HTML is
  adopted, the client is fully live, not a static snapshot.

## Why this shape

[Spec 011](../../../spec/011-SSR.md) frames SSR as part of the target
architecture, not a future concession — and the example leans into
that by exercising the genuinely load-bearing subtleties rather than
the happy path alone:

- **Two frame families, one schema.** SSR has a per-request *server*
  frame (a gensym, in `handle-request`) and a fixed *client* hydration
  frame (`:rf/default`, in `run`). Because
  [`reg-app-schema`](../../../docs/guide/glossary.md#schema) is
  frame-local, the `[:articles]` schema is held as a plain value and
  registered explicitly against *each* frame at its entry point — the
  server commit and the client commit both validate against the same
  contract. (It's `[:maybe …]` because the slice is legitimately
  absent until the fetch lands.)
- **Per-request teardown is load-bearing.** `handle-request` ends with
  `destroy-frame!` in a `finally`, on both the success and the throw
  path — so a long-running server doesn't leak a frame (and its
  request slot) per request, and a render or fetch error never strands
  a half-built one.
- **The payload deliberately omits `:rf/frame-id`.** The server renders
  under a per-request gensym the client can't know ahead of time, and
  the client hydrates its *own* fixed frame. `ssr/hydrate!` treats a
  present payload frame-id as *validation evidence* (a mismatch fails
  closed with `:rf.error/hydration-frame-id-mismatch`), never as a
  target resolver — so an *absent* id is exactly right: the explicit
  client `:frame` stands, and the dynamic server output agrees with the
  static `index.html` next to this file on the frame-id question (neither
  carries one). The `index.html` is an illustrative stand-in, not a
  byte-exact capture — it omits the `:rf/render-hash` and carries a
  sample routing slice this article-only render never populates.

Runnable form: the hand-written `index.html` ships with pre-rendered
HTML inside `<div id='app'>` and a pre-baked `<script
id='__rf_payload'>` — exactly the shape `handle-request` would emit if
a real Clojure server sat in front. The browser-side `run` calls
`ssr/hydrate!` (read the payload → dispatch `:rf/hydrate` → verify the
render-hash) and renders against the now-seeded state through the
carried frame's `frame-provider-existing`.

## Files

```
ssr/
  core.cljc                    — server + client, one artefact.
  index.html                   — pre-rendered shell + payload (mocks a real server emission).
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/ssr
```

Then serve the build output over HTTP and open this folder's
hand-written [`index.html`](index.html) — it ships the pre-rendered
shell and baked payload the client hydrates against.

## See also

- [`examples/reagent/ssr_streaming/`](../ssr_streaming/) — the streaming-SSR counterpart.
- [`examples/reagent/realworld/`](../realworld/) — SSR boot folded into a broader app.
