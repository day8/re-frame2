# 11. The Hicasso Tab

Your view re-rendered and you want to know why. The **Hicasso** tab is Xray's
lens on [Hicasso](../core/hicasso/index.md), re-frame2's native view layer: six
views over one evidence read, covering which boundaries are mounted, which
subscriptions they hold, what was dispatched, what changed, which boundary is
hot, and how one dispatch travelled from event to paint.

It is also the tab most likely to surprise you, because it spends as much effort
telling you what it *cannot* prove as what it can. That is deliberate, and this
chapter is mostly about why.

## The Tab Is Always There

The Hicasso tab is registered at Xray install time alongside Epoch, app-db,
Views and the rest. It is not conditional on the inspected application running
Hicasso, and there is no switch to turn it on.

What changes is what it *says*. On an app that is not running Hicasso, the tab
renders one sentence explaining exactly that, rather than an empty table you
would have to interpret. That distinction is the tab's organising idea: an
absence of evidence and evidence of absence are different findings, and this
panel never lets them look alike.

Reach it in Dynamic mode from the L3 tab strip, or with its `h` mnemonic.

## One Read, Six Views

Across the top of the panel is a strip of six buttons. Each is a projection of
the *same* evidence, taken in a single turn — hover any of them and the tooltip
states the question it answers.

| View | Asks |
| --- | --- |
| **Mounted** | Which boundaries are mounted, over which frames? |
| **Reads** | Which boundaries read each subscription? |
| **Intents** | What was dispatched, in order, inside the retained window? |
| **Why** | Which reads changed, and what can that prove? |
| **Advisor** | Which boundary is hot, what owns the pressure, and what is the smallest route that addresses it? |
| **Causal** | One dispatch, walked link by link from event to paint, with every missing link named? |

They are sub-views of one tab rather than six tabs for a reason worth knowing:
they are derivations of a single one-turn read. A second tab would take a second
turn, and a mount landing between the two would give you a ranking about a
census the causal slice no longer agrees with.

The first four are the questions the spec says a developer actually asks of a
view substrate. Advisor and Causal are derivations over those same four
envelopes — which is why both inherit the mounted census's state.

## Reading An Empty Tab

A tab with no rows can mean three unrelated things, with three unrelated
remedies. Xray writes each one out:

| State | What it means | What to do |
| --- | --- | --- |
| **Not running Hicasso** | The evidence door answered `nil` — this app does not use Hicasso, or this is a production build where the door is erased rather than empty | Nothing. This is the correct reading for a Reagent or UIx app |
| **Schema mismatch** | Hicasso answered, stamping an evidence schema this Xray build was not taught to parse | Align the Xray and Hicasso versions. Rows are suppressed rather than mis-parsed, because a shape read as though it were the expected one is worse than no rows |
| **Empty roster** | Hicasso answered, and the roster is genuinely empty | Depends on the view — see below |

The third row is the interesting one, because "empty" is a different fact in
each view and each gets its own sentence:

- Under **Mounted**, an empty roster is a survey result. No boundary holds a live
  read edge, and the read-set entry cache is authoritative about that. But it is
  a statement about *subscription*, not about the screen: a hidden subtree that
  released its reads leaves exactly this census.
- Under **Reads**, it means no subscription cell is currently held — which is not
  the same as nothing being mounted. A boundary whose body reads nothing still
  mounts and still has no edge to show here. Check Mounted before concluding the
  app is idle.
- Under **Intents**, it is a *cap*, not a finding. The retained ring keeps
  `:rf.trace/events-retained` runs and cannot say what fell off it. Raise the
  retention knob to see further back.
- Under **Why**, **Advisor** and **Causal**, it follows the mounted census and
  carries its qualifications. An empty Advisor is not a verdict that nothing is
  hot.

## The Five Absence Chips

Where a single *field* is missing rather than a whole roster, the tab renders a
chip. There are five, they never render alike, and telling them apart is the
point:

| Chip | Means | Remedy |
| --- | --- | --- |
| `capped` | A retention window bounded this; what fell off the ring cannot be counted | A bigger buffer |
| `opaque` | The substrate keeps no such fact, deliberately and permanently | None — retaining it would cost every application memory for a panel's benefit |
| `host-opaque` | React owns this and does not publish it | React DevTools or the browser's performance tools |
| `uncorrelated` | The fact is real but joins to nothing; any link shown would be adjacency presented as cause | Follow the leads offered instead |
| `unknown` | The producer states this field as not held — not empty, not zero | Nobody looked, or nobody could |

