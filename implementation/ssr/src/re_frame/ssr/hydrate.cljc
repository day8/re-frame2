(ns re-frame.ssr.hydrate
  "The `:rf/hydrate` event + hydration-mismatch detection. Per Spec 011
  §The :rf/hydrate event and §Hydration-mismatch detection.

  The server's payload carries `:rf/render-hash`; the handler replaces
  app-db with `:rf/app-db` AND stashes the server hash under
  `[:rf.runtime/ssr :hydration :server-hash]` so `verify-hydration!`
  can read it after the client's first render.

  Also defines the two `:rf.ssr/check-*` compatibility-check fxs the
  hydrate handler dispatches — best-effort version and
  schema-digest comparison whose mismatch emits a structured warning
  trace without crashing the hydration path.

  All `reg-event` / `reg-fx` calls live in the `re-frame.ssr`
  façade so a `(require 're-frame.ssr :reload)` after
  `(registrar/clear-all!)` re-installs them. This namespace exports
  the handler fns only."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.projection :as projection]
            [re-frame.ssr.hash :as hash]
            [re-frame.ssr.payload-policy :as payload-policy]
            [re-frame.trace :as trace]))

(defn- client-platform?
  "Resolve the active platform for `frame-id` and answer whether
  client-only fxs would actually execute. Mirrors the resolution in
  `router/run-fx-effects!` — per-frame `:config :platform` override
  wins over the host-wide `interop/active-platform` marker. Used by
  the hydrate handler to avoid dispatching `:rf.ssr/check-*` fxs on a
  server-side `:rf/hydrate` (test harness, isomorphic loopback), where
  the fx's own `:platforms #{:client}` gate would otherwise emit a
  `:rf.fx/skipped-on-platform` warning per check."
  [frame-id]
  (let [resolved (or (some-> frame-id frame/frame :config :platform)
                     (interop/active-platform))]
    (= :client resolved)))

(declare hydrate-event-handler*)

(defn- slice-malformed-reason
  "Reason string for a PRESENT-but-non-map partition slice (`:rf/app-db`
  or `:rf/runtime-db`). Factored out so both partition checks fail closed
  identically (Spec 011 §The :rf/hydrate event — \"Both partitions
  validate fail-closed before installation\")."
  [slice-key slice-val]
  (str "the :rf/hydrate payload's " slice-key " slice is not a map (got "
       (cond (nil? slice-val) "nil"
             :else (pr-str (type slice-val)))
       "); hydration rejected — the client frame-state is left unchanged"))

(defn- malformed-hydration-payload-reason
  "Fail-closed guard for the `:rf/hydrate` boundary.
  The payload is a DESERIALISED, UNTRUSTED transport input (the server's
  `pr-str`'d EDN, round-tripped through `cljs.reader/read-string` at the
  boot site). `:replace-frame-state` is the locked merge policy (Spec 011
  §The :rf/hydrate event), so the resolved `:rf/app-db` becomes the ENTIRE
  client app-db AND the resolved `:rf/runtime-db` becomes the runtime-db
  partition. Blindly installing a non-map slice (a string, vector,
  number — a corrupt / hostile / version-skewed payload) silently coerces
  the malformed input to a successful hydration: the same fail-OPEN class
  the schemas / routing sweeps closed at their boundaries.

  Per Spec 011 §The :rf/hydrate event BOTH partitions validate fail-closed
  before installation: returns a `:rf.error/*` reason string when `payload`
  is not a map, or when EITHER the `:rf/app-db` OR `:rf/runtime-db` slice
  KEY is present but its value is not a map (incl. an explicit nil / false /
  non-collection); nil when the payload is structurally acceptable (a map,
  with each partition key either absent or carrying a map). A wholly-ABSENT
  app-db / runtime-db slice key is NOT malformed — it is the documented
  client-only / no-server-slice first-load shape that falls back to the
  existing partition value."
  [payload]
  (cond
    (not (map? payload))
    (str "the :rf/hydrate payload is not a map (got "
         (cond (nil? payload) "nil" :else (pr-str (type payload)))
         "); hydration rejected — the client app-db is left unchanged")

    ;; The `:rf/app-db` slice KEY is present but its value is not a map.
    ;; Installing a non-map (string / number / vector / nil / false) as
    ;; the whole app-db would corrupt the frame, so a PRESENT-but-non-map
    ;; slice is rejected (a wholly-absent key is the legitimate
    ;; no-server-slice fallback and is NOT flagged here).
    (and (contains? payload :rf/app-db)
         (not (map? (:rf/app-db payload))))
    (slice-malformed-reason :rf/app-db (:rf/app-db payload))

    ;; The `:rf/runtime-db` slice KEY is present but its value is not a
    ;; map. Hydration installs a coherent frame state: `:rf/runtime-db`
    ;; becomes the runtime-db partition
    ;; (machine snapshots, route slice, SSR metadata). A present-but-non-map
    ;; runtime-db is rejected the same way as app-db. A
    ;; wholly-absent key is the legitimate no-server-runtime fallback.
    (and (contains? payload :rf/runtime-db)
         (not (map? (:rf/runtime-db payload))))
    (slice-malformed-reason :rf/runtime-db (:rf/runtime-db payload))

    :else nil))

