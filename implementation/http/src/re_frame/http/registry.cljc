(ns re-frame.http.registry
  "The in-flight request registry for `:rf.http/managed`.

  ONE registry holds every in-flight managed request — named, actor-issued and
  anonymous alike — as ONE record per request, filed under its issuing frame:

      {frame-id {token record}}

  A record is a cell (an atom) whose value is the request's whole lifecycle
  state, published once at issuance with every key it will ever carry:

      {:token :frame :request-id :actor-id :origin-event :issuance :attempt
       :url :sensitive? :phase :cancel :abort :abort-fn}

  `re-frame.http.transport` owns the record's lifecycle (its four transitions)
  and retires it from here when it finishes. This namespace owns the registry
  itself and every question asked of it: lookup and abort by `:request-id`,
  supersession, actor destroy, the frame-lifecycle sweeps, and the issuance
  counters behind work ids. Each is a filter over the one registry, so a
  request can never be visible to one question and invisible to another.

  The `:token` is internal and unique per request, so two requests never share
  a record even when their frame ids, request ids or work tuples recur — a
  stale callback holding an old record can neither retire nor touch a
  successor.

  `:abort-fn` is the request's one abort entry point. Every cancellation path
  here resolves records and fires it; a record the registry aborts leaves the
  registry whatever its abort-fn does, so a fabricated test record needs no
  cleanup of its own."
  (:require [re-frame.http.privacy :as rf.http.privacy]
            [re-frame.http.reply   :as rf.http.reply]
            [re-frame.interop      :as rf.interop]
            [re-frame.late-bind    :as rf.late-bind]
            [re-frame.trace        :as rf.trace]))

;; ---- frame scope -----------------------------------------------------------
;;
;; Per Spec 014 §`:request-id` (internal) and §Abort on actor destroy, managed-
;; HTTP cancellation is FRAME-SCOPED: a supersession, a `:rf.http/managed-abort`,
;; an actor destroy or a cleanup reaches only work issued by the SAME frame.
;; Frames are isolated contexts (Spec 002), and reusable app code naturally
;; reuses one stable id — `:request-id :articles/load` — so with raw-id scope
;; two frames running the same code would cross-cancel. Filing every record
;; under its issuing frame gives that isolation from information the runtime
;; already holds, without touching the public args map or the
;; `:rf.http/managed-abort` effect shape; the caller's raw `:request-id` stays
;; the correlation value echoed in replies and traces.
;;
;; The frame-less arities are documented ANY-FRAME seams that match the raw id
;; in every frame. One is reached in production: resources' out-of-cascade
;; teardown through the `:http/abort-in-flight!` late-bind hook, whose token is
;; already frame-qualified (`[:rf.req frame-id work-id]`, Spec 016), so at most
;; one frame can match. Every path that holds an issuing frame — the abort fx,
;; supersession, both actor-destroy callers, the frame sweeps — uses it.

(defonce requests
  ;; frame-id → (sorted-map token record-cell). Process-global, frame-scoped.
  (atom {}))

(defonce ^:private token-seq
  ;; Monotonic and never reset, so a token is never reused for the life of the
  ;; process — even across `clear-all-in-flight!`.
  (atom 0))

(defn next-token!
  "Mint a fresh request token."
  []
  (swap! token-seq inc))

(defn publish!
  "File `record` (a cell whose value carries `:token` and `:frame`) under its
  issuing frame, in one swap. Returns `record`."
  [record]
  (let [{:keys [frame token]} @record]
    (swap! requests update frame (fnil assoc (sorted-map)) token record))
  record)

(defn- frame-records
  "The values of `frame-id`'s records in `registry`, in issue order."
  [registry frame-id]
  (map deref (vals (get registry frame-id))))

(defn- all-records
  "The values of every record in `registry`."
  [registry]
  (mapcat (fn [[_ by-token]] (map deref (vals by-token))) registry))

