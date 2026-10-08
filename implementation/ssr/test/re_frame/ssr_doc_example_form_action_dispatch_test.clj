(ns re-frame.ssr-doc-example-form-action-dispatch-test
  "The `/cart/add` example in `spec/Pattern-FormAction.md`, driven THROUGH THE
  ROUTER and READ BY THE JVM — what `ssr-doc-example-form-action-test`'s direct
  handler calls cannot see: an event `:schema` strict enough to reject at the
  router before the handler's 400 arm runs in a dev build, a view that does
  not compile on the JVM, and the registration's `:sensitive` classification
  of the token. Everything is read out of the page's fences and evaluated."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            ;; Referenced by the page's `decode-form-params` and
            ;; `explain->errors`, evaluated into this namespace.
            [malli.error :as me]
            [malli.transform :as mt]
            [re-frame.classification :as rf.classification]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def ^:private pattern-page
  "Anchored to this file's classpath resource (five parents up is the repo root)."
  (let [res (io/resource "re_frame/ssr_doc_example_form_action_dispatch_test.clj")]
    (assert res "the ssr test/ dir must be on the classpath to find the pattern page")
    (-> (io/file res)
        .getParentFile .getParentFile .getParentFile .getParentFile .getParentFile
        (io/file "spec" "Pattern-FormAction.md")
        .getCanonicalFile)))

