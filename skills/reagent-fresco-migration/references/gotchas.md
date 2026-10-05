# Gotchas — the traps that mangle a view silently

> These are the ways a careless conversion produces a view that compiles,
> renders, and then misbehaves. Read them before a first migration. They are
> ordered by how expensive they are to find later.

## Three leftovers, three ids — and only one of them fails at CLICK time

The single most consequential trap in the whole migration, and it is really
three: a half-converted view strands Reagent-shaped reads and dispatches in
three places, which fail at three different **times** under three different ids:

| The leftover | Fails at | Id |
|---|---|---|
| an ambient `@(rf/subscribe …)` or `(rf/dispatch …)` still inside the render extent — the body **or** a helper it inlines | RENDER | `:rf.error/ambient-frame-refused` |
| a `#(rf/dispatch …)` closure surviving at an `:on-*` prop | CLICK | `:rf.error/no-frame-context` |
| an `(h/sub …)` moved out into a callback, a timer or a promise | FIRE | `:rf.error/fresco-sub-outside-render` |

**Row 1 — the render-time refusal.** A boundary body runs inside an extent that
*refuses* ambient frame resolution, so a surviving `rf/subscribe` or
`rf/dispatch` raises at the first render. It is **not an absence** — a frame IS
in scope, so another boundary or a `with-frame` will not help — and the
`:reason` names both recoveries: `h/sub` for a read, an intent at a handler
position for a dispatch. **The extent covers helpers too**: a parens-called
`defn` runs inside the body, so MIG-02's deref-drop reaches it (MIG-26).

**Row 2 — the click-time failure**, and the reason a converted view can look
finished. Fresco passes an **unmarked plain function** at an `on-*` prop
straight through to React **by identity** — deliberately, so `React.memo` and
every handler-identity bail-out keep working. So a surviving Reagent closure:

```clojure
{:on-click #(dispatch [:save])}     ; converted view, un-lifted handler
```

is not refused at lowering, is not refused at render, and reaches React exactly
as written. When the browser invokes it later the extent has unwound, ambient
dispatch has no frame to resolve against, and it raises
**`:rf.error/no-frame-context`** — *core's* id, not a `fresco-*` one, so a grep
for Fresco diagnostics will not find it either.

**Row 3 — the over-correction**, reached by fixing row 1 or row 2 too
enthusiastically. `h/sub` is legal only *during* a body run: hoist the **read**
to render time and close over the **value**, and where handler code genuinely
needs current state, `rf/subscribe-once` is the sanctioned snapshot.

**So grep the converted bodies for surviving closures rather than finding them
by clicking.** `#(`, `(fn [`, and any `subscribe` or `dispatch` inside a props
map — or inside a helper the body inlines — are the search. The fix is MIG-04/05
(a vector) or MIG-18 (`h/event`, which carries the frame it was lowered in), and
this is what cardinal rule 2 — never half-migrate a view — protects you from.

## Reading a complaint

Each refusal above is a thrown `ex-info`, and its `ex-data` carries core's four
slots. Knowing them turns a stack trace into an instruction:

- **`:rf.error/id`** — the stable discriminator. **This** is what to branch on
  in a test, a tool or an error monitor: an id names one refusal and is never
  re-spelled or reused.
- **`:where`** — the symbol naming the function that refused.
- **`:reason`** — the human sentence, and it **names the fix**. Read this first.
- **`:recovery`** — a keyword classifying that fix
  (`:read-through-the-boundary-collector` for the Fresco render refusal,
  `:no-recovery` where the runtime does not recover).

Beyond the four sits the refusal's own detail — the offending query vector, the
prop position, the frame. `:view` and `:source` name the rendering boundary and
where its `defview` was written: dev-build **context, not contract**, absent
under `:advanced`, so never branch on them.

Every id Fresco raises is indexed in `implementation/fresco/spec/complaints.md`;
what each one means is `spec/009-Instrumentation.md` §Fresco.

## Brackets mount, parens inline — the ownership change that reads like spelling

`[card {:title t}]` mounts a **boundary** with its own subscription edges and
memoisation; `(card-bits t)` is a plain `defn` helper that runs inside whoever
called it. Reagent authors reach for `[thing …]` reflexively — if the piece only
shortens a body, keep it a `defn` called with parens. And a plain function in
head position is a loud error (`:rf.error/fresco-bad-head`), not a silent
embedding ([`mental-model.md`](mental-model.md) §Anchor).

## The bare-symbol trap

A hiccup child that is a **bare symbol** is *content*, not props:

```clojure
[:li item]        ; `item` is the LIST ITEM CONTENT of the <li>
```

It is tempting — and wrong — to treat a non-literal in position 2 as a props map
and forward it. That **mangles the content**: `item` was never a props map.
MIG-28's plain `merge` applies **only** to a genuine props-map expression in
the props position. When in doubt, it is content.

Related: **data vectors are not hiccup.** `[:buy 1]` inside `{:on-click …}` is
an *event vector*, and `[:total]` inside `(h/sub …)` is a *query vector*.
Neither is an element to be head-respelled or forwarded. The distinction is
positional.

## The `::h/…` keywords — two markers, one head, and the rest are not markers

`::h/value` and `::h/checked` substitute only at the intent vector's top level
(MIG-05); nested, they arrive as the literal keyword with no diagnostic. The one
reserved **head** an author writes is `::h/prevent` (MIG-06). Fresco keeps a
second, internal navigate head that `h/route-link` mints — it is not `::h/…`, so
never write it; navigation is `h/route-link` or an ordinary routing event.
Everything else spelled `::h/…` is not a dispatch marker: `::h/revision` is a
controlled-input attribute and `::h/clear` is `h/reg-state`'s clear event id.
The presence overrides are the motion module's own keywords
(`::motion/mounting` / `::motion/unmounting`), not `::h/…` at all.

## The other silent traps, each stated once in its rule

- **A surviving `^{:key …}`** is an absent key — Fresco reads no metadata — and
  a reorderable list then reconciles by position (MIG-07).
- **A string or symbol prop key** is a dead handler; a map at `:class` is not
  truthiness-filtered (MIG-11).
- **A key map away from a keyboard event** raises
  `:rf.error/fresco-intent-needs-the-event` when it fires, and a key-map branch
  that is neither a vector nor a function never fires (MIG-33).
- **An inline `(fn [n] …)` at `:ref`** re-runs mount work and cleanup on every
  commit; a vector at `:ref`, or `:ref` on a `defview` head, is not a ref at
  all (MIG-17).
- **Deleting `rf/init!`** as Reagent scaffolding leaves `rf/make-frame` raising
  `:rf.error/no-adapter-installed`; and a `h/render!` given `:frame` or
  `:initial-events`, a reload that drops or trims the `h/frame-root` head, or a
  handle not held in a `defonce` each break the root (MIG-15).
- **`{:__html html}` in Reagent source was inert under stock Reagent and is live
  under Fresco** (MIG-34).

## The guide is not the API — read the door

The guide (`docs/core/fresco/`) is written for people and drifts; design notes
describe forms that never shipped, such as an `h/fn` callback (the form is
`h/event`). **Read the door** (`re_frame/fresco.cljc`), not any page —
cardinal rule 6.
