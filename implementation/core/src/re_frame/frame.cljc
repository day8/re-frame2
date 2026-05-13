(ns re-frame.frame
  "Frame container, lifecycle, and the frame registry. Per Spec 002.

  A frame is an isolated runtime boundary identified by a keyword. Every
  frame holds its own app-db (a substrate-managed reactive container),
  its own per-frame router queue, and its own sub-cache.

  Frames are not values — they are mutable runtime objects. User code
  holds keywords; this namespace holds the frame records.

  Reserved frame ids:
    :rf/default              — universal default frame (always present)
    :rf.frame/<gensym>       — anonymous instances from make-frame"
  (:require [clojure.string]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the frame record -----------------------------------------------------
;;
;; Per Spec 002 §What lives in a frame, a frame is a map with:
;;   :id          the keyword identity
;;   :app-db      substrate-managed reactive container (opaque; through adapter)
;;   :router      per-frame queue + drain-state FSM (defined in router.cljc)
;;   :sub-cache   per-frame sub-cache (defined in subs.cljc)
;;   :lifecycle   {:created-at :destroyed? :listeners}
;;   :config      the metadata that reg-frame was given
;;
;; Frame records are stored in `frames` keyed by id.

(defonce
  ^{:doc "Map of frame-id → frame-record. Per-process (one global frame registry)."}
  frames
  (atom {}))

;; ---- frame resolution at call sites --------------------------------------
;;
;; *current-frame* is the dynamic var that with-frame binds. Subscribe and
;; dispatch default to (current-frame) when no :frame is supplied, so views
;; nested under a (with-frame ...) wrapper or a Reagent frame-provider
;; auto-route to the right frame.

(def ^:dynamic *current-frame* nil)

(defn current-frame
  "Resolution chain: dynamic var → :rf/default. CLJS-side
  re-frame.views extends this with a React-context lookup."
  []
  (or *current-frame* :rf/default))

(defn resolve-current-frame
  "Resolve the active frame at a no-explicit-frame call site. The
  3-tier resolution chain — dynamic var → React context → `:rf/default` —
  per Spec 002 §Reading the frame from React context and Spec 006
  §Lookup algorithm.

  Per rf2-d4sf, on CLJS this consults the `:adapter/current-frame`
  late-bind hook so the React-context tier is LIVE — adapters publish
  their React-context-aware impl through the hook at ns-load time.
  When the hook is unbound (no adapter loaded yet, or JVM build) the
  fallback is `current-frame` which honours the dynamic-var tier and
  the `:rf/default` tier; the React-context tier silently no-ops in
  that case.

  This is the canonical 3-tier resolver — `subs/subscribe`,
  `router/dispatch*`'s default-frame computation, and
  `core/current-frame` all delegate here so the React-context tier is
  single-sourced (rf2-jj8xf)."
  []
  #?(:cljs (if-let [f (late-bind/get-fn :adapter/current-frame)]
             (f)
             (current-frame))
     :clj  (current-frame)))

;; ---- lookup ---------------------------------------------------------------

(defn frame
  "Return the frame record for id, or nil if not registered or destroyed."
  [id]
  (when-let [f (get @frames id)]
    (when-not (get-in f [:lifecycle :destroyed?])
      f)))

(defn frame-meta
  "Per Spec 002 §The public registrar query API and Spec-Schemas
  §`:rf/frame-meta`: return the effective metadata map for a frame as a
  flat shape — `:id` plus the post-preset-expansion user-supplied
  metadata keys (`:preset`, `:fx-overrides`, `:drain-depth`, `:doc`,
  `:tags`, `:url-bound?`, `:platform`, `:on-error`, `:ssr`, …) merged
  with the lifecycle fields (`:created-at`, `:destroyed?`, `:listeners`).

  Per Spec 002 §Frame presets, the `:preset` key is preserved verbatim
  on the returned map so tools can inspect which preset was applied; the
  expansion keys appear at the top level alongside it. The internal
  storage groupings (`:config` / `:lifecycle` on the frame record) are
  flattened away — tools must not depend on the registry's storage
  organisation, only on the canonical `:rf/frame-meta` shape."
  [id]
  (when-let [f (frame id)]
    (merge (:config f)
           (:lifecycle f)
           {:id (:id f)})))

