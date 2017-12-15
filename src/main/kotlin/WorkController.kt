import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.TemplateHandler
import io.vertx.ext.web.templ.ThymeleafTemplateEngine
import org.jetbrains.exposed.dao.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.coroutines.experimental.buildSequence

object WorkController : BaseController() {
    override fun updateObj(it: RoutingContext, id: Int?) {

        val body = it.bodyAsString
        val ev = mapper.readValue<WorkPost>(body, WorkPost::class.java)

        transaction {
            val p = if (id == null) {
                val work=Work.new {
                    name = ev.name


                }
                ev.parts.forEach { part ->
                    Parts.insert {
                        it[name] = part.name
                        it[Parts.work]=work.id
                    }
                }
                work

            }
            else {
                val work = Work[id]
                work.name = ev.name

                if (ev.authors != null) {
                    AuthorRoles.deleteWhere { AuthorRoles.work.eq(id) }
                }
                //remove deleted parts from db.
                val allParts=ev.parts.filter { it.id!=null }.map { it.id }
                Parts.deleteWhere { Parts.work.eq(work.id).and(Parts.id.notInList(allParts)) }

                for (part in ev.parts) {
                    if (part.id!=null){
                        Parts.update({Parts.id.eq(part.id)},body={
                            it[Parts.name]=part.name
                        })
                    }else
                    {
                        //insert missing
                        Parts.insert {
                            it[name] = part.name
                            it[Parts.work]=work.id
                        }
                    }
                }
                work
            }



            ev.authors?.forEach { role ->
                AuthorRoles.insert {
                    it[name] = role.role
                    it[work] = p.id
                    it[artist] = EntityID(role.artist, Artists)
                }

            }
            it.respond(p.get(authors = ev.authors))
        }
    }

    data class Part(val id:Int?,val name:String)
    data class WorkGet(val id: Int, val name: String, val parts: List<Part>?=null,
                       val authors: List<AuthorRole>?)

    data class AuthorRole(val artist: Int, val role: String,val artistName:String?=null)
    data class WorkPost(val name: String, val parts: List<Part>, val authors: List<AuthorRole>?)

    fun Work.get(includeParts: Boolean = false, authors: List<AuthorRole>?): WorkGet {

        val parts = if (includeParts) Parts.select({
            Parts.work.eq(id)
        }).map { Part(it[Parts.id].value,it[Parts.name]) } else null

        return WorkGet(id.value, name, parts, authors)
    }


    class MyHandler(val templateEngine: ThymeleafTemplateEngine, val path: String) : Handler<RoutingContext> {

        override fun handle(ctx: RoutingContext) {

            when (ctx.request().method()) {
                HttpMethod.POST -> {

                }
            }
            templateEngine.thymeleafTemplateEngine.clearTemplateCache()
            val file = File(ctx.request().path())
            templateEngine.render(ctx, "templates/$path/", file.parentFile.name + ".html", { res ->
                if (res.succeeded()) {
                    ctx.response().end(res.result());
                } else {
                    ctx.fail(res.cause());
                }
            })
        }

    }

    fun createRoute(vertx: Vertx): Router {

        val productionRouter = Router.router(vertx)

        productionRouter.run {

            fun workGet(id: Int): WorkGet {
                return transaction {
                    val select = AuthorRoles.leftJoin(Artists).select { AuthorRoles.work.eq(id) }

                    val authors=select.map { row -> AuthorRole(row[Artists.id].value,row[AuthorRoles.name],row[Artists.name]) }
                    val work = Work[id]
                    work.get(true, authors)
                }
            }




            get("/:id").handler {
                transaction {

                    val id = it.pathParam("id").toInt()
                    val select = AuthorRoles.leftJoin(Artists).select { AuthorRoles.work.eq(id) }

                    val authors=select.map { row -> AuthorRole(row[Artists.id].value,row[AuthorRoles.name],row[Artists.name]) }


                    val work = Work[id]
                    it.respond(work.get(true, authors))
                }
            }
            get().handler {

                transaction {


                    val res = buildSequence {
                        val startsWith = it.qParam("startsWith")
                        val composer = it.qParam("creator")?.toInt()

                        var cond = EqOp(booleanLiteral(true), booleanLiteral(true))
                        val query=if (composer != null) {
                            val query = Works.leftJoin(AuthorRoles).select {
                                val compSelect = AuthorRoles.artist.eq(composer)
                                if (startsWith != null) {
                                    return@select compSelect.and(Works.name.upperCase().like(startsWith.toUpperCase() + "%"))
                                } else
                                    return@select compSelect
                            }
                            query
                        }
                        else{
                            val query=if (startsWith != null) {
                                Works.select(Works.name.upperCase().like(startsWith.toUpperCase() + "%"))
                            } else
                                Works.selectAll()
                            query
                        }



                        for (p in query.limit(it.limit(), it.offset())) {
                            yield(WorkGet(p[Works.id].value,p[Works.name],authors = null))
                        }
                    }


                    it.response().arrayResponse(res.iterator())

                }
            }


            addDefaultRoutes(this)
        }
        return productionRouter
    }
}