# Theming

> **Draft ahead of the product artefact.** Spellings marked **[unfrozen]** stay
> provisional until the API freeze. Theming is largely unexercised by the bench
> arm, and this page still carries one genuine hole — how the app-db theme
> choice reaches a DOM attribute — named below rather than papered over.

Every React component library reaches for context to theme itself. Hicasso doesn't
ship a context API at all, and its theming uses none.

This is not asceticism. Context is a side channel: it is invisible to the data tree,
which kills headless testing and makes SSR harder; it costs a hook per consumer,
against a ≤2 hook budget already fully spent; it re-renders every consumer when it
changes; and it is a second dependency-injection channel next to props. The platform
cascade and the data tree each do the job better on every axis that matters. Real
apps already live on a global theme plus per-instance overrides — that pattern
never needed subtree context in the first place.

Three layers do the work.

## Layer 1 — design tokens as CSS custom properties

The cascade *is* a context system. Nearest ancestor wins, it is subtree-scoped, and
it is platform-native.

```css
:root {
  --app-color-accent:  #2b6cb0;
  --app-color-surface: #ffffff;
  --app-radius:        4px;
}

[data-theme="dark"] {
  --app-color-accent:  #63b3ed;
  --app-color-surface: #1a202c;
}

.app-button {
  background: var(--app-color-accent);
  border-radius: var(--app-radius);
}
```

Nothing about the `--app-*` prefix is Hicasso's; name your custom properties
whatever your stylesheet already does.

Switching theme is one attribute flip on one element, and **restyling from there
costs zero React work** — no re-render, no hook, no diff. The cascade recomputes and
your component tree is never consulted, which is exactly right, because nothing in it
needed to be.

