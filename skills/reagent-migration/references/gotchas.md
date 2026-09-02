# Gotchas — the traps that mangle a view silently

> These are the ways a careless conversion produces a view that compiles,
> renders, and then misbehaves. Read them before a first migration. They are
> ordered by how expensive they are to find later.

## Three leftovers, three ids — and only one of them fails at CLICK time

The single most consequential trap in the whole migration, and it is really
three. A half-converted view strands Reagent-shaped reads and dispatches in
three different places, and they fail at three different **times** under three
different ids:

| The leftover | Fails at | Id |
|---|---|---|
| an ambient `@(rf/subscribe …)` or `(rf/dispatch …)` still inside the render extent — the body **or** a helper it inlines | RENDER | `:rf.error/ambient-frame-refused` |
| a `#(rf/dispatch …)` closure surviving at an `:on-*` prop | CLICK | `:rf.error/no-frame-context` |
| an `(h/sub …)` moved out into a callback, a timer or a promise | FIRE | `:rf.error/hicasso-sub-outside-render` |

**Row 1 — the render-time refusal.** A boundary body runs inside an extent that
*refuses* ambient frame resolution, so a surviving `rf/subscribe` or
`rf/dispatch` does not quietly work: it raises at the first render. It is **not
an absence** — a frame IS in scope — so adding another boundary or a
`with-frame` will not help; the extent withdraws the ambient reach specifically,
and the `:reason` names both recoveries, `h/sub` for a read and an intent at a
handler position for a dispatch. **The extent covers helpers too**: a
parens-called `defn` runs inside the body, so MIG-02's deref-drop reaches it
(MIG-26).

**Row 2 — the click-time failure**, and the reason a converted view can look
finished. Hicasso passes an **unmarked plain function** at an `on-*` prop
straight through to React **by identity** — deliberately, so `React.memo` and
every handler-identity bail-out keep working. So a surviving Reagent closure:

```clojure
{:on-click #(dispatch [:save])}     ; converted view, un-lifted handler
```

is not refused at lowering, is not refused at render, and reaches React exactly
as written. When the browser invokes it later the render extent has unwound,
ambient dispatch has no frame to resolve against, and it raises
**`:rf.error/no-frame-context`** — which is *core's* id, not a `hicasso-*` one,
so a grep for Hicasso diagnostics will not find it either.

**Row 3 — the over-correction**, reached by fixing row 1 or row 2 too
enthusiastically. `h/sub` is legal only *during* a body run, because a read
outside one has no render pass to fence it and no boundary to record the edge
against. Hoist the **read** to render time and close over the **value**; where
handler code genuinely needs current state, `rf/subscribe-once` is the
sanctioned snapshot.

**So grep the converted bodies for surviving closures rather than finding them
by clicking.** `#(`, `(fn [`, and any `subscribe` or `dispatch` inside a props
map — or inside a helper the body inlines — are the search. The fix is MIG-04/05
(a vector) or MIG-18 (`h/event`, which carries the frame it was lowered in).

This is what cardinal rule 2 — never half-migrate a view — is protecting you
from.

## Reading a complaint

Each refusal above is a thrown `ex-info`, and its `ex-data` carries core's four
slots. Knowing them turns a stack trace into an instruction:

- **`:rf.error/id`** — the stable discriminator. **This** is what to branch on
  in a test, a tool or an error monitor: an id names one refusal and is never
  re-spelled or reused.
- **`:where`** — the symbol naming the function that refused.
- **`:reason`** — the human sentence, and it **names the fix**. Read this one
  first; for the render-time refusal it spells out the collector-and-intent
  recovery in full.
- **`:recovery`** — a keyword classifying that fix
  (`:read-through-the-boundary-collector` for the Hicasso render refusal,
  `:no-recovery` where the runtime does not recover).

