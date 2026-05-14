(ns re-frame.ssr.ring-streaming-test
  "Streaming SSR Ring adapter — chunked-response wiring. Per Spec 011
  §Streaming SSR (rf2-ojakd / rf2-olb64 (a)).

  Exercises the full request lifecycle through `stream-handler`:
    1. Ring request comes in
    2. setup-request-frame! seeds the frame + on-create
    3. streaming writer flushes shell → continuations → final payload → close
    4. response body is a PipedInputStream; we drain it into a string
    5. asserts on chunk shapes + final-payload."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.test-fixture :as tf])
  (:import [java.io InputStream]))

(defn- reset+reg-test-handlers
  [test-fn]
  (tf/reset-runtime
    (fn []
      (rf/reg-event-db :rf.test/seed-articles
        (fn [_ [_ arts]] {:articles arts}))
      (rf/reg-event-fx :rf.test.server/init
        {:platforms #{:server}}
        (fn [_ _]
          {:db {:articles [{:id "a" :title "Article A"}
                           {:id "b" :title "Article B"}]
                :comments [{:body "First!"} {:body "Nice"}]}}))
      (rf/reg-sub :articles (fn [db _] (:articles db)))
      (rf/reg-sub :comments (fn [db _] (:comments db)))
      (rf/reg-view ^{:rf/id :test/article-list} article-list-view []
        (let [arts @(subscribe [:articles])]
          (into [:ul.articles]
                (for [{:keys [id title]} arts]
                  ^{:key id} [:li title]))))
      (rf/reg-view ^{:rf/id :test/comments-section} comments-view []
        (let [cs @(subscribe [:comments])]
          (into [:ul.comments]
                (for [{:keys [body]} cs]
                  [:li body]))))
      (rf/reg-view ^{:rf/id :test/root} root-view []
        [:main
         [:h1 "News"]
         [:test/article-list]
         [:rf/suspense-boundary
          {:id :test/comments :fallback [:p "Loading comments…"]}
          [:test/comments-section]]
         [:footer "End"]])
      (test-fn))))

(use-fixtures :each reset+reg-test-handlers)

(defn- drain-stream
  "Drain a Ring response's InputStream body into a string. Synchronous
  — blocks until the writer thread closes the pipe."
  [^InputStream is]
  (with-open [is is]
    (slurp is)))

(deftest stream-handler-emits-shell-then-resolved-then-payload
  (testing "chunk order: shell prefix → shell-html → resolved templates → final __rf_payload → close"
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.server/init]
                      :root-view [:test/root]})
          response (handler {:uri "/" :request-method :get})
          body     (drain-stream (:body response))
          ;; Indices into the body string — the wire-order invariant
          ;; we pin: shell open, then template fallback, then resolved
          ;; template, then __rf_payload, then </body></html>.
          idx-doctype  (str/index-of body "<!DOCTYPE html>")
          idx-h1       (str/index-of body "<h1>News</h1>")
          idx-fallback (str/index-of body "data-rf2-suspense-fallback=\"1\"")
          idx-resolved (str/index-of body "data-rf2-suspense-resolved=\"1\"")
          idx-comments (str/index-of body "First!")
          idx-payload  (str/index-of body "__rf_payload")
          idx-close    (str/index-of body "</body></html>")]
      (is (= 200 (:status response)) "Ring response status defaults to 200")
      (is (some? idx-doctype) "shell carries doctype")
      (is (some? idx-h1) "shell carries the static header")
      (is (some? idx-fallback) "fallback placeholder embedded in shell")
      (is (some? idx-resolved) "resolved subtree chunk emitted")
      (is (some? idx-comments) "resolved subtree carries the rendered comments")
      (is (some? idx-payload) "final __rf_payload chunk emitted")
      (is (some? idx-close) "body close emitted")
      ;; The chunk-ordering contract.
      (is (< idx-doctype idx-h1 idx-fallback) "shell + fallback emitted before resolved chunks")
      (is (< idx-fallback idx-resolved) "fallback placeholder emitted before resolved chunk")
      (is (< idx-resolved idx-payload) "resolved chunks emitted before final payload")
      (is (< idx-payload idx-close) "final payload before body close"))))

(deftest stream-handler-multiple-boundaries-FIFO
  (testing "Multiple boundaries emit resolved chunks in document-order FIFO"
    (rf/reg-view ^{:rf/id :test/multi-root} multi-root-view []
      [:div
       [:rf/suspense-boundary {:id :a :fallback [:p "A loading"]} [:p "A done"]]
       [:rf/suspense-boundary {:id :b :fallback [:p "B loading"]} [:p "B done"]]
       [:rf/suspense-boundary {:id :c :fallback [:p "C loading"]} [:p "C done"]]])
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.server/init]
                      :root-view [:test/multi-root]})
          response (handler {:uri "/" :request-method :get})
          body     (drain-stream (:body response))
          ;; Extract the order of resolved chunks by finding each id's
          ;; resolved template offset.
          offs (->> [:a :b :c]
                    (map (fn [id]
                           [id (str/index-of
                                 body
                                 (str "data-rf2-suspense-id=\":" (name id) "\""
                                      " data-rf2-suspense-resolved"))]))
                    (filter (fn [[_ o]] (some? o)))
                    sort
                    (sort-by second))]
      (is (= [:a :b :c] (mapv first offs))
          "resolved chunks emitted in registration FIFO order"))))

(deftest stream-handler-failed-continuation-stays-fallback
  (testing "Continuation throw → failed-template emitted with data-rf2-suspense-failed marker; response completes"
    (rf/reg-view ^{:rf/id :test/throwing-section} throwing-section []
      (throw (ex-info "rendering broke" {})))
    (rf/reg-view ^{:rf/id :test/fragile-root} fragile-root []
      [:main
       [:h1 "Header"]
       [:rf/suspense-boundary
        {:id :test/throwy :fallback [:p "Still loading…"]}
        [:test/throwing-section]]
       [:footer "End"]])
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.server/init]
                      :root-view [:test/fragile-root]})
          response (handler {:uri "/" :request-method :get})
          body     (drain-stream (:body response))]
      (is (= 200 (:status response)) "stream still 200 — failure is partial-render-safe")
      (is (str/includes? body "<h1>Header</h1>") "shell rendered")
      (is (str/includes? body "<footer>End</footer>") "rest of shell rendered")
      (is (str/includes? body "data-rf2-suspense-failed=\"1\"") "failed marker stamped")
      (is (str/includes? body "Still loading") "fallback materialised in failed chunk")
      (is (str/includes? body "__rf_payload") "final payload still emitted"))))

(deftest stream-handler-no-boundary-zero-continuations
  (testing "A tree with NO :rf/suspense-boundary still streams cleanly (degenerate case)"
    (rf/reg-view ^{:rf/id :test/static-root} static-root []
      [:main [:h1 "Just static"]])
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.server/init]
                      :root-view [:test/static-root]})
          response (handler {:uri "/" :request-method :get})
          body     (drain-stream (:body response))]
      (is (str/includes? body "<h1>Just static</h1>") "static content emitted")
      (is (str/includes? body "__rf_payload") "final payload emitted")
      (is (not (str/includes? body "data-rf2-suspense-id")) "no boundary markers")
      (is (str/includes? body "</body></html>") "body closes cleanly"))))
