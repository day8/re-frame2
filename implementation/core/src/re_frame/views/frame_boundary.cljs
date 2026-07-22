(ns re-frame.views.frame-boundary
  "Substrate-agnostic cores for the TWO frame-boundary components — the
  ENSURE-shaped `rf/frame-root` and the SCOPE-only `rf/frame-provider` — that
  the rf2-nyea0r split (API-shrink) carved out of the previously-merged
  `frame-provider`. One verb per component (EP-0024 §Scope, carry, and
  ownership, amended by rf2-nyea0r — *roots ensure; providers scope*):

    - **`rf/frame-root` — ENSURE (commit-owned).** Create the frame if absent,
      REUSE it if present WITHOUT re-seeding, and provide its id to descendants.
      **There is NO destroy-on-unmount.** A genuine unmount leaves the frame
      live. Takes the `rf/make-frame` opts (`{:id :images :initial-events
      :url-bound? …}` + record-config). `:id` is REQUIRED and must be a keyword.

    - **`rf/frame-provider` — SCOPE-only.** Provide an ALREADY-CREATED frame's
      id to descendants through the shared React context. Creates / refreshes /
      destroys NOTHING. FAILS LOUD when the named frame is ABSENT
      (`:rf.error/frame-provider-frame-absent`) — the guardrail Story + Xray rely
      on (you asked to scope a frame that does not exist). This is the
      scope-INTO-React counterpart to `rf/with-frame` (a dynamic var cannot cross
      React's render boundary). The validate-keyword + provide-tier work itself
      lives in the per-adapter shells
      (`re-frame.substrate.spine/build-frame-provider-element` /
      `re-frame.adapter.context/provider-element`); the absent guard
      (`require-live-frame-for-scope!`) lives here so every shell validates
      against one place.

  ## Why the split, and why frame-root is a COMMIT-OWNED TWO-PASS boundary
  ## (rf2-nyea0r, ruled 2026-07-11)

  The pre-split merged `frame-provider` ran `make-frame` (registry mutation +
  synchronous `:initial-events`) FROM A COMPONENT BODY (render phase). That
  violates React render purity, with a concrete failure: a Suspense-aborted /
  concurrent pre-commit render creates + seeds a GHOST frame whose
  once-per-lifetime initialization is consumed, yet the component never commits;
  a later real mount then re-`make-frame`s the same id (idempotent replacement)
  and never replays the initialization. Creating side-effecting state during
  render is the root defect.

  `frame-root` fixes it by owning the ENSURE at COMMIT time, in two passes:

    1. **First render emits NO descendant subtree.** `frame-root-fc` returns
       `nil` (no children, no Provider) on its first render — before the frame
       exists, there is nothing to scope. A render that React discards (Suspense
       / concurrent abort) therefore creates NOTHING and seeds NOTHING.
    2. **ENSURE runs in a client `useLayoutEffect`.** Only a render that COMMITS
       runs the layout effect; the effect calls `make-frame` (idempotent
       creation) and marks the boundary READY (a `useState` flip). The
       synchronous layout-phase state flip re-renders BEFORE the browser paints,
       so there is no visible flash.
    3. **After ready, provider + children render.** The second render scopes the
       now-live frame's id to the children through the shared React context.

  `:initial-events` therefore fire **ONCE on first successful creation in a
  committed frame-root/frame-id lifetime**, never on a re-mount or a
  reconfiguration: `make-frame` fires them on first creation and re-construction
  RE-RECORDS but does NOT REPLAY them on an idempotent re-registration
  (EP-0027 §Frame provider). React StrictMode's dev double-invoke of the layout
  effect is safe for the same reason — the second `make-frame` is idempotent
  replacement, preserving durable state and not replaying `:initial-events`.

  A MOUNTED `:id` / opts change FAILS LOUD (`:rf.error/frame-root-reconfigured`)
  rather than being silently ignored: a committed frame-root scopes exactly one
  frame for its lifetime, and re-pointing it at a different frame (or a different
  configuration) is a configuration error, not a reconfiguration the boundary
  supports. Change the `:id` and remount (a React `key`) to scope a different
  frame; use `rf/make-frame` again to reconfigure the SAME frame.

  ## Fail-loud, both ways (did-you-mean)

  The two components have disjoint required keys, and each rejects the sibling's
  key with a diagnostic that names the sibling (rf2-nyea0r):

    - `frame-provider` (SCOPE) given `:id`  → `:rf.error/frame-provider-given-id`
      (\"did you mean `frame-root`?\").
    - `frame-root` (ENSURE) given `:frame`   → `:rf.error/frame-root-given-frame`
      (\"did you mean `frame-provider`?\").
    - both-keys is covered by the same two rejectors (giving a SCOPE component
      an `:id`, or an ENSURE component a `:frame`, is the error regardless of
      whether the component's own key is also present).

  ## Where this lives

  CLJS-only (in core, like `re-frame.adapter.context`): it reaches React-shaped
  lifecycle through ONE shared React function component (`frame-root-fc`) so the
  commit-owned two-pass handling lives ONCE, not per substrate. The per-adapter
  shells (Reagent's `:r>` embed in `re-frame.views.provider`, UIx's
  `defui`) only read their props in the native idiom, DISPATCH on
  `:frame` vs `:id`, and hand a clean opts map + children to these cores — zero
  per-substrate lifecycle drift, mirroring how
  `re-frame.substrate.spine/build-frame-provider-element` factors the scope-only
  element-build. The plain-atom (JVM-runnable) build never loads this ns."
  (:require ["react"               :as React]
            [re-frame.adapter.context :as adapter-context]
            [re-frame.error        :as rf-error]
            [re-frame.frame        :as frame]
            [re-frame.live-frame   :as live-frame]))

;; ---- ENSURE: create-if-absent / reuse-if-present (no re-seed) -------------
;;
;; The whole ENSURE is ONE `make-frame` call, run at COMMIT time from
;; `frame-root-fc`'s `useLayoutEffect` (not during render). There is no
;; destroy-on-unmount, so there is no pending-destroy registry, no deferred-
;; destroy timer, and no StrictMode cancellation. The durable-state-survives-a-
;; remount property is `make-frame`'s idempotent replacement (EP-0024 §Duplicate
;; id policy) + the engine's re-record-but-don't-replay-`:initial-events`
;; re-registration (EP-0027), NOT a teardown trick.

(defn acquire-frame-root!
  "ENSURE (rf2-nyea0r; EP-0024 amended). Create the named frame if absent, REUSE
  it WITHOUT re-seeding if present, and return the resolved frame id.

  Runs the unified `make-frame` over the frame-root's `opts` (`{:id :images
  :initial-events :url-bound? …}` plus any record-config keys). Idempotent under
  the same `:id`: a re-acquire (hot reload / StrictMode remount / Story re-eval)
  takes `make-frame`'s idempotent-replacement path — the record-config + resolved
  generation refresh while durable state (app-db, sub-cache, queue) is PRESERVED
  and `:initial-events` are RE-RECORDED but NOT REPLAYED (EP-0027 §Frame
  provider). So a remount never re-seeds.

  `opts` must carry an explicit `:id` keyword — a frame-root names the frame it
  ensures, so the id is the addressing token. `make-frame`'s own validation fails
  loud on a bad shape. Returns the frame id (read off the returned frame value
  via `frame/frame-value->id`).

  CALLED FROM COMMIT PHASE (`frame-root-fc`'s `useLayoutEffect`), never from a
  render body — that is the whole point of the rf2-nyea0r split."
  [opts]
  (frame/frame-value->id (live-frame/make-frame opts)))

(defn ^:no-doc frame-root-opts
  "Normalise the frame-root's prop map into the `make-frame` opts the ENSURE
  consumes. Strips the two SUBSTRATE carriers that are not frame options — the
  `:children` carrier (substrate element macros fold trailing children onto
  `:children`) and the `:fallback` carrier (an optional pre-ready element,
  reserved for a future declared-fallback pass) — and passes EVERY OTHER key
  through: `:id`, `:images`, `:initial-events`, `:url-bound?`, and the
  record-config keys `make-frame` honours (`:fx-overrides`, `:preset`, …).
  `:initial-events` run ONCE per committed frame-id lifetime: the first creation
  runs the setup; a genuine remount / StrictMode re-acquire under the same id
  RE-RECORDS but does NOT replay (idempotent re-registration preserves durable
  state)."
  [props]
  (dissoc props :children :fallback))

(defn ^:no-doc require-frame-root-id!
  "Validate the frame-root's `:id` (rf2-nyea0r; EP-0024 amended). A frame-root
  names the frame it ensures, so `:id` is mandatory and must be a keyword — it is
  the addressing token descendants route to and the key `make-frame` ensures
  under. A missing / nil / non-keyword `:id` is a CONFIGURATION ERROR: emit +
  throw `:rf.error/frame-root-missing-id` so a tooling-generated or hand-authored
  frame-root fails loudly at the boundary rather than minting an anonymous,
  unaddressable frame. (The ENSURE shape takes `:id`; the SCOPE-only shape takes
  `:frame` — a `:frame` key is rejected up front by `reject-frame-root-frame!`,
  so a scope-shaped prop map never reaches here.) Returns the keyword `:id`."
  [id where-sym]
  (if (keyword? id)
    id
    (rf-error/throw-error!
      :rf.error/frame-root-missing-id
      where-sym
      (str "frame-root is the ENSURE component (rf2-nyea0r): it CREATES the frame "
           "if absent and REUSES it if present, so it needs an explicit keyword "
           "`:id` to ensure under. Got " (pr-str id) ". Pass `{:id :your/frame …}` "
           "(plus optional :images / :initial-events / :url-bound?). To merely "
           "SCOPE descendants to a frame that ALREADY EXISTS, use "
           "`frame-provider {:frame :your/frame}`.")
      {:recovery :supply-frame-id
       :extra    {:received id}})))

;; ---- did-you-mean: reject the sibling component's key (rf2-nyea0r) ---------
;;
;; The split gives each component ONE verb and ONE required key. Each shell
;; rejects the OTHER component's key up front with a diagnostic that names the
;; sibling, so a mistyped call fails at the boundary pointing at the right
;; component rather than mis-dispatching (or minting a phantom frame).

(defn ^:no-doc reject-frame-provider-id!
  "The SCOPE-only `frame-provider` was given an `:id`. `:id` is the ENSURE
  key — providers SCOPE an already-live frame; they do not create one. Fail loud
  with `:rf.error/frame-provider-given-id`, naming `frame-root` (rf2-nyea0r).
  Covers the both-keys case too: a `{:frame … :id …}` prop map on a provider is
  still an `:id`-on-a-provider error. Never returns (always throws)."
  [id where-sym]
  (rf-error/throw-error!
    :rf.error/frame-provider-given-id
    where-sym
    (str "frame-provider is the SCOPE-only component (rf2-nyea0r): it provides an "
         "ALREADY-CREATED frame through React context and creates NOTHING, so it "
         "takes `:frame`, not `:id`. Got `:id " (pr-str id) "`. To CREATE the "
         "frame if absent (and reuse it if present, without re-seeding), use the "
         "ENSURE component `frame-root {:id " (pr-str id) " …}`. To scope an "
         "already-live frame, pass `frame-provider {:frame " (pr-str id) "}`.")
    {:recovery :use-frame-root-to-ensure
     :extra    {:received id}}))

(defn ^:no-doc reject-frame-root-frame!
  "The ENSURE `frame-root` was given a `:frame`. `:frame` is the SCOPE key —
  frame-roots ENSURE a frame under `:id`; they do not scope an existing one. Fail
  loud with `:rf.error/frame-root-given-frame`, naming `frame-provider`
  (rf2-nyea0r). Covers the both-keys case too: a `{:id … :frame …}` prop map on a
  frame-root is still a `:frame`-on-a-root error. Never returns (always throws)."
  [frame-val where-sym]
  (rf-error/throw-error!
    :rf.error/frame-root-given-frame
    where-sym
    (str "frame-root is the ENSURE component (rf2-nyea0r): it CREATES / REUSES a "
         "frame under `:id`, so it takes `:id`, not `:frame`. Got `:frame "
         (pr-str frame-val) "`. To merely SCOPE descendants to a frame that "
         "ALREADY EXISTS, use the SCOPE-only component "
         "`frame-provider {:frame " (pr-str frame-val) "}`. To create-if-absent, "
         "pass `frame-root {:id " (pr-str frame-val) " …}`.")
    {:recovery :use-frame-provider-to-scope
     :extra    {:received frame-val}}))