(defn- frame-id-mismatch-reason
  "Fail-closed guard for the `:rf/hydrate` boundary. Returns a
  reason string when the payload's `:rf/frame-id` is PRESENT-and-DIFFERENT
  from the frame the dispatch is installing into (`target` — the
  `:rf.frame/id` coeffect), nil otherwise.

  The payload's `:rf/frame-id` is the SSR-wire spelling of the frame the
  server rendered under:
  payload metadata + frame-isolation evidence, NOT a no-opts target
  resolver. The boot helper `re-frame.ssr.boot/hydrate!` validates it
  against the explicit `:frame` target BEFORE dispatching (and THROWS on a
  conflict — its documented pre-dispatch contract). But `hydrate!`'s own
  docstring recommends direct `dispatch-sync [:rf/hydrate payload] {:frame
  target}` as the split path for hosts that verify the mounted DOM
  themselves, so the boundary is not purely private: a direct dispatch
  bypassed the boot check and silently installed the payload into `target`
  even when `:rf/frame-id` named a different frame. The check is
  therefore enforced HERE, at the handler boundary BOTH paths cross, so a
  manual boot / custom host cannot hydrate the wrong frame.

  A payload carrying NO `:rf/frame-id` is NOT a conflict (a host that
  omitted it, or a client-only/no-server-slice page) — there is nothing to
  disagree with, so the dispatch target stands. Only a PRESENT-and-
  DIFFERENT frame-id is a mismatch. The reason mirrors the boot helper's
  `:rf.error/hydration-frame-id-mismatch` sentence (one diagnostic
  vocabulary across both paths)."
  [target payload]
  (let [payload-frame-id (:rf/frame-id payload)]
    (when (and (some? payload-frame-id)
               (not= payload-frame-id target))
      (str "the :rf/hydrate payload's :rf/frame-id '" payload-frame-id
           "' (the frame the server rendered under) conflicts with the "
           "dispatch target frame '" target "'; hydration rejected — the "
           "runtime will not silently install a server slice into a "
           "different frame than the one it was rendered for. Pass the same "
           "frame the server stamped, or correct the server's render frame."))))

