import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.coroutines.experimental.buildSequence

object VenueController : BaseController() {


    data class VenueGet(val id: Int, val name: String, val lat: Float?, val lon: Float?,
                        val url:String?)

    fun getVenue(resultRow: ResultRow) = VenueGet(resultRow[Venues.id].value, resultRow[Venues.name],
            resultRow[Venues.locationLat], resultRow[Venues.locationLon],
            resultRow[Venues.url])

    fun createRoute(vertx: Vertx): Router {

        val productionRouter = Router.router(vertx)

        productionRouter.run {

            get("/:id").handler {
                transaction {
                    val id = it.pathParam("id").toInt()
                    val o = Venues.select({ Venues.id.eq(id) }).first()
                    it.respond(getVenue(o))
                }
            }
            get().handler {

                transaction {

                    val res = buildSequence {
                        val startsWith = it.queryParam("startsWith").firstOrNull()
                        val res = if (startsWith != null) Venues.select({ Venues.name.upperCase().like(startsWith.toUpperCase() + "%") }) else Venues.selectAll()

                        for (p in res.limit(it.limit(), it.offset())) {
                            yield(getVenue(p))
                        }

                    }
                    it.response().arrayResponse(res.iterator())
                }
            }



            addDefaultRoutes(this)
        }
        return productionRouter
    }

    override fun updateObj(it: RoutingContext, id: Int?) {
        val ev = mapper.readValue<VenueGet>(it.bodyAsString, VenueGet::class.java)

        transaction {


            fun Venues.upd(it: UpdateBuilder<Int>) {
                it[url]=ev.url
                it[name] = ev.name
                it[locationLat] = ev.lat
                it[locationLon] = ev.lon
            }

            val key = if (id == null) Venues.insertAndGetId {
                upd(it)
            } else {
                val res=Venues.update({ Venues.id.eq(id) }, body = {
                    upd(it)
                })
                if (res!=1)
                    throw IllegalStateException("Bad id")
                EntityID(id, Venues)
            }


            if (key != null) {
                it.respond(ev.copy(id = key.value))
            } else
                it.fail(HttpResponseStatus.INTERNAL_SERVER_ERROR.code())
        }
    }
}