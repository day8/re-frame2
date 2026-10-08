(ns re-frame2-pair-mcp.handler-meta-test
  "Unit tests for the `handler-meta` + `list-handlers` MCP tools.

  Both build a form that calls the preloaded runtime and NOTHING ELSE. That
  coupling is a string shipped over nREPL: no `:require`, no compiler error
  and no static check in this build can see it, so emitter tests alone stay
  green over a var that exists nowhere. The runtime-door tests at the end
  read the preload's own source to close that gap, in the shape
  `fresco_wire_test.cljs` uses for the same class of string coupling."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.registry :as registry]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.handler-meta :as hm]))

(def ^:private args-js tu/args->js)
(def ^:private extract-edn tu/extract-edn)
(def ^:private is-error? tu/error?)

;; Stubs are installed by a bare `set!` and restored by this fixture, not by
;; a per-call `.finally`: that can land after `done` and clobber the next
;; test's freshly installed stub.
(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn [] (set! nrepl/cljs-eval-value pristine-eval))})

(defn- with-form-capture!
  "Stub the runtime: the preload probe answers true; any other form is
  captured into `form-atom` and answered with `canned`."
  [form-atom canned body-fn]
  (let [respond (fn [form-str]
                  (if (re-find #"__re_frame2_pair_runtime" form-str)
                    (js/Promise.resolve true)
                    (do (reset! form-atom form-str) (js/Promise.resolve canned))))
        stub    (fn
                  ([_conn _build-id form-str] (respond form-str))
                  ([_conn _build-id form-str _opts] (respond form-str)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn))))))

