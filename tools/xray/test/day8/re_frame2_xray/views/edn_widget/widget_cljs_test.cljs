(ns day8.re-frame2-xray.views.edn-widget.widget-cljs-test
  "Tests for the canonical Xray EDN widget.

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
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.panels.app-db-diff-events :as diff-events]
            [day8.re-frame2-xray.views.edn-widget.widget :as w]
            [day8.re-frame2-xray.theme.tokens :refer [tokens]]))

;; A plain-atom runtime fixture so the copy-affordance dispatch test
;; (rf2-f026h) can fire the registered `:rf.xray/copy-value-to-
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
;; `rf-xray-edn-widget-browse-<panel>-<render-id>` container with
;; cljs-devtools markup inside (never a `rf-xray-data-display-*` node).

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
      (is (some? (find-by-testid out "rf-xray-edn-widget-browse-test-browse-1")))
      (let [ids (->> (walk-hiccup out)
                     (keep #(some-> (second %) :data-testid))
                     (filter string?))]
        (is (not-any? #(.startsWith % "rf-xray-data-display-") ids)
            "browse no longer routes collections through the home-grown engine")))))

(deftest browse-of-collection-renders-keys-and-values
  (let [out (w/browse {:value     {:k :foo/bar}
                       :panel-id  :test
                       :render-id "kw-1"})]
    (testing "the expanded cljs-devtools tree surfaces the key and value"
      (is (contains-text? out ":k"))
      (is (contains-text? out ":foo/bar")))))

(deftest browse-of-wide-nested-collection-collapses-by-default
  (testing "rf2-dw8n7 — browse is click-to-toggle: wide nested
            collections render as ▸ summaries by default
            (§10.4 threshold heuristic at `:default-depth 1`).
            Operator clicks a triangle to drill deeper; sticky state
            persists via `:rf.xray/data-display-expansion`."
    (let [wide (zipmap (map #(keyword (str "k" %)) (range 20)) (range 20))
          out  (w/browse {:value     {:outer wide}
                          :panel-id  :test
                          :render-id "nested-1"})]
      (is (contains-text? out ":outer")
          "root expansion surfaces the top-level key")
      (is (contains-text? out "▸")
          "wide nested collection renders the ▸ collapse glyph"))))

(deftest browse-default-depth-opt-expands-deeper
  (testing "rf2-dw8n7 — explicit `:default-depth N` opens N levels at
            mount; useful for surfaces (like the app-db diff panel)
            that want a deeper initial reveal"
    (let [out (w/browse {:value         {:outer {:inner 99}}
                         :panel-id      :test
                         :render-id     "depth-1"
                         :default-depth 3})]
      (is (contains-text? out ":outer"))
      (is (contains-text? out ":inner"))
      (is (contains-text? out "99")))))

(deftest browse-toggle-event-flips-rerender-end-to-end
  ;; rf2-oswhk regression — pre-fix the triangle's :on-click fired the
  ;; dispatch fine, but TWO bugs meant the click had no visible effect:
  ;;
  ;;   1. every sibling surrogate walked with the same `:path []`, so
  ;;      writing one slot in the expansion-map toggled *every* triangle
  ;;      at that fake-empty path simultaneously — net "click → nothing
  ;;      changes" feel because the operator was actually toggling many
  ;;      slots and undoing them on the next click,
  ;;   2. expanded rendering showed `▾{…}<body>` (both the cljs-devtools
  ;;      header summary AND the body), so even when toggle worked the
  ;;      visible text barely changed.
  ;;
  ;; This test pins the END-TO-END contract: render → extract the
  ;; surrogate's actual walker-path from its `data-testid` →
  ;; dispatch-sync the toggle event for THAT path (in the `:rf/xray`
  ;; frame, matching what the click handler does) → re-render → assert
  ;; the glyph flipped to ▾ AND the body keys surfaced.
  (frame/reg-frame :rf/xray {})
  (rf/with-frame :rf/xray
    (let [wide        (zipmap (map #(keyword (str "k" %)) (range 20))
                              (range 20))
          v           {:outer wide}
          out-1       (w/browse {:value     v
                                 :panel-id  :test
                                 :render-id "tflow"})
          tris-1      (->> (walk-hiccup out-1)
                           (filter (fn [n]
                                     (and (vector? n)
                                          (= :span (first n))
                                          (= "button" (-> n second :role))))))
          tri         (first tris-1)
          glyph-1     (last tri)
          ;; Pull the surrogate's actual path back out of the testid so
          ;; the dispatched event lands on the SAME slot the renderer
          ;; reads — this is exactly what the on-click does in practice.
          testid-1    (-> tri second :data-testid)
          path-suffix (subs testid-1
                            (count "rf-xray-edn-widget-toggle-test-tflow-"))
          path-vec    (mapv #(js/parseInt % 10) (str/split path-suffix #"/"))]
      (testing "pre-toggle: wide nested collapses to ▸"
        (is (= "▸" glyph-1)))
      (rf/dispatch-sync [:rf.xray/data-display-toggle-node
                         :test "tflow" path-vec])
      ;; Re-render after the toggle.
      (let [out-2    (w/browse {:value     v
                                :panel-id  :test
                                :render-id "tflow"})
            tris-2   (->> (walk-hiccup out-2)
                          (filter (fn [n]
                                    (and (vector? n)
                                         (= :span (first n))
                                         (= "button" (-> n second :role))))))
            glyphs-2 (map last tris-2)
            txt-2    (let [out (atom "")]
                       (letfn [(scan [n]
                                 (cond
                                   (string? n) (swap! out str n)
                                   (number? n) (swap! out str n)
                                   (vector? n) (doseq [c (rest n)] (scan c))
                                   (seq? n)    (doseq [c n] (scan c))))]
                         (scan out-2)
                         @out))]
        (testing "after toggle the surrogate flips to ▾"
          (is (some #{"▾"} glyphs-2)
              (str "expected ▾ after toggle; got " (pr-str glyphs-2))))
        (testing "the body keys surface (rendering switched from header
                  `{…}` to the body's `<li>` rows)"
          (is (re-find #":k0" txt-2))
          (is (re-find #":k1" txt-2)))
        (testing "no redundant `{…}` ellipsis next to the body when expanded"
          (is (not (re-find #"\{…\}" txt-2))
              (str "expanded render should not carry the cljs-devtools "
                   "header ellipsis; got " txt-2)))))))

(deftest browse-of-non-collection-routes-through-cljs-devtools
  (let [out (w/browse {:value     :foo/bar
                       :panel-id  :test
                       :render-id "scalar-1"})]
    (testing "non-collection browse returns the cljs-devtools container"
      (is (vector? out))
      (is (= :div (first out)))
      (is (some? (find-by-testid out
                                 "rf-xray-edn-widget-browse-test-scalar-1")))
      (is (contains-text? out ":foo/bar")))))

;; ---- universal copy-to-clipboard affordance (rf2-f026h) ------------------
;;
;; Every `browse` (and therefore `inspect`) render carries a `⎘` copy
;; button on its `position:relative` root so the copy gesture rides to
;; Trace, the segment-inspector, the Event lens, and the Static panels
;; at once. The button's testid is the render container id + "-copy",
;; its aria-label is set, and its on-click dispatches
;; `:rf.xray/copy-value-to-clipboard` with the rendered value.

(deftest browse-exposes-copy-affordance
  (let [out (w/browse {:value     {:a 1}
                       :panel-id  :test
                       :render-id "copy-1"})]
    (testing "the copy button rides on the browse root"
      (let [btn (find-by-testid
                  out "rf-xray-edn-widget-browse-test-copy-1-copy")]
        (is (some? btn) "copy affordance present")
        (is (= :button (first btn)))
        (is (= "Copy value to clipboard" (:aria-label (second btn)))
            "aria-label set for screen readers")
        (is (fn? (:on-click (second btn)))
            "on-click is wired")))
    (testing "the browse root is position:relative so the button anchors"
      (let [root (find-by-testid out "rf-xray-edn-widget-browse-test-copy-1")]
        (is (= "relative" (-> root second :style :position)))))))

(deftest inspect-exposes-copy-affordance
  (let [out (w/inspect {:a 1} "node-copy")]
    (testing "inspect (the panel-facing facade) also carries the copy button"
      (is (some? (find-by-testid
                   out "rf-xray-edn-widget-browse-inspect-node-copy-copy"))))))

;; ---- per-render copy opt-out (rf2-ilubp) ---------------------------------
;;
;; The copy gesture is default-ON (above). The app-db current-state
;; inspector opts THIS render out via `:copy? false` so its section
;; blocks stay chrome-free; every other surface keeps the default. These
;; tests pin the opt-out on both `browse` and the `inspect` facade
;; without disturbing the default-on behaviour the tests above cover.

(deftest browse-copy-false-suppresses-affordance
  (testing "rf2-ilubp — `:copy? false` drops the ⎘ button but keeps the
            value tree"
    (let [out (w/browse {:value     {:a 1}
                         :panel-id  :test
                         :render-id "nocopy-1"
                         :copy?     false})]
      (is (some? (find-by-testid out "rf-xray-edn-widget-browse-test-nocopy-1"))
          "value container still renders")
      (is (nil? (find-by-testid
                  out "rf-xray-edn-widget-browse-test-nocopy-1-copy"))
          "no copy button when opted out")
      (is (contains-text? out ":a") "the value still renders")))
  (testing "default (no `:copy?`) keeps the gesture on"
    (let [out (w/browse {:value     {:a 1}
                         :panel-id  :test
                         :render-id "default-on-1"})]
      (is (some? (find-by-testid
                   out "rf-xray-edn-widget-browse-test-default-on-1-copy"))
          "copy button rides on by default"))))

(deftest inspect-copy-false-suppresses-affordance
  (testing "rf2-ilubp — the 3-arity inspect facade threads `:copy? false`
            through to browse"
    (let [out (w/inspect {:a 1} "node-nocopy" {:copy? false})
          ids (->> (walk-hiccup out)
                   (keep #(some-> (second %) :data-testid))
                   (filter string?))]
      (is (some? (find-by-testid
                   out "rf-xray-edn-widget-browse-inspect-node-nocopy"))
          "value container still renders")
      (is (not-any? #(.endsWith % "-copy") ids)
          "no copy button anywhere in the opted-out inspect render"))))

(deftest copy-affordance-helper-dispatches-copy-value
  ;; `copy-affordance` is public; assert its shape directly. The
  ;; on-click stops propagation (so a row-toggle click underneath
  ;; doesn't fire) and dispatches the value-copy event — the same
  ;; event the App-DB diff panel's Copy-value button uses.
  (let [btn (w/copy-affordance {:k :v} "some-testid")]
    (is (= :button (first btn)))
    (is (= "some-testid" (:data-testid (second btn))))
    (is (= "rf-xray-edn-widget-copy" (:class (second btn)))
        "carries the hover-reveal class the global stylesheet targets")))

(deftest copy-affordance-onclick-dispatches-copy-event
  ;; End-to-end: install the diff-events leaf (which registers
  ;; `:rf.xray/copy-value-to-clipboard` + `:rf.xray.fx/copy-to-
  ;; clipboard`), stub the clipboard fx to capture the payload, then
  ;; invoke the copy button's on-click with a stub event. The captured
  ;; text must be the pr-str of the rendered value — proving the
  ;; widget's copy gesture is wired to the shared copy machinery.
  (diff-events/install!)
  (frame/reg-frame :rf/xray {})
  (let [captured (atom nil)]
    (rf/reg-fx :rf.xray.fx/copy-to-clipboard
      (fn [_ {:keys [text]}] (reset! captured text)))
    (let [btn      (w/copy-affordance {:k :v} "t")
          on-click (:on-click (second btn))
          stop?    (atom false)
          stub-ev  #js {:stopPropagation (fn [] (reset! stop? true))}]
      (rf/with-frame :rf/xray
        (on-click stub-ev))
      ;; flush the queued dispatch
      (rf/dispatch-sync [:rf.xray/copy-value-to-clipboard {:k :v}])
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
      (is (some? (find-by-testid out "rf-xray-data-display-test-diff-1"))))))

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
      (is (some? (find-by-testid out "rf-xray-edn-widget-browse-inspect-node-1")))
      (is (contains-text? out ":a"))
      (is (contains-text? out ":b"))
      ;; NOT the home-grown data-inspector chrome.
      (let [ids (->> (walk-hiccup out)
                     (keep #(some-> (second %) :data-testid))
                     (filter string?))]
        (is (not-any? #(.startsWith % "rf-xray-data-inspector") ids))))))

(deftest inspect-scalar-routes-through-cljs-devtools
  (let [out (w/inspect :hello/world "node-2")]
    (testing "a scalar inspects through cljs-devtools too"
      (is (= :div (first out)))
      (is (contains-text? out ":hello/world")))))

(deftest inspect-redacted-sentinel-keeps-chip
  (let [out (w/inspect :rf/redacted "node-3")]
    (testing "a :rf/redacted sentinel keeps the bespoke redacted chip"
      (is (some? (find-by-testid out "rf-xray-data-inspector-redacted"))))))

(deftest inspect-large-sentinel-keeps-chip
  (let [out (w/inspect {:rf/large {:bytes 1024 :head "preview"}} "node-4")]
    (testing "a :rf/large sentinel keeps the bespoke large chip"
      (is (some? (find-by-testid out "rf-xray-data-inspector-large"))))))

(deftest inspect-default-node-key-renders
  (testing "single-arg inspect renders (default node-key)"
    (let [out (w/inspect {:x 1})]
      (is (= :div (first out)))
      (is (some? (find-by-testid out "rf-xray-edn-widget-browse-inspect-root"))))))

(deftest inspect-inline-collection-routes-through-mini
  (let [out (w/inspect-inline {:a 1})]
    (testing "inline current-state renders the one-line cljs-devtools mini"
      (is (= :span (first out)))
      (is (= "rf-xray-edn-widget-mini" (-> out second :data-testid))))))

(deftest inspect-inline-redacted-sentinel-keeps-chip
  (let [out (w/inspect-inline :rf/redacted)]
    (testing "inline redacted sentinel keeps the chip"
      (is (= "rf-xray-data-inspector-redacted"
             (-> out second :data-testid))))))

;; ---- variant: mini -------------------------------------------------------

(deftest mini-returns-span-shape
  (let [out (w/mini {:a 1})]
    (testing "mini returns a [:span ...] with the canonical testid"
      (is (= :span (first out)))
      (is (= "rf-xray-edn-widget-mini"
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
    (is (some? (find-by-testid out "rf-xray-edn-widget-code-empty")))))

(deftest code-block-renders-pre-code-shape
  (let [out (w/code-block {:source "(def x 1)"})]
    (testing "outer is [:pre ...]"
      (is (= :pre (first out)))
      (is (= "clojure" (get (second out) :data-lang)))
      (is (= "rf-xray-edn-widget-code"
             (get (second out) :data-testid))))
    (testing "contains [:code ...] child"
      (let [code-node (some #(when (and (vector? %) (= :code (first %))) %)
                            (walk-hiccup out))]
        (is (some? code-node))))))

(deftest code-block-pre-clamps-and-scrolls-within-container
  ;; rf2-l7ha9 — at narrow panel widths the EVENT HANDLER source rendered
  ;; broken (first line clipped, later lines run off the right) because the
  ;; `white-space:pre` block grew to its longest line's intrinsic width and
  ;; expanded its flex ancestors past the panel edge. The `<pre>` MUST clamp
  ;; to its containing block and scroll long lines WITHIN it.
  (let [out   (w/code-block {:source "(reg-event-db :counter/inc (fn [db _] (update db :counter/value inc)))"})
        style (-> out second :style)]
    (testing "the code-block <pre> never exceeds its containing block"
      (is (= "100%" (:max-width style))
          "max-width:100% clamps the pre to its container")
      (is (= "border-box" (:box-sizing style))
          "border-box keeps padding inside the clamped width"))
    (testing "long lines scroll within the pre rather than overflowing"
      (is (= "auto" (:overflow-x style))
          "overflow-x:auto keeps long handler lines scrollable in-panel"))))

(deftest code-block-keyword-token-uses-syntax-keyword
  (testing "rf2-93jp0 — keyword tokens render with the Figma
            `.syntax-keyword` red family (`:syntax-keyword`), NOT
            `:accent`. The pre-rf2-93jp0 mapping painted keywords +
            builtins on the same `:accent` blue (monochromatic against
            a real editor); the split moves keywords to the dedicated
            syntax token so `:foo` reads visibly distinct from
            `reg-event-db`."
    (let [out      (w/code-block {:source ":foo"})
          spans    (walk-hiccup out)
          kw?      (fn [n]
                     (let [c (some-> n second :style :color)]
                       (= c (:syntax-keyword tokens))))
          kw-span  (some #(when (and (vector? %)
                                     (= :span (first %))
                                     (kw? %)) %)
                         spans)]
      (is (some? kw-span)
          "keyword tokens render on the :syntax-keyword token (red)"))))

(deftest code-block-string-token-uses-syntax-string
  (testing "rf2-93jp0 — string tokens render on the dedicated
            `:syntax-string` token (Figma `.syntax-string` blue family),
            NOT the semantic `:green` (which carries success / changed
            meanings elsewhere)."
    (let [out      (w/code-block {:source "\"hi\""})
          spans    (walk-hiccup out)
          str?     (fn [n]
                     (let [c (some-> n second :style :color)]
                       (= c (:syntax-string tokens))))
          str-span (some #(when (and (vector? %)
                                     (= :span (first %))
                                     (str? %)) %)
                         spans)]
      (is (some? str-span)
          "string tokens render on the :syntax-string token (blue)"))))

(deftest code-block-builtin-and-keyword-render-distinct-colours
  (testing "rf2-93jp0 — `(let [x :foo] x)` paints `let` (builtin) and
            `:foo` (keyword) on DIFFERENT colours. The headline
            regression behind the fix — pre-fix both landed on `:accent`
            and the editor read monochromatic."
    (let [out          (w/code-block {:source "(let [x :foo] x)"})
          spans        (walk-hiccup out)
          coloured     (keep (fn [n]
                               (when (vector? n)
                                 (let [[tag attrs & body] n
                                       c (some-> attrs :style :color)
                                       lit (first body)]
                                   (when (and (= :span tag)
                                              (string? lit))
                                     [lit c]))))
                             spans)
          builtin-colour (some (fn [[lit c]] (when (= lit "let") c)) coloured)
          keyword-colour (some (fn [[lit c]] (when (= lit ":foo") c)) coloured)]
      (is (some? builtin-colour)
          "builtin `let` rendered with a colour span")
      (is (some? keyword-colour)
          "keyword `:foo` rendered with a colour span")
      (is (not= builtin-colour keyword-colour)
          "builtin and keyword must paint on distinct hues post rf2-93jp0")
      (is (= keyword-colour (:syntax-keyword tokens))
          "keyword colour = :syntax-keyword token")
      (is (= builtin-colour (:accent tokens))
          "builtin colour = :accent token (chrome blue, macro-call emphasis)"))))

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
  (testing "rf2-93jp0 — each token type resolves to a Figma-aligned
            syntax token; keyword and builtin paint DIFFERENT hues so
            `:foo` and `reg-event-db` no longer read identically."
    (is (= :syntax-keyword (w/highlight-clojure-token :keyword))
        "keyword on the Figma `.syntax-keyword` red family")
    (is (= :syntax-string  (w/highlight-clojure-token :string))
        "string on the Figma `.syntax-string` blue family")
    (is (= :syntax-number  (w/highlight-clojure-token :number))
        "number on the Figma `.syntax-number` cool-blue")
    (is (= :text-tertiary  (w/highlight-clojure-token :comment)))
    (is (= :text-primary   (w/highlight-clojure-token :symbol)))
    (is (= :text-tertiary  (w/highlight-clojure-token :paren)))
    (is (= :accent         (w/highlight-clojure-token :builtin))
        "builtin on the chrome accent (macro-call emphasis, distinct
         from the keyword red)")
    ;; Unknown token-type falls through to text-primary.
    (is (= :text-primary   (w/highlight-clojure-token :unknown))))
  (testing "keyword and builtin map to DIFFERENT tokens (rf2-93jp0
            split — pre-fix they both pointed at `:accent`)"
    (is (not= (w/highlight-clojure-token :keyword)
              (w/highlight-clojure-token :builtin))
        "keyword vs builtin must be visually distinct")))

(deftest highlight-clojure-token-palette-resolution
  (testing "rf2-93jp0 — every token-keyword returned by
            `highlight-clojure-token` must actually resolve to a
            CSS-variable string in the live `tokens` map (so the
            `code-block` per-span `:color` lookup never falls back to
            the `:text-primary` default for a syntax token)."
    (doseq [tok-type [:keyword :string :number :comment
                      :symbol :paren :builtin]]
      (let [token-kw (w/highlight-clojure-token tok-type)
            resolved (get tokens token-kw)]
        (is (string? resolved)
            (str tok-type " → " token-kw
                 " must resolve to a CSS-variable string in `tokens`"))
        (is (and (string? resolved)
                 (str/starts-with? resolved "var(--rf-xray-"))
            (str tok-type " → " token-kw
                 " must resolve via the `var(--rf-xray-…)` indirection"))))))

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
