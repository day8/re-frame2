# Spec 004 — full normative rewrite draft (R-1, merges with the first conforming Stage-1 slice)

**Status:** final draft · 2026-07-11 · **codex2 fold-in applied 2026-07-12** (§Stage
conformance profiles added — the merge condition is now profile-defined; compat-tier
live home → `spec/004A-Reagent-Compat.md`; the JVM tree/conversion and root/mount
contract drafts cited as owners; port-ABI/lifecycle references finalized to the
six-operation, three-state model; narrow `local` law per the F8 ruling; wave-2 marks
corrected per deltas #2/#3).
**Target file:** `spec/004-Views.md` (wholesale replacement).
**Merge condition (08 §5 R-1 — now profile-defined):** this rewrite merges **atomically
with the first conforming Stage-1 slice** — never before an implementation conforms.
**"Conforming" means: every row tagged S1 in §Stage conformance profiles passes its
named assertions.** Later-tagged rows are declared-not-yet-asserted; each subsequent
stage's conformance slice asserts its rows atomically with that stage's spec edits (the
12 §2b spec-landing rule — no intermediate checked-in spec claims unimplemented
behaviour). Until then the interim-amended 004 (see
[spec-004-interim-amendment.md](spec-004-interim-amendment.md)) governs.
**Provenance:** every section carries a `⟨source⟩` tag naming the synthesis
passage it traces to (`01`–`10` = `ai/findings/new-substrate-synthesis/*.md`; `R-n` = the
08 §5 decision record; `I-n` = the 01 invariants). Tags are stripped at merge.
Markers: **[TRANSITION]** = conditional until the adapter deletion wave (08 §5 Adapters
decision: proof/default/soak gates, then UIx + Helix + slim are deleted and stock Reagent
— with the `reg-view` family — freezes into the compatibility tier); **[OPEN — needs
ruling]** = the synthesis is silent; do not invent (none remain in the body after the
2026-07-12 fold-in — see the OPEN roster). **[WAVE-2]** = named in the
synthesis as demand-gated, not v1 (08 §3).
House style: British "serialisable" in spec prose (the 08 §5 wording's "serializable"
is normalised).
Sibling contract drafts cited below —
[jvm-tree-and-conversion-contract.md](jvm-tree-and-conversion-contract.md),
[root-identity-and-mount.md](root-identity-and-mount.md),
[reagent-compat-boundary.md](reagent-compat-boundary.md),
[ui-test-selector-grammar.md](ui-test-selector-grammar.md) — are the **owning
contracts** for their surfaces; each citation becomes the promoted spec location at
merge.

---

# Spec 004 — Views

> Status: Drafting. **v1-required.** A view is `ui/defview` — a pure function of **one
> props map** to a **template**. Templates are Reagent-familiar hiccup with the
> ambiguities removed; a compiler lowers every view to **one normalized, serialisable
> template AST** consumed by two emitters — direct React code for the browser, a
> structural render tree for the JVM. No interpreter ships. Event handlers are **data**
> (event vectors) by default. Every view is memoized by default. The CLJS realisation is
> **`re-frame.ui`** (artifact `day8/re-frame2-ui`, alias `ui/`); frames are created at
> host preflight (per [002](002-Frames.md)), never from render. SSR
> ([011](011-SSR.md)) renders the same views on the JVM without React. ⟨README, R-3⟩

## Abstract

A view is a **pure function `(props) → template`**, authored with `ui/defview`. The
pattern-level commitments: ⟨01 invariants⟩

1. **Pure and speculative-safe.** A render may run, restart, or be abandoned; it reads
   values and builds a local capture. It MUST NOT dispatch, acquire ownership, mutate
   committed state, publish debug state, or create/seed frames. ⟨I-1⟩
2. **Frame-explicit, carried never guessed.** Resolution is explicit pin → dynamic
   binding → React context → loud `:rf.error/no-frame-context`. There is no default
   frame and no cross-frame read spelling. Frames are created at **host preflight**
   ([002](002-Frames.md)); the view layer only *scopes* live frames. ⟨I-10, 03 §8⟩
3. **The portability law.** A portable view has one deterministic, serialisable
   **template representation** consumed by each host emitter. Emitted host values may be
   host-native and need not themselves be serialisable. One normalized template AST
   controls every emitter; parity between emitters is **normalized structural
   equivalence** (fingerprinted), not byte-identical output. ⟨R-1, I-13⟩
4. **Client markup is compiled, never interpreted.** Literal templates lower to
   `jsx`/`jsxs` calls; conversion is compile-time; static subtrees hoist. No hiccup
   walker, tag parser, camelizer, or component-shape detector ships in a browser
   bundle. ⟨I-7⟩
5. **Handlers observe committed values.** Event callbacks are per-site stable and read
   committed slots + the committed frame; the canonical handler is an **event vector**
   — data. ⟨I-9⟩
6. **Memoized by default.** Every internal view is memoized on a generated
   straight-line `rf=` comparator over its declared prop slots. There is no opt-out.
   ⟨02 §1⟩

These are pattern-level commitments across the eight in-scope JS-cross-compile hosts
(per [000 §The pattern](000-Vision.md#the-pattern-js-cross-compile-language-agnostic)).
The CLJS reference realisation is `re-frame.ui`; its forms (`defview`, `sub`, `local`,
`effect`, `lease`, and the `ui/*` interop surface) are ordinary namespace vars —
`(:require [re-frame.ui :as ui :refer [defview sub]])` — referred bare in examples below
for readability. ⟨02 header, guide 01⟩

## The portability law and the template AST

⟨R-1, I-7, I-13, 06 §1⟩

**A portable view has one deterministic, serialisable template representation consumed
by each host emitter. Emitted host values may be host-native and need not themselves be
serialisable.**

- **One AST.** `defview` is `.cljc`. The compiler normalizes the template — including
  the control forms (§Template grammar) — into one closed-node-set AST. Every analyzer
  and every emitter consumes that AST; no emitter consumes raw source or another
  emitter's output.
- **Two emitters.** The browser emitter generates direct React code (`jsx`/`jsxs`
  calls, hoisted static subtrees, compile-time prop conversion). The JVM emitter
  generates the canonical serialisable structural render tree consumed by the existing
  `day8/re-frame2-ssr` artifact (per [011](011-SSR.md)) — no second server product.
  That tree's **versioned public node schema** (v1: element / fragment / view-boundary /
  trusted-HTML / text — a closed set of plain serialisable maps in canonical form), the
  semantic normalization `N` that feeds parity and fingerprints, and the `emit-ui-tree`
  SSR consumption boundary (version-gated) are owned by
  [jvm-tree-and-conversion-contract.md](jvm-tree-and-conversion-contract.md) —
  referenced here, never restated. The optimizer/compiler AST stays private; the public
  contract is the tree plus the conversion table. ⟨06 §1, 05 §1; 09 codex2 F2⟩
- **Parity.** Equivalence between emitters is **normalized structural equivalence** over
  semantic nodes — tag/ns, attribute names + values, child order, escaping, keyed order,
  void/boolean handling, fragments, fallbacks — fingerprinted and generatively tested
  (per [008](008-Testing.md)). Byte-identical HTML is NOT the contract. ⟨I-13, 06 §1⟩
- **Serialisation boundary.** The template AST's structure — tags, nesting, non-function
  attribute values, and literal event vectors — is fully serialisable and survives a
  print/read round-trip; event vectors are retained *as data* in the compiler manifest
  and the JVM tree. Non-serialisable sites (`ui/event`, `ui/handler`, bare fns,
  `ui/raw-fn`, foreign values) are explicit spellings recorded in the manifest with a
  `:serializable?`/`:dynamic` flag — escape hatches advertise their cost. ⟨I-14, 02 §3,
  04 §1, 06 §4⟩
- **Closed node set.** Escaping is structural: because the AST's node types are closed,
  there is no unknown-node fallback arm in either emitter. Template-string DSLs remain
  an invalid carrier — strings don't compose, don't diff, don't lint, don't round-trip.
  ⟨06 §1⟩
- **Non-React emitters** are preserved as an option by an AST-shape gate (the IR must
  keep edit-list-sufficient information), not by a maintained implementation. ⟨01
  non-goals, 08 §1⟩

## `ui/defview` — the one component form

⟨02 §1⟩

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

- **Zero or one argument — semantically a props map.** Header destructuring (`:keys`,
  namespaced `:x/keys`, `:or`, explicit bindings) lowers to direct property reads on the
  host props object; no CLJS map is materialized at entry. `:as` opts into
  materialization + generic comparison (a documented dev cost). **There are no
  positional args.**
- **Props ABI.** Each prop keyword maps to a deterministic quoted JS property name
  preserving namespace + name; it cannot collide with React's `key`/`ref`/`children`
  slots. **`:key` is reserved** (it feeds React's key slot) — an app prop literally
  named `:key` is a compile error. Children arrive in the props map as `:children` and
  compare as one slot. The compiler manifest maps compact production slot indexes back
  to keywords. ⟨02 §1, guide 02⟩
- **Options map (closed for v1):** `:props` (Malli — literal call sites checked at
  compile time, dynamic values at dev runtime, elided in production), `:id` (registry
  override), `:display-name`. Nothing else. The following were considered and are
  deliberately absent: `:memo false` (no demonstrated consumer; mutable foreign values
  belong at an explicit boundary); `:on-mount`/`:on-unmount` (domain events cannot ride
  mechanical React lifecycle — StrictMode replay, Activity, HMR, and error recovery make
  "once" semantics unrecoverable; domain visibility belongs to route/domain transitions,
  host sync to `effect`); `:catch`/`:fallback` (error handling is the explicit
  `ui/error-boundary` component). ⟨02 §1⟩
- **Registration.** `defview` defs a Var **and registers in the registrar** under the
  `:view` kind: source metadata, template fingerprint, hook signature, capability bits.
  Default id derivation follows the family rule `(keyword (str *ns*) (str sym))` per
  [Conventions](Conventions.md); `:id` overrides. Story mounts scenes by view id;
  render-keys are instance ids allocated at mount; the Pair hot-swaps a view like an
  event handler (§Hot reload). ⟨02 §1, 04 §5⟩ *(Default-id derivation is the carried
  Conventions rule; the synthesis names only the `:id` override.)*
- **Memo-by-default.** Every internal view is memoized on a generated straight-line
  `rf=` comparator over its declared prop slots. Scope stated honestly: `rf=`-equal
  props ⇒ no *prop-driven* repaint; subscription, local-state, and context changes still
  render. ⟨02 §1, 05 §1⟩
  **[RULED — Mike, 2026-07-12]** `rf=` is, per slot: `Object.is(a,b) OR (= a b)`.
  CLJS data (anything with `IEquiv`, incl. records and js/Date) compares by value;
  host/foreign values (plain JS objects, arrays, functions, React elements) fall
  through to identity. Consequences pinned: fresh-but-equal CLJS literals ⇒ no repaint;
  in-place-mutated host objects ⇒ no repaint (mutable foreign values belong at an
  explicit boundary — consistent with the `:memo false` rejection); `##NaN` props are
  repaint-stable via the `Object.is` branch; `-0`/`+0` compare *equal* via the `=`
  branch (deliberate, harmless divergence from raw `Object.is`). Teach as "React.memo,
  except CLJS data compares by value". The identity check doubles as the generated
  fast path.

## Template grammar

⟨02 §2⟩

Reagent-familiar hiccup with the ambiguities removed. Control forms — `let` / `letfn` /
`if` / `if-not` / `when` / `when-not` / `cond` / `case` / statically-pure `do` / `for` —
normalize **into the AST**; all analyzers and both emitters see through branches.

| Form | Meaning |
|---|---|
| `[:div.cls#id {…} …]` | DOM element; **literal head required** |
| `[view-sym {…} & children]` | internal view (compile-resolved Var) |
| `[ForeignComponent {…} …]` | foreign React component (open props; JS values pass through) |
| `[:<> …]` | fragment |
| `(for [x xs] [item {:key …}])` | keyed list → direct JS array; missing key = build failure |
| `(ui/presence …)` | declarative enter/exit retention (§Presence) |
| strings / numbers / `nil` / `false` | text / nothing |

**Rejected at compile time** (didactic messages naming the escape): dynamic tag heads;
markup-returning `map`; keywords in child position; raw lazy seqs; unkeyed list items;
`sub`/`lease` in loops (extract a keyed child view — sites must be finite). A bare
keyword head is a DOM/custom element, never a registry lookup (rf2-n82bbu; enforced at
compile time).

**DOM prop spelling is pinned:** hyphenated lowercase words mirroring React's camelCase
— `:on-click`, `:on-key-down`, `:on-input` (never `:on-keydown`). Handler-map options:
`{:event […] :prevent-default true :stop-propagation true :capture true :passive true
:once true}` — the DOM listener vocabulary is explicit, not implied.

**Prop conversion is compile-time, contextual, and total:** DOM attribute casing,
`:style` maps (keyword values stringify), `:class` string/vector/map-of-flags; component
props pass through untouched. **One rule table** serves static props, the single
dynamic-map conversion fn (`ui/spread`, v1), and both emitters — and that table now
exists as normative rows (namespaces, attribute names, boolean/booleanish/overloaded
sets, property-only + form-control forms, `:style` px/unitless/custom-property rules,
`:class` composition + `.class#id` sugar precedence, children/escaping):
[jvm-tree-and-conversion-contract.md](jvm-tree-and-conversion-contract.md) §The DOM
conversion table is the owning contract. No `#js` on compiled
DOM/internal paths (foreign React interop may hand raw JS values through — that is the
boundary's job).

**Custom elements** (tag contains `-`): a bounded classification rule — literal props
compile to properties when the name matches a declared property (per an optional
`ui/custom-element` declaration), else attributes; booleans/`:class`/`:style` follow DOM
rules; native custom events ride the normal handler grammar. Never forced through
`ui/raw`. **[RULED — Mike, 2026-07-12]** Declaration grammar:
`(ui/custom-element tag {:properties #{...}})` — top-level, compile-resolvable,
registers like `defview`. The `:properties` set is the **entire v1 grammar** (options
map closed, per the `defview` options-map discipline); future keys (`:events`, per-prop
types, attribute reflection) are new rulings, not silent growth. Declared names →
JS properties, kebab keyword mapped to the camelCase property (`:help-text` →
`helpText`, mirroring the pinned DOM-spelling philosophy); undeclared names →
attributes; undeclared *elements* need no declaration (all-attributes default). SSR/JVM
emitter emits attributes only; property-props are applied at hydration. Rejected:
Lit-style rich schema (no consumer — demand bar), React-19-style runtime `in` check
(breaks compile-time totality + gives SSR no static answer), attribute-only-v1 deferral
(makes property-accepting web components unusable, hollowing "never through
`ui/raw`"). Ships with the S4 epic; `ui/custom-element` added to the 12 §2 freeze
table as the delta protocol's first row-level delta.

## Handlers are data — the callback law

⟨02 §3, I-9, R-4⟩

**Canonical: the event vector.** A vector in an `:on-*` position is the event intent,
dispatched to the committed frame:

```clojure
[:button {:on-click [:cart/add id]} "Add"]
[:input  {:on-input [:form/typed :email :rf.ui/value]}]
[:input  {:type :checkbox :on-change [:prefs/set :dark :rf.ui/checked]}]
```

**Placeholder vocabulary (closed, v1, scalars only):** `:rf.ui/value`,
`:rf.ui/checked`, `:rf.ui/key`. Placeholders splice at top-level positions of the vector
at dispatch time. `:rf.ui/form-data` and `:rf.ui/event` do not exist — form payloads
carry duplicate keys/files and are not EDN; a raw event is a host object; both cases
belong to `ui/event`. Vectors with only literal/placeholder content are **data**:
value-comparable, statically inspectable, JVM-testable, retained as data in the manifest
and the JVM tree. On the client they lower to normal React handlers (per-site stable,
committed-slot reading). No handler attributes are emitted into HTML and no resumability
is claimed (research-tier per R-5; the serialisability *property* is kept, the platform
built on it is not v1).

### The decision table

| Form | Invoker → phase | Identity | Sees | Serialisable | Use for |
|---|---|---|---|---|---|
| `[:event … :rf.ui/value]` | DOM → after commit | per-site stable | committed slots + frame | **yes** | intent (the 90%) |
| `(ui/event [e] … [:vector …])` | DOM/foreign → after commit | per-site stable | committed slots + the live event | no | event mechanics, form/file payloads, filtering (`nil` ⇒ no dispatch) |
| `(ui/handler [x] …)` | foreign → after commit | per-site stable | committed slots | no | imperative work, stable-identity change-callbacks |
| `(ui/render-fn [x] …)` | foreign → **during its render** | none promised | current render | no | item-key/comparator/render props; pure — no dispatch/sub/lease/hooks |
| bare `#(…)` in a **known native event property** (`:on-*` on DOM/custom elements) | DOM → after commit | per-site stable | its closure (committed render's values) | no | shorthand for `ui/handler` — legal because invoker + phase are known. **Only there**: not refs, not arbitrary fn-valued props |
| bare fn at a **foreign-component** boundary | unknown | unknown | unknown | no | **compile error** — choose `ui/event`/`ui/handler`/`ui/render-fn`/`ui/raw-fn` |
| `(ui/raw-fn f)` | foreign, identity-as-protocol | passed through | its closure | no | APIs that treat callback identity as data; also the callback-ref form |

**The narrow bare-fn law (R-4).** Bare fns are legal **only** in known native
event properties, where the invoker and phase are known. One callback never serves both
phases. A day-one **strict lint** — `{:re-frame.ui/bare-handlers :warn}` (or `:error`) —
lets a team adopt explicit-everywhere as policy without a language change; the language
itself stays permissive (flipping it later would break source; the lint is the honest
lever).

**Refs.** `:ref` is a reserved React slot, never an event property — the bare-fn
shorthand does NOT apply. Object refs are preferred. A callback ref MUST be explicit
`(ui/raw-fn f)`: React invokes callback refs during commit *before* the owning view's
layout publication, so no committed-slot promise can be made — the explicit form marks
that. Internal views forward `:ref` only by declaring it; refs never appear in event
vectors or SSR output. ⟨02 §3 refs policy⟩

**Dynamic handler expressions.** Handler-position expressions are legal
(`(if in-cart? [:a id] [:b id])`, a prop-forwarded event). Literal forms classify at
compile time; non-literal values classify **at runtime by type** (vector → dispatch; map
→ options form; compiled handler object → itself; fn → the boundary rules above; `nil` →
no handler). Two consequences, stated honestly: **placeholders are compiled, so they are
recognized in literal vectors only** — a placeholder keyword inside a runtime-forwarded
vector dispatches as an ordinary keyword argument, and dev warns
(`:rf.warning/placeholder-in-dynamic-vector`). And manifests mark value-classified sites
`:dynamic` — the static interaction surface covers literal and normalized-branch sites
and says "dynamic" for the rest.

**Loops.** A capture-free literal vector handler in a `for` body is legal and shares one
callback across rows. A vector that **captures the loop binding** is a compile error
with the extract-a-keyed-child-view fix — per-row committed slots need per-row
instances. The same rule covers `ui/event`/`ui/handler` in loops (they are sites too).
Bare fns in loops get the same diagnostic as a dev *warning* (they work, at per-row
closure cost, and defeat the data idiom — the nudge is deliberate).

**Controlled inputs — the synchrony law.** Dispatches from
`:on-input`/`:on-change`/`:on-before-input` sites on **controlled** DOM elements drain
**synchronously within the DOM event** — event → drain → commit → snapshot advance
before React's discrete-event re-render — so value round-trips cannot drop characters,
jump the caret, or break IME composition. This is the one sanctioned synchronous door;
everything else batches after drain quiescence (all queued write epochs execute; each
dirty cell is notified once for the read/render batch, I-6). Caret/IME
correctness gates first, latency second. **The trigger predicate (confirmed sufficient
by S-5; the residual named gate is the G-8 real-browser input matrix, not the
predicate):** the door applies where the compiler can *prove* the element controlled —
a literal `:value`/`:checked` prop co-present on the element with the vector-handler
site; dynamic props maps, `ui/spread`, and `ui/event`/bare-fn dispatches at such sites
fall back to standard batching with a dev diagnostic naming the sync-door conditions.
⟨02 §3, 03 §3⟩

**Dev safety nets.** Data handlers with unregistered event ids warn at render with the
element's coordinates (`:rf.warning/unregistered-event-id`). The registrar is
process-global — frames isolate state, not behaviour — so a lazily-loaded module that
registers later can produce a false positive; the warning names that possibility.

## Reactive reads — `sub`

⟨02 §4, 03 §1–2⟩

`(sub [:query …])` returns the subscription's value — no deref, no manual memoization,
no deps arrays. Each lexical `(sub …)` is a compile-indexed site; all of a view's sites
share **one** React bridge (one `useSyncExternalStore`, one scalar revision snapshot,
one notification per dirty cell per drain after quiescence — I-3/I-4/I-6). Conditional
reads are legal; `sub` in loops
is a compile error (sites must be finite — extract a keyed child view). Literal queries
are module constants; parametric sites reuse the prior query object while args are
`rf=`; sites return the prior exact value when the new read is `rf=`. The observation
model — the **six-operation port** (`resolve-target` · `probe` · `acquire!` ·
`current?` · `read` · `release!`) over the target/evidence/lease split, the staged
transactional commit algorithm (acquire-before-release with rollback), and the
**three-state lifecycle** (`:connected` / `:disconnected` / `:dead`; Activity-hide vs
unmount are qualified retroactive annotations, never distinct runtime states) — is
owned by [006](006-ReactiveSubstrate.md) (the R-2 observation port; shapes **final**
per the rewritten amendment,
[spec-006-observation-port-amendment.md](spec-006-observation-port-amendment.md));
this Spec owns only the call-site surface. `sub` never fetches (I-11).

## Local state — `local` — and the placement rule (this Spec owns the rule)

⟨02 §5, 03 §5; rf2-5sjbg lineage; 09 codex2 F8 ruling⟩

```clojure
(let [[text set-text] (local "")] …)
```

`(local init)` → `[value set!]` — **host component-local state, deliberately outside
re-frame2 epochs**: not observed by subs, not revertible by epoch restore, re-renders
this view only. `set!` during render is a dev error
(`:rf.warning/render-phase-set!`). There is no frame-resident variant and none is
reserved — if frame-resident ephemera is ever built it will be a new name with its own
semantics.

**The placement rule (this Spec remains its sole owner; the narrow law, ruled per the
codex2 F8 disposition — the earlier "forbidden if any handler ever reads it" strictness
is superseded):**

- **Default — `app-db`.** State with product meaning lives in `app-db`, written by
  events, read by subs — observable, replayable, schema-checkable, headlessly testable.
  When in doubt, `app-db`.
- **The `local` tier (the narrow law).** `local` holds keystroke-latency view
  ephemera — in-flight field text, uncommitted IME composition, transient focus/hover,
  open/closed visual state. A local value **MAY be read by same-view committed
  handlers**: handlers read committed slots, and committed slots include local
  ephemera. The guide's search box — text held in `local`, submitted as
  `[:search/run text]` from the button's handler — is **canonical and conforming**; the
  seam where a local value crosses into an event vector is exactly where it becomes
  product state. When the field's *every keystroke* is product state (live filtering
  another view observes), dispatch placeholders (`:rf.ui/value`) instead of holding it
  locally.
- **The forbidden tier.** `local` is FORBIDDEN when the value needs **cross-view
  observation, replay/persistence, schema or tool inspection, durable navigation
  semantics, or subscription-derived computation** — those belong in `app-db`. Loading
  flags are the classic example. The bias is deliberate: a value wrongly kept local is
  invisible to tools and unrecoverable on replay; a value in `app-db` that turns out
  never to be read is merely slightly verbose. Prefer the recoverable failure.

## Effects and leases — the view-side surface

⟨02 §5, 03 §6–7⟩

```clojure
(effect [node series] (draw! node series) #(destroy!))  ; rf= value deps; cleanup fn
(effect :connect (subscribe-external!))                 ; runs at each connect; cleanup at disconnect
(lease  {:resource :article/by-slug :params {:slug slug}})
```

- `(effect [deps…] body)` is a passive host effect; deps compare by `rf=` (documented
  cost: broad values walk — keep deps narrow); the cleanup fn is honored on dep change,
  disconnect, and unmount. StrictMode dev replay is expected and MUST be idempotent-safe
  — that is what cleanup is for. `(effect :connect body)` runs at each connect, cleanup
  at each disconnect; **there is deliberately no "once"/"mount" name** — React's
  lifecycle has no "once", and pretending otherwise is the `:on-mount` bug. Effects
  synchronize with the host world; app state goes through events.
- Stateful imperative libraries that own their own DOM subtree (D3, Mapbox, CodeMirror,
  animation libraries) attach/detach inside `effect` with a ref — never in the render
  body (I-1). Host primitives the substrate does not wrap (`setTimeout`, `fetch`,
  RAF loops, WebSocket listeners) remain registered fx per
  [Pattern-AsyncEffect](Pattern-AsyncEffect.md) — unchanged dataflow doctrine.
- `(ui/dispatch-fn)` is the stable committed-frame dispatcher for imperative callbacks;
  it fails loudly in every non-connected state (`:rf.error/dispatch-disconnected`).
- `(lease descriptor)` **declares** resource liveness; it is recorded at render and
  reconciled by one aggregated passive effect after commit (ensure/release-owner per
  [016](016-Resources.md)). Reads stay passive (`(sub [:rf/resource …])`); `lease` in
  loops is rejected; routes/events/machines remain the preferred causal owners (I-11).

## Loading state is explicit

⟨I-11, 01 non-goals⟩

Loading state is data in `app-db` (canonically
[Pattern-RemoteData](Pattern-RemoteData.md)'s `:status`); views read it and branch.
`sub` never fetches; `lease` declares liveness and acts only after connected commit;
routes, events, and machines are the preferred causal owners of fetching. **Suspense as
loading state is a non-goal** — it hides loading in the substrate where tools cannot see
it, SSR cannot replay it, and machines cannot govern it. Hiding a loading flag in
`local` is the forbidden-tier violation above.

## Presence — declarative enter/exit

⟨02 §7, 08 §5 presence ruling⟩

A *presence* primitive, deliberately bounded — not an animation system (anything beyond
enter/exit retention is out of scope):

```clojure
(ui/presence {:timeout-ms 300}
  (for [t toasts]
    [toast-card {:key (:id t) :toast t}]))
```

- Keyed children pass `:mounting → :present → :unmounting`; an exiting child stays
  mounted until its transition/animation completes, with `:timeout-ms` as the
  **mandatory** safety bound (unit-suffixed); then cleanup is terminal and exactly-once
  (all ownership released). Unkeyed children under a presence boundary are a build
  failure.
- `(presence-phase)` is the single phase read. Outside a presence boundary it returns
  `:present`, so presence-aware children stay reusable anywhere.
- Removal-then-reinsertion of a key has deterministic interruption/re-entry; exiting
  children are `inert`/`aria-hidden` by default; reduced-motion takes the immediate
  path; hydration does not fabricate enter transitions; tests advance transitions via
  `ui.test/flush-presence!` (no wall-clock sleeps).
- The JVM emitter renders `:present` and exposes presence metadata structurally.
  Occurrence-paths (§View identity) identify retained exiting rows in tooling.

## Interop and boundaries

⟨02 §6⟩

| Surface | Contract |
|---|---|
| `(ui/raw react-element)` | embed an existing React element (child position; SSR paths need a `client-only` sibling fallback) |
| `[ForeignComponent {…}]` | foreign React head; open props, JS values pass through; callbacks per §The decision table |
| `(ui/->react view)` | export a view as a React component — the outward migration bridge. **v1, lands S6** with the migration wave (delta #2, ruled 2026-07-12). Contract: [reagent-compat-boundary.md](reagent-compat-boundary.md) §3 — memoised per view id (returns the stable shell), no new React root/manifest/preflight; the exported view scopes frames, never creates them |
| `(ui/element type props & children)` | runtime-chosen element/component **[WAVE-2]** |
| `(ui/view id)` | registry-addressed component; production use requires production registry entries (dev-only string ids cannot serve prod lookup) **[WAVE-2]** |
| `(ui/spread base overrides)` | the one generic runtime prop-map conversion — **v1** (delta #3, ruled 2026-07-12; the conversion architecture's single dynamic-map path, driven by the owning rule table) |
| `(ui/portal node child)` | React portal; frame context passes through **[WAVE-2]** |
| `(ui/client-only {:fallback tpl} client-tpl)` | browser-only subtree; the fallback is mandatory and MUST be capability-free (compiler-checked); the JVM and first hydration render the fallback, then one root phase-flip swaps all sites in a single update (per [011](011-SSR.md)) |
| `(ui/html string)` | **trusted markup, low-friction.** Renders the string as HTML, explicitly. The spelling *is* the contract: the visible call marks the one place escaping is bypassed; manifests record the site; both emitters treat it identically. Strings anywhere else always escape. |
| `(ui/error-boundary {:fallback view :reset-key val :on-error [:ev …]} child)` | the explicit error component. Catches render/lifecycle throws below it (React does not catch event-handler or async errors — those keep their own typed paths); `:on-error` dispatches **after** the failing commit through a captured live frame (never during render, I-1); the fallback renders with `:error` + declared props and cannot recursively dispatch; changing `:reset-key` clears the caught error (retry = a state change that changes the key); the JVM/SSR renders the child under the server failure policy (per [011](011-SSR.md)) — boundaries are a client recovery mechanism. |
| `re-frame.ui.data` | the interpreter for genuinely runtime-authored UI (CMS trees) — a **separate artifact**, never in a compiled browser bundle by accident |

Wave-2 rows ship only on the demand bar (a named consumer in the repo's examples,
tools, or guide fixtures — guide examples authored by this project do not count as
independent demand for platform-scale features). ⟨08 §3, 01 decision rule⟩

## Roots and mounting

⟨02 §6 Roots, 03 §8, 06 §2⟩

`(ui/mount root-form dom-node)` / `(ui/mount root-form dom-node opts)` is a **macro
over a literal root form** — the compiler must see the root to keep the AST closed and
to extract frame plans; a runtime-assembled vector is a compile error pointing at
`ui/view`/`ui/element`. The opts map carries **root identity** — `:root-id` (authored
wins; a qualified keyword or a qualified-keyword-plus-scalar vector), `:disambiguator`,
`:identifier-prefix`, all compile-time literals — plus the host error callbacks
(`:on-uncaught-error` / `:on-caught-error` / `:on-recoverable-error`). **Every root has
a root-id:** authored, or derived from the mounted view's registered id (a single-root
page needs nothing; the same view mounted twice needs `:disambiguator` or an authored
id — anything else is a compile error). The compiler emits **Root Descriptor v1**
(`:rf.root/schema-version 1`) per mount site — the named, versioned compile-time
**subset of the Stage-5 Root Manifest** (strict superset the other way; readers ignore
unknown keys; additive keys never bump the version). Hosts needing control use
`create-root` / `render!` / `hydrate-root` / `unmount!`; **`hydrate-root` takes its
identity from the manifest**, never from client opts (supplying identity opts there is
`:rf.error/root-manifest-invalid`). The signature set, derivation + slug rules, element
locators, three-layer fail-loud duplicate/conflict detection, and the accepted
`ui.test/render` root forms are owned by
[root-identity-and-mount.md](root-identity-and-mount.md). ⟨09 codex2 F3⟩

- **Frames are created at host preflight, never from render (I-1).** The compiler
  extracts **unconditional `frame-root` plans** from the root form; before React (or the
  JVM renderer) is invoked, the host ensures the frames and drains `:initial-events` —
  exactly once, unaffected by abandoned renders, StrictMode replay, HMR, or error
  recovery. The emitted `frame-root` component then only **scopes** the already-live
  frame. `frame-root` sites MUST sit in the top region of the root form (unconditional,
  compile-extractable); conditional, reactive, or list-generated sites are compile
  errors ("create frames in boot/event infrastructure; scope with `frame-provider`").
  Frame identity, ENSURE semantics, and `frame-provider` (SCOPE) are owned by
  [002](002-Frames.md) (the R-7 staged frame chain).
- **Roots ≠ frames.** A root is one React DOM render/hydration unit; a frame is one
  re-frame2 state world; roots ↔ frames are many-to-many. The root manifest, hydration
  contract, per-root failure isolation, and the static-root explicit policy are owned by
  [011](011-SSR.md); Root Descriptor v1 (above) is this Spec's compile-time subset of
  that manifest. ⟨06 §2–3⟩
- `ui.test/flush!` is the only test flush (per [008](008-Testing.md)).

## View identity and the instrumentation surface

⟨I-8, I-12, 04 §1–4⟩

Five identities, never conflated: **root-id** (owned by [011](011-SSR.md)), **frame-id**
(owned by [002](002-Frames.md)), **render-key** (one committed view instance — owned
here), **occurrence-path** (a keyed repetition inside one instance — owned here), and
**observation-target** (owned by [006](006-ReactiveSubstrate.md)). Sites get
compile-time indexes + source anchors; identity under HMR is source anchor + structural
path + generation, released/remounted on ambiguity.

- **Compiler manifest — what *can* happen.** Per view, dev: source coords, prop slots +
  schema, template fingerprint, hook signature, capability bits, and every site (subs
  with query shapes; events with event shapes + `:serializable?`/`:dynamic` flags;
  leases; effects; presence sites) with source + template path. No runtime values;
  useful before mount — consumed by Xray, Story, editors, and agents.
- **Committed instance record — what *did* happen.** Published only at connected commit;
  speculative renders publish nothing (I-1/I-2). It carries render-key,
  parent-render-key (direct hierarchy — no Fiber or DOM walking), root-id, frame-id,
  view-id, generation, connection state, observations, and the **`:rf.view/causes`
  vector** (mount / subscription / story-override / prop / local-state / frame /
  resource / hmr / hydration-correction / reconnect-correction / epoch-restore /
  foreign-or-react) — attribution is emitted at the cause site, never reconstructed.
  Every bounded buffer reports loss accounting (`total`/`retained`/`dropped`).
- **One catalogue.** The evidence schemas, trace ops, and every error/warning id this
  Spec names (`:rf.error/dispatch-disconnected`, `:rf.error/view-not-found`,
  `:rf.error/frame-payload-invalid`, `:rf.error/flush-in-open-epoch`,
  `:rf.error/jvm-host-op`, `:rf.warning/unregistered-event-id`,
  `:rf.warning/placeholder-in-dynamic-vector`, `:rf.warning/cross-frame-carried-op`,
  `:rf.warning/render-phase-dispatch` / `-set!`, and the compile-error roster) get
  catalogue rows in [009](009-Instrumentation.md) (the one-catalogue rule, rf2-cs0kd1).
  ⟨03 §11⟩
- **Source ↔ DOM navigation.** Compile-time `data-rf2-source-coord` + render-key
  (+ occurrence-path) annotation on compiler-owned host roots — today's attribute
  vocabulary, so existing Xray click-to-source works day one. Dev-gated; production
  builds carry none of it.
- **Production erasure is a proof (I-12).** Compile-time defines + bundle-scan gates;
  manifests, cause vectors, histories, warning text, and `data-rf2-*` strings are on the
  scanned absence roster. The always-on Spec 009 error contracts remain.
- **[TRANSITION]** The `[view-id instance-token]` `:render-key` wire shape and the
  `[:rf.view/anonymous nil]` fallback for unregistered render fns remain emitted by the
  UIx/Helix/slim adapters until the deletion wave, and by the frozen stock-Reagent
  compatibility tier thereafter; the compiled substrate emits its own versioned evidence
  schema (integer render-key + separate `:view-id` + `occurrence-path`) in the 009
  catalogue.

## The JVM structural subset

⟨06 §1⟩

`defview` is `.cljc`; the JVM emitter renders the defined structural subset. "If the
browser renders it, the server renders it" is scoped to exactly this table:

| Feature | JVM structural render |
|---|---|
| structure, props, subs, branches, lists, event intent, `ui/html` | full semantics (subs via the pure snapshot path — no ownership, no watches) |
| `local` | contributes its **initial value**; the setter is absent — invoking it in a JVM test raises `:rf.error/jvm-host-op` |
| `effect` | does not run; recorded as capability metadata |
| refs | absent |
| `portal` / `client-only` | explicit deterministic fallbacks |
| `error-boundary` | server failure policy (project error / status per [011](011-SSR.md)), not client recovery |
| `presence` | renders `:present`; phase metadata exposed structurally |

The tree the JVM emitter returns is the **versioned public ABI** owned by
[jvm-tree-and-conversion-contract.md](jvm-tree-and-conversion-contract.md): five closed
node variants (element / fragment / view-boundary / trusted-HTML / text-as-string),
plain serialisable maps in canonical form, rooted with `:rf.ui/tree-version 1`. **Node
reading (ruled):** nodes are plain maps — *field* reads (`:tag`, `:attrs`, `:events`,
`:children`, `:view-id`) are public ABI; *attribute* reads go through the merged
projection `(ui.test/attrs node)` (attrs + events on elements, props on view-boundary
nodes) — `(:on-click node)` is a field miss, never an attribute read. Event vectors are
retained as data under `:events`. The node schema, canonical-form rules, semantic
normalization `N` (the parity/fingerprint input), and the SSR consumption boundary
(`emit-ui-tree` + the `:rf.error/ssr-ui-tree-version-unsupported` version gate) are that
contract's — summarised here, owned there. Selector semantics over these trees are
[ui-test-selector-grammar.md](ui-test-selector-grammar.md)'s: view-id selectors match
the view-boundary node, so fragment-rooted and nil-rooted views are matchable.
⟨09 codex2 F2 ruling⟩

Host-bearing features (state transitions, effects, refs, focus, portals, presence
timing, error recovery) require mounted (Tier-3) tests — the guide says this out loud.
Headless (Tier-1) tests of structure, subs, branches, lists, and event intent run on the
JVM against this subset via `ui.test` (per [008](008-Testing.md)); Tier-1 requires the
events/subs a view touches to be `.cljc` — an authoring constraint the guide teaches.
⟨07 §1–2⟩

## Hot reload — the view-side contract

⟨03 §10, 02 §8⟩

HMR is a designed contract with fixtures, not a hope; the REPL path *is* the HMR path
(`defview` re-evaluation re-registers and bumps the generation). The view-side surface:

- **Stable shells.** `defview` exports a stable component shell keyed by view id; the
  registry holds the current implementation descriptor. Re-evaluation replaces the
  descriptor; the shell identity never changes, so React state, refs, and cell identity
  survive.
- **Hook-signature hash decides preserve vs remount.** Same signature: mounted cells
  mark stale, the next render runs the new body, commit reconciles changed sites —
  state preserved. Changed signature: the shell deliberately remounts — never a
  corrupted hook order. Dev's fixed full hook skeleton exists precisely so adding your
  first `sub` to a view is a same-signature edit (I-15).
- **Frames are untouched by reload** — ENSURE ran at preflight; re-running the mount fn
  finds the frames live and does not re-seed; `:initial-events` re-run only on a
  genuinely new frame id.
- **The Pair's hot-swap is this same mechanism** invoked over nREPL.

Cell/ownership reconciliation under reload is owned by
[006](006-ReactiveSubstrate.md); compile budgets (expansion p95, watch-loop rebuild)
are gated per [008](008-Testing.md) G-14.

## Removed forms — normative absences

⟨01 non-goals, 02 §1, R-4, 08 §5–6, 10⟩

There is exactly **one** component form. The following do not exist in this contract:

- **Form-1 / Form-2 / Form-3.** No closure-form components, no class components, no
  outer/inner render split; the Forms live on only in the frozen stock-Reagent
  compatibility tier (live normative home: the compatibility appendix
  `spec/004A-Reagent-Compat.md`, per
  [reagent-compat-boundary.md](reagent-compat-boundary.md) §8), taught on one migration
  page only. Form-2 local state is `local`;
  Form-3 lifecycle work is
  `effect` (+ refs) or a foreign-boundary component; setup-on-mount work is a frame's
  `:initial-events` or a route/domain transition — never a render-phase or
  mount-lifecycle dispatch.
- **The `reg-view` family.** `reg-view`, `reg-view*`, the two registration lanes, and
  the `(rf/view id)` runtime lookup are absent from this contract — the family freezes
  with the stock-Reagent compatibility tier, and its **live contract moves to the
  compatibility appendix `spec/004A-Reagent-Compat.md`** (lands with the S7 deletion
  wave; the appendix retains the family's API/facade/Conventions rows under a
  `v1 (frozen — compat tier)` status — **the exports relocate, they are not removed**;
  [reagent-compat-boundary.md](reagent-compat-boundary.md) §8). `defview` is the one
  registration surface;
  the registrar `:view` kind persists as the tooling read surface (Story mounts by view
  id; Xray lists by registry query). Runtime-chosen components are `ui/view` /
  `ui/element` **[WAVE-2]**, demand-gated, with production registry entries required for
  production lookup.
- **Positional view args.** One props map. Call sites are `[view-sym {…}]`.
- **Plain render fns as frame-aware views.** There is no frame injection into
  unregistered fns, no `capture-frame` render affordance, and no
  `[:rf.view/anonymous nil]` trace fallback — every traced view is a `defview`; foreign
  React components are boundaries, not views.
- **`:on-mount` / `:on-unmount`, `:memo false`, `:catch`/`:fallback` options;
  `:rf.ui/form-data` / `:rf.ui/event` placeholders** — considered and rejected
  (rationales in §`ui/defview` and §Handlers).
- **The `h` macro; bare-keyword view heads.** Carried absences — no compile-time hiccup
  walker rewriting keyword heads; a keyword head is always a DOM/custom element
  (rf2-n82bbu: dynamic tag heads are compile errors and the registry is
  never probed on the render path).
- **A second state model.** No ratoms, cursors, reactions, signals, or query caches in
  the view tier — one reactive grammar, subscriptions.
- **Suspense-as-loading, RSC, `startTransition` over app-db, general animation
  frameworks, resumability machinery** — non-goals (resumability is research-tier per
  R-5).

**[TRANSITION] Until the adapter deletion wave** (proof/default/soak gates per the
08 §5 Adapters decision: RealWorld-resources green · Story + Xray green ·
SSR/hydration + HMR matrices green · production-specialization + bundle-absence gates
green · templates/docs/examples defaulted · zero repo-owned non-historical
UIx/Helix/slim imports · two consecutive green nightlies + one week with no fallback),
the UIx and Helix adapters (and reagent-slim) remain shipping surfaces governed by the
carried pre-rewrite contract text under these [TRANSITION] markers — **the markers, not
git history, are the live contract during the transition** (the git tag is provenance
only, never a normative home). **The Reagent-tier forms — Form-1/2/3 and the `reg-view`
family — are not deleted at the wave: they freeze into the stock-Reagent compatibility
tier**, whose **live normative home is the compatibility appendix
`spec/004A-Reagent-Compat.md`** (lands with the wave; carries the freeze rules, the
preserved Form/`reg-view`/Reagent-adapter/frame-context sections as live text, the
two-direction boundary contract, the retained API/facade rows, and the two-suite CI
surface — [reagent-compat-boundary.md](reagent-compat-boundary.md) §8). Correct but
frozen: contract suite + one smoke in CI; no new capabilities; taught on exactly one
migration page; `ui/defview` is the only *taught* component form. Old and new trees
co-mount at explicit boundaries during migration (per the migration guide); the
dataflow layer is untouched throughout. After the wave: Spec 006's host-neutral
contracts, the plain-atom substrate, and benchmark results + fixtures are kept, and a
git tag of UIx/Helix/slim is kept **as provenance, not contract**; those adapters are
not.

## Stage conformance profiles

⟨09 codex2 F4 disposition (binding); 12 §2b; 08 §2; answers Q32; Q61's landing rule is
12 §2b's⟩

R-1's staged merge needs an implementable meaning for "conforming". This section is
that definition. It merges **as part of the spec text** and is the device that keeps an
intermediate checked-in spec honest: rows tagged above the current implementation stage
are **declared, not yet asserted** — their contract text is final, their enforcement
rides their stage's conformance slice, which lands atomically with that stage's spec
edits (the 12 §2b spec-landing rule).

**Definition.** Every normative section of this Spec is tagged with the stage (S1–S7)
whose implementation slice first *asserts* it with conformance fixtures.
**"Stage-N-conforming" = every row tagged ≤ N passes its named assertions.** A row with
a "completes" note is asserted at its tagged stage to the tagged scope only; the
completing stage extends the assertion. Stage assignments align with the authoritative
surface matrix (12 §2b) and the stage contents (08 §2); a conflict is resolved in that
order and is a defect in this table.

| Normative section (this Spec) | Stage | What that stage's fixtures assert |
|---|---|---|
| §The portability law and the template AST | **S1** | one AST → two emitters; normalized structural equivalence (parity corpus v0); serialisation boundary; closed node set; AST-shape gate |
| §`ui/defview` — grammar, props ABI, options map, registration, `rf=` comparator | **S1** | declaration arities + diagnostics; props ABI encoding + `:key` reservation; registrar `:view` entries; the ruled `rf=` comparator emitted and asserted against prop-driven re-render (subscription/local interplay asserts S2/S3; stable-shell identity is S2 HMR work) |
| §Template grammar — forms, control forms, rejection roster | **S1** | table forms lower; compile-error roster with didactic messages |
| §Template grammar — prop conversion (the rule table; `ui/spread`) | **S1** | conversion-table fixtures consumed by both emitters (owning table: [jvm-tree-and-conversion-contract.md](jvm-tree-and-conversion-contract.md)); `spread` dynamic-map cases |
| §Template grammar — custom elements (`ui/custom-element`, RULED grammar) | **S4** | property-vs-attribute classification; SSR attributes-only; W14 fixtures |
| §Handlers — event vectors as structural data (manifest flags; JVM-tree `:events`) | **S1** | vectors/options-maps retained as data in tree + manifest; placeholder keywords retained as keywords |
| §Handlers — committed behaviour (decision table, bare-fn law + lint, dynamic classification, loops, refs, the synchrony door) | **S3** | decision-table fixtures; sync-door fixture (S-5 predicate; G-8 real-browser matrix is the residual named gate); loop/ref diagnostics |
| §Reactive reads — `sub` | **S2** | one-ViewCell binding; stabilization; conditional reads (the loop *rejection* is a compile error from S1) |
| §Local state + the placement rule | **S3** | `local` semantics; narrow-law fixtures (same-view handler read conforming; forbidden-tier diagnostics) |
| §Effects and `ui/dispatch-fn` | **S3** | `rf=` deps + cleanup + StrictMode replay; `:connect` semantics; loud non-connected failure |
| §Leases (view-side surface) | **S2** → confirms **S3** | owner-token semantics + transactional rollback with the observation work (S2); view-level resource-lease confirmation fixture (S3) |
| §Loading state is explicit | **S2** | `sub` never fetches; `lease` acts only after connected commit |
| §Presence | **S4** | enter/exit retention; `flush-presence!` fake-clock fixtures; JVM `:present` |
| §Interop — `ui/raw` | **S1** → completes **S4** | compile form + opaque marker in the tree (S1); foreign-boundary corpus (S4) |
| §Interop — `ui/html` | **S1** | dual-emitter agreement; the single escaping bypass; manifest site recording |
| §Interop — `ui/error-boundary` | **S3** | phase semantics; `:reset-key`; server-policy contrast |
| §Interop — `ui/client-only` | **S3** → completes **S5** | capability-free fallback check (S3); SSR phase flip (S5) |
| §Interop — `ui/spread` | **S1** | with the conversion-table row above |
| §Interop — `ui/->react` | **S6** | compat-boundary fixtures, both nesting directions ([reagent-compat-boundary.md](reagent-compat-boundary.md)) |
| §Interop — `element` / `view` / `portal` / `re-frame.ui.data` | — | [WAVE-2]: no stage, no assertion, no v1 existence |
| §Roots and mounting — mount grammar, root identity, Root Descriptor v1, client host fns, duplicate Layers 1+3, static frame-plan extraction | **S1** | the [root-identity-and-mount.md](root-identity-and-mount.md) §10 S1 row |
| §Roots and mounting — frame preflight ENSURE (runtime) + `frame-root`/`frame-provider` scoping | **S2** | preflight-exactly-once, non-reseed, StrictMode/HMR-immune fixtures |
| §Roots and mounting — hydration + Root Manifest v1 | **S5** | manifest extension keys; multi-root hydration + failed-root isolation |
| `ui.test` surfaces this Spec references | **S1** core (render/find/find-all/text/attrs/frame over Tier-1 trees; `query` enforces the tier split) → **S2** mounted semantics (`dispatch!`, Promise-backed `with-root`, native-CSS `query`, Promise-backed sole test `flush!`; ordinary DOM events, no gesture DSL) → **S4** `flush-presence!` | selector-grammar fixtures; JVM-subset enforcement; real React mount/query/total-teardown/open-drain/drain-quiescence fixtures |
| §View identity and the instrumentation surface | **S3** → budget/absence gates complete **S6** | manifests, instance records, cause vectors, Xray consumption (compile-time site anchors exist from S1; the evidence schema asserts S3); production erasure G-7/G-11 |
| §The JVM structural subset — structure/props/branches/lists/event intent/`ui/html` + `:rf.error/jvm-host-op` | **S1** | Tier-1 rendering against the tree contract |
| §The JVM structural subset — subs via the pure snapshot path | **S2** | the Q32/Q22 answer: `sub` *grammar* compiles at S1, but no Stage-1 Tier-1 fixture exercises a sub read — a Tier-1 render through a sub site (frame or `:sub-overrides`) is an S2 assertion |
| §Hot reload — the view-side contract | **S2** | the full HMR matrix (08 §2 places it with reactivity, deliberately early) |
| §Removed forms — the absences | **S1** | absences are compile errors + export-surface checks from the first slice |
| §Removed forms — [TRANSITION] freeze + the 004A appendix | **S7** | deletion-wave soak gates; `spec/004A-Reagent-Compat.md` lands |

**The S1 profile — what the R-1 atomic merge requires:** the rows tagged S1 above —
portability law + parity corpus v0 · `defview` grammar/props-ABI/registration/
comparator · template grammar + compile-error roster · prop conversion + `spread` ·
event vectors as structural data · `raw` and `html` compile forms · root identity +
Root Descriptor v1 + client mounts · `ui.test` Tier-1 core · the JVM subset's
non-reactive rows · the removed-forms absences. That set passing its named assertions
**is** "the first conforming Stage-1 slice".

**Ripple-row timing** (the atomic-merge sets for the inventory below): rows marked
[TRANSITION] and every "moves to 004A" row land at **S7** with the appendix;
identity/naming/reservation rows (Conventions reserved `:rf.ui/*` namespace + artifact
registration + lint key, Ownership's new-surface rows, `spec/API.md`'s `re-frame.ui`
additions, the 008 `ui.test`/selector rows) land at **S1** with this rewrite;
behaviour rows land with the stage that asserts their subject (002 frame chain → S2,
006 observation port → S2, 009 evidence schema + catalogue rows → their features'
stages in small batches, 011 → S5).

## Resolved decisions

- **R-1 — staged merge.** The portability law merges immediately (the interim
  amendment); this rewrite merges atomically with the first conforming Stage-1 slice —
  "conforming" is profile-defined: the S1 rows of §Stage conformance profiles.
- **R-2 — shapes final.** The observation port's six-operation target/evidence/lease
  ABI is final (S-3 §5 is the sole shape source per the binding codex2 F1 disposition;
  the rewritten 006 amendment carries it) — no provisional shapes remain anywhere in
  this contract.
- **R-3 — naming.** `re-frame.ui`, alias `ui`, artifact `day8/re-frame2-ui`; supporting
  `re-frame.ui.test` / `.react` / (if earned) `.data`. Separate artifact on a lockstep
  release train initially (R-6).
- **R-4 — the narrow bare-fn law + strict lint** (§Handlers).
- **Presence ruling** — wrapper form, no reserved nodes; `:timeout-ms` mandatory;
  `presence-phase` returns `:present` outside a boundary (§Presence).
- **Refs policy** — `:ref` reserved; object refs preferred; callback refs explicit
  `ui/raw-fn` (§Handlers).
- **`:on-mount`/`:on-unmount` rejected** — mechanical React lifecycle cannot carry
  domain "once" semantics under StrictMode/Activity/HMR/error recovery; `effect
  :connect` is named for what it actually does.
- **Controlled-input synchrony door** — committed; the trigger predicate is confirmed
  sufficient by S-5 (the G-8 real-browser input matrix remains the residual named gate)
  (§Handlers).
- **Push ownership committed** (03; context for `sub`'s one-bridge contract — the pull
  alternative survives only as a falsification benchmark).
- **Carried from the checked-in 004:** bare-keyword heads never resolve against the view
  registry (rf2-n82bbu); no `h` macro; the ephemeral-state placement rule and its
  ownership by this Spec.

---

# Cross-spec ripple inventory (NOT part of the merged spec text)

The rewrite above stays inside Spec 004's ownership. Everything below is inventoried,
not drafted. Line references are against the current checked-in files (revisions read
2026-07-11).

## The six requested targets

| Spec file | Section (lines) | Edit needed |
|---|---|---|
| `spec/002-Frames.md` | Quick-reference example (45) | `(rf/reg-view counter …)` example → `ui/defview` spelling. |
| `spec/002-Frames.md` | Carried-invariant "hold" definitions (924, 938) | Re-word the reg-view-injected-closure hold onto compiled committed-frame handler slots (I-9) — the carried-stamp *semantics* survive; the mechanism named changes. |
| `spec/002-Frames.md` | Pattern-contract box (1134–1135) | Restate: views pure `(props) → template`; context-injection realisation → compiled frame scoping; cite the portability law. |
| `spec/002-Frames.md` | §Resolution: `reg-view` is the boundary + §What `reg-view` injects (1157–1204) | Replace wholesale: no lexical `dispatch`/`subscribe` injection; event vectors + `ui/dispatch-fn` are the boundary; unqualified-name convention paragraph (1168) deleted. |
| `spec/002-Frames.md` | Form-1/2/3 + composition pointer (1247–1262) | Delete Form pointers; re-point at rewritten 004 (§Removed forms, §Roots). |
| `spec/002-Frames.md` | Worked examples using `reg-view` (1307, 1346, 1359–1363) | Mechanical re-spell to `defview`. |
| `spec/002-Frames.md` | Merged `frame-provider` ENSURE shape (1502 and surrounds) | The R-7 staged frame chain: `frame-root`/`frame-provider` split (rf2-nyea0r), ENSURE moved to host preflight for the compiled substrate, commit-owned two-pass ENSURE for legacy adapters [TRANSITION]; "reg-view-registered children resolve" wording → defview. |
| `spec/002-Frames.md` | Frame-keyword-captured-by-value note (1701–1702) | Re-word from injected-closure mechanics to committed-slot publication. |
| `spec/006-ReactiveSubstrate.md` | Adapter op table + `render`/`render-to-string` contracts (117, 197–221) | Re-point "a serialisable nested data structure (per Spec 004)" at the template law; `render` consumes a compiled root; `render-to-string` consumes the JVM structural tree. |
| `spec/006-ReactiveSubstrate.md` | Source-coord wrapping component, Form-2 handling, `:rf/view-id-attr` (356–469) | [TRANSITION] Reagent-adapter render-time wrapper machinery — freezes with the stock-Reagent compatibility tier; the compiled substrate stamps coords at compile time (04 §4). |
| `spec/006-ReactiveSubstrate.md` | Reference adapter implementations over hiccup (883–980) | [TRANSITION] UIx/Helix/slim reference implementations deleted at the adapter wave; the stock-Reagent implementation freezes with the compatibility tier; plain-atom substrate and the JVM emitter binding survive. |
| `spec/006-ReactiveSubstrate.md` | `re-frame.views/current-frame` context reader keyed on `reg-view*` `:contextType` (1111–1116) | Replace with the compiled substrate's frame scoping; the compat-tier path freezes [TRANSITION]. |
| `spec/006-ReactiveSubstrate.md` | UIx/Helix adapter decisions + comparison table + artifact list (1242–1243, 1267, 1289–1301, 1348, 1360–1361) | [TRANSITION] deleted at the adapter wave (adapter matrix collapses to one contract suite). |
| `spec/006-ReactiveSubstrate.md` | NEW section | The R-2 observation port, per the FINAL amendment ([spec-006-observation-port-amendment.md](spec-006-observation-port-amendment.md) — shapes **final**, S-3 §5 the sole source): the six frozen invariants (render probes without ownership · commit acquires the captured target-identity, re-resolving the canonical node · acquire-before-release · synchronous idempotent release · evidence corrected before paint · every queued write epoch executes, then each dirty cell is notified once in the drain's post-quiescence batch); the six operations (`resolve-target` · `probe` · `acquire!` · `current?` · `read` · `release!`) over the target/evidence/lease split; staged transactional multi-acquire rollback; static override leases; internal-fail-loud vs public-recover-to-nil on the ONE catalogue id `:rf.error/no-such-sub`; the named seam `re-frame.substrate.observation` + `port-abi-version` guard; explicitly **outside** the closed public ten-fn adapter map. Plus the three-state lifecycle (hide/unmount as qualified retroactive annotations) and the slice-scoped probe memo. |
| `spec/009-Instrumentation.md` | `:op-type` vocabulary + emission catalogue `:rf.view/*` (30, 156, 203–205) | Extend the `:rf.view` family for the compiled substrate's evidence schema (connected-commit publication). Lifecycle per 03 §4 (three layers, F6): the emitted disconnect fact is `:disconnected {:reason :unknown}`; Activity-hide vs unmount are **qualified retroactive annotations** with proof provenance (`:reconnect` / `:host-teardown` / best-effort `:gc-inference`, no exact timestamp, bounded non-retaining tombstone) — never distinct emitted runtime states. |
| `spec/009-Instrumentation.md` | `:rf.view/rendered` tags incl. `:rf.view/render-args` (269–292, 400) | Props are one map (no positional render-args); render-key wire shape versioned (integer + `:view-id` + `occurrence-path` + `parent-render-key` + `:rf.view/causes` vector with loss accounting). |
| `spec/009-Instrumentation.md` | Cascade walkthrough + dev-table rows + epoch-record renders projection + frame routing for view emits (472, 536, 712–713, 784) | Re-spell onto the new evidence schema; `:epoch-restore` cause carries the restore-operation token. |
| `spec/009-Instrumentation.md` | Frame-observation suppression list (956) | Add the new `:rf.view` evidence emits to the suppression key set. |
| `spec/009-Instrumentation.md` | Performance-marks table `:render` per-`reg-view` wrapper (1369) | Re-key onto the compiled shell (view id), not the reg-view* wrapper. |
| `spec/009-Instrumentation.md` | Call-site coord table rows for reg-view injection (1748–1749) | Replace with compiler site ids / manifest anchors (stable site id shared between build logs and Xray). |
| `spec/009-Instrumentation.md` | Error catalogue (2114, 2174–2178, 2211–2225, 2289–2300) | NEW rows for the 03 §11 taxonomy (`:rf.error/dispatch-disconnected`, `view-not-found`, `root-hydration-mismatch`, `frame-payload-invalid`, `flush-in-open-epoch`, `jvm-host-op`; `:rf.warning/unregistered-event-id`, `placeholder-in-dynamic-vector`, `cross-frame-carried-op`, `render-phase-dispatch`/`-set!`) + the compile-error roster, per the one-catalogue rule. Plus the port rows per the final 006 amendment: NEW `:rf.error/read-after-release`, `:rf.error/reentrant-graph-op`, `:rf.error/observation-port-version-mismatch`; the existing `:rf.error/no-such-sub` and `:rf.error/frame-destroyed` rows gain the port's throwing emit surface (one catalogue id — `:rf.error/no-sub` does not exist). [TRANSITION] the reagent-slim template errors are deleted/re-homed at the adapter wave; rows thrown from `re-frame.core-reg-view-macro` and the `:>`-head SSR error freeze with the compat tier (into `spec/004A-Reagent-Compat.md`). |
| `spec/011-SSR.md` | §Views are pure functions + §The render-tree is serialisable data (23–29) | Restate against the portability law (mirror of the interim amendment, applied to 011's own wording). |
| `spec/011-SSR.md` | Hydration equivalence + `:rf/render-hash` (50–69) and server/client flow steps (99–156) | Per-root contract: normalized structural equivalence + root fingerprint/build digest; flows become per-root (manifest read + validate before `hydrate-root`). |
| `spec/011-SSR.md` | §The render-tree → HTML emitter + source-coord under SSR (225–283) | The emitter consumes the compiled JVM structural tree (generated, closed node set — no unknown-node arm); `[:view-id …]` head resolution and the `:>` exemption row (276) deleted [TRANSITION]; coord stamping is compile-time. |
| `spec/011-SSR.md` | Hydration-mismatch detection incl. FNV-1a hash + `:first-diff-path` (375–423) | Superseded by the root manifest's `render-fingerprint`/`build-digest` + per-root failure isolation; no `suppressHydrationWarning`-style escape. |
| `spec/011-SSR.md` | Trusted-shell / body-content guidance naming `reg-view*` (607) | Re-spell to `defview` + `ui/html` (the explicit trusted-markup spelling). |
| `spec/011-SSR.md` | Server error projection — `:error-view` hiccup (824–855) | Re-word "hiccup" to the template/structural-tree vocabulary; `error-boundary` server policy cross-ref. |
| `spec/011-SSR.md` | §JVM-runnable view rendering incl. Form-3 lifecycle note (888–900) | Replace with the JVM structural subset table (004 owns the subset; 011 references it); Form-3 sentence (898) deleted. |
| `spec/011-SSR.md` | Streaming SSR `:rf/suspense-boundary` hiccup marker (975–1108) | Marker survives as the low-level primitive (06 §5: no authoring sugar before a dual-host parity proof + consumer); re-word marker's carrier from "hiccup" to the template AST / structural tree. |
| `spec/011-SSR.md` | NEW section + cross-ref fix (1210) | Roots vs frames: root identity, the root manifest schema (root-id, element locator, view-id, props, frame-payload-ids, render-fingerprint, build-digest, identifier-prefix, phase), idempotent order-independent frame-payload install, per-root failure scopes, static-root explicit policy (prove + declare), `client-only` phase flip. |
| `spec/Conventions.md` | Per-kind registration macro list (136) | `reg-view` → `ui/defview` (registrar `:view` kind retained). |
| `spec/Conventions.md` | Facade export list (1187) | Re-status `reg-view`/`reg-view*` to `v1 (frozen — compat tier)`, pointing at `spec/004A-Reagent-Compat.md` — **the exports MOVE to the appendix's retained-rows table, they are not removed** ([reagent-compat-boundary.md](reagent-compat-boundary.md) §8; lands S7). The *taught* view surface moves to the `re-frame.ui` namespace (diff-time facade-classification rule applies to any name that stays on `re-frame.core`). |
| `spec/Conventions.md` | `*`-suffix macro/fn pair table + asymmetry footnote (1341–1351) | Re-scope the `reg-view`/`reg-view*` row to the compat tier — the pair lives on in `spec/004A-Reagent-Compat.md`, not deleted; re-word the asymmetry footnote accordingly (lands S7). |
| `spec/Conventions.md` | §`reg-view` auto-id derivation rule (1464–1482) | Re-home as the `defview` id rule (same derivation, `:id` override); the same derivation is also stated on the compat side in `spec/004A-Reagent-Compat.md` — one rule, stated once each side. |
| `spec/Conventions.md` | §Render trees use Vars / bare-keyword-head rule (1486–1501) | Update: compile-resolved Var heads; bare keyword head = DOM/custom element enforced at compile time; `(rf/view id)` reference deleted ([WAVE-2] `ui/view`). |
| `spec/Conventions.md` | Reserved namespaces + packaging sections | NEW: reserve `:rf.ui/*` (the closed placeholder vocabulary `:rf.ui/value` / `:rf.ui/checked` / `:rf.ui/key`); register `day8/re-frame2-ui` (lockstep train per R-6) + `re-frame.ui.data` as artifacts; the `{:re-frame.ui/bare-handlers …}` lint key. |
| `spec/Ownership.md` | View contract row (39) | Re-write: "View contract and `reg-view` … Form-1/2/3" → "`ui/defview`, template grammar + portability law, handler law, presence, JVM structural subset"; artifact cell `day8/re-frame2` → `day8/re-frame2-ui`. |
| `spec/Ownership.md` | Ephemeral view-state placement rule row (40) | Owner unchanged (004); wording gains `local` as the sanctioned spelling. |
| `spec/Ownership.md` | Reagent/UIx/Helix adapter rows + shared React frame Context row (42–45) | [TRANSITION] UIx/Helix(/slim) adapter rows deleted at the adapter wave; the Reagent row re-scopes to the frozen compatibility tier with **`spec/004A-Reagent-Compat.md` as its owning spec**; the shared-context row's consumers become `day8/re-frame2-ui` plus the compat tier. |
| `spec/Ownership.md` | SSR rows (54–56) | Emitter wording (hiccup → structural tree); add the root-manifest surface to the 011 row. |
| `spec/Ownership.md` | NEW rows | Observation port (006, R-2); root manifest + per-root hydration (011); presence + `ui/html` + `ui/error-boundary` (004); `ui.test` contract (008); evidence schema / view manifests (009). |

## Beyond the six (inventoried for completeness; one line each)

- `spec/API.md` — projection: move `reg-view`/`reg-view*`/`view` (and the
  Reagent-adapter rows) to `v1 (frozen — compat tier)` status under
  `spec/004A-Reagent-Compat.md` — relocated, not removed (retained-rows table per
  [reagent-compat-boundary.md](reagent-compat-boundary.md) §8; lands S7); UIx/Helix
  adapter rows delete with their adapters; add the `re-frame.ui` surface per its owning
  rows.
- `spec/Spec-Schemas.md` — projection: `:rf/epoch-record` `:renders`, `:rf/view-id-attr`,
  and the new versioned manifest/instance-record shapes follow their owners.
- `spec/008-Testing.md` — the `ui.test` contract (render/find/query/frame/dispatch!/
  with-root/flush!/flush-presence!), tier table, `.cljc` constraint;
  hiccup-walk `test-helpers` [TRANSITION]. Selector grammar: drafted and reconciled
  with the tree contract ([ui-test-selector-grammar.md](ui-test-selector-grammar.md));
  node reading per [jvm-tree-and-conversion-contract.md](jvm-tree-and-conversion-contract.md).
- `spec/Cross-Spec-Interactions.md` §21 — family-asymmetry entry (reg-view's `*`
  partner) re-scoped to the compat tier (the pair lives on in
  `spec/004A-Reagent-Compat.md`), not retired.
- `spec/Construction-Prompts.md` CP-4 — re-scaffold onto `defview`.
- `migration/from-re-frame-v1/README.md` — view-tier rules re-target `defview` (the
  Reagent path per synthesis doc 10).
- `docs/core/views.md`, guide pages, examples, skills — downstream re-teach (Stage-6
  workstream, budgeted; not spec).

## OPEN roster (synthesis-silent items marked in the draft)

1. ~~The precise `rf=` equality contract~~ — **RULED (Mike, 2026-07-12)**:
   per slot `Object.is(a,b) OR (= a b)`; see §`ui/defview` Memo-by-default.
2. ~~The `ui/custom-element` declaration grammar~~ — **RULED (Mike, 2026-07-12)**:
   `(ui/custom-element tag {:properties #{...}})`, closed; see §Template grammar.
3. ~~The `ui.test` `find`/`query` selector grammar~~ — **DRAFTED + RECONCILED
   (2026-07-12)**: the one page exists
   ([ui-test-selector-grammar.md](ui-test-selector-grammar.md)) and is reconciled with
   the JVM tree contract — view-id selectors match the view-boundary node (its OPEN-1
   resolved) and the attrs projection replaces direct keyword lookup. Its remaining
   [OPEN-2]/[OPEN-3] items (path-form demand; `find!` inclusion) are **demand-bar audit
   items, not synthesis silence**.
4. ~~Provisional-by-design shapes~~ — **SETTLED**: the controlled-input trigger
   predicate is confirmed sufficient by S-5 (residual named gate: the G-8 real-browser
   input matrix); ObservationTarget/Probe/Lease shapes are FINAL (S-3 §5 ruled the sole
   source, codex2 F1; carried by the rewritten 006 amendment). No provisional shapes
   remain.
