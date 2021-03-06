import scala.collection.immutable.ListSet

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.FiniteDuration

import reactivemongo.bson._
import reactivemongo.api.collections.bson.BSONCollection

import org.specs2.concurrent.{ ExecutionEnv => EE }

class AggregationSpec extends org.specs2.mutable.Specification {
  "Aggregation framework" title

  import Common._

  sequential

  val zipColName = s"zipcodes${System identityHashCode this}"
  lazy val coll = {
    import ExecutionContext.Implicits.global
    import reactivemongo.api.indexes._, IndexType._

    val c = db(zipColName)
    scala.concurrent.Await.result(c.indexesManager.ensure(Index(
      List("city" -> Text, "state" -> Text))).map(_ => c), timeout * 2)
  }
  lazy val slowColl = slowDb(zipColName)

  case class Location(lon: Double, lat: Double)

  case class ZipCode(_id: String, city: String, state: String,
                     population: Long, location: Location)

  implicit val locationHandler = Macros.handler[Location]
  implicit val zipCodeHandler = Macros.handler[ZipCode]

  private val jpCodes = List(
    ZipCode("JP 13", "TOKYO", "JP", 13185502L,
      Location(35.683333, 139.683333)),
    ZipCode("AO", "AOGASHIMA", "JP", 200L, Location(32.457, 139.767)))

  private val zipCodes = List(
    ZipCode("10280", "NEW YORK", "NY", 19746227L,
      Location(-74.016323, 40.710537)),
    ZipCode("72000", "LE MANS", "FR", 148169L,
      Location(48.0077, 0.1984))) ++ jpCodes

