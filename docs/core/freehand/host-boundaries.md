# Host boundaries

Most Freehand code never needs this page. Ordinary views stay in data: Hiccup,
event vectors, subscriptions, props.

Real apps still embed React widgets, canvas libraries, dialogs, and measurement.
Those are **host facts** — not app-db. Freehand gives a small set of **explicit
boundaries** so the host work is honest and the rest of the tree stays inspectable.

There is no general “neutral hooks / refs / effects” language in ordinary views.

> **Qualify the host edge. Keep application truth in re-frame.**

## Three host shapes

| Shape | Use when |
|---|---|
| **Qualified host leaf** | A React component that is mostly values in and callbacks out |
| **Registered behavior** | One DOM node owned by an imperative library (`connect` / `update` / `disconnect`, optional commands) |
| **UIx wrapper** | Hooks, context, portals, refs, effects, compound React protocols (`asChild`, Suspense, …) |

Bare React component heads are outside the Freehand tree — qualify them.

### Qualified React leaf

```clojure
(ns app.booking
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.host :as host]))

(def date-picker-host
  (host/component ::date-picker DatePicker))

(v/defview booking-date [_]
  (v/client-only
   {:fallback [:input {:type :date
                       :value (v/sub [:booking/date-iso])
                       :read-only true}]}
   [date-picker-host
    {:selected (->js-date (v/sub [:booking/date]))
     :onChange (v/event [date]
                 [:booking/date-picked (from-js-date date)])}]))
```

