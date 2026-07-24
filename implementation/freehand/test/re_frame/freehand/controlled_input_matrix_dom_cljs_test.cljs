(ns re-frame.freehand.controlled-input-matrix-dom-cljs-test
  "F6b matrix 1/8 — CONTROLLED INPUT, in a real browser, across BOTH
  execution modes (EP-0036 §6, gate row \"browser correctness\").

  The pilot proved CASE A once. This matrix proves it as a MATRIX: the
  same controlled-field claims — a same-tick keystroke echo, a mid-string
  caret, a control that disables while its own write is in flight — hold
  in the INTERPRETED and the COMPILED lowering alike, and the two build
  the SAME real DOM. Promotion is a one-line marker; a controlled input
  is the case where a correction has to reach the host inside the event,
  so it is the sharpest place to prove the marker changes nothing.

  It also carries the three ATTRIBUTE-lowering parity cells the F6b gate
  coordinates with the emitter-parity work — a runtime `<select multiple>`
  empty value, a `v/spread-safe` guarded caller, and a routed `:class`
  one-map collision. Each is asserted as cross-mode agreement in a real
  DOM: whatever the two emitters do, they must do the same thing, which is
  the whole substance of a two-mode contract.

  The pilot's own rows live in `pilot-field-dom-cljs-test`; this file is
  the cross-mode matrix over the same declarations, plus the attribute
  cells the pilot never reached.

  Rides the browser lane through its `-dom-cljs-test` suffix; under node
  it has no DOM and says so."
  (:require ["react" :as react]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.matrix-support :as ms]
            [re-frame.freehand.pilot-field :as pilot]
            [re-frame.freehand.pilot-field-compiled :as promoted]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(def ^:private fid :matrix.controlled/frame)
(def ^:private line-id 1)

(def ^:private seed-line
  {:description "Consulting"
   :quantity    "2"
   :unit-price  "150"
   :account     "4000"
   :reference   "REF-1"
   :reference-revision 0})

;; The pilot line, in each mode. One roster drives every row below, so the
;; matrix dimension "view shape × mode" is a literal table.
(def ^:private line-modes
  [["interpreted" pilot/invoice-line]
   ["compiled"    promoted/invoice-line]])

;; ---------------------------------------------------------------------------
;; Attribute-lowering cells — inline twins, marker the only difference
;; ---------------------------------------------------------------------------

(v/defview select-direct-interpreted
  [{:keys [flag]}]
  [:select {:multiple flag :value nil :on-change [:matrix/select-changed ::v/value]}
   [:option {:value "x"} "X"]
   [:option {:value "y"} "Y"]])

(v/defview select-direct-compiled
  {:compiled true}
  [{:keys [flag]}]
  [:select {:multiple flag :value nil :on-change [:matrix/select-changed ::v/value]}
   [:option {:value "x"} "X"]
   [:option {:value "y"} "Y"]])

(v/defview select-safe-interpreted
  [{:keys [flag]}]
  [:select (v/spread-safe {:value nil :on-change [:matrix/select-changed ::v/value]}
                          {:multiple flag})
   [:option {:value "x"} "X"]
   [:option {:value "y"} "Y"]])

(v/defview select-safe-compiled
  {:compiled true}
  [{:keys [flag]}]
  [:select (v/spread-safe {:value nil :on-change [:matrix/select-changed ::v/value]}
                          {:multiple flag})
   [:option {:value "x"} "X"]
   [:option {:value "y"} "Y"]])

(v/defview safe-caller-interpreted
  [{:keys [caller]}]
  [:input.field (v/spread-safe {:value    "owned"
                                :class    "field"
                                :on-input [:matrix/typed ::v/value]}
                               caller)])

(v/defview safe-caller-compiled
  {:compiled true}
  [{:keys [caller]}]
  [:input.field (v/spread-safe {:value    "owned"
                                :class    "field"
                                :on-input [:matrix/typed ::v/value]}
                               caller)])

;; The routed :class one-map collision (rf2-c9kus). The one-map rule is
;; OWNED by that bead and Spec 004B's alias row; F6b asserts only that the
;; two lowerings AGREE on the rendered class, never a particular composed
;; value — the two-mode contract is that promotion changes nothing, and a
;; specific value would pin a rule this matrix does not own.
(v/defview class-one-map-interpreted
  [_]
  [:div {:class "a" :x/class "b"} "x"])

(v/defview class-one-map-compiled
  {:compiled true}
  [_]
  [:div {:class "a" :x/class "b"} "x"])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- register! []
  (rf/reg-event :matrix/select-changed (fn [{:keys [db]} _] {:db db}))
  (rf/reg-event :matrix/typed (fn [{:keys [db]} _] {:db db})))

(defn- seed-line! []
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid {:invoice {line-id seed-line}})
  (pilot/register!)
  (pilot/register-app!)
  (register!)
  fid)

