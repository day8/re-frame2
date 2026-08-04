# Theming

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Behaviour matches the experimental arm under `implementation/freehand/test/re_frame/bench/hicasso/`.

Every React component library reaches for context to theme itself. Hicasso doesn't ship a context API, and theming doesn't need one.

Context is a side channel: invisible to the data tree (so headless tests can't see it), one hook per consumer (hooks already spend their budget elsewhere), a full re-render of every consumer on change, and a second dependency-injection channel next to props. CSS cascade and the data tree each do the job better. Real apps already live on a global theme plus per-instance overrides — that pattern never needed subtree context.

Three layers. Tokens in CSS, part maps as data, the *choice* of theme in app-db.

## Layer 1 — design tokens as CSS custom properties

The cascade already is a context system. Nearest ancestor wins, it is subtree-scoped, and it is platform-native.

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

Nothing about the `--app-*` prefix is Hicasso's; name your custom properties whatever your stylesheet already does.

Switching theme is one attribute flip on one element. **After the attribute changes, restyling costs zero React work** — no re-render, no hook, no diff. The cascade recomputes and the component tree is never consulted.

That claim is about *after* the flip. What puts the attribute on the DOM is layer 3's job — see [Bridging the choice to the DOM](#bridging-the-choice-to-the-dom).

## Layer 2 — parts as data addresses

A control emits keyword-tagged **parts**: named addresses for its internal pieces. A theme is a map from part address to classes and attributes. Applying it is a pure tree-to-tree transform.

```clojure
;; A theme: part address → classes/attrs.
(def compact-theme
  {[:typeahead :root]  {:class "ta ta--compact"}
   [:typeahead :input] {:class "ta__input"}
   [:typeahead :menu]  {:class "ta__menu" :role "listbox"}})
```

Merge order is fixed: **`base < app-theme < instance-props`**. The control's own base wins least, the app's theme overrides it, and props at a specific use site override both.

Because the transform is pure, you can unit-test a theme with `=`. No registry, no multimethods, no runtime resolution order.

How a control *declares* a part, and how an app *installs* a theme, are not settled yet — see **Not settled yet**. The merge order and the pure-function shape are fixed.

## Layer 3 — app-db for the choice

Which theme is selected is application state like any other: read it with a subscription, write it with an event. Frame isolation, time travel, and Xray visibility come free.

```clojure
(rf/reg-sub :theme/current
  (fn [db _query] (:theme/current db)))

(rf/reg-event :theme/choose
  (fn [{:keys [db]} [_ theme]] {:db (assoc db :theme/current theme)}))
```

App-db holds the *choice*. It does not hold the tokens, and it does not hold the part maps.

## Bridging the choice to the DOM

The event above moves a keyword into app-db. Layer 1's cascade keys off a `data-theme` attribute on a DOM element. Something has to join them.

Two practical options. Pick one for your app; neither needs a new Hicasso API.

### Option A — a scope view reads the choice

One view reads `:theme/current` and puts it on its own element:

```clojure
(defview theme-scope [{:keys [children]}]
  (into [:div {:data-theme (name (sub [:theme/current]))}]
        children))

;; At the root:
[theme-scope {} [app {}]]
```

`children` arrives as a realized *vector* of hiccup forms, which is why it is spliced with `into` rather than dropped in as one child — [Views and reads](02-views-and-reads.md#the-component-abi) has the rule.

**Tradeoff.** The boundary that reads the theme re-renders on every switch — one boundary, known cost. What happens below it depends on the [default value-equality bail-out](02-views-and-reads.md#boundaries-memoize-by-default). `app` is a boundary either way (handed down as `children` or minted inside `theme-scope`), and its props are unaffected by the theme, so they compare `=` and its memo wrapper bails. Lose that only by calling `app` as a plain function instead of writing `[app {}]` — a plain call has no boundary of its own, so it re-runs with `theme-scope` every time.

Element identity does not help here: children cross a boundary as hiccup data, not as React elements, so `theme-scope` mints a fresh element for `app` on every render. The skip is always the comparator's `=`, never a referential short-circuit — and `=` on an unchanged props map is cheap. Keep `app` a boundary and the rest of the tree stays quiet.

**Wins.** The DOM is derived from state. Rewind app-db in Xray and the attribute follows. Tests and restored snapshots stay consistent.

### Option B — an effect writes the attribute

Nothing reads the theme in a view. The event that records the choice also returns an effect, and the effect does the flip:

```clojure
(rf/reg-fx :theme/apply!
  {:platforms #{:client}}
  (fn [_ctx theme]
    (.setAttribute (.-documentElement js/document) "data-theme" (name theme))))

(rf/reg-event :theme/choose
  (fn [{:keys [db]} [_ theme]]
    {:db (assoc db :theme/current theme)
     :fx [[:theme/apply! theme]]}))
```

Custom effects are rows under `:fx`. The effect map is closed at the top level, so
a stray `:theme/apply!` beside `:db` is dropped with `:rf.error/effect-map-shape`
while the `:db` write still lands — which looks exactly like an unwired bridge.
`{:platforms #{:client}}` keeps the effect off the server, where there is no
`document`; [Server-side rendering](10-server-side-rendering.md) has the rule.

**Tradeoff.** Zero React work, literally: no subscription, no boundary, no render. The "one attribute flip, zero React work" claim is exact under this option. What you give up:

- **The DOM is no longer derived from state.** Rewind app-db in Xray and the attribute does not follow — no event ran. Time travel, a test fixture, or a restored snapshot can leave the document showing the old theme while app-db says otherwise.
- **Boot needs its own hand.** Apply the initial theme with an initial event that carries the same effect, or the first paint is unthemed.
- **It is not frame-isolated.** `document.documentElement` is one element; two frames on one page share it. Targeting each root's own node would need the effect to reach that node, and nothing in the product says an effect can today.

### Which one?

Ordinary trade: render a fact (A) vs assert it imperatively (B). A keeps derivation at the cost of one reading boundary; B has no render cost and gives up derivation. Neither is the taught default yet — whether A's cost holds at one boundary under multi-frame load, and whether the theme attribute is per-root or per-document, are still open. Until those settle, pick the tradeoff that matches your app.

## The two laws

### (a) Owned literals win the merge

**`:key`, `:ref`, controlled `:value` and `:checked`, and owned event handlers cannot be overridden by a theme or by parts.**

A theme can style a field. It cannot rewrite the field's controlled contract. Without this rule, a stylesheet-shaped map could clobber the `:value` of a controlled input — and you would debug that as an input bug for a day.

This is not theming-specific. There is one attribute merge, spelled `:&`, and the literal keys written in the map always win — whether what arrives is a theme's part attributes, a caller's forwarded remainder, or anything else. A control that emits parts and a control that forwards props share the same rule. See [Controlled inputs](04-controlled-inputs.md#forwarding-attributes-).

### (b) Switchable values live in CSS variables

**Anything that changes at runtime lives in CSS variables. Part-to-class maps are boot-static per app. Structural replacement goes through children and slots, never through parts.**

- Light/dark, density, brand — CSS variables, flipped by the attribute in layer 1.
- A part-to-class map is fixed at boot. Changing one is an app rebuild, **by design** — that is the price of "theme switch is zero React work" for the token layer.
- A different *structure* (custom menu row, replaced icon) is a child or a slot. Parts address the pieces a control renders; they do not replace them.

Break (b) and parts become a second, worse rendering system driven by data at runtime. That is the failure mode the law exists to prevent.

## React context is still there

Hicasso has no context API of its own and does not theme through context. It does not ban React.

The substrate keeps exactly one internal context — frame identity — and ordinary React context remains available at the host edge: a compound-component contract you are implementing, or a provider an ecosystem library demands. Foreign providers come in through [`defhost`](05-interop.md) like any other component.

Using it means taking on React's rules, a hook per consumer, and a node your structural tests can see less of. Honest trade, stated once.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| The theme keyword changes in app-db and nothing on screen changes | No bridge is wired — app-db holds the choice; the cascade keys off a DOM attribute | Wire option A or B above; the event alone does nothing to the document |
| Theme switch re-renders the whole app | Option A, with the themed content spliced in as a plain call rather than a `[boundary …]` vector | Put the content behind a boundary (children or a direct `[app {}]`), or use option B |
| A time-travel rewind shows the old theme | Option B — the attribute was asserted by an effect, so it is not derived from the state you rewound | Expected under B; it is the price named above |
| A controlled input's value gets clobbered by a theme | Should be impossible — law (a) | A runtime bug, not a usage error |
| A part override doesn't take effect | Merge order: instance props beat app theme beat base | Check which layer you set it in |
| Theme change needs a rebuild | Expected, if you changed a part-to-class map — law (b) | Move the switchable part into a CSS variable |
| A themed control renders the wrong structure | Parts address pieces; they don't replace them | Use children or a slot |

## When not to theme

If you have one application and one look, skip layer 2 entirely. Parts are for **component-library authorship** — someone else's app consumes your control and needs to restyle its internals without forking it. An app styling its own components has CSS, and CSS is enough.

Reaching for parts inside a single app buys you an indirection layer and a merge order to remember, in exchange for flexibility nobody will use.

## Not settled yet

| Question | Status |
|---|---|
| How the app-db choice reaches the `data-theme` scope | Open. Both bridges above work; neither is the taught default yet |
| Whether the theme attribute is per-root or per-document | Open. Layer 3 promises frame isolation; a document-level attribute does not have it |
| How a control declares a part | Open. "Controls emit keyword-tagged parts" is the claim; the attribute or macro is unnamed |
| How an app installs a theme | Open. Merge order is fixed; the mechanism that supplies the app-theme layer is not |
| The part-address shape | This guide writes `[:typeahead :root]` by analogy; the actual shape is unstated |
| Where the boot-static part map lives | Implied by law (b) to be fixed at build; residence unstated |
| The controls kit that would ship parts | Not shipped yet |
