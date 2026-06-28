# Decision tree — which Pattern-* fits this task?

> **Audience:** authors writing re-frame2 ClojureScript application code.
> **Use when:** the task description hints at a recurring shape — fetching, submitting, booting, processing, streaming, rendering lifecycle states — and you need to pick the canonical leaf to load.

Patterns compose: most real screens combine two or three of them. This tree picks the *primary* pattern — the one whose shape the feature is built around. Secondary patterns get loaded in their own pass after the primary one is in place.

Each leaf below names the file under [`patterns/`](../patterns/) (the pattern leaf) and the worked example under `examples/` (when one exists).

## Step 1 — name the shape

Read the prompt for **one** of the following shape-tells. They are mutually exclusive at the level of *primary* role:

| Shape-tell in the prompt | Primary pattern |
|---|---|
| "Fetch / GET / load / refresh / reload" — a request whose response writes to `app-db` | RemoteData |
| "Query cache / server-state / TanStack-Query / React-Query / SWR / shared cached read / invalidate after a write / refetch on focus / `reg-resource`" | Resources |
| "Submit / save / form / validation / required / draft / dirty" — user-edited input crossing a server boundary | Forms |
| "Boot / init / hydrate / startup / first-load / splash" — the initial-load sequence before the app is interactive | Boot |
| "WebSocket / SSE / EventSource / live / push / subscribe / channel / heartbeat / reconnect" — long-lived connection | WebSocket |
| "Retry / backoff / circuit-breaker / 4xx vs 5xx / abort / cancel an in-flight request" — a request with status-aware policy | ManagedHTTP |
| "Empty state / no-results / one-result / too-many / not-found / forbidden / nine UI states" — render every legal lifecycle distinctly | NineStates |
| "Fire-and-forget / log / analytics / telemetry / external side-effect with no observable reply" | AsyncEffect |
| "CPU-bound / heavy / parses / hashes / pegs the main thread / chunked / yields / progress bar" | LongRunningWork |
| "Async-result arrives after state moved on / ignore stale results / don't apply old data after navigating away" | StaleDetection |
| "Reusable / parameterised widget / customer-card / renders N of the same / works against any X / compare two side by side" | ReusableComponents |
| "Wrap a JS library / D3 / Mapbox / CodeMirror / Three.js / ag-grid / chart / map / editor / `:ref` + lifecycle / `.setData`" | StatefulComponents |
| "SSR form POST / progressive enhancement / works without JavaScript / server `action` / POST-redirect-GET / CSRF on submit" | FormAction |
| "SSR parallel fetch / `Promise.all` server-side / fan-out N requests before render / per-page loader" | SSR-Loaders |

If you see **two** shape-tells, the dominant one is the one the user names first or the one that owns the data. Pattern composition (e.g. "managed-HTTP retries inside a form submit") is normal — pick the primary, plan the secondary as a follow-up.

## Step 2 — the disambiguation pairs

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

If the boot graph is ≤3 steps with no error states and no progress UI, chain events directly (the Boot leaf's "simple form"). Once any of those conditions break, lift the boot into a state machine — the Boot leaf names the canonical state set (`:reading-config → :authenticating → :loading-profile → :hydrating → :resolving-route → :ready | :failed`).

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

## Step 3 — verify against the example app

Every pattern has a worked example app (the `examples/` tree is test-free; patterns are regression-covered by CLJS contract/unit tests + adapter smokes, not per-example Playwright). After picking the pattern leaf, point at the example named in [`examples-map.md`](../examples-map.md) and confirm: the slice shape matches; the event names scale to the example's `:feature/<verb>` naming; the `reg-app-schema` attachment points cover the same boundaries.

If the example contradicts the leaf, **the example wins** (implementation is ground truth). Report the divergence upstream as a `day8/re-frame2` GitHub issue against the spec; don't silently work around.

## Step 4 — load the leaves

| Primary pattern | Leaf to load | Worked example |
|---|---|---|
| RemoteData | [`patterns/remote-data.md`](../patterns/remote-data.md) | (inline mini-example) |
| Resources | [`patterns/resources.md`](../patterns/resources.md) | `examples/capabilities/resources/resources/` (read-side) · `examples/real-apps/realworld_resources/` (the EP-0016 mutation dogfood) |
| Forms | [`patterns/forms.md`](../patterns/forms.md) | `examples/core/login/` |
| Boot | [`patterns/boot.md`](../patterns/boot.md) | `examples/patterns/boot/` |
| WebSocket | [`patterns/websocket.md`](../patterns/websocket.md) | `examples/patterns/websocket/` |
| ManagedHTTP | [`patterns/managed-http.md`](../patterns/managed-http.md) | `examples/core/managed_http_counter/` |
| NineStates | [`patterns/nine-states.md`](../patterns/nine-states.md) | `examples/patterns/nine_states/` |
| AsyncEffect | [`patterns/async-effect.md`](../patterns/async-effect.md) | (inline mini-example) |
| LongRunningWork | [`patterns/long-running-work.md`](../patterns/long-running-work.md) | `examples/patterns/long_running_work/` |
| StaleDetection | [`patterns/stale-detection.md`](../patterns/stale-detection.md) | (inline mini-example) |
| ReusableComponents | [`patterns/reusable-components.md`](../patterns/reusable-components.md) | (inline mini-example) |
| StatefulComponents | [`patterns/stateful-components.md`](../patterns/stateful-components.md) | (per-adapter README) |
| FormAction | [`patterns/form-action.md`](../patterns/form-action.md) | (inline mini-example) |
| SSR-Loaders | [`patterns/ssr-loaders.md`](../patterns/ssr-loaders.md) | `examples/patterns/boot/` |

Load at most two pattern leaves at a time. If three or more seem necessary, the request probably spans features and should be broken up — author each pattern's leaf in its own pass.

## Step 5 — the state-shape question (separate decision)

After picking the pattern, a second question applies independently: *should the state behind this pattern live as a slice in `app-db`, as a region inside an existing machine, or as a top-level `reg-machine`?* That question is its own decision tree — see [`slice-or-machine.md`](./slice-or-machine.md). Pattern choice and state-shape choice are orthogonal: WebSocket is always a machine; AsyncEffect is usually a slice; RemoteData is a slice unless its retry policy needs a machine.

## Cross-references

- [`slice-or-machine.md`](./slice-or-machine.md) — when to lift state into a machine.
- [`../examples-map.md`](../examples-map.md) — one-paragraph index of every worked example.
- [`../SKILL.md`](../SKILL.md) §Decision: which pattern fits? — the same matrix, in the router's own voice.

---

*Derived from the canonical patterns in the spec (`SKILL-REDIRECT.md` → Pattern entries) and the worked examples under `examples/` @ main `89bd9c3`.*
