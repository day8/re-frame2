# Theming

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

Every React component library reaches for context to theme itself. Hicasso doesn't
ship a context API, and theming doesn't need one.

Context is a side channel: invisible to the data tree (so headless tests can't see
it), one hook per consumer, a full re-render of every consumer on change, and a
second dependency-injection channel next to props. CSS cascade and the data tree
each do the job better. Real apps already live on a global theme plus per-instance
overrides — that pattern never needed subtree context.

> **Tokens in CSS, part maps as data, the *choice* of theme in app-db.**

Three layers.

## Layer 1 — design tokens as CSS custom properties

The cascade already is a context system. Nearest ancestor wins, it is
subtree-scoped, and it is platform-native.

```css
:root {
  --app-color-accent:  #2b6cb0;
  --app-color-surface: #ffffff;
  --app-radius:        4px;
}

[data-theme="dark"] {
  --app-color-accent:  #63b3ed;
  --app-color-surface: #1a202c;
}

.app-button {
  background: var(--app-color-accent);
  border-radius: var(--app-radius);
}
```

Nothing about the `--app-*` prefix is Hicasso's; name your custom properties
whatever your stylesheet already does.

Switching theme is one attribute flip on one element. **After the attribute
changes, restyling costs zero React work** — no re-render, no hook, no diff. The
cascade recomputes and the component tree is never consulted.

That claim is about *after* the flip. What puts the attribute on the DOM is layer
3's job — see [Bridging the choice to the DOM](#bridging-the-choice-to-the-dom).

## Layer 2 — parts as data addresses

A control emits keyword-tagged **parts**: named addresses for its internal pieces.
A theme is a map from part address to classes and attributes. Applying it is a
pure tree-to-tree transform.

```clojure
;; A theme: part address → classes/attrs.
(def compact-theme
  {[:typeahead :root]  {:class "ta ta--compact"}
   [:typeahead :input] {:class "ta__input"}
   [:typeahead :menu]  {:class "ta__menu" :role "listbox"}})
```

Merge order is fixed: **`base < app-theme < instance-props`**. The control's own
base wins least, the app's theme overrides it, and props at a specific use site
override both.

Because the transform is pure, you can unit-test a theme with `=`. No registry, no
multimethods, no runtime resolution order.

How a control *declares* a part, and how an app *installs* a theme, are not settled
yet — see **Not settled yet**. The merge order and the pure-function shape are
fixed.

## Layer 3 — app-db for the choice

Which theme is selected is application state like any other: read it with a
subscription, write it with an event. Frame isolation, time travel, and Xray
visibility come free.

```clojure
(rf/reg-sub :theme/current
  (fn [db _query] (:theme/current db :light)))   ;; unset reads as :light

(rf/reg-event :theme/choose
  (fn [{:keys [db]} [_ theme]] {:db (assoc db :theme/current theme)}))
```

App-db holds the *choice*. It does not hold the tokens, and it does not hold the
part maps.

**The default belongs in the sub, not in each reader.** A fresh app-db has made no
choice, so a bare `(:theme/current db)` reads `nil`, and `nil` is then what every
bridge below has to survive — starting with `name`, which throws on it and
[takes the page down from a root view](09-when-a-view-throws.md). One extra
argument makes the read total and states the fallback once. `:light` needs no rule
of its own: `:root` in layer 1 *is* the light theme, so a `[data-theme="light"]`
that matches nothing leaves it in force.

An app that restores a remembered choice dispatches `:theme/choose` from
[`:initial-events`](01-getting-started.md), which lands before first paint — so the
default paints only when nobody has chosen, never as a flash in front of a late
choice.

## Bridging the choice to the DOM

The event above moves a keyword into app-db. Layer 1's cascade keys off a
`data-theme` attribute on a DOM element. Something has to join them.

