# 00 — ClojureScript for non-Clojurians

> *Reading is the prerequisite. Writing is later.*

This chapter exists because every snippet in chapters 01–23 is ClojureScript, and chapters 01–23 assume you can parse it. If you've never seen a Clojure parenthesis before, the first few snippets will look like noise. After this chapter they won't.

We're after **reading fluency, not writing fluency**. By the end you should be able to look at a re-frame2 event handler, view function, or subscription and know what each piece is doing — even if you couldn't have written it yourself. Forty minutes, eyes only. No setup, no installs.

The cells below are **live**. Click inside one, edit the code, press `Ctrl-Enter` (or `Cmd-Enter`), and the result re-evaluates in your browser. No build step, no REPL, no install — your browser is running a ClojureScript compiler. If a cell shows an error, change something and re-evaluate. There is nothing you can break.

---

## 1. Syntax is just data

ClojureScript code is data. Specifically, it's made of three kinds of bracketed forms plus a handful of literals. That's the whole language surface — there is no special syntax for function calls, no statement-vs-expression distinction, no semicolons. Everything is a value, including the program itself.

### Literals

```klipse
;; numbers, strings, characters, booleans, nil
42
```

```klipse
"hello"
```

```klipse
nil
```

These evaluate to themselves. `nil` is the equivalent of JavaScript's `null` — there is no separate `undefined`.

### Collections

Four collection literals. Note the bracket characters; they're load-bearing.

```klipse
;; a list — parens
'(1 2 3)
```

```klipse
;; a vector — square brackets
[1 2 3]
```

```klipse
;; a map — curly braces; pairs are key value key value
{:name "Ada" :born 1815}
```

```klipse
;; a set — hash + curly braces
#{:red :green :blue}
```

Collections nest. A vector can hold a map can hold a vector — there's no type ceremony:

```klipse
{:user {:name "Ada"
        :tags  ["mathematician" "first-programmer"]
        :addr  {:city "London"}}}
```

### Symbols

A **symbol** is a name. `inc`, `map`, `my-fn`, `app-db` — these are all symbols. They look like identifiers in other languages, but in ClojureScript they're first-class data values that *refer to* something else (a function, a variable, a value).

```klipse
;; quote a symbol to get the symbol itself, not what it refers to
'foo
```

That's the whole syntax. A few more bracketed literals show up later (`#(...)` short-fn, `#"..."` regex, `#'foo` var-quote) but the four collection brackets plus symbols and primitive literals are 95% of what you'll read.

---

## 2. The three evaluation rules

ClojureScript code is data, and **evaluation** is what turns one piece of data into another piece of data. There are exactly three rules.

### Rule 1: literals evaluate to themselves

Numbers, strings, keywords, booleans, `nil`, vectors, maps, and sets all evaluate to themselves. You've seen this above already.

```klipse
[1 2 3]
```

The vector evaluates to itself. There is no function call, no construction, no allocation ceremony — `[1 2 3]` *is* a value.

### Rule 2: symbols evaluate to what they're bound to

A bare symbol is replaced by its value at evaluation time:

```klipse
;; inc is bound to the increment function
inc
```

```klipse
;; + is bound to the addition function
+
```

This is why you don't see operator-vs-function distinctions: `+` is just a symbol bound to a function, like any other.

### Rule 3: lists are function calls

A list `(f a b c)` evaluates by:
1. evaluating `f` (rule 2) to find the function it names,
2. evaluating `a`, `b`, `c` to find the arguments,
3. calling the function on the arguments.

```klipse
(+ 1 2 3)
```

```klipse
(inc 41)
```

```klipse
(str "hello" " " "world")
```

The "operator goes first" thing — `(+ 1 2)` not `1 + 2` — is the most visually jarring part of Clojure for newcomers. It evens out within an hour of reading. The payoff: there's no operator precedence to remember, because there are no operators.

### Forms nest

Function calls are just lists, and lists can contain lists. The reader works inside-out:

```klipse
(+ 1 (* 2 3) (- 10 4))
```

That evaluates `(* 2 3)` and `(- 10 4)` first, then sums their results with `1`. Same idea as nested function calls in any language; the brackets just live in different places.

**Try it yourself.** What does this evaluate to? Edit the cell to confirm your guess:

```klipse
;; Predict the answer before pressing Ctrl-Enter
(+ (inc 4) (* 2 (- 10 7)))
```

> The expected result is `11`: `(inc 4) → 5`, `(- 10 7) → 3`, `(* 2 3) → 6`, `(+ 5 6) → 11`.

---

## 3. Keywords, kebab-case, and the `?` convention

Three small naming conventions that show up everywhere. Worth ten minutes now to save you hours of confusion later.

### Keywords

A **keyword** starts with a colon: `:name`, `:user-id`, `:rf/dispatch`. They're like strings, but cheaper to compare and intentionally designed to be used as map keys.

```klipse
:status
```

Keywords evaluate to themselves (rule 1). The leading colon is the marker — `name` is a symbol that refers to something; `:name` is the keyword itself.

The interesting trick is that **keywords are also functions of maps** — they look themselves up:

```klipse
(:name {:name "Ada" :born 1815})
```

That's `(:name some-map)` — calling the keyword as if it were a function. You'll see this constantly in re-frame2 subscriptions and view code:

```klipse
;; idiomatic — pull a value from a map
(let [user {:name "Ada" :age 30}]
  (:name user))
