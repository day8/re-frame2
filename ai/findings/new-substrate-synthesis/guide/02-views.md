# 02 — Views

A view is a pure function from a props map to hiccup, defined with `defview`. The compiler
lowers the hiccup at build time — you write data; the browser runs direct element
construction.

## Defining views

```clojure
(ui/defview product-card
  "One product tile."
  {:props [:map [:product [:map [:id :int] [:name :string]]]]}
  [{:keys [product]}]
  [:div.card
   [:h3 (:name product)]
   [:button {:on-click [:cart/add (:id product)]} "Add to cart"]])
```

- **Zero or one argument, always a map.** Destructure in the header (`:keys`, `:x/keys`,
  `:or`). (`:as` works, but it materialises the whole map and switches the view to
  generic comparison — a visible dev cost; prefer named slots.) No positional args, no
  second component form — no Form-2, no `with-let`, no class components. Local state and lifecycle are [03](03-state.md); the component form
  never changes.
- The options map takes `:props` (schema, dev-checked — at *compile time* when a call
  site is literal), `:id`, and `:display-name`. That's it — lifecycle is not an option
  (domain events belong to domain transitions, [04](04-events.md)), and error handling is
  an explicit component *(lands S3)*:

  ```clojure
  [ui/error-boundary {:fallback error-panel
                      :reset-key route-id
                      :on-error [:ui/render-failed]}
   [risky-subtree]]
  ```

  It catches render/lifecycle throws below it (not event-handler or async errors — those
  have their own paths), dispatches `:on-error` after the failing commit, renders the
  fallback, and retries when `:reset-key` changes. The fallback view receives `:error`
  alongside the boundary's declared props, and it cannot dispatch during its own render.
  On the server there is no boundary recovery at all: a throw follows the server failure
  policy ([08](08-ssr.md)), because catching and retrying is a client mechanism.
- Views call views by symbol: `[product-card {:product p}]`. Children arrive as
  `:children` — declaring that binding is what opts a view into accepting them (passing
  children to a view that declares none is a compile error). **`:key` is reserved**
  (it's React's list-identity slot) — an app prop named `:key` is a compile error.
- Every view is **memoized automatically** on value-equal props. There is nothing to
  opt into (and no opt-out — a view that must always re-render is reading the wrong
  inputs).

## Templates

Reagent users: home, minus the traps.

```clojure
[:div.sidebar#nav {:style {:width "20rem" :cursor :pointer}}   ; keyword CSS values fine
 [:h2 "Products"]
 [:ul
  (for [p products]
    [product-card {:key (:id p) :product p}])]]
```

- `:div.cls#id` sugar; `:style` maps; `:class` as string, vector, or map-of-flags.
- `[:<> …]` is a fragment. Branch freely with `if/when/cond/case/let` — the compiler
  understands them.
- **Keys on list items are required** — a missing key is a *build failure* with the
  element's file:line, not a console warning.
- **Tag heads must be literal.** `[(if big? :h1 :h2) title]` is a compile error — bind the
  attributes dynamically or write two branches. Genuinely data-driven UI (CMS trees, form
  definitions) uses the explicit interpreter artifact:

```clojure
;; guide:no-fixture — wave-2, does not ship in v1
(require '[re-frame.ui.data :as data])
(data/render tree-from-server)     ; opt-in; its cost is visible and attributable
```

  (`re-frame.ui.data` is **wave-2** — a separate, demand-gated artefact; it does not
  ship in v1. Blessed-table verdict; qualifier added 2026-07-12.)

- **No `#js`, no camelCase on compiled paths.** Conversion is the compiler's job — and
  it's the same conversion on the server, so SSR output cannot drift from the client.
  (Foreign React interop may still hand raw JS values through `ui/raw` and foreign
  props — that's the boundary's job, not yours.)
- A `map` that returns markup is rejected (extract a child view, use `for`); keywords in
  child position are rejected too (silent-text mistakes) — literal ones at build time,
  runtime-produced ones at dev render; lazy seqs don't reach React.

## Exit animations: `ui/presence` *(lands S4 — presence)*

When state says an element is gone, React removes it instantly — but toasts slide out,
modals fade, rows collapse. `ui/presence` owns the gap between *no longer true* and
*no longer visible*. Each card reads where it is in that lifetime with `presence-phase`;
the tray wraps the list in the presence boundary:

```clojure
(ui/defview toast-card [{:keys [toast]}]
  (let [phase (presence-phase)]          ; :mounting | :present | :unmounting
    [:div.toast {:class (name phase)}    ; your CSS does the animating
     (:message toast)]))

(ui/defview toast-tray []
  (ui/presence {:timeout-ms 300}
    (for [t (sub [:toasts/visible])]
      [toast-card {:key (:id t) :toast t}])))
```