(defn- emit-rejected-hydration!
  "Emit a fail-closed `:rf/hydrate` rejection on BOTH the dev-trace axis
  (gated by `interop/debug-enabled?`) and the always-on error-emit axis
  (UNGATED, via the `:error-emit/dispatch-error-record` late-bind hook).
  Shared by the malformed-payload guard and the frame-id-mismatch guard
  (`:rf.error/hydration-frame-id-mismatch`) surface identically — each is a
  fail-closed BOUNDARY rejection of an untrusted payload, not a dev teaching
  diagnostic, so an off-box shipper on a `goog.DEBUG=false` client build
  (development trace elided) must still see it. `extra` is
  merged onto the always-on record (the frame-id guard carries
  `:target-frame` / `:payload-frame-id`, mirroring the boot helper's thrown
  ex-data); the dev-trace tags carry it too so Xray sees the same shape.

  Egress choke point: `extra` lifts
  values OUT OF the DESERIALISED, UNTRUSTED hydration payload — the
  frame-id-mismatch guard stamps `:payload-frame-id (:rf/frame-id payload)`,
  a value the adversary-controllable wire put there. The always-on record
  fans out to corpus listeners (Sentry / Datadog) raw `by contract`, so a
  payload value placed in `extra` would egress off-box UNREDACTED — the
  same leakage class as any unprojected error payload. The untrusted `extra`
  slots route through the centralized
  record-level boundary primitive `re-frame.projection/project-egress` (over
  the shared `elide-wire-value` walker — Security.md §The privacy surface:
  per-tool reimplementation prohibited; sinks consume already-projected
  records only) under the off-box-observability profile, seeded at the
  rejected `frame`, BEFORE the record fans out. A frame that declares the
  `:payload-frame-id` path `:sensitive` redacts it; an unresolvable /
  FRAMELESS frame fails CLOSED (the whole `extra` value redacts to
  `:rf/redacted` rather than ride raw). The
  dev-trace axis (DCE'd in production) keeps the raw `extra` + raw `:reason`
  for local Xray fidelity — the leak is off-box, not the local trace.

  `:reason` is framework-AUTHORED prose, BUT the frame-id-mismatch sentence
  INTERPOLATES the untrusted `:payload-frame-id` into its text (`'<value>'`),
  so a corpus listener reading the always-on record's `:reason` would see the
  raw value the structural `:payload-frame-id` slot just redacted. The off-box
  record therefore SCRUBS the redacted value's rendering out of the reason
  (replacing the raw `pr-str` occurrence with the `:rf/redacted` sentinel)
  whenever the projection redacted that slot — so the value cannot leak via
  the prose copy either. When the projection did NOT redact it (a frame that
  does not declare the path sensitive), the reason rides as authored."
  ([error-id frame reason] (emit-rejected-hydration! error-id frame reason nil))
  ([error-id frame reason extra]
   (let [base {:where      'rf.ssr/hydrate
               :frame      frame
               :failing-id :rf/hydrate
               :reason     reason
               :recovery   :no-recovery}]
     (when interop/debug-enabled?
       (trace/emit-error! error-id (merge base extra)))
     ;; The frame-scoped shape guard rides the always-on axis alongside the
     ;; development trace.
     ;; UNGATED. Union record shape via the late-bind hook. (The PRE-FRAME
     ;; parse failure in `boot/read-server-payload` is the FRAMELESS sibling
     ;; of this same category.)
     ;;
     ;; The untrusted payload-derived `extra` slots route through
     ;; `project-egress` (off-box-observability, frame-seeded) so a
     ;; deserialised payload value never fans out to a corpus listener raw.
     ;; Fail-closed on an unresolvable / frameless frame (whole-value redact).
     (when-let [dispatch-error-record!
                (late-bind/get-fn :error-emit/dispatch-error-record)]
       (let [safe-extra  (when (seq extra)
                           (projection/project-egress
                             extra
                             {:frame             frame
                              :rf.egress/profile :rf.egress/off-box-observability}))
             ;; The reason prose interpolates the raw `:payload-frame-id` (via
             ;; string concatenation: `'<value>'`). If the projection redacted
             ;; that slot, scrub the raw value's rendering out of the reason so
             ;; it does not leak via the prose copy. Matches the `str`-form the
             ;; mismatch sentence interpolates (`'" payload-frame-id "'`).
             raw-pfid    (:payload-frame-id extra)
             redacted?   (and (contains? extra :payload-frame-id)
                              (not= raw-pfid (:payload-frame-id safe-extra)))
             safe-reason (if (and redacted? (string? reason) (some? raw-pfid))
                           (str/replace reason
                                        (str "'" raw-pfid "'")
                                        (str ":rf/redacted"))
                           reason)]
         (dispatch-error-record!
           (merge {:error  error-id
                   :time   (interop/now-ms)}
                  base
                  {:reason safe-reason}
                  safe-extra)))))))

(defn hydrate-event-handler
  "Handler fn for the `:rf/hydrate` event. Replaces app-db with
  `(:rf/app-db payload)`, stashes server-hash + version under
  `[:rf.runtime/ssr :hydration]`, and dispatches the two
  `:rf.ssr/check-*` fxs when the resolved platform is `:client`; server-side
  hydration skips them to avoid
  `:rf.fx/skipped-on-platform` noise).

  The handler fails closed on a malformed, deserialised
  untrusted) payload: a non-map payload, or a present-but-non-map app-db
  slice, is REJECTED — the existing client app-db is left unchanged and a
  `:rf.error/malformed-hydration-payload` diagnostic is emitted (carrying
  `:frame`). Hydration is best-effort by contract (Spec 011 §The
  :rf/hydrate event — degraded-but-running), so a corrupt payload must
  never silently install garbage as the whole app-db.

  The handler also fails closed on a frame-id mismatch: when
  the payload's `:rf/frame-id` is present-and-different from the dispatch
  target (`:rf.frame/id`), the server's slice was rendered for a DIFFERENT
  frame, so installing it here would defeat the frame-isolation evidence the
  payload carries. The boot helper `hydrate!` validates + THROWS pre-dispatch,
  but its docstring recommends direct `dispatch-sync [:rf/hydrate payload]`
  as the split path for hosts that verify the mounted DOM themselves — so the
  guard is enforced HERE, at the boundary both paths cross. app-db AND
  runtime-db are left unchanged and `:rf.error/hydration-frame-id-mismatch`
  is emitted (carrying `:target-frame` / `:payload-frame-id`)."
  [{:keys [db] frame :rf.frame/id rt :rf.db/runtime} [_ payload]]
  (let [hydrated-app-db (or (:rf/app-db payload) db)
        ;; A frame-id mismatch is checked BEFORE the structural shape: a
        ;; payload rendered for a DIFFERENT frame is rejected outright,
        ;; regardless of slice shape. Only fires for a present-
        ;; and-DIFFERENT `:rf/frame-id` against a non-nil target — an absent
        ;; frame-id (or nil target) is no conflict.
        frame-mismatch-reason (when (some? frame)
                                (frame-id-mismatch-reason frame payload))]
    (cond
      frame-mismatch-reason
      (do
        (emit-rejected-hydration! :rf.error/hydration-frame-id-mismatch
                                  frame frame-mismatch-reason
                                  {:target-frame     frame
                                   :payload-frame-id (:rf/frame-id payload)})
        ;; Fail CLOSED: leave BOTH partitions untouched, fire no
        ;; compatibility-check fxs (the slice is not for this frame).
        {:db db})

      :else
      ;; Fail CLOSED on a malformed (deserialised, untrusted) payload —
      ;; non-map payload, or present-but-non-map app-db / runtime-db slice
      ;; The slice would otherwise be coerced into
      ;; the partition (a fail-OPEN).
      (if-let [reason (malformed-hydration-payload-reason payload)]
        (do
          (emit-rejected-hydration! :rf.error/malformed-hydration-payload
                                    frame reason)
          {:db db})
        (hydrate-event-handler* db rt frame payload hydrated-app-db)))))

