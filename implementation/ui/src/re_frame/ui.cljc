(ns re-frame.ui
  "Public surface of the re-frame.ui compiled-view substrate (artifact
  day8/re-frame2-ui; epic rf2-vxgfnd; Spec 004 rewrite).

  S1b (rf2-vxgfnd.2) exposes the compiler slice:

    defview          the ONE component form (macro; .cljc — two emitters)
    custom-element   the RULED custom-element declaration (macro)
    sub              the reactive-read grammar (compiles at S1; reads
                     land S2 — calling at S1 raises the pending error)
    raw / html /
    raw-fn / spread  interop compile forms (analyzed in templates)

  S1c (rf2-vxgfnd.3) adds root identity + the mount surface (the
  root-identity-and-mount contract):

    mount            (mount root-form dom-node opts?) — macro over a
                     LITERAL root form; one-shot client mount, idempotent
                     per root; identity opts (:root-id / :disambiguator /
                     :identifier-prefix) are compile-time literals
    create-root      (create-root dom-node opts) — macro; fixes identity
                     for the Root's lifetime (authored :root-id required —
                     no root form to derive from)
    render!          (render! root root-form) — macro; root form literal
    hydrate-root     (hydrate-root dom-node root-form opts?) — macro;
                     identity comes FROM the manifest (S5) — S1 fails loud
    unmount!         total teardown; unregisters the root-id
    render-static    (render-static root-form) — macro; the pure :server
                     static-HTML render (S5), non-hydrating: no manifest, no
                     payload, no phase flip. JVM/server only
    frame-root       the static ENSURE-plan wrapper, legal only in a root
                     form's top region — plans compile into the Root
                     Descriptor (:rf.root/schema-version 1); ENSURE runs
                     at host preflight (S2c)
    frame-provider   SCOPE-only: scope a subtree to an ALREADY-LIVE frame
                     (`{:frame f}`, a runtime frame id / value) through
                     the shared React context. Creates nothing (roots
                     ensure, providers scope — the rf2-nyea0r split)

  S2 adds the one boot adapter and the ops-bundle body form:

    adapter          pass to `(rf/init! ui/adapter)`; the watchable native
                     React observation substrate on CLJS and the same
                     observation contract over the headless atom host on JVM
    frame            (frame) inside a compiled view body — the frame-locked
                     operation bundle {:frame :dispatch :dispatch-sync
                     :subscribe} (the standard capture-frame bundle) bound
                     to the committed frame; identity stable per live frame
                     incarnation, ops fail loud once that incarnation dies

  Anything not exported here does not exist: no reg-view family, no
  Form-1/2/3, no positional view args, no ratoms/cursors/reactions —
  Spec 004 §Removed forms. local/effect and the committed-handler
  surfaces land S2/S3.

  The template AST is PRIVATE; the public contract is the versioned JVM
  structural tree + the DOM conversion table
  (jvm-tree-and-conversion-contract.md)."
  #?(:cljs (:require-macros [re-frame.ui]))
  (:refer-clojure :exclude [spread dispatch-fn ref])
  (:require [re-frame.error :as error]
            [re-frame.ui.hooks]
            [re-frame.ui.presence-runtime :as presence-rt]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.route-link-seam :as rl]
            #?@(:clj  [[re-frame.ui.compiler :as compiler]
                       [re-frame.ui.compiler.root :as root]
                       ;; JVM: loads the frame machinery so compiled bodies'
                       ;; lowered `(re-frame.ui.frames/frame-ops)` calls (and
                       ;; the emitted jvm-provider/root scopes) resolve in any
                       ;; consumer ns that requires only re-frame.ui — the
                       ;; CLJS side reaches it through re-frame.ui.client
                       [re-frame.ui.frames]
                       [re-frame.ui.tree :as tree]
                       [re-frame.substrate.plain-atom :as plain-atom]
                       [re-frame.ui.rules :as rules]]
                :cljs [[re-frame.ui.client :as client]
                       [re-frame.ui.substrate :as ui-substrate]
                       [re-frame.ui.eq :as eq]
                       [re-frame.ui.rules :as rules]
                       [re-frame.ui.runtime :as runtime]])))

;; ---------------------------------------------------------------------------
;; Process substrate
;; ---------------------------------------------------------------------------

(def adapter
  "The first-party `re-frame.ui` substrate adapter. Pass it once at boot:

      (rf/init! ui/adapter)

  CLJS installs the retained watchable React substrate (`IDeref` +
  `IWatchable` derived values), so observation-port movements drive ViewCells
  without Reagent or UIx. JVM uses the headless atom realization of
  the same closed adapter contract. Both report the canonical discriminator
  `:rf.adapter/ui`. Destroying it first tears down every public compiled Root
  and then the generic React spine, so no DOM/ViewCell/observation ownership
  survives adapter disposal. CLJS Root/ViewCell ownership requires the host's
  standard usable WeakRef constructor + deref, probed once before admission;
  absence or an unusable implementation throws
  :rf.error/ui-platform-incompatible before mutation. FinalizationRegistry is
  optional because synchronous weak scans compact collected entries."
  #?(:clj  (assoc plain-atom/adapter :kind :rf.adapter/ui)
     :cljs (ui-substrate/adapter-with-client-roots
            client/with-root-admission-closed!
            client/drain-live-roots!)))

