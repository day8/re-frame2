# Variant-as-frame — driving Story variants from a pair2 session

> A Story variant *is* a re-frame2 frame. This leaf documents the pattern and what falls out of it for pair2 — frame-scoped reads/writes/watches against a variant's isolated app-db, no extra resolver step. Assumes you've read `SKILL.md` (the multi-frame model) and have a Story-enabled build running.

## When to load this leaf

- The user mentions a Story variant, workspace, or "the canvas" while pair2 is attached.
- You need to read or mutate one variant's state without touching another's.
- You want to diff two scenarios of the same component side by side.
- You're driving the four-phase lifecycle from pair2 (loaders → events → render → play) and need to know which dispatches land where.

Do **not** load this leaf to author variants — that lives in `skills/re-frame2/reference/tooling/stories.md`. Load it for: the variant-id ↔ frame-id identity, the pair2 ops scoped to a variant, and the gotchas that flow from per-variant frame isolation.

## The identity — variant-id IS the frame-id

Per [`spec/007-Stories.md` §Relationship with frames](../../../spec/007-Stories.md) and [`tools/story/spec/002-Runtime.md` §Per-variant frame allocation](../../../tools/story/spec/002-Runtime.md), at variant-mount time the Story runtime calls:

```clojure
(rf/reg-frame variant-id {:doc ... :app-db {} :substrate :reagent ...})
```

The `variant-id` keyword (e.g. `:story.counter/loaded`) is BOTH the variant id Story tracks in its side-table AND the frame id re-frame2's registrar knows. There is no separate "frame-id for this variant" — they are the same keyword. **You do not need a resolver step.** Anywhere pair2's ops take `--frame <id>` (or `{:frame <id>}` in the runtime helpers), you pass the variant id directly.

This identity is the single most important thing about the variant-as-frame pattern. Once you've internalised it, the rest is just normal pair2 ops with a different default-frame.

## Pair2 ops scoped to a variant

Every pair2 op that takes an operating frame works against a variant out of the box. The two recommended idioms:

**Idiom 1 — `frames/select` then operate normally.** Sets the session's default operating frame to the variant; subsequent calls inherit it.

```
frames/select :story.counter/loaded
app-db/snapshot                        ;; reads the variant's frame
trace/last-epoch                       ;; epoch history from the variant's frame
dispatch [:counter/inc]                ;; dispatches into the variant's frame
```

**Idiom 2 — explicit `frame` per call.** Useful when you're flipping between variants or when the session-default is something else (e.g. `:rf/default`).

```
mcp__re-frame-pair2__dispatch  {event: "[:counter/inc]", frame: ":story.counter/loaded"}
mcp__re-frame-pair2__eval-cljs {form: "(re-frame-pair2.runtime/snapshot {:frame :story.counter/loaded})"}
mcp__re-frame-pair2__get-path  {path: "[:count]", frame: ":story.counter/loaded"}
```

