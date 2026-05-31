# 7. Workspaces, modes, and composition

> **What you'll build.** Pivot a whole grid against a theme toggle (modes); switch between the *Dev / Docs / Test* mode-tabs; keep variant bodies DRY with fragments, checks, and `:extends` — without Storybook's decorator opacity. Then promote a failure into a permanent regression variant. This chapter delivers the *doc* face and closes the Diagnose movement.

## Two different things both called "mode"

Before anything else, a disambiguation, because newcomers conflate two completely separate axes and then wonder why the UI seems to have two "mode" things:

- **Mode-tabs** — the per-variant main pane switcher: **Dev / Docs / Test**. (Evidence is a shared spine, [chapter 6](06-xray-earned-at-failure.md), *not* a fourth tab.)
- **Toolbar modes** (`reg-mode`) — chrome-wide saved arg tuples (theme, viewport, locale) that pivot the *whole grid*.

They're orthogonal. Mode-tabs change *what you're looking at* for one variant; toolbar modes change *the environment* every variant renders in. Keep them in separate mental drawers and the rest of this chapter is easy.

## Mode-tabs — Dev / Docs / Test

The three tabs across the canvas:

- **Dev** — the workshop canvas you've been using. Poke the variant, edit controls, look at it.
- **Docs** — executable documentation. `:docs`-tagged variants plus an auto-generated docs table built from each variant's `:doc` string and the view's schema. This is the *doc* face: the same variants you render *are* the documentation, so the docs can't drift from the component — they're the same artifact.
- **Test** — the run-result presentation: pass/fail/cannot-run per assertion, plus the step-debugger (step, pause, rewind, step-back, breakpoint).

<!-- SCREENSHOT S8: the mode-tab strip across the canvas, with Docs mode showing an auto-generated docs table beside a rendered variant. -->