;; ---------------------------------------------------------------------------
;; The component form
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro defview
     "(defview name docstring? opts? [props?] template)

     The one component form: a pure function of ONE props map to a
     template, compiled to direct React code (browser) and the versioned
     structural tree (JVM). Zero or one argument — the props map; header
     destructuring lowers to direct slot reads; `:as` opts into
     materialization + generic comparison. Options (closed):
     :props (Malli), :id (registry override), :display-name.

     Every view is memoized on the generated per-slot rf= comparator
     (RULED: Object.is(a,b) OR (= a b)) and registers in the registrar's
     :view kind."
     [vname & forms]
     (compiler/defview* &form &env vname forms)))

#?(:clj
   (defmacro custom-element
     "(custom-element tag {:properties #{:help-text ...}})

     RULED declaration grammar (2026-07-12): top-level,
     compile-resolvable, registers like defview; the :properties set is
     the ENTIRE v1 grammar. Declared names compile to camelCase JS
     properties on the client (:help-text -> helpText); undeclared names
     are attributes; undeclared elements need no declaration
     (all-attributes default). The JVM serialiser emits attributes only —
     property-props are named by :rf.ui/property-props and applied at
     hydration."
     [tag opts]
     (compiler/custom-element* &form &env tag opts)))

;; ---------------------------------------------------------------------------
;; Roots + mounting (S1c — the root-identity-and-mount contract)
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro mount
     "(mount root-form dom-node)
     (mount root-form dom-node opts)

     The one-shot client mount: create-root + frame preflight + render!,
     IDEMPOTENT PER ROOT (same root-id + same container re-renders the
     existing Root; frames found live, no re-seed). The root form is
     LITERAL — the compiler keeps the AST closed, extracts the static
     frame plans from the top region, and emits Root Descriptor v1.

     opts (closed): identity tier :root-id / :disambiguator /
     :identifier-prefix — compile-time literals (authored :root-id wins;
     otherwise the root-id derives from the mounted view's registered id,
     with [view-id disambiguator] on double-mount of one view); host tier
     :on-uncaught-error / :on-caught-error / :on-recoverable-error —
     plain fns handed to the React root (may be runtime expressions).

     A failed first render retains the exact id/container/prefix claim as
     :tearing-down before cleanup. Normal cleanup releases it at the next FIFO
     microtask; a cleanup throw force-deads the exact framework incarnation,
     stays quarantined fail-closed, and rides the original mount error as
     rfUiRollbackCleanupError secondary evidence.

     Returns the Root."
     ([root-form dom-node]
      (root/mount-form &form &env root-form dom-node {}))
     ([root-form dom-node opts]
      (root/mount-form &form &env root-form dom-node opts))))

#?(:clj
   (defmacro create-root
     "(create-root dom-node opts) => Root

     Identity is fixed HERE, for the Root's lifetime — and with no root
     form to derive from, an authored :root-id is REQUIRED (ui/mount is
     the derivation-default one-liner). opts (closed): :root-id (required)
     / :identifier-prefix + the host error callbacks. No render happens;
     preflight runs before the first render!. On CLJS the one-shot WeakRef
     capability gate runs before React allocation or live-root registration."
     [dom-node opts]
     (root/create-root-form &form &env dom-node opts)))

#?(:clj
   (defmacro render!
     "(render! root root-form)

     Render / re-render the LITERAL root form into a Root obtained from
     create-root. Frame plans in the form's top region preflight before
     the render (S2 seam); the Root's identity is untouched — it was
     fixed at create-root. Returns the Root."
     [root root-form]
     (root/render-form &form &env root root-form)))

#?(:clj
   (defmacro hydrate-root
     "(hydrate-root dom-node root-form)
     (hydrate-root dom-node root-form opts) => Root

     Hydrating mount — identity comes FROM the server-emitted manifest
     (root-id + identifier-prefix); supplying identity opts client-side
     is a compile error (the client must use the server's prefix or
     use-id hydration breaks). opts: host-behaviour tier only
     (:on-uncaught-error / :on-caught-error / :on-recoverable-error). A
     hydrate with no valid server manifest fails loud with
     :rf.error/root-manifest-invalid.

     Canonical SSR boot — two calls. Seed state with ssr/hydrate! WITHOUT
     :render-tree-fn (a compiled root has no hashable client render-tree,
     so the :render-tree-fn hash channel is hiccup-tier-only), then adopt
     the server DOM here:

       (ssr/hydrate! {:frame :app :payload payload})   ;; state only
       (ui/hydrate-root el [ui/frame-provider {:frame :app} [app-root]]
                        {:on-recoverable-error my-handler})

     Verification is by React-native ADOPTION, not a hash: React diffs this
     root's first :server-phase render (its ui/client-only fallbacks)
     against the server DOM, and the runtime surfaces a React-RECOVERABLE
     adoption error (a text-content or structural mismatch) as a
     :rf.ssr/hydration-mismatch diagnostic, composed OVER any authored
     :on-recoverable-error (framework emit first, THEN your callback —
     never clobbered). ATTRIBUTE-ONLY mismatches (a stale class / style /
     ARIA value) are NOT surfaced — React takes its dev-only warning path
     for those and re-frame2 emits no trace on this tier (Spec 011
     §Hydration-mismatch detection, the attribute-only boundary).

     Returns the Root."
     ([dom-node root-form]
      (root/hydrate-root-form &form &env dom-node root-form {}))
     ([dom-node root-form opts]
      (root/hydrate-root-form &form &env dom-node root-form opts))))

