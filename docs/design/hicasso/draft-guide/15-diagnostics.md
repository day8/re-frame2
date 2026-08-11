# Diagnostics

A list re-rendered and you do not know why. A keystroke feels slow and you do
not know where the time went. Xray is the development-time instrument for
those questions. It loads with your dev build as a preload, mounts beside the
app, and observes the same trace the runtime already emits. Nothing in your
views changes to support it, and none of it ships to production.

## How to diagnose

1. **Load Xray** with your dev preload so it mounts beside the app.
2. **Reproduce** the slow click or unexpected re-render.
3. **Select the boundary** that ran — the independently re-rendering view
   ([Views and reads](02-views-and-reads.md)).
4. **Read its cause and fan-out.**

That is the whole operational path. The rest of this page explains what those
panels mean and what to do with the answer.

An *epoch* is one pipeline run: one dispatched event carried through to its
commit. Xray organises evidence around epochs.

## Read the cause

Select a boundary occurrence and ask: *why did this view run?*

Typical answers:

| Cause | Meaning | What you usually do |
| --- | --- | --- |
| Own reads moved (queries named) | A subscription this view used changed | Check whether the read should live lower, or whether the sub is coarser than needed |
| Props changed | Parent passed different props | Check keys, identity of props maps, and whether the parent re-rendered too high |
| Context changed | A React context this view consumes changed | Trace the provider; vendor themes often land here |
| Host forced it | A host boundary forced a child | Check the host's props and whether the force is required |
| Retry / abandoned attempt | React retried or abandoned work | Not always a bug — StrictMode doubles in development |

When several boundaries ran for one event, **fan-out** tells you which case you
have. One changed subscription that reaches many readers is a topology problem.
Many independent causes are ordinary work.

**Render is not commit, and commit is not paint.** Bodies run speculatively.
React owns commit. The browser owns paint. Xray tells you which claim each
number makes. Timing proximity alone proves nothing: a body that ran near an
event may have run for a different reason.

The chain Xray follows:

```
event → subscriptions recomputed → values changed → boundaries notified
     → bodies run → React commit → paint
```

Xray correlates links only when its instruments support the correlation. When
they do not, it labels the link instead of guessing.

## Read attribution

The standing view is the map from subscriptions to the boundaries that currently
read them. Four numbers do most of the diagnostic work:

| Number | What it tells you |
| --- | --- |
| Boundary count | how many independent re-render units are mounted |
| Reads per boundary | fine, coarse, or accidentally enormous read sets |
| Fan-out per subscription | how many boundaries one change will reach |
| Read-set churn | how often a boundary's read set changes membership |

Two shapes deserve attention:

- One subscription that fans out to hundreds of boundaries usually means a
  read that lives too high, or a shared value that needs a deliberate
  topology choice.
- A boundary whose read set churns every render pays a whole-set refresh each
  time, because the edge set is a function of what the body did.

Remedies — reads at the point of use, boundary placement, fine versus coarse
versus chunked reads — live in [Views and reads](02-views-and-reads.md) and
[Lists and collections](06-lists-and-collections.md). Xray makes the topology
visible.

## The hot-view advisor

The advisor ranks boundaries by time, frequency, read churn, and fan-out, then
**classifies the pressure** before it names a remedy:

