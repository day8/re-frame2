# Story

**A workshop for application behaviour — one variant, one plan, six faces.**

Here's the thing about UI bugs: most of them aren't bugs of *logic*. They're bugs of *state combinations the developer never thought to look at*. The empty list — fine. The list with one item — fine. The list with one item where the user is impersonating an admin, the network is mid-flight, and the locale just switched to Japanese — *that's* where the bug lives, and you've never once seen the page in that exact configuration because reproducing it by hand takes eleven clicks across three modal dialogs.

The whole industry has known this for the better part of two decades. This tutorial is about the tool re-frame2 ships to do something about it, and about a thesis that's a little bigger than "look at your states side-by-side." So before the chapter list, let me tell you what Story actually is, why it isn't just a Storybook clone, and the three rules that everything else falls out of.

## The one-sentence pitch — Storybook, for frames

Story is not a category invention. Pull each visual state out into its own declaration, render them all on one page, and stop trying to *navigate* to the bug — that pattern was won, decisively, by [Storybook](https://storybook.js.org/), the dominant tool in the JavaScript ecosystem. Arguing with it at this point is roughly the same energy as arguing that we should go back to writing CSS in `<font>` tags. We're not going to do that. Storybook won.

So if you've used Storybook, you already know the gestures, and you should expect them to feel familiar: a sidebar of stories, a canvas in the middle, controls down one side, a grid view for matrices. We mean for them to feel familiar. The pitch is "Storybook, for frames" — the same idea, plumbed into re-frame2 hard enough that the experience changes shape. Where Story diverges from Storybook, I'll flag it explicitly, because the divergences are the interesting part.

## The thing that's actually different — one artifact, many faces

Here is the thesis, and it's worth slowing down for. When you select a variant in Story, you are selecting **one artifact**. That artifact then gets lowered through **one normalized plan**, and that single plan is what drives every face of the tool:

- **canvas** — the rendered state in the middle of the screen;
- **doc** — the same state, presented as executable documentation;
- **test** — the same state, run as an assertion-bearing test;
- **replay** — the same run, scrubbed beat-by-beat through its causal history;
- **share** — the same state, exported (honestly redacted) for another human;
- **agent** — the same state, driven by an AI over MCP.

One selected artifact, lowered through one plan/result/evidence path, wearing six faces. That's the headline. The promise to *you* is not "learn our substrate." It's six concrete things: open a workshop fast, make states cheaply, *know how trustworthy each state is*, turn examples into tests and docs, follow a failure to its cause, and hand the same state to another human or another agent without re-encoding it.

Hold onto the six-faces picture. The chapters earn each face one at a time, and the last chapter walks back through all six and points at where each was won.

<!-- SCREENSHOT S0: the Story shell — sidebar tree, canvas with a variant rendering, right-hand inspector, mode-tab strip. The "shell overview" establishing shot. -->

## Why a separate tool, and not an MDX bridge to upstream Storybook

The honest question is: why build a whole separate tool instead of writing an adapter that teaches the real Storybook to render re-frame2 components? The answer is one structural fact: **Storybook was designed before frames existed.**

The entire Storybook architecture assumes a story is a render function, that scenario state lives in component closures or context providers, and that `useState` is the cleanest available primitive for "this scenario has some local state." Storybook 9 added an interaction recorder and an Args API and a thousand other refinements on top of that foundation — but the foundation is still "render fn plus closure state."

re-frame2's foundation is different. Frames *exist* — they're a first-class allocation primitive. So in Story, **each variant runs in its own frame**: fresh `app-db`, fresh event queue, fresh subscription cache, fresh interceptor chain, fresh trace bus, the works. There is no "shared mutable state between stories" footgun because there is no shared state to begin with.

We'll meet two examples throughout. The **counter** is the teaching backbone — the smallest domain that lets the abstractions show their shape without business logic in the way. And **nine_states** is the guest star: every UI state of a todos list (Nothing / Loading / Empty / One / Some / Too Many / Incorrect / Correct / Done) rendered on one screen — the wall of nine that proves the "every state side-by-side" claim in the most uncomfortable, honest way possible.

## Three load-bearing rules

Three contracts that nothing else in Story makes sense without. Most of Story's distinctive properties are downstream *consequences* of these three.

1. **Each variant runs in its own frame.** No cross-scenario state bleed, by construction. The `:empty` variant and the `:loaded` variant cannot, even in principle, see each other's `app-db`. What you see on the canvas is what production would render against the same fixture. This sounds like a small thing; it isn't — it's the reason every other rule on this list is sustainable.

2. **Variant bodies are data — never functions.** A variant body is a plain EDN map: `:setup`, `:args`, `:script`, `:decorators`, and so on. Every slot is round-trippable across the network. Closures live in exactly one place — inside decorator *registrations*, where they're a registration-site concern, not a variant-body concern. This is the lock that lets MCP, visual-regression services, and agent input pipelines all consume the same shape.

3. **Assertions record, don't throw.** A failing assertion doesn't blow up the variant — it appends a record to the run. A script with eight assertions where three fail still runs all eight; the runner asks "did every entry pass?" at the end. You get the full picture from a broken variant, not a stack trace and a single failure.

Internalise these now. The rest of the tutorial spends its time showing what they buy you.

## The four movements (the map of this tutorial)

The nine chapters travel in four movements. Each movement is the tool teaching you one more thing it can do with that single selected artifact.

- **Render (1–3)** — get a variant on screen; see every state side-by-side; learn how *real* each one is.
- **Prove (4–5)** — discover that the variant you've been rendering is already a test; record interactions into a script.
- **Diagnose (6–7)** — when it fails, follow the evidence to the cause; then arrange the workshop itself.
- **Scale (8–9)** — identity and sharing; multi-substrate and the agent/MCP loop.

Chapter by chapter:

1. [Your first variant](01-first-variant.md) — `reg-story`, `reg-variant`, the frame pillar. The hello-world.
2. [Every state, side by side](02-every-state-side-by-side.md) — workspaces, the wall of nine, controls that derived themselves.
3. [The fidelity ladder](03-fidelity-ladder.md) — the same state reached three ways, and the badge that tells you which.
4. [The reveal: the variant *is* a test](04-the-variant-is-a-test.md) — `run` / `is` / `explain`, and the one normalized plan.
5. [The recorder, and `:cannot-run`](05-recorder-and-cannot-run.md) — record a script; meet the most honest word in the tool.
6. [Xray, earned at the moment of failure](06-xray-earned-at-failure.md) — the evidence spine, time-travel, the Story/Xray boundary.
7. [Workspaces, modes, and composition](07-workspaces-modes-composition.md) — the two "modes," DRY without decorator opacity, promotion.
8. [Snapshot identity and sharing](08-snapshot-identity-and-sharing.md) — the content hash that survives renames; honest redaction.
9. [Multi-substrate and the agent loop](09-multi-substrate-and-agent-loop.md) — one variant under three substrates; an agent driving the workshop.

Plus the [API reference](api/index.md) — the flat lookup sheet for "what's the exact slot / verb / grammar?" once you've read the arc.

## Where to install

### The one-liner — scaffold a fresh Story-enabled app

The canonical [`re-frame2-template`](https://github.com/day8/re-frame2/tree/main/tools/template) scaffolds a working Reagent app with the Story workshop wired in. One invocation:

```bash
clojure -Tnew create \
        :template io.github.day8/re-frame2-template \
        :name acme/my-app \
        :include-story? true
```

The generated tree carries a `src/acme/my_app/stories.cljs` namespace with the canonical `reg-story` / `reg-variant` / `reg-workspace` shapes wired against a counter, a `core.cljs` entry that hash-routes `#/stories` to the Story shell, and the Story dep already in `:dev`. Run `cd my-app && npm install && npx shadow-cljs watch app`, open `http://localhost:8280/#/stories`, and you're in.

This is the Story-flavoured equivalent of `npx storybook init`. The `:include-story?` flag is Reagent-only at v1; UIx + Helix variants follow once Story's adapter coverage catches up (see [chapter 9](09-multi-substrate-and-agent-loop.md)).

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

Notice what's *not* in that boot snippet: there is no explicit `(story/install-canonical-vocabulary!)` call. The first `reg-*` in `my-app.stories` (loaded via the `:require` above) auto-installs the canonical tags, the lifecycle machine, the `:rf.assert/*` handlers, the built-in `force-fx-stub` decorator, and the v1 panel set. The boot ceremony is implicit — Storybook has no equivalent step, and neither does Story.

Production builds — where `re-frame.story.config/enabled?` is `false` via `:closure-defines` — short-circuit at registration time, and `mount-shell!` short-circuits before any DOM call. The Story workshop is a development-only artefact; it does not ship to your users.

## A note on authoring vocabulary — `:setup` / `:script`

!!! note "The public authoring slots are `:setup` and `:script`"

    A variant declares preconditions under **`:setup`** and behaviour-under-test
    under **`:script`**. You execute it with three verbs — **`run`**, **`is`**,
    and **`explain`** — and an assertion that the runner cannot prove returns a
    third result state, **`:cannot-run`** (not pass, not fail). That's the
    vocabulary this whole tutorial uses.

    One scoped honesty note, because the tree is mid-migration: the **recorder**
    still emits the older `:play-script` spelling, which the registrar lowers to
    `:script`. That's a clean pre-alpha rename, not a long-lived compatibility
    layer. You'll see it called out exactly once, in
    [chapter 5](05-recorder-and-cannot-run.md), where the recorder's output is on
    screen — and nowhere else.

    The normative contract — the four-bucket plan, the three verbs, `:cannot-run`,
    composition, and the epoch-tape evidence projection — lives in
    [`017-Testing-Story.md`](https://github.com/day8/re-frame2/blob/main/tools/story/spec/017-Testing-Story.md).
    The [API reference](api/index.md) is the reader's index into it.

Ready? Start at [your first variant](01-first-variant.md).
