# Server-side rendering

> **Draft ahead of the product artefact — and this page carries more in-flight
> scope than any other, so it is written in strict honest tense.** SSR +
> hydration became **required** Hicasso scope by operator ruling on 2026-08-04;
> the HD-020 addendum in [decisions.md](../decisions.md) is the record, and the
> same-date addendum in
> [EP-0038](../../../EP/EP-0038-the-hicasso-view-layer-programme.md) carries the
> requirement set. The framework's own SSR machinery **ships today**. Of
> Hicasso's doors, the hydration door and `defhost`'s `:ssr` policy are
> **landed in the bench arm and witnessed**; the Node render entry is **in
> review**; the end-to-end witness has **not run**; the production server arm
> is **not ruled**. Every section says which of those it is describing,
> spellings marked **[unfrozen]** stay provisional until the API freeze, and an
> app that needs SSR before the doors land uses a first-class adapter — this
> page ends on exactly that.

SSR is not a feature re-frame2 bolted on; it is one of the reasons the
architecture looks the way it does. Spec 011 opens by calling it a core goal,
and the decisions that make it cheap were taken long ago: views are pure
functions of state, events are data, and a frame is cheap enough to create for
one request and destroy when the response is written. Hicasso claims to be
re-frame2's **native** view layer, and on 2026-08-04 the operator drew the
consequence — *"SSR is an important part of re-frame2. If hicasso is to be the
re-frame native view layer then it has to be used with SSR."* HD-020 had ruled
SSR out of v0; that clause carried its own reopening condition, and this is the
ruling that reopened it.

The design that came out of that ruling fits in a sentence:

> **One renderer, run in two places, judged in one.**

The runtime that renders in the browser is the runtime that renders on the
server; the framework's existing hydration machinery carries the result across;
and whether the whole story holds is decided by one witness set at one sitting,
not by this guide's optimism.

## The framework's story, which exists today

One app, written once, run twice. The runnable reference is the adapter-based
example at `examples/capabilities/ssr` — a single `.cljc` file whose events,
subscriptions and views are shared between the JVM and the browser, tested
headlessly on every PR — and the published [SSR guide](../../../ssr/index.md)
teaches it at corpus depth. The shape, in brief:

**On the server, per request:**

1. A fresh frame is created for the request — a gensym id, nothing shared —
   and the incoming request rides in on a coeffect.
2. `:initial-events` boots it: ordinary handlers run, effects marked
   `:platforms #{:client}` are skipped, and app-db settles.
3. The settled snapshot is rendered to an HTML string.
4. The same state is serialised into the page — a
   `<script id="__rf_payload" type="application/edn">` carrying the app-db
   partition, the serialisable runtime slice, a protocol version and a
   structural render hash, with `</script>`-safe escaping done by the
   framework.
5. The frame is destroyed in a `finally`. No leak per request, throw path
   included.

**On the client, once:**

1. `hydrate!` — the boot helper in `re-frame.ssr` — reads the payload by its
   pinned id. A missing payload means nobody server-rendered this page, a
   plain first load; a malformed one fails closed.
2. It dispatches the reserved `:rf/hydrate` event **before the first render**.
   The framework owns the handler, and the server's state **replaces** the
   client's rather than merging into it — on this question the server is the
   single source of truth.
3. The first render is verified: the client hashes its own first render-tree
   and compares it with the server's hash; disagreement raises
   `:rf.ssr/hydration-mismatch` instead of quietly serving a subtly different
   page.
4. The DOM is adopted with React's `hydrateRoot` — the server's nodes are kept
   and listeners attached, never thrown away and rebuilt.

None of that is Hicasso's to reinvent. Requirement R0 of the ruling says so
outright: Hicasso participates in the **existing** story — the payload, the
`:rf/hydrate` door, the mismatch machinery, `ssr-ring` as the HTTP host —
never a parallel Hicasso-only mechanism. Everything below is about the two
places where a view layer has to hold up its own end: rendering on the server,
and adopting in the browser.

## What Hicasso adds, door by door

