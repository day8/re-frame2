---
name: re-frame2-ui
description: >
  Writes, reviews, and debugs view code for `re-frame.ui` (artefact
  `day8/re-frame2-ui`, alias `ui/`) — re-frame2's compiled-view substrate,
  now the DONOR being absorbed into Freehand. Use it for an app that
  ALREADY depends on `day8/re-frame2-ui`; for new view work on the
  re-frame-native view layer, or for porting Reagent views onto it, route
  to `reagent-migration` and `spec/004-Views.md` instead —
  `defview` components, the closed template grammar,
  reactive `(sub …)` reads, handlers-as-data (event vectors, `ui/event`,
  `ui/handler`, the bare-fn shorthand), view-local state and effects
  (`ui/local`, `ui/effect`, `ui/ref`), presence, frames and roots
  (`frame-root`, `frame-provider`, `ui/mount`), interop (`ui/raw`,
  `ui/->react`, `ui/spread`, `ui/html`, custom elements), structural
  tests via `re-frame.ui.test`, and the Shadow build-hook install. Use
  whenever the user mentions defview, ui/sub, frame-root, ui/mount,
  compiled views / compiled hiccup, the re-frame2 view compiler, ui.test,
  the re-frame.ui context sheet, or a `:rf.ui.compile/*` diagnostic id —
  or is writing/porting VIEW code in an app that depends on
  day8/re-frame2-ui, even when the library is not named. The generated
  compile-rejection roster and API disposition live in
  `references/ui-context.md`. **View layer only.** **Do not use** for:
  events/subs/fx/machines/flows authoring (use `re-frame2`),
  Reagent/UIx/reagent-slim adapter views (first-class alternatives — use
  `re-frame2`), migrating Reagent views to this substrate (use
  `reagent-migration`), or live-app inspection (use `re-frame2-pair`).
allowed-tools:
  - Read
  - Edit
  - Write
  - Grep
  - Glob
  # Authoring-only, mirroring the re-frame2 skill's Q14 lock: the skill
  # stops at writing code; the author runs the compiler and the tests.
  # The compiler's didactic rejections are the feedback loop.
---

# Writing re-frame.ui views

!!! warning "`re-frame.ui` is the donor substrate"

    **Freehand** (`re-frame.freehand`, alias `v`) is re-frame2's re-frame-native
    view layer, and it **absorbs** `re-frame.ui` — the compiled tier below is
    the machinery Freehand is taking over, and the standalone `day8/re-frame2-ui`
    artefact is scheduled for deletion once absorption completes. Nothing here is
    a target for **new** view work.

    This skill deliberately keeps its `re-frame.ui` donor-maintenance scope — and
    its name — rather than being retargeted into "the Freehand skill": it remains
    the maintenance home for existing `day8/re-frame2-ui` views until that donor
    artefact is itself removed (the EP-0036 donor-deletion step). Freehand view
    work is served in the meantime by [`reagent-migration`](../reagent-migration)
    and `spec/004-Views.md`.

    Load this skill for an app that **already depends on `day8/re-frame2-ui`**:
    reading, reviewing, debugging or maintaining views it already has. For
    porting Reagent views onto the re-frame-native layer, use
    [`reagent-migration`](../reagent-migration); for Freehand's own contract and
    exported roster, read `spec/004-Views.md` and `spec/API.md` §Freehand views.
    Freehand's shapes are deliberately **not** `ui/` shapes — it has no `local`,
    no `ref`, no `effect` and no `frame-root`, so do not carry an idiom across
    from this page by analogy.

`re-frame.ui` is re-frame2's **compiled-view substrate** — an opt-in
alternative to the Reagent, UIx, and reagent-slim adapters, which remain
first-class. You write hiccup as data; a compiler reads it at *build*
time and lowers it to direct React construction in the browser and a versioned
structural tree on the JVM. There is no runtime hiccup interpreter in the
production bundle, so anything the compiler cannot prove is a **compile error
with a didactic message that names the fix**.

Everything upstream of the view — events, app-db, subscriptions, effects,
frames — is ordinary re-frame2; author it with the `re-frame2` skill. This
skill covers the `(props) → template` surface and its tests.

If you know React, the load-bearing map:

