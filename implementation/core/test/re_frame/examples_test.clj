(ns re-frame.examples-test
  "Integration tests against the example apps under ../examples/, driving the
  event → state → render pipeline the way a user wires it, which catches API
  ergonomics regressions unit tests miss. The example sources (`ssr.core`,
  `ssr-streaming.core`, `resources-ssr.core`, `state-machine-walkthrough.core`)
  live under
  ../examples/capabilities/{ssr/ssr,ssr/ssr_streaming,ssr/resources_ssr,machines/state_machine_walkthrough}/
  and stay test-free: their tests live here, and each test re-`require`s only
  the production example source, so its ns-load registrations fire against the
  reset registrar."
  (:require [clojure.set]
            [clojure.string]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            ;; the resources artefact (Spec 016), a test-only dep on the core
            ;; test classpath: the resource kind, the `:rf.resource/*`
            ;; events/subs, and the SSR projection / hydration hooks
            [re-frame.resources]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.resources.ssr :as rf.resources.ssr]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.error-listener :as rf.ssr.error-listener]
            [re-frame.ssr.install :as rf.ssr.install]
            [re-frame.ssr.request :as rf.ssr.request]
            [re-frame.ssr.response :as rf.ssr.response]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  ;; `init!` is idempotent only for the adapter already seated, and the SSR
  ;; tests seat `re-frame.ssr/adapter`, so destroy first: every test starts on
  ;; the substrate it asks for, whatever its predecessor left
  (rf/destroy-adapter!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; `clear-all!` also drops what framework namespaces register at load time,
  ;; and requiring an already-loaded namespace does not re-run it, so reload
  ;; each one the examples reach:
  ;;   re-frame.cofx               `:rf/time-ms`, which `:rf.resource/ensure` declares
  ;;   re-frame.http.managed       `:rf.http/managed`, which the examples override
  ;;   re-frame.http.test-support  the canned-success / canned-failure stubs
  ;;   re-frame.machines           `:rf/machine`, which the walkthrough's subs chain off
  ;;   re-frame.ssr                `:rf/hydrate`, the `:rf.ssr/*` / `:rf.server/*` fxs
  ;;                               and cofx, and the late-bind hooks
  ;;                               (`:ssr/on-frame-destroyed` releases the request slot)
  ;;   re-frame.resources          the resource kind, events, subs and SSR hooks
  (require 're-frame.cofx :reload)
  (require 're-frame.http.managed :reload)
  (require 're-frame.http.test-support :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.resources :reload)
  ;; The SSR side-channel tables (request slots, response accumulators,
  ;; pending error traces, the hydration-payload install ledger) are
  ;; `defonce`s keyed by frame id outside app-db. This fixture wipes
  ;; `rf.frame/frames` without `destroy-frame!`, so nothing releases them, and
  ;; a leftover install claim would make the next test's hydrate read as a
  ;; conflicting sibling root (`:rf.error/frame-payload-conflict`).
  (reset! rf.ssr.request/request-slots {})
  (reset! rf.ssr.response/response-slots {})
  (reset! rf.ssr.error-listener/pending-error-traces {})
  (rf.ssr.install/reset-installed-payloads!)
  ;; each test re-evaluates the examples' handlers against the fresh registrar
  (remove-ns 'ssr.core)
  (remove-ns 'ssr-streaming.core)
  (remove-ns 'resources-ssr.core)
  (remove-ns 'state-machine-walkthrough.core)
  ;; EP-0002: `init!` synthesises no `:rf/default`; register it and pin it as
  ;; the body's ambient scope (explicit `{:frame …}` opts win)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- shared helpers ----------------------------------------------------------

(defn- init-ssr!
  "Seat `re-frame.ssr/adapter`, the headless adapter the server flow wants, in
  place of the fixture's plain-atom one. `init!` over a different seated
  adapter raises `:rf.error/adapter-already-installed`, so destroy first."
  []
  (rf/destroy-adapter!)
  (rf/init! rf.ssr/adapter))

(defn- reg-canned-fx!
  "Register `fx-id` as a `:rf.http/managed` stand-in delegating to the
  framework's canned `stub-id` (Spec 014 §Testing) with `extra` merged into the
  request args — the reply shape a live request produces, with no network."
  [fx-id stub-id extra]
  (rf/reg-fx fx-id
    {:platforms #{:server :client}}
    (fn [frame-ctx args-map]
      ((rf.registrar/handler :fx stub-id) frame-ctx (merge args-map extra)))))

(def ^:private two-articles
  [{:id "a" :title "Article A" :body "Body A"}
   {:id "b" :title "Article B" :body "Body B"}])

(defn- install-canned-articles-stub! []
  (reg-canned-fx! :ssr.http/canned-articles :rf.http/managed-canned-success {:value two-articles}))

(defn- handle!
  "Call the example's `handle-request` with `:rf.http/managed` routed to `fx-id`.
  The per-request frame's `:initial-events` drain synchronously inside
  `make-frame`, inside this override scope."
  [handler-sym fx-id uri]
  (rf/with-fx-overrides {:rf.http/managed fx-id}
    ((resolve handler-sym) {:uri uri})))

(defn- frame-ids [] (set (keys @rf.frame/frames)))

(defn- extract-payload-edn
  "Pull the `__rf_payload` EDN string out of an SSR example's HTML body and
  read it. `clojure.edn/read-string` decodes the `\\u003c` reader escapes
  `escape-edn-script-body` emits inside string literals, so the parsed value
  is the exact payload the server built."
  [html-body]
  (let [m (re-find #"(?s)id='__rf_payload' type='application/edn'>(.*?)</script>"
                   html-body)]
    (some-> (second m) edn/read-string)))

(defn- capture-traces!
  "Run f under a trace listener; return the captured event vector."
  [f]
  (let [traces (atom [])
        cb-id  (gensym "::examples-ssr-capture-")]
    (rf/register-listener! :trace cb-id (fn [ev] (swap! traces conj ev)))
    (try (f) (finally (rf/unregister-listener! :trace cb-id)))
    @traces))

(defn- includes-all [s needles]
  (mapv #(clojure.string/includes? s %) needles))

;; ---- ssr: the server flow ------------------------------------------------------
;;
;; per-request frame → :rf/server-init → managed HTTP via the canned stub →
;; render to string → render hash. JVM-only: the server render runs under
;; Clojure.

(deftest ssr-example-runs-end-to-end
  (testing "examples/capabilities/ssr/ssr — the server flow renders the loaded articles"
    (require 'ssr.core :reload)
    (init-ssr!)
    (install-canned-articles-stub!)
    (let [fid         (keyword "rf.frame" (str (gensym "")))
          _           (rf.ssr/set-request! fid {:uri "/articles"})
          f           (rf/make-frame {:id fid :doc "ssr-example test frame"
                                      :platform :server
                                      :initial-events [[:rf/server-init]]
                                      :fx-overrides {:rf.http/managed :ssr.http/canned-articles}})
          final-db    (rf/app-db-value f)
          ;; the root view's body subscribes, so it is called under `with-frame f`
          hiccup      (rf/with-frame f ((rf/view :app/root)))
          ;; one walk, two channels — the shape the example itself uses
          render-hash (rf.ssr/render-tree-hash hiccup)
          html        (rf/with-frame f (rf.ssr/render-to-string hiccup {:render-hash render-hash}))]
      (is (= 2 (count (:articles final-db))))
      (is (= [true true true true]
             (includes-all html ["Article A" "Article B" "<h1>" "data-rf-render-hash"]))
          "the articles render to HTML, with the render-hash marker, without React/JSDOM")
      ;; lowercase-hex FNV-1a (Spec 011), which the client recomputes
      (is (re-matches #"[0-9a-f]{8}" render-hash))
      ;; the hash covers the page the root renders: the ambient :rf/default
      ;; frame holds no articles, so its render of the same root differs
      (is (not= render-hash (rf.ssr/render-tree-hash ((rf/view :app/root))))))))

;; Spec 011 §Per-request frame teardown contract: the example's
;; `handle-request` destroys its per-request frame in a `finally`, so a
;; long-running server leaks neither the frame nor its request slot, on the
;; success path and the throw path alike.

(deftest ssr-example-handle-request-tears-down-per-request-frame
  (testing "examples/capabilities/ssr/ssr — after handle-request returns, no
            per-request frame and no SSR request slot survive"
    (require 'ssr.core :reload)
    (init-ssr!)
    (install-canned-articles-stub!)
    (let [before (frame-ids)
          resp   (handle! 'ssr.core/handle-request :ssr.http/canned-articles "/articles")]
      (is (= [200 true] [(:status resp) (clojure.string/includes? (:body resp) "Article A")]))
      (is (= [#{} {}] [(clojure.set/difference (frame-ids) before) @rf.ssr.request/request-slots])))))

(deftest ssr-example-handle-request-tears-down-on-throw
  (testing "examples/capabilities/ssr/ssr — a render that throws after the frame
            and request slot were allocated still releases both, and the throw
            propagates"
    (require 'ssr.core :reload)
    (init-ssr!)
    (install-canned-articles-stub!)
    (let [before (frame-ids)]
      (with-redefs [rf.ssr/render-to-string (fn [& _] (throw (ex-info "boom — render failure" {})))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (handle! 'ssr.core/handle-request :ssr.http/canned-articles "/articles")))
        (is (= [#{} {}] [(clojure.set/difference (frame-ids) before) @rf.ssr.request/request-slots]))))))

;; The example holds its app schema as a value (`ArticlesSchema`) and registers
;; it against EACH frame family — the per-request server frame in
;; `handle-request`, before `:initial-events` fire, and the client frame in
;; `run`. Bound to the client frame alone, the server-side `:articles` commit
;; would validate against nothing. Requiring `re-frame.schemas` wires the
;; default Malli validator, so validation is live here.

(deftest ssr-example-per-request-frame-carries-and-validates-articles-schema
  (testing "examples/capabilities/ssr/ssr — the per-request SERVER frame carries the
            :articles schema and validation runs on that frame, not :rf/default"
    (require 'ssr.core :reload)
    (init-ssr!)
    (install-canned-articles-stub!)
    (let [schema @(resolve 'ssr.core/ArticlesSchema)
          fid    (keyword "rf.frame" (str (gensym "f")))
          _      (rf.ssr/set-request! fid {:uri "/articles"})
          _      (rf/reg-app-schema [:articles] {:frame fid} schema)
          f      (rf/with-fx-overrides {:rf.http/managed :ssr.http/canned-articles}
                   (rf/make-frame {:id fid :doc "ssr-example per-request validation frame"
                                   :platform :server
                                   :initial-events [[:rf/server-init]]}))
          db     (rf/app-db-value f)
          bad-db (assoc db :articles "not-a-vector-of-articles")]
      (is (= [schema nil 2]
             [(:schema (rf.schemas/app-schema-meta {:frame fid :path [:articles]}))
              (:schema (rf.schemas/app-schema-meta {:frame :rf/default :path [:articles]}))
              (count (:articles db))])
          "bound to the per-request frame and not :rf/default; the server-init commit loaded two articles")
      ;; the [:maybe [:vector …]] schema accepts the loaded articles and refuses
      ;; a non-vector on the per-request frame, while :rf/default, carrying no
      ;; SSR schema, soft-passes the same bad value
      (is (= [true false true]
             [(rf.schemas/validate-app-schema! db :rf/server-init fid)
              (rf.schemas/validate-app-schema! bad-db :rf/server-init fid)
              (rf.schemas/validate-app-schema! bad-db :rf/server-init :rf/default)]))
      (rf/destroy-frame! f))))

;; ---- ssr: the client hydration path ----------------------------------------------
;;
;; The example boots its client through `rf.ssr/hydrate!` and the
;; framework-registered `:rf/hydrate`. JVM-driven with an explicit :payload on a
;; :client frame (no DOM to read).

(deftest ssr-example-client-hydration-stashes-server-hash-and-seeds-db
  (testing "examples/capabilities/ssr/ssr — hydrate! returns the applied payload,
            replaces app-db with the :rf/app-db slice, and stashes the
            :rf/render-hash for the verify step"
    (require 'ssr.core :reload)
    (init-ssr!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-example client frame" :platform :client})
          payload      {:rf/version     1
                        :rf/render-hash "abc12345"
                        :rf/app-db      {:articles [{:id "a" :title "A" :body "ba"}]}
                        :rf/runtime-db  {}}]
      (is (= [payload [{:id "a" :title "A" :body "ba"}] "abc12345"]
             [(rf.ssr/hydrate! {:frame client-frame :payload payload})
              (:articles (rf/app-db-value client-frame))
              (get-in (:rf.db/runtime (rf/frame-state-value client-frame))
                      [:rf.runtime/ssr :hydration :server-hash])])))))

(deftest ssr-example-client-hydration-matching-hash-is-silent
  (testing "examples/capabilities/ssr/ssr — a client render-tree whose hash MATCHES the
            payload's :rf/render-hash emits NO :rf.ssr/hydration-mismatch"
    (require 'ssr.core :reload)
    (init-ssr!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-example verify-match frame"
                                                          :platform :client
                                                          :ssr {:detect-mismatch? true}})
          client-tree  [:div.page [:h1 "Recent articles"]]
          payload      {:rf/version 1 :rf/render-hash (rf.ssr/render-tree-hash client-tree)
                        :rf/app-db {:articles []} :rf/runtime-db {}}
          traces       (capture-traces!
                         #(rf.ssr/hydrate! {:frame client-frame :payload payload
                                            :render-tree-fn (fn [] client-tree)}))]
      (is (not-any? #(= :rf.ssr/hydration-mismatch (:operation %)) traces)
          (pr-str (mapv :operation traces))))))

(deftest ssr-example-client-hydration-divergent-hash-fires-mismatch
  (testing "examples/capabilities/ssr/ssr — a client render-tree whose hash DIVERGES
            from the payload's emits :rf.ssr/hydration-mismatch (the verify step
            the example's `run` wires via :render-tree-fn)"
    (require 'ssr.core :reload)
    (init-ssr!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-example verify-divergent frame"
                                                          :platform :client
                                                          :ssr {:detect-mismatch? true}})
          payload      {:rf/version 1 :rf/render-hash "server00"
                        :rf/app-db {:articles []} :rf/runtime-db {}}
          traces       (capture-traces!
                         #(rf.ssr/hydrate! {:frame client-frame :payload payload
                                            :render-tree-fn (fn [] [:div.page [:h1 "Recent articles"]])}))]
      (is (some #(= :rf.ssr/hydration-mismatch (:operation %)) traces)
          (pr-str (mapv :operation traces))))))

(deftest ssr-example-client-hydration-malformed-payload-does-not-replace-db
  (testing "examples/capabilities/ssr/ssr — a present-but-non-map :rf/app-db slice
            is rejected fail-closed and app-db is left unchanged (Spec 011 §The
            :rf/hydrate event)"
    (require 'ssr.core :reload)
    (init-ssr!)
    (let [client-frame (rf.frame/make-anon-frame-record! {:doc "ssr-example fail-closed frame" :platform :client})
          kept         [{:id "keep" :title "Keep" :body "b"}]]
      (rf/dispatch-sync [:articles/loaded {:value kept}] {:frame client-frame})
      (is (= kept (:articles (rf/app-db-value client-frame))) "precondition: app-db seeded")
      (rf.ssr/hydrate! {:frame client-frame :payload {:rf/version 1 :rf/app-db "not-a-map"}})
      (is (= kept (:articles (rf/app-db-value client-frame)))))))

;; ---- ssr_streaming: shell → per-card resolved chunks → final payload ----------

(deftest ssr-streaming-example-runs-end-to-end
  (testing "examples/capabilities/ssr/ssr_streaming — the server stream produces shell + chunks + payload"
    (require 'ssr-streaming.core :reload)
    (init-ssr!)
    (let [result      ((resolve 'ssr-streaming.core/handle-request) {:uri "/dashboard"})
          chunks      (:resolved-chunks result)
          [failed ok] ((juxt filter remove) :failed? chunks)
          payload     (:final-payload result)]
      ;; the shell carries the static header and the template fallbacks; of
      ;; four boundaries, the flaky one ships the failed template
      (is (= [true true 4 1 3]
             [(clojure.string/includes? (:shell result) "<h1>Dashboard</h1>")
              (clojure.string/includes? (:shell result) "data-rf2-suspense-fallback=\"1\"")
              (count chunks) (count failed) (count ok)]))
      (is (clojure.string/includes? (:template (first failed)) "data-rf2-suspense-failed=\"1\""))
      ;; a deferred body is a Var-headed hiccup vector (`[card-view :revenue]`):
      ;; the emitter must resolve it to the rendered card, not emit the head
      (doseq [c ok]
        (is (= [true true] (includes-all (:template c) ["data-rf2-suspense-resolved=\"1\"" "class=\"card\""]))
            (:template c)))
      ;; the drain's failed boundary reaches the wire in the final payload's
      ;; runtime slice, so the client re-renders the fallback it declared
      ;; rather than inferring failure from absent state; the flaky card threw
      ;; before its data fetched, so three cards carry state
      (is (= [#{:card.flaky} #{:card.flaky} 1 true 3]
             [(:failed-boundaries result)
              (get-in (:rf/runtime-db payload) [:rf.runtime/ssr :streaming :failed-boundaries])
              (:rf/version payload)
              (some? (:rf/render-hash payload))
              (count (:cards (:rf/app-db payload)))])))))

;; ---- the dynamic payload, server → client ----------------------------------------
;;
;; These feed the ACTUAL dynamic payloads (the plain and resources
;; `handle-request` HTML payloads, the streaming `final-payload`) into
;; `rf.ssr/hydrate!` against the example's own `:rf/default` client frame, so
;; a payload stamped with the per-request server gensym would fail loud with
;; `:rf.error/hydration-frame-id-mismatch`. The payloads omit `:rf/frame-id`
;; (an absent frame-id is no conflict — Spec 011 §The hydration payload), and
;; the payload `<script>` routes through the EDN-aware
;; `escape-edn-script-body`, so a server-provided `</script>` cannot close it.

(deftest ssr-example-dynamic-payload-hydrates-without-frame-id-mismatch
  (testing "examples/capabilities/ssr/ssr — the dynamic handle-request payload omits
            :rf/frame-id and hydrates the example's :rf/default client frame with
            the server's articles"
    (require 'ssr.core :reload)
    (init-ssr!)
    (install-canned-articles-stub!)
    (let [resp         (handle! 'ssr.core/handle-request :ssr.http/canned-articles "/articles")
          payload      (extract-payload-edn (:body resp))
          client-frame @(resolve 'ssr.core/app-frame)]
      (is (= [200 true false] [(:status resp) (some? payload) (contains? payload :rf/frame-id)]))
      (rf/make-frame {:id client-frame :doc "ssr-example client frame" :platform :client})
      (is (= [payload two-articles]
             [(rf.ssr/hydrate! {:frame client-frame :payload payload})
              (:articles (rf/app-db-value client-frame))])))))

(deftest ssr-example-payload-script-escapes-script-breakout
  (testing "examples/capabilities/ssr/ssr — a server string carrying `</script>` is
            escaped by the EDN-aware encoder, so it cannot close the
            `__rf_payload` envelope, and still round-trips through the EDN reader"
    (require 'ssr.core :reload)
    (init-ssr!)
    (reg-canned-fx! :ssr.http/canned-evil :rf.http/managed-canned-success
                    {:value [{:id "x" :title "</script><script>alert('xss')</script>" :body "b"}]})
    (let [resp (handle! 'ssr.core/handle-request :ssr.http/canned-evil "/x")
          body (:body resp)]
      ;; the `<` inside the EDN string literal becomes the reader escape
      (is (= [200 false true "</script><script>alert('xss')</script>"]
             [(:status resp)
              (clojure.string/includes? body "</script><script>alert('xss')</script>")
              (clojure.string/includes? body "\\u003c")
              (-> (extract-payload-edn body) :rf/app-db :articles first :title)])))))

;; ---- ssr: the page load SETTLES before the render ----------------------------------
;;
;; `make-frame` drains only the SYNCHRONOUS work its `:initial-events` start.
;; The article fetch goes through `HttpClient/sendAsync` on the JVM, so a
;; `handle-request` reading app-db the instant `make-frame` returned would
;; render "No articles." over a 200 and destroy the only frame the reply could
;; land in. The load has an explicit `:articles/load-state` outcome
;; (`:pending` → `:loaded` / `:failed`) that `handle-request` blocks on,
;; bounded: a delayed reply is waited for, and a non-`:loaded` outcome answers
;; 503 rather than dressing a pending request up as a finished empty page.

(deftest ssr-example-handle-request-waits-for-a-delayed-article-reply
  (testing "examples/capabilities/ssr/ssr — a reply landing after `make-frame`
            returns (deferred through `:dispatch-later`, the shape a real
            sendAsync reply has) still reaches the HTML and the payload"
    (require 'ssr.core :reload)
    (init-ssr!)
    (reg-canned-fx! :ssr.http/delayed-canned-articles :rf.http/managed-canned-success
                    {:after-ms 150 :value [{:id "a" :title "Article A" :body "Body A"}]})
    (let [before (frame-ids)
          resp   (handle! 'ssr.core/handle-request :ssr.http/delayed-canned-articles "/articles")]
      ;; the empty-state render would mean the handler returned before the reply
      (is (= [200 true false [{:id "a" :title "Article A" :body "Body A"}] #{}]
             [(:status resp)
              (clojure.string/includes? (:body resp) "Article A")
              (clojure.string/includes? (:body resp) "No articles.")
              (:articles (:rf/app-db (extract-payload-edn (:body resp))))
              (clojure.set/difference (frame-ids) before)])))))

(deftest ssr-example-handle-request-terminates-deliberately-on-failure-and-deadline
  (testing "examples/capabilities/ssr/ssr — a failed fetch and an exhausted deadline
            each answer 503 promptly and leave no frame behind"
    (require 'ssr.core :reload)
    (init-ssr!)
    (let [timed (fn [fx-id]
                  (let [before  (frame-ids)
                        started (System/currentTimeMillis)
                        resp    (handle! 'ssr.core/handle-request fx-id "/articles")]
                    {:resp    resp
                     :elapsed (- (System/currentTimeMillis) started)
                     :leaked  (clojure.set/difference (frame-ids) before)}))]
      (testing "a failed reply settles on :failed at once — the :on-failure target
                is what makes it terminal rather than costing the full deadline"
        (reg-canned-fx! :ssr.http/canned-articles-failure :rf.http/managed-canned-failure
                        {:kind :rf.http/transport})
        (let [{:keys [resp elapsed leaked]} (timed :ssr.http/canned-articles-failure)]
          (is (= [503 false true #{}]
                 [(:status resp) (clojure.string/includes? (:body resp) "No articles.")
                  (< elapsed 2000) leaked])
              (str "took " elapsed "ms"))))
      (testing "a reply that never arrives stops at the deadline, shortened from
                the shipped five seconds, and says which outcome it was"
        (rf/reg-fx :ssr.http/never-replies {:platforms #{:server :client}} (fn [_ _] nil))
        (let [{:keys [resp elapsed leaked]}
              (with-redefs-fn {(resolve 'ssr.core/page-load-deadline-ms) 50}
                #(timed :ssr.http/never-replies))]
          (is (= [503 true true #{}]
                 [(:status resp) (clojure.string/includes? (:body resp) "timed-out")
                  (< elapsed 2000) leaked])
              (str "took " elapsed "ms")))))))

(deftest ssr-streaming-example-final-payload-hydrates-without-frame-id-mismatch
  (testing "examples/capabilities/ssr/ssr_streaming — the dynamic :final-payload
            omits :rf/frame-id and hydrates the :rf/default client frame with the
            three streamed cards"
    (require 'ssr-streaming.core :reload)
    (init-ssr!)
    (let [payload (:final-payload ((resolve 'ssr-streaming.core/handle-request) {:uri "/dashboard"}))]
      (is (not (contains? payload :rf/frame-id)))
      ;; the example's `app-frame` is `:cljs`-only (the streaming client boots
      ;; in the browser), so use its value, `:rf/default`
      (rf/make-frame {:id :rf/default :doc "ssr-streaming-example client frame" :platform :client})
      (is (= [payload 3]
             [(rf.ssr/hydrate! {:frame :rf/default :payload payload})
              (count (:cards (rf/app-db-value :rf/default)))])))))

;; The deliberate `:card.flaky` boundary is not the risk — the drain turns it
;; into a `:failed?` chunk and returns normally. A failure OUTSIDE that
;; recovery (a throwing shell walk or final-payload build) would otherwise
;; strand one frame per failed request in a long-lived host, so the handler
;; releases the frame in a `try`/`finally`.

(deftest ssr-streaming-example-releases-its-frame-on-an-outer-render-failure
  (testing "examples/capabilities/ssr/ssr_streaming — the happy path (its
            :card.flaky fallback included) and an outer shell-render failure both
            leave the frame registry at its baseline, and the exception propagates"
    (require 'ssr-streaming.core :reload)
    (init-ssr!)
    (let [handle-request (resolve 'ssr-streaming.core/handle-request)
          before         (frame-ids)]
      (is (= [#{:card.flaky} #{}]
             [(:failed-boundaries (handle-request {:uri "/dashboard"}))
              (clojure.set/difference (frame-ids) before)]))
      (with-redefs [rf.ssr/streaming-render-shell
                    (fn [& _] (throw (ex-info "boom — shell render failure" {})))]
        (is (thrown? clojure.lang.ExceptionInfo (handle-request {:uri "/dashboard"})))
        (is (= #{} (clojure.set/difference (frame-ids) before)))))))

;; ---- resources_ssr ---------------------------------------------------------------

(def ^:private resources-ssr-articles
  [{:slug "welcome"   :title "Welcome to re-frame2"}
   {:slug "resources" :title "Server-state as resources"}])

(def ^:private resources-ssr-key
  "The example's one scoped resource key — `:articles/list` at global scope,
  no params (Spec 016 §Resource identity)."
  [:rf.scope/global :articles/list {}])

(defn- resources-ssr-entry
  "The example's ONE cache entry, read out of `frame`'s runtime-db."
  [frame]
  (get-in (:rf.db/runtime (rf/frame-state-value frame))
          (rf.resources.state/entry-path resources-ssr-key)))

(defn- resources-ssr-owners
  "The entry's active-owner set (empty when the entry is absent)."
  [frame]
  (set (:active-owners (resources-ssr-entry frame))))

(defn- resources-ssr-html
  "Render the example's own root view through `frame` — the first client paint."
  [frame]
  (rf/with-frame frame (rf.ssr/render-to-string ((rf/view :app/root)) {})))

(defn- resources-ssr-static-payload
  "The `__rf_payload` EDN baked into the SHIPPED `index.html` (the
  `../../examples/capabilities/ssr` source root is a `:test` classpath entry,
  so it resolves next to `core.cljc`) — the forever-fresh entry a reader
  opening the file offline hydrates from."
  []
  (some-> (io/resource "resources_ssr/index.html")
          slurp
          (->> (re-find #"(?s)id=\"__rf_payload\" type=\"application/edn\">(.*?)</script>"))
          second
          edn/read-string))

(defn- resource-status [frame]
  (:status (rf/resource-state {:resource :articles/list :scope :rf.scope/global :params {} :frame frame})))

(deftest resources-ssr-example-dynamic-payload-hydrates-without-frame-id-mismatch
  (testing "examples/capabilities/ssr/resources_ssr — under the valid
            :rf.ssr.payload/whole-app-db policy (an empty [] policy throws
            :rf.error/ssr-missing-payload-policy) the dynamic payload omits
            :rf/frame-id and installs the SSR-preloaded entry into the client
            :rf.runtime/resources slice (Spec 016 §SSR client hydration)"
    (require 'resources-ssr.core :reload)
    (init-ssr!)
    (reg-canned-fx! :resources-ssr.http/canned :rf.http/managed-canned-success {:value resources-ssr-articles})
    (let [resp     (handle! 'resources-ssr.core/handle-request :resources-ssr.http/canned "/articles")
          body     (:body resp)
          payload  (extract-payload-edn body)
          app-open (clojure.string/index-of body "<div id='app'>")
          entries  (get-in payload [:rf/runtime-db :rf.runtime/resources :entries])]
      (is (= [200 true false] [(:status resp) (some? payload) (contains? payload :rf/frame-id)]))
      ;; the hash covers the page the root renders: the ambient :rf/default
      ;; frame holds no preloaded entry, so its render hashes differently
      (is (not= (:rf/render-hash payload) (rf.ssr/render-tree-hash ((rf/view :app/root)))))
      ;; handle-request owns the ONE document shell and the app renders as a
      ;; FRAGMENT into #app: a nested doctype would be malformed HTML surviving
      ;; only through browser parser recovery
      (is (= [1 true true false]
             [(count (re-seq #"(?i)<!doctype" body))
              (clojure.string/starts-with? (clojure.string/lower-case body) "<!doctype html>")
              (some? app-open)
              (clojure.string/includes? (clojure.string/lower-case (subs body app-open)) "<!doctype")]))
      ;; #app's first child is the page root carrying the render hash, and the
      ;; envelope's head, payload script and boot script survive
      (is (= [true true true true true]
             (includes-all body ["<div id='app'><div class=\"page\"" "data-rf-render-hash="
                                 "<title>Resources SSR demo</title>" "id='__rf_payload'"
                                 "<script src='/main.js'></script>"])))
      ;; the runtime-db projection carries the loaded entry (`:entries`, not the indexes)
      (is (= [1 :loaded] [(count entries) (-> entries vals first :status)]))
      (let [client-frame @(resolve 'resources-ssr.core/app-frame)]
        (rf/make-frame {:id client-frame :doc "resources-ssr-example client frame" :platform :client})
        (is (= payload (rf.ssr/hydrate! {:frame client-frame :payload payload})))
        ;; the reverse indexes are recomputed from :entries on install (Spec 016)
        (let [client-entries (get-in (:rf.db/runtime (rf/frame-state-value client-frame))
                                     [:rf.runtime/resources :entries])]
          (is (= [1 :loaded] [(count client-entries) (-> client-entries vals first :status)])))))))

(deftest resources-ssr-example-static-index-payload-hydrates-into-live-cache
  (testing "examples/capabilities/ssr/resources_ssr — the ACTUAL checked-in
            `index.html` payload (the offline stand-in) hydrates into the LIVE
            resource cache: the public read serves both baked articles on the
            first client render, the baked :entries are keyed on the CEDN byte
            key-id, and the fresh entry issues no client refetch. Reading the
            file itself is what makes a vector-keyed or rotting-:stale-at payload
            fail here"
    (require 'resources-ssr.core :reload)
    (init-ssr!)
    (let [payload      (resources-ssr-static-payload)
          baked        (get-in payload [:rf/runtime-db :rf.runtime/resources :entries])
          expected-kid (rf.resources.state/key-id resources-ssr-key)
          entry        (first (vals baked))]
      (is (some? payload) "resources_ssr/index.html is on the test classpath and its payload parses")
      ;; keyed on the CEDN-1 byte key-id, each entry carrying the scoped vector
      ;; as :resource/key (a legacy vector-keyed row would leave it nil,
      ;; unreachable through the byte-key lookup), with no absolute :stale-at
      (is (= [#{expected-kid} resources-ssr-key nil]
             [(set (keys baked)) (:resource/key entry) (:stale-at entry)]))
      (let [client-frame @(resolve 'resources-ssr.core/app-frame)]
        (rf/make-frame {:id client-frame :doc "resources-ssr static-payload client frame" :platform :client})
        (is (= payload (rf.ssr/hydrate! {:frame client-frame :payload payload})))
        (let [state (rf/resource-state {:resource :articles/list :scope :rf.scope/global
                                        :params {} :frame client-frame})
              html  (resources-ssr-html client-frame)
              rdb   (:rf.db/runtime (rf/frame-state-value client-frame))]
          ;; a fresh hydrated entry is absent from the refetch plan, so the
          ;; route-free client boot issues no transport request
          (is (= [:loaded resources-ssr-articles [true true] true #{expected-kid}]
                 [(:status state)
                  (:data state)
                  (includes-all html ["Welcome to re-frame2" "Server-state as resources"])
                  (empty? (rf.resources.ssr/hydrate-refetch-plan rdb))
                  (set (keys (get-in rdb [:rf.runtime/resources :entries])))])))))))

;; ---- resources_ssr: the client boot seam -------------------------------------------
;;
;; Hydration installs and classifies state: it reconciles the payload, DROPS
;; the completed request's `[:ssr …]` owner, and issues nothing. Liveness rests
;; on the `:resources-ssr.app/page-opened` acquisition `run` dispatch-syncs
;; after the hydrate and before the first render. `run` is `:cljs`-only, so
;; these drive the example's OWN events against its OWN `app-frame` in `run`'s
;; order. Spec 016 §Invalidation refetches a matched entry with ACTIVE owners
;; and leaves an ownerless one merely stale, so "the client owner is live" is
;; proved by the requests the cache does or does not make, in both directions.

(defn- reg-counting-transport!
  "Register a managed-HTTP stand-in under `fx-id` that COUNTS every request the
  resource runtime lowers and, when `reply?`, settles it with the canned
  articles. Returns the counter atom: the refetch PLAN says what hydration
  classified, only the counter says what the acquisition sent."
  [fx-id {:keys [reply?]}]
  (let [calls (atom 0)]
    (rf/reg-fx fx-id
      {:platforms #{:server :client}}
      (fn [frame-ctx args-map]
        (swap! calls inc)
        (when reply?
          ((rf.registrar/handler :fx :rf.http/managed-canned-success)
           frame-ctx (assoc args-map :value resources-ssr-articles)))))
    calls))

(defn- resources-ssr-client-frame!
  "The example's own client app-frame, `:rf.http/managed` redirected at
  `fx-id`. `:platform :client` makes this the browser seam rather than a second
  server render."
  [fx-id]
  (let [fid @(resolve 'resources-ssr.core/app-frame)]
    (rf/make-frame {:id           fid
                    :doc          "resources-ssr client boot seam"
                    :platform     :client
                    :fx-overrides {:rf.http/managed fx-id}})
    fid))

(defn- invalidate-articles! [fid cause]
  (rf/dispatch-sync [:rf.resource/invalidate-tags
                     {:scope :rf.scope/global :tags #{[:article-list]} :cause [:test cause]}]
                    {:frame fid}))

(deftest resources-ssr-example-cold-client-boot-acquires-and-first-paints-loading
  (testing "examples/capabilities/ssr/resources_ssr — with NO payload to hydrate,
            the page-opened acquisition issues EXACTLY ONE request under the
            page's app-minted owner, and the first paint is the :loading
            skeleton, not the empty <ul> an absent entry projects as :idle (which
            reads as a successful answer of zero articles)"
    (require 'resources-ssr.core :reload)
    (init-ssr!)
    (let [calls (reg-counting-transport! :resources-ssr-test/pending {:reply? false})
          fid   (resources-ssr-client-frame! :resources-ssr-test/pending)
          owner @(resolve 'resources-ssr.core/page-owner)]
      (is (= [nil 0 true]
             [(resources-ssr-entry fid) @calls (clojure.string/includes? (resources-ssr-html fid) "articles-list")])
          "before: no entry, no request, and the false empty-success paint")
      (rf/dispatch-sync [:resources-ssr.app/page-opened] {:frame fid})
      (is (= [1 true :loading]
             [@calls (contains? (resources-ssr-owners fid) owner) (resource-status fid)]))
      (let [html (resources-ssr-html fid)]
        (is (= [true false]
               [(clojure.string/includes? html "articles-skeleton")
                (clojure.string/includes? html "articles-list")]))))))

(deftest resources-ssr-example-fresh-hydrated-boot-takes-the-hold-without-refetching
  (testing "examples/capabilities/ssr/resources_ssr — hydrating the forever-fresh
            index.html payload leaves a renderable but OWNERLESS entry; the
            acquisition takes ensure's fresh-skip cache hit, attaching the page
            owner with ZERO requests (the payoff of preloading), and the server's
            markup still paints"
    (require 'resources-ssr.core :reload)
    (init-ssr!)
    (let [calls   (reg-counting-transport! :resources-ssr-test/canned {:reply? true})
          fid     (resources-ssr-client-frame! :resources-ssr-test/canned)
          owner   @(resolve 'resources-ssr.core/page-owner)
          payload (resources-ssr-static-payload)]
      (is (some? payload))
      (rf.ssr/hydrate! {:frame fid :payload payload})
      (is (= [:loaded #{} 0] [(:status (resources-ssr-entry fid)) (resources-ssr-owners fid) @calls]))
      (rf/dispatch-sync [:resources-ssr.app/page-opened] {:frame fid})
      (is (= [0 true :loaded]
             [@calls (contains? (resources-ssr-owners fid) owner) (:status (resources-ssr-entry fid))]))
      (is (= [true true]
             (includes-all (resources-ssr-html fid) ["Welcome to re-frame2" "Server-state as resources"]))))))

(deftest resources-ssr-example-stale-hydrated-boot-keeps-data-and-issues-one-request
  (testing "examples/capabilities/ssr/resources_ssr — invalidating [:article-list]
            before the acquisition stales the still-ownerless entry and issues
            nothing (the counterfactual that gives the owner assertions their
            meaning); the acquisition then issues EXACTLY ONE background request
            while the last-known-good data stays on screen"
    (require 'resources-ssr.core :reload)
    (init-ssr!)
    (let [calls   (reg-counting-transport! :resources-ssr-test/canned {:reply? true})
          fid     (resources-ssr-client-frame! :resources-ssr-test/canned)
          owner   @(resolve 'resources-ssr.core/page-owner)
          payload (resources-ssr-static-payload)]
      (rf.ssr/hydrate! {:frame fid :payload payload})
      (invalidate-articles! fid :resources-ssr/stale-fixture)
      (is (= [0 #{} true]
             [@calls (resources-ssr-owners fid)
              (clojure.string/includes? (resources-ssr-html fid) "Welcome to re-frame2")]))
      (rf/dispatch-sync [:resources-ssr.app/page-opened] {:frame fid})
      (is (= [1 true] [@calls (contains? (resources-ssr-owners fid) owner)]))
      (let [html (resources-ssr-html fid)]
        (is (= [false true]
               [(clojure.string/includes? html "articles-skeleton")
                (clojure.string/includes? html "Welcome to re-frame2")]))))))

(deftest resources-ssr-example-page-closed-releases-the-hold-the-acquisition-took
  (testing "examples/capabilities/ssr/resources_ssr — page-closed is the matching
            drop for the owner page-opened mints (the framework never
            auto-releases an app-minted owner): while held, an invalidation
            refetches exactly once; after the release the same invalidation
            issues nothing and leaves the entry GC-eligible"
    (require 'resources-ssr.core :reload)
    (init-ssr!)
    (let [calls   (reg-counting-transport! :resources-ssr-test/canned {:reply? true})
          fid     (resources-ssr-client-frame! :resources-ssr-test/canned)
          owner   @(resolve 'resources-ssr.core/page-owner)
          payload (resources-ssr-static-payload)]
      (rf.ssr/hydrate! {:frame fid :payload payload})
      (rf/dispatch-sync [:resources-ssr.app/page-opened] {:frame fid})
      (is (= [true 0] [(contains? (resources-ssr-owners fid) owner) @calls]))
      (invalidate-articles! fid :resources-ssr/liveness-probe)
      (is (= 1 @calls) "held: the owner is semantically live, not merely present in a set")
      (rf/dispatch-sync [:resources-ssr.app/page-closed] {:frame fid})
      (is (= #{} (resources-ssr-owners fid)))
      (invalidate-articles! fid :resources-ssr/liveness-probe)
      (is (= 1 @calls) "released: the same invalidation refetches nothing (still 1 in total)"))))

;; `await-resource-loaded!` must be handed the frame ID, not the frame VALUE
;; `make-frame` returns: `rf/resource-state` keys the frame registry by id, so
;; with a value every poll reads nil and the loop runs to
;; `preload-deadline-ms` on EVERY request while the response still carries the
;; article. So this test reads what a content assertion cannot see: the target
;; the poll reads, and how many times it goes round.

(deftest resources-ssr-example-preload-poll-exits-on-the-resource-it-awaits
  (testing "examples/capabilities/ssr/resources_ssr — a synchronously loaded
            resource exits the preload poll at once, on a terminal status read
            from the request's own entry"
    (require 'resources-ssr.core :reload)
    (init-ssr!)
    (reg-canned-fx! :resources-ssr.http/canned :rf.http/managed-canned-success
                    {:value [{:slug "welcome" :title "Welcome to re-frame2"}]})
    (let [original rf/resource-state
          polls    (atom [])
          ;; record what each poll was asked and got, returning the real answer
          resp     (with-redefs [rf/resource-state
                                 (fn [opts]
                                   (let [entry (original opts)]
                                     (swap! polls conj {:keyword-target? (keyword? (:frame opts))
                                                        :status          (:status entry)})
                                     entry))]
                     (handle! 'resources-ssr.core/handle-request :resources-ssr.http/canned "/articles"))]
      ;; a frame-value target would poll nil ~850 times across the five-second
      ;; budget; a synchronously loaded resource settles before the first read
      (is (= [200 true true true :loaded true]
             [(:status resp)
              (clojure.string/includes? (:body resp) "Welcome to re-frame2")
              (boolean (seq @polls))
              (every? :keyword-target? @polls)
              (:status (last @polls))
              (< (count @polls) 20)])
          (str "polls: " (count @polls))))))

(deftest resources-ssr-example-only-serves-a-settled-resource
  (require 'resources-ssr.core :reload)
  (init-ssr!)
  (reg-canned-fx! :resources-ssr.http/delayed-success :rf.http/managed-canned-success
                  {:after-ms 50 :value [{:slug "settled" :title "Settled article"}]})
  (reg-canned-fx! :resources-ssr.http/failure :rf.http/managed-canned-failure {:kind :rf.http/transport})
  (rf/reg-fx :resources-ssr.http/never-replies {:platforms #{:server :client}} (fn [_ _] nil))
  (doseq [[transport deadline status outcome]
          [[:resources-ssr.http/delayed-success 5000 200 nil]
           [:resources-ssr.http/failure 5000 503 "failed"]
           [:resources-ssr.http/never-replies 50 503 "timed-out"]]]
    (testing (str "preload through " transport)
      (let [before   (frame-ids)
            started  (System/currentTimeMillis)
            response (with-redefs-fn
                       {(resolve 'resources-ssr.core/preload-deadline-ms) deadline}
                       #(handle! 'resources-ssr.core/handle-request transport "/articles"))
            elapsed  (- (System/currentTimeMillis) started)]
        (is (= status (:status response)))
        (if (= 200 status)
          (let [entries (vals (get-in (extract-payload-edn (:body response))
                                      [:rf/runtime-db :rf.runtime/resources :entries]))]
            (is (= [true [:loaded] [[{:slug "settled" :title "Settled article"}]]]
                   [(clojure.string/includes? (:body response) "Settled article")
                    (mapv :status entries)
                    (mapv :data entries)])))
          ;; an unfinished resource is never sent as a hydration payload
          (is (= ["no-store" true false]
                 [(get-in response [:headers "Cache-Control"])
                  (clojure.string/includes? (:body response) outcome)
                  (clojure.string/includes? (:body response) "__rf_payload")])))
        (is (= [true #{}] [(< elapsed 2000) (clojure.set/difference (frame-ids) before)])
            (str "terminal replies settle promptly, the deadline is bounded, and the request frame goes; took "
                 elapsed "ms"))))))

;; ---- state-machine-walkthrough: chapter §Headless testing --------------------------
;;
;; Two flavours: pure `machine-transition` (no frame, no app-db) and drain-level
;; (a frame plus a canned stub). The `:walkthrough.login/canned-success` /
;; `-failure` stubs are registered in `state-machine-walkthrough.core`, so the
;; browser demo and these tests share one registration point.

(deftest state-machine-walkthrough-runs-headless
  (require 'state-machine-walkthrough.core :reload)
  (let [login-flow @(resolve 'state-machine-walkthrough.core/login-flow)
        step       (fn [snapshot event] (rf.machines/machine-transition login-flow snapshot event))
        submit     [:walkthrough.login/submit {:email "a@b.com" :password "secret"}]]
    (testing "pure happy path — :idle → :submitting (issuing one :rf.http/managed
              request on entry) → :authed"
      (let [{s1 :snapshot fx1 :fx} (step {:state :idle :data {:attempts 0 :error nil}} submit)
            {s2 :snapshot}         (step s1 [:walkthrough.login/success {:value {:token "t"}}])]
        (is (= [:submitting [:rf.http/managed] :authed]
               [(:state s1) (mapv first fx1) (:state s2)]))))

    (testing "pure lockout — with two failures recorded, the :under-retry-limit
              guard (which reads the snapshot before :record-error runs) rejects
              the third, whose fallback still records it: three attempts, the
              message kept, locked out"
      (let [{s :snapshot} (step {:state :submitting :data {:attempts 2 :error nil}}
                                [:walkthrough.login/failure {:error {:message "bad creds"}}])]
        (is (= [:locked-out 3 "bad creds"]
               [(:state s) (get-in s [:data :attempts]) (get-in s [:data :error])]))))

    (testing "pure :error-shown exits — BOTH ways out clear the stale error and keep
              the retry count. `:exit :clear-error` is one table line but two
              edges in behaviour, so each edge is read from the same failed
              snapshot, and dropping the clear from either alone goes red"
      (let [failed                {:state :error-shown :data {:attempts 1 :error "bad creds"}}
            {d :snapshot}         (step failed [:walkthrough.login/dismiss])
            {r :snapshot rfx :fx} (step failed submit)]
        ;; {:data {:error nil}} MERGES, so neither exit hands back fresh
        ;; attempts; slot order :exit → :action → :entry clears the message
        ;; before :issue-request fires, so a retry never rides beside it
        (is (= [[:idle nil 1] [:submitting nil 1 [:rf.http/managed]]]
               [[(:state d) (get-in d [:data :error]) (get-in d [:data :attempts])]
                [(:state r) (get-in r [:data :error]) (get-in r [:data :attempts]) (mapv first rfx)]]))))

    (testing "drain happy path — a full drain through the per-frame :fx-overrides
              canned-success stub lands the app-db at :authed"
      (let [f (rf.frame/make-anon-frame-record! {:fx-overrides {:rf.http/managed :walkthrough.login/canned-success}})]
        (rf/dispatch-sync [:walkthrough.login/flow submit] {:frame f})
        (is (= :authed (rf/compute-sub [:walkthrough.login/state] (rf/frame-state-value f))))))

    (testing "drain retry-then-lockout — under the lexical `rf/with-fx-overrides`,
              two failures cycle :submitting → :error-shown → :idle, and the third
              :submit lands at :locked-out"
      (let [f      (rf.frame/make-anon-frame-record! {})
            wrong  [:walkthrough.login/submit {:email "x@y.z" :password "wrong"}]]
        (rf/with-fx-overrides {:rf.http/managed :walkthrough.login/canned-failure}
          (dotimes [_ 2]
            (rf/dispatch-sync [:walkthrough.login/flow wrong] {:frame f})
            (rf/dispatch-sync [:walkthrough.login/flow [:walkthrough.login/dismiss]] {:frame f}))
          (rf/dispatch-sync [:walkthrough.login/flow wrong] {:frame f}))
        (is (= :locked-out (rf/compute-sub [:walkthrough.login/state] (rf/frame-state-value f))))))))
