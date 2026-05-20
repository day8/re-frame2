(ns re-frame.ssr-head-test
  "Spec 011 §Head/meta contract — reg-head / render-head / active-head
  (rf2-4dra9).

  Covers:
    - reg-head registers under registry kind :head
    - render-head invokes the registered fn against db + route
    - active-head reads the active route's :head metadata and dispatches
    - default-head fires when no route declares :head
    - head-snapshot records last-produced fragments per frame
    - per-request frame teardown clears head bookkeeping
    - head-model->html emits canonical-ordered tags
    - :rf.error/no-such-head raised for unregistered ids
    - reg-head is idempotent — re-registering replaces the slot

  Mirrors the reset-runtime fixture pattern from ssr_end_to_end_test.clj."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.head :as head]
            [re-frame.ssr.test-fixture :as tf]))

;; Shared reset fixture lives in `re-frame.ssr.test-fixture` (rf2-i3qc0).
(use-fixtures :each tf/reset-runtime)

;; ===========================================================================
;; reg-head — registry kind :head with handler-fn
;; ===========================================================================

(deftest reg-head-registers-under-head-kind
  (testing "reg-head adds an entry to the :head registry kind, keyed by id,
            with the head-fn under :handler-fn"
    (let [head-fn (fn [_db _route] {:title "Hello"})]
      (rf/reg-head :head/static head-fn)
      (let [meta (registrar/lookup :head :head/static)]
        (is (some? meta) "registry slot exists")
        (is (= head-fn (:handler-fn meta))
            "the head-fn is stored under :handler-fn")))))

(deftest reg-head-returns-the-id
  (testing "reg-head returns its id arg per Conventions §reg-* return-value"
    (is (= :head/whatever
           (rf/reg-head :head/whatever (fn [_ _] {}))))))

(deftest reg-head-accepts-metadata-arity
  (testing "the (reg-head id metadata head-fn) arity stores metadata keys"
    (rf/reg-head :head/with-meta
                 {:doc "Article-page head model"}
                 (fn [_db _route] {:title "x"}))
    (let [m (registrar/lookup :head :head/with-meta)]
      (is (= "Article-page head model" (:doc m))
          ":doc metadata is preserved on the registry slot"))))

(deftest reg-head-is-idempotent
  (testing "re-registering the same id replaces the slot (registrar contract)"
    (let [first-fn  (fn [_ _] {:title "first"})
          second-fn (fn [_ _] {:title "second"})]
      (rf/reg-head :head/redo first-fn)
      (rf/reg-head :head/redo second-fn)
      (is (= second-fn (:handler-fn (registrar/lookup :head :head/redo)))
          "second registration wins"))))

;; ===========================================================================
;; render-head — invoke against frame + active route
;; ===========================================================================

(deftest render-head-invokes-handler-against-db-and-route
  (testing "render-head reads the frame's app-db, the active route from the
            :rf/route slice, and applies the registered fn"
    (rf/reg-head :head/article
                 (fn [db {:keys [params]}]
                   (let [{:keys [title summary]} (get-in db [:articles (:id params)])]
                     {:title title
                      :meta  [{:name "description" :content summary}]})))
    (let [f (rf/make-frame
              {:doc       "head test frame"
               :platform  :server
               :on-create [:set-test-state]})]
      (rf/reg-event-db :set-test-state
                       (fn [db _]
                         (-> db
                             (assoc-in [:articles "123"]
                                       {:title   "Hello SSR"
                                        :summary "A summary"})
                             (assoc :rf/route
                                    {:id :route/article :params {:id "123"}}))))
      (rf/dispatch-sync [:set-test-state] {:frame f})
      (let [model (rf/render-head :head/article {:frame f})]
        (is (= "Hello SSR" (:title model)))
        (is (= [{:name "description" :content "A summary"}]
               (:meta model)))))))

(deftest render-head-accepts-frame-keyword-shorthand
  (testing "(render-head head-id frame-id) is sugar for (render-head head-id
            {:frame frame-id})"
    (rf/reg-head :head/simple (fn [_ _] {:title "bare"}))
    (let [f (rf/make-frame {:doc "shorthand frame" :platform :server})]
      (is (= {:title "bare"} (rf/render-head :head/simple f))))))

