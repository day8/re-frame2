(ns re-frame.freehand.a11y-diagnostics-cljs-test
  "The S4-C compile-tier a11y roster (rf2-74vlo; Spec 004 §Compile-tier
  warnings) — host-shared, pure-analyzer.

  Every check is tested in BOTH directions, and the SILENT direction is the
  load-bearing one. These diagnostics are governed by a high-confidence
  charter: a false positive is worse than a miss, so for each check this suite
  pins the exact shapes that must NOT produce a finding — a dynamic child, a
  foreign component, a props spread, an aria value computed at runtime — right
  next to the shape that must.

  Also pinned: the suppression round trip. `^{:rf.ui/suppress {<id> \"reason\"}}`
  silences the PRINTED warning while the finding remains a manifest
  `:diagnostics` fact carrying its reason, and a malformed suppression is the
  loud compile error `:rf.ui.compile/bad-suppress` rather than a silent no-op."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.freehand.compiler.a11y :as a11y]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.env :as env]))

;; ---------------------------------------------------------------------------
;; Injected environment (identical to the frozen-roster suite's)
;; ---------------------------------------------------------------------------

(defn- resolver [sym]
  (case sym
    sub         {:fqn 're-frame.freehand/sub :meta {}}
    spread      {:fqn 're-frame.freehand/spread :meta {}}
    spread-safe {:fqn 're-frame.freehand/spread-safe :meta {}}
    event       {:fqn 're-frame.freehand/event :meta {}}
    handler     {:fqn 're-frame.freehand/handler :meta {}}
    html        {:fqn 're-frame.freehand/html :meta {}}
    presence    {:fqn 're-frame.freehand/presence :meta {}}
    child-view  {:fqn 'app.views/child-view
                 :meta {:rf.ui/view true :rf.ui/children? true}}
    ForeignComp {:fqn 'app.interop/ForeignComp :meta {}}
    nil))

(defn- mk-env []
  (-> (env/make-env {:host :clj :ns-sym 'app.a11y
                     :self 'self-view :self-id :app.a11y/self-view
                     :resolver resolver})
      (assoc :self-children? false :self-closed-keys nil)))

(defn- run
  "Analyze `form`; -> {:warnings [id…] :diagnostics [site…]}."
  [form]
  (let [e (mk-env)]
    (ana/analyze e form)
    {:warnings    (mapv :id @(:warnings e))
     :msgs        (mapv :msg @(:warnings e))
     :diagnostics (:diagnostics @(:sites e))}))

(defn- ids [form] (:warnings (run form)))

(defn- fires!
  "`form` produces exactly the finding `id`, whose message names `escapes`."
  [id form & escapes]
  (testing (str id " <- " (pr-str form))
    (let [{:keys [warnings msgs]} (run form)]
      (is (= [id] warnings)
          (str (pr-str form) " must produce exactly [" id "]"))
      (doseq [esc escapes]
        (is (some #(str/includes? % esc) msgs)
            (str id " must name the rewrite " (pr-str esc)
                 " — got: " (pr-str msgs)))))))

(defn- silent!
  "`form` produces NO finding at all — the high-confidence bound."
  [why form]
  (testing (str "silent (" why ") <- " (pr-str form))
    (is (= [] (ids form))
        (str (pr-str form) " must stay silent: " why))))

(defn- reject
  [form]
  (try (ana/analyze (mk-env) form) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
         ex)))

;; ---------------------------------------------------------------------------
;; Check 1 — a11y-missing-accessible-name
;; ---------------------------------------------------------------------------

(def ^:private missing-name :rf.ui.compile/a11y-missing-accessible-name)

(deftest missing-accessible-name-fires
  (fires! missing-name '[:button {:class "icon"} [:svg {:class "i"}]]
          ":aria-label")
  (fires! missing-name '[:button] ":aria-label")
  (fires! missing-name '[:button ""] ":aria-label")
  (fires! missing-name '[:a {:href "/home"} [:i {:class "fa fa-home"}]]
          ":aria-label")
  (fires! missing-name '[:img {:src "/logo.png"}] ":alt \"\"")
  (testing "the finding names the tag it fired on"
    (is (= [:button] (mapv :tag (:diagnostics (run '[:button [:svg]])))))))

(deftest missing-accessible-name-is-silent-when-not-provable
  ;; --- the name IS there ---------------------------------------------------
  (silent! "literal text content names the button" '[:button "Save"])
  (silent! ":aria-label names an icon button"
           '[:button {:aria-label "Close dialog"} [:svg]])
  (silent! ":title names it" '[:button {:title "Close"} [:svg]])
  (silent! ":aria-labelledby names it"
           '[:button {:aria-labelledby "hdr"} [:svg]])
  (silent! "an inline <svg><title> names the graphic"
           '[:button [:svg [:title "Close"]]])
  (silent! "an inner <img alt> contributes text"
           '[:button [:img {:src "/i.png" :alt "Close"}]])
  (silent! "nested literal text is found through wrapper elements"
           '[:button [:span [:em "Save"]]])
  (silent! "the explicit empty alt is a DECLARED decorative image"
           '[:img {:src "/deco.png" :alt ""}])
  (silent! "aria-hidden markup needs no name"
           '[:button {:aria-hidden true} [:svg]])
  (silent! "role=presentation needs no name"
           '[:img {:src "/x.png" :role "presentation"}])

  ;; --- the name MIGHT be there: the analysis cannot see, so it says nothing -
  (silent! "a dynamic child may render the label"
           '[:button label])
  (silent! "a branch may render the label"
           '[:button (when expanded? "Collapse")])
  (silent! "a (sub …) read may render the label"
           '[:button (sub [:label])])
  (silent! "a foreign component may render the label"
           '[:button [ForeignComp {}]])
  (silent! "a child view may render the label"
           '[:button [child-view {}]])
  (silent! "trusted markup is opaque text"
           '[:button (html "<b>Save</b>")])
  (silent! "a dynamic :aria-label may name it"
           '[:button {:aria-label computed-label} [:svg]])
  (silent! "a dynamic :alt may name the image"
           '[:img {:src "/x.png" :alt computed-alt}])
  (silent! "a spread may carry :aria-label"
           '[:button (spread base) [:svg]])
  (silent! "an inner dynamic child may carry the text"
           '[:button [:span row-label]])

  ;; --- not a control at all ------------------------------------------------
  (silent! "an <a> without :href is not a link" '[:a [:i {:class "icon"}]])
  (silent! "a generic element needs no accessible name" '[:div [:svg]])
  (silent! "an <a href> with text is named" '[:a {:href "/x"} "Home"]))

;; ---------------------------------------------------------------------------
;; Check 2 — a11y-invalid-literal-aria
;; ---------------------------------------------------------------------------

(def ^:private bad-aria :rf.ui.compile/a11y-invalid-literal-aria)

(deftest invalid-literal-aria-fires
  (testing "a misspelled attribute name"
    (fires! bad-aria '[:div {:aria-labeledby "hdr"} "x"] ":aria-labelledby"))
  (testing "an attribute that simply does not exist"
    (fires! bad-aria '[:div {:aria-role "button"} "x"] "not a WAI-ARIA"))
  (testing "a literal value outside the attribute's token set"
    (fires! bad-aria '[:div {:aria-expanded "yes"} "x"] "\"false\"")
    (fires! bad-aria '[:div {:aria-live "loud"} "x"] "\"polite\"")
    (fires! bad-aria '[:div {:aria-current "yes"} "x"] "\"page\""))
  (testing "a non-numeric literal for a numeric attribute"
    (fires! bad-aria '[:div {:aria-level "high"} "x"] "integer")
    (fires! bad-aria '[:div {:aria-valuenow "lots"} "x"] "number")))

(deftest invalid-literal-aria-is-silent-when-not-provable
  (silent! "a correctly spelled attribute" '[:div {:aria-labelledby "hdr"} "x"])
  (silent! "a valid token" '[:div {:aria-live "polite"} "x"])
  (silent! "booleans normalize to the true/false tokens"
           '[:div {:aria-expanded true :aria-hidden false} "x"])
  (silent! "the string spellings are equally valid"
           '[:div {:aria-expanded "true"} "x"])
  (silent! "\"undefined\" is a real ARIA tristate value"
           '[:div {:aria-checked "undefined"} "x"])
  (silent! "numeric attributes accept numbers and numeric strings"
           '[:div {:aria-level 2 :aria-posinset "3" :aria-valuenow 0.5} "x"])
  (silent! "free-form attributes take any string"
           '[:div {:aria-label "anything at all" :aria-roledescription "slide"} "x"])
  ;; THE bound: a value computed at runtime is never judged.
  (silent! "a DYNAMIC value for a token attribute is opaque"
           '[:div {:aria-expanded open?} "x"])
  (silent! "a DYNAMIC value for a numeric attribute is opaque"
           '[:div {:aria-level depth} "x"])
  (silent! "a (sub …) read is opaque"
           '[:div {:aria-live (sub [:politeness])} "x"])
  (silent! "data-* is not this check's business" '[:div {:data-aria "nope"} "x"])
  (testing "a DYNAMIC value still gets its NAME checked"
    (is (= [bad-aria] (ids '[:div {:aria-expandd open?} "x"]))
        "a typo is provable even when the value is not")))

;; ---------------------------------------------------------------------------
;; Check 3 — a11y-click-non-interactive
;; ---------------------------------------------------------------------------

(def ^:private click-non-interactive :rf.ui.compile/a11y-click-non-interactive)

(deftest click-non-interactive-fires
  (fires! click-non-interactive '[:div {:on-click [:row/open]} "Open"]
          "[:button {:on-click" ":role \"button\"")
  (fires! click-non-interactive '[:span {:on-click [:row/open]} "Open"]
          "[:button {:on-click")
  (fires! click-non-interactive '[:li {:on-click (handler [ev] (go! ev))} "Open"]
          "[:button {:on-click"))

(deftest click-non-interactive-is-silent-when-not-provable
  (silent! "the native control is already correct"
           '[:button {:on-click [:row/open]} "Open"])
  (silent! "a real link is already correct"
           '[:a {:href "/row/1" :on-click [:row/open]} "Open"])
  (silent! "a declared interactive role plus focus plus keys is a control"
           '[:div {:role "button" :tab-index 0
                   :on-click [:row/open] :on-key-down [:row/open]} "Open"])
  ;; THE bounds: anything the compiler cannot see means silence.
  (silent! "a DYNAMIC :role may well be interactive"
           '[:div {:role row-role :on-click [:row/open]} "Open"])
  (silent! "a spread may carry :role and :tab-index"
           '[:div (spread-safe {:on-click [:row/open]} caller) "Open"])
  (silent! "an explicitly focusable element with key handling is deliberate"
           '[:div {:tab-index 0 :on-click [:row/open]
                   :on-key-down [:row/open]} "Open"])
  (silent! "contenteditable surfaces are their own interaction model"
           '[:div {:content-editable true :on-click [:row/open]} "Open"])
  (silent! "aria-hidden markup is not reachable at all"
           '[:div {:aria-hidden true :on-click [:row/open]} "Open"])
  (silent! "a non-activation handler is not a click"
           '[:div {:on-mouse-over [:row/peek]} "Open"])
  (silent! "custom elements own their semantics"
           '[:my-widget {:on-click [:row/open]} "Open"]))

;; ---------------------------------------------------------------------------
;; Check 4 — a11y-presence-exit-interactive
;; ---------------------------------------------------------------------------
;;
;; rf2-0ufty ruled presence DOM-agnostic and timeout-only: the framework stamps
;; NO inert/aria-hidden, and the child owns its exit accessibility by reading
;; (v/presence-phase). This check is not a style opinion — it fires on the one
;; shape where that author remedy is STRUCTURALLY unavailable: inline literal
;; markup, whose props evaluate in the parent's render, outside the per-child
;; phase Provider.

(def ^:private exit-interactive :rf.ui.compile/a11y-presence-exit-interactive)

(deftest presence-exit-interactive-fires-only-on-inline-literal-markup
  (fires! exit-interactive
          '(presence {:timeout-ms 300}
                     (for [t toasts]
                       [:div {:key (:id t)}
                        [:button {:on-click [:toast/dismiss]} "Dismiss"]]))
          "(v/presence-phase) = :unmounting" "keyed child view")
  (testing "an explicitly focusable generic element counts too"
    (is (= [exit-interactive]
           (ids '(presence {:timeout-ms 300}
                           (for [t toasts]
                             [:div {:key (:id t) :tab-index 0} "row"])))))))

(deftest presence-exit-interactive-is-silent-when-the-child-can-remedy-it
  ;; THE headline bound: Spec 004's own presence example must stay silent.
  (silent! "a child VIEW reads its own (v/presence-phase) and stamps inert"
           '(presence {:timeout-ms 300}
                      (for [t toasts] [child-view {:key (:id t) :toast t}])))
  (silent! "a foreign component owns its own exit behaviour"
           '(presence {:timeout-ms 300}
                      (for [t toasts] [ForeignComp {:key (:id t)}])))
  (silent! "inline markup with nothing focusable in it"
           '(presence {:timeout-ms 300}
                      (for [t toasts] [:div {:key (:id t)} (:text t)])))
  (silent! "a disabled control is not in the tab order"
           '(presence {:timeout-ms 300}
                      (for [t toasts]
                        [:div {:key (:id t)}
                         [:button {:disabled true} "Dismiss"]])))
  (silent! ":tab-index -1 removes it from the tab order"
           '(presence {:timeout-ms 300}
                      (for [t toasts]
                        [:div {:key (:id t)}
                         [:button {:tab-index -1} "Dismiss"]])))
  (silent! "the author already stamped aria-hidden"
           '(presence {:timeout-ms 300}
                      (for [t toasts]
                        [:div {:key (:id t) :aria-hidden true}
                         [:button {:tab-index -1} "Dismiss"]])))
  (silent! "a spread may carry :inert or :tab-index"
           '(presence {:timeout-ms 300}
                      (for [t toasts]
                        [:div {:key (:id t)}
                         [:button (spread-safe {} caller) "Dismiss"]])))
  (testing "OUTSIDE a presence boundary the check does not exist"
    (is (= [] (ids '[:div [:button {:on-click [:x]} "Dismiss"]]))
        "an ordinary button is not an exit-window hazard")))

;; ---------------------------------------------------------------------------
;; Suppression — the round trip
;; ---------------------------------------------------------------------------

(deftest suppression-silences-the-warning-but-keeps-the-manifest-fact
  (let [form '^{:rf.ui/suppress
                {:rf.ui.compile/a11y-click-non-interactive
                 "drag surface; the keyboard path is the toolbar button"}}
              [:div {:on-click [:row/open]} "Open"]
        {:keys [warnings diagnostics]} (run form)]
    (is (= [] warnings) "a suppressed finding PRINTS nothing")
    (is (= 1 (count diagnostics))
        "a suppressed finding is still a manifest :diagnostics site")
    (let [d (first diagnostics)]
      (is (= click-non-interactive (:id d)))
      (is (true? (:suppressed? d)))
      (is (= "drag surface; the keyboard path is the toolbar button" (:reason d))
          "the site carries the author's REASON")
      (is (string? (:sid d)) "the compiler mints the stable site id"))))

(deftest unsuppressed-findings-are-manifest-sites-too
  (let [{:keys [diagnostics]} (run '[:div {:on-click [:row/open]} "Open"])
        d (first diagnostics)]
    (is (= 1 (count diagnostics)))
    (is (false? (:suppressed? d)))
    (is (nil? (:reason d)) "no reason where nothing was suppressed")
    (is (string? (:sid d)))))

(deftest suppression-is-per-id-not-a-blanket
  (let [form '^{:rf.ui/suppress
                {:rf.ui.compile/a11y-missing-accessible-name "unrelated"}}
              [:div {:on-click [:row/open]} "Open"]]
    (is (= [click-non-interactive] (ids form))
        "suppressing a DIFFERENT id leaves this finding printing")))

(deftest malformed-suppression-is-a-loud-compile-error
  (doseq [[why form] [["an unknown id"
                       '^{:rf.ui/suppress {:rf.ui.compile/a11y-typo "r"}}
                       [:div {:on-click [:x]} "y"]]
                      ["a blank reason"
                       '^{:rf.ui/suppress
                          {:rf.ui.compile/a11y-click-non-interactive "  "}}
                       [:div {:on-click [:x]} "y"]]
                      ["a non-string reason"
                       '^{:rf.ui/suppress
                          {:rf.ui.compile/a11y-click-non-interactive true}}
                       [:div {:on-click [:x]} "y"]]
                      ["an empty map"
                       '^{:rf.ui/suppress {}} [:div {:on-click [:x]} "y"]]]]
    (testing why
      (let [ex (reject form)]
        (is (some? ex) (str why " must be rejected"))
        (is (= :rf.ui.compile/bad-suppress
               (:rf.ui.compile/error (ex-data ex))))))))

(deftest suppression-is-validated-even-on-a-clean-element
  ;; A typo'd suppression on an element that has no finding must NOT pass
  ;; silently — that is exactly how a suppression rots into a no-op.
  (is (= :rf.ui.compile/bad-suppress
         (:rf.ui.compile/error
          (ex-data (reject '^{:rf.ui/suppress {:rf.ui.compile/nonsense "r"}}
                           [:p "perfectly fine"]))))))

;; ---------------------------------------------------------------------------
;; The roster is closed
;; ---------------------------------------------------------------------------

(deftest every-a11y-id-is-exercised-in-both-directions
  (is (= a11y/a11y-warning-ids
         #{missing-name bad-aria click-non-interactive exit-interactive})
       "the four ruled ids and no more — additions are a roster change")
  (doseq [id a11y/a11y-warning-ids]
    (is (str/starts-with? (namespace id) "rf.ui.compile")
        (str id " is a COMPILE-tier id (no Spec 009 row, no runtime emission)"))))
