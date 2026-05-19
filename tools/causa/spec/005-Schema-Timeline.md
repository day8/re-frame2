# 005-Schema-Timeline

## Bug class

**"Schema violations are spamming the console; show me where + when."**

Silent schema violations are real bugs in disguise. The runtime emits
`:rf.error/schema-validation-failure` traces with recovery mode +
Malli explanation; without a timeline view, the author has to grep
the trace firehose to find them.

## Example bug

Your app subtly mis-renders. The console shows nothing dramatic; the
trace firehose shows three `:rf.error/schema-validation-failure`
events scattered across the last 100 traces. You can't tell which
schema, which path, or whether the framework recovered (replaced with
default) or rolled back app-db.

## Insight Causa provides

A **horizontal timeline** with one row per registered schema; coloured
dot per failure (red = `:skip-handler` / `:rollback-db` /
`:re-raised`; yellow = `:replaced-with-default`); a 600ms label-flash
on the empty→non-empty transition so newly-failing schemas catch the
eye. Click a dot → side panel with full Malli explanation, recovery
mode, triggering cascade, registration source-coord, and two
actions: open source, filter timeline to just this schema.

## Affordance

Issues tab — schema-violation timeline (today as its own panel; future
folds into Issues per [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md)).

---

Silent schema violations are real bugs in disguise. The schema
violation timeline surfaces them temporally so they become impossible
to ignore.

## Substrate

Per Spec 010 (Schemas) and Spec 009 §Error contract, every schema
validation failure emits a trace event:

```clojure
{:op-type   :error
 :operation :rf.error/schema-validation-failure
 :recovery  <one of :skip-handler :skip-fx :rollback-db :replaced-with-default :re-raised>
 :tags      {:where  <one of :event-coercion :db-shape :fx-shape ...>
             :path   <path-into-app-db-or-event>
             :value  <the offending value>
             :schema <the schema id>
             :explain <Malli explanation>
             :frame  <frame-id>}
 :time      <ms>}
```

Causa consumes this stream and renders the timeline.

## Layout

A horizontal timeline along the bottom of the issues feed (when the
Schemas panel is active, it fills the canvas instead):

```
                    last 60s →
:schema/cart-item  ····················●··············●···
:schema/user-auth  ······●···●·············●····················
:schema/order      ····················································
:schema/checkout   ·············●··········●··········●·········●
```

- **One row per registered schema** (from `(rf/app-schemas
  frame-id)`).
- **A dot at the timestamp** where the validation failed.
- **Colour encodes recovery**:

  | Recovery | Colour |
  |---|---|
  | `:skip-handler` | Red. |
  | `:skip-fx` | Red. |
  | `:rollback-db` | Red. |
  | `:replaced-with-default` | Yellow. |
  | `:re-raised` | Red, with a thicker stroke. |

- **Time axis** scrolls horizontally; default window is the last 60s.
  Drag-zoom to expand. The Trace-timeline panel governs the same time
  axis when both are visible — selection synchronises.

## Per-violation detail

Click a dot → side panel:

```
┌─ schema violation ───────────────────────────────────╮
│  schema     :schema/user-auth                        │
│  where      :event-coercion                          │
│  path       [:auth :email]                           │
│  value      nil                                      │
│  expected   string?                                  │
│  recovery   :replaced-with-default                   │
│                                                      │
│  Malli explanation:                                  │
│    {:schema [:map [:email string?]]                  │
│     :value {:email nil}                              │
│     :errors [{:path [:email]                         │
│               :in   [:email]                         │
│               :type :malli.core/missing-key          │
│               :message "missing required key"}]}     │
│                                                      │
│  Triggered by:                                       │
│    epoch 14 — :user/load-profile (epoch 14)          │
│                                                      │
│  Registered at:                                      │
│    src/cart/schemas.cljs:7                           │
│    [Open source]                                     │
╰──────────────────────────────────────────────────────╯
```

The detail surfaces two actions:

1. **Open source** — jumps to the `reg-app-schema` call site.
2. **Show me all violations of this schema** — filters the timeline to
   the selected row.

## Hover tooltip

Hover a dot → 240ms tooltip with a one-line cause:

```
at [:auth :email], expected :string, got nil
```

Cheap-to-read; cheap-to-dismiss; gives the answer without committing
to a panel pivot.

## The five recovery categories

