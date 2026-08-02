# Hicasso — the authoring surface

What a programmer (and their AI) writes. This page is design intent for the v0
dogfood and the eventual guide; the declaration spellings stay unfrozen until the
donor arm and the surviving lean-React arm have measured (HD-008) — nothing here
licenses freezing an API early. (Written when the tournament had two arms; Arm 2
was withdrawn 2026-07-31 and the unfrozen rule is otherwise unchanged.) Decisions
cited as HD-nnn are normative in [decisions.md](decisions.md).

## Views and reads

```clojure
(:require [re-frame.hicasso :as h :refer [defview sub]])

(defview todo-row [{:keys [id]}]
  (let [todo     (sub [:todo/by-id id])
        editing? (sub [:todo.ui/editing? id])]
    [:li
     [:span (:title todo)]
     [:button {:on-click [:todo/toggle id]} "✓"]
     (when editing?
       [:input {:value    (sub [:todo.ui/draft id])
                :on-input [:todo.ui/edit id ::h/value]}])]))

(defview todo-list [_]
  [:ul
   (for [id (sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])
```

- **`defview` mints a boundary** — a real React function component. In hiccup,
  `[view-var props-map]` is a boundary child (a React element; `:key` lives in
  the props map — no metadata keys; native-element keys are likewise `:key` in
  the props position). A **plain function call** `(helper …)` inlines into the
  enclosing boundary — no boundary of its own (helper-donated `sub` reads are
  collector-contingent, HD-002). A bare seq of boundary children requires keys
  (dev warning); a plain function in head position is a loud error, never a
  silent embedding; the `for`-lowering sugar is not v0 (HD-016).
- **`sub` returns a value.** Whether it is legal *anywhere* inside a render —
  conditionals, loops, helpers, as sketched above — is **collector-contingent
  (HD-002)**: the sketch shows the challenger's authoring model, which ships only
  if the collector wins its per-read budget in P1. Under the hook-fixed working
  default, reads sit at fixed sites and conditional needs are met by conditional
  child boundaries or conditionally-constructed query values. Either way: `sub`
  outside a render is an error; `subscribe-once` is the sanctioned snapshot for
  handler/utility code; framework subs (machine tags, resource/mutation state,
  route identity) read identically to app subs.
- **Bodies are pure and re-runnable** (StrictMode runs them twice). No
  `useState`/local reactive cells for app state (HD-009); hooks belong at host
  edges — though a body *is* an honest React FC, so an advanced author who uses a
  hook simply takes on React's hook rules themselves (HD-003: taught fence, not
  runtime police).

**The grouped (default) spelling of the same view** — pre-declared per HD-002 so
the default surface is scored from day one, not discovered mid-clock:

```clojure
(:require [re-frame.hicasso :as h :refer [defview use-subs]])

(defview todo-row [{:keys [id]}]
  (let [{:keys [todo editing? draft]}
        (use-subs {:todo     [:todo/by-id id]
                   :editing? [:todo.ui/editing? id]
                   :draft    [:todo.ui/draft id]})]
    [:li
     [:span (:title todo)]
     [:button {:on-click [:todo/toggle id]} "✓"]
     (when editing?
       [:input {:value draft :on-input [:todo.ui/edit id ::h/value]}])]))
```

**The component ABI** (HD-016; pinned by the keyed insert/delete/reorder witness):

