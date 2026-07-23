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
  spelling on both hosts. Frame preflight, authored identity, multi-root duplicate
  detection, failed-root isolation, total teardown and hydration are later slices; a
  bare declared view at the head is the whole grammar here, and `opts` is accepted and
  ignored. See [spec/004C-Roots-and-Mount.md](../../spec/004C-Roots-and-Mount.md#the-minimal-one-root-mount).
- **Example**:
  ```clojure
  (v/mount [app {:label "hello"}] (js/document.getElementById "app"))
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

## Related

- [spec/004-Views.md](../../spec/004-Views.md) — the normative contract
- [spec/API.md](../../spec/API.md) — the tiered var catalogue
- [`re-frame.ui`](re-frame.ui.md) — the compiled-view donor substrate
