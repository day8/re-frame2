(ns re-frame.ui.proof-pack.library-dom-cljs-test
  "rf2-kxork — the proof pack RENDERED. G-18's two consumers
  (`single_view` / `all_views`) root the library's view VARS into a global JS
  array so Closure's advanced DCE can be measured; neither one ever renders a
  node. That proves ISOLATION and nothing about behaviour, so until this file
  landed `selection-controller` and `inline-popover` were rendered NOWHERE in
  the corpus and the other four were only approximated by differently-shaped
  stand-ins in the cross-host parity corpus.

  This namespace mounts EVERY view of `re-frame.ui.proof-pack.library` on a
  real React root and exercises it — the REAL view, never a re-implementation.
  Each test drives exactly one view through its own frame, and every assertion
  is scoped to that view's own render sentinel (`data-pp=...`) or to its own
  frame's app-db, so a neighbouring view's breakage cannot satisfy or falsify
  it. That scoping is deliberate: an aggregate or process-global observation
  can pass on another namespace's leftovers (rf2-veyfp).

  WHY THE PARITY CORPUS IS NOT THE VEHICLE. `parity_fixtures.cljc` is a
  DUAL-EMITTER `.cljc` corpus: every row must render on the JVM as well as
  through react-dom/server. The library is `.cljs`, and four of its six views
  are host-bearing — `ui/sub` (controlled-input, safe-form-control), `ui/local`
  + `ui/handler` (selection-controller), `ui/effect :connect` (inline-popover)
  — so they have no JVM side to compare and can never BE a corpus row. The
  corpus keeps only the two grammar shapes it genuinely owns, under honest
  names; behaviour lives here.

  The whole file self-skips under :node (no `js/document`), so a green
  `npm run test:cljs` proves nothing here — `npm run test:browser` is the
  suite that runs it."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.proof-pack.library :as lib]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn reactive/reset-scheduler!}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- host-turn! []
  (js/Promise. (fn [resolve _] (js/setTimeout #(resolve nil) 0))))

(defn- dispatch-native! [el event] (.dispatchEvent el event))
(defn- click! [el] (dispatch-native! el (js/MouseEvent. "click" #js {:bubbles true})))
(defn- input! [el value]
  (set! (.-value el) value)
  (dispatch-native! el (js/Event. "input" #js {:bubbles true :cancelable true})))

(defn- app-db [frame-id] @(:app-db (frame/frame frame-id)))
(defn- make-frame [id db] (rf/make-frame {:id id :initial-events [[:rf/set-db db]]}))

;; Registrations are process-global but the per-test runtime reset wipes the
;; registrar, so each test re-registers before mounting. `register-pp!` is the
;; library's OWN registration entry point — the one a consumer would call.
(defn- register! []
  (lib/register-pp!)
  (rf/reg-event ::typed (fn [{:keys [db]} [_ value]] {:db (assoc db :text value)})))

;; Sentinel selectors — each view's own render sentinel is the scope of its
;; assertions. Spelled out (not derived) so a sentinel rename reds this file
;; loudly rather than silently widening the scope to "any view".
(def ^:private sel-controlled-input "[data-pp='rf2-pp-controlled-input-sentinel']")
(def ^:private sel-selection-controller "[data-pp='rf2-pp-selection-controller-sentinel']")
(def ^:private sel-list-cell "[data-pp='rf2-pp-list-cell-sentinel']")
(def ^:private sel-safe-form-control "[data-pp='rf2-pp-safe-form-control-sentinel']")
(def ^:private sel-schema-described "[data-pp='rf2-pp-schema-described-sentinel']")
(def ^:private sel-inline-popover "[data-pp='rf2-pp-inline-popover-sentinel']")

;; ---------------------------------------------------------------------------
;; 1 — controlled-input: the reusable event-prefix control (P0-2 door)
;; ---------------------------------------------------------------------------

(defview ci-host []
  [lib/controlled-input {:on-change [::typed]}])

(deftest controlled-input-renders-and-round-trips-the-event-prefix
  (if-not (browser?)
    (is true ":node — the browser suite renders proof-pack controlled-input")
    (let [f (make-frame ::ci {:text "seed"})]
      (register!)
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [ci-host]]]
              (let [el (.querySelector root sel-controlled-input)]
                (is (some? el)
                    "controlled-input renders a node carrying its own sentinel")
                (is (= "INPUT" (.-tagName el)) "and it is the <input> it declares")
                (is (= "seed" (.-value el))
                    "ui/sub [:rf.pp/text] drives the controlled value from app-db")
                (-> (uit/flush! #(do (input! el "typed & <kept>") (host-turn!)))
                    (.then
                     (fn []
                       (is (= "typed & <kept>" (:text (app-db ::ci)))
                           "the :on-change prefix is conj'd with the live native value and dispatched")
                       (is (= "typed & <kept>" (.-value el))
                           "and the committed app-db value flows back through the sub"))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (is false (str "controlled-input: " e)) (done))))))))

;; ---------------------------------------------------------------------------
;; 2 — selection-controller: multi-writer atomic transitions (update!)
;; ---------------------------------------------------------------------------

;; Captured OUTSIDE the frame: `on-select` is an opaque fn prop, and this is
;; the only way to observe the value the view read at click time.
(defonce ^:private selected (atom ::unset))

(defview sc-host []
  [lib/selection-controller {:on-select (fn [sel] (reset! selected sel))}])

(deftest selection-controller-renders-and-composes-two-same-turn-writers
  (if-not (browser?)
    (is true ":node — the browser suite renders proof-pack selection-controller")
    (let [f (make-frame ::sc {})]
      (register!)
      (reset! selected ::unset)
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [sc-host]]]
              (let [host  (.querySelector root sel-selection-controller)
                    count-el (.querySelector root (str sel-selection-controller " [data-role='count']"))
                    btn   (.querySelector root (str sel-selection-controller " button"))]
                (is (some? host)
                    "selection-controller renders a node carrying its own sentinel")
                (is (= "0" (.-textContent count-el))
                    "ui/local seeds the selection empty")
                (-> (uit/flush! #(do (click! btn) (host-turn!)))
                    (.then
                     (fn []
                       (is (= "2" (.-textContent count-el))
                           "TWO same-turn update! writers compose over the LATEST host state (#{} -> #{:a :b})")
                       (is (= #{} @selected)
                           "on-select receives the COMMITTED selection the render read, not the in-flight one"))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (is false (str "selection-controller: " e)) (done))))))))

;; ---------------------------------------------------------------------------
;; 3 — list-cell: parameterized markup through ui/slot + render-fn
;; ---------------------------------------------------------------------------

(defview lc-host [{:keys [rows]}]
  [lib/list-cell {:rows rows
                  :render (ui/render-fn [idx row]
                            [:span {:data-role "cell"} (str idx "=" (:name row))])}])

(deftest list-cell-renders-each-row-through-the-callers-slot
  (if-not (browser?)
    (is true ":node — the browser suite renders proof-pack list-cell")
    (let [f    (make-frame ::lc {})
          rows [{:id 1 :name "a & <b>"} {:id 2 :name "second"}]]
      (register!)
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [lc-host {:rows rows}]]]
              (let [ul    (.querySelector root sel-list-cell)
                    items (when ul (.querySelectorAll ul "li"))
                    cells (when ul (.querySelectorAll ul "[data-role='cell']"))]
                (is (some? ul) "list-cell renders a node carrying its own sentinel")
                (is (= "UL" (.-tagName ul)) "and it is the <ul> it declares")
                (is (= 2 (.-length items)) "one keyed <li> per row, no more")
                (is (= 2 (.-length cells))
                    "ui/slot invokes the caller's render-fn exactly once per row")
                (is (= ["0=a & <b>" "1=second"]
                       (mapv #(.-textContent %) (array-seq cells)))
                    "the slot receives (index row) in order and its output is escaped text")))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (is false (str "list-cell: " e)) (done))))))))

;; ---------------------------------------------------------------------------
;; 4 — safe-form-control: caller attrs through the spread-safe policy
;; ---------------------------------------------------------------------------

(defview sfc-host []
  [lib/safe-form-control
   {:on-change [::typed]
    ;; `:data-pp` deliberately COLLIDES with the view's owned sentinel prop:
    ;; the safe-spread policy says owned props win, so the sentinel must
    ;; survive. If the caller could clobber it, this view's own scope selector
    ;; would stop matching and every assertion below would red.
    :attrs {:aria-label "pp field"
            :data-testid "pp-sfc"
            :data-pp "caller-override-attempt"
            :class "caller-extra"}}])

(deftest safe-form-control-renders-with-owned-props-winning-the-collision
  (if-not (browser?)
    (is true ":node — the browser suite renders proof-pack safe-form-control")
    (let [f (make-frame ::sfc {:text "owned & <kept>"})]
      (register!)
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [sfc-host]]]
              (let [el (.querySelector root sel-safe-form-control)]
                (is (some? el)
                    "safe-form-control renders a node still carrying its OWNED sentinel — the colliding caller :data-pp lost")
                (is (= "INPUT" (.-tagName el)) "and it is the <input> it declares")
                (is (= "owned & <kept>" (.-value el))
                    "the owned controlled :value survives the spread — the sync door is retained")
                (testing "non-colliding caller attrs pass through"
                  (is (= "pp field" (.getAttribute el "aria-label")))
                  (is (= "pp-sfc" (.getAttribute el "data-testid")))
                  (is (.contains (.-classList el) "caller-extra")))
                (-> (uit/flush! #(do (input! el "typed via spread") (host-turn!)))
                    (.then
                     (fn []
                       (is (= "typed via spread" (:text (app-db ::sfc)))
                           "the OWNED :on-input handler survives the spread and dispatches"))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (is false (str "safe-form-control: " e)) (done))))))))

