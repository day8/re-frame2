(ns re-frame.ssr-hydration-mismatch-test
  "A payload baked with a known-wrong `:rf/render-hash` (`\"deadbeef\"`) through
  `:rf/hydrate` and `verify-hydration!`.

  A detected mismatch reaches three channels: the `:rf.ssr/hydration-mismatch`
  dev trace (elided under `-Dre-frame.debug=false`, so its assertions sit in
  `(when interop/debug-enabled? …)` arms), the strict-mode throw and the
  always-on error record, which both hold in production."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error :as rf.error]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

;; No `:rf/frame-id`: the handler fails closed on a present-and-different one,
;; and these tests hydrate fresh anonymous frames.
(def ^:private mismatch-payload
  {:rf/version     1
   :rf/render-hash "deadbeef"
   :rf/app-db      {:count 0}})

(defn- hydrated-frame!
  "A fresh `:client` frame with `frame-opts`, hydrated from `mismatch-payload`."
  ([] (hydrated-frame! {}))
  ([frame-opts]
   (let [frame (rf.frame/make-anon-frame-record! (merge {:platform :client} frame-opts))]
     (rf/dispatch-sync [:rf/hydrate mismatch-payload] {:frame frame})
     frame)))

(defn- mismatch-traces [traces]
  (filter #(= :rf.ssr/hydration-mismatch (:operation %)) traces))

(defn- always-on-mismatch-count
  "How many `:rf.ssr/hydration-mismatch` records `verify-hydration!` fans on the
  always-on error axis for `frame` and `client-hash`."
  [frame client-hash]
  (let [records (atom [])]
    (rf.error-emit/register-error-listener! ::always-on
      (fn [record] (swap! records conj record)))
    (try
      (rf.ssr/verify-hydration! frame client-hash)
      (finally
        (rf.error-emit/unregister-error-listener! ::always-on)))
    (count (filter #(= :rf.ssr/hydration-mismatch (:error %)) @records))))

(deftest mismatch-hydrate-still-stashes-metadata-when-server-hash-set
  (testing ":rf/hydrate stashes the server hash whatever the client later renders"
    (is (= "deadbeef"
           (get-in (:rf.db/runtime (rf/frame-state-value (hydrated-frame!)))
                   [:rf.runtime/ssr :hydration :server-hash])))))

(deftest mismatch-trace-carries-server-hash-failing-id-recovery
  (let [frame (hydrated-frame!)]
    ;; Dev-instrumentation arm.
    (when rf.interop/debug-enabled?
      (with-trace-recorder! [traces]
        (rf.ssr/verify-hydration! frame "0badf00d")
        (is (= [{:server-hash "deadbeef" :client-hash "0badf00d"
                 :failing-id  :rf/hydrate :recovery :warned-and-replaced}]
               (for [ev (mismatch-traces @traces)]
                 (assoc (select-keys (:tags ev) [:server-hash :client-hash :failing-id])
                        :recovery (:recovery ev)))))))))

(deftest mismatch-trace-client-hash-is-8-char-lowercase-hex
  (let [frame       (hydrated-frame!)
        tree        [:div {:data-testid "counter-panel"} [:p "count=" [:span 0]]]
        client-hash (rf.ssr/render-tree-hash tree)]
    (is (re-matches #"^[0-9a-f]{8}$" client-hash))
    ;; Dev-instrumentation arm: verifying a TREE hashes it.
    (when rf.interop/debug-enabled?
      (with-trace-recorder! [traces]
        (rf.ssr/verify-hydration! frame tree)
        (is (= [client-hash]
               (map #(-> % :tags :client-hash) (mismatch-traces @traces))))))))

(deftest mismatch-trace-is-an-error-op-type-event
  (let [frame (hydrated-frame!)]
    ;; Dev-instrumentation arm. The always-on counterpart is the `:rf.error/id`
    ;; the strict-mode throw carries (`mismatch-strict-mode-throws-with-structured-payload`).
    (when rf.interop/debug-enabled?
      (with-trace-recorder! [traces]
        (rf.ssr/verify-hydration! frame "0badf00d")
        (is (= [:error] (map :op-type (mismatch-traces @traces))))))))

(deftest mismatch-page-stays-interactive-post-mismatch
  (testing "the default :warn recovery leaves the dispatch pipeline live"
    (rf/reg-event ::inc (fn [{:keys [db]} _] {:db (update db :count inc)}))
    (let [frame (hydrated-frame!)]
      (rf.ssr/verify-hydration! frame "0badf00d")
      (rf/dispatch-sync [::inc] {:frame frame})
      (is (= {:count 1} (rf/app-db-value frame))))))

(deftest mismatch-strict-mode-throws-with-structured-payload
  (testing ":on-mismatch :hard-error throws the structured mismatch; the frame
            omits :detect-mismatch?, so this also pins detection defaulting on"
    (let [frame  (hydrated-frame! {:ssr {:on-mismatch :hard-error}})
          thrown (try (rf.ssr/verify-hydration! frame "0badf00d")
                      nil
                      (catch clojure.lang.ExceptionInfo e e))
          msg    (ex-message thrown)]
      (is (= {:rf.error/id :rf.ssr/hydration-mismatch
              :server-hash "deadbeef"
              :client-hash "0badf00d"
              :failing-id  :rf/hydrate
              :recovery    :hard-error
              :where       'rf/verify-hydration!}
             (select-keys (ex-data thrown)
                          [:rf.error/id :server-hash :client-hash :failing-id :recovery :where])))
      (is (rf.error/message-has-id-token? msg))
      (is (not (rf.error/keyword-only-message? msg))))))

(deftest mismatch-strict-mode-still-emits-trace-before-throwing
  (let [frame (hydrated-frame! {:ssr {:on-mismatch :hard-error}})]
    ;; Dev-instrumentation arm: the ring is read inside the catch, so the
    ;; trace must already be on the bus when the throw reaches the caller.
    (when rf.interop/debug-enabled?
      (with-trace-recorder! [traces]
        (is (= [:hard-error]
               (try (rf.ssr/verify-hydration! frame "0badf00d")
                    nil
                    (catch clojure.lang.ExceptionInfo _
                      (mapv :recovery (mismatch-traces @traces))))))))))

(deftest mismatch-detection-disabled-skips-comparison
  (testing ":detect-mismatch? false reports no mismatch on the always-on axis;
            the same input on a knob-less frame reports one"
    (is (= 1 (always-on-mismatch-count (hydrated-frame!) "0badf00d")))
    (is (zero? (always-on-mismatch-count (hydrated-frame! {:ssr {:detect-mismatch? false}})
                                         "0badf00d")))))
