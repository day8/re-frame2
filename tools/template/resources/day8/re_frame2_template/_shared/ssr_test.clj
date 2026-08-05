(ns {{namespace}}.ssr-test
  "Headless JVM SSR test for the emitted {{name}} SSR scaffold.

   The direct render test exercises frame setup, rendering, and the hydration
   hash. The handler tests compile and drive server.clj's production path,
   including schema attachment to the frame that ssr-handler creates, the live
   shell's CSS scaffold, and the contained static-asset serving.

   Run with `clojure -M:test`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr-ring]
            [{{namespace}}.core :as app]
            ;; Requiring the host keeps its API surface in the test build.
            [{{namespace}}.server :as server]))

(def ^:private tailwind?
  "This scaffold's `:css` mode — `{{css-name}}` is \"tailwind\" or \"\"."
  (= "{{css-name}}" "tailwind"))

(defn- body-str
  "The response body as a String — the SSR path returns HTML strings, the
   static-asset path returns java.io.File bodies."
  [resp]
  (let [b (:body resp)]
    (cond
      (string? b)                 b
      (instance? java.io.File b)  (slurp b)
      :else                       (str b))))

(defn- ssr-html-fallthrough?
  "True when `resp`'s body is a rendered SSR document — the signal that a
   static/asset request wrongly fell through to the SSR renderer. Keyed on the
   `<!DOCTYPE html>` document prefix, NOT a hydration marker like
   `__rf_payload`: the compiled JS bundle legitimately embeds that marker
   string (the client hydrator reads `getElementById(\"__rf_payload\")`), so it
   can't distinguish a served bundle from an SSR page."
  [resp]
  (string/includes? (body-str resp) "<!DOCTYPE html>"))

