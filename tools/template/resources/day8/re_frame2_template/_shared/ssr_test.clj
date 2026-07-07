(ns {{namespace}}.ssr-test
  "Headless JVM SSR test for the emitted {{name}} SSR scaffold.

   Mirrors the canonical worked-example gate in the re-frame2 repo
   (`re-frame.examples-test/ssr-example-runs-end-to-end`,
   `examples/capabilities/ssr/ssr`): boots the SSR adapter, stands up a
   per-request `:server` frame that drains `:rf/server-init`, renders the
   `:app/root` view to a string with `render-to-string`, and asserts the
   HTML content + the structural render-hash marker. No React / JSDOM — it
   runs end-to-end on the JVM.

   This is the gate shape a template-emitted SSR scaffold should ship (Spec
   011 §Hydration-mismatch detection): it proves the server render path
   compiles and produces hydration-ready HTML with the
   `data-rf-render-hash` tripwire the client compares against.

   `ssr-render-produces-hydration-ready-html` (above) hand-rolls
   `reg-frame` + `register-schema!` directly, which is a DIFFERENT code
   path from the one `server.clj` actually serves — `server.clj` never
   sees its own per-request frame id (`ssr-ring/ssr-handler` gensyms it
   internally), so a test that supplies the frame id itself cannot catch a
   regression in how the schema attaches to THAT path (rf2-hbobni). The
   two tests below exercise the REAL `ssr-ring/ssr-handler` construction
   server.clj uses — the happy path renders normally, and a deliberately
   schema-violating commit is REJECTED, proving `:ssr/register-schema`
   attaches the whole-app-db schema to the exact frame `ssr-handler`
   constructs for the request, not a different/default one.

   Run it with `clojure -M:test` (the `:test` alias picks it up alongside
   the CLJS node-test build)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr-ring]
            [{{namespace}}.core :as app]))

(deftest ssr-render-produces-hydration-ready-html
  (testing "the SSR server flow renders the counter to HTML with a render hash"
    ;; Boot the runtime (idempotent) — installs the SSR adapter. re-frame.ssr
    ;; exports its own JVM-side `adapter` var (the counterpart of the
    ;; reagent/uix/helix adapters); pass it explicitly.
    (rf/init! ssr/adapter)
    (let [fid      (keyword "rf.frame" (str (gensym "")))
          ;; Populate the per-request request slot before the frame's
          ;; :initial-events drain (the `:rf.server/request` cofx reads it).
          _        (ssr/set-request! fid {:uri "/"})
          ;; Register the whole-app-db schema against this per-request frame
          ;; BEFORE reg-frame drains :rf/server-init — otherwise the seed
          ;; commit has nothing to validate against.
          _        (app/register-schema! fid)
          f        (rf/reg-frame fid
                     {:doc            "{{name}} SSR test frame"
                      :platform       :server
                      :initial-events app/server-init-events})
          final-db (rf/app-db-value f)]
      ;; The root view derefs (rf/subscribe [:counter/value]), so it MUST
      ;; be realised INSIDE the frame scope — `with-frame` binds
      ;; *current-frame* across the render so the sub reads from f, not
      ;; :rf/default (and not "no frame context"). We call the view fn,
      ;; render, and hash all inside the one scope.
      (rf/with-frame f
        (let [hiccup      ((rf/view :app/root))
              html        (rf/render-to-string hiccup {:emit-hash? true})
              render-hash (rf/render-tree-hash hiccup)]
          ;; :rf/server-init seeded the counter.
          (is (= 0 (:counter/value final-db))
              ":rf/server-init seeded :counter/value")
          ;; The rendered HTML carries the counter view.
          (is (string/includes? html "<h1>")
              "render-to-string round-trips the root view without React/JSDOM")
          (is (string/includes? html "{{name}}")
              "the rendered HTML carries the app title")
          (is (string/includes? html "0")
              "the seeded counter value is rendered")
          ;; The render hash is a structural marker (lowercase-hex FNV-1a
          ;; per Spec 011); the client recomputes it and the runtime emits
          ;; :rf.ssr/hydration-mismatch on disagreement.
          (is (re-matches #"[0-9a-f]{8}" render-hash)
              "render-tree-hash is an 8-char lowercase-hex FNV-1a digest")
          (is (string/includes? html "data-rf-render-hash")
              "the root element carries the render-hash tripwire for the
               client-side hydration-mismatch check"))))))

(deftest ssr-handler-renders-through-the-real-production-path
  (testing "the REAL ssr-ring/ssr-handler construction (mirroring server.clj's
            make-handler) renders normally end-to-end — :ssr/register-schema
            doesn't disturb the happy path"
    (rf/init! ssr/adapter)
    (let [handler (ssr-ring/ssr-handler
                    {:initial-events app/server-init-events
                     :root-view      app/root-view
                     :payload        [:counter/value]})
          resp    (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status resp))
          "the production ssr-handler path renders a normal 200 response")
      (is (string/includes? (:body resp) "data-rf-render-hash")
          "the response body carries the render-hash tripwire"))))

(deftest ssr-handler-rejects-a-schema-violating-commit-on-the-real-path
  (testing "the REAL ssr-ring/ssr-handler path — the one server.clj actually
            serves — enforces the whole-app-db schema (rf2-hbobni)"
    (rf/init! ssr/adapter)
    ;; A test-only step appended AFTER the app's own `server-init-events`,
    ;; committing a key CounterDb's `{:closed true}` map forbids. If
    ;; `:ssr/register-schema` failed to attach the schema to THIS
    ;; per-request frame (the bug rf2-hbobni describes — the schema landing
    ;; on a different/default frame the server never renders against), this
    ;; commit would sail through uncaught and the assertion below would
    ;; fail, catching the regression.
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
               " — this is exactly the silently-inert-validation bug "
               "rf2-hbobni describes if it regresses")))))