Read that claim precisely, because layer 3 depends on where its edge is. "One
attribute flip, zero React work" describes what happens *after* the attribute
changes. It says nothing about what flips it. See
[Layer 3 and the DOM](#layer-3-and-the-dom) — the one place this page has to report a
hole rather than teach around it.

## Layer 2 — parts as data addresses

A control emits keyword-tagged **parts**: named addresses for its internal pieces.
A theme is a map from part address to classes and attributes, and applying it is a
pure tree-to-tree transform.

```clojure
;; A theme: part address → classes/attrs.
(def compact-theme
  {[:typeahead :root]  {:class "ta ta--compact"}
   [:typeahead :input] {:class "ta__input"}
   [:typeahead :menu]  {:class "ta__menu" :role "listbox"}})
```

Merge order is fixed: **`base < app-theme < instance-props`**. The control's own base
wins least, the app's theme overrides it, and props at a specific use site override
both.

The property that makes this worth having: because it is a pure function from tree
to tree, you can unit-test a theme with `=`. No registry, no multimethods, no
runtime resolution order to reason about.

The spellings for declaring a part in a control and installing a theme in an app are
not settled. See **Not settled yet** — this layer is the least specified of the
three.

## Layer 3 — app-db for the choice

Which theme is selected is application state like any other: read it with a
subscription, write it with an event, and get frame isolation, time travel, and
Xray visibility for free.

```clojure
(rf/reg-sub :theme/current
  (fn [db _query] (:theme/current db)))

(rf/reg-event :theme/choose
  (fn [{:keys [db]} [_ theme]] {:db (assoc db :theme/current theme)}))
```

App-db holds the *choice*. It does not hold the tokens, and it does not hold the
part maps.

## Layer 3 and the DOM

Here is the part that is not joined up, and the reason this section exists rather
than a paragraph of confident prose.

**That event is a no-op as far as the browser is concerned.** It moves a keyword into
app-db. Layer 1's cascade keys off a `data-theme` attribute on a DOM element. Nothing
in the product surface says how the first becomes the second — the choice lives in
app-db, a theme switch is one attribute flip with zero React work *after* the flip,
and the line between them is unwritten. A guide that skipped the gap would be
telling you to write an event that visibly does nothing.

Two bridges close it. Neither needs a new API, and neither is the taught one yet.

### Bridge A — a scope view reads the choice

The obvious one: one view reads `:theme/current` and puts it on its own element.

```clojure
(defview theme-scope [{:keys [children]}]
  (into [:div {:data-theme (name (sub [:theme/current]))}]
        children))

;; At the root:
[theme-scope {} [app {}]]
```

`children` arrives as a realized *vector* of hiccup forms, which is why it is
spliced with `into` rather than dropped in as one child —
[Views and reads](02-views-and-reads.md#the-component-abi) has the rule.

**Its true cost.** The boundary that reads the theme re-renders on every switch —
that much is certain, and it is one boundary. What happens below it turns on the
[default value-equality bail-out](02-views-and-reads.md#boundaries-memoize-by-default)
rather than on how `theme-scope` gets its content. `app` is a boundary either way —
handed down as `children` or minted directly inside `theme-scope`'s own body — and
its props are unaffected by the theme, so they compare `=` at every re-render and
its own memo wrapper bails regardless of which style you write. The way to lose the
bail-out is to call `app` as a plain function instead of writing `[app {}]`. A plain
call has no boundary of its own to hold a memo wrapper, so it re-runs with
`theme-scope` every time — independent of which bridge you chose.

Element identity does not enter into it, because there is none to preserve.
Children cross a boundary as hiccup data, not as React elements, so `theme-scope`
mints a fresh element for `app` on every render whichever style you write. The
skip is therefore always the comparator's `=`, never a referential short-circuit
ahead of it — and `=` on an unchanged props map is cheap. What matters is only
that `app` stays a boundary.

### Bridge B — an effect writes the attribute

The other one: nothing reads the theme at all. The event that records the choice also
returns an effect, and the effect does the flip.

```clojure
(rf/reg-fx :theme/apply!
  (fn [theme]
    (.setAttribute (.-documentElement js/document) "data-theme" (name theme))))

(rf/reg-event :theme/choose
  (fn [{:keys [db]} [_ theme]]
    {:db          (assoc db :theme/current theme)
     :theme/apply! theme}))
```

**Its true cost.** Zero React work, literally rather than approximately: no
subscription, no boundary, no render. The "one attribute flip, zero React work"
claim is exactly true under this bridge.

**What you pay for it.** Three things, and they are real:

- **The DOM is no longer derived from state.** Rewind app-db in Xray and the
  attribute does not follow, because no event ran. Any path that replaces app-db
  without going through `:theme/choose` — time travel, a test fixture, a restored
  snapshot — leaves the document showing the old theme while app-db says otherwise.
- **Boot needs its own hand.** The initial theme is applied by an initial event that
  carries the same effect, or the first paint is unthemed.
- **It is not frame-isolated.** `document.documentElement` is one element and two
  frames on one page share it, which contradicts the "frame isolation for free" that
  layer 3 otherwise gets. Targeting each root's own node instead would need the
  effect to reach that node, and nothing in the product surface says an effect can.

### Pricing the bridges

**Which bridge is the taught one is not settled.** Naming one here would be
designing the missing half rather than reporting it. The shape of the choice is
clear enough: bridge A keeps the DOM derived from state at the cost of the one
reading boundary (the default bail-out covers the rest, per
[Bridge A](#bridge-a--a-scope-view-reads-the-choice) above); bridge B has no render
cost and gives up the derivation. It is the ordinary trade between rendering a fact
and imperatively asserting it.

What would settle it for the reader in practice: whether bridge A's cost holds at
one boundary under multi-frame load, and whether the theme attribute is per-root or
per-document. Both are open; neither is answered by picking a bridge in this guide.

## The two laws

Layers 1 and 2 would be a footgun without these.

### (a) The owned-literal merge law

**`:key`, `:ref`, controlled `:value` and `:checked`, and owned event handlers are
unoverridable by a theme or by parts.**

A theme can style a field. It can never rewrite the field's controlled contract.
Without this law, a stylesheet-shaped piece of data could reach in and clobber the
`:value` of a controlled input — and you would debug that as an input bug for a day
before you thought to look at the theme.

**The law is not theming-specific.** There is one attribute merge, spelled `:&`, and
the literal keys written in the map always win over it — whether what arrives is a
theme's part attributes, a caller's forwarded remainder, or anything else. So a
control that emits parts and a control that forwards props are the same code,
defended by the same rule, and there is no second merge form to choose between. See
[Controlled inputs](04-controlled-inputs.md#forwarding-attributes-onto-a-controlled-input).

### (b) The static-map law

**Anything runtime-switchable lives in CSS variables. Part-to-class maps are
boot-static per app. Structural replacement goes through children and slots, never
through parts.**

The three halves of that, spelled out:

- If it changes while the app runs — light and dark, density, brand — it is a CSS
  variable.
- A part-to-class map is fixed at boot. Changing one is an app rebuild, **by
  design**. The "theme switch is zero React work" claim holds for the token layer —
  for the restyle, and for the flip itself only under bridge B — and this is the
  price of it.
- If you want a different *structure* — a custom menu row, a replaced icon — that is
  a child or a slot. Parts address the pieces a control renders; they do not replace
  them.

Break (b) and parts become a second, worse rendering system driven by data at
runtime. That is the failure mode the law exists to prevent.

## React context is still there

Hicasso bans a *Hicasso* context API and context-based *theming*. It does not ban
React.

The substrate keeps exactly one internal context — frame identity — and ordinary
React context remains available to an advanced author at the host-edge escape: a
compound-component contract you are implementing, or a provider an ecosystem library
demands. Foreign providers come in through [`defhost`](05-interop.md) like any other
component.

Using it means taking on React's rules, a hook per consumer, and a node your
structural tests can see less of. Which is the honest trade, stated once, rather
than a mechanism the guide pretends doesn't exist.

## Troubleshooting

This table names mechanisms rather than error ids.

| Symptom | What went wrong | Fix |
|---|---|---|
| The theme keyword changes in app-db and nothing on screen changes | No bridge is wired — app-db holds the choice, and the cascade keys off a DOM attribute | Wire one of the two bridges above; the event alone does nothing to the document |
| Theme switch re-renders the whole app | Bridge A, with the themed content spliced in as a plain call rather than a `[boundary …]` vector — a plain call has no boundary of its own for the default bail-out to attach to | Put the content behind a boundary (children or a direct `[app {}]`, either works), or use bridge B |
| A time-travel rewind shows the old theme | Bridge B — the attribute was asserted by an effect, so it is not derived from the state you rewound | Expected under bridge B; it is the price named above |
| A controlled input's value gets clobbered by a theme | Should be impossible — law (a) | A runtime bug, not a usage error |
| A part override doesn't take effect | Merge order: instance props beat app theme beat base | Check which layer you set it in |
| Theme change needs a rebuild | Expected, if you changed a part-to-class map — law (b) | Move the switchable part into a CSS variable |
| A themed control renders the wrong structure | Parts address pieces; they don't replace them | Use children or a slot |

## When not to theme

If you have one application and one look, skip layer 2 entirely. Parts are for
**component-library authorship** — the case where someone else's app consumes your
control and needs to restyle its internals without forking it. An app styling its
own components has CSS, and CSS is enough.

Reaching for parts inside a single app buys you an indirection layer and a merge
order to remember, in exchange for a flexibility nobody is going to use.

## Not settled yet

| Question | Status |
|---|---|
| **How the app-db choice reaches the `data-theme` scope** | **Unresolved — the largest hole on this page.** The choice lives in app-db; the switch is one attribute flip after it lands. [Layer 3 and the DOM](#layer-3-and-the-dom) names both candidate bridges and prices them; picking one is still open |
| Whether the theme attribute is per-root or per-document | **Not addressed.** Layer 3 promises frame isolation; a document-level attribute does not have it |
| How a control declares a part | **Not addressed.** "Controls emit keyword-tagged parts" is the whole of the claim; the attribute or macro that does it is unnamed |
| How an app installs a theme | **Not addressed.** The merge order `base < app-theme < instance-props` is fixed; the mechanism that supplies the app-theme layer is not. Since there is no context, it is presumably passed or registered — which is unstated |
| The part-address shape | This guide writes `[:typeahead :root]` by analogy; the actual shape is unstated |
| Where the boot-static part map lives | Implied by law (b) to be fixed at build; the residence is unstated |
| The controls kit that would ship parts | **Post-v0** |
