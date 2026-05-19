# Spec 020 — Filter Predicate Kinds (rf2-piye4)

This document is the normative contract for Causa's filter-pill record
shape. It supersedes the v1-shape note in
[`018-Event-Spine.md` §7](018-Event-Spine.md) — the `{:pattern <kw-or-str>}`
shape from rf2-ak4ms is now the canonical form of one kind among
several typed predicates.

Owner: tools/causa.

## §1 Pill record

Every IN / OUT pill is one of:

```clojure
;; Legacy keyword-pattern (rf2-ak4ms — back-compat persisted shape):
{:pattern <kw-or-str>
 :scope   #{:event-id}}

;; Typed predicate (rf2-piye4):
{:kind   <keyword>
 :params <kind-specific map>}
```

The matcher (`filters/typed-predicates`) `canonicalise-pill`s on the way
through, so legacy shape hydrates as `:event-id-pattern` without a
migration step. New pills written by right-click affordances persist
under the typed shape; the round-trip is symmetric.

## §2 v1 kinds

| kind                  | params                          | matcher                                                           | right-click source                            |
|-----------------------|---------------------------------|-------------------------------------------------------------------|-----------------------------------------------|
| `:event-id-pattern`   | `{:pattern <kw-or-str>}`        | event-id matches `:pattern` per `matcher.cljc`                    | trailing `[+]` add-pill + L2-row right-click  |
| `:machine`            | `{:machine-id <id>}`            | any trace-event in cascade has `:tags :machine-id` = `:machine-id`| Machines panel rows                           |
| `:http-correlation`   | `{:correlation-id <id>}`        | any trace-event in cascade has `:tags :correlation-id` = ditto    | managed-fx panel correlation pill             |
| `:fx`                 | `{:fx-id <kw>}`                 | any trace-event in cascade has `:tags :fx-id` = `:fx-id`          | managed-fx panel fx-id badge                  |

The matcher walks the cascade's `:handler`, `:fx`, `:effects`, `:subs`,
`:renders`, `:other` buckets — same shape `routing_helpers/cascade-
trace-events` consumes.

## §3 Composition (unchanged from §18.7)

```
keep = (no-IN-pills OR matches-IN) AND NOT (matches-OUT)
```

`matches-IN` and `matches-OUT` are `some` over the bucket — pills
within a bucket compose with OR, buckets compose with AND-NOT. Mixing
typed pills + keyword-pattern pills in the same bucket is supported.

## §4 Deferred kinds (rf2-piye4 — defer to v1.1)

| kind             | rationale                                                    |
|------------------|--------------------------------------------------------------|
| `:source-coord`  | Niche; useful but no clear right-click source today.         |
| `:interceptor`   | Niche; ditto.                                                |
| `:descendant-of` | MOOT — Causality dropped this session.                       |

## §5 Editing posture

The edit popup (`filters/edit-popup`) is keyword-pattern-only in v1 —
typed-predicate pills have fully-determined params (one click = one
predicate), so the body is non-clickable and removal is via the `×`
button. A future rev may surface per-kind edit popups; v1 covers the
common cases without that surface area.

## §6 Right-click affordances

| panel surface                                   | event                                  |
|-------------------------------------------------|----------------------------------------|
| Machine inspector picker chrome                 | `:rf.causa/filter-by-machine`          |
| Focused-event lens section header               | `:rf.causa/filter-by-machine`          |
| Managed-fx record correlation pill              | `:rf.causa/filter-by-http-correlation` |
| Managed-fx record fx-id badge                   | `:rf.causa/filter-by-fx`               |
| L2 event row                                    | `:rf.causa/hide-event-type` (popup)    |

Each typed-add event is idempotent: a duplicate add (same params)
collapses to a no-op so multiple right-clicks don't pile up duplicate
pills.

## §7 Persistence

`filters/persistence.cljs` round-trips the whole `:active-filters`
slot — typed pills survive a localStorage write/load because
`pr-str` / `read-string` handle the `:kind` / `:params` shape natively.
No version bump on the storage key (`re-frame2.causa.filters.v1`) —
the shape is additive (legacy `{:pattern ...}` still loads through the
canonicaliser).

## §8 Cross-references

- [`018-Event-Spine.md` §7](018-Event-Spine.md) — pill UI contract +
  IN/OUT composition.
- [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.4 /
  F-C2 — managed-fx record shape (`:correlation-id`, `:fx-id`).
- [`003-Machine-Inspector.md`](003-Machine-Inspector.md) §Selection +
  switching — the machine picker / focused-event lens surfaces.