Per [Spec 010 §Per-step recovery](../../../spec/010-Schemas.md#per-step-recovery):

| Recovery | What the framework did | Causa surface |
|---|---|---|
| `:skip-handler` | Handler did not run. | Red dot. Detail explains the handler was skipped; suggests checking the event payload shape. |
| `:skip-fx` | Specific fx invocation skipped. | Red dot. Detail names the fx-id that was skipped. |
| `:rollback-db` | `app-db` reverted to pre-handler value. | Red dot. Detail diff shows what *would* have been written. |
| `:replaced-with-default` | The schema's `:default` substituted for the bad value. | Yellow dot (the framework recovered cleanly). Detail names the default that was used. |
| `:re-raised` | The validation error was rethrown. | Red dot with a thicker stroke. Detail says "consumed by an error boundary; check the surrounding `:on-error`." |

The five-category palette is **closed** (per Spec 010); Causa does not
invent additional categories. If a sixth lands via a spec increment,
the timeline gains a row colour.

## Reading the timeline at a glance

The empty-row signal matters. A row with no dots in the visible window
is a schema that has not failed recently — which is the desired state.
A row that goes from empty to one-dot-per-second is a regression.

When a *new* row gains its first violation, the schema-name label
flashes once (600ms) — the same attention-cue Causa uses for new
issues. After that, the label rests until cleared.

### Flash-cue mechanism (rf2-x2d7c)

The flash-cue is the schema timeline's only motion affordance and
the panel's primary "look here" signal for regressions. Its
mechanics are normative.

**Trigger.** A row's label MUST flash on the
**empty→non-empty transition** of its in-window violation set —
i.e. when the row had **zero** violations inside the visible time
window on the prior composite recompute and has **one or more**
violations on the current recompute. Once a row is non-empty, every
subsequent recompute on which the row remains non-empty MUST NOT
re-flash. Aging back to empty resets the latch — the next
non-empty recompute flashes again.

The transition is computed in the row projection from a cached copy
of the prior recompute's rows (the `:rf.causa/schema-timeline-prev-rows`
sub per
[`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md)
§Schema-violation timeline). Each projected row carries a `:first?`
boolean that is
`true` for the one recompute on which the transition fires and
`false` otherwise. The view binds the animation to `:first?`; no
imperative timer is required.

The trigger is **per-row**, not per-violation: a row that already
has one in-window dot does NOT re-flash when a second, third or
hundredth violation lands on the same schema. The empty→non-empty
test coalesces rapid-fire violations against the same schema by
construction — only the **transition** fires the cue, never the
volume. This is the normative throttle: rapid-fire violations
against an already-flagged schema MUST NOT strobe the label.

**Visual.** The label column (the schema-id text) animates the
single CSS animation `rf-causa-flash` for exactly
**600ms ease-out, one iteration**. The 600ms duration is reused
from the issues-feed new-item flash so the two attention cues read
as the same gesture across the chrome. The dot track itself does
NOT animate — only the label. No layout shift, no glow that
extends beyond the label's bounding box.

**Accessibility.** The flash MUST honour
`prefers-reduced-motion: reduce` per §Motion + animation in
[`007-UX-IA.md`](./007-UX-IA.md): under that setting the animation
clamps to 0 and the label renders statically at next paint. The
information the cue carries (the schema went from clean to dirty)
is also carried by the dot appearing on the track and by the row's
`:first?` data — accessibility is preserved without the motion.

**Clearing.** The cue clears with the animation; there is no
sticky "recently flashed" decoration on the label after the 600ms
elapses. Clearing the trace buffer (per
[`013-Trace-Bus.md`](./013-Trace-Bus.md) §Lifecycle operations)
drops every row's in-window violations, returning every row to the
empty state; the next violation against any schema will fire its
flash again. Scrubbing the time-axis window so that all of a row's
dots fall outside the visible window is treated identically — the
row re-enters its empty state and the next in-window violation
re-arms the cue.

**Scope of the trigger.** The empty→non-empty test is run against
the **in-window** violation set, comparing the current recompute to
the prior recompute's cache. Consequences:

- Schema **registration** alone (a row appearing because a new
  schema was registered via `(rf/app-schemas frame-id)` but
  carrying no violations) does NOT fire the cue — registration
  doesn't move the row out of the empty bucket.
- Selecting a violation, changing the schema-filter, or any other
  read-only pivot does NOT fire the cue — those don't change the
  row's in-window violation set.
- Time-axis **pan or zoom** that brings previously-out-of-window
  violations into view DOES fire the cue for the affected rows.
  The cue is a fixed function of the empty→non-empty transition;
  it does not distinguish "new arrival" from "scrolled into view".
  This is intentional: the user's attention SHOULD be drawn to a
  schema that just became visibly dirty, irrespective of cause.

## Aging out

The timeline reads from the trace buffer (bounded by Spec 009 at 200
entries). Schema violations age out of the buffer along with everything
else; the timeline shows only what the buffer remembers.

For deep sessions, the user can bump the trace buffer depth in
Settings → Performance. Causa never holds a parallel ring buffer for
schema-violations specifically.

## Performance

- **Render per epoch**: O(visible-violations) — typically 0–5.
- **Diff per dispatch**: 0 (the timeline reads existing trace events;
  no extra work on the hot path).
- **Empty state**: O(0) — the panel renders the rows for registered
  schemas plus an "all schemas clean" indicator.

## Empty state

When no schemas are registered:

```
   No schemas registered.
   Once your app registers schemas via :app-schemas (Spec 010),
   validation events will appear here:
   • Schema violations
   • Path-typed lookups
   • Schema-replaced-with-default events
   → Read about schema integration
```

When schemas are registered but none have failed in the visible window:

```
   All schemas clean in the last 60s.
   This is the desired state.
```

(That second message is deliberate. The empty timeline is a *result*,
not a problem. Causa says so.)
