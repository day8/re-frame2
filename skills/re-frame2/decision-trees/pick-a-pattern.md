# Decision tree — which Pattern-* fits this task?

> **Audience:** authors writing re-frame2 ClojureScript application code.
> **Use when:** two neighbouring patterns both look plausible and you need the rule that picks between them, or you've picked a pattern and need the two follow-on decisions (verify against the example, then slice-vs-machine).

The canonical shape → leaf inventory lives **once** in [`../SKILL.md`](../SKILL.md) §Decision shortcuts (the *Which pattern fits* table) — every shipped pattern, including ResourcesMutations, is reachable from there. This tree does **not** restate that inventory; it carries the value the router can't: the **disambiguation rules** for patterns that share vocabulary, plus the two follow-on decisions. Worked examples are indexed in [`../examples-map.md`](../examples-map.md).

Patterns compose: most real screens combine two or three of them. Pick the *primary* pattern — the one whose shape the feature is built around — from SKILL.md's table; the secondary patterns get loaded in their own pass after the primary one is in place. **Load at most two pattern leaves at a time**; if three or more seem necessary, the request spans features — author each pattern's leaf in its own pass.

## Step 1 — the disambiguation pairs

A few neighbouring patterns share enough vocabulary to get confused. These rules pick the right leaf when both look plausible.

### RemoteData vs ManagedHTTP

Both move data over HTTP. The difference is **who owns the lifecycle vocabulary**.

- **RemoteData** — the `app-db` slice is the protagonist. The feature wants the canonical 5-key slice (`:status` / `:data` / `:error` / `:loaded-at` / `:attempt`) and routes through the four-event lifecycle (`:load → :loaded | :load-failed`). The fx underneath could be `:http`, `:rf.http/managed`, IndexedDB, a wrapped JS library — any AsyncEffect-shaped pipe. Choose RemoteData when the *slice shape* and *state-enum semantics* are the thing.
- **ManagedHTTP** — the `:rf.http/managed` fx is the protagonist. The feature wants retry-with-backoff, the eight-category failure taxonomy, abort tokens, in-flight de-dup, the encode/decode pipeline. Choose ManagedHTTP when the *fx contract* (its inputs, its replies, its retry policy) is the thing.

