(ns re-frame-pair2-mcp.tools
  "MCP tools — one per pair2 op. Each tool builds an nREPL eval request,
  sends it over the persistent connection, and returns the result as an
  MCP `tools/call` result.

  ## Tool catalogue

  | MCP tool name | What it does                                              |
  |---------------|-----------------------------------------------------------|
  | discover-app  | Verify nREPL + confirm the preloaded runtime + health     |
  | eval-cljs     | Eval a CLJS form, return the value                        |
  | dispatch      | Fire a re-frame2 event with :origin :pair                 |
  | trace-window  | Epochs in the last N ms                                   |
  | watch-epochs  | Pull-mode live epoch streaming                            |
  | tail-build    | Wait for a hot-reload to land                             |
  | snapshot      | Coarse-grained per-frame state read (mega-op)             |
  | subscribe     | Streaming trace/epoch channel — push-mode replacement for |
  |               | watch-epochs (rf2-hq49)                                   |
  | unsubscribe   | Close a streaming subscription                            |

  ## Preload probe (no per-session inject)

  The `re-frame-pair2.runtime` namespace ships into the consumer app via
  shadow-cljs's `:devtools :preloads` mechanism. Each tool that needs
  the runtime first calls `ensure-runtime!`, which checks
  `js/globalThis.__re_frame_pair2_runtime` — a load-time mirror the
  preload installs. Missing marker means the preload isn't configured;
  the tool refuses with `:reason :runtime-not-preloaded` and a setup
  hint pointing at `skills/re-frame-pair2/SKILL.md`.

  No cljs-eval inject path: the preload is the canonical setup. Earlier
  drops shipped a per-session inject fallback; that path was cut for
  rf2-7dvg.

  ## Result shape

  Each MCP tool returns `{:content [{:type \"text\" :text <edn-string>}]}`
  on success, or `{:isError true :content [...]}` on failure."
  (:require [applied-science.js-interop :as j]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame-pair2-mcp.nrepl :as nrepl]))

;; ---------------------------------------------------------------------------
;; Config — build id.
;; ---------------------------------------------------------------------------

(defn- default-build-id []
  (or (some-> (j/get-in js/process [:env :SHADOW_CLJS_BUILD_ID])
              keyword)
      :app))

;; ---------------------------------------------------------------------------
;; MCP result helpers.
;; ---------------------------------------------------------------------------