(deftest render-head-accepts-explicit-route-override
  (testing ":route opt overrides the slice read from app-db — useful for
            tools that want a hypothetical-route preview"
    (rf/reg-head :head/echo (fn [_ route] {:title (str (:id route))}))
    (let [f (rf/make-frame {:doc "explicit-route frame" :platform :server})]
      (is (= ":route/explicit"
             (:title (rf/render-head :head/echo
                                     {:frame f
                                      :route {:id :route/explicit}})))))))

(deftest render-head-raises-on-unregistered-id
  (testing "render-head against an unknown id throws :rf.error/no-such-head"
    (let [f (rf/make-frame {:doc "missing-head frame" :platform :server})]
      (try
        (rf/render-head :head/nope {:frame f})
        (is false "expected exception")
        (catch clojure.lang.ExceptionInfo e
          (is (= ":rf.error/no-such-head" (.getMessage e)))
          (is (= :head/nope (:head-id (ex-data e)))))))))

(deftest render-head-records-fragment-in-snapshot
  (testing "every render-head call records the produced model under (frame,
            head-id) so head-snapshot reflects the latest output"
    (rf/reg-head :head/a (fn [_ _] {:title "A"}))
    (rf/reg-head :head/b (fn [_ _] {:title "B"}))
    (let [f (rf/make-frame {:doc "snapshot frame" :platform :server})]
      (rf/render-head :head/a {:frame f})
      (rf/render-head :head/b {:frame f})
      (let [snap (head/head-snapshot f)]
        (is (= 2 (count snap)))
        (is (= {:title "A"} (snap :head/a)))
        (is (= {:title "B"} (snap :head/b)))))))

;; ===========================================================================
;; active-head — resolves via :head route metadata
;; ===========================================================================

(deftest active-head-uses-route-head-metadata
  (testing "active-head reads the :head key from the active route's
            registration; calls render-head; returns the model"
    (rf/reg-head :head/article
                 (fn [_db {:keys [params]}]
                   {:title (str "Article " (:id params))}))
    (rf/reg-route :route/article
                  {:doc  "Article page"
                   :path "/articles/:id"
                   :head :head/article})
    (let [f (rf/make-frame {:doc "active-route frame" :platform :server})]
      (rf/dispatch-sync
        [::seed-route] {:frame f})
      ;; The test sub-handler isn't registered; instead seed app-db directly
      ;; via with-frame and assoc — but the framework's :rf/route slice
      ;; populates via dispatch-driven routing. We bypass with a one-shot
      ;; event below.
      (rf/reg-event-db ::seed-route
                       (fn [db _]
                         (assoc db :rf/route
                                {:id :route/article :params {:id "42"}})))
      (rf/dispatch-sync [::seed-route] {:frame f})
      (is (= {:title "Article 42"}
             (rf/active-head f))))))

