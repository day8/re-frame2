(ns re-frame.views.frame-boundary
  "Substrate-agnostic cores for the two frame-boundary components, the
  ENSURE `rf/frame-root` and the SCOPE-only `rf/frame-provider` — *roots
  ensure; providers scope*. Spec 002 §`frame-root` — the ENSURE component
  and §`frame-provider` — the SCOPE-only component hold the contract
  (EP-0024 §Scope, carry, and ownership are separate).

  The per-substrate shells — Reagent's in `re-frame.views.provider`, UIx's
  `defui`s, Fresco's boundary heads — read their props in their own idiom,
  run the did-you-mean rejectors and guards here, and hand a clean opts map
  and children down. frame-root's commit-owned two-pass lifecycle lives once,
  in `frame-root-fc`, so no substrate carries a copy to drift, as
  `re-frame.substrate.spine/build-frame-provider-element` does for the
  scope-only element build.

  CLJS-only, like `re-frame.adapter.context`; the JVM build never loads it."
  (:require ["react"               :as React]
            [re-frame.adapter.context :as rf.adapter.context]
            [re-frame.error        :as rf.error]
            [re-frame.frame        :as rf.frame]
            [re-frame.live-frame   :as rf.live-frame]))

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
  "ENSURE: create the named frame if absent, reuse it without re-seeding if
  present, and return its id. One `make-frame` over the frame-root's `opts`,
  whose idempotent replacement under the same `:id` is what makes a remount,
  a StrictMode re-invoke or a hot reload safe (Spec 002 §`frame-root` — the
  ENSURE component). `make-frame` validates the opts. Called from
  `frame-root-fc`'s commit-phase `useLayoutEffect`, never from a render body."
  [opts]
  (rf.frame/frame-value->id (rf.live-frame/make-frame opts)))

(defn ^:no-doc frame-root-opts
  "Normalise the frame-root's prop map into the `make-frame` opts the ENSURE
  consumes. Strips the two SUBSTRATE carriers that are not frame options — the
  `:children` carrier (substrate element macros fold trailing children onto
  `:children`) and the `:fallback` carrier (an optional pre-ready element,
  reserved: the boundary renders `nil` before it is ready) — and passes
  EVERY OTHER key through: `:id`, `:images`, `:initial-events`, `:url-bound?`, and the
  record-config keys `make-frame` honours (`:fx-overrides`, `:preset`, …)."
  [props]
  (dissoc props :children :fallback))

(defn ^:no-doc require-frame-root-id!
  "Validate the frame-root's `:id` (EP-0024). A frame-root
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
    (rf.error/throw-error!
      :rf.error/frame-root-missing-id
      where-sym
      (str "frame-root is the ENSURE component: it CREATES the frame "
           "if absent and REUSES it if present, so it needs an explicit keyword "
           "`:id` to ensure under. Got " (pr-str id) ". Pass `{:id :your/frame …}` "
           "(plus optional :images / :initial-events / :url-bound?). To merely "
           "SCOPE descendants to a frame that ALREADY EXISTS, use "
           "`frame-provider {:frame :your/frame}`.")
      {:recovery :supply-frame-id
       :extra    {:received id}})))

;; ---- did-you-mean: reject the sibling component's key --------------------
;;
;; Each component has ONE verb and ONE required key. Each shell
;; rejects the OTHER component's key up front with a diagnostic that names the
;; sibling, so a mistyped call fails at the boundary pointing at the right
;; component rather than mis-dispatching (or minting a phantom frame).