(defn frame-ids
  "All registered, non-destroyed frame ids.

  Two arities:
    (frame-ids)
      Return the full id set.
    (frame-ids ns-prefix)
      Return the subset whose id-namespace starts with `ns-prefix`
      (a string). Namespaceless ids (e.g. `:rf/default`'s namespace is
      `\"rf\"` — keyword-namespace, not value-namespace) are matched
      against the keyword's `namespace` component; ids with no
      namespace are excluded.

  Per Spec 002 §The public registrar query API."
  ([]
   (into #{}
         (comp (filter (fn [[_ f]] (not (get-in f [:lifecycle :destroyed?]))))
               (map key))
         @frames))
  ([ns-prefix]
   (let [prefix (str ns-prefix)]
     (into #{}
           (comp (filter (fn [[_ f]] (not (get-in f [:lifecycle :destroyed?]))))
                 (map key)
                 (filter (fn [k]
                           (when-let [ns (namespace k)]
                             (clojure.string/starts-with? ns prefix)))))
           @frames))))

(defn get-frame-db
  "Return the underlying app-db container for the frame. Tools and tests
  use this; user handlers receive db via cofx."
  [id]
  (:app-db (frame id)))

(defn frame-app-db-value
  "Read the current app-db value for a frame as a plain map (deref through
  the substrate adapter)."
  [id]
  (when-let [container (get-frame-db id)]
    (adapter/read-container container)))

;; ---- frame presets (Spec 002 §Frame presets) ------------------------------
;;
;; A :preset key in metadata expands at registration time into a fixed
;; bundle of metadata keys. User-supplied keys win on conflict.
;; Per Spec 002 §Frame presets, the v1 closed list is:
;;   :default :test :story :ssr-server