(defn- hydrate-event-handler*
  "The structurally valid hydration path. `hydrated-app-db` is the
  resolved server slice (or the existing `db` when the payload carried no
  app-db slice — the client-only fallback). `current-runtime-db` is the
  `:rf.db/runtime` coeffect; the hydration metadata
  is durable runtime-db state, written under `[:rf.runtime/ssr :hydration]`."
  ;; A cross-feature artefact may reconcile its OWN installed runtime-db
  ;; subtree via the late-bound `:resources/hydrate-runtime-db` hook (Spec
  ;; 016 §SSR and hydration — Resources recomputes its reverse indexes from
  ;; entries, orphans SSR owners, surfaces clock skew). Absent hook leaves
  ;; the runtime-db unchanged (no resources artefact loaded).
  ;;
  ;; That hook reconciles the installed VALUE. Its counterpart for HOST work
  ;; is the `:machines/rearm-after-hydration!` gate on the `:fx` vector
  ;; below (rf2-jqvgp): machine `:after` timers are suppressed server-side
  ;; and cannot ride the wire, so the machines artefact re-arms them from
  ;; the hydrated snapshots once the runtime-db has committed.
  [_app-db current-runtime-db frame-id payload hydrated-app-db]
  (let [version       (:rf/version payload)
        schema-digest (:rf/schema-digest payload)
        ;; Server-settled durable runtime state
        ;; (machine snapshots, route slice) rides the payload's `:rf/runtime-db`
        ;; slice — a coherent runtime-db value the hydrate handler installs into
        ;; the runtime-db partition so the framework subs (`:rf/machine`,
        ;; `[:rf.route/*]`) see the hydrated state, and it survives as durable
        ;; frame-state. This path only installs the payload projection; the
        ;; base for the metadata
        ;; merge is the payload slice when present, else the existing
        ;; runtime-db coeffect.
        ;; A present-but-non-map :rf/runtime-db is already REJECTED by the
        ;; fail-closed guard, so by the time we get here `runtime-db-slice`
        ;; is either absent (nil → no-server-runtime fallback) or a map.
        ;; The `(when (map? runtime-db-slice) runtime-db-slice)` is a
        ;; belt-and-braces no-op on the validated path.
        payload-runtime-db (let [runtime-db-slice (:rf/runtime-db payload)]
                             (when (map? runtime-db-slice) runtime-db-slice))
        hydration-runtime-db-base (or payload-runtime-db current-runtime-db {})
        ;; Declarative hydration-metadata construction — additive,
        ;; nil-pruned. New keys land here as kv pairs without re-ordering
        ;; the previous `cond->` clauses.
        metadata      (into {}
                            (filter (comp some? val))
                            {:server-hash (:rf/render-hash payload)
                             :version     version})
        ;; Gate compatibility checks at the handler rather than effect dispatch
        ;; so server-
        ;; side `:rf/hydrate` runs (test harness, isomorphic loopback)
        ;; don't fire `:rf.fx/skipped-on-platform` per check. The fxs
        ;; themselves remain `:platforms #{:client}` so any direct
        ;; caller still gets the gate; the handler simply doesn't
        ;; request them on a known non-client run.
        client?       (client-platform? frame-id)]
    ;; Per Spec 011 §The :rf/hydrate event: dispatch the compatibility-
    ;; check fxs as part of `:fx` so a mismatch surfaces a structured
    ;; trace event without crashing the hydration path. Both fxs gate on
    ;; payload-key presence — the scalar form passed here is the
    ;; server's value (the "expected"). The two fxs resolve the client-
    ;; side "actual" DIFFERENTLY: `:rf.ssr/check-version` reads the SSR
    ;; artefact's compiled-in `payload-policy/pattern-protocol-version`
    ;; constant (always resolves — the version check never skips), while
    ;; `:rf.ssr/check-schema-digest` resolves through its optional
    ;; `:schemas/app-schemas-digest` late-bind hook (absent hook → the
    ;; comparison is skipped).
    ;;
    ;; SSR hydration metadata is durable,
    ;; serializable framework runtime state (it must survive epoch-restore /
    ;; reconstitution so `verify-hydration!` can compare against it after the
    ;; first client render), so it lives in the frame's RUNTIME-DB partition
    ;; at `[:rf.runtime/ssr :hydration]` (Conventions §Reserved runtime-db
    ;; keys) — NOT in app-db (where it briefly sat under the retired
    ;; `:rf/runtime` root). The handler installs both partitions coherently:
    ;; `:db` (the server's app-db slice) AND `:rf.db/runtime` (the hydration
    ;; metadata). The reference `:rf/hydrate` handler is framework code, so
    ;; emitting the reserved `:rf.db/runtime` effect is in-bounds (decision
    ;; #4 — reserved by convention).
    (cond-> {:db hydrated-app-db
             :fx (cond-> []
                   (and client? version)
                   (conj [:rf.ssr/check-version       version])

                   (and client? schema-digest)
                   (conj [:rf.ssr/check-schema-digest schema-digest])

                   ;; LATE-BOUND cross-subsystem hydration RE-ARM (rf2-jqvgp).
                   ;; The `:resources/hydrate-runtime-db` hook below is PURE —
                   ;; it reshapes the runtime-db value being installed. Machines
                   ;; needs the other half: HOST work. A machine hydrated in an
                   ;; `:after`-bearing state carries the right `:state` / `:data`
                   ;; / `:rf/after-epoch` and no live timer, because the server
                   ;; suppressed timer scheduling (Spec 005 §SSR mode) and a
                   ;; wall-clock handle is not serialisable. Spec 011 §`:after`
                   ;; is no-op under SSR requires those timers to begin running
                   ;; on the client against the preserved epoch, so the machines
                   ;; artefact re-arms them — WITHOUT re-running entry actions,
                   ;; raises or spawns the server already performed.
                   ;;
                   ;; An EFFECT rather than a hook call inside the handler,
                   ;; because arming host clocks is not something a pure handler
                   ;; may do and because the walk must read the runtime-db AFTER
                   ;; it commits: `commit-frame-effects!` installs both
                   ;; partitions before `run-fx-effects!` walks this vector.
                   ;;
                   ;; Gated three ways, each of which independently means "no
                   ;; timers": `client?` (a server-side / isomorphic-loopback
                   ;; hydrate arms nothing, same gate the two `:rf.ssr/check-*`
                   ;; fxs use), `payload-runtime-db` (only a server-settled
                   ;; runtime-db slice needs reconstructing — a client-only
                   ;; payload's machines are already running their own timers),
                   ;; and the hook's presence (an SSR app without the machines
                   ;; artefact emits nothing, so `:rf.machine/hydrate-rearm` is
                   ;; never requested from a runtime that has no handler for it).
                   ;; The rejected paths — malformed payload, wrong frame-id —
                   ;; return before this handler and so arm nothing either.
                   (and client?
                        (some? payload-runtime-db)
                        (some? (late-bind/get-fn :machines/rearm-after-hydration!)))
                   (conj [:rf.machine/hydrate-rearm {}]))}
      ;; Install the runtime-db partition when EITHER a server-settled
      ;; runtime-db slice rode the payload OR hydration metadata was produced.
      ;; The metadata merges on top of the payload slice (server-hash/version
      ;; sit alongside the hydrated machine snapshots / route slice).
      (or payload-runtime-db (seq metadata))
      (assoc :rf.db/runtime
             (let [hydration-runtime-db (if (seq metadata)
                                          (assoc-in hydration-runtime-db-base
                                                    [:rf.runtime/ssr :hydration]
                                                    metadata)
                                          hydration-runtime-db-base)]
               ;; LATE-BOUND cross-subsystem hydration RECONCILE. A cross-feature
               ;; artefact (Resources, Spec 016 §SSR and hydration) reconciles its
               ;; OWN durable runtime-db subtree once installed — recompute reverse
               ;; indexes from entries (never trust the wire), orphan SSR owners,
               ;; clear transient host pointers, surface server clock skew — without
               ;; SSR statically `:require`ing it. Absent hook (no resources
               ;; artefact) leaves `hydration-runtime-db` unchanged, so an SSR
               ;; app without resources sees no behaviour change. Resources is
               ;; the first consumer; it reconciles `:rf.runtime/resources`. The
               ;; symmetric COUNTERPART of `:ssr/extend-runtime-db-projection`
               ;; (the server projection hook in `project-runtime-db`).
               (if-let [reconcile-runtime-db
                        (late-bind/get-fn :resources/hydrate-runtime-db)]
                 (reconcile-runtime-db hydration-runtime-db frame-id)
                 hydration-runtime-db))))))

