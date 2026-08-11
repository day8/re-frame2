# Diagnostics

Use Xray when a view re-renders unexpectedly or an interaction feels slow and
you need to find the cause.

Xray is development tooling. It loads beside the application, reads the trace
the runtime already emits, and requires no instrumentation in your views. It
is removed from production builds.

## Diagnose an interaction

1. Load Xray through the development preload.
2. Reproduce the click, keystroke, or update.
3. Select the view occurrence that ran.
4. Read its cause, fan-out, and attribution.

An **epoch** is one event pipeline run, from dispatch through its state commit.
Xray organises its evidence around epochs.

## Why did this view run?

A selected view occurrence usually has one of these causes:

| Cause | Meaning | Typical response |
| --- | --- | --- |
| Its own reads changed | A subscription read by this view produced a new value | Check whether the read belongs lower in the tree or whether the subscription is too coarse |
| Props changed | The parent supplied unequal props | Inspect keys, prop identity, and whether the parent owns too much work |
| Context changed | A React context consumed by the view changed | Trace the provider; vendor theme providers often appear here |
| A host forced the update | A foreign host caused the child to run | Inspect the host's props and contract |
| Retry or abandoned attempt | React retried or discarded work | This may be expected, especially under development StrictMode |

When several views run for one event, fan-out distinguishes a topology problem
from independent useful work. One changed subscription reaching hundreds of
readers deserves attention. Hundreds of unrelated reads changing together may
be the intended update.

Do not collapse render, commit, and paint into one number:

- a body can run speculatively;
- React decides what commits;
- the browser decides when it paints.

Xray labels which stage each measurement belongs to. Timing proximity alone
does not prove causation.

The causal chain is:

```text
event
  → subscriptions recomputed
  → values changed
  → views notified
  → bodies run
  → React commit
  → browser paint
```

Xray correlates links only where an instrument can support the relationship.
When it cannot, it reports that limitation instead of guessing.

## Read topology and fan-out

The standing view maps subscriptions to the currently committed views that
read them. Four measurements usually identify the shape:

| Measurement | What it reveals |
| --- | --- |
| View count | Number of independently re-rendering units currently mounted |
| Reads per view | Fine-grained, coarse, or accidentally enormous read sets |
| Fan-out per subscription | Number of views one changed value can notify |
| Read-set churn | How often a view changes which subscriptions it reads |

Two common failures:

- One subscription fans out to hundreds of views because a shared read lives
  too high or the read model is too broad.
- A view changes its read membership every render and pays to replace the
  complete committed read set each time.

Move reads, change view boundaries, or choose fine, coarse, chunked, or
windowed collection reads as described in
[Views and reads](02-views-and-reads.md) and
[Lists and collections](06-lists-and-collections.md).

## Use attribution before choosing a fix

The hot-view advisor ranks views by time, frequency, read churn, and fan-out.
It first identifies where the time is going:

| Pressure | Cost owner | Smallest credible fix |
| --- | --- | --- |
| Computation | View code or an expensive subscription chain | Move or reduce the computation; derive display values in subscriptions |
| Topology | Too many invalidated views or unstable read sets | Move reads, split or combine views, or change collection read shape |
| Hiccup lowering | Turning one hot view's Hiccup into React elements | Return `n/$` directly from that same view ([The native tier](10-native-tier.md)) |
| React | Reconciliation, hooks, or vendor internals | Use a named native island or UIx component |
| Layout and paint | Browser style, layout, and rendering | Reduce DOM, virtualise, or fix CSS; use browser tooling |

The advisor recommends a native escape only when native code addresses the
measured owner. If lowering is 4% of an interaction, a lowering escape cannot
recover more than that 4%.

Xray recommends; it does not rewrite or promote code automatically. Any native
escape must pass the benefit thresholds in [Performance](18-performance.md).

## Incomplete evidence is reported explicitly

Xray uses named completeness states instead of pretending that missing
evidence is an empty result:

| Label | Meaning | Response |
| --- | --- | --- |
| `:unknown` | No instrument covers the requested relationship | Ask a question the instruments can answer, or encode the claim in a test |
| `:opaque` / `:no-static-analysis` | An interpreted body's facts cannot be enumerated before execution | Run the interaction; current reads come from the body that actually ran |
| `:host-opaque` | The inner React tree is hidden behind a host or native crossing | Xray still names and times the crossing; inspect its internals with React DevTools |
| `:cap` | The bounded history has dropped older evidence | Reproduce and capture a fresh epoch |
| `:uncorrelated` | The event-to-render relationship could not be established | Treat it as an honest absence and reproduce with a scripted interaction |

## Complaint IDs

Hicasso errors and warnings use stable identifiers:

- `:rf.error/*` for errors;
- `:rf.warning/*` for recoverable misuse.

A thrown error places the id in `ex-data` under `:rf.error/id`. Trace records
use the same id and include the recovery the runtime applied. Xray can link to
the registration site and the call site when source data is available.

Examples from the guide:

