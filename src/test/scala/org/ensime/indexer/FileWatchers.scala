// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.indexer

import akka.event.slf4j.SLF4JLogging
import com.google.common.io.Files
import file._
import java.io.{ File => JFile, _ }
import java.nio.charset.Charset
import java.util.regex.Pattern
import java.util.zip.ZipFile
import org.apache.commons.vfs2.{ _ }
import org.apache.commons.vfs2.impl._
import org.apache.commons.vfs2.provider.jar.JarFileSystem
import org.apache.commons.vfs2.provider.local.LocalFileSystem
import org.apache.commons.vfs2.provider.zip.ZipFileSystem
import scala.Predef.{ any2stringadd => _, _ }
import scala.collection.Set
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.implicitConversions

trait FileChangeListener {
  def fileAdded(f: FileObject): Unit
  def fileRemoved(f: FileObject): Unit
  def fileChanged(f: FileObject): Unit
  def baseReCreated(f: FileObject): Unit = {}
  def baseRemoved(f: FileObject): Unit = {}
}

trait Watcher {
  def shutdown(): Unit
}

/**
 * Decorate `java.io.File` with functionality from common utility
 * packages, which would otherwise be verbose/ugly to call directly.
 *
 * Its nicer to put conveniences for working with `File` here
 * instead of using static accessors from J2SE or Guava.
 */
package object file {
  type File = JFile

  /**
   * Convenience for creating `File`s (which we do a lot), but has the
   * caveat that static methods on `java.io.File` can no longer be
   * accessed, so it must be imported like:
   *
   *   `java.io.{ File => JFile }`
   */
  def File(name: String): File = new File(name)

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

  def withTempFile[T](a: File => T): T = {
    val file = JFile.createTempFile("ensime-", ".tmp").canon
    try a(file)
    finally file.delete()
  }

  implicit class RichFile(val file: File) extends AnyVal {

    def /(sub: String): File = new File(file, sub)

    def isScala: Boolean = file.getName.toLowerCase.endsWith(".scala")
    def isJava: Boolean = file.getName.toLowerCase.endsWith(".java")
    def isClassfile: Boolean = file.getName.toLowerCase.endsWith(".class")
    def isJar: Boolean = file.getName.toLowerCase.endsWith(".jar")

    def parts: List[String] =
      file.getPath.split(
        Pattern.quote(JFile.separator)
      ).toList.filterNot(Set("", "."))

    def outputStream(): OutputStream = new FileOutputStream(file)

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

abstract class ExtSelector extends FileSelector {
  def includeFile(f: FileObject): Boolean = include(f.getName.getExtension)
  def includeFile(info: FileSelectInfo): Boolean = includeFile(info.getFile)
  def includeFile(f: File): Boolean = include.exists(f.getName.endsWith(_))
  def traverseDescendents(info: FileSelectInfo) = true
  def include: Set[String]
}

// intended to be used when watching a single jar file
object JarSelector extends ExtSelector {
  val include = Set("jar")
  override def traverseDescendents(info: FileSelectInfo) = false
}

object ClassfileSelector extends ExtSelector {
  val include = Set("class")
}

object SourceSelector extends ExtSelector {
  val include = Set("scala", "java")
}

object EnsimeVFS {
  def apply(): EnsimeVFS = {
    // avoid classloader race conditions
    require(classOf[LocalFileSystem] != null)
    require(classOf[ZipFileSystem] != null)
    require(classOf[JarFileSystem] != null)

    val vfsInst = new StandardFileSystemManager()
    vfsInst.init()
    vfsInst
  }
}

object `package` {
  type EnsimeVFS = DefaultFileSystemManager

  // the alternative is a monkey patch, count yourself lucky
  private val zipFileField = classOf[ZipFileSystem].getDeclaredField("zipFile")
  zipFileField.setAccessible(true)

  implicit class RichVFS(val vfs: DefaultFileSystemManager) extends AnyVal {
    implicit def toFileObject(f: File): FileObject = vfile(f)

    private def withContext[T](msg: String)(t: => T): T = try { t } catch {
      case e: FileSystemException => throw new FileSystemException(e.getMessage + " in " + msg, e)
    }

    def vfile(name: String) = withContext(s"$name =>")(
      vfs.resolveFile(name.intern)
    )
    def vfile(file: File) = withContext(s"$file =>")(
      vfs.toFileObject(file)
    )
    def vres(path: String) = withContext(s"$path =>")(
      vfs.resolveFile(("res:" + path).intern)
    )
    def vjar(jar: File) = withContext(s"$jar =>")(
      vfs.resolveFile(("jar:" + jar.getAbsolutePath).intern)
    )
    def vjar(jar: FileObject) = withContext(s"$jar =>")(
      vfs.resolveFile(("jar:" + jar.getName.getURI).intern)
    )

    // WORKAROUND https://issues.apache.org/jira/browse/VFS-594
    def nuke(jar: FileObject) = {
      jar.close()

      val fs = jar.getFileSystem

      // clearing an entry from the cache doesn't close it
      val zip = zipFileField.get(fs)
      if (zip != null)
        zip.asInstanceOf[ZipFile].close()

      vfs.getFilesCache.clear(fs)
    }
  }
}
