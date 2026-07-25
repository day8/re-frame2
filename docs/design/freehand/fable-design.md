# Better UI — Freehand: a two-tier, event-native view substrate for re-frame2

**Freehand** is the product name, and the single public entry point is
`re-frame.freehand`, with `v` as the documented source alias (D001) — all
dream code below uses `v`. Edge namespaces (`re-frame.freehand.test`, plus the
later accepted `.form` and `.controls`) exist only where a bounded capability
materially clarifies; there is no public `.host` namespace. Compiler and runtime
namespaces stay internal; `re-frame.view` is NOT published as an alias (one
door, per the absorption ruling), and `re-frame.ui` is a donor namespace only,
deleted at the gate.

**Role of this document.** Two documents cover Freehand by deliberate division:
`codex-design.md` is the PRODUCT SPINE — the tight statement of what Freehand
is and is not, its conformance surface, and its build order. THIS document is
the ARGUED DOSSIER — the worked dream code, the fitness scoring against the
harness/census/re-com classes, the semantic traces and measurement
obligations, the wounds and pre-mortems, and the decision record (§8). **All
twenty-one design decisions D001–D021 are ratified and folded** (2026-07-22;
dossiers in `decisions/`): where the two documents once forked, the fork is
now a closed ruling carried in the body as settled design, not a numbered
question. Design only: no implementation plan, no staging, no effort
estimates (EP-0036 owns programme slices; the product spine records technical
dependency order). Every load-bearing
claim is verified at source (`file:line` in this checkout; decision headers for
rulings); estimates and knowledge-sourced claims are labelled. Provenance and
the verification ledger are in Appendix C.

**2026-07-26 completion addendum.** The later accepted
[`product-completion-setpoint.md`](product-completion-setpoint.md) and
[D022](decisions/D022-public-react-host-door.md) extend the original twenty-one
decisions. Where this dossier still distinguishes a qualified leaf from a
React-owned wrapper, or sketches a different inward React door, the later
setpoint controls: both are implementation shapes behind one `v/defhost`
descriptor kind.

Two premises govern everything below:

- **The compiled tier is assumed (operator axiom).** Some applications will have
  performance-critical components that must be compiled. The design does not
  argue this; it designs for it. Both tiers ship together. What the corpus
  evidence governs is *placement* — where each tier applies — not whether the
  second tier exists.
- **One reactive state system: re-frame.** Events and subscriptions are the only
  reactive model. No ratoms, no hooks-shaped state, no component-local reactive
  cells in the neutral core. Hooks exist only inside visibly React-bound
  components registered through `v/defhost`.

---

## §1 The design at a glance

re-frame2 gets a view substrate that natively understands events and
subscriptions — not a generic renderer with re-frame glued on. It has two tiers
over one semantic model:

- **The interpreted tier is the paved path.** Views are ordinary Clojure
  functions of one props map returning hiccup — full language, no grammar, no
  compiler. `(sub [:q])` returns a plain value; the substrate records what each
  view boundary read and re-renders exactly the boundaries whose subscriptions
  moved. Event intent lives in the tree as data — `{:on-click [:cart/add 42]}` —
  so "what does this button do" is an equality assertion, on the JVM, with no
  mount.
- **The compiled tier is the hot frontend.** A manually selected view compiles
  under a finite-site grammar, buying the deleted interpretation walk, deleted
  handler minting, hoisted static subtrees, generated comparators, per-view
  manifests, and provable cell elision for sub-free views. Selection is always
  manual and evidence-driven; the seam laws (§3.2) guarantee promotion changes
  one definition site and no test. Shape, by operator ruling: **Absorption** —
  `re-frame.ui`'s compiler, emitters, presence runtime, and test machinery are
  absorbed as the compiled tier's implementation; `re-frame.ui` as a standalone
  artifact enters donor mode and is EVENTUALLY DELETED, gated on the
  conformance contract (§3.6) going green; §3.4 carries the staged posture
  and the worklist.

React is the primary host. The renderer-neutral core is the data plane — trees,
event vectors, controller addresses, the structural test tree — not a
portability layer.

**One native substrate.** The compiled tier is selected as an option on the one
declaration — `(v/defview todo-row {:compiled true} …)` — not a second
namespace: promotion edits one option at one definition site, which is law 2 at
its cleanest. The compiled grammar is versioned (`:re-frame.freehand/v1`)
per substrate release, not per view. The product topology:

| Surface | Relationship to re-frame | Role |
|---|---|---|
| Freehand (this design) | native; assumes re-frame | THE view substrate: interpreted paved path + compiled hot tier, one artifact |
| `re-frame.ui` | native; donor | the compiled tier's implementation source (compiler, emitters, presence runtime, test machinery — absorbed); as a standalone artifact it is in donor mode and eventually DELETED; what does not survive absorption, and where each job moved, is §3.4's worklist |
| Reagent · UIx · Helix | independent | adapters over external renderers; the React-ecosystem escape, retained |
| Replicant | independent | the whole-state, subscription-free alternative renderer — an architectural comparison point, not a co-mounted layer |

**The decisions, one row each** (adjudication for each in the cited section):

| Axis | Adopted | Where |
|---|---|---|
| Render granularity | the sharp declaration boundary (D002) — `v/defview` declares every boundary, `[view props]` mounts it; `defn` helpers inline with parens and are never vector heads; the one-character paren/bracket dial is retired | §2.1, §2.2 |
| `sub` | plain value, render-only (D005) — tracked capture inside an active declared render (query-value-keyed); outside a render it is a typed error naming `rf/subscribe-once` as the recovery | §2.2 |
| Scheduling | host-checkpoint render batches; one sanctioned synchronous door for controlled inputs (D009: frame-scoped flush confirmed) | §2.2, §2.3 |
| Events | flat `:on-*`; vectors + options map + closed placeholder trio + key-condition maps (D007); materialize-at-the-adapter splice (D006); **one event per user action** (no multi-intent vectors) | §2.3 |
| Instance state | semantic controllers (D003 + D004) — library-registered records keyed by kind + explicit caller-supplied `:control` address, semantic transition events; occurrence identity stays in the tool plane; raw storage verbs only for protocol-free state; controlled-first stands | §2.4 |
| Host integration | registered behaviors (D013) — `host/defbehavior` + `::v/behavior` use-site data, connect/update/disconnect, bounded `:commands` with semantic-id targets; React ref protocols stay inside explicit wrappers | §2.5 |
| Data orientation | purpose-ranked doctrine (testability, AI-authorability first); inline `v/sub` is the one read language in v1 (D012 — no `:reads` declaration) | §2.5 |
| Tier-2 shape | ABSORPTION, by operator ruling: re-frame.ui's machinery becomes the compiled tier; the standalone artifact is deleted at the gate | §3.4 |
| Tier placement | app pages interpret; library leaves and row templates compile at birth; promotion by evidence, never folklore | §3.5 |
| Renderer ownership | React-first; snabbdom as an architectural probe, not a promise | §2.6 |

**Public concepts** on the paved path: hiccup · declared boundaries
(`v/defview` + `[view props]`) · `sub` · event forms · `:key` · controller
records + `:control` addresses · the escape roster. Seven; everything else is
Clojure. The full budget, including the hatch tier and the library author's
tier-2 load, is Appendix B.2.

---

## §2 The interpreted tier

Namespace in all dream code: `(:require [re-frame.freehand :as v :refer [sub]])`.

### §2.1 Dream code

**A tiny view.** `v/defview` declares a boundary; `[cart-badge {}]` mounts it;
the tree carries intent as data (the sharp declaration boundary, D002).

```clojure
(v/defview cart-badge [_]
  [:span.badge (sub [:cart/count])])

(v/defview header [_]
  [:header.main
   [:h1 "Shoply"]
   [cart-badge {}]
   [:button {:on-click [:nav/go :cart]} "Cart"]])
```

A `:cart/count` change re-renders the badge alone. Helpers are ordinary `defn`s
called with parens — `(price-line {...})` runs inside the enclosing boundary,
owns nothing (a `sub` reached through a helper records against the enclosing
view), and is never a vector head. A declared view, conversely, interns a
descriptor that cannot be successfully CALLED, so `(cart-badge {})` is a
didactic error naming the three recoveries: mount it with `[cart-badge props]`,
declare it with a plain `defn` and keep the parentheses to inline it, or extract
a plain `defn` helper both can share. The descriptor implements the host call
protocol solely in order to throw (D002, amended 2026-07-22), so `ifn?` answers
true and says nothing about mountability — ask `v/view?`. One total classification rule
everywhere (interpreter, compiled analyzer, JVM structural host): a vector head
is a Freehand descriptor, a keyword element, or a declared host boundary —
anything else is an error naming those three legal forms. The ownership move
from helper to boundary is one visible edit — `defn` → `v/defview`, parens →
brackets — which is exactly where a subscription-owning, memoized,
error-bounded boundary should surface in review; no region DSL compensates,
because a plain helper already is the deliberately coarse region. Against the
Reagent spelling, the deref, the reaction object, and the `#(dispatch …)`
closure are gone.

**A keyed editable list.** Rows subscribe themselves — the corpus's own
granularity idiom (todomvc reads `[:todo.ui/editing? id]` per row), kept, minus
ratoms:

```clojure
(v/defview todo-row [{:keys [id]}]
  (let [{:keys [title done?]} (sub [:todo/by-id id])
        editing?              (sub [:todo.ui/editing? id])]
    [:li.todo {:class (str/join " " (cond-> [] done? (conj "completed")
                                              editing? (conj "editing")))}
     [:div.view
      [:input.toggle {:type :checkbox :checked done? :on-change [:todo/toggle id]}]
      [:label {:on-double-click [:todo.ui/start-edit id]} title]
      [:button.destroy {:on-click [:todo/delete id]}]]
     (when editing?
       [:input.edit {:value      (or (sub [:todo.ui/draft :edit]) "")
                     :auto-focus true
                     :on-input   [:todo.ui/edit-field :edit ::v/value]
                     :on-blur    [:todo.ui/commit-edit]
                     :on-key-down {"Enter"  [:todo.ui/commit-edit]
                                   "Escape" [:todo.ui/stop-edit]}}])]))

(v/defview todo-list [_]
  [:ul.todo-list
   (for [id (sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])
```

The list reads only the id vector; renaming a todo re-renders one row; reorder
moves row instances by key with no row render (their props are `rf=`). Reagent's
five threaded callbacks and its `handle-keydown` closure casing on `.-key`
(examples/core/todomvc/views.cljs:45-62) became event vectors, one placeholder,
and a key-condition map; the focus `:ref` callback became `:auto-focus` (under
keyed reconciliation the edit input mounts when `editing?` flips). "What does
Enter do in the edit box" is an equality assertion.

**The routed async page — the RealWorld editor.** Everything below the view tier
is the shipped example, unchanged: the route owns the article read and releases
it on leave; seeding is the slug-correlated ownerless reply; the write is a
mutation instance; `:can-leave` guards navigation
(examples/real-apps/realworld_resources/article_editor.cljs, routing.cljs). The
view owns no lifecycle — and interpretation buys markup abstraction with plain
data and functions:

```clojure
(def fields
  [{:field :title       :kind :input    :ph "Article Title" :big? true}
   {:field :description :kind :input    :ph "What's this article about?"}
   {:field :body        :kind :textarea :ph "Write your article (in markdown)" :rows 8}
   {:field :tagList     :kind :input    :ph "Enter tags (comma-separated)" :no-blur? true}])

(defn field-group [{:keys [field kind ph rows big? no-blur?]} draft busy?]
  ;; a plain helper: paren-called, inlined into the enclosing boundary; its
  ;; `sub` records against editor-page
  (let [err (when-not no-blur? (sub [:editor/field-error field]))]
    [:fieldset.form-group
     [(if (= kind :textarea) :textarea.form-control :input.form-control)
      (cond-> {:placeholder ph :value (get draft field) :disabled busy?
               :on-input [:editor/edit-field field ::v/value]}
        rows           (assoc :rows rows)
        big?           (assoc :class "form-control-lg")
        (not no-blur?) (assoc :on-blur [:editor/blur-field field]))]
     (when err [:div.error-messages err])]))

(v/defview editor-page [_]
  (let [draft (sub [:editor/draft])
        slug  (sub [:editor/slug])
        save  (sub [:rf/mutation {:instance :editor/save}])
        busy? (:pending? save)]
    [:div.editor-page
     [:div.container.page
      [:div.row
       [:div.col-md-10.offset-md-1.col-xs-12
        (when (:error? save)
          [:ul.error-messages [:li "That didn't save. Try again."]])
        [:form {:on-submit {:event [:editor/submit] :prevent-default true}}
         [:fieldset
          (map #(field-group % draft busy?) fields)
          [:button.btn.btn-lg.btn-primary
           {:type :submit :disabled (or busy? (not (sub [:editor/can-submit?])))}
           (if (some? slug) "Update Article" "Publish Article")]
          (when (some? slug)
            [:button.btn.btn-outline-danger
             {:type :button :disabled busy? :on-click [:editor/delete]}
             "Delete Article"])]]]]]]))
```

Against the shipped Reagent rendition, every `#(dispatch …)` /
`(.. % -target -value)` closure and the `(fn [e] (.preventDefault e) …)` submit
wrapper became data; against the shipped compiled rendition, the four
hand-unrolled fieldsets collapse into a data table plus a 12-line helper. That
collapse is the interpretation dividend — and it is exactly what the compiled
grammar cannot express (computed heads, markup-returning `map`): this page is
the cold-start table's first row (§3.5) — pages stay interpreted.

**A third-party React wrapper.** Foreign heads pass props through untouched; a
bare fn callback at a foreign boundary is refused at mint time with the didactic
choose-your-form error (spec/004-Views.md:408, enforced at runtime):

```clojure
(v/defview departure-picker [_]
  (let [date (sub [:booking/departure])]
    [:div.field
     [:label "Departure"]
     [DatePicker {:selected  (some-> date js/Date.)
                  :minDate   (js/Date.)
                  :onChange  (v/event [d] [:booking/departure-changed (iso-date d)])
                  :className "picker"}]]))
```

The callback roster is closed and role-specific (D008; there is no
`v/dispatcher` prefix-to-callback form: appending raw callback arguments would
invite mutable dates and host objects into event vectors, and `v/event` already
says conversion-to-plain-data explicitly). `v/event` converts the live callback
payload synchronously into ONE event vector or `nil` — no `sub`, hooks, refs,
or effects inside. Identity is the per-site committed slot: the runtime mints
one proxy per `(committed site, callback-prop)`; a later selected commit
updates the proxy's body atomically WITHOUT changing its JS identity, so
unchanged-or-updated intent never churns a foreign `React.memo` or effect-deps
array — and these committed per-site slots are reused verbatim as foreign
props (the shipped mechanism; no dependency arrays exist anywhere). A key
change, node replacement, disconnect, or incompatible HMR generation retires
the proxy; a retired proxy is inert and emits development evidence rather than
dispatching into a replacement owner. Equal callback values at two different
sites keep independent proxies. `v/handler` (explicit imperative foreign work,
same per-site committed identity, cleanup at disconnect), `v/render-fn` (pure
render props a foreign owner invokes during ITS render — deliberately NO
cross-mode identity guarantee, because it can run during uncommitted candidate
renders; compiled lowering may reuse it while captures are `rf=`-equal), and
`v/raw-fn` (expert pass-through when authored function identity is itself
protocol data — exactly the supplied identity, no stabilization promised)
complete the four-form escape roster with the compiled tier's exact semantics
(004:399-410). Bare fns stay legal at native `:on-*` sites and as opaque values
between internal views; at declared foreign callback positions they are refused
at mint time with the didactic error naming the roster.

### §2.2 The reactor — what `sub` means

One sentence, complete (D005): **`v/sub` is a reactive read owned by
the active declared-view render, and it is legal nowhere else.** Outside a
render — a `v/event` or `v/handler` body, a timer, a promise, a host callback,
the REPL — it raises a typed error identifying the query and naming the
recovery, so the classic stale-closure mistake (a callback that believes it
retained a reactive read) fails where it is authored instead of observing a
late value from the wrong moment. The error distinguishes "outside render"
from "wrong thread during render" and from "no frame context." The deliberate
one-shot read keeps its own name: `rf/subscribe-once` (core.cljc:1053-1064) —
resolve, probe, return, release; no watch, no retained handle, no committed
dependency — for REPL exploration, test setup, tooling, and non-reactive
server-side calculation. Callbacks that need a decision based on changing
state emit an intent and let the handler consult committed state. The JVM
structural render extent counts as inside. Absorption reuses the donor's
proven resolution, override, probing, and stabilization machinery beneath both
operations; the donor's ambient dual-mode overload of one name does not
survive it.

Inside a boundary render, the ambient capture
records each read; **site identity is the query value** — the dependency set of
a render is the set of queries it read; a repeated read dedups instead of
throwing. The read path inherits from the proven ViewCell reactor unchanged:
query stabilization (an `rf=`-equal query reuses the prior exact object), the
`:sub-overrides` door (Story/test pinning, reactive.cljc:242-341), frame
resolution (explicit pin → binding → context → loud
`:rf.error/no-frame-context`), the slice-scoped probe memo (sibling boundaries
share cold derivation parents within one render slice), and value stabilization
(`rf=`-stable reads return the prior exact reference so memo stays
identity-fast).

Commit is the inherited 8-step transactional reconciler (reactive.cljc:3577+):
kept-check retained queries; stage-acquire before any release (a shared sub node
never crosses its zero-owner edge; failure rolls back staged handles); evidence
comparison for staged AND retained handles before paint; publish atomically;
release dropped queries; advance revision synchronously if evidence moved.
**Conditional subscriptions are not a mechanism — they are the set diff**: a
branch not taken this render is absent from the capture, classified dropped,
its watch released. Abandoned renders (StrictMode double-render, tear-off, a
throw) own nothing, structurally.

Scheduling is the host-checkpoint batch law (reactive.cljc:61-154): a render
batch is the pending window ending at the next host checkpoint; N epochs in one
drain coalesce into one render; invalidation is constant-work (mark, never
compute); the first mark arms one microtask; the JVM auto-schedules nothing and
flushes explicitly. The one sanctioned exception is the controlled-input
synchronous door (§2.3). Boundaries are memoized on generic `rf=` over the one
props map — the compiled memo-by-default law (004:135-148) with an honest
constant-factor downgrade (generic map compare vs generated comparator). **Two
precision laws on top:** the selected commit publishes the whole candidate
bundle atomically — dependencies, event sites, and tree evidence together, for
one descriptor revision and one frame incarnation — so subscription ownership
and event bodies can never come from different generations; and **frame
retarget beats memo**: a provider retargeting a subtree from frame A to frame B
rebinds children even when their props are `rf=`-equal (frame context is not a
prop; a compiled shell may elide frame machinery only when its manifest proves
the view and its events frame-insensitive). Capture is same-render-thread only:
a `sub` reached through `future`/`pmap`/a conveyed thread fails before probing,
naming same-thread realization or keyed child views as the recovery (the
inherited conveyed-thread guard). Handle cardinality is INTERNAL, not ABI: the
interpreted runtime may deduplicate invalidation edges by resolved target while
the compiled runtime owns balanced per-site handles — tools report read
occurrences and distinct targets without equating them, which is what lets the
two observation implementations differ without the seam noticing.

### §2.3 The event grammar

Flat `:on-*` keys; classification is deterministic by value, at wiring time.
The base is the compiled decision table (004:399-421); this grammar EXTENDS it
with exactly one form — the key-condition map — priced in the tier-2 roster
(§3.6) and the absorption worklist (§3.4):

```clojure
{:on-click  [:cart/add 42]}                                  ; 1 intent — the 90%
{:on-input  [:form/typed :email ::v/value]}                  ; 2 placeholder splice (closed trio)
{:on-submit {:event [:editor/submit] :prevent-default true}} ; 3 options map — mechanics as data
{:on-key-down {"Enter"  [:form/submit]                       ; 4 key-condition map (:on-key-* only);
               "Escape" {:event [:modal/close]               ;   values are forms 1/2/3 or v/event,
                         :prevent-default true}}}            ;   one level of recursion
{:on-focus-out (v/event [e]                                  ; 5 mechanics escape: fn over the live
                 (when-not (.contains (.-currentTarget e)    ;   event RETURNING intent (nil ⇒ none),
                                      (.-relatedTarget e))   ;   per-site stable
                   [:menu/close]))}
```

