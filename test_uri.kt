import java.net.URI
fun main() {
    val q = "https://example.com/?pubKey=hello%2Bworld+stuff"
    val uri = URI.create(q)
    val query = uri.query
    println("Query: " + query)
}