(deftest active-head-falls-back-to-default-when-route-omits-head
  (testing "no :head on the route → default-head fires (charset + viewport)"
    (rf/reg-route :route/no-head
                  {:doc  "Bare route"
                   :path "/"})
    (let [f (rf/make-frame {:doc "Default-head probe" :platform :server})]
      (rf/reg-event-db ::seed-route-no-head
                       (fn [db _]
                         (assoc db :rf/route {:id :route/no-head})))
      (rf/dispatch-sync [::seed-route-no-head] {:frame f})
      (let [model (rf/active-head f)]
        (is (= "Default-head probe" (:title model))
            ":doc rolls into :title per Spec 011 §Default head")
        (is (some #(= "utf-8" (:charset %)) (:meta model))
            "default carries the utf-8 charset meta")
        (is (some #(= "viewport" (:name %)) (:meta model))
            "default carries the viewport meta")))))

(deftest active-head-uses-default-when-no-route-at-all
  (testing "no :rf/route slice (e.g. a frame that hasn't routed yet) → default"
    (let [f (rf/make-frame {:doc "Bare" :platform :server})
          model (rf/active-head f)]
      (is (= "Bare" (:title model)))
      (is (seq (:meta model))))))

;; ===========================================================================
;; head-model->html — canonical-ordered emitter
;; ===========================================================================

(deftest head-model->html-canonical-order
  (testing "tags emit in canonical order: title → meta → link → script →
            JSON-LD. The model below intentionally provides keys in a
            non-canonical order to exercise ordering."
    (let [model {:link    [{:rel "canonical" :href "https://example.com/x"}]
                 :title   "Hello"
                 :meta    [{:name "description" :content "x"}
                           {:property "og:title" :content "Hello"}]
                 :script  [{:src "/main.js" :defer true}]
                 :json-ld [{"@type" "Article" "headline" "Hello"}]}
          html  (rf/head-model->html model)]
      (is (str/starts-with? html "<title>Hello</title>")
          "title is first")
      (let [t (.indexOf html "<title")
            m (.indexOf html "<meta")
            l (.indexOf html "<link")
            s (.indexOf html "<script src")
            j (.indexOf html "<script type=\"application/ld+json")]
        (is (< t m l s j)
            (str "tags not in canonical order: "
                 {:title t :meta m :link l :script s :json-ld j}))))))

(deftest head-model->html-meta-tags-shape
  (testing "meta tags render with declaration-order attributes; self-closing
            void elements emit without a closing tag"
    (let [html (rf/head-model->html
                 {:meta [{:name "description" :content "A summary"}
                         {:property "og:title" :content "T"}]})]
      (is (str/includes? html "<meta"))
      (is (str/includes? html "name=\"description\""))
      (is (str/includes? html "content=\"A summary\""))
      (is (str/includes? html "property=\"og:title\""))
      ;; void elements: no </meta> closing tag.
      (is (not (str/includes? html "</meta>"))))))

(deftest head-model->html-link-tags
  (testing "link tags render with canonical/href/rel attrs"
    (let [html (rf/head-model->html
                 {:link [{:rel "canonical" :href "https://example.com/x"}
                         {:rel "icon" :href "/favicon.ico"}]})]
      (is (str/includes? html "rel=\"canonical\""))
      (is (str/includes? html "href=\"https://example.com/x\""))
      (is (str/includes? html "rel=\"icon\"")))))

(deftest head-model->html-script-tags
  (testing "script tags render with src + boolean attrs (async/defer)"
    (let [html (rf/head-model->html
                 {:script [{:src "/main.js" :async true}
                           {:src "/other.js" :defer true :type "module"}]})]
      (is (str/includes? html "src=\"/main.js\""))
      (is (str/includes? html " async"))
      (is (str/includes? html " defer"))
      (is (str/includes? html "type=\"module\""))
      ;; <script> is not void — needs closing tag.
      (is (str/includes? html "</script>")))))

(deftest head-model->html-json-ld
  (testing "JSON-LD tags serialise the structured map and ride a
            <script type=\"application/ld+json\"> envelope"
    (let [html (rf/head-model->html
                 {:json-ld [{"@context" "https://schema.org"
                             "@type"    "Article"
                             "headline" "Hello"}]})]
      (is (str/includes? html "type=\"application/ld+json\""))
      (is (str/includes? html "\"@context\""))
      (is (str/includes? html "\"@type\":\"Article\""))
      (is (str/includes? html "\"headline\":\"Hello\"")))))

(deftest head-model->html-json-ld-preserves-keyword-namespaces
  (testing "rf2-a50nz — keyword map keys retain their namespace when
            serialised; the printer's key and value handling are symmetric.
            A user supplying `{:my.app/key \"value\"}` must see
            `\"my.app/key\":\"value\"` in the rendered JSON-LD."
    (let [html (rf/head-model->html
                 {:json-ld [{:my.app/key "value"
                             :unqualified "v2"}]})]
      (is (str/includes? html "\"my.app/key\":\"value\"")
          "namespaced keyword key preserves its namespace")
      (is (str/includes? html "\"unqualified\":\"v2\"")
          "unqualified keyword key still serialises as a bare name"))
    (testing "keyword values continue to preserve namespace (regression
              guard against accidental asymmetry resurfacing)"
      (let [html (rf/head-model->html
                   {:json-ld [{"@type" :schema/Article
                               :my.app/headline :my.app/hello}]})]
        (is (str/includes? html "\"@type\":\"schema/Article\""))
        (is (str/includes? html "\"my.app/headline\":\"my.app/hello\""))))))

(deftest head-model->html-json-ld-escapes-script-close-in-string-values
  (testing "rf2-m5u23 / security audit 2026-05-14 §P1.1 — a string value
            containing `</script>` MUST NOT close the surrounding
            `<script type=\"application/ld+json\">` envelope. Every `<`
            inside string contents is escaped as `\\u003c`; JSON.parse
            on the client accepts `\\u003c` as a six-character escape
            for `<`, so the payload round-trips unchanged."
    (let [hostile "</script><script>alert(document.cookie)</script>"
          html    (rf/head-model->html
                    {:json-ld [{"@context" "https://schema.org"
                                "@type"    "Article"
                                "headline" hostile}]})]
      ;; The hostile literal MUST NOT survive — it would close our
      ;; <script type="application/ld+json"> envelope.
      (is (not (str/includes? html "</script><script>alert"))
          "the closing-tag pattern is broken — no raw </script> escape")
      ;; The escape sequence appears in place of each `<` char (the
      ;; original string carried two `<` — the closing-tag escape and
      ;; the nested-script opener).
      (is (str/includes? html "\\u003c/script>\\u003cscript>")
          "every `<` in the string value is escaped as the JSON `\\u003c` escape")
      ;; Sanity: the envelope's own closing </script> is still present
      ;; (it's the genuine end of the JSON-LD block).
      (is (str/ends-with? html "</script>")
          "the genuine envelope-closing </script> is unaffected"))))

(deftest head-model->html-json-ld-escapes-script-close-in-keys
  (testing "rf2-m5u23 — a `<` inside a JSON-LD KEY (a string-keyed map
            entry that somehow carries `<`) is also escaped. Defensive:
            map keys aren't a typical attack surface, but the helper
            walks the whole string, so this is free coverage."
    (let [hostile-key "</script>"
          html        (rf/head-model->html
                        {:json-ld [{hostile-key "value"}]})]
      (is (not (str/includes? html "</script>\":"))
          "</script> as a key cannot close the envelope (the `:value`
           separator immediately follows the key — assert no
           `</script>\":` substring survives)")
      (is (str/includes? html "\\u003c/script>")
          "`<` in keys comes through escaped (only `<` is escaped — `>`
           is harmless inside a <script> body and remains literal)"))))

(deftest head-model->html-empty-model
  (testing "an empty / minimal model emits nothing (no orphan tags)"
    (is (= "" (rf/head-model->html {})))
    (is (= "" (rf/head-model->html nil)))
    (is (= "<head></head>"
           (rf/head-model->html {} {:wrap? true})))))

(deftest head-model->html-attr-name-validation
  (testing "rf2-vl8ir / security audit 2026-05-14 §P2.5 — attribute KEYS
            are gated by the HTML5 grammar `[A-Za-z][A-Za-z0-9_:-]*`. A
            key that violates the grammar throws
            `:rf.error/ssr-invalid-attribute-name` rather than emitting
            attacker-controlled `<meta>` / `<link>` attributes that
            could carry event-handler payloads."
    (testing "valid keys pass through unchanged"
      (let [html (rf/head-model->html
                   {:meta [{:name        "viewport"
                            :content     "width=device-width"
                            :data-theme  "dark"}]})]
        (is (str/includes? html "name=\"viewport\""))
        (is (str/includes? html "data-theme=\"dark\""))))

    (testing "a key with an `=`-injection payload throws"
      ;; The exploit shape: a key like "onclick=alert(1) data-x" would
      ;; (without validation) render as ` onclick=alert(1) data-x=\"…\"`,
      ;; injecting an event-handler attribute.
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/ssr-invalid-attribute-name"
            (rf/head-model->html
              {:meta [{(keyword "onclick=alert(1) data-x") "v"}]}))))

    (testing "a key starting with a digit throws (HTML5 first-char rule)"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/ssr-invalid-attribute-name"
            (rf/head-model->html
              {:meta [{(keyword "1bad") "v"}]}))))

    (testing "a key with whitespace throws"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #":rf\.error/ssr-invalid-attribute-name"
            (rf/head-model->html
              {:meta [{(keyword "bad attr") "v"}]}))))))

