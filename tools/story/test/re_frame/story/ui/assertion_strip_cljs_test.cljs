(ns re-frame.story.ui.assertion-strip-cljs-test
  "The shared inline assertion strip — the pure projections (`truncate`,
  `summary-line`, `group-by-event`, `value-display`) and the rendered row /
  strip / detail-value hiccup, walked without a React mount."
  (:require [cljs.test :refer-macros [are deftest is]]
            [re-frame.story.ui.assertion-strip :as rf.story.ui.assertion-strip]))

;; ---- pure: truncate ------------------------------------------------------

(deftest truncate-clamps-with-a-single-ellipsis
  (are [s n expected] (= expected (rf.story.ui.assertion-strip/truncate s n))
    "abc" 10 "abc"
    nil   10 ""
    ;; longer than the limit: clamped to it, the ellipsis included
    "aaaaaaaaaaaaaaaaaa" 5 "aaaa…"
    ;; non-string input is coerced through str
    42    10 "42"))

;; ---- pure: summary-line --------------------------------------------------

(deftest summary-line-by-status
  (are [row expected] (= expected (rf.story.ui.assertion-strip/summary-line row))
    ;; :pass is blank — the label already names the assertion
    {:status :pass :detail {:expected 1 :actual 1}}
    ""

    ;; :fail surfaces its :reason, else expected vs actual
    {:status :fail :detail {:reason "values differ"}}
    "values differ"

    {:status :fail :detail {:expected 99 :actual 0}}
    "expected 99 · actual 0"

    ;; :skip surfaces its :reason, else the placeholder
    {:status :skip :detail {:reason "feature gated"}}
    "feature gated"

    {:status :skip :detail {}}
    "skipped"

    ;; :error prefers the error's :message, the more specific of the two...
    {:status :error :detail {:reason "generic" :error {:message "specific boom"}}}
    "specific boom"

    ;; ...then falls back to :reason (pr-str'd when not a string), then to
    ;; "error". It is never blank: a blank summary is a silent grey row
    ;; saying nothing.
    {:status :error :detail {:reason "setup blew up"}}
    "setup blew up"

    {:status :error :detail {:reason {:code 42}}}
    "{:code 42}"

    {:status :error :detail {}}
    "error"

    ;; a non-string :message does not answer for the summary
    {:status :error :detail {:error {:message nil}}}
    "error"

    ;; the :error arm does not leak into :fail
    {:status :fail :detail {:reason "values differ" :error {:message "should not be read"}}}
    "values differ"))

;; ---- pure: group-by-event ------------------------------------------------

(deftest group-by-event-preserves-insertion-order
  ;; Records with no :event (setup assertions, decorator throws) cluster
  ;; under a nil-event group; every group sits where its first record did.
  (let [s1 {:n 1} a1 {:event [:a] :n 2} b {:event [:b] :n 3}
        a2 {:event [:a] :n 4} s2 {:n 5}]
    (is (= [{:event nil  :records [s1 s2]}
            {:event [:a] :records [a1 a2]}
            {:event [:b] :records [b]}]
           (rf.story.ui.assertion-strip/group-by-event [s1 a1 b a2 s2])))))

;; ---- pure: status-glyph map ----------------------------------------------

(deftest status-glyph-shape
  ;; spec/004 §Canonical assertion-strip publishes these four; `✖` keeps an
  ;; errored row distinguishable from a failed one on the shared red band.
  (is (= {:pass "✓" :fail "✗" :skip "⊘" :error "✖"}
         rf.story.ui.assertion-strip/status-glyph)))

;; ---- hiccup walkers ------------------------------------------------------

(defn- find-prop
  "The first prop map in `hiccup` carrying `(= (get m k) v)`, or nil."
  [hiccup k v]
  (letfn [(walk [node]
            (cond
              (and (map? node) (= v (get node k))) node
              (vector? node)                       (some walk node)
              (seq? node)                          (some walk node)
              :else                                nil))]
    (walk hiccup)))

(defn- find-string
  "The first STRING node in `hiccup` containing `s`, or nil — `find-prop`
  sees prop maps only, not bare string children."
  [hiccup s]
  (letfn [(walk [node]
            (cond
              (string? node)  (when (not= -1 (.indexOf node s)) node)
              (vector? node)  (some walk node)
              (seq? node)     (some walk node)
              :else           nil))]
    (walk hiccup)))

(defn- collect
  "Every node in `hiccup` matching `pred`, in document order, without
  descending into a match."
  [pred hiccup]
  (letfn [(walk [node]
            (cond
              (pred node)    [node]
              (vector? node) (mapcat walk node)
              (seq? node)    (mapcat walk node)
              :else          nil))]
    (vec (walk hiccup))))

(defn- data-test-el?
  "Predicate: a hiccup element whose prop map carries `:data-test` `dt`."
  [dt]
  (fn [x] (and (vector? x) (map? (second x)) (= dt (:data-test (second x))))))

(defn- find-detail-value-element
  "The first `[detail-value <label> <v>]` component vector whose label is
  `label`, or nil. `detail-value` is a Reagent component, so `row-detail`
  emits it un-expanded and its inner prop map does not exist yet."
  [hiccup label]
  (first (collect #(and (vector? %)
                        (= rf.story.ui.assertion-strip/detail-value (first %))
                        (= label (second %)))
                  hiccup)))

