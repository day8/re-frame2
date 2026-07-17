(ns re-frame.ui.teardown-falsy-failure-cljs-test
  "rf2-s2cfv — falsy failure preservation across the UI teardown boundaries.

  On CLJS a thrown value need not be a truthy Error: JS lets code `throw false`
  or `throw nil`. PR #5965 taught `re-frame.ui.test/with-root` to track cleanup
  failures by PRESENCE (an identity sentinel) rather than JS truthiness; this
  fixture pins the same presence discipline at the four production teardown
  boundaries so a falsy failure is never swallowed, overwritten, or misrouted:

    - `client/reclaim-consumed-container!` — a falsy FIRST reclaim failure is not
      overwritten by a later step and is still rethrown.
    - `client/drain-live-roots!` — a non-empty error set whose PRIMARY is falsy
      still rethrows (never suppressed by `when-let`), and later failures stay
      observable even for a primitive primary.
    - `substrate/dispose-outcome` — the extracted presence decision for
      `dispose-adapter!`: a falsy public-root/spine failure is preserved and
      correctly ordered (public-root primary over spine).
    - `substrate/attach-secondary-cleanup!` — a falsy secondary is attached by
      presence; a primitive primary keeps it observable via console.

  Runs off the DOM under `npm run test:cljs` (node): the boundaries are exercised
  with fake Root/container objects and the extracted pure deciders."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [re-frame.ui.client :as client]
            [re-frame.ui.substrate :as substrate]))

(use-fixtures :each
  (fn [f]
    (client/reset-live-roots!)
    (try (f) (finally (client/reset-live-roots!)))))

(defn- catch-throw
  "Run `thunk`, returning `[::threw v]` for the thrown value `v` (including a
  falsy one) or `[::no-throw ret]` when it returns — so a swallowed falsy failure
  is distinguishable from a rethrown one."
  [thunk]
  (try [::no-throw (thunk)] (catch :default e [::threw e])))

(defn- with-warn-spy
  "Run `f` with `js/console.warn` captured; returns the vector of `[msg detail]`
  arg pairs it was called with."
  [f]
  (let [calls (atom [])
        orig  (.-warn js/console)]
    (set! (.-warn js/console) (fn [msg detail] (swap! calls conj [msg detail])))
    (try (f) (finally (set! (.-warn js/console) orig)))
    @calls))

