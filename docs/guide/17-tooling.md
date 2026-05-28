# 17 — Tooling

You could debug with `println`. People do; I've done it; it works right up until the bug is "the cascade three dispatches back left app-db in a state that only breaks rendering now." For that bug — and for the whole class of "what *happened*?" questions that `println` answers one line at a time — you have something better: a devtools panel mounted inside your own app that lets you scrub time backwards, click any trace event to the line of code that fired it, develop components in isolation, and hand an AI a socket into the *running* program so it can replay the exact cascade that broke. This chapter is the three tools that do that — Xray, Story, and the pair tool — and the one thing they have in common is that they all read the trace stream from [chapter 16](16-observability.md). They add nothing the framework didn't already expose. They're presentation of an already-structured runtime.

That last point is the whole reason this chapter can exist as a *tour* rather than a reference manual. Because the runtime emits everything as data, the tools are thin — they consume the trace bus, the epoch history, and the registry, and they render. So I'm going to show you what each one *does* and *when to reach for it*, not catalogue every panel and flag. The reference lives in each tool's own spec; this is the map.

## Xray: the cascade you can see

If you've used the React DevTools, you know the shape of the genre: a panel docked in your app that shows you what the framework is doing. Xray is that — but where React DevTools show you the component *tree as it is now*, Xray shows you the *cascade as it happened*. That difference is the whole pitch, and it's downstream of the architecture: re-frame2 has cascades to show because effects run at one place and flow through six dominoes ([chapter 04](04-events-and-the-cascade.md)); React doesn't, because the causality is smeared across the tree.

If you've used **re-frame-10x** (the v1 devtool), Xray is its structural successor — same lineage, reorganised. Where 10x organised debugging around the *epoch panel* — here's the event, here's the diff — Xray organises it around the *story a cascade tells*: every dispatch is a node in a graph of causes, every state delta is a slice you can scrub, every machine transition lands on a state-chart you can read.

### What it actually is

Xray is an in-app, inline devtools panel for re-frame2 apps. You don't run it as a separate browser extension or a standalone window — it mounts *inside* your app, in a right-side column your layout reserves for it, and auto-opens once the substrate adapter is ready. You wire it in two steps: drop an `<aside data-rf-xray-host>` into your app shell so it has somewhere to live, and add its preload to your dev build:

```clojure
;; shadow-cljs.edn — dev build only
{:builds {:app {:devtools {:preloads [day8.re-frame2-xray.preload]}}}}
```

The preload registers Xray's listeners (`register-listener!` and `register-epoch-listener!` — the exact APIs from [chapter 16](16-observability.md)), installs a keybinding (`Ctrl+Shift+C` to toggle), and opens the panel. That's it. And because the whole thing rides the dev-only trace surface, a production build with `goog.DEBUG=false` dead-code-eliminates every byte of it — Xray cannot ship to your users even if you forget to remove the preload, because the surfaces it reads aren't there.

### The experiences you'll reach for

Xray is a lot of panels, but you'll live in a handful:

- **The event-detail panel** is the hero, and it's what lands on every open. You click a button, you open Xray, and it answers the canonical questions on first paint: here's the event vector, here's the app-db diff it produced, here's an inline mini-graph of the cascade, here's which fx fired, which subs recomputed, which views re-rendered, and how long each took. This is the "what did that click do?" panel, and most days it's all you need.
- **The time-travel scrubber** is the bottom rail, and it's the thing `println` can never give you. Drag it and you rebase the panel's view of history — scrub back through recent epochs and watch the diffs. Click *rewind* and it calls `restore-epoch` (the time-travel primitive from [chapter 16](16-observability.md)) to actually roll a frame's app-db back to that point. It's read-first by design: passive scrubbing just changes what you're *looking* at; the explicit rewind is the one that mutates, and it surfaces the failure modes a rewind can hit rather than silently lying to you.
- **The machine inspector** draws a proper state-chart per running state machine — the same kind of diagram you'd draw on a whiteboard for the wizard or auth flow from [chapter 12](12-machines.md) — with transition history and jump-to-source. If your flow is a machine, this is where you watch it move.
- **The schema-violation timeline** gives you one row per registered schema and a coloured dot per failure, with the recovery mode that fired. It's how a schema rejection ([chapter 08](08-schemas.md)) becomes something you *see* instead of something you find out about three screens later.
- **The issues ribbon** is the unified feed — errors, warnings, schema violations, hydration mismatches, all in one place so a problem can't hide.