| Door | What it is | Status, 2026-08-04 |
|---|---|---|
| The hydration door | `hydrate-root!` **[unfrozen]** beside `root!` — adopt server DOM into a live root | **Landed**, witnessed in the bench arm (`rf2-2rtt6.84`) |
| The `:ssr` host policy | `defhost` declares what a foreign region does server-side | **Landed**, witnessed; dated HD-011 addendum (`rf2-2rtt6.85`) |
| The Node render entry | `renderToString` of the existing runtime, fixture bake, live demo | **In review** — the PR is open, not on main (`rf2-2rtt6.86`) |
| The spike witness | X1–X5 on the hydrated dogfood screen | **Not run** (`rf2-2rtt6.87`) |
| The production server arm | JVM structural walk vs Node sidecar | **Not ruled** — priced and ruled at the P2 sitting (`rf2-2rtt6.88`) |

"Landed" means what it means everywhere in this guide: witnessed by the bench
arm's tests under
`implementation/freehand/test/re_frame/bench/hicasso/`, with no
`implementation/hicasso/` artefact anywhere. "In review" means the code exists
on a branch, and this page does not call a branch the tree.

### The server engine is the client runtime

The ruled server engine is not a second renderer. It is the existing runtime
run under `react-dom/server`'s `renderToString`, so hydration parity holds by
construction — there is no separate emitter whose output could drift from what
the client would have rendered. The property that makes this safe is already
on main and witnessed: every boundary shell passes a server snapshot to its
`useSyncExternalStore`, so under a server render React never subscribes —
every `sub` read is the mutation-free cold probe, no commit runs, no effect
fires, and the render leaves **zero durable registration** behind. A server
render is an abandoned render, and HD-002's ledger discipline already required
the runtime to survive those (requirement R6).

The *entry* that runs it per request — a gensym frame, app-db seeded through
the framework doors, `renderToString`, the payload built with the framework's
own bytes, `destroy-frame!` in a `finally`, plus baked fixtures and a live
page-level demo driver — is `rf2-2rtt6.86`, in review and not on main. Two of
its properties are requirements rather than aspirations, worth knowing in
advance: the render is deterministic (same bundle + same snapshot means
byte-identical HTML, checked by double render), and the demo driver is
**explicitly not a production host** — Spec 011's HTTP response contract stays
`ssr-ring`'s.

SSR *speed*, meanwhile, is off the bar on the record — fast applications, not
fast SSR (HD-012 stands unchanged).

### The hydration door — landed

`hydrate-root!` **[unfrozen]** stands beside `root!`
([Getting started](01-getting-started.md)): the same association of a DOM
node, a frame and one view, except the node already holds the server's markup
and the frame has already adopted the server's state. It returns the same
handle shape `root!` does, so teardown treats a hydrated root like any other.

Three of its properties are the reason it took a ruling rather than an
afternoon, and all three are witnessed (`arm1/hydrate_dom_cljs_test`):

- **It calls `hydrateRoot` plain — no `flushSync` — so it returns before
  adoption completes.** React adopts concurrently; the DOM on the line after
  the call is still the server's. Completion is observable rather than forced:
  a closer component's passive effect ends the adoption window, the same
  pattern the core substrate uses for its own hydration. (The reap horizon
  that guards provisional subscription entries is margin, not contract — it
  leaves adopted subscriptions alone, and no caller may rely on it.)
- **Presence children are born present.** A node managed by presence hydrates
  in its `:present` state: nothing that arrived with the page replays an
  entrance over content the user is already reading, and server HTML does not
  arrive carrying mounting-phase `opacity: 0` overrides.
- **Hydration never converges controlled text.** HD-019's row extends to the
  hydrated path: a controlled input arrives with React's `defaultValue` mirror
  intact and the model's value as the one truth. There is no flash of server
  text corrected a beat later.

One more, for the witnesses rather than the reader: the boundary memo never
fakes adoption — body runs are counted, not inferred (HD-028) — which is what
lets the spike below say "exactly one body pass" and mean it.

### The `:ssr` host policy — landed

A foreign React component is exactly the node whose render may reach for
`window`, and a declaration that names only the component tells the door
nothing about that. So the declaration says it
([Interop](05-interop.md) owns the door):

```clojure
(h/defhost chart Chart)                                 ;; :client-only — the default
(h/defhost chart Chart {:ssr :client-only})             ;; the same, said out loud
(h/defhost chart Chart {:ssr {:fallback [:div.skel]}})  ;; that markup until adoption
```