(defn- ok-text [v]
  #js {:content #js [#js {:type "text" :text (pr-str v)}]})

(defn- err-text [v]
  #js {:isError true
       :content #js [#js {:type "text" :text (pr-str v)}]})

(defn- arg
  "Extract an MCP tool argument by name. Returns nil if absent."
  [args k]
  (let [v (j/get args (name k))]
    (when-not (or (nil? v) (undefined? v)) v)))

(defn- arg-build [args]
  (or (some-> (arg args :build) keyword)
      (default-build-id)))

;; ---------------------------------------------------------------------------
;; Preload probe.
;; ---------------------------------------------------------------------------

(def ^:private preload-missing-hint
  (str "re-frame-pair2.runtime is not loaded into this build. Add the "
       "preload entry to your shadow-cljs.edn:\n"
       "  :builds {:app {:devtools {:preloads [re-frame-pair2.runtime]}}}\n"
       "and make sure the directory containing re_frame_pair2/runtime.cljs "
       "is on :source-paths. See skills/re-frame-pair2/SKILL.md (§Setup)."))

(defn- runtime-preloaded?
  "Probe `js/globalThis.__re_frame_pair2_runtime` — the load-time
  marker set by the preloaded `re-frame-pair2.runtime` namespace.
  Resolves to true iff the marker is present. One bencode round-trip,
  no CLJS compile."
  [conn build-id]
  (-> (nrepl/cljs-eval-value
        conn build-id
        "(some? (and (exists? js/globalThis) (.-__re_frame_pair2_runtime js/globalThis)))")
      (.then (fn [v] (true? v)))
      (.catch (fn [_] false))))

(defn- runtime-health!
  "Call `(re-frame-pair2.runtime/health)`. Caller must have already
  confirmed the preload landed via `runtime-preloaded?`."
  [conn build-id]
  (nrepl/cljs-eval-value conn build-id "(re-frame-pair2.runtime/health)"))

(defn- ensure-runtime!
  "Confirm the pair2 runtime is preloaded. Resolves to nil on success,
  rejects with a structured error otherwise. Tools that need the
  runtime call this first."
  [conn build-id]
  (-> (runtime-preloaded? conn build-id)
      (.then (fn [ok?]
               (if ok?
                 nil
                 (js/Promise.reject
                   (ex-info "pair2 runtime not preloaded"
                            {:reason :runtime-not-preloaded
                             :hint   preload-missing-hint})))))))

;; ---------------------------------------------------------------------------
;; Error helpers — surface structured `ex-info` from `ensure-runtime!`.
;; ---------------------------------------------------------------------------

(defn- err->result
  "Translate a Promise rejection into an `ok-text` result. Structured
  ex-info reasons (e.g. `:runtime-not-preloaded`) surface verbatim;
  other errors fall through to a generic eval-error shape."
  [fallback-reason err]
  (if-let [data (ex-data err)]
    (ok-text (merge {:ok? false} data))
    (ok-text {:ok? false :reason fallback-reason :message (.-message err)})))

;; ---------------------------------------------------------------------------
;; :sensitive? default-suppress (per spec/009 §Privacy / sensitive data).
;;
;; Spec 009 mandates that framework-published forwarders — Sentry /
;; Honeybadger, pair2 server, Causa-MCP — MUST default-drop trace events
;; whose registration was declared `:sensitive? true`. The runtime
;; stamps `:sensitive? true` at the top level of every emitted trace
;; event inside such a registration's handler scope; an event with no
;; such stamp (or `:sensitive? false`) is fine to forward.
;;
;; Opt-in escape hatch: an MCP arg of `:include-sensitive? true` (on
;; any read/stream tool that surfaces trace-like data) removes the
;; filter for that call. The default is off — apps that want sensitive
;; cascades visible to the pair tool configure the policy explicitly.
;; ---------------------------------------------------------------------------

(defn- include-sensitive?
  "True iff the caller has opted in to forwarding `:sensitive? true`
  events for this call. Default off."
  [args]
  (boolean (arg args :include-sensitive?)))

(defn- sensitive-event?
  "Does this event carry the top-level `:sensitive? true` stamp? The
  filter is conservative — only the literal `true` value drops; any
  other value (including the runtime's possible string-coercion via an
  ill-behaved transport) passes through. The `:rf/trace-event` schema
  (per spec/009) types `:sensitive?` as a boolean."
  [ev]
  (and (map? ev)
       (true? (:sensitive? ev))))

(defn- strip-sensitive
  "Remove `:sensitive? true` events from `events` unless the caller opted
  in. Returns `[kept dropped-count]`. Cheap on the common path
  (no sensitive events ⇒ identical-vector return + zero drop count)."
  [events include?]
  (cond
    include?            [events 0]
    (empty? events)     [events 0]
    :else
    (let [kept (filterv (complement sensitive-event?) events)
          n    (- (count events) (count kept))]
      [kept n])))

;; ---------------------------------------------------------------------------
;; Tool: discover-app — verify the stack and probe the preloaded runtime.
;; ---------------------------------------------------------------------------

(defn- discover-app [conn args]
  (let [build-id (arg-build args)]
    (-> (ensure-runtime! conn build-id)
        (.then (fn [_] (runtime-health! conn build-id)))
        (.then
          (fn [health]
            (cond
              (not (:ok? health))
              (ok-text health)

              (not (:debug-enabled? health))
              (ok-text {:ok? false :reason :debug-disabled
                        :hint (str "re-frame.interop/debug-enabled? is false. "
                                   "This is a production build (or goog.DEBUG was "
                                   "forced off). Trace and epoch surfaces are elided.")})

              (empty? (:frames health))
              (ok-text {:ok? false :reason :no-frames-registered
                        :hint "Call (rf/init!) to register :rf/default, or wait for app boot."})

              (:ambiguous-frame? health)
              (ok-text (assoc health :ok? true
                                     :warning :ambiguous-frame
                                     :note (str "Multiple frames registered: "
                                                (vec (:frames health))
                                                ". Mutating ops require --frame :foo "
                                                "or run `frames/select` first.")))

              (not (:coord-annotation-enabled? health))
              (ok-text (assoc health :ok? true
                                     :warning :no-source-coord-annotation
                                     :note (str "Neither data-rf2-source-coord nor "
                                                "data-rc-src is on any element. "
                                                "DOM->source ops will degrade. Enable "
                                                "(rf/configure :source-coords {:annotate-dom? true}) "
                                                "or use re-com with :src (at).")))

              :else
              (ok-text (assoc health :ok? true :build-id build-id)))))
        (.catch (fn [err] (err->result :discover-failed err))))))

;; ---------------------------------------------------------------------------
;; Tool: eval-cljs — evaluate one CLJS form.
;; ---------------------------------------------------------------------------

(defn- eval-cljs-tool [conn args]
  (let [form     (arg args :form)
        build-id (arg-build args)]
    (cond
      (or (nil? form) (str/blank? form))
      (js/Promise.resolve
        (err-text {:ok? false :reason :missing-form
                   :hint "usage: eval-cljs {form '<cljs-form>' [build :app]}"}))

      :else
      (-> (ensure-runtime! conn build-id)
          (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
          (.then (fn [v] (ok-text {:ok? true :value v})))
          (.catch (fn [err] (err->result :eval-error err)))))))

;; ---------------------------------------------------------------------------
;; Tool: dispatch — fire an event.
;; ---------------------------------------------------------------------------

(defn- dispatch-tool [conn args]
  (let [event-str   (arg args :event)
        build-id    (arg-build args)
        sync?       (boolean (arg args :sync))
        trace?      (boolean (arg args :trace))
        frame       (some-> (arg args :frame) keyword)
        fx-overrides (when-let [o (arg args :fx-overrides)] (js->clj o :keywordize-keys true))]
    (cond
      (or (nil? event-str) (str/blank? event-str))
      (js/Promise.resolve
        (err-text {:ok? false :reason :missing-event
                   :hint "usage: dispatch {event '[:ev/id ...]' [sync true] [trace true] [frame :foo] [fx-overrides {...}]}"}))

      :else
      (let [opts-form (cond-> {}
                        frame        (assoc :frame frame)
                        fx-overrides (assoc :fx-overrides fx-overrides))
            form (cond
                   trace?
                   (str "(re-frame-pair2.runtime/dispatch-and-collect " event-str " " (pr-str opts-form) ")")
                   sync?
                   (str "(re-frame-pair2.runtime/pair-dispatch-sync! " event-str " " (pr-str opts-form) ")")
                   :else
                   (str "(re-frame-pair2.runtime/pair-dispatch! " event-str " " (pr-str opts-form) ")"))
            mode (cond trace? :trace sync? :sync :else :queued)]
        (-> (ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [v] (ok-text (merge {:mode mode} (when (map? v) v)))))
            (.catch (fn [err] (err->result :dispatch-failed err))))))))

;; ---------------------------------------------------------------------------
;; Tool: trace-window — epochs in the last N ms.
;; ---------------------------------------------------------------------------

(defn- trace-window-tool [conn args]
  (let [ms        (or (arg args :ms) 1000)
        build-id  (arg-build args)
        frame     (some-> (arg args :frame) keyword)
        incl?     (include-sensitive? args)
        form      (if frame
                    (str "(re-frame-pair2.runtime/epochs-in-last-ms " ms " " (pr-str frame) ")")
                    (str "(re-frame-pair2.runtime/epochs-in-last-ms " ms ")"))]
    (-> (ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [epochs]
                 (let [[kept dropped] (strip-sensitive (vec epochs) incl?)]
                   (ok-text (cond-> {:ok? true
                                     :window-ms ms
                                     :count (count kept)
                                     :epochs kept}
                              (pos? dropped) (assoc :dropped-sensitive dropped))))))
        (.catch (fn [err] (err->result :trace-failed err))))))

;; ---------------------------------------------------------------------------
;; Tool: watch-epochs — pull-mode polling with predicate filter.
;;
;; The bash version streams via repeated `emit`s on stdout. MCP tools
;; aren't streaming — we return one bundle of matches per call. Callers
;; that want a tight loop call us repeatedly with the same `since-id`.
;; ---------------------------------------------------------------------------

(defn- watch-epochs-tool [conn args]
  (let [build-id  (arg-build args)
        frame     (some-> (arg args :frame) keyword)
        since-id  (arg args :since-id)
        incl?     (include-sensitive? args)
        pred-map  (when-let [p (arg args :pred)] (js->clj p :keywordize-keys true))
        frame-arg (if frame (str " " (pr-str frame)) "")
        form      (str "(let [r (re-frame-pair2.runtime/epochs-since "
                       (pr-str since-id) frame-arg ")"
                       "      matches (filterv #(re-frame-pair2.runtime/epoch-matches? "
                       (pr-str (or pred-map {})) " %) (:epochs r))]"
                       "  {:matches matches"
                       "   :id-aged-out? (:id-aged-out? r)"
                       "   :head-id (:head-id r)})")]
    (-> (ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [v]
                 (let [matches        (when (map? v) (:matches v))
                       [kept dropped] (strip-sensitive (vec matches) incl?)
                       base           (cond-> {:ok? true}
                                        (map? v) (merge v))]
                   (ok-text (cond-> (assoc base :matches kept)
                              (pos? dropped) (assoc :dropped-sensitive dropped))))))
        (.catch (fn [err] (err->result :watch-failed err))))))