(defn- fresh-frame! []
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid {})
  (register!)
  fid)

(defn- render! [root form]
  (ms/act #(.render root (shell/provide-frame fid (fr/element form)))))

(defn- control [container nm]
  (.querySelector container (str "[data-field='" nm "'] [data-part='control']")))

(defn- app [] (frame/frame-app-db-value fid))
(defn- line [] (get-in (app) [:invoice line-id]))

;; ===========================================================================
;; Row 1 — the two lowerings build the SAME controlled line
;; ===========================================================================

(deftest controlled-matrix-both-modes-build-the-same-controlled-line
  (testing "The whole pilot line — four controlled fields and a buffered
            fifth — mounted in each mode against ONE seed, produces the
            SAME real DOM. Not a sampled attribute: the entire subtree's
            tag/attribute/text outline, so a divergence anywhere fails."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the parity assertion")
      (async done
        (seed-line!)
        (let [[ci ri] (ms/create-root!)
              [cc rc] (ms/create-root!)]
          (-> (render! ri [pilot/invoice-line {:id line-id}])
              (.then (fn [_] (render! rc [promoted/invoice-line {:id line-id}])))
              (.then (fn [_]
                       (let [shared (ms/outlines-agree?
                                      (.-firstElementChild ci)
                                      (.-firstElementChild cc)
                                      "controlled line")]
                         (is (re-find #"data-component=\"acme/invoice-line\"" shared)
                             "non-vacuous: the shared outline really is the invoice line")
                         (is (re-find #"REF-1" shared)
                             "and it carries the seeded buffered value"))
                       (ms/destroy-root! ci ri)
                       (ms/destroy-root! cc rc)
                       (done)))
              (.catch (fn [e]
                        (is false (str "a controlled mount rejected: " e))
                        (ms/destroy-root! ci ri)
                        (ms/destroy-root! cc rc)
                        (done)))))))))

;; ===========================================================================
;; Row 2 — a keystroke burst echoes character-for-character, both modes
;; ===========================================================================

(deftest controlled-matrix-a-keystroke-burst-echoes-in-both-modes
  (testing "A burst of keystrokes into the controlled `quantity` field
            yields a final value equal to the typed string character for
            character — in the DOM and in application state — in each
            mode. The extra library hop rides inside the synchronous drain
            the door opened; if promotion moved it outside, the compiled
            arm would lose characters here and the interpreted one would
            not."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the typing assertions")
      (async done
        (ms/each-mode
          line-modes
          (fn [[label view]]
            (seed-line!)
            (let [[container root] (ms/create-root!)]
              (-> (render! root [view {:id line-id}])
                  (.then (fn [_]
                           (ms/live!)
                           (let [node  (control container "quantity")
                                 typed "1234567890123456789"]
                             (is (= "2" (.-value node)) (str label ": starts on the seed"))
                             (ms/set-native-value! node "")
                             (ms/fire-input! node nil)
                             (ms/type-string! node typed)
                             (is (= typed (.-value node))
                                 (str label ": every keystroke survived in the DOM"))
                             (is (= typed (:quantity (line)))
                                 (str label ": and application state holds exactly what was typed")))
                           (ms/destroy-root! container root)
                           nil)))))
          done)))))

;; ===========================================================================
;; Row 3 — a mid-string insert keeps the caret, both modes
;; ===========================================================================

