(ns re-frame.freehand.ownership-maps-dom-cljs-test
  "FH-BEHAVIOR-010 — ownership routing for an imperative SDK that owns its
  OWN DOM subtree, its own render loop, and a pane it creates for you.

  The Google Maps JavaScript API is the shape this file exists for, and it
  is the hardest of the three third-party ownership shapes rf2-drpa3.182.9
  routes, because the library owns three things a view substrate normally
  owns:

    THE NODE.     `new google.maps.Map(node, opts)` takes the node over.
                  Everything below it is the library's, built and rebuilt on
                  the library's own schedule, and React must never touch it
                  again. So the node is a LEAF as far as Freehand is
                  concerned, and the decoration that hands it over is a
                  registered behavior — the one sanctioned imperative
                  boundary (Spec 004 §Registered behaviors and commands).

    THE UPDATE.   `map.setOptions(…)` mutates and returns nothing. That is
                  the ordinary JS shape the memory law (rf2-wj1ao) exists
                  for: `:update`'s return value is DISCARDED, so a recipe
                  that returned the mutator's answer would erase the
                  instance `:disconnect` has to release. The memory is a
                  MUTABLE CELL established once by `:connect`.

    THE PANE.     An `OverlayView`'s `onAdd` hands the author a node the
                  LIBRARY created, inside a pane the library owns. There is
                  no portal in Freehand and there is not going to be one
                  (Spec 004 §No neutral portal), so a Freehand view in that
                  pane is an EXPLICIT NESTED ROOT — `v/mount` into the node
                  the host gave you, `v/unmount!` when it takes it back. It
                  is an island: it does not inherit the outer tree's React
                  context, its Suspense, or its event bubbling, and nothing
                  here pretends it does.

  ## The law, and why it is about reclamation

  Two of those three are ordinary. The one that decides whether an
  integration leaks is that reclamation can be INITIATED FROM EITHER SIDE.
  The author's `:disconnect` runs when the view goes away. The library's own
  `onRemove` runs when the map, a control, or the user takes the overlay
  away — and it can run first, or second, or twice.

  Two initiators over one set of resources is the defect shape. The routing
  that answers it is ONE reclamation path, fenced on its own terminal phase,
  entered from both sides:

      (defn- reclaim! [cell why] …)      ; one path
      :disconnect  (fn [{:keys [memory]}] (reclaim! memory :disconnect))
      :on-remove   (fn [] (reclaim! cell :host-removal))

  Every case below drives that path from a different direction and asserts
  the same absence: zero maps, zero listeners, zero overlays, zero nested
  Freehand roots, and — separately, because it is a different clock — an
  already-empty substrate connection table.

  ## The deferral gap, measured rather than trusted

  Spec 004 §Commit-only connection and total cleanup states the one place
  those two clocks disagree:

    \"React will not unmount a nested root from inside a render, so a
     behavior that opened one defers the unmount and its second tree is
     still open at the moment the substrate's own absence is already true.\"

  So the reclamation path defers `v/unmount!` to a TASK boundary, and
  [[the-deferred-release-is-one-task-behind-the-substrates-absence]] reads
  both clocks across it. That matters to an author writing a leak check: a
  check that reads its own host resources at the instant the substrate's
  books go empty reads them one task too early and reports a leak that is
  not one.

  ## What runs here, and what does not

  `google.maps` is NOT executed. It loads from the network under an API key
  against a billed account; a CI lane cannot do that, and a proof that
  pretended otherwise would be measuring a network rather than a contract.
  What runs is a surrogate reproducing the API's OWNERSHIP SHAPE and nothing
  else — node takeover, a void-returning `set-options!`, a listener handle
  released by `.remove()`, an `OverlayView` supplying a host-created pane
  and calling back on removal, and a book of what the library still holds.
  Every name below is the Maps name so an author can map the recipe across
  one to one.

  That is the same discipline `behavior-async-dom-cljs-test` states for its
  Promise-acquired surrogate, and for the same reason: a real library would
  make this a measurement of that library's scheduling. The boundary is
  recorded in the fixture's `:evidence :limits` as well as here, so a reader
  of the roster meets it without opening this file. NO npm dependency was
  added for this row.

  This file rides the browser lane through its `-dom-cljs-test` suffix. It
  also matches the node suites' broader regex, where there is no DOM to
  mount and it says so rather than passing quietly."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.behaviors :as behaviors]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.mount-support :as ms]
            [re-frame.freehand.root :as root]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(def behavior-010 (conf/fixture :FH-BEHAVIOR-010))

(def ^:private fid :dom/ownership-maps)

;; ===========================================================================
;; The surrogate SDK — `google.maps`, in the only part that is about ownership
;; ===========================================================================
;;
;; Deliberately small, deliberately deterministic, and named after the API it
;; stands in for. Each behaviour below is here because the real library has it:
;;
;;   - a Map CONSTRUCTS OVER a caller's node and fills it with its own DOM;
;;   - `set-options!` MUTATES and returns nothing;
;;   - a listener is a HANDLE, released by `.remove()` and not by forgetting it;
;;   - an OverlayView's `on-add` supplies a node the LIBRARY created;
;;   - the library CALLS BACK on removal, and that callback is a second
;;     initiator of reclamation the author does not control;
;;   - and the library knows what it still holds, which is what makes "nothing
;;     survived" an assertion rather than a hope.
;;
;; What is NOT modelled, on purpose: the network, the API key, tiles, and
;; asynchronous `onAdd`. `onAdd` fires synchronously at attach time here so
;; every case is one deterministic sequence; the real one fires on the map's
;; own schedule, which changes WHEN the nested root is mounted and none of
;; the ownership facts this row states.

(def ^:private live-maps
  "Map id -> the node the library took over. The library's own book."
  (atom {}))

(def ^:private live-listeners
  "Listener id -> the map it is attached to."
  (atom {}))

(def ^:private live-overlays
  "Overlay id -> the pane node the library created for it."
  (atom {}))

(def ^:private next-id (atom 0))

(defn- fresh-id! [] (swap! next-id inc))

(defn- create-map!
  "`new google.maps.Map(node, opts)`. Takes `node` over: the library appends
  its own subtree and treats everything below as its own from here."
  [node opts]
  (let [id     (fresh-id!)
        own    (.createElement js/document "div")
        centre (.createElement js/document "div")]
    (set! (.-className own) "gm-style")
    ;; The centre readout is its OWN node rather than the container's text,
    ;; because the panes the library creates hang off `own` too — reading the
    ;; container's `textContent` would read the author's overlay back as if it
    ;; were the library's state.
    (set! (.-className centre) "gm-center")
    (set! (.-textContent centre) (str (:center opts)))
    (.appendChild own centre)
    (.appendChild node own)
    (swap! live-maps assoc id node)
    {:id id :node node :own own :centre centre}))

(defn- set-options!
  "`map.setOptions(…)`. VOID — the shape the memory law exists for."
  [m opts]
  (when-not (contains? @live-maps (:id m))
    (throw (ex-info "setOptions on a destroyed map" {:id (:id m)})))
  (set! (.-textContent (:centre m)) (str (:center opts)))
  nil)

(defn- add-listener
  "`google.maps.event.addListener(map, ev, f)` — answers a HANDLE. Forgetting
  the handle leaks the listener; only `.remove()` releases it."
  [m _ev]
  (let [id (fresh-id!)]
    (swap! live-listeners assoc id (:id m))
    {:id id}))

(defn- remove-listener! [h] (swap! live-listeners dissoc (:id h)) nil)

(defn- add-overlay!
  "`overlay.setMap(map)`, whose `onAdd` supplies a pane node the LIBRARY
  created. `on-remove` is the host-removal callback: the library calls it
  when the overlay leaves, for any reason the library decides."
  [m {:keys [on-add on-remove]}]
  (let [id   (fresh-id!)
        pane (.createElement js/document "div")]
    (set! (.-className pane) "gm-pane")
    (.appendChild (:own m) pane)
    (swap! live-overlays assoc id pane)
    (let [ov {:id id :pane pane :on-remove on-remove}]
      (on-add pane)
      ov)))

(defn- detach-overlay!
  "`overlay.setMap(null)`. Releases the pane and calls the author back — so
  this is the LIBRARY's entry into reclamation. Idempotent on the library's
  side, exactly as a real `setMap(null)` on an already-detached overlay is a
  no-op rather than a fault."
  [ov]
  (when (contains? @live-overlays (:id ov))
    (swap! live-overlays dissoc (:id ov))
    (.remove (:pane ov))
    ((:on-remove ov)))
  nil)

(defn- destroy-map!
  "The teardown an author writes for a Map: detach whatever the library still
  holds — which calls back through [[detach-overlay!]] — drop the map's own
  subtree, and forget the instance."
  [m overlays]
  (doseq [ov overlays] (detach-overlay! ov))
  (when (contains? @live-maps (:id m))
    (swap! live-maps dissoc (:id m))
    (.remove (:own m)))
  nil)

(defn- reset-sdk! []
  (reset! live-maps {}) (reset! live-listeners {}) (reset! live-overlays {})
  (reset! next-id 0)
  nil)

;; ===========================================================================
;; THE RECIPE — one behavior, one cell, ONE reclamation path
;; ===========================================================================

(def ^:private entries
  "Every ENTRY into the reclamation path, in order, whether or not it did
  work. Paired below with [[worked]], and the difference between the two IS
  the idempotence claim."
  (atom []))

(def ^:private worked
  "The entries that actually released something. Exactly one per connection,
  whichever side got there first."
  (atom []))

(def ^:private deferrals
  "Every nested-root release the recipe deferred, as a thunk. Held rather
  than run so the suite can read both clocks across the task boundary — the
  release is posted by [[reclaim!]] with `setTimeout 0` in a real
  integration, and driven explicitly here so the gap is a measurement and
  not a race."
  (atom []))

(defn- run-deferrals!
  "Run every deferred release, in order, and answer a promise that settles
  one task later — so a caller reads the AFTER state on the right clock.

  Inside the `act` boundary, because unmounting a root is a React commit and
  a commit outside `act` is a warning on the page rather than a measurement."
  []
  (let [pending @deferrals]
    (reset! deferrals [])
    (-> (ms/act (fn [] (doseq [f pending] (f)) nil))
        (.then (fn [_] (ms/tick!))))))

(defn- reclaim!
  "The ONE reclamation path, entered from the host-removal callback and from
  `:disconnect` alike.

  FENCE FIRST. `:closed` is written before anything is released, so a
  re-entry — from the other initiator, or from the library calling back
  while this very call is tearing its overlay off — finds a terminal cell
  and returns. That is what makes either order and any repetition end in the
  same absence.

  The nested root's release is DEFERRED. React refuses to unmount a nested
  root from inside a render, and `:disconnect` runs from exactly there, so
  the release is posted to a task boundary. Spec 004 §Commit-only connection
  and total cleanup names this gap; the suite measures it."
  [cell why]
  (swap! entries conj why)
  (let [{:keys [phase map* listener overlay nested-root]} @cell]
    (when (not= :closed phase)
      (swap! cell assoc :phase :closed)
      (swap! worked conj why)
      (when nested-root (swap! deferrals conj #(v/unmount! nested-root)))
      (when listener (remove-listener! listener))
      (when map* (destroy-map! map* (when overlay [overlay])))))
  nil)

(v/defview overlay-legend
  "The view that lives in the pane the LIBRARY created — the nested root's
  own root view. It is an island: it reads its frame through the root's own
  `:frame` scope rather than through an outer React context it does not
  have."
  [_]
  [:div.legend "legend"])

(v/defbehavior maps-canvas
  "A Maps-shaped SDK, owned with the mechanism that ships."
  {:timing  :passive
   :connect
   (fn [{:keys [node config]}]
     ;; The memory is established HERE and never written again — everything
     ;; below moves this one cell in place (rf2-wj1ao).
     (let [cell (atom {:phase :live :map* nil :listener nil
                       :overlay nil :nested-root nil})
           m    (create-map! node config)]
       (swap! cell assoc
              :map*     m
              :listener (add-listener m "idle")
              :overlay  (add-overlay!
                          m {:on-add    (fn [pane]
                                          ;; The host made this node. An
                                          ;; explicit nested root is the only
                                          ;; sanctioned way to put a Freehand
                                          ;; view in it.
                                          (swap! cell assoc :nested-root
                                                 (v/mount [overlay-legend {}] pane
                                                          {:frame fid})))
                             :on-remove (fn [] (reclaim! cell :host-removal))}))
       cell))

   :update
   (fn [{:keys [config memory]}]
     ;; The return is DISCARDED, so this writes through the cell. `set-options!`
     ;; answers nothing, which is exactly why it may not be returned.
     (when (= :live (:phase @memory))
       (set-options! (:map* @memory) config)))

   :disconnect
   (fn [{:keys [memory]}] (reclaim! memory :disconnect))

   :commands
   {:reclaim (fn [{:keys [memory]}] (reclaim! memory :manual))
    :detach  (fn [{:keys [memory]}]
               ;; The LIBRARY's initiator, reached the way a real one is: the
               ;; overlay is taken away, and the author hears about it through
               ;; the removal callback rather than through this command.
               (detach-overlay! (:overlay @memory)))}})

;; ---------------------------------------------------------------------------
;; The application under mount
;; ---------------------------------------------------------------------------

(rf/reg-sub :maps/center (fn [db _] (:center db)))

(v/defview maps-page
  [_]
  (let [center (v/sub [:maps/center])]
    [:section.maps-page
     [v/behavior {:use    maps-canvas
                  :target :maps/main
                  :config {:center center}}
      [:div.map-node]]]))

;; ===========================================================================
;; Harness
;; ===========================================================================

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (root/reset-registry!)
                      ;; Bookkeeping only — it forgets what the previous case
                      ;; mounted and tears nothing down, which is what lets
                      ;; `ms/residue-clean!` report a retained root AFTER a
                      ;; case rather than a reset swallowing it before the next.
                      (ms/reset-ledger!))}))

