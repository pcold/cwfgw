package com.cwfgw.config

import com.comcast.ip4s.*

case class ServerConfig(host: String, port: Int):
  def http4sHost: Host = Host.fromString(host).getOrElse(ipv4"0.0.0.0")
  def http4sPort: Port = Port.fromInt(port).getOrElse(port"8080")

case class DatabaseConfig(driver: String, url: String, user: String, password: String, poolSize: Int)

case class AppConfig(server: ServerConfig, database: DatabaseConfig)

object AppConfig:
  import pureconfig.*

  given ConfigReader[ServerConfig] = ConfigReader.derived
  given ConfigReader[DatabaseConfig] = ConfigReader.derived
  given ConfigReader[AppConfig] = ConfigReader.derived

  def load: Either[pureconfig.error.ConfigReaderFailures, AppConfig] = ConfigSource.default.load[AppConfig]
