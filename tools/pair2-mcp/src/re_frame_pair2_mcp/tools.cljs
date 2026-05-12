(ns re-frame-pair2-mcp.tools
  "MCP tools — one per bash-shim op surface in
  `skills/re-frame-pair2/scripts/`. Each tool builds an nREPL eval
  request, sends it over the persistent connection, and returns the
  result as an MCP `tools/call` result.

  ## Tool catalogue (mirrors the seven bash shims)

  | MCP tool name | Bash shim equivalent      | What it does                             |
  |---------------|---------------------------|------------------------------------------|
  | discover-app  | discover-app.sh           | Verify nREPL + inject runtime + health   |
  | eval-cljs     | eval-cljs.sh              | Eval a CLJS form, return the value       |
  | inject-runtime| inject-runtime.sh         | Force re-ship of runtime.cljs            |
  | dispatch      | dispatch.sh               | Fire a re-frame2 event with :origin :pair|
  | trace-window  | trace-window.sh           | Epochs in the last N ms                  |
  | watch-epochs  | watch-epochs.sh           | Pull-mode live epoch streaming           |
  | tail-build    | tail-build.sh             | Wait for a hot-reload to land            |

  ## Sentinel-based reconnect

  Each tool that needs the in-browser runtime first calls
  `ensure-runtime!`. That probes `re-frame-pair2.runtime/session-id`;
  if the value is missing (typical after a full page reload), it
  re-ships `runtime.cljs` from the skill before proceeding. This
  mirrors the bash-shim's `runtime-already-injected?` check.

  ## Result shape

  Each MCP tool returns `{:content [{:type \"text\" :text <edn-string>}]}`
  on success, or `{:isError true :content [...]}` on failure. The EDN
  string is what the bash shims emit on stdout — kept identical so the
  SKILL.md prose that quotes example output stays accurate."
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [re-frame-pair2-mcp.nrepl :as nrepl]
            ["fs" :as fs]
            ["path" :as path]))

;; ---------------------------------------------------------------------------
;; Config — build id, runtime source location.
;; ---------------------------------------------------------------------------

(defn- default-build-id []
  (or (some-> (j/get-in js/process [:env :SHADOW_CLJS_BUILD_ID])
              keyword)
      :app))

(defn- runtime-source-path
  "Locate the canonical `runtime.cljs` from the skill. We support two
  layouts:
    1. Co-located: server is run from the re-frame2 worktree; the file
       sits at `skills/re-frame-pair2/scripts/runtime.cljs` relative to
       the project root.
    2. Override: the env var `PAIR2_RUNTIME_PATH` points at the file.
  Returns nil if neither is found — `discover-app` reports a structured
  error in that case."
  []
  (or (j/get-in js/process [:env :PAIR2_RUNTIME_PATH])
      (let [candidates ["skills/re-frame-pair2/scripts/runtime.cljs"
                        "../../skills/re-frame-pair2/scripts/runtime.cljs"
                        "../../../skills/re-frame-pair2/scripts/runtime.cljs"]]
        (some (fn [c] (when (.existsSync fs c) c)) candidates))))

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
;; Runtime injection / sentinel reconnect.
;; ---------------------------------------------------------------------------

(defn- runtime-injected?
  "Probe `re-frame-pair2.runtime/session-id`. Resolves to true iff a
  non-blank string is bound. Failures of any kind (var missing, eval
  error) resolve to false — the next step is to re-inject."
  [conn build-id]
  (-> (nrepl/cljs-eval-value conn build-id "re-frame-pair2.runtime/session-id")
      (.then (fn [v] (boolean (and (string? v) (seq v)))))
      (.catch (fn [_] false))))

(defn- inject-runtime!
  "Re-ship `runtime.cljs` to the connected runtime."
  [conn build-id]
  (if-let [src-path (runtime-source-path)]
    (let [src (.toString (.readFileSync fs src-path))]
      (-> (nrepl/cljs-eval conn build-id src)
          (.then (fn [_]
                   (nrepl/cljs-eval-value conn build-id
                                          "(re-frame-pair2.runtime/health)")))))
    (js/Promise.reject
      (js/Error. (str "runtime.cljs not found. Set PAIR2_RUNTIME_PATH or run "
                      "the MCP server from a directory where "
                      "skills/re-frame-pair2/scripts/runtime.cljs is reachable.")))))

(defn- ensure-runtime!
  "Probe the sentinel; if missing, re-inject. Returns a Promise
  resolving to nil. Tools that need the runtime call this first."
  [conn build-id]
  (-> (runtime-injected? conn build-id)
      (.then (fn [injected?]
               (if injected?
                 nil
                 (inject-runtime! conn build-id))))))

;; ---------------------------------------------------------------------------
;; Tool: discover-app — verify the stack and inject the runtime.
;; ---------------------------------------------------------------------------

(defn- discover-app [conn args]
  (let [build-id (arg-build args)]
    (-> (inject-runtime! conn build-id)
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
        (.catch
          (fn [err]
            (ok-text {:ok? false :reason :discover-failed
                      :message (.-message err)}))))))

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
          (.catch
            (fn [err]
              (ok-text {:ok? false :reason :eval-error
                        :message (.-message err)})))))))

;; ---------------------------------------------------------------------------
;; Tool: inject-runtime — force a re-ship.
;; ---------------------------------------------------------------------------

(defn- inject-runtime-tool [conn args]
  (let [build-id (arg-build args)]
    (-> (inject-runtime! conn build-id)
        (.then (fn [health]
                 (ok-text (assoc health :build-id build-id
                                        :forced? true
                                        :note "Source re-shipped regardless of sentinel."))))
        (.catch (fn [err]
                  (ok-text {:ok? false :reason :inject-failed
                            :message (.-message err)}))))))

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
            (.catch (fn [err]
                      (ok-text {:ok? false :reason :dispatch-failed
                                :message (.-message err)}))))))))

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
        (.catch (fn [err]
                  (ok-text {:ok? false :reason :trace-failed
                            :message (.-message err)}))))))

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
        (.catch (fn [err]
                  (ok-text {:ok? false :reason :watch-failed
                            :message (.-message err)}))))))

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
;; Tool descriptors — exposed via tools/list.
;; ---------------------------------------------------------------------------

(def tool-descriptors
  [{:name "discover-app"
    :description "Verify the shadow-cljs nREPL is reachable, inject the pair2 runtime, and report a health summary. Run this first every session."
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
   {:name "inject-runtime"
    :description "Force a re-ship of re-frame-pair2.runtime to the connected runtime. Use after editing scripts/runtime.cljs."
    :inputSchema {:type "object"
                  :properties {:build {:type "string"}}
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
    "inject-runtime"   (inject-runtime-tool conn args)
    "dispatch"         (dispatch-tool  conn args)
    "trace-window"     (trace-window-tool conn args)
    "watch-epochs"     (watch-epochs-tool conn args)
    "tail-build"       (tail-build-tool conn args)
    (js/Promise.resolve
      (err-text {:ok? false :reason :unknown-tool :tool name}))))
