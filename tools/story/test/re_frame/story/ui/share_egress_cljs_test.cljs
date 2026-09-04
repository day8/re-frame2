(ns re-frame.story.ui.share-egress-cljs-test
  "CLJS tests for the human share / export / copy egress UI surface
  (rf2-ba86n.16). The PURE reproducibility classifier is covered JVM-side
  in `re-frame.story.egress-test`; this file pins the UI glue that the
  classifier feeds: the reproducibility badge hiccup, the report + EDN-
  snippet builders over shell state, and the dialog open/close + render.

  Runs on CLJS under shadow's `:node-test` target (ns suffix
  `-cljs-test`). `share.cljs` is CLJS-only (Reagent / DOM)."
  (:require [clojure.string :as str]
            [cljs.test :refer [deftest is testing use-fixtures async]]
            [goog.object :as gobj]
            [re-frame.story :as rf.story]
            [re-frame.story.share :as rf.story.share]
            [re-frame.story.ui.share :as rf.story.ui.share]
            [re-frame.story.ui.state :as rf.story.ui.state]))

(use-fixtures :each
  ;; Map form (`:before` / `:after`) — async tests in this ns (the rf2-ehc5bq
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

(deftest badge-nil-report-renders-nothing
  (testing "no report (no variant focused) → no badge"
    (is (nil? (rf.story.ui.share/reproducibility-badge nil)))))

(deftest badge-full-shows-label-no-reasons
  (testing "a fully-reproducible report renders the label and no reason list"
    (let [hiccup (rf.story.ui.share/reproducibility-badge
                   {:status :full :label "fully reproducible" :reasons []})
          flat   (str hiccup)]
      (is (str/includes? flat "story-egress-badge"))
      (is (str/includes? flat "fully reproducible"))
      (is (not (str/includes? flat "story-egress-reasons"))
          "no reason list when nothing downgraded"))))

(deftest badge-partial-lists-reasons-with-codes
  (testing "a downgraded report renders each reason + its machine code"
    (let [hiccup (rf.story.ui.share/reproducibility-badge
                   {:status  :partial
                    :label   "partially reproducible"
                    :reasons [{:status :partial :code :dropped-overrides
                               :detail "2 overrides no longer apply"}]})
          flat   (str hiccup)]
      (is (str/includes? flat "partially reproducible"))
      (is (str/includes? flat "story-egress-reasons"))
      (is (str/includes? flat "dropped-overrides"))
      (is (str/includes? flat "2 overrides no longer apply")))))

;; ---- current-share-report / egress-edn-snippet ---------------------------

(deftest report-nil-when-no-variant
  (testing "with no variant focused the classifier still reports full (no
            per-variant data to omit on the chrome/workspace URL)"
    (let [report (rf.story.ui.share/current-share-report (rf.story.ui.state/get-state))]
      (is (= :full (:status report))))))

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

(deftest edn-snippet-emits-reg-variant-form
  (testing "the copy-EDN snippet is a (reg-variant …) form pinning :extends
            + the effective args of the focused cell"
    (rf.story/reg-variant :story.egress/counter {:tags #{:dev} :setup [] :args {:n 1}})
    (rf.story.ui.state/swap-state!
      (fn [s] (-> s
                  (assoc :selected-variant :story.egress/counter)
                  (assoc-in [:cell-overrides :story.egress/counter] {:n 7}))))
    (let [snip (rf.story.ui.share/egress-edn-snippet (rf.story.ui.state/get-state))]
      ;; NOT `rf.story/` — this string pins EMITTED OUTPUT, not this file's
      ;; alias. The snippet is built by `rf.story.predicates/reg-variant-form`,
      ;; whose alias prefix is a plain `"story"` argument (share.cljs), so the
      ;; copyable form a user pastes still reads `(story/reg-variant …)`.
      ;; Renaming the require alias above does not move it.
      (is (str/starts-with? snip "(story/reg-variant "))
      (is (str/includes? snip ":story.egress/counter"))
      (is (str/includes? snip ":extends"))
      (is (str/includes? snip ":n 7") "the cell-override beats the variant default")
      (is (str/ends-with? snip "})")))))

(deftest edn-snippet-nil-without-variant
  (testing "no variant focused → no EDN snippet"
    (is (nil? (rf.story.ui.share/egress-edn-snippet (rf.story.ui.state/get-state))))))

;; ---- dialog open / close / render ----------------------------------------

(deftest dialog-closed-renders-nil
  (testing "the dialog renders nil while closed"
    (rf.story.ui.share/close-share-export-dialog!)
    (is (nil? (rf.story.ui.share/share-export-dialog)))))

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
      (is (str/includes? flat "view-only"))
      ;; NOT privacy-gated: no privacy-theatre friction on human egress
      (is (not (str/includes? (str/lower-case flat) "privacy-sensitive")))
      (is (not (str/includes? (str/lower-case flat) "blocked until"))))))

(deftest dialog-share-chip-opens-dialog
  (testing "the toolbar SHARE chip's on-click opens the dialog"
    (rf.story.ui.share/close-share-export-dialog!)
    (is (nil? (rf.story.ui.share/share-export-dialog)) "closed before the click")
    (let [chip     (rf.story.ui.share/share-chip)
          attrs    (second chip)
          on-click (:on-click attrs)]
      (is (= "story-toolbar-share" (:data-test attrs)))
      (is (fn? on-click))
      (on-click nil)
      (is (some? (rf.story.ui.share/share-export-dialog))
          "clicking the chip opens the dialog (it now renders a tree)"))))

