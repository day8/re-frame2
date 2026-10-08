(ns re-frame2-pair-mcp.dispatch-dry-run-test
  "The dispatch-dry-run tool's wire boundary: the caller's options reach
  the runtime call as quoted data, `:fx-overrides` and a malformed `cofx`
  are refused before the eval, fx args fail closed even under
  `--allow-sensitive-reads`, and the runtime envelope is unwrapped from
  the elision form with a failed rollback always reading as an error.
  The runtime semantics are exercised by the live preload tests under
  skills/re-frame2-pair/tests/runtime/."
  (:require [cljs.test :refer-macros [deftest is async]]
            [applied-science.js-interop :as j]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.dispatch-dry-run :as dry-run]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- with-captured-eval!
  "Record every form into `forms*`; the raw-state signal answers nil and
  every other form `dispatch-canned`."
  [forms* dispatch-canned body-fn]
  (let [orig nrepl/cljs-eval-value
        run  (fn [form-str]
               (swap! forms* conj form-str)
               (js/Promise.resolve
                 (if (str/includes? form-str "configure-raw-state!")
                   nil
                   dispatch-canned)))
        stub (fn
               ([_conn _build-id form-str] (run form-str))
               ([_conn _build-id form-str _opts] (run form-str)))]
    (set! nrepl/cljs-eval-value stub)
    (raw-state/reset-runtime-signal-cache!)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(defn- dispatch-form
  "The recorded form that mentions the runtime `dispatch-dry-run` call."
  [forms*]
  (some #(when (str/includes? % "dispatch-dry-run") %) @forms*))

;; The eval form wraps the runtime envelope as `{:value <env> :elided-count N}`.
(defn- wrap [env elided]
  {:value env :elided-count elided})

(def ^:private read-result-text tu/extract-edn)
(def ^:private err? tu/error?)

(defn- with-raw-gate! [enabled? body-fn]
  (let [prev (raw-state/raw-state-allowed?)]
    (raw-state/set-allow-raw-state! enabled?)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (raw-state/set-allow-raw-state! prev))))))

(deftest rejects-caller-fx-overrides
  ;; The effect sink records every fx before override resolution, so an
  ;; override could only take effect by running a body.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true :dry-run? true :rolled-back? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:cart/checkout]"
                                                  :fx-overrides #js {":http" ":stub-http"}})))
          (.then (fn [r]
                   (is (err? r))
                   (is (nil? (dispatch-form forms))
                       "the dispatch-dry-run eval never fired")
                   (done)))))))

(deftest cofx-non-map-rejected
  (async done
    (-> (with-captured-eval! (atom []) (wrap {:ok? true} 0)
          (fn []
            (dry-run/dispatch-dry-run-tool (fresh-conn)
                                           #js {:event "[:counter/inc]"
                                                :cofx "[:not :a :map]"})))
        (.then (fn [r]
                 (is (err? r))
                 (is (= :invalid-cofx (:reason (read-result-text r))))
                 (done))))))

