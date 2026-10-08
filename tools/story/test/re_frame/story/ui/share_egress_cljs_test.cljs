(ns re-frame.story.ui.share-egress-cljs-test
  "CLJS tests for the human share / export / copy egress UI surface.
  The PURE reproducibility classifier is covered JVM-side
  in `re-frame.story.egress-test`; this file pins the UI glue that the
  classifier feeds: the reproducibility badge hiccup, the report + EDN-
  snippet builders over shell state, and the dialog open/close + render.

  Runs on CLJS under shadow's `:node-test` target (ns suffix
  `-cljs-test`). `share.cljs` is CLJS-only (Reagent / DOM)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [cljs.reader :as reader]
            [cljs.test :refer [deftest is testing use-fixtures async]]
            [goog.object :as gobj]
            [re-frame.registrar :as rf.registrar]
            [re-frame.story :as rf.story]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.share :as rf.story.ui.share]
            [re-frame.story.ui.state :as rf.story.ui.state]))

(use-fixtures :each
  ;; Map form (`:before` / `:after`) — async tests in this ns (the
  ;; screenshot suite) require the map fixture shape; cljs.test aborts an
  ;; async test under a bare-fn fixture.
  {:before (fn []
             (rf.story/clear-all!)
             (rf.story.ui.state/reset-shell-state!)
             (rf.story/install-canonical-vocabulary!)
             (rf.story.ui.share/close-share-export-dialog!))
   :after  (fn []
             (rf.story/clear-all!)
             (rf.story.ui.state/reset-shell-state!)
             (rf.story.ui.share/close-share-export-dialog!))})

;; ---- reproducibility-badge -----------------------------------------------

(deftest badge-lists-reasons-only-when-downgraded
  (testing "a fully-reproducible report renders the label and no reason list"
    (let [flat (str (rf.story.ui.share/reproducibility-badge
                      {:status :full :label "fully reproducible" :reasons []}))]
      (is (str/includes? flat "story-egress-badge"))
      (is (str/includes? flat "fully reproducible"))
      (is (not (str/includes? flat "story-egress-reasons")))))
  (testing "a downgraded report renders each reason + its machine code"
    (let [flat (str (rf.story.ui.share/reproducibility-badge
                      {:status  :partial
                       :label   "partially reproducible"
                       :reasons [{:status :partial :code :dropped-overrides
                                  :detail "2 overrides no longer apply"}]}))]
      (is (str/includes? flat "partially reproducible"))
      (is (str/includes? flat "story-egress-reasons"))
      (is (str/includes? flat "dropped-overrides"))
      (is (str/includes? flat "2 overrides no longer apply")))))

;; ---- current-share-report / egress-edn-snippet ---------------------------

