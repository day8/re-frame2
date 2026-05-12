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
  (let [ms       (or (arg args :ms) 1000)
        build-id (arg-build args)
        frame    (some-> (arg args :frame) keyword)
        form     (if frame
                   (str "(re-frame-pair2.runtime/epochs-in-last-ms " ms " " (pr-str frame) ")")
                   (str "(re-frame-pair2.runtime/epochs-in-last-ms " ms ")"))]
    (-> (ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [epochs]
                 (ok-text {:ok? true
                           :window-ms ms
                           :count (count epochs)
                           :epochs epochs})))
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
                 (ok-text (merge {:ok? true} (when (map? v) v)))))
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

(defn- snapshot-tool [conn args]
  (let [build-id (arg-build args)
        frames   (parse-frames-arg (arg args :frames))
        include  (parse-include-arg (arg args :include))
        opts     {:frames frames :include include}
        form     (str "(re-frame-pair2.runtime/snapshot-state "
                      (pr-str opts) ")")]
    (-> (ensure-runtime! conn build-id)
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id form)))
        (.then (fn [v]
                 (ok-text {:ok?      true
                           :frames   (if (= :all frames) :all (vec frames))
                           :include  include
                           :snapshot v})))
        (.catch (fn [err] (err->result :snapshot-failed err))))))

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
    :description "Return the :rf/epoch-records added in the last N ms for the operating frame."
    :inputSchema {:type "object"
                  :properties {:ms    {:type "integer" :description "Window size in milliseconds (default 1000)"}
                               :frame {:type "string"}
                               :build {:type "string"}}
                  :additionalProperties false}}
   {:name "watch-epochs"
    :description "Pull-mode poll: returns the epochs matching `pred` that landed after `since-id`. Call repeatedly to live-watch. Predicate keys: :event-id, :event-id-prefix, :effects, :touches-path, :sub-ran, :render, :origin, :frame."
    :inputSchema {:type "object"
                  :properties {:since-id {:type "string" :description "The last epoch id you've seen (omit to start fresh)"}
                               :pred     {:type "object" :description "Filter map"}
                               :frame    {:type "string"}
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
                      "Prefer this over chaining 5-10 individual reads.")
    :inputSchema {:type "object"
                  :properties {:frames  {:description "Frames to snapshot. Pass \"all\" (default) or an array of frame-id strings like [\":rf/default\", \":stories\"]."
                                         :oneOf [{:type "string"}
                                                 {:type "array" :items {:type "string"}}]}
                               :include {:type "array"
                                         :description "Slices to include. Defaults to all five. Recognised: app-db, sub-cache, machines, epochs, traces."
                                         :items {:type "string"
                                                 :enum ["app-db" "sub-cache" "machines" "epochs" "traces"]}}
                               :build   {:type "string" :description "shadow-cljs build id (default: app)"}}
                  :additionalProperties false}}])

(defn tool-descriptors-js []
  (clj->js tool-descriptors))

(defn invoke
  "Dispatch a `tools/call` invocation to the right tool implementation.
  Returns a Promise resolving to the MCP result object. Unknown tools
  resolve to an isError result rather than throwing — keeps the server
  loop simple."
  [conn name args]
  (case name
    "discover-app"     (discover-app   conn args)
    "eval-cljs"        (eval-cljs-tool conn args)
    "dispatch"         (dispatch-tool  conn args)
    "trace-window"     (trace-window-tool conn args)
    "watch-epochs"     (watch-epochs-tool conn args)
    "tail-build"       (tail-build-tool conn args)
    "snapshot"         (snapshot-tool  conn args)
    (js/Promise.resolve
      (err-text {:ok? false :reason :unknown-tool :tool name}))))