| You know (React) | re-frame.ui | The divergence that matters |
|---|---|---|
| function component | `defview` | pure `(props-map) → template`; auto-registered; auto-memoized on `rf=` |
| `children` prop | `:children` binding | **opt-in by declaration** — passing children to a view that doesn't bind them is a compile error |
| `useSelector`-ish read | `(sub [:query …])` | a plain value, nothing to deref; compile-indexed; ONE React bridge per view |
| `onClick={fn}` | `:on-click [:event-id …]` | **handlers are event vectors (data)** — serializable, replayable, tool-visible |
| `useState` / `useEffect` | `ui/local` / `ui/effect` | host-local by design, with a placement law (§State) |
| `useRef` for a DOM node | `(ui/ref)` | the everyday object ref; callback refs are the explicit `(ui/raw-fn f)` expert seam |
| JSX spread | `(ui/spread base overrides)` | the ONE dynamic prop-map conversion; props position only |
| `dangerouslySetInnerHTML` | `(ui/html string)` | the one escaping bypass; call-site visible; manifest-recorded |
| `<Provider>` + bootstrap | `frame-root` / `frame-provider` | roots **ensure** frames at preflight; providers **scope** live ones — never during render |
| `createRoot` / `render` | `ui/mount` | the root form is **literal** (the compiler extracts frame plans); roots carry identity |
| AnimatePresence | `(ui/presence {:timeout-ms n} …)` | a presence primitive (phases + timeout-bounded retention), not an animation system |
| React Testing Library | `re-frame.ui.test` | six names across two hosts; structural-first on the JVM, no DOM |

Depth lives one level down: [`references/ui-context.md`](references/ui-context.md)
is the **generated** context sheet — the authoring-surface disposition (every
public `ui/` var, taught or deliberately out of scope) and the full
`:rf.ui.compile/*` compile-rejection roster, each entry the compiler's own
message. Load it when you hit a diagnostic id or need the exact call shape;
it is regenerated from the compiler itself, so it never drifts.

## Cardinal rules

1. **Handlers are data.** A literal event vector at an `:on-*` site is the
   canonical form; reach for `ui/event`/`ui/handler` only when the table in
   §Handlers says so.
2. **Product state lives in app-db behind events.** `ui/local` is for
   keystroke-latency ephemera only. Authoring the events and subs is the
   `re-frame2` skill's job.
3. **The compiler is the reviewer.** The grammar is closed; an unprovable
   form is a rejection whose message names the fix. Read the error, fix the
   shape it names, never wrap around it. The roster lives in
   `references/ui-context.md`.
4. **Frames are isolated contexts.** Views never spell a frame id into a
   query; reads resolve the ambient frame. Cross-frame reads are an
   anti-pattern the framework diagnoses.
5. **Assert structure and intent in tests, not pixels.** Handler slots hold
   event vectors as data, so "what does this button do" is an equality check.

## One-time setup

A consumer app adds two dependencies and one Shadow setting:

```clojure
;; deps.edn
{:deps {day8/re-frame2    {:mvn/version "..."}
        day8/re-frame2-ui {:mvn/version "..."}}}
```

```clojure
;; shadow-cljs.edn — TOP level, not inside a build
{:build-defaults {:build-hooks [(re-frame.ui.compiler.build-hook/hook)]}}
```

That hook is the complete contract, and it is load-bearing: it harvests the
whole-build registries (view digests, root/plan indexes, custom-element
declarations) from cache-durable analyzer data. There is no `:cache-blockers`
setting — if you meet one in an older project, delete it; the hook alone is
correct, and faster. React arrives through npm (`react`, `react-dom` in
`package.json`). shadow-cljs 3.4.0–3.4.11 is the tested range, and the two
shadow-cljs halves (deps.edn and package.json) must pin the same version.

The failure mode when the hook is missing is a *runtime* throw on namespace
load — the app cannot resolve its own compiled views. The fix is always the
same: add the hook. Full recipe with the smoke test:
`docs/core/how-to/install-re-frame-ui.md` in the re-frame2 repo.

Boot installs the substrate's adapter once, then mounts:

```clojure
(ns my.app
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui :refer [defview sub]]))

(defn ^:export run []
  (rf/init! ui/adapter)
  (ui/mount [ui/frame-root {:id :app :initial-events [[:app/init]]}
             [app-root]]
            (js/document.getElementById "root")))
```

## `defview` — the one component form

