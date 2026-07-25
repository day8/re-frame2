# How Freehand is different from Reagent

Same *job* (view layer on re-frame). Different *contract*: Freehand is
**re-frame-native**; Reagent is a React wrapper re-frame *uses*. Hiccup is still
Hiccup. What changes is subscriptions, events, local state, and how much of the
UI stays plain data.

This is a **habit comparison**, not a migration checklist.

## The short version

| Topic | Typical Reagent + re-frame | Freehand |
|---|---|---|
| Role in re-frame2 | first-class **adapter** (React-shaped view layer) | first-party **re-frame-native** view layer |
| What a view is | function / form-1 / form-2 / form-3 | one form: `v/defview` |
| How you call a component | often `[comp]` or subtle Form-2 rules | `[view props]` = boundary; `(helper)` = inline |
| Subscriptions | `(rf/subscribe …)` then `@` / deref | `(v/sub …)` **is** the value |
| Event handlers in the tree | usually `#(rf/dispatch …)` closures | usually event **vectors** (data) |
| Local UI state | ratoms, Form-2 atoms, sometimes Reagent state | **no** `local` / ratom model in ordinary views — re-frame (or a library controller) |
| React interop | often close to the metal | explicit host boundaries: a React child, a registered behavior, or `v/->react` |
| Testing intent | often mount or simulate | structural tree on the JVM; assert vectors with `=` |
| Performance path | memo, careful Form-2, React tricks | same authoring model; optional `{:compiled true}` on the declaration |

**One sentence:** Freehand keeps re-frame’s data orientation all the way to the
view; Reagent is a general React wrapper that re-frame *uses*, so the view layer
can drift into a second state and handler model.

## What stays comfortable

You will not feel lost on day one.

- **Hiccup** — tags, props maps, children, the usual shapes.  
- **Props** — one map into the view is the Freehand norm (similar to many modern
  Reagent styles, stricter than positional args).  
- **Vector call for components** — `[todo-row {:id id}]` still mounts a child.  
- **re-frame itself** — `reg-event`, `reg-sub`, app-db, effects, frames. Freehand
  does not replace that pipeline; it sits on top of it.

If your Reagent code was already “mostly controlled inputs and dispatch,” Freehand
will feel like a cleanup of that style, not a new architecture.

## The shifts that matter

### 1. One component form, not Form-1 / 2 / 3

Reagent’s Form-2 and Form-3 exist largely to hold local state and lifecycle.
Freehand collapses that:

```clojure
;; Freehand — always this shape
(v/defview greeting [{:keys [name]}]
  [:p "Hello, " name])
```

There is no “switch to Form-2 because I need an atom.” If you need state, it goes
through re-frame (or a documented library controller). Lifecycle that touches the
DOM goes through host boundaries, not a component class.

Helpers are plain `defn`s called with parentheses. Their `sub` reads belong to the
enclosing boundary. That replaces a lot of “is this Form-1 or Form-2?” folklore
with one sharp rule: **vector = boundary, parens = inline.**

### 2. Subscriptions are values, not things you deref

```clojure
;; Reagent-ish
(let [n @(rf/subscribe [:count])]
  [:span n])

;; Freehand
[:span (v/sub [:count])]
```

You do not hold a reaction object in application code. Freehand records the read
while the view renders and re-renders that boundary when the value moves.

Outside a view render, use `rf/subscribe-once` — Freehand will not let `v/sub`
quietly mean “probe once” in a callback. That separation is intentional.

### 3. Handlers are data on the paved path

```clojure
;; Reagent-ish
[:button {:on-click #(rf/dispatch [:count/inc])} "+"]

;; Freehand
[:button {:on-click [:count/inc]} "+"]
```

The Freehand form is readable in source, visible to tools before it fires, and
assertable in a JVM test without clicking. Closures still exist when you need them
(`v/event`, `v/handler`), but they are the **escape**, not the default.

For inputs, live scalars use projection markers instead of
`(.. % -target -value)` in a lambda:

```clojure
[:input {:value (v/sub [:email])
         :on-input [:form/set-email ::v/value]}]
```

### 4. No ratom as the app model

Reagent makes it easy (sometimes too easy) to put “just this toggle” in a ratom.
Freehand removes that escape hatch from ordinary views on purpose.