| Head | Props | Children | `:key` | `:ref` |
|---|---|---|---|---|
| Native tag | attr map | trailing forms; seqs realized once, flattened one level; `nil`/`false` render nothing, `true` errors | `:key` in the attr map | callback ref, legal |
| Hicasso view | one props map | trailing forms, delivered as `(:children props)` (realized vector) | in the props map; **extracted before the body sees props** (React's contract) | not a v0 surface (use ids) |
| Fragment `[:<> …]` | — | trailing forms | on the fragment vector's props map if keyed | — |
| Foreign (`defhost` / `[:>]`) | converted per declaration/defaults | hiccup → elements | `:key` in props | callback ref, legal |

An existing React element is a legal child anywhere (pass-through). A view may
return `nil`, one root, or a fragment.

`:ref` takes a **function** in v0. A **vector** is the reserved value-space for the
later data spelling (`{:ref [registered-id config]}`) and is refused loudly —
`:rf.error/hicasso-ref-vector-reserved` (HD-022). The reservation is one branch and
one error id; it exists so the imperative escape can become data without minting a
second attribute name, and it carries one honest limit that shapes what you write
today: a ref callback fires on attach and detach and **never on config change**, so
steady-state change belongs on an effect, not on the ref.

## Event intent as data

- A literal vector at an event position is the intent; `::h/value` is the value
  placeholder (~97% of corpus handler sites become pure data). Ordinary functions
  remain legal at event positions.
- Census-weighted policy defaults: `:on-submit` intents auto-prevent (the rare
  opt-out is the `h/fn` escape — a callback holds the event, so it owns it); a
  data key-map `{:on-keydown {"Enter" [...] "Escape" [...]}}` with the
  composition law centralised — a composing Enter (IME, including the
  keyCode-229 legacy signal) commits nothing.
- Everywhere else, prevention is opted **in** by one reserved head,
  `[::h/prevent [:conduit/show-your-feed]]` (HD-026) — the shape is the
  anchor-acting-as-a-button, which a click default cannot absorb because a
  modifier-click on a real link must still open a tab. It is a head rather than metadata on the intent
  because metadata does not participate in `=`, and HD-021's headless door reads
  intent vectors by equality. The grammar is closed: exactly one inner intent
  vector, unwrapped before `::h/value` is looked for, and anything else is
  `:rf.error/hicasso-malformed-prevent` naming the position.
- Callbacks generated from intents close over the boundary's frame (resolved once
  per boundary from the substrate's single internal context) so they remain valid
  when the browser invokes them after render scope unwinds.
- **The route-link is a plain function over routing's link seam** (HD-027; the
  census counts 106 and calls the form tier-1). The author names a route and
  params and never sees a URL —
  `(route-link {:to :conduit.profile/show :params {:username u}} u)` — and what
  comes back is one real `<a>` whose href is routing's and whose click decision
  travels as data under the second reserved head, `[::h/navigate {…}]`. It
  inlines (no boundary, no hook, no read), the click law is routing's
  `activate-link!` seam verbatim (modifier-click and native anchors stay the
  browser's), and its `:on-click` is the pre-navigation veto:
  `[::h/prevent [:app/event]]` there cancels the navigation and dispatches the
  app intent instead — the cancelable-navigation case the prevent head was built
  for. A bare intent vector at that position is refused loudly: the click
  already produces the one routing intent.

### When a vector is not enough: one form, and the position decides (HD-024)

There is **one** callback form, `h/fn`, and it is an **ordinary function**:

```clojure
[:input {:type "file"
         :on-change (h/fn [e] [:upload/picked (js/Array.from (.. e -target -files))])}]
```

| Position | Contract |
|---|---|
| a native `:on-*` prop | **event** — a returned vector is dispatched; any other return is ignored |
| a `defhost` `:callbacks` entry | as **declared** (`:event` or `:handler`) |
| any other walked prop position (a slot, a foreign render prop) | **render** — pure; the return is output, and dispatching from inside is a loud error **naming the position** |
| `:ref` | React's own contract; not lowered |
| anywhere Hicasso does not walk | a plain function; it runs, and its return is ignored |

A view's props map is not a position — it is data in transit, exactly as an intent
vector is; the value is lowered where it finally lands. Ordinary functions remain
untouched at every position, which is why there is no separate identity-passthrough
form: the codec already hands functions to React by identity so `React.memo` and
handler-identity bail-outs keep working.

The point of the collapse is the last row. In the predecessor the roster forms are
carriers — marker objects — so one handed to a raw `#js` prop is not callable and
the failure is the engine's own `TypeError`, naming nothing the author wrote. A
form that is a function cannot fail that way.

## Forwarding attributes — one merge, spelled `:&` (HD-023)

There is **one** attribute merge and it is a reserved key in the attribute map, not
a call:

```clojure
(defview field [{:keys [id busy?] :as attrs}]
  [:input.form-control {:& (dissoc attrs :id :busy?)
                        :value    (sub [:editor/field id])
                        :disabled busy?
                        :on-input [:editor/edit-field id ::h/value]}])
```

**The law is unconditional: the literal keys written in the map always win over
`:&`.** That is HD-010(a)'s owned-literal law applied to every merge rather than
only under theming, and it is what makes the controlled-input door
non-forfeitable — a merge cannot reach an owned literal, so there is no wrong
choice of syntax to make. The case where a caller override *should* win is spelled
by not writing the literal.

Three details. `:key` and `:ref` are never taken from a `:&` map (they address the
element the caller wrote). The same key and the same law hold at a crossing — a
view head, a `defhost` head, `[:>]` — because `:&` is merged before any conversion
and the conversion that follows is the position's own, so a forwarded `:className`
crosses under the name it was written as. And an element's own classes belong on
the **tag**, where the shorthand merge composes them with a forwarded `:class`
rather than one silently replacing the other.

Both halves of the law are enforced on the **slot the key is emitted into**, not on
the key itself. Prop names reach React through one canonicalisation, so `"key"`,
`:x/key` and `'key` are all React's key, and `:onInput` is the same handler as an
owned `:on-input` — a remainder cannot reach either by changing how it spells them
(HD-023(c′)).

## The controlled-input door

Controlled text rides the synchronous door: dispatch → drain/commit → re-render
lands the echo in the keystroke's own task; caret and IME survive value
reassertion (R-A1/R-A2 are v0's acceptance). On the lean-React arm the
end-of-discrete-event restore is React's; a PATCH back end must own it
(architecture.md, hard gate).

## The interop door (HD-011)

`defhost` is the door, and the only form taught — one declaration per foreign
component, with strong defaults:

```clojure
(h/defhost date-picker DatePicker)  ;; defaults: shallow camelCase props,
                                    ;; hiccup children → elements,
                                    ;; fns pass through, SSR placeholder
```

Usage `[date-picker {...}]` is indistinguishable from a native view; the JS
require stays quarantined in one `.cljs` host namespace; the crossing has a
declared identity for tooling; policy overrides live on the declaration. A
migration codemod (collect `[:> X …]` sites → emit the defhost block → rewrite
call sites) upgrades sites incrementally. **The one raw escape** (HD-011),
explicitly secondary to the declaration:
`[:> Component props & children]` — same foreign lowering path, same default
conversions, `.cljs`-only at that node, reduced structural identity; for
runtime-selected components, `memo`/`lazy` values, render-prop-supplied
components, providers an ecosystem library hands you, and one-off migration
sites. The guide's rule: **declare what you use twice.** Bare-head auto-hosting
stays rejected — one sentinel, not two shortcuts. Foreign providers are hosted
either way; imperative SDKs use ordinary host-edge React (refs/effects, HD-003).

## Theming — and why there is no context API (HD-010)

React context is not a Hicasso-native mechanism: the substrate owns exactly one
internal context (frame identity). Library and app theming is three layers, none
of them context:

1. **Design tokens as CSS custom properties** — the cascade *is* a context
   system: nearest-ancestor-wins, subtree-scoped, platform-native. A theme switch
   is one attribute flip: zero React re-renders.
2. **Parts as data addresses; theme as pure data/fn** — controls emit
   keyword-tagged parts; a theme maps part-address → classes/attrs, merged
   `base < app-theme < instance-props`; applying a theme is a tree→tree
   transform unit-testable by equality. No part registry, no multimethods.
   Two laws (HD-010): the **owned-literal merge law** — `:key`, `:ref`,
   controlled `:value`/`:checked`, and owned handlers are unoverridable by theme
   or parts; and the **static-map law** — runtime-switchable styling lives in
   CSS variables, part→class maps are boot-static, structural replacement goes
   through children/slots, never parts.
3. **App-db + `sub` for the theme choice only** — user-picked, per-frame,
   time-travelling.

## Presence — phase as data (HD-025)

`h/presence` retains exiting keyed children for `:timeout-ms`. A child says what it
looks like in each phase, **in its own attribute map**:

```clojure
(h/presence {:timeout-ms 300}
  (for [t (sub [:toasts/visible])]
    [:div.toast {:key (:id t)
                 ::h/unmounting {:class "toast toast--exit"
                                 :inert true :aria-hidden true}}
     (:message t)]))
```

No child view, no ambient read. When the child *is* a boundary, the phase arrives
as an ordinary prop — `[toast-card {:key id :toast t :rf/phase :unmounting}]` — so
it cannot be read from the wrong render scope, it appears in a structural test's
props map, and a headless test supplies it with no clock. There is no
`presence-phase`.

An override wins over the node's own literals (that is what an override is) and can
never reach `:key` or `:ref`, the same law `:&` carries. Writing one on a **view**
head is a loud error naming `:rf/phase`, because the boundary cannot see inside an
opaque child. `:timeout-ms` is mandatory and is both the retention length and the
hard terminal bound; re-entry cancels exit; every dynamic child needs a `:key`;
presence never dispatches domain mount/unmount events.

**Enter is the weak half.** A `:mounting` → `:present` class flip can lose the race
to paint. `::h/mounting` exists, but the reliable spelling for enter is an
animation on insertion (or `@starting-style`); exit is the phase that transitions
happily, because the node is already painted.

## Ephemeral state (HD-009)

There is no `local`/ratom-equivalent, and no `useState` for app state. In order:
CSS for hover/focus; platform-carried state (the top layer owns open/dismiss;
resources/mutations own async status; the controls kit owns drafts/revisions);
host-private React state at host edges for geometry/composition; and app-db for
everything semantically meaningful — where the tax is per-*concern*, not
per-instance (one parametric sub + one named event serve every instance). If the
dogfood shows the residual ceremony registering, the pre-agreed *response class*
is one-declaration sugar — never a state system:

```clojure
;; SKETCH — v0 ships nothing here, and the shape is unfrozen.
(h/defstate ::open {:default false})
;; would mint: a parametric sub (sub [::open id])
;;             and a NAMED setter event [::open id v] — never a generic ui/set
```

Read that as an illustration of the response *class*, not a plan of record.
HD-009 freezes two things about it and nothing else: that any such sugar mints a
**named** setter event rather than a generic `ui/set`, and that it is sugar
rather than a state system. Its concrete shape — the `defstate` spelling,
whether a declared app-db tier is involved at all, and what that tier's frame
and persistence scope would be — stays **unfrozen until the evidence exists**,
and so does whether it ever ships. v0 ships nothing here; the concept budget
arbitrates.

## Testing doors

- **Headless** (HD-021): a structural render returns the hiccup tree as data —
  intent vectors assertable by equality, sub reads overridable through a pure
  read resolver, no browser. Scope: hook-free tier-1 bodies; hook-using and
  foreign regions are mounted-test territory — no fake hook dispatcher, ever.
  This is what deletes most of the corpus's 364 `data-testid` retail.
- **Mounted**: the shared browser facade (act, root lifecycle, residue
  assertions) with zero leaked subscription ref-counts after teardown as a
  standing assertion.
- **Tooling**: the index's evidence seam (HD-005) lets explain-render-class tools
  attach in dev at zero production cost; the registry/manifest surface (views
  enumerable with schemas, docs, source coordinates) is the AI-ergonomics door,
  post-v0.
