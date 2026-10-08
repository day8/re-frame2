(ns re-frame.ssr-head-test
  "Spec 011 §Head/meta contract on the JVM: `reg-head`, the parts of
  `head-model`'s selection rule `re-frame.ssr.head-model-cljs-test` does not
  pin on both hosts, and the hand-rolled JVM `head-model->html` emitter
  (canonical order, escaping, the JSON-LD printer)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- frame-on-route!
  "A server frame whose runtime-db route slice names `route-id`."
  [doc route-id]
  (let [f (rf.frame/make-anon-frame-record! {:doc doc :platform :server})]
    (rf/reg-event ::seed-route
                  (fn [{rt :rf.db/runtime} _]
                    {:rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/routing :current]
                                              {:route-id route-id})}))
    (rf/dispatch-sync [::seed-route] {:frame f})
    f))

(defn- ld-json [json]
  (str "<script type=\"application/ld+json\">" json "</script>"))

;; ---- reg-head ---------------------------------------------------------------

(deftest reg-head-accepts-metadata-arity
  (is (= :head/with-meta
         (rf/reg-head :head/with-meta {:doc "Article-page head model"} (fn [_db _route] {:title "x"})))
      "reg-head returns its id (Conventions §reg-* return-value convention)")
  (let [m (rf.registrar/lookup :head :head/with-meta)]
    (is (= {:title "x"} ((:handler-fn m) {} nil))
        "the metadata argument does not displace the head-fn")
    ;; `:doc` is pure documentation: kept in dev, elided under the production
    ;; gate (Spec 001 §Production elision contract).
    (is (= (when rf.interop/debug-enabled? "Article-page head model") (:doc m)))))

;; ---- head-model selection ---------------------------------------------------

(deftest head-model-head-id-beats-the-routes-head
  (rf/reg-head :head/from-route (fn [_ _] {:title "route's"}))
  (rf/reg-head :head/from-opts  (fn [_ _] {:title "opts'"}))
  (rf/reg-route :route/declares-head {:head :head/from-route} "/declares")
  (is (= {:title "opts'"}
         (rf.ssr/head-model (frame-on-route! "override frame" :route/declares-head)
                            {:head-id :head/from-opts}))))

(deftest head-model-explicit-nil-route-differs-from-an-absent-route
  (testing "{:route nil} means \"no route\"; an ABSENT :route reads the frame's slice"
    (rf/reg-head :head/named (fn [_ _] {:title "named"}))
    (rf/reg-route :route/named {:head :head/named} "/named")
    (let [f (frame-on-route! "nil-route frame" :route/named)]
      (is (= {:title "named"} (rf.ssr/head-model f)))
      (is (= "nil-route frame" (:title (rf.ssr/head-model f {:route nil})))))))

(deftest head-model-raises-when-the-route-declares-an-unregistered-head
  (testing "a route that opts in to an unregistered head id is an error, not a
            silent default — only a route declaring no :head falls back"
    (rf/reg-route :route/dangling {:head :head/never-registered} "/dangling")
    (let [f (frame-on-route! "dangling frame" :route/dangling)]
      (is (= [:rf.error/no-such-head :head/never-registered]
             (try (rf.ssr/head-model f)
                  nil
                  (catch clojure.lang.ExceptionInfo e
                    ((juxt :rf.error/id :head-id) (ex-data e)))))))))

(deftest head-model-falls-back-to-default-when-route-omits-head
  (testing "Spec 011 §Default head: the frame :doc as :title, the viewport
            meta, and no charset (the shell's envelope owns that)"
    (rf/reg-route :route/no-head {:doc "Bare route"} "/")
    (is (= {:title "Default-head probe"
            :meta  [{:name "viewport" :content "width=device-width, initial-scale=1"}]}
           (rf.ssr/head-model (frame-on-route! "Default-head probe" :route/no-head))))))

;; ---- head-model->html -------------------------------------------------------

(deftest head-model->html-canonical-order
  (testing "title → meta → link → script → JSON-LD whatever the key order,
            each tag's attributes in declaration order, and the
            :html-attrs / :body-attrs bags left to the host shell"
    (is (= (str "<title>Hello</title>"
                "<meta name=\"description\" content=\"A summary\">"
                "<meta property=\"og:title\" content=\"T\">"
                "<link rel=\"canonical\" href=\"https://example.com/x\">"
                "<script src=\"/main.js\" async></script>"
                "<script src=\"/other.js\" defer type=\"module\"></script>"
                (ld-json "{\"@type\":\"Article\",\"headline\":\"Hello\"}"))
           (rf.ssr/head-model->html
             {:link       [{:rel "canonical" :href "https://example.com/x"}]
              :html-attrs {:lang "fr"}
              :title      "Hello"
              :meta       [{:name "description" :content "A summary"}
                           {:property "og:title" :content "T"}]
              :body-attrs {:class "page-x"}
              :script     [{:src "/main.js" :async true}
                           {:src "/other.js" :defer true :type "module"}]
              :json-ld    [{"@type" "Article" "headline" "Hello"}]})))))

(deftest head-model->html-json-ld-preserves-keyword-namespaces
  (testing "keyword keys AND keyword values serialise to their qualified name"
    (is (= (ld-json (str "{\"my.app/key\":\"value\",\"unqualified\":\"v2\","
                         "\"@type\":\"schema/Article\",\"my.app/headline\":\"my.app/hello\"}"))
           (rf.ssr/head-model->html
             {:json-ld [{:my.app/key      "value"
                         :unqualified     "v2"
                         "@type"          :schema/Article
                         :my.app/headline :my.app/hello}]})))))

(deftest head-model->html-json-ld-emits-json-valid-numbers
  (testing "a ratio is emitted as its double, never as `1/3`"
    (is (= (ld-json "{\"@type\":\"Rating\",\"ratingValue\":0.3333333333333333}")
           (rf.ssr/head-model->html {:json-ld [{"@type" "Rating" "ratingValue" 1/3}]}))))
  (testing "a non-finite double has no JSON form, so the emitter fails fast"
    (doseq [bad [##Inf ##-Inf ##NaN]]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #":rf\.error/invalid-json-ld-number"
            (rf.ssr/head-model->html {:json-ld [{"@type" "Rating" "ratingValue" bad}]}))
          (pr-str bad)))))

(deftest head-model->html-json-ld-escapes-control-chars
  (testing "every C0 control char is JSON-escaped (the five short forms, else
            \\u00XX) and printable content is untouched, as JSON.stringify does
            on the CLJS branch"
    (is (= (ld-json "{\"@type\":\"Article\",\"headline\":\"a\\n\\t\\r\\b\\f\\u0000\\u0001\\u001fHello, world!\"}")
           (rf.ssr/head-model->html
             {:json-ld [{"@type"    "Article"
                         "headline" (str "a" \newline \tab \return \backspace \formfeed
                                         (char 0x00) (char 0x01) (char 0x1f) "Hello, world!")}]})))))

