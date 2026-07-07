# Variant-as-frame — driving Story variants from a re-frame2-pair session

> A Story variant *is* a re-frame2 frame. This leaf documents the pattern and what falls out of it for re-frame2-pair — frame-scoped reads/writes/watches against a variant's isolated app-db, no extra resolver step. One exception (see Idiom 1): `subscribe` has no `frame` arg, so the operating-frame pin does **not** scope a streaming subscription — scope it with `filter {:frame ...}`. Assumes you've read `SKILL.md` (the multi-frame model) and have a Story-enabled build running.

## When to load this leaf

- The user mentions a Story variant, workspace, or "the canvas" while re-frame2-pair is attached.
- You need to read or mutate one variant's state without touching another's.
- You want to diff two scenarios of the same component side by side.
- You're driving the four-phase lifecycle from re-frame2-pair (loaders → events → render → play) and need to know which dispatches land where.

Do **not** load this leaf to author variants — that lives in `skills/re-frame2/references/tooling/stories.md`. Load it for: the variant-id ↔ frame-id identity, the re-frame2-pair ops scoped to a variant, and the gotchas that flow from per-variant frame isolation.

## The identity — variant-id IS the frame-id

Per [`spec/007-Stories.md` §Relationship with frames](../../../spec/007-Stories.md) and [`tools/story/spec/002-Runtime.md` §Per-variant frame allocation](../../../tools/story/spec/002-Runtime.md), at variant-mount time the Story runtime calls:

```clojure
(rf/reg-frame variant-id {:doc ... :preset :story :rf/story? true :rf/variant variant-id ...})
```

