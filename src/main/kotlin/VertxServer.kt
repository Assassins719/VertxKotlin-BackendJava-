import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.http.HttpServerResponse
import io.vertx.core.json.JsonObject
import io.vertx.ext.auth.KeyStoreOptions
import io.vertx.ext.auth.jwt.JWTAuth
import io.vertx.ext.auth.jwt.JWTAuthOptions
import io.vertx.ext.auth.jwt.JWTOptions
import io.vertx.ext.auth.oauth2.OAuth2Auth
import io.vertx.ext.auth.oauth2.OAuth2ClientOptions
import io.vertx.ext.auth.oauth2.OAuth2FlowType
import io.vertx.ext.auth.oauth2.providers.FacebookAuth
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.handler.JWTAuthHandler
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.ext.web.templ.ThymeleafTemplateEngine
import io.vertx.kotlin.ext.auth.oauth2.OAuth2ClientOptions
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.thymeleaf.cache.StandardCacheManager
import java.lang.Exception
import java.net.URI
import java.sql.SQLException
import java.text.SimpleDateFormat

fun HttpServerResponse.arrayResponse(f: () -> Unit) {
    isChunked = true
    write("""{"data":[""")
    f()
    end("]}")
}

fun HttpServerResponse.arrayResponse(f: Iterator<Any>) {
    isChunked = true
    write("""{"data":[""")
    for (elem in f) {

        write(mapper.writeValueAsString(elem))
        if (f.hasNext())
            write(",\n")
    }
    end("]}")
}


//    companion object {
//        fun newEvent(ev:Event): GetEvent {
//            return GetEvent(ev.name,ev.production.name,ev.production.id.value,ev.d)
//        }
//
//    }


open abstract class BaseController {
    companion object {
        val templateEngine = ThymeleafTemplateEngine.create()

        init {
            val standardCacheManager = StandardCacheManager()
            standardCacheManager.templateCacheMaxSize = 0
            templateEngine.thymeleafTemplateEngine.cacheManager = standardCacheManager
        }
    }

    fun RoutingContext.pathParam(name: String, default: Int): Int {
        val pathParam = pathParam(name)
        if (pathParam != null)
            return pathParam.toInt()
        return default
    }

    fun RoutingContext.respond(obj: Any) {
        response().end(mapper.writeValueAsString(obj))
    }

    fun RoutingContext.limit(): Int {
        return request().getParam("limit")?.toInt() ?: 10
    }

    fun RoutingContext.offset(): Int {
        return request().getParam("offset")?.toInt() ?: 0
    }

    fun RoutingContext.qParam(p: String): String? {
        return request().getParam(p)
    }

    abstract fun updateObj(it: RoutingContext, id: Int? = null);


    data class Error(val status:Int, val title:String)
    data class ErrorResponse(val errors: List<Error>){
        constructor(status:Int,title: String) : this(listOf(Error(status,title)))
    }
    enum class OD_ErrorCodes() {
        OK,
        DatabaseError,
        ObjectNotFound,
        Unknown
    }
    fun RoutingContext.fail(error:OD_ErrorCodes){
        response().end(mapper.writeValueAsString(ErrorResponse(error.ordinal, error.name)))
    }

    fun RoutingContext.replyFail(error:Exception){
        response().end(mapper.writeValueAsString(ErrorResponse(OD_ErrorCodes.Unknown.ordinal, error.message?:"undefined")))
    }
    fun addDefaultRoutes(router: Router) {



        router.apply {
            put("/:id").handler {

                try {
                val id = it.pathParam("id").toInt()
                updateObj(it, id)
                }
                catch (e: SQLException){
                    it.response().end(mapper.writeValueAsString(ErrorResponse(1, e.message?:"SQL error")))
                }
                catch (e:java.lang.IllegalStateException){
                    it.response().end(mapper.writeValueAsString(ErrorResponse(OD_ErrorCodes.DatabaseError.ordinal, e.message?:"SQL error")))
                }


            }

            post().handler {
                updateObj(it)
            }
        }
    }
}

fun RoutingContext.respond(obj: Any) {
    response().end(mapper.writeValueAsString(obj))
}

val rfc3339format = "yyyy-MM-dd'T'HH:mm:ssZ";
val isoformat = "yyyy-MM-dd'T'HH:mm:ss.SSSX";
val dateformat = SimpleDateFormat(
        isoformat);
