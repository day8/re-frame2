# Accessibility

A screen reader announces "clickable, group" where you meant "Delete, button",
and Tab skips your busiest control. [Hicasso](glossary.md#hicasso) ships no accessibility subsystem
to fix that, because the fix is not a subsystem. Semantic Hiccup and native
controls carry names, roles, and keyboard behaviour on their own. Your job is
mostly to keep those behaviours in place — and then prove it in tests.

> **The platform does accessibility. Write the element that already means
> what you mean, name it with ordinary attributes, and test names and roles
> like any other data.**

## Semantic elements first

Semantic elements give you the platform's behaviour at no cost. A `:button`
is focusable, keyboard-activatable, and announced as a button. A `:div` with
a click handler is none of those things. Every attribute you then add by
hand is a copy of one platform behaviour, minus the ones you forgot:

```clojure
;; Don't — a click target only a mouse can find
[:div.delete {:on-click [:article/delete id]} "Delete"]

;; Do — focus, Enter, Space, and the announcement come with the tag
[:button.delete {:on-click [:article/delete id]} "Delete"]
```

The same trade repeats across the vocabulary:

- `:a` for navigation — [`route-link`](glossary.md#route-link) returns a real anchor, so middle-click
  and copy-link work.
- `:label` wrapping its control, or pointing at it with `:for`.
- `:ul`/`:li` for lists, and `:table` for tabular data.
- One `:h1`-per-page heading outline.
- `:main`/`:nav`/`:aside` landmarks, so a reader can jump between regions.

Use ARIA roles only where no element exists — a listbox, a tab strip. Then
take the whole contract, keyboard included, as
[the combobox in Overlays and focus](12-overlays-and-focus.md) does.

## Names are ordinary attributes

What an element shows visually is usually also its accessible name, with no
work from you. The gaps are icon-only controls, and controls whose visible
label lives elsewhere. The fix is data in the attribute map, per instance:

```clojure
[:button {:aria-label "Close" :on-click [:dialog/dismissed id]} "×"]

[:fieldset.form-group
 [:label {:for (str "email-" id)} "Email"]
 [:input {:id       (str "email-" id)
          :type     :email
          :value    (h/sub [:signup/email id])
          :on-input [:signup/set-email id ::h/value]}]]
```

Ids that pair elements — `:for`/`:id`, `:aria-labelledby`,
`:aria-describedby`, `:aria-activedescendant` — are per-instance strings.
Build them from the same instance key that addresses the state
([Ephemeral state](11-ephemeral-state.md#choosing-the-address)). Two
instances that share one id are the accessibility form of two panels that
share one address, and they break the same way.

`:aria-*` attributes pass through exactly as written
([Views and reads](02-views-and-reads.md)), so there is nothing
Hicasso-specific to learn — including on [`h/defhost`](glossary.md#defhost) crossings, where
`aria-*` stays hyphenated.

## State assistive tech reads is the state you already have

A screen reader's view of your widget — expanded, selected, busy, invalid —
must track app-db. The cheapest way to keep the two representations aligned
is to derive both from one read:

```clojure
(h/defview filter-toggle [{:keys [id]}]
  (let [open? (h/sub [:filter-menu/open? id])]
    [:button {:aria-haspopup "menu"
              :aria-expanded open?              ;; the same fact the panel renders from
              :on-click      [:filter-menu/toggled id]}
     "Filter"]))
```

The chapters that own each widget already write this shape:
`:aria-expanded` and `:aria-activedescendant` on
[the dropdown](12-overlays-and-focus.md), and `:aria-current "page"` on
[the active nav link](07-routing-and-navigation.md). `:aria-busy` sits on
[the fetching suggestion list](08-async-resources.md), and `:inert` plus
`:aria-hidden` ride [an exiting node](11-ephemeral-state.md). What those
chapters never do is mirror platform state back into app-db. Focus in
particular belongs to the platform
([Ephemeral state](11-ephemeral-state.md)): you express [intent](glossary.md#intent) once, and the
restore contracts do the rest.

Error display pairs the message to the field the same way — derived, per
instance, and announced:

```clojure
(when-let [error (h/sub [:editor/field-error :title])]
  [:div.error-messages {:id "title-error" :role "alert"} error])
;; and on the input: :aria-invalid true, :aria-describedby "title-error"
```

## Keyboard and focus have owners

Keyboard behaviour is data: the key map, with IME composition handled
centrally ([Events as data](03-events-as-data.md)). Native controls bring
their bindings with them. What is left is focus *movement*. Each case
already has its owner page, and this table is the inventory:

| Moment | Conduct | Owner |
|---|---|---|
| Route change | Focus the keyed main region, `:tab-index -1`, `preventScroll` | [Routing and navigation](07-routing-and-navigation.md) |
| Overlay opens / closes | `:auto-focus` [intent](glossary.md#intent) in; platform trap; restore on close | [Overlays and focus](12-overlays-and-focus.md) |
| Composite widget (menu, listbox) | Focus stays on the trigger; `:aria-activedescendant` walks options | [Overlays and focus](12-overlays-and-focus.md) |
| Exit animation | `:inert` + `:aria-hidden` ride the unmounting phase | [Ephemeral state](11-ephemeral-state.md) |
| Virtualized list | The keyboard cannot reach rows that do not exist — decide, then verify in a browser | [Lists and collections](06-lists-and-collections.md) |

## Prove it

Names and roles are attributes in the semantic tree, so most accessibility
claims are L2 data assertions. The harness, the fixtures, and the sabotage
discipline are the same as everything else in [Testing](14-testing.md):

```clojure
(deftest toggle-announces-its-state
  (let [tree (ht/tree [views/filter-toggle {:id 7}]
                      {:subs {[:filter-menu/open? 7] true}})
        btn  (ht/attrs (ht/find tree #(= :button (:tag %))))]
    (is (= "menu" (:aria-haspopup btn)))
    (is (true? (:aria-expanded btn)))))

(deftest toggle-announces-its-state--sabotage-twin
  (let [tree (ht/tree [views/filter-toggle {:id 7}]
                      {:subs {[:filter-menu/open? 7] false}})]
    (is (false? (:aria-expanded (ht/attrs (ht/find tree #(= :button (:tag %)))))))))
```

Data cannot prove focus and traversal: where focus lands after a route
change, whether Tab is trapped in a [modal](glossary.md#overlay), whether a virtualized grid is
walkable. Those are engine facts, so they live in the browser tier, next to
an automated axe pass over each screen's mounted state. The axe run is a
floor, not the test. It catches missing names and broken pairings, and it
says nothing about whether the keyboard *order* makes sense. Script that
walk yourself for the screens where it matters.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| A control cannot be reached with Tab | It is not a real control — a `:div`/`:span` with a click handler | Use `:button`, `:a`, or the real control; `:tab-index` on a div is the last resort, not the first |
| An icon button announces nothing, or announces "×" | No accessible name beyond its glyph | `:aria-label` on the control |
| A reader announces the wrong label, or none, for an input | The `:label` is not paired — missing `:for`, or an id shared between instances | Per-instance ids built from the instance key |
| "Expanded" never changes in the reader while the panel opens | The `:aria-expanded` value is a literal, not the sub the panel renders from | Derive it from the same read |
| Validation errors appear silently | The message div is not announced and not paired | `:role "alert"` on the message; `:aria-invalid` and `:aria-describedby` on the field |
| Focus goes nowhere after navigating | The main region is not keyed and focusable | The route-focus recipe ([Routing and navigation](07-routing-and-navigation.md)) |
| A fading toast still takes focus and clicks | The exit override lacks the a11y pair | `:inert` and `:aria-hidden` in `::motion/unmounting` ([Ephemeral state](11-ephemeral-state.md)) |
| The [modal](glossary.md#overlay) traps focus but the page behind still scrolls | A hand-written dialog, not the native modal path | `overlay/modal` ([Overlays and focus](12-overlays-and-focus.md)) |
| axe flags nothing but keyboard users are lost | axe checks attributes, not traversal order | Script the Tab walk in the browser tier for that screen |

## When not to add more

- **Do not restate what the element already says.** `{:role "button"}` on a
  `:button` and `{:aria-label "Save"}` beside visible "Save" text are noise
  at best and contradictions at worst. ARIA fills gaps, and the first rule
  of ARIA is to prefer the element.
- **Do not mirror focus or hover into app-db** to drive announcements. The
  platform tracks them, and a mirror drifts
  ([Ephemeral state](11-ephemeral-state.md)).
- **Do not build an announcer service.** A `:role "alert"` region rendered
  from state covers the occasional live announcement. A general live-region
  subsystem is machinery built before any caller needs it.
