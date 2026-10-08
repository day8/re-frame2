(ns re-frame2-pair-mcp.replace-app-db-test
  "The replace-app-db write tool: refused without touching the runtime
  while `--allow-writes` is off, the `db` value and the frame reach
  `app-db-reset!` as data, and a non-map runtime answer is an error."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.writes :as writes]
            [re-frame2-pair-mcp.tools.replace-app-db :as replace-app-db]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- with-captured-eval!
  "Answer every form with `canned-value`, leaving the last one sent (the
  `app-db-reset!` form, which follows the raw-state signal) in `captured*`."
  [captured* canned-value body-fn]
  (let [orig nrepl/cljs-eval-value
        run  (fn [form-str]
               (reset! captured* form-str)
               (js/Promise.resolve canned-value))
        stub (fn
               ([_conn _build-id form-str] (run form-str))
               ([_conn _build-id form-str _opts] (run form-str)))]
    (set! nrepl/cljs-eval-value stub)
    (raw-state/reset-runtime-signal-cache!)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(defn- with-writes-on! [body-fn]
  (let [prev (writes/allow-writes-enabled?)]
    (writes/set-allow-writes! true)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (writes/set-allow-writes! prev))))))

(deftest gated-off-by-default-without-touching-runtime
  ;; The corpus fixture answers every eval with nil, so only this test can
  ;; see a write sent before the refusal.
  (async done
    (let [captured (atom :untouched)
          prev     (writes/allow-writes-enabled?)]
      (writes/set-allow-writes! false)
      (-> (with-captured-eval! captured :should-not-reach
            (fn []
              (replace-app-db/replace-app-db-tool (fresh-conn) #js {:db "{:k :v}"})))
          (.then (fn [r]
                   (is (tu/error? r))
                   (is (= :rf.error/writes-disabled (:reason (tu/extract-edn r))))
                   (is (= :untouched @captured) "runtime must NOT be contacted when gated")))
          (.finally (fn [] (writes/set-allow-writes! prev) (done)))))))

(deftest passes-frame-as-second-arg
  ;; The caller's db rides quoted, so it evaluates to the datum sent; the
  ;; server-composed frame rides plain.
  (async done
    (let [captured (atom nil)]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! captured {:ok? true :frame :stories}
                (fn []
                  (replace-app-db/replace-app-db-tool (fresh-conn)
                                                      #js {:db "{:count 0}" :frame ":stories"})))))
          (.then (fn [_]
                   (is (= '(re-frame2-pair.runtime/app-db-reset! (quote {:count 0}) :stories)
                          (cljs.reader/read-string @captured)))
                   (done)))))))

(deftest unexpected-shape-fallback-rides-as-isError
  ;; A degraded runtime's non-map answer means the write did not land in a
  ;; known-good shape.
  (async done
    (-> (with-writes-on!
          (fn []
            (with-captured-eval! (atom nil) "not-a-map"
              (fn []
                (replace-app-db/replace-app-db-tool (fresh-conn)
                                                    #js {:db "{:counter 0}"})))))
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= {:ok? false :reason :unexpected-shape :value "not-a-map" :frame nil}
                        (tu/extract-edn r)))
                 (done))))))
