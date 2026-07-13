---
name: re-frame2-ui
description: >
  Writes view code for re-frame2's compiled-view substrate `re-frame.ui`
  (artifact `day8/re-frame2-ui`, alias `ui/`) — `defview` components and
  children, templates, reactive `sub` reads, event handlers (vectors,
  `ui/event`, `ui/handler`, the bare-fn shorthand), local state and
  effects, presence, frames and roots (`frame-root`, `frame-provider`,
  `ui/mount`), interop (`ui/raw`, `ui/html`, `ui/spread`, `ui/raw-fn`,
  custom elements, `ui/->react`), and structural tests via
  `re-frame.ui.test`. Use whenever the user mentions defview, ui/sub,
  frame-root, ui/mount, compiled views / compiled hiccup, the re-frame2
  view compiler, ui.test, presence-phase, or is writing/porting VIEW code
  in an app that depends on day8/re-frame2-ui — even when the library is
  not named explicitly. **View layer only** (the `(props) → template`
  surface and its tests). **Do not use** for: events/subs/fx/machines/
  flows authoring (use `re-frame2`), Reagent/UIx/Helix adapter views (the
  frozen/legacy substrates — use `re-frame2` §views), live-app inspection
  (use `re-frame2-pair`), or migrating a Reagent codebase (use
  `re-frame-migration` once its Reagent→`re-frame.ui` path lands, W2).
allowed-tools:
  - Read
  - Edit
  - Write
  - Grep
  - Glob
  # Authoring-only, mirroring the re-frame2 skill's Q14 lock: the skill
  # stops at writing code; the author runs compilers/tests.
---

<!-- ═══════════════════════════════════════════════════════════════════
DRAFT STATUS (preparation artifact — NOT installed)
Prepared 2026-07-13 by Fable as W6 preparation (rf2-vxgfnd program), v3
after review sweeps. Grounded in: 02-programming-model.md (final), guide/02-views
+ 03-state + 09-testing, merged Spec-004/004B/004C/004D, the blessed §2
API table. The skill teaches the FULL v1 surface because it installs at
W6 (Stage 5–6) when it is all live; verbs not yet shipped as of S2 carry
an inline ⏳S<n> tag — the promotion bead strips tags as stages land and
verifies every example against the then-current conformance profile.
═══════════════════════════════════════════════════════════════════ -->

# Writing re-frame.ui views

`re-frame.ui` is the one taught view layer for re-frame2. Views are **hiccup
compiled at build time** — you write data; a compiler lowers it to direct
React code (browser) and a structural render tree (JVM). No interpreter, no
`#js`, no camelCase, no manual memo. If you know React, the load-bearing map:

| You know (React) | re-frame.ui | The divergence that matters |
|---|---|---|
| function component | `defview` | pure `(props-map) → template`; auto-registered; auto-memoized on `rf=` |
| `children` prop | `:children` binding | **opt-in by declaration** — passing children to a view that doesn't bind them is a compile error |
| `useSelector`-ish read | `(sub [:query …])` | compile-indexed; ONE React bridge per view, not per read |
| `onClick={fn}` | `:on-click [:event-id …]` | **handlers are event vectors (data)** — serializable, replayable, tool-visible |
| `useState`/`useEffect` | `local` / `effect` ⏳S3 | host-local by design, with a placement LAW (§6) |
| JSX spread | `(ui/spread base overrides)` | the ONE dynamic prop-map conversion; props position only |
| `dangerouslySetInnerHTML` | `(ui/html string)` | the one escaping bypass; call-site visible; manifest-recorded |
| `<Provider>` + bootstrap | `frame-root` / `frame-provider` | frames are created at **host preflight**, never during render |
| `createRoot/render` | `ui/mount` (+ `create-root`/`render!`/`unmount!`) | root forms are **literal** (macro); roots carry identity |
| AnimatePresence | `(ui/presence …)` ⏳S4 | a presence primitive (3 phases + lifetime contract), not an animation system |
| React Testing Library | `re-frame.ui.test` | structural-first on the JVM (no DOM), closed selector grammar |

