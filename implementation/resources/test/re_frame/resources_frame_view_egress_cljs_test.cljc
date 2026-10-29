(ns re-frame.resources-frame-view-egress-cljs-test
  "Off-box egress of the resource family resolves each owner in the projected
  frame's own registration view. A frame whose image selects a classified
  registration keeps that classification even when another namespace registers
  the same id unclassified, which makes the unclassified one the id's latest
  registration anywhere. Spec 015 §Subsystem projection-relative
  classification.

  Each surface is pinned for two frames over the same ids: the private frame,
  whose image selects the `:sensitive?` registrations, and the public frame,
  whose image selects the unclassified ones, so a redaction can only come from
  the frame each projection names."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [clojure.string :as str]
   [re-frame.core :as rf]
   ;; load-bearing: publishes the `:rf/epoch-record` projector `project-egress`
   ;; dispatches to.
   [re-frame.epoch.tool-pair]
   [re-frame.image :as rf.image]
   [re-frame.live-frame :as rf.live-frame]
   [re-frame.resources]
   [re-frame.resources.mutation-registry :as rf.resources.mutation-registry]
   [re-frame.resources.registry :as rf.resources.registry]
   [re-frame.resources.trace-egress :as rf.resources.trace-egress]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(def ^:private secret "FRAME-VIEW-SENTINEL-0qb2a")

(def ^:private private-frame :frame-view/private)
(def ^:private public-frame :frame-view/public)

(def ^:private mutation-id :frame-view/save)
(def ^:private resource-id :frame-view/article)

(defn- request-fn [_params _ctx] {:request {:method :post :url "/x"}})

(defn- reg-owners!
  "Registers the mutation and the resource in namespace `ns-name` with `claim`
  merged in, through their owning fns, so the stamped `:ns` is their only
  provenance and an image's `:select-ns` reads it."
  [ns-name claim]
  (let [spec (merge {:params-schema [:map [:secret :string]] :ns ns-name} claim)]
    (rf.resources.mutation-registry/reg-mutation mutation-id spec request-fn)
    (rf.resources.registry/reg-resource resource-id (assoc spec :scope :rf.scope/global) request-fn)))

(defn- frame-selecting [frame-id ns-name]
  (rf/make-frame {:id     frame-id
                  :images [(rf.image/image {:id         (keyword "frame-view.image" ns-name)
                                            :select-ns  {:include [ns-name]}})]}))

(defn- init! []
  ;; The public registrations land last, so they are each id's latest
  ;; registration and what an unbound lookup returns.
  (reg-owners! "frame-view.private" {:sensitive? true})
  (reg-owners! "frame-view.public" {})
  (frame-selecting private-frame "frame-view.private")
  (frame-selecting public-frame "frame-view.public"))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter substrate/adapter
     :init-fn init!}))

;; ---- helpers --------------------------------------------------------------

(def ^:private secret-params {:secret secret})

(def ^:private scoped-key [:rf.scope/global resource-id secret-params])

(defn- redacted-token? [v] (and (map? v) (contains? v :rf/redacted)))

(defn- leaks? [v] (str/includes? (pr-str v) secret))

(defn- record
  "An epoch record of `frame-id` carrying `trace-events`."
  [frame-id trace-events]
  {:kind :rf/epoch-record :frame frame-id :trace-events trace-events})

(defn- reply-carrier-row
  "An `:rf.fx/handled` row dispatching `reply` to an app target that declares
  nothing."
  [frame-id reply]
  {:operation :rf.fx/handled
   :op-type   :rf.fx
   :tags      {:rf.frame/id frame-id
               :rf.fx/id    :dispatch
               :rf.fx/args  [:app/replied reply]}})

(defn- carried-reply
  "The reply on `reply`'s carrier row, projected off-box for `frame-id` under
  the trusted-local fx-argument opt-in, with `:rf.egress/include-sensitive?`
  left out."
  [frame-id reply]
  (-> (rf/project-egress (record frame-id [(reply-carrier-row frame-id reply)])
                         {:rf.egress/include-fx-args? true})
      :trace-events first :tags :rf.fx/args second))

