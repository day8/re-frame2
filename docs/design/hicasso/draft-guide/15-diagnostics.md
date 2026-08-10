# Diagnostics

A list re-rendered and you do not know why. A keystroke feels slow and you
do not know where the time went. Xray is the development-time instrument for
those questions. It loads with your dev build as a preload, mounts beside
the app, and observes the same trace the runtime already emits. Nothing in
your views changes to support it, and none of it ships to production.

> **A labeled gap is an answer. An empty panel would be a lie.**

Xray answers the questions you ask:

1. What ran or committed in this epoch — mounted, hidden, suspended, recently
   committed?
2. Why did this [boundary](glossary.md#boundary) run — which reads changed, and what was their fan-out?
3. Was the cause props, context, a read-set change, a retry, abandonment, an
   error, or a host?
4. Where is the pressure — computation, topology, [lowering](glossary.md#lowering), React, or layout —
   and what remedy is credible?
5. What is unknown, capped, opaque, or uncorrelated?

An *epoch* is one pipeline run: one dispatched event carried through to its
commit. The epoch is the unit that Xray organises evidence around.

## The [causal lens](glossary.md#causal-lens)

Every diagnosis follows one chain:

```
event -> subscriptions recomputed -> values changed -> boundaries notified
      -> bodies run -> React commit -> paint
```

Each link has its own evidence seam, and Xray never blurs them. Timing
proximity proves nothing. A body that ran near an event may have run for a
different reason. A render measure may be a retry, a StrictMode duplicate,
or work that React abandoned before commit. Xray correlates links only when
its instruments support the correlation. When they do not, Xray labels the
link honestly instead of guessing.

Learn this discipline before you read any panel: **render is not commit,
and commit is not paint.** Bodies run speculatively. React owns commit. The
browser owns paint. Xray tells you which claim each number makes.

## Explain-render

Select a [boundary](glossary.md#boundary) occurrence and ask the first question that matters: *why
did this view run?* The answer is data, in the same shape that the whole
evidence surface uses:

```clojure
{:view         todo.views/todo-row
 :frame        :app/main
 :cause        {:kind          :reads
                :changed-reads [[:todo/by-id 7]]}
 :attempt      :committed          ;; not a retry, not abandoned
 :reads        [[:todo/by-id 7]]   ;; the current read set, from execution
 :fan-out      1                   ;; boundaries this change reached
 :completeness :complete
 :loss         nil}
```

The `:cause` kinds are the honest taxonomy of question 3. The possible
causes: the boundary's own reads moved (with the changed queries named);
its props changed; a context it consumes changed; a host boundary forced
it; or React retried or abandoned an attempt. When several [boundaries](glossary.md#boundary) ran
for one event, fan-out tells you which case you have. One changed
subscription that reaches many readers is a topology problem. Many
independent causes are ordinary work.

Every envelope on this surface also states its schema, producer, operation,
scope, and basis. A consumer — the panel, a test, an AI pair — then knows
exactly what it holds and which generation of the contract produced it.

## Read attribution

The complementary standing view is the map from subscriptions to the
[boundaries](glossary.md#boundary) that currently read them: who depends on what, right now. Four
numbers on this view do most of the diagnostic work:

| Number | What it tells you |
|---|---|
| Boundary count | how many independent re-render units are mounted |
| Reads per boundary | fine, coarse, or accidentally enormous read sets |
| Fan-out per subscription | how many boundaries one change will reach |
| Read-set churn | how often a boundary's read set changes membership |

Two shapes deserve attention. One subscription that fans out to hundreds of
boundaries usually means a read that lives too high, or a shared value that
deserves a deliberate topology choice. A boundary whose read set churns
every render pays a whole-set refresh each time, because the edge set is a
function of what the body did. The remedies — reads at the point of use,
boundary placement, fine versus coarse versus chunked reads — are owned by
[Views and reads](02-views-and-reads.md) and
[Lists and collections](06-lists-and-collections.md). Xray's job is to make
the topology observable instead of guessed.

## The [hot-view advisor](glossary.md#hot-view-advisor)

The advisor is the part of Xray that turns evidence into a recommendation.
It ranks [boundaries](glossary.md#boundary) by time, frequency, read churn, and fan-out. Then it
does the step that matters: it **classifies the pressure** before it names
a remedy.

| Pressure | The time is going to | Smallest credible remedy |
|---|---|---|
| Computation | your own code — the body's work or an expensive sub chain | fix the computation; derive it in the subscription layer, where it is a pure function you can test |
| Topology | too many boundaries invalidated, or churning read sets | move reads, split or merge boundaries, coarse or chunked or windowed reads ([Lists and collections](06-lists-and-collections.md)) |
| Lowering | turning one hot boundary's Hiccup into React elements | a direct [`n/$`](glossary.md#n-dollar) return from that same boundary ([Native tier](10-native-tier.md)) |
| React | reconciliation, hooks, vendor component internals | a named [native island](glossary.md#native-island) — [`n/defcomponent`](glossary.md#ndefcomponent) or UIx ([Native tier](10-native-tier.md)) |
| Layout | the browser — style, layout, paint | shrink the DOM, virtualize, fix the CSS; browser tools own this ground |

The advisor never breaks one rule: **it recommends a native escape only
when the measured owner is a class that native addresses.** If [lowering](glossary.md#lowering) is
four percent of a slow interaction, extraction cannot buy more than four
percent. The advisor says so, and points at the real owner instead. There
is no automatic promotion. The advisor recommends; you decide. An escape
you take must meet the thresholds in [Performance](18-performance.md), or
it comes back out.

## Honest [loss labels](glossary.md#loss-labels)

An interpreted runtime cannot know some facts. Xray reports that limit as
an answer; it does not present an empty panel. Unknown is never encoded as
an empty collection.

| Label | It means | What you do |
|---|---|---|
| `:unknown` | no instrument covers this link | ask a question the instruments answer; if the claim matters, make it a test |
| `:opaque` / `:no-static-analysis` | an interpreted body's facts cannot be enumerated ahead of execution | run the interaction — current reads come from actual execution, not prediction |
| `:host-opaque` | raw React internals past a host or native crossing | Xray still names and times the crossing; React DevTools owns the inner tree |
| `:cap` | the bounded retention window has dropped older history | reproduce and capture fresh; retention is deliberately bounded |
| `:uncorrelated` | the event-to-render relationship could not be established | treat it as honest absence; reproduce with a scripted interaction so the links line up |

A dashboard that filled those cells with zeros would be easier to read and
worse to trust. The label tells you which tool to use next.

## The [complaint catalogue](glossary.md#complaint-catalogue)

Every refusal in this guide carries a stable id: `:rf.error/*` for errors,
`:rf.warning/*` for recoverable misuse. Every id is an entry in the
[complaint catalogue](glossary.md#complaint-catalogue) — a docs anchor that states what fired, why it fired,
and the concrete recovery. A complaint is structured data end to end. When
thrown, it carries its id in `ex-data` under `:rf.error/id`. On the trace,
it is a record with the id as its category and the recovery the runtime
took. In Xray, it renders with jump-to-source for both the registration
site and the call site.

A sample of entries from earlier chapters:

| Id | Complaint | Recovery |
|---|---|---|
| `:rf.error/hicasso-sub-outside-render` | a read escaped every render extent | read during the body; in a handler, declare the fact as a coeffect ([Views and reads](02-views-and-reads.md)) |
| `:rf.error/hicasso-deferred-read-at-boundary` | an unforced `delay` carrying a read tried to cross a [boundary](glossary.md#boundary) | force it in the body; close over the value ([Views and reads](02-views-and-reads.md)) |
| `:rf.error/hicasso-bad-head` | a plain `defn` in head position | mint it with [`h/defview`](glossary.md#defview), or call it inline ([Views and reads](02-views-and-reads.md)) |
| `:rf.error/hicasso-intent-outside-boundary` | an [intent](glossary.md#intent) vector reached a position with no boundary frame | dispatch belongs inside a boundary; at a foreign edge, use [`h/event`](glossary.md#hevent) ([Events as data](03-events-as-data.md)) |
| `:rf.error/hicasso-host-unclaimed-callback` | an [`h/event`](glossary.md#hevent) arrived at a host slot with no declared contract | declare the slot on the [`defhost`](glossary.md#defhost) ([Interop](09-interop.md)) |
| `:rf.error/hicasso-revision-not-controlled` | [`::h/revision`](glossary.md#hrevision) on a field that is not controlled | control the field, or drop the revision ([Controlled inputs](04-controlled-inputs.md)) |
| `:rf.warning/hicasso-missing-key` | a seq of boundary children without `:key` | put `:key` in each child's props map ([Lists and collections](06-lists-and-collections.md)) |
| `:rf.error/frame-destroyed` | an operation minted against a destroyed frame incarnation fired after its successor seated | drop the stale handle; capture from the live frame ([Events as data](03-events-as-data.md)) |

Two habits make the catalogue useful. When a complaint surprises you,
follow its anchor before you change code; the recovery column is usually
shorter than the debugging session. When you test a refusal, assert the id,
never the message:

```clojure
;; ht is the test kit from 14-testing.md
(defn badge [_props] [:span.badge "hi"])   ;; a plain defn — not a view

(defn refusal-id [f]
  (try (f) ::did-not-throw
       (catch :default e (:rf.error/id (ex-data e)))))

(deftest plain-defn-head-refuses
  (is (= :rf.error/hicasso-bad-head
         (refusal-id #(ht/tree [badge {}] {:subs {}})))))
```

Message text improves between releases; ids are frozen. A string assertion
tests the message text, not the refusal.

## Production erasure

Everything on this page is development tooling, and all of it erases from a
release build: the evidence producer, the projections, the advisor, the
dev-only checks, and their message strings. Production evidence queries
return nil. A release bundle contains no evidence machinery, no schema
sentinels, and no source-location tables. Performance collection is gated
separately by its own compile-time flag and is off by default. A shipped
build carries no timing instrumentation.

Do not accept this claim without a check. The check is two builds and one
search. Pick a sentinel that you can see in the dev bundle (`rf.xray`, the
evidence machinery's own namespace prefix, is a good default). Search both
builds for it:

```bash
npx shadow-cljs compile app            # dev build
grep -c "rf.xray" public/js/main.js    # positive control: expect > 0

npx shadow-cljs release app            # release build, same output path
grep -c "rf.xray" public/js/main.js    # expect 0
```

The dev-build search is the positive control, and it is not optional. A
zero on the release bundle means "erased" only if the same search finds the
sentinel in the dev bundle. Zero in both places means your search is
broken, not your build. This is the sabotage-twin habit from
[Testing](14-testing.md), applied to the bundle. The product's own release
gate runs the same discipline, with unique sentinels on every dev-only
surface.

The surface erases, so application logic must never depend on it: no
feature may read evidence, count complaints, or branch on a panel's data.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| A view you know ran is absent from the epoch | Its [boundary](glossary.md#boundary) bailed out — a skipped body emits nothing | Absence is the measurement of work not done; if you expected it to run, [explain-render](glossary.md#explain-render) the parent that did |
| Explain-render answers `:uncorrelated` | The event-to-render link could not be established for that occurrence | An honest answer, not a failure; reproduce with a scripted interaction and read the fresh epoch |
| A foreign subtree shows `:host-opaque` | Raw React internals are not enumerable past the crossing | Xray still names and times the crossing; open React DevTools for the inside |
| History stops with `:cap` | The bounded retention window dropped older epochs | Reproduce and capture fresh; retention does not grow to fit the question |
| The advisor refuses to recommend an island you want | The measured owner is not a class native addresses | Do the smaller remedy it named; re-measure; the ladder is in [Performance](18-performance.md) |
| Two runs of one interaction show different times | Xray timing is attribution, not a benchmark | Treat the classification, not the number, as the finding; benchmark under the discipline in [Performance](18-performance.md) |
| A complaint id you hit has no catalogue anchor | The id is not from this product's surface, or your app and kit versions have drifted | Check the id's namespace and align versions; every shipped id resolves |
| Panels are empty in a release build | Production erasure, working as designed | Diagnose on a dev build; production carries no evidence |

## When Xray is not the tool

**Xray timing is not a benchmark.** It attributes: it tells you which link
of the chain owns the time, and which remedy class is credible. It does not
settle "is this fast enough" — budgets and measurement discipline live in
[Performance](18-performance.md). Its render numbers are not commit
evidence. React DevTools remains the authority for commits. The browser's
performance tooling remains the authority for layout and paint. When an
interaction is slow in a release build, profile the release build with
browser tools; the dev build carries dev-only work by design.

When the question is about *correctness* rather than cause — does this body
mean what it should, does teardown leak — the answer is a test, not a
panel: [Testing](14-testing.md).

## Advanced

### The same projection feeds the AI pair

Xray and the AI pair consume one versioned, privacy-projected evidence
schema: byte-equivalent projections, one contract. Query arguments pass
through the privacy projector. Raw values and text are omitted by default.
Nothing leaves the process unless an explicitly authorized consumer
requests it. If you script your own diagnosis through the pair, you read
exactly what the panel reads, including the [loss labels](glossary.md#loss-labels).

### Optional modules bring their own evidence

Evidence follows installation. The forms module contributes draft
ownership. Overlays contribute the active top-layer region. Motion
contributes the current transition posture. Resources contribute the live
demand. Each is a bounded projection that exists only while the module is
installed and in use. None of them adds a standing accumulator to ordinary
[Hicasso](glossary.md#hicasso). An app that uses none of them pays for none of this.

### Retention is bounded on purpose

Xray owns its history budget. The trace ring is the record, and a named
operation (mount/unmount correlation, for example) may keep bounded,
commit-owned identity. No universal occurrence ledger accumulates in the
background. The `:cap` label is the visible edge of that choice. The cost
is bounded history; the alternative is a runtime that slows down in
proportion to how long you have debugged it.
