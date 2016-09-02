(ns com.walmartlabs.active-status.component
  "A component for managing a status board."
  {:added "0.1.4"}
  (:require [com.walmartlabs.active-status :as as]
            [io.aviso.config :as config]
            [com.stuartsierra.component :as component]
            [com.walmartlabs.active-status.minimal-board :as minimal]
            [clojure.core.async :refer [close!]]
            [clojure.spec :as s]))

(s/def ::mode #{:console :minimal})
(s/def ::config (s/keys :req-un [::mode]))

(defrecord StatusBoardComponent [status-board mode]

  config/Configurable

  (configure [this configuration]
    (merge this configuration))

  as/StatusJobInitiation

  component/Lifecycle

  (start [this]
    (assoc this :board-ch
           (if (= mode :minimal)
             (minimal/minimal-status-board)
             (as/console-status-board))))

  (stop [this]
    (as/shutdown! status-board)
    this)

  as/StatusJobInitiation

  (add-job [_]
    (as/add-job status-board))

  (add-job [_ options]
    (as/add-job status-board options)))

(defn status-board
  "Creates the status board component, returning a system map
  that may be merged into an overall system.

  Jobs may not be added to the board until after it is started.

  The component uses the profile :status-board for configuration."
  []
  (component/system-map
    :status-board (-> (map->StatusBoardComponent {})
                      (config/with-config-spec :status-board ::config))))
