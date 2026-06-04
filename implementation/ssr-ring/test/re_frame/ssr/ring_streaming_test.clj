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
            [re-frame.interop :as interop]
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
                      :payload :rf.ssr.payload/whole-app-db})
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
                      :payload :rf.ssr.payload/whole-app-db})
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
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (drain-stream (:body response))]
      (is (= 200 (:status response)) "stream still 200 — failure is partial-render-safe")
      (is (str/includes? body "<h1>Header</h1>") "shell rendered")
      (is (str/includes? body "<footer>End</footer>") "rest of shell rendered")
      (is (str/includes? body "data-rf2-suspense-failed=\"1\"") "failed marker stamped")
      (is (str/includes? body "Still loading") "fallback materialised in failed chunk")
      (is (str/includes? body "__rf_payload") "final payload still emitted"))))

(deftest stream-handler-nested-boundary-drains-inner-FIFO
  (testing "rf2-sgvn6 / rf2-b1v8v: an OUTER :rf/suspense-boundary whose
            subtree contains an INNER :rf/suspense-boundary streams
            end-to-end. The writer drains a GROWABLE FIFO: the inner
            boundary registers DURING the outer continuation's render and
            its resolved chunk streams at the TAIL — AFTER the outer's
            resolved chunk (Spec 011 §922-924/§966/§983). The outer must
            NOT be marked failed; the inner must resolve its own body."
    (rf/reg-view ^{:rf/id :test/inner-section} inner-section []
      [:div.inner-body "INNER BODY CONTENT"])
    (rf/reg-view ^{:rf/id :test/outer-section} outer-section []
      [:section.outer
       [:p "outer content above inner"]
       [:rf/suspense-boundary
        {:id :test/inner :fallback [:p.inner-fallback "inner loading"]}
        [:test/inner-section]]])
    (rf/reg-view ^{:rf/id :test/nested-root} nested-root []
      [:main
       [:h1 "Nested"]
       [:rf/suspense-boundary
        {:id :test/outer :fallback [:p.outer-fallback "outer loading"]}
        [:test/outer-section]]
       [:footer "End"]])
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.server/init]
                      :root-view [:test/nested-root]
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (drain-stream (:body response))
          ;; Wire offsets pinning the FIFO drain order.
          idx-outer-fallback (str/index-of body "data-rf2-suspense-id=\":test/outer\" data-rf2-suspense-fallback=\"1\"")
          idx-outer-resolved (str/index-of body "data-rf2-suspense-id=\":test/outer\" data-rf2-suspense-resolved=\"1\"")
          idx-inner-fallback (str/index-of body "data-rf2-suspense-id=\":test/inner\" data-rf2-suspense-fallback=\"1\"")
          idx-inner-resolved (str/index-of body "data-rf2-suspense-id=\":test/inner\" data-rf2-suspense-resolved=\"1\"")
          idx-inner-body     (str/index-of body "INNER BODY CONTENT")
          idx-payload        (str/index-of body "__rf_payload")
          idx-close          (str/index-of body "</body></html>")]
      (is (= 200 (:status response)) "nested stream still 200")
      ;; Shell carries the OUTER fallback only — the inner is buried in
      ;; the unresolved outer subtree.
      (is (some? idx-outer-fallback) "outer fallback placeholder in the shell")
      (is (str/includes? body "outer loading") "outer fallback text in the shell")
      (is (< idx-outer-fallback (or idx-inner-fallback Long/MAX_VALUE))
          "the inner fallback is NOT in the shell — it appears only inside
           the outer's resolved chunk, which streams after the shell")
      ;; CRITICAL — the outer continuation resolved CLEANLY (no failed
      ;; marker on the outer). Pre-fix the outer was marked failed because
      ;; the buried inner boundary threw through the non-streaming emitter.
      (is (some? idx-outer-resolved)
          "outer resolved chunk emitted (NOT a failed re-emit of its fallback)")
      (is (not (str/includes? body "data-rf2-suspense-id=\":test/outer\" data-rf2-suspense-resolved=\"1\" data-rf2-suspense-failed=\"1\""))
          "the OUTER boundary is NOT marked failed — it resolved through
           the streaming walker (rf2-sgvn6)")
      (is (str/includes? body "outer content above inner")
          "the outer subtree's static content resolved")
      ;; The inner boundary's FALLBACK template appears INSIDE the outer's
      ;; resolved chunk (the inner is deferred at outer-drain time).
      (is (some? idx-inner-fallback)
          "the inner boundary's fallback placeholder appears (inside the
           outer resolved chunk) — the inner registered during the outer
           drain")
      ;; The inner's OWN resolved chunk + body stream LAST (FIFO tail).
      (is (some? idx-inner-resolved)
          "the inner boundary's resolved chunk streamed — the nested
           continuation was appended to the FIFO and drained")
      (is (some? idx-inner-body)
          "the inner resolved chunk carries the inner body content")
      (is (some? idx-payload) "final __rf_payload still emitted")
      (is (some? idx-close) "body close emitted")
      ;; FIFO drain-order contract: outer fallback (shell) → outer resolved
      ;; → inner resolved → final payload → close. The inner resolved chunk
      ;; lands AFTER the outer resolved chunk (registered at the tail).
      (is (< idx-outer-fallback idx-outer-resolved)
          "outer fallback (shell) before outer resolved chunk")
      (is (< idx-outer-resolved idx-inner-resolved)
          "inner resolved chunk streams AFTER the outer resolved chunk —
           FIFO tail registration (Spec 011 §966/§983)")
      (is (< idx-inner-resolved idx-payload)
          "inner resolved chunk before the final payload")
      (is (< idx-payload idx-close)
          "final payload before body close"))))

(deftest stream-handler-no-boundary-zero-continuations
  (testing "A tree with NO :rf/suspense-boundary still streams cleanly (degenerate case)"
    (rf/reg-view ^{:rf/id :test/static-root} static-root []
      [:main [:h1 "Just static"]])
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.server/init]
                      :root-view [:test/static-root]
                      :payload :rf.ssr.payload/whole-app-db})
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
                           :payload :rf.ssr.payload/whole-app-db})
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
                      :payload :rf.ssr.payload/whole-app-db})
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
                          :payload :rf.ssr.payload/whole-app-db})
            ns-resp    (ns-handler {:uri "/secret" :request-method :get})
            ns-ct      (or (get (:headers ns-resp) "Content-Type")
                           (get (:headers ns-resp) "content-type"))]
        (is (= ct ns-ct)
            "streaming + non-streaming redirect paths agree on Content-Type")))))

