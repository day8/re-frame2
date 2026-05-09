(ns re-frame.views
  "Views — reg-view, frame-provider. Per Spec 004.

  CLJS-only (Reagent-side). The pure-data render-tree contract lives in
  Spec 011 (SSR); this namespace ties view registration into the Reagent
  substrate.

  Form-1 (a render fn) is the canonical view shape. Form-2/3 are
  supported via Reagent's native handling.

  reg-view auto-defs the local Var (per Spec 004 §reg-view defs the Var
  by default); the Var is the canonical call-site reference. For
  late-binding by id (e.g. across module boundaries, runtime-computed
  ids, or hot-reload semantics), call `(re-frame.core/view :id)`
  to obtain the wrapped fn and use `[(rf/view :id) args]` as the
  hiccup head."
  (:require [reagent.core   :as r]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.performance :as performance :include-macros true]
            [re-frame.registrar :as registrar]
            [re-frame.source-coords :as source-coords]
            [re-frame.substrate.context :as substrate-context]))

;; ---- the React context for frame propagation -----------------------------
;;
;; Per rf2-3yij Decision 2 the React Context object lives in
;; `re-frame.substrate.context` so the UIx adapter (and any future
;; React-shaped adapter) reads the *same* context — not a parallel one.
;; The Reagent code below references it via the alias rather than
;; minting a new createContext call.

(def ^:private frame-context substrate-context/frame-context)

(defn- frame-provider-component
  "The single Reagent component that backs every frame-provider. It takes
  the frame keyword as its first render-time arg and scopes that keyword
  to its subtree via React context. One built component services every
  frame — the keyword lives in the Provider's `:value`, not in a
  closure."
  [frame-kw & children]
  (into [:> (.-Provider frame-context) {:value frame-kw}] children))

(defn build-frame-provider
  "Used by re-frame.substrate.reagent/register-context-provider. Returns
  a Reagent component that scopes a frame keyword to its subtree.

  Zero-arity (rf2-4y60): the returned component takes the frame keyword
  at render time, and a single built component services every frame, so
  there is nothing to specialise at build time. Substrates whose
  `register-context-provider` slot receives a frame-keyword on call (per
  Spec 006 §Frame-provider via React context) discard it and call this
  with no args."
  []
  frame-provider-component)

(defn frame-provider
  "User-facing Reagent component that scopes a frame keyword to its
  subtree. Inside the subtree, `(rf/dispatcher)` / `(rf/subscriber)`
  capture the named frame; reg-view-registered descendants resolve to
  it via React context.

      [rf/frame-provider {:frame :session}
       [header]
       [main-area]
       [footer]]

  `props` is a map carrying `:frame frame-id`. Children render under
  that frame (variadic — zero, one, or many).

  When `:frame` is missing or `nil`, falls through to `:rf/default` —
  matches the no-provider behaviour. This is the defensive default per
  the rf2-sixo decision; an explicit error would catch typos but would
  also break tooling-generated trees that elide the prop.

  Per Spec 002 §What `frame-provider` is. `build-frame-provider` is the
  lower-level substrate hook; this fn is the canonical user-facing
  surface."
  [props & children]
  (let [frame-kw (or (:frame props) :rf/default)]
    (into [(build-frame-provider) frame-kw] children)))

;; ---- frame resolution at render time -------------------------------------

(def ^:dynamic *current-frame* nil)

(defn current-frame
  "Resolution chain (per Spec 002 §Reading the frame from React context):
    1. Dynamic var (set by with-frame).
    2. Closest enclosing frame-provider via React context.
    3. :rf/default.

  Frame ids are always keywords (per Spec 002 §Frame ids). The keyword?
  check filters out React's empty-object default for components without
  a wired contextType — `goog/typeOf` reports both keywords and bare
  objects as \"object\", so a typeOf-based discriminator silently
  swallowed valid keyword contexts and made `frame-provider` resolve
  to `:rf/default` regardless of what it pushed."
  []
  (or *current-frame*
      (when-let [cmp (r/current-component)]
        (let [ctx (.-context cmp)]
          (when (keyword? ctx) ctx)))
      :rf/default))

