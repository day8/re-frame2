(ns re-frame.http.registry
  "In-flight request registries for `:rf.http/managed`.

  Two indexes coexist:

   - `in-flight`        — `[frame-id request-id]` → request-handle. Per
                         Spec 014 §Aborts: a `:rf.http/managed-abort`
                         resolves the abort-fn through this index, and a
                         fresh request with the same `:request-id` in the
                         SAME frame supersedes the previous one.
   - `actor-in-flight`  — `[frame-id actor-id]` → [request-handle ...]. Per
                         Spec 014 §Abort on actor destroy, requests
                         whose originating event-id is a spawned state-
                         machine actor's address are ALSO indexed by
                         that actor-id so a `:rf.machine/destroy`
                         cascade can abort every in-flight request the
                         actor had issued.

  Both maps are PROCESS-GLOBAL storage with FRAME-SCOPED KEYS (rf2-o8ek).
  See §Frame-scoped cancellation identity below for why the frame is part of
  the key and not merely a stamp on the value.

  Handles carry `:abort-fn` (the fn the runtime calls with an abort reason),
  `:url`, plus the framework-stamped `:request-id` and
  `:actor-id` when applicable so subsequent `clear-in-flight!` calls
  can locate them in either index by identity.

  `abort-on-actor-destroy` lives here (rather than next to the machine-
  shape wrapper) because the operation is atomic state — it walks
  both atoms and mutates them under one `swap!` per slot. Keeping it
  next to the atoms makes the invariant local."
  (:require [re-frame.http.privacy :as rf.http.privacy]
            [re-frame.http.reply   :as rf.http.reply]
            [re-frame.interop      :as rf.interop]
            [re-frame.late-bind    :as rf.late-bind]
            [re-frame.trace        :as rf.trace]))

;; ---- frame-scoped cancellation identity (rf2-o8ek) -------------------------
;;
;; Per Spec 014 §`:request-id` (internal) and §Abort on actor destroy, managed-
;; HTTP cancellation is FRAME-SCOPED: a supersession, a `:rf.http/managed-abort`,
;; or an actor destroy reaches only work issued by the SAME frame.
;;
;; The two indexes below are process-global atoms, but their KEY is the pair
;; `[frame-id id]` — never the caller's raw id alone. The frame was already
;; stamped on every handle (`:frame`, from the fx context's envelope frame); it
;; is the KEY that had to change, because a stamp on the value cannot stop a
;; lookup keyed on the raw id from resolving a sibling frame's handle.
;;
;; Why this is a correctness law and not tidiness: frames are ISOLATED CONTEXTS
;; (Spec 002; `docs/core/frames.md`), and Spec 014 §Frame awareness promises
;; multi-frame apps "work without extra ceremony". Reusable app code naturally
;; reuses an ordinary stable id — `:request-id :articles/load` — so with raw-id
;; keying two frames running the SAME code cross-cancel: frame B's issuance
;; supersedes frame A's live request, and a `:rf.http/managed-abort` dispatched
;; in B aborts A's. Making the frame part of the internal key gives that
;; isolation from information the runtime already holds, with no change to the
;; public `:rf.http/managed` args map or the `:rf.http/managed-abort` effect
;; shape — the caller's raw `:request-id` remains the correlation value echoed
;; in replies and traces.
;;
;; ANY-FRAME seams. Three entry points are reached from artefacts that hold a
;; token but not a frame — resources' out-of-cascade teardown through the
;; `:http/abort-in-flight!` late-bind hook, and the machines / core actor-
;; destroy cascade through `:http/abort-on-actor-destroy`. Their frame-less
;; arities are preserved and documented as ANY-FRAME: they match on the raw id
;; across every frame, which is exactly the pre-rf2-o8ek behaviour. That is
;; correct for resources (its token is already frame-qualified —
;; `[:rf.req frame-id work-id]` per Spec 016 — so at most one frame can match)
;; and it is the conservative reading for actor destroy until its callers thread
;; a frame. The frame-BEARING arities alongside them are the isolated ones.

(defn- scoped-key
  "The internal cancellation key: the ISSUING FRAME paired with the caller's
  raw id (a `:request-id`, or a spawned actor's address). A 2-vector, so a
  caller whose raw id is itself a vector can never collide with a compound key
  — the frame always sits at position 0 and the WHOLE raw id at position 1."
  [frame-id id]
  [frame-id id])

;; ---- in-flight request registry -------------------------------------------

(defonce in-flight
  ;; [frame-id request-id] → request-handle map. The handle is implementation-
  ;; specific (CLJS: AbortController; JVM: CompletableFuture). The :abort-fn
  ;; value is the no-arg fn the runtime calls to cancel.
  (atom {}))

(defonce actor-in-flight
  ;; [frame-id actor-id] → vector of {:abort-fn :request-id :url :frame}.
  ;;
  ;; Index by (frame, actor-id) — populated when a managed request's originating
  ;; event-id is a spawned actor's address (per Spec 014 §Abort on actor
  ;; destroy). Each entry carries the same :abort-fn the
  ;; request-id index would carry; the actor-destroy hook walks the
  ;; vector, fires each :abort-fn, and clears the index slot. Multiple
  ;; in-flight requests from the same actor accumulate as separate
  ;; entries; sibling actors — and same-named actors in SIBLING FRAMES —
  ;; keep independent slots.
  (atom {}))

(defn record-in-flight!
  "Record a request handle. `handle` is the abort-handle map (carries
  `:abort-fn`, `:url`, plus the framework stamps `:request-id` and
  `:actor-id` when applicable so subsequent `clear-in-flight!` calls
  can locate it in either index by identity).

  Returns the (possibly-stamped) handle so the natural-completion
  sites can hold a reference for the 2-arg `clear-in-flight!` cleanup
  path. `request-id` and `actor-id` are both optional (pass nil). When
  both are nil the handle is unindexed and only reachable via natural
  completion.

  rf2-o8ek — the ISSUING FRAME is read off the handle's own `:frame` stamp
  (the transport stamps it from the fx context on both the live-fetch and the
  sleeping-backoff handle) and becomes part of the index key, so no signature
  change was needed here. A handle with no `:frame` keys under `nil`, which is
  a coherent scope of its own: unstamped handles share one namespace exactly as
  they did before frames entered the key."
  [request-id actor-id handle]
  (let [frame-id       (:frame handle)
        stamped-handle (cond-> handle
                         request-id (assoc :request-id request-id)
                         actor-id   (assoc :actor-id actor-id))]
    (when request-id
      (swap! in-flight assoc (scoped-key frame-id request-id) stamped-handle))
    (when actor-id
      (swap! actor-in-flight update (scoped-key frame-id actor-id)
             (fnil conj []) stamped-handle))
    stamped-handle))

