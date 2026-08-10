# Theming and i18n

You want a dark mode and a second language. In most React stacks each arrives
with machinery: a ThemeProvider, an i18n context, a hook per consumer.
[Hicasso](glossary.md#hicasso) ships neither, because neither job needs adapter support. Tokens live
in CSS, strings are ordinary values, and the current choice — theme or locale
— is one key in app-db that ordinary subs read.

> **Tokens live in CSS, strings are data, the choice lives in app-db, and the
> absence of a theming or i18n subsystem is the design.**

## Design tokens in CSS

The cascade is already a scoping system: the nearest ancestor wins, the scope
is a subtree, and the mechanism is platform-native. Put your tokens on
`:root` and override them under a theme attribute:

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

The `--app-*` prefix is not [Hicasso](glossary.md#hicasso)'s. Name your custom properties the way
your stylesheet already does. `:root` *is* the light theme, so nobody writes
a `[data-theme="light"]` block.

A theme switch is one attribute flip on one element. After the flip, the
restyle costs zero React work: the cascade recomputes, and the component tree
is never consulted.

## The live theme switch

The choice is application state like any other: one sub, one event, and one
view that renders the attribute.

```clojure
(rf/reg-sub :theme/current
  (fn [db _query] (:theme/current db :light)))   ;; unset reads as :light

(rf/reg-event :theme/choose
  (fn [{:keys [db]} [_ theme]] {:db (assoc db :theme/current theme)}))

(h/defview theme-scope [{:keys [children]}]
  (into [:div {:data-theme (name (h/sub [:theme/current]))}]
        children))

(h/defview theme-toggle [_props]
  (let [theme (h/sub [:theme/current])]
    [:button {:on-click [:theme/choose (if (= theme :dark) :light :dark)]}
     (if (= theme :dark) "Light mode" "Dark mode")]))

;; At the frame root:
[theme-scope {} [app {}]]
```

`children` arrives as a realized vector; that is why `into` splices it
([Views and reads](02-views-and-reads.md) has the rule).

**The default belongs in the sub, not in each reader.** A fresh app-db holds
no choice, so a bare `(:theme/current db)` reads `nil`, and `(name nil)`
throws. From a root view, that throw is a blank page. One extra argument
makes the read total and states the fallback once.

Three placement details carry the rest:

- **The attribute lands per frame, not per document.** A view renders its own
  element and nothing above it, so `data-theme` goes on `theme-scope`'s own
  `div`. That is what frame isolation needs. Two frames on one page, one
  light and one dark, is an ordinary page.
- **Keep a [`defview`](glossary.md#defview) head immediately below the scope.** The scope re-renders
  on every switch — one [boundary](glossary.md#boundary), a known cost. `[app {}]` below the scope
  bails out, because `app` is a `defview` and its props are unchanged
  ([Views and reads](02-views-and-reads.md)). A native-tag subtree, or a view
  called as a plain function, in that position re-runs with the scope.
- **Keep the scope at the frame's root, above any [`defhost`](glossary.md#defhost) crossing.** On
  the server, a Client-only crossing renders its fallback instead of its
  subtree. A scope below such a crossing is absent from the response,
  together with everything the scope covered
  ([SSR and hydration](17-ssr-and-hydration.md)).

An app that restores a remembered choice dispatches `:theme/choose` from
`:initial-events`, which lands before first paint
([Installation](installation.md)). The default paints only when
nobody has chosen, never as a flash before a late choice.

A class is the same bridge: `{:class (str "app app--" (name theme))}` swaps a
class instead of an attribute. Density, brand, or compact mode are more keys
driven the same way — one sub, one class or variable swap. If you measure a
need for zero render work on the switch, the imperative variant is three
lines of `rf/reg-fx` that set the attribute on `documentElement`. The price:
the DOM no longer derives from app-db, so a rewind or a restored snapshot
shows the old theme. Render the fact unless you have that measurement.

If all you want is *follow the OS*, you need no app state at all. A
`@media (prefers-color-scheme: dark)` block themes the page with no sub, no
event, and no bridge. Use app-db when the user picks, or when the pick must
survive a reload.

### Page chrome and the top layer

Two surfaces sit outside every frame. The document's own chrome — the
scrollbar, the `<body>` canvas, `<meta name="theme-color">` — is above any
root, so a per-frame attribute never reaches it. When the chrome matters,
echo the same app-db fact at document level from the choosing event, as a row
under `:fx` beside its `:db` write. Use a *different* attribute name, so the
rendered scope stays the one real carrier and the document copy stays
cosmetic:

```clojure
(rf/reg-fx :page/echo-theme!
  {:platforms #{:client}}
  (fn [_ctx theme]
    (.setAttribute js/document.documentElement "data-page-theme" (name theme))))
```

The top layer needs no echo. An [overlay](glossary.md#overlay)'s `::backdrop` pseudo-element
inherits custom properties from the element that opened it, so a modal opened
inside `theme-scope` takes the scope's tokens. Style the backdrop with the
same variables ([Overlays and focus](12-overlays-and-focus.md)).

## Strings are values: i18n

I18n has the same shape, one level up. The locale is a key in app-db, the
strings are a map, and a sub joins them. A translated string is an ordinary
value that flows through an ordinary sub. When the locale changes, every view
that read a string re-renders, because its inputs changed. That is the whole
mechanism: no observer registry, no i18n context, no remount.

```clojure
(def strings
  {:en {:greeting "Welcome back" :cart/empty "Your cart is empty"}
   :fr {:greeting "Bon retour"   :cart/empty "Votre panier est vide"}})

(rf/reg-sub :i18n/locale
  (fn [db _query] (:i18n/locale db :en)))

(rf/reg-sub :i18n/t
  (fn [db [_ k]]
    (get-in strings [(:i18n/locale db :en) k] (name k))))  ;; miss shows the key

(rf/reg-event :i18n/set-locale
  (fn [{:keys [db]} [_ locale]] {:db (assoc db :i18n/locale locale)}))

(h/defview greeting [_props]
  [:header
   [:h1 (h/sub [:i18n/t :greeting])]
   [:button {:on-click [:i18n/set-locale :fr]} "Français"]])
```

Numbers and dates need no framework either. The platform already localizes
them, with the locale read as an ordinary value:

```clojure
(h/defview price [{:keys [amount]}]
  (let [locale (name (h/sub [:i18n/locale]))]
    [:span.price
     (.format (js/Intl.NumberFormat. locale #js {:style "currency" :currency "EUR"})
              amount)]))
```

Loaded locale packs are the same design with the table in app-db instead of
code. Fetch the pack, `assoc` the pack in, and let the sub read
`(get-in db [:i18n/strings locale k])`. A string that arrives late is app-db
changing, and the views that read the string follow.

## What over-engineering looks like

The instinct to build a subsystem here is strong, so here is the cost:

```clojure
;; Don't — a "theme system": tokens in app-db, every view reading the registry
(rf/reg-sub :theme/token
  (fn [db [_ k]] (get-in db [:theme/tokens (:theme/current db) k])))

(h/defview save-button [_props]
  [:button {:style {:background    (h/sub [:theme/token :accent])
                    :border-radius (h/sub [:theme/token :radius])}}
   "Save"])
```

Now every view reads the token registry, a theme switch re-renders all of
those views, and app-db carries a second copy of what the stylesheet already
owns. The cascade does this job at no cost: tokens in CSS, one attribute
flip, zero React work after the flip. The same instinct builds an i18n
provider with a hook per consumer — and the sub already is the provider.

## A vendor theme goes through the host door

A component library that themes itself through React context gets its
provider the way any foreign component arrives: declared once with
[`h/defhost`](glossary.md#defhost), used as an ordinary head, with children lowered where they
render ([Interop](09-interop.md)).

```clojure
(ns app.vendor-theme
  (:require ["@acme/ui" :refer [ThemeProvider createTheme]]
            [re-frame.hicasso :as h]))

(def light-theme (createTheme #js {:mode "light"}))
(def dark-theme  (createTheme #js {:mode "dark"}))

(h/defhost theme-provider ThemeProvider)

(h/defview vendor-area [{:keys [children]}]
  (into [theme-provider {:theme (if (= :dark (h/sub [:theme/current]))
                                  dark-theme
                                  light-theme)}]
        children))
```

The vendor's theme objects are minted once at load. The app-db choice only
picks which object crosses. Context stays a React fact on the React side of
the door; your own app's theming never needed context.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Blank page on first load; console shows `Doesn't support name:` | Nobody had chosen a theme, the sub read `nil`, and `(name nil)` threw at the root | Give the sub a default: `(:theme/current db :light)` |
| The theme key changes in app-db and nothing on screen changes | No view renders the attribute — the cascade keys off the DOM, not app-db | Mount `theme-scope` (or your equivalent) at the frame root |
| Theme switch re-renders the whole app | What sits below the scope carries no [boundary](glossary.md#boundary) — a native-tag subtree, or a view called as a plain function | Keep a [`defview`](glossary.md#defview) head immediately below the scope, as `[app {}]` is |
| Locale switches but some strings stay in the old language | Those strings were captured once — in a `def`, a prop computed at load, a memoized helper — instead of read at render | Read strings where you use them: `(h/sub [:i18n/t k])` |
| A missing translation renders as the key's name | The sub's miss fallback, working as designed | Add the key to the table; the fallback names it so you can find it |
| The hydrated page keeps the server's theme | The hydration payload did not carry the choice, and an attribute-only divergence is the one class React never reports | Carry `:theme/current` in the payload ([SSR and hydration](17-ssr-and-hydration.md)) |

## When not

- **Following the OS with no user override** — skip app state; the media
  query is the whole feature.
- **One locale today** — do not build the table. Literal strings in views are
  fine. The table earns its place when the second locale is real, and the
  migration is mechanical: each literal becomes a key.
- **Your own app's styling** — never needs the vendor door or context. CSS is
  enough.
