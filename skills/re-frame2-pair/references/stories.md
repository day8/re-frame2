# Stories — driving Story variants in the app you have open

> A Story variant *is* a re-frame2 frame, and in a pair session it is a frame **in the browser heap on the other end of your nREPL connection**. So there is nothing new to learn about addressing one: enumerate and run through `eval-cljs` over `re-frame.story/*`, then read, dispatch, trace and diff it with the ordinary Pair tools. Assumes you have read `SKILL.md` (the trace + epoch primitives and the multi-frame model) and have a Story-enabled build running.

## When to load this leaf

- The user mentions a Story variant, a workspace, or "the canvas" while you are attached to their app.
- They want a variant *driven* — mounted, run, re-run after a fix — not just observed.
- They want a variant *asserted* against: was the play sequence valid, did the cascade meet its `:rf.assert/*` expectations, did axe-core find a regression.
- They want one variant's state read or mutated without touching another's, or two scenarios of the same component compared side by side.

Do **not** load this leaf to author variant bodies from scratch with no runtime in the loop — that is `skills/re-frame2/references/tooling/stories.md`. And do not load it to run Story **headlessly**: see [§Headless Story is a different task](#headless-story-is-a-different-task) at the foot of this page.

## The identity — variant-id IS the frame-id

Per [`spec/007-Stories.md` §Relationship with frames](https://github.com/day8/re-frame2/blob/main/spec/007-Stories.md) and [`tools/story/spec/002-Runtime.md` §Per-variant frame allocation](https://github.com/day8/re-frame2/blob/main/tools/story/spec/002-Runtime.md), at variant-mount time the Story runtime calls:

```clojure
(rf/make-frame {:id variant-id :doc ... :preset :story :rf/story? true :rf/variant variant-id ...})
```

(The frame is created with `:preset :story` plus the `:rf/story?` / `:rf/variant` marker keys; app-db seeding rides the variant's `:loaders` / `:setup` as setup events, not a create-time `:app-db` key. There is no `:initial-db` config seed.) The `variant-id` keyword (e.g. `:story.counter/loaded`) is BOTH the variant id Story tracks in its side-table AND the frame id re-frame2's registrar knows — the same keyword, **no resolver step**. Anywhere a Pair op takes a `frame: ":foo"` arg, pass the variant id directly.

This identity is the single most important thing on this page. Once you have it, the rest is ordinary Pair work with a different operating frame.

## Enumerate, run, operate

The `re-frame.story` namespace is loaded in any Story-enabled build. Reference it by its full name in an `eval-cljs` form — there is no `story` alias in the browser unless the app made one.

**1. Enumerate the registry.** `ids` takes a registrar kind — `:story` for the parent stories, `:variant` for every concrete variant; `variants-of` returns one story's variants:

```
mcp__re-frame2-pair__eval-cljs {form: "(sort (re-frame.story/ids :story))"}
;; => (:story.login :story.cart …)

mcp__re-frame2-pair__eval-cljs {form: "(sort (re-frame.story/variants-of :story.login))"}
;; => (:story.login/empty :story.login/filled :story.login/success …)
```

`(re-frame.story/variant-frames)` is the complementary read — every variant frame currently *allocated*, as against every variant *registered*. `(re-frame.story/lifecycle-state :story.login/success)` returns `:pre-mount` / `:mounting` / `:loading` / `:ready` / `:error` for one of them.

**2. Run a variant — it returns a Promise, so `await`.** `run-variant` allocates the variant's frame and runs setup → loaders → events → play, resolving to the unified run-result. In the browser it returns a **JS Promise**, so eval it with `await: true` — the server awaits the thenable and returns the resolved value as `:value` (a plain eval would hand back an unresolved Promise). Project down to the verdict in the SAME round-trip so the whole (potentially large) result never crosses the wire — `eval-cljs` is un-elided, so returning only `:status` + failing assertions is both smaller and privacy-safer than shipping the variant's whole `:app-db`:

```
mcp__re-frame2-pair__eval-cljs {
  form: "(.then (re-frame.story/run-variant :story.login/success)
                (fn [res]
                  {:status   (re-frame.story/result-status res)
                   :passed?  (re-frame.story/result-passed? res)
                   :failures (filterv #(not= :pass (:status %))
                                      (:assertions res))}))",
  await: true, timeout-ms: 10000
}
;; => {:status :pass :passed? true :failures []}
```

The resolved run-result is the frozen spec/017 §Run-result contract: `:status` (`:pass` | `:fail` | `:cannot-run` | `:error` — read via `re-frame.story/result-status`; `result-passed?` is true only for `:pass`, a `:cannot-run` is NOT a pass), `:assertions` (the unified records, each with `:status` / `:passed?` / `:expected` / `:actual` / `:reason`), `:checks`, plus optional `:app-db` / `:elapsed-ms` / `:narrative` / `:effects` / `:sub-runs` / `:renders`. To inspect the whole result, `await` the bare `(re-frame.story/run-variant …)` form instead of the projection.

**The variant frame needs the app's adapter booted.** Enumerating is free; *running* is not. `run-variant` allocates the variant's frame, and frame allocation goes through `rf/make-state-container`, which requires an installed re-frame adapter. Nothing installs one for you — per [`spec/006-ReactiveSubstrate.md`](https://github.com/day8/re-frame2/blob/main/spec/006-ReactiveSubstrate.md) the substrate choice belongs to the app. A normally-booted tab already satisfies this, so you meet it in two narrow cases, and they are different ids with different remedies: `:rf.error/no-adapter-installed` when the app threw before `(rf/init! ...)`, `:rf.error/adapter-disposed` when a REPL experiment called `(rf/destroy-adapter!)`. Both name the delegation surface that demanded the adapter and repeat the id as a trailing `[:rf.error/...]` token in the message. Boot the adapter the app actually renders with — `(rf/init! reagent/adapter)`, not `plain-atom`, which renders nothing.

**That is not a `:cannot-run` verdict, and on this host it is not a pre-flight refusal either.** `:cannot-run` is a verdict the runner *produced* — it attempted the plan and could not honour it, so the remedy is to change the runner. A missing adapter is a host prerequisite instead, and neither refining the variant nor swapping the runner touches it. Nor does it arrive in the shape the headless host documents: browser-side `run-variant` has no pre-flight guard, so the throw is caught by the runner's own error branch and settles through the ordinary run/error path rather than as an `isError` refusal envelope ([§Headless Story is a different task](#headless-story-is-a-different-task) has that host's shape). Catalogue reads need no adapter either way — `ids`, `variants-of`, `variant->edn` and `explain` read the registry side-table and allocate no frame.

**3. Operate on it as a frame.** Pin the variant and every frame-targeted op inherits it; or pass `frame:` per call when you are flipping between variants:

```
set-operating-frame {frame: ":story.login/success"}
mcp__re-frame2-pair__read-sub  {sub: "[:auth.login/status]"}
mcp__re-frame2-pair__snapshot  {frames: [":story.login/success"]}
mcp__re-frame2-pair__dispatch  {event: "[:auth.login/submit]", frame: ":story.login/success"}
mcp__re-frame2-pair__watch-epochs {pred: {"frame": ":story.login/success"}}
```

Use the pin for a long session inside one variant, the per-call arg for cross-variant work. A fresh `run-variant` calls `reset-frame!` and wipes anything you injected between runs — bake durable setup into the variant's `:loaders` / `:setup`, not a REPL dispatch.

### Verified transcript (condensed)

Against a Story-enabled build with the `:story.login` example loaded (`examples/core/login`), one enumerate → run → read pass:

```
eval-cljs {form: "(sort (re-frame.story/variants-of :story.login))"}
;; :value (:story.login/auth-error :story.login/empty :story.login/filled
;;          :story.login/invalid-credentials :story.login/locked-out
;;          :story.login/submitting :story.login/success)

eval-cljs {form: "(.then (re-frame.story/run-variant :story.login/success)
                         (fn [res] [(re-frame.story/result-status res)
                                    (count (:assertions res))]))",
           await: true}
;; :value [:pass 3]
```

The entry-point names and the Promise-return + result shape are confirmed against `tools/story/src/re_frame/story.cljc`; substitute your app's own `:story.<name>` ids.

## The rest of the Story surface, from the same session

Every Story read worth having in a live session is a public `re-frame.story/*` fn, reachable through the same `eval-cljs` channel. There is deliberately no dedicated Pair tool for any of them — `eval-cljs` is the first-class long-tail surface (SKILL.md §Style guidance), and promoting one of these to a named tool is a later option behind a demand bar, not something to build pre-emptively.

| You want | Form |
|---|---|
| The failures from the last run, without re-running | `(re-frame.story/read-assertions :story.counter/loaded)` — the frame's `:rf.story/assertions` accumulator. It is a re-READ, so it is stale after any manual dispatch, and it carries no epoch tape: for the full verdict (tape floor + runner refusals) re-run instead. |
| A verdict over a result map or a bare assertions vector | `(re-frame.story/assertions-passing? x)` — given a run-RESULT it reflects the run `:status` (a floor-escalated `:fail` or a `:cannot-run` returns false even when every record passed); given a bare vector it is the vacuous-green fold. |
| A content hash to skip cells unchanged since a prior run | `(re-frame.story/snapshot-identity :story.counter/loaded)` → `{:variant-id :active-modes :substrate :content-hash}`, hashed over `(variant × resolved-args × decorators × loaders × substrate × modes)`. |
| Why the plan resolved this way — `:extends` lineage, composed fragments, strict conflicts, effective args, setup/script order | `(re-frame.story/explain :story.counter/loaded)` — the same `:explain` projection the human Explain panel renders. Pure author data over the registry side-table; it allocates no frame. |
| What a variant's decorators will do before you drive it | `(re-frame.story/registrations :decorator)` → `{id → body}`; each body carries `:kind` (`:hiccup` / `:frame-setup` / `:fx-override`) and its kind-specific slots, so an `:fx-override` stub (`:http → :stub-http`) is visible up front. |
| The variant body as data, or a story's own metadata | `(re-frame.story/variant->edn :story.counter/loaded)` / `(re-frame.story/handler-meta :story :story.counter)`. |
| axe-core violations the a11y panel accumulated | `(get @re-frame.story.ui.a11y/violations-by-frame :story.counter/loaded)` — a **browser-only** atom the panel fills. Reading it neither runs a fresh check nor proves the variant accessible; it reflects whatever the panel last stored. An empty vector for a frame the panel never scanned means *nothing looked*, not *nothing found*. |
| The variant the user is looking at right now | `(:selected-variant (re-frame.story.ui.state/get-state))` — the shell tracks the active variant there; there is no `:story/active?` frame-metadata flag. Pair has no DOM bridge that locates the canvas iframe specifically; use `dom/source-at` on something inside it. |
| Tear down or re-run one variant | `(re-frame.story/destroy-variant! id)` / `(re-frame.story/reset-variant id)`. |

**Capture a live interaction back into a `:script`.** The recorder is browser-side too, so it drives from `eval-cljs` like everything else — but it is not a zero-arity trio. `start-recording!` takes the variant it records against, and `gen-play-snippet` takes the captured events plus an opts map whose `:variant-id` is required. Only `stop-recording!` is zero-arity, so bind what it returns and feed that in:

```
mcp__re-frame2-pair__eval-cljs {form: "(:recording? (re-frame.story/start-recording! :story.counter/loaded))"}
;; => true          … now let the user interact with the canvas …

mcp__re-frame2-pair__eval-cljs {
  form: "(let [{:keys [events cofx]} (re-frame.story/stop-recording!)]
           (re-frame.story/gen-play-snippet
             events
             {:variant-id :story.counter/recorded-flow, :cofx cofx}))"
}
;; => "(story/reg-variant :story.counter/recorded-flow
;;        {:script {:auto-run? true :script [[:dispatch-sync [:counter/inc]] …]}})"
```

`stop-recording!` returns the recorder state — `:recording?` false, `:events` the captured event vectors in declared order, `:cofx` index-aligned with them, `:variant-id` naming the source. Stopping preserves that capture, so `(re-frame.story/recorder-state)` re-reads the same `:events` if you split the two round-trips. Passing `:cofx` is optional; without it the pasted snippet restamps coeffects on replay instead of re-presenting the recorded ones. The user lands the returned string back in source.

`gen-play-snippet` renders the bare `:events` stream — **dispatched events only**. Canvas clicks, typed input and form submits are captured too, but into `:entries`, and this snippet is blind to them: for those, `(re-frame.story/recording->script-body entries opts)` returns the live `{:script [...] :auto-run? …}` body, and the shell's own REC save dialog renders the rich pasteable form. What is captured at all is not free-form either: the trace-bus listener only offers `:rf.event/dispatched` events whose `:frame` matches the recording target, and `re-frame.story.recorder/recordable-event?` then drops Story's internal namespaces (`:rf.assert/*`, `:rf.story/*`, `re-frame.story.*`).

## What is per-variant, and what is not

Each variant has its own isolated copy of every per-frame surface. State does not leak between variants — that is the whole point.

- **`app-db`** — each variant starts with `{}` (or whatever loaders + events populate). `(rf/app-db-value :story.counter/loaded)` and `:story.counter/empty` return independent values.
- **Epoch history** — `(rf/epoch-history :story.counter/loaded)` is its own ring. Dispatches into one variant never appear in another's.
- **Sub cache** — the live snapshot (`re-frame.subs.tooling/sub-cache-snapshot`) is per-frame; `[:count]` materialised in one variant is independent of `[:count]` in another.
- **Trace events** — `:frame` is stamped on every emitted event (Spec 009 §Per-frame stamping), so filter raw trace by `{:frame :story.counter/loaded}` to scope.
- **`[:rf.runtime/elision :declarations]`** — the elision registry lives in the runtime-db partition under the reserved `:rf.runtime/elision` child (Spec 009 §Nomination paths), so large-path nominations are per-frame too.
- **Error observability** — `:frame` is stamped on every `:rf.error/*` record, so filtering the always-on `register-listener! :errors` stream by frame scopes errors to one variant. Recovery is framework-owned; there is no per-frame recovery policy.
- **`:fx-overrides`** — Story's `:fx-override` decorators stub fx per-variant. Calls into one variant's stub do not affect another.

**Registrations are NOT per-variant.** A frame resolves behaviour against its resolved image generation (SKILL.md §Multi-frame model). In a single-installation app — the Story case — `reg-event`, `reg-sub`, `reg-machine`, `reg-view`, `reg-decorator` register into one shared set every variant sees. So a hot-swap through `eval-cljs` affects **every** variant sharing that set: useful for the experiment loop, occasionally surprising. If you need a change scoped to one variant, dispatch different args into different variants rather than reaching for the per-frame `:interceptor-overrides` slot (Spec 002 §Per-frame overrides), which Story's variant-mount does not expose.

## Diffing two variants

Per-variant isolation is what makes *"why does state diverge in scenario A vs scenario B?"* a two-frame read. Snapshot both, then compute the difference in one round-trip:

```
mcp__re-frame2-pair__snapshot {frames: [":story.counter/empty", ":story.counter/loaded"]}

mcp__re-frame2-pair__eval-cljs {
  form: "(re-frame2-pair.runtime/frame-diff :story.counter/empty :story.counter/loaded)"
}
;; => {:only-in-a … :only-in-b … :common …}
```

`frame-diff` matches `epoch-diff`'s semantics but across two frames instead of one epoch's before/after. Cross-check the cascades with `(rf/epoch-history <id>)` on each: variants that ran the same events but ended in different states usually diverge in their loaders. Full recipe: [`recipes.md` §Diff two variants of the same component](recipes.md#diff-two-variants-of-the-same-component).

## Common gotchas

- **Dispatching without a `frame:` arg does NOT silently target the variant.** Forget to pin-or-pass and the op resolves by the four-tier contract (SKILL.md §Multi-frame model). With the variant frame plus the host app frame both live that is two-plus app frames, so the op **refuses** with `:reason :ambiguous-frame` — you see a refusal, not the variant's history. Pin it, or be explicit per call.
- **`run-variant` / re-registration call `reset-frame!`.** Each run wipes the variant's `app-db` back to `{}`, then re-runs loaders + setup + play. Any REPL-only state you injected — a `dispatch`, a `replace-app-db` — is gone. Permanent state lives in `:loaders` / `:setup`.
- **`destroy-frame!` happens on variant-unmount.** If the user navigates away in the canvas, the frame is gone and ops against it return `:rf.error/no-such-handler` (kind `:frame`). Navigate back, or re-mount with `(re-frame.story/run-variant :story.foo/bar)`.
- **Loaders run before you can see them.** Phase-1 loaders dispatch-sync into the variant's frame at mount time. If you attached *after* the variant mounted, those traces are already in the retain-N ring — reach for `trace/buffer`, not `trace/recent`.
- **`:script` steps look like user interactions.** The play-runner dispatches with only `:frame` set, so its events carry the default `:origin :app` — there is no play-specific origin tag. Your own Pair dispatches carry `:origin :pair`, so filter `pred {:origin :pair}` to isolate your own, and lean on frame scope and timing for the rest. (Both axes are Spec 002 §Dispatch origin tagging; `:source` is the closed enum in `spec/Spec-Schemas.md`.)
- **Workspaces nest frame-providers.** A `reg-workspace` containing variants A, B, C renders each inside its own `frame-provider`; the workspace itself may or may not be a registered frame (spec/007 §Relationship with frames). When the user points at "the workspace", clarify whether they mean the layout frame or one of its variant frames.

## Headless Story is a different task

Everything above targets the browser heap. Running Story **headlessly** — no browser, a same-JVM registry built inside another process — is a different job with a different host: the `re-frame2-story-mcp` server, documented at [`tools/story-mcp/README.md`](https://github.com/day8/re-frame2/blob/main/tools/story-mcp/README.md) and specified in [`tools/story-mcp/spec/000-Vision.md`](https://github.com/day8/re-frame2/blob/main/tools/story-mcp/spec/000-Vision.md). It is **not** part of this skill, and a live pair session must not start or call it. That host also refuses *differently*, which is worth knowing so you do not go looking for its symptom here: with no adapter installed its `run-variant` / `preview-variant` refuse **before any lifecycle work** — `isError true` carrying `:rf.error/no-adapter-installed` plus `:tool` and a `:recovery` naming the boot, and never a `:status` — and its renderer-free boot is `(rf/init! plain-atom/adapter)` in the namespace its launch alias preloads ([`tools/story-mcp/spec/002-Tool-Registry.md`](https://github.com/day8/re-frame2/blob/main/tools/story-mcp/spec/002-Tool-Registry.md#running-a-variant-needs-an-installed-adapter-rf2-c9t52) §Running a variant needs an installed adapter).

The reason is a capability boundary, not a preference. That host has no nREPL, socket, or JVM-to-browser bridge, so it cannot see the browser tab's Story registry at all — and the browser cannot see its. A variant id that exists in both is **two different frames with two different `app-db` values**. So a procedure that runs a variant over there and then reads the frame over here is not a slow path or a lossy one; it observes an object that was never touched, and the reads line up plausibly enough to be believed. Where a session genuinely needs the headless host, it is that session's *only* Story host — not a second one alongside this connection.

## Cross-references

- Recipes driving variants end-to-end — [`recipes.md`](recipes.md) §Drive a Story variant, §Diff two variants, §Refine a variant interactively.
- Authoring variant bodies (no runtime in the loop) — [`skills/re-frame2/references/tooling/stories.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2/references/tooling/stories.md).
- The frame primitive itself — [`spec/002-Frames.md`](https://github.com/day8/re-frame2/blob/main/spec/002-Frames.md).
- Story runtime spec — [`tools/story/spec/002-Runtime.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/002-Runtime.md); run-result contract — [`tools/story/spec/017-Testing-Story.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/017-Testing-Story.md).
