# Custom elements

Web components work in templates directly. A tag containing a `-` is a **custom
element** — a keyword head is always a DOM or custom element, on every host, so
`[:user-picker {…}]` renders the element itself and is never forced through
`ui/raw` or mistaken for a view lookup.

The one thing the compiler cannot guess is which prop names are JS **properties**
(set on the element object) versus **attributes** (written into markup). Declare
the properties once, at the top level:

```clojure
(ui/custom-element :user-picker {:properties #{:users :selected-id}})

(ui/defview team-picker [{:keys [users current-id]}]
  [:user-picker {:users           users
                 :selected-id     current-id
                 :placeholder     "Choose…"
                 :on-picker-close [:team/picker-closed]}])
```

The rules, in full:

- **Declared names become JS properties**, kebab-case mapped to camelCase
  (`:selected-id` → `selectedId`) — the same spelling philosophy as the rest of the
  DOM grammar.
- **Undeclared names are attributes.** Undeclared *elements* need no declaration at
  all — an element that only takes attributes needs no declaration.
- `:class`, `:style`, and booleans follow the ordinary DOM rules.
- The `{:properties #{…}}` map is the entire declaration grammar — the options map
  is closed.
- **Events ride the normal handler grammar.** A native custom event
  (`:on-picker-close` above) takes an event vector like any DOM listener; when the
  payload lives on `event.detail`, reach for `ui/event`:

```clojure
[:user-picker {:users users
               :on-picker-change (ui/event [e]
                                   [:team/picked (.. e -detail -id)])}]
```

- **On the JVM / SSR the emitter writes attributes only** — property props are
  applied at hydration, when a live element object exists to set them on.

## Troubleshooting

| If you write | What you see | The fix |
|---|---|---|
| A malformed declaration — a non-set `:properties`, an unknown option | Compile error `:rf.ui.compile/bad-custom-element` | The grammar is `(ui/custom-element tag {:properties #{…}})`, nothing more |
| Two conflicting declarations for one tag | `:rf.ui.compile/custom-element-conflict` (runtime: `:rf.error/custom-element-conflict`) | One declaration per tag — put it beside the element's interop wrapper |

## When not

- If a wrapper library already exposes the component as a **React** component,
  embed it as a foreign head (`[TheComponent {…}]`) instead — that boundary and its
  callback rules live on the [interop page](interop-and-limits.md).
- Reminder: `re-frame.ui` is experimental — the retained adapters are the default
  choice; under Reagent/UIx, custom elements follow those libraries' own interop
  conventions.