#?(:clj
   (defmacro render-static
     "(render-static root-form) => an inert HTML string

     The pure static-HTML render entry — the compiled-view counterpart of
     React's `renderToStaticMarkup`. It renders a compiled root to a static
     HTML string in the pure `:server` phase: NON-hydrating, with NO manifest,
     NO hydration payload, and NO phase flip (a `client-only` site renders its
     capability-free fallback and stops there). It is the static-page path, not
     the SSR-then-hydrate path — `ui/hydrate-root` + `re-frame.ssr/hydrate!` own
     that (Spec 011).

     Like `mount` / `render!` / `hydrate-root`, render-static is a MACRO so it
     enforces the LITERAL root form at the call site — a runtime-assembled vector
     is the same `:rf.ui.compile/runtime-root-form` compile error (a macro sees
     the literal form; a fn cannot). The root form mounts exactly one view (its
     derived identity — §7). JVM/server only: a CLJS expansion is a compile error
     (there are no structural trees in the browser). Renders against the ambient
     per-request frame (the SSR host's `with-frame`)."
     [root-form]
     (root/render-static-form &form &env root-form)))

(defn unmount!
  "(unmount! root) — TOTAL exact-incarnation teardown. The complete
  root-id/container/identifier-prefix claim moves :live → :tearing-down →
  :released and frees only at host settlement: inline for synchronous React
  cleanup, or at the deferred settlement microtask. A throwing host cleanup
  force-deads framework ownership but quarantines the unproven claim
  :tearing-down; retry uses a fresh container. Idempotent. Returns nil."
  [root]
  #?(:clj  (tree/jvm-host-op!
            :ui/unmount!
            "(ui/unmount! root) tears down a live client React root")
     :cljs (client/unmount!* root)))

(defn frame-root
  "(frame-root {:id :frame-id ...config} children...) — the static
  ENSURE-plan wrapper, legal ONLY in the top region of a root form handed
  to ui/mount / ui/render! / ui/hydrate-root. :id MUST be a compile-time
  literal keyword (plans are static identity); the remaining config
  entries are runtime expressions evaluated at preflight. The compiler
  extracts {:frame-id :config-fingerprint} into the Root Descriptor and
  renders the wrapper TRANSPARENTLY at S1 — frame ENSURE + :initial-events
  drain land with the S2 frame wiring (Spec 002 owns the semantics).

  This var exists for compile-time resolution; the form is compiled away,
  never called — a direct call fails loud by design."
  [& _args]
  (error/throw-error!
   :rf.error/ui-frame-root-outside-root-form 're-frame.ui/frame-root
   (str "(ui/frame-root {:id ...} children...) is a ROOT-FORM wrapper — "
        "the static ENSURE-plan position, legal only in the top region of "
        "a root form handed to ui/mount / ui/render! / ui/hydrate-root; "
        "it compiles away and is never called. To SCOPE a subtree to an "
        "already-live frame inside a view, use ui/frame-provider")
   nil))

(defn frame-provider
  "(frame-provider {:frame f} children...) — SCOPE a subtree to an
  ALREADY-LIVE frame through the shared React context. `:frame` is a
  RUNTIME frame target (a frame-id keyword or a live frame value); the
  provider creates / refreshes / destroys NOTHING (roots ensure,
  providers scope — the rf2-nyea0r split). Legal anywhere a template
  form is (defview templates and root forms). Descendant view boundaries
  resolve this frame ambiently (explicit pin > dynamic binding > this
  context > loud :rf.error/no-frame-context); sites in the SAME template
  body read the OUTER scope (React context semantics). A `:frame` naming
  no live frame fails loud (`:rf.error/frame-provider-frame-absent`).

  This var exists for compile-time resolution; the form compiles into a
  scope element and is never called — a direct call fails loud."
  [& _args]
  (error/throw-error!
   :rf.error/ui-frame-provider-outside-template 're-frame.ui/frame-provider
   (str "(ui/frame-provider {:frame f} children...) is a TEMPLATE form — "
        "it scopes a subtree to an already-live frame through the shared "
        "React context, and the compiler wires it into a scope element; "
        "it is never called directly. To CREATE the frame if absent, use "
        "ui/frame-root in a root form's top region")
   nil))

;; ---------------------------------------------------------------------------
;; Reactive-read grammar (S1: compiles; reads land S2)
;; ---------------------------------------------------------------------------

(defn sub
  "(sub [:query ...]) is a compiler-owned authoring form for a lexical view
  site. Accepted template occurrences lower to the internal two-argument
  site-bearing read on BOTH hosts. This var exists for symbol resolution only;
  a direct call (including from a helper the view compiler cannot inspect)
  fails loudly rather than becoming a nonreactive one-shot read. Pass values
  into helpers, or make the helper a defview. Explicit headless probing belongs
  to the test/observation seam."
  [query]
  ;; `error/pr-form` / `error/safe-form`, not bare `pr-str`: the whole body is
  ;; the throw, so `query` is whatever the author passed — including a foreign
  ;; JS value, and a cyclic one (a React context) would make `pr-str` blow the
  ;; stack instead of producing this error (rf2-30dc7).
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/sub
   (str "(ui/sub " (error/pr-form query) ") executed outside compiler lowering — "
        "ui/sub is a lexical defview form, not a callable subscription helper. "
        "Pass the read value into the helper or extract a defview")
   {:extra {:query (error/safe-form query)}}))

