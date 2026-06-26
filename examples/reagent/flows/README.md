# flows — Spec 013 worked example

A shopping cart whose subtotal and total aren't read with a subscription — they're *materialised into app-db* and kept fresh by the framework. That one move is the whole point of this example, and it's the companion to [`spec/013-Flows.md`](../../../spec/013-Flows.md).

Here's the idea in a sentence. A [subscription](../../../spec/013-Flows.md) keeps its answer in a view-facing cache: great for views, invisible to everything else. A **flow** keeps its answer in [app-db](../../../spec/013-Flows.md) — so an event handler can read it as plain data, it travels back under time-travel, and it survives the wire. You declare the `:inputs` to watch, a pure `:derive`, and the `:output-path` to maintain; whenever an input changes, the runtime re-runs `:derive` and writes the result, in step with the event cascade. The cart's subtotal and total are exactly such values.

## Why a flow and not a sub

This is the load-bearing question — Spec 013 §When (and when not) to use a flow — and the reason this example exists. The honest default is *still a subscription*: lighter, cached per-input, no app-db write. A typical app has dozens of subs and a handful of flows, if that. So reach for a flow only when the derived value has graduated from "a view's render input" to part of the application's **state**:

- another event handler reads it as plain `app-db` data,
- it should survive SSR hydration, a time-travel revert, or app-db serialisation (sub-cache contents do not survive the wire),
- the derivation is stable enough to be worth registering.

The cart total ticks all three boxes, and the tell is one line: `:checkout/place-order` needs the total *inside a handler*, where no subscription can reach. That single requirement is what tips it from view-input to state. Most values never tip — when in doubt, use a sub.

## What this demonstrates

Three things Spec 013 calls out, all live in this one cart.

**Materialised computed state.** `:cart/subtotal` folds the line items into a sum and writes it to `[:cart :subtotal]`; `:cart/total` takes it from there. Both are declared with `rf/reg-flow` — a registered, runtime-toggleable computed-state declaration — and the runtime, not your code, is the sole author of those paths.

**A flow that reads another flow — the topological cascade.** `:cart/total`'s `:inputs` are `[:cart :subtotal]` (another flow's output) and `[:cart :discount-rate]`. The runtime reads the dependency edge straight off that path overlap and sorts the two flows so `:cart/subtotal` always runs first. Both settle in a *single* walk that fires right after the event handler and before the `:db` install — so bump a quantity and the line-item change, the new subtotal, and the new total all land in one commit. A view never catches the cart mid-update with the subtotal moved but the total stale.

**A derivation you can switch on at runtime.** The discount is a feature gate, and `:cart/discount-rate` is *not* registered at boot — when it's absent its path reads nil and `:cart/total` treats it as 0% off. "Apply 10% discount" registers the flow mid-event via the `:rf.fx/reg-flow` effect; "Remove discount" tears it down via `:rf.fx/clear-flow` (which also vacates the path back to nil). This is the trick a database's materialised view can't pull off: because flows are data the framework holds in a registry rather than logic compiled into event chains, a derivation can be added or removed while the app runs. (v1's `on-changes` interceptor — the closest equivalent — wires into specific events at registration time and can't be toggled.)

Two smaller payoffs ride along, and both are about how *ordinary* a flow's output is:

- **A handler reads it with a bare `get-in`.** `:checkout/place-order` reads `[:cart :total]` straight off `db` — no subscribe ceremony, no special flow accessor. Materialised state *is* app-db state.
- **A view reads it through a plain sub.** The subs over `[:cart :subtotal]` and `[:cart :total]` are nothing but app-db reads; flows publish no framework sub of their own. The `:output-path` is the contract, and anything that reads app-db can read it.

### One wrinkle worth knowing: the one-event lag

A flow registered mid-event doesn't compute during *that* event — effects run after the event's flow pass has already happened, so a freshly-registered flow's first output only appears on the **next** drain. The discount handlers paper over this with a tiny trick from the spec's own `:wizard/settle` pattern: right after registering (or clearing) the discount flow, they `[:dispatch [:cart/touch]]`. `:cart/touch` is a no-op — `(fn [{:keys [db]} _] {:db db})`, it writes nothing — whose *only* job is to make a drain happen, so the flow transform re-walks with the just-(de)registered flow now visible and materialises the discounted total. The toggle is the registration; the no-op is the nudge that surfaces it.

## Files

```
flows/
  core.cljs    — :cart slice, the three flows, demo events,
                 view, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/flows
```

The watch build emits `main.js` into `out/examples/flows/`; copy this
folder's hand-written [`index.html`](index.html) (and the shared
assets it references under [`../../_shared/`](../../_shared/))
alongside it, then serve `out/examples/flows/` over HTTP.
(`npm run test:adapter-smokes` does not build this example — it compiles and
serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).) Examples are test-free per
[`examples/README.md`](../../README.md); flow contract testing lives
in `implementation/flows/test/`.

## Cross-references

- [`spec/013-Flows.md`](../../../spec/013-Flows.md) — the normative spec; every section title in the docstring matches a section there.
- [`spec/013-Flows.md` §When (and when not) to use a flow](../../../spec/013-Flows.md) — the load-bearing decision this example illustrates.
- [`spec/Pattern-LongRunningWork.md`](../../../spec/Pattern-LongRunningWork.md) — separate but related pattern (long-running work, not materialised state).
