(ns com.walmartlabs.active-status.component
  "A component for managing a status board."
  {:added "0.1.4"}
  (:require [com.walmartlabs.active-status :as as]
            [io.aviso.config :as config]
            [com.stuartsierra.component :as component]
            [com.walmartlabs.active-status.minimal-board :as minimal]
            [com.walmartlabs.active-status.progress :refer [elapsed-time-job]]
            [clojure.core.async :refer [close!]]
            [clojure.spec :as s]))

(s/def ::mode #{:console :minimal})
(s/def ::status-board-config (s/keys :req-un [::mode]))

(defrecord StatusBoardComponent [status-board mode]

  config/Configurable

  (configure [this configuration]
    (merge this configuration))

  component/Lifecycle

  (start [this]
    (assoc this :status-board
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
                      (config/with-config-spec :status-board ::status-board-config))))

(defrecord Elapsed [status-board configuration]

  component/Lifecycle

  (start [this]
    (elapsed-time-job status-board
                      {:delay-millis (-> configuration :delay (* 1000))
                       :interval-millis (-> configuration :interval (* 1000))})
    this)

  (stop [this] this))

;; Delay for first notification, then interval, both in seconds.
(s/def ::delay int?)
(s/def ::interval int?)
(s/def ::elapsed-time-spec (s/keys :req-un [::delay ::interval]))

(defn elapsed-time
  "Returns a system map for an :elapsed-time component.
  This is based on [[elapsed-time-job]].

  Shares the :status-board profile for its default configuration."
  []
  (component/system-map
    :elapsed-time (-> (map->Elapsed {})
                      (component/using [:status-board])
                      (config/with-config-spec :elapsed-time ::elapsed-time-spec))))