If both, **load RemoteData first, ManagedHTTP second** — RemoteData owns the slice; ManagedHTTP plugs into it. The escape hatch into a state-machine-driven HTTP flow (semantic retries that aren't transport-retries) is documented inside the ManagedHTTP leaf.

### Resources vs RemoteData

Both model "the result of a fetch". The difference is **who owns the cache bookkeeping**.

- **RemoteData** — you hand-roll the slice (or machine region): the 5-key shape, the four-event lifecycle, any TTL/refetch/invalidation logic. Choose it for a **one-off fetch** with no sharing and no cross-read invalidation story, or when the optional `day8/re-frame2-resources` artefact is not on the classpath.
- **Resources** — the framework owns identity, **cache scope** (fail-closed tenant/user boundary), **staleness/TTL**, dedupe, **tag invalidation** (so a write refreshes related reads), GC, in-flight ownership, route-declared loading, and SSR preload. Choose it when the same fetch is **shared across views**, needs freshness/fresh-skip, must be **invalidated after a mutation**, wants auditable scoping, or needs a **write-completion workflow continuation** (navigate / toast / fold errors after a save — call-site `:reply-to`, the `onSuccess` shape). It is the TanStack-Query-shaped layer; it lowers onto the same managed HTTP underneath.

Rule of thumb: a single feature's private fetch → RemoteData; a server-state cache several features read and a write must invalidate → Resources.

### Resources vs ResourcesMutations

Both live in the `day8/re-frame2-resources` layer; the split is **read vs write** — do not fold mutations into the Resources leaf.

- **Resources** — the *read / cache lifecycle*. `reg-resource` declares a cached, scoped, revalidated server-state **read**: identity, cache scope, staleness/TTL, dedupe, tag invalidation, GC, route-declared loading, SSR preload. Views read it **passively** through a `[:rf/resource {...}]` subscription; the fetch is *caused* by a route `:resources` entry or a dispatched `[:rf.resource/ensure {...}]` — never from render. Choose it when the question is "how is this cached read shared, kept fresh, and scoped?" (→ [`../patterns/resources.md`](../patterns/resources.md)).
- **ResourcesMutations** — the *write + post-write workflow*. `reg-mutation` declares a server **write** dispatched via `[:rf.mutation/execute {...}]`: declarative cache consequences (`:invalidates` tags, `:populates` authoritative seed), optimistic apply/rollback (`:optimistic` / `:optimistic-tags`, the runtime records the inverse), per-submission `:instance` keying so concurrent writes never clobber, and a **call-site** `:reply-to` handler for the app workflow (navigate / toast / fold field errors — a causal event target, not a callback). Choose it when the question is "what happens *after* a write settles, and how do the caches it touched refresh?" (→ [`../patterns/resources-mutations.md`](../patterns/resources-mutations.md)).

Rule of thumb: reading and caching server-state → Resources; changing server-state and orchestrating the consequences → ResourcesMutations. A screen that lists then edits uses **both** — the list is a Resource, the save is a Mutation whose `:invalidates` refreshes that list. Keep the two axes apart: cache consequences are declarative on `reg-mutation`; app workflow lives in the `:reply-to` handler — never drive navigation/toast off `:populates`/`:invalidates`, and never watch `[:rf/mutation ...]` from a component lifecycle hook.

### Forms vs RemoteData

Both have a status enum. The Forms enum is `:idle | :submitting | :submitted | :error`; the RemoteData enum is `:idle | :loading | :fetching | :loaded | :error`. The names differ because the lifecycles differ.

- **Forms** — there is user input. Pre-submit shape is the form draft; post-submit shape is the server's confirmation. The slice tracks `:draft`, `:submitted`, `:touched`, `:errors`, `:submit-attempted?`. Choose Forms when the prompt mentions fields, validation, dirty state, or submit buttons.
- **RemoteData** — there is no user input crossing the boundary. The slice holds *what the server told us*, not *what the user is editing*. Choose RemoteData when the prompt is read-only / fetch-only from the user's side.

A form's `:submit` step is usually a Forms-driven dispatch *into* a RemoteData or ManagedHTTP request. The form owns the input lifecycle; the request owns the response lifecycle. Both slices exist; neither is the other.

### AsyncEffect vs RemoteData

- **AsyncEffect** is the *generic* six-step shape (register fx → return `:fx` → post work → reply → dispatch → commit). RemoteData *specialises* it for HTTP with the 5-key slice.
- Choose **AsyncEffect** for fire-and-forget side effects that do not commit a result to `app-db` — analytics emits, log shipping, browser-notification triggers, `postMessage` to an external system you don't await. There is no slice.
- Choose **RemoteData** when there *is* a reply that updates the slice. The lifecycle slice is the giveaway.

Anything that *would* have a slice but doesn't have one yet is a RemoteData leaf to author. Anything that genuinely has no observable reply is AsyncEffect.

### WebSocket vs AsyncEffect

- **AsyncEffect** is one-shot: post, reply, done. There is no second message over the same channel.
- **WebSocket** is long-lived: a connection actor that survives across many messages, with phases (`:disconnected → :connecting → :authenticating → :connected → :reconnecting → :failed`), heartbeats, server pushes without correlation, queued sends when disconnected. The machine *owns the socket*; messages over the socket may themselves be AsyncEffect-shaped (request/reply with a correlation id), but the connection is a state machine.

If the prompt mentions reconnect, heartbeat, subscribe-to-topic, or server push — WebSocket. If a single send-and-receive — AsyncEffect.

### Boot vs RemoteData (multiple)

A boot sequence is many requests, but it is not "multiple RemoteData slices". Boot has:

- **Sequential dependencies** — config before profile before route resolve.
- **Phase-distinct failure semantics** — a failed step usually halts boot; the user sees an error page, not a partial app.
- **Visible progress** — the user wants to see "Loading profile…" then "Connecting…".
- **One-shot per app load** — re-booting is unusual; hot-reload must not re-trigger boot.

If the boot graph is ≤3 steps with no error states and no progress UI, chain events directly (the Boot leaf's "simple form"). Once any of those conditions break, lift the boot into a state machine — the Boot leaf names the canonical state set (`:configuring → :authenticating → :loading-profile → :hydrating → :routing → :ready`, with per-phase terminal error states `:auth-failed` / `:profile-failed` / `:fatal-error`).

### NineStates vs RemoteData/Forms

NineStates is **not** a substitute for either. It *layers* over them. Choose NineStates when the prompt mentions:

- Designing every render branch a page might show — including empty, one-result, too-many, validation-incorrect, transient-correct, and terminal/done.
- Wanting tooling-enumerable render states (for stories, for visual regression, for design review).
- A page that has *both* a data lifecycle (RemoteData) *and* a form lifecycle (Forms) *and* a mode lifecycle (running vs done) — three orthogonal axes.

If the prompt names only one axis (say, just a data fetch), do not reach for NineStates. NineStates pays off when ≥2 axes need to be modelled and rendered independently.

### LongRunningWork vs AsyncEffect

- **AsyncEffect** — the runtime already yields for you. HTTP, IndexedDB, `postMessage` — none of these hold the main thread.
- **LongRunningWork** — the work is CPU-bound on the main thread (parsing a 50MB file, hashing N items, running a large reduce, simulating physics). The dispatch loop blocks; the browser stops painting.

The LongRunningWork leaf's own decision tree picks between (a) a Web Worker (preferred when the work serialises across the worker boundary, hosted by AsyncEffect or WebSocket) and (b) the main-thread chunked-state-machine pattern (when DOM access or awkward serialisation forces main-thread execution).

### StaleDetection — not a primary choice, an overlay

StaleDetection is rarely the *primary* pattern for a task. It is the **epoch idiom** that overlays RemoteData, WebSocket, or any state-machine that initiates async work which might be superseded before its reply arrives. Choose StaleDetection as a primary pattern only when the explicit task is *"add stale detection to an existing feature"*. Otherwise the relevant pattern leaf (RemoteData, WebSocket, the machine) names where the epoch attaches; StaleDetection is the reference for *how*.

## Step 2 — verify against the example app

Every pattern has a worked example app (the `examples/` tree is test-free; patterns are regression-covered by CLJS contract/unit tests + adapter smokes, not per-example Playwright). After picking the pattern leaf, point at the example named in [`../examples-map.md`](../examples-map.md) and confirm: the slice shape matches; the event names scale to the example's `:feature/<verb>` naming; the `reg-app-schema` attachment points cover the same boundaries.

If the example contradicts the leaf, **the example wins** (implementation is ground truth). Report the divergence upstream as a `day8/re-frame2` GitHub issue against the spec; don't silently work around.

## Step 3 — the state-shape question (separate decision)

After picking the pattern, a second question applies independently: *should the state behind this pattern live as a slice in `app-db`, as a region inside an existing machine, or as a top-level `reg-machine`?* That question is its own decision tree — see [`slice-or-machine.md`](slice-or-machine.md). Pattern choice and state-shape choice are orthogonal: WebSocket is always a machine; AsyncEffect is usually a slice; RemoteData is a slice unless its retry policy needs a machine.

## Cross-references

- [`../SKILL.md`](../SKILL.md) §Decision shortcuts — the *Which pattern fits* table: the canonical shape → leaf inventory this tree disambiguates (owned there, never restated here).
- [`../examples-map.md`](../examples-map.md) — one-paragraph index of every worked example.
- [`slice-or-machine.md`](slice-or-machine.md) — when to lift state into a machine.

---

*Derived from the canonical patterns in the spec (`SKILL-REDIRECT.md` → Pattern entries) and the worked examples under `examples/`.*