(defn ^:no-doc reject-frame-provider-id!
  "The SCOPE-only `frame-provider` was given an `:id`. `:id` is the ENSURE
  key — providers SCOPE an already-live frame; they do not create one. Fail loud
  with `:rf.error/frame-provider-given-id`, naming `frame-root`.
  Covers the both-keys case too: a `{:frame … :id …}` prop map on a provider is
  still an `:id`-on-a-provider error. Never returns (always throws)."
  [id where-sym]
  (rf.error/throw-error!
    :rf.error/frame-provider-given-id
    where-sym
    (str "frame-provider is the SCOPE-only component: it provides an "
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
  loud with `:rf.error/frame-root-given-frame`, naming `frame-provider`.
  Covers the both-keys case too: a `{:id … :frame …}` prop map on a
  frame-root is still a `:frame`-on-a-root error. Never returns (always throws)."
  [frame-val where-sym]
  (rf.error/throw-error!
    :rf.error/frame-root-given-frame
    where-sym
    (str "frame-root is the ENSURE component: it CREATES / REUSES a "
         "frame under `:id`, so it takes `:id`, not `:frame`. Got `:frame "
         (pr-str frame-val) "`. To merely SCOPE descendants to a frame that "
         "ALREADY EXISTS, use the SCOPE-only component "
         "`frame-provider {:frame " (pr-str frame-val) "}`. To create-if-absent, "
         "pass `frame-root {:id " (pr-str frame-val) " …}`.")
    {:recovery :use-frame-provider-to-scope
     :extra    {:received frame-val}}))

(defn ^:no-doc same-root-opts?
  "True when two frame-root opts are the same configuration: equal as
  values, with any two fns in matching positions counted equal. A fn written
  inline in render (`:fx-overrides {:http (fn ...)}`, an interceptor built
  per render) is a new object on every render, so comparing fns by identity
  would refuse every parent re-render of a root that changed nothing."
  [a b]
  (cond
    (identical? a b)          true
    (and (fn? a) (fn? b))     true
    (and (map? a) (map? b))   (and (= (count a) (count b))
                                   (every? (fn [[k v]]
                                             (and (contains? b k)
                                                  (same-root-opts? v (get b k))))
                                           a))
    (and (sequential? a)
         (sequential? b))     (and (= (count a) (count b))
                                   (every? true? (map same-root-opts? a b)))
    :else                     (= a b)))

(defn ^:no-doc require-unchanged-root-opts!
  "Fail loud when a MOUNTED frame-root's `:id` / opts change. A committed
  frame-root scopes exactly one frame for its lifetime; re-pointing it at a
  different frame id — or a different `make-frame` configuration — is a
  CONFIGURATION ERROR, not a reconfiguration the boundary supports (committed
  reconfiguration support would be unearned machinery, and silently ignoring
  the change would hide a real mistake). Emit + throw
  `:rf.error/frame-root-reconfigured`. `committed` is the opts recorded at the
  first successful commit; `current` is this render's opts (both already stripped
  of `:children` / `:fallback`). Called from `frame-root-fc`'s render guard only
  when the two differ. Never returns (always throws)."
  [committed current where-sym]
  (rf.error/throw-error!
    :rf.error/frame-root-reconfigured
    where-sym
    (str "frame-root's `:id` / opts changed after it mounted: a "
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
;; The guard lives here, beside the frame-root id guard, so every per-adapter
;; scope shell validates against one place before delegating to its provide
;; tier.

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
  (if (some? (rf.frame/frame frame-kw))
    frame-kw
    (rf.error/throw-error!
      :rf.error/frame-provider-frame-absent
      where-sym
      (str "frame-provider {:frame " (pr-str frame-kw) "} is the SCOPE-only "
           "component: it provides an ALREADY-CREATED frame through "
           "React context and creates nothing. No live frame is registered "
           "under " (pr-str frame-kw) " — it was never created, or it has been "
           "destroyed. To CREATE the frame if absent (and reuse it if present, "
           "without re-seeding), use the ENSURE component "
           "`frame-root {:id " (pr-str frame-kw) " …}` instead; to scope an "
           "existing frame, ensure it is created (e.g. `rf/make-frame`) before "
           "the provider mounts.")
      {:recovery :ensure-or-create-the-frame
       :extra    {:frame frame-kw}})))

;; ---- an adopting root cannot adopt a frame-root ---------------------------
;;
;; The first pass renders nothing, and React keeps an unclaimed server node
;; directly under a root without reporting it, so a hydrating root topped by a
;; frame-root keeps the server's markup and the second pass mounts the client
;; tree beside it: the page twice, with no mismatch reported. The spine's
;; `adoption-window-closer` provides the root's adoption flag through
;; `rf.adapter.context/adoption-context`, so the boundary refuses in its first
;; render, before React commits, while that flag is up.

(defn- refuse-adopting-frame-root!
  "Refuse a `frame-root` rendered while its root is still adopting the
  server's markup, naming the boot that adopts instead: make the frame,
  install the payload, scope it with `frame-provider`."
  [frame-kw]
  (rf.error/throw-error!
    :rf.error/fresco-frame-root-adopting
    're-frame.views.frame-boundary/frame-root-fc
    (str "frame-root cannot be adopted by a hydrating root: its first render "
         "is empty — it makes the frame at commit and its children arrive on "
         "a second pass — so React would keep the server's markup and mount "
         "the client tree beside it, showing the page twice. A hydrating root "
         "scopes a frame that already exists. Make it with (rf/make-frame "
         "{:id " (pr-str frame-kw) "}), install the payload with (ssr/hydrate! "
         "{:frame " (pr-str frame-kw) "}), then hydrate a tree topped by "
         "frame-provider {:frame " (pr-str frame-kw) "}.")
    {:extra {:frame frame-kw}}))

;; ---- the shared React lifecycle component (frame-root, TWO-PASS) ----------
;;
;; ONE React function component backs `rf/frame-root` on every React-shaped
;; substrate — Reagent through `:r>`, UIx and Fresco through
;; `frame-root-react-element` — so the commit-owned two-pass ENSURE lives here,
;; not per adapter.

(defn ^:no-doc frame-root-fc
  "The React function component that ENSURES one frame at COMMIT time and scopes
  it to its children, in the two passes Spec 002 §`frame-root` — the ENSURE
  component specifies. `props` is a JS object carrying:

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

  `ready?` is false on the first render, which returns nil; the empty-deps
  `useLayoutEffect` ensures the frame, records the committed opts in a ref and
  flips `ready?`, and the ready render returns the Provider. A later render
  whose opts differ from the committed ones fails loud
  (`require-unchanged-root-opts!`), and a render under an adopting root refuses
  (`refuse-adopting-frame-root!`). It arms no unmount effect: the boundary does
  not own the frame's teardown."
  [^js props]
  (let [opts       (.-rfOpts props)
        children   (.-children props)
        adoption   (React/useContext rf.adapter.context/adoption-context)
        ready+set  (React/useState false)
        ready?     (aget ready+set 0)
        set-ready  (aget ready+set 1)
        committed  (React/useRef nil)]
    ;; Under a root still adopting the server's markup the empty first pass
    ;; would leave that markup in place and mount the tree beside it: refuse.
    (when (and (some? adoption) (.-adopting ^js adoption))
      (refuse-adopting-frame-root! (:id opts)))
    ;; Render-phase fail-loud guard: once the boundary
    ;; has committed under an opts baseline, a differing opts on a later render
    ;; is a reconfiguration attempt — fail loud rather than silently ignore it.
    (when-let [prev (.-current committed)]
      (when-not (same-root-opts? prev opts)
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
      (apply rf.adapter.context/provider-element
             (:id opts)
             (rf.adapter.context/normalize-children children))
      ;; PASS 1 / SSR / pre-commit: emit no descendant subtree.
      nil)))

(defn ^:no-doc frame-root-react-element
  "Build the raw React element for `rf/frame-root`, for UIx's `frame-root` and
  Fresco's boundary head. The substrate-agnostic CORE those shells delegate to,
  parallel to
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
         (rf.adapter.context/normalize-children children)))
