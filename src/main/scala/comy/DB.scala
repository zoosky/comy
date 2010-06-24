package comy

import java.util.{Date, Calendar}
import java.text.SimpleDateFormat

import com.mongodb._

object DB {
  val URL_COLL     = "urls"
  val KEY          = "key"
  val URL          = "url"
  val ACCESS_COUNT = "access_count"
  val CREATED_ON   = "created_on"
  val UPDATED_ON   = "updated_on"  // The date when ACCESS_COUNT is incremented
}

/**
 * See: http://www.mongodb.org/display/DOCS/Java+Language+Center
 * Only one instance of this class should be used for the whole application.
 */
class DB(config: Config) extends Logger {
  import DB._

  val left  = new ServerAddress(config.dbHostLeft,  config.dbPortLeft)
  val right = new ServerAddress(config.dbHostRight, config.dbPortRight)

  val options = new MongoOptions
  options.connectionsPerHost = config.dbConnectionsPerHost
  options.autoConnectRetry   = true

  val mongo = new Mongo(left, right, options)
  val db = mongo.getDB(config.dbName)
  val coll = db.getCollection(URL_COLL)

  ensureIndex

  /**
   * @return None if there is error (DB is down etc.)
   */
  def saveUrl(url: String): Option[String] = {
    try {
      val existedKey = getKeyFromUrl(url)
      if (existedKey == None) {
        var key = ""
        var keyDuplicated = true
        while (keyDuplicated) {
          key = KeyGenerator.generateKey
          if (getUrlFromKey(key, false) == None) {
            addNewUrl(key, url)
            keyDuplicated = false
          }
        }
        Some(key)
      } else {
        existedKey
      }
    } catch {
      case e: Exception =>
        error(e)
        None
    }
  }

  /**
   * @return None if there is error (DB is down etc.)
   */
  def getUrl(key: String): Option[String] = {
    try {
      getUrlFromKey(key, true)
    } catch {
      case e: Exception =>
        error(e)
        None
    }
  }

  /**
   * Removes all URLs that have not been accessed within the last number of days.
   * The number of days is configured in config.properties.
   *
   * @return false if there is error (DB is down etc.)
   */
  def removeExpiredUrls: Boolean = {
    try {
      val expirationDate = getFormattedExpirationDate(config.dbExpirationDays)
      val query = new BasicDBObject
      query.put(ACCESS_COUNT, 0)
      query.put(UPDATED_ON,   new BasicDBObject("$lte", expirationDate))
      val result = coll.find(query)
      while (result.hasNext) {
        coll.remove(result.next)
      }
      true
    } catch {
      case e: Exception =>
        error(e)
        false
    }
  }

  def ensureIndex {
    // Index each column separately (3 indexes in total) because we will search
    // based on each one separately
    coll.ensureIndex(new BasicDBObject(KEY,        1))
    coll.ensureIndex(new BasicDBObject(URL,        1))
    coll.ensureIndex(new BasicDBObject(UPDATED_ON, 1))
  }

  /**
   * @return None if URL is not existed, or otherwise the associated key
   */
  private def getKeyFromUrl(url: String): Option[String] = {
    val result = coll.findOne(new BasicDBObject(URL, url))
    if (result != null) {
      Some(result.get(KEY).toString)
    } else {
      None
    }
  }

  /**
   * @return None if Key is not existed.
   * Otherwise, return the associated URL
   * Also update last_access and access_counter if specified
   */
  private def getUrlFromKey(key: String, updateAccess: Boolean): Option[String] = {
    val result = coll.findOne(new BasicDBObject(KEY, key))
    if (result != null) {
      if (updateAccess) {
      	// This may not be accurate when there are many concurrent requests to the same key!
        val resultUpdate = new BasicDBObject("$inc", new BasicDBObject(ACCESS_COUNT, 1))

        resultUpdate.append("$set", new BasicDBObject(UPDATED_ON, formatDate(new Date)))
        coll.update(result, resultUpdate)
      }
      Some(result.get(URL).toString)
    } else {
      None
    }
  }

  /**
   * Add a new URL to the database
   */
  private def addNewUrl(key: String, url: String) {
    val doc = new BasicDBObject
    val today = new Date
    doc.put(KEY,          key)
    doc.put(URL,          url)
    doc.put(ACCESS_COUNT, 0)
    doc.put(CREATED_ON,   formatDate(today))
    doc.put(UPDATED_ON,   formatDate(today))
    coll.insert(doc)
  }

  private def formatDate(date: Date): String = {
    val format = new SimpleDateFormat("yyyyMMdd")
    format.format(date)
  }

  private def getFormattedExpirationDate(days: Int): String = {
    val today = new Date
    val cal = Calendar.getInstance
    cal.setTime(today)
    cal.add(Calendar.DATE, -days)
    formatDate(cal.getTime)
  }
}
