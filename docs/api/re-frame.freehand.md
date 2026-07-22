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

The roster below is the whole door today. `v/mount`, `v/sub`, the host boundary and
the compiled tier are declared vacancies that land with their own EP-0036 slices.

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
  `(panel {})` raises rather than quietly returning `nil` the way a map-shaped
  descriptor would.

  A plain `defn` is the other half of the convention: helpers are direct-called with
  parentheses and run inside the boundary that called them, owning no subscriptions,
  no occurrence, no memoisation and no error containment of their own. Changing
  brackets to parentheses changes runtime **ownership**, not spelling.

  The option roster is **closed**: `:children-policy` — `:optional` (the default),
  `:none`, or `:required`. An unknown key, a reserved-but-unimplemented key
  (`{:compiled true}`), a missing body, more than one parameter, or a policy outside
  the roster all raise `:rf.error/defview-bad-args` at **macro-expansion** time. A
  view that deliberately renders nothing writes an explicit `nil` body.
- **Example**:
  ```clojure
  (v/defview cart-badge
    "The header's item count."
    {:children-policy :none}
    [_]
    [:span.badge "3"])
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
  ClojureScript.

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

## Related

- [spec/004-Views.md](../../spec/004-Views.md) — the normative contract
- [spec/API.md](../../spec/API.md) — the tiered var catalogue
- [`re-frame.ui`](re-frame.ui.md) — the compiled-view donor substrate
