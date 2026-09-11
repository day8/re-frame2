(ns day8.re-frame2-xray.settings.settings-fresco-boundary-dom-cljs-test
  "Real-DOM witnesses for the Xray SETTINGS chrome as Fresco boundaries
  (rf2-k97c.3).

  ## Why a browser row at all — the node lane cannot see this

  The node lane drives `settings.view/popup-tree` through
  `test-helpers.modal-trees`, which reproduces the boundary's reads with
  `rf/subscribe`. That is evidence about COMPOSITION — what hiccup the
  values produce — and it is evidence about nothing else, BY
  CONSTRUCTION: it never mounts a boundary, never crosses the
  `as-component` bridge, and never asks the collector for anything. A
  green node lane is therefore compatible with a settings popup that
  paints nothing, or that paints once and never updates again. Only a
  committed DOM can answer those, and this file is the settings sibling
  of `shell_fresco_boundary_dom_cljs_test`.

  ## The mount is the PRODUCTION crossing

  `shell.cljs`'s `shell-view` is still an `rf/reg-view` — severing that
  is the parent epic's coupling (1) and a later slice — and it mounts
  these two modals as the Reagent hiccup heads `[settings-popup/Modal]`
  and `[editor-hint/Toast]`, inside its own `[rf/frame-provider …]`.
  [[mount-modal!]] reproduces exactly that two-level form and nothing
  else. The shell's surrounding chrome is
  `shell_fresco_boundary_dom_cljs_test`'s subject, not this file's; what
  is under test here is the crossing — Reagent parent → plain-fn bridge →
  `[:>]` → Fresco boundary → React context frame → `rf.fresco/sub`.

  Nothing below ever calls a view a second time. Every assertion after
  the mount reads `container.querySelector…` — the DOM React committed on
  its own.

  ## The three rows

  W1 asks whether the popup PAINTS through the bridge. W2 asks whether
  its reads are LIVE AND FRAME-ROUTED, by pressing a real tab button and
  holding a second live frame as the deaf lever. W3 asks the same paint
  question of the second boundary, the editor-hint toast, whose gate is
  the one read that did NOT move out of a helper.

  ## THE WITNESS IS CHOSEN, NOT CONVENIENT — most of this surface cannot witness routing

  W2 watches `:rf.xray/settings-active-tab`. That is deliberate and the
  alternatives are traps. `:rf.xray/setting` — the query behind ELEVEN of
  the boundary's fourteen reads, the theme toggle and every General-tab
  value among them — is

      (or (get-in db [:settings section key]) (config/get-setting section key))

  in `settings/subs.cljs`, so an unseeded app-db slot falls through to a
  PROCESS-GLOBAL atom. A control backed by process-global state answers
  correctly under a deliberately WRONG frame, so it witnesses the read
  path and says nothing whatever about frame routing. Only three queries
  on this surface have LITERAL fallbacks and therefore traverse the
  frame: `:rf.xray/settings-open?`, `:rf.xray/settings-active-tab` and
  `:rf.xray/settings-clear-confirm-open?`. W2 uses the second because it
  is also DRIVEN FROM INSIDE the boundary — a real click on a real
  button, through the dispatcher the body captured — so one row exercises
  the read path and the captured-dispatcher write path in a single round
  trip through the frame.

  ## Node-lane behaviour

  This ns matches the `:browser-test` build's regex and also loads under
  `:node-test`, where every row short-circuits through [[browser?]] and
  reports the skip rather than passing silently."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.settings.editor-hint :as editor-hint]
            [day8.re-frame2-xray.settings.popup :as settings-popup]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private modal-frame
  "The frame this suite's modal instance owns. NOT `:rf/xray`: the
  production singleton is shared with every other suite on the page, and
  naming a private frame is what makes W2's negative half mean
  something."
  ::modal)

(def ^:private other-frame
  "A second live frame W2 dispatches into as its DEAF LEVER — the
  negative control. A write here must move nothing in the popup above."
  ::other)

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture knows nothing about;
                      ;; a neighbour's boundary left in the entry cache
                      ;; would make these rows read a residue that is not
                      ;; this popup's.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; NO `flush-render!` HELPER HERE, and its absence is a finding rather than
