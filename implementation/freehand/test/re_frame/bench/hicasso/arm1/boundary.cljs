(ns re-frame.bench.hicasso.arm1.boundary
  "`h/boundary` — THE RUNTIME'S OWN ERROR BOUNDARY (HD-020(c),
  rf2-2rtt6.41).

  HD-020(c) rules that \"the runtime ships one internal class-based
  boundary exposed as `h/boundary` (`:fallback`/`:reset-key`/`:on-error`);
  it is the P1 witness's *real error boundary*\". validation.md's
  `:foreign/host-and-error-boundary` row names it by that description.
  This is it, and it is deliberately the smallest thing that satisfies
  the three keys.

      [boundary {:fallback  [:p.oops \"that did not work\"]
                 :reset-key attempt
                 :on-error  [:app/record-failure]}
       [risky-view {}]]

  ## Why a class, and what that buys the hook budget

  React catches a render-phase throw at a **class** component and nowhere
  else: `getDerivedStateFromError` is the render-phase marker React 19
  requires for the boundary to catch at all, and `componentDidCatch` is
  the commit-phase callback where a side effect is safe. There is no hook
  form of either, which is the whole reason HD-020(c) says \"class-based\"
  rather than leaving it to taste.

  **A class component calls no hooks, so this costs the ≤2-hook shell
  budget exactly nothing** — the boundary is not a boundary *shell*, it
  reads no subscription, mints no registration and takes no cell. That
  stayed true when the frame binding below arrived: `contextType` is a
  property of the component, not a hook call, so it is invisible at
  React's dispatcher. `arm1_lifecycle_dom_cljs_test` counts a healthy
  page there and `arm1_boundary_intent_dom_cljs_test` counts one in its
  ERROR state, rather than either asserting it here: a page wrapping a
  Hicasso boundary in one of these still reads two.

  ## The three keys

  | key | shape | meaning |
  |---|---|---|
  | `:fallback` | hiccup, or `(fn [error] hiccup)` | what renders instead of the children once something below has thrown |
  | `:reset-key` | any value, compared with `=` | a change clears the caught failure and re-mounts the children, so the retry is the CALLER's to schedule and never the boundary's to guess |
  | `:on-error` | an intent vector, or a plain function | fired **once per caught failure**. A vector is dispatched with the error appended, through the frame the boundary is mounted under; a function is called with the error |

  Nothing else. No error classification, no retry policy, no logging
  surface, no telemetry: each of those is an application's decision, and
  `:on-error` is the door it makes them behind.

  ## Once per failure, and why that needs NO flag — measured, not assumed

  `:on-error` fires from `componentDidCatch` and from nowhere else, so it
  fires exactly as often as React catches. Under StrictMode the render
  that threw runs **twice** and `componentDidCatch` is still called
  **once**, which is the whole of the once-per-failure guarantee:
  `arm1_lifecycle_dom_cljs_test/the-boundary-reports-once-under-strictmode`
  mounts the failing tree in StrictMode and reads one record.

  This started life with an instance flag gating the report, on the
  reasoning the freehand boundary uses for its generation counter
  (`re-frame.freehand.error-react`, whose `componentDidUpdate` promotes
  too and therefore genuinely needs one). Removing the flag changed no
  witness — **the mutation went green**, which is the definition of a
  line nothing observes — so it is gone. The freehand law is not being
  contradicted; it is being told apart, and the difference is that this
  boundary reports from one lifecycle rather than three.

  The frame reaches the class through `contextType` — the substrate's one
  internal React context, the same object `runtime/shell` reads with
  `useContext` — so `:on-error`'s intent lands in the frame the boundary
  was mounted under rather than in whatever happened to be ambient when
  the throw arrived.

  ## The fallback and the children are lowered HERE (rf2-uo9di)

  Both are hiccup **data**, written in the parent boundary's body — and
  both are walked by the codec inside **this class's own React render**,
  one render later, after that body's dynamic extent has unwound. So
  `intent/*dispatch*` was unbound at the moment the codec reached them,
  and before the binding below existed an intent at an event position on
  the fallback or on a native child raised
  `:rf.error/hicasso-intent-outside-boundary` at render, while an `h/fn`
  at one raised the same id at invocation.

  The fallback half is the sharp one, because it is the half the ruling
  above is sold on: `:fallback` sits beside `:reset-key` precisely so
  that \"the retry is the CALLER's to schedule\", and the control that
  schedules it is a button whose `:on-click` is an intent. So the table's
  own worked example could not be written — and a fallback that throws
  while rendering does not fail quietly in a corner, it takes the *next*
  boundary up, turning an application's error path into an
  application-wide failure.

  The repair is [[re-frame.bench.hicasso.arm1.presence]]'s, one component
  along, and **cheaper**: presence had to buy a `useContext` to find its
  frame and paid for it in HD-025's stated cost, while this class already
  has [[frame-of]] through `contextType`. So there is no new hook and no
  new accessor — only HD-020(a)'s rule applied where the lowering
  actually happens rather than where the hiccup was written, with
  `runtime/frame-dispatch`'s memoised frame-locked dispatch so a child
  here lowers *identically* to one written in the parent's body and
  nothing is allocated per render.

  **No frame in scope is not an error here.** The class reads nothing, so
  a boundary mounted outside a frame is legal until something below it
  writes an intent — at which point the existing loud error fires and
  names the intent, which is better attribution than a generic
  no-frame-context throw from the boundary. The binding is therefore
  unconditional and simply carries `nil` when there is no provider.

  ## What it does NOT catch, stated because a boundary that quietly does
  ## not catch is worse than none

  React error boundaries catch throws from **render, and from the
  lifecycle and effects of the tree below**. They do not catch a throw
  from an event handler, from a `setTimeout`, or from anything the
  browser calls outside React's own work loop — an intent handler that
  throws lands in the browser's error channel, not here. That is React's
  boundary, not this arm's, and the arm inherits it exactly."
  (:require [re-frame.adapter.context :as adapter-context]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.codec :as codec]
            [re-frame.bench.hicasso.front.intent :as intent]
            ["react" :as react]))

