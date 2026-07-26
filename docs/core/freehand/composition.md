# Composition and theming

You need more than “Hiccup in `:children`” — fixed regions, row renderers, safe
attribute forwarding, or library parts that consumers can style without forking
your control.

Use the **lightest** structural form that matches the job. Mixing forms is fine;
inventing a slot registry or post-render tree rewrite is not. Host widgets are a
separate plane (qualified leaves, behaviors, wrappers).

> **Props → trailing children → compound views → pure slots → declared child views.**

## The ladder (read top-down)

| Need | Form | Example |
|---|---|---|
| Plain data (string, number, enum, event vector) | **prop** | `{:title "Cart" :on-close [:cart/close]}` |
| One default / fixed body region | **trailing children** → reserved `:children` | `[panel {:title "…"} [body {}]]` |
| Several fixed regions (trigger + panel, label + control) | **compound child views** | named child components for each region |
| Parameterised row / cell / item renderer (pure) | **`v/render-fn` + `v/slot`** | table row body from the caller |
| Stateful customisation (own `sub`, events, identity) | **declared child view** | `[person-row {:key id :id id}]` |

Prefer the **highest** row that still fits. A pure string is a prop, not a slot.
A pure row renderer is a slot, not a child view with empty events. A control that
must subscribe is a **view boundary**, not a closure smuggled through a prop.

## One props map

Every internal view receives **one** map. There is no second args vector of
children the way some React APIs split “props” and “kids.”

```clojure
(v/defview panel [{:keys [title children]}]
  [:section.panel
   [:h2 title]
   children])

[panel {:key panel-id :title "Details"}
 [details {:id panel-id}]]
```

Contract details that bite people:

| Rule | Meaning |
|---|---|
| Trailing children | become the reserved **`:children`** vector in the props map |
| Caller `:children` in the props map | **rejected** — write trailing forms, not a hand-built key |
| `:key` | sibling identity only; **stripped** before the body sees props |
| Children policy | descriptor metadata **`:children-policy`** (`:none` / `:optional` / `:required`) — a view can refuse children |
| Equality | internal props use re-frame value equality for memoisation |
| Host mutables | refs, instances, foreign handles → [host boundary](host-boundaries.md), not neutral props |

Optional props schemas (Malli vector form, closed by default) document and check
what a library leaf accepts. `:key` stays outside the schema; children policy is
descriptor metadata, not a prop field you invent ad hoc.

### Declaring children policy

Children policy is descriptor metadata (product spine ABI field
**`:children-policy`**). Authoring puts the same key on the `v/defview` options
map alongside `{:compiled true}` when you need to state it. Values are exactly
`:none`, `:optional`, or `:required` — not a prop, and not a second map key
named `:children` (that name is reserved for the trailing-children **vector**
delivered into the body).

```clojure
;; Leaf that must not be given trailing children
(v/defview icon-button
  {:children-policy :none}
  [{:keys [event label]}]
  [:button {:on-click event :aria-label label}
   [icon-glyph {:name label}]])

;; Default region allowed but optional
(v/defview card
  {:children-policy :optional}
  [{:keys [title children]}]
  [:article.card
   (when title [:header title])
   (when (seq children) [:div.card-body children])])

;; Parent that requires a body region
(v/defview dialog-shell
  {:children-policy :required}
  [{:keys [title children]}]
  [:section.dialog
   [:h2 title]
   [:div.dialog-body children]])
```

| Policy | Meaning |
|---|---|
| `:none` | trailing children are a finding / error — misuse is not silent drop |
| `:optional` | zero or more trailing children → `:children` vector (may be empty) |
| `:required` | at least one trailing child expected |

Omit the option when you accept the product default for application views
(typically optional). Library leaves that own a fixed chrome should set `:none`
or `:required` so the checker and catalog can prove the contract.

## Trailing children (default region)

Use trailing children when the parent has **one** obvious content hole: the body
of a card, the items of a list shell, the main of a layout.