;; an omission. A Fresco boundary is NOT in Reagent's render queue — its
;; update is scheduled by the collector through React — so draining
;; Reagent's queue commits nothing of these boundaries', and a row written
;; that way reads a DOM that has not moved and reports a live popup as
;; dead. Mount is committed with `flushSync` (React's own door) and
;; everything after it is polled.

(defn- settle
  "A promise resolving once every render pipeline on the page has had a
  real chance to commit — two animation frames and a macrotask. It exists
  for the CONTROL in W2: an absence asserted immediately after the world
  moves is a race, and an absence asserted after this is a decision."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- poll-until
  "Resolve as soon as `pred` answers truthy, or after `budget-ms`. The
  resolution value is `pred`'s last answer, so a caller asserts on the
  value rather than on the fact that polling ended."
  ([pred] (poll-until pred 2000))
  ([pred budget-ms]
   (js/Promise.
     (fn [resolve]
       (let [deadline (+ (js/Date.now) budget-ms)]
         (letfn [(tick []
                   (let [v (pred)]
                     (cond
                       v                          (resolve v)
                       (> (js/Date.now) deadline) (resolve v)
                       :else (js/requestAnimationFrame (fn [_] (tick))))))]
           (tick)))))))

(defn- setup!
  "Register Xray's handlers — which is what installs the settings subs
  and events these rows drive — and make the frames.

  `:rf/xray` is made as well as the two this suite names. It is
  `shell/default-frame-id`, the production singleton, and several Xray
  registrations reach it by that name regardless of which frame an
  instance is mounted at; a missing frame is a loud re-frame refusal, and
  one raised inside a React render surfaces only as 'an error occurred
  in <…>' with the message nowhere on screen."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id shell/default-frame-id})
  (rf/make-frame {:id modal-frame})
  (rf/make-frame {:id other-frame})
  nil)

(defn- mount-modal!
  "Mount one shell-root modal the way `shell.cljs` mounts it — the
  enclosing `frame-provider` included:

      [rf/frame-provider {:frame …} [settings-popup/Modal]]

  THAT WRAPPER IS LOAD-BEARING. The bridge itself performs no read, so it
  would not refuse without one; the BOUNDARY behind it takes its frame
  from React context, and at a bare root there is no context to take. The
  provider is what puts the instance frame there, exactly as
  `shell-view`'s does in production (rf2-uu3lp).

  Committed synchronously — React 19's `root.render` is otherwise async
  and the first assertion would read an empty container."
  [frame view]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame} [view]])))
    {:container container :root root}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects — which is where
  the collector releases a boundary's reads — have RUN by the time the
  next line reads anything. A bare `.unmount` schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

;; ---- the diagnostic ------------------------------------------------------
;;
;; A re-frame refusal raised inside a React render does NOT reach a
;; `try/catch` around `flushSync`: React 19 catches it, reports "an error
;; occurred in <…>" to the console, and re-raises it as an UNCAUGHT window
;; error. Its `ex-message` and `ex-data` — the whole of what re-frame
;; refuses WITH — then appear nowhere a row can read, and every assertion
;; below reddens on a nil testid saying only that the popup is absent.
;; Capturing the error object is what turns several uninformative failures
;; into one that names the refusal.

(defonce ^:private !last-uncaught (atom nil))

(defonce ^:private error-capture-armed?
  (when (exists? js/window)
    (.addEventListener js/window "error"
                       (fn [^js e] (reset! !last-uncaught (.-error e))))
    true))

