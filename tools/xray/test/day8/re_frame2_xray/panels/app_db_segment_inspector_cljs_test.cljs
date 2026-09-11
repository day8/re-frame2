(ns day8.re-frame2-xray.panels.app-db-segment-inspector-cljs-test
  "Content-projection guard for the App-DB segment inspector popup
  (rf2-dkmnm — closes the gap flagged in
  ai/findings/2026-05-21-testcov-xray.md §Axis 1.2).

  Before this file the inspector (`panels/app-db-segment-inspector`)
  was exercised only for its ARIA SHAPE (`modals-aria-cljs-test`).
  Its OWN content projection — the path-prefix slice the value sub
  computes + the value the body renders — had no focused test. This
  is that test.

  ## Wiring

  `:rf.xray/segment-inspector-value` chains off
  `:rf.xray/app-db-current+diff` (rf2-jmucu), which resolves the
  FOCUSED epoch's `:db-after` (its own post-state) — the SAME image the
  App-DB panel body renders. With no epoch focused (the path-prefix
  tests below) it falls back to the live observed-frame db, so seeding
  the host `:rf/default` frame's db still drives the projection through
  the exact production sub-chain — no stubbed seam. The off-head
  consistency test additionally seeds an epoch history + focuses a
  non-head epoch to pin the popup==panel-body invariant."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.panels.app-db-segment-inspector
             :as segment-inspector]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.edn-widget :as edn]))

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the bespoke `xray-init!`
  ;; (core `make-reset-runtime-fixture` + Xray `reset-all!`) into one owner:
  ;; plain-atom adapter + the default `:all` reset tier — install/registry/
  ;; mount idempotency sentinels plus the trace-collector rings.
  (xray-test-support/make-xray-runtime-fixture))

;; ---- frame + host-db setup ----------------------------------------------

(def host-db
  "A nested host app-db so the inspector's path-prefix slice has
  something to project at each depth."
  {:cart {:items [{:id 7 :qty 1} {:id 22 :qty 3}]
          :gross 42}
   :user {:name "ada" :prefs {:theme :dark}}})

(defn- setup!
  "Register Xray's handler graph + the `:rf/xray` and host `:rf/default`
  frames, then seed the host db so the value sub projects from it."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id :rf/default})
  ;; A one-off host event to plant a known db on the observed frame.
  (rf/reg-event :test/seed-host-db (fn [_ [_ db]] {:db db}))
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:test/seed-host-db host-db]))
  ;; EP-0002 (rf2-bd4div) — the inspected target no longer defaults to
  ;; `:rf/default`; select the host frame explicitly so the value sub's
  ;; observed-frame fallback (`:rf.xray/target-frame-db`) projects from it
  ;; rather than nil.
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/set-target-frame :rf/default])))

;; ---- hiccup walk helpers ------------------------------------------------
;; The private expand-tree (form-2 aware) / hiccup-seq / find-by-testid /
;; text-content copies were semantically identical to `re-frame.test-helpers`
;; (`rf.test-helpers/expand-tree` handles the form-2 edn-inspector widget too); tests call
;; `rf.test-helpers/find-by-testid` / `rf.test-helpers/text-content` directly (rf2-vj80u8 — no Xray
;; walker facade).

(defn- read-value-sub []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/segment-inspector-value])))

;; ---- value-sub: path-prefix slice --------------------------------------

(deftest value-sub-empty-path-projects-whole-db
  (testing "rf2-e9tb0 — an empty path (the root breadcrumb) projects
            the whole observed-frame db"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector []]))
    (is (= host-db (read-value-sub))
        "empty path did not project the whole db")))

(deftest value-sub-projects-path-prefix-slice
  (testing "rf2-e9tb0 — a non-empty path projects `get-in db path` —
            the slice AT the clicked prefix, not the whole db"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:cart]]))
    (is (= (:cart host-db) (read-value-sub))
        "[:cart] did not slice to the cart map")
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:cart :gross]]))
    (is (= 42 (read-value-sub))
        "[:cart :gross] did not slice to the leaf value")
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:user :prefs :theme]]))
    (is (= :dark (read-value-sub))
        "[:user :prefs :theme] did not slice to the deep leaf")))

(deftest value-sub-tracks-reopened-path
  (testing "rf2-e9tb0 — reopening at a new path re-projects (the slot
            is overwritten; the value sub re-fires for the new prefix)"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:cart]]))
    (let [v0 (read-value-sub)]
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/open-segment-inspector [:user]]))
      (let [v1 (read-value-sub)]
        (is (not= v0 v1)
            "value sub did not re-project on reopen at a new path")
        (is (= (:user host-db) v1)
            "reopened value sub did not slice to the new prefix")))))