(defn ^:no-doc require-unchanged-root-opts!
  "Fail loud when a MOUNTED frame-root's `:id` / opts change (rf2-nyea0r
  amendment (4)). A committed frame-root scopes exactly one frame for its
  lifetime; re-pointing it at a different frame id — or a different `make-frame`
  configuration — is a CONFIGURATION ERROR, not a reconfiguration the boundary
  supports (committed reconfiguration support is unearned machinery, and the
  pre-split silent `useRef` ignore hid a real mistake). Emit + throw
  `:rf.error/frame-root-reconfigured`. `committed` is the opts recorded at the
  first successful commit; `current` is this render's opts (both already stripped
  of `:children` / `:fallback`). Called from `frame-root-fc`'s render guard only
  when the two differ. Never returns (always throws)."
  [committed current where-sym]
  (rf-error/throw-error!
    :rf.error/frame-root-reconfigured
    where-sym
    (str "frame-root's `:id` / opts changed after it mounted (rf2-nyea0r): a "
         "committed frame-root scopes ONE frame for its lifetime and does not "
         "support in-place reconfiguration. Committed with " (pr-str committed)
         ", re-rendered with " (pr-str current) ". To scope a DIFFERENT frame, "
         "give the frame-root a React `key` that changes so it remounts; to "
         "reconfigure the SAME frame (new :images / :initial-events), call "
         "`rf/make-frame` with the same `:id` directly.")
    {:recovery :remount-with-a-new-key
     :extra    {:committed committed :received current}}))

