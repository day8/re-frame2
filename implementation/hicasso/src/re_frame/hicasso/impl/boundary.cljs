(ns re-frame.hicasso.impl.boundary
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
  page there and `boundary_intent_dom_cljs_test` counts one in its
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

  ## \"Nothing else\" is now REFUSED rather than merely stated (rf2-czlb)

  The three keys above, plus the `:children` the codec writes from the
  trailing forms, are the whole of [[prop-roster]], and [[check-props!]]
  refuses anything outside it. Until it did, `{:on-errors …}` minted,
  crossed `rfProps` intact, and was consulted by nothing — and so did
  `{:resetKey …}` and `{:fall-back …}`. `mint-host!` had refused the
  same class at a `defhost` declaration since rf2-hic-007, with its own
  message giving the reason — *\"reading past an option it does not know
  is how a policy comes to be set and never applied\"* — and this was the
  one declaration surface in the package without the guard. It was also
  the surface whose silent failure is worst: an error boundary that
  reports nothing, wearing an `:on-error` that says it does.

  A **shape** check on `:on-error` rides the same guard, because the
  silent trap has a second door. [[report!]] is `(cond (vector? …) …
  (fn? …) … :else nil)`, so `{:on-error :app/boundary-failed}` — a bare
  intent keyword, which is what somebody writes who has not yet noticed
  intents are vectors here — swallowed every caught error and returned
  nil.

  ### Why the shape is refused HERE and not in `report!`

  The obvious place for a shape check is the arm that consumes the
  shape, and it is the wrong one. [[report!]] runs from
  `componentDidCatch`, which is to say **only after something below has
  already thrown**: a refusal raised there replaces the application's
  real error with a complaint about the declaration, escapes to the next
  boundary up, and takes the error path down with it — precisely the
  failure the `:fallback` lowering above exists to prevent, arrived at
  from the other side. React runs this class's `render` before it can
  ever run its `componentDidCatch`, so refusing here is strictly
  earlier: a bad `:on-error` is reported on the first paint, in the
  ordinary course of loading the page, rather than on the first failure.
  By the time [[report!]] runs, `:on-error` is nil, a vector or a
  function, and its last arm means *no `:on-error` was declared* — which
  is what it now says.

  ### Why a throw from `render` is acceptable when one from `report!` is not

  Both escape to the boundary above. The difference is that this one
  **cannot lie dormant**: the props map is a literal written one body up,
  so the refusal fires on the very first render, deterministically, on
  every run — it is a declaration fault caught at the first moment the
  declaration is reachable, not a conditional error path that waits for
  bad luck. The codec already raises
  `:rf.error/hicasso-intent-outside-boundary` out of this same render for
  the same reason, so the precedent is inside this class rather than
  beside it.

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
  internal React context, the same object `collector/shell` reads with
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

  The repair is [[re-frame.hicasso.impl.presence-react]]'s, one component
  along, and **cheaper**: presence had to buy a `useContext` to find its
  frame and paid for it in HD-025's stated cost, while this class already
  has [[frame-of]] through `contextType`. So there is no new hook and no
  new accessor — only HD-020(a)'s rule applied where the lowering
  actually happens rather than where the hiccup was written, with
  `collector/frame-dispatch`'s memoised frame-locked dispatch so a child
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
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.error :refer [fail!]]
            [re-frame.hicasso.impl.intent :as intent]
            ["react" :as react]))

(defn- props-of
  "The ClojureScript props map the codec's boundary hand-off stashed under
  `rfProps` — the same crossing every `defview` product reads."
  [^js this]
  (or (unchecked-get (.-props this) "rfProps") {}))