Beyond the four, a complaint carries its own class's situational detail — the
offending query vector, the prop position, the frame. Two further slots,
`:view` and `:source`, name the boundary that was rendering and the file and
line its `defview` was written at; they are dev-build **context, not contract**,
absent under `:advanced` with `goog.DEBUG` false, so read them to help a human
and never branch on them.

The index of every id Hicasso raises is
`implementation/hicasso/spec/complaints.md`; what each one means and what else
it carries is `spec/009-Instrumentation.md` §Hicasso. Both are in the re-frame2
repository, alongside the door.

## Brackets mount, parens inline — the ownership change that reads like spelling

`h/defview` mints a **real React function component** and binds the var:

```clojure
[card {:title t}]     ; a BOUNDARY: its own subscription edges, its own memoisation
(card-bits t)         ; a plain defn helper: runs inside whoever called it, owns nothing
```

Two ways a Reagent codebase trips on this:

- **A helper that should have stayed a helper.** Reagent authors reach for
  `[thing …]` reflexively. If the extracted piece exists only to shorten a body,
  leave it a `defn` and call it with parens — you keep one boundary rather than
  minting an occurrence per call.
- **A plain function in head position is a loud error**, not a silent embedding.
  That is what keeps a head's identity stable by construction, and it is the
  rule that replaces all of Reagent's Form-1/2/3 folklore.

## `^{:key …}` metadata is not read — at all

**Hicasso performs no metadata read anywhere in the codec.** A surviving
`^{:key (:id t)}` is not a spelling variant to be tidied later; it is a key that
is simply **absent**, and React falls back to reconciling the list by position.

In a static list you will never notice. In a reorderable, filterable or
paginated one it is silent state corruption — the wrong row keeps the wrong
row's input text, the wrong item animates, the wrong subtree survives a
re-sort. MIG-07 is therefore mandatory rather than cosmetic.

Two signals help and neither is complete cover: React's own key warning fires
for a missing key (Hicasso adds nothing to it), and the dev-only
`:rf.warning/hicasso-entity-key` fires for a **boundary-headed** member of a
sequence whose key is not a string/number/keyword/uuid/symbol.

`:key` is the **exact literal keyword**. `"key"` and `:x/key` are ordinary
attributes, not the key.

## The exactly-one-props-map law

An `h/defview` takes **one** parameter and it is the props map. Call sites match:
`[status-pill {}]`, never `[status-pill]`. Reagent's habit of zero-arg
components is the most common mechanical miss in a first pass.

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

## Markers do not nest, and only two exist

`::h/value` and `::h/checked` substitute in **one pass over the intent vector's
own elements**. A marker written below the top level:

```clojure
{:on-input [:form/set {:title ::h/value}]}    ; WRONG — arrives as a literal keyword
```

arrives at the handler as `:re-frame.hicasso/value`, silently, with no
diagnostic. Restructure the event's payload instead:
`[:form/set :title ::h/value]`.

The reserved **head** an author writes is one — `::h/prevent` — and it sits at
index 0; its payload cannot itself be a reserved head. Hicasso keeps a second,
internal navigate head, but `h/route-link` mints it and it is not `::h/…` —
never write it. Navigation is `h/route-link` or an ordinary routing event.

Everything else spelled `::h/…` is not a dispatch marker: `::h/revision` is a
controlled-input attribute, `::h/clear` is a registered event id. The presence
overrides are the motion module's own keywords — `::motion/mounting` /
`::motion/unmounting`, i.e. `:re-frame.hicasso.motion/…` — not `::h/…` at all.

## Prop-dialect edges that fail silently

The canonical-slot rule accepts kebab and camel alike, so most of a Reagent
codebase needs no respelling (MIG-11). Three edges do not follow that:

- **A string key is verbatim.** `{"on-input" f}` emits the slot `on-input`,
  which React ignores — a dead handler with no error. (This is a deliberate
  escape hatch for custom elements.)
- **A symbol key camelCases but is not an event position.** `{'on-click [:go]}`
  emits `onClick` and the intent vector crosses as an inert JavaScript array.
