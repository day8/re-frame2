(ns re-frame.ssr.ring.custom-shell-payload-tag-test
  "A custom `:html-shell` is handed the hydration payload `<script>` already
  built, as `:payload-tag` in its opts (Spec 011 §Trusted shell hook
  contract). Splicing that one string is all a shell does to ship the
  payload: the id the client bootstrap reads and the EDN-aware script-body
  escaping are already in it. The default shell splices the same part, so
  its bytes are pinned here too."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.html-helpers :as rf.ssr.html-helpers]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(def ^:private hostile-title
  "A string that would close a hand-written payload `<script>` and open a
  second one."
  "</script><script>alert(1)</script>")

(defn- splicing-shell
  "A custom shell that ships the payload by splicing `:payload-tag` and
  nothing else: it never reads `payload-edn`. `seen` collects the opts."
  [seen]
  (fn [body-html _payload-edn {:keys [payload-tag] :as opts}]
    (swap! seen conj opts)
    (str "<!DOCTYPE html><html><head><title>Custom</title></head><body>"
         "<main id=\"app\">" body-html "</main>"
         payload-tag
         "</body></html>")))

(defn- read-payload [body]
  (some->> body
           (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>")
           second
           edn/read-string))

(defn- serve-with-shell [shell]
  (rf/reg-event :shell/init {:platforms #{:server}}
    (fn [_ _] {:db {:articles ["Alpha" "Beta"] :title hostile-title}}))
  (rf/reg-view* :shell/root (fn [] [:p "page"]))
  (:body ((rf.ssr.ring/ssr-handler {:frame      {:initial-events [[:shell/init]]}
                                    :root-view  [(rf/view :shell/root)]
                                    :payload    [:articles :title]
                                    :html-shell shell})
          {:request-method :get :uri "/"})))

(deftest a-custom-shell-that-splices-the-payload-tag-hydrates
  (let [seen    (atom [])
        body    (serve-with-shell (splicing-shell seen))
        payload (read-payload body)]
    (testing "the tag carries the payload under the id the client reads"
      (is (some? payload) body))
    (testing "a client frame hydrated from it holds exactly the shipped slice"
      (rf/make-frame {:id :shell/client :platform :client})
      (is (= payload (rf.ssr/hydrate! {:frame :shell/client :payload payload})))
      (is (= {:articles ["Alpha" "Beta"] :title hostile-title}
             (rf/app-db-value :shell/client))))
    (testing "`</script>` inside a string cannot close the element"
      (is (not (str/includes? body hostile-title)))
      (is (= 1 (count (re-seq #"<script" body)))
          "the page holds the payload script and no other"))))

(deftest the-payload-tag-is-the-shared-helper-output
  (let [seen (atom [])]
    (serve-with-shell
      (fn [body-html payload-edn opts]
        ((splicing-shell seen) body-html payload-edn opts)
        (is (= (rf.ssr.html-helpers/payload-script-tag payload-edn)
               (:payload-tag opts)))
        ""))
    (is (= 1 (count @seen)) "the shell ran once")))

(def ^:private pinned-default-shell
  "`default-html-shell`'s bytes for the input in the test below, captured
  from the shell before `:payload-tag` existed."
  (str "<!DOCTYPE html><html lang=\"fr\" data-theme=\"dark\"><head><meta charset=\"utf-8\">"
       "<title>T</title></head><body class=\"page\"><div id=\"root\"><main>Hi</main></div>"
       "<script id=\"__rf_payload\" type=\"application/edn\">"
       "#:rf{:app-db {:t \"\\u003c/script>\", :k :a<b}}</script>"
       "<script src=\"/app.js\"></script><script>window.x=1</script></body></html>"))

(deftest the-default-shell-bytes-are-unchanged
  (let [payload-edn (pr-str {:rf/app-db {:t "</script>" :k :a<b}})
        opts        {:head           "<title>T</title>"
                     :html-attrs     {:lang "fr" :data-theme "dark"}
                     :body-attrs     {:class "page"}
                     :app-element-id "root"
                     :script-src     "/app.js"
                     :body-end       "<script>window.x=1</script>"}]
    (testing "called directly, with no `:payload-tag`"
      (is (= pinned-default-shell
             (rf.ssr.ring/default-html-shell "<main>Hi</main>" payload-edn opts))))
    (testing "called as the handler calls it, with `:payload-tag` in opts"
      (is (= pinned-default-shell
             (rf.ssr.ring/default-html-shell
               "<main>Hi</main>" payload-edn
               (assoc opts :payload-tag
                      (rf.ssr.html-helpers/payload-script-tag payload-edn))))))))