`:client-only` renders nothing where the host sits until the client has
adopted the page; `{:fallback <hiccup>}` renders that markup there instead.
There is no third value: a policy the gate does not recognise is refused at
the declaration (`:rf.error/hicasso-host-bad-ssr-policy`), and an option
`defhost` does not know is now a refusal too
(`:rf.error/hicasso-host-unknown-option`) rather than the silent ignore the
2026-08-04 audit found. One mechanism serves all three places the policy has
to hold — the server render, hydration's first client pass, and a fresh
client-only mount, which renders the foreign component immediately with no
placeholder flash. Witnessed in `arm1/host_ssr_dom_cljs_test`, with the honest
cost on the record: the door used to mint no wrapper at all, and the gate is
one fiber and one hook per declaration.

### The witness and the sitting — ahead

Whether all of this works as **one page** is not this guide's to assert. The
X1–X5 spike witness (`rf2-2rtt6.87`, not run; it depends on the render entry)
drives the programme's dogfood screen through the whole arc and reports, with
SHA and repro command per row: byte-identical double render and canonical-DOM
parity with a client-only mount; adoption with zero mismatch, server node
identity preserved and exactly one body pass; reactivity adopted on React's
own schedule; a 17-step real-DOM intent script driven at the hydrated screen,
controlled-text echo included; and clean teardown. It is the P2 sitting's SSR
feasibility input, and it publishes **no verdict** — the verdict is the
operator's.

The same sitting prices and rules the **production server arm**
(`rf2-2rtt6.88`). The default direction to be priced is a JVM structural walk
— the codec's pure analysis rules twinned on the JVM, folded to HTML by the
tree emitter the SSR artefact already ships, in-process with `ssr-ring` —
which would also discharge most of [Testing](08-testing.md)'s headless door,
since a structural render core is the larger half of one. Priced beside it: a
Node sidecar behind an EDN render contract, with `ssr-ring` keeping HTTP
either way. Neither is built. Do not design a deployment against either yet —
this is the one part of the story where the record genuinely does not know the
answer.

## Write the app so SSR is free

Everything above is the runtime's problem. What makes SSR cheap or expensive
in an *application* is authored long before a server enters the picture — and
every rule below is usable now, because each one is a property the landed
surface already has.

**Bodies are portable by construction.** A tier-1 `defview` body is hiccup,
reads and ordinary Clojure — no JS interop in the forms, the same property
that gives the headless door its scope. JS lives at declared edges: `defhost`
declarations and host-edge namespaces ([Interop](05-interop.md)). Keep it
there and your view namespaces load anywhere, which is the precondition for
every server story on the table.

**Events are data, so nothing needs serialising.** `[:todo/toggle id]` at an
`:on-click` has no closure to ship: the runtime builds the callback from the
vector on whichever side is rendering, and a handler on the hydrated page is
exactly the handler a fresh mount would have built. The same fact pays on the
wire — app state crosses as EDN because it *is* data. There is no "serialise
my functions" problem anywhere in this story.

**A body is a function of its props and reads — keep it that way.** Same
snapshot in, same tree out is what "render from a db snapshot" means, and it
is also what adoption demands. A clock read, a `js/window` sniff or a random
in a body renders one thing on the server and another in the browser, and
React will report the disagreement as a mismatch. Platform differences belong
in effects (`:platforms #{:client}` exists for exactly this) and at host
edges, never in tier-1 bodies — which
[Views and reads](02-views-and-reads.md) already requires: bodies are pure and
re-runnable.

**Controlled inputs are already SSR-shaped.** The value comes from the model
([Controlled inputs](04-controlled-inputs.md)), the server renders the model's
value, and hydration never converges the text. There is nothing to write and
nothing to avoid; the one thing you would have had to worry about is the thing
the door forecloses.

**Entrances should not depend on being mounted.** Drive *enter* with an
animation on insertion or `@starting-style` —
[Ephemeral state](07-ephemeral-state.md)'s standing rule, and the SSR-friendly
one: the entrance is a CSS fact of the markup, it runs at first paint, and
adoption reuses the server's nodes rather than re-creating them, so it gets no
second trigger. Presence handles the other half — a presence-managed node
hydrates born-present, so nothing that arrived with the page replays an
entrance.

