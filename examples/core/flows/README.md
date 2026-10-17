# A shopping cart that keeps its totals up to date

This example is a small shopping cart. It has 3 line items, each with a quantity you can nudge up or down, plus a running subtotal and total. Bump a quantity and the subtotal and total update on their own. Click **Apply 10% discount** and the total drops; click **Remove discount** and it climbs back. It all runs in your browser — there's no backend.

Those totals aren't worked out in the [view](../../../docs/core/glossary.md#view). The framework computes them and stores them in [app-db](../../../docs/core/glossary.md#app-db), right next to the line items they come from — and a derived value kept in app-db like that is a [flow](../../../docs/core/glossary.md#flow). That's the idea worth taking away:

> Some computed values are state, not just something a view shows.

A [subscription](../../../docs/core/glossary.md#subscription) derives a value too, but keeps its result in a cache. Views subscribe reactively; other code, including an event handler, can read it once with [`rf/subscribe-once`](../../../docs/core/subscriptions.md). A flow writes its result to app-db instead, where it is ordinary application state: it comes back under time-travel and survives the wire.

You declare 3 things: the `:inputs` to watch, a pure `:derive`, and the `:output-path` to write. When an input changes, the runtime re-runs `:derive` and writes the result — in step with the event pipeline. The cart's subtotal and total are exactly that kind of value.

This is the runnable companion to [`spec/013-Flows.md`](../../../spec/013-Flows.md), the spec for flows. New to flows? Read the [flows guide](../../../docs/core/flows.md) first — this example assumes the basics.

## Why a flow and not a sub

This is the key question this example exists to answer (Spec 013 §When (and when not) to use a flow). The default answer is still a subscription: lighter, cached per-input, no app-db write. A typical app has dozens of subscriptions and a handful of flows, if that.

Reach for a flow only when the derived value is no longer just a view's render input — when it has become part of the application's state:

- another event handler reads it as plain `app-db` data
- it should survive SSR hydration, a time-travel revert, or app-db serialisation (sub-cache contents do not survive the wire)
- the derivation is stable enough to be worth registering

The cart total meets all 3. `:checkout/place-order` reads the total straight from its `db` coeffect, alongside the line items. Materialising it makes that complete cart snapshot available to handlers and serialisation alike. An occasional handler read alone would not require a flow — `rf/subscribe-once` can do that. When in doubt, use a subscription.

## What this demonstrates

Three things Spec 013 calls out, all in this one cart.

Computed state in app-db. `:cart/subtotal` sums the line items and writes the result to `[:cart :subtotal]`. `:cart/total` takes it from there. Both are declared with `rf/reg-flow` as top-level forms, like the events and subscriptions beside them, and the cart's frame runs them because its `:flows` config names them: `{:id :rf/default :flows [:cart/subtotal :cart/total] …}`. The runtime — not your code — is the only writer of those paths.

A flow that reads another flow. `:cart/total`'s `:inputs` are `[:cart :subtotal]` (another flow's output) and `[:cart :discount-rate]`. The runtime sees that `:cart/total` depends on `:cart/subtotal` (their paths overlap) and runs `:cart/subtotal` first. Both update in a single pass, right after the event handler and before the `:db` commit. So when you bump a quantity, the line-item change, the new subtotal, and the new total all land in one commit. A view never sees the subtotal updated while the total is still stale.

A discount that is just data. The discount rate is an ordinary app-db value. "Apply 10% discount" is an event that writes `0.1` to `[:cart :discount-rate]`; "Remove discount" is an event that removes it, and while it is absent `:cart/total` reads nil as 0% off. Because that path is one of `:cart/total`'s inputs, the total recomputes in the same event, before the commit: one click, one event, one write. A feature that switches a derivation on and off is state the flow reads, not a flow that comes and goes.

Two smaller payoffs come along too, both about how ordinary a flow's output is:

- a handler reads it with a plain `get-in` — `:checkout/place-order` reads `[:cart :total]` straight off `db`, with no subscribe and no special flow accessor. The flow's output is just app-db state.
- a view reads it through a plain subscription — the subscriptions over `[:cart :subtotal]` and `[:cart :total]` are ordinary app-db reads; a flow publishes no subscription of its own. The `:output-path` is the contract, and anything that reads app-db can read it.

## Files

```
flows/
  core.cljs    — :cart slice, the two flows, demo events,
                 subs, view, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/flows
```

Then open the URL it prints. Bump a cart quantity to watch the subtotal and total update together. Toggle the discount to watch the total follow it in the same click.

Hot reload works on the flows too: edit a `:derive` body in `core.cljs` and save. The reloaded namespace re-registers the flow like any other top-level form, and the live frame computes with the edited formula from its next event — your cart state survives, and the next quantity nudge shows the change.

## Cross-references

- [`spec/013-Flows.md`](../../../spec/013-Flows.md) — the normative spec for everything above.
- [`spec/Pattern-LongRunningWork.md`](../../../spec/Pattern-LongRunningWork.md) — a separate but related pattern (long-running work, not materialised state).