(defn- setup! []
  (behaviors/reset-connections!)
  (reset-sdk!)
  (reset! entries []) (reset! worked []) (reset! deferrals [])
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid {:center "0,0"})
  (rf/reg-event :maps/center-changed (fn [{:keys [db]} [_ c]] {:db (assoc db :center c)}))
  (rf/reg-event :maps/command (fn [_ [_ cmd]] {:fx [[behaviors/command-fx-id cmd]]}))
  nil)

(defn- send! [ev] (rf/dispatch-sync ev {:frame fid}))

(defn- command! [op] (send! [:maps/command {:target :maps/main :op op}]))

(defn- owned
  "What the LIBRARY and the door still hold, as one comparable map — the
  shape `:connected` and `:after-reclamation` are written in."
  []
  {:maps      (count @live-maps)
   :listeners (count @live-listeners)
   :overlays  (count @live-overlays)
   ;; The DOOR's registry, which holds the page's own root AND the island in
   ;; the host's pane — two roots on one page, which is what an explicit
   ;; nested root is and what a portal would have hidden.
   :roots     (count (root/live-root-ids))})

(defn- substrate
  "The SUBSTRATE's two books. A different clock from [[owned]], which is the
  whole point of `:the-deferral-gap`."
  []
  {:connections (behaviors/connection-count)
   :targets     (vec (sort (behaviors/target-ids)))})

