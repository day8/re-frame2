(ns re-frame.security.one-secret-every-egress-security-cljs-test
  "One declared token, followed through every egress it reaches. An HTTP reply
  carries it in a vector of maps whose `:decode` schema marks the slot, the
  reply event hands those maps to a machine whose `:sensitive` declaration
  names the same slot in its `:data`, and the SSR hydration payload carries
  the machine's snapshot. No `:rf.http/*` trace row, as delivered to listeners
  or projected off-box, and no byte of the serialised payload carries the
  token; the app's delivered reply and the live snapshot hold it raw, and the
  scan is shown to find it there.

  The owning contracts: Spec 014 §Response-body classification (the decoded
  body on the trace), Spec 015 §Subsystem projection-relative classification
  (the machine's `:data`) and Spec 011 §Payload scope (the hydration payload).

  The case is `^:requires-debug`: trace rows and the off-box epoch projection
  exist only in a debug build."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.string :as str]
            [re-frame.core :as rf]
            ;; Registers `:rf.http/managed`.
            [re-frame.http.managed]
            [re-frame.http.stub-transport :as rf.http.stub-transport]
            ;; Registers `:rf/machine` and the spawn fx.
            [re-frame.machines]
            [re-frame.machines.paths :as rf.machines.paths]
            ;; Binds the schema walker the `:decode` marks classify through;
            ;; without it the trace carries the token verbatim.
            [re-frame.schemas]
            [re-frame.ssr :as rf.ssr]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private token "S3CR3T-one-secret-every-egress")

(def ^:private frame-id ::server)

(def ^:private actor-id ::holder-1)

(def ^:private url "https://api.example.test/items")

(def ^:private Items
  [:map [:items [:vector [:map [:id :int] [:token {:sensitive? true} :string]]]]])

;; Map keys and wrapped strings are scanned, so a copied value cannot hide in a
;; marker; the printed form reaches a value inside a host object `tree-seq`
;; cannot open.
(defn- embeds? [data raw]
  (some #(and (string? %) (str/includes? % raw))
        (tree-seq coll? seq [data (pr-str data)])))

(defn- reg-app! []
  (rf/reg-machine ::holder
    {:initial   :holding
     :data      {}
     :sensitive [[:data :items :token]]
     :states    {:holding {}}})
  (rf/reg-event ::replied
    {:doc "Stores the reply and hands its items to a machine."}
    (fn [{:keys [db]} [_ reply]]
      {:db (assoc db ::reply reply)
       :fx [[:rf.machine/spawn {:machine-id     ::holder
                                :fixed-actor-id actor-id
                                :data           {:items (get-in reply [:value :items])}}]]}))
  (rf/reg-event ::fetch
    {:doc "Fetches the items."}
    (fn [_ _]
      {:fx [[:rf.http/managed {:request  {:url url}
                               :decode   Items
                               :reply-to [::replied]}]]})))

(defn- traces-of
  "Run `f` with every trace event collected; return the events."
  [f]
  (let [traces (atom [])
        id     (keyword (gensym "one-secret"))]
    (rf/register-listener! :trace id #(swap! traces conj %))
    (try (f) @traces
         (finally (rf/unregister-listener! :trace id)))))

(defn- off-box
  "`rows` as an off-box epoch projection ships them."
  [rows]
  (:trace-events (rf/project-egress {:kind         :rf/epoch-record
                                     :frame        frame-id
                                     :trace-events rows}
                                    {:rf.egress/profile :rf.egress/off-box-tool})))

(deftest ^:requires-debug one-declared-token-is-redacted-at-every-egress-it-reaches
  (reg-app!)
  (rf/make-frame {:id frame-id :platform :server})
  (let [traces   (traces-of
                   #(rf.http.stub-transport/with-transport
                      (rf.http.stub-transport/route-transport
                        {[:get url] {:body {:items [{:id 1 :token token} {:id 2 :token token}]}}})
                      (fn [] (rf/dispatch-sync [::fetch] {:frame frame-id}))))
        rows     (filterv #(= "rf.http" (namespace (:operation %))) traces)
        snapshot (get-in (rf/frame-state-value frame-id)
                         (into [:rf.db/runtime] (rf.machines.paths/snapshot-path actor-id)))
        payload  (rf.ssr/hydration-payload frame-id {:payload []})
        redacted [{:id 1 :token :rf/redacted} {:id 2 :token :rf/redacted}]]
    (testing "detector control: the scan finds the token where it is raw by design"
      (is (embeds? (::reply (rf/app-db-value frame-id)) token) "the app's delivered reply")
      (is (embeds? (:data snapshot) token) "the live machine snapshot's :data"))
    (testing "every :rf.http/* trace row, as delivered and as projected off-box"
      (is (= [redacted]
             (keep #(when (= :rf.http/replied (:operation %)) (get-in % [:tags :value :items])) rows))
          "the reply row carries the decoded body, the marked slot redacted in every element")
      (is (not (embeds? rows token)))
      (is (not (embeds? (off-box rows) token))))
    (testing "the serialised hydration payload"
      (is (= redacted
             (get-in payload [:rf/runtime-db :rf.runtime/machines :snapshots actor-id :data :items]))
          "the payload carries the machine's snapshot, the declared slot redacted in every element")
      (is (not (embeds? (pr-str payload) token))))))