(defn frame
  "(frame) is the compiler-owned ops-bundle body form: inside a compiled
  view it returns the frame-locked operation bundle

      {:frame         <frame-id>
       :dispatch      (fn ([event] [event opts]))
       :dispatch-sync (fn ([event] [event opts]))
       :subscribe     (fn [query-v])}

  — the standard capture-frame bundle, bound to the COMMITTED frame (the
  same ambient resolution every frame-aware UI form uses; frame-provider /
  frame-root scope it). Bundle identity is stable for one live frame
  incarnation and its ops fail loud (`:rf.error/frame-destroyed`) once
  that incarnation is destroyed or replaced — a carried bundle never
  silently retargets. Accepted occurrences lower to the internal runtime
  bridge on BOTH hosts; like `sub`, the form is finite and render-time
  (loops, deferred callbacks, and root expressions are compile errors).

  This var exists for symbol resolution only; a direct call (including
  from a helper the view compiler cannot inspect) fails loudly. In plain
  fns, tools, tests, and boot code hold a frame with `rf/capture-frame`
  instead."
  []
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/frame
   (str "(ui/frame) executed outside compiler lowering — it is a lexical "
        "defview body form, not a callable frame helper. Author it inside "
        "a compiled view body; outside views hold a frame with "
        "rf/capture-frame")
   nil))

(defn local
  "(local init) is the compiler-owned view-local ephemera form: bound in a
  defview's top-region `let`, it returns the THREE-TUPLE

      [value set! update!]

  — host component-local state, deliberately OUTSIDE re-frame2 epochs (not
  observed by subs, not revertible by epoch restore, re-renders this view
  only). `set!` stores its argument EXACTLY — a stored function is a value,
  never an updater (no useState fn-overload). `update!` applies
  `(f current & args)` to the LATEST host state (not the committed render's
  value), so several same-turn host writers (key + pointer + timer + observer
  callbacks) compose instead of last-write-wins — the multi-writer contract a
  component library cannot build soundly itself.

  Setter and updater are HOST-ONLY: calling them during a render pass fails
  loud (a dev error — renders are speculative); committed same-view handlers
  and `effect` callbacks may read and update. Two-element destructuring
  `[value set!]` stays valid. On the JVM structural render `local` exposes the
  INITIAL value, but invoking `set!`/`update!` raises `:rf.error/jvm-host-op`.

  Doctrine (rf2-5sjbg lineage): product-meaning state lives in app-db behind
  events; `local` is for keystroke-latency ephemera. When a field's every
  keystroke IS product state, dispatch placeholders (`:rf.ui/value`) instead.

  This var exists for symbol resolution only; a direct call fails loudly."
  [_init]
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/local
   (str "(local init) executed outside compiler lowering — it is a lexical "
        "defview form bound in a top-region let, not a callable helper. Author "
        "it as (let [[value set! update!] (local init)] …) in the view body")
   nil))

(defn ref
  "(ref) / (ref initial) → the host ref object (React `useRef`), the everyday
  \"I need the DOM node\" primitive (focus, measurement, a third-party widget).
  Bound in a defview's top-region `let`; read/write via `(.-current ref)`.
  Assignment NEVER re-renders — its reason to exist beside `local` — and it is
  the preferred object ref for a `:ref` position. No deps.

  The canonical shape captures the ref in the body, hands it to `:ref`, and reads
  `(.-current node)` from an `effect` — the object ref attaches at commit BEFORE
  effects fire, so `.-current` is populated by first effect time:

      (ui/defview chart [{:keys [series]}]
        (let [node (ui/ref)]
          (ui/effect [node series]
            (when-let [el (.-current node)]
              (draw! el series)
              #(destroy! el)))
          [:canvas {:ref node}]))

  Callback refs via `ui/raw-fn` remain the honest expert seam for when the target
  node's identity change must ITSELF trigger work — `ui/ref` is the common case.

  On the JVM structural render `ref` yields an inert ref (`current` nil, stays
  nil); refs never appear in the JVM tree.

  This var exists for symbol resolution only; a direct call fails loudly."
  ([] (error/throw-error!
       :rf.error/ui-tree-malformed 're-frame.ui/ref
       (str "(ref) executed outside compiler lowering — it is a lexical defview "
            "form bound in a top-region let, not a callable helper. Author it as "
            "(let [node (ui/ref)] …) in the view body")
       nil))
  ([_initial] (error/throw-error!
               :rf.error/ui-tree-malformed 're-frame.ui/ref
               (str "(ref initial) executed outside compiler lowering — it is a "
                    "lexical defview form bound in a top-region let, not a "
                    "callable helper. Author it as (let [node (ui/ref initial)] …) "
                    "in the view body")
               nil)))

(defn effect
  "(effect [deps…] body…) / (effect :connect body…) is the compiler-owned host
  effect form: a leading STATEMENT in a defview's top region (or a top-region
  `let`/`do` body), before the final template.

  The value-deps form runs its body after commit whenever the literal deps
  change, COMPARED BY `rf=` (documented cost: broad values walk — keep deps
  narrow); the body's return, when a function, is the cleanup honoured on
  dep-change, disconnect, and unmount. `(effect :connect body…)` runs at each
  connect (mount, or reveal after an Activity hide) with cleanup at each
  disconnect — there is deliberately no \"once\"/\"mount\" name. StrictMode dev
  replay is expected and MUST be idempotent-safe (that is what cleanup is for).

  Effects synchronise with the host world (measurement, chart/animation
  libraries attached via a ref); app state goes through events. Dispatch from
  an effect with `(ui/dispatch-fn)`. `sub`/`frame` inside an effect
  body are compile errors (a deferred callback owns no render-time site).

  On the JVM structural render effects DO NOT run — they are recorded as
  capability metadata only.

  This var exists for symbol resolution only; a direct call fails loudly."
  [& _]
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/effect
   (str "(effect [deps…] body…) executed outside compiler lowering — it is a "
        "lexical defview statement form, placed before the final template, not "
        "a callable helper. Author it in a view's top region")
   nil))

