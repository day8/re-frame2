(ns re-frame.hicasso.server-render-ssr-dom-cljs-test
  "THE SERVER-RENDER ENTRY, AND THE OBSTRUCTION IT CLEARS (rf2-b6jkj).

  `re-frame.hicasso.server/render` is `dispositions.md` HS-11's first
  candidate repair — *a matching server-render entry of this arm's own*
  — and this file is what measures that it IS one.

  ## The claim, and the row that would catch it being false

  `identifier_prefix_ssr_dom_cljs_test` established the obstruction on
  the server side alone, with no DOM in sight: one prefix, two tree
  SHAPES, two ids. Its `server-html!` is *the tree a consumer can spell
  today* — the frame provider over the root element — and its
  `root-shaped-server-html!` reproduces `impl.mount/tree`'s hydrating
  shape BY HAND as a measurement, explicitly *not offered as a product
  path*. The ids disagree, and that disagreement is the whole of
  obstruction 2.

  So the repair has exactly one thing to prove, and §1 proves it:
  **`server/render`'s bytes carry the SHAPED id, not the plain one.**
  Both directions are asserted, because either alone is a green that
  means nothing — agreeing with the shaped id while also agreeing with
  the plain one would say the shapes had stopped differing rather than
  that this entry picked the right one, and §1 pins that premise first.

  A witness that only hydrated and looked for silence would not do this
  job. **The closer renders no DOM**, so the markup the two shapes
  produce is byte-identical everywhere except inside a `useId`, which is
  precisely why the package shipped this bug with every markup-reading
  row green.

  ## Why the entry does not reproduce the shape, and why that is testable

  It calls [[re-frame.hicasso.impl.mount/tree]] — the same function
  `hydrate-root!` calls. §1's last row is the standing guard on that:
  it renders the entry's element and the door's element and compares the
  ids. Were the entry ever to grow its own copy of the fork, that row
  reds the moment the two drift, which a row comparing the entry against
  a hand-written expectation could not do.

  ## The runtime split

  Rows that only render to a string need no DOM and run under
  `:node-test` too. The hydration rows need a real React DOM and say so
  with `sup/skip!` rather than degrading to a false green.

  ## §5 measures the OTHER half of the round trip (rf2-lb1xi)

  §4 proves the bytes ADOPT. It says nothing about the state those bytes
  were rendered from ever reaching the client, because it seeds the frame
  by hand — and chapter 18 shipped a recipe that seeded nothing at all.
  §5 drives the documented sequence end to end instead: the render's own
  `__rf_payload` script goes into the document, `re-frame.ssr/hydrate!`
  reads it, and only then does the public DOM door adopt.

  Its control is the recipe as shipped. An absent client frame makes the
  seed a NO-OP rather than an error — the router drops a dispatch into a
  frame that is not there and reports it on its own axis — so
  `ssr/hydrate!` still answers a payload while installing nothing, which
  is precisely why the defect shipped green. The control asserts that
  shape directly, so §5's own green is a claim about the frame existing
  and not about `hydrate!` being called."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.roots :as roots]
            [re-frame.hicasso.native :as n]
            [re-frame.hicasso.roots-frames-support :as sup]
            [re-frame.hicasso.server :as server]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.constants :as ssr-constants]
            [re-frame.test-support :as test-support]
            ["react" :as react]
            ["react-dom/server" :as react-dom-server]))

(def ^:private wire-frame ::app-main)

;; Registered ABOVE `use-fixtures` for the sibling suites' reason: the
;; reset fixture captures its source-store baseline when the
;; `use-fixtures` form is EVALUATED, so a registration written below it
;; is erased before the first row runs.

(rf/reg-sub ::label (fn [db _] (:label db)))
(rf/reg-sub ::secret (fn [db _] (:secret db)))
(rf/reg-event ::relabel (fn [{:keys [db]} [_ label]] {:db (assoc db :label label)}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (collector/reset-runtime!))}))

