# `tools/xray/testbeds/two_frame_isolation/`

Two-frame isolation testbed (rf2-6qgbs.1; repointed under rf2-wa8my) —
**THE** canonical multi-frame isolation surface for Xray. One app,
mounted in **two** frames on **one** page (`:above` and `:below`) on the
left, with a **Xray** instance on the right.

## The shape

```
┌──────────────────────────────────┬──────────────────┐
│  Two-frame isolation             │                  │
│  ┌────────────────────────────┐  │   Xray          │
│  │ ABOVE frame  (:above)      │  │   (inline)       │
│  │ standard-epochs runner deck│  │                  │
│  └────────────────────────────┘  │   frame picker   │
│  ┌────────────────────────────┐  │   switches every │
│  │ BELOW frame  (:below)      │  │   L2 / L4 panel  │
│  │ standard-epochs runner deck│  │   between        │
│  └────────────────────────────┘  │   :above /:below │
└──────────────────────────────────┴──────────────────┘
```

Two `frame-provider` subtrees, each rooted on a separate frame id. Each
is a **fully isolated reactive context**: its own `app-db`, its own
sub-cache, its own epoch history. Handlers and subs are registered once
globally; the `reg-view`-injected `dispatch` / `subscribe` resolve via
React context to the surrounding `frame-provider`'s frame id, so the
same view source produces two independent reactive contexts.

The exercise IS observing the two frames diverge as the user interacts
with each independently. No deliberate bug, no teaching layer, no
anti-pattern demonstration — just clean feature exercise across two
isolated frames.

## The shared app code path — `standard_epochs` ×2

The app both frames mount is the **standard-epochs runner-driven deck**
(`standard-epochs.core/root`, under
`tools/xray/testbeds/standard_epochs/`). This testbed
(`two-frame-isolation.core`) supplies only the two-frame harness: it
requires `standard-epochs.core` (which installs every event / sub /
view / cofx / fx / flow / schema **once globally** at namespace load),
then mounts that namespace's `root` view **twice**, in two
frame-providers — plus the Xray host.

It does **not** call `standard-epochs.core/run` (which would mount the
deck once into `#app` on the default frame). It reuses only the
registrations + the `root` Var.

### Per-frame run-step events (rf2-3xakq → rf2-5sjbg)

`standard-epochs.core/root` is the shared step-driver runner
(`runner.core`), parameterised over `[host-frame prefix run-step-event]`.
The runner's cursor lives in each frame's **app-db `:step`** slot (NOT a
Reagent atom) — and per-frame app-db gives each mount its own `:step`, so
the two cursors are isolated by construction. The runner dispatches its
run-step event into `host-frame` **explicitly** (a runner control's
`on-click` fires outside the React frame-provider context, so an
un-targeted dispatch would land on the ambient frame). This testbed
therefore registers a **distinct run-step event per frame** and supplies
**the frame's own id + a distinct testid prefix + that event per mount**:

| frame    | host-frame | prefix                  | run-step event              |
| -------- | ---------- | ----------------------- | --------------------------- |
| `:above` | `:above`   | `standard-epochs-above` | `:two-frame/run-step-above` |
| `:below` | `:below`   | `standard-epochs-below` | `:two-frame/run-step-below` |

Two genuinely independent cursors, each driving events **only** into its
own frame. Pressing **⏭ Step** (or a numbered RUN-THIS-STEP button) in
`:above` writes only `:above`'s `:step` and moves only `:above`'s app-db /
sub-cache / epoch history — the load-bearing detail that keeps the
isolation proof intact. There is **no Reagent atom** for step state.

> **Why `standard_epochs` ×2 (rf2-wa8my)?** The earlier shape mounted a
> bespoke `testdeck.*` tabs-as-routes panel. That coupled this testbed
> to a multi-module app whose tabs-as-routes machinery doubled as a
> manual multi-frame *routing* demo. rf2-wa8my deleted the testdeck
> modules and repointed this testbed to the cleanest possible per-frame
> **isolation** proof: mount the same simple deck twice and watch the
> two reactive contexts stay independent. See *Coverage shift* below for
> where the routing surface went.

## Frame isolation — the load-bearing rule

Per `spec/006-ReactiveSubstrate.md` §The cache is held inside the frame
container: **subs are per-frame**. A sub registered globally runs
against whichever frame's `app-db` the dispatch envelope targets; it
**must not** reach into another frame's `app-db`. Every sub here reads
the current frame's `app-db` only. There is no cross-frame projection
helper, no shared root state. The cross-frame anti-pattern is
structurally impossible — the `reg-view`-injected accessors only ever
see the current frame.