The through-line: Xray is **read-only by posture**. It observes. The one place it writes — the rewind button — is the deliberate, surfaced exception, not a casual side effect. You can leave it open all day and trust that it isn't quietly changing your app's behaviour.

Reach for Xray when the question is *"what is my app doing, and what did this just do to it?"* It's the everyday panel.

## Story: a playground for one component at a time

Here's the JS-ecosystem anchor, and it's a clean one: **Story is Storybook for re-frame2.** If you've built components in Storybook — each component in isolation, in a sidebar, in a bunch of named states you can click between — you already know the shape. Story is that shape, with the re-frame2 architecture making several of Storybook's hardest problems disappear.

The premise is the same as Storybook's: developing a component *inside* your full app is miserable. To see the login form's error state, you have to drive the whole app into that state — type a bad email, submit, wait. To see the empty state, the success state, the loading state, you do it again and again, by hand, every time you touch the CSS. Story (like Storybook) flips that: you declare the *variants* of a component up front, each one a named scenario, and flip between them in a sidebar instantly.

### Where it diverges from Storybook — and wins

Three places Story is deliberately *not* Storybook, each a direct consequence of re-frame2's primitives:

**Each variant runs in its own frame.** This is the big one. In Storybook, isolating one story's state from another's is a constant, leaky battle — global stores bleed, module state persists, you fight it with decorators and resets. Story gets isolation for free because [frames](18-frames.md) are *already* isolated app contexts: each variant mounts in its own frame, with its own app-db, its own subscription cache. What you see is what you'd get in production, because it's a real re-frame2 app running for real — just a small one.

**Variants are data, not functions.** A Storybook story is JavaScript — a function with closures, which means it can only really live in the browser that runs it. A Story variant is plain EDN:

```clojure
(story/reg-variant :story.auth.login-form/validation-error
  {:doc    "Invalid email shown inline after submit."
   :events [[:auth/initialise]
            [:auth/email-changed "not-an-email"]
            [:auth/login-pressed]]
   :play-script [[:dispatch-sync [:rf.assert/path-equals [:auth :status] :rejected]]
                 [:dispatch-sync [:rf.assert/no-warnings]]]
   :tags   #{:dev :docs :test}})
```

No fn-slots, no closures — the variant is the events that drive the component into the state, plus an optional play-script of assertions. Because it's data, it round-trips: through MCP to an AI, through a visual-regression service, through the test runner. The same variant that's a sidebar entry in dev is a snapshot key in CI is a fixture an agent can read. A function can't do that; data can.

**Mock anything, not just the network.** Storybook needs a separate addon for each kind of fake — MSW for HTTP, custom decorators for analytics, shim libraries for storage. Story has one primitive that covers all of it: `force-fx-stub` stubs *any* effect handler you registered with `reg-fx`, in three lines inside the variant body. Because every side effect in re-frame2 is a named, data-described fx ([chapter 07](07-effects-and-coeffects.md)), stubbing one is uniform — the network isn't special, it's just one fx among many, and the same gesture fakes the lot.

### The bits that round it out

Story embeds re-frame-10x's epoch panel as a registered panel — so time-travel inside a story is a UI affordance, not a reimplementation. Its variants ship schema-derived controls (the knobs Storybook calls "args," auto-generated from your schemas), content-hashed snapshot identities for visual-regression keying, and a `:test`-mode play step-debugger — step, pause, rewind, breakpoint over a variant's play-script, the same controls Storybook's Interactions panel gives you for `play()` functions, driving the canvas re-render against each step's app-db.

Reach for Story when the question is *"how does this component behave across its states?"* — when you're building or refactoring a component and want to see all its faces at once without driving the whole app into each one.

## The pair tool: an AI with a socket into your running app

The third tool isn't a panel — it's a server. And its JS-ecosystem anchor is the newest of the three, because it barely existed until recently: **MCP**, the Model Context Protocol, the standard way an AI coding agent (Claude Code, Cursor, Copilot) reaches out of its chat window and *operates on something*. The pair tool — `re-frame2-pair-mcp` — is an MCP server that gives an agent a live socket into your **running** re-frame2 app.

Sit with the difference between this and the other two. Xray and Story are tools *you* read. The pair tool is a tool an *AI* reads — and not a static codebase, but the live runtime, mid-session, with real state in app-db and a real trace ring full of what just happened. The agent isn't guessing from your source code what the bug might be. It's *attached to the bug*.

### What it can do