val dformat = DateTimeFormat.forPattern("yyyy-MM-dd")
val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).setDateFormat(dateformat)
fun main(args: Array<String>) {


    Database.connect("jdbc:h2:~/test", driver = "org.h2.Driver")
    //val db=Database.connect("jdbc:h2:mem:test", driver = "org.h2.Driver")


    createTables()

    createDummyData()
    transaction {
        val events = Event.all().toList()
        println(events)
    }

    val vertx = Vertx.vertx()
    val server = vertx.createHttpServer()

    val router = Router.router(vertx)
    // Will only accept GET requests from origin "vertx.io"
    router.route().handler(CorsHandler.create("*").allowedMethod(HttpMethod.POST))

    val staticroute = router.route("/webapp/*")
    staticroute.handler(StaticHandler.create("webapp/dist"))
    staticroute.failureHandler({ context ->
        if (!context.request().path().contains('.'))
            context.reroute("/webapp/index.html")
        else
            context.fail(HttpResponseStatus.NOT_FOUND.code())
    })


    // This body handler will be called for all routes
    router.route().handler(BodyHandler.create())

    router.route("/test/:id").handler {
        val p = it.pathParam("id")
        it.response().end(p)
    }
    router.get("/v1/event/*").produces("application/json").handler {
        it.response().putHeader("content-type", "application/json")
        it.next()
    }

    val eventRouter = EventController.createRoute(vertx)
    router.mountSubRouter("/v1/events", eventRouter)

    abstract class MyHandler : Handler<RoutingContext> {

    }
//    fun myhandler(handler : MyHandler.(RoutingContext?) -> Unit ):MyHandler{
//        return object : MyHandler() {
//            override fun handle(event: RoutingContext?) {
//                handler(event)
//            }
//
//        }
//    }


    fun Route.myhandler(h: MyHandler): Route {
        return this
    }


    val productionRouter = ProdController(vertx).createRoute()

    router.mountSubRouter("/v1/productions", productionRouter)

    router.mountSubRouter("/v1/works", WorkController.createRoute(vertx))
    router.mountSubRouter("/v1/venues", VenueController.createRoute(vertx))
    router.mountSubRouter("/v1/artists", ArtistController.createRoute(vertx))



    val authConfig = JsonObject("""{
        "keyStore":{
                "type" : "jceks",
                "path" : "keystore.jceks",
                "password" : "secret"
                }
                }""")
    val opt= JWTAuthOptions().setKeyStore(KeyStoreOptions().setType("jceks").setPassword("secret").setPath("keystore.jceks"))
    var authProvider = JWTAuth.create(vertx, opt)

    router.route("/login").handler({ ctx ->

        val request = ctx.request()
        val provider= request.getParam("provider")
        if (provider=="facebook"){
            val accessToken=request.getParam("accessToken")
            //verify
            //get https://graph.facebook.com/me?fields=email,name&access_token=<accesstoken>
            val url = URI("https://graph.facebook.com/me?fields=email,name&access_token=${accessToken}")
            val req=vertx.createHttpClient(HttpClientOptions().setSsl(true).setDefaultPort(443)).get(url.host,
                    url.path,{ response ->
                response.bodyHandler({event: Buffer? ->
                    val body=event?.toJsonObject()
                    val email = body?.getString("email")
                    if (email!=null){
                        //we're good
                    }
                    else{
                        ctx.fail(HttpResponseStatus.UNAUTHORIZED.code())
                    }
                 })
            })
            req.exceptionHandler {

            }
            req.end()

            return@handler
        }
        // this is an example, authentication should be done with another provider...
        if ("paulo" == request.getParam("username") &&
                "secret" == request.getParam("password")) {
            ctx.response().end(authProvider.generateToken(
                    JsonObject(mapOf("sub" to "paulo")) , JWTOptions()))
        } else {
            ctx.fail(401)
        }
    })
    router.route("/protected/*").handler(JWTAuthHandler.create(authProvider))

    router.get("/protected/*").handler { it ->
        println(it.user().principal().getString("sub"))
        it.respond("ga!")
    }

    server.requestHandler(Handler {
        router.accept(it)
    })

    val fbauth=FacebookAuth.create(vertx,"1803627816601907","89b116d65b51765e32544bc9bb839476")


    server.listen(8080)
}

private fun createDummyData() {
    transaction {


        val ove = Work.new {
            name = "ove"
        }
        val part_hamlet = Parts.insertAndGetId {
            it[name] = "King Hamlet"
            it[work] = ove.id
        }

        val artistId = Artists.insertAndGetId {
            it[name] = "William Shakespeare"
            it[traitComposer] = true
            it[traits]=ArtistTrait.Composer.toMask()
        }

        val artist_august = Artists.insertAndGetId {
            it[name] = "August"
            it[url] = "http://august.kicks.ass"
            it[traits]=ArtistTrait.Singer.toMask()
        }
        AuthorRoles.insertAndGetId {
            it[artist] = artistId!!;
            it[work] = ove.id
            it[name] = "Author"
        }
        val malmoopera = Venues.insertAndGetId {
            it[locationLat] = 55f
            it[locationLon] = 13f
            it[name] = "Malmoe opera"
        }

        val prod1 = Production.new {
            name = "New production"
            venue = malmoopera!!
        }

        Production2Work.insert {
            it[production] = prod1.id
            it[work] = ove.id
        }


        ProductionPartAssignments.insert {
            it[EventPartAssignments.artist] = artist_august!!
            it[part] = part_hamlet!!
            it[production]=prod1.id
        }

//        Event.new {
//            name = "testprod"
//            production = prod1
//
//        }

        val event1 = Events.insertAndGetId {
            it[name] = "myevent"
            it[production] = prod1.id
            it[date] = DateTime.now()
            it[venueOverride] = malmoopera
        }
        val event2 = Events.insertAndGetId {
            it[name] = "ev2"
            it[production] = prod1.id
            it[date] = DateTime.now().plusDays(1)
        }
        val henningvs = Artists.insertAndGetId {
            it[name] = "henningvs"
            it[traitComposer] = false
            it[url] = "henningvs.com"
            it[traits]=ArtistTrait.Pianist.toMask()
        }

        EventPartAssignments.insert {
            it[EventPartAssignments.artist] = henningvs!!
            it[part] = part_hamlet!!
            it[event]=event1!!
        }

    }
}
