(ns re-frame2-pair-mcp.read-ui-test
  "Unit tests for the typed ui/read op — read-ui: the
  `(re-frame2-pair.runtime/ui-read {...})` form it composes from the MCP
  args, and the envelope it forwards. The read itself runs browser-side."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.read-ui :as read-ui]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- with-captured-form!
  "Stub `cljs-eval-value` to record the emitted form into `seen` and
  resolve to `canned`, run `body-fn`, then restore."
  [seen canned body-fn]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build form] (reset! seen form) (js/Promise.resolve canned))
               ([_conn _build form _opts] (reset! seen form) (js/Promise.resolve canned)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(deftest form-carries-the-entry-point-and-opts
  ;; A non-string :frame from a malformed client is dropped, not thrown on.
  (async done
    (-> (reduce
          (fn [p [args expected]]
            (.then p (fn [_]
                       (let [seen (atom nil)]
                         (-> (with-captured-form! seen {:ok? true}
                               #(read-ui/read-ui-tool (fresh-conn) args))
                             (.then (fn [_]
                                      (is (= expected (cljs.reader/read-string @seen))
                                          (pr-str expected)))))))))
          (js/Promise.resolve nil)
          [[#js {:view-id ":my.app/counter"}
            '(re-frame2-pair.runtime/ui-read {:max-text 2000 :view-id :my.app/counter})]
           [#js {:point #js {:x 12 :y 34} :max-text 100 :frame ":stories"}
            '(re-frame2-pair.runtime/ui-read {:max-text 100 :point {:x 12 :y 34} :frame :stories})]
           [#js {:selector "#save" :frame 42}
            '(re-frame2-pair.runtime/ui-read {:max-text 2000 :selector "#save"})]])
        (.catch (fn [e] (is false (str "drive rejected: " e))))
        (.then (fn [_] (done))))))

;; ---------------------------------------------------------------------------
;; Tool wiring — the runtime envelope rides back :build-stamped.
;; ---------------------------------------------------------------------------

(deftest happy-returns-entity-and-content
  (async done
    (let [canned {:ok? true :via :view-id
                  :entity {:view-id :my.app/counter
                           :source-coord {:ns "my.app" :handler-id "counter"
                                          :line 42 :col 3
                                          :file "/abs/my/app.cljs"}
                           :render-key 8123
                           :subs-read [[:count] [:user]]}
                  :content {:tag "div" :text "Count: 3"
                            :attrs {"class" "counter" "data-count" "3"}}}]
      (-> (tu/with-stubbed-eval! canned
            #(read-ui/read-ui-tool (fresh-conn) #js {:view-id ":my.app/counter"}))
          (.then (fn [r]
                   (is (= [false (assoc canned :build :app)]
                          [(tu/error? r) (tu/extract-edn r)]))
                   (done)))))))

(deftest bad-selector-error-forwarded
  ;; Every :ok? false is isError (spec/003-Tool-Catalogue.md), which also
  ;; keeps a transient failure out of the response cache.
  (async done
    (let [canned {:ok? false :reason :rf.error/ui-read-bad-selector
                  :message "bad selector"}]
      (-> (tu/with-stubbed-eval! canned
            #(read-ui/read-ui-tool (fresh-conn) #js {:selector "###"}))
          (.then (fn [r]
                   (is (= [true (assoc canned :build :app)]
                          [(tu/error? r) (tu/extract-edn r)]))
                   (done)))))))

(deftest blank-eval-result-becomes-structured-error-not-host-failure
  (async done
    (-> (tu/with-stubbed-eval! nil
          #(read-ui/read-ui-tool (fresh-conn) #js {:selector "body"}))
        (.then (fn [r]
                 (is (= [true {:ok? false :reason :rf.error/read-ui-blank-result :build :app}]
                        [(tu/error? r) (dissoc (tu/extract-edn r) :hint)]))
                 (done))))))
