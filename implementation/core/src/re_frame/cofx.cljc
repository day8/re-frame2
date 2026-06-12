(ns re-frame.cofx
  "Coeffect registration and declared-only delivery. Per Spec 001
  §`reg-cofx` (value-returning, graded), Spec 002 §Recordable coeffects,
  and EP-0017.

  A coeffect is a **fact the causal run consumed** — data from outside the
  event. Every coeffect id is REGISTERED through one value-returning
  `reg-cofx` supplier and consumed through one declaration key,
  `:rf.cofx/requires`, on `reg-event-fx` / `reg-event-ctx`. Handlers
  receive `:db`, `:event` (the fold's own arguments) plus EXACTLY the facts
  they declare, delivered flat — nothing implicit, including `:rf/time-ms`.

  Two grades (the grade is a property, not a namespace):

    - **ambient** (default) — its supplier runs at context assembly, the
      value is delivered to declaring handlers and NEVER recorded; replay
      re-runs the supplier. Legal only where no durable write depends on
      the value (display preferences, diagnostics, host-transient reads).
    - **recordable** (`:recordable? true`) — the fact is ensured onto the
      causal token, recorded, and re-presented verbatim by replay. A
      `:provided? true` recordable fact has NO generator: its value is
      stamped onto the token by its owner (framework, subsystem, dispatch
      boundary). The framework ships exactly ONE built-in registration —
      `:rf/time-ms` (recordable, provided), stamped at enqueue.

  `inject-cofx` is REMOVED (EP-0017, no alias) — calling it is the hard
  error `:rf.error/inject-cofx-removed` naming `:rf.cofx/requires`."
  (:require [re-frame.registrar :as registrar]
            [re-frame.interceptor :as interceptor]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.frame :as frame]
            [re-frame.fx :as fx]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- the platform predicate -----------------------------------------------
;;
;; Per Spec 011 §634-642 the `:platforms` metadata applies to BOTH
;; `reg-fx` AND `reg-cofx`; a cofx tagged `:platforms #{:client}` must
;; no-op when its supplier would run on a server-side frame (the SSR
;; contract — request-cofx like browser locale, localStorage,
;; navigator-info etc. would otherwise blow up under JVM render or produce
;; nonsense values).
;;
;; Single definition lives in `re-frame.fx/runs-on-platform?` (rf2-4ymm0
;; SP6); we alias it here so internal call sites read in the cofx
;; vocabulary.

(def ^:private cofx-runs-on-platform? fx/runs-on-platform?)

(defn- active-platform-for-frame
  "Resolve the active platform for a cofx supplier run from a frame-id.
  Resolves the frame record, then defers to the shared per-frame platform
  resolution `fx/platform-for-frame-record`: the frame's `:config
  :platform` override (set by the `:ssr-server` preset, or any
  user-supplied frame config) takes precedence over the host-wide platform
  marker (`interop/active-platform`, toggled via
  `re-frame.core/init-platform`)."
  [frame-id]
  (fx/platform-for-frame-record (when frame-id (frame/frame frame-id))))

;; ---- reserved / framework-owned coeffect names ----------------------------
;;
;; Per Spec 002 §4 + Spec 001 §Collisions: `:db` and `:event` are the
;; fold's own arguments — NOT registered suppliers and never declarable via
;; `:rf.cofx/requires`. A `reg-cofx` colliding with them (or with an
;; application id under an `rf.`-prefixed namespace) is the registration-time
;; hard error `:rf.error/cofx-name-collision`.

(def ^:private fold-argument-keys
  "The fold's own argument keys — staged by the runtime, never registered as
  cofx suppliers and never declarable. A `reg-cofx` colliding with one is a
  name collision (Spec 001 §Collisions)."
  #{:db :event})

(defn- rf-prefixed-app-namespace?
  "True when `id` is a qualified keyword whose namespace starts with `rf.`
  but is NOT a framework-reserved root. Application ids MUST NOT use
  `rf.`-prefixed namespaces (Spec 002 §Owner-qualified fact naming). The
  framework's own facts (`:rf/time-ms`, `:rf.route/*`, `:rf.server/*`, …)
  are owner-qualified and legitimate; this guard catches an APP registering
  under an `rf.`-prefixed namespace, which the registration site cannot
  distinguish from a framework fact. We only police the obviously-app shape
  here (the lint surface in Spec 009 §9 is the deeper check); the structural
  guard below rejects nothing that the framework itself registers."
  [_id]
  ;; Conservative: do not reject any `rf.`/`rf/` id at registration — the
  ;; framework and its subsystems legitimately register many of them and the
  ;; registration site cannot tell app from framework. The owner-qualified
  ;; naming rule is enforced by the lint (Spec 009 §9), not a structural
  ;; registration guard. Kept as a named seam for the future lint.
  false)

(defn- emit-cofx-name-collision!
  "Emit `:rf.error/cofx-name-collision` (registration-time, diagnostic) and
  throw. Per Spec 001 §Collisions + Spec 009 §Error catalogue."
  [id reason]
  (trace/emit-error! :rf.error/cofx-name-collision
                     {:rf.cofx/id id
                      :reason     reason
                      :recovery   :no-recovery})
  (throw (ex-info ":rf.error/cofx-name-collision"
                  {:rf.error/id :rf.error/cofx-name-collision
                   :rf.cofx/id  id
                   :where       'rf/reg-cofx
                   :reason      reason
                   :recovery    :no-recovery})))

(defn reg-cofx
  "Register a coeffect id with a **value-returning supplier** and standard
  Spec 001 metadata. Per Spec 001 §`reg-cofx` + EP-0017.

  Supplier signatures:

      (fn [] value)       ;; nullary — the ordinary supplier
      (fn [arg] value)    ;; call-site-parameterized id — declared as
                          ;; `[id arg]` in `:rf.cofx/requires`

  The supplier returns the coeffect VALUE directly (the EP-0017 shape); the
  ctx→ctx form is retired with `inject-cofx`. A handler takes delivery by
  declaring the id in `:rf.cofx/requires` (see `reg-event-fx`); the value
  arrives FLAT under the id in the coeffects map — never a nested `:cofx`
  sub-map.

  Shapes:

      (reg-cofx :id                                  (fn [] value))
      (reg-cofx :id {:doc \"...\" :recordable? true}  (fn [] value))

  Optional metadata keys:

      :recordable?  mark the fact RECORDABLE — ensured onto the causal
                    token, recorded, and re-presented verbatim by replay.
                    Default false (the AMBIENT grade — runs at context
                    assembly, never recorded).
      :provided?    (recordable only) the fact has NO generator: its value
                    is stamped onto the token by its owner. An absent
                    provided fact a handler declares is
                    `:rf.error/missing-required-cofx` in every mode.
      :doc          one-sentence what-and-why; surfaces via
                    `(rf/handler-meta :cofx id)`.
      :schema       Malli schema validating supplied / replayed values
                    (the `:schema` validation step is slice-B-built).
      :platforms    set of `#{:client :server}`; default
                    `#{:client :server}`. The supplier is skipped on
                    platforms not in the set (`:rf.cofx/skipped-on-platform`
                    warning trace, mirroring `reg-fx`'s contract per Spec
                    011 §634-642).

  Registration-time errors:

      :rf.error/cofx-name-collision  the id collides with the fold's
                                     argument keys (`:db` / `:event`) or
                                     another registered coeffect id.

  Examples:

      ;; ambient (default) — a display preference; never feeds durable state
      (rf/reg-cofx :ui/local-theme
        {:doc \"Ambient localStorage read for the display theme.\"}
        (fn [storage-key]
          (some-> (.-localStorage js/globalThis) (.getItem storage-key))))

      ;; recordable, PROVIDED — a boundary fact stamped by its owner
      (rf/reg-cofx :auth.session/token
        {:recordable? true :provided? true
         :doc \"The saved JWT the boot dispatch stamps onto its token.\"})

  Returns `id`.

  See also: `re-frame.events/reg-event-fx` (declare `:rf.cofx/requires`),
  `reg-fx` (output-side counterpart), spec/API.md §Registration."
  [id metadata-or-supplier & maybe-supplier]
  (let [[meta supplier]
        (if (map? metadata-or-supplier)
          [metadata-or-supplier (first maybe-supplier)]
          ;; A bare second arg is the supplier; a provided recordable fact
          ;; legitimately has no supplier, so `(reg-cofx :id {:provided? true})`
          ;; is the single-arg-meta form handled by the map? branch above.
          [{} metadata-or-supplier])]
    ;; ---- name-collision guard (Spec 001 §Collisions) ----------------------
    (when (contains? fold-argument-keys id)
      (emit-cofx-name-collision!
        id
        (str "`reg-cofx` id `" id "` collides with a fold ARGUMENT key. `:db` "
             "and `:event` are the handler's own arguments — staged by the "
             "runtime, not registered coeffects, and not declarable via "
             "`:rf.cofx/requires`. Choose an owner-qualified fact name.")))
    (when (rf-prefixed-app-namespace? id)
      (emit-cofx-name-collision!
        id
        (str "`reg-cofx` id `" id "` uses an `rf.`-prefixed namespace reserved "
             "for the framework. Application coeffects must be owner-qualified "
             "under an application namespace.")))
    (let [recordable? (boolean (:recordable? meta))
          provided?   (boolean (:provided? meta))]
      ;; A non-recordable (ambient) fact with no supplier cannot produce a
      ;; value; only a PROVIDED recordable fact legitimately omits its
      ;; generator (its owner stamps the token). An ambient fact MUST carry a
      ;; supplier.
      (when (and (nil? supplier) (not provided?))
        (emit-cofx-name-collision!
          id
          (str "`reg-cofx` id `" id "` declared no supplier. Only a PROVIDED "
               "recordable fact (`{:recordable? true :provided? true}`) may "
               "omit its supplier — its owner stamps the value onto the token. "
               "An ambient supplier must be a value-returning fn.")))
      (registrar/register! :cofx id
                           (assoc (source-coords/merge-coords meta)
                                  ;; The value-returning supplier rides the
                                  ;; registrar's conventional `:handler-fn`
                                  ;; slot (so `registrar/handler` + descriptor
                                  ;; lifting resolve it like every other kind);
                                  ;; nil for a provided fact with no generator.
                                  :handler-fn  supplier
                                  :recordable? recordable?
                                  :provided?   provided?)))
    ;; Per Spec 015 §5. Coeffects — stash `:sensitive` / `:large` path
    ;; declarations so emit-time projection redacts the delivered coeffect
    ;; value's slots in trace events that surface `:coeffects`.
    (when-let [register! (late-bind/get-fn :marks/register-marks!)]
      (register! :cofx id meta))
    id))

;; ---- :rf.cofx/requires parsing (Spec 001 §The declaration key) ------------
;;
;; `:rf.cofx/requires` is a vector of registered coeffect ids; a
;; parameterized id appears as `[id arg]`. Parsing produces a normalised
;; seq of `{:id <kw> :arg <value-or-::no-arg>}` entries the delivery step
;; consumes. Malformed values are `:rf.error/cofx-request-invalid` at
;; registration; the same id declared twice (any args) is
;; `:rf.error/cofx-name-collision`.

(def ^:private no-arg ::no-arg)

(defn- emit-cofx-request-invalid!
  "Emit `:rf.error/cofx-request-invalid` (registration-time) and throw. Per
  Spec 001 §The declaration key + Spec 009 §Error catalogue."
  [failing-id received reason]
  (trace/emit-error! :rf.error/cofx-request-invalid
                     {:failing-id failing-id
                      :received   received
                      :reason     reason
                      :recovery   :no-recovery})
  (throw (ex-info ":rf.error/cofx-request-invalid"
                  {:rf.error/id :rf.error/cofx-request-invalid
                   :failing-id  failing-id
                   :received    received
                   :where       'rf/reg-event-fx
                   :reason      reason
                   :recovery    :no-recovery})))

(defn parse-requires
  "Parse a `:rf.cofx/requires` declaration into a vector of
  `{:id <kw> :arg <value-or-::no-arg>}` entries, validating shape at
  registration. `failing-id` is the declaring handler / entry (for the
  error payload). Returns `[]` for an absent declaration.

  Each entry is either a bare coeffect id (a keyword) or a `[id arg]` pair
  (a parameterized id mirroring the binary supplier arity). Malformed
  shapes raise `:rf.error/cofx-request-invalid`; the same id declared twice
  (any args) raises `:rf.error/cofx-name-collision`."
  [failing-id requires]
  (cond
    (nil? requires) []
    (not (vector? requires))
    (emit-cofx-request-invalid!
      failing-id requires
      (str "`:rf.cofx/requires` for `" failing-id "` must be a VECTOR of "
           "registered coeffect ids (e.g. `[:rf/time-ms [:ui/local-theme "
           "\"theme\"]]`), got " (pr-str requires) "."))
    :else
    (let [entries
          (mapv
            (fn [entry]
              (cond
                (keyword? entry) {:id entry :arg no-arg}
                (and (vector? entry)
                     (= 2 (count entry))
                     (keyword? (first entry)))
                {:id (first entry) :arg (second entry)}
                :else
                (emit-cofx-request-invalid!
                  failing-id requires
                  (str "`:rf.cofx/requires` for `" failing-id "` carried a "
                       "non-id entry " (pr-str entry) "; each entry must be a "
                       "coeffect id (keyword) or an `[id arg]` pair."))))
            requires)
          ids (map :id entries)]
      (when (not= (count ids) (count (distinct ids)))
        (emit-cofx-name-collision!
          (->> (frequencies ids) (filter #(> (val %) 1)) ffirst)
          (str "`:rf.cofx/requires` for `" failing-id "` declares the same "
               "coeffect id twice (any args) — each id may appear once per "
               "consumer scope. Declaration: " (pr-str requires) ".")))
      entries)))

;; ---- declared-only delivery (Spec 002 §Satisfaction algorithm) ------------
;;
;; The runtime delivers EXACTLY the declared facts, flat, into the handler's
;; coeffects map. A recordable fact is read from the token's `:rf.cofx`; an
;; ambient fact runs its supplier now; a provided fact absent from the token
;; is `:rf.error/missing-required-cofx`. Generation of declared-absent
;; generator-backed recordable facts is slice B — in slice A every requirable
;; fact is provided or ambient, so a generator-backed recordable absent from
;; the token is treated as missing-required (no generator runs).

(defn- emit-unregistered-cofx!
  "Emit `:rf.error/unregistered-cofx` (the typo case — a declared id with no
  `reg-cofx` registration) and throw. Always-on (fires in production). Per
  Spec 001 §The declaration key + Spec 009."
  [cofx-id failing-id frame-id]
  (when-let [dispatch-on-error! (late-bind/get-fn-cached :error-emit/dispatch-on-error)]
    (dispatch-on-error! :rf.error/unregistered-cofx nil failing-id frame-id nil 0 (interop/now-ms)))
  (trace/emit-error! :rf.error/unregistered-cofx
                     (cond-> {:rf.cofx/id        cofx-id
                              :failing-id        failing-id
                              :rf.trace/event-id failing-id
                              :recovery          :no-recovery}
                       frame-id (assoc :frame frame-id)))
  (throw (ex-info ":rf.error/unregistered-cofx"
                  {:rf.error/id :rf.error/unregistered-cofx
                   :rf.cofx/id  cofx-id
                   :failing-id  failing-id
                   :recovery    :no-recovery})))

(defn- emit-missing-required-cofx!
  "Emit `:rf.error/missing-required-cofx` (a declared recordable fact absent
  and unensurable — a provided fact whose value was not stamped, or strict
  mode) and throw. Always-on (fires in production; the strict-replay loud
  failure). Per Spec 002 §Mint policies + Spec 009."
  [cofx-id failing-id frame-id]
  (when-let [dispatch-on-error! (late-bind/get-fn-cached :error-emit/dispatch-on-error)]
    (dispatch-on-error! :rf.error/missing-required-cofx nil failing-id frame-id nil 0 (interop/now-ms)))
  (trace/emit-error! :rf.error/missing-required-cofx
                     (cond-> {:rf.cofx/id        cofx-id
                              :failing-id        failing-id
                              :rf.trace/event-id failing-id
                              :recovery          :no-recovery}
                       frame-id (assoc :frame frame-id)))
  (throw (ex-info ":rf.error/missing-required-cofx"
                  {:rf.error/id :rf.error/missing-required-cofx
                   :rf.cofx/id  cofx-id
                   :failing-id  failing-id
                   :recovery    :no-recovery})))

(defn- emit-coeffect-exception!
  "Emit `:rf.error/coeffect-exception` for a supplier that threw during
  context assembly, then re-throw. Mirrors the router's
  `classify-pipeline-exception` shape (`:operation`
  `:rf.error/coeffect-exception`, `:failing-id` = the cofx id, `:phase
  :before`) so tools that capture pipeline exceptions (Story) surface a
  supplier throw with the same fidelity as the retired `inject-cofx`
  interceptor path. Fans out through the always-on error-emit listener too.
  Does NOT re-throw — the cascade is failed by SKIPPING the handler (the
  retired `inject-cofx` interceptor captured rather than propagated, so the
  drain emitted exactly one pipeline-exception trace; matching that keeps
  tools from double-recording a captured trace AND a propagated Throwable)."
  [cofx-id failing-id frame-id ^Throwable t]
  (let [msg #?(:clj (.getMessage t) :cljs (.-message t))]
    (when-let [dispatch-on-error! (late-bind/get-fn-cached :error-emit/dispatch-on-error)]
      (dispatch-on-error! :rf.error/coeffect-exception nil failing-id frame-id t 0 (interop/now-ms)))
    (trace/emit-error! :rf.error/coeffect-exception
                       (cond-> {:rf.cofx/id        cofx-id
                                :failing-id        cofx-id
                                :rf.trace/event-id failing-id
                                :phase             :before
                                :exception         t
                                :reason            (str "Coeffect supplier for `" cofx-id "` threw"
                                                        (when msg (str ": " msg)) ".")
                                :recovery          :no-recovery}
                         frame-id (assoc :frame frame-id)))))

(defn- run-ambient-supplier
  "Run an ambient supplier under its HandlerScope + platform gate, returning
  `[outcome value]` where `outcome` is `:delivered`, `:skipped` (platform
  gate; the fact is not delivered), or `:threw` (the supplier threw — emits
  `:rf.error/coeffect-exception` and the caller skips the handler). A run
  emits the dev-only `:rf.cofx/run` success op."
  [cofx-id meta supplier arg frame-id failing-id]
  (let [active-platform (active-platform-for-frame frame-id)]
    (if (cofx-runs-on-platform? meta active-platform)
      (trace/with-handler-scope
        (trace/handler-scope-from-meta :cofx cofx-id meta)
        (let [valued? (not (identical? arg no-arg))
              t0      (when interop/debug-enabled? (interop/now-ms))
              outcome (try
                        [:delivered (if valued? (supplier arg) (supplier))]
                        (catch #?(:clj Throwable :cljs :default) t
                          (emit-coeffect-exception! cofx-id failing-id frame-id t)
                          [:threw nil]))
              elapsed (when interop/debug-enabled? (- (interop/now-ms) t0))]
          (when (and interop/debug-enabled? (= :delivered (first outcome)))
            (trace/emit! :rf.cofx :rf.cofx/run
                         (cond-> {:rf.cofx/id cofx-id
                                  :frame      frame-id}
                           valued?           (assoc :rf.cofx/value arg)
                           (some? elapsed)   (assoc :rf.cofx/elapsed-ms elapsed))))
          outcome))
      (do
        (trace/emit! :warning :rf.cofx/skipped-on-platform
                     {:rf.cofx/id                   cofx-id
                      :frame                        frame-id
                      :rf.cofx/platform             active-platform
                      :rf.cofx/registered-platforms (:platforms meta)
                      :recovery                     :skipped})
        [:skipped nil]))))

(defn deliver-declared-cofx
  "Deliver the handler's declared coeffects FLAT into `coeffects`, per Spec
  002 §Satisfaction algorithm step 4 + EP-0017 §5. `requires` is the parsed
  entry vector (`parse-requires`); `recorded` is the token's `:rf.cofx` map;
  `frame-id` tags the trace / error emits. Returns the augmented coeffects
  map.

  For each declared id (declaration order):

    - **unregistered** → `:rf.error/unregistered-cofx` (the typo case; halts);
    - **ambient** → run its supplier now, deliver the value (never recorded);
      a platform-skipped supplier delivers nothing;
    - **recordable, present on the token** → deliver the recorded value
      verbatim (no host read);
    - **recordable, absent** → `:rf.error/missing-required-cofx` (provided
      facts in every mode; generator-backed facts too in slice A — generation
      is slice B).

  Delivery is DECLARED-ONLY and FLAT: an undeclared leaf on the token is not
  staged, and there is no nested `:cofx` / `:rf.cofx` duplicate in the
  delivered spread (the envelope's canonical `:rf.cofx` map is reachable
  through the context for generic code).

  Returns `{:coeffects <augmented> :rf/skip-handler? <bool>}`. An unregistered
  or missing-required declared fact halts loudly (throws); a supplier that
  THREW emits `:rf.error/coeffect-exception` and sets `:rf/skip-handler?` so
  the handler does not run and the cascade is failed without a raw throw
  escaping context assembly (mirroring the retired `inject-cofx` interceptor's
  capture-don't-propagate behaviour)."
  [coeffects requires recorded failing-id frame-id]
  (reduce
    (fn [{:keys [coeffects] :as acc} {:keys [id arg]}]
      (let [meta (registrar/lookup :cofx id)]
        (cond
          ;; A declared id with NO registration is the typo case (the
          ;; framework's own facts — e.g. the provided `:rf/time-ms` — are
          ;; registered, so a nil meta is always an unregistered id).
          (nil? meta)
          (emit-unregistered-cofx! id failing-id frame-id)

          (:recordable? meta)
          (if (contains? recorded id)
            (assoc-in acc [:coeffects id] (get recorded id))
            (emit-missing-required-cofx! id failing-id frame-id))

          :else                                   ;; ambient
          (let [[outcome value]
                (run-ambient-supplier id meta (:handler-fn meta) arg frame-id failing-id)]
            (case outcome
              :delivered (assoc-in acc [:coeffects id] value)
              :skipped   acc
              :threw     (assoc acc :rf/skip-handler? true))))))
    {:coeffects coeffects :rf/skip-handler? false}
    requires))

;; ---- inject-cofx is REMOVED (EP-0017 slice A.3, no alias) ------------------
;;
;; Per Spec 001 §`inject-cofx` is removed + Spec 009 §Error catalogue:
;; `inject-cofx` (and `inject-cofx*`) — the v1 ctx→ctx delivery idiom that ran
;; a coeffect-injecting function as a positional interceptor at handler time —
;; is REMOVED with no alias. `:rf.cofx/requires` is the one declaration
;; surface. Calling it is the hard error `:rf.error/inject-cofx-removed`
;; naming the replacement; it fires in production too (a correctness contract,
;; not a dev diagnostic). The stub remains so a stale call site fails LOUDLY
;; with an actionable message rather than an opaque "no such var".

(defn inject-cofx
  "REMOVED in EP-0017 (no alias). Calling `inject-cofx` is the hard error
  `:rf.error/inject-cofx-removed`, naming `:rf.cofx/requires` as the
  replacement. See `re-frame.events/reg-event-fx` and spec/001-Registration.md
  §`inject-cofx` is removed."
  [& args]
  (let [cofx-id (first args)]
    (when-let [dispatch-on-error! (late-bind/get-fn-cached :error-emit/dispatch-on-error)]
      (dispatch-on-error! :rf.error/inject-cofx-removed nil cofx-id nil nil 0 (interop/now-ms)))
    (trace/emit-error! :rf.error/inject-cofx-removed
                       (cond-> {:recovery :no-recovery}
                         cofx-id (assoc :rf.cofx/id cofx-id)))
    (throw (ex-info ":rf.error/inject-cofx-removed"
                    {:rf.error/id :rf.error/inject-cofx-removed
                     :rf.cofx/id  cofx-id
                     :where       'rf/inject-cofx
                     :recovery    :no-recovery
                     :reason
                     (str "`inject-cofx` is REMOVED in EP-0017 (no alias). "
                          "Declare the coeffect on the handler's registration "
                          "metadata instead: "
                          "`{:rf.cofx/requires [" (if cofx-id (pr-str cofx-id) ":your/cofx") "]}`. "
                          "The declared value arrives flat in the coeffects map "
                          "under its id; the registration's grade decides replay "
                          "semantics. See spec/001-Registration.md §`inject-cofx` "
                          "is removed.")}))))

;; ---- standard registrations -----------------------------------------------
;;
;; The framework ships exactly ONE built-in coeffect registration:
;; `:rf/time-ms` — recordable, provided, stamped at enqueue on every dispatch
;; and reply envelope (EP-0010's stamping rules unchanged). It is the
;; canonical durable wall-clock fact; the framework's own durable writers
;; (resource freshness, work-ledger rows, mutation instances, epoch records)
;; read it from the envelope. A handler takes delivery by declaring
;; `:rf.cofx/requires [:rf/time-ms]` and reading `time-ms` flat.
;;
;; `:db` and `:event` are the fold's OWN arguments (Spec 002 §4) — staged by
;; the runtime, NOT registered as cofx suppliers and never declarable. They
;; carry no `reg-cofx` registration in the EP-0017 model (the former ctx→ctx
;; `:db` / `:event` / `:app/now-ms` no-op cofx are retired with the ctx form).

(reg-cofx :rf/time-ms
  {:recordable? true
   :provided?   true
   :doc "The framework's one provided recordable coeffect: wall-clock epoch
        milliseconds, stamped onto every dispatch and reply envelope at
        enqueue (EP-0010 / EP-0017). The canonical durable causal-time fact —
        a handler that folds a timestamp into durable state declares
        `:rf.cofx/requires [:rf/time-ms]` and reads `time-ms` flat. Always
        present on the token (the enqueue stamp guarantees it)."})
