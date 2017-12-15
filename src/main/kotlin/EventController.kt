import io.netty.handler.codec.http.HttpResponseStatus
import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import java.util.Date
import kotlin.coroutines.experimental.buildSequence

object EventController : BaseController() {

    fun Event.toJson(): EventGet {
        var venueName: String? = null
        val actualVenue = (venue ?: production.venue)
        if (actualVenue != null) {
            venueName = Venues.slice(Venues.name).select { Venues.id.eq(actualVenue) }.firstOrNull()?.get(Venues.name)
        }
        return EventGet(id.value, name, production.id.value, production.name, date.toDate(),
                productionVenue = production.venue?.value,
                venueOverride = venue?.value,
                venueName = venueName, cast = null)
    }

    override fun updateObj(it: RoutingContext, id: Int?) {
        val ev = mapper.readValue<EventPost>(it.bodyAsString, EventPost::class.java)
        transaction {

            fun Event.upd() {
                name = ev.name
                production = Production[1]
                venue = if (ev.venueOverride != null) EntityID(ev.venueOverride, Venues) else null
                date = DateTime(ev.date)
            }

            val event = if (id == null) {
                val newEvent = Event.new {
                    upd()
                }
                newEvent
            } else {
                val event = Event[id]
                event.upd()
                event
            }

            if (ev.castOverride != null) {
                EventPartAssignments.deleteWhere { EventPartAssignments.event.eq(event.id) }
                for (r in ev.castOverride) {
                    EventPartAssignments.insert {
                        it[artist] = artist.fromInt(r.artist)
                        it[part] = part.fromInt(r.part)
                        it[EventPartAssignments.event] = event.id
                    }
                }
            }

            it.respond(event.toJson())
        }

    }

    data class EventGet(val id: Int, val name: String, val productionId: Int, val productionName: String?, val date: Date,
                        val productionVenue: Int?,
                        val venueOverride: Int?,
                        val venueName: String?,
                        val cast: List<ProdController.PartAssignmentNamed>? = null)

    data class EventPost(val name: String, val productionId: Int, val date: Date, val venueOverride: Int?,
                         val castOverride: List<ProdController.PartAssignment>)

    fun createRoute(vertx: Vertx): Router {

        fun getEvent(resultRow: ResultRow, castlist: List<ProdController.PartAssignmentNamed>?) =
                EventGet(resultRow[Events.id].value, resultRow[Events.name],
                        productionName = resultRow[Productions.name], productionId = resultRow[Productions.id].value,
                        date = resultRow[Events.date].toDate(), venueOverride = resultRow[Events.venueOverride]?.value,
                        productionVenue = resultRow[Productions.venue]?.value,
                        venueName = resultRow[Venues.name],
                        cast = castlist)

        val eventRouter = Router.router(vertx)
        eventRouter.get("/:eventkey").handler {
            val eventID = it.pathParam("eventkey").toInt()



            if (eventID == null) {
                it.fail(HttpResponseStatus.NOT_FOUND.code())
                return@handler
            }

            transaction {
                val venueOverride = Event[eventID].venue != null
                val event = Events.leftJoin(Productions, onColumn = { production },
                        otherColumn = { Productions.id }).
                        leftJoin(Venues, onColumn = { if (venueOverride) Events.venueOverride else Productions.venue },
                                otherColumn = { id }).
                        select { Events.id.eq(eventID) }.first()

                val prodId = event[Events.production]
                val prodcast = ProductionPartAssignments.leftJoin(Artists).leftJoin(Parts).select { ProductionPartAssignments.production.eq(prodId) }

                val castlist = prodcast.map { row ->
                    ProdController.PartAssignmentNamed(row[ProductionPartAssignments.part].value,
                            row[ProductionPartAssignments.artist].value,
                            row[Parts.name], row[Artists.name])
                }.toMutableList()


                //check out the overrided parts
                val eventCast = EventPartAssignments.leftJoin(Artists).leftJoin(Parts).select { EventPartAssignments.event.eq(eventID) }

                for (row in eventCast) {
                    val first = castlist.find { it.part == row[EventPartAssignments.part].value }
                    if (first == null) {
                        EventController.logger.error("Part not found in production!")
                        continue
                    }
                    val updated = first.copy(
                            artist = row[EventPartAssignments.artist].value,
                            override = true,
                            artistName = row[Artists.name])

                    castlist.remove(first)
                    castlist.add(updated)

                }

                it.respond(getEvent(event, castlist))
            }
        }

        fun RoutingContext.pathParam(name: String, default: Int): Int {
            val pathParam = pathParam(name)
            if (pathParam != null)
                return pathParam.toInt()
            return default
        }


        fun getHandler(it: RoutingContext) {


            transaction {

                val seq = buildSequence {
                    val location = it.request().getParam("location")

                    //val date=DateTime.now().withTimeAtStartOfDay()

                    val dateParam = it.request().getParam("date")
                    val date: DateTime? = if (dateParam == null) null else dformat.parseLocalDate(dateParam).toDateTimeAtStartOfDay()
                    var op = if (location != null) {


//                val query=
//                    Venues.innerJoin(Events).select {
//                        var q=Venues.locationLat.between(lat - distance, lat + distance) and
//                                Venues.locationLon.between(lon - distance, lon + distance)
//                        if (date!=null)
//                            q= q.and(Events.date.eq(date))
//                        q
//                    }

                        Op.build {
                            val (lat, lon) = location.split(",").map { it.toFloat() }
                            val distance = 1f
                            var q = Venues.locationLat.between(lat - distance, lat + distance) and
                                    Venues.locationLon.between(lon - distance, lon + distance)

                            q
                        }

                        //Between(Events.locationLat,LiteralOp<Float>(FloatColumnType(),lat-distance),lat+distance)
                    } else null


                    if (date != null) {
                        val timeOp = Op.build { Events.date.between(date, date.plusDays(1)) }

                        op = (if (op != null) op.and(timeOp)
                        else timeOp)
                    }


                    val join = Events.leftJoin(Productions, { production }, { id })
                            .leftJoin(Venues, { Events.venueOverride }, { id });
                    val query = (if (op != null) join.select(op) else join.selectAll()).
                            limit(it.limit(),it.offset())


                    for (resultRow in query) {
                        val ev = getEvent(resultRow, null)
                        yield(ev)
                    }

                }

                it.response().arrayResponse(seq.iterator())

            }
        }
        eventRouter.get().handler(::getHandler)
        addDefaultRoutes(eventRouter)

        return eventRouter
    }

    val logger = LoggerFactory.getLogger(javaClass)
}