(ns re-frame.cofx
  "Coeffect registration and declared-only delivery. Per Spec 001
  §`reg-cofx` (value-returning, graded), Spec 002 §Recordable coeffects,
  and EP-0017.

  A coeffect is a **fact the causal run consumed** — data from outside the
  event. Every coeffect id is REGISTERED through one value-returning
  `reg-cofx` supplier and consumed through one declaration key,
  `:rf.cofx/requires`, on `reg-event`. Handlers
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
            [re-frame.error :as error]
            [re-frame.cofx.value-check :as value-check]
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
;; `:rf.cofx/requires`. A `reg-cofx` colliding with them is the
;; registration-time hard error `:rf.error/cofx-name-collision`. An
;; application id under an `rf.`-prefixed namespace is a LINT diagnostic
;; (Spec 009 §9), not a registration guard — the registration site cannot
;; tell an app id from a framework/subsystem `rf.*` fact. A duplicate cofx
;; id across namespaces is caught at EP-0023 image assembly
;; (`:rf.error/image-duplicate-id`), uniformly with every other kind.

(def ^:private fold-argument-keys
  "The fold's own argument keys — staged by the runtime, never registered as
  cofx suppliers and never declarable. A `reg-cofx` colliding with one is a
  name collision (Spec 001 §Collisions)."
  #{:db :event})

;; NOTE (rf2-6zfzxy): the registration site does NOT police `rf.`-prefixed
;; APP namespaces — it cannot distinguish an app id from a legitimate
;; framework/subsystem `rf.*` fact (`:rf/time-ms`, `:rf.route/*`, …), which
;; the framework registers many of. The owner-qualified naming rule is the
;; lint surface in Spec 009 §9, not a structural registration guard. The
;; previously-present `rf-prefixed-app-namespace?` seam was a hardcoded `false`
;; feeding an unreachable `emit-cofx-name-collision!` branch — removed as dead
;; code (the future lint lives in Spec 009 §9, not a dormant always-false
;; predicate here).

(defn- emit-cofx-name-collision!
  "Emit `:rf.error/cofx-name-collision` (registration-time, diagnostic) and
  throw. Per Spec 001 §Collisions + Spec 009 §Error catalogue."
  [id reason]
  (trace/emit-error! :rf.error/cofx-name-collision
                     {:rf.cofx/id id
                      :reason     reason
                      :recovery   :no-recovery})
  (error/throw-error! :rf.error/cofx-name-collision 'rf/reg-cofx reason
                      {:extra {:rf.cofx/id id}}))

(defn- emit-cofx-registration-invalid!
  "Emit `:rf.error/cofx-registration-invalid` (registration-time, diagnostic)
  and throw. The malformed-METADATA case — a `reg-cofx` whose grade is
  internally contradictory (`:provided?` without `:recordable?`; a missing
  supplier on a non-provided fact; a provided fact carrying a supplier that
  delivery will silently ignore). DISTINCT from
  `:rf.error/cofx-name-collision`, which is reserved for the call-time
  ownership clashes (`:db` / `:event` fold-argument keys; the same id twice
  in one `:rf.cofx/requires`). A duplicate cofx id across namespaces is
  caught at EP-0023 image assembly (`:rf.error/image-duplicate-id`), not
  here. Per Spec 001 §`reg-cofx` + Spec 009 §Error catalogue."
  [id reason]
  (trace/emit-error! :rf.error/cofx-registration-invalid
                     {:rf.cofx/id id
                      :reason     reason
                      :recovery   :no-recovery})
  (error/throw-error! :rf.error/cofx-registration-invalid 'rf/reg-cofx reason
                      {:extra {:rf.cofx/id id}}))

(defn reg-cofx
  "Register a coeffect id with a **value-returning supplier** and standard
  Spec 001 metadata. Per Spec 001 §`reg-cofx` + EP-0017.

  Supplier signatures:

      (fn [] value)       ;; nullary — the ordinary supplier
      (fn [arg] value)    ;; call-site-parameterized id — declared as
                          ;; `[id arg]` in `:rf.cofx/requires`

  The supplier returns the coeffect VALUE directly (the EP-0017 shape); the
  ctx→ctx form is retired with `inject-cofx`. A handler takes delivery by
  declaring the id in `:rf.cofx/requires` (see `reg-event`); the value
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

      :rf.error/cofx-name-collision       the id collides with the fold's
                                          argument keys (`:db` / `:event`).
                                          (A duplicate cofx id across
                                          namespaces is caught at EP-0023
                                          image assembly as
                                          `:rf.error/image-duplicate-id`,
                                          not here.)
      :rf.error/cofx-registration-invalid the metadata grade is malformed —
                                          `:provided?` without
                                          `:recordable?`, a missing supplier
                                          on a non-provided fact, or a
                                          provided fact carrying a (silently
                                          ignored) supplier.

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

  See also: `re-frame.events/reg-event` (declare `:rf.cofx/requires`),
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
    (let [recordable? (boolean (:recordable? meta))
          provided?   (boolean (:provided? meta))]
      ;; `:provided?` is meaningful ONLY alongside `:recordable? true` — a
      ;; provided fact is recordable by definition (its owner stamps the
      ;; value onto the token; an ambient fact always runs a supplier). A
      ;; `{:provided? true}` without `:recordable? true` is a malformed
      ;; grade that would otherwise register as an ambient fact with a nil
      ;; supplier, surfacing only as an opaque host NPE at delivery
      ;; (`run-ambient-supplier` invoking nil). Reject it loudly at the call
      ;; site instead (Spec-Schemas §`:rf/cofx-meta`, rf2-cu8wet). This is a
      ;; malformed-grade error, NOT a name collision (rf2-d8mvke.6).
      (when (and provided? (not recordable?))
        (emit-cofx-registration-invalid!
          id
          (str "`reg-cofx` id `" id "` declared `:provided? true` without "
               "`:recordable? true`. A provided coeffect is recordable by "
               "definition (its owner stamps the value onto the token); an "
               "ambient coeffect always runs a supplier. Either add "
               "`:recordable? true` (a provided recordable fact) or drop "
               "`:provided?` and supply a value-returning fn (an ambient "
               "fact).")))
      ;; A PROVIDED recordable fact has NO generator: its value is stamped
      ;; onto the token by its owner and delivery reads it from the token's
      ;; `:rf.cofx` verbatim (`deliver-declared-cofx`'s recordable branch
      ;; never invokes a supplier). A supplier passed alongside `:provided?
      ;; true` is therefore SILENTLY IGNORED at delivery — the registration
      ;; looks like it provides a generator, but the first handler requiring
      ;; the fact (when it is absent from the token) fails as
      ;; `:rf.error/missing-required-cofx`. Reject the contradiction at the
      ;; call site (rf2-d8mvke.1). The valid provided shape is
      ;; `{:recordable? true :provided? true}` with NO supplier.
      (when (and provided? (some? supplier))
        (emit-cofx-registration-invalid!
          id
          (str "`reg-cofx` id `" id "` declared `:provided? true` WITH a "
               "supplier. A provided recordable fact has NO generator — its "
               "value is stamped onto the causal token by its owner and "
               "delivered verbatim, so this supplier would be silently "
               "ignored at delivery (the first consumer fails as "
               "`:rf.error/missing-required-cofx`). Either drop the supplier "
               "(a provided recordable fact: `{:recordable? true :provided? "
               "true}`) or drop `:provided?` (a recordable fact whose "
               "supplier generates the value).")))
      ;; A non-recordable (ambient) fact with no supplier cannot produce a
      ;; value; only a PROVIDED recordable fact legitimately omits its
      ;; generator (its owner stamps the token). An ambient fact MUST carry a
      ;; supplier. This is a malformed-grade error, NOT a name collision
      ;; (rf2-d8mvke.6).
      (when (and (nil? supplier) (not provided?))
        (emit-cofx-registration-invalid!
          id
          (str "`reg-cofx` id `" id "` declared no supplier. Only a PROVIDED "
               "recordable fact (`{:recordable? true :provided? true}`) may "
               "omit its supplier — its owner stamps the value onto the token. "
               "An ambient supplier must be a value-returning fn.")))
      ;; Per Spec 015 §5. Coeffects — VALIDATE any declared `:sensitive` /
      ;; `:large` marks fail-loud BEFORE the registrar write (rf2-ehexnw); the
      ;; marks themselves are DERIVED from the registrar meta at `marks-for`
      ;; read time (emit-time projection redacts the delivered coeffect value's
      ;; slots in trace events that surface `:coeffects`), no imperative stash.
      (when-let [validate! (late-bind/get-fn :marks/validate-marks!)]
        (validate! :cofx meta))
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
    id))

;; ---- EP-0023 inline-registration lowering (rf2-ffc6s0) --------------------
;;
;; An image's inline `:registrations` `:reg-cofx` entry carries the raw
;; value-returning supplier fn under `:impl`. For the inline cofx to be
;; DELIVERED through a frame-targeted cascade, the assembled generation's
;; resolver descriptor must carry the SAME runnable slots `reg-cofx` installs
;; — `:handler-fn` (the supplier) + the `:recordable?` / `:provided?` grade
;; flags delivery reads. Closes the EP-0023 §Image Fragments "same runtime
;; descriptor shape" contract for cofx. Published via late-bind (image-assembly
;; cannot static-require this ns).

(defn lower-inline-cofx
  "Lower an inline `:reg-cofx` descriptor's raw supplier fn into the runnable
  cofx slots `reg-cofx` installs (`:handler-fn` + the `:recordable?` /
  `:provided?` grade flags read at delivery). `meta` is the inline entry's
  metadata map (its `:recordable?` / `:provided?` grade is read here, mirroring
  `reg-cofx`); `impl` is the raw value-returning supplier (nil for a provided
  recordable fact). Returns ONLY the runnable slots so image-assembly merges
  them onto the descriptor, preserving `:impl` + provenance."
  [meta impl]
  {:handler-fn  impl
   :recordable? (boolean (:recordable? meta))
   :provided?   (boolean (:provided? meta))})

(late-bind/set-fn! :image/lower-inline-cofx lower-inline-cofx)

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
  (error/throw-error! :rf.error/cofx-request-invalid 'rf/reg-event reason
                      {:extra {:failing-id failing-id
                               :received   received}}))

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
;; is `:rf.error/missing-required-cofx`.
;;
;; GENERATION (EP-0017 slice B.7, rf2-ygpac8). A declared-absent recordable
;; fact that is NEITHER provided NOR present on the token is GENERATOR-BACKED:
;; its `reg-cofx` carries a value-returning supplier. The mint policy (§6)
;; decides what happens —
;;
;;   - `:live` / `:explicit-live` → run the generator at PROCESSING-START
;;     (the declaration is only knowable once the handler is resolved — late
;;     registration is legal), emit the dev-only `:rf.cofx/generated` trace
;;     op, validate the produced value against the registration's `:schema`
;;     (a PRODUCTION hard error on mismatch — `:rf.error/cofx-value-invalid`),
;;     WRITE the value back into the in-flight `:rf.cofx` record (so the epoch
;;     captures the post-generation token and replay re-presents it — at which
;;     point the fact is present and the generator finds nothing to do), and
;;     deliver it;
;;   - `:strict` → no generator runs, no host read: `:rf.error/missing-
;;     required-cofx` (replay is unconditionally strict — an incomplete record
;;     MUST fail loudly rather than silently re-read the host).
;;
;; Validation also covers SUPPLIED / REPLAYED recordable values: a fact PRESENT
;; on the token whose `reg-cofx` declared a `:schema` is validated before
;; delivery (supplied values win, but the `:schema` is the type of the replay
;; hole — folding an out-of-contract value into the ledger is corrupt durable
;; state). The mint policy threads in via the `mint-policy` arg; the router
;; default is `:live` (slice-B.8 wires the binding points — `:test` preset and
;; replay hard-wire `:strict`).

