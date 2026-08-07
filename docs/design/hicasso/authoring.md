# Hicasso — the authoring surface

What a programmer (and their AI) writes. This page is design intent for the v0
dogfood and the eventual guide; the declaration spellings stay unfrozen until the
donor arm and the surviving lean-React arm have measured (HD-008) — nothing here
licenses freezing an API early. (Written when the tournament had two arms; Arm 2
was withdrawn 2026-07-31 and the unfrozen rule is otherwise unchanged.) Decisions
cited as HD-nnn are normative in [decisions.md](decisions.md).

## Views and reads

```clojure
(:require [re-frame.hicasso :as h :refer [defview sub]])

(defview todo-row [{:keys [id]}]
  (let [todo     (sub [:todo/by-id id])
        editing? (sub [:todo.ui/editing? id])]
    [:li
     [:span (:title todo)]
     [:button {:on-click [:todo/toggle id]} "✓"]
     (when editing?
       [:input {:value    (sub [:todo.ui/draft id])
                :on-input [:todo.ui/edit id ::h/value]}])]))

(defview todo-list [_]
  [:ul
   (for [id (sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])
```

- **`defview` mints a boundary** — a real React function component. In hiccup,
  `[view-var props-map]` is a boundary child (a React element; `:key` lives in
  the props map — no metadata keys; native-element keys are likewise `:key` in
  the props position). A **plain function call** `(helper …)` inlines into the
  enclosing boundary — no boundary of its own; a helper's `sub` reads are
  ordinary ambient reads (HD-002 — `sub` is the one product surface). A bare
  seq of boundary children requires keys (dev warning); a plain function in
  head position is a loud error, never a silent embedding; the `for`-lowering
  sugar is not v0 (HD-016).
- **`sub` returns a value, legal *anywhere* inside a render** —
  conditionals, loops, helpers, as sketched above. The operator ruled this the
  only ergonomically acceptable surface (HD-002, superseded 2026-07-31); the
  correctness and cost gates a per-read collector must still clear — the
  tripwire, the ownership state machine, the survival metric — are unwaived
  and adjudicated in [hd-002-adjudication.md](hd-002-adjudication.md). `sub`
  outside a render is an error; `subscribe-once` is the sanctioned snapshot for
  handler/utility code; framework subs (machine tags, resource/mutation state,
  route identity) read identically to app subs.
- **Bodies are pure and re-runnable** (StrictMode runs them twice). No
  `useState`/local reactive cells for app state (HD-009); hooks belong at host
  edges — though a body *is* an honest React FC, so an advanced author who uses a
  hook simply takes on React's hook rules themselves (HD-003: taught fence, not
  runtime police).

**The grouped spelling of the same view, kept as the collector's comparator**
(HD-002 — ergonomically rejected as a product surface, not a fallback) — pre-
declared so it is scored from day one, not discovered mid-clock:

```clojure
(:require [re-frame.hicasso :as h :refer [defview use-subs]])

(defview todo-row [{:keys [id]}]
  (let [{:keys [todo editing? draft]}
        (use-subs {:todo     [:todo/by-id id]
                   :editing? [:todo.ui/editing? id]
                   :draft    [:todo.ui/draft id]})]
    [:li
     [:span (:title todo)]
     [:button {:on-click [:todo/toggle id]} "✓"]
     (when editing?
       [:input {:value draft :on-input [:todo.ui/edit id ::h/value]}])]))
```

**The component ABI** (HD-016; pinned by the keyed insert/delete/reorder witness):

