# Accessibility

Freehand does not ship a second accessibility framework. It makes a11y **easier
to do correctly** because markup, handlers, and structure are data — and it
refuses diagnostics that pretend to know more than they can prove.

The product bar is **native-element-first**: prefer platform primitives that
already carry roles, focus, and keyboard behaviour; assert what the structural
tree can prove; use a real browser for composite widgets and foreign interiors.

## Principles

| Principle | Practice |
|---|---|
| **Native element first** | `<button>`, `<a href>`, `<input>`, `<dialog>`, `popover` — before ARIA on a `div` |
| **Accessible name** | every interactive control has a name (text content, `aria-label`, labelled control, …) |
| **Owned relations stay owned** | library leaves may lock required roles / ARIA; callers cannot strip them via `:parts` |
| **Provable-only static findings** | analyzer and structural walks report only what they can prove; unknown is not “pass” |
| **Browser owns composite proof** | focus traps, roving tabindex, live regions, foreign widget interiors |

Freehand’s contribution is the **data tree** and the **host contracts** (top layer,
presence overrides, controlled inputs). Your component design still chooses
semantics.

## Prefer the platform

| UI need | Prefer | Avoid as first move |
|---|---|---|
| Button | `:button` or `:input {:type "submit"}` | clickable `:div` + role |
| Navigation | real `:a` with `href` (route-link laws) | `div` + click only |
| Modal | top-layer / native dialog semantics | hand-rolled focus trap as a first cut |
| Anchored popover | native popover / top-layer desired state | portal soup without focus policy |
| Expand/collapse | `aria-expanded` on a real button + controlled region | mystery disclosure without name |
| Form field | labelled control (`:label` wrapping or `aria-labelledby`) | placeholder-as-only-label |

Overlays, measurement, top-layer open state, and presence exit classes are defined
on their host and presence pages. This page only states the **a11y obligations** those
features create.

## Names, roles, and keyboard

### Accessible name

In structural tests and dev sweeps, treat “interactive node without a name” as a
finding when it is **provable**:

```clojure
;; bad — icon-only, no name
[:button {:on-click [:cart/open]} [icon-cart {}]]

;; good — visible text or aria-label
[:button {:on-click [:cart/open]
          :aria-label "Open cart"}
 [icon-cart {}]]
```

Event vectors do not replace names. Tools can see `[:cart/open]`; a screen reader
still needs a name.

### Roles and relations libraries own

A reusable control may require a role or labelling relation as part of its
contract. Those must not be overwritable by caller `:parts` maps. Libraries
merge parts through `v/spread-safe`, which **denies** stripping required roles /
a11y relations the component owns — see
[Composition — spreading props](composition.md#spreading-props-attribute-forwarding)
and [Composition — theming and parts](composition.md#theming-and-parts).
Structural replacement of a labelled region uses the
[composition ladder](composition.md), not a part override that deletes the label.

### Keyboard

- Prefer **native** activators (`button`, `a`, `input`) so Enter/Space and tab order
  work without a custom handler.  
- Keyboard branching is an **ordinary event**. Send the key with the intent —
  `[:menu/key-pressed ::v/key]` — and decide in the handler, or write
  `(v/event [e] …)` at the site when `preventDefault` depends on which key it was
  (see [Events](events-and-handlers.md#keyboard-branching)). Listbox and combobox
  policy usually needs more than either: reach for a host widget rather than fake
  a full ARIA composite out of a few key branches.  
- IME composition must not commit partial text on Enter; Freehand’s controlled-input
  door already treats composition as a browser law.

## Presence and exit

While a keyed child is retained as **`:unmounting`**, it can still take focus and
clicks unless you hide it from interaction and assistive tech:

The presence boundary stamps nothing on your markup, so this is the **child's**
job — it reads its own phase and hides itself:

```clojure
(v/defview toast-card [{:keys [toast]}]
  (let [exiting? (= :unmounting (v/presence-phase))]
    [:div.toast {:class       (when exiting? "toast--exit")
                 :inert       (when exiting? true)
                 :aria-hidden (when exiting? true)}
     (:message toast)]))
```

Development checks should warn when retained interactive content lacks `inert`
(or equivalent) and assistive hiding. Honour `prefers-reduced-motion` in CSS so
timeout still removes the node without motion.

Presence never owns focus traps or modal UX — only presentation overrides during
retention. Details: [Presence — accessibility](../host/presence.md#accessibility).

## Top layer and overlays

When open state is a **desired fact** (dialog/popover), the browser can supply
backdrop, Esc, and much of focus return. Your job:

| Obligation | Note |
|---|---|
| Initial focus | native dialog behaviour or an explicit focus intent (`:auto-focus` / focus fx) |
| Background | inert / non-interactable while modal |
| Return focus | on close, back to the control that opened |
| Label | dialog has an accessible name |
| Nested stacks | LIFO / native stacking — do not invent a second modal manager in app-db |

Do not reimplement portal + trap as neutral Freehand primitives. Use the top-layer
contract and, when React protocols demand it, a React component entered as a child.

## Forms

- Associate every control with a visible label (or a deliberate `aria-label`).  
- Surface validation in text the tree can assert — not colour alone.  
- `aria-invalid` / `aria-describedby` pair well with the form-slice
  [`:errors`](forms.md) map when you wire them as ordinary attrs.  
- Disabled submit is not a substitute for explaining why submit failed.

## Testing: two lanes

Accessibility proof is intentionally split so tools do not overclaim.

### Lane 1 — static / structural (provable only)

| Can assert | How |
|---|---|
| Missing accessible name on a **static** interactive element | structural tree walk / compile-time finding |
| Required role still present after `:parts` merge | unit test on attrs |
| Event intent still present after composition | equality on the tree |
| Presence plan includes `inert` / `aria-hidden` on exit | presence metadata on the structural tree |

**Cannot** honestly claim from static analysis alone:

- dynamic names built only at runtime from opaque data  
- interiors of foreign host widgets  
- real focus order through a composite  
- live region politeness in a real AT  

Those stay **unknown** in static reports — not “green.”

### Lane 2 — complete tree / mounted browser

| Need | Tier |
|---|---|
| Roles and names after full composition | complete structural tree or DOM queries |
| Keyboard and focus containment/return | mounted browser |
| Top-layer nesting and background inertness | mounted browser |
| Third-party (Radix, grid) keyboard | real primitive + disconnect cleanup |

See [Testing](../operate/testing.md). Presence exit motion may use a fake clock for
retention timing and a real browser for CSS/a11y.

## Diagnostics product rule

> Static accessibility findings report **only** facts the analyzer or complete
> structural tree can prove. Dynamic content and opaque foreign interiors are
> marked unknown, not guessed.

That rule is a fitness-harness obligation, not a suggestion. A CI check that
“passes a11y” by assuming every host leaf is fine is a product defect.

## Checklist

1. Prefer **native** elements and top-layer primitives over ARIA-on-div.  
2. Give every interactive control an **accessible name**.  
3. Keep library **roles and labels** non-strippable via
   [`spread-safe`](composition.md#spreading-props-attribute-forwarding) / `:parts`.  
4. Put **`inert` + `aria-hidden`** on presence exit overrides for interactive trees.  
5. Wire form errors as **text in the tree**, not only style.  
6. Assert **provable** facts in structural tests; mount for focus and composites.  
7. Never treat static “unknown” as pass.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Interactive control with no name | label, `aria-label`, or labelled-by; structural tree can assert names |
| Modal without focus management | prefer native `<dialog>` / top-layer; do not invent a trap first |
| Enter commits mid-IME | guard key handlers with `isComposing` — see [Events](events-and-handlers.md) |