## The Advisor Ranks, Classifies, And Refuses

The **Advisor** view ranks the mounted census by time, frequency, read churn and
fan-out, then does the part a sorted list of durations cannot: it says what owns
the pressure.

| Owner | Basis | Means |
| --- | --- | --- |
| Computation | observation | One read's measured recompute time dominates, above the clock's floor |
| Read topology | derivation | The read set is the problem — it oscillates, or re-runs repeatedly for little measured work |
| Unattributed (recomputes happened) | host-opaque | The window was searched, recomputes happened, and the measured half does not explain them |
| Unattributed (memo hits only) | host-opaque | Reads were considered and the memo answered every one. Nothing recomputed, so computation owns none of it |
| Unattributed (nothing retained) | cap | The window retained no activity for this boundary at all |

Those last three are the trio a naive tool collapses into one shrug. Keeping
them apart is what makes the advice actionable: `cap` means *raise the retention
knob and look again*, which is free, while the two `host-opaque` rows mean *the
answer is real and lives in another tool*, which is a change of instrument.

From the owner, the advisor selects a rung of the performance ladder — never
from how hot the boundary is. A boundary can be the hottest on the page and
still select "tune topology", because topology is what its owner answers to.

**And here is the part to know before you open it: from this evidence the
advisor never recommends a native route.** Not once, not for the hottest
boundary on the page. That is not a stub and not timidity. Of the five pressure
classes, this door measures one — application computation, from the retained
subscription ring's elapsed times — and derives a second, read topology, from
fan-out and read orders. Hiccup lowering, React reconciliation and DOM layout
are all unmeasured here, and every native rung of the ladder addresses one of
those three. Recommending an expensive, semantics-changing, diagnostics-losing
change on evidence that cannot speak to whether it helps is the most expensive
mistake this surface could make you make.

So when the owner is one this door cannot see, the Advisor names the three
candidates, names the instrument that settles each, and stops. That refusal is
the product, not a gap in it. [Fix a slow
view](../core/how-to/fix-a-slow-view.md) walks the ladder itself.

## The Causal View

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

Each link prints its own basis *and* its join to the previous link, separately —
because an evidenced link joined to its predecessor by nothing is a different
state from an evidenced link joined by an id, and only the second is a chain.

The last three links are constant, and honestly so. Whether a notified boundary
re-ran, retried, was abandoned or was bailed out by its memo comparator is
React's to know; a render measure is not a commit; and nothing in a subscription
table or a trace ring observes the compositor. Each of the three names its
authority — React DevTools for the first two, the browser's own performance
tools for paint — so you know which tool to open next rather than being left
with a blank.

The slice is drawn for the boundary the Advisor ranked first and the newest
dispatch the ring still holds, so ranking and chain are one workflow rather than
two lookups.

## Focusing The Tab From A Host

`:hicasso` is one of the canonical focusable panel ids, so a host — a Story beat,
an assertion, a docs link — can land a reader directly on it:

```clojure
(require '[day8.re-frame2-xray.core :as xray])

(xray/focus! {:panel :hicasso})
```

The full set is in [the reference](api/reference.md#day8re-frame2-xraycore).

What the Hicasso tab does *not* have is a standalone `mount-*!` facade. It is an
**L4-only registry tab**: focusable and composed by the shell, but not
independently mountable into a host's own layout the way Epoch or App-DB Diff
are. Graph and Frames are the same shape. This is a deliberate split, and it is
a different axis from focusability — a tab can be one without the other.

## A Good Hicasso Debugging Loop

1. Reproduce the interaction.
2. Click the event row in the spine.
3. Open **Hicasso** and read **Advisor** first — it points at a boundary and
   names the owner.
4. If the owner is computation or topology, go to **Reads** for the fan-out and
   read-set shape behind it.
5. If the owner is unattributed with a `cap` basis, raise
   `:rf.trace/events-retained`, reproduce, and read again.
6. If the owner is unattributed and `host-opaque`, stop here and open the tool
   the advisor named. This tab has told you everything it honestly can.
7. Use **Causal** when you need the whole chain for one dispatch rather than one
   boundary's ranking.

[Diagnostics](../core/hicasso/16-diagnostics.md) in the Hicasso guide covers the
same ground from the application side — the cause table, the pressure table, and
the fixes each one selects.