(deftest gate-on-default-still-redacts-fx-args
  ;; Revealing app-db (`:include-sensitive`) does not reveal fx args;
  ;; only `:include-fx-args true` does.
  (async done
    (let [forms (atom [])]
      (-> (with-raw-gate! true
            (fn []
              (with-captured-eval! forms (wrap {:ok? true :dry-run? true} 0)
                (fn []
                  (dry-run/dispatch-dry-run-tool (fresh-conn)
                                                 #js {:event "[:cart/checkout]"
                                                      :include-sensitive true})))))
          (.then (fn [_]
                   (is (str/includes? (dispatch-form forms) ":args :rf/redacted"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; The opts map rides quoted: printed, a scripted fact containing a list
;; would be evaluated while the call is built, and the simulation would run
;; on a different fact from the one scripted.
;; ---------------------------------------------------------------------------

(defn- quoted-datum
  "The datum a `(quote <datum>)` form evaluates to, or `::not-quoted`."
  [form]
  (if (and (seq? form) (= 'quote (first form)) (= 2 (count form)))
    (second form)
    ::not-quoted))

(defn- runtime-call
  "The `(re-frame2-pair.runtime/dispatch-dry-run <event> <opts>)` call read
  out of the emitted `rt-let` form."
  [forms*]
  (let [form (dispatch-form forms*)
        head "(re-frame2-pair.runtime/dispatch-dry-run "
        i    (str/index-of form head)]
    (when i (cljs.reader/read-string (subs form i)))))

(deftest dry-run-cofx-fact-lists-are-not-evaluated
  (async done
    (let [forms (atom [])]
      (-> (with-captured-eval! forms (wrap {:ok? true :dry-run? true :rolled-back? true} 0)
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn)
                                             #js {:event "[:review/event]"
                                                  :frame ":checkout"
                                                  :cofx "{:review/fact (inc 41)}"})))
          (.then (fn [_]
                   (is (= {:frame :checkout :rf.cofx {:review/fact '(inc 41)}}
                          (quoted-datum (nth (runtime-call forms) 2))))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; The reply decoded by the real decoder: `nrepl/cljs-eval` is stubbed one
;; layer below `cljs-eval-value`, so an application-defined tag in the
;; projected state arrives exactly as shadow-cljs delivers it.
;; ---------------------------------------------------------------------------

(defn- with-printed-reply!
  "Answer the dry-run eval with `printed` and the raw-state signal with nil."
  [printed body-fn]
  (let [orig  nrepl/cljs-eval
        reply (fn [form-str]
                (js/Promise.resolve
                  {:value (pr-str {:results [(if (str/includes? form-str "configure-raw-state!")
                                               "nil"
                                               printed)]
                                   :ns 'cljs.user})}))
        stub  (fn
                ([_conn _build-id form-str] (reply form-str))
                ([_conn _build-id form-str _opts] (reply form-str)))]
    (set! nrepl/cljs-eval stub)
    (raw-state/reset-runtime-signal-cache!)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-cljs-eval! stub orig))))))

(defn- read-tagged-text
  "A result's EDN text read by a client with no application readers."
  [r]
  (cljs.reader/read-string {:default tagged-literal} (tu/extract-text r)))

(def ^:private instant (tagged-literal 'instant "2026-01-01T00:00:00Z"))

(deftest an-application-tag-does-not-discard-a-rolled-back-simulation
  (async done
    (let [env {:ok?                       true
               :dry-run?                  true
               :rolled-back?              true
               :would-fire-effects        [{:fx-id :http :args :rf/redacted}]
               :db-state-after-simulation {:sample instant
                                           :nested (tagged-literal 'app/outer
                                                                   {:inner (tagged-literal 'app/inner [1 2])})
                                           :user   {:token :rf/redacted}
                                           :blob   {:rf.size/large-elided {:bytes 99999 :type "string"}}}}]
      (-> (with-printed-reply! (pr-str (wrap env 1))
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn) #js {:event "[:toggle-dev-mode]"})))
          (.then (fn [r]
                   (is (not (err? r)) "a successful, rolled-back simulation reads as success")
                   (let [edn (read-tagged-text r)
                         db  (:db-state-after-simulation edn)]
                     (is (true? (:ok? edn)))
                     (is (true? (:rolled-back? edn)))
                     (is (= instant (:sample db)) "the tag rides through as inert tagged data")
                     (is (= (tagged-literal 'app/inner [1 2]) (get-in db [:nested :form :inner]))
                         "a nested tag rides through too")
                     (is (= :rf/redacted (get-in db [:user :token])) "redaction is preserved")
                     (is (= :rf/redacted (get-in edn [:would-fire-effects 0 :args]))
                         "fx-args redaction is preserved")
                     (is (contains? (:blob db) :rf.size/large-elided) "the size-elision marker is preserved")
                     (is (= 1 (:elided-large edn)) "the elided-large indicator counts the marker"))
                   (is (re-find #"#instant \"2026-01-01T00:00:00Z\"" (tu/extract-text r))
                       "the canonical EDN keeps the tag")
                   (is (= {"rf.mcp/tag" "instant" "rf.mcp/form" "2026-01-01T00:00:00Z"}
                          (js->clj (j/get-in r [:structuredContent "db-state-after-simulation" "sample"])))
                       "the structured slot carries the tag in its defined JSON form")
                   (done)))))))

(deftest a-failed-rollback-stays-a-failure-beside-an-application-tag
  ;; `:ok? true` beside `:rolled-back? false` still reads red: a runtime
  ;; claiming success over a live, mutated db is the case the guard is for.
  ;; The tagged value carries success-looking fields of its own.
  (async done
    (let [env {:ok?                       true
               :dry-run?                  true
               :rolled-back?              false
               :db-state-after-simulation {:sample instant
                                           :opaque (tagged-literal 'app/result
                                                                   {:ok? true :rolled-back? true})}}]
      (-> (with-printed-reply! (pr-str (wrap env 0))
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn) #js {:event "[:toggle-dev-mode]"})))
          (.then (fn [r]
                   (is (err? r) ":rolled-back? false is an error whatever the state carries")
                   (let [edn (read-tagged-text r)]
                     (is (false? (:rolled-back? edn))
                         "the envelope's own rollback outcome rides through")
                     (is (not= :unexpected-shape (:reason edn))
                         "the failure is the rollback, not an undifferentiated shape failure"))
                   (done)))))))

(deftest a-reported-rollback-failure-beside-an-application-tag-keeps-its-reason
  (async done
    (let [env {:ok?          false
               :reason       :rollback-failed
               :dry-run?     true
               :rolled-back? false
               :event        [:toggle-dev-mode]
               :frame        :rf/default
               :at           instant}]
      (-> (with-printed-reply! (pr-str (wrap env 0))
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn) #js {:event "[:toggle-dev-mode]"})))
          (.then (fn [r]
                   (is (err? r))
                   (let [edn (read-tagged-text r)]
                     (is (= :rollback-failed (:reason edn)))
                     (is (false? (:rolled-back? edn))))
                   (done)))))))

(deftest a-malformed-reply-still-fails-as-unexpected-shape
  ;; An unreadable reply decodes to the raw string, which takes the
  ;; non-map arm.
  (async done
    (let [orig-err (.-error js/console)]
      (set! (.-error js/console) (fn [& _] nil))
      (-> (with-printed-reply! "{:value {:ok? true :rolled-back? true :sample #instant \"2026\""
            (fn []
              (dry-run/dispatch-dry-run-tool (fresh-conn) #js {:event "[:toggle-dev-mode]"})))
          (.then (fn [r]
                   (is (err? r))
                   (is (= :unexpected-shape (:reason (read-tagged-text r)))
                       "an unreadable reply never reads as a successful simulation")))
          (.finally (fn []
                      (set! (.-error js/console) orig-err)
                      (done)))))))
