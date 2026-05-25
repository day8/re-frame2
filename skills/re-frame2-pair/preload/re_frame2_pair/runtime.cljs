;;;; re-frame2-pair.runtime — re-frame2-pair helper namespace, preloaded into the app.
;;;;
;;;; This file is loaded into the consumer app via shadow-cljs's
;;;; `:devtools :preloads` mechanism. See `skills/re-frame2-pair/SKILL.md`
;;;; for the one-line setup; the MCP server's `discover-app` tool calls
;;;; `(re-frame2-pair.runtime/health)` and refuses with a structured
;;;; `:reason :runtime-not-preloaded` error pointing at the setup doc
;;;; when the namespace isn't present.
;;;;
;;;; Design invariants (see docs/initial-spec.md):
;;;;   - All trace and epoch reads consume re-frame2's public Tool-Pair
;;;;     surfaces (`re-frame.core/register-listener!`, `trace-buffer`,
;;;;     `register-epoch-listener!`, `epoch-history`, `restore-epoch`). No
;;;;     reaching into private namespaces.
;;;;   - Exactly one trace listener (`:re-frame2-pair`) and one epoch
;;;;     listener (`:re-frame2-pair-epoch`) are registered. Multi-tool
;;;;     coexistence is the expected default; per Spec 009 §Listener
;;;;     ordering, listener ordering is not contract.
;;;;   - Streaming subscriptions ride those same single
;;;;     listener slots — the listener fans matching events into per-
;;;;     subscription queues. `subscribe!` / `drain-subscription!` /
;;;;     `unsubscribe!` are the public surface the MCP server's
;;;;     `subscribe` op consumes.
;;;;   - The `session-id` sentinel below is read by the MCP server's
;;;;     preload probe. A mirror is also set on
;;;;     `js/globalThis.__re_frame2_pair_runtime` at load time so the
;;;;     probe can be a single bencode round-trip rather than a CLJS
;;;;     compile. A full page refresh wipes both — `discover-app`
;;;;     reports the missing preload with a structured setup hint.
;;;;
;;;; Naming surfaces — MCP vs runtime.
;;;;
;;;;   re-frame2-pair-mcp deliberately carries TWO vocabularies for the
;;;;   same logical surface: the MCP tool catalogue (operator-facing,
;;;;   disciplined to the cross-MCP NAMING.md `list-<things>` verb
;;;;   shape) and the runtime fn surface in THIS ns +
;;;;   `re-frame.core` (historical names, kept as-is). Rename pairs:
;;;;
;;;;     | MCP tool name           | Runtime fn name             |
;;;;     |-------------------------|-----------------------------|
;;;;     | `list-subscriptions`    | `subscription-info`         |
;;;;     | `list-handlers`         | `rf/registry-list`          |
;;;;
;;;;   An agent generating an eval form via `eval-cljs` uses the
;;;;   right-hand column; the same agent calling the MCP tool surface
;;;;   uses the left-hand column. The split is deliberate (the rename
;;;;   stops at the MCP boundary — the runtime's audience
;;;;   is smaller than the MCP surface, and a runtime rename ripples
;;;;   into every eval-form caller). This paragraph documents the
;;;;   asymmetry so it's discoverable rather than inherited-from-
;;;;   history; see `spec/Principles.md` §"Tool verbs follow the
;;;;   cross-MCP convention" for the policy rationale.