(defn- reclaim! [root]
  (#'client/reclaim-consumed-container! root))

;; ===========================================================================
;; client/reclaim-consumed-container! — a falsy first reclaim failure is neither
;; swallowed at the rethrow nor overwritten by a later step.
;; ===========================================================================

(deftest reclaim-rethrows-a-falsy-first-cleanup-failure
  (testing "a FALSE first-step failure is rethrown, not swallowed by truthiness"
    (let [container #js {}
          _         (set! (.-replaceChildren container) (fn [] (throw false)))
          root      (client/->Root nil container nil)]
      (is (= [::threw false] (catch-throw #(reclaim! root))))))
  (testing "a NIL first-step failure is rethrown"
    (let [container #js {}
          _         (set! (.-replaceChildren container) (fn [] (throw nil)))
          root      (client/->Root nil container nil)]
      (is (= [::threw nil] (catch-throw #(reclaim! root)))))))

(deftest reclaim-keeps-the-falsy-first-failure-over-a-later-truthy-one
  ;; First step throws FALSE; the marker-deletion step then throws an Error. The
  ;; falsy first failure must be preserved by presence — a later truthy failure
  ;; must not overwrite it.
  (let [second-boom (js/Error. "marker deletion failed")
        base        #js {}
        _           (set! (.-replaceChildren base) (fn [] (throw false)))
        _           (js/Object.defineProperty
                     base "__reactContainer$1"
                     #js {:value 1 :enumerable true :configurable true})
        container   (js/Proxy. base
                               #js {:deleteProperty (fn [_ _] (throw second-boom))})
        root        (client/->Root nil container nil)]
    (is (= [::threw false] (catch-throw #(reclaim! root)))
        "the falsy FIRST failure wins — never overwritten by the later Error")))

(deftest reclaim-is-clean-when-both-steps-succeed
  (let [container #js {}
        _         (set! (.-replaceChildren container) (fn [] nil))
        root      (client/->Root nil container nil)]
    (is (= [::no-throw nil] (catch-throw #(reclaim! root)))
        "a repaired container reclaims cleanly and returns nil")))

;; ===========================================================================
;; client/drain-live-roots! — a non-empty error set with a FALSY primary still
;; rethrows, and later failures stay observable for a primitive primary.
;; ===========================================================================

(defn- quarantined-entry
  "A live-root registry entry already `:cleanup-failure?` quarantined, whose
  terminal container reclaim throws `boom` (drain takes the `quarantined?` branch
  → `reclaim-consumed-container!`)."
  [root-id boom]
  (let [container #js {}
        _         (set! (.-replaceChildren container) (fn [] (throw boom)))
        root      (client/->Root nil container root-id)]
    {:root root
     :root-id root-id
     :tearing-down? true
     :cleanup-failure? true
     :root-incarnation (js/Object.)}))

(defn- seed-live-roots! [entries-map]
  (reset! @#'client/live-roots entries-map))

(deftest drain-rethrows-a-falsy-primary-cleanup-failure
  (testing "a single reclaim failure of FALSE still rethrows — not suppressed"
    (seed-live-roots! (array-map :falsy-drain/one (quarantined-entry :falsy-drain/one false)))
    (is (= [::threw false] (catch-throw client/drain-live-roots!))))
  (testing "a single reclaim failure of NIL still rethrows"
    (seed-live-roots! (array-map :falsy-drain/one (quarantined-entry :falsy-drain/one nil)))
    (is (= [::threw nil] (catch-throw client/drain-live-roots!)))))

(deftest drain-keeps-a-falsy-primary-and-keeps-later-errors-observable
  ;; Two quarantined roots: the FIRST reclaim throws FALSE, the second throws an
  ;; Error. The falsy primary must be rethrown (ordering preserved), and because a
  ;; primitive primary cannot carry the `rfUiAdapterCleanupErrors` diagnostic
  ;; array, the later error rides a console warning so it stays observable.
  (let [second-boom (js/Error. "second root cleanup failed")]
    (seed-live-roots!
     (array-map
      :falsy-drain/primary   (quarantined-entry :falsy-drain/primary false)
      :falsy-drain/secondary (quarantined-entry :falsy-drain/secondary second-boom)))
    (let [thrown (atom nil)
          warns  (with-warn-spy
                   #(reset! thrown (catch-throw client/drain-live-roots!)))]
      (is (= [::threw false] @thrown)
          "the falsy PRIMARY is rethrown ahead of the later Error")
      (is (= 1 (count warns)) "the primitive-primary fallback logged exactly one warning")
      (is (= second-boom (aget (second (first warns)) 0))
          "…the later error rides the console warning so it stays observable"))))

;; ===========================================================================
;; substrate/dispose-outcome — the extracted presence decision for
;; dispose-adapter!: a falsy public-root/spine failure is preserved and ordered.
;; ===========================================================================

(def ^:private absent-marker (js/Object.))

(defn- outcome [root-present? root-error spine-present? spine-error]
  (#'substrate/dispose-outcome root-present? root-error spine-present? spine-error))

(deftest dispose-outcome-preserves-a-falsy-public-root-failure
  (testing "a FALSE public-root drain failure is thrown, never taken for clean"
    (is (= [:throw false] (outcome true false false absent-marker))))
  (testing "a NIL public-root drain failure is thrown"
    (is (= [:throw nil] (outcome true nil false absent-marker)))))

(deftest dispose-outcome-preserves-a-falsy-spine-only-failure
  (testing "a FALSE spine-only failure is thrown, never taken for clean"
    (is (= [:throw false] (outcome false absent-marker true false))))
  (testing "a NIL spine-only failure is thrown"
    (is (= [:throw nil] (outcome false absent-marker true nil)))))

(deftest dispose-outcome-orders-a-falsy-public-root-ahead-of-the-spine
  ;; Both halves failed and the public-root failure is FALSE: it must remain
  ;; primary (a truthiness cond would let the truthy spine error win the throw).
  (let [spine-boom (js/Error. "spine dispose failed")
        [tag reason] (outcome true false true spine-boom)]
    (is (= :throw tag))
    (is (= false reason) "the falsy public-root failure stays primary over the spine error")))

(deftest dispose-outcome-is-clean-when-neither-half-failed
  (is (= [:ok] (outcome false absent-marker false absent-marker))
      "no present failure → clean disposal, never a spurious throw"))

;; ===========================================================================
;; substrate/attach-secondary-cleanup! — a falsy secondary is attached by
;; presence; a primitive primary keeps it observable via console.
;; ===========================================================================

(defn- attach [primary secondary-present? secondary]
  (#'substrate/attach-secondary-cleanup! primary secondary-present? secondary))

(deftest attach-secondary-attaches-a-present-falsy-secondary
  (let [primary (js/Error. "public root failed")]
    (is (identical? primary (attach primary true false)) "the primary is returned unchanged")
    (is (= false (.-rfUiAdapterCleanupError primary))
        "a PRESENT but falsy secondary is attached by presence, not truthiness")))

(deftest attach-secondary-skips-an-absent-secondary
  (let [primary (js/Error. "public root failed")]
    (is (identical? primary (attach primary false nil)))
    (is (undefined? (.-rfUiAdapterCleanupError primary))
        "no secondary present → nothing attached")))

(deftest attach-secondary-keeps-a-secondary-observable-for-a-primitive-primary
  ;; A primitive primary (false) cannot carry a defineProperty diagnostic, so the
  ;; secondary must ride a console warning instead — the primary is unchanged.
  (let [secondary (js/Error. "spine failed")
        warns     (with-warn-spy #(is (= false (attach false true secondary))))]
    (is (= 1 (count warns)) "exactly one fallback warning was logged")
    (is (= secondary (second (first warns)))
        "…carrying the secondary so it stays observable")))
