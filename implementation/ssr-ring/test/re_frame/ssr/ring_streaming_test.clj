(ns re-frame.ssr.ring-streaming-test
  "Streaming SSR Ring adapter — chunked-response wiring. Per Spec 011
  §Streaming SSR (rf2-ojakd / rf2-olb64 (a)).

  Exercises the full request lifecycle through `stream-handler`:
    1. Ring request comes in
    2. setup-request-frame! seeds the frame + on-create
    3. streaming writer flushes shell → continuations → final payload → close
    4. response body is a PipedInputStream; we drain it into a string
    5. asserts on chunk shapes + final-payload."
  (:require [clojure.edn]
            [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.ssr.ring.lifecycle :as lifecycle]
            [re-frame.ssr.ring.test-support :as ts])
  (:import [java.io InputStream]))

(defn- reset+reg-test-handlers
  [test-fn]
  (ts/reset-runtime
    (fn []
      (rf/reg-event :rf.test/seed-articles
        (fn [{:keys [db]} [_ arts]] {:db {:articles arts}}))
      (rf/reg-event :rf.test.server/init
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

;; ===========================================================================
;; rf2-z9dduj — the streaming shell closes the app root (`</div>`) at the END
;; of the shell chunk, BEFORE the resolved templates, hydration-delta scripts,
;; and the final `__rf_payload`. Pre-fix the close lived in
;; `default-streaming-suffix`, AFTER every protocol chunk — so resolved
;; templates + the `__rf_payload` script landed INSIDE the application root,
;; leaking streaming control/protocol nodes into the DOM `#app` a client
;; renderer/hydrator owns (hydration-mismatch / replacement risk). Spec 011
;; §Chunk-ordering contract pins chunk 1 as `…<div id="app"><shell-html/></div>`
;; and the non-streaming `default-html-shell` already closes `</div>` before the
;; payload script.
;; ===========================================================================

(deftest stream-handler-closes-app-root-before-protocol-chunks
  (testing "rf2-z9dduj: the app-root `</div>` is emitted at the END of the
            shell chunk — BEFORE the first resolved template AND before the
            final `__rf_payload` — so the resolved templates, delta scripts,
            and payload all stream OUTSIDE `#app` (Spec 011 §998-1001). The
            `:test/root` shell has NO inner `<div>`, so the only `</div>` in
            the wire is the app-root close."
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.server/init]
                      :root-view [:test/root]
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (drain-stream (:body response))
          idx-app-open  (str/index-of body "<div id=\"app\"")
          ;; The app-root close: `:test/root` emits no nested <div>, so the
          ;; first (and only) `</div>` IS the app-root close.
          idx-app-close (str/index-of body "</div>")
          idx-resolved  (str/index-of body "data-rf2-suspense-resolved=\"1\"")
          idx-payload   (str/index-of body "__rf_payload")
          idx-comments  (str/index-of body "First!")]
      (is (= 200 (:status response)))
      (is (some? idx-app-open) "the #app root div opened")
      (is (some? idx-app-close) "an app-root close exists")
      (is (some? idx-resolved) "a resolved subtree chunk streamed")
      (is (some? idx-payload) "the final payload chunk streamed")
      ;; THE PINS — the app-root close precedes the protocol chunks.
      (is (< idx-app-open idx-app-close)
          "the `</div>` comes after the `<div id=\"app\"` open")
      (is (< idx-app-close idx-resolved)
          "rf2-z9dduj: the app root closes BEFORE the first resolved template
           — resolved chunks stream OUTSIDE #app, not nested inside it")
      (is (< idx-app-close idx-payload)
          "rf2-z9dduj: the app root closes BEFORE the final __rf_payload —
           the payload script streams OUTSIDE #app (matching the non-streaming
           shell, which closes </div> before the payload script)")
      ;; The resolved comment body is itself a child of the resolved
      ;; <template>, which is OUTSIDE #app — so it too lands after the close.
      (is (< idx-app-close idx-comments)
          "the resolved subtree body streams after the app-root close")
      ;; Defense-in-depth: NO `__rf_payload` appears between the #app open and
      ;; its close (i.e. the payload is not nested in the app root).
      (let [app-inner (subs body idx-app-open idx-app-close)]
        (is (not (str/includes? app-inner "__rf_payload"))
            "no __rf_payload script nested inside #app")
        (is (not (str/includes? app-inner "data-rf2-suspense-resolved"))
            "no resolved-template protocol node nested inside #app")))))

(deftest stream-handler-suffix-no-longer-emits-app-close
  (testing "rf2-z9dduj: `default-streaming-suffix` no longer carries the
            app-root `</div>` (it moved to the shell chunk). The suffix is now
            purely the bootstrap script + body-end + document close."
    (let [suffix (ssr-ring/default-streaming-suffix {:script-src "/main.js"})]
      (is (not (str/includes? suffix "</div>"))
          "the suffix no longer closes the app root")
      (is (str/includes? suffix "</body></html>")
          "the suffix still closes the document")
      (is (str/includes? suffix "<script src=\"/main.js\">")
          "the suffix still emits the bootstrap script"))))

(deftest stream-handler-no-boundary-closes-app-root-before-payload
  (testing "rf2-z9dduj: the degenerate (no-suspense) path also closes #app
            before the final __rf_payload — the wire shape collapses to
            shell-prefix + shell-html + `</div>` + payload + suffix."
    (rf/reg-view ^{:rf/id :test/static-only} static-only []
      [:main [:h1 "Static"]])
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.server/init]
                      :root-view [:test/static-only]
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (drain-stream (:body response))
          idx-close   (str/index-of body "</div>")
          idx-payload (str/index-of body "__rf_payload")]
      (is (some? idx-close) "the app root closed")
      (is (some? idx-payload) "the payload streamed")
      (is (< idx-close idx-payload)
          "rf2-z9dduj: even with zero continuations, #app closes before the
           __rf_payload script")
      (let [app-inner (subs body (str/index-of body "<div id=\"app\"") idx-close)]
        (is (not (str/includes? app-inner "__rf_payload"))
            "the payload is not nested inside #app on the degenerate path")))))

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
;; rf2-kjf3m.5 — the per-boundary hydration-delta <script> is emitted ONLY
;; for a boundary whose render CHANGED app-db. A continuation that merely
;; READS state (the common case — deferred subtrees typically subscribe,
;; they do not mutate) yields an EMPTY delta (`{}`) from `subtree-delta`,
;; and the writer must emit NO `<script data-rf2-suspense-hydrate>` chunk
;; for it. Pre-fix the writer guard was `(some? delta)` — `{}` is `some?`,
;; so every unchanged boundary shipped an inert `…hydrate=…>{}</script>`
;; the client parsed and discarded; the guard is now `(seq delta)`.
;; ===========================================================================