- **A map at `:class` is not truthiness-filtered.** `{:class {:active true}}`
  renders `"active true"`, because a map is a collection like any other. Rewrite
  conditional-class maps to a vector with `when`.

## `:on-submit` prevents by default — and a key map there prevents everywhere

`:on-submit` is the **one** position that calls `.preventDefault` for you, and
only for the data spellings (a vector, a key map). An `h/event` or a plain fn at
`:on-submit` is never auto-prevented — whoever holds the event owns it.

The consequence to watch: a **key map** written at `:on-submit` passes that
position down to every branch, so every branch prevents. That is rarely what a
Reagent keystroke handler meant.

## A callback ref must be a stable top-level fn

React's contract is identity-based: hand it a fresh `(fn [n] …)` each render and
it detaches and reattaches on **every** commit, running your mount work and your
cleanup over and over.

```clojure
;; RIGHT
(defn- focus-on-mount [node] (when node (.focus node)))
(h/defview composer [_] [:textarea {:ref focus-on-mount}])

;; WRONG — new identity every render
(h/defview composer [_] [:textarea {:ref (fn [n] (when n (.focus n)))}])
```

Two more: a **vector** at `:ref` is not a ref — it crosses to React as data and
the ref never fires, so the callback function is the only spelling — and
`:ref` on a **`defview` head** is not a ref at all — the boundary path lifts
only `:key`, so it stays in the props map as ordinary data with nothing to
report it.

## `h/mount!` ensures its frame and takes `:initial-events`

`(h/mount! container config hiccup)` takes a config map, and mounting **ensures**
the named frame: `{:frame ::frame :initial-events [[:boot]]}` creates the frame
if it is absent and seeds it before the first paint, or joins an already-live
frame without replaying the seed. So the Reagent pair `(rdom/render [app] el)` +
`(rf/dispatch-sync [:boot])` maps onto a single `h/mount!` — with `rf/init!`
before it, because frame construction raises
`:rf.error/no-adapter-installed` until a reactive adapter is installed.

An explicit `rf/make-frame` before the mount is still legal, and is what you
want when several roots share one frame, or when the frame needs options the
mount config does not carry (`:images`, `:fx-overrides`). The mount then finds
the frame live and joins it without re-seeding.

The app's existing `rf/init!` stays. Do not delete it as Reagent scaffolding:
re-frame2 installs no adapter for you and has no default-adapter registry, so
the install is the app's own explicit line whatever the views are written in. A
Reagent adapter under a Hicasso tree keeps working exactly as it did, and is
what a part-migrated page wants — every React-shaped adapter writes the same
frame context, so the Reagent subtree and the Hicasso one resolve to the *same*
frame. Hicasso does ship an adapter of its own (`re-frame.hicasso.substrate`),
but it is an optional module nothing under Hicasso's own source requires, so
mounting a Hicasso view neither installs it nor displaces what the app has.

For hot reload use `h/render!`, never a second `h/mount!` — the latter
`createRoot`s again and replaces the whole tree.

## Silent drops in a key map

A key-map branch whose value is neither a vector nor a function becomes `nil`
and never fires — no error, no warning. A keyword or a map written there is
simply dead.

## The draft guide is not the API

Several things the Hicasso draft guide teaches do not exist in shipped code, and
writing them produces a view that will not load:

| Taught in the guide | Reality |
|---|---|
| an `h/fn` spelling | shipped is `h/event`; `hfn` was swept to it |
| "key maps are valid only at `:on-key-down` / `:on-key-up`" | shipped accepts a map at any event position |
| the reserved vocabulary as four keywords | stale twice over — its `::h/navigate` is now an internal head `h/route-link` mints (an author never writes it), it omits `::h/clear`, and the presence overrides are the motion module's `::motion/mounting` / `::motion/unmounting` |

**Read the door** — `implementation/hicasso/src/re_frame/hicasso.cljc` — not a
design page (cardinal rule 6).