;; ---- popup-view: header + body content ---------------------------------
;;
;; rf2-k97c.3 — `Popup` is now an `rf.fresco/as-component` bridge and
;; answers an interop vector, not a tree to walk. The markup is
;; `popup-tree`, a pure fn of the values the boundary reads.
;;
;; The helper below reproduces `PopupView`'s gate and reads EXACTLY —
;; same gate, same order, same query vectors — so every row here asserts
;; on the same hiccup it did before, and a boundary that stopped reading
;; one of these slots would diverge from its own test helper rather than
;; silently agreeing with it.
;;
;; Ambient `rf/subscribe` deliberately: these rows run under
;; `rf/with-frame :rf/xray` in the node lane with no React commit at all.
;; What the boundary's own read resolves to — the frame React context
;; names, not the ambient one — is the browser lane's subject.

(defn- popup-tree
  "What calling `Popup` directly returned before the migration: nil while
  closed, the dialog otherwise.

  `:inspector` substitutes the REAGENT twin `edn/inspect` for the
  boundary head production emits. That substitution is not cosmetic and
  it is what lets the rows below read the rendered value at all:
  `rf.test-helpers/find-by-testid` and `text-content` collect their nodes
  off `expand-tree`, which INVOKES function components, and a Fresco
  boundary may not be invoked as a hiccup render fn — it raises inside
  the body. The two heads share ONE private renderer and differ only in
  how each resolves the expansion slots and its per-mount identity,
  neither of which any row here asserts on, so the markup these rows walk
  is the markup production renders. WHICH HEAD THE PANEL ACTUALLY EMITS
  is deliberately not graded here — it is erased by this substitution —
  and `popup-body-embeds-a-legal-head-when-open` pins it directly off the
  UNSUBSTITUTED tree instead."
  []
  (when @(rf/subscribe [:rf.xray/segment-inspector-open?])
    (segment-inspector/popup-tree
      {:path        @(rf/subscribe [:rf.xray/segment-inspector-path])
       :value       @(rf/subscribe [:rf.xray/segment-inspector-value])
       :positioning @(rf/subscribe [:rf.xray/modal-positioning])
       :inspector   edn/inspect})))

(defn- popup-tree-as-emitted
  "The popup's markup with the head production actually emits — no
  substitution. Walk it RAW; expanding it raises inside the boundary,
  which is the property `popup-body-embeds-a-legal-head-when-open`
  exists to assert rather than to trip over."
  []
  (when @(rf/subscribe [:rf.xray/segment-inspector-open?])
    (segment-inspector/popup-tree
      {:path        @(rf/subscribe [:rf.xray/segment-inspector-path])
       :value       @(rf/subscribe [:rf.xray/segment-inspector-value])
       :positioning @(rf/subscribe [:rf.xray/modal-positioning])})))