;; ---- SCOPE-only: fail-loud-if-absent guard --------------------------------
;;
;; `rf/frame-provider` provides an ALREADY-CREATED frame and creates / refreshes
;; / destroys NOTHING. Per the EP-0024 amendment ruling it FAILS LOUD when the
;; named frame is ABSENT (the scope-only guardrail Story + Xray rely on) — a
;; caller cannot silently scope a subtree to a frame that does not exist. The
;; guard lives here (beside the frame-root id guard) so both the Reagent and
;; React-hook shells validate against one place; every per-adapter scope shell
;; calls it before delegating to the scope-only provide tier.

(defn ^:no-doc require-live-frame-for-scope!
  "Fail loud when a `{:frame existing-id}` SCOPE-only `frame-provider` names a
  frame that is NOT live in the registry (never created, or destroyed). The
  scope shape provides an ALREADY-CREATED frame — scoping a subtree to an
  absent frame would silently establish a context every descendant
  subscribe / dispatch then mis-resolves, so it is a CONFIGURATION ERROR:
  emit + throw `:rf.error/frame-provider-frame-absent` pointing the caller at
  the ENSURE component (`frame-root {:id …}`) for create-if-absent. `frame-kw`
  is the already-validated keyword frame id; `where-sym` names the calling shell
  for the diagnostic. Returns the keyword when the frame is live."
  [frame-kw where-sym]
  (if (some? (frame/frame frame-kw))
    frame-kw
    (rf-error/throw-error!
      :rf.error/frame-provider-frame-absent
      where-sym
      (str "frame-provider {:frame " (pr-str frame-kw) "} is the SCOPE-only "
           "component (rf2-nyea0r): it provides an ALREADY-CREATED frame through "
           "React context and creates nothing. No live frame is registered "
           "under " (pr-str frame-kw) " — it was never created, or it has been "
           "destroyed. To CREATE the frame if absent (and reuse it if present, "
           "without re-seeding), use the ENSURE component "
           "`frame-root {:id " (pr-str frame-kw) " …}` instead; to scope an "
           "existing frame, ensure it is created (e.g. `rf/make-frame`) before "
           "the provider mounts.")
      {:recovery :ensure-or-create-the-frame
       :extra    {:frame frame-kw}})))

