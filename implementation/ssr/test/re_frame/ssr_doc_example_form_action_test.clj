(ns re-frame.ssr-doc-example-form-action-test
  "The `/cart/add` worked example in `spec/Pattern-FormAction.md` is EXECUTED:
  its schemas, wire-to-domain seam, helpers and action handler are read out of
  the page's fences and evaluated here, then driven with both legitimate call
  sites — the token-less hydrated-client dispatch and a real
  `application/x-www-form-urlencoded` POST body. No docs gate evaluates a
  fence, and this example is untrusted-input handling a reader copies.

  Every assertion is a pure call on extracted code, so the namespace runs
  identically under the production gate, where the handler's own arms are the
  only validation left."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]
            ;; Referenced by the page's `decode-form-params`, evaluated into
            ;; this namespace.
            [malli.transform :as mt])
  (:import [java.net URLDecoder]))

(def ^:private pattern-page
  "Anchored to this file's classpath resource (five parents up is the repo
  root), so it resolves under both the per-artefact and the combined alias."
  (let [res (io/resource "re_frame/ssr_doc_example_form_action_test.clj")]
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

(defn- forms [fence-text]
  (read-string (str "[" fence-text "]")))

(def ^:private example
  "The page's worked example, evaluated into this namespace: schemas first,
  since the handler resolves them by name."
  (delay
    (binding [*ns* (find-ns 're-frame.ssr-doc-example-form-action-test)]
      (let [schema-forms (forms (fence "(def AddToCartFields"))
            handler-form (first (forms (fence "rf/reg-event :cart/add-item")))
            draft-sym    (->> schema-forms
                              (filter #(= [:cart :add-form :draft] (second %)))
                              first
                              (#(nth % 2)))]
        (doseq [f schema-forms :when (= 'def (first f))]
          (eval f))
        (doseq [f (forms (fence "defn decode-form-params"))
                :when (contains? #{'def 'defn} (first f))]
          (eval f))
        (doseq [f (forms (fence "defn write-form-errors")) :when (= 'defn (first f))]
          (eval f))
        {:fields        @(resolve 'AddToCartFields)
         :submission    @(resolve 'AddToCartSubmission)
         :decode        @(resolve 'decode-form-params)
         :route->action @(resolve 'route->action)
         :handler       (eval (last handler-form))
         :draft-schema  (let [v (resolve draft-sym)]
                          (assert v (str "the page registers " draft-sym
                                         " at [:cart :add-form :draft] but no fence defines it"))
                          @v)}))))

(def ^:private posted-field-names
  "The `name`s of the `<input>`s the documented view renders."
  (delay
    (->> (re-seq #":name\s+\"([^\"]+)\"" (fence "rf/reg-view add-to-cart-form"))
         (map second)
         (map keyword)
         set)))

(defn- parse-form-urlencoded
  "The host adapter's job (Ring's `wrap-params`): string keys to string values."
  [body]
  (into {} (for [pair (str/split body #"&")
                 :let [[k v] (str/split pair #"=" 2)]]
             [(URLDecoder/decode k "UTF-8") (URLDecoder/decode v "UTF-8")])))

(def ^:private parsed-post
  (delay (parse-form-urlencoded "csrf-token=tok-abc&item-id=sku-1&quantity=2")))

(defn- decode-post
  "A parsed body through the page's own `route->action` and `decode-form-params`."
  [parsed]
  (let [{:keys [decode route->action]} @example]
    (decode (:schema (route->action :route/cart-add)) parsed)))

(def ^:private server-post (delay (decode-post @parsed-post)))

(def ^:private client-dispatch
  "The hydrated client's payload: no CSRF token."
  {:item-id "sku-1" :quantity 2})

(defn- invoke
  "Call the documented handler as the runtime would. On the client both
  server-only coeffect keys are absent, which is the platform test the
  handler makes."
  [{:keys [server? active-token]} form-params]
  ((:handler @example)
   (cond-> {:db {}}
     server? (assoc :rf.server/request     {:request-method :post :uri "/cart/add"}
                    :app.csrf/active-token active-token))
   [:cart/add-item form-params]))

(defn- outcome
  "`[fx cart-items]` of a handler call."
  [result]
  [(:fx result) (get-in result [:db :cart :items])])

(deftest the-hydrated-client-submission-navigates-rather-than-400ing
  (let [{:keys [db fx]} (invoke {:server? false} client-dispatch)]
    (is (= [[[:dispatch [:rf.route/navigate {:to :route/cart}]]] [client-dispatch] nil]
           [fx (get-in db [:cart :items]) (get-in db [:cart :add-form :errors])])
        "[navigates, item in the cart, no errors]")))

(deftest the-no-js-post-reaches-the-same-success-arm
  (testing "the decoded wire body is answered with the 303, and the token never
            reaches app-db"
    (is (= [[[:rf.server/redirect {:status 303 :location "/cart"}]] [{:item-id "sku-1" :quantity 2}]]
           (outcome (invoke {:server? true :active-token "tok-abc"} @server-post))))))

(deftest the-documented-seam-turns-the-wire-body-into-the-handlers-shape
  (is (= [:cart/add-item {:csrf-token "tok-abc" :item-id "sku-1" :quantity 2}]
         [(:event ((:route->action @example) :route/cart-add)) @server-post])
      "the route resolves to its event, and the seam keywordises keys and coerces :quantity"))

(deftest the-raw-parsed-body-does-not-reach-303-so-the-seam-is-load-bearing
  (testing "undecoded, a correct submission misses the string-keyed token and
            the CSRF arm fails closed"
    (is (= [[[:rf.server/set-status 403]] nil]
           (outcome (invoke {:server? true :active-token "tok-abc"} @parsed-post))))))

(deftest the-post-body-the-form-submits-is-the-shape-the-schemas-name
  (testing "the form posts exactly what the envelope schema names, while the
            field schema names only the editable fields"
    (let [{:keys [fields submission]} @example
          entry-keys (fn [s] (set (map first (m/children (m/schema s)))))]
      (is (= [#{:csrf-token :item-id :quantity} #{:csrf-token :item-id :quantity} #{:item-id :quantity}]
             [@posted-field-names (entry-keys submission) (entry-keys fields)])
          "[posted inputs, envelope keys, field keys]"))))

(deftest the-token-is-marked-sensitive-on-the-surface-it-travels-through
  (testing "the envelope's token is :sensitive? (a validation-failure trace
            carries args verbatim) and :optional (one schema admits both call
            sites)"
    (let [props (->> (m/children (m/schema (:submission @example)))
                     (filter #(= :csrf-token (first %)))
                     first
                     second)]
      (is (= [true true] [(:sensitive? props) (:optional props)])))))

(deftest the-failure-arm-writes-a-draft-that-satisfies-the-registered-schema
  (testing "a malformed POST — decodable or not — gets the 400, the errors and
            the submitted fields (never the token) back in the draft, and that
            draft satisfies the schema the page registers at the draft path"
    (let [{:keys [fields draft-schema]} @example]
      (doseq [[wire draft] [["csrf-token=tok-abc&item-id=sku-1&quantity=0"
                             {:item-id "sku-1" :quantity 0}]
                            ["csrf-token=tok-abc&item-id=sku-1&quantity=abc"
                             {:item-id "sku-1" :quantity "abc"}]]]
        (let [{:keys [db fx]} (invoke {:server? true :active-token "tok-abc"}
                                      (decode-post (parse-form-urlencoded wire)))]
          (is (= [[[:rf.server/set-status 400]] draft true nil]
                 [fx
                  (get-in db [:cart :add-form :draft])
                  (boolean (seq (get-in db [:cart :add-form :errors])))
                  (get-in db [:cart :items])])
              (str wire " — [fx draft errors? cart]"))
          (is (m/validate draft-schema draft) (str wire " — the registered draft schema admits it"))))
      (is (not (m/validate fields {:item-id "sku-1" :quantity 0}))
          "control: the field schema refuses that draft, so the draft schema is not merely the same schema"))))

(deftest client-side-validation-failure-takes-the-same-arm
  (is (= [[[:rf.server/set-status 400]] nil]
         (outcome (invoke {:server? false} (assoc client-dispatch :quantity 0))))))

(deftest the-csrf-arm-rejects-a-mismatched-token
  (is (= [[[:rf.server/set-status 403]] nil]
         (outcome (invoke {:server? true :active-token "tok-abc"}
                          (assoc @server-post :csrf-token "tok-WRONG"))))))

(deftest the-csrf-arm-fails-closed-when-the-request-carries-no-session
  (testing "nil = nil must not read as a valid token"
    (is (= [[[:rf.server/set-status 403]] nil]
           (outcome (invoke {:server? true :active-token nil}
                            (dissoc @server-post :csrf-token)))))))

(deftest the-published-helpers-produce-the-shapes-pattern-forms-expects
  (testing "`explain->errors` yields the humanized per-field map Pattern-Forms renders"
    (binding [*ns* (find-ns 're-frame.ssr-doc-example-form-action-test)]
      (let [explanation (m/explain (:fields @example) (assoc client-dispatch :quantity 0))]
        (is (= (me/humanize explanation) ((resolve 'explain->errors) explanation)))))))
