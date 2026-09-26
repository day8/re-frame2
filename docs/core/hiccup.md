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
 [:h2 "Shopping list"]
 [:p "Three things, " [:em "maybe"] " four:"]
 [:ul
  [:li "Bread"]
  [:li "Milk"]
  [:li "Cheese"]]]
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
[:div.card ...]          ; <div class="card">
[:input#email.wide ...]  ; <input id="email" class="wide">
```

## It's data, so code writes it

There is no template language to learn. The screen is a data structure, and all of
ClojureScript already works on data:

```cljs-rf2
(def fruits ["Apples" "Pears" "Plums" "Cherries"])

(into [:ul]
  (for [f fruits]
    [:li f]))
```

`for` produces a `[:li ...]` per fruit; `into` pours them into the `[:ul]`. Add a
fruit. Then make it `(for [f (sort fruits)] ...)`.

Conditional markup is plain `when`, because a `nil` child renders as nothing:

```cljs-rf2
(def done? true)

[:h3 "Buy milk "
 (when done?
   [:em {:style {:color "Tomato"}} "— done!"])]
```

Change `true` to `false` and the emphasis disappears. There is no special syntax for
conditional rendering, just an expression that is sometimes `nil`.

??? info "For JavaScript developers"

    Hiccup does the job of JSX. Both describe a tree of elements, but hiccup is
    literal data (vectors and maps you can `map`, `filter`, `sort`, and pass to
    functions), so there is no build-time transform and no `{}` escape back into
    the host language.

## Naming a piece of screen: `reg-view`

So far our hiccup has been anonymous. Real apps are built from *named* pieces, and
you met the registration for that in the Introduction:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-view greeting [who]
  [:p "Hello, " [:strong who] "!"])

[greeting "world"]
```

`reg-view` reads like `defn` — arguments in, hiccup out — and it also *registers*
the view under an id derived from its name, so the framework and its tooling can find
it. Notice how the view gets used: not called like a function, but placed at the
**head of a vector**, where a tag keyword would go, with its arguments as the tail.
`[greeting "world"]` is still just data.

These cells render inside a frame the page provides for you. In an app, views render
under a `frame-root`, as in the Introduction; rendering a view outside any frame
raises `:rf.error/no-frame-context`.

## Views use views

That head-of-vector rule is the composition rule. A view's hiccup can contain other
views, exactly the way a `:div` contains a `:span`:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-view price-tag [amount]
  [:span {:style {:color "MediumSeaGreen" :font-weight "bold"}}
   "$" amount])

(rf/reg-view product-card [{:keys [title price]}]
  [:div {:style {:border "1px solid #ccc" :border-radius "8px"
                 :padding "0.5em 1em" :margin "0.5em 0"}}
   [:h3 title]
   [:p "Yours for " [price-tag price]]])

[:div
 [product-card {:title "Aeron chair" :price 1200}]
 [product-card {:title "Standing desk" :price 800}]]
```

The same shape repeats at every level: `product-card` uses `price-tag` the way
`:div` uses `:span`. A screen is a tree of views, and views bottom out in element
keywords.

!!! tip "Try it"

    View usage is data too, so the `for`/`into` trick from earlier composes views as
    easily as `:li`s:

    ```clojure
    (def products [{:title "Aeron chair" :price 1200}
                   {:title "Standing desk" :price 800}])

    (into [:div]
      (for [p products]
        [product-card p]))
    ```

## Troubleshooting

| What you wrote | The rule it tripped | Fix |
|---|---|---|
| `["div" "hi"]` | The head must be a tag keyword or a view — a string head is neither | `[:div "hi"]` |
| `[:p "hi" {:style ...}]` | The attribute map must be **second**; anywhere later it's just another child | `[:p {:style ...} "hi"]` |
| The cell reports a reader error | A bracket is unbalanced — hiccup is data before it is anything else | Balance the brackets |
| The console warns that every element in a seq needs a unique `:key` | A `(for ...)` sequence was placed directly as a child | Pour it in with `into`, or give each item a stable `^{:key id}` ([Views](views.md) shows keys) |
