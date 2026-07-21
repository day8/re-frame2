(ns re-frame.ui.test-guide02-fixture-jvm-test
  "Guide 02 §Advanced examples as fixtures (07 §7: every guide example
  compiles and runs as a fixture; an example needing internals explained
  is an API defect).

  Enrolled here: the chapter's two S4 fences — §Exit animations
  (`ui/presence` + `presence-phase`) and §Custom elements
  (`ui/custom-element` + the declared-property view) — lifted with their
  authored shape, plus the load-bearing prose one-liners each fence
  carries. Both passages dropped their `(lands S4)` markers when S4-A
  (presence, rf2-uckeg) and S4-B (custom-element classification,
  rf2-vea1f) shipped, so under the guide's `unmarked = shipped` law they
  now owe executable proof.

  HOST BOUNDARY, stated rather than papered over: the JVM structural
  render has no lifecycle, so the three-phase enter/exit machine (a
  removed child retained as `:unmounting` for exactly the mandatory
  `:timeout-ms` retention duration — the boundary is DOM-agnostic and
  observes no transition or animation completion — then terminal
  exactly-once removal) is NOT
  provable here — it rides `presence_dom_cljs_test` on the client, and
  `flush-presence!` advances it there (guide 08's fixture binds that
  contract). What this file binds on the JVM is what the JVM genuinely
  owns: the boundary renders with its compile-time `:timeout-ms`, every
  keyed child renders `:present`, `(presence-phase)` is `:present`
  outside any boundary, and the custom-element property/attribute
  classification the chapter states.

  The chapter's earlier fences (the core view model, `ui/error-boundary`,
  the `.react` interop tier) are NOT enrolled by this file; `02-views.md`
  fence #3 is a declared `guide:no-fixture` wave-2 fence."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview presence-phase sub]]
            [re-frame.ui.rules :as rules]
            [re-frame.ui.semantic :as semantic]
            [re-frame.ui.test :as uit]
            [re-frame.ui.tree :as tree]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :ambient-frame nil}))

(defn- all-nodes [t]
  (tree-seq map? (fn [n] (filter map? (:children n))) t))

