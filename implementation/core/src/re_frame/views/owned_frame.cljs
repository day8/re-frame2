(ns re-frame.views.owned-frame
  "Substrate-agnostic core of the merged config-shaped `rf/frame-provider`
  (EP-0024 §Scope, carry, and ownership, amended). One provider, two
  config shapes, dispatched on the prop map:

    - **`{:frame existing-id}` — SCOPE-ONLY.** Provide an ALREADY-CREATED
      frame's id to descendants through the shared React context. Creates /
      refreshes / destroys NOTHING. FAILS LOUD when the named frame is
      ABSENT (`:rf.error/frame-provider-frame-absent`) — the guardrail Story
      + Xray rely on (you asked to scope a frame that does not exist). This
      is the scope-INTO-React counterpart to `rf/with-frame` (a dynamic var
      cannot cross React's render boundary). The validate-keyword +
      provide-tier work itself lives in the per-adapter shells
      (`re-frame.substrate.spine/build-frame-provider-element` /
      `re-frame.adapter.context/provider-element`); the absent guard
      (`require-live-frame-for-scope!`) lives here so both the Reagent and
      React-hook shells validate against one place.

    - **`{:id the-id …}` — ENSURE.** Create the frame if absent
      (`acquire-ensure-frame!` runs the unified `re-frame.live-frame/make-frame`
      over the provider's `{:id :images :initial-events :url-bound? …}` opts),
      REUSE it if present WITHOUT re-seeding (`make-frame`'s idempotent
      replacement preserves durable app-db / sub-cache / queue and
      `reg-frame` RE-RECORDS but does NOT REPLAY `:initial-events` on a
      re-registration), and provide its id to descendants. **There is NO
      destroy-on-unmount.** A genuine unmount leaves the frame live.

  EP-0024 amendment (provider collapse). The previous family had THREE
  components: an OWNED `frame-provider` (create-on-mount + destroy-on-unmount),
  a scope-only `frame-provider-existing`, and a namespace-safe twin. All three
  have COLLAPSED into this one merged `rf/frame-provider`. The owned
  destroy-on-unmount has been RETIRED — it had zero product consumers (only
  its own lifecycle tests). True ownership (modals, multi-instance widgets)
  stays expressible as `rf/make-frame` + `rf/destroy-frame!` inside a
  `create-class` (what Story already does), where the component explicitly
  owns the lifetime. The scope-only `frame-provider-existing` and its
  namespace-safe twin have been retired too — the merged provider's
  `{:frame …}` shape (routed through Reagent's `:r>` interop head, so a
  namespaced frame keyword survives the React-context round trip) is their one
  replacement. The merged `rf/frame-provider` keeps the name and serves the
  two non-owning jobs (scope an existing frame, ensure a named frame) that
  every product call site actually needs.

  Why ENSURE rather than CREATE-AND-DESTROY. The ensure shape is exactly
  what hot reload, React StrictMode dev double-invoke, and Story
  re-evaluation need: re-mounting under the SAME `:id` must NOT destroy
  durable state (app-db, sub-cache, queue) NOR re-run `:initial-events`. The
  unified `make-frame` already gives this for free — re-`make-frame`-ing an
  existing id is idempotent replacement (config + resolved generation
  refresh, durable state preserved; EP-0024 §Duplicate id policy), and
  `reg-frame`'s re-registration branch RE-RECORDS the `:initial-events`
  without replaying them (EP-0027 §Reset / §Frame provider). So the ENSURE
  provider is a render-phase `make-frame` and nothing else: no destroy
  effect, no deferred-destroy registry, no StrictMode cancellation dance —
  the durable-state-survives property falls out of `make-frame`'s
  idempotence, not out of a teardown-timing trick.

  CLJS-only (in core, like `re-frame.adapter.context`): it reaches React-shaped
  lifecycle through ONE shared React function component (`ensure-frame-fc`) so
  the ensure-on-render handling lives once, not per substrate. The per-adapter
  `frame-provider` shells (Reagent's `:r>` embed, UIx's `defui`, Helix's
  `defnc`) only read their props in the native idiom, DISPATCH on `:frame` vs
  `:id`, and hand a clean opts map + children to this core — zero
  per-substrate lifecycle drift, mirroring how
  `re-frame.substrate.spine/build-frame-provider-element` factors the
  scope-only element-build. The plain-atom (JVM-runnable) build never loads
  this ns."
  (:require ["react"               :as React]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.error        :as rf-error]
            [re-frame.frame        :as frame]
            [re-frame.live-frame   :as live-frame]))

;; ---- ENSURE: create-if-absent / reuse-if-present (no re-seed) -------------
;;
;; The ensure provider's whole lifecycle is ONE render-phase `make-frame`.
;; There is no destroy-on-unmount, so there is no pending-destroy registry,
;; no deferred-destroy timer, and no StrictMode cancellation. The
;; durable-state-survives-a-remount property is `make-frame`'s idempotent
;; replacement (EP-0024 §Duplicate id policy) + `reg-frame`'s re-record-but-
;; don't-replay-`:initial-events` re-registration (EP-0027), NOT a teardown
;; trick.

(defn acquire-ensure-frame!
  "ENSURE (EP-0024 amended). Create the named frame if absent, REUSE it
  WITHOUT re-seeding if present, and return the resolved frame id.

  Runs the unified `make-frame` over the ensure frame-provider's `opts`
  (`{:id :images :initial-events :url-bound? …}` plus any record-config keys).
  Idempotent under the same `:id`: a re-acquire (hot reload / StrictMode
  remount / Story re-eval) takes `make-frame`'s idempotent-replacement path —
  the record-config + resolved generation refresh while durable state (app-db,
  sub-cache, queue) is PRESERVED and `:initial-events` are RE-RECORDED but NOT
  REPLAYED (EP-0027 §Frame provider). So a remount never re-seeds.

  `opts` must carry an explicit `:id` keyword — an ensure provider names the
  frame it ensures, so the id is the addressing token. `make-frame`'s own
  validation fails loud on a bad shape. Returns the frame id (read off the
  returned frame value via `frame/frame-value->id`)."
  [opts]
  (frame/frame-value->id (live-frame/make-frame opts)))

(defn ^:no-doc ensure-frame-opts
  "Normalise the ensure frame-provider's prop map into the `make-frame` opts
  the lifecycle consumes. Strips the `:children` carrier (the substrate
  element macros fold trailing children onto `:children`; it is not a frame
  option) and passes EVERY OTHER key through — `:id`, `:images`,
  `:initial-events`, `:url-bound?`, and the record-config keys `make-frame`
  honours (`:fx-overrides`, `:preset`, …). `:initial-events` run ONCE per
  frame-id lifetime: the first creation runs the setup; a genuine remount /
  StrictMode re-acquire under the same id RE-RECORDS but does NOT replay
  (idempotent re-registration preserves durable state)."
  [props]
  (dissoc props :children))

(defn ^:no-doc require-ensure-frame-id!
  "Validate the ENSURE frame-provider's `:id` (EP-0024 amended). An ensure
  provider names the frame it ensures, so `:id` is mandatory and must be a
  keyword — it is the addressing token descendants route to and the key
  `make-frame` ensures under. A missing / nil / non-keyword `:id` is a
  CONFIGURATION ERROR: emit + throw `:rf.error/ensure-frame-provider-missing-id`
  so a tooling-generated or hand-authored ensure provider fails loudly at the
  boundary rather than minting an anonymous, unaddressable frame. (The ENSURE
  shape takes `:id`; the SCOPE-only shape takes `:frame` — a `:frame` key with
  no `:id` selects the scope branch, so it never reaches here.) Returns the
  keyword `:id`."
  [id where-sym]
  (if (keyword? id)
    id
    (rf-error/throw-error!
      :rf.error/ensure-frame-provider-missing-id
      where-sym
      (str "frame-provider {:id …} is the ENSURE shape (EP-0024): it CREATES "
           "the frame if absent and REUSES it if present, so it needs an "
           "explicit keyword `:id` to ensure under. Got " (pr-str id) ". Pass "
           "`{:id :your/frame …}` (plus optional :images / :initial-events / "
           ":url-bound?). To merely SCOPE descendants to a frame that ALREADY "
           "EXISTS, use `frame-provider {:frame :your/frame}`.")
      {:recovery :no-frame-context
       :extra    {:received id}})))

;; ---- SCOPE-only: fail-loud-if-absent guard --------------------------------
;;
;; The merged `rf/frame-provider` in its `{:frame existing-id}` shape provides
;; an ALREADY-CREATED frame and creates / refreshes / destroys NOTHING. Per
;; the EP-0024 amendment ruling it FAILS LOUD when the named frame is ABSENT
;; (the scope-only guardrail Story + Xray rely on) — a caller cannot silently
;; scope a subtree to a frame that does not exist. The
;; guard lives here (beside the ensure-provider id guard) so both shells
;; validate against one place; every per-adapter scope shell calls it before
;; delegating to the scope-only provide tier.

(defn ^:no-doc require-live-frame-for-scope!
  "Fail loud when a `{:frame existing-id}` SCOPE-only `frame-provider` names a
  frame that is NOT live in the registry (never created, or destroyed). The
  scope shape provides an ALREADY-CREATED frame — scoping a subtree to an
  absent frame would silently establish a context every descendant
  subscribe / dispatch then mis-resolves, so it is a CONFIGURATION ERROR:
  emit + throw `:rf.error/frame-provider-frame-absent` pointing the caller at
  the ENSURE shape (`{:id …}`) for create-if-absent. `frame-kw` is the
  already-validated keyword frame id; `where-sym` names the calling shell for
  the diagnostic. Returns the keyword when the frame is live."
  [frame-kw where-sym]
  (if (some? (frame/frame frame-kw))
    frame-kw
    (rf-error/throw-error!
      :rf.error/frame-provider-frame-absent
      where-sym
      (str "frame-provider {:frame " (pr-str frame-kw) "} is the SCOPE-only "
           "shape (EP-0024): it provides an ALREADY-CREATED frame through "
           "React context and creates nothing. No live frame is registered "
           "under " (pr-str frame-kw) " — it was never created, or it has been "
           "destroyed. To CREATE the frame if absent (and reuse it if present, "
           "without re-seeding), use the ENSURE shape "
           "`frame-provider {:id " (pr-str frame-kw) " …}` instead; to scope an "
           "existing frame, ensure it is created (e.g. `rf/make-frame`) before "
           "the provider mounts.")
      {:recovery :ensure-or-create-the-frame
       :extra    {:frame frame-kw}})))

;; ---- the shared React lifecycle component (ENSURE) -----------------------
;;
;; ONE React function component backs the ENSURE frame-provider on every
;; React-shaped substrate (Reagent via `:r>`, UIx/Helix via `createElement`).
;; The ensure-on-render handling lives here, not per adapter. ONE phase:
;;
;;   - ENSURE during RENDER (synchronously, before children render) so a
;;     descendant that subscribes/dispatches on first render observes a live
;;     frame. `make-frame` is idempotent replacement, so ensuring during
;;     render (which React may run more than once before commit — concurrent
;;     features, StrictMode dev double-INVOKE of the render fn) is safe: a
;;     same-id re-ensure refreshes config + generation, preserving durable
;;     state and NOT replaying `:initial-events`. The resolved id is stashed
;;     in a `useRef` so re-renders reuse it.
;;
;; There is deliberately NO unmount effect — the ensure provider does not own
;; the frame's teardown. True ownership stays explicit (`make-frame` +
;; `destroy-frame!` inside a `create-class`).

