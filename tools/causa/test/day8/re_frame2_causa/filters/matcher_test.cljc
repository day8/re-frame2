(ns day8.re-frame2-causa.filters.matcher-test
  "Pure-data tests for the IN/OUT filter matcher (rf2-ak4ms).

  CLJC so the JVM corpus exercises the matcher without a CLJS
  runtime — the matcher is pure data, no atoms, no I/O."
  (:require [clojure.test :refer [deftest is testing]]
            [day8.re-frame2-causa.filters.matcher :as matcher]))

;; ---- normalise-pattern --------------------------------------------------

(deftest normalise-pattern-exact-keyword
  (is (= {:kind :exact :pattern :auth/login}
         (#'matcher/normalise-pattern :auth/login))))

(deftest normalise-pattern-prefix-glob
  (is (= {:kind :prefix :pattern ":auth/"}
         (#'matcher/normalise-pattern :auth/*)))
  (is (= {:kind :prefix :pattern ":order.cart/"}
         (#'matcher/normalise-pattern :order.cart/*))))

(deftest normalise-pattern-bare-keyword-is-exact
  (testing "a bare unqualified keyword compiles to :exact — spec/018 §7's
            example pill `[× :mouse-move]` lands there"
    (is (= {:kind :exact :pattern :mouse-move}
           (#'matcher/normalise-pattern :mouse-move)))))

(deftest normalise-pattern-string-with-leading-colon
  (testing "string starting with `:` parses as a keyword first"
    (is (= {:kind :exact :pattern :auth/login}
           (#'matcher/normalise-pattern ":auth/login")))
    (is (= {:kind :prefix :pattern ":auth/"}
           (#'matcher/normalise-pattern ":auth/*")))))

(deftest normalise-pattern-bare-substring
  (testing "bare string (no leading `:`) compiles to :substring"
    (is (= {:kind :substring :pattern "/login"}
           (#'matcher/normalise-pattern "/login")))))

(deftest normalise-pattern-blank-is-never
  (testing "blank pattern compiles to :never (guards a half-filled pill)"
    (is (= {:kind :never :pattern nil}
           (#'matcher/normalise-pattern "")))
    (is (= {:kind :never :pattern nil}
           (#'matcher/normalise-pattern "   ")))
    (is (= {:kind :never :pattern nil}
           (#'matcher/normalise-pattern nil)))))

;; ---- match-event-id? ----------------------------------------------------

(deftest match-event-id-exact
  (let [spec (#'matcher/normalise-pattern :auth/login)]
    (is (matcher/match-event-id? :auth/login spec))
    (is (not (matcher/match-event-id? :auth/logout spec)))
    (is (not (matcher/match-event-id? nil spec)))))

(deftest match-event-id-prefix-glob
  (let [spec (#'matcher/normalise-pattern :auth/*)]
    (is (matcher/match-event-id? :auth/login spec))
    (is (matcher/match-event-id? :auth/logout spec))
    (is (matcher/match-event-id? :auth/anything spec))
    (is (not (matcher/match-event-id? :order/submit spec)))))

(deftest match-event-id-bare-keyword-is-exact
  (let [spec (#'matcher/normalise-pattern :mouse-move)]
    (is (matcher/match-event-id? :mouse-move spec))
    (is (not (matcher/match-event-id? :other-event spec)))
    (is (not (matcher/match-event-id? :user/mouse-move spec))
        "unqualified pattern does not match qualified event-id")))

(deftest match-event-id-via-namespace-style-glob
  (testing "spec/018 §7 'namespace (:order/*)' — implemented as a
            prefix glob; matches every event-id with that namespace"
    (let [spec (#'matcher/normalise-pattern :order/*)]
      (is (matcher/match-event-id? :order/submit spec))
      (is (matcher/match-event-id? :order/cancel spec))
      (is (not (matcher/match-event-id? :auth/login spec))))))

(deftest match-event-id-substring
  (let [spec (#'matcher/normalise-pattern "/login")]
    (is (matcher/match-event-id? :auth/login spec))
    (is (matcher/match-event-id? :user/login-clicked spec))
    (is (not (matcher/match-event-id? :auth/logout spec)))))

(deftest match-event-id-never-matches-nothing
  (let [spec (#'matcher/normalise-pattern "")]
    (is (not (matcher/match-event-id? :auth/login spec)))
    (is (not (matcher/match-event-id? nil spec)))))

;; ---- match-pill? --------------------------------------------------------

(deftest match-pill-via-pattern-key
  (is (matcher/match-pill? {:pattern :auth/*} :auth/login))
  (is (not (matcher/match-pill? {:pattern :auth/*} :order/submit))))

(deftest match-pill-missing-pattern-returns-false
  (is (not (matcher/match-pill? {} :auth/login)))
  (is (not (matcher/match-pill? nil :auth/login))))

;; ---- cascade-matches? ---------------------------------------------------

(deftest cascade-matches-any-pill
  (let [cascade {:event [:auth/login]}
        pills   [{:pattern :order/*}
                 {:pattern :auth/*}
                 {:pattern :user/*}]]
    (is (matcher/cascade-matches? cascade pills))))

(deftest cascade-matches-empty-pills-is-false
  (is (not (matcher/cascade-matches? {:event [:auth/login]} [])))
  (is (not (matcher/cascade-matches? {:event [:auth/login]} nil))))

(deftest cascade-matches-unrouted-cascade-never-matches
  (testing "a cascade with no event vector has no event-id; no pill matches"
    (is (not (matcher/cascade-matches?
               {:event nil}
               [{:pattern :auth/*}])))))

;; ---- keep-cascade? + filter-cascades ------------------------------------

(deftest keep-cascade-no-filters-keeps-all
  (let [filters {:in [] :out []}]
    (is (matcher/keep-cascade? {:event [:auth/login]} filters))
    (is (matcher/keep-cascade? {:event [:mouse-move]} filters))))

(deftest keep-cascade-out-only-blacklists
  (let [filters {:in [] :out [{:pattern :mouse-move}]}]
    (is (matcher/keep-cascade? {:event [:auth/login]} filters))
    (is (not (matcher/keep-cascade? {:event [:mouse-move]} filters)))))

(deftest keep-cascade-in-only-whitelists
  (let [filters {:in [{:pattern :auth/*}] :out []}]
    (is (matcher/keep-cascade? {:event [:auth/login]} filters))
    (is (matcher/keep-cascade? {:event [:auth/logout]} filters))
    (is (not (matcher/keep-cascade? {:event [:order/submit]} filters)))))

(deftest keep-cascade-in-and-out-intersect-correctly
  (testing "spec/018 §7 — ACTIVE = (match-any-IN) AND NOT (match-any-OUT)"
    (let [filters {:in  [{:pattern :auth/*}]
                   :out [{:pattern :auth/login}]}]
      (is (not (matcher/keep-cascade?
                 {:event [:auth/login]} filters))
          "matched IN but also OUT → drop")
      (is (matcher/keep-cascade?
            {:event [:auth/logout]} filters)
          "matched IN, not OUT → keep")
      (is (not (matcher/keep-cascade?
                 {:event [:order/submit]} filters))
          "didn't match IN → drop"))))

(deftest filter-cascades-preserves-order
  (let [cascades [{:dispatch-id 1 :event [:auth/login]}
                  {:dispatch-id 2 :event [:mouse-move]}
                  {:dispatch-id 3 :event [:auth/logout]}
                  {:dispatch-id 4 :event [:order/submit]}]
        filters  {:in [] :out [{:pattern :mouse-move}]}]
    (is (= [1 3 4] (mapv :dispatch-id
                         (matcher/filter-cascades cascades filters)))
        "OUT drops :mouse-move; survivors keep their order")))

(deftest filter-cascades-empty-input-is-empty
  (is (= [] (matcher/filter-cascades [] {:in [] :out []})))
  (is (= [] (matcher/filter-cascades [] {:in [{:pattern :auth/*}] :out []}))))

;; ---- spec/018 §7 first-session honesty regression -----------------------

(deftest filter-cascades-default-empty-keeps-everything
  (testing "rf2-ak4ms: shipping defaults must be empty — first-session
            honesty beats first-session quietness. An empty filter set
            keeps every cascade regardless of event-id."
    (let [cascades [{:event [:user/login]}
                    {:event [:mouse-move]}
                    {:event [:anim-frame]}]]
      (is (= cascades
             (matcher/filter-cascades cascades {:in [] :out []}))))))

;; ---- frame-picker filter (rf2-oziyr) ------------------------------------

(deftest keep-cascade-for-frame-nil-picker-keeps-everything
  (testing "nil picker-frame means 'no frame filter' — every cascade survives"
    (is (matcher/keep-cascade-for-frame? {:frame :cart-frame} nil))
    (is (matcher/keep-cascade-for-frame? {:frame :checkout-frame} nil))
    (is (matcher/keep-cascade-for-frame? {:frame nil} nil))))

(deftest keep-cascade-for-frame-matching-frame-keeps
  (is (matcher/keep-cascade-for-frame? {:frame :cart-frame} :cart-frame))
  (is (matcher/keep-cascade-for-frame? {:frame :rf/default} :rf/default)))

(deftest keep-cascade-for-frame-non-matching-frame-drops
  (is (not (matcher/keep-cascade-for-frame? {:frame :cart-frame} :checkout-frame)))
  (is (not (matcher/keep-cascade-for-frame? {:frame nil} :cart-frame))
      "ungrouped/frame-less cascade drops when a frame filter is active"))

(deftest filter-cascades-by-frame-nil-is-identity
  (let [cascades [{:dispatch-id 1 :frame :cart-frame}
                  {:dispatch-id 2 :frame :checkout-frame}
                  {:dispatch-id 3 :frame nil}]]
    (is (= cascades (matcher/filter-cascades-by-frame cascades nil)))))

(deftest filter-cascades-by-frame-restricts-and-preserves-order
  (testing "rf2-oziyr — picker filter at data layer keeps only matching
            cascades; order preserved so [◀ ▶ ⏭] walks the same surface"
    (let [cascades [{:dispatch-id 1 :frame :cart-frame}
                    {:dispatch-id 2 :frame :checkout-frame}
                    {:dispatch-id 3 :frame :cart-frame}
                    {:dispatch-id 4 :frame :checkout-frame}]]
      (is (= [1 3] (mapv :dispatch-id
                         (matcher/filter-cascades-by-frame cascades :cart-frame))))
      (is (= [2 4] (mapv :dispatch-id
                         (matcher/filter-cascades-by-frame cascades :checkout-frame)))))))

(deftest filter-cascades-by-frame-drops-frameless
  (testing "ungrouped / frame-less cascades drop when a frame filter is
            active — keeps the L2 list aligned with the picker label"
    (let [cascades [{:dispatch-id 1 :frame :cart-frame}
                    {:dispatch-id :ungrouped :frame nil}
                    {:dispatch-id 2 :frame :cart-frame}]]
      (is (= [1 2] (mapv :dispatch-id
                         (matcher/filter-cascades-by-frame cascades :cart-frame)))))))
