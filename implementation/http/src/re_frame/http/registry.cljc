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
  itself and every question asked of it — lookup and abort by `:request-id`,
  supersession, actor destroy and the frame-lifecycle sweeps, each a filter
  over the one registry, so a request can never be visible to one question
  and invisible to another — and the per-frame issuance counter behind work
  ids.

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
;; Every cancellation path names the issuing frame: the abort fx, resources'
;; out-of-cascade teardown, supersession, both actor-destroy callers and the
;; frame sweeps.

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
  "Return the in-flight record value `frame-id` issued under `request-id`, or
  nil (when absent, or when `request-id` is nil). Read-only. A sibling frame's
  identical id is invisible."
  [frame-id request-id]
  (when request-id
    (some #(when (= request-id (:request-id %)) %) (frame-records @requests frame-id))))

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

(defn abort-in-flight-in-frame!
  "Best-effort abort the in-flight managed request `frame-id` issued under
  `request-id`, firing its `:abort-fn` with `reason` (default `:user`). The
  one abort-by-request-id seam: the `:rf.http/managed-abort` fx and resources'
  out-of-cascade teardown both route through it, and a sibling frame that used
  the same id is invisible here. No-op when nothing is in flight under the id.
  Never throws; returns true iff a request was found and its abort-fn ran."
  ([frame-id request-id] (abort-in-flight-in-frame! frame-id request-id :user))
  ([frame-id request-id reason]
   (abort-record! (lookup-in-flight frame-id request-id) reason)))

;; ---- the per-frame issuance counter ----------------------------------------
;;
;; One HTTP attempt has one work id (Managed-Effects §Work-id correlation):
;; `[:rf.work/http issuance attempt]`. `issuance` comes from ONE monotone
;; counter per issuing frame — every `:rf.http/managed` issuance in the frame,
;; named or anonymous, takes the next number — and `attempt` numbers the
;; transport retries within it. So no two requests a frame issues share a work
;; id: not two loads of one `:request-id` in sequence, not a superseded attempt
;; and its superseder, not two anonymous requests of one event. The caller's
;; `:request-id` rides the reply's `:correlation`, not the work id.
;;
;; The counter is host state, outside frame-state, so an epoch restore cannot
;; rewind it: a request issued after a restore never takes a number the
;; unwound timeline already used. Completion leaves it alone too. It goes only
;; when its frame is destroyed — after the frame's in-flight requests have been
;; aborted, so every id it minted is terminal — which keeps the map bounded by
;; the live frames; a frame later created under the same id starts again at 1.

(defonce ^:private issuance-counters
  ;; frame-id → the highest issuance number that frame has allocated.
  (atom {}))

(defn next-issuance!
  "Allocate and return `frame-id`'s next issuance number: 1 for the frame's
  first managed request, then 2, 3, … for every later one, named or
  anonymous. A sibling frame runs its own sequence."
  [frame-id]
  (get (swap! issuance-counters update frame-id (fnil inc 0)) frame-id))

(defn reset-issuance-counters-for-test!
  "Test-time helper: drop every frame's issuance counter, so a fresh test run
  starts every frame at issuance 1. Not part of the user-facing API."
  []
  (reset! issuance-counters {})
  nil)

(defn issuance-counter-count
  "Test/introspection helper: the number of frames holding an issuance
  counter. Lets a leak test assert that a destroyed frame's counter goes. Not
  part of the user-facing API."
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

;; ---- per-frame stub transports -------------------------------------------
;;
;; A demo runs on a fake backend by installing a stub transport on its frame
;; (`re-frame.http.stub-transport/install-transport!`). The install is the
;; frame's own HTTP state, held here beside its issuance counter and dropped
;; with it when the frame is destroyed, so a frame later created under the same
;; id starts on the host adapter.

(defonce ^:private frame-transports
  ;; frame-id → the stub transport the frame's requests go out through.
  (atom {}))

(defn install-frame-transport!
  "Send every managed request `frame-id` issues through `transport`, or, with
  `transport` nil, through the host adapter again. Returns nil."
  [frame-id transport]
  (swap! frame-transports #(if transport (assoc % frame-id transport) (dissoc % frame-id)))
  nil)

(defn frame-transport
  "The stub transport installed on `frame-id`, or nil."
  [frame-id]
  (get @frame-transports frame-id))

(defn clear-all-in-flight!
  "Test-time helper: cancel every in-flight managed request, then drop every
  frame's issuance counter and installed stub transport. Test fixtures use
  this between runs.

  Symmetric with the machines timer reset: a request sleeping in its retry
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
  (reset! frame-transports {})
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
       (abort-record! record :actor-destroyed)))
   nil))

;; ---- the frame-lifecycle sweeps --------------------------------------------
;;
;; Epoch restore installs the captured durable frame-state WHOLESALE, but a
;; plain `:rf.http/managed` request in flight is host work — an AbortController
;; / CompletableFuture and a registry record, NOT frame-state — so the install
;; leaves it attached to the pre-restore timeline. Unlike resource-backed HTTP
;; (whose live attempt the resources reconcile dangles), a plain managed
;; request has no attempt gate: its late completion would still deliver to its
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

  The http lifecycle contribution's `:post-dead` member, so it runs after the
  machines `:pre-dead` cascade gave actor-owned work its more specific
  `:actor-destroyed` semantics; it aborts every request the frame still has in
  flight, resource-owned ones included, whichever of http and resources tears
  down first (an abort is idempotent). Then drops the destroyed frame's issuance counter and installed
  stub transport: every id it minted is terminal by then, both maps stay
  bounded by the live frames, and a frame later made under the same id starts
  on the host adapter. Idempotent; returns nil."
  [frame-id]
  (abort-frame-records! frame-id :frame-destroyed :suppressed-on-frame-destroy)
  (swap! issuance-counters dissoc frame-id)
  (swap! frame-transports dissoc frame-id)
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