(deftest controlled-matrix-a-mid-string-insert-keeps-the-caret-in-both-modes
  (testing "Editing in the middle of an existing value: the character lands
            at the caret and the caret stays with it, in each mode. A round
            trip that completed late would put the whole value back with the
            cursor at the end — a divergence promotion must not introduce."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the caret assertions")
      (async done
        (ms/each-mode
          line-modes
          (fn [[label view]]
            (seed-line!)
            (let [[container root] (ms/create-root!)]
              (-> (render! root [view {:id line-id}])
                  (.then (fn [_]
                           (ms/live!)
                           (let [node (control container "description")]
                             (is (= "Consulting" (.-value node)) (str label ": seeded"))
                             (ms/insert-at! node 3 "X")
                             (is (= "ConXsulting" (.-value node))
                                 (str label ": the insert landed at the caret"))
                             (is (= [4 4] (ms/caret node))
                                 (str label ": and the caret did not jump to the end"))
                             (is (= "ConXsulting" (:description (line)))
                                 (str label ": application state matches character for character")))
                           (ms/destroy-root! container root)
                           nil)))))
          done)))))

;; ===========================================================================
;; Row 4 — the buffered control disables while its write is in flight
;; ===========================================================================

(deftest controlled-matrix-the-busy-control-disables-in-both-modes
  (testing "The buffered control's `disabled` follows its own in-flight
            normalisation — a real DOM property, not an attribute a tree
            carries — and does so identically in each mode."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the busy assertions")
      (async done
        (ms/each-mode
          line-modes
          (fn [[label view]]
            (seed-line!)
            (let [[container root] (ms/create-root!)]
              (-> (render! root [view {:id line-id}])
                  (.then (fn [_]
                           (let [node (control container "reference")]
                             (is (false? (.-disabled node)) (str label ": idle"))
                             (-> (ms/act #(rf/dispatch-sync
                                            [:acme.invoice/reference-submitted line-id "REF-2" :req-1]
                                            {:frame fid}))
                                 (.then (fn [_]
                                          (is (true? (.-disabled node))
                                              (str label ": in flight → disabled"))
                                          (ms/act #(rf/dispatch-sync
                                                     [:acme.invoice/reference-normalised line-id :req-1 "REF-2"]
                                                     {:frame fid}))))
                                 (.then (fn [_]
                                          (is (false? (.-disabled node))
                                              (str label ": settled → enabled again"))
                                          (is (= "REF-2" (.-value node))
                                              (str label ": and the normalised value shows"))
                                          (ms/destroy-root! container root)
                                          nil)))))))))
          done)))))

;; ===========================================================================
;; Cell A — a runtime <select multiple> empty value (rf2-sf9n5)
;; ===========================================================================

(defn- check-multiple-select [container label]
  (let [el (ms/q container "select")]
    (is (some? el) (str label ": a select mounted"))
    (when el
      (is (true? (.-multiple el)) (str label ": mounted as a multiple select"))
      (is (= 0 (.-length (.-selectedOptions el)))
          (str label ": nothing is selected under an empty controlled value")))
    el))

;; [label interpreted-view compiled-view] — the direct props form and the
;; v/spread-safe split form, each mounted in both modes and compared.
(def ^:private select-forms
  [["direct"        select-direct-interpreted select-direct-compiled]
   ["v/spread-safe" select-safe-interpreted   select-safe-compiled]])

(deftest controlled-matrix-a-runtime-multiple-select-agrees-across-modes
  (testing "A `<select multiple>` whose `multiple` is RUNTIME-valued and
            whose controlled value is nil mounts in each mode as a real
            multiple select with nothing selected, and the two modes build
            the same DOM. A native multiple select's empty value is the
            empty array, not the empty string; the compiled emitter used to
            settle that verdict only from a build-time literal, so a
            runtime `multiple` diverged from the interpreted walk. Both the
            DIRECT and the `v/spread-safe` split forms are exercised.

            The array-vs-string PROP shape is pinned at unit level in
            `compiled-select-multiple-cljs-test`; here the claim is the
            real-DOM one: the two modes agree, and each is a working
            multiple select."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the select assertions")
      (async done
        (fresh-frame!)
        (ms/each-mode
          select-forms
          (fn [[label iv cv]]
            (let [[ci ri] (ms/create-root!)
                  [cc rc] (ms/create-root!)]
              (-> (render! ri [iv {:flag true}])
                  (.then (fn [_] (render! rc [cv {:flag true}])))
                  (.then (fn [_]
                           (check-multiple-select ci (str label "/interpreted"))
                           (check-multiple-select cc (str label "/compiled"))
                           (ms/outlines-agree? (ms/q ci "select") (ms/q cc "select")
                                               (str "runtime multiple select (" label ")"))
                           (ms/destroy-root! ci ri)
                           (ms/destroy-root! cc rc)
                           nil)))))
          done)))))

