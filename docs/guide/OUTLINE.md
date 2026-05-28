# Guide rewrite — chapter outline and sequence

> **Status: phase (a)+(b) of the docs/guide rewrite — awaiting Mike's voice approval.**
> This document is the proposed chapter list, sequence, and rationale for the from-scratch
> rewrite of `docs/guide/`. The companion deliverable is one fully-written voice-test chapter
> ([`01-introduction.md`](01-introduction.md)) — the tone-setter Mike reads to approve or reject
> the voice before the full corpus (phase c) is written.
>
> This is an authoring artefact, not a reader page. It is excluded from the published nav.

## What this rewrite is

The current guide (chapters `02-app-db` through `26-config`) is competent, accurate, and
LLM-shaped. It reads like a careful reference that someone gently narrativised after the fact:
measured, even, allergic to a strong sentence, and bookended on every page by a "What's Next"
section that does the table of contents' job for it. It is *correct*. It is not *alive*.

The rewrite is a tutorial for **humans** — a person reading a story, not an agent parsing a
contract. The voice is Steve Yegge's blog register: long-form-rant-as-engineering-essay,
conversational, funny, opinionated-but-analytical, technically deep without being dry, digressive
by design, and willing to start from a local complaint and expand it into a theory of software.
The reference track (`/docs/api`, `/spec`) stays contract-shaped; the guide is the complement.

## Structure rules (hard constraints — these govern every chapter)

1. **Chapter files are `NN-name.md`.** Straight integer ordering, no `NNa-`/`NNb-` suffixes,
   no gaps. The current guide starts at `02` with no `01`; the rewrite renumbers from `01`.
2. **Every chapter opens with a 2-3 sentence problem/interest hook.** Not "Welcome to the chapter
   on subscriptions." Instead: *"You like to sleep at night and you want to add unit tests? This
   chapter teaches you the seam between event handlers and pure code."* The hook is the bait; it
   names the reader's actual problem and the payoff for reading on.
3. **No "What's Next" / "Next" / "Where this goes" section at the end of a chapter.** The chapter
   ends when its point is made. The TOC carries onward-navigation; the prose does not need to.
   Mid-chapter cross-links to other chapters are fine and encouraged — it's the *trailing
   signpost section* that's banned.
4. **Concepts before notation; notation reinforced by concrete example.** Name the idea, then show
   the form, then run it.
5. **Worked examples beat toy snippets beat prose.** Where an idea rewards being *changed and
   re-run*, it gets a runnable live cell, not a static block.

## The runnable-cell policy

