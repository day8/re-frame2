(ns re-frame2-pair-mcp.reset-frame-db-test
  "Unit tests for the reset-frame-db tool (rf2-ee38b.18).

  State injection — replaces a frame's app-db with an arbitrary EDN
  value via the Tool-Pair `reset-frame-db!` write primitive. Pins:

    - the `--allow-writes` gate (default OFF returns
      `:rf.error/writes-disabled` without touching the runtime);
    - the `db` arg parsed as EDN DATA (not host source — the
      injection-closing posture, same as dispatch);
    - the runtime envelope passthrough (`app-db-reset!` returns a
      structured `{:ok? ...}` map)."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.writes :as writes]
            [re-frame2-pair-mcp.tools.reset-frame-db :as reset-frame-db]))

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
  (async done
    (let [captured (atom :untouched)
          prev     (writes/allow-writes-enabled?)]
      (writes/set-allow-writes! false)
      (-> (with-captured-eval! captured :should-not-reach
            (fn []
              (reset-frame-db/reset-frame-db-tool (fresh-conn) #js {:db "{:k :v}"})))
          (.then (fn [r]
                   (is (err? r))
                   (is (= :rf.error/writes-disabled (:reason (read-result-text r))))
                   (is (= :untouched @captured) "runtime must NOT be contacted when gated")))
          (.finally (fn [] (writes/set-allow-writes! prev) (done)))))))

;; ---------------------------------------------------------------------------
;; db as EDN data, not host source.
;; ---------------------------------------------------------------------------

(deftest accepts-edn-map-and-emits-data-arg
  ;; Happy path: a map db reads as data and flows into app-db-reset! as
  ;; an EDN literal — NO host-form splice.
  (async done
    (let [captured (atom nil)]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! captured {:ok? true :frame :rf/default}
                (fn []
                  (reset-frame-db/reset-frame-db-tool (fresh-conn)
                                                      #js {:db "{:counter 0}"})))))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-result-text r)]
                     (is (= true (:ok? edn)))
                     (is (= :rf/default (:frame edn)) "runtime envelope passes through"))
                   (let [parsed (cljs.reader/read-string @captured)]
                     (is (= 're-frame2-pair.runtime/app-db-reset! (first parsed)))
                     (is (= {:counter 0} (second parsed)) "db rides as DATA, not source"))
                   (done)))))))

(deftest does-not-execute-host-form-in-db-arg
  ;; A prompt-injected `(println :pwn)` string is parsed as a LIST
  ;; literal (data), emitted verbatim as the db value — never executed.
  ;; The runtime would reject it on schema validation; the point here is
  ;; the host boundary: it rides as data.
  (async done
    (let [captured (atom nil)]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! captured {:ok? false :reason :reset-rejected}
                (fn []
                  (reset-frame-db/reset-frame-db-tool (fresh-conn)
                                                      #js {:db "(println :pwn)"})))))
          (.then (fn [_]
                   (let [parsed (cljs.reader/read-string @captured)]
                     (is (= 're-frame2-pair.runtime/app-db-reset! (first parsed)))
                     ;; The injected list rides as a quoted-data literal
                     ;; (pr-str of a list), NOT spliced as a callable form
                     ;; at the top of the eval. The second arg is the
                     ;; list data itself.
                     (is (seq? (second parsed)))
                     (is (= 'println (first (second parsed)))))
                   (done)))))))

(deftest passes-frame-as-second-arg
  (async done
    (let [captured (atom nil)]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! captured {:ok? true :frame :stories}
                (fn []
                  (reset-frame-db/reset-frame-db-tool (fresh-conn)
                                                      #js {:db "{:count 0}" :frame ":stories"})))))
          (.then (fn [_]
                   (let [parsed (cljs.reader/read-string @captured)]
                     ;; (rt/app-db-reset! {:count 0} :stories) — value 1st, frame 2nd.
                     (is (= {:count 0} (second parsed)))
                     (is (= :stories (nth parsed 2))))
                   (done)))))))

(deftest rejects-missing-db
  (async done
    (-> (with-writes-on!
          (fn []
            (reset-frame-db/reset-frame-db-tool (fresh-conn) #js {})))
        (.then (fn [r]
                 (is (err? r))
                 (is (= :missing-db (:reason (read-result-text r))))
                 (done))))))

(deftest rejects-unreadable-db
  (async done
    (-> (with-writes-on!
          (fn []
            (reset-frame-db/reset-frame-db-tool (fresh-conn) #js {:db "{:a"})))
        (.then (fn [r]
                 (is (err? r))
                 (is (= :invalid-db-edn (:reason (read-result-text r))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Runtime soft-failure passthrough.
;; ---------------------------------------------------------------------------

(deftest passes-through-reset-rejected-envelope
  (async done
    (let [captured (atom nil)]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! captured {:ok? false :frame :rf/default
                                             :reason :reset-rejected
                                             :hint "schema mismatch"}
                (fn []
                  (reset-frame-db/reset-frame-db-tool (fresh-conn)
                                                      #js {:db "{:bad :shape}"})))))
          (.then (fn [r]
                   (is (not (err? r)) "soft-failure rides as ok-text, not isError")
                   (let [edn (read-result-text r)]
                     (is (= false (:ok? edn)))
                     (is (= :reset-rejected (:reason edn))))
                   (done)))))))
