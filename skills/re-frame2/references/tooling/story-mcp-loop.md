# Story-MCP — which host, authoring, and three recipes

> **What this leaf owns.** The story-mcp half of Story agent work: no browser in the loop, the story-mcp server over stdio, and the tools THIS skill (`re-frame2`) is allowed to call — write a variant body, preview it, explain it, read it back, refine it. The other half, a human's live workshop in the loop, belongs to the `re-frame2-pair` skill, whose session calls the `re-frame.story/*` functions through `eval-cljs` in the browser rather than any story-mcp tool. §Which host to use is the rule that picks between the two before you start; §Three recipes are promotion, fidelity upgrade and explain as they run here.

> **Mental model: think in Storybook, map onto Story.** When authoring a variant, sketch it as a Storybook story first (which args, which play steps?), then translate to the EDN `reg-variant` body — see `stories.md` §Mental model for the full concept map. Story's distinctive twist over a Storybook play function: `:rf.assert/*` events *record* (they don't throw), so a single run returns **every** mismatch at once.

## When to load

- An agent (Claude Code, Cursor, Copilot) is **writing or refining** variant bodies against a re-frame2 codebase and wants the story-mcp authoring surface (register / preview / read-back / explain).
- You need to pick a host before you start — story-mcp on the JVM, or the browser through a `re-frame2-pair` session (§Which host to use).
- You want to promote a failing run to a regression variant, upgrade a pinned state's fidelity, or explain a variant before running it (§Three recipes).

Do **not** load this leaf to learn how to author a variant body's *contents* — see `stories.md`. Load it for: the host rule, the authoring tools, the three recipes, and the handoff.

## Which host to use

Apply this before the first tool call. The same four lines sit in the `re-frame2-pair` skill's [`references/stories.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/references/stories.md#which-host-to-use) and in [`tools/story-mcp/README.md`](https://github.com/day8/re-frame2/blob/main/tools/story-mcp/README.md#which-host-to-use).

1. **No browser in the loop → story-mcp over stdio.** The `re-frame2` skill owns this half: registry reads, `explain-variant`, `preview-variant` (it runs the variant headlessly and returns the unified run-result) and the gated `register-variant`. A tool that needs a rendered substrate or a live a11y engine answers `:rf.error/story-mcp-capability-unavailable`; that is the verdict, not a failure.
2. **A human's live workshop in the loop → re-frame2-pair.** The `re-frame2-pair` skill owns this half: `eval-cljs` into the browser's Story registry over `re-frame.story/*`, drive the live variant frame with the ordinary Pair tools, and read the a11y panel, which reports violations and incomplete checks as two counts.
3. **Never both for one edit.** A variant id registered in both hosts names two frames with two app-dbs, so a read in one host describes nothing the other ran.
4. **When in doubt, start on the JVM**, and move to the browser only when a tool answers capability-unavailable.

Inside the first half, this skill's JVM run is `preview-variant`: the four Testing-category tools are allow-listed by no skill (§Authoring tools this skill can call). Inside the second, the run loop against the app a human has open is the handoff at the end of §Worked authoring pass — then the handoff.

## Authoring tools this skill can call — by step

Per `tools/story-mcp/spec/002-Tool-Registry.md`, the story-mcp catalogue is nineteen tools across four categories. The authoring subset `re-frame2` is allow-listed for:

| Step | Tool | Category | What it does |
|---|---|---|---|
| Write | `register-variant` | Write (gated) | `re-frame.story/reg-variant*` with the agent's body |
| Write | `unregister-variant` | Write (gated) | symmetric tear-down between iterations |
| Preview | `preview-variant` | Dev | render one variant; returns the unified run-result PLUS the share URL + rendered view ("show me what this looks like"). **Needs an installed adapter** — it allocates a variant frame; see §Gates and prerequisites |
| Read | `get-variant` | Docs | full variant body as canonical EDN, for the agent to read before editing |
| Read | `explain-variant` | Docs | "why did the plan resolve this way" — the variant-plan `:explain` (source chain, merge, runner requirements); the agent's mirror of the human Explain panel |
| Onboard | `get-story-instructions` | Dev | the EDN-first constraint, canonical body keys, the seven `:rf.assert/*` events, the four-phase lifecycle, the inclusion-tag vocabulary — one self-contained string. Call once per session, before authoring. |
| Enumerate | `list-stories` / `get-story` / `variant->edn` / `list-tags` / `list-modes` / `list-decorators` / `list-assertions` / `list-substrates`† / `get-docs-markdown` | Docs / Dev | navigate an unfamiliar Story registry (and read its docs) while authoring |

