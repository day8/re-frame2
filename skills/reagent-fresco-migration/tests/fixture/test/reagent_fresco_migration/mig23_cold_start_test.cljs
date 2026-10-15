(ns reagent-fresco-migration.mig23-cold-start-test
  "Cold-start phases stay ordered: never-installed state exists only once per process."
  (:require [cljs.test :refer [deftest testing is]]
            ["react-dom/server" :as react-server]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.ssr :as rf.ssr]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.server :as rf.fresco.server]))

(rf.fresco/defview page [_props]
  [:div "mig23 cold-start evidence"])

(def ^:private render-opts
  {:hiccup            [page {}]
   :payload           [:catalog/items]
   :snapshot          {:catalog/items ["a" "b"]}
   :client-frame-id   :app/main
   :identifier-prefix "main"})

(def ^:private bridge-props
  {:status :ready :item {:title "Task"} :on-select [:task/select 7]})

(rf.fresco/defview bridged-card [{:keys [status item on-select]}]
  [:p (str (keyword? status) "/" (get item :title "missing") "/"
           (= [:task/select 7] on-select))])

(def ^:private card-component (rf.fresco/as-component bridged-card))

(defn- reagent-parent [{:keys [mode]}]
  [:section
   (case mode
     "converted" [:> card-component bridge-props]
     "element" (rf.fresco/as-element [bridged-card bridge-props])
     "raw" (r/create-element card-component
                             #js {"status" (:status bridge-props)
                                  "item" (:item bridge-props)
                                  "onSelect" (:on-select bridge-props)}))])

(rf.fresco/defhost reagent-parent-host (r/reactify-component reagent-parent)
  {:server :render})

(def ^:private kept-island (atom nil))

(rf.fresco/defview island-parent [_props]
  [:section (reset! kept-island
                    (r/as-element [:ul (for [t ["a" "b"]] ^{:key t} [:li t])]))])

(def ^:private crossing-callback (atom nil))
(def ^:private received-callback (atom nil))

(rf.fresco/defview callback-child [{:keys [on-pick]}]
  (reset! received-callback on-pick)
  [:p "child"])

(def ^:private callback-component (rf.fresco/as-component callback-child))

(defn- callback-parent [{:keys [mode]}]
  (let [on-pick @crossing-callback]
    [:section
     (case mode
       "element" (rf.fresco/as-element [callback-child {:on-pick on-pick}])
       "converted" [:> callback-component {:on-pick on-pick}])]))

(rf.fresco/defhost callback-parent-host (r/reactify-component callback-parent)
  {:server :render})

(defn- rf-error-id [f]
  (try [:no-throw (f)]
       (catch :default e (:rf.error/id (ex-data e)))))

(deftest mig23-cold-start-contract
  (testing "both recipe entry points require process boot"
    (is (= [nil :rf.error/no-adapter-installed :rf.error/no-adapter-installed nil]
           [(rf/current-adapter)
            (rf-error-id #(rf.fresco.server/render render-opts))
            (rf-error-id #(rf/make-frame {:id :app/main :platform :client}))
            (rf/current-adapter)])))

  (testing "one server adapter install serves two requests"
    (rf/init! rf.ssr/adapter)
    (let [installed (rf/current-adapter)
          r1 (rf.fresco.server/render render-opts)
          r2 (rf.fresco.server/render render-opts)]
      (is (= [:rf.adapter/ssr true true true true]
             [(:kind installed)
              (string? (:document r1))
              (map? (:payload r1))
              (string? (:document r2))
              (identical? installed (rf/current-adapter))]))))

  (testing "MIG-22 bridge doors preserve Clojure props"
    (let [render (fn [mode]
                   (:html (rf.fresco.server/render
                            (assoc render-opts :hiccup
                                   [reagent-parent-host {:mode mode}]))))]
      (doseq [[mode pattern]
              [["converted" #"^<section><p[^>]*>false/missing/false</p></section>$"]
               ["element" #"^<section><p[^>]*>true/Task/true</p></section>$"]]]
        (is (re-find pattern (render mode))))
      (is (= (render "element") (render "raw")))))

  (testing "a retained Reagent island keeps metadata keys"
    (reset! kept-island nil)
    (let [keys-of (fn [^js el]
                    (when el
                      (let [^js props (.-props el)]
                        (mapv (fn [^js child] (.-key child)) (array-seq (.-children props))))))
          html (:html (rf.fresco.server/render
                        (assoc render-opts :hiccup [island-parent {}])))]
      (is (re-find #"^<section[^>]*><ul><li>a</li><li>b</li></ul></section>$" html))
      (is (= [["a" "b"] [nil nil]]
             [(keys-of @kept-island)
              (keys-of (rf.fresco/as-element
                         [:ul (for [t ["a" "b"]] ^{:key t} [:li t])]))]))))

  (testing "a capture-frame callback crosses both doors by identity"
    (let [on-pick (:dispatch (rf/capture-frame :app/main))
          received (fn [mode]
                     (reset! received-callback nil)
                     (rf.fresco.server/render
                       (assoc render-opts :hiccup [callback-parent-host {:mode mode}]))
                     @received-callback)]
      (reset! crossing-callback on-pick)
      (is (= [true true true]
             [(fn? on-pick)
              (identical? on-pick (received "element"))
              (identical? on-pick (received "converted"))]))))

  (testing "the client keeps Reagent and boot remains idempotent"
    (rf/destroy-adapter!)
    (rf/init! rf.adapter.reagent/adapter)
    (let [installed (rf/current-adapter)]
      (rf/make-frame {:id :app/main :platform :client})
      (let [created? (contains? (rf/frame-ids) :app/main)]
        (rf/init! rf.adapter.reagent/adapter)
        (is (= [:rf.adapter/reagent true true]
               [(:kind installed) created?
                (identical? installed (rf/current-adapter))]))))))

(deftest mig14-preserve-empty-boolean-children
  (let [render #(react-server/renderToStaticMarkup %)]
    (doseq [[hidden? expected] [[true "<div></div>"]
                              [false "<div><span>Details</span></div>"]]]
      (let [donor [:div (or hidden? [:span "Details"])]
            original (render (r/as-element donor))]
        (when hidden?
          (is (= :rf.error/fresco-true-child
                 (rf-error-id #(rf.fresco/as-element donor)))))
        (is (= [expected expected]
               [original
                (render (rf.fresco/as-element
                          [:div (when-not hidden? [:span "Details"])]))]))))))

(deftest mig34-preserve-working-unsafe-html
  (let [html "<b>already trusted</b>"
        wrapped (r/unsafe-html html)
        render #(react-server/renderToStaticMarkup %)
        original (render (r/as-element [:div {:dangerouslySetInnerHTML wrapped}]))]
    (is (thrown-with-msg? js/Error #"__html"
          (render (rf.fresco/as-element [:div {:dangerouslySetInnerHTML wrapped}]))))
    (is (= ["<div><b>already trusted</b></div>"
            "<div><b>already trusted</b></div>"
            "<div></div>"]
           [original
            (render (rf.fresco/as-element [:div {:dangerouslySetInnerHTML {:__html html}}]))
            (render (r/as-element [:div {:dangerouslySetInnerHTML {:__html html}}]))]))))