(defn- absence
  "The one map `:after-reclamation` is compared against — the library's books
  and the substrate's, read together once both clocks have settled."
  []
  (merge (owned) (substrate)))

(defn- released!
  "The terminal assertion of every case. Reads the same books through the
  shared residue assertion, which adds the one no per-suite teardown can
  make: that every ROOT this case created was torn down and left its
  container empty and detached (rf2-n9rzw)."
  [where]
  (ms/residue-clean! where
                     [["the library's live-map book"      #(deref live-maps)]
                      ["the library's listener book"      #(deref live-listeners)]
                      ["the library's overlay book"       #(deref live-overlays)]
                      ["the door's live-root registry"    #(root/live-root-ids)]
                      ["the connection table"             #(behaviors/connection-count)]
                      ["the live target index"            #(behaviors/target-ids)]]))

;; ===========================================================================
;; 1 — the route itself: a behavior owns the node, the update and the pane
;; ===========================================================================

(deftest a-behavior-owns-the-node-the-update-and-the-host-created-pane
  (testing "FH-BEHAVIOR-010, the routing before the reclamation. The SDK takes
            the behavior's node over and builds its own subtree there; the
            declared config drives `set-options!`, whose void return is
            discarded rather than written back over the memory; and the pane
            the library created in its `onAdd` carries an EXPLICIT nested
            Freehand root, which is a second root on the page rather than a
            portal Freehand does not have."
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the mounted ownership route")
      (async done
        (setup!)
        (let [container (ms/host-node!)]
          (-> (ms/act #(v/mount [maps-page {}] container {:frame fid}))
              (.then
                (fn [mounted]
                  (is (= (:connected behavior-010) (owned))
                      "one map over the behavior's node, one listener, one
                       overlay, and TWO roots in the door's registry — the
                       page's own, and the island in the pane the LIBRARY
                       created")
                  (is (some? (.querySelector container ".map-node .gm-style"))
                      "the SDK's own subtree is under the behavior's node, where
                       React will not touch it again")
                  (is (= "legend" (.-textContent (.querySelector container ".gm-pane .legend")))
                      "and the nested root really rendered into the host's pane")
                  (is (= "0,0" (.-textContent (.querySelector container ".gm-center")))
                      "the initial config reached the library")
                  (ms/act (fn [] (send! [:maps/center-changed "1,1"]) mounted))))
              (.then
                (fn [mounted]
                  (is (= "1,1" (.-textContent (.querySelector container ".gm-center")))
                      "a config movement drove `set-options!` — and the recipe
                       survived it, which a recipe that returned the void
                       mutator's answer would not have: the memory it needs to
                       release is still there")
                  (is (= 1 (:connections (substrate)))
                      "FH-BEHAVIOR-010 — and one connection is claiming the target,
                       so the absence asserted below is a release rather than a
                       counter nothing ever wrote")
                  (ms/act (fn [] (v/unmount! mounted) nil))))
              (.then (fn [_] (run-deferrals!)))
              (.then (fn [_]
                       (ms/remove-node! container)
                       (is (= (:after-reclamation behavior-010) (absence))
                           "FH-BEHAVIOR-010 — zero maps, zero listeners, zero
                            overlays, zero roots, and both substrate books empty")
                       (released! "FH-BEHAVIOR-010 — after the ordinary teardown")
                       (done)))
              (.catch (fn [e] (is false (str "case rejected: " e)) (done)))))))))

;; ===========================================================================
;; 2 — the reclamation orders, all three, through the ONE path
;; ===========================================================================
;;
;; Each row drives the same path from a different direction and asserts the
;; same absence. The `:entered` / `:did-work` pair is what makes it a claim
;; about IDEMPOTENCE rather than about cleanup: a path that released twice
;; would show two entries doing work, and the library's non-idempotent books
;; would notice.

(defn- run-order!
  "Mount, drive `initiate!` (which may be nil), unmount, settle both clocks,
  and answer a promise of the observed `{:entered :did-work}` pair."
  [initiate!]
  (let [container (ms/host-node!)]
    (-> (ms/act #(v/mount [maps-page {}] container {:frame fid}))
        (.then (fn [mounted]
                 (is (= (:connected behavior-010) (owned))
                     "connected before anything is reclaimed")
                 (ms/act (fn [] (when initiate! (initiate!)) mounted))))
        (.then (fn [mounted] (ms/act (fn [] (v/unmount! mounted) nil))))
        (.then (fn [_] (run-deferrals!)))
        (.then (fn [_]
                 (ms/remove-node! container)
                 {:entered (mapv identity @entries) :did-work (mapv identity @worked)})))))

(deftest one-idempotent-path-serves-both-initiators-in-either-order
  (testing "The defect shape an imperative SDK integration has: reclamation
            can be initiated by the AUTHOR (`:disconnect`, when the view goes
            away) or by the LIBRARY (its removal callback, when the map, a
            control or the user takes the overlay away) — and either can come
            first, second, or twice.

            Routing it through ONE path fenced on its own terminal phase is
            what makes all of those one outcome. Every row here ends in the
            SAME absence; what differs is only who got there first."
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the mounted ownership route")
      (async done
        (let [rows (:reclamation behavior-010)
              run  (fn [row initiate!]
                     (fn [_]
                       (setup!)
                       (-> (run-order! initiate!)
                           (.then (fn [observed]
                                    (is (= (:entered row) (:entered observed))
                                        (str "FH-BEHAVIOR-010 — " (:case row)
                                             ": every ENTRY into the one path, in order"))
                                    (is (= (:did-work row) (:did-work observed))
                                        (str "FH-BEHAVIOR-010 — " (:case row)
                                             ": and exactly one of them released anything"))
                                    (is (= (:after-reclamation behavior-010) (absence))
                                        (str "FH-BEHAVIOR-010 — " (:case row)
                                             ": the same absence, whichever side got there first"))
                                    (released! (str "FH-BEHAVIOR-010 — " (:case row))))))))]
          (-> (js/Promise.resolve nil)
              ;; row 0 — the author initiates; the library's own teardown
              ;; re-enters through the removal callback.
              (.then (run (nth rows 0) nil))
              ;; row 1 — the HOST removes the overlay first.
              (.then (run (nth rows 1) #(command! :detach)))
              ;; row 2 — the path entered twice by hand before teardown.
              (.then (run (nth rows 2) #(do (command! :reclaim) (command! :reclaim))))
              ;; The rejection handler sits UPSTREAM of the single trailing
              ;; `done` (rf2-qpns): `done` runs the whole remainder of the run
              ;; synchronously, so a `.catch` after it claims a foreign throw
              ;; as this row's and fires `done` a second time.
              (.catch (fn [e] (is false (str "an order rejected: " e)) nil))
              (.then (fn [_] (done)))))))))

;; ===========================================================================
;; 3 — the two clocks
;; ===========================================================================

(deftest the-deferred-release-is-one-task-behind-the-substrates-absence
  (testing "Spec 004 §Commit-only connection and total cleanup states that the
            substrate's absence is SYNCHRONOUS — the connection table is empty
            at the instant the last connection is released — while what
            `:disconnect` handed to a library is the AUTHOR's, and a host is
            entitled to refuse a synchronous release. React refuses exactly
            that for a nested root: it will not unmount one from inside a
            render, and `:disconnect` runs from inside one.

            So the recipe posts the nested `v/unmount!` to a task boundary,
            and the two clocks genuinely disagree for one task. That is
            measured here rather than trusted, because it is the one place an
            author's leak check can be written against the wrong clock: read
            your own host resources at the instant the substrate's books go
            empty and you will report a leak that is not one."
    (if-not (ms/browser?)
      (ms/skip! "the browser job owns the mounted ownership route")
      (async done
        (setup!)
        (let [container (ms/host-node!)
              gap       (:the-deferral-gap behavior-010)]
          (-> (ms/act #(v/mount [maps-page {}] container {:frame fid}))
              (.then (fn [mounted] (ms/act (fn [] (v/unmount! mounted) nil))))
              (.then (fn [_]
                       (is (= (:at-unmount gap)
                              (assoc (substrate) :roots (count (root/live-root-ids))))
                           "at unmount: the SUBSTRATE's books are already empty —
                            an exact zero, not a threshold — while the author's
                            nested root is still open")
                       (is (= 1 (count @deferrals))
                           "because its release was deferred rather than skipped")
                       (run-deferrals!)))
              (.then (fn [_]
                       (is (= (:one-task-later gap)
                              (assoc (substrate) :roots (count (root/live-root-ids))))
                           "one task later both clocks agree, and the gap is
                            closed by the AUTHOR's release rather than by the
                            substrate waiting for it")
                       (ms/remove-node! container)
                       (is (= (:after-reclamation behavior-010) (absence))
                           "FH-BEHAVIOR-010 — and the same absence as every other route,
                            reached one task later than the substrate's")
                       (released! "FH-BEHAVIOR-010 — after the deferred release")
                       (done)))
              (.catch (fn [e] (is false (str "case rejected: " e)) (done)))))))))
