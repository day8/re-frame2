# Diagnostics

Use Xray when a view re-renders unexpectedly or an interaction feels slow and
you need to find the cause.

Xray is development tooling. It loads beside the application, reads the trace
the runtime already emits, and requires no instrumentation in your views. It
is removed from production builds.

## Diagnose an interaction

1. Load Xray through the development preload ([Load Xray](#load-xray)).
2. Reproduce the click, keystroke, or update.
3. Open Xray's **Fresco** tab and pick the view that matches your question:
   Mounted, Reads, Intents, Why, Advisor, or Causal.
4. Read the cause, fan-out, and cost it reports.

Everything below happens in the Fresco tab, in Dynamic mode. The Causal view
follows whichever dispatch is selected in Xray's event list.
[The Fresco tab](../../xray/11-fresco-tab.md) documents each view and how to
read an empty one. This chapter covers the application side: what the cause
is and what to change.

An **epoch** is one event pipeline run, from dispatch through its state commit.
Xray organises its evidence around epochs.

### Load Xray

Put the dependency in a dev alias and the preload in the dev build, never in a
release build. While re-frame2 is pre-alpha the dependency is a checkout-local
one, relative to your own `deps.edn`.

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

That is the whole setup. The preload opens Xray into the host once the adapter
is ready, so you do not call `init!` yourself. `Ctrl+Shift+C` hides and shows
the panel. [Xray's installation chapter](../../xray/01-installation.md) covers
styling the host, a different selector, jump-to-source, and popping out to a
second window.

## Why did this view run?

The Fresco tab's **Why** view answers this for one view at a time. It separates
what it can prove from what it can only suggest:

| What the row says | Meaning | Typical response |
| --- | --- | --- |
| Its own reads moved | `:latest-reads`: the view's subscriptions whose values changed most recently | Check whether the read belongs lower in the tree, or whether the subscription is too coarse |
| Leads, not a cause | `:candidates`: recent dispatches that recomputed a subscription this view reads | Places to look; Fresco records no link from a commit to the dispatch that caused it |
| Nothing was searched | `:cap`: no dispatch is retained in the frames this view reads, or the view reads nothing | Reproduce the interaction and check again. Retention matters only if it was set to `0`; `(rf/configure! {:trace-buffer {:events-retained 50}})` restores the default |

Props, context, a parent host, a retried or discarded render, a bail-out, the
commit and the paint are not causes Fresco records. Use the React DevTools
Profiler for the run and commit, and the browser's performance tools for the paint.

### The causal chain

Do not collapse render, commit, and paint into one number:

- a body can run speculatively;
- React decides what commits;
- the browser decides when it paints.

Xray labels which stage each measurement belongs to. Timing proximity alone
does not prove causation.

The **Causal** view walks one dispatch along this chain:

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

The Fresco tab's **Reads** view maps subscriptions to the currently committed
views that read them. Four measurements usually identify the shape:

| Measurement | What it reveals |
| --- | --- |
| View count | Number of independently re-rendering units currently mounted |
| Reads per view | Fine-grained, coarse, or accidentally enormous read sets |
| Fan-out per subscription | Number of views one changed value can notify |
| Read-set churn | How often a view changes which subscriptions it reads |

When several views run for one event, fan-out distinguishes a topology problem
from independent useful work. One changed subscription reaching hundreds of
readers deserves attention. Hundreds of unrelated reads changing together may
be the intended update.

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

The Fresco tab's **Advisor** view ranks views by time, frequency, read churn,
and fan-out. It first identifies where the time is going:

| Pressure | Cost owner | Smallest credible fix |
| --- | --- | --- |
| Computation | View code or an expensive subscription chain | Move or reduce the computation; derive display values in subscriptions |
| Topology | Too many invalidated views or unstable read sets | Move reads, split or combine views, or change collection read shape |
| Hiccup conversion | Turning one hot view's Hiccup into React elements | Return a React element directly from that same view ([Islands](10-native-tier.md)) |
| React | Reconciliation, hooks, or vendor internals | Use a React island, raw or UIx ([Islands](10-native-tier.md)) |
| Layout and paint | Browser style, layout, and rendering | Reduce DOM, virtualise, or fix CSS; use browser tooling |

The advisor measures only the first two rows: computation from subscription
timings, and topology from fan-out, read order and recompute counts. It names
the other three but does not rank them. Chrome's timer has a 0.1 ms grain while
a view body costs a few microseconds, so per-view time would be noise, and
commit and paint belong to React.

So the advisor never recommends a native escape, even for the hottest view on
the page: its evidence cannot show that Hiccup conversion or React owns the
cost. Check the last three rows yourself, each with its own tool: the
`rf:render:<view-id>` User Timing measures for Hiccup conversion (off by
default; see [Performance](19-performance.md#rf-user-timing)), the React
DevTools Profiler for React, and browser performance tools for layout and
paint. Any native escape must still pass the thresholds in
[Performance](19-performance.md).

## Incomplete evidence is reported explicitly

Xray uses named completeness states instead of pretending that missing
evidence is an empty result:

| Label | Meaning | Response |
| --- | --- | --- |
| `:unknown` | No instrument covers the requested relationship | Ask a question the instruments can answer, or encode the claim in a test |
| `:opaque` | The runtime does not record this fact, by design | Ask a question the instruments do hold |
| `:host-opaque` | React owns this fact and does not publish it (commit and paint for every view) | Use React DevTools and the browser performance tools |
| `:cap` | The bounded history has dropped older evidence | Reproduce and capture a fresh epoch |
| `:uncorrelated` | The fact is real, but no id links it to a cause | A rerun does not change it; read the leads offered and confirm the link yourself |

## Complaint IDs

Fresco errors and warnings use stable identifiers:

- `:rf.error/*` for errors;
- `:rf.warning/*` for recoverable misuse.

A thrown error places the id in `ex-data` under `:rf.error/id`. Fresco's own
errors are throws, and its warnings are development console lines, printed once
per site with the id in the message. Core ids such as `:rf.error/frame-destroyed`
also appear as trace records, carrying the recovery the runtime applied. Xray can link to
the registration site and the call site when source data is available.

[Troubleshooting](troubleshooting.md#the-complaint-index) lists every Fresco id
with its cause and fix.

Follow the named recovery before changing unrelated code.

When testing a refusal, assert the stable id rather than the message:

```clojure
(ns todo.refusal-test
  (:require [cljs.test :refer [deftest is]]
            [re-frame.fresco.test :as ht]))

(defn todo-count [_]
  [:span.todo-count "3 left"])

(defn footer [_]
  [:footer
   [todo-count {}]])

(defn refusal-id [f]
  (try
    (f)
    ::did-not-throw
    (catch :default e
      (:rf.error/id (ex-data e)))))

(deftest plain-defn-child-head-refuses
  (is (= :rf.error/fresco-test-plain-fn-head
         (refusal-id
          #(ht/tree [footer {}] {:subs {}})))))
```

Messages may change between releases; ids do not.

The test kit accepts a plain function as the root of `ht/tree`, because that is
the body it is running. A plain function as a child head raises
`:rf.error/fresco-test-plain-fn-head`; the same mistake in a mounted tree
raises `:rf.error/fresco-bad-head`.

## Verify production erasure

A release build removes Xray, Fresco's evidence collection, development
warnings and source locations. An error that can still fire in production keeps
its id and message. Evidence queries return `nil` in production. Performance instrumentation has a separate compile-time flag and is
off by default.

Verify erasure with a positive control:

```bash
npx shadow-cljs compile app
grep -c "rf.xray" public/js/main.js    # expect more than 0

npx shadow-cljs release app
grep -c "rf.xray" public/js/main.js    # expect 0
```

The development build must show a count above 0. Zero in both builds means the search is
broken, not that erasure worked (the sabotage-twin idea from
[Testing](15-testing.md)).

Application behaviour must never depend on diagnostics. Do not branch on
whether evidence exists, count warnings as product data, or read panel state
from application code.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A view you expected is absent from the epoch | Its props and reads allowed it to skip; a body that did not run emits no occurrence | Treat absence as work avoided. Inspect the parent occurrence when you expected different props |
| Explain-render returns `:uncorrelated` | Fresco records no link from a commit to the dispatch that caused it | Expected; a bigger history does not change it. Work from the `:candidates` leads |
| Every view reports `:host-opaque` after its body ran | React owns commit and paint for all views | Expected. Use React DevTools for the run and the commit, browser tools for the paint |
| History ends with `:cap` | The bounded retention window discarded old epochs | Reproduce the issue and capture it again |
| The advisor will not recommend a native island | It cannot measure the costs native code fixes | Apply the remedy it names, or measure with the tool it points to |
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

The **Why** view, tests, and an AI pair all read one versioned evidence map,
produced by
[`re-frame.fresco.tool/explain-render`](api-reference.md#re-framefrescotool).
Call it yourself only for scripted diagnosis; it returns `nil` in a release
build. A representative entry:

```clojure
{:boundary     {:parent nil :key [[:app :todo/by-id [:todo/by-id 7]]]}
 :views        [{:view   "todo.views/todo-row"
                 :source {:ns todo.views :file "src/todo/views.cljs" :line 41 :column 1}}]
 :frame        :app
 :instances    1
 :window       {:frames [:app] :retained-runs 12}
 :snapshot     9
 :peak-epoch   5
 :latest-reads [{:sub-id :todo/by-id :query [:todo/by-id 7] :frame-id :app}]
 :loss         {:reason :uncorrelated :dropped :unknown}
 :candidates   [{:dispatch-id 41 :event-id :todo/toggle :frame-id :app :sub-id :todo/by-id}]}
```

`:latest-reads` is proven: the reads whose values changed most recently.
`:candidates` are leads, not a cause, because Fresco records no link from a
commit to its dispatch; `:loss` says so. The enclosing map also names its
schema version and states its own completeness.

### Privacy projection

Xray and the AI pair consume the same privacy-projected evidence schema.
Query arguments pass through the projector. Raw values and text are omitted by
default. Data leaves the process only through an authorised consumer.

### Optional-module evidence

The resources module adds its own tooling view: which owners hold an entry, and
the cause that started each fetch. The forms, overlay and motion modules add no
evidence of their own.