;; ===========================================================================
;; rf2-r06pc — STREAMING SHELL FAILURES FAIL CLOSED to a non-200
;; ===========================================================================
;;
;; The bug: streaming materialised the Ring response head (status 200) and
;; spawned the daemon writer BEFORE the writer resolved/rendered the shell.
;; So a root-view throw, a shell-walk throw, and a production-mode reactive
;; sub throw during the shell render could NOT stamp the HTTP status or
;; render the projected error page — the wire shipped a silent 200 /
;; truncated body. The non-streaming handler already fixed the recovered-
;; sub variant with a post-render re-flush (rf2-c0bq1, `pipeline.clj`).
;;
;; The fix (rf2-r06pc): render the shell on the REQUEST thread before the
;; head commits. A throw escalates to `:rf.error/ssr-render-failed` (→
;; projected non-200 error page); a recovered-to-nil sub buffers a fail-
;; closed status the post-shell `get-response` re-read picks up onto the
;; committed Ring head. Only a known-renderable shell + success status
;; commits the chunked response. These direct-handler tests pin the
;; contract in-process; the bytes-on-wire counterparts (root-view throw +
;; production-sub throw through Jetty) live in `streaming_robustness_test`.
;; ===========================================================================

(defn- streamed-body
  "Drain a Ring response body to a string when it is an InputStream
  (the streamed-chunk path), else return it verbatim (a projected error
  page is an ordinary non-chunked String body)."
  [body]
  (if (instance? InputStream body)
    (drain-stream body)
    body))

