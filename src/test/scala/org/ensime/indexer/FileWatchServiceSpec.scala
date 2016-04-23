// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.indexer

import akka.testkit._
import akka.event.slf4j.SLF4JLogging
import com.google.common.io.Files
import file._
import java.util.UUID
import org.ensime.fixture._
import org.ensime.util._
import org.scalatest.ParallelTestExecution
import org.scalatest.tagobjects.Retryable
import org.scalatest.concurrent.ScalaFutures
import java.nio.charset.Charset
import org.ensime.filewatcher._
import scala.collection.immutable.Set
import scala.concurrent.duration._
import scala.language.implicitConversions
import java.io.File
import scala.concurrent.ExecutionContext.Implicits.global

sealed trait FileWatcherMessage

case class Added(b: File, f: File) extends FileWatcherMessage
case class Removed(b: File, f: File) extends FileWatcherMessage
case class Changed(b: File, f: File) extends FileWatcherMessage

case class BaseRegistered(f: File) extends FileWatcherMessage
case class BaseRemoved(f: File) extends FileWatcherMessage
case class BaseSubdirRemoved(b: File, f: File) extends FileWatcherMessage
case class MissingBaseRegistered(f: File) extends FileWatcherMessage
case class BaseSubdirRegistered(b: File, f: File) extends FileWatcherMessage
case class ProxyRegistered(b: File, f: File) extends FileWatcherMessage

case class ExistingFile(b: File, f: File) extends FileWatcherMessage

/**
 * These tests are insanely flakey so everything is retryable. The
 * fundamental problem is that file watching is impossible without
 * true OS and FS support, which is lacking on all major platforms.
 */