| Head | Props | Children | `:key` | `:ref` |
|---|---|---|---|---|
| Native tag | attr map | trailing forms; seqs realized once, flattened one level; `nil`/`false` render nothing, `true` errors | `:key` in the attr map | callback ref, legal |
| Hicasso view | one props map | trailing forms, delivered as `(:children props)` (realized vector) | in the props map; **extracted before the body sees props** (React's contract) | not a v0 surface (use ids) |
| Fragment `[:<> …]` | — | trailing forms | on the fragment vector's props map if keyed | — |
| Foreign (`defhost` / `[:>]`) | converted per declaration/defaults | hiccup → elements | `:key` in props | callback ref, legal |

An existing React element is a legal child anywhere (pass-through). A view may
return `nil`, one root, or a fragment.

`:ref` takes a **function** in v0 — the taught form, and the one the guide
teaches, because React 19 makes attach and teardown structural there (whatever the
callback returns is its cleanup). A **vector** is the reserved value-space for the
later data spelling (`{:ref [registered-id config]}`) and is refused loudly —
`:rf.error/hicasso-ref-vector-reserved` (HD-022). The reservation is one branch and
one error id; it exists so the imperative escape can become data without minting a
second attribute name, and it carries one honest limit that shapes what you write
today: a ref callback fires on attach and detach and **never on config change**, so
steady-state change belongs on an effect, not on the ref.

The vector is the **only** value the codec refuses there. An object ref
(`(react/createRef)`) is untaught rather than illegal: React 19 carries `ref` as an
ordinary prop, so it crosses by identity at a native tag and at a `defhost`/`[:>]`
crossing alike and behaves exactly as React documents (HD-016's 2026-08-05 note,
rf2-d03av).

## Event intent as data

- A literal vector at an event position is the intent; `::h/value` is the value
  placeholder (~97% of corpus handler sites become pure data). Ordinary functions
  remain legal at event positions.
- Census-weighted policy defaults: `:on-submit` intents auto-prevent (the rare
  opt-out is the `h/fn` escape — a callback holds the event, so it owns it); a
  data key-map `{:on-keydown {"Enter" [...] "Escape" [...]}}` with the
  composition law centralised — a composing Enter (IME, including the
  keyCode-229 legacy signal) commits nothing.
- Everywhere else, prevention is opted **in** by one reserved head,
  `[::h/prevent [:conduit/show-your-feed]]` (HD-026) — the shape is the
  anchor-acting-as-a-button, which a click default cannot absorb because a
  modifier-click on a real link must still open a tab. It is a head rather than metadata on the intent
  because metadata does not participate in `=`, and HD-021's headless door reads
  intent vectors by equality. The grammar is closed: exactly one inner intent
  vector, unwrapped before `::h/value` is looked for, and anything else is
  `:rf.error/hicasso-malformed-prevent` naming the position.
- Callbacks generated from intents close over the boundary's frame (resolved once
  per boundary from the substrate's single internal context) so they remain valid
  when the browser invokes them after render scope unwinds.
- **The route-link is a plain function over routing's link seam** (HD-027; the
  census counts 106 and calls the form tier-1). The author names a route and
  params and never sees a URL —
  `(route-link {:to :conduit.profile/show :params {:username u}} u)` — and what
  comes back is one real `<a>` whose href is routing's and whose click decision
  travels as data under the second reserved head, `[::h/navigate {…}]`. It
  inlines (no boundary, no hook, no read), the click law is routing's
  `activate-link!` seam verbatim (modifier-click and native anchors stay the
  browser's), and its `:on-click` is the pre-navigation veto:
  `[::h/prevent [:app/event]]` there cancels the navigation and dispatches the
  app intent instead — the cancelable-navigation case the prevent head was built
  for. A bare intent vector at that position is refused loudly: the click
  already produces the one routing intent.

### When a vector is not enough: one form, and the position decides (HD-024)

There is **one** callback form, `h/fn`, and it is an **ordinary function**:

```clojure
[:input {:type "file"
         :on-change (h/fn [e] [:upload/picked (js/Array.from (.. e -target -files))])}]
```

| Position | Contract |
|---|---|
| a native `:on-*` prop | **event** — a returned vector is dispatched; any other return is ignored |
| a `defhost` `:callbacks` entry | as **declared** (`:event`, `:handler` or `:render`) |
| any other walked prop position (a slot, a foreign render prop) | **render** — pure; the return is output, and dispatching from inside is a loud error **naming the position** |
| `:ref` | React's own contract; not lowered |
| anywhere Hicasso does not walk | a plain function; it runs, and its return is ignored |

A view's props map is not a position — it is data in transit, exactly as an intent
vector is; the value is lowered where it finally lands. Ordinary functions remain
untouched at every position, which is why there is no separate identity-passthrough
form: the codec already hands functions to React by identity so `React.memo` and
handler-identity bail-outs keep working.

**The declared contract governs every carrier at that position**, not just the
`h/fn`. An intent vector and a key-map are each a dispatch and nothing else, so
they are accepted at `:event` and refused at `:handler` and `:render` with
`:rf.error/hicasso-intent-at-a-non-event-contract` — otherwise the value would be
selecting the contract, which is the thing the row above forbids.

**The vector spelling is event-first.** `::h/prevent`, `::h/value`, `::h/checked`
and a key-map's key lookup read the DOM event from **argument one** — what a
native position hands them and what `onDraft(event)` hands them. A value-first
foreign invoker (`onPick(value, event)`) raises
`:rf.error/hicasso-intent-needs-the-event` naming the position, rather than the
engine's `value.preventDefault is not a function`; `h/fn` is the spelling there,
since it receives every argument in order. An intent carrying neither a marker nor
a decorator never reads its argument, so it is correct under any invoker contract.

The point of the collapse is the last row. In the predecessor the roster forms are
carriers — marker objects — so one handed to a raw `#js` prop is not callable and
the failure is the engine's own `TypeError`, naming nothing the author wrote. A
form that is a function cannot fail that way.

## Forwarding attributes — one merge, spelled `:&` (HD-023)

There is **one** attribute merge and it is a reserved key in the attribute map, not
a call:

```clojure
(defview field [{:keys [id busy?] :as attrs}]
  [:input.form-control {:& (dissoc attrs :id :busy?)
                        :value    (sub [:editor/field id])
                        :disabled busy?
                        :on-input [:editor/edit-field id ::h/value]}])
```

**The law is unconditional: the literal keys written in the map always win over
`:&`.** That is HD-010(a)'s owned-literal law applied to every merge rather than
only under theming, and it is what makes the controlled-input door
non-forfeitable — a merge cannot reach an owned literal, so there is no wrong
choice of syntax to make. The case where a caller override *should* win is spelled
by not writing the literal.

Three details. `:key`, `:ref` and `::h/revision` are never taken from a `:&` map
(they address the element the caller wrote, and a remainder that carries a
`::h/revision` is refused loudly rather than quietly ignored — it would
otherwise be asking to force a re-baseline on a field whose author never wrote
one). The same key and the same law hold at a crossing — a
view head, a `defhost` head, `[:>]` — because `:&` is merged before any conversion
and the conversion that follows is the position's own, so a forwarded `:className`
crosses under the name it was written as. And an element's own classes belong on
the **tag**, where the shorthand composes them with a forwarded class rather than
one silently replacing the other.

Both halves of the law are enforced on the **slot the key is emitted into**, not on
the key itself. Prop names reach React through one canonicalisation, so `"key"`,
`:x/key` and `'key` are all React's key, and `:onInput` is the same handler as an
owned `:on-input` — a remainder cannot reach either by changing how it spells them
(HD-023(c′)). The tag shorthand is folded on the emitted slot for the same reason:
an explicit id beats `#tag` and a declared class composes with `.foo` whether it
arrives as `:id`/`:class`, as `"id"`/`"className"`, or as `:x/id`/`:x/class`
(HD-023(c″)).

## The controlled-input door

Controlled text rides the synchronous door: dispatch → drain/commit → re-render
lands the echo in the keystroke's own task; the caret survives value
reassertion, and the key-map's composition gate keeps a composing Enter from
committing (R-A1/R-A2 are v0's acceptance). **A live IME composition is carved
out of the convergence, and that is a deliberate divergence from plain React**
(`rf2-digtt`, ruled 2026-08-03; HD-019's dated addendum). Nothing writes a
controlled text field while a composition is running — not the runtime's
converge and not React's own end-of-event restore — so a model that refuses or
normalises what is being composed no longer destroys the exchange to say so:
the composition survives, and the refusal or normalisation lands whole, once,
at `compositionend`. Everywhere else the conduct is unchanged, including the
model, which still sees and still refuses every intermediate composition state.
Plain React and the UIx port abort the exchange in the same case; that is
measured beside this in one run, and it is what the divergence is measured
against. **Witnessed on Chromium only** — the harness drives composition over
CDP, which is Chromium's protocol. On the lean-React arm the
end-of-discrete-event restore is React's; a PATCH back end must own it
(architecture.md, hard gate).

### Resetting a field — `::h/revision`, the third reserved key

Resets are by **explicit caller revision, never value equality** (HD-019's law,
kept from D016). `::h/revision` is where that law is spelled at the element:

```clojure
[:input {:value    (sub [:editor/field id])
         ::h/revision (sub [:editor/baseline-id id])
         :on-input [:editor/edit-field id ::h/value]}]
```

A change to the revision re-baselines the field to the model **without
remount** — the node, the focus and the selection survive, and the caret lands
at end-of-model on the commit carrying the reset. A `:value` that changes under
an *unchanged* revision continues the draft rather than resetting it, which is
what makes the distinction explicit rather than guessed. The comparison is `=`,
so equal-but-fresh values are inert.

**Write a revision the way you would write an instance key**: a domain fact
your events put in `app-db` — a record id, a load generation, a "form opened
at" stamp. Never a render-order index, never a counter minted in render, never
`random-uuid`; each of those resets the field on every render.

Four limits, stated rather than discovered. A revision arriving **during a
live IME composition defers to the exchange's close**, like everything else on
this path — the model is correct throughout, the glass is not, and there is no
cancel primitive to do better with. On an *accepting* field a post-bump edit
**supersedes** the reset by ordinary event order, so the close lands the
then-current model rather than the model the reset produced. A revision
arriving **mid-hydration defers past adoption** for the same reason it defers
past a composition: React discards the server's node when any client render
lands before adoption completes — with or without a revision change, so this
is adoption's conduct and not the prop's — and after adoption a bump keeps the
node and lands the reset normally. And the spelling is matched **exactly**: a
bare `:revision` is not this prop, and becomes an
ordinary DOM attribute — silently, and with a namespaced keyword's namespace
deleted on the way, so `:rev/a` and `:other/a` both show as `revision="a"` in
devtools. On anything that is not a controlled `<input>`/`<textarea>` the prop
is a loud refusal, `:rf.error/hicasso-revision-not-controlled` — including a
value-less checkbox, though a checkbox carrying a form-submission `value` is
accepted and the revision is simply inert there.

This is the single prop and nothing more: no commit/cancel intents, no
acknowledgement that the reset landed, no caret-policy knobs. The post-v0
buffered-controls ladder **consumes** this trigger; it never extends it.

## The interop door (HD-011)

`defhost` is the door, and the only form taught — one declaration per foreign
component, with strong defaults:

```clojure
(h/defhost date-picker DatePicker)  ;; defaults: shallow camelCase props,
                                    ;; hiccup children → elements,
                                    ;; fns pass through, SSR placeholder
```

Usage `[date-picker {...}]` is indistinguishable from a native view; the JS
require stays quarantined in one `.cljs` host namespace; the crossing has a
declared identity for tooling; policy overrides live on the declaration. A
migration codemod (collect `[:> X …]` sites → emit the defhost block → rewrite
call sites) upgrades sites incrementally. **The one raw escape** (HD-011),
explicitly secondary to the declaration:
`[:> Component props & children]` — same foreign lowering path, same default
conversions, `.cljs`-only at that node, reduced structural identity; for
runtime-selected components, `memo`/`lazy` values, render-prop-supplied
components, providers an ecosystem library hands you, and one-off migration
sites. The guide's rule: **declare what you use twice.** Bare-head auto-hosting
stays rejected — one sentinel, not two shortcuts. Foreign providers are hosted
either way; imperative SDKs use ordinary host-edge React (refs/effects, HD-003).

## Theming — and why there is no context API (HD-010)

React context is not a Hicasso-native mechanism: the substrate owns exactly one
internal context (frame identity). Library and app theming is three layers, none
of them context:

1. **Design tokens as CSS custom properties** — the cascade *is* a context
   system: nearest-ancestor-wins, subtree-scoped, platform-native. A theme switch
   is one attribute flip: zero React re-renders.
2. **Parts as data addresses; theme as pure data/fn** — controls emit
   keyword-tagged parts; a theme maps part-address → classes/attrs, merged
   `base < app-theme < instance-props`; applying a theme is a tree→tree
   transform unit-testable by equality. No part registry, no multimethods.
   Two laws (HD-010): the **owned-literal merge law** — `:key`, `:ref`,
   controlled `:value`/`:checked`, and owned handlers are unoverridable by theme
   or parts; and the **static-map law** — runtime-switchable styling lives in
   CSS variables, part→class maps are boot-static, structural replacement goes
   through children/slots, never parts.
3. **App-db + `sub` for the theme choice only** — user-picked, per-frame,
   time-travelling.

## Presence — phase as data (HD-025)

`h/presence` retains exiting keyed children for `:timeout-ms`. A child says what it
looks like in each phase, **in its own attribute map**:

```clojure
(h/presence {:timeout-ms 300}
  (for [t (sub [:toasts/visible])]
    [:div.toast {:key (:id t)
                 ::h/unmounting {:class "toast toast--exit"
                                 :inert true :aria-hidden true}}
     (:message t)]))
```

No child view, no ambient read. When the child *is* a boundary, the phase arrives
as an ordinary prop — `[toast-card {:key id :toast t :rf/phase :unmounting}]` — so
it cannot be read from the wrong render scope, it appears in a structural test's
props map, and a headless test supplies it with no clock. There is no
`presence-phase`.

An override wins over the node's own literals (that is what an override is) and can
never reach `:key` or `:ref`, the same law `:&` carries. Writing one on a **view**
head is a loud error naming `:rf/phase`, because the boundary cannot see inside an
opaque child. `:timeout-ms` is mandatory and is both the retention length and the
hard terminal bound; re-entry cancels exit; every dynamic child needs a `:key`;
presence never dispatches domain mount/unmount events.

**Enter is the weak half.** A `:mounting` → `:present` class flip can lose the race
to paint. `::h/mounting` exists, but the reliable spelling for enter is an
animation on insertion (or `@starting-style`); exit is the phase that transitions
happily, because the node is already painted.

## Ephemeral state (HD-009)

There is no `local`/ratom-equivalent, and no `useState` for app state. In order:
CSS for hover/focus; platform-carried state (the top layer owns open/dismiss;
resources/mutations own async status; the controls kit owns drafts/revisions);
host-private React state at host edges for geometry/composition; and app-db for
everything semantically meaningful — where the tax is per-*concern*, not
per-instance (one parametric sub + one named event serve every instance). The
one-declaration sugar for that pattern was **ruled into v0 on 2026-08-04**
(HD-009's dated addendum in [decisions.md](decisions.md) is the record; it is
in implementation, not yet landed):

```clojure
;; RULED 2026-08-04 — in implementation, not yet landed; spelling [unfrozen].
(h/reg-state ::open {:default false})
;; mints: a parametric sub (sub [::open ikey])
;;        a concern-named setter event [::open ikey v] — never a generic ui/set
;;        the documented path [:ui ::open ikey]
;; clear: the framework event [::h/clear ::open ikey] removes the entry;
;;        bad keys refuse loudly (:rf.error/hicasso-state-bad-key)
;; nesting: (h/child-key parent-key part)
```

The instance key is authored data — domain ids first, entity-qualified id
values when one widget serves two entity types, placement-like vs value-like
sharing, "a good React `:key` is a good instance key" — four rules taught in
[the guide](draft-guide/07-ephemeral-state.md), not policed. What HD-009 froze
about any such sugar still holds under the ruled design: it mints a **named**
setter event rather than a generic `ui/set`, and it is sugar rather than a
state system — no runtime state, no hooks, no context.

## Testing doors

- **Headless** (HD-021): a structural render returns the hiccup tree as data —
  intent vectors assertable by equality, sub reads overridable through a pure
  read resolver, no browser. Scope: hook-free tier-1 bodies; hook-using and
  foreign regions are mounted-test territory — no fake hook dispatcher, ever.
  This is what deletes most of the corpus's 364 `data-testid` retail.
- **Mounted**: the shared browser facade (act, root lifecycle, residue
  assertions) with zero leaked subscription ref-counts after teardown as a
  standing assertion.
- **Tooling**: the index's evidence seam (HD-005) lets explain-render-class tools
  attach in dev at zero production cost; the registry/manifest surface (views
  enumerable with schemas, docs, source coordinates) is the AI-ergonomics door,
  post-v0.