(defn dispatch-fn
  "(ui/dispatch-fn) is the compiler-owned STABLE committed-frame dispatcher for
  imperative/foreign callbacks: a render-time site (like `(frame)`) whose value
  is a per-view stable function

      (fn ([event] [event opts]))

  bound to the COMMITTED frame. Its identity is stable across renders (attach it
  once as a listener); it reads the committed frame at CALL time and retargets
  only on commit; and it FAILS LOUD in every non-connected state
  (`:rf.error/dispatch-disconnected`) — the leaked-listener detector for an
  external callback that outlived its view. Capture it in the view body and use
  it from an `effect` callback or a foreign event bridge.

  On the JVM structural render `dispatch-fn` exposes a dispatcher whose
  invocation raises `:rf.error/jvm-host-op`. Like `frame`, it is finite and
  render-time (loops and deferred callbacks are compile errors).

  This var exists for symbol resolution only; a direct call fails loudly."
  []
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/dispatch-fn
   (str "(ui/dispatch-fn) executed outside compiler lowering — it is a lexical "
        "defview body form returning the committed-frame dispatcher, not a "
        "callable helper. Author it inside a compiled view body; outside views "
        "hold a frame with rf/capture-frame")
   nil))

;; ---------------------------------------------------------------------------
;; Interop compile forms — recognized by the analyzer in templates; the
;; fn definitions below give them var identity (resolution) + honest
;; direct-call behaviour outside templates.
;; ---------------------------------------------------------------------------

(defn raw
  "(ui/raw react-element) — embed an existing React element (child
  position; SSR paths need a client-only sibling fallback). On the JVM a
  rendered ui/raw child raises :rf.error/jvm-host-op."
  [x]
  #?(:clj  (tree/jvm-host-op! :ui/raw "(ui/raw ...) is a host React element")
     :cljs x))

(defn html
  "(ui/html string) — trusted markup, low-friction: the visible call
  marks the one place escaping is bypassed. S1 grammar: the SOLE child of
  a DOM element ([:div (ui/html s)]) — both emitters treat it
  identically. Direct JVM calls build the trusted-HTML node."
  [s]
  #?(:clj  (tree/html s)
     :cljs (error/throw-error!
            :rf.error/ui-tree-malformed 're-frame.ui/html
            (str "(ui/html ...) is a template form — it renders as the sole "
                 "child of a DOM element: [:div (ui/html s)]")
            {:extra {:value s}})))

(defn raw-fn
  "(ui/raw-fn f) — identity-as-protocol callback marker: the fn passes
  through to the host verbatim (also the explicit callback-ref form). In
  the JVM tree it appears as the opaque :ui/raw-fn marker."
  [f]
  f)