(defn- take-records!
  "Remove `frame-id`'s records whose value satisfies `pred` from the registry
  in ONE swap, and return those values. Reading and removing in one step means
  a record published alongside the take is either taken (and aborted by the
  caller) or left in place — never dropped unaborted. `pred` must read only
  the record's identity keys, which never change."
  [frame-id pred]
  (let [[before _] (swap-vals! requests
                               (fn [registry]
                                 (let [kept (into (sorted-map)
                                                  (remove (fn [[_ record]] (pred @record)))
                                                  (get registry frame-id))]
                                   (if (seq kept)
                                     (assoc registry frame-id kept)
                                     (dissoc registry frame-id)))))]
    (filter pred (frame-records before frame-id))))

(defn record-in-flight!
  "Publish a fabricated in-flight request — the test and testbed seam for a
  request that should look in flight without one being issued. `handle` is
  the record value: it MUST carry an `:abort-fn` accepting an abort reason,
  and typically `:frame` and `:url`. `request-id` and `actor-id` are optional
  (pass nil) and are stamped onto it, as is a fresh `:token`.

  The issuing frame is read off the handle's own `:frame`; a handle with none
  files under `nil`, a scope of its own that no frame-scoped abort reaches.
  Returns the stamped value, which the snapshot helpers then return
  `identical?`."
  [request-id actor-id handle]
  (let [record (cond-> (assoc handle :token (next-token!))
                 request-id (assoc :request-id request-id)
                 actor-id   (assoc :actor-id actor-id))]
    (publish! (atom record))
    record))

(defn clear-in-flight!
  "Remove the record `handle` names from the registry — by its token, so a
  completing request never evicts a successor that reuses its frame, request
  id or actor. A nil `handle` is a no-op, as is one already gone.
  `request-id` is the handle's own id, kept so call sites read as before.
  Returns nil."
  [_request-id handle]
  (when handle
    (let [{:keys [frame token]} handle]
      (swap! requests
             (fn [registry]
               (let [remaining (dissoc (get registry frame) token)]
                 (if (seq remaining)
                   (assoc registry frame remaining)
                   (dissoc registry frame)))))))
  nil)

