package com.cwfgw.config

import com.comcast.ip4s.{Host, Port}

case class ServerConfig(host: String, port: Int):
  def http4sHost: Host = Host.fromString(host).getOrElse(Host.fromString("0.0.0.0").get)
  def http4sPort: Port = Port.fromInt(port).getOrElse(Port.fromInt(8080).get)

case class DatabaseConfig(driver: String, url: String, user: String, password: String, poolSize: Int)

case class AppConfig(server: ServerConfig, database: DatabaseConfig)

object AppConfig:
  import pureconfig.*

  given ConfigReader[ServerConfig] = ConfigReader.derived
  given ConfigReader[DatabaseConfig] = ConfigReader.derived
  given ConfigReader[AppConfig] = ConfigReader.derived

  def load: Either[pureconfig.error.ConfigReaderFailures, AppConfig] = ConfigSource.default.load[AppConfig]