(deftest head-model->html-escaping
  (testing "title and attribute values are HTML/attribute escaped — no raw
            tag injection"
    (let [html (rf/head-model->html
                 {:title "Hello <script>alert(1)</script>"
                  :meta  [{:name "x" :content "\"weird\""}]})]
      (is (str/includes? html "&lt;script&gt;")
          "title is HTML-escaped — no raw tag injection")
      (is (str/includes? html "content=\"&quot;weird&quot;\"")
          "attribute values are attribute-escaped"))))

(deftest head-model->html-wraps-on-opt
  (testing "the {:wrap? true} opt surrounds with <head></head>"
    (let [html (rf/head-model->html {:title "Hi"} {:wrap? true})]
      (is (str/starts-with? html "<head>"))
      (is (str/ends-with? html "</head>"))
      (is (str/includes? html "<title>Hi</title>")))))

;; ===========================================================================
;; per-request frame teardown — head bookkeeping cleared on destroy
;; ===========================================================================

(deftest head-snapshot-cleared-on-frame-destroy
  (testing "destroying a per-request frame drops its head-snapshot entry —
            head bookkeeping must not leak across requests, per Spec 011
            §Per-request frame teardown and rf2-fcj33"
    (rf/reg-head :head/leaktest (fn [_ _] {:title "snapshot-A"}))
    (let [f (rf/make-frame {:doc "teardown frame" :platform :server})]
      (rf/render-head :head/leaktest {:frame f})
      (is (= {:title "snapshot-A"}
             (get (head/head-snapshot f) :head/leaktest))
          "snapshot present pre-destroy")
      (rf/destroy-frame! f)
      (is (= {} (head/head-snapshot f))
          "snapshot cleared post-destroy"))))

