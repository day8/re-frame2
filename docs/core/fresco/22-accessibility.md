# Accessibility

Fresco has no accessibility layer of its own. Start with semantic HTML, which
already provides names, roles, keyboard activation, and focus behaviour. Then
derive ARIA state from the same subscriptions that drive the UI, and test it.

## Use semantic elements

A button is focusable, keyboard-activatable, and announced as a button. A
clickable `div` is not.

```clojure
;; Don't do this: only a pointer user can reliably operate it.
[:div.delete {:on-click [:todo/delete id]} "Delete"]

;; Do this: focus, Enter, Space, and the role come from the browser.
[:button.delete {:on-click [:todo/delete id]} "Delete"]
```

The same applies across the page:

- use `:a` for navigation; `h/route-link` returns a real anchor;
- pair labels and controls with a wrapping `:label` or matching `:for` and
  `:id`;
- use `:ul`/`:li` for lists and `:table` for tabular data;
- keep a meaningful heading outline, normally with one page `:h1`;
- use `:main`, `:nav`, and `:aside` landmarks.

Use ARIA roles when no native element fits the widget, such as a listbox or a
tab strip, and then implement its full keyboard and state behaviour as well as
the role. The dropdown in
[Overlays and focus](13-overlays-and-focus.md) is an example.

## Give controls an accessible name

Visible text usually supplies the name automatically. Add an explicit name for
icon-only controls or when the visible label lives elsewhere:

```clojure
[:button
 {:aria-label "Delete"
  :on-click   [:todo/delete id]}
 "×"]

[:div
 [:label {:for (str "todo-title-" id)} "Title"]
 [:input
  {:id       (str "todo-title-" id)
   :value    (:title (h/sub [:todo/by-id id]))
   :on-input [:todo/rename id ::h/value]}]]
```

