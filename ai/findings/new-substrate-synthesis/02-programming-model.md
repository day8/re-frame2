# 02 — Programming model: `defview`, templates, props, handlers, interop

**Status:** final · 2026-07-11 · codex2 fold-in 2026-07-12 (Findings 5/8/9). Namespace
`re-frame.ui` (alias `ui/`). Body forms `sub` / `local` / `effect` / `lease` / `frame`
referred bare for readability.

## 1. `defview` — the one component form

```clojure
(ui/defview product-card
  "One product tile."
  {:props [:map
           [:product [:map [:id :int] [:name :string] [:price :double]]]]}
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

- **Zero or one argument, semantically a props map.** Header destructuring (`:keys`,
  namespaced `:x/keys`, `:or`, explicit bindings) lowers to direct property reads on the
  host props object — no CLJS map at entry. `:as` opts into materialization + generic
  comparison (dev cost note). No positional args.
- **Props ABI:** each keyword maps to a
  deterministic quoted JS property name preserving namespace+name; it cannot collide with
  React's `key`/`ref`/`children` slots. **`:key` is reserved** (feeds React's key slot);
  an app prop literally named `:key` is a compile error. `children` compares as one slot.
  The manifest maps compact production slot indexes back to keywords.
- **Options map (closed for v1):** `:props` (Malli — literal call sites checked at
  compile time, dynamic at dev runtime, elided in prod), `:id` (registry override),
  `:display-name`. **Deliberately absent:** `:memo false` (no demonstrated
  consumer — mutable foreign values belong at an explicit boundary); `:on-mount` /
  `:on-unmount` (domain events cannot ride mechanical React lifecycle — StrictMode
  replay, Activity, HMR, and error recovery make "once"/"viewed" semantics unrecoverable;
  domain visibility belongs to route/domain transitions, host sync to effects);
  `:catch`/`:fallback` (error handling is an explicit component — §6).
- `defview` defs a Var **and registers in the registrar** (`:view` kind: source metadata,
  template fingerprint, hook signature, capability bits). Xray lists views by registry
  query; **Story mounts scenes by view id** (render-keys are instance ids, allocated at
  mount); the Pair hot-swaps a view like an event handler.
- Every internal view is **memoized** on a generated straight-line `rf=` comparator over
  its declared prop slots.

## 2. Template grammar

Reagent-familiar hiccup, ambiguities removed. Control forms (`let`/`letfn`/`if`/
`if-not`/`when`/`when-not`/`cond`/`case`/statically-pure `do`/`for`) normalize **into the
AST**; all analyzers and both emitters see through branches.

| Form | Meaning |
|---|---|
| `[:div.cls#id {…} …]` | DOM element; literal head required |
| `[view-sym {…} & children]` | internal view (compile-resolved Var) |
| `[ForeignComponent {…} …]` | foreign React component (open props; JS values pass through) |
| `[:<> …]` | fragment |
| `(for [x xs] [item {:key …}])` | keyed list → direct JS array; missing key = build failure |
| `(ui/presence …)` | declarative enter/exit retention (§7) |
| strings/numbers/nil/false | text / nothing |

Rejected at compile time (didactic messages naming the escape): dynamic tag heads;
markup-returning `map`; keywords in child position; raw lazy seqs; unkeyed list items;
`sub`/`lease` in loops (extract a keyed child view — sites must be finite).

**DOM prop spelling is pinned:** hyphenated lowercase words mirroring React's camelCase —
`:on-click`, `:on-key-down`, `:on-input` (never `:on-keydown`). Handler-map options:
`{:event […] :prevent-default true :stop-propagation true :capture true :passive true
:once true}` — the DOM listener vocabulary is explicit, not implied.

**Prop conversion is compile-time, contextual, and total**: DOM attribute casing,
`:style` maps (keyword values stringify), `:class` string/vector/map-of-flags; component
props pass through untouched. One rule table serves static props, the single dynamic-map
conversion fn, and both emitters. **No `#js` on compiled DOM/internal paths** (foreign
React interop may still hand raw JS values through — that's the boundary's job).

**Custom elements** (tag contains `-`): a bounded classification rule — literal props
compile to properties when the name matches a declared property (per an optional
`ui/custom-element` declaration) else attributes; booleans/`:class`/`:style` follow DOM
rules; native custom events via the normal handler grammar. Not forced through `ui/raw`.

## 3. Event handlers

**Canonical: the event vector.** A vector in an `:on-*` position is the event intent,
dispatched to the committed frame:

```clojure
[:button {:on-click [:cart/add id]} "Add"]
[:input  {:on-input [:form/typed :email :rf.ui/value]}]
[:input  {:type :checkbox :on-change [:prefs/set :dark :rf.ui/checked]}]
```

**Placeholder vocabulary (closed, v1, scalars only):** `:rf.ui/value`, `:rf.ui/checked`,
`:rf.ui/key`. Placeholders splice at top-level positions of the vector at dispatch time.
*(`:rf.ui/form-data` and `:rf.ui/event` do not exist — form payloads carry
duplicate keys/files and are not EDN; a raw event is a host object. Both cases belong to
`ui/event`, which exists precisely for them.)* Vectors with only literal/placeholder
content are **data**: value-comparable, statically inspectable, JVM-testable, and
retained as data in the manifest and JVM tree. On the client they lower to normal React
handlers (per-site stable, committed-slot reading). *No handler attributes are emitted
into HTML and no resumability is claimed — see 06 §4.*

**The decision table:**

| Form | Invoker → phase | Identity | Sees | Serializable | Use for |
|---|---|---|---|---|---|
| `[:event … :rf.ui/value]` | DOM → after commit | per-site stable | committed slots + frame | **yes** | intent (the 90%) |
| `(ui/event [e] … [:vector …])` | DOM/foreign → after commit | per-site stable | committed slots + the live event | no | event mechanics, form/file payloads, filtering (`nil` ⇒ no dispatch) |
| `(ui/handler [x] …)` | foreign → after commit | per-site stable | committed slots | no | imperative work, stable-identity change-callbacks |
| `(ui/render-fn [x] …)` | foreign → **during its render** | none promised | current render | no | item-key/comparator/render props; pure — no dispatch/sub/lease/hooks |
| bare `#(…)` in a **known native event property** (`:on-*` on DOM/custom elements) | DOM → after commit | per-site stable | its closure (committed render's values) | no | shorthand for `ui/handler` — legal because invoker+phase are known. **Only there**: not refs, not arbitrary fn-valued props |
| bare fn at a **foreign-component** boundary | unknown | unknown | unknown | no | **compile error** — choose `ui/event`/`ui/handler`/`ui/render-fn`/`ui/raw-fn` |
| `(ui/raw-fn f)` | foreign, identity-as-protocol | passed through | its closure | no | APIs that treat callback identity as data; **also the callback-ref form** (below) |

**Committed slots include `local` values (ruled 2026-07-12).** A same-view committed
handler — data vector, `ui/event`, `ui/handler`, or the DOM bare-fn shorthand — may read
a `local` value: handlers read committed slots, and a view's local ephemera are committed
slots of that view. The guide's search-box seam (local keystroke text flowing into a
`[:search/run text]` data handler) is canonical and conforming. The placement boundary
rides the *value*, not the handler — §5 states when a value is forbidden from `local`.

A day-one **strict lint** exists: `{:re-frame.ui/bare-handlers :warn}` (or `:error`) lets a
team adopt explicit-everywhere as policy without a language change — the language itself
stays permissive (R-4; flipping the language later would break source).

**Refs:** `:ref` is a reserved React
slot, never an event property — the bare-fn shorthand does **not** apply. Object refs are
preferred. A callback ref must be explicit `(ui/raw-fn f)`: React invokes callback refs
during commit *before* the owning view's layout publication, so no committed-slot promise
can be made — the explicit form is what marks that. Internal views forward `:ref` only by
declaring it; refs never appear in event vectors or SSR output.

**Dynamic handler expressions.** Handler-position expressions are legal
(`(if in-cart? [:a id] [:b id])`, a prop-forwarded `event`). Classification is: literal
forms classify at compile time; non-literal values classify **at runtime by type**
(vector → dispatch; map → options form; compiled handler object → itself; fn → per the
boundary rules above; nil → no handler). Two consequences, stated honestly:
**placeholders are compiled, so they are recognized in literal vectors only** — a
placeholder keyword inside a runtime-forwarded vector dispatches as an ordinary keyword
argument, and dev warns when it sees one ("placeholder in dynamic vector — build the
literal at the DOM site or use `ui/event`"). And manifests mark value-classified sites
`:dynamic` — Xray's static interaction surface covers literal and normalized-branch sites
and says "dynamic" for the rest.

**Loops.** A **capture-free** literal vector handler in a `for` body is legal and shares
one callback across rows (`[:li {:key id :on-click [:list/refresh]} …]`). A vector that
**captures the loop binding** (`[::open (:id t)]`) is a compile error with the
extract-a-keyed-child-view fix — per-row committed slots need per-row instances. The same
rule covers `ui/event`/`ui/handler` in loops (they are sites too). Bare fns in loops get
the same diagnostic as a dev *warning* (they work, at per-row closure cost, and defeat
the data idiom — the nudge is deliberate).

**Controlled inputs — the synchrony law.** Dispatches from
`:on-input`/`:on-change`/`:on-before-input` sites on **controlled** DOM elements drain
**synchronously within the DOM event** — event → drain → commit → snapshot advance before
React's discrete-event re-render — so value round-trips can't drop characters, jump the
caret, or break IME composition. This is the one sanctioned synchronous door; everything
else batches per I-6. G-8 (07 §5) pins caret/IME correctness first, latency second.
**The trigger predicate (S-5-confirmed, 2026-07-12):** the door applies where the
compiler can *prove* the element controlled — a literal `:value`/`:checked` prop
co-present on the same element as the vector-handler site. Dynamic props maps,
`ui/spread`, and `ui/event`/bare-fn dispatches at such sites fall back to standard
batching with a dev diagnostic naming the sync-door conditions. The S-5 spike confirmed
this predicate is sufficient and it is kept unchanged; what remains open is the named
gate G-8 (07 §5) — the real-browser matrix (Chromium/WebKit IME, caret restore, event
ordering, pre-paint) has not yet run.

Dev safety nets: data handlers with unregistered event ids warn at render with the
element's coordinates (the registrar is **process-global** — frames isolate state, not
behaviour; a lazily-loaded module that registers later can produce a false positive, so
the warning names that possibility).

## 4. Reactive reads (contract in 03)

`(sub [:query …])` returns the value; a compiler-indexed site in the view's single store
binding. Conditional reads legal; loops rejected (finite sites).

## 5. Local state, effects, leases (contract in 03)

```clojure
(let [[text set-text] (local "")] …)     ; host component-local state — precisely that
(effect [node series] (draw! node series) #(destroy!))   ; rf= value deps; cleanup fn
(effect :connect (subscribe-external!) )  ; runs at each connect; cleanup at disconnect
(lease {:resource :article/by-slug :params {:slug slug}})
```

`local` is **host component-local state, deliberately outside re-frame2 epochs** — not a
future frame feature wearing a substrate name (there is no frame-resident variant and
none is reserved; if frame-resident ephemera is ever built it will be a new name with its
own semantics). `effect`'s connect form is named for what it does — there is no "once" in
React's lifecycle, and pretending otherwise is the `:on-mount` bug.

**The placement law (ruled 2026-07-12):** a `local` value MAY be read by same-view
committed handlers — handlers read committed slots, local ephemera included; the guide's
search-box seam (local keystroke text inside `[:search/run text]`) is canonical and
conforming. `local` is FORBIDDEN when the value needs cross-view observation,
replay/persistence, schema or tool inspection, durable navigation semantics, or
subscription-derived computation — those belong in app-db behind an event.

## 6. Interop and boundaries

- `(ui/raw react-element)` — embed an existing React element (child position; SSR needs a
  `client-only` sibling fallback).
- `[ForeignComponent {…}]` — foreign heads; callbacks per the §3 table.
- `(ui/->react view)` — export a view as a React component. **v1, lands S6 with the
  migration wave** *(ruled 2026-07-12)* — it is the outward bridge that per-subtree
  migration from Reagent rides (doc 10).
- `(ui/element type props & children)` / `(ui/view id)` — runtime-chosen components
  (**wave-2**, demand-gated — not in the v1 surface; `ui/raw` covers a runtime-chosen
  head meanwhile, and `ui/view` in production requires production registry entries —
  dev-only string ids can't serve dynamic prod lookup).
- `(ui/spread base overrides)` — the one generic runtime prop-map conversion (v1).
- `(ui/portal node child)` — React portal; frame context passes through (**wave-2** —
  not in the v1 surface).
- `(ui/client-only {:fallback tpl} client-tpl)` — browser-only subtree with a mandatory
  capability-free JVM/first-hydration fallback (06 §3).
- **`(ui/html string)` — trusted markup, low-friction.** Renders the string as HTML,
  explicitly. The spelling *is* the contract: we trust the programmer; the visible call
  marks the one place escaping is bypassed, manifests record the site, and both emitters
  treat it identically. No token ceremony. (Strings anywhere else always escape.)
- **`(ui/error-boundary {:fallback view :reset-key val :on-error [:ev …]} child)`** —
  the explicit error component. Semantics stated:
  catches render/lifecycle throws below it (React does not catch event-handler or async
  errors — those keep their own typed paths); `:on-error` dispatches **after** the failing
  commit through a captured live frame (never during render, I-1); fallback renders with
  `:error` + declared props and cannot recursively dispatch; changing `:reset-key` clears
  the caught error (retry = state change that changes the key); JVM/SSR renders the child
  per the server failure policy (06 §1) — boundaries are a client recovery mechanism.
- **`re-frame.ui.data`** (separate artifact): the interpreter for runtime-authored UI.

**Roots.** `(ui/mount root-form dom-node)` is a **macro over a literal root form** — the
compiler must see the root to keep the AST closed; a runtime-assembled vector is a
compile error pointing at the escapes (`ui/raw` in v1; `ui/view`/`ui/element` when the
wave-2 surface ships). Hosts needing control:
`create-root` / `render!` / `hydrate-root` / `unmount!`. Root opts carry the identity
keys `:root-id` / `:disambiguator` / `:identifier-prefix` (compile-time literals;
`root-id` is required identity with a derivation default from the mounted view's id)
plus the host error callbacks — the full contract is
[drafts/root-identity-and-mount.md](drafts/root-identity-and-mount.md); frame wiring is
the template's job — the root-manifest contract lives in 06 §2. `ui.test/flush!` is the
only test flush (07 §2).

## 7. Presence — declarative enter/exit

Replicant-equivalent capability, React-ownership terms, deliberately bounded — a
*presence* primitive, not an animation system:

```clojure
(ui/presence {:timeout-ms 300}
  (for [t toasts]
    [toast-card {:key (:id t) :toast t}]))
```

- Keyed children pass `:mounting → :present → :unmounting`; an exiting child stays
  mounted until its **transition/animation completes**, with `:timeout-ms` as the
  mandatory safety bound (unit-suffixed by convention); then cleanup is terminal and
  exactly-once (all ownership released). Unkeyed children under a presence boundary are
  a build failure.
- The child reads its phase with `(presence-phase)` — the single phase read. **Outside a
  presence boundary it returns `:present`**, so presence-aware children stay reusable
  anywhere.
- Removal-then-reinsertion of a key has deterministic interruption/re-entry; exiting
  children are `inert`/`aria-hidden` by default; reduced-motion takes the immediate path;
  hydration does not fabricate enter transitions; tests advance transitions via
  `ui.test/flush-presence!` (no wall-clock sleeps).
- JVM renders `:present` and exposes presence metadata structurally. Occurrence-paths
  (I-8) identify retained exiting rows in Xray.

## 8. REPL and compile-time story

`defview` re-evaluation at the REPL re-registers (generation bump; mounted dev cells go
stale; hook-signature change remounts) — the HMR path *is* the REPL path. Compile budget
is gated (07 §5 G-14): `defview` expansion p95 and watch-loop rebuild on the dashboard
fixture — the guide-examples-as-fixtures policy must not make iteration slow.

The live body and build identity deliberately have different commit boundaries. An
unsaved direct REPL evaluation may replace the body immediately, but the whole-build
`:build-digest` remains the last successfully completed configured build/watch pass.
Saving and rebuilding publishes the next scalar. Macroexpand-only, never-evaluated, and
failed REPL forms therefore cannot become digest members.