(defn- emit-unregistered-cofx!
  "Emit `:rf.error/unregistered-cofx` (the typo case — a declared id with no
  `reg-cofx` registration) and throw. Always-on (fires in production). Per
  Spec 001 §The declaration key + Spec 009."
  [cofx-id failing-id frame-id]
  (let [reason (str "Event `" failing-id "` declared `:rf.cofx/requires` coeffect `"
                    cofx-id "`, but no `reg-cofx` registered that id — almost "
                    "always a typo or a missing registration ns. Register the "
                    "coeffect with `rf/reg-cofx` (or fix the id in "
                    "`:rf.cofx/requires`) so a supplier exists before the event "
                    "is dispatched."
                    (when frame-id (str " (frame `" frame-id "`)")))]
    (when-let [emit-error-both! (late-bind/get-fn-cached :error-emit/emit-error-both)]
      (emit-error-both! :rf.error/unregistered-cofx nil failing-id frame-id nil 0 (interop/now-ms)
                        (cond-> {:rf.cofx/id        cofx-id
                                 :failing-id        failing-id
                                 :rf.trace/event-id failing-id
                                 :reason            reason
                                 :recovery          :no-recovery}
                          frame-id (assoc :frame frame-id))))
    (error/throw-error! :rf.error/unregistered-cofx 'rf/reg-event reason
                        {:extra (cond-> {:rf.cofx/id cofx-id
                                         :failing-id failing-id}
                                  frame-id (assoc :frame frame-id))})))