(ns re-frame2-pair.runtime
  (:require [re-frame.core :as rf]
            ;; sub-cache-snapshot lives in re-frame.subs.tooling
            ;; (production-DCE split). re-frame2-pair is dev-tier — loading the
            ;; tooling sibling here is bundle-isolation-safe (the
            ;; preload is dev-only).
            [re-frame.subs.tooling :as subs-tooling]
            ;; register-listener! / trace-buffer (and the rest of
            ;; the listener + ring-buffer surface) live in
            ;; re-frame.trace.tooling, not re-frame.trace. CLJS deliberately
            ;; omits `rf/<name>` aliases for these so production counter
            ;; bundles DCE the tooling sibling wholesale; this preload is
            ;; dev-only, so requiring the tooling ns directly here is
            ;; bundle-isolation-safe.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.interop :as interop]
            [clojure.data :as data]
            [clojure.set :as set]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Session sentinel
;; ---------------------------------------------------------------------------
;;
;; A random UUID set once per preload. The MCP server probes either
;; `re-frame2-pair.runtime/session-id` (CLJS var) or its mirror at
;; `js/globalThis.__re_frame2_pair_runtime` (cheaper, no compile) to
;; confirm this namespace landed in the running browser session.

(def session-id
  (str (random-uuid)))

;; Globally-visible mirror — the preload probe in
;; `re-frame2-pair-mcp.tools/ensure-runtime!` reads this rather than
;; resolving the CLJS var, so the probe is one bencode round-trip on
;; the persistent socket. Set at namespace-load time; cleared by a
;; full page refresh (along with everything else). The marker uses
;; `js-obj` (not the `#js` reader literal) so the bb-runnable
;; structural tests under `tests/runtime/` keep reading the rest of
;; the file — bb's reader rejects `#js` at top level.
(defonce ^:private install-global-sentinel!
  (do (when (exists? js/globalThis)
        (aset js/globalThis "__re_frame2_pair_runtime"
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
  "Flat metadata map for frame `id` — `(rf/frame-meta id)`. Returns `:id`,
   `:created-at`, the preset-expansion keys (`:preset`, `:fx-overrides`,
   `:drain-depth`, …) and lifecycle fields (`:destroyed?`, `:listeners`)
   all at the top level. See `:rf/frame-meta` in Spec-Schemas."
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

;; ---------------------------------------------------------------------------
;; Raw-state posture
;; ---------------------------------------------------------------------------
;;
;; The MCP server (`tools/re-frame2-pair-mcp/`) carries a
;; `--allow-sensitive-reads` boot gate (default OFF; CLI flag name
;; aligned across MCP servers). The internal Clojure keyword
;; `:allow-raw-state?` below is the implementation-side identifier and
;; retains the legacy name. When OFF, the runtime MUST default-elide
;; any verbatim app-db value before emitting it through `tap>` —
;; otherwise an `app-db-reset!` log entry would surface the same raw
;; payload that the wire path already redacts.
;;
;; The MCP server signals the runtime once per build per server lifetime
;; via `(configure-raw-state! {:allow-raw-state? bool})`. The flag is
;; consulted by `app-db-reset!` (and any future raw-state tap site)
;; before deciding whether to ship verbatim payloads or run them through
;; `re-frame.core/elide-wire-value`.
;;
;; Default OFF — a runtime loaded into an app without a re-frame2-pair-mcp server
;; sees the gate as "raw allowed", which preserves the original behaviour
;; for direct CLJS callers (developer at the REPL invoking
;; `app-db-reset!`). The re-frame2-pair-mcp server flips it on first tool use to
;; "raw gated" when its own boot flag is OFF.

(defonce ^:private raw-state-config
  ;; {:allow-raw-state? bool}
  ;; Default true — raw `tap>` payloads ride unmodified UNTIL the MCP
  ;; server signals otherwise. A bare CLJS REPL session (no re-frame2-pair-mcp
  ;; attached) sees the legacy verbatim behaviour.
  (atom {:allow-raw-state? true}))

(defn configure-raw-state!
  "Set the raw-state posture for tap>-emitting surfaces. Opts:

     :allow-raw-state?  boolean — when true (default), `app-db-reset!`
                        taps verbatim pre- and post-reset app-db
                        values. When false, the values are walked
                        through `re-frame.core/elide-wire-value` so
                        large / sensitive slots redact before any tap
                        consumer sees them.

   Returns the merged config map. Idempotent. Called by
   `re-frame2-pair-mcp.tools.raw-state/signal-runtime!` once per build
   per server lifetime; safe to call by hand from a REPL to flip the
   posture.

   Per Spec 009 §Privacy: re-frame2-pair-mcp's published-build default has the
   server's boot gate OFF, so the runtime ends up in
   `:allow-raw-state? false` mode the moment a state-emitting MCP tool
   first fires. Operators who passed `--allow-sensitive-reads` at server
   launch get `:allow-raw-state? true` instead."
  [{:keys [allow-raw-state?] :as opts}]
  (swap! raw-state-config merge (select-keys opts [:allow-raw-state?]))
  (assoc @raw-state-config :ok? true))

(defn raw-state-config-snapshot
  "Return the current raw-state config — diagnostic helper."
  []
  (assoc @raw-state-config :ok? true))

(defn- maybe-elide-for-tap
  "Walk `v` through `re-frame.core/elide-wire-value` when the raw-state
  gate is OFF, otherwise pass through. The walker substitutes large
  slots with the `:rf.size/large-elided` marker and sensitive slots
  with `:rf/redacted` — same redaction the wire path applies before
  emitting state over MCP, applied here BEFORE any registered tap
  consumer sees the payload.

  `frame-id` is supplied so the walker resolves the right
  `[:rf/elision]` registry."
  [v frame-id]
  (if (:allow-raw-state? @raw-state-config)
    v
    (rf/elide-wire-value v {:frame frame-id})))

(declare attach-cascade db-diff-summary machine-transitions-summary cascade-summary)

(defn app-db-reset!
  "Replace the operating frame's app-db with v. Logged explicitly via
   `tap>` so the human sees what the agent changed.

   Delegates to the canonical Tool-Pair write surface
   `(rf/reset-frame-db! frame-id v)` (Tool-Pair §Pair-tool writes).
   That surface bypasses the dispatch loop (no event, no
   cascade) but DOES record a synthetic `:rf/epoch-record` with
   `:event-id :rf.epoch/db-replaced` so that `restore-epoch` can
   rewind past the injection. Use sparingly — prefer `dispatch` for
   any change you want the data loop to see.

   The `tap>` emission default-elides both `:previous`
   and `:next` slots when the raw-state gate is OFF (the published-
   build default for re-frame2-pair-mcp). The wire walker's normal large- and
   sensitive- predicates apply; large slots collapse to
   `:rf.size/large-elided` markers, sensitive slots to `:rf/redacted`.
   Operators who passed `--allow-sensitive-reads` see verbatim payloads.

   Returns `{:ok? true :frame frame-id :cascade-summary {...}}` on
   success. The cascade-summary slot (rf2-6yqdl) projects the synthetic
   `:rf.epoch/db-replaced` epoch the framework just recorded — the
   `:event-id` is `:rf.epoch/db-replaced`, `:db-diff` summarises the
   before-vs-after delta at depth 1, and `:fx-fired` is empty (state
   injection bypasses fx).

   Failure modes (each is a no-op on app-db; corresponding
   `:rf.epoch/*` or `:rf.error/*` trace fires per Spec 009):
     :no-such-frame                — frame not registered
     :reset-frame-db-during-drain  — drain in flight
     :schema-mismatch              — v fails the frame's app-schema
     :epoch-artefact-missing       — re-frame2-epoch artefact not loaded"
  ([v] (app-db-reset! v (current-frame)))
  ([v frame-id]
   (tap> {:re-frame2-pair/op :app-db/reset
          :frame              frame-id
          :previous           (maybe-elide-for-tap (rf/get-frame-db frame-id) frame-id)
          :next               (maybe-elide-for-tap v frame-id)
          :t                  (js/Date.now)})
   (try
     (if (rf/reset-frame-db! frame-id v)
       ;; Per rf2-6yqdl: surface the synthetic `:rf.epoch/db-replaced`
       ;; epoch the framework just appended (Tool-Pair §Pair-tool writes).
       ;; The new head IS this epoch by construction; reading the
       ;; history head is the canonical way to project it.
       (let [head-id (some-> (rf/epoch-history frame-id) peek :epoch-id)]
         (attach-cascade {:ok? true :frame frame-id :epoch-id head-id}
                         frame-id head-id))
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
        :hint   "rf/reset-frame-db! returned false. Inspect (re-frame.trace.tooling/trace-buffer {:op-type :error}) and {:op-type :rf.epoch} for the structured reason — :rf.error/no-such-handler, :rf.epoch/reset-frame-db-during-drain, or :rf.epoch/reset-frame-db-schema-mismatch. (rf/trace-buffer is JVM-only; CLJS callers use the re-frame.trace.tooling ns.)"})
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
  "Enumerate registered ids under a kind. (rf/registrations kind) returns
   `{id meta}`; we return the sorted id vector."
  [kind]
  (-> (rf/registrations kind) keys sort vec))

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

   Augments with :handler-fn-hash for use as a probe over hot-reload.

   rf2-l7vnd: the :handler-fn slot carries a raw Function. `pr-str` of a
   Function emits `#object[Function ...]` — unreadable EDN on the MCP
   wire, which made the tool's read-back fall to a string and the
   handler-meta envelope misreport :unexpected-shape. The hash already
   covers every hot-reload probing use; the raw fn ref had no surviving
   on-the-wire consumer. Drop it before returning so the response is
   EDN-clean by construction."
  [kind id]
  (if-let [m (rf/handler-meta kind id)]
    (-> m
        (assoc :handler-fn-hash (handler-fn-hash m))
        (dissoc :handler-fn))
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
   (subs-tooling/sub-cache-snapshot frame-id)))

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
;; ours under id :re-frame2-pair-epoch — multi-tool coexistence per
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

;; ---- O(1) per-frame app-db hash cache ------------------------------------
;;
;; The re-frame2-pair-mcp precheck issues `(hash app-db)` to decide a
;; cache hit before running the tool eval. `(hash <persistent-map>)` is
;; cached on the map node itself in CLJS — the first call walks; every
;; subsequent call returns the cached integer in O(1) (per
;; `cljs.core/-hash` on `PersistentArrayMap` / `PersistentHashMap`). So
;; the wire saving from a precheck-side cache here is small for the
;; precheck hot path itself.
;;
;; What *is* expensive is the route through `(re-frame2-pair.runtime/
;; snapshot frame)` → `(rf/get-frame-db frame-id)` → dereferences and
;; map lookups, which the precheck eval form has to thread on every
;; call. With the per-frame cached integer here, the precheck form
;; resolves to a single atom deref + map lookup, completely independent
;; of app-db size or structure.
;;
;; The cache is updated whenever an epoch settles — every mutation path
;; (dispatch via the router, `rf/reset-frame-db!` synthetic `:rf.epoch/
;; db-replaced`, `rf/restore-epoch`) produces an assembled-epoch record
;; that arrives at `on-epoch-streaming`. We update the cache there from
;; `(:db-after record)`. On the first read for a frame, if the slot is
;; absent (no epoch has fired yet for this frame), we compute it lazily
;; from `(rf/get-frame-db frame-id)` and stash it.
;;
;; Pre-alpha: no back-compat surface to keep — `app-db-hash` is the
;; only accessor; callers needing a path-scoped hash hash the slice
;; themselves until a sub-tree accessor is filed.

(defonce ^:private frame-db-hashes
  ;; frame-id -> cached `(hash app-db)` integer
  (atom {}))

(defn- update-frame-db-hash!
  "Update the cached hash for `frame-id` from the epoch record's
   `:db-after` slot. Called from the epoch listener."
  [frame-id db-after]
  (swap! frame-db-hashes assoc frame-id (hash db-after)))

(defn app-db-hash
  "Cheap O(1) accessor for the current `(hash app-db)` of `frame-id`.

   Cached by the epoch listener at every settled mutation. On the first
   read for a frame whose hash hasn't been observed yet (no epoch
   fired since session start), the value is computed lazily from
   `(rf/get-frame-db frame-id)` and stashed.

   Returns an integer hash, or `nil` if the frame doesn't exist.

   The re-frame2-pair-mcp precheck form threads through this accessor
   so the cache-hit decision is a single integer compare rather than a
   full app-db walk."
  ([] (app-db-hash (current-frame)))
  ([frame-id]
   (when frame-id
     (or (get @frame-db-hashes frame-id)
         (let [db (rf/get-frame-db frame-id)
               h  (hash db)]
           (swap! frame-db-hashes assoc frame-id h)
           h)))))

;; The per-frame `observed-epochs` stash and the streaming dispatch both
;; ride the same `register-epoch-listener!` slot — combined into
;; `on-epoch-streaming` below to keep listener ordering deterministic.
;; The legacy single-purpose `on-epoch` was inlined into the
;; streaming listener.

(declare on-epoch-streaming)

(defn- ensure-epoch-listener!
  "Register the assembled-epoch listener if it isn't already. Idempotent —
   passing the same id twice replaces (per `register-epoch-listener!` contract).

   Installs the streaming-aware listener. The streaming
   dispatch is a no-op when no subscriptions are active, so this is
   safe to install unconditionally."
  []
  (rf/register-epoch-listener! :re-frame2-pair-epoch on-epoch-streaming))

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

(defn frame-diff
  "Cross-frame counterpart to `epoch-diff`: diff the current `app-db`
   of two frames. Use to compare two Story variants (variant-id IS the
   frame-id) or any two live frames. Returns the cross-frame vocabulary:
       {:only-in-a <map> :only-in-b <map> :common <map>}
   where A is `frame-id-a` and B is `frame-id-b`."
  [frame-id-a frame-id-b]
  (let [[a b c] (data/diff (rf/get-frame-db frame-id-a)
                           (rf/get-frame-db frame-id-b))]
    {:only-in-a a :only-in-b b :common c}))

;; ---------------------------------------------------------------------------
;; Trace stream listener (raw-trace, retain-N buffer is in framework)
;; ---------------------------------------------------------------------------
;;
;; The framework already maintains a retain-N ring buffer accessible via
;; `(re-frame.trace.tooling/trace-buffer opts)` (the `rf/` alias is
;; JVM-only). We register one listener here for callers
;; that want a programmatic side-channel (e.g. a watch loop's idle
;; detector); the buffer remains the canonical query surface.

(defonce ^:private last-trace-id (atom 0))

;; The legacy `last-trace-id` cursor and the streaming dispatch both
;; ride the same `register-listener!` slot — combined into
;; `on-trace-streaming` below. The legacy `on-trace` was
;; inlined into the streaming listener.

(declare on-trace-streaming)

(defn- ensure-trace-listener!
  "Register the raw-trace listener if it isn't already.

   Installs the streaming-aware listener. The streaming
   dispatch is a no-op when no subscriptions are active, so this is
   safe to install unconditionally — `last-trace-event-id` keeps
   working through it."
  []
  (trace-tooling/register-listener! :re-frame2-pair on-trace-streaming))

(defn last-trace-event-id
  "Last trace event id observed by the skill's listener. Useful as a
   `:since` cursor for `(re-frame.trace.tooling/trace-buffer {:since N})`."
  []
  @last-trace-id)

;; ---------------------------------------------------------------------------
;; Streaming subscriptions
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
;;    :queue-bytes <integer>         ;; running sum of (count (pr-str ev)) for queued events
;;    :dropped-events <integer>      ;; events evicted because EITHER budget tripped
;;    :dropped-bytes  <integer>      ;; bytes evicted alongside :dropped-events
;;    :overflow-reason :max-buffered-events | :max-buffered-bytes | nil
;;                                   ;; budget that tripped LAST — surfaced verbatim
;;                                   ;; so the AI client knows WHICH limit it should
;;                                   ;; tune. Reset at every drain alongside the
;;                                   ;; counters.
;;    :created-at <ms>
;;    :max-buffered-events <integer> ;; queue cap in events; default 500
;;    :max-buffered-bytes  <integer>} ;; queue cap in bytes; default 5_000_000 (~5 MB)
;;
;; Overflow policy (drop-oldest, byte+event budget):
;;   On enqueue, we first append the new event, then evict from the
;;   FRONT until BOTH budgets hold. Event-count overflow trips
;;   `:overflow-reason :max-buffered-events`; byte overflow trips
;;   `:overflow-reason :max-buffered-bytes`. When both trip on the
;;   same enqueue, the more-recently-tripped budget wins (typically
;;   bytes — a single fat event can put us over bytes while still
;;   inside events). Drop-oldest is the only sensible policy for a
;;   byte budget: a single fat newcomer can require evicting many
;;   small predecessors to fit, and there's no way to know that on
;;   entry without already having admitted it.
;;
;; Topic semantics:
;;   :trace     — every event in the raw trace stream matching `:filter`
;;                (filter map mirrors `(re-frame.trace.tooling/trace-buffer)` filter vocab —
;;                see the trace-buffer surface). Cascade-bundle delivery
;;                (rf2-mscih): per drain, matched events are grouped by
;;                `:rf.trace/dispatch-id` and projected into one cascade
;;                bundle per cascade (`group-cascades` shape with a
;;                `:trace-events` slot carrying the raw events). Events
;;                without a `:rf.trace/dispatch-id` tag (frameless
;;                registry / lifecycle emits) NEVER ride this topic —
;;                consumers wanting those subscribe to `:frameless`.
;;   :epoch     — every assembled `:rf/epoch-record` matching `:filter`
;;                (filter map mirrors `epoch-matches?` — see watch-epochs).
;;                One event per committed epoch. `:rf/epoch-record` is
;;                already a cascade-bundle by construction.
;;   :fx        — sugar for `:topic :trace :filter {:op-type :rf.fx}` with
;;                optional `:fx-id` and `:event-id` axes from the trace
;;                filter vocabulary. Cascade-bundle delivery as for :trace.
;;   :error     — sugar for `:topic :trace :filter {:op-type :error}`,
;;                with `:event-id`/`:handler-id`/`:source` available.
;;                Cascade-bundle delivery as for :trace.
;;   :frameless — every trace event matching `:filter` whose
;;                `:rf.trace/dispatch-id` tag is absent. Registration
;;                emits, REPL evals, lifecycle outside any cascade flow
;;                here per Spec 002 / Tool-Pair §Frameless trace events.
;;                Single-event delivery (no cascade to bundle).
;;
;; The :fx and :error topics compose with axes from the trace filter
;; vocabulary verbatim — they just default `:op-type` to `:rf.fx` /
;; `:error` and let callers override the rest.
;;
;; Cascade-bundle wire format (rf2-mscih) — emitted on `:trace`/`:fx`/
;; `:error` drain ticks:
;;
;;   {:dispatch-id        <id>                  ;; cascade id
;;    :frame              <frame-id or nil>
;;    :event              <event-vector or nil> ;; from :rf.event/dispatched :tags
;;    :dispatched         <trace-event or nil>  ;; the full :rf.event/dispatched event
;;    :handler            <trace-event or nil>  ;; the :rf.event/run-end emit
;;    :fx                 <trace-event or nil>  ;; :rf.fx/do-fx
;;    :effects            [<trace-event> ...]   ;; :op-type :rf.fx (other ops)
;;    :subs               [<trace-event> ...]   ;; :rf.sub/run + :rf.sub/skip + :rf.sub/create
;;    :renders            [<trace-event> ...]   ;; :rf.view/render
;;    :other              [<trace-event> ...]   ;; everything else
;;    :trace-events       [<trace-event> ...]   ;; raw events for the cascade
;;    :parent-dispatch-id <id or nil>}          ;; causal-parent link
;;
;; Mirrors the framework's `(rf/trace-buffer frame-id)` shape so an
;; agent that already pattern-matches on the in-process per-frame
;; trace-ring sees the same shape on the wire (per Spec 009 §Cascade
;; projection + Tool-Pair §Reading the per-frame trace ring).
;;
;; Cross-frame cascade reconstruction (rf2-mscih) — a cascade can fan
;; out across frames; every emit on every frame shares the same
;; `:rf.trace/dispatch-id`. Consumers merge by `:dispatch-id` across
;; per-frame bundles to reconstruct the cross-frame view (per Tool-Pair
;; §Cross-frame cascade reconstruction). The runtime emits one bundle
;; per (frame, dispatch-id) pair per drain — the merge is the
;; consumer's job, cheap because the key is already on each bundle.

(defonce ^:private subscriptions
  ;; sub-id -> subscription map (see above)
  (atom {}))

(def ^:private default-max-buffered-events 500)
(def ^:private default-max-buffered-bytes
  ;; ~5 MB — sized to match the 5,000-token wire-cap posture of the
  ;; MCP egress boundary scaled up by the per-tick batching ratio.
  ;; The streaming progress payload is metered per-tick, but the
  ;; underlying runtime queue is the upstream bound: it must be big
  ;; enough that a normal drain cadence (~100 ms server poll) drains
  ;; bursts before they back up, while small enough that an idle
  ;; subscription forgotten for hours doesn't accumulate hundreds of
  ;; megabytes of stale events.
  5000000)

;; ---------------------------------------------------------------------------
;; Privacy posture for the streaming surface
;; ---------------------------------------------------------------------------
;;
;; Per Spec 009 §Privacy / sensitive data: framework-published listener
;; integrations — including the re-frame2-pair server — MUST default-suppress
;; `:sensitive? true` trace events before forwarding to the AI surface.
;;
;; The trust boundary is "any trace data that leaves the browser tab and
;; reaches the LLM-facing channel". Streaming subscriptions are how that
;; happens in re-frame2-pair: the MCP server registers a subscription, polls
;; `drain-subscription!`, and forwards every drained event back to the
;; agent as a `notifications/progress` payload. The retain-N ring buffer
;; reached via `(re-frame.trace.tooling/trace-buffer)` is a separate, explicit read surface;
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
;; monotonically) and still ride `(re-frame.trace.tooling/trace-buffer)` unchanged — only
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
   default exists because re-frame2-pair forwards events to an LLM-facing
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
   `:error` are sugar over `:op-type`; `:trace`, `:epoch`, `:frameless`
   add no base constraint here (the user-supplied filter is the only
   constraint). `:frameless` is gated additionally by
   `frameless-event?` at dispatch time — the base filter doesn't model
   the dispatch-id-absence test."
  [topic]
  (case topic
    :fx    {:op-type :rf.fx}
    :error {:op-type :error}
    {}))

;; ---------------------------------------------------------------------------
;; Cascade-bundle projection (rf2-mscih)
;; ---------------------------------------------------------------------------
;;
;; Per Spec 009 §Cascade projection and Tool-Pair §Reading the per-frame
;; trace ring, the wire-delivery unit for the streaming subscribe is
;; the cascade bundle (one entry per `:rf.trace/dispatch-id`) rather
;; than the flat per-event slice. We mirror the framework's per-frame
;; trace-ring shape: `rf/group-cascades` projection PLUS a
;; `:trace-events` slot carrying the raw events.
;;
;; The bundling happens at drain time (not enqueue), so the per-event
;; queue's byte+event budget still works as the upstream bound — an
;; oversized cascade still evicts oldest events per the documented
;; policy. Each bundle's `:trace-events` slot carries ONLY the events
;; that survived eviction.
;;
;; Frameless events (`:rf.trace/dispatch-id` tag absent) NEVER ride
;; cascade-bundle topics — `dispatch-trace-to-subs!` filters them out
;; for those topics. The `:frameless` topic exists to deliver them
;; explicitly (Tool-Pair §Frameless trace events — live channel only).

(defn- frameless-event?
  "True when `ev` carries no `:rf.trace/dispatch-id` tag — a registration
   emit, REPL eval, or lifecycle event that never rode a dispatch
   cascade. The cascade-bundle topics filter these out; the `:frameless`
   topic accepts only these."
  [ev]
  (nil? (get-in ev [:tags :rf.trace/dispatch-id])))

(defn- cascade-bundle-events
  "Group a vector of raw trace events by `:rf.trace/dispatch-id` and
   project each group into a cascade-bundle map matching the framework's
   `(rf/trace-buffer frame-id)` shape — the `group-cascades` projection
   PLUS a `:trace-events` slot carrying the raw events for the cascade.
   The returned vector is sorted by emission order (lowest `:id` first,
   the order `rf/group-cascades` returns).

   Events whose `:rf.trace/dispatch-id` tag is missing are NOT included
   — the caller is expected to have filtered them upstream (cascade-
   bundle topics) or routed them to the frameless channel."
  [events]
  (let [;; rf/group-cascades returns a vector of cascade records sorted
        ;; by emission order, keyed by `:dispatch-id`. We then splice
        ;; the raw events per cascade into the `:trace-events` slot.
        cascades   (rf/group-cascades events)
        by-id      (group-by #(get-in % [:tags :rf.trace/dispatch-id])
                             events)]
    (->> cascades
         (remove #(= :ungrouped (:dispatch-id %)))
         (mapv (fn [c]
                 (assoc c :trace-events (vec (get by-id (:dispatch-id c) []))))))))

(defn- compose-trace-filter
  "Compose the topic's base trace-filter with the user-supplied filter.
   User keys win on conflict — the topic is a default, not a lock."
  [topic user-filter]
  (merge (topic->base-filter topic) (or user-filter {})))

(declare epoch-matches?) ;; resolved below

(defn- trace-matches?
  "Test a raw trace event against a filter map. Mirrors the filter
   vocabulary of `(re-frame.trace.tooling/trace-buffer opts)` — composes
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
                                    (get-in ev [:tags :rf.trace/event-id])))
           (or (nil? handler-id) (= handler-id
                                    (get-in ev [:tags :handler-id])))
           (or (nil? source)     (= source
                                    (or (:source ev)
                                        (get-in ev [:tags :source]))))
           (or (nil? origin)     (= origin
                                    (get-in ev [:tags :rf.event/origin])))
           (or (nil? dispatch-id)(= dispatch-id
                                    (get-in ev [:tags :rf.trace/dispatch-id])))
           (or (nil? since-ms)   (and (number? (:time ev))
                                      (> (:time ev) since-ms)))
           (or (nil? t0)         (and (number? (:time ev))
                                      (<= t0 (:time ev) t1)))))))

