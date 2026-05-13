;;;; re-frame-pair2.runtime — pair2 helper namespace, preloaded into the app.
;;;;
;;;; This file is loaded into the consumer app via shadow-cljs's
;;;; `:devtools :preloads` mechanism. See `skills/re-frame-pair2/SKILL.md`
;;;; for the one-line setup; the MCP server's `discover-app` tool calls
;;;; `(re-frame-pair2.runtime/health)` and refuses with a structured
;;;; `:reason :runtime-not-preloaded` error pointing at the setup doc
;;;; when the namespace isn't present.
;;;;
;;;; Design invariants (see docs/initial-spec.md):
;;;;   - All trace and epoch reads consume re-frame2's public Tool-Pair
;;;;     surfaces (`re-frame.core/register-trace-cb!`, `trace-buffer`,
;;;;     `register-epoch-cb!`, `epoch-history`, `restore-epoch`). No
;;;;     reaching into private namespaces.
;;;;   - Exactly one trace listener (`:re-frame-pair2`) and one epoch
;;;;     listener (`:re-frame-pair2-epoch`) are registered. Multi-tool
;;;;     coexistence is the expected default; per Spec 009 §Listener
;;;;     ordering, listener ordering is not contract.
;;;;   - Streaming subscriptions (rf2-hq49) ride those same single
;;;;     listener slots — the listener fans matching events into per-
;;;;     subscription queues. `subscribe!` / `drain-subscription!` /
;;;;     `unsubscribe!` are the public surface the MCP server's
;;;;     `subscribe` op consumes.
;;;;   - The `session-id` sentinel below is read by the MCP server's
;;;;     preload probe. A mirror is also set on
;;;;     `js/globalThis.__re_frame_pair2_runtime` at load time so the
;;;;     probe can be a single bencode round-trip rather than a CLJS
;;;;     compile. A full page refresh wipes both — `discover-app`
;;;;     reports the missing preload with a structured setup hint.