abstract class FileWatcherSpec extends EnsimeSpec
    //with ParallelTestExecution
    with IsolatedTestKitFixture {

  // variant that watches a jar file
  def createJarWatcher(jar: File)(implicit tk: TestKit): Watcher

  // variant that recursively watches a directory of classes
  def createClassWatcher(base: File)(implicit tk: TestKit): Watcher

  val maxWait = 20 seconds

  "FileWatcher" should "detect added files" in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        log.debug(s"detect added files ${dir}")
        withClassWatcher(dir) { watcher =>
          checkBase(dir, tk)
          createFoo(dir, tk)
          createBar(dir, tk)
        }
      }
    }

  it should "detect added / changed files" in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        log.debug(s"detect added / changed files ${dir}")
        withClassWatcher(dir) { watcher =>
          checkBase(dir, tk)
          createFoo(dir, tk)
          createBar(dir, tk)
          changeFooBar(dir, tk)
        }
      }
    }

  it should "detect added / removed files" in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        log.debug(s"detect added / removed files: ${dir}")
        withClassWatcher(dir) { watcher =>
          checkBase(dir, tk)
          createFoo(dir, tk)
          createBar(dir, tk)

          deleteFoo(dir, tk)
          deleteBar(dir, tk)
        }
      }
    }

  it should "detect removed base directory" in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        log.debug(s"detect removed base directory: ${dir}")
        withClassWatcher(dir) { watcher =>
          checkBase(dir, tk)

          tk.system.scheduler.scheduleOnce(30 milli) {
            log.debug(s"remove base ${dir}")
            dir.delete()
          }

          val baseRemovedAndCreated: Fish = {
            case BaseRemoved(f) => {
              log.debug(s"Detected BaseRemoved ${f} for ${dir} base")
              f == dir
            }
            case MissingBaseRegistered(f) => {
              log.debug(s"Detected MissingBaseRegistered ${f} for ${dir} base")
              f == dir
            }
            case e => { logEvent("Bad ", dir, e); false }
          }

          tk.fishForMessage(maxWait)(baseRemovedAndCreated)
          tk.fishForMessage(maxWait)(baseRemovedAndCreated)
        }
      }
    }

  it should "detect removed parent base directory" in
    withTestKit { implicit tk =>
      val parent = Files.createTempDir().canon
      val dir = parent / "base"
      dir.mkdirs()
      log.debug(s"detect removed parent base directory ${dir}")
      try {

        withClassWatcher(dir) { watcher =>
          checkBase(dir, tk)

          tk.system.scheduler.scheduleOnce(50 milli) {
            log.debug(s"Remove parent base ${parent} ${dir} base}")
            parent.tree.reverse.foreach(_.delete())
          }

          val baseRemovedAndCreated: Fish = {
            case BaseRemoved(f) => f == dir
            case MissingBaseRegistered(f) => f == dir
            case e => { logEvent("Bad ", dir, e); false }
          }
          tk.fishForMessage(maxWait)(baseRemovedAndCreated)
          tk.fishForMessage(maxWait)(baseRemovedAndCreated)

        }
      } finally parent.tree.reverse.foreach(_.delete())
    }

  it should "survive deletion of the watched directory" in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        log.debug(s"survive deletion of the watched directory: ${dir}")
        withClassWatcher(dir) { watcher =>
          checkBase(dir, tk)
          createFoo(dir, tk)
          createBar(dir, tk)

          tk.system.scheduler.scheduleOnce(50 milli) {
            log.debug(s"remove ${dir}")
            dir.tree.reverse.foreach(_.delete())
          }

          deleteBase(dir, dir, tk)

          createFoo(dir, tk)
          createBar(dir, tk)
        }
      }
    }

  it should "be able to start up from a non-existent directory" in
    withTestKit { implicit tk =>
      val root = Files.createTempDir().canon
      val dir = (root / "dir")
      log.debug(s"be able to start up from a non-existent directory: ${dir}")
      dir.delete()
      try {
        log.debug(s"be able to start up from a non-existent directory: ${dir}")
        withClassWatcher(dir) { watcher =>

          val proxyRegistered: Fish = {
            case ProxyRegistered(b, f) => {
              b == dir && f == root
            }
            case e => { logEvent("Bad ", dir, e); false }
          }
          tk.fishForMessage(maxWait)(proxyRegistered)

          val foo = (dir / "foo.class")
          val bar = (dir / "b/bar.class")

          tk.system.scheduler.scheduleOnce(30 milli) { foo.createWithParents() shouldBe true }

          val missingBaseAndFoo: Fish = {
            case MissingBaseRegistered(f) => f == dir
            case ExistingFile(b, f) => {
              b == dir && f == foo
            }
            case Added(b, f) => {
              b == dir && f == foo
            }
            case e => { logEvent("Bad ", dir, e); false }
          }
          tk.fishForMessage(maxWait)(missingBaseAndFoo)
          tk.fishForMessage(maxWait)(missingBaseAndFoo)

          tk.system.scheduler.scheduleOnce(30 milli) { bar.createWithParents() shouldBe true }

          val subdirAndBar: Fish = {
            case BaseSubdirRegistered(b, f) => b == dir && f == bar.getParentFile
            case ExistingFile(b, f) => {
              b == dir && f == bar
            }
            case Added(b, f) => {
              b == dir && f == bar
            }
            case e => { logEvent("Bad ", dir, e); false }
          }

          tk.fishForMessage(maxWait)(subdirAndBar)
          tk.fishForMessage(maxWait)(subdirAndBar)
        }
      } finally dir.tree.reverse.foreach(_.delete())
    }

  it should "survive removed parent base directory and recreated base" in
    withTestKit { implicit tk =>

      val root = Files.createTempDir().canon
      val parent = root / "parent"
      val dir = parent / "base"
      log.debug(s"survive removed parent base directory and recreated base. ${dir}")
      dir.mkdirs()
      try {
        withClassWatcher(dir) { watcher =>
          checkBase(dir, tk)
          createFoo(dir, tk)
          createBar(dir, tk)

          deleteBase(root, dir, tk)

          createFoo(dir, tk)
          createBar(dir, tk)

        }
      } finally dir.tree.reverse.foreach(_.delete())
    }

  //////////////////////////////////////////////////////////////////////////////
  it should "detect changes to a file base" in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        log.debug(s"detect changes to a file base ${dir}")
        val jar = (dir / "jar.jar")
        jar.createWithParents() shouldBe true

        withJarWatcher(jar) { watcher =>
          tk.ignoreMsg {
            case msg: ExistingFile => true
          }
          checkBase(jar, tk)

          tk.system.scheduler.scheduleOnce(1000 milli) {
            jar.writeString("binks")
          }

          val jarChanged: Fish = {
            case Changed(b, f) => {
              log.debug(s"Detected Changed ${b}, ${f} ")
              b == jar && f == jar
            }
            case e => { logEvent("Bad ", dir, e); false }
          }
          tk.fishForMessage(maxWait)(jarChanged)

        }
      }
    }

  it should "detect removal of a file base" in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        log.debug(s"detect removal of a file base. ${dir}")
        val root = (dir / "root")
        val jar = (root / "jar.jar")
        jar.createWithParents() shouldBe true

        withJarWatcher(jar) { watcher =>
          tk.ignoreMsg {
            case msg: ExistingFile => true
          }
          checkBase(jar, tk)

          tk.system.scheduler.scheduleOnce(30 milli) {
            jar.delete()
          }
          val jarRemoved: Fish = {
            case BaseRemoved(f) => f == jar
            case Removed(b, f) => b == jar && f == jar
            case e => { logEvent("Bad ", dir, e); false }
          }
          tk.fishForMessage(maxWait)(jarRemoved)
        }
      }
    }

  it should "be able to start up from a non-existent base file" in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        val jar = (dir / "jar.jar")
        log.debug(s"be able to start up from a non-existent base file. ${jar}")
        withJarWatcher(jar) { watcher =>

          val proxyRegistered: Fish = {
            case ProxyRegistered(b, f) => {
              log.debug(s"Detected ProxyRegistered ${b} ${f}, ${jar} jar")
              b == jar && f == dir
            }
            case e => { logEvent("Bad ", dir, e); false }
          }
          tk.fishForMessage(maxWait)(proxyRegistered)

          jar.createWithParents() shouldBe true

          val jarAdded: Fish = {
            case MissingBaseRegistered(f) => f == jar
            case Added(b, f) => false
            case ExistingFile(b, f) => false
            case e => { logEvent("Bad ", dir, e); false }
          }
          tk.fishForMessage(maxWait)(jarAdded)
        }
      }
    }

  it should "survive removal of a file base" in
    withTestKit { implicit tk =>
      withTempDir { root =>
        val dir = (root / "base")
        val jar = (dir / "jar.jar")
        log.debug(s"survive removal of a file base ${jar}")
        jar.createWithParents() shouldBe true

        withJarWatcher(jar) { watcher =>
          tk.ignoreMsg {
            case msg: ExistingFile => true
          }
          checkBase(jar, tk)

          jar.delete() shouldBe true

          val jarRemoved: Fish = {
            case BaseRemoved(f) => f == jar
            case Removed(b, f) => b == jar && f == jar
            case ProxyRegistered(b, f) => b == jar && f == dir
            case e => { logEvent("Bad ", dir, e); false }
          }
          tk.fishForMessage(maxWait)(jarRemoved)
          tk.fishForMessage(maxWait)(jarRemoved)
          tk.fishForMessage(maxWait)(jarRemoved)

          jar.writeString("binks")
          jar.exists shouldBe true
          val jarAdded: Fish = {
            case MissingBaseRegistered(f) => f == jar
            case Added(b, f) => false
            case ExistingFile(b, f) => false

            case Changed(b, f) => false
            case e => { logEvent("Bad ", dir, e); false }
          }
          tk.fishForMessage(maxWait)(jarAdded)
        }
      }
    }

  it should "survive removal of a parent of a file base" in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        log.debug(s"survive removal of a parent of a file base. ${dir}")
        val root = (dir / "root")
        val jar = (root / "parent" / "jar.jar")
        jar.createWithParents() shouldBe true
        withJarWatcher(jar) { watcher =>
          tk.ignoreMsg {
            case msg: ExistingFile => true
          }

          checkBase(jar, tk)

          root.tree.reverse.foreach(_.delete())

          val jarRemoved: Fish = {
            case BaseRemoved(f) => {
              log.debug(s"Detected BaseRemoved ${f} base ${jar}")
              f == jar
            }
            case Removed(b, f) => {
              log.debug(s"Detected Removed ${b} ${f} base ${jar}")
              b == jar && f == jar
            }
            case ProxyRegistered(b, f) => {
              log.debug(s"Detected ProxyRegistered ${b} ${f} base ${jar}")
              false
            } // ignore
            case e => { logEvent("Bad ", dir, e); false }
          }
          tk.fishForMessage(maxWait)(jarRemoved)
          tk.fishForMessage(maxWait)(jarRemoved)

          jar.createWithParents() shouldBe true

          val jarAdded: Fish = {
            case MissingBaseRegistered(f) => f == jar
            case Added(b, f) => false
            case ExistingFile(b, f) => false
            case ProxyRegistered(b, f) => {
              log.debug(s"Detected ProxyRegistered ${b} ${f} base ${jar}")
              false
            } // ignore

            case e => { logEvent("Bad ", dir, e); false }
          }
          tk.fishForMessage(maxWait)(jarAdded)
        }
      }
    }
  it should "be able to start up from a non-existent grandparent of a base file" in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        val top = (dir / "top")
        val grand = (top / "grand")
        val parent = (grand / "parent")
        val jar = (parent / "jar.jar")
        log.debug(s"be able to start up from a non-existent grandparent of a base file. ${jar}")
        (dir / "top").tree.reverse.foreach(_.delete())
        withJarWatcher(jar) { watcher =>
          val proxyDirRegistered: Fish = {
            case ProxyRegistered(b, f) => {
              log.debug(s"Detected ProxyRegistered ${b} ${f}, ${jar} jar")
              b == jar && f == dir
            }
            case e => { logEvent("Bad ", dir, e); false }
          }
          tk.fishForMessage(maxWait)(proxyDirRegistered)

          jar.createWithParents() shouldBe true

          val missingBaseRegistered: Fish = {
            case ProxyRegistered(b, f) => {
              log.debug(s"Detected ProxyRegistered ${b} ${f}, ${jar} jar")
              false

            }
            case ExistingFile(b, f) => false
            case MissingBaseRegistered(f) => f == jar
            case e => { logEvent("Bad ", dir, e); false }
          }
          tk.fishForMessage(maxWait)(missingBaseRegistered)

        }
      }
    }

  def checkBase(dir: File, tk: TestKit) = {
    val baseCreated: Fish = {
      case BaseRegistered(f) => f == dir
      case e => { logEvent("Bad ", dir, e); false }
    }
    tk.fishForMessage(maxWait)(baseCreated)
  }

  def createFoo(dir: File, tk: TestKit) = {
    val foo = (dir / "foo.class")
    tk.system.scheduler.scheduleOnce(30 milli) {
      log.debug(s"created ${foo}")
      foo.createWithParents() shouldBe true
      foo.exists shouldBe true
    }
    val fishFoo: Fish = {
      case ExistingFile(b, f) => {
        log.debug(s"Detected ExistingFile ${b} ${f}, base: ${dir}")
        b == dir && f == foo
      }
      case Added(b, f) => {
        log.debug(s"Detected Added ${b} ${f}, base: ${dir}")
        b == dir && f == foo
      }
      case e => { logEvent("Bad ", dir, e); false }
    }
    tk.fishForMessage(maxWait)(fishFoo)
  }

  def createBar(dir: File, tk: TestKit) = {
    val bar = (dir / "b/bar.class")

    tk.system.scheduler.scheduleOnce(30 milli) {
      log.debug(s"created ${bar}")
      bar.createWithParents() shouldBe true
      bar.exists shouldBe true
    }
    val fishBar: Fish = {
      case BaseSubdirRegistered(b, f) => {
        log.debug(s"Detected BaseSubdirRegistered ${b} ${f}, base: ${dir}")
        b == dir && f == bar.getParentFile
      }
      case ExistingFile(b, f) => {
        log.debug(s"Detected ExistingFile ${b} ${f}, base: ${dir}")
        b == dir && f == bar
      }
      case Added(b, f) => {
        log.debug(s"Detected Added ${b} ${f}, base: ${dir}")
        b == dir && f == bar
      }
      case e => { logEvent("Bad ", dir, e); false }
    }
    tk.fishForMessage(maxWait)(fishBar)
    tk.fishForMessage(maxWait)(fishBar)
  }

  def changeFooBar(dir: File, tk: TestKit) = {
    val foo = (dir / "foo.class")
    val bar = (dir / "b/bar.class")
    tk.system.scheduler.scheduleOnce(30 milli) {
      log.debug(s"changed ${foo}")
      foo.writeString("foo")
      log.debug(s"changed ${bar}")
      bar.writeString("bar")
    }
    val fishFooBarChange: Fish = {
      case Changed(b, f) => {
        log.debug(s"Detected Changed ${b} ${f}, base: ${dir}")
        b == dir && (f == foo || f == bar)
      }
      case e => { logEvent("Bad ", dir, e); false }
    }

    tk.fishForMessage(maxWait)(fishFooBarChange)
    tk.fishForMessage(maxWait)(fishFooBarChange)
  }

  def deleteFoo(dir: File, tk: TestKit) = {
    val foo = (dir / "foo.class")
    tk.system.scheduler.scheduleOnce(30 milli) {
      log.debug(s"deleted ${foo}")
      foo.delete() shouldBe true
    }
    val fishFooRemoved: Fish = {
      case Removed(b, f) => {
        log.debug(s"Detected Removed ${b} ${f}, base: ${dir}")
        b == dir && f == foo
      }
      case e => { logEvent("Bad ", dir, e); false }
    }
    tk.fishForMessage(maxWait)(fishFooRemoved)
  }

  def deleteBar(dir: File, tk: TestKit) = {
    val bar = (dir / "b/bar.class")
    tk.system.scheduler.scheduleOnce(30 milli) {
      log.debug(s"deleted ${bar}")
      bar.delete() shouldBe true
    }
    val fishBarRemoved: Fish = {
      case Removed(b, f) => {
        log.debug(s"Detected Removed ${b} ${f}, base: ${dir}")
        b == dir && f == bar
      }
      case e => { logEvent("Bad ", dir, e); false }
    }
    tk.fishForMessage(maxWait)(fishBarRemoved)

  }

  def deleteBase(root: File, base: File, tk: TestKit) = {
    tk.system.scheduler.scheduleOnce(50 milli) {
      log.debug(s"remove ${base}")
      root.tree.reverse.foreach(_.delete())
    }
    val fishBaseRemovedAndCreated: Fish = {
      case BaseRemoved(f) => {
        log.debug(s"Detected BaseRemoved ${f} base ${base}")
        f == base
      }
      case Removed(b, f) => {
        log.debug(s"Detected Removed ${b} ${f} base ${base}. IGNORE as OS X doesn't detect it.")
        false
      }
      case MissingBaseRegistered(f) => {
        log.debug(s"Detected MissingBaseRegistered ${f} base ${base}")
        f == base
      }
      case e => { logEvent("Bad ", base, e); false }
    }
    tk.fishForMessage(maxWait)(fishBaseRemovedAndCreated)
    tk.fishForMessage(maxWait)(fishBaseRemovedAndCreated)

  }
  //////////////////////////////////////////////////////////////////////////////
  type -->[A, B] = PartialFunction[A, B]
  type Fish = PartialFunction[Any, Boolean]

  def withClassWatcher[T](base: File)(code: Watcher => T)(implicit tk: TestKit) = {
    val w = createClassWatcher(base)
    try code(w)
    finally w.shutdown()

  }

  def withJarWatcher[T](jar: File)(code: Watcher => T)(implicit tk: TestKit) = {
    val w = createJarWatcher(jar)
    try code(w)
    finally w.shutdown()
  }

  def logEvent(typ: String, base: File, e: Any) = e match {
    case Added(b: File, f: File) => log.debug(s"${typ} Added ${b}, ${f} base: ${base}")
    case Removed(b: File, f: File) => log.debug(s"${typ} Removed ${b}, ${f} base: ${base}")
    case Changed(b: File, f: File) => log.debug(s"${typ} Changed ${b}, ${f} base: ${base}")
    case BaseRegistered(f: File) => log.debug(s"${typ} BaseRegistered ${f} base: ${base}")
    case BaseRemoved(f: File) => log.debug(s"${typ} BaseRemoved ${f} base: ${base}")
    case BaseSubdirRemoved(b: File, f: File) => log.debug(s"${typ} BaseSubdirRemoved ${b} ${f} base: ${base}")
    case MissingBaseRegistered(f: File) => log.debug(s"${typ} MissingBaseRegistered ${f} base: ${base}")
    case BaseSubdirRegistered(b: File, f: File) => log.debug(s"${typ} MissingSubdirRegistered ${b} ${f} base: ${base}")
    case ProxyRegistered(b: File, f: File) => log.debug(s"${typ} ProxyRegistered ${b} ${f} base: ${base}")
    case ExistingFile(b: File, f: File) => log.debug(s"${typ} ExistingFile ${b} ${f} base: ${base}")
    case _ => log.debug(s"${typ} Unknown event.  base: ${base}")
  }

  object WatcherListener {
    def apply(
      b: File,
      r: Boolean,
      e: Set[String],
      w: UUID
    )(implicit tk: TestKit): WatcherListener = {
      new WatcherListener {
        val base: File = b
        val recursive: Boolean = r
        val extensions: Set[String] = e
        val watcherId: UUID = w

        override def fileCreated(f: File): Unit = {
          log.debug(s"event: fileCreated ${f} ${base} base")
          tk.testActor ! Added(base, f)
        }
        override def fileDeleted(f: File): Unit = {
          log.debug(s"event: fileDeleted ${f} ${base} base")
          tk.testActor ! Removed(base, f)
        }
        override def fileModified(f: File): Unit = {
          log.debug(s"event: fileModified ${f} ${base} base")
          tk.testActor ! Changed(base, f)
        }

        override def baseRegistered(): Unit = {
          log.debug(s"event: baseRegistered ${base} ")
          tk.testActor ! BaseRegistered(base)
        }
        override def baseRemoved(): Unit = {
          log.debug(s"event: baseRemoved ${base}")
          tk.testActor ! BaseRemoved(base)
        }
        override def baseSubdirRemoved(f: File): Unit = {
          log.debug(s"event: baseSubdirRemoved ${f} ${base} base")
          tk.testActor ! BaseSubdirRemoved(base, f)
        }
        override def missingBaseRegistered(): Unit = {
          log.debug(s"event: missingBaseRegistered ${base} ")
          tk.testActor ! MissingBaseRegistered(base)
        }

        override def baseSubdirRegistered(f: File): Unit = {
          log.debug(s"event: baseSubdirRegistered ${f} ${base} base")
          tk.testActor ! BaseSubdirRegistered(base, f)
        }
        override def proxyRegistered(f: File): Unit = {
          log.debug(s"event: proxyRegistered ${f} ${base} base")
          tk.testActor ! ProxyRegistered(base, f)
        }

        override def existingFile(f: File): Unit = {
          log.debug(s"event: existingFile ${f} ${base} base")
          tk.testActor ! ExistingFile(base, f)
        }
      }
    }
  }

}