(deftest head-model->html-json-ld-non-string-keys-are-quoted
  (testing "JSON object keys are quoted strings, as JSON.stringify coerces them"
    (doseq [[k quoted] [[1 "\"1\""] [true "\"true\""] [1/2 "\"0.5\""]]]
      (is (= (ld-json (str "{" quoted ":\"a\"}"))
             (rf.ssr/head-model->html {:json-ld [{k "a"}]}))
          (pr-str k))))
  (testing "a nil key has no JSON representation — fail fast"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #":rf\.error/invalid-json-ld-key"
          (rf.ssr/head-model->html {:json-ld [{nil "a"}]})))))

(deftest head-model->html-empty-model-and-wrap-opt
  (is (= "" (rf.ssr/head-model->html {})) "an empty model emits no orphan tags")
  (is (= "<head><title>Hi</title></head>" (rf.ssr/head-model->html {:title "Hi"} {:wrap? true}))))

(deftest head-model->html-attr-name-validation
  (testing "head tag attribute keys pass the HTML5 name grammar
            `[A-Za-z][A-Za-z0-9_:-]*`, so an `=`-injection key cannot smuggle
            an event-handler attribute"
    (doseq [k ["onclick=alert(1) data-x" "1bad" "bad attr"]]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #":rf\.error/ssr-invalid-attribute-name"
            (rf.ssr/head-model->html {:meta [{(keyword k) "v"}]}))
          k))))

(deftest head-model->html-escaping
  (is (= (str "<title>Hello &lt;script&gt;alert(1)&lt;/script&gt;</title>"
              "<meta name=\"x\" content=\"&quot;weird&quot;\">")
         (rf.ssr/head-model->html {:title "Hello <script>alert(1)</script>"
                                   :meta  [{:name "x" :content "\"weird\""}]}))))

;; ---- reg-head + reg-route + head-model + emission ---------------------------

(deftest head-emits-canonical-html-from-active-route
  (testing "the Spec 011 article flow: the route declares :head, the head fn
            derives the model from app-db and the route params"
    (rf/reg-event :seed-article
                  (fn [{:keys [db] rt :rf.db/runtime} _]
                    {:db            (assoc-in db [:articles "123"]
                                              {:title   "re-frame2 SSR"
                                               :summary "How re-frame2 ships SSR"
                                               :image   "https://example.com/og.png"})
                     :rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/routing :current]
                                              {:route-id :route/article :params {:id "123"}})}))
    (rf/reg-head :head/article
                 (fn [db {:keys [params]}]
                   (let [{:keys [title summary image]} (get-in db [:articles (:id params)])]
                     {:title (str "Article: " title " — Example")
                      :meta  [{:name "description" :content summary}
                              {:property "og:title" :content title}
                              {:property "og:image" :content image}]
                      :link  [{:rel  "canonical"
                               :href (str "https://example.com/articles/" (:id params))}]})))
    (rf/reg-route :route/article {:doc "Article page" :head :head/article} "/articles/:id")
    (let [f (rf.frame/make-anon-frame-record! {:doc "article frame" :platform :server})]
      (rf/dispatch-sync [:seed-article] {:frame f})
      (is (= (str "<title>Article: re-frame2 SSR — Example</title>"
                  "<meta name=\"description\" content=\"How re-frame2 ships SSR\">"
                  "<meta property=\"og:title\" content=\"re-frame2 SSR\">"
                  "<meta property=\"og:image\" content=\"https://example.com/og.png\">"
                  "<link rel=\"canonical\" href=\"https://example.com/articles/123\">")
             (rf.ssr/head-model->html (rf.ssr/head-model f)))))))
