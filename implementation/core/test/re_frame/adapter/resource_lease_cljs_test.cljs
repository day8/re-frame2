(ns re-frame.adapter.resource-lease-cljs-test
  "Unit coverage for the centralized resource-lease helpers
  (`re-frame.adapter.resource-lease`, rf2-qdkt8y) — the SINGLE home for lease
  identity + the shared ratom-family lifecycle logic that Reagent, reagent-slim,
  UIx and Helix all draw from. Pinning the shared behaviour ONCE here means the
  per-adapter DOM suites stay thin native-shell integration tests.

  The mount/unmount lifecycle wiring (React effects / Reagent class methods)
  needs a real DOM and is exercised by the per-adapter `*-dom-cljs-test`
  suites and the mixed-family regression; here we pin the pure, substrate-free
  pieces."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.resource-lease :as rl]))

;; ---- the single owner mint (the core bug fix) ------------------------------

(deftest mint-lease-owner-shape
  (testing "mints the framework-canonical [:lease <n>] owner shape"
    (let [owner (rl/mint-lease-owner!)]
      (is (vector? owner))
      (is (= :lease (first owner)))
      (is (number? (second owner))))))

(deftest mint-lease-owner-is-globally-unique
  (testing "successive mints are always DISTINCT — the property that stops a
            mixed-adapter process colliding two families' lease owners (every
            family now draws from THIS one mint, rf2-qdkt8y)"
    (let [owners (repeatedly 200 rl/mint-lease-owner!)]
      (is (= 200 (count (set owners)))
          "200 mints yield 200 distinct owners (no collision, no reuse)")
      (is (apply distinct? (map second owners))
          "the minted tokens are strictly distinct"))))

;; ---- ratom-family arg normalization ----------------------------------------

(deftest lease-args-body-only
  (testing "a leading fn is the body; cause/frame take defaults"
    (let [body (fn [] [:div "x"])
          {:keys [descriptor cause frame body]} (rl/lease-args [{:resource :r} body])]
      (is (= {:resource :r} descriptor))
      (is (= [:lease :mount] cause) "cause defaults to [:lease :mount]")
      (is (nil? frame) "no frame pin by default")
      (is (fn? body)))))

(deftest lease-args-opts-then-body
  (testing "an opts map between descriptor and body supplies :cause / :frame"
    (let [body (fn [] [:div "x"])
          {:keys [descriptor cause frame body]}
          (rl/lease-args [{:resource :r}
                          {:cause :dashboard :frame :some/frame}
                          body])]
      (is (= {:resource :r} descriptor))
      (is (= :dashboard cause) "explicit :cause is carried")
      (is (= :some/frame frame) "explicit :frame is carried")
      (is (fn? body)))))

(deftest lease-args-opts-defaults
  (testing "an opts map with no :cause still defaults the cause"
    (let [{:keys [cause frame]} (rl/lease-args [{:resource :r} {:frame :f} (fn [])])]
      (is (= [:lease :mount] cause))
      (is (= :f frame)))))

;; ---- render-time frame resolution precedence -------------------------------

(deftest resolve-lease-frame-explicit-wins
  (testing "an explicit frame is returned verbatim, never consulting the
            carried-invariant reader (EP-0002: explicit :frame pins the target)"
    (is (= :pinned/frame
           (rl/resolve-lease-frame :pinned/frame :some-reader 'some/where)))))

;; ---- desired/held re-lease diff --------------------------------------------
;; The commit-phase methods diff #js target records; a change on frame,
;; descriptor, OR cause triggers a re-lease.

(defn- rec [frame descriptor cause]
  #js {:frame frame :descriptor descriptor :cause cause})

(deftest lease-target-changed-detects-each-axis
  (let [held (rec :f {:resource :r :params {:page 0}} :c)]
    (testing "an identical target is NOT a change (holds one lease, no churn)"
      (is (false? (boolean (rl/lease-target-changed? held (rec :f {:resource :r :params {:page 0}} :c))))))
    (testing "a frame change is a re-lease"
      (is (rl/lease-target-changed? held (rec :g {:resource :r :params {:page 0}} :c))))
    (testing "a descriptor change is a re-lease"
      (is (rl/lease-target-changed? held (rec :f {:resource :r :params {:page 1}} :c))))
    (testing "a cause change is a re-lease"
      (is (rl/lease-target-changed? held (rec :f {:resource :r :params {:page 0}} :d))))))
