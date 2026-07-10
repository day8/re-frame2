(ns {{namespace}}.ssr-test
  "Headless JVM SSR test for the emitted {{name}} SSR scaffold.

   The direct render test exercises frame setup, rendering, and the hydration
   hash. The handler tests compile and drive server.clj's production path,
   including schema attachment to the frame that ssr-handler creates.

   Run with `clojure -M:test`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr-ring]
            [{{namespace}}.core :as app]
            ;; Requiring the host keeps its API surface in the test build.
            [{{namespace}}.server :as server]))

(deftest ssr-render-produces-hydration-ready-html
  (testing "the SSR server flow renders the counter to HTML with a render hash"
    (rf/init! ssr/adapter)
    (let [fid      (keyword "rf.frame" (str (gensym "")))
          ;; Initial events read the request coeffect from this slot.
          _        (ssr/set-request! fid {:uri "/"})
          ;; Attach the schema before reg-frame drains initial events.
          _        (app/register-schema! fid)
          f        (rf/reg-frame fid
                     {:doc            "{{name}} SSR test frame"
                      :platform       :server
                      :initial-events app/server-init-events})
          final-db (rf/app-db-value f)]
      ;; Realise and hash the view inside the request frame so subscriptions
      ;; read the state seeded above.
      (rf/with-frame f
        (let [hiccup      ((rf/view :app/root))
              html        (rf/render-to-string hiccup {:emit-hash? true})
              render-hash (rf/render-tree-hash hiccup)]
          (is (= 0 (:counter/value final-db))
              ":rf/server-init seeded :counter/value")
          (is (string/includes? html "<h1>")
              "render-to-string round-trips the root view without React/JSDOM")
          (is (string/includes? html "{{name}}")
              "the rendered HTML carries the app title")
          (is (string/includes? html "0")
              "the seeded counter value is rendered")
          ;; The client compares this marker during hydration.
          (is (re-matches #"[0-9a-f]{8}" render-hash)
              "render-tree-hash is an 8-char lowercase-hex FNV-1a digest")
          (is (string/includes? html "data-rf-render-hash")
              "the root element carries the render-hash tripwire for the
               client-side hydration-mismatch check"))))))

(deftest ssr-handler-renders-through-the-real-production-path
  (testing "server.clj's OWN make-handler (the composite Ring handler
            `clojure -X:server` serves) renders normally end-to-end —
            :ssr/register-schema doesn't disturb the happy path, and
            requiring the host ns compiles server.clj"
    (rf/init! ssr/adapter)
    ;; static-root is unused by the SSR route exercised here.
    (let [handler (server/make-handler "resources/public/js")
          resp    (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status resp))
          "the production server/make-handler path renders a normal 200 response")
      (is (string/includes? (:body resp) "data-rf-render-hash")
          "the response body carries the render-hash tripwire"))))

(deftest ssr-handler-rejects-a-schema-violating-commit-on-the-real-path
  (testing "the REAL ssr-ring/ssr-handler path — the one server.clj actually
            serves — enforces the whole-app-db schema"
    (rf/init! ssr/adapter)
    ;; Append a commit forbidden by CounterDb. A 500 proves the schema is
    ;; attached to this request frame rather than another frame.
    (rf/reg-event :ssr-test/corrupt-commit
      {:doc "Test-only — forces a schema-violating app-db commit to prove
             server-side validation is live on the production ssr-handler
             path."}
      (fn [{:keys [db]} _event]
        {:db (assoc db :ssr-test/not-in-schema true)}))
    (let [handler (ssr-ring/ssr-handler
                    {:initial-events (conj app/server-init-events
                                           [:ssr-test/corrupt-commit])
                     :root-view      app/root-view
                     :payload        [:counter/value]})
          resp    (handler {:uri "/" :request-method :get})]
      (is (= 500 (:status resp))
          (str "expected the schema-violating commit to be REJECTED by the "
               "production ssr-handler path (proving server-side app-db "
               "validation is live), but got status " (:status resp)
               " — validation may not be attached to the request frame")))))