† `list-substrates` is the one enumeration that is **browser-only**, so it is not a plain registry read. Substrate registration is CLJS-only (`re-frame.story/register-substrate!`) and the JVM stdio server has no bridge to that registry, so there it returns `isError true` with `:rf.error/story-mcp-capability-unavailable` — never an empty list. The distinction is load-bearing: an empty `:substrates` vec is reserved for a REACHED registry that genuinely holds nothing, so EMPTY means nothing is registered and UNAVAILABLE means the host could not look. Read it from a browser-local Story host. (`tools/story-mcp/spec/002-Tool-Registry.md` §Host execution model; `read-a11y-violations` on the run side behaves the same way.)

What this subset is **missing** (and why): `run-variant`, `read-failures`, `snapshot-identity`, and `read-a11y-violations` are the four **Testing**-category run tools, and no skill allow-lists them — not this one, and not `re-frame2-pair`. They drive story-mcp's own headless host in its JVM, which has no bridge to a browser tab's Story registry, so an operator reaches them only by launching story-mcp directly for an explicitly headless run. The drift gate (`scripts/check_skill_mcp_drift.py`) records this: its only story-mcp mapping is the one for `re-frame2`, which marks those four `intentional_server_only`, and its single-host rule refuses any story-mcp entry in `re-frame2-pair`'s allow-list. The run loop itself still belongs to a `re-frame2-pair` session, which calls the `re-frame.story/*` functions through `eval-cljs` (§Worked authoring pass — then the handoff).

`preview-variant`, `run-variant`, and `read-failures` all speak the SAME unified run-result the human Story UI reads (spec/017 §Run result) — there is no agent-only result vocabulary. The headline is the top-level `:status` ∈ `{:pass :fail :cannot-run :error}`. On this host that `:status` arrives through `preview-variant`; in the browser a `re-frame2-pair` session reads it from `re-frame.story/run-variant`.

## Worked authoring pass — then the handoff

The agent has been asked to add a "user clicks delete then confirms" variant for `:story.todos/list-with-items`. Authoring side (this skill) — the `preview-variant` step below assumes the host has a re-frame adapter installed; on a bare launch it refuses rather than returning a `:status` (§Gates and prerequisites):

```
agent → register-variant
  {:variant-id :story.todos/delete-confirmed
   :body {:extends :story.todos/list-with-items
          :script [[:dispatch-sync [:todo/delete-pressed 3]]
                   [:dispatch-sync [:todo/confirm-pressed]]
                   [:dispatch-sync [:rf.assert/path-equals [:todos :items] [{:id 1} {:id 2}]]]]}}

agent → preview-variant {:variant-id :story.todos/delete-confirmed}
  ← {:status :pass :share-url "..." :effective-args {...} :app-db {...} ...}
```