;; ---- per-render identity (rf2-piag / rf2-t5tx) ----------------------------
;;
;; Render-trace entries carry a `:render-key` of shape
;; `[<view-id> <instance-token>]`. Per rf2-t5tx Option C the tuple is the
;; canonical identity: the view-id (registry keyword) names the kind; the
;; instance-token disambiguates concurrently-mounted instances of the same
;; kind. Tokens are minted at mount time from a process-wide counter and
;; are NOT correlated across runs — they're for in-run instance discrimination
;; only (per Spec 004 §Render-tree primitives).
;;
;; For renders that did not enter through reg-view / reg-view* (plain
;; Reagent fns), `*render-key*` is unbound at trace-emission time; consumers
;; treat that as `[:rf.view/anonymous nil]` (per the bead resolution).

(defonce ^:private instance-counter (atom 0))

(defn mint-instance-token!
  "Return a fresh integer token for a freshly-mounted view instance. The
  counter is process-wide and monotonic; values are unique within a
  single process run but carry no cross-run correlation guarantee."
  []
  (swap! instance-counter inc))

(def ^:dynamic *render-key*
  "The `:render-key` for the in-flight render — a tuple
  `[<view-id> <instance-token>]`. Bound by the wrapper emitted by
  `reg-view*` for the duration of each render. Nil outside a registered
  view's render (the trace recorder treats nil as
  `[:rf.view/anonymous nil]` per Spec 004 §Render-tree primitives)."
  nil)

(defn current-render-key
  "Return the `:render-key` for the in-flight render, or
  `[:rf.view/anonymous nil]` when none is bound (e.g. inside a plain
  Reagent fn that bypassed reg-view). Per Spec 004 §Render-tree
  primitives — the anonymous fallback is the documented unbound-shape."
  []
  (or *render-key* [:rf.view/anonymous nil]))

(defn- reagent-component-token
  "Return the per-component-instance token, minting one on first call.
  Stored on the Reagent component object as `.-rfInstanceToken` so the
  same mounted instance reuses the token across re-renders. When called
  outside a Reagent component (direct invocation in headless tests),
  mints a fresh token per call — that mirrors the per-mount-fresh
  semantics for tests that simulate one mount per call."
  []
  (if-let [cmp (r/current-component)]
    (or (.-rfInstanceToken ^js cmp)
        (let [tok (mint-instance-token!)]
          (set! (.-rfInstanceToken ^js cmp) tok)
          tok))
    (mint-instance-token!)))

(defn- emit-render-trace!
  "Emit a `:view/render` trace event tagged with the in-flight
  `:render-key`. The trace also carries the `:frame` tag so the
  epoch-capture buffer (per re-frame.epoch §capture-event!) can route
  the render into the right per-frame cascade. Goes through late-bind
  so this ns doesn't depend on re-frame.trace (which itself routes
  through late-bind for registrar/views ordering reasons). Production
  builds elide via the `interop/debug-enabled?` gate the trace surface
  itself rides."
  [render-key]
  (when interop/debug-enabled?
    (when-let [emit! (late-bind/get-fn :trace/emit!)]
      (emit! :view :view/render
             {:render-key render-key
              :frame      (current-frame)}))))

