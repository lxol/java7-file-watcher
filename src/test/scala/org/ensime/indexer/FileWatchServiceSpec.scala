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

sealed trait FileWatcherMessage

case class Added(f: File) extends FileWatcherMessage
case class Removed(f: File) extends FileWatcherMessage
case class Changed(f: File) extends FileWatcherMessage
case class BaseAdded(f: File) extends FileWatcherMessage
case class BaseRemoved(f: File) extends FileWatcherMessage

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

  /**
   * The Linux ext2+ filesystems have a timestamp precision of 1
   * second, which means its impossible to tell if a newly created
   * file has been modified, or deleted and re-added, if it happens
   * sub-second (without looking at the contents).
   */
  def waitForLinus(): Unit = {
    Thread.sleep(2000)
  }

  val maxWait = 11 seconds

  "FileWatcher" should "detect added files" taggedAs (Retryable) in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        withClassWatcher(dir) { watcher =>
          val foo = (dir / "foo.class")
          val bar = (dir / "b/bar.class")
          val baseCreated: Fish = {
            case BaseAdded(f) => f == dir
            case _ => false
          }

          tk.fishForMessage(maxWait)(baseCreated)

          foo.createWithParents() shouldBe true
          bar.createWithParents() shouldBe true
          val fooOrBarAdded: Fish = {
            case Added(f) => {
              f == foo || f == bar
            }
            case _ => false
          }
          tk.fishForMessage(maxWait)(fooOrBarAdded)
          tk.fishForMessage(maxWait)(fooOrBarAdded)
        }
      }
    }

  it should "detect added / changed files" taggedAs (Retryable) in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        withClassWatcher(dir) { watcher =>
          val foo = (dir / "foo.class")
          val bar = (dir / "b/bar.class")

          foo.createWithParents() shouldBe true
          bar.createWithParents() shouldBe true
          foo.isFile() shouldBe true
          bar.isFile() shouldBe true
          val fooOrBarAdded: Fish = {
            case Added(f) => {
              f == foo || f == bar
            }
            case _ => false
          }
          tk.fishForMessage(maxWait)(fooOrBarAdded)
          tk.fishForMessage(maxWait)(fooOrBarAdded)
          foo.writeString("foo")
          bar.writeString("bar")
          val fooOrBarChanged: Fish = {
            case Changed(f) => {
              f == foo || f == bar
            }
            case _ => false
          }

          tk.fishForMessage(maxWait)(fooOrBarChanged)
          tk.fishForMessage(maxWait)(fooOrBarChanged)
        }
      }
    }

  it should "detect added / removed files" taggedAs (Retryable) in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        withClassWatcher(dir) { watcher =>
          val baseCreated: Fish = {
            case BaseAdded(f) => f == dir
            case _ => false
          }

          tk.fishForMessage(maxWait)(baseCreated)

          val foo = (dir / "foo.class")
          val bar = (dir / "b/bar.class")
          log.debug(s"detect added / removed files ${foo} ${bar}")
          foo.createWithParents() shouldBe true
          bar.createWithParents() shouldBe true
          val fooOrBarAdded: Fish = {
            case Added(f) => {
              f == foo || f == bar
            }
            case _ => false
          }
          tk.fishForMessage(maxWait)(fooOrBarAdded)
          tk.fishForMessage(maxWait)(fooOrBarAdded)

          foo.delete() shouldBe true
          bar.delete() shouldBe true
          val fooOrBarRemoved: Fish = {
            case Removed(f) => {
              f == foo || f == bar
            }
            case _ => false
          }
          tk.fishForMessage(maxWait)(fooOrBarRemoved)
          tk.fishForMessage(maxWait)(fooOrBarRemoved)
        }
      }
    }

  it should "detect removed base directory" taggedAs (Retryable) in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        withClassWatcher(dir) { watcher =>

          dir.delete()

          val baseRemovedAndCreated: Fish = {
            case BaseRemoved(f) => f == dir
            case BaseAdded(f) => f == dir
            case _ => false
          }

          tk.fishForMessage(maxWait)(baseRemovedAndCreated)
          tk.fishForMessage(maxWait)(baseRemovedAndCreated)
        }
      }
    }

  it should "detect removed parent base directory" taggedAs (Retryable) in
    withTestKit { implicit tk =>
      val parent = Files.createTempDir().canon
      val dir = parent / "base"
      dir.mkdirs()
      try {
        withClassWatcher(dir) { watcher =>
          dir.tree.reverse.foreach(_.delete())
          parent.delete()
          val baseRemovedAndCreated: Fish = {
            case BaseRemoved(f) => f == dir
            case BaseAdded(f) => f == dir
            case _ => false
          }
          tk.fishForMessage(maxWait)(baseRemovedAndCreated)
          tk.fishForMessage(maxWait)(baseRemovedAndCreated)

        }
      } finally parent.tree.reverse.foreach(_.delete())
    }

  it should "survive deletion of the watched directory" taggedAs (Retryable) in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        withClassWatcher(dir) { watcher =>
          val foo = (dir / "foo.class")
          val bar = (dir / "b/bar.class")
          log.debug("start: survive deletion of the watched directory")
          foo.createWithParents() shouldBe true
          bar.createWithParents() shouldBe true
          val fooOrBarAdded: Fish = {
            case Added(f) => {
              val addedFile = f
              addedFile == foo || addedFile == bar
            }
            case _ => false
          }
          tk.fishForMessage(maxWait)(fooOrBarAdded)
          tk.fishForMessage(maxWait)(fooOrBarAdded)

          dir.tree.reverse.foreach(_.delete())

          val baseRemovedAndCreated: Fish = {
            case BaseRemoved(f) => f == dir
            case BaseAdded(f) => f == dir
            case _ => false
          }

          tk.fishForMessage(maxWait)(baseRemovedAndCreated)
          tk.fishForMessage(maxWait)(baseRemovedAndCreated)
          Thread.sleep(1000)
          foo.createWithParents() shouldBe true
          bar.createWithParents() shouldBe true

          tk.fishForMessage(maxWait)(fooOrBarAdded)
          tk.fishForMessage(maxWait)(fooOrBarAdded)
          log.debug("end: survive deletion of the watched directory")
        }
      }
    }

  it should "be able to start up from a non-existent directory" taggedAs (Retryable) in
    withTestKit { implicit tk =>
      val root = Files.createTempDir().canon
      val dir = (root / "dir")
      dir.delete()
      try {
        withClassWatcher(dir) { watcher =>
          val foo = (dir / "foo.class")
          val bar = (dir / "b/bar.class")

          foo.createWithParents() shouldBe true
          bar.createWithParents() shouldBe true
          //waitForLinus()
          val fooOrBarAdded: Fish = {
            case Added(f) => {
              f == foo || f == bar
            }
            case _ => false
          }
          tk.fishForMessage(maxWait)(fooOrBarAdded)
          tk.fishForMessage(maxWait)(fooOrBarAdded)
        }
      } finally dir.tree.reverse.foreach(_.delete())
    }

  it should "survive removed parent base directory and recreated base" taggedAs (Retryable) in
    withTestKit { implicit tk =>

      val parent = Files.createTempDir().canon
      val dir = parent / "base"
      dir.mkdirs()
      try {
        withClassWatcher(dir) { watcher =>
          val foo = (dir / "foo.class")
          val bar = (dir / "b/bar.class")

          foo.createWithParents() shouldBe true
          bar.createWithParents() shouldBe true
          //waitForLinus()
          val fooOrBarAdded: Fish = {
            case Added(f) => {
              f == foo || f == bar
            }
            case _ => false
          }
          tk.fishForMessage(maxWait)(fooOrBarAdded)
          tk.fishForMessage(maxWait)(fooOrBarAdded)

          dir.tree.reverse.foreach(_.delete())

          parent.delete()
          val baseRemovedAndCreated: Fish = {
            case BaseRemoved(f) => f == dir
            case BaseAdded(f) => f == dir
            case _ => false
          }

          tk.fishForMessage(maxWait)(baseRemovedAndCreated)
          tk.fishForMessage(maxWait)(baseRemovedAndCreated)

          foo.createWithParents() shouldBe true
          bar.createWithParents() shouldBe true
          //waitForLinus()
          tk.fishForMessage(maxWait)(fooOrBarAdded)
          tk.fishForMessage(maxWait)(fooOrBarAdded)

        }
      } finally dir.tree.reverse.foreach(_.delete())
    }

  //////////////////////////////////////////////////////////////////////////////
  it should "detect changes to a file base" taggedAs (Retryable) in
    withTestKit { implicit tk =>
      withTempDir { dir =>

        val jar = (dir / "jar.jar")
        jar.createWithParents() shouldBe true

        withJarWatcher(jar) { watcher =>
          log.debug(s"detect changes to a file base ${jar}")
          Thread.sleep(500) //time to register before testing the chnages
          jar.writeString("binks")
          val jarChanged: Fish = {
            case Changed(f) => f == jar
            case _ => false
          }
          tk.fishForMessage(maxWait)(jarChanged)

        }
      }
    }

  it should "detect removal of a file base" taggedAs (Retryable) in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        val root = (dir / "root")
        val jar = (root / "jar.jar")
        jar.createWithParents() shouldBe true

        withJarWatcher(jar) { watcher =>
          jar.delete()
          val jarRemoved: Fish = {
            case Removed(f) => f == jar
            case _ => false
          }
          tk.fishForMessage(maxWait)(jarRemoved)
        }
      }
    }

  it should "be able to start up from a non-existent base file" taggedAs (Retryable) in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        val jar = (dir / "jar.jar")
        withJarWatcher(jar) { watcher =>
          jar.createWithParents() shouldBe true
          val jarAdded: Fish = {
            case Added(f) => f == jar
            case _ => false
          }
          tk.fishForMessage(maxWait)(jarAdded)
        }
      }
    }

  it should "survive removal of a file base" taggedAs (Retryable) in
    withTestKit { implicit tk =>
      withTempDir { root =>
        val dir = (root / "base")
        val jar = (dir / "jar.jar")

        log.debug(s"survive removal of a file base ${jar}")
        jar.createWithParents() shouldBe true

        withJarWatcher(jar) { watcher =>

          jar.delete() shouldBe true
          log.debug(s"survive deleted a file ${jar}")

          val jarRemoved: Fish = {
            case Removed(f) => f == jar
            case _ => false
          }
          tk.fishForMessage(maxWait)(jarRemoved)

          jar.writeString("binks")
          jar.exists shouldBe true
          val jarAdded: Fish = {
            case Added(f) => f == jar
            case _ => false
          }
          tk.fishForMessage(maxWait)(jarAdded)
          log.debug(s"end of survive removal of a file base ${jar}")
        }
      }
    }

  it should "survive removal of a parent of a file base" taggedAs (Retryable) in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        val jar = (dir / "parent" / "jar.jar")
        jar.createWithParents() shouldBe true
        withJarWatcher(jar) { watcher =>
          dir.tree.reverse.foreach(_.delete())
          log.debug(s"deleted ${dir}")
          val jarRemoved: Fish = {
            case Removed(f) => f == jar
            case _ => false
          }
          tk.fishForMessage(maxWait)(jarRemoved)
          log.debug(s"before created ${jar}")
          jar.createWithParents() shouldBe true
          log.debug(s"created ${jar}")
          val jarAdded: Fish = {
            case Added(f) => f == jar
            case _ => false
          }
          tk.fishForMessage(maxWait)(jarAdded)

        }
      }
    }

  it should "be able to start up from a non-existent grandparent of a base file" taggedAs (Retryable) in
    withTestKit { implicit tk =>
      withTempDir { dir =>
        val jar = (dir / "top" / "grand" / "parent" / "jar.jar")
        (dir / "top").tree.reverse.foreach(_.delete())
        withJarWatcher(jar) { watcher =>

          jar.createWithParents() shouldBe true
          val jarAdded: Fish = {
            case Added(f) => f == jar
            case _ => false
          }
          tk.fishForMessage(maxWait)(jarAdded)

        }
      }
    }

  //////////////////////////////////////////////////////////////////////////////
  type -->[A, B] = PartialFunction[A, B]
  type Fish = PartialFunction[Any, Boolean]

  def withClassWatcher[T](base: File)(code: Watcher => T)(implicit tk: TestKit) = {
    val w = createClassWatcher(base)
    waitForLinus()
    try code(w)
    finally w.shutdown()
  }

  def withJarWatcher[T](jar: File)(code: Watcher => T)(implicit tk: TestKit) = {
    val w = createJarWatcher(jar)
    waitForLinus()
    try code(w)
    finally w.shutdown()
  }

  def listeners(implicit tk: TestKit) = Set(
    new TestListener {
      def fileAdded(f: File): Unit = { tk.testActor ! Added(f) }
      def fileRemoved(f: File): Unit = { tk.testActor ! Removed(f) }
      def fileChanged(f: File): Unit = { tk.testActor ! Changed(f) }
      override def baseReCreated(f: File): Unit = { tk.testActor ! BaseAdded(f) }
      override def baseRemoved(f: File): Unit = { tk.testActor ! BaseRemoved(f) }
    }
  )

}