(defn- remove-from-actor-index!
  "Drop `handle` from its own actor-index slot BY IDENTITY. Both halves of the
  slot key — the frame and the actor-id — are read off the handle itself, so
  the caller never has to carry them (rf2-o8ek)."
  [handle]
  (when-let [actor-id (:actor-id handle)]
    (let [k (scoped-key (:frame handle) actor-id)]
      (swap! actor-in-flight
             (fn [actor-index]
               (let [actor-handles     (get actor-index k [])
                     remaining-handles (vec (remove #(identical? % handle)
                                                    actor-handles))]
                 (if (seq remaining-handles)
                   (assoc actor-index k remaining-handles)
                   (dissoc actor-index k)))))))
  nil)

(defn clear-in-flight!
  "Clear a request handle from both indexes. Two arities:

   - 1-arg `[request-id]` — the resolve-by-id, ANY-FRAME form (rf2-o8ek).
     Resolves every frame's handle registered under this raw `request-id`
     and walks both indexes (the handle stores `:frame` and `:actor-id` so
     the actor-index slot can be located by identity). It carries no frame,
     so it cannot be frame-scoped; it is the seam for callers holding an
     already-frame-qualified token (resources' `[:rf.req frame-id work-id]`,
     per Spec 016), for which at most one frame can ever match. Prefer the
     2-arg form, which IS frame-exact. No-op when `request-id` is nil —
     anonymous requests use the 2-arg form below.
   - 2-arg `[request-id handle]` — the natural-completion form used by
     the per-host attempt loops. Both args are taken from the captured
     ctx + handle pair, so the cleanup is index-walks by identity and
     does not depend on `request-id` being non-nil. This arity covers
     anonymous-request natural completion from inside spawned actors.

  Per rf2-plngk this is THE single source of truth for in-flight
  cleanup on per-request termination. The natural-completion sites
  (`finalise-success!`, `finalise-failure!`, retry-clear in
  `maybe-retry!`) call this directly; the abort sites
  (`managed-abort-handler`, `abort-on-actor-destroy`) rely on the
  abort-fn → `finalise-failure!` cascade to reach here. Idempotent
  against already-gone state — the swap!s no-op on absent slots.

  rf2-ous9e5 — the 2-arg request-id dissoc is IDENTITY-CONDITIONAL,
  mirroring `remove-from-actor-index!`: it drops the request-id slot
  ONLY while that slot still holds THIS `handle`. A completing OLD
  attempt whose slot has already been taken over by a same-id SUCCESSOR
  (a fresh request superseded it, or a retry re-registered a backoff
  handle under the id) therefore no-ops on the request-id index and
  leaves the live successor's handle in place. The earlier unconditional
  `(dissoc request-id)` evicted the successor, making two effectively-
  live requests share one id — the out-of-order-reply pattern
  supersession exists to prevent. Single-request cleanup is unchanged:
  when the slot still holds `handle`, the identity check passes and the
  dissoc runs exactly as before."
  ([request-id]
   (when request-id
     ;; `swap-vals!` (core on both runtimes) yields the pre-swap snapshot from
     ;; the winning CAS attempt, so the handles we then walk out of the actor
     ;; index are exactly the ones this call removed.
     (let [[previous _] (swap-vals!
                          in-flight
                          (fn [request-index]
                            (reduce-kv (fn [index k _]
                                         (if (= request-id (second k))
                                           (dissoc index k)
                                           index))
                                       request-index request-index)))]
       (doseq [[k handle] previous
               :when (= request-id (second k))]
         (remove-from-actor-index! handle))))
   nil)
  ([request-id handle]
   (if (nil? handle)
     ;; rf2-o8ek — a nil `handle` (an abort that fired before the handle was
     ;; published to `@handle-cell` / `@handle-holder`) carries no `:frame`, so
     ;; there is no key to be exact about. Fall back to the ANY-FRAME clear
     ;; above: that pre-publication window precedes any successor, so there is
     ;; nothing to protect and the slot must still be cleared.
     (clear-in-flight! request-id)
     (do
       (when request-id
         (let [k (scoped-key (:frame handle) request-id)]
           (swap! in-flight
                  (fn [request-index]
                    ;; rf2-ous9e5 — drop the slot ONLY while it still holds THIS
                    ;; handle, so a same-id successor is never evicted.
                    (if (identical? (get request-index k) handle)
                      (dissoc request-index k)
                      request-index)))))
       (remove-from-actor-index! handle)))
   nil))

