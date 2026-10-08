(ns re-frame2-pair-mcp.restore-epoch-test
  "The restore-epoch write tool: refused without touching the runtime
  while `--allow-writes` is off, the caller's epoch id and the frame
  reach the runtime as data, a runtime map passes through (an
  `:ok? false` one as an error), and the raw-state posture is signalled
  before the restore eval."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.writes :as writes]
            [re-frame2-pair-mcp.tools.restore-epoch :as restore-epoch]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- with-captured-forms!
  "Record every form into `forms*`; the raw-state signal answers nil and
  every other form `canned-value`."
  [forms* canned-value body-fn]
  (let [orig nrepl/cljs-eval-value
        run  (fn [form-str]
               (swap! forms* conj form-str)
               (js/Promise.resolve
                 (when-not (str/includes? form-str "configure-raw-state!")
                   canned-value)))
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

(defn- restore! [forms canned args]
  (with-writes-on!
    (fn []
      (with-captured-forms! forms canned
        (fn [] (restore-epoch/restore-epoch-tool (fresh-conn) args))))))

(deftest gated-off-by-default-without-touching-runtime
  ;; The corpus fixture answers every eval with nil, so only this test can
  ;; see a write sent before the refusal.
  (async done
    (let [forms (atom [])
          prev  (writes/allow-writes-enabled?)]
      (writes/set-allow-writes! false)
      (-> (with-captured-forms! forms :should-not-reach
            (fn []
              (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "7"})))
          (.then (fn [r]
                   (is (tu/error? r))
                   (is (= :rf.error/writes-disabled (:reason (tu/extract-edn r))))
                   (is (empty? @forms) "runtime must NOT be contacted when gated")))
          (.finally (fn [] (writes/set-allow-writes! prev) (done)))))))

(deftest passes-frame-as-second-arg
  (async done
    (let [forms (atom [])]
      (-> (restore! forms true #js {:epoch-id "12" :frame ":stories"})
          (.then (fn [_]
                   (is (= '(re-frame2-pair.runtime/restore-epoch (quote 12) :stories)
                          (cljs.reader/read-string (last @forms))))
                   (done)))))))

(deftest surfaces-isError-when-runtime-returns-structured-failure-map
  ;; A rejected restore can come back as a map as well as a bare `false`;
  ;; passed to ok-text it would read as a landed write.
  (async done
    (let [failure {:ok? false :restored? false :reason :restore-rejected
                   :epoch-id 7 :frame :rf/default}]
      (-> (restore! (atom []) failure #js {:epoch-id "7"})
          (.then (fn [r]
                   (is (tu/error? r))
                   (is (= failure (tu/extract-edn r)))
                   (done)))))))

(deftest cascade-summary-passes-through-on-success
  ;; A runtime map passes through whole; only a bare `true` is synthesised.
  (async done
    (let [envelope {:ok? true :restored? true :epoch-id 7 :frame :rf/default
                    :cascade-summary {:epoch-id 7 :restore? true}
                    :unreplayable-effects [{:fx-id :http :coord [:my.app.cart 42 4]}]}]
      (-> (restore! (atom []) envelope #js {:epoch-id "7"})
          (.then (fn [r]
                   (is (not (tu/error? r)))
                   (is (= envelope (tu/extract-edn r)))
                   (done)))))))

(deftest signals-raw-state-posture-before-the-restore-eval
  ;; The runtime redacts a sensitive target epoch's `:event-vector` only
  ;; once told the gate is off.
  (async done
    (let [forms (atom [])
          prev  (raw-state/allow-raw-state-enabled?)
          idx   (fn [s] (first (keep-indexed #(when (str/includes? %2 s) %1) @forms)))]
      (raw-state/set-allow-raw-state! false)
      (-> (restore! forms true #js {:epoch-id "7"})
          (.then (fn [_]
                   (let [cfg (idx "configure-raw-state!")
                         rst (idx "restore-epoch")]
                     (is (and cfg rst (< cfg rst))
                         "raw-state posture is signalled before the restore eval")
                     (is (str/includes? (nth @forms cfg) ":allow-raw-state? false")))))
          (.finally (fn [] (raw-state/set-allow-raw-state! prev) (done)))))))
