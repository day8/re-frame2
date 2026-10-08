(ns re-frame.resources-cache-key-identity-cljs-test
  "The resource cache key never `=`-collapses through its carriers.
  `(= [1 2 3] '(1 2 3))` is true, so the entries, indexes and work ledger key
  on the CEDN-1 byte `key-id` string, which rides the SSR and epoch wire with
  no custom transit handler. The live entries table is pinned in
  `resources-runtime-cljs-test`."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   #?(:cljs [cljs.reader])
   [re-frame.resources.ssr :as rf.resources.ssr]
   [re-frame.resources.state :as rf.resources.state]
   [re-frame.resources.work-ledger :as rf.resources.work-ledger]
   [re-frame.resources]
   [re-frame.test-support :as rf.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

(def ^:private kv (rf.resources.state/scoped-resource-key :rf.scope/global :r/x {:xs [1 2 3]}))
(def ^:private kl (rf.resources.state/scoped-resource-key :rf.scope/global :r/x {:xs '(1 2 3)}))

(defn- loaded-entry [sk data tags]
  (-> (rf.resources.state/empty-entry :r/x sk)
      (merge {:status :loaded :data data :loaded-at 1000 :stale-at 9.0e15
              :generation 1 :tags tags})))

(defn- byte-keyed-runtime-db
  "A runtime-db holding a list-params and a vector-params entry, byte-keyed.
  A `{kv … kl …}` literal would itself throw Duplicate key."
  []
  {rf.resources.state/resources-key
   {:entries (into {} (map (fn [[sk e]] [(rf.resources.state/key-id sk) e]))
                   [[kv (loaded-entry kv {:v 1} #{:t})]
                    [kl (loaded-entry kl {:l 1} #{:t})]])
    :tag-index {} :owner-index {}}})

(def ^:private both-key-ids #{(rf.resources.state/key-id kv) (rf.resources.state/key-id kl)})

(deftest carrier-2-work-ledger-keys-distinctly
  (let [wv   (rf.resources.work-ledger/resource-work-id kv 1)
        wl   (rf.resources.work-ledger/resource-work-id kl 1)
        rec  (fn [wid k] (rf.resources.work-ledger/work-record {:work-id wid :frame-id :f :resource/key k
                                                                :generation 1 :transport :rf.http/managed}))
        rdb  (-> {}
                 (rf.resources.work-ledger/put-record wv (rec wv kv))
                 (rf.resources.work-ledger/put-record wl (rec wl kl)))]
    (is (= wv wl) "precondition: the work-id vectors are Clojure-=")
    (is (= 2 (count (:rf.runtime/work-ledger rdb))))
    (is (= [kv kl] [(:resource/key (rf.resources.work-ledger/get-record rdb wv))
                    (:resource/key (rf.resources.work-ledger/get-record rdb wl))]))
    (is (vector? (get-in (rf.resources.work-ledger/get-record rdb wv) [:resource/key 2 :xs])))
    (is (seq? (get-in (rf.resources.work-ledger/get-record rdb wl) [:resource/key 2 :xs])))))

(deftest carrier-3-ssr-projection-round-trip-keeps-two-entries
  (let [wired (get-in (rf.resources.ssr/project-resources-runtime-db (byte-keyed-runtime-db))
                      [rf.resources.state/resources-key :entries])]
    (is (= both-key-ids (set (keys wired))) "two wire entries under plain string keys")
    (is (= wired #?(:clj  (read-string (pr-str wired))
                    :cljs (cljs.reader/read-string (pr-str wired))))
        "the wire entries survive an EDN round-trip")
    (let [out (rf.resources.ssr/hydrate-runtime-db {rf.resources.state/resources-key {:entries wired}} :app/main)]
      (is (= both-key-ids (set (keys (get-in out [rf.resources.state/resources-key :entries])))))
      (is (= both-key-ids (get-in out [rf.resources.state/resources-key :tag-index :t]))))))

(deftest carrier-3b-restore-reconcile-keeps-two-entries
  (let [out (rf.resources.ssr/reconcile-on-restore
              (assoc (byte-keyed-runtime-db) rf.resources.state/work-ledger-key {}))]
    (is (= 2 (count (get-in out [rf.resources.state/resources-key :entries]))))
    (is (= both-key-ids (get-in out [rf.resources.state/resources-key :tag-index :t])))))

;; An instant canonicalizes to the tagged tuple [:rf.identity/instant <text>],
;; never a bare string, so it cannot alias a look-alike string param; two
;; spellings of one instant still dedupe.

(deftest instant-vs-string-params-distinct-identity
  (let [instant-text "2026-06-10T00:00:00.000Z"
        ki (rf.resources.state/scoped-resource-key
             :rf.scope/global :r/x {:at #inst "2026-06-10T00:00:00.000-00:00"})
        ks (rf.resources.state/scoped-resource-key :rf.scope/global :r/x {:at instant-text})
        kd (rf.resources.state/scoped-resource-key
             :rf.scope/global :r/x {:at #?(:clj  (java.util.Date. 1781049600000)
                                           :cljs (js/Date. 1781049600000))})]
    (is (= [{:at [:rf.identity/instant instant-text]} {:at instant-text}] [(nth ki 2) (nth ks 2)]))
    (is (not= (rf.resources.state/key-id ki) (rf.resources.state/key-id ks)))
    (is (= ki kd) "a host Date and its #inst literal are one identity")
    (is (= (rf.resources.state/key-id ki) (rf.resources.state/key-id kd)))))
