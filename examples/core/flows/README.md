# A shopping cart that keeps its totals up to date

This example is a small shopping cart. It has three line items, each with a quantity you can nudge up or down, plus a running subtotal and total. Bump a quantity and the subtotal and total update on their own. Click **Apply 10% discount** and the total drops; click **Remove discount** and it climbs back. It all runs in your browser — there's no backend.

Those totals aren't worked out in the [view](../../../docs/core/glossary.md#view). The framework computes them and stores them in [app-db](../../../docs/core/glossary.md#app-db), right next to the line items they come from — and a derived value kept in app-db like that is a [flow](../../../docs/core/glossary.md#flow). That's the idea worth taking away:

> **Some computed values are state, not just something a view shows.**

A [subscription](../../../docs/core/glossary.md#subscription) derives a value too, but it keeps the result in a view-facing cache — perfect for rendering, invisible to everything else. A flow writes its result to app-db instead. So an [event](../../../docs/core/glossary.md#event) handler can read it as plain data, it comes back under time-travel, and it survives the wire.

You declare three things: the `:inputs` to watch, a pure `:derive`, and the `:output-path` to write. When an input changes, the runtime re-runs `:derive` and writes the result — in step with the event pipeline. The cart's subtotal and total are exactly that kind of value.

This is the runnable companion to [`spec/013-Flows.md`](../../../spec/013-Flows.md), the spec for flows. New to flows? Read the [flows guide](../../../docs/core/flows.md) first — this example assumes the basics.

## Why a flow and not a sub

This is the key question this example exists to answer (Spec 013 §When (and when not) to use a flow). The default answer is *still a subscription*: lighter, cached per-input, no app-db write. A typical app has dozens of subscriptions and a handful of flows, if that.

Reach for a flow only when the derived value is no longer just a view's render input — when it has become part of the application's **state**:

- another event handler reads it as plain `app-db` data,
- it should survive SSR hydration, a time-travel revert, or app-db serialisation (sub-cache contents do not survive the wire),
- the derivation is stable enough to be worth registering.

The cart total meets all three. The clearest sign is one line: `:checkout/place-order` needs the total *inside a handler*, where no subscription can reach. That single need is what makes it state, not a view input. Most values never cross that line — when in doubt, use a subscription.

## What this demonstrates

Three things Spec 013 calls out, all in this one cart.

**Computed state in app-db.** `:cart/subtotal` sums the line items and writes the result to `[:cart :subtotal]`. `:cart/total` takes it from there. Both are declared with `rf/reg-flow`. The runtime — not your code — is the only writer of those paths.

**A flow that reads another flow.** `:cart/total`'s `:inputs` are `[:cart :subtotal]` (another flow's output) and `[:cart :discount-rate]`. The runtime sees that `:cart/total` depends on `:cart/subtotal` (their paths overlap) and runs `:cart/subtotal` first. Both update in a *single* pass, right after the event handler and before the `:db` commit. So when you bump a quantity, the line-item change, the new subtotal, and the new total all land in one commit. A view never sees the subtotal updated while the total is still stale.

**A derivation you can switch on at runtime.** The discount is a feature gate. `:cart/discount-rate` is *not* registered at boot; while it is absent, its path reads nil and `:cart/total` treats it as 0% off. "Apply 10% discount" registers the flow mid-event with the `:rf.fx/reg-flow` effect. "Remove discount" tears it down with `:rf.fx/clear-flow` (which also removes its output from app-db, so the path reads nil again). Because a flow is data in a registry — not logic baked into event chains — you can add or remove one while the app runs. (v1's closest equivalent, the `on-changes` interceptor, wires into fixed events at registration time and can't be toggled.)

Two smaller payoffs come along too, both about how *ordinary* a flow's output is:

- **A handler reads it with a plain `get-in`.** `:checkout/place-order` reads `[:cart :total]` straight off `db` — no subscribe, no special flow accessor. The flow's output is just app-db state.
- **A view reads it through a plain subscription.** The subscriptions over `[:cart :subtotal]` and `[:cart :total]` are ordinary app-db reads; a flow publishes no subscription of its own. The `:output-path` is the contract, and anything that reads app-db can read it.

### One wrinkle worth knowing: the one-event lag

A flow registered mid-event does not compute during *that* event. Effects run after the event's flow pass is already done, so a freshly-registered flow produces its first output only on the **next** drain.

The discount handlers solve this with a small trick from the spec's `:wizard/settle` pattern. Right after registering (or clearing) the discount flow, they `[:dispatch [:cart/touch]]`. `:cart/touch` is a no-op — `(fn [{:keys [db]} _] {:db db})`, it writes nothing. Its only job is to cause a drain, so the flows re-run with the just-changed flow now visible, and the new total appears. The toggle is the registration; the no-op is the nudge that makes it show up.

## Files

```
flows/
  core.cljs    — :cart slice, the three flows, demo events,
                 subs, view, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/flows
```

Then open the served build. Bump a cart quantity to watch the subtotal and total update together. Toggle the discount to add and remove a flow live.

## Cross-references

- [`spec/013-Flows.md`](../../../spec/013-Flows.md) — the normative spec for everything above.
- [`spec/Pattern-LongRunningWork.md`](../../../spec/Pattern-LongRunningWork.md) — a separate but related pattern (long-running work, not materialised state).
