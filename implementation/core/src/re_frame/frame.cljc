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

;; ---- destroy-in-flight guard (rf2-r1ciy) ---------------------------------
;;
;; Tracks frame-ids whose `destroy-frame!` call is currently mid-flight so
;; a re-entrant `(destroy-frame! id)` from inside the same id's
;; `:on-destroy` handler (or downstream teardown hook) is a silent no-op.
;; Without this guard a re-entrant destroy would recursively re-enter
;; teardown — re-firing `:on-destroy`, re-running the machine cascade,
;; re-disposing the sub-cache — and likely throw on a half-torn-down
;; frame. Per Spec 002 §Destroy — re-entrant destroy is idempotent.

(defonce ^:private destroying-frames
  (atom #{}))

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
  ;; Sticky hook (rf2-f72pd) — `:adapter/current-frame` is published
  ;; once per loaded React-shaped adapter at ns-load time and routed
  ;; via `current-adapter`; it fires on every default-frame resolution
  ;; (every dispatch and every subscribe).
  #?(:cljs (if-let [f (late-bind/get-fn-cached :adapter/current-frame)]
             (f)
             (current-frame))
     :clj  (current-frame)))

;; ---- lookup ---------------------------------------------------------------

(defn frame
  "Return the frame record for id, or nil if not registered or destroyed.

  2-level lookup written as keyword-invoke (`(-> f :lifecycle :destroyed?)`)
  rather than `(get-in f [:lifecycle :destroyed?])` — `get-in` allocates
  a path vector per call (rf2-mqv4m), and `frame` runs on every dispatch
  / subscribe through `current-frame` resolution."
  [id]
  (when-let [f (get @frames id)]
    (when-not (-> f :lifecycle :destroyed?)
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
    (true? (-> f :lifecycle :destroyed?))
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
         (comp (filter (fn [[_ f]] (not (-> f :lifecycle :destroyed?))))
               (map key))
         @frames))
  ([ns-prefix]
   (let [prefix (str ns-prefix)]
     (into #{}
           (comp (filter (fn [[_ f]] (not (-> f :lifecycle :destroyed?))))
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
  ;;
  ;; rf2-cdmle — the :test / :story redirect targets
  ;; `:rf.http/managed-canned-success`, which registers from the test-
  ;; support namespace `re-frame.http-test-support`. Apps that use these
  ;; presets must `:require [re-frame.http-test-support]` (alongside
  ;; `re-frame.http-managed`) so the redirect target resolves. Production
  ;; / SSR code paths use `:default` / `:ssr-server` and never reach this
  ;; branch.
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
  "Run the user-supplied `:on-destroy` event synchronously, then continue
  teardown regardless of outcome. Per Spec 002 §Destroy — `:on-destroy`
  handler throw semantics (rf2-r1ciy decision b): a throw from the user's
  handler MUST NOT abort teardown. Emit `:rf.error/on-destroy-handler-exception`
  via `trace/emit-error!` and continue — every downstream step
  (machine cascade, sub-cache disposal, cleanup hooks, `:frame/destroyed`,
  registry dissoc) MUST still run so the frame is fully torn down.

  Mechanism: the router catches handler throws and converts them to
  `:rf.error/handler-exception` traces — `dispatch-sync!` does not re-
  throw. To surface the throw as the dedicated `:rf.error/on-destroy-
  handler-exception` category (Mike's decision), we observe the trace
  stream for the duration of the dispatch: any `:rf.error/handler-
  exception` whose `:frame` matches us is captured and re-emitted under
  the new category. We also wrap the dispatch itself in try/catch as a
  defence-in-depth: if `dispatch-sync!` ever re-throws (e.g. a fault
  inside the dispatch infrastructure itself, not the user handler),
  we catch it here.

  This mirrors the swallow-then-continue shape of `safe-call-hook!` below
  but ALSO emits a structured error trace (where `safe-call-hook!` is
  silent) — the user's `:on-destroy` is application code; its failure
  is a first-class diagnostic event."
  [id f]
  (when-let [on-destroy (-> f :config :on-destroy)]
    (when-let [dispatch-sync (late-bind/get-fn :router/dispatch-sync!)]
      (let [captured (atom nil)
            ;; The trace-buffer / listener registry lives in the optional
            ;; trace.tooling sibling per rf2-qwm0a. Reach it through
            ;; late-bind so this fn carries no static dep on the tooling
            ;; ns; in production CLJS builds where trace.tooling is not
            ;; loaded, the listener install is a silent no-op (no trace
            ;; surface to observe, no trace to re-emit).
            register   (late-bind/get-fn :trace.tooling/register-trace-cb!)
            remove-cb  (late-bind/get-fn :trace.tooling/remove-trace-cb!)
            listener-k ::on-destroy-throw-watch
            listener   (fn [ev]
                         (when (and (= :rf.error/handler-exception (:operation ev))
                                    (= id (-> ev :tags :frame))
                                    (nil? @captured))
                           (reset! captured ev)))]
        (when (and register remove-cb)
          (register listener-k listener))
        (try
          (try
            (dispatch-sync on-destroy {:frame id})
            (catch #?(:clj Throwable :cljs :default) ex
              ;; Defence-in-depth: dispatch-sync! normally swallows
              ;; handler throws, but if the dispatch infrastructure
              ;; itself fails we still emit the dedicated category.
              (trace/emit-error! :rf.error/on-destroy-handler-exception
                                 {:frame     id
                                  :event     on-destroy
                                  :exception ex
                                  :where     :fire-on-destroy-event!})))
          (finally
            (when (and register remove-cb)
              (remove-cb listener-k))))
        ;; If the router converted a handler throw to a trace, re-emit
        ;; under the dedicated :on-destroy category so consumers can
        ;; discriminate teardown failures from regular handler throws.
        (when-let [ev @captured]
          (let [tags (:tags ev)]
            (trace/emit-error! :rf.error/on-destroy-handler-exception
                               {:frame             id
                                :event             on-destroy
                                :exception         (:exception tags)
                                :exception-message (:exception-message tags)
                                :where             :fire-on-destroy-event!})))))))

(defn- notify-machine-destruction!
  "Frame-destroy machine-cascade entry-point.

  Per rf2-vsigt — Spec 005 §Cross-Spec Interactions §1: when the
  machines artefact is loaded, delegate the full cascade
  (reverse-creation walk, per-machine `:exit` cascade, HTTP abort,
  unified teardown projection, system-id release, handler unregister)
  to the late-bind hook `:machines/teardown-on-frame-destroy!`. The
  hook is published by `re-frame.machines` so core never statically
  requires the optional machines artefact.

  Fallback (no machines artefact on the classpath): preserve the
  legacy minimal behaviour — fire the `:http/abort-on-actor-destroy`
  hook per snapshot key and emit `:rf.machine.lifecycle/destroyed`
  with `:reason :parent-frame-destroyed`. Without the machines
  artefact there are no live `:exit` cascades to run, no actor
  handlers to unregister, and no system-id reverse index to release."
  [id]
  (if-let [teardown! (late-bind/get-fn :machines/teardown-on-frame-destroy!)]
    (teardown! id)
    ;; Fallback path — preserve the pre-rf2-vsigt minimal contract.
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
                      :reason     :parent-frame-destroyed})))))

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
    2. notify-machine-destruction!  — per Spec 005 §Cross-Spec Interactions §1:
                                      delegates to the machines artefact's
                                      `:machines/teardown-on-frame-destroy!`
                                      hook (rf2-vsigt). That walks each
                                      active machine in reverse-creation
                                      order: runs the `:exit` cascade
                                      against a live container, applies
                                      the unified teardown projection
                                      (snapshot + system-id + spawn-slot
                                      prune), unregisters the live handler,
                                      and emits
                                      `:rf.machine.lifecycle/destroyed`
                                      with :reason :parent-frame-destroyed.
                                      Falls back to minimal HTTP-abort +
                                      trace when the machines artefact is
                                      absent.
    3. mark-frame-destroyed!        — flip :lifecycle :destroyed?.
    4. tear-down-sub-cache!         — dispose every cached reaction.
    *. cleanup hooks (best-effort, no-op when artefact absent):
         :privacy/clear-suppression-cache!  — reset sensitive-without-
                                              redaction warn-once cache.
         :elision/clear-warning-cache!      — reset schema-first elision
                                              warning cache.
         :ssr/on-frame-destroyed            — clear SSR side-channel
                                              atoms for this frame.
         :machines/on-frame-destroyed!      — clear the machines
                                              artefact's frame-scoped
                                              `:after` timer table.
         :schemas/on-frame-destroyed!       — drop schemas registered
                                              against this frame
                                              (rf2-wkxng / rf2-6m0se).
         :flows/teardown-on-frame-destroy!  — drop flows + last-inputs
                                              rows + dead `:flow`
                                              registrar slots
                                              (rf2-wbtjn).
    5. emit-frame-destroyed-trace!  — emit :frame/destroyed AFTER the
                                      machine cascade.
    6. dissoc-frame!                — remove from the `frames` atom.
    7. unregister-frame!            — drop from the registrar.
    8. notify-epoch-listeners!      — fire the epoch hook so tools see
                                      :rf.epoch.cb/silenced-on-frame-destroy.

  Subsequent dispatch / subscribe against a destroyed frame raises
  :rf.error/frame-destroyed.

  Re-entrancy (rf2-r1ciy): if `destroy-frame!` is called for `id` while
  an outer `destroy-frame!` for the same `id` is still on the stack
  (e.g. the user's `:on-destroy` handler itself calls `destroy-frame!`,
  or a machine `:exit` cascade does so), the re-entrant call is a
  silent no-op — the outer call's teardown is already in flight and
  re-running the recipe would re-fire `:on-destroy`, re-run the
  machine cascade, and corrupt the half-torn-down state. Idempotent
  destroy is the existing pattern (a destroyed frame's `(frame id)`
  lookup already returns nil, so a *later* `destroy-frame!` short-
  circuits at the outer `when-let`); the in-flight guard closes the
  RE-ENTRANT window before `mark-frame-destroyed!` flips the flag."
  [id]
  ;; Re-entrancy guard: short-circuit if we're already destroying this id.
  ;; Silent no-op (idempotent destroy is already a no-op pattern; no new
  ;; trace event needed per rf2-r1ciy decision).
  (when-not (contains? @destroying-frames id)
    (when-let [f (frame id)]
      (swap! destroying-frames conj id)
      (try
        (fire-on-destroy-event! id f)
        (notify-machine-destruction! id)
        (mark-frame-destroyed! id)
        (tear-down-sub-cache! f)
        (safe-call-hook! :privacy/clear-suppression-cache!)
        (safe-call-hook! :elision/clear-warning-cache!)
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
        ;; Per rf2-wbtjn: drop every flow registered against the destroyed
        ;; frame plus its cached `last-inputs` rows, and prune the
        ;; `:flow` registrar slot when the destroyed frame was the last
        ;; owner. Symmetric with the machines teardown hook above
        ;; (rf2-vsigt). Without this hook a long-running SSR JVM with
        ;; per-request frame churn grows the flow registry unboundedly.
        ;; No-op when re-frame.flows is absent (the artefact is optional
        ;; per rf2-tfw3).
        (safe-call-hook! :flows/teardown-on-frame-destroy! id)
        (emit-frame-destroyed-trace! id)
        (dissoc-frame! id)
        (unregister-frame! id)
        (notify-epoch-listeners! id)
        nil
        (finally
          ;; Always clear the in-flight marker — even if a downstream step
          ;; throws unexpectedly, future `destroy-frame!` calls for `id`
          ;; (after a fresh `reg-frame`) must not see a stale entry.
          (swap! destroying-frames disj id))))))

(defn reset-frame!
  "destroy-frame! followed by reg-frame with the same config. Per Spec 002
  §reset-frame! — full replace, opt-in."
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