**Reading the ⏳ tags:** ⏳S`n` = that verb ships at rollout stage *n*;
untagged surface is the live S1/S2 baseline. Writing for an app whose
`re-frame2-ui` version predates a tag's stage: the verb does not exist there
yet — use the interim path the section names (§6's pre-S3 note is the pattern).
The full ledger is at the bottom; tags are stripped at promotion, when
everything here is live.

Examples below assume this ns shape (body forms referred bare):

```clojure
(ns shop.views
  (:require [re-frame.core :as rf]   ; events/subs/fx — authored with the `re-frame2` skill
            [re-frame.ui :as ui :refer [defview sub local effect lease presence-phase]]))
;; tests (§10): (:require [clojure.test :refer [deftest is]]
;;                        [re-frame.ui.test :as uit])
```

## 1. `defview` — the one component form

```clojure
(ui/defview product-card
  "One product tile."
  {:props [:map [:product [:map [:id :int] [:name :string] [:price :double]]]]}
  [{:keys [product]}]
  (let [{:keys [id name price]} product
        in-cart? (sub [:cart/contains? id])]
    [:div.card
     [:h3 name]
     [:span.price (format-price price)]
     [:button {:on-click (if in-cart? [:cart/remove id] [:cart/add id])
               :disabled (sub [:cart/locked?])}
      (if in-cart? "Remove" "Add to cart")]]))
```

- **Zero or one argument, always a props map — no positional args, no Form-2,
  no `with-let`, no classes.** Header destructuring (`:keys`, `:x/keys`,
  `:or`) lowers to direct host property reads — no CLJS map is built at
  entry. `:as` works but materializes the map and switches to generic
  comparison (visible dev cost) — prefer named slots.
- **Options map (closed):** `:props` (Malli — literal call sites checked at
  COMPILE time, dynamic at dev runtime, elided in prod), `:id`,
  `:display-name`. Deliberately absent: `:memo false`, `:on-mount`/
  `:on-unmount` (domain events don't ride mechanical React lifecycle —
  StrictMode/Activity/HMR make "once" unrecoverable; visibility belongs to
  route/domain transitions), `:catch`/`:fallback` (error handling is an
  explicit component, §8).
- **Children:** arrive as the `:children` prop — *binding it is what opts a
  view into accepting children*; passing children to a view that declares
  none is a compile error. `:key` is **reserved** (React's list-identity
  slot); an app prop literally named `:key` is a compile error.
- Views call views **by Var**, always with the props map:
  `[product-card {:product p}]`; zero props is the explicit empty map —
  `[status-pill {}]`.
- Every view is **auto-memoized** on a generated per-slot `rf=` comparator.
  No opt-out — a view that must always re-render is reading the wrong inputs.
- `defview` also **registers** (registrar `:view` kind) — Xray lists views,
  Story mounts scenes by view id, the Pair hot-swaps views like handlers.

## 2. Templates

Reagent-familiar hiccup, ambiguities removed. Branch freely — `let`/`if`/
`when`/`cond`/`case`/statically-pure `do`/`for` normalize into the AST; the
compiler sees through them.

```clojure
[:div.sidebar#nav {:style {:width "20rem" :cursor :pointer}}  ; keyword CSS values fine
 [:h2 "Products"]
 [:ul (for [p products]
        [product-card {:key (:id p) :product p}])]]
```

- `:div.cls#id` sugar · `[:<> …]` fragment · strings/numbers = text ·
  nil/false = nothing.
- `:class` — string, vector, or map-of-flags. `:style` — a map; keyword
  values stringify. **Prop conversion is compile-time, contextual, total** —
  the same rule table drives both emitters, so SSR output cannot drift.
- **DOM prop spelling is pinned**: hyphenated lowercase mirroring React's
  camelCase — `:on-click`, `:on-key-down`, `:on-input` (never `:on-keydown`).
- **Keys on list items are required** — a missing key is a *build failure*
  with file:line; keys must stay unique **after string coercion** (`1`
  collides with `"1"`).
- **Rejected at compile time** (didactic errors naming the escape): dynamic
  tag heads (`[(if big? :h1 :h2) …]` — write two branches or bind attrs);
  markup-returning `map` (use `for`/a child view); keywords in child
  position; raw lazy seqs; unkeyed list items; `sub`/`lease` in loop
  *bodies* (extract a keyed child view — sites must be finite; §4).
- Genuinely runtime-authored UI (CMS trees) is `re-frame.ui.data/render` —
  a separate, demand-gated artifact (wave-2, not v1). Until then a
  runtime-chosen head is a child view per branch or `ui/raw`.

## 3. Event handlers — the decision table

**Canonical: the event vector.** Dispatched to the committed frame:

```clojure
[:button {:on-click [:cart/add id]} "Add"]
[:input  {:on-input [:form/typed :email :rf.ui/value]}]
[:input  {:type :checkbox :on-change [:prefs/set :dark :rf.ui/checked]}]
```

**Placeholders (closed vocabulary, scalars only):** `:rf.ui/value`,
`:rf.ui/checked`, `:rf.ui/key` — spliced at dispatch time, recognized in
**literal vectors only** (a placeholder inside a runtime-forwarded vector
dispatches as an ordinary keyword; dev warns). There is deliberately no
`:rf.ui/form-data` or `:rf.ui/event` — those cases belong to `ui/event`.

| Form | Fires | Serializable | Use for |
|---|---|---|---|
| `[:event … :rf.ui/value]` | after commit | **yes** | intent — the 90% case |
| `(ui/event [e] … [:vector …])` ⏳S3 | after commit, sees the live event | no | event mechanics, form/file payloads, filtering (`nil` ⇒ no dispatch) |
| `(ui/handler [x] …)` ⏳S3 | after commit | no | imperative work, stable-identity change-callbacks |
| `(ui/render-fn [x] …)` ⏳S3 | during the *foreign* render | no | item-key/comparator/render props; pure — no dispatch/sub/lease/hooks |
| bare `#(…)` on a **known native event prop** (`:on-*` on DOM/custom elements) | after commit | no | legal shorthand for `ui/handler` — **only there** (not refs, not arbitrary fn-valued props) |
| bare fn in any **other prop position** (foreign heads, internal-view props) | — | — | **compile error** — foreign: pick `ui/event`/`ui/handler`/`ui/render-fn`/`ui/raw-fn`; view props: forward intent as data (the child places the vector at its own DOM site) or pass `ui/handler`/`ui/raw-fn` |
| `(ui/raw-fn f)` | identity passed through | no | identity-as-protocol APIs; **the callback-ref form** |

*(Stage note: every form above is recognized grammar from S1 — the data
vector included — but committed **dispatch behavior** for all of them lands
with the ⏳S3 events stage; the ⏳S3 tags mark the three macros that do not
ship until then.)*

- **Handler-map options** for DOM listeners: `{:event [:ev …]
  :prevent-default true :stop-propagation true :capture true :passive true
  :once true}` — explicit, never implied.
- **Dynamic handler expressions are legal** (`(if in-cart? [:a id] [:b id])`,
  prop-forwarded handlers). Literal forms classify at compile time; runtime
  values classify by type (vector→dispatch, map→options, compiled handler
  object→itself, fn→boundary rules, nil→no handler). Manifests mark
  value-classified sites `:dynamic`.
- **Loops:** a capture-free literal vector shares one callback across rows.
  A vector **capturing the loop binding** is a compile error — extract a
  keyed child view (per-row committed slots need per-row instances); the
  same rule covers `ui/event`/`ui/handler` in loops (they are sites too).
  Bare fns in loops work but dev-warn (per-row closures defeat the data
  idiom).
- **Explicit-everywhere as team policy:** the day-one strict lint
  `{:re-frame.ui/bare-handlers :warn}` (or `:error`) — the language stays
  permissive; the lint is the lever.
- **Refs:** `:ref` is a reserved React slot, never an event prop — the
  bare-fn shorthand does NOT apply. Prefer object refs; a callback ref must
  be explicit `(ui/raw-fn f)`. Internal views forward `:ref` only by
  declaring it.
- **Controlled inputs — the synchrony law** ⏳S3: dispatches from
  `:on-input`/`:on-change`/`:on-before-input` on a *provably controlled*
  element (literal `:value`/`:checked` co-present at the same site) drain
  synchronously within the DOM event — no dropped characters, no caret
  jumps, IME-safe. Dynamic props maps / `ui/spread` / `ui/event`/bare-fn
  dispatches at such sites fall back to batching with a dev diagnostic
  naming the conditions.
- Dev safety net: a data handler naming an unregistered event id warns at
  render with the element's coordinates.

## 4. Reactive reads — `sub`

```clojure
(sub [:orders/by-id id])   ; → value; a compile-indexed site
```

- Conditional reads are legal; `sub` **in a loop body is a compile error**
  (per-row sites are unbounded — index rows into a keyed child view; each
  row gets its own cell). The loop's *seq expression* is one site and legal:
  `(for [t (sub [:toasts/visible])] …)` (§7 relies on this).
- **Read narrowly; compute in subs.** A row reading `(sub [:orders/by-id id])`
  repaints on its own; a broad `(sub [:orders/all])` repaints on any change —
  the classic re-frame layering advice. Keep real computation in `reg-sub`
  (cached, Xray-visible, JVM-testable); views do presentation math only.
- All of a view's sites share **one** React bridge (one
  `useSyncExternalStore`, one revision; renders probe without ownership, the
  commit acquires). You never manage subscriptions.