;; ---------------------------------------------------------------------------
;; Tool: tail-build — wait for hot-reload to land.
;; ---------------------------------------------------------------------------

(defn- tail-build-tool [conn args]
  (let [build-id (arg-build args)
        wait-ms  (or (arg args :wait-ms) 5000)
        probe    (arg args :probe)
        poll-ms  100]
    (cond
      (nil? probe)
      ;; Soft delay — matches the bash version's behaviour when no probe
      ;; is supplied. We just resolve after a short sleep.
      (js/Promise.
        (fn [resolve _]
          (js/setTimeout
            (fn []
              (resolve (ok-text {:ok? true :t (js/Date.now) :soft? true
                                 :note "No probe supplied; waited a 300ms fixed delay."})))
            300)))

      :else
      (let [start (js/Date.now)]
        (-> (nrepl/cljs-eval-value conn build-id probe)
            (.then
              (fn [before]
                (js/Promise.
                  (fn [resolve _]
                    (letfn [(poll []
                              (js/setTimeout
                                (fn []
                                  (let [elapsed (- (js/Date.now) start)]
                                    (if (>= elapsed wait-ms)
                                      (resolve
                                        (ok-text {:ok? false :reason :timed-out :timed-out? true
                                                  :note "Probe did not change within wait-ms. Likely a compile error."}))
                                      (-> (nrepl/cljs-eval-value conn build-id probe)
                                          (.then
                                            (fn [now]
                                              (if (not= now before)
                                                (resolve (ok-text {:ok? true :t (js/Date.now) :soft? false}))
                                                (poll))))
                                          (.catch (fn [_] (poll)))))))
                                poll-ms))]
                      (poll))))))
            (.catch
              (fn [err]
                (ok-text {:ok? false :reason :probe-failed
                          :message (.-message err)}))))))))