;; ---- source-coord DOM annotation (rf2-z7f7) -------------------------------
;;
;; Per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1) the Reagent
;; substrate adapter MUST inject `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"`
;; on each registered view's root DOM element when `interop/debug-enabled?`
;; is true. The annotation lets pair-shaped tools (re-frame-pair,
;; re-frame-10x, IDE jump-to-source) map a clicked DOM node back to the
;; reg-view call site.
;;
;; Contract details:
;;
;;   - The id is a registry keyword `<ns>/<sym>`. Combined with the
;;     captured `:line` / `:column` (from `(meta &form)` at reg-view
;;     macro-expansion time), the attribute value is
;;     `<ns>:<sym>:<line>:<col>`. `<col>` is `?` when the column was
;;     not captured (the column-key is optional per Spec 001).
;;
;;   - The wrapper inspects the user's render-fn output:
;;       * `[:tag {...attrs} & children]` → merge :data-rf2-source-coord
;;         into attrs.
;;       * `[:tag & children]` (no attrs map)            → splice an attrs
;;         map in.
;;       * `[fn-or-component-or-fragment …]` (head is a fn / class / `:>`
;;         / React-fragment marker) → SKIP and emit a one-shot warning
;;         per id. Pair-tool consumers fall back to the registry's
;;         `:rf/id` for these cases (per Spec 006 §Source-coord
;;         annotation, documented Fragment exemption).
;;       * Form-2: when the render-fn returns a fn (`(fn [args] body)`),
;;         we recurse on the inner-fn's output the next time the wrapper
;;         is called — Reagent invokes the inner fn during the SAME
;;         render cycle, but the wrapper's annotation runs OUTSIDE
;;         Reagent's per-render machinery. The simplest correct shape
;;         is to wrap the returned fn so the inner output gets walked
;;         too.
;;
;;   - Production elision: every annotation site sits inside
;;     `(when interop/debug-enabled? ...)` so the closure compiler
;;     constant-folds the entire branch under `:advanced` +
;;     `goog.DEBUG=false`. Per Spec 009 §Production builds.

(defn- format-source-coord
  "Render the registry slot's captured coords as the attribute value
  shape `<ns>:<sym>:<line>:<col>`. The id keyword's namespace and name
  give us `<ns>` and `<sym>`; `<line>` / `<col>` come from the captured
  coords (CLJS reg-view macro at expansion time). Per Spec 006
  §Source-coord annotation."
  [id coords]
  (let [ns-part  (or (namespace id) "?")
        sym-part (name id)
        line     (:line coords)
        col      (:column coords)]
    (str ns-part ":" sym-part ":"
         (if line (str line) "?")
         ":"
         (if col (str col) "?"))))

