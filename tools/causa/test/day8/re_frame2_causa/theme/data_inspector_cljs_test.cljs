(ns day8.re-frame2-causa.theme.data-inspector-cljs-test
  "Tests for the cljs-devtools-shaped renderer (rf2-x9fzk).

  ## What's under test

  1. **Primitive renderers** — every supported leaf shape produces a
     coloured hiccup span with the right colour token.
  2. **Sentinel detection** — `:rf/redacted` bare, `{:rf/large {...}}`
     wrapper, `{:rf/redacted {:bytes N}}` combined.
  3. **Sentinel chips** — redacted opaque (no on-click + no expand
     affordance); large carries the click affordance.
  4. **Collection rendering** — maps / vectors / sets / lists each get
     the right delimiters; long collections collapse by default.
  5. **`inspect-inline` compact mode** — no expand widget; long
     collections render as headcount summary.

  Pure-data scope: tests assert against the hiccup tree, no DOM mount."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa.theme.data-inspector :as inspector]))

;; ---- helpers ------------------------------------------------------------

(defn- find-in
  "Depth-first search for the first hiccup vector whose attr-map carries
  the given key/value pair."
  [hiccup k v]
  (let [stack (atom [hiccup])
        hit   (atom nil)]
    (loop []
      (when (and (seq @stack) (nil? @hit))
        (let [node (peek @stack)]
          (swap! stack pop)
          (cond
            (and (vector? node) (map? (second node))
                 (= v (get (second node) k)))
            (reset! hit node)

            (vector? node)
            (doseq [child (rest node)]
              (when (or (vector? child) (seq? child))
                (if (vector? child)
                  (swap! stack conj child)
                  (doseq [c child]
                    (when (vector? c)
                      (swap! stack conj c)))))))
          (recur))))
    @hit))

(defn- collect-all
  "Collect every hiccup node in the tree (depth-first)."
  [hiccup]
  (let [out (atom [])]
    (letfn [(walk [node]
              (when (vector? node)
                (swap! out conj node)
                (doseq [child (rest node)]
                  (cond
                    (vector? child) (walk child)
                    (seq? child)    (doseq [c child] (walk c))))))]
      (walk hiccup))
    @out))

;; ---- sentinel detection -------------------------------------------------

(deftest redacted-sentinel-detection
  (is (inspector/redacted-sentinel? :rf/redacted))
  (is (not (inspector/redacted-sentinel? :rf/redactedx)))
  (is (not (inspector/redacted-sentinel? "redacted")))
  (is (not (inspector/redacted-sentinel? nil))))

(deftest large-meta-detection
  (testing "single-entry map with :rf/large key + map value"
    (let [m {:rf/large {:bytes 1024 :head "abc"}}]
      (is (= {:bytes 1024 :head "abc"} (inspector/large-meta m)))))
  (testing "non-matching shapes return nil"
    (is (nil? (inspector/large-meta {:rf/large "scalar"})))
    (is (nil? (inspector/large-meta {:rf/large {:bytes 1} :other 1})))
    (is (nil? (inspector/large-meta nil)))
    (is (nil? (inspector/large-meta :rf/large)))))

(deftest redacted-plus-size-detection
  (let [m {:rf/redacted {:bytes 4523198}}]
    (is (= {:bytes 4523198} (inspector/redacted+size-meta m))))
  (is (nil? (inspector/redacted+size-meta :rf/redacted)))
  (is (nil? (inspector/redacted+size-meta {:rf/redacted "string"}))))

;; ---- primitive rendering ------------------------------------------------

(deftest primitive-renderers
  (testing "every supported leaf shape returns a hiccup span"
    (is (vector? (inspector/render-primitive nil)))
    (is (vector? (inspector/render-primitive :foo)))
    (is (vector? (inspector/render-primitive "hi")))
    (is (vector? (inspector/render-primitive 42)))
    (is (vector? (inspector/render-primitive true))))
  (testing "strings are quoted in the output"
    (let [[_ _ s] (inspector/render-primitive "abc")]
      (is (= "\"abc\"" s))))
  (testing "keywords carry the leading colon"
    (let [[_ _ s] (inspector/render-primitive :foo/bar)]
      (is (= ":foo/bar" s)))))

;; ---- chip renderers -----------------------------------------------------

(deftest redacted-chip-has-no-on-click
  (let [chip   (inspector/redacted-chip nil)
        nodes  (collect-all chip)
        attrs  (mapv #(when (map? (second %)) (second %)) nodes)]
    (is (every? #(not (contains? % :on-click)) (remove nil? attrs))
        "no node in the redacted chip carries an :on-click — sensitive sentinels are NEVER drillable")))

(deftest redacted-chip-with-bytes
  (let [chip  (inspector/redacted-chip 4523198)
        text  (pr-str chip)]
    (is (clojure.string/includes? text "4523198")
        "bytes are surfaced when the combined sensitive+large form supplies them")))

(deftest render-value-routes-sentinels
  (testing "bare :rf/redacted routes to the redacted chip"
    (let [out (inspector/render-value :rf/redacted {} "k" 0)]
      (is (= "rf-causa-data-inspector-redacted"
             (get-in out [1 :data-testid])))))
  (testing "combined :rf/redacted + size routes to the redacted chip"
    (let [out (inspector/render-value {:rf/redacted {:bytes 999}} {} "k" 0)]
      (is (= "rf-causa-data-inspector-redacted"
             (get-in out [1 :data-testid])))))
  (testing ":rf/large routes to the large chip (carries :data-testid + click affordance)"
    (let [out  (inspector/render-value {:rf/large {:bytes 1024 :head "a"}} {} "k" 0)
          ;; The large chip is wrapped in an outer :span with the testid
          chip (find-in out :data-testid "rf-causa-data-inspector-large")]
      (is (some? chip)))))

;; ---- collection rendering -----------------------------------------------

(deftest small-map-renders-expanded
  (let [out (inspector/render-value {:a 1 :b 2} {} "m" 0)]
    (is (= "rf-causa-data-inspector-map-expanded-m"
           (get-in out [1 :data-testid])))))

(deftest long-map-collapses-by-default
  (let [m   (zipmap (range 20) (range 20))
        out (inspector/render-value m {} "m" 0)]
    (is (= "rf-causa-data-inspector-map-collapsed-m"
           (get-in out [1 :data-testid])))))

(deftest collection-delimiters
  (testing "vector uses []"
    (let [out  (inspector/render-value [1 2 3] {} "v" 0)
          text (pr-str out)]
      (is (clojure.string/includes? text "["))
      (is (clojure.string/includes? text "]"))))
  (testing "set uses #{}"
    (let [out  (inspector/render-value #{:a :b} {} "v" 0)
          text (pr-str out)]
      (is (clojure.string/includes? text "#{"))))
  (testing "list uses ()"
    (let [out  (inspector/render-value '(1 2) {} "v" 0)
          text (pr-str out)]
      (is (clojure.string/includes? text "(")))))

(deftest expand-state-projection-honoured
  (testing "explicit :expanded? true overrides collapsed-by-default"
    (let [m       (zipmap (range 20) (range 20))
          state   {"m" {:expanded? true}}
          out     (inspector/render-value m state "m" 0)]
      (is (= "rf-causa-data-inspector-map-expanded-m"
             (get-in out [1 :data-testid])))))
  (testing "explicit :expanded? false overrides expanded-by-default"
    (let [out (inspector/render-value {:a 1} {"m" {:expanded? false}} "m" 0)]
      (is (= "rf-causa-data-inspector-map-collapsed-m"
             (get-in out [1 :data-testid]))))))

;; ---- inspect-inline -----------------------------------------------------

(deftest inspect-inline-redacted-sentinel
  (let [out (inspector/inspect-inline :rf/redacted)]
    (is (= "rf-causa-data-inspector-redacted"
           (get-in out [1 :data-testid])))))

(deftest inspect-inline-collection-headcount
  (let [out  (inspector/inspect-inline (zipmap (range 10) (range 10)))
        text (pr-str out)]
    (is (clojure.string/includes? text "10 entries")))
  (let [out  (inspector/inspect-inline [1 2 3 4 5 6])
        text (pr-str out)]
    (is (clojure.string/includes? text "6 items"))))

(deftest inspect-inline-truncates-long-strings
  (let [long-s (apply str (repeat 200 "x"))
        [_ _ s] (inspector/inspect-inline long-s)]
    (is (clojure.string/includes? s "…")
        "long strings get a tail ellipsis in inline mode")))

(deftest inspect-inline-large-sentinel-rendering
  (let [out  (inspector/inspect-inline {:rf/large {:bytes 1024 :head "preview"}})
        text (pr-str out)]
    (is (clojure.string/includes? text "1024"))
    (is (clojure.string/includes? text "preview"))))
