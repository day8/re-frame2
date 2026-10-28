(ns ^:requires-debug re-frame.epoch-egress-trace-events-test
  "Off-box projection of an epoch record's `:trace-events` slot: the focused
  slices a path interceptor stamps into `:rf.event/after-deltas`, HTTP response
  bodies, and the trace-tag allow-list (`re-frame.projection/trace-tag-allow-
  list`) that decides what any tag ships. The records here are hand-built, so
  no HTTP artefact is loaded.

  ## Production gate

  Dev-only, so the `(ns ...)` form carries `^:requires-debug`: the
  `:prod-gate` lane loads this namespace and runs none of it. Class: SUBJECT
  ELIDED. What it asserts about — a recorded epoch, the ring, the
  capture buffer, or a record read back out of them — is assembled only
  while `interop/debug-enabled?` is on, so under `-Dre-frame.debug=false`
  there is no subject. Epoch's production posture is that the
  artefact is off, so that is the design, not a defect."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as rf.epoch]
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
    (rf/reg-classification ::classified {:sensitive [[:auth :password]]})
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db (assoc db :auth {:password secret})}))
    (rf/reg-event :rotate {:interceptors [[:rf.interceptor/path [:auth]]]}
      (fn [{:keys [db]} _] {:db (assoc db :password (str secret "-rotated"))}))
    (rf/dispatch-sync [:seed] {:frame :test/eg})
    (rf/dispatch-sync [:rotate] {:frame :test/eg})
    (let [raw     (last (rf.epoch/epoch-history :test/eg))
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

(deftest off-box-http-body-ships-only-as-its-schema-classified-it
  (testing "an HTTP body slot is one the trace-tag allow-list never names, so an
            :omit-stamped body ships :rf/redacted, a retry-attempt's nested
            intermediate failure included; a :classify-stamped body, whose
            sensitive slots its :decode schema already redacted on-box, ships
            as classified"
    (rf/make-frame {:id :test/http})
    (are [operation tags expected] (= expected (projected-tags (http-record operation tags) nil))
      :rf.http/retry-attempt  {:failure {:http-status 500 :body http-secret} :rf.http/off-box-body :omit}
                              {:failure :rf/redacted :rf.http/off-box-body :rf/redacted}
      :rf.http/replied        {:value {:token :rf/redacted :user "ada"} :rf.http/off-box-body :classify}
                              {:value {:token :rf/redacted :user "ada"} :rf.http/off-box-body :rf/redacted}
      :rf.http/accept-failure {:decoded {:status "rejected"} :rf.http/off-box-body :classify}
                              {:decoded {:status "rejected"} :rf.http/off-box-body :rf/redacted}
      :rf.http/http-4xx       {:body {:status "rejected"} :rf.http/off-box-body :classify}
                              {:body :rf/redacted :rf.http/off-box-body :rf/redacted})))

(deftest local-raw-profile-lifts-omission-without-an-explicit-key
  (testing "the allow-list reads the sensitive axis by key presence, so the
            profile floor is resolved once at the record boundary: the
            local-raw PROFILE alone lifts it, and an explicit false still
            overlays that floor and wins"
    (rf/make-frame {:id :test/http})
    (let [body   {:token http-secret :user-id 42}
          record (http-record :rf.http/replied {:value body :rf.http/off-box-body :omit})]
      (is (= [:rf/redacted body :rf/redacted]
             (mapv #(:value (projected-tags record %))
                   [{:rf.egress/profile :rf.egress/off-box-observability}
                    {:rf.egress/profile :rf.egress/local-raw}
                    {:rf.egress/profile          :rf.egress/local-raw
                     :rf.egress/include-sensitive? false}]))))))

(deftest off-box-http-bodies-redact-from-a-cold-gate-false-load
  (testing "project-egress is a pure transform a production JVM calls with the
            debug gate false FROM namespace load, so the allow-list redacts
            every production HTTP body regardless of the gate. A projection
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
        ;; Reload under the true gate the rest of the suite runs with.
        (require 're-frame.epoch.tool-pair :reload)))))

;; ---- the off-box trace-tag allow-list ----------------------------------------

(defn- tags-record
  "An epoch record for `:test/al` whose `:trace-events` carries `events`, each a
  `[operation tags]` pair."
  [events]
  {:kind         :rf/epoch-record
   :epoch-id     1
   :frame        :test/al
   :trace-events (mapv (fn [[operation tags]] {:op-type :rf.trace :operation operation :tags tags})
                       events)})

(def ^:private off-box {:rf.egress/profile :rf.egress/off-box-tool})

(deftest an-unknown-tag-ships-redacted-off-box
  (testing "a tag no allow-list entry names ships as :rf/redacted, whatever it
            carries; an allow-listed tag ships only a structural value"
    (rf/make-frame {:id :test/al})
    (is (= {:frame                 :test/al
            :rf.trace/dispatch-id  7
            :app/note              :rf/redacted
            :app/flag              :rf/redacted
            :rf.sub/id             :rf/redacted}
           (projected-tags (tags-record [[:app/custom {:frame                :test/al
                                                       :rf.trace/dispatch-id 7
                                                       :app/note             secret
                                                       :app/flag             :on
                                                       :rf.sub/id            secret}]])
                           off-box)))))

(deftest query-vector-args-redact-and-their-ids-survive
  (testing "a query vector is identity, and its args are user values: the head
            id ships, every arg is :rf/redacted, on each query-vector carrier"
    (rf/make-frame {:id :test/al})
    (is (= {:frame                     :test/al
            :rf.sub/id                 :user/by-email
            :rf.sub/query-v            [:user/by-email :rf/redacted]
            :rf.sub/input-signals      [[:session :rf/redacted] [:users]]
            :rf.view/deref-subs        [[:user/by-email :rf/redacted] [:todos]]}
           (projected-tags (tags-record [[:rf.sub/run {:frame                :test/al
                                                       :rf.sub/id            :user/by-email
                                                       :rf.sub/query-v       [:user/by-email "a@b.com"]
                                                       :rf.sub/input-signals [[:session 42] [:users]]
                                                       :rf.view/deref-subs   [[:user/by-email "a@b.com"]
                                                                              [:todos]]}]])
                           off-box)))))

(def ^:private render-operations
  "The operations re-frame2-pair-mcp's `project-dispatch-result-src` filters a
  projected epoch's `:trace-events` down to when it re-derives `:render-events`."
  #{:rf.view/render :rf.view/rendered :rf.view/rendered-cap-reached :rf.view/unmounted})

(deftest the-render-events-derivation-is-unchanged
  (testing "the render tags the pair MCP's :render-events derivation ships are
            allow-listed, so filtering the projected record by operation yields
            the same render events it always has"
    (rf/make-frame {:id :test/al})
    (let [render-key [:app/row 3]
          record     (tags-record
                       [[:rf.event/run-start {:frame :test/al :rf.event/v [:row/toggle]}]
                        [:rf.view/render     {:frame :test/al :rf.view/render-key render-key}]
                        [:rf.view/rendered   {:frame                  :test/al
                                              :rf.view/id             :app/row
                                              :rf.view/render-key     render-key
                                              :rf.view/mount?         false
                                              :rf.view/deref-subs     [[:rows]]
                                              :rf.view/cause-subs     [[:rows]]
                                              :rf.view/triggered-by   [:rows]
                                              :rf.view/cause-event-id :row/toggle
                                              :rf.view/elapsed-ms     0.4}]
                        [:rf.view/rendered-cap-reached {:frame :test/al :rf.view/dropped-after 100}]
                        [:rf.view/unmounted  {:frame              :test/al
                                              :rf.view/id         :app/row
                                              :rf.view/render-key render-key}]])
          derive     #(filterv (comp render-operations :operation) (:trace-events %))]
      (is (= (derive record) (derive (rf/project-egress record off-box)))))))

(deftest trusted-local-output-is-unchanged
  (testing "under the trusted-local sensitive opt-in no allow-list applies: an
            unknown tag and a query vector's args ride as they did, while the
            event-args and fx-args axes keep their own fail-closed defaults"
    (rf/make-frame {:id :test/al})
    (let [record (tags-record [[:app/custom   {:frame :test/al :app/note secret}]
                               [:rf.sub/run   {:frame :test/al :rf.sub/query-v [:user/by-email "a@b.com"]}]
                               [:rf.event/run-start {:frame :test/al :rf.event/v [:login secret]}]
                               [:rf.fx/handled {:frame :test/al :rf.fx/id :http :rf.fx/args {:body secret}}]])]
      (is (= [{:frame :test/al :app/note secret}
              {:frame :test/al :rf.sub/query-v [:user/by-email "a@b.com"]}
              {:frame :test/al :rf.event/v [:login :rf/redacted]}
              {:frame :test/al :rf.fx/id :http :rf.fx/args :rf/redacted}]
             (mapv :tags (:trace-events (rf/project-egress record {:rf.egress/profile :rf.egress/local-raw}))))))))
