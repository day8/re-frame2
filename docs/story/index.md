# Story

**Storybook, for frames.**

Here's the thing about UI bugs: most of them aren't bugs of *logic*. They're bugs of *state combinations the developer never thought to look at*. The empty list — fine. The list with one item — fine. The list with one item where the user is impersonating an admin, the network is mid-flight, and the locale just switched to Japanese — *that's* where the bug lives, and you've never once seen the page in that exact configuration because reproducing it by hand takes eleven clicks across three modal dialogs.

The whole industry has known this for the better part of two decades. Storybook is the canonical answer: pull each visual state out into its own file, render them all on one page, and stop trying to navigate to the bug. The pattern is so well-validated at this point that arguing with it is roughly the same energy as arguing that we should go back to writing CSS in `<font>` tags. We're not going to do that. Storybook won.

So Story isn't a category invention. We're not pitching you on a new way to think about UI development; that work was done. Story is *the same idea*, plumbed into re-frame2 hard enough that the experience changes shape. You catalogue every state a component can be in — empty, loading, error, loaded, dropdown-open, disabled, impersonating, first-login-welcome — and the playground renders them all side-by-side. Forms. Dropdowns. Cards. Whatever you'd reach for Storybook to drive.

The reason for an entirely separate tool — instead of, say, an MDX bridge to upstream Storybook — comes down to one structural fact: **Storybook was designed before frames existed**. The whole Storybook architecture assumes that each story is a render fn, that state lives in component closures or context providers, and that `useState` is the cleanest available primitive for "this scenario has some local state." Storybook 9 added an interaction recorder and an Args API and a thousand other refinements on top of that foundation, but the foundation is still "render fn plus closure state."

re-frame2's foundation is different. Frames *exist*. They're a first-class allocation primitive. So in Story, each variant *runs in its own frame* — fresh `app-db`, fresh queue, fresh sub-cache, fresh interceptor chain, fresh trace bus, the works. There's no "shared mutable state between stories" footgun because there's no shared state to begin with. The variant body is plain EDN data — no JSX-shaped DSL, no inline render fns, no closures except inside decorator registrations where they belong. Assertions ride the same `dispatch` pipeline as production events. Time-travel hands you Causa scoped to the variant's epoch buffer. When you scaffold a new component, you're not reaching for a separate `.stories.tsx` file — you're declaring `reg-story` and `reg-variant` in plain Clojure against the same component you're shipping.

If you've used Storybook, the gestures will feel deeply familiar. We mean for them to. Most of what's in here is "Storybook patterns, but the substrate happens to be honest about state isolation."

![The Story shell with sidebar, canvas, and right-hand inspector visible.](../images/story/01-shell-overview.png)

*(1) The sidebar tree showing parent stories and their variants. (2) The canvas with a variant rendering. (3) The right-pane Causa embed. (4) The mode-tab strip above the canvas. (5) The controls panel — Causa's chip row carries the args-editor, trace, and machines surfaces.*

## A scenario, to fix the picture

You're building a login form. There's an empty state. A loading state. An error state when the password's wrong. An impersonating-as-admin state. A "welcome back, first time in this account" state.

The old loop: render the form, click around until you're in the state you want, screenshot, repeat. To compare empty against error you refresh the whole page. To compare error against loaded you refresh again. Five states, five refresh cycles per change. You sit there with your finger on Cmd-R like a lab rat in a behavioural-economics paper, except instead of getting a pellet at the end you get to see whether the disabled-button colour token still works.

The Story loop:

1. Open `#/stories` in your dev build.
2. Click each state in the left sidebar. The canvas swaps in 30ms.
3. Open a workspace that mounts all five side-by-side; design review against the grid.
4. Switch to *Tests* mode; every variant's `:play-script` assertions run automatically; the sidebar dots flip green.
5. Click *record* on the canvas toolbar, tap through the form once, click *stop*; out comes an EDN `:play-script` body that captures exactly what you just did. Paste it into the variant.
6. Ship.

If you've used Storybook 9 the rough shape is going to look familiar — record-and-replay is their flagship feature too. Where it diverges:

- **Variant bodies are EDN, not TypeScript.** The recorder emits EDN that pastes straight into a `reg-variant` form. MCP, visual-regression tools, and agent input pipelines all consume the same shape.
- **Controls auto-derive from the view's Malli schema.** No `argTypes` plumbing. The schema is the source of truth; the editor is the consequence.
- **Each variant gets its own frame.** State doesn't leak between scenarios. The `:empty` variant and the `:loaded` variant cannot, by construction, see each other's `app-db`.
- **Tests run inline; failures record rather than throw.** A play sequence with eight assertions where three fail still runs all eight — you get the full picture, not the first failure.

