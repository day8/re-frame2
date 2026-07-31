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
  reads no subscription, mints no registration and takes no cell.
  `arm1_lifecycle_dom_cljs_test` counts that at React's own dispatcher
  rather than asserting it here: a page wrapping a Hicasso boundary in
  one of these still reads two.

  ## The three keys

  | key | shape | meaning |
  |---|---|---|
  | `:fallback` | hiccup, or `(fn [error] hiccup)` | what renders instead of the children once something below has thrown |
  | `:reset-key` | any value, compared with `=` | a change clears the caught failure and re-mounts the children, so the retry is the CALLER's to schedule and never the boundary's to guess |
  | `:on-error` | an intent vector, or a plain function | fired **once per caught failure**. A vector is dispatched with the error appended, through the frame the boundary is mounted under; a function is called with the error |

  Nothing else. No error classification, no retry policy, no logging
  surface, no telemetry: each of those is an application's decision, and
  `:on-error` is the door it makes them behind.

  ## Once per failure, and why that needs a flag

  React's commit runs `componentDidCatch` as an update-queue callback, and
  under StrictMode the render that threw runs twice. `:on-error` must
  still fire once, so the report is gated on an instance flag cleared only
  by a `:reset-key` change. The freehand boundary
  (`re-frame.freehand.error-react`) reaches the same conclusion through a
  generation counter in a shared law; this arm needs one boolean, and
  says so rather than importing the law.

  The frame reaches the class through `contextType` — the substrate's one
  internal React context, the same object `runtime/shell` reads with
  `useContext` — so `:on-error`'s intent lands in the frame the boundary
  was mounted under rather than in whatever happened to be ambient when
  the throw arrived.

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
  "Fire `:on-error` once for the failure now held. A vector is an intent
  and is dispatched with the error appended; a function is called with it.
  The flag is what makes StrictMode's second render, and every later
  commit of the fallback, a no-op."
  [^js this error]
  (when-not (.-reported this)
    (set! (.-reported this) true)
    (let [on-error (:on-error (props-of this))]
      (cond
        (vector? on-error) (when-some [frame-kw (frame-of this)]
                             (rt/dispatch! frame-kw (conj on-error error)))
        (fn? on-error)     (on-error error)
        :else              nil)))
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
                  (set! (.-reported this) false)
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
                  (set! (.-reported this) false)
                  ;; Unconditional: a reset that only fired while a
                  ;; failure was held would leave the flag armed after a
                  ;; clean pass, and the NEXT failure would report
                  ;; nothing.
                  (when (some? (unchecked-get (.-state this) "error"))
                    (.setState this #js {"error" nil})))))))
    (set! (.-render proto)
          (fn []
            (this-as ^js this
              (let [{:keys [fallback children]} (props-of this)
                    error (unchecked-get (.-state this) "error")]
                (if (some? error)
                  (codec/as-element (if (fn? fallback) (fallback error) fallback))
                  (codec/as-element (into [:<>] children)))))))
    (codec/mark-boundary! ctor)))