(defn- raw-nodes
  "Every node in the tree AS THE PANEL EMITS IT — no expansion of any
  kind, so a widget head is whatever the panel actually wrote there."
  [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(deftest popup-renders-value-and-path-in-body-and-header
  (testing "rf2-e9tb0 — the open popup renders the inspected path in
            the header title and the sliced VALUE in the body (the
            content projection the breadcrumb click ultimately
            surfaces). This is the gap the ARIA-only test left open."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:user]]))
    (let [tree   (rf/with-frame :rf/xray (popup-tree))
          title  (rf.test-helpers/find-by-testid tree "rf-xray-segment-inspector-title")
          body   (rf.test-helpers/find-by-testid tree "rf-xray-segment-inspector-body")]
      (is (some? tree) "Popup renders when open")
      (is (some? title) "header title node renders")
      (is (some? body) "body node renders")
      ;; The header echoes the inspected path so the user knows what
      ;; they are looking at.
      (let [title-text (rf.test-helpers/text-content title)]
        (is (re-find #":user" title-text)
            "header title did not echo the inspected path"))
      ;; The body renders the sliced value — the user's name appears
      ;; somewhere in the projected tree.
      (let [body-text (rf.test-helpers/text-content body)]
        (is (re-find #"ada" body-text)
            "body did not render the sliced value's content")))))

(deftest popup-root-path-header-says-root
  (testing "rf2-e9tb0 — an empty path inspects the whole db; the
            header title reads '(root)' so the user isn't left guessing
            what scope they are inspecting"
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector []]))
    (let [tree  (rf/with-frame :rf/xray (popup-tree))
          title (rf.test-helpers/find-by-testid tree "rf-xray-segment-inspector-title")]
      (is (re-find #"root" (rf.test-helpers/text-content title))
          "root-path header title did not read '(root)'"))))

(deftest popup-closed-renders-nothing
  (testing "rf2-e9tb0 — the closed-state body short-circuits to nil
            (the single-subscribe + `when` cheapness contract)"
    (setup!)
    (is (nil? (rf/with-frame :rf/xray (popup-tree)))
        "closed segment inspector did not render nil")))

;; ---- rf2-k97c.3: head legality, asserted DIRECTLY -----------------------
;;
;; The node lane structurally CANNOT prove this. `expand-tree` invokes a
;; fn head, so `[popup …]` and `(popup …)` expand identically — a green
;; row above says the markup is right, and says nothing whatever about
;; whether a head inside it is legal under a Fresco boundary. And this is
;; a MODAL: it renders nothing unless the open state is seeded, so its
;; heads are invisible to any row that does not open it first.
;;
;; So the heads are interrogated directly rather than walked, and the
;; interrogation is controlled in BOTH directions so a positive cannot be
;; vacuous. `head-kind` is the codec's own dispatch — the same function
;; `vec->element` uses to decide what to build — so this is the property
;; the shell's root swap actually depends on.

(deftest popup-view-is-a-fresco-boundary
  (testing "rf2-k97c.3 — `PopupView` is an `rf.fresco/defview` boundary,
            so the shell root becoming a Fresco tree mounts it rather
            than refusing it. A revert to `rf/reg-view` (or to a plain
            `defn`) grades `:invalid` down the same codec arm and reds
            this row — which is the point, since that revert is silent
            under Reagent."
    (setup!)
    (is (= :boundary (rf.fresco.impl.codec/head-kind
                       segment-inspector/PopupView))
        "PopupView is a Fresco boundary")))

(deftest popup-body-embeds-a-legal-head-when-open
  (testing "rf2-k97c.3 — the value-rendering head INSIDE the open dialog
            is a boundary too, so the subtree is legal all the way down.
            The dialog is opened first: a modal renders nothing while
            closed, so a row that did not seed the open state would assert
            on an empty tree and pass vacuously."
    (setup!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/open-segment-inspector [:user]]))
    (let [tree  (rf/with-frame :rf/xray (popup-tree-as-emitted))
          heads (->> (raw-nodes tree)
                     (filter vector?)
                     (map first)
                     (filter fn?))]
      (is (some? tree) "the dialog is open, so there is a tree to grade")
      (is (seq heads)
          "there is a fn head in the emitted tree — otherwise the check
           below is an absence reported by a dead instrument")
      (is (every? #(= :boundary (rf.fresco.impl.codec/head-kind %)) heads)
          "every fn head in the open dialog grades :boundary")
      ;; Both directions: the Reagent head this replaced grades :invalid,
      ;; so `:boundary` above is a real discrimination and not something
      ;; `head-kind` says about everything.
      (is (= :invalid (rf.fresco.impl.codec/head-kind
                        (first (edn/inspect {:probe 1} "probe"))))
          "`edn/inspect`, the Reagent head this file used to call, grades
           :invalid")
      (is (= :boundary (rf.fresco.impl.codec/head-kind
                         (first (edn/inspect-view {:probe 1} "probe"))))
          "`edn/inspect-view`, the head it calls now, grades :boundary"))))

;; ---- rf2-jmucu: off-head popup==panel-body consistency ------------------
;;
;; Post-rf2-02j4r the App-DB panel BODY shows the FOCUSED epoch's own
;; `:db-after` (via `:rf.xray/app-db-current+diff`'s `:value`), NOT the
;; live db — so selecting epoch N shows N's own state with no later-event
;; bleed. The breadcrumb segment-inspector popup pops OUT OF that body, so
;; it must agree with it. Before rf2-jmucu the popup read
;; `:rf.xray/target-frame-db` (the LIVE deref), so off-head the popup
;; showed live state while the body showed epoch N — the same later-event
;; bleed rf2-02j4r killed in the body, surviving in the popup. This test
;; pins the invariant: at a non-head focus, the popup value == the panel
;; body's focused-epoch value, and is NOT the live value.

(defn- mk-record
  "Minimal `:rf/epoch-record` for the history seed (mirrors the
  app-db-diff test's builder)."
  [epoch-id event db-before db-after]
  {:epoch-id      epoch-id
   :frame         :rf/default
   :committed-at  0
   :event-id      (first event)
   :trigger-event event
   :db-before     db-before
   :db-after      db-after
   :trace-events  []})

(defn- setup-history!
  "Register Xray's handler graph + the `:rf/xray` and host `:rf/default`
  frames, seed the host `:rf/default` frame's LIVE db, and seed the
  `:rf/xray` frame's `:epoch-history`. Drives the focused-epoch sub
  chain through the production wiring (`:rf.xray/select-epoch` → the
  `:rf.xray/focus` spine sub → `:rf.xray/app-db-current+diff`)."
  [live-db history]
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id :rf/default})
  (rf/replace-frame-state! :rf/default {:rf.db/app live-db})
  (rf/reg-event :rf.xray-test/seed-history
    (fn [{:keys [db]} [_ records]]
      {:db (assoc db :epoch-history (vec records))}))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray-test/seed-history history])))

