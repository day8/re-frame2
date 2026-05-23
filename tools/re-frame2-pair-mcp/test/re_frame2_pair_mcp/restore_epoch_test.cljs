(ns re-frame2-pair-mcp.restore-epoch-test
  "Unit tests for the restore-epoch tool (rf2-ee38b.18).

  Time-travel undo — rewinds a frame's app-db to a recorded prior
  epoch via the Tool-Pair `restore-epoch` write primitive. Pins:

    - the `--allow-writes` gate (default OFF returns
      `:rf.error/writes-disabled` without touching the runtime);
    - the EDN parse of the `epoch-id` arg, including INTEGER ids (the
      reference runtime emits integers — `:epoch-id` is `:any`);
    - the success / restore-rejected envelope shapes."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.writes :as writes]
            [re-frame2-pair-mcp.tools.restore-epoch :as restore-epoch]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- with-captured-eval!
  [captured* canned-value body-fn]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build-id form-str]
                (reset! captured* form-str)
                (js/Promise.resolve canned-value))
               ([_conn _build-id form-str _opts]
                (reset! captured* form-str)
                (js/Promise.resolve canned-value)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (set! nrepl/cljs-eval-value orig))))))

(defn- with-writes-on! [body-fn]
  (let [prev (writes/allow-writes-enabled?)]
    (writes/set-allow-writes! true)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (writes/set-allow-writes! prev))))))

(def ^:private read-result-text tu/extract-edn)
(def ^:private err? tu/error?)

;; ---------------------------------------------------------------------------
;; Gate — default OFF.
;; ---------------------------------------------------------------------------

(deftest gated-off-by-default-without-touching-runtime
  ;; The default-safe posture: with --allow-writes OFF the tool refuses
  ;; before any nREPL round-trip. We install a stub that would FAIL the
  ;; test (reset! captured) if reached — proving the gate short-circuits.
  (async done
    (let [captured (atom :untouched)
          prev     (writes/allow-writes-enabled?)]
      (writes/set-allow-writes! false)
      (-> (with-captured-eval! captured :should-not-reach
            (fn []
              (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "7"})))
          (.then (fn [r]
                   (is (err? r))
                   (is (= :rf.error/writes-disabled (:reason (read-result-text r))))
                   (is (= :untouched @captured) "runtime must NOT be contacted when gated")))
          (.finally (fn [] (writes/set-allow-writes! prev) (done)))))))

;; ---------------------------------------------------------------------------
;; epoch-id parsing — :any, including integers.
;; ---------------------------------------------------------------------------

(deftest accepts-integer-epoch-id-as-data
  ;; The reference runtime emits INTEGER epoch-ids. "7" reads as the
  ;; number 7 and rides into the runtime call as a data literal.
  (async done
    (let [captured (atom nil)]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! captured true
                (fn []
                  (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "7"})))))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-result-text r)]
                     (is (= true (:ok? edn)))
                     (is (= true (:restored? edn)))
                     (is (= 7 (:epoch-id edn)) "integer id round-trips through the envelope"))
                   (let [parsed (cljs.reader/read-string @captured)]
                     (is (= 're-frame2-pair.runtime/restore-epoch (first parsed)))
                     (is (= 7 (second parsed)) "epoch-id rides as the integer 7, not \"7\""))
                   (done)))))))

(deftest passes-frame-as-second-arg
  (async done
    (let [captured (atom nil)]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! captured true
                (fn []
                  (restore-epoch/restore-epoch-tool (fresh-conn)
                                                    #js {:epoch-id "12" :frame ":stories"})))))
          (.then (fn [_]
                   (let [parsed (cljs.reader/read-string @captured)]
                     ;; (rt/restore-epoch 12 :stories) — frame is the 2nd arg.
                     (is (= 12 (second parsed)))
                     (is (= :stories (nth parsed 2))))
                   (done)))))))

(deftest rejects-missing-epoch-id
  (async done
    (-> (with-writes-on!
          (fn []
            (restore-epoch/restore-epoch-tool (fresh-conn) #js {})))
        (.then (fn [r]
                 (is (err? r))
                 (is (= :missing-epoch-id (:reason (read-result-text r))))
                 (done))))))

(deftest rejects-unreadable-epoch-id
  (async done
    (-> (with-writes-on!
          (fn []
            (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "#("})))
        (.then (fn [r]
                 (is (err? r))
                 (is (= :invalid-epoch-id (:reason (read-result-text r))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Restore failure — runtime returns false.
;; ---------------------------------------------------------------------------

(deftest surfaces-restore-rejected-when-runtime-returns-false
  ;; restore-epoch returns false on any failure (aged-out id,
  ;; drain-in-flight, …); the app-db is unchanged. We surface a
  ;; structured :restore-rejected.
  (async done
    (let [captured (atom nil)]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! captured false
                (fn []
                  (restore-epoch/restore-epoch-tool (fresh-conn) #js {:epoch-id "999"})))))
          (.then (fn [r]
                   (is (not (err? r)) "soft-failure rides as an ok-text envelope, not isError")
                   (let [edn (read-result-text r)]
                     (is (= false (:ok? edn)))
                     (is (= false (:restored? edn)))
                     (is (= :restore-rejected (:reason edn)))
                     (is (= 999 (:epoch-id edn))))
                   (done)))))))