The guide has a live-cell capability: ` ```cljs-rf2 ` fences become editable CodeMirror editors
that mount a real re-frame2 component in the reader's browser (see
[Writing interactive tutorials](interactive-tutorials.md)). Not every chapter gets cells, and
the ones that do don't get *walls* of them. A cell earns its place when editing-and-rerunning
teaches more than reading would. The "Live cells" column below records the intent per chapter;
where a chapter is marked **no**, a static block is the right call and a live cell would be a slow
screenshot.

Two cell rules the rewrite inherits from the existing interactive track and must honour:
- Cells are functions-only: write `defn` views with explicit `rf/dispatch` / `rf/subscribe`,
  not the `reg-view` macro. Note the equivalence in prose the first time it comes up.
- Every cell is self-contained: its own `require`, registrations, app-db seed, and a final
  renderable hiccup form. No cell depends on state left by an earlier cell.

## The chapter list

The guide is in four arcs. The arc boundaries are pedagogical, not structural — there's no
"Part II" page; the reader just notices the terrain change.

### Arc 1 — Foundations (the reader can build a real app after this arc)

| # | File | The hook this chapter sells | Live cells |
|---|---|---|---|
| 01 | `01-introduction.md` | *Your foot in the door. By the end you understand 80% of the basics and have run a real re-frame2 program in your browser without installing a thing.* | **yes** — the whole counter, live |
| 02 | `02-app-db.md` | *Where does the data actually live? In one place. This chapter is the one-sentence answer and all of its consequences.* | no |
| 03 | `03-first-app.md` | *Build the counter for real — in a project, on your toolchain — and meet the five primitives that every re-frame2 app is made of.* | **yes** — counter, taken apart |
| 04 | `04-events-and-the-cascade.md` | *A click happened. Now what? This chapter walks one event through all six dominoes and shows you the machine that runs under every re-frame2 app.* | **yes** — dispatch + trace |
| 05 | `05-subscriptions.md` | *Your view needs to read state without knowing where it lives, and recompute only when it must. That's a subscription, and the graph behind it is where the performance comes from.* | **yes** — sub graph |
| 06 | `06-views.md` | *Views are the least interesting part of the app, and that's the whole point. This chapter is why your render functions are boring, derivative, and impossible to get into a weird state.* | **yes** — hiccup + reaction |

### Arc 2 — Doing real work (the reader can build a feature that talks to the world)

| # | File | The hook this chapter sells | Live cells |
|---|---|---|---|
| 07 | `07-effects-and-coeffects.md` | *Your handler needs the current time, a random id, and to fire an HTTP request — but you swore it would stay pure. Effects and coeffects are how it stays pure and still gets things done.* | **yes** — fx as data |
| 08 | `08-schemas.md` | *You want the app to scream the instant app-db goes wrong, in dev, and to cost exactly nothing in production. This chapter is the schema boundary and the off-switch.* | **yes** — validate at the seam |
| 09 | `09-interceptors.md` | *Every event handler in your app should do the same three boring things before and after the interesting part. This chapter is how you write that once instead of three hundred times.* | no |
| 10 | `10-http.md` | *Network calls are where naive apps go to die: retries, aborts, double-submits, the eight states of a request. This chapter is the one managed-effect shape that handles all of it as data.* | no |
| 11 | `11-forms.md` | *A form is a tiny state machine wearing a trenchcoat: draft, dirty, submitting, failed, done. This chapter is the seven-event lifecycle that stops you reinventing it badly on every screen.* | **yes** — form lifecycle |
| 12 | `12-machines.md` | *Some flows are not "set a flag" — they're "what state are we even in?" Wizards, auth, retries, the Nine States of every GUI. This chapter is when you reach for an actual state machine and why it's the same six dominoes underneath.* | **yes** — a small FSM |

### Arc 3 — Confidence (the reader can trust, test, observe, and ship the app)

| # | File | The hook this chapter sells | Live cells |
|---|---|---|---|
| 13 | `13-testing.md` | *You like to sleep at night and you want to add unit tests? This chapter teaches you the seam between event handlers and pure code — and why testing a re-frame2 app needs no browser, no mocks, and no patience.* | no |
| 14 | `14-errors.md` | *Things break: a handler throws, the network 500s, the schema rejects, the server-rendered HTML disagrees with the client. This chapter is the difference between an error you can see in the trace and a white screen and a shrug.* | no |
| 15 | `15-performance.md` | *Your app got slow and you have no idea why. This chapter is why re-frame2 is fast by default, the handful of ways you can sabotage that, and how the tooling tells you which one you did.* | no |
| 16 | `16-observability.md` | *Every event your app has ever seen is on one bus, and six different tools read it. This chapter is the trace stream, the epoch buffer, and why your running app is the ultimate surveillance state.* | no |
| 17 | `17-tooling.md` | *You could debug with println. Or you could scrub time backwards in a panel mounted inside your own app, click any trace event to the line that fired it, and let an AI replay the cascade that broke. This chapter is Xray, Story, and the pair tool.* | no |

### Arc 4 — The wider world (the reader can build the whole thing, not just the SPA)

| # | File | The hook this chapter sells | Live cells |
|---|---|---|---|
| 18 | `18-frames.md` | *You want two independent copies of your app on one screen — a Story canvas, a split-pane editor, a server render — that don't leak state into each other. This chapter is frames, the isolated context that makes that not a nightmare.* | no |
| 19 | `19-routing.md` | *The URL is application state, your back button is a dispatch, and a route change is just an event. This chapter is routing without a router that fights your state model.* | no |
| 20 | `20-server-side.md` | *You want server-rendered HTML that hydrates cleanly, runs your real handlers on the JVM, and doesn't make you learn a second mental model. This chapter is SSR as the same six dominoes, server-side.* | no |
| 21 | `21-dynamic-model.md` | *Most app state isn't a flag — it's a thing with a lifecycle that the runtime should manage for you: machines, flows, route state, in-flight requests. This chapter is the dynamic model, the runtime-managed slices of app-db you read but don't write.* | no |
| 22 | `22-adapters.md` | *Reagent, UIx, Helix, slim — same app, four substrates, one `init!` call that differs by a single Var. This chapter is what the adapter does and why your app code never names a substrate.* | no |
| 23 | `23-privacy-and-large-things.md` | *Your auth token must not end up in a Datadog log, and a 40MB PDF must not end up in the trace buffer. This chapter is the `:sensitive?` and `:large?` elision story, opt-in and composable.* | no |
| 24 | `24-config-and-safety.md` | *Every knob the framework exposes — what to validate, what to trace, what to elide, what to elide in production — in one place, with the safe defaults called out and the footguns flagged.* | no |
| 25 | `25-from-re-frame-v1.md` | *You have a re-frame v1 app and a migration to plan. This chapter is what maps across cleanly, what changed and why, and where the v2 architecture tells a genuinely different story.* | no |
| 26 | `26-where-to-go-next.md` | *You've read the guide. Here's the spec, the tooling docs, the skills, and the examples — the curated portal to everything the tutorial deliberately didn't cover.* | no |

## Sequence rationale

The didactic spine follows re-frame v1's proven sequence — *counter → cascade → subscriptions →
views* — because it works: it front-loads the smallest complete program, then takes it apart in
the order the reader will need the pieces. The rewrite deviates from v1 in three places where the
v2 architecture changes the story, and the deviations are deliberate:

**Why `01` is a live, hands-on introduction (not a prose overview).** The single best thing the
guide can do in chapter one is get a real re-frame2 program *running in the reader's browser
before they've installed anything*. The capability exists; v1 didn't have it. So chapter 01 folds
together what the current guide splits across `README.md` (the philosophy), `02-app-db`, `03-first-app`,
and `interactive-counter.md` (the live version) into one foot-in-the-door chapter that ends with
the reader having clicked a counter they edited. The "80% of the basics" promise is the hook, and
it's honest: after chapter 1 they've seen events, subscriptions, a view, dispatch, and the cascade,
running. The deeper *why* of each is what arcs 1-2 are for.

**Why effects/coeffects (07) come before HTTP (10) and after the core loop.** The current guide
puts coeffects at `06`, before views. That's backwards for a human: the reader has no *motivating
problem* for coeffects until they've felt the pain of wanting a side effect inside a pure handler.
So the rewrite teaches the pure six-domino loop first (arcs 1), *then* introduces effects/coeffects
(07) as the answer to "but my handler needs to do something impure" — and only then HTTP (10) as
the worked, managed instance of an effect. Problem before machinery, every time.

**Why frames, routing, and SSR move to arc 4.** The current guide front-loads frames (`08`) right
after views. Frames are conceptually gorgeous and pedagogically premature — a reader who can't yet
build a single app doesn't need to know how to run two isolated copies of one. The rewrite holds
frames until the reader has a whole app under their belt and a *reason* to want isolation (Story
canvases, split panes, server renders). Same logic moves routing and SSR late: they're "the wider
world," not "the basics."

**What the reader can build after each arc** — the milestone test for the sequence:

- **After arc 1 (ch. 1-6):** a complete, correct, single-feature SPA — the counter and anything
  shaped like it. Events, subscriptions, a view, the cascade. They understand the machine.
- **After arc 2 (ch. 7-12):** a real feature that talks to the world — a form that validates,
  submits over HTTP, retries, and is driven by a state machine where the flow warrants one. This is
  the arc where re-frame2 stops being a toy and starts being a framework.
- **After arc 3 (ch. 13-17):** an app they can *trust* — tested without a browser, observable on the
  trace bus, debuggable in Xray, fast by default. The arc that turns "it works on my machine" into
  "I can ship this."
- **After arc 4 (ch. 18-26):** the whole system — multi-pane, routed, server-rendered, ported from
  v1 if need be, running on whichever React substrate they like. Plus the portal to the spec for
  when they want the contract.

## Mapping from the current guide

The rewrite is not a renaming pass; several current chapters merge, split, or move arcs. For the
phase-c dispatch, here's the provenance so no content is silently dropped:

| Rewrite chapter | Sourced / merged from current |
|---|---|
| 01 introduction | `README.md` (philosophy) + `interactive-counter.md` + opening of `03-first-app` |
| 02 app-db | `02-app-db` |
| 03 first app | `03-first-app` |
| 04 events + cascade | `04-events` |
| 05 subscriptions | subscription material currently split across `07-views` |
| 06 views | `07-views` (view material) |
| 07 effects + coeffects | `06-coeffects` + effects material from `04-events` |
| 08 schemas | `05-schemas` |
| 09 interceptors | `09-interceptors` |
| 10 http | `12-http` |
| 11 forms | `10-forms` |
| 12 machines | `11-machines` |
| 13 testing | `15-testing` |
| 14 errors | `16-errors` |
| 15 performance | `17-performance` |
| 16 observability | `23-observability` |
| 17 tooling | new — consolidates the Xray/Story/pair narrative the current guide scatters |
| 18 frames | `08-frames` |
| 19 routing | `18-routing` + `19-routing-ref` (ref folds in or moves to /api) |
| 20 server side | `13-server-side` |
| 21 dynamic model | `14-dynamic-model` |
| 22 adapters | `21-adapters` |
| 23 privacy + large things | `24-privacy` + `25-large-blobs` |
| 24 config + safety | `26-config` |
| 25 from re-frame v1 | `20-migration` |
| 26 where to go next | `22-where-next` |

Two current pages don't survive as chapters: `interactive-counter.md` folds into `01`, and the
`interactive-tutorials.md` / `AUTHORING.md` contributor notes stay as contributor notes (not reader
chapters, not in nav). `19-routing-ref` is reference-shaped and is a candidate to move to `/api`
rather than live in the narrative track — flagged for Mike's call in phase c.

## Phase plan

- **(a) outline + sequence** — this document. ✅ delivered.
- **(b) voice-test chapter** — [`01-introduction.md`](01-introduction.md), written fully in the
  target voice as the tone-setter. ✅ delivered.
- **(c) full-corpus rewrite** — one bead per chapter, filed only *after* Mike approves the voice and
  the outline. Each phase-c bead inherits the four structure rules above and the per-chapter hook
  seeded in the table.