(defn spread
  "(ui/spread base overrides) — the ONE generic runtime prop-map
  conversion, legal in a DOM element's props position:
  [:div.card (ui/spread base {:class \"x\"})]. A template form — the
  compiler routes it through the rule table; calling it directly is an
  error by design."
  ([_base] (spread nil nil))
  ([_base _overrides]
   (error/throw-error!
    :rf.error/ui-spread-outside-template 're-frame.ui/spread
    (str "(ui/spread ...) is a template form — it is legal only in a DOM "
         "element's props position, where the compiler wires it through "
         "the conversion rule table")
    nil)))

(defn spread-safe
  "(ui/spread-safe owned caller) — the LITERAL safe-spread policy: the ONE
  attr-passthrough a component library can forward onto an internal element
  without clobbering owned props or forfeiting the controlled guarantee.

  `owned` is a LITERAL map of the component's own props (the compiler sees it
  and analyses it exactly like an element's props map — a controlled owned
  site, a literal `:value`/`:checked` co-present with a literal-vector or
  `ui/event` handler, therefore RETAINS the sync door). `caller` is the
  runtime attr map forwarded from the consumer.

  The compiler-visible deny law, enforced in EVERY build (not dev-only): the
  structural/controlled/identity keys `:key` `:ref` `:value` `:checked` and
  the component's OWNED `:on-*` handlers may not appear in `caller` — a literal
  offender is a compile error (`:rf.ui.compile/spread-safe-owned-key`), a
  runtime offender throws (`:rf.error/ui-tree-malformed`), neither elided in an
  advanced build. Everything else passes: allowed `:on-*` values classify
  through the handler decision table, `aria-*`/`data-*`/`title`/`:class`/
  `:style` convert per the 004B rule table. Owned props win any collision;
  `:class` composes (owned classes first). General `(ui/spread base overrides)`
  remains the visible-cost escape and still forfeits the sync door.

  A template form, legal only in a DOM/custom element's props position; a
  direct call fails loud by design.

  This var exists for symbol resolution only; a direct call fails loudly."
  ([_owned _caller]
   (error/throw-error!
    :rf.error/ui-spread-outside-template 're-frame.ui/spread-safe
    (str "(ui/spread-safe owned caller) is a template form — it is legal only "
         "in a DOM/custom element's props position, where the compiler wires "
         "it through the safe-spread policy and the conversion rule table")
    nil)))

(defn event
  "(ui/event [e] body…) — a compiler-owned committed handler form for a
  DOM/custom-element `:on-*` site whose body needs the live native event.
  The body runs when the callback fires; its result is the EVENT VECTOR to
  dispatch, or `nil` to dispatch nothing (a filter). The callback sees the
  view's COMMITTED values and dispatches to the committed frame — the same
  per-site-stable, retarget-safe machinery a literal event vector rides.

  At a compiler-proven controlled DOM site (a literal `:value`/`:checked`
  prop co-present with the handler on `:on-input`/`:on-change`/
  `:on-before-input`) a synchronous `ui/event` whose result is a vector rides
  the ONE synchronous door alongside a literal vector handler — the shape a
  reusable input control uses to append its live payload to an event prefix
  received through props. Any synchronous result other than a vector or `nil`
  is a loud runtime diagnostic; placeholder keywords inside the runtime result
  are ordinary data (dev warns).

  This var exists for symbol resolution only; a direct call fails loudly."
  [& _]
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/event
   (str "(ui/event [e] …) executed outside compiler lowering — it is a "
        "lexical handler form for a DOM/custom-element :on-* site, not a "
        "callable helper. Author it at the element's handler position")
   nil))

(defn handler
  "(ui/handler [x] body…) — the explicit IMPERATIVE committed callback form,
  the imperative sibling of `ui/event`. Its body runs after commit and its
  return is IGNORED (no dispatch of a returned vector — that is `ui/event`); it
  does imperative work (calling `(ui/dispatch-fn)`, driving a foreign widget,
  measuring). Like every committed callback it is PER-SITE STABLE and reads the
  COMMITTED values — one stable identity an external/foreign consumer attaches
  once, every invocation reading the winning commit (the stale-closure boundary
  law).

  Legal at a DOM/custom-element `:on-*` site (the explicit spelling of the
  bare-fn shorthand; the native event binds its parameter) and at a
  FOREIGN-component or INTERNAL-view prop (the invoker's arguments bind through
  verbatim). At an internal-view seam it gives a bare fn prop the per-site
  stable identity a fresh closure lacks (C-13a).

  This var exists for symbol resolution only; a direct call fails loudly."
  [& _]
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/handler
   (str "(ui/handler [x] body…) executed outside compiler lowering — it is a "
        "lexical committed-callback form for a :on-* site or a foreign/view "
        "prop, not a callable helper. Author it at the callback position")
   nil))

(defn error-boundary
  "(ui/error-boundary {:fallback view :reset-key val :on-error [:ev …]} child)
  — the explicit error component. Catches render/lifecycle throws BELOW it
  (React does not catch event-handler or async errors — those keep their own
  typed paths). On catch the `:fallback` VIEW renders with `:error` + its
  declared props and cannot recursively dispatch. `:on-error` (optional)
  dispatches AFTER the failing commit through a live frame captured at the
  owning view's render — never during render (I-1) — with the caught error
  appended to the authored event vector. Changing `:reset-key` (compared `rf=`)
  clears the caught error (retry = a state change that changes the key). The
  JVM/SSR renders the CHILD under the server failure policy (per [011]) —
  boundaries are a client recovery mechanism.

  A template form, legal in child position; a direct call fails loud by design."
  [& _]
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/error-boundary
   (str "(ui/error-boundary {:fallback view …} child) is a template form — the "
        "compiler wires it into the runtime error boundary; it is never called "
        "directly")
   nil))

(defn client-only
  "(ui/client-only {:fallback tpl} client-tpl) — a browser-only subtree with a
  MANDATORY, capability-free fallback (compiler-checked: the fallback carries no
  reactive read / host state / effect / event handler, so the JVM/SSR and first
  hydration render it deterministically). The JVM/SSR renders the fallback (a
  `:rf.ui/boundary :client-only` node); the browser renders the client subtree
  (the S3 activation). The one root phase-flip that swaps all sites in a single
  update completes S5 (per [011]).

  A template form, legal in child position; a direct call fails loud by design."
  [& _]
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/client-only
   (str "(ui/client-only {:fallback tpl} client-tpl) is a template form — the "
        "compiler renders the client subtree in the browser and the "
        "capability-free fallback on the JVM/SSR; it is never called directly")
   nil))

(defn presence
  "(ui/presence {:timeout-ms n} keyed-children) — declarative enter/exit
  retention, deliberately bounded (NOT an animation system). Keyed children
  pass :mounting → :present → :unmounting; an exiting child is retained for
  exactly the MANDATORY :timeout-ms — the exit retention duration AND terminal
  bound — then removal is terminal and exactly-once (all ownership released).
  Removal-then-reinsertion of a key interrupts the exit and re-enters. Unkeyed
  children under a presence boundary are a build failure. Read a child's phase
  with (ui/presence-phase).

  DOM-agnostic: the boundary inserts no wrapper node, stamps no attributes, and
  observes no DOM events. A presence-aware child owns its own exit styling and
  accessibility — stamp inert / aria-hidden and the exit class against
  (ui/presence-phase) = :unmounting; the child's stylesheet owns
  prefers-reduced-motion.

  A template form — the compiler wires it into the runtime retention boundary;
  a direct call fails loud by design."
  [& _]
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/presence
   (str "(ui/presence {:timeout-ms n} children) is a template form — the "
        "compiler wires it into the runtime enter/exit retention boundary; it "
        "is never called directly")
   nil))

