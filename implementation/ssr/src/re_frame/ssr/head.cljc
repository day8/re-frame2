(ns re-frame.ssr.head
  "Head/meta contract façade — `reg-head`, `head-model`, `default-head`
  and `head-model->html`.

  The server-rendered HTML must carry head metadata — `<title>`,
  `<meta>`, `<link>`, JSON-LD — on first byte; crawlers and link-
  unfurlers don't run JS. The pattern's commitment: **the head model
  is data derived from app-db**, not an imperative DOM API.

  The head module decomposes into three concern-per-file siblings + a
  shared HTML-helpers ns:

    - `re-frame.ssr.html-helpers`   — shared HTML escape helpers
                                      (`escape-html` / `escape-attr` /
                                      `attr-string`); consumed by the
                                      hiccup emitter AND the head emitter.
    - `re-frame.ssr.head.emit`      — `head-model->html` and its
                                      per-element emitters.
    - `re-frame.ssr.head.registry`  — `reg-head`, `head-model`,
                                      `default-head`.
    - `re-frame.ssr.head`           — this façade. Re-exports the public
                                      surface and publishes the late-bind
                                      hooks so `(require 're-frame.ssr.head
                                      :reload)` resurrects every
                                      registration after a `clear-all!`.

  Per the optional-artefact wrapper convention (Conventions.md
  §Optional-artefact wrapper convention), the REGISTRAR `reg-head` is
  reachable via `re-frame.core` through the `:ssr/reg-head` late-bind hook
  so core never statically `:require`s this namespace. The READ is not
  re-exported onto core: `re-frame.ssr` carries `head-model` and
  `head-model->html`, and consumers reach them there or here —
  rf2-kuky.44 / rf2-kuky.87 / rf2-kuky.89."
  (:require [re-frame.late-bind :as rf.late-bind]
            [re-frame.ssr.head.emit :as rf.ssr.head.emit]
            [re-frame.ssr.head.registry :as rf.ssr.head.registry]))

;; ---- public-surface re-exports --------------------------------------------

(def head-model->html    rf.ssr.head.emit/head-model->html)

(def reg-head            rf.ssr.head.registry/reg-head)
(def head-model          rf.ssr.head.registry/head-model)
(def default-head        rf.ssr.head.registry/default-head)

;; ---- late-bind hook registration ------------------------------------------
;;
;; The one late-bind hook fires on ns load. Keeping it in the façade
;; (rather than in the producing sub-ns) means that `(require
;; 're-frame.ssr.head :reload)` — the canonical test-fixture reset shape —
;; re-publishes it, regardless of which sub-ns defined the underlying fn.

(rf.late-bind/set-fn! :ssr/reg-head          reg-head)