!!! tip "Run the scenario yourself"

    The five-state login flow above is a runnable testbed at [`tools/story/testbeds/login_form/`](https://github.com/day8/re-frame2/tree/main/tools/story/testbeds/login_form). The sidebar lists five variants — `/idle`, `/submitting`, `/error`, `/submitting-retry`, `/authenticated` — and a workspace that mounts all five at once. Every contract this tutorial mentions (frame-per-variant isolation, `force-fx-stub`, the four-layer args resolution chain, EDN-first variant bodies, `:rf.assert/*` shapes) is exercised on that testbed.

## The chapters

We'll walk through the surface roughly in the order you'd reach for it. First story, then chrome, then the recorder, then the things you grow into.

- [1. Your first story](01-first-story.md) — `reg-story`, `reg-variant`, schema-derived controls. The hello-world.
- [2. Mode tabs](02-mode-tabs.md) — the *Canvas / Docs / Tests* switcher, plus the viewport / background / a11y / locale toolbars.
- [3. Recorder + Test Codegen](03-recorder-codegen.md) — the hero chapter. Record an interaction, get an EDN `:play-script` body, paste it in.
- [4. Workspaces + args editor](04-workspaces.md) — multiple variants on one page; modes; live arg overrides.
- [5. Snapshot identity + QR sharing](05-snapshot-identity.md) — content-hashed snapshots that survive renames. The visual-regression integration story.
- [6. Time-travel in Story](06-time-travel.md) — Causa embedded in the RHS, scoped per variant frame.
- [7. Multi-substrate side-by-side](07-multi-substrate.md) — the same variant under Reagent, UIx, Helix. For adapter authors and component-library maintainers.

## Three load-bearing rules

Before we dive in, three contracts that nothing else in Story makes sense without:

1. **Each variant runs in its own frame.** Fresh `app-db`, fresh queue, fresh sub-cache. State doesn't leak between scenarios. What you see is what production would render against the same fixture. This sounds like a small thing; it isn't. It's the reason every other rule on this list is sustainable.

2. **Variant bodies are data — never functions.** A variant body is a map with `:events`, `:args`, `:decorators`, `:play-script`, etc. Every slot is plain EDN, round-trippable across the network. Closures live in exactly one place: inside decorator registrations, where they're a registration-site concern, not a variant-body concern. This is the lock that lets MCP, visual-regression services, and agent input pipelines all consume the same shape.

3. **Assertions record, don't throw.** A failing `:rf.assert/path-equals` doesn't blow up the variant — it appends an entry to the variant frame's assertion accumulator. The play sequence runs to completion either way; the test runner asks "did every entry pass?" at the end. You get the full picture from a broken variant, not a stack trace and a single failure.

Most of Story's distinctive properties — the test-mode reporter, the snapshot identity, the MCP write surface, the agent self-healing loop — are downstream consequences of those three rules. Internalise them now; the rest of the tutorial spends its time showing what they buy you.

## Where to install

### The one-liner — scaffold a fresh Story-enabled app

The canonical [`re-frame2-template`](https://github.com/day8/re-frame2/tree/main/tools/template) scaffolds a working Reagent app with the Story playground wired in. One invocation:

```bash
clojure -Tnew create \
        :template io.github.day8/re-frame2-template \
        :name acme/my-app \
        :include-story? true
```

The generated tree carries a `src/acme/my_app/stories.cljs` namespace with the canonical `reg-story` / `reg-variant` / `reg-workspace` shapes wired against a counter, a `core.cljs` entry that hash-routes `#/stories` to the Story shell, and the Story dep already in `:dev`. Run `cd my-app && npm install && npx shadow-cljs watch app`, open `http://localhost:8280/#/stories`, and you're in.

This is the Story-flavoured equivalent of `npx storybook init`. The `:include-story?` flag is Reagent-only at v1; UIx + Helix variants follow once Story's adapter coverage catches up.

### Adding Story to an existing app

Story lives at [`tools/story/`](https://github.com/day8/re-frame2/tree/main/tools/story) under coord `day8/re-frame2-story`. While re-frame2 is in alpha, vendor through a checkout:

```clojure
;; deps.edn — Story is a dev-shape dep; production builds DCE it.
{:aliases
 {:dev
  {:extra-deps {day8/re-frame2-story {:local/root "tools/story"}}}}}
```

In your app's entry namespace:

```clojure
(ns my-app.core
  (:require [re-frame.core :as rf]
            [re-frame.story :as story]
            [my-app.adapters.reagent :as reagent-adapter]
            [my-app.stories]))    ; loads the registrations

(defn run []
  (rf/init! reagent-adapter/adapter)
  ;; ... normal app boot ...
  (when (= "#/stories" js/window.location.hash)
    (story/mount-shell! (js/document.getElementById "app") {})))
```

Notice what's *not* in that boot snippet: there's no explicit `(story/install-canonical-vocabulary!)` call. The first `reg-*` in `my-app.stories` (loaded via the `:require` above) auto-installs the seven canonical tags, the lifecycle machine, the `:rf.assert/*` handlers, the built-in `force-fx-stub` decorator, and the v1.0 panel set. The boot ceremony is implicit. Storybook has no equivalent step; neither does Story.

Production builds — where `re-frame.story.config/enabled?` is `false` via `:closure-defines` — short-circuit at registration time, and `mount-shell!` short-circuits before any DOM call. The Story playground is a development-only artefact; it does not ship to your users.

The shell is a three-pane Reagent component: a **left sidebar** (stories tree + tag filter + workspaces), the **main pane** (selected variant's canvas or selected workspace), and a **right panel** — Causa embedded as the primary inspector, plus controls, dispatch console, and any project-custom `reg-story-panel` placements.

Ready? Start at [your first story](01-first-story.md).
