# The native tier

Most Hicasso applications do not need native view code. Start with interpreted
Hiccup, tracked `h/sub` reads, and event vectors. Move a measured region only
when profiling identifies work that the normal topology cannot remove.

`[...]` always means interpreted Hiccup. `n/$` always creates React elements
directly. The crossing is explicit in source, visible to diagnostics, and does
not replace the root, frame, or app-db. Applications that never require the
native namespace include none of its code.

## Escalation ladder

| Rung | Authoring form | What changes | Use it when |
| --- | --- | --- | --- |
| 1. Ordinary Hicasso | Hiccup, `h/sub`, event vectors | nothing | default for every screen |
| 2. Tuned Hicasso | same language | view boundaries, keys, read shape, chunking, or virtualization | profiling identifies topology cost |
| 3. Native return from `defview` | a Hicasso view returns `n/$` | skips Hiccup interpretation for that result; retains the view's frame, reads, memo, and lifecycle | Hiccup construction is the measured owner |
| 4. Named native island | `n/defcomponent` or UIx with `n/use-sub` and `n/use-frame` | hooks and high-rate local mechanics run in a real native component | hooks, vendor behaviour, reconciliation, or per-frame local work dominate |
| 5. Native screen | native namespace, UIx, or hosted JS/TS tree | that screen uses a React-first view language | the product surface is React-shaped by design |

Keep an escape only when the measured interaction improves materially: at
least 20%, at least 2 ms at p95, or enough to move a user-visible budget from
fail to pass. Otherwise remove it. A small explicit diff is easier to maintain
than a permanent second authoring style with no demonstrated benefit.

## Return native elements from a Hicasso view

Assume a windowed watchlist has stable keys and each visible row makes one
render-ready subscription read:

```clojure
(ns app.watchlist.row
  (:require [re-frame.hicasso :as h]))

(h/defview quote-row [{:keys [sym]}]
  (let [{:keys [px chg pct vol up?]}
        (h/sub [:quotes/display-row sym])]
    [:tr {:class    (if up? "quote up" "quote down")
          :on-click [:watchlist/select sym]}
     [:td.sym sym]
     [:td.px px]
     [:td.chg chg]
     [:td.pct pct]
     [:td.vol vol]]))
```

If event attribution shows Hiccup construction, rather than subscriptions or
React commit, as the remaining cost, the same `defview` may return an existing
React element:

```clojure
(ns app.watchlist.row
  (:require [re-frame.hicasso :as h]
            [re-frame.hicasso.native :as n]))

(h/defview quote-row [{:keys [sym]}]
  (let [{:keys [px chg pct vol up?]}
        (h/sub [:quotes/display-row sym])]
    (n/$ :tr
         {:class    (if up? "quote up" "quote down")
          :on-click (h/event [_]
                      [:watchlist/select sym])}
         (n/$ :td {:class "sym"} sym)
         (n/$ :td {:class "px"} px)
         (n/$ :td {:class "chg"} chg)
         (n/$ :td {:class "pct"} pct)
         (n/$ :td {:class "vol"} vol))))
```

The parent still renders `[quote-row {:key sym :sym sym}]`. The Hicasso view
keeps its identity, equality memo, frame, subscription reads, lifecycle, and
Xray name. Only its returned subtree bypasses Hiccup interpretation.

The guide's worked measurement moved a 21 ms p95 tick to 13 ms: 38% and 8 ms
recovered. Those numbers justify that example but are not a promise for another
application; remeasure the actual interaction before keeping the crossing.

Important syntax changes:

- Hiccup selector shorthand has no native equivalent. Write class and id in
  props.
- Native event props require functions. Use `h/event` in a rung-3 body to
  create a frame-aware callback; an event vector is rejected.
- Native children are ReactNode values. Nest `n/$` forms. Convert a Hicasso
  subtree explicitly with `h/as-element`.

| Retained by the enclosing `defview` | Not provided inside the native result |
| --- | --- |
| current frame and `h/sub` reads | Hiccup interpretation |
| props ABI, equality memo, and parent's key | event-vector-to-callback conversion |
| lifecycle, HMR behaviour, and Xray name | controlled-input repair and reserved markers |
| deterministic intrinsic server output | structural tree assertions and Hicasso key diagnostics inside the opaque element |

Keep form controls interpreted. A raw React `<input>` created with `n/$` does
not receive Hicasso's same-turn convergence, selection preservation, IME
protection, or `::h/revision` handling.