(defn- props-of
  "The ClojureScript props map the codec's boundary hand-off stashed under
  `rfProps` — the same crossing every `defview` product reads."
  [^js this]
  (or (unchecked-get (.-props this) "rfProps") {}))

(defn- frame-of
  "The frame this boundary is mounted under, read through `contextType`."
  [^js this]
  (adapter-context/context-value->current-frame (.-context this)))

(defn- report!
  "Fire `:on-error` for the failure React just caught. A vector is an
  intent and is dispatched, with the error appended, into the frame the
  boundary is mounted under; a function is called with the error.

  **Called from `componentDidCatch` and from nowhere else**, which is what
  makes it once per failure without a flag to make it so."
  [^js this error]
  (let [on-error (:on-error (props-of this))]
    (cond
      (vector? on-error) (when-some [frame-kw (frame-of this)]
                           (rt/dispatch! frame-kw (conj on-error error)))
      (fn? on-error)     (on-error error)
      :else              nil))
  nil)

(def boundary
  "`h/boundary` — a legal hiccup head, marked the way a `defview` product
  is, and a React **class** so React will hand it a render-phase throw
  from anything below.

  It is not a Hicasso *reactive* boundary: it reads no subscription,
  holds no cell and spends no hook."
  (let [ctor  (fn hicasso-boundary-ctor [props]
                (this-as ^js this
                  (.call ^js react/Component this props)
                  (set! (.-state this) #js {"error" nil})
                  (set! (.-resetKey this)
                        (:reset-key (or (unchecked-get props "rfProps") {})))
                  this))
        ;; Bound once and `^js`-tagged so the React lifecycle names below
        ;; are inferred externs — the same discipline
        ;; `re-frame.freehand.error-react` uses, for the same reason: a
        ;; `(.. ctor -prototype -X)` chain cannot infer them, and an
        ;; `:advanced` build that munged `componentDidCatch` would give a
        ;; boundary that silently never catches.
        proto ^js (js/Object.create (.-prototype ^js react/Component))]
    (set! (.-prototype ^js ctor) proto)
    (set! (.-constructor proto) ctor)
    (set! (.-displayName ^js ctor) "hicasso/boundary")
    (set! (.-contextType ^js ctor) adapter-context/frame-context)
    ;; React 19 requires the static marker for the boundary to catch at
    ;; all. It cannot reach the instance, so it only flips the render
    ;; marker; everything with a side effect is the commit's.
    (set! (.-getDerivedStateFromError ^js ctor) (fn [error] #js {"error" error}))
    (set! (.-componentDidCatch proto)
          (fn [error _info]
            (this-as ^js this (report! this error))))
    (set! (.-componentDidUpdate proto)
          (fn [_prev-props _prev-state _snapshot]
            (this-as ^js this
              (let [k (:reset-key (props-of this))]
                (when (not= k (.-resetKey this))
                  (set! (.-resetKey this) k)
                  (when (some? (unchecked-get (.-state this) "error"))
                    (.setState this #js {"error" nil})))))))
    (set! (.-render proto)
          (fn []
            (this-as ^js this
              (let [{:keys [fallback children]} (props-of this)
                    error    (unchecked-get (.-state this) "error")
                    frame-kw (frame-of this)]
                ;; THE LOWERING, inside the frame (rf2-uo9di). The fallback
                ;; and the children were both written in the parent's body
                ;; and are both walked HERE, so the ambient frame the codec's
                ;; intent lowering reads has to be re-established around this
                ;; call and nowhere else. ONE binding covers both branches
                ;; because both are the same crossing — including
                ;; `(fallback error)` itself, so hiccup the fallback function
                ;; mints is lowered under the same frame as hiccup it was
                ;; handed. `nil` when no provider is above the boundary: the
                ;; binding is unconditional so the branch does not exist, and
                ;; an intent written under a frameless boundary still lands on
                ;; the existing loud error naming the intent.
                (intent/with-frame frame-kw (when frame-kw (rt/frame-dispatch frame-kw))
                  (fn []
                    (if (some? error)
                      (codec/as-element (if (fn? fallback) (fallback error) fallback))
                      (codec/as-element (into [:<>] children)))))))))
    (codec/mark-boundary! ctor)))
