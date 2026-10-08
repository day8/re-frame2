(ns re-frame2-pair-mcp.read-dom-test
  "Unit tests for the read-dom raw-DOM-plane read tool: the
  `(re-frame2-pair.runtime/dom-read {...})` form it composes, and the
  envelope it forwards. The corpus pins the happy path and the
  missing-selector gate; the read itself runs browser-side."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.read-dom :as read-dom]))

(deftest form-carries-only-the-supplied-opts
  ;; Omitting :attrs lets the runtime ride its default set plus the
  ;; data-*/aria-* sweep, so an absent opt must not ride as nil. The form is
  ;; a bare runtime call over literal data — nothing the bare browser eval
  ;; context cannot resolve.
  (is (= '(re-frame2-pair.runtime/dom-read {:selector "div" :limit 10 :max-text 100})
         (cljs.reader/read-string (#'read-dom/read-dom-form "div" nil 10 100 nil nil))))
  (is (= '(re-frame2-pair.runtime/dom-read {:selector "div" :limit 10 :max-text 100
                                            :sub-selector ".title" :attrs ["id"] :frame :stories})
         (cljs.reader/read-string (#'read-dom/read-dom-form "div" ".title" 10 100 ["id"] :stories)))))

(deftest parse-attrs-arg-shapes
  (doseq [[raw expected] [[nil nil]
                          [#js ["id" "class"] ["id" "class"]]
                          ["id, data-state" ["id" "data-state"]]
                          ["   " nil]]]
    (is (= expected (#'read-dom/parse-attrs-arg raw)) (pr-str raw))))

;; ---------------------------------------------------------------------------
;; Tool wiring.
;; ---------------------------------------------------------------------------

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(deftest bad-selector-error-forwarded
  ;; Every :ok? false is isError (spec/003-Tool-Catalogue.md), which also
  ;; keeps a transient failure out of the response cache.
  (async done
    (let [canned {:ok? false :reason :rf.error/read-dom-bad-selector
                  :selector "###" :message "bad selector"}]
      (-> (tu/with-stubbed-eval! canned
            #(read-dom/read-dom-tool (fresh-conn) #js {:selector "###"}))
          (.then (fn [r]
                   (is (= [true (assoc canned :build :app)]
                          [(tu/error? r) (tu/extract-edn r)]))
                   (done)))))))

(deftest blank-eval-result-becomes-structured-error-not-host-failure
  ;; A blank eval (the runtime did not answer) must not become a null
  ;; structuredContent, which the SDK rejects at the transport layer.
  (async done
    (-> (tu/with-stubbed-eval! nil
          #(read-dom/read-dom-tool (fresh-conn) #js {:selector "body" :limit 1}))
        (.then (fn [r]
                 (is (= [true {:ok? false :reason :rf.error/read-dom-blank-result
                               :build :app :selector "body"}]
                        [(tu/error? r) (dissoc (tu/extract-edn r) :hint)]))
                 (done))))))