(defn lookup-in-flight
  "Return the in-flight record value under `request-id`, or nil (when absent,
  or when `request-id` is nil). Read-only.

   - 2-arg `[frame-id request-id]` — the FRAME-SCOPED form, which every
     managed-HTTP lifetime path uses. A sibling frame's identical id is
     invisible.
   - 1-arg `[request-id]` — the ANY-FRAME form, for a caller holding a token
     that is already frame-qualified (resources' `[:rf.req frame-id
     work-id]`). Do not reach for it from a call site that HAS a frame."
  ([request-id]
   (when request-id
     (some #(when (= request-id (:request-id %)) %) (all-records @requests))))
  ([frame-id request-id]
   (when request-id
     (some #(when (= request-id (:request-id %)) %) (frame-records @requests frame-id)))))

(defn- abort-record!
  "Fire `record`'s `:abort-fn` with `reason`, defensively, then take the
  record out of the registry. Returns true iff a record was given and its
  abort-fn ran without throwing.

  The removal comes AFTER the abort-fn, so a JVM abort waiting for an
  in-progress host send leaves the request registered until it can act."
  [record reason]
  (boolean
    (when record
      (let [ran? (try ((:abort-fn record) reason) true
                      (catch #?(:clj Throwable :cljs :default) _ false))]
        (clear-in-flight! (:request-id record) record)
        ran?))))

(defn abort-in-flight!
  "Best-effort abort the in-flight managed request under `request-id` in ANY
  frame, firing its `:abort-fn` with `reason` (default `:user`). No-op when
  nothing is in flight under the id. Never throws; returns true iff a request
  was found and its abort-fn ran.

  The ANY-FRAME abort seam: resources' out-of-cascade teardown reaches it
  through the published `:http/abort-in-flight!` late-bind hook, with a token
  that already carries the frame. The `:rf.http/managed-abort` fx uses
  `abort-in-flight-in-frame!` instead, because an app's raw `:request-id` is
  frame-LOCAL."
  ([request-id] (abort-in-flight! request-id :user))
  ([request-id reason]
   (abort-record! (lookup-in-flight request-id) reason)))

(defn abort-in-flight-in-frame!
  "Best-effort abort the in-flight managed request `frame-id` issued under
  `request-id`, firing its `:abort-fn` with `reason` (default `:user`). The
  FRAME-SCOPED counterpart of `abort-in-flight!` and the seam the
  `:rf.http/managed-abort` fx routes through: a sibling frame that used the
  same raw id is invisible here. Never throws; returns true iff a request was
  found and its abort-fn ran."
  ([frame-id request-id] (abort-in-flight-in-frame! frame-id request-id :user))
  ([frame-id request-id reason]
   (abort-record! (lookup-in-flight frame-id request-id) reason)))

;; ---- per-request-id issuance generation ------------------------------------
;;
;; EP-0011 §Work-id correlation: "one attempt has one work id". HTTP has no
;; generation counter of its own — supersession is keyed on `:request-id`
;; equality — so two requests issued under the SAME `:request-id` both reset
;; their retry `:attempt` to 1 and, without this counter, would compute the SAME
;; work-id `[:rf.work/http logical-id 1]`. Tooling and conformance could then
;; not distinguish the superseded attempt from the superseding one by
;; `:work/id`, breaking EP-0011's single-attempt identity for HTTP
;; supersession (Managed-Effects §Work-id correlation — §184: "one attempt has
;; one work id").
;;
;; This monotonic per-request-id ISSUANCE counter discriminates them: each
;; fresh issuance under a given request-id bumps the counter, so the
;; superseded request (issuance N) and the superseding one (issuance N+1) get
;; distinct work-id tuples. The retry `:attempt` still discriminates retries
;; WITHIN one issuance; the issuance discriminates re-issuances ACROSS
;; supersessions.
;;
;; An ANONYMOUS request (no `:request-id`) never supersedes, but
;; its logical id is the originating event-id, so two anonymous requests of one
;; event in one frame would otherwise share a work id and their completion rows
;; could not be told apart. They take their issuance from a SEPARATE
;; per-(frame, event-id) counter, `anonymous-issuance-counters`, below.

(defonce ^:private issuance-counters
  ;; [frame-id request-id] → highest issuance number allocated so far.
  ;;
  ;; Keyed by the issuing frame as well as the id, because the counter exists
  ;; to discriminate a supersession, and supersession is frame-scoped. Keyed by
  ;; the raw id alone, a request in frame B would take its FIRST issuance number
  ;; from frame A's counter, perturbing B's `:work/id` for a supersession that
  ;; never happened to it.
  ;;
  ;; Monotonic per (frame, request-id) WHILE THE ID IS LIVE: `next-issuance!`
  ;; only ever advances it,
  ;; so a superseded attempt (issuance N) and its superseder (N+1) carry
  ;; distinct `:work/id`s, and a late completion of an old attempt cannot reuse
  ;; a live issuance. The entry is EVICTED (not decremented) when
  ;; the id's final attempt terminates with the counter still AT that attempt's
  ;; issuance (`evict-issuance-on-completion!`), so an app minting an UNBOUNDED
  ;; distinct-id space (e.g. `[:load-item item-id]` over an unbounded item
  ;; space) does not accumulate one permanent entry per id ever requested on
  ;; a long-running JVM. A re-issuance after a full eviction restarts at 1 —
  ;; safe because the prior attempt is already terminal (its trace/reply
  ;; emitted), so there is no live work-id collision to discriminate.
  (atom {}))

(defonce ^:private anonymous-issuance-counters
  ;; [frame-id origin-event-id] → highest issuance number allocated so far to
  ;; an ANONYMOUS request (nil `:request-id`) of that event in that frame.
  ;;
  ;; Held apart from `issuance-counters` so `issuance-counter-count` (the
  ;; leak instrument for the UNBOUNDED request-id space) keeps its
  ;; meaning, and NEVER evicted on completion: the compare-and-drop eviction
  ;; protects only a supersession's newer live successor, so with two live
  ;; anonymous siblings (1 and 2) the second completing first would drop the
  ;; counter and hand the next request 1 while the first was still live — a
  ;; live work-id collision. For an ordinary event the key space is (frames ×
  ;; registered event-ids), which is bounded. A request issued from inside a
  ;; spawned actor is keyed by the actor's ADDRESS (`<prefix>#<n>`, n only
  ;; rises), which is not, so `abort-on-actor-destroy` drops the address's
  ;; entry when the actor goes; a destroyed frame's entries
  ;; are dropped by `abort-in-flight-on-frame-destroyed!`.
  (atom {}))

