package pl.newicom.dddd.test.support

import com.typesafe.config.{Config, ConfigFactory}
import akka.actor.ActorSystem

object TestConfig {
  val config = ConfigFactory.parseString(
    """akka.loglevel = DEBUG
      |akka.loggers = ["akka.event.slf4j.Slf4jLogger"]
      |akka.logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"
      |akka.log-dead-letters-during-shutdown = "off"
      |akka.log-dead-letters = "off"
      |akka.log-config-on-start = "off"
      |akka.actor.debug.autoreceive = "off"
      |akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
      |akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      |app.view-store.config {
      |  profile = "slick.jdbc.H2Profile$"
      |  db {
      |    driver = "org.h2.Driver"
      |    url = "jdbc:h2:mem:view_update_sql_test;DB_CLOSE_DELAY=-1"
      |  }
      |}
    """.stripMargin)

  implicit def testSystem: ActorSystem = testSystem(TestConfig.config)

  def testSystem(config: Config) = {
    ActorSystem("Tests", config)
  }

}
