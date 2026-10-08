(ns re-frame2-pair-mcp.fresco-tool-test
  "Unit tests for the three re-frame.fresco.tool reads:
  read-mounted-boundaries / read-read-attribution / explain-render.

  Each tool ships ONE self-describing form that RESOLVES
  `re-frame.fresco.tool` at runtime and calls the read off it, rather than
  routing through a `re-frame2-pair.runtime` wrapper: the door is optional,
  so the preload must not require it. The schema gate and the envelope
  passthrough are pinned by the conformance corpus; that the provider
  publishes the reads these forms name is
  [[re-frame2-pair-mcp.fresco-wire-test]]; the analyzer half, against a
  build without the door, is `test/live-fresco-wire.cjs`."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.fresco-tool :as fresco-tool]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- with-captured-form!
  [seen canned body-fn]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build form] (reset! seen form) (js/Promise.resolve canned))
               ([_conn _build form _opts] (reset! seen form) (js/Promise.resolve canned)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

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

(deftest each-tool-emits-a-runtime-resolved-door-call
  ;; Each tool sends its read's projection form bare — no preload wrapper.
  (async done
    (let [seen   (atom nil)
          canned {:ok? true :schema fresco-tool/consumed-evidence-schema}]
      (-> (reduce
            (fn [p [read-fn tool-fn]]
              (.then p (fn [_]
                         (-> (with-captured-form! seen canned
                               (fn [] (tool-fn (fresh-conn) #js {})))
                             (.then (fn [_]
                                      (is (= (fresco-tool/projection-form read-fn) @seen)
                                          read-fn)))))))
            (js/Promise.resolve nil)
            [["read-mounted-boundaries" fresco-tool/read-mounted-boundaries-tool]
             ["read-read-attribution"   fresco-tool/read-read-attribution-tool]
             ["explain-render"          fresco-tool/explain-render-tool]])
          (.then (fn [_] (done)))))))
