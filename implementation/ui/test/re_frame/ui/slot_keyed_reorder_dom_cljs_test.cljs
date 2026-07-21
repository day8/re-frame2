(ns re-frame.ui.slot-keyed-reorder-dom-cljs-test
  "rf2-4unxm — the missing G-16 KEYED-REORDER-UNDER-SLOTS proof.

  G-16 (S3 conformance profile §6) claims four sub-proofs: render-slot parity
  across both emitters, KEYED REORDER UNDER SLOTS, purity diagnostics inside slot
  bodies, and manifest slot sites. The shipped `:slot-vtable` parity corpus case
  renders ONE fixed row order through SSR/JVM structural normalization — it never
  mounts, never rerenders A/B as B/A, and never observes identity/occurrence
  ownership across commits. This file wires the absent arm.

  The behaviour is a MOUNTED React reconciliation fact — a slot body executes
  deferred inside the reusable v-table seam while the enclosing keyed row still
  determines React identity. A one-state structural render cannot catch a reorder
  that remounts or cross-retargets a row's committed slot output, so the proof is
  live-DOM only (`with-root` + real `react-dom/client`). Under a non-browser host
  it degrades to a trivial pass, exactly like the sibling
  `callbacks_boundaries_dom_cljs_test`.

  Two arms share ONE harness:

    keyed-consumer   the v-table seam keyed by the STABLE datum id. A reorder
                     MOVES each row's fiber, so its `<li>` AND its slot-rendered
                     `<span>` keep their own DOM identity — the positive proof.
    index-consumer   the SAME seam keyed by the loop INDEX — the vacuity tooth.
                     React reuses each POSITION's node and mutates its datum, so a
                     row's node does NOT travel with its key; the node that showed
                     A now shows B. This is the focused key-propagation mutation
                     that makes the identity claim fail, proving it discriminates
                     correct reorder from broken reorder."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview sub]]
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
;; Harness (mirrors callbacks_boundaries_dom_cljs_test)
;; ---------------------------------------------------------------------------

(defn- host-turn! []
  (js/Promise. (fn [resolve _] (js/setTimeout #(resolve nil) 0))))

(defn- make-frame [id db] (rf/make-frame {:id id :initial-events [[:rf/set-db db]]}))

;; Dispatch to a specific frame from outside a view (the test thunk has no
;; ambient scope): capture the frame's committed dispatch bundle by id.
(defn- frame-dispatch! [frame-id event]
  ((:dispatch (rf/capture-frame frame-id)) event))

(defn- first-key [container attr]
  (some-> (.-firstElementChild container) (.getAttribute attr)))
(defn- last-key [container attr]
  (some-> (.-lastElementChild container) (.getAttribute attr)))

;; The per-test runtime reset wipes the registrar, so each test re-registers the
;; `:rows` sub + the reverse-reorder event after the fixture reset, before mount.
(defn- register! []
  (rf/reg-sub :rows (fn [db _] (:rows db)))
  (rf/reg-event :reorder/reverse
                (fn [{:keys [db]} _] {:db (update db :rows (comp vec reverse))})))

(def ^:private seed-rows [{:id 1 :name "Alpha"} {:id 2 :name "Beta"}])

;; ---------------------------------------------------------------------------
;; Fixtures — the v-table-shaped slot seam, keyed two ways
;; ---------------------------------------------------------------------------

(defview keyed-rows
  "The LIBRARY seam: owns the rows, iterates, and renders each row's body through
  `ui/slot` as a keyed child. Rows are keyed by their STABLE `:id`, so a reorder
  must MOVE each row's fiber — preserving identity — rather than rebuild it."
  [{:keys [rows render]}]
  [:ul.kslot
   (for [[i r] (map-indexed vector rows)]
     [:li.krow {:key (:id r) :data-key (str (:id r))}
      [:span.kbody (ui/slot render i r)]])])

(defview keyed-consumer
  "The CONSUMER: rows arrive from a sub, so dispatching `:reorder/reverse`
  rerenders them in reverse order. The row body is a caller-supplied compiled
  `ui/render-fn` whose committed output the slot renders — the v-table shape end
  to end, now driven across two commits."
  []
  (let [rows (sub [:rows])]
    [keyed-rows {:rows rows
                 :render (ui/render-fn [_idx row]
                           [:span.kcell {:data-name (:name row)} (:name row)])}]))

(defview index-rows
  "The MUTATION control (the vacuity tooth): the IDENTICAL seam keyed by the loop
  INDEX. Under a reorder React reuses each POSITION's fiber and mutates its datum,
  so a row's DOM node does NOT travel with its key — identity tracks position."
  [{:keys [rows render]}]
  [:ul.islot
   (for [[i r] (map-indexed vector rows)]
     [:li.irow {:key i :data-pos (str i)}
      [:span.ibody (ui/slot render i r)]])])

(defview index-consumer []
  (let [rows (sub [:rows])]
    [index-rows {:rows rows
                 :render (ui/render-fn [_idx row]
                           [:span.icell {:data-name (:name row)} (:name row)])}]))

;; ---------------------------------------------------------------------------
;; The proof — keyed reorder under slots preserves per-row identity + occurrence
;; ---------------------------------------------------------------------------

(deftest keyed-reorder-under-slots-preserves-row-and-slot-identity
  (if-not (browser?)
    (is true ":node — browser gate runs keyed-reorder-under-slots identity")
    (let [f (make-frame ::k {:rows seed-rows})]
      (register!)
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [keyed-consumer]]]
              (let [ul     (.querySelector root ".kslot")
                    row-a0 (.querySelector root ".krow[data-key='1']")
                    row-b0 (.querySelector root ".krow[data-key='2']")
                    cell-a0 (.querySelector root ".krow[data-key='1'] .kcell")
                    cell-b0 (.querySelector root ".krow[data-key='2'] .kcell")]
                ;; sanity — initial order A,B; each row's slot body shows its own datum
                (is (some? row-a0) "row A mounted through the slot seam")
                (is (some? row-b0) "row B mounted through the slot seam")
                (is (= "Alpha" (.-textContent cell-a0)) "row A's slot body renders A's datum")
                (is (= "Beta"  (.-textContent cell-b0)) "row B's slot body renders B's datum")
                (is (= "1" (first-key ul "data-key")) "initial DOM order: A is first")
                (is (= "2" (last-key ul "data-key"))  "initial DOM order: B is last")
                (-> (uit/flush! #(do (frame-dispatch! ::k [:reorder/reverse]) (host-turn!)))
                    (.then
                     (fn []
                       (let [ul1     (.querySelector root ".kslot")
                             row-a1  (.querySelector root ".krow[data-key='1']")
                             row-b1  (.querySelector root ".krow[data-key='2']")
                             cell-a1 (.querySelector root ".krow[data-key='1'] .kcell")
                             cell-b1 (.querySelector root ".krow[data-key='2'] .kcell")]
                         ;; the ORDER flipped to B,A
                         (is (= "2" (first-key ul1 "data-key")) "after reorder: B is first")
                         (is (= "1" (last-key ul1 "data-key"))  "after reorder: A is last")
                         ;; ENCLOSING-ROW IDENTITY: each key's <li> is the SAME DOM
                         ;; object, MOVED — not remounted, not swapped
                         (is (identical? row-a0 row-a1)
                             "row A kept its own DOM identity across the reorder (moved, not remounted)")
                         (is (identical? row-b0 row-b1)
                             "row B kept its own DOM identity across the reorder (moved, not remounted)")
                         ;; SLOT-OWNED OCCURRENCE: the slot-rendered <span> stays
                         ;; attached to its key — the occurrence did not cross-retarget
                         (is (identical? cell-a0 cell-a1)
                             "row A's slot body kept its identity (the slot occurrence stayed with key A)")
                         (is (identical? cell-b0 cell-b1)
                             "row B's slot body kept its identity (the slot occurrence stayed with key B)")
                         (is (= "Alpha" (.-textContent cell-a1))
                             "row A's slot body still renders A's datum after reorder (no content swap)")
                         (is (= "Beta" (.-textContent cell-b1))
                             "row B's slot body still renders B's datum after reorder (no content swap)")))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f) (is false (str "keyed reorder: " e)) (done))))))))

;; ---------------------------------------------------------------------------
;; The vacuity tooth — INDEX keying makes the identity claim fail
;; ---------------------------------------------------------------------------

(deftest index-keying-loses-identity-under-slots-the-vacuity-tooth
  ;; The counterexample that proves the positive `identical?` assertions above are
  ;; NOT vacuous: the ONLY change is the enclosing row's key (`:id` -> loop index).
  ;; Now React keeps each POSITION's node across the reorder and swaps its datum,
  ;; so the node that showed A's datum before now shows B's. Identity tracks
  ;; POSITION, not key — the failure mode the correct fixture rules out.
  (if-not (browser?)
    (is true ":node — browser gate runs the index-keying vacuity tooth")
    (let [f (make-frame ::i {:rows seed-rows})]
      (register!)
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [index-consumer]]]
              (let [ul      (.querySelector root ".islot")
                    pos0-0  (.-firstElementChild ul)]
                (is (= "Alpha" (.-textContent pos0-0)) "position 0 initially shows A's datum")
                (-> (uit/flush! #(do (frame-dispatch! ::i [:reorder/reverse]) (host-turn!)))
                    (.then
                     (fn []
                       (let [ul1    (.querySelector root ".islot")
                             pos0-1 (.-firstElementChild ul1)]
                         ;; SAME position-0 node object (React kept key 0) ...
                         (is (identical? pos0-0 pos0-1)
                             "index keying keeps the position-0 node object across the reorder")
                         ;; ... but its datum was SWAPPED A->B: identity did NOT
                         ;; travel with the key — the discriminating tooth
                         (is (= "Beta" (.-textContent pos0-1))
                             "the position-0 node's datum swapped A->B: index identity tracks POSITION, not key")))))))
            (.then (fn [] (rf/destroy-frame! f) (done))
                   (fn [e] (rf/destroy-frame! f) (is false (str "index tooth: " e)) (done))))))))