;; ---- the shared React lifecycle component (frame-root, TWO-PASS) ----------
;;
;; ONE React function component backs `rf/frame-root` on every React-shaped
;; substrate (Reagent via `:r>`, UIx via `createElement`). The commit-owned
;; two-pass ENSURE lives here, not per adapter (rf2-nyea0r):
;;
;;   PASS 1 (render, pre-ready): return nil. No frame exists yet, so there is
;;     nothing to scope and NO descendant subtree is emitted. A render React
;;     discards (Suspense / concurrent abort) thus creates + seeds NOTHING.
;;   COMMIT (useLayoutEffect): `make-frame` (idempotent creation), record the
;;     committed opts baseline, flip the READY state. Only a render that COMMITS
;;     reaches here. StrictMode double-invoke is safe (idempotent make-frame,
;;     re-construction does not replay :initial-events).
;;   PASS 2 (render, ready): scope the now-live frame's id to the children
;;     through the shared React context Provider.
;;
;; A mounted `:id` / opts change is a render-phase fail-loud
;; (`:rf.error/frame-root-reconfigured`) — a committed root scopes exactly one
;; frame for its lifetime. There is deliberately NO unmount effect — the
;; frame-root does not own the frame's teardown. True ownership stays explicit
;; (`make-frame` + `destroy-frame!` inside a `create-class`).

