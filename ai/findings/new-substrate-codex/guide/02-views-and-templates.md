# Views and templates

## A view is a pure named React component

```clojure
(ui/defview user-name [{:user/keys [first-name last-name]}]
  [:span.user-name first-name " " last-name])
```

In the browser this becomes a real named React function component. On JVM it becomes a plain function producing the SSR render tree. The same compiler template controls both.

Render may read values and describe elements. It must not dispatch, attach listeners, start timers, mutate the DOM, or begin I/O.

## Definition forms

### No props

```clojure
(ui/defview logo []
  [:a.logo {:href "/"} "Acme"])
```

### Props with defaults

```clojure
(ui/defview avatar
  [{:keys [name size] :or {size 32}}]
  [:img.avatar
   {:src (avatar-url name size)
    :width size
    :height size
    :alt (str name "'s avatar")}])
```

### Docstring and schema

```clojure
(ui/defview avatar
  "A user avatar with deterministic dimensions."
  {:props [:map
           [:name string?]
           [:size {:optional true} pos-int?]]}
  [{:keys [name size] :or {size 32}}]
  ...)
```

The schema validates development calls and lets the compiler catch missing required keys in literal component calls. Production assertions elide.

## DOM nodes

```clojure
[:section.card#account
 {:class ["raised" (when selected? "selected")]
  :data-kind :account
  :aria-labelledby "account-title"}
 [:h2#account-title "Account"]
 body]
```

Tag shorthand is parsed at compile time:

- `:section` → tag;
- `.card` → static class;
- `#account` → static ID.

An explicit `:id` wins over shorthand. Static class and dynamic `:class` values are combined.

Use idiomatic kebab-case prop names. The compiler validates and converts them to React DOM spellings. Named values on native DOM props become strings where appropriate; internal/foreign component prop values retain their Clojure identity.

## Styles

Literal style keys compile directly:

```clojure
[:div
 {:style {:display "grid"
          :grid-template-columns (str "repeat(" columns ", 1fr)")
          :opacity (if disabled? 0.5 1)}}]
```

Use numeric values only where React accepts unitless numbers. Compiler diagnostics catch known invalid property names but cannot validate arbitrary custom properties or browser support.

CSS classes are usually a better static path. Dynamic inline style objects are still direct JS objects, but React must receive a new object when a value changes.

## Children

Strings and numbers render as text. `nil` and `false` render nothing:

```clojure
[:p
 "Welcome " name
 (when admin? [:strong " (administrator)"])]
```

Keywords and symbols are not silently rendered as text. Convert intentionally:

```clojure
[:span (name status)]
```

Arbitrary sequences are not flattened. The only collection-of-elements path is a compiler-recognized list form.

## Branches

Write branches in template position:

```clojure
(ui/defview result [{:keys [state]}]
  (case (:status state)
    :loading [loading-row]
    :error   [error-row {:error (:error state)}]
    :loaded  [data-row {:data (:data state)}]
    [empty-row]))
```

Supported branching forms include `if`, `when`, `cond`, and `case`. Each branch is compiled and checked on both hosts.

Do not build markup as ordinary data and place the resulting variable into the tree:

```clojure
;; Avoid: asks for runtime markup interpretation, which does not exist.
(let [row (if ok? [:span "ok"] [:strong "bad"])]
  [:div row])
```

Move the branch into the template or extract a child view.

## Fragments

```clojure
[:<>
 [:dt term]
 [:dd definition]]
```

Fragments add no DOM wrapper. In development, each compiler-owned top-level host child can carry source/view annotation. When a fragment is a list item, put the key on the fragment through the list form supported by the compiler; a diagnostic shows the exact spelling for the current implementation.

## Lists

```clojure
(ui/defview todo-list [{:keys [todos]}]
  [:ul.todo-list
   (for [todo todos]
     [todo-row
      {:key  (:todo/id todo)
       :todo todo}])])
```

The compiler lowers this `for` to a JavaScript array. Every element requires a visible `:key`.

Use a stable semantic key:

```clojure
{:key (:todo/id todo)}       ; good
{:key index}                 ; only safe for a proven append-only list
{:key (random-uuid)}         ; always wrong; remounts on every render
```

The supported list grammar includes one generator and `:let`, `:when`, and `:while` modifiers:

```clojure
(for [item items
      :when (:visible? item)
      :let [id (:item/id item)]]
  [item-row {:key id :item item}])
```

Nested collections use nested `for` forms or child views. A `map` returning markup is rejected because it would reintroduce lazy element production and weaken source/key analysis.

## DOM events

The most common form is data:

```clojure
[:button {:on-click [::saved document-id]} "Save"]
```

See [State reads and events](03-state-and-events.md) for event-object extraction and forwarding.

## Static output

The compiler hoists output that reads no props, locals, subscriptions, context, refs, or handlers:

```clojure
(ui/defview legal-notice []
  [:aside.legal
   [:strong "Notice"]
   [:p "Terms apply."]])
```

You do not write a memo annotation. The compiler proves and performs the hoist in production. Development may retain per-instance element creation for source evidence.

## Dynamic React type

Use `ui/element` when the type itself is a runtime value:

```clojure
(ui/element heading-component {:level 2} title)
```

The props and children are still compiled. If the props map is also dynamic, use `ui/spread` explicitly:

```clojure
(ui/element heading-component (ui/spread heading-props) title)
```

`ui/element` uses the public/foreign JS props ABI. For a runtime-selected internal view, obtain its boundary wrapper explicitly:

```clojure
(ui/element (ui/view view-id) {:document-id id})
```

Literal `[known-defview {...}]` calls remain the fast path and bypass that wrapper. The runtime never guesses whether an arbitrary component type is internal.

## Dynamic props

```clojure
[:button
 (ui/spread shared-button-props
            {:disabled disabled?})
 "Submit"]
```

`ui/spread` walks and converts a runtime map. It is valid, but it is visible in compiler performance reports because it cannot use the normal straight-line prop path. Prefer literal props in hot repeated views.

## Existing React element

```clojure
[:section (ui/raw element-from-js)]
```

`ui/raw` promises the value is already React-renderable. JVM SSR needs a declared `ui/client-only` fallback if that element originates from JavaScript.

## Compile errors are boundaries

Common messages and fixes:

| Error | Fix |
|---|---|
| Runtime vector in template position | Inline the branch, extract a `defview`, or use `ui/element` for a dynamic React type. |
| Lazy seq as children | Use compiler-recognized `for`. |
| Missing key | Add a stable `:key` to the list element. |
| Unknown DOM prop | Use the suggested canonical spelling or a valid `data-*`/`aria-*` prop. |
| Plain function callback | Choose `ui/event`/`ui/handler` for post-commit invocation, `ui/render-fn` for pure render-time invocation, or the explicit `ui/raw-handler` escape. |
| Subscription in a loop | Extract a keyed child view or register an aggregate subscription. |
| Dynamic prop map | Mark it with `ui/spread` so the cost and semantics are explicit. |

Strictness means a source form has one predictable browser, SSR, debugging, and performance meaning.
