# Use a React component

A date picker, a chart or a rich-text editor from npm is a React component written in JavaScript. This recipe renders one inside a `reg-view`, feeds it a value from a subscription, and turns its callbacks into events. It uses the Reagent adapter, and [reagent-slim](use-uix-or-slim.md) works the same way, with one difference in [how props cross](#2-know-how-props-cross). A UIx component passes the component to `$` like any other. A [Fresco](../fresco/index.md) view declares it with `h/defhost` instead ([Interop](../fresco/09-interop.md)).

??? info "For JavaScript developers"

    `[:> DatePicker {:selected date :on-change f}]` is `<DatePicker selected={date} onChange={f} />`. The component itself is unchanged; only the call site is hiccup.

## 1. Render it with `[:>]`

Install the package with npm and require it. A vector headed by `:>` renders it: the component comes second, then its props, then any children.

```clojure
(ns my.app.views
  (:require ["react-datepicker" :default DatePicker]
            [re-frame.core :as rf]))

(rf/reg-event :todo/set-due
  (fn [{:keys [db]} [_ id date]]
    {:db (assoc-in db [:todos id :due] date)}))

(rf/reg-sub :todo/due-date
  (fn [db [_ id]]
    (get-in db [:todos id :due])))

(rf/reg-view due-field [{:keys [id]}]
  [:> DatePicker {:selected         @(subscribe [:todo/due-date id])
                  :on-change        #(dispatch [:todo/set-due id %])
                  :placeholder-text "No due date"}])
```

The value comes from a subscription and a change is an event, as in any other view, so the due date lives in app-db and each change shows up in the trace.

The date picker calls `onChange` when the user picks a date, after `due-field` has rendered. The callback therefore uses the injected `dispatch`, which captured the view's [frame](../glossary.md#frame) during render. A bare `rf/dispatch` there raises `:rf.error/no-frame-context` ([Views](../views.md#the-trap-a-callback-that-fires-after-render-has-no-frame) explains why).

A callback receives JavaScript values. This one receives a `js/Date`, which is the value `#inst` reads as in ClojureScript, so it can go into app-db as it is. Convert other JavaScript objects to data before you dispatch them.

## 2. Know how props cross

Reagent turns the props map into a JavaScript object before the component sees it:

| You write | The component receives |
|---|---|
| `:on-change`, `:placeholder-text` | `onChange`, `placeholderText` |
| `:class`, `:for` | `className`, `htmlFor` |
| `:data-testid`, `:aria-label` | `data-testid`, `aria-label`, unchanged |
| a string, number, boolean, `nil` or function | the same value |
| a nested map, such as `{:max-width 200}` | a JavaScript object with camelCase keys, `{maxWidth: 200}` |
| a vector, list or set | a JavaScript array made by `clj->js`, whose map keys are not camelCased |
| a keyword, such as `:small` | its name, `"small"` |

reagent-slim differs on the last row. It passes a keyword prop value through unchanged, except under `:class`, `:id`, `:role`, `data-*` and `aria-*`, and a development build warns once; keywords inside a nested map still become strings. Write a string wherever the library documents one, and both adapters agree.

Children after the props map are hiccup, converted like any other child.

## 3. Pass hiccup where the component wants an element

Hiccup inside a prop is a vector, so it arrives as a JavaScript array rather than a React element. A prop that takes an element needs `reagent.core/as-element`:

```clojure
(ns my.app.views
  (:require ["react-datepicker" :default DatePicker]
            [re-frame.core :as rf]
            [reagent.core :as r]))

(rf/reg-view due-field [{:keys [id]}]
  [:> DatePicker {:selected     @(subscribe [:todo/due-date id])
                  :on-change    #(dispatch [:todo/set-due id %])
                  :custom-input (r/as-element [:button.due "Set a due date"])}])
```

A render prop, a function the component calls for an element, follows the same rule: `(fn [item] (r/as-element [:li (.-label item)]))`. On reagent-slim the function is `reagent2.core/as-element`.

## 4. Return several elements with `[:<>]`

A fragment groups siblings without a wrapper element. Here a `when` needs to add two elements to the list:

```clojure
(rf/reg-view todo-detail [{:keys [title due]}]
  [:dl
   [:dt "Title"] [:dd title]
   (when due
     [:<> [:dt "Due"] [:dd (.toLocaleDateString due)]])])
```

A view can return a fragment as its root in the same way. In a `for`, give each fragment a key, `^{:key id} [:<> …]`, as you would any other element.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| React reports "Element type is invalid" | The require resolved to `nil`, usually a default export required with `:refer`, or a named export required with `:default` | Match the library's export: `["lib" :default X]` for a default export, `["lib" :refer [X]]` for a named one |
| A callback raises `:rf.error/no-frame-context` | It calls `rf/dispatch`, which looks for a frame when the component calls back | Use the view's injected `dispatch` |
| Hiccup in a prop shows up as text, or the component throws when it uses the prop | The prop was converted to a JavaScript array | Wrap it in `r/as-element` |
| The library ignores an option inside a vector | Map keys inside a vector are not camelCased | Build that value with `#js` and the library's own key names |
| React warns that each child in a list needs a key | A `for` produced `[:>]` or `[:<>]` elements without keys | Add `^{:key id}` to each |