class FileWatchServiceSpec extends FileWatcherSpec {
  override def createClassWatcher(base: File)(implicit tk: TestKit): Watcher = {
    ClassWatcher.register(base, Set("class"), true, listeners)
  }

  override def createJarWatcher(jar: File)(implicit tk: TestKit): Watcher =
    JarWatcher.register(jar, Set("jar"), false, listeners)
}
trait TestListener {
  def fileAdded(f: File): Unit
  def fileRemoved(f: File): Unit
  def fileChanged(f: File): Unit
  def baseReCreated(f: File): Unit = {}
  def baseRemoved(f: File): Unit = {}
}

object JarWatcher extends BaseWatcher
object ClassWatcher extends BaseWatcher

class BaseWatcher extends SLF4JLogging {
  val fileWatchService: FileWatchService = new FileWatchService
  val testWatcher = fileWatchService.spawnWatcher()
  def register(
    baseFile: File,
    selector: Set[String],
    rec: Boolean,
    listeners: Set[TestListener]
  ) = {
    log.debug(s"watching ${baseFile}")
    val s = listeners.map {
      l: TestListener =>
        new WatcherListener() {
          override val base = baseFile
          override val recursive = rec
          override val extensions = selector
          override val treatExistingAsNew = true
          override val watcherId = testWatcher.watcherId

          override def fileCreated(f: File) = {
            log.debug(s"fileAdded ${f}")
            l.fileAdded(f)
          }
          override def fileDeleted(f: File) = {
            log.debug(s"fileDeleted ${f}")
            l.fileRemoved(f)
          }
          override def fileModified(f: File) = {
            log.debug(s"fileModified ${f}")
            l.fileChanged(f)
          }
          override def baseReCreated(f: File) = {
            log.debug(s"baseReCreated ${f}")
            l.baseReCreated(f)
          }
          override def baseRemoved(f: File) = {
            log.debug(s"baseRemoved ${f}")
            l.baseRemoved(f)
          }
        }
    }
    testWatcher.register(baseFile, s.toSet)
    testWatcher
  }
}

package object file {
  implicit val DefaultCharset: Charset = Charset.defaultCharset()
  /**
   * WARNING: do not create symbolic links in the temporary directory
   * or the cleanup script will exit the sandbox and start deleting
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