Classification: vector → intent; map with `:event` → options form; string-keyed
map on a key-event attribute → key conditions; `v/event`/`v/handler` →
themselves; bare fn → legal only in known native event positions (the narrow
bare-fn law, 004:411-421); nil → no handler; a vector whose head is a vector is
a didactic wiring-time error naming the one-event respelling.

**One event per user action — the multi-intent law.** There is no
vector-of-vectors form. Three grounds, any one sufficient: the u53yy fence
rejects action-vector batching in handler values as a second effects language; a
vector-of-vectors sits outside the door's known-outcome clause (004:455-461), so
every controlled site carrying one would silently forfeit the caret/IME
synchrony law; and one semantic event whose effects express the transaction is
better causality — one epoch, one trace cause, per user action. Composed actions
spell as one event: app-level, a domain event; in reusable controls, a
library-registered consult-state event carrying the caller's prefix
(`[:fh.dropdown/select control commit value]` — §4's gallery). This deletion is
what keeps the door perimeter clean: every paved-path handler is a single
literal vector, door-eligible under the inherited law unchanged.

The key-condition map is confirmed (D007) on R-B5 grounds:
listbox-tier keyboard needs per-key intent AND per-key `:prevent-default` as
data — a dispatch-then-decide handler cannot suppress the page-scroll default
retroactively, and `v/event` closures would be opaque at exactly the library's
hottest keyboard surfaces. The closed boundary, normatively: legal only on
`:on-key-down` and `:on-key-up`; keys are exact `KeyboardEvent.key` strings;
values are the existing event forms (vector, options map, `v/event`, `nil`);
selection is one level; a missing key is a no-op; **no branch matches while
`KeyboardEvent.isComposing` is true** — a control that deliberately handles
composition keys uses `v/event`; mixed key strings and listener-option keys in
one map are a typed authoring error; options such as `:prevent-default` apply
only to the selected branch and execute before dispatch; no wildcard,
ordering, regex, modifier syntax, platform alias, or state predicate exists in
the grammar. Modifier chords and any mechanics decision that depends on live
application state stay in `v/event` — the census counts three
keyboard-condition sites against 93 pure-intent ones; frequency justifies
syntax. The form carries a delete-before-release pilot gate: the dropdown
pilot must express its repeated, state-independent movement keys as data
without hiding mechanics in callbacks, the typeahead pilot must leave its
state-dependent Tab behavior in `v/event` without expanding the map language,
and `:on-key-up`'s weaker corpus evidence is called out in the pilot report —
if that repeated use fails to materialize, the form is deleted before release.
Stated honestly at library scale: the data grammar carries roughly two-thirds
of a rich widget's keyboard surface (plain keys with per-key preventDefault);
the residue — modifier chords, keys whose preventDefault depends on live state
(Tab-as-select when suggestions exist), measurement-dependent paging — is
`v/event` territory by design, and each such site is a mounted-test obligation
(§5.5).

Flat `:on-*` over Replicant's nested `{:on {…}}` is decided by the shared
vocabulary: the compiled tier normatively spells `:on-*` (004:379-385), a
tier-1-only grammar would make every promotion rewrite every handler, the
host-neutrality nesting buys is already achieved by meaning (`[:cart/add 42]`
contains zero React), and the normalized structural tree groups events under
`:events` for tools anyway.

**Placeholders** splice at firing time by value, uniformly in both tiers
(D006): the reserved scalar trio `::v/value` / `::v/checked` /
`::v/key` — the permanent Freehand-qualified spellings; donor `:rf.ui/*`
spellings are replaced mechanically at migration, never aliased — splices
wherever it appears in the top-level argument positions of the dispatched
vector, so `(conj commit ::v/value)`, the reusable-control idiom, means the
same thing everywhere. **Materialization lives in Freehand's event adapter,
not in general dispatch.** The native or qualified-host adapter builds a small
payload map from the live callback
(`{::v/value (.. e -target -value), ::v/checked …, ::v/key …}`) and applies
one pure materializer whose semantics are deliberately small: position zero is
never a marker; matching markers are replaced only in top-level argument
positions, every occurrence; nested markers pass through as ordinary data; an
unavailable requested payload is a typed error and NO malformed event
dispatches; the result is a plain vector before re-frame sees it. Ordinary
`rf/dispatch` gains no payload-map arity — a projection keyword in a domain
event is never secretly interpreted. Tests reuse the production materializer
through the test namespace: `v.test/materialize` (pure) and `v.test/dispatch`
(materialize-then-dispatch, `(v.test/dispatch intent {::v/value "x"})`) — one
mechanism shared by production and tests, no test-only splice idiom to drift.
Minted callbacks additionally stamp the dispatch trace with `:source :ui` and
dev-only tags `{:rf.view/anchor <anchor> :rf.view/attr :on-click}` — so a
trace answers "what dispatched this?" with the exact site, closing the loop
the self-describing tree opens (tree→intent forward, trace→site backward).
This deliberately supersedes the compiled tier's literal-only recognition
(004:434-437) inside this substrate (worklist row 2, §3.4); the closed trio lives in
the reserved keyword catalogue, and a fourth projection requires a new grammar
decision. The false positive — a reserved keyword travelling as ordinary
data — is fenced by the reserved namespace plus a dev warning on reserved
keywords in non-handler dispatches; residual risk accepted and labelled.

**Intent payloads are plain data.** Segments riding an intent vector must have
value semantics — keywords, strings (ISO dates, not date objects), numbers,
small collections — because the handler cache, `rf=` memoization, traces,
serialization, and replay all ride value equality; a JS `RegExp` or a mutable
date object in a vector silently defeats them all. Host objects and
non-value policies go through `v/event` or a registered policy instead; dev
warns on non-value payloads in minted handlers. Fn-valued props between
internal views (item renderers, extractors, validators) remain legal opaque
values (the C-13a law) with one taught discipline: they should be top-level
defs or registry-named renderers — an inline lambda re-mints per parent render
and silently defeats the `rf=` memo on every boundary it crosses; the dev
sweep warns on per-render fn-prop identity churn at a memoized boundary.

**Minted DOM callbacks are owned PER SITE**: the owner is
`(committed node identity, event-prop)`, where node identity is key-aware
within its sibling scope and positional only when no key exists. A site's
callback is stable across renders while its handler value is `rf=`-unchanged;
equal handler values at two DIFFERENT sites retain independent callbacks. This
is deliberate three times over: `:once` state is per site; the trace stamp
(anchor + attr, above) is per site — a value-shared callback could not say
which site fired; and the door class is the site's own fact (a controlled and
an uncontrolled `:on-input` carrying the same forwarded vector schedule
differently), so no cross-site sharing could honor both. Removal, key changes,
disconnect, and HMR clean up the exact site. Native attachment needed for
`:passive`/`:once` is internal event-adapter lifecycle, never a public effect
system.

**Attr forwarding joins the common grammar.** `(v/spread-safe owned-literal
caller-attrs)` forwards caller DOM attrs beneath literal owned props — it
denies `:key`, `:ref`, `:value`, `:checked`, and owned handlers, composes
class, and PRESERVES the controlled-input door proof (the one dynamic-map form
that does not forfeit the door, 004:466-471); `(v/spread …)` is the visible
open-props escape at foreign boundaries where literal owned props win. Both
mean the same thing in both tiers — the attrs-forwarding wrapper
(`labelled-field` forwarding to an inner input) is ordinary vocabulary, not a
compiled-only grammar.