Legacy bash forms (if the MCP server isn't wired): `scripts/dispatch.sh '[:counter/inc]' --frame :story.counter/loaded` and `scripts/eval-cljs.sh '(re-frame-pair2.runtime/snapshot {:frame :story.counter/loaded})'`.

Use Idiom 1 for long sessions inside one variant; Idiom 2 for cross-variant work (recipe 2 below).

## What's per-variant (frame-scoped)

Each variant has its own isolated copy of every per-frame surface. State does not leak between variants — that's the whole point.

- **`app-db`** — each variant starts with `{}` (or whatever loaders + events populate). `(rf/get-frame-db :story.counter/loaded)` and `:story.counter/empty` return independent values.
- **Epoch history** — `(rf/epoch-history :story.counter/loaded)` is its own ring. Dispatches into one variant never appear in another's history.
- **Sub cache** — `(rf/sub-cache)` is per-frame; `[:count]` materialised in `:story.counter/loaded` is independent of `[:count]` materialised in `:story.counter/empty`.
- **Trace events** — `:frame` is stamped on every emitted trace event (Spec 009 §Per-frame stamping). Filter raw trace by `{:frame :story.counter/loaded}` to scope.
- **`:rf/elision :declarations`** — the elision registry lives in app-db (Spec 009 §Nomination paths), so large-path nominations are per-frame too. A variant that declares `[:cart :inventory]` as a large-path doesn't affect another variant's elision behaviour.
- **`:on-error` policy** — each frame can carry its own policy. Variants intended to provoke errors can install a policy that records-and-continues without affecting sibling variants.
- **`:fx-overrides`** — Story's `:fx-override`-kind decorators stub fx per-variant (e.g. `:http → :stub-http`). Calls into one variant's stub do not affect another.

## What's NOT per-variant (registry-scoped)

The **registrar** is global: `reg-event`, `reg-sub`, `reg-fx`, `reg-machine`, `reg-view`, `reg-decorator`, etc. are visible from every frame. Hot-swapping a handler via pair2's `repl/eval` affects every variant that dispatches that event — useful for the experiment loop (`recipes.md §Experiment loop`), occasionally surprising if you expected variant-scoped isolation.

If you need a handler change to affect *only* one variant, use the per-frame `:interceptor-overrides` slot on `reg-frame` (Spec 002 §Per-frame overrides) — but Story's variant-mount doesn't expose this directly; you'd need to mutate the variant's frame metadata via `repl/eval`. Usually the right move is to dispatch different args into different variants rather than reach for per-frame overrides.

## Discovering the current variant

When you've attached to a Story-enabled build and don't know which variants are registered:

```
frames/list                              ;; (rf/frame-ids) — every registered frame
```

Story-registered variants appear as `:story.*` keywords. Filter:

```
mcp__re-frame-pair2__eval-cljs {form: "(filter #(= \"story\" (namespace %)) (rf/frame-ids))"}
```

Legacy bash form: `scripts/eval-cljs.sh '(filter #(= "story" (namespace %)) (rf/frame-ids))'`.

For richer metadata (parent story, tags, modes, substrates), use Story's side-table via the MCP transport if available (`mcp__re-frame2-story-mcp__list-stories` / `get-variant`), or fall back to `(re-frame.story/variant->edn <id>)` over `repl/eval`. The variant-id grammar (`:story.<dotted.path>/<variant-name>`) is documented in `skills/re-frame2/reference/tooling/stories.md`.

To discover the *active* variant in the user's canvas (the one currently visible), inspect frame metadata for the `:story/active?` flag set by Story's shell — or ask the user. Pair2 has no DOM bridge that locates the canvas iframe specifically; use `dom/source-at` on something inside it.

## Common gotchas — variant-as-frame specific

- **Dispatching without `--frame` hits `:rf/default`, not the variant.** If you forget to select-or-pass the variant id, your dispatch lands in the host app's default frame and you'll see *nothing* in the variant's epoch history. The `:ambiguous-frame` refusal (`SKILL.md` §Multi-frame model) kicks in only when no session-default is set; once you `frames/select :rf/default` (e.g. earlier in the session), subsequent dispatches silently target it. Be explicit when working across frames.
- **`destroy-frame!` happens on variant-unmount.** If the user navigates away from the variant in the canvas, the frame is gone. Subsequent ops against `:story.foo/bar` return `:rf.error/no-such-handler` (kind `:frame`). The fix is to navigate back, or to call `(re-frame.story/run-variant :story.foo/bar)` to re-mount the variant programmatically.
- **`reset-frame!` on re-registration.** Hot-reloading a variant (or `register-variant` over MCP with the same id) calls `reset-frame!` on its frame — `app-db` reverts to `{}`, then loaders + events re-run. Any REPL-only state you'd injected (`app-db/reset`, hot-swapped handlers' side effects on app-db) is gone. Permanent state lives in the variant body's `:loaders` / `:events` slots.
- **Loaders run before pair2 can see them.** Phase 1 loaders dispatch-sync into the variant's frame at mount time — `:event/dispatched` traces fire, but if you attach pair2 *after* the variant mounted, those traces are already in the retain-N ring (visible) but not in the recent dispatch window. Use `trace/buffer` (not `trace/recent`) to see loader events that fired before you attached.
- **`:play` events look like user interactions.** The play-runner uses `dispatch-sync` per phase 4; epoch records carry the play event in `:trigger-event`. There's no `:origin :play` distinguisher (the dispatch origin tagging surface — Spec 002 §Dispatch origin tagging — currently lists `:pair :app :ui :timer :http`, not `:play`). If you need to tell agent-driven from play-driven dispatches inside a variant, filter on something else (e.g. timing, or assert from outside the variant).
- **Workspaces nest frame-providers.** A `reg-workspace` containing variants A, B, C renders each variant inside its own `frame-provider`. Workspace frames may or may not exist as registered frames themselves (per spec/007 §Relationship-with-frames: *"may be ordinary frames containing nested `frame-provider`s"*). When the user points at "the workspace", clarify whether they mean the layout-level frame (if any) or one of its variant frames.

## Cross-references

- Authoring variants — [`skills/re-frame2/reference/tooling/stories.md`](../../re-frame2/reference/tooling/stories.md).
- Story-MCP self-healing loop — [`skills/re-frame2/reference/tooling/story-mcp-loop.md`](../../re-frame2/reference/tooling/story-mcp-loop.md).
- Recipes driving variants from pair2 — [`recipes.md`](recipes.md) §Drive a Story variant, §Diff two variants, §Refine a variant interactively.
- The frame primitive itself — [`spec/002-Frames.md`](../../../spec/002-Frames.md).
- Story runtime spec — [`tools/story/spec/002-Runtime.md`](../../../tools/story/spec/002-Runtime.md).