A child removed from the list keeps rendering as `:unmounting` until its transition ends
(or `:timeout-ms` — nothing can strand a zombie), then is truly removed with all its
subscriptions and leases released. Outside any presence boundary, `(presence-phase)`
returns `:present` — presence-aware components work anywhere. What you get for free: exiting children are
`inert`/`aria-hidden` (a departing toast can't steal a click), remove-then-re-add is
deterministic (delete + undo doesn't ghost), reduced-motion users get the instant path,
server-rendered content doesn't all slide in on load, and tests advance transitions with
`(ui.test/flush-presence!)` instead of sleeping.

It's a *presence* primitive, not an animation system: three phases and a lifetime
contract. The animations themselves are CSS (or a foreign library at an interop
boundary).

## Props discipline

Props are compared by value to decide re-renders — keep them data and you never think
about identity, `useCallback`, or memo wrappers again. The comparator is `rf=`, per prop
slot; the mental model is **React.memo, except CLJS data compares by value**:

- **CLJS data** — maps, vectors, sets, records, dates — compares by value. A
  rebuilt-but-equal map is the same prop; a fresh `{:id 42}` literal never repaints.
- **Host values** — plain JS objects, arrays, fns, React elements — fall through to
  identity. Two honest consequences: a raw fn in props "re-renders with the parent"
  (the dev heatmap points at it if it matters), and an in-place-mutated JS object never
  repaints — mutable foreign values belong at an explicit interop boundary, not in
  props.
- Edge cases behave: `##NaN` props are repaint-stable; the identity check doubles as
  the fast path, so structurally shared values are cheap to compare.

Because handlers are usually vectors and children are realized, the comparison stays
honest by default.

## Interop

```clojure
[:div (ui/raw badge-element)]                       ; a React element a foreign lib handed you
[DatePicker {:selected date                         ; foreign component as template head
             :on-change (ui/handler [v] (pick! v))}]
(def MyWidget (ui/->react product-card))            ; view exported to a React codebase
```

Foreign props pass through as JS values; callbacks choose a form from
[04's decision table](04-events.md) *(committed callback behaviour lands S3)*.
(`ui/->react` ships with the migration wave *(lands S6)* — it
exists so a view can live inside a React or Reagent codebase you're migrating
incrementally.) For runtime-chosen components there's `ui/element` (wave-2 —
demand-gated; until it ships, `ui/raw` covers a runtime-chosen head); for
browser-only libraries under SSR, `ui/client-only` ([08](08-ssr.md)). For generic
prop-map merging there's `ui/spread` — the one explicit runtime conversion, driven by
the same rule table the compiler uses:

```clojure
(ui/defview text-field [{:keys [attrs label]}]
  [:label label
   [:input (ui/spread {:type :text :class "field"} attrs)]])   ; base, then overrides
```

And for sanitized CMS/markdown output, the one explicit escaping bypass — visible at
the call site, which is the whole contract:

```clojure
[:article (ui/html rendered-markdown)]   ; you're vouching for this string
```

Code that genuinely lives at a foreign-React boundary and needs a React hook there gets
the `re-frame.ui.react` namespace — thin wrappers (`use-ref`, `use-effect`,
`use-layout-effect`, `use-effect-event`, `use-context`, `use-id`, `lazy`) *(lands S3 —
interop tier)*. Inside ordinary views you never reach for them: `local`, `effect`, and
`sub` are the component story.

That is the entire React surface you touch.

## Web boundaries: custom elements *(lands S4 — web boundaries)*

A tag containing a `-` is a custom element — used directly, never forced through
`ui/raw`:

```clojure
[:fancy-tooltip {:label "Save"                    ; attribute
                 :open  true}                     ; attribute (boolean, DOM rules)
 [:button {:on-click [:doc/save]} "Save"]]
```

By default every prop becomes an **attribute** — strings on the wire, which is also
what the server can emit. Web components that take real **properties** (rich data, not
strings) declare them once, at the top level:

```clojure
(ui/custom-element :user-picker {:properties #{:users :selected-id}})

(ui/defview team-picker [{:keys [users current-id]}]
  [:user-picker {:users        users              ; JS property (declared)
                 :selected-id  current-id         ; JS property (declared)
                 :placeholder  "Choose…"          ; attribute (undeclared)
                 :on-picker-close [:team/picker-closed]}])  ; native custom event — normal handler grammar
```

- The `{:properties #{…}}` set is the entire declaration grammar — closed, like the
  `defview` options map. Declared kebab-case names map to the camelCase JS property
  (`:selected-id` → `selectedId`), mirroring the pinned DOM spelling rule.
- Undeclared names on a declared element are attributes; undeclared *elements* need no
  declaration at all — all-attributes is the default.
- Booleans, `:class`, and `:style` follow the ordinary DOM rules.
- Native custom events ride the normal handler grammar — a custom element's `:on-*` is
  a known native event property, so vectors and the bare-fn shorthand are both legal.
  A payload riding `event.detail` is `ui/event`'s job:
  `(ui/event [e] [:team/picked (.-detail e)])`.
- On the server, the JVM emitter emits **attributes only**; property props are applied
  at hydration.

(Head policy — what may render into `<head>` — hardens in the same stage; the posture
until then: treat the document head as host-owned.)
