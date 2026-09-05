# Reading what's on screen

The **what is on screen, and why did it render** half of the op vocabulary, split out of [`ops.md`](ops.md) so a read/dispatch/trace/time-travel session does not load it and a screen-read session does not load the whole catalogue. Same transport, same conventions.

> **Raw `eval-cljs` is un-elided — the carve-out, stated here so you need no second leaf.** The `dom/*` rows in §DOM source bridge below are raw `eval-cljs` forms. `eval-cljs` is default-ON (gated only by `--no-eval`) and is **not** governed by the `--allow-sensitive-reads` gate: it returns the form's value **without** running the wire-boundary elision walker, so whatever those forms read ships verbatim — rendered user text and attribute values included. The structured `read-dom` / `read-ui` tools do the opposite, capping and eliding per node at the source (`:rf.size/large-elided`), and `read-ui`'s `:text` additionally routes through `elide-wire-value`. So when the screen may carry privacy-sensitive content, prefer the structured tool; reach for a raw `dom/*` eval for that content only on explicit user/operator request. This is the same raw-eval carve-out as [SKILL.md §Style guidance](../SKILL.md), which is always loaded.

Three doors, in the order you usually need them:

- **[DOM source bridge](#dom-source-bridge)** — a DOM element → the registration that produced it (`data-rf2-source-coord`).
- **[Reading what's on screen](#reading-whats-on-screen--two-planes-read-dom-vs-read-ui)** — `read-dom` (raw DOM plane) vs `read-ui` (re-frame2 view plane).
- **[Hicasso evidence](#hicasso-evidence--mounted-boundaries-read-attribution-render-cause)** — mounted boundaries, read attribution, render cause.

## DOM source bridge

**Why this family matters — read first.** In a debug build, re-frame2 injects a `data-rf2-source-coord` attribute on every **registered view's** root DOM element pointing back to the registration that produced it (mandatory per Tool-Pair §Source-mapping / Spec 006 §Source-coord annotation, gated on `interop/debug-enabled?` — **no** `configure!` knob, not user-enabled). `re-frame2-pair.runtime/parse-rf2-coord` parses that attribute into `{:ns :handler-id :line :col}` (the registration's source coords, auto-captured by `reg-*` macros per Spec 001 §Source-coordinate capture) — a direct two-way bridge between a live DOM element and the exact source line that rendered it. (`:file` is not on the raw attribute; it arrives only when the coord is enriched through `handler-meta`, as `read-ui`'s `:source-coord` does.)

**Two attribute formats are recognised:**

- `data-rf2-source-coord` — re-frame2's own annotation, present on registered-view roots in debug builds. Stable, preferred.
- `data-rc-src` — re-com's debug-instrumentation attribute. The runtime parses both; if both are present on a node, `data-rf2-source-coord` wins.

**Prerequisites — at least one of:**

- a debug build with the element produced by a **registered view** (`reg-view`) on a DOM-capable adapter (annotation is mandatory there — no opt-in needed), *or*
- re-com debug instrumentation enabled and the call site passed `:src (at)`.

**Degradation is per-element.** Neither attribute present on an element → `{:src nil :reason :no-coord-at-this-element}` (e.g. an anonymous Reagent fn, not a registered view). No source-coord attributes reaching the DOM app-wide (production build, or no registered-view / re-com coverage) → every element returns `{:src nil :reason :source-coord-annotation-disabled}` — diagnose by checking registered-view coverage, DOM-capable adapter, debug build (`goog.DEBUG`), or a re-com `:src (at)` fallback. Tell the user which case they're hitting.

| Op | Invocation | Returns |
|---|---|---|
| `dom/source-at` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/dom-source-at \"#save-button\")"}` (or `(... :last-clicked)`) | `{:ok? true :src <coord>}` for a CSS selector (or the most recently clicked element) — `:src` is `{:ns :handler-id :line :col}` when the re-frame2 attribute matched, `{:file :line :column}` on the re-com `data-rc-src` fallback |
| `dom/find-by-src` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/dom-find-by-src \"view.cljs\" 84)"}` | Live DOM elements rendered by that source line |
| `dom/fire-click-at-src` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/dom-fire-click \"view.cljs\" 84)"}` | Synthesise a click on the element rendered by that line |
| `dom/describe` | `mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/dom-describe \"#save-button\")"}` | Tag, classes, both source-coord attributes, and any registration metadata they resolve to |

## Reading what's on screen — two planes (`read-dom` vs `read-ui`)

Two rendered-state reads at **different layers** — NOT duplicates:

- **`read-dom` = the raw DOM plane.** A CSS selector → matched nodes `{:tag :text :attrs}`. Multi-node, exact, **no re-frame2 awareness**. *"What does this exact node SAY?"*
- **`read-ui` = the re-frame2 view plane.** Rides the `data-rf-view` map → content **PLUS the producing entity** (view-id, source-coord, subs-read, render-key). *"What is this view, and what produced it?"*

Both read what the app **rendered**. When the question is instead which Hicasso boundaries are mounted, what reads a given subscription, or which reads moved most recently, that is the [Hicasso evidence door](#hicasso-evidence--mounted-boundaries-read-attribution-render-cause) below.

**When to use which:** `read-dom` when you already have a CSS selector, want raw content across N matched nodes, and don't need provenance; `read-ui` when you want a view's content **and** its re-frame2 entity (which subs feed it, where it's defined) in one round-trip — or when you only have a view-id / screen point rather than a selector. Both apply per-node text caps at the source and emit the same `:rf.size/large-elided` marker for over-cap text. Both call the same preloaded runtime ns (`re-frame2-pair.runtime/dom-read` / `…/ui-read`) and share one per-node projection, so content shapes stay aligned.

### View → rendered content + producing entity (`ui/read`)

**The most common UI-pairing question, first-classed.** The DOM source bridge above maps a gesture/selector → *source coord*; `read-dom` returns raw *content* by explicit CSS selector. `ui/read` (MCP tool `read-ui`) does what neither does: given a **view-id** (or point / CSS selector), return the **rendered subtree** as structured, elided data **PLUS the re-frame2 entity that produced it** — view-id, source-coord, render-key, and the frame's live `subs-read` — in one round-trip. It rides the **same view-id↔DOM map** the Xray pink hover-highlight uses (every registered view's root carries `data-rf-view="<id>"`, per [Spec 006 §View tagging contract](https://github.com/day8/re-frame2/blob/main/spec/006-ReactiveSubstrate.md#view-tagging-contract-fallback)), so it works on **any** re-frame2 app with **zero testids** — no guessing selectors then mapping the node back to a view by hand.

Pass **exactly one** entry point (precedence `view-id` > `point` > `selector`). The returned `:text` routes through `re-frame.core/elide-wire-value` (the elision `snapshot` / `get-path` use), so large/sensitive content collapses to `:rf.size/large-elided` rather than shipping raw user DOM text. Read-only by construction.

| Op | Invocation | Returns |
|---|---|---|
| `ui/read` by view-id | `mcp__re-frame2-pair__read-ui {view-id: ":my.app/counter"}` | `{:ok? true :via :view-id :entity {:view-id … :source-coord {:ns … :line … :file …} :render-key … :subs-read [[:count] …]} :content {:tag "div" :text "Count: 3" :attrs {…}}}` |
| `ui/read` by point | `mcp__re-frame2-pair__read-ui {point: {x: 120, y: 240}}` | The view under viewport point (120,240): `elementFromPoint` → nearest `[data-rf-view]` ancestor → entity + content |
| `ui/read` by selector | `mcp__re-frame2-pair__read-ui {selector: "#save"}` | `querySelector` → walk up to the producing view → entity + content |

The accepted args (`:additionalProperties false`): `view-id`, `point`, `selector`, `max-text` (per-node char cap, default 2000), `frame`, `build`. Failure modes: `:no-target-arg` (no entry point), `:no-element` (entry point matched nothing), `:rf.error/ui-read-bad-selector` (malformed CSS). A portal / fragment leaf with no tagged view ancestor still returns `:content`, with `:entity {:view-id nil :reason :no-tagged-view-root}`.

### `read-dom` — raw DOM content by explicit CSS selector

`read-dom` is the **raw DOM plane** read by explicit CSS selector — the answer to *"did the UI update?"* / *"what does the rendered node SAY?"* when you have a selector and don't need entity provenance (for that, use `read-ui` above). The data-plane reads (`snapshot` / `get-path` / `read-sub` / `trace-window`) tell you what's in `app-db` and the trace; `read-dom` tells you what the app actually **put on screen**. Read-only by construction (only `querySelectorAll` + `textContent` / attribute strings). Pairs with `dispatch :await-render` / `:settle` for a deterministic *dispatch → settle → read-dom* observe.

| Op | Invocation | Returns |
|---|---|---|
| `read-dom` | `mcp__re-frame2-pair__read-dom {selector: "#app .counter"}` | `{:ok? true :selector … :count N :truncated? bool :nodes [{:tag "div" :text "Count: 3" :attrs {"class" "counter" "data-count" "3"}}]}` — `:count` is the full pre-`:limit` tally |
| `read-dom` scoped | `mcp__re-frame2-pair__read-dom {selector: ".card", sub-selector: ".title"}` | `:sub-selector` runs RELATIVE to each matched node to narrow a coarse match (a card) to its inner parts |
| `read-dom` specific attrs | `mcp__re-frame2-pair__read-dom {selector: "input[name=email]", attrs: ["value", "data-valid"]}` | Omit `:attrs` and a curated default set rides PLUS a `data-*` / `aria-*` sweep (the re-frame2 view-plane idiom for surfacing rendered state) |

Caps are applied **at the source** (browser-side) so only bounded EDN crosses the wire: `:max-text` (per-node text cap, default 2000 — over-cap → `{:rf.size/large-elided {:type :dom-text :chars N :preview "…"}}`) and `:limit` (matched-node cap, default 50; `:truncated? true` when more matched than returned). Failure modes: `:rf.error/read-dom-bad-selector` (malformed CSS); a no-match returns `{:ok? true :count 0 :nodes []}`.

## Hicasso evidence — mounted boundaries, read attribution, render cause

The two reads above answer *"what is on screen right now?"*. Three further tools answer a different family of question: which **boundaries** are mounted, which of them read a given subscription, and **which reads moved** most recently. If React DevTools is your mental model, this is its Components panel plus the Profiler's *"why did this render?"*, reached over the nREPL rather than a browser extension. The deliberate divergence is honesty about what the runtime can prove: DevTools will happily show you a component's internals and infer the rest, whereas these projections egress only bounded, serializable, versioned data — and no read value at all — and label the things they cannot establish (see *Reading the honesty markers* below).

Every response carries `:schema`, `:producer` and `:read` — the evidence-schema version, the substrate, and which read answered — plus the two claims every Hicasso evidence surface states: `:complete?` and `:loss` (what was dropped, and why). Read those before you read the payload; they are what tell you how far to trust it. In a development build every mounted row, reader and explanation also carries `:views` — the declared views that rendered that boundary, each as `{:view "<ns>/<sym>" :source {:ns :file :line :column}}` — or `:unknown` for a body minted without a name, so a boundary reads as `app.views/todo-row` beside its key.

**A boundary has no name, and that is permanent.** The Hicasso runtime mints no boundary identity and keeps no view registry: a registration is its read set, React's notifier and the acquired cells. So a boundary is **keyed by the set of reads it holds**, `:instances` says how many hold that key, and `:view` / `:source` are `:unknown` under an `:opaque` naming projection. Do not ask these tools for a view name, do not report `:unknown` as a gap waiting to be closed, and do not tell a user their view is missing — tell them a boundary reading *these* subscriptions is mounted, which is the identity the runtime actually retains.

**Availability — expect a real "this app can't answer that".** All three read `re-frame.hicasso.tool`, which ships in `day8/re-frame2-hicasso`. Nothing in `re-frame.hicasso` requires it — that is how a production build never loads it — so a Hicasso app has the door only once something pulls it in (Xray does), and an app built on the Reagent or UIx adapter does not have it at all. Every one of the three then answers `{:ok? false :reason :evidence-tier-unavailable}` with a hint rather than an empty result. A production build nil-gates the whole door: `:evidence-tier-inactive`. Read either reason as *"this app cannot answer that"* — never as *"the answer is nothing"*.

**Reagent and UIx apps do not answer here, by design.** The Reagent, reagent-slim and UIx adapters are first-class and fully supported, and they are not Hicasso: an app whose views are Reagent components reads as `:evidence-tier-unavailable` on all three, which is the honest answer rather than a bug. Everything else in this skill works unchanged on such an app — `app-db`, dispatch, subscriptions, traces, epochs and `restore-epoch` are frame-level surfaces that no view layer participates in; for what is on screen, the DOM reads above still apply.

**All three read live state, and none takes an argument.** There is no id to narrow by, and nothing here is answerable before mount: these are projections of the read-set entry cache, the cell table and Spec 009's retained ring, taken at the moment you ask.

| Op | Invocation | Returns |
|---|---|---|
| `read-mounted-boundaries` | `mcp__re-frame2-pair__read-mounted-boundaries {}` | Every boundary mounted **right now**: `{:boundary {:key [[:app/main :todo [:todo 7]]]} :views [{:view "app.views/todo-row" :source {:ns app.views :file "…" :line 12 :column 1}}] :instances 3 :read-orders 1 :frame :app/main :reads [{:sub-id :todo :query [:todo 7] :frame-id :app/main :epoch 4}]}` |
| `read-read-attribution` | `mcp__re-frame2-pair__read-read-attribution {}` | The reverse edge, exactly: per subscription `{:sub-id :todo :query [:todo 7] :frame-id :app/main :epoch 4 :fan-out 3 :readers [{:key … :views …}]}` — `:fan-out` is the slot count, `:readers` the distinct boundaries holding them, each named by its `:views` |
| `explain-render` | `mcp__re-frame2-pair__explain-render {}` | Per boundary the proven half — `:latest-reads` at its `:peak-epoch`, and the `:snapshot` React itself compares — beside the leads: `:candidates`, with the loss accounting described below |

All three also accept `build` and `max-tokens`. None takes a `view-id`; there is no such argument on this door.

**Choosing between them.** Reach for `read-mounted-boundaries` when the question is *"which views are mounted, what do they read, and how many instances share a key?"* — it answers about **subscription**, not visibility, so *"is this thing on screen?"* stays with the DOM/host evidence (`read-dom` / `read-ui` above); React owns commit, paint and visibility and this door does not restate them. Reach for `read-read-attribution` when the question is *"what reads this subscription?"* — and reach for it **first** whenever you can name a subscription but not a boundary, because it is the way in: attribution is how you turn a sub you know into the `:key` and `:views` of the boundaries holding it. Reach for `explain-render` last, when a boundary *is* mounted and reading the right subscription but rendered when you did not expect it to.

**Reading the honesty markers.** These tools are deliberate about the boundary between evidence and inference, and the markers are the point rather than noise:

- `:views :unknown` means the boundary was minted without a name — a harness body, or one built outside `defview` — not that the read degraded. A view named outside the macro carries `:source :unknown`, because no coordinate was ever declared for it.
- No explanation names a cause, and that is **structural**, not circumstantial: the commit seam records no cascade id, so there is nothing to join; a bigger ring does not fix it and neither does a longer session. The row's own `:loss` says so.
- `:candidates` are **leads, never the answer** — retained runs that recomputed a subscription this boundary reads. Quote them as candidates or not at all; presenting one as the cause is exactly the failure the loss vocabulary exists to prevent.
- `:loss {:reason :uncorrelated}` means the lead search really ran, so `:candidates []` is an honest survey result. `{:reason :cap}` means the boundary's own frames held an empty window, so no search happened and `:candidates` is `:unknown`. Different reasons, different remedies: the second is answered by a bigger `:rf.trace/events-retained` buffer, the first is not.
- `:read-orders` above 1 means two entries were folded into one row — either because their key arrays differed only in order, or because the application declared the arguments that told them apart sensitive. The row is the honest ceiling the egress policy allows.
- Commit, paint, attempt outcome, visibility and hidden-retained are React's, and this door ships no field for them rather than a field stating `:unknown`. These rosters are about **subscription**, not visibility; React DevTools is the authority on the screen.

**What this door deliberately does not have.** It is a reader, not an accumulator: nothing is retained to answer these questions. There is no occurrence index, no history store and no second retention knob — the one history any read folds is Spec 009's ring, folded at read time and kept by nobody. `read-mounted-boundaries` and `read-read-attribution` answer about **now** and say nothing about what once was. **No read carries a read VALUE**, at all: what a boundary read is a fact about the boundary, what it read *as* is application data, and this surface is not a second egress path for it. If a user asks for a value, `read-sub` and `get-path` are where that egress is governed.

**Empty versus absent.** With the door loaded and nothing mounted, `read-mounted-boundaries` returns an empty-but-versioned `{:ok? true :boundaries []}` — and it is complete for its scope, because the read-set entry cache is authoritative about what holds a live read edge. What the empty does **not** establish is that nothing is retained above: an Activity-hidden subtree that released its reads leaves the same empty census as an unmounted one, and only a later re-subscribe distinguishes them. Read the other way round, a row is not proof the boundary is on screen — a Suspense-fallback-hidden subtree stays subscribed and stays in the roster. A read stamped a `:schema` this tool build was not written against comes back as `{:ok? false :reason :evidence-tier-version-mismatch}` with `:expected` and `:actual` rather than a successful read of a shape it cannot parse; there is no compatibility adapter on either side, deliberately. A throwing read degrades to `:evidence-tier-error` with a `:message` rather than failing the eval. Every `:ok? false` rides `isError: true`, so a degraded read is never cached and never masquerades as a successful empty answer.
