(ns re-frame.ui
  "Public surface of the re-frame.ui compiled-view substrate (artifact
  day8/re-frame2-ui; epic rf2-vxgfnd; Spec 004 rewrite).

  S1b (rf2-vxgfnd.2) exposes the compiler slice:

    defview          the ONE component form (macro; .cljc — two emitters)
    custom-element   the RULED custom-element declaration (macro)
    sub / lease      the reactive-read grammar (compiles at S1; reads
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
    frame-root       the static ENSURE-plan wrapper, legal only in a root
                     form's top region — plans compile into the Root
                     Descriptor (:rf.root/schema-version 1); frame ENSURE
                     semantics land with the S2 wiring

  Anything not exported here does not exist: no reg-view family, no
  Form-1/2/3, no positional view args, no ratoms/cursors/reactions —
  Spec 004 §Removed forms. local/effect and the committed-handler
  surfaces land S2/S3.

  The template AST is PRIVATE; the public contract is the versioned JVM
  structural tree + the DOM conversion table
  (jvm-tree-and-conversion-contract.md)."
  #?(:cljs (:require-macros [re-frame.ui]))
  (:require [re-frame.error :as error]
            #?@(:clj  [[re-frame.ui.compiler :as compiler]
                       [re-frame.ui.compiler.root :as root]
                       [re-frame.ui.tree :as tree]
                       [re-frame.ui.rules :as rules]]
                :cljs [[re-frame.ui.client :as client]
                       [re-frame.ui.eq :as eq]
                       [re-frame.ui.rules :as rules]
                       [re-frame.ui.runtime :as runtime]])))

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
     preflight runs before the first render!."
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
     use-id hydration breaks). opts: host-behaviour tier only. Server
     rendering + manifests land S5 — at S1 every hydrate fails loud with
     :rf.error/root-manifest-invalid."
     ([dom-node root-form]
      (root/hydrate-root-form &form &env dom-node root-form {}))
     ([dom-node root-form opts]
      (root/hydrate-root-form &form &env dom-node root-form opts))))

(defn unmount!
  "(unmount! root) — TOTAL teardown: unmount the React root and
  unregister the root-id from the live-root registry (Layer 3).
  Idempotent. Returns nil."
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
        "it compiles away and is never called. Inside defview templates "
        "frames are ambient (frame scoping lands S2)")
   nil))

;; ---------------------------------------------------------------------------
;; Reactive-read grammar (S1: compiles; reads land S2)
;; ---------------------------------------------------------------------------

(defn sub
  "(sub [:query ...]) — returns the subscription's value at a compile-
  indexed view site. The GRAMMAR compiles at S1 (loop rejection is a
  compile error); actual reads land with the S2 reactivity slice — until
  then every call raises :rf.error/ui-sub-unavailable."
  [query]
  #?(:clj  (error/throw-error!
            :rf.error/ui-sub-unavailable 're-frame.ui/sub
            (str "(sub " (pr-str query) ") — reactive reads land with the S2 "
                 "reactivity slice; no Stage-1 Tier-1 fixture may exercise a "
                 "sub read")
            {:extra {:query query}})
     :cljs (runtime/sub* query)))

(defn lease
  "(lease descriptor) — declares resource liveness at a view site.
  Grammar-only at S1; lands with the S2 observation slice."
  [descriptor]
  #?(:clj  (error/throw-error!
            :rf.error/ui-lease-unavailable 're-frame.ui/lease
            (str "(lease " (pr-str descriptor) ") — leases land with the S2 "
                 "observation slice")
            {:extra {:descriptor descriptor}})
     :cljs (runtime/lease* descriptor)))

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