| ID | Meaning | Recovery |
| --- | --- | --- |
| `:rf.error/hicasso-sub-outside-render` | A subscription read ran after every render context had ended | Read during the body and close over the value; handlers declare state as coeffects |
| `:rf.error/hicasso-deferred-read-at-boundary` | An unforced `delay` carrying a read tried to leave a view | Force it in the body or pass the realised value |
| `:rf.error/hicasso-bad-head` | A plain `defn` appeared in Hiccup head position | Call it inline or define a view with `h/defview` |
| `:rf.error/hicasso-intent-outside-boundary` | An event intent reached a position with no frame | Keep it under a view boundary; use `h/event` at a foreign callback edge |
| `:rf.error/hicasso-host-unclaimed-callback` | `h/event` was passed to a host prop with no callback contract | Declare the prop in `:callbacks` |
| `:rf.error/hicasso-revision-not-controlled` | `::h/revision` appeared on a non-controlled field | Control the text field or remove the revision |
| `:rf.warning/hicasso-missing-key` | A sequence child has no `:key` | Put a stable key in each member's props map |
| `:rf.error/frame-destroyed` | An operation captured from a destroyed frame incarnation fired later | Drop the stale handle and capture from the current frame |

Follow the named recovery before changing unrelated code.

When testing a refusal, assert the stable id rather than the message:

```clojure
(defn badge [_]
  [:span.badge "hi"])

(defn card [_]
  [:div.card
   [badge {}]])

(defn refusal-id [f]
  (try
    (f)
    ::did-not-throw
    (catch :default e
      (:rf.error/id (ex-data e)))))

(deftest plain-defn-child-head-refuses
  (is (= :rf.error/hicasso-test-plain-fn-head
         (refusal-id
          #(ht/tree [card {}] {:subs {}})))))
```

Error messages may improve between releases. IDs are part of the stable
complaint contract.

At L2, the test kit accepts a plain body function as the **root** because that
is the function it is deliberately running. A plain function reached as a
child head raises `:rf.error/hicasso-test-plain-fn-head`. The equivalent
mounted mistake raises the runtime's `:rf.error/hicasso-bad-head`.

## Verify production erasure

Xray, its evidence producer, projections, advisor, development checks, source
locations, and message strings are removed from a release build. Production
evidence queries return `nil`. Performance instrumentation has a separate
compile-time flag and is off by default.

Verify erasure with a positive control:

```bash
npx shadow-cljs compile app
grep -c "rf.xray" public/js/main.js    # expect more than 0

npx shadow-cljs release app
grep -c "rf.xray" public/js/main.js    # expect 0
```

The development search must find the sentinel. Zero in both files means the
search is ineffective, not that production erasure has been demonstrated.
This is the sabotage-control principle from [Testing](14-testing.md).

Application behaviour must never depend on diagnostics. Do not branch on
whether evidence exists, count warnings as product data, or read panel state
from application code.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A view you expected is absent from the epoch | Its props and reads allowed it to skip; a body that did not run emits no occurrence | Treat absence as work avoided. Inspect the parent occurrence when you expected different props |
| Explain-render returns `:uncorrelated` | The event-to-render link could not be established | Reproduce with a scripted interaction and inspect a fresh epoch |
| A foreign subtree shows `:host-opaque` | Raw React internals are not visible beyond the crossing | Use Xray for the crossing and React DevTools for its inner tree |
| History ends with `:cap` | The bounded retention window discarded old epochs | Reproduce the issue and capture it again |
| The advisor will not recommend a native island | The measured owner is not a cost native code fixes | Apply the smaller remedy it names and re-measure |
| Repeated runs have different timings | Xray timing is diagnostic attribution, not a controlled benchmark | Use the cost classification; benchmark under [Performance](18-performance.md) |
| A complaint id has no catalogue entry | The id belongs to another namespace, or application and test-kit versions differ | Check the namespace and align installed versions |
| Panels are empty in a release build | Diagnostics were erased as designed | Diagnose with a development build |

## When Xray is not the right tool

Xray identifies the cost owner and the likely class of remedy. It does not
answer whether an interaction meets a production budget. Use the measurement
method in [Performance](18-performance.md) for that.

Use React DevTools for commit-level React details and browser performance tools
for layout and paint. Profile release builds for production slowness; a
development build intentionally contains development work.

When the question is correctness rather than cause, write a test
([Testing](14-testing.md)).

## Advanced

### Explain-render envelope

The panel, tests, and AI pair use the same versioned evidence envelope. A
representative occurrence:

```clojure
{:view         todo.views/todo-row
 :frame        :app/main
 :cause        {:kind          :reads
                :changed-reads [[:todo/by-id 7]]}
 :attempt      :committed
 :reads        [[:todo/by-id 7]]
 :fan-out      1
 :completeness :complete
 :loss         nil}
```

The full envelope also identifies its schema, producer, operation, scope, and
basis. Most developers do not need the raw map; it matters for scripted
diagnosis and assertions.

### Privacy projection

Xray and the AI pair consume the same privacy-projected evidence schema.
Query arguments pass through the projector. Raw values and text are omitted by
default. Data leaves the process only through an authorised consumer.

### Optional-module evidence

Installed modules can add bounded projections:

- forms: draft ownership;
- overlays: active top-layer regions;
- motion: transition posture;
- resources: live demand.

An unused or uninstalled module contributes no projection.

### Bounded retention

Xray owns a fixed history budget. The trace ring is the record, and named
operations may retain bounded, commit-owned identity for correlation. There is
no unbounded occurrence ledger. `:cap` is the visible boundary of that choice.