| Reagent habit | Freehand direction |
|---|---|
| `(reagent/atom false)` for open/closed | re-frame fact + event, or props-only controlled component |
| Form-2 let-binding of atoms | same — re-frame or library controller |
| “Ephemeral so it doesn’t belong in app-db” | still re-frame if it is product interaction; host-only if it is truly DOM |

That can feel stricter at first. The payoff is one place for truth, one place for
time-travel and Xray, and fewer “works until reload” bugs.

Hard multi-step fields (commit-on-blur, cancel, same-value reject) can still be
packaged cleanly — as a **library** pattern with an explicit `:control` address,
not as silent component-local cells.

### 5. React is a host, not the whole model

In Reagent, the boundary between “my component” and “React” is often soft. In
Freehand:

- ordinary views stay free of hooks  
- foreign components are **qualified** host leaves  
- imperative DOM libraries use **registered behaviors**  
- real hook/portal/context needs go in a **React component**, entered as a child  

You still use React. You just do not pretend every React pattern is a Freehand
primitive.

### 6. Testing intent without a browser

With Reagent, “what does this button do?” often means mount and simulate, or trust
a callback mock.

With Freehand, a structural render on the JVM can show the event vector on the
node. You still use mounted tests for caret, focus, and third-party widgets — but
not for every handler wiring check.

### 7. Performance path is “same view, optional compile”

Reagent performance work is often memo, Form-2 discipline, and React-level
tricks.

Freehand’s growth path is: write freely interpreted → measure → optionally mark
`{:compiled true}` on the **same** `defview`. Call sites and
tests stay the same. That is different from both “always interpret” and from
re-frame.ui’s compile-first day-one feel.

### 8. A key is a prop, never metadata

`^{:key (:id r)} [row-view r]` is *the* Reagent spelling for a list row, and it is
the habit most likely to survive a rewrite unnoticed. Freehand does not read it.
Nothing in this substrate carries a contract in metadata, so a key written there
reaches neither the structural tree nor React, and the list renders silently
unkeyed — which surfaces much later, as rows that reuse each other's state.

Both modes therefore refuse the metadata spelling outright rather than dropping
it, and the key goes where every other prop goes:

```clojure
;; Reagent
(for [r rows] ^{:key (:id r)} [row-view r])

;; Freehand
(for [r rows] [row-view {:key (:id r) :row r}])
```

Content with no props map of its own takes a keyed fragment instead:
`[:<> {:key k} (v/slot row r)]`.

## Side-by-side: a tiny screen

```clojure
;; --- Reagent-ish sketch ---
(defn counter []
  (let [n @(rf/subscribe [:count])]
    [:div
     [:p "Count: " n]
     [:button {:on-click #(rf/dispatch [:count/inc])} "Inc"]
     [:input {:value @(rf/subscribe [:note])
              :on-change #(rf/dispatch [:note/set (.. % -target -value)])}]]))

;; --- Freehand ---
(v/defview counter [_]
  [:div
   [:p "Count: " (v/sub [:count])]
   [:button {:on-click [:count/inc]} "Inc"]
   [:input {:value (v/sub [:note])
            :on-input [:note/set ::v/value]}]])
```

Same re-frame events and subscriptions underneath. Different view contract on top.

## What Freehand is *not* trying to be

- A drop-in Reagent replacement with the same Form-2/Form-3 APIs  
- A promise that every Reagent library will work unchanged  
- A ban on React — only a ban on treating hooks and ratoms as the app model  

Reagent and UIx remain first-class **view layers** in re-frame2 for ecosystem and
migration reasons. Freehand is the **re-frame-native** peer for teams that want
the UI to stay inside that architecture.

## When the comparison bites

| If you loved… | You may feel… | Freehand’s answer |
|---|---|---|
| Quick ratom toggles | “App-db for *this*?” | Yes for product UI; host boundary for pure DOM |
| `#(dispatch …)` everywhere | “Vectors are verbose” | Until you test and inspect them |
| Form-2 closures | “Where does instance state go?” | Props, re-frame, or library `:control` |
| Free React components | “Why qualify hosts?” | So the tree stays honest for tools and tests |

None of those frictions are accidents. They are the cost of keeping the view layer
in the same data story as the rest of re-frame.
