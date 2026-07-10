(ns re-frame.adapter.resource-lease
  "Shared React-hook resource-lease helper for the React-shaped adapters
  (UIx / Helix). Delivers the adapter-ergonomics helper EP-0020 §Open Issue #1
  promised (rf2-cxozh4): a view-layer wrapper that mints a resource
  liveness LEASE on mount and RELEASES it on unmount, so a view can
  declaratively own a polled / cached resource for its lifetime without
  hand-wiring a Form-3 / `useEffect` per app.

  ## Why here (core, CLJS-only)

  The React-hook body is substrate-agnostic — it uses `React/useRef` /
  `React/useEffect` directly (exactly like the spine's unmount sentinel),
  not any UIx- or Helix-specific hook — so the UIx and Helix
  `use-resource-lease` surfaces re-export this ONE implementation with zero
  drift (mirroring how `use-subscribe` lives once in the spine). It lives in
  the core artefact because core already `:require`s React (via
  `re-frame.views` / `re-frame.adapter.context`), so this file adds no new
  transitive dep, and both adapters depend on core.

  ## Bundle isolation

  This ns does NOT `:require` the resources artefact — resource ownership
  is expressed purely as DATA (the two public resource events dispatched by
  keyword id, `:rf.resource/ensure` and `:rf.resource/release-owner`). An
  app that omits `day8/re-frame2-resources` simply never registers those
  events, so the helper is inert there — matching the EP framing that this
  is an adapter concern, not a runtime-contract concern.

  ## The lease contract (Spec 016 §The scoped-cache lease lifecycle)

  ACQUIRE — `[:rf.resource/ensure {:resource … :scope … :params …
             :owner [:lease …] :cause …}]` attaches the lease owner to the
             resolved scoped entry (+ owner-index), keeping it alive and, if
             the resource declares `:poll-interval-ms`, polling for the
             view's lifetime.
  RELEASE — `[:rf.resource/release-owner {:owner [:lease …]}]` drops that
             lease. When it was the last owner the entry becomes GC-eligible
             and (per EP-0020) polling stops immediately.

  ## Frame resolution + carry

  The frame is resolved at RENDER time through the same carried-invariant
  chain `use-subscribe` uses (`frame/require-current-frame!`: dynamic-var →
  React-context → loud `:rf.error/no-frame-context`), or pinned explicitly
  via the `:frame` opt. Because the `useEffect` acquire/release runs AFTER
  the render scope has unwound (an async boundary), the resolved frame is
  CLOSED OVER and both dispatches carry it as an explicit `{:frame …}` opt —
  the standard `capture-frame` carry (Spec 002 §capture-frame).

  ## Idempotency (SSR / React StrictMode double-mount)

  - SSR / JVM has no `useEffect` execution (effects never run on the
    server), so the acquire/release is a natural no-op under
    `render-to-string` — the lease is a client-lifetime concern.
  - React 18 StrictMode dev double-invokes an effect (mount → cleanup →
    mount). The lease token is minted ONCE per instance (via `useRef`,
    stable across the double-invoke), and ensure is idempotent for a
    re-attached owner (a second ensure of an already-owned entry re-attaches
    the SAME lease — no duplicate durable owner; Spec 016 §Active owners).
    release-owner drops that one lease. So the mount→cleanup→mount cycle
    settles to exactly one held lease, matching the committed instance."
  (:require ["react" :as React]
            [re-frame.frame  :as frame]
            [re-frame.router :as router]))

;; ============================================================================
;; Process-wide lease identity — the SINGLE owner mint (rf2-qdkt8y)
;; ============================================================================
;;
;; ONE counter, ONE mint, for the WHOLE framework. Every adapter family — the
;; ratom families (Reagent / reagent-slim, via `make-resource-lease-component`
;; below) and the React-hook families (UIx / Helix, via `use-resource-lease`
;; below) — draws its lease owner from HERE, so owners minted by two different
;; adapter families in one mixed-adapter process are GUARANTEED DISTINCT.
;;
;; Before rf2-qdkt8y each adapter family kept its OWN `(atom 0)` counter, so the
;; first lease from any two families collided on the identical owner `[:lease
;; 1]`. Because `:rf.resource/release-owner` drops an owner from EVERY indexed
;; resource, unmounting one family's component then cross-released another
;; family's still-live resource. A single source of owner identity removes the
;; collision by construction.

(defonce ^:private lease-owner-counter
  ;; Monotone per-runtime counter minting a globally-unique lease token — the
  ;; SOLE lease-owner counter in the framework (mirrors the spine's
  ;; `unmount-instance-counter`). Two components leasing the same resource+scope
  ;; hold INDEPENDENT leases (releasing one never drops the other), AND two
  ;; components from DIFFERENT adapter families can never collide on one owner.
  (atom 0))

(defn mint-lease-owner!
  "Mint a fresh, process-unique resource-lease OWNER in the framework-canonical
  `[:lease <n>]` shape (Spec 016 §Active owners). THE single owner mint for
  every adapter family (rf2-qdkt8y): two owners minted anywhere in one process
  are always distinct, so a mixed-adapter process can never collide two
  families' leases on one owner. Framework-internal — not part of the public
  adapter surface."
  []
  [:lease (swap! lease-owner-counter inc)])

;; ============================================================================
;; Shared ensure / release dispatch (one implementation, all families)
;; ============================================================================

(defn- dispatch-ensure!
  "Dispatch `:rf.resource/ensure` for `descriptor` under `owner` into `frame-id`,
  recording `cause`. Direct owning-ns dispatch (not the `rf/dispatch` macro) —
  framework-internal plumbing, not an application call site, so it deliberately
  carries no `:rf.trace/call-site`."
  [frame-id {:keys [resource scope params]} owner cause]
  (router/dispatch! [:rf.resource/ensure
                     {:resource resource
                      :scope    scope
                      :params   params
                      :owner    owner
                      :cause    cause}]
                    {:frame frame-id}))

(defn- dispatch-release!
  "Dispatch `:rf.resource/release-owner` for `owner` into `frame-id` — the frame
  the matching ensure targeted."
  [frame-id owner]
  (router/dispatch! [:rf.resource/release-owner {:owner owner}]
                    {:frame frame-id}))

;; ============================================================================
;; Ratom-family (Reagent / reagent-slim) shared lifecycle + component factory
;; ============================================================================
;;
;; The ratom families express the lease as a Form-3 component (mount / update /
;; unmount lifecycle methods) rather than a React hook. Everything about the
;; lease — arg normalization, render-time frame resolution, the desired/held
;; diff, ensure / release, and the re-lease ORDERING (release-old then
;; ensure-new under the SAME owner) — lives HERE, once. The two adapters differ
;; ONLY in bare substrate ops (`create-class` / `current-component`) and how the
;; frame React-context is wired onto the class (stock Reagent copies a
;; `:context-type` map key; reagent-slim's create-class caps its key set and
;; sets the static `contextType` afterwards) — supplied via
;; `make-resource-lease-component`'s `build-class`, so those substrate specifics
;; stay ISOLATED per adapter and never cross-leak.

(defn lease-args
  "Normalise a ratom `with-resource-lease` call `[descriptor & args]` into
  `{:descriptor :cause :frame :body}`. The optional `opts` map may sit between
  the descriptor and the body thunk; a leading fn is the body."
  [[descriptor & args]]
  (let [[opts body] (if (fn? (first args))
                      [nil (first args)]
                      [(first args) (second args)])]
    {:descriptor descriptor
     :cause      (get opts :cause [:lease :mount])
     :frame      (get opts :frame)
     :body       body}))

(defn resolve-lease-frame
  "Resolve the frame a lease targets, at RENDER time, honouring EP-0002 tier
  precedence (Spec 002 §Frame target resolution): an explicit `explicit-frame`
  pins the target; otherwise delegate to the canonical reader
  `frame/require-current-frame!` — dynamic-var tier (`*current-frame*`) FIRST,
  React-context tier SECOND, else the always-on `:rf.error/no-frame-context`.
  `reader` / `where` name the caller for that error (the ratom families pass the
  `with-resource-lease` marker; the hook passes the `use-resource-lease` one).

  Resolving at RENDER time (not in a commit-phase lifecycle method) keeps the
  dynamic-var tier able to win — resolving after the render scope unwound would
  see `*current-frame*` already restored (the EP-0002 inversion)."
  [explicit-frame reader where]
  (or explicit-frame
      (frame/require-current-frame! reader {:where where})))

(defn- ensure-lease!
  "Dispatch `:rf.resource/ensure` for the render-time-resolved `desired` target
  (`#js {:frame :descriptor :cause}`) under `owner`. Returns the HELD-lease
  record (frame + owner + descriptor + cause snapshot) so a later re-lease diff
  and the unmount release act on the SAME target the ensure used, even after the
  render scope has unwound."
  [^js desired owner]
  (let [frame-id   (.-frame desired)
        descriptor (.-descriptor desired)
        cause      (.-cause desired)]
    (dispatch-ensure! frame-id descriptor owner cause)
    #js {:frame frame-id :lease owner :descriptor descriptor :cause cause}))

(defn- release-lease!
  "Dispatch `:rf.resource/release-owner` for the `held` lease into the frame it
  was ensured against."
  [^js held]
  (dispatch-release! (.-frame held) (.-lease held)))

(defn lease-target-changed?
  "True when the render-time `desired` target differs from the currently-`held`
  lease on frame, descriptor, or cause — the same `[frame descriptor cause]`
  tuple the React-hook twin keys its effect on."
  [^js held ^js desired]
  (or (not= (.-frame held)      (.-frame desired))
      (not= (.-descriptor held) (.-descriptor desired))
      (not= (.-cause held)      (.-cause desired))))

;; The ratom families' frame-resolution error marker. reagent-slim is PUBLISHED
;; as `re-frame.adapter.reagent` (its in-tree `-slim` ns is renamed at
;; publication; a `-slim` symbol would survive verbatim into the shipped jar),
;; so BOTH ratom adapters name the canonical `re-frame.adapter.reagent/
;; with-resource-lease` here — rename-stable in-tree AND post-publish
;; (rf2-gjvu84).
(def ^:private ratom-lease-reader :with-resource-lease)
(def ^:private ratom-lease-where  're-frame.adapter.reagent/with-resource-lease)

(defn make-resource-lease-component
  "Build a ratom-family (Reagent / reagent-slim) `with-resource-lease` Form-3
  component. The lease IDENTITY (owner mint) and LIFECYCLE semantics (frame
  resolution, ensure on mount, re-lease on descriptor/frame/cause change,
  release on unmount) live here — ONE implementation shared by both ratom
  adapters (rf2-qdkt8y). The substrate supplies ONLY:

    :current-component  the substrate's `current-component` reader, called in
                        `:reagent-render` to stash the render-time target.
    :build-class        `(fn [class-map] -> class)` — runs the substrate's
                        `create-class` on the lifecycle-method map AND wires the
                        frame React-context onto the class. Kept per-adapter so
                        stock Reagent's `:context-type` map key and reagent-
                        slim's post-hoc static `contextType` stay ISOLATED (no
                        cross-leak) — the ONLY substrate divergence.

  A single Form-3 `create-class` (NOT a Form-2 returning one — that would mint a
  fresh component type per render and remount). Frame resolution happens in
  `:reagent-render` (render time, so the dynamic-var tier can win and the
  in-flight component is the resolving one); the render-time-resolved frame +
  descriptor + cause are stashed on the instance so the commit-phase methods act
  on the render-time target even after the render scope unwinds.

  Mount mints the per-instance owner ONCE (reused across re-leases, so a
  hot-reload re-mount settles to exactly one held lease — ensure re-attaches the
  same owner; Spec 016 §Active owners); `:component-did-update` diffs the
  render-time `[frame descriptor cause]` against the held lease and, on any
  change, RELEASES the old lease then ENSURES the new target under the SAME
  owner (so neither is the old resource over-retained nor the new one
  under-provisioned until unmount). Under SSR (`render-to-string`) lifecycle
  methods do not run, so the acquire/release is a natural no-op."
  [{:keys [current-component build-class]}]
  (build-class
    {:display-name "re-frame.adapter.reagent/with-resource-lease"
     :reagent-render
     (fn [d & args]
       (let [{:keys [cause descriptor frame body]} (lease-args (cons d args))
             frame-id (resolve-lease-frame frame ratom-lease-reader ratom-lease-where)]
         ;; Stash the render-time-resolved lease target (dynamic-var-honouring
         ;; frame + current descriptor/cause) so the commit-phase lifecycle
         ;; methods take + re-lease against it after the render scope unwinds.
         (set! (.-rf2LeaseDesired ^js (current-component))
               #js {:frame frame-id :descriptor descriptor :cause cause})
         (body)))
     :component-did-mount
     (fn [^js this]
       ;; Mint the per-instance owner ONCE (reused across re-leases, matching
       ;; the hook twin's stable useRef owner) + take the first lease.
       (set! (.-rf2ResourceLease this)
             (ensure-lease! (.-rf2LeaseDesired this) (mint-lease-owner!))))
     :component-did-update
     ;; Variadic to span both substrates: stock Reagent invokes did-update
     ;; 4-arity (prev-argv, prev-children, snapshot), reagent-slim 3-arity
     ;; (prev-argv, snapshot) per IMPL-SPEC §6.6. We read the render-time stash,
     ;; not the prev args, so the extra positional args are ignored either way.
     (fn [^js this & _]
       (let [held    (.-rf2ResourceLease this)
             desired (.-rf2LeaseDesired this)]
         (when (and held desired (lease-target-changed? held desired))
           ;; Descriptor / frame / cause changed across re-render — release the
           ;; old lease then re-ensure the new target under the SAME owner.
           (release-lease! held)
           (set! (.-rf2ResourceLease this)
                 (ensure-lease! desired (.-lease held))))))
     :component-will-unmount
     (fn [^js this]
       (when-let [held (.-rf2ResourceLease this)]
         (release-lease! held)))}))

;; ============================================================================
;; React-hook family (UIx / Helix) — `use-resource-lease`
;; ============================================================================

(defn use-resource-lease
  "React hook (UIx / Helix): take a resource liveness LEASE for the calling
  component's mounted lifetime. On mount it dispatches `:rf.resource/ensure`
  with an app-minted `[:lease …]` owner; on unmount it dispatches
  `:rf.resource/release-owner` for that same lease. Returns nil (a lifecycle
  hook, not a read — pair it with `use-subscribe` on a `[:rf.resource/*]`
  query to read the data).

  Call shapes:

      (use-resource-lease {:resource :my/feed
                           :scope    :rf.scope/global
                           :params   {:page 0}})

      (use-resource-lease {:resource :my/feed :scope … :params …}
                          {:cause :dashboard-widget   ;; recorded on the ensure
                           :frame :some-frame})       ;; explicit-frame pin

  `descriptor` is the resource-instance identity — `{:resource :scope
  :params}` — exactly the ensure payload's read keys (Spec 016 §Events).

  `opts`:
    :cause  — the `:cause` recorded on the ensure (observability; a
              free-form data value). Defaults to `[:lease :mount]`.
    :frame  — pin the lease to an explicit frame id, bypassing the ambient
              frame-provider / dynamic-var resolution.

  Frame resolution (1-arg / no `:frame`): the surrounding `frame-provider`'s
  keyword via the carried-invariant chain (`frame/require-current-frame!`),
  raising `:rf.error/no-frame-context` when no frame is in scope — the same
  contract as `use-subscribe`.

  Re-lease semantics: changing the resolved frame, the descriptor, or the
  cause tears down the old lease (release-owner) and takes a fresh one
  (ensure) — the effect is keyed on those values. A stable descriptor across
  re-renders holds ONE lease with no churn."
  ([descriptor] (use-resource-lease descriptor nil))
  ([descriptor {:keys [cause frame] :as _opts}]
   ;; Resolve the frame through the full carried-invariant chain at RENDER
   ;; time — an explicit `:frame` opt wins; otherwise dynamic-var →
   ;; React-context → loud `:rf.error/no-frame-context`. Resolving here (not
   ;; inside the effect) captures the render-time frame to close over, since
   ;; the effect runs after the render scope unwinds.
   (let [frame-id (resolve-lease-frame
                    frame :use-resource-lease
                    're-frame.adapter.resource-lease/use-resource-lease)
         ;; One stable lease OWNER per mounted instance (survives re-renders AND
         ;; React StrictMode's mount→cleanup→mount double-invoke), drawn from the
         ;; single framework owner mint so a mixed-adapter process can't collide.
         owner-ref (React/useRef nil)
         owner     (or (.-current owner-ref)
                       (let [o (mint-lease-owner!)]
                         (set! (.-current owner-ref) o)
                         o))
         cause     (or cause [:lease :mount])
         {:keys [resource scope params]} descriptor
         ;; Stable primitive deps for React's `Object.is` comparison: the
         ;; descriptor / cause are value-equal but fresh JS objects per
         ;; render, so key the effect on a printed digest rather than the
         ;; live objects (the same stable-key discipline the spine's
         ;; use-subscribe uses for its query vector). The owner is stable per
         ;; instance, so it never triggers a re-lease.
         deps-key  (pr-str [frame-id owner resource scope params cause])]
     (React/useEffect
       (fn arm-lease []
         (dispatch-ensure! frame-id descriptor owner cause)
         (fn release-lease []
           (dispatch-release! frame-id owner)))
       #js [deps-key])
     nil)))
