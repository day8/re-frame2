# Debug with Xray

It's 11pm and the app is wrong. A value that should be `3` is `7`. A button is disabled that shouldn't be. Whatever caused it happened several clicks ago. You don't need to reproduce it. You don't need a single `console.log`. Every event the app processed is already recorded: the state change, the effects, the subscription recomputes, and the renders that followed.

> **Your app's state isn't a mystery to reconstruct from console.log; it's a ledger you can read.**

If you know Redux DevTools, Xray is that idea: an action ledger, a state diff, time travel. Xray extends it to the whole cascade. Not just "which action, what state", but which effects fired, which subscriptions recomputed, which views re-rendered, and which line of your code each one came from.

> **Coming from re-frame v1?** Xray is the successor to re-frame-10x: the same epoch-centric debugging, rebuilt on re-frame2's structured trace stream.

## Before you start: a dev build with Xray loaded

Xray ships as a dev-build preload:

```clojure
;; shadow-cljs.edn — dev build only
{:builds {:app {:devtools {:preloads [day8.re-frame2-xray.preload]}}}}
```

It mounts inline into a page element you mark `data-rf-xray-host`. If it can't find one, it says so on the console. `Ctrl+Shift+C` toggles it. The shell has four layers: a ribbon, the **event list** (the ledger), a tab bar, and a detail panel. Select an event and every tab rebinds to it. Each tab is a different lens on that one event. Got a multi-frame app? Pick the frame you're inspecting in the ribbon's frame picker.

None of this exists in production. Xray and the trace machinery it reads compile away in `:advanced` builds. See [Configure dev and production builds](configure-dev-and-prod.md).

## Find the event that broke state

You're staring at bad state. You don't know which event put it there.

1. **Open Xray** (`Ctrl+Shift+C`). It lands on the latest event. Press `Space` to pause the live feed so new dispatches stop moving the list.
2. **Press `a`** for the App-db tab. You see the diff this one event made: app-db before against app-db after. Each event shows only its own delta, never a cumulative pile.
3. **Walk backwards** with `←` / `→` (or `j` / `k` in the list), watching the diff. Ask one small question per step: *did this event write the bad value?*
4. **Stop at the first epoch where the bad value appears.** That's your culprit. Press `e` for the Epoch tab. You get the full cascade as a numbered pipeline: dispatch site, event vector, coeffects, handler, effects, then the subscriptions and views that followed.

Two shortcuts:

- An epoch that carries an issue tints its row pink in the list. A thrown exception or a schema violation does this. If the breakage was loud, scan for the tint instead of stepping.
- Was the culprit handler dispatched by another event's `:dispatch` effect? Keep walking. Its cascade lists the follow-up dispatches it queued, so the causal chain stays legible. ([Events and the cascade](../concepts/events-and-the-cascade.md) is the model behind this.)

Press `L` to snap back to live when you're done.

The ledger keeps the most recent 50 epochs by default. Older ones age out.

## See why a subscription recomputed

Symptom: a view re-rendered, or it shows a wrong derived value, and you don't know why.

Focus the suspect event. Press `v` for the Views tab. It lists every subscription that ran during this cascade, one row each. Each row flags two things: whether the sub's **value actually changed**, and whether the recompute was **driven by an upstream sub**. The upstream sub is named right on the row. Below that, the views that re-rendered this epoch. Hover a view row and it highlights that view's rendered DOM in the page, so "which component is this row?" never needs guessing.

Read it bottom-up to answer *why did this view re-render?* The view, the sub that triggered it, the upstream subs behind that, and finally the app-db path that changed this epoch (one keypress away on `a`). Read it top-down to answer *why is this sub returning the wrong value?* Find the sub's row, check whether its value changed this epoch, and follow its source chip into the registration code.

If the verdict is "correct, just too often", go to [Find and fix a slow view](fix-a-slow-view.md).

## Rewind to the bad epoch

Selecting an old event is **passive**. The panels rebase to show that moment, but your running app doesn't move. Want the app itself back in the bad state, so you can poke at it live, retry the click, or show a colleague? Rewind. Focus the epoch and press `r` in the event list (or click the epoch's reset control).

That's a real write. The frame's state is restored atomically to that point: app-db and the runtime's own state, machine snapshots and route included. Subscriptions recompute. The UI repaints as it was. A rewind that can't be performed (the epoch has aged out of the buffer, for instance) is refused with a stated reason rather than silently doing nothing.

> **Heads-up.** Rewind restores *state*, not the world. Effects that already escaped — HTTP requests sent, writes to localStorage — happened and stay happened. It's "put the app back in that state", not "undo".

## Jump to source from the panel

Everything in the panels that came from your code carries a source coordinate: the dispatch site, the handler, a sub's registration, a view. Each one renders as a clickable chip. Click it and your editor opens at that file and line. With focus in the event list, `o` opens the focused event's dispatch site.

The default editor is VS Code. Point the chips elsewhere at boot:

```clojure
(require '[day8.re-frame2-xray.config :as xray-config])

(xray-config/configure! {:rf.xray/editor :cursor})
;; :vscode (default) · :cursor · :windsurf · :zed · :idea
```

## When Xray isn't the tool

- **The incident is in production.** Xray is dev-only by construction, so there is nothing to open. Production failures reach you through the always-on error surface instead: [Report errors in production](report-errors-in-production.md).
- **The panel shows `REDACTED`.** Values you've classified as sensitive render redacted in Xray, exactly as they do on every other surface. That's working as intended. [Keep secrets and large things out of traces](keep-secrets-out-of-traces.md) covers the classification.

---

**You can now:**

- walk the event ledger backwards to the exact event that wrote bad state, and read its app-db diff
- explain a re-render: which sub changed, which upstream sub drove it, from which app-db path
- rewind a running app to a past epoch — and say what rewind does and doesn't undo
- jump from any panel artefact to the line of code that registered it

**Next:** [Observability: one wire, every tool](../concepts/observability.md) explains the trace stream all of this reads. Re-render too expensive rather than wrong? [Find and fix a slow view](fix-a-slow-view.md).
