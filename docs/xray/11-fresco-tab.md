# 11. The Fresco tab

A view re-rendered and you want to know why, or a screen is slow and you want to know what owns the cost. The **Fresco** tab is Xray's view of [Fresco](../core/fresco/index.md), re-frame2's native view layer. It has six views: which views are mounted, which subscriptions they read, what was dispatched, what changed, which view is hot, and how one dispatch travelled from event to paint.

Fresco calls each independently re-rendering view a **boundary**, and so does this tab.

The tab also tells you what it cannot see. Where it has no evidence for something, it says so and names why, instead of showing an empty table or a guess.

## The tab is always there

The Fresco tab is present in every Xray, whether or not the inspected app uses Fresco, and there is no switch to turn it on.

What changes is what it says. Xray loads Fresco's evidence reader itself, so in a development build it always answers, and on an app with no Fresco boundaries each view says what an empty list means for it. In a production build, where Fresco's evidence is compiled out, the tab says in one sentence that there is no Fresco evidence.

Reach it in Dynamic mode from the tab strip or the command palette, or from a host with `focus!`, described [below](#focusing-the-tab-from-a-host). Its tooltip reads "Fresco (h)", but like every tab letter the `h` is a label, not a shortcut.

## One read, six views

Across the top of the tab is a strip of six buttons. Hover any of them and the tooltip states the question it answers.

| View | Asks |
| --- | --- |
| **Mounted** | Which boundaries are mounted, over which frames? |
| **Reads** | Which boundaries read each subscription? |
| **Intents** | What was dispatched, in order, inside the retained window? |
| **Why** | Which reads changed, and what can and can't that prove? |
| **Advisor** | Which boundary is hot, what owns the pressure, and what is the smallest route that addresses it? |
| **Causal** | How did one dispatch travel from event to paint, link by link, with every missing link named? |

All six are computed from one read of Fresco's evidence, taken at a single moment. That is why they are views inside one tab rather than six tabs: separate reads could disagree, for example if a view mounted between them. Advisor and Causal also read the runtime's retained trace records: Advisor for the subscriptions' measured recompute times, and Causal for the dispatch's events.

## Reading an empty tab

A tab with no rows can mean three different things, and Xray says which:

| State | What it means | What to do |
| --- | --- | --- |
| **No Fresco evidence** | The evidence reader answered `nil`. Xray loads the reader itself, so this is what a production build shows | Nothing. A Reagent or UIx app in a development build shows an empty roster instead |
| **Schema mismatch** | Fresco answered in an evidence format this Xray build cannot read | Align the Xray and Fresco versions. Xray shows no rows rather than misreading them |
| **Empty roster** | Fresco answered, and there is genuinely nothing to list | Depends on the view — see below |

An empty roster means something different in each view:

- Under **Mounted**, no boundary currently reads a subscription. That is a statement about subscriptions, not about the screen: a hidden subtree that released its reads leaves exactly this result.
- Under **Reads**, no subscription is currently being read, which is not the same as nothing being mounted. A boundary whose body reads nothing still mounts and has nothing to show here. Check Mounted before concluding the app is idle.
- Under **Intents**, the retained window holds no dispatch. That is a limit of the window, not a finding: the runtime keeps only the last `:rf.trace/events-retained` runs per frame and cannot say what fell off. Reproduce the interaction and read again; a bigger window cannot fill an empty one.
- Under **Why**, **Advisor** and **Causal**, it follows Mounted and means what Mounted means. An empty Advisor is not a verdict that nothing is hot.

## The five absence chips

Where a single field is missing rather than a whole list, the tab shows a chip saying why. There are five:

| Chip | Means | What to do |
| --- | --- | --- |
| `capped` | A retention window bounded this; what fell off it cannot be counted | Reproduce the interaction and read again. A bigger buffer recovers nothing already dropped and cannot fill an empty window. Retention matters only if it was set to `0`; `(rf/configure! {:trace-buffer {:events-retained 50}})` restores the default |
| `opaque` | The runtime deliberately does not keep this fact | Nothing. Keeping it would cost every application memory |
| `host-opaque` | React owns this and does not publish it | React DevTools or the browser's performance tools |
| `uncorrelated` | The fact is real but no id links it to a cause, so any link shown would be a guess | Follow the leads offered instead |
| `unknown` | The source reports this field as not recorded, which is not the same as empty or zero | Nobody looked, or nobody could |

## The Advisor

The **Advisor** view ranks the mounted boundaries by attributable subscription time. When no retained recompute carries a measured time, it ranks them by recompute count instead, and says so in its summary line. Each row shows the boundary's time, recomputes and memo hits, read churn and fan-out.

It then says what owns the cost:

| Owner | Basis | Means |
| --- | --- | --- |
| Computation | observation | One read's measured recompute time dominates, above the clock's floor |
| Read topology | derivation | The read set is the problem — several read orders are folded onto the boundary's key, or it re-runs repeatedly for little measured work. A fold can have more than one cause, so that finding carries an `uncorrelated` chip |
| Unattributed (recomputes happened) | host-opaque | The window was searched, recomputes happened, and the measured time does not explain them |
| Unattributed (memo hits only) | host-opaque | Reads were considered and the memo answered every one. Nothing recomputed, so computation owns none of the cost |
| Unattributed (nothing retained) | cap | The window retained no activity for this boundary at all |

The three unattributed rows need different responses. `cap` means nothing about this boundary is in the window yet, so reproduce the interaction and read again. The two `host-opaque` rows mean the cost is real but lies in lowering, React or layout, which this tab does not measure, so you need another tool.

From the owner, never from how hot the boundary is, the Advisor picks a route:

| Owner | Route |
| --- | --- |
| Computation | **Narrow or memoize the subscription**. The cost runs below the view layer, so moving the view would keep it |
| Read topology | **Tune topology without changing language**, rung 2 of the performance ladder |
| Unattributed, host-opaque | **Measure the unattributed half elsewhere** |
| Unattributed, cap | Reproduce the interaction and read the tab again |

The hottest boundary on the page and the coldest one with the same owner get the same route.

The Advisor never recommends a native route, such as isolating a view as a React island, from this evidence. It measures application computation, from the retained subscription times, and derives read topology from read orders and recompute counts. It does not measure Hiccup lowering, React reconciliation or DOM layout, and every native rung of the ladder addresses one of those three. So when the owner is one it cannot see, the Advisor names the three candidates and the tool that settles each, and stops there. [Fix a slow view](../core/how-to/fix-a-slow-view.md) walks the ladder itself.

## The Causal view

**Causal** takes one real dispatch and walks the chain link by link:

```text
event
  → subscriptions recomputed
  → values changed
  → boundaries notified
  → bodies run
  → React commit
  → paint
```

Each link shows its own evidence and, separately, what joins it to the previous link. A link can be evidenced but joined to its predecessor by nothing, and only links joined by an id make a chain.

The last three links are always unknown to Xray. Whether a notified boundary actually re-ran, retried, was abandoned or was skipped by its memo comparison is React's to know; a render measurement is not a commit; and nothing re-frame2 records observes the browser's paint. Each of the three names the tool that does know: React DevTools for the first two, the browser's performance tools for paint.

The chain is drawn for the boundary the Advisor ranked first and for the event focused in the event list. When nothing is focused, or the focused dispatch has left the retained window, it walks the newest dispatch the window still holds.

## Focusing the tab from a host

A host, such as a Story beat or a docs link, can open the tab directly:

```clojure
(require '[day8.re-frame2-xray.core :as xray])

(xray/focus! {:panel :fresco})
```

[Mount control](api/mount-control.md#focusing-a-panel-from-a-host) lists the other keys `focus!` takes.

## A Fresco debugging loop

1. Reproduce the interaction.
2. Open **Fresco** and read **Advisor** first. It points at a boundary and names the owner.
3. If the owner is computation or topology, open **Reads** for the fan-out and read set behind it.
4. If the owner is unattributed with a `cap` basis, reproduce the interaction and read again.
5. If the owner is unattributed and `host-opaque`, open the tool the Advisor named. This tab has nothing more to add.
6. When you need the whole chain for one dispatch rather than one boundary's ranking, click that event in the event list and open **Causal**.

[Diagnostics](../core/fresco/16-diagnostics.md) in the Fresco guide covers the same ground from the application side: the cause table, the pressure table, and the fixes each one selects.