;; ---------------------------------------------------------------------------
;; The probe — a `useId` that reaches the server bytes
;; ---------------------------------------------------------------------------
;;
;; `useId` is a React hook, so it is written where React hooks are
;; written: a native island, declared `{:server :render}` under a
;; `{:server :render}` host — the only spelling that puts a native body
;; into a server response. The island is the vehicle and never the
;; subject: what is under test is the TREE the entry renders, and the
;; island is the smallest legal thing that makes React's answer to it
;; visible.

(n/defcomponent id-probe
  "One `useId`, rendered as text, beside a prop that changes."
  {:server :render}
  [^js props]
  (n/$ :span #js {"className" "island"}
       (n/$ :b #js {"className" "probe"} (react/useId))
       (n/$ :i #js {"className" "label"} (.-label props))))

(h/defhost id-host id-probe {:server :render})

(h/defview id-page
  "The page both sides render. One subscription read, so the root
  acquires a frame-keyed cell and its commit is observable at all; one
  island, so the page has an id in it."
  [_]
  [:div.page
   [:p.value (h/sub [::label])]
   [id-host {:label (h/sub [::label])}]])

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(def ^:private snapshot {:label "alpha" :secret "do-not-ship"})

(defn- request
  "One request's options, with `extra` merged over the defaults. The
  allowlist is `[:label]` — `:secret` is in the snapshot and must not be
  on the wire, which is what makes the payload rows non-vacuous."
  [& {:as extra}]
  (merge {:hiccup            [id-page {}]
          :snapshot          snapshot
          :payload           [:label]
          :client-frame-id   wire-frame
          :identifier-prefix "pfx-a-"}
         extra))

(defn- id-in-html
  "The id inside `html`, read straight off the bytes.

  A string match rather than a parse, because these rows run under
  `:node-test` too and there is no `document` there. It matches
  [[id-probe]]'s OWN markup, so recognising it is reading back what this
  file wrote; it assumes nothing about the id's SHAPE — the capture is
  `.*?` and every assertion compares ids to each other."
  [html]
  (second (re-find #"<b class=\"probe\">(.*?)</b>" html)))

(defn- plain-server-html!
  "*The tree a consumer can spell today* — the frame provider over the
  root element, handed to `renderToString`. `identifier_prefix_ssr_dom`'s
  `server-html!`, re-spelled here so this file's premise row does not
  reach into another suite's private."
  [frame-kw hiccup prefix]
  (react-dom-server/renderToString
    (mount/provider frame-kw (codec/root-element frame-kw hiccup))
    #js {"identifierPrefix" prefix}))

(defn- door-shaped-html!
  "The bytes for the element the PRODUCT DOOR would hydrate — built by
  calling `impl.mount/tree` with a hydrating handle, which is what
  `hydrate-root!` does with the handle it mints.

  This is the cross-check for §1's last row, and it is deliberately not
  a copy of the fork: if `tree` changes, this moves with it and so does
  the entry, which is the property being asserted."
  [frame-kw hiccup prefix]
  (react-dom-server/renderToString
    (mount/tree {:frame frame-kw :adoption (roots/open-adoption-window!)} hiccup)
    #js {"identifierPrefix" prefix}))

;; ---------------------------------------------------------------------------
;; 1 — THE REPAIR: the entry emits the tree the door adopts
;; ---------------------------------------------------------------------------

(deftest the-entry-emits-the-hydrating-shape-and-not-the-plain-one
  (testing "the premise first: the two shapes still disagree. Every row
            below is about which one the entry picked, and all of them
            would go vacuously green if the shapes had merely stopped
            differing"
    (let [plain (id-in-html (plain-server-html! ::probe-frame [id-page {}] "pfx-a-"))
          door  (id-in-html (door-shaped-html!  ::probe-frame [id-page {}] "pfx-a-"))]
      (is (some? plain) "premise: the island is IN the bytes, so there is an id to read")
      (is (some? door))
      (is (not= plain door)
          (str "premise: the hydrating shape's fork must still move the id — "
               "if these agree, obstruction 2 has some other cause and this "
               "whole file is measuring nothing; got " (pr-str plain) " both times"))

      (testing "and the entry's bytes carry the DOOR's id"
        (let [entry (id-in-html (:html (server/render (request))))]
          (is (some? entry))
          (is (= door entry)
              (str "server/render must emit the tree hydrate-root! adopts; got "
                   (pr-str entry) " against the door's " (pr-str door)))
          (is (not= plain entry)
              (str "and it must NOT emit the plain provider-over-root tree — "
                   "that is the shape whose bytes mismatch, and the one this "
                   "entry exists to replace")))))))

(deftest the-entry-honours-the-identifier-prefix
  (testing "`:identifier-prefix` reaches React's own option — the half a
            hydrating root must be handed the same string for"
    (let [a (id-in-html (:html (server/render (request :identifier-prefix "pfx-a-"))))
          b (id-in-html (:html (server/render (request :identifier-prefix "pfx-b-"))))
          n (id-in-html (:html (server/render (request :identifier-prefix nil))))]
      (is (str/includes? a "pfx-a-") (str "got " (pr-str a)))
      (is (str/includes? b "pfx-b-") (str "got " (pr-str b)))
      (is (not= a b) "two prefixes, two ids")
      (is (some? n) "naming no prefix is not an error")
      (is (not (str/includes? n "pfx-a-")) "an unprefixed render carries no prefix"))))

;; ---------------------------------------------------------------------------
;; 2 — the request: isolation, determinism, and the frame that must not leak
;; ---------------------------------------------------------------------------

(deftest two-renders-of-one-request-are-the-same-bytes
  (testing "same bundle + same snapshot ⇒ byte-identical document, and the
            per-request gensym is INVISIBLE on the wire — the two renders
            take different ids, so a document that mentioned one could not
            be byte-identical to the other"
    (let [{:keys [identical? differs-at first second]} (server/render-twice (request))]
      (is identical?
          (str "two renders of one request differed at character " differs-at))
      (is (not= (:frame-id first) (:frame-id second))
          "premise: the two renders really did take different frame ids, so
           the comparison above is a proof about the gensym and not an
           accident of it being reused")
      (is (not (str/includes? (:document first) (name (:frame-id first))))
          "the per-request id must not appear in the document"))))

(deftest the-request-frame-is-destroyed-even-when-the-render-throws
  (testing "the happy path leaves no frame behind"
    ;; `app-db-value` answers nil for a frame that is gone AND for one that
    ;; never existed, so the nil below is only evidence beside a control
    ;; that a LIVE frame answers something. Without it this row would stay
    ;; green under a `render` that never made a frame at all.
    (let [{:keys [frame-id]} (server/render (request))]
      (rf/make-frame {:id ::control :initial-events [[:rf/set-db snapshot]]})
      (is (some? (rf/app-db-value ::control))
          "control: a live frame answers its app-db, so nil below means gone")
      (is (nil? (rf/app-db-value frame-id))
          "the per-request frame must be gone by the time render returns")
      (rf/destroy-frame! ::control)))

  (testing "and so does a render that threw — the `finally` is the whole
            claim, since a leaked frame per failed request is a leak per
            request under any real load"
    (let [seen (atom nil)]
      (is (thrown? :default
                   (server/render (request :hiccup [(fn [] (throw (js/Error. "boom")))]))))
      ;; The id is not observable from outside a successful render, so the
      ;; standing proof is that the NEXT request still renders cleanly: a
      ;; leaked frame would not stop it, but a runtime left mid-render
      ;; would.
      (reset! seen (:html (server/render (request))))
      (is (str/includes? @seen "alpha")
          "a request after a failed one still renders"))))

;; ---------------------------------------------------------------------------
;; 3 — the payload path is the framework's, fail-closed
;; ---------------------------------------------------------------------------

(deftest the-payload-is-fail-closed-and-allowlisted
  (testing "omitting `:payload` raises the framework's own refusal — this
            module adds no check, it hands the value straight to
            `payload-policy`, and this row is what says so"
    (is (thrown? :default (server/render (dissoc (request) :payload)))))

  (testing "an allowlisted key rides and an un-allowlisted one does not.
            `:secret` is in the snapshot the page rendered FROM, so a
            green here is the allowlist working rather than the key being
            absent"
    (let [{:keys [payload document]} (server/render (request))
          db (:rf/app-db payload)]
      (is (contains? db :label) (str "the allowlisted key must ride; got " (pr-str db)))
      (is (not (contains? db :secret)) (str "and the other must not; got " (pr-str db)))
      (is (not (str/includes? document "do-not-ship"))
          "and its value must not reach the document by any other route")))

  (testing "the wire frame id is the CALLER's stable one and never the
            per-request gensym (rf2-lm2yzy: stamping the gensym
            guarantees :rf.error/hydration-frame-id-mismatch on every
            real page)"
    (let [{:keys [payload frame-id]} (server/render (request))]
      (is (= wire-frame (:rf/frame-id payload)))
      (is (not= frame-id (:rf/frame-id payload)))))

  (testing "and omitting `:client-frame-id` omits the key — the
            anonymous-server-frame shape, not a nil stamped on the wire"
    (let [{:keys [payload]} (server/render (dissoc (request) :client-frame-id))]
      (is (not (contains? payload :rf/frame-id))))))

(deftest the-document-carries-the-pinned-payload-script
  (let [{:keys [document payload-edn]}
        (server/render (request :app-element-id "app" :script-src "/js/app.js" :title "The feed"))]
    (is (str/includes? document (str "id=\"" ssr-constants/payload-script-id "\""))
        "the script id is the framework CONSTANT the client bootstrap reads")
    (is (str/includes? document "<div id=\"app\">"))
    (is (str/includes? document "<script src=\"/js/app.js\"></script>"))
    (is (str/includes? document "<title>The feed</title>"))
    (is (some? payload-edn))
    (testing "the payload script follows the app root's close and the
              bootstrap is last — `ssr-ring`'s own order"
      (is (< (.indexOf document "</div>")
             (.indexOf document (str "id=\"" ssr-constants/payload-script-id "\""))
             (.indexOf document "/js/app.js"))))))

;; ---------------------------------------------------------------------------
;; 4 — the far side: the bytes hydrate through the PUBLIC door
;; ---------------------------------------------------------------------------

(deftest the-entry-s-bytes-hydrate-through-h-hydrate-without-a-mismatch
  (if-not (mount/browser?)
    (sup/skip! "adoption is React's own DOM business")
    (async done
      (let [{:keys [html]} (server/render (request))
            container      (sup/stamp-server-nodes! (sup/server-dom! html))
            watch          (sup/watch-mismatches!)
            handle         (h/hydrate! container
                                       {:frame wire-frame :identifier-prefix "pfx-a-"}
                                       [id-page {}])]
        (rf/make-frame {:id wire-frame :initial-events [[:rf/set-db snapshot]]})
        (-> (sup/adopted! handle)
            (.then (fn [shut?]
                     (is shut? "the root's own adoption window shut")
                     (testing "the page is the SERVER's nodes — an expando
                               survives adoption and does not survive a
                               replacement, which is the entire difference
                               between adopted and re-rendered"
                       (is (sup/every-server-node? container ".page")))
                     (testing "and the framework reported no hydration mismatch"
                       (is (= [] ((:stop! watch)))))
                     (testing "the handle is the one every other door takes"
                       (is (some? (:root handle)))
                       (is (= wire-frame (:frame handle)))
                       (is (nil? (h/unmount! handle))))
                     (done)))
            (.catch (fn [e] (is false (str "hydration row threw: " e)) (done))))))))

;; ---------------------------------------------------------------------------
;; 5 — THE DOCUMENTED ROUND TRIP: payload script → state → DOM (rf2-lb1xi)
;; ---------------------------------------------------------------------------

(defn- plant-payload-script!
  "Put a render's OWN `<script id=\"__rf_payload\">` into the document —
  the element `ssr/hydrate!`'s DOM read looks up by id. Answers a 0-arity
  remover.

  `innerHTML` does not EXECUTE an injected script and nothing here needs
  it to: the payload rides as `type=\"application/edn\"` and is read back
  as text. Using the render's own `:payload-script` rather than a
  hand-built element is the point — a fixture spelling the tag itself
  would keep passing after the emitter's id or escaping drifted."
  [payload-script-html]
  (let [host (js/document.createElement "div")]
    (set! (.-innerHTML host) payload-script-html)
    (.appendChild js/document.body host)
    (fn [] (.remove host))))

(deftest an-absent-client-frame-swallows-the-seed
  ;; THE CONTROL, and it is the recipe chapter 18 shipped (rf2-lb1xi).
  ;; Every assertion in the round-trip row below is about the frame having
  ;; been CREATED; without this row a green there would be equally
  ;; consistent with `ssr/hydrate!` seeding a frame it made itself.
  (if-not (mount/browser?)
    (sup/skip! "the payload script is read off a real document")
    (let [{:keys [payload payload-script]} (server/render (request))
          remove!                          (plant-payload-script! payload-script)]
      (try
        (is (nil? (rf/app-db-value wire-frame))
            "premise: no client frame exists yet — this is a cold page")
        (let [applied (ssr/hydrate! {:frame wire-frame})]
          (testing "the false comfort: the read succeeded and the call
                    answers the payload, so a boot that checks only this
                    return value sees a hydration that worked"
            (is (some? applied))
            (is (= (:rf/app-db payload) (:rf/app-db applied))))
          (testing "and nothing was installed — dispatching into an absent
                    frame is a no-op, not a throw"
            (is (nil? (rf/app-db-value wire-frame)))))
        (testing "nor does a frame created afterwards inherit the seed"
          (rf/make-frame {:id wire-frame :platform :client})
          (is (nil? (:label (rf/app-db-value wire-frame)))))
        (finally (remove!))))))

(deftest the-payload-script-seeds-the-frame-the-server-dom-then-adopts
  (if-not (mount/browser?)
    (sup/skip! "adoption is React's own DOM business")
    (async done
      (let [{:keys [html payload-script]} (server/render (request))
            remove!   (plant-payload-script! payload-script)
            container (sup/stamp-server-nodes! (sup/server-dom! html))
            watch     (sup/watch-mismatches!)]
        (rf/make-frame {:id wire-frame :platform :client})
        (is (nil? (:label (rf/app-db-value wire-frame)))
            "premise: the frame starts EMPTY, so every value below arrived
             over the wire rather than having been put there by hand")

        (let [applied (ssr/hydrate! {:frame wire-frame})
              db      (rf/app-db-value wire-frame)]
          (testing "state first, and off the document's own payload script"
            (is (some? applied))
            (is (= "alpha" (:label db))))
          (testing "and only the allowlisted slice — `:secret` was in the
                    snapshot the server rendered from, so its absence here
                    is the egress projection working"
            (is (not (contains? db :secret)))))

        (let [handle (h/hydrate! container
                                 {:frame wire-frame :identifier-prefix "pfx-a-"}
                                 [id-page {}])]
          (-> (sup/adopted! handle)
              (.then (fn [shut?]
                       (is shut? "the root's own adoption window shut")
                       (testing "the first client render saw the server's
                                 snapshot: the server's nodes are still the
                                 page's nodes, and they still carry the
                                 server's value"
                         (is (sup/every-server-node? container ".page"))
                         (is (= "alpha"
                                (.-textContent (.querySelector container ".value")))))
                       (testing "and the framework reported no mismatch — the
                                 whole claim, since a frame seeded after the
                                 adopt would have diverged on this text"
                         (is (= [] ((:stop! watch)))))
                       (h/unmount! handle)
                       (remove!)
                       (done)))
              (.catch (fn [e]
                        (is false (str "round-trip row threw: " e))
                        (remove!)
                        (done)))))))))