`preview-variant` confirms the body parses, the parent `:extends` resolves, the script mounts, and the resolved args and post-run state look right (it shares `run-variant`'s headless lifecycle, so it returns no rendered output — the share URL is how a human sees the canvas). If the preview shows the wrong state or `explain-variant` reveals a bad merge/runner, the agent refines the body and re-registers — still entirely on the authoring side.

When the developer wants the loop to run against the app they have open — a human's live workshop in the loop, where `re-frame.story/run-variant` settles `:status :fail` carrying every `:rf.assert/*` record in its `:assertions`, and the agent iterates to `:status :pass` against the running library — hand off to a `re-frame2-pair` session:

> "The variant body is registered and previews correctly. To run the assertions and iterate against the live runtime, switch to the **re-frame2-pair** skill (it calls `re-frame.story/run-variant` through `eval-cljs` against the running app behind `shadow-cljs watch`) and run the self-healing loop there — it'll see every `:rf.assert/*` mismatch in one pass because the assertions record rather than throw."

The agent reports the registered body + preview result back, and names the run-side surface for the next step. It does **not** pretend to call `run-variant` from here.

## Gates and prerequisites (authoring side)

- **Write surface is gated.** `register-variant` / `unregister-variant` require `re-frame.story-mcp.config/allow-writes?` truthy — set via `--allow-writes` flag, `RF_STORY_MCP_ALLOW_WRITES=true` env, or `-Drf.story-mcp.allow-writes=true` JVM property. Without it, authoring is read-only: the enumerations still work against existing variants, and so does `preview-variant` — but only once an adapter is installed (next bullet). The write gate and the adapter prerequisite are independent; clearing one does not clear the other.
- **Previewing needs an installed adapter.** Catalogue reads work on a bare launch. *Running* one does not: `preview-variant` allocates a variant frame, and a frame takes its state substrate from an installed re-frame adapter. The server deliberately installs none — per `spec/006-ReactiveSubstrate.md` the substrate choice belongs to the app — so a consuming project that wants headless previews installs one in the namespace its launch alias preloads, `(rf/init! plain-atom/adapter)` being the renderer-free choice. With none installed `preview-variant` **refuses before any lifecycle work**: `isError true` with `:rf.error/no-adapter-installed` (core's own id for this condition), plus `:tool` and a `:recovery` naming the boot. It never returns a `:status`, so this is not a `:cannot-run` verdict to interpret — it is a host prerequisite to satisfy. The refusal exists because the alternative is a success-shaped NON-RUN: with no substrate the setup dispatches reach nothing and the script plays nothing, yet the run settles the ordinary `:status :pass` envelope over `{}` and `[]`, indistinguishable on the wire from a genuine green. (`tools/story-mcp/spec/002-Tool-Registry.md` §Running a variant needs an installed adapter; `run-variant` on the run side carries the same prerequisite.)
- **`preview-variant` is deadline-bounded.** It accepts a tunable `:timeout-ms` — default 10 s, hard ceiling 30 s, caller values above the ceiling clamp DOWN rather than reject (the MCP stdio loop is single-threaded, so an unbounded lifecycle call would park unrelated calls). An over-budget run is cancelled and returns the canonical `:status :error` run-result, never a false `:pass`.
- **`register-variant` neither requires nor synthesises a parent story.** There is no `register-story` tool, and a variant registers and runs whether or not its `:story.<path>` parent is registered — without one it inherits nothing from a parent (no `:component`, `:args` or decorators), and its `:doc` stays variant metadata. The user normally lands the parent inline, with `reg-story` in a namespace the server's launch alias loads; the agent registers variants under it.
- **Source-coord stamping survives MCP registration.** `register-variant` stamps `{:file <agent-supplied> :line <n>}` if provided in the body; without it, `:source` is omitted and downstream failure records carry no jump-to-line affordance. Agents that want clickable failures (on the run side) supply `:source` from the file they'll write the variant back into.

## Three recipes

Each recipe names its forms on this host; the browser forms of the same three are in the `re-frame2-pair` skill's [`references/stories.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-pair/references/stories.md#three-recipes-in-the-browser).

### Promote a failing run

A failing run becomes a regression variant only when the new variant keeps the reason the run failed. There are three routes.

- **From the API** — either host; here on the JVM. Compile the source's plan with the run's argument layers, coerce it to a run artifact, and promote it under a new id:

  ```clojure
  (require '[re-frame.story :as story]
           '[re-frame.story.args :as args]
           '[re-frame.story.determinism :as determinism])
  (story/promote-run-artifact!
    (determinism/->artifact
      (story/variant-plan :story.cart/checkout-fails
                          {:run-args (args/run-arg-layers :story.cart/checkout-fails)}))
    {:variant/id :story.cart/checkout-regression})
  ```

  `:run-args` folds in the layers a run applies around the variant: global args and the parent story's `:args`. Without it the plan sees the variant's own `:args` alone, so a script that substitutes a story-level `[:arg …]` throws `:rf.error/story-missing-arg`. If the failing run passed `:active-modes` or `:cell-overrides`, pass that same opts map as `run-arg-layers`' second argument.

  The registered body carries the source's whole program (setup and script, `[:assert …]` checkpoints included), the source's own `:assertions`, and the check ids its verdict depends on as the compiler resolves them: composed checks too, and inherited ones when the body does not `:extends` the source, each id once.
- **From Test mode** — the browser, where a human or a `re-frame2-pair` session drives it. The Test-mode promote dialog captures the run's dispatches, carries the registered source's `:assertions` and the checks its verdict depends on (composed ones included), and replaces a dispatch-only capture with the source's full program. A variant whose `:script` has no dispatch step (every login-form testbed variant) promotes from Test mode too: the dialog captures that variant's own stepped program.
- **Over MCP, with this skill's tools.** `preview-variant` the source and note its assertion count, `get-variant` it, then `register-variant` a new id whose body `:extends` the source and restates the source's `:script` and `:assertions`. Neither of those two inherits through `:extends` (`:setup` and `:checks` do), so a body that only `:extends` the source runs `:status :pass` with no assertions at all. A `register-variant` registration lives as long as the server process, and fixing the app means relaunching it, so land the promoted body (`variant->edn` prints it) in a namespace the launch alias loads before the next two checks.

Whichever route, apply one acceptance, in order:

1. With the fault in place, the promoted variant **fails**, with the same assertion count as the source.
2. With the app fixed, it **passes**.
3. With the fault restored, it **fails** again.

Only that sequence proves a repair. An agent that edits the expected value instead of the app has repaired nothing: its variant passes under the fault and fails once the app is fixed.

### Upgrade a pinned state's fidelity

A variant that pins subscription values with `:sub-overrides` shows a picture, not evidence. `upgrade-snippet` writes the copy-paste form that keeps it a `reg-variant` and trades the pin for real setup. The JVM one-liner, from a project whose classpath carries Story and your story namespaces:

```bash
clojure -M -e "(require 'app.stories 're-frame.story.ui.view-state) (println (re-frame.story.ui.view-state/upgrade-snippet :story.cart/pinned :real-setup))"
```

It prints ONE `(story/reg-variant :story.cart/pinned-upgraded { … })` form with a `:setup` placeholder to fill (`:db-seed` is the other target rung). It drops the pin by walking the source's `:extends` chain: it extends the nearest ancestor above the first pinning layer and restates the source's own slots without `:sub-overrides`. Fill the placeholder, land the form, then check the compiled child: `(:fidelity (story/explain :story.cart/pinned-upgraded))` — `explain-variant`'s `:fidelity` over MCP — must not contain `:sub-overrides` unless you kept a pin on purpose. Do not skip that check. A composed fragment (`:compose`) that pins is dropped from the form and named, with the queries it pinned, in a trailing `;;` comment; its `:args` and `:setup` are not inlined, so re-add whatever you still need from it by hand. Fragments that pin nothing stay.

### Explain before running

Read the resolved plan before you edit a declaration. `(story/explain id)` — `explain-variant` over MCP — reports `:args` and `:effective-args` with the ambient layers folded in (global args, the parent story's `:args`, and any `:active-modes` / `:cell-overrides` in its opts), which are the values a run uses. The pure compiler `re-frame.story.plan/explain` folds the ambient layers only when handed `:run-args`, so called bare it shows the variant's own layer alone, by design. A story-level `:args` missing from the pure form is not missing from the run.

## Common gotchas

- **The live run loop is not this skill's to drive.** Against the app a human has open it belongs to a `re-frame2-pair` session, which calls `re-frame.story/run-variant` through `eval-cljs`; the story-mcp `run-variant` / `read-failures` tools are allow-listed by no skill. If the task is "run it against the running app and fix the failures," that is the handoff — don't infer access to tools the skill isn't allow-listed for.
- **`:rf.assert/*` events record, they do not throw.** A failing assertion does not abort the script — every run reads the full failure list. Assertion events ride the `:dispatch-sync` rail in `:script` (the public phase-4 play surface — spec/017 §Public vocabulary; the retired `:play-script` spelling is rejected at registration).
- **`:status :pass` is the loop terminator, on either host.** The top-level `:status` ∈ `{:pass :fail :cannot-run :error}` is the unified verdict (spec/017 §Run result). Distinguish `:fail` (an assertion mismatched — refine the variant) from `:cannot-run` (the runner could not attempt the plan, e.g. a causal assertion under a non-reactive runner — change the runner, refining won't help) from `:error` (a handler / fx / step threw). On this host the verdict arrives through `preview-variant`.
- **`explain-variant` is the authoring-side read for surprises.** When a preview renders unexpectedly or a plan merges/composes oddly, `explain-variant` shows the resolved plan — source/parent chain, composed fragments/checks, strict-conflict winners, the selected runner + what it required — the agent's mirror of the human Explain panel.

## Deeper material

- Full tool registry + per-tool I/O schemas → `tools/story-mcp/spec/002-Tool-Registry.md` and `tools/story-mcp/spec/API.md`.
- Wire protocol (JSON-RPC over stdio, `initialize` handshake) → `tools/story-mcp/spec/001-Wire-Protocol.md`.
- Write-surface gating → `tools/story-mcp/spec/003-Write-Surface-Gating.md`.
- Promotion, the run artifact and the determinism gate → `tools/story/spec/017-Testing-Story.md` §Promotion.
- The **run-side** loop against a live app → the `re-frame2-pair` skill, which drives variants in the attached browser heap through `eval-cljs` over the `re-frame.story/*` functions.
- Recorder integration → `story-recorder.md` (sibling leaf — interactive canvas recording is performed through Pair in the attached CLJS runtime).
- Variant body shape, `:rf.assert/*` vocabulary → `stories.md` (sibling leaf).

---

*Derived from `tools/story-mcp/spec/` and `tools/story/spec/017-Testing-Story.md` @ main. Re-verify after MCP tool-registry changes, write-surface gating updates, promotion or fidelity-upgrade changes, or any change to which story-mcp tools a skill allow-lists (`scripts/check_skill_mcp_drift.py`).*
