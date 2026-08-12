# Accessibility

Start with semantic HTML. Native elements already provide names, roles,
keyboard activation, focus behaviour, and platform integration. Hicasso does
not replace those contracts with an accessibility subsystem.

Then derive ARIA state from the same application values that control the UI,
and test the result at the lowest level that can prove it.

## Use semantic elements

A button is focusable, keyboard-activatable, and announced as a button. A
clickable `div` is not.

```clojure
;; Don't: only a pointer user can reliably operate this.
[:div.delete
 {:on-click [:article/delete id]}
 "Delete"]

;; Do: focus, Enter, Space, and the role come from the platform.
[:button.delete
 {:on-click [:article/delete id]}
 "Delete"]
```

Apply the same rule throughout the page:

- use `:a` for navigation; `route-link` returns a real anchor;
- pair labels and controls with a wrapping `:label` or matching `:for` and
  `:id`;
- use `:ul`/`:li` for lists and `:table` for tabular data;
- keep a meaningful heading outline, normally with one page `:h1`;
- use `:main`, `:nav`, and `:aside` landmarks.

Use ARIA roles when no native element expresses the widget, such as a listbox
or tab strip. In that case, implement the complete keyboard and state contract,
not only the role. The dropdown in
[Overlays and focus](13-overlays-and-focus.md) is an example.

## Give controls an accessible name

Visible text usually supplies the name automatically. Add an explicit name for
icon-only controls or when the visible label lives elsewhere:

```clojure
[:button
 {:aria-label "Close"
  :on-click [:dialog/dismissed id]}
 "×"]

[:fieldset.form-group
 [:label {:for (str "email-" id)}
  "Email"]
 [:input
  {:id       (str "email-" id)
   :type     :email
   :value    (h/sub [:signup/email id])
   :on-input [:signup/set-email id ::h/value]}]]
```

IDs used by `:for`, `:aria-labelledby`, `:aria-describedby`, and
`:aria-activedescendant` must be unique per instance. Build them from the same
stable instance key used for application state
([Ephemeral state](11-ephemeral-state.md#choose-a-stable-instance-address)).

`:aria-*` and `:data-*` attributes pass through as written on native elements
and declared hosts.

## Derive ARIA state from the real state

Do not keep a second accessibility-only copy of expanded, selected, busy, or
invalid state. Read the fact once and use it for both behaviour and ARIA:

```clojure
(h/defview filter-toggle [{:keys [id]}]
  (let [open? (h/sub [:filter-menu/open? id])]
    [:button
     {:aria-haspopup "menu"
      :aria-expanded open?
      :on-click [:filter-menu/toggled id]}
     "Filter"]))
```

The same pattern appears elsewhere in the guide:

- `:aria-expanded` and `:aria-activedescendant` on the dropdown;
- `:aria-current "page"` on the active route link;
- `:aria-busy` on a resource-backed suggestion list;
- `:inert` and `:aria-hidden` on an exiting presence node.

Focus is different: it remains browser state. Express one focus action when
needed and let the browser or overlay contract restore it. Do not mirror the
currently focused element in app-db.

### Pair validation messages with fields

```clojure
(when-let [error
           (h/sub [:editor/field-error :title])]
  [:div.error-messages
   {:id "title-error"
    :role "alert"}
   error])
```

The corresponding input should carry:

```clojure
{:aria-invalid true
 :aria-describedby "title-error"}
```

Generate the message id per form instance when more than one form can appear on
the page.

## Keyboard and focus ownership

Native controls already own their keyboard bindings. Hicasso's keyboard map
expresses additional key-to-intent behaviour and suppresses mappings during IME
composition ([Events as data](03-events-as-data.md)).

Focus movement has specific owners:

| Moment | Behaviour | Owner |
| --- | --- | --- |
| Route change | Focus a keyed `main` region with `:tab-index -1` and `preventScroll` | [Routing and navigation](07-routing-and-navigation.md) |
| Overlay open and close | Apply `:auto-focus`, use the platform trap, restore the opener | [Overlays and focus](13-overlays-and-focus.md) |
| Menu or listbox navigation | Keep DOM focus on the trigger and move `:aria-activedescendant` | [Overlays and focus](13-overlays-and-focus.md) |
| Exit animation | Add `:inert` and `:aria-hidden` during unmounting | [Motion and presence](12-motion-and-presence.md) |
| Virtualised collection | Decide how keyboard users reach items that do not exist in the DOM and verify it in a browser | [Lists and collections](06-lists-and-collections.md) |

## Test attributes as data

Names, roles, and ARIA state are visible in the L2 semantic tree:

```clojure
(deftest toggle-announces-its-state
  (let [tree
        (ht/tree
         [views/filter-toggle {:id 7}]
         {:subs {[:filter-menu/open? 7] true}})
        btn
        (ht/attrs
         (ht/find tree #(= :button (:tag %))))]
    (is (= "menu" (:aria-haspopup btn)))
    (is (true? (:aria-expanded btn)))))

(deftest toggle-announces-its-state--sabotage-twin
  (let [tree
        (ht/tree
         [views/filter-toggle {:id 7}]
         {:subs {[:filter-menu/open? 7] false}})
        btn
        (ht/attrs
         (ht/find tree #(= :button (:tag %))))]
    (is (false? (:aria-expanded btn)))))
```

The sabotage twin proves that the assertion changes when the source state
changes.

Data tests cannot prove actual focus, Tab order, modal trapping, virtualised
keyboard movement, or screen-reader/browser integration. Test those behaviours
in real browser engines. Run an automated axe check on the mounted screen as a
baseline, then script the keyboard walk for important flows. Axe can identify
missing names and broken pairings; it cannot decide whether the traversal order
makes sense.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Control cannot be reached with Tab | A `div` or `span` is acting as a control | Use the real `button`, `a`, input, or other native control; add `tab-index` to a generic element only when no semantic element fits |
| Icon button is unnamed or announced only as “×” | Its glyph is the only name | Add `:aria-label` to the button |
| Input has the wrong or no label | `:for` and `:id` do not match, or ids are reused across instances | Generate stable per-instance ids |
| Screen reader's expanded state never changes | `:aria-expanded` is a literal or separate stale value | Derive it from the same subscription that controls the panel |
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