```clojure
(ui/defview product-card
  "One product tile."
  {:props [:map [:product [:map [:id :int] [:name :string]]]]}
  [{:keys [product]}]
  (let [{:keys [id name]} product
        in-cart? (sub [:cart/contains? id])]
    [:div.card
     [:h3 name]
     [:button {:on-click (if in-cart? [:cart/remove id] [:cart/add id])}
      (if in-cart? "Remove" "Add to cart")]]))
```

- **Zero or one argument, always a props map** — no positional args, no
  Form-2, no classes. Header destructuring (`:keys`, `:or`) lowers to direct
  host slot reads; `:as` works but materializes the map and switches to
  generic comparison — prefer named slots.
- **Options map (closed):** `:props` (a Malli schema — present closes the
  map, absent leaves it open), `:id` (registry-id override, a qualified
  keyword), `:display-name`. Deliberately absent: `:memo false` (no opt-out —
  a view that must always re-render is reading the wrong inputs),
  `:on-mount`/`:on-unmount` (domain events don't ride mechanical React
  lifecycle), `:catch` (error handling is the explicit `ui/error-boundary`
  component).
- **Children** arrive as the `:children` prop — binding it is what opts a
  view into accepting children. `:key` is reserved (React's list-identity
  slot); it never arrives as a prop.
- Views call views **by var**, always with the props map:
  `[product-card {:product p}]`; zero props is the explicit empty map.
- Every view is **auto-memoized** on a generated per-slot `rf=` comparator,
  and `defview` **registers** the view — devtools list it, Story mounts it,
  the pair tooling hot-swaps it.

## Templates

Reagent-familiar hiccup with the ambiguities removed. Branch freely —
`let`/`if`/`when`/`cond`/`case`/`for` normalize into the AST; the compiler
sees through them.

```clojure
[:div.sidebar#nav {:style {:width "20rem"}}
 [:h2 "Products"]
 [:ul (for [p products]
        [product-card {:key (:id p) :product p}])]]
```

- `:div.cls#id` sugar; `[:<> …]` fragments; strings/numbers are text;
  nil/false render nothing. `:class` takes a string, vector, or map of
  flags; `:style` a map with literal keyword keys.
- **Prop spelling is pinned**: hyphenated lowercase mirroring React's
  camelCase — `:on-click`, `:on-key-down` (never `:on-keydown`, never
  camelCase). One spelling per concept; the compiler rejects the others and
  names the replacement.
- **Keys on list items are required** — a missing key is a build failure
  with file:line.
- **Heads are literal**: a keyword, a `defview` var, or a foreign-component
  var. A runtime-chosen head is a compile error — write a branch per head,
  or `ui/raw` for a runtime React element.
- Rejected at compile time, each with a didactic message: dynamic heads,
  markup-returning `map` (use `for`), raw lazy seqs, unkeyed list items,
  keywords in child position, `sub` in a loop body (extract a keyed child
  view). The full roster with every message:
  [`references/ui-context.md`](references/ui-context.md).

## Handlers — the decision table

Canonical: the **literal event vector**, dispatched to the committed frame.

```clojure
[:button {:on-click [:cart/add id]} "Add"]
[:input  {:on-input [:form/typed :email :rf.ui/value]}]
[:input  {:type :checkbox :on-change [:prefs/set :dark :rf.ui/checked]}]
```

**Placeholders** — `:rf.ui/value`, `:rf.ui/checked`, `:rf.ui/key` — are a
closed set of three scalars, spliced at dispatch time at **top-level
positions of literal vectors only**. Richer payloads (form data, files,
`event.detail`) belong to `ui/event`.

| Form | Use for |
|---|---|
| `[:event … :rf.ui/value]` | intent — the 90% case; serializable data |
| `(ui/event [e] … [:vector …])` | needs the native event; its **return is the vector to dispatch**, `nil` dispatches nothing (a filter) |
| `(ui/handler [x] …)` | imperative work, return ignored; also gives a fn prop a per-site-stable identity at foreign/internal-view props |
| `(ui/render-fn [args…] template)` | a **pure** render slot a library invokes via `(ui/slot rf-value arg…)` — no `sub`/`frame`/`local`/`effect`/dispatch inside |
| bare `#(…)` | legal shorthand **only** on known native event props (`:on-*` on DOM/custom elements) — never refs, never foreign/view props |
| `(ui/raw-fn f)` | identity-as-protocol callbacks; **the callback-ref form** |