;; ---- rendered: render-row ------------------------------------------------

(deftest render-row-error-shape-summary-on-message
  ;; spec/004 publishes the -glyph / -label / -summary data-test hooks.
  (let [row    {:status  :error
                :label   ":rf.error/exception"
                :row-key ":rf.error/exception"
                :detail  {:reason nil
                          :error  {:message "no handler registered for :your/setup-event"}}}
        hiccup (rf.story.ui.assertion-strip/render-row row false (fn [_]))]
    (is (some? (find-prop hiccup :data-test "story-canvas-assertion-glyph")))
    (is (some? (find-prop hiccup :data-test "story-canvas-assertion-label")))
    (is (= "no handler registered for :your/setup-event"
           (:title (find-prop hiccup :data-test "story-canvas-assertion-summary")))
        "an errored row's summary is its error message, collapsed or not")))

(deftest render-row-error-detail-renders-error-data
  ;; a captured error's :data routes through detail-value, so a large map
  ;; clamps rather than blowing the panel into the canvas height
  (let [row {:status  :error
             :label   ":rf.error/exception"
             :row-key ":rf.error/exception"
             :detail  {:error {:message "kaboom" :data {:cause :network}}}}]
    (is (= {:cause :network}
           (nth (find-detail-value-element
                  (rf.story.ui.assertion-strip/render-row row true (fn [_]))
                  "error data")
                2)))))

;; ---- rendered: assertion-strip component ---------------------------------
;;
;; The component is a Reagent form-2: the outer fn returns the inner render
;; fn, which returns the hiccup.

