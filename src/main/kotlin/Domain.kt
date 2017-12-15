import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ColumnType
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.geom.Point2D
import java.math.BigDecimal

data class NewEvent(val name: String, val prodId: Int, val lat: Float?, val lon: Float?)

open class NamedDbObj: IntIdTable() {
    val name=varchar("name", length = 50)
}

object Works : IntIdTable() {
    val name = varchar("name", length = 50) // Column<String>
    init {
        index(columns = name)
    }
}
object Parts : NamedDbObj() {

    val work=(entityId("workId", Works)).references(Works.id)
}

class Work(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Work>(Works)
    var name by Works.name
}

object AuthorRoles:NamedDbObj(){
    val work=(entityId("work", Works)).references(Works.id)
    val artist=(entityId("artist", Artists)).references(Artists.id)
}
enum class ArtistTrait{
    Composer,
    Pianist,
    Singer;
    fun toMask():Int=(1 shl ordinal)
    companion object {
        fun fromMask(mask:Int):List<ArtistTrait> {

            val res= mutableListOf<ArtistTrait>()
            for (trait in values()) {
                if ((mask.and (1 shl trait.ordinal))!=0){
                    res.add(trait)

                }
            }
            return res
        }

        fun toMask(traits: List<ArtistTrait>): Int {
            var mask=0
            for (trait in traits) {
                mask=mask or (1 shl trait.ordinal)
            }
            return mask
        }
    }
}
object Artists:NamedDbObj(){
    val url = varchar("url",length = urlMaxLength).nullable()
    val traitComposer=bool("trait_composer").nullable()
    val traits=integer("trait")
}
class Artist(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Artist>(Artists)
    var name by Artists.name
    var traitComposer by Artists.traitComposer
    var traits by Artists.traits
    var url by Artists.url
}
data class JsonResponse(val data: Any)
//data class Location(val lat: Float, val lon: Float)
object Productions : NamedDbObj() {
    val buyNowLink = varchar("buyNowLink", length = 250).nullable()
    val venue=reference("venue",Venues)
}

class Production(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Production>(Productions)

    var name by Productions.name
    var venue by Productions.venue

    data class ProductionGet(val name:String, val id:Int, val workId:Int, val workName:String)

}
object Production2Work : IntIdTable() {
    val production =reference("production",Productions).index()
    val work =reference("work",Works)
}

object ProductionPartAssignments:IntIdTable(){
    val production =reference("production",Productions).index()
    val part =reference("part",Parts)
    val artist =reference("artist",Artists)
}

object EventPartAssignments:IntIdTable(){
    val event =reference("event",Events).index()
    val part =reference("part",Parts)
    val artist =reference("artist",Artists)
}
fun Column<EntityID<Int>>.fromInt(id:Int): EntityID<Int> {
    return EntityID<Int>(id, table as IdTable<Int>)
}
//class PointColumnType : ColumnType() {
//    override fun sqlType(): String = "point"
//
//    override fun valueFromDB(value: Any): Any {
//        return when (value) {
//            is Point2D.Float -> value
//            is String -> {
//                val regex = Regex("""\((.+),(.+)\)""")
//                val result = regex.find(value)
//                if (result != null) {
//                    val x = result.groupValues[1].toFloat()
//                    val y = result.groupValues[2].toFloat()
//                    return Point2D.Float(x, y)
//                } else
//                    error("Unexpected value : $value")
//            }
//            else -> error("Unexpected value of type Int: $value")
//        }
//    }
//}
class FloatColumnType() : ColumnType() {
    override fun sqlType(): String = "REAL"
    override fun valueFromDB(value: Any): Any {
        val valueFromDB = super.valueFromDB(value)
        return when (valueFromDB) {
            is BigDecimal -> valueFromDB.toFloat()
            is Double -> valueFromDB.toFloat()
            is Int -> valueFromDB
            is Long -> valueFromDB
            else -> valueFromDB
        }
    }
}
typealias MyPoint = Point2D.Float

fun Table.float(name: String): Column<Float> = registerColumn(name, FloatColumnType())

object Events : IntIdTable() {
    val name = varchar("name", length = 50) // Column<String>
    val production = (entityId("prod", Productions) references Productions.id) // Column<Int?>
    // val locationOverrideLat: Column<MyPoint> = registerColumn("location",PointColumnType())
    val venueOverride=(entityId("venue", Venues) references Venues.id).nullable()
    val date=datetime("date")
}
val urlMaxLength=500
object Venues: IntIdTable() {
    val name=varchar("name", length = 50)
    val locationLat = float("locLat").nullable()
    val locationLon = float("locLon").nullable()
    val url = varchar("url",urlMaxLength).nullable()
}

class Event(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<Event>(Events)

    var name by Events.name
    var production by Production referencedOn Events.production

    var date by Events.date
    var venue by Events.venueOverride
    //var city by City referencedOn Users.city


}

fun createTables(){
    transaction {
        val tables = arrayOf(Events, Productions, Works, Parts,
                Venues, Artists, AuthorRoles,
                Production2Work,
                ProductionPartAssignments,
                EventPartAssignments)
        SchemaUtils.drop(*tables)
        SchemaUtils.create(*tables)
    }
}