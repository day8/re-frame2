(ns day8.re-frame2-xray.filters.matcher-cljs-test
  "Pure-data tests for the IN/OUT filter matcher (rf2-ak4ms).

  CLJC so BOTH corpora exercise the matcher — it is pure data, no
  atoms, no I/O, so there is nothing to keep it off the CLJS lane
  (rf2-odlm3)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-xray.filters.matcher :as matcher]))

;; ---- normalise-pattern --------------------------------------------------

(deftest normalise-pattern-exact-keyword
  (is (= {:kind :exact :pattern :auth/login}
         (#'matcher/normalise-pattern :auth/login))))

(deftest normalise-pattern-prefix-glob
  (is (= {:kind :prefix :pattern ":auth/"}
         (#'matcher/normalise-pattern :auth/*)))
  (is (= {:kind :prefix :pattern ":order.cart/"}
         (#'matcher/normalise-pattern :order.cart/*))))

(deftest normalise-pattern-bare-keyword-is-exact-or-namespace
  (testing "rf2-y8doi.27 — a bare unqualified keyword compiles to
            :exact-or-ns, so the Add-filter dialog's `:auth` example
            matches BOTH readings. It used to compile to :exact alone,
            which made that example match nothing and silently emptied
            the L2 list."
    (is (= {:kind :exact-or-ns :pattern :auth}
           (#'matcher/normalise-pattern :auth)))
    (is (= {:kind :exact-or-ns :pattern :mouse-move}
           (#'matcher/normalise-pattern :mouse-move))))
  (testing "a QUALIFIED keyword is unambiguous and stays :exact"
    (is (= {:kind :exact :pattern :auth/login}
           (#'matcher/normalise-pattern :auth/login)))))

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

(deftest normalise-pattern-bare-string-glob-is-prefix
  (testing "rf2-y8doi.27 — a colon-less string ending in `*` is the same
            intent as the keyword glob (the user dropped the `:`), so it
            compiles to :prefix. It used to fall to :substring, which
            matched nothing at all: no `(str event-id)` contains a `*`."
    (is (= {:kind :prefix :pattern ":auth/"}
           (#'matcher/normalise-pattern "auth/*")))
    (is (= {:kind :prefix :pattern ":auth"}
           (#'matcher/normalise-pattern "auth*")))
    (is (= (#'matcher/normalise-pattern ":auth/*")
           (#'matcher/normalise-pattern "auth/*"))
        "the colon-less and colon-led globs compile identically")))

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

(deftest match-event-id-bare-keyword-is-exact-or-namespace
  (testing "rf2-y8doi.27 — the Add-filter dialog's own `:auth` example.
            An IN pill `:auth` matches the namespace AND the bare id,
            and nothing else."
    (let [spec (#'matcher/normalise-pattern :auth)]
      (is (matcher/match-event-id? :auth/login spec)
          ":auth matches :auth/login — the namespace reading")
      (is (matcher/match-event-id? :auth spec)
          ":auth matches :auth — the exact reading")
      (is (not (matcher/match-event-id? :authors/x spec))
          ":auth does NOT match :authors/x — a namespace is matched whole,
           never as a string prefix")
      (is (not (matcher/match-event-id? :order/submit spec)))
      (is (not (matcher/match-event-id? nil spec)))))
  (testing "spec/018 §7's example pill `[× :mouse-move]` still blocks the
            bare event-id it was written for"
    (let [spec (#'matcher/normalise-pattern :mouse-move)]
      (is (matcher/match-event-id? :mouse-move spec))
      (is (not (matcher/match-event-id? :other-event spec)))
      (is (not (matcher/match-event-id? :user/mouse-move spec))
          "an unqualified pattern matches a qualified event-id only
           through the NAMESPACE, and `:user/mouse-move`'s namespace is
           `user`, not `mouse-move`"))))

(deftest match-event-id-bare-string-glob
  (testing "rf2-y8doi.27 — `auth/*` (no colon) matches :auth/login"
    (let [spec (#'matcher/normalise-pattern "auth/*")]
      (is (matcher/match-event-id? :auth/login spec))
      (is (matcher/match-event-id? :auth/logout spec))
      (is (not (matcher/match-event-id? :order/submit spec)))
      (is (not (matcher/match-event-id? nil spec))))))

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

;; ---- event-bundle-matches? ---------------------------------------------------

(deftest event-bundle-matches-any-pill
  (let [cascade {:event [:auth/login]}
        pills   [{:pattern :order/*}
                 {:pattern :auth/*}
                 {:pattern :user/*}]]
    (is (matcher/event-bundle-matches? cascade pills))))

(deftest event-bundle-matches-empty-pills-is-false
  (is (not (matcher/event-bundle-matches? {:event [:auth/login]} [])))
  (is (not (matcher/event-bundle-matches? {:event [:auth/login]} nil))))

(deftest event-bundle-matches-unrouted-cascade-never-matches
  (testing "a cascade with no event vector has no event-id; no pill matches"
    (is (not (matcher/event-bundle-matches?
               {:event nil}
               [{:pattern :auth/*}])))))

;; ---- keep-event-bundle? + filter-event-bundles ------------------------------------

(deftest keep-event-bundle-no-filters-keeps-all
  (let [filters {:in [] :out []}]
    (is (matcher/keep-event-bundle? {:event [:auth/login]} filters))
    (is (matcher/keep-event-bundle? {:event [:mouse-move]} filters))))

(deftest keep-event-bundle-out-only-blacklists
  (let [filters {:in [] :out [{:pattern :mouse-move}]}]
    (is (matcher/keep-event-bundle? {:event [:auth/login]} filters))
    (is (not (matcher/keep-event-bundle? {:event [:mouse-move]} filters)))))

(deftest keep-event-bundle-in-pill-bare-keyword-keeps-the-namespace
  (testing "rf2-y8doi.27 — the whole point, at the level the L2 list
            reads: an IN pill typed `:auth` (the Add-filter dialog's own
            example) keeps the `auth` namespace and the bare id, and
            drops a namespace that merely starts with the same letters.
            Before the grammar fix this pill kept NOTHING and the list
            silently emptied."
    (let [filters {:in [{:pattern :auth}] :out []}]
      (is (matcher/keep-event-bundle? {:event [:auth/login]} filters))
      (is (matcher/keep-event-bundle? {:event [:auth]} filters))
      (is (not (matcher/keep-event-bundle? {:event [:authors/x]} filters)))
      (is (not (matcher/keep-event-bundle? {:event [:order/submit]} filters))))))

(deftest keep-event-bundle-in-only-whitelists
  (let [filters {:in [{:pattern :auth/*}] :out []}]
    (is (matcher/keep-event-bundle? {:event [:auth/login]} filters))
    (is (matcher/keep-event-bundle? {:event [:auth/logout]} filters))
    (is (not (matcher/keep-event-bundle? {:event [:order/submit]} filters)))))

(deftest keep-event-bundle-in-and-out-intersect-correctly
  (testing "spec/018 §7 — ACTIVE = (match-any-IN) AND NOT (match-any-OUT)"
    (let [filters {:in  [{:pattern :auth/*}]
                   :out [{:pattern :auth/login}]}]
      (is (not (matcher/keep-event-bundle?
                 {:event [:auth/login]} filters))
          "matched IN but also OUT → drop")
      (is (matcher/keep-event-bundle?
            {:event [:auth/logout]} filters)
          "matched IN, not OUT → keep")
      (is (not (matcher/keep-event-bundle?
                 {:event [:order/submit]} filters))
          "didn't match IN → drop"))))

(deftest filter-event-bundles-preserves-order
  (let [cascades [{:dispatch-id 1 :event [:auth/login]}
                  {:dispatch-id 2 :event [:mouse-move]}
                  {:dispatch-id 3 :event [:auth/logout]}
                  {:dispatch-id 4 :event [:order/submit]}]
        filters  {:in [] :out [{:pattern :mouse-move}]}]
    (is (= [1 3 4] (mapv :dispatch-id
                         (matcher/filter-event-bundles cascades filters)))
        "OUT drops :mouse-move; survivors keep their order")))

(deftest filter-event-bundles-empty-input-is-empty
  (is (= [] (matcher/filter-event-bundles [] {:in [] :out []})))
  (is (= [] (matcher/filter-event-bundles [] {:in [{:pattern :auth/*}] :out []}))))

;; ---- spec/018 §7 first-session honesty regression -----------------------

(deftest filter-event-bundles-default-empty-keeps-everything
  (testing "rf2-ak4ms: shipping defaults must be empty — first-session
            honesty beats first-session quietness. An empty filter set
            keeps every cascade regardless of event-id."
    (let [cascades [{:event [:user/login]}
                    {:event [:mouse-move]}
                    {:event [:anim-frame]}]]
      (is (= cascades
             (matcher/filter-event-bundles cascades {:in [] :out []}))))))

;; ---- frame-picker filter (rf2-oziyr) ------------------------------------

(deftest keep-event-bundle-for-frame-nil-picker-keeps-everything
  (testing "nil picker-frame means 'no frame filter' — every cascade survives"
    (is (matcher/keep-event-bundle-for-frame? {:frame :cart-frame} nil))
    (is (matcher/keep-event-bundle-for-frame? {:frame :checkout-frame} nil))
    (is (matcher/keep-event-bundle-for-frame? {:frame nil} nil))))

(deftest keep-event-bundle-for-frame-matching-frame-keeps
  (is (matcher/keep-event-bundle-for-frame? {:frame :cart-frame} :cart-frame))
  (is (matcher/keep-event-bundle-for-frame? {:frame :rf/default} :rf/default)))

(deftest keep-event-bundle-for-frame-non-matching-frame-drops
  (is (not (matcher/keep-event-bundle-for-frame? {:frame :cart-frame} :checkout-frame)))
  (is (not (matcher/keep-event-bundle-for-frame? {:frame nil} :cart-frame))
      "ungrouped/frame-less cascade drops when a frame filter is active"))

(deftest filter-event-bundles-by-frame-nil-is-identity
  (let [cascades [{:dispatch-id 1 :frame :cart-frame}
                  {:dispatch-id 2 :frame :checkout-frame}
                  {:dispatch-id 3 :frame nil}]]
    (is (= cascades (matcher/filter-event-bundles-by-frame cascades nil)))))

(deftest filter-event-bundles-by-frame-restricts-and-preserves-order
  (testing "rf2-oziyr — picker filter at data layer keeps only matching
            cascades; order preserved so [◀ ▶ ⏭] walks the same surface"
    (let [cascades [{:dispatch-id 1 :frame :cart-frame}
                    {:dispatch-id 2 :frame :checkout-frame}
                    {:dispatch-id 3 :frame :cart-frame}
                    {:dispatch-id 4 :frame :checkout-frame}]]
      (is (= [1 3] (mapv :dispatch-id
                         (matcher/filter-event-bundles-by-frame cascades :cart-frame))))
      (is (= [2 4] (mapv :dispatch-id
                         (matcher/filter-event-bundles-by-frame cascades :checkout-frame)))))))

(deftest filter-event-bundles-by-frame-drops-frameless
  (testing "ungrouped / frame-less cascades drop when a frame filter is
            active — keeps the L2 list aligned with the picker label"
    (let [cascades [{:dispatch-id 1 :frame :cart-frame}
                    {:dispatch-id :ungrouped :frame nil}
                    {:dispatch-id 2 :frame :cart-frame}]]
      (is (= [1 2] (mapv :dispatch-id
                         (matcher/filter-event-bundles-by-frame cascades :cart-frame)))))))

;; ---- view-scope filter (rf2-4vp5j) --------------------------------------

(deftest keep-event-bundle-for-view-scope-nil-keeps-everything
  (testing "nil scope-frame means 'no view scope' — every cascade survives"
    (is (matcher/keep-event-bundle-for-view-scope? {:frame :cart-frame} nil))
    (is (matcher/keep-event-bundle-for-view-scope? {:frame nil} nil))))

(deftest keep-event-bundle-for-view-scope-keeps-matching-and-frameless
  (testing "rf2-4vp5j — a view scope keeps the matching frame AND the
            frame-agnostic `:ungrouped` bucket (nil frame); only OTHER
            real frames drop"
    (is (matcher/keep-event-bundle-for-view-scope? {:frame :cart-frame} :cart-frame))
    (is (matcher/keep-event-bundle-for-view-scope? {:frame nil} :cart-frame)
        "frameless :ungrouped bucket survives the view scope")
    (is (not (matcher/keep-event-bundle-for-view-scope? {:frame :other-frame} :cart-frame))
        "a different real frame drops out of scope")))

(deftest filter-event-bundles-by-view-scope-preserves-frameless-bucket
  (testing "rf2-4vp5j — unlike the strict frame filter, the view-scope
            filter keeps the frameless `:ungrouped` bucket so a defaulted
            scope never swallows it (its render is gated by show-ungrouped?)"
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