(defn next-issuance!
  "Allocate and return the next monotonic issuance number for `request-id`
  AS ISSUED BY `frame-id`. The FIRST
  issuance under a (frame, request-id) pair is 1; each subsequent re-issuance
  in THAT frame (the caller bumps this before `supersede!`) returns the next
  integer, so the superseded and superseding attempts carry distinct
  `:work/id`s. A sibling frame reusing the same raw id keeps its own sequence
  and starts at 1.

  For a nil `request-id` (an ANONYMOUS request, whose logical id is
  the originating event-id) the number comes from the per-(frame, event-id)
  anonymous counter instead, keyed by `(first origin-event)`: the first
  anonymous request of an event in a frame is 1, the next 2, and so on, never
  reset by completion — so two anonymous requests of one event never share a
  work id. The count restarts only when its owner goes: the frame, or the
  spawned actor whose address is the event-id."
  ([frame-id request-id] (next-issuance! frame-id request-id nil))
  ([frame-id request-id origin-event]
   (if (nil? request-id)
     (let [k [frame-id (first origin-event)]]
       (get (swap! anonymous-issuance-counters update k (fnil inc 0)) k))
     (let [k [frame-id request-id]]
       (get (swap! issuance-counters update k (fnil inc 0)) k)))))

(defn evict-issuance-on-completion!
  "Evict `request-id`'s issuance counter when the attempt that carried
  `issuance` reaches a TERMINAL completion, bounding the
  `issuance-counters` map for apps minting UNBOUNDED distinct request-ids.

  Eviction is CONDITIONAL and atomic: the counter is dropped ONLY when it
  still equals `issuance` — no fresh request has re-issued under the same id
  since this attempt was allocated. That single-`swap!` compare-and-drop is
  what preserves the anti-collision invariant the monotonic counter
  exists for: when a supersede is in flight, `next-issuance!` has ALREADY
  bumped the counter PAST the superseded attempt's `issuance` (it bumps before
  `supersede!`, handlers.cljc), so this evict sees `counter > issuance`, skips,
  and the counter survives for the live successor. A single-issuance request
  (the leak vector) satisfies `counter == issuance`, so it evicts cleanly on
  completion. A `nil` `request-id` is an anonymous request, whose counter
  lives in `anonymous-issuance-counters` and is deliberately never evicted on
  completion — no-op.

  Called when a request is retired — never between attempts, where the
  attempt continues under the same issuance.

  `frame-id` is the ISSUING frame, so an eviction can only ever drop the
  counter this attempt was allocated from and never a sibling frame's live
  one."
  [frame-id request-id issuance]
  (when (and (some? request-id) (some? issuance))
    (let [k [frame-id request-id]]
      (swap! issuance-counters
             (fn [counters]
               (if (= issuance (get counters k))
                 (dissoc counters k)
                 counters)))))
  nil)

(defn reset-issuance-counters-for-test!
  "Test-time helper: drop the per-request-id issuance counters so
  a fresh test run starts every request-id at issuance 1. Not part of the
  user-facing API."
  []
  (reset! issuance-counters {})
  (reset! anonymous-issuance-counters {})
  nil)

