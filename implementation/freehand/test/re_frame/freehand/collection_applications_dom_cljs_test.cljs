(ns re-frame.freehand.collection-applications-dom-cljs-test
  "THE EDITING GRID, MOUNTED — the pilot's hundred-cell workload on a real
  page, now over `re-frame.freehand.collection`'s engine (rf2-pa57v).

  [[re-frame.freehand.collection-applications-cljs-test]] settles what the
  applications DECIDE: how many rows the window holds, which records they
  read, what each row states about its absolute place, and that a hundred
  distinct edit intents exist. One thing it cannot settle is what a browser
  does with a virtualized window of CONTROLLED inputs — an `input` whose
  value is a subscription and whose every keystroke is an event is the one
  element where the substrate owns a synchronous door, and a windowed
  hundred of them is where that meets continuous mounting and unmounting.

  So exactly one claim is here, and it is the one the structural tree
  cannot make: five hundred rows in the sheet, a hundred real `input`
  elements in the document, each addressing itself, and nothing left behind
  when the root goes.

  The keyed-reconciliation laws the pilot also carried — a surviving row
  being the same NODE across a real scroll, and a reorder carrying a row's
  ELEMENT with it — are laws about the CONTROL rather than about an
  application, so they migrated onto FH-CTRL-021's own mounted suite
  ([[re-frame.freehand.virtual-collection-dom-cljs-test]]) rather than
  here.

  This file rides the browser lane through its `-dom-cljs-test` namespace
  suffix, and it also matches the node suites' broader regex, where it has
  no DOM to mount and says so rather than passing quietly."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.frame :as frame]
            [re-frame.freehand.collection-applications-cljs-test :as app]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.shell :as shell]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (ms/reset-ledger!))}))

(def ^:private fid :dom/collection-applications)
(def ^:private grid-id "acme-editing-grid")

(defn- seed! []
  (live-frame/make-frame {:id fid})
  (app/register!)
  (frame/replace-app-db! fid (app/seed-db {:grid-n app/grid-total}))
  fid)

(defn- render! [root]
  (ms/act #(.render root
                    (shell/provide-frame
                      fid (fr/element [app/editing-grid {:list-id grid-id}])))))

(defn- inputs-in [container]
  (vec (array-seq (.querySelectorAll container "input"))))

(deftest the-editing-grid-mounts-exactly-one-hundred-controlled-cells
  (testing "The hundred-cell workload on a real page: five hundred rows in
            the sheet, ten windowed rows of ten `input` elements in the
            document, and each one addressing itself. The cell count is the
            VIEWPORT's — a sheet five hundred rows tall costs a hundred
            inputs, and a sheet a hundred thousand rows tall costs the
            same hundred.

            Then the part that only a teardown can say: a window of
            controlled inputs mounts and unmounts continuously, so a
            boundary that failed to release would ACCUMULATE. The books are
            read after the root is destroyed, and both are exact zeroes."
    (if-not (ms/browser?)
      (ms/skip! "the browser job runs the mount assertions")
      (async done
        (seed!)
        (let [[container root] (ms/create-root! {:label "editing-grid"})]
          (-> (render! root)
              (.then
                (fn [_]
                  (let [inputs (inputs-in container)
                        rows   (array-seq (.querySelectorAll container
                                                             "[data-part='row']"))]
                    (is (= app/grid-total
                           (count (get-in (frame/frame-app-db-value fid)
                                          [:acme.app/grid :ids])))
                        "non-vacuous: five hundred rows are in app-db")
                    (is (= app/grid-window-rows (count rows))
                        "exactly ten windowed rows reached the document")
                    (is (= app/grid-window-cells (count inputs))
                        "carrying exactly one hundred controlled cells")
                    (is (< app/grid-window-cells
                           (* app/grid-total (count app/grid-columns)))
                        "non-vacuous: the sheet is five thousand cells")
                    (is (= "g7/c3" (.getAttribute (nth inputs 73) "data-cell"))
                        "and each one addresses itself")
                    (is (= "grid" (.getAttribute (.getElementById js/document grid-id)
                                                 "role"))
                        "on a viewport wearing the role the APPLICATION chose"))
                  (ms/destroy-root! container root)
                  (ms/residue-clean!
                    "the editing grid — after a windowed hundred-cell mount"
                    [["every row node in the document"
                      #(.-length (.querySelectorAll js/document "[data-part='row']"))]
                     ["every controlled cell in the document"
                      #(.-length (.querySelectorAll js/document "input.acme-grid-cell"))]])
                  (done)))
              (.catch (fn [e]
                        (is false (str "the editing-grid mount rejected: " e))
                        (ms/destroy-root! container root)
                        (done)))))))))
