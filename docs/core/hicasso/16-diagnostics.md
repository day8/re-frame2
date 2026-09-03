# Diagnostics

Use Xray when a view re-renders unexpectedly or an interaction feels slow and
you need to find the cause.

Xray is development tooling. It loads beside the application, reads the trace
the runtime already emits, and requires no instrumentation in your views. It
is removed from production builds.

## Diagnose an interaction

1. Load Xray through the development preload. [The coordinate, the host
   element, and the preload namespace](#load-xray) are below.
2. Reproduce the click, keystroke, or update.
3. Open Xray's **Hicasso** tab and select the view occurrence that ran.
4. Read its cause, fan-out, and attribution.

Steps 3 and 4 — and the read topology, advisor, and explain-render sections
below — all happen in one place: Xray's **Hicasso** tab, in Dynamic mode. [11.
The Hicasso tab](../../xray/11-hicasso-tab.md) is its reference chapter: what
each of its views asks, how to read an empty one, and why the advisor refuses to
recommend a native route. This chapter covers the same ground from the
application side — which cause you are looking at, which pressure owns it, and
what to change.

An **epoch** is one event pipeline run, from dispatch through its state commit.
Xray organises its evidence around epochs.

### Load Xray

Xray is a tool, not application code: the dependency belongs in a dev alias, and
the preload belongs in the dev build, never in a release build.

While re-frame2 is pre-alpha the dependency is a checkout-local one, resolved
relative to your own `deps.edn`. It becomes an ordinary Maven coordinate once
Xray is published.

```clojure
;; deps.edn
{:aliases
 {:dev
  {:extra-deps
   {day8/re-frame2-xray {:local/root "../re-frame2/tools/xray"}}}}}
```

The preload namespace is `day8.re-frame2-xray.preload`:

```clojure
;; shadow-cljs.edn
{:builds
 {:app
  {:devtools
   {:preloads [day8.re-frame2-xray.preload]}}}}
```

Xray renders into a host element your page reserves, marked
`data-rf-xray-host`:

```html
<div class="app-shell">
  <main id="app"></main>
  <aside data-rf-xray-host></aside>
</div>
```

That is the whole setup. The preload registers the collectors, installs the
browser API, and opens Xray into the host once the substrate adapter is ready,
so you do not call `init!` yourself. `Ctrl+Shift+C` hides and shows the panel.

