(ns re-frame.ssr-skill-form-action-test
  "Execute the authoring skill's FormAction recipe through the router and JVM
  reader. Calling only its handler misses event-schema rejection before the
  handler; reading only the input names misses browser-only view code."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [malli.error :as me]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private skill-page
  (-> (io/resource "re_frame/ssr_skill_form_action_test.clj") io/file
      .getParentFile .getParentFile .getParentFile .getParentFile .getParentFile
      (io/file "skills/re-frame2/patterns/form-action.md")))

(defn- recipe-forms [needle]
  (let [page (str/replace (slurp skill-page) "\r\n" "\n")
        hits (filter #(str/includes? % needle)
                     (map second (re-seq #"(?s)```clojure\n(.*?)```" page)))]
    (assert (= 1 (count hits)) (str "Expected one recipe fence: " needle))
    (read-string {:read-cond :allow :features #{:clj}}
                 (str "[" (first hits) "]"))))

;; App-owned helpers the recipe explicitly asks the consumer to supply.
(defn write-form-errors [db path errors]
  (-> db (assoc-in (conj path :errors) errors)
      (assoc-in (conj path :status) :error)
      (assoc-in (conj path :submit-attempted?) true)))

(defn explain->errors [explanation]
  (me/humanize explanation))

(defn- eval-recipe [form]
  (binding [*ns* (the-ns 're-frame.ssr-skill-form-action-test)] (eval form)))

(defn- install-action! []
  (doseq [form (recipe-forms "(def AddToCartFields")
          :when (= 'def (first form))]
    (eval-recipe form))
  (eval-recipe (first (recipe-forms "rf/reg-event :cart/add-item")))
  ;; The fixture loads the real server-request provider; the app owns its session.
  (rf/reg-cofx :app.csrf/active-token {:platforms #{:server}}
    (fn [] "tok-abc")))

(defn- dispatch-action [payload]
  (let [effects (atom [])]
    (rf/reg-event :form-action-test/init
      (fn [_ _] {:db {:cart {:add-form {:draft {}}}}}))
    (rf/reg-fx :form-action-test/status
      (fn [_ status] (swap! effects conj [:status status])))
    (rf/reg-fx :form-action-test/redirect
      (fn [_ target] (swap! effects conj [:redirect target])))
    (rf/with-new-frame [frame (rf/make-frame
                               {:platform :server
                                :initial-events [[:form-action-test/init]]
                                :fx-overrides
                                {:rf.server/set-status :form-action-test/status
                                 :rf.server/redirect :form-action-test/redirect}})]
      (eval-recipe '(rf/reg-app-schema [:cart :add-form :draft] AddToCartDraft))
      (rf/dispatch-sync [:cart/add-item payload] {:frame frame})
      {:db (rf/app-db-value frame) :fx @effects})))

(deftest invalid-fields-reach-the-custom-error-arm-through-dispatch
  (install-action!)
  (doseq [debug? [true false]
          quantity [0 "abc"]]
    (testing (str "debug=" debug? ", quantity=" (pr-str quantity))
      (with-redefs [rf.interop/debug-enabled? debug?]
        (let [{:keys [db fx]} (dispatch-action
                               {:item-id "sku-1" :quantity quantity
                                :csrf-token "tok-abc"})]
          (is (= [[:status 400]] fx))
          (is (= {:item-id "sku-1" :quantity quantity}
                 (get-in db [:cart :add-form :draft])))
          (is (seq (get-in db [:cart :add-form :errors :quantity])))
          (is (nil? (get-in db [:cart :items]))))))))

(deftest valid-fields-still-reach-the-redirect-arm
  (install-action!)
  (let [{:keys [db fx]} (dispatch-action
                         {:item-id "sku-1" :quantity 2 :csrf-token "tok-abc"})]
    (is (= [[:redirect {:status 303 :location "/cart"}]] fx))
    (is (= [{:item-id "sku-1" :quantity 2}] (get-in db [:cart :items])))))

(deftest malformed-token-reaches-the-csrf-arm-before-field-validation
  (install-action!)
  (let [{:keys [db fx]} (dispatch-action
                         {:item-id "sku-1" :quantity 0 :csrf-token ""})]
    (is (= [[:status 403]] fx))
    (is (seq (get-in db [:cart :add-form :errors :_form])))
    (is (nil? (get-in db [:cart :items])))))

(deftest shared-view-compiles-on-jvm-and-preserves-native-post
  (rf/reg-sub :form.cart-add/draft (fn [_ _] {:quantity 2}))
  (rf/reg-sub :form.cart-add/field-error (fn [_ _] nil))
  (rf/reg-sub :app.csrf/token (fn [_ _] "tok-abc"))
  (eval-recipe (first (recipe-forms "rf/reg-view add-to-cart-form")))
  (let [view @(ns-resolve 're-frame.ssr-skill-form-action-test 'add-to-cart-form)
        [_ attrs & children] (view "sku-1")
        inputs (filter #(and (vector? %) (= :input (first %))) children)]
    (is (= "POST" (:method attrs)))
    (is (= "/cart/add" (:action attrs)))
    (is (nil? (:on-submit attrs)))
    (is (= #{"csrf-token" "item-id" "quantity"}
           (set (map #(get-in % [1 :name]) inputs))))
    (is (every? #(nil? (get-in % [1 :on-change])) inputs))))