(def ^:private prop-roster
  "**The closed set of keys a boundary's props map may carry.** Three are
  the author's — the table in the namespace docstring — and `:children`
  is the codec's, written by `impl.codec/boundary-element` from the
  trailing forms.

  Four rather than three, and stated as data rather than as a `case`,
  because the refusal has to name the roster it checked: the author who
  wrote `:on-errors` is told what the four are, from the same value the
  guard read. `:key` never reaches here (`boundary-element` strips it
  onto the React props) and neither does `:&` (`merge-caller` folds it
  before the hand-off), so both are absent by construction rather than
  by exemption."
  #{:on-error :reset-key :fallback :children})

(defn- check-props!
  "Refuse a props map outside [[prop-roster]], and an `:on-error` outside
  the two shapes that can fire. Returns `props`, so the one call site
  reads as the read it already was.

  The doseq is `mint-host!`'s, deliberately — this is the same refusal
  class one surface over, and the comparator whose absence here was the
  defect. Cost is one `contains?` per key of a map that legally holds
  four, on a component that renders when its own props or its caught
  error change; the walk of `:children` in the same render is orders of
  magnitude more work."
  [props]
  (doseq [k (keys props)]
    (when-not (contains? prop-roster k)
      (fail! :rf.error/hicasso-boundary-unknown-prop
             're-frame.hicasso.impl.boundary/boundary
             (str "h/boundary was given " (pr-str k) ", which is not one of "
                  "its props. A boundary carries :fallback, :reset-key and "
                  ":on-error, and its :children are the trailing forms rather "
                  "than a key you write. A misspelling here is not an ignored "
                  "option: it is an error boundary that reports nothing, "
                  "wearing a declaration that says it does.")
             :write-fallback-reset-key-or-on-error
             {:prop k :props prop-roster})))
  (let [on-error (:on-error props)]
    (when-not (or (nil? on-error) (vector? on-error) (fn? on-error))
      (fail! :rf.error/hicasso-boundary-bad-on-error
             're-frame.hicasso.impl.boundary/boundary
             (str "h/boundary's :on-error was " (pr-str on-error)
                  ", which nothing can fire. It takes an INTENT VECTOR, "
                  "dispatched with the error appended into the frame the "
                  "boundary is mounted under, or a FUNCTION called with the "
                  "error. A bare keyword is not an intent here — wrap it: "
                  "[:app/failed].")
             :hand-an-intent-vector-or-a-one-argument-function
             {:value on-error})))
  props)

(defn- frame-of
  "The frame this boundary is mounted under, read through `contextType`."
  [^js this]
  (adapter-context/context-value->current-frame (.-context this)))

(defn- report!
  "Fire `:on-error` for the failure React just caught. A vector is an
  intent and is dispatched, with the error appended, into the frame the
  boundary is mounted under; a function is called with the error.

  **Called from `componentDidCatch` and from nowhere else**, which is what
  makes it once per failure without a flag to make it so.

  The last arm means **no `:on-error` was declared**, and nothing else:
  [[check-props!]] ran in this instance's own render — which React
  always runs before it can run `componentDidCatch` — so every other
  shape was refused there, where the refusal does not cost the
  application its error path (rf2-czlb, and the namespace docstring's
  argument for the placement)."
  [^js this error]
  (let [on-error (:on-error (props-of this))]
    (cond
      (vector? on-error) (when-some [frame-kw (frame-of this)]
                           (collector/dispatch! frame-kw (conj on-error error)))
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
              ;; THE ROSTER AND THE SHAPE, once per render and before
              ;; anything is read off the map (rf2-czlb). Here rather
              ;; than in `report!` because React runs this before it can
              ;; run `componentDidCatch`, so a bad declaration is
              ;; refused on the first paint instead of being discovered
              ;; by — and then destroying — the first real failure.
              (let [{:keys [fallback children]} (check-props! (props-of this))
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
                (intent/with-frame frame-kw (when frame-kw (collector/frame-dispatch frame-kw))
                  (fn []
                    (if (some? error)
                      (codec/as-element (if (fn? fallback) (fallback error) fallback))
                      (codec/as-element (into [:<>] children)))))))))
    (codec/mark-boundary! ctor)))
