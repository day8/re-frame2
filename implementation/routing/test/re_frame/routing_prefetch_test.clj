(ns re-frame.routing-prefetch-test
  "EP-0037 R3 — resource-only intent prefetch: the PURE routing surfaces.

  The always-on prefetch-address structural gate (`address/prefetch-address-
  error`) and the pure `[:rf.route/prefetch …]` payload synthesis
  (`link/prefetch-payload`) the `rf/route-link` intent handlers + the substrate-
  neutral `:routing/prefetch-payload` seam are built on. The event's warm-plan
  behaviour (isolation, dedupe, reuse, planning failure) is proven end-to-end by
  the `ep-0037-r3-prefetch-*` conformance fixtures; the DOM intent arm (hover /
  focus / touch → dispatch) is proven by the CLJS route-link tests. Per Spec 012
  §Route-plan prefetch."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.routing :as routing]
            [re-frame.routing.address :as address]
            [re-frame.routing.link :as link]
            [re-frame.routing-test-support :as rts]))

(use-fixtures :each rts/reset-runtime)

(deftest prefetch-address-error-accepts-only-a-closed-route-address
  (testing "a well-formed named :rf/route-address passes the gate"
    (is (nil? (address/prefetch-address-error {:to :route/article})))
    (is (nil? (address/prefetch-address-error {:to :route/article :params {:slug "x"}})))
    (is (nil? (address/prefetch-address-error {:to :route/article :params {:slug "x"}
                                               :query {:tab "comments"} :fragment "reply"})))
    (is (nil? (address/prefetch-address-error {:to :route/article :fragment nil}))))

  (testing "a raw :url escape is NOT a RouteAddress — rejected as an unknown key"
    (is (= {:reason :unknown-keys :keys [:url]}
           (address/prefetch-address-error {:url "/articles/x"}))))

  (testing "a policy / edit key is rejected as unknown (prefetch takes address only)"
    (is (= {:reason :unknown-keys :keys [:replace?]}
           (address/prefetch-address-error {:to :route/article :replace? true})))
    (is (= {:reason :unknown-keys :keys [:query-merge]}
           (address/prefetch-address-error {:to :route/article :query-merge {:tab "x"}}))))

  (testing "a missing / non-keyword :to is rejected before planning"
    (is (= {:reason :missing-to :keys [:to]}
           (address/prefetch-address-error {:params {:slug "x"}})))
    (is (= {:reason :missing-to :keys [:to]}
           (address/prefetch-address-error {:to "route/article"}))))

  (testing "a structurally-wrong address value (non-map :params) is a bad address"
    (is (= {:reason :bad-address :keys []}
           (address/prefetch-address-error {:to :route/article :params [:not :a :map]}))))

  (testing "a non-map request rejects (never reaches planning)"
    (is (= {:reason :request-not-a-map :keys []}
           (address/prefetch-address-error nil)))))

(deftest prefetch-payload-synthesises-the-event-only-on-intent-opt-in
  (testing "a link that opts into :prefetch :intent yields the address-only event"
    (is (= [:rf.route/prefetch {:to :route/article :params {:slug "x"}}]
           (link/prefetch-payload {:to :route/article :params {:slug "x"}
                                   :prefetch :intent :class "title"})))
    (is (= [:rf.route/prefetch {:to :route/article :params {:slug "x"} :query {:tab "c"}}]
           (link/prefetch-payload {:to :route/article :params {:slug "x"}
                                   :query {:tab "c"} :prefetch :intent}))))

  (testing "the payload carries ONLY the address — no policy / DOM / behaviour keys leak"
    (is (= [:rf.route/prefetch {:to :route/article}]
           (link/prefetch-payload {:to :route/article :prefetch :intent
                                   :class "title" :on-click identity
                                   :on-mouse-enter identity}))))

  (testing "an ABSENT :prefetch yields nil — passive by default, and the ONLY
            way to be passive"
    (is (nil? (link/prefetch-payload {:to :route/article})))))

;; ---- the :prefetch behaviour value is VALIDATED, not silently stripped -----

(defn- bad-prefetch-ex-data
  "The `ex-data` of the throw a link calculation raises for `props`, or nil if
  it did not throw."
  [calc props]
  (try (calc props) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest a-present-but-unsupported-prefetch-value-fails-loud
  (testing ":intent is the ONLY accepted value (Spec 012 §:prefetch :intent) —
            an unsupported mode is a caller bug, NOT a silently passive link.
            Returning nil for it made `:prefetch :render` and a plain typo
            indistinguishable from a link that never asked to prefetch."
    (doseq [v [true false nil :render :viewport :hover "intent" 1]]
      (let [data (bad-prefetch-ex-data link/prefetch-payload
                                       {:to :route/article :prefetch v})]
        (is (= :rf.error/route-link-bad-prefetch (:rf.error/id data))
            (str "prefetch value " (pr-str v) " must fail loud"))
        (is (= :prefetch (:slot data)))
        (is (= :intent (:accepted data)))
        (is (= 'rf/route-link (:where data)))))
    (testing "and the message is didactic — it names the accepted value and the
              way to be passive"
      (let [msg (try (link/prefetch-payload {:to :route/article :prefetch :render})
                     (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (re-find #":intent" msg))
        (is (re-find #"(?i)omit" msg)))))

  (testing ":prefetch :intent and an absent key both pass validation"
    (is (nil? (link/validate-prefetch! {:to :route/article :prefetch :intent})))
    (is (nil? (link/validate-prefetch! {:to :route/article})))))

(deftest every-link-surface-validates-the-prefetch-value-on-both-hosts
  ;; `href-attrs` is private but is reached through both `rf/route-link` render
  ;; halves; `route-link-render-ssr` is the JVM half and is public, so the SSR
  ;; shell is exercised directly here. `link-model` is the one calculation the
  ;; compiled `ui/route-link` runs on both hosts.
  (testing "the rf/route-link SSR shell rejects it (it used to render the anchor
            with the bad value merely stripped)"
    (is (= :rf.error/route-link-bad-prefetch
           (:rf.error/id (bad-prefetch-ex-data
                           #(link/route-link-render-ssr %)
                           {:to :route/article :prefetch :render})))))
  (testing "the ui/route-link model rejects it on the JVM too — the arm that
            never called prefetch-payload server-side"
    (is (= :rf.error/route-link-bad-prefetch
           (:rf.error/id (bad-prefetch-ex-data
                           #(link/link-model % :rf/default)
                           {:to :route/article :prefetch true})))))
  (testing "and a valid :intent link still renders / models normally, with
            :prefetch stripped before DOM emission"
    (routing/reg-route :route/article {} "/articles/:slug")
    (let [props {:to :route/article :params {:slug "x"} :prefetch :intent :class "t"}
          [_tag attrs] (link/route-link-render-ssr props)]
      (is (= "/articles/x" (:href attrs)))
      (is (not (contains? attrs :prefetch)) ":prefetch never reaches the <a>")
      (is (= "t" (:class attrs)) "passthrough attrs survive"))
    (is (= "/articles/x" (:href (link/link-model {:to :route/article
                                                  :params {:slug "x"}
                                                  :prefetch :intent}
                                                 :rf/default))))))