(defn- uncaught-note
  "A suffix naming the last uncaught error, for a row whose subject is
  missing. Empty when nothing was thrown — in which case the absence is
  the finding rather than a hidden exception."
  []
  (if-some [e (when error-capture-armed? @!last-uncaught)]
    (str " — an uncaught error was raised during render: "
         (:rf.error/id (ex-data e) (ex-message e))
         " · at: "
         (let [s (str (.-stack ^js e))]
           (->> (str/split-lines s)
                (remove #(str/blank? %))
                (filter #(str/includes? % "at "))
                (take 14)
                (str/join " | "))))
    ""))

(defn- q [container sel] (.querySelector container sel))

(defn- testid [container id]
  (q container (str "[data-testid=\"" id "\"]")))

(defn- active-tabpanel-id
  "The committed settings body's `id`, which encodes the ACTIVE TAB. This
  is W2's needle: it moves only if the boundary re-ran on a real
  `:rf.xray/settings-active-tab` change."
  [container]
  (some-> (testid container "rf-xray-settings-body") (.getAttribute "id")))

;; ===========================================================================
;; W1 — the settings popup PAINTS through the bridge
;; ===========================================================================

(deftest w1-settings-popup-paints-through-the-bridge
  (testing "rf2-k97c.3 — `settings.popup/Popup` is a Fresco boundary and
            commits its dialog through the private `as-component` bridge
            `shell.cljs` still mounts by the name `Modal`. Epic
            criterion 1.

            THIS ROW CARRIES WHAT THE NODE LANE GAVE UP. Every settings
            row in `popup_cljs_test` now drives `view/popup-tree` through
            `test-helpers.modal-trees`, because a boundary's body only
            runs inside a React render window. Those rows are evidence
            about COMPOSITION; this one is the only evidence that the
            composition reaches a screen."
    (if-not (browser?)
      (is true "skipped: no DOM under the :node-test build")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount-modal! modal-frame settings-popup/Modal)]
          (-> (settle)
              (.then
                (fn [_]
                  ;; CLOSED FIRST, and this half is not decoration: the
                  ;; gate now lives INSIDE the boundary rather than in
                  ;; the Reagent bridge, so `nil`-when-closed is a claim
                  ;; about the boundary that only a DOM can check.
                  (is (nil? (testid container "rf-xray-settings-dialog"))
                      "closed: the boundary commits no dialog")
                  (rf/dispatch-sync [:rf.xray/settings-open] {:frame modal-frame})
                  (poll-until #(testid container "rf-xray-settings-dialog"))))
              (.then
                (fn [dialog]
                  (is (some? dialog)
                      (str "open: the dialog is committed to the DOM"
                           (uncaught-note)))
                  (is (some? (testid container "rf-xray-settings-backdrop"))
                      "the backdrop is committed")
                  (is (some? (testid container "rf-xray-settings-tab-strip"))
                      "the tab strip is committed")
                  ;; The default tab's SECTION, which is the half that
                  ;; proves the hoisted reads reached `popup-tree` — an
                  ;; envelope with no body would satisfy every assertion
                  ;; above it.
                  (is (some? (testid container "rf-xray-settings-section-general"))
                      "the General section is committed, so the hoisted
                       values reached the section helpers")
                  (teardown! root container)
                  (done)))))))))

;; ===========================================================================
;; W2 — the reads are LIVE and FRAME-ROUTED, driven by a real click
;; ===========================================================================

(deftest w2-tab-click-rerenders-the-boundary-on-its-own-frame
  (testing "rf2-k97c.3 — pressing a real tab button dispatches through
            the dispatcher the BOUNDARY captured
            (`(:dispatch (rf/capture-frame))`), the boundary re-runs on
            the resulting `:rf.xray/settings-active-tab` change, and the
            committed DOM moves. Epic criterion 3 (observation) and the
            frame half of criterion 2.

            THE CLICK IS THE POINT. A `dispatch-sync` from the test would
            exercise the read path while BYPASSING the captured
            dispatcher, which is the half a wrong frame would break.

            THE DEAF LEVER IS THE CONTROL. The same event is then
            dispatched into a second live frame; if the popup moved on
            that, its reads would not be frame-routed at all and the
            positive half above would be worth nothing."
    (if-not (browser?)
      (is true "skipped: no DOM under the :node-test build")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount-modal! modal-frame settings-popup/Modal)]
          (rf/dispatch-sync [:rf.xray/settings-open] {:frame modal-frame})
          (-> (poll-until #(testid container "rf-xray-settings-body"))
              (.then
                (fn [_]
                  (is (= "rf-xray-settings-tabpanel-general"
                         (active-tabpanel-id container))
                      (str "baseline: the popup opens on the General tab"
                           (uncaught-note)))
                  ;; A REAL CLICK, on the real committed button. React's
                  ;; delegated listener turns it into the boundary's own
                  ;; `:on-click`, which calls the captured dispatcher.
                  (.click (testid container "rf-xray-settings-tab-buffer"))
                  (poll-until
                    #(= "rf-xray-settings-tabpanel-buffer"
                        (active-tabpanel-id container)))))
              (.then
                (fn [switched?]
                  (is switched?
                      (str "the click re-rendered the boundary and the "
                           "committed tabpanel became Buffer"
                           (uncaught-note)))
                  (is (some? (testid container "rf-xray-settings-section-buffer"))
                      "the Buffer section is the committed body")
                  (is (nil? (testid container "rf-xray-settings-section-general"))
                      "the General section is gone — a real swap, not an
                       accumulation")
                  ;; ---- the deaf lever -------------------------------
                  (rf/dispatch-sync [:rf.xray/settings-select-tab :diff]
                                    {:frame other-frame})
                  (settle)))
              (.then
                (fn [_]
                  (is (= "rf-xray-settings-tabpanel-buffer"
                         (active-tabpanel-id container))
                      "CONTROL: the same event on a SECOND live frame moved
                       nothing here, so the boundary's reads resolve
                       through its own frame rather than ambiently")
                  (is (nil? (testid container "rf-xray-settings-section-diff"))
                      "CONTROL: the Diff section did not appear")
                  ;; And the lever is not simply broken: the SAME event on
                  ;; THIS frame does move the popup. Without this, a deaf
                  ;; lever and a dead event are the same observation.
                  (rf/dispatch-sync [:rf.xray/settings-select-tab :diff]
                                    {:frame modal-frame})
                  (poll-until
                    #(= "rf-xray-settings-tabpanel-diff"
                        (active-tabpanel-id container)))))
              (.then
                (fn [moved?]
                  (is moved?
                      (str "COUNTER-CONTROL: the identical event on the "
                           "popup's OWN frame does move it, so the deaf "
                           "lever above measured routing rather than a "
                           "dead event" (uncaught-note)))
                  (teardown! root container)
                  (done)))))))))