(defonce ^:private warned-non-dom-roots (atom #{}))

(defn- warn-non-dom-root!
  "Emit a one-shot warning per id that the reg-view'd component returned
  a non-DOM root (a fn/class component, or a React Fragment). Pair tools
  fall back to the registry's `:rf/id`; documented exemption per Spec 006
  §Source-coord annotation."
  [id head]
  (when-not (contains? @warned-non-dom-roots id)
    (swap! warned-non-dom-roots conj id)
    (when (exists? js/console)
      (.warn js/console
        (str "[re-frame] reg-view " id " — root element is "
             (pr-str head) "; data-rf2-source-coord skipped (Spec 006 "
             "§Source-coord annotation: pair tools fall back to :rf/id "
             "for non-DOM roots).")))))

(defn- dom-tag?
  "True if `head` is a Hiccup DOM-tag keyword. Reagent's React-fragment
  marker is `:<>`; the `:>` (interop) marker is for arbitrary React
  components — both are exempt from annotation per Spec 006."
  [head]
  (and (keyword? head)
       (not= :<> head)
       (not= :> head)))

(defn- inject-source-coord-attr
  "Walk the user's render-fn output and merge :data-rf2-source-coord
  into the root element's attrs map. Called from inside the wrapper
  (gated on interop/debug-enabled?). Returns the (possibly rewritten)
  hiccup. Non-DOM roots are returned unchanged after a one-shot
  warning per Spec 006 §Source-coord annotation.

  Form-2: when `out` is a fn, return a fn that recurses on the inner
  output — Reagent's renderer will call our returned fn just like
  the user's fn, and we get a chance to annotate the inner hiccup."
  [id coord-attr out]
  (cond
    ;; Form-2: render-fn returned a fn. Wrap so the inner fn's output
    ;; is also annotated when Reagent calls through.
    (fn? out)
    (fn form-2-wrapper [& args]
      (inject-source-coord-attr id coord-attr (apply out args)))

    ;; Hiccup vector with a DOM-tag keyword head. Annotate the root.
    (and (vector? out) (dom-tag? (first out)))
    (let [head     (first out)
          maybe-attrs (second out)]
      (if (map? maybe-attrs)
        ;; Existing attrs map — merge in (don't overwrite if user
        ;; already set it for some reason).
        (let [merged (if (contains? maybe-attrs :data-rf2-source-coord)
                       maybe-attrs
                       (assoc maybe-attrs :data-rf2-source-coord coord-attr))]
          (into [head merged] (drop 2 out)))
        ;; No attrs map — splice one in between head and children.
        (into [head {:data-rf2-source-coord coord-attr}] (rest out))))

    ;; Non-DOM root (fn-component head, fragment, lazy-seq, nil). Skip
    ;; with a one-shot warning. Pair tools fall back to :rf/id.
    :else
    (do
      (when (vector? out)
        (warn-non-dom-root! id (first out)))
      out)))

;; ---- reg-view -------------------------------------------------------------

(defn reg-view*
  "Reagent-aware view registration. Wraps `render-fn` with the React
  `:contextType` static-field metadata used to resolve the surrounding
  frame at render time, then registers it in the :view kind of the
  registrar.

  Per Spec 004 §reg-view*: this is the plain-fn surface delegated to
  by `re-frame.core/reg-view*` on CLJS. `metadata` is merged into the
  registry slot's metadata as-is; source-coord capture is performed
  by the caller (`re-frame.core/reg-view*`).

  Note (rf2-kdwc): Reagent's create-class / fn-to-class machinery
  recognises `:contextType` (camelCase, the React static-field name),
  not `:context-type` (kebab). The earlier shape used the kebab key
  and was silently ignored, which is why frame-provider context
  resolution fell back to :rf/default.

  Per rf2-piag / rf2-t5tx: each render binds `*render-key*` to the
  tuple `[id instance-token]` for the body, so the trace recorder can
  attribute the render. The instance-token is minted at mount and
  reused across re-renders of the same component instance (per
  Spec 004 §Render-tree primitives).

  Per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1): when
  `interop/debug-enabled?` is true, the wrapper merges
  `:data-rf2-source-coord` onto the rendered root DOM element. The
  attribute value carries `<ns>:<sym>:<line>:<col>`, derived from the
  registry id and the coords captured by the reg-view macro at
  expansion time. Production builds elide the entire annotation
  branch via the `interop/debug-enabled?` gate."
  [id metadata render-fn]
  (let [coord-attr (when interop/debug-enabled?
                     (format-source-coord id metadata))
        wrapped (with-meta
                  (fn frame-aware-view [& args]
                    (let [tok        (reagent-component-token)
                          render-key [id tok]]
                      (binding [*render-key* render-key]
                        (emit-render-trace! render-key)
                        ;; Per Spec 009 §Performance instrumentation
                        ;; (rf2-du3i): every render of a registered view
                        ;; brackets the user render-fn in performance
                        ;; marks so prod builds with the perf flag
                        ;; enabled produce a `rf:render:<view-id>`
                        ;; measure entry. Default-off; under :advanced +
                        ;; `re-frame.performance/enabled?=false` the
                        ;; bracket DCEs and the form collapses to the
                        ;; bare `(apply render-fn args)` call.
                        (let [out (performance/mark-and-measure :render id
                                    (apply render-fn args))]
                          (if interop/debug-enabled?
                            (inject-source-coord-attr id coord-attr out)
                            out)))))
                  {:contextType frame-context})]
    (registrar/register! :view id (assoc metadata :handler-fn wrapped))
    wrapped))
