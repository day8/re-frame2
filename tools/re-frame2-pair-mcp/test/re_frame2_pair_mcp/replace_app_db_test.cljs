(ns re-frame2-pair-mcp.replace-app-db-test
  "Unit tests for the replace-app-db tool (rf2-ee38b.18).

  State injection — replaces a frame's app-db with an arbitrary EDN
  value via the Tool-Pair `replace-app-db!` write primitive. Pins:

    - the `--allow-writes` gate (default OFF returns
      `:rf.error/writes-disabled` without touching the runtime);
    - the `db` arg parsed as EDN DATA (not host source — the
      injection-closing posture, same as dispatch);
    - the runtime envelope passthrough (`app-db-reset!` returns a
      structured `{:ok? ...}` map)."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.writes :as writes]
            [re-frame2-pair-mcp.tools.replace-app-db :as replace-app-db]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

;; rf2-z7roa — reset now issues `configure-raw-state!` (the raw-state
;; tap signal) BEFORE `app-db-reset!`. The stub records EVERY form so a
;; test can assert that ordering. `captured*` holds the LAST recorded
;; form (the app-db-reset! form, since it runs after the signal) — the
;; legacy single-form assertions still read it. The `configure-raw-
;; state!` eval resolves to the same canned value (harmless — its
;; result is swallowed). The signal cache is reset per test so the
;; signal fires freshly.
(defn- with-captured-eval!
  ([captured* canned-value body-fn]
   (with-captured-eval! captured* (atom []) canned-value body-fn))
  ([captured* forms* canned-value body-fn]
   (let [orig nrepl/cljs-eval-value
         run  (fn [form-str]
                (swap! forms* conj form-str)
                ;; `captured*` mirrors the LAST app-db-reset! form for
                ;; the legacy assertions (it overwrites on the signal
                ;; form first, then the reset form).
                (reset! captured* form-str)
                (js/Promise.resolve canned-value))
         stub (fn
                ([_conn _build-id form-str] (run form-str))
                ([_conn _build-id form-str _opts] (run form-str)))]
     (set! nrepl/cljs-eval-value stub)
     (raw-state/reset-runtime-signal-cache!)
     (-> (js/Promise.resolve nil)
         (.then (fn [_] (body-fn)))
         (.finally (fn [] (tu/restore-eval! stub orig)))))))

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
              (replace-app-db/replace-app-db-tool (fresh-conn) #js {:db "{:k :v}"})))
          (.then (fn [r]
                   (is (err? r))
                   (is (= :rf.error/writes-disabled (:reason (read-result-text r))))
                   (is (= :untouched @captured) "runtime must NOT be contacted when gated")))
          (.finally (fn [] (writes/set-allow-writes! prev) (done)))))))

;; ---------------------------------------------------------------------------
;; rf2-z7roa — raw-state tap signal ordering. `configure-raw-state!` MUST
;; be evaluated BEFORE `app-db-reset!`, so the runtime's tap-emitting
;; surface is in its gated (default-elided) posture before the reset taps
;; the pre-/post-reset app-db. A write-path test FAILS if app-db-reset!
;; can run first.
;; ---------------------------------------------------------------------------