;; ---- :rf.ssr/check-version + :rf.ssr/check-schema-digest fxs --------------
;;
;; Per Spec 011 §The :rf/hydrate event, the handler
;; dispatches these two fxs after replacing the client app-db with the
;; server's authoritative slice. They are best-effort compatibility checks:
;; a mismatch emits a structured warning trace and the hydration proceeds.
;; The runtime never throws on a mismatch — degraded-but-running beats
;; a crashed boot.
;;
;; Argument shape:
;;
;;   - SCALAR — `[:rf.ssr/check-version <server-value>]` per the spec's
;;     reference :rf/hydrate handler. The fx treats the scalar as the
;;     "expected" (server-side) value and resolves the client-side
;;     "actual": for version, the SSR artefact's compiled-in
;;     `payload-policy/pattern-protocol-version` constant (always resolves
;;     — the SAME value the server stamped, so a matching build compares
;;     equal); for schema-digest, the `:schemas/app-schemas-digest`
;;     late-bind hook. When the schema-digest hook is unavailable (schemas
;;     artefact not on the classpath), that fx emits a
;;     `:rf.ssr/compatibility-check-skipped` trace and no-ops the
;;     comparison.
;;
;;   - MAP — `[:rf.ssr/check-version {:expected ... :actual ...}]` for
;;     callers that compute both sides explicitly (test harnesses, hosts
;;     that pin their own version constant). The fx compares the two
;;     values directly.
;;
;; Gating: `:platforms #{:client}` — these checks only make sense on the
;; hydration side. Server-side dispatches no-op via the standard fx-
;; gating contract (`:rf.fx/skipped-on-platform`).

