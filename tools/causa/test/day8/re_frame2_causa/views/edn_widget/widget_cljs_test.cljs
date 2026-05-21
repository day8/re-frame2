(ns day8.re-frame2-causa.views.edn-widget.widget-cljs-test
  "Tests for the canonical Causa EDN widget.

  ## What's under test

  1. **Variant dispatch** — `browse` / `diff` / `mini` / `render` route
     to the right underlying engine call shape. `browse` of a
     collection routes through `data-display/render-tree`; `browse` of
     a non-collection routes through `cljs-devtools-render`; `mini`
     always routes through `cljs-devtools-render`.
  2. **Code-block tokenizer** — `tokenize-clojure` classifies keywords,
     strings, numbers, parens, builtins, and plain symbols correctly.
     This is the source-text highlighter (not CLJS-value rendering;
     cljs-devtools owns values, this tokenizer owns source strings).
  3. **Code-block rendering** — `code-block` returns the expected
     `[:pre [:code ...]]` shape with per-token colour spans.
  4. **Mini chrome** — `:title`, `:data-pr`, and `:data-testid`
     attributes are set; long pr-str gets truncated in `:data-pr`.

  Pure-data scope — no DOM mount; hiccup-shape assertions only."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.app-db-diff-events :as diff-events]
            [day8.re-frame2-causa.views.edn-widget.widget :as w]
            [day8.re-frame2-causa.theme.tokens :refer [tokens]]))

;; A plain-atom runtime fixture so the copy-affordance dispatch test
;; (rf2-f026h) can fire the registered `:rf.causa/copy-value-to-
;; clipboard` event end-to-end. The pure-data tests below don't need
;; it but are unaffected by its presence.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- helpers ------------------------------------------------------------

(defn- walk-hiccup
  [tree]
  (let [out (atom [])]
    (letfn [(walk [node]
              (when (vector? node)
                (swap! out conj node)
                (doseq [child (rest node)]
                  (cond
                    (vector? child) (walk child)
                    (seq? child)    (doseq [c child] (walk c))))))]
      (walk tree))
    @out))

(defn- find-by-testid
  [tree id]
  (->> (walk-hiccup tree)
       (filter (fn [n]
                 (and (vector? n)
                      (map? (second n))
                      (= id (get (second n) :data-testid)))))
       first))

;; ---- variant: browse -----------------------------------------------------
;;
;; Per Mike-direction 2026-05-21 (rf2-dmso5) browse mode is the
;; re-frame-10x current-state look: collections AND scalars route
;; through cljs-devtools. The home-grown `data-display` engine is now
;; diff-only. So `browse` of either a map or a scalar produces the
;; `rf-causa-edn-widget-browse-<panel>-<render-id>` container with
;; cljs-devtools markup inside (never a `rf-causa-data-display-*` node).

(defn- contains-text?
  "True when some string/number descendant of `tree` contains `s`.
  cljs-devtools renders numeric leaves as JS numbers, so scan both."
  [tree s]
  (let [hit (atom false)
        re  (re-pattern s)]
    (letfn [(scan [n]
              (cond
                (string? n)  (when (re-find re n) (reset! hit true))
                (number? n)  (when (re-find re (str n)) (reset! hit true))
                (boolean? n) (when (re-find re (str n)) (reset! hit true))
                (vector? n)  (doseq [c (rest n)] (scan c))
                (seq? n)     (doseq [c n] (scan c))))]
      (scan tree))
    @hit))