(defn- presence-node [t]
  (some #(when (contains? % :rf.ui/presence) %) (all-nodes t)))

;; ---------------------------------------------------------------------------
;; §Exit animations: `ui/presence` — the chapter's fence, lifted
;; ---------------------------------------------------------------------------

(defn- reg-toasts!
  "The dataflow the chapter's fence reads: `[:toasts/visible]`. The
  reset-runtime fixture wipes the registry per test, so each test that
  renders the tray re-registers first."
  []
  (rf/reg-sub :toasts/visible (fn [db _] (:toasts db))))

(defview toast-card [{:keys [toast]}]
  (let [phase (presence-phase)]          ; :mounting | :present | :unmounting
    [:div.toast {:class (name phase)}
     (:message toast)]))

(defview toast-tray []
  (ui/presence {:timeout-ms 300}
    (for [t (sub [:toasts/visible])]
      [toast-card {:key (:id t) :toast t}])))

;; "Outside any presence boundary, `(presence-phase)` returns `:present`."
(defview standalone-toast []
  [:div.toast {:class (name (presence-phase))} "standalone"])

(def ^:private fixture-toasts
  [{:id 1 :message "Saved"} {:id 2 :message "Copied"}])

(deftest presence-fence-renders-the-bounded-tray
  (reg-toasts!)
  (rf/with-new-frame
    [frame (rf/make-frame {:initial-events [[:rf/set-db {:toasts fixture-toasts}]]})]
    (let [t (uit/render [toast-tray])
          p (presence-node t)]
      (is (some? p) "the fence's boundary renders as a presence node")
      (testing "the MANDATORY :timeout-ms safety bound is the fence's own 300"
        (is (= 300 (:timeout-ms (:rf.ui/presence p)))))
      (testing "every keyed child from (sub [:toasts/visible]) renders"
        (is (= "SavedCopied" (uit/text t))
            "the tray reads its toasts through the compiled sub path — no prop injection")))))

;; "`(presence-phase)` … :mounting | :present | :unmounting" — the JVM
;; structural subset has no lifecycle, so it always yields :present, and the
;; child's `:class` is the phase name the fence computes.
(deftest presence-phase-names-the-class-the-fence-computes
  (reg-toasts!)
  (rf/with-new-frame
    [frame (rf/make-frame {:initial-events [[:rf/set-db {:toasts fixture-toasts}]]})]
    (let [t (uit/render [toast-tray])]
      (is (= "toast present"
             (:class (uit/attrs (some #(when (= :div (:tag %)) %)
                                      (tree-seq map? :children t)))))
          "the `.toast` shorthand merges with the fence's computed phase class"))))

(deftest presence-phase-is-present-outside-any-boundary
  ;; The chapter's own sentence, executable: a presence-aware child stays
  ;; reusable anywhere — no boundary required to render it.
  (let [t (uit/render [standalone-toast])]
    (is (nil? (presence-node t))
        "no boundary was rendered")
    (is (= "toast present" (:class (uit/attrs (some #(when (= :div (:tag %)) %)
                                                    (tree-seq map? :children t)))))
        "(presence-phase) still resolves — :present outside a boundary")))

;; ---------------------------------------------------------------------------
;; §Custom elements — the chapter's fence, lifted verbatim
;; ---------------------------------------------------------------------------

(ui/custom-element :user-picker {:properties #{:users :selected-id}})

(defview team-picker [{:keys [users current-id]}]
  [:user-picker {:users        users
                 :selected-id  current-id
                 :placeholder  "Choose…"
                 :on-picker-close [:team/picker-closed]}])

(def ^:private fixture-users
  [{:id 7 :name "Ada"} {:id 9 :name "Grace"}])

(defn- picker-node []
  (some #(when (= :user-picker (:tag %)) %)
        (tree-seq map? :children
                  (uit/render [team-picker {:users fixture-users :current-id 7}]))))

;; "A tag containing a `-` is a custom element — used directly, never forced
;; through `ui/raw`."
(deftest custom-element-tag-is-used-directly
  (is (= :user-picker (:tag (picker-node)))
      "the hyphenated tag renders as itself — no ui/raw wrapper in the fence"))

;; "Declared kebab-case property names map to camelCase JS properties.
;;  Undeclared names are attributes."
(deftest declared-names-are-properties-undeclared-are-attributes
  (let [el (picker-node)]
    (is (= #{:users :selected-id} (:rf.ui/property-props el))
        "exactly the fence's declared :properties set classifies as properties")
    (testing "the undeclared names in the same fence stay attributes"
      (is (not (contains? (:rf.ui/property-props el) :placeholder)))
      (is (not (contains? (:rf.ui/property-props el) :on-picker-close))))
    (testing "kebab-case declared names map to camelCase JS property names"
      (is (= "selectedId" (rules/custom-element-property-name "selected-id"))))))

;; "Native custom events ride the normal handler grammar" — the fence's
;; `:on-picker-close` is an ordinary event vector, read like any other intent.
(deftest custom-event-rides-the-normal-handler-grammar
  (is (= [:team/picker-closed]
         (:on-picker-close (uit/attrs (picker-node))))
      "the custom element's handler is an event vector, same as :on-click"))

(deftest jvm-markup-carries-attributes-only
  ;; The server cannot set properties, so the declared ones are omitted from
  ;; markup and applied at hydration; the fence's plain attributes survive.
  (let [[node] (semantic/normalize
                 (tree/render team-picker {:props {:users fixture-users
                                                   :current-id 7}}))
        attrs  (:attrs node)]
    ;; semantic output is markup-space: attribute names are STRINGS.
    (is (= "Choose…" (get attrs "placeholder"))
        "the undeclared attribute is real markup")
    (is (not (contains? attrs "users"))
        "a declared PROPERTY is omitted from server markup")
    (is (not (contains? attrs "selected-id"))
        "…including the kebab-cased one")
    (is (not (contains? attrs "selectedId"))
        "…under neither spelling")
    (is (not (contains? node :rf.ui/property-props))
        "the classification marker itself never reaches markup")))
