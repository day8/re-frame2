(ns re-frame.ssr-doc-example-projector-test
  "The `reg-error-projector` example in `docs/api/re-frame.ssr.md` is the
  projector a reader ships, so it is read out of the page and driven: it
  must return the four locked keys (or `project-error` replaces every answer
  with the generic 500), and its 404 arm must fire on the category that
  reports an unroutable URL in production — `:rf.error/no-such-handler` with
  `:kind :route` — and on nothing else. The page's `project-error` `;; =>`
  result comment is held to the runtime too."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

;; The fixture leaves the always-on listener registry alone: the façade's
;; projection listener there is the production status-projection path.
(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private api-page
  "Anchored to this file's classpath resource (five parents up is the repo root)."
  (let [res (io/resource "re_frame/ssr_doc_example_projector_test.clj")]
    (assert res "the ssr test/ dir must be on the classpath to find the API page")
    (-> (io/file res)
        .getParentFile .getParentFile .getParentFile .getParentFile .getParentFile
        (io/file "docs" "api" "re-frame.ssr.md")
        .getCanonicalFile)))

(defn- fence
  "The one ```clojure fence on the page containing `needle`; hard-errors on
  zero or many. CRLF is normalised for a Windows checkout."
  [needle]
  (let [md   (str/replace (slurp api-page) "\r\n" "\n")
        hits (->> (re-seq #"(?s)```clojure\n(.*?)```" md)
                  (map second)
                  (filter #(str/includes? % needle)))]
    (assert (= 1 (count hits))
            (str "expected EXACTLY ONE ```clojure fence in " api-page
                 " containing " (pr-str needle) ", found " (count hits)))
    (first hits)))

(def ^:private documented-projector-fn
  "The `(fn [trace-event] …)` of the page's `reg-error-projector` example."
  (delay
    (binding [*ns* (find-ns 're-frame.ssr-doc-example-projector-test)]
      (eval (last (read-string (fence "rf/reg-error-projector :app/public-error")))))))

(defn- server-frame-using-the-documented-projector []
  (rf/reg-route :route/home {} "/")
  (rf/reg-route :rf.route/not-found {} "/not-found")
  (rf/reg-view* :pages/not-found (fn [] [:main.not-found [:h1 "No such page"]]))
  (rf/reg-error-projector :app/public-error @documented-projector-fn)
  (rf.frame/make-anon-frame-record!
    {:platform :server
     :ssr      {:public-error-id   :app/public-error
                :dev-error-detail? false}}))

(deftest the-documented-example-answers-an-unroutable-url-with-its-own-404
  (testing "the example's own 404 and message reach the wire, so its return
            passed the closed four-key check"
    (let [f (server-frame-using-the-documented-projector)]
      (rf/dispatch-sync [:rf.route/handle-url-change "/no-such-page"] {:frame f})
      (let [{:keys [response public-error]} (rf.ssr/flush-response-result! f)]
        (is (= [404 :not-found "We couldn't find that page." rf.ssr/public-error-keys]
               [(:status response) (:code public-error) (:message public-error)
                (set (keys public-error))]))))))

(deftest the-documented-example-gates-its-404-on-kind-route
  (testing "an unregistered EVENT is a server defect, and every non-route
            `:kind` takes the 500"
    (let [f (server-frame-using-the-documented-projector)]
      (rf/dispatch-sync [:never/registered] {:frame f})
      (is (= [500 500 500]
             [(:status (rf.ssr/flush-response! f))
              (:status (@documented-projector-fn {:operation :rf.error/no-such-handler
                                                  :tags      {:kind :frame}}))
              (:status (@documented-projector-fn {:operation :rf.error/no-such-handler
                                                  :tags      {}}))])
          "[unregistered event, :kind :frame, no :kind]"))))

(deftest the-project-error-example-result-comment-matches-the-runtime
  (is (= (rf.ssr/default-error-projector-fn {:operation :rf.error/no-such-handler
                                             :tags      {:kind :route}})
         (-> (fence "ssr/project-error :rf/default trace-event")
             (->> (re-find #"(?m)^\s*;;\s*=>\s*(\{.*\})\s*$"))
             second
             edn/read-string))))