(def ^:private mutation-reply
  {:rf.reply/work-kind :mutation
   :mutation           mutation-id
   :params             secret-params
   :value              secret-params})

(def ^:private read-reply
  {:rf.reply/work-kind :resource
   :resource/key       scoped-key
   :resource           resource-id
   :params             secret-params
   :value              secret-params})

;; ---- tests ----------------------------------------------------------------

(deftest fixture-each-frame-selects-its-own-registration
  (testing "the unbound lookup returns the unclassified registration"
    (is (nil? (:sensitive? (rf.resources.mutation-registry/mutation-meta mutation-id))))
    (is (nil? (:sensitive? (rf.resources.registry/resource-meta resource-id)))))
  (testing "the private frame's view selects the :sensitive? claim"
    (is (= [true true]
           (rf.live-frame/call-with-frame-resolution
             private-frame
             #(vector (:sensitive? (rf.resources.mutation-registry/mutation-meta mutation-id))
                      (:sensitive? (rf.resources.registry/resource-meta resource-id)))))))
  (testing "the public frame's view selects the unclassified registrations"
    (is (= [nil nil]
           (rf.live-frame/call-with-frame-resolution
             public-frame
             #(vector (:sensitive? (rf.resources.mutation-registry/mutation-meta mutation-id))
                      (:sensitive? (rf.resources.registry/resource-meta resource-id))))))))

(deftest a-mutation-reply-classifies-by-its-frames-registration
  (testing "the private frame's :sensitive? owner tokenizes the reply"
    (let [reply (carried-reply private-frame mutation-reply)]
      (is (redacted-token? (:params reply)))
      (is (redacted-token? (:value reply)))
      (is (not (leaks? reply)))))
  (testing "the public frame's unclassified owner leaves the reply readable"
    (let [reply (carried-reply public-frame mutation-reply)]
      (is (= secret-params (:params reply)))
      (is (= secret-params (:value reply))))))

(deftest a-read-reply-classifies-by-its-frames-registration
  (testing "the private frame's :sensitive? owner tokenizes the reply"
    (let [reply (carried-reply private-frame read-reply)]
      (is (redacted-token? (:params reply)))
      (is (redacted-token? (:value reply)))
      (is (not (leaks? reply)))))
  (testing "the public frame's unclassified owner leaves the reply readable"
    (let [reply (carried-reply public-frame read-reply)]
      (is (= secret-params (:params reply)))
      (is (= secret-params (:value reply))))))

(deftest a-family-row-classifies-by-its-frames-registration
  ;; Family rows project at the off-box default, with no opt-in.
  (let [row-tags (fn [frame-id]
                   (-> (rf/project-egress
                         (record frame-id
                                 [{:operation :rf.resource/ensured
                                   :op-type   :rf.resource
                                   :tags      {:rf.frame/id  frame-id
                                               :resource/key scoped-key
                                               :resource/id  resource-id}}])
                         {})
                       :trace-events first :tags))]
    (testing "the private frame's :sensitive? owner tokenizes the key's params"
      (let [tags (row-tags private-frame)]
        (is (redacted-token? (nth (:resource/key tags) 2)))
        (is (true? (:sensitive? tags)))
        (is (not (leaks? tags)))))
    (testing "the public frame's unclassified owner leaves the key readable"
      (is (= scoped-key (:resource/key (row-tags public-frame)))))))

(deftest a-read-sub-classifies-by-its-frames-registration
  (let [project (fn [frame-id]
                  (rf.resources.trace-egress/project-read-sub-egress
                    [:rf.resource/data {:resource resource-id :params secret-params}]
                    secret-params
                    {:frame frame-id}))]
    (is (= :rf/redacted (project private-frame))
        "the private frame's :sensitive? owner redacts the value")
    (is (= secret-params (project public-frame))
        "the public frame's unclassified owner leaves the value readable")))