(deftest head-snapshot-isolated-across-frames
  (testing "two frames carry independent head-snapshots — destroying one
            doesn't touch the other"
    (rf/reg-head :head/iso (fn [_ _] {:title "x"}))
    (let [f1 (rf/make-frame {:doc "frame-1" :platform :server})
          f2 (rf/make-frame {:doc "frame-2" :platform :server})]
      (rf/render-head :head/iso {:frame f1})
      (rf/render-head :head/iso {:frame f2})
      (is (seq (head/head-snapshot f1)))
      (is (seq (head/head-snapshot f2)))
      (rf/destroy-frame! f1)
      (is (= {} (head/head-snapshot f1)))
      (is (seq (head/head-snapshot f2))
          "destroying f1 did not clear f2's bookkeeping"))))

;; ===========================================================================
;; rf/head-snapshot — public re-export (rf2-p1frh, parent rf2-ip6ol)
;; ===========================================================================

(deftest rf-head-snapshot-resolves
  (testing "rf/head-snapshot is a public-Var on re-frame.core (rf2-p1frh)"
    (let [v (resolve 're-frame.core/head-snapshot)]
      (is (some? v) "rf/head-snapshot resolves to a Var")
      (is (fn? @v)  "rf/head-snapshot is callable"))))

(deftest rf-head-snapshot-returns-per-frame-map
  (testing "rf/head-snapshot returns the per-frame
            {head-id → last-produced head-model} snapshot — the same data
            the internal re-frame.ssr.head/head-snapshot returns"
    (rf/reg-head :head/pub-a (fn [_ _] {:title "Pub-A"}))
    (rf/reg-head :head/pub-b (fn [_ _] {:title "Pub-B"}))
    (let [f (rf/make-frame {:doc "rf/head-snapshot frame" :platform :server})]
      (is (= {} (rf/head-snapshot f))
          "empty pre-render")
      (rf/render-head :head/pub-a {:frame f})
      (rf/render-head :head/pub-b {:frame f})
      (let [snap (rf/head-snapshot f)]
        (is (= 2 (count snap)))
        (is (= {:title "Pub-A"} (snap :head/pub-a)))
        (is (= {:title "Pub-B"} (snap :head/pub-b)))
        (is (= snap (head/head-snapshot f))
            "rf/head-snapshot agrees with the internal snapshot reader")))))

;; ===========================================================================
;; full integration — reg-head + reg-route + active-head + html emission
;; ===========================================================================

(deftest head-emits-canonical-html-from-active-route
  (testing "the canonical Spec-011 example flow: an article route declares
            :head :head/article; the head fn derives title/meta/link from
            app-db; active-head → head-model->html emits the tags in
            canonical order"
    (rf/reg-event-db :seed-article
                     (fn [db _]
                       (-> db
                           (assoc-in [:articles "123"]
                                     {:title   "re-frame2 SSR"
                                      :summary "How re-frame2 ships SSR"
                                      :image   "https://example.com/og.png"})
                           (assoc :rf/route
                                  {:id :route/article :params {:id "123"}}))))
    (rf/reg-head :head/article
                 {:doc "Article-page head model"}
                 (fn [db {:keys [params]}]
                   (let [{:keys [title summary image]}
                         (get-in db [:articles (:id params)])]
                     {:title (str "Article: " title " — Example")
                      :meta  [{:name "description" :content summary}
                              {:property "og:title" :content title}
                              {:property "og:image" :content image}]
                      :link  [{:rel "canonical"
                               :href (str "https://example.com/articles/"
                                          (:id params))}]})))
    (rf/reg-route :route/article
                  {:doc  "Article page"
                   :path "/articles/:id"
                   :head :head/article})
    (let [f (rf/make-frame {:doc "article frame" :platform :server})]
      (rf/dispatch-sync [:seed-article] {:frame f})
      (let [model (rf/active-head f)
            html  (rf/head-model->html model)]
        (is (= "Article: re-frame2 SSR — Example" (:title model)))
        (is (str/includes? html
                           "<title>Article: re-frame2 SSR — Example</title>"))
        (is (str/includes? html
                           "<meta name=\"description\" content=\"How re-frame2 ships SSR\">"))
        (is (str/includes? html
                           "<meta property=\"og:title\" content=\"re-frame2 SSR\">"))
        (is (str/includes? html
                           "<meta property=\"og:image\" content=\"https://example.com/og.png\">"))
        (is (str/includes? html
                           "<link rel=\"canonical\" href=\"https://example.com/articles/123\">"))))))

;; ===========================================================================
;; rf2-hyk9j TC-2 — :html-attrs / :body-attrs head-model keys reach the model
;; ===========================================================================
;;
;; Per Spec 011 §Head/meta — line 478: head models may carry `:html-attrs`
;; and `:body-attrs`; the host shell stamps them on the opening tags
;; (`head-model->html` deliberately drops them — the shell layer is the
;; right place to stamp). The ssr-ring shell test pins the wire emission;
;; this test pins the model-side contract — the keys survive
;; `reg-head` → `render-head` → `active-head` verbatim, so any shell
;; implementation (including non-default ones) can read them.

(deftest html-attrs-and-body-attrs-survive-render-head
  (testing "head models with :html-attrs / :body-attrs are produced verbatim
            and reach the active-head consumer (Spec 011 §Head/meta line 478)"
    (rf/reg-head :head/with-attrs
                 (fn [_ _]
                   {:title      "Article — fr-FR"
                    :html-attrs {:lang "fr" :data-theme "dark"}
                    :body-attrs {:class "page-article"}}))
    (rf/reg-route :route/article-fr
                  {:doc  "French article page"
                   :path "/fr/articles/:id"
                   :head :head/with-attrs})
    (rf/reg-event-db :seed-fr
                     (fn [db _]
                       (assoc db :rf/route
                              {:id :route/article-fr :params {:id "1"}})))
    (let [f (rf/make-frame {:platform :server})]
      (rf/dispatch-sync [:seed-fr] {:frame f})
      (let [model (rf/active-head f)]
        (is (= "Article — fr-FR" (:title model)))
        (is (= {:lang "fr" :data-theme "dark"} (:html-attrs model))
            ":html-attrs reaches the model verbatim")
        (is (= {:class "page-article"} (:body-attrs model))
            ":body-attrs reaches the model verbatim")))))

(deftest head-model-to-html-drops-attr-bags
  (testing "head-model->html does NOT inline :html-attrs / :body-attrs in the
            fragment — those bags belong on <html> / <body> in the surrounding
            shell, not in the head fragment"
    (let [html (rf/head-model->html
                 {:title      "X"
                  :html-attrs {:lang "fr"}
                  :body-attrs {:class "page-x"}})]
      (is (str/includes? html "<title>X</title>"))
      (is (not (str/includes? html "lang"))
          "head-model->html does not stamp :html-attrs anywhere in its output")
      (is (not (str/includes? html "page-x"))
          "head-model->html does not stamp :body-attrs anywhere in its output"))))

;; ===========================================================================
;; rf2-j54ee CQ-2 — destroy-time trace symmetry on head cleanup hook
;; ===========================================================================
;;
;; Per request.cljc's `on-frame-destroyed!`: a head-cleanup throw must
;; surface on the trace bus rather than vanishing silently. Mirrors the
;; pattern shipped at `ssr-ring/lifecycle/destroy-frame-quietly!`
;; (audit R6 / cluster rf2-sljs1).

(deftest head-cleanup-throw-emits-warning-trace
  (testing "if the :ssr/head-on-frame-destroyed hook throws, the destroy
            still completes AND a :rf.ssr.head/cleanup-failed warning trace
            surfaces (audit rf2-cegm7 CQ-2 / rf2-j54ee)"
    (let [prior-hook (late-bind/get-fn :ssr/head-on-frame-destroyed)
          traces     (atom [])
          ssr-request (requiring-resolve 're-frame.ssr.request/on-frame-destroyed!)]
      ;; Install a head-cleanup hook that throws — vanity exception
      ;; so the catch path runs.
      (late-bind/set-fn! :ssr/head-on-frame-destroyed
        (fn [_frame-id] (throw (ex-info "boom from head cleanup" {:reason :test}))))
      (try
        (rf/register-listener! ::head-clean (fn [ev] (swap! traces conj ev)))
        ;; Drive the destroy hook directly — that's the path Mark-3
        ;; teardown takes per the late-bind chaining.
        (try (ssr-request :test/frame-id)
             (catch Throwable _
               ;; The hook must NOT propagate the exception — fail the
               ;; test if it does.
               (is false "on-frame-destroyed! must catch head-cleanup throws")))
        (rf/unregister-listener! ::head-clean)

        (let [hits (filterv #(= :rf.ssr.head/cleanup-failed (:operation %)) @traces)]
          (is (= 1 (count hits))
              (str "expected one :rf.ssr.head/cleanup-failed trace; saw: "
                   (pr-str (mapv :operation @traces))))
          (when (seq hits)
            (let [ev (first hits)]
              (is (= :warning (:op-type ev)))
              (is (= :test/frame-id (-> ev :tags :frame))
                  ":frame tag identifies which frame's cleanup failed")
              (is (= :ssr/head-on-frame-destroyed (-> ev :tags :hook))
                  ":hook tag identifies which hook misbehaved")
              (is (string? (-> ev :tags :reason))
                  ":reason carries the throwable's message")
              (is (string? (-> ev :tags :ex-class))
                  ":ex-class carries the throwable's class name")
              (is (= :warned-and-skipped (:recovery ev))
                  ":recovery names the policy — observed and continued"))))
        (finally
          ;; Restore the prior hook so subsequent tests see normal behaviour.
          (if prior-hook
            (late-bind/set-fn! :ssr/head-on-frame-destroyed prior-hook)
            (swap! late-bind/hooks dissoc :ssr/head-on-frame-destroyed)))))))