(defn- run-tool
  "Run `tool` on `args` against a runtime answering `canned`; resolve to
  `[shipped-form result]`."
  [tool args canned]
  (let [form (atom nil)]
    (-> (with-form-capture! form canned #(tool nil (args-js args)))
        (.then (fn [r] [@form r])))))

(defn- settle
  "Finish the async test once `p` settles, failing it on a rejection."
  [p done]
  (-> p
      (.catch (fn [e] (is false (str "rejected: " (.-message e)))))
      (.then (fn [_] (done)))))

;; ---------------------------------------------------------------------------
;; Descriptors.
;; ---------------------------------------------------------------------------

(defn- find-descriptor [name]
  (some #(when (= name (:name %)) %) registry/tool-descriptors))

(def ^:private kinds
  "The published kind vocabulary. `flow` and `frame` are reserved-but-EMPTY
  registrar slots the framework refuses to query, so neither is offered."
  #{"event" "sub" "fx" "cofx" "interceptor" "view" "route" "head"
    "error-projector" "resource" "mutation" "resource-scope" "machine"})

(deftest descriptors-advertise-the-accepted-kinds-and-an-optional-frame
  (is (= kinds (set (map name @#'hm/supported-kinds)))
      "the tools accept exactly the kinds the descriptors advertise")
  (doseq [[tool-name required] [["handler-meta" #{"kind" "id"}]
                                ["list-handlers" #{"kind"}]]]
    (let [{req :required props :properties} (:inputSchema (find-descriptor tool-name))]
      (is (= required (set req)) tool-name)
      (is (= kinds (set (:enum (:kind props)))) tool-name)
      (is (contains? props :frame) (str tool-name " takes an optional :frame")))))

;; ---------------------------------------------------------------------------
;; Argument refusals, before any runtime round-trip. The missing-kind
;; envelope of both tools is pinned by the corpus fixtures.
;; ---------------------------------------------------------------------------

(deftest handler-meta-rejects-missing-id
  (async done
    (settle (-> (hm/handler-meta-tool nil (args-js {:kind "event"}))
                (.then (fn [r]
                         (is (is-error? r))
                         (is (= :missing-id (:reason (extract-edn r)))))))
            done)))

(deftest list-handlers-refuses-reserved-empty-kinds
  ;; Querying `flow` / `frame` throws at the framework and the preload does
  ;; not catch, so offering either would answer with a throw, not an envelope.
  (async done
    (settle (js/Promise.all
              (into-array
                (for [k ["flow" "frame"]]
                  (-> (hm/list-handlers-tool nil (args-js {:kind k}))
                      (.then (fn [r]
                               (is (is-error? r) k)
                               (is (= {:ok? false :reason :invalid-kind :kind k}
                                      (dissoc (extract-edn r) :hint)))))))))
            done)))

(deftest handler-meta-rejects-frame-with-machine
  ;; Machines are not in the image generation resolver.
  (async done
    (settle (-> (hm/handler-meta-tool nil (args-js {:kind  "machine"
                                                    :id    ":auth/session"
                                                    :frame ":blue/main"}))
                (.then (fn [r]
                         (is (is-error? r))
                         (is (= {:ok? false :reason :frame-unsupported-for-machine
                                 :frame :blue/main :kind :machine}
                                (dissoc (extract-edn r) :hint))))))
            done)))

(deftest list-handlers-rejects-frame-with-machine
  (async done
    (settle (-> (hm/list-handlers-tool nil (args-js {:kind "machine" :frame ":blue/main"}))
                (.then (fn [r]
                         (is (is-error? r))
                         (is (= {:ok? false :reason :frame-unsupported-for-machine
                                 :frame :blue/main :kind :machine}
                                (dissoc (extract-edn r) :hint))))))
            done)))

;; ---------------------------------------------------------------------------
;; Routing and result shaping. The default registrar paths of both tools are
;; pinned by the corpus fixtures `:handler-meta/happy` / `:list-handlers/happy`.
;; ---------------------------------------------------------------------------

(deftest handler-meta-frame-routes-through-frame-registrar-describe
  ;; `:frame` re-keys the lookup through that frame's own image generation.
  (async done
    (let [canned {:ns 'blue.core :line 1 :handler-fn-hash 7
                  :rf.image/coordinate {:source :registered :ns "blue.core"}}]
      (settle (-> (run-tool hm/handler-meta-tool
                            {:kind "event" :id ":counter/inc" :frame ":blue/main"} canned)
                  (.then (fn [[form r]]
                           (is (str/includes? form (str "(re-frame2-pair.runtime/frame-registrar-describe"
                                                        " :blue/main :event (quote :counter/inc))")))
                           (is (= (assoc canned :ok? true :kind :event :id :counter/inc :frame :blue/main)
                                  (extract-edn r))
                               "the frame is stamped and the provenance coordinate rides through"))))
              done))))

(deftest list-handlers-frame-routes-through-frame-registrar-list
  (async done
    (settle (-> (run-tool hm/list-handlers-tool {:kind "event" :frame ":blue/main"} [:counter/inc])
                (.then (fn [[form r]]
                         (is (str/includes? form "(re-frame2-pair.runtime/frame-registrar-list :blue/main :event)"))
                         (is (= {:ok? true :kind :event :ids [:counter/inc] :count 1 :frame :blue/main}
                                (extract-edn r))))))
            done)))

(deftest handler-meta-machine-routes-through-the-runtime-door
  ;; The machine query surface is not on the facade, so this is the branch
  ;; most likely to name a var that exists nowhere.
  (async done
    (let [canned {:initial :idle :states {:idle {}} :guards {:can? :rf/fn}}]
      (settle (-> (run-tool hm/handler-meta-tool {:kind "machine" :id ":auth/session"} canned)
                  (.then (fn [[form r]]
                           (is (str/includes? form "(re-frame2-pair.runtime/machine-describe (quote :auth/session))"))
                           (is (= (assoc canned :ok? true :kind :machine :id :auth/session)
                                  (extract-edn r))
                               "the door's stripped :guards ride through as readable EDN"))))
              done))))

(deftest handler-meta-machine-miss-is-the-uniform-not-registered-envelope
  ;; The door's own `:not-a-machine` is renamed, so an agent branches on ONE
  ;; miss reason across every kind.
  (async done
    (settle (-> (run-tool hm/handler-meta-tool {:kind "machine" :id ":nope/nothing"}
                          {:ok? false :reason :not-a-machine :id :nope/nothing})
                (.then (fn [[_ r]]
                         (is (= {:ok? false :reason :not-registered :kind :machine :id :nope/nothing}
                                (extract-edn r))))))
            done)))

(deftest handler-meta-unserializable-surfaces-structured
  ;; The runtime codec tags a meta map that cannot round-trip as EDN; the
  ;; tool stamps the request onto that error rather than shipping the map
  ;; as a string.
  (async done
    (let [tagged {:rf.mcp/result :unserializable
                  :type          "object"
                  :preview       "{:ns testdeck.counter :handler-fn #object[Function]}"}]
      (settle (-> (run-tool hm/handler-meta-tool {:kind "event" :id ":counter/inc"} tagged)
                  (.then (fn [[_ r]]
                           (is (is-error? r))
                           (is (= {:ok?     false
                                   :reason  :rf.error/unserializable
                                   :type    "object"
                                   :preview (:preview tagged)
                                   :kind    :event
                                   :id      :counter/inc}
                                  (dissoc (extract-edn r) :hint))))))
              done))))

(deftest handler-meta-genuinely-unparseable-still-fails
  ;; A non-map answer is a defect, not a miss, so it rides isError rather
  ;; than as a success envelope carrying `:ok? false`.
  (async done
    (settle (-> (run-tool hm/handler-meta-tool {:kind "event" :id ":anything"} 42)
                (.then (fn [[_ r]]
                         (is (is-error? r))
                         (is (= {:ok? false :reason :unexpected-shape :kind :event :id :anything :value 42}
                                (extract-edn r))
                             "the offending value rides on :value for forensics"))))
            done)))

;; ---------------------------------------------------------------------------
;; THE RUNTIME DOOR — the both-sides witness. The symbols come from forms the
;; tools actually emit: the string on the wire is what has to resolve.
;; ---------------------------------------------------------------------------

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- repo-root
  "Walk upward from the test process's cwd to the repository root — the first
  directory holding both this artefact and the preload the emitted forms call.
  Robust against the runner's working directory (`tools/re-frame2-pair-mcp` vs
  repo root)."
  []
  (loop [d (.cwd js/process)]
    (cond
      (and (.existsSync fs (.join path d "tools/re-frame2-pair-mcp/src"))
           (.existsSync fs (.join path d "skills/re-frame2-pair/preload")))
      d

      (= d (.dirname path d))
      (throw (ex-info (str "Could not locate the repository root from cwd — the runtime-door "
                           "witness needs both tools/re-frame2-pair-mcp/src and "
                           "skills/re-frame2-pair/preload to compare the two sides.")
                      {:cwd (.cwd js/process)}))

      :else (recur (.dirname path d)))))

(def ^:private preload-src
  ;; CR stripped: the `\n(defn ` anchor below is line-start-anchored, and a
  ;; CRLF checkout would otherwise fail every assertion for a reason that has
  ;; nothing to do with the door.
  (delay
    (let [rel  "skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs"
          full (.join path (repo-root) rel)]
      (when-not (.existsSync fs full)
        (throw (ex-info (str "the runtime preload is missing: " rel
                             " — every form these tools ship targets it by string, so its "
                             "absence is the failure this witness exists to report.")
                        {:path full})))
      (str/replace (.toString (.readFileSync fs full)) "\r" ""))))

(def ^:private door-cases
  "Every branch of the two tools' form builders that reaches a runtime call.
  `:machine` with a `:frame` is refused before a form is built, so it is not
  a case. `:canned` lets each tool's own post-processing run."
  [{:tool hm/handler-meta-tool  :args {:kind "event"   :id ":a/b"}                      :canned {:ns 'a.b :line 1}}
   {:tool hm/handler-meta-tool  :args {:kind "event"   :id ":a/b" :frame ":blue/main"}  :canned {:ns 'a.b :line 1}}
   {:tool hm/handler-meta-tool  :args {:kind "machine" :id ":auth/session"}             :canned {:initial :idle}}
   {:tool hm/list-handlers-tool :args {:kind "event"}                                   :canned [:a/b]}
   {:tool hm/list-handlers-tool :args {:kind "event"   :frame ":blue/main"}             :canned [:a/b]}
   {:tool hm/list-handlers-tool :args {:kind "machine"}                                 :canned [:auth/session]}])

(defn- capture-emitted-forms
  "The form strings the tools ship for every `door-cases` entry. Serial: the
  stub is one var, so two runs in flight would capture each other's forms."
  []
  (reduce
    (fn [p {:keys [tool args canned]}]
      (.then p (fn [acc]
                 (-> (run-tool tool args canned)
                     (.then (fn [[form _]] (conj acc form)))))))
    (js/Promise.resolve [])
    door-cases))

(defn- runtime-symbols
  "Every fn name a form resolves off the runtime preload, as a set. Parsed out
  of the emitted source rather than read off the DSL, because the source string
  is what crosses the wire."
  [form]
  (into #{}
        (map second)
        (re-seq (re-pattern (str "\\(" (str/replace ef/runtime-ns "." "\\.")
                                 "/([^\\s)]+)"))
                form)))

(deftest every-emitted-runtime-symbol-is-published-by-the-preload
  (async done
    (settle (-> (capture-emitted-forms)
                (.then (fn [forms]
                         (let [src  @preload-src
                               syms (into #{} (mapcat runtime-symbols) forms)]
                           ;; Controls: the extraction does find the machine doors.
                           (is (contains? syms "machine-describe"))
                           (is (contains? syms "machines-list"))
                           ;; A PUBLIC `defn` at column 0: a `defn-` is unreachable from
                           ;; an eval form. One set difference, not an `is` per symbol,
                           ;; so a failure does not print the whole preload.
                           (let [missing (into (sorted-set)
                                               (remove #(str/includes? src (str "\n(defn " % "\n")))
                                               syms)]
                             (is (empty? missing)
                                 (str "re-frame2-pair.runtime must publish every symbol an emitted "
                                      "form names — missing: " (pr-str (vec missing)))))))))
            done)))

(deftest no-emitted-form-names-a-framework-var
  ;; The preload is the SINGLE place a framework symbol is spelled. A form
  ;; reaching past it compiles, passes every emitter test, and fails only in
  ;; someone else's process.
  (async done
    (settle (-> (capture-emitted-forms)
                (.then (fn [forms]
                         (is (= [] (filterv #(re-find #"re-frame\.(?:core|machines|schemas|routing|flows)/" %)
                                            forms))
                             "route every framework read through the preload"))))
            done)))
