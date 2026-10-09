(ns re-frame.epoch-egress-trace-events-test
  "Off-box projection of an epoch record's `:trace-events` slot: the focused
  slices a path interceptor stamps into `:rf.event/after-deltas`, and the
  fail-closed rule for HTTP response bodies. The HTTP emit site stamps each
  body's off-box disposition forward under `:tags :rf.http/off-box-body`
  (`:omit` for an unschematized body, `:classify` for one already marked
  on-box) and `omit-off-box-http-bodies` enforces it; the records here are
  hand-built carrying that stamp, so no HTTP artefact is loaded."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.epoch]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- leaks? [x needle] (.contains (pr-str x) ^String needle))

(def ^:private secret "topsecret-do-not-leak")

(deftest off-box-projection-of-an-auth-focused-event-omits-its-slice-secret
  (testing "a handler focused AT the classified subtree
            ([:rf.interceptor/path [:auth]] writing :password) puts focused
            slices into :rf.event/after-deltas, where a root-anchored walk
            cannot match [:auth :password]. The off-box-tool projection of the
            record carries the secret nowhere"
    (rf/make-frame {:id :test/eg})
    (rf.frame/swap-runtime-db! :test/eg
      #(rf.elision/apply-classification-effects % {:sensitive [[:auth :password]]}))
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db (assoc db :auth {:password secret})}))
    (rf/reg-event :rotate {:interceptors [[:rf.interceptor/path [:auth]]]}
      (fn [{:keys [db]} _] {:db (assoc db :password (str secret "-rotated"))}))
    (rf/dispatch-sync [:seed] {:frame :test/eg})
    (rf/dispatch-sync [:rotate] {:frame :test/eg})
    (let [raw     (last (rf/epoch-history :test/eg))
          run-end (first (filter #(= :rf.event/run-end (:operation %)) (:trace-events raw)))]
      (is (= [:rf.interceptor/path]
             (mapv :rf.interceptor.delta/id (get-in run-end [:tags :rf.event/after-deltas])))
          "control: the newest record carries the path interceptor's after-delta")
      (is (not (leaks? (rf/project-egress raw {:rf.egress/profile :rf.egress/off-box-tool}) secret))))))

;; ---- off-box HTTP response-body fail-closed --------------------------------

(def ^:private http-secret "raw-bearer-token-do-not-leak")

(defn- http-record
  "An epoch record whose `:trace-events` carries one `operation` row with `tags`."
  [operation tags]
  {:kind         :rf/epoch-record
   :epoch-id     1
   :frame        :test/http
   :trace-events [{:op-type :rf.trace :operation operation :tags tags}]})

(defn- projected-tags [record opts]
  (:tags (first (:trace-events (rf/project-egress record opts)))))

(deftest off-box-omits-only-an-explicit-omit-stamp
  (testing "an :omit-stamped body redacts off-box, at a nested slot too (a
            retry-attempt's intermediate failure body), leaving its siblings;
            a :classify-stamped body (its sensitive slots already redacted
            on-box) and an unstamped one ride untouched"
    (rf/make-frame {:id :test/http})
    (are [operation tags expected] (= expected (projected-tags (http-record operation tags) nil))
      :rf.http/retry-attempt {:failure {:status 500 :body http-secret} :rf.http/off-box-body :omit}
                             {:failure {:status 500 :body :rf/redacted} :rf.http/off-box-body :omit}
      :rf.http/replied       {:value {:token :rf/redacted :user-id 42} :rf.http/off-box-body :classify}
                             {:value {:token :rf/redacted :user-id 42} :rf.http/off-box-body :classify}
      :rf.http/replied       {:value {:k "v"}}
                             {:value {:k "v"}})))

(deftest local-raw-profile-lifts-omission-without-an-explicit-key
  (testing "the omit seams read the sensitive axis by key presence, so the
            profile floor is resolved once at the record boundary: the
            local-raw PROFILE alone lifts the omission, and an explicit false
            still overlays that floor and wins"
    (rf/make-frame {:id :test/http})
    (let [body   {:token http-secret :user-id 42}
          record (http-record :rf.http/replied {:value body :rf.http/off-box-body :omit})]
      (is (= [:rf/redacted body :rf/redacted]
             (mapv #(:value (projected-tags record %))
                   [{:rf.egress/profile :rf.egress/off-box-observability}
                    {:rf.egress/profile :rf.egress/local-raw}
                    {:rf.egress/profile          :rf.egress/local-raw
                     :rf.egress/include-sensitive? false}]))))))

(deftest off-box-http-fail-closed-survives-cold-gate-false
  (testing "project-egress is a pure transform a production JVM calls with the
            debug gate false FROM namespace load, so the five production HTTP
            rows of the body-slot table bind regardless of the gate. A table
            loaded gate-true and then flipped would not show this, so
            tool-pair is RE-LOADED under a false gate, as a cold start loads it"
    (rf/make-frame {:id :test/http})
    (try
      (with-redefs [rf.interop/debug-enabled? false]
        (require 're-frame.epoch.tool-pair :reload))
      (is (= (repeat 5 :rf/redacted)
             (for [[operation slot] [[:rf.http/replied        :value]
                                     [:rf.http/accept-failure :decoded]
                                     [:rf.http/http-4xx       :body]
                                     [:rf.http/http-5xx       :body]
                                     [:rf.http/decode-failure :body-text]]]
               (get (projected-tags (http-record operation {slot                  {:token http-secret}
                                                            :rf.http/off-box-body :omit})
                                    nil)
                    slot))))
      (finally
        ;; Restore the gate-true table, which carries the dev-only retry row.
        (require 're-frame.epoch.tool-pair :reload)))))
