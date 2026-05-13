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
            [re-frame.frame :as frame]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.head :as head]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (reset! head/head-snapshots {})
  (reset! ssr/request-slots {})
  ;; The defonce-backed `pending-error-traces` atom in re-frame.ssr is
  ;; private; reach it reflectively. Resetting between tests prevents
  ;; the load-test downstream from inheriting stale entries left by
  ;; earlier suites — the ssr-head tests themselves don't drive any
  ;; error-projection path, but the defonce atom is process-wide.
  (when-let [v (resolve 're-frame.ssr/pending-error-traces)]
    (reset! @v {}))
  (rf/init! ssr/adapter)
  ;; Resurrect ns-load-time registrations after clear-all!.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.ssr.head :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

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

(deftest head-model->html-empty-model
  (testing "an empty / minimal model emits nothing (no orphan tags)"
    (is (= "" (rf/head-model->html {})))
    (is (= "" (rf/head-model->html nil)))
    (is (= "<head></head>"
           (rf/head-model->html {} {:wrap? true})))))

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
      (rf/destroy-frame f)
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
      (rf/destroy-frame f1)
      (is (= {} (head/head-snapshot f1)))
      (is (seq (head/head-snapshot f2))
          "destroying f1 did not clear f2's bookkeeping"))))

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
