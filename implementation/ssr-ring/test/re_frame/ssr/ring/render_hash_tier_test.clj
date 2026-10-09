(ns re-frame.ssr.ring.render-hash-tier-test
  "The server end of Spec 011's tier rule for the render-hash channel. A root
  that stays a callable-headed vector (the unresolved root form, the only
  shape an adoption-tier root can take) hashes to one constant per arity, so
  it ships no `:rf/render-hash` and no `data-rf-render-hash`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.lifecycle :as rf.ssr.ring.lifecycle]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(defn- register-app! []
  (rf/reg-event :rf.test.q1b96/init
    {:platforms #{:server}}
    (fn [_ _] {:db {:heading "Tier"}}))
  (rf/reg-sub :q1b96/heading (fn [db _] (:heading db)))
  (rf/reg-view* :q1b96/root
    (fn [] [:main.page [:h1 (rf/subscribe-once [:q1b96/heading])] [:p "body"]])))

(defn- unresolved-opts []
  {:initial-events [[:rf.test.q1b96/init]]
   :root-view      [(rf/view :q1b96/root)]
   :payload        :rf.ssr.payload/whole-app-db})

(defn- a-component [_props] [:div "a"])
(defn- b-component [_props] [:section [:h2 "b"] [:p "different shape entirely"]])

(deftest unresolved-root-form-recognises-callable-heads
  ;; A Var head is `ifn?` but not `fn?`; a keyword head is `ifn?` but a DOM element.
  (register-app!)
  (is (= [true true false false false]
         (mapv rf.ssr.ring.lifecycle/unresolved-root-form?
               [[(rf/view :q1b96/root)]
                [#'a-component]
                [:div.page [:h1 "x"]]
                (list [:div "x"])
                []]))))

(deftest the-hash-an-unresolved-root-would-have-had-is-a-constant
  ;; Holds the table in `render-document-hash`'s docstring to the code.
  (register-app!)
  (rf/reg-view* :q1b96/other (fn [] [:aside "nothing like the other one"]))
  (is (= ["f1d63f7e" "f1d63f7e" "83b865f8" "83b865f8"]
         (mapv rf.ssr/render-tree-hash
               [[(rf/view :q1b96/root)]
                [(rf/view :q1b96/other)]
                [a-component {}]
                [b-component {}]]))))

(deftest an-unresolved-root-view-ships-no-render-hash
  (register-app!)
  (let [body (:body ((rf.ssr.ring/ssr-handler (unresolved-opts))
                     {:uri "/" :request-method :get}))]
    (is (str/includes? body "<h1>Tier</h1>"))
    (is (not (str/includes? body "render-hash")))))

(deftest the-surviving-hash-matches-the-documented-client-tree
  ;; The resolving root hashes what the client's `:render-tree-fn
  ;; #((rf/view :id))` hashes, and both `:root-view` spellings emit the same
  ;; HTML, as the `ssr-handler` docstring says.
  (register-app!)
  (rf/make-frame {:id             :q1b96/server
                  :platform       :server
                  :initial-events [[:rf.test.q1b96/init]]})
  (rf/with-frame :q1b96/server
    (let [resolved (rf.ssr.ring.lifecycle/resolve-root-view
                     (fn [] ((rf/view :q1b96/root))))]
      (is (= (rf.ssr/render-tree-hash ((rf/view :q1b96/root)))
             (rf.ssr.ring.lifecycle/render-document-hash resolved)))
      (is (= (rf.ssr/render-to-string resolved {})
             (rf.ssr/render-to-string
               (rf.ssr.ring.lifecycle/resolve-root-view [(rf/view :q1b96/root)])
               {}))))))

(deftest streaming-unresolved-root-view-ships-no-render-hash
  ;; The streaming prefix stamps from `:render-hash` alone: a separate call site.
  (register-app!)
  (let [body (with-open [in ^java.io.InputStream
                            (:body ((rf.ssr.ring/stream-handler (unresolved-opts))
                                    {:uri "/" :request-method :get}))]
               (slurp in))]
    (is (str/includes? body "<h1>Tier</h1>"))
    (is (not (str/includes? body "render-hash")))))
