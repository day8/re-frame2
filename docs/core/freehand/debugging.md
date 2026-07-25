# Debugging and performance

A view looks stale, something feels slow, or you cannot tell which control owns a
draft. Most of the answers are already values you can print — and you climb a
performance ladder **before** you reach for the compiler.

> **Interpretation cost is rarely the problem. Fix subscriptions and boundaries first.**

## Questions and where to look

| Question | Where to look |
|---|---|
| What will this button dispatch? | The structural tree — `(t/attrs node)` carries the event vector |
| What is this view, exactly? | `(v/describe view)` — id, source, lowering, children policy, props schema |
| What can the compiler prove about it? | `(v/manifest view)` — `nil` for an interpreted declaration |
| Is this value even a view? | `(v/view? x)` — `ifn?` is not a proxy for it |
| Which host behaviors are connected? | `(v/active-connections)` |
| Did that command reach its target? | `(v/command-log)` |
| What dispatched this event? | the ordinary re-frame trace stream, and Xray over it |
| Why did this view re-render? | Xray's reactive panel; then narrow the subscription |
| Should I compile? | only after the ladder below |

## Inspecting a declaration

`v/describe` is the descriptor's public projection — a plain map, and inspection
data rather than a dispatch surface:

```clojure
(v/describe todo-row)
;; => {:re-frame.freehand/view true
;;     :view-id                :app.todo/todo-row
;;     :source                 {:ns … :file … :line …}
;;     :lowering               :interpreted        ; or :compiled
;;     :children-policy        :optional
;;     :props-schema           <schema>}           ; absent when none declared
```

`:props-schema` is **absent** when no schema was declared, never `:any` — an
undeclared contract and a deliberately permissive one are different facts, and the
projection refuses to blur them.

`v/manifest` answers what a **compiled** declaration's analysis makes statically
knowable, and `nil` for an interpreted one — which is the honest answer, not an
omission:

```clojure
(v/manifest people-list)
;; => {:view-id   :app.people/people-list
;;     :grammar   :re-frame.freehand/v1
;;     :crossings [{:view-id :re-frame.freehand/markup
;;                  :lowering :interpreted :path [1]}]}
```

`:crossings` is the roster of view boundaries the body mounts, one entry per
lexical site, each marked with the mode it crosses into. A compiled view mounting
an interpreted child is the ordinary case — promotion is per declaration and never
transitive — so the manifest says where the compiled tier *stops* rather than
leaving you to assume it does not.

## The behavior tool plane