;; ---- rf2-76l69l: declared-arg-keys is the stale-override contract --------

(deftest declared-arg-keys-unions-args-and-argtypes
  (testing "rf2-76l69l — declared-arg-keys is the variant's editable arg
            surface: resolved-args keys ∪ :argtypes keys"
    (rf.story/reg-variant :story.dak/v
      {:tags     #{:dev}
       :setup   []
       :args     {:label "Hi" :count 1}
       :argtypes {:flavour {:control :select}}})
    (let [ks (rf.story.ui.share/declared-arg-keys :story.dak/v)]
      (is (contains? ks :label) "an :args key is declared")
      (is (contains? ks :count) "an :args key is declared")
      (is (contains? ks :flavour) "an :argtypes-only key is declared")
      (is (not (contains? ks :removed)) "a never-declared key is NOT in the set"))))

(deftest declared-arg-keys-nil-for-unregistered
  (testing "rf2-76l69l — an unregistered variant has no known contract → nil
            (so drop-stale-overrides degrades to keep-all, not drop-all)"
    (is (nil? (rf.story.ui.share/declared-arg-keys :story.dak/never-registered)))))

(deftest drop-stale-overrides-against-live-contract
  (testing "rf2-76l69l — a stale override (an arg the variant removed) is
            dropped + reported against the live declared-key set, while a
            still-declared override survives — the end-to-end finding-2 fix"
    (rf.story/reg-variant :story.dak/contract
      {:tags   #{:dev}
       :setup []
       :args   {:label "Hi"}})
    (let [parsed {:overrides {:label "Renamed" :gone 9} :dropped []}
          out    (rf.story.share/drop-stale-overrides
                   parsed (rf.story.ui.share/declared-arg-keys :story.dak/contract))]
      (is (= {:label "Renamed"} (:overrides out))
          "the still-declared :label override survives")
      (is (= 1 (count (:dropped out))) "the removed :gone override is dropped")
      (is (re-find #":gone" (first (:dropped out)))
          "the dropped token names the stale key"))))

;; ===========================================================================
;; rf2-ehc5bq — screenshot egress: real capture-to-PNG + clipboard write, or
;; an HONEST unavailable/error state. The old body marked `:copied :screenshot`
;; BEFORE any capture/write and only optionally poked an absent host shim, so
;; a no-shim click was a silent no-op that still flashed copied. These tests
;; exercise the ACTION (not just the render) on both the success path and the
;; failure paths; the failure-path tests fail against that old false-success
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
  (testing "rf2-ehc5bq — with a canvas node + a capture seam + the async
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

(deftest screenshot-no-shim-reports-error-not-false-copied
  (testing "rf2-ehc5bq (adversarial) — clipboard image-write IS available but
            NO host capture seam is installed: copy-screenshot! must record an
            HONEST :error and must NOT flash :copied :screenshot. This FAILS
            against the old body that marked copied before any capture."
    (async done
      (let [prev (install-globals!
                   {"document"      (fake-document #js {:tag "canvas"})
                    ;; window WITHOUT rfStoryCaptureNode → no raster seam
                    "window"        #js {}
                    "navigator"     (fake-navigator-with-write (atom nil))
                    "ClipboardItem" (fn [parts] (this-as this this))})]
        (-> (rf.story.ui.share/copy-screenshot!)
            (.then
              (fn [ok?]
                (is (false? ok?) "no seam → failure outcome")
                (let [snap (rf.story.ui.share/dialog-state-snapshot)]
                  (is (not= :screenshot (:copied snap))
                      "MUST NOT report success on a no-op (the false-success bug)")
                  (is (= :screenshot (:cmd (:error snap)))
                      "records an honest screenshot error")
                  (is (string? (:reason (:error snap)))
                      "the error carries a human reason"))))
            (.finally
              (fn [] (restore! prev) (done))))))))

(deftest screenshot-no-clipboard-reports-error-not-false-copied
  (testing "rf2-ehc5bq (adversarial) — a host WITHOUT the async Clipboard
            image-write API (no navigator.clipboard.write / no ClipboardItem)
            yields an honest :error, never a false :copied. FAILS against the
            old false-success no-op."
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
  (testing "rf2-ehc5bq — no variant canvas node in the DOM → honest :error, no
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
  (testing "rf2-ehc5bq — after a failed screenshot egress the open dialog
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
                      ;; the screenshot command-block invocation props slice
                      shot (second (str/split flat #":test \"screenshot\""))]
                  (is (some? shot) "the screenshot row is present")
                  ;; the honest error reason is threaded into the row's :error
                  (is (str/includes? shot ":error \"screenshot capture seam not installed on this host\"")
                      "the screenshot row carries the honest error reason")
                  ;; …and the row is NOT marked copied
                  (is (str/includes? shot ":copied? false")
                      "the screenshot row is NOT a false copied success")
                  ;; sanity: a genuinely-copied row WOULD read :copied? true —
                  ;; here it does not, so no false success leaked through
                  (is (not (str/includes? shot ":copied? true"))
                      "no false copied ✓ for the failed screenshot egress"))))
            (.finally
              (fn [] (restore! prev) (done))))))))