;; ---------------------------------------------------------------------------
;; Tool: snapshot — coarse-grained per-frame state read in one round-trip.
;;
;; Many investigate-X workflows chain 5-10 reads; each is a bencode
;; round-trip plus Claude-think latency. This op composes the existing
;; per-slice readers server-side and returns a per-frame map.
;; ---------------------------------------------------------------------------

(def ^:private valid-slices
  #{:app-db :sub-cache :machines :epochs :traces})

(defn- ->frame-keyword
  "Coerce a frame-id string into a keyword. Accepts both bare names
   (`\"rf/default\"`) and EDN-shaped strings (`\":rf/default\"`) — strips
   a leading colon when present so callers can pass either form."
  [x]
  (cond
    (keyword? x) x
    (string? x)
    (let [s (if (str/starts-with? x ":") (subs x 1) x)]
      (keyword s))
    :else (keyword x)))

(defn- parse-frames-arg
  "Normalise the `frames` MCP arg into the form the runtime expects.
   Accepts `:all`, the string \"all\", a JS array of strings, or a CLJS
   vector. Returns `:all` or a vector of keyword frame-ids. Returns
   `:all` for nil / empty / unrecognised input — least-surprise."
  [raw]
  (cond
    (nil? raw) :all
    (or (= raw :all) (= raw "all")) :all
    (array? raw)
    (->> (js->clj raw) (mapv ->frame-keyword))
    (sequential? raw)
    (mapv ->frame-keyword raw)
    :else :all))

(defn- parse-include-arg
  "Normalise the `include` MCP arg into the slice vector the runtime
   expects. Filters to known slices; returns the full set when arg
   is nil / empty / all-unknown."
  [raw]
  (let [full [:app-db :sub-cache :machines :epochs :traces]
        coerce (fn [xs]
                 (->> xs
                      (map keyword)
                      (filter valid-slices)
                      vec))]
    (cond
      (nil? raw) full
      (array? raw)
      (let [v (coerce (js->clj raw))]
        (if (seq v) v full))
      (sequential? raw)
      (let [v (coerce raw)]
        (if (seq v) v full))
      :else full)))

(defn- scrub-snapshot-sensitive
  "Walk a snapshot's per-frame map and drop `:sensitive? true` items from
  the `:traces` slice (and, defensively, `:epochs` — epoch records may
  inherit the stamp in future runtime revisions per spec/009). Returns
  `[scrubbed dropped-count]`. The non-trace slices (:app-db, :sub-cache,
  :machines) pass through unchanged — redaction of those payloads is
  the `with-redacted` interceptor's job, not the forwarder's."
  [snapshot include?]
  (if (or include? (not (map? snapshot)))
    [snapshot 0]
    (let [dropped (atom 0)
          scrub-slice
          (fn [items]
            (let [[kept n] (strip-sensitive (vec items) false)]
              (swap! dropped + n)
              kept))
          scrub-frame
          (fn [frame-map]
            (cond-> frame-map
              (contains? frame-map :traces) (update :traces scrub-slice)
              (contains? frame-map :epochs) (update :epochs scrub-slice)))
          scrubbed (reduce-kv (fn [m k v]
                                (assoc m k (if (map? v) (scrub-frame v) v)))
                              {} snapshot)]
      [scrubbed @dropped])))

(defn- snapshot-tool [conn args]
  (let [build-id (arg-build args)
        frames   (parse-frames-arg (arg args :frames))
        include  (parse-include-arg (arg args :include))
        incl?    (include-sensitive? args)
        opts     {:frames frames :include include}
        form     (str "(re-frame-pair2.runtime/snapshot-state "
                      (pr-str opts) ")")]
    (-> (ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [v]
                 (let [[scrubbed dropped] (scrub-snapshot-sensitive v incl?)]
                   (ok-text (cond-> {:ok?      true
                                     :frames   (if (= :all frames) :all (vec frames))
                                     :include  include
                                     :snapshot scrubbed}
                              (pos? dropped) (assoc :dropped-sensitive dropped))))))
        (.catch (fn [err] (err->result :snapshot-failed err))))))

