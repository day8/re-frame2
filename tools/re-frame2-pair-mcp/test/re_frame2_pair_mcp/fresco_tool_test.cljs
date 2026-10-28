(ns re-frame2-pair-mcp.fresco-tool-test
  "Unit tests for the three re-frame.fresco.tool reads:
  read-mounted-boundaries / read-read-attribution / explain-render.

  Each tool ships ONE self-describing form that RESOLVES
  `re-frame.fresco.tool` at runtime and calls the read off it, rather than
  routing through a `re-frame2-pair.runtime` wrapper: the door is optional,
  so the preload must not require it. The schema gate, the envelope
  passthrough and each tool's read name are pinned by the conformance
  corpus; that the provider publishes the reads these forms name is
  [[re-frame2-pair-mcp.fresco-wire-test]]; the analyzer half, against a
  build without the door, is `test/live-fresco-wire.cjs`."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.fresco-tool :as fresco-tool]))

(deftest projection-form-resolves-the-door-and-calls-it-nullary
  (let [form (fresco-tool/projection-form "read-mounted-boundaries")
        i    (str/index-of form ":evidence-tier-unavailable")]
    (is (str/includes? form
                       "((cljs.core/unchecked-get d (cljs.core/munge \"read-mounted-boundaries\")))")
        "the read is resolved off the namespace object and called NULLARY")
    (is (str/includes? (subs form i (min (count form) (+ i 600)))
                       "Load re-frame.fresco.tool into the running build and retry")
        "an absent door degrades to :evidence-tier-unavailable with the load-the-door instruction")
    (is (str/includes? form ":reason :evidence-tier-inactive")
        "a nil read is a production build — the door is dev-only")
    (is (str/includes? form "(cljs.core/assoc p :ok? true)")
        "a present projection is forwarded verbatim, stamped :ok? true")
    (is (str/includes? form "catch :default e")
        "a throwing read degrades to :evidence-tier-error")))

(deftest no-form-references-a-door-var-as-a-symbol
  ;; The regression fence. shadow's analyzer resolves a fully-qualified
  ;; `re-frame.fresco.tool/…` symbol before the form runs, so against an app
  ;; without the door the eval is a compile error and the unavailable rung is
  ;; dead. The door's name may ride only as a STRING.
  (is (nil? (re-find (re-pattern (str (str/replace fresco-tool/tier-ns "." "\\.")
                                      "/[a-zA-Z0-9?!*<>=+_-]"))
                     (fresco-tool/projection-form "read-mounted-boundaries")))))

;; ---------------------------------------------------------------------------
;; Rows under a reserved `:rf/*` tool frame are left out and counted. Xray
;; renders its own panels through Fresco, so on an app with no Fresco code an
;; open panel is the whole roster; forwarded, it overflows the default cap
;; with nothing the agent asked about.
;; ---------------------------------------------------------------------------

(defn- door-read!
  "Run `tool-fn` against a runtime whose door read answers `answer`."
  [tool-fn answer]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build-id form]
                (js/Promise.resolve (if (str/includes? form "__re_frame2_pair_runtime") true answer)))
               ([_conn _build-id form _opts]
                (js/Promise.resolve (if (str/includes? form "__re_frame2_pair_runtime") true answer))))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (tool-fn nil #js {})))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(defn- envelope [read rows-k rows]
  {:ok? true :schema fresco-tool/consumed-evidence-schema :producer :re-frame/fresco
   :read read :complete? true :loss nil rows-k rows})

(def ^:private reads
  ;; [tool, envelope :read, row slot, frame key on a row]
  [[fresco-tool/read-mounted-boundaries-tool :mounted-boundaries :boundaries :frame]
   [fresco-tool/read-read-attribution-tool :read-attribution :edges :frame-id]
   [fresco-tool/explain-render-tool :explain-render :explanations :frame]])

(deftest tool-frame-rows-are-excluded-and-counted
  ;; One read at a time: each installs its own eval stub.
  (async done
    (-> (reduce
          (fn [chain [tool-fn read rows-k frame-k]]
            (let [rows [{frame-k :rf/xray :n 1} {frame-k :app/main :n 2} {frame-k :rf/xray :n 3}
                        {frame-k :rf/default :n 4} {frame-k :unknown :n 5}]]
              (-> chain
                  (.then (fn [_] (door-read! tool-fn (envelope read rows-k rows))))
                  (.then (fn [result]
                             (let [edn (tu/extract-edn result)]
                               (is (not (tu/error? result)))
                               (is (= [2 4 5] (mapv :n (get edn rows-k)))
                                   (str read ": the app's rows, the :rf/default app frame and a "
                                        "cross-frame row stay; the tool frame's rows go"))
                               (is (= {:tool-frames [:rf/xray] :count 2}
                                      (select-keys (:excluded edn) [:tool-frames :count]))
                                   (str read ": the drop is stated, with its count"))
                               (is (str/includes? (str (get-in edn [:excluded :hint])) "eval-cljs")
                                   (str read ": the hint names the route to the withheld rows"))
                               (is (= {:complete? true :loss nil}
                                      (select-keys edn [:complete? :loss]))
                                   (str read ": the door's own claims are forwarded as stated"))))))))
          (js/Promise.resolve nil)
          reads)
        (.then (fn [_] (done))))))

(deftest a-roster-with-no-tool-frame-rows-is-forwarded-unchanged
  (async done
    (let [answer (envelope :mounted-boundaries :boundaries [{:frame :app/main :n 1}])]
      (-> (door-read! fresco-tool/read-mounted-boundaries-tool answer)
          (.then (fn [result]
                   (is (= answer (tu/extract-edn result)))
                   (done)))))))
