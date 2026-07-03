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

   Run it with `clojure -M:test` (the `:test` alias picks it up alongside
   the CLJS node-test build)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
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
