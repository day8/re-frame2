(ns re-frame.http-privacy-body-test
  "Unit tests for `re-frame.http.privacy-body` — HTTP response-body
  classification (EP-0015 §8, ruled issue 5; rf2-ppkh3v).

  Pins the contract that a managed HTTP response body is a registration-
  owned transient payload classified per-slot via `:sensitive?` / `:large?`
  props on the request's `:decode` SCHEMA (the EP-0005 mechanism reused):

    1. a Malli-schema `:decode` is a schema (carries marks); the keyword
       decode modes and a custom fn are NOT;
    2. per-slot `:sensitive?` marks on the decode schema redact the decoded
       body's marked slots irrespective of the per-call `:sensitive?` flag;
    3. per-slot `:large?` marks on the decode schema elide the marked slots
       to the `:rf.size/large-elided` marker; sensitive wins over large
       (rf2-jhyccs);
    4. a root-level (`[]`) `:sensitive?` mark redacts the WHOLE body;
    5. an unschematized body fails CLOSED off-box (omitted via
       `off-box-classify-body`), while a schema-classified body rides
       classified (rf2-t55hxg.6).

  The schemas artefact is a test-only dep here, so requiring it binds the
  shared walker hooks (`:schemas/extract-sensitive-paths-from-schema` etc.)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.http.privacy-body :as body]
            [re-frame.registrar :as registrar]
            ;; load-bearing: binds the shared schema walker hooks.
            [re-frame.schemas]))

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (t))

(use-fixtures :each reset-runtime)

;; ---- 1. schema-decode? ----------------------------------------------------

(deftest schema-decode?-distinguishes-schemas-from-modes-and-fns
  (testing "a Malli-schema :decode is a schema"
    (is (body/schema-decode? [:map [:token :string]]))
    (is (body/schema-decode? [:map [:a :int]])))
  (testing "keyword decode modes are NOT schemas"
    (is (not (body/schema-decode? :auto)))
    (is (not (body/schema-decode? :json)))
    (is (not (body/schema-decode? :text)))
    (is (not (body/schema-decode? :blob)))
    (is (not (body/schema-decode? :array-buffer)))
    (is (not (body/schema-decode? :form-data))))
  (testing "a custom decoder fn is NOT a schema"
    (is (not (body/schema-decode? (fn [_text _headers] {}))))
    (is (not (body/schema-decode? identity))))
  (testing "nil :decode (= :auto) is NOT a schema"
    (is (not (body/schema-decode? nil)))))

;; ---- 2. per-slot marks → classify-decoded ---------------------------------

(deftest classify-decoded-redacts-marked-slot
  (testing "a :decode schema marking [:token] sensitive redacts that slot
            of the decoded body, leaving siblings intact"
    (let [schema  [:map
                   [:token {:sensitive? true} :string]
                   [:user-id :int]]
          decoded {:token "bearer-secret" :user-id 42}
          out     (body/classify-decoded decoded schema)]
      (is (= :rf/redacted (:token out)))
      (is (= 42 (:user-id out)) "non-sensitive sibling rides verbatim"))))

(deftest classify-decoded-nested-slot
  (testing "a nested :sensitive? slot redacts at depth"
    (let [schema  [:map
                   [:auth [:map [:refresh-token {:sensitive? true} :string]]]
                   [:profile [:map [:name :string]]]]
          decoded {:auth {:refresh-token "rt-secret"}
                   :profile {:name "Ada"}}
          out     (body/classify-decoded decoded schema)]
      (is (= :rf/redacted (get-in out [:auth :refresh-token])))
      (is (= "Ada" (get-in out [:profile :name]))))))

(deftest classify-decoded-no-marks-passes-through
  (testing "a schema with no :sensitive? marks leaves the body unchanged"
    (let [schema  [:map [:a :int] [:b :string]]
          decoded {:a 1 :b "x"}]
      (is (= decoded (body/classify-decoded decoded schema))))))

(deftest classify-decoded-non-schema-is-noop
  (testing "a keyword / fn / nil :decode is a no-op (body governed by the
            per-call flag / off-box disposition, not per-slot marks)"
    (let [decoded {:token "secret"}]
      (is (= decoded (body/classify-decoded decoded :json)))
      (is (= decoded (body/classify-decoded decoded :auto)))
      (is (= decoded (body/classify-decoded decoded nil)))
      (is (= decoded (body/classify-decoded decoded (fn [_ _] {})))))))

;; ---- 3. whole-body root prop ----------------------------------------------

(deftest whole-body-root-sensitive-redacts-everything
  (testing "a root-level [] :sensitive? prop on the decode schema marks the
            WHOLE body sensitive (the opaque-token-response case)"
    (let [schema  [:string {:sensitive? true}]
          decoded "opaque-token-value"]
      (is (body/whole-body-sensitive-mark? schema))
      (is (= :rf/redacted (body/classify-decoded decoded schema))))))

(deftest whole-body-mark-absent-for-non-root-marks
  (testing "a per-slot (non-root) mark is NOT a whole-body mark"
    (is (not (body/whole-body-sensitive-mark? [:map [:token {:sensitive? true} :string]])))
    (is (not (body/whole-body-sensitive-mark? :json)))))

;; ---- 4. off-box disposition (fail-closed) ---------------------------------

(deftest off-box-disposition-classifies-schema-bodies
  (testing "a schema-:decode body is :classify off-box"
    (is (= :classify (body/off-box-body-disposition [:map [:a :int]])))
    (is (= :classify (body/off-box-body-disposition [:string {:sensitive? true}])))))

