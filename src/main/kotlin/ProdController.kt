import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.coroutines.experimental.buildSequence

class ProdController(val vertx: Vertx) : BaseController() {

    data class PartAssignment(val part:Int, val artist: Int){
        fun toNamed()=PartAssignmentNamed(part,artist,null, null)
    }
    data class PartAssignmentNamed(val part:Int, val artist: Int,
                                   val partname: String? =null, val artistName: String? =null,
                                   val override: Boolean=false);

    data class NewProd(val name: String, val works: List<Int>,val venue:Int?,
                       val cast:List<PartAssignment>?)

    data class Work(val id: Int, val name: String?)
    data class NamedObj(val id: Int, val name: String? = null)
    data class GetObj(val id: Int, val name: String, val works: List<Work>?, val venue: NamedObj?,
                      val cast:List<PartAssignmentNamed>?)

    fun Production.toGet(worklist: List<Work>? = null, cast:List<PartAssignmentNamed>?=null,
                         venue: NamedObj?): GetObj {
        val venue1 = venue

        return GetObj(id.value, name, worklist,
                venue,cast)
    }

    fun createRoute(): Router {

        val productionRouter = Router.router(vertx)

        productionRouter.run {

            get("/:prodId").handler {
                transaction {
                    val prodId = it.pathParam("prodId").toInt()
//                    val production = Productions.leftJoin(Works,{Productions.workId},{Works.id}).
//                            select({Productions.id.eq(prodId)})
                    val production1 = Production[prodId]
                    val works = Production2Work.leftJoin(Works).select { Production2Work.production.eq(prodId) }
                    val worklist = works.map { Work(it[Works.id].value, it[Works.name]) }


                    val cast = ProductionPartAssignments.leftJoin(Artists).leftJoin(Parts).select { ProductionPartAssignments.production.eq(prodId) }
                    val castlist=cast.map { row ->
                        PartAssignmentNamed(row[ProductionPartAssignments.part].value,
                                row[ProductionPartAssignments.artist].value,
                            row[Parts.name],row[Artists.name])
                    }
                    val venue=if (production1.venue!=null){
                        val row = Venues.select { Venues.id.eq(production1.venue) }.firstOrNull()

                        if (row!=null)
                            NamedObj(production1.venue.value, row[Venues.name])
                        else null
                    } else null


                    it.respond(production1.toGet(worklist,castlist,venue))
                }
            }
            get().handler {

                transaction {

                    val response = it.response()
                    response.arrayResponse(


                            buildSequence {

                                val prods = Production.all().limit(it.limit(), it.offset()).toList()
                                for (p in prods) {
                                    val venue = p.venue
                                    yield(p.toGet(venue=NamedObj(p.venue.value)))
                                }
                            }.iterator()
                    )
                }
            }



            post().handler {
                updateObj(it)
            }
            put("/:id").handler {
                val id = it.pathParam("id").toInt()
                updateObj(it, id)
            }

        }
        return productionRouter
    }

    override fun updateObj(it: RoutingContext, id: Int?) {
        val ev = mapper.readValue<NewProd>(it.bodyAsString, NewProd::class.java)

        transaction {
            val p = if (id == null) Production.new {
                name = ev.name
            } else {

                Production2Work.deleteWhere { Production2Work.production.eq(id) }

                val production = Production[id]
                production.name = ev.name
                production
            }

            p.venue=EntityID(ev.venue,Venues)

            for (w in ev.works) {
                Production2Work.insert {
                    it[production] = p.id
                    it[work] = EntityID(w, Works)
                }
            }

            if (ev.cast!=null){
                ProductionPartAssignments.deleteWhere { ProductionPartAssignments.production.eq(p.id) }
                for (r in ev.cast){
                    ProductionPartAssignments.insert {
                        it[artist]= artist.fromInt(r.artist)
                        it[part]= part.fromInt(r.part)
                        it[production]=p.id
                    }
                }
            }

            val works = ev.works.map { Work(it, null) }
            it.respond(p.toGet(works,ev.cast?.map { it.toNamed() },NamedObj(p.venue.value)))
        }
    }
}