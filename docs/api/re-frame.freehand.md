# re-frame.freehand

`re-frame.freehand` is the **one public door** of the Freehand view substrate
(EP-0036; conventionally aliased `v`, artefact `day8/re-frame2-freehand`). A view is
declared with `v/defview`, mounted with square brackets, and never invoked.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.freehand :as v])

(v/defview panel
  {:children-policy :optional}
  [{:keys [title children]}]
  [:section.panel [:h2 title] children])

;; mounted, never called
[panel {:title "Details"} [:p "…"]]
```

**The namespace is a door, not a layer.** Everything here is either declared here
(the authoring macros) or re-exported from the namespace that owns it. The
descriptor **type**, its **constructor**, the vector-head classifier, the call
normalizer and the render body live in the internal `re-frame.freehand.descriptor`
and are not a supported surface. That is deliberate: a published constructor could
mint a value that passes `v/view?` and classifies as an internal boundary while
carrying no view-id, no source and no lowering — `v/defview` is the only way to
create a mounted boundary, and where the constructor lives is what enforces it.

The roster below is the whole door today. The compiled tier has landed — `{:compiled
true}` on a declaration selects it — and the host boundary is the remaining declared
vacancy, landing with its own EP-0036 slice.

## Declaration

### `defview`

- **Kind**: macro
- **Signature**:
  ```clojure
  (defview name docstring? opts? [props] body+)
  ```
- **Description**: the ONE declaration form, and the only way to create an internal
  mounted boundary. The parameter vector takes **exactly one argument, the props
  map** — there are no positional view arguments, so destructure the map instead.
  The var holds a descriptor value: `panel` is mounted as `[panel {…}]`, and
  `(panel {})` raises `:rf.error/view-called-directly` naming the three legal
  recoveries — mount it, inline it as a plain `defn`, or extract a shared `defn`
  helper — rather than quietly returning `nil` the way a map-shaped descriptor
  would, or answering with a raw host cast failure. The descriptor implements the
  host call protocol for exactly that reason, so `(ifn? panel)` is **true**; ask
  `v/view?` when the question is "is this a view?".

  A plain `defn` is the other half of the convention: helpers are direct-called with
  parentheses and run inside the boundary that called them, owning no subscriptions,
  no occurrence, no memoisation and no error containment of their own. Changing
  brackets to parentheses changes runtime **ownership**, not spelling.

  The option roster is **closed**, and every key is optional:

      :children-policy   :optional (the default), :none, or :required
      :compiled          false (the default), or true to select the compiled tier

  `{:compiled true}` is the **one-line promotion** — it selects the already-landed
  compiled tier's finite grammar for that one declaration, and nothing else moves:
  callers, structural output and the view's own tests are unchanged, because the
  compiled tier reuses the interpreted tier's descriptor, props contract and boundary
  node (see [spec/004D-Freehand-Compiled-Grammar.md](../../spec/004D-Freehand-Compiled-Grammar.md)).
  What does change is that the body must sit inside the finite language: a form the
  grammar does not admit is a build failure naming a recovery, never a silent
  demotion.

  An unknown key, a **reserved-but-unimplemented** key (the props-schema options —
  `{:props-schema …}` — which the schema slice owns and which are refused until it
  lands, the way `:compiled` was refused until the compiled tier landed), a missing
  body, more than one parameter, or a policy outside the roster all raise
  `:rf.error/defview-bad-args` at **macro-expansion** time. A view that deliberately
  renders nothing writes an explicit `nil` body.
- **Example**:
  ```clojure
  (v/defview cart-badge
    "The header's item count."
    {:children-policy :none}
    [_]
    [:span.badge "3"])
  ```

## Roots and mounting

### `mount`

- **Kind**: function (CLJS / browser only)
- **Signature**:
  ```clojure
  (mount root-form dom-node) → root
  (mount root-form dom-node opts) → root
  ```
- **Description**: mount the declared view at `root-form`'s head into `dom-node`, and
  return the live root handle. The minimal one-root spelling is a bare declared view
  at the head; its identity — the `:root-id` — is **derived** from the mounted view's
  registered id (a qualified keyword), so the single-root page authors nothing. The
  mount derives the minimal Root Descriptor (`{:rf.root/schema-version 1 :root-id
  :view-id :root-id-provenance}`) from the site alone.

  **Idempotent per root**: re-mounting the same root-id into the same container
  RE-RENDERS the existing host root rather than allocating a second one. That is the
  hot-reload path — a reload mints a fresh descriptor object for the redefined view,
  but the qualified id it keys on does not move, so the reload finds the root live and
  re-renders the new body without reseeding the host root. Body/generation churn is an
  internal fact of the descriptor, never part of the identity.

  Browser-only, because a DOM node is: the JVM renders the SAME `[view {…}]` root form
  structurally through the tree emitter, so mounting and structural rendering are one
  spelling on both hosts.

  `opts` is a **closed** map:

  | Opt | Tier | Meaning |
  |---|---|---|
  | `:root-id` | identity | the root-id, verbatim — a qualified keyword, or a vector of one plus scalar disambiguators |
  | `:disambiguator` | identity | a scalar appended to the DERIVED id, so one view can mount twice on one page with neither site authoring an id |
  | `:identifier-prefix` | identity | the React `identifierPrefix`. Default: `"rf2-" + root-id-slug + "-"`, injective over root-id |
  | `:frame` | preflight | a frame-id keyword SCOPES a frame something else owns; a `make-frame` opts map carrying `:id` ENSUREs one the root owns for its lifetime |
  | `:on-uncaught-error` `:on-caught-error` `:on-recoverable-error` | host | passed to the React root options |

  A root claims its **id**, its **container** and its effective **identifierPrefix**
  before it renders anything, so a collision on any of them fails loud
  (`:rf.error/duplicate-root-id`, `:rf.error/root-container-in-use`,
  `:rf.error/duplicate-identifier-prefix`) with the roots already on the page
  untouched. The `:frame` plan runs to completion before `createRoot`, so a body that
  reads a subscription on its first render finds a frame that is already seeded.
  See [spec/004C-Roots-and-Mount.md](../../spec/004C-Roots-and-Mount.md#the-minimal-one-root-mount).
- **Example**:
  ```clojure
  (v/mount [app {:label "hello"}] (js/document.getElementById "app"))

  ;; one view, two roots, two identities, two occurrences
  (v/mount [panel {:side :left}]  left  {:disambiguator :left})
  (v/mount [panel {:side :right}] right {:root-id :shop/right})

  ;; the root owns the frame's lifetime, seeded before React sees anything
  (v/mount [app {}] node {:frame {:id :shop/main :initial-events [[:shop/boot]]}})
  ```

### `hydrate-root`

- **Kind**: function (CLJS / browser only)
- **Signature**:
  ```clojure
  (hydrate-root dom-node root-form) → root
  (hydrate-root dom-node root-form opts) → root
  ```
- **Description**: adopt the server-rendered markup already in `dom-node` for the
  declared view at `root-form`'s head. Hydration **adopts** the server's DOM rather
  than replacing it, so the page the reader has been looking at since first paint
  becomes the live page.

  **Verification is React's own adoption.** A divergence React recovers from — a text
  mismatch, or a missing, extra or wrong-type element — is reported as
  `:rf.ssr/hydration-mismatch` carrying this root's id, and React replaces the
  divergent DOM with the client's truth. The framework reporter is composed **over**
  any host-supplied `:on-recoverable-error` (framework emit first, then delegate) and
  is bounded to the hydration adoption window, so a later recoverable error is not
  mislabelled a mismatch. An **attribute-only** divergence is outside that signal by
  React's own contract, which makes no guarantee to patch attribute mismatches.

  Identity comes from the server, so identity opts (`:root-id`, `:disambiguator`,
  `:identifier-prefix`) are **refused** here with `:rf.error/root-manifest-invalid`
  naming the conflicting key — a client that renders under its own `identifierPrefix`
  breaks `use-id` hydration outright. `:frame` and the host error callbacks are
  accepted exactly as at [`mount`](#mount).

  A container carrying nothing to adopt takes the **fallback**: the root mounts
  client-side instead — the client-only first load of a page whose server never
  rendered this root. See [spec/011-SSR.md](../../spec/011-SSR.md#hydration-on-the-freehand-paved-path).
- **Example**:
  ```clojure
  (v/hydrate-root (js/document.getElementById "app") [app {:label "hello"}])
  ```

### `unmount!`

- **Kind**: function (CLJS / browser only)
- **Signature**:
  ```clojure
  (unmount! root) → nil
  ```
- **Description**: tear a mounted root down completely. The registry entry goes — and
  with it the root-id, container and `identifierPrefix` claims — the React root
  unmounts, every ViewCell below it disconnects (releasing every dependency and
  retiring every published callback), and the root's reference to its frame is
  released. A frame the root **ENSUREd** is destroyed once no live root still
  references it; a frame it merely **scoped** is left alone, because the root borrowed
  it.

  **Guarded**, and a no-op rather than a throw when the guard fails: a root already
  unmounted, or superseded by a newer root claiming its id, has nothing left to
  release — and tearing down on its behalf would tear down the successor.
  See [spec/004C-Roots-and-Mount.md](../../spec/004C-Roots-and-Mount.md#total-teardown).
- **Example**:
  ```clojure
  (let [root (v/mount [app {}] node)]
    (v/unmount! root))
  ```

## Server render

### `render-static`

- **Kind**: macro (JVM / server only)
- **Signature**:
  ```clojure
  (render-static root-form) → HTML string
  ```
- **Description**: the pure `:server`-phase static render — Freehand's counterpart
  of React's `renderToStaticMarkup`. It compiles the **literal** root form to the
  versioned JVM structural tree and folds it to a static HTML string: **non-hydrating**,
  with no Root Manifest, no hydration payload, and no phase flip. It is the
  static-page path, not the SSR-then-hydrate path — `v/hydrate-root` +
  `re-frame.ssr/hydrate!` own that.

  **JVM / server only.** Author it in a `.clj` (or JVM-loaded `.cljc`) server-render
  namespace; a CLJS expansion is the compile error
  `:rf.ui.compile/ui-render-static-jvm-only`, because the client emitter targets React
  directly and there are no structural trees in the browser. Like the mount verbs it
  is a **macro**, so the root form is literal at the call site — a runtime-assembled
  vector is the same `:rf.ui.compile/runtime-root-form` compile error every root-form
  entry raises — and the root-id derives from the **one** mounted view exactly as
  `v/mount` derives it.

  **No silent elision** (Spec 004C §3, EP-0034 §2): a runtime-requiring capability —
  a subscription, a committed handler, an effect, a foreign head — anywhere in the
  root's server-reachable view closure is a loud
  `:rf.ui.compile/static-root-requires-runtime` build error with source coordinates,
  never a capability quietly dropped from the static output. `v/client-only` stays
  legal (only its capability-free fallback is server-reachable) and a deterministic
  `use-id` is exempt. To server-render a live subtree, mount it in the browser with
  `v/mount` / `v/hydrate-root`, or move it behind a `v/client-only` with a
  capability-free fallback.

  `re-frame.freehand` takes **no compile-time require** on `re-frame.ssr`: the render
  reaches the SSR serialiser through the late-resolution seam
  `re-frame.freehand.tree/emit-static-html`, so a render-static call in a namespace
  that requires only `re-frame.freehand` compiles and renders. A missing
  `day8/re-frame2-ssr` artefact at render time is the ruled
  `:rf.error/ssr-artefact-missing` naming the coordinate, never a raw host exception.
  See [spec/011-SSR.md](../../spec/011-SSR.md#the-server-render-on-the-freehand-paved-path).
- **Example**:
  ```clojure
  ;; a .clj / .cljc-on-JVM server-render namespace
  (:require [re-frame.freehand :as v]
            [re-frame.ssr])            ; require the artefact at app boot

  (v/render-static [footer {:year 2026}])
  ;; => "<footer>© 2026</footer>"
  ```

## Inspection

### `view?`

- **Kind**: function
- **Signature**:
  ```clojure
  (view? x) → boolean
  ```
- **Description**: true when `x` is a view declared with `v/defview` — the ONE value
  a Freehand runtime classifies as an internal view boundary. Total and
  host-neutral: the same value answers the same way on the JVM and in
  ClojureScript. `ifn?` is **not** a proxy for it — a declared view *is* `IFn`,
  purely so that calling one can explain itself.

### `describe`

- **Kind**: function
- **Signature**:
  ```clojure
  (describe view) → projection-map
  ```
- **Description**: the descriptor's public inspection / registry projection — a
  plain map, distinct from the runtime value:

  ```clojure
  {:re-frame.freehand/view true
   :view-id                :app.todo/todo-row
   :source                 {:ns … :file … :line …}
   :lowering               :interpreted   ; or :compiled
   :children-policy        :optional
   :props-schema           <schema>}      ; absent when none declared
  ```

  `:props-schema` is **absent** when no schema was declared — absence is reported as
  absence, never as `:any`. The render body and the host `mount` / structural `tree`
  entries are private and are not projected. The key roster is closed in both
  directions: an extra key is as much a defect as a missing one. This is inspection
  data, never a dispatch surface.

### `manifest`

- **Kind**: function
- **Signature**:
  ```clojure
  (manifest view) → manifest-map | nil
  ```
- **Description**: a **compiled** declaration's manifest — what its analysis makes
  statically knowable about it, as plain data — or `nil` for an interpreted one,
  which has no analysis to report.

  ```clojure
  (v/manifest people-list)
  ;; => {:view-id   :app.people/people-list
  ;;     :grammar   :re-frame.freehand/v1
  ;;     :crossings [{:view-id :re-frame.freehand/markup
  ;;                  :lowering :interpreted :path [1]}]}
  ```

  `:crossings` is the roster of internal-view boundaries the body mounts, one entry
  per lexical site, each marked with the mode it crosses into. A compiled view that
  mounts an interpreted child is the ordinary case — promotion is per declaration
  and not transitive — so the manifest says where the compiled tier stops rather
  than leaving a reader to assume it does not. `nil` for an interpreted declaration
  is the honest answer, not an omission. See
  [spec/004D-Freehand-Compiled-Grammar.md](../../spec/004D-Freehand-Compiled-Grammar.md#manifests-mark-the-crossing).

## Subscriptions

### `sub`

- **Kind**: function
- **Signature**:
  ```clojure
  (sub query) → value
  ```
- **Description**: the paved path's **render-only reactive read**. `v/sub` resolves
  the subscription `query` against the view's frame, returns its current **value**
  (not a reactive reference), and records a render-owned read. The read owns nothing
  on its own — no ref-count, no watch, no cache node — so a render the host abandons
  leaks nothing; the SELECTED commit is what turns the record into an owned
  dependency, published atomically with the rest of the boundary's bundle. A later
  change to the query's value then invalidates exactly this occurrence and recommits.

  It is legal **only during an active declared render**, and the capture is
  **same-thread** — including through an ordinary `defn` helper called from the body,
  because the render owns the read wherever the call lexically sits. A `v/sub` with
  no active render — a REPL, a timer, a `v/event` / `v/handler` callback, a foreign
  listener — fails loud with `:rf.error/view-read-outside-render` rather than probing
  a value nobody owns; a read conveyed to a child thread fails with
  `:rf.error/view-forked-capture`. Non-reactive callers use the frame-explicit
  one-shot `rf/subscribe-once`, deliberately a `re-frame.core` verb and not a
  Freehand one.

  The value is **stabilized**: an `rf=`-equal recompute returns the exact prior value
  object, so an equal value is not movement. The rule holds in both execution modes —
  the compiled tier proves a finite set of read sites, the interpreted tier records
  the reads a committed render actually made. The subscription law is
  [spec/006-ReactiveSubstrate.md](../../spec/006-ReactiveSubstrate.md).
- **Example**:
  ```clojure
  (v/defview basket-total [_]
    [:output (v/sub [:basket/total])])
  ```

## Callbacks and event intent

### `event`

- **Kind**: macro
- **Signature**:
  ```clojure
  (event [params…] body+)
  ```
- **Description**: the explicit conversion seam at a foreign boundary. The body runs
  synchronously with the live callback arguments and NAMES its outcome: an event
  vector dispatches (through the same materializer every other event form uses, so
  `::v/value` and friends still fill), `nil` dispatches nothing, and anything else
  is a loud diagnostic. It may not `v/sub`, use hooks, refs or effects. Its identity
  is stable per site.
- **Example**:
  ```clojure
  [date-picker {:on-change (v/event [date]
                             [:booking/departure-changed (iso-date date)])}]
  ```

### `handler`

- **Kind**: macro
- **Signature**:
  ```clojure
  (handler [params…] body+)
  ```
- **Description**: the explicit IMPERATIVE foreign callback — work that is not
  application intent and produces no event. The body's return is ignored. Like
  `v/event` it is stable per site, reads the exact committed body when invoked, and
  is retired with its site, so a listener that outlives its view is inert rather
  than firing into a successor.
- **Example**:
  ```clojure
  [canvas-host {:measure (v/handler [node] (measure! node))}]
  ```

### `render-fn`

- **Kind**: macro
- **Signature**:
  ```clojure
  (render-fn [params…] body+)
  ```
- **Description**: a PURE callback a foreign owner invokes during **its** render. It
  may return Freehand content, and it may NOT `v/sub`, dispatch, use hooks or touch
  refs — it can run during an uncommitted candidate render, which is why it is
  excluded from the committed-proxy scheme. Freehand makes no identity promise here;
  an API that treats callback identity as protocol data uses `v/raw-fn`.
- **Example**:
  ```clojure
  [virtual-list {:render-item (v/render-fn [{:keys [id]}]
                                [result-row {:id id}])}]
  ```

### `raw-fn`

- **Kind**: function
- **Signature**:
  ```clojure
  (raw-fn f) → f
  ```
- **Description**: the expert callback seam — hand a foreign API a function with
  EXACTLY the supplied identity, for the case where that identity is itself protocol
  data (a listener the library removes by identity, a memo key it compares). Every
  other roster form gets a site-owned stable proxy; this one deliberately does not,
  so re-render churn is the author's to manage.

### `projections`

- **Kind**: Var (set)
- **Signature**:
  ```clojure
  projections ;; => #{::v/value ::v/checked ::v/key}
  ```
- **Description**: the CLOSED reserved scalar-projection roster. These are the only
  markers a declarative event vector may carry, and the exact keys of the payload
  map a firing site supplies. A marker in a top-level argument position is replaced
  at firing time from the live callback payload; a marker nested inside another
  value is ordinary application data.

### `materialize-event`

- **Kind**: function
- **Signature**:
  ```clojure
  (materialize-event event payload) → event-vector
  ```
- **Description**: the ONE pure event materializer — replace the reserved projection
  markers in an event vector with the live scalars in a payload map, and return a
  plain vector ready for ordinary re-frame dispatch. Every path runs through exactly
  this function (a literal vector, a forwarded `conj`, an options map's `:event`, a
  `v/event` body; interpreted and compiled; production and test), which is why
  general `rf/dispatch` needs no payload arity. It is exposed so a structural test
  can supply a literal payload and assert the exact dispatched vector without a
  browser.
- **Example**:
  ```clojure
  (v/materialize-event [:account/email-edited ::v/value]
                       {::v/value "mike@example.com"})
  ;; => [:account/email-edited "mike@example.com"]
  ```

## Render slots

Parameterized content the **caller** supplies and the component renders where it
chooses. `v/render-fn` declares it, `v/slot` invokes it, and the pair is common
grammar: seq forms the compiled analyzer recognises, an ordinary macro and an
ordinary function call in an interpreted body.

### `slot`

- **Kind**: function
- **Signature**:
  ```clojure
  (slot render-fn-value arg…) → content
  ```
- **Description**: render the content the caller supplied, with the arguments the
  component supplies. The value is a `v/render-fn`, or `nil` — an absent slot
  renders nothing, so a component may offer content it does not require. The
  rendered output participates in the surrounding children exactly like any other
  child: there is no slot node and no wrapper in the tree.

  The arity is a **contract**, not a convention. A render-fn declares a fixed
  parameter vector and a slot passes exactly that many arguments, checked before the
  call — because the two hosts disagree about what a mismatch does, and neither
  JavaScript's silent `undefined` nor the JVM's raw `ArityException` is a
  diagnostic.

  One thing differs by mode, deliberately: an **interpreted** slot also accepts an
  ordinary pure function of the same arguments, because it has nothing to prove
  about what it invokes. A **compiled** one does not — the compiled tier's whole
  claim is that it can see what it lowers, so a bare fn is refused at build time,
  naming `v/render-fn`.

- **Example**:
  ```clojure
  (v/defview data-table [{:keys [rows row]}]
    [:tbody (for [r rows] [:tr {:key (:id r)} (v/slot row r)])])

  [data-table {:rows rows
               :row  (v/render-fn [r] [:td (:name r)])}]
  ```

## Props forwarding

Two forms, because forwarding a consumer's attribute map onto an element you own is
two different bargains and the grammar makes the author pick one at the site.

### `spread`

- **Kind**: function
- **Signature**:
  ```clojure
  (spread base) / (spread base overrides) → attribute map
  ```
- **Description**: forward a runtime attribute map onto an element, in its props
  position. `overrides` wins every collision. Every key is judged by the rule a
  literal attribute key is judged by — the same refusals, read off the same emitted
  slot — so a map assembled at run time cannot smuggle in a spelling the grammar
  refuses at a visible site. `:key` is refused outright: it is not an attribute, and
  it is literal at the element that carries it.

  This is the **visible-cost** forward. Whatever the map carries lands on the
  element, and the author said so at the site. A component library forwarding a
  consumer's attrs wants `v/spread-safe` instead.

- **Example**:
  ```clojure
  [:div.card (v/spread attrs {:class "is-open"})]
  ```

### `spread-safe`

- **Kind**: function
- **Signature**:
  ```clojure
  (spread-safe owned caller) → attribute map
  ```
- **Description**: forward a **consumer's** attribute map onto an element the
  component owns, bounded. `owned` is the component's own props map; `caller` the
  forwarded runtime one.

  The deny law runs in **every** build, not just dev: `:key`, `:ref`, `:value`,
  `:checked` and the component's own `on-*` handler families — both the bubble and
  capture phases — may not appear in `caller`, and an offender is a loud refusal
  rather than a silent drop. Alternate spellings do not route around it: a key is
  judged by the slot it is about to be written into, so a namespaced, string, symbol
  or already-camel spelling of a denied name is denied with it.

  Everything that survives folds **under** the owned props, with `:class` the one
  exception — the two class values compose, owned first, because a caller passing a
  utility class is adding to the element and not replacing what the component put
  there.

  That bound is what lets a component keep a promise about the element it renders. A
  controlled input stays controlled, an owned handler stays the one that fires, and
  the consumer still gets to pass `aria-*`, `data-*` and a class.

- **Example**:
  ```clojure
  (v/defview text-field [{:keys [value attrs]}]
    [:input (v/spread-safe {:value value :on-change [:field/changed]}
                           attrs)])
  ```

## Semantic controllers

A reusable view is **props-only by default** — value in, intent out — and needs
nothing in this section. A **semantic controller** is the exception a component
library earns when a control owns a protocol spanning several interactions: a field
that drafts and commits on blur or Enter, a dropdown holding an open flag beside an
active option, a typeahead holding a typed query beside a settled one. Making every
caller rebuild those state machines is poor library ergonomics; hiding them in host
state puts interaction facts where re-frame cannot see them.

A controller is **not a new kind of thing**. It is a `v/defview` plus ordinary
`reg-sub` and `reg-event` registrations — no registry, no reducer language, no second
state tier — and its state is ordinary frame data an epoch, a snapshot and a JVM test
all see. What the substrate contributes is the three verbs below, and they answer two
questions a library would otherwise answer twice and differently: **where** the record
lives, and **when** it is still the one the caller means.

The two are separable on purpose. Asking for the *key* is what makes a controller
writable; asking for the *revision* is what makes it buffered. A dropdown that holds
an open flag is writable and not buffered, so it takes the key and no generation.

### `controller-key`

- **Kind**: function
- **Signature**:
  ```clojure
  (controller-key kind props) → [kind address]
  ```
- **Description**: the key a **writable** controller's record lives under — the pair
  of the library's controller `kind` and the caller-supplied `:control` address
  carried by `props`.

  **Asking for the key is what makes a controller writable.** A props-only view never
  calls it and pays nothing, so reach for it only when a control genuinely owns a
  protocol spanning several interactions.

  The `kind` is the library's own keyword and is half the key, so a dropdown and a
  field addressed at the same domain identity read two records rather than each
  other's. The address is the **caller's**: immutable EDN naming the domain thing that
  owns the state, never a DOM id, a React key, or anything derived from render
  position — a derived anchor turns a sort, a view rename or a parent extraction into
  a silent state migration, while `[:invoice 42 :amount]` survives all of them.

  An absent `:control` is refused with `:rf.error/view-control-address-missing` rather
  than defaulted: every controller that skipped the address would otherwise share one
  record keyed by `nil`, which presents as one field editing another and has no local
  explanation. Two occurrences passed the **same** address share one record on purpose
  — that is how two views onto one draft are spelled — and it is not diagnosed.

  Where the record then lives is the library's choice, not the substrate's. Freehand
  fixes the identity model and no storage path, so there is no reserved app-db root to
  migrate off later.

- **Example**:
  ```clojure
  (v/defview buffered-field
    {:props [:map [:control :any] [:reset-key :any] [:value :string] …]}
    [props]
    (let [k (v/controller-key ::buffered-field props)]
      ;; k => [::buffered-field [:invoice 42 :amount]]
      …))
  ```

### `controller-revision`

- **Kind**: function
- **Signature**:
  ```clojure
  (controller-revision kind props) → revision
  ```
- **Description**: the **generation** a buffered controller is rendering under — the
  caller's `:reset-key`, taken from `props`. It is any EDN the caller likes (a
  counter, a timestamp, the id of the decision that set the baseline), because the
  fence only ever asks whether two of them are equal.

  What it must **not** be is the value. The case this exists for is a caller
  *rejecting* an edit by reasserting what it already had: the accepted value is
  `"10"`, the user drafts `"bad"`, the caller refuses and stands by `"10"`. The value
  before that decision and the value after it are identical, so value-equality sees
  nothing happen and the refused draft survives on screen — the bug every hand-rolled
  buffered input eventually acquires.

  **Required.** An absent `:reset-key` is refused with
  `:rf.error/view-control-reset-revision-missing`. Optional would be worse than
  absent: a control with no generation buffers perfectly well right up to the first
  rejection, so omission ships a defect development never reproduces. A caller that
  genuinely never resets says so with a stable literal — `:reset-key 0` reads as *do
  not externally reset an active edit*, which is a statement rather than a silence.

- **Example**:
  ```clojure
  (let [k (v/controller-key ::buffered-field props)
        g (v/controller-revision ::buffered-field props)]
    …)
  ```

### `controller-current?`

- **Kind**: function
- **Signature**:
  ```clojure
  (controller-current? stamped revision) → boolean
  ```
- **Description**: the **generation fence**. Is work `stamped` with one generation
  still current against `revision`, the caller's generation now?

  One predicate, asked at **both** of a buffered controller's boundaries — the read,
  where a draft is displayed only while it is current, and the write, where only a
  current record may produce the caller's intent. They are the same question through
  the same function, because a control whose display and whose commit disagreed about
  which generation is live would commit something the user could not see.

  **Total, and safe in the missing direction.** An absent stamp is not current,
  whatever `revision` is — work that cannot prove its currency does not have it. That
  is the half a hand-rolled `(= a b)` gets wrong, since two `nil`s compare equal and
  an unstamped record would read as current. It is also what makes a draft written
  from a superseded render *born stale*: it carries the generation its own render
  displayed, so if the caller has moved on it is neither shown nor committed.

  Comparison is the corpus's own equality, so a revision may be a collection without a
  controller having to remember that. The function is a pure comparison over two
  generations and reads no record shape, so the record stays the library's.

  A superseded draft is **invisible, not erased** — which is why a reset costs no
  render-time dispatch, no render-phase mutation and no remount.

- **Example**:
  ```clojure
  ;; the READ — display the draft only while it is current
  (rf/reg-sub :acme.buffered/text
    (fn [db [_ k revision baseline]]
      (let [record (get-in db [records-root k])]
        (if (v/controller-current? (:reset-key record) revision)
          (:draft record)
          baseline))))

  ;; the WRITE — only a current record may produce the caller's intent
  (when (v/controller-current? (:reset-key record) revision)
    {:fx [[:dispatch (conj on-commit (:draft record))]]})
  ```

## Presence

### `presence`

- **Kind**: function
- **Signature**:
  ```clojure
  (presence {:timeout-ms n} & keyed-children)
  ```
- **Description**: declarative enter/exit retention over keyed children —
  deliberately bounded, and not an animation system. Every keyed child passes
  `:mounting → :present`; when a key leaves the incoming set its child is RETAINED
  `:unmounting` until the mandatory `:timeout-ms` fires, then removal is terminal
  and exactly-once. Re-entry before the timeout interrupts the exit and returns the
  child to `:present`. Children hold first-appearance order; an incoming reorder is
  ignored. One contract, both modes: a seq form to the compiler, an ordinary
  function call to the interpreter. DOM-agnostic — the boundary stamps nothing; a
  presence-aware child owns its own exit styling and accessibility by reading
  `v/presence-phase`. See
  [spec/004-Views.md](../../spec/004-Views.md#presence).
- **Example**:
  ```clojure
  (v/presence {:timeout-ms 300}
    (for [t toasts]
      [toast-card {:key (:id t) :toast t}]))
  ```

### `presence-phase`

- **Kind**: function
- **Signature**:
  ```clojure
  (presence-phase) → :mounting | :present | :unmounting
  ```
- **Description**: the single presence-phase read — the current phase inside a
  `v/presence` boundary, and `:present` outside one, so a presence-aware child
  stays reusable anywhere. A render-time read (a React context read on
  ClojureScript); the JVM structural render always yields `:present`. A child reads
  it to stamp its exit class and accessibility (`inert` / `aria-hidden`) while
  `:unmounting`.
- **Example**:
  ```clojure
  (v/defview toast-card [{:keys [toast]}]
    (let [exiting? (= :unmounting (v/presence-phase))]
      [:div.toast {:class       (when exiting? "toast--exit")
                   :aria-hidden (when exiting? true)}
       (:message toast)]))
  ```

## Framework views

### `route-link`

- **Kind**: Var (a declared view descriptor)
- **Signature**:
  ```clojure
  [v/route-link {:to :route-id …html-attrs} & children]
  ```
- **Description**: a navigation anchor — and, deliberately, an ORDINARY
  declaration. A framework-supplied view is not a privileged one: `route-link` is
  declared with the same `v/defview` an application uses, holds the same
  descriptor, and takes the same one props map, so there is no route-link
  intrinsic to teach or to keep in step with the paved path.

  It renders a real `<a href=…>` carrying the route's strategy-encoded href, so
  copy-link, open-in-new-tab, keyboard activation and no-JavaScript navigation all
  work; on a plain in-app left click it dispatches the routing cascade to the frame
  that rendered it. `:to` is required; `:params` / `:query` / `:fragment` feed both
  the href and the payload; every other key (`:class`, `:target`, `:download`,
  `:aria-label`, `:on-click`, …) passes through to the `<a>`. A caller `:on-click`
  runs first and may veto, and modifier / middle clicks and native anchors defer to
  the browser — that deferral is the whole reason to use this view rather than
  hand-rolling one. Without `day8/re-frame2-routing` on the classpath it fails loud
  with `:rf.error/routing-artefact-missing` rather than emitting a dead link.

  Routing owns the law: see [spec/012-Routing.md](../../spec/012-Routing.md).
- **Example**:
  ```clojure
  [v/route-link {:to :article :params {:slug slug} :class "title"} title]
  ```

### `markup`

- **Kind**: Var (a declared view descriptor)
- **Signature**:
  ```clojure
  [v/markup {:value hiccup}]
  ```
- **Description**: the declared boundary that markup already held as a **value**
  crosses at — and, like `route-link`, an ORDINARY interpreted declaration. The
  compiled tier treats a template as a finite grammar and cannot lower a runtime
  value, so a compiled body that hands a value to a child position names `v/markup`
  as its recovery:

  ```clojure
  (v/defview editor
    {:compiled true}
    [{:keys [error hint]}]
    [:section
     [v/markup {:value (field-help error hint)}]])
  ```

  There is no `v/interp` and no automatic dynamic-markup walk. Nothing in the
  compiled tier knows its name: mounting it is mounting a statically named
  interpreted child, so the compiled parent sees one descriptor boundary, the child
  owns the walk and its own occurrence, and the parent's manifest marks the crossing
  `:interpreted` rather than quietly claiming the subtree. `:value` is anything a
  view body may return — a Hiccup vector, a seq of them, text, a number, or nothing;
  it accepts no children (`:children-policy :none`), because the value *is* the
  content. See [spec/004-Views.md](../../spec/004-Views.md#the-vmarkup-boundary).

### `error-boundary`

- **Kind**: Var (a declared view descriptor)
- **Signature**:
  ```clojure
  [v/error-boundary {:fallback … :reset-key … :on-error …} child]
  ```
- **Description**: the framework's resettable render-failure boundary. Like
  `route-link` and `markup` it is a declared descriptor mounted in a vector head,
  never called; what it does not share with an ordinary `v/defview` is a render
  body, because a boundary *contains* its child rather than producing markup.

  It catches render-class failures below it — a Freehand child body throwing,
  Hiccup normalization or common prop/event validation throwing, and (in the
  browser) a descendant foreign component throwing where React boundaries apply.
  It does **not** catch event-handler, asynchronous, or re-frame handler/sub
  failures: those keep their existing typed owners. A caught failure shows
  `:fallback` and publishes nothing from the failed render. `:on-error`, when
  present, is one event prefix; the framework appends a bounded **safe summary**
  (a stable diagnostic id, the failing view id, phase, fingerprint and evidence —
  never the exception, props, app-db, or event payloads) and dispatches it exactly
  once per failure generation, after the fallback commits. Changing `:reset-key`
  by `rf=` clears the captured failure and re-mounts the child; there is no
  boundary ref and no imperative reset handle.

  Production reporting rides a second, private channel: at most one record per
  failure generation is promoted onto re-frame's always-on error axis and the
  frame-owned observability sink, carrying the opaque exception and a capped host
  stack. That record carries no automatic app-db or event-history capture.

  The option roster is CLOSED — `:fallback` (required), `:reset-key`, `:on-error`.
  Anything else raises `:rf.error/error-boundary-bad-args`. See
  [spec/004-Views.md](../../spec/004-Views.md#error-boundaries-and-error-egress).
- **Example**:
  ```clojure
  [v/error-boundary
   {:reset-key route-revision
    :fallback  [broken-page {}]
    :on-error  [:telemetry/ui-render-failed]}
   [workspace-page {:workspace-id workspace-id}]]
  ```

## Registered behaviors

### `defbehavior`

- **Kind**: macro
- **Signature**:
  ```clojure
  (defbehavior name docstring?
    {:timing … :connect … :update … :disconnect … :commands … :opaque …})
  ```
- **Description**: register an imperative host **behavior** — the one sanctioned
  way to own DOM or opaque host state, bounded to a single node. The var it binds
  holds the **registered id** (the qualified keyword `:my.ns/name`), not the
  implementation. That split is what keeps a use site data: the tree records an
  id, the registry holds the code, and nothing serializable ever carries a
  function.

  The definition roster is CLOSED, and `:timing` is closed with it —
  `:layout` runs before the browser paints, which is the only honest home for
  measure-then-place work, and `:passive` (the default) runs after paint. There
  is no third moment: the set of moments at which host state may move is part of
  the contract rather than an implementation detail.

  `:connect` runs once, at the commit that mounts the node, and its return value
  becomes the connection's private memory. `:update` runs only when the committed
  `:config` moves by `rf=`, receiving `:prev-config` alongside — a re-render that
  changes nothing touches no host state. `:disconnect` runs exactly once per
  committed connection, after the connection is released, so its context is
  inert. `:commands` is a finite roster of named operations reached through the
  `:re-frame.freehand.host/command` effect. `:opaque` declares that the behavior
  owns the node's descendants, which makes Freehand children on that node an
  error rather than content the host would silently overwrite.

  Every entry takes one context map: `:node`, `:config`, `:memory`, `:behavior`,
  `:target`, `:generation`, and `:dispatch` — a generation-fenced outward
  dispatch into the frame the connection committed under. There is no frame query
  function, so a host can never read application state at a moment nobody chose.
  See
  [spec/004-Views.md](../../spec/004-Views.md#registered-behaviors-and-commands).
- **Example**:
  ```clojure
  (v/defbehavior autosize
    "Grow the textarea to fit its content."
    {:timing     :layout
     :connect    (fn [{:keys [node config]}] (fit! node config))
     :update     (fn [{:keys [node config]}] (fit! node config))
     :disconnect (fn [{:keys [memory]}] (some-> memory .disconnect))
     :commands   {:refit (fn [{:keys [node config]}] (fit! node config))}})
  ```

### `behavior`

- **Kind**: Var (a declared view descriptor)
- **Signature**:
  ```clojure
  [v/behavior {:use behavior-id :target … :config …} node]
  ```
- **Description**: attach a registered behavior to one node. A declared boundary
  mounted in a vector head and never called, like `error-boundary` and `markup`.

  The option roster is CLOSED. `:use` is required and names the registered
  behavior. `:target` is the caller-authored semantic id a command addresses — it
  is derived from nothing (not render position, not a key path, not the DOM), so
  a sort, a rename, a parent extraction or a virtualized remount does not move
  it, and it must be unique among live connections. `:config` is the public
  configuration and is **data at every depth**: a callback, a node, a ref or a
  preconstructed host instance is refused on both hosts, because a configuration
  the structural tree cannot record is a use site a test and a tool cannot read.

  The child is exactly **one element** — a behavior owns one node, so a declared
  view, a fragment, a presence boundary or text is refused rather than guessed
  at. The behavior addresses the node the host hands it and has no way to reach
  any other: there is no selector, no document query, and no ref an application
  can hold.

  Connection is commit-only. The lifecycle rides a ref and an effect, both of
  which React runs only for a render it selected, so a candidate the host
  abandons performs no host work at all. Teardown is total: after the last
  unmount the substrate holds no connection record, no target claim, no node and
  no memory.

  On the JVM this is an inert marker — the boundary node records `:use`,
  `:target` and `:config` with the decorated element as its child, nothing
  connects, and a command is refused with the channel's own diagnostic. See
  [spec/004-Views.md](../../spec/004-Views.md#registered-behaviors-and-commands).
- **Example**:
  ```clojure
  [v/behavior {:use    autosize
               :target :composer/body
               :config {:max-rows 8}}
   [:textarea.composer {:value draft :on-input [:composer/typed ::v/value]}]]
  ```

  A one-shot operation on the live connection is an ordinary effect:

  ```clojure
  (rf/reg-event :composer/refit-requested
    (fn [_ _]
      {:fx [[:re-frame.freehand.host/command
             {:target :composer/body :op :refit}]]}))
  ```

## Related

- [spec/004-Views.md](../../spec/004-Views.md) — the normative contract
- [spec/API.md](../../spec/API.md) — the tiered var catalogue
- [`re-frame.ui`](re-frame.ui.md) — the compiled-view donor substrate