(defn- event-byte-size
  "Cheap, monotonic estimate of an event's on-wire byte cost. Uses the
   same `pr-str`-char-count discipline as the wire-cap helper in
   `tools.cljs` (`token-estimate`) — keeps the runtime queue's budget
   in the same units as the egress cap, so the two budgets stay
   coherent. `pr-str` failure (a reader-unfriendly value somehow on
   the bus) falls back to `0` rather than blowing up enqueue."
  [event]
  (try (count (pr-str event))
       (catch :default _ 0)))

(defn- evict-oldest
  "Drop events from the FRONT of `sub`'s queue until BOTH budgets
   hold (or the queue is empty). Returns the updated sub with the
   queue/byte-running-total trimmed and `:dropped-events` /
   `:dropped-bytes` / `:overflow-reason` updated. Drop-oldest is the
   only sensible policy for a byte budget — see the namespace docs
   above."
  [sub max-events max-bytes]
  (loop [q       (:queue sub)
         bytes   (:queue-bytes sub 0)
         dropped-n 0
         dropped-b 0
         reason    nil]
    (let [n (count q)
          over-events? (> n max-events)
          over-bytes?  (> bytes max-bytes)]
      (if (and (or over-events? over-bytes?)
               (pos? n))
        (let [head     (nth q 0)
              head-bs  (event-byte-size head)]
          (recur (subvec q 1)
                 (max 0 (- bytes head-bs))
                 (inc dropped-n)
                 (+ dropped-b head-bs)
                 ;; bytes wins ties — if both budgets trip on the
                 ;; same enqueue, the byte budget is the one the
                 ;; agent likely needs to know about. (Event-count
                 ;; alone tripping is the easy case; bytes tripping
                 ;; signals a large-payload storm.)
                 (cond over-bytes?  :max-buffered-bytes
                       over-events? :max-buffered-events
                       :else        reason)))
        (cond-> (assoc sub :queue q :queue-bytes bytes)
          (pos? dropped-n)
          (-> (update :dropped-events (fnil + 0) dropped-n)
              (update :dropped-bytes  (fnil + 0) dropped-b)
              (assoc :overflow-reason reason)))))))

