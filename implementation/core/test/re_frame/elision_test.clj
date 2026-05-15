(ns re-frame.elision-test
  "Schema-first wire elision tests."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
  (elision/clear-warning-cache!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.elision :reload)
  (require 're-frame.schemas :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-trace-cb! id (fn [ev] (swap! acc conj ev)))
    acc))

(deftest walker-noop-on-small-values
  (is (= 42 (rf/elide-wire-value 42)))
  (is (= "hello" (rf/elide-wire-value "hello")))
  (is (= {:a 1 :b [2 3]} (rf/elide-wire-value {:a 1 :b [2 3]}))))

(deftest schema-large-path-emits-marker
  (rf/reg-app-schema [:user]
                     [:map
                      [:name :string]
                      [:uploaded-pdf {:large? true :hint "Upload preview blob"}
                       :string]])
  (is (= [[:user :uploaded-pdf]]
         (rf/populate-elision-from-schemas!)))
  (let [decls (rf/elision-declarations)
        out   (rf/elide-wire-value
                {:user {:name "Ada" :uploaded-pdf "<<5MB-blob>>"}})
        slot  (get-in out [:user :uploaded-pdf])]
    (is (= {:large? true :source :schema :hint "Upload preview blob"}
           (get decls [:user :uploaded-pdf])))
    (is (elision/marker? slot))
    (is (= [:user :uploaded-pdf]
           (get-in slot [:rf.size/large-elided :path])))
    (is (= :schema (get-in slot [:rf.size/large-elided :reason])))
    (is (= "Upload preview blob"
           (get-in slot [:rf.size/large-elided :hint])))
    (is (= "Ada" (get-in out [:user :name])))))

(deftest include-large-bypasses-schema-elision
  (rf/reg-app-schema [:big] [:string {:large? true}])
  (rf/populate-elision-from-schemas!)
  (is (elision/marker? (:big (rf/elide-wire-value {:big "blob"}))))
  (is (= "blob"
         (:big (rf/elide-wire-value {:big "blob"}
                                    {:rf.size/include-large? true})))))

(deftest unschema'd-large-value-warns-but-does-not-elide
  (let [big    (apply str (repeat 3000 "ABCDEFGH"))
        traces (collect-traces! :elision-test/unschema'd)
        out    (rf/elide-wire-value {:user {:photo big}})]
    (is (= big (get-in out [:user :photo]))
        "schema-less large values are not auto-elided")
    (let [warnings (filterv #(= :rf.warning/large-value-unschema'd
                                (:operation %))
                            @traces)]
      (is (= 1 (count warnings)))
      (is (= [:user :photo] (get-in (first warnings) [:tags :path])))
      (is (pos-int? (get-in (first warnings) [:tags :bytes])))
      (is (= "Add `{:large? true}` to the schema slot for this path."
             (get-in (first warnings) [:tags :hint]))))
    (rf/remove-trace-cb! :elision-test/unschema'd)))

(deftest unschema'd-large-warning-is-once-per-path
  (let [big    (apply str (repeat 3000 "ABCDEFGH"))
        traces (collect-traces! :elision-test/once)]
    (rf/elide-wire-value {:photo big})
    (rf/elide-wire-value {:photo big})
    (rf/elide-wire-value {:photo big})
    (is (= 1 (count (filter #(= :rf.warning/large-value-unschema'd
                                (:operation %))
                            @traces))))
    (rf/remove-trace-cb! :elision-test/once)))

(deftest schema-sensitive-path-redacts
  (rf/reg-app-schema [:auth]
                     [:map
                      [:username :string]
                      [:password {:sensitive? true} :string]])
  (rf/populate-sensitive-from-schemas!)
  (let [out (rf/elide-wire-value {:auth {:username "ada"
                                         :password "shh"}})]
    (is (= "ada" (get-in out [:auth :username])))
    (is (= :rf/redacted (get-in out [:auth :password])))
    (is (= "shh"
           (get-in (rf/elide-wire-value
                     {:auth {:password "shh"}}
                     {:rf.size/include-sensitive? true})
                   [:auth :password])))))

(deftest sensitive-wins-over-large
  (rf/reg-app-schema [:secret-pdf]
                     [:string {:large? true
                               :sensitive? true
                               :hint "encrypted blob"}])
  (rf/populate-elision-from-schemas!)
  (rf/populate-sensitive-from-schemas!)
  (let [out (rf/elide-wire-value {:secret-pdf "payload"})]
    (is (= :rf/redacted (:secret-pdf out)))
    (is (not (elision/marker? (:secret-pdf out))))))

(deftest marker-options
  (rf/reg-app-schema [:b] [:string {:large? true :hint "hint"}])
  (rf/populate-elision-from-schemas!)
  (let [out    (rf/elide-wire-value {:b "X"}
                                    {:rf.size/include-digests? true
                                     :as-of-epoch 42})
        marker (get-in out [:b :rf.size/large-elided])]
    (is (= [:rf.elision/at [:b] :as-of-epoch 42] (:handle marker)))
    (is (= :string (:type marker)))
    (is (= :schema (:reason marker)))
    (is (string? (:digest marker)))))

(deftest nested-schema-population
  (rf/reg-app-schema [:root]
                     [:map
                      [:a [:map
                           [:b [:map
                                [:c {:large? true :hint "deep"} :string]
                                [:token {:sensitive? true} :string]]]]]])
  (is (= [[:root :a :b :c]]
         (rf/populate-elision-from-schemas!)))
  (is (= [[:root :a :b :token]]
         (rf/populate-sensitive-from-schemas!)))
  (is (= "deep"
         (get-in (rf/elision-declarations)
                 [[:root :a :b :c] :hint]))))

(deftest schema-repopulation-prunes-stale-schema-entries
  (rf/reg-app-schema [:user]
                     [:map [:pdf {:large? true} :string]])
  (rf/populate-elision-from-schemas!)
  (is (contains? (rf/elision-declarations) [:user :pdf]))
  (rf/reg-app-schema [:user] [:map [:pdf :string]])
  (rf/populate-elision-from-schemas!)
  (is (not (contains? (rf/elision-declarations) [:user :pdf]))))

(deftest registries-are-frame-isolated
  (frame/reg-frame :elision-test/other {})
  (rf/reg-app-schema [:blob] [:string {:large? true}] {:frame :rf/default})
  (rf/reg-app-schema [:blob] [:string] {:frame :elision-test/other})
  (rf/populate-elision-from-schemas! :rf/default)
  (rf/populate-elision-from-schemas! :elision-test/other)
  (is (contains? (rf/elision-declarations :rf/default) [:blob]))
  (is (not (contains? (rf/elision-declarations :elision-test/other) [:blob]))))
