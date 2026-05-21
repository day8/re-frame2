(ns day8.re-frame2-causa.panels.app-db-segment-inspector-cljs-test
  "Content-projection guard for the App-DB segment inspector popup
  (rf2-dkmnm — closes the gap flagged in
  ai/findings/2026-05-21-testcov-causa.md §Axis 1.2).

  Before this file the inspector (`panels/app-db-segment-inspector`)
  was exercised only for its ARIA SHAPE (`modals-aria-cljs-test`) and
  indirectly for the breadcrumb-open dispatch
  (`diff/render-cljs-test`). Its OWN content projection — the
  path-prefix slice the value sub computes + the value the body
  renders — had no focused test. This is that test.

  ## Wiring

  `:rf.causa/segment-inspector-value` chains off
  `:rf.causa/target-frame-db`, which derives the *observed* frame
  (default `:rf/default`) and reads `get-frame-db` on it. So seeding
  the host `:rf/default` frame's db drives the projection through the
  exact production sub-chain — no stubbed seam."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.panels.app-db-segment-inspector
             :as segment-inspector]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]))

(defn- causa-init! []
  (causa-test-support/reset-all!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- frame + host-db setup ----------------------------------------------

(def host-db
  "A nested host app-db so the inspector's path-prefix slice has
  something to project at each depth."
  {:cart {:items [{:id 7 :qty 1} {:id 22 :qty 3}]
          :gross 42}
   :user {:name "ada" :prefs {:theme :dark}}})

(defn- setup!
  "Register Causa's handler graph + the `:rf/causa` and host `:rf/default`
  frames, then seed the host db so the value sub projects from it."
  []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (frame/reg-frame :rf/default {})
  ;; A one-off host event to plant a known db on the observed frame.
  (rf/reg-event-db :test/seed-host-db (fn [_ [_ db]] db))
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:test/seed-host-db host-db])))

;; ---- hiccup walk helpers ------------------------------------------------

(defn- hiccup-seq [tree]
  (tree-seq (some-fn vector? seq?) seq tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- text-content
  "Flatten all string leaves of a hiccup subtree into one string —
  enough to assert the rendered value text shows up."
  [tree]
  (->> (hiccup-seq tree)
       (filter string?)
       (apply str)))

(defn- read-value-sub []
  (rf/with-frame :rf/causa
    @(rf/subscribe [:rf.causa/segment-inspector-value])))

;; ---- value-sub: path-prefix slice --------------------------------------

(deftest value-sub-empty-path-projects-whole-db
  (testing "rf2-e9tb0 — an empty path (the root breadcrumb) projects
            the whole observed-frame db"
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-segment-inspector []]))
    (is (= host-db (read-value-sub))
        "empty path did not project the whole db")))

(deftest value-sub-projects-path-prefix-slice
  (testing "rf2-e9tb0 — a non-empty path projects `get-in db path` —
            the slice AT the clicked prefix, not the whole db"
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-segment-inspector [:cart]]))
    (is (= (:cart host-db) (read-value-sub))
        "[:cart] did not slice to the cart map")
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-segment-inspector [:cart :gross]]))
    (is (= 42 (read-value-sub))
        "[:cart :gross] did not slice to the leaf value")
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-segment-inspector [:user :prefs :theme]]))
    (is (= :dark (read-value-sub))
        "[:user :prefs :theme] did not slice to the deep leaf")))

(deftest value-sub-tracks-reopened-path
  (testing "rf2-e9tb0 — reopening at a new path re-projects (the slot
            is overwritten; the value sub re-fires for the new prefix)"
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-segment-inspector [:cart]]))
    (let [v0 (read-value-sub)]
      (rf/with-frame :rf/causa
        (rf/dispatch-sync [:rf.causa/open-segment-inspector [:user]]))
      (let [v1 (read-value-sub)]
        (is (not= v0 v1)
            "value sub did not re-project on reopen at a new path")
        (is (= (:user host-db) v1)
            "reopened value sub did not slice to the new prefix")))))

;; ---- popup-view: header + body content ---------------------------------

(deftest popup-renders-value-and-path-in-body-and-header
  (testing "rf2-e9tb0 — the open popup renders the inspected path in
            the header title and the sliced VALUE in the body (the
            content projection the breadcrumb click ultimately
            surfaces). This is the gap the ARIA-only test left open."
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-segment-inspector [:user]]))
    (let [tree   (rf/with-frame :rf/causa (segment-inspector/Popup))
          title  (find-by-testid tree "rf-causa-segment-inspector-title")
          body   (find-by-testid tree "rf-causa-segment-inspector-body")]
      (is (some? tree) "Popup renders when open")
      (is (some? title) "header title node renders")
      (is (some? body) "body node renders")
      ;; The header echoes the inspected path so the user knows what
      ;; they are looking at.
      (let [title-text (text-content title)]
        (is (re-find #":user" title-text)
            "header title did not echo the inspected path"))
      ;; The body renders the sliced value — the user's name appears
      ;; somewhere in the projected tree.
      (let [body-text (text-content body)]
        (is (re-find #"ada" body-text)
            "body did not render the sliced value's content")))))

(deftest popup-root-path-header-says-root
  (testing "rf2-e9tb0 — an empty path inspects the whole db; the
            header title reads '(root)' so the user isn't left guessing
            what scope they are inspecting"
    (setup!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/open-segment-inspector []]))
    (let [tree  (rf/with-frame :rf/causa (segment-inspector/Popup))
          title (find-by-testid tree "rf-causa-segment-inspector-title")]
      (is (re-find #"root" (text-content title))
          "root-path header title did not read '(root)'"))))

(deftest popup-closed-renders-nothing
  (testing "rf2-e9tb0 — the closed-state body short-circuits to nil
            (the single-subscribe + `when` cheapness contract)"
    (setup!)
    (is (nil? (rf/with-frame :rf/causa (segment-inspector/Popup)))
        "closed segment inspector did not render nil")))