(defn- check-args
  "Normalise the fx argument to `{:expected <server-value> :actual <client-value>}`.
  Returns nil when the argument doesn't carry an `:expected` value the
  fx can compare against. The `actual-lookup-fn` is a 0-arity fn called
  to resolve the client-side value when the caller passed a scalar; it
  may return nil to signal `:lookup-unavailable`."
  [arg actual-lookup-fn]
  (cond
    (and (map? arg) (contains? arg :expected))
    (cond-> {:expected (:expected arg)}
      (contains? arg :actual) (assoc :actual (:actual arg))
      ;; map without :actual falls back to the lookup
      (not (contains? arg :actual)) (assoc :actual (actual-lookup-fn)))

    (nil? arg) nil

    :else
    {:expected arg :actual (actual-lookup-fn)}))

(defn- runtime-version-lookup
  "The client-side hydration pattern-protocol version: the SSR artefact's
  compiled-in `payload-policy/pattern-protocol-version` constant — the SAME
  value the server stamps into `:rf/version`. Both wire ends read this one
  source of truth, so a scalar `:rf.ssr/check-version` on a matching build
  compares equal (no host wiring, and it never resolves to nil)."
  []
  payload-policy/pattern-protocol-version)

(defn- schema-digest-lookup
  "Look up the active frame's `app-schemas-digest`. Sourced via the
  schemas artefact's `:schemas/app-schemas-digest` late-bind hook so
  re-frame.ssr does not statically `:require` the schemas artefact —
  in builds where schemas is absent the lookup returns nil and the
  check emits `:rf.ssr/compatibility-check-skipped`."
  []
  (when-let [f (late-bind/get-fn :schemas/app-schemas-digest)]
    (f)))