(defn- emit-missing-required-cofx!
  "Emit `:rf.error/missing-required-cofx` (a declared recordable fact absent
  and unensurable — a provided fact whose value was not stamped, or strict
  mode) and throw. Always-on (fires in production; the strict-replay loud
  failure). Per Spec 002 §Mint policies + Spec 009."
  [cofx-id failing-id frame-id]
  (let [reason (str "Event `" failing-id "` requires recordable coeffect `"
                    cofx-id "`, but it is absent from the causal token and "
                    "cannot be produced: a `:provided?` fact's value was not "
                    "stamped by its owner, or a `:strict` mint policy (replay / "
                    "the `:test` preset) forbids running a generator. Stamp the "
                    "provided value onto the token at the dispatch boundary, or "
                    "dispatch under `:live` so a generator-backed fact can mint "
                    "it."
                    (when frame-id (str " (frame `" frame-id "`)")))]
    (when-let [emit-error-both! (late-bind/get-fn-cached :error-emit/emit-error-both)]
      (emit-error-both! :rf.error/missing-required-cofx nil failing-id frame-id nil 0 (interop/now-ms)
                        (cond-> {:rf.cofx/id        cofx-id
                                 :failing-id        failing-id
                                 :rf.trace/event-id failing-id
                                 :reason            reason
                                 :recovery          :no-recovery}
                          frame-id (assoc :frame frame-id))))
    (error/throw-error! :rf.error/missing-required-cofx 'rf/reg-event reason
                        {:extra (cond-> {:rf.cofx/id cofx-id
                                         :failing-id failing-id}
                                  frame-id (assoc :frame frame-id))})))

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
    (when-let [emit-error-both! (late-bind/get-fn-cached :error-emit/emit-error-both)]
      (emit-error-both! :rf.error/coeffect-exception nil failing-id frame-id t 0 (interop/now-ms)
                        (cond-> {:rf.cofx/id        cofx-id
                                 :failing-id        cofx-id
                                 :rf.trace/event-id failing-id
                                 :phase             :before
                                 :exception         t
                                 :reason            (str "Coeffect supplier for `" cofx-id "` threw"
                                                         (when msg (str ": " msg)) ".")
                                 :recovery          :no-recovery}
                          frame-id (assoc :frame frame-id))))))

;; ---- :schema validation of recordable values (EP-0017 §5 / slice B.7) -----
;;
;; A recordable value — supplied on the token, replayed from a record, or
;; freshly generated — is validated against its registration's `:schema`
;; before it reaches the fold. Failure is `:rf.error/cofx-value-invalid`, a
;; HARD ERROR in dev AND production (the `:dispatched-at` precedent — folding
;; an out-of-contract value into the durable ledger is corrupt state). The
;; check routes through the SAME registered validator the dev-time hot path
;; uses (the `set-schema-validator!` seam, reached via the late-bind
;; `:schemas/validate-with-registered-fn` hook) so a substituted (non-Malli)
;; validator covers this surface too; when the schemas artefact is absent or
;; the validator was set to nil the hook is nil and validation is a no-op
;; (nil = "every value passes", per Spec 010 §Non-Malli validators).

