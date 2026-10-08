(ns day8.re-frame2-xray.filters.matcher-cljs-test
  "Pure-data tests for the event-id pattern matcher and the frame /
  view-scope filters."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.filters.matcher :as matcher]))

;; ---- normalise-pattern: string forms ------------------------------------

(deftest normalise-pattern-string-with-leading-colon
  (testing "string starting with `:` parses as a keyword first"
    (is (= {:kind :exact :pattern :auth/login}
           (#'matcher/normalise-pattern ":auth/login")))
    (is (= {:kind :prefix :pattern ":auth/"}
           (#'matcher/normalise-pattern ":auth/*")))))

(deftest normalise-pattern-bare-string-glob-is-prefix
  (testing "a colon-less string ending in `*` is the keyword glob with the
            `:` dropped. As a :substring it would match nothing: no
            `(str event-id)` contains a `*`."
    (is (= {:kind :prefix :pattern ":auth/"}
           (#'matcher/normalise-pattern "auth/*")))))

;; ---- match-event-id? ----------------------------------------------------

(deftest match-event-id-exact
  (let [spec (#'matcher/normalise-pattern :auth/login)]
    (is (matcher/match-event-id? :auth/login spec))
    (is (not (matcher/match-event-id? :auth/logout spec)))))

(deftest match-event-id-bare-keyword-is-exact-or-namespace
  (testing "the Add-filter dialog's `:auth` example matches the namespace AND
            the bare id, and nothing else"
    (let [spec (#'matcher/normalise-pattern :auth)]
      (is (matcher/match-event-id? :auth/login spec))
      (is (matcher/match-event-id? :auth spec))
      (is (not (matcher/match-event-id? :authors/x spec))
          "a namespace is matched whole, never as a string prefix")
      (is (not (matcher/match-event-id? nil spec)))))
  (testing "spec/018 §7's example pill `[× :mouse-move]` blocks the bare
            event-id, and a qualified id only through its NAMESPACE"
    (let [spec (#'matcher/normalise-pattern :mouse-move)]
      (is (matcher/match-event-id? :mouse-move spec))
      (is (not (matcher/match-event-id? :user/mouse-move spec))))))

(deftest match-event-id-substring
  (let [spec (#'matcher/normalise-pattern "/login")]
    (is (matcher/match-event-id? :user/login-clicked spec))
    (is (not (matcher/match-event-id? :auth/logout spec)))))

(deftest match-event-id-never-matches-nothing
  (testing "a blank pattern matches nothing — as a substring it would match
            every event"
    (is (not (matcher/match-event-id? :auth/login (#'matcher/normalise-pattern ""))))))

;; ---- frame-picker filter ------------------------------------------------

(deftest filter-event-bundles-by-frame-nil-is-identity
  (let [cascades [{:dispatch-id 1 :frame :cart-frame}
                  {:dispatch-id 2 :frame :checkout-frame}
                  {:dispatch-id 3 :frame nil}]]
    (is (= cascades (matcher/filter-event-bundles-by-frame cascades nil)))))

(deftest filter-event-bundles-by-frame-restricts-and-preserves-order
  (testing "keeps only the picked frame's cascades, in order; frameless
            `:ungrouped` cascades drop so the list matches the picker label"
    (let [cascades [{:dispatch-id 1 :frame :cart-frame}
                    {:dispatch-id :ungrouped :frame nil}
                    {:dispatch-id 2 :frame :checkout-frame}
                    {:dispatch-id 3 :frame :cart-frame}]]
      (is (= [1 3] (mapv :dispatch-id
                         (matcher/filter-event-bundles-by-frame cascades :cart-frame)))))))

;; ---- view-scope filter --------------------------------------------------

(deftest filter-event-bundles-by-view-scope-preserves-frameless-bucket
  (testing "unlike the strict frame filter, the view scope keeps the
            frameless `:ungrouped` bucket (its render is gated by
            show-ungrouped?)"
    (let [cascades [{:dispatch-id 1 :frame :cart-frame}
                    {:dispatch-id :ungrouped :frame nil}
                    {:dispatch-id 2 :frame :other-frame}
                    {:dispatch-id 3 :frame :cart-frame}]]
      (is (= [1 :ungrouped 3]
             (mapv :dispatch-id
                   (matcher/filter-event-bundles-by-view-scope cascades :cart-frame)))))))

(deftest filter-event-bundles-by-view-scope-nil-is-identity
  (let [cascades [{:dispatch-id 1 :frame :a} {:dispatch-id 2 :frame :b}]]
    (is (= cascades (matcher/filter-event-bundles-by-view-scope cascades nil)))))