(defn check-version-fx
  "Handler fn for the `:rf.ssr/check-version` fx. Compares the
  payload's `:rf/version` (server) against the client runtime's
  pattern-protocol version — the SSR artefact's compiled-in
  `payload-policy/pattern-protocol-version` constant (via
  `runtime-version-lookup`). Both wire ends read one source of truth, so a
  matching build compares equal; genuine skew emits `:rf.ssr/version-mismatch`."
  [{:keys [frame]} arg]
  (let [{:keys [expected actual]} (check-args arg runtime-version-lookup)]
    (cond
      (nil? expected) nil                              ;; nothing to check

      (not= expected actual)
      (trace/emit! :warning :rf.ssr/version-mismatch
                   {:expected expected
                    :actual   actual
                    :frame    frame
                    :reason   (str "Hydration version-mismatch: server '"
                                   expected "' != client '" actual
                                   "'. Hydrating anyway (best-effort).")
                    :recovery :warned-and-applied})

      :else nil)))                                     ;; match → silent

(defn check-schema-digest-fx
  "Handler fn for the `:rf.ssr/check-schema-digest` fx. Compares the
  payload's `:rf/schema-digest` (server) against the client's
  registered app-schema digest via the `:schemas/app-schemas-digest`
  late-bind hook."
  [{:keys [frame]} arg]
  (let [{:keys [expected actual]} (check-args arg schema-digest-lookup)]
    (cond
      (nil? expected) nil                              ;; nothing to check

      (nil? actual)
      (trace/emit! :warning :rf.ssr/compatibility-check-skipped
                   {:check    :rf.ssr/check-schema-digest
                    :expected expected
                    :reason   "No schema digest available for comparison (schemas artefact not on classpath, or :schemas/app-schemas-digest hook absent)."
                    :frame    frame
                    :recovery :skipped})

      (not= expected actual)
      (trace/emit! :warning :rf.ssr/schema-digest-mismatch
                   {:expected expected
                    :actual   actual
                    :frame    frame
                    :reason   (str "Hydration schema-digest mismatch: server '"
                                   expected "' != client '" actual
                                   "'. Deploy drift — server and client are running different schema sets. Hydrating anyway (best-effort).")
                    :recovery :warned-and-applied})

      :else nil)))                                     ;; match → silent

(defn- detect-mismatch?
  "Per Spec 011 §Mismatch recovery and configuration item 4. Read the
  frame's `:ssr {:detect-mismatch? …}` knob — default `true` (detection
  on in all builds). When a production build sets it `false`, the
  hash-comparison work is skipped entirely for a small first-render perf
  win, at the cost of silent mismatches. Absence of the key (the common
  case) leaves detection ON."
  [frame-id]
  (let [configured-value (get-in (frame/frame-meta frame-id)
                                 [:ssr :detect-mismatch?])]
    (if (some? configured-value) (boolean configured-value) true)))

(defn- mismatch-policy
  "Per Spec 011 §Mismatch recovery and configuration item 2. Read the
  frame's `:ssr {:on-mismatch …}` knob — default `:warn` (the
  `:warned-and-replaced` recovery). `:hard-error` escalates a mismatch to
  a thrown structured exception (dev/CI fail-fast). Any other value falls
  back to `:warn`."
  [frame-id]
  (let [configured-value (get-in (frame/frame-meta frame-id) [:ssr :on-mismatch])]
    (if (= :hard-error configured-value) :hard-error :warn)))