;; ===========================================================================
;; Cell B — v/spread-safe folds a guarded caller, both modes (rf2-drpa3.132)
;; ===========================================================================

(deftest controlled-matrix-spread-safe-folds-the-caller-across-modes
  (testing "`(v/spread-safe owned caller)` on a real controlled input: the
            owned value wins, the owned `:class` composes owned-first with
            the caller's, and a benign caller attribute reaches the DOM —
            identically in each mode. The interpreted React lowering
            reached its guarded caller through `react/put-caller!`
            (rf2-drpa3.132); this proves that reach lands the same page the
            compiled fold does."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the spread-safe assertions")
      (async done
        (fresh-frame!)
        (let [caller {:aria-label "Email" :class "wide"}]
          (letfn [(check [container label]
                    (let [el (ms/q container "input")]
                      (is (= "owned" (.-value el)) (str label ": the owned value won"))
                      (is (= "Email" (.getAttribute el "aria-label"))
                          (str label ": the benign caller attribute reached the DOM"))
                      (let [classes (set (.split (.getAttribute el "class") #"\s+"))]
                        (is (contains? classes "field") (str label ": the owned class is present"))
                        (is (contains? classes "wide") (str label ": composed with the caller's")))
                      el))]
            (let [[ci ri] (ms/create-root!)
                  [cc rc] (ms/create-root!)]
              (-> (render! ri [safe-caller-interpreted {:caller caller}])
                  (.then (fn [_] (render! rc [safe-caller-compiled {:caller caller}])))
                  (.then (fn [_]
                           (check ci "interpreted")
                           (check cc "compiled")
                           (ms/outlines-agree? (ms/q ci "input") (ms/q cc "input")
                                               "v/spread-safe guarded caller")
                           (ms/destroy-root! ci ri)
                           (ms/destroy-root! cc rc)
                           (done)))
                  (.catch (fn [e]
                            (is false (str "a spread-safe mount rejected: " e))
                            (ms/destroy-root! ci ri)
                            (ms/destroy-root! cc rc)
                            (done)))))))))))

;; ===========================================================================
;; Cell C — a routed :class one-map collision AGREES across modes (rf2-c9kus)
;; ===========================================================================

(deftest controlled-matrix-routed-class-one-map-agrees-across-modes
  (testing "One attribute map carrying the exact `:class` and a routed
            `:x/class` alias projecting onto the same slot. Whatever the
            walk does with the collision — compose, refuse, or rank — the
            two-mode contract is that the interpreted and compiled
            lowerings do the SAME thing, read back as the rendered `class`
            off a real element. The specific one-map rule is owned by
            rf2-c9kus and Spec 004B; this cell pins only the agreement,
            which is exactly the promotion-changes-nothing law."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the class-routing assertion")
      (async done
        (fresh-frame!)
        (let [[ci ri] (ms/create-root!)
              [cc rc] (ms/create-root!)]
          (-> (render! ri [class-one-map-interpreted {}])
              (.then (fn [_] (render! rc [class-one-map-compiled {}])))
              (.then (fn [_]
                       (let [ic (.getAttribute (ms/q ci "div") "class")
                             cclass (.getAttribute (ms/q cc "div") "class")]
                         (is (= ic cclass)
                             (str "interpreted and compiled agree on the rendered class "
                                  "(interpreted=" (pr-str ic) " compiled=" (pr-str cclass) ")")))
                       (ms/destroy-root! ci ri)
                       (ms/destroy-root! cc rc)
                       (done)))
              (.catch (fn [e]
                        (is false (str "a class-routing mount rejected: " e))
                        (ms/destroy-root! ci ri)
                        (ms/destroy-root! cc rc)
                        (done)))))))))
