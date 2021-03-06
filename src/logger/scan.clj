(ns logger.scan
  (:require [clj-http.client :as client]
            [bacure.core :as bac]
            [bacure.remote-device :as rd]
            [bacure.local-device :as ld]
            [bacure.local-save :as local]
            [logger.encoding :as encoding]
            [clojure.java.io :as io]
            [gzip64.core :as g]))

(def ^:dynamic *base-url*
  "https://bacnethelp.com")


(def logger-version
  "The logger version used to check what data encoding is used."
  "2.0.1")

(def path (str local/path "logger/"))

(defn- remove-nil-in-maps [m]
  (into {} (remove (comp nil? val) m)))
  
(defn get-configs-only
  "Get the logger configs. Remove all the `nil' entries."[]
  (try (->> (str path "/configs.cjm")
            slurp
            local/safe-read
            remove-nil-in-maps)
       (catch Exception e)))

(defn get-configs
  "Get the logger configs and merge them with the local device
  configs."[]
  (merge (dissoc (local/get-configs) :objects)
         (get-configs-only)))

(defn save-configs
  "Save data to configs file. Return data." [data]
  (local/mkdir-spit (str path "/configs.cjm") data) data)

(defn delete-configs
  "Delete the logger configs file, if found." []
  (try (io/delete-file (str path "/configs.cjm"))
       (catch Exception e)))

(defmacro dev [& body]
  `(binding [*base-url* "https://bacnethelp.com:8443"]
     ~@body))

(defn project-configs-from-server
  "Retrieve the logger project's config from the remote
   server. `query' is map of this form:
    
   {:project-id <string>
    :logger-password <string>}"
  [query]
  (-> (client/get (str *base-url* "/logger/get-config")
                  {:query-params (assoc query :logger-version logger-version)})
      :body
      local/safe-read))

(defn update-configs
  "Get the local configs, fetch them from the server, and merge
   everything together. Return the configs only if they changed."[]
   (try (let [local-configs (get-configs-only)
              remote-configs (-> (project-configs-from-server local-configs)
                                 remove-nil-in-maps)]
          (when (not=  (:timestamp remote-configs) (:timestamp local-configs))
            (save-configs (-> local-configs
                              (find :logger-password)
                              ((fn [x] (merge remote-configs x)))))))
        (catch Exception e)))

(defn filter-device
  "Test the criteria maps agains a device ID and return :remove if any
  succeed, otherwise :keep. If the extended information is not yet
  available, simply return nil." [id criteria-coll]
  (rd/extended-information id)
  (when (rd/extended-information? id)
    (let [remote-device-props
          (bac/remote-object-properties id [:device id]
                                        [:vendor-identifier :description :device-type
                                         :vendor-name :object-name :model-name])]
      ;;don't use `:all', it might not return the model-name if it's a
      ;;device that doesn't support read-property-multiple.
      (-> (filter (bac/where (first criteria-coll)) remote-device-props)
          ((fn [x] (let [crits (next criteria-coll)]
                     (cond (and (seq x) (seq (first criteria-coll))) :remove
                           crits (filter-device id crits)
                           :else :keep))))))))
                     

(def remove-device-table
  "A map of the device ID, associated with its associated scan behavior.
   Each time a new device ID is found, it should be matched against
   the criteria-map to see if it should be scanned. Returns :keep
   or :remove. If the device is still unchecked, it will be tested
   before giving a result."
  (atom {}))

(defn remove-device?
  "Check if the device ID is marked to be removed. If it hasn't been
   tested yet, test it and record the result." [id]
   (if-let [result (get @remove-device-table id)]
     result
     (get (swap! remove-device-table #(->> (:criteria-coll (get-configs))
                                       (filter-device id)
                                       (assoc % id))) id)))

(defn reset-devices-to-remove-table []
   (reset! remove-device-table {})
   (pmap remove-device? (rd/remote-devices)))
   

(defn find-id-to-scan
  "Check all the different filtering options and return a list of
   device-id to scan." []
   (let [{:keys [max-range min-range id-to-remove id-to-keep]} (get-configs)
         id-to-keep-fn (fn [x] (if id-to-keep (clojure.set/intersection (into #{} id-to-keep) x) x))
         id-to-remove-fn (fn [x] (clojure.set/difference x (into #{} id-to-remove)))
         remove-device (fn [x] (filter #(= :keep (remove-device? %)) x))
         min-fn (fn [x] (if min-range (filter #(> % min-range) x) x))
         max-fn (fn [x] (if max-range (filter #(< % max-range) x) x))]
     ;; and now just keep the remote devices for which we have extended information
     (-> (into #{} (filter rd/extended-information? (rd/remote-devices)))
         id-to-keep-fn
         id-to-remove-fn
         remove-device
         min-fn
         max-fn)))

(def last-scan-duration (atom nil))

(defn scan-network
  "Scan the network and return a `snapshot' for logging purposes."[]
  (->> (find-id-to-scan)
       (pmap encoding/scan-device) ;;use the power of parallelization!
       (apply merge)
       (hash-map :data)))


(defn find-unsent-logs []
  (let [filename-list (map #(.getAbsolutePath %)
                           (seq (.listFiles (clojure.java.io/file
                                             path))))]
    (filter #(re-find #"BH.*\.log" %) filename-list)))

(defn send-to-remote-server 
  "Send the data to remote servers. Return the result of the http POST
  request."
  [data]
  (let [{:keys [logger-password project-id]} (get-configs)]
    (try (client/post (str *base-url* "/logger/post-to-project")
                      {:form-params {:data data
                                     :logger-version logger-version
                                     :logger-password logger-password
                                     :project-id project-id}
                       :content-type "application/x-www-form-urlencoded"})
         (catch Exception e))))


;; for now we simply spit the file on the hard drive. It might be a
;; good idea to keep everything in the RAM until we fail to reach the
;; server. (Mostly for limited read/write type of hard drive.)
;; (defn scan-and-spit
;;   "Scan the network and save the result in a \"BH-<timestamp>\".log
;;    file." []
;;    (let [start-time (encoding/timestamp)
;;          spit-file-fn (partial local/mkdir-spit
;;                                (str path "BH" start-time ".log"))]
;;      (-> (scan-network)
;;          ((comp g/gz64 str))
;;          spit-file-fn)
;;      (reset! last-scan-duration (- (encoding/timestamp) start-time))))

(defn scan-and-send
  "Scan the network and send the result to remote servers. If the
   server can't be reached, or the data is turned down for any
   reason, save the result in a \"BH-<timestamp>\".log file." []
   (let [start-time (encoding/timestamp)
         spit-file-fn (partial local/mkdir-spit
                               (str path "BH" start-time ".log"))
         data (-> (scan-network) ((comp g/gz64 str)))]
     ;; try to send to server
     (when-not (= 200 (:status (send-to-remote-server data)))
       ;; if it doesn't work, save data locally.
       (spit-file-fn data))
     (reset! last-scan-duration (- (encoding/timestamp) start-time))))


(defn project-active?
  "Check with the remote servers if the project is currently in an
  active account."[]
  (let [{:keys [project-id]} (get-configs)]
    (->> (try (client/get (str *base-url* "/logger/active-project")
                          {:query-params {:project-id project-id}})
              (catch Exception e))
         :status
         (= 200))))

(defn send-logs
  "Check in the logger path for any unsent logs. If the server
   can't be reached (or the account inactive), keep every log." []
   (when (project-active?)
     (doseq [file (find-unsent-logs)]
       (when (= 200 (:status (send-to-remote-server (slurp file))))
         ;; if there's an error, keep the files for the next round
         (clojure.java.io/delete-file file)))))