(defn- enqueue!
  "Append an event to a subscription's queue, honouring the byte+event
   buffer budget. Drop-oldest semantics: we always admit
   the new event first, then evict from the FRONT until both budgets
   hold. A single fat newcomer can therefore evict an arbitrary
   number of small predecessors — that's correct: the byte budget is
   a hard upstream bound, and the agent draining the sub gets the
   most recent state of the world."
  [sub-state sub-id event]
  (update sub-state sub-id
          (fn [sub]
            (when sub
              (let [max-events (:max-buffered-events sub default-max-buffered-events)
                    max-bytes  (:max-buffered-bytes  sub default-max-buffered-bytes)
                    ev-bytes   (event-byte-size event)
                    sub'       (-> sub
                                   (update :queue       conj event)
                                   (update :queue-bytes (fnil + 0) ev-bytes))]
                (evict-oldest sub' max-events max-bytes))))))

(defn- dispatch-trace-to-subs!
  "Called from the raw-trace listener — iterates active subscriptions of
   trace-like topics, matches, enqueues. Cheap when no subs exist (the
   common path).

   rf2-mscih channel split:
   - Cascade-bundle topics (`:trace`/`:fx`/`:error`) receive events
     carrying a `:rf.trace/dispatch-id` tag. The bundling happens at
     drain time; the queue stays per-event for the byte+event budget.
   - The `:frameless` topic receives only events whose
     `:rf.trace/dispatch-id` tag is absent (registration / REPL /
     lifecycle emits outside any cascade)."
  [ev]
  (let [frameless? (frameless-event? ev)]
    (swap! subscriptions
           (fn [m]
             (reduce-kv
               (fn [acc sub-id sub]
                 (let [topic (:topic sub)
                       routes? (cond
                                 (contains? #{:trace :fx :error} topic)
                                 ;; Cascade-bundle topics — never deliver
                                 ;; frameless events; consumers wanting
                                 ;; those opt into the `:frameless` topic.
                                 (and (not frameless?)
                                      (trace-matches? (:compiled-filter sub) ev))

                                 (= :frameless topic)
                                 ;; The frameless channel — only frameless
                                 ;; events, then filter-matched.
                                 (and frameless?
                                      (trace-matches? (:compiled-filter sub) ev))

                                 :else
                                 false)]
                   (if routes?
                     (enqueue! acc sub-id ev)
                     acc)))
               m m)))))

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

   Privacy filter: trace events stamped `:sensitive? true`
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
  "Replacement assembled-epoch listener that drives the observed stash
   (legacy), the per-frame app-db-hash cache, and the
   streaming subs dispatch."
  [record]
  (let [frame-id (:frame record)]
    (swap! observed-epochs
           (fn [m]
             (let [v        (or (get m frame-id) [])
                   v+       (conj v record)
                   n        (count v+)]
               (assoc m frame-id (if (> n 500) (subvec v+ (- n 500)) v+)))))
    (when frame-id
      (update-frame-db-hash! frame-id (:db-after record))))
  (dispatch-epoch-to-subs! record))

(defn subscribe!
  "Open a streaming subscription on the trace or epoch bus. Returns
   `{:ok? true :sub-id <uuid>}`. Subsequent calls to
   `drain-subscription!` return queued events matching `:filter`.

   Opts:
     :topic   :trace | :epoch | :fx | :error | :frameless  (required)
     :filter  filter map — vocab depends on topic. See namespace docs.
     :max-buffered-events  cap on the in-runtime queue in events.
                           Default 500. When either budget trips, the
                           OLDEST events are evicted (drop-oldest FIFO).
     :max-buffered-bytes   cap on the in-runtime queue in pr-str bytes.
                           Default 5_000_000 (~5 MB). Same drop-oldest
                           policy. The two budgets are OR-combined:
                           whichever trips first evicts.

   Drop counts surface on `drain-subscription!` as `:dropped-events`
   and `:dropped-bytes`, with `:overflow-reason` carrying the budget
   keyword (`:max-buffered-events` or `:max-buffered-bytes`) that
   tripped LAST. The bookkeeping reset happens on drain — each tick
   reports the deltas since the previous tick.

   Idempotency: each call returns a fresh sub-id — repeated `subscribe!`
   calls do not share state. Use `unsubscribe!` to release.

   Topic delivery shape (rf2-mscih):
     :trace / :fx / :error — drain returns `:cascades [<bundle> ...]`
                             with each bundle in the `(rf/trace-buffer
                             frame-id)` shape (per Spec 009 §Cascade
                             projection). One bundle per cascade per
                             drain.
     :epoch                — drain returns `:events [<:rf/epoch-record>
                             ...]` unchanged.
     :frameless            — drain returns `:events [<trace-event>
                             ...]` for events with no
                             `:rf.trace/dispatch-id` tag (registration
                             emits, REPL evals, lifecycle outside any
                             cascade)."
  [{:keys [topic filter max-buffered-events max-buffered-bytes] :as opts}]
  (cond
    (not (contains? #{:trace :epoch :fx :error :frameless} topic))
    {:ok? false :reason :unknown-topic
     :hint "Recognised topics: :trace :epoch :fx :error :frameless"
     :given topic}

    :else
    (let [sub-id (str (random-uuid))
          compiled (when (#{:trace :fx :error :frameless} topic)
                     (compose-trace-filter topic filter))
          sub {:id              sub-id
               :topic           topic
               :filter          (or filter {})
               :compiled-filter compiled
               :queue           []
               :queue-bytes     0
               :dropped-events  0
               :dropped-bytes   0
               :overflow-reason nil
               :created-at      (js/Date.now)
               :max-buffered-events (or max-buffered-events
                                        default-max-buffered-events)
               :max-buffered-bytes  (or max-buffered-bytes
                                        default-max-buffered-bytes)}]
      ;; Make sure the upgraded listeners are wired (idempotent — same
      ;; id, replaces the basic listeners installed by `health`).
      (trace-tooling/register-listener! :re-frame2-pair on-trace-streaming)
      (rf/register-epoch-listener! :re-frame2-pair-epoch on-epoch-streaming)
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
   Returns one of two envelopes per the sub's topic (rf2-mscih):

   - Cascade-bundle topics (`:trace`/`:fx`/`:error`):
     `{:ok? true :sub-id ... :cascades [<bundle> ...] :dropped-events <n>
       :dropped-bytes <m> :overflow-reason <kw|nil> :gone? bool}`
     — queued raw events are grouped by `:rf.trace/dispatch-id` and
     projected into cascade bundles (`group-cascades` shape with a
     `:trace-events` slot) per Spec 009 §Cascade projection.

   - Flat topics (`:epoch`/`:frameless`):
     `{:ok? true :sub-id ... :events [...] :dropped-events <n>
       :dropped-bytes <m> :overflow-reason <kw|nil> :gone? bool}`
     — `:epoch` ships `:rf/epoch-record`s; `:frameless` ships raw
     trace events with no `:rf.trace/dispatch-id` tag.

   If the subscription doesn't exist (already unsubscribed or runtime
   was reloaded), `:gone? true` (envelope shape: `:events []`).

   The `:dropped-events`, `:dropped-bytes`, and `:overflow-reason`
   counters report what got EVICTED from the QUEUE between drains —
   they reset on every drain so the next tick reports the delta. AI
   clients pattern-match on `:overflow-reason` to know which budget
   tripped (`:max-buffered-events` or `:max-buffered-bytes`); the
   `:dropped-bytes` figure tells them how much state they missed.

   Note on cascade-bundle counters: `:dropped-events` counts the raw
   trace events evicted from the queue, NOT cascades. An evicted event
   may have left its sibling cascade-members in place — consumers
   reconstructing a cascade should be tolerant of partially-truncated
   bundles when `:dropped-events` is non-zero."
  [sub-id]
  (let [snap (atom nil)]
    (swap! subscriptions
           (fn [m]
             (if-let [sub (get m sub-id)]
               (do (reset! snap {:queue           (:queue sub)
                                 :topic           (:topic sub)
                                 :dropped-events  (:dropped-events sub 0)
                                 :dropped-bytes   (:dropped-bytes  sub 0)
                                 :overflow-reason (:overflow-reason sub)})
                   (assoc m sub-id (-> sub
                                       (assoc :queue [])
                                       (assoc :queue-bytes 0)
                                       (assoc :dropped-events 0)
                                       (assoc :dropped-bytes 0)
                                       (assoc :overflow-reason nil))))
               (do (reset! snap nil) m))))
    (if-let [{:keys [queue topic dropped-events dropped-bytes overflow-reason]} @snap]
      (let [base {:ok?             true
                  :sub-id          sub-id
                  :dropped-events  (or dropped-events 0)
                  :dropped-bytes   (or dropped-bytes  0)
                  :overflow-reason overflow-reason
                  :gone?           false}]
        (cond
          ;; Cascade-bundle delivery (rf2-mscih) — group raw queued
          ;; trace events by `:rf.trace/dispatch-id` into bundles. The
          ;; cascade-bundle topics never enqueue frameless events
          ;; (`dispatch-trace-to-subs!` filters them out at the gate),
          ;; so `cascade-bundle-events`'s `:ungrouped`-drop is purely
          ;; defensive.
          (contains? #{:trace :fx :error} topic)
          (assoc base :cascades (cascade-bundle-events queue))

          ;; Flat delivery — :epoch and :frameless.
          :else
          (assoc base :events queue)))
      {:ok? true :sub-id sub-id :events []
       :dropped-events 0
       :dropped-bytes  0
       :overflow-reason nil
       :gone? true})))

(defn subscription-info
  "Return active subscription metadata — handy for diagnostics. Returns
   `{:ok? true :subs [{:id :topic :filter :queue-depth :queue-bytes
                       :dropped-events :dropped-bytes :overflow-reason
                       :created-at}]}`. Does not drain."
  []
  {:ok? true
   :subs (mapv (fn [[sub-id sub]]
                 {:id              sub-id
                  :topic           (:topic sub)
                  :filter          (:filter sub)
                  :queue-depth     (count (:queue sub))
                  :queue-bytes     (:queue-bytes sub 0)
                  :dropped-events  (:dropped-events sub 0)
                  :dropped-bytes   (:dropped-bytes  sub 0)
                  :overflow-reason (:overflow-reason sub)
                  :created-at      (:created-at sub)})
               @subscriptions)})

;; ---------------------------------------------------------------------------
;; Dispatch correlation (Spec 009 §Dispatch correlation)
;; ---------------------------------------------------------------------------

(defn cascade-of
  "Reconstruct the cascade tree from a root `:dispatch-id` by walking
   the per-frame trace-ring cascade bundles for matching parent links.
   Returns a tree of `{:dispatch-id <id> :event <ev> :children [...]}`.

   Per rf2-g1b2m / rf2-8uwce the trace ring is per-frame and
   cascade-keyed; per rf2-mscih the read unit is the cascade bundle
   (`group-cascades` shape) rather than the legacy flat-event stream.
   A single cascade can fan out across frames (per Spec 002
   §Cross-frame dispatch) — every emit on every frame shares the same
   `:rf.trace/dispatch-id`, so cross-frame reconstruction iterates
   every registered frame's bundles and merges by `:dispatch-id`.
   Per-frame depth is configurable via
   `(rf/configure :trace-buffer {:cascades-retained N})`."
  [root-dispatch-id]
  (let [;; Per-frame rings, cascade-bundle reads — merge across
        ;; frames so cross-frame cascades reconstruct correctly. Each
        ;; bundle carries `:parent-dispatch-id` (the causal-parent
        ;; link) and `:dispatched :tags :rf.event/origin` directly,
        ;; so the tree walk reads them off the bundle slot rather
        ;; than re-deriving from raw trace events.
        bundles    (into []
                         (mapcat #(trace-tooling/trace-buffer %))
                         (rf/frame-ids))
        by-parent  (group-by :parent-dispatch-id bundles)
        node       (fn node [did]
                     (let [b (some (fn [bundle] (when (= did (:dispatch-id bundle)) bundle))
                                   bundles)]
                       {:dispatch-id did
                        :event       (:event b)
                        :origin      (get-in b [:dispatched :tags :rf.event/origin])
                        :children    (mapv #(node (:dispatch-id %))
                                           (get by-parent did []))}))]
    (node root-dispatch-id)))

(declare epoch-elapsed-ms)

;; ---------------------------------------------------------------------------
;; Cascade summary (rf2-6yqdl)
;; ---------------------------------------------------------------------------
;;
;; A compact projection of the framework's `:rf/epoch-record` that answers
;; the universal "what did my dispatch do?" question in one call. Surfaced
;; by `pair-dispatch!` / `pair-dispatch-sync!` / `dispatch-and-collect` /
;; `app-db-reset!` / `restore-epoch` / `dispatch-dry-run` whenever a new
;; epoch settled as a consequence of the call.
;;
;; The shape MIRRORS the assembled-epoch projection that
;; `register-epoch-listener!` consumers already know (Spec 009 §Epoch
;; records) — operators familiar with `watch-epochs` see the same
;; vocabulary in dispatch responses. The mirror is intentionally LOSSY
;; (counts instead of full vectors, db-diff path-only summary instead of
;; the raw `:db-before`/`:db-after` pair) so the cascade-summary rides
;; under the 5K-token wire-cap without further elision; an operator who
;; wants the full epoch reads `(rf/epoch-history)` or runs `trace-window`
;; / `watch-epochs` for the same id.
;;
;; Slot inventory (every slot is optional — absent on epochs that lack
;; that signal; e.g. synthetic `:rf.epoch/db-replaced` epochs have no
;; `:event-id`):
;;
;;   :epoch-id            — the assembled epoch's id (`:any` per Spec-
;;                          Schemas; integer in the reference runtime)
;;   :event-id            — the triggering event-id keyword (absent on
;;                          synthetic / halted-trigger-less paths)
;;   :event-vector        — the original dispatch vector (absent on the
;;                          same paths as :event-id)
;;   :frame               — the frame-id the cascade settled in
;;   :outcome             — the consumer-facing tier (`:ok` / `:blocked`
;;                          / `:error`, per `outcome->consumer-facing`)
;;   :db-diff             — {:changed-paths [...] :added-paths [...]
;;                           :removed-paths [...]} — top-level depth-1
;;                          summary computed from `:db-before` /
;;                          `:db-after`. NO raw values cross the wire
;;                          here; an operator inspects the addressed
;;                          subtree via `get-path` or `snapshot {:path
;;                          ...}` on demand.
;;   :fx-fired            — vector of distinct fx-ids that fired
;;                          (`(:fx-id %)` over the epoch's `:effects`
;;                          projection); duplicates collapsed
;;   :subs-recomputed     — count of unique sub-runs in this cascade
;;   :renders             — count of render emits in this cascade
;;   :machine-transitions — vector of `{:machine-id :from :to :phase}`
;;                          projections (absent on cascades without
;;                          machine activity)
;;   :elapsed-ms          — wall-clock elapsed-ms (`epoch-elapsed-ms`)
;;   :sensitive?          — the record's `:rf.epoch/sensitive?` rollup;
;;                          when true, consumers branch on the absent-
;;                          slot pattern in `:db-diff` (sensitive paths
;;                          are dropped from the projection by the
;;                          framework's redact-fn before we read it)
;;
;; Production builds elide the entire epoch-record path under
;; `interop/debug-enabled? false`; cascade-summary inherits that —
;; the projection is dev-only by construction. The :elision /
;; :sensitive-paths machinery upstream of us already ran by the time
;; we read :db-before / :db-after, so we never see raw sensitive values.

(defn- db-diff-summary
  "Top-level (depth-1) path summary of the db-before -> db-after delta.
  Returns `{:changed-paths [...] :added-paths [...] :removed-paths [...]}`.
  Each path is a one-key vector (e.g. `[:cart]`) — operators drill in
  via `get-path` for the full subtree. Bounded by the depth-1 walk so
  cascade-summary stays under the wire cap regardless of db size."
  [db-before db-after]
  (cond
    (and (map? db-before) (map? db-after))
    (let [ks-b   (set (keys db-before))
          ks-a   (set (keys db-after))
          common (set/intersection ks-b ks-a)]
      {:added-paths   (vec (sort (map vector (set/difference ks-a ks-b))))
       :removed-paths (vec (sort (map vector (set/difference ks-b ks-a))))
       :changed-paths (vec (sort (for [k common
                                       :when (not= (get db-before k) (get db-after k))]
                                   [k])))})

    (= db-before db-after)
    {:added-paths [] :removed-paths [] :changed-paths []}

    :else
    {:added-paths [] :removed-paths [] :changed-paths [[]]}))

(defn- machine-transitions-summary
  "Project machine-transition trace events out of an epoch's
  `:trace-events`. Returns a vector of compact `{:machine-id :from :to
  :phase}` maps, or nil when no machine activity. Per Spec 005 the
  machine-step trace stream uses `:rf.machine/transition` ops with
  `:tags {:machine-id :from :to :phase}`."
  [trace-events]
  (let [picks (->> trace-events
                   (filter (fn [ev] (= :rf.machine/transition (:operation ev))))
                   (mapv (fn [ev]
                           (let [t (:tags ev)]
                             (cond-> {}
                               (:machine-id t) (assoc :machine-id (:machine-id t))
                               (:from t)       (assoc :from (:from t))
                               (:to t)         (assoc :to (:to t))
                               (:phase t)      (assoc :phase (:phase t)))))))]
    (when (seq picks) picks)))

(defn- outcome-tier
  "Project the epoch's detailed `:outcome` cause onto the consumer-
  facing three-tier summary (`:ok` / `:blocked` / `:error`). Mirrors
  `re-frame.epoch.assembly/outcome->consumer-facing` (the same projection
  pinned in the framework). When `:outcome` is absent (older epoch
  shapes), defaults to `:ok`."
  [outcome]
  (case outcome
    :ok                       :ok
    :halted-depth             :blocked
    :halted-destroy           :blocked
    :halted-handler-exception :error
    :ok))

(defn cascade-summary
  "Project an assembled `:rf/epoch-record` into the compact wire shape
  surfaced by dispatch / reset-frame-db / restore-epoch / dispatch-dry-
  run (rf2-6yqdl). See the §Cascade summary section header above for
  the slot inventory.

  Pure data — `(epoch-record) -> cascade-summary-map`. Returns nil for a
  nil record so callers can `(when summary ...)` without an explicit
  nil-check at each call site."
  [{:keys [epoch-id event-id trigger-event frame outcome
           db-before db-after effects sub-runs renders trace-events]
    :as record}]
  (when record
    (let [diff       (db-diff-summary db-before db-after)
          fx-fired   (->> effects (map :fx-id) distinct vec)
          transitions (machine-transitions-summary trace-events)
          elapsed    (epoch-elapsed-ms record)
          sensitive? (:rf.epoch/sensitive? record)]
      (cond-> {:epoch-id        epoch-id
               :frame           frame
               :outcome         (outcome-tier outcome)
               :db-diff         diff
               :fx-fired        fx-fired
               :subs-recomputed (count (or sub-runs []))
               :renders         (count (or renders []))}
        event-id      (assoc :event-id event-id)
        trigger-event (assoc :event-vector trigger-event)
        transitions   (assoc :machine-transitions transitions)
        elapsed       (assoc :elapsed-ms elapsed)
        sensitive?    (assoc :sensitive? true)))))

(defn- attach-cascade
  "Attach a `:cascade-summary` slot to `result` when `epoch-id` resolves
  to a record in the operating frame's history. No-op when the head did
  not advance (queued dispatch that hasn't drained yet, dispatch-sync
  whose cascade settled without a recorded epoch)."
  [result frame-id epoch-id]
  (if-let [record (epoch-by-id epoch-id frame-id)]
    (assoc result :cascade-summary (cascade-summary record))
    result))

;; ---------------------------------------------------------------------------
;; Pair-tagged dispatch
;; ---------------------------------------------------------------------------
;;
;; Per Spec 002 §Dispatch origin tagging, dispatches carry an :origin opt
;; (default :app). The skill stamps :pair so `:rf.event/dispatched` traces
;; can be filtered by who fired them. Pair-epoch tracking populates
;; `pair-epoch-ids` from the assembled-epoch listener.

(defn- mark-pair! [epoch-id]
  (when epoch-id (swap! pair-epoch-ids conj epoch-id)))

(defn pair-dispatch!
  "Queued dispatch with `:origin :pair`. Returns
   `{:ok? true :queued? true :event ...}`. The epoch-id appears once
   the cascade settles; callers can read it via `last-pair-epoch`.

   Per rf2-6yqdl the response carries a `:cascade-summary` slot when
   the runtime drained the queue synchronously (the typical CLJS
   single-threaded case — `rf/dispatch` enqueues, the goog.async tick
   drains, and by the time our `(rf/epoch-history)` read fires the
   head has advanced). When the head did NOT advance (the cascade is
   still pending), the response omits `:cascade-summary` and ships
   `:cascade-summary-pending? true :before-epoch-id <prior-head>` so
   the caller can poll `watch-epochs` for the eventual settlement.

   No back-compat shim — `:queued? true` is preserved (the pre-rf2-6yqdl
   contract) but the cascade slot is the canonical 'what happened?'
   surface for new code."
  ([event-v] (pair-dispatch! event-v {}))
  ([event-v opts]
   (let [frame-id  (or (:frame opts) (current-frame))
         before-id (when frame-id (some-> (rf/epoch-history frame-id) peek :epoch-id))]
     (rf/dispatch event-v (merge {:origin :pair} opts))
     (let [after-id (when frame-id (some-> (rf/epoch-history frame-id) peek :epoch-id))
           base     {:ok? true :queued? true :event event-v :opts opts}]
       (cond
         (and after-id (not= before-id after-id))
         (do (mark-pair! after-id)
             (attach-cascade (assoc base :epoch-id after-id :frame frame-id)
                             frame-id after-id))

         :else
         (assoc base
                :frame frame-id
                :cascade-summary-pending? true
                :before-epoch-id before-id
                :hint (str "rf/dispatch enqueued; head did not advance synchronously. "
                           "Poll watch-epochs (since-id before-epoch-id) for the "
                           "eventual cascade.")))))))

(defn pair-dispatch-sync!
  "Synchronous dispatch with `:origin :pair`. Reads the operating
   frame's epoch-history before and after; the new head is reported
   as the pair-attributed epoch.

   On real success returns {:ok? true :epoch-id <id> :event ...
   :cascade-summary {...}} — per rf2-6yqdl the cascade summary rides
   under `:cascade-summary` (the compact projection defined above; see
   §Cascade summary). When epoch-history depth is 0 (recording
   disabled) or the frame isn't registered, reports the failure mode
   rather than claiming success."
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
             (attach-cascade {:ok? true :epoch-id after-id :event event-v :frame frame-id}
                             frame-id after-id))

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

(defn- restore-cascade-summary
  "Build a cascade-summary projection for a successful `restore-epoch`
   call (rf2-6yqdl). A restore is NOT a real cascade — the framework
   rewinds the app-db in-place without recording a new epoch — so the
   normal `epoch-by-id` lookup wouldn't surface anything. Instead we
   project against the TARGET epoch (the one we restored TO) and
   compute the `:db-diff` from `pre-db` (the db state immediately
   before the restore) to the target's `:db-after`. That answers the
   programmer's actual question — 'what is now different from where I
   was?' — using the cascade-summary vocabulary everyone already knows.

   Returns nil if the target epoch isn't in the ring (defensive — a
   successful restore implies the id is in the ring, but the read may
   race against a ring rotation in a heavy concurrent setting).

   Additionally returns the `:unreplayable-effects` slot per the bead
   spec: every fx that the ORIGINAL cascade fired and that the restore
   cannot undo (http requests already sent, navigation already pushed,
   storage already written). Programmers reading 'I just rewound' need
   to know which side-effects already escaped the framework."
  [pre-db frame-id target-epoch-id]
  (when-let [target (epoch-by-id target-epoch-id frame-id)]
    (let [diff       (db-diff-summary pre-db (:db-after target))
          fx-fired   (->> (:effects target) (map :fx-id) distinct vec)
          transitions (machine-transitions-summary (:trace-events target))
          ;; Every fx in the target's :effects fired BEFORE the restore;
          ;; the restore rewinds db only. They are unreplayable by
          ;; construction.
          unreplayable (mapv (fn [eff]
                               (cond-> {:fx-id (:fx-id eff)}
                                 (:coord eff) (assoc :coord (:coord eff))))
                             (:effects target))]
      {:cascade-summary
       (cond-> {:epoch-id       target-epoch-id
                :frame          frame-id
                :outcome        :ok
                :db-diff        diff
                :fx-fired       fx-fired
                :subs-recomputed (count (or (:sub-runs target) []))
                :renders         (count (or (:renders target) []))
                :restore?       true}
         (:event-id target)      (assoc :event-id (:event-id target))
         (:trigger-event target) (assoc :event-vector (:trigger-event target))
         transitions             (assoc :machine-transitions transitions))
       :unreplayable-effects unreplayable})))

(defn restore-epoch
  "(rf/restore-epoch frame-id epoch-id). Returns a structured envelope
   per rf2-6yqdl:

     - `{:ok? true :restored? true :epoch-id <id> :frame <id>
         :cascade-summary {...} :unreplayable-effects [...]}` on success.
       The cascade-summary projects the TARGET epoch's shape; the
       `:db-diff` slot is computed from the live db at restore-time to
       the target's `:db-after`. `:unreplayable-effects` enumerates the
       fx the original cascade fired that the restore cannot undo.
     - `false` on any failure mode. Failure traces fire under
       `:rf.epoch/*` — read them with
       `(re-frame.trace.tooling/trace-buffer {:op-type :error})`.

   The two arities mirror `pair-dispatch-sync!`'s shape — 1-arity reads
   `(current-frame)`, 2-arity is explicit."
  ([epoch-id] (restore-epoch epoch-id (current-frame)))
  ([epoch-id frame-id]
   (let [pre-db (rf/get-frame-db frame-id)
         ok?    (rf/restore-epoch frame-id epoch-id)]
     (if ok?
       (let [extras (restore-cascade-summary pre-db frame-id epoch-id)]
         (merge {:ok? true :restored? true :epoch-id epoch-id :frame frame-id}
                extras))
       ;; Preserve the legacy `false` return on failure — the MCP
       ;; restore-epoch tool turns that into a structured envelope at
       ;; the wire boundary (the pre-rf2-6yqdl behaviour). Mirrors how
       ;; reset-frame-db! returns a soft-failure envelope on the runtime
       ;; side but the tool can elide the framework's `false`.
       false))))

(defn undo-step-back
  "Restore the previous epoch in the operating frame. Returns
   `{:ok? true :epoch-id <previous> :restored? true :cascade-summary
   {...} :unreplayable-effects [...]}` on success or
   `{:ok? false :reason :no-prior-epoch}` when there is no previous
   record. Cascade-summary slot per rf2-6yqdl."
  ([] (undo-step-back (current-frame)))
  ([frame-id]
   (let [history (vec (rf/epoch-history frame-id))
         n       (count history)]
     (if (< n 2)
       {:ok? false :reason :no-prior-epoch :history-size n :frame frame-id}
       (let [prior     (nth history (- n 2))
             epoch-id  (:epoch-id prior)
             pre-db    (rf/get-frame-db frame-id)
             ok?       (rf/restore-epoch frame-id epoch-id)]
         (if ok?
           (merge {:ok? true :epoch-id epoch-id :restored? true :frame frame-id}
                  (restore-cascade-summary pre-db frame-id epoch-id))
           {:ok? false :epoch-id epoch-id :restored? false :frame frame-id
            :reason :restore-rejected}))))))

(defn undo-to-epoch
  "Restore a specific epoch by id. Returns the same shape as
   `restore-epoch`'s success envelope. Cascade-summary slot per
   rf2-6yqdl."
  ([epoch-id] (undo-to-epoch epoch-id (current-frame)))
  ([epoch-id frame-id]
   (let [pre-db (rf/get-frame-db frame-id)
         ok?    (rf/restore-epoch frame-id epoch-id)]
     (if ok?
       (merge {:ok? true :epoch-id epoch-id :restored? true :frame frame-id}
              (restore-cascade-summary pre-db frame-id epoch-id))
       {:ok?       true
        :epoch-id  epoch-id
        :restored? false
        :frame     frame-id
        :reason    :restore-rejected}))))

;; ---------------------------------------------------------------------------
;; DOM ↔ source bridge
;; ---------------------------------------------------------------------------
;;
;; Two attribute sources, in priority order:
;;   1. data-rf2-source-coord (re-frame2's own annotation when
;;      :annotate-dom? is on — Tool-Pair §Source-mapping;
;;      Spec 006 §Source-coord annotation)
;;   2. data-rc-src (re-com's debug attribute, fallback)
;;
;; The two attributes resolve to different schemas — re-frame2's
;; carries the registry-id derived <ns>/<handler-id> with <line>/<col>;
;; re-com's carries <file>/<line>/<column>. The runtime returns
;; whichever map the first present attribute parses to.

(defn- parse-rf2-coord
  "Parse re-frame2's `data-rf2-source-coord` attribute.

   Per Spec 006 §Source-coord annotation and Tool-Pair
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
   parser exists so re-frame2-pair's DOM-to-source bridge can be useful, but
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

(defn epoch-elapsed-ms
  "Compute the handler's wall-clock elapsed-ms for an epoch by pairing
   the cascade's `:rf.event/run-start` and `:rf.event/run-end` trace events
   on `:time` (the host-clock timestamp every trace event carries per
   Spec 009 §Trace event shape).

   The epoch record itself has no top-level timing slot — derivation
   happens here. Multiple run-start/run-end pairs can appear inside a
   single epoch when a handler synchronously dispatches further events;
   we span from the FIRST run-start to the LAST run-end so the value
   answers 'how long did this cascade's handler-chain hold the
   thread?', which is the intuition `--timing-ms` users have from the
   bash shim.

   Returns nil when neither bracket is present (degenerate cascades, or
   epochs whose `:trace-events` slot was elided for ring-buffer age —
   see Spec-Schemas §`:rf/epoch-record`)."
  [{:keys [trace-events]}]
  (let [run-event? (fn [op ev]
                     (and (= :rf.event (:op-type ev))
                          (= op (:operation ev))))
        first-time (some (fn [ev] (when (run-event? :rf.event/run-start ev) (:time ev))) trace-events)
        last-time  (reduce (fn [acc ev]
                             (if (run-event? :rf.event/run-end ev)
                               (let [t (:time ev)] (if (and (number? t) (or (nil? acc) (> t acc))) t acc))
                               acc))
                           nil
                           trace-events)]
    (when (and (number? first-time) (number? last-time) (>= last-time first-time))
      (- last-time first-time))))

(defn ^:private parse-timing-pred
  "Parse a `:timing-ms` predicate value into a one-arg matcher fn.

   Accepts:
     - a number `N` — sugar for `>= N` (the common 'slow events'
       intuition: `100` ≡ '100 ms or slower').
     - a string `\">N\"`, `\">=N\"`, `\"<N\"`, `\"<=N\"`, `\"=N\"` —
       comparison against the parsed numeric threshold.

   Returns `nil` for unparseable inputs; callers treat nil as
   'predicate absent' so a malformed filter doesn't accidentally
   match everything."
  [v]
  (cond
    (number? v)
    (fn [ms] (and (number? ms) (>= ms v)))

    (string? v)
    (let [m (re-matches #"\s*(>=|<=|>|<|=)?\s*(-?\d+(?:\.\d+)?)\s*" v)]
      (when m
        (let [op (or (nth m 1) ">=")
              n  (js/parseFloat (nth m 2))]
          (when-not (js/isNaN n)
            (case op
              ">"  (fn [ms] (and (number? ms) (> ms n)))
              ">=" (fn [ms] (and (number? ms) (>= ms n)))
              "<"  (fn [ms] (and (number? ms) (< ms n)))
              "<=" (fn [ms] (and (number? ms) (<= ms n)))
              "="  (fn [ms] (and (number? ms) (= ms n)))
              nil)))))))

(defn epoch-matches?
  "Test an epoch record against a predicate map built from
   `watch-epochs.sh` CLI args.

   Recognised keys: :event-id, :event-id-prefix, :effects (matches
   :fx-id in the projection), :touches-path (anywhere in db-before /
   db-after), :sub-ran (matches :sub-id or first of :query-v),
   :render (matches :render-key as a string), :origin (matches
   :rf.event/origin in :rf.event/dispatched trace events), :frame, :timing-ms.

   `:timing-ms` — server-side timing filter. Accepts a
   number (sugar for `>= N`) or a comparison string (`\">100\"`,
   `\"<=50\"`, `\">=100\"`, `\"<200\"`, `\"=42\"`). Compares against
   the epoch's wall-clock elapsed-ms derived from the cascade's
   `:rf.event/run-start` / `:rf.event/run-end` trace pair (see
   `epoch-elapsed-ms`). Filtering rides server-side so the wire
   payload shrinks before bytes cross the boundary — matters for the
   'alert me on slow events' recipe under streaming subscriptions.

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
         p-frame  :frame
         p-timing :timing-ms} pred
        timing-fn (when (some? p-timing) (parse-timing-pred p-timing))]
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
      (if p-origin (some (fn [t] (and (= :rf.event/dispatched (:operation t))
                                      (= p-origin (get-in t [:tags :rf.event/origin]))))
                         trace-events)
                   true)
      (if p-frame  (= p-frame frame) true)
      (if timing-fn (timing-fn (epoch-elapsed-ms epoch)) true)))))

;; ---------------------------------------------------------------------------
;; Dispatch-and-collect
;; ---------------------------------------------------------------------------

(defn dispatch-and-collect
  "Synchronously dispatch (origin :pair) and return the resulting
   epoch record. Drain-settle is synchronous in re-frame2's
   `dispatch-sync`, so the new epoch is in the frame's history by the
   time this call returns.

   The response carries BOTH the full `:epoch` (the verbatim assembled
   record — the trace mode's historical payload) AND a `:cascade-summary`
   slot per rf2-6yqdl. Callers that only need the headline 'what
   happened' read the compact summary; callers that need the raw
   trace-events / db-before / db-after pair read `:epoch`."
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
;; Wire-boundary wrapping. `snapshot-state` here returns the
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
    :sub-cache  (subs-tooling/sub-cache-snapshot frame-id)
    ;; The global machine-id list is registrar-level (not per-frame).
    ;; Per Spec 005 each frame holds its own machine snapshots at
    ;; [:rf/machines machine-id] in app-db, so the per-frame slice
    ;; returns {:ids [...] :state {machine-id snapshot}}.
    :machines   (let [ids (vec (rf/machines))
                      state (or (get (rf/get-frame-db frame-id) :rf/machines)
                                {})]
                  {:ids ids :state state})
    :epochs     (vec (rf/epoch-history frame-id))
    ;; Per rf2-g1b2m / rf2-8uwce the trace ring is per-frame and
    ;; cascade-bundle-shaped by default; rf2-mscih shifts this slot
    ;; to deliver bundles (the storage unit) rather than the legacy
    ;; flat-event stream, matching the cascade-bundle wire format
    ;; emitted by the streaming subscribe surface (Tool-Pair §Reading
    ;; the per-frame trace ring + Tool-Pair §`watch-epochs` /
    ;; `trace-window` consumer shape).
    :traces     (vec (trace-tooling/trace-buffer frame-id))
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
   path-slicing + lazy-summary at the wire boundary — see
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
