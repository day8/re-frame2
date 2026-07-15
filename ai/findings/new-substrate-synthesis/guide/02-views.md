# 02 — Views

A view is a pure function from a props map to hiccup, defined with `defview`. The
compiler lowers the hiccup at build time: you write data; the browser runs direct
element construction.

## Defining a view

```clojure
(ui/defview product-card
  "One product tile."
  {:props [:map [:product [:map [:id :int] [:name :string]]]]}
  [{:keys [product]}]
  [:div.card
   [:h3 (:name product)]
   [:button {:on-click [:cart/add (:id product)]} "Add to cart"]])
```

**Shape**

- Zero or one argument, always a **map**. Destructure in the header (`:keys`,
  `:x/keys`, `:or`). Prefer named slots over `:as` — `:as` materialises the whole map
  and switches the view to generic comparison (a visible dev cost).
- No positional args, no second component form — no Form-2, no `with-let`, no class
  components. Local state and lifecycle live in [03](03-state.md); the component form
  never changes.
- Options map: `:props` (schema, dev-checked — at *compile time* when a call site is
  literal), `:id`, and `:display-name`. Lifecycle is not an option.

**Calling**

- Views call views by symbol: `[product-card {:product p}]`.
- Children arrive as `:children` — declaring that binding is what opts a view into
  accepting them. Passing children to a view that declares none is a compile error.
- **`:key` is reserved** (React's list-identity slot). An app prop named `:key` is a
  compile error.

**Memoisation**

Every view is memoised automatically on value-equal props. There is nothing to opt
into, and no opt-out — a view that must always re-render is reading the wrong inputs.

## Templates

If you know Reagent hiccup, you are home — minus the traps.

```clojure
[:div.sidebar#nav {:style {:width "20rem" :cursor :pointer}}
 [:h2 "Products"]
 [:ul
  (for [p products]
    [product-card {:key (:id p) :product p}])]]
```

- `:div.cls#id` sugar; `:style` maps; `:class` as string, vector, or map-of-flags.
- `[:<> …]` is a fragment. Branch freely with `if` / `when` / `cond` / `case` /
  `let` — the compiler understands them.
- **Keys on list items are required.** A missing key is a *build failure* with the
  element's file:line, not a console warning.
- **Tag heads must be literal.** `[(if big? :h1 :h2) title]` is a compile error —
  bind attributes dynamically or write two branches.
- **No `#js`, no camelCase on compiled paths.** Conversion is the compiler's job.
  The browser lowers to direct React construction; the JVM lowers to versioned
  `re-frame.ui.tree` structural data under the same specified conversion contract.
  At S5, `re-frame.ssr/emit-ui-tree` separately serializes that tree to HTML;
  browser/JVM and serializer parity gates detect drift.
- A `map` that returns markup is rejected (extract a child view, use `for`). Keywords
  in child position are rejected (silent-text mistakes).

These are not taste rules — they are what compile-time lowering requires. The full
catalogue of walls, fixes, and escapes is
[14 — What the compiler forbids](14-compile-time-limits.md).

Genuinely data-driven UI (CMS trees, form definitions) uses an explicit interpreter
artefact — opt-in, cost visible:

```clojure
;; guide:no-fixture — wave-2, does not ship in v1
(require '[re-frame.ui.data :as data])
(data/render tree-from-server)
```

## Styling

`:class` and `:style` are the whole surface the compiler owns. Browser direct-React
output and JVM structural-tree output apply the same specified conversion contract
to them (keyword CSS values included); parity gates detect implementation drift. The
CSS itself is yours: plain stylesheets, Tailwind, utility classes — anything that
resolves to class names works unchanged. There is no css-in-cljs layer to learn. Later,
`ui/presence` hands you phase names so *your* CSS does the animating.

## Props discipline

Props are compared by value to decide re-renders. Keep them data and you never think
about identity, `useCallback`, or memo wrappers again. The comparator is `rf=`, per
prop slot — React.memo, except CLJS data compares by value:

| Kind | Comparison |
|---|---|
| CLJS data (maps, vectors, sets, records, dates) | by value — a rebuilt-but-equal map is the same prop |
| Host values (plain JS objects, arrays, fns, React elements) | by identity |

Two honest consequences of the host row: a raw fn in props "re-renders with the
parent" (the S3 cause vector on its existing Xray Views row points at that identity
change), and an in-place-mutated JS object never repaints — mutable foreign values
belong at an explicit interop boundary, not in props.