(defn presence-phase
  "(ui/presence-phase) — the single presence-phase read: :mounting / :present /
  :unmounting inside a (ui/presence …) boundary, :present outside one (so
  presence-aware children stay reusable anywhere). A render-time read (a React
  context read on CLJS); the JVM structural render always yields :present."
  []
  (presence-rt/presence-phase))

;; ---------------------------------------------------------------------------
;; Compiled render slots (S3) — the internal library-seam callback + invocation
;; ---------------------------------------------------------------------------

(defn render-fn
  "(ui/render-fn [args…] template) — a compiler-owned PURE render callback for
  an INTERNAL library seam. The body is a template lexically visible at the
  consumer call site, so BOTH emitters COMPILE it (closed grammar, no runtime
  hiccup); it renders from its arguments alone. A render-fn value is legal in
  exactly two positions: a component call-site prop value ([list {:row (ui/
  render-fn [i x] …)}]) — the parameterized markup a reusable view accepts —
  and a ui/slot argument. The library invokes it through ui/slot.

  A slot body is PURE render phase: sub / frame (and dispatch / hooks /
  local / effect) inside are compile errors. A STATEFUL replacement part is a
  pure slot body that MOUNTS a static defview — the defview owns its state, so
  the slot body stays pure.

  This var exists for symbol resolution only; a direct call fails loudly."
  [& _]
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/render-fn
   (str "(ui/render-fn [args…] template) executed outside compiler lowering — "
        "it is a lexical render-slot callback, not a callable helper. Author "
        "it as a component prop value or a ui/slot argument, and invoke it "
        "with ui/slot")
   nil))

(defn slot
  "(ui/slot render-fn-value arg…) — the compiler-owned invocation of a
  ui/render-fn value at a library seam. `render-fn-value` is a ui/render-fn
  (inline or carried through a prop) or nil; any other value is a loud
  didactic error (`:rf.error/ui-tree-malformed`). nil renders nothing; a
  render-fn renders with the supplied args, and its output participates in
  the surrounding children exactly like any other child. A template form,
  legal only in a child position — a direct call fails loud by design.

  This var exists for symbol resolution only; a direct call fails loudly."
  [& _]
  (error/throw-error!
   :rf.error/ui-tree-malformed 're-frame.ui/slot
   (str "(ui/slot render-fn-value arg…) executed outside compiler lowering — "
        "it is a template form invoking a ui/render-fn value in child "
        "position, wired by the compiler; it is never called directly")
   nil))

;; ---------------------------------------------------------------------------
;; route-link (S3, rf2-vxgfnd.95.5) — an ORDINARY compiled view, NOT a compiler
;; intrinsic and NOT a UI→routing dependency
;; ---------------------------------------------------------------------------