(defn anonymous-issuance-counter-count
  "Test/introspection helper: the number of resident
  per-(frame, event-id) anonymous issuance counters. Not part of the
  user-facing API."
  []
  (count @anonymous-issuance-counters))

(defn issuance-counter-count
  "Test/introspection helper: the number of resident per-request-id
  issuance counters. Lets a leak test assert the map does not grow unbounded
  across completed requests. Not part of the user-facing API."
  []
  (count @issuance-counters))

(defn supersede!
  "If a request `frame-id` issued is already in flight under `request-id`,
  abort it with `:reason :request-id-superseded`. Per Spec 014 §`:request-id`
  (internal). Supersession is FRAME-SCOPED: a sibling frame that reused the
  same raw id is left untouched.

  Returns the superseded record value (or nil when nothing was in flight) so
  the caller can emit the canonical `:status :stale` /
  `:rf.reply/work-status :suppressed` trace for the OLD attempt (Managed-Effects
  §Stale suppression). The value carries the old attempt's identity facts
  (`:request-id`, `:origin-event`, `:issuance`, `:attempt`, `:frame`) — for a
  request sleeping in its backoff, the attempt that failed."
  [frame-id request-id]
  (when-let [superseded (lookup-in-flight frame-id request-id)]
    (abort-record! superseded :request-id-superseded)
    superseded))

(defn clear-all-in-flight!
  "Test-time helper: cancel every in-flight managed request, then drop the
  issuance counters. Test fixtures use this between runs.

  Symmetric with `:machines/reset-timers!`: a request sleeping in its retry
  backoff holds an armed `js/setTimeout` / `ScheduledFuture` that would
  otherwise outlive the synchronous test and issue its next attempt into a
  runtime the next test owns. The whole registry is taken in one step and each
  record's abort-fn fires with `:test-reset`, which cancels its timer or host
  work. Each abort-fn is fired defensively — a throwing one must not strand the
  rest."
  []
  (let [[registry _] (reset-vals! requests {})]
    (doseq [record (all-records registry)]
      (abort-record! record :test-reset)))
  (reset! issuance-counters {})
  (reset! anonymous-issuance-counters {})
  nil)

