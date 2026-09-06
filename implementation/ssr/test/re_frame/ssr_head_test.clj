(ns re-frame.ssr-head-test
  "Spec 011 §Head/meta contract — reg-head / render-head / active-head
  (rf2-4dra9).

  Covers:
    - reg-head registers under registry kind :head
    - render-head invokes the registered fn against db + route
    - active-head reads the active route's :head metadata and dispatches
    - default-head fires when no route declares :head
    - render-head RETURNS the produced model (a pure read; nothing is recorded)
    - head-model->html emits canonical-ordered tags
    - :rf.error/no-such-head raised for unregistered ids
    - reg-head is idempotent — re-registering replaces the slot

  Mirrors the reset-runtime fixture pattern from ssr_end_to_end_test.clj.

  ## Posture split (rf2-lwtlk)

  Two assertions here were about DEV-ONLY surfaces and dragged the rest of
  the namespace out of `scripts/test-ssr-prod-gate.sh` with them.

  `reg-head-accepts-metadata-arity` asserted `:doc` on the registry slot.
  `:doc` is a pure-documentation key: `registrar/strip-pure-documentation`
  drops it when `interop/debug-enabled?` is false, per Spec 001 §Production
  elision contract. So its absence under the gate is the elision working.
  The `:doc` assertion is kept verbatim in a `(when interop/debug-enabled? …)`
  arm; what the deftest is really for — that the 3-arity is a REGISTRATION
  arity, storing a working `:handler-fn` under the id — is asserted outside
  it, and a `when-not` arm pins the elision itself under the real gate.

  (`head-cleanup-throw-emits-warning-trace` covered the head-cleanup
  teardown hook and went with it — head reads keep no per-frame state, so
  there is no cleanup hook left to throw.)"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
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
      ;; SEMANTIC, posture-independent (rf2-lwtlk): the 3-arity is above all a
      ;; REGISTRATION arity — the extra metadata argument must not displace
      ;; the head-fn, and the slot must be usable.
      (is (some? m) "the 3-arity registered a slot")
      (is (fn? (:handler-fn m))
          "the head-fn is stored under :handler-fn, not shifted by the metadata arg")
      (is (= {:title "x"} ((:handler-fn m) {} nil))
          "the stored head-fn is the one passed, and it runs")

      ;; rf2-lwtlk — dev-instrumentation arm (see ns docstring). `:doc` is a
      ;; pure-documentation key, dropped by
      ;; `registrar/strip-pure-documentation` under the production gate per
      ;; Spec 001 §Production elision contract.
      (when interop/debug-enabled?
        (is (= "Article-page head model" (:doc m))
            ":doc metadata is preserved on the registry slot"))

      ;; rf2-lwtlk — the REAL-gate arm: the elision itself, witnessed on a JVM
      ;; actually started with `-Dre-frame.debug=false` rather than through a
      ;; `with-redefs` rebind that a load-time gate cannot see (rf2-9c2jf).
      (when-not interop/debug-enabled?
        (is (nil? (:doc m))
            ":doc is elided from the registry slot in production builds
             (Spec 001 §Production elision contract)")))))

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
            [:rf.runtime/routing :current] slice, and applies the registered fn"
    (rf/reg-head :head/article
                 (fn [db {:keys [params]}]
                   (let [{:keys [title summary]} (get-in db [:articles (:id params)])]
                     {:title title
                      :meta  [{:name "description" :content summary}]})))
    (let [f (frame/make-anon-frame-record!
              {:doc       "head test frame"
               :platform  :server
               :initial-events [[:set-test-state]]})]
      ;; EP-0001 (rf2-vzld77): the route slice is durable routing runtime-db
      ;; state — seed it via :rf.db/runtime; :articles stays in app-db.
      (rf/reg-event :set-test-state
                       (fn [{:keys [db] rt :rf.db/runtime} _]
                         {:db (assoc-in db [:articles "123"]
                                        {:title   "Hello SSR"
                                         :summary "A summary"})
                          :rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/routing :current]
                                                   {:route-id :route/article :params {:id "123"}})}))
      (rf/dispatch-sync [:set-test-state] {:frame f})
      (let [model (rf/render-head :head/article {:frame f})]
        (is (= "Hello SSR" (:title model)))
        (is (= [{:name "description" :content "A summary"}]
               (:meta model)))))))