(deftest signals-configure-raw-state-before-app-db-reset
  (async done
    (let [captured (atom nil)
          forms    (atom [])]
      (-> (with-writes-on!
            (fn []
              ;; gate OFF (default published posture) — the signal pushes
              ;; :allow-raw-state? false to the runtime before the reset.
              (let [prev (raw-state/allow-raw-state-enabled?)]
                (raw-state/set-allow-raw-state! false)
                (-> (with-captured-eval! captured forms {:ok? true :frame :rf/default}
                      (fn []
                        (replace-app-db/replace-app-db-tool (fresh-conn)
                                                            #js {:db "{:counter 0}"})))
                    (.finally (fn [] (raw-state/set-allow-raw-state! prev)))))))
          (.then (fn [_]
                   (let [all      @forms
                         cfg-idx  (first (keep-indexed (fn [i f] (when (str/includes? f "configure-raw-state!") i)) all))
                         reset-idx (first (keep-indexed (fn [i f] (when (str/includes? f "app-db-reset!") i)) all))]
                     (is (some? cfg-idx) "configure-raw-state! IS signalled before the reset")
                     (is (some? reset-idx) "app-db-reset! is evaluated")
                     (is (< cfg-idx reset-idx)
                         "configure-raw-state! MUST be evaluated BEFORE app-db-reset!")
                     (is (str/includes? (nth all cfg-idx) ":allow-raw-state? false")
                         "the gate-OFF posture is pushed to the runtime ahead of the reset tap"))
                   (done)))))))

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
                  (replace-app-db/replace-app-db-tool (fresh-conn)
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
                  (replace-app-db/replace-app-db-tool (fresh-conn)
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
                  (replace-app-db/replace-app-db-tool (fresh-conn)
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
            (replace-app-db/replace-app-db-tool (fresh-conn) #js {})))
        (.then (fn [r]
                 (is (err? r))
                 (is (= :missing-db (:reason (read-result-text r))))
                 (done))))))

(deftest rejects-unreadable-db
  (async done
    (-> (with-writes-on!
          (fn []
            (replace-app-db/replace-app-db-tool (fresh-conn) #js {:db "{:a"})))
        (.then (fn [r]
                 (is (err? r))
                 (is (= :invalid-db-edn (:reason (read-result-text r))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Runtime soft-failure passthrough.
;; ---------------------------------------------------------------------------

(deftest reset-rejected-envelope-rides-as-isError
  ;; rf2-or8s29 — a `{:ok? false :reason :reset-rejected ...}` from the
  ;; runtime means the injection did NOT land (no-such-frame,
  ;; replace-during-drain, schema-mismatch). It is not a terminal-empty
  ;; outcome, so it MUST ride as an isError result carrying the reason,
  ;; not a success-shaped envelope the host reads as a landed write.
  (async done
    (let [captured (atom nil)]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! captured {:ok? false :frame :rf/default
                                             :reason :reset-rejected
                                             :hint "schema mismatch"}
                (fn []
                  (replace-app-db/replace-app-db-tool (fresh-conn)
                                                      #js {:db "{:bad :shape}"})))))
          (.then (fn [r]
                   (is (err? r) "soft-failure rides as an isError result (rf2-or8s29)")
                   (let [edn (read-result-text r)]
                     (is (= false (:ok? edn)))
                     (is (= :reset-rejected (:reason edn))))
                   (done)))))))

(deftest unexpected-shape-fallback-rides-as-isError
  ;; rf2-or8s29 — a degraded / pre-rf2-c2dtu runtime can return a
  ;; non-map value; the tool synthesises `{:ok? false :reason
  ;; :unexpected-shape ...}`. That too means the write did not land in a
  ;; known-good shape, so it MUST ride as an isError result.
  (async done
    (let [captured (atom nil)]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! captured "not-a-map"
                (fn []
                  (replace-app-db/replace-app-db-tool (fresh-conn)
                                                      #js {:db "{:counter 0}"})))))
          (.then (fn [r]
                   (is (err? r) "unexpected-shape fallback rides as isError (rf2-or8s29)")
                   (let [edn (read-result-text r)]
                     (is (= false (:ok? edn)))
                     (is (= :unexpected-shape (:reason edn)))
                     (is (= "not-a-map" (:value edn))))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Cascade summary (rf2-6yqdl) — successful reset surfaces the synthetic
;; `:rf.epoch/db-replaced` epoch via :cascade-summary.
;; ---------------------------------------------------------------------------

(deftest cascade-summary-passes-through-on-success
  (async done
    (let [canned-cascade {:epoch-id 42
                          :event-id :rf.epoch/db-replaced
                          :frame :rf/default
                          :outcome :ok
                          :db-diff {:added-paths [[:counter]]
                                    :removed-paths [] :changed-paths []}
                          :fx-fired []
                          :subs-recomputed 0
                          :renders 0}
          runtime-envelope {:ok? true :frame :rf/default :epoch-id 42
                            :cascade-summary canned-cascade}]
      (-> (with-writes-on!
            (fn []
              (with-captured-eval! (atom nil) runtime-envelope
                (fn []
                  (replace-app-db/replace-app-db-tool (fresh-conn)
                                                      #js {:db "{:counter 0}"})))))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-result-text r)]
                     (is (true? (:ok? edn)))
                     (is (= canned-cascade (:cascade-summary edn))
                         "cascade-summary rides through verbatim"))
                   (done)))))))
