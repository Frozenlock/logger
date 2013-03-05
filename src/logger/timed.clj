(ns logger.timed
  (:require [logger.scan :as scan]
            [overtone.at-at :as ot]
            [bacure.core :as bac]
            [bacure.local-device :as ld]
            [bacure.remote-device :as rd]
            [bacure.remote-device :as remote-device]))

(def pool (ot/mk-pool))

(declare start-logging)

(def logging-state
  (atom "Stopped"))

(defn stop-logging []
  (ot/stop-and-reset-pool! pool)
  (reset! logging-state "Stopped"))

(defn restart-logging []
  (stop-logging)
  (start-logging))

(defn min-ms
  "Convert minutes into milliseconds."
  [time-in-min]
  (* 60000 time-in-min))

(defn init
  "Reset the local device, make a list of remote devices and find
   those that should be excluded based on their properties."[]
   (ld/reset-local-device (scan/get-configs))
   (rd/discover-network)
   (Thread/sleep (min-ms 0.5)) ;; wait 30 sec so we know we have all the network.
   (rd/all-extended-information) ;; recheck for extented information
   (scan/reset-devices-to-remove-table))

(defn update-configs
  "Check with the remote server if the configs have changed. If they
  did, restart the logging."[]
  (when (scan/update-configs)
    (restart-logging)))

(defn start-logging
  "Add jobs to be executed in the future and/or at regulvar interval.

  :logger ---------> We scan the network at a regulvar time
                     interval (:time-interval in the configs). Also,
                     check for configuration update and send back any
                     local logs found.

  :restart --------> Restart the local device AT LEAST daily. (This is
                     done in order to discard any `visitor devices'
                     that are no longer on the network.)

  At start: we reset the local-device, discover the network, wait a
  while and then start to log the network."[]
  (when (= @logging-state "Stopped") ;;don't log twice simultaneously
    (reset! logging-state "Mapping network")
    (future ;; in another thread
      (scan/update-configs)
      (init)
      (when-not (= @logging-state "Stopped") ;; if we didn't stop the logging meanwhile
        (reset! logging-state "Logging")
        (let [time-interval (min-ms (or (:time-interval (scan/get-configs)) 10))]
          {:logger (ot/every time-interval #(do (update-configs)
                                                (rd/discover-network) ;; if new devices (or just slow)
                                                (scan/scan-and-spit)
                                                (scan/send-logs)) pool)
           :restart (ot/at (+ (min-ms 1440) (ot/now)) restart-logging pool)}))))) ;;1440

(defn maybe-start-logging
  "If a logger config file is found, start the logging and return
   true. Do nothing otherwise and return nil." []
   (when (scan/get-configs)
     (do (start-logging) true)))
  