Ids used by `:for`, `:aria-labelledby`, `:aria-describedby`, and
`:aria-activedescendant` must be unique on the page. Build them from the same
stable key used for the application state, here the todo id
([Ephemeral state](11-ephemeral-state.md#choose-a-stable-instance-address)).

`:aria-*` and `:data-*` attributes pass through as written on native elements
and declared hosts.

## Derive ARIA state from the real state

Do not keep a separate accessibility copy of expanded, selected, busy, or
invalid state. Read the value once and use it for both behaviour and ARIA:

```clojure
(h/defview filter-toggle [_]
  (let [open? (h/sub [:todo.ui/filter-menu-open?])]
    [:button
     {:aria-haspopup "menu"
      :aria-expanded open?
      :on-click      [:todo.ui/toggle-filter-menu]}
     "Show"]))
```

Other pages use the same pattern:

- the dropdown in [Overlays and focus](13-overlays-and-focus.md) sets
  `:aria-expanded`, `:aria-activedescendant` (the keyboard's current
  position), and `:aria-selected` (the chosen value);
- the active route link sets `:aria-current "page"`;
- a resource-backed suggestion list sets `:aria-busy`;
- an exiting presence node sets `:inert` and `:aria-hidden`.

Focus is the exception: it stays browser state. Move focus when you need to
and let the browser or overlay restore it. Do not copy the focused element
into app-db.

### Pair validation messages with fields

```clojure
(h/defview title-field [{:keys [id]}]
  (let [error    (h/sub [:todo.ui/title-error id])
        error-id (str "todo-title-error-" id)]
    [:div
     [:input
      {:value            (:title (h/sub [:todo/by-id id]))
       :on-input         [:todo/rename id ::h/value]
       :aria-invalid     (some? error)
       :aria-describedby (when error error-id)}]
     (when error
       [:p {:id error-id :role "alert"} error])]))
```

`:role "alert"` announces the message when it appears, and
`:aria-describedby` links it to the field.

## Keyboard and focus ownership

Native controls already handle their own keys. For additional keys, put a
[keyboard map](03-events-as-data.md#keyboard-maps) at `:on-key-down` or
`:on-key-up`; Fresco does not dispatch from it during IME composition.

Where focus moves, and which page covers it:

| Moment | Behaviour | Covered in |
| --- | --- | --- |
| Route change | Focus a keyed `main` region with `:tab-index -1` and `preventScroll` | [Routing and navigation](07-routing-and-navigation.md) |
| Overlay open and close | Put the control that should receive focus first in tree order, use the platform trap, restore the opener | [Overlays and focus](13-overlays-and-focus.md) |
| Menu or listbox navigation | Keep DOM focus on the trigger and move `:aria-activedescendant` from a `combobox` that `:aria-controls` the list, leaving `:aria-selected` on the committed value | [Overlays and focus](13-overlays-and-focus.md) |
| Exit animation | Add `:inert` and `:aria-hidden` during unmounting | [Motion and presence](12-motion-and-presence.md) |
| Virtualised collection | Decide how keyboard users reach items that do not exist in the DOM and verify it in a browser | [Lists and collections](06-lists-and-collections.md) |

## Test attributes as data

Names, roles, and ARIA state are ordinary attributes in the tree `ht/tree`
returns, so a plain unit test can check them:

```clojure
(ns app.views-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.fresco.test :as ht]
            [app.views :as views]))

(defn toggle-attrs [open?]
  (let [tree (ht/tree [views/filter-toggle {}]
                      {:subs {[:todo.ui/filter-menu-open?] open?}})]
    (ht/attrs (ht/find tree #(= :button (:tag %))))))

(deftest toggle-announces-its-state
  (is (= "menu" (:aria-haspopup (toggle-attrs true))))
  (is (true? (:aria-expanded (toggle-attrs true))))
  ;; the attribute follows the state
  (is (false? (:aria-expanded (toggle-attrs false)))))
```

Checking both states shows that the attribute really follows the
subscription.

Tests on data cannot check real focus, Tab order, modal trapping, keyboard
movement in virtualised lists, or screen-reader behaviour. Test those in real
browsers ([Testing](15-testing.md)). Run an automated axe check on the mounted
screen as a baseline, then script the keyboard walk for important flows. Axe
finds missing names and broken label pairings; it cannot tell whether the Tab
order makes sense.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Control cannot be reached with Tab | A `div` or `span` is acting as a control | Use a native `button`, `a`, or input; add `tab-index` to a generic element only when nothing native fits |
| Icon button is unnamed or announced only as “×” | Its glyph is the only name | Add `:aria-label` to the button |
| Input has the wrong or no label | `:for` and `:id` do not match, or ids are reused across instances | Generate stable per-instance ids |
| Screen reader's expanded state never changes | `:aria-expanded` is a literal or a separate stale value | Derive it from the same subscription that controls the panel |
| Validation message appears without announcement | The message is not a live alert and the field is not linked to it | Add `:role "alert"`, `:aria-invalid`, and `:aria-describedby` |
| Focus does not move after route navigation | The new main region is not keyed or programmatically focusable | Use the route focus recipe |
| Fading item still accepts focus and clicks | Exit appearance changed without disabling interaction | Add `:inert` and `:aria-hidden` in the unmounting override |
| Modal traps focus but the background remains interactive | A non-modal or hand-written dialog is being used | Use `overlay/modal`, which calls `showModal` |
| Axe passes but keyboard users still get lost | Automated rules cannot evaluate the intended traversal order | Script the Tab, arrow-key, Escape, and focus-return flows in browser tests |

## When not to add ARIA or state

- Do not repeat native semantics. `role="button"` on a `button`, or an
  `aria-label` that contradicts visible text, makes the result worse.
- Do not mirror hover or focus into app-db solely for announcements. The
  browser already owns those facts.
- Do not build a general announcer service before a real use case requires it.
  A state-driven `role="alert"` region covers occasional live messages.
