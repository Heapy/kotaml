#!/usr/bin/env kotlinr

/** Preview the static site on loopback using the JDK HTTP server. Stop with Ctrl+C. */
import com.sun.net.httpserver.SimpleFileServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.io.path.isDirectory

require(args.size <= 2) { "Usage: kotlinr docs/serve.main.kts [port] [site-directory]" }
val port = args.firstOrNull()?.let {
    requireNotNull(it.toIntOrNull()?.takeIf { number -> number in 1..65535 }) { "Port must be between 1 and 65535" }
} ?: 8000
val site = Path.of(args.getOrNull(1) ?: "docs/site").toAbsolutePath().normalize()
require(site.isDirectory()) { "Site directory does not exist: $site" }
val server = SimpleFileServer.createFileServer(
    InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
    site,
    SimpleFileServer.OutputLevel.INFO,
)
Runtime.getRuntime().addShutdownHook(Thread { server.stop(0) })
server.start()
println("Preview: http://127.0.0.1:$port/ — serving $site (Ctrl+C to stop)")