class FileWatchServiceSpec extends FileWatcherSpec {
  override def createClassWatcher(base: File)(implicit tk: TestKit): Watcher = {
    val watcherId = UUID.randomUUID()
    ClassWatchService.spawnWatcher(
      watcherId,
      base,
      Set(
        WatcherListener(
          base,
          true,
          Set("class"),
          watcherId
        )
      )
    )
  }

  override def createJarWatcher(jar: File)(implicit tk: TestKit): Watcher = {
    val watcherId = UUID.randomUUID()
    JarWatchService.spawnWatcher(
      watcherId,
      jar,
      Set(
        WatcherListener(
          jar,
          true,
          Set("jar"),
          watcherId
        )
      )
    )
  }
}

object JarWatchService extends BaseWatcher
object ClassWatchService extends BaseWatcher

class BaseWatcher extends SLF4JLogging {
  val fileWatchService: FileWatchService = new FileWatchService

  def spawnWatcher(
    watcherId: UUID,
    baseFile: File,
    listeners: Set[WatcherListener]
  ) = {
    //log.debug(s"watching ${baseFile} watcherID: ${testWatcher.watcherId}")
    fileWatchService.spawnWatcher(watcherId, baseFile, listeners)
  }
}

package object file {
  implicit val DefaultCharset: Charset = Charset.defaultCharset()
  /**
   * WARNING: do not create symbolic links in the temporary directory
   * or the cleanup script will exit the sandbox and start deleting //
   * other files.
   */
  def withTempDir[T](a: File => T): T = {
    // sadly not able to provide a prefix. If we really need the
    // support we could re-implement the Guava method.
    val dir = Files.createTempDir().canon
    try a(dir)
    finally dir.tree.reverse.foreach(_.delete())
  }

  implicit class RichFile(val file: File) extends AnyVal {

    def /(sub: String): File = new File(file, sub)

    def isScala: Boolean = file.getName.toLowerCase.endsWith(".scala")
    def isJava: Boolean = file.getName.toLowerCase.endsWith(".java")
    def isClassfile: Boolean = file.getName.toLowerCase.endsWith(".class")
    def isJar: Boolean = file.getName.toLowerCase.endsWith(".jar")

    def createWithParents(): Boolean = {
      Files.createParentDirs(file)
      file.createNewFile()
    }

    def readLines()(implicit cs: Charset): List[String] = {
      import collection.JavaConversions._
      Files.readLines(file, cs).toList
    }

    def writeLines(lines: List[String])(implicit cs: Charset): Unit = {
      Files.write(lines.mkString("", "\n", "\n"), file, cs)
    }

    def writeString(contents: String)(implicit cs: Charset): Unit = {
      Files.write(contents, file, cs)
    }

    def readString()(implicit cs: Charset): String = {
      Files.toString(file, cs)
    }

    /**
     * @return the file and its descendent family tree (if it is a directory).
     */
    def tree: Stream[File] = {
      import collection.JavaConversions._
      file #:: Files.fileTreeTraverser().breadthFirstTraversal(file).toStream
    }

    /**
     * Non-recursive children of the file.
     */
    def children: Stream[File] =
      Option(file.listFiles()).map(_.toStream).getOrElse(Stream.empty)

    /**
     * Helps to resolve ambiguity surrounding files in symbolically
     * linked directories, which are common on operating systems that
     * use a symbolically linked temporary directory (OS X I'm looking
     * at you).
     *
     * @return the canonical form of `file`, falling back to the absolute file.
     */
    def canon =
      try file.getCanonicalFile
      catch {
        case t: Throwable => file.getAbsoluteFile
      }
  }
}
