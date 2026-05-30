(ns re-frame.story.ui.share-egress-cljs-test
  "CLJS tests for the human share / export / copy egress UI surface
  (rf2-ba86n.16). The PURE reproducibility classifier is covered JVM-side
  in `re-frame.story.egress-test`; this file pins the UI glue that the
  classifier feeds: the reproducibility badge hiccup, the report + EDN-
  snippet builders over shell state, and the dialog open/close + render.

  Runs on CLJS under shadow's `:node-test` target (ns suffix
  `-cljs-test`). `share.cljs` is CLJS-only (Reagent / DOM)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.ui.share :as ui-share]
            [re-frame.story.ui.state :as state]))

(use-fixtures :each
  (fn [t]
    (story/clear-all!)
    (state/reset-shell-state!)
    (story/install-canonical-vocabulary!)
    (ui-share/close-share-export-dialog!)
    (t)
    (story/clear-all!)
    (state/reset-shell-state!)
    (ui-share/close-share-export-dialog!)))

;; ---- reproducibility-badge -----------------------------------------------

(deftest badge-nil-report-renders-nothing
  (testing "no report (no variant focused) → no badge"
    (is (nil? (ui-share/reproducibility-badge nil)))))

(deftest badge-full-shows-label-no-reasons
  (testing "a fully-reproducible report renders the label and no reason list"
    (let [hiccup (ui-share/reproducibility-badge
                   {:status :full :label "fully reproducible" :reasons []})
          flat   (str hiccup)]
      (is (str/includes? flat "story-egress-badge"))
      (is (str/includes? flat "fully reproducible"))
      (is (not (str/includes? flat "story-egress-reasons"))
          "no reason list when nothing downgraded"))))

(deftest badge-partial-lists-reasons-with-codes
  (testing "a downgraded report renders each reason + its machine code"
    (let [hiccup (ui-share/reproducibility-badge
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
    (let [report (ui-share/current-share-report (state/get-state))]
      (is (= :full (:status report))))))

(deftest report-flags-fn-override-as-view-only
  (testing "a fn-valued cell-override on the focused variant → view-only"
    (story/reg-variant :story.egress/btn {:tags #{:dev} :events []})
    (state/swap-state!
      (fn [s] (-> s
                  (assoc :selected-variant :story.egress/btn)
                  (assoc-in [:cell-overrides :story.egress/btn]
                            {:on-click (fn [_] nil) :label "ok"}))))
    (let [report (ui-share/current-share-report (state/get-state))]
      (is (= :view-only (:status report)))
      (is (some #(= :override-fn (:code %)) (:reasons report))))))

(deftest edn-snippet-emits-reg-variant-form
  (testing "the copy-EDN snippet is a (reg-variant …) form pinning :extends
            + the effective args of the focused cell"
    (story/reg-variant :story.egress/counter {:tags #{:dev} :events [] :args {:n 1}})
    (state/swap-state!
      (fn [s] (-> s
                  (assoc :selected-variant :story.egress/counter)
                  (assoc-in [:cell-overrides :story.egress/counter] {:n 7}))))
    (let [snip (ui-share/egress-edn-snippet (state/get-state))]
      (is (str/starts-with? snip "(story/reg-variant "))
      (is (str/includes? snip ":story.egress/counter"))
      (is (str/includes? snip ":extends"))
      (is (str/includes? snip ":n 7") "the cell-override beats the variant default")
      (is (str/ends-with? snip "})")))))

(deftest edn-snippet-nil-without-variant
  (testing "no variant focused → no EDN snippet"
    (is (nil? (ui-share/egress-edn-snippet (state/get-state))))))

;; ---- dialog open / close / render ----------------------------------------

(deftest dialog-closed-renders-nil
  (testing "the dialog renders nil while closed"
    (ui-share/close-share-export-dialog!)
    (is (nil? (ui-share/share-export-dialog)))))

(deftest dialog-open-renders-every-egress-command
  (testing "the open dialog renders all four human-egress commands, each
            shipping (NOT disabled-pending-a-seam) + carrying a
            reproducibility label"
    (story/reg-variant :story.egress/d {:tags #{:dev} :events [] :args {:n 1}})
    (state/swap-state! #(assoc % :selected-variant :story.egress/d))
    (ui-share/open-share-export-dialog!)
    ;; `command-block` is a child Reagent component — `str` over the dialog
    ;; hiccup shows each command's invocation PROPS (`:test "share-url"` …),
    ;; not the child's expanded `data-test`. Assert on the props (the
    ;; idiomatic shallow-hiccup check for child components).
    (let [flat (str (ui-share/share-export-dialog))]
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
    (ui-share/close-share-export-dialog!)
    (is (nil? (ui-share/share-export-dialog)) "closed before the click")
    (let [chip     (ui-share/share-chip)
          attrs    (second chip)
          on-click (:on-click attrs)]
      (is (= "story-toolbar-share" (:data-test attrs)))
      (is (fn? on-click))
      (on-click nil)
      (is (some? (ui-share/share-export-dialog))
          "clicking the chip opens the dialog (it now renders a tree)"))))