(deftest report-flags-fn-override-as-view-only
  (testing "a fn-valued cell-override on the focused variant → view-only"
    (rf.story/reg-variant :story.egress/btn {:tags #{:dev} :setup []})
    (rf.story.ui.state/swap-state!
      (fn [s] (-> s
                  (assoc :selected-variant :story.egress/btn)
                  (assoc-in [:cell-overrides :story.egress/btn]
                            {:on-click (fn [_] nil) :label "ok"}))))
    (let [report (rf.story.ui.share/current-share-report (rf.story.ui.state/get-state))]
      (is (= :view-only (:status report)))
      (is (some #(= :override-fn (:code %)) (:reasons report))))))

;; ---- the report compiles the shared cell, not the bare body --------------

(def ^:private labelled-view
  "A registered view requiring `:label` — the prop the story supplies."
  :views.nlvgc/labelled)

(deftest report-keeps-the-plan-of-a-variant-that-leaves-a-prop-to-its-story
  (testing "a variant whose required prop comes from its story's
            :args still carries its plan-derived downgrade reason. The report
            compiles the shared cell with the ambient arg layers, as a run
            does; a bare compile would fail view-args validation, drop the
            plan, and read a function-valued network stub as :full"
    (rf.registrar/register! :view labelled-view
                            {:rf/props   [:map [:label :string]]
                             :handler-fn (fn [_] nil)})
    (try
      (rf.story/reg-story :story.nlvgc {:component labelled-view :args {:label "Go"}})
      (rf.story/reg-variant :story.nlvgc/stubbed
                            {:tags    #{:dev}
                             :network {[:get "/api/x"] {:reply {:ok (fn [_] {})}}}})
      (rf.story/reg-variant :story.nlvgc/plain {:tags #{:dev} :setup []})
      (rf.story.ui.state/swap-state!
        (fn [s] (assoc s :selected-variant :story.nlvgc/stubbed)))
      (let [report (rf.story.ui.share/current-share-report (rf.story.ui.state/get-state))]
        (is (= :partial (:status report)))
        (is (= [:network-reply-fn] (mapv :code (:reasons report)))
            "the stub's reason comes off the compiled plan"))
      (testing "control: the same story's variant with no plan-derived reason
                is fully reproducible"
        (rf.story.ui.state/swap-state!
          (fn [s] (assoc s :selected-variant :story.nlvgc/plain)))
        (let [report (rf.story.ui.share/current-share-report (rf.story.ui.state/get-state))]
          (is (= :full (:status report)))
          (is (empty? (:reasons report)))))
      (finally
        (rf.registrar/unregister! :view labelled-view)))))

;; ---- the copied form must not extend itself ------------------------------
;;
;; A form registering at the focused variant's OWN id while naming it as its
;; `:extends` parent would replace the source and fail with
;; `:rf.error/story-extends-cycle`, so the snippet's id is derived.

(deftest edn-snippet-round-trips-through-registration
  (testing "ACCEPTANCE: read the advertised form, register it through the
            normal API, and compile it — it renders the original source
            with the edited args, without a cycle, and the ORIGINAL
            registration stays usable"
    (rf.story/reg-variant :story.egress/counter
                          {:tags #{:dev} :setup [] :args {:n 1 :label "base"}})
    (rf.story.ui.state/swap-state!
      (fn [s] (-> s
                  (assoc :selected-variant :story.egress/counter)
                  (assoc-in [:cell-overrides :story.egress/counter] {:n 7}))))
    (let [snip           (rf.story.ui.share/egress-edn-snippet
                           (rf.story.ui.state/get-state) 1700000000000)
          [_ new-id body] (reader/read-string snip)]
      ;; The paste, performed for real. `reg-variant` is a MACRO whose
      ;; expansion is exactly this call, so registering the READ id + body
      ;; through `reg-variant*` is what compiling the pasted source does —
      ;; the only form of the paste reachable from a test, since the
      ;; snippet's id is derived at runtime.
      (rf.story.registrar/reg-variant* new-id body)

      ;; 1 · the copied variant compiles — no :rf.error/story-extends-cycle.
      (let [plan (rf.story.plan/variant-plan new-id)]
        (is (= 7 (get-in plan [:world :effective-args :n]))
            "it lands on the edited args the cell was showing")
        (is (= "base" (get-in plan [:world :effective-args :label]))
            "and inherits the parent's un-overridden args through :extends"))

      ;; 2 · the SOURCE registration is untouched — pasting must not
      ;; REPLACE it with a self-cycling body.
      (is (= 1 (get-in (rf.story.plan/variant-plan :story.egress/counter)
                       [:world :effective-args :n]))
          "the source still carries its own args, unreplaced"))))

;; ---- the copied args are the RESOLVED scenario's -------------------------

(deftest edn-snippet-carries-inherited-and-composed-args
  (testing "Copy EDN pins the args the focused cell actually runs
            with. An :extends parent's (or a :compose fragment's) args beat
            the story default, exactly as the compiled plan resolves them.
            A snippet that read the variant's OWN :args only would let the
            story default replace the inherited value in the pasted form."
    (rf.story/reg-story :story.egress {:args {:n 0 :nested {:v 0}}})
    (rf.story/reg-variant :story.egress/parent
                          {:tags #{:dev} :setup [] :args {:n 42 :nested {:v 7}}})
    (rf.story/reg-variant :story.egress/child
                          {:tags #{:dev} :setup [] :extends :story.egress/parent})
    (rf.story/reg-fragment :fragment.egress/args {:args {:n 42 :nested {:v 7}}})
    (rf.story/reg-variant :story.egress/composed
                          {:tags #{:dev} :setup [] :compose [:fragment.egress/args]})
    (doseq [vid [:story.egress/child :story.egress/composed]]
      (rf.story.ui.state/swap-state! (fn [s] (assoc s :selected-variant vid)))
      (let [[_ _ body] (reader/read-string
                         (rf.story.ui.share/egress-edn-snippet
                           (rf.story.ui.state/get-state) 1700000000000))]
        (is (= {:n 42 :nested {:v 7}} (:args body))
            (str vid " — the copied :args are the resolved scenario's, not the story default"))))))

;; ---- dialog open / close / render ----------------------------------------

(deftest dialog-open-renders-every-egress-command
  (testing "the open dialog renders all four human-egress commands, each
            shipping (NOT disabled-pending-a-seam) + carrying a
            reproducibility label"
    (rf.story/reg-variant :story.egress/d {:tags #{:dev} :setup [] :args {:n 1}})
    (rf.story.ui.state/swap-state! #(assoc % :selected-variant :story.egress/d))
    (rf.story.ui.share/open-share-export-dialog!)
    ;; `command-block` is a child Reagent component — `str` over the dialog
    ;; hiccup shows each command's invocation PROPS (`:test "share-url"` …),
    ;; not the child's expanded `data-test`. Assert on the props (the
    ;; idiomatic shallow-hiccup check for child components).
    (let [flat (str (rf.story.ui.share/share-export-dialog))]
      (is (str/includes? flat "story-share-export-dialog"))
      ;; all four commands present and enabled (no disabled-pending-a-seam)
      (is (str/includes? flat ":test \"share-url\""))
      (is (str/includes? flat ":test \"copy-edn\""))
      (is (str/includes? flat ":test \"screenshot\""))
      (is (str/includes? flat ":test \"static-build\""))
      ;; reproducibility labelling present on the rows (badges expand eagerly)
      (is (str/includes? flat "story-egress-reproducibility"))
      (is (str/includes? flat "fully reproducible"))
      ;; the screenshot row honestly states view-only
      (is (str/includes? flat "view-only")))))

(deftest dialog-share-chip-opens-dialog
  (testing "the toolbar SHARE chip's on-click opens the dialog"
    (rf.story.ui.share/close-share-export-dialog!)
    (is (nil? (rf.story.ui.share/share-export-dialog)) "closed before the click")
    (let [attrs (second (rf.story.ui.share/share-chip))]
      (is (= "story-toolbar-share" (:data-test attrs)))
      ((:on-click attrs) nil)
      (is (some? (rf.story.ui.share/share-export-dialog))
          "clicking the chip opens the dialog (it now renders a tree)"))))

;; ---- declared-arg-keys is the stale-override contract --------------------

(deftest declared-arg-keys-unions-args-and-argtypes
  (testing "declared-arg-keys is the variant's editable arg
            surface: resolved-args keys ∪ :argtypes keys"
    (rf.story/reg-variant :story.dak/v
      {:tags     #{:dev}
       :setup   []
       :args     {:label "Hi" :count 1}
       :argtypes {:flavour {:control :select}}})
    (is (every? (rf.story.ui.share/declared-arg-keys :story.dak/v)
                [:label :count :flavour])
        "both :args keys and the :argtypes-only key are declared")))

(deftest declared-arg-keys-nil-for-unregistered
  (testing "an unregistered variant has no known contract → nil
            (so drop-stale-overrides degrades to keep-all, not drop-all)"
    (is (nil? (rf.story.ui.share/declared-arg-keys :story.dak/never-registered)))))

;; ===========================================================================
;; Screenshot egress: real capture-to-PNG + clipboard write, or an HONEST
;; unavailable/error state. A body that marked `:copied :screenshot` BEFORE
;; any capture/write, and only optionally poked an absent host shim, would
;; make a no-shim click a silent no-op that still flashed copied. These tests
;; exercise the ACTION (not just the render) on both the success path and the
;; failure paths; the failure-path tests fail against such a false-success
;; no-op.
;;
;; The `:node-test` runner has no DOM (`js/document` / `js/window` /
;; `js/navigator` / `js/ClipboardItem` are absent), so each test installs the
;; minimal fakes it needs on `goog/global` (the Node global object) and tears
;; them down after. `js/Blob` IS a Node global (Node 18+).
;; ===========================================================================

(def ^:private global goog/global)

;; Some globals (e.g. `navigator` on Node 21+) are getter-only but
;; configurable, so a plain assignment throws. Route every install through
;; `Object.defineProperty` with a writable+configurable data descriptor, and
;; restore the same way — this works for both the settable (`window`,
;; `ClipboardItem`) and the getter-only (`navigator`) globals uniformly.
(defn- define-global! [k v]
  (js/Object.defineProperty
    global (name k)
    #js {:value v :writable true :configurable true}))

(defn- install-globals!
  "Install `m` (a {js-name → value} map) on the Node global via
  `Object.defineProperty`. Returns the prior values so a `finally` can
  restore them. A nil/undefined value INSTALLS that (so a test can simulate
  an 'absent' capability by overwriting an existing key)."
  [m]
  (let [prev (into {} (map (fn [[k _]] [k (gobj/get global (name k))])) m)]
    (doseq [[k v] m] (define-global! k v))
    prev))

(defn- restore! [prev]
  (doseq [[k v] prev] (define-global! k v)))

(defn- fake-document
  "A `js/document` whose `querySelector` returns a single sentinel node for the
  canvas selectors `canvas-node` probes, nil otherwise."
  [node]
  #js {:querySelector
       (fn [sel]
         (if (or (str/includes? sel "Variant canvas")
                 (str/includes? sel "data-test-variant"))
           node
           nil))})

(defn- fake-window-with-capture
  "A `js/window` exposing an `rfStoryCaptureNode` shim that resolves a PNG
  Blob (the success raster seam)."
  []
  #js {:rfStoryCaptureNode
       (fn [_node]
         (js/Promise.resolve (js/Blob. #js ["PNGDATA"] #js {:type "image/png"})))})

(defn- fake-navigator-with-write
  "A `js/navigator.clipboard` whose `write` resolves and records the items it
  was handed in `recorder` (an atom)."
  [recorder]
  #js {:clipboard
       #js {:write (fn [items]
                     (reset! recorder items)
                     (js/Promise.resolve js/undefined))}})

(deftest screenshot-success-writes-png-and-marks-copied
  (testing "with a canvas node + a capture seam + the async
            Clipboard image-write API, copy-screenshot! rasters to a PNG,
            writes a ClipboardItem, and ONLY THEN flashes :copied :screenshot"
    (async done
      (let [written (atom nil)
            prev (install-globals!
                   {"document"      (fake-document #js {:tag "canvas"})
                    "window"        (fake-window-with-capture)
                    "navigator"     (fake-navigator-with-write written)
                    "ClipboardItem" (fn [parts] (this-as this
                                                   (set! (.-_parts this) parts)
                                                   this))})]
        (-> (rf.story.ui.share/copy-screenshot!)
            (.then
              (fn [ok?]
                (is (true? ok?) "the write resolved → success")
                (let [snap (rf.story.ui.share/dialog-state-snapshot)]
                  (is (= :screenshot (:copied snap))
                      "copied flashes only on a real, resolved clipboard write")
                  (is (nil? (:error snap)) "no error on the success path"))
                (is (some? @written) "a ClipboardItem was handed to clipboard.write")))
            (.finally
              (fn [] (restore! prev) (done))))))))

(deftest screenshot-no-clipboard-reports-error-not-false-copied
  (testing "(adversarial) a host WITHOUT the async Clipboard
            image-write API (no navigator.clipboard.write / no ClipboardItem)
            yields an honest :error, never a false :copied. FAILS against a
            false-success no-op."
    (async done
      (let [prev (install-globals!
                   {"document"      (fake-document #js {:tag "canvas"})
                    "window"        (fake-window-with-capture)
                    ;; navigator without a clipboard → image write unsupported
                    "navigator"     #js {}
                    "ClipboardItem" js/undefined})]
        (-> (rf.story.ui.share/copy-screenshot!)
            (.then
              (fn [ok?]
                (is (false? ok?) "no clipboard image write → failure outcome")
                (let [snap (rf.story.ui.share/dialog-state-snapshot)]
                  (is (not= :screenshot (:copied snap))
                      "MUST NOT report success when clipboard image write is absent")
                  (is (= :screenshot (:cmd (:error snap)))
                      "records an honest screenshot error")
                  (is (str/includes? (str/lower-case (:reason (:error snap)))
                                     "clipboard")
                      "the reason names the missing clipboard capability"))))
            (.finally
              (fn [] (restore! prev) (done))))))))

(deftest screenshot-no-canvas-reports-error
  (testing "no variant canvas node in the DOM → honest :error, no
            false :copied."
    (async done
      (let [prev (install-globals!
                   {"document"      (fake-document nil) ; querySelector → nil
                    "window"        (fake-window-with-capture)
                    "navigator"     (fake-navigator-with-write (atom nil))
                    "ClipboardItem" (fn [parts] (this-as this this))})]
        (-> (rf.story.ui.share/copy-screenshot!)
            (.then
              (fn [ok?]
                (is (false? ok?))
                (let [snap (rf.story.ui.share/dialog-state-snapshot)]
                  (is (not= :screenshot (:copied snap)))
                  (is (= :screenshot (:cmd (:error snap)))))))
            (.finally
              (fn [] (restore! prev) (done))))))))

(deftest screenshot-row-renders-error-not-copied-after-no-op
  (testing "after a failed screenshot egress the open dialog
            threads the honest error reason into the screenshot row (`:error`)
            and leaves it NOT copied (`:copied? false`). `command-block` is a
            child component, so `str` over the dialog shows its invocation
            PROPS — the idiomatic shallow-hiccup check used by the sibling
            render test."
    (async done
      (rf.story/reg-variant :story.egress/shot {:tags #{:dev} :setup []})
      (rf.story.ui.state/swap-state! #(assoc % :selected-variant :story.egress/shot))
      (rf.story.ui.share/open-share-export-dialog!)
      (let [prev (install-globals!
                   {"document"      (fake-document #js {:tag "canvas"})
                    "window"        #js {}                ; no capture seam
                    "navigator"     (fake-navigator-with-write (atom nil))
                    "ClipboardItem" (fn [parts] (this-as this this))})]
        (-> (rf.story.ui.share/copy-screenshot!)
            (.then
              (fn [_]
                (let [flat (str (rf.story.ui.share/share-export-dialog))
                      ;; the screenshot command-block invocation props slice,
                      ;; ending where the next row's props begin
                      shot (some-> (second (str/split flat #":test \"screenshot\""))
                                   (str/split #":test \"")
                                   first)]
                  ;; Asserted, not implied: a nil `shot` would throw inside
                  ;; this callback, where cljs.test never records it.
                  (is (some? shot) "the screenshot row is present")
                  (is (str/includes? shot ":error \"screenshot capture seam not installed on this host\"")
                      "the screenshot row carries the honest error reason")
                  (is (str/includes? shot ":copied? false")
                      "the screenshot row is NOT a false copied success"))))
            (.finally
              (fn [] (restore! prev) (done))))))))

;; ---- TEXT copy reports completion, never assumes it ----------------------
;;
;; `copy-text!` backs Copy URL / Copy EDN / Copy static-build command. Calling
;; the shared clipboard shim and `mark-copied!` on the very next line would
;; make an absent `navigator.clipboard` (an insecure dev host, JSDOM), a
;; denied permission, or a merely PENDING write all display "copied ✓". These
;; pin the same honesty the screenshot path above has.

(defn- fake-navigator-with-write-text
  "A `js/navigator.clipboard` whose `writeText` records the text and returns
  `outcome-fn` applied to it (a resolved or rejected Promise)."
  [recorder outcome-fn]
  #js {:clipboard
       #js {:writeText (fn [text]
                         (reset! recorder text)
                         (outcome-fn text))}})

(deftest copy-text-marks-copied-only-after-the-write-fulfils
  (testing "a fulfilled writeText flashes :copied and no error"
    (async done
      (let [written (atom nil)
            prev    (install-globals!
                      {"navigator" (fake-navigator-with-write-text
                                     written (fn [_] (js/Promise.resolve js/undefined)))})]
        (-> (rf.story.ui.share/copy-text! :share-url "https://example.test/?variant=x")
            (.then (fn [ok?]
                     (is (true? ok?) "the write resolved → success")
                     (is (= "https://example.test/?variant=x" @written)
                         "the text actually reached the clipboard API")
                     (let [snap (rf.story.ui.share/dialog-state-snapshot)]
                       (is (= :share-url (:copied snap)))
                       (is (nil? (:error snap)) "no error on the success path"))))
            (.finally (fn [] (restore! prev) (done))))))))

(deftest copy-text-rejected-write-reports-error-not-false-copied
  (testing "(adversarial) the ordinary PERMISSION DENIAL: the API is
            present but writeText REJECTS. A body that never observed the
            rejected Promise would show copied and leak an unhandled
            rejection."
    (async done
      (let [prev (install-globals!
                   {"navigator" (fake-navigator-with-write-text
                                  (atom nil)
                                  (fn [_] (js/Promise.reject (js/Error. "NotAllowedError"))))})]
        (-> (rf.story.ui.share/copy-text! :static-build "npm run story:build")
            (.then (fn [ok?]
                     (is (false? ok?) "a rejected write → failure outcome")
                     (let [snap (rf.story.ui.share/dialog-state-snapshot)]
                       (is (not= :static-build (:copied snap))
                           "MUST NOT report success when the write was refused")
                       (is (= :static-build (:cmd (:error snap)))))))
            (.finally (fn [] (restore! prev) (done))))))))

(deftest copy-text-does-not-mark-copied-while-the-write-is-pending
  (testing "a write that has NOT settled yet must leave the row
            un-copied; success appears only on fulfilment."
    (async done
      (let [resolve-fn (atom nil)
            prev       (install-globals!
                         {"navigator" (fake-navigator-with-write-text
                                        (atom nil)
                                        (fn [_] (js/Promise. (fn [res _] (reset! resolve-fn res)))))})
            p          (rf.story.ui.share/copy-text! :share-url "https://example.test/")]
        (is (not= :share-url (:copied (rf.story.ui.share/dialog-state-snapshot)))
            "no success flash while the clipboard write is still pending")
        (@resolve-fn js/undefined)
        (-> p
            (.then (fn [ok?]
                     (is (true? ok?))
                     (is (= :share-url (:copied (rf.story.ui.share/dialog-state-snapshot)))
                         "success appears once the write fulfils")))
            (.finally (fn [] (restore! prev) (done))))))))

;; ---- a failed TEXT copy shows on its ROW ---------------------------------
;;
;; The suite above pins only the state atom, so a row that never rendered the
;; error would pass it. `command-block` is
;; a child component: the dialog's hiccup holds its invocation props, so
;; `expand-command-rows` calls it the way Reagent would and these assertions
;; read the row's own `data-test` hooks.

(defn- expand-command-rows
  "`hiccup` with each `command-block` invocation (a component vector whose
  props carry `:action-label`) replaced by the row it renders."
  [hiccup]
  (walk/prewalk
    (fn [x]
      (if (and (vector? x) (fn? (first x))
               (map? (second x)) (contains? (second x) :action-label))
        ((first x) (second x))
        x))
    hiccup))

(defn- node-with-data-test
  "The first element in `hiccup` whose attrs carry `data-test`, or nil."
  [hiccup data-test]
  (some (fn [x]
          (when (and (vector? x) (map? (second x))
                     (= data-test (:data-test (second x))))
            x))
        (tree-seq coll? seq hiccup)))

(defn- failed-copy-dialog
  "Open the dialog on a focused variant, fail `cmd`'s text copy (no
  `navigator.clipboard`), and resolve the expanded dialog hiccup."
  [cmd]
  (rf.story.ui.share/open-share-export-dialog!)
  (-> (rf.story.ui.share/copy-text! cmd "text the clipboard never received")
      (.then (fn [_] (expand-command-rows (rf.story.ui.share/share-export-dialog))))))

(deftest text-copy-failure-renders-on-its-own-row
  (testing "Share URL, Copy EDN and Static build each render
            their own failed copy, as the Screenshot row does"
    (async done
      (rf.story/reg-variant :story.egress/copied {:tags #{:dev} :setup []})
      (rf.story.ui.state/swap-state! #(assoc % :selected-variant :story.egress/copied))
      (let [prev  (install-globals! {"navigator" #js {}})
            check (fn [cmd]
                    (-> (failed-copy-dialog cmd)
                        (.then (fn [dialog]
                                 (let [row (node-with-data-test
                                             dialog (str "story-egress-error-" (name cmd)))]
                                   (is (some? row) (str cmd " renders its error"))
                                   (is (str/includes? (str row) "copy it by hand")
                                       (str cmd " says why, and what to do"))
                                   (is (nil? (node-with-data-test
                                               dialog (str "story-egress-copied-" (name cmd))))
                                       (str cmd " shows no copied flash")))))))]
        (-> (check :share-url)
            (.then (fn [_] (check :copy-edn)))
            (.then (fn [_] (check :static-build)))
            (.finally (fn [] (restore! prev) (done))))))))

(deftest failed-copy-edn-shows-the-snippet-to-copy-by-hand
  (testing "Copy EDN has no on-screen body of its own, so a
            failed copy shows the snippet read-only: the manual fallback
            `copy-text!` promises. A dialog with no failure shows none."
    (async done
      (rf.story/reg-variant :story.egress/by-hand {:tags #{:dev} :setup []})
      (rf.story.ui.state/swap-state! #(assoc % :selected-variant :story.egress/by-hand))
      (rf.story.ui.share/open-share-export-dialog!)
      (is (nil? (node-with-data-test
                  (expand-command-rows (rf.story.ui.share/share-export-dialog))
                  "story-egress-copy-edn-fallback"))
          "control: no fallback field before any copy failed")
      (let [prev (install-globals! {"navigator" #js {}})]
        (-> (failed-copy-dialog :copy-edn)
            (.then (fn [dialog]
                     (let [attrs (second (node-with-data-test
                                           dialog "story-egress-copy-edn-fallback"))]
                       (is (true? (:read-only attrs)) "the fallback field is read-only")
                       (is (str/starts-with? (str (:value attrs)) "(rf.story/reg-variant ")
                           "it holds the snippet Copy EDN copies")
                       (is (str/includes? (str (:value attrs)) ":extends :story.egress/by-hand")
                           "for the focused variant"))))
            (.finally (fn [] (restore! prev) (done))))))))