(defn- emit-cofx-value-invalid!
  "Emit `:rf.error/cofx-value-invalid` (a supplied / replayed / generated
  recordable value failed the registration's `:schema`) and throw. ALWAYS-ON
  — fires in production too: a causal-token contract validation (an
  out-of-contract durable value is corrupt state). Per Spec 002
  §Satisfaction + Spec 009 §Error catalogue.

  Per rf2-hdi6wr (EP-0015 / EP-0017) — a recordable value validated against a
  `:schema` that marks any slot `{:sensitive? true}` MUST NOT surface the raw
  secret off-box. Both the emitted trace tags AND the thrown `ex-info`'s
  `ex-data` carry the value-bearing slots (`:value` / `:explain`), and BOTH
  egress (the trace to every listener → MCP / log / story; the throw's ex-data
  is public error data). Route them through THE shared schema-aware redaction
  seam (`:schemas/redact-validation-tags`, the same one the boundary
  interceptor / machine-`:data` / sub-override / flow-output emit sites use):
  when the `schema` declares any `:sensitive?` slot the value-bearing slots
  scrub to `:rf/redacted` and `:sensitive? true` is stamped; otherwise the
  slots ride verbatim. FAILS CLOSED — a recordable-value failure with a
  sensitive schema redacts on every off-box surface. The hook is nil only
  when the schemas artefact is absent — but this fn is reached only AFTER the
  schemas-owned validator ran (a `:schema` was declared + a validator was
  registered), so the hook is bound whenever a sensitive failure can fire; the
  `(when redact-fn …)` guard is belt-and-braces (no schemas artefact ⇒ no
  schema to redact against)."
  [cofx-id value explanation schema failing-id frame-id]
  (let [redact-fn (late-bind/get-fn-cached :schemas/redact-validation-tags)
        ;; The off-box slots shared by the trace and the throw's ex-data.
        ;; Redaction scrubs `:value` / `:explain` and stamps `:sensitive?`
        ;; when `schema` declares any `:sensitive?` slot.
        leak-slots (cond-> {:value value}
                     (some? explanation) (assoc :explain explanation))
        redacted   (if (and redact-fn (some? schema))
                     (redact-fn schema leak-slots)
                     leak-slots)]
    (let [reason (str "Recordable coeffect `" cofx-id "` (supplied, replayed, or "
                      "generated) failed its `reg-cofx` `:schema`. An "
                      "out-of-contract recordable value is corrupt durable state "
                      "— it folds into the epoch ledger and replays verbatim — so "
                      "this is a hard error in dev AND production. Fix the value "
                      "to satisfy the declared `:schema` (or correct the schema). "
                      "See `:explain` for the validation detail.")]
      (when-let [emit-error-both! (late-bind/get-fn-cached :error-emit/emit-error-both)]
        (emit-error-both! :rf.error/cofx-value-invalid nil failing-id frame-id nil 0 (interop/now-ms)
                          (cond-> {:rf.cofx/id        cofx-id
                                   :failing-id        failing-id
                                   :rf.trace/event-id failing-id
                                   :value             (:value redacted)
                                   :reason            reason
                                   :recovery          :no-recovery}
                            (contains? redacted :explain) (assoc :explain (:explain redacted))
                            (:sensitive? redacted)        (assoc :sensitive? true)
                            frame-id                      (assoc :frame frame-id))))
      (error/throw-error! :rf.error/cofx-value-invalid 'rf/reg-cofx reason
                          {:extra (cond-> {:rf.cofx/id cofx-id
                                           :failing-id failing-id
                                           :value      (:value redacted)
                                           :explain    (:explain redacted)}
                                    (:sensitive? redacted) (assoc :sensitive? true))}))))

