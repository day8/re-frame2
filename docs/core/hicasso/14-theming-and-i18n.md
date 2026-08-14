# Theming and internationalisation

Hicasso does not add a theme provider or an i18n context. CSS owns design
tokens. Translated strings are ordinary values. The current theme and locale
are app-db facts read through ordinary subscriptions.

## Put design tokens in CSS

Use custom properties for tokens and override them under a theme attribute:

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

The `--app-*` prefix is only an example. Use your application's existing token
names. Let `:root` be the default theme so the page has useful values before
any user preference is loaded.

Changing one `data-theme` attribute lets the CSS cascade restyle the subtree.
React does not need to re-render each token consumer.

## Render the selected theme

The user's choice is application state:

```clojure
(ns app.theme
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

(rf/reg-sub :theme/current
  (fn [db _query]
    (:theme/current db :light)))

(rf/reg-event :theme/choose
  (fn [{:keys [db]} [_ theme]]
    {:db (assoc db :theme/current theme)}))

(h/defview theme-scope [{:keys [children]}]
  (into [:div {:data-theme (name (h/sub [:theme/current]))}]
        children))

(h/defview theme-toggle [_]
  (let [theme (h/sub [:theme/current])]
    [:button
     {:on-click [:theme/choose
                 (if (= theme :dark) :light :dark)]}
     (if (= theme :dark) "Light mode" "Dark mode")]))

[theme-scope {}
 [app {}]]
```

`children` is a realised vector, so `into` splices it into the wrapper
([Views and reads](02-views-and-reads.md)).

Give the subscription a default. Without `(:theme/current db :light)`, a fresh
app-db returns `nil`, and `(name nil)` throws from the root before the page can
render.

Place the scope carefully:

- **Theme per frame.** Render `data-theme` on an element inside the frame. Two
  frames can then use different themes on one page.
- **Keep a view boundary immediately underneath.** The scope re-renders when
  the theme value changes. `[app {}]` can then skip its body when its own props
  and reads are unchanged. An inlined native-tag subtree would re-run with the
  scope.
- **Place the scope above foreign crossings.** A Client-only host can replace
  its subtree with a fallback on the server. A theme scope beneath that host
  would be absent from the server response
  ([SSR and hydration](18-ssr-and-hydration.md)).

Restore a persisted choice through `:initial-events` so it reaches app-db
before first paint ([Installation](00-installation.md)). The default applies only
when no preference has been chosen.

A theme class works the same way:

```clojure
{:class (str "app app--" (name theme))}
```

Density, brand, and compact-mode settings can use the same pattern.

When the user never overrides the operating-system preference, skip app-db and
use `@media (prefers-color-scheme: dark)`.

### Imperative document-level theming

An effect can set an attribute on `documentElement` without a React render:

```clojure
(rf/reg-fx :page/echo-theme!
  {:platforms #{:client}}
  (fn [_ctx theme]
    (.setAttribute js/document.documentElement
                   "data-page-theme"
                   (name theme))))
```

Use this only for document chrome outside every frame: the body canvas,
scrollbar, or `<meta name="theme-color">`. Keep a different attribute name so
the rendered frame scope remains the real carrier and the document copy is
clearly cosmetic.

An imperative-only theme does not automatically follow time travel or a
restored snapshot. Prefer the rendered attribute unless you have measured a
reason not to.

The browser's top layer does not need a document echo. An overlay's
`::backdrop` inherits custom properties from the element that opened it, so a
modal inside `theme-scope` receives the same tokens
([Overlays and focus](13-overlays-and-focus.md)).

## Treat translated strings as values

Store the locale in app-db and derive strings through a subscription:

