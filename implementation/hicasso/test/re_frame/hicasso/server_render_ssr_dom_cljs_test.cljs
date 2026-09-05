(ns re-frame.hicasso.server-render-ssr-dom-cljs-test
  "THE SERVER-RENDER ENTRY, AND THE OBSTRUCTION IT CLEARS.

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

  ## §5 measures the OTHER half of the round trip

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
  and not about `hydrate!` being called.

  ## §3b is about none of that

  Every row from §4 on is asynchronous and holds a live React root, a
  trace listener and sometimes a replaced `console.error` — none of
  which the `:each` fixture takes back. §3b is the single settlement
  path they all end with and the two controls that exercise its failure
  branches, which no green path reaches. It measures no claim about
  `server/render`; it is what stops one row's failure from being
  reported against the next."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.mount :as mount]
            [re-frame.hicasso.impl.roots :as roots]
            [re-frame.hicasso.roots-frames-support :as sup]
            [re-frame.hicasso.server :as server]
            [re-frame.hicasso.test.server :as ts]
            [re-frame.interop :as interop]
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

;; rf2-323z — the platform PAIR. Two effects that differ in exactly one
;; thing, their `:platforms` metadata, dispatched together from one event,
;; so which of them runs READS OFF the active platform of whatever frame
;; drained the event. Nothing else in the pair can decide the answer, which
;; is what lets the untagged control below be a control.
(def ^:private platform-spy (atom []))

(rf/reg-fx ::client-only-spy
  {:platforms #{:client}}
  (fn [_ _] (swap! platform-spy conj :client)))

(rf/reg-fx ::server-only-spy
  {:platforms #{:server}}
  (fn [_ _] (swap! platform-spy conj :server)))

(rf/reg-event ::fire-both-platform-arms
  (fn [_ _] {:fx [[::client-only-spy {}] [::server-only-spy {}]]}))

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
;; written: a raw React component, mounted through a `{:server :render}`
;; host — the only policy that puts a foreign body into a server
;; response. The island is the vehicle and never the subject: what is
;; under test is the TREE the entry renders, and the island is the
;; smallest legal thing that makes React's answer to it visible.

(defn- id-probe
  "One `useId`, rendered as text, beside a prop that changes."
  [^js props]
  (react/createElement "span" #js {:className "island"}
    (react/createElement "b" #js {:className "probe"} (react/useId))
    (react/createElement "i" #js {:className "label"} (.-label props))))

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

(defn- plain-html-for-this-request!
  "The bytes a HAND-ROLLED route ships for the very request [[request]]
  describes — [[plain-server-html!]] driven from the same snapshot, at the
  same prefix, through a frame minted and destroyed for the purpose.

  Seeding matters: rendered against a frame that does not exist, the page
  answers a nil label and the bytes would then differ from the door's in
  the LABEL as well as in the id, which is a second difference §4b's
  control would silently be measuring."
  []
  (let [frame-kw ::plain-request]
    (try
      (rf/make-frame {:id frame-kw :initial-events [[:rf/set-db snapshot]]})
      (plain-server-html! frame-kw [id-page {}] "pfx-a-")
      (finally (rf/destroy-frame! frame-kw)))))

(defn- without-the-id
  "`html` with its probe id replaced by a fixed token — the two shapes'
  bytes with the one thing that may differ taken out of them.

  `[\\s\\S]*?` rather than a dot with a DOTALL flag: `re-pattern` builds a
  JavaScript `RegExp`, which has no inline `(?s)`."
  [html]
  (str/replace html #"(<b class=\"probe\">)[\s\S]*?(</b>)" "$1ID-ELIDED$2"))

;; ---------------------------------------------------------------------------
;; DOM reads — the far side of an adoption
;; ---------------------------------------------------------------------------

(defn- text-in [container sel]
  (some-> (.querySelector container sel) .-textContent))

(defn- probe-id
  "The id this container's probe is DISPLAYING — the client's answer once
  a render has run, not a server byte nobody touched."
  [container]
  (text-in container ".probe"))

(defn- relabel!
  "Dispatch into the wire frame and let the sync-lane notification commit,
  so the reading on the next line is taken after a REAL client render.

  The probe's `:label` prop moves with it, which is what stops React
  bailing out of the island and re-reading a body that never ran."
  [label]
  (rf/with-frame wire-frame (rf/dispatch-sync [::relabel label]))
  (mount/settle!)
  nil)

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
    (let [{:keys [identical? differs-at first second]} (ts/render-twice (request))]
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

(deftest a-hostile-value-cannot-break-out-of-the-envelope
  (testing "a `</script` inside an ALLOWLISTED app-db value is escaped in
            the payload script body — the EDN-aware escaper's whole job,
            and until this row nothing in the shipped tree exercised it:
            deleting the `escape-edn-script-body` call would have redded
            only the fenced bench donor's pin"
    (let [evil "</script><script>window.pwned=1</script>"
          {:keys [document payload]}
          (server/render (request :snapshot {:label evil :secret "x"}))]
      (is (= evil (get-in payload [:rf/app-db :label]))
          "the premise: the hostile value IS on the allowlisted wire —
           without this the absence below could be the allowlist's doing")
      (is (not (str/includes? document "</script><script>"))
          "the raw breakout sequence reaches no position in the document")
      (is (str/includes? document "\\u003c/script")
          "because inside the EDN string literal every < is the reader's
           six-character escape, which round-trips to the same value")))
  (testing "the document envelope's own text and attribute positions
            escape too — the title through `escape-html`"
    (let [{:keys [document]}
          (server/render (request :title "Bob's <Feed> & Friends"))]
      (is (str/includes? document "<title>Bob&#39;s &lt;Feed&gt; &amp; Friends</title>")
          (str "every reserved character in its HTML spelling: " document))
      (is (not (str/includes? document "<Feed>"))
          "and the raw angle pair reaches nothing")))
  (testing "`or`, not `:or` — a caller with one options map threads nil
            through for every option it did not set, so the KEY is
            supplied and destructuring defaults never fire; the module's
            own comment records the page silently getting id=\"\" when
            this regressed"
    (let [{:keys [document]}
          (server/render (request :title nil :app-element-id nil))]
      (is (str/includes? document "<div id=\"app\">")
          "the app element default survives an explicit nil")
      (is (str/includes? document "<title>Hicasso SSR</title>")
          "and so does the title default"))))

(deftest frame-opts-ride-under-the-modules-id-and-the-wire-keys-ride-the-payload
  (testing "`:frame-opts` merges UNDER the module's :id and
            :initial-events — a request needing :url-strategy or
            :fx-overrides declares them the way any frame does, and a
            copy-pasted :id or :initial-events in them cannot hijack the
            per-request frame or replace the seed. The invariant is one
            `assoc`, and nothing pinned it"
    (let [{:keys [document payload frame-id]}
          (server/render (request :frame-opts {:id             ::hijack
                                               :initial-events [[::relabel "hijacked"]]}))]
      (is (not= ::hijack frame-id)
          "the per-request gensym rules the frame id")
      (is (= wire-frame (:rf/frame-id payload))
          "and the wire id stays the caller's stable one")
      (is (str/includes? document "alpha")
          "the seed is the module's snapshot")
      (is (not (str/includes? document "hijacked"))
          "and the frame-opts' events vector never ran")))
  (testing "`:version` and `:schema-digest` ride the payload under the
            wire keys the hydrate side reads — the version COERCED to the
            schema's integer (Spec-Schemas pins `:rf/version` as `:int`,
            and a whole-number string is parsed rather than rejected)"
    (let [{:keys [payload]}
          (server/render (request :version "7" :schema-digest "digest-abc"))]
      (is (= 7 (:rf/version payload)))
      (is (= "digest-abc" (:rf/schema-digest payload))))
    (testing "an omitted `:version` still ships the slot — the SSR
              artefact's own protocol constant, so both wire ends agree
              with no host wiring; an `(or version …)` here once silently
              defeated the version-mismatch check"
      (let [{:keys [payload]} (server/render (request))]
        (is (pos-int? (:rf/version payload)))))
    (testing "while an omitted `:schema-digest` omits the KEY — the
              no-participation shape, not a nil stamped on the wire"
      (let [{:keys [payload]} (server/render (request))]
        (is (not (contains? payload :rf/schema-digest)))))))

(deftest the-request-frame-is-server-platform-and-no-caller-can-invert-it
  ;; rf2-323z. `render` is the whole-page NODE renderer, and its private
  ;; request frame was born UNTAGGED. Platform resolution is
  ;; `(or (-> frame-record :config :platform) (rf.interop/active-platform))`
  ;; (`fx/platform-for-frame-record`), and the CLJS host marker is `:client`
  ;; by default (`interop.cljs`) — so an untagged request frame ran a render
  ;; with no client on the CLIENT contract: a correctly declared
  ;; `:platforms #{:client}` effect reached from `:initial-events` EXECUTED
  ;; in Node, and the `#{:server}` one was skipped. Frames are isolated
  ;; contexts, and a request frame answering to the wrong platform contract
  ;; is that isolation broken rather than untidiness.
  ;;
  ;; The sibling `render-body` has carried `:platform :server` since it
  ;; landed; this row is what stops the two doors drifting apart again.
  (testing "with the host-wide marker at CLJS's own `:client` default, a
            render's initial event runs the SERVER arm of a platform pair
            and not the client arm"
    (let [restore (interop/active-platform)]
      (try
        (rf/init-platform :client)
        (is (= :client (interop/active-platform))
            "PREMISE: the host marker is `:client`, so the frame's own tag is
             the only thing that can select the server arm below — without
             this line a green row could mean the host was `:server` all
             along and the tag did nothing")

        (reset! platform-spy [])
        (server/render (request :initial-events [[::fire-both-platform-arms]]))
        (is (= [:server] @platform-spy)
            "exactly the server-only effect ran inside the request frame")

        (testing "and `:frame-opts` cannot invert it — the renderer OWNS its
                  server identity, so a caller's contradictory `:platform
                  :client` is assoc'd OVER rather than merged under, exactly
                  as `:id` and `:initial-events` are"
          (reset! platform-spy [])
          (server/render (request :initial-events [[::fire-both-platform-arms]]
                                  :frame-opts     {:platform :client}))
          (is (= [:server] @platform-spy)
              "the hostile `:platform :client` did not reach the frame"))

        (testing "NON-VACUITY — the same event in an UNTAGGED frame of this
                  same runtime selects the CLIENT arm. This is the shape
                  `render` used to build, so it is the defect itself, kept as
                  the control: were the pair not really platform-gated (both
                  effects inert, one unregistered, the metadata ignored) this
                  row would not read `[:client]`, and the two `[:server]`
                  assertions above would be green about nothing"
          (reset! platform-spy [])
          (rf/make-frame {:id             ::untagged-platform-control
                          :initial-events [[::fire-both-platform-arms]]})
          (rf/destroy-frame! ::untagged-platform-control)
          (is (= [:client] @platform-spy)
              "an untagged frame falls back to the host marker — `:client`"))
        (finally
          (rf/init-platform restore))))))

;; ---------------------------------------------------------------------------
;; 3b — HOW EVERY ASYNC ROW BELOW ENDS
;; ---------------------------------------------------------------------------
;;
;; Every row from here down hydrates a real root and waits on
;; `sup/adopted!`, and each of them holds things the `:each` fixture will
;; NOT take back. The fixture resets frames, the cell table and the
;; runtime; it does not unmount a React root, it does not unregister a
;; trace listener, and it does not give `console.error` back to the page.
;; Whatever a row fails to release itself, the NEXT row inherits.
;;
;; These rows used to end with a shape that released none of it when
;; things went wrong, and did it in two different ways:
;;
;;   (-> (sup/adopted! handle)
;;       (.then (fn [_] (try …assertions…
;;                           (finally (h/unmount! handle) (done)))))
;;       (.catch (fn [e] (is false …) (done))))
;;
;;   * A rejection BEFORE `.then` ran ended the row through the `.catch`,
;;     which unmounted no handle and stopped no watcher.
;;   * A throw INSIDE `.then` ran the `finally` — unmount, `done` — and
;;     then rejected the promise `.then` answers, so the `.catch` fired
;;     too and called `done` a SECOND time. `cljs.test`'s async runner
;;     reads that as the next row starting, so a single failure advances
;;     the runner twice and lands its report on whichever row is running
;;     by then.
;;
;; `sup/settle-row!` is the one path all of them now end with, and the two
;; rows under it are what say it works — because neither branch is on any
;; green path, and a repair to a branch nothing takes is untested by
;; construction.

(deftest a-throwing-body-finishes-the-row-once-and-not-twice
  ;; THE DOUBLE-FINISH CONTROL. No DOM in it — this is the promise
  ;; discipline by itself — so it is the one row in this file that takes
  ;; its reading in the node lane too.
  (async done
    (let [finishes (atom 0)
          releases (atom 0)
          reports  (atom [])]
      (-> (js/Promise.resolve true)
          (.then (fn [_] (throw (js/Error. "the body threw"))))
          (sup/settle-row! {:row      "the throwing-body control"
                            :done     (fn [] (swap! finishes inc))
                            :release! (fn [] (swap! releases inc))
                            :report!  (fn [e] (swap! reports conj e))})
          (.then
            (fn [_]
              (testing "a body that throws finishes the row ONCE. The shape
                        this file used to carry ran its `finally` — unmount,
                        `done` — and then let the `.catch` call `done` again,
                        which advances `cljs.test`'s async runner past a row
                        that never ran"
                (is (= 1 @finishes) (str "done ran " @finishes " times"))
                (is (= 1 @releases) (str "release ran " @releases " times")))
              (testing "and the throw is REPORTED rather than swallowed — a
                        settlement that ate it would turn a broken row into a
                        silent green"
                (is (= 1 (count @reports)) (str "got " (pr-str @reports)))
                (is (str/includes? (str (first @reports)) "the body threw")
                    (str "naming the error the body raised; got "
                         (pr-str @reports))))))
          (sup/settle-row! {:row "the throwing-body control's own settlement"
                            :done done})))))

(deftest a-rejected-adoption-leaves-the-next-row-a-clean-page
  ;; THE LEAK CONTROL. The rejection is injected AFTER the adoption
  ;; completes, which is the harder case and not the weaker one: every
  ;; resource the row owns is live and committed at that moment, so there
  ;; is strictly more to release than there would be had `adopted!`
  ;; rejected before the root ever adopted.
  (if-not (mount/browser?)
    (sup/skip! "what a rejection can leak is a real root and a real console")
    (async done
      (sup/leave-act-environment!)
      (let [{:keys [html]} (server/render (request))
            container      (sup/stamp-server-nodes! (sup/server-dom! html))
            watch          (sup/watch-mismatches!)
            console-before (.-error js/console)
            capture        (sup/open-console-capture!)
            stops          (atom 0)
            finishes       (atom 0)
            reports        (atom [])]
        (rf/make-frame {:id wire-frame :initial-events [[:rf/set-db snapshot]]})
        (let [handle (h/hydrate! container
                                 {:frame wire-frame :identifier-prefix "pfx-a-"}
                                 [id-page {}])]
          (-> (sup/adopted! handle)
              (.then (fn [shut?]
                       (is shut? "premise: the root really did adopt")
                       (is (not= sup/released (sup/census))
                           (str "premise: the runtime is holding this root's "
                                "cells and edges, so the census taken after "
                                "the rejection is a RELEASE and not an empty "
                                "page; got " (pr-str (sup/census))))
                       (js/Promise.reject (js/Error. "adoption rejected on purpose"))))
              (sup/settle-row! {:row      "the rejected-adoption control"
                                :done     (fn [] (swap! finishes inc))
                                :report!  (fn [e] (swap! reports conj e))
                                :release! (fn []
                                            ((:close! capture))
                                            (swap! stops inc)
                                            ((:stop! watch))
                                            (h/unmount! handle))})
              ;; The cell reapers are armed at unmount and run past a bare
              ;; macrotask, so the table is read at the runtime's own
              ;; horizon rather than one tick after the release.
              (.then (fn [_] (sup/quiesced!)))
              (.then
                (fn [_]
                  (testing "the rejection is reported, and the row ends once"
                    (is (= 1 @finishes) (str "done ran " @finishes " times"))
                    (is (= 1 (count @reports)) (str "got " (pr-str @reports)))
                    (is (str/includes? (str (first @reports))
                                       "adoption rejected on purpose")
                        (str "naming the rejection; got " (pr-str @reports))))
                  (testing "and the next row inherits NOTHING. Not one of these
                            four is the `:each` fixture's to take back: it
                            resets frames, the cell table and the runtime, but
                            it does not unmount a root, unregister a trace
                            listener, or hand `console.error` back"
                    (is (= sup/released (sup/census))
                        (str "no root: residue was " (pr-str (sup/census))))
                    (is (empty? (sup/cell-frames))
                        (str "no frame: the cell table still mentions "
                             (pr-str (sup/cell-frames))))
                    (is (= 1 @stops)
                        (str "the mismatch watcher was stopped — `stop!` is "
                             "what unregisters the trace listener; it ran "
                             @stops " times"))
                    (is (identical? console-before (.-error js/console))
                        "`console.error` is the page's own again"))))
              (sup/settle-row! {:row      "the rejected-adoption control's own settlement"
                                :done     done
                                :release! (fn []
                                            ((:close! capture))
                                            ((:stop! watch))
                                            (h/unmount! handle))})))))))

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
                       (is (nil? (h/unmount! handle))))))
            ;; The unmount above is an ASSERTION about the door's answer,
            ;; not this row's teardown; the teardown is named again here
            ;; so a rejection still gets one, and `unmount!` is
            ;; idempotent by contract.
            (sup/settle-row! {:row      "§4's hydration row"
                              :done     done
                              :release! (fn []
                                          ((:stop! watch))
                                          (h/unmount! handle))}))))))

;; ---------------------------------------------------------------------------
;; 4b — THE READING: the id itself, read off the bytes and read back off
;;      the DOM, with the hand-rolled path beside it as the control
;; ---------------------------------------------------------------------------
;;
;; §4 above asserts a SILENCE — no mismatch reported — and a silence is
;; not by itself a reading. `identifier_prefix_ssr_dom_cljs_test` §3 says
;; why in as many words: reading the id back is *the only reading that
;; separates "the client minted the same id" from "nobody touched the
;; server's text"*, and it takes that reading against a shape it builds
;; BY HAND and does not offer as a product path. Its §4 preamble records
;; the gap outright — *what it does NOT witness is a product door that
;; emits these bytes*.
;;
;; These three rows close it at the door, and they are a PAIR plus its
;; premise rather than three independent claims:
;;
;;   §4b-1  the two shapes' bytes differ in EXACTLY the id — which is why
;;          every markup-reading row in the package stayed green while
;;          obstruction 2 stood, and what makes the id the only thing the
;;          two rows below can be measuring.
;;   §4b-2  the door's bytes keep their id across a real client render.
;;   §4b-3  the hand-rolled bytes lose theirs, through the SAME public
;;          door, with the same helpers, in the same file.
;;
;; §4b-3 is not decoration. Without it §4b-2's equality is a number with
;; no scale, and *the plain path would have mismatched* would be an
;; inference — which is the one thing `dispositions.md` §2.4 does not
;; accept in place of a showing.

(deftest the-door-s-bytes-differ-from-the-hand-rolled-bytes-in-exactly-the-id
  (testing "one snapshot, one prefix, two tree shapes. The closer renders
            no DOM, so the id is the ONLY byte that may move — and this
            row is what says so rather than assuming it"
    (let [door  (:html (server/render (request)))
          plain (plain-html-for-this-request!)
          d-id  (id-in-html door)
          p-id  (id-in-html plain)]
      (is (some? d-id) "premise: the door's bytes carry an id")
      (is (some? p-id) "premise: so do the hand-rolled ones")
      (is (not= d-id p-id)
          (str "premise: the fork must still move the id — if these agree, "
               "the rows below are measuring nothing; got " (pr-str d-id)
               " both times"))
      (is (str/includes? door "alpha")
          "premise: both were rendered from the same seeded snapshot, so
           the label cannot be a second difference")
      (is (str/includes? plain "alpha"))
      (is (= (without-the-id door) (without-the-id plain))
          (str "with the id taken out, the two shapes' bytes must be "
               "IDENTICAL — that is why obstruction 2 shipped invisible to "
               "every row that reads markup. Door " (count door)
               " chars, hand-rolled " (count plain))))))

(deftest the-door-s-id-survives-the-adoption-and-a-real-client-render
  (if-not (mount/browser?)
    (sup/skip! "an id read back off the DOM needs a real React DOM")
    (async done
      (sup/leave-act-environment!)
      (let [{:keys [html]} (server/render (request))
            server-id      (id-in-html html)
            container      (sup/stamp-server-nodes! (sup/server-dom! html))
            watch          (sup/watch-mismatches!)]
        ;; State before DOM, the order `h/hydrate!`'s own docstring
        ;; teaches, so the first client render sees what the server
        ;; rendered from and the only thing left to disagree about is the
        ;; id.
        (rf/make-frame {:id wire-frame :initial-events [[:rf/set-db snapshot]]})
        (let [handle (h/hydrate! container
                                 {:frame wire-frame :identifier-prefix "pfx-a-"}
                                 [id-page {}])]
          (-> (sup/adopted! handle)
              (.then (fn [shut?]
                       (is shut? "the root's own adoption window shut")
                       (is (some? server-id) "premise: the bytes carry an id")

                       (testing "the probe is the SERVER's own node, and this
                                 row names it in the selector where §4 stops
                                 at `.page` — an expando does not survive a
                                 replacement, so this is what says the id
                                 below was read off an adopted node"
                         (is (sup/every-server-node? container ".page, .value, .probe")))

                       (relabel! "alpha'")
                       (testing "premise: a real client render ran. Both the
                                 view and the ISLAND repainted, so the id on
                                 the next line is the client's own answer and
                                 not a server byte nobody touched"
                         (is (= "alpha'" (text-in container ".value")))
                         (is (= "alpha'" (text-in container ".label"))))

                       (testing "THE READING — the client mints the id the
                                 door's bytes already carried"
                         (is (str/includes? server-id "pfx-a-")
                             "the server's id carried the prefix")
                         (is (= server-id (probe-id container))
                             (str "the client's own id must equal the "
                                  "server's; server " (pr-str server-id)
                                  ", client " (pr-str (probe-id container)))))

                       (testing "and nothing reached Spec 011's channel"
                         (is (= [] ((:stop! watch)))))))
              (sup/settle-row! {:row      "§4b-2's reading row"
                                :done     done
                                :release! (fn []
                                            ((:stop! watch))
                                            (h/unmount! handle))})))))))

(deftest the-hand-rolled-bytes-lose-the-id-through-the-same-door
  ;; THE CONTROL. Same public door, same helpers, same run — the other
  ;; answer, shown rather than inferred.
  (if-not (mount/browser?)
    (sup/skip! "an id read back off the DOM needs a real React DOM")
    (async done
      (sup/leave-act-environment!)
      (let [html      (plain-html-for-this-request!)
            server-id (id-in-html html)
            container (sup/stamp-server-nodes! (sup/server-dom! html))
            watch     (sup/watch-mismatches!)
            ;; MANUFACTURED here and asserted on here — the only shape of
            ;; call site at which swallowing an uncaught error is not the
            ;; fail-open the runner's pageerror rule exists to prevent.
            ;; React routes a hydration failure to `reportError`.
            capture   (sup/open-console-capture! {:swallow-uncaught? true})]
        (rf/make-frame {:id wire-frame :initial-events [[:rf/set-db snapshot]]})
        (let [handle (h/hydrate! container
                                 {:frame wire-frame :identifier-prefix "pfx-a-"}
                                 [id-page {}])]
          (-> (sup/adopted! handle)
              (.then (fn [shut?]
                       ;; Closed HERE and not only in the settlement: the
                       ;; assertions below must report through the page's
                       ;; own `console.error` rather than into the capture
                       ;; they are reading.
                       ((:close! capture))
                       (is shut? "the root's own adoption window shut")
                       (is (some? server-id) "premise: the bytes carry an id")

                       (testing "the divergence reaches Spec 011's channel,
                                 attributed to source rather than left as an
                                 uncaught window error"
                         (let [seen ((:stop! watch))]
                           (is (seq seen)
                               (str "the hand-rolled bytes must produce a "
                                    "reported mismatch; the page complained "
                                    (pr-str (mapv #(subs % 0 (min 60 (count %)))
                                                  @(:captured capture)))))
                           (when (seq seen)
                             (is (= 're-frame.hicasso.impl.mount/hydrate-root!
                                    (:where (sup/tags-of (first seen))))
                                 (str "attributed to source; got "
                                      (pr-str (:where (sup/tags-of (first seen)))))))))

                       (relabel! "alpha'")
                       (testing "premise: a real client render ran"
                         (is (= "alpha'" (text-in container ".label"))))

                       (testing "THE CONTROL'S READING — the id the client
                                 mints is NOT the one these bytes carried,
                                 and the prefix is not what moved"
                         (is (str/includes? server-id "pfx-a-"))
                         (is (str/includes? (probe-id container) "pfx-a-")
                             (str "both sides carry the prefix; got "
                                  (pr-str (probe-id container))))
                         (is (not= server-id (probe-id container))
                             (str "and they differ all the same — obstruction "
                                  "2's own mechanism, at the public door; "
                                  "server " (pr-str server-id) ", client "
                                  (pr-str (probe-id container)))))))
              ;; A rejection here would leave `console.error` replaced for
              ;; every row that follows, which is the one leak in this file
              ;; that no fixture takes back.
              (sup/settle-row! {:row      "§4b-3's control row"
                                :done     done
                                :release! (fn []
                                            ((:close! capture))
                                            ((:stop! watch))
                                            (h/unmount! handle))})))))))

;; ---------------------------------------------------------------------------
;; 5 — THE DOCUMENTED ROUND TRIP: payload script → state → DOM
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
  ;; THE CONTROL, and it is the recipe chapter 18 shipped.
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
                         (is (= [] ((:stop! watch)))))))
              (sup/settle-row! {:row      "§5's round-trip row"
                                :done     done
                                :release! (fn []
                                            ((:stop! watch))
                                            (h/unmount! handle)
                                            (remove!))})))))))