- Reads resolve the ambient frame (§5). **Frames are isolated contexts** —
  never spell another frame's id into a query; cross-frame reads are an
  anti-pattern the framework diagnoses.

## 5. Frames, roots, mounting

A **frame** is an app-state universe; a **root** is a React mount. A page is
N roots referencing M frames. Frames are created at **host preflight**,
never from render.

```clojure
(rf/init! ui/adapter)   ; boot, once: install the view substrate
(ui/mount
  [ui/frame-root {:id :app/shop :initial-events [[:boot]]}
   [product-page {}]]
  (js/document.getElementById "root"))
```

- `ui/mount` is a **macro over a literal root form** — a runtime-assembled
  vector is a compile error. Root opts (the optional third argument:
  `(ui/mount form el opts)`) carry identity: `:root-id` (required
  identity, derivable from the mounted view's id), `:disambiguator`,
  `:identifier-prefix` (compile-time literals; effective prefixes must be
  unique per page — typed error, not aliasing), plus host error callbacks.
- `frame-root {:id …}` (top region of a root form only): its plan is
  extracted at compile time; preflight **ENSUREs** — creates-if-absent,
  idempotent (same fingerprint = no-op), a differing plan from a *different*
  root fails exactly that root (`:rf.error/frame-payload-conflict`); a
  differing plan re-declared by the *same* root is a surgical refresh (the
  HMR config-edit path — durable state survives, `:initial-events`
  re-recorded not replayed); then
  the emitted component **scopes** the live frame to the subtree. Takes
  `make-frame` opts. Conditional/list `frame-root` is a compile error.
- A plan-less `[ui/frame-root {:id :app/session}]` **adopts** a boot-created
  frame — it never rewrites the boot config/generation (ENSURE still holds:
  no live frame → created with default config); a config-carrying
  plan meeting a boot frame fails loud.
- `frame-provider {:frame f}` — SCOPE-only, template-legal, plumbs an
  **already-live** frame; fails loud if absent. It never creates.
- Ambient resolution: explicit pin → dynamic binding → React context → loud
  `:rf.error/no-frame-context`. Design views so they never know their frame.
- Host tier: `create-root` (identity fixed for the Root's lifetime; authored
  `:root-id` required — no root form to derive from), `render!`, `unmount!`
  (total teardown); `hydrate-root` + `render-static` + root manifests land
  with SSR ⏳S5 (hydrate fails loud until then; hydration identity comes
  FROM the server manifest — supplying identity opts client-side is an
  error).

## 6. Local state and effects ⏳S3 — and the placement LAW

```clojure
(let [[text set-text] (local "")]              ; host component-local state
  [:input {:value text :on-input #(set-text (.. % -target -value))}])  ; bare fn — legal on a native event prop

(effect [node series] (draw! node series) #(destroy!))  ; rf= value deps; cleanup fn
(effect :connect (subscribe-external!))                  ; per-connect; cleanup at disconnect
(lease {:resource :article/by-slug :params {:slug slug}}) ; interest only (executes from S2; resource semantics confirm ⏳S3)
```

- `local` is **host component-local, deliberately outside re-frame2
  epochs**. There is no "once"/"mount" effect name — `:connect` is named for
  what React actually does.
- **The placement law:** a `local` value MAY be read by same-view committed
  handlers (the search-box seam — local keystroke text inside
  `[:search/run text]` — is canonical). `local` is **FORBIDDEN** when the
  value needs cross-view observation, replay/persistence, schema/tool
  inspection, durable navigation semantics, or subscription-derived
  computation — those belong in app-db behind an event (author with the
  `re-frame2` skill).
- **`lease` declares *interest* only** — first lease in ensures the resource
  (fetch starts or an in-flight one is joined), last lease out lets it wind
  down; it never returns data and never fetches during render. Reading is
  always `(sub [:rf/resource …])`. Navigation/workflow loading rides
  route/event resource plans; `lease` is for liveness that genuinely follows
  visible UI — dashboard tiles, hover cards, modals.
- **Dispatching imperatively — from an `effect`, an interop callback, or a
  bare-fn/`ui/handler` body —** uses
  `(ui/dispatch-fn)` ⏳S3 — one stable fn per view instance, bound to the
  committed frame, loud after the view disconnects
  (`:rf.error/dispatch-disconnected` — a leaked foreign listener becomes an
  error you see). There is no ambient dispatch inside a view or its
  callbacks — `rf/dispatch` there finds no frame context (`ui/event` needs
  none: the framework dispatches its returned vector). The fuller ops map is
  `(ui/frame)` (`:frame` `:dispatch`
  `:dispatch-sync` `:subscribe`) for bridges that must name their frame.
  Dispatching *during render* is a dev warning.
- Until S3 lands in the consumer's version: keep ALL state in app-db
  (`local`/`effect` do not exist yet). `lease` executes from S2 — on a
  pre-S2 version it raises `:rf.error/ui-lease-unavailable`.
- Foreign-React boundaries that genuinely need a hook get `re-frame.ui.react`
  (`use-ref`, `use-effect`, `use-layout-effect`, `use-effect-event`,
  `use-context`, `use-id`, `lazy`) ⏳S3. Inside ordinary views you never
  reach for them.

## 7. Presence — declarative enter/exit ⏳S4

```clojure
(ui/presence {:timeout-ms 300}
  (for [t (sub [:toasts/visible])]
    [toast-card {:key (:id t) :toast t}]))

(ui/defview toast-card [{:keys [toast]}]
  (let [phase (presence-phase)]          ; :mounting | :present | :unmounting
    [:div.toast {:class (name phase)}    ; your CSS animates
     (:message toast)]))
```

Keyed children pass `:mounting → :present → :unmounting`; an exiting child
stays until its transition ends (`:timeout-ms` is the mandatory bound — no
zombies), then cleanup is terminal and exactly-once. Free wins: exiting
children are `inert`/`aria-hidden`; remove-then-re-add is deterministic;
reduced-motion takes the instant path; hydration doesn't fabricate enters;
tests use `ui.test/flush-presence!` (never sleeps). Outside a boundary,
`(presence-phase)` returns `:present` — presence-aware views work anywhere;
JVM/Tier-1 renders `:present` (phase metadata exposed structurally).
Unkeyed children under presence are a build failure. It is a *presence*
primitive — the animations are CSS or a foreign library at a boundary.

## 8. Interop and boundaries

```clojure
[:div (ui/raw badge-element)]                 ; embed a React element (child position)
[DatePicker {:selected date                   ; foreign component head — JS values pass through
             :on-change (ui/handler [v] (pick! v))}]   ; callbacks per §3 table
[:article (ui/html rendered-markdown)]        ; trusted markup — you're vouching
[:input (ui/spread {:type :text :class "field"} attrs)] ; base, then overrides
(def MyWidget (ui/->react product-card))      ; export to a React codebase ⏳S6
(ui/client-only {:fallback [:div.map-shell]}  ; browser-only subtree ⏳S3 (SSR flip S5)
  [mapbox-panel])
```

- `ui/error-boundary` ⏳S3 — `{:fallback view :reset-key val :on-error
  [:ev …]}`: catches render/lifecycle throws below it (NOT event-handler or
  async errors); `:on-error` dispatches after the failing commit; the
  fallback renders with `:error` + declared props and cannot recursively
  dispatch; changing `:reset-key` clears (retry = state change); SSR renders
  the child.
- **Custom elements** (tag containing `-`; the `ui/custom-element` export is
  live from S1 — classification conformance asserts ⏳S4): used directly,
  never through
  `ui/raw`. Default: every prop is an **attribute** (server-emittable). Rich
  **properties** declare once: `(ui/custom-element :user-picker {:properties
  #{:users :selected-id}})` — the set is the entire grammar; declared
  kebab-case maps to camelCase property; native custom events ride the
  normal handler grammar (`event.detail` is `ui/event`'s job). JVM emits
  attributes only; properties apply at hydration.
- No `ui/element` / `ui/view` / `ui/portal` — wave-2, demand-gated.

## 9. Props discipline — why you never think about memo again

Props compare by value (`rf=`, per slot) to decide re-renders:
- **CLJS data** (maps/vectors/sets/records/dates) compares by value — a
  rebuilt-but-equal map never repaints; a fresh `{:id 42}` literal is free.
- **Host values** (raw JS objects/arrays/fns/React elements) fall through to
  identity: a fn prop "re-renders with the parent" (fns reach view props
  only via §3's explicit forms — a bare fn prop on a view is a compile
  error); an in-place-mutated
  JS object never repaints. Mutable foreign values belong at an interop
  boundary, not in props.
- Handlers are vectors and children are realized, so comparison stays honest
  by default. `##NaN` is repaint-stable; identity doubles as the fast path.

## 10. Testing — `re-frame.ui.test`

```clojure
(deftest add-button-carries-intent
  (let [frame (uit/frame {:app-db {:cart #{}}})
        tree  (uit/render [product-card {:product {:id 42 :name "Hat" :price 9.5}}]
                          {:frame frame})]
    (is (= "Hat"          (uit/text (uit/find tree :h3))))
    (is (= [:cart/add 42] (:on-click (uit/attrs (uit/find tree :button)))))))
```

- **Tier-1 (default): real view + real frame on the JVM, no DOM.** `render`
  opts are CLOSED: `{:frame f}` XOR `{:app-db seed}`, `:props` (bare-view
  form only), `:sub-overrides {query value}` (the explicit JVM override
  door). Unknown keys throw didactically.
- `render` also takes a **literal root form** — plan-bearing `frame-root`
  forms preflight fresh isolated test frames and tear down after. With a root
  form, `:props` is rejected (props live in the form); `:frame`/`:app-db`
  alongside a plan-bearing form are rejected (the form owns its frames).
- **Assert structure and intent, not pixels.** Handler slots hold event
  vectors as data — "what does this button do" is an equality check, no
  click simulation. **Reads go through the projections**: `(uit/attrs node)`
  (attrs + events merged on elements; props on view-boundary nodes) and
  `(uit/text node)`. Keyword lookup on a *node* reads a tree field and
  silently misses — `(:on-click node)` is wrong;
  `(:on-click (uit/attrs node))` is the idiom.
- **Selector grammar (closed):** tag keyword · view id / defview Var · attr
  map (`rf=`) · predicate fn. Compose by nesting:
  `(uit/find (uit/find tree :form) :button)`. **No CSS strings in `find`**;
  **no vector path selectors** (demand-deferred). `find` returns the node
  (itself queryable) or `nil` — a miss threads through to a clean
  `nil ≠ expected` failure.
- `dispatch!` drives Tier-1 state: real dispatch + drain to fixed point —
  re-render and assert; no flush call needed. On CLJS, Tier-3 `with-root`
  and both `(flush!)` / `(flush! thunk)` return Promises. `with-root` awaits
  initial mount, the body value/Promise, and total teardown; prefer the thunk
  flush so the write runs inside direct React 19 `act`, reaches drain
  quiescence, and then awaits the one Promise through the framework/React
  fixed point. In `cljs.test`, use
  `async done` and explicit `.then` success + rejection callbacks — never a
  bare returned Promise. Await before asserting or beginning another mounted
  operation; otherwise `:rf.error/ui-test-overlapping-act` fails loudly.
  JVM `flush!` is synchronous and returns nil. Tier-3 also carries `query`
  (native CSS) + ordinary DOM `dispatchEvent` calls;
  `flush-presence!` advances presence ⏳S4. Reach for Tier-3 only when DOM
  mechanics are the subject.
- **The `.cljc` law:** Tier-1 requires the events/subs a view touches to be
  `.cljc` — an authoring constraint on the app, stated up front.
- **JVM structural subset:** structure, props, branches, lists, event
  *intent* are fully faithful. Host-bearing ops (`ui/raw` children, foreign
  heads, `local` setters) throw `:rf.error/jvm-host-op` → use a mounted
  test. A Tier-1 render of a `local`-using view sees initial values.

## 11. REPL, HMR, and errors

- `defview` re-evaluation at the REPL **re-registers** (generation bump;
  mounted dev cells go stale; a hook-signature change remounts). The HMR
  path IS the REPL path — no frame re-seeding, stable shells.
- The compiler and runtime throw **named, catalogued, didactic** errors
  (`:rf.error/ui-tree-malformed`, `ui-duplicate-key`, `ui-test-bad-selector`,
  `ui-test-overlapping-act`, `frame-payload-conflict`, `no-frame-context`, …).
  The error text teaches
  the fix — read it, fix the shape it names, never wrap around it.

## Staged-surface ledger (strip tags at promotion)

⏳S3: `local` `effect` `dispatch-fn` · `ui/event` `ui/handler` `ui/render-fn`
+ committed dispatch behavior for ALL handler forms, the data vector
included (grammar compiles from S1) + sync door · `error-boundary` · `client-only` ·
`re-frame.ui.react/*` · `lease` view-level resource semantics (`lease`
itself executes from S2) — ⏳S4: `presence`/
`presence-phase` · `custom-element` classification conformance — ⏳S5: SSR
(`render-static`, live `hydrate-root`, root manifests, `client-only` flip)
— ⏳S6: `->react` — wave-2 (no v1): `element` `view` `portal`
`re-frame.ui.data`.

## Repo-specific notes (examples only — this skill is generic)

A consumer app needs `day8/re-frame2` + `day8/re-frame2-ui` on the
classpath. This dev repo's fixtures: `implementation/ui/test/`; guide seed:
`ai/findings/new-substrate-synthesis/guide/`.
