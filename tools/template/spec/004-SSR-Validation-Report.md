# Template — SSR Validation Report (rf2-0m5ea)

> **Status.** Direction-set validation. Documents the gap between the
> current deps-new template body and what a Spec 011 reference SSR
> impl-as-scaffolded-template-variant would emit. Findings here become
> the scope for the `:include-ssr?` work bead under
> [rf2-dolpf](003-DepsNew-Rebuild-Plan.md).
>
> **Date.** 2026-05-20.
>
> **Worktree.** `worker/template-ssr-validate`.
>
> **Cross-refs.**
> [Spec 011 — SSR & Hydration](../../../spec/011-SSR.md) ·
> [003-DepsNew-Rebuild-Plan §5 SSR opt-in row](003-DepsNew-Rebuild-Plan.md#5-cross-references-to-locked-v1-emit-spec) ·
> [000-Vision §Non-goals](000-Vision.md) ·
> [001-Substrate-Variants §SSR](001-Substrate-Variants.md) ·
> [`examples/reagent/ssr/`](../../../examples/reagent/ssr/) ·
> [`implementation/ssr/`](../../../implementation/ssr/) ·
> [`implementation/ssr-ring/`](../../../implementation/ssr-ring/).

## TL;DR

- Spec 011's reference impl is **fully ready**. `re-frame.ssr`
  (`day8/re-frame2-ssr`) + `re-frame.ssr.ring`
  (`day8/re-frame2-ssr-ring`) ship the entire surface — pure
  hiccup → HTML emitter, `:rf/hydrate` (auto-registered at
  ns-load), `:rf.server/*` fx family, per-request response
  accumulator, payload-policy enforcement, FNV-1a render-tree hash,
  Ring-shaped `ssr-handler`. JVM tests green
  (63 tests / 327 assertions).
- A canonical worked example — `examples/reagent/ssr/` — already
  exists. It has a `core.cljc` (shared JVM + CLJS namespace),
  a `server.clj` (live Jetty + `ssr-handler` for the
  `ssr-live.spec.cjs` Playwright smoke), a deps.edn pulling in the
  SSR + ssr-ring artefacts, and an `index.html` shaped as the
  pre-baked hydration target. This is the file shape the
  `:include-ssr?` template variant should mirror.
- The **current deps-new template emits a pure-CLJS SPA**. There is
  no `:include-ssr?` arg today — flag-coercion is wired (data-fn
  doesn't even know the keyword), no `_*/server.clj` source, no
  `.cljc` shared core, no ssr / ssr-ring coords in `_reagent/deps.edn`,
  no `:server` shadow-cljs build, no Ring deps, no Jetty alias.
- **Recommended scaffolding shape** (per `examples/reagent/ssr/` and
  `examples/reagent/realworld/ssr.cljc`):
  see [§3 Recommended template additions](#3-recommended-template-additions).
- **Recommended decision.** Add `:include-ssr?` as a **Reagent-only**
  flag in v1 (matches `:include-story?`'s shape — same Reagent-first
  rationale: the SSR worked example, the live-SSR smoke, and the
  ssr-ring test corpus are all Reagent-driven). UIx + Helix SSR
  follow once the per-substrate adapters demonstrate parity. This
  matches the bead description ("Adding `:include-ssr?` flag to
  the (deps-new) template — this is the work bead").

## 1. What works out of the box (validation passes)

### 1.1 Baseline emission

`clojure -Tnew create :template day8/re-frame2-template
:name acme/ssr-test` (via `:local/root` per Q3 stage) emits a
Reagent-only SPA — the canonical counter — with no SSR
scaffolding. The template's three locked flags (per
[003-DepsNew-Rebuild-Plan §5](003-DepsNew-Rebuild-Plan.md#5-cross-references-to-locked-v1-emit-spec))
today resolve to two: `:substrate :reagent|:uix|:helix` and
`:include-story?`. `:include-ssr?` is documented in
[`hooks.clj`](../src/day8/re_frame2_template/hooks.clj) as
"Pending flags (deferred to later stages)" and is not wired.

### 1.2 Spec 011 reference impl

`implementation/ssr/` (`day8/re-frame2-ssr`) and
`implementation/ssr-ring/` (`day8/re-frame2-ssr-ring`) carry
the full Spec 011 surface:

- `re-frame.ssr/render-to-string` — pure hiccup → HTML emitter
  with optional `:emit-hash?` for the structural FNV-1a render
  hash (Spec 011 §Hydration-mismatch detection).
- `re-frame.ssr/render-tree-hash` — canonical-EDN hash for
  non-DOM consumers.
- `:rf/hydrate` event — auto-registered at ns-load,
  `:replace-app-db` policy lock per Spec 011 §The `:rf/hydrate`
  event.
- `:rf.server/{set-status, set-header, append-header, set-cookie,
  delete-cookie, redirect}` — the managed-effect family for
  HTTP response composition.
- Per-frame response accumulator + side-channel storage
  (rf2-jbcmt) — never travels in the hydration payload.
- `re-frame.ssr.payload-policy/apply-policy` — fail-closed
  `:payload-keys` allowlist (rf2-gtgf9). `:rf.ssr.payload/whole-app-db`
  opt-in escape hatch.
- `re-frame.ssr.ring/ssr-handler` — Ring-shaped (req → resp)
  handler. The per-request pipeline (frame create → drain →
  response read → render → wrap → frame destroy) is in
  `re-frame.ssr.ring.pipeline`.
- `re-frame.ssr.ring.shell/default-html-shell` — pre-built HTML
  envelope with `<div id="app">` + `<script id="__rf_payload">`.
- `re-frame.ssr.ring.cookie/cookie->set-cookie-header` — RFC 6265
  Set-Cookie serialisation.

`implementation/ssr-ring/` `clojure -M:test` → **63 tests /
327 assertions, 0 failures, 0 errors** (this worktree, 2026-05-20).

### 1.3 Canonical worked example — `examples/reagent/ssr/`

The example is the closest existing real-world template input.
Inventory (relative to `examples/reagent/ssr/`):

```
ssr/
├── deps.edn        — :live-ssr-server alias for the Playwright smoke
├── core.cljc       — shared JVM + CLJS namespace
│                     (events, fx, subs, views, server entry,
│                      client entry, headless test fn)
├── server.clj      — live Jetty + ssr-handler composite handler
└── index.html      — pre-baked hydration target (companion to the
                      browser smoke that doesn't need a live server)
```

Key shape decisions visible in the example:

- **`.cljc` for the registration root.** Same code evaluates on the
  JVM (server render path) and on the browser (hydration path).
  `:require` of `re-frame.ssr` is JVM-side only; `:require` of
  the substrate adapter (`reagent-slim-adapter`) is CLJS-side
  only. `reg-view` macros work identically across both.
- **Per-request frame discipline.** `make-frame` per request,
  `:on-create [:rf/server-init]`, drain to fixed point, render via
  `(rf/view :app/root)` + `render-to-string`, destroy in `finally`.
- **`:fx-overrides` swap for HTTP.** `:rf.http/managed` is
  redirected per-frame to a canned-success stub for the demo;
  the override key is registered-fx-id → registered-fx-id, never
  a raw fn (Spec 011 §The override seam is id-based).
- **Server entry (`server.clj`).** Boots `ssr/adapter`, requires
  `ssr.core`, registers the canned-articles fx, wraps
  `ssr-handler` with a static-asset handler for `/main.js` so the
  browser can hydrate same-origin. Runs Jetty on 127.0.0.1
  ephemeral.
- **Headless SSR test (`ssr-tests` in `core.cljc`).** Runs
  end-to-end on the JVM with no React/JSDOM — boots `ssr/adapter`,
  reg-frames with the `:fx-overrides` redirect, calls
  `render-to-string`, asserts HTML content + `data-rf-render-hash`
  marker. This is the gate shape a template-emitted SSR scaffold
  should ship.

### 1.4 Multi-route / realworld shape

[`examples/reagent/realworld/ssr.cljc`](../../../examples/reagent/realworld/ssr.cljc)
is the next step beyond a single-route demo: a hand-curated
`ssr-slice-keys` allowlist projecting `app-db` to the payload, a
`hydration-payload` builder, a `read-server-payload` /
`hydrate-client!` pair on the CLJS side. The template's SSR
scaffold doesn't need this complexity in v1 (the locked sample is
the counter — Q4 of the template walkthrough), but it documents
the shape the scaffolded app's `<namespace>.ssr` namespace should
take once users grow beyond the starter.

## 2. Gap analysis — what's missing from the current template

Verified by emitting a fresh `acme/ssr-test` scaffold against the
local `:local/root` template and walking the tree. Comparing to
`examples/reagent/ssr/`'s file inventory:

| Slot | Current template | Required for `:include-ssr? true` | Source under `_reagent/` |
|---|---|---|---|
| Flag wiring in `hooks.clj` | NOT present (data-fn ignores `:include-ssr?`) | Add `coerce-include-ssr?` + `:include-ssr?` branches in `template-fn`. Reagent-only guard, matching `:include-story?`. | — |
| `_reagent/core.cljs` | Pure-CLJS counter boot | Default path unchanged. Under `:include-ssr? true`, swap to `core_with_ssr.cljc` (shared JVM + CLJS, mirrors `examples/reagent/ssr/core.cljc`). | New: `core_with_ssr.cljc` |
| Server entry namespace | Absent | New file `server.clj` mirroring `examples/reagent/ssr/server.clj` (composite handler: static `/main.js` + favicon 204 + `ssr-handler`). | New: `server.clj` |
| `_reagent/deps.edn` | core + adapter + schemas + causa + reagent | Under `:include-ssr? true`, swap to `deps_with_ssr.edn` adding `day8/re-frame2-ssr`, `day8/re-frame2-ssr-ring`, `ring/ring-jetty-adapter` (or `info.sunng/ring-jetty9-adapter` per Ring 2.x), and a `:server` alias with `:exec-fn <ns>.server/-main`. Same pattern `:include-story?` uses with `deps_with_story.edn`. | New: `deps_with_ssr.edn` |
| `_reagent/shadow-cljs.edn` | Single `:app` browser build | Under `:include-ssr? true`, swap to `shadow-cljs_with_ssr.edn` that retains `:app` and points `:output-dir` somewhere `server.clj`'s `:static-root` can locate. (The example's `server.clj` already accepts `:static-root` as an arg — no shadow-side change strictly required, but the README/quick-start needs to reference both watchers.) | New: `shadow-cljs_with_ssr.edn` (optional; current shape may suffice with documentation only) |
| `root/resources/public/index.html` | Static HTML envelope, `<div id="app">` empty | Under `:include-ssr? true`, the live server emits its own envelope at runtime — the static `index.html` becomes optional / dev-fallback. Either: (a) leave as-is and let `server.clj` shadow it; or (b) emit a `dev/index.html` variant explaining the role. Recommended: leave as-is. | — |
| `_reagent/package.json` | reagent + react/react-dom + shadow | Under `:include-ssr? true`, no change (JVM-side handles SSR; no node-side render dep). | — |
| README quick-start | Single `npx shadow-cljs watch app` flow | Under `:include-ssr? true`, README quick-start branches: (1) `clojure -X:server` to run the SSR server, (2) `npx shadow-cljs watch app` to build the client bundle. Hot-reload of the JVM side is `(require '<ns>.server :reload)` from a REPL. | Either two README variants or one README with a conditional SSR section (deps-new lacks Mustache; needs separate `README_with_ssr.md`) |
| `_shared/events.cljs` | Counter increment event | Under `:include-ssr? true`, fold `:rf/server-init` in (the bead spec calls for "boot the server" verifying counter still works post-hydration). Recommend keeping events.cljs substrate-shared and adding `_shared/events_with_ssr.cljs` (variant pattern same as the `_with_story` files). | New: `events_with_ssr.cljs` |
| Tests | `events_test.cljs` only | Under `:include-ssr? true`, add a headless `ssr_test.clj` mirroring the `ssr-tests` fn in `examples/reagent/ssr/core.cljc`. Same gate the worked example uses — boots `ssr/adapter`, renders the root view, asserts HTML content + render-hash marker. | New: `_shared/ssr_test.clj` |

### 2.1 Decision: separate-file variants vs. conditional inclusion

The template's deps-new substitution engine is **flat string
replacement only** — no Mustache conditionals (per
[`hooks.clj` Substitution engine note](../src/day8/re_frame2_template/hooks.clj#L20-L34)).
`:include-story?` already handles this with **separate source
files** (`deps_with_story.edn` vs. `deps.edn`,
`core_with_stories.cljs` vs. `core.cljs`) — `template-fn`'s
file-map picks the right source per the flag.

`:include-ssr?` adopts the same shape:

```
_reagent/
├── core.cljs                       — default Reagent boot
├── core_with_stories.cljs          — adds story-playground boot
├── core_with_ssr.cljc              — NEW: shared JVM + CLJS boot
│                                      (cannot share an output name
│                                      with core.cljs — the .cljc
│                                      vs .cljs extension matters
│                                      for shadow-cljs and deps.edn
│                                      classpath. Target dir is
│                                      `src/<nested>/core.cljc`,
│                                      file-map entry handles the
│                                      extension swap.)
├── deps.edn                        — default
├── deps_with_story.edn             — adds Story coord
├── deps_with_ssr.edn               — NEW: adds ssr / ssr-ring / Jetty
├── server.clj                      — NEW: emitted only under :include-ssr? true
└── shadow-cljs.edn                 — unchanged (or _with_ssr variant
                                      if the build inventory differs)
```

`template-fn`'s per-substrate Reagent branch grows a second
`include-ssr?` switch alongside the existing `include-story?`
switch — the matrix is 2 × 2 (story × ssr), which the
file-naming convention `core_with_X_and_Y.<ext>` would blow up
combinatorially. **Recommended v1 lock**: `:include-ssr?` and
`:include-story?` are **mutually exclusive** in v1 (throw with a
clear message if both are passed). Two flags × Reagent = 3 valid
combinations (neither, story-only, ssr-only); UIx + Helix
disallow both. This matches the spirit of the locked-three-flags
rule — every additive combination requires a separate template
test, and the v1 surface stays comprehensible.

Future expansion: if user demand surfaces, lift the
mutual-exclusion to a flag-combination matrix tested per cell.
Not v1.

## 3. Recommended template additions

Three follow-on beads under [rf2-dolpf](003-DepsNew-Rebuild-Plan.md) §2.x:

### 3.1 Bead A — `:include-ssr?` flag wiring (Reagent-only)

- Add `coerce-include-ssr?` to `hooks.clj` (mirrors
  `coerce-include-story?`).
- Update `data-fn` to:
  - validate `(not (and include-ssr? include-story?))` — throw
    `:rf.error/ssr-and-story-mutually-exclusive` with a clear
    message;
  - validate `:include-ssr? true` ⇒ `:substrate :reagent` — same
    guard shape as `:include-story?`'s Reagent-only branch;
  - thread `:include-ssr?` into the data map.
- Update `template-fn`'s Reagent branch to pick `_with_ssr` source
  files when the flag is on.
- Update `post-process-fn`'s logged next-steps to add the
  `clojure -X:server` line when `:include-ssr?` is true.
- README quick-start update (split README or `_with_ssr` variant —
  see §2.1).

Scope: ~ Reagent only. UIx + Helix throw with a clear message
("`:include-ssr?` is Reagent-only in v1; UIx + Helix variants
follow once the SSR worked-example coverage matches Reagent's.")

### 3.2 Bead B — emit the SSR file body under `_reagent/`

- `_reagent/core_with_ssr.cljc` (port from `examples/reagent/ssr/core.cljc`,
  rebrand counter — the locked sample). Same shape:
  `:rf/server-init`, `:rf/hydrate` (re-registered for documentary
  value or relying on the auto-registered default), `reg-view`
  for the root view, server entry, client entry, headless test fn.
- `_reagent/server.clj` (port from `examples/reagent/ssr/server.clj`).
  Composite handler with static `/main.js` + favicon + `ssr-handler`.
  Read `:static-root` from `:exec-args`. Boot adapter, require
  app namespace, run Jetty 127.0.0.1.
- `_reagent/deps_with_ssr.edn` adds:
  - `day8/re-frame2-ssr {:mvn/version "{{rf2-version}}"}`
  - `day8/re-frame2-ssr-ring {:mvn/version "{{rf2-version}}"}`
  - `ring/ring-jetty-adapter {:mvn/version "1.12.1"}` (or Ring 2.x
    equivalent; pin lockstep with `implementation/ssr-ring/deps.edn`'s
    test dep).
  - `:server` alias: `:exec-fn {{namespace}}.server/-main` with
    `:exec-args {:port 8030 :static-root "resources/public/js"}`.
- `_shared/ssr_test.clj` — headless JVM test mirroring the
  `ssr-tests` fn in the worked example. Renders the root view via
  `render-to-string`, asserts the rendered HTML contains the
  expected content, asserts the `data-rf-render-hash` marker is
  present and matches `[0-9a-f]{8}`. Runs via the existing
  `:test` alias on the emitted app (no harness change needed —
  the existing emitted-test-run test will pick it up).

### 3.3 Bead C — template-test coverage for the new variant

Mirror the existing `:include-story?` test pattern in
`template_test.clj`:

- Generate `acme/ssr-app :substrate :reagent :include-ssr? true`.
- Assert the file inventory:
  - `src/acme/ssr_app/core.cljc` present, NOT `core.cljs`;
  - `src/acme/ssr_app/server.clj` present;
  - generated `deps.edn` reads as EDN, has the three SSR coords
    + `:server` alias;
  - generated `test/acme/ssr_app/ssr_test.clj` present.
- Negative tests:
  - `:substrate :uix :include-ssr? true` throws.
  - `:substrate :helix :include-ssr? true` throws.
  - `:include-story? true :include-ssr? true :substrate :reagent`
    throws (mutual-exclusion).

The `emitted_test_run_test.clj` runner (which already runs
`clojure -M:test` against the emitted app for each substrate)
should pick up the new `ssr_test.clj` automatically — verify
end-to-end by running it against an `:include-ssr? true` emission.

## 4. Spec-side gaps surfaced (Spec 011)

None. The reference impl is complete and tested. The walk through
`examples/reagent/ssr/core.cljc` + `server.clj` +
`implementation/ssr-ring/` flushed up zero ambiguity that would
need a Spec 011 amendment — the contract is locked, the impl
matches, the worked example demonstrates the canonical shape.

(The bead's caveat — "Q7 locked pending validation" — clears.
SSR support is implementable as a template variant today.)

## 5. Validation evidence

| Check | Result |
|---|---|
| `tools/template/` `clojure -M:test` (baseline) | 19 tests / 318 assertions, 0 failures, 0 errors |
| `implementation/ssr-ring/` `clojure -M:test` | 63 tests / 327 assertions, 0 failures, 0 errors |
| Template emission `acme/ssr-test :substrate :reagent` | succeeded; emitted 18-file pure-CLJS SPA (no SSR) |
| Manual file-shape walk of emitted app | `src/acme/ssr_test/{core,events,subs,views,schema}.cljs` + `test/acme/ssr_test/events_test.cljs`; no `core.cljc`, no `server.clj`, no SSR coord, no Ring dep — matches §2 gap analysis |
| `examples/reagent/ssr/` file inventory | `deps.edn` + `core.cljc` + `server.clj` + `index.html` — the recommended `:include-ssr? true` shape |
| `implementation/ssr/src/re_frame/ssr.cljc` `:rf/hydrate` auto-registration | confirmed at L146 (`events/reg-event-fx :rf/hydrate hydrate/hydrate-event-handler`) — scaffolded apps need only `:require` `re-frame.ssr` |

## 6. Recommended next actions

1. File three follow-on beads under [rf2-dolpf](003-DepsNew-Rebuild-Plan.md) §2.x
   for the three sub-tasks in §3 above (flag wiring, file body, test
   coverage). Each is single-worker scope (~30-60 minutes).
2. Update [003-DepsNew-Rebuild-Plan.md §5](003-DepsNew-Rebuild-Plan.md#5-cross-references-to-locked-v1-emit-spec)
   table's SSR row from "gated on rf2-0m5ea" to the new work-bead id.
3. Update [001-Substrate-Variants.md L114-115](001-Substrate-Variants.md)
   — drop the "lands as a separate template variant if/when SSR
   matures" deferral; replace with the locked Reagent-only
   `:include-ssr?` flag language.
4. Update [000-Vision.md](000-Vision.md) §Non-goals — flip the SSR
   line from "SSR scaffolding (Spec 011) lands as a separate
   template variant" deferral to a tick under §Goals once §3
   beads land.
5. Close rf2-0m5ea once the three §3 beads are filed (this report
   is the deliverable).

## 7. Out of scope (deferred)

- **UIx + Helix SSR variants.** The Spec 011 contract is
  substrate-agnostic — `render-to-string` consumes hiccup, which
  any substrate can emit — but the worked example and the
  ssr-ring test corpus are Reagent-driven. Follow-on beads once
  UIx/Helix SSR adapters demonstrate parity.
- **Streaming SSR (`re-frame.ssr.streaming`).** Already in the
  impl (Spec 011 §Streaming SSR), but the canonical scaffold is
  single-shot. Optional second flag `:include-ssr-streaming?` is
  out of v1 scope.
- **Production-grade host beyond Jetty.** The scaffold mirrors
  the worked example: Jetty for dev/test simplicity. HttpKit,
  Pedestal, Reitit-ring all consume Ring-shaped handlers and
  work without scaffold changes — README documents the swap.
- **Skill install for SSR-specific workflow.** No SSR-specific
  skill exists yet; `re-frame-pair` covers the runtime side.
  Out of scope.