(defn- validate-recordable-value!
  "Validate a recordable `value` for `cofx-id` against its registration's
  `:schema` (from `meta`). A no-op when the registration declares no
  `:schema`, when no validator is registered (schemas artefact absent / set
  to nil), or when the value conforms; otherwise emits
  `:rf.error/cofx-value-invalid` and THROWS (a production hard error).

  Routes through the shared `:schemas/validate-with-registered-fn` /
  `:schemas/explain-with-registered-fn` late-bind seam (the same one
  `re-frame.spec/validate-at-boundary-interceptor` uses). FAILS CLOSED on a
  validator that throws (a malformed schema, or a non-schemas validator that
  escapes its isolation) — coercing the throw to a PASS would fold an
  unvalidated value into the durable ledger, the exact fail-OPEN class this
  check exists to close. Returns `value` on success."
  [cofx-id value meta failing-id frame-id]
  (if-let [schema (:schema meta)]
    (let [validate-fn (late-bind/get-fn-cached :schemas/validate-with-registered-fn)]
      (if (nil? validate-fn)
        ;; No validator registered (schemas artefact absent, or
        ;; `set-schema-validator!` called with nil) → nil = "every value
        ;; passes"; the check is a no-op (Spec 010 §Non-Malli validators).
        value
        (let [ok? (try (validate-fn schema value)
                       (catch #?(:clj Throwable :cljs :default) _ false))]
          (if ok?
            value
            (let [explain-fn  (late-bind/get-fn-cached :schemas/explain-with-registered-fn)
                  explanation (when explain-fn
                                (try (explain-fn schema value)
                                     (catch #?(:clj Throwable :cljs :default) _ nil)))]
              ;; Pass `schema` so the emit redacts the value-bearing slots
              ;; (trace tags AND thrown ex-data) when the schema marks any
              ;; slot `:sensitive?` — fail-closed off-box (rf2-hdi6wr).
              (emit-cofx-value-invalid! cofx-id value explanation schema failing-id frame-id))))))
    value))

;; ---- structural-EDN check of GENERATED recordable values ------------------
;;    (EP-0017 erratum rf2-rmroo4 slice B — rf2-uqz2ir)
;;
;; A GENERATED recordable value rides the durable causal record (it is written
;; back into the in-flight `:rf.cofx`, folded into the epoch ledger, replayed
;; verbatim, shipped in the SSR payload, exported, re-read by Xray / pair
;; tooling). So — exactly like a SUPPLIED recordable value at the dispatch
;; boundary (slice A, router/diagnostics.cljc) — it MUST be ordinary EDN data
;; (EP-0017:386). A generator that mints a host handle (a DOM node, Promise,
;; function, atom, Date, JS / Java object) breaks that contract SILENTLY: the
;; failure surfaces far away at replay / Xray / SSR time, not at the generator.
;; This dev-time guard catches the author error AT THE SOURCE — the moment the
;; generator produces it, before the write-back — reusing the slice-A walker
;; (`re-frame.recordable`) and error shape (`:rf.error/cofx-value-invalid`,
;; reason `:non-edn-recordable-value`).
;;
;; ALWAYS-ON — NOT gated on `interop/debug-enabled?` (rf2-q34j26, EP-0017 Open
;; Issue 9 — structural EDN always, hard error in production too), matching the
;; slice-A supplied-value walk (router/diagnostics.cljc) now hardened the same
;; way: a generated host handle folds a non-EDN value into the durable record
;; (epoch ledger / replay / SSR / Xray), corrupt durable state in production as
;; much as dev. The declared-`:schema` check (`validate-recordable-value!`,
;; above) is the complementary always-on per-supplier causal-token contract;
;; this structural always-EDN gate is the floor that fires even when no
;; `:schema` was declared.

(defn- validate-generated-recordable-value!
  "ALWAYS-ON structural-EDN check of a GENERATED recordable `value` for
  `cofx-id`, run at the generator write-back site BEFORE the value is folded
  into the in-flight `:rf.cofx` causal record (rf2-rmroo4 slice B;
  production-hardened rf2-q34j26). The first non-recordable leaf throws
  `:rf.error/cofx-value-invalid` (reason `:non-edn-recordable-value`); a
  fully-EDN value passes and returns `value`.

  ALWAYS-ON — NOT gated on `interop/debug-enabled?` (EP-0017 §3 / §5 / Open
  Issue 9 — structural EDN always, hard error in production too): a generated
  value that is a host handle folds a non-EDN value into the durable causal
  record (written back into `:rf.cofx`, captured in the epoch ledger, replayed,
  shipped in the SSR payload, read by Xray) — corrupt durable state, not a dev
  nicety, the same causal-token contract the declared-`:schema` check already
  enforces always-on. The walk runs once per generated fact and short-circuits
  at the first non-EDN leaf; generation is already the rare slice-B branch (a
  declared-absent generator-backed fact under `:live`). The declared-`:schema`
  check stays the complementary always-on production contract; this is the
  structural always-EDN floor that fires even with no `:schema` (closing the
  EP-0017 errata tail — a generator without a `:schema` minting a host handle
  no longer escapes to a far-away replay / Xray / SSR failure).

  The `:generated` arm of the shared `value-check/check-edn-value!` (rf2-6zfzxy)
  — its supplied-value twin (slice A) lives at the dispatch boundary
  (`router.diagnostics`). The throw propagates from inside the generator's
  HandlerScope, so the failure carries the cofx's source-coord like every other
  cofx emit."
  [cofx-id value failing-id frame-id]
  (value-check/check-edn-value! :generated cofx-id value failing-id nil frame-id))

;; ---- generation at processing-start (EP-0017 §5 step 3 / slice B.7) --------
;;
;; A declared-absent recordable fact that is generator-backed (a `reg-cofx`
;; with a value-returning supplier, NOT `:provided?`) runs its generator at
;; processing-start when the mint policy permits. The generator runs under the
;; cofx's HandlerScope (so a throw is attributed to the cofx via
;; `:rf.error/coeffect-exception`, mirroring the ambient path) and the
;; platform gate (a `:platforms`-excluded generator does not run — that fact
;; is then absent and surfaces as missing-required, never a half-generated
;; nondeterministic value). The produced value is validated against `:schema`
;; (a production hard error on mismatch) and written back into the in-flight
;; `:rf.cofx` record so the epoch captures the post-generation token.

(defn- generator-backed?
  "True when `meta` is a RECORDABLE registration with a generator — i.e. a
  recordable, non-provided cofx carrying a value-returning supplier (the
  `:handler-fn` slot). A `:provided?` recordable fact has no generator (its
  owner stamps the token); an ambient fact is not recordable. Generation
  applies only to generator-backed recordable facts."
  [meta]
  (and (:recordable? meta)
       (not (:provided? meta))
       (some? (:handler-fn meta))))

(defn- run-generator
  "Run a generator-backed recordable supplier at processing-start, returning
  `[outcome value]` where `outcome` is `:generated` (value produced + emitted
  + schema-validated), `:skipped` (platform gate — the fact is not produced,
  the caller treats it as missing-required), or `:threw` (the generator threw
  — emits `:rf.error/coeffect-exception` and the caller skips the handler).

  A successful run emits the dev-only `:rf.cofx/generated` trace op (fact-name
  + supplier id) so traces are self-describing even though the record is flat,
  then validates the produced value: against the registration's `:schema` (a
  PRODUCTION hard error on mismatch — the validation throw propagates) AND
  structurally against the recordable-EDN walker (rf2-rmroo4 slice B /
  rf2-uqz2ir / production-hardened rf2-q34j26 — a generator that mints a non-EDN
  host handle throws `:rf.error/cofx-value-invalid` reason
  `:non-edn-recordable-value` in dev AND production, BEFORE the value is written
  back into the durable record). The emit / scope shape
  mirrors `run-ambient-supplier`; the op differs (`:rf.cofx/generated` vs
  `:rf.cofx/run`) because generation produces a RECORDED fact, not an ambient
  read."
  [cofx-id meta supplier arg frame-id failing-id]
  (let [active-platform (active-platform-for-frame frame-id)]
    (if (cofx-runs-on-platform? meta active-platform)
      (trace/with-handler-scope
        (trace/handler-scope-from-meta :cofx cofx-id meta)
        (let [valued? (not (identical? arg no-arg))
              outcome (try
                        [:generated (if valued? (supplier arg) (supplier))]
                        (catch #?(:clj Throwable :cljs :default) t
                          (emit-coeffect-exception! cofx-id failing-id frame-id t)
                          [:threw nil]))]
          (when (= :generated (first outcome))
            ;; Validate the produced value against `:schema` (production hard
            ;; error) BEFORE emitting the dev `:rf.cofx/generated` trace. A
            ;; miss throws `:rf.error/cofx-value-invalid` from here, inside the
            ;; scope binding, so the failure carries the cofx's source-coord
            ;; like every other cofx emit.
            ;;
            ;; rf2-0mjgx6 — validation runs FIRST so a schema-invalid generated
            ;; value never reaches the `:rf.cofx/generated` trace. The earlier
            ;; order (emit THEN validate) leaked a schema-`{:sensitive? true}`
            ;; produced value verbatim on `:rf.cofx/generated` before the
            ;; (correctly-redacted) `:rf.error/cofx-value-invalid` fired —
            ;; `:rf.cofx/generated`'s marks projection (`project-cofx-run-tags`)
            ;; redacts only explicit `:sensitive` reg-marks, not schema-slot
            ;; `:sensitive?`, so the failing value egressed to trace listeners /
            ;; epoch `:trace-events` / MCP / log sinks unredacted. Validating
            ;; first means the throw aborts before the emit, so the raw value
            ;; never ships on ANY trace on the failure path. (The structural
            ;; EDN-always check runs AFTER `:schema` so a declared `:schema`
            ;; mismatch — the prod contract — is reported first; rf2-rmroo4
            ;; slice B / rf2-uqz2ir.)
            (validate-recordable-value! cofx-id (second outcome) meta failing-id frame-id)
            ;; Structural EDN-always check of the GENERATED value (rf2-rmroo4
            ;; slice B, rf2-uqz2ir; production-hardened rf2-q34j26): a generator
            ;; that mints a host handle fails loudly HERE — at the source, before
            ;; the write-back into the durable `:rf.cofx` record — not far away
            ;; at replay / Xray / SSR. ALWAYS-ON (production hard error too);
            ;; reuses the slice-A walker + error shape. Runs AFTER `:schema` so a
            ;; declared `:schema` mismatch is reported first, and BEFORE the
            ;; `:rf.cofx/generated` emit (rf2-0mjgx6) so a non-EDN host handle
            ;; never ships on the dev trace either.
            (validate-generated-recordable-value! cofx-id (second outcome) failing-id frame-id)
            ;; Dev-only `:rf.cofx/generated` — fact-name + supplier id (the
            ;; cofx id is both). Gated on `interop/debug-enabled?` so
            ;; production DCEs the tag-map + emit, exactly like
            ;; `:rf.cofx/run`. The generated value itself rides the durable
            ;; `:rf.cofx` record (always-on), NOT this dev trace. Emitted ONLY
            ;; after both validations pass — a VALID generated value's
            ;; `:rf.cofx/value` is still projected through the marks chokepoint
            ;; (`project-cofx-run-tags`) for any explicit `:sensitive` reg-mark.
            (when interop/debug-enabled?
              (trace/emit! :rf.cofx :rf.cofx/generated
                           (cond-> {:rf.cofx/id    cofx-id
                                    :frame         frame-id
                                    :rf.cofx/value (second outcome)}
                             valued? (assoc :rf.cofx/arg arg)))))
          outcome))
      (do
        (trace/emit! :warning :rf.cofx/skipped-on-platform
                     {:rf.cofx/id                   cofx-id
                      :frame                        frame-id
                      :rf.cofx/platform             active-platform
                      :rf.cofx/registered-platforms (:platforms meta)
                      :recovery                     :skipped})
        [:skipped nil]))))

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
          ;; `:rf.cofx/value` carries the supplier's PRODUCED value — the
          ;; coeffect that actually egresses into `:coeffects` — so the
          ;; marks chokepoint (`marks/project-cofx-run-tags`, wired to
          ;; `:rf.cofx/value`) redacts a declared-`:sensitive` produced
          ;; value before it surfaces in trace. The requirement-arg rides
          ;; under the distinct `:rf.cofx/arg`, present only for a
          ;; parameterized `[id arg]` requirement (rf2-sepqgg). Both ride
          ;; under the `interop/debug-enabled?` gate so production DCEs them.
          (when (and interop/debug-enabled? (= :delivered (first outcome)))
            (trace/emit! :rf.cofx :rf.cofx/run
                         (cond-> {:rf.cofx/id    cofx-id
                                  :frame         frame-id
                                  :rf.cofx/value (second outcome)}
                           valued?           (assoc :rf.cofx/arg arg)
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

(def default-mint-policy
  "The mint policy when no binding point selects one — the router's `:live`
  default (EP-0017 §6). `:live` generates declared-absent generator-backed
  recordable values; the binding points (`resolve-mint-policy`) select
  `:strict` (the `:test` preset default; hard-wired for replay) or
  `:explicit-live` (the declared-nondeterminism escape)."
  :live)

(def mint-policies
  "The closed set of EP-0017 §6 mint-policy values. `:live` (router default)
  and `:explicit-live` (declared-nondeterminism escape) generate a
  declared-absent generator-backed recordable fact; `:strict` (the `:test`
  preset default; hard-wired for replay) does not. An unrecognised value is
  treated CONSERVATIVELY as non-generating (see `mint-policy-generates?`) — an
  unknown policy must never silently mint a nondeterministic value into the
  durable ledger — but the registration/dispatch boundary uses this set to
  reject typos loudly where it can."
  #{:live :strict :explicit-live})

(defn resolve-mint-policy
  "Resolve the effective cofx mint policy for a dispatch, MOST-SPECIFIC-WINS
  (EP-0017 §6 binding points, slice-B.8):

    1. `per-call`   — the `:rf.cofx/mint-policy` dispatch opt (a Tool-Pair
                      replay supplies `:strict`; a nondeterminism-declaring
                      test supplies `:explicit-live`);
    2. `frame-cfg`  — the `:rf.cofx/mint-policy` key on the frame's config
                      (the `:test` preset expands to `:strict`);
    3. `:live`      — the router default, when neither is present.

  Returns the first non-nil of `per-call`, `frame-policy`, else
  `default-mint-policy`. The value is NOT validated here — an unrecognised
  policy is treated conservatively as non-generating by
  `mint-policy-generates?` (fail-safe: an unknown policy never mints), and the
  dispatch boundary (`known-dispatch-opts`) is where a typo'd OPT KEY is
  surfaced. This keeps the hot satisfaction path branch-light while preserving
  the no-silent-mint invariant."
  [per-call frame-policy]
  (or per-call frame-policy default-mint-policy))

(defn- mint-policy-generates?
  "True when `policy` runs a generator for a declared-absent generator-backed
  recordable fact — `:live` (router default) and `:explicit-live`
  (declared-nondeterminism test escape) generate; `:strict` does not (an
  absent fact is missing-required, no host read). An unrecognised policy is
  treated CONSERVATIVELY as non-generating (strict) — an unknown policy must
  not silently mint a nondeterministic value into the durable ledger."
  [policy]
  (or (= policy :live) (= policy :explicit-live)))

(defn deliver-declared-cofx
  "Deliver the handler's declared coeffects FLAT into `coeffects`, per Spec
  002 §Satisfaction algorithm step 4 + EP-0017 §5. `requires` is the parsed
  entry vector (`parse-requires`); `recorded` is the token's `:rf.cofx` map;
  `frame-id` tags the trace / error emits. Returns
  `{:coeffects <augmented> :rf.cofx <record> :rf/skip-handler? <bool>}`.

  For each declared id (declaration order):

    - **unregistered** → `:rf.error/unregistered-cofx` (the typo case; halts);
    - **ambient** → run its supplier now, deliver the value (never recorded);
      a platform-skipped supplier delivers nothing;
    - **recordable, present on the token** → validate against the
      registration's `:schema` (a production hard error on mismatch —
      `:rf.error/cofx-value-invalid`), then deliver the recorded value
      verbatim (no host read);
    - **recordable, absent, generator-backed** → consult the mint policy
      (EP-0017 §6): `:live` / `:explicit-live` run the generator at
      processing-start (emit `:rf.cofx/generated`, validate against `:schema`,
      write the value back into the returned `:rf.cofx` record so the epoch
      captures the post-generation token), `:strict` fails with
      `:rf.error/missing-required-cofx`;
    - **recordable, absent, provided** → `:rf.error/missing-required-cofx`
      (every mode).

  Delivery is DECLARED-ONLY and FLAT: an undeclared leaf on the token is not
  staged, and there is no nested `:cofx` / `:rf.cofx` duplicate in the
  delivered spread (the envelope's canonical `:rf.cofx` map is reachable
  through the context for generic code).

  `mint-policy` (EP-0017 §6) gates generation; it defaults to `:live` (the
  router default) when omitted. The returned `:rf.cofx` is the (possibly
  generation-augmented) record — equal to `recorded` when no fact was
  generated; the caller restamps the in-flight token / context `:rf.cofx`
  with it so the canonical record carries every generated fact.

  An unregistered, missing-required, or schema-invalid declared fact halts
  loudly (throws); a supplier / generator that THREW emits
  `:rf.error/coeffect-exception` and sets `:rf/skip-handler?` so the handler
  does not run and the cascade is failed without a raw throw escaping context
  assembly (mirroring the retired `inject-cofx` interceptor's
  capture-don't-propagate behaviour)."
  ([coeffects requires recorded failing-id frame-id]
   (deliver-declared-cofx coeffects requires recorded failing-id frame-id default-mint-policy))
  ([coeffects requires recorded failing-id frame-id mint-policy]
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
           (cond
             ;; Present on the token (supplied / replayed) → validate the
             ;; value against `:schema` (production hard error on mismatch),
             ;; then deliver verbatim. Supplied values win, but the `:schema`
             ;; is the type of the replay hole — folding an out-of-contract
             ;; value into the ledger is corrupt durable state.
             (contains? (:rf.cofx acc) id)
             (let [value (get (:rf.cofx acc) id)]
               (validate-recordable-value! id value meta failing-id frame-id)
               (assoc-in acc [:coeffects id] value))

             ;; Absent + generator-backed → consult the mint policy. `:live` /
             ;; `:explicit-live` run the generator at processing-start, write
             ;; the value into BOTH the delivered spread AND the in-flight
             ;; `:rf.cofx` record (so the epoch captures it and replay
             ;; re-presents it); `:strict` (and any unrecognised policy) fails
             ;; with missing-required (no host read).
             (generator-backed? meta)
             (if (mint-policy-generates? mint-policy)
               (let [[outcome value]
                     (run-generator id meta (:handler-fn meta) arg frame-id failing-id)]
                 (case outcome
                   :generated (-> acc
                                  (assoc-in [:coeffects id] value)
                                  (assoc-in [:rf.cofx id] value))
                   ;; Platform-skipped generator → the fact is not produced;
                   ;; treat it as missing-required (a half-skipped generated
                   ;; fact must not surface as a silent nil).
                   :skipped   (emit-missing-required-cofx! id failing-id frame-id)
                   :threw     (assoc acc :rf/skip-handler? true)))
               (emit-missing-required-cofx! id failing-id frame-id))

             ;; Absent + provided (no generator) → missing-required, every mode.
             :else
             (emit-missing-required-cofx! id failing-id frame-id))

           :else                                   ;; ambient
           (let [[outcome value]
                 (run-ambient-supplier id meta (:handler-fn meta) arg frame-id failing-id)]
             (case outcome
               :delivered (assoc-in acc [:coeffects id] value)
               :skipped   acc
               :threw     (assoc acc :rf/skip-handler? true))))))
     {:coeffects coeffects :rf.cofx recorded :rf/skip-handler? false}
     requires)))

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

;; ---- shared always-on removed-API thrower (rf2-8au0w6) --------------------
;;
;; The removed-public-API "throwing stub" pattern that ALSO fans out on the
;; always-on observability channel (a catalogued Spec 009 §Error-event
;; category — `:rf.error/inject-cofx-removed`, `:rf.error/reg-event-*-removed`)
;; was hand-rolled per surface: the same three-step body (surface on the
;; always-on `:error-emit/dispatch-on-error` listener → emit the dev
;; `:rf.error/*` trace → throw the canonical `error/throw-error!` hard error).
;; `raise-removed!` is the ONE shared fan-out thrower both surfaces delegate
;; to (the EP-0017 `inject-cofx` stub here, and the EP-0018 reg-event removed
;; names via `re-frame.events/raise-removed-reg-event!`, which requires this
;; ns). Lives here rather than in `re-frame.error` because the fan-out needs
;; `late-bind` + `trace`, both of which require `error` — pushing it into
;; `error` would close a load cycle. Behaviour is byte-identical to the prior
;; bespoke bodies: the caller passes its exact `error-kw` / `where` / `reason`
;; / offending `id` and the `id-key` under which the id rides the trace tag +
;; thrown ex-data (`:id` for reg-event, `:rf.cofx/id` for inject-cofx). The
;; std-interceptor removed values (EP-0022) ride no catalogued 009 category, so
;; they throw `error/throw-error!` directly (no fan-out) and do NOT use this.
(defn raise-removed!
  "Fan out + throw a removed-public-API hard error that rides the always-on
  Spec 009 observability channel (rf2-8au0w6). Surfaces `error-kw` on the
  always-on `:error-emit/dispatch-on-error` listener (production-survivable),
  emits the dev `:rf.error/*` trace, then throws the canonical
  `error/throw-error!` hard error attributed to `where` with `reason`. The
  offending `id` (the removed registrar/cofx call's first arg, or nil) rides
  the trace tag + thrown ex-data under `id-key` when present. Never returns
  normally. The ONE place the fan-out throw mechanics live; every fan-out
  removed stub delegates here so the next such removal is a data edit."
  [error-kw where reason id id-key]
  ;; Both channels via the shared helper (rf2-c4oycd): axis 1 the always-on
  ;; listener (production-survivable), axis 2 the dev trace (DCEs in CLJS prod).
  ;; Reached via the `:error-emit/emit-error-both` hook (cofx cannot
  ;; static-require error-emit — load cycle). `elapsed-ms 0`.
  (when-let [emit-error-both! (late-bind/get-fn-cached :error-emit/emit-error-both)]
    (emit-error-both! error-kw nil id nil nil 0 (interop/now-ms)
                      (cond-> {:reason reason :recovery :no-recovery}
                        (some? id) (assoc id-key id))))
  (error/throw-error! error-kw where reason
                      {:recovery :no-recovery
                       :extra    (when (some? id) {id-key id})}))

(defn inject-cofx
  "REMOVED in EP-0017 (no alias). Calling `inject-cofx` is the hard error
  `:rf.error/inject-cofx-removed`, naming `:rf.cofx/requires` as the
  replacement. See `re-frame.events/reg-event` and spec/001-Registration.md
  §`inject-cofx` is removed."
  [& args]
  (let [cofx-id (first args)
        reason  (str "`inject-cofx` is REMOVED in EP-0017 (no alias). "
                     "Declare the coeffect on the handler's registration "
                     "metadata instead: "
                     "`{:rf.cofx/requires [" (if cofx-id (pr-str cofx-id) ":your/cofx") "]}`. "
                     "The declared value arrives flat in the coeffects map "
                     "under its id; the registration's grade decides replay "
                     "semantics. See spec/001-Registration.md §`inject-cofx` "
                     "is removed.")]
    (raise-removed! :rf.error/inject-cofx-removed 'rf/inject-cofx reason
                    cofx-id :rf.cofx/id)))

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