(The frame is created with `:preset :story` plus the `:rf/story?` / `:rf/variant` marker keys; app-db seeding rides the variant's `:loaders` / `:events` as setup events, not a create-time `:app-db` key. There is no `:initial-db` config seed.) The `variant-id` keyword (e.g. `:story.counter/loaded`) is BOTH the variant id Story tracks in its side-table AND the frame id re-frame2's registrar knows — the same keyword, no separate "frame-id for this variant". **No resolver step needed.** Anywhere re-frame2-pair's ops take a `frame: ":foo"` arg (or `{:frame <id>}` in the runtime helpers), pass the variant id directly.

This identity is the single most important thing about the pattern; once internalised, the rest is normal re-frame2-pair ops with a different default-frame.

## re-frame2-pair ops scoped to a variant

Every re-frame2-pair op that takes an operating frame works against a variant out of the box. The two recommended idioms:

**Idiom 1 — pin the session operating frame, then operate normally.** `set-operating-frame {frame: ":story.counter/loaded"}` pins the variant as the session's default; the frame-arg-bearing ops (`snapshot`, `get-path`, `dispatch`, `read-sub`, `list-subscriptions`, `trace-window`, `watch-epochs`, …) inherit it (the eval-based `select-frame!` is the lower-level equivalent).

```
set-operating-frame {frame: ":story.counter/loaded"}
snapshot                               ;; reads the variant's frame
trace-window                           ;; epoch window from the variant's frame
dispatch {event: "[:counter/inc]"}     ;; dispatches into the variant's frame
```

> **`subscribe` is the exception — pin it doesn't scope.** Unlike the ops above, the `subscribe` streaming tool has **no top-level `frame` arg**; the operating-frame pin does not restrict it. A pinned variant + `subscribe {topic: "epoch"}` streams epochs from **every** frame. To scope a stream to a variant, pass `filter {:frame ":story.counter/loaded"}` (see [§Recipe — drive a Story variant](recipes.md) and the [streaming-subscriptions](streaming-subscriptions.md) filter vocab). Until/unless `subscribe` gains a `frame` arg, the filter is the only frame scope for streams.

**Idiom 2 — explicit `frame` per call.** Useful when you're flipping between variants or when the session-default is something else (e.g. `:rf/default`).

```
mcp__re-frame2-pair__dispatch  {event: "[:counter/inc]", frame: ":story.counter/loaded"}
mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/snapshot {:frame :story.counter/loaded})"}
mcp__re-frame2-pair__get-path  {path: "[:count]", frame: ":story.counter/loaded"}
```


Use Idiom 1 for long sessions inside one variant; Idiom 2 for cross-variant work (recipe 2 below).

## What's per-variant (frame-scoped)

Each variant has its own isolated copy of every per-frame surface. State does not leak between variants — that's the whole point.

- **`app-db`** — each variant starts with `{}` (or whatever loaders + events populate). `(rf/app-db-value :story.counter/loaded)` and `:story.counter/empty` return independent values.
- **Epoch history** — `(rf/epoch-history :story.counter/loaded)` is its own ring. Dispatches into one variant never appear in another's history.
- **Sub cache** — the live sub-cache snapshot (`re-frame.subs.tooling/sub-cache-snapshot`, alias `subs/sub-cache-snapshot`) is per-frame; `[:count]` materialised in `:story.counter/loaded` is independent of `[:count]` materialised in `:story.counter/empty`.
- **Trace events** — `:frame` is stamped on every emitted trace event (Spec 009 §Per-frame stamping). Filter raw trace by `{:frame :story.counter/loaded}` to scope.
- **`[:rf.runtime/elision :declarations]`** — the elision registry lives in the **runtime-db** partition under the reserved `:rf.runtime/elision` child (Spec 009 §Nomination paths), so large-path nominations are per-frame too. A variant that declares `[:cart :inventory]` as a large-path doesn't affect another variant's elision behaviour.
- **Error observability** — `:frame` is stamped on every `:rf.error/*` record, so filtering the always-on `register-listener! :errors` stream (or the trace buffer) by `{:frame :story.counter/loaded}` scopes errors to one variant. Recovery is framework-owned (the per-category typed default); there is no per-frame recovery policy.
- **`:fx-overrides`** — Story's `:fx-override`-kind decorators stub fx per-variant (e.g. `:http → :stub-http`). Calls into one variant's stub do not affect another.

## What's NOT per-variant (registry-scoped)

A frame resolves behaviour against its **resolved image generation** (the image selects the registration set — SKILL.md §Multi-frame model). In a single-installation app (the Story case) the global `reg-event`, `reg-sub`, `reg-fx`, `reg-machine`, `reg-view`, `reg-decorator`, etc. register into one shared set, and every variant/frame sharing it sees them. Hot-swapping a handler via re-frame2-pair's `eval-cljs` affects every variant sharing that set — useful for the experiment loop (`recipes.md §Experiment loop`), occasionally surprising if you expected variant-scoped isolation.

If you need a handler change to affect *only* one variant, use the per-frame `:interceptor-overrides` slot on `reg-frame` (Spec 002 §Per-frame overrides) — but Story's variant-mount doesn't expose this directly; you'd need to mutate the variant's frame metadata via `eval-cljs`. Usually the right move is to dispatch different args into different variants rather than reach for per-frame overrides.

## Discovering the current variant

When you've attached to a Story-enabled build and don't know which variants are registered:

```
get-operating-frame                      ;; :frames lists every registered frame (rf/frame-ids)
```

Story-registered variants appear as `:story.*` keywords. Filter:

```
mcp__re-frame2-pair__eval-cljs {form: "(filter #(= \"story\" (namespace %)) (rf/frame-ids))"}
```


For richer metadata (parent story, tags, modes, substrates), Story's side-table is reachable via `list-stories` / `get-variant` — but those are the **authoring** surface, allow-listed by the `re-frame2` skill rather than re-frame2-pair, so reach them across the skill boundary only if a session has the authoring skill loaded. Within re-frame2-pair's own surface, fall back to `(re-frame.story/variant->edn <id>)` over `eval-cljs`. The variant-id grammar (`:story.<dotted.path>/<variant-name>`) is documented in `skills/re-frame2/references/tooling/stories.md`.

To discover the *active* variant in the user's canvas (the one currently visible), read the shell's own active-variant slot — `eval-cljs {form: "(:selected-variant (re-frame.story.ui.state/get-state))"}` — or ask the user. (There is no `:story/active?` frame-metadata flag; the shell tracks the active variant in its `:selected-variant` state.) re-frame2-pair has no DOM bridge that locates the canvas iframe specifically; use `dom/source-at` on something inside it.

## Common gotchas — variant-as-frame specific

- **Dispatching without a `frame:` arg does NOT silently target the variant.** Forget to pin-or-pass the variant id and the op resolves by the four-tier contract (`SKILL.md` §Multi-frame model). With the variant frame plus the host app frame both live, that's two-plus app frames, so the op **refuses** with `:reason :ambiguous-frame` — you see a refusal, not the variant's epoch history. Pin a frame (`set-operating-frame {frame: ":story.foo/bar"}`) and subsequent ops target it. Be explicit when working across frames.
- **`destroy-frame!` happens on variant-unmount.** If the user navigates away from the variant in the canvas, the frame is gone. Subsequent ops against `:story.foo/bar` return `:rf.error/no-such-handler` (kind `:frame`). The fix is to navigate back, or to call `(re-frame.story/run-variant :story.foo/bar)` to re-mount the variant programmatically.
- **`reset-frame!` on re-registration.** Hot-reloading a variant (or `register-variant` over MCP with the same id) calls `reset-frame!` on its frame — `app-db` reverts to `{}`, then loaders + events re-run. Any REPL-only state you'd injected (`app-db/reset`, hot-swapped handlers' side effects on app-db) is gone. Permanent state lives in the variant body's `:loaders` / `:events` slots.
- **Loaders run before re-frame2-pair can see them.** Phase 1 loaders dispatch-sync into the variant's frame at mount time — `:rf.event/dispatched` traces fire, but if you attach re-frame2-pair *after* the variant mounted, those traces are already in the retain-N ring (visible) but not in the recent dispatch window. Use `trace/buffer` (not `trace/recent`) to see loader events that fired before you attached.
- **`:play-script` steps look like user interactions.** The play-runner uses `dispatch` / `dispatch-sync` per phase 4; epoch records carry the dispatched event in `:trigger-event`. Dispatch tagging has two independent axes (Spec 002 §Dispatch origin tagging): `:origin` — the *actor*, an **open vocabulary** defaulting to `:app` (pair tools stamp `:origin :pair`; common values `:pair`, `:claude`, `:story`, `:test`) — and `:source` — the *trigger kind*, the closed enum in `spec/Spec-Schemas.md §:rf/dispatch-envelope` (`:ui / :frame-init / :machine-spawn / :machine-action / :always / :after-timer / :fx-dispatch / :fx-dispatch-later / :http / :router / :ssr-hydration / :test / :tool / :websocket / :repl / :unknown / :other`). The play-runner dispatches with only `:frame` set (`(dispatch-sync event {:frame frame-id})`), so its events fall under the default `:origin :app` — no `:origin :play-script` distinguisher. Your own re-frame2-pair dispatches *do* carry `:origin :pair`, so to tell pair-driven from play-driven inside a variant, filter `pred {:origin :pair}` for your own, or scope reads to the variant's frame (pin with `set-operating-frame`) and lean on timing / phase rather than a play-specific origin tag.
- **Workspaces nest frame-providers.** A `reg-workspace` containing variants A, B, C renders each variant inside its own `frame-provider`. Workspace frames may or may not exist as registered frames themselves (per spec/007 §Relationship-with-frames: *"may be ordinary frames containing nested `frame-provider`s"*). When the user points at "the workspace", clarify whether they mean the layout-level frame (if any) or one of its variant frames.

## Cross-references

- Authoring variants — [`skills/re-frame2/references/tooling/stories.md`](../../re-frame2/references/tooling/stories.md).
- Story-MCP self-healing loop — [`skills/re-frame2/references/tooling/story-mcp-loop.md`](../../re-frame2/references/tooling/story-mcp-loop.md).
- Recipes driving variants from re-frame2-pair — [`recipes.md`](recipes.md) §Drive a Story variant, §Diff two variants, §Refine a variant interactively.
- The frame primitive itself — [`spec/002-Frames.md`](../../../spec/002-Frames.md).
- Story runtime spec — [`tools/story/spec/002-Runtime.md`](../../../tools/story/spec/002-Runtime.md).