**The synchronous door** (D009: the narrow door with a frame-scoped
flush). Dispatches from `:on-input`/`:on-change` on elements carrying
`:value`/`:checked` (presence, including `nil`, is the controlled predicate)
drain synchronously within the DOM event — event → materialize one intent →
dispatch against the exact committed frame → drain re-frame → flush dirty
ViewCells observing that frame → return to React's discrete-event processing —
so value round-trips cannot drop characters, jump the caret, or break IME
(004:448-471). Four rulings pin the perimeter. **`:on-before-input` is not a
door site**: `beforeinput` fires before the DOM mutation, so `target.value` is
not generally the candidate value and IME needs its own evidence — it remains
a normal event site; admitting it would require a browser-backed projection
and composition contract, not a name added to a list. **Options maps carrying
a known vector are door-eligible** — a site qualifies when its selected
outcome is synchronously known to be one event vector or `nil` (a literal
vector, an options map containing one, or a synchronous `v/event`), and only
while its listener options keep it on the same native attachment lane
(`:capture` and `:passive` are excluded until that lane passes the same
browser proof); `nil` means no dispatch and no flush. **The flush is
frame-scoped, confirmed; cell-scoped flushing is rejected** — a targeted flush
would let sibling occurrences commit an older snapshot of the same frame and
requires a new scheduling proof the donor mechanism already has; the honest
consequence (keystroke latency coupling to background dirty work, A.2b) is
visible, measurable, and reducible by ordinary granularity discipline; the
contention harness records both the input boundary's commit and the frame's
settlement/presentation channel, and only the user-visible channel could
justify a future targeted design — an implementation optimization, never
permission for local mirrors. **Promotion parity is an implementation
obligation**: the absorbed emitter must carry a statically proved door
classification through a dynamic vector/options handler site — emitting an
unconditionally asynchronous dynamic handler would silently change an input
from synchronous to batched, a violation; compilation either pins the common
class or rejects the site. Key-condition maps never reach the door (key events
are outside its site list); dynamic props maps forfeit it into batching with a
dev diagnostic, in both tiers — `v/spread-safe` is the one dynamic-map form
that preserves the proof; an opaque foreign spread cannot claim it. Dev
evidence reports classification flapping on BOTH axes — controlled prop
present/absent and handler class vector/options/closure; compiling a
flap-prone input site pins the door statically — the cheapest predictability
purchase in the design (§3.5's placement table).

### §2.4 Instance state under the one-state-system pin

No ratoms, no `local` (the compiled substrate ships `local` deliberately outside
epochs, 004:574-612; this substrate's pin supersedes it — priced in §7.1), no
hooks. Controls are CONTROLLED-FIRST: value-in / intent-out with zero instance
state is the default contract; the family below exists only for state a control
genuinely owns. The wider read gradient runs the same direction: prefer
PROPS-ONLY views for reusable and leaf presentation (data in, hiccup out — the
Replicant flow, available whole through one page-level view-model sub when that
is the clearer model); place scoped `sub` reads at application boundaries where
locality, invalidation, and derivation reuse pay; never thread the whole
database through presentation to satisfy a purity slogan. App state is app-db;
the design problem is per-instance scoping for reusable controls without
collision, at the cost of exactly one explicit prop where durable state
exists.

- **Two identities, two jobs (D004).** The renderer OCCURRENCE
  identity — `(view-id, parent occurrence, key-or-position)` — answers "which
  mounted view/node/event site is this?" It is computed by the runtime for
  reconciliation, presence, event-site ownership, connection generations, HMR
  fencing, and evidence; it appears in error messages, dev warnings, traces,
  and Xray; boundary nodes in the structural tree carry it (a tree-schema
  field). It is NEVER a writable application-state address, and no public
  reader for it exists in v1 — `v/self` is not API; any public occurrence
  reader would be a separate decision. Conflating the two identities would
  make render refactors into state migrations.
- **Semantic state addresses are explicit and caller-supplied.** A stateful
  control receives a conventional `:control` value — immutable EDN naming
  causal ownership (`[:invoice invoice-id :amount]`, `[:editor article-id
  :title]`, `[:route route-instance :filters :status]`) — never a DOM id,
  React key, callback, or runtime token. The controller's storage key is
  `(controller-kind, control-address)`; the kind prevents a dropdown and a
  field from interpreting the same record. `:key` still selects siblings for
  reconciliation; it may equal part of the address, but Freehand never derives
  one from the other. A control library may derive an address from explicit
  semantic props when the derivation is total and documented; it may never
  fall back to render position — the substrate does not mint durable
  application identity from an unstable render position. Reordering, parent
  extraction, view renames, HMR, Story rendering, virtualization, and
  temporary absence leave `[:invoice id :amount]` fixed; a test or Story scene
  seeds the exact address it passes, with no parent tree to reproduce; and one
  identity rule holds in both execution modes. The cost is one prop exactly
  where durable state exists — useful friction that makes collision,
  persistence, tests, and cleanup discussable at the call site. State
  retention follows the semantic owner: a route/form/workflow event clears its
  addresses; unmount removes only the occurrence join. The
  occurrence-to-controller join is development evidence only — it lets tools
  navigate from a DOM/view occurrence to its state without making the
  occurrence the key.
- **Semantic controllers over shared infrastructure (D003).** When a
  reusable control genuinely owns an interaction protocol, its state is an
  ordinary frame-scoped controller RECORD, keyed by controller kind + the
  explicit `:control` address (D004's contract, above):

  ```clojure
  {:re-frame.freehand/controllers
   {[:fh/buffered-field [:invoice 42 :amount]]
    {:reset-key 7 :draft "12."}}}
  ```

  The LIBRARY registers the record's schema and its semantic transitions;
  rendered intents name protocol actions — `edited`, `committed`, `cancelled`,
  `opened`, `moved` — and a transition may update controller state AND dispatch
  the caller's intent as effects of one semantic event. The substrate supplies
  only the minimum: record location, frame scoping, generation fencing,
  evidence, and retention — never a controller DSL (pilots first: the buffered
  field, then dropdown and typeahead, before any common mechanics are
  extracted). No controller API exposes refs, effects, hooks, or DOM
  instances.

**The protocol-free annex.** Raw storage verbs (`:rf.inst/put` / `toggle` /
`clear` over the same record root) remain legal ONLY for protocol-free state —
no phase, no generation, no delayed-event decision, no caller intent. The test
is mechanical: once any of those exists, every hard case in the control's table
contains a decision that must consult COMMITTED state after the minting render
is stale, which a storage verb structurally cannot do — so it must be a
library-owned semantic event. The disclosure's `:open?` is the annex's worked
example (the one consultation-free case); storage verbs are not the taught
component-authoring model, and a trace of `put :draft` saying where-not-why is
exactly what the semantic events exist to prevent. (Spelling note: `[:rf/inst
address]` reads elsewhere in this document are shorthand for the controller
record root under `:re-frame.freehand/controllers`; the address is D004's
explicit `:control` value. Retention is unchanged: state persists until a
semantic transition or its causal owner clears it, unmount never dispatches
cleanup, and dev tools report orphaned records so retention stays visible
rather than magical.)

The disclosure — the annex's one legitimate case; even a one-off visual
control pays the one address prop, which is D004's accepted cost:

```clojure
(v/defview disclosure [{:keys [control label children]}]
  (let [open? (boolean (sub [:rf/inst control :open?]))]
    [:section.disclosure
     [:button {:aria-expanded open? :on-click [:rf.inst/toggle control :open?]} label]
     (when open? [:div.body children])]))
```

Because the address is an ordinary prop value, the rendered tree is fully
self-describing — the button says in data what clicking it does, and a JVM test
asserts it with `=`, then dispatches that very value. What the pin pays back:
the open menu and the half-typed draft time-travel with `restore-epoch`, appear
in Xray with zero new tooling, validate under schemas, and drive headlessly.
Retention is persist-until-cleared — honest under replay; cleanup is the
control's own commit flow or the owner's `clear-under`; no dispatch-at-unmount
exists anywhere.

**The consult-state commit law.** A minted handler is a render-time VALUE —
nothing re-evaluates at fire time — so any decision that must reflect current
state lives in the event layer: commit events read the committed controller
record at its address at fire
time and no-op unless the stored facts say the action is live.
Render-time values may ride in the intent (a carried generation key re-mints
with the prop); the DECISION is always made against committed state. Every
library event in §4 obeys this law; every render-time `(when guard …)` around a
handler expression is the bug class it replaces.

**Identity hazards, fenced** (full pricing in §7.1): because writable state
never derives from render position, the positional-swap corruption class is
gone by construction. What remains is fenced at the address layer: two mounted
WRITABLE owners of the same `(kind, address)` are a didactic dev error
reporting both source locations and occurrences, unless the controller kind
explicitly declares shared ownership — multiple readers are harmless. Address
migration is visible domain work: when a temporary row id is replaced by a
server id, the caller maps or migrates the controller address in data — a
`:key` change alone never orphans controller state, because `:key` is not the
address. The keyed-list law is unweakened: keys remain mandatory wherever
sibling identity matters, and unkeyed same-view multiplicity keeps its
reconciliation warning; those are occurrence facts, not state facts. Docs and
dev warnings teach why random ids to dodge address design defeat replay and
tests — addresses name causal owners.

Host integration (refs, measurement, focus mechanics, observers) is NOT
interaction state: it lives at the behavior registry and the explicit React
boundary (§2.5), never in app-db.

### §2.5 The data-orientation doctrine

**Why data — the purpose hierarchy**, so every "should X be data?" question has
a test. In priority order: (1) **Testability** — intent assertable by `=` on the
JVM without a mount; the crown jewel every other section defends. (2)
**AI-authorability** — the project mission: models generate, verify, and
mechanically transform data grammars far more reliably than closures; the
generated context sheet works because the surface is data. (3)
**Inspectability** — tools answer "what does this button do / what does this
view read / what is attached to this node" from values. (4) **Traceability** —
the causal chain is data end-to-end; the Spec 009 instrumentation plane extends
into the view tier with zero new vocabulary. (5) **Replay honesty** — state and
intent as values make epochs restorable and Story scenes serializable. (6)
**Identity economics** — `rf=` on values drives handler caches, memo, and
change detection. (7) **Host-neutrality** — a consequence, not a goal. The
LIMIT is binding: the burden of proof sits on the closure, but a function
remains the smallest honest escape hatch where mechanics are genuinely
host-shaped, and an elaborate DSL is worse than either.

**Registered behaviors — imperative host integration as data (D013).**
Replicant's best organ (lifecycle hooks as data + remembered host state) enters
in re-frame's own shape — intent in the tree, implementation registered once,
exactly like events/`reg-event` and fx/`reg-fx` — under the spine's protocol
and vocabulary (behavior, not a second "attach" concept):

```clojure
(host/defbehavior chart-host
  {:connect    (fn [node ctx {:keys [series]}] (chart/create node series))  ; → state
   :update     (fn [node ctx inst old new]
                 (when (not= (:series old) (:series new))
                   (chart/set-series inst (:series new)))
                 inst)
   :disconnect (fn [node ctx inst] (chart/destroy inst))
   :commands   {:export (fn [node ctx inst args] (chart/export inst args))}})

(v/defview revenue-chart [{:keys [series]}]
  [:div.chart {::v/behavior [chart-host {:series series}]}])  ; the USE SITE is data
```

The tree carries `[chart-host {:series …}]` — comparable, serializable, assertable
by `=` in a JVM test (inert there); the imperative code lives in one registered
place. `:connect` runs only after a selected commit, `:update` on committed
`rf=`-changed config, `:disconnect` exactly once per committed connection,
threading remembered state (WeakMap-backed on the client). Registration timing is
closed: `:timing :passive` is the default; `:timing :layout` is reserved for
measurement or mutation that must finish before paint and carries the no-wrong-
position-paint proof. This is registry metadata, not a lifecycle callback DSL.
**`ctx`** is
deliberately small — committed-generation-bound `:dispatch` plus diagnostic
identity, and NOT an unrestricted frame query function: values flow in through
config; hidden imperative frame reads are refused. The dispatch is the
EVENTS-OUT half of every integration (an editor's update-listener, a
spreadsheet's ValueChanged) — host events become ordinary re-frame intents
through it, and a disconnected generation is inert. **`:commands`** is the
COMMANDS-IN half, one bounded framework fx:
`[:re-frame.freehand.host/command {:target [:invoice-sheet sheet-id]
:op :export-xlsx :args {…}}]` — commands are finite keyword operations
registered with the behavior type; arguments and recorded outcomes are values;
the target is a caller-supplied semantic instance id declared at the use site
(an `:instance` config value), NEVER a derived occurrence, key path, DOM
selector, or host object, and must be unique among live connections in its
command scope (multiple roots sharing a frame name the Root Descriptor id —
Freehand never guesses from whichever node mounted last). A command runs only
against the currently committed connection: it is never queued for a future
mount and never replayed after reconnect — a future mount is driven by
state/config or a fresh event, not an old imperative request; an asynchronous
completion after the connection changes finds its context inert.
Missing, duplicate, stale, or unsupported targets produce typed evidence;
results that matter to domain state return through configured event intents,
never as host handles; the trace records target, behavior id, op, generation,
and outcome class — never the instance — and **epoch/trace replay records
command data but never re-invokes the host operation**: crossing the
imperative boundary again requires a fresh live command. Imperative handles
(`gridApi.exportDataAsCsv()`, `editor.focus()`, `scroll-into-view`) thereby
become traceable data instead of retained refs and side registries. **The
echo-cancellation doctrine** for value-synced hosts (editors, spreadsheets):
an `:update` receiving config that is the host's own edit echoed back must
diff against the HOST'S CURRENT value, not the old config, and apply minimal
changes — clobbering the widget's cursor/selection with a full reset is the
same failure class the door exists to prevent on native inputs; keeping the
doc host-side and committing on idle/save (the uncontrolled dodge) remains the
taught default for high-rate editors. No behavior catalogue ships with the
substrate: the `:v.scroll/window` scroll-capture behavior is DEFERRED
to the virtual-list pilot, which must demonstrate that the reusable
implementation belongs in the substrate rather than a component library.

The taught split is plain: state/configuration changed → event updates app
state → view supplies new config → `:update` reconciles the host; host emitted
information → configured intent through the ctx dispatch; perform one-shot
host operation now → one data command effect; React owns the protocol → a
wrapper, not a behavior. This registry carries measurement (§4.2's anchored
placement), observers, focus mechanics beyond `:auto-focus`, and non-React
host libraries (charts, maps, editors). A node reference that is itself part of a
foreign React component protocol stays inside the React-bound wrapper alongside
hooks, context, and portals; there is no neutral `v/ref`. Existing read-only
occurrence/evidence projections list active behavior connections and command
traffic (the `:active-owners` pattern extended to host integration), so “leaked
listener” stays an assertable absence—without
exposing private instances or adding application events. The compiled tier
lowers `::v/behavior` as an ordinary attribute into the same registry —
tier-blind by construction. Four behavior laws: behavior CONFIG carries
Clojure values and event INTENTS — never callbacks, nodes, refs, or
preconstructed instances; ONE behavior per node; a
behavior that owns descendants marks its node OPAQUE — common hiccup children
are rejected there, so React and the imperative library never reconcile the
same subtree; and connect/disconnect may be replayed across StrictMode, HMR,
or host recovery — implementations must tolerate a later fresh connection. On
the JVM a behavior is an inert marker with its public config, or renders a
declared fallback when it owns visible content.

**Lifecycle facts go to the tool plane — and are refused to the app.**
Replicant hands lifecycle to the application as dispatched events; this design
refuses exactly that. The causal-ownership doctrine stands: routes/domains own
lifetimes, no dispatch-at-unmount exists, rendering never owns domain
lifecycle. Instead, boundary mount/unmount/reconnect facts — with occurrence
identity — flow to the trace and Xray timeline: lifecycle as observable data
for TOOLS, never as app events. **The Spec 009 binding, stated precisely
(D020):** interpreted and compiled cells emit the SHIPPED `:rf.view/*`
trace ops and mint the S6 committed-instance evidence record (the surface
Xray's Views tab already renders), each carrying ONE additive join field —
`:rf.view/anchor`, the runtime occurrence identity — which supersedes the
instance-token; that one field is the honest new-vocabulary count, and it is
what lets every tool row join the occurrence→controller evidence join to a
control's explicit address (the occurrence is the join key, never the storage
key). Retention rides Spec 009's existing per-frame retained-event ring and
its ONE retention control — no Freehand history store, no root-level retention
knob; mounted occurrence records are live projections, and when any emission
cap is hit the loss is reported in data rather than silently truncated.
Detailed evidence compiles out of production; only deliberately enabled
aggregate metrics and D019's minimal error envelope remain. Dev additionally
emits one op per hot reload (`{:op :rf.view/hmr-reload :rejected-captures n
:orphaned-occurrences […]}`) so a weird-after-reload app has a signal at the
moment it went weird. The app gets registered behaviors (host work); the tools
get lifecycle facts (observability); the domain gets neither, because it never
owned rendering in the first place.

**One read language (D012).** There is no `:reads` descriptor key in
`:re-frame.freehand/v1` — not for applications, not for libraries: an
"optional" declaration would still be a second read language both emitters and
every tool must understand, with a placeholder grammar (`?id` interpolation),
aliasing rules, conditional-read semantics, and a blurred caller-input /
substrate-input line. Inline `v/sub` is the paved path and the whole path: the
census's 231 reads (the 7-read flight-booker `let` is the archetype) read
naturally as calls, and full Clojure means conditional and chained reads a
declaration language would need a DSL to express. The static evidence the
declaration promised arrives where it matters without a second authoring form:
library controls compile at birth, and their compiled manifests ARE the finite
site tables. Reconsideration has concrete triggers — SSR preflight needing
dependency knowledge compiled manifests and root plans cannot provide; a
sizable interpreted library needing static read contracts without compiling;
observed CI unions routinely missing production paths with real faults; pure
catalog invocation materially impaired; the same small template shape
recurring without conditional reads — and the first admissible future
experiment is an ENFORCED empty-read declaration for a props-only boundary
(any `v/sub` rejected), which tests the value of static read contracts before
any placeholder language exists. If declarations are ever admitted they must
be enforced, never silently mixed with undeclared inline reads; an unenforced
annotation is a claim (`:basis :declaration, :complete? false`), not proof,
and never grounds elision or closure.

**The ledger — what is data, what stays function, where data stops paying:**

| Surface | Status | Instrument |
|---|---|---|
| view structure | data (hiccup values) | trees, `=`, tree-seq |
| event intent | data (vectors + options + placeholders + key-maps) | intent equality; traces |
| event mechanics (relatedTarget, geometry, files) | function (`v/event`) — priced escape | per-site stable; opaque |
| subscriptions | function (`sub`) — the one read language in v1 (D012); evidence records carry committed/possible reads | capture (dynamic); compiled manifests (static) |
| host integration | data use site (`::v/behavior`) + registered impl (D013) | behavior registry; assertable connections |
| lifecycle | data facts to tools (Spec 009); never app events | Xray timeline; occurrence identity |
| instance state | data (controller records in app-db — D003/D004, explicit addresses) | time-travel; schemas; semantic causes |
| async policy (debounce, correlation) | data (fx ids, resource identity) | traces; replay |
| presence | keyed retention plan + mounting/unmounting attr overrides | browser host, JVM tree, tests, a11y checks |
| root plan | the versioned Root Descriptor | mount, frame preflight, SSR/hydration, tools |
| view contract | qualified descriptor: id, source, props schema, children policy, profile | host, HMR, compiler, catalogue, context sheet |
| diagnostics | typed findings with recovery DATA (the checker EDN, §3.5) | compiler, runtime, editor, CI, AI |
| theming/parts | data addresses (`data-part`/`data-component` + CSS tokens) + bounded `:parts` spreads (D018); transforms are interpreted/test tooling, never a compiler seam | structural tests |
| controls catalogue | data (registry + schemas → docs/sheet) | §5.4 P14/P15 |
| view bodies | function — full Clojure, the tier-1 dividend | the compiled tier is where structure becomes static, by choice |
| animation/presence intent | data — the keyed `v/presence` plan + `::v/mounting`/`::v/unmounting` overrides (this section) | presence runtime; `flush-presence!`; JVM metadata |
| drag/drop, gesture streams | deliberately NOT data — host-shaped mechanics through `v/event`/behaviors | where data stops paying |

Replicant, judged: it stops short where this design extends (no subscriptions
or compiled read manifests; no controller-state, async-policy, or catalogue
data planes) and goes too far exactly once (app-dispatched lifecycle — the
mistake causal ownership forbids). D012's refusal of a `:reads` declaration is
this rule applied inward: the read data was real, but a second read language
costs more than the compiled manifest that already provides it. The test for every future "make X data"
proposal is the purpose hierarchy: if data buys testability, AI-authorability,
inspectability, or traceability at less cost than the closure it replaces, it
earns a row; if it only buys symmetry, it is a DSL growing. The instance-level
test, stated as one rule: **make a fact data
when more than one consumer benefits from naming, comparing, inspecting,
transforming, recording, or interpreting it independently of the code that
performs it** — pure functions from data to data remain the engine, and turning
ordinary control flow into a map DSL creates no useful data orientation.

**Presence as data — enter/exit retention, designed.** Mounting and unmounting
presentation is data over the keyed `presence` primitive — Replicant's
mounting/unmounting attribute-override organ fused with the compiled tier's
shipped presence runtime (004:678-711, presence_runtime.cljc), which is what
makes it absorption-coherent (worklist row 8 resolves to "presence joins the
interpreted tier"):

```clojure
(v/presence {:timeout-ms 250}
  [:div.toast
   {:key toast-id
    :class ["transition-opacity" "opacity-100"]
    ::v/mounting   {:class ["opacity-0"]}
    ::v/unmounting {:class ["opacity-0"] :inert true :aria-hidden true}}
   message])
```

The bounded contract: keyed children move through `:mounting` → `:present` →
`:unmounting`; mounting attrs apply to the initial committed phase then yield
to base attrs; unmounting attrs apply while the exiting node is retained;
`:timeout-ms` is MANDATORY — the deterministic terminal safety bound; overrides
may change presentation/accessibility attrs but never keys, children,
controlled values, refs, or event ownership; re-entry cancels removal through
the presence state machine; the JVM emits the present/base state with
qualified presence metadata; `flush-presence!` advances the fake clock in
tests; dev warns on retained interactive content without `inert`/AT-hiding.
`v/presence-phase` covers the uncommon child whose STRUCTURE (not attrs)
depends on phase. Presence never dispatches domain mount/unmount events — the
causal-ownership doctrine is unmoved: data makes a mechanical lifecycle
inspectable; it cannot make it the right owner of fetching, seeding, or
cleanup. (Framer Motion remains a legitimate foreign-head alternative for
teams already in its idiom; the native primitive is what library controls
build on.)

### §2.6 The renderer boundary

React-first, concretely: boundaries lower to function components (one
`useSyncExternalStore` + one layout effect + one context read — user code
contains no hooks, so no hook-signature HMR machinery exists); foreign
components are grammar-native with open props; children compose; hydration
renders the same tree client-side over SSR output with handlers attached at
hydration (no handler attributes in HTML, 004:394-397). **No neutral portal
primitive exists** (D015): a portal target is a live host node — not
portable data, with no honest JVM representation — and portals solve stacking
ownership, not dismissal, focus, or teardown; actual React portals live inside
explicit wrappers. A public portal helper is reconsidered only when the
efxb1h graduation triggers fire, cited verbatim (gate fires ONLY on a written
repro; triggers 2 and 3 must recur across 2+ overlay families; aesthetic
preference or React convention never fires it): "1. A consumer must render
into a FOREIGN DOM node re-frame2 does not own (host-page container, embedding
shell) — the one genuinely portal-only capability. 2. A recorded requirement
the top layer cannot express (e.g. overlay must stack BENEATH a designated
sibling; partial-pane containment with backdrop; ::backdrop styling
insufficiency). 3. A documented host/webview in the support matrix lacking
top-layer support where the fixed-position fallback is ALSO defeated (e.g.
ancestor transform) — observed, not hypothetical."

**The outward bridge (D014).** `(v/->react view)` returns a React
component wrapping a declared substrate view — the shipped compiled-tier
contract as the base (004:719: memoised per view, scopes frames, never creates
them), pinned down to one bounded shape. Descriptor only: passing a plain
function or arbitrary Hiccup is a didactic dev-enforced error naming a
declared view or an explicit wrapper as the recovery. The no-option form
performs ONE shallow own-property mapping — every own enumerable property
except the reserved `frame` prop is matched to the declared prop ABI by exact
name, its value uncoerced; no camelisation, no deep conversion, no walk. The
one option is an explicit prop adapter — `(v/->react view {:map-props
cell-props})` — where the adapter receives the raw foreign React props object
(an AG Grid renderer params object, say) and returns the one Freehand props
map: a top-level, testable projection function at the host edge, honest about
being code. An exact own `frame` prop may name or carry an existing live
frame; otherwise the component consumes ambient context; missing, malformed,
or dead targets fail loudly — the bridge never creates a root or picks a
silent default. Caching is keyed by declared descriptor identity plus stable
adapter identity, across body revisions — promotion and HMR never change the
exported component identity, so a foreign library is never remounted because
a body changed (an inline adapter re-mints the component and gets a dev
diagnostic). No protocol smuggling: React children, refs, and host callbacks
are not guessed — protocols requiring them use a wrapper; a use site is
either backed by a truthful SSR adapter or enclosed by `v/client-only` with a
declared fallback; calling the bridge on the JVM raises the common typed
host-operation error. Evidence names the underlying view id and the foreign
call site, so debugging does not stop at an anonymous wrapper. This is how
substrate views reach component-as-prop APIs (a grid's `cellRenderer`, a
drag-overlay's content, any render-prop library) without forfeiting
intent-as-data inside the cell — the inward direction (foreign heads) and the
outward direction (views as components) are both one call.

**The React-bound wrapper, specified.** The construct that hosts hooks —
"visibly React-bound" everywhere this document says it — is an ordinary
UIx/Helix/JS component mounted as a foreign head, with this contract: props in
are data (values + event-prefix intents; `v/event`/`v/handler` per-site
committed slots pass verbatim as foreign props, and `v/raw-fn` where authored
identity is itself the protocol — D008); substrate children pass through as lowered React
children (frame context is guaranteed across it — frame scope rides one React
context that intermediate foreign components transmit by React semantics);
hooks, library contexts, refs, effects, portals, Suspense live inside; under
SSR it supplies truthful server output or declares itself client-only — the
concrete form is `(v/client-only {:fallback [inert-server-view …]} [wrapped …])`
with an explicit declared fallback; a React component does not acquire server
semantics merely because it can create browser DOM. Its public props and
outward intents remain visible in the structural tree while its interior is an
honest opaque leaf. One generic wrapper per hook SURFACE (one sortable-item
shell serves every item type by taking children) is the taught shape —
hook-per-item libraries do not multiply wrapper files. Mounting itself rides
one versioned ROOT DESCRIPTOR (root identity, mounted view, frame preflight,
SSR/hydration facts — a data-plane row) consumed identically by both
frontends; the live DOM container and root handle stay host objects, and
`unmount!` is total teardown that records its result rather than becoming a
data event inside the view tree.

**Top-layer intrinsics** (the overlay answer, §4.2; D015): the common
tree recognises a CLOSED, qualified pair of desired-state properties under an
explicitly web/host namespace — `:rf.web/popover-open?` (legal only on an
element with a valid `:popover` mode) and `:rf.web/modal-open?` (legal only on
`<dialog>`, mapping to `showModal()`/`close()`; an ordinary non-modal dialog
uses the platform's normal `:open` attribute) — deliberately not one generic
open flag, because popover, non-modal dialog, and modal dialog have materially
different browser operations. At selected commit the DOM host diffs each
property and performs the matching idempotent browser call; repeated equal
values are no-ops, and stale generations cannot act on a replacement node.
Native `:on-toggle`/`:on-before-toggle`/`:on-close`/`:on-cancel` remain
ordinary event positions — browser-initiated dismissal never mutates app state
implicitly; the author reconciles it with ordinary Freehand intent, and dev
diagnostics identify a controlled top-layer node with no reconciliation
handler. Invalid calls (opening a disconnected node, `showModal` on an
already-open non-modal dialog) become typed development evidence with a
concise recovery, not swallowed exceptions. JVM/SSR output carries the
semantic element and the qualified desired-state fact without claiming
top-layer promotion; hydration performs the first host operation after commit.
Positioning is separate (CSS anchor positioning or a D013 behavior with an
explicit update contract; no every-frame tracking implied), enter/exit uses
`v/presence` where retention is required, and the intrinsics neither start
timers nor delay removal. These are compiler-recognised COMMON semantics, not
compiled-only forms, and they do not consume the node's one behavior slot —
placement/measurement can coexist. The substrate ships the mechanics FIRST;
the component library's `web/popover` and `web/dialog` are the intrinsics'
first consumers, supplying semantics, ARIA, keyboard policy, placement, and
styling on top — DOM-platform-specific, not React-specific, which is exactly
why the efxb1h ruling beats portal machinery.

The renderer-NEUTRAL core is the data plane: hiccup trees, event vectors,
options maps, placeholders, controller addresses, behavior use sites, the
structural test tree. A snabbdom host would reuse the interpreter, the reactor,
the dispatcher, and the whole event grammar, swapping element creation, the
memo integration, and the door's host mechanics — the coupling surface is the
lowering layer, which is the right place for it. Two React-specifics are named
rather than abstracted: the door's timing contract (React discrete-event
semantics) and portals. The snabbdom test is a design probe, not a deliverable.

**Mounted-test settling:** the mounted host keeps the inherited
alternating-drain idiom (`flush!`'s fixed point, test.cljc:527-592); if React
`act` plus the production post-drain checkpoint cannot settle
deterministically, the maximal concession is exactly one adapter-level
`settle!` shared by tests and tools — never a family of mirrored verbs.

### §2.7 Errors and diagnostics

The mistakes people and AI will actually make, each with its diagnostic — same
rule in both tiers wherever the rule is shared; the legality FLIPS at the seam
are separated below because they, not the tenses, are the teaching burden:

| Mistake | Interpreted tier | Compiled tier |
|---|---|---|
| hook in an ordinary view | didactic mint-time error: hooks live only in visibly React-bound wrappers | same, at compile |
| declared view called as a function / plain `defn` as a vector head | didactic error naming the two legal spellings (`[view props]` to mount; a paren-called helper to inline) | same, at compile |
| opaque event closure at a foreign boundary | mint-time error naming the four explicit forms | compile error (004:408) |
| `v/sub` outside an active declared render | typed error at the call site identifying the query; recovery names `rf/subscribe-once` or a consult-state event | same — one rule, both tiers |
| unregistered event id in a handler vector | render-time dev warning with element coordinates | same, plus manifest row |
| malformed hiccup | typed `:rf.error/ui-tree-malformed` carrying occurrence + path + offending form | mostly impossible by construction |
| thrown render | abandoned capture owns nothing; nearest `[v/error-boundary {:fallback … :on-error […]}]` catches; `:on-error` dispatches ONCE per failure generation after fallback commit, carrying the safe summary (D019) | same component, same envelope |
| two writable owners of one `(kind, address)` | dev ERROR reporting both source locations and occurrences (§2.4) | same — one identity rule in both modes |

Legality flips at the seam (legal interpreted, rejected compiled — each error
ends with "keep this view interpreted"): unkeyed dynamic list (dev warning →
build failure, 004:172); helper returning hiccup in child position (idiom →
`opaque-markup-call` with the four-recovery ladder: make the finite structure
lexically visible, pass computed values into visible structure, extract a
declared child — `v/markup` for inert markup already in hand — or keep the
view interpreted); `sub` in loops (legal
dedup → extract a keyed child); loop-binding-capturing handler vectors (legal →
extract, 004:441-444); computed element heads (legal → close over `case`);
dynamic props maps on controlled inputs (door forfeit with diagnostic → same
forfeit, proven earlier). These flips are the per-shape legality matrix the
generated context sheet must carry (§5.4 P15).

Warning policy is deliberate (D020): a warning is ON by default only
for a detected contract misfire whose symptom would otherwise surface far from
its cause (placeholder misplacement per 004:301-346, unkeyed dynamic lists,
door-classification flapping), emitted once per stable source site and warning
kind, with occurrence coordinates. Quality and predictive lints — nameless
buttons, accessibility sweeps — move to opt-in `v/check` categories rather
than default render-time noise. Hard errors are reserved for semantic
corruption or ambiguity: malformed trees, illegal compiled forms, `sub`
outside its permitted context, invalid event outcomes, and ownership
violations. Categories stay configurable. An AI's feedback loop is honestly
longer in the interpreted tier — run-to-learn instead of compile-to-learn —
partially recovered by the default warnings, by "render every view once in CI"
being a cheap JVM structural test, and by `v/check` being runnable against ANY
view as a lint before promotion.

**Demote-to-debug.** Because demotion deletes one marker (law 2) and changes
no test (law 3), the compiled-view debugging workflow is: delete the marker,
reproduce under full Clojure with REPL redefinition and plain stacks, fix,
re-promote via `v/check` — generated code never stands between a programmer
and a misbehaving view for longer than one line.

**The boundary contract and production reports (D019).**
`v/error-boundary` is a declared core boundary with one child region.
`:fallback` is static hiccup, a declared view (an ordinary fresh mounted view
that may subscribe normally), or a pure `v/render-fn` receiving the safe
failure summary (no-`sub`/no-effect applies to the render-fn). `:reset-key` is
the caller-owned recovery: when it changes by `rf=`, the captured failure
clears and the child remounts/retries — a retry button dispatches a normal
event that changes this value; no boundary ref or imperative `reset!` handle
exists. A failure keeps the prior committed child bundle untouched until the
fallback is selected, then tears the failed subtree down through ordinary
disconnect; a fallback that itself throws propagates to the next outer
boundary. `:on-error`, when present, is one event prefix; Freehand appends the
SAFE SUMMARY and dispatches exactly once per captured failure generation,
after the fallback commit — StrictMode, HMR retries, and repeated parent
renders produce no duplicates. The safe summary is bounded data whose
production policy is explicit: stable diagnostic id and failure fingerprint,
failing view id and boundary view id, phase, source coordinates when retained,
frame public id, the occurrence as a bounded correlation token (the anchor is
a public PROJECTION of occurrence identity, never a replay promise), and the
evidence record in D020's scope/basis/completeness/loss vocabulary. It carries
NO raw props, no app-db, no event payloads, no host objects, no exception
object — and no `:epoch`: production epoch records do not exist, so the field
would be a false promise. Host detail rides the OTHER channel: the
browser/server host promotes at most one record per failure generation onto
the shipped Spec 009 error axis and the frame-owned observability sink — the
safe summary plus the opaque exception (available only during the call) and
the capped host/React component stack; observer/sink code owns redaction,
source maps, transport, and vendor integration, a sink failure is isolated and
never replaces the user's fallback, and no second reporter slot is added to
Root Descriptors. An application that wants a redacted app-db snapshot or
recent event ids MAY obtain them in its own `:on-error` handler or error
observer through an allow-list it owns — capture is opt-in application
policy, never a substrate default, and Freehand promises no replayable
production history.

---

## §3 The compiled tier and the seam

### §3.1 What compilation buys, honestly

Per promoted view: the deleted interpretation walk, deleted handler minting,
hoisted static subtrees (fully-static subtrees are module constants React skips
whole), generated straight-line prop comparators, per-site prebound handlers, a
per-view manifest tools read before anything runs, and — for provably sub-free
views — no cell at all (elision: straight from `React.memo` to the raw body,
zero ViewCell hooks; emit_cljs.cljc:944-947, 004:188-195). What compilation
does NOT buy: the capture/commit apparatus is SHARED — compiled views run
`sub-read` under the same ambient capture and the same 8-step commit as
interpreted ones (reactive.cljc:988-1051, 3577+) — so the steady-state
advantage on sub-heavy, small-template boundaries is modest, and compilation's
strongest ground is node-heavy templates and sub-free leaves. The interpreted
tier's sub-free "analogue" (an empty capture skips sub-graph enrolment)
recovers sub-graph cost only: the boundary still pays cell mint, the hook pair,
capture allocation, trivial commit, and the memo compare — mass sub-free rows
are therefore exactly the elision case that argues for compiling row templates.

### §3.2 The six seam laws

1. **Subset law.** Every legal hot-tier body is a legal interpreted body with
   identical rendered semantics. Checked three ways: tree parity rides the
   shipped shared-analyzer/two-emitter mechanism where it is proven, with the
   interpreter as an ADDITIVE oracle over the common subset (a third witness,
   not the sole soundness mechanism — structural normalization drops `:events`
   and `:key` values, so tree parity alone is blind on the event surface);
   the handler surface is removed from the drift surface BY CONSTRUCTION —
   law 5 taken literally: the hot frontend lowers every handler site to the
   SAME runtime classifier/dispatcher (per-site identity and prebinding are
   optimizations over shared code); the fundamental parity proof is borne by
   the language/emitter conformance corpus, while per-view generative corpora
   ride props schemas WHERE THEY EXIST — schemas are optional in the grammar
   and mandatory by policy for library/catalogue surfaces and for any per-view
   generated-parity claim (D011) — with branch-coverage denominators
   compiled-side-measured.
2. **Call-site invariance.** `[view props]` is the spelling in both tiers, both
   directions; promotion edits one definition site; demotion deletes the marker.
3. **Test invariance.** Promotion changes no test; a test that can tell which
   tier its view is in is a substrate defect.
4. **Evidence continuity.** The counters that justified a promotion keep
   flowing from the compiled cell afterwards — the same table shows the win, or
   shows there wasn't one.
5. **One event/frame/key/identity model.** One dispatcher with firing-time
   materialization; one frame-resolution chain; one `:key` convention; one
   state-identity discipline — single shared implementations, never per-tier
   re-implementations. The identity clause is satisfied by RULE, not
   machinery: writable controller state uses explicit semantic addresses in
   BOTH modes (D004), so no per-tier anchor restriction exists to teach;
   occurrence identity stays in the tool plane on both sides of the seam.
6. **The compiled grammar is closed; the markup crossing is a boundary.**
   `:re-frame.freehand/v1` admits no dynamic-markup valve (D010):
   there is no `v/interp` form, no capture masking, no hidden interpreted walk
   inside a compiled template — a manifest never conceals interpreter work.
   Markup-as-value crosses into compiled context only through a statically
   named descriptor boundary, and the blessed standard recovery for "markup
   already in hand" is the pass-through boundary view `[v/markup {:value m}]`:
   an ordinary declared interpreted child over the D1 C→I emission — the
   compiled parent sees one descriptor; the child owns the interpreted walk
   with its normal ViewCell and evidence. The manifest marks the site
   `:interpreted` like any C→I crossing, and the evidence table prices the
   residency (§3.5). If pilots ever reveal a large recurring class of inert
   markup for which a child boundary is materially worse, an explicit valve is
   the only acceptable later extension — as a unit (explicit spelling, capture
   masking, a loud read error, manifest accounting, measured residency) in a
   LATER grammar version, never an unmeasured or automatic half-version. (The
   shipped runtime already throws on the un-masked mix — an un-sidded read
   reaching an active compiled capture is a designed error,
   reactive.cljc:1006-1014.)

### §3.3 The four deltas

- **D1 — promotion is not transitive.** A compiled view mounts interpreted
  children freely; the hot PATH compiles, cold leaves under it need not.
  Promotion converts one definition site, never a subtree. (The C→I
  boundary emission this requires is the one genuinely new emitter
  capability — worklist row 4, §3.4.)
- **D2 — `[v/markup {:value m}]`.** The blessed pass-through boundary view for
  markup-as-value — an ordinary declared interpreted child riding D1's C→I
  emission; law 6's semantics. Not a valve: the compiled grammar stays closed.
- **D3 — firing-time placeholder materialization by value, uniform.** §2.3; a
  contract change to the shipped literal-only recognition (worklist row 2,
  §3.4).
- **D4 — parity as law 1 states it.** Shipped two-emitter mechanism +
  additive oracle + handler surface shared by construction.

### §3.4 Shape: Absorption (operator ruling)

**The USEFUL code in `re-frame.ui` is folded into Freehand; `re-frame.ui` is
then eventually deleted.** What folds in — the analyzer, both emitters, the
ViewCell reactor (already the inherited spine), the presence runtime, the
manifest/elision machinery, the didactic-diagnostic taxonomy, and the test
machinery — becomes the compiled tier's IMPLEMENTATION; no second compiler is
ever built (the arithmetic that killed building new stands as rationale: a
fresh analyzer front, React emitter, and diagnostic taxonomy — donor: 3,490 +
1,055 lines plus the taxonomy — to save a 492-line JVM emitter, while
adjudicating a second forever-grammar in parallel). What does NOT fold in dies
with the artifact: `local` and its placement machinery, the compiled React
hook tier, the standalone product contract itself. Because no second PRODUCT's
contract survives to be preserved, there is no compatibility ledger to keep —
only the ABSORPTION WORKLIST below, every row resolved in Freehand's
direction rather than negotiated against a fence.

**The staged posture** — direction ruled now, deletion gated, never dated:
(1) `re-frame.ui` enters DONOR MODE immediately: no new standalone surface;
its machinery evolves only as Freehand's tier-2 implementation. (2)
Transitional coexistence while Freehand is built over the absorbed machinery
(the shipped alpha-train surface keeps working; spec 004 migrates by rename
into Freehand's tier-2 grammar spec). (3) DELETION when the conformance
contract (§3.6) is green and the component/library pilots pass — a gate, not
a date.

**The absorption worklist** — the surfaces where the donor's shipped contract
and Freehand's laws diverge, each with its ruled disposition:

1. **`local`** — DELETED, not fenced: the pin refused it, the generation-fenced
   buffered controller (§4.1) replaced its one serious use case, and no
   unprofiled variant survives to house it.
2. **Placeholder provenance** — the materialize-at-the-adapter law with the
   permanent `::v/value`/`::v/checked`/`::v/key` spellings (§2.3, D006)
   becomes the one contract; donor `:rf.ui/*` spellings are rewritten
   mechanically at migration, never aliased; the donor's literal-only
   recognition is simply superseded — nothing remains for it to be
   compatible with.
3. **Instance state** — explicit semantic addresses in both modes (D004):
   writable controller state is keyed by `(kind, :control address)`
   everywhere, occurrence identity stays in the tool plane, and the donor's
   render-position machinery contributes nothing to application-state
   identity — no compiled-anchor restriction survives to be taught.
4. **Ordinary children (C→I emission)** — the one genuinely NEW emitter
   capability: without it promotion is not local (D1). Built once, in the
   absorbed emitter; `v/markup` (D2) rides it.
5. **Controlled scheduling** — one node-class law, one scheduling
   implementation, proven against the real-browser caret/IME matrix (the
   conformance contract's row); the emitter carries statically proved door
   classification through dynamic vector/options sites (D009's promotion
   parity).
6. **Host forms** — `ui/ref`/`ui/effect`'s jobs move to the behavior registry
   and the wrapper (§2.5, §2.6); the compiled React hook tier is replaced by
   the wrapper outright. The forwarding and parameterized-content forms —
   `spread-safe`/`spread`, `render-fn`/`slot` — fold in as COMMON grammar:
   spread-safe is door-preserving in both tiers (§2.3), and slots are ordinary
   pure functions interpreted with lexically visible bodies compiled — which
   is what makes a compiled table with a caller row-slot possible.
7. **The grammar delta** — the key-condition map's tier-2 row (§3.6), added to
   the absorbed analyzer.
8. **Presence** — already resolved into the design: `v/presence` (§2.5) IS the
   absorbed presence runtime with the interpreted tier joining it — one keyed
   retention/override/timeout/a11y/test contract; remaining work is
   conformance.
9. **The JVM callable-view switch (D002)** — the donor JVM emitter's callable
   view value becomes the shared descriptor form, which cannot be successfully
   called; preserving the old callable output would reintroduce the cross-host
   call mismatch the sharp declaration boundary closes.

Under absorption every row is internal work, not cross-product negotiation —
the coordination tax the two-frontends framing carried (coupling a hot tier to
a separately-owned artifact's evolution) evaporates with the second product.
One internal honesty note survives: the observation layer remains two
implementation variants (query-keyed interpreted cells; sid-keyed compiled
cells) behind the handle-cardinality-is-internal law (§2.2) — a private fact
of one artifact, no longer a seam between two.

### §3.5 Placement: where each tier applies

**The library exception.** Promotion economics are retrospective —
`v/hot-views` needs a running app under load — which is correct for app code
and structurally impossible for a component-library author: consumer pages do
not exist before ship, so the library's evidence can never arrive, while every
consumer pays whatever tax the library chose (N instances × wrapper + walk +
generic memo per page). Library promotion cost is ~zero (law 2; §4's
transcripts) and sub-free leaves are elision's home surface. Therefore:
**library leaf controls and row templates ship compiled by default; app pages
and composition ship interpreted; the anti-folklore rule governs app
promotions.** Stateful library controls carry their explicit `:control`
addresses regardless of tier (D004), so compiling them never touches state
identity.

**Cold-start defaults** (day-one teams have no evidence table — taught
defaults, evidence overrides):

| Surface class | Default tier | Why |
|---|---|---|
| App pages, routes, composition, shells | interpret | the dividend lives here; promotion evidence can arrive later |
| Library leaf controls | compile at birth | the library exception; elision; explicit `:control` addresses by rule |
| Keyed row templates at scale | compile when in-subset; window regardless | wide-parent churn and mount storms are the mechanical hot classes (§7.3); windowing bounds N first |
| Flap-prone controlled input sites | compile to pin the door | the cheapest predictability purchase |
| Everything else | interpret until evidence says otherwise | anti-folklore |

**The evidence instrument** (dev-only counters on work the interpreter already
does):

```clojure
(v/hot-views frame)
;; ⇒ [{:view app/person-list :renders 214 :self-ms-p95 6.2 :nodes 12040
;;     :rows-max 1200 :top-causes [[[:crud/filtered-people] 180]
;;                                 [[:rf.view/parent] 30] …]   ; props-churn rows
;;     :stable-renders 0.31 :interp-slots 0}]  ; :interp-slots = derived count of
;;                                             ;   v/markup mounts under this view
```

Promote on `self-ms × renders` and `nodes` — that product is the interpretation
compilation deletes. Do NOT promote on a high `:stable-renders` fraction —
equal output means the fix is narrower subs, which helps both tiers; the table
distinguishes expensive work from unnecessary work. `:top-causes` includes
`[:rf.view/parent n]` rows for renders with no moved sub (a parent passing
non-`rf=` props — a rebuilt inline map, a per-render lambda), so "narrow the
sub" vs "stabilize the props" is decidable from the table. `(v/check view)` is
the dry-run, returning stable EDN — the finding IS the work-order:

```clojure
{:view-id  :app.people/people-list
 :source   {:file "src/app/people.cljc" :line 42 :column 1}
 :profile  :re-frame.freehand/v1
 :eligible? false
 :findings [{:id       :re-frame.freehand.compile/opaque-markup-call
             :source   {:line 47 :column 5}
             :form     '(render-person person)
             :reason   :markup-hidden-from-analyzer
             :recovery [:make-template-visible :pass-computed-value
                        :extract-declared-child :keep-interpreted]}]}
```

The checker never edits code and never recommends compilation from a
percentage; changing the declaration is the final step, not the discovery
mechanism. An AI's loop is read table → check → apply named edits → promote →
law 4 shows the delta. A release build accepts `:interpret-all?` (treat every
promotion marker as absent) so an app team can audit law 3 locally in one run.

**The boundary inspector** — the first move in almost every debugging session
is "show me what this boundary actually depends on," so the runtime exposes
its cell as data:

```clojure
(v/inspect-boundary occurrence)
;; ⇒ {:occurrence …  :view app/todo-row
;;    :committed [{:query [:todo/by-id 7] :value … :owned? true} …]
;;    :last-render {:epoch 812 :cause [:todo/by-id 7]}
;;    :props {:current … :rf=-prev? true}
;;    :door-sites [{:attr :on-input :door? true :reason :literal-controlled}]
;;    :controller {:kind :fh/buffered-field :address [:invoice 42 :amount]}}
```

Stale view → `inspect-boundary` → committed queries vs the query being mutated
(mismatch is the bug), or jump to the sub side's equality-gate evidence. The
same read answers "which sites are doored and why" (the mint cache already
holds `door?` per entry) and "where is this instance's state" — the
`:controller` entry is the occurrence→controller evidence join (D004), never
the storage key. Companion dev reads, all table lookups over state the runtime
holds: `(v/orphans frame {:epochs n})` — controller records with no mounted
occurrence join for n epochs, surfaced as badges in the Xray controller
browser; `(v/behaviors frame)` — active behavior connections per occurrence
(§2.5). **Every evidence projection is one record shape (D012/D020):
`{:scope … :basis … :complete? … :loss …}`** — scope names possible-sites, a
committed generation, or a named corpus; basis is static-proof, observation,
declaration (future, enforced-only), or opaque; completeness is claimed only
relative to the stated scope; any cap records explicit loss. "Proven /
declared / observed" are human-facing RENDERINGS of that grid, never separate
taxonomies. So: a compiled manifest is `{:scope :possible-sites, :basis
:static-proof, :complete? true}`; the committed reads of one render are
complete for that generation; the CI corpus artifact (renders every view
across a generative corpus on the JVM, publishing per-view unions of realized
captures and intents) is observation over a named corpus, never a program
proof; an intentionally opaque wrapper says so. `view-manifest`,
`mounted-views`, `view-dependencies`, `view-event-sites`, and `explain-render`
share this vocabulary; tools never invent mode-specific meanings.
Compilation alone still buys static proof of possible sites and whole-app
closure.

**Measurement obligations.** The split's arithmetic must not ship on estimates:
the B1-B5 benchmark matrix (Appendix B.5) is a day-one obligation — the walk
constant, the mount-storm decomposition, the 10k-row memo comparison, the door
under input + background storm, and the bundle deltas. Until measured, every
constant is labelled estimate. Release policy is two-lane (D021):
deterministic properties gate; wall-clock and bytes are published evidence,
never thresholds (§7.4).

### §3.6 The hot grammar, its teaching, and state identity across tiers

The restrictions compilation genuinely requires, and how they teach (every
rejection ends with "keep this view interpreted"): markup must be lexically
visible; sites must be finite (`sub` in loops → extract a keyed child);
handler vectors capturing a loop binding extract to a keyed child (per-row
committed slots need per-row instances, 004:441-444); expression macros are the
closed audited set (an unaudited macro could hide a `sub` and falsify the
manifest — 004:182-195, transferred whole); dynamic heads close over `case` or
stay interpreted with promoted leaves.

**The tier-2 handler roster.** Forms 1/2/3 and `v/event`/`v/handler` are the
shipped compiled table verbatim; the materializer contract is worklist row 2. The
key-condition map is the ONE added form: literal string keys compile to a
static per-key classification table; each value classifies by the same rules as
a top-level handler (one level of recursion); non-literal keys or a computed
map reject with "extract to `v/event` or stay interpreted."

**The conformance contract** — the per-surface parity the two-tier claim
requires (and, under the absorption ruling, the DELETION GATE for the donor
artifact); the §5.2 R-T rows score it, and release acceptance is this table
going green, not aspirational:

| Surface | Required parity |
|---|---|
| calls | `[view props]` both directions; promotion edits one definition site; no call-site or test changes |
| identity/HMR | qualified view id + `(parent, key-or-position)` occurrence; generations internal; one reload-epoch fence |
| children | statically named ordinary/compiled children cross through descriptors (the C→I emission); no hidden walker |
| props | one map; reserved trailing `:children`; stripped `:key`; shared `rf=` and conversion tables |
| events | ONE normalizer for literal/forwarded values + the one materializer (D006); per-site ownership; atomic selected bundle |
| controlled input | one node predicate, one scheduling implementation, one real-browser matrix |
| frame | ordinary shells always observe context; retarget beats memo; compiled elision only under manifest proof |
| subscriptions | value, resolution, invalidation, and commit safety identical; handle cardinality internal |
| instance state | one controller-record discipline (D003/D004): explicit semantic addresses, identical in both modes; occurrence→controller joins in evidence |
| presence | one keyed retention/override/timeout/a11y/fake-clock contract (§2.5) |
| behavior | one id/config protocol; commit-only connection; replay-tolerant; command targets semantic-only; JVM marker/fallback |
| roots/SSR | one Root Descriptor, preflight, hydration, and teardown contract |
| structure | one versioned semantic tree + conversion table; occurrence identity on boundary nodes |
| diagnostics | stable ids, source coordinates, recovery data (the checker EDN); one trace vocabulary with the anchor tag |

**State identity across tiers** (D004): there is no compiled-tense
anchor rule, because there is nothing for it to fence. Writable controller
state is keyed by `(kind, :control address)` in both modes; the address is an
ordinary prop value the compiled emitter passes like any other, so stateful
controls promote with their state intact by construction — no keyed-or-id'd
restriction, no `v/self` resolution question, no per-tier identity teaching.
Occurrence identity remains a runtime fact on both sides of the seam
(compiled cells mint their occurrences without a walk) and stays in the tool
plane. The 004:172 unkeyed-list build failure survives untouched as a
RECONCILIATION law — keys select siblings; they were never state addresses.

**Conversion deltas, honestly priced.** CRUD screen: 4 edited lines
(`{:compiled true}` on the declaration, `map`→keyed `for`, the helper declared
`defn`→`v/defview` with `(helper)`→`[helper {}]`), zero call sites — the
checker names every edit before it is made:

```
$ v check crud-screen
:re-frame.freehand.compile/markup-returning-map   crud_screen.cljc:17
  (map (fn [p] [person-option …]) people) … use (for [p people] [person-option {:key (:id p) :person p}])
:re-frame.freehand.compile/opaque-markup-call     crud_screen.cljc:18
  (draft-fields) … declare it and mount it: v/defview + [draft-fields {}] — draft-fields may stay interpreted …
2 rejections. person-option: compiles clean (sub-free — would elide its cell).
```

Editor-form slice: 0 lines (single literal vectors, literal props maps). The
helper-heavy editor PAGE: not a clean promotion — its `cond->`-built props maps
on controlled inputs forfeit the synchronous door on promotion (dynamic props
maps fall back to batching, 004:463-466), so the honest choices are unrolling
to per-branch literal props maps (exactly the shipped compiled rendition's four
hand-unrolled fieldsets, ui_editor.cljc:89-120) or staying interpreted — which
the cold-start table already says. The dividend and the door are the same trade
seen from two sides. Library controls: §4's transcripts (one keyed-child
extraction each for the row cases; zero edits for the field family). General
honesty: deltas are small because views were written data-first;
HOF-over-markup converts by extraction, not by edits.

---

## §4 The gallery — three hard cases

Format per case: today's verified baseline, the substrate's spelling, the
promotion transcript, the judgment (clearly nicer / merely different / worse)
held against both the Reagent corpus baseline and the UIx floor.

Two library-tier conventions used throughout (D017): the control
families are FIRST-PARTY LIBRARY vocabulary, never framework grammar — the
`:rf.*` reserved root makes no field, dropdown, typeahead, or debounce
promise, because a reserved keyword is a compatibility promise the substrate
should not spend before pilots prove commonality. The gallery spells the
first-party control library's root as `:fh.field/*`, `:fh.dropdown/*`,
`:fh.typeahead/*` — PROVISIONAL pending the library's own naming decision;
a third-party library mints its own root with the same shapes, and Freehand
owns only the laws underneath (one-event results, projections, controlled
scheduling, committed-state decisions, address and generation infrastructure,
evidence). Promotion of any control family or policy into a reserved framework
namespace happens only through the explicit promotion test: two or more
independent consumers needing identical semantics, behavior specifiable
without one widget's accessibility/composition policy, cross-mode parity
demonstrated, material tool leverage from standardized identity, and the
compatibility cost accepted explicitly. Tools consume optional controller-kind
metadata (schema, evidence label, ownership mode), not keyword folklore — and
Freehand ships no `register-kind!`, no `def-control-event`, and no reducer
language in v1: each family hand-writes the same consult-state skeleton (read
the committed record at the address → liveness guard → emit `(conj on-commit …)`
→ clear/close), and if the pilots prove the repetition, the LIBRARY may grow
its own macro — the substrate does not.

### §4.1 Controlled input with validation — and the buffered field

**Baselines.** App-level: flight_booker's deref pyramid + closures + coercion in
the view (core.cljs:197-230). Library-level: re-com `input_text.cljs:78-113` —
form-2 closure over twin ratoms, `showing?` re-minted every render (the
library's own authors misplacing ephemeral state), documented same-value
blindness, commit-forces-reset flicker, the arity-sniffed done-fn, render-phase
double `reset!`.

**The app form:**

```clojure
(v/defview flight-booker [_]
  [:div.flight-booker
   [:select {:value     (name (sub [:flight/trip-type]))
             :on-change [:flight/set-trip-type-str ::v/value]}  ; handler coerces
    [:option {:value "one-way"} "one-way flight"]
    [:option {:value "return"}  "return flight"]]
   [:input {:value    (sub [:flight/start-text])
            :class    (when-not (sub [:flight/start-valid?]) "invalid")
            :on-input [:flight/set-start ::v/value]}]
   [:input {:value    (sub [:flight/return-text])
            :disabled (not (sub [:flight/return-enabled?]))
            :on-input [:flight/set-return ::v/value]}]
   [:button {:disabled (not (sub [:flight/book-enabled?]))
             :on-click [:flight/book]}
    "Book"]])
```

The sub chain is unchanged — the dataflow tier was already right. What moved:
keyword coercion left the view for the event handler (testable), every closure
became a vector, and the form's whole intent is one equality assertion away.
Per keystroke: one synchronous drain (the door), one epoch, sub-graph
propagation, re-render of exactly the boundaries reading the moved subs —
O(this form), never O(page).

**The reusable buffered control** (D016: ONE generation-fenced
controller; there is no separate simple-buffered/revision split, because two
subtly different state machines would drift and the simpler one would fail
exactly at same-value rejection). `:reset-key` is required — a caller REVISION,
never the model value (rf2-nzst23's NON-NEGOTIABLE: a parent reasserting the
SAME value to reject an edit is invisible to value equality); a caller that
never rejects passes a stable literal, which deliberately means "do not
externally reset an active edit":

```clojure
(v/defview buffered-field
  "Commit-on-blur/Enter with caller-rejectable commits. The draft lives in a
   generation-fenced controller record at `control`; bump `reset-key` (a caller
   revision) and the display snaps to `value` — even when that baseline
   rf=-equals the previously accepted draft."
  [{:keys [control value reset-key on-commit validate placeholder]}]
  (let [{stored-rk :reset-key draft :draft} (sub [:fh.field/record control])
        editing? (and (some? draft) (= stored-rk reset-key))
        text     (if editing? draft (or value ""))
        problem  (when (and editing? validate) (validate draft))]
    [:span.buffered-field
     [:input
      {:value        text
       :placeholder  placeholder
       :aria-invalid (boolean problem)
       :on-input     [:fh.field/edited control reset-key ::v/value]
       :on-key-down  {"Enter"  [:fh.field/commit control on-commit reset-key]
                      "Escape" [:fh.field/cancelled control]}
       :on-blur      [:fh.field/commit control on-commit reset-key]}]
     (when problem [:span.problem problem])]))
```

The controller record is minimal — `{:reset-key rk :draft s}` — and **draft
presence under a matching reset key IS the edit session**: no `:phase` field
exists, a matching record renders `:draft`, absence or a reset-key mismatch
renders `:value`. The transitions: **begin = the first `edited`, not focus** —
focus alone creates no controller state (there is no `began` event); `edited`
atomically creates or replaces `{reset-key draft}` from the live input value.
**Cancel is a semantic clear, no tombstone**: Escape removes the record; a
racing native blur's commit consults committed state, finds no live record,
and no-ops — Escape-then-blur cannot resurrect a cancelled draft. **Enter and
blur are aliases for one commit**: `:fh.field/commit` reads the committed
record at fire time, and only a record whose stored `:reset-key` matches the
carried one dispatches `(conj on-commit draft)` and clears — a stale
generation (the caller rejected and bumped while a blur sat queued) is an
idempotent no-op. The same unification closes the Enter-commits-then-keystrokes
hole: commit clears the record, and the next keystroke's `edited` simply
begins a fresh session from the live input value — no focus ceremony, no dead
zone. An external `:value` change under an unchanged reset key leaves the
current draft editing; replacing/rejecting the edit is exactly what a
`:reset-key` bump says — the distinction is explicit, never guessed. Two
scheduling clauses are pinned: `edited` synchronously commits the frame for
controlled echo (a single literal vector — door-eligible — so keystrokes hold
caret, selection, and IME), and `commit` produces the caller dispatch plus the
record clear as effects of ONE library event — one epoch, one trace cause.
Browser contract riding the pilot: `compositionstart`/`compositionend` keep
Enter from committing a partial IME composition (the key-condition map's
isComposing clause, §2.3); controlled echo never replaces the node or resets
selection; a transformed accepted value may move the caret only when the
caller's new reset key establishes a new baseline; repeated commit, cancel, or
stale-generation events are idempotent no-ops; disconnect removes host
listeners/joins only — never a semantic transition. Doctrine: in-control
`validate` is ADVISORY DISPLAY (`aria-invalid` + the problem span); commit
gating belongs to the CALLER, who filters the commit or rejects via the
revision bump; `:on-cancel` is optional for domain-significant cancellation.

What dissolved against re-com: the twin atoms (the draft is controller state
in app-db); same-value-rejection blindness (display switches on the
generation-fenced record, never on value diffing); the flicker/done-fn stack
(there is no forced reset — a new reset key exposes the external baseline
immediately); render-phase `reset!` (nothing imperative exists to reset). The
guard lives once in the library's sub/event pair, inside epochs — the pin that
deleted `local` is what makes the explicit-revision guarantee an ordinary
event protocol instead of a compiler feature. Conformance condition carried
honestly: this row goes green only when it passes the full nzst23 acceptance
suite (same-value rejection, zero stale paint, caret/selection/IME,
independent slots, retries, HMR, JVM, measured per-keystroke cost); failure
re-examines the one-state premise, not the paperwork.

**Promotion transcript.** The field ships compiled as a library leaf: every
handler is a single literal vector (door-eligible where controlled), props
maps literal (door proof holds), key-condition maps compile under the roster
row, and controller state rides the explicit `:control` address — identical in
both modes (D004), so promotion touches nothing about state. Delta: declare
the field with `{:compiled true}`, zero body edits, zero call-site changes.

**Judgment.** App form: clearly nicer than the Reagent baseline and the UIx
floor (closures + hook ceremony for nothing). The buffered control: clearly
nicer than re-com's stack by construction — three workaround layers deleted,
state visible in app-db, time-travels, JVM-testable. Honest "merely
different": per-keystroke app-db writes are a real cost priced in §7.3; the
one required `:reset-key` prop is a real caller obligation needing excellent
naming and examples; the uncontrolled dodge (`:default-value` +
commit-on-blur) remains legal and is the taught grid default.

### §4.2 Dropdown / popover — focus, dismissal, top layer

**Baseline** (the corpus has zero floating overlays in 85 files; the real
baseline is re-com, verified): dropdown keeps open/focus/position in 5+
instance ratoms, installs a `js/document` click listener on open and removes it
only in close — unmount-while-open leaks it permanently — and runs a
requestAnimationFrame loop EVERY FRAME while open to track the anchor,
rendering position:fixed at measured coordinates on a z-index ladder
(4/20/30/1020 across the families). popover parks at -10000px until measured
with a -2000px margin hack and a `.parentNode`×3 anchor walk. modal has no
focus trap, no inert, no Esc, no focus return (rf2-efxb1h's verified parity
bar).

**The spelling** — in-flow structure + the native top layer (the efxb1h ruling
made substrate vocabulary, §2.6):

```clojure
(v/defview select-dropdown
  "Anchored single-select. Open state in a controller record at `control`; body
   on the NATIVE top layer — no portal, no z-index, no document listener
   anywhere."
  [{:keys [control items value on-commit placeholder]}]
  (let [open?  (boolean (sub [:fh.dropdown/open? control]))
        active (sub [:fh.dropdown/active control])
        label  (or (some #(when (= value (:value %)) (:label %)) items) placeholder)]
    [:div.dropdown
     [:button.trigger
      {:aria-haspopup "listbox" :aria-expanded open?
       :on-click    [:fh.dropdown/toggled control]
       :on-key-down {"ArrowDown" {:event [:fh.dropdown/move control 1 (mapv :value items)]
                                  :prevent-default true}
                     "ArrowUp"   {:event [:fh.dropdown/move control -1 (mapv :value items)]
                                  :prevent-default true}
                     "Enter"     [:fh.dropdown/commit control on-commit]}}
      label]
     (when open?
       [:ul.menu
        {:role "listbox" :popover "auto" :rf.web/popover-open? open?
         :on-toggle (v/event [e]
                      [:fh.dropdown/reconciled control (= "open" (.-newState e))])}
        ;; native light-dismiss (outside click, Esc) reconciles back into state —
        ;; UNCONDITIONALLY: the write is idempotent on same-value writes, and any
        ;; guard comparing against a render-captured open? would be the
        ;; render-minted staleness class the consult-state law forbids. Mounted
        ;; only while open (R-B10: closed overlay = zero DOM); the intrinsic's
        ;; commit-time diff shows the popover on mount-with-open-true.
        (for [{:keys [value label]} items]
          [:li {:key value :role "option" :aria-selected (= value active)
                :on-click [:fh.dropdown/select control on-commit value]}
           label])])]))
;; :fh.dropdown/move, :fh.dropdown/commit, :fh.dropdown/select — three
;; library-registered consult-state events: move steps :active through the
;; carried value list against COMMITTED state; commit reads the committed
;; :active at fire time and — only when open with an active option — emits
;; (conj on-commit active) + closes; select emits (conj on-commit value) +
;; closes. One epoch, one trace cause, per user action. No per-instance
;; registration.
```

Requirement by requirement (harness R-B rows): clipping/stacking — the browser
hoists the top layer above every stacking context, immune to the ancestor
transforms that defeat position:fixed; the z-ladder has nothing left to order.
Outside-dismiss with bounded listener lifetime — light-dismiss is the
platform's; NO listener exists, by construction. Focus contract —
`<dialog>.showModal()` brings backdrop, inert background, Esc, initial focus,
and focus return natively; the modal exceeds re-com's zero-a11y bar for free.
LIFO nesting — native top-layer stack. One owner per instance — the
controller record at `control` + `:on-toggle` reconciliation, so state and
platform cannot silently diverge. Frame continuity — the element never leaves
its structural parent: SSR emits it closed, hydration relocates nothing.
Headless split — open/close/dismissal policy is events + subs, provable on the
JVM; geometry stays a mounted proof.

**Placement honesty.** The top layer solves stacking, not geometry. Where CSS
anchor positioning is available it is pure CSS and the substrate emits
attributes [support status: knowledge, labelled]. Until then, anchored
placement is measured geometry spelled as a registered behavior:
`{::v/behavior [measure/anchor {:anchor ::trigger
                                :into [:fh.dropdown/measured control]}]}`
— measure before paint, write coalesced and `rf=`-gated through the library's
semantic event into the controller record, converge in one extra local
re-render. Declared contract: positions on open and
on resize via ResizeObserver; no every-frame tracking loop — where re-com's rAF
loop was silent. Natives delete much, not all: per-widget focus semantics,
keyboard navigation, and placement remain component work (exactly the work the
listbox spells as data), and `<dialog>` is the wrong element for listboxes,
menus, tooltips — the popover attribute family carries those.

**Promotion transcript.** The trigger and menu shell promote clean. The option
row does NOT promote in place — `[:fh.dropdown/select control on-commit value]`
captures the loop binding, a compile error under the loop law (004:441-444).
The extraction the checker names:

```clojure
(v/defview option-row {:compiled true}
  [{:keys [value label active? control on-commit]}]
  [:li {:role "option" :aria-selected active?
        :on-click [:fh.dropdown/select control on-commit value]} ; prop now, not loop binding
   label])
;; in the menu:
(for [{:keys [value label]} items]
  [option-row {:key value :value value :label label
               :active? (= value active) :control control :on-commit on-commit}])
```

One extraction + prop threading — a real source change, honestly priced. And
`option-row` is sub-free: its compiled form elides its cell entirely — the
library exception's showcase.

**Judgment.** Against re-com: clearly nicer — an entire failure class (listener
leaks, rAF loops, z-ladders, parked measurement, a11y absence) deleted by
platform adoption plus data-owned state. Against the UIx floor (portal +
floating-ui + hand-rolled focus management): clearly nicer on
dismissal/focus/leak-freedom and state inspectability; merely different on
placement math.

### §4.3 Composed async control — the typeahead

**Baseline** (re-com, verified): `single_dropdown` coordinates eleven+ instance
ratoms and takes `:choices` as an `(opts, done, fail)` callback with loading
state in a local ratom; `typeahead` hand-rolls a core.async channel + debounce
over a suggestions state machine — async machinery buried in component
closures, invisible to traces, untestable headlessly.

**The spelling** — rebuilt on the substrate's own async contract:

```clojure
(v/defview user-typeahead
  "Debounced remote search. The query is a per-instance draft; the results are a
   RESOURCE keyed by the query; selection commits via the caller's event prefix.
   No core.async, no callbacks, no local state machine."
  [{:keys [control on-commit placeholder]}]
  (let [{:keys [q sq open?]} (sub [:fh.typeahead/record control]) ; sq = settled/debounced query
        search (when (seq sq) (sub [:rf/resource {:id [:users/search sq]}]))
        rows   (:value search)]
    [:div.typeahead
     [:input
      {:value (or q "") :placeholder placeholder :aria-expanded (boolean open?)
       :on-input [:fh.typeahead/typed control ::v/value]
       :on-key-down {"Escape" [:fh.typeahead/closed control]}
       :on-blur (v/event [e]
                  (when-not (.contains (.. e -currentTarget -parentNode)
                                       (.-relatedTarget e))
                    [:fh.typeahead/closed control]))}]
     (when open?
       [:ul.suggestions {:role "listbox" :popover "auto"
                         :rf.web/popover-open? open?}
        (cond
          (:pending? search) [:li.hint "Searching…"]
          (:error? search)   [:li.hint.error "Search failed — keep typing to retry"]
          (empty? rows)      (when (seq sq) [:li.hint "No matches"])
          :else
          (for [{:keys [id name]} rows]
            [:li {:key id :role "option"
                  :on-click [:fh.typeahead/pick control on-commit id]}
             name]))])]))

;; The library registers TWO events, once:
;;   :fh.typeahead/typed → writes {:q v :open? true} at the address and emits
;;     {:fx [[:fh.fx/debounce {:id [::search control] :ms 250
;;            :dispatch [:fh.typeahead/settled control v]}]]}
;;     — one event per keystroke (door-eligible literal vector); debounce is an
;;       fx POLICY (cancel-and-replace by id), visible in traces, not a channel
;;       in a closure. [:fh.fx/debounce is a LIBRARY fx (D017); it graduates to
;;       re-frame only through the promotion test — two independent non-widget
;;       consumers needing identical id/cancellation/frame/SSR/trace semantics.]
;;   :fh.typeahead/pick → consult-state: emits (conj on-commit id) + clears.
;;   the resource [:users/search sq] — declarative identity, cache, staleness,
;;     supersession by correlation: a late reply for an old sq lands in the OLD
;;     cache entry; the view reads only the current one.
```

Requirement accounting (harness R-C rows): per-instance status collision-free
at N instances — the resource is keyed by query and the draft by its explicit
address; fifty
typeaheads share nothing but the cache they want to share. Debounce has a
visible home — an fx with an id, in the trace, replayable. Composed identity —
one `:control` address spans input + list + status; re-com's eleven ratoms
become three
keys in one app-db map. Supersession rides the resource contract the corpus
already proves (slug-correlated replies). The whole loop is headlessly
provable: dispatch the input's own rendered intent, flush, assert the
suggestion list — no mock, no simulated click, no browser. The editor loop's
seed discipline is the blessed form-slice shape (§5.4 P3): seed untouched
fields from the baseline, preserve touched ones — the one obvious spelling,
shown once:

```clojure
(rf/reg-event :editor/article-loaded
  (fn [{:keys [db]} [_ slug article]]
    (let [{:keys [draft touched]} (get db :editor)
          seeded (reduce-kv (fn [d k v] (if (contains? touched k) d (assoc d k v)))
                            draft article)]
      {:db (assoc db :editor {:slug slug :draft seeded :baseline article
                              :touched touched})})))
;; touched fields keep the user's typing; untouched fields seed from the reply —
;; a late same-slug settle can no longer clobber keystrokes (R-C1).
```

— the paved answer to the live
same-slug clobber (article_editor.cljs:304-314, still present; bd rf2-y4mgw).

**Promotion transcript.** The input + status shell promotes clean. The
suggestion row extracts exactly as §4.2's option-row (loop law; sub-free row →
elided cell).

**Judgment.** Against re-com: clearly nicer — the state machine became three
data keys, the channel became a policy fx, the callback protocol became a
resource, every step assertable. Against the UIx floor (Suspense/`use` +
`use-optimistic`: powerful, host-locked, state invisible to tools, untestable
without a mount): clearly nicer under this project's goals. The two spots the
spelling drops below pure data — the `:fh.fx/debounce` fx id and the `:on-blur`
containment `v/event` — are visible and priced.

---

## §5 Fitness

### §5.1 The census, mapped to primitives

The independent harness's census over `examples/{core,capabilities,patterns,
real-apps}` (verified counts: 231 `@(subscribe …)` reads, 147 `#(dispatch …)`
closures, 58 `(.. % -target -value)` extractions, 77 controlled controls, 364
`data-testid`s, 106 route links, zero view-local ratoms, zero portals, one ref,
one lifecycle reach) arbitrates "frequent shapes justify syntax":

| Must-be-beautiful shape (census weight) | Primitive | Where |
|---|---|---|
| sub-read view body (231 reads; 27% framework subs; 23% parameterised) | plain-value `sub` throughout a declared render, conditional reads free | §2.2 |
| intent dispatch (93 pure + 58 value-carrying + 36 preventDefault + 3 key sites) | vectors + placeholder trio + options map + key-condition maps → ~97% of the 183 handler sites become pure data | §2.3 |
| controlled draft input (77 controls, 100 `:disabled`) | the door + draft/canonical split + the field family | §4.1 |
| keyed row with per-row identity (48 `for` / 35 keys) | `:key` → host slot + occurrence; rows self-subscribe | §2.1; scale: §3.5 |
| status-driven attribute + route link (106 links) | attrs off async-state maps; donor view renamed `v/route-link`, with its href/click law still owned by Spec 012 — first-page shared vocabulary in both tiers | inherited contract, renamed surface |
| rare by census (1 ref, 1 autofocus, 0 portals, 0 observers, 0 foreign components) | escape hatches only: behavior registry, explicit React wrapper, top-layer intrinsics | correctly starved of syntax |

### §5.2 The requirements matrix

Scoring: ✔ meets by construction · ✔ˢ meets with a stated spelling · ◐
partial/priced. Every ✔ is a design answer at the harness's level, not a
validated capability — rows touching real-browser input/IME, focus, hydration,
and alternate hosts remain mounted-proof obligations for any implementation.

| Req | Substance (compressed) | Score | Where |
|---|---|---|---|
| R-A1 | same-tick echo; input-path exception stated | ✔ the synchronous door | §2.3 |
| R-A2 | caret under reject/transform; no remount-reset | ✔ door + generation-fenced display switch; reject/transform via the `:reset-key` bump (nzst23 suite ahead) | §4.1 |
| R-A3 | same-value rejection visible | ✔ display source is the fenced record, never value diffing | §4.1 |
| R-A4 | draft vs canonical without reformat-jitter | ✔ corpus discipline packaged per instance | §4.1 |
| R-A5 | touched/attempt-gated errors | ✔ˢ the blessed form-slice shape | §5.4 P3 |
| R-A6 | derived gates read by view AND handler | ✔ subs/flows unchanged from core | §2.1 |
| R-A7 | Enter/Escape/blur protocol; cancel-then-blur safe | ✔ˢ key-condition maps + `:fh.field/commit` (consult-state; generation-carried) | §4.1 |
| R-A8 | N instances, zero collision | ✔ˢ explicit `:control` addresses — one address prop per stateful instance, the corpus's own `:edit`/`:new` id ceremony; duplicate-owner dev error | §2.4 |
| R-A9 | async-transform race without flicker/arity tricks | ✔ nothing renders that app-db doesn't say | §4.1 |
| R-A10 | busy off async status | ✔ mutation-instance subs | §2.1 |
| R-A11 | headless draft→validity→gate assertions | ✔ JVM interpreter, real events | §5.5 |
| R-A12 | per-keystroke cost stated mechanically | ✔ decomposed: 1 sync drain + 1 epoch (+history) + spine allocations + live-instance sub checks + 1 boundary re-render (µs-scale walk, estimate) + the fixed pipeline — MEASURED ~1ms/keystroke Chromium dev (rf2-dpwel, corrected close), tier-blind; interpretation ≈ 1% of it, so compiled input leaves buy the static door proof, not latency. Grids: per-cell draft events price at epoch-noise/history growth — why the uncontrolled dodge stays the grid default | §2.3, §7.3 |
| R-B1 | measure-then-place without wrong-position paint | ✔ˢ behavior-registry measure before paint, `rf=`-gated; CSS anchor positioning where available | §4.2 |
| R-B2 | escape clipping/stacking without emulation | ✔ native top layer | §4.2 |
| R-B3 | outside-dismiss, listener lifetime ≤ mount | ✔ light-dismiss is the platform's; no listener exists | §4.2 |
| R-B4 | focus contract per overlay class | ✔ `<dialog>`/popover natives | §4.2 |
| R-B5 | keyboard nav for list bodies | ✔ˢ key-condition maps + the dropdown event trio | §4.2 |
| R-B6 | anchor-tracking honesty | ✔ declared contract (open + resize), no silent rAF loop | §4.2 |
| R-B7 | one owner per instance | ✔ the controller record at `control` + unconditional `:on-toggle` reconciliation | §4.2 |
| R-B8 | transitions without orphaned timers | ✔ˢ `v/presence` (§2.5): keyed retention with mandatory `:timeout-ms` as the deterministic terminal bound, mounting/unmounting attr overrides, re-entry cancellation, fake-clock testing — matching the shipped compiled runtime; CSS `@starting-style` still covers the trivial overlay case [knowledge] | §2.5 |
| R-B9 | LIFO nesting | ✔ native top-layer stack | §4.2 |
| R-B10 | closed overlay = zero DOM; JVM-safe render | ✔ conditional render + attrs-as-data | §4.2 |
| R-B11 | frame/context continuity | ✔ in-tree always | §4.2 |
| R-B12 | total teardown incl. mid-transition | ✔ unmount = disconnect; behavior cleanup owned; mid-transition teardown bounded by presence's mandatory `:timeout-ms` | §2.5 |
| R-B13 | honest headless/mounted split | ✔ policy as data headless; geometry/focus mounted | §4.2 |
| R-C1 | late settle must not clobber typed input | ✔ˢ blessed seed-merge-touched spelling (the live y4mgw trap made the paved path) | §4.3 |
| R-C2 | reply correlation | ✔ inherited contract | §2.1 |
| R-C3 | causal owner covers every exit | ✔ routes own reads; views own nothing | §2.1 |
| R-C4 | cancellation-failure survival | ✔ correlation gates + keyed cache entries | §4.3 |
| R-C5 | per-instance async status at N | ✔ resource/mutation instances as data | §4.3 |
| R-C6 | optimistic + rollback without disabling | ✔ inherited (`:optimistic?` tags) | harness C.1 |
| R-C7 | debounce has a visible home | ✔ˢ policy fx with id | §4.3 |
| R-C8 | composed identity | ✔ one `:control` address spans the parts | §4.3 |
| R-C9 | dirty-gate navigation | ✔ `:can-leave` + `:rf/pending-navigation`, unchanged | §2.1 |
| R-C10 | whole loop headlessly provable | ✔ the JVM interpreter drives the REAL reply path | §5.5 |

**R-T — the tier-2 requirements** (the 35 rows above are tier-blind; the
shipping compiled surface carries its own obligations):

| Req | Substance | Score | Where |
|---|---|---|---|
| R-T1 | call-site invariance | ✔ law 2; verified on every §4 transcript | §3.2 |
| R-T2 | test invariance | ✔ law 3 | §3.2 |
| R-T3 | one handler implementation across tiers | ✔ˢ law 1(b)/law 5 — a build obligation under absorption | §3.2 |
| R-T4 | parity mechanism named and buildable | ✔ˢ law 1: two-emitter mechanism + additive oracle + schema-gated corpora where schemas exist (D011 policy) | §3.2 |
| R-T5 | instance state survives promotion | ✔ explicit addresses are tier-blind by construction (D004) | §3.6 |
| R-T6 | markup-as-value crossing safe | ✔ `v/markup` boundary view over the C→I emission (D010) | §3.2 law 6 |
| R-T7 | loop-law extractions named per library control | ✔ §4 transcripts | §4 |
| R-T8 | door survives promotion or the forfeit is priced | ✔ˢ one-event law + literal-unroll analysis | §2.3, §3.6 |
| R-T9 | cold-start defaults taught | ✔ the placement table | §3.5 |
| R-T10 | day-one measurement obligations named | ✔ B1-B5 | §3.5, App. B.5 |

### §5.3 The examples coverage

Worked in this document: the RealWorld editor (§2.1 — judged clearly nicer than
the Reagent rendition; near-identical to the compiled one with the fieldset
table as the interpretation dividend), flight-booker (§4.1, clearly nicer),
CRUD + its promotion (§3.6), the buffered/temperature discipline (§4.1), the
dropdown (§4.2), the typeahead (§4.3). The stress case, cells:

```clojure
(v/defview cell [{:keys [addr]}]
  (let [{:keys [display editing? raw]} (sub [:cells/cell-view addr])]
    [:td {:class (when editing? "editing")
          :on-double-click [:cells/start-edit addr]}
     (if editing?
       [:input {:default-value raw :auto-focus true          ; the corpus's own dodge
                :on-key-down {"Enter"  (v/event [e] [:cells/commit addr (.. e -target -value)])
                              "Escape" [:cells/cancel-edit]}
                :on-blur (v/event [e] [:cells/commit addr (.. e -target -value)])}]
       display)]))

(v/defview sheet [_]
  [:table [:tbody
    (for [r (range 100)]
      [:tr {:key r}
       (for [c cols] [cell {:key c :addr [c r]}])])]])
```

The uncontrolled `:default-value` dodge is kept deliberately (a spreadsheet
should not write app-db per keystroke; the price — app-db cannot see mid-edit
text — is the dodge's documented trade). Mount cost: 2,600 tracked boundaries ≈
13-39ms (estimate; §7.3's decomposition; B2 measures it). Steady state is where
the design earns it: with a derived per-cell sub (`[:cells/editing? addr]`), one
click re-renders 2 cells, not 2,600 — the sub-graph fix, tier-free (the
derivation fan-out it creates — 2,600 trivial predicate recomputes per move —
is ~0.5-1ms, estimate, named in the A.2b trace). The cell promotes cleanly
under the roster (sites finite, keyed, literal; the uncontrolled input avoids
the door entirely); the evidence table says whether it matters. Remaining
coverage, compressed: counter/temperature/timer trivial (a 10Hz tick re-renders
one gauge boundary); circles — canvas coords via `v/event` (the corpus's one
`getBoundingClientRect` stays an event-object read); login/flows/nine-states —
machine-tag booleans read like any sub; websocket/long-running-work — the one
`with-let` unmount-dispatch re-homes causally or becomes behavior cleanup;
routing/SSR — `route-link` as-is; SSR is the same interpreter emitting on the
JVM with stable occurrence identity. No corpus shape renders worse than today's
spelling; the honest near-misses are the two `v/event` extractions above, where
Reagent's closure was equally opaque.

### §5.4 The component-library test

The harness's 15 re-com problem classes, each with this design's answer:

| # | Problem class | Answer | Beats all donors? |
|---|---|---|---|
| P1 | reusable stateful controls | semantic controllers at explicit `:control` addresses: caller pays one address prop exactly where durable state exists; state inspectable, replayable, seedable before mount | yes — Replicant pushes bookkeeping to callers; UIx hides it in hooks |
| P2 | controlled values + change events | value-in / intent-out; placeholder splice; no dual value-or-atom contract (the 235-site `deref-or-value` tax deleted) | yes — intent-as-data at the change site |
| P3 | validation + status | the blessed form-slice shape: `{:draft :baseline :touched :errors :submit-attempted?}` + derived gates + seed-merge-touched (R-C1's cure); schemas per slice | yes — donors have nothing at this layer |
| P4 | ephemeral interaction state | CSS-first; true ephemera in controller records; the input_text:90 bug class (state re-minted per render) is impossible — no render closure can hold state | yes |
| P5 | layout primitives | refuse the class: plain hiccup + modern CSS; no box DSL (census: zero box-family need) | n/a — deletion |
| P6 | overlays | top-layer intrinsics (§4.2); portals gated behind efxb1h triggers | yes — the ruled repo answer, made vocabulary |
| P7 | buffered/commit-draft inputs | ONE generation-fenced `buffered-field` (D016) dissolves the twin-atom class and the reject residue at the library tier — nzst23's explicit caller revision as an ordinary sub/event pair, tier-blind; acceptance suite ahead | yes for the class |
| P8 | focus + measurement | native elements carry focus; one-shot focus = `:auto-focus` or a focus behavior/fx (data, JVM-inert); measurement = registered behaviors with declared phase, assertable by `=` | yes |
| P9 | named-content / multi-slot props | hiccup values in props, free in tier 1; slot content keeps event vectors → intent-testable after composition; compiled crossing priced (`v/markup` for inert markup-as-value, declared boundaries for behavior); taxonomy: data props first, trailing children for the default region, compound child components for fixed regions, `v/render-fn` + `v/slot` for the parameterized row/cell/item renderer — COMMON forms in both tiers (ordinary pure fns interpreted; lexically visible bodies compiled — which is what lets a compiled table take a caller row-slot); stateful customization is a declared child view | tie on mechanism; wins on testability |
| P10 | async-data controls | resources/mutations INTO controls (§4.3): reads declared per instance key, debounce as policy fx, supersession by correlation | yes — the largest donor gap |
| P11 | theming/parts | TWO-PLANE contract (D018), because compiled leaves have no runtime tree to transform: (1) the portable plane — the CSS CASCADE is the transport: `data-theme`/class selects named token bundles of namespaced custom properties on a root/scope, so a theme switch re-renders nothing (no reactive theme-token subs, no per-leaf fan-out — a value prop remains for the genuinely dynamic residue); parts are stable semantic ADDRESSES emitted as literal `data-part` values scoped by a `data-component` marker (classes carry variants only, never part identity); per-part `:parts` maps are attrs-maps-ONLY, merged through the common `v/spread-safe` law — legal on controlled parts BECAUSE spread-safe denies key/ref/children/controlled values/owned handlers/required roles/top-layer state and preserves the door proof; unknown part ids and denied attrs get source-located dev findings; (2) the freedom plane — structure uses composition (children, compound children, `render-fn`/slot, declared child views), and pure tree→tree transforms remain interpreted-tier/test tooling, never a compiler seam or a way to theme a compiled leaf | yes, scoped |
| P12 | accessibility | native-element-first; structural trees make "every interactive node has an accessible name" a data assertion (dev sweep + CI walk); compile-provable warnings in tier 2 | yes at the bar that exists |
| P13 | lifecycle-sensitive behavior | causal ownership everywhere; no document listeners to leak; behavior connections and teardown assertable via owner state | yes |
| P14 | facade organisation | controls are views in the registry: enumerable, schema'd, tool-queryable — not 72 bare aliases | yes |
| P15 | args validation + self-documentation | props schemas are optional in the grammar, MANDATORY by policy here (D011: library/catalogue surfaces and any generated-parity claim); vector-form Malli, closed by default with an explicit `{:closed false}` escape; `:v/intent`/`:v/intent-prefix` schema types type the event-carrying props; a missing schema reports `:schema-status :absent`, never an implicit `:any`; the descriptor carries a `:parts` field enumerating D018's public part ids; one declaration → schema + didactic dev errors with source + registry metadata + generated docs; validation dead-code-eliminated from production; the context sheet is a birth requirement, TWO-TIER by construction: tier-decision section (App. B.4), per-shape legality matrix (§2.7's flips), tier-detection rule, and the AI loop "run `v/check` before editing any compiled view" | yes |

Ranked better-than-all-donors highlights: 1. P10 async controls on the
resource/mutation contract. 2. P6 overlays on the native top layer. 3. P2/P3
intent-as-data at the change site + the blessed form slice. 4. P7's explicit
caller-revision reset as an event protocol. 5. P13 causal ownership as an
assertable property.

### §5.5 The testing story

One story across tiers: the interpreted JVM render produces the same versioned
structural tree as the compiled JVM emitter (element / view-boundary / fragment
/ trusted-HTML / text; events under the `:events` projection read through
`attrs`); same six names (`render`/`attrs`/`text`; `with-root`/`flush!`/
`flush-presence!` mounted). No parallel simulation API exists: a test reads
intent from the tree and DISPATCHES that very value through the real router;
`sub` reads resolve through the real graph or the same `:sub-overrides` door
the runtime honours; boundary nodes carry their occurrence identity, and a
stateful control's `:control` address is ordinary props data in the tree — so
controller state is addressable test data.

```clojure
(deftest dropdown-opens-and-commits
  (rf/with-new-frame
    (rf/reg-event :cart/set-size (fn [{:keys [db]} [_ v]] {:db (assoc db :size v)}))
    (rf/reg-sub :cart/size (fn [db _] (:size db)))
    (let [form   [select-dropdown {:control [:demo :size]
                                   :items [{:value :s :label "Small"}]
                                   :value nil :on-commit [:cart/set-size]
                                   :placeholder "Size"}]
          node   (fn [tree pred] (some #(when (pred %) %)
                                       (tree-seq map? :children tree)))
          t0     (v.test/render form)
          toggle (:on-click (v.test/attrs (node t0 #(= :button (:tag %)))))]
      (is (= :fh.dropdown/toggled (first toggle)))  ; the tree SAYS what the trigger does
      (rf/dispatch-sync toggle)                     ; …then DO it, same semantics as a click
      (let [t1     (v.test/render form)
            option (node t1 #(= "option" (:role (v.test/attrs %))))]
        (is (some? option))
        (rf/dispatch-sync (:on-click (v.test/attrs option)))
        (is (= :s (rf/subscribe-once [:cart/size])))))))  ; the one-shot read, by name (D005)
```

`v.test/render`/`v.test/attrs` ARE the production six-name structural surface
under this substrate's namespace — not a parallel test API. Node location gets
one pure query pair — `(v.test/find tree pred-or-attrs-map)` /
`(v.test/find-all …)` (first/all nodes matching a predicate or an
attrs-subset map) — a data query, not a simulation verb; the taught addressing
order is role/accessible-name first, a stable domain attribute second, tag
last (testids remain legal tree data where a team wants them). Value-carrying
intents dispatch through `v.test/dispatch` with the explicit payload map
(§2.3, D006) — the production materializer, reused; no test-only splice idiom
exists. Debounce/throttle policy fx ride the clock capability, so their timers
advance through the clock capability's existing test double — deterministic
firing with no new Freehand verb (D017; the `flush-presence!` shape stays,
because presence owns its own retention clock) — traces made the
policy visible; the clock makes it controllable. What still needs a mounted
test, honestly: focus/caret under the door, IME, hydration, third-party
components, registered behaviors against real nodes, and EVERY `v/event` site (an
opaque marker on the JVM — not assertable as intent, not fireable — so each
one adds its path to the view's mounted obligations). The mounted matrix
splits explicitly: jsdom suffices for wrapper/hydration smokes; top-layer
behavior (`:rf.web/popover-open?` light-dismiss, focus return) requires a REAL browser —
jsdom does not implement the popover/dialog top layer [knowledge]. SSR: the
same JVM walk with an HTML emitter; hydration attaches handlers client-side.
Effects don't run and host ops raise on the JVM, exactly as today. Views'
events/subs stay `.cljc`; a promoted view's props schema doubles as the
generative corpus for the app's own render-every-view CI sweep.

---

## §6 Alternatives considered

Each entry is rationale, kept because the losing argument constrains the
winning design.

- **Whole-root re-rendering (the Replicant model).** One pure root from
  already-available state; no subscriptions, no cells, no cleanup — maximal
  simplicity. Its own mechanical analysis locates the break: the cost of any
  change is the size of the page (keystroke × page-size coupling at ~10⁴
  nodes), and memoizing a sub-reading view without knowing what it read IS
  per-view tracking. With re-frame's sub graph, top-down forces every read to
  the root and threads it down — reusable controls become prop-plumbing
  exercises. Per-boundary tracking is the right granularity for this corpus's
  idiom (231 inline reads; per-row subscriptions); the coarse style survives as
  plain `defn` helpers inlined into one declared boundary, not a separate
  model. What was kept from the pole: the ownership-free probe (surviving
  under its own name, `rf/subscribe-once`), the identity gate
  instinct, and the honesty discipline of its concession ladder.
- **Reagent + discipline.** Already has JVM structural testing, SSR,
  registries, frames — and the corpus obeys the no-ratom discipline at zero
  migration cost. Falls on demonstration: intent equality vs
  invoke-and-poll ("the Create button carries `[:crud/create]`" is one `=`
  here); the key-condition map vs an opaque `handle-keydown` closure; and two
  authoring-failure CLASSES removed, not instances — ephemeral state minted
  inside render closures (re-com's own authors shipped that bug), and stale
  closures over old state (handlers here are vectors resolved at fire time).
- **Interpreted-only (no compiled tier).** Overridden by the operator axiom.
  Its evidence survives as placement: nothing in the corpus needs compilation
  (largest real list: 10 rows; the 2,600-cell sheet is cured by a derived
  sub), which is exactly why app code defaults interpreted and promotion is
  evidence-driven. Its strongest residual argument — every genuinely hot
  surface taken at the foreign-React boundary forfeits intent-as-data, JVM
  tests, and instance state inside the leaf — is now an argument FOR the
  compiled tier: it preserves substrate semantics precisely where the door
  would lose them.
- **re-frame.ui + a thin ergonomic layer (no interpreted tier).** The
  friction programme shipped 9/10 (bd rf2-u53yy) and the compiled editor
  rendition already reads like dream code for that page. What a thin layer
  cannot deliver: full-language tier-1 views (the closed grammar's residue —
  the defhook gap, dynamic heads, forwarding wrappers — is permanent by
  design), instance state without `local`'s outside-epochs carve-out, and the
  production interpreter as the same artifact tests run. The absorption ruling
  completes this alternative rather than refuting it: re-frame.ui's useful
  code IS the compiled tier's implementation, fronted by the interpreted
  tier — and the standalone artifact then retires.
- **UIx + better bindings.** Fails the goal block structurally: cannot test as
  data without mounting React (strings can't be dispatched into), cannot
  guarantee handlers-as-data or the one-state pin in a substrate this repo
  does not own. Survives as two permanent facts: the L1 competitive floor
  (every §4 judgment asks "nicer than the UIx spelling?"), and the LLM-prior
  warning — countered by the generated context sheet as a birth requirement.
- **A macro-thin compiled tier (compile literals, keep dynamic capture —
  "VENEER"/Living-hot).** Speed without knowledge: removes the walk but
  cannot produce a complete manifest or prove a cell unnecessary, and a
  partial site table would make every completeness claim false. Rejected by
  both design tracks independently; under absorption it is moot (the absorbed
  compiler is the proof-bearing one).
- **A plan-cache interpreter (JIT).** Interpret once per view shape, replay
  the plan — would close an unknown fraction of the walk cost with zero
  dialect, at the price of shape-guards, deoptimisation, and a third
  execution model with u53yy's soundness ghosts. Evaluate-and-record before
  any analyzer work remains the diligence obligation; expected to lose.
- **Nested `{:on {…}}` event grammar; alias indirection; app-dispatched
  lifecycle** — Replicant organs refused: the shared `:on-*` vocabulary wins
  promotion-compatibility and grep-ability; tier-1 dynamic heads make aliases
  unnecessary; lifecycle-to-the-app invites rendering to own domain lifetime.
- **Multi-intent handler vectors** — refused as grammar (§2.3's one-event
  law); the composed-action need is served by consult-state library events.
- **Derived structural anchors as writable state addresses** — overturned by
  D004. The losing argument's residue constrains the winner: derived occurrence identity keeps every job it was
  good at (reconciliation, presence, event-site ownership, evidence joins) in
  the tool plane; it simply never becomes an application storage key, because
  a view rename, parent extraction, subtree test, or key migration must not be
  a state migration. The zero-ceremony appeal was strongest exactly where it
  was least safe — small controls whose authors think least about identity.

### Deliberate non-goals

What this design will not build, stated once so absence reads as decision:

- no second compiler, and no compiled-with-interpreted-fallback hybrid — a
  compiled template never hides an interpreted subtree (`v/markup` is a
  visible declared boundary, not a fallback);
- no plan-cache/JIT interpreter as shipped machinery (evaluate-and-record
  diligence only, expected to lose);
- no automatic promotion, and no percentage-driven "hot 5%" rule — tier choice
  is manual, evidence-guided, per view;
- no multi-intent handler vectors and no recursive event DSL — one event per
  user action; the grammar is the closed §2.3 roster;
- no second component-local reactive state system — no ratoms, no
  useState-alike, no hooks in neutral views; `local` is deleted with the
  donor artifact;
- no `:reads` declaration language in v1 — inline `v/sub` is the one read
  language; static dependency data comes from compiled manifests;
- no public occurrence reader — `v/self` does not exist; occurrence identity
  stays in the tool plane, and writable state takes explicit addresses;
- no app-dispatched lifecycle — mount/unmount facts go to tools, never to
  domain events; render never owns causal lifetime;
- no neutral hooks/refs/effects/portals vocabulary beyond the behavior
  registry and the top-layer intrinsics — React protocols live in the wrapper;
- no parallel test or simulation API — no structural `click!`, no mirrored
  dispatch, no selector DSL beyond the pure `find`/`find-all` data queries;
- no serialization of host objects — nodes, React elements, callbacks, and
  library instances never enter app-db, intent vectors, or traces;
- no tree→tree transforms as a cross-tier theming seam, and no layout DSL;
- no whole-app closure claims from the interpreted tier — every evidence
  projection states scope, basis, completeness, and loss, never conflated;
- no permanent standalone compiled-only product — the donor's useful code
  folds in; the artifact retires at the gate.

---

## §7 Risks, wounds, obligations

### §7.1 Standing wounds and tensions

- **Anonymous-view identity — CLOSED by construction (D002).** Every boundary
  is declared, so every boundary has a stable qualified id, source
  coordinates, a reload revision, and a registry row for tools/docs/the
  context sheet; a HOF cannot mint an undeclared boundary, and helper fns
  never carry boundary identity at all. The shared descriptor shape —
  `{::v/view true :view-id … :source … :profile … :parts … :mount <host-entry>
  :tree <structural-entry>}` (its inspection projection; the runtime value is
  a descriptor type that cannot be successfully called, D002; `:parts` per
  D011/D018; `:props`
  optional, `:schema-status :absent` when missing) — is what vector heads
  resolve through and what makes cross-mode children descriptor dispatch
  rather than special cases. What was a wound is now one macro of ceremony per
  boundary, priced in B.2.
- **Fn-handler identity at memoized foreign boundaries — CLOSED (D008:
  REJECTED).** No deps-annotated `v/event` exists; this design ships zero deps
  arrays. The per-site committed proxy already gives `v/event`/`v/handler`
  stable JS identity with atomically updated bodies, so foreign
  `React.memo`/effect-deps see one function per site. `v/render-fn` is the
  honest exception — no cross-mode identity guarantee, because candidate
  renders may invoke it — and APIs that treat callback identity as protocol
  data use `v/raw-fn`, the D014 bridge, or a wrapper. Library pilots measure
  callback identity churn; any future shorthand needs that evidence.
- **The value-vs-syntax cliff.** Tier 1's deepest idiom (hiccup as value —
  helpers, props, `map`) is compilation's deepest impossibility. The crossing
  is priced at promotion time, loudly: the `v/markup` boundary view, its
  mount count in the `:interp-slots` evidence column, and the extraction
  transcripts. The cliff is real; nothing dissolves it.
- **Address migration and duplicate owners.** Explicit addresses (D004) delete
  the positional-swap class, and what remains is application data modeling:
  an address reused twice fires the duplicate-owner dev error with both
  source locations; a domain identity migration (temp→server id) is visible
  caller work on the address, not a silent orphaning; and address design must
  be taught — random ids to dodge it defeat replay and tests. If the
  diagnostics prove insufficient, the fix is better evidence, not derived
  addresses.
- **Controller-record silt.** Long sessions accumulate dead controller
  records until an owner clears them. Mitigations: `clear-under` on route
  leave as the taught idiom, the Xray controller browser, the `(v/orphans …)`
  report (records with no mounted occurrence join for N epochs). Visible silt
  in an inspectable place — deliberately chosen over invisible state in
  component slots.
- **The splice false positive.** A reserved `::v/*` keyword travelling as
  ordinary payload data would splice. Fenced by the reserved single-root
  namespace + a dev warning on reserved keywords in non-handler dispatches;
  residual risk accepted, labelled.
- **The R-C1 merge discipline.** The blessed form-slice shape cures the
  same-slug clobber only where adopted; apps hand-rolling seeds re-import the
  bug. The substrate's leverage is docs/skills defaults; it cannot make wrong
  seeds unwritable.
- **The `local` tension.** The compiled substrate ships `local` (outside
  epochs, deliberately, 004:574-612); this design's harder pin refuses it and
  routes ephemera through controller records — keystroke drafts INTO epochs.
  The costs are opposite: `local` is invisible to replay/tools; controller
  state is epoch traffic and silt. The census says the corpus already lives on
  the app-db side, and the uncontrolled dodge remains for keystroke-noise
  cases — but the standing prediction is recorded: if draft/disclosure
  pressure presses harder than the controller family absorbs, the PIN — not
  the design — is what to re-examine.
- **Presence/transitions — designed (§2.5), obligations residual.** What
  remains for the `v/presence` contract is conformance:
  the browser matrix for retention/re-entry/accessibility, the JVM metadata
  emission, and the cross-tier parity rows (the shipped compiled runtime is
  the reference implementation). At re-com scale the verified need is narrow —
  dropdown enter/exit, hover-tooltip transitions, popover reveal — so CSS
  `@starting-style` remains the zero-machinery answer for the trivial case,
  and Framer Motion's AnimatePresence (ordinary foreign heads) remains a
  legitimate alternative for teams in its idiom; `v/presence` is what library
  controls build on.
- **The grammar treadmill.** Every quarter one more audited form wants into
  the hot grammar. Growth only by ruling, with hidden-reactive-injection
  counterfixtures (the 004:246-257 discipline); under absorption the
  treadmill turns one crank by construction.

### §7.2 Pre-mortems

- **Death by seam drift**: interpreter and compiler normalize a prop or a
  forwarded event differently and structural tests lie — law 1's shared
  handler implementation and the parity gates exist for exactly this; if they
  rot, everything above them is decoration.
- **Death by silent identity**: AI-generated code reuses one `:control`
  address across records, or under-keys rows and blames the state layer when
  reconciliation churns; trust in controller records never recovers. Guards:
  the duplicate-owner error with both source locations, the context sheet's
  address-design and keying rules, the taught causal-ownership address
  idioms — explicit addresses mean a swap now requires writing the same
  address twice, which is diagnosable, not silent.
- **Death by markup-boundary culture**: hot views run 40% interpreted through
  `v/markup` children; promotions stop measuring; the two-tier system feels
  like one mediocre tool. The `:interp-slots` mount count and law 4's
  continuity make it visible early; if nobody reads them, the seam design
  failed.
- **Death by treadmill**: the rejection list scares teams off promotion; the
  analyzer serves 2% of views and rots undertested. Counter-signals to watch:
  rulings-per-quarter on the grammar; promotion count; parity-gate red
  frequency.
- **Death by benchmark chart**: per-keystroke full passes made a bad chart in
  someone's video — prevented not by argument but by B4's measured
  tier-blind pipeline being on record first.

### §7.3 Uncertain assumptions → constraints now

| Uncertain assumption | What would falsify it | Constraint now |
|---|---|---|
| per-node interpretation cost is a minor constant | profiles show walk/normalization dominating after granularity + windowing | the split's placement arithmetic depends on it: measured at birth (B1-B5). Derived regimes (estimates, stated assumptions): wide-parent churn ≈ 7-15ms/parent render at 10⁴ keyed children; mount storm ≈ 13-39ms for 2,600 boundaries (mint + capture + commit + hook pair ≈ 5-15µs each) |
| the per-keystroke pipeline stays ~1ms and tier-blind | B4's interpreted arm or the background-storm run diverges | R-A12's row and the door's placement advice both cite dpwel's corrected close; B4 re-verifies with an interpreted arm |
| descriptor HMR identity behaves under rename/move | renamed/moved declared views orphan tool joins or remount unnecessarily | every boundary is declared (D002), so identity is descriptor-keyed, never munged-name-derived; the HMR generation source is a process-global reload epoch bumped per after-load plus per-descriptor revisions; controller state is address-keyed and cannot orphan on reload |
| dev-warning noise is tolerable on by default | the unkeyed-multiplicity + door-flap warnings drown signal | on-by-default warnings are only contract misfires (D020), per-site once, with occurrence coordinates; quality lints live in opt-in `v/check`; the duplicate-owner ERROR is exempt (fires only on corruption risk); on-by-default is revisitable policy |
| CSS anchor positioning's support matures | Firefox stalls | constrains only the placement fallback's lifespan (§4.2's behavior contract) |
| the nzst23 acceptance suite passes for the buffered field | same-value reset, caret/IME, or per-keystroke cost pins fail | the row stays ✔ˢ-with-condition; failure re-examines the one-state premise, not the paperwork |

### §7.4 The measurement obligations

The B1-B5 benchmark matrix (Appendix B.5) is a day-one obligation: B1 the walk
constant; B2 the cells-shaped mount storm (with the elision variant); B3 the
10k-row memo comparison (with a windowed variant under ticks); B4 the extended
G-8 input harness (interpreted arm + background-storm run); B5 the bundle
deltas. Every §3.5/§7.3 constant traces to a row.

Release policy over those results is TWO-LANE (D021). Lane one gates:
deterministic properties — semantic/structural equality, no dropped input,
exact attributable commit counts, manifest and cell-elision claims, row commit
counts, bundle reachability — run on ordinary CI and block release when they
regress; engine, hardware, and timer-granularity drift cannot flap them red.
Lane two informs: wall-clock and byte distributions are MANDATORY PUBLISHED
EVIDENCE, never pass/fail thresholds — results ship in the shipped g8 result
shape (revision, fixture, build, host/hardware class, sample count,
distribution percentiles, named baseline delta, `:status :evidence`), noisy
full runs on a pinned scheduled/release worker with a small smoke subset on
PRs where stable. A stable adverse trend opens an attribution bead — the trend
must be attributed, explained, and dispositioned explicitly, but it does not
auto-fail a release; and any public numerical claim must cite its supporting
artifact or be withdrawn. No numerical budgets are ratified — not now, not at
beta: choosing thresholds from estimates would be folklore, and correctness
invariants are always judged before timing is considered. A null result
(compilation not helping a workload) is design information and may change
placement guidance.

---

## §8 For the operator

**The ruling table — everything CORE.** With D001–D021 ratified (2026-07-22),
every surface this document carries is settled design, converged with the
product spine. Where the two documents forked, the fork is now a ruling — one
line each, full dossiers in `decisions/`:

| Decision | Ruling (one line) |
|---|---|
| D001 | the product is Freehand; one public door, `re-frame.freehand`, alias `v` |
| D002 | sharp declaration boundary — `v/defview` for every boundary; helpers never vector heads; the paren/bracket dial retired |
| D003 | semantic controllers over shared infrastructure; storage verbs only for protocol-free state |
| D004 | explicit caller-supplied `:control` addresses for writable state; occurrence identity stays tool-plane; no public `v/self` in v1 |
| D005 | `v/sub` is render-only; `rf/subscribe-once` is the named one-shot read |
| D006 | projections materialize in Freehand's event adapter (`::v/value`/`::v/checked`/`::v/key`); no payload arity on general dispatch; tests reuse the materializer via `v.test/materialize`/`v.test/dispatch` |
| D007 | the closed key-condition map is confirmed, with the normative isComposing clause and a delete-before-release pilot gate |
| D008 | the closed callback roster with per-site committed slots; no `v/dispatcher`; no deps-annotated `v/event` |
| D009 | the narrow synchronous door with a frame-scoped flush; no `:on-before-input`; options-maps-with-vectors eligible; promotion parity is an emitter obligation |
| D010 | no dynamic-markup valve in v1; `v/markup` is the blessed boundary crossing; `:interp-slots` counts its mounts |
| D011 | props schemas optional in the grammar, mandatory by library/catalogue/generated-parity policy; closed-by-default Malli |
| D012 | no `:reads` declaration in v1; evidence is the scope×basis×complete?×loss record everywhere |
| D013 | registered behaviors (`host/defbehavior`, connect/update/disconnect) with closed passive/layout timing and the bounded `:commands` channel; semantic-id targets only; `:v.scroll/window` deferred |
| D014 | `v/->react` on the shipped contract: shallow uncoerced copy, reserved `frame` prop, one `:map-props` adapter, descriptor-only, descriptor-keyed caching |
| D015 | the closed top-layer intrinsic pair `:rf.web/popover-open?`/`:rf.web/modal-open?`; no neutral portal; efxb1h triggers gate any future helper |
| D016 | one generation-fenced buffered controller; required `:reset-key`; begin = first edit; cancel = semantic clear |
| D017 | control families are first-party LIBRARY vocabulary (provisional `:fh.*` root), never reserved framework grammar; policies graduate by the promotion test |
| D018 | two-plane theming: CSS-cascade tokens + `data-part`/`data-component` addresses + bounded `:parts` spreads through `v/spread-safe`; transforms stay interpreted/test-only |
| D019 | `v/error-boundary` with `:reset-key` recovery and once-per-failure-generation `:on-error`; production detail rides the Spec 009 error axis + frame sink; snapshots opt-in only |
| D020 | one versioned occurrence-keyed evidence schema; hard errors only for semantic corruption; warnings once-per-site, contract-misfires only; retention on 009's ring; the anchor is one additive join field |
| D021 | two-lane release policy: deterministic gates; wall-clock/bytes as published evidence in the g8 shape, never thresholds |

**Nothing before implementation needs the operator.** What remains is
implementation-time confirmation, owned by the build and the pilots, not by
further design: the nzst23 acceptance suite for the buffered controller; the
real-browser matrices (door caret/IME under contention, top-layer
light-dismiss/focus-return, presence retention/re-entry); the component and
library pilots that gate donor deletion (§3.4) and the key-condition map's
delete-before-release evidence (D007); the D013 Vega/SpreadJS-class behavior
pilots and the D014 bridge pilots; and the B1–B5 measurement obligations with
D021's two-lane policy. EP-0036 owns programme sequencing, donor migration, and
canonical-spec graduation; this dossier does not restate them.

**Premises, reframed where the design disagrees with the original brief:**

- **"90/5"** is the operating assumption by axiom; the census's contribution
  is that the 5% is not located in the current corpus — it names an app class
  (dense unwindowable grids, mass-instantiated library leaves, per-frame
  animation), which is why the library exception and the cold-start defaults
  place the tiers, not folklore.
- **The snabbdom test, scoped**: applied as pressure, it did its job (the
  neutral core is the data; the coupling lives in the lowering layer). Two of
  the substrate's best answers — the top layer and the synchronous door — are
  DOM-platform answers, and the door's timing is React-specific; symmetric
  renderer-agnosticism would have diluted both. A design probe, not a
  deliverable.
- **"The same Hiccup in both tiers"** means the same elements, props, events,
  keys, view calls, and output meaning. Arbitrary tier-1 Clojure that
  manufactures markup is not guaranteed a mechanical compiled translation —
  the extraction transcripts are the honest cost.

---

## Appendix A — semantic traces

Owner cast: VIEW (the user's declared view) · INTERP (walks hiccup, lowers,
mints handlers owned per site — committed node identity × event-prop, computes
occurrence identity) · CELL (per-instance
LiveCell) · PORT (the six-op observation port, unchanged) · SCHED (dirty
registry + microtask + scoped flush) · HOST (React) · CORE (events, drain,
epochs, sub graph, frames).

**A.1 Initial render.** HOST mounts wrapper → CELL minted `:fresh` → VIEW runs
under the capture (probe-only; slice memo shared across sibling first-mounts) →
INTERP walks: memoized tag parse, props conversion, handler mint, child
boundaries become elements NOT calls — the walk stops at boundaries → layout
commit: 8 steps — nothing retained, all staged (acquire-before-release),
evidence compared, published, connected; StrictMode's double render abandons
with zero ownership → paint.

**A.2 Sub change (steady state).** Event → CORE drain (N epochs) → PORT
`on-change` per owning handle (constant work) → SCHED dedups; the FIRST mark
arms one microtask → checkpoint flush advances each dirty cell once → HOST
re-renders exactly those boundaries → probes fresh values → commit kept-checks
(typically all retained) → paint. Two back-to-back `dispatch-sync!` in one
stack = one render batch.

**A.2b The door under a pending batched window.** A background storm has
dirtied cells; the user types into a controlled input. The door drain is
frame-scoped synchronous: event → drain → `flush-frame!` — which flushes EVERY
dirty cell observing that frame, so tick-dirtied cells ride the keystroke's
discrete-event render. Correctness holds by the batch law; the honest
consequence is keystroke latency COUPLING to background-storm work —
tier-blind, bounded by granularity discipline on the storm's boundaries,
measured by B4. Relatedly, the derived-sub granularity fix moves work into the
sub graph rather than deleting it (cells: 2,600 trivial predicate recomputes
per editing-id move, ~0.5-1ms, estimate).

**A.3 Conditional-dependency change.** Render N reads `#{[:ui/tab]
[:inbox/items]}`; the event moves `[:ui/tab]`; render N+1 reads `#{[:ui/tab]
[:archive/items]}`; commit retains `[:ui/tab]`, STAGES `[:archive/items]`
before RELEASING `[:inbox/items]` (last owner ⇒ the node leaves the graph). An
`[:inbox/items]` change now touches nothing here. Cleanup is a commit
responsibility, not a GC hope.

**A.4 Keyed reorder.** `[:todo/move :b :front]` moves `[:todo/visible-ids]`;
the LIST cell dirties; row cells do not → list re-renders; HOST matches
children by key → row INSTANCES move with cells and occurrences attached; row
props `rf=` ⇒ the memo skips row bodies; zero handle churn. Controller state
is keyed by explicit address (D004), so reorder AND key migration leave it
untouched — the occurrence join simply follows the moved instance.

**A.5 Unmount / frame teardown.** Disconnect releases handles, records kept
values for exact reuse on reconnect, annotates the lifecycle fact honestly
(`:unknown` → retroactive hide/unmount). Root teardown reaps hidden cells via
the weak root registry; a frame destroyed mid-flight sweeps its observing
cells; scoped `flush-frame!` keeps epoch work from leaking across roots.
Registered behaviors run `:disconnect` with their remembered state.

**A.6 Hot reload.** shadow re-evals the ns → vars hold new descriptors →
after-load re-renders the root; dev component identity is descriptor-keyed
(every boundary is declared — D002) so React updates in place; controller
state is keyed by explicit address (in app-db anyway), so reload cannot orphan
it; kept-check retains identical queries — no resubscribe storm. A
capture in flight from the old body is rejected by the commit's generation
fence, whose source is one process-global RELOAD EPOCH bumped per after-load
plus per-descriptor revisions for finer fencing. No
descriptor transactions, hook signatures, or remount generations exist to
need.

**A.7 Thrown error mid-render.** The render was probe-only ⇒ the abandoned
capture owns nothing; the cell's committed set/values/handles remain. HOST
propagates to the nearest `[v/error-boundary …]`; the fallback renders;
`:on-error` dispatches ONCE per failure generation, after the fallback
commit, carrying the safe summary as data (D019 — diagnostic id, view and
boundary ids, phase, frame public id, the occurrence as a correlation token;
no `:epoch`, no exception object); the host promotes one bounded record onto
the frame's error egress; a `:reset-key` change clears the capture and
remounts the subtree. Escaping all boundaries unmounts
through the ordinary disconnect path. Nothing leaks; nothing half-commits.

**A.8 The seam crossing.** Chain: interpreted screen → compiled list →
compiled sub-free rows (cells elided) → interpreted row-actions → compiled
confirm-button.

| crossing | mechanism | cost |
|---|---|---|
| I mounts C | INTERP resolves the Var, finds the compiled marker, mounts the compiled component with one props value | none; the child's memo is the generated comparator — same `rf=` predicate |
| C mounts I | compiler emits a boundary-element call handing props to the tracked interpreted component (D1's C→I emission) | the child re-enters dynamic tracking; the manifest marks the site `:interpreted` — static evidence ends here, honestly |
| frame scope | ONE provider chain read by both runtimes | none |
| keys / addresses | `:key` → host slot; controller addresses explicit in both modes (D004) | none — state identity never derives from position |
| events | same vectors, one dispatcher, firing-time materialization | none semantic; per-site prebinding above is invisible to meaning |
| errors | one envelope `{:view :coord :phase}` | none observable |
| dev evidence | law 4: the same counters from both runtimes | granularity shifts (manifest above, capture below); the table says so |

Closed under composition: I→C→I→C adds rows, not rules.

---

## Appendix B — matrices and budgets

**B.1 The elision/DCE/manifest ledger** (what re-frame.ui-as-shipped has, and
this substrate's position):

| Asset | This design |
|---|---|
| compile-time rejection as default authoring | opt-in: promoted views + the library exception's compiled-at-birth leaves |
| per-view manifests | one evidence record everywhere — the scope×basis×complete?×loss grid (§3.5): compiled manifests prove possible sites; the CI corpus union is observation over a named corpus, never a program proof |
| sub-free cell elision | kept, provable, per promoted view; the interpreted "analogue" recovers sub-graph enrolment only (§3.1) |
| whole-app closure ("only these views/intents exist") | forfeited — tier 1 is open by design |
| interpreter-free bundle | forfeited permanently (the interpreter ships; B5 measures the bytes) |
| JVM parity mechanism | shipped shared-analyzer/two-emitter mechanism + interpreter as additive oracle (law 1) |

**B.2 Concept + ceremony budget.** Paved path: hiccup · declared boundaries
(`v/defview` + `[view props]`; helpers stay parens) · `sub` · event forms ·
`:key` · controller records + `:control` addresses · the escape roster. Seven.
The hatch tier met in the first weeks:
`v/error-boundary` · `::v/behavior` · the top-layer intrinsics · an explicit
React wrapper + `v/->react` at the React boundary — twelve. The library/hot-surface owner
adds the `{:compiled true}` option and `v/markup` — fourteen — plus the
teaching load of the three fence sentences (markup visible, sites finite,
macros closed) and the tier-decision procedure (cold-start table + evidence
override), front-loaded exactly on the population the library exception sends
to tier 2 first; app authors hold seven until their first promotion.
Optional beyond that: props schemas outside the library/catalogue policy
gates (D011).
Ceremony per worked example: one `v/defview` per boundary, zero wrapper
forms, zero identifiers minted beyond the domain's, one `:key` per row, one
`:control` address per stateful control.

**B.3 Tutorial page 1 — both tiers, verbatim** (what it never mentions: tiers).

> **Your first view.** A view is declared with `v/defview`. It takes one map,
> reads what it needs, and returns what it looks like:
>
> ```clojure
> (ns shop.views (:require [re-frame.freehand :as v :refer [defview sub]]))
>
> (defview cart-badge [_]
>   [:span.badge (sub [:cart/count])])
>
> (defview header [_]
>   [:header [:h1 "Shoply"] [cart-badge {}]
>    [:button {:on-click [:nav/go :cart]} "Cart"]])
> ```
>
> `(sub [:cart/count])` is the current value — not a box, not a signal, just
> the number. Write `[cart-badge {}]` with square brackets and the badge
> re-renders by itself when the count changes; nothing else does. (Plain
> `defn` helpers are still just Clojure — call them with parens inside a view;
> only declared views go in square brackets.) The button
> doesn't take a callback: it carries WHAT SHOULD HAPPEN, as data. That means
> this test passes without a browser, and it is the whole testing story in one
> line:
>
> ```clojure
> (is (= [:nav/go :cart]
>        (:on-click (v.test/attrs (v.test/find (v.test/render [header {}]) {:tag :button})))))
> ```
>
> That's the model. State lives in your app's data; views read it with `sub`;
> intent rides the tree as vectors. Everything else is ordinary Clojure.

**B.4 Tutorial page 2 — when a view compiles** (first week, not first day):

> **Most views never compile.** Pages, routes, and composition stay ordinary
> functions — that's where full Clojure pays. Three surfaces compile:
>
> 1. **Library controls compile when shipped.** Publishing a reusable control?
>    Compile its leaves (`{:compiled true}` on the declaration); stateful
>    controls already take a caller `:control` address, which promotion never
>    touches — your consumers' pages can't be profiled before they exist, so
>    the library pays the tax for everyone or nobody.
> 2. **Big keyed rows compile when measured.** If `v/hot-views` shows a row
>    template dominating (`self-ms × renders`, high `nodes`), window the list
>    first, then compile the row. High `:stable-renders` means narrow your
>    subs instead — compiling wasted renders just wastes them faster.
> 3. **An input site compiles to pin its caret behaviour** if dev warns that
>    its handler classification flaps.
>
> To compile: run `(v/check my-view)`. It either says "clean" or names every
> edit — usually `map`→keyed `for`, a markup helper declared into a mounted
> view (`v/defview` + `[helper {}]`), and
> occasionally extracting a keyed row child. "Keep this view interpreted" is
> always a legal answer. Your tests don't change; your call sites don't
> change; the same table that told you to compile shows you what it bought.
>
> **AI rule:** before editing any compiled view, run `v/check`; the idioms
> that differ are exactly: markup helpers (become boundaries), `sub` in loops
> (extract keyed child), computed element heads (close over `case`), dynamic
> props maps on controlled inputs (unroll or stay interpreted).
>
> **Debugging a compiled view:** delete the marker, debug it as an ordinary
> function (REPL, plain stacks), re-promote with `v/check`. Nothing else
> changes — not tests, not call sites, not the counters.

**B.5 The day-one benchmark matrix** (every §3.5/§7.3 constant traces here):

| # | Shape | Metric | Decision it informs |
|---|---|---|---|
| B1 | one boundary re-rendering a 10³-10⁴-node template, interpreted vs compiled | per-node ns; p95 re-render ms | the walk constant; cold-start thresholds; calibrates the external UIx 1.6×/2.7× figure |
| B2 | cells-shaped mount storm (26×100 boundaries, 3 subs each) interpreted vs compiled vs sub-free (elision live) | mount ms; per-boundary µs split (mint/capture/commit/hooks) | the mount-storm regime; how much the shared capture/commit bounds the compiled win; route-transition evidence |
| B3 | 10k keyed rows, `rf=`-stable props: parent re-render, generic memo vs generated comparators; windowed-40 variant under 20Hz ticks | ms/parent render; µs/skipped row | memo-downgrade materiality; the unwindowable-grid placement |
| B4 | the shipped G-8 input harness + an interpreted arm; run under a 20Hz background storm | p95 event→commit/keystroke; storm-coupled latency (A.2b) | R-A12; the predicted null (interpretation ≈ 1% of the ~1ms tier-blind pipeline) |
| B5 | bundle: interpreter+reactor entry vs compiled-runtime baseline; per-promoted-view delta | KB gzipped | the interpreter-bundle speculation; whether elision's bytes matter at library scale |

---

## Appendix C — provenance and verification

**Provenance.** This dossier is the consolidated product of: a five-seat blind
design studio (two granularity poles, a compiled-seam seat, a steelman of the
four do-nothing alternatives, and an independent fitness harness — 35
acceptance requirements, an 85-file corpus census, a 15-class re-com problem
inventory); a cross-fold with the sibling exploration `codex-design.md`
(verified catches adopted in both directions; the adopt-don't-build shape — since superseded by the absorption ruling — and the
consult-state commit law originate there; derived anchors, the key-condition
map, and the attach-registry promotion originate here); a four-agent
adversarial review (a premise-free correctness sweep plus three lenses under
the compiled-from-birth axiom: seam architecture, DX/AI authoring, performance
honesty — all findings adjudicated, load-bearing ones re-verified at source);
a four-lens PROGRAMMER EVALUATION of the consolidated design (a library author
porting re-com component-by-component against a local re-com checkout; an
integrator wiring VegaLite, SpreadJS, AG Grid,
CodeMirror, Framer Motion, and dnd-kit; a tester building a real suite; a
debugger walking ten 5pm scenarios against the shipped Spec 009/Xray surface —
their confirmed findings produced: the outward `v/->react` bridge, the wrapper
specification, the attach ctx/`:commands`/echo-cancellation surfaces, the
`:v.scroll/window` behavior, the two-plane P11 theming contract, ledger row 8,
the payload-map splice source, `v.test/find`, `advance-fx-clock!`, the
`{:id}`/seeding spec, the Spec 009 anchor binding, `v/inspect-boundary` and
its companion reads, demote-to-debug, and the plain-data payload rule); and
the operator's directives (the compiled-from-birth axiom; the data-orientation
exploration of §2.5); and a second fold with the sibling's consolidated
rewrite (codex-design.md, 973-line form) — adopted from it: the Freehand
working name, the named versioned compatibility profile and product-topology
framing, `v/presence` with mounting/unmounting overrides (resolving ledger
row 8 and R-B8), per-site event ownership (replacing this document's
cross-site value-keyed handler cache, whose sharing was incompatible with
`:once` state and per-site trace attribution), `spread-safe`/`spread` and
`render-fn`/`slot` into the common grammar (shrinking ledger row 6 to the
compiled-only state/host forms), the frame-retarget-beats-memo and
atomic-bundle precision laws, handle-cardinality-is-internal, the behavior
laws (config carries intents not callbacks; one behavior per node; opacity
under owned descendants; replay tolerance), `v/client-only` with declared
fallback, the Root Descriptor as a data-plane row, the checker's stable EDN
shape, the props-only-first read gradient, the shared descriptor shape, and
the namespace-based promotion naming that freed `v/defview` for the
declaration descriptor (itself since superseded by the `{:compiled true}`
option under the absorption ruling). Recorded then as positions, not adopted: the sharp
declared-views-are-never-callable convention, view-only `sub`, and
the keymap refusal — since ruled: D002 adopted the sharp convention, D005 the
view-only `sub`, and D007 confirmed the key-condition map on this document's
R-B5 grounds. The
pre-consolidation text, including the full editing history and the
adjudication-by-adjudication ledger, is preserved in
`fable-design-pre-reorg-backup.md`. Decision D003 (2026-07-22, ratified) replaced §2.4's public generic
instance-state quartet with the semantic-controller model — library-registered
records keyed by kind + explicit address, semantic transition events
(`edited`/`committed`/`cancelled`/`toggled`/`reconciled`/`closed`/
`settled` spelled throughout §4; D003's original `began` was later removed by
D016, under which begin is the first `edited`), the protocol-free annex
(disclosure), and
the pilots-first fence; the gallery's remaining raw-verb sites on
protocol-bearing state were respelled accordingly. Decision D001 (2026-07-22,
`decisions/`) ruled the name
and namespace: Freehand / `re-frame.freehand`, alias `v`, no second public
door — unlocking namespace ownership for absorbed code, diagnostic ids, and
the generated context sheet (reserved-keyword final spellings remain
downstream work). The operator's absorption ruling (2026-07-22: the useful code in re-frame.ui
folds into Freehand; re-frame.ui is then eventually deleted, gated on the
conformance contract) reshaped §3.4/§8 Q1 and the topology; the product spine
(`codex-design.md`) carries the same one-substrate absorption framing. A
comparative critique of the two
documents (operator-supplied) fixed their division of labor — product spine
vs argued dossier — and prompted this document's conformance contract,
deliberate-non-goals list, and settled-vs-contested surface ledger.

**The batch fold (2026-07-22).** The nineteen remaining decision dossiers
D002 and D004–D021 (`decisions/`) were ratified by the
operator wholesale and folded into this document in one pass. Each decision file
now states its operative ruling in the header; `codex-design.md` carries the
resulting target contract, and this dossier retains the supporting analysis. The
fold rewrote §2.1–§2.7,
§3.2–§3.6, §4, §5, §6, §7, §8, and Appendices A–B to carry the rulings as
settled design — the sharp declaration boundary, render-only `sub`,
explicit-only state addresses, the materializer, the closed callback roster,
the door's four pinned deltas, the closed compiled grammar with `v/markup`,
the unified buffered controller, registered behaviors with bounded commands,
the top-layer intrinsic pair, the two-plane theming contract, library-owned
control vocabulary, policy-gated schemas, the error-boundary/production-report
contract, the one evidence taxonomy, the two-lane release policy, and the
bounded outward bridge — and collapsed §8's ledger to CORE with every former
open question closed (the pre-ratification numbering: Q1 absorption → §3.4;
Q2(a) → D002; Q2(b) → D004; Q3 → D008, rejected; Q4 → D015; Q5 → D005;
Q6 → D012; Q7 → D013; Q8 → D017). Each fold was vision-checked against the
document's governing commitments (the two premises — compiled-from-birth and
one
reactive state system; §2.5's purpose hierarchy and data-orientation rule;
the absorption ruling and §6's deliberate non-goals; controlled-first and
trust-the-programmer) before being written in.

**Verified at source this session** (the claims the argument leans on):
reactive.cljc — batch law + guarantees (:61-154), slice memo (:185-205), `rf=`
stabilization (:53-59), `sub-read` split (:988-1051), the un-sidded-read guard
(:1006-1014), 8-step `commit*` (:3577+), cell struct (:538-628); file 3,952
lines. emit_cljs.cljc — hoisting (:6, :40, :515-525), per-slot comparators
(:854-876), stable callbacks (:8), sub-free elision (:944-947); 1,055 lines.
analyze.cljc — 3,490 lines. emit_jvm.cljc — 492 lines. spec/004-Views.md —
decision table (:399-421), narrow bare-fn law (:411-421), dynamic
classification + literal-only placeholders (:430-439), loop law (:441-446),
the door + its predicate (:448-471), `sub` sites + closed-macro soundness
(:478-498, :182-199), memo (:135-148), grammar + rejections (:150-180),
`local` (:574-612), slots (:954-1010), two-emitter parity (:56-78).
test.cljc — six names (:1-64), flush fixed point (:527-592). events.cljs —
the shipped door (:255-272). core.cljc — `subscribe-once` (:1053-1064),
`render-to-string` (:756-763). Corpus — todomvc views (:37-62, :64-127),
flight_booker (:197-230), cells (:44-50, :430-474), article_editor (:83-94,
:246-314 — the same-slug seed clobber is live at :304-314, bd rf2-y4mgw),
ui_editor.cljc (:63-132, the literal fieldsets :89-120), realworld_shared
page-size 10 (http.cljs:60-75), ssr/core.cljc `^{:rf/id}` (:221-251). re-com —
input_text.cljs (:78-113 twin atoms, :90 re-mint, same-value/flicker/done-fn),
dropdown.cljs (:287-296 rAF loop, :321-456 no unmount hook, :350-362 document
listener), popover.cljs (:335-352 parked measurement), v_table.cljs (1,431
lines, windowed), core.cljs (72 aliases). Beads — rf2-dpwel (CLOSED
2026-07-22, corrected: Chromium commit p95 1.4-1.7ms vs 0.2ms; ~1ms residual
tier-blind framework cost/keystroke; the prior ~5.2/1.0 attributed
substantially to measurement boundary; evidence-only), rf2-u53yy (9/10;
demand-gate governance; the action-vector reject fence), rf2-efxb1h (top-layer
ruling + portal graduation triggers), rf2-nzst23 (explicit caller-revision
NON-NEGOTIABLE + the ten-pin acceptance suite), rf2-y4mgw (causal-ownership
doctrine; the open same-slug merge omission). EP-0030 (:190-207 — W11
benchmarks future; UIx retained first-class). adapters/uix.cljs (:49-136 —
`use-subscribe` value semantics). Census greps reproduced exactly: 231 / 147 /
58 / 364(±2 method) / 106. External, labelled [knowledge]: Replicant's API
surface (no findings doc in this checkout), UIx's 1.6×/2.7× benchmark
(UIx-docs-reported), CSS anchor-positioning support, `@starting-style`.

— end —
