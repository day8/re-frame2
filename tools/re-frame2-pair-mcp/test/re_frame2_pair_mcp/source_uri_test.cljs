(ns re-frame2-pair-mcp.source-uri-test
  "Unit tests for the source-URI decorator: every map carrying a usable
  source coordinate — a `:source-coord` sub-map, or flat `:file` plus
  `:ns`/`:line`/`:column` keys (the handler-meta shape) — anywhere in the
  payload tree gets a sibling `:rf.mcp/source-uri`, built for the live
  editor preference. URI formats are `re-frame.source-coords.editor-uri`'s."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame2-pair-mcp.config :as config]
            [re-frame2-pair-mcp.tools.source-uri :as source-uri]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]))

(deftest config-defaults-to-vscode
  ;; RE_FRAME2_PAIR_MCP_EDITOR is unset in the test process.
  (is (= :vscode (config/get-editor))))

(def ^:private sample-coord
  {:ns 'app.events :file "src/app/events.cljs" :line 42 :column 7})

(def ^:private vscode-uri "vscode://file/src/app/events.cljs:42:7")

(deftest decorate-splices-uri-on-every-nested-carrier
  (let [carrier   {:source-coord sample-coord}
        decorated (assoc carrier :rf.mcp/source-uri vscode-uri)]
    (is (= {:source-coord sample-coord :rf.mcp/source-uri vscode-uri
            :epochs [{:handler-meta decorated}]
            :seq    (list decorated)
            :set    #{decorated}}
           (source-uri/decorate {:source-coord sample-coord
                                 :epochs [{:handler-meta carrier}]
                                 :seq    (list carrier)
                                 :set    #{carrier}}
                                :vscode)))))

(deftest decorate-skips-maps-without-a-usable-coord
  ;; No `:file`, a blank one, or a non-map coord yields no URI key rather
  ;; than a nil slot; a bare `:file` (a tool arg map, say) is not a carrier.
  (doseq [v [{:event-id :x :source-coord {:ns 'app.events :line 42 :column 7}}
             {:event-id :x :source-coord (assoc sample-coord :file "")}
             {:source-coord nil}
             {:file "src/app/events.cljs"}
             (assoc sample-coord :file "")]]
    (is (= v (source-uri/decorate v :vscode)) (pr-str v))))

(deftest decorate-splices-uri-on-flat-coord-carrier-map
  (let [v (assoc sample-coord :ok? true :kind :event :id :user/login :doc "Sign the user in.")]
    (is (= (assoc v :rf.mcp/source-uri vscode-uri) (source-uri/decorate v :vscode)))))

(defn- with-editor [editor f]
  (let [prior (config/get-editor)]
    (try
      (config/set-editor! editor)
      (f)
      (finally
        (config/set-editor! prior)))))

(deftest pipeline-respects-live-editor
  ;; The pipeline decorates after every per-kind arm, reading the editor
  ;; preference at decoration time.
  (with-editor :cursor
    #(let [epochs [{:event-id :a :source-coord sample-coord}
                   {:event-id :b :source-coord (assoc sample-coord :file "src/app/subs.cljs")}]]
       (is (= "cursor://file/src/app/events.cljs:42:7"
              (:rf.mcp/source-uri (:value (wp/run-wire-pipeline {:source-coord sample-coord}
                                                                {:kind          :scalar-value
                                                                 :server-elided 0})))))
       (is (= ["cursor://file/src/app/events.cljs:42:7" "cursor://file/src/app/subs.cljs:42:7"]
              (mapv :rf.mcp/source-uri
                    (:value (wp/run-wire-pipeline epochs {:kind   :epoch-vector
                                                          :incl?  false
                                                          :mode   :diff
                                                          :dedup? false}))))))))
