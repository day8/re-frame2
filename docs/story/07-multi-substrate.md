# 7. Multi-substrate side-by-side

re-frame2 ships with four view-substrate adapters: **Reagent**, **Reagent-slim**, **UIx**, and **Helix**. Most apps pick one and stay there. But for component-library maintainers and adapter authors, *the same variant rendered under multiple substrates* is the daily diff.

Story's `:substrates` slot on `reg-story` declares which substrates the parent story exercises:

```clojure
(story/reg-story :story.counter
  {:component  :counter-with-stories.views/counter-card
   :substrates #{:reagent :uix :helix}
   :args       {:label "Count"}})
```

Now every variant under `:story.counter` mounts under all three substrates, side-by-side, in a tri-cell layout.

## What you see

Three columns. Same variant. Three substrates rendering it. The component must be substrate-agnostic — keyword view-id, registered through `reg-view`, no substrate-specific imports in the view body. The runtime knows how to invoke the keyword under each substrate.

![Same variant rendered side-by-side under Reagent, UIx, and Helix](../images/story/07-multi-substrate.png)

If a substrate-specific behaviour diverges (e.g., Reagent's r-atom mount semantics vs UIx's signal mount), the three columns diverge visibly. That's the point of the side-by-side: visual confirmation of behaviour parity.

## The contract on substrate-agnostic views

For `:substrates` to work, your view registration must be substrate-agnostic. Concretely:

- No `:require` of substrate-specific libraries from inside the view body. (`reagent.core`, `uix.core`, `helix.dom` — none of them.)
- Use `reg-view` (not Reagent's `defn` shape or UIx's `defui` shape directly). `reg-view` is the substrate-agnostic registration macro; the adapters interpret the registered body.
- For host-specific affordances (Reagent's `:>` shape, UIx's `$` shape), guard them behind a `(case (rf/substrate) :reagent ... :uix ...)` switch. Most apps don't need this.

Views that *do* need substrate-specific code aren't covered by `:substrates`; they live under a single-substrate parent story. The adapter chapters at [Guide 19 — Adapters](../guide/19-adapters.md) walk the gotchas.

## What the side-by-side diff is for

Three audiences reach for this:

- **Adapter authors.** When you're writing a new adapter (or fixing a regression in an existing one), you compare the same variant under your new adapter and the canonical Reagent. Visual divergence = bug in the new adapter. The diff is per-variant, per-arg-tuple, per-mode — a tiny budget for differences.
- **Component-library maintainers.** A re-frame2 component library that ships across substrates lives or dies on whether `:re-com/dropdown` renders the same way under all four. Side-by-side is the regression test.
- **Apps migrating substrates.** When a project moves from Reagent v1 to Reagent v2, or from Reagent to Reagent-slim for bundle-size reasons, side-by-side variants are the safety net.

For most app code, you don't need this. You pick a substrate at `init!` time and your stories run under that substrate. Multi-substrate is for the cases where multi-substrate is the point.

## What's deferred

Two surfaces aren't in v1.0:

- **Per-substrate args.** You can't currently say "use these args under Reagent and those args under UIx" — args are substrate-agnostic. If the substrates need different args to produce the same output, the registered view is doing something host-specific that probably should be factored out.
- **Cross-substrate snapshot identity.** Today every substrate produces its own snapshot identity (because the rendered DOM is hashed). A future `:semantic-fingerprint` slot would let visual-regression diff *intended output* rather than *rendered output*. The spec rev is open; today the right tool is human visual review.

---

That's Story.

You've seen:

- The three load-bearing rules: frame-per-variant, EDN bodies, record-don't-throw.
- The four daily affordances: variants, mode tabs, recorder, workspaces.
- The three surfaces unusual for the Storybook lineage: snapshot identity, time-travel, multi-substrate.
- The agent surface (story-mcp) that consumes all of the above through MCP tools.

If you want to look at the worked example end-to-end, [`tools/story/testbeds/counter_with_stories/`](https://github.com/day8/re-frame2/tree/main/tools/story/testbeds/counter_with_stories) wires the seven `reg-*` macros against the canonical counter app — four variants, two workspaces, every assertion shape, every decorator kind, plus a passing integration test.

Or open `#/stories` against your own app, register one variant, and see how it feels.
