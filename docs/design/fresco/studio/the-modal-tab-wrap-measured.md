# The modal's Tab wrap, measured — what the engine's trap leaves open and what a candidate set costs

**Beads** `rf2-hic-052`, `rf2-5lzq` · **epic** `rf2-6c12m` (this page, `rf2-6c12m.25`)
**Runtime** Chromium as driven by the browser lane (`playwright` 1.59.1 in
`implementation/package.json`), headless and headed alike where the row says
so; the readings were taken by hand in that browser when each repair landed and
are pinned by the rows named beside them.
**Reproduction** `npm run test:browser` from `implementation/` — the
`:browser-test` build selects every `*-dom-cljs-test` namespace, and the rows
are in
`implementation/hicasso/test/re_frame/hicasso/overlay_focus_dom_cljs_test.cljs`.
Each row below names the `deftest` that pins it.

This page is the measured record behind `re-frame.hicasso.impl.overlay`'s
`wrap-tab!` and the four predicates that feed it. It was carried in that
namespace's docstrings until `rf2-6c12m.4` ruled measurement narrative out of
source; the mechanism it explains — and the one-sentence invariant each
predicate protects — stays beside the code.

## The engine's trap has one gap, and it is the wrap

`showModal` makes the rest of the document inert, so Tab cannot reach a control
behind the panel; that half is the engine's. But the wrap off the panel's last
control goes through the document's own end-of-scope step, and for one press
`document.activeElement` is `<body>`: focus rests nowhere, the focus ring
vanishes, and in a browser with UI that step is the browser UI rather than the
page. Pressed over a three-control modal starting at the first control:

    Tab       → reason, cancel, BODY, confirm, reason …
    Shift+Tab → BODY, cancel, reason, confirm …

Four stops for three controls, and headed Chromium did not even do it
consistently — the second backward lap dropped the waypoint. A modal is bought
for *focus cannot Tab outside it*, which the guide promises, so the two edges
are closed by a handler that tracks no state, holds no listener while idle, and
runs for no key but Tab. Landed on main as
`5d748604423ca58aa11c41fe5f09bea3ff526024`; pinned by
`a-real-tab-cycles-within-the-modal-and-a-real-escape-gives-the-page-back`.

The wrap confirms its landing before it takes the default action away:
`HTMLElement.focus()` on an element that cannot be focused is a no-op that
leaves `activeElement` where it was, so the move is attempted first and
`preventDefault` follows only if it took. That is a floor and not a licence —
it saves a wrap aimed at nothing, and cannot save one aimed at the wrong
control, which is the whole of the next section.

## First and last are sequential order, not document order

A named radio group is one tab stop and not one per button — the checked
member, or the first focusable member when none is checked. Measured over a
modal holding `[unchecked r1, checked r2, ok]`: Tab visits `r2` and `ok` and
never `r1`. Taking document order for sequential order therefore sends Tab from
the modal's last control onto an unchecked radio, and Shift+Tab from its checked
one onto `<body>`. A positive `tabindex` likewise sorts ahead of every
`tabindex=0`, ascending, with document order inside each bucket. Both halves of
the grouping rule were measured rather than read: two same-named groups inside
two `<form>`s keep one stop each, and two unnamed radios side by side are two
stops. The two degenerate cases fall out of choosing over the already-filtered
candidates rather than needing rules of their own — a checked-but-disabled
member is gone before the vote, so the first surviving member takes the slot,
and a group whose members are all disabled contributes no stop. Landed on main
as `ed2311cb583a38f7ddb1cf3bbea346ce711d60d5`; pinned by
`a-real-tab-wraps-at-the-radio-groups-edge-and-not-at-the-dom-s` and
`a-real-tab-wraps-at-the-tabindex-ordered-edge`.

## A surplus candidate is not free — the four effective non-stops

There are four ways an element can be a candidate a `querySelectorAll` scan
finds and a stop the engine never visits: `visibility:hidden`, the contents of a
closed `<details>`, an `inert` subtree, and a `disabled` `<fieldset>`. Measured
with `.focus()` on each: all four refuse focus, while an unchecked radio in a
checked group *accepts* it — which is why the radio group is a wrong landing and
these four are a missed wrap.

A missed wrap is not free at the last position. There the surplus is what
`peek` returns, so Tab off the real last control matches no edge, the handler
declines, and the engine's own end-of-scope step puts `activeElement` on
`<body>` — the leak the handler exists to close, reached by a fourth route.
Shift+Tab off the first stop fails from the other end: the wrap aims at the
surplus, `.focus()` declines, `preventDefault` is correctly withheld, and
withholding it is what lets focus out. So *refuses focus* and *costs nothing*
are different claims, and only the first holds. A trailing non-stop is ordinary
markup: a wizard step the user has not reached is an `inert` region and a form
section a prior answer has not unlocked is a `disabled` `<fieldset>`, and
either is naturally written after the buttons that lead to it.

How each is excluded, and the reading that decided the spelling:

| Non-stop | Excluded by | Measured |
|---|---|---|
| `visibility:hidden`; closed `<details>` contents | `checkVisibility({visibilityProperty: true})` | `getClientRects()` reports `rects: 1` for both, so a rect test counts them; both refuse focus |
| `content-visibility:auto` subtree currently skipped | **not** excluded — `contentVisibilityAuto` is left off | takes focus (the engine renders it on the way in), while the option answers false for it; passing it would drop a real stop |
| `opacity:0` | **not** excluded — `opacityProperty` is left off | focusable |
| `<fieldset disabled>` contents | `:disabled` the pseudo-class | the `.disabled` IDL property reflects the element's own attribute and reads `false` for a control inside a disabled fieldset; the pseudo-class is the effective state, and controls in the fieldset's first `<legend>` stay enabled and stay stops |
| `inert` subtree | `closest("[inert]")` | `matches(':inert')` throws `SyntaxError`; the attribute goes on the region and every control under it stops being a stop |

A candidate set too small aims the wrap at the wrong control, which is the
worse failure; a set too large lets focus out. The rect test stays as the
fallback for an engine without `checkVisibility`. Landed on main as
`47a3f84a610e7e16a2eb0968510bf31ca85acd84`; pinned by
`a-real-tab-wraps-past-a-trailing-hidden-control`,
`a-real-tab-wraps-past-a-trailing-inert-region` and
`a-real-tab-wraps-past-a-trailing-disabled-fieldset`.

## What the model is not

It is not the platform's focus algorithm: shadow roots, `delegatesFocus` and
scrollable-overflow focusability are outside the set, and a control the engine
skips for a reason not listed above is still counted. A group whose checked
member sits outside the panel needs no rule — everything outside the panel is
inert under `showModal`, so the engine falls to the first focusable member
inside, which is what scanning only the panel already computes; measured. A
press a control inside the panel has already claimed is left alone, read off
the native event's `defaultPrevented` rather than the synthetic event's copy,
which React takes at construction (pinned by
`a-real-tab-a-panel-control-has-claimed-does-not-wrap`). The popover carries no
handler at all, because a popover is deliberately not a trap
(`a-real-tab-leaves-an-open-popover`).