;; ---------------------------------------------------------------------------
;; Tool: subscribe — streaming trace + epoch channel (rf2-hq49).
;;
;; The MCP `tools/call` request runs until either:
;;   (a) the client aborts (cancellation arrives via the MCP `extra.signal`
;;       AbortSignal), or
;;   (b) an `unsubscribe` op clears the sub-id from the runtime.
;;
;; While running, each batch of newly-queued runtime events is shipped to
;; the client as a `notifications/progress` notification correlated to the
;; original tools/call via `extra._meta.progressToken`. The final
;; `tools/call` result is a summary `{:ok? true :sub-id :delivered N
;; :overflow N :reason <terminated-reason>}`.
;;
;; The runtime queue is bounded (default 500); overflow events get
;; counted in a per-sub `:overflow` slot and surfaced verbatim. The
;; server's poll cadence (`:poll-ms`, default 100) is well below the
;; agent-loop perceptual threshold and costs one bencode round-trip
;; per tick.
;;
;; Filter vocabulary (server-side normalisation happens on the runtime).
;;
;; Topics:
;;   :trace  — every entry of the raw trace stream matching `:filter`.
;;             `:filter` keys: :operation :op-type :frame :severity
;;                            :event-id :handler-id :source :origin
;;                            :dispatch-id :since-ms :between
;;             (mirrors `(rf/trace-buffer)` per rf2-97ah0).
;;   :epoch  — every assembled `:rf/epoch-record` matching `:filter`.
;;             `:filter` keys: :event-id :event-id-prefix :effects
;;                            :touches-path :sub-ran :render :origin
;;                            :frame  (mirrors `epoch-matches?`).
;;   :fx     — sugar for :topic :trace :filter {:op-type :fx ...}.
;;   :error  — sugar for :topic :trace :filter {:op-type :error ...}.
;; ---------------------------------------------------------------------------

(def ^:private default-poll-ms 100)