Under the hood it holds one persistent nREPL socket to your shadow-cljs dev process and exposes a set of operations as MCP tools the agent calls. The shape of the surface:

- **Inspect state.** `snapshot` reads a frame's app-db, sub-cache, machines, epochs, and traces in one round-trip — the "what state is the app in right now?" mega-read. `get-path` reads a single addressed value when the agent already knows where to look. `eval-cljs` evaluates an arbitrary form against the live runtime — the REPL primitive, for when the agent needs to ask a question the named ops don't cover.
- **Watch the cascade.** `subscribe` opens a streaming subscription on the trace / epoch bus — every matching event arrives at the agent as it fires, push-mode. `watch-epochs` and `trace-window` are the pull-mode equivalents: "give me the epochs that matched this predicate in the last N ms." This is the agent reading the *same* trace stream Xray paints — the [chapter 16](16-observability.md) wire, consumed by an AI instead of a panel.
- **Drive the app.** `dispatch` fires a re-frame2 event — tagged with `:origin :pair` so its dispatches are distinguishable from the app's own — and the agent can then watch the cascade that results.
- **Time-travel and inject.** `restore-epoch` rewinds a frame's app-db to a recorded prior epoch (the same `restore-epoch` Xray's rewind button calls). `reset-frame-db` injects an arbitrary state value the runtime never recorded — the "reproduce the bug that only happens with *this* JSON loaded" gesture.
- **Discover.** `list-handlers` and `handler-meta` introspect the registry — every registered event / sub / fx / view, with source coordinates and a jump-to-editor link. The agent can find the handler, read its metadata, and jump you to the line.

### The gestures that matter — and the guardrails

The killer move is the loop: the agent dispatches an event, streams the resulting cascade, reads the app-db delta, and — if something's wrong — rewinds with `restore-epoch` and tries again. It's the time-travel debugging from [chapter 16](16-observability.md), driven by an AI that can read the trace stream, form a hypothesis, test it against the live runtime, and undo. The agent that broke your app can replay the cascade that broke it.

That power is gated, on purpose. The two state-mutating tools — `restore-epoch` and `reset-frame-db` — are **disabled by default**; the operator opts in at launch with `--allow-writes`, and even then they carry a destructive-action annotation so the agent host can prompt before firing. The read/inspect tools default to honouring the same privacy elision the production wire does — sensitive slots redacted, large slots elided — unless you explicitly opt out with `--allow-sensitive-reads`. The honest note the tool itself makes: these gates protect the *named-write audit trail*; they don't sandbox `eval-cljs`, which can express any write. The real defence is the obvious one — don't wire this MCP up to a caller you don't trust. Once it's in your `~/.claude.json`, you've declared trust in the surface.

One design choice worth knowing: the pair tool is **re-frame2-runtime-only**. It reads and drives app-db, subs, machines, the trace bus — it does *not* click buttons or take screenshots. That's deliberate: browser-driving is a separate concern with a heavyweight Chromium dependency, and bundling it would force every re-frame2 developer to take that on just to read app-db. The intended setup is co-install: a browser-substrate MCP (Chrome DevTools MCP, Playwright MCP) drives the DOM, the pair tool drives the runtime, and the agent glues them — the browser MCP clicks the button, the pair tool's `subscribe` receives the resulting epoch, the agent inspects the new state slice. Each server stays single-purpose.

Reach for the pair tool when the question is *"this is broken in a way I can't see, help me find it in the live app"* — and you want an AI doing the read-hypothesise-test-undo loop against the running runtime instead of squinting at source.

## Three tools, one wire

Step back and the pattern is the whole [chapter 16](16-observability.md) thesis made concrete. Xray reads the trace stream and paints it for you. Story runs real frames and embeds the same epoch panel. The pair tool streams the same trace bus to an AI and drives the same `restore-epoch` rewind. None of the three has a private hook into the framework. None patches your handlers. They all consume the one structured stream the runtime already emits — `register-listener!`, `register-epoch-listener!`, `trace-buffer`, the registry query API — which is why they tell *consistent* stories: the cascade Xray draws, the cascade Story replays, and the cascade the AI describes over the socket are the same cascade, because they're the same events.

So the choice between them isn't really "which tool" — it's "which question." *What did this just do?* — Xray. *How does this component behave across its states?* — Story. *Help me find a bug I can't see in the live app?* — the pair tool. And when you outgrow all three, the door's open: a custom recorder, a debug overlay, a domain-specific monitor is one `register-listener!` away, because the framework owns the data and you own the rendering.