;; ---------------------------------------------------------------------------
;; 5 — schema-described: a literal props schema over ordinary markup
;; ---------------------------------------------------------------------------

(defview sd-host []
  [lib/schema-described {:label "Name & <label>"}])

(deftest schema-described-renders-its-label
  (if-not (browser?)
    (is true ":node — the browser suite renders proof-pack schema-described")
    (let [f (make-frame ::sd {})]
      (register!)
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [sd-host]]]
              (let [el (.querySelector root sel-schema-described)]
                (is (some? el)
                    "schema-described renders a node carrying its own sentinel")
                (is (= "LABEL" (.-tagName el)) "and it is the <label> it declares")
                (is (= "Name & <label>" (.-textContent el))
                    "the declared :label prop reaches the markup as escaped text")))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (is false (str "schema-described: " e)) (done))))))))

;; ---------------------------------------------------------------------------
;; 6 — inline-popover: the measure-before-paint geometry shell (interop tier)
;; ---------------------------------------------------------------------------

(defview ip-host []
  [lib/inline-popover {:content "popover body & <content>"}])

(deftest inline-popover-mounts-its-connect-effect-and-renders-its-shell
  (if-not (browser?)
    (is true ":node — the browser suite renders proof-pack inline-popover")
    (let [f (make-frame ::ip {})]
      (register!)
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [ip-host]]]
              (let [el (.querySelector root sel-inline-popover)]
                ;; `with-root` awaits the initial COMMIT, so reaching this point
                ;; means the view's `ui/effect :connect` ran without throwing.
                ;; The library's connect body is `(fn [] nil)` by design — a
                ;; pure structural shell (its comment says the C-6
                ;; measure-before-paint work lives there), so there is no
                ;; observable side effect to assert and none is claimed.
                (is (some? el)
                    "inline-popover renders a node carrying its own sentinel after a committed mount")
                (is (= "DIV" (.-tagName el)) "and it is the <div> it declares")
                (is (= "dialog" (.getAttribute el "role"))
                    "the geometry shell keeps its dialog role")
                (is (= "popover body & <content>" (.-textContent el))
                    "the :content child renders inside the shell")))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f)
                     (is false (str "inline-popover: " e)) (done))))))))

;; ---------------------------------------------------------------------------
;; Roster completeness is owned by the G-18 checker (rf2-syell)
;; ---------------------------------------------------------------------------
;;
;; "Every library view is exercised here" used to be a deftest in this file, but
;; its only roster assertion was a hand-maintained `(= 6 (count [lib/... ]))`
;; over a spelled-out var list: a seventh `defview` in library.cljs armed
;; NEITHER side, so a shipped view rendered nowhere stayed green — the exact
;; regression #6459 exists to prevent (rf2-kxork probe: a real seventh view left
;; 495 tests / 2765 assertions green). A .cljs test cannot read library.cljs at
;; runtime to derive that roster without a macro or generated source, so the
;; completeness claim lives in the one seam that already derives the library
;; roster from source: scripts/check-ui-facade-isolation.cjs (the required
;; cljs-ui-facade-isolation gate). It compares the derived `defview` set against
;; the sentinel selectors THIS suite references — the sel-* constants above are
;; that suite's half of the comparison. So a seventh view REDs the gate until it
;; is mounted and scoped here, and a removed/renamed view REDs on its stale
;; selector, neither able to drift on a hard-coded count. Sentinel distinctness
;; (no test satisfied by a sibling) is likewise enforced there, per view.