(defn- progress-payload
  "Build the JSON params payload for one `notifications/progress` tick.
  `events` is the EDN-printed string of the batch (kept as a string so
  the agent host sees the same shape as `tools/call` results)."
  [progress-token tick events overflow]
  #js {:progressToken progress-token
       :progress      tick
       ;; `message` is the human-readable slot. We stash an EDN form
       ;; here so an MCP client that surfaces progress messages to
       ;; the agent shows the events directly. A capable client can
       ;; additionally inspect the `data` slot for the structured
       ;; counts.
       :message       events
       :data          #js {:overflow overflow}})

(defn- parse-filter-arg
  "MCP-side filter arg can be either a JS object or an EDN string. We
  accept both for ergonomic parity with the bash-shim chain (`pred`
  has been a JSON object there). Returns an EDN-printable map or nil
  when missing."
  [raw]
  (cond
    (nil? raw)        nil
    (string? raw)     (try (cljs.reader/read-string raw)
                           (catch :default _
                             {:invalid-filter-edn raw}))
    (map? raw)        raw
    :else             (js->clj raw :keywordize-keys true)))

(defn- subscribe-tool [conn args extra]
  (let [build-id    (arg-build args)
        topic       (some-> (arg args :topic) keyword)
        filter-map  (parse-filter-arg (arg args :filter))
        max-buf     (or (arg args :max-buffered) 500)
        poll-ms     (or (arg args :poll-ms) default-poll-ms)
        max-ms      (or (arg args :max-ms) 0)    ;; 0 = no upper bound
        max-events  (or (arg args :max-events) 0) ;; 0 = no upper bound
        incl?       (include-sensitive? args)
        progress-tk (some-> extra
                            (j/get :_meta)
                            (j/get :progressToken))
        send-note   (some-> extra (j/get :sendNotification))
        signal      (some-> extra (j/get :signal))]
    (cond
      (or (nil? topic)
          (not (#{:trace :epoch :fx :error} topic)))
      (js/Promise.resolve
        (err-text {:ok? false :reason :unknown-topic
                   :given (arg args :topic)
                   :hint "Recognised topics: trace, epoch, fx, error."}))

      :else
      (let [subscribe-form
            (str "(re-frame-pair2.runtime/subscribe! "
                 (pr-str (cond-> {:topic topic
                                  :max-buffered max-buf}
                           filter-map (assoc :filter filter-map)))
                 ")")]
        (-> (ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id subscribe-form)))
            (.then
              (fn [subscribe-resp]
                (if-not (:ok? subscribe-resp)
                  ;; Runtime refused (unknown topic, etc.) — surface verbatim.
                  (ok-text subscribe-resp)
                  (let [sub-id (:sub-id subscribe-resp)]
                    (js/Promise.
                      (fn [resolve _reject]
                        (let [tick               (atom 0)
                              delivered          (atom 0)
                              overflow*          (atom 0)
                              dropped-sensitive* (atom 0)
                              terminate
                              (fn [reason]
                                ;; Drop the runtime subscription and
                                ;; resolve. Idempotent — unsubscribe!
                                ;; returns :existed? false the second
                                ;; time.
                                (-> (nrepl/cljs-eval-value
                                      conn build-id
                                      (str "(re-frame-pair2.runtime/unsubscribe! "
                                           (pr-str sub-id) ")"))
                                    (.catch (fn [_] nil))
                                    (.then
                                      (fn [_]
                                        (resolve
                                          (ok-text
                                            (cond-> {:ok?        true
                                                     :sub-id     sub-id
                                                     :topic      topic
                                                     :delivered  @delivered
                                                     :overflow   @overflow*
                                                     :ticks      @tick
                                                     :reason     reason}
                                              (pos? @dropped-sensitive*)
                                              (assoc :dropped-sensitive @dropped-sensitive*))))))))
                              poll
                              (fn poll []
                                (cond
                                  ;; Client cancelled the tools/call.
                                  (and signal (.-aborted signal))
                                  (terminate :aborted)

                                  ;; Caller-supplied upper bounds.
                                  (and (pos? max-events)
                                       (>= @delivered max-events))
                                  (terminate :max-events-reached)

                                  :else
                                  (-> (nrepl/cljs-eval-value
                                        conn build-id
                                        (str "(re-frame-pair2.runtime/drain-subscription! "
                                             (pr-str sub-id) ")"))
                                      (.then
                                        (fn [drain-resp]
                                          (cond
                                            (:gone? drain-resp)
                                            (terminate :sub-gone)

                                            :else
                                            (let [raw-evts       (:events drain-resp)
                                                  ov             (:overflow drain-resp 0)
                                                  [evts dropped] (strip-sensitive
                                                                   (vec raw-evts) incl?)
                                                  n              (count evts)]
                                              (swap! overflow* + ov)
                                              (when (pos? dropped)
                                                (swap! dropped-sensitive* + dropped))
                                              (when (or (pos? n) (pos? ov))
                                                (swap! tick inc)
                                                (swap! delivered + n)
                                                (when (and send-note progress-tk)
                                                  (try
                                                    (send-note
                                                      #js {:method "notifications/progress"
                                                           :params (progress-payload
                                                                     progress-tk
                                                                     @tick
                                                                     (pr-str
                                                                       (cond-> {:sub-id sub-id
                                                                                :events evts
                                                                                :overflow ov}
                                                                         (pos? dropped)
                                                                         (assoc :dropped-sensitive dropped)))
                                                                     ov)})
                                                    (catch :default _ nil))))
                                              (js/setTimeout poll poll-ms)))))
                                      (.catch
                                        (fn [_err]
                                          ;; nREPL hiccup — back off
                                          ;; and try again rather than
                                          ;; collapsing the stream.
                                          (js/setTimeout poll (* 2 poll-ms)))))))]
                          ;; Optional max-ms hard cap.
                          (when (pos? max-ms)
                            (js/setTimeout #(terminate :max-ms-reached) max-ms))
                          (poll))))))))
            (.catch (fn [err] (err->result :subscribe-failed err))))))))

(defn- unsubscribe-tool [conn args]
  (let [build-id (arg-build args)
        sub-id   (arg args :sub-id)]
    (cond
      (or (nil? sub-id) (str/blank? sub-id))
      (js/Promise.resolve
        (err-text {:ok? false :reason :missing-sub-id
                   :hint "usage: unsubscribe {sub-id '<uuid>'}"}))

      :else
      (let [form (str "(re-frame-pair2.runtime/unsubscribe! "
                      (pr-str sub-id) ")")]
        (-> (ensure-runtime! conn build-id)
            (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
            (.then (fn [v] (ok-text (merge {:ok? true :sub-id sub-id}
                                           (when (map? v) v)))))
            (.catch (fn [err] (err->result :unsubscribe-failed err))))))))

;; ---------------------------------------------------------------------------
;; Tool descriptors — exposed via tools/list.
;; ---------------------------------------------------------------------------

(def tool-descriptors
  [{:name "discover-app"
    :description "Verify the shadow-cljs nREPL is reachable, confirm the pair2 runtime preload landed, and report a health summary. Run this first every session. Returns :reason :runtime-not-preloaded when the preload entry is missing."
    :inputSchema {:type "object"
                  :properties {:build {:type "string"
                                       :description "shadow-cljs build id (default: app)"}}
                  :additionalProperties false}}
   {:name "eval-cljs"
    :description "Evaluate a ClojureScript form in the connected browser runtime via shadow-cljs's cljs-eval. Returns the EDN value."
    :inputSchema {:type "object"
                  :properties {:form  {:type "string" :description "The CLJS form to evaluate."}
                               :build {:type "string" :description "shadow-cljs build id (default: app)"}}
                  :required ["form"]
                  :additionalProperties false}}
   {:name "dispatch"
    :description "Fire a re-frame2 event tagged with :origin :pair. Default mode is queued dispatch. Set `sync` for dispatch-sync, `trace` for synchronous dispatch returning the assembled :rf/epoch-record."
    :inputSchema {:type "object"
                  :properties {:event {:type "string" :description "The event vector, e.g. [:cart/checkout]"}
                               :sync  {:type "boolean"}
                               :trace {:type "boolean"}
                               :frame {:type "string" :description "Operating frame (e.g. :stories)"}
                               :fx-overrides {:type "object"
                                              :description "Per-call fx redirects, e.g. {:http :stub-http}"}
                               :build {:type "string"}}
                  :required ["event"]
                  :additionalProperties false}}
   {:name "trace-window"
    :description (str "Return the :rf/epoch-records added in the last N ms for the operating frame. "
                      "Per spec/009 §Privacy this forwarder default-drops items carrying `:sensitive? true` "
                      "at the top level; opt back in with `include-sensitive? true`. Dropped count surfaces "
                      "as `:dropped-sensitive` on the result when non-zero.")
    :inputSchema {:type "object"
                  :properties {:ms    {:type "integer" :description "Window size in milliseconds (default 1000)"}
                               :frame {:type "string"}
                               :include-sensitive? {:type "boolean"
                                                    :description "Opt back in to forwarding `:sensitive? true` items. Default false."}
                               :build {:type "string"}}
                  :additionalProperties false}}
   {:name "watch-epochs"
    :description (str "Pull-mode poll: returns the epochs matching `pred` that landed after `since-id`. "
                      "Call repeatedly to live-watch. Predicate keys: :event-id, :event-id-prefix, :effects, "
                      ":touches-path, :sub-ran, :render, :origin, :frame. Per spec/009 §Privacy this forwarder "
                      "default-drops items carrying `:sensitive? true`; opt back in with `include-sensitive? true`.")
    :inputSchema {:type "object"
                  :properties {:since-id {:type "string" :description "The last epoch id you've seen (omit to start fresh)"}
                               :pred     {:type "object" :description "Filter map"}
                               :frame    {:type "string"}
                               :include-sensitive? {:type "boolean"
                                                    :description "Opt back in to forwarding `:sensitive? true` items. Default false."}
                               :build    {:type "string"}}
                  :additionalProperties false}}
   {:name "tail-build"
    :description "Wait for a hot-reload to land by polling a probe form until its value changes. Returns once changed, or times out."
    :inputSchema {:type "object"
                  :properties {:probe   {:type "string" :description "CLJS form whose value should change after the reload"}
                               :wait-ms {:type "integer" :description "Max wait in ms (default 5000)"}
                               :build   {:type "string"}}
                  :additionalProperties false}}
   {:name "snapshot"
    :description (str "Coarse-grained per-frame state read in one round-trip — the mega-op for investigate-X workflows. "
                      "Returns a map keyed by frame-id whose values carry the requested slices: "
                      ":app-db, :sub-cache, :machines, :epochs, :traces. "
                      "Server-side composition over the existing per-slice runtime readers. "
                      "Prefer this over chaining 5-10 individual reads. "
                      "Per spec/009 §Privacy the `:traces` and `:epochs` slices default-drop items carrying "
                      "`:sensitive? true`; opt back in with `include-sensitive? true`. App-db / sub-cache / "
                      "machines slices pass through unchanged — payload redaction is the `with-redacted` "
                      "interceptor's job, not the forwarder's.")
    :inputSchema {:type "object"
                  :properties {:frames  {:description "Frames to snapshot. Pass \"all\" (default) or an array of frame-id strings like [\":rf/default\", \":stories\"]."
                                         :oneOf [{:type "string"}
                                                 {:type "array" :items {:type "string"}}]}
                               :include {:type "array"
                                         :description "Slices to include. Defaults to all five. Recognised: app-db, sub-cache, machines, epochs, traces."
                                         :items {:type "string"
                                                 :enum ["app-db" "sub-cache" "machines" "epochs" "traces"]}}
                               :include-sensitive? {:type "boolean"
                                                    :description "Opt back in to forwarding `:sensitive? true` items in the :traces / :epochs slices. Default false."}
                               :build   {:type "string" :description "shadow-cljs build id (default: app)"}}
                  :additionalProperties false}}
   {:name "subscribe"
    :description (str "Open a streaming subscription on the trace or epoch bus. Push-mode replacement for watch-epochs. "
                      "Long-running tools/call — emits each batch of matching events as a notifications/progress notification "
                      "(correlated via the call's progressToken), and resolves with a summary when the client cancels or an "
                      "unsubscribe op fires. Topics: 'trace' (raw trace stream), 'epoch' (assembled :rf/epoch-records), "
                      "'fx' (trace stream filtered to :op-type :fx), 'error' (trace stream filtered to :op-type :error). "
                      "Filter vocab depends on topic — :trace/:fx/:error accept the (rf/trace-buffer) filter map "
                      "(:operation :op-type :frame :severity :event-id :handler-id :source :origin :dispatch-id :since-ms :between); "
                      ":epoch accepts the epoch-matches? predicate map (:event-id :event-id-prefix :effects :touches-path "
                      ":sub-ran :render :origin :frame). Pass `filter` either as a JSON object or as an EDN-encoded string. "
                      "Per spec/009 §Privacy this forwarder default-drops events carrying `:sensitive? true` at the top "
                      "level; opt back in with `include-sensitive? true`. Dropped count surfaces as `:dropped-sensitive` "
                      "on each progress payload (when non-zero) and the final summary.")
    :inputSchema {:type "object"
                  :properties {:topic   {:type "string"
                                         :description "Topic name. Required."
                                         :enum ["trace" "epoch" "fx" "error"]}
                               :filter  {:description "Filter map (JSON object) or EDN string. Vocab depends on topic."
                                         :oneOf [{:type "object"}
                                                 {:type "string"}]}
                               :max-buffered {:type "integer"
                                              :description "Runtime-side queue cap. Default 500. Overflow is counted, not blocked."}
                               :poll-ms {:type "integer"
                                         :description "Server poll cadence in ms. Default 100."}
                               :max-ms  {:type "integer"
                                         :description "Hard upper-bound on how long the subscription stays open, ms. 0 = unbounded (close on cancel only). Default 0."}
                               :max-events {:type "integer"
                                            :description "Terminate after this many events have been delivered. 0 = unbounded. Default 0."}
                               :include-sensitive? {:type "boolean"
                                                    :description "Opt back in to forwarding `:sensitive? true` items. Default false."}
                               :build   {:type "string"}}
                  :required ["topic"]
                  :additionalProperties false}}
   {:name "unsubscribe"
    :description "Close the subscription with the given sub-id. Idempotent — closing an unknown sub-id returns :existed? false."
    :inputSchema {:type "object"
                  :properties {:sub-id {:type "string"
                                        :description "The uuid returned by `subscribe`."}
                               :build  {:type "string"}}
                  :required ["sub-id"]
                  :additionalProperties false}}])

(defn tool-descriptors-js []
  (clj->js tool-descriptors))

(defn invoke
  "Dispatch a `tools/call` invocation to the right tool implementation.
  Returns a Promise resolving to the MCP result object. Unknown tools
  resolve to an isError result rather than throwing — keeps the server
  loop simple.

  `extra` carries the MCP `extra` payload (signal + sendNotification +
  _meta.progressToken) for streaming tools. Non-streaming tools
  ignore it."
  [conn name args extra]
  (case name
    "discover-app"     (discover-app   conn args)
    "eval-cljs"        (eval-cljs-tool conn args)
    "dispatch"         (dispatch-tool  conn args)
    "trace-window"     (trace-window-tool conn args)
    "watch-epochs"     (watch-epochs-tool conn args)
    "tail-build"       (tail-build-tool conn args)
    "snapshot"         (snapshot-tool  conn args)
    "subscribe"        (subscribe-tool conn args extra)
    "unsubscribe"      (unsubscribe-tool conn args)
    (js/Promise.resolve
      (err-text {:ok? false :reason :unknown-tool :tool name}))))