(defn- render-strip
  "The strip's raw hiccup. Rows are un-expanded `[render-row row open?
  toggle]` component vectors, which is where their React `:key` sits."
  [assertions]
  (let [inner (rf.story.ui.assertion-strip/assertion-strip assertions)]
    (inner assertions)))

(defn- render-strip-expanded
  "The strip's hiccup with each row component vector replaced by what
  `render-row` returns, as Reagent expands it at mount."
  [assertions]
  (letfn [(expand [node]
            (cond
              (and (vector? node) (= rf.story.ui.assertion-strip/render-row (first node)))
              (apply rf.story.ui.assertion-strip/render-row (rest node))

              (vector? node) (mapv expand node)
              (seq? node)    (map expand node)
              :else          node))]
    (expand (render-strip assertions))))

(deftest assertion-strip-empty-renders-nil
  (is (nil? (render-strip []))))

(deftest assertion-strip-one-row-per-record-failures-open
  ;; Failed AND errored rows land open on first paint; passed and skipped
  ;; rows stay collapsed. The error record is the real captured shape — no
  ;; :status, :passed? false, an :error map — which reaches :error through
  ;; the projection's `(or (:error rec) (:exception rec))` arm; a fixture
  ;; stamping :status would take a different arm.
  (let [hiccup (render-strip-expanded
                 [{:assertion :rf.assert/path-equals
                   :passed? true :payload [[:c] 1] :expected 1 :actual 1}
                  {:assertion :rf.assert/path-equals
                   :passed? false :payload [[:c] 2] :expected 2 :actual 0
                   :reason "values differ"}
                  {:assertion :rf.assert/skipped
                   :passed? false :reason "feature gated"}
                  {:assertion :rf.error/exception
                   :passed?   false
                   :event     [:your/setup-event {}]
                   :reason    nil
                   :error     {:message "no handler registered for :your/setup-event"}}])]
    (is (some? (find-prop hiccup :data-test "story-canvas-assertion-strip")))
    (is (= [["pass" false] ["fail" true] ["skip" false] ["error" true]]
           (mapv (fn [[_ props :as row]]
                   [(:data-status props)
                    (some? (find-prop row :data-test "story-canvas-assertion-detail"))])
                 (collect (data-test-el? "story-canvas-assertion-row") hiccup))))
    (is (some? (find-string hiccup "no handler registered for :your/setup-event"))
        "the captured error's message reaches the strip")))

(deftest assertion-strip-group-heads-only-when-multi-group
  ;; a single group reads cleanly without a head
  (are [events heads]
       (= heads
          (count (collect (data-test-el? "story-canvas-assertion-group-head")
                          (render-strip (mapv (fn [e] {:assertion :rf.assert/path-equals
                                                       :passed?   true
                                                       :event     e})
                                              events)))))
    [[:click] [:click]]  0
    [[:click] [:submit]] 2))

;; Each row and group element carries a unique React :key in its metadata —
;; on the element vector, since a key on a function-call form is dropped. An
;; unkeyed seq warns on every run and fails the Story/Xray feature-load
;; browser gate, and a shape check cannot see it.

(deftest assertion-strip-rows-and-groups-carry-unique-keys
  (let [hiccup  (render-strip [{:assertion :rf.assert/path-equals :passed? true
                                :event [:click] :payload [[:c] 1]}
                               {:assertion :rf.assert/path-equals :passed? false
                                :event [:click] :payload [[:c] 2] :reason "differ"}
                               {:assertion :rf.assert/path-equals :passed? true
                                :event [:submit] :payload [[:c] 3]}])
        keys-of (fn [els] (set (keep #(:key (meta %)) els)))
        rows    (collect #(and (vector? %) (= rf.story.ui.assertion-strip/render-row (first %)))
                         hiccup)
        groups  (collect (data-test-el? "story-canvas-assertion-group") hiccup)]
    (is (= 3 (count rows) (count (keys-of rows))))
    (is (= 2 (count groups) (count (keys-of groups))))))

;; ---- pure: value-display -------------------------------------------------

(deftest value-display-clamps-past-the-cap
  (are [args out] (= out (apply rf.story.ui.assertion-strip/value-display args))
    [42]             {:full "42" :clamped "42" :long? false}
    ["abcdefghij" 5] {:full "\"abcdefghij\"" :clamped "\"abc…" :long? true}))

;; ---- rendered: detail-value ----------------------------------------------

(defn- render-detail-value
  [label v]
  (let [inner (rf.story.ui.assertion-strip/detail-value label v)]
    (inner label v)))

(deftest detail-value-long-renders-reveal-chord-collapsed
  (is (nil? (find-prop (render-detail-value "expected" 7)
                       :data-test "story-canvas-assertion-detail-reveal"))
      "a short value gets no reveal chord")
  (let [hiccup (render-detail-value "actual" {:k (apply str (repeat 300 "x"))})]
    (is (some? (find-prop hiccup :data-test "story-canvas-assertion-detail-reveal")))
    (is (= "false" (:data-revealed (find-prop hiccup :data-test "story-canvas-assertion-detail-value")))
        "a long value starts collapsed")))
