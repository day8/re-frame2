(ns re-frame.ssr.ring-streaming-test
  "`stream-handler` end to end (Spec 011 §Streaming SSR): the chunk order,
  redirects, failures before the head commits, the envelope opts and the hash
  channels."
  (:require [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.lifecycle :as rf.ssr.ring.lifecycle]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support])
  (:import [java.io InputStream]))

(defn- reset+reg-test-handlers
  [test-fn]
  (rf.ssr.ring.test-support/reset-runtime
    (fn []
      (rf/reg-event :rf.test.server/init
        {:platforms #{:server}}
        (fn [_ _]
          {:db {:articles [{:id "a" :title "Article A"}
                           {:id "b" :title "Article B"}]
                :comments [{:body "First!"} {:body "Nice"}]}}))
      (rf/reg-sub :articles (fn [db _] (:articles db)))
      (rf/reg-sub :comments (fn [db _] (:comments db)))
      (rf/reg-view ^{:rf/id :test/article-list} article-list-view []
        (into [:ul.articles]
              (for [{:keys [id title]} @(subscribe [:articles])]
                ^{:key id} [:li title])))
      (rf/reg-view ^{:rf/id :test/comments-section} comments-view []
        (into [:ul.comments]
              (for [{:keys [body]} @(subscribe [:comments])]
                [:li body])))
      (rf/reg-view ^{:rf/id :test/root} root-view []
        [:main
         [:h1 "News"]
         [(rf/view :test/article-list)]
         [:rf/suspense-boundary
          {:id :test/comments :fallback [:p "Loading comments…"]}
          [(rf/view :test/comments-section)]]
         [:footer "End"]])
      (test-fn))))

(use-fixtures :each reset+reg-test-handlers)

(def ^:private get-root {:uri "/" :request-method :get})

(defn- handler-opts [opts]
  (merge {:initial-events [[:rf.test.server/init]]
          :root-view      [(rf/view :test/root)]
          :payload        :rf.ssr.payload/whole-app-db}
         opts))

(defn- stream
  "GET `/` through a `stream-handler` built from `opts` over the defaults; a
  streamed body comes back drained to a string."
  [opts]
  (let [response ((rf.ssr.ring/stream-handler (handler-opts opts)) get-root)]
    (cond-> response
      (instance? InputStream (:body response))
      (update :body #(with-open [^InputStream is %] (slurp is))))))

(defn- final-payload [body]
  (some-> (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>" body)
          second
          clojure.edn/read-string))

(defn- wire-render-hash [body]
  (second (re-find #"<div id=\"app\" data-rf-render-hash=\"([0-9a-f]{8})\">" body)))

(defn- wire-head-hash [body]
  (second (re-find #"data-rf-head-hash=\"([0-9a-f]{8})\"" body)))

(def ^:private redirect-shape
  (juxt :status #(get-in % [:headers "Location"]) :body))

(deftest stream-handler-emits-shell-then-resolved-then-payload
  (testing "shell, the app-root close, resolved templates, the final payload,
            the document close — so no protocol chunk lands inside #app.
            `:test/root` renders no <div>, so the first </div> is the root's"
    (let [{:keys [status body]} (stream {})
          at                    #(str/index-of body %)]
      (is (= 200 status))
      (is (< (at "<!DOCTYPE html>")
             (at "<div id=\"app\"")
             (at "<h1>News</h1>")
             (at "data-rf2-suspense-fallback=\"1\"")
             (at "</div>")
             (at "data-rf2-suspense-resolved=\"1\"")
             (at "First!")
             (at "__rf_payload")
             (at "</body></html>"))))))

(deftest stream-handler-drains-boundaries-FIFO-and-carries-every-failure
  (testing "siblings drain in registration order, a boundary registered while
            another renders drains from the tail, a throwing continuation ships
            the failed marker, and the payload names exactly the failed ids"
    (rf/reg-view ^{:rf/id :test/throwing} throwing []
      (throw (ex-info "continuation broke" {})))
    (rf/reg-view ^{:rf/id :test/fifo-root} fifo-root []
      [:main
       [:rf/suspense-boundary {:id :test/outer :fallback [:p "outer loading"]}
        [:section
         [:p "outer"]
         [:rf/suspense-boundary {:id :test/inner-bad :fallback [:p "inner loading"]}
          [(rf/view :test/throwing)]]]]
       [:rf/suspense-boundary {:id :test/good :fallback [:p "good loading"]}
        [:p "GOOD"]]
       [:rf/suspense-boundary {:id :test/bad :fallback [:p "bad loading"]}
        [(rf/view :test/throwing)]]])
    (let [{:keys [body]} (stream {:root-view [(rf/view :test/fifo-root)]})]
      (is (= [["test/outer" false] ["test/good" false]
              ["test/bad" true] ["test/inner-bad" true]]
             (mapv (fn [[_ id failed]] [id (some? failed)])
                   (re-seq #"data-rf2-suspense-id=\":([^\"]+)\" data-rf2-suspense-resolved=\"1\"( data-rf2-suspense-failed=\"1\")?"
                           body))))
      (is (= #{:test/inner-bad :test/bad}
             (get-in (final-payload body)
                     [:rf/runtime-db :rf.runtime/ssr :streaming :failed-boundaries]))))))

(deftest stream-handler-skips-empty-delta-script-on-unchanged-boundary
  (testing "a continuation that changes app-db ships its hydration-delta script;
            one that only reads ships none, not an inert {} chunk"
    (rf/reg-event :rf.test/bump-counter
      {:platforms #{:server}}
      (fn [{:keys [db]} _] {:db (update db :counter (fnil inc 0))}))
    (rf/reg-view ^{:rf/id :test/mutating-section} mutating-section []
      (rf/dispatch-sync [:rf.test/bump-counter])
      [:div "mutated"])
    (rf/reg-view ^{:rf/id :test/delta-root} delta-root []
      [:main
       [:rf/suspense-boundary {:id :test/changed :fallback [:p "loading"]}
        [(rf/view :test/mutating-section)]]
       [:rf/suspense-boundary {:id :test/unchanged :fallback [:p "loading"]}
        [(rf/view :test/article-list)]]])
    (let [{:keys [body]} (stream {:root-view [(rf/view :test/delta-root)]})]
      (is (str/includes? body "Article A") "control: the read-only boundary resolved")
      (is (str/includes? body "data-rf2-suspense-hydrate=\":test/changed\""))
      (is (not (str/includes? body "data-rf2-suspense-hydrate=\":test/unchanged\""))))))

(deftest stream-handler-redirect-destroys-frame
  (testing "a drain-time redirect ships a bodiless Location response and
            destroys the request frame inline, by the make-frame value, so a
            same-id successor survives"
    (rf/reg-event :rf.test.stream/redirect
      {:platforms #{:server}}
      (fn [_ _] {:fx [[:rf.server/redirect {:status 302 :location "/login"}]]}))
    (let [real-destroy rf/destroy-frame!
          target       (atom nil)
          before       (rf.frame/frame-ids)
          response     (with-redefs [rf/destroy-frame!
                                     (fn [t & more]
                                       (compare-and-set! target nil t)
                                       (apply real-destroy t more))]
                         (stream {:initial-events [[:rf.test.stream/redirect]]}))]
      (is (= [302 "/login" ""] (redirect-shape response)))
      (is (= before (rf.frame/frame-ids)))
      (is (some? (rf.frame/frame-value-incarnation-token @target))))))

(defn- seed-route-with-html-attrs!
  "Make `:test.stream/attr-route`, whose head model carries `html-attrs`, the
  route `attr-route-opts` renders."
  [html-attrs]
  (rf/reg-head :test.stream/attr-head
               (fn [_db _route] {:title "T" :html-attrs html-attrs}))
  (rf/reg-route :test.stream/attr-route
                {:doc  "Route whose head model carries html-attrs"
                 :head :test.stream/attr-head} "/attrs")
  (rf/reg-event :rf.test.stream/seed-attr-route
    {:platforms #{:server}}
    (fn [{rt :rf.db/runtime} _]
      {:rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/routing :current]
                                {:route-id :test.stream/attr-route})}))
  (rf/reg-view ^{:rf/id :test/attr-body} attr-body []
    [:main [:h1 "attr body"]]))

(defn- attr-route-opts []
  {:initial-events [[:rf.test.stream/seed-attr-route]]
   :root-view      [(rf/view :test/attr-body)]})

(deftest stream-handler-shell-render-throw-fails-closed-before-commit
  (testing "a throw while resolving the root view, walking the shell or
            rendering the document prefix is the projected 500 on the request
            thread, with the frame torn down inline — never a committed 200"
    (rf/reg-view ^{:rf/id :test/throwing} throwing []
      (throw (ex-info "shell walk broke" {})))
    ;; `attr-string` refuses this attribute name.
    (seed-route-with-html-attrs! {:data-user.id "42"})
    (doseq [[label opts] [["root view" {:root-view (fn [] (throw (ex-info "root broke" {})))}]
                          ["shell walk" {:root-view [:main [:h1 "Header"] [(rf/view :test/throwing)]]}]
                          ["prefix" (attr-route-opts)]]]
      (let [before                (rf.frame/frame-ids)
            {:keys [status body]} (stream opts)]
        (is (= 500 status) label)
        (is (str/includes? body "Something went wrong") label)
        (is (= before (rf.frame/frame-ids)) label)))
    (is (= 500 (:status ((rf.ssr.ring/ssr-handler (handler-opts (attr-route-opts))) get-root)))
        "ssr-handler answers the refused prefix the same way")))

(deftest stream-handler-valid-head-model-attrs-stream-a-200
  (testing "control: a valid attribute name streams a 200 whose <html> carries it"
    (seed-route-with-html-attrs! {:data-user-id "42"})
    (let [{:keys [status body]} (stream (attr-route-opts))]
      (is (= 200 status))
      (is (str/includes? body "data-user-id=\"42\""))
      (is (str/ends-with? body "</body></html>")))))

(deftest stream-handler-post-shell-redirect-bodiless
  (testing "a redirect at the post-shell re-read (stubbed: the second
            flush-response-result!) ships a bodiless Location response, not a
            streamed body, and destroys the frame inline"
    (let [real-flush rf.ssr/flush-response-result!
          calls      (atom 0)
          before     (rf.frame/frame-ids)
          response   (with-redefs [rf.ssr/flush-response-result!
                                   (fn [fid]
                                     (if (= 2 (swap! calls inc))
                                       {:response {:status   302
                                                   :headers  []
                                                   :cookies  []
                                                   :redirect {:status 302 :location "/post-shell-login"}}}
                                       (real-flush fid)))]
                       (stream {}))]
      (is (= [302 "/post-shell-login" ""] (redirect-shape response)))
      (is (= before (rf.frame/frame-ids))))))

(deftest stream-handler-honors-custom-content-type-opt
  (testing "a :content-type opt replaces the default on the streamed head, as
            the one content-type key"
    (let [{:keys [headers]} (stream {:content-type "application/xhtml+xml; charset=utf-8"})]
      (is (= ["application/xhtml+xml; charset=utf-8"]
             (keep (fn [[k v]] (when (= "content-type" (str/lower-case k)) v)) headers))))))

(deftest stream-handler-refuses-html-shell-at-construction
  (testing "a one-piece :html-shell cannot run once streaming starts, so
            construction refuses it rather than dropping it"
    (let [shell (fn [body-html payload-edn _opts] (str body-html payload-edn))
          data  (try (rf.ssr.ring/stream-handler (handler-opts {:html-shell shell}))
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= {:rf.error/id :rf.error/ssr-streaming-unsupported-opt
              :opt-key     :html-shell
              :got         shell}
             (select-keys data [:rf.error/id :opt-key :got])))))
  (testing "an explicit nil :html-shell constructs"
    (is (fn? (rf.ssr.ring/stream-handler (handler-opts {:html-shell nil}))))))

(deftest stream-handler-render-hash-is-body-only-and-head-hash-tracks-the-head
  (testing "two route heads and an explicit :head string over one body share
            the payload :rf/render-hash; :rf/head-hash differs per head model
            and is omitted for a :head string; the #app and <head> markers
            equal the payload's values"
    (rf/reg-head :test.stream/head-a (fn [_db _route] {:title "Stream head A"}))
    (rf/reg-head :test.stream/head-b (fn [_db _route] {:title "Stream head B"}))
    (rf/reg-route :test.stream/route-a {:doc "A" :head :test.stream/head-a} "/")
    (rf/reg-route :test.stream/route-b {:doc "B" :head :test.stream/head-b} "/")
    (rf/reg-event :test.stream/seed-route
      {:platforms #{:server}}
      (fn [{rt :rf.db/runtime} [_ route-id]]
        {:rf.db/runtime (assoc-in (or rt {}) [:rf.runtime/routing :current]
                                  {:route-id route-id})}))
    (let [;; Resolving root form: an unresolved root carries no hash at all.
          root     (fn [] ((rf/view :test/root)))
          routed   #(:body (stream {:initial-events [[:rf.test.server/init]
                                                     [:test.stream/seed-route %]]
                                    :root-view      root}))
          body-a   (routed :test.stream/route-a)
          [a b s]  (map final-payload
                        [body-a
                         (routed :test.stream/route-b)
                         (:body (stream {:root-view root :head "<title>Alpha</title>"}))])]
      (is (some? (:rf/render-hash a)))
      (is (apply = (map :rf/render-hash [a b s])))
      (is (every? some? (map :rf/head-hash [a b])))
      (is (apply not= (map :rf/head-hash [a b])))
      (is (nil? (:rf/head-hash s)))
      (is (= [(:rf/render-hash a) (:rf/head-hash a)]
             [(wire-render-hash body-a) (wire-head-hash body-a)])))))

(deftest stream-handler-emit-hash-false-omits-root-marker
  (testing ":emit-hash? false drops both wire markers; the payload keeps both
            hashes"
    (let [{:keys [body]} (stream {:root-view  (fn [] ((rf/view :test/root)))
                                  :emit-hash? false})]
      (is (not (re-find #"data-rf-(render|head)-hash" body)))
      (is (every? some? ((juxt :rf/render-hash :rf/head-hash) (final-payload body)))))))

(deftest stream-handler-final-hash-reflects-post-drain-state
  (testing "when a continuation changes a key the root reads, the payload's
            :rf/render-hash is the hash a client computes re-rendering the root
            over the shipped post-drain app-db"
    (rf/reg-event :rf.test/bump-counter
      {:platforms #{:server}}
      (fn [{:keys [db]} _] {:db (update db :counter (fnil inc 0))}))
    (rf/reg-event :rf.test/set-db {:platforms #{:server}} (fn [_ [_ db]] {:db db}))
    (rf/reg-sub :counter (fn [db _] (:counter db)))
    (rf/reg-view ^{:rf/id :test/counter-mutator} counter-mutator []
      (rf/dispatch-sync [:rf.test/bump-counter])
      [:div "bumped"])
    ;; The root reads :counter inline: the structural hash does not expand view refs.
    (rf/reg-view ^{:rf/id :test/counter-root} counter-root []
      [:main
       [:span (str "counter=" @(subscribe [:counter]))]
       [:rf/suspense-boundary {:id :test/bump :fallback [:p "loading"]}
        [(rf/view :test/counter-mutator)]]])
    (let [payload (final-payload
                    (:body (stream {:initial-events [[:rf.test/set-db {:counter 0}]]
                                    :root-view      (fn [] ((rf/view :test/counter-root)))})))
          db      (:rf/app-db payload)
          client  (keyword "rf.frame" (str (gensym "client")))]
      (is (= 1 (:counter db)) "the payload ships the post-drain app-db")
      (rf/make-frame {:id client :platform :server})
      (try
        (rf/with-frame client
          (rf/dispatch-sync [:rf.test/set-db db])
          (is (= (:rf/render-hash payload)
                 (rf.ssr.ring.lifecycle/render-document-hash ((rf/view :test/counter-root))))))
        (finally
          (rf/destroy-frame! client))))))

(deftest stream-handler-fn-root-view-non-idempotent-hashes-consistently-when-no-continuations
  (testing "with no continuation a fn :root-view runs once, so even a
            non-idempotent root stamps a marker equal to the payload hash"
    (rf/reg-view* :pages/stream-noni (fn [n] [:div {:data-call n} "noni"]))
    (let [calls          (atom 0)
          {:keys [body]} (stream {:root-view (fn [] ((rf/view :pages/stream-noni) (swap! calls inc)))})]
      (is (= 1 @calls))
      (is (some? (wire-render-hash body)))
      (is (= (wire-render-hash body) (:rf/render-hash (final-payload body)))))))