;; NOTE: the macro is called QUALIFIED (`re-frame.ui/defview`) — the facade's own
;; `:require-macros [re-frame.ui]` (self) exposes it qualified, and a BARE `defview`
;; here would not resolve to the self-macro on CLJS (it would compile as a plain
;; call and evaluate the header binding symbols as vars). This is the one
;; framework-authored compiled view, so it is the only in-facade `defview` use.
(re-frame.ui/defview route-link
  "(ui/route-link {:to :route-id ...html-attrs} & children) — a navigation
  anchor that renders a REAL `<a href=…>` with the route's strategy-encoded href
  (so copy-link / open-in-new-tab / keyboard activation / no-JS navigation all
  work) and, on a plain in-app left click, dispatches the routing cascade to the
  committed frame instead of letting the browser reload.

  `:to` is required (a registered route id). `:params` / `:query` / `:fragment`
  feed both the href and the dispatch payload; every OTHER key — `:class`,
  `:title`, `:id`, `:aria-label`, `:target`, `:download`, `:on-click`, and any
  further HTML attribute — passes through to the underlying `<a>`. A caller
  `:on-click` runs FIRST; if it prevents the default (or the anchor is
  native — `:target` other than `_self`, or `:download`) or the click is a
  modifier / middle / auxiliary-button click, the framework does NOT intercept
  and the browser's native `href` behaviour stands. Otherwise the plain
  left-click is intercepted and the navigation dispatches to the frame that
  RENDERED the link, tagged `:source :router`.

  `:prefetch :intent` warms the destination's resources on credible user intent
  — pointer hover, focus or touch — by dispatching `[:rf.route/prefetch …]` for
  this link's own address to the same frame the click targets. `:intent` is the
  only accepted value: a passive render dispatches nothing, so there is no
  render mode, viewport mode or hover-delay knob. The installed handlers COMPOSE
  with a caller-supplied `:on-mouse-enter` / `:on-focus` / `:on-touch-start`
  (yours runs first) rather than replacing it, and `:prefetch` is a routing
  control key — it is stripped before DOM emission and never reaches the `<a>`.

  This is an ORDINARY compiled `defview` — it gets JSX emission, the generated
  prop comparator, JVM-tree parity, and dev identity for free; there is no
  route-link compiler intrinsic. The routing calculation (href encode, dispatch
  payload, native-attr detection), the click law and the prefetch law live in the
  OPTIONAL routing artefact behind the substrate-neutral `:routing/link-model` /
  `:routing/activate-link!` / `:routing/prefetch-payload` /
  `:routing/prefetch-on-intent!` late-bound seam, consumed here through core's
  late-bind registry — so `re-frame.ui` never statically requires routing
  (`ui -> core late-bind <- routing`). Rendering a `ui/route-link` without
  `day8/re-frame2-routing` on the classpath fails loud with
  `:rf.error/routing-artefact-missing`; a plain `[:a]` stays available for
  intentional browser-native navigation. JVM/SSR renders the handler-free
  path-form `<a href>` shell (no raw host event enters the server tree); the
  hydrated client re-encodes through the frame's URL strategy. The behavioural
  contract + full conformance matrix live in Spec 012 (routing owns the law)."
  [{:keys [to params query fragment on-click children] :as props}]
  (let [render-frame (:frame (frame))
        ;; `target` carries the route control keys AND the native-handling attrs
        ;; (`:target` / `:download`) the model needs; `link-model` reads only what
        ;; it needs and ignores the rest.
        target      (dissoc props :on-click :children)
        {:keys [href payload native?]} (rl/link-model target render-frame)
        ;; Everything the caller passed EXCEPT the framework-owned control keys
        ;; is forwarded onto the anchor; the owned `:href` (+ CLJS `:on-click`
        ;; and, on a prefetch link, the three intent handlers) win the spread —
        ;; which is also how a caller's intent handler is COMPOSED rather than
        ;; dropped: the override closure holds it and runs it first.
        passthrough (dissoc props :to :params :query :fragment :prefetch :on-click :children)
        ;; The handler closures are CLJS-only — the JVM/SSR shell renders a
        ;; handler-free anchor (no DOM event to intercept). Reader conditionals
        ;; give each emitter its own override map, so the raw host event never
        ;; enters the server tree.
        overrides   #?(:cljs
                       ;; nil unless this link asked for `:prefetch :intent` —
                       ;; routing decides what asking means, so the view never
                       ;; reads `:prefetch` itself. Asked on CLJS only: the
                       ;; payload exists to be dispatched from an intent handler,
                       ;; and the JVM/SSR shell installs none.
                       (let [prefetch (rl/prefetch-payload target)]
                         (cond-> {:href     href
                                  :on-click (fn [e]
                                              (rl/activate! e on-click render-frame
                                                            payload native?))}
                           prefetch
                           (assoc :on-mouse-enter
                                  (fn [e] (rl/prefetch-on-intent!
                                            e (:on-mouse-enter props) render-frame prefetch))
                                  :on-focus
                                  (fn [e] (rl/prefetch-on-intent!
                                            e (:on-focus props) render-frame prefetch))
                                  :on-touch-start
                                  (fn [e] (rl/prefetch-on-intent!
                                            e (:on-touch-start props) render-frame prefetch)))))
                       :clj  {:href href})]
    [:a (spread passthrough overrides)
     children]))

;; ---------------------------------------------------------------------------
;; The outward interop bridge — `ui/->react` (rf2-u53yy.2)
;; ---------------------------------------------------------------------------

(defn ->react
  "(ui/->react view) => a React component

  Export a compiled re-frame.ui `view` (a `defview` value) as a React component
  a FOREIGN React/UIx tree can render — the OUTWARD half of the foreign
  boundary. `ui/raw` is the inward half (a foreign React element inside a
  compiled view); this is the reverse (a compiled view subtree inside a
  legacy/foreign React parent), the incremental-adoption bridge:

      (def CartRow (ui/->react cart-row))   ;; then render <CartRow …/> anywhere

  Contract (settled — Spec 004 §The React interop tier; the compat-boundary
  contract §3):

    - STABLE, memoised per view identity: repeated `(ui/->react view)` calls —
      and, in DEV, the HMR generations that retain the view's stable shell —
      return the IDENTICAL component object, so a foreign parent re-render never
      remounts the exported subtree.
    - Creates NO React root, mints NO root manifest, runs NO host preflight: the
      exported subtree renders inside the host root the foreign parent owns.
      Frame creation stays with the host app's boot/event infrastructure — an
      exported view scopes and resolves frames, it never creates them.
    - Scopes a SUPPLIED frame without owning it: the ONE reserved prop `frame`
      (a frame-id keyword or a live frame value) scopes the view's subtree
      through the shared React frame context (SCOPE-only — nothing created,
      refreshed, or destroyed; a target naming no live frame fails loud). With
      no `frame` prop the exported view resolves its frame by the ordinary
      ambient chain (a foreign `frame-provider`/`frame-root` above it), or fails
      loud with `:rf.error/no-frame-context` — never a silent default.
    - ONE shallow props-conversion rule: the foreign JS props object is handed
      to the view by a single shallow copy, dropping only the reserved `frame`
      key. Every other own-enumerable key is a view prop-ABI slot matched by
      exact string name (namespace+name preserved — no camelisation, no deep
      walk). React's own `children` and `ref` pass through under that one rule,
      so children and ref semantics are preserved by construction.

  A React component export is meaningless in a JVM structural render, so a JVM
  call raises `:rf.error/jvm-host-op`."
  [view]
  #?(:clj  (tree/jvm-host-op!
            :ui/->react
            "(ui/->react view) exports a compiled view as a foreign React component")
     :cljs (runtime/->react-component view)))