```clojure
(v/defview card [{:keys [title children]}]
  [:article.card
   (when title [:header title])
   [:div.card-body children]])

[card {:title "Invoice"}
 [line-items {:invoice-id id}]
 [totals {:invoice-id id}]]
```

Both trailing forms land in one `:children` vector. The parent places that vector
where the default region lives. No registry, no string slot names.

## Compound child views (fixed regions)

When a control has **several** fixed holes — trigger vs panel, toolbar vs canvas,
label vs control — prefer **named child views** (or named props that hold Hiccup
for pure markup) over overloading a single `:children` bag with positional magic.

```clojure
;; Library shape (illustrative): fixed regions as props that hold structure,
;; or as dedicated child components the parent knows how to place.

(v/defview disclosure [{:keys [id label children]}]
  (let [open? (v/sub [:disclosure/open? id])]
    [:div.disclosure
     [:button {:aria-expanded open?
               :on-click      [:disclosure/toggled id]}
      label]
     (when open? [:div.body children])]))
```

Rules of thumb:

- Fixed regions stay **named** in the parent’s contract (schema/docs).  
- Event vectors inside those regions stay **data** — structural tests still see
  intent after composition.  
- Do not use compound regions as a substitute for theming (`:parts` is
  attrs-only — see [below](#theming-and-parts)). Replacing structure is
  composition; recolouring is CSS/parts.

## Parameterised content: `v/render-fn` and `v/slot`

Tables, lists, and grids often need “for each row, render **this** pure body.”
That is not trailing children (the parent owns the loop) and not a full child view
(the body may be a few cells with no state).

```clojure
(v/defview data-table [{:keys [rows row]}]
  [:table
   [:tbody
    (for [item rows]
      [:tr {:key (:id item)}
       (v/slot row item)])]])

[data-table
 {:rows people
  :row  (v/render-fn [person]
          [:<>
           [:td (:name person)]
           [:td (:email person)]])}]
```

| Piece | Job |
|---|---|
| **`v/render-fn`** | caller-supplied pure body; no `sub`, dispatch, hooks, or refs |
| **`v/slot`** | parent applies that body at a known site with arguments |

**Interpreted** mode may also accept an ordinary pure function for the same job.
**Compiled** mode needs a **lexically visible** `v/render-fn` / `v/slot` so the
analyzer can see the body (see [Compilation](compilation.md)).

When the row needs a subscription, its own events, or stable occurrence identity,
extract a **declared child**:

```clojure
(v/defview person-row [{:keys [id]}]
  (let [person (v/sub [:person/by-id id])]
    [:tr
     [:td (:name person)]
     [:td [:button {:on-click [:person/edit id]} "Edit"]]]))

;; parent
(for [id ids]
  [person-row {:key id :id id}])
```

Do not hide `sub` inside an opaque function the parent treats as a “render prop.”
That breaks compilation, tools, and the “handlers are data” story.

## Keys and repeated children

Repeated view children use **real keys**:

```clojure
(v/defview todo-list [_]
  [:ul.todo-list
   (for [id (v/sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])
```

| Practice | Why |
|---|---|
| Stable identity in `:key` | rows move with their cells; presence and memoisation work |
| Pass domain id as a prop too | the body must not re-derive identity only from React’s key |
| Keyed `for` of declared views under compiled parents | the closed loop grammar can see structure |
| Unkeyed dynamic lists | broken identity; compile error when compiled |

A plain helper can still build a deliberately coarse region; isolate changing rows
as keyed declared children. No separate “region DSL” is required.

## Spreading props (attribute forwarding)

Composition of **structure** uses the ladder above. Forwarding **attributes**
uses two common-grammar forms. This section is the **canonical** explanation;
[events](events-and-handlers.md) (controlled door) and
[host boundaries](host-boundaries.md) (foreign `v/spread`) only point here.

Spreads do **not** replace children. Putting structure into a spread map is the
wrong plane — use trailing children, compound regions, slots, or child views.

### Two forms

| Form | Where | Promise |
|---|---|---|
| **`v/spread-safe`** | internal DOM / library element that must keep controlled + identity invariants | caller attrs sit **under** owned literals; forbidden keys cannot clobber the contract; **preserves the controlled-input door** |
| **`v/spread`** | **foreign** host head (open prop ABI) | later-arg-wins, so the owned literals go in the **overrides** position; the forwarded remainder is otherwise opaque — **does not** claim the door |

Same meaning in interpreted and compiled mode. The classic “attrs-forwarding
wrapper” (`labelled-field` → inner `input`) is ordinary vocabulary, not a
compiled-only trick.

### Shape

```clojure
;; owned literals first, caller (or part) map second
(v/spread-safe
  {:data-part "control"
   :value value
   :on-input (conj on-change ::v/value)}
  caller-attrs)

;; foreign host head — caller's remainder first, owned contract second so it wins
(v/spread
  (dissoc props :date :on-pick)
  {:selected date
   :onChange (v/event [js-date] [:picker/picked js-date])})
```

`v/spread` takes one or two arguments: `(v/spread base)` forwards a map alone, and
`(v/spread base overrides)` lets the overrides win every collision. Every key is
judged by the rule a literal attribute key is judged by — the same refusals, read
off the same emitted slot — so a map assembled at run time cannot smuggle in a
spelling the grammar refuses at a visible site. `:key` is refused outright: it is
not an attribute, and it is literal at the element that carries it.

### Merge law (`v/spread-safe`)

1. **Owned literals win.** Caller keys never override what the component wrote
   for identity, control, or handlers it owns.  
2. **Caller sits underneath** for everything else (`class`, `data-*`, approved
   ARIA/DOM attrs, extra listeners the component allows).  
3. **`:class` composes** — owned first, then the caller's, because a caller
   passing a utility class is adding to the element rather than replacing what the
   component put there.  
4. **Deny law** (these may not appear in the caller map at all):

| Denied | Why |
|---|---|
| `:key`, `:ref` | occurrence identity and host handles are not caller toys |
| `:value`, `:checked` | the controlled contract |
| the component's own `on-*` families, both bubble and capture phases | library-wired intents stay the product contract |

That list is the whole deny law, and it runs in **every** build rather than only
in development. An offender is a **loud refusal**, not a silent drop — and
alternate spellings do not route around it, because a key is judged by the slot it
is about to be written into, so a namespaced, string, symbol or already-camel
spelling of a denied name is denied with it.

Structure is not on the list because it is not an attribute: putting children into
a spread is the wrong plane entirely — use the composition ladder.

### Why `spread-safe` exists

A dynamic props map on a controlled input normally **forfeits** the synchronous
[controlled door](events-and-handlers.md#controlled-inputs) into batching.
Libraries still need to forward consumer attrs — `class`, analytics `data-*`,
extra ARIA — onto that input.

**`v/spread-safe` is the one dynamic-map form that keeps the door proof:** owned
`:value`/`:checked` and the owned handler remain literal and eligible; the
forwarded residue cannot rewrite them. Opaque `v/spread` at a foreign widget
cannot claim that guarantee — foreign components own their own timing.

### Worked example — library field

```clojure
(v/defview field
  [{:keys [label value on-change parts]}]
  [:label {:data-component "my.lib/field"
           :data-part "root"
           :class "my-lib-field"}
   [:span (v/spread-safe
           {:data-part "label"}
           (get parts :label))
    label]
   [:input
    (v/spread-safe
     {:data-part "control"
      :value value
      :on-input (conj on-change ::v/value)}
     (get parts :control))]])

[field {:label "Email"
        :value email
        :on-change [:account/email-changed]
        :parts {:control {:class "wide-control"
                          :data-analytics "signup-email"}}}]
```

The caller can restyle and annotate the control. They cannot pass
`{:value other :on-input [:different/event] :key :new-id}` through `:parts` and
steal the door. Full parts/theming policy: [Theming and parts](#theming-and-parts).

### Worked sketch — foreign widget

A foreign React component is not a vector head. `v/defhost` declares one and mints
the head, and it is the only thing that does:

```clojure
(v/defhost date-picker
  "A third-party date picker."
  DatePicker                                  ; the imported React component
  {:callbacks {:onChange :event}
   :children  :none
   :ssr       :client-only})

(v/defview date-field [{:keys [date on-pick] :as props}]
  [date-picker
   (v/spread (dissoc props :date :on-pick)
             {:selected date
              :onChange (v/event [js-date] (conj on-pick js-date))})])
```

Three things about that call site are easy to get wrong.

**The head is the declaration, not the component.** Putting `DatePicker` itself at
the head raises `:rf.error/view-bad-head`, which names the three legal forms; the
descriptor `v/defhost` binds is the third of them.

**Use `v/spread`, not `spread-safe`, and put the owned literals second.** `v/spread`
is later-arg-wins, so the forwarded remainder goes in the base position and the keys
you own go in the overrides — reverse them and a caller's `:selected` silently
replaces yours. `spread-safe` is the wrong tool here: its bound is a promise about a
controlled element you own, and a foreign component owns its own timing.

**Host prop names pass exactly.** `:onChange` reaches React as `onChange` — there is
no camelisation on this path, so `:on-change` would arrive as `on-change` and the
library would never see it. A callback reaches a host only at a position the
declaration named, and it arrives as a carrier from the
[escape roster](events-and-handlers.md#the-escape-roster); a function in an ordinary
prop is refused.

### Choosing quickly

| You are… | Use |
|---|---|
| Forwarding onto an **internal** `input` / DOM node / library leaf that is controlled | `v/spread-safe` |
| Merging `:parts` attrs onto a declared `data-part` | `v/spread-safe` |
| Forwarding remainder props onto a **declared `v/defhost` head** | `v/spread`, owned literals in the overrides position |
| Replacing a label, row body, or trigger structure | composition ladder — not a spread |
| Hoping a dynamic map keeps the door without `spread-safe` | it won’t — fix the merge |

## Interpreted vs compiled

The taxonomy is **common** to both modes:

| Surface | Interpreted | Compiled |
|---|---|---|
| Trailing `:children` | same contract | same contract |
| Compound fixed regions | same | same |
| Parameterised row | ordinary pure fn OK | lexically visible `render-fn` / `slot` |
| Stateful customisation | declared child view | declared child view (statically named under compiled parents) |
| Cross-mode children | fine | statically named interpreted children of a compiled parent are explicit; no hidden walker |

Call sites stay `[view props]`. Structural tests should not care which mode produced
the tree for the common subset. See [Compilation — seam laws](compilation.md#crossing-modes-and-seam-laws-author-facing).

## What is not composition

| Temptation | Why it is wrong | Do this instead |
|---|---|---|
| Late tree→tree “theme” that rewrites Hiccup | smashes keys, controlled values, handlers; no runtime tree under compiled leaves | CSS / `data-part` / safe `:parts` + this ladder |
| `:parts` that swap children or handlers | parts are attrs-only via `spread-safe` | structural form from the ladder |
| Dynamic map on a controlled input without `spread-safe` | forfeits the door into batching | `v/spread-safe` for the residue |
| `v/spread` on an internal controlled input | no door promise | `v/spread-safe` |
| Opaque reactive closure as a slot | hides `sub` / identity | declared child view |
| Emulating Radix `asChild` / prop cloning in neutral Hiccup | compound React protocol | a React component, entered as a child |
| Caller-built `:children` inside the props map | reserved channel is trailing forms only | trailing children syntax |
| Unkeyed list “for convenience” | identity and presence break | stable `:key` per child |

## Theming and parts

Library authors need a way to restyle controls without rewriting structure or
breaking controlled inputs. D018 settles Freehand’s contract: **CSS cascade for
tokens**, **`data-part` / `data-component` for addresses**, and **bounded `:parts`
maps** merged safely. No late tree-transform theme system.

App authors mainly consume `data-theme` and `:parts` from those libraries.

### Two planes

| Plane | Job | Mechanism |
|---|---|---|
| **Portable styling** | colours, spacing, density, motion tokens | CSS custom properties + classes / `data-theme` on a root or scope |
| **Structure** | replace a label, row, or trigger | this page’s ladder: children, compound children, `v/render-fn`/`v/slot`, declared child views |

Do not use “theme” to mean “rewrite the Hiccup after analysis.” Compiled leaves
have no runtime tree for that, and uncontrolled rewrites can smash `:key`,
controlled values, and handlers.

### Tokens: CSS, not reactive subs

Prefer:

```css
.my-app[data-theme="dark"] {
  --my-lib-field-bg: #1a1a1a;
  --my-lib-field-fg: #f5f5f5;
}

.my-lib-field[data-part="control"] {
  background: var(--my-lib-field-bg);
  color: var(--my-lib-field-fg);
}
```

A theme switch that only flips `data-theme` (or a class) on a root **re-renders
nothing** in Freehand. That is intentional: no per-leaf token subscriptions, no
fan-out.

Reserve re-frame values for the genuinely dynamic residue (user density preference
that must also drive layout math, not only paint).

### Parts: stable addresses

Components emit **literal** part markers, scoped by a component marker:

```clojure
(v/defview field
  [{:keys [label value on-change parts]}]
  [:label {:data-component "my.lib/field"
           :data-part "root"
           :class "my-lib-field"}
   [:span (v/spread-safe
           {:data-part "label"}
           (get parts :label))
    label]
   [:input
    (v/spread-safe
     {:data-part "control"
      :value value
      :on-input (conj on-change ::v/value)}
     (get parts :control))]])
```

Rules:

- **`data-part`** is identity for styling and docs — classes carry **variants**
  only, not “which part this is.”  
- Renaming a public part id is a **breaking** library change.  
- Document part ids in the component schema/catalog when you ship library leaves.  

### `:parts` maps: attrs only

Callers may pass bounded overrides:

```clojure
[field {:label "Email"
        :value email
        :on-input [:account/email-changed ::v/value]
        :parts {:label   {:class "quiet-label"}
                :control {:class "wide-control"
                          :data-analytics "signup-email"}}}]
```

Each declared part merges through **`v/spread-safe`** so the library’s owned
literals win and the controlled-input door stays intact. That is why `:parts` is
**legal on controlled parts**. Full merge law:
[Spreading props](#spreading-props-attribute-forwarding).

This must **not** be allowed as “theming”:

```clojure
;; illegal intent — must not replace controlled contract via :parts
{:parts {:control {:value other
                   :on-input [:different/event]
                   :key :new-identity}}}
```

Unknown part ids and denied attributes should become source-located development
findings.

Structural customisation is not a part map — use the ladder above. Replacing a
label with “icon + help popover” is structure, not CSS.

### Interpreted vs compiled (theming)

The portable plane (CSS + `data-part` + safe `:parts`) works in **both** modes.
Do not design a library theme that requires interpreting compiled output at
runtime. Arbitrary late tree→tree transforms may exist as **interpreted/test
tooling** only — never as the portable Freehand theme contract.

## Day-one checklist

1. Start with **props**; add trailing children only for a real default region.  
2. Name **fixed regions** when there is more than one hole.  
3. Use **`render-fn` / `slot`** only for pure parameterised bodies.  
4. Promote to a **declared child view** as soon as state or events appear.  
5. Key every dynamic list of views.  
6. Forward attrs with **`spread-safe`** (internal/controlled) or **`spread`**
   (foreign) — never a bare dynamic map on a controlled door.  

## If something feels wrong

| Symptom | Fix |
|---|---|
| Controlled input lost door after attrs forward | `v/spread-safe`, not bare merge / `v/spread` |
| Part override stripped role | owned a11y is denied in part merges — keep library roles |
| Slot body hides `sub` | declared child view, not a reactive closure prop |
| Theme requires rewriting structure | CSS / `data-part` / `:parts` attrs — structure uses the ladder |

Library styling depth (tokens, parts maps): sections above. Under compilation,
keep slot bodies lexically visible and child heads statically named.