;; ===========================================================================
;; W3 — the editor-hint toast paints through its own bridge
;; ===========================================================================

(deftest w3-editor-hint-toast-paints-through-the-bridge
  (testing "rf2-k97c.3 — `settings.editor-hint/Hint` is the slice's second
            boundary. Its gate is the ONE read on this surface that did
            not move out of a helper, because `toast-view` was already
            pure, so this row is the whole of that boundary's evidence.

            `:rf.xray/editor-hint-open?` is `(get db :editor-hint-open?
            false)` — a plain app-db read with a literal fallback — so
            unlike most of the settings queries it genuinely traverses
            the frame, and the closed/open transition below is a frame
            claim as well as a paint claim."
    (if-not (browser?)
      (is true "skipped: no DOM under the :node-test build")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount-modal! modal-frame editor-hint/Toast)]
          (-> (settle)
              (.then
                (fn [_]
                  (is (nil? (testid container "rf-xray-editor-hint-toast"))
                      "closed: the boundary commits no toast")
                  (rf/dispatch-sync [:rf.xray/editor-hint-show] {:frame modal-frame})
                  (poll-until #(testid container "rf-xray-editor-hint-toast"))))
              (.then
                (fn [toast]
                  (is (some? toast)
                      (str "open: the toast is committed to the DOM"
                           (uncaught-note)))
                  (is (some? (testid container "rf-xray-editor-hint-open-settings"))
                      "the 'Open Settings' button is committed")
                  ;; The deaf lever again, cheaply: dismissing on ANOTHER
                  ;; frame must leave this toast standing.
                  (rf/dispatch-sync [:rf.xray/editor-hint-dismiss] {:frame other-frame})
                  (settle)))
              (.then
                (fn [_]
                  (is (some? (testid container "rf-xray-editor-hint-toast"))
                      "CONTROL: a dismiss on a SECOND live frame did not
                       close this toast")
                  (rf/dispatch-sync [:rf.xray/editor-hint-dismiss] {:frame modal-frame})
                  (poll-until #(nil? (testid container "rf-xray-editor-hint-toast")))))
              (.then
                (fn [_]
                  (is (nil? (testid container "rf-xray-editor-hint-toast"))
                      (str "COUNTER-CONTROL: the identical dismiss on the "
                           "toast's OWN frame does close it"
                           (uncaught-note)))
                  (teardown! root container)
                  (done)))))))))