(deftest render-head-accepts-frame-keyword-shorthand
  (testing "(render-head head-id frame-id) is sugar for (render-head head-id
            {:frame frame-id})"
    (rf/reg-head :head/simple (fn [_ _] {:title "bare"}))
    (let [f (frame/make-anon-frame-record! {:doc "shorthand frame" :platform :server})]
      (is (= {:title "bare"} (rf/render-head :head/simple f))))))

(deftest render-head-accepts-explicit-route-override
  (testing ":route opt overrides the slice read from app-db — useful for
            tools that want a hypothetical-route preview"
    (rf/reg-head :head/echo (fn [_ route] {:title (str (:route-id route))}))
    (let [f (frame/make-anon-frame-record! {:doc "explicit-route frame" :platform :server})]
      (is (= ":route/explicit"
             (:title (rf/render-head :head/echo
                                     {:frame f
                                      :route {:route-id :route/explicit}})))))))

(deftest render-head-raises-on-unregistered-id
  (testing "render-head against an unknown id throws :rf.error/no-such-head"
    (let [f (frame/make-anon-frame-record! {:doc "missing-head frame" :platform :server})]
      (try
        (rf/render-head :head/nope {:frame f})
        (is false "expected exception")
        (catch clojure.lang.ExceptionInfo e
          ;; rf2-vvixub — branch on the canonical :rf.error/id; the message is
          ;; the human :reason + the [:rf.error/<id>] token (non-normative bytes).
          (is (= :rf.error/no-such-head (:rf.error/id (ex-data e))))
          (is (= :head/nope (:head-id (ex-data e)))))))))

(deftest render-head-returns-each-head-model
  (testing "each render-head call RETURNS the model its registered fn
            produced — successive calls on one frame are independent reads,
            and the second call's return is the second head's model"
    (rf/reg-head :head/a (fn [_ _] {:title "A"}))
    (rf/reg-head :head/b (fn [_ _] {:title "B"}))
    (let [f (frame/make-anon-frame-record! {:doc "render-head frame" :platform :server})]
      (is (= {:title "A"} (rf/render-head :head/a {:frame f}))
          "the first call returns the first head's model")
      (is (= {:title "B"} (rf/render-head :head/b {:frame f}))
          "the second call returns the SECOND head's model, not the first's")
      (is (= {:title "A"} (rf/render-head :head/a {:frame f}))
          "re-reading the first head returns its model again — the read is pure"))))

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
                   :head :head/article} "/articles/:id")
    (let [f (frame/make-anon-frame-record! {:doc "active-route frame" :platform :server})]
      (rf/dispatch-sync
        [::seed-route] {:frame f})
      ;; The test sub-handler isn't registered; instead seed the runtime-db
      ;; directly — the framework's [:rf.runtime/routing :current] slice
      ;; populates via dispatch-driven routing.
      ;; We bypass with a one-shot event below.
      (rf/reg-event ::seed-route
                       (fn [{rt :rf.db/runtime} _]
                         {:rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/routing :current]
                                                   {:route-id :route/article :params {:id "42"}})}))
      (rf/dispatch-sync [::seed-route] {:frame f})
      (is (= {:title "Article 42"}
             (rf/active-head f))))))