| Pressure | Time is going to | Smallest credible remedy |
| --- | --- | --- |
| Computation | your own code — the body's work or an expensive sub chain | fix the computation; derive it in the subscription layer |
| Topology | too many boundaries invalidated, or churning read sets | move reads, split or merge boundaries, coarse / chunked / windowed reads ([Lists and collections](06-lists-and-collections.md)) |
| Lowering | turning one hot boundary's Hiccup into React elements | a direct [`n/$`](glossary.md#n-dollar) return from that same boundary ([Native tier](10-native-tier.md)) |
| React | reconciliation, hooks, vendor component internals | a named [native island](glossary.md#native-island) — [`n/defcomponent`](glossary.md#ndefcomponent) or UIx ([Native tier](10-native-tier.md)) |
| Layout | the browser — style, layout, paint | shrink the DOM, virtualize, fix the CSS; browser tools own this ground |

The advisor recommends a native escape only when the measured owner is a class
that native addresses. If lowering is four percent of a slow interaction,
extraction cannot buy more than four percent. The advisor says so and points
at the real owner. There is no automatic promotion — the advisor recommends,
you decide. An escape you take must meet the thresholds in
[Performance](18-performance.md), or it comes back out.

## When evidence is incomplete

An interpreted runtime cannot know some facts. Xray reports that limit as an
answer; it does not present an empty panel. Unknown is never encoded as an
empty collection.

| Label | Meaning | What you do |
| --- | --- | --- |
| `:unknown` | no instrument covers this link | ask a question the instruments answer; if the claim matters, make it a test |
| `:opaque` / `:no-static-analysis` | an interpreted body's facts cannot be enumerated ahead of execution | run the interaction — current reads come from actual execution |
| `:host-opaque` | raw React internals past a host or native crossing | Xray still names and times the crossing; React DevTools owns the inner tree |
| `:cap` | the bounded retention window has dropped older history | reproduce and capture fresh |
| `:uncorrelated` | the event-to-render relationship could not be established | treat as honest absence; reproduce with a scripted interaction so the links line up |

## The complaint catalogue

Every refusal in this guide carries a stable id: `:rf.error/*` for errors,
`:rf.warning/*` for recoverable misuse. When thrown, the id is in `ex-data`
under `:rf.error/id`. On the trace it is a record with the id as category and
the recovery the runtime took. In Xray it renders with jump-to-source for the
registration site and the call site.

A sample of entries from earlier chapters:

| Id | Complaint | Recovery |
| --- | --- | --- |
| `:rf.error/hicasso-sub-outside-render` | a read escaped every render context | read during the body; in a handler, declare the fact as a coeffect ([Views and reads](02-views-and-reads.md)) |
| `:rf.error/hicasso-deferred-read-at-boundary` | an unforced `delay` carrying a read tried to leave the view | force it in the body; close over the value ([Views and reads](02-views-and-reads.md)) |
| `:rf.error/hicasso-bad-head` | a plain `defn` in head position | define it with [`h/defview`](glossary.md#defview), or call it inline ([Views and reads](02-views-and-reads.md)) |
| `:rf.error/hicasso-intent-outside-boundary` | an [intent](glossary.md#intent) vector reached a position with no boundary frame | dispatch belongs inside a boundary; at a foreign edge, use [`h/event`](glossary.md#hevent) ([Events as data](03-events-as-data.md)) |
| `:rf.error/hicasso-host-unclaimed-callback` | an [`h/event`](glossary.md#hevent) arrived at a host prop that no callback contract claims | declare the prop in `:callbacks`; at a ReactNode slot, write markup ([Interop](09-interop.md)) |
| `:rf.error/hicasso-revision-not-controlled` | [`::h/revision`](glossary.md#hrevision) on a field that is not controlled | control the field, or drop the revision ([Controlled inputs](04-controlled-inputs.md)) |
| `:rf.warning/hicasso-missing-key` | a seq of boundary children without `:key` | put `:key` in each child's props map ([Lists and collections](06-lists-and-collections.md)) |
| `:rf.error/frame-destroyed` | an operation captured against a destroyed frame incarnation fired after its successor seated | drop the stale handle; capture from the live frame ([Events as data](03-events-as-data.md)) |

When a complaint surprises you, follow its recovery before you change code.
When you test a refusal, assert the id, never the message:

```clojure
;; ht is the test kit from 14-testing.md
(defn badge [_props] [:span.badge "hi"])       ;; a plain defn — not a view
(defn card  [_props] [:div.card [badge {}]])   ;; …and here it is, in a head

(defn refusal-id [f]
  (try (f) ::did-not-throw
       (catch :default e (:rf.error/id (ex-data e)))))

(deftest plain-defn-child-head-refuses
  (is (= :rf.error/hicasso-test-plain-fn-head
         (refusal-id #(ht/tree [card {}] {:subs {}})))))
```

Message text improves between releases; ids are frozen.

`ht/tree`'s root form is headed by the body function the kit is about to run,
so `[badge {}]` written as the root form is accepted — it runs, and nothing
raises. Only a plain `defn` reached *inside* the tree is a mistake. At L2 the
kit answers with `:rf.error/hicasso-test-plain-fn-head`; the same child mounted
at L3 raises the runtime's `:rf.error/hicasso-bad-head`.

## Production erasure

Everything on this page is development tooling, and all of it erases from a
release build: the evidence producer, the projections, the advisor, the
dev-only checks, and their message strings. Production evidence queries return
nil. A release bundle contains no evidence machinery, no schema sentinels, and
no source-location tables. Performance collection is gated separately by its
own compile-time flag and is off by default.

Do not accept erasure without a check. Pick a sentinel you can see in the dev
bundle (`rf.xray` is a good default). Search both builds:

```bash
npx shadow-cljs compile app            # dev build
grep -c "rf.xray" public/js/main.js    # positive control: expect > 0

npx shadow-cljs release app            # release build, same output path
grep -c "rf.xray" public/js/main.js    # expect 0
```

The dev-build search is the positive control. Zero on the release bundle means
"erased" only if the same search finds the sentinel in the dev bundle. Zero in
both places means the search is broken, not the build. This is the
sabotage-twin habit from [Testing](14-testing.md) applied to the bundle.

Application logic must never depend on the surface: no feature may read
evidence, count complaints, or branch on a panel's data.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A view you know ran is absent from the epoch | Its boundary bailed out — a skipped body emits nothing | Absence measures work not done; if you expected it to run, explain-render the parent that did |
| Explain-render answers `:uncorrelated` | Event-to-render link could not be established for that occurrence | Honest answer, not a failure; reproduce with a scripted interaction and read the fresh epoch |
| A foreign subtree shows `:host-opaque` | Raw React internals are not enumerable past the crossing | Xray still names and times the crossing; open React DevTools for the inside |
| History stops with `:cap` | Bounded retention window dropped older epochs | Reproduce and capture fresh |
| Advisor will not recommend an island you want | Measured owner is not a class native addresses | Do the smaller remedy it named; re-measure ([Performance](18-performance.md)) |
| Two runs of one interaction show different times | Xray timing is attribution, not a benchmark | Treat the classification, not the number, as the finding; benchmark under [Performance](18-performance.md) |
| A complaint id you hit has no catalogue anchor | Id is not from this product's surface, or app and kit versions have drifted | Check the id's namespace and align versions |
| Panels are empty in a release build | Production erasure, working as designed | Diagnose on a dev build |

## When Xray is not the tool

**Xray timing is not a benchmark.** It attributes: which link of the chain owns
the time, and which remedy class is credible. It does not settle "is this fast
enough" — budgets live in [Performance](18-performance.md). Its render numbers
are not commit evidence. React DevTools remains the authority for commits. The
browser's performance tooling remains the authority for layout and paint. When
an interaction is slow in a release build, profile the release build with
browser tools; the dev build carries dev-only work by design.

When the question is about *correctness* rather than cause — does this body
mean what it should, does teardown leak — the answer is a test, not a panel
([Testing](14-testing.md)).

## Advanced

### Explain-render envelope shape

The panel, tests, and the AI pair consume the same envelope. A complete example:

```clojure
{:view         todo.views/todo-row
 :frame        :app/main
 :cause        {:kind          :reads
                :changed-reads [[:todo/by-id 7]]}
 :attempt      :committed          ;; not a retry, not abandoned
 :reads        [[:todo/by-id 7]]   ;; current read set, from execution
 :fan-out      1                   ;; boundaries this change reached
 :completeness :complete
 :loss         nil}
```

Every envelope also states its schema, producer, operation, scope, and basis so
a consumer knows what it holds and which generation of the contract produced
it. You do not need this map to use the panel — open Xray, select the boundary,
read the cause. The shape matters when you script diagnosis or assert on it.

### The same projection feeds the AI pair

Xray and the AI pair consume one versioned, privacy-projected evidence schema:
byte-equivalent projections, one contract. Query arguments pass through the
privacy projector. Raw values and text are omitted by default. Nothing leaves
the process unless an authorized consumer requests it.

### Optional modules bring their own evidence

Evidence follows installation. The forms module contributes draft ownership.
Overlays contribute the active top-layer region. Motion contributes the current
transition posture. Resources contribute the live demand. Each is a bounded
projection that exists only while the module is installed and in use. An app
that uses none of them pays for none of this.

### Retention is bounded on purpose

Xray owns its history budget. The trace ring is the record; a named operation
(mount/unmount correlation, for example) may keep bounded, commit-owned
identity. No universal occurrence ledger accumulates in the background. The
`:cap` label is the visible edge of that choice. The cost is bounded history;
the alternative is a runtime that slows down in proportion to how long you have
debugged it.