[Xray's installation chapter](../../xray/01-installation.md) is the reference
for the rest: styling the host, choosing a different selector, jump-to-source
editor configuration, popping out to a second window, and production posture.

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

The Hicasso tab's **Reads** view maps subscriptions to the currently committed
views that read them. Four measurements usually identify the shape:

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

The Hicasso tab's **Advisor** view ranks views by time, frequency, read churn,
and fan-out. It first identifies where the time is going:

| Pressure | Cost owner | Smallest credible fix |
| --- | --- | --- |
| Computation | View code or an expensive subscription chain | Move or reduce the computation; derive display values in subscriptions |
| Topology | Too many invalidated views or unstable read sets | Move reads, split or combine views, or change collection read shape |
| Hiccup lowering | Turning one hot view's Hiccup into React elements | Return a React element directly from that same view ([Islands](10-native-tier.md)) |
| React | Reconciliation, hooks, or vendor internals | Use a React island, raw or UIx ([Islands](10-native-tier.md)) |
| Layout and paint | Browser style, layout, and rendering | Reduce DOM, virtualise, or fix CSS; use browser tooling |

Only the first two rows of that table are instrumented. The advisor measures
computation from the retained subscription ring's elapsed times and derives
topology from the cell table's fan-out and the entry cache's read orders.
Hiccup lowering, React, and layout and paint it **names but cannot measure**,
and it says so rather than ranking on a clock it does not have. That absence is
a decision rather than a gap: Chrome clamps its timer to a 0.1 ms grain while a
boundary body costs single-digit microseconds, so a ranking built on boundary
self time would order noise; and commit, paint, and attempt outcome belong to
React, which reports them as opaque every time.

One consequence is worth stating plainly, because it is the opposite of what a
ranked list of durations suggests: **the advisor never recommends a native
escape.** Not for the hottest boundary on the page. Every native rung addresses
lowering, hooks, or reconciliation, and this evidence cannot say whether any of
them owns the pressure — so recommending one would be an expensive,
semantics-changing change made on evidence that cannot support it.

Treat the last three rows as your own checklist instead, reached through React
DevTools and browser performance tools. Xray recommends; it does not rewrite or
promote code automatically, and any native escape must still pass the benefit
thresholds in [Performance](19-performance.md).

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
| `:rf.error/hicasso-host-unclaimed-callback` | `h/event` was passed to a host prop declared a ReactNode slot | Write markup there, or take the prop out of `:slots` |
| `:rf.error/hicasso-revision-not-controlled` | `::h/revision` appeared on a non-controlled field | Control the text field or remove the revision |
| `:rf.warning/hicasso-entity-key` | A boundary-headed sequence child's `:key` is a map, collection, date or other entity value React would coerce by content | Key on a stable primitive identifier |
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
This is the sabotage-control principle from [Testing](15-testing.md).

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
| Repeated runs have different timings | Xray timing is diagnostic attribution, not a controlled benchmark | Use the cost classification; benchmark under [Performance](19-performance.md) |
| A complaint id has no catalogue entry | The id belongs to another namespace, or application and test-kit versions differ | Check the namespace and align installed versions |
| Panels are empty in a release build | Diagnostics were erased as designed | Diagnose with a development build |

## When Xray is not the right tool

Xray identifies the cost owner and the likely class of remedy. It does not
answer whether an interaction meets a production budget. Use the measurement
method in [Performance](19-performance.md) for that.

Use React DevTools for commit-level React details and browser performance tools
for layout and paint. Profile release builds for production slowness; a
development build intentionally contains development work.

When the question is correctness rather than cause, write a test
([Testing](15-testing.md)).

## Advanced

### Explain-render envelope

The Hicasso tab's **Why** view, tests, and an AI pair all read one versioned
evidence envelope, produced by
[`re-frame.hicasso.tool/explain-render`](api-reference.md#re-framehicassotool).
Read it through the Why view; calling the reader yourself is for scripted
diagnosis, and every read on that door answers `nil` in a release build. A
representative occurrence:

```clojure
{:boundary     {:parent nil :key [[:app/main :todo/by-id [:todo/by-id 7]]]}
 :views        [{:view   "todo.views/todo-row"
                 :source {:ns todo.views :file "src/todo/views.cljs" :line 41 :column 1}}]
 :frame        :app/main
 :instances    1
 :window       {:frames [:app/main] :retained-runs 12}
 :snapshot     9
 :peak-epoch   5
 :latest-reads [{:sub-id :todo/by-id :query [:todo/by-id 7] :frame-id :app/main}]
 :loss         {:reason :uncorrelated :dropped :unknown}
 :candidates   [{:dispatch-id 41 :event-id :todo/toggle :frame-id :app/main :sub-id :todo/by-id}]}
```

`:latest-reads` is the proven half — the reads whose values moved most
recently, off the cells' own epoch stamps. `:candidates` are leads, never a
cause: the commit seam records no cascade identity, which is what the row's
`:loss` says. The enclosing envelope identifies its schema, producer and read,
and states its own completeness and loss. Most developers do not need the raw
map; it matters for scripted diagnosis and assertions.

### Privacy projection

Xray and the AI pair consume the same privacy-projected evidence schema.
Query arguments pass through the projector. Raw values and text are omitted by
default. Data leaves the process only through an authorised consumer.

### Optional-module evidence

Installed modules can add bounded projections:

- forms: draft ownership;
- overlays: active top-layer regions;
- motion: transition posture;
- resources: which owners hold an entry, and the cause that started each fetch.

An unused or uninstalled module contributes no projection.

### Bounded retention

Xray owns a fixed history budget. The trace ring is the record, and named
operations may retain bounded, commit-owned identity for correlation. There is
no unbounded occurrence ledger. `:cap` is the visible boundary of that choice.