See the saved Mike-feedback memories:

- `feedback_frames_are_isolated_contexts.md`
- `feedback_testbeds_are_test_surfaces.md`

## Coverage shift (rf2-wa8my)

The old two-frame testbed mounted testdeck **tabs as routes**, so it
doubled as a manual multi-frame *routing* surface. That routing surface
now has explicit, owned homes:

- **Per-frame routing** lives in `tools/xray/testbeds/routes_epochs/`
  (the single-frame ROUTING deck driving the Xray Routing panel).
- **Multi-frame isolation** is the gate assertion on the framework
  `testbeds/multi_frame/` surface (the feature-matrix gate's
  `multi-frame isolation substrate` scenario — `runMultiFrame`), which
  asserts per-frame app-db / epoch / trace isolation across the
  `:counter/a` · `:counter/b` · `:log` frames.

No **automated** multi-frame-routing assertion was dropped: the old
two-frame testbed carried no `spec.cjs` and was referenced by no gate
scenario — its tabs-as-routes was a manual dev/inspection affordance,
not a regression assertion. The closest gate routing coverage
(single-frame `routes_epochs`) plus the multi-frame isolation gate
scenario together cover what the old surface demonstrated.

## What to try in Xray

Open the page; Xray auto-mounts inline on the right. The frame picker
in the L1 ribbon switches every L2 (Events) and L4 (App-db, Views,
Trace, …) panel between observing `:above` and `:below`. Each frame's
deck is runner-driven: press **⏭ Step** to walk the ladder, or click a
numbered **RUN-THIS-STEP** button to drive a specific step directly
(`standard-epochs-above-step-<n>-run` / `standard-epochs-below-step-<n>-run`,
n = 0-based).

1. **Frame divergence on the step cursor.** Run step **1 (Increment)**
   on `:above`. Switch the Xray frame picker to `:below`: the Events list
   is empty for `:below`; the App-db diff shows no `:step` movement.

2. **Per-frame Views.** Run step **6 (Mount Child A)** on `:above`
   only. Switch the picker to `:below`: the Views lens shows no Child-A
   node and none of its sub-cache entries — A's whole reactive subtree
   lives only in `:above`'s context.

3. **Per-frame Issues.** Run step **12 (Exception in the handler)** on
   `:below`. The handler-exception surfaces scoped to `:below`; the
   `:above` frame shows no exception.

4. **Per-frame epoch history.** Drive several steps on `:above`, a
   different few on `:below`. The Epoch panel's history differs per
   frame — each frame accumulated only its own dispatches.

5. **Per-frame runner cursor.** Step `:above` three times, `:below`
   once. Each deck's status chip + highlighted row reflect its OWN
   cursor — the two runners never share state.

## Files

- `core.cljs` — the two-frame harness + mount. Requires
  `standard-epochs.core` for its registrations + `root` view, then
  mounts that view twice in two frame-providers.
- `index.html` — minimal static host with the standard
  `[data-rf-xray-host]` aside so the Xray preload auto-mounts inline.

This testbed is test-free (rf2-8cevm). Regression coverage for
multi-frame isolation lives in the substrate contract tests
(`npm run test:cljs`) and the Xray feature-matrix gate's
`multi-frame isolation substrate` scenario.

## Running

From `implementation/`:

```bash
npx shadow-cljs watch :examples/two-frame-isolation
```

Served at http://localhost:8030 via the top-level `:dev-http` map.

## Build target

`:examples/two-frame-isolation` (defined in
[`implementation/shadow-cljs.edn`](../../../../implementation/shadow-cljs.edn)).
The Xray preload (`day8.re-frame2-xray.preload`) is wired in
`:devtools/:preloads` — every dev build auto-mounts Xray inline on
load.

## Cross-references

- [`spec/006-ReactiveSubstrate.md`](../../../../spec/006-ReactiveSubstrate.md)
  §The cache is held inside the frame container — the normative
  statement of per-frame isolation. This testbed is the canonical demo.
- [`spec/002-Frames.md`](../../../../spec/002-Frames.md) — frame
  lifecycle, `reg-frame`, `:initial-events`, frame-provider context.
- The shared deck: `tools/xray/testbeds/standard_epochs/` (the same
  `root` ladder, mounted once on its own dev surface at port 8031).
