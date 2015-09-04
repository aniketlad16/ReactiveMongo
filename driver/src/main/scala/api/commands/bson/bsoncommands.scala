package reactivemongo.api.commands.bson

import reactivemongo.api.BSONSerializationPack
import reactivemongo.api.commands._
import reactivemongo.bson._

object CommonImplicits {
  implicit object UnitBoxReader extends BSONDocumentReader[UnitBox.type] {
    def read(doc: BSONDocument): UnitBox.type = UnitBox
  }
}

trait BSONCommandError extends CommandError {
  def originalDocument: BSONDocument
}

case class DefaultBSONCommandError(
    code: Option[Int],
    errmsg: Option[String],
    originalDocument: BSONDocument) extends BSONCommandError {
  override def getMessage = s"CommandError[code=${code.getOrElse("<unknown>")}, errmsg=${errmsg.getOrElse("<unknown>")}, doc: ${BSONDocument.pretty(originalDocument)}]"
}

/** Helper to read a command result, with error handling. */
trait DealingWithGenericCommandErrorsReader[A] extends BSONDocumentReader[A] {
  /** Results the successful result (only if `ok` is true). */
  def readResult(doc: BSONDocument): A

  final def read(doc: BSONDocument): A =
    if (!doc.getAs[BSONBooleanLike]("ok").exists(_.toBoolean)) {
      throw new DefaultBSONCommandError(
        code = doc.getAs[Int]("code"),
        errmsg = doc.getAs[String]("errmsg"),
        originalDocument = doc)
    } else readResult(doc)
}
