(ns com.walmartlabs.active-status.component
  "A component for managing a status board."
  {:added "0.1.4"}
  (:require [com.walmartlabs.active-status :as active-status]
            [io.aviso.config :as config]
            [com.stuartsierra.component :as component]
            [com.walmartlabs.active-status.minimal-board :as minimal]
            [clojure.core.async :refer [close!]]
            [schema.core :as s]))

(defprotocol StatusBoard
  "A component for managing the creation and lifecycle of a status board.

  The component configuration controls whether the full console status board
  is used, or a minimal status board (for limited environments)."

  (add-job [component]
    [component options]
    "Returns a channel used to send job updates to the status board."))

(s/defschema StatusBoardConfig
  {:mode (s/enum :console :minimal)})

(defrecord StatusBoardComponent [board-ch mode]

  config/Configurable

  (configure [this configuration]
    (merge this configuration))

  component/Lifecycle

  (start [this]
    (assoc this :board-ch
                (if (= mode :minimal)
                  (minimal/minimal-status-board)
                  (active-status/console-status-board))))

  (stop [this]
    ;; Give the status board a chance to catch up with final job updates:
    (Thread/sleep 100)
    (close! board-ch)
    this)

  StatusBoard

  (add-job [_]
    (active-status/add-job board-ch))

  (add-job [_ options]
    (active-status/add-job board-ch options)))

(defn status-board
  "Creates the status board component.

  Jobs may not be added to the board until after it is started.

  The compnent uses the profile :status-board for configuration."
  []
  (-> (map->StatusBoardComponent {})
      (config/with-config-schema :status-board StatusBoardConfig)))