(defn ^:no-doc ensure-frame-fc
  "The React function component that ENSURES one frame on render — ONE
  implementation shared by every React-shaped substrate. `props` is a JS
  object carrying:

    - `:rfOpts`   — the `make-frame` opts (an opaque CLJS value; passed through
                    React props untouched, because every per-adapter shell builds
                    this element via raw `createElement` / Reagent `:r>`, both of
                    which pass JS prop VALUES through without marshalling — the
                    keyword-mangling seam never sees it).
    - `children`  — React's STANDARD children prop. Each shell hands its scoped
                    subtree as trailing `createElement` args (Reagent's `:r>`
                    translates trailing hiccup into React children; UIx/Helix
                    pass their native `:children` React elements), so they arrive
                    here as ordinary React children.

  Returns the shared frame Context Provider element (via
  `adapter-context/provider-element`) scoping the ENSURED frame's id to those
  children.

  Render-phase ensure (NOT effect-phase): lazily acquire the frame the first
  time this instance renders, caching the resolved id in a `useRef` so
  re-renders reuse it (and so the provider's context `:value` is stable).
  Ensuring during render guarantees the frame exists BEFORE children render and
  read it — an effect would run after the first child render and race
  descendant subscribes/dispatches.

  No unmount effect: the ensure provider does NOT destroy the frame on
  unmount (EP-0024 amendment — owned destroy-on-unmount retired). A genuine
  unmount leaves the frame live; a remount re-ensures it (idempotent, no
  re-seed). True ownership stays expressible as `make-frame` + `destroy-frame!`
  inside a `create-class`."
  [^js props]
  (let [opts     (.-rfOpts props)
        children (.-children props)
        id-ref   (React/useRef nil)
        ;; Render-phase ensure (idempotent). Cache the resolved id so the
        ;; context value is stable across re-renders.
        frame-id (or (.-current id-ref)
                     (let [fid (acquire-ensure-frame! opts)]
                       (set! (.-current id-ref) fid)
                       fid))]
    (apply adapter-context/provider-element
           frame-id
           (adapter-context/normalize-children children))))

(defn ^:no-doc ensure-frame-react-element
  "Build the raw React element for the ENSURE frame-provider, for the
  React-hook substrates (UIx / Helix). The substrate-agnostic CORE those
  adapters' native `frame-provider` shells delegate to (on the `{:id …}`
  branch), parallel to `re-frame.substrate.spine/build-frame-provider-element`
  for the scope-only `{:frame …}` branch. Given the provider's
  already-native-read prop map `props` (`{:id :images :initial-events
  :url-bound? …}`), the React children value `children`, and `where-sym` (the
  calling shell's fn symbol, for the fail-loud `:id` diagnostic): validate
  `:id`, separate the `make-frame` opts, and return a `createElement` over
  `ensure-frame-fc` so React ensures the frame on render.

  The Reagent shell does NOT use this — it embeds `ensure-frame-fc` via
  Reagent's `:r>` interop head (so trailing hiccup children translate to React
  children), building the element in `re-frame.views.provider`."
  [props children where-sym]
  (require-ensure-frame-id! (:id props) where-sym)
  (apply React/createElement
         ensure-frame-fc
         #js {:rfOpts (ensure-frame-opts props)}
         (adapter-context/normalize-children children)))
