package comy.action

import io.netty.handler.codec.http.HttpHeaders
import HttpHeaders.Names.CONTENT_TYPE

import xitrum.annotation.{GET, POST}
import xitrum.validator.{Required, MaxLength, Url}

import comy.model.{DB, SaveUrlResult, QRCode}

@GET("")
class UserIndex extends AppAction {
  def execute() {
    respondView()
  }
}

@GET("user/qrcode")  // ?url=xxx
class UserQRcode extends AppAction {
  def execute() {
    // See: http://www.hascode.com/2010/05/playing-around-with-qr-codes/
    val url   = param("url")
    val bytes = QRCode.render(url)
    HttpHeaders.setHeader(response, CONTENT_TYPE, "image/png")
    respondBinary(bytes)
  }
}

@POST("user/shorten")
class UserShorten extends AppAction {
  def execute() {
    val toBeShorten = param("url").trim
    if (toBeShorten.isEmpty) {
      jsRespond("$('#result').html('%s')".format(jsEscape(<p class="error">{t("URL must not be empty")}</p>)))
      return
    }

    val keyo = {
      val ret = param("key").trim
      if (ret.isEmpty) None else Some(ret)
    }
    val (resultCode, resultString) = DB.saveUrl(this, toBeShorten, keyo)

    // This causes error with Scala 2.10.0:
    //val html = resultCode match {...

    val html: scala.xml.Node = resultCode match {
      case SaveUrlResult.VALID =>
        val absoluteUrl = absUrl[ApiLengthen]("key" -> resultString)
        <xml:group>
          <hr />
          {absoluteUrl}<br />
          <a href={absoluteUrl} target="_blank"><img src={url[UserQRcode]("url" -> absoluteUrl)} /></a>
        </xml:group>

      case SaveUrlResult.INVALID =>
        <p class="error">{resultString}</p>

      case SaveUrlResult.DUPLICATE =>
        <p class="error">{t("Key has been chosen")}</p>

      case SaveUrlResult.ERROR =>
        <p class="error">{t("Server error")}</p>
    }
    jsRespond("$('#result').html('%s')".format(jsEscape(html)))
  }
}