(defn lookup-in-flight
  "Return a request-handle, or nil (when absent, or when `request-id` is nil).
  The handle carries the `:abort-fn` the abort / supersede paths fire.
  Read-only — does not mutate either index.

   - 2-arg `[frame-id request-id]` — the FRAME-SCOPED form (rf2-o8ek), and the
     one the managed-HTTP lifetime paths use. Resolves the handle issued by
     THIS frame under this raw `request-id`; a sibling frame's identical id is
     invisible.
   - 1-arg `[request-id]` — the ANY-FRAME form: the first handle registered
     under this raw id in ANY frame. Retained for callers holding a token that
     is already frame-qualified (resources' `[:rf.req frame-id work-id]`), for
     which at most one frame can match. Do not reach for it from a call site
     that HAS a frame — it is precisely the reach-through this bead removed."
  ([request-id]
   (when request-id
     (some (fn [[k handle]]
             (when (= request-id (second k)) handle))
           @in-flight)))
  ([frame-id request-id]
   (when request-id
     (get @in-flight (scoped-key frame-id request-id)))))

(defn- fire-abort!
  "Fire `handle`'s `:abort-fn` with `reason`, defensively. Returns true iff a
  handle was present and its abort-fn ran without throwing.

  Per rf2-plngk the in-flight registry cleanup is owned by `finalise-failure!`
  (the abort-fn closure calls into it), so this never touches an index."
  [handle reason]
  (boolean
    (when handle
      (try ((:abort-fn handle) reason) true
           (catch #?(:clj Throwable :cljs :default) _ false)))))

(defn abort-in-flight!
  "Best-effort abort the in-flight managed request registered under
  `request-id` in ANY frame, firing its `:abort-fn` with `reason` (default
  `:user`). No-op when nothing is registered under `request-id` (the reply
  already landed, or the id is unknown / nil) — cancellation is opportunistic.
  Never throws (a throwing abort-fn is swallowed). Returns true iff a handle
  was found and its abort-fn fired, false otherwise.

  This is the ANY-FRAME abort-by-request-id seam (rf2-o8ek): the resources
  out-of-cascade teardown paths (clear-resource / frame destroy) reach it
  through the published `:http/abort-in-flight!` late-bind hook so they can
  abort a managed request by its frame-qualified request-id without the
  resources artefact statically `:require`ing the http transport (rf2-rak684).
  Because that token already carries the frame (`[:rf.req frame-id work-id]`,
  Spec 016), at most one frame can match and the any-frame scan is exact.

  The `:rf.http/managed-abort` fx does NOT route here — a raw app-authored
  `:request-id` is frame-LOCAL, so it uses `abort-in-flight-in-frame!` below.
  Both fire identical abort semantics; they differ only in what they can see."
  ([request-id] (abort-in-flight! request-id :user))
  ([request-id reason]
   (fire-abort! (lookup-in-flight request-id) reason)))

(defn abort-in-flight-in-frame!
  "Best-effort abort the in-flight managed request `frame-id` issued under
  `request-id`, firing its `:abort-fn` with `reason` (default `:user`). The
  FRAME-SCOPED counterpart of `abort-in-flight!` and the seam the
  `:rf.http/managed-abort` fx routes through (rf2-o8ek).

  A sibling frame that happens to have used the same raw `:request-id` is
  invisible here — which is the whole point: reusable app code writes
  `:request-id :articles/load` once, and two isolated frames running that code
  must not cancel each other. No-op when this frame has nothing registered
  under the id. Never throws; returns true iff a handle was found and its
  abort-fn fired."
  ([frame-id request-id] (abort-in-flight-in-frame! frame-id request-id :user))
  ([frame-id request-id reason]
   (fire-abort! (lookup-in-flight frame-id request-id) reason)))

;; ---- per-request-id issuance generation (rf2-azcmd3) -----------------------
;;
;; EP-0011 §Work-id correlation: "one attempt has one work id". HTTP has no
;; generation counter of its own — supersession is keyed on `:request-id`
;; equality — so two requests issued under the SAME `:request-id` both reset
;; their retry `:attempt` to 1 and, before this counter, computed the SAME
;; work-id `[:rf.work/http logical-id 1]`. Tooling and conformance could then
;; not distinguish the superseded attempt from the superseding one by
;; `:work/id`, and EP-0011's single-attempt identity was broken for HTTP
;; supersession (Managed-Effects §Work-id correlation — §184: "one attempt has
;; one work id").
;;
;; This monotonic per-request-id ISSUANCE counter discriminates them: each
;; fresh issuance under a given request-id bumps the counter, so the
;; superseded request (issuance N) and the superseding one (issuance N+1) get
;; distinct work-id tuples. The retry `:attempt` still discriminates retries
;; WITHIN one issuance; the issuance discriminates re-issuances ACROSS
;; supersessions. An anonymous request (no `:request-id`) never supersedes, so
;; it stays at issuance 1.

(defonce ^:private issuance-counters
  ;; [frame-id request-id] → highest issuance number allocated so far.
  ;;
  ;; rf2-o8ek — keyed by the same frame-scoped identity as `in-flight`, because
  ;; the counter exists to discriminate a supersession, and supersession is
  ;; frame-scoped. Under raw-id keying a request in frame B took its FIRST
  ;; issuance number from frame A's counter (measured: frame B's first
  ;; issuance read 2), perturbing B's `:work/id` for a supersession that never
  ;; happened to it.
  ;;
  ;; Monotonic per (frame, request-id) WHILE THE ID IS LIVE: `next-issuance!`
  ;; only ever advances it,
  ;; so a superseded attempt (issuance N) and its superseder (N+1) carry
  ;; distinct `:work/id`s, and a late completion of an old attempt cannot reuse
  ;; a live issuance. rf2-k47b3d — the entry is EVICTED (not decremented) when
  ;; the id's final attempt terminates with the counter still AT that attempt's
  ;; issuance (`evict-issuance-on-completion!`), so an app minting an UNBOUNDED
  ;; distinct-id space (e.g. `[:load-item item-id]` over an unbounded item
  ;; space) no longer accumulates one permanent entry per id ever requested on
  ;; a long-running JVM. A re-issuance after a full eviction restarts at 1 —
  ;; safe because the prior attempt is already terminal (its trace/reply
  ;; emitted), so there is no live work-id collision to discriminate.
  (atom {}))

(defn next-issuance!
  "Allocate and return the next monotonic issuance number for `request-id`
  AS ISSUED BY `frame-id` (rf2-azcmd3; frame-scoped per rf2-o8ek). The FIRST
  issuance under a (frame, request-id) pair is 1; each subsequent re-issuance
  in THAT frame (the caller bumps this before `supersede!`) returns the next
  integer, so the superseded and superseding attempts carry distinct
  `:work/id`s. A sibling frame reusing the same raw id keeps its own sequence
  and starts at 1. Returns 1 for a nil `request-id` (an anonymous request never
  supersedes — there is nothing to discriminate)."
  [frame-id request-id]
  (if (nil? request-id)
    1
    (let [k (scoped-key frame-id request-id)]
      (get (swap! issuance-counters update k (fnil inc 0)) k))))

(defn evict-issuance-on-completion!
  "Evict `request-id`'s issuance counter when the attempt that carried
  `issuance` reaches a TERMINAL completion (rf2-k47b3d), bounding the
  `issuance-counters` map for apps minting UNBOUNDED distinct request-ids.

  Eviction is CONDITIONAL and atomic: the counter is dropped ONLY when it
  still equals `issuance` — no fresh request has re-issued under the same id
  since this attempt was allocated. That single-`swap!` compare-and-drop is
  what preserves the rf2-azcmd3 anti-collision invariant the monotonic counter
  exists for: when a supersede is in flight, `next-issuance!` has ALREADY
  bumped the counter PAST the superseded attempt's `issuance` (it bumps before
  `supersede!`, handlers.cljc), so this evict sees `counter > issuance`, skips,
  and the counter survives for the live successor. A single-issuance request
  (the leak vector) satisfies `counter == issuance`, so it evicts cleanly on
  completion. A `nil` `request-id` never had a counter (an anonymous request
  stays at issuance 1 without touching the map) — no-op.

  Called ONLY at the terminal-completion sites (`finalise-success!`,
  `finalise-failure!`, `dispatch-aborted!`), NEVER on the retry-clear or
  supersede-clear `clear-in-flight!` paths — those are not terminal (the
  attempt continues under the same issuance, or a live successor owns the id).

  rf2-o8ek — `frame-id` is the ISSUING frame (the completing attempt's own
  `:frame`), so an eviction can only ever drop the counter this attempt was
  allocated from and never a sibling frame's live one."
  [frame-id request-id issuance]
  (when (and (some? request-id) (some? issuance))
    (let [k (scoped-key frame-id request-id)]
      (swap! issuance-counters
             (fn [counters]
               (if (= issuance (get counters k))
                 (dissoc counters k)
                 counters)))))
  nil)

(defn reset-issuance-counters-for-test!
  "Test-time helper (rf2-azcmd3): drop the per-request-id issuance counters so
  a fresh test run starts every request-id at issuance 1. Not part of the
  user-facing API."
  []
  (reset! issuance-counters {})
  nil)

(defn issuance-counter-count
  "Test/introspection helper (rf2-k47b3d): the number of resident per-request-id
  issuance counters. Lets a leak test assert the map does not grow unbounded
  across completed requests. Not part of the user-facing API."
  []
  (count @issuance-counters))

(defn supersede!
  "If a request `frame-id` issued is already in flight under `request-id`,
  abort it with `:reason :request-id-superseded`. Per Spec 014 §`:request-id`
  (internal).

  rf2-o8ek — supersession is FRAME-SCOPED. Only the issuing frame's own prior
  attempt is superseded; a sibling frame that reused the same raw
  `:request-id` — the ordinary consequence of running the same reusable app
  code in two isolated frames — is left untouched, and neither frame can
  suppress the other's live request.

  rf2-azcmd3 — returns the superseded handle (or nil when nothing was in
  flight) so the caller can emit the canonical `:status :stale` /
  `:rf.reply/work-status :suppressed` reply-envelope trace for the OLD attempt
  (Managed-Effects §Stale suppression). The handle carries the old attempt's
  identity facts (`:work/id`, `:request-id`, `:origin-event`, `:attempt`,
  `:frame`) the stale-reply trace needs."
  [frame-id request-id]
  (when-let [superseded-handle (lookup-in-flight frame-id request-id)]
    ;; The identity-conditional 2-arg clear: the slot was just read and holds
    ;; exactly this handle, so the dissoc runs — and it keys off the handle's
    ;; own `:frame`, so it can only ever touch this frame's slot.
    (clear-in-flight! request-id superseded-handle)
    (try
      ((:abort-fn superseded-handle) :request-id-superseded)
      (catch #?(:clj Throwable :cljs :default) _ nil))
    superseded-handle))

(defn clear-all-in-flight!
  "Test-time helper: cancel every in-flight managed request, then drop the
  registry. Test fixtures use this between runs.

  rf2-adcmk8 — symmetric with `:machines/reset-timers!` (0-arity): a
  request sleeping in a `schedule-backoff-handle!` retry window holds an
  armed `js/setTimeout` / `ScheduledFuture` backoff timer that would
  otherwise outlive the synchronous test, fire `run-attempt!`, and
  dispatch into a runtime the next test owns. Firing each handle's
  `:abort-fn` runs the once-only cancel cascade (`interop/clear-timeout!`
  / `AbortController.abort` → `clear-in-flight!`), releasing the host-
  clock handle so no timer is left armed. Each abort-fn also clears its
  own slot from both indexes; the trailing `reset!`s mop up any handle
  that was unindexed or that an abort-fn left behind, and guarantee a
  clean registry regardless.

  Walk BOTH indexes: an anonymous-from-actor request (request-id nil) is
  indexed only in `actor-in-flight`, so iterating `in-flight` alone would
  miss its armed timer. Dedupe by identity (the same stamped handle sits
  in both indexes when it carries request-id + actor-id) so each abort-fn
  fires once — the once-only CAS inside the abort-fn already makes a
  double-fire a harmless no-op, but the dedupe keeps the work minimal.

  Snapshot the handles before firing so an abort-fn's own
  `swap!`/`clear-in-flight!` cannot trip a concurrent-modification on the
  iteration. Each abort-fn is fired defensively — a throwing one must not
  strand the remaining handles or leave the registry undropped. The abort
  reason `:test-reset` marks these as fixture-teardown cancellations
  rather than real runtime aborts."
  []
  (let [handles (->> (concat (vals @in-flight)
                             (mapcat val @actor-in-flight))
                     (reduce (fn [unique-handles handle]
                               (if (some #(identical? % handle) unique-handles)
                                 unique-handles
                                 (conj unique-handles handle)))
                             []))]
    (doseq [handle handles]
      (when-let [abort-fn (:abort-fn handle)]
        (try
          (abort-fn :test-reset)
          (catch #?(:clj Throwable :cljs :default) _ nil)))))
  (reset! in-flight {})
  (reset! actor-in-flight {})
  ;; rf2-azcmd3 — drop the per-request-id issuance counters too, so the next
  ;; test run starts every request-id at issuance 1.
  (reset! issuance-counters {})
  nil)

(defn in-flight-snapshot
  "Test-time helper: read the in-flight request index, keyed by the CALLER'S
  RAW `:request-id` (not the internal `[frame-id request-id]` key). Inspecting
  state in tests; not part of the user-facing API.

   - 0-arg — every frame, flattened onto the raw id. rf2-o8ek made the internal
     key frame-scoped; this projection keeps the ergonomic single-frame read
     (`(contains? (in-flight-snapshot) :articles/load)`) working unchanged. It
     is LOSSY across frames by construction: two frames holding the same raw id
     collapse to one entry. A multi-frame assertion MUST use the 1-arg form.
   - 1-arg `[frame-id]` — only the handles that frame issued, still keyed by
     raw id. This is the frame-precise read."
  ([]
   (into {} (map (fn [[k handle]] [(second k) handle])) @in-flight))
  ([frame-id]
   (into {}
         (comp (filter (fn [[k _]] (= frame-id (first k))))
               (map (fn [[k handle]] [(second k) handle])))
         @in-flight)))

(defn actor-in-flight-snapshot
  "Test-time helper: read the actor-owned in-flight index, keyed by the raw
  actor-id (not the internal `[frame-id actor-id]` key). Inspecting state in
  tests; not part of the user-facing API.

   - 0-arg — every frame. Same-named actors in sibling frames have their handle
     vectors CONCATENATED under the one actor-id, matching the pre-rf2-o8ek
     shape; use the 1-arg form to tell them apart.
   - 1-arg `[frame-id]` — only that frame's actors."
  ([]
   (reduce-kv (fn [by-actor k handles]
                (update by-actor (second k) (fnil into []) handles))
              {} @actor-in-flight))
  ([frame-id]
   (reduce-kv (fn [by-actor k handles]
                (if (= frame-id (first k))
                  (assoc by-actor (second k) handles)
                  by-actor))
              {} @actor-in-flight)))

(defn seed-in-flight-for-test!
  "Register a fabricated in-flight handle for tests
  through the SAME `record-in-flight!` path production uses, so both the
  request-id and actor-id indexes stay consistent (the actor-index slot,
  the `:request-id` / `:actor-id` stamps) rather than a raw `swap!` of the
  `in-flight` atom that bypasses those invariants.

  For fixtures that need an in-flight slot present WITHOUT issuing a real
  request (e.g. asserting that a navigation-cancel does not abort an
  active-route request). `handle` is the abort-handle map — it MUST carry
  an `:abort-fn` accepting an abort reason and typically
  `:url`. `request-id` / `actor-id` are optional (pass nil for an
  unindexed / anonymous handle). Returns the stamped handle.

  Not part of the user-facing API — production code routes through
  `:rf.http/managed`."
  ([handle] (seed-in-flight-for-test! (:request-id handle) (:actor-id handle) handle))
  ([request-id actor-id handle]
   (record-in-flight! request-id actor-id handle)))

;; ---- abort-on-actor-destroy (rf2-wvkn) ------------------------------------
;;
;; Per Spec 014 §Abort on actor destroy: when a spawned state-machine
;; actor is destroyed (parent state exit, parent's :after firing,
;; :spawn-all join resolution, frame destroy, imperative destroy),
;; the runtime invokes this fn with the destroyed actor's address. We
;; walk the actor-in-flight index, abort each in-flight request (which
;; cascades into the natural-failure-dispatch path with :reason
;; :actor-destroyed), and clear the slot.
;;
;; Discovered through the late-bind hook table at :http/abort-on-actor-destroy
;; — re-frame.machines does NOT statically :require this namespace; the
;; destroy path looks up this fn at call time. When the http artefact is
;; not on the classpath the hook resolves to nil and the destroy proceeds
;; without aborting any HTTP (apps that don't issue managed-HTTP pay
;; nothing).

(defn abort-on-actor-destroy
  "Per Spec 014 §Abort on actor destroy (rf2-wvkn). Abort every in-flight
  `:rf.http/managed` request that was issued from inside spawned actor
  `actor-id`. Each abort emits a `:rf.http/aborted-on-actor-destroy`
  trace event and dispatches a standard `:rf.http/aborted` reply with
  `:reason :actor-destroyed`.

  Idempotent: invoking against an actor with no in-flight HTTP is a
  no-op. Tolerant of repeated invocations against the same actor —
  the actor-side slot is cleared atomically first so a re-entry sees
  an empty registry.

  Per rf2-plngk the per-handle request-id cleanup is owned by
  `clear-in-flight!` (called inside the abort-fn closure via
  `finalise-failure!`). The earlier shape pre-walked the request-id
  index here AND cleared inside `finalise-failure!`, doubling the
  `swap!` traffic per actor destroy. The actor-side eager dissoc
  remains: it pins the idempotency guarantee against re-entry, and
  it's a single `swap!` regardless of handle count.

  Two arities (rf2-o8ek):

   - 2-arg `[frame-id actor-id]` — FRAME-SCOPED. Aborts only the HTTP the
     named frame's actor issued. A same-named actor in a sibling frame is
     untouched, exactly as §Sibling actors are not affected already promises
     for siblings within one frame. This is the isolated form.
   - 1-arg `[actor-id]` — ANY-FRAME: sweeps that actor-id in EVERY frame. It
     is the arity the machines / core destroy cascade currently calls through
     the `:http/abort-on-actor-destroy` late-bind hook, which passes an
     actor address and no frame, so it preserves the pre-rf2-o8ek behaviour
     byte-for-byte rather than silently narrowing a teardown. Actor addresses
     are frame-LOCAL, so this arity can still reach a sibling frame's work;
     closing that needs the hook's callers to thread the frame they already
     hold, which is a change in the machines and core artefacts."
  ([actor-id]
   (when actor-id
     ;; Snapshot the matching frames BEFORE aborting: each per-frame call
     ;; mutates `actor-in-flight`, and an abort-fn may mutate it re-entrantly.
     (doseq [frame-id (->> @actor-in-flight
                           keys
                           (filter #(= actor-id (second %)))
                           (mapv first))]
       (abort-on-actor-destroy frame-id actor-id)))
   nil)
  ([frame-id actor-id]
  (when actor-id
    (let [k       (scoped-key frame-id actor-id)
          handles (get @actor-in-flight k)]
      ;; Atomically clear the slot first so a re-entry sees no handles.
      (swap! actor-in-flight dissoc k)
      (doseq [handle handles]
        (when rf.interop/debug-enabled?
          ;; rf2-bma05 — the handle carries the originating request's
          ;; effective :sensitive? flag; stamp the trace event so off-box
          ;; consumers honour the privacy contract on actor-destroy aborts.
          (rf.trace/emit! :info :rf.http/aborted-on-actor-destroy
                       (rf.http.privacy/prepare-emit-tags
                         {:request-id (:request-id handle)
                          :actor-id   actor-id
                          :url        (:url handle)}
                         (true? (:sensitive? handle)))))
        (try
          ((:abort-fn handle) :actor-destroyed)
          (catch #?(:clj Throwable :cljs :default) _ nil)))))
   nil))

;; ---- abort-in-flight-for-frame! (rf2-u5kmf8) ------------------------------
;;
;; Epoch-restore host-transient quiesce for NON-resource managed HTTP. The epoch
;; restore boundary (`perform-restore!`) installs the captured durable
;; frame-state WHOLESALE, but a plain `:rf.http/managed` request in flight is
;; host work — an AbortController / CompletableFuture + an in-flight registry
;; slot, NOT frame-state — so the wholesale install leaves it attached to the
;; pre-restore timeline. Unlike resource-backed HTTP (whose work-ledger row the
;; resources reconcile dangles), a plain managed request has no ledger gate: its
;; late completion would still deliver to its original `:rf/reply-to` target
;; against the restored state.
;;
;; This aborts every in-flight managed request the restored frame issued,
;; suppressing the app reply (the abort fires with `:reason :epoch-restored`,
;; which `http-transport` treats as a reply-suppressing reason — no delivery to
;; `:rf/reply-to`) and emitting the EP-0011 `:status :stale` /
;; `:rf.reply/work-status :suppressed` envelope facts for the suppressed attempt
;; (Managed-Effects §restore: "epoch restore MUST NOT revive host work" —
;; clauses 2/3/4 of §Stale suppression). It is the non-resource counterpart of
;; the resources reconcile, published as the `:http/abort-in-flight-for-frame!`
;; late-bind hook the epoch boundary fires AFTER a successful install.

(defn- emit-frame-boundary-stale-trace!
  "Emit the canonical EP-0011 `:status :stale` / `:rf.reply/work-status :suppressed`
  reply-envelope trace for one managed HTTP attempt aborted at a FRAME-LIFECYCLE
  boundary — epoch restore (rf2-u5kmf8) OR frame destruction (rf2-j538f7.8) —
  WITHOUT dispatching any app target. `recovery` names the boundary
  (`:suppressed-on-epoch-restore` vs `:suppressed-on-frame-destroy`) so the two
  reply-suppressing boundaries stay discriminable on the trace stream while
  sharing the identical carried-id-against-nil-current suppression gate.
  Mirrors `http-transport/emit-superseded-stale-trace!` (the supersede sibling):
  the carried work-id is the aborted attempt's identity; there is no current
  work-id (the boundary replaces the attempt with nothing) so the gate
  suppresses the carried id against a nil current. Gated on `debug-enabled?`
  like the other `:rf.http/*` trace rows."
  [handle recovery]
  (when rf.interop/debug-enabled?
    (let [stale-ctx {:request-id   (:request-id handle)
                     :origin-event (:origin-event handle)
                     :issuance     (:issuance handle)
                     :attempt      (:attempt handle)
                     :frame        (:frame handle)}
          {:keys [reply trace]} (rf.http.reply/suppress stale-ctx nil)
          summary (rf.http.reply/trace-reply
                    reply
                    (cond-> {:sensitive? (true? (:sensitive? handle))}
                      (:frame handle) (assoc :frame (:frame handle))))]
      (rf.trace/emit! :info :rf.http/stale-suppressed
                   (cond-> {:rf.reply/status       (:status summary)
                            :rf.reply/work-status  (:rf.reply/work-status summary)
                            :rf.reply/stale-reason (:rf.reply/stale-reason summary)
                            :rf.reply/work-id      (:rf.reply/work-id summary)
                            :rf.reply/work-kind             :http
                            :rf.reply/carried      (:rf.reply/carried trace)
                            :rf.reply/current      (:rf.reply/current trace)
                            :recovery              recovery}
                     (:frame handle) (assoc :frame (:frame handle)))))))

(defn- abort-frame-handles!
  "Shared frame-scoped abort walk for the two reply-suppressing frame-lifecycle
  boundaries: epoch restore (`:epoch-restored`) and frame destroy
  (`:frame-destroyed`). Walks BOTH indexes (an anonymous-from-actor request is
  only in `actor-in-flight`), filtering on the handle's `:frame` stamp, and
  dedupes by identity so a handle present in both indexes fires once. For each
  matching handle: emit the EP-0011 stale-suppression envelope facts with
  `recovery`, then fire its `:abort-fn` with `reason` — a reply-suppressing
  reason, so the late completion does NOT deliver to its original `:rf/reply-to`
  target. The abort-fn cascade clears the registry slot via `clear-in-flight!`.

  Snapshots the handles before firing so an abort-fn's own `clear-in-flight!`
  swap cannot trip a concurrent-modification on the iteration. Each abort-fn is
  fired defensively — a throwing one must not strand the rest. Idempotent and a
  no-op for a frame with no in-flight managed HTTP (an app that issues none pays
  nothing, and a second sweep over already-cleared indexes finds no handles).
  Returns nil."
  [frame-id reason recovery]
  (when frame-id
    (let [handles (->> (concat (vals @in-flight)
                               (mapcat val @actor-in-flight))
                       (filter #(= frame-id (:frame %)))
                       (reduce (fn [unique-handles handle]
                                 (if (some #(identical? % handle) unique-handles)
                                   unique-handles
                                   (conj unique-handles handle)))
                               []))]
      (doseq [handle handles]
        (emit-frame-boundary-stale-trace! handle recovery)
        (when-let [abort-fn (:abort-fn handle)]
          (try (abort-fn reason)
               (catch #?(:clj Throwable :cljs :default) _ nil))))))
  nil)

(defn abort-in-flight-for-frame!
  "Abort every in-flight managed HTTP request issued by `frame-id`, because epoch
  restore unwound that frame's timeline (rf2-u5kmf8). For each matching handle:
  emit the EP-0011 stale-suppression envelope facts, then fire its `:abort-fn`
  with `:reason :epoch-restored` — a reply-suppressing reason, so the late
  completion does NOT deliver to its original `:rf/reply-to` target
  (Managed-Effects §restore). The abort-fn cascade clears the registry slot via
  `clear-in-flight!`.

  Walks BOTH indexes (an anonymous-from-actor request is only in
  `actor-in-flight`), filtering on the handle's `:frame` stamp, and dedupes by
  identity so a handle present in both indexes fires once. Snapshots the handles
  before firing so an abort-fn's own `clear-in-flight!` swap cannot trip a
  concurrent-modification on the iteration. Each abort-fn is fired defensively —
  a throwing one must not strand the rest. Idempotent and a no-op for a frame
  with no in-flight managed HTTP (an app that issues none pays nothing). Returns
  nil."
  [frame-id]
  (abort-frame-handles! frame-id :epoch-restored :suppressed-on-epoch-restore))

(defn abort-in-flight-on-frame-destroyed!
  "Abort every in-flight managed HTTP request issued by `frame-id`, because the
  owning frame is being DESTROYED (rf2-j538f7.8). The frame-teardown counterpart
  of `abort-in-flight-for-frame!` (epoch restore): the SAME frame-filtered,
  identity-deduped, sibling-preserving walk over both indexes, but fires each
  `:abort-fn` with `:reason :frame-destroyed` and stamps the stale-suppression
  trace with `:recovery :suppressed-on-frame-destroy`.

  Frame destroy is reply-suppressing: the target frame is already marked
  destroyed, so dispatching a live cancellation reply into it is invalid.
  `:frame-destroyed` is a member of `http-transport/reply-suppressing-abort-
  reasons`, so the abort cascade cancels the live fetch/future or sleeping
  backoff timer, detaches any external `:abort-signal` listener, clears both
  registry indexes, and delivers NOTHING to the original `:rf/reply-to` target —
  no `:rf.error/frame-destroyed` dispatch into the dead frame.

  Published as the `:http/on-frame-destroyed!` late-bind hook and called from
  core `frame/destroy-frame!` AFTER machine + resource teardown, so actor-owned
  work gets its more specific `:actor-destroyed` semantics and resource-owned
  work gets its ledger/owner cleanup first; this generic sweep then catches the
  remaining PLAIN managed requests (ordinary event-handler issuance with no
  actor id — the exposed path) and no-ops on any handle already cleared (the
  already-empty indexes yield no handles). Does NOT overload `:epoch-restored`.
  Idempotent; a no-op for a frame with no in-flight managed HTTP. Returns nil."
  [frame-id]
  (abort-frame-handles! frame-id :frame-destroyed :suppressed-on-frame-destroy))

;; ---- spawned-actor detection (rf2-ma0wvq inversion) -----------------------
;;
;; Per Spec 014 §Abort on actor destroy: a managed request "belongs to"
;; spawned actor `<spawned-id>` iff its originating event vector's first
;; element is `<spawned-id>` AND that id is registered as a spawned actor
;; in the frame's runtime-db spawn registry (per Spec 005 §Declarative
;; :spawn). That ownership decision is MACHINES-OWNED: machines knows the
;; registry shape, so it publishes the `:machines/owning-actor-id` late-
;; bind hook `(fn [frame-id event-id]) -> actor-id|nil`. http no longer
;; re-states the registry path or walks the registry itself — it asks
;; machines through the hook and treats a nil/absent hook (no machines
;; artefact on the classpath) as "not owned by any actor". This keeps the
;; structural dependency on the `:spawned` runtime-db layout entirely
;; inside the machines artefact that owns it (rf2-ma0wvq inverts the old
;; coupling, where http peeked inside machines state to classify ownership
;; while machines already called http through `:http/abort-on-actor-destroy`
;; for the destroy side).

(defn resolve-owning-actor-id
  "Resolve the spawned-actor-id for the request at hand, given the frame
  id and the originating event vector. Returns the actor-id (a keyword,
  the spawned actor's machine address) when the originating event-id is
  owned by a spawned actor, otherwise nil — meaning the request is NOT
  subject to actor-destroy cancellation (it was dispatched from an
  ordinary event handler, not from inside a spawned actor).

  Ownership is decided by the machines artefact via the
  `:machines/owning-actor-id` late-bind hook (rf2-ma0wvq): http asks
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
