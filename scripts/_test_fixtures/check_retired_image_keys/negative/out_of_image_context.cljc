(ns fixture.out-of-context-tokens)

;; The counterpart to the context-gated positives: the SAME live tokens, in live
;; code, with nothing in the window anchoring them to an image or a frame
;; constructor. Each is the sanctioned unrelated spelling and MUST STAY GREEN —
;; and green HERE has to come from the missing context, so this fixture is
;; deliberately written clear of every word that suppresses a hit as prose.

;; `:include-ns` / `:exclude-ns` double as describe-image TOOL ARGS: boolean
;; flags toggling the returned `:registrations`, which this gate leaves alone.
(def tool-args
  {:include-ns true
   :exclude-ns false})

;; A bare `:replace` keyword — a JSON-patch style diff op, an FSM event name —
;; is an ordinary keyword with a sanctioned meaning of its own.
(def diff-op
  {:op :replace :path "/todos/0" :value "milk"})

;; A bare `:capabilities` key outside any frame constructor is an app's own
;; config field.
(def app-config
  {:capabilities {:feature/x true}})
