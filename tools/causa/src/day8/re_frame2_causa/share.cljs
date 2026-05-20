(ns day8.re-frame2-causa.share
  "Causa-internal share-URL infra (rf2-nqw0v, Phase 5, parent rf2-2tkza).

  ## What this owns

  The Share affordance encodes a snapshot of the user's current
  Causa-side context (focused machine + selected instance + scrubber
  position + view-mode + selected tab) into a URL the user can paste
  into chat, the bug tracker, or a teammate's IDE. On load, Causa
  parses the URL and restores the context.

  ## Encoding choice — flat query-string, no base64

  Per the bead's divergence allowance the encoding is a flat
  key→value query-string rather than base64-encoded EDN:

      ?causa-share=1
      &machine=auth%2Flogin
      &instance=auth%2Flogin
      &pos=3
      &mode=mode-b
      &tab=machines

  Why flat: the encoded form is human-legible, diff-friendly, and
  trivially editable in chat / bug-trackers (`&pos=3` vs an opaque
  base64 blob). Transit-EDN + a short-URL service rides a follow-on
  bead when the encoded surface area outgrows query-string-sized
  ergonomics.

  ## Reserved param keys

    `causa-share` — sentinel; presence (`=1`) flips the restore path
                    on. Lets a downstream short-URL service add
                    arbitrary other params without false-positives.
    `machine`     — focused machine-id (keyword string, ns/leaf form)
    `instance`    — instance-id (same encoding as machine; for v1 the
                    instance-id is the machine-id under the snapshot
                    widening — the slot exists for the spawn-aware
                    future)
    `pos`         — scrubber position: integer or `present`
    `mode`        — forced view-mode: `mode-a` / `mode-b` / `mode-c`
                    / `auto`
    `tab`         — L3 tab id: `event` / `app-db` / `views` / `trace`
                    / `machines` / `issues`

  ## Frame isolation

  The events here all dispatch against `:rf/causa`. The browser
  bridge — `js/window.location`, `navigator.clipboard` — is wrapped
  so the JVM / Node test rigs can stub through plain test setters.

  ## Helper algebra

  Pure encode + decode + URL-build helpers live in this ns under
  `encode-state` / `decode-query-string` / `build-share-url`. The
  fxs + event-fxs are CLJS-only — the test surface drives them via
  reg-fx replacement."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-causa.export.cascade :as export-cascade]))

;; ---- encode / decode ----------------------------------------------------

(defn- kw->encoded
  "Encode a keyword for the query string. Namespaced keywords serialise
  as `ns/name`; bare keywords as `name`. nil → nil. Strings pass
  through. Anything else uses pr-str so the round-trip is faithful."
  [v]
  (cond
    (nil? v)        nil
    (keyword? v)    (if-let [ns (namespace v)]
                      (str ns "/" (name v))
                      (name v))
    (string? v)     v
    :else           (pr-str v)))

(defn- encoded->kw
  "Inverse of kw->encoded. The encoded form preserves the leading
  `:` only when the value itself was pr-str'd (vectors, maps, etc.);
  bare keywords roundtrip via `ns/name` form."
  [s]
  (cond
    (nil? s)                          nil
    (str/blank? s)                    nil
    (and (> (count s) 1) (= \: (first s)))
    ;; pr-str'd form — read back as EDN
    (try (reader/read-string s) (catch :default _ nil))
    :else
    ;; plain keyword form: "auth/login" or "idle"
    (if (str/includes? s "/")
      (let [[ns name] (str/split s #"/" 2)]
        (keyword ns name))
      (keyword s))))

(defn- url-encode
  "Browser-side URL component encoder. Tests can rebind."
  [s]
  (when s
    (js/encodeURIComponent s)))

(defn- url-decode
  "Browser-side URL component decoder. Tests can rebind."
  [s]
  (when s
    (try (js/decodeURIComponent s) (catch :default _ s))))

(defn encode-state
  "Render the Causa-side state map into a query-param map. nil-safe;
  drops empty / nil values so the URL stays compact. Pure fn.

  Input shape:

      {:machine-id  <keyword-or-nil>
       :instance-id <keyword-or-nil>
       :position    <int|:present|nil>
       :mode        <:mode-a|:mode-b|:mode-c|nil>
       :tab         <keyword-or-nil>}

  Returns a sorted vec of `[key value]` pairs (sorted so the encoded
  URL is deterministic; useful for round-trip tests + dedupe)."
  [{:keys [machine-id instance-id position mode tab]}]
  (let [pairs (cond-> []
                true                       (conj ["causa-share" "1"])
                (some? machine-id)         (conj ["machine" (kw->encoded machine-id)])
                (some? instance-id)        (conj ["instance" (kw->encoded instance-id)])
                (or (= :present position)
                    (nil? position))       (conj ["pos" "present"])
                (integer? position)        (conj ["pos" (str position)])
                (some? mode)               (conj ["mode" (kw->encoded mode)])
                (some? tab)                (conj ["tab" (kw->encoded tab)]))]
    ;; Sort by key for deterministic encoding (test-friendly).
    (vec (sort-by first pairs))))

(defn query-string
  "Render a sorted [[k v] ...] pair-vec into a `?a=b&c=d` query string
  (with leading `?`). Pure fn."
  [pairs]
  (if (empty? pairs)
    ""
    (str "?" (str/join "&" (map (fn [[k v]]
                                  (str (url-encode k) "=" (url-encode v)))
                                pairs)))))

(defn build-share-url
  "Compose `origin` + `pathname` + the encoded state's query string.
  Pure fn — tests pass an explicit base; the CLJS `current-share-url`
  fn below reads `js/window.location` for the runtime."
  [base state]
  (str (or base "") (query-string (encode-state state))))

(defn parse-query-string
  "Inverse of `query-string` — parses a leading `?a=b&c=d` (or bare
  `a=b&c=d`) into a `{key value}` string-map. nil / empty → `{}`.
  Pure fn. Tolerant of malformed pairs (drops them rather than
  throwing)."
  [s]
  (if (str/blank? s)
    {}
    (let [trimmed (if (str/starts-with? s "?") (subs s 1) s)
          parts   (str/split trimmed #"&")]
      (into {}
            (keep (fn [part]
                    (let [[k v] (str/split part #"=" 2)]
                      (when (and k v)
                        [(url-decode k) (url-decode v)]))))
            parts))))

(defn decode-state
  "Inverse of `encode-state` — parses the query-string param map back
  into a Causa-side state map. Returns nil when `causa-share` is
  absent (so the restore path can short-circuit on non-share URLs).

  Pure fn — does NOT touch app-db; the event handler in this ns
  applies the state."
  [params]
  (when (and params (= "1" (get params "causa-share")))
    (let [pos-raw (get params "pos")]
      (cond-> {}
        (get params "machine")
        (assoc :machine-id (encoded->kw (get params "machine")))

        (get params "instance")
        (assoc :instance-id (encoded->kw (get params "instance")))

        (= "present" pos-raw)
        (assoc :position :present)

        (and pos-raw (not= "present" pos-raw))
        (assoc :position (try
                           (let [n (js/parseInt pos-raw 10)]
                             (if (js/isNaN n) :present n))
                           (catch :default _ :present)))

        (get params "mode")
        (assoc :mode (encoded->kw (get params "mode")))

        (get params "tab")
        (assoc :tab (encoded->kw (get params "tab")))))))

(defn decode-share-url
  "Convenience: parse a full URL's query string into the Causa state
  map. Pure fn. Returns nil when the URL doesn't carry the
  `causa-share` sentinel."
  [url]
  (when url
    (let [qs-idx (str/index-of url "?")
          qs     (when qs-idx (subs url qs-idx))]
      (decode-state (parse-query-string qs)))))

;; ---- browser bridge -----------------------------------------------------

(defn- window-location-base
  "Read `js/window.location.origin + pathname` to produce the base
  for the share URL. Tests rebind via `with-redefs`."
  []
  (try
    (str (.. js/window -location -origin)
         (.. js/window -location -pathname))
    (catch :default _ "")))

(defn current-share-url
  "Render the share URL for the current Causa state. CLJS-only —
  reads `js/window.location` for the base. Pure once the base is
  passed in (the JVM/Node test target calls `build-share-url`
  directly)."
  [state]
  (build-share-url (window-location-base) state))

;; ---- subs ---------------------------------------------------------------

(defn install-subs!
  "Register the share-modal sub family."
  []
  (rf/reg-sub :rf.causa/share-modal-open?
    (fn [db _query]
      (boolean (get db :share/modal-open?))))

  ;; The Causa-side state composite the share URL encodes. Built as a
  ;; standalone sub so the modal can show the encoded URL live as the
  ;; user moves the scrubber / picks a different machine.
  ;;
  ;; Pulls the explicit `:rf.causa/selected-machine-id` slot rather
  ;; than the composite's effective fall-back: the user's INTENT (an
  ;; explicit pick, or no pick at all) is what should round-trip
  ;; through the share URL — sharing the implicit "first machine"
  ;; would be a footgun for hosts whose registered-machines order
  ;; changes between sessions.
  (rf/reg-sub :rf.causa/share-state
    :<- [:rf.causa/selected-machine-id]
    :<- [:rf.causa/selected-tab]
    :<- [:rf.causa/machine-scrubber-position]
    (fn [[machine-id tab position] _query]
      ;; instance-id == machine-id under the Phase 1/3 snapshot
      ;; widening; the slot stays for the spawn-aware future.
      ;;
      ;; rf2-y9xmf: `:mode` (forced Mode A/B/C) is gone — the panel is
      ;; event-driven only. Inbound share URLs carrying a `:mode` slot
      ;; are silently dropped in `restore-from-share-url` below.
      (cond-> {}
        (some? machine-id)  (assoc :machine-id machine-id
                                   :instance-id machine-id)
        (some? tab)         (assoc :tab tab)
        (some? position)    (assoc :position position))))

  ;; The composed URL string. The modal renders this in its <input>
  ;; field; the copy fx reads it via the same composite at fire time.
  (rf/reg-sub :rf.causa/share-url
    :<- [:rf.causa/share-state]
    (fn [state _query]
      (current-share-url state)))

  ;; Effect status — `:idle | :copied | :failed`. The modal flips the
  ;; copy button's label off this slot ("Copy" → "Copied!" → reset).
  (rf/reg-sub :rf.causa/share-copy-status
    (fn [db _query]
      (get db :share/copy-status :idle)))

  ;; ---- per-cascade structured export (rf2-0us27) -----------------------
  ;;
  ;; The export is a pure projection of the currently-focused cascade
  ;; into a JVM-serialisable EDN map (`day8.re-frame2-causa.export.
  ;; cascade/project-cascade`). It rides the same modal as the share-
  ;; URL because conceptually both are "share a snapshot of my Causa
  ;; context"; the modal renders the URL + an export button row.
  ;;
  ;; The composite picks the focused cascade by `:dispatch-id` /
  ;; `:frame` off the spine (`:rf.causa/focus`), pairs it with the
  ;; matching epoch record from `:rf.causa/epoch-history`, and feeds
  ;; both into the pure projection. Pre-focus / empty-buffer returns
  ;; nil; the modal toggles its export buttons off in that state.
  (rf/reg-sub :rf.causa/cascade-export
    :<- [:rf.causa/event-detail]
    :<- [:rf.causa/epoch-history]
    :<- [:rf.causa/focus]
    (fn [[event-detail history focus] _query]
      (let [{:keys [selected-cascade selected-dispatch-id]} event-detail
            epoch-id (:epoch-id focus)
            epoch    (when epoch-id
                       (some (fn [r] (when (= epoch-id (:epoch-id r)) r))
                             history))]
        (when (and selected-cascade selected-dispatch-id)
          (export-cascade/project-cascade
            selected-cascade
            {:epoch epoch})))))

  ;; The EDN string the modal exposes — pre-rendered so the modal can
  ;; show a preview / size hint live. nil when no cascade is focused.
  (rf/reg-sub :rf.causa/cascade-export-edn
    :<- [:rf.causa/cascade-export]
    (fn [export _query]
      (when export
        (export-cascade/to-edn-string export))))

  ;; Convenience: is there anything to export right now? Drives the
  ;; export-button's `:disabled` slot in the modal.
  (rf/reg-sub :rf.causa/cascade-export-available?
    :<- [:rf.causa/cascade-export]
    (fn [export _query]
      (boolean export)))

  ;; Export-action status — `:idle | :copied | :downloaded | :failed`.
  ;; Mirrors `:share-copy-status` but for the cascade-export buttons.
  (rf/reg-sub :rf.causa/cascade-export-status
    (fn [db _query]
      (get db :share/cascade-export-status :idle))))

;; ---- effects ------------------------------------------------------------

(defn copy-to-clipboard!
  "Native clipboard write. CLJS-only; returns a Promise. Tests can
  stub via `with-redefs` since the event-fx wraps this call (so
  redefining this fn is enough to mock the copy).

  Per the Causa-wide fx convention the fire side uses the existing
  `:rf.causa.fx/copy-to-clipboard` fx (registered in
  `app_db_diff_events.cljs`); the post-copy status flip rides a
  fire-and-forget `.then` on the Promise here so the modal's button
  label flips on resolve / reject."
  [text]
  (when (and js/navigator (.-clipboard js/navigator))
    (.writeText (.-clipboard js/navigator) (str text))))

(defn download-text-file!
  "Browser-side text-file download via a Blob + transient <a> click.
  CLJS-only. Tests `with-redefs` this to capture the (filename, text)
  pair without touching the DOM. Returns true on a successful
  best-effort dispatch, false otherwise."
  [filename text]
  (try
    (when (and js/document js/window)
      (let [blob (js/Blob. #js [(str text)] #js {:type "text/plain;charset=utf-8"})
            url  (.createObjectURL (.-URL js/window) blob)
            a    (.createElement js/document "a")]
        (set! (.-href a) url)
        (set! (.-download a) (str filename))
        (.appendChild (.-body js/document) a)
        (.click a)
        (.removeChild (.-body js/document) a)
        (.revokeObjectURL (.-URL js/window) url)
        true))
    (catch :default _ false)))

(defn install-fxs!
  "Register the share-specific fxs. The clipboard fx itself reuses
  the shared `:rf.causa.fx/copy-to-clipboard` fx already registered
  by app_db_diff_events.cljs; this install only adds the new-tab +
  download fxs."
  []
  ;; Open a URL in a new browser tab. Wrapped as an fx so the test
  ;; rig can stub `:rf.causa.fx/open-in-new-tab` instead of touching
  ;; `js/window.open` directly.
  (rf/reg-fx :rf.causa.fx/open-in-new-tab
    (fn [url]
      (when (and url js/window)
        (.open js/window (str url) "_blank"))))

  ;; Save a text payload to disk via a transient anchor + Blob URL.
  ;; rf2-0us27 — used by the cascade-export download button.
  ;; Wrapped as an fx so tests can stub the side-effect.
  (rf/reg-fx :rf.causa.fx/download-text-file
    (fn [{:keys [filename text]}]
      (download-text-file! filename text))))

;; ---- events -------------------------------------------------------------

(defn install-events!
  "Register the share-modal event family."
  []
  (rf/reg-event-db :rf.causa/share-modal-open
    (fn [db _event]
      (-> db
          (assoc :share/modal-open? true)
          (assoc :share/copy-status :idle))))

  (rf/reg-event-db :rf.causa/share-modal-close
    (fn [db _event]
      (-> db
          (dissoc :share/modal-open?)
          (dissoc :share/copy-status))))

  (rf/reg-event-db :rf.causa/share-copy-status
    (fn [db [_ status]]
      (assoc db :share/copy-status (or status :idle))))

  ;; Copy the current share URL. The handler reads the composite slots
  ;; from app-db directly to capture the URL at fire-time — the modal's
  ;; UI might be a tick behind on a fast scrub but the URL the user
  ;; actually pastes always reflects the moment they clicked Copy.
  ;;
  ;; The fire path:
  ;;   1. Compose the share-state from the per-slot app-db reads.
  ;;   2. Render the URL.
  ;;   3. Call `copy-to-clipboard!` directly (so tests can stub via
  ;;      `with-redefs`) — the returned Promise (when clipboard is
  ;;      available) fans out a status-update dispatch on resolve /
  ;;      reject so the modal's button label flips. Fire-and-forget;
  ;;      we don't `await` from inside the handler.
  ;;   4. Stash the URL in app-db for test inspection.
  (rf/reg-event-fx :rf.causa/copy-share-url-to-clipboard
    (fn [{:keys [db]} _event]
      (let [state (let [machine-id  (:selected-machine-id db)
                        tab         (or (:selected-tab db) :event)
                        position    (:machine-inspector/scrubber-position db)]
                    (cond-> {}
                      (some? machine-id)  (assoc :machine-id machine-id
                                                 :instance-id machine-id)
                      (some? tab)         (assoc :tab tab)
                      (some? position)    (assoc :position position)))
            url   (current-share-url state)
            p     (copy-to-clipboard! url)]
        (when p
          (.then p
                 (fn [_]
                   (rf/dispatch [:rf.causa/share-copy-status :copied]
                                {:frame :rf/causa})
                   (js/setTimeout
                     (fn []
                       (rf/dispatch [:rf.causa/share-copy-status :idle]
                                    {:frame :rf/causa}))
                     1500))
                 (fn [_]
                   (rf/dispatch [:rf.causa/share-copy-status :failed]
                                {:frame :rf/causa}))))
        {:db (assoc db :share/last-encoded-url url)})))

  (rf/reg-event-fx :rf.causa/open-share-url-in-new-tab
    (fn [{:keys [db]} _event]
      (let [state (let [machine-id  (:selected-machine-id db)
                        tab         (or (:selected-tab db) :event)
                        position    (:machine-inspector/scrubber-position db)]
                    (cond-> {}
                      (some? machine-id)  (assoc :machine-id machine-id
                                                 :instance-id machine-id)
                      (some? tab)         (assoc :tab tab)
                      (some? position)    (assoc :position position)))
            url   (current-share-url state)]
        {:fx [[:rf.causa.fx/open-in-new-tab url]]})))

  ;; ---- per-cascade structured export events (rf2-0us27) -------------
  ;;
  ;; Two surfaces against the same projection: copy the EDN to clipboard
  ;; or download it as a file. Both fire-and-forget; the status slot
  ;; flips on success / failure and resets to `:idle` after a short
  ;; delay so the button label reverts.
  (rf/reg-event-db :rf.causa/cascade-export-status
    (fn [db [_ status]]
      (assoc db :share/cascade-export-status (or status :idle))))

  ;; rf2-0us27 — Copy the focused cascade's export EDN to clipboard.
  ;; Reads the composite the same way share-url does: subscribe-at-fire
  ;; rather than rely on the modal's in-flight render. Stashes the
  ;; rendered EDN under `:share/last-cascade-export` for test inspection
  ;; and flips `:share/cascade-export-status` to drive the button label.
  (rf/reg-event-fx :rf.causa/copy-cascade-export-to-clipboard
    (fn [{:keys [db]} _event]
      (let [;; Build the export by reading the same sub the modal does.
            ;; The sub is layer-2 over `:rf.causa/event-detail` /
            ;; `:rf.causa/epoch-history` / `:rf.causa/focus` so the
            ;; value is the same value the modal renders.
            export @(rf/subscribe [:rf.causa/cascade-export])
            edn    (when export (export-cascade/to-edn-string export))]
        (if (not edn)
          {:db (assoc db :share/cascade-export-status :failed)}
          (let [p (copy-to-clipboard! edn)]
            (when p
              (.then p
                     (fn [_]
                       (rf/dispatch [:rf.causa/cascade-export-status :copied]
                                    {:frame :rf/causa})
                       (js/setTimeout
                         (fn []
                           (rf/dispatch [:rf.causa/cascade-export-status :idle]
                                        {:frame :rf/causa}))
                         1500))
                     (fn [_]
                       (rf/dispatch [:rf.causa/cascade-export-status :failed]
                                    {:frame :rf/causa}))))
            {:db (assoc db :share/last-cascade-export edn)})))))

  ;; rf2-0us27 — Download the focused cascade's export EDN as a file.
  ;; Routes through the `:rf.causa.fx/download-text-file` fx so tests
  ;; can `with-redefs` the side effect away. Same status-slot wiring as
  ;; the copy event so the button reverts to idle on resolve.
  (rf/reg-event-fx :rf.causa/download-cascade-export
    (fn [{:keys [db]} _event]
      (let [export   @(rf/subscribe [:rf.causa/cascade-export])
            edn      (when export (export-cascade/to-edn-string export))
            ts       (try (.toISOString (js/Date.)) (catch :default _ nil))
            filename (when export
                       (export-cascade/suggested-filename
                         {:dispatch-id (:dispatch-id export)
                          :exported-at ts}))]
        (if (or (not edn) (not filename))
          {:db (assoc db :share/cascade-export-status :failed)}
          (do
            ;; Status flip rides setTimeouts (mirroring the copy-URL path)
            ;; so the button reverts after the user has seen "Downloaded!".
            (js/setTimeout
              (fn []
                (rf/dispatch [:rf.causa/cascade-export-status :downloaded]
                             {:frame :rf/causa}))
              0)
            (js/setTimeout
              (fn []
                (rf/dispatch [:rf.causa/cascade-export-status :idle]
                             {:frame :rf/causa}))
              1500)
            {:db (assoc db
                        :share/last-cascade-export      edn
                        :share/last-cascade-export-name filename)
             :fx [[:rf.causa.fx/download-text-file
                   {:filename filename :text edn}]]})))))

  ;; Restore Causa state from a decoded share-state map. Drives the
  ;; per-slot reducers so the same code-paths that handle interactive
  ;; events handle the restored ones — no special-case branches.
  ;;
  ;; rf2-y9xmf: the `:mode` slot (forced Mode A/B/C) was removed when
  ;; the Machine Inspector was collapsed to an event-driven panel.
  ;; Inbound share URLs carrying `:mode` are silently dropped here so
  ;; old links don't error.
  (rf/reg-event-db :rf.causa/restore-from-share-url
    (fn [db [_ {:keys [machine-id position tab] :as _state}]]
      (cond-> db
        (some? machine-id) (assoc :selected-machine-id machine-id)
        (some? position)   (assoc :machine-inspector/scrubber-position position)
        (some? tab)        (assoc :selected-tab tab)))))

;; ---- on-load restore hook -----------------------------------------------

(defn maybe-restore-from-location!
  "Inspect `js/window.location.search` and, if it carries the
  `causa-share` sentinel, dispatch the restore event. Called from
  `mount.cljs` after the Causa frame is registered. CLJS-only.

  Returns the parsed state map (for test inspection) or nil when no
  share URL is present."
  []
  (try
    (let [qs    (.. js/window -location -search)
          state (decode-state (parse-query-string qs))]
      (when state
        (rf/dispatch [:rf.causa/restore-from-share-url state]
                     {:frame :rf/causa}))
      state)
    (catch :default _ nil)))

;; ---- public install entry -----------------------------------------------

(defn install!
  "Idempotent install for the share infra. Called by
  `machine_inspector/install!`. Wires subs + events + fxs through the
  Causa-side registrar."
  []
  (install-subs!)
  (install-fxs!)
  (install-events!))
