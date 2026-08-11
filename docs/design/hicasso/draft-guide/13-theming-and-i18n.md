# Theming and i18n

You want a dark mode and a second language. Many React stacks add a
ThemeProvider, an i18n context, and a hook per consumer. Hicasso ships
neither. Tokens live in CSS, strings are ordinary values, and the current
theme or locale is one key in app-db that ordinary subscriptions read.

## Design tokens in CSS

The cascade already scopes styles: the nearest ancestor wins. Put tokens on
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

The `--app-*` prefix is yours — name custom properties the way your
stylesheet already does. `:root` is the light theme, so you do not need a
`[data-theme="light"]` block.

A theme switch flips one attribute on one element. After the flip, the cascade
restyles and React does no work for token changes.

## The live theme switch

The choice is application state: one subscription, one event, and one view
that renders the attribute.

```clojure
(ns app.theme
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

(rf/reg-sub :theme/current
  (fn [db _query] (:theme/current db :light)))   ;; unset reads as :light

(rf/reg-event :theme/choose
  (fn [{:keys [db]} [_ theme]]
    {:db (assoc db :theme/current theme)}))

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
([Views and reads](02-views-and-reads.md)).

**Put the default in the subscription, not in each reader.** A fresh app-db
holds no choice, so a bare `(:theme/current db)` is `nil`, and `(name nil)`
throws. From a root view that throw blanks the page. One extra argument makes
the read total.

Placement rules:

- **Per frame, not per document.** A view renders its own element, so
  `data-theme` goes on `theme-scope`'s `div`. Two frames on one page — one
  light, one dark — work without further machinery.
- **Keep a [`defview`](glossary.md#defview) head immediately below the scope.** The scope
  re-renders on every switch. `[app {}]` below the scope bails out when its
  props are unchanged ([Views and reads](02-views-and-reads.md)). A native-tag
  subtree, or a view called as a plain function, re-runs with the scope.
- **Keep the scope at the frame root, above any [`defhost`](glossary.md#defhost) crossing.** On
  the server, a Client-only crossing renders its fallback instead of its
  subtree. A scope below that crossing is missing from the response
  ([SSR and hydration](17-ssr-and-hydration.md)).

To restore a remembered choice, dispatch `:theme/choose` from
`:initial-events` so it lands before first paint
([Installation](installation.md)). The default paints only when nobody has
chosen.

A class is the same bridge: `{:class (str "app app--" (name theme))}`.
Density, brand, or compact mode are more keys driven the same way.

If you need zero render work on the switch, an `rf/reg-fx` can set the
attribute on `documentElement`. The cost: the DOM no longer derives from
app-db, so a rewind or restored snapshot shows the old theme. Render the fact
unless you have measured a need for the imperative path.

To follow the OS with no user override, skip app-db entirely and use
`@media (prefers-color-scheme: dark)`. Use app-db when the user picks, or when
the pick must survive a reload.

### Page chrome and the top layer

Two surfaces sit outside every frame. Document chrome — scrollbar, `<body>`
canvas, `<meta name="theme-color">` — is above any root, so a per-frame
attribute never reaches it. When chrome matters, echo the same app-db fact at
document level from the choosing event, as a row under `:fx` beside the `:db`
write. Use a *different* attribute name so the rendered scope stays the real
carrier and the document copy stays cosmetic:

```clojure
(rf/reg-fx :page/echo-theme!
  {:platforms #{:client}}
  (fn [_ctx theme]
    (.setAttribute js/document.documentElement "data-page-theme" (name theme))))
```

The top layer needs no echo. An [overlay](glossary.md#overlay)'s `::backdrop`
pseudo-element inherits custom properties from the element that opened it, so
a modal opened inside `theme-scope` takes the scope's tokens. Style the
backdrop with the same variables
([Overlays and focus](12-overlays-and-focus.md)).

## Strings are values: i18n

Locale is a key in app-db, strings are a map, and a subscription joins them.
When the locale changes, every view that read a string re-renders because its
inputs changed. No observer registry, no i18n context, no remount.

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
  (fn [{:keys [db]} [_ locale]]
    {:db (assoc db :i18n/locale locale)}))

(h/defview greeting [_props]
  [:header
   [:h1 (h/sub [:i18n/t :greeting])]
   [:button {:on-click [:i18n/set-locale :fr]} "Français"]])
```

Numbers and dates use the platform with the locale as an ordinary value:

```clojure
(h/defview price [{:keys [amount]}]
  (let [locale (name (h/sub [:i18n/locale]))]
    [:span.price
     (.format (js/Intl.NumberFormat. locale
                                     #js {:style "currency" :currency "EUR"})
              amount)]))
```

Loaded locale packs use the same design with the table in app-db instead of
code. Fetch the pack, `assoc` it in, and let the subscription read
`(get-in db [:i18n/strings locale k])`. A late string is app-db changing; the
views that read it follow.

## What over-engineering looks like

```clojure
;; Don't — tokens in app-db; every view reads the registry
(rf/reg-sub :theme/token
  (fn [db [_ k]] (get-in db [:theme/tokens (:theme/current db) k])))

(h/defview save-button [_props]
  [:button {:style {:background    (h/sub [:theme/token :accent])
                    :border-radius (h/sub [:theme/token :radius])}}
   "Save"])
```

Now every view reads the token registry, a theme switch re-renders all of
those views, and app-db carries a second copy of what the stylesheet already
owns. Tokens in CSS plus one attribute flip cost zero React work for token
changes. The same instinct builds an i18n provider with a hook per consumer —
the subscription already is the provider.

## A vendor theme via `defhost`

A component library that themes itself through React context gets its provider
the way any foreign component arrives: declare once with
[`h/defhost`](glossary.md#defhost), use as an ordinary head
([Interop](09-interop.md)).

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

Create the vendor theme objects once at load. App-db only picks which object
crosses. Context stays a React fact on the React side of the host; your own
app's theming never needed context.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Blank page on first load; console shows `Doesn't support name:` | Nobody chose a theme, the sub read `nil`, and `(name nil)` threw at the root | Give the sub a default: `(:theme/current db :light)` |
| Theme key changes in app-db and nothing on screen changes | No view renders the attribute — the cascade keys off the DOM, not app-db | Mount `theme-scope` (or equivalent) at the frame root |
| Theme switch re-renders the whole app | What sits below the scope has no independent re-render unit — native-tag subtree, or a view called as a plain function | Keep a `defview` head immediately below the scope, as `[app {}]` is |
| Locale switches but some strings stay in the old language | Those strings were captured once — in a `def`, a prop computed at load, a memoized helper — instead of read at render | Read strings where you use them: `(h/sub [:i18n/t k])` |
| A missing translation renders as the key's name | The sub's miss fallback, working as designed | Add the key to the table; the fallback names it so you can find it |
| Hydrated page keeps the server's theme | Hydration payload did not carry the choice; attribute-only divergence is the class React never reports | Carry `:theme/current` in the payload ([SSR and hydration](17-ssr-and-hydration.md)) |

## When not to use it

- **Following the OS with no user override** — skip app state; the media
  query is the whole feature.
- **One locale today** — do not build the table. Literal strings in views are
  fine. The table earns its place when the second locale is real; migration is
  mechanical (each literal becomes a key).
- **Your own app's styling** — never needs a vendor host or context. CSS is
  enough.