- **Handler-map options** for DOM listeners are explicit, never implied:
  `{:event [:ev …] :prevent-default true :stop-propagation true :capture
  true :passive true :once true}` (closed set).
- **Loops:** a vector capturing the loop binding is a compile error —
  extract a keyed child view and pass the captured value as a prop. A bare
  fn in a loop works but dev-warns (per-row closures defeat the data idiom).
- **Controlled inputs:** a literal `:value`/`:checked` co-present with a
  literal vector or synchronous `ui/event` handler on
  `:on-input`/`:on-change`/`:on-before-input` rides the one synchronous
  dispatch door — no dropped characters, no caret jumps. Other handler
  shapes at such a site fall back to batching with a dev diagnostic.
- Dev safety net: a data handler naming an unregistered event id warns at
  render with the element's coordinates.

## Reactive reads — `sub`

```clojure
(sub [:orders/by-id id])   ; → the value; a compile-indexed site
```

- `(sub q)` is a **lexical view form**, not a callable helper — the compiler
  must see the call site. Pass the read *value* into helpers, or make the
  helper a `defview`. In a loop body it is a compile error (extract a keyed
  child view); the loop's *seq expression* is one site and legal:
  `(for [t (sub [:toasts/visible])] …)`.
- **Read narrowly; compute in subs.** A row reading
  `(sub [:orders/by-id id])` repaints alone; a broad `(sub [:orders/all])`
  repaints on any change. Keep real computation in `reg-sub` (cached,
  tool-visible, JVM-testable); views do presentation math only.
- All of a view's sites share **one** React bridge — you never manage
  subscriptions, and there is nothing to deref.

## State — where each value belongs

| Input | Form | Owns | Use for |
|---|---|---|---|
| subscription | `(sub [:query …])` | app-db (derived) | any value derived from app state |
| props | the `defview` arg map | the parent | values the caller passes down |
| local | `(let [[v set! update!] (ui/local init)] …)` | this component instance | keystroke-latency ephemera: open/closed, hover, uncommitted field text |
| frame | `(ui/frame)` → `{:dispatch :dispatch-sync :subscribe :frame}` | the committed frame | an imperative ops bundle for bridges that must name their frame |

- **The placement law:** `ui/local` is host component-local, deliberately
  outside re-frame2 epochs — it does not revert on epoch restore. The moment
  a value needs cross-view observation, replay/persistence, schema or tool
  inspection, or subscription-derived computation, it belongs in app-db
  behind an event (author with `re-frame2`). When every keystroke is product
  state, dispatch a placeholder instead of holding text in `local`.
- `ui/local` binds in the view's **unconditional top region**. `set!` stores
  its argument exactly (no `useState` fn-overload); `update!` applies
  `(f current & args)` to the latest host state. Both are host-only —
  calling them during render fails loud.

### Effects and refs

```clojure
(ui/defview chart [{:keys [data]}]
  (let [node (ui/ref)]                     ; the DOM-node object ref
    (ui/effect [node data]                 ; re-runs (rf=) when deps change
      (when-let [el (.-current node)]
        (let [c (make-chart el data)]
          (fn [] (destroy-chart c)))))     ; returned fn = cleanup
    [:canvas {:ref node}]))
```

- `(ui/effect [deps…] body…)` is a **leading statement** in the top region,
  before the final template. Deps compare by `rf=` — keep them narrow. A
  returned fn is the cleanup. `(ui/effect :connect body…)` runs at each
  connect with cleanup at each disconnect — there is deliberately no
  `"once"`/`"mount"` name; StrictMode dev replay is expected and cleanup
  must make it idempotent.
- `(ui/ref)` is the everyday DOM-node primitive: bind it in the top-region
  `let`, pass it to `:ref`, read `(.-current node)` from the effect (it
  attaches at commit, before the effect fires). Assignment never re-renders.
  A bare fn at `:ref` is a compile error — a callback ref is the explicit
  `(ui/raw-fn f)` expert seam. An internal view forwards `:ref` only by
  declaring it in its header (React 19 ref-as-prop).
- `sub`/`frame` inside an effect body are compile errors (a deferred
  callback owns no render-time site). Dispatching from an effect or a
  foreign callback uses `(ui/dispatch-fn)` — one stable dispatcher per view
  instance, bound to the committed frame, loud after the view disconnects
  (`:rf.error/dispatch-disconnected` — a leaked listener becomes an error
  you can see).
