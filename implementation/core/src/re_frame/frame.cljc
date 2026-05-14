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

  On CLJS this consults the `:adapter/current-frame` late-bind hook
  so the React-context tier is LIVE — adapters publish their React-
  context-aware impl through the hook at ns-load time. When the hook
  is unbound (no adapter loaded yet, or JVM build) the fallback is
  `current-frame` which honours the dynamic-var tier and the
  `:rf/default` tier; the React-context tier silently no-ops.

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

(defn frame-disposed-for-drain?
  "Per Spec 002 §Frame disposal mid-drain: predicate used by the
  router's drain loop to interrupt a pass when the frame was destroyed
  mid-cycle. True when EITHER:

    (a) The frame record still exists but `:destroyed?` is flipped
        (post-step-3 of `destroy-frame!`, before step-6 dissoc), OR
    (b) The frame record is absent from the `frames` atom (post-step-6
        of `destroy-frame!` — the dissoc step has run).

  Returns false when `id` is registered and not destroyed. Calling for
  a never-registered `id` returns true — that case is benign for the
  drain-loop caller (a drain cannot run on a frame that was never
  registered), but the predicate is named `*-for-drain?` to make the
  intended seam explicit and avoid suggesting general
  destroyed-vs-never-registered discrimination."
  [id]
  (if-let [f (get @frames id)]
    (true? (get-in f [:lifecycle :destroyed?]))
    ;; Absent from the atom — destroy-frame!'s step 6 ran, OR the id
    ;; was never registered. The drain-loop caller only consults this
    ;; while a pass is already in flight, so the latter case cannot
    ;; arise from that seam.
    true))

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