Hooks also remain illegal in a `defview` body. Hicasso bodies may branch and
loop dynamically, which is incompatible with hook order. A needed hook is a
signal to use a named native island.

## `n/$` grammar

```clojure
(n/$ head)
(n/$ head child*)
(n/$ head literal-props child*)
(n/$ head (n/props dynamic-props) child*)
```

Rules:

- An unqualified keyword names an intrinsic React element. A string names an
  intrinsic or custom element verbatim. Any other head expression must evaluate
  to a native React component.
- Only `nil`, a literal ClojureScript map, a literal `#js` object, or
  `(n/props expression)` is classified as the props operand. Every other
  trailing form is a child.
- Keys in a ClojureScript props map normalize to React slots: kebab-case to
  camelCase, `:class` to `className`, `:for` to `htmlFor`, with `data-*` and
  `aria-*` unchanged. String keys pass verbatim. A JS object is not renamed.
- Prop values pass by identity. There is no event-vector conversion, class
  collection join, style-map conversion, keyword value conversion,
  controlled-field repair, or deep conversion. Supply JS values explicitly
  where React or a library requires them.
- Children must already be valid ReactNode values. Collections are normally
  JavaScript arrays of keyed elements. `:children` in the props map is
  rejected because trailing forms are the one child channel.
- `:key` and `:ref` use ordinary React slots. Two source keys that normalize to
  the same slot, such as `:class` and `"className"`, are rejected rather than
  resolved by map order.

!!! warning "Mark dynamic props with `n/props`"
    The macro classifies props syntactically. A runtime map in second position
    is otherwise a child:

    ```clojure
    ;; Don't
    (let [cell-props {:class "px" :dir "ltr"}]
      (n/$ :td cell-props px))

    ;; Do
    (let [cell-props {:class "px" :dir "ltr"}]
      (n/$ :td (n/props cell-props) px))
    ```

    `n/props` adds no runtime wrapper. It only marks the operand. A CLJS map is
    converted shallowly under the slot-name rule; a JS object passes through.

## Named native islands

Use a top-level native component when the remaining owner is hooks, retained
vendor behaviour, React reconciliation, or high-rate mechanics. The example
below keeps drag motion in local React state and commits one domain fact to
app-db when the pointer is released:

```clojure
(ns app.watchlist.resizer
  (:require ["react" :as react]
            [re-frame.hicasso.native :as n]))

(n/defcomponent col-resizer
  [^js props]
  (let [col                (.-col props)
        {:keys [dispatch]} (n/use-frame)
        committed          (n/use-sub [:watchlist/col-width col])
        [live set-live]     (react/useState nil)]
    (n/$ :div
         {:class            "col-resizer"
          :role             "separator"
          :aria-orientation "vertical"
          :on-pointer-down
          (fn [e]
            (.setPointerCapture (.-currentTarget e)
                                (.-pointerId e))
            (set-live committed))
          :on-pointer-move
          (fn [e]
            (set-live
             (fn [width]
               (some-> width (+ (.-movementX e))))))
          :on-pointer-up
          (fn [_]
            (when live
              (dispatch [:watchlist/set-col-width col live]))
            (set-live nil))
          :on-lost-pointer-capture
          (fn [_]
            (set-live nil))}
         (when live
           (n/$ :div
                {:class "col-resizer__guide"
                 :style #js {:transform
                             (str "translateX("
                                  (- live committed)
                                  "px)")}})))))
```

The component ABI is one raw JS props object; children are at `.-children`.
An optional declaration map before the argument vector chooses
`{:server :render}` or `{:server :client-only}`. Omission defaults to
Client-only, which is appropriate for a pointer-only widget.

React hooks use their normal rules. `n/use-sub` is a hook and must be called
unconditionally at the top level. `n/use-frame` returns
`{:frame :dispatch :dispatch-sync :subscribe}` for the current frame and is
reference-stable during one frame incarnation.

A frame keyword is only an address. Destroying a frame and recreating it under
the same id creates a new incarnation. A bundle retained across that change is
silently inert; obtain frame operations from the live island rather than a
global stash.

High-rate pointer updates remain local to the island. Dispatch only the fact
that the rest of the application needs — the final width.