- Foreign-React boundaries that genuinely need a hook get the
  `re-frame.ui.react` wrappers (`use-effect`, `use-context`, `use-id`,
  `lazy`, …). Inside ordinary views you never reach for them — the compiler
  rejects a misplaced hook and names the substrate form instead.

## Frames, roots, mounting

A **frame** is an app-state universe; a **root** is a React mount. A page is
N roots referencing M frames. Frames are created at **host preflight**,
never from render.

- `ui/mount` is a macro over a **literal root form** — the compiler extracts
  static frame plans from it; a runtime-assembled vector is a compile error.
- `[ui/frame-root {:id :app …}]` (top region of a root form only)
  **ENSURES** its frame: creates-if-absent, idempotent per fingerprint, and
  a hot reload finds the frame live and reuses it — durable state survives
  edits, `:initial-events` are not replayed. A plan-less
  `[ui/frame-root {:id :app}]` adopts a boot-created frame.
- `[ui/frame-provider {:frame f} …]` **SCOPES** an already-live frame for a
  subtree — template-legal, fails loud if the frame is absent, never
  creates. Roots ensure; providers scope.
- Ambient resolution inside views: explicit pin → dynamic binding → React
  context → loud `:rf.error/no-frame-context`. Design views so they never
  know their frame.
- Server paths: `ui/render-static` (inert HTML string, JVM) and
  `ui/hydrate-root` (hydration identity comes FROM the server manifest —
  supplying identity opts client-side is an error). Browser-only subtrees
  sit behind `(ui/client-only {:fallback tpl} client-tpl)` with a mandatory,
  capability-free fallback.

## Presence — declarative enter/exit

```clojure
(ui/presence {:timeout-ms 300}
  (for [t (sub [:toasts/visible])]
    [toast-card {:key (:id t) :toast t}]))

(ui/defview toast-card [{:keys [toast]}]
  (let [phase (ui/presence-phase)]         ; :mounting | :present | :unmounting
    [:div.toast {:class (name phase)
                 :inert (= :unmounting phase)}
     (:message toast)]))
```

A presence primitive, deliberately bounded — not an animation system. Keyed
children pass `:mounting → :present → :unmounting`; an exiting child is
retained for exactly `:timeout-ms` — the **mandatory** exit retention
duration and terminal bound — then cleanup is terminal and exactly-once.
The boundary is **DOM-agnostic**: it inserts no wrapper node, stamps no
attributes, and observes no DOM events. A presence-aware child **owns its
own exit styling and accessibility** — stamp `inert`/`aria-hidden` and the
exit class against `(ui/presence-phase)` = `:unmounting`, and let the
child's stylesheet honour `prefers-reduced-motion`. Children render in
first-appearance order and keys hold their slots — sort upstream when order
matters. Outside a boundary `(ui/presence-phase)` returns `:present`, so
presence-aware views work anywhere; the JVM renders `:present`. Unkeyed
children under presence are a build failure. Tests advance transitions with
`ui.test/flush-presence!` — never wall-clock sleeps. The animations
themselves are CSS (or a foreign library at an interop boundary).

## Interop and boundaries

```clojure
[:div (ui/raw badge-element)]                 ; embed a React element (child position)
[DatePicker {:selected date                   ; foreign component head — JS values pass through
             :on-change (ui/handler [v] (pick! v))}]
[DatePicker (ui/spread {:selected date} forwarded-props)] ; wrapper idiom at a foreign head
[:article (ui/html rendered-markdown)]        ; trusted markup — sole child; you're vouching
[:input (ui/spread base overrides)]           ; DOM element props position; later-arg-wins
(def CartRow (ui/->react cart-row))           ; export a compiled view to a React codebase
```

- `ui/->react` is the **outward migration bridge**: the exported component
  renders inside the host's React root (no new root, no preflight) and
  scopes — never creates — a frame via its one reserved `frame` prop, else
  the ambient frame.