```clojure
(def strings
  {:en {:greeting "Welcome back"
        :cart/empty "Your cart is empty"}
   :fr {:greeting "Bon retour"
        :cart/empty "Votre panier est vide"}})

(rf/reg-sub :i18n/locale
  (fn [db _query]
    (:i18n/locale db :en)))

(rf/reg-sub :i18n/t
  (fn [db [_ k]]
    (get-in strings
            [(:i18n/locale db :en) k]
            (name k))))

(rf/reg-event :i18n/set-locale
  (fn [{:keys [db]} [_ locale]]
    {:db (assoc db :i18n/locale locale)}))

(h/defview greeting [_]
  [:header
   [:h1 (h/sub [:i18n/t :greeting])]
   [:button {:on-click [:i18n/set-locale :fr]}
    "Français"]])
```

When the locale changes, only views that read translated values need to
re-render. The fallback renders the missing key's name, which makes incomplete
translation tables visible instead of blank.

Format numbers and dates with the platform and the current locale:

```clojure
(h/defview price [{:keys [amount]}]
  (let [locale (name (h/sub [:i18n/locale]))]
    [:span.price
     (.format
      (js/Intl.NumberFormat.
       locale
       #js {:style "currency" :currency "EUR"})
      amount)]))
```

For separately loaded locale packs, store the loaded table in app-db and let
the translation subscription read from it:

```clojure
(get-in db [:i18n/strings locale k])
```

A late locale pack is an ordinary app-db update. Views that read its strings
update normally.

## Avoid storing CSS tokens in app-db

```clojure
;; Don't: every token consumer becomes a subscription and re-render target.
(rf/reg-sub :theme/token
  (fn [db [_ k]]
    (get-in db [:theme/tokens (:theme/current db) k])))

(h/defview save-button [_]
  [:button
   {:style {:background (h/sub [:theme/token :accent])
            :border-radius (h/sub [:theme/token :radius])}}
   "Save"])
```

The stylesheet already owns these values. Duplicating them in app-db makes a
theme switch recompute every token consumer. CSS custom properties plus one
attribute avoid that work.

The same principle applies to i18n providers: the subscription is already the
reactive access path. A second context and hook layer do not add information.

## Vendor theme providers

A component library that uses React context still enters through a declared
host ([Interop](09-interop.md)):

```clojure
(ns app.vendor-theme
  (:require ["@acme/ui" :refer [ThemeProvider createTheme]]
            [re-frame.hicasso :as h]))

(def light-theme
  (createTheme #js {:mode "light"}))

(def dark-theme
  (createTheme #js {:mode "dark"}))

(h/defhost theme-provider ThemeProvider)

(h/defview vendor-area [{:keys [children]}]
  (into
   [theme-provider
    {:theme (if (= :dark (h/sub [:theme/current]))
              dark-theme
              light-theme)}]
   children))
```

Create vendor theme objects once at namespace load. App-db chooses which
object crosses. The vendor's context remains an implementation detail on the
React side; your own application theme still uses CSS.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| First load is blank and the console reports `Doesn't support name:` | The theme subscription returned `nil`, then `(name nil)` threw | Give the subscription a default such as `(:theme/current db :light)` |
| Theme changes in app-db but the page does not change | No rendered element exposes the theme to CSS | Mount `theme-scope`, or an equivalent attribute carrier, at the frame root |
| A theme switch re-runs the whole application | The content below the scope has no independent view boundary | Put a `defview` head such as `[app {}]` immediately below the scope |
| Some strings remain in the old locale | They were computed at load time, stored in a `def`, or otherwise captured outside render | Read them where used with `(h/sub [:i18n/t k])` |
| A missing translation displays the key name | The translation fallback is working | Add the key to the selected locale table |
| Hydration keeps the server's theme | The hydration payload omitted `:theme/current`; attribute-only divergence may not produce a useful React warning | Include the theme choice in the hydration payload ([SSR and hydration](18-ssr-and-hydration.md)) |

## When not to add this machinery

- Use the CSS media query when the application follows the OS and offers no
  user override.
- Keep literal strings when there is only one real locale. Extract them when a
  second locale becomes an actual requirement.
- Do not introduce a vendor provider for your own styles. CSS is sufficient.