- Foreign callbacks use the [escape roster](events-and-handlers.md#the-escape-roster)
  — never a bare function at a declared foreign callback position.
- Props are open at the foreign head. Values pass through per the host conversion
  rules for that leaf.
- A React component does **not** gain SSR semantics merely because it can create
  browser DOM. Use `client-only` or an explicit JVM/SSR adapter.

### `v/client-only`

Marks a subtree that must not pretend to run on the server:

```clojure
(v/client-only
 {:fallback [:div.chart-placeholder "Chart loads in the browser"]}
 [chart-host {:spec spec}])
```

| Side | Behaviour |
|---|---|
| JVM / SSR | render the **fallback** (or inert policy) |
| Browser | mount the real host subtree after hydrate |
| Hydration | mismatch without a truthful fallback is an error |

Use for canvas charts, maps, and libraries with no honest server output.

### Registered behaviors

Use this for **DOM-owned** imperative libraries (Vega View, Mapbox, SpreadJS,
canvas editors) — not for “I wanted `useEffect`.”

```clojure
(host/defbehavior vega-view
  {:connect    connect-vega!
   :update     update-vega!
   :disconnect disconnect-vega!
   :ssr        :inert})   ; or explicit fallback policy

(v/defview chart [{:keys [spec data on-signal]}]
  [:div.chart
   {::v/behavior [vega-view
                  {:spec spec
                   :data data
                   :on-signal on-signal}]}])
```

| Rule | Meaning |
|---|---|
| `connect` | after **selected commit**; returns private memory |
| Timing | registry metadata: `:passive` (default) or `:layout` (before paint) — not use-site syntax |
| `update` | when public config changes by `rf=` |
| `disconnect` | exactly once per connection; tolerate reconnect/replay |
| Config | Clojure values + event intents only — no nodes, refs, prebuilt instances |
| One behavior per node | a second is illegal |
| Opaque descendants | if the library owns children, ordinary Hiccup children are **rejected** |
| Outward intents | dispatch through the **committed** frame; disconnected generation is **inert** |
| JVM | inert marker + public config, or explicit fallback if the behavior owns visible content |
| Evidence | connect/update/disconnect are **tool facts**, not domain events |

**`:layout` behaviors** must not flash wrong geometry. Prove measure-then-place,
state whether they track resize/scroll with bounded observers, and never run a
silent animation-frame loop. Total cleanup of listeners/observers is part of the
contract.

This is **not** a general `on-mount` / `on-unmount` callback API.

### Commands (one-shot host ops)

Optional finite command map on a behavior for export, print, focus-cell, etc.:

```clojure
(host/defbehavior workbook
  {:connect    connect-workbook!
   :update     update-workbook!
   :disconnect disconnect-workbook!
   :commands   {:export-xlsx export-xlsx!
                :focus-cell  focus-cell!}})

;; use-site — :instance only when commands need a target
[:div
 {::v/behavior
  [workbook {:instance [:invoice-sheet invoice-id]
             :document document
             :on-result [:invoice/workbook-result]}]}]

;; from a re-frame handler
{:fx [[:re-frame.freehand.host/command
       {:target [:invoice-sheet invoice-id]
        :op     :export-xlsx
        :args   {:filename "invoice.xlsx"}}]]}
```

| Rule | Meaning |
|---|---|
| `:instance` / `:target` | caller-supplied **value** identity in frame/root scope — not a DOM path or “last mounted” |
| Timing | only the **currently committed** connection; never queued for a future mount |
| Replay | never replayed after reconnect / trace replay |
| Multi-root | name the Root Descriptor id when targets could collide; Freehand does not pick |
| Return | no host handles in effects; async completion → generation-fenced intent |
| Steady state | still flows through config + `update`; commands are the narrow one-shot escape |

### UIx wrappers

When the integration needs React’s real protocol surface (hooks, context, portals,
ref merging, `asChild`, Suspense), write an **honest UIx wrapper**. Freehand does
not emulate those in neutral Hiccup. Keep the wrapper’s **public props and outward
intents** visible in the structural tree; mark the interior opaque.

## Outward React bridge (`v/->react`)

Some React libraries demand a **component value** as a prop (cell renderer, drag
overlay). That is how you put Freehand **inside someone else’s React DOM tree** —
not by mounting a second full app, and not by inventing a fourth host shape.

```clojure
{:cellRenderer (v/->react person-cell)}

(v/->react person-cell {:map-props cell-props})
```

Think of it as the reverse of a host leaf: the foreign tree is the parent, Freehand
is the island.

| Rule | Meaning |
|---|---|
| Input | only a declared Freehand **descriptor** |
| Cache | memoized React component by descriptor (+ stable mapper) identity |
| Default props | shallow copy of own enumerable props except reserved `frame` |
| `:map-props` | optional stable adapter: foreign props → one Freehand props map |
| Frame | reserved `frame` prop or ambient context — **never creates** a frame |
| Missing/dead frame | loud failure |
| Not included | deep conversion, key camelCase magic, callback guessing, ref forward, children conversion |
| JVM | typed host-operation error unless SSR adapter / `client-only` |

**Frame is required for Freehand to work inside that island.** Either the
surrounding Freehand/React context already provides one, or the foreign parent
passes a reserved `frame` prop pointing at a **live** frame you already created.
The bridge will not mint a new world for you.

Protocols needing hooks, refs, or compound cloning still need a real UIx wrapper.

## DOM top layer

Desired **open** state for platform popover/dialog — not presence, not portals:

```clojure
[:div {:popover :auto
       ::web/popover-open? open?
       :on-toggle
       (v/event [e]
         (conj on-open-change (= "open" (.-newState e))))}]

[:dialog {::web/modal-open? open?
          :on-cancel [:dialog/cancelled]}]
```

| Intrinsic | Constraint |
|---|---|
| `::web/popover-open?` | only with a valid `:popover` mode |
| `::web/modal-open?` | only on `<dialog>` → `showModal()` / `close()` |
| Non-modal dialog | platform `:open` attribute |

Browser dismissal never silently mutates app-db. Reconcile through intents.
Development reports a controlled top-layer node with no reconciliation handler.
**No neutral portal** in v1; React portals stay in wrappers. Timed exit after close
pairs with [Presence](presence.md). Positioning is CSS anchors or a behavior.

## Error boundaries

Render failures happen: nil assumptions, bad data, a foreign component throw,
malformed Hiccup. Freehand already guarantees that a **thrown render owns
nothing** — no half-published subscriptions or handlers. What the **user** sees
next and what **telemetry** may learn is the D019 contract below.

```clojure
[v/error-boundary
 {:reset-key route-revision
  :fallback  [broken-page {}]
  :on-error  [:telemetry/ui-render-failed]}
 [workspace-page {:workspace-id workspace-id}]]
```

| Prop | Role |
|---|---|
| **child region** | one region of UI protected by the boundary |
| **`:fallback`** | what to show instead — static structure, a declared view, or a pure `v/render-fn` of the safe summary |
| **`:reset-key`** | when this value changes, remount/retry the child (no imperative `reset!` ref) |
| **`:on-error`** | optional intent prefix; fires **once per failure generation** after the fallback commits |

There is no public “boundary handle” API. Recovery is data: change `:reset-key`
(often a route revision or a user “Try again” counter in app-db).

### What a boundary catches — and what it does not

| Failure | Boundary shows fallback? | Who owns reporting |
|---|---|---|
| Freehand child render throws | yes | boundary + frame error egress |
| Hiccup normalization / common validation throws | yes | same |
| Descendant React throw during render/lifecycle (where React boundaries apply) | yes (browser) | same Freehand boundary |
| **Fallback itself** throws | no at this boundary — propagates outward | parent boundary / frame |
| **re-frame event / sub / resource handler** throws | **no** | re-frame error path |
| **Async** timer / promise / DOM callback after the fact | **no** | browser / owning wrapper |
| Behavior `connect` / command after commit | not as render fallback by default | behavior diagnostics + frame egress |
| SSR transport outside view evaluation | no | server / root host |

**Critical:** a Freehand error boundary is a **render** safety net. It will not
save you from a bad event handler or a failed HTTP effect. Those stay causal-layer
errors. Do not wrap the whole app and assume “all failures become fallback UI.”

### Safe summary vs host detail (two channels)

**Application intent (`:on-error`).** When you supply `:on-error`, Freehand
dispatches **at most one** event per captured failure generation. The payload is
a **safe summary**: stable ids, view ids, phase, correlation facts, evidence
completeness — **not** raw props, full app-db, exception objects, or host nodes.
Use that event for a toast, a redacted product log, or serializable analytics.

**Frame error egress (production detail).** In parallel, the host may promote a
**private** record onto the existing Spec 009 / frame error path: safe summary
plus capped host/React stack for the configured observer. Redaction, transport,
and vendor integration live there.

Defaults:

- Freehand does **not** capture all of app-db or recent event payloads.  
- Snapshots are **opt-in** and allow-listed if you need them.  
- Development evidence is richer; production may be thinner — completeness fields
  must say so.  
- Epoch identity in production reporting stays careful (ordinal / correlation, not
  “dump the world”).  

### Lifecycle of a failure

1. Child render throws (or React reports a catchable render/lifecycle error).  
2. Candidate render publishes **nothing**.  
3. Boundary selects fallback UI.  
4. After fallback commits, optional `:on-error` fires **once** for this failure
   generation.  
5. User or app changes `:reset-key` → child remounts and retries.  
6. StrictMode / HMR must not spam the same generation as many reports.  

When reporting from production, the useful pair is often **where** (view id /
occurrence / correlation from the safe summary) and **what** (redacted snapshot
or release id you opted into). Do not rely on full event history in production
builds.

On the server, do not pretend a client recovery commit happened mid-stream. Use
the Root Descriptor’s **server error projection** — see [SSR](ssr.md).

## Focus, autofocus, and measurement (no neutral refs)

Freehand has **no** `v/ref`, `useEffect`, or “run this on mount” form in ordinary
views. Host ephemera stay at explicit boundaries.

| Need | Prefer first | Escalate to |
|---|---|---|
| Focus a field when it appears | native `:auto-focus true` on the input | — |
| Focus after a **semantic** open (dialog, rename) | top-layer / open state + CSS or browser dialog focus | small **behavior** that `.focus()`s on connect |
| Measure layout / position before paint | CSS anchors where possible | registered behavior with `:layout` timing |
| Scroll lock, trap, restore focus | top-layer / native dialog patterns | UIx wrapper if you need a full React focus library |
| Third-party “needs a ref callback” | qualified leaf / wrapper owns the ref | never a bare ref prop on a Freehand view as app state |

```clojure
;; Often enough — platform autofocus when the node mounts with the tree
[:input {:value (v/sub [:rename/draft])
         :auto-focus true
         :on-input [:rename/drafted ::v/value]}]

;; When you must call .focus() after connect — same registry shape as any behavior
(defn connect-focus! [el _config]
  (.focus el)
  nil)   ; private memory optional

(host/defbehavior focus-on-connect
  {:connect    connect-focus!
   :update     (fn [_el _config mem] mem)
   :disconnect (fn [_el _mem])
   :ssr        :inert})
;; Timing defaults to :passive; use :layout only for measure-then-place work
;; (registry metadata on the registration — not use-site callback syntax).

[:input {::v/behavior [focus-on-connect {}]
         :value (v/sub [:rename/draft])
         :on-input [:rename/drafted ::v/value]}]
```

Do not store DOM nodes in app-db. Do not invent mount-domain events for “I
focused.” If focus is product-visible (which field is active), that is a re-frame
fact driven by events — the DOM call is still host-side. Behaviors are **not** a
general on-mount API; prefer `:auto-focus` unless you need an imperative call.

## Choosing a shape

| Need | Shape |
|---|---|
| DatePicker value + callback | qualified leaf + `v/event` |
| Vega/Mapbox owns a DOM node | registered behavior |
| Radix / hooks / portals | UIx wrapper |
| Grid wants a React component prop | `v/->react` |
| Render failure UI | `v/error-boundary` (above) |
| Exit animation after close | presence (+ top-layer for open) |
| Framer / GSAP / other JS libs | host leaf or behavior (JS-library recipes) |
| Autofocus / measure / `.focus()` | native attr first; else behavior / wrapper (above) |
| Structure, spreads, theming | not host shapes — composition plane |

## If something feels wrong

| Symptom | Fix |
|---|---|
| Bare React component as a vector head | `host/component` or UIx wrapper |
| Instance / DOM node in app-db | keep private memory in the behavior; config is data only |
| Command “for later when it mounts” | commands hit the **live** connection only — no queue |
| Error boundary never resets | change `:reset-key` — no imperative reset handle |
| Telemetry spam on StrictMode | once-per-failure-generation for `:on-error` |