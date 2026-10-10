(ns re-frame.http-privacy-body-test
  "Unit tests for `re-frame.http.privacy-body` — response-body classification
  by the request's `:decode` schema (Spec 014 §Privacy). The transport wiring
  is covered end-to-end in `re-frame.http-privacy-integration-test`."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.http.privacy-body :as rf.http.privacy-body]
            [re-frame.late-bind :as rf.late-bind]
            ;; load-bearing: binds the shared schema walker hooks.
            [re-frame.schemas]))

;; The walker cannot introspect a keyword registry ref (it yields no marks), so
;; riding it :classify off-box would ship the body unclassified.
(deftest off-box-disposition-omits-opaque-registry-ref
  (is (= :omit (rf.http.privacy-body/off-box-body-disposition :my-app/token-schema))))

;; A vector form can still hide its marks behind a reference the walker
;; resolves nowhere, so `:classify` needs the walker to report no opaque
;; descendant.
(deftest off-box-disposition-omits-a-vector-form-the-walker-reports-opaque
  (are [decode] (= :omit (rf.http.privacy-body/off-box-body-disposition decode))
    [:ref :app/user]
    [:schema {:registry {:app/u2 [:map [:token {:sensitive? true} :string]]}} :app/u2]
    [:map [:user [:ref :app/user]]]
    [:map [:items [:vector [:ref :app/user]]]]))

(deftest off-box-disposition-classifies-a-walkable-vector-form
  (are [decode] (= :classify (rf.http.privacy-body/off-box-body-disposition decode))
    [:map [:token {:sensitive? true} :string] [:user-id :int]]
    [:map [:items [:vector [:map [:id :int] [:token {:sensitive? true} :string]]]]]
    [:string {:sensitive? true}]))

(deftest classify-decoded-redacts-a-collection-mark-in-every-element
  (is (= {:items [{:id 1 :token :rf/redacted} {:id 2 :token :rf/redacted}]
          :meta  {:token "public"}}
         (rf.http.privacy-body/classify-decoded
           {:items [{:id 1 :token "a"} {:id 2 :token "b"}] :meta {:token "public"}}
           [:map
            [:items [:vector [:map [:id :int] [:token {:sensitive? true} :string]]]]
            [:meta [:map [:token :string]]]])))
  (testing "a position-pinned tuple mark still matches only its own element"
    (is (= {:pair ["x" {:token :rf/redacted}]}
           (rf.http.privacy-body/classify-decoded
             {:pair ["x" {:token "s"}]}
             [:map [:pair [:tuple :string [:map [:token {:sensitive? true} :string]]]]])))))

(deftest classify-decoded-elides-large-slot
  (let [out (rf.http.privacy-body/classify-decoded
              {:blob (apply str (repeat 100 "x")) :user-id 42}
              [:map [:blob {:large? true} :string] [:user-id :int]])]
    (is (contains? (:blob out) :rf.size/large-elided))
    (is (= 42 (:user-id out)) "an unmarked sibling rides verbatim")))

(deftest classify-decoded-sensitive-wins-over-large
  (is (= {:secret :rf/redacted}
         (rf.http.privacy-body/classify-decoded
           {:secret (apply str (repeat 100 "z"))}
           [:map [:secret {:sensitive? true :large? true} :string]]))))

;; ---- an UNBOUND shared walker ------------------------------------------------
;;
;; The walker ships in the optional schemas artefact and arrives through a
;; late-bind hook. Unbound, the marks are UNKNOWN rather than empty: a schema
;; that declares a mark must throw rather than ride its marked slot verbatim,
;; while a schema declaring none keeps working without the artefact.

(defn- with-walker-unbound
  "Run `f` with every shared schema-walker hook removed; restore them after."
  [f]
  (let [hook-keys [:schemas/extract-sensitive-paths-from-schema
                   :schemas/extract-large-paths-from-schema
                   :schemas/schema-has-opaque-child?]
        saved     (select-keys @rf.late-bind/hooks hook-keys)
        refresh!  #(doseq [k hook-keys] (rf.late-bind/invalidate-cache! k))]
    (try
      (swap! rf.late-bind/hooks #(apply dissoc % hook-keys))
      (refresh!)
      (f)
      (finally
        (swap! rf.late-bind/hooks merge saved)
        (refresh!)))))

(deftest unbound-walker-throws-for-a-mark-declaring-schema
  (with-walker-unbound
    (fn []
      (are [decoded schema]
           (= :rf.error/schemas-artefact-missing
              (try (rf.http.privacy-body/classify-decoded decoded schema) nil
                   (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))
        {:token "bearer-secret"} [:map [:token {:sensitive? true} :string]]
        {:blob "huge"}           [:map [:blob {:large? true} :string]]))))

(deftest unbound-walker-fails-closed-off-box
  (testing "without the walker nothing can show a vector form complete"
    (with-walker-unbound
      #(is (= :omit (rf.http.privacy-body/off-box-body-disposition [:map [:id :int]]))))))

(deftest unbound-walker-is-silent-for-a-markless-schema
  (testing "only a :sensitive? / :large? PROP is a mark: a plain schema, a field
            merely named :sensitive?, and opaque schemas ride unchanged"
    (with-walker-unbound
      (fn []
        (are [decoded schema] (= decoded (rf.http.privacy-body/classify-decoded decoded schema))
          {:id 1 :title "t"} [:map [:id :int] [:title :string]]
          {:sensitive? true} [:map [:sensitive? :boolean]]
          {:a 1}             :user/profile
          {:a 1}             {:opaque :compiled})))))
