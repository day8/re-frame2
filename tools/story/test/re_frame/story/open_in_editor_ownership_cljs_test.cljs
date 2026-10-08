(ns re-frame.story.open-in-editor-ownership-cljs-test
  "Cross-tool ownership contract for open-in-editor.

  Story and Xray each own a DISTINCT open-in-editor effect —
  `:rf.story.fx/open-in-editor` and `:rf.xray.fx/open-in-editor`.
  `re-frame.registrar/register!` is last-writer-wins, so with one shared
  id whichever tool installed LAST would commandeer the other's editor,
  project root and navigator — and co-loading is the flagship topology
  (Story mounts Xray as its embed). Both tools are configured with
  DIFFERENT editors and roots, installed in both orders, and each tool's
  public event must reach its OWN navigator with its OWN URI.

  The real registered effects are driven and observed at each tool's
  navigator seam (`set-navigator!`); stubbing them via `:fx-overrides`
  would replace the very registration under test. The shared core
  endpoint launcher (`rf.source-coords.open-endpoint/set-launcher!`) is
  stubbed to call the `fallback!` thunk, forcing the synchronous
  `editor://` URI path."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.source-coords.open-endpoint :as rf.source-coords.open-endpoint]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story.canonical :as rf.story.canonical]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.ui.open-in-editor :as rf.story.ui.open-in-editor]
            [day8.re-frame2-xray.config :as xray-config]
            [day8.re-frame2-xray.open-in-editor :as xray-open]))

;; ---- deliberately divergent per-tool configuration ----------------------
;;
;; Different editor AND different project root on each side, so a URI
;; observed at either navigator identifies its originating tool
;; unambiguously — a takeover cannot masquerade as a pass.

(def ^:private story-editor :zed)
(def ^:private story-root   "C:/repo/story-root")
(def ^:private xray-editor  :idea)
(def ^:private xray-root    "C:/repo/xray-root")

(def ^:private coord {:file "src/app/events.cljs" :line 17 :column 3})

(def ^:private story-uri
  "zed://file/C:/repo/story-root/src/app/events.cljs:17:3")

(def ^:private xray-uri
  "idea://open?file=C:/repo/xray-root/src/app/events.cljs&line=17&column=3")

;; ---- capture seams -------------------------------------------------------

(defonce ^:private story-navigated (atom []))
(defonce ^:private xray-navigated  (atom []))

(defn- configure-both-tools!
  "Give each tool its own editor + project root, and clear any operator
  override so `get-editor` resolves to the host default on the Xray side."
  []
  (rf.story.config/set-editor! story-editor)
  (rf.story.config/set-project-root! story-root)
  (xray-config/set-editor! xray-editor)
  (xray-config/set-project-root! xray-root)
  (xray-config/update-setting! :general :editor-override nil))

(defn- capture-navigators!
  "Point each tool's navigator seam at its own capture atom."
  []
  (reset! story-navigated [])
  (reset! xray-navigated [])
  (rf.story.ui.open-in-editor/set-navigator! #(swap! story-navigated conj %))
  (xray-open/set-navigator!  #(swap! xray-navigated conj %))
  nil)

(defn- install-story! [] (rf.story.ui.open-in-editor/install!))
(defn- install-xray!  [] (xray-open/install!))

(defn- ensure-adapter!
  "Install the plain-atom test adapter unless one is already installed, so
  `rf/make-frame` has a state-container factory when this namespace runs
  alone. `init!` throws for an already-seated adapter; the catch is that
  no-op branch."
  []
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil)))

(defn- fresh-frames!
  "Story's public event lands on `:rf/default`; Xray's on `:rf/xray`."
  []
  (ensure-adapter!)
  (rf.frame/ensure-default-frame!)
  (rf/make-frame {:id :rf/xray}))

(defn- dispatch-both!
  "Fire each tool's PUBLIC event (the fx-ids are internal)."
  []
  (rf/with-frame :rf/default
    (rf/dispatch-sync [:rf.story/open-in-editor coord]))
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/open-in-editor coord])))

;; Every seam these tests swap is process-global, so each is saved and
;; restored around the test rather than reset to a guessed default.
(use-fixtures :each
  (fn [run-test]
    (let [prev-story-nav (rf.story.ui.open-in-editor/set-navigator! #(swap! story-navigated conj %))
          prev-xray-nav  (xray-open/set-navigator! #(swap! xray-navigated conj %))
          ;; `open-coord!` calls `(@launcher url fallback!)`; invoking
          ;; `fallback!` models "no dev server present", the only path
          ;; carrying the per-tool URI.
          prev-launcher  (rf.source-coords.open-endpoint/set-launcher!
                           (fn [_url fallback!] (fallback!)))]
      (try
        (run-test)
        (finally
          (xray-config/update-setting! :general :editor-override nil)
          (xray-config/set-project-root! nil)
          (rf.story.config/set-project-root! nil)
          (rf.source-coords.open-endpoint/set-launcher! prev-launcher)
          (rf.story.ui.open-in-editor/set-navigator! prev-story-nav)
          (xray-open/set-navigator! prev-xray-nav))))))

;; ---- the ownership contract ---------------------------------------------

(deftest each-tool-keeps-its-own-editor-regardless-of-install-order
  (testing "with one shared fx-id the second installer would overwrite the
            first, so exactly one of these two orders would fail"
    (doseq [[label install-first! install-second!]
            [["story-then-xray" install-story! install-xray!]
             ["xray-then-story" install-xray!  install-story!]]]
      (configure-both-tools!)
      (install-first!)
      (install-second!)
      (fresh-frames!)
      (capture-navigators!)
      (dispatch-both!)

      (is (= [story-uri] @story-navigated)
          (str label " — Story's navigator receives Story's editor ("
               story-editor ") and Story's project root; it is NOT "
               "carrying Xray's " xray-editor " URI"))
      (is (= [xray-uri] @xray-navigated)
          (str label " — Xray's navigator receives Xray's editor ("
               xray-editor ") and Xray's project root; it is NOT "
               "carrying Story's " story-editor " URI")))))

;; ---- Story's public event is installed by canonical boot ----------------

(deftest story-public-event-exists-after-canonical-boot
  (testing "`[:rf.story/open-in-editor coord]` is a documented public
            dispatch whose installer rides the canonical roster, so it
            works after `rf.story.canonical/install!` with no hand-wiring"
    (configure-both-tools!)
    (rf.story.canonical/reset-installed-flag!)
    (rf.story.canonical/install!)
    (fresh-frames!)
    (capture-navigators!)

    (rf/with-frame :rf/default
      (rf/dispatch-sync [:rf.story/open-in-editor coord]))

    (is (= [story-uri] @story-navigated))))
