(ns day8.re-frame2-causa.panels.l2-timeline-cljs-test
  "Pure-data tests for the L2 epoch-timeline helpers (rf2-gf58j).

  ## What's under test

    1. **dispatch-origin extraction** — `dispatch-origin-of` reads
       `[:dispatched :tags :rf/dispatch-origin]` and nil-safes every
       step.
    2. **origin → glyph mapping** — every closed-enum value renders
       the right glyph; `:user` is silent; unknown / nil return nil.
    3. **origin → title text** — present only when a glyph is
       present.
    4. **cascade activity flags** — per-class detection from `:other`
       events + the cascade's origin tag + the `:errors` slot.
    5. **activity-badges projection** — ordered, compressed, glyph-
       matched against the canonical mapping.
    6. **activity tooltip** — nil-safe + human-readable.

  Pure-fn shape — input cascade-aggregate map → expected output
  glyph string / hiccup-ready vector. No CLJS runtime touched."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.l2-timeline :as l2]))

;; ---- fixture builders ---------------------------------------------------

(defn- cascade-with-origin
  "Build a synthetic cascade record whose `:dispatched` carries the
  given dispatch-origin tag. Mirrors the shape produced by
  `re-frame.trace.projection/group-cascades`."
  [origin]
  {:dispatch-id 42
   :event       [:cart/add-item {:id 99}]
   :dispatched  {:operation :event/dispatched
                 :op-type   :event
                 :tags      {:rf/dispatch-origin origin
                             :dispatch-id        42}}
   :other       []
   :errors      []})

(defn- ev
  "Build a synthetic trace event with the given operation."
  [operation & {:keys [op-type tags] :or {op-type nil tags {}}}]
  (cond-> {:operation operation :tags tags}
    op-type (assoc :op-type op-type)))

;; ---- 1. dispatch-origin extraction --------------------------------------

(deftest dispatch-origin-of-test
  (testing "reads :rf/dispatch-origin from :dispatched :tags"
    (is (= :tool   (l2/dispatch-origin-of (cascade-with-origin :tool))))
    (is (= :router (l2/dispatch-origin-of (cascade-with-origin :router))))
    (is (= :user   (l2/dispatch-origin-of (cascade-with-origin :user)))))

  (testing "nil-safe on missing slots"
    (is (nil? (l2/dispatch-origin-of nil)))
    (is (nil? (l2/dispatch-origin-of {})))
    (is (nil? (l2/dispatch-origin-of {:dispatched nil})))
    (is (nil? (l2/dispatch-origin-of {:dispatched {}})))
    (is (nil? (l2/dispatch-origin-of {:dispatched {:tags {}}}))))

  (testing "non-map input returns nil"
    (is (nil? (l2/dispatch-origin-of "not a cascade")))
    (is (nil? (l2/dispatch-origin-of 42)))))

;; ---- 2. origin → glyph mapping ------------------------------------------

(deftest origin-prefix-glyph-test
  (testing ":user is silent (no prefix) — the common case stays clutter-free"
    (is (nil? (l2/origin-prefix-glyph :user))))

  (testing "every non-user closed-enum value renders a glyph"
    (is (= "R"    (l2/origin-prefix-glyph :router)))
    (is (= "🌐"   (l2/origin-prefix-glyph :http)))     ; 🌐
    (is (= "💧"   (l2/origin-prefix-glyph :ssr)))      ; 💧
    (is (= "⚡"     (l2/origin-prefix-glyph :fx-emit)))  ; ⚡
    (is (= "⏲"     (l2/origin-prefix-glyph :timer)))    ; ⏲
    (is (= "T"    (l2/origin-prefix-glyph :test-harness)))
    (is (= "🔧"   (l2/origin-prefix-glyph :tool)))     ; 🔧
    (is (= "i"    (l2/origin-prefix-glyph :internal)))
    (is (= "🌊"   (l2/origin-prefix-glyph :websocket)))) ; 🌊

  (testing "unknown / nil → nil (defence-in-depth, never throws)"
    (is (nil? (l2/origin-prefix-glyph nil)))
    (is (nil? (l2/origin-prefix-glyph :unknown-axis)))
    (is (nil? (l2/origin-prefix-glyph "string"))))

  (testing "the mapping covers exactly the closed enum (Spec 009 / Causa A.5)"
    (is (= #{:user :router :http :ssr :fx-emit :timer
             :test-harness :tool :internal :websocket}
           (set (keys l2/origin-glyphs))))))

