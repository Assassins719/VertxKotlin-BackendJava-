import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.transactions.transaction
import java.lang.Exception
import kotlin.coroutines.experimental.buildSequence

object ArtistController : BaseController() {
    data class Work(val id:Int, val name: String?)
    data class Get(val id: Int, val name: String,
                   val traits: List<ArtistTrait>,val works: List<Work>? = null,
                   val url:String?=null)

    data class Post(val name: String, val works: List<Work>,
                    val traits: List<ArtistTrait>? = null,
                    val url:String?=null)


    fun createRoute(vertx: Vertx): Router {

        val router = Router.router(vertx)

        router.run {

            fun getArtist(id: Int): Get {
                return transaction {
                    val select = AuthorRoles.leftJoin(Works).select { AuthorRoles.artist.eq(id) }

                    val authors = mutableMapOf<String, String>()
                    val works = mutableListOf<Work>()
                    val workNames = mutableListOf<String>()
                    for (row in select) {
                        val element = row[Works.id].value
                        works.add(Work(element,row[Works.name]))
                    }
                    val artist = Artists.select({ Artists.id.eq(id) }).first()
                    Get(id, artist[Artists.name],
                            ArtistTrait.fromMask(artist[Artists.traits]),
                            works,
                            url = artist[Artists.url])
                }
            }




            get("/:id").handler {
                transaction {

                    val id = it.pathParam("id").toInt()

                    it.respond(getArtist(id))
                }
            }
            get().handler {

                try {
                transaction {

                    val startsWith = it.qParam("startsWith")
                    val traits = it.queryParam("traits").firstOrNull()?.split(",")
                    val onlyComposers=traits != null && traits.map { ArtistTrait.valueOf(it) }.contains(ArtistTrait.Composer)

                    val res = buildSequence {

                        val conditions = buildSequence {
                            if (startsWith != null)
                                yield(Artists.name.upperCase().like(startsWith.toUpperCase() + "%"))
                            if (onlyComposers)
                                yield(Artists.traitComposer.eq(true))

                        }.toList()

                        val res = if (conditions.isEmpty()) Artist.all() else
                            Artist.find(conditions.reduce({ o1, o2 -> AndOp(o1, o2) }))
                        //else Artist.all()

                        for (p in res.limit(it.limit(), it.offset())) {
                            yield(Get(p.id.value,  p.name,
                                    ArtistTrait.fromMask(p.traits),url = p.url
                                    ))
                        }
                    }


                    it.response().arrayResponse(res.iterator())

                }
            } catch (e:Exception){
                    it.replyFail(e)
                }
            }


            addDefaultRoutes(this)
        }
        return router
    }

    override fun updateObj(it: RoutingContext, id: Int?) {
        val ev = mapper.readValue<Post>(it?.bodyAsString, Post::class.java)

        transaction {

            fun Artist.update(ev: Post) {
                name = ev.name
                if (ev.traits != null) {
                    traitComposer = ev.traits.contains(ArtistTrait.Composer)
                    traits=ArtistTrait.toMask(ev.traits)
                }
                url=ev.url
            }

            val ar = if (id != null) {
                val a = Artist[id]
                a.update(ev)
                a
            } else Artist.new {
                update(ev)
            }



            if (id != null) {
                AuthorRoles.deleteWhere { AuthorRoles.artist.eq(id) }
            }
            for (w in ev.works) {

                AuthorRoles.insert {
                    it[artist] = ar.id
                    it[work] = EntityID(w.id, Works)
                    it[name] = "Composer"
                }
            }
            it.respond(Get(ar.id.value, ar.name,  if (ar.traitComposer?:false) listOf(ArtistTrait.Composer) else listOf(),
                    ev.works))
        }
    }
}