(defn in-flight-snapshot
  "Test-time helper: the in-flight records keyed by the CALLER'S RAW
  `:request-id`. Inspecting state in tests; not part of the user-facing API.

   - 0-arg — every frame, flattened onto the raw id. LOSSY across frames by
     construction: two frames holding the same raw id collapse to one entry. A
     multi-frame assertion MUST use the 1-arg form.
   - 1-arg `[frame-id]` — only the requests that frame issued."
  ([]
   (into {} (keep #(when-let [request-id (:request-id %)] [request-id %]))
         (all-records @requests)))
  ([frame-id]
   (into {} (keep #(when-let [request-id (:request-id %)] [request-id %]))
         (frame-records @requests frame-id))))

(defn- by-actor [records]
  (reduce (fn [acc record]
            (if-let [actor-id (:actor-id record)]
              (update acc actor-id (fnil conj []) record)
              acc))
          {} records))

(defn actor-in-flight-snapshot
  "Test-time helper: the in-flight records an actor issued, keyed by the raw
  actor-id. Inspecting state in tests; not part of the user-facing API.

   - 0-arg — every frame. Same-named actors in sibling frames have their
     records CONCATENATED under the one actor-id; use the 1-arg form to tell
     them apart.
   - 1-arg `[frame-id]` — only that frame's actors."
  ([] (by-actor (all-records @requests)))
  ([frame-id] (by-actor (frame-records @requests frame-id))))

(defn seed-in-flight-for-test!
  "Publish a fabricated in-flight record for tests through `record-in-flight!`,
  rather than a raw `swap!` of the registry. `handle` MUST carry an
  `:abort-fn` accepting an abort reason, and typically `:url` and `:frame`.
  `request-id` / `actor-id` are optional. Returns the stamped record value.

  Not part of the user-facing API — production code routes through
  `:rf.http/managed`."
  ([handle] (seed-in-flight-for-test! (:request-id handle) (:actor-id handle) handle))
  ([request-id actor-id handle]
   (record-in-flight! request-id actor-id handle)))

;; ---- abort-on-actor-destroy -----------------------------------------------
;;
;; Per Spec 014 §Abort on actor destroy: when a spawned state-machine
;; actor is destroyed (parent state exit, parent's :after firing,
;; :spawn-all join resolution, frame destroy, imperative destroy),
;; the runtime invokes this fn with the destroyed actor's address, and every
;; request the actor issued is aborted with :reason :actor-destroyed.
;;
;; Discovered through the late-bind hook table at :http/abort-on-actor-destroy
;; — re-frame.machines does NOT statically :require this namespace; the
;; destroy path looks up this fn at call time. When the http artefact is
;; not on the classpath the hook resolves to nil and the destroy proceeds
;; without aborting any HTTP (apps that don't issue managed-HTTP pay
;; nothing).

(defn abort-on-actor-destroy
  "Per Spec 014 §Abort on actor destroy. Abort every in-flight
  `:rf.http/managed` request issued from inside spawned actor `actor-id`.
  Each abort emits a `:rf.http/aborted-on-actor-destroy` trace event and
  dispatches a standard `:rf.http/aborted` reply with `:reason
  :actor-destroyed`.

  The actor's records are read AND removed in one step, so a re-entrant or
  repeated destroy finds nothing — idempotent — and a request published
  alongside the destroy is either taken and aborted or left untouched, never
  dropped unaborted.

  Two arities:

   - 2-arg `[frame-id actor-id]` — FRAME-SCOPED. Aborts only the HTTP the
     named frame's actor issued. A same-named actor in a sibling frame is
     untouched, exactly as §Sibling actors are not affected already promises
     for siblings within one frame. Both callers of the
     `:http/abort-on-actor-destroy` late-bind hook — the machines destroy
     cascade and core's machines-absent `destroy-frame!` fallback — thread the
     destroying frame and take this arity, because actor addresses are
     frame-LOCAL and collide across frames by construction.
   - 1-arg `[actor-id]` — ANY-FRAME: sweeps that actor-id in EVERY frame. The
     documented seam for a caller that genuinely holds an address and no
     frame; no in-repo destroy path is such a caller."
  ([actor-id]
   (when actor-id
     ;; `map`, not `keep`: a record filed under the nil frame is reached too.
     (doseq [frame-id (distinct (map :frame (filter #(= actor-id (:actor-id %))
                                                    (all-records @requests))))]
       (abort-on-actor-destroy frame-id actor-id)))
   nil)
  ([frame-id actor-id]
   (when actor-id
     (doseq [record (take-records! frame-id #(= actor-id (:actor-id %)))]
       (when rf.interop/debug-enabled?
         ;; The record carries the request's effective :sensitive? flag, so
         ;; off-box consumers honour the privacy contract on actor-destroy
         ;; aborts.
         (rf.trace/emit! :info :rf.http/aborted-on-actor-destroy
                         (rf.http.privacy/prepare-emit-tags
                           {:request-id (:request-id record)
                            :actor-id   actor-id
                            :url        (:url record)}
                           (true? (:sensitive? record)))))
       (abort-record! record :actor-destroyed))
     ;; An anonymous request issued from inside this actor took its issuance
     ;; from the counter keyed by the actor's ADDRESS (`<prefix>#<n>`, n only
     ;; rises), so keeping the entry would leave one per spawn for the frame's
     ;; lifetime. Every request the address issued is terminal once the aborts
     ;; above have run, so restarting its count cannot collide, even if the
     ;; address is later minted again.
     (swap! anonymous-issuance-counters dissoc [frame-id actor-id]))
   nil))

;; ---- the frame-lifecycle sweeps --------------------------------------------
;;
;; Epoch restore installs the captured durable frame-state WHOLESALE, but a
;; plain `:rf.http/managed` request in flight is host work — an AbortController
;; / CompletableFuture and a registry record, NOT frame-state — so the install
;; leaves it attached to the pre-restore timeline. Unlike resource-backed HTTP
;; (whose work-ledger row the resources reconcile dangles), a plain managed
;; request has no ledger gate: its late completion would still deliver to its
;; original `:rf/reply-to` target against the restored state. Frame destroy is
;; the same problem with the target frame already gone.
;;
;; Both boundaries therefore abort every record the frame issued — named,
;; actor-issued and anonymous — with a reply-suppressing reason, and emit the
;; EP-0011 `:status :stale` / `:rf.reply/work-status :suppressed` envelope facts
;; for each suppressed attempt (Managed-Effects §restore: "epoch restore MUST
;; NOT revive host work" — clauses 2/3/4 of §Stale suppression).

(defn- emit-frame-boundary-stale-trace!
  "Emit the canonical EP-0011 `:status :stale` / `:rf.reply/work-status :suppressed`
  reply-envelope trace for one managed HTTP attempt aborted at a FRAME-LIFECYCLE
  boundary — epoch restore OR frame destruction —
  WITHOUT dispatching any app target. `recovery` names the boundary
  (`:suppressed-on-epoch-restore` vs `:suppressed-on-frame-destroy`) so the two
  reply-suppressing boundaries stay discriminable on the trace stream while
  sharing the identical carried-id-against-nil-current suppression gate.
  Mirrors `http-transport/emit-superseded-stale-trace!` (the supersede sibling):
  the carried work-id is the aborted attempt's identity; there is no current
  work-id (the boundary replaces the attempt with nothing) so the gate
  suppresses the carried id against a nil current. Gated on `debug-enabled?`
  like the other `:rf.http/*` trace rows."
  [record recovery]
  (when rf.interop/debug-enabled?
    (let [stale-ctx {:request-id   (:request-id record)
                     :origin-event (:origin-event record)
                     :issuance     (:issuance record)
                     :attempt      (:attempt record)
                     :frame        (:frame record)}
          {:keys [reply trace]} (rf.http.reply/suppress stale-ctx nil)
          summary (rf.http.reply/trace-reply
                    reply
                    (cond-> {:sensitive? (true? (:sensitive? record))}
                      (:frame record) (assoc :frame (:frame record))))]
      (rf.trace/emit! :info :rf.http/stale-suppressed
                   (cond-> {:rf.reply/status       (:status summary)
                            :rf.reply/work-status  (:rf.reply/work-status summary)
                            :rf.reply/stale-reason (:rf.reply/stale-reason summary)
                            :rf.reply/work-id      (:rf.reply/work-id summary)
                            :rf.reply/work-kind             :http
                            :rf.reply/carried      (:rf.reply/carried trace)
                            :rf.reply/current      (:rf.reply/current trace)
                            :recovery              recovery}
                     (:frame record) (assoc :frame (:frame record)))))))

(defn- abort-frame-records!
  "Take every record `frame-id` issued in ONE step, then for each emit the
  EP-0011 stale-suppression envelope facts with `recovery` and fire its
  `:abort-fn` with `reason` — a reply-suppressing reason, so the late
  completion does NOT deliver to its original `:rf/reply-to` target.

  A successor frame created under the same id afterwards is spared: its
  records are published after the take, and a retiring old record removes only
  its own token. Idempotent and a no-op for a frame with nothing in flight.
  Returns nil."
  [frame-id reason recovery]
  (when frame-id
    (doseq [record (take-records! frame-id (constantly true))]
      (emit-frame-boundary-stale-trace! record recovery)
      (abort-record! record reason)))
  nil)

(defn abort-in-flight-for-frame!
  "Abort every in-flight managed HTTP request issued by `frame-id`, because epoch
  restore unwound that frame's timeline: emit the EP-0011 stale-suppression
  envelope facts, then abort with `:reason :epoch-restored` — a
  reply-suppressing reason (Managed-Effects §restore). Published as the
  `:http/abort-in-flight-for-frame!` late-bind hook the epoch boundary fires
  AFTER a successful install. Idempotent; returns nil."
  [frame-id]
  (abort-frame-records! frame-id :epoch-restored :suppressed-on-epoch-restore))

(defn abort-in-flight-on-frame-destroyed!
  "Abort every in-flight managed HTTP request issued by `frame-id`, because the
  owning frame is being DESTROYED. The frame-teardown counterpart of
  `abort-in-flight-for-frame!`: the same sweep, with `:reason :frame-destroyed`
  and `:recovery :suppressed-on-frame-destroy` on the stale-suppression trace.

  Frame destroy is reply-suppressing: the target frame is already marked
  destroyed, so dispatching a live cancellation reply into it is invalid. Each
  abort cancels the live fetch / future or the sleeping backoff timer, detaches
  any external `:abort-signal` listener, and delivers NOTHING to the original
  `:rf/reply-to` target.

  Published as the `:http/on-frame-destroyed!` late-bind hook and called from
  core `frame/destroy-frame!` AFTER machine + resource teardown, so actor-owned
  work gets its more specific `:actor-destroyed` semantics and resource-owned
  work its ledger/owner cleanup first; this sweep then catches the remaining
  requests. Also drops the destroyed frame's anonymous issuance counters — the
  only other place those entries go is `abort-on-actor-destroy`, which drops a
  destroyed actor's. Idempotent; returns nil."
  [frame-id]
  (abort-frame-records! frame-id :frame-destroyed :suppressed-on-frame-destroy)
  (swap! anonymous-issuance-counters
         #(into {} (remove (fn [[[f _] _]] (= f frame-id))) %))
  nil)

;; ---- spawned-actor detection ----------------------------------------------
;;
;; Per Spec 014 §Abort on actor destroy: a managed request "belongs to"
;; spawned actor `<spawned-id>` iff its originating event vector's first
;; element is `<spawned-id>` AND that id is registered as a spawned actor
;; in the frame's runtime-db spawn registry (per Spec 005 §Declarative
;; :spawn). That ownership decision is MACHINES-OWNED: machines knows the
;; registry shape, so it publishes the `:machines/owning-actor-id` late-
;; bind hook `(fn [frame-id event-id]) -> actor-id|nil`. http does not
;; re-state the registry path or walk the registry itself — it asks
;; machines through the hook and treats a nil/absent hook (no machines
;; artefact on the classpath) as "not owned by any actor". This keeps the
;; structural dependency on the `:spawned` runtime-db layout entirely
;; inside the machines artefact that owns it; machines in turn calls http
;; through `:http/abort-on-actor-destroy` for the destroy side.

(defn resolve-owning-actor-id
  "Resolve the spawned-actor-id for the request at hand, given the frame
  id and the originating event vector. Returns the actor-id (a keyword,
  the spawned actor's machine address) when the originating event-id is
  owned by a spawned actor, otherwise nil — meaning the request is NOT
  subject to actor-destroy cancellation (it was dispatched from an
  ordinary event handler, not from inside a spawned actor).

  Ownership is decided by the machines artefact via the
  `:machines/owning-actor-id` late-bind hook: http asks
  machines whether the originating event-id belongs to a spawned actor
  rather than re-stating the registry path and walking it itself. When
  the machines artefact is absent the hook is unregistered, this returns
  nil, and apps that don't use state machines pay nothing.

  The `:rf.http/managed` guard short-circuits before the hook call: the
  framework-stamped fx event-id is never an actor address, so there is no
  point asking machines about it."
  [frame-id origin-event]
  (let [event-id (when (vector? origin-event) (first origin-event))]
    (when (and event-id
               (not= event-id :rf.http/managed))
      (when-let [owning-actor-id (rf.late-bind/get-fn :machines/owning-actor-id)]
        (owning-actor-id frame-id event-id)))))