Because handlers are usually vectors and children are realised, comparison stays
honest by default.

## Interop with foreign React

```clojure
[:div (ui/raw badge-element)]                       ; React element a foreign lib handed you
[DatePicker {:selected date
             :on-change (ui/handler [v] (pick! v))}] ; foreign component as template head
(def MyWidget (ui/->react product-card))            ; view exported to a React codebase
```

Foreign props pass through as JS values. Callbacks choose a form from
[04's decision table](04-events.md).

- `ui/->react` *(lands S6 with the migration wave)* — a view inside a React or
  Reagent codebase you are migrating incrementally.
- `ui/spread` — explicit runtime prop merge, same conversion rule table as the
  compiler:

```clojure
(ui/defview text-field [{:keys [attrs label]}]
  [:label label
   [:input (ui/spread {:type :text :class "field"} attrs)]])
```

- `ui/html` — the one escaping bypass, visible at the call site:

```clojure
[:article (ui/html rendered-markdown)]   ; you vouch for this string
```

Code that genuinely lives at a foreign-React boundary and needs a React hook there
uses `re-frame.ui.react` — thin wrappers (`use-ref`, `use-effect`, …)
*(lands S3 — interop tier)*. Inside ordinary views you never reach for them:
`local`, `effect`, and `sub` are the component story.

That is the entire React surface you touch in normal app code.

## Error boundaries *(lands S3)*

```clojure
[ui/error-boundary {:fallback error-panel
                    :reset-key route-id
                    :on-error [:ui/render-failed]}
 [risky-subtree]]
```

Catches render/lifecycle throws below it (not event-handler or async errors).
Dispatches `:on-error` after the failing commit, renders the fallback, retries when
`:reset-key` changes. On the server there is no boundary recovery: a throw follows
the server failure policy ([11](11-ssr.md)).

## Advanced: presence and custom elements

These land later and do not change the core view model above.

### Exit animations: `ui/presence` *(lands S4)*

When state says an element is gone, React removes it instantly. Toasts slide out;
modals fade. `ui/presence` owns the gap between *no longer true* and *no longer
visible*:

```clojure
(ui/defview toast-card [{:keys [toast]}]
  (let [phase (presence-phase)]          ; :mounting | :present | :unmounting
    [:div.toast {:class (name phase)}
     (:message toast)]))

(ui/defview toast-tray []
  (ui/presence {:timeout-ms 300}
    (for [t (sub [:toasts/visible])]
      [toast-card {:key (:id t) :toast t}])))
```

A removed child keeps rendering as `:unmounting` until its transition ends (or
`:timeout-ms`), then is truly removed with subscriptions and leases released.
Outside any presence boundary, `(presence-phase)` returns `:present`. Animations
themselves are CSS (or a foreign library at an interop boundary).

### Custom elements *(lands S4)*

A tag containing a `-` is a custom element — used directly, never forced through
`ui/raw`:

```clojure
(ui/custom-element :user-picker {:properties #{:users :selected-id}})

(ui/defview team-picker [{:keys [users current-id]}]
  [:user-picker {:users        users
                 :selected-id  current-id
                 :placeholder  "Choose…"
                 :on-picker-close [:team/picker-closed]}])
```

Declared kebab-case property names map to camelCase JS properties. Undeclared names
are attributes. Native custom events ride the normal handler grammar; payloads on
`event.detail` use `ui/event`.
