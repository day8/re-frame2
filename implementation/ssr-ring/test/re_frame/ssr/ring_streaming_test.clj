(ns re-frame.ssr.ring-streaming-test
  "Streaming SSR Ring adapter — chunked-response wiring. Per Spec 011
  §Streaming SSR (rf2-ojakd / rf2-olb64 (a)).

  Exercises the full request lifecycle through `stream-handler`:
    1. Ring request comes in
    2. setup-request-frame! seeds the frame + on-create
    3. streaming writer flushes shell → continuations → final payload → close
    4. response body is a PipedInputStream; we drain it into a string
    5. asserts on chunk shapes + final-payload."
  (:require [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.ssr :as ssr]
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
                      :root-view [:test/root]
                      :payload-policy :rf.ssr.payload/whole-app-db})
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
                      :root-view [:test/multi-root]
                      :payload-policy :rf.ssr.payload/whole-app-db})
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
                      :root-view [:test/fragile-root]
                      :payload-policy :rf.ssr.payload/whole-app-db})
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
                      :root-view [:test/static-root]
                      :payload-policy :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (drain-stream (:body response))]
      (is (str/includes? body "<h1>Just static</h1>") "static content emitted")
      (is (str/includes? body "__rf_payload") "final payload emitted")
      (is (not (str/includes? body "data-rf2-suspense-id")) "no boundary markers")
      (is (str/includes? body "</body></html>") "body closes cleanly"))))

;; ===========================================================================
;; rf2-ee38b.11 — streaming redirect short-circuit MUST destroy the
;; per-request frame.
;;
;; The redirect branch returns BEFORE the writer thread (whose `finally`
;; tears the frame down on the streaming path) is ever spawned, so the
;; teardown must happen inline. Pre-fix the redirect branch returned
;; directly from inside the `try` with no enclosing `finally`, leaking
;; the per-request frame + its three side-channel slots (request /
;; response / pending-error-trace) on every redirected streaming request
;; — a per-request leak on auth-gated SSR routes (login redirects) where
;; redirects are common. Spec 011 §Per-request frame teardown contract.
;; ===========================================================================

(deftest stream-handler-redirect-destroys-frame
  (testing "rf2-ee38b.11: a :rf.server/redirect on the streaming path
            short-circuits to a Location response AND destroys the per-
            request frame inline — no frame / side-channel-slot leak.
            The redirect branch never spawns the writer thread, so the
            teardown CANNOT defer to the writer's finally."
    (rf/reg-event-fx :rf.test.stream/redirect
      {:platforms #{:server}}
      (fn [_ _]
        {:fx [[:rf.server/redirect {:status 302 :location "/login"}]]}))
    (rf/reg-view ^{:rf/id :test/should-not-stream} should-not-stream []
      [:div "should not render under redirect"])
    (let [handler       (ssr-ring/stream-handler
                          {:on-create [:rf.test.stream/redirect]
                           :root-view [:test/should-not-stream]
                           :payload-policy :rf.ssr.payload/whole-app-db})
          baseline-fids (disj (frame/frame-ids) :rf/default)
          response      (handler {:uri "/secret" :request-method :get})]
      ;; Redirect response shape — status + Location, empty body, no
      ;; chunked InputStream (a redirect has no streamed body).
      (is (= 302 (:status response)) "redirect status on the wire")
      (let [headers (:headers response)
            loc     (or (get headers "Location") (get headers "location"))]
        (is (= "/login" loc) "Location header carries the redirect target"))
      (is (= "" (:body response))
          "redirect short-circuits the stream — empty body, no InputStream")
      (is (not (instance? InputStream (:body response)))
          "redirect body is NOT a streamed pipe — it short-circuits")
      ;; Teardown — the per-request frame + its request slot MUST be
      ;; gone immediately after the call (no writer thread to wait on).
      (let [end-fids (disj (frame/frame-ids) :rf/default)
            leaked   (clojure.set/difference end-fids baseline-fids)]
        (is (empty? leaked)
            (str "the per-request frame MUST be destroyed on the streaming
                 redirect path — found leaked frame-ids: " (vec leaked))))
      (doseq [fid (disj (frame/frame-ids) :rf/default)]
        (is (nil? (ssr/get-request fid))
            (str "no request slot leaks for frame " fid))))))

(deftest stream-handler-redirect-no-content-type-stamped
  (testing "rf2-ee38b.11: the streaming redirect path does NOT stamp a
            default Content-Type on the bodiless 302 — it agrees with the
            non-streaming redirect path (which passes no default-content-
            type). A redirect has no body, so a defaulted Content-Type is
            meaningless; the two handlers must not diverge."
    (rf/reg-event-fx :rf.test.stream/redirect-ct
      {:platforms #{:server}}
      (fn [_ _]
        {:fx [[:rf.server/redirect {:status 302 :location "/login"}]]}))
    (rf/reg-view ^{:rf/id :test/redirect-ct-root} redirect-ct-root []
      [:div "noop"])
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.stream/redirect-ct]
                      :root-view [:test/redirect-ct-root]
                      :payload-policy :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/secret" :request-method :get})
          headers  (:headers response)
          ct       (or (get headers "Content-Type") (get headers "content-type"))]
      (is (= 302 (:status response)))
      ;; The SSR runtime may set a Content-Type in the response
      ;; accumulator's default headers; the contract under test is that
      ;; the streaming redirect does not ADD the handler's
      ;; `:content-type` default on top — it passes the 2-arg form. Pin
      ;; the agreement: whatever Content-Type rides is the accumulator's,
      ;; identical to the non-streaming redirect of the same response.
      (let [ns-handler (ssr-ring/ssr-handler
                         {:on-create [:rf.test.stream/redirect-ct]
                          :root-view [:test/redirect-ct-root]
                          :payload-policy :rf.ssr.payload/whole-app-db})
            ns-resp    (ns-handler {:uri "/secret" :request-method :get})
            ns-ct      (or (get (:headers ns-resp) "Content-Type")
                           (get (:headers ns-resp) "content-type"))]
        (is (= ct ns-ct)
            "streaming + non-streaming redirect paths agree on Content-Type")))))
