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
| `:machine`            | `{:machine-id <id>}`            | any trace-event in cascade has `:tags :machine-id` = `:machine-id`| none wired today — see §6                     |
| `:http-correlation`   | `{:correlation-id <id>}`        | the exchange's two producer-identifiable bundles — see below      | managed-fx panel correlation pill             |
| `:fx`                 | `{:fx-id <kw>}`                 | any trace-event in cascade has `:tags :rf.fx/id` = `:fx-id`       | managed-fx panel fx-id badge                  |

The matcher walks the cascade's `:handler`, `:fx`, `:effects`, `:subs`,
`:renders`, `:other` buckets via
`typed-predicates/event-bundle-trace-events`. `panels/routing_helpers`
carries a private helper of the same name over the same shape; the
matcher keeps its own so it stays a self-contained pure unit.

**`:http-correlation` is the one kind whose match is not a single tag
lookup.** The id on the pill is the managed-fx record's derived
`:correlation-id`, and no producer stamps it as a flat trace tag — so
the matcher reaches the exchange through the two bundles producer data
DOES identify: the **issuing** bundle, whose `:tags :rf.fx/args` carries
the id at one of the caller-supplied keys (`:request-id`, `:socket-id`,
`:fixed-actor-id`, `:machine-id`, `:id`, `:flow-id`), and the
**reply-dispatch** bundle, whose dispatched event vector carries the
family's canonical reply map, matched on the VALUES of its
`:correlation` sub-map so one expression holds across HTTP and machines.
The `:rf.http/replied` completion row is deliberately NOT matched: it is
emitted outside any handler scope, carries no `:rf.trace/dispatch-id`,
and the projection buckets it into the shared `:ungrouped`
pseudo-bundle alongside unrelated exchanges' rows — neither arm reaches
it, so the exclusion falls out of the two arms rather than needing a
special case (rf2-st7j0).

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
touched by removing filters. Pills + mutes are the only suppressing
filters this doc models. (rf2-pjjwh — the `Clear Filters` *button* was
retired from the events ribbon, and the orphaned
`:rf.xray/clear-all-filters` event was removed with it per rf2-rdhbk —
the call-site census found no surviving caller. Pills are removed
individually via each pill's `✕`; muted event-ids are managed through
the chrome ribbon's `🔇 N` chip → mute manager.) See
[`018-Event-Spine.md` §7 Frame picker is a view scope](018-Event-Spine.md).

### §3.1 Causal lineage under epoch-per-event — frame-qualified identity (rf2-3fc89f.25)

Under epoch-per-event each dequeued event — including `:fx :dispatch`
children and machine-internal transitions — is its OWN event-bundle
(`re-frame.trace.projection/group-by-event` keys one record per
`:rf.trace/dispatch-id`). A `:machine` / `:http-correlation` / `:fx` IN
pill that matched ONLY the event-bundle carrying its tag would lose the
link to the PARENT event that spawned the transition (a sibling
event-bundle). So a directly-matching event-bundle pulls in its whole
**spawning lineage**: itself plus every causal ancestor, walked UP the
`:parent-dispatch-id` link to the root user event —
`keep = matching event-bundles ∪ their ancestors`. OUT (hide) pills stay
event-bundle-local so hiding a child never silently drops the ancestor
that spawned it.

**Event-bundle identity is the frame-qualified `[frame dispatch-id]`
pair — normative and portable.** The published trace contract
([`013-Trace-Consumer.md`](013-Trace-Consumer.md)) guarantees
`dispatch-id` uniqueness only WITHIN a frame, so two frames may
legitimately reuse the same dispatch-id (deterministic replay and
per-frame id allocation make this live, not merely latent). The
causal-lineage machinery therefore keys on the frame-qualified identity
**end-to-end** — the one canonical helper
`typed-predicates/event-bundle-identity` `⇒ [frame dispatch-id]` backs
the ancestor index, the retained-key set, the membership check, AND the
cycle guard. A parent resolves to `[frame parent-dispatch-id]`
(`event-bundle-parent-identity`), since a parent and child always share a
frame under epoch-per-event, so the ancestor walk never crosses into
another frame that reuses the parent's dispatch-id. A bare-id identity is
rejected: it cross-links unrelated frames' lineages (an IN pill could
display an unrelated frame's event and attach the wrong causal ancestor).
This is pre-alpha — there is NO compatibility fallback to id-only
identity; the frame-qualified pair is the contract.

The `:ungrouped` pseudo-event-bundle and any event-bundle with a nil
dispatch-id are excluded from the causal index — they are not addressable
causal nodes — regardless of frame. This is the same frame-isolation
invariant already enforced in the Event Spine
([`018-Event-Spine.md`](018-Event-Spine.md)) and Pair MCP event grouping.

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
| Managed-fx record correlation pill              | `:rf.xray/filter-by-http-correlation` |
| Managed-fx record fx-id badge                   | `:rf.xray/filter-by-fx`               |
| L2 event row                                    | `:rf.xray/hide-event-type` (popup)    |

Each typed-add event is idempotent: a duplicate add (same params)
collapses to a no-op so multiple right-clicks don't pile up duplicate
pills.

**The `:machine` kind has no right-click source today.**
`:rf.xray/filter-by-machine` is registered (`filters.cljs`) and its
matcher, pill label and glyph are complete, but nothing in
`tools/xray/src` dispatches it: the Machine inspector's picker chrome
and the focused-event lens header were both specified as sources and
neither was built, so the `:machine` pill kind is unreachable from the
UI. The event is kept rather than retired — it is the wiring point for
whichever surface lands the affordance.

## §7 Reset-on-load (rf2-swclw)

**Pills RESET on every load (rf2-swclw).** The IN/OUT pills are a
**transient exploration filter**, so the first-mount hook
(`mount.cljs/::reset-transient-filters`) does NOT hydrate the
`:active-filters` slot — a fresh page load starts fully unfiltered (so a
stale pill can never silently hide rows and make the inspector look
broken — rf2-jvghz). The muted-event-id set and the frame view-scope
follow the same reset-on-load discipline, and for those two, which do
still carry a localStorage slot, the hook additionally clears the stale
stored value. Only durable view prefs (mode, density, layout) hydrate.

**The pills carry no persistence layer at all (rf2-y8doi.27).** They
previously round-tripped through `filters/persistence.cljs` into a
versioned localStorage slot (`re-frame2.xray.filters.v1`); since the
reset above discarded whatever had been written on every load, that
store had a writer and no reader, and the namespace, the storage-key
config knob and the `persist` fx were all deleted. **The reset-on-load
policy is unchanged — it is the REASON the layer could go, and it now
holds by construction rather than by cleanup.** Typed pills therefore
need no serialisation contract: they live in `:active-filters` for the
duration of a session and nothing writes them to disk. See
[`015-Configuration.md` §`:rf.xray/filters`](015-Configuration.md)
(transient user filters vs the explicit host seed)
+ [`018-Event-Spine.md` §Filter reset-on-load](018-Event-Spine.md).

## §8 "N events filtered out" indicator (rf2-jvghz / rf2-pjjwh)

Because pills reset on load but can still hide rows mid-session, the
events ribbon surfaces an in-session safety net: an `N events filtered
out` message renders at the far right when N > 0. `N = max 0 (raw-visible
− filtered-visible)`, both counts over the L2 list's visible-row set and
both scoped to the selected frame. The frame view-scope is excluded
(frame ≠ filter — §3), so switching frames never inflates N. The pure
model lives in `filters/hidden.cljc` (`summary`).

**rf2-pjjwh — the `Clear Filters` button was retired** (not in the Figma
surface); pills are removed individually via each pill's `✕`, and muted
event-ids are managed through the chrome ribbon's `🔇 N` chip → mute
manager. The orphaned `:rf.xray/clear-all-filters` event was removed with
the button (rf2-rdhbk — no palette verb, keybinding, or programmatic
caller survived). The whole events ribbon is hidden by default and
animates open only once the first filter exists.

## §9 Cross-references

- [`018-Event-Spine.md` §7](018-Event-Spine.md) — pill UI contract +
  IN/OUT composition.
- [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.4 /
  F-C2 — the managed-fx bug classes these pills serve. The record shape
  itself is not specified there; `panels/managed_fx_helpers.cljc` derives
  a record's `:correlation-id` and `:fx-id`.
- [`003-Machine-Inspector.md`](003-Machine-Inspector.md) §Selection +
  switching — the machine picker / focused-event lens surfaces.
