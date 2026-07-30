# Theming

> **Pre-implementation draft — Hicasso does not exist yet.** This page describes the
> *designed* surface so it can be read before it is built. Spellings marked
> **[unfrozen]** are placeholders that will change. The whole tree is disposable: it
> is rewritten after the P2 fork ruling, against a real implementation. Normative
> source: [decisions.md](../decisions.md) (HD-001…HD-021).

Every React component library reaches for context to theme itself. Hicasso doesn't
ship a context API at all, and its theming uses none.

This is not asceticism. Context is a side channel: it is invisible to the data tree,
which kills headless testing and makes SSR harder; it costs a hook per consumer,
against a ≤2 hook budget already fully spent; it re-renders every consumer when it
changes; and it is a second dependency-injection channel next to props. The platform
cascade and the data tree each do the job better on every axis the charter measures,
and the predecessor library's real usage — a global theme plus per-instance
overrides — never needed subtree context in the first place.

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

Switching theme is one attribute flip on one element. **Zero React work** — no
re-render, no hook, no diff. Nothing in your component tree knows it happened, which
is exactly right, because nothing in your component tree needed to.

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
not in the record. See **Not settled yet** — this layer is the least specified of
the three.

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

## The two laws

Layers 1 and 2 would be a footgun without these. Both are ruled in HD-010.

### (a) The owned-literal merge law

**`:key`, `:ref`, controlled `:value` and `:checked`, and owned event handlers are
unoverridable by a theme or by parts.**

A theme can style a field. It can never rewrite the field's controlled contract.
Without this law, a stylesheet-shaped piece of data could reach in and clobber the
`:value` of a controlled input — and you would debug that as an input bug for a day
before you thought to look at the theme.

### (b) The static-map law

**Anything runtime-switchable lives in CSS variables. Part-to-class maps are
boot-static per app. Structural replacement goes through children and slots, never
through parts.**

The three halves of that, spelled out:

- If it changes while the app runs — light and dark, density, brand — it is a CSS
  variable.
- A part-to-class map is fixed at boot. Changing one is an app rebuild, **by
  design**. The "theme switch is zero React work" claim holds for the token layer,
  and this is the price.
- If you want a different *structure* — a custom menu row, a replaced icon — that is
  a child or a slot. Parts address the pieces a control renders; they do not replace
  them.

Break (b) and parts become a second, worse rendering system driven by data at
runtime. That is the failure mode the law exists to prevent.

## React context is still there

HD-010 bans a *Hicasso* context API and context-based *theming*. It does not ban
React.

The substrate keeps exactly one internal context — frame identity — and ordinary
React context remains available to an advanced author at the HD-003 escape hatch: a
compound-component contract you are implementing, or a provider an ecosystem library
demands. Foreign providers come in through [`defhost`](05-interop.md) like any other
component.

Using it means taking on React's rules, a hook per consumer, and a node your
structural tests can see less of. Which is the honest trade, stated once, rather
than a mechanism the guide pretends doesn't exist.

## Troubleshooting

No Hicasso error ids exist yet; this table names mechanisms.

| Symptom | What went wrong | Fix |
|---|---|---|
| Theme switch re-renders the whole app | The switch went through app-db into a prop that every view reads | Switch a CSS variable scope instead — the token layer is the runtime-switchable one |
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
| How a control declares a part | **Not addressed.** "Controls emit keyword-tagged parts" is the whole of the record; the attribute or macro that does it is unnamed |
| How an app installs a theme | **Not addressed.** The merge order `base < app-theme < instance-props` is ruled; the mechanism that supplies the app-theme layer is not. Since there is no context, it is presumably passed or registered — the record does not say which |
| The part-address shape | This guide writes `[:typeahead :root]` by analogy with the record's "part address" language; the actual shape is unstated |
| CSS custom property naming convention | **Not addressed.** The `--app-*` prefix above is this guide's invention |
| Where the boot-static part map lives | Implied by law (b) to be fixed at build; the residence is unstated |
| The controls kit that would ship parts | **Post-v0** |