(deftest popup-value-matches-focused-epoch-not-live-off-head
  (testing "rf2-jmucu — off-head, the segment-inspector popup value at a
            path equals the FOCUSED epoch's `:db-after` value at that path
            (the same image the panel body renders), NOT the live db."
    ;; History: counter 5 → 6 → 7. LIVE db is at the head (counter 7).
    ;; Focus the MIDDLE epoch (:e-2, counter 6) — a non-head selection.
    (let [live    {:counter 7 :user {:name "ada"}}
          history [(mk-record :e-1 [:counter/inc] {:counter 5} {:counter 6 :user {:name "ada"}})
                   (mk-record :e-2 [:counter/inc]
                              {:counter 6 :user {:name "ada"}}
                              {:counter 6 :user {:name "ada"} :flash {:text "saved"}})
                   (mk-record :e-3 [:counter/inc]
                              {:counter 6 :user {:name "ada"} :flash {:text "saved"}}
                              live)]]
      (setup-history! live history)
      (rf/with-frame :rf/xray
        ;; Focus the non-head epoch via the production spine shim.
        (rf/dispatch-sync [:rf.xray/select-epoch :e-2]))
      ;; Sanity: the panel body's source (`:value`) is :e-2's :db-after,
      ;; not live — establishes what "agree with the body" means here.
      (let [panel-value (rf/with-frame :rf/xray
                          (:value @(rf/subscribe [:rf.xray/app-db-current+diff])))]
        (is (= (:db-after (nth history 1)) panel-value)
            "precondition: the panel body shows :e-2's :db-after off-head")
        (is (not= live panel-value)
            "precondition: the panel body is NOT showing the live db off-head")
        ;; Now open the popup at a path present in BOTH the focused epoch
        ;; and live, but VALUED DIFFERENTLY (the bleed-prone case): the
        ;; focused epoch has no :flash before :e-2; counter differs
        ;; between :e-2 (6) and live (7).
        (rf/with-frame :rf/xray
          (rf/dispatch-sync [:rf.xray/open-segment-inspector [:counter]]))
        (is (= 6 (read-value-sub))
            "popup at [:counter] must show :e-2's value (6), not the live head (7)")
        (is (not= (:counter live) (read-value-sub))
            "popup at [:counter] must NOT bleed the live head value")
        ;; The popup at the focused epoch's [:flash] subtree must match
        ;; the panel-body image exactly.
        (rf/with-frame :rf/xray
          (rf/dispatch-sync [:rf.xray/open-segment-inspector [:flash]]))
        (is (= (get-in (:db-after (nth history 1)) [:flash]) (read-value-sub))
            "popup [:flash] slice must equal the focused epoch's slice")
        ;; Whole-db (root) popup must equal the focused epoch's :db-after,
        ;; not live — the strongest form of popup==panel-body agreement.
        (rf/with-frame :rf/xray
          (rf/dispatch-sync [:rf.xray/open-segment-inspector []]))
        (is (= (:db-after (nth history 1)) (read-value-sub))
            "root popup must equal the focused epoch's whole :db-after")
        (is (not= live (read-value-sub))
            "root popup must NOT equal the live db off-head")))))

(deftest popup-value-equals-live-on-head
  (testing "rf2-jmucu — on-head (focus the newest epoch), the popup value
            equals the live db, because the head epoch's :db-after IS the
            live db. Consistency holds at every scrub position, including
            head."
    (let [live    {:counter 7 :user {:name "ada"}}
          history [(mk-record :e-1 [:counter/inc] {:counter 6 :user {:name "ada"}} live)]]
      (setup-history! live history)
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/select-epoch :e-1]))
      (rf/with-frame :rf/xray
        (rf/dispatch-sync [:rf.xray/open-segment-inspector [:counter]]))
      (is (= 7 (read-value-sub))
          "on-head, popup at [:counter] equals the live head value (7)"))))