(defn ^:no-doc frame-root-fc
  "The React function component that ENSURES one frame at COMMIT time and scopes
  it to its children — ONE implementation shared by every React-shaped substrate
  (rf2-nyea0r). `props` is a JS object carrying:

    - `:rfOpts`   — the `make-frame` opts (an opaque CLJS value, already stripped
                    of `:children` / `:fallback` by `frame-root-opts`; passed
                    through React props untouched, because every per-adapter shell
                    builds this element via raw `createElement` / Reagent `:r>`,
                    both of which pass JS prop VALUES through without marshalling
                    — the keyword-mangling seam never sees it).
    - `children`  — React's STANDARD children prop (the scoped subtree). Each
                    shell hands its subtree as trailing `createElement` args
                    (Reagent's `:r>` translates trailing hiccup; UIx pass
                    native React children), so they arrive here as ordinary React
                    children.

  Two-pass, commit-owned:

    - `ready?` (a `useState` flag) is `false` on first render → return `nil`
      (PASS 1: no frame yet, so no subtree — a discarded render creates nothing).
    - A `useLayoutEffect` (empty deps, so it runs once per committed mount; twice
      under StrictMode's dev double-invoke, harmlessly, because `make-frame` is
      idempotent) calls `acquire-frame-root!`, records the committed opts baseline
      in a ref, and flips `ready?` true. The layout-phase state flip re-renders
      synchronously before paint.
    - Once `ready?`, return the shared frame Context Provider element scoping the
      ENSURED frame's id (`:id` of the opts) to the children (PASS 2).

  A MOUNTED `:id` / opts change is caught in the render guard and fails loud
  (`require-unchanged-root-opts!`) — the committed baseline in the ref is
  compared against this render's opts.

  No unmount effect: the frame-root does NOT destroy the frame on unmount
  (rf2-nyea0r / EP-0024 amendment — owned destroy-on-unmount retired). A genuine
  unmount leaves the frame live; a keyed remount re-ensures it (idempotent, no
  re-seed). True ownership stays expressible as `make-frame` + `destroy-frame!`
  inside a `create-class`."
  [^js props]
  (let [opts       (.-rfOpts props)
        children   (.-children props)
        ready+set  (React/useState false)
        ready?     (aget ready+set 0)
        set-ready  (aget ready+set 1)
        committed  (React/useRef nil)]
    ;; Render-phase fail-loud guard (rf2-nyea0r amendment 4): once the boundary
    ;; has committed under an opts baseline, a differing opts on a later render
    ;; is a reconfiguration attempt — fail loud rather than silently ignore it.
    (when-let [prev (.-current committed)]
      (when-not (= prev opts)
        (require-unchanged-root-opts! prev opts 're-frame.views.frame-boundary/frame-root-fc)))
    ;; Commit-owned ENSURE: create the frame AFTER the render commits, record the
    ;; committed baseline, then mark ready so the children render. Empty deps ⇒
    ;; the effect arms once per committed mount; StrictMode's dev double-invoke is
    ;; safe (make-frame idempotent; re-construction does not replay :initial-events).
    (React/useLayoutEffect
      (fn frame-root-ensure-effect []
        (acquire-frame-root! opts)
        (set! (.-current committed) opts)
        (set-ready (fn [_] true))
        ;; No cleanup — the frame-root never destroys the frame on unmount.
        js/undefined)
      #js [])
    (if ready?
      (apply adapter-context/provider-element
             (:id opts)
             (adapter-context/normalize-children children))
      ;; PASS 1 / SSR / pre-commit: emit no descendant subtree.
      nil)))

(defn ^:no-doc frame-root-react-element
  "Build the raw React element for `rf/frame-root`, for the React-hook substrates
  (UIx). The substrate-agnostic CORE those adapters' native `frame-root`
  shells delegate to, parallel to
  `re-frame.substrate.spine/build-frame-provider-element` for the scope-only
  `frame-provider`. Given the frame-root's already-native-read prop map `props`
  (`{:id :images :initial-events :url-bound? …}`), the React children value
  `children`, and `where-sym` (the calling shell's fn symbol, for the fail-loud
  diagnostics): reject a stray `:frame` (did-you-mean `frame-provider`),
  validate `:id`, separate the `make-frame` opts, and return a `createElement`
  over `frame-root-fc` so React ensures the frame at COMMIT time (two-pass).

  The Reagent shell does NOT use this — it embeds `frame-root-fc` via Reagent's
  `:r>` interop head (so trailing hiccup children translate to React children),
  building the element in `re-frame.views.provider`."
  [props children where-sym]
  (when (contains? props :frame)
    (reject-frame-root-frame! (:frame props) where-sym))
  (require-frame-root-id! (:id props) where-sym)
  (apply React/createElement
         frame-root-fc
         #js {:rfOpts (frame-root-opts props)}
         (adapter-context/normalize-children children)))