  "Zip codes collection" should {
    "be inserted" in { implicit ee: EE =>
      def insert(data: List[ZipCode]): Future[Unit] = data.headOption match {
        case Some(zip) => coll.insert(zip).flatMap(_ => insert(data.tail))
        case _         => Future.successful({})
      }

      insert(zipCodes) aka "insert" must beEqualTo({}).await(1, timeout) and (
        coll.count() aka "count #1" must beEqualTo(4).await(1, slowTimeout)).
        and(slowColl.count() aka "count #2" must beEqualTo(4).
          await(1, slowTimeout))

    }

    "return states with populations above 10000000" >> {
      // http://docs.mongodb.org/manual/tutorial/aggregation-zip-code-data-set/#return-states-with-populations-above-10-million
      val expected = List(document("_id" -> "JP", "totalPop" -> 13185702L),
        document("_id" -> "NY", "totalPop" -> 19746227L))

      def withRes[T](c: BSONCollection)(f: Future[List[BSONDocument]] => T)(implicit ec: ExecutionContext) = {
        import c.BatchCommands.AggregationFramework
        import AggregationFramework.{ Group, Match, SumField }

        f(c.aggregate(Group(BSONString("$state"))(
          "totalPop" -> SumField("population")), List(
          Match(document("totalPop" ->
            document("$gte" -> 10000000L))))).map(_.firstBatch))
      }

      "with the default connection" in { implicit ee: EE =>
        withRes(coll) {
          _ aka "results" must beEqualTo(expected).await(1, timeout)
        }
      }

      "with the slow connection" in { implicit ee: EE =>
        withRes(slowColl) {
          _ aka "results" must beEqualTo(expected).await(1, slowTimeout)
        }
      }
    }

    "explain simple result" in { implicit ee: EE =>
      val expected = List(document("_id" -> "JP", "totalPop" -> 13185702L),
        document("_id" -> "NY", "totalPop" -> 19746227L))

      import coll.BatchCommands.AggregationFramework
      import AggregationFramework.{ Group, Match, SumField }

      coll.aggregate(Group(BSONString("$state"))(
        "totalPop" -> SumField("population")), List(
        Match(document("totalPop" ->
          document("$gte" -> 10000000L)))), explain = true).map(_.firstBatch).
        aka("results") must beLike[List[BSONDocument]] {
          case explainResult :: Nil =>
            explainResult.getAs[BSONArray]("stages") must beSome
        }.await(1, timeout)
    }

    "return average city population by state" >> {
      // See http://docs.mongodb.org/manual/tutorial/aggregation-zip-code-data-set/#return-average-city-population-by-state
      val expected = List(document("_id" -> "NY", "avgCityPop" -> 19746227D),
        document("_id" -> "FR", "avgCityPop" -> 148169D),
        document("_id" -> "JP", "avgCityPop" -> 6592851D))

      def withCtx[T](c: BSONCollection)(f: (c.BatchCommands.AggregationFramework.Group, List[c.PipelineOperator]) => T): T = {
        import c.BatchCommands.AggregationFramework
        import AggregationFramework.{ Cursor, Group, SumField }

        val firstOp = Group(document("state" -> "$state", "city" -> "$city"))(
          "pop" -> SumField("population"))

        val pipeline = List(
          Group(BSONString("$_id.state"))("avgCityPop" ->
            AggregationFramework.Avg("pop")))

        f(firstOp, pipeline)
      }

      "successfully as a single batch" in { implicit ee: EE =>
        withCtx(coll) { (firstOp, pipeline) =>
          coll.aggregate(firstOp, pipeline).map(_.firstBatch).
            aka("results") must beEqualTo(expected).await(1, timeout)
        }
      }

      "with cursor" >> {
        def collect(c: BSONCollection, upTo: Int = Int.MaxValue)(implicit ec: ExecutionContext) = withCtx(c) { (firstOp, pipeline) =>
          c.aggregate1[BSONDocument](firstOp, pipeline,
            c.BatchCommands.AggregationFramework.Cursor(1)).
            flatMap(_.collect[List](upTo))
        }

        "without limit (maxDocs)" in { implicit ee: EE =>
          collect(coll) aka "cursor result" must beEqualTo(expected).
            await(1, timeout)
        }

        "with limit (maxDocs)" in { implicit ee: EE =>
          collect(coll, 2) aka "cursor result" must beEqualTo(expected take 2).
            await(1, timeout)
        }

        "with metadata sort" in { implicit ee: EE =>
          import coll.BatchCommands.AggregationFramework
          import AggregationFramework.{
            Descending,
            Cursor,
            Match,
            MetadataSort,
            Sort,
            TextScore
          }

          val firstOp = Match(BSONDocument(
            "$text" -> BSONDocument("$search" -> "JP")))

          val pipeline = List(Sort(
            MetadataSort("score", TextScore), Descending("city")))

          coll.aggregate1[ZipCode](firstOp, pipeline, Cursor(1)).
            flatMap(_.collect[List](4)) must beEqualTo(jpCodes).
            await(1, timeout)

        }
      }
    }

    "return largest and smallest cities by state" in { implicit ee: EE =>
      // See http://docs.mongodb.org/manual/tutorial/aggregation-zip-code-data-set/#return-largest-and-smallest-cities-by-state
      import coll.BatchCommands.AggregationFramework
      import AggregationFramework.{
        First,
        Group,
        Last,
        Project,
        Sort,
        Ascending,
        Sample,
        SumField
      }

      val expected = List(document(
        "biggestCity" -> document(
          "name" -> "NEW YORK", "population" -> 19746227L),
        "smallestCity" -> document(
          "name" -> "NEW YORK", "population" -> 19746227L),
        "state" -> "NY"),
        document(
          "biggestCity" -> document(
            "name" -> "LE MANS", "population" -> 148169L),
          "smallestCity" -> document(
            "name" -> "LE MANS", "population" -> 148169L),
          "state" -> "FR"), document(
          "biggestCity" -> document(
            "name" -> "TOKYO", "population" -> 13185502L),
          "smallestCity" -> document(
            "name" -> "AOGASHIMA", "population" -> 200L),
          "state" -> "JP"))

      coll.aggregate(
        Group(document("state" -> "$state", "city" -> "$city"))(
          "pop" -> SumField("population")),
        List(Sort(Ascending("population")),
          Group(BSONString("$_id.state"))(
            "biggestCity" -> Last("_id.city"),
            "biggestPop" -> Last("pop"),
            "smallestCity" -> First("_id.city"),
            "smallestPop" -> First("pop")),
          Project(document("_id" -> 0, "state" -> "$_id",
            "biggestCity" -> document(
              "name" -> "$biggestCity", "population" -> "$biggestPop"),
            "smallestCity" -> document(
              "name" -> "$smallestCity", "population" -> "$smallestPop"))))).
        map(_.firstBatch) aka "results" must beEqualTo(expected).
        await(1, timeout)
    }

    "return distinct states" >> {
      def distinctSpec(c: BSONCollection, timeout: FiniteDuration)(implicit ee: EE) = c.distinct[String, ListSet]("state").
        aka("states") must beEqualTo(ListSet("NY", "FR", "JP")).
        await(1, timeout)

      "with the default connection" in { implicit ee: EE =>
        distinctSpec(coll, timeout)
      }

      "with the slow connection" in { implicit ee: EE =>
        distinctSpec(slowColl, slowTimeout)
      }
    }

    "return a random sample" in { implicit ee: EE =>
      import coll.BatchCommands.AggregationFramework

      coll.aggregate(AggregationFramework.Sample(2)).map(_.head[ZipCode].
        filter(zipCodes.contains).size) must beEqualTo(2).await(1, timeout)
    } tag "not_mongo26"
  }
}