(defn verify-hydration!
  "Per Spec 011 §Hydration-mismatch detection + §Mismatch recovery and
  configuration. Called by client code after the first render. Compares
  the post-render hash to the server hash stashed during :rf/hydrate; on
  disagreement emits :rf.ssr/hydration-mismatch with :recovery
  :warned-and-replaced.

  The second arg may be EITHER a render tree (we hash it) OR a
  pre-computed hash string (used by test harnesses that simulate the
  client render).

    (verify-hydration! frame-id render-tree)
    (verify-hydration! frame-id render-tree opts)

  opts may carry :first-diff-path, :failing-id, AND :server-hash.
  The :server-hash opt overrides the
  [:rf.runtime/ssr :hydration :server-hash] slot in runtime-db — useful
  when the user's :rf/hydrate handler doesn't populate that slot
  (e.g. fixture-overridden handlers).

  Two per-frame `:ssr` config knobs govern detection + recovery
  (Spec 011 §Mismatch recovery and configuration):

    - `:ssr {:detect-mismatch? false}` — skip the hash comparison
      entirely (production perf win; silent mismatches). Default: detect.
    - `:ssr {:on-mismatch :hard-error}` — escalate a detected mismatch to
      a thrown `:rf.ssr/hydration-mismatch` structured exception (dev/CI
      fail-fast) carrying the same `:server-hash` / `:client-hash` /
      `:failing-id` payload as the trace. Default: `:warn`
      (`:warned-and-replaced`)."
  ([frame-id tree-or-hash] (verify-hydration! frame-id tree-or-hash {}))
  ([frame-id tree-or-hash {:keys [first-diff-path failing-id server-hash]}]
   (when (detect-mismatch? frame-id)
     ;; SSR hydration metadata is durable runtime-db
     ;; state at `[:rf.runtime/ssr :hydration]` — read it off the runtime-db
     ;; partition.
     (let [runtime-db (frame/frame-runtime-db-value frame-id)
           server-hash (or server-hash
                           (get-in runtime-db
                                   [:rf.runtime/ssr :hydration :server-hash]))
           client-hash (cond
                         (string? tree-or-hash) tree-or-hash
                         tree-or-hash           (hash/render-tree-hash tree-or-hash))]
       (when (and server-hash client-hash (not= server-hash client-hash))
         (let [hard-error? (= :hard-error (mismatch-policy frame-id))
               recovery (if hard-error? :hard-error :warned-and-replaced)
               ;; ONE shared payload reused by both the dev-trace emit AND the
               ;; strict-mode throw (the build-shared-payload-then-throw-and-emit
               ;; idiom error/ex-info-from-data was purpose-built for). It carries
               ;; the canonical thrown-error slots — :rf.error/id, :reason,
               ;; :recovery, and :where.
               mismatch-data (cond-> {:rf.error/id :rf.ssr/hydration-mismatch
                                      :where       'rf/verify-hydration!
                                      :server-hash server-hash
                                      :client-hash client-hash
                                      :frame       frame-id
                                      :failing-id  (or failing-id :rf/hydrate)
                                      :reason      (str "Hydration mismatch: server hash '"
                                                        server-hash
                                                        "' != client hash '"
                                                        client-hash
                                                        "'. "
                                                        (if hard-error?
                                                          "Strict mode — throwing."
                                                          "Re-rendering client-side."))
                                      :recovery    recovery}
                               first-diff-path
                               (assoc :first-diff-path first-diff-path))
               emit-error! (late-bind/get-fn :trace/emit-error!)]
           ;; Always emit the trace (monitoring integrations rely on it),
           ;; THEN escalate in strict mode. The thrown ex-info carries the
           ;; same structured payload so a CI run sees the full diff.
           (when emit-error!
             (emit-error! :rf.ssr/hydration-mismatch mismatch-data))
           (when hard-error?
             ;; Route the shared-payload throw through error/ex-info-from-data;
             ;; it derives the human
             ;; message from the payload's own :rf.error/id + :reason (LEADING
             ;; the sentence, TRAILING the [:rf.ssr/hydration-mismatch] token)
             ;; in ONE call, instead of re-deriving error/human-message inline.
             ;; The payload IS the ex-data verbatim (one map, throw + trace agree).
             (throw (error/ex-info-from-data mismatch-data)))))))))