;; ---- 3. origin → title text ---------------------------------------------

(deftest origin-prefix-title-test
  (testing "title text present only when the glyph is present"
    (is (nil?              (l2/origin-prefix-title :user)))
    (is (nil?              (l2/origin-prefix-title nil)))
    (is (some?             (l2/origin-prefix-title :router)))
    (is (some?             (l2/origin-prefix-title :tool)))
    (is (some?             (l2/origin-prefix-title :http))))

  (testing "title carries the keyword name so the operator can read the closed-enum value"
    (is (re-find #":router"      (l2/origin-prefix-title :router)))
    (is (re-find #":fx-emit"     (l2/origin-prefix-title :fx-emit)))
    (is (re-find #":websocket"   (l2/origin-prefix-title :websocket)))))

;; ---- 4. cascade activity flags ------------------------------------------

(deftest cascade-activity-flags-defaults-test
  (testing "an empty cascade yields all-false flags"
    (let [flags (l2/cascade-activity-flags {})]
      (is (= {:error? false :machine? false :http? false
              :timer? false :fx-emit? false}
             flags))))

  (testing "nil cascade is safe — returns all-false flags"
    (let [flags (l2/cascade-activity-flags nil)]
      (is (= {:error? false :machine? false :http? false
              :timer? false :fx-emit? false}
             flags)))))

(deftest cascade-activity-flags-error-test
  (testing ":errors slot populated → error? true (legacy axis)"
    (let [flags (l2/cascade-activity-flags
                 {:errors [{:operation :rf.error/handler-throw}]})]
      (is (true? (:error? flags)))))

  (testing ":op-type :error on :other → error? true"
    (let [flags (l2/cascade-activity-flags
                 {:other [(ev :rf.error/handler-throw :op-type :error)]})]
      (is (true? (:error? flags)))))

  (testing ":rf.error/* operation on :other (no :op-type) → error? true"
    (let [flags (l2/cascade-activity-flags
                 {:other [(ev :rf.error/bad-fx)]})]
      (is (true? (:error? flags))))))

(deftest cascade-activity-flags-machine-test
  (testing ":rf.machine/* operation → machine? true"
    (let [flags (l2/cascade-activity-flags
                 {:other [(ev :rf.machine/transition)]})]
      (is (true? (:machine? flags))))
    (let [flags (l2/cascade-activity-flags
                 {:other [(ev :rf.machine/spawn)
                          (ev :rf.machine/despawn)]})]
      (is (true? (:machine? flags))))))

(deftest cascade-activity-flags-http-test
  (testing ":rf.http/* operation → http? true"
    (let [flags (l2/cascade-activity-flags
                 {:other [(ev :rf.http/managed-request-settle)]})]
      (is (true? (:http? flags)))))

  (testing ":http/* legacy operation → http? true"
    (let [flags (l2/cascade-activity-flags
                 {:other [(ev :http/request-success)]})]
      (is (true? (:http? flags))))))

(deftest cascade-activity-flags-timer-test
  (testing ":rf.timer/* operation → timer? true"
    (let [flags (l2/cascade-activity-flags
                 {:other [(ev :rf.timer/fired)]})]
      (is (true? (:timer? flags)))))

  (testing "cascade origin :timer alone (no :rf.timer trace) → timer? true"
    ;; The runtime stamps origin-on-dispatch; some timer dispatches
    ;; don't carry a separate :rf.timer/fired trace event but the
    ;; origin tag still tells the story.
    (let [flags (l2/cascade-activity-flags (cascade-with-origin :timer))]
      (is (true? (:timer? flags))))))

(deftest cascade-activity-flags-fx-emit-test
  (testing "cascade origin :fx-emit → fx-emit? true"
    (let [flags (l2/cascade-activity-flags (cascade-with-origin :fx-emit))]
      (is (true? (:fx-emit? flags)))))

  (testing "cascade origin :user → fx-emit? false"
    (let [flags (l2/cascade-activity-flags (cascade-with-origin :user))]
      (is (false? (:fx-emit? flags))))))

(deftest cascade-activity-flags-mixed-test
  (testing "multiple activity classes light up independently"
    (let [c (-> (cascade-with-origin :fx-emit)
                (assoc :other [(ev :rf.machine/transition)
                               (ev :rf.http/managed-request-settle)
                               (ev :rf.error/handler-throw :op-type :error)]))
          flags (l2/cascade-activity-flags c)]
      (is (= {:error? true :machine? true :http? true
              :timer? false :fx-emit? true}
             flags)))))

;; ---- 5. activity-badges projection --------------------------------------

(deftest activity-badges-empty-test
  (testing "a quiet cascade yields []"
    (is (= [] (l2/activity-badges {})))
    (is (= [] (l2/activity-badges nil)))
    (is (= [] (l2/activity-badges (cascade-with-origin :user))))))

(deftest activity-badges-single-test
  (testing "one flag → one badge"
    (is (= ["⚠"]
           (l2/activity-badges {:errors [{:operation :rf.error/throw}]})))
    (is (= ["◆"]
           (l2/activity-badges {:other [(ev :rf.machine/transition)]})))
    (is (= ["🌐"]
           (l2/activity-badges {:other [(ev :rf.http/managed-request-settle)]})))
    (is (= ["⚡"]
           (l2/activity-badges (cascade-with-origin :fx-emit))))
    (is (= ["⏲"]
           (l2/activity-badges (cascade-with-origin :timer))))))

(deftest activity-badges-ordered-test
  (testing "badges render in the canonical order ⚠ ◆ 🌐 ⚡ ⏲"
    (let [c (-> (cascade-with-origin :fx-emit)
                (assoc :other [(ev :rf.timer/fired)
                               (ev :rf.http/managed-request-settle)
                               (ev :rf.machine/transition)
                               (ev :rf.error/handler-throw :op-type :error)]))]
      (is (= ["⚠" "◆" "🌐" "⚡" "⏲"]
             (l2/activity-badges c))))))

(deftest activity-badges-glyph-mapping-test
  (testing "every flag maps to its canonical glyph"
    (is (= "⚠" (get l2/activity-badge-glyphs :error?)))
    (is (= "◆" (get l2/activity-badge-glyphs :machine?)))
    (is (= "🌐" (get l2/activity-badge-glyphs :http?)))
    (is (= "⚡" (get l2/activity-badge-glyphs :fx-emit?)))
    (is (= "⏲" (get l2/activity-badge-glyphs :timer?))))

  (testing "the mapping covers exactly the five activity flags"
    (is (= #{:error? :machine? :http? :fx-emit? :timer?}
           (set (keys l2/activity-badge-glyphs))))))

;; ---- 6. activity tooltip ------------------------------------------------

(deftest activity-badges-tooltip-test
  (testing "nil when no badges are present"
    (is (nil? (l2/activity-badges-tooltip {})))
    (is (nil? (l2/activity-badges-tooltip nil)))
    (is (nil? (l2/activity-badges-tooltip (cascade-with-origin :user)))))

  (testing "human-readable cluster description"
    (let [c (-> (cascade-with-origin :fx-emit)
                (assoc :other [(ev :rf.machine/transition)
                               (ev :rf.http/managed-request-settle)
                               (ev :rf.error/handler-throw :op-type :error)]))
          tip (l2/activity-badges-tooltip c)]
      (is (string? tip))
      (is (re-find #"^Activity:" tip))
      (is (re-find #"issues raised" tip))
      (is (re-find #"machine transition" tip))
      (is (re-find #"HTTP activity" tip))
      (is (re-find #"fx-emit child" tip))))

  (testing "single-badge tooltip"
    (is (= "Activity: issues raised"
           (l2/activity-badges-tooltip
            {:errors [{:operation :rf.error/throw}]})))))