- **Custom elements** (tag containing `-`) are used directly. Every prop is
  an attribute by default; rich properties declare once —
  `(ui/custom-element :user-picker {:properties #{:users}})` — and the set
  is the entire grammar. Native custom events ride the normal handler
  grammar (`event.detail` is `ui/event`'s job).
- `ui/spread-safe` is the literal safe-forward for component libraries —
  structural/controlled keys and owned `:on-*` are denied in the caller map
  in every build.
- `ui/error-boundary` is the explicit error component:
  `{:fallback view :reset-key val :on-error [:ev …]}` — catches
  render/lifecycle throws below it (not event-handler or async errors);
  changing `:reset-key` clears (retry = state change).

## Testing — `re-frame.ui.test`

**Six names across two hosts.** JVM structural (Tier-1, headless, the
default): `render`, `attrs`, `text`. CLJS mounted (Tier-3): `with-root`,
`flush!`, `flush-presence!`.

```clojure
;; (:require [clojure.test :refer [deftest is]]
;;           [re-frame.core :as rf]
;;           [re-frame.ui.test :as uit])

(deftest add-button-carries-intent
  (rf/with-new-frame [frame (rf/make-frame {:initial-events [[:rf/set-db {:cart #{}}]]})]
    (let [tree (uit/render [product-card {:product {:id 42 :name "Hat"}}]
                           {:sub-overrides {[:cart/locked?] false}})]
      (is (= [:cart/add 42]
             (-> (some #(when (= :button (:tag %)) %)
                       (tree-seq map? :children tree))
                 uit/attrs :on-click))))))
```

- `render` has **one grammar** — the literal view form, props carried in it —
  and **one option**, `:sub-overrides` (query vector → value, the explicit
  JVM override door). Frame scope is the ordinary bracket:
  `rf/with-new-frame` for a fresh owned frame, `rf/with-frame` to pin one.
  Drive state with `rf/dispatch-sync` and assert on a **fresh** `render`.
- **Traverse with ordinary Clojure**: `(tree-seq map? :children tree)` plus
  a predicate over `(:tag %)` (element) or `(:view-id %)` (view boundary);
  `filterv` for every match. There is no selector engine, by design.
- **Read through the projections**: `(uit/attrs node)` (attrs + events
  merged on elements; props on view-boundary nodes) and `(uit/text node)`
  (document-order text). A bare `(:on-click node)` is a field miss — events
  live under `:events`; `(:on-click (uit/attrs node))` is the idiom.
- **Tier-3, when DOM mechanics are the subject**: `with-root` mounts the
  literal root form into a connected test-owned container, awaits the
  initial commit, runs/awaits the body, and tears down on every exit. Query
  the container with native `.querySelector`/`.querySelectorAll`. `flush!`
  is the sole flush — the thunk form
  `(uit/flush! #(rf/dispatch-sync event {:frame f}))` runs inside awaited
  React `act` to a framework/React fixed point. In `cljs.test` use
  `async done` with explicit `.then` success and rejection callbacks.
- **The `.cljc` law**: Tier-1 needs the events/subs a view touches to be
  `.cljc` — the standard re-frame discipline, stated up front. Host-bearing
  ops (`ui/raw` children, `local` setters, `dispatch-fn` invocation) raise
  `:rf.error/jvm-host-op` on the JVM → use a mounted test.

## Errors, REPL, HMR

- The compiler and runtime throw **named, catalogued, didactic** errors.
  The error text teaches the fix — read it, fix the shape it names, never
  wrap around it. Every `:rf.ui.compile/*` id with its full message:
  [`references/ui-context.md`](references/ui-context.md).
- `defview` re-evaluation at the REPL re-registers; the HMR path IS the
  REPL path — no frame re-seeding, stable shells, durable state survives.

## When NOT to use / deep references

Routing is single-sourced at `skills/README.md` §Skill routing. In brief:
events, subs, effects, machines, schemas, and everything upstream of the
view → **`re-frame2`**; porting existing Reagent views onto **Freehand**, the
re-frame-native view layer this substrate is being absorbed into →
**`reagent-migration`** (optional; staying on Reagent/UIx/reagent-slim is
first-class); a running app to inspect or drive → **`re-frame2-pair`**.

**New view work does not start here.** This skill maintains what an app
already has on `day8/re-frame2-ui`.

Deep references, in the re-frame2 repo:

- [`references/ui-context.md`](references/ui-context.md) — the generated
  context sheet: authoring-surface disposition + the full compile-rejection
  roster (regenerated from the compiler; drift-checked in CI).
- `docs/core/re-frame.ui/` — the guide (mental model, build-a-view, state,
  events-and-handlers, interop-and-limits, testing, ssr, presence).
- `docs/core/how-to/install-re-frame-ui.md` — the full install recipe.
- `spec/004-Views.md` — the normative grammar.