**A view renders the attribute. That is the default**, and the reason is the one
this guide leans on everywhere else: the DOM stays derived from app-db, so a
rewind in Xray, a restored snapshot and a test fixture that writes the theme all
agree with what is on screen. Option B below asserts the attribute imperatively
from an effect, and stays documented for apps that want literally zero render work
on a switch.

If all you want is *follow the OS*, you need no bridge at all — a
`@media (prefers-color-scheme: dark)` block in layer 1's stylesheet themes the
page with no app state, no subscription and no effect. Bridge the choice when the
**user** picks the theme, or when their pick has to survive a reload.

### Option A — a scope view reads the choice

One view reads `:theme/current` and puts it on its own element:

```clojure
(defview theme-scope [{:keys [children]}]
  (into [:div {:data-theme (name (sub [:theme/current]))}]
        children))

;; At the root:
[theme-scope {} [app {}]]
```

`children` arrives as a realized *vector* of hiccup forms, which is why it is
spliced with `into` rather than dropped in as one child —
[Views and reads](02-views-and-reads.md#the-component-abi) has the rule.

**Where the attribute lands.** A view renders its own element and nothing above
it, so `data-theme` goes on `theme-scope`'s own `div`: per frame, never per
document. That is not a second decision you make after picking A — it is the only
place the attribute can go, and it happens to be what frame isolation needs. Two
frames on one page, one light and one dark, is an ordinary story page and an
unwritable one against a single `documentElement`.

Keep the scope at the frame's root, above any [`defhost`](05-interop.md) crossing.
Under the default `:ssr :client-only` policy — and under `{:fallback …}` — a
crossing renders *instead of* its subtree, so a scope beneath one goes missing
from the server response along with everything it was scoping, and nothing reports
it ([Server-side rendering](10-server-side-rendering.md#render--when-the-region-has-to-be-in-the-response)).

Per-root does not strand the top layer. `::backdrop` inherits from its originating
element on Chromium 122, Firefox 120 and Safari 17.4 and later, so a dialog opened
inside the scope takes the scope's tokens; on engines older than those it
inherited from nothing, which a document-level attribute did not fix either.

**Tradeoff.** The boundary that reads the theme re-renders on every switch — one
boundary, known cost. What happens below it depends on *what sits there*. The
[value-equality bail-out](02-views-and-reads.md#boundaries-memoize-by-default) is
carried by heads `defview` mints, so `[app {}]` bails because `app` is a `defview`
and its props are unaffected by the theme. A native-tag subtree, a fragment, or a
`defhost` crossing carries no wrapper and re-runs with the scope — and so does
`app` called as a plain function, which mints no boundary at all. Keep a `defview`
head immediately below the scope and the rest of the tree stays quiet.

Element identity does not help here: children cross a boundary as hiccup data, not
as React elements, so `theme-scope` mints a fresh element for `app` on every render.
The skip is always the comparator's `=`, never a referential short-circuit — and
`=` on an unchanged props map is cheap.

That quiet subtree rides the bail-out being the **default**, which guide 02 still
lists as unsettled. If it ever becomes something you ask for, the arithmetic does
not move: one boundary asks, and the tree below it is quiet again.

**Wins.** The DOM is derived from state. Rewind app-db in Xray and the attribute
follows; tests and restored snapshots stay consistent. The server render is the
same view reading the same snapshot, so the response carries the attribute and
hydration adopts it — **as long as the payload carries the choice**. If it does
not, the two sides disagree about one attribute, and an attribute-only divergence
is the one class React never reports
([Server-side rendering](10-server-side-rendering.md#troubleshooting)): the page
hydrates in the server's theme and stays there until that boundary next re-renders.
B's cost is a flash you can watch happen; A's residual cost is a wrong attribute
nobody tells you about.

**Page chrome.** The document scrollbar, the `<body>` canvas and
`<meta name="theme-color">` sit above every frame, so a per-root attribute does not
reach them. Echo the same db fact document-level, outside Hicasso's contract — on
the client, that is one app-owned effect:

```clojure
(rf/reg-fx :page/echo-theme!                    ;; a projection, not the bridge
  {:platforms #{:client}}
  (fn [_ctx theme]
    (.setAttribute (.-documentElement js/document)
                   "data-page-theme" (name theme))))
```

`:theme/choose` returns it as a row under `:fx` beside its `:db` write — option B
below shows that shape. On the server the same keyword rides `:html-attrs` in the
head model your route already declares, which is a pure function of app-db, so it
is there on the first byte. Give the document copy a **different attribute name**
than layer 1's scope selector, as above: reuse `data-theme` on `<html>` and the
cascade honours it as a real second carrier for every frame that has no scope of
its own.

One source, two projections, and deliberately asymmetric: the rendered root
attribute is the app's only theme carrier, the document copy is cosmetic, and a
stale copy costs you a scrollbar colour. Doubling the *carrier* — mixing A and B
over the same elements — is the incoherence worth avoiding.

### Option B — an effect writes the attribute

Nothing reads the theme in a view. The event that records the choice also returns
an effect, and the effect does the flip:

```clojure
(rf/reg-fx :theme/apply!
  {:platforms #{:client}}
  (fn [_ctx theme]
    (.setAttribute (.-documentElement js/document) "data-theme" (name theme))))

(rf/reg-event :theme/choose
  (fn [{:keys [db]} [_ theme]]
    {:db (assoc db :theme/current theme)
     :fx [[:theme/apply! theme]]}))
```

Custom effects are rows under `:fx`. The effect map is closed at the top level, so
a stray `:theme/apply!` beside `:db` is dropped with `:rf.error/effect-map-shape`
while the `:db` write still lands — which looks exactly like an unwired bridge.
`{:platforms #{:client}}` keeps the effect off the server, where there is no
`document`; [Server-side rendering](10-server-side-rendering.md) has the rule.

**Tradeoff.** Zero React work, literally: no subscription, no boundary, no render.
The "one attribute flip, zero React work" claim is exact under this option. What
you give up:

- **The DOM is no longer derived from state.** Rewind app-db in Xray and the
  attribute does not follow — no event ran. Time travel, a test fixture, or a
  restored snapshot can leave the document showing the old theme while app-db says
  otherwise.
- **Boot needs its own hand.** Apply the initial theme with an initial event that
  carries the same effect, or the first paint is unthemed.
- **The server response carries no attribute.** The effect is client-only by
  declaration, so an SSR'd page arrives unthemed and flips when the client boots.
  That flash is visible, self-healing and about as diagnosable as a page load gets
  — the opposite polarity to A's silent divergence.
- **It is per-document as written.** `document.documentElement` is one element and
  two frames on one page share it, so two themes at once is not expressible.
  Per-frame B *is* writable: an fx handler receives the frame id in its context
  ([Events as data](03-events-as-data.md#callbacks-carry-their-frame)), so an
  app-owned `frame-id → node` map populated at mount plus one effect reading it
  runs to about five lines. Five lines you then own.

### Which one?

**A, unless you have a reason.** Rendering the fact keeps every derived-state
property the rest of this guide builds on, and the cost is one boundary re-running
on an action users take rarely.

Take B when you have measured a need for zero render work on the switch and can
live without derivation — no rewind, no snapshot restore, no fixture that themes
by writing app-db — and when the page is client-rendered, so the flash never
happens. Nothing about starting on A makes that move harder: B is `rf/reg-fx`,
public API you have already met.

## The two laws

### (a) Owned literals win the merge

**`:key`, `:ref`, controlled `:value` and `:checked`, and owned event handlers
cannot be overridden by a theme or by parts.**

A theme can style a field. It cannot rewrite the field's controlled contract.
Without this rule, a stylesheet-shaped map could clobber the `:value` of a
controlled input — and you would debug that as an input bug for a day.

This is not theming-specific. There is one attribute merge, spelled `:&`, and the
literal keys written in the map always win — whether what arrives is a theme's
part attributes, a caller's forwarded remainder, or anything else. A control that
emits parts and a control that forwards props share the same rule. See
[Controlled inputs](04-controlled-inputs.md#forwarding-attributes).

### (b) Switchable values live in CSS variables

**Anything that changes at runtime lives in CSS variables. Part-to-class maps are
boot-static per app. Structural replacement goes through children and slots, never
through parts.**

- Light/dark, density, brand — CSS variables, flipped by the attribute in layer 1.
- A part-to-class map is fixed at boot. Changing one is an app rebuild, **by
  design** — that is the price of "theme switch is zero React work" for the token
  layer.
- A different *structure* (custom menu row, replaced icon) is a child or a slot.
  Parts address the pieces a control renders; they do not replace them.

Break (b) and parts become a second, worse rendering system driven by data at
runtime. That is the failure mode the law exists to prevent.

## React context is still there

Hicasso has no context API of its own and does not theme through context. It does
not ban React.

The substrate keeps exactly one internal context — frame identity — and ordinary
React context remains available at the host edge: a compound-component contract
you are implementing, or a provider an ecosystem library demands. Foreign providers
come in through [`defhost`](05-interop.md) like any other component.

Using it means taking on React's rules, a hook per consumer, and a node your
structural tests can see less of. Honest trade, stated once.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Blank page on first load, console shows `Doesn't support name:` | Nobody had chosen a theme, the sub read `nil`, and `(name nil)` threw at the root | Give the sub a default, as layer 3 does |
| The theme keyword changes in app-db and nothing on screen changes | No bridge is wired — app-db holds the choice; the cascade keys off a DOM attribute | Wire option A or B above; the event alone does nothing to the document |
| Theme switch re-renders the whole app | Option A, and what sits below the scope carries no memo wrapper — a native-tag subtree, a fragment, a `defhost` crossing, or `app` called as a plain function | Put a `defview` head immediately below the scope, as `[app {}]` does; or use option B |
| The hydrated page keeps the server's theme and never corrects itself | Option A, and the two sides disagreed about the choice — the payload did not carry `:theme/current`, or each derived it differently. An attribute-only divergence is the one class React never reports | Allowlist the key in the hydration payload; there is no diagnostic coming ([Server-side rendering](10-server-side-rendering.md#instance-state-and-the-payload-allowlist)) |
| A time-travel rewind shows the old theme | Option B — the attribute was asserted by an effect, so it is not derived from the state you rewound | Expected under B; it is the price named above |
| A controlled input's value gets clobbered by a theme | Should be impossible — law (a) | A runtime bug, not a usage error |
| A part override doesn't take effect | Merge order: instance props beat app theme beat base | Check which layer you set it in |
| Theme change needs a rebuild | Expected, if you changed a part-to-class map — law (b) | Move the switchable part into a CSS variable |
| A themed control renders the wrong structure | Parts address pieces; they don't replace them | Use children or a slot |

## When not to theme

If you have one application and one look, skip layer 2 entirely. Parts are for
**component-library authorship** — someone else's app consumes your control and
needs to restyle its internals without forking it. An app styling its own
components has CSS, and CSS is enough.

Reaching for parts inside a single app buys you an indirection layer and a merge
order to remember, in exchange for flexibility nobody will use.

## Not settled yet

| Question | Status |
|---|---|
| How a control declares a part | Open. "Controls emit keyword-tagged parts" is the claim; the attribute or macro is unnamed |
| How an app installs a theme | Open. Merge order is fixed; the mechanism that supplies the app-theme layer is not |
| The part-address shape | This guide writes `[:typeahead :root]` by analogy; the actual shape is unstated |
| Where the boot-static part map lives | Implied by law (b) to be fixed at build; residence unstated |
| The controls kit that would ship parts | Not shipped yet |
