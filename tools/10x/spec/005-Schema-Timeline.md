# 005-Schema-Timeline

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
             :explanation <Malli explanation>
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
│    [Open in causality graph]                         │
│                                                      │
│  Registered at:                                      │
│    src/cart/schemas.cljs:7                           │
│    [Open source]                                     │
╰──────────────────────────────────────────────────────╯
```

The detail surfaces three actions:

1. **Open in causality graph** — pivots to the graph filtered to the
   dispatch that caused this violation.
2. **Open source** — jumps to the `reg-app-schema` call site.
3. **Show me all violations of this schema** — filters the timeline to
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