(deftest active-head-falls-back-to-default-when-route-omits-head
  (testing "no :head on the route → default-head fires (viewport only)"
    (rf/reg-route :route/no-head
                  {:doc  "Bare route"} "/")
    (let [f (frame/make-anon-frame-record! {:doc "Default-head probe" :platform :server})]
      (rf/reg-event ::seed-route-no-head
                       (fn [{rt :rf.db/runtime} _]
                         {:rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/routing :current] {:route-id :route/no-head})}))
      (rf/dispatch-sync [::seed-route-no-head] {:frame f})
      (let [model (rf/active-head f)]
        (is (= "Default-head probe" (:title model))
            ":doc rolls into :title per Spec 011 §Default head")
        (is (not-any? #(contains? % :charset) (:meta model))
            "default does NOT carry a charset meta (rf2-q78s1) — charset is an
             envelope concern owned by the shell, not a per-route head concern")
        (is (some #(= "viewport" (:name %)) (:meta model))
            "default carries the viewport meta")))))

(deftest active-head-uses-default-when-no-route-at-all
  (testing "no route slice (e.g. a frame that hasn't routed yet) → default"
    (let [f (frame/make-anon-frame-record! {:doc "Bare" :platform :server})
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

(deftest head-model->html-json-ld-emits-json-valid-numbers
  (testing "rf2-8jl26 — the JVM JSON-LD emitter must produce JSON-valid
            numbers. A Clojure ratio is coerced to a double (so the wire
            form is a JSON number, not `1/3`); JSON.parse never sees a
            ratio literal."
    (let [html (rf/head-model->html
                 {:json-ld [{"@type" "Rating"
                             "ratingValue" 1/3}]})]
      (is (str/includes? html "\"ratingValue\":0.3333333333333333")
          "the ratio 1/3 is emitted as its double value, not `1/3`")
      (is (not (str/includes? html "1/3"))
          "no raw ratio literal survives into the JSON-LD body")))
  (testing "rf2-8jl26 — non-finite doubles (##Inf / ##-Inf / ##NaN) have no
            JSON representation; the emitter fails fast rather than emitting
            `Infinity` / `NaN`, which JSON.parse rejects"
    (doseq [bad [##Inf ##-Inf ##NaN]]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #":rf\.error/invalid-json-ld-number"
            (rf/head-model->html
              {:json-ld [{"@type" "Rating" "ratingValue" bad}]}))
          (str "non-finite JSON-LD number " (pr-str bad) " is rejected")))))

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

(deftest head-model->html-json-ld-escapes-control-chars
  (testing "rf2-hzttr finding 1 — the JVM JSON-LD emitter MUST JSON-escape
            control characters (U+0000..U+001F) inside string values. A raw
            newline/tab/CR in a `<script type=\"application/ld+json\">` body
            is INVALID JSON that search/social consumers reject; the CLJS
            branch gets this for free via JSON.stringify, so the JVM
            hand-rolled emitter must match or the two branches diverge."
    (testing "the five JSON-named short escapes (\\b \\t \\n \\f \\r)"
      (let [html (rf/head-model->html
                   {:json-ld [{"@type"   "Article"
                               "headline" (str "a" \newline "b" \tab "c"
                                               \return "d" \backspace "e"
                                               \formfeed "f")}]})]
        (is (str/includes? html "\\n") "newline → \\n")
        (is (str/includes? html "\\t") "tab → \\t")
        (is (str/includes? html "\\r") "carriage return → \\r")
        (is (str/includes? html "\\b") "backspace → \\b")
        (is (str/includes? html "\\f") "form feed → \\f")
        (is (not (str/includes? html (str \newline)))
            "no raw newline char survives in the JSON body")
        (is (not (str/includes? html (str \tab)))
            "no raw tab char survives in the JSON body")))

    (testing "other C0 control chars (U+0000..U+001F) use the \\u00XX escape"
      (let [html (rf/head-model->html
                   {:json-ld [{"@type"   "Article"
                               "headline" (str "x" (char 0x00) (char 0x01)
                                               (char 0x1f) "y")}]})]
        (is (str/includes? html "\\u0000") "NUL → \\u0000")
        (is (str/includes? html "\\u0001") "SOH → \\u0001")
        (is (str/includes? html "\\u001f") "US → \\u001f")))

    (testing "the rendered JSON-LD body parses as valid JSON — the
              regression's whole point. A strict parser rejects raw control
              bytes; assert every escape is present and no raw C0 byte
              survives in the post-`<`-decode body."
      (let [headline (str "line1" \newline "line2" \tab "tabbed" (char 0x07))
            html     (rf/head-model->html
                       {:json-ld [{"@context" "https://schema.org"
                                   "@type"    "Article"
                                   "headline" headline}]})
            ;; Carve the JSON body out of the <script> envelope.
            body     (-> html
                         (str/replace
                           "<script type=\"application/ld+json\">" "")
                         (str/replace "</script>" ""))
            ;; The client decodes `<` back to `<` before JSON.parse;
            ;; mirror that here so the assertions see the post-decode bytes.
            decoded  (str/replace body "\\u003c" "<")]
        (is (str/includes? decoded "\\n"))
        (is (str/includes? decoded "\\t"))
        (is (str/includes? decoded "\\u0007") "BEL (U+0007) → \\u0007")
        (doseq [cp (range 0x00 0x20)]
          (is (not (str/includes? decoded (str (char cp))))
              (str "no raw control char U+"
                   (format "%04X" cp) " survives in the JSON body")))))

    (testing "ordinary printable content is untouched (no over-escaping)"
      (let [html (rf/head-model->html
                   {:json-ld [{"@type" "Article" "headline" "Hello, world!"}]})]
        (is (str/includes? html "\"headline\":\"Hello, world!\"")
            "no spurious escapes on plain ASCII content")))))

(deftest head-model->html-json-ld-non-string-keys-are-quoted
  (testing "rf2-ee38b.10 — JSON object keys MUST be quoted strings. The
            JVM emitter previously emitted a bare key for number / boolean
            keys (`1:\"a\"`, `true:\"a\"`), invalid JSON the client's
            JSON.parse rejects; the CLJS branch coerces via JSON.stringify.
            The JVM branch now coerces every key to a quoted string."
    (testing "a numeric key is quoted (mirrors JSON.stringify)"
      (let [html (rf/head-model->html {:json-ld [{1 "a"}]})]
        (is (str/includes? html "\"1\":\"a\"")
            "the number key 1 is emitted as the quoted string key \"1\"")
        (is (not (str/includes? html "1:\"a\""))
            "no bare-number key survives — that is invalid JSON")))
    (testing "a boolean key is quoted"
      (let [html (rf/head-model->html {:json-ld [{true "a"}]})]
        (is (str/includes? html "\"true\":\"a\""))
        (is (not (str/includes? html ">true:")))))
    (testing "a ratio key is coerced to its double then quoted"
      (let [html (rf/head-model->html {:json-ld [{1/2 "a"}]})]
        (is (str/includes? html "\"0.5\":\"a\""))))
    (testing "a nil key has no JSON representation — fail fast"
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #":rf\.error/invalid-json-ld-key"
            (rf/head-model->html {:json-ld [{nil "a"}]}))))))

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
;; full integration — reg-head + reg-route + active-head + html emission
;; ===========================================================================

(deftest head-emits-canonical-html-from-active-route
  (testing "the canonical Spec-011 example flow: an article route declares
            :head :head/article; the head fn derives title/meta/link from
            app-db; active-head → head-model->html emits the tags in
            canonical order"
    (rf/reg-event :seed-article
                     (fn [{:keys [db] rt :rf.db/runtime} _]
                       {:db (assoc-in db [:articles "123"]
                                      {:title   "re-frame2 SSR"
                                       :summary "How re-frame2 ships SSR"
                                       :image   "https://example.com/og.png"})
                        :rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/routing :current]
                                                 {:route-id :route/article :params {:id "123"}})}))
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
                   :head :head/article} "/articles/:id")
    (let [f (frame/make-anon-frame-record! {:doc "article frame" :platform :server})]
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
                   :head :head/with-attrs} "/fr/articles/:id")
    (rf/reg-event :seed-fr
                     (fn [{rt :rf.db/runtime} _]
                       {:rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/routing :current]
                                                 {:route-id :route/article-fr :params {:id "1"}})}))
    (let [f (frame/make-anon-frame-record! {:platform :server})]
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