(defn- preset-expansion [preset]
  ;; Per Spec 002 §Frame presets and Spec-Schemas §:rf/preset-expansion.
  ;; The four canonical expansions:
  ;;   :default    -> {} (explicit no-op; identical to omitting :preset)
  ;;   :test       -> redirect :rf.http/managed to its canned-success stub
  ;;                  (Spec 014); explicit :drain-depth 100 (matches the
  ;;                  framework default — surfaced so tooling can read the
  ;;                  bound off frame-meta without consulting the global default).
  ;;   :story      -> same HTTP redirect as :test; tighter :drain-depth 16
  ;;                  so a runaway dispatch cascade fails fast under a story.
  ;;   :ssr-server -> :platform :server (gates fx via reg-fx :platforms);
  ;;                  :on-error :rf.error/server-projection (server-side
  ;;                  exception projection per Spec 011).
  ;; User-supplied keys win on conflict; see expand-preset.
  (case preset
    :default    {}
    :test       {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}
                 :drain-depth  100}
    :story      {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}
                 :drain-depth  16}
    :ssr-server {:platform     :server
                 :on-error     :rf.error/server-projection}
    nil         {}
    (throw (ex-info ":rf.error/unknown-preset"
                    {:preset preset
                     :valid  #{:default :test :story :ssr-server}}))))

(defn- expand-preset [metadata]
  (let [preset    (:preset metadata)
        expansion (preset-expansion preset)]
    ;; user-supplied keys win on conflict
    (merge expansion metadata)))

;; ---- registration ---------------------------------------------------------

(defn- new-frame-record [id config]
  {:id         id
   :app-db     (adapter/make-state-container {})
   :router     (atom {:queue interop/empty-queue :scheduled? false})
   ;; Per rf2-ynk7 §single-drainer invariant: a separate CAS-able cell
   ;; that admits at most one thread into `drain!` at a time. On the JVM
   ;; the executor's `next-tick` callback can wake while the calling
   ;; thread is mid-drain (e.g. `dispatch-sync!`); without this guard,
   ;; both threads' peek+pop sequence on `:queue` is non-atomic and
   ;; double-processes / drops envelopes (the rf2-lmkk #442 flake). The
   ;; loser of the CAS no-ops; the winning drainer rechecks the queue
   ;; before releasing the flag so envelopes queued in the gap are not
   ;; orphaned. CLJS is single-threaded so the CAS is uncontended there,
   ;; but the same flag preserves the contract under any future
   ;; concurrent host.
   :drain-lock (atom false)
   :sub-cache  (atom {})
   :lifecycle  {:created-at (interop/now-ms)
                :destroyed? false
                :listeners  []}
   :config     config})

(declare destroy-frame!)

(defn reg-frame
  "Atomic create-and-register. Per Spec 002 §reg-frame is atomic:
  - If the id is unregistered, create the frame container, run :on-create
    events synchronously, return the keyword.
  - If the id is already registered, perform a SURGICAL UPDATE: existing
    runtime state (app-db, sub-cache, queue) is preserved; only the
    metadata/config is replaced. Hot-reload Just Works."
  [id metadata]
  (let [config (source-coords/merge-coords (expand-preset metadata))]
    (registrar/register! :frame id config)
    (let [existing (get @frames id)]
      (cond
        ;; First registration: create everything.
        (nil? existing)
        (let [f (new-frame-record id config)]
          (swap! frames assoc id f)
          ;; Run :on-create events synchronously BEFORE emitting :frame/created.
          ;; Per Spec 002 §Frame creation: on-create completes first, then
          ;; the frame is observable to listeners. The router/dispatch
          ;; namespace handles dispatch-sync; we forward via the late-bind
          ;; registry to avoid a cyclic dep at compile time. (`resolve` is
          ;; not a runtime fn in CLJS, so the older `(resolve 'router/...)`
          ;; pattern silently no-op'd in CLJS — see rf2-p8g8.)
          (when-let [on-create (:on-create config)]
            (when-let [dispatch-sync (late-bind/get-fn :router/dispatch-sync!)]
              (dispatch-sync on-create {:frame id})))
          (trace/emit! :frame :frame/created
                       {:frame id :config config})
          id)

        ;; Re-registration: surgical update of replaceable slots only.
        ;; Per Spec 002 §Re-registration — surgical update.
        :else
        (do
          (swap! frames update id assoc :config config)
          (trace/emit! :frame :frame/re-registered
                       {:frame id :config config})
          id)))))

(defn make-frame
  "Anonymous-instance creation. Generates a gensym'd id under :rf.frame/.
  Returns the gensym'd id. Per Spec 002 §Per-instance frames."
  [config]
  (let [id (keyword "rf.frame" (str (gensym "")))]
    (reg-frame id config)
    id))

;; ---- destruction ----------------------------------------------------------
;;
;; destroy-frame! runs an ordered teardown. Each step lives in its own
;; named helper so the body of destroy-frame! reads as documentation; the
;; helper names ARE the step list. Order matters — see destroy-frame!'s
;; docstring and Spec 002 §Destroy.

(defn- fire-on-destroy-event!
  "Step 1. Fire the user-supplied :on-destroy event before any lifecycle
  mutation, so handlers still observe an alive frame. No-op when the
  frame has no :on-destroy or when the router artefact is absent."
  [id f]
  (when-let [on-destroy (get-in f [:config :on-destroy])]
    (when-let [dispatch-sync (late-bind/get-fn :router/dispatch-sync!)]
      (dispatch-sync on-destroy {:frame id}))))

(defn- notify-machine-destruction!
  "Step 2 (with interleaved 2a). For every machine that has an active
  snapshot under [:rf/machines ...] in app-db:

    2a. Fire the rf2-wvkn HTTP abort hook (Spec 005 §Cancellation
        cascade — frame-destroy is a documented destroy trigger).
        Late-bound; nil when the http artefact is absent. Runs BEFORE
        the trace event so observers see abort-then-destroy ordering.
    2.  Emit ONE :rf.machine.lifecycle/destroyed trace per actor
        snapshot, carrying :reason :parent-frame-destroyed under :tags.
        Pairs with :rf.machine.lifecycle/created emitted at reg-machine
        so lifecycle observers see one consistent op-type for create
        AND destroy across every cause and code path. The :reason tag
        discriminates why the actor went away — frame-exit emits
        :parent-frame-destroyed; the fx-substrate's :rf.machine/destroyed
        emit-site (lifecycle_fx.cljc) carries the other reasons
        (:rf.machine/finished, :explicit, :parent-unmount-cascade).

  Full automatic exit-cascade would require storing every machine def
  in a registry, which is out of scope for the v1 closed kind set."
  [id]
  (let [container  (get-frame-db id)
        db         (when container (adapter/read-container container))
        machines   (get db :rf/machines)
        abort-http (late-bind/get-fn :http/abort-on-actor-destroy)]
    (doseq [[machine-id snapshot] machines]
      (when abort-http
        (try (abort-http machine-id)
             (catch #?(:clj Throwable :cljs :default) _ nil)))
      (trace/emit! :rf.machine.lifecycle/destroyed :rf.machine.lifecycle/destroyed
                   {:frame      id
                    :machine-id machine-id
                    :last-state (:state snapshot)
                    :reason     :parent-frame-destroyed}))))

(defn- mark-frame-destroyed!
  "Step 3. Flip :lifecycle :destroyed? so subsequent dispatch / subscribe
  against this frame raises :rf.error/frame-destroyed. Done before
  sub-cache disposal so a racing reaction can't re-enter on a frame
  that's mid-teardown."
  [id]
  (swap! frames update id assoc-in [:lifecycle :destroyed?] true))

(defn- tear-down-sub-cache!
  "Step 4. Walk every cached reaction in the frame's sub-cache and
  dispose it; clear the cache atom. Disposal exceptions are swallowed
  so one bad on-dispose can't block the rest of teardown."
  [f]
  (when-let [cache (:sub-cache f)]
    (doseq [[_k entry] @cache]
      (when-let [r (:reaction entry)]
        (try (interop/dispose! r)
             (catch #?(:clj Throwable :cljs :default) _ nil))))
    (reset! cache {})))

(defn- emit-frame-destroyed-trace!
  "Step 5. Emit the :frame/destroyed trace, AFTER the per-machine
  cascade so listeners see machines-then-frame ordering."
  [id]
  (trace/emit! :frame :frame/destroyed
               {:frame id}))

(defn- dissoc-frame!
  "Step 6. Remove the frame record from the `frames` atom so subsequent
  lookups return nil."
  [id]
  (swap! frames dissoc id))

(defn- unregister-frame!
  "Step 7. Per Spec 001 §Hot-reload semantics — drop the frame from the
  registrar, surfacing :rf.registry/handler-cleared so tools tracking
  the live registry observe the slot freed."
  [id]
  (registrar/unregister! :frame id))

(defn- notify-epoch-listeners!
  "Step 8. Per Tool-Pair §Surface behaviour against destroyed frames
  (rf2-d656): emit a one-shot :rf.epoch.cb/silenced-on-frame-destroy
  for every register-epoch-cb! listener that previously observed this
  frame. Late-bound through the hook table so core never statically
  requires the epoch artefact."
  [id]
  (when-let [on-destroyed (late-bind/get-fn :epoch/on-frame-destroyed)]
    (try (on-destroyed id)
         (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn destroy-frame!
  "Tear down a frame. Per Spec 002 §Destroy, the ordered steps are:

    1. fire-on-destroy-event!       — run the user :on-destroy event
                                      while the frame is still alive.
    2. notify-machine-destruction!  — for each active machine snapshot:
                                      (2a) fire the rf2-wvkn HTTP abort
                                      hook, then emit the unified
                                      :rf.machine.lifecycle/destroyed
                                      trace (one per snapshot) carrying
                                      :reason :parent-frame-destroyed.
    3. mark-frame-destroyed!        — flip :lifecycle :destroyed?.
    4. tear-down-sub-cache!         — dispose every cached reaction.
    5. emit-frame-destroyed-trace!  — emit :frame/destroyed.
    6. dissoc-frame!                — remove from the `frames` atom.
    7. unregister-frame!            — drop from the registrar.
    8. notify-epoch-listeners!      — fire the rf2-d656 epoch hook.

  Two cleanup hooks fire between step 4 and step 5 — both are best-
  effort and tolerate the hook artefact being absent at runtime:

    :privacy/clear-suppression-cache!   — rf2-isdwf, reset the warn-once
                                          cache so a re-registered frame
                                          re-emits the sensitive-without-
                                          redaction warning if mis-config.
    :ssr/on-frame-destroyed             — rf2-fcj33, drop the SSR
                                          side-channel atoms' entries
                                          for the destroyed frame
                                          (pending-error-traces +
                                          request-slots). Without this
                                          hook those two defonce atoms
                                          accumulate one entry per
                                          request under SSR load — a
                                          slow leak that compounds over
                                          a long-running server process.
    :machines/on-frame-destroyed!       — rf2-ysa94, clear the machines
                                          artefact's frame-scoped
                                          `:after` timer table for the
                                          destroyed frame and release
                                          any in-flight host-clock
                                          handles / subscription
                                          watchers belonging to that
                                          frame. Without this hook the
                                          destroyed frame's inner table
                                          would linger and live timers
                                          would survive teardown.

  Subsequent dispatch / subscribe against a destroyed frame raises
  :rf.error/frame-destroyed."
  [id]
  (when-let [f (frame id)]
    (fire-on-destroy-event! id f)
    (notify-machine-destruction! id)
    (mark-frame-destroyed! id)
    (tear-down-sub-cache! f)
    ;; Per rf2-isdwf / Spec 009 §Privacy: reset the
    ;; sensitive-without-redaction warning suppression cache so a
    ;; re-registration after frame teardown (test fixtures, hot
    ;; reload that destroys and re-creates the frame) re-emits the
    ;; warning if still mis-configured. Late-bound through the hook
    ;; table; no-op when re-frame.privacy hasn't been loaded.
    (when-let [clear-cache! (late-bind/get-fn :privacy/clear-suppression-cache!)]
      (try (clear-cache!)
           (catch #?(:clj Throwable :cljs :default) _ nil)))
    ;; Per rf2-fcj33 / Spec 011 §Per-request frame teardown contract:
    ;; clear the SSR side-channel atoms (pending-error-traces +
    ;; request-slots) for the destroyed frame. Both live outside app-db
    ;; for documented design reasons (the buffered-traces sidestep an
    ;; app-db clobber race; the request slot keeps host-controlled
    ;; data out of the hydration payload). Neither is otherwise cleared
    ;; by destroy-frame's app-db / sub-cache teardown, so under SSR
    ;; load each request would leak one entry in each atom. Late-bound
    ;; through the hook table; no-op when re-frame.ssr is absent.
    (when-let [ssr-cleanup! (late-bind/get-fn :ssr/on-frame-destroyed)]
      (try (ssr-cleanup! id)
           (catch #?(:clj Throwable :cljs :default) _ nil)))
    ;; Per rf2-ysa94 / Spec 005 §Delayed `:after` transitions: clear the
    ;; machines artefact's frame-scoped `:after` timer table for the
    ;; destroyed frame. The table is partitioned per frame; without this
    ;; hook the destroyed frame's inner-table would linger as dead
    ;; bookkeeping and any in-flight host-clock handles would survive
    ;; teardown. Late-bound through the hook table — no-op when
    ;; re-frame.machines is absent (the artefact is optional).
    (when-let [machines-cleanup! (late-bind/get-fn :machines/on-frame-destroyed!)]
      (try (machines-cleanup! id)
           (catch #?(:clj Throwable :cljs :default) _ nil)))
    (emit-frame-destroyed-trace! id)
    (dissoc-frame! id)
    (unregister-frame! id)
    (notify-epoch-listeners! id)
    nil))

(defn reset-frame!
  "destroy-frame! followed by reg-frame with the same config. Per Spec 002
  §reset-frame — full replace, opt-in."
  [id]
  (when-let [f (frame id)]
    (let [config (:config f)]
      (destroy-frame! id)
      (reg-frame id config))))

;; ---- :rf/default ----------------------------------------------------------

(defn ensure-default-frame!
  "The :rf/default frame is registered automatically the first time the
  runtime boots. Idempotent."
  []
  (when-not (get @frames :rf/default)
    (reg-frame :rf/default {:doc "Universal default frame."})))