(ns re-frame-pair2.runtime
  (:require [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [clojure.data :as data]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Session sentinel
;; ---------------------------------------------------------------------------
;;
;; A random UUID set once per preload. The MCP server probes either
;; `re-frame-pair2.runtime/session-id` (CLJS var) or its mirror at
;; `js/globalThis.__re_frame_pair2_runtime` (cheaper, no compile) to
;; confirm this namespace landed in the running browser session.

(def session-id
  (str (random-uuid)))

;; Globally-visible mirror — the preload probe in
;; `re-frame-pair2-mcp.tools/ensure-runtime!` reads this rather than
;; resolving the CLJS var, so the probe is one bencode round-trip on
;; the persistent socket. Set at namespace-load time; cleared by a
;; full page refresh (along with everything else). The marker uses
;; `js-obj` (not the `#js` reader literal) so the bb-runnable
;; structural tests under `tests/runtime/` keep reading the rest of
;; the file — bb's reader rejects `#js` at top level.
(defonce ^:private install-global-sentinel!
  (do (when (exists? js/globalThis)
        (aset js/globalThis "__re_frame_pair2_runtime"
              (js-obj "session-id" session-id
                      "installed"  (js/Date.now))))
      true))

(defn sentinel
  "Return the session sentinel. Used by the shim to confirm the runtime
   is still alive in the current browser runtime."
  []
  {:ok?        true
   :session-id session-id
   :installed  (js/Date.now)})

;; ---------------------------------------------------------------------------
;; Operating frame
;; ---------------------------------------------------------------------------
;;
;; re-frame2 is multi-frame (Spec 002). Every read/write op resolves an
;; *operating frame* — the session-cached default, overridable per call.
;; Mutating ops refuse with :ambiguous-frame when more than one frame is
;; registered and the session hasn't selected one. Reads proceed against
;; :rf/default and emit a warning.

(defonce ^:private selected-frame (atom nil))

(defn select-frame!
  "Pin the operating frame for this session. Subsequent ops use it
   unless an explicit `:frame` opt is passed."
  [frame-id]
  (reset! selected-frame frame-id)
  {:ok? true :frame frame-id})

(defn current-frame
  "Resolve the operating frame: explicit override -> session pin ->
   the sole registered frame -> nil (ambiguous).

   Returns nil whenever more than one frame is registered AND the
   session hasn't selected one (and the caller didn't pass an override).
   Mutating ops then refuse via the `:ambiguous-frame` path in
   `pair-dispatch-sync!`. Reads that nil-default to `:rf/default` would
   silently land in the wrong frame, so the resolver is deliberately
   conservative — callers either pin via `select-frame!`, pass an
   explicit override, or get a clear refusal."
  ([] (current-frame nil))
  ([override]
   (or override
       @selected-frame
       (let [fids (rf/frame-ids)]
         (when (= 1 (count fids))
           (first fids))))))

(defn frames-list
  "All registered, non-destroyed frame ids plus the operating frame."
  []
  {:ok?              true
   :frames           (vec (rf/frame-ids))
   :selected         @selected-frame
   :operating        (current-frame)})

(defn frames-meta
  "Frame metadata (config, lifecycle) for `id` — `(rf/frame-meta id)`."
  [id]
  (or (rf/frame-meta id)
      {:ok? false :reason :no-such-frame :frame-id id}))

;; ---------------------------------------------------------------------------
;; app-db read/write
;; ---------------------------------------------------------------------------
;;
;; All app-db access is via the public Tool-Pair surfaces:
;;   (rf/get-frame-db frame-id)        — current value
;;   (rf/snapshot-of path opts)        — path-scoped read with :frame opt

(defn snapshot
  "Full current app-db value for the operating frame. No-arg form uses
   the session's operating frame; arity-1 takes an explicit frame-id."
  ([] (snapshot (current-frame)))
  ([frame-id]
   (rf/get-frame-db frame-id)))

(defn app-db-at
  "Read a path in app-db for the operating frame.
   Sugar over (rf/snapshot-of path {:frame frame-id})."
  ([path] (app-db-at path (current-frame)))
  ([path frame-id]
   (rf/snapshot-of path {:frame frame-id})))

(defn app-db-reset!
  "Replace the operating frame's app-db with v. Logged explicitly via
   `tap>` so the human sees what the agent changed.

   Delegates to the canonical Tool-Pair write surface
   `(rf/reset-frame-db! frame-id v)` (Tool-Pair §Pair-tool writes,
   rf2-zq55). That surface bypasses the dispatch loop (no event, no
   cascade) but DOES record a synthetic `:rf/epoch-record` with
   `:event-id :rf.epoch/db-replaced` so that `restore-epoch` can
   rewind past the injection. Use sparingly — prefer `dispatch` for
   any change you want the data loop to see.

   Returns `{:ok? true :frame frame-id}` on success.

   Failure modes (each is a no-op on app-db; corresponding
   `:rf.epoch/*` or `:rf.error/*` trace fires per Spec 009):
     :no-such-frame                — frame not registered
     :reset-frame-db-during-drain  — drain in flight
     :schema-mismatch              — v fails the frame's app-schema
     :epoch-artefact-missing       — re-frame2-epoch artefact not loaded"
  ([v] (app-db-reset! v (current-frame)))
  ([v frame-id]
   (tap> {:re-frame-pair2/op :app-db/reset
          :frame              frame-id
          :previous           (rf/get-frame-db frame-id)
          :next               v
          :t                  (js/Date.now)})
   (try
     (if (rf/reset-frame-db! frame-id v)
       {:ok? true :frame frame-id}
       ;; reset-frame-db! returns false on the soft-failure modes
       ;; (unknown frame, in-drain, schema-mismatch). The structured
       ;; reason is in the trace stream (`:rf.error/no-such-handler`,
       ;; `:rf.epoch/reset-frame-db-during-drain`,
       ;; `:rf.epoch/reset-frame-db-schema-mismatch`); we surface a
       ;; `:reset-rejected` umbrella so callers know the call did
       ;; not land without having to interpret the trace.
       {:ok?    false
        :frame  frame-id
        :reason :reset-rejected
        :hint   "rf/reset-frame-db! returned false. Inspect (rf/trace-buffer {:op-type :error}) and {:op-type :rf.epoch} for the structured reason — :rf.error/no-such-handler, :rf.epoch/reset-frame-db-during-drain, or :rf.epoch/reset-frame-db-schema-mismatch."})
     (catch :default e
       (let [{:keys [reason] :as data} (ex-data e)]
         {:ok?     false
          :frame   frame-id
          :reason  (or reason :reset-throw)
          :message (.-message e)
          :data    data})))))

(defn schemas
  "All registered app-schemas for the operating frame.
   Map of `path → schema`. (rf/app-schemas frame-id)"
  ([] (schemas (current-frame)))
  ([frame-id]
   (rf/app-schemas frame-id)))

;; ---------------------------------------------------------------------------
;; Registrar introspection
;; ---------------------------------------------------------------------------

(defn registrar-list
  "Enumerate registered ids under a kind. (rf/handlers kind) returns
   `{id meta}`; we return the sorted id vector."
  [kind]
  (-> (rf/handlers kind) keys sort vec))

(defn- handler-fn-hash
  "Opaque hash for hot-reload probe comparisons. Function refs aren't
   reliably `=`, so hash a stringified form."
  [meta-map]
  (some-> meta-map :handler-fn str hash))

(defn registrar-describe
  "Return public handler metadata for kind+id. (rf/handler-meta kind id)
   already gives the source coords (:ns :line :file :column), the
   :handler-fn, the :rf/machine? flag where applicable, and any extra
   keys the registrar carries (e.g. retained source forms when present).

   Augments with :handler-fn-hash for use as a probe over hot-reload."
  [kind id]
  (if-let [m (rf/handler-meta kind id)]
    (assoc m :handler-fn-hash (handler-fn-hash m))
    {:ok? false :reason :not-registered :kind kind :id id}))

(defn registrar-handler-ref
  "Stable opaque identifier for the currently-registered handler. Used
   as a hot-reload probe: capture before edit, compare after. The hash
   changes on every re-registration (new fn ref, new source coords)."
  [kind id]
  (handler-fn-hash (rf/handler-meta kind id)))

;; ---------------------------------------------------------------------------
;; Subscriptions
;; ---------------------------------------------------------------------------

(defn sub-cache
  "(rf/sub-cache frame-id) — public Tool-Pair surface returning
   `{query-v {:value v :ref-count n}}` for every materialised
   subscription in the operating frame. CLJS-only; nil on JVM."
  ([] (sub-cache (current-frame)))
  ([frame-id]
   (rf/sub-cache frame-id)))

(defn subs-sample
  "Subscribe to query-v in the operating frame and deref once. Goes
   through `rf/subscribe` so the cache lifecycle is the standard one —
   fine for one-shot probes, not for repeated polling outside a
   reactive context.

   Threads the resolved operating frame through `(rf/subscribe
   frame-id query-v)` so a prior `select-frame!` (or an explicit
   `frame-id` arg) actually steers the read. Returns
   `{:ok? false :reason :ambiguous-frame}` if no frame can be
   resolved — read ops shouldn't silently fall back to `:rf/default`
   in a multi-frame session."
  ([query-v] (subs-sample query-v (current-frame)))
  ([query-v frame-id]
   (cond
     (nil? frame-id)
     {:ok? false :reason :ambiguous-frame
      :hint "Multi-frame session with no selected frame — pass `frame-id` or call `select-frame!` first."}

     :else
     (try
       @(rf/subscribe frame-id query-v)
       (catch :default e
         {:ok? false :reason :sub-error :message (.-message e) :frame frame-id})))))

;; ---------------------------------------------------------------------------
;; Machines (Spec 005)
;; ---------------------------------------------------------------------------

(defn machines-list
  "(rf/machines) — all registered machine ids."
  []
  (vec (rf/machines)))

(defn machine-describe
  "(rf/machine-meta id) — registered spec map for one machine, or
   `{:ok? false :reason :not-a-machine}`."
  [machine-id]
  (or (rf/machine-meta machine-id)
      {:ok? false :reason :not-a-machine :id machine-id}))

(defn machine-state
  "Snapshot of one machine in the operating frame. Per Spec 005, machine
   snapshots live at `[:rf/machines machine-id]` in app-db."
  ([machine-id] (machine-state machine-id (current-frame)))
  ([machine-id frame-id]
   (rf/snapshot-of [:rf/machines machine-id] {:frame frame-id})))

;; ---------------------------------------------------------------------------
;; Epoch history & assembled-stream listener
;; ---------------------------------------------------------------------------
;;
;; re-frame2 ships first-class epoch recording. The listener fires once
;; per drain-settle with the assembled `:rf/epoch-record`. We register
;; ours under id :re-frame-pair2-epoch — multi-tool coexistence per
;; Spec 009 §Listener ordering.
;;
;; The stash atom retains the last N records observed via the listener,
;; even though `(rf/epoch-history frame-id)` already returns the ring.
;; Two reasons: (a) we can stash *only* this skill's perspective, useful
;; for last-pair-epoch; (b) it gives the watch loop a cheap "what's
;; new since I last checked" without re-walking the framework's ring.

(defonce ^:private observed-epochs
  ;; frame-id -> vector of records (oldest first), capped to 500
  (atom {}))

(defonce ^:private pair-epoch-ids
  ;; Set of epoch-ids that this skill itself dispatched (used by
  ;; last-pair-epoch). Populated by the dispatch helpers below.
  (atom #{}))

;; The per-frame `observed-epochs` stash and the streaming dispatch both
;; ride the same `register-epoch-cb!` slot — combined into
;; `on-epoch-streaming` below to keep listener ordering deterministic
;; (rf2-hq49). The legacy single-purpose `on-epoch` was inlined into the
;; streaming listener.

(declare on-epoch-streaming)

(defn- ensure-epoch-listener!
  "Register the assembled-epoch listener if it isn't already. Idempotent —
   passing the same id twice replaces (per `register-epoch-cb!` contract).

   Installs the streaming-aware listener (rf2-hq49). The streaming
   dispatch is a no-op when no subscriptions are active, so this is
   safe to install unconditionally."
  []
  (rf/register-epoch-cb! :re-frame-pair2-epoch on-epoch-streaming))

(defn epoch-history
  "Pass-through to (rf/epoch-history frame-id) — the framework's
   per-frame ring, oldest-first."
  ([] (epoch-history (current-frame)))
  ([frame-id]
   (vec (rf/epoch-history frame-id))))

(defn last-epoch
  "Most recent epoch in the operating frame's history."
  ([] (last-epoch (current-frame)))
  ([frame-id]
   (peek (rf/epoch-history frame-id))))

(defn last-pair-epoch
  "Most recent epoch this skill dispatched. Walks the operating frame's
   history backward, filtering by epoch-id membership in pair-epoch-ids."
  ([] (last-pair-epoch (current-frame)))
  ([frame-id]
   (let [ours @pair-epoch-ids]
     (->> (rf/epoch-history frame-id)
          reverse
          (some (fn [r] (when (contains? ours (:epoch-id r)) r)))))))

(defn epoch-by-id
  "Look up an epoch by id in the operating frame's history."
  ([epoch-id] (epoch-by-id epoch-id (current-frame)))
  ([epoch-id frame-id]
   (some (fn [r] (when (= epoch-id (:epoch-id r)) r))
         (rf/epoch-history frame-id))))

(defn epochs-since
  "Records appended *after* the given epoch-id in the operating frame.
   Returns `{:epochs [...] :id-aged-out? bool :head-id <last-id>}`.

   Semantics:
     - `id` nil                  -> all records, :id-aged-out? false
     - `id` matches current head -> [], :id-aged-out? false
     - `id` matches some record  -> records strictly after it
     - `id` not found            -> [], :id-aged-out? true"
  ([epoch-id] (epochs-since epoch-id (current-frame)))
  ([epoch-id frame-id]
   (let [history (vec (rf/epoch-history frame-id))
         head-id (some-> history peek :epoch-id)]
     (cond
       (nil? epoch-id)
       {:epochs history :id-aged-out? false :head-id head-id}

       (some #(= epoch-id (:epoch-id %)) history)
       {:epochs (vec (rest (drop-while #(not= epoch-id (:epoch-id %)) history)))
        :id-aged-out? false
        :head-id head-id}

       :else
       {:epochs [] :id-aged-out? true :head-id head-id :requested-id epoch-id}))))

(defn epochs-in-last-ms
  "Records whose `:committed-at` falls inside the last N ms in the
   operating frame's history."
  ([ms] (epochs-in-last-ms ms (current-frame)))
  ([ms frame-id]
   (let [cutoff (- (js/Date.now) ms)]
     (->> (rf/epoch-history frame-id)
          (filterv #(>= (or (:committed-at %) 0) cutoff))))))

(defn find-where
  "Walk the operating frame's epoch-history in reverse chronological
   order and return the first record matching the predicate, or nil.

   Primary forensic op — 'find the epoch where X happened'. Examples:

     ;; find the epoch where :auth-state flipped to :expired
     (find-where
       (fn [e] (= :expired (get-in (:db-after e) [:auth-state]))))

     ;; find the epoch that triggered a specific event id
     (find-where
       (fn [e] (= :user/sign-out (:event-id e))))

   Most recent match wins — usually what you want for 'how did I get
   into this state?' post-mortems."
  ([pred] (find-where pred (current-frame)))
  ([pred frame-id]
   (->> (rf/epoch-history frame-id)
        reverse
        (filter pred)
        first)))

(defn find-all-where
  "Like find-where but returns every matching epoch, newest first.
   Use when you want the trajectory of a path — 'every epoch where
   :cart changed' — not just the most recent transition."
  ([pred] (find-all-where pred (current-frame)))
  ([pred frame-id]
   (->> (rf/epoch-history frame-id)
        reverse
        (filterv pred))))

(defn epoch-diff
  "Pre-computed diff between an epoch's `:db-before` and `:db-after`,
   shaped to match the v1 vocabulary the skill uses:
       {:only-before <map> :only-after <map> :common <map>}"
  [{:keys [db-before db-after]}]
  (let [[ob oa c] (data/diff db-before db-after)]
    {:only-before ob :only-after oa :common c}))

;; ---------------------------------------------------------------------------
;; Trace stream listener (raw-trace, retain-N buffer is in framework)
;; ---------------------------------------------------------------------------
;;
;; The framework already maintains a retain-N ring buffer accessible via
;; `(rf/trace-buffer opts)`. We register one listener here for callers
;; that want a programmatic side-channel (e.g. a watch loop's idle
;; detector); the buffer remains the canonical query surface.

(defonce ^:private last-trace-id (atom 0))

;; The legacy `last-trace-id` cursor and the streaming dispatch both
;; ride the same `register-trace-cb!` slot — combined into
;; `on-trace-streaming` below (rf2-hq49). The legacy `on-trace` was
;; inlined into the streaming listener.

(declare on-trace-streaming)

(defn- ensure-trace-listener!
  "Register the raw-trace listener if it isn't already.

   Installs the streaming-aware listener (rf2-hq49). The streaming
   dispatch is a no-op when no subscriptions are active, so this is
   safe to install unconditionally — `last-trace-event-id` keeps
   working through it."
  []
  (rf/register-trace-cb! :re-frame-pair2 on-trace-streaming))

(defn last-trace-event-id
  "Last trace event id observed by the skill's listener. Useful as a
   `:since` cursor for `(rf/trace-buffer {:since N})`."
  []
  @last-trace-id)

;; ---------------------------------------------------------------------------
;; Streaming subscriptions (rf2-hq49)
;; ---------------------------------------------------------------------------
;;
;; A subscription is a server-side filtered tap on the trace bus or the
;; epoch bus. The MCP server registers a subscription via `subscribe!`,
;; then polls `drain-subscription!` in a tight loop to retrieve queued
;; events between polls; each batch is pushed back to the MCP client as
;; a `notifications/progress` notification.
;;
;; Why poll-from-server rather than push-from-runtime? The runtime lives
;; in the browser tab; the only side-channel back to the MCP server is
;; the nREPL socket (controlled by the server). Polling at ~100ms is
;; well below the perceptual threshold for the agent loop, costs one
;; bencode round-trip per tick, and stays correct across page reloads
;; (a reload wipes the runtime's subscription registry along with
;; everything else; the server's poll loop sees an empty drain + the
;; sub-id absent from `subscription-info`, and exits cleanly).
;;
;; Per-subscription state:
;;   {:id <uuid>
;;    :topic    :trace | :epoch | :fx | :error
;;    :filter   <filter-map>         ;; vocab depends on topic
;;    :queue    <vector of events>   ;; appended-to by the cb, drained by the server
;;    :overflow <integer>            ;; events dropped because queue exceeded :max-buffered
;;    :created-at <ms>
;;    :max-buffered <integer>}       ;; queue cap; default 500
;;
;; Topic semantics:
;;   :trace  — every event in the raw trace stream matching `:filter`
;;             (filter map mirrors `(rf/trace-buffer)` filter vocab —
;;             see rf2-97ah0). One event per delivered trace event.
;;   :epoch  — every assembled `:rf/epoch-record` matching `:filter`
;;             (filter map mirrors `epoch-matches?` — see watch-epochs).
;;             One event per committed epoch.
;;   :fx     — sugar for `:topic :trace :filter {:op-type :fx}` with
;;             optional `:fx-id` and `:event-id` axes from the trace
;;             filter vocabulary.
;;   :error  — sugar for `:topic :trace :filter {:op-type :error}`,
;;             with `:event-id`/`:handler-id`/`:source` available.
;;
;; The :fx and :error topics compose with axes from the trace filter
;; vocabulary verbatim — they just default `:op-type` to `:fx` /
;; `:error` and let callers override the rest.

(defonce ^:private subscriptions
  ;; sub-id -> subscription map (see above)
  (atom {}))

(def ^:private default-max-buffered 500)

;; ---------------------------------------------------------------------------
;; Privacy posture for the streaming surface (rf2-3cted)
;; ---------------------------------------------------------------------------
;;
;; Per Spec 009 §Privacy / sensitive data: framework-published listener
;; integrations — including the pair2 server — MUST default-suppress
;; `:sensitive? true` trace events before forwarding to the AI surface.
;;
;; The trust boundary is "any trace data that leaves the browser tab and
;; reaches the LLM-facing channel". Streaming subscriptions are how that
;; happens in pair2: the MCP server registers a subscription, polls
;; `drain-subscription!`, and forwards every drained event back to the
;; agent as a `notifications/progress` payload. The retain-N ring buffer
;; reached via `(rf/trace-buffer)` is a separate, explicit read surface;
;; agents asking for it are making a deliberate request and the filter
;; vocabulary already exposes `:sensitive? false` for tools that want to
;; pre-filter (see Spec 009 §Filter vocabulary).
;;
;; The default is **drop**. Apps that need sensitive cascades visible to
;; the pair tool (rare; only when the tool is itself the trust boundary)
;; opt in explicitly via `configure-privacy!`.
;;
;; The flag is consulted at `on-trace-streaming` entry — before any
;; subscription's queue sees the event. Dropped events still update the
;; `last-trace-id` cursor (so `last-trace-event-id` keeps incrementing
;; monotonically) and still ride `(rf/trace-buffer)` unchanged — only
;; the streaming dispatch is gated.

(defonce ^:private privacy-config
  ;; {:include-sensitive? bool}
  ;; Default: false — suppress `:sensitive? true` events from the
  ;; streaming dispatch path. See namespace docs above.
  (atom {:include-sensitive? false}))

(defn configure-privacy!
  "Set the privacy posture for the streaming surface. Opts:

     :include-sensitive?  boolean — when true, `:sensitive? true` trace
                          events ride the streaming dispatch unchanged.
                          Default: **false** (drop) per Spec 009 §Privacy.

   Returns the merged config map. Idempotent. Use sparingly — the
   default exists because pair2 forwards events to an LLM-facing
   channel, and the framework's privacy contract is that sensitive
   data does not cross that boundary by accident."
  [{:keys [include-sensitive?] :as opts}]
  (swap! privacy-config merge (select-keys opts [:include-sensitive?]))
  (assoc @privacy-config :ok? true))

(defn privacy-config-snapshot
  "Return the current privacy config — diagnostic helper."
  []
  (assoc @privacy-config :ok? true))

(defn- streaming-drop?
  "True when the streaming surface should drop `ev` for privacy reasons.
   Today: any trace event stamped `:sensitive? true` at the top level
   unless the operator has opted in via `configure-privacy!`."
  [ev]
  (and (true? (:sensitive? ev))
       (not (true? (:include-sensitive? @privacy-config)))))

(defn- topic->base-filter
  "Map a topic keyword to its base trace-filter constraints. `:fx` and
   `:error` are sugar over `:op-type`; `:trace` and `:epoch` add no
   base constraint here (the user-supplied filter is the only constraint)."
  [topic]
  (case topic
    :fx    {:op-type :fx}
    :error {:op-type :error}
    {}))

(defn- compose-trace-filter
  "Compose the topic's base trace-filter with the user-supplied filter.
   User keys win on conflict — the topic is a default, not a lock."
  [topic user-filter]
  (merge (topic->base-filter topic) (or user-filter {})))

(declare epoch-matches?) ;; resolved below

(defn- trace-matches?
  "Test a raw trace event against a filter map. Mirrors the filter
   vocabulary of `(rf/trace-buffer opts)` (rf2-97ah0) — composes
   AND-wise, absent key means no constraint on that axis."
  [filter-map ev]
  (let [{:keys [operation op-type frame severity
                event-id handler-id source origin
                dispatch-id since-ms between]}
        filter-map
        [t0 t1] (when (and (sequential? between) (= 2 (count between)))
                  between)]
    (boolean
      (and (or (nil? operation)  (= operation (:operation ev)))
           (or (nil? op-type)    (= op-type   (:op-type ev)))
           (or (nil? severity)   (= severity  (:op-type ev)))
           (or (nil? frame)      (= frame
                                    (or (:frame ev)
                                        (get-in ev [:tags :frame]))))
           (or (nil? event-id)   (= event-id
                                    (get-in ev [:tags :event-id])))
           (or (nil? handler-id) (= handler-id
                                    (get-in ev [:tags :handler-id])))
           (or (nil? source)     (= source
                                    (or (:source ev)
                                        (get-in ev [:tags :source]))))
           (or (nil? origin)     (= origin
                                    (get-in ev [:tags :origin])))
           (or (nil? dispatch-id)(= dispatch-id
                                    (get-in ev [:tags :dispatch-id])))
           (or (nil? since-ms)   (and (number? (:time ev))
                                      (> (:time ev) since-ms)))
           (or (nil? t0)         (and (number? (:time ev))
                                      (<= t0 (:time ev) t1)))))))

(defn- enqueue!
  "Append an event to a subscription's queue, honouring max-buffered.
   When the queue is full we drop the new event and increment overflow —
   keeping the oldest events lets the agent reconstruct the start of a
   storm rather than seeing only the tail."
  [sub-state sub-id event]
  (update sub-state sub-id
          (fn [sub]
            (when sub
              (let [q  (:queue sub)
                    n  (count q)
                    cap (:max-buffered sub default-max-buffered)]
                (if (>= n cap)
                  (update sub :overflow (fnil inc 0))
                  (update sub :queue conj event)))))))

(defn- dispatch-trace-to-subs!
  "Called from the raw-trace listener — iterates active subscriptions of
   trace-like topics, matches, enqueues. Cheap when no subs exist (the
   common path)."
  [ev]
  (swap! subscriptions
         (fn [m]
           (reduce-kv
             (fn [acc sub-id sub]
               (if (and (contains? #{:trace :fx :error} (:topic sub))
                        (trace-matches? (:compiled-filter sub) ev))
                 (enqueue! acc sub-id ev)
                 acc))
             m m))))

(defn- dispatch-epoch-to-subs!
  "Called from the assembled-epoch listener — iterates active epoch
   subscriptions, matches, enqueues."
  [record]
  (swap! subscriptions
         (fn [m]
           (reduce-kv
             (fn [acc sub-id sub]
               (if (and (= :epoch (:topic sub))
                        (epoch-matches? (or (:filter sub) {}) record))
                 (enqueue! acc sub-id record)
                 acc))
             m m))))

(defn- on-trace-streaming
  "Replacement raw-trace listener that drives both the last-trace-id
   cursor (legacy) and the streaming subs dispatch.

   Privacy filter (rf2-3cted): trace events stamped `:sensitive? true`
   at the top level are dropped from the streaming dispatch by default,
   per Spec 009 §Privacy. The `last-trace-id` cursor still advances so
   the legacy `since`-based ring-buffer reads remain monotonic — only
   the LLM-facing streaming surface is gated. Opt in via
   `(configure-privacy! {:include-sensitive? true})`."
  [ev]
  (when-let [id (:id ev)]
    (when (number? id) (reset! last-trace-id id)))
  (when-not (streaming-drop? ev)
    (dispatch-trace-to-subs! ev)))

(defn- on-epoch-streaming
  "Replacement assembled-epoch listener that drives both the observed
   stash (legacy) and the streaming subs dispatch."
  [record]
  (swap! observed-epochs
         (fn [m]
           (let [frame-id (:frame record)
                 v        (or (get m frame-id) [])
                 v+       (conj v record)
                 n        (count v+)]
             (assoc m frame-id (if (> n 500) (subvec v+ (- n 500)) v+)))))
  (dispatch-epoch-to-subs! record))

(defn subscribe!
  "Open a streaming subscription on the trace or epoch bus. Returns
   `{:ok? true :sub-id <uuid>}`. Subsequent calls to
   `drain-subscription!` return queued events matching `:filter`.

   Opts:
     :topic   :trace | :epoch | :fx | :error  (required)
     :filter  filter map — vocab depends on topic. See namespace docs.
     :max-buffered  cap on the in-runtime queue. Default 500. Once
                    the cap is reached, new events are dropped and
                    counted in `:overflow`.

   Idempotency: each call returns a fresh sub-id — repeated `subscribe!`
   calls do not share state. Use `unsubscribe!` to release."
  [{:keys [topic filter max-buffered] :as opts}]
  (cond
    (not (contains? #{:trace :epoch :fx :error} topic))
    {:ok? false :reason :unknown-topic
     :hint "Recognised topics: :trace :epoch :fx :error"
     :given topic}

    :else
    (let [sub-id (str (random-uuid))
          compiled (when (#{:trace :fx :error} topic)
                     (compose-trace-filter topic filter))
          sub {:id            sub-id
               :topic         topic
               :filter        (or filter {})
               :compiled-filter compiled
               :queue         []
               :overflow      0
               :created-at    (js/Date.now)
               :max-buffered  (or max-buffered default-max-buffered)}]
      ;; Make sure the upgraded listeners are wired (idempotent — same
      ;; id, replaces the basic listeners installed by `health`).
      (rf/register-trace-cb! :re-frame-pair2 on-trace-streaming)
      (rf/register-epoch-cb! :re-frame-pair2-epoch on-epoch-streaming)
      (swap! subscriptions assoc sub-id sub)
      {:ok? true :sub-id sub-id :topic topic :filter (:filter sub)})))

(defn unsubscribe!
  "Drop subscription `sub-id`. Returns `{:ok? true :sub-id ...}` even
   if the id was unknown — callers (the MCP server's poll loop) want
   idempotent close."
  [sub-id]
  (let [existed? (contains? @subscriptions sub-id)]
    (swap! subscriptions dissoc sub-id)
    {:ok? true :sub-id sub-id :existed? existed?}))

(defn drain-subscription!
  "Pop every queued event for `sub-id` and return them in order.
   Returns `{:ok? true :sub-id ... :events [...] :overflow <n> :gone? bool}`.
   If the subscription doesn't exist (already unsubscribed or runtime
   was reloaded), `:gone? true`."
  [sub-id]
  (let [snap (atom nil)]
    (swap! subscriptions
           (fn [m]
             (if-let [sub (get m sub-id)]
               (do (reset! snap {:events (:queue sub)
                                 :overflow (:overflow sub)})
                   (assoc m sub-id (-> sub
                                       (assoc :queue [])
                                       (assoc :overflow 0))))
               (do (reset! snap nil) m))))
    (if-let [{:keys [events overflow]} @snap]
      {:ok? true :sub-id sub-id :events events :overflow (or overflow 0) :gone? false}
      {:ok? true :sub-id sub-id :events [] :overflow 0 :gone? true})))

(defn subscription-info
  "Return active subscription metadata — handy for diagnostics. Returns
   `{:ok? true :subs [{:id :topic :filter :queue-depth :overflow :created-at}]}`.
   Does not drain."
  []
  {:ok? true
   :subs (mapv (fn [[sub-id sub]]
                 {:id        sub-id
                  :topic     (:topic sub)
                  :filter    (:filter sub)
                  :queue-depth (count (:queue sub))
                  :overflow  (:overflow sub)
                  :created-at (:created-at sub)})
               @subscriptions)})

;; ---------------------------------------------------------------------------
;; Dispatch correlation (Spec 009 §Dispatch correlation)
;; ---------------------------------------------------------------------------

(defn cascade-of
  "Reconstruct the cascade tree from a root `:dispatch-id` by walking
   `:event/dispatched` traces in the buffer for matching :parent links.
   Returns a tree of {:dispatch-id <id> :event <ev> :children [...]}.

   Note: this walks the in-memory trace buffer (default depth 200 events
   per `(rf/configure :trace-buffer {:depth N})`). For long cascades use
   `(rf/configure :trace-buffer {:depth ...})` to widen the window."
  [root-dispatch-id]
  (let [events     (rf/trace-buffer {:operation :event/dispatched})
        by-parent  (group-by #(get-in % [:tags :parent-dispatch-id]) events)
        node       (fn node [did]
                     (let [ev (some (fn [e] (when (= did (get-in e [:tags :dispatch-id])) e))
                                    events)]
                       {:dispatch-id did
                        :event       (get-in ev [:tags :event])
                        :origin      (get-in ev [:tags :origin])
                        :children    (mapv #(node (get-in % [:tags :dispatch-id]))
                                           (get by-parent did []))}))]
    (node root-dispatch-id)))

;; ---------------------------------------------------------------------------
;; Pair-tagged dispatch
;; ---------------------------------------------------------------------------
;;
;; Per Spec 002 §Dispatch origin tagging, dispatches carry an :origin opt
;; (default :app). The skill stamps :pair so `:event/dispatched` traces
;; can be filtered by who fired them. Pair-epoch tracking populates
;; `pair-epoch-ids` from the assembled-epoch listener.

(defn- mark-pair! [epoch-id]
  (when epoch-id (swap! pair-epoch-ids conj epoch-id)))

(defn pair-dispatch!
  "Queued dispatch with `:origin :pair`. Returns
   `{:ok? true :queued? true :event ...}`. The epoch-id appears once
   the cascade settles; callers can read it via `last-pair-epoch`."
  ([event-v] (pair-dispatch! event-v {}))
  ([event-v opts]
   (rf/dispatch event-v (merge {:origin :pair} opts))
   {:ok? true :queued? true :event event-v :opts opts}))

(defn pair-dispatch-sync!
  "Synchronous dispatch with `:origin :pair`. Reads the operating
   frame's epoch-history before and after; the new head is reported
   as the pair-attributed epoch.

   On real success returns {:ok? true :epoch-id <id> :event ...}.
   When epoch-history depth is 0 (recording disabled) or the frame
   isn't registered, reports the failure mode rather than claiming
   success."
  ([event-v] (pair-dispatch-sync! event-v {}))
  ([event-v opts]
   (let [frame-id  (or (:frame opts) (current-frame))
         _         (when-not frame-id
                     (throw (ex-info "ambiguous frame" {:reason :ambiguous-frame})))
         before-id (some-> (rf/epoch-history frame-id) peek :epoch-id)]
     (rf/dispatch-sync event-v (merge {:origin :pair} opts))
     (let [after-id (some-> (rf/epoch-history frame-id) peek :epoch-id)]
       (cond
         (and after-id (not= before-id after-id))
         (do (mark-pair! after-id)
             {:ok? true :epoch-id after-id :event event-v :frame frame-id})

         (and (nil? before-id) (nil? after-id))
         {:ok? false
          :reason :no-epoch-recorded
          :event event-v
          :frame frame-id
          :hint (str "epoch-history is empty after dispatch. Either depth "
                     "is 0 (disabled), the frame is destroyed, or "
                     "interop/debug-enabled? is false (production build).")}

         :else
         {:ok? false
          :reason :no-new-epoch
          :event event-v
          :frame frame-id
          :hint "dispatch-sync returned, but epoch-history head did not advance."})))))

;; ---------------------------------------------------------------------------
;; Effect stubs (per-call :fx-overrides)
;; ---------------------------------------------------------------------------

(defn pair-dispatch-with-fx-overrides!
  "Dispatch with a Spec 002 §Per-frame and per-call overrides
   `:fx-overrides` map. `overrides` is `{fx-id stub-id ...}` where
   `stub-id` is a separately-registered `reg-fx`. Each stub redirects
   for this dispatch only."
  [event-v overrides & {:keys [sync? frame]}]
  (let [opts {:origin :pair :fx-overrides overrides :frame frame}]
    (if sync?
      (pair-dispatch-sync! event-v opts)
      (pair-dispatch! event-v opts))))

;; ---------------------------------------------------------------------------
;; Time-travel — first-class via re-frame2
;; ---------------------------------------------------------------------------

(defn restore-epoch
  "(rf/restore-epoch frame-id epoch-id). Returns true on success, false
   on any failure mode. Failure traces fire under :rf.epoch/* — read
   them with `(rf/trace-buffer {:op-type :error})`."
  ([epoch-id] (restore-epoch epoch-id (current-frame)))
  ([epoch-id frame-id]
   (rf/restore-epoch frame-id epoch-id)))

(defn undo-step-back
  "Restore the previous epoch in the operating frame. Returns
   {:ok? true :epoch-id <previous> :restored? true|false} or
   {:ok? false :reason :no-prior-epoch} when there is no previous
   record."
  ([] (undo-step-back (current-frame)))
  ([frame-id]
   (let [history (vec (rf/epoch-history frame-id))
         n       (count history)]
     (if (< n 2)
       {:ok? false :reason :no-prior-epoch :history-size n :frame frame-id}
       (let [prior (nth history (- n 2))
             epoch-id (:epoch-id prior)
             ok?   (rf/restore-epoch frame-id epoch-id)]
         {:ok? true :epoch-id epoch-id :restored? ok? :frame frame-id})))))

(defn undo-to-epoch
  "Restore a specific epoch by id."
  ([epoch-id] (undo-to-epoch epoch-id (current-frame)))
  ([epoch-id frame-id]
   {:ok? true
    :epoch-id epoch-id
    :restored? (rf/restore-epoch frame-id epoch-id)
    :frame frame-id}))

;; ---------------------------------------------------------------------------
;; DOM ↔ source bridge
;; ---------------------------------------------------------------------------
;;
;; Two attribute sources, in priority order:
;;   1. data-rf2-source-coord (re-frame2's own annotation when
;;      :annotate-dom? is on — Tool-Pair §Source-mapping;
;;      Spec 006 §Source-coord annotation, rf2-z7f7)
;;   2. data-rc-src (re-com's debug attribute, fallback)
;;
;; The two attributes resolve to different schemas — re-frame2's
;; carries the registry-id derived <ns>/<handler-id> with <line>/<col>;
;; re-com's carries <file>/<line>/<column>. The runtime returns
;; whichever map the first present attribute parses to.

(defn- parse-rf2-coord
  "Parse re-frame2's `data-rf2-source-coord` attribute.

   Per Spec 006 §Source-coord annotation (rf2-z7f7) and Tool-Pair
   §Source-mapping the attribute value is a four-segment colon-
   separated string:

       <ns>:<handler-id>:<line>:<col>

   where <ns> and <handler-id> derive from the registry id keyword
   (`(namespace id)` and `(name id)`) and <line>/<col> are the
   captured source coords from the reg-view macro. Either coord
   segment may be the literal `?` for programmatic registrations
   that bypassed the macro path (Spec 006 §Attribute value format).

   Returns
       {:ns          <string>
        :handler-id  <string>
        :line        <int|nil>
        :col         <int|nil>}

   - :line and :col are nil when the corresponding segment is `?`
     or otherwise non-numeric.
   - Returns nil for blank input, non-strings, or fewer than four
     segments. Pair-shaped consumers fall back to (rf/handler-meta
     :view id) for those cases (Spec 006 §Documented exemption).

   Tool-Pair.md declares the value format opaque to consumers; this
   parser exists so pair2's DOM-to-source bridge can be useful, but
   downstream callers MUST NOT depend on the parsed shape's
   stability across re-frame2 versions."
  [attr-val]
  (when (and (string? attr-val) (seq attr-val))
    (let [parts (str/split attr-val #":")]
      (when (= 4 (count parts))
        (let [[ns-part sym-part line-part col-part] parts
              parse-int (fn [s]
                          (when (and (string? s) (re-matches #"\d+" s))
                            (js/parseInt s 10)))]
          (when (and (seq ns-part) (seq sym-part))
            {:ns         ns-part
             :handler-id sym-part
             :line       (parse-int line-part)
             :col        (parse-int col-part)}))))))

(defn- parse-rc-src
  "Parse re-com's `data-rc-src` attribute into {:file :line :column}.
   Expected shapes: 'app/cart.cljs:42', 'app/cart.cljs:42:8'."
  [attr-val]
  (when (and (string? attr-val) (seq attr-val))
    (let [parts (str/split attr-val #":")
          valid-line? (fn [s] (and s (re-matches #"\d+" s)))]
      (when (and (>= (count parts) 2) (valid-line? (nth parts 1 nil)))
        {:file   (first parts)
         :line   (js/parseInt (nth parts 1) 10)
         :column (when (and (>= (count parts) 3) (valid-line? (nth parts 2 nil)))
                   (js/parseInt (nth parts 2) 10))}))))

(defn- read-coord-from-element
  "Try data-rf2-source-coord first, then data-rc-src. Returns the
   parsed map or nil."
  [el]
  (or (some-> (.getAttribute el "data-rf2-source-coord") parse-rf2-coord)
      (some-> (.getAttribute el "data-rc-src") parse-rc-src)))

(defn coord-annotation-enabled?
  "Heuristic: at least one element on the page carries either
   `data-rf2-source-coord` or `data-rc-src`. False when neither
   annotation source is producing attributes (re-frame2's
   :annotate-dom? off and no re-com :src (at) call sites). Reads
   may be unreliable on a freshly-loaded page that hasn't rendered."
  []
  (or (some? (.querySelector js/document "[data-rf2-source-coord]"))
      (some? (.querySelector js/document "[data-rc-src]"))))

;; Last-clicked capture — passive listener that records the element
;; most recently clicked anywhere on the page. Installed once by
;; `install-last-click-capture!` during injection so ops like
;; `dom/source-at :last-clicked` have something to resolve.

(defonce ^:private last-clicked (atom nil))

(defn install-last-click-capture!
  "Install a single capturing click listener on document that records
   the most recently clicked element. Idempotent — calling twice does
   not double-register (guard via a marker on window)."
  []
  (when-not (aget js/window "__rfp2_click_capture__")
    (aset js/window "__rfp2_click_capture__" true)
    (.addEventListener
     js/document
     "click"
     (fn [e] (reset! last-clicked (.-target e)))
     #js {:capture true :passive true})))

(defn last-clicked-element
  "Return the DOM element most recently clicked, or nil if nothing has
   been clicked yet this session."
  []
  @last-clicked)

(defn- selector-or-last-clicked [selector]
  (cond
    (or (= selector :last-clicked) (= selector "last-clicked"))
    (last-clicked-element)

    (string? selector)
    (.querySelector js/document selector)

    :else nil))

(defn dom-source-at
  "Given a CSS selector (or `:last-clicked`), return the source coord
   attached by re-frame2's annotation or re-com's debug path. Shape
   depends on which attribute matched:
     - re-frame2: {:ns :handler-id :line :col}  (Spec 006 §Source-coord
       annotation)
     - re-com:    {:file :line :column}
   Returns a structured result wrapping the coord under :src."
  [selector]
  (if-let [el (selector-or-last-clicked selector)]
    (if-let [coord (read-coord-from-element el)]
      {:ok? true :src coord :selector selector}
      {:ok? true :src nil :selector selector
       :reason (if (coord-annotation-enabled?)
                 :no-coord-at-this-element
                 :source-coord-annotation-disabled)})
    {:ok? false :reason :no-element :selector selector
     :hint (when (or (= selector :last-clicked) (= selector "last-clicked"))
             "Nothing clicked this session; interact with the page first, or pass a CSS selector instead.")}))

(defn dom-find-by-src
  "Find live DOM elements whose source-coord attributes mention the
   needle `<file-or-id>:<line>`. Searches both `data-rf2-source-coord`
   and `data-rc-src`.

   For `data-rc-src` the needle pairs the source file and line.
   For `data-rf2-source-coord` (whose value is `<ns>:<handler-id>:<line>:<col>`,
   per Spec 006 §Source-coord annotation) callers typically pass the
   handler-id as the first argument so the substring match hits the
   handler-id segment. Pair-shaped consumers wanting <ns>-scoped
   queries should construct their own selector."
  [file-or-id line]
  (let [needle (str file-or-id ":" line)
        nodes  (.querySelectorAll js/document
                                  (str "[data-rf2-source-coord*='" needle "'],"
                                       "[data-rc-src*='" needle "']"))]
    (->> (array-seq nodes)
         (mapv (fn [node]
                 {:tag   (.toLowerCase (.-tagName node))
                  :id    (not-empty (.-id node))
                  :class (not-empty (.-className node))
                  :src   (read-coord-from-element node)})))))

(defn dom-fire-click
  "Synthesise a click on the element whose source-coord attribute
   contains the substring `<file-or-id>:<line>`. Picks the first
   match if multiple. Returns {:ok? true :clicked {...}} — the
   resulting epoch lands asynchronously, fetch it with `last-epoch`.
   See `dom-find-by-src` for the needle semantics."
  [file-or-id line]
  (let [needle   (str file-or-id ":" line)
        selector (str "[data-rf2-source-coord*='" needle "'],"
                      "[data-rc-src*='" needle "']")
        el       (.querySelector js/document selector)]
    (if el
      (let [ev (js/Event. "click" #js {:bubbles true :cancelable true})]
        (.dispatchEvent el ev)
        {:ok?     true
         :clicked {:tag (.toLowerCase (.-tagName el))
                   :id  (not-empty (.-id el))}})
      {:ok? false :reason :no-element-at-src :file-or-id file-or-id :line line})))

(defn dom-describe
  "Summarise a DOM element."
  [selector]
  (if-let [el (.querySelector js/document selector)]
    {:ok?      true
     :tag      (.toLowerCase (.-tagName el))
     :id       (not-empty (.-id el))
     :class    (not-empty (.-className el))
     :src      (read-coord-from-element el)
     :rf2-src  (some-> (.getAttribute el "data-rf2-source-coord") parse-rf2-coord)
     :rc-src   (some-> (.getAttribute el "data-rc-src") parse-rc-src)
     :text     (let [t (.-textContent el)]
                 (when (and t (< (count t) 200)) t))}
    {:ok? false :reason :no-element :selector selector}))

;; ---------------------------------------------------------------------------
;; Watch predicate matching
;; ---------------------------------------------------------------------------
;;
;; Translated for re-frame2's :rf/epoch-record shape — :event-id and
;; :trigger-event are top-level slots; :sub-runs / :renders / :effects
;; are pre-projected; the trace-events vector carries everything else.

(defn epoch-matches?
  "Test an epoch record against a predicate map built from
   `watch-epochs.sh` CLI args.

   Recognised keys: :event-id, :event-id-prefix, :effects (matches
   :fx-id in the projection), :touches-path (anywhere in db-before /
   db-after), :sub-ran (matches :sub-id or first of :query-v),
   :render (matches :render-key as a string), :origin (matches
   :origin in :event/dispatched trace events), :frame.

   Prefix matching uses `str` on both sides so `:cart` matches
   `:cart/apply-coupon`."
  [pred {:keys [event-id trigger-event sub-runs renders effects
                trace-events frame db-before db-after] :as epoch}]
  (let [{p-eid    :event-id
         p-prefix :event-id-prefix
         p-fx     :effects
         p-path   :touches-path
         p-sub    :sub-ran
         p-render :render
         p-origin :origin
         p-frame  :frame} pred]
    (boolean
     (and
      (if p-eid    (= p-eid event-id) true)
      (if p-prefix (some-> event-id str (str/starts-with? (str p-prefix))) true)
      (if p-fx     (some #(= p-fx (:fx-id %)) effects) true)
      (if p-path   (or (some? (get-in db-before p-path))
                       (some? (get-in db-after p-path)))
                   true)
      (if p-sub    (some #(or (= p-sub (:sub-id %))
                              (= p-sub (first (:query-v %))))
                         sub-runs) true)
      (if p-render (some #(= p-render (str (:render-key %))) renders) true)
      (if p-origin (some (fn [t] (and (= :event/dispatched (:operation t))
                                      (= p-origin (get-in t [:tags :origin]))))
                         trace-events)
                   true)
      (if p-frame  (= p-frame frame) true)))))

;; ---------------------------------------------------------------------------
;; Dispatch-and-collect
;; ---------------------------------------------------------------------------

(defn dispatch-and-collect
  "Synchronously dispatch (origin :pair) and return the resulting
   epoch record. Drain-settle is synchronous in re-frame2's
   `dispatch-sync`, so the new epoch is in the frame's history by the
   time this call returns."
  ([event-v] (dispatch-and-collect event-v {}))
  ([event-v opts]
   (let [result (pair-dispatch-sync! event-v opts)]
     (if (:ok? result)
       (assoc result :epoch (epoch-by-id (:epoch-id result)
                                         (:frame result)))
       result))))

;; ---------------------------------------------------------------------------
;; Coarse-grained snapshot — one round-trip per investigate-X workflow.
;; ---------------------------------------------------------------------------
;;
;; Many investigate-X tasks chain 5-10 reads — each currently a fresh nREPL
;; round-trip plus Claude-think latency. The runtime-side composer below
;; assembles every per-frame slice (:app-db, :sub-cache, :machines, :epochs,
;; :traces) in one CLJS eval, so the MCP `snapshot` op is a single bencode
;; round-trip producing one map keyed by frame-id.
;;
;; Each slice delegates to the existing per-slice reader — no parallel
;; reimplementation. The composer just routes by `:include` keys.
;;
;; Wire-boundary wrapping (rf2-tygdv). `snapshot-state` here returns the
;; full slice values; the MCP server's `snapshot` tool then wraps the
;; `:app-db` slice with path-slicing + lazy-summary before crossing the
;; wire. The default mode at the wire is `:summary` — the slice value
;; is replaced with a `{:rf.mcp/summary {:type :map :keys [...] :count
;; ... :bytes ~...}}` marker carrying the top-level shape only. Callers
;; pass `:path [...]` to receive `(get-in db path)` (`:mode
;; :path-sliced`); root `:path []` opts back into the full slice
;; (equivalent to the legacy default). Out-of-range paths surface
;; per-frame in a `:path-not-found` map with `:deepest-valid-prefix`
;; so the agent can re-aim. The `get-path` tool exposes the same
;; targeted-read primitive directly — `:exists?` distinguishes a path
;; that legitimately points at `nil` from one that doesn't resolve.

(def ^:private all-snapshot-slices
  [:app-db :sub-cache :machines :epochs :traces])

(defn- snapshot-frame-slice
  "Compute one slice for one frame-id. Delegates to the existing per-slice
   readers."
  [frame-id slice]
  (case slice
    :app-db     (rf/get-frame-db frame-id)
    :sub-cache  (rf/sub-cache frame-id)
    ;; The global machine-id list is registrar-level (not per-frame).
    ;; Per Spec 005 each frame holds its own machine snapshots at
    ;; [:rf/machines machine-id] in app-db, so the per-frame slice
    ;; returns {:ids [...] :state {machine-id snapshot}}.
    :machines   (let [ids (vec (rf/machines))
                      state (or (get (rf/get-frame-db frame-id) :rf/machines)
                                {})]
                  {:ids ids :state state})
    :epochs     (vec (rf/epoch-history frame-id))
    ;; The retain-N trace ring buffer is global; filter by frame.
    :traces     (vec (rf/trace-buffer {:frame frame-id}))
    nil))

(defn- snapshot-frame
  "Assemble the requested slices for one frame-id."
  [frame-id slices]
  (reduce (fn [m slice]
            (assoc m slice (snapshot-frame-slice frame-id slice)))
          {}
          slices))

(defn snapshot-state
  "Coarse-grained per-frame state read. Returns one map keyed by
   frame-id whose values are slice maps.

   Opts:
     :frames    :all (default) or a vector of frame-ids.
     :include   vector of slice keywords. Defaults to
                [:app-db :sub-cache :machines :epochs :traces].
                Pass a subset to skip slices.

   Example return shape:

     {:rf/default {:app-db {...}
                   :sub-cache {[:cart/total] {:value 42 :ref-count 2}}
                   :machines {:ids [:auth] :state {:auth {...}}}
                   :epochs [{...}{...}]
                   :traces [{...}{...}]}
      :stories    {...}}

   Routes through existing per-slice readers — no parallel implementation.
   Side effects: installs the trace + epoch listeners (idempotent via
   `health`).

   Note: this runtime-side form returns the *full* `:app-db` slice for
   every frame. The MCP `snapshot` tool wraps this output with
   path-slicing + lazy-summary at the wire boundary (rf2-tygdv) — see
   the section header above for the modes. The wrapping is wire-side
   only; direct callers of this CLJS form see slices verbatim."
  ([] (snapshot-state {}))
  ([{:keys [frames include]
     :or   {frames  :all
            include all-snapshot-slices}}]
   (install-last-click-capture!)
   (ensure-trace-listener!)
   (ensure-epoch-listener!)
   (let [registered (vec (rf/frame-ids))
         fids       (cond
                      (= :all frames)  registered
                      (sequential? frames) (vec frames)
                      :else            registered)
         slices     (vec include)]
     (reduce (fn [m fid]
               (assoc m fid (snapshot-frame fid slices)))
             {}
             fids))))

;; ---------------------------------------------------------------------------
;; Health check
;; ---------------------------------------------------------------------------

(defn debug-enabled?
  "Probe `re-frame.interop/debug-enabled?`. False in production builds;
   trace and epoch surfaces elide entirely when this is false."
  []
  (boolean interop/debug-enabled?))

(defn health
  "One-call summary of the runtime's view of the world. Used by
   `discover-app.sh` to confirm the environment is healthy.

   Side effects: installs the last-click capture listener; registers
   the trace and epoch listeners. All idempotent."
  []
  (install-last-click-capture!)
  (ensure-trace-listener!)
  (ensure-epoch-listener!)
  (let [fids (rf/frame-ids)]
    {:ok?                       true
     :session-id                session-id
     :debug-enabled?            (debug-enabled?)
     :coord-annotation-enabled? (coord-annotation-enabled?)
     :last-click-capture?       true
     :frames                    (vec fids)
     :selected-frame            @selected-frame
     :operating-frame           (current-frame)
     :ambiguous-frame?          (and (> (count fids) 1)
                                     (nil? @selected-frame))
     :epoch-history-depth       (try
                                  (let [requiring (resolve 're-frame.epoch/current-config)]
                                    (when requiring (:depth (requiring))))
                                  (catch :default _ nil))
     :epoch-counts              (into {} (map (fn [fid]
                                                [fid (count (rf/epoch-history fid))])
                                              fids))
     :pair-epoch-count          (count @pair-epoch-ids)}))