(defn swap-frame-db!
  "Mutate the frame's app-db: read the current value, compute
  `(apply f db args)`, and replace the container with the result. Returns
  the new-db, or nil if the frame is not registered.

  Models `swap!` over the substrate-managed reactive container. Under the
  single-drainer invariant (Spec 002 §Single drainer per frame) the
  read-then-replace is effectively atomic — `replace-container!` is the
  only writer during fx drain. The helper is the canonical \"mutate the
  frame's app-db\" surface; the read-container / replace-container dance
  belongs here, not at every fx-handler call site."
  [id f & args]
  (when-let [container (get-frame-db id)]
    (let [old-db (adapter/read-container container)
          new-db (apply f old-db args)]
      (adapter/replace-container! container new-db)
      new-db)))

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
   ;; Single-drainer invariant: a separate CAS-able cell that admits
   ;; at most one thread into `drain!` at a time. On the JVM the
   ;; executor's `next-tick` callback can wake while the calling
   ;; thread is mid-drain (e.g. `dispatch-sync!`); without this guard,
   ;; both threads' peek+pop sequence on `:queue` is non-atomic and
   ;; double-processes / drops envelopes. The loser of the CAS no-ops;
   ;; the winning drainer rechecks the queue before releasing the
   ;; flag so envelopes queued in the gap are not orphaned. CLJS is
   ;; single-threaded so the CAS is uncontended there, but the same
   ;; flag preserves the contract under any future concurrent host.
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
          ;; Run :on-create events BEFORE emitting :frame/created
          ;; (Spec 002 §Frame creation). The router/dispatch ns is
          ;; reached through late-bind to avoid a cyclic dep at
          ;; compile time.
          ;;
          ;; Per Spec 002 §`reg-frame` / `make-frame` called from inside
          ;; a handler: when a handler creates a child frame mid-
          ;; cascade, the child's `:on-create` MUST be async-queued
          ;; (not dispatch-sync'd) — synchronous dispatch-sync from
          ;; inside a handler is an error, and even were it permitted
          ;; the two cascades would interleave (forbidden by the no-
          ;; cross-frame-drain rule in Spec 002 §Run-to-completion).
          ;; The signal for "inside a handler" is `*current-frame*`
          ;; being bound — the router binds it in `process-event!`
          ;; for the duration of the cascade.
          (when-let [on-create (:on-create config)]
            (if *current-frame*
              ;; Handler-created child frame: async-queue on the child.
              (when-let [dispatch (late-bind/get-fn :router/dispatch!)]
                (dispatch on-create {:frame id}))
              ;; Top-level (no in-flight cascade): synchronous, as before.
              (when-let [dispatch-sync (late-bind/get-fn :router/dispatch-sync!)]
                (dispatch-sync on-create {:frame id}))))
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
;; named helper so the body of destroy-frame! reads as a step list. Order
;; matters — see destroy-frame!'s docstring for the authoritative recipe.

(defn- safe-call-hook!
  "Fire a late-bound cleanup hook by key. No-op when unbound; exceptions
  are swallowed so one bad hook can't block the rest of teardown."
  [hook-key & args]
  (when-let [f (late-bind/get-fn hook-key)]
    (try (apply f args)
         (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn- fire-on-destroy-event!
  [id f]
  (when-let [on-destroy (get-in f [:config :on-destroy])]
    (when-let [dispatch-sync (late-bind/get-fn :router/dispatch-sync!)]
      (dispatch-sync on-destroy {:frame id}))))

(defn- notify-machine-destruction!
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
  [id]
  (swap! frames update id assoc-in [:lifecycle :destroyed?] true))

(defn- tear-down-sub-cache!
  [f]
  (when-let [cache (:sub-cache f)]
    (doseq [[_k entry] @cache]
      (when-let [r (:reaction entry)]
        (try (interop/dispose! r)
             (catch #?(:clj Throwable :cljs :default) _ nil))))
    (reset! cache {})))

(defn- emit-frame-destroyed-trace!
  [id]
  (trace/emit! :frame :frame/destroyed
               {:frame id}))

(defn- dissoc-frame!
  [id]
  (swap! frames dissoc id))

(defn- unregister-frame!
  [id]
  (registrar/unregister! :frame id))

(defn- notify-epoch-listeners!
  [id]
  (safe-call-hook! :epoch/on-frame-destroyed id))

(defn destroy-frame!
  "Tear down a frame. Per Spec 002 §Destroy, the ordered steps are:

    1. fire-on-destroy-event!       — run user :on-destroy while frame
                                      is still alive.
    2. notify-machine-destruction!  — for each active machine snapshot:
                                      fire the HTTP abort hook then emit
                                      :rf.machine.lifecycle/destroyed
                                      with :reason :parent-frame-destroyed.
    3. mark-frame-destroyed!        — flip :lifecycle :destroyed?.
    4. tear-down-sub-cache!         — dispose every cached reaction.
    *. cleanup hooks (best-effort, no-op when artefact absent):
         :privacy/clear-suppression-cache!  — reset sensitive-without-
                                              redaction warn-once cache.
         :ssr/on-frame-destroyed            — clear SSR side-channel
                                              atoms for this frame.
         :machines/on-frame-destroyed!      — clear the machines
                                              artefact's frame-scoped
                                              `:after` timer table.
    5. emit-frame-destroyed-trace!  — emit :frame/destroyed AFTER the
                                      machine cascade.
    6. dissoc-frame!                — remove from the `frames` atom.
    7. unregister-frame!            — drop from the registrar.
    8. notify-epoch-listeners!      — fire the epoch hook so tools see
                                      :rf.epoch.cb/silenced-on-frame-destroy.

  Subsequent dispatch / subscribe against a destroyed frame raises
  :rf.error/frame-destroyed."
  [id]
  (when-let [f (frame id)]
    (fire-on-destroy-event! id f)
    (notify-machine-destruction! id)
    (mark-frame-destroyed! id)
    (tear-down-sub-cache! f)
    (safe-call-hook! :privacy/clear-suppression-cache!)
    (safe-call-hook! :ssr/on-frame-destroyed id)
    (safe-call-hook! :machines/on-frame-destroyed! id)
    ;; Per rf2-wkxng / rf2-6m0se: drop every schema registered against
    ;; the destroyed frame so a re-registered frame starts with a
    ;; clean schema slate. Without this hook, orphan app-db schemas
    ;; from a prior `reg-frame` cycle persist and re-fire under the
    ;; rollback contract — manifesting as spurious rollbacks against
    ;; paths the new frame's :on-create never wrote. No-op when
    ;; re-frame.schemas is absent (the artefact is optional per
    ;; rf2-p7va).
    (safe-call-hook! :schemas/on-frame-destroyed! id)
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
