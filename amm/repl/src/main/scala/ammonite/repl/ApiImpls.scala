package ammonite.repl


import ammonite.interp.Interpreter
import ammonite.runtime._
import ammonite.util.Util._
import ammonite.util._

import scala.collection.mutable

class SessionApiImpl(frames0: => StableRef[List[Frame]]) extends Session{
  def frames = frames0()
  val namedFrames = mutable.Map.empty[String, List[Frame]]

  def childFrame(parent: Frame) = new Frame(
    new SpecialClassLoader(
      parent.classloader,
      parent.classloader.classpathSignature
    ),
    new SpecialClassLoader(
      parent.pluginClassloader,
      parent.pluginClassloader.classpathSignature
    ),
    parent.imports,
    parent.classpath
  )

  def save(name: String = "") = {
    if (name != "") namedFrames(name) = frames
    frames0() = childFrame(frames.head) :: frames
  }

  def pop(num: Int = 1) = {
    var next = frames
    for(i <- 0 until num){
      if (next.tail != Nil) next = next.tail
    }
    val out = SessionChanged.delta(frames.head, next.head)
    frames0() = childFrame(next.head) :: next
    out
  }
  
  def load(name: String = "") = {
    val next = if (name == "") frames.tail else namedFrames(name)
    val out = SessionChanged.delta(frames.head, next.head)
    frames0() = childFrame(next.head) :: next
    out
  }

  def delete(name: String) = {
    namedFrames.remove(name)
  }
}
trait ReplApiImpl extends FullReplAPI{

  implicit def tprintColorsImplicit = pprint.TPrintColors(
    typeColor = colors().`type`()
  )
  implicit val codeColorsImplicit = new CodeColors{
    def comment = colors().comment()
    def `type` = colors().`type`()
    def literal = colors().literal()
    def keyword = colors().keyword()
    def ident = colors().ident()
  }

  implicit val pprinter: Ref[pprint.PPrinter] = Ref.live(() =>
    pprint.PPrinter.Color.copy(
      defaultHeight = height / 2,
      defaultWidth = width,
      colorLiteral = colors().literal(),
      colorApplyPrefix = colors().prefix(),
      additionalHandlers = PPrints.replPPrintHandlers
    )
  )

  def show(t: Any) = show(t, null, 9999999, null)

  def printer: Printer

  override def show(t: Any,
                    width: Integer = null,
                    height: Integer = 9999999,
                    indent: Integer = null) = {

    pprinter()
      .tokenize(
        t,
        width = if (width == null) pprinter().defaultWidth else width,
        height = if (height == null) pprinter().defaultHeight else height,
        indent = if (indent == null) pprinter().defaultIndent else indent
      )
      .map(_.render)
      .foreach(printer.out)
    printer.out(newLine)
  }

  def sess: SessionApiImpl

}