(deftest off-box-disposition-omits-unschematized-bodies
  (testing "an UNSCHEMATIZED body (keyword mode / custom fn / nil) is :omit
            off-box — whole-sensitive, fail-closed (EP-0015 issue 5)"
    (is (= :omit (body/off-box-body-disposition :auto)))
    (is (= :omit (body/off-box-body-disposition :json)))
    (is (= :omit (body/off-box-body-disposition :text)))
    (is (= :omit (body/off-box-body-disposition :blob)))
    (is (= :omit (body/off-box-body-disposition nil)))
    (is (= :omit (body/off-box-body-disposition (fn [_ _] {}))))))

(deftest off-box-disposition-omits-opaque-registry-ref
  (testing "an OPAQUE keyword registry-ref :decode is :omit off-box — the
            shared schema walker cannot inspect a registry ref (it returns
            {} per-slot marks), so :classify would ride the body UNCHANGED.
            EP-0015 issue 5 requires fail-CLOSED when classification is
            unknown (rf2-y1pgdl)"
    ;; A bare keyword that is NOT a known decode mode is taken by
    ;; `schema-decode?` as a registry ref — but the walker can only
    ;; introspect the VECTOR form. Off-box it must fail closed.
    (is (= :omit (body/off-box-body-disposition :my-app/token-schema)))
    (is (= :omit (body/off-box-body-disposition :user/profile)))))

(deftest off-box-disposition-omits-opaque-compiled-schema
  (testing "an OPAQUE non-vector schema value (a compiled m/schema object /
            a map / any non-vector non-keyword-mode form) is :omit off-box —
            the walker cannot introspect it, so fail-closed (rf2-y1pgdl)"
    ;; A map (or any non-vector, non-keyword decode value the walker treats
    ;; as an opaque leaf returning {}) must NOT ride :classify off-box.
    (is (= :omit (body/off-box-body-disposition {:opaque :compiled-schema-stand-in})))))

;; ---- 5. per-slot :large? elision (rf2-jhyccs) -----------------------------

(deftest classify-decoded-elides-large-slot
  (testing "a :decode schema marking [:blob] :large? elides that slot of the
            decoded body to the :rf.size/large-elided marker, leaving
            siblings intact"
    (let [schema  [:map
                   [:blob {:large? true} :string]
                   [:user-id :int]]
          decoded {:blob (apply str (repeat 100 "x")) :user-id 42}
          out     (body/classify-decoded decoded schema)]
      (is (contains? (:blob out) :rf.size/large-elided)
          "large body slot elided to the size marker")
      (is (= 42 (:user-id out)) "non-large sibling rides verbatim"))))

(deftest classify-decoded-whole-body-large-elides
  (testing "a root-level [] :large? prop on the decode schema elides the
            WHOLE body to the size marker"
    (let [schema  [:string {:large? true}]
          decoded (apply str (repeat 200 "y"))
          out     (body/classify-decoded decoded schema)]
      (is (contains? out :rf.size/large-elided)))))

(deftest classify-decoded-sensitive-wins-over-large
  (testing "a slot marked BOTH :sensitive? and :large? redacts (sensitive
            wins over large — the shared elision-walk ordering)"
    (let [schema  [:map
                   [:secret {:sensitive? true :large? true} :string]]
          decoded {:secret (apply str (repeat 100 "z"))}
          out     (body/classify-decoded decoded schema)]
      (is (= :rf/redacted (:secret out))
          "sensitive wins — the slot is redacted, not a large marker"))))

;; ---- 6. off-box-classify-body (rf2-t55hxg.6) ------------------------------

(deftest off-box-classify-body-omits-unschematized
  (testing "an UNSCHEMATIZED body is OMITTED (replaced with the marker)
            off-box regardless of content — fail-closed"
    (is (= :rf/redacted (body/off-box-classify-body {:token "secret"} :json)))
    (is (= :rf/redacted (body/off-box-classify-body {:token "secret"} :auto)))
    (is (= :rf/redacted (body/off-box-classify-body "anything" nil)))
    (is (= :rf/redacted (body/off-box-classify-body {:a 1} (fn [_ _] {}))))
    (is (= body/off-box-body-marker
           (body/off-box-classify-body {:a 1} :text)))))

(deftest off-box-classify-body-omits-opaque-registry-ref
  (testing "an OPAQUE keyword registry-ref :decode OMITS the body off-box —
            the walker cannot inspect a registry ref's per-slot marks, so the
            body must NOT ride unchanged (the rf2-y1pgdl fail-open leak: a
            sensitive/large opaque-ref body slipping through classified-but-
            unredacted)"
    (is (= :rf/redacted (body/off-box-classify-body {:token "bearer-secret"}
                                                    :my-app/token-schema)))
    (is (= body/off-box-body-marker
           (body/off-box-classify-body {:token "secret" :blob "huge"}
                                       :user/profile)))))

(deftest off-box-classify-body-omits-opaque-compiled-schema
  (testing "an OPAQUE non-vector schema value OMITS the body off-box —
            fail-closed (rf2-y1pgdl)"
    (is (= :rf/redacted (body/off-box-classify-body {:secret "x"}
                                                    {:opaque :compiled})))))

(deftest off-box-classify-body-classifies-schema-bodies
  (testing "a SCHEMA body rides CLASSIFIED off-box — per-slot sensitive
            redacts, large elides, non-marked structure rides verbatim"
    (let [schema  [:map
                   [:token {:sensitive? true} :string]
                   [:blob {:large? true} :string]
                   [:user-id :int]]
          decoded {:token "bearer-secret"
                   :blob (apply str (repeat 100 "x"))
                   :user-id 42}
          out     (body/off-box-classify-body decoded schema)]
      (is (= :rf/redacted (:token out)) "sensitive slot redacted off-box")
      (is (contains? (:blob out) :rf.size/large-elided)
          "large slot elided off-box")
      (is (= 42 (:user-id out)) "non-marked structure rides classified"))))
