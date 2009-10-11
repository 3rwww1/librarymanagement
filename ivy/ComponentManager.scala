package xsbt

import java.io.{File,FileOutputStream}
import ComponentManager.lock

/** A component manager provides access to the pieces of xsbt that are distributed as components.
* There are two types of components.  The first type is compiled subproject jars with their dependencies.
* The second type is a subproject distributed as a source jar so that it can be compiled against a specific
* version of Scala.
*
* The component manager provides services to install and retrieve components to the local repository.
* This is used for compiled source jars so that the compilation need not be repeated for other projects on the same
* machine.
*/
class ComponentManager(provider: xsbti.ComponentProvider, val log: IvyLogger) extends NotNull
{
	/** Get all of the files for component 'id', throwing an exception if no files exist for the component. */
	def files(id: String)(ifMissing: IfMissing): Iterable[File] =
	{
		def fromGlobal =
			lockGlobalCache {
				try { update(id); getOrElse(createAndCache) }
				catch { case e: NotInCache => createAndCache }
			}
		def getOrElse(orElse: => Iterable[File]) =
		{
			val existing = provider.component(id)
			if(existing.isEmpty) orElse else existing
		}
		def notFound = invalid("Could not find required component '" + id + "'")
		def createAndCache =
			ifMissing match {
				case IfMissing.Fail => notFound
				case d: IfMissing.Define =>
					d()
					if(d.cache) cache(id)
					getOrElse(notFound)
			}
		
		lockLocalCache { getOrElse(fromGlobal) }
	}
	private def lockLocalCache[T](action: => T): T = lock("local cache", provider.lockFile, log) ( action )
	private def lockGlobalCache[T](action: => T): T = lock("global cache", IvyCache.lockFile, log)( action )
	/** Get the file for component 'id', throwing an exception if no files or multiple files exist for the component. */
	def file(id: String)(ifMissing: IfMissing): File =
		files(id)(ifMissing).toList match {
			case x :: Nil => x
			case xs => invalid("Expected single file for component '" + id + "', found: " + xs.mkString(", "))
		}
	private def invalid(msg: String) = throw new InvalidComponent(msg)
	private def invalid(e: NotInCache) = throw new InvalidComponent(e.getMessage, e)

	def define(id: String, files: Iterable[File]) = lockLocalCache { provider.defineComponent(id, files.toSeq.toArray) }
	/** Retrieve the file for component 'id' from the local repository. */
	private def update(id: String): Unit = IvyCache.withCachedJar(sbtModuleID(id), log)(jar => define(id, Seq(jar)) )

	private def sbtModuleID(id: String) = ModuleID("org.scala-tools.sbt", id, xsbti.Versions.Sbt)
	/** Install the files for component 'id' to the local repository.  This is usually used after writing files to the directory returned by 'location'. */
	def cache(id: String): Unit = IvyCache.cacheJar(sbtModuleID(id), file(id)(IfMissing.Fail), log)
	def clearCache(id: String): Unit = lockGlobalCache { IvyCache.clearCachedJar(sbtModuleID(id), log) }
}
class InvalidComponent(msg: String, cause: Throwable) extends RuntimeException(msg, cause)
{
	def this(msg: String) = this(msg, null)
}
sealed trait IfMissing extends NotNull
object IfMissing
{
	object Fail extends IfMissing
	final class Define(val cache: Boolean, define: => Unit) extends IfMissing { def apply() = define }
}
object ComponentManager
{
	def lock[T](label: String, file: File, log: IvyLogger)(action: => T): T =
	{
		synchronized {
			val channel = new FileOutputStream(file).getChannel
			try {
				val freeLock = channel.tryLock
				val lock = if(freeLock eq null) { log.info("Waiting for " + label + " to be available..."); channel.lock } else freeLock
				try { action }
				finally { lock.release() }
			}
			finally { channel.close() }
		}
	}
}