(defn- fence
  "The one ```clojure fence on the page containing `needle`; hard-errors on
  zero or many. CRLF is normalised for a Windows checkout."
  [needle]
  (let [md   (str/replace (slurp pattern-page) "\r\n" "\n")
        hits (->> (re-seq #"(?s)```clojure\n(.*?)```" md)
                  (map second)
                  (filter #(str/includes? % needle)))]
    (assert (= 1 (count hits))
            (str "expected EXACTLY ONE ```clojure fence in " pattern-page
                 " containing " (pr-str needle) ", found " (count hits)))
    (first hits)))

(defn- forms
  "Every top-level form in a fence, read with the `:clj` feature as a `.cljc`
  view compiled for the server is, so an unguarded browser-only form reads as
  the CLJS it is."
  [fence-text]
  (read-string {:read-cond :allow :features #{:clj}} (str "[" fence-text "]")))

(def ^:private this-ns 're-frame.ssr-doc-example-form-action-dispatch-test)

(defn- eval-in-ns [form]
  (binding [*ns* (find-ns this-ns)] (eval form)))

(defn- extracted
  "The value of a symbol the page's code defined in this namespace (named
  explicitly: a test var runs with whatever `*ns*` the runner last set)."
  [sym]
  (let [v (ns-resolve (find-ns this-ns) sym)]
    (assert v (str "the pattern page's fences define no " sym))
    @v))

(def ^:private draft-schema-form
  "The page's own `reg-app-schema` at the draft path. Registering it makes the
  dev-build proof real: `reg-app-schema` rejects a failing candidate whole."
  (delay
    (let [hits (->> (forms (fence "(def AddToCartFields"))
                    (filter #(and (seq? %)
                                  (= 'rf/reg-app-schema (first %))
                                  (= [:cart :add-form :draft] (second %)))))]
      (assert (= 1 (count hits))
              (str "expected ONE reg-app-schema at [:cart :add-form :draft], found " (count hits)))
      (first hits))))

(defn- install-action!
  "Evaluate the page's schemas, seam, helpers and action registration here."
  []
  (doseq [f (forms (fence "(def AddToCartFields")) :when (= 'def (first f))]
    (eval-in-ns f))
  (doseq [f (forms (fence "defn decode-form-params"))
          :when (contains? #{'def 'defn} (first f))]
    (eval-in-ns f))
  (doseq [f (forms (fence "defn write-form-errors")) :when (= 'defn (first f))]
    (eval-in-ns f))
  (eval-in-ns (first (forms (fence "rf/reg-event :cart/add-item"))))
  ;; App-owned: re-frame2 ships no CSRF surface.
  (rf/reg-cofx :app.csrf/active-token {:platforms #{:server}}
    (fn [] "tok-abc")))

(defn- dispatch-action
  "Dispatch the documented event into a server frame whose two server fxs
  record what the handler's arm emitted."
  [payload]
  (let [effects (atom [])]
    (rf/reg-event :form-action-dispatch-test/init
      (fn [_ _] {:db {:cart {:add-form {:draft {}}}}}))
    (rf/reg-fx :form-action-dispatch-test/status
      (fn [_ status] (swap! effects conj [:status status])))
    (rf/reg-fx :form-action-dispatch-test/redirect
      (fn [_ target] (swap! effects conj [:redirect target])))
    (rf/with-new-frame [frame (rf/make-frame
                               {:platform        :server
                                :initial-events  [[:form-action-dispatch-test/init]]
                                :fx-overrides
                                {:rf.server/set-status :form-action-dispatch-test/status
                                 :rf.server/redirect   :form-action-dispatch-test/redirect}})]
      (eval-in-ns @draft-schema-form)
      (rf/dispatch-sync [:cart/add-item payload] {:frame frame})
      {:db (rf/app-db-value frame) :fx @effects})))

(deftest invalid-fields-reach-the-custom-400-arm-through-dispatch
  (testing "the event `:schema` is adjudicated at the router, so it must be
            structural: malformed fields reach the handler's own 400 arm in
            both postures"
    (install-action!)
    (doseq [debug?   [true false]
            quantity [0 "abc"]]
      (with-redefs [rf.interop/debug-enabled? debug?]
        (let [{:keys [db fx]} (dispatch-action {:item-id    "sku-1"
                                                :quantity   quantity
                                                :csrf-token "tok-abc"})]
          (is (= [[[:status 400]] {:item-id "sku-1" :quantity quantity} true nil]
                 [fx
                  (get-in db [:cart :add-form :draft])
                  (boolean (seq (get-in db [:cart :add-form :errors :quantity])))
                  (get-in db [:cart :items])])
              (str "debug=" debug? ", quantity=" (pr-str quantity)
                   " — [fx draft quantity-error? cart]")))))))

(deftest a-valid-submission-still-reaches-the-303-through-dispatch
  (install-action!)
  (doseq [debug? [true false]]
    (with-redefs [rf.interop/debug-enabled? debug?]
      (let [{:keys [db fx]} (dispatch-action {:item-id    "sku-1"
                                              :quantity   2
                                              :csrf-token "tok-abc"})]
        (is (= [[[:redirect {:status 303 :location "/cart"}]] [{:item-id "sku-1" :quantity 2}]]
               [fx (get-in db [:cart :items])])
            (str "debug=" debug?))))))

(deftest the-event-tripwire-is-structural-while-the-handler-stays-strict
  (testing "the event `:schema` admits the token-less client dispatch too"
    (install-action!)
    (let [handler-form (first (forms (fence "rf/reg-event :cart/add-item")))
          event-schema (eval-in-ns (:schema (nth handler-form 2)))]
      (is (m/validate event-schema [:cart/add-item {:item-id "sku-1" :quantity 2}])))))

(deftest the-view-compiles-on-the-jvm-and-keeps-the-native-post
  (testing "the view reads and evaluates on the JVM with its browser-only
            callbacks guarded, leaving the native POST and every field"
    (rf/reg-sub :form.cart-add/draft (fn [_ _] {:quantity 2}))
    (rf/reg-sub :form.cart-add/form-errors (fn [_ _] nil))
    (rf/reg-sub :form.cart-add/field-error (fn [_ _] nil))
    (rf/reg-sub :app.csrf/token (fn [_ _] "tok-abc"))
    (eval-in-ns (first (forms (fence "rf/reg-view add-to-cart-form"))))
    (let [[tag attrs & children] ((extracted 'add-to-cart-form) "sku-1")
          ;; `sequential?`, not `vector?`: `children` is a seq.
          inputs (filter #(and (vector? %) (= :input (first %)))
                         (tree-seq sequential? seq children))]
      (is (= [:form "POST" "/cart/add" nil #{"csrf-token" "item-id" "quantity"} true]
             [tag (:method attrs) (:action attrs) (:on-submit attrs)
              (set (keep #(get-in % [1 :name]) inputs))
              (every? #(nil? (get-in % [1 :on-change])) inputs)])
          "[tag method action on-submit input-names no-on-change?]"))))

(deftest the-registration-classifies-the-token-for-event-observation
  (testing "the action's `:sensitive [[:csrf-token]]` redacts the token on the
            dispatched event vector, which the schema's `:sensitive?` prop
            does not reach"
    (install-action!)
    (is (= [:cart/add-item {:item-id "sku-1" :quantity 2 :csrf-token :rf/redacted}]
           (rf.classification/redact-event-by-registration
             [:cart/add-item {:item-id "sku-1" :quantity 2 :csrf-token "tok-abc"}])))))
