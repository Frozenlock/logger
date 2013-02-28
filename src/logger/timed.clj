(ns logger.timed
  (:require [logger.scan :as scan]
            [overtone.at-at :as ot]
            [bacure.core :as bac]))

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
   (bac/reset-local-device (scan/get-configs))
   (bac/discover-network)
   (Thread/sleep (min-ms 0.5)) ;; wait 30 sec so we know we have all the network.
   (bac/all-extended-information)
   (scan/update-devices-to-remove))

(defn update-configs
  "Check with the remote server if the configs have changed. If they
  did, restart the logging."[]
  (when (scan/update-configs)
    (restart-logging)))

(defn start-logging
  "Add jobs to be executed in the future and/or at regulvar interval.

  :logger ---------> We scan the network at a regulvar time
                     interval (:time-interval in the configs).

  :send-logs ------> Send the logs to a remote server every hour.


  :check-updates --> Check if the logger configurations on the server
                     changed and update if necessary.

  :restart --------> Restart the local device AT LEAST daily. (This is
                     done in order to discard any `visitor devices'
                     that are no longer on the network.)

  At start: we reset the local-device, discover the network, wait a
  while and then start to log the network."[]
  (reset! logging-state "Mapping network")
  (future ;; in another thread
    (scan/update-configs)
    (init)
    (reset! logging-state "Logging")
    (let [time-interval (min-ms (or (:time-interval (scan/get-configs)) 10))]
      {:logger (ot/every time-interval scan/scan-and-spit pool)
       :send-logs (ot/every (min-ms 5) scan/send-logs pool) ;;60
       :check-updates (ot/every (min-ms 60) update-configs pool :initial-delay (min-ms 10)) ;;60
       :restart (ot/at (+ (min-ms 60) (ot/now)) restart-logging pool)}))) ;;1440

(defn maybe-start-logging
  "If a logger config file is found, start the logging. Do nothing
   otherwise." []
   (when (scan/get-configs)
     (start-logging)))
  