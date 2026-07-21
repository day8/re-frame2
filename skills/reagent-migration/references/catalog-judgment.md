# D-tier — "here's how to DECIDE" (judgment)

> This is where the skill earns its keep. The shelved codemod could only
> *flag* these; an AI **reasons** about the right shape. For each rule: the
> decision the source cannot answer, and how to make it. A view that trips
> any D rule is **held whole** — decide it with the author, then convert the
> whole view, or leave the whole view on Reagent (cardinal rule 2).

## MIG-16 — Form-2 / `with-let` view-local state

```clojure
;; before
(defn dropdown []
  (let [open? (r/atom false)]
    (fn [] [:div {:on-click #(reset! open? true)} (when @open? …)])))
```

**The decision: is this state PRODUCT meaning, or ephemeral UI state?** The source can't tell you; the domain can.

- **Product state** (a selected filter, a draft the app acts on, anything another view or an event cares about) → move it to **app-db**: a `reg-event` to write it and a `reg-sub` to read it. This is a dataflow change the skill *names* for the author (cardinal rule 5), not a view rewrite.
- **Ephemeral UI state** (a dropdown's open/closed, a hover flag — meaningful only to this one view) → the compiled host-local `local`:

```clojure
;; after (ephemeral)
(ui/defview dropdown []
  (let [[open? set-open! update-open!] (local false)]
    [:div {:on-click [... ]} (when open? …)]))    ; @open? → open?, (reset! open? v) → (set-open! v)
```

`local` returns a **three-tuple** `[value set! update!]`. Map `@a` → `value`, `(reset! a v)` → `(set! v)`, and — load-bearing — `(swap! a f args)` → `(update! f args)`, which applies `(f current & args)` to the *latest* host state so multiple same-turn writers compose. **Never** emit `(set-open! (f open? …))` over a committed render value — that is last-write-wins. `with-let`'s `finally` clause maps to `effect` cleanup **only for host teardown**; a `finally` that *dispatches* a domain event instead follows MIG-17 (there is no dispatch-at-unmount — re-home it to the causal events; see *Domain work on unmount* below). Setter/updater are host-only; a render-phase mutation fails loud.

## MIG-17 — Form-3 / `r/create-class` lifecycle

```clojure
;; before
(r/create-class {:component-did-mount     #(focus! …)
                 :component-will-unmount   #(dispatch [:resource/release])
                 :should-component-update  …
                 :reagent-render           (fn [] [:div …])})
```

**The decision: decompose each lifecycle body into host work vs domain work.** Mechanical parts first: delete `:should-component-update` (memo-by-default makes it dead), and extract `:reagent-render` as the view body (the other rules apply to it). Then, per lifecycle body, split by *what the body does*:

- **Host / DOM work** → split by *timing*, because `component-did-mount` fired **before paint** and the passive `effect` fires **after** it. Ordinary listeners and genuinely deferrable host work (wire a listener, attach a resize observer) → `(effect :connect …)`; its signature is a trap — read **the `effect` signature** below before you emit one. But work whose **initial DOM/visual state must exist before the browser paints** — reading a node's geometry to place a popover/dropdown, sizing a table viewport — must NOT move to the passive `effect` (it would land a frame late and flicker). Route that to **`ui/ref` + `re-frame.ui.react/use-layout-effect`** — the measure-before-paint door (see **below**). **Classify focus and chart setup by that same timing test, not by filing them under one door:** an initial focus ring, scroll position, or first chart draw that must be on screen at first paint belongs in the layout effect; the same setup, when deliberately deferrable, stays on the passive `effect`. (Loading data is **not** host work — a "load on mount" is a domain event through the dataflow, next.)
- **Domain work on MOUNT** ("mark viewed", "load on mount") → a route/domain **event** through the dataflow (name it for the author). There is deliberately **no** `:on-mount` primitive; domain-on-mount is a dispatch through the dataflow, not a lifecycle hook.
- **Domain work on UNMOUNT** ("release a held resource", "mark the draft abandoned") → **re-home it OUT of the view** — see **Domain work on unmount** below. There is **no dispatch-at-unmount** in native re-frame.ui; you do **not** preserve it as an `effect` cleanup.

### The update, snapshot-pairing, and error-boundary roles

The mount/unmount split above covers `:component-did-mount` and `:component-will-unmount`. Three more lifecycle keys are supported (re-com's Form-3 sites use `:component-did-update`; scroll restoration uses the snapshot pair; audited apps use `:component-did-catch`), and each needs its **own** routing — the generic "host work → `effect`" advice does not cover them safely: an update hook needs explicit dependency semantics, the snapshot protocol is a paired pre-/post-commit dance with no passive-effect translation, and an error boundary has its own shipped form. This mirrors the reagent-slim [`FORM-3.md`](../../../implementation/adapters/reagent-slim/FORM-3.md) §6 decision table cell-for-cell:

| Reagent Form-3 lifecycle | Phase & frequency | Native re-frame.ui target |
|---|---|---|
| `:component-did-update` | after every commit but the first, per update | **Re-feed an imperative library on a prop change** → the dependency-keyed `(effect [deps…] …)` (the **`effect`'s signature** section below — it runs its body after commit whenever a dep changes, compared by `rf=`; keep deps narrow). This is where a library-update recipe lives (finalize the old view, embed the new). If the hook exists **only** to read changed data and re-render, the data is a **subscription** and the view is Form-1 — the lifecycle disappears (name the `reg-sub` for the author, cardinal rule 5). |
| `:get-snapshot-before-update` + `:component-did-update` (paired) | measure the previous DOM pre-commit → restore post-commit | **No native passive-effect or hook equivalent — stays on reagent-slim Form-3.** `use-layout-effect` (the pre-paint door above) runs *after* React has mutated the DOM, so it **cannot** read the pre-mutation geometry; the pre-commit half of the protocol has no native door. This paired protocol (scroll restoration) is exactly why the 7-key cap keeps `:get-snapshot-before-update` — hold the **whole** view (cardinal rule 2). |
| `:component-did-catch` | on a descendant render/lifecycle throw | The shipped **`ui/error-boundary`** `{:fallback :reset-key :on-error}` when its logging/recovery semantics fit: `:fallback` renders with `:error` (the stateful fallback, which cannot recursively dispatch), `:on-error` dispatches a domain event **after** the failing commit, and changing `:reset-key` (compared `rf=`) clears the caught error (retry). Otherwise the view **stays on reagent-slim Form-3** (`:component-did-catch` + a local `r/atom`) or is redesigned. React catches only render/lifecycle throws **below** the boundary — not event-handler or async errors, which keep their typed paths. |

The two "stays on reagent-slim Form-3" cells are honest **holds**, not gaps in the skill: the snapshot protocol and (when `ui/error-boundary` does not fit) the error boundary have no faithful native passive-effect translation, so the class shape is the answer — which is exactly why the `create-class` cap keeps those keys.

### Measure/mutate before paint → `ui/ref` + `use-layout-effect`

Only for the narrow pre-paint case (geometry read, DOM mutation the layout depends on), and only when a passive `effect` would visibly flicker. The node ref is the substrate-native `ui/ref`; the layout-effect hook lives in `re-frame.ui.react` (`[re-frame.ui.react :as react]`) and takes React's own `setup`→cleanup shape (a `setup` fn returning a cleanup fn or `nil`) — *unlike* `ui/effect`'s body-return shape:

```clojure
;; before — measurement in :component-did-mount (pre-paint), Form-3
(r/create-class
 {:component-did-mount (fn [this] (place! (rdom/dom-node this) (measure anchor)))
  :reagent-render      (fn [] [:div.popover …])})

;; after — pre-paint semantics preserved by the layout effect, node via ui/ref.
;; BOTH the ref and the hook are outer-let BINDINGS — `ui/ref` is bound in the
;; defview's top-region let, and a host hook is a binding value, never a second
;; statement in the let body. A hook call left in the body makes the let a
;; two-form body and FAILS compilation with :rf.ui.compile/multi-form-body at
;; [:body]; the single template is the only form the body may hold.
(ui/defview popover [{:keys [anchor]}]
  (let [el (ui/ref)
        _  (react/use-layout-effect
             (fn [] (place! (.-current el) (measure anchor)) nil)   ; runs BEFORE paint; nil = no cleanup
             [anchor])]
    [:div.popover {:ref el} …]))
```

Everything else — listeners and deferrable host work — stays on the passive `effect` below (focus and chart setup follow the timing test above: the layout-effect door only when the initial state must exist before paint). This is a targeted door, not a hooks-migration framework.

### `effect`'s signature (verify against `ui.cljc` — REQUIRED)

`effect` is a compiler-owned form whose contract is *unlike* React's `useEffect`, and it is documented only in `re-frame.ui`'s source. **Read the `effect` docstring in `implementation/ui/src/re_frame/ui.cljc` before you emit one** (the framework's `effect` var carries the authoritative arglist + cleanup semantics). The shape is easy to get subtly wrong in a way that *compiles but silently never runs the cleanup* — a leak with no error.

Two forms, both a **leading statement** in a `defview`'s top region (before the final template):

```clojure
(effect [dep …] body…)     ; runs body after commit whenever a dep changes (compared by rf=)
(effect :connect body…)    ; runs body at each connect (mount / reveal); cleanup at each disconnect
```

**The body forms run directly** — you do *not* pass a setup function. **The body's RETURN value, when it is a function, is the cleanup.** So the cleanup is a *trailing* `(fn [] …)`, not an inner lambda:

```clojure
;; RIGHT — the setup forms run at connect; the trailing fn is the cleanup
(effect :connect
  (let [node @ref
        on-key (fn [e] …)]
    (.addEventListener node "keydown" on-key)
    (fn [] (.removeEventListener node "keydown" on-key))))   ; ← body's return = cleanup

;; WRONG — the React useEffect shape (a setup fn that RETURNS a cleanup fn).
;; The body evaluates to a single function, so the framework registers THAT
;; function as the cleanup and NEVER runs your setup at connect. Compiles,
;; leaks silently — no error.
(effect :connect
  (fn [] (.addEventListener node "keydown" on-key)
         (fn [] (.removeEventListener node "keydown" on-key))))
```

`:connect` cleanup runs at each *disconnect*, not once at unmount, and dev StrictMode replays connect/disconnect — so the cleanup must be idempotent (the **StrictMode idempotency** gotcha, [`gotchas.md`](gotchas.md)). `sub`/`frame` inside an effect body are compile errors. To dispatch from an effect, capture `(ui/dispatch-fn)` in the view body and call it — **but that dispatcher fails loud at disconnect**, which is exactly why unmount domain-work re-homes (next).

### Domain work on unmount re-homes OUT of the view (no dispatch-at-unmount)

A Reagent view that dispatched a domain event from `:component-will-unmount` (release a held resource, mark a draft abandoned) has **no compiled equivalent, by design**. Native re-frame.ui has **no dispatch-at-unmount**. The framework's own words on the committed dispatcher (`ui.cljc`, `dispatch-fn`):

> *… it FAILS LOUD in every non-connected state (`:rf.error/dispatch-disconnected`) — the leaked-listener detector for an external callback that outlived its view.*

An unmount cleanup fires while the view is disconnecting, so a `(ui/dispatch-fn)` call there is rejected — the **leaked-listener law**. You cannot dispatch domain work at unmount, and an `effect` cleanup is **not** a place to smuggle it in.

**So re-home the resource lifecycle out of the view.** The canonical `re-frame.ui` editor reference captures the doctrine:

> *The article read is a DATAFLOW concern that the ROUTE owns … `:realworld.editor/edit` declares `:realworld/article` as a `:resources` entry (routing.cljs), so the runtime marks it active under the route owner `[:route :realworld.editor/edit nav-token]` on entry and RELEASES that owner on every route leave … So the native view owns no resource lifetime — there is nothing at the view tier to leak — and the resource lifecycle lives in the route's `:resources`, released on every exit.*

The pattern, abstractly: the resource was minted by a *causal event* (a route match, a "start editing" event), and it is released by an owner that covers **every** exit path — **not** by the view's teardown. That completeness is the discipline: a D-tier view stays held until each old teardown responsibility has a **proven** new owner; naming a plausible end event is not completion — enumerate every exit and prove each one releases. The RealWorld editor is the corrected reference (`examples/real-apps/realworld_resources/{routing,article_editor,ui_editor}.cljc`): ordinary *route leave* (edit→home, edit A→B, save) fires no `:editor/*` event, so an enumeration that stopped at delete/finish would leak. Ownership re-homes to the **route** — the article read is a `:resources` entry the runtime releases on every route leave, one framework lifecycle covering every exit. The migrated view becomes a **pure render** of subs; it owns no resource lifetime, so there is nothing at the view tier to leak.

```clojure
;; before — a Form-3 view owns the resource lifetime and releases it at unmount
(defn editor []
  (let [{:keys [dispatch]} (rf/capture-frame)]
    (r/create-class
     {:component-will-unmount (fn [_] (dispatch [:resource/release]))
      :reagent-render         (fn [] [editor-form])})))

;; after — the view owns no resource lifetime; it is a pure defview.
;; The resource lifetime RE-HOMES to the dataflow: a route / "start" event MINTS
;;   the owner (e.g. a route `:resources` entry, or an app-minted event owner such
;;   as [:editor/opened id]); an owner that covers EVERY exit RELEASES it — for a
;;   route-scoped read that owner is the route's `:resources` (released on every
;;   route leave), not a hand-picked set of end events. Name/scope that owner for
;;   the author (cardinal rule 5) — the skill does not write it, it scopes it.
(ui/defview editor [] [editor-form])
```

**re-frame.ui has no view-owned resource lifetime, by design** — there is no component-presence resource owner, and liveness never re-enters through a view's mount/unmount. So the owner is always **causal**: a route (a `:resources` entry, released on every route leave), a machine (`[:machine actor-id]`, released on actor destroy), an SSR request (`[:ssr request-id nav-token]`), or a **semantic app event** that mints an app-kind owner (e.g. `[:editor/opened id]`) with a matching `:rf.resource/release-owner` on the event that ends it. Which causal owner fits is an **ownership-model decision** to make with the author, not a mechanical migration — and routes/events/machines remain the preferred owners (Spec 004, I-11; [Spec 016 §Release authority](../../../spec/016-Resources.md)). When a read genuinely has **no causal event bounding its life**, the valid tool is an **ownerless `ensure`** — cause-only, carrying no `:owner`, so the entry stays GC-eligible (focus/reconnect revalidate it; it is reaped once inactive) — never a view that owns a lifetime. Either way, **the view never dispatches at unmount and owns no resource lifetime.**

## MIG-18 — non-conforming `:on-*` handlers

A DOM/custom-element `:on-*` handler whose body **fails the three clean shapes** (MIG-04/05/06) — mixed local work plus a dispatch, a *guarded* dispatch, a dispatch of a non-literal / helper-routed vector, or pure imperative work with no dispatch at all:

```clojure
;; before — mixed local work + dispatch
{:on-click (fn [e] (.preventDefault e) (when ok? (dispatch [:save])))}
```

**The decision: split local work from app intent, then pick the compiled form.** A converted `defview` has **no ambient `dispatch`** in scope, so this is not a one-line lift:

- **A bare fn stays legal** on a DOM `:on-*` (the narrow bare-fn law). But imperative dispatch *inside* that fn obtains the committed dispatcher via **`(ui/dispatch-fn)`** in the view body (per-view stable; reads the committed frame at call time; fails loud with `:rf.error/dispatch-disconnected` when the view is disconnected).
- **A guarded or payload-extracting dispatch** → **`ui/event`**: a `nil` return means *no dispatch*, which is exactly the guard (`when ok?`) expressed as data-with-a-filter.
- **Pure imperative work** whose return is irrelevant → **`ui/handler`**; work that computes and **returns a vector to dispatch** → **`ui/event`**.
- **Mixed**: the local work stays a fn; the app intent becomes a vector on the natural element.

Coupled to MIG-16's state decision (local work often reads/writes view-local state), which is why a MIG-18 hit **gates the view** — decide it whole with the author.

## MIG-23 — SSR (`render-to-string` / hydrate)

```clojure
;; before
(reagent.dom.server/render-to-string [app])
```

**The decision: which SSR path?** re-frame.ui ships the **static-page** path end-to-end; the **SSR-then-hydrate** path has shipped its **client adoption half only** — pick by intent, and hold the hydrate path if you need the server half today:

- **Static-page (non-hydrating) HTML** — the compiled counterpart of React's `renderToStaticMarkup` → **`(ui/render-static [app-root])`**. Pure `:server` phase, no manifest, no hydration payload; a `ui/client-only` site renders its capability-free fallback and stops. JVM/server only; the root form must be literal. **This arm is shipped end-to-end.**
- **SSR-then-hydrate — client half shipped, server half not yet.** The client adoption half is real: **`re-frame.ssr/hydrate!`** (seed state, no `:render-tree-fn` for a compiled root) **then `ui/hydrate-root`** (per Spec 011), and the `reagent.dom.client/hydrate-root` mount from MIG-15 routes here. But the **server** half is not a supported public path yet: `re-frame.ssr/emit-ui-tree` emits one root's **markup only**, and the page-assembly that renders the root *and* emits its adjacent Root Manifest (the response-local page registry a hydrating client needs) lives in an internal namespace under a separate, still-open owner. So a compiled **hydrating** root has **no shipped public server page-assembly path today**. **Hold** an SSR-then-hydrate migration — keep it on Reagent's `render-to-string` — until that server owner lands; the static-page arm above is unaffected.

Keep the caveat: views using refs/effects need `ui/client-only` (or restructure) for the server phase. (`ui/render-static` and `ui/hydrate-root` shipped; the still-missing SSR piece is the public server page-assembly / Root-Manifest path for a hydrating root — verify any SSR helper against `re-frame.ssr`'s exports before assuming it exists.)

## MIG-19 — derived state (`r/track` / `r/cursor` / `reaction`)

```clojure
;; before
(let [x (r/track compute a)] [:div @x])
```

**The decision: name the sub, design its query, and rewire the call sites.** The compute fn copies almost verbatim into a `(reg-sub :ns/name …)`; the read site becomes `(sub [:ns/name a])`. You gain caching + Xray visibility. Two things need real thought:

- **A `r/cursor` has a WRITE side** the read-rewrite must not leave behind. `(reset! the-cursor v)` is silent mutation from anywhere; each write site needs a named event (`[:ns/set-b v]`). Enumerate the write sites alongside the reads — naming them is the point of the move.
- **A `track` capturing a component-local value** can't become a global sub verbatim — parameterise the captured value into the query vector.

## MIG-20 — ratom-as-store (a second state model)

```clojure
;; before
(def app-state (r/atom {}))         ; top-level, read by many views
(add-watch app-state :k (fn [_ _ _ v] …))
```

**The decision: this is a *second state model* — restructure it into app-db.** There is no direct re-frame.ui equivalent, and that is deliberate: a shared top-level ratom (and `add-watch`/`track!`/`run!` reactions driving effects) was a **latent concurrency bug in Reagent too**. The move is a *causal-event* restructure: the reads become subs, the writes become events, and an `add-watch` becomes the event handler that made the change (the handler carries the consequence) — **not** a mechanical rewrite to `effect`. A watcher fires *synchronously mid-`swap!`, before render*; an effect runs *after commit* — swapping one for the other is a behaviour change no diff review catches. **Compat escape:** the views on this store stay on Reagent until the store is restructured. This is a dataflow re-model the skill scopes and names; the author does it.

## MIG-28 — computed / dynamic DOM props → `ui/spread-safe` or `ui/spread`

**The decision: which spread?** For a DOM / custom-element props map re-frame.ui ships an *ergonomic fork* — reach for the safe form first, and drop to the generic one only when the props are genuinely opaque. (`ui/spread-safe` is DOM/custom-element only; the generic `ui/spread` *also* serves a foreign component call site — the wrapper idiom documented at the end of this section.)

- **Literal owned props + a caller-forwarded attr map → `ui/spread-safe`** (the component-library shape, and the ergonomic default). `(ui/spread-safe owned caller)` takes a **literal** `owned` map — the compiler analyses it exactly like an element's props map, so a literal `:value`/`:checked` co-present with its handler **retains the controlled-input synchrony door** — and merges the runtime `caller` attrs over it. A compiler-visible **deny law** (enforced in *every* build) keeps `caller` from clobbering the structural/controlled/owned keys `:key` `:ref` `:value` `:checked` and the component's own `:on-*` handlers; owned props win a collision and `:class` composes. This is the shape a reusable input/control uses to forward a consumer's `data-*`/`aria-*`/`:class` without losing its own guarantees:

```clojure
;; before — a control forwards the caller's attrs onto its <input>, holding its own value + handler
[:input (merge attrs {:type "text" :value (:title draft) :on-input on-input})]
;; after — owned props literal (sync door KEPT); caller attrs spread safely (deny law applies)
[:input (ui/spread-safe {:type "text" :value (:title draft) :on-input on-input} attrs)]
```

- **A genuinely opaque runtime props map → `ui/spread`** (the visible-cost escape). `(ui/spread base overrides)` is the one *generic* runtime conversion for a map the compiler cannot see into. It **forfeits the static manifest row *and* the controlled-input synchrony door** (which needs a provably-literal `:value`/`:checked` on the element). Weigh it knowingly: shrink the spread to genuinely pass-through props and lift `:value`/handlers back to literals where you can — or, if the owned/forwarded split is clean, prefer `ui/spread-safe`.

**Internal-view call sites stay literal-map (MIG-01), never a spread** — an internal view needs the literal keys for its per-slot memo comparator. A **foreign** component call site is the exception: it accepts `(ui/spread literal-part runtime-map)` (the wrapper idiom — the literal part compiles normally, the forwarded map passes through opaque, an internal view still rejects it with `:rf.ui.compile/spread-internal-view`). See the **bare-symbol trap** ([`gotchas.md`](gotchas.md)) — a bare symbol *child* is content, never a spread.

## MIG-22 — third-party Reagent wrapper components (re-com et al.)

```clojure
;; before
[rc/single-dropdown {:choices cs}]
```

**The decision is per-library, and these are the LAST movers.** A third-party Reagent component (re-com, a charting wrapper) has no compiled counterpart. Two honest options, chosen with the author:

- **Keep the subtree on Reagent** (the default) — a fully-supported mixed page.
- **Embed it under the boundary form** `(ui/raw (r/as-element [rc/single-dropdown {…}]))` — same root, with the frame-scoping/teardown rules of the boundary contract.

The outward direction (a compiled view *inside* a Reagent/foreign React tree) is the **shipped** `ui/->react` bridge — no longer a hold. Export the converted leaf once, `(def CartRow (ui/->react cart-row))`, and render `CartRow` from the unconverted parent like any component. The exported component is memoised per view identity (a parent re-render never remounts it), creates no new root/manifest/preflight, and resolves its frame from the ambient chain — a `rf/frame-provider`/`rf/frame-root` above it in the Reagent tree (the shared React context object spans both tiers), or a supplied `frame` prop. Props map to the view's ABI slots by exact name; `children` and `ref` pass through. This makes the leaf→root ordering ([`procedure.md`](procedure.md)) a *recommended default* (fewer boundary wrappers, subtrees stay pure `ui`) rather than a hard constraint — a converted view that MUST be called from a view staying on Reagent now has an honest spelling.

## The other judgment calls (decide, then hold-or-convert whole)

| MIG | Construct | The decision |
|---|---|---|
| **MIG-03** | `@(subscribe [:q] {:frame f})` / explicit-frame op | No arity-1 `sub` frame-pin is exported yet. Scope the subtree with `ui/frame-provider {:frame f}`, or hold the view. (Capability gap → R-tier.) |
| **MIG-08** | unkeyed `for`; `sub` in a loop; loop-capturing handler | Extract a **keyed child view** (per-row instances). Missing key = build failure; a `sub`/capture in a loop = compile error. The tool never does this structural move — you do, with the author. |
| **MIG-10** | fn-valued prop at a **foreign** component boundary | Choose the callback form: identity/ref → `(ui/raw-fn f)`; needs the event/payload → `ui/event`; imperative/stable-identity → `ui/handler`; pure render prop → `ui/render-fn`. Event *vectors* are not a foreign-boundary form. |
| **MIG-13** | markup-returning `(map (fn …) xs)` in child position | Rewrite to a keyed `for` (`(for [t ts] [item {:key (:id t) :t t}])`) — mechanical only when the fn is a literal with a keyed hiccup body; confirm the candidate. |
| **MIG-26** | ambient `subscribe`/`dispatch` in a plain unregistered `defn` | Grep for these — they throw `:rf.error/no-frame-context`. Preference order: (1) register as a view; (2) hoist the op to the nearest registered ancestor and pass values down; (3) explicit `{:frame f}`. |
| **MIG-27** | fn-valued prop on an **internal-view** call site | **Legal and opaque** (a plain fn prop is an identity-compared value — *not* a compile error; non-gating). *Recommend*, don't force: forward a data vector where you want tool-visibility (`:on-commit [:commit]`, child places it at its DOM `:on-*` site), or `ui/handler`/`ui/render-fn` where a phase/stable-identity is genuinely needed. |
| **MIG-30** | runtime-built markup helper (`(md/render …)` walking an AST) | No compiled spelling for runtime hiccup *data*. Template-ise the callee into `defview` branches (then MIG-01 applies), or hold the view on Reagent. There is **no `re-frame.ui.data` today** — the runtime UI interpreter is a reserved future wave-2 artifact (a *possible future home*, not a namespace you can require now). |

Every row above is a view the skill leaves whole until the decision is made. Decide it, then convert the whole view or hold the whole view — never a partial body.