(deftest stream-handler-skips-empty-delta-script-on-unchanged-boundary
  (testing "rf2-kjf3m.5: a boundary whose continuation MUTATES app-db emits
            its delta script; a boundary whose continuation only READS state
            (empty/unchanged delta) emits NO `data-rf2-suspense-hydrate`
            script — not an inert `{}` chunk."
    ;; A db-mutating event the deferred subtree dispatches during its render
    ;; — produces a non-empty per-subtree delta for the :changed boundary.
    (rf/reg-event :rf.test/bump-counter
      {:platforms #{:server}}
      (fn [{:keys [db]} _] {:db (update db :counter (fnil inc 0))}))
    ;; Subtree that mutates app-db during render → non-empty delta.
    (rf/reg-view ^{:rf/id :test/mutating-section} mutating-section []
      (rf/dispatch-sync [:rf.test/bump-counter])
      [:div.mutated "mutated content"])
    ;; Subtree that only READS app-db (subscribes, no mutation) → empty delta.
    (rf/reg-view ^{:rf/id :test/reading-section} reading-section []
      (let [arts @(subscribe [:articles])]
        (into [:div.read] (for [{:keys [title]} arts] [:span title]))))
    (rf/reg-view ^{:rf/id :test/delta-root} delta-root []
      [:main
       [:h1 "Deltas"]
       [:rf/suspense-boundary
        {:id :test/changed :fallback [:p "changed loading"]}
        [:test/mutating-section]]
       [:rf/suspense-boundary
        {:id :test/unchanged :fallback [:p "unchanged loading"]}
        [:test/reading-section]]
       [:footer "End"]])
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.server/init]
                      :root-view [:test/delta-root]
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (drain-stream (:body response))]
      (is (= 200 (:status response)) "stream still 200")
      ;; Both boundaries resolved (their bodies are on the wire).
      (is (str/includes? body "mutated content")
          "the mutating boundary's resolved body streamed")
      (is (str/includes? body "Article A")
          "the reading boundary's resolved body streamed")
      ;; The mutating boundary SHIPS its delta script.
      (is (str/includes? body "data-rf2-suspense-hydrate=\":test/changed\"")
          "the boundary that changed app-db ships its hydration-delta script")
      ;; THE PIN: the read-only boundary ships NO delta script. Pre-fix this
      ;; emitted `data-rf2-suspense-hydrate=":test/unchanged" …>{}</script>`.
      (is (not (str/includes? body "data-rf2-suspense-hydrate=\":test/unchanged\""))
          "the unchanged boundary ships NO hydration-delta script — an empty
           `{}` delta is skipped, not emitted as an inert script chunk
           (rf2-kjf3m.5: guard is `(seq delta)`, not `(some? delta)`)")
      ;; No empty-EDN delta body anywhere on the wire.
      (is (not (str/includes? body ">{}</script>"))
          "no empty-`{}` delta-script body crosses the wire")
      (is (str/includes? body "__rf_payload") "final payload still emitted"))))

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
    (rf/reg-event :rf.test.stream/redirect
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
    (rf/reg-event :rf.test.stream/redirect-ct
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
    (rf/reg-event :rf.test.server/init-min
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
    (rf/reg-event :rf.test.server/init-min
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
    (rf/reg-event :rf.test.server/init-min
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
    (rf/reg-event :rf.test.server/init-min
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

;; ===========================================================================
;; rf2-5knxf.1 — a :redirect surfacing at the POST-SHELL re-read must ship a
;;               bodiless redirect, NOT a streamed body, and spawn NO writer.
;; ===========================================================================
;;
;; The early redirect branch (stream-handler, on the :on-create-drain
;; `get-response`) is already covered by stream-handler-redirect-destroys-
;; frame above. This test covers the SECOND `get-response` — the post-shell
;; re-read at the materialise site (rf2-r06pc). Pre-fix, that branch
;; UNCONDITIONALLY materialised `resp-map`, spawned the daemon writer, and
;; assoc'd the pipe `:body` — so a `:redirect` carried on the post-shell
;; accumulator would have shipped a malformed 3xx + Location + a FULL
;; streamed HTML body (the writer pumps shell + payload + close) for a wire
;; response the contract says must be bodiless (Spec 011 §Redirect
;; precedence). The non-streaming handler gets this for free
;; (`ssr-response->ring-response` ignores the body arg on its `:redirect`
;; branch); the streaming path had to branch explicitly.
;;
;; A `:redirect` cannot surface at the post-shell read under v1's
;; architecture (it is set only by the `:rf.server/redirect` fx during the
;; `:on-create` drain — caught by the EARLY branch — and the error projector
;; stamps `:status` only, never `:redirect`). So this is a LATENT fail-open:
;; we simulate the latent condition by stubbing `ssr/get-response` to return
;; a non-redirect on the FIRST call (so the early branch passes through to
;; the shell render) and a redirect on the SECOND call (the post-shell
;; re-read). This exercises the new post-shell redirect branch directly.

(deftest stream-handler-post-shell-redirect-bodiless
  (testing "rf2-5knxf.1: a :redirect surfacing at the POST-SHELL get-response
            re-read ships a bodiless Location response (NOT a streamed
            body), spawns NO writer thread, and destroys the frame inline."
    (rf/reg-event :rf.test.server/init-min
      {:platforms #{:server}}
      (fn [_ _] {:db {}}))
    (rf/reg-view ^{:rf/id :test/plain-root} plain-root []
      [:main [:h1 "rendered shell"]])
    (let [real-get-response ssr/get-response
          ;; Per-frame call counter so we stub ONLY the second
          ;; get-response (the post-shell re-read) for our frame, leaving
          ;; the first (early-branch drain read) untouched. Keying on the
          ;; arg-count of calls keeps the stub from perturbing any other
          ;; frame's reads.
          calls (atom 0)
          redirect-resp {:status   302
                         :headers  []
                         :cookies  []
                         :redirect {:status 302 :location "/post-shell-login"}}]
      (with-redefs [ssr/get-response
                    (fn [fid]
                      (let [n (swap! calls inc)]
                        ;; 1st call = early-branch drain read → real value
                        ;; (no redirect, so the handler proceeds to render
                        ;; the shell). 2nd call = post-shell re-read → inject
                        ;; the latent redirect.
                        (if (= n 2)
                          redirect-resp
                          (real-get-response fid))))]
        (let [handler       (ssr-ring/stream-handler
                              {:on-create [:rf.test.server/init-min]
                               :root-view [:test/plain-root]
                               :payload :rf.ssr.payload/whole-app-db})
              baseline-fids (disj (frame/frame-ids) :rf/default)
              response      (handler {:uri "/secret" :request-method :get})]
          (is (= 302 (:status response))
              "post-shell redirect ships the 3xx status on the wire")
          (let [headers (:headers response)
                loc     (or (get headers "Location") (get headers "location"))]
            (is (= "/post-shell-login" loc)
                "Location header carries the post-shell redirect target"))
          (is (= "" (:body response))
              "post-shell redirect is BODILESS — :body \"\", NOT a streamed
               full HTML document (the latent fail-open this fix closes)")
          (is (not (instance? InputStream (:body response)))
              "post-shell redirect body is NOT a PipedInputStream — no
               writer pipe is handed out")
          ;; No writer thread spawned + frame torn down inline (the writer's
          ;; finally — the streaming teardown path — never runs on this
          ;; branch, so the inline destroy is load-bearing).
          (let [end-fids (disj (frame/frame-ids) :rf/default)
                leaked   (clojure.set/difference end-fids baseline-fids)]
            (is (empty? leaked)
                (str "the per-request frame MUST be destroyed inline on the
                     post-shell redirect branch — leaked frame-ids: "
                     (vec leaked)))))))))

;; ===========================================================================
;; rf2-5knxf.2 — the streaming final-payload :rf/render-hash MATCHES the
;;               client's resolved-tree hash (markers and all) when forms
;;               are symmetric; the suspense marker is NOT a divergence.
;; ===========================================================================
;;
;; The concern (rf2-5knxf.2) was that the streaming final hash is computed
;; over the suspense-marker-bearing tree while the client recomputes over a
;; "resolved" (marker-free) tree, firing a spurious
;; :rf.ssr/hydration-mismatch on a successful streaming hydration.
;;
;; That premise is empirically FALSE. `render-tree-hash` is a PURE
;; structural FNV-1a over the canonical-EDN of the hiccup — it does not
;; expand views and does not throw on `:rf/suspense-boundary`; the marker
;; hashes to the same canonical-EDN bytes on both sides. A streaming-aware
;; client verifies via `:render-tree-fn #((rf/view :app/root))`, whose
;; result is the SAME marker-bearing hiccup the server's `(rf/view
;; :app/root)` produces. This test pins that equality directly, and pins
;; that the streaming hash is computed by the IDENTICAL mechanism as the
;; non-streaming handler (the only divergence is the host's choice of
;; view-ref vs expanded form for :root-view / :render-tree-fn — shared by
;; both handlers, not a streaming marker bug).

(deftest streaming-final-hash-matches-client-resolved-tree
  (testing "rf2-5knxf.2: the server's render-tree-hash of the marker-bearing
            root view EQUALS the client's `#((rf/view :root))` hash — the
            suspense marker is NOT a divergence (render-tree-hash is a pure
            structural hash that does not expand or reject the marker)."
    (rf/reg-event :rf.test.server/init
      {:platforms #{:server}}
      (fn [_ _] {:db {:comments [{:body "First!"}]}}))
    (rf/reg-sub :comments (fn [db _] (:comments db)))
    (rf/reg-view ^{:rf/id :test/comments-section} comments-view []
      (let [cs @(subscribe [:comments])]
        (into [:ul.comments] (for [{:keys [body]} cs] [:li body]))))
    (rf/reg-view ^{:rf/id :test/hash-root} hash-root []
      [:main [:h1 "News"]
       [:rf/suspense-boundary {:id :test/comments :fallback [:p "Loading…"]}
        [:test/comments-section]]
       [:footer "End"]])
    (let [fid (keyword "rf.frame" (str (gensym "f")))]
      (rf/reg-frame fid {:platform :server :on-create [:rf.test.server/init]})
      (try
        (rf/with-frame fid
          (let [;; The server streaming handler computes the final hash over
                ;; `hiccup` = (resolve-root-view root-view). When the host
                ;; passes an EXPANDED form (symmetric with the client's
                ;; :render-tree-fn), this is the marker-bearing root tree.
                server-hiccup (lifecycle/resolve-root-view
                                (fn [] ((rf/view :test/hash-root))))
                server-hash   (ssr/render-tree-hash server-hiccup)
                ;; The client verifies via :render-tree-fn #((rf/view :root)).
                client-hiccup ((rf/view :test/hash-root))
                client-hash   (ssr/render-tree-hash client-hiccup)]
            ;; The marker IS present in both trees (the equality is NOT
            ;; achieved by stripping it).
            (is (str/includes? (pr-str server-hiccup) ":rf/suspense-boundary")
                "server hiccup carries the :rf/suspense-boundary marker")
            (is (str/includes? (pr-str client-hiccup) ":rf/suspense-boundary")
                "client hiccup carries the :rf/suspense-boundary marker")
            ;; The load-bearing assertion: marker-bearing server hash ==
            ;; marker-bearing client hash. No spurious mismatch.
            (is (= server-hash client-hash)
                "streaming final-payload render-hash (over the marker-bearing
                 resolved root) EQUALS the client's resolved-tree hash — the
                 suspense marker hashes symmetrically on both sides, so a
                 successful streaming hydration fires NO spurious
                 :rf.ssr/hydration-mismatch (rf2-5knxf.2)")))
        (finally
          (rf/destroy-frame! fid))))))

;; ===========================================================================
;; rf2-7rkc0 — STREAMING-path counterpart of the non-streaming
;; handler-throwing-head-fn-ships-degraded-200 wire pin
;; (ring_test.clj `handler-throwing-head-fn-ships-degraded-200-not-projected-error`).
;;
;; CONTRACT (Spec 011 §1070, lifecycle/resolve-head, rf2-bof8i Option B):
;; a throwing route `:head` fn is a RECOVERABLE DEGRADATION — the request
;; still ships a 200 with an empty head fragment + body intact, AND emits
;; `:rf.error/ssr-head-resolution-failed` for observability; the throwable
;; message never reaches the wire.
;;
;; The STREAMING path resolves the head on the request thread inside
;; `render-streaming-shell!` (streaming.clj `lifecycle/resolve-head`),
;; BEFORE the chunked response head + status are committed and BEFORE the
;; daemon writer is spawned — so a head-resolution failure physically
;; cannot change the already-committed 200. The streaming path is thus
;; structurally MORE immune than the non-streaming one; this test pins the
;; degraded-stream-200 contract so a future refactor of the streaming head
;; path cannot regress it silently (the non-streaming side had a wire pin,
;; the streaming side did not — rf2-7rkc0).
;; ===========================================================================

(deftest stream-handler-throwing-head-fn-ships-degraded-streamed-200
  (testing "rf2-7rkc0: a throwing route :head fn on the STREAMING path →
            a streamed 200 (degraded — empty head, body chunks intact),
            NEVER a projected 4xx/5xx, AND the
            :rf.error/ssr-head-resolution-failed trace fires once."
    (rf/reg-head :test.stream/head-throws
                 (fn [_db _route]
                   (throw (ex-info "synthetic streaming head failure (rf2-7rkc0)"
                                   {:reason :test}))))
    (rf/reg-route :test.stream/route-head-throws
                  {:doc  "Streaming route whose head fn throws"
                   :path "/stream-head-throws"
                   :head :test.stream/head-throws})
    ;; EP-0001 (rf2-vzld77): the route slice is durable routing runtime-db state.
    (rf/reg-event :rf.test.stream/seed-throwing-head-route
      {:platforms #{:server}}
      (fn [{rt :rf.db/runtime} _]
        {:rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/routing :current]
                                  {:route-id :test.stream/route-head-throws})}))
    (rf/reg-view ^{:rf/id :test/stream-head-body} stream-head-body []
      [:main [:h1 "Streamed body rendered fine"]])
    (let [traces  (atom [])
          _       (rf/register-listener! ::stream-head-fail-watch
                    (fn [ev]
                      (when (= :rf.error/ssr-head-resolution-failed
                               (:operation ev))
                        (swap! traces conj ev))))
          handler (ssr-ring/stream-handler
                    {:on-create [:rf.test.stream/seed-throwing-head-route]
                     :root-view [:test/stream-head-body]
                     :payload   :rf.ssr.payload/whole-app-db})
          response (try
                     (handler {:uri "/stream-head-throws" :request-method :get})
                     (finally
                       (rf/unregister-listener! ::stream-head-fail-watch)))
          ;; Drain the chunked body — the head trace fires on the request
          ;; thread (before the writer), but draining proves the body chunks
          ;; still ship cleanly after the head degradation.
          body    (drain-stream (:body response))]
      ;; THE PIN: the streamed status is 200, not a projected 5xx. The head
      ;; status/headers are committed BEFORE the writer is spawned, so a
      ;; head-resolution throw cannot change the already-sent status — were
      ;; the head trace ever projected (a reorder regression), this 200
      ;; assertion turns red.
      (is (= 200 (:status response))
          "throwing :head fn degrades to a streamed 200 — NEVER a projected
           4xx/5xx (Spec 011 §1070; the streaming status commits BEFORE the
           writer thread + the projector-skip belt-and-suspenders, rf2-lia3i)")
      ;; The streamed body is intact: doctype + the rendered root view.
      (is (str/includes? body "<!DOCTYPE html>")
          "the document still streams — the shell + body rendered")
      (is (str/includes? body "Streamed body rendered fine")
          "the body view content streams intact — only the head degraded")
      (is (str/includes? body "__rf_payload")
          "the final payload chunk still streams")
      (is (str/includes? body "</body></html>")
          "the streamed body closes cleanly")
      ;; The throwable message never crosses the wire.
      (is (not (str/includes? body "synthetic streaming head failure"))
          "the throwable's message never reaches the streamed wire")
      ;; Observability preserved: degrade AND emit.
      (is (= 1 (count @traces))
          ":rf.error/ssr-head-resolution-failed fires once for observability
           on the streaming path too (Spec 011 §1070 Option B) — degraded,
           not silent"))))

;; ===========================================================================
;; rf2-h3dg0 — stale Content-Length on the streamed body
;;
;; `stream-handler` materialises the response head from the accumulator and
;; then replaces the body with a chunk-producing PipedInputStream. If app /
;; server init set a `Content-Length` header during the `:on-create` drain
;; (a fixed byte count for a body that no longer exists), that stale length
;; must NOT survive onto the streamed response — a Ring server may honour it
;; instead of chunking, truncating the HTML / blocking the client on the
;; wrong byte count / losing progressive chunks (Spec 011 §Streaming SSR —
;; chunked-transfer framing). The fix strips `Content-Length`
;; case-insensitively before wiring the InputStream body.
;; ===========================================================================

(defn- header-keys-lower
  "Lower-cased set of a Ring response's header keys — for
  case-insensitive header-presence assertions."
  [response]
  (into #{} (map (fn [k] (clojure.string/lower-case (str k))))
        (keys (:headers response))))

(deftest stream-handler-strips-stale-content-length-header
  (testing "rf2-h3dg0: an :on-create-set Content-Length is stripped
            (case-insensitively) from the streamed response so the Ring
            server owns chunked-transfer framing; the body still streams in
            full with NO Content-Length header surviving"
    (rf/reg-event :rf.test.server/init-with-content-length
      {:platforms #{:server}}
      (fn [_ _]
        {:db {:articles [{:id "a" :title "Article A"}]
              :comments [{:body "First!"}]}
         ;; A deliberately WRONG fixed length set before streaming was
         ;; chosen — both a canonical-cased and a lower-cased variant, to
         ;; prove the strip is case-insensitive across the materialiser's
         ;; verbatim header casing.
         :fx [[:rf.server/set-header {:name "Content-Length" :value "7"}]
              [:rf.server/append-header {:name "content-length" :value "13"}]]}))
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.server/init-with-content-length]
                      :root-view [:test/root]
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          ;; Capture the head BEFORE draining (the head is committed on the
          ;; request thread; the body pipe is separate).
          keys-lc  (header-keys-lower response)
          body     (drain-stream (:body response))]
      (is (= 200 (:status response)) "streamed response still 200")
      (is (instance? InputStream (:body response))
          "the body is the streaming PipedInputStream")
      (is (not (contains? keys-lc "content-length"))
          (str "NO Content-Length header survives on the streamed response "
               "(any casing) — the server frames the InputStream body as "
               "chunked. Header keys (lower-cased): " (pr-str keys-lc)))
      ;; The full payload is readable end-to-end — the strip did not break
      ;; the stream, and no byte-count cap truncated it.
      (is (str/includes? body "<!DOCTYPE html>") "shell streamed")
      (is (str/includes? body "Article A")
          "the resolved body content streamed in full")
      (is (str/includes? body "__rf_payload")
          "the final payload chunk streamed")
      (is (str/includes? body "</body></html>")
          "the streamed body closed cleanly — full payload readable, not
           truncated to a stale Content-Length"))))

(deftest stream-handler-preserves-content-type-when-stripping-content-length
  (testing "rf2-h3dg0: stripping Content-Length leaves the other head
            machinery intact — Content-Type still rides the streamed
            response"
    (rf/reg-event :rf.test.server/init-cl-and-ct
      {:platforms #{:server}}
      (fn [_ _]
        {:db {:articles [] :comments []}
         :fx [[:rf.server/set-header {:name "Content-Length" :value "999"}]]}))
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.server/init-cl-and-ct]
                      :root-view [:test/root]
                      :payload :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          keys-lc  (header-keys-lower response)
          _drain   (drain-stream (:body response))]
      (is (not (contains? keys-lc "content-length"))
          "Content-Length stripped")
      (is (contains? keys-lc "content-type")
          "Content-Type default survives the strip — only Content-Length
           is removed"))))

;; ---- :html-shell is rejected at construction (rf2-oq4m5) -----------------
;;
;; The non-streaming `ssr-handler` honours a one-piece `:html-shell` fn;
;; the streaming path flushes a SPLIT prefix/suffix straddling the
;; continuation chunks, so a one-piece shell callback can never run after
;; streaming starts. `stream-handler` MUST fail CLOSED at construction
;; rather than silently dropping a passed `:html-shell` (a fail-open
;; API-contract gap — a custom shell carries CSP nonces / asset URLs /
;; root markup an app would lose switching ssr-handler → stream-handler).

(deftest stream-handler-rejects-html-shell-fn-at-construction
  (testing "rf2-oq4m5: a one-piece :html-shell fn (the non-streaming
            contract) is REJECTED at handler-construction time — not
            silently ignored per-request"
    (let [custom-shell (fn [body-html payload-edn _opts]
                         (str "<custom>" body-html payload-edn "</custom>"))
          ex (is (thrown? clojure.lang.ExceptionInfo
                   (ssr-ring/stream-handler
                     {:on-create  [:rf.test.server/init]
                      :root-view  [:test/root]
                      :payload    :rf.ssr.payload/whole-app-db
                      :html-shell custom-shell})))
          data (ex-data ex)]
      (is (= :rf.error/ssr-streaming-unsupported-opt (:rf.error/id data))
          "structured error id names the unsupported-opt failure")
      (is (= :html-shell (:opt-key data))
          "ex-data names the offending opt")
      (is (= custom-shell (:got data))
          "ex-data carries the rejected value")
      (is (str/includes? (str (:reason data)) ":html-shell")
          "the reason explains :html-shell is unsupported under streaming")
      (is (str/includes? (str (:reason data)) "ssr-handler")
          "the reason points the caller at the non-streaming handler"))))

(deftest stream-handler-rejects-non-fn-html-shell-at-construction
  (testing "rf2-oq4m5: ANY non-nil :html-shell value is rejected — the
            streaming path supports no one-piece shell of any shape, so a
            string / map / vector fails closed the same way a fn does"
    (doseq [bad ["<html>…</html>" {:shape :map} [:vector]]]
      (let [ex (is (thrown? clojure.lang.ExceptionInfo
                     (ssr-ring/stream-handler
                       {:on-create  [:rf.test.server/init]
                        :root-view  [:test/root]
                        :payload    :rf.ssr.payload/whole-app-db
                        :html-shell bad}))
                 (str "stream-handler must reject :html-shell " (pr-str bad)))]
        (is (= :rf.error/ssr-streaming-unsupported-opt
               (:rf.error/id (ex-data ex)))
            (str "structured rejection for :html-shell " (pr-str bad)))))))

(deftest stream-handler-constructs-without-html-shell
  (testing "rf2-oq4m5: absent OR explicit-nil :html-shell constructs
            cleanly and streams normally — the rejection gates only a
            non-nil override, never the no-override common path"
    ;; Absent — the default streaming construction path.
    (let [handler  (ssr-ring/stream-handler
                     {:on-create [:rf.test.server/init]
                      :root-view [:test/root]
                      :payload   :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (drain-stream (:body response))]
      (is (= 200 (:status response)) "absent :html-shell streams a 200")
      (is (str/includes? body "<!DOCTYPE html>")
          "the default streaming prefix opens the document")
      (is (str/includes? body "<h1>News</h1>")
          "the default split envelope streams the rendered shell"))
    ;; Explicit nil — "no override requested" passes the gate.
    (let [handler  (ssr-ring/stream-handler
                     {:on-create  [:rf.test.server/init]
                      :root-view  [:test/root]
                      :payload    :rf.ssr.payload/whole-app-db
                      :html-shell nil})
          response (handler {:uri "/" :request-method :get})
          body     (drain-stream (:body response))]
      (is (= 200 (:status response)) "explicit :html-shell nil streams a 200")
      (is (str/includes? body "<!DOCTYPE html>")
          "explicit-nil :html-shell still opens the default document")
      (is (str/includes? body "<h1>News</h1>")
          "explicit-nil :html-shell still streams the default envelope"))))

;; ===========================================================================
;; rf2-9fw2de — streaming: the HEAD folds into the unified render hash, AND
;; the streamed root element honours :emit-hash? with a data-rf-render-hash
;; marker equal to the final payload's :rf/render-hash.
;;
;; Issue 1 (High): the streaming final-payload `:rf/render-hash` was computed
;; over the body `hiccup` alone, excluding the head — so a head-only
;; divergence left the hash unchanged (Spec 011 §624-626/§648-650 lock head +
;; body onto the unified channel). Fix: the streaming hash is the canonical
;; FULL-document hash (`lifecycle/render-document-hash`), folding the head.
;;
;; Issue 2 (Medium): `stream-handler` advertised `:emit-hash?` (default true)
;; but `run-streaming-writer!` destructured it without use — the streamed
;; shell never carried a `data-rf-render-hash` root marker, making
;; `:emit-hash?` a public no-op for the HTML path (Spec 011 §362-363 requires
;; the server marker on the root element). Fix: the streaming prefix stamps
;; the full-document hash onto the `#app` root div when `:emit-hash?` is true.
;; ===========================================================================

(defn- stream-payload-hash
  "Pull the `:rf/render-hash` out of a streamed document's final
  `__rf_payload` script. Matches the `#:rf{…}` namespace-map shorthand too."
  [body]
  (let [payload-edn (second (re-find
                              #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>"
                              body))]
    (when payload-edn
      (second (re-find #":(?:rf/)?render-hash \"([0-9a-f]{8})\"" payload-edn)))))

(defn- stream-root-wire-hash
  "Pull the `data-rf-render-hash` stamped on the streamed `#app` root div."
  [body]
  (second (re-find #"data-rf-render-hash=\"([0-9a-f]{8})\"" body)))

(deftest stream-handler-head-folds-into-render-hash
  (testing "rf2-9fw2de: identical body + DIFFERENT explicit :head → DIFFERENT
            streamed final-payload :rf/render-hash. The head rides the unified
            hash channel for the streaming path too."
    (let [mk      (fn [head]
                    (ssr-ring/stream-handler
                      {:on-create [:rf.test.server/init]
                       :root-view [:test/root]
                       :head      head
                       :payload   :rf.ssr.payload/whole-app-db}))
          body-a  (drain-stream (:body ((mk "<title>Alpha</title>")
                                        {:uri "/" :request-method :get})))
          body-b  (drain-stream (:body ((mk "<title>Beta</title>")
                                        {:uri "/" :request-method :get})))
          ha      (stream-payload-hash body-a)
          hb      (stream-payload-hash body-b)]
      (is (str/includes? body-a "<h1>News</h1>") "body A identical")
      (is (str/includes? body-b "<h1>News</h1>") "body B identical")
      (is (some? ha) "streamed payload A carries :rf/render-hash")
      (is (some? hb) "streamed payload B carries :rf/render-hash")
      (is (not= ha hb)
          "rf2-9fw2de: identical streamed body but different head → DIFFERENT
           final-payload :rf/render-hash (head folded into the unified
           streaming hash channel)"))))

(deftest stream-handler-emit-hash-true-stamps-root-marker-equal-to-payload
  (testing "rf2-9fw2de (Issue 2): :emit-hash? true → the streamed #app root
            div carries data-rf-render-hash, and it EQUALS the final payload's
            :rf/render-hash (the same canonical full-document hash)."
    (let [handler  (ssr-ring/stream-handler
                     {:on-create  [:rf.test.server/init]
                      :root-view  [:test/root]
                      :emit-hash? true
                      :payload    :rf.ssr.payload/whole-app-db})
          body     (drain-stream (:body (handler {:uri "/" :request-method :get})))
          wire     (stream-root-wire-hash body)
          payload  (stream-payload-hash body)]
      (is (some? wire)
          "rf2-9fw2de: :emit-hash? true stamps data-rf-render-hash on the
           streamed root element (was a no-op before — Issue 2)")
      (is (some? payload) "final payload carries :rf/render-hash")
      (is (= wire payload)
          "streamed root data-rf-render-hash == final payload :rf/render-hash
           (both the canonical full-document hash)")
      ;; The marker lands on the #app root div, not buried in a child.
      (is (re-find #"<div id=\"app\" data-rf-render-hash=\"[0-9a-f]{8}\">" body)
          "the marker is stamped on the opening #app root div"))))

(deftest stream-handler-emit-hash-false-omits-root-marker
  (testing "rf2-9fw2de (Issue 2): :emit-hash? false → NO data-rf-render-hash on
            the streamed shell. Toggling the opt now has an observable effect
            on the wire (it was a no-op for the HTML path before)."
    (let [handler  (ssr-ring/stream-handler
                     {:on-create  [:rf.test.server/init]
                      :root-view  [:test/root]
                      :emit-hash? false
                      :payload    :rf.ssr.payload/whole-app-db})
          body     (drain-stream (:body (handler {:uri "/" :request-method :get})))]
      (is (str/includes? body "<div id=\"app\">")
          "the #app root div is present without a hash marker")
      (is (not (str/includes? body "data-rf-render-hash"))
          "rf2-9fw2de: :emit-hash? false → no data-rf-render-hash anywhere in
           the streamed shell")
      ;; The payload still carries the hash (the payload slot is hash-driven
      ;; regardless of :emit-hash?, mirroring the non-streaming handler).
      (is (some? (stream-payload-hash body))
          "the final payload still carries :rf/render-hash when :emit-hash?
           is false (only the wire MARKER is gated by the opt)"))))

;; ===========================================================================
;; rf2-1kqvbx — the streaming final-payload :rf/render-hash MUST describe the
;; SAME (post-drain) render state as the :rf/app-db it ships.
;;
;; Defect: `doc-hash` is computed in `render-streaming-shell!` on the request
;; thread BEFORE any continuation drains (over the PRE-drain root hiccup),
;; but `build-final-payload` reads the LIVE frame app-db AFTER every
;; continuation has drained. When a continuation mutates app-db and the ROOT
;; tree reads that mutated key, the final payload carries POST-drain state
;; (`:rf/app-db`) with a PRE-drain `:rf/render-hash`. A streaming hydrate
;; re-renders the root tree against the payload's post-drain app-db, hashes
;; it, and sees a mismatch against the pre-drain `:rf/render-hash` even though
;; the server shipped its own canonical final state — firing a spurious
;; `:rf.ssr/hydration-mismatch` (Spec 011 §Hydration equivalence rule).
;;
;; The fix recomputes the final-payload `:rf/render-hash` from the post-drain
;; render tree (re-resolving the root view after all continuations drain), so
;; the hash and the `:rf/app-db` describe the same moment in the request.
;; ===========================================================================

(defn- stream-payload-edn
  "Pull the EDN body of the streamed `__rf_payload` script and read it."
  [body]
  (let [payload-edn (second (re-find
                              #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>"
                              body))]
    (when payload-edn
      (clojure.edn/read-string payload-edn))))

(deftest stream-handler-final-hash-reflects-post-drain-state
  (testing "rf2-1kqvbx: a continuation that MUTATES app-db a ROOT-read key
            depends on → the final payload's :rf/render-hash describes the
            POST-drain render tree (the one the client hydrates against),
            NOT the pre-drain shell render. The hash and the shipped
            :rf/app-db describe the same moment."
    ;; A db-mutating event the deferred subtree dispatches during its render.
    (rf/reg-event :rf.test/bump-counter
      {:platforms #{:server}}
      (fn [{:keys [db]} _] {:db (update db :counter (fnil inc 0))}))
    ;; on-create seeds :counter 0 alongside the fixture's articles/comments.
    (rf/reg-event :rf.test.server/init-counter
      {:platforms #{:server}}
      (fn [_ _] {:db {:counter 0}}))
    ;; Client hydration replaces app-db with the payload slice wholesale.
    (rf/reg-event :rf.test/hydrate-db
      {:platforms #{:server}}
      (fn [_ [_ db]] {:db db}))
    (rf/reg-sub :counter (fn [db _] (:counter db)))
    ;; A deferred subtree that MUTATES :counter during its continuation render.
    (rf/reg-view ^{:rf/id :test/counter-mutator} counter-mutator []
      (rf/dispatch-sync [:rf.test/bump-counter])
      [:div.mutated "bumped"])
    ;; The ROOT view READS :counter INLINE — `render-tree-hash` is a pure
    ;; structural hash that does NOT expand view-refs, so the subscribed
    ;; value must land directly in the root hiccup for the hash to depend on
    ;; it (pre-drain 0 vs post-drain 1 → different hashes). The deferred
    ;; subtree stays a view-ref child of the suspense boundary (the streaming
    ;; walker drains it; the hash leaves the boundary marker unexpanded).
    (rf/reg-view ^{:rf/id :test/counter-root} counter-root []
      [:main
       [:span.counter (str "counter=" @(subscribe [:counter]))]
       [:rf/suspense-boundary
        {:id :test/bump :fallback [:p "loading"]}
        [:test/counter-mutator]]])
    (let [head-html "<title>Counter</title>"
          handler  (ssr-ring/stream-handler
                     ;; EXPANDED :root-view form (symmetric with the client's
                     ;; :render-tree-fn) so the streamed hash is over the
                     ;; rendered counter-bearing tree, not an unexpanded
                     ;; view-ref vector. Explicit `:head` so the document hash
                     ;; folds in a KNOWN head fragment we can replay on the
                     ;; client side below (the default route head would be
                     ;; unknown to the client model).
                     {:on-create [:rf.test.server/init-counter]
                      :root-view (fn [] ((rf/view :test/counter-root)))
                      :head      head-html
                      :payload   :rf.ssr.payload/whole-app-db})
          response (handler {:uri "/" :request-method :get})
          body     (drain-stream (:body response))
          payload  (stream-payload-edn body)
          db-slice (:rf/app-db payload)
          server-hash (:rf/render-hash payload)]
      (is (= 200 (:status response)) "stream still 200")
      (is (str/includes? body "bumped")
          "the mutating boundary's resolved body streamed")
      ;; The shipped app-db is the POST-drain state (counter bumped to 1).
      (is (= 1 (:counter db-slice))
          "the final payload's :rf/app-db carries the post-drain counter=1")
      ;; Model the streaming client: it hydrates against the payload's
      ;; post-drain :rf/app-db and re-renders the root tree, then hashes it.
      ;; That hash MUST equal the payload's :rf/render-hash, or
      ;; :rf.ssr/hydration-mismatch fires on a page the server just shipped.
      (let [cfid (keyword "rf.frame" (str (gensym "client")))]
        (rf/reg-frame cfid {:platform :server})
        (try
          (rf/with-frame cfid
            ;; Hydrate the client frame with the payload's post-drain app-db
            ;; (the :replace-app-db hydration semantics, modelled via a
            ;; whole-db replace event).
            (rf/dispatch-sync [:rf.test/hydrate-db db-slice])
            (let [client-hiccup ((rf/view :test/counter-root))
                  client-hash   (lifecycle/render-document-hash
                                  client-hiccup
                                  {:head-html head-html
                                   :html-attrs nil :body-attrs nil})]
              ;; THE PIN — the load-bearing assertion. Pre-fix the server hash
              ;; describes counter=0 (pre-drain) while the client renders
              ;; counter=1 (post-drain), so these DIFFER → spurious mismatch.
              (is (= server-hash client-hash)
                  "rf2-1kqvbx: the final-payload :rf/render-hash describes the
                   SAME post-drain render state the client hydrates against;
                   the server hash matches the client's re-render hash over the
                   shipped :rf/app-db (no spurious :rf.ssr/hydration-mismatch)")))
          (finally
            (rf/destroy-frame! cfid)))))))