(deftest stream-handler-root-view-throw-fails-closed
  (testing "rf2-r06pc: a root-view fn that throws on resolution fails
            closed to a non-200 PROJECTED error response on the request
            thread — NOT a streamed 200. No InputStream body is handed
            out (the chunked response was never committed)."
    (rf/reg-event-fx :rf.test.server/init-min
      {:platforms #{:server}}
      (fn [_ _] {:db {}}))
    (let [throwing-root (fn root-view-fn []
                          (throw (ex-info ":rf.test/root-view-throw"
                                          {:reason "shell-render fail-closed probe"})))
          handler       (ssr-ring/stream-handler
                          {:on-create [:rf.test.server/init-min]
                           :root-view throwing-root
                           :payload :rf.ssr.payload/whole-app-db})
          response      (handler {:uri "/" :request-method :get})]
      (is (= 500 (:status response))
          "root-view throw → `:rf.error/ssr-render-failed` projection →
           non-200 (default projector maps to 500) on the request thread,
           not a streamed 200 (Spec 011 §744/§748/§954)")
      (is (not (instance? InputStream (:body response)))
          "the body is a projected error page (ordinary String), NOT a
           PipedInputStream — the chunked response was never committed")
      (is (not (str/includes? (str (:body response)) "root-view-throw"))
          "the projected body carries no internal exception detail"))))

(deftest stream-handler-shell-walk-throw-fails-closed
  (testing "rf2-r06pc: a view INSIDE the shell walk (NOT inside a
            `:rf/suspense-boundary`) that throws fails closed to a
            non-200 — the shell-walk throw is a structural failure that
            escalates per Spec 011 §954 (the inline-fallback boundary
            stops at continuations)."
    (rf/reg-event-fx :rf.test.server/init-min
      {:platforms #{:server}}
      (fn [_ _] {:db {}}))
    ;; A view that throws during the shell walk — it is NOT wrapped in a
    ;; `:rf/suspense-boundary`, so the inline-fallback path does NOT apply.
    (rf/reg-view ^{:rf/id :test/shell-throwing-section} shell-throwing-section []
      (throw (ex-info ":rf.test/shell-walk-throw" {})))
    (rf/reg-view ^{:rf/id :test/shell-throwing-root} shell-throwing-root []
      [:main
       [:h1 "Header"]
       [:test/shell-throwing-section]
       [:footer "End"]])
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.server/init-min]
                      :root-view [:test/shell-throwing-root]
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})]
      (is (= 500 (:status response))
          "shell-walk throw escalates to `:rf.error/ssr-render-failed` →
           non-200; the inline-fallback boundary stops at continuations
           (Spec 011 §954)")
      (is (not (instance? InputStream (:body response)))
          "shell-walk throw fails closed to a projected error page, not a
           streamed chunked body"))))

(deftest stream-handler-rendertime-sub-throw-fails-closed
  (testing "rf2-r06pc / rf2-vvwmi: a production-mode reactive sub that
            THROWS during the streaming shell render recovers to nil (the
            walk does NOT throw) but buffers a fail-closed 500 on the
            always-on error-emit substrate; the post-shell `get-response`
            re-read stamps the 500 onto the committed Ring head — NOT the
            stale pre-render 200 the old daemon-writer-first order shipped
            (Spec 011 §744/§750)."
    (rf/reg-sub :throwing-sub (fn [_db _] (throw (ex-info "sub-boom" {}))))
    (rf/reg-view ^{:rf/id :test/uses-throwing-sub} uses-throwing-sub []
      (let [v @(rf/subscribe [:throwing-sub])]
        [:main.broken
         [:h1 "header that renders"]
         [:p (str "value: " v)]]))
    (rf/reg-event-fx :rf.test.server/init-min
      {:platforms #{:server}}
      (fn [_ _] {:db {}}))
    (let [handler (ssr-ring/stream-handler
                    {:on-create [:rf.test.server/init-min]
                     :root-view [:test/uses-throwing-sub]
                     :ssr       {:public-error-id   :rf.ssr/default-error-projector
                                 :dev-error-detail? false}
                     :payload :rf.ssr.payload/whole-app-db})]
      ;; Production hardening — the dev-only trace listener elides; the
      ;; always-on error-emit substrate is the status source of truth.
      (with-redefs [interop/debug-enabled? false]
        (let [response (handler {:uri "/" :request-method :get})]
          (is (= 500 (:status response))
              "render-time sub-throw under production hardening →
               buffered fail-closed 500 re-read AFTER the shell render →
               committed Ring status 500. Before rf2-r06pc this was a
               silent 200 (the stale pre-render status committed onto the
               chunked head before the daemon writer ran the render).")
          ;; The shell recovered-to-nil and rendered, so the streamed body
          ;; (carried under the 500 status) is still well-formed HTML —
          ;; the status is the fail-closed signal, mirroring the non-
          ;; streaming handler (rf2-c0bq1).
          (let [body (streamed-body (:body response))]
            (is (str/includes? body "header that renders")
                "the recovered shell still streamed (the 500 status is the
                 fail-closed signal; the body is moot under a non-200)")))))))

(deftest stream-handler-clean-render-stays-200
  (testing "rf2-r06pc: the request-thread shell render + post-shell
            re-read is benign for the happy path — a clean shell render
            with no buffered error keeps the default 200 and streams
            normally. Confirms the fix is fail-closed-on-error, not a
            blanket divert."
    (rf/reg-sub :clean-sub (fn [_db _] :ok))
    (rf/reg-view ^{:rf/id :test/uses-clean-sub} uses-clean-sub []
      (let [v @(rf/subscribe [:clean-sub])]
        [:main [:h1 "clean"] [:p (str "value: " v)]]))
    (rf/reg-event-fx :rf.test.server/init-min
      {:platforms #{:server}}
      (fn [_ _] {:db {}}))
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.server/init-min]
                      :root-view [:test/uses-clean-sub]
                      :ssr       {:public-error-id   :rf.ssr/default-error-projector
                                  :dev-error-detail? false}
                      :payload :rf.ssr.payload/whole-app-db})]
      (with-redefs [interop/debug-enabled? false]
        (let [response (handler {:uri "/" :request-method :get})
              body     (streamed-body (:body response))]
          (is (= 200 (:status response))
              "clean shell render → empty error buffer → re-read no-op → 200")
          (is (instance? InputStream (:body response))
              "happy path streams a chunked InputStream body")
          (is (str/includes? body "value: :ok")
              "the clean sub's value rendered into the streamed HTML")
          (is (str/includes? body "__rf_payload")
              "the final payload chunk streamed"))))))
