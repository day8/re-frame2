# Spec 020 — Filter Predicate Kinds (rf2-piye4)

This document is the normative contract for Xray's filter-pill record
shape. It supersedes the v1-shape note in
[`018-Event-Spine.md` §7](018-Event-Spine.md) — the `{:pattern <kw-or-str>}`
shape from rf2-ak4ms is now the canonical form of one kind among
several typed predicates.

Owner: tools/xray.

## §1 Pill record

Every IN / OUT pill is one of:

```clojure
;; Keyword-pattern (rf2-ak4ms — the Add-filter dialog's only output):
;; event-id is the implicit, only scope (rf2-o8pjv).
{:pattern <kw-or-str>}

;; Typed predicate (rf2-piye4):
{:kind   <keyword>
 :params <kind-specific map>}
```

The matcher (`filters/typed-predicates`) `canonicalise-pill`s on the way
through, so the keyword-pattern shape hydrates as `:event-id-pattern`
without a migration step. New pills written by right-click affordances
persist under the typed shape; the round-trip is symmetric. A stale
`:scope` key on a pre-rf2-o8pjv persisted pill is dropped on hydration —
the matcher honours event-id only.

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

**The frame is NOT in this composition (rf2-4vp5j).** The frame picker
is a single, defaulted VIEW SCOPE, not a filter predicate. The
`:rf.xray/filtered-event-bundles` sub scopes cascades to the selected frame
(`matcher/filter-event-bundles-by-view-scope`) BEFORE applying the IN/OUT
pills + mutes; the frame scope is never counted as "hidden" and is never
reset by the `:rf.xray/clear-all-filters` event. Pills + mutes are the
only suppressing filters this doc models. (rf2-pjjwh — the `Clear Filters`
*button* was retired from the events ribbon; the
`:rf.xray/clear-all-filters` event remains for programmatic / Cmd-K reset.
Pills are removed individually via each pill's `✕`.) See
[`018-Event-Spine.md` §7 Frame picker is a view scope](018-Event-Spine.md).

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
| Machine inspector picker chrome                 | `:rf.xray/filter-by-machine`          |
| Focused-event lens section header               | `:rf.xray/filter-by-machine`          |
| Managed-fx record correlation pill              | `:rf.xray/filter-by-http-correlation` |
| Managed-fx record fx-id badge                   | `:rf.xray/filter-by-fx`               |
| L2 event row                                    | `:rf.xray/hide-event-type` (popup)    |

Each typed-add event is idempotent: a duplicate add (same params)
collapses to a no-op so multiple right-clicks don't pile up duplicate
pills.

## §7 Persistence — write-through, reset-on-load (rf2-swclw)

`filters/persistence.cljs` round-trips the whole `:active-filters`
slot — typed pills survive a localStorage write/load because
`pr-str` / `read-string` handle the `:kind` / `:params` shape natively.
No version bump on the storage key (`re-frame2.xray.filters.v1`) —
the shape is additive (legacy `{:pattern ...}` still loads through the
canonicaliser).

**Pills RESET on every load (rf2-swclw).** The IN/OUT pills are a
**transient exploration filter**, so the first-mount hook
(`mount.cljs/::reset-transient-filters`) does NOT hydrate the slot from
localStorage AND clears the stale stored value — a fresh page load starts
fully unfiltered (so a stale pill can never silently hide rows and make
the inspector look broken — rf2-jvghz). The persist fx still writes pills
through *within* a session; it is the LOAD that resets. The muted-event-id
set and the frame view-scope follow the same reset-on-load discipline.
Only durable view prefs (mode, density, layout) hydrate. See
[`015-Configuration.md` §Transient vs durable state](015-Configuration.md)
+ [`018-Event-Spine.md` §Filter persistence](018-Event-Spine.md).

## §8 "N events filtered out" indicator (rf2-jvghz / rf2-pjjwh)

Because pills reset on load but can still hide rows mid-session, the
events ribbon surfaces an in-session safety net: an `N events filtered
out` message renders at the far right when N > 0. `N = max 0 (raw-visible
− filtered-visible)`, both counts over the L2 list's visible-row set and
both scoped to the selected frame. The frame view-scope is excluded
(frame ≠ filter — §3), so switching frames never inflates N. The pure
model lives in `filters/hidden.cljc` (`summary`).

**rf2-pjjwh — the `Clear Filters` button was retired** (not in the Figma
surface); pills are removed individually via each pill's `✕`. The
`:rf.xray/clear-all-filters` event survives as the programmatic / Cmd-K
reset path. The whole events ribbon is hidden by default and animates open
only once the first filter exists.

## §9 Cross-references

- [`018-Event-Spine.md` §7](018-Event-Spine.md) — pill UI contract +
  IN/OUT composition.
- [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.4 /
  F-C2 — managed-fx record shape (`:correlation-id`, `:fx-id`).
- [`003-Machine-Inspector.md`](003-Machine-Inspector.md) §Selection +
  switching — the machine picker / focused-event lens surfaces.
