# Hiccup: HTML as data

Every view you write returns **hiccup**: nested vectors shaped like the DOM they
describe. The [Introduction](introduction.md)'s counter view was hiccup. This page
covers the notation. Each cell below renders its last form; edit any of them and press
**Ctrl-Enter** (**Cmd-Enter** on macOS) to re-evaluate.

## An element is a vector

```cljs-rf2
[:p "Hello from a vector"]
```

The keyword at the head names the tag. Everything after it is content. Change `:p`
to `:h1` and re-evaluate.

## Children follow, and they nest

```cljs-rf2
[:div
 [:h2 "Todos"]
 [:p "Three things, " [:em "maybe"] " four:"]
 [:ul
  [:li "Buy milk"]
  [:li "Walk the dog"]
  [:li "Pay rent"]]]
```

Strings become text nodes; vectors become child elements; order is document order.
The indentation is just formatting; the structure comes entirely from the nesting.

## Attributes are a map in second position

```cljs-rf2
[:div {:style {:background "LavenderBlush"
               :padding "1em"
               :border-radius "8px"
               :font-family "sans-serif"}}
 "A " [:a {:href "https://clojurescript.org"} "link"]
 " in a styled box"]
```

If an element's second slot is a map, those are its attributes — `:href`, `:title`,
`:disabled`, anything the element takes. `:style` is itself a map: CSS properties as
keywords, values as strings.

!!! tip "Try it"

    `"LavenderBlush"` is a real CSS colour name, and so is `"PapayaWhip"`.
    Swap one in, add `:border "2px solid Tomato"`, re-evaluate.

Two shorthands, borrowed from CSS selectors, fold classes and ids into the tag
keyword:

```clojure
[:li.done ...]                  ; <li class="done">
[:input#new-todo.new-todo ...]  ; <input id="new-todo" class="new-todo">
```

## It's data, so code writes it

There is no template language to learn. The screen is a data structure, and all of
ClojureScript already works on data:

```cljs-rf2
(def titles ["Buy milk" "Walk the dog" "Pay rent" "Call Mum"])

(into [:ul]
  (for [t titles]
    [:li t]))
```

`for` produces a `[:li ...]` per title; `into` pours them into the `[:ul]`. Add a
title. Then make it `(for [t (sort titles)] ...)`.

Conditional markup is plain `when`, because a `nil` child renders as nothing:

```cljs-rf2
(def done? true)

[:h3 "Buy milk "
 (when done?
   [:em {:style {:color "Tomato"}} "— done!"])]
```

Change `true` to `false` and the emphasis disappears. There is no special syntax for
conditional rendering, just an expression that is sometimes `nil`.

Two special heads cover what a tag keyword can't. `[:<> child1 child2]` is a
fragment: several siblings with no wrapper element, for a view that returns more
than one element. `[:> Component props child …]` renders a React component written in
JavaScript, such as a date picker from npm.

??? info "For JavaScript developers"

    Hiccup does the job of JSX. Both describe a tree of elements, but hiccup is
    literal data (vectors and maps you can `map`, `filter`, `sort`, and pass to
    functions), so there is no build-time transform and no `{}` escape back into
    the host language.

## Naming a piece of screen: `reg-view`

So far the hiccup has been anonymous. Real apps are built from named pieces, and
you met the registration for that in the Introduction:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-view greeting [who]
  [:p "Hello, " [:strong who] "!"])

[greeting "world"]
```

`reg-view` reads like `defn` (arguments in, hiccup out), and it also registers the
view under an id derived from its name, so the framework and its tooling can find it.
The view is not called like a function. It is placed at the head of a vector, where a
tag keyword would go, with its arguments as the tail. `[greeting "world"]` is still
just data.

These cells render inside a frame the page provides for you. In an app, views render
under a `frame-root`, as in the Introduction; rendering a view outside any frame
raises `:rf.error/no-frame-context`.

## Views use views

That head-of-vector rule is the composition rule. A view's hiccup can contain other
views, exactly the way a `:div` contains a `:span`:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-view done-badge [done?]
  [:span {:style {:color (if done? "MediumSeaGreen" "Tomato") :font-weight "bold"}}
   (if done? "done" "to do")])

(rf/reg-view todo-row [{:keys [title done?]}]
  [:li {:style {:margin "0.25em 0"}}
   title " " [done-badge done?]])

[:ul
 [todo-row {:title "Buy milk" :done? false}]
 [todo-row {:title "Walk the dog" :done? true}]]
```

The same shape repeats at every level: `todo-row` uses `done-badge` the way `:li`
uses `:span`. A screen is a tree of views, and views bottom out in element keywords.

!!! tip "Try it"

    View usage is data too, so the `for`/`into` trick from earlier composes views as
    easily as `:li`s:

    ```clojure
    (def todos [{:title "Buy milk" :done? false}
                {:title "Walk the dog" :done? true}])

    (into [:ul]
      (for [t todos]
        [todo-row t]))
    ```

## Troubleshooting

| What you wrote | The rule it tripped | Fix |
|---|---|---|
| `["div" "hi"]` | The head must be a tag keyword or a view; a string is neither | `[:div "hi"]` |
| `[:p "hi" {:style ...}]` | The attribute map must be **second**; anywhere later it's just another child | `[:p {:style ...} "hi"]` |
| The cell reports a reader error | A bracket is unbalanced; hiccup must first read as data | Balance the brackets |
| The console warns that every element in a seq needs a unique `:key` | A `(for ...)` sequence was placed directly as a child | Pour it in with `into`, or give each item a stable `^{:key id}` ([Views](views.md) shows keys) |