```

Keywords can also be **namespaced** with a slash: `:rf/dispatch`, `:user/email`. The namespace is just part of the name — it makes the keyword distinct from a bare `:dispatch` or `:email` in some other map. Re-frame2 uses `:rf/*` keywords throughout for framework-reserved keys.

### kebab-case

Clojure names words-like-this, not wordsLikeThis or words_like_this. So `app-db`, not `appDb` or `app_db`. `user-name`, `is-logged-in`, `dispatch-sync`.

The `-` is just part of the symbol — it isn't subtraction (subtraction is the `-` symbol used in operator position, `(- 1 2)`). The reader knows the difference from context.

### The `?` suffix for predicates

A function whose name ends in `?` returns a boolean. By convention, always — never `true`/`false`/`nil` mixed with other return types.

```klipse
(odd? 3)
```

```klipse
(empty? [])
```

```klipse
(string? "hi")
```

When you see `(loading? db)` or `(valid? form)` in the wild, you know without thinking: it's a boolean test.

---

## 4. Special forms: `if`, `fn`, `def`, `defn`, `let`

These five forms cover almost every piece of control flow and binding you'll read in a re-frame2 codebase. They're **special forms** because they don't follow rule 3 strictly — they have their own evaluation rules baked into the language.

### `if`

`(if test then else)`. Standard ternary, but everything is an expression — `if` *returns* a value.

```klipse
(if (> 3 2) "yes" "no")
```

`nil` and `false` are the only falsy values. Everything else — including `0`, `""`, and `[]` — is truthy. (This trips people coming from JavaScript; commit it to muscle memory.)

```klipse
(if 0 "zero is truthy" "zero is falsy")
```

### `fn` — anonymous functions

`(fn [args] body)` makes a function value. It evaluates to a function; you can call it immediately or hand it off:

```klipse
((fn [x] (* x x)) 5)
```

The argument list is a *vector* (`[x]`), not parentheses. The body is whatever comes after; the last expression is the return value.

### `def` — bind a name to a value

`(def my-name some-value)` introduces a top-level name. The right-hand side can be anything — a number, a function, a map.

```klipse
(def pi 3.14159)
pi
```

### `defn` — define a named function

`(defn fn-name [args] body)` is sugar for `(def fn-name (fn [args] body))`. This is the bread-and-butter form for declaring functions.

```klipse
(defn square [x] (* x x))
(square 7)
```

Most re-frame2 event handlers are written with `defn`:

```cljs
;; A typical event handler — don't run this; it needs re-frame loaded.
(defn handle-login
  [db [_ user]]
  (assoc db :current-user user))
```

### `let` — local bindings

`(let [name value name value ...] body)`. Like `const` but inside an expression and the bindings vanish at the closing paren of the `let`. The body is the *value* of the whole `let` form.

```klipse
(let [x 3
      y 4
      h (Math/sqrt (+ (* x x) (* y y)))]
  h)
```

Bindings can refer to earlier bindings in the same `let`: `y` could use `x`; `h` uses both. They're sequential.

**Try it yourself.** Predict the result, then run:

```klipse
(let [a 10
      b (* a 2)
      c (+ a b)]
  c)
```

> If you got anything other than `30`, look again: `a=10`, `b=20`, `c=30`.

---

## 5. Data-as-function, builtins, and threading

Two of the most distinctively-Clojure ideas live in this section. They're the difference between "I can parse this" and "I can read this fluently."

### Data structures are functions of their keys

You've already seen keywords-as-functions:

```klipse
(:name {:name "Ada"})
```

It works the other way too — **maps and vectors are themselves functions** of their keys/indices:

```klipse
;; map as function: look up by key
({:a 1 :b 2} :a)
```

```klipse
;; vector as function: look up by index
(["zero" "one" "two"] 1)
```

This is why `(get my-map :key)` and `(:key my-map)` and `(my-map :key)` all do the same thing. You'll see all three in the wild; pick one when writing, recognise all three when reading.

### Builtins you'll see constantly

A handful of pure functions show up so often in re-frame2 code that it's worth knowing them on sight.

`assoc` — return a *new* map with a key added or updated. The original map is unchanged.

```klipse
(assoc {:a 1 :b 2} :c 3)
```

`update` — return a new map with a function applied to one value.

```klipse
(update {:count 5} :count inc)
```

`map` — apply a function to every element of a collection; returns a new sequence.

```klipse
(map inc [10 20 30])
```

`filter` — keep only elements for which a predicate returns truthy.

```klipse
(filter odd? [1 2 3 4 5])
```

`reduce` — fold a collection down to a single value.

```klipse
(reduce + 0 [1 2 3 4 5])
```

### Threading: `->` and `->>`

When you chain operations, naive nesting reads inside-out and gets ugly fast:

```klipse
;; "take the map, assoc :greeted, then update :count"
(update (assoc {:count 0} :greeted true) :count inc)
```

The **thread-first macro** `->` rewrites that as a left-to-right pipeline. It passes the value through as the *first* argument of each step:

```klipse
(-> {:count 0}
    (assoc :greeted true)
    (update :count inc))
```

Same result, but you read it top-to-bottom: "start with this map; assoc `:greeted true`; update `:count` with `inc`." Re-frame2 event handlers are almost always written in this shape.

The **thread-last macro** `->>` passes the value as the *last* argument. Used with sequence operations:

```klipse
(->> [1 2 3 4 5]
     (filter odd?)
     (map #(* % %))
     (reduce +))
```

The `#(* % %)` you'll meet in a moment — it's a shorthand for `(fn [x] (* x x))`.

---

## 6. Immutability and four small things

This is the conceptual heart of the chapter. If you understand this section, the rest of the guide will read naturally.

### Immutable data

Every collection in ClojureScript is **immutable**. `assoc`, `update`, `conj`, etc. all *return new collections* rather than mutating the original. Try it:

```klipse
(let [a {:x 1}
      b (assoc a :y 2)]
  ;; a is unchanged. b is the new map.
  [a b])
```

You'll see `a` is still `{:x 1}` and `b` is `{:x 1 :y 2}`. The two maps share their inner structure — adding `:y` didn't *copy* the whole map, it built a new map that points to the old one's contents and adds `:y` on top. This is **structural sharing**, and it's why immutability isn't slow in Clojure.

This single property is what makes re-frame2's "time-travel debugger" not a trick. The framework can record every `app-db` value the app ever had, because old values are never overwritten — they're just no longer the *current* value.

### Four small things you'll see in passing

These don't get their own section, but they show up in re-frame2 code and you should recognise them.

**1. `#(...)` — short anonymous function.** A reader shorthand for a one-off `fn`. `%` is the (single) argument; `%1 %2` are positional args when there are multiple.

```klipse
;; same as (fn [x] (* x x))
(#(* % %) 7)
```

```klipse
;; with two args
(#(str %1 " — " %2) "hello" "world")
```

You'll see `#(...)` constantly in `map`/`filter`/`reduce` calls.

**2. `and` and `or` short-circuit and return the value, not a bool.**

```klipse
;; (and ...) returns the last truthy value, or the first falsy one
(and 1 2 3)
```

```klipse
;; (or ...) returns the first truthy value
(or nil false :found)
```

This is why you'll see `(or (:user db) :anonymous)` in the wild — it's both a guard *and* a default.

**3. `@` — deref a reactive cell.** When you see `@some-thing` in a view or subscription, it means "the current value inside this reactive container." Atoms, reagent's RAtoms, and re-frame2 subscriptions all support `@`. You'll meet this properly in ch.06 (Views and frames); for now just know that the `@` symbol is reading a value, not calling a function.

**4. Destructuring and JS interop exist; skip them for now.** You'll see things like `(defn handler [db [_ user]] ...)` — that's *destructuring* pulling the second element out of the event vector — and `(js/console.log "x")` — that's calling out to JavaScript. You don't need to write either of these to *read* the guide. Ch.18 (From re-frame v1) covers destructuring patterns properly; the [official Clojure guide](https://clojure.org/guides/destructuring) is the canonical reference.

---

## 7. Putting it together

A small worked example. This is the shape of a re-frame2 event handler, written in pure ClojureScript so it runs in the cell. Read it top-to-bottom and see if every piece makes sense.

```klipse
(defn handle-add-item
  "Take the current db and an [event-id item] vector;
   return a new db with the item appended to :cart."
  [db [_ item]]
  (-> db
      (update :cart conj item)
      (assoc  :last-added item)))

(handle-add-item
  {:cart [] :user "Ada"}
  [:add-item {:sku "ABC" :qty 1}])
```

Things to notice:

- The function takes two arguments: `db` (the world) and an event vector. The `[_ item]` destructures the event — `_` discards the event-id, `item` binds to the payload.
- The body is a `->` pipeline. Read it as: "start with `db`; update `:cart` by `conj`'ing the new item onto it; assoc `:last-added` to the item."
- `db` is never mutated. The return value is a *new* map with the changes baked in.
- The function is **pure**: same inputs always produce the same output. No side-effects, no clock, no network. This is why re-frame2 event handlers are trivially testable.

If that read cleanly, you've got it.

**Try it yourself.** Modify the cell above to also increment a `:item-count` key in `db`. (Hint: use `update` with `inc` and a sensible default like `(fnil inc 0)`.) The result map should include `:item-count 1`.

---

## 8. Where to go from here

You don't need to read more ClojureScript theory before ch.01. The rest of the guide explains re-frame2 itself, and every CLJS construct it uses has been covered above. If a snippet ever reads oddly, come back here — this chapter is your index.

Three paths forward, depending on what you want next:

1. **To read the rest of the guide** — you're done. Head to [01 — Why re-frame2](01-why-re-frame2.md). The guide assumes you can parse the constructs in this chapter and nothing more.

2. **To run the example apps locally** — see the [`examples/`](https://github.com/day8/re-frame2/tree/main/examples) directory in the repo. Each example has its own `npm install && npm run dev` instructions. The build tool is **shadow-cljs**, which you'll meet there.

3. **To write your own ClojureScript from scratch** — read [clojure.org/guides/learn/syntax](https://clojure.org/guides/learn/syntax) and the [shadow-cljs User's Guide](https://shadow-cljs.github.io/docs/UsersGuide.html). Or, easier and more fun: clone an example, change something, and watch it hot-reload.

### Summary

You learned three things:

- **Code is data.** Lists are function calls, vectors and maps are themselves, symbols look up bindings.
- **Three evaluation rules.** Literals self-evaluate, symbols resolve to bindings, lists invoke functions on their evaluated arguments. Everything else — `let`, `if`, `fn`, threading — is built on top.
- **Immutability is structural.** New collections share structure with old ones; old ones never change. This is why re-frame2's whole architecture works.

If you can hold those three ideas, you can read every chapter that follows.

---

<!--
  Klipse: in-browser ClojureScript evaluator. Loaded ONLY on this page
  (intentionally not site-wide; ~700 KB plugin).
  Vendored locally in docs/klipse/ rather than CDN-loaded so the chapter
  works even if the upstream Klipse project (dormant since 2022) goes
  away. Relative paths below resolve from the built /guide/00-.../ URL
  back up to /klipse/ at the site root.
-->

<link rel="stylesheet" type="text/css" href="../../klipse/codemirror.css">

<style>
  /* Match Material theme: keep cells visually distinct from the static
     pygments highlight, but inherit the Material content-area font. */
  .CodeMirror {
    height: auto !important;
    border: 1px solid var(--md-default-fg-color--lightest, #e0e0e0);
    border-radius: 4px;
    font-family: "Source Code Pro", ui-monospace, monospace;
    font-size: 0.85rem;
  }
  .klipse-result, .klipse-clojure-result {
    border-left: 3px solid var(--md-primary-fg-color, #1976d2);
    padding: 0.5em 0.75em;
    margin-top: 0.25em;
    background: var(--md-code-bg-color, #f5f5f5);
    font-family: "Source Code Pro", ui-monospace, monospace;
    font-size: 0.85rem;
    white-space: pre-wrap;
  }
</style>

<script>
  // Klipse evaluates ClojureScript in the browser by hijacking elements
  // whose class matches the selector below. Material/pymdownx-superfences
  // renders ```klipse fences as <pre><code class="language-klipse">...</code></pre>
  // (via the custom_fences entry in mkdocs.yml). The selector below
  // targets that exact class — Klipse walks up from <code> to the
  // surrounding <pre> on its own.
  window.klipse_settings = {
    selector_eval_clojure: '.language-klipse',
    codemirror_options_in: {
      lineWrapping: true,
      autoCloseBrackets: true,
      matchBrackets: true
    },
    codemirror_options_out: {
      lineWrapping: true
    }
  };
</script>
<script src="../../klipse/klipse_plugin.min.js"></script>
</content>
</invoke>