**Decide each foreign region's server story at its declaration.**
`:client-only` when the region is genuinely browser-bound; `{:fallback …}`
when a skeleton keeps the page's shape. One line, at the crossing, and it is
the difference between "the chart area is blank until adoption" being a choice
and being a surprise.

Do all of that — which is to say, write idiomatic Hicasso — and SSR is not a
rewrite waiting to happen. That is the design's actual bet: the app that is
easiest to write is also the app that serves.

## Non-goals

Stated once, per the 2026-08-04 addendum in
[EP-0038](../../../EP/EP-0038-the-hicasso-view-layer-programme.md), so nobody
reads silence as intent: **streaming** (`renderToPipeableStream` is out of
scope), **React Server Components**, **islands / partial hydration**, **no-JS
progressive enhancement** and **SEO metadata management** are not part of this
story. Neither is SSR speed as a bar — HD-012's line stands. The story is one
page, rendered whole from a snapshot, adopted whole by the client.

## Troubleshooting

The first two rows are the framework machinery's, live today under the
adapters and inherited unchanged (requirement R0); the rest belong to the
landed doors.

| Symptom | What went wrong | Fix |
|---|---|---|
| React reports a hydration mismatch during adoption | The client's first render disagreed with the server HTML — a body read the platform (a clock, `js/window`, a random) instead of being a function of props and reads | Keep bodies pure; platform work goes to effects (`:platforms #{:client}`) and host edges |
| `:rf.ssr/hydration-mismatch` after the first render | The structural-hash verify: the client's first tree is not the one the server hashed — hydration ran after the first render, a seed overwrote the payload, or the two sides run different builds | `hydrate!` before anything renders; its whole job is holding read → hydrate → verify in that order |
| A foreign component throws `window is not defined` in a server render | Its render reached for the browser, and a declaration that names only the component cannot know that | Declare the region's policy: `:client-only` (the default), or `{:fallback …}` under `:ssr` |
| A controlled input's text jumps just after adoption | Not this design: hydration never converges controlled text, witnessed on the hydrated path | Seen under another stack, it means the server markup and the model disagreed — fix the state they share |
| Frames accumulate on a long-running server | A request path that skips teardown | The per-request frame dies in a `finally`, throw path included — the reference example is the copyable shape |

## When you need SSR today

Use a first-class adapter. The Reagent and UIx
[adapters](../../../core/views.md) are supported, actively maintained, and
carry the whole Spec 011 story end to end — the reference example at
`examples/capabilities/ssr` is exactly that, runnable now. This is not a
consolation: adapters remaining the answer is a *successful* outcome for the
programme ([Getting started](01-getting-started.md) says the same about the
whole of Hicasso). What this chapter adds for an adapter user is the
authored-code discipline above, most of which — events as data, pure bodies,
platform work in effects — is portable re-frame2 rather than Hicasso, and
costs nothing to adopt early.

## Not settled yet

| Question | Status |
|---|---|
| The Node render entry, the fixture bake, the live demo driver | **In review** (`rf2-2rtt6.86`) — ruled and written, not on main. Until it merges, nothing in the tree renders a Hicasso page per request |
| The X1–X5 spike witness | **Not run** (`rf2-2rtt6.87`); depends on the render entry. Its rows are the P2 sitting's SSR feasibility input, and it publishes no verdict |
| The production server arm | **Not ruled** (`rf2-2rtt6.88`, decided at the P2 sitting): a JVM structural walk (the default direction, to be priced; it would co-discharge the headless testing door) vs a Node sidecar behind an EDN render contract. `ssr-ring` keeps Spec 011's HTTP response contract either way |
| How the boot composes at the product surface | **Not addressed.** The bench door associates a container, a frame and a view; the framework's `hydrate!` seeds and verifies state before first render. Whether the product artefact offers one operation or two — and where `:initial-events` sits on a hydrating load — is unstated |
| The spellings | `hydrate-root!` is a bench-arm name, as `root!` was before it; every spelling on this page is **[unfrozen]** until the API freeze |