(deftest ssr-render-produces-hydration-ready-html
  (testing "the SSR server flow renders the counter to HTML with a render hash"
    (rf/init! ssr/adapter)
    (let [fid      (keyword "rf.frame" (str (gensym "")))
          ;; Initial events read the request coeffect from this slot.
          _        (ssr/set-request! fid {:uri "/"})
          ;; Attach the schema before make-frame drains initial events.
          _        (app/register-schema! fid)
          _        (rf/make-frame {:id             fid
                                   :doc            "{{name}} SSR test frame"
                                   :platform       :server
                                   :initial-events app/server-init-events})
          final-db (rf/app-db-value fid)]
      ;; Realise and hash the view inside the request frame so subscriptions
      ;; read the state seeded above.
      (rf/with-frame fid
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
               client-side hydration-mismatch check")
          ;; The pairing this tripwire depends on. `render-tree-hash` never
          ;; expands a callable head, so the reference form
          ;; `[(rf/view :app/root)]` hashes one constant for every app on
          ;; earth — ssr-ring omits the hash entirely for it (rf2-q1b96).
          ;; `root-view` must therefore resolve to the same DOM-rooted tree
          ;; the client's `:render-tree-fn` hashes, or the two ends compare
          ;; different trees and every page reports a mismatch.
          (is (= render-hash (rf/render-tree-hash (app/root-view)))
              "the server's :root-view hashes the SAME tree the client's
               :render-tree-fn does — note the outer call in both"))))))

(deftest ssr-handler-renders-through-the-real-production-path
  (testing "server.clj's OWN make-handler (the composite Ring handler
            `clojure -X:server` serves) renders normally end-to-end —
            :ssr/register-schema doesn't disturb the happy path, and
            requiring the host ns compiles server.clj"
    (rf/init! ssr/adapter)
    (let [handler (server/make-handler "resources/public")
          resp    (handler {:uri "/" :request-method :get})]
      (is (= 200 (:status resp))
          "the production server/make-handler path renders a normal 200 response")
      (is (string/includes? (:body resp) "data-rf-render-hash")
          "the response body carries the render-hash tripwire"))))

(deftest ssr-live-shell-loads-and-serves-the-css-scaffold
  (testing "the live SSR shell (the one server.clj's make-handler serves —
            NOT the unused static index.html) links the selected CSS scaffold,
            the composite handler serves it with a CSS MIME, and the
            static-asset boundary contains URI traversal"
    (rf/init! ssr/adapter)
    (let [handler (server/make-handler "resources/public")]

      ;; -- the live shell links the CSS (Tailwind mode: + the dev compiler),
      ;;    and preserves every hydration/head invariant --
      (let [resp (handler {:uri "/" :request-method :get})
            body (body-str resp)]
        (is (= 200 (:status resp)) "the SSR / route renders a 200")
        (is (string/includes? body "<link rel=\"stylesheet\" href=\"/css/app.css\">")
            "the / response links the emitted CSS scaffold, so the page loads styled")
        (when tailwind?
          (is (string/includes? body "@tailwindcss/browser@4")
              "Tailwind mode loads the @tailwindcss/browser Play CDN compiler in the live shell")
          ;; The Play CDN compiler reads ONLY inline
          ;; `<style type="text/tailwindcss">` nodes — never an external
          ;; `<link>` stylesheet. So the live SSR response must carry the
          ;; CSS-first SOURCE inline here (not just the CDN script), else the
          ;; @import/@theme directives are silently dropped and the linked
          ;; app.css becomes an unread input.
          (is (string/includes? body "<style type=\"text/tailwindcss\">")
              "Tailwind mode injects the inline <style type=\"text/tailwindcss\"> source block — the Play CDN compiler's only input")
          (is (re-find #"(?s)<style type=\"text/tailwindcss\">.*?@import \"tailwindcss\";.*?@theme" body)
              "the inline source block carries the @import + @theme the compiler actually compiles"))
        (is (string/includes? body "__rf_payload")
            "the __rf_payload hydration script id is preserved")
        (is (string/includes? body "id=\"app\"")
            "the #app hydration root is preserved")
        (is (string/includes? body "data-rf-render-hash")
            "the render-hash tripwire is preserved")
        (is (string/includes? body "src=\"/main.js\"")
            "the /main.js bootstrap is preserved")
        (is (string/includes? body "data-rf-xray-host")
            "the live shell carries the [data-rf-xray-host] Xray host")
        (is (string/includes? body "<title>")
            "the route-resolved head title/meta survives the CSS injection (not clobbered)"))

      ;; -- GET /css/app.css returns the emitted stylesheet, CSS MIME, NOT HTML --
      (let [resp (handler {:uri "/css/app.css" :request-method :get})
            body (body-str resp)]
        (is (= 200 (:status resp)) "GET /css/app.css is served, not 404/SSR")
        (is (= "text/css; charset=utf-8" (get-in resp [:headers "Content-Type"]))
            "the stylesheet is served as text/css, not an SSR HTML document")
        (is (not (ssr-html-fallthrough? resp))
            "GET /css/app.css returns CSS bytes, NOT the SSR HTML app response")
        ;; The served app.css is ordinary native CSS in BOTH modes — the
        ;; Tailwind CSS-first source lives inline in the live shell, never
        ;; here. So it carries the plain Xray-host layout and, crucially, NO
        ;; bare `@import "tailwindcss"` (which the browser would turn into a
        ;; bogus /css/tailwindcss request). This is the SSR-response
        ;; counterpart of the SPA "no phantom request" proof.
        (is (string/includes? body "rf2-xray-host")
            "the served app.css carries the plain Xray-host layout (native CSS, both css modes)")
        (is (not (string/includes? body "@import \"tailwindcss\""))
            "the served app.css has NO bare @import \"tailwindcss\" — the Tailwind
             input lives inline in the live shell, so the browser issues no
             phantom /css/tailwindcss request"))

      ;; -- /main.js keeps a JavaScript MIME (never an SSR HTML fallthrough) --
      (let [resp (handler {:uri "/main.js" :request-method :get})]
        (is (contains? #{200 404} (:status resp))
            "the bundle route resolves (200 built / 404 not-yet-built), never SSR HTML")
        (when (= 200 (:status resp))
          (is (= "text/javascript; charset=utf-8" (get-in resp [:headers "Content-Type"]))
              "the built bundle keeps a JavaScript MIME"))
        (is (not (ssr-html-fallthrough? resp))
            "the bundle route never falls through to an SSR HTML response"))

      ;; -- static-asset containment: a ../-style URI cannot escape public root --
      (let [resp (handler {:uri "/css/../../../../../../deps.edn" :request-method :get})]
        (is (= 403 (:status resp))
            "a ../-style traversal under /css is rejected (contained under public root)")
        (is (not (ssr-html-fallthrough? resp))
            "a traversal request does not fall through to an SSR HTML document"))

      ;; -- a missing static asset is a 404, not a 200 SSR HTML document --
      (let [resp (handler {:uri "/css/does-not-exist.css" :request-method :get})]
        (is (= 404 (:status resp))
            "a missing CSS asset returns 404, not a 200 SSR HTML document mislabelled CSS")
        (is (not (ssr-html-fallthrough? resp))
            "a missing-asset request does not fall through to SSR")))))

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