That Docs mode is fed by the *same* schema-derived machinery as the controls panel ([chapter 2](02-every-state-side-by-side.md#controls-that-derived-themselves)) is the schema dividend paying out a third time: one Malli schema → validation → controls → docs.

## Toolbar modes — pivot the grid

A `reg-mode` is a saved bundle of args that the toolbar can toggle across the entire grid:

```clojure
(story/reg-mode :Mode.app/dark
  {:doc  "Dark theme — sets background and label colours."
   :args {:theme :dark :background "#1e1e1e" :foreground "#e0e0e0"}})

(story/reg-mode :Mode.app/light
  {:doc  "Light theme — the default."
   :args {:theme :light :background "#ffffff" :foreground "#1a1a1a"}})
```

Toggle dark mode in the toolbar and *every* variant in the grid re-renders against it. Modes deep-merge into effective args at their slot in the precedence ladder (`global < mode < story < variant`), so a variant can still override the theme locally if it needs to. Group modes by `:axis` — give two themes `:axis :theme` and the toolbar makes them single-select-within-axis (turning on sepia turns off dark). This is the parity with Storybook's `parameters`/globals, expressed as plain saved arg tuples.

## Composition — context flows down, verdict is local

Here's the DRY problem. A dozen checkout variants all need "cart with one SKU, address filled, ready to submit" before they do their one distinctive thing. You do *not* want to copy that prefix into every variant. Storybook's answer is decorators — and decorators are exactly where Storybook gets opaque, because a decorator can quietly inject global behaviour you can't see at the call site. Story's seam is explicit instead. Three tools:

**`:extends`** — specialize another variant. The inheritance rule is precise and worth memorising, because it's the one people get wrong: **context flows down; verdict is local.**

```clojure
(story/reg-variant :story.login/error-after-submit
  {:extends    :story.login/filled         ; inherit the filled-form context
   :script     [[:dispatch [:auth/login-pressed]]]   ; child-only
   :assertions [[:rf.assert/path-equals [:auth :state] :error]]})  ; child-only
```

Through `:extends`:

| Field | Behaviour |
|---|---|
| `:setup` | **appends** root → child (a common silent-regression site — test it) |
| world context (`:args`, frame, network, decorators) | **inherits** (deep-merge / child-wins per slot) |
| `:checks` | **inherit** (checks are the inheritable expectation form) |
| ordinary `:assertions` | **child-only** — never inherited |
| `:script` | **child-only** — never inherited |
| `:tags` | union |

The principle in one line: a child inherits where the parent *was* (the world it set up) but is solely responsible for what it *does* and what it *judges*. A child must never silently run a parent's script.

**`reg-fragment`** — a reusable piece of setup/script/world. Fragments are *flat*: a fragment must not compose another fragment, so cycles are impossible by construction.

```clojure
(story/reg-fragment :fragment.checkout/ready-to-submit
  {:setup  [[:dispatch [:cart/add {:sku "A"}]]]
   :script [[:dispatch [:checkout/open]]
            [:dispatch [:checkout/type-address {:postcode "2000"}]]]})
```

**`reg-check`** — a reusable assertion pack ([chapter 4](04-the-variant-is-a-test.md#checks--reusable-assertion-packs)).

**`:compose`** — pull fragments and checks in explicitly, in declared order:

```clojure
(story/reg-variant :story.checkout/submits
  {:compose    [:fragment.checkout/ready-to-submit :check/no-runtime-errors]
   :script     [[:dispatch [:checkout/submit]]]
   :assertions [[:rf.assert/path-equals [:checkout :state] :submitted]]})
```

The whole point: composition reuses common context **without decorator-style opacity.** Everything that flows into the variant is named at the call site — `:extends` this, `:compose` those — and [`story/explain`](04-the-variant-is-a-test.md#the-four-bucket-plan-a-peek-under-the-hood) will show you exactly what resolved, including which source won any conflict. There is no hidden global behaviour; the seam is explicit fragments and checks, and the explanation is mandatory.

<!-- SCREENSHOT S12 (net-new thesis shot): the Explain panel over a composed variant — source chain, field-level merge decisions, resolved args, final setup/script order, and the winning source for any strict conflict. NOTE (floor-state): the story/explain DATA API is CURRENT where 017 has landed, but the Explain PANEL UI is a TARGET net-new Story surface (018 §6); confirm the panel renders before leaning this shot on it — until then the explain data is reachable via (story/explain target) at the REPL. -->


One inversion to name so it doesn't surprise you: **`:hiccup` decorators read outermost-wraps-innermost** (the first decorator is the outer wrapper), but **`:fx-override` reads innermost-overrides-outermost** (last-wins on an `:fx-id` collision). Two different stacking directions for two different jobs; the spec is explicit about both.

## Promotion — turn a failure into a curated variant

This is the capstone, and it links straight back to [chapter 6](06-xray-earned-at-failure.md). You diagnosed a failure. The failure is real, it's reproducible, and you want it to *stay* caught. In a lot of stacks, this is where generated failures go to live as separate, lonely repro files that drift out of the suite. Story's answer is **promotion**: a failure becomes a curated variant that lives in the grid alongside the hand-authored ones.

Two functions, with a deliberate purity split:

```clojure
(story/materialize-variant-plan artifact opts)
;; -> a readable, normalized variant plan. PURE — registers NOTHING.
;;    Read what a promotion *would* produce before committing to it.

(story/promote-run-artifact! artifact {:variant/id :story.checkout/regression-042})
;; -> registers a NAMED variant. The ONLY function that registers.
```

`promote-run-artifact!` **requires** an explicit `:variant/id` — a missing id is an error (`:rf.error/story-promote-no-id`), never a silent default. There is no auto-register path: a generated failure becomes a curated variant *only* through this named call, on purpose. The promoted variant is indistinguishable from a hand-authored one except for a `:run-artifact` provenance slot that records where it came from and lets it re-derive the run.

So the full Diagnose-movement arc closes here: a variant fails ([ch 6](06-xray-earned-at-failure.md)) → you walk the evidence spine to the cause → you promote the run into a regression variant that lives in the grid forever. The failure you found yesterday is a green dot in your grid today.

## Where we go next

A variant has a stable *identity* that survives renames — and that identity is the key to both visual-regression keying and safe sharing. [Chapter 8](08-snapshot-identity-and-sharing.md): the content hash, and honest redaction.
