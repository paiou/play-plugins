package com.typesafe.plugin

import scala.language.postfixOps
import javax.mail._
import scala.concurrent._
import scala.async.Async._
import play.api.libs.Files._
import scala.util.{ Try, Success, Failure }
import java.io.FileOutputStream
import play.api.Logger
import org.apache.commons.mail.EmailException
import java.io.File
import org.apache.commons.mail.EmailAttachment

package object mailer {

  /**
   * Trait representing a content part of an email
   */
  sealed trait MailContent {
    def content: Any
  }

  /**
   * Represents a text part
   */
  case class TextContent(content: String) extends MailContent

  /**
   * Represents a part that is a file attachment.
   *
   * content is a temporary file that needs to be moved or copied to another place in order to be kept after VM shutdown
   */
  case class FileContent(content: TemporaryFile) extends MailContent

  /**
   * Adds extension methods to Message objects.
   */
  implicit class MailMessage(val message: Message) extends AnyVal {

    /**
     * Parse content of message and return a Map defining a List of MailContent for each contentType key
     */
    def getContentByType()(implicit context: ExecutionContext): Future[Map[String, List[MailContent]]] =
      parseContent(message.getContentType, message.getContent)

  }

  /**
   *  Empty content map (to avoid creating a new Map each time)
   */
  protected[mailer] lazy val emptyMap = Map[String, List[MailContent]]()

  /**
   * Parse a content given its content type
   *
   * @param contentType Content type to use for parsing
   * @param content Content to be parsed
   */
  protected[mailer] def parseContent(contentType: String, content: AnyRef)(implicit context: ExecutionContext): Future[Map[String, List[MailContent]]] =
    async {
      content match {
        case multipart: Multipart =>
          val contentMap = for (i <- 0 to multipart.getCount() - 1) yield {
            val part = multipart.getBodyPart(i)
            parseContent(part.getContentType, part)
          }
          await {
            Future.sequence(contentMap) map { _.foldLeft(emptyMap)((m1, m2) => m1 ++ m2) }
          }
        case str: String =>
          Map(contentType -> List(TextContent(str)))
        case bodypart: BodyPart =>
          contentType.split(";").toList match {
            case contType :: params if contType startsWith "text/" =>
              Map(contType -> List(TextContent(bodypart.getContent.toString)))
            case contType :: params if contType startsWith "multipart/" =>
              await { parseContent(contentType, bodypart.getContent) }
            case contType :: params =>
              val file = await { saveFile(bodypart) }
              file map { tempFile =>
                Map(contType -> List(FileContent(tempFile)))
              } getOrElse {
                Logger.error("Unknown content type " + contentType + " trying to parse file - Content: \n" + bodypart.getContent)
                emptyMap
              }
            case _ =>
              Logger.error("Unknown content type " + contentType + " - Content: \n" + bodypart.getContent)
              emptyMap
          }
      }
    }

  /**
   * Async method to save attachment from a mail Part into a TemporaryFile
   */
  protected[mailer] def saveFile(part: Part)(implicit context: ExecutionContext): Future[Try[TemporaryFile]] = {
    part.getFileName match {
      case str: String =>
        async {
          Try {
            val fileName = str split "\\."
            val outputFile = TemporaryFile("mailpart-" + (fileName dropRight 1 mkString ""), "." + (fileName takeRight 1 head))
            val is = part.getInputStream
            val fos = new FileOutputStream(outputFile.file)
            var buf = new Array[Byte](4096)
            var bytesRead = is.read(buf)
            while (bytesRead != -1) {
              fos.write(buf, 0, bytesRead)
              bytesRead = is.read(buf)
            }
            fos.close
            outputFile
          } recoverWith {
            case e =>
              Logger.error("Error saving attachment from email", e)
              Failure(e)
          }
        }
      case null => Future.successful(Failure(new EmailException("Mail part does not contain an attachment filename.")))
    }
  }
}