| Read API | Legal context | Rule |
| --- | --- | --- |
| `h/sub` | synchronous Hicasso view body | ordinary function call; branches, loops, and helpers are legal |
| `n/use-sub` | native React component | React hook; top-level and unconditional |

## Mount an island

Declare a native component as a host when interpreted Hiccup needs to render
it:

```clojure
(h/defhost resize-handle col-resizer)

[:th {:class "px"}
 "Price"
 [resize-handle {:col :px}]]
```

Native code renders it directly:

```clojure
(n/$ col-resizer {:col :px})
```

Both stay under the existing root and frame. Repeated crossings should receive
a named declaration for tooling and tests; the raw escape is for migration and
one-off dynamic cases.

An intrinsic-headed `n/$` result can render deterministically on the server.
A component-headed island defaults to Client-only and emits its declared
fallback, or nothing, until explicitly marked Render and verified for
hydration.

??? info "Using UIx"
    A rung-3 `defview` may return a UIx element, and a rung-4 island may be a
    `defui`. `n/use-sub` and `n/use-frame` work inside UIx components. The
    native namespace does not import UIx and is intentionally not a full
    component framework. Use UIx when a native region becomes substantial
    React-first product code.

## Preserve the native marker

`n/defcomponent` records a display name, native-tier marker, and server policy.
Xray and embedding helpers use that marker. Raw React wrappers remove it:

```clojure
;; Don't — React behaviour works, but Hicasso loses the marker
(def quote-cell
  (react/memo quote-cell*))

;; Do
(def quote-cell
  (n/memo quote-cell*))
```

Use `n/memo` and `n/lazy` so the props/children ABI and marker survive
memoization or code-split loading. Refs need no helper; function components
receive the React ref slot through props.

Hot reload reallocates a native component. React sees a new element type and
remounts that subtree. Local hook state resets on save by design. State that
must survive code reload belongs in app-db and returns through `n/use-sub`.

## Native screens

A canvas editor, diagramming surface, or vendor-grid screen may be React-shaped
from its first useful design. Implement that screen natively under the same
adapter, root, and frames. This changes only the view implementation for that
screen; it does not justify a second state owner or independent React root.

An independent root is an isolation decision, not a performance optimisation.

## Verify every crossing

The performance chapter owns the working method: reproduce, attribute with
Xray, tune topology first, compare an escape, and retain it only if it meets
the benefit rule.

After crossing, rerun the contracts that Hicasso can no longer inspect inside
the native subtree:

- DOM and interaction parity
- focus and selection
- frame routing
- SSR and hydration
- cleanup and StrictMode behaviour
- the original performance script

Xray can name and time the native boundary and show `n/use-sub` reads, while
correctly labelling the inner React tree opaque. It does not silently pretend
to inspect native descendants.

## When not to go native

Do not cross without a reproducible interaction and measured owner. Read
placement, unstable props, excessive event volume, and uncontrolled DOM size
must be fixed at the Hicasso level first.

Native construction does not solve controlled-input latency, and native inputs
lose the Hicasso controlled-field contract. Keep those fields interpreted.

Do not create islands merely for stylistic consistency. A few named escapes
are a boundary; islands throughout the application are a change of view-layer
strategy.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A CLJS map is rejected as a React child | Dynamic map was not classified as props | Wrap the expression with `(n/props m)` |
| A vector child is rejected inside `n/$` | Hiccup is not interpreted past the native fence | Convert with `h/as-element` or keep the subtree interpreted |
| An event vector in a native prop is rejected | Native props require functions | Use `h/event` in a rung-3 body or dispatch from `n/use-frame` in an island |
| `:children` in the props map is rejected | Native grammar has one trailing child channel | Move children after the props operand |
| Two prop keys are rejected as a slot collision | Both normalize to one React slot | Keep one canonical spelling |
| `n/use-sub` or `n/use-frame` raises `:rf.error/no-frame-context` | Component mounted outside a Hicasso frame provider | Mount under the application root or use the test kit's provider |
| Xray shows an anonymous native view | Raw `react/memo` or `React.lazy` removed the marker | Use `n/memo` or `n/lazy` |
| Local island state resets after each code save | Hot reload allocates a new component and React remounts it | Expected; move persistent state to app-db |
| Native rewrite does not improve the measurement | Construction was not the actual cost owner | Remove the escape and return to topology/event attribution |
| A controlled field loses caret or composition behaviour | It was moved behind the native fence | Keep the field interpreted or implement the full React contract yourself |
