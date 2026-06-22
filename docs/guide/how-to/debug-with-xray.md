# Debug with Xray

It's 11pm and the app is wrong. A value that should be `3` is `7`, or a button is disabled that shouldn't be. Whatever caused it happened several clicks ago. Here's the good news: you don't need to reproduce it, and you don't need a single `console.log`. Every event the app processed is already recorded — the state change, the effects, the subscription recomputes, and the renders that followed. (An event, by the way, is the data describing something that happened — a click, a response arriving — that your app dispatches to update state.)

> **Your app's state isn't a mystery to reconstruct from console.log; it's a ledger you can read.**

If you know Redux DevTools, Xray is that same idea: an action ledger, a state diff, time travel. What Xray adds is the rest of the cascade. Not just "which action, what state", but which effects fired, which subscriptions recomputed, which views re-rendered, and which line of your code each one came from. (A subscription is a query that derives a value from app-db — your app's single state map — and a view is the component that renders it.)

> **Coming from re-frame v1?** Xray is the successor to re-frame-10x: the same epoch-centric debugging, rebuilt on re-frame2's structured trace stream. If you lived in 10x, the muscle memory transfers — same instinct to step the event list and watch the db diff.

## Before you start: a dev build with Xray loaded

Xray ships as a dev-build preload, so the first step is loading it into your dev build:

```clojure
;; shadow-cljs.edn — dev build only
{:builds {:app {:devtools {:preloads [day8.re-frame2-xray.preload]}}}}
```

It mounts inline into a page element you mark `data-rf-xray-host`. If it can't find one, it tells you so on the console. `Ctrl+Shift+C` toggles it. The shell has four layers: a ribbon, the **event list** (the ledger), a tab bar, and a detail panel. Select an event and every tab rebinds to it, so each tab becomes a different lens on that one event. Working in a multi-frame app? Pick the frame you're inspecting in the ribbon's frame picker. (A frame is one isolated instance of your app — its own app-db and registrations.)

> **One event in, full insight out.** This is the whole mental model. There is no "current state" view floating free of an event — every panel is bound to the *one focused event* in the list. Move the focus and the whole shell re-points at that moment. Once that clicks, the rest of Xray is just choosing which lens (tab) you want on the moment you've focused.

None of this exists in production, which is by design. Xray and the trace machinery it reads compile away entirely in `:advanced` builds. See [Configure dev and production builds](configure-dev-and-prod.md).

## Find the event that broke state

You're staring at bad state, and you don't yet know which event put it there. Here's how to find it.

1. **Open Xray** (`Ctrl+Shift+C`). It lands on the latest event. Press `Space` to pause the live feed so new dispatches stop moving the list out from under you.
2. **Press `a`** for the App-db tab. You'll see the diff this one event made — app-db before against app-db after. Each event shows only its own delta, never a cumulative pile, so the change is easy to read.
3. **Walk backwards** with `j` (the previous event in the list; `k` steps forward — or use the ribbon's `◀` `▶` nav buttons), watching the diff. Ask one small question per step: *did this event write the bad value?*
4. **Stop at the first epoch where the bad value appears.** That's your culprit. Press `e` for the Epoch tab, which gives you the full cascade as a numbered pipeline: dispatch site, event vector, coeffects, handler, effects, then the subscriptions and views that followed. (An effect is a description of a side effect to perform — an HTTP call, a navigation — and a handler is the function that runs an event and returns those effects.)

> **Why walking backwards works.** Each event's diff is *just that event's* delta. So the question at every step is purely local — "did this one write the bad value?" — never "what's the running total of everything so far?" You're bisecting a timeline of small, independent changes. The first step where the bad value appears in the *after* column is, by definition, the event that wrote it. No reasoning about accumulation required.

Two shortcuts worth knowing:

- An epoch that carries an issue tints its row pink in the list — a thrown exception or a schema violation does this. So if the breakage was loud, scan for the tint instead of stepping one by one.
- Was the culprit handler dispatched by another event's `:dispatch` effect? Then keep walking. Its cascade lists the follow-up dispatches it queued, so the causal chain stays legible. ([Events and the cascade](../concepts/events-and-the-cascade.md) is the model behind this.)

Press `L` to snap back to live when you're done (`G` does the same — "go to head").

The ledger keeps the most recent 50 epochs by default, and older ones age out. (You can raise that depth in Settings → General if a long session needs more history — at a heap cost.)

## See why a subscription recomputed

The symptom here is different: a view re-rendered, or it shows a wrong derived value, and you don't know why.

Focus the suspect event and press `v` for the Views tab. It lists every subscription that ran during this cascade, one row each. Each row flags two things: whether the sub's **value actually changed**, and whether the recompute was **driven by an upstream sub** — and if so, that upstream sub is named right on the row. Below that, you'll see the views that re-rendered this epoch. Hover a view row and it highlights that view's rendered DOM in the page, so "which component is this row?" never needs guessing. This is the part that usually trips people up, so the hover is there to remove the guesswork.

> **Coming from React DevTools' "why did this render"?** Same question, deeper answer. React can tell you a component re-rendered and which prop changed. Xray follows the chain the whole way down: the view, the subscription that triggered it, the upstream subscriptions behind *that*, and finally the app-db path that actually moved this epoch. You read a causal chain, not just a leaf.

Read it bottom-up to answer *why did this view re-render?* — the view, the sub that triggered it, the upstream subs behind that, and finally the app-db path that changed this epoch (one keypress away on `a`). Read it top-down to answer *why is this sub returning the wrong value?* — find the sub's row, check whether its value changed this epoch, and follow its source chip into the registration code.

If the verdict turns out to be "correct, just too often", head to [Find and fix a slow view](fix-a-slow-view.md).

## Rewind to the bad epoch

Selecting an old event is **passive**. The panels rebase to show that moment, but your running app doesn't move. What if you want the app itself back in the bad state — so you can poke at it live, retry the click, or show a colleague? That's what rewind is for. Focus the epoch and press `r` in the event list (or click the epoch's reset control).

That's a real write. The frame's state is restored atomically to that point: app-db and the runtime's own state, machine snapshots and route included. Subscriptions recompute, and the UI repaints as it was. A rewind that can't be performed — because the epoch has aged out of the buffer, for instance — is refused with a stated reason rather than silently doing nothing.

> **Rewind restores state, not the world.** Effects that already escaped — HTTP requests sent, writes to `localStorage` — happened and stay happened. Rewind is "put the app's *state* back to that point", not "undo the universe". If you know Redux DevTools' time travel, it's the same boundary: the store rewinds; side effects that already left the building do not come back.

## Jump to source from the panel

Everything in the panels that came from your code carries a source coordinate: the dispatch site, the handler, a sub's registration, a view. Each one renders as a clickable chip. Click it and your editor opens at that file and line, which saves you hunting for it. With focus in the event list, `o` opens the focused event's dispatch site.

The default editor is VS Code. To point the chips elsewhere, set it at boot:

```clojure
(require '[day8.re-frame2-xray.config :as xray-config])

(xray-config/configure! {:rf.xray/editor :cursor})
;; :vscode (default) · :cursor · :windsurf · :zed · :idea
;; or {:custom "<uri-template>"} for an editor Xray doesn't know natively
```

> **Nothing happens when you click a chip?** The default `vscode://` scheme only fires if VS Code is actually registered as a handler on your machine — otherwise the OS silently swallows the navigation and JavaScript can't even see it failed. Xray notices this case and nudges you to pick your editor in Settings rather than leaving you clicking a dead chip. Set `:rf.xray/editor` (above) once and the chips light up.

## The keys you'll actually use

Xray is keyboard-first, but you only need a handful of keys for everything above. They live in two scopes: anywhere (global), and while the event list has focus.

| Key | Scope | What it does |
|---|---|---|
| `Ctrl+Shift+C` | global | Show / hide the Xray shell |
| `Space` | event list | Pause / resume the live feed |
| `j` / `k` | event list | Focus the previous / next event |
| `L` or `G` | event list | Snap back to live (follow the head) |
| `a` / `e` / `v` | tab bar | Jump to App-db / Epoch / Views |
| `t` / `m` / `r` | tab bar | Jump to Trace / Machines / Routing |
| `r` | event list | Rewind the live app to the focused epoch |
| `o` | event list | Open the focused event's dispatch site in your editor |
| `Cmd/Ctrl+K` | global | Command palette (everything by name) |
| `,` or `s` | global | Settings |

Don't memorise the table — `Cmd/Ctrl+K` opens a command palette where you can find any action by typing its name. The keys are just the shortcuts you'll wear in over time.

## When Xray isn't the tool

> **The incident is in production.** Xray is dev-only by construction, so there is nothing to open. Production failures reach you through the always-on error surface instead: [Report errors in production](report-errors-in-production.md).

> **The panel shows `REDACTED`.** Values you've classified as sensitive render redacted in Xray, exactly as they do on every other surface. That's working as intended, not a bug in the tool. [Keep secrets and large things out of traces](keep-secrets-out-of-traces.md) covers the classification.

---

**You can now:**

- walk the event ledger backwards to the exact event that wrote bad state, and read its app-db diff
- explain a re-render: which sub changed, which upstream sub drove it, from which app-db path
- rewind a running app to a past epoch — and say what rewind does and doesn't undo
- jump from any panel artefact to the line of code that registered it