A [registered behavior](host-boundaries.md#registered-behaviors) is the one place
in the substrate where opaque host state lives, so it is the one place you most
need to see into. Two read-only projections sit on the public door, so a tool never
has to depend on an implementation namespace.

```clojure
(v/active-connections)
;; => [{:generation 1
;;      :behavior   :my.ns/autosize
;;      :frame      :rf/default
;;      :target     :composer/body
;;      :config     {:max-rows 8}}]
```

Every live connection, oldest first: which behaviors are connected, under which
frame, claiming which semantic ids, on which public config. `:generation` and
`:behavior` are always present; `:frame`, `:target` and `:config` appear only where
the connection has one — so a behavior nothing commands projects **no** `:target`,
absent rather than `nil`.

The private memory and the host node are **absent by construction**, not filtered
out. A projection that answered with a live host instance would be exactly the
instance registry the behavior contract exists to refuse, reached through the
inspection door instead of the front one.

```clojure
(v/command-log)
;; => [{:frame      :rf/default
;;      :target     :composer/body
;;      :op         :refit
;;      :behavior   :my.ns/autosize
;;      :generation 1
;;      :outcome    :delivered}]
```

Recent command traffic as a **bounded** window, oldest first. A row records what
the command *named* and what the channel *decided* — `:outcome` is `:delivered` or
`:refused` — never what the host made of it, because the operation's return value
is the connection's private memory. A delivered row is written *before* the
operation runs, so a command that crashes its host is in the log rather than
missing from it.

Refusals are recorded as faithfully as deliveries. A log that only saw the
successes would be evidence for the one case nobody debugs.

Both are **browser-only**, absent on the JVM exactly like the mount verbs: a
structural render connects nothing, and an eternal `[]` there would be
present-and-lying where absence is honest. Neither is an event stream — you ask,
and nothing calls you back.

The obvious summaries are deliberately *not* published, because you can compute
them: `(count (v/active-connections))` and
`(into #{} (keep :target) (v/active-connections))` are the same answers.

### There is no occurrence accumulator

Freehand publishes **no** per-render history: no list of which occurrences rendered
recently, why, or how often. The two projections above are the whole tool plane,
and they cover host connections rather than view renders.

That is a stated omission rather than an oversight. For per-render questions —
which occurrence re-rendered, and what caused it — use **Xray**, which reads the
development-only evidence surface directly.

## Performance ladder (before compilation)

Work in this order:

1. **Find the cause.** Which query moved, or did the parent simply re-render?
2. **Narrow the subscription.** The classic bug: every cell reads one global
   “editing id”, so a single click dirties two thousand cells instead of two. Fix
   it with per-id queries.
3. **Choose the boundary.** Keyed child views for rows that change independently;
   plain `defn` helpers for pure structure you are happy to re-run with the parent.
4. **Window large lists.** Do not mount what the user cannot see — see
   [Reactivity](reactivity-and-ownership.md#window-large-lists-before-you-compile).
5. **Measure again.**
6. **Only then** promote a remaining hot boundary with `{:compiled true}`, or a
   library leaf that needs static proof.

Subscription results are stabilised with `rf=`, and each boundary is memoised on
its one props map, so churn with identical output almost always means over-broad
subscriptions or props rebuilt on every parent render — not interpretation cost.
If a host library or React reconciliation dominates the budget, another Hiccup
compiler will not save you: fix the host boundary or the data size.

## Traces

Freehand dispatches ordinary re-frame events, so the ordinary trace stream is the
record of what happened. Nothing about a Freehand view invents a second channel:
an event fired from a view is the same event fired from anywhere else, with the
same epoch, the same interceptors and the same observability sink.

Two things worth knowing while reading a trace of a typing session:

- **Each keystroke on a controlled field is its own event.** Typing `hello` is
  about five dispatches. The stream looking busy is expected, not a defect — see
  [Events](events-and-handlers.md#each-keystroke-is-an-event).
- **A render-failure summary is bounded on purpose.** `v/error-boundary`'s
  `:on-error` carries a stable diagnostic id, the failing view id, phase,
  fingerprint and evidence — never the exception, props, app-db or event payloads.
  The exception itself rides a separate private channel to the always-on error
  axis.

## Xray

[Xray](../../xray/index.md) is the panel over all of this: the trace stream,
time-travel scrubbing, the derivation graph, and — in development builds — the
per-occurrence view evidence the door does not publish.

That evidence is **development-only** and stripped from production bundles, which
is the trade: a production build carries no render history, and a development
build can tell you which occurrence rendered, under which frame and descriptor
generation, and which reads and event sites were selected in it.

Evidence always states how complete it is. Freehand does not claim omniscience
over a full-Clojure interpreted body: a compiled view's read sites are proven
statically, an interpreted view's are the reads a committed render actually made,
and a host interior is opaque. Those are labels on one grid, not separate modes.

## If something feels wrong

| Symptom | Fix |
|---|---|
| `(v/manifest view)` is `nil` | the declaration is interpreted — that is the honest answer, not a failure |
| `(v/active-connections)` is empty in a JVM test | it is browser-only; a structural render connects nothing |
| A command did nothing | check `(v/command-log)` for a `:refused` row and the `:target` it named |
| Whole list re-renders on one edit | per-id subscriptions and keyed row boundaries |
| High churn, identical output | props rebuilt inline every parent render, or an over-broad subscription |
| Reaching for a render-history verb | there is none on the door — use Xray |