(deftest browse-of-collection-routes-through-cljs-devtools
  (let [out (w/browse {:value     {:a 1 :b 2}
                       :panel-id  :test
                       :render-id "browse-1"})]
    (testing "browse of a map returns the cljs-devtools browse container"
      (is (vector? out))
      (is (= :div (first out)))
      ;; The widget's cljs-devtools browse path stamps this testid;
      ;; the home-grown data-display root testid must NOT appear.
      (is (some? (find-by-testid out "rf-causa-edn-widget-browse-test-browse-1")))
      (let [ids (->> (walk-hiccup out)
                     (keep #(some-> (second %) :data-testid))
                     (filter string?))]
        (is (not-any? #(.startsWith % "rf-causa-data-display-") ids)
            "browse no longer routes collections through the home-grown engine")))))

(deftest browse-of-collection-renders-keys-and-values
  (let [out (w/browse {:value     {:k :foo/bar}
                       :panel-id  :test
                       :render-id "kw-1"})]
    (testing "the expanded cljs-devtools tree surfaces the key and value"
      (is (contains-text? out ":k"))
      (is (contains-text? out ":foo/bar")))))

(deftest browse-of-nested-collection-expands-recursively
  (let [out (w/browse {:value     {:outer {:inner 99}}
                       :panel-id  :test
                       :render-id "nested-1"})]
    (testing "a nested map's inner key + value render (full expansion)"
      (is (contains-text? out ":outer"))
      (is (contains-text? out ":inner"))
      (is (contains-text? out "99")))))

(deftest browse-of-non-collection-routes-through-cljs-devtools
  (let [out (w/browse {:value     :foo/bar
                       :panel-id  :test
                       :render-id "scalar-1"})]
    (testing "non-collection browse returns the cljs-devtools container"
      (is (vector? out))
      (is (= :div (first out)))
      (is (some? (find-by-testid out
                                 "rf-causa-edn-widget-browse-test-scalar-1")))
      (is (contains-text? out ":foo/bar")))))

;; ---- universal copy-to-clipboard affordance (rf2-f026h) ------------------
;;
;; Every `browse` (and therefore `inspect`) render carries a `⎘` copy
;; button on its `position:relative` root so the copy gesture rides to
;; Trace, the segment-inspector, the Event lens, and the Static panels
;; at once. The button's testid is the render container id + "-copy",
;; its aria-label is set, and its on-click dispatches
;; `:rf.causa/copy-value-to-clipboard` with the rendered value.

(deftest browse-exposes-copy-affordance
  (let [out (w/browse {:value     {:a 1}
                       :panel-id  :test
                       :render-id "copy-1"})]
    (testing "the copy button rides on the browse root"
      (let [btn (find-by-testid
                  out "rf-causa-edn-widget-browse-test-copy-1-copy")]
        (is (some? btn) "copy affordance present")
        (is (= :button (first btn)))
        (is (= "Copy value to clipboard" (:aria-label (second btn)))
            "aria-label set for screen readers")
        (is (fn? (:on-click (second btn)))
            "on-click is wired")))
    (testing "the browse root is position:relative so the button anchors"
      (let [root (find-by-testid out "rf-causa-edn-widget-browse-test-copy-1")]
        (is (= "relative" (-> root second :style :position)))))))

(deftest inspect-exposes-copy-affordance
  (let [out (w/inspect {:a 1} "node-copy")]
    (testing "inspect (the panel-facing facade) also carries the copy button"
      (is (some? (find-by-testid
                   out "rf-causa-edn-widget-browse-inspect-node-copy-copy"))))))

(deftest copy-affordance-helper-dispatches-copy-value
  ;; `copy-affordance` is public; assert its shape directly. The
  ;; on-click stops propagation (so a row-toggle click underneath
  ;; doesn't fire) and dispatches the value-copy event — the same
  ;; event the App-DB diff panel's Copy-value button uses.
  (let [btn (w/copy-affordance {:k :v} "some-testid")]
    (is (= :button (first btn)))
    (is (= "some-testid" (:data-testid (second btn))))
    (is (= "rf-causa-edn-widget-copy" (:class (second btn)))
        "carries the hover-reveal class the global stylesheet targets")))

(deftest copy-affordance-onclick-dispatches-copy-event
  ;; End-to-end: install the diff-events leaf (which registers
  ;; `:rf.causa/copy-value-to-clipboard` + `:rf.causa.fx/copy-to-
  ;; clipboard`), stub the clipboard fx to capture the payload, then
  ;; invoke the copy button's on-click with a stub event. The captured
  ;; text must be the pr-str of the rendered value — proving the
  ;; widget's copy gesture is wired to the shared copy machinery.
  (diff-events/install!)
  (frame/reg-frame :rf/causa {})
  (let [captured (atom nil)]
    (rf/reg-fx :rf.causa.fx/copy-to-clipboard
      (fn [_ {:keys [text]}] (reset! captured text)))
    (let [btn      (w/copy-affordance {:k :v} "t")
          on-click (:on-click (second btn))
          stop?    (atom false)
          stub-ev  #js {:stopPropagation (fn [] (reset! stop? true))}]
      (rf/with-frame :rf/causa
        (on-click stub-ev))
      ;; flush the queued dispatch
      (rf/dispatch-sync [:rf.causa/copy-value-to-clipboard {:k :v}])
      (is (true? @stop?) "on-click stops propagation so a row-toggle won't fire")
      (is (= (pr-str {:k :v}) @captured)
          "clipboard fx received the pr-str of the value"))))

(deftest redacted-sentinel-has-no-copy-affordance
  ;; Security posture: `:rf/redacted` keeps the bespoke chip chrome and
  ;; never routes through `browse`, so it gets NO copy gesture — a
  ;; redacted value must never be copyable. (spec/015 + 007 §Sentinel)
  (let [out (w/inspect :rf/redacted "node-redacted")]
    (testing "no copy button on a redacted sentinel"
      (let [ids (->> (walk-hiccup out)
                     (keep #(some-> (second %) :data-testid))
                     (filter string?))]
        (is (not-any? #(.endsWith % "-copy") ids))))))

;; ---- variant: diff -------------------------------------------------------
;;
;; Diff mode (Event panel `:db` before→after smallest-diff) STAYS on
;; the home-grown `data-display/render-tree` engine — cljs-devtools has
;; no diff vocabulary. rf2-dmso5 must not disturb this.

(deftest diff-emits-diff-tree
  (let [out (w/diff {:before    {:a 1}
                     :after     {:a 2}
                     :panel-id  :test
                     :render-id "diff-1"})]
    (testing "diff returns the home-grown data-display diff-mode tree"
      (is (vector? out))
      (is (= :div (first out)))
      (is (some? (find-by-testid out "rf-causa-data-display-test-diff-1"))))))

;; ---- facade: inspect / inspect-inline (current-state, rf2-dmso5) ---------
;;
;; The panel-facing `inspect` facade is the current-state renderer. Per
;; rf2-dmso5 it routes the value through cljs-devtools (browse) EXCEPT
;; for the spec/015 data-classification sentinels, which keep their
;; bespoke chip chrome from `theme.data-inspector`.

(deftest inspect-collection-routes-through-cljs-devtools
  (let [out (w/inspect {:a 1 :b 2} "node-1")]
    (testing "a plain map inspects through the cljs-devtools browse path"
      (is (= :div (first out)))
      (is (some? (find-by-testid out "rf-causa-edn-widget-browse-inspect-node-1")))
      (is (contains-text? out ":a"))
      (is (contains-text? out ":b"))
      ;; NOT the home-grown data-inspector chrome.
      (let [ids (->> (walk-hiccup out)
                     (keep #(some-> (second %) :data-testid))
                     (filter string?))]
        (is (not-any? #(.startsWith % "rf-causa-data-inspector") ids))))))

(deftest inspect-scalar-routes-through-cljs-devtools
  (let [out (w/inspect :hello/world "node-2")]
    (testing "a scalar inspects through cljs-devtools too"
      (is (= :div (first out)))
      (is (contains-text? out ":hello/world")))))

(deftest inspect-redacted-sentinel-keeps-chip
  (let [out (w/inspect :rf/redacted "node-3")]
    (testing "a :rf/redacted sentinel keeps the bespoke redacted chip"
      (is (some? (find-by-testid out "rf-causa-data-inspector-redacted"))))))

(deftest inspect-large-sentinel-keeps-chip
  (let [out (w/inspect {:rf/large {:bytes 1024 :head "preview"}} "node-4")]
    (testing "a :rf/large sentinel keeps the bespoke large chip"
      (is (some? (find-by-testid out "rf-causa-data-inspector-large"))))))

(deftest inspect-default-node-key-renders
  (testing "single-arg inspect renders (default node-key)"
    (let [out (w/inspect {:x 1})]
      (is (= :div (first out)))
      (is (some? (find-by-testid out "rf-causa-edn-widget-browse-inspect-root"))))))

(deftest inspect-inline-collection-routes-through-mini
  (let [out (w/inspect-inline {:a 1})]
    (testing "inline current-state renders the one-line cljs-devtools mini"
      (is (= :span (first out)))
      (is (= "rf-causa-edn-widget-mini" (-> out second :data-testid))))))

(deftest inspect-inline-redacted-sentinel-keeps-chip
  (let [out (w/inspect-inline :rf/redacted)]
    (testing "inline redacted sentinel keeps the chip"
      (is (= "rf-causa-data-inspector-redacted"
             (-> out second :data-testid))))))

;; ---- variant: mini -------------------------------------------------------

(deftest mini-returns-span-shape
  (let [out (w/mini {:a 1})]
    (testing "mini returns a [:span ...] with the canonical testid"
      (is (= :span (first out)))
      (is (= "rf-causa-edn-widget-mini"
             (-> out second :data-testid))))))

(deftest mini-short-value-data-pr-not-truncated
  (let [out   (w/mini {:a 1})
        attrs (second out)]
    (testing "short pr-str fits — no ellipsis in :data-pr"
      (is (= (pr-str {:a 1}) (:title attrs)))
      (is (not (re-find #"…$" (:data-pr attrs)))))))

(deftest mini-long-value-data-pr-truncated-but-title-full
  (let [v   (zipmap (map #(keyword (str "key-" %)) (range 30))
                    (range 30))
        out (w/mini v 40)
        attrs (second out)]
    (testing "ellipsis appended to :data-pr when over max-len"
      (is (re-find #"…$" (:data-pr attrs))))
    (testing ":title attr carries the full pr-str"
      (is (= (pr-str v) (:title attrs))))))

(deftest mini-renders-cljs-devtools-markup-inside
  (let [out   (w/mini :hello)
        spans (walk-hiccup out)]
    (testing "mini embeds cljs-devtools-rendered hiccup as children"
      ;; cljs-devtools renders a keyword as a coloured span; we should
      ;; see at least one nested :span under the outer :span.
      (is (some #(and (vector? %) (= :span (first %))) (rest spans))))))

;; ---- code-block tokenizer ------------------------------------------------

(deftest classify-token-keyword
  (is (= :keyword  (w/classify-token ":foo")))
  (is (= :keyword  (w/classify-token ":ns/foo"))))

(deftest classify-token-string
  (is (= :string   (w/classify-token "\"hi\"")))
  (is (= :string   (w/classify-token "\"with \\\"quote\\\"\""))))

(deftest classify-token-number
  (is (= :number   (w/classify-token "42")))
  (is (= :number   (w/classify-token "-3.14"))))

(deftest classify-token-comment
  (is (= :comment  (w/classify-token "; hi"))))

(deftest classify-token-paren
  (is (= :paren    (w/classify-token "(")))
  (is (= :paren    (w/classify-token "}"))))

(deftest classify-token-builtin
  (is (= :builtin  (w/classify-token "reg-event-db")))
  (is (= :builtin  (w/classify-token "let"))))

(deftest classify-token-symbol
  (is (= :symbol   (w/classify-token "my-symbol")))
  (is (= :symbol   (w/classify-token "x"))))

(deftest tokenize-clojure-roundtrip
  (testing "concatenating tokenized literals reconstructs the source"
    (let [src  "(reg-event-db :foo (fn [db [_ x]] (assoc db :y x)))"
          toks (w/tokenize-clojure src)]
      (is (= src (apply str (map second toks)))))))

(deftest tokenize-clojure-keyword-classification
  (let [toks (w/tokenize-clojure "(:foo bar)")
        kws  (filter #(= :keyword (first %)) toks)]
    (is (= 1 (count kws)))
    (is (= ":foo" (second (first kws))))))

(deftest tokenize-clojure-string-classification
  (let [toks (w/tokenize-clojure "(def s \"hello\")")
        strs (filter #(= :string (first %)) toks)]
    (is (= 1 (count strs)))
    (is (= "\"hello\"" (second (first strs))))))

(deftest tokenize-clojure-builtin-classification
  (let [toks (w/tokenize-clojure "(reg-event-db :foo)")
        blt  (filter #(= :builtin (first %)) toks)]
    (is (= 1 (count blt)))
    (is (= "reg-event-db" (second (first blt))))))

;; ---- code-block render ---------------------------------------------------

(deftest code-block-empty-source-renders-placeholder
  (let [out (w/code-block {:source nil})]
    (is (some? (find-by-testid out "rf-causa-edn-widget-code-empty")))))

(deftest code-block-renders-pre-code-shape
  (let [out (w/code-block {:source "(def x 1)"})]
    (testing "outer is [:pre ...]"
      (is (= :pre (first out)))
      (is (= "clojure" (get (second out) :data-lang)))
      (is (= "rf-causa-edn-widget-code"
             (get (second out) :data-testid))))
    (testing "contains [:code ...] child"
      (let [code-node (some #(when (and (vector? %) (= :code (first %))) %)
                            (walk-hiccup out))]
        (is (some? code-node))))))

(deftest code-block-keyword-token-uses-accent-violet
  (let [out      (w/code-block {:source ":foo"})
        spans    (walk-hiccup out)
        violet?  (fn [n]
                   (let [c (some-> n second :style :color)]
                     (= c (:accent-violet tokens))))
        kw-span  (some #(when (and (vector? %)
                                   (= :span (first %))
                                   (violet? %)) %)
                       spans)]
    (is (some? kw-span)
        "keyword tokens render with accent-violet colour")))

(deftest code-block-string-token-uses-green
  (let [out     (w/code-block {:source "\"hi\""})
        spans   (walk-hiccup out)
        green?  (fn [n]
                  (let [c (some-> n second :style :color)]
                    (= c (:green tokens))))
        str-span (some #(when (and (vector? %)
                                   (= :span (first %))
                                   (green? %)) %)
                       spans)]
    (is (some? str-span))))

;; ---- zprint pre-format ---------------------------------------------------

(deftest format-source-nil-input-returns-input
  (testing "nil source survives — format-source never throws"
    (is (nil? (w/format-source nil)))))

(deftest format-source-empty-input-returns-input
  (testing "empty / blank source survives unchanged"
    (is (= "" (w/format-source "")))))

(deftest format-source-pretty-prints-clojure
  (let [src       "(reg-event-db :counter/inc (fn [db _] (update db :n inc)))"
        formatted (w/format-source src)]
    (testing "zprint reformats the input (canonical line-breaks)"
      ;; A well-formed registration on one line gets canonicalised —
      ;; either the same string back (zprint thinks it's already ideal)
      ;; or one with newlines introduced. The output MUST still be a
      ;; valid string.
      (is (string? formatted)))
    (testing "the round-trip text contains the same form contents"
      ;; zprint never drops form contents — every token in the input
      ;; appears in the output (possibly across more lines).
      (is (re-find #"reg-event-db" formatted))
      (is (re-find #":counter/inc" formatted))
      (is (re-find #"update" formatted)))))

(deftest format-source-malformed-input-falls-through
  (testing "zprint parse failure returns the original input unchanged"
    ;; Unmatched paren — zprint's parser refuses; format-source's
    ;; try/catch falls through.
    (let [bad "(reg-event-db :foo "]
      (is (= bad (w/format-source bad))))))

(deftest code-block-pre-formats-via-zprint
  (let [out  (w/code-block {:source "(reg-event-db :counter/inc (fn [db _] (update db :n inc)))"})
        pre  (some #(when (and (vector? %) (= :pre (first %))) %)
                   (walk-hiccup out))
        attrs (when pre (second pre))]
    (testing "code-block emits the :pre root with a :data-formatted attr"
      (is (some? attrs))
      (is (contains? attrs :data-formatted)))))

(deftest code-block-non-clojure-lang-skips-format
  (let [out  (w/code-block {:source "function f(){}" :lang :javascript})
        pre  (some #(when (and (vector? %) (= :pre (first %))) %)
                   (walk-hiccup out))
        attrs (when pre (second pre))]
    (testing "non-clojure lang skips the zprint pre-format stage"
      (is (= "false" (:data-formatted attrs))
          ":data-formatted = false when zprint did not run"))))

;; ---- highlight-clojure-token mapping -------------------------------------

(deftest highlight-clojure-token-mapping
  (is (= :accent-violet (w/highlight-clojure-token :keyword)))
  (is (= :green         (w/highlight-clojure-token :string)))
  (is (= :cyan          (w/highlight-clojure-token :number)))
  (is (= :text-tertiary (w/highlight-clojure-token :comment)))
  (is (= :text-primary  (w/highlight-clojure-token :symbol)))
  (is (= :accent-violet (w/highlight-clojure-token :builtin)))
  ;; Unknown token-type falls through to text-primary.
  (is (= :text-primary  (w/highlight-clojure-token :unknown))))

;; ---- render dispatcher ---------------------------------------------------

(deftest render-defaults-to-browse
  (let [out (w/render {:value     {:a 1}
                       :panel-id  :test
                       :render-id "dispatch-1"})]
    (is (= :div (first out)))))

(deftest render-routes-to-diff-when-variant-diff
  (let [out (w/render {:variant   :diff
                       :before    {:a 1}
                       :after     {:a 2}
                       :panel-id  :test
                       :render-id "dispatch-2"})]
    (is (= :div (first out)))))

(deftest render-routes-to-mini-when-variant-mini
  (let [out (w/render {:variant :mini
                       :value   {:a 1}})]
    (is (= :span (first out)))))
